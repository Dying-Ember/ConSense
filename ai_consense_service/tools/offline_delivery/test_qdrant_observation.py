"""Small fake-only tests; never contact Qdrant or signal a real process."""
from contextlib import contextmanager, redirect_stdout
import io
import hashlib
import json
from pathlib import Path
import select
import signal
import stat
from types import SimpleNamespace
import unittest
from unittest import mock

import container_admission as admission
import jre_probe_admission as jre
import qdrant_observation as obs
import qdrant_start_admission as qdrant


@contextmanager
def lock():
    yield


class FakeSocket:
    def __init__(self, data): self.data, self.sent = io.BytesIO(data), b''
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def sendall(self, value): self.sent += value
    def settimeout(self, value): pass
    def recv(self, limit): return self.data.read(limit)


class ObservationTest(unittest.TestCase):
    def setUp(self):
        self.binding = {'id': 'b' * 64, 'pid': 42, 'starttime': 99,
                        'startedAt': '2026-10-29T15:00:00Z', 'boot': qdrant.BOOT,
                        'receiptSha256': 'c' * 64}
        self.daemon = dict(obs.DAEMON)

    def test_unbound_refuses_before_lock_or_engine(self):
        with mock.patch.object(obs, 'BINDING', None), mock.patch.object(admission, '_lock') as locked, \
             mock.patch.object(obs, '_request') as request:
            for operation in (obs.observe, obs.stop):
                with self.assertRaisesRegex(admission.AdmissionError, 'binding unavailable'):
                    operation(self.binding, self.daemon)
            locked.assert_not_called()
            request.assert_not_called()

    def test_identity_rejects_any_other_container_before_gpu_inspection(self):
        rows = [{'Id': self.binding['id']}, {'Id': qdrant.GPU_ID}, {'Id': 'd' * 64}]
        with mock.patch.object(obs, 'BINDING', self.binding), mock.patch.object(admission, '_profile'), \
             mock.patch.object(obs, '_request', side_effect=[{'ID': self.daemon['id'],
                                                             'DockerRootDir': admission.DATA_ROOT}, rows]), \
             mock.patch.object(qdrant, '_gpu_state') as gpu:
            with self.assertRaisesRegex(admission.AdmissionError, 'inventory differs'):
                obs._identity(self.binding, self.daemon)
            gpu.assert_not_called()

    def test_combined_binding_is_rederived_from_receipt_bytes(self):
        result = {k: self.binding[k] for k in ('id', 'pid', 'starttime', 'startedAt')}
        payload = json.dumps({'scope': obs.COMBINED_SCOPE, 'boot': qdrant.BOOT,
                              'daemon': self.daemon, 'result': result}, sort_keys=True).encode()
        binding = dict(self.binding, receiptSha256=hashlib.sha256(payload).hexdigest())
        directory = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_uid=0, st_gid=0)
        file = SimpleNamespace(st_dev=1, st_ino=2, st_mode=stat.S_IFREG | 0o600, st_uid=0, st_gid=0,
                               st_nlink=1, st_size=len(payload), st_mtime_ns=1, st_ctime_ns=1)
        def lstat(path): return directory if path == obs.RECEIPT_DIR else file
        with mock.patch.object(jre, '_safe_parent'), mock.patch.object(Path, 'lstat', lstat), \
             mock.patch.object(obs.os, 'open', return_value=10), mock.patch.object(obs.os, 'fstat', return_value=file), \
             mock.patch.object(obs.os, 'read', return_value=payload), mock.patch.object(obs.os, 'close'):
            obs._binding(binding, combined=True)
            bad = dict(binding, receiptSha256='d' * 64)
            with self.assertRaisesRegex(admission.AdmissionError, 'receipt changed'):
                obs._binding(bad, combined=True)
            bad = dict(binding, pid=43)
            with self.assertRaisesRegex(admission.AdmissionError, 'binding differs'):
                obs._binding(bad, combined=True)

    def test_one_http_get_and_bounded_response(self):
        body = json.dumps({'title': 'qdrant', 'version': '1.19.2'}).encode()
        sock = FakeSocket(b'HTTP/1.1 200 OK\r\nContent-Length: ' + str(len(body)).encode() + b'\r\n\r\n' + body)
        with mock.patch.object(obs.socket, 'create_connection', return_value=sock):
            result = obs._http_version()
        self.assertEqual('1.19.2', result['version'])
        self.assertEqual(1, sock.sent.count(b'GET / HTTP/1.1'))
        bad = FakeSocket(b'HTTP/1.1 200 OK\r\nContent-Length: 40000\r\n\r\n')
        with mock.patch.object(obs.socket, 'create_connection', return_value=bad):
            with self.assertRaisesRegex(admission.AdmissionError, 'body budget'):
                obs._http_version()

    def test_alert_does_not_stop_and_preserves_limit_labels(self):
        cg = {'device': 1, 'inode': 2, 'memory.current': str(384 * 1024**2),
              'pids.current': '1', 'memory.events': 'oom 0\noom_kill 0', 'cpu.max': '100000 100000'}
        with mock.patch.object(obs, 'BINDING', self.binding), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), mock.patch.object(obs, '_identity', return_value={}), \
             mock.patch.object(jre, '_verify_resource'), mock.patch.object(obs, '_http_version', return_value={'status': 200}), \
             mock.patch.object(obs, '_cgroup', return_value=cg), \
             mock.patch.object(obs, '_log_size', return_value={'device': 1, 'inode': 2, 'bytes': 0}), \
             mock.patch.object(obs, '_tree_size', return_value={'entries': 0, 'logicalBytes': 0}), \
             mock.patch.object(obs, 'stop') as stopped, redirect_stdout(io.StringIO()):
            result = obs.observe(self.binding, self.daemon)
        self.assertEqual('alert', result['status'])
        self.assertEqual(['memory'], result['alerts'])
        self.assertEqual(1, result['sampleCount'])
        self.assertFalse(result['continuousIsolationVerified'])
        self.assertFalse(result['storageAccountingExact'])
        stopped.assert_not_called()

    def test_exact_pidfd_stop_sends_once_and_confirms_terminal(self):
        poll = mock.Mock()
        poll.poll.side_effect = [[], [(7, select.POLLIN)]]
        terminal = {'StartedAt': self.binding['startedAt'], 'FinishedAt': '2026-10-29T15:00:02Z', 'ExitCode': 0}
        def request(path, daemon):
            if path == '/containers/json?all=1': return [{'Id': self.binding['id']}, {'Id': qdrant.GPU_ID}]
            return {}
        with mock.patch.object(obs, 'BINDING', self.binding), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), mock.patch.object(obs, '_identity', return_value={'HostConfig': {'RestartPolicy': {'Name': 'no'}}}), \
             mock.patch.object(jre, '_verify_resource', side_effect=admission.AdmissionError('resource unavailable')), \
             mock.patch.object(obs.os, 'pidfd_open', return_value=7), \
             mock.patch.object(obs.os, 'close'), mock.patch.object(obs.select, 'poll', return_value=poll), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as send, mock.patch.object(obs, '_request', side_effect=request), \
             mock.patch.object(jre, '_inspect', return_value=terminal), mock.patch.object(qdrant, '_gpu_state'), \
             mock.patch.object(qdrant, '_network'), \
             mock.patch.object(obs, '_no_listener'), redirect_stdout(io.StringIO()):
            result = obs.stop(self.binding, self.daemon)
        send.assert_called_once_with(7, signal.SIGTERM, None, 0)
        self.assertEqual('exited', result['status'])
        self.assertFalse(result['removed'])
        self.assertFalse(result['descendantsReleasedVerified'])

    def test_stop_timeout_never_retries_signal(self):
        poll = mock.Mock()
        poll.poll.side_effect = [[], []]
        with mock.patch.object(obs, 'BINDING', self.binding), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), mock.patch.object(obs, '_identity', return_value={'HostConfig': {'RestartPolicy': {'Name': 'no'}}}), \
             mock.patch.object(jre, '_verify_resource'), mock.patch.object(obs.os, 'pidfd_open', return_value=7), \
             mock.patch.object(obs.os, 'close'), mock.patch.object(obs.select, 'poll', return_value=poll), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as send, redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(admission.AdmissionError, 'exit not observed'):
                obs.stop(self.binding, self.daemon)
        send.assert_called_once_with(7, signal.SIGTERM, None, 0)

    def test_pidfd_error_event_does_not_prove_exit(self):
        poll = mock.Mock()
        poll.poll.side_effect = [[], [(7, select.POLLERR)]]
        with mock.patch.object(obs, 'BINDING', self.binding), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), mock.patch.object(obs, '_identity', return_value={'HostConfig': {'RestartPolicy': {'Name': 'no'}}}), \
             mock.patch.object(jre, '_verify_resource'), mock.patch.object(obs.os, 'pidfd_open', return_value=7), \
             mock.patch.object(obs.os, 'close'), mock.patch.object(obs.select, 'poll', return_value=poll), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as send, redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(admission.AdmissionError, 'exit not observed'):
                obs.stop(self.binding, self.daemon)
        send.assert_called_once()

    def test_metadata_walk_refuses_over_budget_without_reading_files(self):
        root = SimpleNamespace(st_dev=2112, st_ino=56681, st_uid=10001, st_gid=10001, st_mode=stat.S_IFDIR | 0o700)
        file = SimpleNamespace(st_dev=2112, st_ino=9, st_uid=10001, st_gid=10001,
                               st_mode=stat.S_IFREG | 0o600, st_nlink=1, st_size=123)
        entry = SimpleNamespace(name='a', stat=lambda **kwargs: file)
        scan = mock.MagicMock()
        scan.__enter__.return_value = iter([entry, entry])
        with mock.patch.object(obs, 'MAX_ENTRIES', 1), mock.patch.object(obs.os, 'open', side_effect=[42, 43]), \
             mock.patch.object(obs.os, 'fstat', side_effect=[root, file]), mock.patch.object(obs.os, 'scandir', return_value=scan), \
             mock.patch.object(obs.os, 'close'), mock.patch.object(obs.os, 'read') as read, \
             mock.patch.object(obs, '_mount_id', return_value=1):
            with self.assertRaisesRegex(admission.AdmissionError, 'entry budget'):
                obs._tree_size('/fixture', 56681)
            read.assert_not_called()

    def test_bounded_tcp_table_and_socket_ownership(self):
        streams = {10: io.BytesIO(b'header\n0: 0100007F:700B 00000000:0000 0A x x x 1 0 30000\n'),
                   11: io.BytesIO(b'header\n')}
        with mock.patch.object(obs.os, 'open', side_effect=[10, 11]), mock.patch.object(obs.os, 'close'), \
             mock.patch.object(obs.os, 'read', side_effect=lambda fd, n: streams[fd].read(n)):
            self.assertEqual([('tcp', '0100007F', '30000')], obs._tcp_entries(42))
        entries = mock.MagicMock()
        entries.__enter__.return_value = iter([SimpleNamespace(path='/proc/42/fd/3')])
        with mock.patch.object(obs, '_tcp_entries', return_value=[('tcp', '0100007F', '30000')]), \
             mock.patch.object(obs.os, 'scandir', return_value=entries), \
             mock.patch.object(obs.os, 'readlink', return_value='socket:[wrong]'):
            with self.assertRaisesRegex(admission.AdmissionError, 'not owned'):
                obs._listener(42)

    def test_oversized_tcp_fails_closed(self):
        with mock.patch.object(obs, 'MAX_TCP_BYTES', 8), mock.patch.object(obs.os, 'open', return_value=10), \
             mock.patch.object(obs.os, 'close'), mock.patch.object(obs.os, 'read', return_value=b'x' * 9) as read:
            with self.assertRaisesRegex(admission.AdmissionError, 'TCP table oversized'):
                obs._tcp_entries(42)
            read.assert_called_once_with(10, 9)


if __name__ == '__main__':
    unittest.main()
