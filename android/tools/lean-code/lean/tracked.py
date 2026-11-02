# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""Copy a recipe folder as its checkout holds it (ORDER 339's rule; Daniel, 2026-10-29: "nothing private online").

Inside a git work tree: exactly the files git tracks, with their working bytes (`git ls-files --stage -z`), refusing
unresolved index entries, submodules and symlinked parents. Outside one: the folder without __pycache__ and *.pyc.
The rule used is said; a git failure stops the copy instead of widening it, so a broken checkout never passes for an
export. The build repository's tools/copy-recipe.py applies the same rule to recipe inputs; this module carries it
in the toolkit, which ships inside dpkg-stereo.
"""

from __future__ import annotations

import os
from pathlib import Path
import stat
import subprocess


def _git(source: Path, *args):
    return subprocess.run(['git', '-C', str(source), *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          env=dict(os.environ, LC_ALL='C'))


def inventory(source: Path):
    """(the rule used, the relative paths to copy)."""
    probe = _git(source, 'rev-parse', '--is-inside-work-tree')
    if probe.returncode == 0:
        if probe.stdout.strip() != b'true':
            raise ValueError(f'{source}: not a git work tree')
        listing = _git(source, 'ls-files', '--stage', '-z', '--', '.')
        if listing.returncode:
            raise ValueError(listing.stderr.decode(errors='replace').strip())
        paths = []
        for entry in listing.stdout.split(b'\0'):
            if not entry:
                continue
            info, name = entry.split(b'\t', 1)
            mode, _, number = info.split()
            if number != b'0' or mode not in (b'100644', b'100755', b'120000'):
                raise ValueError(f'{os.fsdecode(name)!r}: unresolved index entry or submodule; export it separately')
            paths.append(Path(os.fsdecode(name)))
        if not paths:
            raise ValueError(f'{source}: git tracks no file here; add the recipe inputs to git first')
        return 'the files git tracks', paths
    for parent in (source, *source.parents):
        if os.path.lexists(parent / '.git'):
            raise ValueError(probe.stderr.decode(errors='replace').strip() or f'{parent}: git cannot read it')
    if probe.returncode != 128 or not probe.stderr.startswith(b'fatal: not a git repository'):
        raise ValueError(probe.stderr.decode(errors='replace').strip() or 'git failed')
    paths = []

    def failed(error):
        raise error

    for folder, directories, files in os.walk(source, onerror=failed, followlinks=False):
        for name in directories[:]:
            path = Path(folder) / name
            if name in ('.git', '__pycache__'):
                directories.remove(name)
            elif path.is_symlink():
                directories.remove(name)
                if not name.endswith('.pyc'):
                    paths.append(path.relative_to(source))
        paths.extend((Path(folder) / name).relative_to(source) for name in files
                     if name not in ('.git', '__pycache__') and not name.endswith('.pyc'))
    return 'no git work tree: the folder without __pycache__ and *.pyc', paths


def copy(source, destination, log=print):
    """Copy the contents of `source` into `destination` by the rule; returns the rule used."""
    source, destination = Path(source).resolve(strict=True), Path(destination).absolute()
    if destination.resolve().is_relative_to(source):
        raise ValueError('the destination must lie outside the source folder')
    rule, paths = inventory(source)
    for relative in paths:
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError(f'invalid source path: {relative}')
        for parent in relative.parents:
            if (source / parent).is_symlink():
                raise ValueError(f'{source / parent}: a source folder is a symlink')
        mode = (source / relative).lstat().st_mode
        if not (stat.S_ISREG(mode) or stat.S_ISLNK(mode)):
            raise ValueError(f'{source / relative}: expected a file or a symlink')
        for target in (destination / relative, *(destination / relative).parents):
            if target.is_symlink():
                raise ValueError(f'{target}: the destination holds a symlink')
    destination.mkdir(parents=True, exist_ok=True)
    for start in range(0, len(paths), 128):
        subprocess.run(['cp', '-a', '--parents', '-t', str(destination), '--',
                        *(str(p) for p in paths[start:start + 128])], cwd=source, check=True)
    log(f'lc1: authoring inputs from {source}: {rule} ({len(paths)} files)')
    return rule
