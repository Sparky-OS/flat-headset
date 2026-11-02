# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""Zig: `//` line comments, outside string and character literals and multi-line string lines.

The language reference (0.16.0, "Comments"): "Comments in Zig start with "//" and end at the next LF
byte"; "There are no multiline comments in Zig". Its grammar: line_comment <- '//' ![!/] non_control_utf8*
/ '////' non_control_utf8*. So a comment always ends its line: a comment-only line goes completely, a
comment after code goes with the blanks before it, and the authored empty lines stay (as C and C++ since
engine 0.1.20: no `#line`, no kept empty line, no stored line map; dpkg-stereo --resolve finds a lean line by
its content in the authored text).
Kept: doc comments, `///` (exactly three slashes) and `//!` (the namespace's), which are tokens the
compiler reads and `zig std` and -femit-docs publish; `// zig fmt: off` and `// zig fmt: on`, which zig fmt
reads (std/zig/Ast/Render.zig); notices and SPDX lines; the pointer line.
Data, never edited: string and character literals, and every `\\\\` multi-line string line (the grammar's
line_string runs to the end of its line, `//` included).
Left as authored, with the reason: a file whose code calls @src() (its line is the authored line at run
time); a file another file of the payload reads with @embedFile (read_as_data); templates (zig init's
lib/init, generators' templates/ folders: consumers.choose).
The check is the consumer's: Zig's own tokenizer (std.zig.Tokenizer, through ZigTokens.zig built with the
installed zig) gives both texts the same token stream (tag, bytes and whether a line break precedes each
token, the relation Parse.tokensOnSameLine reads; doc comments are tokens, so they are compared byte for
byte), and `zig ast-check` accepts both.
"""

from __future__ import annotations

import atexit
import os
import posixpath
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from .core import COMMENT, KEEP, LITERAL, Span, is_notice, is_pointer

KEY = 'zig'
EXTS = frozenset({'.zig'})
MARKER = '//'
DROP_LINES = True
FMT_DIRECTIVES = ('zig fmt: off', 'zig fmt: on')
WHITESPACE = ' \t\n\r\x0b\x0c'                  # std.ascii.whitespace, as Render.zig trims a comment
SRC = re.compile(r'@src\s*\(')
EMBED = re.compile(r'@embedFile\s*\(\s*(?:"((?:[^"\\\n]|\\.)*)"|([A-Za-z_][A-Za-z0-9_]*))\s*\)|@embedFile\s*\(')
TEMPLATES = ('*/lib/init/*', '*/lib/zig/init/*', '*/templates/*', '*/template/*')

_built = None
_temporary = None


def keep_empty_after(line: str) -> bool:
    return False                                 # Zig has no line continuation


def joiner(src: str, start: int, end: int) -> str:
    return ''                                    # never used: a Zig comment ends its line


def _quoted(line: str, i: int, quote: str) -> int:
    j = i + 1
    while j < len(line):
        if line[j] == '\\':
            j += 2
            continue
        if line[j] == quote:
            return j + 1
        j += 1
    raise ValueError(f'unterminated {"string" if quote == chr(34) else "character"} literal')


def _comment(raw: str, a: int, b: int) -> Span:
    if raw.startswith('///') and not raw.startswith('////'):
        return Span(a, b, KEEP, 'doc comment: a token the compiler reads, published by zig std and -femit-docs')
    if raw.startswith('//!'):
        return Span(a, b, KEEP, "container doc comment: a token, the namespace's documentation")
    if raw[2:].strip(WHITESPACE) in FMT_DIRECTIVES:
        return Span(a, b, KEEP, 'zig fmt directive (std/zig/Ast/Render.zig reads it)')
    if is_pointer(raw):
        return Span(a, b, KEEP, 'pointer line')
    if is_notice(raw):
        return Span(a, b, KEEP, 'licence or copyright notice')
    return Span(a, b, COMMENT)


def lex(src: str) -> list:
    spans = []
    pos = 0
    for line in src.split('\n'):
        i = 0
        while i < len(line):
            c = line[i]
            if line.startswith('\\\\', i):
                spans.append(Span(pos + i, pos + len(line), LITERAL, 'multi-line string line'))
                break
            if c == '"' or c == "'":
                j = _quoted(line, i, c)
                spans.append(Span(pos + i, pos + j, LITERAL, 'string literal' if c == '"' else 'character literal'))
                i = j
                continue
            if line.startswith('//', i):
                spans.append(_comment(line[i:], pos + i, pos + len(line)))
                break
            i += 1
        pos += len(line) + 1
    return spans


def code_text(src: str, literals=True) -> str:
    """The text with its comments (and its literals) blanked, offsets kept: what the compiler reads as code."""
    out = list(src)
    for sp in lex(src):
        if sp.kind == LITERAL and not literals:
            continue
        for k in range(sp.start, sp.end):
            if out[k] != '\n':
                out[k] = ' '
    return ''.join(out)


def refuse(src: str) -> str | None:
    if '\0' in src:
        return 'carries binary data'
    try:
        code = code_text(src)
    except ValueError:
        return None                              # the consumer check names it
    if SRC.search(code):
        return 'calls @src(), whose line is the authored line at run time'
    return None


def embedded(path: str, src: str):
    """The payload paths this file reads with @embedFile, each with the reason it stays authored: a literal
    path, or a constant bound to one in this file, relative to this file's folder; a path computed any other
    way names this file's whole folder (the trailing '/')."""
    try:
        code, text = code_text(src), code_text(src, literals=False)
    except ValueError:
        return []
    here = posixpath.dirname(path)
    out = []
    for call in re.finditer(r'@embedFile\s*\(', code):
        m = EMBED.match(text, call.start())
        target = None
        if m.group(1) is not None or m.group(2) is not None:
            raw = src[m.start(1):m.end(1)] if m.group(1) is not None else None
            if raw is None:
                const = re.search(r'\bconst\s+' + re.escape(m.group(2)) + r'\s*(?::[^=]*)?=\s*"((?:[^"\\\n]|\\.)*)"\s*;',
                                  src)
                raw = const.group(1) if const else None
            if raw is not None and '\\' not in raw:
                target = posixpath.normpath(posixpath.join(here, raw))
        why = f'read as data by @embedFile in {path}'
        out.append((target if target else here.rstrip('/') + '/', why if target else why + ' (a computed path)'))
    return out


def zig() -> str | None:
    return os.environ.get('LEAN_ZIG') or shutil.which('zig')


def _close():
    if _temporary is not None:
        _temporary.cleanup()


atexit.register(_close)


def _env(cache):
    env = dict(os.environ)
    env['ZIG_GLOBAL_CACHE_DIR'] = os.path.join(cache, 'global')
    env['ZIG_LOCAL_CACHE_DIR'] = os.path.join(cache, 'local')
    return env


def _helper():
    """ZigTokens.zig built once per process with the installed zig (LEAN_ZIG_CACHE keeps zig's cache across
    processes)."""
    global _built, _temporary
    if _built is not None:
        return _built
    _temporary = tempfile.TemporaryDirectory(prefix='lean-zig-')
    cache = os.environ.get('LEAN_ZIG_CACHE') or _temporary.name
    exe = os.path.join(_temporary.name, 'zig-tokens')
    r = subprocess.run([zig(), 'build-exe', '-O', 'Debug', str(Path(__file__).with_name('ZigTokens.zig')),
                        f'-femit-bin={exe}'], capture_output=True, timeout=600, cwd=_temporary.name, env=_env(cache))
    if r.returncode != 0:
        raise OSError('zig could not build the tokenizer helper: '
                      + r.stderr.decode('utf-8', 'replace').strip().split('\n')[-1])
    _built = exe
    return exe


def _with_files(texts, run):
    with tempfile.TemporaryDirectory(prefix='lean-zig-in-') as d:
        names = []
        for n, text in enumerate(texts):
            p = os.path.join(d, f'f{n}.zig')
            with open(p, 'w', encoding='utf-8') as f:
                f.write(text)
            names.append(p)
        return run(d, names)


def token_streams(*texts):
    """Each text's token stream as Zig's tokenizer reads it: (tag, line break before it, bytes) per token."""
    def run(d, names):
        r = subprocess.run([_helper(), *names], capture_output=True, timeout=300, cwd=d)
        if r.returncode != 0:
            raise OSError('the tokenizer helper failed: ' + r.stderr.decode('utf-8', 'replace').strip()[-200:])
        return r.stdout.decode('ascii')
    out = _with_files(texts, run)
    streams = []
    for block in out.split('== ')[1:]:
        lines = block.split('\n')[1:]
        streams.append([tuple(line.split('\t')) for line in lines if line])
    if len(streams) != len(texts):
        raise OSError('the tokenizer helper printed an incomplete answer')
    return streams


def ast_check(text) -> str | None:
    """None when `zig ast-check` accepts the text, else its first message."""
    def run(d, names):
        return subprocess.run([zig(), 'ast-check', names[0]], capture_output=True, timeout=300, cwd=d,
                              env=_env(d))
    r = _with_files([text], run)
    if r.returncode == 0:
        return None
    msg = (r.stderr or r.stdout).decode('utf-8', 'replace').strip().split('\n')[0]
    return re.sub(r'^\S*f0\.zig:', '', msg) or f'exit status {r.returncode}'


def authored_refusal(text: str) -> str | None:
    """An authored file Zig itself does not accept is retained: the consumer check needs Zig to read it."""
    if not zig():
        return 'zig unavailable; authored file retained'
    try:
        (stream,) = token_streams(text)
        if any(tok[0] == 'invalid' for tok in stream):
            return "authored file holds bytes Zig's tokenizer does not accept; consumer qualification unavailable"
        why = ast_check(text)
    except (OSError, subprocess.SubprocessError) as e:
        return f'zig could not read the authored file: {e}'
    if why:
        return f'authored file is not accepted by zig ast-check ({why}); consumer qualification unavailable'
    return None


def verify(original: str, lean: str) -> tuple:
    try:
        a, b = token_streams(original, lean)
        if a != b:
            k = next((k for k, (x, y) in enumerate(zip(a, b)) if x != y), min(len(a), len(b)))
            return False, f"Zig's tokenizer reads different tokens (token {k + 1})"
        if any(tok[0] == 'invalid' for tok in a):
            return False, "Zig's tokenizer reads an invalid token"
        why = ast_check(lean)
        if why:
            return False, f'zig ast-check refuses the lean file: {why}'
    except (OSError, subprocess.SubprocessError) as e:
        return False, str(e)
    return True, "Zig's tokenizer: the same tokens, doc comments and line breaks between tokens; zig ast-check accepts it"
