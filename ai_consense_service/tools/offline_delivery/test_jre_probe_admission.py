"""Fake Docker tests; no daemon or containers are started."""
from contextlib import contextmanager
import copy
import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
from unittest import mock
import unittest

import container_admission as admission
import jre_probe_admission as probe


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.cid = 'b' * 64
        self.daemon = {'id': 'fixture', 'socketDevice': 1, 'socketInode': 2,
                       'pid': 3, 'startTicks': 4, 'uid': 0, 'gid': 0}
        self.profile = {
            'name': 'consense-jre-probe-local01',
            'config': {
                'Image': 'sha256:' + 'a' * 64, 'User': 'consense:consense',
                'Entrypoint': ['/bin/sh'], 'Cmd': list(probe.COMMAND),
                'Env': list(probe.ENV),
                'Labels': {'consense.role': 'jre-probe', 'consense.deployment': 'r1-20261029'},
                'HostConfig': {'NetworkMode': 'none', 'ReadonlyRootfs': True, 'Privileged': False,
                               'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'],
                               'Mounts': [{'Type': 'bind', 'Source': source, 'Target': target, 'ReadOnly': True}
                                          for target, source in probe.MOUNTS.items()],
                               'Memory': 268435456, 'NanoCpus': 500000000, 'PidsLimit': 32},
            },
        }
        self.calls = []
        self.started = False
        self.created = False
        self.removed = False
        self.reads = 0
        self.locked = False
        self.exit_code = 0
        self.alter = None

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
        config['ExposedPorts'] = None
        host.update(CapAdd=None, Binds=None, Devices=None, DeviceRequests=None, PidMode='',
                    PortBindings=None, PublishAllPorts=False)
        state = {'Status': status, 'Running': status == 'running', 'Paused': False,
                 'Restarting': False, 'Dead': False, 'Pid': 42 if status == 'running' else 0,
                 'ExitCode': self.exit_code, 'OOMKilled': False, 'Error': '',
                 'StartedAt': '0001-01-01T00:00:00Z' if status == 'created' else '2026-10-29T15:00:00.123456789Z',
                 'FinishedAt': '0001-01-01T00:00:00Z' if status in ('created', 'running') else '2026-10-29T15:00:02.123456789Z'}
        row = {'Id': self.cid, 'Name': '/' + self.profile['name'], 'Image': config['Image'],
               'Config': config, 'HostConfig': host, 'State': state,
               'Mounts': [{'Type': 'bind', 'Source': m['Source'], 'Destination': m['Target'], 'RW': False}
                          for m in host['Mounts']]}
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
                'User': 'consense:consense', 'Env': list(probe.IMAGE_ENV), 'WorkingDir': '/home/consense',
                'Volumes': None, 'ExposedPorts': None}}
        if path == '/containers/json?all=1':
            return [] if self.removed or not self.created else [{'Id': self.cid}]
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
        if method == 'DELETE':
            self.removed = True
            return None
        self.fail(path)

    def run_probe(self, verify=None, pin=None, timeout=30, output='openjdk version "17.0.20.1" 2026-10-20\n'):
        if verify is None:
            verify = lambda profile: None
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(admission, '_process_identity') as identity, \
             mock.patch.object(admission, '_verify_mount_isolation'), \
             mock.patch.object(probe, '_verify_scope'), \
             mock.patch.object(probe, '_verify_resource'), \
             mock.patch.object(probe, '_verify_runtime', side_effect=verify), \
             mock.patch.object(probe, '_starttime', return_value=99), \
             mock.patch.object(probe, '_logs', return_value=output), \
             mock.patch.object(probe.time, 'sleep'):
            result = probe.run(self.profile, pin or admission._pin(self.profile), {}, self.daemon, timeout)
            self.assertEqual([mock.call(42), mock.call(42)], identity.call_args_list)
            return result

    def test_success_observes_process_and_cleans_exact_id_under_lock(self):
        def verify(profile):
            self.assertTrue(self.locked)
        result = self.run_probe(verify)
        self.assertEqual({'pid': 42, 'starttime': 99, 'startedAt': '2026-10-29T15:00:00.123456789Z'}, result['wrapperProcess'])
        self.assertEqual(0, result['exitCode'])
        self.assertTrue(result['removed'])
        self.assertIn(('DELETE', '/containers/' + self.cid), self.calls)

    def test_profile_and_runtime_failure_precede_create(self):
        for change in (
            lambda c: c['HostConfig'].__setitem__('NetworkMode', 'host'),
            lambda c: c['HostConfig']['Mounts'][0].__setitem__('ReadOnly', False),
            lambda c: c.__setitem__('Cmd', ['-c', 'other']),
            lambda c: c.__setitem__('User', 'root'),
            lambda c: c['HostConfig'].__setitem__('Memory', 536870913),
        ):
            original = copy.deepcopy(self.profile)
            change(self.profile['config'])
            with self.assertRaises(admission.AdmissionError):
                self.run_probe()
            self.profile = original
        with self.assertRaises(admission.AdmissionError):
            self.run_probe(pin='0' * 64)
        with self.assertRaisesRegex(ValueError, 'unverified'):
            self.run_probe(verify=lambda profile: (_ for _ in ()).throw(ValueError('unverified')))
        self.assertFalse(any(path.startswith('/containers/create?') for _, path in self.calls))

    def test_failure_exit_and_changed_mount_never_delete(self):
        self.exit_code = 1
        with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
            self.run_probe()
        self.assertFalse(self.removed)
        self.created, self.started, self.reads, self.exit_code = False, False, 0, 0
        self.alter = lambda row, status: row['Mounts'][0].__setitem__('RW', True)
        with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
            self.run_probe()
        self.assertFalse(self.removed)

    def test_fast_exit_without_process_identity_is_not_success(self):
        self.reads = 1
        with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
            self.run_probe()
        self.assertFalse(self.removed)

    def test_wrong_version_scope_and_runtime_failure_fail_closed(self):
        with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
            self.run_probe(output='openjdk version "17.0.19"\n')
        self.assertFalse(self.removed)
        self.created, self.started = False, False
        self.calls.clear()
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(probe, '_verify_scope', side_effect=admission.AdmissionError('probe scope unavailable')):
            with self.assertRaisesRegex(admission.AdmissionError, 'scope unavailable'):
                probe.run(self.profile, admission._pin(self.profile), {}, self.daemon)
        self.assertFalse(any(path.startswith('/containers/create?') for _, path in self.calls))
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(probe, '_verify_scope'), \
             mock.patch.object(probe, '_verify_resource'), \
             mock.patch.object(admission, '_verify_mount_isolation'), \
             mock.patch.object(probe, '_verify_runtime', side_effect=admission.AdmissionError('runtime bytes changed')):
            with self.assertRaisesRegex(admission.AdmissionError, 'runtime bytes changed'):
                probe.run(self.profile, admission._pin(self.profile), {}, self.daemon)
        self.assertFalse(any(path.startswith('/containers/create?') for _, path in self.calls))

    @unittest.skipUnless(hasattr(os, 'O_NOFOLLOW') and hasattr(os, 'O_CLOEXEC'), 'requires Linux no-follow file access')
    def test_actual_scope_requires_exact_authorized_operation_and_rejects_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'state.json'
            state = {'ticket01Gate': 'closed', 'heavyTaskGate': 'ordered-single-heavy-task',
                     'heavyTaskOwner': 'ticket-02', 'heavyTaskScope': probe.SCOPE}
            with mock.patch.object(probe, 'STATE', path):
                path.write_text(json.dumps(state), encoding='utf-8')
                probe._verify_scope()
                state['heavyTaskScope'] = 'different operation'
                path.write_text(json.dumps(state), encoding='utf-8')
                with self.assertRaisesRegex(admission.AdmissionError, 'scope unavailable'):
                    probe._verify_scope()
                path.write_text('{"heavyTaskOwner":"ticket-02","heavyTaskOwner":"ticket-02"}', encoding='utf-8')
                with self.assertRaisesRegex(admission.AdmissionError, 'duplicate scope field'):
                    probe._verify_scope()

    @unittest.skipUnless(hasattr(os, 'O_NOFOLLOW') and hasattr(os, 'O_CLOEXEC') and hasattr(os, 'geteuid')
                         and os.geteuid() == 0, 'requires root and Linux no-follow file access')
    def test_runtime_entry_checks_bytes_and_link_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'java'; source.write_bytes(b'fixture')
            link = root / 'link'; link.symlink_to('java')
            digest = hashlib.sha256(b'fixture').hexdigest()
            with mock.patch.object(probe, '_safe_parent'), mock.patch.object(probe.time, 'sleep'):
                count = probe._verify_entry(source, ('file', 7, digest), probe.time.monotonic(), 0)
                self.assertEqual(7, count)
                self.assertEqual(count, probe._verify_entry(link, ('symlink', 'java'), probe.time.monotonic(), count))
                source.write_bytes(b'changed')
                self.assertEqual(0, probe._verify_entry(source, ('file', 7, digest), probe.time.monotonic(), 0, False),
                                 'Metadata-only recheck deliberately does not prove unchanged bytes')
                with self.assertRaisesRegex(admission.AdmissionError, 'runtime bytes changed'):
                    probe._verify_entry(source, ('file', 7, digest), probe.time.monotonic(), 0)
                with self.assertRaisesRegex(admission.AdmissionError, 'runtime link differs'):
                    probe._verify_entry(link, ('symlink', 'other'), probe.time.monotonic(), 0)

    def test_runtime_hash_checks_only_the_fixed_scopes(self):
        info = SimpleNamespace(st_dev=1, st_ino=2, st_mode=0o100644, st_uid=0, st_gid=0,
                               st_size=7, st_mtime_ns=1, st_ctime_ns=1, st_nlink=1)
        digest = hashlib.sha256(b'fixture').hexdigest()
        for scope in (probe.SCOPE, probe.COMPILER_SCOPE, probe.H2_SYNTHETIC_SCOPE, probe.H2_DISK_BOOTSTRAP_SCOPE):
            with self.subTest(scope=scope), mock.patch.object(probe, '_safe_parent'), \
                 mock.patch.object(probe.Path, 'lstat', return_value=info), \
                 mock.patch.object(probe.os, 'open', return_value=42), \
                 mock.patch.object(probe.os, 'fstat', return_value=info), \
                 mock.patch.object(probe.os, 'read', side_effect=[b'fixture', b'']), \
                 mock.patch.object(probe.os, 'close'), mock.patch.object(probe.time, 'sleep'), \
                 mock.patch.object(probe, '_verify_scope') as verify:
                probe._verify_entry(Path('/fixture'), ('file', 7, digest), probe.time.monotonic(),
                                    32 * 1024 * 1024 - 4, scope=scope)
                verify.assert_called_once_with(scope)
        with mock.patch.object(probe, '_read_manifest') as read:
            with self.assertRaisesRegex(admission.AdmissionError, 'unsupported runtime scope'):
                probe._verify_runtime({}, scope='other')
            read.assert_not_called()

    def test_runtime_growth_never_reads_beyond_declared_file(self):
        before = SimpleNamespace(st_dev=1, st_ino=2, st_mode=0o100644, st_uid=0, st_gid=0,
                                 st_size=7, st_mtime_ns=1, st_ctime_ns=1, st_nlink=1)
        grown = SimpleNamespace(**{**vars(before), 'st_size': 8, 'st_mtime_ns': 2})
        with mock.patch.object(probe, '_safe_parent'), mock.patch.object(probe.Path, 'lstat', return_value=before), \
             mock.patch.object(probe.os, 'open', return_value=42), \
             mock.patch.object(probe.os, 'fstat', side_effect=[before, grown]), \
             mock.patch.object(probe.os, 'read', return_value=b'fixture') as read, \
             mock.patch.object(probe.os, 'close'), mock.patch.object(probe.time, 'sleep'):
            with self.assertRaisesRegex(admission.AdmissionError, 'runtime bytes changed'):
                probe._verify_entry(Path('/fixture'), ('file', 7, hashlib.sha256(b'fixture').hexdigest()),
                                    probe.time.monotonic(), 0)
            read.assert_called_once_with(42, 7)

    def test_runtime_total_budget_refuses_before_read_and_accepts_exact_limit(self):
        info = SimpleNamespace(st_dev=1, st_ino=2, st_mode=0o100644, st_uid=0, st_gid=0,
                               st_size=7, st_mtime_ns=1, st_ctime_ns=1, st_nlink=1)
        row = ('file', 7, hashlib.sha256(b'fixture').hexdigest())
        limit = 192 * 1024 * 1024
        with mock.patch.object(probe, '_safe_parent'), mock.patch.object(probe.Path, 'lstat', return_value=info), \
             mock.patch.object(probe.os, 'open', return_value=42), mock.patch.object(probe.os, 'fstat', return_value=info), \
             mock.patch.object(probe.os, 'read', return_value=b'fixture') as read, \
             mock.patch.object(probe.os, 'close'), mock.patch.object(probe.time, 'sleep'), \
             mock.patch.object(probe, '_verify_scope'):
            with self.assertRaisesRegex(admission.AdmissionError, 'runtime read budget exceeded'):
                probe._verify_entry(Path('/fixture'), row, probe.time.monotonic(), limit - 3)
            read.assert_not_called()
            self.assertEqual(limit, probe._verify_entry(Path('/fixture'), row, probe.time.monotonic(), limit - 7))
            read.assert_called_once_with(42, 7)

    def test_runtime_short_read_fails_without_extra_data(self):
        info = SimpleNamespace(st_dev=1, st_ino=2, st_mode=0o100644, st_uid=0, st_gid=0,
                               st_size=7, st_mtime_ns=1, st_ctime_ns=1, st_nlink=1)
        with mock.patch.object(probe, '_safe_parent'), mock.patch.object(probe.Path, 'lstat', return_value=info), \
             mock.patch.object(probe.os, 'open', return_value=42), mock.patch.object(probe.os, 'fstat', return_value=info), \
             mock.patch.object(probe.os, 'read', side_effect=[b'fix', b'']) as read, \
             mock.patch.object(probe.os, 'close'), mock.patch.object(probe.time, 'sleep'):
            with self.assertRaisesRegex(admission.AdmissionError, 'runtime bytes changed'):
                probe._verify_entry(Path('/fixture'), ('file', 7, hashlib.sha256(b'fixture').hexdigest()),
                                    probe.time.monotonic(), 0)
            self.assertEqual([mock.call(42, 7), mock.call(42, 4)], read.call_args_list)

    def test_resource_sample_requires_fresh_matching_daemon(self):
        snapshot = {'status': 'ok', 'reason': None, 'boot': 'fixture', 'monotonicNs': 10,
                    'daemon': {'pid': 3, 'starttime': 4, 'exe': '/usr/bin/dockerd'},
                    'observation': {'rssBytes': 1, 'processCount': 2, 'memAvailableBytes': 9 * 1024**3,
                                    'ext4AvailableBytes': 11 * 1024**3, 'dAvailableBytes': 21 * 1024**3}}
        with mock.patch.object(probe, '_read_manifest', return_value=snapshot), \
             mock.patch.object(probe.Path, 'read_text', return_value='fixture'), \
             mock.patch.object(probe.time, 'monotonic_ns', return_value=20):
            probe._verify_resource(self.daemon)
            snapshot['daemon']['pid'] = 8
            with self.assertRaisesRegex(admission.AdmissionError, 'resource sample'):
                probe._verify_resource(self.daemon)
            snapshot['daemon']['pid'] = 3
            snapshot['monotonicNs'] = -10_000_000_000
            with self.assertRaisesRegex(admission.AdmissionError, 'resource sample'):
                probe._verify_resource(self.daemon)

    def test_five_slots_block_creation(self):
        with mock.patch.object(admission, '_inventory', return_value=['a' * 64] * 5):
            with self.assertRaisesRegex(admission.AdmissionError, 'slot unavailable'):
                self.run_probe()
        self.assertFalse(any(path.startswith('/containers/create?') for _, path in self.calls))

    def test_inventory_or_create_unknown_never_delete_or_retry(self):
        request = self.request
        def foreign(method, path, daemon, payload=None):
            if path == '/containers/json?all=1':
                return [{'Id': 'c' * 64}]
            return request(method, path, daemon, payload)
        self.request = foreign
        with self.assertRaises(admission.AdmissionError):
            self.run_probe()
        self.assertFalse(self.removed)
        def unknown(method, path, daemon, payload=None):
            if path.startswith('/containers/create?'):
                self.calls.append((method, path))
                raise TimeoutError()
            return request(method, path, daemon, payload)
        self.request = unknown
        with self.assertRaisesRegex(admission.AdmissionError, 'outcome unknown'):
            self.run_probe()
        self.assertEqual(1, sum(path.startswith('/containers/create?') for _, path in self.calls))
        self.assertFalse(self.removed)

    def test_timeout_keeps_container(self):
        original = self.inspect
        self.inspect = lambda status: original('running' if self.started else status)
        with mock.patch.object(probe.time, 'monotonic', side_effect=[0, 1, 31]):
            with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
                self.run_probe()
        self.assertFalse(self.removed)

    def test_image_metadata_mismatch_fails_before_create(self):
        original = self.request
        def changed(method, path, daemon, payload=None):
            row = original(method, path, daemon, payload)
            if path.startswith('/images/'):
                row['Config']['Env'].append('JAVA_TOOL_OPTIONS=-Xmx1g')
            return row
        self.request = changed
        with self.assertRaisesRegex(admission.AdmissionError, 'image metadata'):
            self.run_probe()
        self.assertFalse(any(path.startswith('/containers/create?') for _, path in self.calls))

    def test_inspected_merged_env_mismatch_retains_before_start(self):
        self.alter = lambda row, status: row['Config']['Env'].append('JAVA_TOOL_OPTIONS=-Xmx1g')
        with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
            self.run_probe()
        self.assertFalse(self.started)
        self.assertFalse(self.removed)

    def test_inspect_allows_omitted_exposed_ports_but_rejects_nonempty_or_invalid_values(self):
        def docker_shape(row, status):
            row['Config'].pop('ExposedPorts')
            row['HostConfig']['PortBindings'] = {}
        self.alter = docker_shape
        self.assertTrue(self.run_probe()['removed'])
        for value in ({'80/tcp': {}}, [], False, ''):
            with self.subTest(exposed=value):
                self.created = self.started = self.removed = False
                self.reads = 0
                self.alter = lambda row, status: row['Config'].__setitem__('ExposedPorts', value)
                with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
                    self.run_probe()
                self.assertFalse(self.started)
                self.assertFalse(self.removed)
        for value in ({'80/tcp': []}, [], False, ''):
            with self.subTest(bindings=value):
                self.created = self.started = self.removed = False
                self.reads = 0
                self.alter = lambda row, status: row['HostConfig'].__setitem__('PortBindings', value)
                with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
                    self.run_probe()
                self.assertFalse(self.started)
                self.assertFalse(self.removed)

    def test_resume_uses_existing_created_probe_without_create_or_content_rehash(self):
        self.created = True
        self.alter = lambda row, status: row['Config'].pop('ExposedPorts')
        runtime = {'bytesRead': 0, 'hashesRechecked': False, 'fullClosureVerified': False}
        with mock.patch.object(probe, 'RESUME_ID', self.cid), \
             mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(admission, '_process_identity'), \
             mock.patch.object(admission, '_verify_mount_isolation'), \
             mock.patch.object(probe, '_verify_scope') as scope, \
             mock.patch.object(probe, '_verify_resource'), \
             mock.patch.object(probe, '_verify_runtime', return_value=runtime) as verify, \
             mock.patch.object(probe, '_starttime', return_value=99), \
             mock.patch.object(probe, '_logs', return_value='openjdk version "17.0.20.1"\n'), \
             mock.patch.object(probe.time, 'sleep'):
            result = probe.resume_created(self.profile, admission._pin(self.profile), self.cid, self.daemon)
        verify.assert_called_once_with(self.profile, hash_contents=False)
        self.assertTrue(scope.call_args_list)
        self.assertTrue(all(call == mock.call(probe.RESUME_SCOPE) for call in scope.call_args_list))
        self.assertEqual(runtime, result['runtimeFileCheck'])
        self.assertTrue(result['removed'])
        self.assertFalse(any(path.startswith('/containers/create?') for _, path in self.calls))
        self.assertEqual(1, sum(path.endswith('/start') for _, path in self.calls))

    def test_resume_refuses_other_or_previously_started_container(self):
        self.created = True
        self.alter = lambda row, status: row['State'].__setitem__('StartedAt', '2026-10-29T15:00:00Z')
        with mock.patch.object(probe, 'RESUME_ID', self.cid), \
             mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(admission, '_verify_mount_isolation'), \
             mock.patch.object(probe, '_verify_scope'), \
             mock.patch.object(probe, '_verify_resource'), \
             mock.patch.object(probe, '_verify_runtime', return_value={'hashesRechecked': False}):
            with self.assertRaisesRegex(admission.AdmissionError, 'fixed existing probe identity'):
                probe.resume_created(self.profile, admission._pin(self.profile), 'c' * 64, self.daemon)
            with self.assertRaisesRegex(admission.AdmissionError, 'already started'):
                probe.resume_created(self.profile, admission._pin(self.profile), self.cid, self.daemon)
        self.assertFalse(any(path.endswith('/start') for _, path in self.calls))
        self.assertFalse(self.removed)

    def test_resume_does_not_start_when_source_recheck_fails(self):
        self.created = True
        with mock.patch.object(probe, 'RESUME_ID', self.cid), \
             mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(admission, '_verify_mount_isolation'), \
             mock.patch.object(probe, '_verify_scope'), \
             mock.patch.object(probe, '_verify_resource'), \
             mock.patch.object(probe, '_verify_runtime', side_effect=admission.AdmissionError('runtime file changed')):
            with self.assertRaisesRegex(admission.AdmissionError, 'runtime file changed'):
                probe.resume_created(self.profile, admission._pin(self.profile), self.cid, self.daemon)
        self.assertFalse(any(path.endswith('/start') for _, path in self.calls))
        self.assertFalse(self.removed)

    def test_bad_terminal_evidence_never_cleans_up(self):
        for change in (
            lambda state: state.__setitem__('OOMKilled', True),
            lambda state: state.__setitem__('Error', 'runtime failure'),
            lambda state: state.__setitem__('StartedAt', '2026-10-29T15:00:01Z'),
            lambda state: state.__setitem__('FinishedAt', '2026-10-29T14:59:59Z'),
            lambda state: state.__setitem__('FinishedAt', '0001-01-01T00:00:00Z'),
            lambda state: state.pop('FinishedAt'),
        ):
            with self.subTest(change=change):
                self.created = self.started = self.removed = False
                self.reads = 0
                self.alter = lambda row, status: change(row['State']) if status == 'exited' else None
                with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
                    self.run_probe()
                self.assertFalse(self.removed)

    def test_log_frames_are_bounded_and_validated(self):
        body = b'openjdk version "17.0.20.1"\n'
        frame = b'\x02\0\0\0' + struct.pack('>I', len(body)) + body
        response = mock.Mock(status=200)
        connection = mock.Mock()
        connection.getresponse.return_value = response
        with mock.patch.object(admission, '_daemon_peer', return_value=mock.Mock()), \
             mock.patch.object(probe.http.client, 'HTTPConnection', return_value=connection):
            response.read.return_value = frame
            self.assertEqual(body.decode(), probe._logs(self.cid, self.daemon))
            response.read.return_value = frame[:-1]
            with self.assertRaises(admission.AdmissionError):
                probe._logs(self.cid, self.daemon)
            response.read.return_value = b'x' * 65537
            with self.assertRaises(admission.AdmissionError):
                probe._logs(self.cid, self.daemon)

    def test_binary_docker_logs_reach_version_check_without_mocking_logs(self):
        body = b'openjdk version "17.0.20.1" 2026-10-20\n'
        response = mock.Mock(status=200)
        response.read.return_value = struct.pack('>B3xI', 2, len(body)) + body
        connection = mock.Mock()
        connection.getresponse.return_value = response
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']), \
             mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(admission, '_process_identity'), \
             mock.patch.object(admission, '_verify_mount_isolation'), \
             mock.patch.object(probe, '_verify_scope'), \
             mock.patch.object(probe, '_verify_resource'), \
             mock.patch.object(probe, '_verify_runtime'), \
             mock.patch.object(probe, '_starttime', return_value=99), \
             mock.patch.object(probe.time, 'sleep'), \
             mock.patch.object(admission, '_daemon_peer', return_value=mock.Mock()), \
             mock.patch.object(probe.http.client, 'HTTPConnection', return_value=connection):
            result = probe.run(self.profile, admission._pin(self.profile), {}, self.daemon)
        self.assertEqual(body.decode(), result['output'])
        self.assertTrue(result['removed'])

    def test_near_miss_versions_are_rejected(self):
        for output in ('openjdk version "17x0x20x1"\n',
                       'openjdk version "17.0.20.10"\n',
                       'prefix openjdk version "17.0.20.1"\n',
                       'openjdk version "17.0.20.1"suffix\n'):
            with self.subTest(output=output):
                self.created = self.started = self.removed = False
                self.reads = 0
                with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
                    self.run_probe(output=output)
                self.assertFalse(self.removed)


if __name__ == '__main__':
    unittest.main()
