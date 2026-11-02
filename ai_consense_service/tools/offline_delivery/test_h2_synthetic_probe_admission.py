"""Fake-engine tests only; never contacts Docker or starts Java."""
from contextlib import contextmanager
import copy
import hashlib
import os
from pathlib import Path
import tempfile
from unittest import mock
import unittest

import h2_synthetic_probe_admission as compiler
import container_admission as admission
import jre_probe_admission as jre


HEADER = ['synthetic fixture only; no inference about real database credentials',
          'H2 DDL may commit implicitly; commit does not establish transactional rollback']
VERSION = 'java.version=17.0.20.1 h2.database=2.1.214 (2022-06-13) h2.driver=2.1.214 (2022-06-13)'
GOOD_OUTPUT = '\n'.join(HEADER + [VERSION, 'phase1 preparing password statement',
    'phase1 password statement prepared', 'phase1 executing password statement',
    'phase1 password statement executed; DDL may have committed implicitly',
    'phase1 commit returned', 'phase1 complete'] + HEADER + [
    'phase2 old password rejected sqlstate=28000 errorcode=28000',
    'phase2 wrong password rejected sqlstate=28000 errorcode=28000', VERSION,
    'phase2 new synthetic credential and marker verified', 'phase2 complete']) + '\n'


class H2SyntheticTest(unittest.TestCase):
    def setUp(self):
        self.cid = 'b' * 64
        self.daemon = {'id': 'fixture', 'socketDevice': 1, 'socketInode': 2,
                       'pid': 3, 'startTicks': 4, 'uid': 0, 'gid': 0}
        self.profile = {'name': 'consense-h2-synthetic-probe-local01', 'config': {
            'Image': compiler.IMAGE, 'User': 'consense:consense', 'Entrypoint': ['/bin/sh'],
            'Cmd': list(compiler.COMMAND), 'Env': list(jre.ENV),
            'Labels': {'consense.role': 'h2-synthetic-probe', 'consense.deployment': 'r1-20261029'},
            'HostConfig': {'NetworkMode': 'none', 'ReadonlyRootfs': True, 'Privileged': False,
                           'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'],
                           'Mounts': [{'Type': 'bind', 'Source': source, 'Target': target, 'ReadOnly': True}
                                      for target, source in {**jre.MOUNTS, **{k: v[0] for k, v in compiler.INPUTS.items()}}.items()],
                           'Memory': 536870912, 'NanoCpus': 500000000, 'PidsLimit': 32,
                           'Tmpfs': dict(compiler.TMPFS)}}}
        self.calls = []
        self.created = self.started = False
        self.reads = 0
        self.locked = False
        self.alter = None
        self.output = GOOD_OUTPUT

    @contextmanager
    def lock(self):
        self.assertFalse(self.locked)
        self.locked = True
        try:
            yield
        finally:
            self.locked = False

    def inspect(self, status):
        config = copy.deepcopy(self.profile['config'])
        host = config.pop('HostConfig')
        host.update(CapAdd=None, Binds=None, Devices=None, DeviceRequests=None, PidMode='',
                    PortBindings={}, PublishAllPorts=False)
        state = {'Status': status, 'Running': status == 'running', 'Paused': False,
                 'Restarting': False, 'Dead': False, 'Pid': 42 if status == 'running' else 0,
                 'ExitCode': 0, 'OOMKilled': False, 'Error': '',
                 'StartedAt': '0001-01-01T00:00:00Z' if status == 'created' else '2026-10-29T15:00:00Z',
                 'FinishedAt': '2026-10-29T15:00:02Z' if status == 'exited' else '0001-01-01T00:00:00Z'}
        row = {'Id': self.cid, 'Name': '/' + self.profile['name'], 'Image': config['Image'],
               'Config': config, 'HostConfig': host, 'State': state,
               'Mounts': [{'Type': m['Type'], 'Source': m['Source'], 'Destination': m['Target'],
                           'RW': not m['ReadOnly']} for m in host['Mounts']]}
        if self.alter:
            self.alter(row, status)
        return row

    def request(self, method, path, daemon, payload=None):
        self.assertTrue(self.locked)
        self.calls.append((method, path))
        if path == '/info':
            return {'ID': 'fixture', 'DockerRootDir': admission.DATA_ROOT}
        if path.startswith('/images/'):
            return {'Id': self.profile['config']['Image'], 'Config': {
                'User': 'consense:consense', 'Env': list(jre.IMAGE_ENV), 'WorkingDir': '/home/consense',
                'Volumes': None, 'ExposedPorts': None}}
        if path == '/containers/json?all=1':
            return [{'Id': self.cid}] if self.created else []
        if path.startswith('/containers/create?'):
            self.created = True
            return {'Id': self.cid}
        if path.endswith('/start'):
            self.started = True
            return None
        if path.endswith('/json'):
            if not self.started:
                return self.inspect('created')
            self.reads += 1
            return self.inspect('running' if self.reads == 1 else 'exited')
        self.fail((method, path))

    def run_fake(self):
        with mock.patch.object(compiler, '_verify_inputs'), \
             mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(admission, '_process_identity'), \
             mock.patch.object(admission, '_verify_mount_isolation'), \
             mock.patch.object(jre, '_verify_scope') as scopes, \
             mock.patch.object(jre, '_verify_resource'), \
             mock.patch.object(jre, '_verify_runtime', return_value={'fullClosureVerified': False}) as runtime, \
             mock.patch.object(jre, '_starttime', return_value=99), \
             mock.patch.object(jre, '_logs', return_value=self.output), \
             mock.patch.object(compiler.time, 'sleep'):
            result = compiler.run(self.profile, admission._pin(self.profile), {}, self.daemon)
            self.assertTrue(all(call == mock.call(compiler.SCOPE) for call in scopes.call_args_list))
            runtime.assert_called_once_with(self.profile, scope=compiler.SCOPE)
            return result

    def test_invalid_inputs_stop_before_engine_even_with_scope(self):
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(jre, '_verify_scope') as scopes, \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(jre, '_verify_resource'), \
             mock.patch.object(compiler, '_verify_inputs', side_effect=admission.AdmissionError('invalid inputs')), \
             mock.patch.object(admission, '_request') as request:
            with self.assertRaisesRegex(admission.AdmissionError, 'invalid inputs'):
                compiler.run(self.profile, admission._pin(self.profile), {}, self.daemon)
            scopes.assert_called_once_with(compiler.SCOPE)
            request.assert_not_called()

    def test_resource_failure_prevents_input_reads(self):
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(jre, '_verify_scope'), \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(jre, '_verify_resource', side_effect=admission.AdmissionError('stale resource')), \
             mock.patch.object(compiler, '_verify_inputs') as inputs, \
             mock.patch.object(admission, '_request') as request:
            with self.assertRaisesRegex(admission.AdmissionError, 'stale resource'):
                compiler.run(self.profile, admission._pin(self.profile), {}, self.daemon)
            inputs.assert_not_called()
            request.assert_not_called()

    @unittest.skipUnless(hasattr(os, 'geteuid') and os.geteuid() == 0, 'requires Linux root fixture')
    def test_input_hash_and_later_metadata_checks_use_same_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            stage = root / 'stage'
            stage.mkdir(mode=0o700)
            source = stage / 'fixture'
            source.write_bytes(b'fixture')
            source.chmod(0o444)
            info, directory = source.stat(), stage.stat()
            target = '/runtime/probe-input/fixture'
            inputs = {target: (str(source), hashlib.sha256(b'fixture').hexdigest(), 7)}
            ids = {target: (info.st_dev, info.st_ino)}
            with mock.patch.object(compiler, 'INPUTS', inputs), mock.patch.object(compiler, 'INPUT_IDS', ids), \
                 mock.patch.object(compiler, 'STAGE', stage), \
                 mock.patch.object(compiler, 'STAGE_ID', (directory.st_dev, directory.st_ino)), \
                 mock.patch.object(jre, '_safe_parent'), mock.patch.object(jre.time, 'sleep'):
                result = compiler._verify_inputs()
                self.assertEqual(result['bytesRead'], 7)
                metadata = compiler._verify_inputs(hash_contents=False, prior=result)
                self.assertEqual(metadata['bytesRead'], 0)
                source.chmod(0o644)
                with self.assertRaises(admission.AdmissionError):
                    compiler._verify_inputs(hash_contents=False, prior=result)

    def test_success_is_mock_only_and_retains_container(self):
        result = self.run_fake()
        self.assertEqual(result['id'], self.cid)
        self.assertFalse(result['removed'])
        self.assertFalse(result['fixtureRetained'])
        self.assertFalse(result['javaProcessIdentityVerified'])
        self.assertFalse(result['tmpfsRuntimeVerified'])
        self.assertFalse(result['releaseVerified'])
        self.assertFalse(any(method == 'DELETE' for method, _ in self.calls))

    def test_profile_refuses_modified_inputs_or_security(self):
        changes = (
            lambda h: h.__setitem__('Tmpfs', {'/runtime/probe': 'rw,size=16m'}),
            lambda h: h.__setitem__('NetworkMode', 'host'),
            lambda h: h['Mounts'][-1].__setitem__('ReadOnly', False),
            lambda h: h['Mounts'][-1].__setitem__('Source', '/different'),
            lambda h: h.__setitem__('PidsLimit', 33),
        )
        with mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']):
            for change in changes:
                profile = copy.deepcopy(self.profile)
                change(profile['config']['HostConfig'])
                with self.subTest(change=change), self.assertRaises(admission.AdmissionError):
                    compiler._profile(profile, admission._pin(profile))

    def test_unexpected_output_or_password_is_not_returned(self):
        for value in (GOOD_OUTPUT.replace('phase1 complete', 'phase1 failed'),
                      GOOD_OUTPUT.replace('phase2 complete', 'synthetic-only-new-password'),
                      GOOD_OUTPUT.replace('sqlstate=28000', 'sqlstate=99999', 1)):
            with self.subTest(value=value), self.assertRaises(admission.AdmissionError):
                compiler._output(value)

    def test_wrong_scope_and_failed_terminal_never_delete(self):
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(jre, '_verify_scope', side_effect=admission.AdmissionError('wrong scope')), \
             mock.patch.object(admission, '_request') as request:
            with self.assertRaisesRegex(admission.AdmissionError, 'wrong scope'):
                compiler.run(self.profile, admission._pin(self.profile), {}, self.daemon)
            request.assert_not_called()
        self.alter = lambda row, status: row['State'].__setitem__('ExitCode', 1) if status == 'exited' else None
        with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
            self.run_fake()
        self.assertTrue(self.created)
        self.assertFalse(any(method == 'DELETE' for method, _ in self.calls))

    def test_unexpected_container_during_runtime_check_prevents_create(self):
        request = self.request
        def changed(method, path, daemon, payload=None):
            if path == '/containers/json?all=1' and any(p == path for _, p in self.calls):
                self.calls.append((method, path))
                return [{'Id': 'c' * 64}]
            return request(method, path, daemon, payload)
        self.request = changed
        with self.assertRaises(admission.AdmissionError):
            self.run_fake()
        self.assertFalse(self.created)
        self.assertFalse(any(method == 'POST' for method, _ in self.calls))

    def test_changed_profile_at_inspection_keeps_created_container(self):
        self.alter = lambda row, status: row['HostConfig'].__setitem__('Tmpfs', {})
        with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
            self.run_fake()
        self.assertTrue(self.created)
        self.assertFalse(self.started)
        self.assertFalse(any(method == 'DELETE' for method, _ in self.calls))


if __name__ == '__main__':
    unittest.main()
