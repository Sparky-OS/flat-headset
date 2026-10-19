#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright 2026 Daniel Campos Ramos
"""Resolve a projected Java/Kotlin diagnostic to its preserved authored source."""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / 'lean-code'))
from lean import project

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--evidence', type=Path, required=True)
parser.add_argument('--component', required=True)
parser.add_argument('--root', type=Path, required=True, help='Projected component source root')
parser.add_argument('path', help='Relative file path from the build diagnostic')
parser.add_argument('line', type=int, help='One-based projected line')
parser.add_argument('--column', type=int, help='One-based Unicode code-point column, not UTF-16 units')
args = parser.parse_args()
try:
    rel = Path(args.path)
    if rel.is_absolute() or '..' in rel.parts or not args.component.replace('-', '').replace('_', '').isalnum():
        raise ValueError('Use a component name and a relative path without parent traversal')
    record = json.loads((args.evidence / f'{args.component}.json').read_text())['files'][rel.as_posix()]
    mr = (args.root / rel).read_bytes()
    lines = mr.decode('utf-8').split('\n')
    if not 1 <= args.line <= len(lines):
        raise ValueError('Line must be a one-based location within the projected file')
    if args.column is not None and not 1 <= args.column <= len(lines[args.line - 1]) + 1:
        raise ValueError('Column must be a one-based Unicode code-point location within the line')
    if project.sha256(mr) != record['final_sha256']:
        raise ValueError('Projected source changed since the recorded build')
    if record['status'] == 'projected':
        hr_path = args.evidence / 'authored' / args.component / rel
        hr = hr_path.read_bytes()
        if project.sha256(hr) != record['sha_in']:
            raise ValueError('Authored source differs from the recorded input')
        line, column = project.resolve(hr, mr, '/' + rel.as_posix(), 'flat-headset-native',
                                       args.line, args.column, pointer=False)
    else:
        hr_path, line, column = args.root / rel, args.line, args.column
    print(f'{hr_path}:{line}' + (f':{column}' if column is not None else ''))
except (OSError, ValueError, KeyError, project.StaleMr) as error:
    parser.exit(1, str(error) + '\n')
