# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""Which handler reads a file: chosen by its real consumer (where it is installed, its name, its
interpreter line), never by a file name alone (LEAN-CODE-POLICY.md: "`.conf` is not a grammar").

Ported from CGM's resolver and extension registry (dispatch by extension, refusals by name), widened
from "extension -> handler" to "installed path -> consumer -> handler", because an operating system's
files keep their upstream names. Every file gets either a handler or the reason it has none.
"""

from __future__ import annotations

import fnmatch
import os
import re

from . import lang_c, lang_ini, lang_js, lang_json, lang_perl, lang_python, lang_shell, lang_toml, lang_yaml, lang_java, lang_kotlin

HANDLERS = {m.KEY: m for m in (lang_shell, lang_python, lang_js, lang_ini, lang_yaml, lang_toml, lang_json,
                               lang_c, lang_perl, lang_java, lang_kotlin)}

# never projected, with the reason recorded in the manifest
EXCLUDE = [
    ('/usr/share/doc/*', 'documentation (kept whole by the policy)'),
    ('/usr/share/man/*', 'manual pages (kept whole by the policy)'),
    ('/usr/share/info/*', 'info manuals (kept whole by the policy)'),
    ('/usr/share/locale/*', 'translations'),
    ('/usr/share/common-licenses/*', 'licence texts'),
    ('*/copyright', 'copyright file'),
    ('*/LICENSE*', 'licence text'), ('*/COPYING*', 'licence text'),
    ('/var/lib/dpkg/*', "dpkg's own database and maintainer scripts"),
    ('/var/lib/dpkg-stereo/*', "dpkg-stereo's own records"),
    ('/usr/lib/dpkg-stereo/*', 'dpkg-stereo itself: the tool that undoes projections stays as it was built'),
    ('/usr/sbin/dpkg-stereo', 'dpkg-stereo itself: the tool that undoes projections stays as it was built'),
    ('/var/cache/*', 'caches'), ('/var/log/*', 'logs'), ('/tmp/*', 'temporary files'),
    ('/boot/*', 'boot files'),
    ('/etc/sudoers*', 'sudoers: `#` starts directives (#include) and user IDs'),
    ('/usr/src/*/.config', 'kernel configuration: "# CONFIG_X is not set" lines are read by Kconfig'),
    ('*/Kconfig*', 'Kconfig language: no qualified adapter'),
    ('/etc/apt/*', 'APT configuration: its own grammar, no qualified adapter yet'),
    ('/usr/lib/python3*/test/*', 'Python test data (some read their own comments)'),
    ('/usr/lib/python3/dist-packages/*/tests/*', 'test suites read their own text'),
]

SYSTEMD_UNIT = ('.service', '.socket', '.timer', '.target', '.path', '.mount', '.automount', '.slice',
                '.swap', '.network', '.netdev', '.link', '.nspawn', '.scope')
SYSTEMD_DIRS = ('/usr/lib/systemd/', '/lib/systemd/', '/etc/systemd/', '/usr/lib/environment.d/')
SDLINE = [
    ('/usr/lib/tmpfiles.d/*.conf', 'sdline-hash'), ('/etc/tmpfiles.d/*.conf', 'sdline-hash'),
    ('/usr/lib/sysusers.d/*.conf', 'sdline-hash'), ('/etc/sysusers.d/*.conf', 'sdline-hash'),
    ('/usr/lib/sysctl.d/*.conf', 'sdline'), ('/etc/sysctl.d/*.conf', 'sdline'), ('/etc/sysctl.conf', 'sdline'),
    ('/usr/lib/modules-load.d/*.conf', 'sdline'), ('/etc/modules-load.d/*.conf', 'sdline'),
    ('/usr/lib/binfmt.d/*.conf', 'sdline'), ('/etc/binfmt.d/*.conf', 'sdline'),
    ('/usr/lib/environment.d/*.conf', 'sdline-hash'), ('/etc/environment.d/*.conf', 'sdline-hash'),
    ('/etc/modules', 'sdline-hash'),
]
KCONFIG = ['/etc/xdg/*rc', '/etc/xdg/kdeglobals', '/usr/share/config/*rc', '/usr/share/knotifications6/*.notifyrc',
           '/usr/share/knsrcfiles/*.knsrc', '/usr/share/color-schemes/*.colors', '/etc/xdg/autostart-scripts/*rc']
DESKTOP = ('.desktop', '.directory')


def shebang(data: bytes) -> str | None:
    if not data.startswith(b'#!'):
        return None
    line = data.split(b'\n', 1)[0].decode('utf-8', 'replace')
    parts = line[2:].strip().split()
    if not parts:
        return None
    prog = os.path.basename(parts[0])
    if prog == 'env':
        rest = [p for p in parts[1:] if not p.startswith('-')]
        prog = os.path.basename(rest[0]) if rest else 'env'
    return re.sub(r'[0-9.]+$', '', prog) or prog


def choose(path: str, data: bytes):
    """(handler key, variant or interpreter, None) or (None, None, reason)."""
    for pat, why in EXCLUDE:
        if fnmatch.fnmatchcase(path, pat):
            return None, None, why
    base = os.path.basename(path)
    ext = os.path.splitext(base)[1].lower()
    interp = shebang(data)
    if interp:
        if interp in ('sh', 'dash'):
            return 'shell', 'dash', None
        if interp == 'bash':
            return 'shell', 'bash', None
        if interp == 'python':
            return 'python', None, None
        if interp == 'perl':
            return 'perl', None, None
        if interp in ('node', 'nodejs'):
            return 'js', None, None
        return None, None, f'interpreter {interp}: no qualified adapter'
    if ext in ('.sh', '.bash'):
        return 'shell', 'bash' if ext == '.bash' else 'dash', None
    if path.startswith('/etc/profile.d/') or path in ('/etc/profile', '/etc/bash.bashrc', '/etc/skel/.bashrc',
                                                       '/etc/skel/.profile', '/etc/skel/.bash_logout'):
        return 'shell', 'bash', None
    if path.startswith('/usr/share/bash-completion/completions/') or path.startswith('/etc/bash_completion.d/') \
            or path == '/usr/share/bash-completion/bash_completion':
        return 'shell', 'bash', None
    if path.startswith('/etc/X11/Xsession.d/') or path.startswith('/etc/grub.d/'):
        return 'shell', 'bash' if path.startswith('/etc/grub.d/') else 'dash', None
    if ext == '.py':
        return 'python', None, None
    if ext == '.java':
        return 'java', None, None
    if ext in ('.kt', '.kts'):
        return 'kotlin', None, None
    if ext in ('.pl', '.pm'):
        return 'perl', None, None
    if ext in ('.qml', '.js', '.mjs'):
        return 'js', None, None
    if ext in ('.yaml', '.yml'):
        return 'yaml', None, None
    if ext == '.toml':
        return 'toml', None, None
    if ext == '.json':
        return None, None, 'JSON has no comment syntax (RFC 8259)'
    if ext in lang_c.EXTS:
        return 'c', 'c++' if ext in ('.hh', '.hpp', '.hxx', '.cc', '.cpp', '.cxx', '.inl') or '/c++/' in path \
            or '/qt6/' in path or '/KF6/' in path else 'c', None
    if ext in lang_c.GLSL_EXTS:
        return 'c', 'glsl', None
    if ext in SYSTEMD_UNIT or (ext == '.conf' and any(path.startswith(d) for d in SYSTEMD_DIRS)):
        return 'ini', 'systemd', None
    for pat, variant in SDLINE:
        if fnmatch.fnmatchcase(path, pat):
            return 'ini', variant, None
    if ext == '.rules' and ('/udev/' in path):
        return 'ini', 'udev', None
    if fnmatch.fnmatchcase(path, '/etc/modprobe.d/*.conf') or fnmatch.fnmatchcase(path, '/usr/lib/modprobe.d/*.conf'):
        return 'ini', 'modprobe', None
    if ext in DESKTOP:
        return 'ini', 'desktop', None
    if any(fnmatch.fnmatchcase(path, p) for p in KCONFIG):
        return 'ini', 'kconfig', None
    if path == '/etc/environment' or path == '/etc/default/locale':
        return 'ini', 'sdline-hash', None
    if path.startswith('/etc/default/'):
        return 'shell', 'env', None
    return None, None, 'no qualified adapter for this consumer'
