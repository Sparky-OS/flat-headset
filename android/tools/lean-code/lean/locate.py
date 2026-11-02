# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""A lean (MR) location -> its authored (HR) location, found by the line's content.

Daniel's design, 2026-11-01: "simple - pick the line content and search it on the source". The lean line's
code (its comments and blanks aside) is looked up among the authored lines' code; where that content occurs
more than once, the neighbouring lean lines pick the one whose neighbours match too. No render of the same
engine is needed, so it reads every lean form the engines have written: comment-only lines dropped (0.1.20),
every authored line kept (0.1.17 to 0.1.19) and `#line` after each removed run (0.1.15 and older).

Each line's key is its text with every comment the handler's reader finds blanked, a continuation backslash
at its end dropped, and its blanks collapsed, so a line that lost a trailing or an inner comment still has
its authored line's key. A lean line made of two authored lines (a comment that held line breaks, with code
before and after it, became one space) matches the joined key of those lines.
"""

from __future__ import annotations

import bisect
from dataclasses import dataclass

from . import consumers, core, lang_c


class Unresolved(RuntimeError):
    """A location the content search cannot stand behind; named, never guessed."""


@dataclass
class Found:
    line: int                    # authored line (1-based)
    col: int | None              # authored column, when a column was asked and the line's code matches
    last: int                    # last authored line the lean line came from (a joined line spans more)
    how: str                     # how the line was picked: unique, neighbours, order, line without code


def _norm(line: str) -> str:
    t = line.rstrip(' \t\f\v')
    k = len(t)
    while k > 0 and t[k - 1] == '\\':
        k -= 1
    if (len(t) - k) % 2 == 1:
        t = t[:-1]               # a backslash-newline is a splice, not content
    return ' '.join(t.split())


class Side:
    """One text's lines and keys: `keys[i]` the code of line i (0-based), '' when it holds none."""

    def __init__(self, text: str, spans: list, c_like: bool):
        self.text = text
        self.lines = text.split('\n')
        buf = list(text)
        self.comment = bytearray(len(text))        # 1 where a comment character is
        kind = bytearray(len(text))                # 1 removable comment, 2 kept comment, 3 literal
        for sp in spans:
            mark = {core.COMMENT: 1, core.KEEP: 2, core.LITERAL: 3}.get(sp.kind, 0)
            for k in range(sp.start, sp.end):
                kind[k] = mark
                if sp.kind in (core.COMMENT, core.KEEP):
                    self.comment[k] = 1
                    if buf[k] != '\n':
                        buf[k] = ' '
        blanked = ''.join(buf).split('\n')
        self.keys = [_norm(b) for b in blanked]
        self.starts = core.line_starts(text)
        # what a line without code holds: a kept comment, a literal's text, only a removable comment, or nothing
        self.cls = []
        for i, line in enumerate(self.lines):
            st = self.starts[i]
            marks = {kind[st + k] for k, ch in enumerate(line) if ch not in ' \t\f\v'}
            self.cls.append('kept' if 2 in marks else 'literal' if 3 in marks else 'comment' if 1 in marks
                            else 'blank')
        self.control = [bool(c_like and lang_c.LINE_CONTROL.match(b)) for b in blanked]
        self.pointer = [core.is_pointer(ln) and k == '' for ln, k in zip(self.lines, self.keys)]
        self.content = [bool(k) and not c for k, c in zip(self.keys, self.control)]
        # a comment that crosses the end of line i, with code before it on i and code after it on the line
        # it ends on, j: the lean line may be the two joined (one space for the comment)
        self.join_to = {}
        for sp in spans:
            if sp.kind != core.COMMENT:
                continue
            i = self.line_of(sp.start)
            j = self.line_of(max(sp.start, sp.end - 1))
            if j > i and self.content[i] and j < len(self.keys) and self.content[j] \
                    and self._code_after(sp.end, j):
                self.join_to[i] = j
        self.joined = {}
        for i in sorted(self.join_to, reverse=True):
            j = self.join_to[i]
            self.joined[i] = (self.keys[i] + ' ' + self.joined.get(j, (self.keys[j], j))[0],
                              self.joined.get(j, (None, j))[1])
        self.index = {}
        for i, k in enumerate(self.keys):
            if self.content[i]:
                self.index.setdefault(k, []).append((i, i))
        for i, (k, last) in self.joined.items():
            self.index.setdefault(k, []).append((i, last))
        for v in self.index.values():
            v.sort()
        self.joined_end = {}                      # last line of a join -> its first line
        for i, (_, last) in self.joined.items():
            self.joined_end.setdefault(last, []).append(i)

    def line_of(self, off: int) -> int:
        return bisect.bisect_right(self.starts, off) - 1

    def _code_after(self, end: int, j: int) -> bool:
        stop = self.starts[j + 1] - 1 if j + 1 < len(self.starts) else len(self.text)
        return any(not self.comment[k] and self.text[k] not in ' \t\f\v\\'
                   for k in range(end, stop))


def _sides(hr: str, mr: str, path: str):
    key, variant, why = consumers.choose(path, hr.encode('utf-8'))
    if key is None:
        raise Unresolved(f'{path}: no handler reads this file ({why})')
    mod = consumers.HANDLERS[key]

    def spans(text):
        try:
            if key == 'ini':
                return mod.lex(text, variant)
            if key == 'shell':
                return mod.lex(text, whole_line_only=(variant == 'env'))
            return mod.lex(text)
        except Exception as e:  # noqa: BLE001 - the reader's own refusal, named
            raise Unresolved(f'{path}: the {key} reader could not read it: {type(e).__name__}: {e}') from e
    c_like = key == 'c' and variant in ('c', 'c++')
    return Side(hr, spans(hr), c_like), Side(mr, spans(mr), c_like)


def _prev(side: Side, i: int) -> int:
    i -= 1
    while i >= 0 and not side.content[i]:
        i -= 1
    return i


def _next(side: Side, i: int) -> int:
    i += 1
    while i < len(side.keys) and not side.content[i]:
        i += 1
    return i if i < len(side.keys) else -1


def _step_up(a: Side, q: int, key: str):
    """The authored content line above q (exclusive) that matches `key`, walking one lean line up: the next
    content line above, or a join that ends on it. Returns the new q (the matched first line) or None."""
    p = _prev(a, q)
    if p < 0:
        return None
    if a.keys[p] == key:
        return p
    for first in a.joined_end.get(p, ()):
        if a.joined[first][0] == key:
            return first
    return None


def _step_down(a: Side, q: int, key: str):
    """The same downwards from q (the last authored line used so far): returns the new last line, or None."""
    p = _next(a, q)
    if p < 0:
        return None
    if a.keys[p] == key:
        return p
    if p in a.joined and a.joined[p][0] == key:
        return a.joined[p][1]
    return None


def _pick(a: Side, m: Side, j: int, limit: int):
    """The authored (first, last) line of lean content line j, and how it was chosen."""
    cands = list(a.index.get(m.keys[j], ()))
    if not cands:
        raise Unresolved(f'lean line {j + 1}: its content is in no authored line (the installed file was changed, '
                         f'or the authored copy is another version)')
    if len(cands) == 1:
        return cands[0], 'unique'
    state = [(c, c[0], c[1]) for c in cands]       # candidate, authored up position, authored down position
    up, down = j, j
    depth = 0
    while len(state) > 1 and depth < limit:
        up_n = _prev(m, up) if up >= 0 else -1
        down_n = _next(m, down) if down >= 0 else -1
        if up_n < 0 and down_n < 0:
            break
        depth += 1
        nxt = []
        for c, qu, qd in state:
            nu, nd = qu, qd
            if up_n >= 0:
                nu = _step_up(a, qu, m.keys[up_n])
                if nu is None:
                    continue
            elif _prev(a, qu) >= 0:
                continue          # the lean file has nothing above here, the authored one has
            if down_n >= 0:
                nd = _step_down(a, qd, m.keys[down_n])
                if nd is None:
                    continue
            elif _next(a, qd) >= 0:
                continue
            nxt.append((c, nu, nd))
        if not nxt:
            break                 # no candidate keeps matching: the file differs here; keep the last set
        state = nxt
        up = up_n if up_n >= 0 else -1
        down = down_n if down_n >= 0 else -1
    if len(state) == 1:
        return state[0][0], f'neighbours ({depth} lines each side)'
    # still several: the same occurrence, in order, among the lines with this content
    rank = sum(1 for k in range(j) if m.content[k] and m.keys[k] == m.keys[j])
    left = [c for c, _, _ in state]
    every = a.index[m.keys[j]]
    want = every[rank] if rank < len(every) else None
    pick = want if want in left else left[min(rank, len(left) - 1)]
    return pick, f'order (occurrence {rank + 1} of {len(every)} equal lines; neighbours matched {depth} lines)'


def _column(a: Side, m: Side, j: int, first: int, last: int, col: int):
    """The authored (line, column) of lean column `col` (1-based): the code characters of the lean line and of
    the authored lines it came from, comments aside, are the same characters in the same order."""
    mline = m.lines[j]
    ms = m.starts[j]
    mine = [k for k in range(len(mline)) if not m.comment[ms + k] and mline[k] not in ' \t\f\v\\']
    theirs = []
    for li in range(first, last + 1):
        st = a.starts[li]
        for k, ch in enumerate(a.lines[li]):
            if not a.comment[st + k] and ch not in ' \t\f\v\\':
                theirs.append((li, k))
    if [mline[k] for k in mine] != [a.lines[li][k] for li, k in theirs]:
        return None
    c = col - 1
    if c in mine:
        li, k = theirs[mine.index(c)]
        return li + 1, k + 1
    before = sum(1 for k in mine if k < c)
    if before == 0:               # in the blanks before the code
        if not theirs:
            return first + 1, col
        li, k = theirs[0]
        return li + 1, max(1, k + 1 - (mine[0] - c))
    li, k = theirs[before - 1]    # in blanks after a code character: just after it
    return li + 1, k + 2


class Locator:
    """The authored and the lean text of one file, read once, for any number of lookups."""

    def __init__(self, hr: bytes, mr: bytes, path: str):
        try:
            a_text, m_text = hr.decode('utf-8'), mr.decode('utf-8')
        except UnicodeDecodeError as e:
            raise Unresolved(f'{path}: not UTF-8 text') from e
        self.a, self.m = _sides(a_text, m_text, path)

    def find(self, line: int, col: int | None = None, *, limit: int = 4096) -> Found:
        """Lean `line`[:`col`] (1-based) -> its authored line, found by content."""
        a, m = self.a, self.m
        j = line - 1
        if j < 0 or j >= len(m.keys):
            raise Unresolved(f'lean line {line} is outside the lean file ({len(m.keys)} lines)')
        if m.pointer[j]:
            raise Unresolved(f'lean line {line} is the pointer line: no authored line stands behind it')
        if m.control[j] and not any(a.control[i] and a.keys[i] == m.keys[j] for i in range(len(a.keys))):
            raise Unresolved(f'lean line {line} is a #line directive an older engine wrote: no authored line '
                             f'stands behind it')
        if m.content[j]:
            (first, last), how = _pick(a, m, j, limit)
            c = _column(a, m, j, first, last, col) if col is not None else None
            if c is not None:
                return Found(c[0], c[1], last + 1, how)
            return Found(first + 1, None, last + 1, how)
        # a line without code (blank, a kept comment, an authored #line): the same kind of line, in order,
        # between the authored lines of the content lines around it
        above = _prev(m, j)
        base = _pick(a, m, above, limit)[0][1] if above >= 0 else -1
        nxt = _next(a, base)
        stop = nxt if nxt >= 0 else len(a.keys)
        want, cls = m.lines[j].strip(), m.cls[j]
        free = [i for i in range(base + 1, stop) if not a.content[i] and a.cls[i] == cls]
        same = [i for i in free if a.lines[i].strip() == want]
        pool = same or free
        k = sum(1 for i in range(above + 1, j)
                if not m.pointer[i] and m.cls[i] == cls and (m.lines[i].strip() == want or not same))
        if k < len(pool):
            return Found(pool[k] + 1, col if col is not None and same else None, pool[k] + 1, 'line without code')
        if pool:
            return Found(pool[-1] + 1, None, pool[-1] + 1, 'line without code (the last such line there)')
        raise Unresolved(f'lean line {line} holds no code, and no authored line without code stands at that '
                         f'place')


def locate(hr: bytes, mr: bytes, path: str, line: int, col: int | None = None, *, limit: int = 4096) -> Found:
    """Lean `path`:`line`[:`col`] (1-based) -> its authored line in `hr`, found by content."""
    return Locator(hr, mr, path).find(line, col, limit=limit)
