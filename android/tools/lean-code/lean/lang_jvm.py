# SPDX-License-Identifier: MIT
# Copyright 2026 Daniel Campos Ramos
"""Compiler-backed Java/Kotlin adapters; preserve documentation consumed by processors."""
import atexit
import base64
from functools import lru_cache
import os
from pathlib import Path
import re
import subprocess
import tempfile

from .core import COMMENT, KEEP, LITERAL, Span, is_notice, is_pointer

_process = None
_temporary = None
COMMENTS = {'EOL_COMMENT', 'BLOCK_COMMENT', 'DOC_COMMENT', 'KDOC', 'SHEBANG_COMMENT'}
DIRECTIVE = re.compile(r'noinspection|suppress|formatter\s*:|language\s*=|CHECKSTYLE|ktlint|detekt|region\b|endregion\b', re.I)


def _close():
    global _process
    if _process is not None:
        _process.stdin.close()
        try:
            _process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            _process.kill()
            _process.wait()
        _process = None
    if _temporary:
        _temporary.cleanup()


atexit.register(_close)


def _server():
    global _process, _temporary
    if _process is not None:
        return _process
    _temporary = tempfile.TemporaryDirectory(prefix='lean-jvm-')
    jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
    javac = str(jdk / 'javac') if jdk else 'javac'
    java = str(jdk / 'java') if jdk else 'java'
    exports = ['--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED',
               '--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED']
    subprocess.run([javac, *exports, '-d', _temporary.name, str(Path(__file__).with_name('JvmLexer.java'))],
                   check=True, capture_output=True, timeout=60)
    cp = _temporary.name + os.pathsep + os.environ.get('LEAN_KOTLIN_CLASSPATH', '')
    _process = subprocess.Popen([java, *exports, '-Xmx512m', '-cp', cp, 'JvmLexer'], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding='utf-8')
    return _process


@lru_cache(maxsize=128)
def tokens(language, text):
    p = _server()
    p.stdin.write(language + '\t' + base64.b64encode(text.encode()).decode() + '\n')
    p.stdin.flush()
    reply = p.stdout.readline().rstrip('\n')
    status, sep, data = reply.partition('\t')
    if not sep:
        raise ValueError('Compiler lexer stopped without a response')
    result = base64.b64decode(data).decode()
    if status != 'OK':
        raise ValueError(result)
    # Compiler offsets are UTF-16 code units; the renderer maps Python code points.
    offsets = [0]
    for i, ch in enumerate(text):
        if ord(ch) > 0xffff:
            offsets.append(i)
        offsets.append(i + 1)
    return tuple((kind, offsets[int(start)], offsets[int(end)])
                 for kind, start, end in (line.split('\t') for line in result.splitlines()))


def comment_span(text, start, end, language):
    raw = text[start:end]
    if raw.startswith('/*'):
        if language == 'kotlin':
            depth = 0
            for match in re.finditer(r'/\*|\*/', raw):
                depth += 1 if match[0] == '/*' else -1
                if depth < 0:
                    raise ValueError('Invalid nested comment')
            if depth:
                raise ValueError('Unterminated Kotlin comment')
        elif not raw.endswith('*/'):
            raise ValueError('Unterminated Java comment')
    if raw.startswith('/**'):
        return Span(start, end, KEEP, 'documentation consumed by annotation/symbol processors')
    if is_notice(raw) or is_pointer(raw) or DIRECTIVE.search(raw) or raw.startswith(('#!', '/*@', '/*-{')):
        return Span(start, end, KEEP, 'notice or tooling directive')
    return Span(start, end, COMMENT, 'explanatory comment')


def lex(language, text):
    spans = []
    for kind, start, end in tokens(language, text):
        raw = text[start:end]
        if language == 'java' and kind == 'GAP':
            # javac already translated Unicode escapes. Keep ambiguous raw gaps;
            # executable tokens following an escaped newline stay outside this gap.
            if re.search(r'\\u+[0-9a-fA-F]{4}', raw):
                spans.append(Span(start, end, LITERAL, 'Unicode-translated Java gap'))
                continue
            at = start
            for m in re.finditer(r'\s+|//[^\r\n]*|/\*[\s\S]*?\*/', raw):
                if start + m.start() != at:
                    raise ValueError('Unrecognized Java intertoken input')
                at = start + m.end()
                if m[0].startswith('/'):
                    spans.append(comment_span(text, start + m.start(), at, language))
            if at != end:
                raise ValueError('Incomplete Java intertoken input')
        elif language == 'kotlin' and kind in COMMENTS:
            spans.append(comment_span(text, start, end, language))
        elif kind not in {'WHITE_SPACE', 'GAP'}:
            spans.append(Span(start, end, LITERAL, 'compiler token'))
    return spans


def signature(language, text):
    result = []
    newline = separated = False
    for kind, start, end in tokens(language, text):
        raw = text[start:end]
        if language == 'kotlin' and kind in COMMENTS:
            separated = True
        elif kind in {'WHITE_SPACE', 'GAP'}:
            separated = True
            if kind == 'WHITE_SPACE':
                newline |= '\n' in raw
        else:
            result.append((kind, raw, newline if language == 'kotlin' and result else False,
                           separated if language == 'kotlin' and result else False))
            newline = separated = False
    return result


def verify(language, original, lean):
    try:
        if signature(language, original) != signature(language, lean):
            return False, f'{language} compiler tokens or significant separators differ'
        before = [original[s.start:s.end] for s in lex(language, original)
                  if s.kind == KEEP and not is_pointer(original[s.start:s.end])]
        after = [lean[s.start:s.end] for s in lex(language, lean)
                 if s.kind == KEEP and not is_pointer(lean[s.start:s.end])]
        if before != after:
            return False, 'operational or documentation comments changed'
        return True, f'{language} compiler lexer: identical tokens, separators and retained directives'
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        return False, str(error)
