# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""C and C++: `//` and `/* */` comments, outside string, character and raw string literals.

Ported from CGM's _lang_c_like (its literal scan and raw strings), with the 2026-10-09 counterexamples fixed
using the edition's qualified regression fixtures:
  - `int/**/value` became `intvalue`: a removed comment with code after it is one space, as in
    translation phase 3 (C23 5.1.1.2; GCC "Initial processing");
  - a raw string lost one of its blank lines: literals are never compacted.
From engine 0.1.20 a comment-only line goes completely, as in every other language, and the authored empty
lines stay as they are: the lean line numbers differ from the authored ones, with no `#line` and no empty
line where a comment was, and dpkg-stereo --resolve finds a lean location's authored line by its content
(Daniel, 2026-11-01: "simple - pick the line content and search it on the source"). Engines 0.1.17 to 0.1.19
kept every authored line (a removed comment left its line breaks), and engines up to 0.1.15 wrote `#line`
after every removed run; GCC paid for both (ORDER 303, ORDER 334). A comment with code before and after it
that holds line breaks becomes one line break, so the two pieces of code keep lines of their own, except
where a line break would end a directive early or start one (a `#` after a comment that began after code):
there it is one space, which is what the comment is to the compiler. A continuation stays one: an emptied
line that ended a backslash continuation takes that backslash with it.
Also: a `//` comment ending in a backslash continues on the next line (phase 2 splices it); C++14
digit separators (1'000) are not character literals; a lone quote in `#error` text is not a literal.
Kept comments: notices, the pointer line, and the fall-through comments GCC's -Wimplicit-fallthrough
reads. The pointer goes on a new first line, as in the other languages.
The check runs GCC's own preprocessor: with -fpreprocessed (no expansion) both texts give the same
tokens and the same directives; where the file preprocesses on its own, full preprocessing gives the same
tokens with `__LINE__` pinned to one value (a file whose own code expands `__LINE__` is named in the check,
since there its values follow the lean lines); and the lean text holds no line control (`#line` or a line
marker) the authored text did not.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile

from .core import COMMENT, KEEP, LITERAL, Span, held_breaks, is_notice, is_pointer

KEY = 'c'
EXTS = frozenset({'.h', '.hh', '.hpp', '.hxx', '.c', '.cc', '.cpp', '.cxx', '.inl'})
# assembler sources the C preprocessor reads first (GCC: "file.S, file.sx: Assembler code that must be preprocessed";
# matched with their case: a `.s` file is never preprocessed)
ASM_EXTS = frozenset({'.S', '.sx'})
ASM = 'assembler-with-cpp'
# GCC and clang define __ASSEMBLER__ only when they preprocess assembler (Linux spells its own __ASSEMBLY__)
ASM_GUARD = re.compile(r'\b__ASSEMBL(?:ER|Y)__\b')
GLSL_EXTS = frozenset({'.glsl', '.vert', '.frag', '.geom', '.comp', '.tesc', '.tese'})
MARKER = '//'
FALLTHROUGH = re.compile(r'fall(s| |-)?thr(ough|u)|\bnotreached\b|\blint\b', re.I)
RAW_PREFIX = ('R', 'u8R', 'uR', 'UR', 'LR')
RAW_OPEN = re.compile(r'"([^()\\\s]{0,16})\(')
# an #include directive's header-name between < and > is one token (C23 6.4.7): no comment starts inside it
HEADER_NAME = re.compile(r'#[ \t]*(?:include(?:_next)?|import|embed)[ \t]*<[^>\n]*>')
# line control (`#line`, C17 6.10.4) and GCC's line markers: the lean text must hold none the authored text lacks
LINE_CONTROL = re.compile(r'^[ \t]*(?:#|%:)[ \t]*(?:line\b|[0-9])', re.M)
# C++20 module and import lines are directives too ([cpp.pre], [cpp.module], [cpp.import]): line breaks end them
WORD = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
LINE_WORDS = frozenset({'import', 'module', 'export'})


def keep_empty_after(line: str) -> bool:
    k, n = len(line), 0
    while k > 0 and line[k - 1] == '\\':
        n += 1
        k -= 1
    return n % 2 == 1


def joiner(src: str, start: int, end: int) -> str:
    return ' '


def joiner_for(src: str, spans: list, asm: bool = False):
    """The joiner for a removed comment with code after it on its line (core.render drop_lines): one space
    when it holds no line break a backslash does not splice (translation phase 3), or when its line breaks
    lie in an open directive or would start one (break_holders); otherwise one line break, so the code
    before it and the code after it keep lines of their own (the renderer makes it one space where nothing
    precedes the comment on its line). For a file the assembler preprocessor reads (asm) it is always one
    space, as the preprocessor makes it: the assembler reads one statement per line."""
    holders = break_holders(src, spans)

    def join(s, start, end):
        if asm or start in holders or '\n' not in held_breaks(s, start, end).replace('\\\n', ''):
            return ' '
        return '\n'
    return join


def blanked(text: str) -> str:
    """The text with its comments and literals blanked, line breaks and offsets kept: its code."""
    buf = list(text)
    for sp in lex(text):
        for k in range(sp.start, sp.end):
            if buf[k] != '\n':
                buf[k] = ' '
    return ''.join(buf)


def assembler_evidence(text: str, variant: str) -> str | None:
    """Why the assembler preprocessor reads this file, or None: an assembler source (.S, .sx), or code that
    tests __ASSEMBLER__ or __ASSEMBLY__ (a header written to be included from assembler sources too)."""
    if variant == 'asm':
        return 'an assembler source (.S, .sx)'
    if ASM_GUARD.search(blanked(text)):
        return 'its code tests __ASSEMBLER__ or __ASSEMBLY__'
    return None


def break_holders(src: str, spans: list) -> set:
    """Starts of the removable comments whose line breaks must not become a line break. A preprocessing
    directive is one logical line, opened by a `#` (or `%:`) that is its first token (C23 6.10; in C++20
    also `import`, `module` and `export` lines, taken as directives here whatever follows them): a line
    break ends it, a comment's line breaks do not (a backslash-newline joins lines before either). So a
    comment that holds a line break no backslash splices, lies in an open directive and has code after it
    on its last line, or follows code and is followed by a `#` (or one of those words) on its last line
    (that is no directive in the authored text), becomes one space, which is what it is to the compiler."""
    out = set()
    n = len(src)
    at_span = {sp.start: sp for sp in spans}
    in_dir = False      # the logical line is a directive
    has_tok = False     # the logical line has a token before this point
    i = 0
    while i < n:
        sp = at_span.get(i)
        if sp is not None and sp.end > i:
            if sp.kind == COMMENT and '\n' in held_breaks(src, sp.start, sp.end).replace('\\\n', ''):
                if in_dir and not _blank_tail(src, sp.end):
                    out.add(sp.start)
                elif has_tok and _directive_after(src, sp.end, at_span):
                    out.add(sp.start)
            if sp.kind == LITERAL:
                has_tok = True
            i = sp.end
            continue
        c = src[i]
        if c == '\\' and src.startswith('\n', i + 1):
            i += 2              # phase 2: a backslash-newline splices two lines into one logical line
            continue
        if c == '\n':
            in_dir = has_tok = False
        elif c not in ' \t\f\v\r':
            if not has_tok and _opens_directive(src, i):
                in_dir = True
            has_tok = True
        i += 1
    return out


def _opens_directive(src: str, i: int) -> bool:
    if src[i] == '#' or src.startswith('%:', i):
        return True
    m = WORD.match(src, i)
    return bool(m) and m.group() in LINE_WORDS


def _blank_tail(src: str, end: int) -> bool:
    nl = src.find('\n', end)
    return src[end: nl if nl >= 0 else len(src)].strip(' \t\f\v') == ''


def _directive_after(src: str, end: int, at_span: dict) -> bool:
    """True when the first token after `end` on its logical line could open a directive."""
    i, n = end, len(src)
    while i < n:
        sp = at_span.get(i)
        if sp is not None and sp.kind in (COMMENT, KEEP) and sp.end > i:
            i = sp.end
            continue
        c = src[i]
        if c == '\\' and src.startswith('\n', i + 1):
            i += 2
            continue
        if c in ' \t\f\v\r':
            i += 1
            continue
        return _opens_directive(src, i)
    return False


def lex(src: str) -> list:
    s, n = src, len(src)
    spans = []
    i = 0
    while i < n:
        c = s[i]
        nx = s[i + 1] if i + 1 < n else ''
        if c == '#':
            k = i - 1
            while k >= 0 and s[k] in ' \t':
                k -= 1
            if k < 0 or s[k] == '\n':
                m = HEADER_NAME.match(s, i)
                if m:
                    i = m.end()
                    continue
        if c == '/' and nx == '/':
            j = i + 2
            while j < n:
                if s[j] == '\n':
                    # a backslash-newline continues the comment (splicing comes before comments)
                    k, bs = j - 1, 0
                    while k > i and s[k] == '\\':
                        bs += 1
                        k -= 1
                    if bs % 2 == 1:
                        j += 1
                        continue
                    break
                j += 1
            _comment(spans, s, i, j)
            i = j
            continue
        if c == '/' and nx == '*':
            j = s.find('*/', i + 2)
            j = n if j < 0 else j + 2
            _comment(spans, s, i, j)
            i = j
            continue
        if c.isalpha() or c == '_':
            j = i
            while j < n and (s[j].isalnum() or s[j] == '_'):
                j += 1
            word = s[i:j]
            if j < n and s[j] == '"' and word in RAW_PREFIX:
                m = RAW_OPEN.match(s, j)
                if m:
                    end = s.find(')' + m.group(1) + '"', m.end())
                    if end >= 0:
                        end += len(m.group(1)) + 2
                        spans.append(Span(i, end, LITERAL, 'raw string literal'))
                        i = end
                        continue
            i = j
            continue
        if c.isdigit() or (c == '.' and nx.isdigit()):
            # a preprocessing number (C23 6.4.8): digits, letters, '.', signs after e/p, and C++14 digit separators
            j = i + 1
            while j < n:
                d = s[j]
                if d.isalnum() or d in '._':
                    j += 1
                elif d in '+-' and s[j - 1] in 'eEpP':
                    j += 1
                elif d == "'" and j + 1 < n and (s[j + 1].isalnum() or s[j + 1] == '_'):
                    j += 1
                else:
                    break
            i = j
            continue
        if c in '"\'':
            j = i + 1
            closed = False
            while j < n and s[j] != '\n':
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == c:
                    closed = True
                    break
                j += 1
            i = j + 1 if closed else i + 1   # a lone quote (as in #error text) is just a character
            continue
        i += 1
    return spans


def _comment(spans, s, i, j):
    text = s[i:j]
    if is_pointer(text):
        spans.append(Span(i, j, KEEP, 'pointer line'))
    elif FALLTHROUGH.search(text) and len(text) < 80:
        spans.append(Span(i, j, KEEP, 'read by the compiler (-Wimplicit-fallthrough) or lint'))
    elif is_notice(text):
        spans.append(Span(i, j, KEEP, 'licence or copyright notice'))
    else:
        spans.append(Span(i, j, COMMENT))


def refuse(src: str):
    if src.startswith('\ufeff'):
        return 'UTF-8 byte-order mark requires qualified pointer placement'
    if '\0' in src:
        return 'carries binary data'
    if '??/' in src:
        return 'uses trigraphs'
    if re.search(r'^[ \t]*#[ \t]*(?:cmakedefine(?:01)?|mesondefine)\b', src, re.M):
        return 'build-system template requires generation before C qualification'
    if re.search(r'^[ \t]*#!', src, re.M):
        # Some sources retain a generator in a disabled #if branch. Its shell
        # quoting/comment rules are not C rules; preserve the entire mixed file.
        return 'contains an embedded interpreter script'
    for span in lex(src):
        if span.kind not in (COMMENT, KEEP):
            continue
        text = src[span.start:span.end]
        if ((text.startswith(('/**', '/*!')) and len(text) > 4)
                or text.startswith(('///', '//!', '//:', '//=', '//~', '/*<'))):
            # These comments feed documentation, bindings or translations.
            # Even retaining the comment while emptying lines between it and
            # its declaration can break a textual consumer. Leave the entire
            # file unchanged until that consumer has a qualified adapter.
            return 'structured documentation or annotations require consumer qualification'
    return None


def _cpp(args, text, lang):
    return _cpp_why(args, text, lang)[0]


def _env():
    env = dict(os.environ, SOURCE_DATE_EPOCH='0', LANGUAGE='')
    if 'LC_ALL' in env:            # the messages in English (their kind is read), the character set kept
        env.setdefault('LC_CTYPE', env.pop('LC_ALL'))
    env['LC_MESSAGES'] = 'C'
    return env


def _cpp_why(args, text, lang):
    """(GCC's output, None) or (None, GCC's messages)."""
    gcc = shutil.which('gcc') or shutil.which('cc')
    if not gcc:
        return None, 'gcc unavailable'
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, 'f.' + ('cc' if lang == 'c++' else 'c'))
        with open(p, 'w', encoding='utf-8', errors='surrogateescape') as f:
            f.write(text)
        # Both sides must have the same logical filename and clock. Otherwise
        # __FILE__ (including uses hidden in system-header macros), __BASE_FILE__
        # and time macros produce differences caused only by our test fixtures.
        os.utime(p, (0, 0))
        r = subprocess.run([gcc, '-E', '-x', lang, *args, os.path.basename(p)],
                           cwd=d, env=_env(), capture_output=True, timeout=120)
        if r.returncode != 0:
            return None, r.stderr.decode('utf-8', 'replace')
        return r.stdout.decode('utf-8', 'surrogateescape'), None


def _units(pp: str) -> list:
    out = []
    for line in pp.split('\n'):
        t = line.strip()
        if not t or re.match(r'#\s*line\b', t) or re.match(r'#\s+\d+\s', t):
            continue
        if t.startswith('#'):
            out.append(' '.join(t.split()))
        else:
            out.extend(t.split())
    return out


def _tokens(pp: str) -> list:
    """The tokens of full preprocessing output (GCC -E), line markers aside. Its line layout follows the
    source lines (a `#` that follows a removed multi-line comment may start an output line in one text and
    not in the other), so only the token sequence is compared; the directive structure is the -fpreprocessed
    comparison's (_units)."""
    out = []
    for line in pp.split('\n'):
        t = line.strip()
        if re.match(r'#\s*line\b', t) or re.match(r'#\s+\d+(\s|$)', t):
            continue
        out.extend(t.split())
    return out


def _splice(text: str) -> str:
    """Translation phase 2 (C23 5.1.1.2): a backslash-newline joins two lines. GCC's -fpreprocessed
    mode skips it, so the check does it first, the same way for both texts."""
    return text.replace('\\\n', '')


def _line_controls(text: str) -> int:
    """How many line-control directives (`#line N`, `# N "file"`) the text's code holds, with comments and
    literals blanked and lines spliced."""
    buf = list(text)
    for sp in lex(text):
        for k in range(sp.start, sp.end):
            if buf[k] != '\n':
                buf[k] = ' '
    return len(LINE_CONTROL.findall(_splice(''.join(buf))))


# `__LINE__` pinned to one value for the full-preprocessing comparison: the lean lines differ from the authored
# ones on purpose, and every other token must stay the same
PIN_LINE = ('-U__LINE__', '-D__LINE__=0')
# the compiler's own line builtins (std::source_location reads them): no preprocessor can pin them
LINE_BUILTIN = re.compile(r'\b__builtin_(?:LINE|source_location)\b')


def line_uses(text: str, lang: str, pinned: str | None | bool = False) -> list:
    """What makes this file's own code carry line numbers: `__LINE__` expanded by its full preprocessing
    (the pinned and the free output differ; nothing to say where the file does not preprocess on its own),
    and the compiler's line builtins in its code."""
    out = []
    if pinned is False:          # not run yet (None: run, and the file does not preprocess on its own)
        pinned = _cpp(['-w', *PIN_LINE], text, lang)
    free = _cpp(['-w'], text, lang) if pinned is not None else None
    if free is not None and _tokens(free) != _tokens(pinned):
        out.append('__LINE__')
    buf = list(text)
    for sp in lex(text):
        for k in range(sp.start, sp.end):
            if buf[k] != '\n':
                buf[k] = ' '
    if LINE_BUILTIN.search(''.join(buf)):
        out.append('__builtin_LINE')
    return out


def _lines(pp: str) -> list:
    """The non-empty lines of preprocessed output, line markers aside, blanks normalised: an assembler reads one
    statement per line, so its preprocessed lines are compared, not only its tokens."""
    out = []
    for line in pp.split('\n'):
        t = line.strip()
        if not t or re.match(r'#\s*line\b', t) or re.match(r'#\s+\d+(\s|$)', t):
            continue
        out.append(' '.join(t.split()))
    return out


def _gcc_mode(original, lean, mode, a):
    """GCC's comparison in one mode, the authored reading `a` given: (True, detail, full preprocessing of the
    authored text or None) or (False, why, None)."""
    b, _ = _cpp_why(['-fpreprocessed', '-dD', '-P'], _splice(lean), mode)
    name = 'GCC' if mode in ('c', 'c++') else f'GCC ({mode})'
    if b is None:
        return False, f'{name} could not read the lean file', None
    if _units(a) != _units(b):
        return False, f'{name} sees different tokens or directives', None
    if mode == ASM and _lines(a) != _lines(b):
        return False, f'{name} sees different lines (an assembler statement is a line)', None
    fa = _cpp(['-w', *PIN_LINE], original, mode)
    if fa is not None:
        fb = _cpp(['-w', *PIN_LINE], lean, mode)
        if fb is None or _tokens(fa) != _tokens(fb) or (mode == ASM and _lines(fa) != _lines(fb)):
            return False, f'{name}: full preprocessing differs (its tokens, with __LINE__ pinned)', None
        return True, f'{name}: same tokens and directives, and the same full preprocessing with __LINE__ pinned', fa
    return True, f'{name}: same tokens and directives (the file does not preprocess on its own)', None


# GCC's -fpreprocessed reading takes every group of a conditional, the groups no consumer on this target takes
# included, and still checks or runs some directives there: a `#` in a function-like macro that names no
# parameter (assembler immediates), #error, a misspelt directive name, a `#pragma GCC target` naming another
# architecture's options. C23 6.10.1: in a skipped group "directives are processed only through the name that
# determines the directive"; the consumers that take such a group are other targets or configurations, and
# what they all read alike is the token and directive stream.
GCC_LOCATED = re.compile(r'^f\.(?:c|cc):(\d+):\d+: (?:fatal )?error: (.*)$', re.M)
GCC_TARGET = re.compile(r"^cc1(?:plus)?: error: (attribute .target. argument .[^\n]*. is unknown)$", re.M)
CONDITIONAL = re.compile(r'^[ \t]*(?:#|%:)[ \t]*(if|ifdef|ifndef|elif|elifdef|elifndef|else|endif)\b')
PRAGMA_TARGET = re.compile(r'^[ \t]*(?:#|%:)[ \t]*pragma[ \t]+GCC[ \t]+target\b')


def conditional_lines(text: str) -> set:
    """1-based lines of `text` that lie inside a conditional group, its include guard aside (the outer
    #ifndef X / #define X ... #endif that spans the file, which every consumer takes once)."""
    lines = blanked(text).split('\n')
    directives = [(k, m.group(1)) for k, ln in enumerate(lines, 1) for m in [CONDITIONAL.match(ln)] if m]
    other = [k for k, ln in enumerate(lines, 1) if re.match(r'^[ \t]*(?:#|%:)', ln)]
    guard = None
    if directives and directives[0][1] in ('ifndef', 'if') and other and other[0] == directives[0][0]:
        m = re.match(r'^[ \t]*#[ \t]*(?:ifndef[ \t]+(\w+)|if[ \t]+!\s*defined\s*\(?\s*(\w+)\s*\)?)[ \t]*$',
                     lines[directives[0][0] - 1])
        if m and len(other) > 1 and re.match(r'^[ \t]*#[ \t]*define[ \t]+' + re.escape(m.group(1) or m.group(2)) + r'\b',
                                             lines[other[1] - 1]):
            depth = 0
            for k, kind in directives:
                depth += kind in ('if', 'ifdef', 'ifndef')
                depth -= kind == 'endif'
                if depth == 0:
                    if k == directives[-1][0] and k == other[-1]:
                        guard = (directives[0][0], k)
                    break
    inside, depth = set(), 0
    at = {k: kind for k, kind in directives}
    for k in range(1, len(lines) + 1):
        kind = at.get(k)
        if guard and k in guard:
            continue
        if kind in ('if', 'ifdef', 'ifndef'):
            depth += 1
            continue
        if kind == 'endif':
            depth -= 1
            continue
        if kind is not None:
            continue                 # #else and #elif lines: their condition is every consumer's
        if depth > 0:
            inside.add(k)
    return inside


def gcc_objections_in_groups(text: str, why: str) -> bool:
    """True when every error GCC's -fpreprocessed reading of `text` (spliced, as GCC read it) names is at a line
    inside a conditional group, a `#pragma GCC target` naming options this host's target lacks counting at its
    own lines: what this host refuses is a group other consumers take."""
    inside = conditional_lines(text)
    lines = {int(m.group(1)) for m in GCC_LOCATED.finditer(why or '')}
    if GCC_TARGET.search(why or ''):
        lines |= {k for k, ln in enumerate(blanked(text).split('\n'), 1) if PRAGMA_TARGET.match(ln)}
    other = [ln for ln in (why or '').split('\n') if ': error:' in ln and not GCC_LOCATED.match(ln)
             and not GCC_TARGET.match(ln)]
    return bool(lines) and not other and lines <= inside


RAW_END = re.compile(r'\tLoc=<f\.(?:c|cc|S):\d+:\d+>\n')
RAW_FLAGS = re.compile(r"(?: \[(?:UnClean='.*'|[A-Za-z]+)\])*", re.S)


def _raw_records(out: str):
    """(kind, spelling) of each record clang -cc1 -dump-raw-tokens prints: `kind 'spelling'<TAB>[ flags]<TAB>Loc`."""
    at = 0
    for m in RAW_END.finditer(out):
        piece, at = out[at:m.start()], m.end()
        kind, sep, rest = piece.partition(" '")
        if not sep or not re.fullmatch(r'\w+', kind):
            raise ValueError('clang printed a token this reader does not parse')
        k = rest.find("'\t")
        while k >= 0 and not RAW_FLAGS.fullmatch(rest[k + 2:]):
            k = rest.find("'\t", k + 1)
        if k < 0:
            raise ValueError('clang printed a token this reader does not parse')
        yield kind, rest[:k]
    if at != len(out):
        raise ValueError('clang printed text this reader does not parse')


def clang():
    return os.environ.get('LEAN_CLANG') or shutil.which('clang')


def raw_stream(text: str, mode: str):
    """The token and directive stream of `text` as clang's raw lexer reads it in `mode` (c, c++,
    assembler-with-cpp): it executes no directive and checks no macro, so it reads alike for every target.
    (tokens, directive lines, logical lines): tokens are (kind, spelling); a logical line ends at a line break
    outside a comment (translation phase 3 makes a comment one space) that no backslash splices."""
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, 'f.' + ('S' if mode == ASM else 'cc' if mode == 'c++' else 'c'))
        with open(p, 'w', encoding='utf-8', errors='surrogateescape') as f:
            f.write(text)
        r = subprocess.run([clang(), '-cc1', '-dump-raw-tokens', '-x', mode, os.path.basename(p)], cwd=d,
                           env=_env(), capture_output=True, timeout=120)
    out = r.stderr.decode('utf-8', 'surrogateescape')
    if r.returncode != 0:
        raise ValueError(f'clang could not read it: {out.strip().splitlines()[-1] if out.strip() else r.returncode}')
    tokens, lines, line = [], [], []
    for kind, spelling in _raw_records(out):
        if kind == 'comment':
            continue
        if kind == 'unknown' and not spelling.strip():
            if re.search(r'(?<!\\)\n', spelling) and line:
                lines.append(tuple(line))
                line = []
            continue
        tokens.append((kind, spelling))
        line.append((kind, spelling))
    if line:
        lines.append(tuple(line))
    return tokens, [ln for ln in lines if ln[0] == ('hash', '#')], lines


def lexical(original: str, lean: str, mode: str) -> tuple:
    if not clang():
        return False, f'clang unavailable: the {mode} token and directive stream needs its raw lexer'
    try:
        a, b = raw_stream(original, mode), raw_stream(lean, mode)
    except (ValueError, OSError, subprocess.SubprocessError) as e:
        return False, str(e)
    if a[0] != b[0]:
        return False, f'clang raw lexer ({mode}): different tokens'
    if a[1] != b[1]:
        return False, f'clang raw lexer ({mode}): different directives'
    if mode == ASM and a[2] != b[2]:
        return False, f'clang raw lexer ({mode}): different lines'
    return True, f'clang raw lexer ({mode}): same tokens and directives' + ('; same lines' if mode == ASM else '')


def verify(original: str, lean: str, interpreter: str = 'c') -> tuple:
    """Every consumer of the file reads the same token and directive stream, in each mode it is read in: C or
    C++ (its name), and assembler-with-cpp where the assembler preprocessor reads it (assembler_evidence, or GCC
    reads it only in that mode). In each mode GCC's comparison runs where GCC reads the authored file; where it
    does not, the lexical reader qualifies that mode only when GCC reads the file as assembler, or when every
    directive GCC objects to lies in a conditional group (gcc_objections_in_groups). Anything else stops."""
    lang = 'c++' if interpreter == 'c++' else 'c'
    asm_why = assembler_evidence(original, interpreter)
    modes = [ASM] if interpreter == 'asm' else [lang] + ([ASM] if asm_why else [])
    read = {m: _cpp_why(['-fpreprocessed', '-dD', '-P'], _splice(original), m) for m in modes}
    grouped = {m: read[m][0] is None and gcc_objections_in_groups(_splice(original), read[m][1]) for m in modes}
    if interpreter != 'asm' and not asm_why and read[lang][0] is None and not grouped[lang]:
        # both modes tried: a file GCC reads only as assembler is the assembler preprocessor's input
        alt = _cpp_why(['-fpreprocessed', '-dD', '-P'], _splice(original), ASM)
        if alt[0] is not None:
            modes.append(ASM)
            read[ASM], grouped[ASM] = alt, False
            asm_why = 'GCC reads it only as assembler-with-cpp'
    details, full = [], {}
    for mode in modes:
        a, why = read[mode]
        if a is not None:
            ok, detail, full[mode] = _gcc_mode(original, lean, mode, a)
        elif grouped[mode]:
            ok, detail = lexical(original, lean, mode)
            first = next((ln for ln in why.split('\n') if 'error:' in ln), '').split('error: ', 1)[-1]
            detail += f' (GCC refuses only directives inside conditional groups: {first})'
        elif mode != ASM and ASM in modes and read[ASM][0] is not None:
            ok, detail = lexical(original, lean, mode)
            detail += f' (GCC reads it as assembler-with-cpp, {asm_why}; as {mode} it refuses the assembler macros)'
        else:
            first = next((ln for ln in (why or '').split('\n') if 'error' in ln), '').strip()
            return False, (f'GCC could not read the authored file' + (f' as {mode}' if mode == ASM else '')
                           + (f': {first}' if first else ''))
        if not ok:
            return False, detail
        details.append(detail)
    if _line_controls(lean) != _line_controls(original):
        return False, 'the lean text holds line control (#line) the authored text does not'
    uses = line_uses(original, modes[0], full[modes[0]]) if modes[0] in full else []
    note = f'; its own code reads line numbers ({", ".join(uses)}): they follow the lean lines' if uses else ''
    return True, '; '.join(details) + note
