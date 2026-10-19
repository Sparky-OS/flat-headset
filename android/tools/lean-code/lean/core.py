# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""The renderer every handler shares: authored text (HR) in, machine text (MR) and its line map out.

Ported from CGM Forge's hr_mr_compiler (Daniel Ramos): the C-like handler's render with an origin per
output character and its LineMap, generalised so every language handler renders through it. A handler
only says where its comments, kept comments and literal regions are (a list of Spans) and how a removed
comment is replaced; this module removes the comments and the lines they leave empty, never touches a
literal, records the origin of every MR character, and builds the map from that record, so the map comes
from the same decisions that made the MR (LEAN-CODE-POLICY.md, "Error locations belong to the authored
source": never inferred later by searching for matching lines).
"""

from __future__ import annotations

import bisect
from dataclasses import dataclass, field

COMMENT = 'comment'   # removable commentary
KEEP = 'keep'         # a comment a consumer or the law needs: shebang, directive, notice, the pointer line
LITERAL = 'literal'   # text that is data: heredoc bodies, multi-line strings, block scalars; never edited

POINTER_TAG = 'Lean Code:'
POINTER_URL = 'repo.sparkyos.org/lean-code'

NOTICE_WORDS = ('copyright', '(c) ', '©', 'spdx-license-identifier', 'license', 'licence', 'licensed',
                'permission is hereby granted', 'all rights reserved', 'gnu general public', 'gnu lesser',
                'warranty', 'author:', 'authors:')


@dataclass(frozen=True)
class Span:
    start: int
    end: int
    kind: str
    why: str = ''


@dataclass
class Rendered:
    text: str
    origin: list
    line_map: 'LineMap'
    removed: int = 0            # comments removed
    kept: list = field(default_factory=list)   # (source line, why) of every comment kept


class MrLineMapRefusal(RuntimeError):
    """A location this render cannot stand behind; named, never clamped or guessed."""


class LineMap:
    """MR line and column -> authored line and column (both 1-based), from the render's own record.

    Ported from CGM's LineMap: `lines` holds, for each MR line, the authored line of its first
    character; `pieces` lists, for lines joined from more than one authored line, where each run of
    characters came from, so a column resolves to the run that produced it.
    """

    __slots__ = ('lines', 'pieces', 'mr_line_count', 'source_line_count')

    def __init__(self, lines, pieces, source_line_count):
        self.lines = tuple(lines)
        self.pieces = {k: tuple(v) for k, v in pieces.items()}
        self.mr_line_count = len(self.lines)
        self.source_line_count = source_line_count

    def resolve(self, mr_line: int, mr_col: int = 1) -> tuple:
        if mr_line < 1 or mr_line > self.mr_line_count:
            raise MrLineMapRefusal(f'MR line {mr_line} is outside this render ({self.mr_line_count} lines): the '
                                   f'location comes from another version of the file')
        if mr_col < 1:
            raise MrLineMapRefusal(f'MR column {mr_col} is not a 1-based column')
        base = self.lines[mr_line - 1]
        if base == 0:
            raise MrLineMapRefusal(f'MR line {mr_line} has no authored origin (the pointer line or a line the '
                                   f'renderer added); nothing in the authored file stands behind it')
        runs = self.pieces.get(mr_line)
        if not runs:
            return base, mr_col
        pick = None
        for run in runs:
            if run[0] <= mr_col:
                pick = run
            else:
                break
        if pick is None:
            return base, mr_col
        return pick[1], pick[2] + (mr_col - pick[0])


def line_starts(src: str) -> list:
    starts = [0]
    at = src.find('\n')
    while at >= 0:
        starts.append(at + 1)
        at = src.find('\n', at + 1)
    return starts


def build_line_map(src: str, text: str, origin: list) -> LineMap:
    """Ported from CGM's _build_line_map: one anchor per MR line, plus runs where origins jump."""
    starts = line_starts(src)

    def loc(off):
        li = bisect.bisect_right(starts, off)
        return li, off - starts[li - 1] + 1

    breaks = [p for p in range(1, len(origin)) if origin[p] != origin[p - 1] + 1]
    lines, pieces = [], {}
    n, pos, mr_line = len(text), 0, 0
    while pos < n:
        end = text.find('\n', pos)
        if end < 0:
            end = n
        mr_line += 1
        if end > pos:
            anchor = origin[pos]
        elif end < n:
            anchor = origin[end]
        else:
            anchor = -1
        lines.append(loc(anchor)[0] if anchor >= 0 else 0)
        lo = bisect.bisect_left(breaks, pos + 1)
        hi = bisect.bisect_left(breaks, end)
        if hi > lo:
            runs = []
            for bi in range(lo, hi):
                p = breaks[bi]
                o = origin[p]
                if o < 0:
                    continue
                sl, sc = loc(o)
                runs.append((p - pos + 1, sl, sc))
            if runs:
                pieces[mr_line] = runs
        pos = end + 1
    return LineMap(lines, pieces, len(starts))


def is_notice(text: str) -> bool:
    low = text.lower()
    return any(w in low for w in NOTICE_WORDS)


def blank(ch: str) -> bool:
    return ch in ' \t\f\v'


def render(src: str, spans: list, joiner, *, keep_empty_after=None, collapse_blank=True) -> Rendered:
    """Remove the COMMENT spans of `src` and return the MR, its origin record and its line map.

    joiner(src, start, end) -> str: what replaces a removed comment that has code after it on its line
        ('' or ' ' or '\\n'), the handler's knowledge of its language (a C comment is one space, a
        JavaScript comment holding a line terminator is a line terminator). A comment that ends its line
        is replaced by nothing, and the blanks before it go with it.
    keep_empty_after(line_text) -> bool: True when the line before a comment-only line continues into
        it (shell's and C's backslash-newline), so the emptied line stays as an empty line instead of
        being deleted; None for formats whose parser skips comment lines inside a continuation (systemd,
        udev).
    collapse_blank: runs of more than one empty line outside literals become one (CGM's MR shape).
    """
    n = len(src)
    lit = [(sp.start, sp.end) for sp in spans if sp.kind in (LITERAL, KEEP)]
    lit_starts = [a for a, _ in lit]

    def src_in_literal(o):
        if o < 0:
            return False
        k = bisect.bisect_right(lit_starts, o) - 1
        return k >= 0 and o < lit[k][1]

    starts = line_starts(src)
    buf, origin, removed_at, kept = [], [], [], []
    removed = 0
    i = 0
    for sp in spans:
        if sp.start > i:
            buf.extend(src[i:sp.start])
            origin.extend(range(i, sp.start))
        if sp.kind == COMMENT:
            nl = src.find('\n', sp.end)
            tail = src[sp.end: nl if nl >= 0 else n]
            if tail.strip(' \t\f\v') == '':
                j = len(buf)
                while (j > 0 and blank(buf[j - 1]) and not (j >= 2 and buf[j - 2] == '\\')
                       and not src_in_literal(origin[j - 1])):
                    j -= 1
                del buf[j:]
                del origin[j:]
                rep = ''
            else:
                rep = joiner(src, sp.start, sp.end)
            removed_at.append(len(buf))
            buf.extend(rep)
            origin.extend([sp.start] * len(rep))
            removed += 1
        else:
            buf.extend(src[sp.start:sp.end])
            origin.extend(range(sp.start, sp.end))
            if sp.kind == KEEP:
                kept.append((bisect.bisect_right(starts, sp.start), sp.why))
        i = sp.end
    if i < n:
        buf.extend(src[i:])
        origin.extend(range(i, n))
    text = ''.join(buf)
    assert len(text) == len(origin)

    # lines a removal left empty (only blanks) go, newline included
    dels = []
    for ls in sorted({text.rfind('\n', 0, off) + 1 for off in removed_at}):
        le = text.find('\n', ls)
        line_end = le if le >= 0 else len(text)
        if text[ls:line_end].strip(' \t\f\v') != '':
            continue
        if any(src_in_literal(origin[x]) for x in range(ls, line_end)):
            continue
        if keep_empty_after is not None and ls > 0:
            prev_start = text.rfind('\n', 0, ls - 1) + 1
            if keep_empty_after(text[prev_start:ls - 1]):
                if line_end > ls:
                    dels.append((ls, line_end))   # keep the newline, drop the blanks
                continue
        if le >= 0:
            dels.append((ls, le + 1))
        elif ls > 0:
            dels.append((ls - 1, line_end))       # a last line without newline: drop the newline before it
        else:
            dels.append((ls, line_end))
    text, origin = apply_deletions(text, origin, dels)

    if collapse_blank:
        dels = []
        p = 0
        while True:
            p = text.find('\n\n\n', p)
            if p < 0:
                break
            q = p
            while q < len(text) and text[q] == '\n':
                q += 1
            if not any(src_in_literal(origin[x]) for x in range(p, q)):
                if keep_empty_after is None or p == 0 or not keep_empty_after(text[text.rfind('\n', 0, p) + 1:p]):
                    dels.append((p + 2, q))
            p = q
        text, origin = apply_deletions(text, origin, dels)
    return Rendered(text, origin, build_line_map(src, text, origin), removed, kept)


def apply_deletions(text: str, origin: list, dels: list):
    """Ported from CGM's _apply_deletions: delete [a, b) ranges, keeping text and origin in step."""
    if not dels:
        return text, origin
    dels = sorted(dels)
    parts, kept, prev = [], [], 0
    for a, b in dels:
        if a < prev:
            a = prev
        if b <= a:
            continue
        parts.append(text[prev:a])
        kept.append(origin[prev:a])
        prev = b
    parts.append(text[prev:])
    kept.append(origin[prev:])
    new_origin = []
    for chunk in kept:
        new_origin.extend(chunk)
    return ''.join(parts), new_origin


def insert_line(text: str, origin: list, at_line: int, line: str):
    """Insert one whole line (with newline) before MR line `at_line` (0-based); origin -1 marks it as
    rendered, not authored."""
    pos = 0
    for _ in range(at_line):
        nl = text.find('\n', pos)
        if nl < 0:
            pos = len(text)
            if not text.endswith('\n'):
                text += '\n'
                origin = origin + [-1]
                pos = len(text)
            break
        pos = nl + 1
    add = line + '\n'
    return text[:pos] + add + text[pos:], origin[:pos] + [-1] * len(add) + origin[pos:]


def pointer_text(marker: str, package: str | None, path: str | None, closer: str = '') -> str:
    """The one comment line each lean file keeps at its top (Daniel, 2026-10-14: "yes, keep the pointer
    line"): where its full HR copy is (the file's own path under its package's hr folder) and the online
    page with the resolver and its manual. Kept short: it is read at every start too."""
    where = f'/usr/share/doc/{package}/hr/, ' if package else ''
    return f'{marker} {POINTER_TAG} HR in {where}{POINTER_URL}{closer}'


def is_pointer(comment_text: str) -> bool:
    return POINTER_TAG in comment_text and POINTER_URL in comment_text
