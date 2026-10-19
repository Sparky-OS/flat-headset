# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""C, C++ and GLSL: `//` and `/* */` comments, outside string, character and raw string literals.

Ported from CGM's _lang_c_like (its literal scan, raw strings and LineMap), with the 2026-10-09
counterexamples fixed using the edition's qualified regression fixtures:
  - `int/**/value` became `intvalue`: a removed comment with code after it is one space, as in
    translation phase 3 (C23 5.1.1.2; GCC "Initial processing");
  - a raw string lost one of its blank lines: literals are never compacted;
  - `__LINE__` changed from 4 to 3: for C and C++ the renderer adds `#line N` before the first line
    after every removed run (and after every conditional directive while the numbering is shifted), so
    the compiler, `__LINE__`, `assert` and debug information keep the authored line numbers; the
    origin mapping takes part in the compiler's own location handling (LEAN-CODE-POLICY.md, C and C++).
Also: a `//` comment ending in a backslash continues on the next line (phase 2 splices it); C++14
digit separators (1'000) are not character literals; a lone quote in `#error` text is not a literal.
Kept comments: notices, the pointer line, and the fall-through comments GCC's -Wimplicit-fallthrough
reads. The check runs GCC's own preprocessor: with -fpreprocessed (no expansion) both texts give the
same tokens and the same directives, and where the file preprocesses on its own, full preprocessing
gives the same output, `__LINE__` values included.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile

from .core import COMMENT, KEEP, LITERAL, Span, is_notice, is_pointer

KEY = 'c'
EXTS = frozenset({'.h', '.hh', '.hpp', '.hxx', '.c', '.cc', '.cpp', '.cxx', '.inl'})
GLSL_EXTS = frozenset({'.glsl', '.vert', '.frag', '.geom', '.comp', '.tesc', '.tese'})
MARKER = '//'
FALLTHROUGH = re.compile(r'fall(s| |-)?thr(ough|u)|\bnotreached\b|\blint\b', re.I)
RAW_PREFIX = ('R', 'u8R', 'uR', 'UR', 'LR')
RAW_OPEN = re.compile(r'"([^()\\\s]{0,16})\(')
CONDITIONAL = re.compile(r'^[ \t]*#[ \t]*(if|ifdef|ifndef|elif|elifdef|elifndef|else|endif)\b')


def keep_empty_after(line: str) -> bool:
    k, n = len(line), 0
    while k > 0 and line[k - 1] == '\\':
        n += 1
        k -= 1
    return n % 2 == 1


def joiner(src: str, start: int, end: int) -> str:
    return ' '


def lex(src: str) -> list:
    s, n = src, len(src)
    spans = []
    i = 0
    while i < n:
        c = s[i]
        nx = s[i + 1] if i + 1 < n else ''
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
    if '\0' in src:
        return 'carries binary data'
    if '??/' in src:
        return 'uses trigraphs'
    return None


GUARD_IF = re.compile(r'^[ \t]*#[ \t]*(ifndef[ \t]+(\w+)|if[ \t]+![ \t]*defined[ \t]*\(?[ \t]*(\w+))')
GUARD_DEFINE = re.compile(r'^[ \t]*#[ \t]*define[ \t]+(\w+)')
ENDIF = re.compile(r'^[ \t]*#[ \t]*endif\b')


def _guard(lines):
    """(index of the guard's #define line, index of its closing #endif) when the file is wrapped in an
    include guard, else None. GCC reads a guarded header once per translation unit only when the #ifndef is
    the first directive and the #endif the last (its multiple-include optimisation), so no #line may go
    before the #define or after the #endif."""
    def code(i):
        t = lines[i].strip()
        return t and not t.startswith('//') and not t.startswith('/*') and not t.startswith('*')
    first = next((i for i in range(len(lines)) if code(i)), None)
    if first is None:
        return None
    m = GUARD_IF.match(lines[first])
    if not m:
        return None
    name = m.group(2) or m.group(3)
    second = next((i for i in range(first + 1, len(lines)) if code(i)), None)
    if second is None:
        return None
    d = GUARD_DEFINE.match(lines[second])
    if not d or d.group(1) != name:
        return None
    last = next((i for i in range(len(lines) - 1, second, -1) if code(i)), None)
    if last is None or not ENDIF.match(lines[last]):
        return None
    return second, last


def line_directives(text: str, origin: list, src: str):
    """Add `#line N` so every MR line keeps its authored number for the compiler; returns new
    (text, origin). A directive goes before the first non-blank line after a shift, and again after a
    conditional directive when one went inside the group it closes (a skipped group ignores it). Never
    after a line ending in a backslash (a directive cannot start inside a continued line), and never
    outside an include guard (before its #define or after its #endif)."""
    import bisect
    starts = [0]
    for k, ch in enumerate(src):
        if ch == '\n':
            starts.append(k + 1)

    def src_line(off):
        return bisect.bisect_right(starts, off)

    lines = text.split('\n')
    guard = _guard(lines)
    out, out_origin = [], []
    pos = 0
    believed = 1           # the line number the compiler believes the next MR line has
    prev = ''
    emitted_in_group = False   # a #line went into the conditional group now open
    resync = False             # a group that may have been skipped held a #line: say the number again
    for idx, line in enumerate(lines):
        o = origin[pos] if pos < len(origin) else -1
        real = src_line(o) if o >= 0 else None
        allowed = (line.strip() != '' and not keep_empty_after(prev)
                   and (guard is None or guard[0] < idx <= guard[1]))
        if real is not None and allowed and (real != believed or resync):
            d = f'#line {real}'
            out.append(d)
            out_origin.extend([-1] * (len(d) + 1))
            believed = real
            emitted_in_group = True
            resync = False
        out.append(line)
        seg = origin[pos:pos + len(line) + 1]
        out_origin.extend(seg if len(seg) == len(line) + 1 else seg + [-1] * (len(line) + 1 - len(seg)))
        pos += len(line) + 1
        believed += 1
        if CONDITIONAL.match(line):
            resync = resync or emitted_in_group
            emitted_in_group = False
        prev = line
    new = '\n'.join(out)
    return new, out_origin[:len(new)]


def _cpp(args, text, lang):
    gcc = shutil.which('gcc') or shutil.which('cc')
    if not gcc:
        return None
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, 'f.' + ('cc' if lang == 'c++' else 'c'))
        with open(p, 'w', encoding='utf-8', errors='surrogateescape') as f:
            f.write(text)
        # Both sides must have the same logical filename and clock. Otherwise
        # __FILE__ (including uses hidden in system-header macros), __BASE_FILE__
        # and time macros produce differences caused only by our test fixtures.
        os.utime(p, (0, 0))
        env = dict(os.environ, SOURCE_DATE_EPOCH='0')
        r = subprocess.run([gcc, '-E', '-x', lang, *args, os.path.basename(p)],
                           cwd=d, env=env, capture_output=True, timeout=120)
        if r.returncode != 0:
            return None
        return r.stdout.decode('utf-8', 'surrogateescape')


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


def _splice(text: str) -> str:
    """Translation phase 2 (C23 5.1.1.2): a backslash-newline joins two lines. GCC's -fpreprocessed
    mode skips it, so the check does it first, the same way for both texts."""
    return text.replace('\\\n', '')


def verify(original: str, lean: str, interpreter: str = 'c') -> tuple:
    lang = 'c++' if interpreter == 'c++' else 'c'
    a = _cpp(['-fpreprocessed', '-dD', '-P'], _splice(original), lang)
    b = _cpp(['-fpreprocessed', '-dD', '-P'], _splice(lean), lang)
    if a is None:
        return False, 'GCC could not read the authored file'
    if b is None:
        return False, 'GCC could not read the lean file'
    if _units(a) != _units(b):
        return False, 'GCC sees different tokens or directives'
    fa = _cpp(['-P', '-w'], original, lang)
    if fa is not None:
        fb = _cpp(['-P', '-w'], lean, lang)
        if fb is None or _units(fa) != _units(fb):
            return False, 'full preprocessing differs'
        return True, 'GCC: same tokens and directives, and the same full preprocessing'
    return True, 'GCC: same tokens and directives (the file does not preprocess on its own)'
