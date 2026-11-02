"""Fake-engine tests only; never contacts Docker or starts Java."""
from contextlib import contextmanager
import copy
from unittest import mock
import unittest

import compiler_probe_admission as compiler
import container_admission as admission
import jre_probe_admission as jre


class CompilerTest(unittest.TestCase):
    def setUp(self):
        self.cid = 'b' * 64
        self.daemon = {'id': 'fixture', 'socketDevice': 1, 'socketInode': 2,
                       'pid': 3, 'startTicks': 4, 'uid': 0, 'gid': 0}
        self.profile = {'name': 'consense-jre-compiler-probe-local01', 'config': {
            'Image': compiler.IMAGE, 'User': 'consense:consense', 'Entrypoint': ['/bin/sh'],
            'Cmd': list(compiler.COMMAND), 'Env': list(jre.ENV),
            'Labels': {'consense.role': 'jre-compiler-probe', 'consense.deployment': 'r1-20261029'},
            'HostConfig': {'NetworkMode': 'none', 'ReadonlyRootfs': True, 'Privileged': False,
                           'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'],
                           'Mounts': [{'Type': 'bind', 'Source': source, 'Target': target, 'ReadOnly': True}
                                      for target, source in jre.MOUNTS.items()],
                           'Memory': 536870912, 'NanoCpus': 500000000, 'PidsLimit': 32,
                           'Tmpfs': dict(compiler.TMPFS)}}}
        self.calls = []
        self.created = self.started = False
        self.reads = 0
        self.locked = False
        self.alter = None
        self.output = 'SOURCE_MODE_OK\nJAVAC_COMPILE_OK\n'

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
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
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

    def test_wrong_scope_blocks_before_any_engine_request(self):
        with mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request') as request, \
             mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(jre, '_verify_scope', side_effect=admission.AdmissionError('scope unavailable')):
            with self.assertRaisesRegex(admission.AdmissionError, 'scope unavailable'):
                compiler.run(self.profile, admission._pin(self.profile), {}, self.daemon)
            request.assert_not_called()

    def test_created_container_with_prior_start_never_starts(self):
        self.alter = lambda row, status: row['State'].__setitem__('StartedAt', '2026-10-29T15:00:00Z') if status == 'created' else None
        with self.assertRaisesRegex(admission.AdmissionError, 'retained or outcome unknown'):
            self.run_fake()
        self.assertTrue(self.created)
        self.assertFalse(self.started)
        self.assertFalse(any(method == 'DELETE' for method, _ in self.calls))

    def test_different_valid_image_is_rejected_even_with_matching_profile_hash(self):
        self.profile['config']['Image'] = 'sha256:' + 'a' * 64
        with mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request') as request, mock.patch.object(admission, '_lock') as lock:
            with self.assertRaisesRegex(admission.AdmissionError, 'invalid compiler command or identity'):
                compiler.run(self.profile, admission._pin(self.profile), {}, self.daemon)
            request.assert_not_called()
            lock.assert_not_called()

    def test_same_container_terminal_and_markers_are_reported_but_retained(self):
        result = self.run_fake()
        self.assertEqual(self.cid, result['id'])
        self.assertEqual(42, result['wrapperProcess']['pid'])
        self.assertEqual(0, result['exitCode'])
        self.assertFalse(result['removed'])
        self.assertFalse(result['javaProcessIdentityVerified'])
        self.assertFalse(result['tmpfsRuntimeVerified'])
        self.assertFalse(result['releaseVerified'])
        self.assertFalse(any(method == 'DELETE' for method, _ in self.calls))

    def test_wrong_profile_rejected_before_create(self):
        cases = (
            lambda h: h.__setitem__('Tmpfs', {'/tmp': 'rw,size=16m'}),
            lambda h: h.__setitem__('NetworkMode', 'host'),
            lambda h: h.__setitem__('Memory', 536870913),
            lambda h: h['Mounts'][0].__setitem__('ReadOnly', False),
            lambda h: h.__setitem__('Devices', [{'PathOnHost': '/dev/dxg'}]),
        )
        with mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']):
            for change in cases:
                profile = copy.deepcopy(self.profile)
                change(profile['config']['HostConfig'])
                with self.subTest(profile=profile), self.assertRaises(admission.AdmissionError):
                    compiler._profile(profile, admission._pin(profile))
            with self.assertRaises(admission.AdmissionError):
                compiler._profile(self.profile, '0' * 64)

    def test_new_container_during_runtime_verification_blocks_create(self):
        original = self.request
        def changed(method, path, daemon, payload=None):
            if path == '/containers/json?all=1' and any(p == path for _, p in self.calls):
                self.calls.append((method, path))
                return [{'Id': 'c' * 64}]
            return original(method, path, daemon, payload)
        self.request = changed
        with self.assertRaisesRegex(admission.AdmissionError, 'unknown or missing container'):
            self.run_fake()
        self.assertFalse(self.created)
        self.assertFalse(any(method == 'POST' for method, _ in self.calls))

    def test_inspect_tmpfs_mismatch_keeps_created_container_unstarted(self):
        self.alter = lambda row, status: row['HostConfig'].__setitem__('Tmpfs', {'/tmp': 'rw,size=16m'})
        with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
            self.run_fake()
        self.assertTrue(self.created)
        self.assertFalse(self.started)
        self.assertFalse(any(method == 'DELETE' for method, _ in self.calls))

    def test_bad_markers_or_exit_identity_retain_container(self):
        for output in ('JAVAC_COMPILE_OK\nSOURCE_MODE_OK\n', 'SOURCE_MODE_OK\n',
                       'SOURCE_MODE_OK\nJAVAC_COMPILE_OK\nJAVAC_COMPILE_OK\n'):
            self.created = self.started = False
            self.reads = 0
            self.output = output
            with self.subTest(output=output), self.assertRaisesRegex(admission.AdmissionError, 'retained'):
                self.run_fake()
        self.output = 'SOURCE_MODE_OK\nJAVAC_COMPILE_OK\n'
        for change in (lambda row: row['State'].__setitem__('OOMKilled', True),
                       lambda row: row['State'].__setitem__('ExitCode', 1),
                       lambda row: row.__setitem__('Id', 'c' * 64),
                       lambda row: row['State'].__setitem__('StartedAt', '2026-10-29T15:00:01Z')):
            self.created = self.started = False
            self.reads = 0
            self.alter = lambda row, status: change(row) if status == 'exited' else None
            with self.subTest(change=change), self.assertRaisesRegex(admission.AdmissionError, 'retained'):
                self.run_fake()
        self.assertFalse(any(method == 'DELETE' for method, _ in self.calls))


if __name__ == '__main__':
    unittest.main()
