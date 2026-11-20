#!/usr/bin/env python3
"""Check every native catalog key and product name; print the per-key coverage table."""
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = ('en', 'pl', 'pt-BR', 'fr', 'de', 'es', 'ja', 'ko', 'zh-Hans', 'zh-Hant')
KEYS = {'Localizable.strings': {'metal_unavailable', 'renderer_failed', 'render_failed'},
        'InfoPlist.strings': {'CFBundleDisplayName', 'CFBundleName'}}
PAIR = re.compile(r'\s*("(?:[^"\\]|\\.)*")\s*=\s*("(?:[^"\\]|\\.)*")\s*;')


def read_catalog(path):
    text = path.read_text(encoding='utf-8')
    result = {}
    pos = 0
    while text[pos:].strip():
        match = PAIR.match(text, pos)
        if not match:
            raise ValueError(f'{path}: invalid .strings syntax at offset {pos}')
        key, value = (json.loads(token) for token in match.groups())
        if key in result or not value.strip():
            raise ValueError(f'{path}: duplicate key or empty value: {key}')
        result[key] = value
        pos = match.end()
    return result


def check(root=ROOT):
    for filename, required in KEYS.items():
        for language in LANGUAGES:
            path = root / 'Resources' / f'{language}.lproj' / filename
            values = read_catalog(path)
            if values.keys() != required:
                raise ValueError(f'{language}/{filename}: missing {required - values.keys()}, extra {values.keys() - required}')
            if filename == 'InfoPlist.strings' and set(values.values()) != {'Flat Headset'}:
                raise ValueError(f'{language}: product name must remain Flat Headset')
    source = (root / 'Sources/Main.mm').read_text()
    used = set(re.findall(r'(?:failWithKey:|NSLocalizedString\()@"([a-z_]+)"', source))
    if used != KEYS['Localizable.strings']:
        raise ValueError(f'Native source/catalog key mismatch: {used}')
    print('| Key | ' + ' | '.join(LANGUAGES) + ' |')
    print('| --- | ' + ' | '.join('---' for _ in LANGUAGES) + ' |')
    for filename, keys in KEYS.items():
        for key in sorted(keys):
            print('| ' + filename + ': ' + key + ' | ' + ' | '.join('yes' for _ in LANGUAGES) + ' |')
    print('PASS: all 5 keys in all 10 native catalogs')


if __name__ == '__main__':
    try:
        check()
    except (OSError, ValueError) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        sys.exit(1)
