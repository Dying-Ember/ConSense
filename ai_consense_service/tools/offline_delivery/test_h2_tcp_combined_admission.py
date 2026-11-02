"""Fake lifecycle and small inputs only; no Docker, H2, Java or real signals."""
from contextlib import contextmanager, ExitStack
import copy
import os
from types import SimpleNamespace
from unittest import mock
import unittest

import container_admission as admission
import h2_tcp_combined_admission as combined
import jre_probe_admission as jre


VERSION = 'java.version=17.0.20.1 h2.database=2.1.214 (2022-06-13) h2.driver=2.1.214 (2022-06-13)'
WRITE = VERSION + '\ntcp synthetic commit starting; failure may be ambiguous\ntcp synthetic commit returned\ntcp synthetic write complete\n'
READ = VERSION + '\ntcp synthetic read complete\n'


class CombinedTest(unittest.TestCase):
    def setUp(self):
        self.daemon = {'id': 'fixture', 'socketDevice': 1, 'socketInode': 2,
                       'pid': 3, 'startTicks': 4, 'uid': 0, 'gid': 0}
        self.cid = 'b' * 64
        self.client = 'c' * 64
        self.binding = {'id': self.cid, 'pid': 42, 'starttime': 99, 'startedAt': '2026-10-29T15:00:00Z'}

    @contextmanager
    def lock(self):
        yield

    def test_client_output_and_profile_are_exact(self):
        self.assertEqual(combined._client_output('write', WRITE), WRITE)
        self.assertEqual(combined._client_output('read', READ), READ)
        for phase, text in (('write', READ), ('read', READ + 'unexpected\n'),
                            ('write', WRITE + 'synthetic-only-new-password\n')):
            with self.subTest(phase=phase), self.assertRaises(admission.AdmissionError):
                combined._client_output(phase, text)
        profile = combined._client_profile('write')
        self.assertEqual(profile['config']['HostConfig']['NetworkMode'], 'host')
        self.assertEqual(profile['config']['User'], 'consense:consense')
        self.assertEqual(profile['config']['HostConfig']['LogConfig']['Config']['max-size'], '1m')
        self.assertTrue(all(m['ReadOnly'] for m in profile['config']['HostConfig']['Mounts']))
        self.assertNotIn('synthetic-only-new-password', str(profile))

    def test_actual_scope_is_combined_not_old_server_scope(self):
        with mock.patch.object(jre, '_verify_scope') as verify:
            combined._scope()
            verify.assert_called_once_with('ticket-02 H2 synthetic TCP combined attempt1 only')

    def test_client_lifecycle_requires_observed_java_and_retains_id(self):
        owned = [self.cid]
        calls = []
        reads = 0
        def request(method, path, daemon, payload=None):
            nonlocal reads
            calls.append((method, path))
            if path.startswith('/containers/create?'):
                return {'Id': self.client}
            if path.endswith('/start'):
                return None
            if path.endswith('/json'):
                if not any(p.endswith('/start') for _, p in calls):
                    return {'kind': 'created'}
                reads += 1
                kind = 'running' if reads == 1 else 'exited'
                return {'kind': kind, 'State': {'Status': kind}}
            self.fail((method, path))
        def inspect(row, profile, cid, status):
            self.assertEqual(row['kind'], status)
            if status == 'created':
                return {'StartedAt': '0001-01-01T00:00:00Z', 'FinishedAt': '0001-01-01T00:00:00Z'}
            if status == 'running':
                return {'Pid': 43, 'StartedAt': '2026-10-29T15:00:00Z'}
            return {'ExitCode': 0, 'StartedAt': '2026-10-29T15:00:00Z',
                    'FinishedAt': '2026-10-29T15:00:02Z', 'OOMKilled': False, 'Error': ''}
        with mock.patch.object(combined, '_qdrant_check'), mock.patch.object(admission, '_mounts'), mock.patch.object(admission, '_request', side_effect=request), \
             mock.patch.object(combined, '_observe_server') as observe, \
             mock.patch.object(jre, '_inspect', side_effect=inspect), \
             mock.patch.object(jre, '_starttime', return_value=100), mock.patch.object(jre, '_logs', return_value=WRITE), \
             mock.patch.object(combined, '_process_identity'), mock.patch.object(combined, '_network'), \
             mock.patch.object(combined.os, 'stat', return_value=SimpleNamespace(st_dev=2112, st_ino=56329)), \
             mock.patch.object(combined, '_event'), mock.patch.object(combined.time, 'sleep'):
            self.assertEqual(combined._client('write', self.daemon, owned, self.binding, 10), self.client)
            self.assertGreaterEqual(observe.call_count, 4)
        self.assertEqual(owned, [self.cid, self.client])
        self.assertFalse(any(method == 'DELETE' for method, _ in calls))

    def test_client_sixth_slot_rejected_before_create(self):
        with mock.patch.object(admission, '_request') as request:
            with self.assertRaisesRegex(admission.AdmissionError, 'slot unavailable'):
                combined._client('read', self.daemon, ['1' * 64, '2' * 64, '3' * 64], self.binding, 10)
            request.assert_not_called()

    def test_listener_accepts_only_one_exact_owned_loopback(self):
        accepted = [('tcp', '0100007F', '42'),
                    ('tcp6', '0000000000000000FFFF00000100007F', '42')]
        rejected = [('tcp', '00000000', '42'), ('tcp', '0200007F', '42'),
                    ('tcp6', '00000000000000000000000000000000', '42'),
                    ('tcp6', '00000000000000000000000001000000', '42'),
                    ('tcp6', '0000000000000000FFFF00000200007F', '42')]
        fd = object()
        with mock.patch.object(combined.Path, 'iterdir', return_value=[fd]), \
             mock.patch.object(combined.os, 'readlink', return_value='socket:[42]'):
            for entry in accepted:
                with self.subTest(entry=entry), mock.patch.object(combined, '_port_entries', return_value=[entry]):
                    self.assertTrue(combined._listener(42))
            for entry in rejected:
                with self.subTest(entry=entry), mock.patch.object(combined, '_port_entries', return_value=[entry]):
                    with self.assertRaisesRegex(admission.AdmissionError, 'listener differs'):
                        combined._listener(42)
            with mock.patch.object(combined, '_port_entries', return_value=accepted):
                with self.assertRaisesRegex(admission.AdmissionError, 'listener differs'):
                    combined._listener(42)
            with mock.patch.object(combined, '_port_entries', return_value=[accepted[0]]), \
                 mock.patch.object(combined.os, 'readlink', return_value='socket:[99]'):
                with self.assertRaisesRegex(admission.AdmissionError, 'not owned'):
                    combined._listener(42)
            with mock.patch.object(combined, '_port_entries', return_value=[]):
                self.assertFalse(combined._listener(42))

    def test_port_entries_reads_both_families_and_keeps_all_listeners(self):
        v4 = 'head\n0: 0100007F:700A 00000000:0000 0A 0:0 00:0 0 10001 0 42\n'
        v6 = 'head\n0: 0000000000000000FFFF00000100007F:700A 0:0 0A 0:0 00:0 0 10001 0 43\n'
        with mock.patch.object(combined.Path, 'read_text', side_effect=[v4, v6]):
            self.assertEqual(combined._port_entries('/fake'), [('tcp', '0100007F', '42'),
                             ('tcp6', '0000000000000000FFFF00000100007F', '43')])

    def test_listener_failure_records_observed_server_for_exact_stop(self):
        observed = []
        with mock.patch.object(combined, '_scope'), mock.patch.object(jre, '_verify_resource'), \
             mock.patch.object(combined, '_inventory'), mock.patch.object(admission, '_request', return_value={}), \
             mock.patch.object(jre, '_inspect', return_value={'Pid': 42, 'StartedAt': self.binding['startedAt']}), \
             mock.patch.object(jre, '_starttime', return_value=99), mock.patch.object(combined, '_process_identity'), \
             mock.patch.object(combined.os, 'stat', return_value=SimpleNamespace(st_dev=2112, st_ino=56329)), \
             mock.patch.object(combined, '_network'), \
             mock.patch.object(combined, '_listener', side_effect=admission.AdmissionError('listener differs')):
            with self.assertRaisesRegex(admission.AdmissionError, 'listener differs'):
                combined._wait_server(self.daemon, [self.cid], self.cid, 10, observed=observed.append)
        self.assertEqual(observed, [self.binding])

    def test_pidfd_stop_signals_once_and_never_retries(self):
        running = {'Pid': 42, 'StartedAt': self.binding['startedAt']}
        exited = {'ExitCode': 143, 'StartedAt': self.binding['startedAt'], 'FinishedAt': '2026-10-29T15:00:02Z',
                  'OOMKilled': False, 'Error': ''}
        poller = mock.Mock(); poller.poll.side_effect = [[], [(7, 1)]]
        attempted = set()
        with mock.patch.object(combined, '_scope'), mock.patch.object(combined, '_inventory'), \
             mock.patch.object(admission, '_request', return_value={'State': {'Status': 'exited'}}), \
             mock.patch.object(jre, '_inspect', side_effect=[running, exited]), \
             mock.patch.object(jre, '_starttime', return_value=99), \
             mock.patch.object(combined, '_process_identity'), mock.patch.object(combined, '_port_entries', return_value=[]), \
             mock.patch.object(combined.os, 'stat', return_value=SimpleNamespace(st_dev=2112, st_ino=56329)), \
             mock.patch.object(combined.os, 'pidfd_open', return_value=7), mock.patch.object(combined.os, 'close'), \
             mock.patch.object(combined.select, 'poll', return_value=poller), \
             mock.patch.object(combined.signal, 'pidfd_send_signal') as send, mock.patch.object(combined, '_event'):
            self.assertEqual(combined._signal_stop(self.daemon, [self.cid], self.binding, attempted, 10), exited)
            with self.assertRaisesRegex(admission.AdmissionError, 'already attempted'):
                combined._signal_stop(self.daemon, [self.cid], self.binding, attempted, 10)
            send.assert_called_once()

    def test_stop_preflight_failure_is_not_retried(self):
        attempted = set()
        with mock.patch.object(combined, '_scope', side_effect=admission.AdmissionError('scope unavailable')), \
             mock.patch.object(combined.signal, 'pidfd_send_signal') as send:
            with self.assertRaisesRegex(admission.AdmissionError, 'scope unavailable'):
                combined._signal_stop(self.daemon, [self.cid], self.binding, attempted, 10)
            with self.assertRaisesRegex(admission.AdmissionError, 'already attempted'):
                combined._signal_stop(self.daemon, [self.cid], self.binding, attempted, 10)
            send.assert_not_called()

    def test_pidfd_invalid_events_do_not_prove_exit(self):
        running = {'Pid': 42, 'StartedAt': self.binding['startedAt']}
        invalid = [[(8, combined.select.POLLIN)], [(7, combined.select.POLLERR)],
                   [(7, combined.select.POLLNVAL)], [(7, combined.select.POLLIN | combined.select.POLLERR)],
                   [(7, combined.select.POLLIN | combined.select.POLLNVAL)], [(7, 0)],
                   [(7, combined.select.POLLIN), (8, combined.select.POLLIN)]]
        for events in invalid:
            with self.subTest(events=events):
                poller = mock.Mock(); poller.poll.side_effect = [[], events]
                attempted = set()
                with mock.patch.object(combined, '_scope'), mock.patch.object(combined, '_inventory'), \
                     mock.patch.object(admission, '_request', return_value={'State': {'Status': 'exited'}}), \
                     mock.patch.object(jre, '_inspect', return_value=running) as inspect, \
                     mock.patch.object(jre, '_starttime', return_value=99), \
                     mock.patch.object(combined, '_process_identity'), \
                     mock.patch.object(combined.os, 'stat', return_value=SimpleNamespace(st_dev=2112, st_ino=56329)), \
                     mock.patch.object(combined.os, 'pidfd_open', return_value=7), mock.patch.object(combined.os, 'close'), \
                     mock.patch.object(combined.select, 'poll', return_value=poller), \
                     mock.patch.object(combined.signal, 'pidfd_send_signal') as send, mock.patch.object(combined, '_event'):
                    with self.assertRaisesRegex(admission.AdmissionError, 'pidfd exit event differs'):
                        combined._signal_stop(self.daemon, [self.cid], self.binding, attempted, 10)
                    with self.assertRaisesRegex(admission.AdmissionError, 'already attempted'):
                        combined._signal_stop(self.daemon, [self.cid], self.binding, attempted, 10)
                    send.assert_called_once()
                    inspect.assert_called_once()

    def test_pidfd_signal_failure_is_not_retried(self):
        running = {'Pid': 42, 'StartedAt': self.binding['startedAt']}
        poller = mock.Mock(); poller.poll.return_value = []
        attempted = set()
        with mock.patch.object(combined, '_scope'), mock.patch.object(combined, '_inventory'), \
             mock.patch.object(admission, '_request', return_value={}), \
             mock.patch.object(jre, '_inspect', return_value=running), mock.patch.object(jre, '_starttime', return_value=99), \
             mock.patch.object(combined, '_process_identity'), \
             mock.patch.object(combined.os, 'stat', return_value=SimpleNamespace(st_dev=2112, st_ino=56329)), \
             mock.patch.object(combined.os, 'pidfd_open', return_value=7), mock.patch.object(combined.os, 'close'), \
             mock.patch.object(combined.select, 'poll', return_value=poller), mock.patch.object(combined, '_event'), \
             mock.patch.object(combined.signal, 'pidfd_send_signal', side_effect=OSError('synthetic failure')) as send:
            with self.assertRaises(OSError):
                combined._signal_stop(self.daemon, [self.cid], self.binding, attempted, 10)
            with self.assertRaisesRegex(admission.AdmissionError, 'already attempted'):
                combined._signal_stop(self.daemon, [self.cid], self.binding, attempted, 10)
            send.assert_called_once()

    def test_bad_profile_rejected_before_any_engine_request(self):
        profile = copy.deepcopy(combined.PROFILE)
        profile['config']['HostConfig']['NetworkMode'] = 'none'
        with mock.patch.object(admission, '_request') as request:
            with self.assertRaisesRegex(admission.AdmissionError, 'profile differs'):
                combined.run(profile, admission._pin(profile), self.daemon)
            request.assert_not_called()

    def test_inventory_checks_two_fixed_stopped_profiles_and_rejects_unknown(self):
        def retained(profile, cid, exitcode, started, finished):
            config = copy.deepcopy(profile['config']); host = config.pop('HostConfig')
            host.update(CapAdd=None, Binds=None, DeviceRequests=None, PidMode='', PortBindings={},
                        PublishAllPorts=False)
            host.setdefault('Devices', None)
            state = {'Status': 'exited', 'Running': False, 'Paused': False, 'Restarting': False, 'Dead': False,
                     'Pid': 0, 'ExitCode': exitcode, 'StartedAt': started, 'FinishedAt': finished,
                     'OOMKilled': False, 'Error': ''}
            return {'Id': cid, 'Name': '/' + profile['name'], 'Image': config['Image'], 'Config': config,
                    'HostConfig': host, 'State': state,
                    'Mounts': [{'Type': m['Type'], 'Source': m['Source'], 'Destination': m['Target'],
                                'RW': not m['ReadOnly']} for m in host['Mounts']]}
        gpu = retained(combined.disk.GPU_PROFILE, combined.disk.GPU_ID, 1,
                       combined.disk.GPU_START, combined.disk.GPU_FINISH)
        qdrant = retained(combined.QDRANT_PROFILE, combined.QDRANT_ID, 101,
                          combined.QDRANT_START, combined.QDRANT_FINISH)
        rows = [{'Id': combined.disk.GPU_ID}, {'Id': combined.QDRANT_ID}, {'Id': self.cid}]
        def request(method, path, daemon, payload=None):
            self.assertEqual(method, 'GET')
            if path == '/containers/json?all=1': return rows
            if combined.disk.GPU_ID in path: return gpu
            if combined.QDRANT_ID in path: return qdrant
            self.fail(path)
        with mock.patch.object(admission, '_request', side_effect=request):
            combined._inventory(self.daemon, [self.cid])
            qdrant['State']['ExitCode'] = 0
            with self.assertRaises(admission.AdmissionError):
                combined._inventory(self.daemon, [self.cid])
            qdrant['State']['ExitCode'] = 101
            rows.append({'Id': 'd' * 64})
            with self.assertRaisesRegex(admission.AdmissionError, 'unexpected or missing'):
                combined._inventory(self.daemon, [self.cid])

    def fake_chain(self, fail_write=False, fail_check_at=None):
        calls, phases, stops, checks = [], [], [], []
        first = dict(self.binding)
        second = {'id': self.cid, 'pid': 44, 'starttime': 101, 'startedAt': '2026-10-29T15:00:03Z'}
        def request(method, path, daemon, payload=None):
            calls.append((method, path))
            if path == '/info': return {'ID': 'fixture', 'DockerRootDir': admission.DATA_ROOT}
            if path.startswith('/containers/create?'): return {'Id': self.cid}
            if path.endswith('/start'): return None
            if path.endswith('/json'): return {}
            self.fail((method, path))
        def inspect(row, profile, cid, status):
            if status == 'created':
                return {'StartedAt': '0001-01-01T00:00:00Z', 'FinishedAt': '0001-01-01T00:00:00Z'}
            return {'ExitCode': 143, 'StartedAt': first['startedAt'], 'FinishedAt': '2026-10-29T15:00:02Z',
                    'OOMKilled': False, 'Error': ''}
        def wait(daemon, owned, cid, timeout, previous=None, observed=None):
            binding = first if previous is None else second
            observed(binding)
            return binding
        def client(phase, daemon, owned, binding, timeout):
            phases.append(phase)
            if fail_write: raise admission.AdmissionError('synthetic client failed')
            owned.append(('c' if phase == 'write' else 'd') * 64)
        def stop(daemon, owned, binding, attempted, timeout):
            key = (binding['pid'], binding['starttime']); self.assertNotIn(key, attempted)
            attempted.add(key); stops.append(key)
            return {'ExitCode': 143, 'StartedAt': binding['startedAt'], 'FinishedAt': '2026-10-29T15:00:02Z',
                    'OOMKilled': False, 'Error': ''}
        def check(*args):
            checks.append(len(checks) + 1)
            if len(checks) == fail_check_at:
                raise admission.AdmissionError('Qdrant protection changed')
        with ExitStack() as stack:
            patches = [(admission, '_lock', {'side_effect': self.lock}), (admission, '_mounts', {}),
                       (admission, '_request', {'side_effect': request}), (admission, '_verify_mount_isolation', {}),
                       (jre, '_verify_resource', {}), (jre, '_image', {}), (jre, '_inspect', {'side_effect': inspect}),
                       (combined, '_scope', {}), (combined, '_network', {}), (combined, '_ports_free', {}),
                       (combined, '_closed_fixture', {'return_value': {}}), (combined, '_inventory', {}),
                       (combined, '_qdrant_check', {'side_effect': check}),
                       (combined, '_runtime', {'return_value': {'bytesRead': 1, 'identities': {}}}),
                       (combined, '_recheck_runtime', {}), (combined, '_wait_server', {'side_effect': wait}),
                       (combined, '_client', {'side_effect': client}), (combined, '_signal_stop', {'side_effect': stop}),
                       (combined, '_event', {}),
                       (combined.Path, 'lstat', {'return_value': SimpleNamespace(st_mode=0o100644, st_dev=2112,
                        st_ino=56756, st_uid=10001, st_gid=10001, st_nlink=1, st_size=28672)})]
            for obj, name, kwargs in patches:
                stack.enter_context(mock.patch.object(obj, name, **kwargs))
            if fail_write or fail_check_at:
                with self.assertRaisesRegex(admission.AdmissionError, 'retained'):
                    combined.run(combined.PROFILE, admission._pin(combined.PROFILE), self.daemon)
                result = None
            else:
                result = combined.run(combined.PROFILE, admission._pin(combined.PROFILE), self.daemon)
        return result, calls, phases, stops

    def test_mock_chain_stops_restarts_reads_and_stops_without_delete(self):
        result, calls, phases, stops = self.fake_chain()
        self.assertEqual(phases, ['write', 'read'])
        self.assertEqual(stops, [(42, 99), (44, 101)])
        self.assertEqual(sum(method == 'POST' and path.endswith('/start') for method, path in calls), 2)
        self.assertEqual(len(result['clientIds']), 2)
        self.assertFalse(result['containersRemoved'])
        self.assertFalse(any(method == 'DELETE' for method, _ in calls))

    def test_mock_failed_write_attempts_one_safe_stop_without_restart(self):
        _, calls, phases, stops = self.fake_chain(fail_write=True)
        self.assertEqual(phases, ['write'])
        self.assertEqual(stops, [(42, 99)])
        self.assertEqual(sum(method == 'POST' and path.endswith('/start') for method, path in calls), 1)
        self.assertFalse(any(method == 'DELETE' for method, _ in calls))

    def test_qdrant_change_before_restart_prevents_second_start(self):
        _, calls, phases, stops = self.fake_chain(fail_check_at=4)
        self.assertEqual(phases, ['write'])
        self.assertEqual(stops, [(42, 99)])
        self.assertEqual(sum(method == 'POST' and path.endswith('/start') for method, path in calls), 1)
        self.assertFalse(any(method == 'DELETE' for method, _ in calls))

    def test_qdrant_change_after_final_stop_does_not_repeat_signal(self):
        _, calls, phases, stops = self.fake_chain(fail_check_at=5)
        self.assertEqual(phases, ['write', 'read'])
        self.assertEqual(stops, [(42, 99), (44, 101)])
        self.assertFalse(any(method == 'DELETE' for method, _ in calls))


if __name__ == '__main__':
    unittest.main()
