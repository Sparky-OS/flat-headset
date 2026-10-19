# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""JSON has no comment syntax (RFC 8259): its MR is its HR, byte for byte.

Ported from CGM's _lang_json in name only: CGM re-printed JSON with sorted keys, which is a
reformatting, not a removal of commentary, and the policy forbids reformatting (LEAN-CODE-POLICY.md,
"What may be removed"). A JSON file is recorded as "no comment syntax" and left as it is.
"""

KEY = 'json'
EXTS = frozenset({'.json'})
MARKER = None
keep_empty_after = None


def joiner(src, start, end):
    return ''


def lex(src):
    return []


def refuse(src):
    return 'JSON has no comment syntax (RFC 8259)'


def verify(original, lean, interpreter=''):
    return original == lean, 'identical'
