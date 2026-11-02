# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""The version table: every renderer identity a release of this toolkit carried, each lean version with the code
digest of the renderer that built under it (project.code_sha256: RENDERER_FILES and every lang_*.py).

One label, one renderer (ORDER 352): a change to a renderer file moves the digest, so it needs the next VERSION
and its row here, in the same change. tests/test_identities.py fails when this toolkit's own digest is not
its VERSION's row, and release.check_debs refuses a package whose lc1 manifest pairs a label with a digest
this table does not give it (every build-tree, check-debs, check-selection and check-repo run).
This file is no renderer file: it records digests, so it cannot be part of one.

Rows up to 0.1.23 come from the published repository (the lc1 manifests of every package in the trees of rounds
r1101c, r1101d and r1101e, 1,895 archives, and the dpkg-stereo builds that went out), checked against git
history. Before the identity carried its digest (0.1.17) one label could hold several renderers, and three
labels did: 0.1.9 three, 0.1.15 two. 0.1.9's 29b0f156 is in no commit of the build repository (xscreensaver
6.09+dfsg1-2+stereo3d4+lc1, 16 archives, was built from a toolkit tree no commit holds). Labels without a
published package (0.1.0 to 0.1.6, 0.1.11 to 0.1.14, 0.1.16) carried no lc1 manifest that is still served.
"""

from __future__ import annotations

import re

RELEASED = {
    '0.1.7': {'3c395f339acca313ca67526c93edf3099cb8c5652cad1a5a50714ce8880db044': 'lean 0.1.7 (collapse-blank,pointer)'},
    '0.1.8': {'3ae8890d08883db3f95f148b9d1e5b67a88b1827c2b71f6c0179e2b094c7749a': 'lean 0.1.8 (collapse-blank,pointer)'},
    '0.1.9': {'a80628aa963be7a9fffc85af3e0634ccd097666356622c8984a170f04515f938': 'lean 0.1.9 (collapse-blank,pointer)',
              '29b0f156738c8eec8427eee2e43f57d51b1498c1c3a46308c737add06fcb2192': 'lean 0.1.9 (collapse-blank,pointer)',
              '1e3749512e6e5468cb7769a3638a89a32eab1848131f78a7b3fb3c1d980f7756': 'lean 0.1.9 (collapse-blank,pointer)'},
    '0.1.10': {'d14160a6321b96c3ae3a17e97c9ebe079dfbd25fbf46484b8781ee8949d48ae8': 'lean 0.1.10 (collapse-blank,pointer)'},
    '0.1.15': {'1cffe546c5cfeaa508c447f05c1df145c5a44a1035a7e8383f1e7b45f6ba2686': 'lean 0.1.15 (collapse-blank,pointer)',
               '5a7d0b27a4308f9f57743395b6fee6f712b3d1546a67fe22a8fdeaab37537cd1': 'lean 0.1.15 (collapse-blank,pointer)'},
    '0.1.17': {'5d9e7cc6816ab1dc6e2d030a7d5627962a5e9ef97446803fcf262956b848babc':
               'lean 0.1.17 (collapse-blank,pointer,c-line-breaks) code 5d9e7cc6816ab1dc'},
    '0.1.18': {'daacaf045271700d3de5fdbbe335d89c5801c7fd6218df07edc15b9c1b930dce':
               'lean 0.1.18 (collapse-blank,pointer,c-line-breaks) code daacaf045271700d'},
    '0.1.19': {'d2c6599d9ef95a2159d0c676f8fce30ac3b1a4b0d4073758267a568204a12adf':
               'lean 0.1.19 (collapse-blank,pointer,c-line-breaks) code d2c6599d9ef95a21'},
    '0.1.20': {'4af6b011082cf7b7b78ccc5c394a35d98ad8d9825884e9854e88eda254e10993':
               'lean 0.1.20 (collapse-blank,pointer,c-drop-lines) code 4af6b011082cf7b7'},
    '0.1.21': {'16e4e63b2f7bd627f86bf7c50bc62f3f87091c8850a8ae984e7e23f23807cb20':
               'lean 0.1.21 (collapse-blank,pointer,c-drop-lines) code 16e4e63b2f7bd627'},
    '0.1.22': {'469db99475b2e77cc1e2a7c2de2bd20299d4622e5fa534c7bf94d901e9859127':
               'lean 0.1.22 (collapse-blank,pointer,c-drop-lines) code 469db99475b2e77c'},
    '0.1.23': {'26ccce6201a30ee14a50006856be96ec05bf46b12f8e053a9a1b926e8578334c':
               'lean 0.1.23 (collapse-blank,pointer,c-drop-lines) code 26ccce6201a30ee1'},
    # ORDER 352: the Zig adapter and 346c's Perl rule, released under their own label
    '0.1.24': {'d426fe3fd950a527223d5e1972b5525f6f499002b0abbfb1bb2f782dcd99c57a':
               'lean 0.1.24 (collapse-blank,pointer,c-drop-lines) code d426fe3fd950a527'},
}

# Digests that built under a label without being its released renderer: never accepted.
NEVER_RELEASED = {
    '0906d5288e79c2b2231ada0152845d7f355c598a3e9884740f00d62e924a2081':
        ('0.1.23', "f127811 (ORDER 346c, the Perl rule) changed project.py and lang_perl.py under 0.1.23's label, "
                   'on build main from 07a8baa to dfc6ec6; 0.1.24 releases that code (ORDER 352)'),
}

LABEL = re.compile(r'lean (\S+) \(([^()]*)\)(?: code ([0-9a-f]{16}))?')


def problem(identity: str, sha256: str) -> str | None:
    """None when (identity, sha256), a renderer as an lc1 manifest records it, is a released pair of this table;
    otherwise why it is not."""
    m = LABEL.fullmatch(identity or '')
    if not m:
        return f'renderer identity {identity!r} is not of the form "lean VERSION (OPTIONS)[ code DIGEST]"'
    version = m.group(1)
    if sha256 in NEVER_RELEASED:
        label, why = NEVER_RELEASED[sha256]
        return f'{identity}: code {sha256[:16]} was never released under lean {label} ({why})'
    rows = RELEASED.get(version)
    if rows is None:
        return f'{identity}: lean {version} is not a released version in the toolkit\'s table (lean/identities.py)'
    if sha256 not in rows:
        known = ', '.join(d[:16] for d in rows)
        return f'{identity}: code {sha256[:16]} is not the renderer lean {version} released ({known})'
    if rows[sha256] != identity:
        return f'{identity}: lean {version} code {sha256[:16]} was released as {rows[sha256]!r}'
    return None


def toolkit_problem(version: str, identity: str, sha256: str) -> str | None:
    """None when this toolkit's own renderer is its VERSION's one row; otherwise what to do (the review step:
    a renderer change without a version bump, or a bump without its row)."""
    rows = RELEASED.get(version)
    if rows is None:
        return f'lean {version} has no row in lean/identities.py: add it with code {sha256}'
    if len(rows) != 1:
        return f'lean {version} has {len(rows)} renderers in lean/identities.py; a label carries one'
    if sha256 not in rows:
        return (f'the renderer files changed (code {sha256}) but lean {version} records code '
                f'{next(iter(rows))}: give the change the next VERSION and its row in lean/identities.py')
    if rows[sha256] != identity:
        return f'lean {version} records identity {rows[sha256]!r}, the toolkit gives {identity!r}'
    return None
