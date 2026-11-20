# SPDX-License-Identifier: GPL-3.0-or-later
"""Negative controls for native localization and artifact metadata gates. No phone or signing."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('catalogs', ROOT / 'Tools/check_localizations.py')
catalogs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalogs)
with tempfile.TemporaryDirectory() as scratch:
    root = Path(scratch)
    shutil.copytree(ROOT / 'Resources', root / 'Resources')
    shutil.copytree(ROOT / 'Sources', root / 'Sources')
    with contextlib.redirect_stdout(io.StringIO()):
        catalogs.check(root)
    missing = root / 'Resources/es.lproj/Localizable.strings'
    missing.write_text(missing.read_text().replace('"renderer_failed" = "Flat Headset no pudo crear la imagen.";\n', ''))
    try:
        catalogs.check(root)
    except ValueError as error:
        assert 'renderer_failed' in str(error)
    else:
        raise AssertionError('Missing Spanish key was accepted')
    print('PASS: removing one Spanish key fails the localization gate')
    info = {'CFBundleDisplayName': 'Flat Headset', 'CFBundleIdentifier': 'org.sparkyos.flatheadset',
            'CFBundleExecutable': 'FlatHeadset', 'MinimumOSVersion': '16.0'}
    ipa = root / 'FlatHeadset-unsigned.ipa'
    def package(signed=False):
        with zipfile.ZipFile(ipa, 'w') as archive:
            archive.writestr('Payload/FlatHeadset.app/Info.plist', plistlib.dumps(info))
            archive.writestr('Payload/FlatHeadset.app/FlatHeadset', b'synthetic metadata fixture, not an executable')
            if signed:
                archive.writestr('Payload/FlatHeadset.app/embedded.mobileprovision', b'synthetic rejected fixture')
    def manifest():
        return subprocess.run([sys.executable, str(ROOT / 'Tools/package_manifest.py'), str(ipa), '--commit', 'test'], capture_output=True, text=True)
    package()
    assert manifest().returncode == 0
    entry = json.loads((root / 'flat-headset-artifact.json').read_text())['apps'][0]
    assert entry['sha256'] == hashlib.sha256(ipa.read_bytes()).hexdigest()
    assert entry['path'] == ipa.name and entry['role'] == 'flat-headset'
    print('PASS: artifact manifest contains the exact IPA hash and renewal role')
    package(signed=True)
    assert manifest().returncode != 0
    print('PASS: provisioned input is rejected by unsigned-artifact gate')
    info['CFBundleIdentifier'] = 'unexpected.app'
    package()
    assert manifest().returncode != 0
    print('PASS: unexpected bundle ID is rejected')
