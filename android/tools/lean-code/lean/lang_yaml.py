# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""YAML: `#` comments (a `#` at the start of a line or after a blank), outside quoted scalars and outside
block scalars, whose lines are data even when they begin with `#` (YAML 1.2.2, 6.6 "Comments", 8.1
"Block Scalar Styles").

Ported from CGM's _lang_yaml, whose line scan did not know block scalars (`key: |` followed by a line
`# data` lost that line); here a block scalar's body is a literal. The check is the consumer's model:
PyYAML composes both texts into the same node graph (tags, values, structure; positions excluded).
"""

from __future__ import annotations

import re

from .core import COMMENT, KEEP, LITERAL, Span, is_notice, is_pointer

KEY = 'yaml'
EXTS = frozenset({'.yaml', '.yml'})
MARKER = '#'
BLOCK = re.compile(r'(^|[\s:\-?])[|>][-+0-9]*\s*(#.*)?$')

keep_empty_after = None


def joiner(src, start, end):
    return ''


def lex(src: str) -> list:
    spans = []
    lines = src.split('\n')
    pos = 0
    offs = []
    for ln in lines:
        offs.append(pos)
        pos += len(ln) + 1
    i = 0
    quote = None          # an open quoted scalar carried across lines: (char, start offset)
    while i < len(lines):
        line = lines[i]
        base = offs[i]
        j = 0
        n = len(line)
        if quote is None and line.lstrip(' ').startswith('#'):
            k = n - len(line.lstrip(' '))
            text = line[k:]
            kind = KEEP if (is_pointer(text) or is_notice(text) or (i == 0 and text.startswith('#!'))) else COMMENT
            spans.append(Span(base + k, base + n, kind, 'notice or pointer' if kind == KEEP else ''))
            i += 1
            continue
        block = False
        while j < n:
            c = line[j]
            if quote is not None:
                q, qs = quote
                if q == "'" and c == "'":
                    if j + 1 < n and line[j + 1] == "'":
                        j += 2
                        continue
                    if base + j + 1 - qs > 0 and '\n' in src[qs:base + j + 1]:
                        spans.append(Span(qs, base + j + 1, LITERAL, 'multi-line quoted scalar'))
                    quote = None
                elif q == '"' and c == '\\':
                    j += 2
                    continue
                elif q == '"' and c == '"':
                    if '\n' in src[qs:base + j + 1]:
                        spans.append(Span(qs, base + j + 1, LITERAL, 'multi-line quoted scalar'))
                    quote = None
                j += 1
                continue
            if c in '"\'' and (j == 0 or line[j - 1] in ' \t[{,:-?'):
                quote = (c, base + j)
                j += 1
                continue
            if c == '#' and (j == 0 or line[j - 1] in ' \t'):
                text = line[j:]
                kind = KEEP if (is_pointer(text) or is_notice(text)) else COMMENT
                spans.append(Span(base + j, base + n, kind, 'notice or pointer' if kind == KEEP else ''))
                if BLOCK.search(line[:j].rstrip() + ' '):
                    block = True
                break
            j += 1
        else:
            if quote is None and BLOCK.search(line):
                block = True
        if block and quote is None:
            parent = len(line) - len(line.lstrip(' '))
            k = i + 1
            indent = None
            while k < len(lines):
                l2 = lines[k]
                if l2.strip() == '':
                    k += 1
                    continue
                ind = len(l2) - len(l2.lstrip(' '))
                if indent is None:
                    if ind <= parent:
                        break
                    indent = ind
                if ind < indent:
                    break
                k += 1
            # trailing empty lines belong to the scalar too (chomping decides their value)
            if k > i + 1:
                spans.append(Span(offs[i + 1], offs[k - 1] + len(lines[k - 1]), LITERAL, 'block scalar'))
                i = k
                continue
        i += 1
    spans.sort(key=lambda s: s.start)
    out = []
    for sp in spans:
        if out and sp.start < out[-1].end:
            continue
        out.append(sp)
    return out


def refuse(src: str):
    return 'carries binary data' if '\0' in src else None


def _nodes(text):
    import yaml
    loader = getattr(yaml, 'CSafeLoader', yaml.SafeLoader)

    class L(loader):
        pass

    def walk(n):
        if isinstance(n, yaml.ScalarNode):
            return ('s', n.tag, n.value, n.style if n.style in ('|', '>') else '')
        if isinstance(n, yaml.SequenceNode):
            return ('q', n.tag, tuple(walk(x) for x in n.value))
        return ('m', n.tag, tuple((walk(k), walk(v)) for k, v in n.value))
    return [walk(d) for d in yaml.compose_all(text, Loader=L) if d is not None]


def verify(original: str, lean: str, interpreter: str = '') -> tuple:
    try:
        import yaml  # noqa: F401
    except ImportError:
        return False, 'no YAML parser (python3-yaml) to check with'
    try:
        a = _nodes(original)
    except Exception as e:
        return False, f'the authored file does not parse as YAML: {type(e).__name__}'
    try:
        b = _nodes(lean)
    except Exception as e:
        return False, f'the lean file does not parse as YAML: {type(e).__name__}'
    if a != b:
        return False, 'the YAML node graphs differ'
    return True, 'PyYAML composes the same node graph'
