# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""TOML: `#` comments outside strings; multi-line strings are data (TOML 1.0.0, "Comment", "String").

Ported from CGM's _lang_toml (its multi-line string tracking), with comment detection done by one
character scan over basic, literal and multi-line strings instead of per-line heuristics. The check is
the consumer's model: Python's tomllib reads both texts to the same document.
"""

from __future__ import annotations

from .core import COMMENT, KEEP, LITERAL, Span, is_notice, is_pointer

KEY = 'toml'
EXTS = frozenset({'.toml'})
MARKER = '#'
keep_empty_after = None


def joiner(src, start, end):
    return ''


def lex(src: str) -> list:
    s, n = src, len(src)
    spans = []
    i = 0
    while i < n:
        c = s[i]
        if c == '#':
            j = s.find('\n', i)
            j = n if j < 0 else j
            text = s[i:j]
            kind = KEEP if (is_pointer(text) or is_notice(text)) else COMMENT
            spans.append(Span(i, j, kind, 'notice or pointer' if kind == KEEP else ''))
            i = j
            continue
        if s.startswith('"""', i) or s.startswith("'''", i):
            q = s[i:i + 3]
            j = i + 3
            while j < n:
                if q == '"""' and s[j] == '\\':
                    j += 2
                    continue
                if s.startswith(q, j):
                    while j + 3 < n and s[j + 3] == q[0]:
                        j += 1      # up to two quotes may end the content right before the delimiter
                    j += 3
                    break
                j += 1
            spans.append(Span(i, j, LITERAL, 'multi-line string'))
            i = j
            continue
        if c in '"\'':
            j = i + 1
            while j < n and s[j] != c and s[j] != '\n':
                if c == '"' and s[j] == '\\':
                    j += 1
                j += 1
            i = j + 1
            continue
        i += 1
    return spans


def refuse(src):
    return 'carries binary data' if '\0' in src else None


def verify(original, lean, interpreter=''):
    import tomllib
    try:
        a = tomllib.loads(original)
    except tomllib.TOMLDecodeError as e:
        return False, f'the authored file does not parse as TOML: {e}'
    try:
        b = tomllib.loads(lean)
    except tomllib.TOMLDecodeError as e:
        return False, f'the lean file does not parse as TOML: {e}'
    if a != b:
        return False, 'the TOML documents differ'
    return True, 'tomllib reads the same document'
