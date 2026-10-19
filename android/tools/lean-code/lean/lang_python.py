# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""Python: comments are the tokenizer's COMMENT tokens; docstrings stay.

Ported from CGM's _lang_python, with the policy's correction: CGM's MR dropped docstrings and re-printed
the whole module with ast.unparse; here the authored text is kept byte for byte except its comments,
and docstrings stay, because they are runtime data (`__doc__`, help()) (LEAN-CODE-POLICY.md, "What may
be removed"; Python data model; the 2026-10-09 counterexample `result = f.__doc__` became None).
Kept comments: the interpreter line and the encoding declaration (PEP 263: line 1 or 2), notices.
The check is the consumer's: both texts parse to the same abstract syntax tree (positions excluded).
"""

from __future__ import annotations

import ast
import io
import re
import tokenize
import warnings

from .core import COMMENT, KEEP, LITERAL, Span, is_notice, is_pointer

KEY = 'python'
EXTS = frozenset({'.py'})
INTERPRETERS = ('python', 'python3')
MARKER = '#'
CODING = re.compile(r'^[ \t\f]*#.*?coding[:=][ \t]*([-\w.]+)')
SELF_READ = re.compile(r'inspect\.(getsource|getsourcelines|getcomments|findsource)|getcomments\(|open\(\s*__file__')


def keep_empty_after(line: str) -> bool:
    return line.endswith('\\')


def joiner(src: str, start: int, end: int) -> str:
    return ''


def lex(src: str) -> list:
    spans = []
    starts = [0]
    for m in re.finditer('\n', src):
        starts.append(m.end())

    def off(pos):
        return starts[pos[0] - 1] + pos[1]

    toks = tokenize.generate_tokens(io.StringIO(src).readline)
    fs_start = getattr(tokenize, 'FSTRING_START', -1), getattr(tokenize, 'TSTRING_START', -2)
    fs_end = getattr(tokenize, 'FSTRING_END', -1), getattr(tokenize, 'TSTRING_END', -2)
    stack = []
    for t in toks:
        if t.type == tokenize.COMMENT:
            a, b = off(t.start), off(t.end)
            text = t.string
            line = t.start[0]
            if line == 1 and text.startswith('#!'):
                spans.append(Span(a, b, KEEP, 'interpreter line'))
            elif line <= 2 and CODING.match(src[starts[line - 1]:b]):
                spans.append(Span(a, b, KEEP, 'encoding declaration (PEP 263)'))
            elif is_pointer(text):
                spans.append(Span(a, b, KEEP, 'pointer line'))
            elif re.match(r'#.*-\*-.*-\*-', text) or re.search(r'\b(vim?|ex):\s*\S', text):
                spans.append(Span(a, b, KEEP, 'editor modeline'))
            elif is_notice(text):
                spans.append(Span(a, b, KEEP, 'licence or copyright notice'))
            else:
                spans.append(Span(a, b, COMMENT))
        elif t.type == tokenize.STRING:
            if t.start[0] != t.end[0]:
                spans.append(Span(off(t.start), off(t.end), LITERAL, 'multi-line string'))
        elif t.type in fs_start:
            stack.append(t.start)
        elif t.type in fs_end and stack:
            st = stack.pop()
            if not stack and st[0] != t.end[0]:
                # a multi-line f-string is data as a whole; a comment inside its replacement fields stays
                spans = [sp for sp in spans if not (off(st) <= sp.start < off(t.end))]
                spans.append(Span(off(st), off(t.end), LITERAL, 'multi-line f-string'))
    spans.sort(key=lambda sp: sp.start)
    return _merge_fstrings(src, spans)


def _merge_fstrings(src, spans):
    out = []
    for sp in spans:
        if out and sp.start < out[-1].end:
            continue
        out.append(sp)
    return out


def refuse(src: str) -> str | None:
    if SELF_READ.search(src):
        return 'reads its own source or comments (inspect.getsource/getcomments, open(__file__))'
    if '\0' in src:
        return 'carries binary data'
    return None


def verify(original: str, lean: str, interpreter: str = '') -> tuple:
    with warnings.catch_warnings():
        warnings.simplefilter('ignore')
        return _verify(original, lean)


def _verify(original, lean):
    try:
        a = ast.dump(ast.parse(original))
    except (SyntaxError, ValueError) as e:
        return False, f'the authored file does not parse: {e}'
    try:
        b = ast.dump(ast.parse(lean))
    except (SyntaxError, ValueError) as e:
        return False, f'the lean file does not parse: {e}'
    if a != b:
        return False, 'the syntax trees differ'
    return True, 'same syntax tree (ast.dump, positions excluded)'
