#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Account-free analogue of the Mac session's decision branches and command boundary."""
import copy
from datetime import datetime, timedelta, timezone
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from contextlib import ExitStack

spec = importlib.util.spec_from_file_location('session', Path(__file__).resolve().parents[1] / 'Tools/Mac/session.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class SessionTests(unittest.TestCase):
    def test_platform_versions(self):
        for mac in ('15.6', '15.7.2', '26.0', '26.2'):
            m.check_versions('Darwin', mac, 'Xcode 26.3')
        for values, message in [
            (('Linux','15.6','26.3'), 'macOS'),
            (('Darwin','14.7','26.3'), 'update to Sequoia'),
            (('Darwin','15.5','26.3'), 'update to Sequoia'),
            (('Darwin','26.6','27.0'), 'too new'),
            (('Darwin','26.2','26.6'), 'verified with Xcode 26.3')]:
            with self.subTest(values=values), self.assertRaisesRegex(m.Stop, message):
                m.check_versions(*values)

    def test_missing_xcode_and_metal(self):
        for missing, sentence in [('xcodebuild','Xcode is unavailable'), ('metal','Metal Toolchain is missing')]:
            def execute(argv, **kwargs):
                cmd = ' '.join(argv)
                if missing in cmd: raise FileNotFoundError(missing)
                data = b'Darwin\n' if argv[0]=='uname' else b'15.7\n' if argv[0]=='sw_vers' else b'Xcode 26.3\n' if argv[0]=='xcodebuild' else b'/Applications/Xcode.app/Contents/Developer\n'
                return subprocess.CompletedProcess(argv, 0, data, b'')
            with self.subTest(missing=missing), patch.object(m.subprocess,'run',side_effect=execute), self.assertRaisesRegex(m.Stop,sentence):
                m.host_check()

    def event(self, uid='fixture-device'):
        return {'Event':'DeviceDetected','Interface':'USB','Device':{'DeviceIdentifier':uid,'ProductVersion':'16.7.12','DeviceClass':'iPhone'}}

    def test_device_selection(self):
        self.assertEqual(m.select_phone([self.event()]), 'fixture-device')
        for events, message in [([], 'No USB phone'), ([dict(self.event(),Interface='WIFI')], 'No USB phone'),
                                ([self.event(),self.event('second')], 'More than one')]:
            with self.assertRaisesRegex(m.Stop,message): m.select_phone(events)
        for field,value in [('ProductVersion','17.0'),('DeviceClass','iPad'),('ProductVersion','')]:
            event=self.event();event['Device'][field]=value
            with self.assertRaises(m.Stop): m.select_phone([event])

    def test_untrusted_phone_and_developer_mode(self):
        for available, mode, okay in [(False,True,False),(True,False,False),(True,True,True)]:
            answers=[json.dumps(self.event()),json.dumps([{'identifier':'fixture-device','available':available}]),json.dumps({'Event':'DeveloperMode','IsEnabled':mode})]
            with patch.object(m,'run',side_effect=answers):
                if okay: self.assertEqual(m.check_phone('fixture-installer'),'fixture-device')
                else:
                    with self.assertRaisesRegex(m.Stop,'Trust|trusted'): m.check_phone('fixture-installer')

    def test_json_stream(self):
        events=[self.event(), {'Event':'DeviceDisconnected'}]
        self.assertEqual(m.json_events('\n'.join(json.dumps(e,indent=2) for e in events)),events)
        with self.assertRaisesRegex(m.Stop,'unreadable'): m.json_events('unexpected text')

    def test_profile_authorization_and_group_order(self):
        now=datetime.now(timezone.utc)
        profile={'TeamIdentifier':['TESTTEAM01'],'ProvisionedDevices':['fixture-device'],
                 'ApplicationIdentifierPrefix':['PREFIX0001'],'ExpirationDate':now+timedelta(days=7),
                 'Entitlements':{'application-identifier':'PREFIX0001.*','keychain-access-groups':['PREFIX0001.*']}}
        ent={'application-identifier':'PREFIX0001.org.sparkyos.flatheadset',
             'keychain-access-groups':['PREFIX0001.org.sparkyos.flatheadset','PREFIX0001.org.sparkyos.kdeconnect']}
        self.assertEqual(m.verify_profile(profile,ent,'org.sparkyos.flatheadset','TESTTEAM01','fixture-device',now),'PREFIX0001.')
        mutations=[('TeamIdentifier',['OTHERTEAM1']),('ProvisionedDevices',[]),('ExpirationDate',now-timedelta(seconds=1)),
                   ('Entitlements',{'application-identifier':'WRONG.*','keychain-access-groups':['PREFIX0001.*']}),
                   ('Entitlements',{'application-identifier':'PREFIX0001.*','keychain-access-groups':['PREFIX0001.org.sparkyos.flatheadset']})]
        for key,value in mutations:
            bad=copy.deepcopy(profile);bad[key]=value
            with self.subTest(key=key), self.assertRaises(m.Stop): m.verify_profile(bad,ent,'org.sparkyos.flatheadset','TESTTEAM01','fixture-device',now)
        for groups in [list(reversed(ent['keychain-access-groups'])),ent['keychain-access-groups'][:1]]:
            with self.assertRaisesRegex(m.Stop,'shared KDE Connect'): m.verify_profile(profile,dict(ent,**{'keychain-access-groups':groups}),'org.sparkyos.flatheadset','TESTTEAM01','fixture-device',now)

    def test_signing_has_no_account_or_password_argument(self):
        flags=m.signing_flags('TESTTEAM01','fixture-device','fixture.entitlements')
        self.assertIn('DEVELOPMENT_TEAM=TESTTEAM01',flags)
        self.assertIn('-allowProvisioningUpdates',flags)
        self.assertFalse(any('authenticationKey' in x or 'password' in x for x in flags))
        with self.assertRaisesRegex(m.Stop,'Team ID'): m.signing_flags('invalid','fixture','fixture')

    def test_command_errors_and_no_stdin(self):
        def failure(argv, **kwargs):
            self.assertEqual(kwargs['stdin'],subprocess.DEVNULL)
            return subprocess.CompletedProcess(argv,1,b'',b'fixture failure')
        with patch.object(m.subprocess,'run',side_effect=failure), self.assertRaisesRegex(m.Stop,'plain sentence'):
            m.run(['false'],failure='plain sentence')

    def test_repository_pin_and_dirty_tree(self):
        sha='a'*40
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'flat-headset').mkdir()
            with patch.object(m,'run',side_effect=['https://github.com/Sparky-OS/flat-headset.git\n',' M tracked\n']), self.assertRaisesRegex(m.Stop,'local changes'):
                m.fetch_repository(root,'flat-headset',sha)
            with patch.object(m,'run',side_effect=['https://github.com/Sparky-OS/flat-headset.git\n','','','','b'*40]), self.assertRaisesRegex(m.Stop,'reviewed commit'):
                m.fetch_repository(root,'flat-headset',sha)
            with patch.object(m,'run',side_effect=['https://github.com/Sparky-OS/flat-headset.git\n','','','',sha]) as commands:
                m.fetch_repository(root,'flat-headset',sha)
                self.assertIn(['git','-C',root/'flat-headset','fetch','--depth=1','origin',sha],[c.args[0] for c in commands.call_args_list])

    def test_all_profiles_checked_before_any_install(self):
        apps=[('KDE Connect','kdeconnect',Path('/fixture/kde.app')),('Cardboard','cardboard',Path('/fixture/card.app')),('Flat Headset','flatheadset',Path('/fixture/flat.app'))]
        for failed, prefixes in [(True, None),(False,['P1.','P1.','P2.']),(False,['P1.']*3)]:
            with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
                stack.enter_context(patch.object(m.sys,'argv',['session.py','--work',tmp,'--team','TESTTEAM01']))
                stack.enter_context(patch.object(m,'host_check'))
                stack.enter_context(patch.object(m,'fetch_repository',return_value=Path('/fixture/source')))
                stack.enter_context(patch.object(m,'build_installer',return_value=Path('/fixture/installer')))
                stack.enter_context(patch.object(m,'check_phone',return_value='fixture-device'))
                stack.enter_context(patch.object(m,'build_apps',return_value=apps))
                verification=stack.enter_context(patch.object(m,'verify_signed',side_effect=m.Stop('refused profile') if failed else prefixes))
                commands=stack.enter_context(patch.object(m,'run',return_value=''))
                if failed or len(set(prefixes)) != 1:
                    with self.assertRaises(m.Stop): m.main()
                    commands.assert_not_called()
                else:
                    m.main()
                    self.assertEqual(verification.call_count,3)
                    self.assertEqual(commands.call_count,3)
                    for call, app in zip(commands.call_args_list,apps):
                        self.assertEqual(call.args[0][-2:],['--bundle',app[2]])
                        self.assertIn('--no-wifi',call.args[0])
                        self.assertNotIn('--uninstall',call.args[0])

    def test_unsigned_path_never_accesses_phone_or_signing(self):
        import plistlib
        with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
            root=Path(tmp);app=root/'fixture.app';app.mkdir()
            (app/'Info.plist').write_bytes(plistlib.dumps({'CFBundleExecutable':'fixture'}))
            (app/'fixture').write_bytes(b'authored fixture')
            stack.enter_context(patch.object(m.sys,'argv',['session.py','--work',tmp,'--unsigned']))
            stack.enter_context(patch.object(m,'host_check'))
            stack.enter_context(patch.object(m,'fetch_repository',return_value=root))
            stack.enter_context(patch.object(m,'build_installer',return_value=root/'installer'))
            phone=stack.enter_context(patch.object(m,'check_phone'))
            signing=stack.enter_context(patch.object(m,'verify_signed'))
            stack.enter_context(patch.object(m,'build_apps',return_value=[('Fixture','fixture',app)]))
            def execute(argv, **kwargs):
                if argv[0]=='ditto': Path(argv[-1]).write_bytes(b'authored IPA fixture')
                else: self.assertEqual(argv[:3],['xcrun','otool','-l'])
                return ''
            stack.enter_context(patch.object(m,'run',side_effect=execute))
            m.main()
            phone.assert_not_called();signing.assert_not_called()

if __name__ == '__main__':
    print('Authored fixtures only: installation messages below are mocked; no Mac, account or phone is used.', flush=True)
    unittest.main(verbosity=2)
