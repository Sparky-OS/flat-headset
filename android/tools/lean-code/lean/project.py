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
import re
from dataclasses import dataclass, field

from . import VERSION, consumers, core, lang_c

OPTIONS = 'collapse-blank,pointer'
IDENTITY = f'lean {VERSION} ({OPTIONS})'


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
        return mod.verify(original, lean, 'c++' if variant == 'c++' else 'c')
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
    """The render alone: (lean text, Rendered, comment spans). Deterministic for fixed inputs."""
    mod = consumers.HANDLERS[key]
    spans = _lex(key, mod, text, variant)
    rendered = core.render(text, spans, mod.joiner, keep_empty_after=_keep_empty(key, mod, variant))
    out, origin = rendered.text, rendered.origin
    if pointer and mod.MARKER and rendered.removed:
        ptr = core.pointer_text(mod.MARKER, package, path)
        out, origin = core.insert_line(out, origin, pointer_position(out), ptr)
    if key == 'c' and variant in ('c', 'c++'):
        out, origin = lang_c.line_directives(out, origin, text)
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


def resolve(hr: bytes, mr: bytes, path: str, package: str | None, line: int, col: int | None = None, *, pointer=True):
    """An MR location -> the authored (HR) location, rendering the HR again and refusing a stale MR.
    C and C++ need no map: their `#line` directives already make the compiler report authored lines."""
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
