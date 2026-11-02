"""Synthetic listener evidence only: no real sockets, services or /proc reads."""
from contextlib import contextmanager
from types import SimpleNamespace
from unittest import TestCase, main, mock

import container_admission as admission
import h2_listener_observation as obs


HEADER = b'  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n'


def line(address='0100007F', port='700A', state='0A', inode='123'):
    return ('  0: ' + address + ':' + port + ' 00000000:0000 ' + state +
            ' 00000000:00000000 00:00000000 00000000 10001 0 ' + inode + '\n').encode()


class ParserTests(TestCase):
    def parse(self, raw, family='tcp'):
        found = []
        obs._parse_table(raw, family, found)
        return found

    def test_tcp_tcp6_and_additional_listener_keep_exact_rows(self):
        raw = line() + line('00000000', inode='124') + line(port='700B', inode='125')
        entries = self.parse(HEADER + raw)
        self.assertEqual([row['inode'] for row in entries], ['123', '124'])
        self.assertEqual(entries[0]['raw'], line().decode().rstrip('\n'))
        v6 = self.parse(HEADER + line('0000000000000000FFFF00000100007F'), 'tcp6')
        self.assertEqual((v6[0]['family'], v6[0]['address']),
                         ('tcp6', '0000000000000000FFFF00000100007F'))

    def test_truncation_overflow_bad_inode_and_malformed_row_fail(self):
        cases = [HEADER + line()[:-1], b'x' * (obs.MAX_TABLE + 1), HEADER + line(inode='0'),
                 HEADER + b'bad row\n', HEADER + line() * (obs.MAX_MATCHES + 1)]
        for raw in cases:
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                self.parse(raw)

    def test_partial_rows_are_kept_if_later_data_is_malformed(self):
        entries = []
        with self.assertRaises(ValueError):
            obs._parse_table(HEADER + line() + b'bad row\n', 'tcp', entries)
        self.assertEqual(entries[0]['inode'], '123')


class CaptureTests(TestCase):
    def setUp(self):
        self.identity = (99, (4, 123), 'boot')
        self.row = {'family': 'tcp', 'raw': line().decode().rstrip('\n'),
                    'address': '0100007F', 'inode': '123'}

    def test_stable_snapshot_reports_owned_and_absent(self):
        for fds, expected in (({'123'}, 'owned'), ({'456'}, 'absent')):
            with self.subTest(fds=fds), mock.patch.object(obs, '_identity', return_value=self.identity), \
                 mock.patch.object(obs, '_tables', side_effect=lambda _, rows: rows.append(dict(self.row))), \
                 mock.patch.object(obs, '_fds', return_value=fds):
                result = obs.capture(42, 99, 'boot', (4, 123))
                self.assertEqual(result['status'], 'observed')
                self.assertEqual(result['listeners'][0]['ownership'], expected)
                self.assertEqual(result['verificationListeners'][0]['ownership'], expected)

    def test_identity_or_netns_change_never_claims_ownership(self):
        for changed in ((100, (4, 123), 'boot'), (99, (4, 124), 'boot'), (99, (4, 123), 'other')):
            with self.subTest(changed=changed), mock.patch.object(obs, '_identity', side_effect=[self.identity, changed]), \
                 mock.patch.object(obs, '_tables', side_effect=lambda _, rows: rows.append(dict(self.row))), \
                 mock.patch.object(obs, '_fds', return_value={'123'}):
                result = obs.capture(42, 99, 'boot', (4, 123))
                self.assertEqual(result['status'], 'unknown')
                self.assertEqual(result['listeners'][0]['ownership'], 'unknown')

    def test_fd_access_loss_or_change_never_claims_absence(self):
        for side in ([PermissionError('denied')], [FileNotFoundError('vanished')], [{'123'}, set()]):
            with self.subTest(side=side), mock.patch.object(obs, '_identity', return_value=self.identity), \
                 mock.patch.object(obs, '_tables', side_effect=lambda _, rows: rows.append(dict(self.row))), \
                 mock.patch.object(obs, '_fds', side_effect=side):
                result = obs.capture(42, 99, 'boot', (4, 123))
                self.assertEqual(result['status'], 'unknown')
                self.assertEqual(result['listeners'][0]['ownership'], 'unknown')

    def test_listener_table_change_retains_first_and_verification_rows(self):
        def tables(_, rows):
            rows.append(dict(self.row, inode='123' if not rows_seen else '456'))
            rows_seen.append(True)
        rows_seen = []
        with mock.patch.object(obs, '_identity', return_value=self.identity), \
             mock.patch.object(obs, '_tables', side_effect=tables), mock.patch.object(obs, '_fds', return_value={'123'}):
            result = obs.capture(42, 99, 'boot', (4, 123))
        self.assertEqual(result['status'], 'unknown')
        self.assertEqual(result['listeners'][0]['inode'], '123')
        self.assertEqual(result['verificationListeners'][0]['inode'], '456')
        self.assertEqual(result['verificationListeners'][0]['ownership'], 'unknown')

    def test_invalid_bound_identity_is_rejected_before_sampling(self):
        with mock.patch.object(obs, '_identity') as identity:
            with self.assertRaises(ValueError):
                obs.capture(42, 99, 'boot', (4, '123'))
            identity.assert_not_called()

    def test_identity_error_is_unknown(self):
        with mock.patch.object(obs, '_identity', side_effect=admission.AdmissionError('start unavailable')):
            self.assertEqual(obs.capture(42, 99, 'boot', (4, 123))['status'], 'unknown')

    def test_fd_overflow_and_readlink_failure_are_detected(self):
        @contextmanager
        def entries(count):
            yield iter(SimpleNamespace(path=str(i)) for i in range(count))
        with mock.patch.object(obs.os, 'scandir', return_value=entries(obs.MAX_FDS + 1)), \
             mock.patch.object(obs.os, 'readlink', return_value='socket:[123]'):
            with self.assertRaisesRegex(ValueError, 'too many file descriptors'):
                obs._fds(42)
        with mock.patch.object(obs.os, 'scandir', return_value=entries(1)), \
             mock.patch.object(obs.os, 'readlink', side_effect=PermissionError('denied')):
            with self.assertRaises(PermissionError):
                obs._fds(42)


if __name__ == '__main__':
    main()
