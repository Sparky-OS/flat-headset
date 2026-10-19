# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""QML and JavaScript: `//` and `/* */` comments, found outside strings, template literals and regular
expression literals.

Ported from CGM's _lang_qml (its string and regular-expression scan), widened to JavaScript files and
corrected where the language requires it (ECMA-262, "Comments" and "Automatic Semicolon Insertion"):
  - a removed comment between two tokens on one line becomes one space, so tokens never merge;
  - a removed multi-line comment with code after it becomes a line terminator, because ECMAScript
    treats a MultiLineComment holding a line terminator as a LineTerminator for semicolon insertion;
  - template literals (`...${expr}...`) are data, with their nested expressions read as code.
Kept comments: `//#`, `//@` (source maps, tooling directives), `/*!` and `@license`/`@preserve`
blocks, Qt's translator comments (`//:`, `//=`, `//~`, read by lupdate), notices.
The check is an independent tokenizer, acorn (ECMA-262), run by Node: both texts give the same tokens
with the same line-terminator-before flags.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile

from .core import COMMENT, KEEP, LITERAL, Span, is_notice, is_pointer

KEY = 'js'
EXTS = frozenset({'.qml', '.js', '.mjs'})
INTERPRETERS = ('node', 'nodejs')
MARKER = '//'
REGEX_AFTER_WORDS = {'return', 'typeof', 'instanceof', 'in', 'of', 'new', 'delete', 'void', 'throw', 'case',
                     'do', 'else', 'yield', 'await'}
KEEP_PREFIXES = ('//#', '//@', '/*!', '//:', '//=', '//~', '/*#', '/*@')


class JsLexError(ValueError):
    pass


keep_empty_after = None   # JavaScript has no line continuation outside strings


def joiner(src: str, start: int, end: int) -> str:
    if '\n' in src[start:end] or ' ' in src[start:end] or ' ' in src[start:end]:
        return '\n'
    before = src[start - 1] if start > 0 else ' '
    after = src[end] if end < len(src) else ' '
    if before.isspace() or after.isspace():
        return ''
    return ' '


class _Lexer:
    def __init__(self, src):
        self.s = src
        self.n = len(src)
        self.spans = []
        self.prev = ''        # last significant token: a word, or one punctuator character

    def regex_allowed(self):
        p = self.prev
        if p == '':
            return True
        if p[0].isalnum() or p[0] in '_$':
            return p in REGEX_AFTER_WORDS
        return p not in (')', ']', '}', '"', "'", '`', '.')

    def run(self, i=0, stop=None):
        s, n = self.s, self.n
        depth = 0
        while i < n:
            c = s[i]
            if c in ' \t\r\n\f\v ﻿  ':
                i += 1
                continue
            nx = s[i + 1] if i + 1 < n else ''
            if c == '/' and nx == '/':
                j = i + 2
                while j < n and s[j] not in '\n\r  ':
                    j += 1
                self.comment(i, j)
                i = j
                continue
            if c == '/' and nx == '*':
                j = s.find('*/', i + 2)
                if j < 0:
                    raise JsLexError('unterminated block comment')
                self.comment(i, j + 2)
                i = j + 2
                continue
            if c in '"\'':
                i = self.string(i)
                self.prev = c
                continue
            if c == '`':
                i = self.template(i)
                self.prev = '`'
                continue
            if c == '/' and self.regex_allowed():
                i = self.regex(i)
                self.prev = ')'
                continue
            if c.isalnum() or c in '_$' or ord(c) > 127:
                j = i
                while j < n and (s[j].isalnum() or s[j] in '_$' or ord(s[j]) > 127):
                    j += 1
                if c.isdigit() and j < n and s[j] == '.':
                    j += 1
                    while j < n and (s[j].isalnum() or s[j] == '_'):
                        j += 1
                self.prev = s[i:j]
                i = j
                continue
            if stop is not None:
                if c == '{':
                    depth += 1
                elif c == '}':
                    if depth == 0:
                        return i + 1
                    depth -= 1
            self.prev = c
            i += 1
        if stop is not None:
            raise JsLexError('unterminated ${ in a template literal')
        return i

    def comment(self, i, j):
        text = self.s[i:j]
        if i == 0 and text.startswith('#!'):
            kind, why = KEEP, 'interpreter line'
        elif is_pointer(text):
            kind, why = KEEP, 'pointer line'
        elif text.startswith(KEEP_PREFIXES) or '@license' in text or '@preserve' in text or '@cc_on' in text:
            kind, why = KEEP, 'directive or translator comment read by a tool'
        elif is_notice(text):
            kind, why = KEEP, 'licence or copyright notice'
        else:
            kind, why = COMMENT, ''
        self.spans.append(Span(i, j, kind, why))

    def string(self, i):
        s, n = self.s, self.n
        q = s[i]
        j = i + 1
        while j < n:
            c = s[j]
            if c == '\\':
                j += 2
                continue
            if c == q:
                if '\n' in s[i:j]:
                    self.spans.append(Span(i, j + 1, LITERAL, 'string with a line continuation'))
                return j + 1
            if c == '\n':
                raise JsLexError('newline in a string')
            j += 1
        raise JsLexError('unterminated string')

    def template(self, i):
        """A template literal is data as a whole; its ${ } expressions are read only to find its end."""
        s, n = self.s, self.n
        j = i + 1
        while j < n:
            c = s[j]
            if c == '\\':
                j += 2
                continue
            if c == '`':
                self.spans.append(Span(i, j + 1, LITERAL, 'template literal'))
                return j + 1
            if c == '$' and j + 1 < n and s[j + 1] == '{':
                save = self.prev
                self.prev = ''
                j = self.run(j + 2, stop='}')
                self.prev = save
                continue
            j += 1
        raise JsLexError('unterminated template literal')

    def regex(self, i):
        s, n = self.s, self.n
        j = i + 1
        in_class = False
        while j < n:
            c = s[j]
            if c == '\\':
                j += 2
                continue
            if c == '\n':
                raise JsLexError('newline in a regular expression')
            if in_class:
                if c == ']':
                    in_class = False
            elif c == '[':
                in_class = True
            elif c == '/':
                j += 1
                while j < n and (s[j].isalnum() or s[j] in '_$'):
                    j += 1
                return j
            j += 1
        raise JsLexError('unterminated regular expression')


def lex(src: str) -> list:
    lx = _Lexer(src)
    lx.run(0)
    spans = sorted(lx.spans, key=lambda sp: sp.start)
    out = []
    for sp in spans:
        if out and sp.start < out[-1].end:
            continue
        out.append(sp)
    return out


def refuse(src: str) -> str | None:
    if '\0' in src:
        return 'carries binary data'
    if 'Function.prototype.toString' in src or '.toString().match(' in src:
        return 'reads function source text'
    return None


# acorn runs in one Node process per projecting process, kept open: a line with two file names in, a line
# with both token lists out (starting Node for every file cost more than the projection itself)
_ACORN = r'''
const fs = require('fs');
const readline = require('readline');
let acorn;
try { acorn = require('acorn'); } catch (e) { process.exit(4); }
function toks(f) {
  const src = fs.readFileSync(f, 'utf8');
  const out = [];
  let prevEnd = 0;
  try {
    for (const t of acorn.tokenizer(src, {ecmaVersion: 'latest', allowHashBang: true, locations: true,
                                          allowReturnOutsideFunction: true, allowImportExportEverywhere: true,
                                          allowAwaitOutsideFunction: true, sourceType: 'script'})) {
      const nl = t.loc.start.line > prevEnd ? 1 : 0;
      out.push([t.type.label, t.value === undefined ? null : String(t.value), nl]);
      prevEnd = t.loc.end.line;
    }
  } catch (e) { return null; }
  return out;
}
readline.createInterface({input: process.stdin}).on('line', (line) => {
  const files = JSON.parse(line);
  process.stdout.write(JSON.stringify(files.map(toks)) + '\n');
});
'''

_node = None


def _server():
    global _node
    if _node is not None and _node.poll() is None:
        return _node
    node = shutil.which('node') or shutil.which('nodejs')
    if not node:
        return None
    env = dict(os.environ, NODE_PATH='/usr/share/nodejs:/usr/lib/nodejs:' + os.environ.get('NODE_PATH', ''))
    _node = subprocess.Popen([node, '-e', _ACORN], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, env=env, text=True, bufsize=1)
    return _node


def tokens(*texts) -> list | None:
    paths = []
    try:
        for t in texts:
            with tempfile.NamedTemporaryFile('w', suffix='.js', delete=False, encoding='utf-8', errors='surrogateescape') as f:
                f.write(t)
                paths.append(f.name)
        for attempt in (1, 2):
            srv = _server()
            if srv is None:
                return None
            try:
                srv.stdin.write(json.dumps(paths) + '\n')
                srv.stdin.flush()
                line = srv.stdout.readline()
            except (BrokenPipeError, OSError):
                line = ''
            if line:
                return json.loads(line)
            global _node
            _node = None          # the server died (acorn missing or a crash): start it once more, then give up
        return None
    finally:
        for p in paths:
            os.unlink(p)


def verify(original: str, lean: str, interpreter: str = '') -> tuple:
    res = tokens(original, lean)
    if res is None:
        return False, 'no independent tokenizer (node with acorn) available'
    a, b = res
    if a is None:
        return False, 'acorn could not tokenize the authored file'
    if b is None:
        return False, 'acorn could not tokenize the lean file'
    if len(a) != len(b):
        return False, f'token counts differ ({len(a)} authored, {len(b)} lean)'
    first = True
    for x, y in zip(a, b):
        if first:
            # the pointer line may sit before the first token: the first token's line break is not data
            x, y = x[:2], y[:2]
            first = False
        if x != y:
            return False, f'tokens differ: {x} / {y}'
    return True, 'acorn gives the same tokens and line breaks'
