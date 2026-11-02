"""Mocks only; no Engine, Qdrant, filesystem mutation or actual signal."""
from contextlib import contextmanager, redirect_stdout
import hashlib
import io
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
import qdrant_ca_attempt2_admission as qdrant
import qdrant_ca_attempt2_observation as obs


@contextmanager
def lock():
    yield


class ObservationTest(unittest.TestCase):
    def setUp(self):
        self.binding = {'id': 'b' * 64, 'pid': 42, 'starttime': 99,
                        'startedAt': '2026-10-29T15:00:00Z', 'boot': qdrant.BOOT,
                        'receiptSha256': 'c' * 64}
        self.daemon = dict(obs.DAEMON)
        self.observed = {'status': 'observed', 'sampleCount': 13,
                         'http': {'status': 200, 'version': '1.19.2'}}
        self.safe_inspect = {'HostConfig': {'AutoRemove': False, 'Tmpfs': None,
                                            'RestartPolicy': {'Name': 'no', 'MaximumRetryCount': 0}}}

    def test_receipt_binds_actual_bytes_scope_pid_and_daemon(self):
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
            obs._binding(binding)
            for bad in (dict(binding, receiptSha256='d' * 64), dict(binding, pid=43)):
                with self.assertRaises(admission.AdmissionError):
                    obs._binding(bad)

    def test_identity_invokes_retained_inventory_before_current_process(self):
        profile = {'a': 1}
        with mock.patch.object(obs, '_binding'), mock.patch.object(admission, '_daemon_pin'), \
             mock.patch.object(obs, '_request', return_value={'ID': self.daemon['id'], 'DockerRootDir': admission.DATA_ROOT}), \
             mock.patch.object(qdrant, 'fixed_profile', return_value=profile), mock.patch.object(qdrant, 'PROFILE_SHA', 'pin'), \
             mock.patch.object(admission, '_pin', return_value='pin'), mock.patch.object(admission, '_profile'), \
             mock.patch.object(qdrant, '_inventory', side_effect=admission.AdmissionError('retained mismatch')) as inventory, \
             mock.patch.object(qdrant, '_inspect_instance') as inspected:
            with self.assertRaisesRegex(admission.AdmissionError, 'retained mismatch'):
                obs._identity(self.binding, self.daemon)
            inventory.assert_called_once_with(self.daemon, self.binding['id'])
            inspected.assert_not_called()

    def test_identity_uses_real_fixed_profile_and_attempt2_directories(self):
        profile = qdrant.fixed_profile()
        self.assertEqual(qdrant.PROFILE_SHA, admission._pin(profile))
        storage = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_dev=2112, st_ino=56790,
                                  st_uid=10001, st_gid=10001)
        tmp = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_dev=2112, st_ino=56791,
                              st_uid=10001, st_gid=10001)
        running = {'Pid': 42, 'StartedAt': self.binding['startedAt']}
        with mock.patch.object(obs, '_binding'), mock.patch.object(admission, '_daemon_pin'), \
             mock.patch.object(obs, '_request', side_effect=[{'ID': self.daemon['id'],
                                                               'DockerRootDir': admission.DATA_ROOT}, self.safe_inspect]), \
             mock.patch.object(admission, '_profile') as check_profile, mock.patch.object(qdrant, '_inventory') as inventory, \
             mock.patch.object(jre, '_inspect', return_value=running) as inspect, \
             mock.patch.object(jre, '_starttime', return_value=99), mock.patch.object(admission, '_process_identity'), \
             mock.patch.object(qdrant, '_process_binary'), mock.patch.object(qdrant, '_network'), \
             mock.patch.object(obs.os, 'lstat', side_effect=[storage, tmp]), mock.patch.object(obs, '_listener'):
            obs._identity(self.binding, self.daemon)
            check_profile.assert_called_once_with(profile, qdrant.PROFILE_SHA)
            inventory.assert_called_once_with(self.daemon, self.binding['id'])
            inspect.assert_called_once_with(self.safe_inspect, profile, self.binding['id'], 'running')

    def test_identity_rejects_unsafe_retention_before_inspect(self):
        bad_rows = [{'HostConfig': {'AutoRemove': True, 'Tmpfs': None,
                                    'RestartPolicy': {'Name': 'no', 'MaximumRetryCount': 0}}},
                    {'HostConfig': {'AutoRemove': False, 'Tmpfs': {'/tmp': 'rw'},
                                    'RestartPolicy': {'Name': 'no', 'MaximumRetryCount': 0}}},
                    {'HostConfig': {'AutoRemove': False, 'Tmpfs': None,
                                    'RestartPolicy': {'Name': 'always', 'MaximumRetryCount': 0}}}]
        for bad in bad_rows:
            with self.subTest(bad=bad), mock.patch.object(obs, '_binding'), \
                 mock.patch.object(admission, '_daemon_pin'), \
                 mock.patch.object(obs, '_request', side_effect=[{'ID': self.daemon['id'],
                                                                  'DockerRootDir': admission.DATA_ROOT}, bad]), \
                 mock.patch.object(admission, '_profile'), mock.patch.object(qdrant, '_inventory'), \
                 mock.patch.object(jre, '_inspect') as inspect:
                with self.assertRaisesRegex(admission.AdmissionError, 'retention policy'):
                    obs._identity(self.binding, self.daemon)
                inspect.assert_not_called()

    def test_bad_observation_or_wrong_scope_never_opens_pidfd(self):
        candidates = [None, {}, dict(self.observed, status='failed'), dict(self.observed, sampleCount=0),
                      dict(self.observed, http={'status': 200, 'version': 'wrong'})]
        with mock.patch.object(obs.os, 'pidfd_open') as opened, mock.patch.object(obs.signal, 'pidfd_send_signal') as sent:
            for value in candidates:
                with self.assertRaises(admission.AdmissionError):
                    obs.stop(self.binding, self.daemon, value)
            with self.assertRaises(admission.AdmissionError):
                obs.stop(self.binding, self.daemon, self.observed, scope='old scope')
            opened.assert_not_called()
            sent.assert_not_called()

    def test_alert_returns_without_signal_and_limits_labels(self):
        cg = {'device': 1, 'inode': 2, 'memory.current': str(384 * 1024**2),
              'pids.current': '1', 'memory.events': 'oom 0\noom_kill 0', 'cpu.max': '100000 100000'}
        with mock.patch.object(obs, '_binding'), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), mock.patch.object(obs, '_identity', return_value={}), \
             mock.patch.object(jre, '_verify_resource'), \
             mock.patch.object(obs, '_http_version', return_value={'status': 200, 'version': '1.19.2'}), \
             mock.patch.object(obs, '_cgroup', return_value=cg), \
             mock.patch.object(obs, '_log_size', return_value={'device': 1, 'inode': 2, 'bytes': 0}), \
             mock.patch.object(obs, '_tree_size', return_value={'entries': 0, 'logicalBytes': 0}), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as sent, redirect_stdout(io.StringIO()):
            result = obs.observe(self.binding, self.daemon)
        self.assertEqual('alert', result['status'])
        self.assertEqual(1, result['sampleCount'])
        self.assertFalse(result['continuousIsolationVerified'])
        sent.assert_not_called()

    def test_observation_failure_never_signals(self):
        with mock.patch.object(obs, '_binding'), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), mock.patch.object(obs, '_identity'), \
             mock.patch.object(jre, '_verify_resource'), \
             mock.patch.object(obs, '_http_version', side_effect=admission.AdmissionError('bad HTTP')), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as send:
            with self.assertRaisesRegex(admission.AdmissionError, 'bad HTTP'):
                obs.observe(self.binding, self.daemon)
            send.assert_not_called()

    def test_stop_pid_identity_rechecked_and_signal_once(self):
        poll = mock.Mock()
        poll.poll.side_effect = [[], [(7, select.POLLIN)]]
        terminal = {'StartedAt': self.binding['startedAt'], 'FinishedAt': '2026-10-29T15:00:02Z', 'ExitCode': 0}
        with mock.patch.object(obs, '_binding'), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), \
             mock.patch.object(obs, '_identity', return_value={'HostConfig': {'RestartPolicy': {'Name': 'no'}}}) as identity, \
             mock.patch.object(obs.os, 'pidfd_open', return_value=7), mock.patch.object(obs.os, 'close'), \
             mock.patch.object(obs.select, 'poll', return_value=poll), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as send, \
             mock.patch.object(obs, '_request', return_value=self.safe_inspect), \
             mock.patch.object(jre, '_inspect', return_value=terminal) as inspect, \
             mock.patch.object(qdrant, '_inventory') as inventory, \
             mock.patch.object(qdrant, '_network'), mock.patch.object(obs, '_no_listener'), redirect_stdout(io.StringIO()):
            result = obs.stop(self.binding, self.daemon, self.observed)
        self.assertEqual(2, identity.call_count)
        send.assert_called_once_with(7, signal.SIGTERM, None, 0)
        inventory.assert_called_once_with(self.daemon, self.binding['id'])
        self.assertEqual(2, inspect.call_count)
        for call in inspect.call_args_list:
            self.assertEqual((self.safe_inspect, qdrant.fixed_profile(), self.binding['id'], 'exited'), call.args)
        self.assertEqual('exited', result['status'])
        self.assertFalse(result['removed'])
        self.assertFalse(result['descendantsReleasedVerified'])

    def test_second_terminal_retention_mismatch_is_not_success(self):
        poll = mock.Mock()
        poll.poll.side_effect = [[], [(7, select.POLLIN)]]
        terminal = {'StartedAt': self.binding['startedAt'], 'FinishedAt': '2026-10-29T15:00:02Z', 'ExitCode': 0}
        bad = {'HostConfig': {'AutoRemove': True, 'Tmpfs': None,
                              'RestartPolicy': {'Name': 'no', 'MaximumRetryCount': 0}}}
        with mock.patch.object(obs, '_binding'), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), \
             mock.patch.object(obs, '_identity', return_value={'HostConfig': {'RestartPolicy': {'Name': 'no'}}}), \
             mock.patch.object(obs.os, 'pidfd_open', return_value=7), mock.patch.object(obs.os, 'close'), \
             mock.patch.object(obs.select, 'poll', return_value=poll), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as send, \
             mock.patch.object(obs, '_request', side_effect=[self.safe_inspect, bad]), \
             mock.patch.object(jre, '_inspect', return_value=terminal) as inspect, \
             mock.patch.object(qdrant, '_inventory'), mock.patch.object(qdrant, '_network'), \
             mock.patch.object(obs, '_no_listener'), redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(admission.AdmissionError, 'retention policy'):
                obs.stop(self.binding, self.daemon, self.observed)
            send.assert_called_once()
            inspect.assert_called_once()

    def test_second_identity_failure_prevents_signal(self):
        poll = mock.Mock()
        poll.poll.return_value = []
        with mock.patch.object(obs, '_binding'), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), \
             mock.patch.object(obs, '_identity', side_effect=[{'HostConfig': {'RestartPolicy': {'Name': 'no'}}},
                                                              admission.AdmissionError('changed')]), \
             mock.patch.object(obs.os, 'pidfd_open', return_value=7), mock.patch.object(obs.os, 'close'), \
             mock.patch.object(obs.select, 'poll', return_value=poll), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as send:
            with self.assertRaisesRegex(admission.AdmissionError, 'changed'):
                obs.stop(self.binding, self.daemon, self.observed)
            send.assert_not_called()

    def test_stop_timeout_does_not_repeat_signal(self):
        poll = mock.Mock()
        poll.poll.side_effect = [[], []]
        with mock.patch.object(obs, '_binding'), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), \
             mock.patch.object(obs, '_identity', return_value={'HostConfig': {'RestartPolicy': {'Name': 'no'}}}), \
             mock.patch.object(obs.os, 'pidfd_open', return_value=7), mock.patch.object(obs.os, 'close'), \
             mock.patch.object(obs.select, 'poll', return_value=poll), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as send, redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(admission.AdmissionError, 'exit not observed'):
                obs.stop(self.binding, self.daemon, self.observed)
            send.assert_called_once_with(7, signal.SIGTERM, None, 0)

    def test_pidfd_error_event_never_proves_exit(self):
        poll = mock.Mock()
        poll.poll.side_effect = [[], [(7, select.POLLERR)]]
        with mock.patch.object(obs, '_binding'), mock.patch.object(admission, '_lock', side_effect=lock), \
             mock.patch.object(obs, '_scope'), \
             mock.patch.object(obs, '_identity', return_value={'HostConfig': {'RestartPolicy': {'Name': 'no'}}}), \
             mock.patch.object(obs.os, 'pidfd_open', return_value=7), mock.patch.object(obs.os, 'close'), \
             mock.patch.object(obs.select, 'poll', return_value=poll), \
             mock.patch.object(obs.signal, 'pidfd_send_signal') as send, redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(admission.AdmissionError, 'exit not observed'):
                obs.stop(self.binding, self.daemon, self.observed)
            send.assert_called_once()


if __name__ == '__main__':
    unittest.main()
