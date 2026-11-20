#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fetch hash-pinned core sources, or verify and extract an existing archive cache."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('destination', type=Path)
parser.add_argument('--archives', type=Path)
args = parser.parse_args()
lock = Path(__file__).resolve().parents[1] / 'Core/sources.json'
args.destination.mkdir(parents=True, exist_ok=True)
for name, source in json.loads(lock.read_text()).items():
    destination = args.destination / name
    if destination.exists():
        raise SystemExit(f'Refusing existing source directory: {destination}')
    with tempfile.TemporaryDirectory(dir=args.destination) as scratch:
        scratch = Path(scratch)
        archive = args.archives / (name + '.tar.gz') if args.archives else scratch / 'source.tar.gz'
        if not args.archives:
            subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--proto', '=https',
                            source['url'], '--output', str(archive)], check=True)
        with archive.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != source['sha256']:
            raise SystemExit(f'Source hash mismatch: {name}')
        unpacked = scratch / 'unpacked'
        unpacked.mkdir()
        with tarfile.open(archive) as package:
            package.extractall(unpacked, filter='data')
        roots = list(unpacked.iterdir())
        if len(roots) != 1 or not roots[0].is_dir():
            raise SystemExit(f'Unexpected archive root: {name}')
        roots[0].rename(destination)
    print(f'Verified {name} {source["revision"]} {digest}')
