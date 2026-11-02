"""Synthetic fixtures and fake Engine only; no H2, Java, sockets or Docker."""
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest import mock
import unittest

import container_admission as admission
import h2_synthetic_disk_admission as disk
import h2_tcp_server_admission as server
import jre_probe_admission as jre


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.cid = 'b' * 64
        self.daemon = {'id': 'fixture', 'socketDevice': 1, 'socketInode': 2,
                       'pid': 3, 'startTicks': 4, 'uid': 0, 'gid': 0}
        self.calls = []
        self.locked = False

    @contextmanager
    def lock(self):
        self.assertFalse(self.locked)
        self.locked = True
        try:
            yield
        finally:
            self.locked = False

    def request(self, method, path, daemon, payload=None):
        self.assertTrue(self.locked)
        self.calls.append((method, path))
        if path == '/info':
            return {'ID': 'fixture', 'DockerRootDir': admission.DATA_ROOT}
        if path.startswith('/containers/create?'):
            return {'Id': self.cid}
        if path.endswith('/start'):
            return None
        if path.endswith('/json'):
            return {'state_for_test': 'created' if not any(p.endswith('/start') for _, p in self.calls) else 'running'}
        self.fail((method, path))

    def inspect(self, row, profile, cid, status):
        self.assertEqual(row['state_for_test'], status)
        if status == 'created':
            return {'StartedAt': '0001-01-01T00:00:00Z', 'FinishedAt': '0001-01-01T00:00:00Z'}
        return {'Pid': 42, 'StartedAt': '2026-10-29T15:00:00Z'}

    def fake_run(self, listener=True):
        def inventory(known, daemon, probe=None):
            return [disk.GPU_ID] + ([probe] if probe else [])
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(admission, '_profile'), mock.patch.object(admission, '_request', side_effect=self.request), \
             mock.patch.object(admission, '_verify_mount_isolation'), \
             mock.patch.object(disk, '_known_inventory', side_effect=inventory), \
             mock.patch.object(jre, '_verify_resource'), mock.patch.object(jre, '_image'), \
             mock.patch.object(jre, '_inspect', side_effect=self.inspect), \
             mock.patch.object(jre, '_starttime', return_value=99), \
             mock.patch.object(server, '_scope'), mock.patch.object(server, '_network', return_value=(1, 2)), \
             mock.patch.object(server, '_ports_free'), mock.patch.object(server, '_closed_fixture', return_value={}), \
             mock.patch.object(server, '_runtime', return_value={'identities': {}, 'fullClosureVerified': False}), \
             mock.patch.object(server, '_recheck_runtime'), mock.patch.object(server, '_process_identity'), \
             mock.patch.object(server, '_listener', side_effect=listener if isinstance(listener, Exception) else None,
                               return_value=listener), \
             mock.patch.object(server.os, 'stat', return_value=SimpleNamespace(st_dev=2112, st_ino=56329)), \
             mock.patch.object(server.time, 'sleep'):
            return server.run(server.PROFILE, admission._pin(server.PROFILE), self.daemon)

    def test_profile_is_fixed_host_loopback_and_nonroot(self):
        profile = server.PROFILE
        with mock.patch.object(admission, '_mounts', side_effect=lambda cfg: cfg['HostConfig']['Mounts']):
            admission._profile(profile, admission._pin(profile))
        self.assertEqual(profile['config']['HostConfig']['NetworkMode'], 'host')
        self.assertEqual(profile['config']['User'], 'consense:consense')
        self.assertIn('-Dh2.bindAddress=127.0.0.1', profile['config']['Cmd'])
        self.assertNotIn('-tcpAllowOthers', profile['config']['Cmd'])
        self.assertIn('-ifExists', profile['config']['Cmd'])

    def test_fake_start_keeps_gpu_and_reports_no_client_or_persistence(self):
        result = self.fake_run()
        self.assertEqual(result['id'], self.cid)
        self.assertEqual(result['listener'], '127.0.0.1:28682')
        self.assertEqual(result['gpuRelease'], 'unknown')
        self.assertFalse(result['cgroupVerified'])
        self.assertFalse(result['clientVerified'])
        self.assertFalse(result['persistenceVerified'])
        self.assertFalse(any(method == 'DELETE' or disk.GPU_ID in path for method, path in self.calls))

    def test_bad_listener_retains_created_container_without_gpu_mutation(self):
        with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
            self.fake_run(admission.AdmissionError('listener differs'))
        self.assertTrue(any(method == 'POST' and path.endswith('/start') for method, path in self.calls))
        self.assertFalse(any(method == 'DELETE' or disk.GPU_ID in path for method, path in self.calls))

    def test_changed_profile_refused_before_engine(self):
        with mock.patch.object(admission, '_request') as request:
            with self.assertRaises(admission.AdmissionError):
                server.run(server.PROFILE, '0' * 64, self.daemon)
            request.assert_not_called()

    def test_scope_is_new_exact_scope(self):
        with mock.patch.object(jre, '_verify_scope') as verify:
            server._scope()
            verify.assert_called_once_with('ticket-02 H2 synthetic TCP server start attempt1 only')

    def test_listener_rejects_wildcard_and_ipv6(self):
        for entries in ([('tcp', '00000000', '1')], [('tcp6', '0' * 32, '1')],
                        [('tcp', '0100007F', '1'), ('tcp6', '0' * 32, '2')]):
            with self.subTest(entries=entries), mock.patch.object(server, '_port_entries', return_value=entries):
                with self.assertRaisesRegex(admission.AdmissionError, 'listener differs'):
                    server._listener(42)
        with mock.patch.object(server, '_port_entries', return_value=[]):
            self.assertFalse(server._listener(42))

    @unittest.skipUnless(hasattr(os, 'geteuid') and os.geteuid() == 0, 'requires Linux root fixture')
    def test_bounded_runtime_read_hashes_and_uses_only_server_scope(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture'
            path.write_bytes(b'fixture')
            start = server.time.monotonic()
            with mock.patch.object(jre, '_safe_parent'), mock.patch.object(server.time, 'sleep'), \
                 mock.patch.object(server, '_scope') as scope:
                identities = {}
                count = server._entry(path, ('file', 7, hashlib.sha256(b'fixture').hexdigest()),
                                      start, 32 * 1024 * 1024 - 4, identities)
                self.assertEqual(count, 32 * 1024 * 1024 + 3)
                scope.assert_called_once_with()
                self.assertIn(str(path), identities)
                with self.assertRaises(admission.AdmissionError):
                    server._entry(path, ('file', 7, '0' * 64), start, 0, {})

    @unittest.skipUnless(hasattr(os, 'geteuid') and os.geteuid() == 0, 'requires Linux root fixture')
    def test_closed_fixture_only_stats_files_and_rejects_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            parent = Path(folder) / 'outer'; parent.mkdir(mode=0o700)
            root = parent / 'work'; root.mkdir(mode=0o700); os.chown(root, 10001, 10001)
            db = root / 'db'; db.mkdir(mode=0o755); os.chmod(db, 0o755); os.chown(db, 10001, 10001)
            rows = {}
            for name in server.DB_ROWS:
                path = db / name; path.write_bytes(b'synthetic'); os.chmod(path, 0o644); os.chown(path, 10001, 10001)
                item = path.stat(); rows[name] = (item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
            rootinfo, parentinfo, dbinfo = root.stat(), parent.stat(), db.stat()
            with mock.patch.object(disk, 'FIXTURE', root), \
                 mock.patch.object(disk, 'FIXTURE_ID', (rootinfo.st_dev, rootinfo.st_ino)), \
                 mock.patch.object(disk, 'FIXTURE_PARENT_ID', (parentinfo.st_dev, parentinfo.st_ino)), \
                 mock.patch.object(jre, '_safe_parent'), mock.patch.object(server, 'DB_ROWS', rows), \
                 mock.patch.object(server, 'DB_DIR_ID', (dbinfo.st_dev, dbinfo.st_ino)):
                original_open = os.open
                with mock.patch.object(server.os, 'open', wraps=original_open) as opened:
                    saved = server._closed_fixture()
                    self.assertEqual(saved, server._closed_fixture(prior=saved))
                    self.assertTrue(all(call.args[1] & os.O_DIRECTORY for call in opened.call_args_list))
                (db / 'unexpected').write_text('synthetic')
                with self.assertRaisesRegex(admission.AdmissionError, 'database directory differs'):
                    server._closed_fixture(prior=saved)

    def test_scope_failure_stops_before_engine(self):
        with mock.patch.object(admission, '_lock', side_effect=self.lock), \
             mock.patch.object(server, '_scope', side_effect=admission.AdmissionError('wrong scope')), \
             mock.patch.object(admission, '_request') as request:
            with self.assertRaisesRegex(admission.AdmissionError, 'wrong scope'):
                server.run(server.PROFILE, admission._pin(server.PROFILE), self.daemon)
            request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
