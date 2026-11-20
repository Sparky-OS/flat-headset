#!/usr/bin/env python3
"""Verify an unsigned IPA's metadata and emit the receiver's renewal-manifest entry."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('ipa', type=Path)
parser.add_argument('--commit', required=True)
args = parser.parse_args()
with zipfile.ZipFile(args.ipa) as archive:
    info = plistlib.loads(archive.read('Payload/FlatHeadset.app/Info.plist'))
    assert info['CFBundleDisplayName'] == 'Flat Headset', 'Unexpected product name'
    assert info['CFBundleIdentifier'] == 'org.sparkyos.flatheadset', 'Unexpected bundle identifier'
    assert info['MinimumOSVersion'] == '16.0', 'Unexpected deployment target'
    assert info['CFBundleExecutable'] == 'FlatHeadset', 'Unexpected executable'
    assert archive.read('Payload/FlatHeadset.app/FlatHeadset'), 'Missing executable'
    assert not any('/_CodeSignature/' in n or n.endswith('embedded.mobileprovision') for n in archive.namelist()), 'IPA must be unsigned'
with args.ipa.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
entry = {'role': 'flat-headset', 'path': args.ipa.name, 'sha256': digest, 'bundle_id': info['CFBundleIdentifier']}
args.ipa.with_name('flat-headset-artifact.json').write_text(json.dumps({'commit': args.commit, 'apps': [entry]}, indent=2) + '\n')
args.ipa.with_name('SHA256SUMS').write_text(f'{digest}  {args.ipa.name}\n')
print('PASS: unsigned Flat Headset IPA metadata; receiver manifest entry written')
