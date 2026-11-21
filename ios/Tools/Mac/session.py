#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the pinned iPhone sources on a Mac; optionally sign and install over USB.

No credentials are accepted. Add the account in Xcode itself before a signed run.
Logs and provisioning results stay in the private session directory on the Mac.
"""
import argparse
from datetime import datetime, timezone
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

LOCK = Path(__file__).with_name('sources.json')

class Stop(Exception):
    pass


def run(argv, *, cwd=None, log=None, quiet=False, failure=None):
    if failure is None:
        failure = f'{Path(str(argv[0])).name} failed; ' + (f'inspect {Path(log).name} and retry.' if log else 'check this tool and the private session directory, then retry.')
    try:
        result = subprocess.run([str(x) for x in argv], cwd=cwd, stdin=subprocess.DEVNULL,
                                stdout=subprocess.DEVNULL if quiet else subprocess.PIPE,
                                stderr=subprocess.DEVNULL if quiet else subprocess.PIPE, timeout=3600)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise Stop(failure) from error
    if log:
        Path(log).write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise Stop(failure)
    return (result.stdout or b'').decode('utf-8', errors='replace')


def version(text):
    match = re.search(r'\d+(?:\.\d+)*', text)
    if not match:
        raise Stop('The version response was unreadable; check the selected Xcode and macOS.')
    return tuple(int(x) for x in match[0].split('.'))


def check_versions(system, mac, xcode):
    if system != 'Darwin':
        raise Stop('This session needs macOS; run it on the Mac, not the Linux station.')
    m, x = version(mac), version(xcode)
    if x[0] >= 27:
        raise Stop('This Xcode is too new for the iOS 16 phone; select Xcode 26.3.')
    if x != (26, 3):
        raise Stop('This recipe is verified with Xcode 26.3; select that version before continuing.')
    if not ((m[0] == 15 and m >= (15, 6)) or m[0] == 26):
        raise Stop('This macOS cannot run the verified Xcode 26.3; update to Sequoia 15.6 or Tahoe 26.')


def host_check():
    system = run(['uname', '-s']).strip()
    if system != 'Darwin':
        check_versions(system, '0', '0')
    mac = run(['sw_vers', '-productVersion'])
    xcode = run(['xcodebuild', '-version'], failure='Xcode is unavailable; install Xcode 26.3, open it once and select its Command Line Tools in Settings > Locations.')
    check_versions(system, mac, xcode.splitlines()[0])
    selected = run(['xcode-select', '-p']).strip()
    metal = run(['xcrun', '--sdk', 'iphoneos', 'metal', '--version'], failure='The Metal Toolchain is missing; in Xcode Settings > Components install Metal Toolchain, or run xcodebuild -downloadComponent MetalToolchain on this Mac, then retry.')
    print('macOS:', mac.strip(), '|', xcode.replace('\n', ' ').strip())
    print('Host processor:', run(['uname', '-m']).strip())
    print('xcode-select:', selected)
    print('Effective DEVELOPER_DIR:', os.environ.get('DEVELOPER_DIR', selected))
    print('Metal:', metal.splitlines()[0])
    print(run(['df', '-h', '.']).strip())
    for name in ('git', 'curl', 'cmake', 'ninja', 'pkg-config', 'perl', 'make'):
        if not shutil.which(name):
            raise Stop(f'{name} is missing; install the build prerequisites listed on the session page, then retry.')
    if sys.version_info < (3, 12):
        raise Stop('Python 3.12 or newer is required; select that Python on this Mac and retry.')
    print('PASS: Mac, Xcode 26.3, Metal and build tools are ready.')


def fetch_repository(root, name, revision):
    if not re.fullmatch('[a-f0-9]{40}', revision):
        raise Stop('A source pin is invalid; restore the reviewed sources.json.')
    destination = root / name
    url = 'https://github.com/Sparky-OS/' + name + '.git'
    if not destination.exists():
        run(['git', 'init', destination])
        run(['git', '-C', destination, 'remote', 'add', 'origin', url])
    if run(['git', '-C', destination, 'remote', 'get-url', 'origin']).strip() != url:
        raise Stop(f'{name} has a different source remote; use a new empty session directory.')
    if run(['git', '-C', destination, 'status', '--porcelain']).strip():
        raise Stop(f'{name} has local changes; keep that work and use a new session directory.')
    run(['git', '-C', destination, 'fetch', '--depth=1', 'origin', revision],
        failure=f'Could not fetch the pinned {name} commit; check the connection and that the reviewed commit has been pushed.')
    run(['git', '-C', destination, 'checkout', '--detach', revision])
    if run(['git', '-C', destination, 'rev-parse', 'HEAD']).strip() != revision:
        raise Stop(f'{name} is not at the reviewed commit; stop and restore the source pin.')
    print(f'PASS: {name} at {revision}')
    return destination


def build_installer(lock, work):
    """Only source is fetched. Apple's MobileDevice framework is linked on the Mac."""
    item = lock['ios-deploy']
    archive = work / 'ios-deploy.tar.gz'
    run(['curl', '--fail', '--location', '--retry', '3', '--proto', '=https', item['url'], '--output', archive],
        failure='Could not download the pinned USB installer source; check the connection and retry.')
    if hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() != item['sha256']:
        raise Stop('The USB installer source hash differs; stop and have the source pin reviewed.')
    with tarfile.open(archive) as package:
        package.extractall(work / 'installer-source', filter='data')
    source = work / 'installer-source' / ('ios-deploy-' + item['commit'])
    # The upstream Xcode target copies MobileDevice.framework into its source tree.
    # Compile its one source directly and link the installed framework in place.
    frameworks = [Path('/Library/Apple/System/Library/PrivateFrameworks'),
                  Path('/System/Library/PrivateFrameworks')]
    framework = next((p for p in frameworks if (p / 'MobileDevice.framework').is_dir()), None)
    if framework is None:
        raise Stop('Apple MobileDevice.framework is missing; finish the Xcode/iOS components installation on the Mac.')
    (source / 'src/ios-deploy/lldb.py.h').write_text(json.dumps((source / 'src/scripts/lldb.py').read_text()) + '\n')
    binary = work / 'ios-deploy'
    run(['xcrun', '--sdk', 'macosx', 'clang', '-std=gnu99', '-fno-objc-arc', '-fblocks', '-O2',
         '-DNDEBUG=1', '-DIOS_DEPLOY_FEATURE_DEVELOPER_MODE=1',
         '-isysroot', run(['xcrun', '--sdk', 'macosx', '--show-sdk-path']).strip(),
         '-mmacosx-version-min=15.6', '-F' + str(framework), '-Wl,-rpath,' + str(framework),
         '-framework', 'Foundation', '-framework', 'CoreFoundation', '-framework', 'MobileDevice',
         source / 'src/ios-deploy/ios-deploy.m', '-o', binary],
        log=work / 'installer-build.log', failure='The USB installer did not build; inspect installer-build.log before connecting a phone.')
    run([binary, '--version'], failure='The built USB installer could not run; inspect installer-build.log.')
    if b'--check-developer-mode' not in binary.read_bytes():
        raise Stop('The USB installer lacks its Developer Mode check; inspect its build definition before continuing.')
    return binary


def json_events(text):
    decoder = json.JSONDecoder()
    values = []
    text = text.strip()
    while text:
        try:
            value, end = decoder.raw_decode(text)
        except ValueError as error:
            raise Stop('The USB installer returned unreadable device data; check its version before retrying.') from error
        values.append(value)
        text = text[end:].lstrip()
    return values


def select_phone(events):
    devices = {e['Device']['DeviceIdentifier']: e['Device'] for e in events
               if e.get('Event') == 'DeviceDetected' and e.get('Interface') == 'USB'
               and e.get('Device', {}).get('DeviceIdentifier')}
    if not devices:
        raise Stop('No USB phone was found; connect and unlock the intended phone and accept Trust on its screen.')
    if len(devices) != 1:
        raise Stop('More than one USB device is connected; leave only the intended phone connected and retry.')
    uid, device = next(iter(devices.items()))
    if not device.get('ProductVersion') or not device.get('DeviceClass'):
        raise Stop('The phone information is unavailable; unlock it, accept Trust and check it in Xcode Devices and Simulators.')
    if device['DeviceClass'] != 'iPhone' or version(device['ProductVersion'])[0] != 16:
        raise Stop('This session is prepared for an iPhone on iOS 16; have the other device route reviewed before installing.')
    return uid


def check_phone(installer):
    events = json_events(run([installer, '--detect', '--no-wifi', '--json', '--timeout', '5'],
        failure='USB discovery failed; connect and unlock the phone, accept Trust and retry.'))
    uid = select_phone(events)
    available = json.loads(run(['xcrun', 'xcdevice', 'list', '--timeout', '10'],
        failure='Xcode could not inspect the phone; open Devices and Simulators on the Mac and settle its reported error.'))
    matches = [d for d in available if d.get('identifier') == uid and not d.get('simulator')]
    if len(matches) != 1 or not matches[0].get('available') or matches[0].get('error'):
        raise Stop('The phone is unavailable or not trusted in Xcode; unlock it, accept Trust and finish preparation in Devices and Simulators.')
    events = json_events(run([installer, '--id', uid, '--no-wifi', '--json', '--timeout', '10', '--check-developer-mode'],
        failure='The phone could not authorize development; accept Trust, enable Developer Mode and check it in Xcode.'))
    states = [e for e in events if e.get('Event') == 'DeveloperMode']
    if len(states) != 1 or states[0].get('Code', 0) or states[0].get('IsEnabled') is not True:
        raise Stop('The phone is not ready for development; accept Trust and enable Developer Mode on its screen, then retry.')
    print('PASS: one USB iPhone on iOS 16 reports Developer Mode enabled.')
    return uid


def signing_flags(team, uid, entitlements):
    if not re.fullmatch('[A-Z0-9]{10}', team):
        raise Stop('The Personal Team ID is missing or invalid; copy its ten-character ID from Xcode, not an account name.')
    return ['-destination', f'platform=iOS,id={uid}', '-allowProvisioningUpdates',
            '-allowProvisioningDeviceRegistration', 'CODE_SIGN_STYLE=Automatic',
            'CODE_SIGNING_ALLOWED=YES', 'CODE_SIGNING_REQUIRED=YES',
            'CODE_SIGN_IDENTITY=Apple Development', f'DEVELOPMENT_TEAM={team}',
            f'CODE_SIGN_ENTITLEMENTS={entitlements}']


def verify_profile(profile, entitlements, bundle, team, uid, now):
    if team not in profile.get('TeamIdentifier', []) or uid not in profile.get('ProvisionedDevices', []):
        raise Stop('The signing profile does not authorize this team and phone; repair automatic signing in Xcode.')
    expiry = profile.get('ExpirationDate')
    if not expiry or expiry.replace(tzinfo=timezone.utc) <= now:
        raise Stop('The signing profile has expired; let Xcode renew it before installing.')
    prefixes = profile.get('ApplicationIdentifierPrefix', [])
    if len(prefixes) != 1:
        raise Stop('The profile has no unique application prefix; inspect automatic signing in Xcode.')
    prefix = prefixes[0] + '.'
    allowed = profile.get('Entitlements', {})
    if entitlements.get('application-identifier') != prefix + bundle or not fnmatch.fnmatchcase(prefix + bundle, allowed.get('application-identifier', '')):
        raise Stop('The signed application identifier does not match its profile; inspect the Xcode signing settings.')
    groups = entitlements.get('keychain-access-groups', [])
    needed = [prefix + bundle]
    if bundle.endswith('.flatheadset'):
        needed.append(prefix + 'org.sparkyos.kdeconnect')
    if bundle.endswith('.cardboard'):
        needed = groups  # Cardboard needs no shared keychain entitlement.
    if groups != needed or any(not any(fnmatch.fnmatchcase(g, p) for p in allowed.get('keychain-access-groups', [])) for g in groups):
        raise Stop('This profile does not permit the shared KDE Connect pairing groups; stop before installing and have the team capabilities reviewed.')
    return prefix


def verify_signed(app, bundle, team, uid):
    profile = plistlib.loads(run(['security', 'cms', '-D', '-i', app / 'embedded.mobileprovision']).encode())
    entitlements = plistlib.loads(run(['codesign', '-d', '--entitlements', ':-', app]).encode())
    run(['codesign', '--verify', '--strict', app], failure='The signed app failed signature verification; inspect its signing log before installing.')
    prefix = verify_profile(profile, entitlements, bundle, team, uid, datetime.now(timezone.utc))
    print(f'PASS: {bundle} profile expires {profile["ExpirationDate"].isoformat()} UTC.')
    return prefix


def build_apps(repos, work, team=None, uid=None):
    flat, kde, card = (repos[n] for n in ('flat-headset', 'kdeconnect-ios', 'cardboard'))
    core = work / 'core'
    run([sys.executable, flat / 'ios/Tools/fetch_core_sources.py', work / 'core-sources'], log=work / 'core-fetch.log')
    run(['bash', flat / 'ios/Tools/build_core.sh', 'ios', work / 'core-sources', core], log=work / 'core-build.log',
        failure='The iPhone core build failed; inspect core-build.log and keep the selected Xcode 26.3.')
    run([sys.executable, kde / 'Sparky/fetch_openssl.py', work / 'kde-sources'])
    run(['bash', kde / 'Sparky/build_openssl.sh', 'ios', work / 'kde-sources/openssl', work / 'kde-openssl'], log=work / 'kde-openssl.log')
    apps = [
        ('KDE Connect', kde / 'KDE Connect.xcodeproj', 'KDE Connect', 'kdeconnect',
         kde / 'Sparky/Sideload.entitlements', [f'KDE_OPENSSL_PREFIX={work / "kde-openssl/install"}',
         '-clonedSourcePackagesDirPath', str(work / 'packages'), '-onlyUsePackageVersionsFromResolvedFile']),
        ('Cardboard', card / 'profile-ios/CardboardProfile.xcodeproj', 'CardboardProfile', 'cardboard', '', []),
        ('Flat Headset', flat / 'ios/FlatHeadset.xcodeproj', 'FlatHeadset', 'flatheadset',
         flat / 'ios/FlatHeadset.entitlements', [f'FH_CORE_PREFIX={core / "install"}']),
    ]
    built = []
    for title, project, scheme, suffix, entitlements, extras in apps:
        folder = work / suffix
        flags = signing_flags(team, uid, entitlements) if team else [
            '-destination', 'generic/platform=iOS', 'CODE_SIGNING_ALLOWED=NO',
            'CODE_SIGNING_REQUIRED=NO', 'CODE_SIGN_IDENTITY=', 'DEVELOPMENT_TEAM=']
        run(['xcodebuild', '-project', project, '-scheme', scheme, '-configuration', 'Release',
             '-sdk', 'iphoneos', '-derivedDataPath', folder, '-jobs', '4',
             f'PRODUCT_BUNDLE_IDENTIFIER=org.sparkyos.{suffix}', 'IPHONEOS_DEPLOYMENT_TARGET=16.0',
             *flags, *extras, 'build'], log=None if team else work / f'{suffix}-build.log', quiet=bool(team),
            failure=f'{title} did not build. ' + ('Check automatic signing in Xcode and let the person unlock/authorize the login keychain at the Mac; then retry. Signing output is not recorded.' if team else f'Inspect {suffix}-build.log and retry.'))
        app = folder / 'Build/Products/Release-iphoneos' / (scheme + '.app')
        if not app.is_dir():
            raise Stop(f'{title} app was not produced; inspect {suffix}-build.log.')
        if any(p.suffix in ('.dylib', '.framework', '.appex') for p in app.rglob('*')):
            raise Stop(f'{title} contains unexpected embedded code; inspect the build before packaging or installing.')
        plist = plistlib.loads((app / 'Info.plist').read_bytes())
        if plist['CFBundleIdentifier'] != 'org.sparkyos.' + suffix:
            raise Stop(f'{title} has an unexpected bundle identifier; stop before installing.')
        run(['xcrun', 'lipo', app / plist['CFBundleExecutable'], '-verify_arch', 'arm64'])
        built.append((title, suffix, app))
        print(f'PASS: {title} built for arm64 iPhone.')
    return built


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--unsigned', action='store_true', help='build only, no account, signing or device access')
    parser.add_argument('--team', help='non-secret ten-character Personal Team ID from Xcode')
    parser.add_argument('--check-host', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    host_check()
    if args.check_host:
        return
    if not args.unsigned and not args.team:
        raise Stop('No Personal Team ID was supplied; add the account in Xcode yourself, then pass only its team ID with --team.')
    if args.unsigned and args.team:
        raise Stop('An unsigned proof must not select a team; remove --team.')
    if args.team:
        signing_flags(args.team, 'validation-only', '')
    root = args.work.expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(root).free < 10 * 1024**3:
        raise Stop('Less than 10 GiB is free for this build; free space on the Mac before retrying.')
    lock = json.loads(LOCK.read_text())
    repos = {name: fetch_repository(root / 'sources', name, pin) for name, pin in lock['repositories'].items()}
    work = Path(tempfile.mkdtemp(prefix='session-', dir=root))
    print('Private build logs:', work)
    installer = build_installer(lock, work)
    uid = None if args.unsigned else check_phone(installer)
    built = build_apps(repos, work, args.team, uid)
    if args.unsigned:
        for title, suffix, app in built:
            output = run(['xcrun', 'otool', '-l', app / plistlib.loads((app / 'Info.plist').read_bytes())['CFBundleExecutable']])
            if 'LC_CODE_SIGNATURE' in output or (app / 'embedded.mobileprovision').exists():
                raise Stop(f'{title} unexpectedly contains signing material; inspect the unsigned build settings.')
            payload = work / f'{suffix}-ipa/Payload'
            payload.mkdir(parents=True)
            shutil.copytree(app, payload / app.name)
            ipa = work / f'{suffix}-unsigned.ipa'
            run(['ditto', '-c', '-k', '--sequesterRsrc', '--keepParent', payload, ipa])
            digest = hashlib.file_digest(ipa.open('rb'), 'sha256').hexdigest()
            ipa.with_suffix('.sha256').write_text(f'{digest}  {ipa.name}\n')
            print(f'{title}: unsigned build verified; nothing installed.')
        return
    # Validate all three before the first install, including the receiver's shared group.
    prefixes = [verify_signed(app, 'org.sparkyos.' + suffix, args.team, uid) for _, suffix, app in built]
    if len(set(prefixes)) != 1:
        raise Stop('The three profiles use different application prefixes; stop and review the signing team.')
    for title, suffix, app in built:
        run([installer, '--id', uid, '--no-wifi', '--timeout', '30', '--bundle', app],
            log=work / f'{suffix}-install.log', failure=f'{title} was not installed; inspect {suffix}-install.log and check USB trust. Keep the apps installed and retry; do not uninstall them.')
        print(f'{title}: installed; open it on the phone to check its first screen.')


if __name__ == '__main__':
    try:
        main()
    except Stop as error:
        print('STOP:', error, file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, tarfile.TarError):
        print('STOP: A session file or tool response could not be read or written; check directory permissions, the reviewed source manifest and the private build logs before retrying.', file=sys.stderr)
        sys.exit(1)
