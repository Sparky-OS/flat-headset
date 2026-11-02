# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""Project one file: choose its handler, render its MR, add the pointer line, check it with the
consumer, and return the result with the reason for every file left as it is.

Ported from CGM's compiler (compile_one: render, write only when the bytes change, refuse by name) and
its MR location resolver (resolve: re-render the authored text, refuse when the MR on disk is not that
render, then read the line map). In CGM the authored text is a `.source` beside the MR; here it is the
file as its package ships it (the HR), which dpkg-stereo restores from its record or the package.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

from . import VERSION, consumers, core, lang_c

# The files whose code decides what a render gives. The identity carries their digest, so it changes
# whenever that code does (ORDER 297: two renderers that differed both reported "lean 0.1.15
# (collapse-blank,pointer)", and an image sealed by one was refused by the other).
RENDERER_FILES = ('__init__.py', 'project.py', 'core.py', 'consumers.py', 'JvmLexer.java', 'ZigTokens.zig')


def code_sha256(root=None) -> str:
    """The renderer's code digest: this toolkit's, or that of the toolkit copy in `root`."""
    root = Path(root) if root is not None else Path(__file__).parent
    names = list(RENDERER_FILES) + sorted(p.name for p in root.glob('lang_*.py'))
    files = {n: hashlib.sha256((root / n).read_bytes()).hexdigest() for n in names}
    return hashlib.sha256((json.dumps(files, sort_keys=True, separators=(',', ':'), ensure_ascii=True)
                           + '\n').encode()).hexdigest()


CODE_SHA256 = code_sha256()
# c-drop-lines: C and C++ lose their comment-only lines like every other language, keep their authored empty
# lines, and get no #line (engine 0.1.20; 0.1.17 to 0.1.19 were c-line-breaks, which kept every authored line)
OPTIONS = 'collapse-blank,pointer,c-drop-lines'
IDENTITY = f'lean {VERSION} ({OPTIONS}) code {CODE_SHA256[:16]}'


@dataclass
class Result:
    path: str
    status: str                  # projected | unchanged | left
    reason: str
    handler: str = ''
    variant: str = ''
    bytes_in: int = 0
    bytes_out: int = 0
    comments_removed: int = 0
    sha_in: str = ''
    sha_out: str = ''
    lean: bytes | None = None
    kept: list = field(default_factory=list)
    check: str = ''


class StaleMr(RuntimeError):
    """The installed MR is not the render of the authored text: resolving would point at the wrong line."""


def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def _lex(key, mod, text, variant):
    if key == 'ini':
        return mod.lex(text, variant)
    if key == 'shell':
        return mod.lex(text, whole_line_only=(variant == 'env'))
    return mod.lex(text)


def _keep_empty(key, mod, variant):
    if key == 'ini':
        return mod.keep_empty_after_for(variant)
    return getattr(mod, 'keep_empty_after', None)


def _verify(key, mod, original, lean, variant):
    if key == 'ini':
        return mod.verify(original, lean, variant)
    if key == 'shell':
        return mod.verify(original, lean, 'dash' if variant in ('dash', 'env') else 'bash')
    if key == 'c':
        return mod.verify(original, lean, variant if variant in ('c++', 'asm') else 'c')
    return mod.verify(original, lean)


def pointer_position(text: str) -> int:
    """MR line (0-based) where the pointer goes: after an interpreter line, a Python encoding line and
    editor modelines that must stay first."""
    lines = text.split('\n', 3)
    at = 0
    for k, ln in enumerate(lines[:2]):
        if k == 0 and ln.startswith('#!'):
            at = 1
        elif re.match(r'^[ \t\f]*#.*?coding[:=]', ln) or '-*-' in ln:
            at = k + 1
        else:
            break
    return at


def render_text(text: str, key: str, variant: str | None, package: str | None, path: str | None, pointer=True):
    """The render alone: (lean text, Rendered, comment spans). Deterministic for fixed inputs.
    C and C++ lose their comment-only lines completely and keep their authored empty lines (no run of them is
    collapsed), with no #line: their lean line numbers differ from the authored ones, as in every other
    language, and dpkg-stereo --resolve finds the authored line by its content."""
    mod = consumers.HANDLERS[key]
    spans = _lex(key, mod, text, variant)
    if key == 'c' and variant in ('c', 'c++', 'asm'):
        # a file the assembler preprocessor reads keeps its lines: a removed comment is one space there
        asm = lang_c.assembler_evidence(text, variant) is not None
        rendered = core.render(text, spans, lang_c.joiner_for(text, spans, asm=asm), keep_empty_after=mod.keep_empty_after,
                               collapse_blank=False, drop_lines=True)
    elif getattr(mod, 'DROP_LINES', False):
        # Zig as C and C++: comment-only lines go completely, the authored empty lines stay
        rendered = core.render(text, spans, mod.joiner, keep_empty_after=mod.keep_empty_after, collapse_blank=False,
                               drop_lines=True)
    else:
        rendered = core.render(text, spans, mod.joiner, keep_empty_after=_keep_empty(key, mod, variant))
    out, origin = rendered.text, rendered.origin
    if pointer and mod.MARKER and rendered.removed:
        ptr = core.pointer_text(mod.MARKER, package, path)
        out, origin = core.insert_line(out, origin, pointer_position(out), ptr)
    line_map = core.build_line_map(text, out, origin)
    rendered.text, rendered.origin, rendered.line_map = out, origin, line_map
    return out, rendered, spans


def project(path: str, data: bytes, package: str | None = None, *, pointer=True, check=True) -> Result:
    res = Result(path=path, status='left', reason='', bytes_in=len(data), sha_in=sha256(data))
    key, variant, why = consumers.choose(path, data)
    if key is None:
        res.reason = why
        return res
    res.handler, res.variant = key, variant or ''
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        res.reason = 'not UTF-8 text'
        return res
    if '\r' in text:
        res.reason = 'carriage returns (CRLF): no qualified adapter'
        return res
    mod = consumers.HANDLERS[key]
    why = mod.refuse(text)
    if why:
        res.reason = why
        return res
    if key == 'shell' and check:
        why = mod.authored_refusal(text, 'dash' if variant in ('dash', 'env') else 'bash')
        if why:
            res.reason = why
            return res
    if key in ('perl', 'zig') and check:
        # as for shell: an authored file perl does not compile on its own (a handler that runs inside its host,
        # such as snmptrapd's embedded Perl, which defines its names) has no consumer check, so it is retained;
        # so is a Zig file Zig's tokenizer or `zig ast-check` does not accept, or any Zig file without zig
        why = mod.authored_refusal(text)
        if why:
            res.reason = why
            return res
    try:
        lean, rendered, spans = render_text(text, key, variant, package, path, pointer)
    except Exception as e:      # a lexer's refusal, or the language's own tokenizer error (TabError, TokenError)
        res.reason = f'the {key} reader could not read it: {type(e).__name__}: {e}'.split('\n')[0]
        return res
    res.kept = rendered.kept
    if rendered.removed == 0:
        res.status, res.reason = 'unchanged', 'no removable comments'
        return res
    out = lean.encode('utf-8')
    if len(out) >= len(data):
        res.status, res.reason = 'unchanged', 'the pointer line outweighs the comments it would replace'
        return res
    if check:
        ok, detail = _verify(key, mod, text, lean, variant)
        res.check = detail
        if not ok:
            res.reason = f'check failed: {detail}'
            return res
        again = _lex(key, mod, lean, variant)
        if any(sp.kind == core.COMMENT for sp in again):
            res.reason = 'not stable under a second pass'
            return res
    res.status, res.reason = 'projected', 'removable comments removed'
    res.lean, res.bytes_out, res.sha_out = out, len(out), sha256(out)
    res.comments_removed = rendered.removed
    return res


def reads_others(path: str) -> bool:
    """True for a file whose text can name other files it reads as data (a Zig file's @embedFile)."""
    return os.path.splitext(path)[1].lower() in consumers.lang_zig.EXTS


def read_as_data(texts: dict, paths) -> dict:
    """{path: reason} for the payload files another file reads as data, whatever their format: their bytes are
    that file's input, so they stay as authored (Zig's @embedFile: the language reference, "@embedFile ...
    returns a compile time constant pointer to null-terminated, fixed-size array with length equal to the
    byte count of the file"). `texts` maps the files reads_others names to their bytes; `paths` is the whole
    payload, which a computed @embedFile path covers folder by folder."""
    paths = list(paths)
    out = {}
    for path, data in sorted(texts.items()):
        try:
            text = data.decode('utf-8')
        except UnicodeDecodeError:
            continue
        for target, why in consumers.lang_zig.embedded(path, text):
            if target.endswith('/'):
                for other in paths:
                    if other.startswith(target) and other != path:
                        out.setdefault(other, why)
            else:
                out.setdefault(target, why)
    return out


def resolve(hr: bytes, mr: bytes, path: str, package: str | None, line: int, col: int | None = None, *, pointer=True):
    """An MR location -> the authored (HR) location, rendering the HR again and refusing a stale MR, then
    reading the render's own line map (dpkg-stereo --resolve-render). dpkg-stereo --resolve finds it by the
    line's content instead (locate.locate), which needs no render by the same engine."""
    key, variant, why = consumers.choose(path, hr)
    if key is None:
        raise StaleMr(f'{path}: no handler ({why})')
    text = hr.decode('utf-8')
    lean, rendered, _ = render_text(text, key, variant, package, path, pointer=pointer)
    if lean.encode('utf-8') != mr:
        raise StaleMr(f'{path}: the installed file is not the render of its authored text; '
                      f'regenerate it (dpkg-stereo --project) before resolving')
    sl, sc = rendered.line_map.resolve(line, col or 1)
    return sl, (sc if col is not None else None)
