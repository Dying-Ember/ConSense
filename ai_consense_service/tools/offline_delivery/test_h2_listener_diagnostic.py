"""Fake-Engine checks; no real container, listener, SQL or signal."""
import contextlib
import copy
import io
import stat
from types import SimpleNamespace
from unittest import TestCase, main, mock

import container_admission as admission
import h2_listener_diagnostic as diag


class VerdictTests(TestCase):
    def sample(self, rows, status='observed'):
        return {'status': status, 'listeners': rows}

    def test_only_single_owned_ipv4_loopback_passes(self):
        row = {'family': 'tcp', 'address': '0100007F', 'ownership': 'owned', 'inode': '9'}
        self.assertEqual(diag.verdict(self.sample([row])), 'strict_ipv4_loopback_owned')
        for rows in ([dict(row, address='00000000')], [dict(row, family='tcp6')],
                     [dict(row, ownership='absent')], [row, row]):
            with self.subTest(rows=rows):
                self.assertEqual(diag.verdict(self.sample(rows)), 'rejected')
        self.assertEqual(diag.verdict(self.sample([], 'unknown')), 'unknown')
        self.assertEqual(diag.verdict(self.sample([])), 'no_listener')

    def test_profile_does_not_mount_fixture_or_run_client(self):
        cfg = diag.PROFILE['config']
        self.assertEqual(cfg['HostConfig']['Tmpfs'], diag.TMPFS)
        self.assertEqual(cfg['HostConfig']['NetworkMode'], 'host')
        self.assertTrue(cfg['HostConfig']['ReadonlyRootfs'])
        self.assertTrue(all(m['ReadOnly'] and m['Source'] != str(diag.disk.FIXTURE)
                            for m in cfg['HostConfig']['Mounts']))
        self.assertIn('org.h2.tools.Server', cfg['Cmd'])
        self.assertIn('-ifExists', cfg['Cmd'])
        self.assertNotIn('H2TcpSyntheticClient.java', cfg['Cmd'])

    def test_tmpfs_requires_exact_mount_flags_and_capacity(self):
        binding = {'pid': 123, 'starttime': 456}
        inode = SimpleNamespace(st_dev=46, st_ino=1, st_mode=stat.S_IFDIR | 0o700, st_uid=10001,
                                st_gid=10001, st_size=0, st_mtime_ns=1, st_ctime_ns=1, st_nlink=1)
        raw = b'123 1 0:46 / /runtime/probe rw,nosuid,nodev,noexec - tmpfs tmpfs rw,size=16384k\n'
        with mock.patch.object(diag.os, 'open', return_value=77), mock.patch.object(diag.os, 'close'), \
             mock.patch.object(diag.os, 'fstat', return_value=inode), \
             mock.patch.object(diag.os, 'fstatvfs', return_value=SimpleNamespace(f_blocks=4096, f_frsize=4096)), \
             mock.patch.object(diag.Path, 'read_text', return_value='mnt_id:\t123\n'), \
             mock.patch.object(diag.Path, 'open', side_effect=lambda *a, **kw: io.BytesIO(raw)), \
             mock.patch.object(diag.jre, '_starttime', return_value=456):
            self.assertEqual(diag.verify_tmpfs(binding)['mountId'], 123)
            raw = raw.replace(b'nodev,', b'')
            with self.assertRaisesRegex(admission.AdmissionError, 'flags differ'):
                diag.verify_tmpfs(binding)


class LifecycleTests(TestCase):
    def test_observation_is_recorded_before_one_stop(self):
        cid = 'a' * 64
        binding = {'id': cid, 'pid': 123, 'starttime': 456, 'startedAt': '2026-01-01T00:00:00Z',
                   'networkNamespace': (1, 2)}
        sample = {'status': 'observed', 'listeners': [{'family': 'tcp6', 'address': '0' * 32,
                                                        'inode': '7', 'ownership': 'owned'}]}
        events = []

        def request(method, path, daemon, payload=None):
            events.append((method, path))
            if path == '/info': return {'ID': diag.DAEMON['id'], 'DockerRootDir': admission.DATA_ROOT}
            if path.startswith('/containers/create?'): return {'Id': cid}
            if path.endswith('/json'): return {}
            if path.endswith('/start'): return {}
            self.fail(path)

        def record(folder, name, value):
            events.append(('record', name))
            return name + '-hash'

        def stop(current, attempted):
            events.append(('stop', current['id']))
            return {'status': 'exited', 'exitCode': 143}

        patches = [mock.patch.object(admission, '_lock', return_value=contextlib.nullcontext()),
                   mock.patch.object(admission, '_daemon_pin'), mock.patch.object(admission, '_mounts'),
                   mock.patch.object(admission, '_request', side_effect=request),
                   mock.patch.object(diag, 'scope'), mock.patch.object(diag, 'inventory'),
                   mock.patch.object(diag.jre, '_verify_resource'), mock.patch.object(diag.jre, '_image'),
                   mock.patch.object(diag.h2, '_network'), mock.patch.object(diag.h2, '_qdrant_check'),
                   mock.patch.object(diag.h2, '_closed_fixture', return_value={}), mock.patch.object(diag.h2, '_ports_free'),
                   mock.patch.object(diag, 'runtime', return_value={'identities': {}}),
                   mock.patch.object(diag, 'recheck_inputs'), mock.patch.object(diag, 'make_evidence', return_value=42),
                   mock.patch.object(diag.os, 'close'), mock.patch.object(diag.os, 'pidfd_open', create=True),
                   mock.patch.object(diag.signal, 'pidfd_send_signal', create=True),
                   mock.patch.object(diag, 'bound', return_value=binding),
                   mock.patch.object(diag, 'verify_tmpfs', return_value={'mountId': 1}),
                   mock.patch.object(diag.h2, '_sample_server', return_value={'rssBytes': 1}),
                   mock.patch.object(diag.observation, 'capture', return_value=sample),
                   mock.patch.object(diag, 'record', side_effect=record), mock.patch.object(diag, 'stop', side_effect=stop),
                   mock.patch.object(diag, 'inspect', return_value={'StartedAt': '0001-01-01T00:00:00Z',
                                                                    'FinishedAt': '0001-01-01T00:00:00Z'})]
        with contextlib.ExitStack() as stack:
            for item in patches: stack.enter_context(item)
            result = diag.run(admission._pin(diag.PROFILE))
        self.assertEqual(result['verdict'], 'rejected')
        self.assertEqual(events.count(('stop', cid)), 1)
        self.assertLess(events.index(('record', 'observation.json')), events.index(('stop', cid)))
        self.assertFalse(any(method == 'DELETE' for method, _ in events))
        self.assertFalse(result['sqlSentByRunner'])

    def test_unknown_sample_is_not_accepted(self):
        row = {'family': 'tcp', 'address': '0100007F', 'ownership': 'unknown'}
        self.assertEqual(diag.verdict({'status': 'unknown', 'listeners': [row]}), 'unknown')

    def test_pidfd_stop_is_attempted_once(self):
        binding = {'id': 'a' * 64, 'pid': 123, 'starttime': 456,
                   'startedAt': '2026-01-01T00:00:00Z', 'networkNamespace': (1, 2)}
        poller = mock.Mock()
        poller.poll.side_effect = [[], [(77, diag.select.POLLIN)]]
        state = {'StartedAt': binding['startedAt'], 'FinishedAt': '2026-01-01T00:00:01Z', 'ExitCode': 143}
        attempted = set()
        with mock.patch.object(diag, 'scope'), mock.patch.object(diag, 'inventory'), \
             mock.patch.object(diag, 'bound', return_value=binding), \
             mock.patch.object(diag.os, 'pidfd_open', return_value=77), \
             mock.patch.object(diag.os, 'close'), mock.patch.object(diag.select, 'poll', return_value=poller), \
             mock.patch.object(diag.signal, 'pidfd_send_signal') as sent, \
             mock.patch.object(diag.jre, '_starttime', return_value=456), \
             mock.patch.object(diag.jre, '_timestamp', side_effect=lambda value: value), \
             mock.patch.object(diag.h2, '_port_entries', return_value=[]), \
             mock.patch.object(diag.admission, '_request', return_value={'State': {'Status': 'exited'}}), \
             mock.patch.object(diag, 'inspect', return_value=state):
            self.assertEqual(diag.stop(binding, attempted)['exitCode'], 143)
            with self.assertRaisesRegex(admission.AdmissionError, 'already attempted'):
                diag.stop(binding, attempted)
        sent.assert_called_once()

    def test_bad_pidfd_event_is_unknown_and_does_not_repeat_signal(self):
        binding = {'id': 'a' * 64, 'pid': 123, 'starttime': 456,
                   'startedAt': '2026-01-01T00:00:00Z', 'networkNamespace': (1, 2)}
        poller = mock.Mock()
        poller.poll.side_effect = [[], [(77, diag.select.POLLERR)]]
        attempted = set()
        with mock.patch.object(diag, 'scope'), mock.patch.object(diag, 'inventory'), \
             mock.patch.object(diag, 'bound', return_value=binding), \
             mock.patch.object(diag.os, 'pidfd_open', return_value=77), mock.patch.object(diag.os, 'close'), \
             mock.patch.object(diag.select, 'poll', return_value=poller), \
             mock.patch.object(diag.signal, 'pidfd_send_signal') as sent, \
             mock.patch.object(diag.jre, '_starttime', return_value=456):
            with self.assertRaisesRegex(admission.AdmissionError, 'pidfd event differs'):
                diag.stop(binding, attempted)
            with self.assertRaisesRegex(admission.AdmissionError, 'already attempted'):
                diag.stop(binding, attempted)
        sent.assert_called_once()


if __name__ == '__main__':
    main()
