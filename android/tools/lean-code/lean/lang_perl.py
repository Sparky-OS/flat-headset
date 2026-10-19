# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""Perl: whole comment lines only, outside POD, heredoc bodies and the __END__/__DATA__ section.

Perl is the hardest language here to read without perl itself (quote-like operators with any
delimiter, `#` inside /x regular expressions, heredocs), so this handler removes only lines that are a
comment from their first non-blank character, skips POD (perlpod: kept, it is program documentation),
heredoc bodies (perlop "Quote and Quote-like Operators") and everything after __END__/__DATA__, keeps
`# line N "file"` directives (perlsyn "Plain Old Comments (Not!)"), and lets perl decide: B::Deparse
must print the same program for both texts (it prints the compiled op tree, never comments). Deparse
compiles the file, so it runs as an unprivileged user when the projection runs as root.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile

from .core import COMMENT, KEEP, LITERAL, Span, is_notice, is_pointer

KEY = 'perl'
EXTS = frozenset({'.pl', '.pm'})
MARKER = '#'
LINE_DIRECTIVE = re.compile(r'#\s*line\s+\d+')
HEREDOC = re.compile(r'<<(~?)\s*(?:"([^"\n]*)"|\'([^\'\n]*)\'|([A-Za-z_]\w*))')
keep_empty_after = None


def joiner(src, start, end):
    return ''


def lex(src: str) -> list:
    spans = []
    lines = src.split('\n')
    pos = 0
    pod = False
    pending = []
    i = 0
    offs = []
    for ln in lines:
        offs.append(pos)
        pos += len(ln) + 1
    while i < len(lines):
        line = lines[i]
        a = offs[i]
        if pending:
            delim, indent = pending.pop(0)
            j = i
            while j < len(lines):
                t = lines[j].strip() if indent else lines[j]
                if t == delim:
                    break
                j += 1
            if j > i:
                spans.append(Span(offs[i], offs[j - 1] + len(lines[j - 1]), LITERAL, 'heredoc body'))
            i = j + 1
            continue
        if pod:
            if line.startswith('=cut'):
                pod = False
            i += 1
            continue
        if re.match(r'=[a-zA-Z]', line):
            pod = True
            i += 1
            continue
        if line.startswith(('__END__', '__DATA__')):
            break
        t = line.lstrip(' \t')
        if t.startswith('#'):
            if i == 0 and t.startswith('#!'):
                spans.append(Span(a, a + len(line), KEEP, 'interpreter line'))
            elif LINE_DIRECTIVE.match(t):
                spans.append(Span(a, a + len(line), KEEP, 'line directive read by perl'))
            elif is_pointer(t) or is_notice(t):
                spans.append(Span(a, a + len(line), KEEP, 'notice or pointer'))
            else:
                spans.append(Span(a + len(line) - len(t), a + len(line), COMMENT))
        else:
            for m in HEREDOC.finditer(line):
                pending.append((m.group(2) or m.group(3) or m.group(4), bool(m.group(1))))
        i += 1
    return spans


def refuse(src):
    if '\0' in src:
        return 'carries binary data'
    if '__LINE__' in src or 'caller' in src and 'line' in src:
        return 'uses line numbers (__LINE__, caller)'
    if re.search(r'\$0|__FILE__', src) and re.search(r'\bopen\b', src):
        return 'may read its own source'
    return None


def deparse(text: str):
    perl = shutil.which('perl')
    if not perl:
        return None
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, 'f.pl')
        with open(p, 'w', encoding='utf-8', errors='surrogateescape') as f:
            f.write(text)
        os.chmod(d, 0o755)
        os.chmod(p, 0o644)
        cmd = [perl, '-MO=Deparse,-q', p]
        if os.geteuid() == 0 and shutil.which('setpriv'):
            cmd = ['setpriv', '--reuid=nobody', '--regid=nogroup', '--clear-groups', *cmd]
        try:
            r = subprocess.run(cmd, capture_output=True, timeout=120, cwd=d)
        except subprocess.TimeoutExpired:
            return None
        if r.returncode != 0:
            return None
        return r.stdout.decode('utf-8', 'surrogateescape').replace(p, 'FILE')


def verify(original, lean, interpreter=''):
    a = deparse(original)
    if a is None:
        return False, 'perl could not compile the authored file on its own'
    b = deparse(lean)
    if a != b:
        return False, 'B::Deparse prints a different program'
    return True, 'B::Deparse prints the same program'
