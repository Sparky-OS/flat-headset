# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""Line-oriented settings: KConfig files, desktop entries, systemd units and the systemd family of
configuration directories, udev rules, kmod configuration, environment files.

Ported from CGM's _lang_ini (whole-line `#`/`;` comments), narrowed to what each real consumer reads,
because `.conf` is not a grammar (LEAN-CODE-POLICY.md, "Configuration adapters must be qualified against
the actual parser"). None of these parsers has inline comments, so only whole comment lines go; each
variant follows its consumer's documented rule:
  kconfig   KConfig: a line whose first non-blank character is `#` (KConfigIniBackend trims the line)
  desktop   Desktop Entry Specification 1.5 "Comments": a line beginning with `#`; GLib's GKeyFile reads
            only column 0, so only column-0 lines go, which both parsers agree are comments
  systemd   systemd.syntax(7): lines starting with `#` or `;` are ignored, and a comment block after a
            line ending in a backslash is skipped while the continued line goes on
  sdline    tmpfiles.d(5), sysusers.d(5), sysctl.d(5), modules-load.d(5), binfmt.d(5), environment.d(5):
            empty lines and lines whose first non-blank character is `#` (or `;` where the page says
            so) are ignored; no continuation
  udev      udev(7) "Rules Files": lines beginning with `#` are ignored, also inside a continuation
            (udev-rules.c tests for `#` before joining)
  modprobe  modprobe.d(5): `#` lines are comments; libkmod joins backslash continuations first, so a
            comment line after a continuation is left alone
The check reads both texts with each consumer's rule and compares the logical lines they keep.
"""

from __future__ import annotations

from .core import COMMENT, KEEP, Span, is_notice, is_pointer

KEY = 'ini'
EXTS = frozenset()
MARKER = '#'

VARIANTS = {
    #           markers     column0  continuation
    'kconfig': (('#',), False, None),
    'desktop': (('#',), True, None),
    'systemd': (('#', ';'), False, 'skip'),
    'sdline': (('#', ';'), False, None),
    'sdline-hash': (('#',), False, None),
    'udev': (('#',), False, 'skip'),
    'modprobe': (('#',), False, 'keep'),
}


def joiner(src: str, start: int, end: int) -> str:
    return ''


def _lines(src):
    pos = 0
    for line in src.splitlines(keepends=True):
        body = line.rstrip('\r\n')
        yield pos, pos + len(body), body
        pos += len(line)


def _is_comment(body, markers, column0):
    t = body if column0 else body.lstrip(' \t')
    return t[:1] in markers and t != ''


def lex(src: str, variant: str) -> list:
    markers, column0, cont = VARIANTS[variant]
    spans = []
    prev_continues = False
    for a, b, body in _lines(src):
        if _is_comment(body, markers, column0):
            text = body.lstrip(' \t')
            if prev_continues and cont == 'keep':
                spans.append(Span(a, b, KEEP, 'comment line inside a continuation'))
            elif is_pointer(text):
                spans.append(Span(a, b, KEEP, 'pointer line'))
            elif is_notice(text):
                spans.append(Span(a, b, KEEP, 'licence or copyright notice'))
            else:
                spans.append(Span(a, b, COMMENT))
            if cont == 'skip':
                continue      # a skipped comment line does not end the continuation
        prev_continues = cont is not None and body.rstrip(' \t').endswith('\\')
    return spans


def refuse(src):
    return 'carries binary data' if '\0' in src else None


def keep_empty_after_for(variant):
    return None


def logical(src: str, variant: str) -> list:
    """The lines the consumer acts on, each stripped, continuations joined: an independent reading
    of the consumer's rule, used to check a projection (it shares no code with lex)."""
    markers, column0, cont = VARIANTS[variant]
    out = []
    acc = None
    for raw in src.split('\n'):
        raw = raw.rstrip('\r')
        t = raw if column0 else raw.lstrip(' \t')
        is_c = t[:1] in markers and t != ''
        if is_c:
            if acc is not None and cont == 'keep':
                acc += ' ' + raw.strip()
            continue
        if raw.strip() == '' and acc is None:
            continue
        if cont is not None and raw.rstrip(' \t').endswith('\\'):
            piece = raw.rstrip(' \t')[:-1]
            acc = piece if acc is None else acc + ' ' + piece
            continue
        line = raw if acc is None else acc + ' ' + raw
        acc = None
        if line.strip():
            out.append(' '.join(line.split()))
    if acc is not None and acc.strip():
        out.append(' '.join(acc.split()))
    return out


def verify(original: str, lean: str, variant: str) -> tuple:
    if logical(original, variant) != logical(lean, variant):
        return False, f'the {variant} reading differs'
    return True, f'the same {variant} lines'
