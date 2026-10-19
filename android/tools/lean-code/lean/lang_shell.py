# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""Shell (bash and POSIX sh/dash): comments are found the way the shell's own reader finds them.

Ported from CGM's _lang_shell (shebang kept, `#` comments to MR-absent), with its two counterexamples
of 2026-10-09 fixed using the edition's qualified regression fixtures:
  - a `#` inside double quotes is data ("value # keep" stayed broken shell);
  - a heredoc's body is data (`cat <<'EOF'` / `# literal data` / `EOF` lost its line).
The rule is the shell's: a `#` starts a comment only at the start of a word, outside quotes, outside
parameter expansions and arithmetic, and never inside a heredoc body (bash(1) "COMMENTS", "QUOTING",
"Here Documents"; POSIX XCU 2.3 "Token Recognition", rule 9, and 2.7.4 "Here-Document").
"""

from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
import os

from .core import COMMENT, KEEP, LITERAL, Span, is_notice, is_pointer

KEY = 'shell'
EXTS = frozenset({'.sh', '.bash'})
INTERPRETERS = ('sh', 'bash', 'dash')
MARKER = '#'
WORD_BREAK = set(' \t\n;&|()<>')
# a script that reads its own text or counts its own lines is line-sensitive: left alone
SELF_READ = re.compile(r'(\$0|\$\{0\}|BASH_SOURCE|LINENO|\bcaller\b)')
SELF_TOOLS = re.compile(r'\b(sed|awk|grep|head|tail|cat|cut|perl|python3?|dd|tr)\b')


class ShellLexError(ValueError):
    pass


def keep_empty_after(line: str) -> bool:
    """A line ending in an unescaped backslash continues into the next one (bash(1) "QUOTING")."""
    k = len(line)
    n = 0
    while k > 0 and line[k - 1] == '\\':
        n += 1
        k -= 1
    return n % 2 == 1


def joiner(src: str, start: int, end: int) -> str:
    return ''   # a shell comment always runs to the end of its line


class _Lexer:
    def __init__(self, src):
        self.s = src
        self.n = len(src)
        self.spans = []
        self.heredocs = []   # pending (delimiter, strip_tabs)

    def word_start(self, i):
        # a backslash-newline is removed before the shell reads words, so look past it
        while i >= 2 and self.s[i - 1] == '\n' and self.s[i - 2] == '\\' and self.escaped(i - 2):
            i -= 2
        if i == 0:
            return True
        c = self.s[i - 1]
        if c in WORD_BREAK:
            # a blank escaped by a backslash belongs to the word before it
            if c in ' \t' and i >= 2 and self.s[i - 2] == '\\':
                k, bs = i - 2, 0
                while k >= 0 and self.s[k] == '\\':
                    bs += 1
                    k -= 1
                return bs % 2 == 0
            return True
        return False

    def escaped(self, k):
        """True when the backslash at k is itself unescaped (an odd run of backslashes ends at k)."""
        bs = 0
        while k >= 0 and self.s[k] == '\\':
            bs += 1
            k -= 1
        return bs % 2 == 1

    def code(self, i, stop=None, depth=0):
        """Shell code from i until `stop` (')' of a $( ), or the end); returns the index after it."""
        s, n = self.s, self.n
        paren = 0
        while i < n:
            c = s[i]
            if c == '\n':
                i += 1
                if self.heredocs:
                    i = self.bodies(i)
                continue
            if c == '\\':
                i += 2
                continue
            if c == '#' and self.word_start(i):
                j = s.find('\n', i)
                j = n if j < 0 else j
                self.comment(i, j)
                i = j
                continue
            if c == "'":
                j = s.find("'", i + 1)
                if j < 0:
                    raise ShellLexError('unterminated single quote')
                self.literal(i, j + 1)
                i = j + 1
                continue
            if c == '"':
                i = self.dquote(i + 1)
                continue
            if c == '`':
                i = self.backquote(i + 1)
                continue
            if c == '$':
                nx = s[i + 1] if i + 1 < n else ''
                if nx == "'":
                    i = self.ansi(i + 2)
                    continue
                if nx == '"':
                    i = self.dquote(i + 2)
                    continue
                if s.startswith('$((', i):
                    i = self.arith(i + 3)
                    continue
                if nx == '(':
                    i = self.code(i + 2, stop=')', depth=depth + 1)
                    continue
                if nx == '{':
                    i = self.braced(i + 2)
                    continue
                i += 2 if nx else 1
                continue
            if c == '(' and s.startswith('((', i) and self.word_start(i):
                i = self.arith(i + 2)
                continue
            if c == '<' and s.startswith('<<<', i):
                i += 3          # a here-string, not a heredoc
                continue
            if c == '<' and s.startswith('<<', i):
                i = self.heredoc_op(i + 2)
                continue
            if stop is not None:
                if c == '(':
                    paren += 1
                elif c == ')':
                    if paren == 0:
                        return i + 1
                    paren -= 1
            i += 1
        if stop is not None:
            raise ShellLexError('unterminated $(')
        return i

    def comment(self, i, j):
        text = self.s[i:j]
        if i == 0 and text.startswith('#!'):
            self.spans.append(Span(i, j, KEEP, 'interpreter line'))
        elif is_pointer(text):
            self.spans.append(Span(i, j, KEEP, 'pointer line'))
        elif re.match(r'#.*-\*-.*-\*-', text) or re.search(r'\b(vim?|ex):\s*\S', text):
            self.spans.append(Span(i, j, KEEP, 'editor modeline'))
        elif is_notice(text):
            self.spans.append(Span(i, j, KEEP, 'licence or copyright notice'))
        else:
            self.spans.append(Span(i, j, COMMENT))

    def literal(self, i, j):
        if '\n' in self.s[i:j]:
            self.spans.append(Span(i, j, LITERAL, 'multi-line quoted string'))

    def dquote(self, i):
        s, n = self.s, self.n
        start = i - 1
        while i < n:
            c = s[i]
            if c == '\\':
                i += 2
                continue
            if c == '"':
                if '\n' in s[start:i] and '$(' not in s[start:i] and '`' not in s[start:i]:
                    self.spans.append(Span(start, i + 1, LITERAL, 'multi-line quoted string'))
                return i + 1
            if c == '$':
                if s.startswith('$((', i):
                    i = self.arith(i + 3)
                    continue
                if s.startswith('$(', i):
                    i = self.code(i + 2, stop=')')
                    continue
                if s.startswith('${', i):
                    i = self.braced(i + 2)
                    continue
            if c == '`':
                i = self.backquote(i + 1)
                continue
            i += 1
        raise ShellLexError('unterminated double quote')

    def ansi(self, i):
        s, n = self.s, self.n
        start = i - 2
        while i < n:
            if s[i] == '\\':
                i += 2
                continue
            if s[i] == "'":
                self.literal(start, i + 1)
                return i + 1
            i += 1
        raise ShellLexError("unterminated $'")

    def backquote(self, i):
        # the old command substitution: kept whole as data, comments inside it included
        s, n = self.s, self.n
        start = i - 1
        while i < n:
            if s[i] == '\\':
                i += 2
                continue
            if s[i] == '`':
                if '\n' in s[start:i]:
                    self.spans.append(Span(start, i + 1, LITERAL, 'backquoted command'))
                return i + 1
            i += 1
        raise ShellLexError('unterminated backquote')

    def braced(self, i):
        """${...}: no comments inside (`${#v}`, `${v#p}` are operators); nested quotes and expansions."""
        s, n = self.s, self.n
        depth = 1
        while i < n:
            c = s[i]
            if c == '\\':
                i += 2
                continue
            if c == "'":
                j = s.find("'", i + 1)
                if j < 0:
                    raise ShellLexError('unterminated quote in ${')
                i = j + 1
                continue
            if c == '"':
                i = self.dquote(i + 1)
                continue
            if c == '`':
                i = self.backquote(i + 1)
                continue
            if c == '$' and s.startswith('$(', i) and not s.startswith('$((', i):
                i = self.code(i + 2, stop=')')
                continue
            if c == '$' and s.startswith('${', i):
                depth += 1
                i += 2
                continue
            if c == '}':
                depth -= 1
                if depth == 0:
                    return i + 1
            i += 1
        raise ShellLexError('unterminated ${')

    def arith(self, i):
        """$(( )) and (( )): arithmetic, where `#` is a number base (2#101), never a comment."""
        s, n = self.s, self.n
        depth = 0
        while i < n:
            c = s[i]
            if c == '(':
                depth += 1
            elif c == ')':
                if depth == 0 and s.startswith('))', i):
                    return i + 2
                depth -= 1
            elif c == '$' and s.startswith('$(', i) and not s.startswith('$((', i):
                i = self.code(i + 2, stop=')')
                continue
            elif c == '"':
                i = self.dquote(i + 1)
                continue
            i += 1
        raise ShellLexError('unterminated arithmetic')

    def heredoc_op(self, i):
        s, n = self.s, self.n
        strip = False
        if i < n and s[i] == '-':
            strip = True
            i += 1
        while i < n and s[i] in ' \t':
            i += 1
        word = []
        while i < n and s[i] not in ' \t\n;&|()<>':
            c = s[i]
            if c == '\\':
                if i + 1 < n:
                    word.append(s[i + 1])
                i += 2
                continue
            if c in '\'"':
                j = s.find(c, i + 1)
                if j < 0:
                    raise ShellLexError('unterminated heredoc delimiter')
                word.append(s[i + 1:j])
                i = j + 1
                continue
            word.append(c)
            i += 1
        if not word:
            raise ShellLexError('heredoc without delimiter')
        self.heredocs.append((''.join(word), strip))
        return i

    def bodies(self, i):
        """The bodies of every heredoc opened on the line just ended: data up to each delimiter line."""
        s, n = self.s, self.n
        for delim, strip in self.heredocs:
            start = i
            while True:
                if i >= n:
                    raise ShellLexError('heredoc body runs to the end of the file')
                j = s.find('\n', i)
                j = n if j < 0 else j
                line = s[i:j]
                if (line.lstrip('\t') if strip else line) == delim:
                    if i > start:
                        self.spans.append(Span(start, i, LITERAL, 'heredoc body'))
                    i = j + 1 if j < n else n
                    break
                i = j + 1
        self.heredocs = []
        return i


def lex(src: str, whole_line_only: bool = False) -> list:
    """whole_line_only: files that other parsers read too (/etc/default, environment files): only lines
    that are nothing but a comment go, which every such parser agrees are comments."""
    lx = _Lexer(src)
    lx.code(0)
    if lx.heredocs:
        raise ShellLexError('heredoc without body')
    spans = sorted(lx.spans, key=lambda sp: sp.start)
    if whole_line_only:
        fixed = []
        for sp in spans:
            if sp.kind == COMMENT:
                ls = src.rfind('\n', 0, sp.start) + 1
                if src[ls:sp.start].strip(' \t') != '':
                    sp = Span(sp.start, sp.end, KEEP, 'inline comment in a file other parsers read')
            fixed.append(sp)
        spans = fixed
    return spans


def refuse(src: str) -> str | None:
    """A reason to leave this script alone, or None."""
    for line in src.splitlines():
        if SELF_READ.search(line) and SELF_TOOLS.search(line):
            return 'reads its own text or line numbers ($0, BASH_SOURCE, LINENO, caller)'
        if 'LINENO' in line or re.search(r'\bcaller\b', line):
            return 'uses line numbers (LINENO, caller)'
    if '\0' in src:
        return 'carries binary data'
    return None


# The consumer's own parser decides: bash parses the script as one function body and prints it back
# with `declare -f`, which drops comments and nothing else; equal prints mean equal programs. One bash
# stays open per projecting process (starting bash per file was most of a projection's cost). It runs
# with every builtin but the few the loop needs switched off and an empty PATH, as nobody when the
# projection runs as root, so a broken script whose stray `}` ends the function early cannot run anything.
_BASH_SERVER = r"""
shopt -s extglob
tok=$1
for b in $(compgen -b); do
	case $b in eval|declare|printf|read|unset|shopt|builtin|enable|return|true|false|local) ;;
	*) enable -n "$b" 2>/dev/null ;;
	esac
done
PATH=/nonexistent
while IFS= read -r f; do
	unset -f __lean_check
	src=$(<"$f")
	if eval "__lean_check() {
$src
}" 2>/dev/null; then declare -f __lean_check; s=0; else s=1; fi
	printf '\n%s %s\n' "$tok" "$s"
done
"""

_bash = None
_bash_tok = None


def _bash_server():
    global _bash, _bash_tok
    if _bash is not None and _bash.poll() is None:
        return _bash
    bash = shutil.which('bash')
    if not bash:
        return None
    _bash_tok = '__LEAN_END_' + os.urandom(8).hex() + '__'
    cmd = [bash, '--norc', '--noprofile', '-c', _BASH_SERVER, 'lean-check', _bash_tok]
    if os.geteuid() == 0 and shutil.which('setpriv'):
        cmd = ['setpriv', '--reuid=nobody', '--regid=nogroup', '--clear-groups', *cmd]
    _bash = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             cwd='/', bufsize=0)
    return _bash


def bash_print(text: str) -> str | None:
    global _bash
    with tempfile.NamedTemporaryFile('w', suffix='.sh', delete=False, encoding='utf-8', errors='surrogateescape') as f:
        f.write(text)
        p = f.name
    os.chmod(p, 0o644)
    try:
        for attempt in (1, 2):
            srv = _bash_server()
            if srv is None:
                return None
            try:
                srv.stdin.write(p.encode() + b'\n')
                out = []
                end = (_bash_tok + ' ').encode()
                while True:
                    line = srv.stdout.readline()
                    if not line:
                        raise BrokenPipeError
                    if line.startswith(end):
                        status = line[len(end):].strip()
                        break
                    out.append(line)
                if status != b'0':
                    return None
                return b''.join(out).decode('utf-8', 'surrogateescape')
            except (BrokenPipeError, OSError):
                _bash = None     # it died (a script that broke the loop): start it once more, then give up
        return None
    finally:
        os.unlink(p)


def verify(original: str, lean: str, interpreter: str = 'bash') -> tuple:
    a = bash_print(original)
    if a is None:
        return False, 'bash could not parse the authored script as a function body'
    b = bash_print(lean)
    if b != a:
        return False, 'bash parses the lean script differently'
    if interpreter == 'dash' and shutil.which('dash'):
        with tempfile.NamedTemporaryFile('w', suffix='.sh', delete=False, encoding='utf-8', errors='surrogateescape') as f:
            f.write(lean)
            p = f.name
        try:
            r = subprocess.run(['dash', '-n', p], capture_output=True, timeout=60)
        finally:
            os.unlink(p)
        if r.returncode != 0:
            return False, 'dash -n rejects the lean script'
    return True, 'bash declare -f prints the same program'
