"""Small Docker-boundary fixtures; never contact a real daemon."""
from contextlib import contextmanager, nullcontext
import copy
import hashlib
import json
import os
from pathlib import Path
import select
import shutil
import stat
import tempfile
import threading
from types import SimpleNamespace
from unittest import mock
import unittest

import container_admission as admission


def pin(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


class AdmissionTest(unittest.TestCase):
    def setUp(self):
        self.cid = 'b' * 64
        self.image = 'sha256:' + 'a' * 64
        self.profile = {
            'role': 'qdrant', 'name': 'consense-qdrant-local01', 'ports': [28683],
            'config': {
                'Image': self.image, 'User': 'consense:consense', 'Entrypoint': ['/bin/sh'],
                'Cmd': ['/runtime/release/entry.sh'], 'Env': [],
                'Labels': {'consense.role': 'qdrant', 'consense.deployment': 'r1-20261029'},
                'HostConfig': {'NetworkMode': 'host', 'ReadonlyRootfs': True, 'Privileged': False,
                               'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'],
                               'Mounts': [], 'Memory': 268435456, 'NanoCpus': 1000000000, 'PidsLimit': 128},
            },
        }
        self.daemon = {'id': 'fixture-daemon', 'socketDevice': 1, 'socketInode': 2,
                       'pid': 3, 'startTicks': 4, 'uid': 0, 'gid': 0}
        self.calls = []

    def inspect(self, status, profile=None, cid=None):
        profile = self.profile if profile is None else profile
        config = copy.deepcopy(profile['config'])
        host = config.pop('HostConfig')
        host.update(CapAdd=None, Binds=None, DeviceRequests=None, DeviceCgroupRules=None, PidMode='')
        host.setdefault('Devices', None)
        return {'Id': cid or self.cid, 'Name': '/' + profile['name'], 'Image': profile['config']['Image'],
                'Config': config, 'HostConfig': host, 'Mounts': [],
                'State': {'Status': status, 'Running': status == 'running',
                          'Paused': False, 'Restarting': False, 'Dead': False,
                          'Pid': 42 if status == 'running' else 0}}

    def docker(self, method, path, expected, payload=None):
        self.calls.append((method, path, copy.deepcopy(payload)))
        if path == '/info':
            return {'ID': self.daemon['id'], 'DockerRootDir': '/srv/consense-docker/data'}
        if path == '/containers/json?all=1':
            return [] if not any(p.endswith('/start') for _, p, _ in self.calls) else [{'Id': self.cid}]
        if path.startswith('/containers/create?'):
            return {'Id': self.cid}
        if path == '/containers/' + self.cid + '/start':
            return None
        if path == '/containers/' + self.cid + '/json':
            return self.inspect('running' if any(p.endswith('/start') for _, p, _ in self.calls) else 'created')
        raise AssertionError(path)

    def test_normal_start_is_locked_and_returns_actual_identity(self):
        with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=self.docker), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            result = admission.start(self.profile, pin(self.profile), {}, self.daemon)
        self.assertEqual({'role': 'qdrant', 'id': self.cid, 'pid': 42, 'status': 'running'}, result)
        self.assertEqual(1, sum(path.startswith('/containers/create?') for _, path, _ in self.calls))
        self.assertEqual(1, sum(path.endswith('/start') for _, path, _ in self.calls))

    def test_pid_limit_accepts_docker_unlimited_and_rejects_invalid_values(self):
        for value in (0, 128, -1, True, '0'):
            profile = copy.deepcopy(self.profile)
            profile['config']['HostConfig']['PidsLimit'] = value
            with self.subTest(pids=value):
                if type(value) is int and value >= 0:
                    admission._profile(profile, pin(profile))
                else:
                    with self.assertRaises(admission.AdmissionError):
                        admission._profile(profile, pin(profile))

    def test_inspect_accepts_null_for_explicit_unlimited_pids(self):
        profile = copy.deepcopy(self.profile)
        profile['config']['HostConfig']['PidsLimit'] = 0
        result = self.inspect('created', profile)
        result['HostConfig']['PidsLimit'] = None
        admission._inspect(result, profile, self.cid, False)
        result['HostConfig']['PidsLimit'] = 128
        with self.assertRaises(admission.AdmissionError):
            admission._inspect(result, profile, self.cid, False)

    def reject(self, profile=None, daemon=None, known=None, docker=None, ports=None):
        profile = self.profile if profile is None else profile
        daemon = self.daemon if daemon is None else daemon
        known = {} if known is None else known
        with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=docker or self.docker), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', side_effect=ports):
            with self.assertRaises((admission.AdmissionError, OSError)):
                admission.start(profile, pin(profile), known, daemon)

    def test_missing_daemon_identity_and_wrong_daemon_fail_before_create(self):
        self.reject(daemon={})
        self.assertEqual([], self.calls)
        def wrong(method, path, expected, payload=None):
            result = self.docker(method, path, expected, payload)
            if path == '/info':
                result['DockerRootDir'] = '/foreign'
            return result
        self.reject(docker=wrong)
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_unknown_container_fails_before_create(self):
        def foreign(method, path, expected, payload=None):
            if path == '/containers/json?all=1':
                self.calls.append((method, path, payload))
                return [{'Id': 'c' * 64}]
            if path == '/containers/' + 'c' * 64 + '/json':
                self.calls.append((method, path, payload))
                return self.inspect('running', cid='c' * 64)
            return self.docker(method, path, expected, payload)
        self.reject(docker=foreign)
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_exited_diagnostics_do_not_use_running_slots(self):
        diagnostics = [format(number, '064x') for number in range(10, 17)]
        def retained(method, path, expected, payload=None):
            if path == '/containers/json?all=1':
                return [{'Id': cid} for cid in diagnostics] + self.docker(method, path, expected, payload)
            for cid in diagnostics:
                if path == '/containers/' + cid + '/json':
                    self.calls.append((method, path, payload))
                    return self.inspect('exited', cid=cid)
            return self.docker(method, path, expected, payload)
        with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=retained), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            result = admission.start(self.profile, pin(self.profile), {}, self.daemon)
        self.assertEqual('running', result['status'])
        self.assertEqual(2, sum(path == '/containers/json?all=1' for _, path, _ in self.calls))
        self.assertEqual(14, sum(path == '/containers/' + cid + '/json'
                                 for _, path, _ in self.calls for cid in diagnostics))
        self.assertFalse(any(method == 'DELETE' for method, _, _ in self.calls))

    def test_unconfirmed_stopped_diagnostic_fails_before_create(self):
        cid = 'c' * 64
        for status, pid in (('created', 0), ('exited', 42)):
            with self.subTest(status=status, pid=pid):
                self.calls.clear()
                def retained(method, path, expected, payload=None):
                    if path == '/containers/json?all=1':
                        self.calls.append((method, path, payload))
                        return [{'Id': cid}]
                    if path == '/containers/' + cid + '/json':
                        self.calls.append((method, path, payload))
                        result = self.inspect(status, cid=cid)
                        result['State']['Pid'] = pid
                        return result
                    return self.docker(method, path, expected, payload)
                self.reject(docker=retained)
                self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_existing_exited_container_blocks_without_cleanup(self):
        existing = copy.deepcopy(self.profile)
        existing.update(role='h2', name='consense-h2-local01', ports=[28682])
        existing['config']['Labels']['consense.role'] = 'h2'
        cid = 'c' * 64
        known = {'h2': {'id': cid, 'profile': existing, 'profileSha256': pin(existing)}}
        def exited(method, path, expected, payload=None):
            if path == '/containers/json?all=1':
                self.calls.append((method, path, payload))
                return [{'Id': cid}]
            if path == '/containers/' + cid + '/json':
                self.calls.append((method, path, payload))
                return self.inspect('exited', existing, cid)
            return self.docker(method, path, expected, payload)
        self.reject(known=known, docker=exited)
        self.assertFalse(any(method in ('POST', 'DELETE') for method, _, _ in self.calls))

    def test_five_registered_running_roles_block_an_extra_container(self):
        known, ids = {}, []
        for number, role in enumerate(('app', 'source', 'h2', 'qdrant', 'gpu'), 1):
            profile = copy.deepcopy(self.profile)
            profile.update(role=role, name='consense-' + role + '-local01',
                           ports=[] if role == 'gpu' else [min(admission.PORTS[role])])
            profile['config']['Labels']['consense.role'] = role
            if role == 'app':
                profile['appBinding'] = {'root': '/fixture/release', 'manifest': '/fixture/manifest.json',
                                         'manifestSha256': 'a' * 64, 'entry': 'entry.sh', 'jar': 'app.jar',
                                         'web': 'web', 'appFiles': 'app-files'}
            if role == 'gpu':
                profile['config']['HostConfig']['NetworkMode'] = 'none'
            cid = format(number, '064x')
            known[role] = {'id': cid, 'profile': profile, 'profileSha256': pin(profile)}
            ids.append(cid)
        def five(method, path, expected, payload=None):
            if path == '/containers/json?all=1':
                self.calls.append((method, path, payload))
                return [{'Id': cid} for cid in ids]
            for row in known.values():
                if path == '/containers/' + row['id'] + '/json':
                    self.calls.append((method, path, payload))
                    return self.inspect('running', row['profile'], row['id'])
            return self.docker(method, path, expected, payload)
        self.reject(known=known, docker=five)
        self.assertFalse(any(method in ('POST', 'DELETE') for method, _, _ in self.calls))

    def test_unsafe_requested_profile_and_occupied_port_fail_before_create(self):
        bad = copy.deepcopy(self.profile)
        bad['config']['HostConfig']['Privileged'] = True
        self.reject(profile=bad)
        self.assertEqual([], self.calls)
        self.reject(ports=OSError('occupied'))
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_verified_profile_cannot_change_before_create(self):
        def change_after_pin(method, path, expected, payload=None):
            if path == '/info':
                self.profile['config']['HostConfig']['Privileged'] = True
            if path.startswith('/containers/create?'):
                self.calls.append((method, path, copy.deepcopy(payload)))
                raise admission.AdmissionError('stop after captured create')
            return self.docker(method, path, expected, payload)
        self.reject(docker=change_after_pin)
        create = next(payload for _, path, payload in self.calls if path.startswith('/containers/create?'))
        self.assertFalse(create['HostConfig']['Privileged'])

    def test_changed_created_security_never_starts_or_cleans_up(self):
        def altered(method, path, expected, payload=None):
            result = self.docker(method, path, expected, payload)
            if path.endswith('/json') and path != '/containers/json?all=1':
                result['HostConfig']['Privileged'] = True
            return result
        self.reject(docker=altered)
        self.assertEqual(1, sum(path.startswith('/containers/create?') for _, path, _ in self.calls))
        self.assertFalse(any(path.endswith('/start') or method == 'DELETE' for method, path, _ in self.calls))

    def test_missing_inspect_security_information_never_starts(self):
        def incomplete(method, path, expected, payload=None):
            result = self.docker(method, path, expected, payload)
            if path.endswith('/json') and path != '/containers/json?all=1':
                result['HostConfig'].pop('CapAdd')
            return result
        self.reject(docker=incomplete)
        self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))

    def test_inspect_security_or_mount_mismatch_never_starts(self):
        changes = (
            ('user', lambda row: row['Config'].__setitem__('User', 'root')),
            ('capabilities', lambda row: row['HostConfig'].__setitem__('CapDrop', [])),
            ('no-new-privileges', lambda row: row['HostConfig'].__setitem__('SecurityOpt', [])),
            ('rootfs', lambda row: row['HostConfig'].__setitem__('ReadonlyRootfs', False)),
            ('socket', lambda row: row['Mounts'].append({'Type': 'bind', 'Source': '/run/consense-docker/docker.sock',
                                                        'Destination': '/socket', 'RW': False})),
        )
        for name, change in changes:
            with self.subTest(name=name):
                self.calls.clear()
                def altered(method, path, expected, payload=None):
                    result = self.docker(method, path, expected, payload)
                    if path.endswith('/json') and path != '/containers/json?all=1':
                        change(result)
                    return result
                self.reject(docker=altered)
                self.assertFalse(any(path.endswith('/start') or method == 'DELETE' for method, path, _ in self.calls))

    def test_docker_inspect_omits_false_readonly_for_writable_bind(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, 'source')
            os.mkdir(source)
            target = '/runtime/data'
            self.profile['config']['HostConfig']['Mounts'] = [
                {'Type': 'bind', 'Source': source, 'Target': target, 'ReadOnly': False}]
            actual_rw = True
            def real_shape(method, path, expected, payload=None):
                result = self.docker(method, path, expected, payload)
                if path.endswith('/json') and path != '/containers/json?all=1':
                    result['HostConfig']['Mounts'][0].pop('ReadOnly')
                    result['Mounts'] = [{'Type': 'bind', 'Source': source, 'Destination': target, 'RW': actual_rw}]
                return result
            with mock.patch.object(admission, 'SOCKET', os.path.join(directory, 'daemon', 'docker.sock')), \
                 mock.patch.object(admission, '_lock', return_value=nullcontext()), \
                 mock.patch.object(admission, '_request', side_effect=real_shape), \
                 mock.patch.object(admission, '_process_identity', return_value=None), \
                 mock.patch.object(admission, '_ports_available', return_value=None):
                result = admission.start(self.profile, pin(self.profile), {}, self.daemon)
                self.assertEqual('running', result['status'])
                self.calls.clear()
                actual_rw = False
                with self.assertRaises(admission.AdmissionError):
                    admission.start(self.profile, pin(self.profile), {}, self.daemon)
                self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))

    def test_start_failure_is_not_retried_or_cleaned(self):
        def failed(method, path, expected, payload=None):
            if path.endswith('/start'):
                self.calls.append((method, path, payload))
                raise admission.AdmissionError('start failed')
            return self.docker(method, path, expected, payload)
        with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=failed), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            with self.assertRaisesRegex(admission.AdmissionError, self.cid + ' retained'):
                admission.start(self.profile, pin(self.profile), {}, self.daemon)
        self.assertEqual(1, sum(path.endswith('/start') for _, path, _ in self.calls))
        self.assertFalse(any(method == 'DELETE' for method, _, _ in self.calls))

    def test_create_timeout_is_unknown_and_never_retried_or_cleaned(self):
        def timed_out(method, path, expected, payload=None):
            if path.startswith('/containers/create?'):
                self.calls.append((method, path, payload))
                raise TimeoutError('timeout')
            return self.docker(method, path, expected, payload)
        with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=timed_out), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            with self.assertRaisesRegex(admission.AdmissionError, 'create outcome unknown'):
                admission.start(self.profile, pin(self.profile), {}, self.daemon)
        self.assertEqual(1, sum(path.startswith('/containers/create?') for _, path, _ in self.calls))
        self.assertFalse(any(method == 'DELETE' for method, _, _ in self.calls))

    def test_gpu_private_network_can_have_no_tcp_port(self):
        self.profile['role'] = 'gpu'
        self.profile['name'] = 'consense-gpu-local01'
        self.profile['ports'] = []
        self.profile['config']['Labels']['consense.role'] = 'gpu'
        self.profile['config']['HostConfig']['NetworkMode'] = 'none'
        with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=self.docker), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            self.assertEqual('gpu', admission.start(self.profile, pin(self.profile), {}, self.daemon)['role'])

    def test_gpu_host_network_requires_registered_entry_port(self):
        self.profile.update(role='gpu', name='consense-gpu-local01', ports=[])
        self.profile['config']['Labels']['consense.role'] = 'gpu'
        self.reject()
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_gpu_host_profile_accepts_only_exact_device_and_checks_inspect(self):
        self.profile.update(role='gpu', name='consense-gpu-local01', ports=[28684, 28771, 28772])
        self.profile['config']['Labels']['consense.role'] = 'gpu'
        host = self.profile['config']['HostConfig']
        host['Devices'] = [{'PathOnHost': '/dev/dxg', 'PathInContainer': '/dev/dxg',
                            'CgroupPermissions': 'rw'}]
        device = SimpleNamespace(st_mode=stat.S_IFCHR | 0o666, st_dev=1, st_ino=2, st_rdev=3)
        with mock.patch.object(admission.os, 'lstat', return_value=device), \
             mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=self.docker), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            self.assertEqual('gpu', admission.start(self.profile, pin(self.profile), {}, self.daemon)['role'])
        for change in (
            lambda d: d[0].__setitem__('CgroupPermissions', 'rwm'),
            lambda d: d[0].__setitem__('PathOnHost', '/dev/null'),
            lambda d: d[0].__setitem__('PathInContainer', '/dev/other'),
            lambda d: d.append(copy.deepcopy(d[0])),
        ):
            with self.subTest(change=change):
                bad = copy.deepcopy(self.profile)
                change(bad['config']['HostConfig']['Devices'])
                with self.assertRaises(admission.AdmissionError):
                    admission._profile(bad, pin(bad))
        row = self.inspect('created')
        row['HostConfig']['Devices'][0]['CgroupPermissions'] = 'rwm'
        with self.assertRaises(admission.AdmissionError):
            admission._inspect(row, self.profile, self.cid, False)
        row = self.inspect('created')
        row['HostConfig']['NetworkMode'] = 'none'
        with self.assertRaises(admission.AdmissionError):
            admission._inspect(row, self.profile, self.cid, False)
        row = self.inspect('created')
        row['HostConfig']['DeviceCgroupRules'] = ['c *:* rwm']
        with self.assertRaises(admission.AdmissionError):
            admission._inspect(row, self.profile, self.cid, False)
        with mock.patch.object(admission.os, 'lstat', return_value=SimpleNamespace(st_mode=stat.S_IFREG | 0o666)):
            with self.assertRaises(admission.AdmissionError):
                admission._profile(self.profile, pin(self.profile))

    def test_device_is_denied_for_cpu_and_changed_gpu_device_fails_before_start(self):
        mapping = [{'PathOnHost': '/dev/dxg', 'PathInContainer': '/dev/dxg', 'CgroupPermissions': 'rw'}]
        self.profile['config']['HostConfig']['Devices'] = mapping
        with self.assertRaises(admission.AdmissionError):
            admission._profile(self.profile, pin(self.profile))
        self.profile.update(role='gpu', name='consense-gpu-local01', ports=[28684])
        self.profile['config']['Labels']['consense.role'] = 'gpu'
        first = SimpleNamespace(st_mode=stat.S_IFCHR | 0o666, st_dev=1, st_ino=2, st_rdev=3)
        changed = SimpleNamespace(st_mode=stat.S_IFCHR | 0o666, st_dev=1, st_ino=9, st_rdev=3)
        with mock.patch.object(admission.os, 'lstat', side_effect=[first, first, first, changed]), \
             mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=self.docker), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
                admission.start(self.profile, pin(self.profile), {}, self.daemon)
        self.assertFalse(any(path.endswith('/start') for _, path, _ in self.calls))

    def test_readonly_mount_cannot_have_writable_child_but_readonly_child_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            first, second = (os.path.join(directory, name) for name in ('source1', 'source2'))
            os.mkdir(first)
            os.mkdir(second)
            root = '/runtime/release'
            child = '/runtime/release/web'
            mounts = [{'Type': 'bind', 'Source': first, 'Target': root, 'ReadOnly': True},
                      {'Type': 'bind', 'Source': second, 'Target': child, 'ReadOnly': False}]
            self.profile['config']['HostConfig']['Mounts'] = mounts
            def mounted(method, path, expected, payload=None):
                result = self.docker(method, path, expected, payload)
                if path.endswith('/json') and path != '/containers/json?all=1':
                    result['Mounts'] = [{'Type': m['Type'], 'Source': m['Source'],
                                         'Destination': m['Target'], 'RW': not m['ReadOnly']} for m in mounts]
                return result
            with mock.patch.object(admission, 'SOCKET', os.path.join(directory, 'daemon', 'docker.sock')):
                self.reject(docker=mounted)
                self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))
                self.calls.clear()
                mounts[1]['ReadOnly'] = True
                with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
                     mock.patch.object(admission, '_request', side_effect=mounted), \
                     mock.patch.object(admission, '_process_identity', return_value=None), \
                     mock.patch.object(admission, '_ports_available', return_value=None):
                    self.assertEqual('running', admission.start(self.profile, pin(self.profile), {}, self.daemon)['status'])

    @unittest.skipUnless(os.name == 'posix', 'Linux flock required')
    def test_lock_yields_the_checked_fd_and_closes_it(self):
        import fcntl
        directory = SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_uid=0)
        file_info = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0,
                                    st_nlink=1, st_dev=3, st_ino=4)
        def lstat(path):
            return file_info if str(path) == '/safe/lock' else directory
        with mock.patch.object(admission, 'LOCK', '/safe/lock'), \
             mock.patch.object(Path, 'lstat', autospec=True, side_effect=lstat), \
             mock.patch.object(os, 'open', return_value=91) as opened, \
             mock.patch.object(os, 'fstat', return_value=file_info), \
             mock.patch.object(os, 'lstat', return_value=file_info), \
             mock.patch.object(os, 'close') as closed, \
             mock.patch.object(fcntl, 'flock') as flock:
            with admission._lock() as fd:
                self.assertEqual(91, fd)
                flock.assert_called_once_with(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertTrue(opened.call_args.args[1] & os.O_CLOEXEC)
                closed.assert_not_called()
            closed.assert_called_once_with(91)

    @unittest.skipUnless(os.name == 'posix', 'Linux flock required')
    def test_real_lock_excludes_concurrent_thread(self):
        import fcntl
        with tempfile.TemporaryDirectory() as directory:
            lock = os.path.join(directory, 'lock')
            errors = []
            with mock.patch.object(admission, 'LOCK', lock):
                with admission._lock() as fd:
                    self.assertEqual(os.fstat(fd).st_ino, os.stat(lock).st_ino)
                    self.assertTrue(fcntl.fcntl(fd, fcntl.F_GETFD) & fcntl.FD_CLOEXEC)
                    def second():
                        try:
                            with admission._lock():
                                errors.append('acquired')
                        except admission.AdmissionError:
                            errors.append('busy')
                    thread = threading.Thread(target=second)
                    thread.start()
                    thread.join(2)
                    self.assertFalse(thread.is_alive())
                    self.assertEqual(['busy'], errors)

    @unittest.skipUnless(hasattr(os, 'fork'), 'Linux process flock required')
    def test_real_lock_excludes_another_process(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = os.path.join(directory, 'lock')
            with mock.patch.object(admission, 'LOCK', lock), admission._lock():
                read_fd, write_fd = os.pipe()
                pid = os.fork()
                if pid == 0:
                    os.close(read_fd)
                    try:
                        with admission._lock():
                            os.write(write_fd, b'acquired')
                    except admission.AdmissionError:
                        os.write(write_fd, b'busy')
                    finally:
                        os.close(write_fd)
                        os._exit(0)
                os.close(write_fd)
                try:
                    self.assertTrue(select.select([read_fd], [], [], 2)[0])
                    self.assertEqual(b'busy', os.read(read_fd, 32))
                finally:
                    os.close(read_fd)
                    os.waitpid(pid, 0)


class AppBindingTest(unittest.TestCase):
    def setUp(self):
        AdmissionTest.setUp(self)
        self.profile.update(role='app', name='consense-app-local01', ports=[28680])
        self.profile['config']['Labels']['consense.role'] = 'app'
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'release'
        (self.root / 'web').mkdir(parents=True)
        (self.root / 'app-files').mkdir()
        (self.root / 'entry.sh').write_bytes(b'entry')
        (self.root / 'app.jar').write_bytes(b'jar')
        (self.root / 'web/index.html').write_bytes(b'web')
        (self.root / 'app-files/web-files').write_bytes(b'web/index.html\n')
        sums = (hashlib.sha256(b'jar').hexdigest() + '  app.jar\n'
                + hashlib.sha256(b'web').hexdigest() + '  web/index.html\n')
        (self.root / 'app-files/sha256sums').write_bytes(sums.encode())
        self.manifest = self.base / 'manifest.json'
        self.write_manifest()
        self.profile['appBinding'] = {
            'root': str(self.root.resolve()), 'manifest': str(self.manifest.resolve()),
            'manifestSha256': hashlib.sha256(self.manifest.read_bytes()).hexdigest(),
            'entry': 'entry.sh', 'jar': 'app.jar', 'web': 'web', 'appFiles': 'app-files',
        }
        self.profile['config']['Cmd'] = ['/runtime/release/entry.sh']
        self.profile['config']['Env'] = [
            'APP_ARTIFACT_ROOT=/runtime/release', 'APP_JAR=app.jar', 'APP_WEB_DIRECTORY=web',
            'APP_FILE_MANIFEST=/runtime/release/app-files',
        ]
        self.profile['config']['HostConfig']['Mounts'] = [
            {'Type': 'bind', 'Source': str(self.root.resolve()), 'Target': '/runtime/release', 'ReadOnly': True}]
        usr = self.base / 'usr'
        java = self.base / 'java'
        (usr / 'bin').mkdir(parents=True)
        (java / 'bin').mkdir(parents=True)
        commands = {}
        for name in ('java', 'find', 'sort', 'cmp', 'mktemp', 'cat', 'cut', 'sha256sum', 'grep', 'rm'):
            location = java / 'bin' / name if name == 'java' else usr / 'bin' / name
            content = ('fixture-' + name).encode()
            location.write_bytes(content)
            location.chmod(0o755)
            commands[name] = {'path': ('/runtime/java/bin/' if name == 'java' else '/usr/bin/') + name,
                              'sha256': hashlib.sha256(content).hexdigest()}
        self.exec_manifest = self.base / 'exec-manifest.json'
        self.exec_manifest.write_text(json.dumps({'commands': commands}), encoding='utf-8')
        self.profile['appBinding']['execution'] = {
            'path': ['/runtime/java/bin', '/usr/bin'], 'manifest': str(self.exec_manifest.resolve()),
            'manifestSha256': hashlib.sha256(self.exec_manifest.read_bytes()).hexdigest(),
            'scanBudget': {'maxEntries': 100, 'maxSeconds': 2},
        }
        self.profile['config']['Env'].append('PATH=/runtime/java/bin:/usr/bin')
        self.profile['config']['HostConfig']['Mounts'].extend([
            {'Type': 'bind', 'Source': str(java.resolve()), 'Target': '/runtime/java', 'ReadOnly': True},
            {'Type': 'bind', 'Source': str(usr.resolve()), 'Target': '/usr', 'ReadOnly': True},
        ])
        socket_patch = mock.patch.object(admission, 'SOCKET', str(self.base / 'daemon' / 'docker.sock'))
        socket_patch.start()
        self.addCleanup(socket_patch.stop)
        self.mount_aliases = {}
        table_patch = mock.patch.object(admission, '_mount_table', return_value={'namespace': (1, 1), 'root': (2, 2), 'raw': b'fixture'})
        table_patch.start()
        self.addCleanup(table_patch.stop)
        def mount_ref(source, table):
            actual = Path(self.mount_aliases.get(source, source))
            info = actual.stat()
            return {'device': str(info.st_dev), 'inode': info.st_ino,
                    'backing': '/fixture/' + actual.as_posix().replace(':', '_').lstrip('/')}
        ref_patch = mock.patch.object(admission, '_mount_ref', side_effect=mount_ref)
        ref_patch.start()
        self.addCleanup(ref_patch.stop)

    def write_manifest(self):
        files = [{'path': p.relative_to(self.root).as_posix(), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                 for p in sorted(self.root.rglob('*')) if p.is_file()]
        self.manifest.write_text(json.dumps({'files': files}), encoding='utf-8')

    def inspect(self, status, profile=None, cid=None):
        profile = self.profile if profile is None else profile
        result = AdmissionTest.inspect(self, status, profile, cid)
        result['Mounts'] = [{'Type': m['Type'], 'Source': m['Source'], 'Destination': m['Target'],
                             'RW': not m['ReadOnly']} for m in profile['config']['HostConfig']['Mounts']]
        return result

    docker = AdmissionTest.docker
    reject = AdmissionTest.reject

    def test_app_binding_uses_exact_verified_release_mount_and_entry(self):
        with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=self.docker), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            result = admission.start(self.profile, pin(self.profile), {}, self.daemon)
        self.assertEqual('running', result['status'])
        config = next(body for _, path, body in self.calls if path.startswith('/containers/create?'))
        self.assertEqual(['/runtime/release/entry.sh'], config['Cmd'])
        self.assertEqual(str(self.root.resolve()), config['HostConfig']['Mounts'][0]['Source'])
        self.assertTrue(config['HostConfig']['Mounts'][0]['ReadOnly'])

    def test_writable_data_path_cannot_precede_the_verified_runtime(self):
        self.profile['config']['Env'][-1] = 'PATH=/runtime/data:/runtime/java/bin:/usr/bin'
        self.assert_no_create()

    def test_release_tree_cannot_be_remounted_as_runtime_path(self):
        self.profile['config']['HostConfig']['Mounts'].append(
            {'Type': 'bind', 'Source': str(self.root.resolve()), 'Target': '/runtime/other', 'ReadOnly': True})
        self.profile['appBinding']['execution']['path'].insert(0, '/runtime/other')
        self.profile['config']['Env'][-1] = 'PATH=/runtime/other:/runtime/java/bin:/usr/bin'
        self.assert_no_create()

    def test_writable_source_cannot_overlap_release_or_runtime(self):
        mounts = self.profile['config']['HostConfig']['Mounts']
        for source in (self.root, self.root / 'web', self.base, self.base / 'usr', self.base / 'usr/bin'):
            with self.subTest(source=str(source)):
                mounts.append({'Type': 'bind', 'Source': str(source.resolve()), 'Target': '/runtime/data', 'ReadOnly': False})
                self.assert_no_create()
                mounts.pop()

    def test_writable_bind_alias_of_protected_subtree_is_rejected(self):
        alias = self.base / 'bind-alias'
        alias.mkdir()
        self.mount_aliases[str(alias.resolve())] = str((self.root / 'web').resolve())
        self.profile['config']['HostConfig']['Mounts'].append(
            {'Type': 'bind', 'Source': str(alias.resolve()), 'Target': '/runtime/data', 'ReadOnly': False})
        self.assert_no_create()

    def test_missing_mount_visibility_fails_before_create(self):
        with mock.patch.object(admission, '_mount_table', side_effect=admission.AdmissionError('unavailable')):
            self.assert_no_create()

    def test_changed_mount_visibility_fails_before_create(self):
        before = {'namespace': (1, 1), 'root': (2, 2), 'raw': b'first'}
        after = {'namespace': (1, 1), 'root': (2, 2), 'raw': b'changed'}
        with mock.patch.object(admission, '_mount_table', side_effect=[before, after]):
            self.assert_no_create()

    def test_mountinfo_backing_detects_alias_without_scanning_trees(self):
        from types import SimpleNamespace
        raw = (b'10 1 8:1 /source /release rw - ext4 /dev/test rw\n'
               b'11 1 8:1 /source/web /alias rw - ext4 /dev/test rw\n')
        records = admission._parse_mountinfo(raw)
        with mock.patch.object(admission.os, 'major', create=True, return_value=8), \
             mock.patch.object(admission.os, 'minor', create=True, return_value=1):
            protected = admission._mount_backing('/release', SimpleNamespace(st_dev=1, st_ino=10), records[10], records)
            alias = admission._mount_backing('/alias', SimpleNamespace(st_dev=1, st_ino=11), records[11], records)
        table = {'namespace': (1, 1), 'root': (2, 2), 'raw': raw}
        with mock.patch.object(admission, '_mount_table', return_value=table), \
             mock.patch.object(admission, '_mount_ref', side_effect=lambda source, _: protected if source == '/release' else alias):
            with self.assertRaises(admission.AdmissionError):
                admission._verify_mount_isolation(['/release'], ['/alias'], self.daemon)

    def test_unknown_backing_and_nested_mount_are_rejected(self):
        from types import SimpleNamespace
        raw = (b'10 1 8:1 /source /release rw - ext4 /dev/test rw\n'
               b'11 10 8:2 / /release/nested rw - ext4 /dev/other rw\n')
        records = admission._parse_mountinfo(raw)
        with mock.patch.object(admission.os, 'major', create=True, return_value=8), \
             mock.patch.object(admission.os, 'minor', create=True, return_value=1):
            with self.assertRaises(admission.AdmissionError):
                admission._mount_backing('/release', SimpleNamespace(st_dev=1, st_ino=10), records[10], records)
            records.pop(11)
            records[10]['filesystem'] = 'overlay'
            with self.assertRaises(admission.AdmissionError):
                admission._mount_backing('/release', SimpleNamespace(st_dev=1, st_ino=10), records[10], records)

    def test_hardlink_alias_of_release_or_runtime_executable_is_rejected(self):
        for source in (self.root / 'app.jar', self.base / 'usr/bin/find', self.manifest, self.exec_manifest):
            with self.subTest(source=str(source)):
                alias = self.base / 'outside-link'
                os.link(source, alias)
                try:
                    self.assert_no_create()
                finally:
                    alias.unlink()

    def test_noncommand_runtime_hardlink_alias_is_rejected(self):
        library = self.base / 'usr/libfixture.so'
        library.write_bytes(b'library')
        os.link(library, self.base / 'outside-library')
        self.assert_no_create()

    def test_unverified_runtime_symlink_is_rejected(self):
        link = self.base / 'usr/other-link'
        try:
            link.symlink_to(self.base / 'usr/bin/find')
        except OSError as error:
            self.skipTest('symbolic links unavailable: ' + str(error))
        self.assert_no_create()

    def test_runtime_metadata_scan_budget_is_mandatory_and_enforced(self):
        execution = self.profile['appBinding']['execution']
        for budget in ({}, {'maxEntries': 1, 'maxSeconds': 2}, {'maxEntries': 20001, 'maxSeconds': 2},
                       {'maxEntries': 100, 'maxSeconds': 11}):
            with self.subTest(budget=budget):
                execution['scanBudget'] = budget
                self.assert_no_create()

    def test_writable_tree_is_not_scanned(self):
        writable = self.base / 'writable'
        writable.mkdir()
        try:
            (writable / 'unrelated-link').symlink_to(self.base / 'usr/bin/find')
        except OSError as error:
            self.skipTest('symbolic links unavailable: ' + str(error))
        self.profile['config']['HostConfig']['Mounts'].append(
            {'Type': 'bind', 'Source': str(writable.resolve()), 'Target': '/runtime/data', 'ReadOnly': False})
        with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=self.docker), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            self.assertEqual('running', admission.start(self.profile, pin(self.profile), {}, self.daemon)['status'])

    def test_execute_bits_use_owner_then_group_then_other(self):
        from types import SimpleNamespace
        info = SimpleNamespace(st_uid=10001, st_gid=10001, st_mode=0o001)
        self.assertFalse(admission._executable_for_app(info))
        info.st_uid = 0
        self.assertFalse(admission._executable_for_app(info))
        info.st_gid = 0
        self.assertTrue(admission._executable_for_app(info))
        info.st_uid = 10001
        info.st_mode = 0o100
        self.assertTrue(admission._executable_for_app(info))

    def test_legacy_unbound_path_and_java_options_cannot_start(self):
        self.profile['appBinding'].pop('execution')
        self.profile['config']['Env'][-1] = 'PATH=/runtime/data:/usr/bin'
        self.profile['config']['Env'].append('JAVA_TOOL_OPTIONS=-javaagent:/runtime/data/injected.jar')
        self.assert_no_create()

    def test_java_and_dynamic_loader_injection_variables_are_rejected(self):
        for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'LD_PRELOAD', 'LD_AUDIT', 'LD_LIBRARY_PATH'):
            with self.subTest(name=name):
                self.profile['config']['Env'].append(name + '=injected')
                self.assert_no_create()
                self.profile['config']['Env'].pop()

    def test_execution_manifest_and_selected_binary_must_match_pins(self):
        execution = self.profile['appBinding']['execution']
        original = execution['manifestSha256']
        execution['manifestSha256'] = '0' * 64
        self.assert_no_create()
        execution['manifestSha256'] = original
        binary = self.base / 'usr/bin/find'
        binary.write_bytes(b'tampered executable')
        self.assert_no_create()

    def test_earlier_path_entry_cannot_shadow_registered_command(self):
        (self.base / 'java/bin/find').write_bytes(b'unregistered command')
        self.assert_no_create()

    def test_missing_executable_and_writable_runtime_mount_are_rejected(self):
        binary = self.base / 'java/bin/java'
        binary.unlink()
        self.assert_no_create()
        binary.write_bytes(b'fixture-java')
        binary.chmod(0o755)
        self.profile['config']['HostConfig']['Mounts'][2]['ReadOnly'] = False
        self.assert_no_create()

    def test_executable_symlink_is_not_assumed_to_resolve_in_container(self):
        binary = self.base / 'usr/bin/find'
        binary.unlink()
        try:
            binary.symlink_to(self.base / 'usr/bin/grep')
        except OSError as error:
            self.skipTest('symbolic links unavailable: ' + str(error))
        self.assert_no_create()

    def test_execution_manifest_cannot_be_inside_a_runtime_mount(self):
        nested = self.base / 'usr/execution.json'
        nested.write_bytes(self.exec_manifest.read_bytes())
        self.profile['appBinding']['execution']['manifest'] = str(nested.resolve())
        self.assert_no_create()

    def assert_no_create(self):
        self.reject()
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_wrong_manifest_pin_rejects_before_create(self):
        self.profile['appBinding']['manifestSha256'] = '0' * 64
        self.assert_no_create()

    def test_wrong_profile_pin_rejects_before_create(self):
        with mock.patch.object(admission, '_lock', return_value=nullcontext()), \
             mock.patch.object(admission, '_request', side_effect=self.docker):
            with self.assertRaises(admission.AdmissionError):
                admission.start(self.profile, '0' * 64, {}, self.daemon)
        self.assertEqual([], self.calls)

    def test_release_verification_occurs_inside_the_admission_lock(self):
        state = {'locked': False}
        @contextmanager
        def locked():
            state['locked'] = True
            try:
                yield
            finally:
                state['locked'] = False
        original = admission.release_guard.verify
        def verify(*args, **kwargs):
            self.assertTrue(state['locked'])
            return original(*args, **kwargs)
        with mock.patch.object(admission, '_lock', side_effect=locked), \
             mock.patch.object(admission.release_guard, 'verify', side_effect=verify), \
             mock.patch.object(admission, '_request', side_effect=self.docker), \
             mock.patch.object(admission, '_process_identity', return_value=None), \
             mock.patch.object(admission, '_ports_available', return_value=None):
            self.assertEqual('running', admission.start(self.profile, pin(self.profile), {}, self.daemon)['status'])
        self.assertFalse(state['locked'])

    def test_extra_missing_or_tampered_release_rejects_before_create(self):
        extra = self.root / 'extra.jar'
        extra.write_bytes(b'extra')
        self.assert_no_create()
        extra.unlink()
        jar = self.root / 'app.jar'
        jar.unlink()
        self.assert_no_create()
        jar.write_bytes(b'changed')
        self.assert_no_create()

    def test_release_symlink_is_rejected_before_create(self):
        jar = self.root / 'app.jar'
        jar.unlink()
        try:
            jar.symlink_to(self.root / 'entry.sh')
        except OSError as error:
            self.skipTest('symbolic links unavailable: ' + str(error))
        self.assert_no_create()

    def test_manifest_replacement_during_admission_is_rejected(self):
        def replaced(method, path, expected, payload=None):
            result = self.docker(method, path, expected, payload)
            if path == '/containers/json?all=1':
                self.manifest.write_bytes(self.manifest.read_bytes() + b' ')
            return result
        self.reject(docker=replaced)
        self.assertFalse(any(method == 'POST' for method, _, _ in self.calls))

    def test_same_content_at_different_mount_root_is_rejected(self):
        other = self.base / 'different-release'
        shutil.copytree(self.root, other)
        self.profile['config']['HostConfig']['Mounts'][0]['Source'] = str(other.resolve())
        self.assert_no_create()

    def test_entry_env_and_readonly_submount_mismatch_reject_before_create(self):
        self.profile['config']['Cmd'] = ['/runtime/release/not-entry.sh']
        self.assert_no_create()
        self.profile['config']['Cmd'] = ['/runtime/release/entry.sh']
        self.profile['config']['Env'].append('APP_JAR=other.jar')
        self.assert_no_create()
        self.profile['config']['Env'].pop()
        self.profile['config']['Env'][1] = 'APP_JAR=other.jar'
        self.assert_no_create()
        self.profile['config']['Env'][1] = 'APP_JAR=app.jar'
        self.profile['config']['HostConfig']['Mounts'].append(
            {'Type': 'bind', 'Source': str((self.root / 'web').resolve()),
             'Target': '/runtime/release/web', 'ReadOnly': True})
        self.assert_no_create()

    def test_double_slash_mount_alias_cannot_shadow_release(self):
        self.profile['config']['HostConfig']['Mounts'].append(
            {'Type': 'bind', 'Source': str((self.root / 'web').resolve()),
             'Target': '//runtime/release/web', 'ReadOnly': True})
        self.assert_no_create()

    def test_unregistered_entry_jar_and_web_reject_before_create(self):
        binding = self.profile['appBinding']
        for key, invalid in (('entry', 'missing.sh'), ('jar', 'missing.jar'), ('web', 'missing-web')):
            with self.subTest(key=key):
                original = binding[key]
                binding[key] = invalid
                self.assert_no_create()
                binding[key] = original

    def test_pinned_but_wrong_app_file_list_is_rejected(self):
        (self.root / 'app-files/web-files').write_bytes(b'web/index.html\nweb/index.html\n')
        self.write_manifest()
        self.profile['appBinding']['manifestSha256'] = hashlib.sha256(self.manifest.read_bytes()).hexdigest()
        self.assert_no_create()
        (self.root / 'app-files/web-files').write_bytes(b'web/index.html\n')
        (self.root / 'app-files/sha256sums').write_bytes(b'bad checksum  app.jar\n')
        self.write_manifest()
        self.profile['appBinding']['manifestSha256'] = hashlib.sha256(self.manifest.read_bytes()).hexdigest()
        self.assert_no_create()


class NamespaceMountInfoTest(unittest.TestCase):
    def test_mountinfo_decodes_four_valid_escapes(self):
        raw = rb'10 1 0:4 / /mnt/a\040b\011c\012d\134e rw - tmpfs tmpfs rw' + b'\n'
        self.assertEqual('/mnt/a b\tc\nd\\e', admission._parse_mountinfo(raw)[10]['point'])

    def test_tmpfs_backing_maps_normally(self):
        from types import SimpleNamespace
        raw = b'10 1 0:4 /workspace /mnt/tmp rw - tmpfs tmpfs rw\n'
        records = admission._parse_mountinfo(raw)
        with mock.patch.object(admission.os, 'major', create=True, return_value=0), \
             mock.patch.object(admission.os, 'minor', create=True, return_value=4):
            result = admission._mount_backing('/mnt/tmp/cache', SimpleNamespace(st_dev=1, st_ino=10),
                                              records[10], records)
        self.assertEqual({'device': '0:4', 'inode': 10, 'backing': '/workspace/cache'}, result)

    def test_network_namespace_pseudoroot_is_retained(self):
        raw = b'468 555 0:4 net:[4026531833] /run/consense-docker/exec/netns/default rw shared:310 - nsfs nsfs rw\n'
        self.assertEqual({'device': '0:4', 'root': 'net:[4026531833]',
                          'point': '/run/consense-docker/exec/netns/default', 'filesystem': 'nsfs'},
                         admission._parse_mountinfo(raw)[468])

    def test_other_pseudoroots_and_bad_points_fail_closed(self):
        variants = [
            b'1 2 0:4 net:[42] /net rw - ext4 nsfs rw',
            b'1 2 0:4 net:[42] /net rw - nsfs other rw',
            b'1 2 0:4 user:[42] /net rw - nsfs nsfs rw',
            b'1 2 0:4 net:[] /net rw - nsfs nsfs rw',
            b'1 2 0:4 net:[-1] /net rw - nsfs nsfs rw',
            b'1 2 0:4 net:[42]suffix /net rw - nsfs nsfs rw',
            b'1 2 0:4 net:[42] relative rw - nsfs nsfs rw',
            b'1 2 0:4 net:[42] /net/../other rw - nsfs nsfs rw',
            b'1 2 0:4 net:[42] /net//other rw - nsfs nsfs rw',
            b'1 2 0:4 net:[42] /net\\777 rw - nsfs nsfs rw',
            b'1 2 0:4 net:[42] /net rw - nsfs nsfs rw\n1 2 0:4 net:[43] /other rw - nsfs nsfs rw',
        ]
        for raw in variants:
            with self.subTest(raw=raw), self.assertRaises(admission.AdmissionError):
                admission._parse_mountinfo(raw + b'\n')

    def test_namespace_mount_stays_visible_and_cannot_be_mapped(self):
        from types import SimpleNamespace
        raw = (b'10 1 8:1 /source /release rw - ext4 /dev/test rw\n'
               b'11 10 0:4 net:[42] /release/nested rw - nsfs nsfs rw\n')
        records = admission._parse_mountinfo(raw)
        with mock.patch.object(admission.os, 'major', create=True, return_value=8), \
             mock.patch.object(admission.os, 'minor', create=True, return_value=1):
            with self.assertRaisesRegex(admission.AdmissionError, 'nested mount'):
                admission._mount_backing('/release', SimpleNamespace(st_dev=1, st_ino=10), records[10], records)
            with self.assertRaisesRegex(admission.AdmissionError, 'cannot be mapped'):
                admission._mount_backing('/release/nested', SimpleNamespace(st_dev=1, st_ino=11), records[11], records)


if __name__ == '__main__':
    unittest.main()
