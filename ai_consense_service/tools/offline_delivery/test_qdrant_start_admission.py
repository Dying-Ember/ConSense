"""Mocks only; no Docker, Qdrant or real runtime reads."""
from contextlib import contextmanager
import copy
import hashlib
import io
from pathlib import Path
import stat
from types import SimpleNamespace
import unittest
from unittest import mock

import container_admission as admission
import jre_probe_admission as jre
import qdrant_start_admission as qdrant


class QdrantCandidateTest(unittest.TestCase):
    def setUp(self):
        self.profile = qdrant.fixed_profile()
        self.daemon = {'id': 'fixture', 'socketDevice': 1, 'socketInode': 2,
                       'pid': 3, 'startTicks': 4, 'uid': 0, 'gid': 0}
        self.cid = 'b' * 64
        self.calls = []

    @contextmanager
    def lock(self):
        yield

    def request(self, method, path, daemon, payload=None):
        self.calls.append((method, path))
        if path == '/info':
            return {'ID': 'fixture', 'DockerRootDir': admission.DATA_ROOT}
        if path.startswith('/containers/create?'):
            return {'Id': self.cid}
        if path.endswith('/json'):
            return {'State': {'Status': 'running' if any(p.endswith('/start') for _, p in self.calls) else 'created'}}
        if path.endswith('/start'):
            return None
        self.fail((method, path))

    def run_fake(self, listener=True):
        def inspect(row, profile, cid, state):
            return {'StartedAt': '0001-01-01T00:00:00Z', 'FinishedAt': '0001-01-01T00:00:00Z'} if state == 'created' else {
                'Pid': 42, 'StartedAt': '2026-10-29T15:00:00Z'}
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_profile'), mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(qdrant, '_inventory', side_effect=lambda daemon, cid=None: sorted([qdrant.GPU_ID] + ([cid] if cid else []))), \
             mock.patch.object(admission, '_process_identity'), \
             mock.patch.object(admission, '_verify_mount_isolation'), mock.patch.object(jre, '_verify_resource'), \
             mock.patch.object(jre, '_image'), mock.patch.object(jre, '_inspect', side_effect=inspect), \
             mock.patch.object(jre, '_check_inventory'), mock.patch.object(jre, '_starttime', return_value=99), \
             mock.patch.object(qdrant, '_scope'), mock.patch.object(qdrant, '_directories', return_value={'fixed': 1}), \
             mock.patch.object(qdrant, '_hash_files', return_value={'bytesRead': qdrant.MAX_READ, 'identities': {},
                                                                    'fullClosureVerified': False}), \
             mock.patch.object(qdrant, '_recheck_files'), mock.patch.object(qdrant, '_ports_free'), \
             mock.patch.object(qdrant, '_network', return_value=(1, 2)), mock.patch.object(qdrant, '_process_binary'), \
             mock.patch.object(qdrant, '_listener', return_value=listener), \
             mock.patch.object(qdrant.time, 'sleep'):
            return qdrant.run(self.profile, admission._pin(self.profile), {}, self.daemon, timeout=1)

    def test_wrong_scope_stops_before_engine_and_read(self):
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(qdrant, '_scope', side_effect=admission.AdmissionError('scope')), \
             mock.patch.object(admission, '_request') as request, mock.patch.object(qdrant, '_hash_files') as read:
            with self.assertRaisesRegex(admission.AdmissionError, 'scope'):
                qdrant.run(self.profile, admission._pin(self.profile), {}, self.daemon)
            request.assert_not_called()
            read.assert_not_called()

    def test_wrong_network_stops_before_engine_and_read(self):
        with mock.patch.object(admission, '_lock', side_effect=self.lock), mock.patch.object(qdrant, '_scope'), \
             mock.patch.object(jre, '_verify_resource'), \
             mock.patch.object(qdrant, '_network', side_effect=admission.AdmissionError('network')), \
             mock.patch.object(admission, '_request') as request, mock.patch.object(qdrant, '_hash_files') as read:
            with self.assertRaisesRegex(admission.AdmissionError, 'network'):
                qdrant.run(self.profile, admission._pin(self.profile), {}, self.daemon)
            request.assert_not_called()
            read.assert_not_called()

    def test_retained_gpu_inventory_allows_only_exact_exited_state(self):
        self.assertEqual(qdrant.GPU_PROFILE_SHA, admission._pin(qdrant.GPU_PROFILE))
        good = {'ExitCode': 1, 'StartedAt': qdrant.GPU_STARTED, 'FinishedAt': qdrant.GPU_FINISHED}
        with mock.patch.object(jre, '_inspect', return_value=good):
            qdrant._gpu_state({'HostConfig': {'Tmpfs': None}})
            qdrant._gpu_state({'HostConfig': {'Tmpfs': {}}})
            with self.assertRaisesRegex(admission.AdmissionError, 'GPU tmpfs differs'):
                qdrant._gpu_state({'HostConfig': {'Tmpfs': {'/probe': 'rw'}}})
        with mock.patch.object(jre, '_inspect', return_value=dict(good, ExitCode=0)):
            with self.assertRaisesRegex(admission.AdmissionError, 'GPU terminal identity'):
                qdrant._gpu_state({'HostConfig': {'Tmpfs': None}})
        def get(method, path, daemon):
            if path == '/containers/json?all=1':
                return [{'Id': qdrant.GPU_ID}, {'Id': self.cid}]
            self.assertEqual('/containers/' + qdrant.GPU_ID + '/json', path)
            return {'HostConfig': {'Tmpfs': None}}
        with mock.patch.object(admission, '_request', side_effect=get) as request, \
             mock.patch.object(jre, '_inspect', return_value=good):
            self.assertEqual(sorted([qdrant.GPU_ID, self.cid]), qdrant._inventory(self.daemon, self.cid))
            self.assertTrue(all(call.args[0] == 'GET' for call in request.call_args_list))
        for ids in ([], [qdrant.GPU_ID, 'c' * 64], [qdrant.GPU_ID, qdrant.GPU_ID]):
            with self.subTest(ids=ids), mock.patch.object(admission, '_request', return_value=[{'Id': x} for x in ids]):
                with self.assertRaisesRegex(admission.AdmissionError, 'unexpected or missing'):
                    qdrant._inventory(self.daemon)

    def test_recomputed_digest_cannot_change_fixed_profile(self):
        for change in (lambda p: p['ports'].append(1234),
                       lambda p: p['config'].__setitem__('Image', 'sha256:' + 'a' * 64),
                       lambda p: p['config']['HostConfig']['Mounts'][-1].__setitem__('Source', '/other'),
                       lambda p: p['config']['HostConfig'].__setitem__('Privileged', True)):
            profile = copy.deepcopy(self.profile)
            change(profile)
            with self.subTest(profile=profile), self.assertRaisesRegex(admission.AdmissionError, 'fixed profile'):
                qdrant.run(profile, admission._pin(profile), {}, self.daemon)

    def test_success_reports_selected_scope_without_claiming_http(self):
        result = self.run_fake()
        self.assertEqual(result['id'], self.cid)
        self.assertEqual(result['pid'], 42)
        self.assertFalse(result['httpVerified'])
        self.assertFalse(result['persistenceVerified'])
        self.assertFalse(result['selectedFiles']['fullClosureVerified'])
        self.assertFalse(any(m == 'DELETE' for m, _ in self.calls))

    def test_no_listener_retains_container(self):
        ticks = iter([0, 2])
        with mock.patch.object(qdrant.time, 'monotonic', side_effect=lambda: next(ticks)):
            with self.assertRaisesRegex(admission.AdmissionError, 'retained or outcome unknown'):
                self.run_fake(False)
        self.assertTrue(any(path.endswith('/start') for _, path in self.calls))
        self.assertFalse(any(m == 'DELETE' for m, _ in self.calls))

    def test_growing_stream_never_reads_above_declared_budget(self):
        info = SimpleNamespace(st_dev=1, st_ino=2, st_size=3, st_uid=0, st_gid=0, st_nlink=1,
                               st_mode=stat.S_IFREG | 0o444, st_mtime_ns=0, st_ctime_ns=0)
        src = io.BytesIO(b'abcd')
        def read(fd, n): return src.read(n)
        files = (('/fixture', 1, 2, 3, 0o444, hashlib.sha256(b'abc').hexdigest()),)
        with mock.patch.object(qdrant, 'FILES', files), mock.patch.object(qdrant, 'MAX_READ', 3), \
             mock.patch.object(jre, '_safe_parent'), mock.patch.object(Path, 'lstat', return_value=info), \
             mock.patch.object(qdrant.os, 'open', return_value=10), mock.patch.object(qdrant.os, 'fstat', return_value=info), \
             mock.patch.object(qdrant.os, 'read', side_effect=read) as calls, mock.patch.object(qdrant.os, 'close'), \
             mock.patch.object(qdrant.time, 'sleep'):
            result = qdrant._hash_files()
        self.assertEqual(3, result['bytesRead'])
        self.assertEqual([mock.call(10, 3)], calls.call_args_list)
        self.assertEqual(b'd', src.read())

    def test_listener_requires_only_loopback_and_process_socket(self):
        row = '0: 0100007F:700B 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10001 0 30000\n'
        def table(path):
            return 'header\n' + (row if path.name == 'tcp' else '')
        with mock.patch.object(Path, 'read_text', table), \
             mock.patch.object(Path, 'iterdir', return_value=[Path('/proc/42/fd/7')]), \
             mock.patch.object(qdrant.os, 'readlink', return_value='socket:[30000]'):
            self.assertTrue(qdrant._listener(42))
        with mock.patch.object(Path, 'read_text', table), \
             mock.patch.object(Path, 'iterdir', return_value=[Path('/proc/42/fd/7')]), \
             mock.patch.object(qdrant.os, 'readlink', return_value='socket:[other]'):
            with self.assertRaisesRegex(admission.AdmissionError, 'not owned'):
                qdrant._listener(42)
        row = row.replace('0100007F', '00000000')
        with mock.patch.object(Path, 'read_text', table):
            with self.assertRaisesRegex(admission.AdmissionError, 'listener differs'):
                qdrant._listener(42)

    def test_boot_and_network_must_match_daemon_and_process(self):
        same = SimpleNamespace(st_dev=4, st_ino=5)
        other = SimpleNamespace(st_dev=4, st_ino=6)
        def start(pid): return {3: 4, 42: 99}[pid]
        with mock.patch.object(Path, 'read_text', return_value=qdrant.BOOT), \
             mock.patch.object(jre, '_starttime', side_effect=start), \
             mock.patch.object(qdrant.os, 'stat', return_value=same):
            self.assertEqual((4, 5), qdrant._network(self.daemon, 42, 99))
        with mock.patch.object(Path, 'read_text', return_value=qdrant.BOOT), \
             mock.patch.object(jre, '_starttime', side_effect=start), \
             mock.patch.object(qdrant.os, 'stat', side_effect=[same, same, other]):
            with self.assertRaisesRegex(admission.AdmissionError, 'network namespace'):
                qdrant._network(self.daemon, 42, 99)
        with mock.patch.object(Path, 'read_text', return_value='different boot'), \
             mock.patch.object(qdrant.os, 'stat') as stat_call:
            with self.assertRaisesRegex(admission.AdmissionError, 'boot or daemon'):
                qdrant._network(self.daemon)
            stat_call.assert_not_called()


if __name__ == '__main__':
    unittest.main()
