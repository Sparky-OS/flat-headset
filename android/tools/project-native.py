#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright 2026 Daniel Campos Ramos
"""Project disposable C/C++, Java and Kotlin inputs with the edition's Lean Code engine."""
import argparse
from dataclasses import asdict
import fcntl
import hashlib
import json
import os
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent / 'lean-code'))
from lean import VERSION, identities, lang_c, project

# This file has a second consumer besides the C compiler: FreeRDP's
# include/CMakeLists.txt parses the trailing numeric comments into settings IDs.
GENERATOR_INPUTS = {
    ('freerdp', 'include/freerdp/settings_types_private.h'):
        'FreeRDP CMake settings-key generator reads numeric comments as IDs',
}

def digest(data):
    return hashlib.sha256(data).hexdigest()


def check_identity():
    """The vendored engine must be a released renderer: its code is its VERSION's one row of the edition's version
    table (lean/identities.py), so a projection never runs under a label with another engine's code."""
    why = identities.toolkit_problem(VERSION, project.IDENTITY, project.CODE_SHA256)
    if why:
        raise ValueError(f'vendored Lean Code is not a released renderer: {why}; copy the toolkit again from the '
                         f'edition build tree')
    return project.IDENTITY


def native(path):
    """C, C++, assembler sources the C preprocessor reads (.S, .sx, with their case), Java and Kotlin."""
    return path.suffix in lang_c.ASM_EXTS or path.suffix.lower() in lang_c.EXTS | {'.java', '.kt', '.kts'}


def run(root, evidence, component):
    engine = check_identity()
    if not root.is_dir() or not component.replace('-', '').replace('_', '').isalnum():
        raise ValueError('Need an existing disposable root and an alphanumeric component name')
    if evidence.is_relative_to(root):
        raise ValueError('Keep evidence outside the projected source root')
    evidence.mkdir(parents=True, exist_ok=True)
    manifest = evidence / f'{component}.json'
    started = time.monotonic()
    with (evidence / f'{component}.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        pending = evidence / f'{component}.in-progress'
        if pending.exists() or (not manifest.exists() and (evidence / 'authored' / component).exists()):
            raise ValueError(f'{component}: an interrupted projection needs a fresh staging tree')
        previous = json.loads(manifest.read_text()) if manifest.exists() else {'files': {}}
        if previous.get('engine', engine) != engine:
            raise ValueError('Lean engine changed; use a fresh staging tree')
        records = previous['files']
        pending.write_text('Projection in progress; do not reuse partial source as authored input.\n')
        for name in records:
            if not (root / name).is_file():
                raise ValueError(f'{component}/{name}: recorded source disappeared')
        for path in sorted(root.rglob('*')):
            rel = path.relative_to(root)
            if (not path.is_file() or path.is_symlink() or
                    any(x in {'.git', '.gradle', '.cxx', 'build', 'tools'} for x in rel.parts) or
                    not native(path)):
                continue
            name = rel.as_posix()
            if component == 'freerdp' and name.startswith((
                    'client/Android/Studio/freeRDPCore/src/main/cpp/external/',
                    'client/Android/Studio/freeRDPCore/src/main/jniLibs/')):
                continue  # Dependencies have their own isolated ABI-specific projection.
            raw = path.read_bytes()
            metadata = path.stat()
            old = records.get(name)
            if (component, name) in GENERATOR_INPUTS:
                if old and old['status'] == 'projected':
                    original = evidence / 'authored' / component / rel
                    hr = original.read_bytes()
                    if digest(raw) != old['final_sha256'] or digest(hr) != old['sha_in']:
                        raise ValueError(f'{component}/{name}: changed source prevents authored recovery')
                    raw = hr
                    path.write_bytes(raw)
                result = project.Result('/' + name, 'left', GENERATOR_INPUTS[(component, name)],
                                        bytes_in=len(raw), bytes_out=len(raw), sha_in=digest(raw), sha_out=digest(raw))
                record = asdict(result)
                record.pop('lean')
                record.update(path=name, final_sha256=digest(raw))
                records[name] = record
                continue
            if (old and digest(raw) == old['final_sha256'] and
                    not old['reason'].startswith(('check failed:', 'the c reader', 'the java reader', 'the kotlin reader', 'not stable'))):
                if old['status'] == 'projected':
                    original = evidence / 'authored' / component / rel
                    if digest(original.read_bytes()) != old['sha_in']:
                        raise ValueError(f'{component}/{name}: authored recovery copy changed')
                continue
            original = evidence / 'authored' / component / rel
            if old and original.exists() and digest(raw) != old['sha_in']:
                raise ValueError(f'{component}/{name}: source changed after projection; use a fresh staging tree')
            result = project.project('/' + name, raw, package='flat-headset-native', pointer=False)
            record = asdict(result)
            record.pop('lean')
            record['path'] = name
            record['source_mtime_ns'] = metadata.st_mtime_ns
            record['final_sha256'] = digest(result.lean if result.lean is not None else raw)
            records[name] = record
            if result.status == 'projected':
                original.parent.mkdir(parents=True, exist_ok=True)
                original.write_bytes(raw)
                path.write_bytes(result.lean)
                os.utime(path, ns=(metadata.st_atime_ns, metadata.st_mtime_ns))
        report = {'schema': 1, 'engine': engine, 'component': component,
                  'stage': 'before compilation', 'options': f'{project.OPTIONS},no-pointer,JVM source mapping',
                  'seconds': round(time.monotonic() - started, 3), 'files': records}
        temporary = manifest.with_suffix('.json.tmp')
        temporary.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
        temporary.replace(manifest)
        pending.unlink()
        failures = [n for n, r in records.items() if r['reason'].startswith(('check failed:', 'the c reader', 'the java reader', 'the kotlin reader', 'not stable'))]
        changed = [r for r in records.values() if r['status'] == 'projected']
        print(f'{component}: {len(changed)}/{len(records)} native files projected, '
              f'{sum(r["bytes_in"]-r["bytes_out"] for r in changed)} bytes removed; {len(failures)} failed checks', flush=True)
        if failures:
            raise ValueError('Projection checks failed: ' + ', '.join(failures[:15]))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-identity', action='store_true', help='only check the vendored engine is released')
    parser.add_argument('--root', type=Path)
    parser.add_argument('--component')
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    try:
        if args.check_identity:
            print(check_identity())
            sys.exit(0)
        if not (args.root and args.component and args.evidence):
            parser.error('--root, --component and --evidence are required')
        run(args.root.resolve(), args.evidence.resolve(), args.component)
    except (ValueError, OSError) as error:
        parser.exit(1, str(error) + '\n')
