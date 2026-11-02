"""Isolated Docker API fixtures; no daemon, cgroup, or GPU is contacted."""
from contextlib import nullcontext
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import container_admission as admission
import test_container_admission as fixture_module


class TwoPhaseGpuTest(unittest.TestCase):
    def setUp(self):
        fixture = fixture_module.AdmissionTest('test_normal_start_is_locked_and_returns_actual_identity')
        fixture.setUp()
        self.fixture = fixture
        self.cpu_profile = copy.deepcopy(fixture.profile)
        self.profile = copy.deepcopy(fixture.profile)
        self.profile.update(role='gpu', name='consense-gpu-local01', ports=[28684])
        config = self.profile['config']
        config.update(Entrypoint=['/runtime/envs/retrieval/bin/python3.12.0',
                                   '/runtime/tools/offline_delivery/fixed_gpu_worker.py', '--config'],
                      Cmd=['/runtime/config/gpu.json'])
        config['Labels']['consense.role'] = 'gpu'
        config['HostConfig']['Devices'] = [{'PathOnHost': '/dev/dxg', 'PathInContainer': '/dev/dxg',
                                            'CgroupPermissions': 'rw'}]
        temporary = tempfile.TemporaryDirectory(prefix='two-phase-gpu-')
        self.addCleanup(temporary.cleanup)
        self.config_dir = Path(temporary.name)
        self.config_file = self.config_dir / 'gpu.json'
        self.cgroup_root = self.config_dir / 'cgroups'
        self.cgroup_root.mkdir()
        (self.cgroup_root / 'cgroup.controllers').write_text('cpu memory pids\n')
        self.cgroup_parent = self.cgroup_root / self.profile['name']
        self.cgroup_parent.mkdir(parents=True)
        (self.cgroup_parent / 'cgroup.procs').write_text('')
        self.proc_root = self.config_dir / 'proc'
        (self.proc_root / '42').mkdir(parents=True)
        (self.proc_root / '42/cgroup').write_text(
            '0::/docker/' + self.profile['name'] + '/' + fixture.cid + '\n')
        config['HostConfig'].update(CgroupnsMode='host', CgroupParent='/docker/' + self.profile['name'])
        config['HostConfig']['Mounts'] = [{'Type': 'bind', 'Source': str(self.config_dir),
                                           'Target': '/runtime/config', 'ReadOnly': True},
                                          {'Type': 'bind', 'Source': str(self.cgroup_parent),
                                           'Target': '/delegated', 'ReadOnly': False}]
        self.daemon = fixture.daemon
        self.cid = fixture.cid
        self.state = 'absent'
        self.source_cid = 'd' * 64
        self.source_state = 'absent'
        self.source_profile = copy.deepcopy(self.cpu_profile)
        self.source_profile.update(role='source', name='consense-source-local01', ports=[28681])
        self.source_profile['config']['Labels']['consense.role'] = 'source'
        self.foreign_status = None
        self.mutate_after_start = False
        self.fail_create = False
        self.fail_start = False
        self.calls = []
        for name, replacement in (
            ('_lock', lambda: nullcontext()),
            ('_request', self.docker),
            ('_ports_available', lambda _: None),
            ('_process_identity', lambda _: None),
            ('_gpu_device', lambda _: (1, 2, 3)),
            ('GPU_CGROUP_PARENT_ROOT', str(self.cgroup_root)),
            ('PROC_ROOT', str(self.proc_root)),
        ):
            patcher = mock.patch.object(admission, name, replacement)
            patcher.start()
            self.addCleanup(patcher.stop)

    def docker(self, method, path, _daemon, payload=None):
        self.calls.append((method, path, copy.deepcopy(payload)))
        if path == '/info':
            return {'ID': self.daemon['id'], 'DockerRootDir': admission.DATA_ROOT}
        if path == '/containers/json?all=1':
            rows = [] if self.state == 'absent' else [{'Id': self.cid}]
            if self.source_state != 'absent':
                rows.append({'Id': self.source_cid})
            return rows + ([{'Id': 'c' * 64}] if self.foreign_status else [])
        if path.startswith('/containers/create?'):
            if payload['Labels']['consense.role'] == 'source':
                if self.source_state != 'absent':
                    raise AssertionError('duplicate source create')
                self.source_state = 'created'
                return {'Id': self.source_cid}
            if self.state != 'absent':
                raise AssertionError('duplicate create')
            self.state = 'created'
            if self.fail_create:
                raise TimeoutError('create outcome unknown')
            return {'Id': self.cid}
        if path == '/containers/' + self.cid + '/json':
            result = self.fixture.inspect(self.state, self.profile, self.cid)
            result['Mounts'] = [{'Type': item['Type'], 'Source': item['Source'],
                                 'Destination': item['Target'], 'RW': not item['ReadOnly']}
                                for item in self.profile['config']['HostConfig']['Mounts']]
            return result
        if path == '/containers/' + self.source_cid + '/json':
            return self.fixture.inspect(self.source_state, self.source_profile, self.source_cid)
        if path == '/containers/' + 'c' * 64 + '/json':
            return self.fixture.inspect(self.foreign_status, self.profile, 'c' * 64)
        if path == '/containers/' + self.cid + '/start':
            self.state = 'running'
            child = self.cgroup_parent / self.cid
            child.mkdir()
            (child / 'cgroup.procs').write_text('42\n')
            if self.mutate_after_start:
                self.config_file.write_bytes(b'tampered after start')
            if self.fail_start:
                raise TimeoutError('outcome unknown')
            return None
        if path == '/containers/' + self.source_cid + '/start':
            self.source_state = 'running'
            return None
        raise AssertionError(path)

    def create(self):
        return admission.start(self.profile, fixture_module.pin(self.profile), {}, self.daemon, defer_start=True)

    def config_digest(self):
        self.config_file.write_bytes(('{' + '"containerId":"' + self.cid + '",'
                + '"cgroupRoot":"/delegated/' + self.cid + '"}').encode())
        return hashlib.sha256(self.config_file.read_bytes()).hexdigest()

    def finish(self, created, digest):
        return admission.start_created_gpu(self.profile, fixture_module.pin(self.profile), created, digest, {}, self.daemon)

    def test_real_id_can_pin_config_then_start_exact_created_object(self):
        created = self.create()
        self.assertEqual({'role': 'gpu', 'id': self.cid, 'profileSha256': fixture_module.pin(self.profile),
                          'gpuDevice': [1, 2, 3], 'status': 'created'}, created)
        self.assertEqual('created', self.state)
        self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))
        result = self.finish(created, self.config_digest())
        self.assertEqual({'role': 'gpu', 'id': self.cid, 'pid': 42, 'status': 'running'}, result)
        self.assertEqual(1, sum(path.startswith('/containers/create?') for _, path, _ in self.calls))
        self.assertEqual(1, sum(path.endswith('/start') for _, path, _ in self.calls))

    def test_source_can_start_beside_one_explicit_created_gpu(self):
        created = self.create()
        pending = {'id': created['id'], 'profile': self.profile,
                   'profileSha256': fixture_module.pin(self.profile)}
        result = admission.start(self.source_profile, fixture_module.pin(self.source_profile),
                                 {}, self.daemon, pending_gpu=pending)
        self.assertEqual({'role': 'source', 'id': self.source_cid, 'pid': 42, 'status': 'running'}, result)
        self.assertEqual('created', self.state)
        self.assertEqual('running', self.source_state)

    def test_source_rejects_unregistered_created_gpu(self):
        self.create()
        with self.assertRaises(admission.AdmissionError):
            admission.start(self.source_profile, fixture_module.pin(self.source_profile), {}, self.daemon)
        self.assertEqual('absent', self.source_state)

    def test_source_reserves_a_running_slot_for_prepared_gpu(self):
        created = self.create()
        pending = {'id': created['id'], 'profile': self.profile,
                   'profileSha256': fixture_module.pin(self.profile)}
        self.calls.clear()
        with mock.patch.object(admission, '_inventory', return_value=['1', '2', '3', '4']):
            with self.assertRaisesRegex(admission.AdmissionError, 'slot unavailable'):
                admission.start(self.source_profile, fixture_module.pin(self.source_profile),
                                {}, self.daemon, pending_gpu=pending)
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_gpu_config_wrong_id_or_cgroup_blocks_start(self):
        created = self.create()
        for row in ({'containerId': 'a' * 64, 'cgroupRoot': '/delegated/' + self.cid},
                    {'containerId': self.cid, 'cgroupRoot': '/delegated/' + 'a' * 64}):
            raw = json.dumps(row).encode()
            self.config_file.write_bytes(raw)
            with self.assertRaisesRegex(admission.AdmissionError, 'does not bind'):
                self.finish(created, hashlib.sha256(raw).hexdigest())
        self.assertEqual('created', self.state)
        self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))

    def test_untrusted_gpu_config_owner_or_directory_blocks_start(self):
        created = self.create()
        digest = self.config_digest()
        os.chown(self.config_file, 10001, 10001)
        with self.assertRaisesRegex(admission.AdmissionError, 'unsafe GPU config file'):
            self.finish(created, digest)
        os.chown(self.config_file, 0, 0)
        self.config_dir.chmod(0o777)
        with self.assertRaisesRegex(admission.AdmissionError, 'untrusted GPU config directory'):
            self.finish(created, digest)
        self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))

    def test_gpu_cgroup_profile_mismatch_blocks_create(self):
        self.profile['config']['HostConfig']['CgroupParent'] = '/docker/foreign'
        with self.assertRaisesRegex(admission.AdmissionError, 'exact Docker cgroup'):
            self.create()
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_gpu_cgroup_bind_mismatch_blocks_create(self):
        self.profile['config']['HostConfig']['Mounts'][1]['Target'] = '/runtime/foreign'
        with self.assertRaisesRegex(admission.AdmissionError, 'exact delegated cgroup bind'):
            self.create()
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_actual_child_cgroup_mismatch_is_not_accepted(self):
        created = self.create()
        digest = self.config_digest()
        (self.proc_root / '42/cgroup').write_text('0::/docker/foreign/' + self.cid + '\n')
        with self.assertRaisesRegex(admission.AdmissionError, 'start outcome unknown'):
            self.finish(created, digest)
        self.assertEqual('running', self.state)

    def test_cpu_deferred_create_rejected_before_daemon_contact(self):
        with self.assertRaisesRegex(admission.AdmissionError, 'only the GPU'):
            admission.start(self.cpu_profile, fixture_module.pin(self.cpu_profile), {}, self.daemon, defer_start=True)
        self.assertEqual([], self.calls)

    def test_unusable_config_binding_rejected_before_create(self):
        self.profile['config']['Cmd'] = ['/runtime/config/other.json']
        with self.assertRaisesRegex(admission.AdmissionError, 'fixed GPU worker command'):
            self.create()
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_unknown_created_object_blocks_second_stage(self):
        created = self.create()
        digest = self.config_digest()
        self.foreign_status = 'created'
        with self.assertRaises(admission.AdmissionError):
            self.finish(created, digest)
        self.assertEqual('created', self.state)
        self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))

    def test_unknown_running_object_blocks_create_before_post(self):
        self.foreign_status = 'running'
        with self.assertRaises(admission.AdmissionError):
            self.create()
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_unknown_create_outcome_is_not_retried(self):
        self.fail_create = True
        with self.assertRaisesRegex(admission.AdmissionError, 'create outcome unknown'):
            self.create()
        with self.assertRaises(admission.AdmissionError):
            self.create()
        self.assertEqual(1, sum(path.startswith('/containers/create?') for _, path, _ in self.calls))

    def test_missing_or_changed_prepared_identity_blocks_start(self):
        created = self.create()
        digest = self.config_digest()
        wrong = dict(created, id='a' * 64)
        with self.assertRaises(admission.AdmissionError):
            self.finish(wrong, digest)
        self.state = 'absent'
        with self.assertRaises(admission.AdmissionError):
            self.finish(created, digest)
        self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))

    def test_config_digest_and_symlink_are_checked_before_start(self):
        created = self.create()
        digest = self.config_digest()
        with self.assertRaisesRegex(admission.AdmissionError, 'config identity or SHA changed'):
            self.finish(created, '0' * 64)
        self.config_file.unlink()
        (self.config_dir / 'other.json').write_bytes(b'content')
        self.config_file.symlink_to(self.config_dir / 'other.json')
        with self.assertRaisesRegex(admission.AdmissionError, 'config unavailable'):
            self.finish(created, digest)
        self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))

    def test_device_receipt_and_port_are_rechecked_before_start(self):
        created = self.create()
        digest = self.config_digest()
        with self.assertRaisesRegex(admission.AdmissionError, 'GPU device changed'):
            self.finish(dict(created, gpuDevice=[1, 2, 4]), digest)
        with mock.patch.object(admission, '_ports_available', side_effect=OSError('occupied')):
            with self.assertRaises(OSError):
                self.finish(created, digest)
        self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))

    def test_failed_start_result_retains_exact_object_and_does_not_retry(self):
        created = self.create()
        digest = self.config_digest()
        self.fail_start = True
        with self.assertRaisesRegex(admission.AdmissionError, 'start outcome unknown'):
            self.finish(created, digest)
        self.assertEqual('running', self.state)
        self.assertEqual(1, sum(path.endswith('/start') for _, path, _ in self.calls))

    def test_config_mutation_during_start_is_not_accepted(self):
        created = self.create()
        digest = self.config_digest()
        self.mutate_after_start = True
        with self.assertRaisesRegex(admission.AdmissionError, 'start outcome unknown'):
            self.finish(created, digest)
        self.assertEqual('running', self.state)


if __name__ == '__main__':
    unittest.main()
