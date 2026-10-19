#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright 2026 Daniel Campos Ramos
"""Fetch the qualified lexer jars; print a classpath, never execute downloaded code here."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('cache', type=Path)
args = parser.parse_args()
args.cache.mkdir(parents=True, exist_ok=True)
paths = []
for artifact in json.loads(Path(__file__).with_name('kotlin-lexer-lock.json').read_text()):
    path = args.cache.resolve() / artifact['url'].rsplit('/', 1)[1]
    if not path.exists():
        with tempfile.NamedTemporaryFile(dir=args.cache, delete=False) as stream:
            temporary = Path(stream.name)
            try:
                with urllib.request.urlopen(artifact['url'], timeout=60) as response:
                    while block := response.read(1 << 20):
                        stream.write(block)
                stream.flush()
                if hashlib.sha256(temporary.read_bytes()).hexdigest() != artifact['sha256']:
                    raise ValueError(f'Checksum mismatch: {artifact["coordinate"]}')
                os.link(temporary, path)
            finally:
                temporary.unlink(missing_ok=True)
    if hashlib.sha256(path.read_bytes()).hexdigest() != artifact['sha256']:
        raise ValueError(f'Cache checksum mismatch: {path}')
    paths.append(str(path))
print(os.pathsep.join(paths))
