"""In-memory syscall substitutes; no filesystem, daemon or container operations."""
import errno
import json
import stat
import sys
from types import SimpleNamespace
from unittest import mock
import unittest

import admission_ledger as ledger
import container_admission as admission


CONTEXT = (('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', ((42, 7),) * 5, b'mountinfo'), (42, 7), 1)
CHANGED_CONTEXT = (('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', ((42, 7),) * 5, b'mountinfo-changed'), (42, 7), 1)


def info(mode=stat.S_IFREG | 0o600, inode=7, uid=0, gid=0, links=1):
    return SimpleNamespace(st_mode=mode, st_dev=42, st_ino=inode, st_uid=uid, st_gid=gid,
                           st_nlink=links, st_size=10, st_mtime_ns=1, st_ctime_ns=2)


class LedgerTest(unittest.TestCase):
    def test_reject_invalid_and_duplicate_json(self):
        for raw in (b'{', b'{"state":"clean","state":"pending"}', b'\xff'):
            with self.subTest(raw=raw), self.assertRaises(admission.AdmissionError):
                ledger._json(raw)

    def test_scope_owner_exact_and_state_identity(self):
        state = {'coordinator': ledger.COORDINATOR, 'ticket01Gate': 'closed',
                 'heavyTaskGate': 'ordered-single-heavy-task', 'heavyTaskOwner': ledger.OWNER,
                 'heavyTaskScope': ledger.SCOPE}
        with mock.patch.object(ledger.os, 'open', return_value=19), \
             mock.patch.object(ledger.os, 'close'), \
             mock.patch.object(ledger.os, 'fstat', return_value=info()), \
             mock.patch.object(ledger.os, 'lstat', return_value=info()):
            for field in ('coordinator', 'ticket01Gate', 'heavyTaskGate', 'heavyTaskOwner', 'heavyTaskScope'):
                bad = dict(state, **{field: 'wrong'})
                with mock.patch.object(ledger, '_read_bounded', return_value=json.dumps(bad).encode()):
                    with self.subTest(field=field), self.assertRaises(admission.AdmissionError):
                        ledger._scope()
            with mock.patch.object(ledger, '_read_bounded', return_value=json.dumps(state).encode()):
                self.assertEqual(json.dumps(state).encode(), ledger._scope())
            with mock.patch.object(ledger.os, 'lstat', return_value=info(inode=99)):
                with self.assertRaises(admission.AdmissionError):
                    ledger._scope()

    def test_missing_or_extra_ledger_entry_is_blocked(self):
        for names in ([], ['gate.json', '.pending-incomplete'], ['gate.json', 'unknown']):
            with mock.patch.object(ledger.os, 'listdir', return_value=names), \
                 mock.patch.object(ledger.os, 'open') as opened:
                with self.subTest(names=names), self.assertRaises(admission.AdmissionError):
                    ledger._read_gate(10)
                opened.assert_not_called()

    def test_gate_rejects_unsafe_file_and_replacement(self):
        for bad in (info(mode=stat.S_IFREG | 0o644), info(uid=1), info(links=2),
                    info(mode=stat.S_IFLNK | 0o777)):
            with mock.patch.object(ledger.os, 'listdir', return_value=['gate.json']), \
                 mock.patch.object(ledger.os, 'open', return_value=11), \
                 mock.patch.object(ledger.os, 'close'), \
                 mock.patch.object(ledger, '_mount_id', return_value=1), \
                 mock.patch.object(ledger.os, 'fstat', return_value=bad), \
                 mock.patch.object(ledger.os, 'stat', return_value=bad):
                with self.subTest(mode=bad.st_mode, uid=bad.st_uid, links=bad.st_nlink), \
                     self.assertRaises(admission.AdmissionError):
                    ledger._read_gate(10)
        with mock.patch.object(ledger.os, 'listdir', return_value=['gate.json']), \
             mock.patch.object(ledger.os, 'open', return_value=11), \
             mock.patch.object(ledger.os, 'close'), \
             mock.patch.object(ledger, '_mount_id', return_value=1), \
             mock.patch.object(ledger.os, 'fstat', return_value=info()), \
             mock.patch.object(ledger.os, 'stat', return_value=info(inode=8)):
            with self.assertRaises(admission.AdmissionError):
                ledger._read_gate(10)

    def test_directory_requires_root_private_same_ext4_mount(self):
        for failure in (None, 'writable', 'mount', 'name', 'mode'):
            with self.subTest(failure=failure):
                nodes = {fd: info(mode=stat.S_IFDIR | (0o700 if fd == 15 else 0o755), inode=fd)
                         for fd in range(10, 16)}
                if failure == 'writable': nodes[12].st_mode |= 0o020
                if failure == 'mode': nodes[15].st_mode = stat.S_IFDIR | 0o755
                def named(_part, *, dir_fd, follow_symlinks):
                    return info(mode=nodes[dir_fd + 1].st_mode,
                                inode=99 if failure == 'name' and dir_fd == 11 else dir_fd + 1)
                def mount(fd):
                    return 2 if failure == 'mount' and fd == 12 else 1
                records = {1: {'filesystem': 'ext4', 'device': '0:42'},
                           2: {'filesystem': 'ext4', 'device': '0:42'}}
                with mock.patch.object(ledger.os, 'open', side_effect=range(10, 16)), \
                     mock.patch.object(ledger.os, 'fstat', side_effect=lambda fd: nodes[fd]), \
                     mock.patch.object(ledger.os, 'stat', side_effect=named), \
                     mock.patch.object(ledger.os, 'lstat', return_value=nodes[15]), \
                     mock.patch.object(ledger.os, 'close'), \
                     mock.patch.object(ledger.Path, 'read_bytes', return_value=b'fixture'), \
                     mock.patch.object(admission, '_parse_mountinfo', return_value=records), \
                     mock.patch.object(ledger, '_mount_id', side_effect=mount):
                    if failure is None:
                        self.assertEqual(15, ledger._open_directory())
                    else:
                        with self.assertRaises(admission.AdmissionError):
                            ledger._open_directory()

    def test_begin_rejects_bad_state_before_creating(self):
        clean = {'schema': 1, 'generation': 0, 'state': 'clean'}
        invalid = [dict(clean, state='pending'), dict(clean, generation=True),
                   dict(clean, schema=True), dict(clean, extra=1), dict(clean, generation=-1)]
        with mock.patch.object(ledger, '_held_lock'), mock.patch.object(ledger, '_scope', return_value=b'ok'), \
             mock.patch.object(ledger, '_open_directory', return_value=30), \
             mock.patch.object(ledger, '_directory_context', return_value=CONTEXT), \
             mock.patch.object(ledger.os, 'close'), mock.patch.object(ledger.os, 'open') as opened:
            for state in invalid:
                with mock.patch.object(ledger, '_read_gate', return_value=state):
                    with self.subTest(state=state), self.assertRaises(admission.AdmissionError):
                        ledger.begin(3, CONTEXT)
            opened.assert_not_called()

    def test_begin_persists_pending_and_never_cleans_on_failures(self):
        for failure in (None, 'context-initial', 'write', 'file-fsync', 'scope', 'context-before', 'replace',
                        'dir-fsync', 'readback', 'context-after'):
            with self.subTest(failure=failure):
                events, content = [], bytearray()
                clean = {'schema': 1, 'generation': 4, 'state': 'clean'}
                renamed = [False]
                def write(fd, raw):
                    events.append('write')
                    if failure == 'write': raise OSError('injected')
                    content.extend(raw)
                    return len(raw)
                def sync(fd):
                    kind = 'file-fsync' if fd == 40 else 'dir-fsync'
                    events.append(kind)
                    if failure == kind: raise OSError('injected')
                def replace(*args, **kwargs):
                    events.append('replace')
                    if failure == 'replace': raise OSError('injected')
                    renamed[0] = True
                def read_gate(_directory):
                    if not renamed[0]: return clean
                    if failure == 'readback': raise admission.AdmissionError('injected')
                    return json.loads(content)
                scope = mock.Mock(side_effect=[b'original', b'changed' if failure == 'scope' else b'original', b'original'])
                contexts = [CHANGED_CONTEXT if failure == 'context-initial' else CONTEXT,
                            CHANGED_CONTEXT if failure == 'context-before' else CONTEXT,
                            CHANGED_CONTEXT if failure == 'context-after' else CONTEXT]
                with mock.patch.object(ledger, '_held_lock'), mock.patch.object(ledger, '_scope', scope), \
                     mock.patch.object(ledger, '_open_directory', return_value=30), \
                     mock.patch.object(ledger, '_directory_context', side_effect=contexts), \
                     mock.patch.object(ledger, '_mount_id', return_value=1), \
                     mock.patch.object(ledger, '_read_gate', side_effect=read_gate), \
                     mock.patch.object(ledger.secrets, 'token_hex', return_value='a' * 32), \
                     mock.patch.object(ledger.os, 'open', return_value=40), \
                     mock.patch.object(ledger.os, 'fstat', return_value=info()), \
                     mock.patch.object(ledger.os, 'stat', return_value=info()), \
                     mock.patch.object(ledger.os, 'write', side_effect=write), \
                     mock.patch.object(ledger.os, 'fsync', side_effect=sync), \
                     mock.patch.object(ledger.os, 'replace', side_effect=replace), \
                     mock.patch.object(ledger.os, 'unlink') as unlink, mock.patch.object(ledger.os, 'close'):
                    if failure is None:
                        result = ledger.begin(3, CONTEXT)
                        self.assertEqual('pending', result['state'])
                        self.assertEqual(5, result['generation'])
                        self.assertEqual(['write', 'file-fsync', 'replace', 'dir-fsync'], events)
                    else:
                        with self.assertRaises((OSError, admission.AdmissionError)):
                            ledger.begin(3, CONTEXT)
                        if failure in ('context-initial', 'write', 'file-fsync', 'scope', 'context-before', 'replace'):
                            self.assertFalse(renamed[0])
                        else:
                            self.assertTrue(renamed[0])
                    unlink.assert_not_called()

    def test_missing_and_malformed_expected_context_reject_before_lock(self):
        invalid = [None, (), True, (CONTEXT[0], CONTEXT[1], True),
                   ((CONTEXT[0][0], CONTEXT[0][1], b''), CONTEXT[1], 1),
                   ((CONTEXT[0][0], CONTEXT[0][1][:-1], b'mountinfo'), CONTEXT[1], 1)]
        with mock.patch.object(ledger, '_held_lock') as locked:
            for value in invalid:
                with self.subTest(value=value), self.assertRaises(admission.AdmissionError):
                    ledger.begin(3, value)
            with self.assertRaises(admission.AdmissionError):
                ledger.begin(3)
            locked.assert_not_called()

    def test_context_rejects_path_mount_and_environment_changes(self):
        for change in (None, 'path', 'mount', 'environment'):
            with self.subTest(change=change):
                environments = ['same', 'changed' if change == 'environment' else 'same']
                def fake_stat(fd):
                    return info(inode=99 if change == 'path' and fd == 31 else 7)
                def mount(fd):
                    return 2 if change == 'mount' and fd == 31 else 1
                with mock.patch.object(ledger, '_environment', side_effect=environments), \
                     mock.patch.object(ledger, '_open_directory', return_value=31), \
                     mock.patch.object(ledger, '_mount_id', side_effect=mount), \
                     mock.patch.object(ledger.os, 'fstat', side_effect=fake_stat), \
                     mock.patch.object(ledger.os, 'close') as close:
                    if change is None:
                        self.assertEqual(('same', (42, 7), 1), ledger._directory_context(30))
                    else:
                        with self.assertRaises(admission.AdmissionError):
                            ledger._directory_context(30)
                    close.assert_called_once_with(31)

    def test_environment_requires_valid_boot_and_binds_all_namespaces(self):
        boot = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
        with mock.patch.object(ledger.Path, 'read_text', return_value=boot), \
             mock.patch.object(ledger.Path, 'read_bytes', return_value=b'mountinfo'), \
             mock.patch.object(ledger.os, 'stat', return_value=info()) as observe:
            self.assertEqual((boot, ((42, 7),) * 5, b'mountinfo'), ledger._environment())
            self.assertEqual([mock.call('/proc/self/ns/' + n) for n in ('mnt', 'user', 'pid', 'cgroup', 'time')],
                             observe.call_args_list)
        with mock.patch.object(ledger.Path, 'read_text', return_value='not-a-boot-id'):
            with self.assertRaises(admission.AdmissionError):
                ledger._environment()

    def test_finish_is_not_a_boolean_clear(self):
        for argument in (True, False, {'verified': True}, None):
            with self.subTest(argument=argument), self.assertRaises(admission.AdmissionError):
                ledger.finish(argument)

    def test_same_descriptor_must_hold_the_lock(self):
        constants = SimpleNamespace(F_GETFD=1, FD_CLOEXEC=1, LOCK_EX=2, LOCK_NB=4, LOCK_UN=8)
        for owner in ('supplied', 'other', 'none'):
            def flock(fd, operation):
                if operation == constants.LOCK_UN: return
                if fd == 99 and owner != 'none': raise BlockingIOError(errno.EAGAIN, 'busy')
                if fd == 3 and owner == 'other': raise BlockingIOError(errno.EAGAIN, 'busy')
            constants.flock = flock
            constants.fcntl = lambda fd, operation: 1
            with mock.patch.dict(sys.modules, {'fcntl': constants}), \
                 mock.patch.object(ledger, '_exclusive_owned'), \
                 mock.patch.object(ledger.os, 'fstat', return_value=info()), \
                 mock.patch.object(ledger.os, 'lstat', return_value=info()), \
                 mock.patch.object(ledger.os, 'open', return_value=99), mock.patch.object(ledger.os, 'close'):
                if owner == 'supplied':
                    ledger._held_lock(3)
                else:
                    with self.subTest(owner=owner), self.assertRaises(admission.AdmissionError):
                        ledger._held_lock(3)

    def test_lock_record_requires_current_pid_and_exclusive_lock(self):
        good = '4: FLOCK ADVISORY WRITE 123 00:2a:7 0 EOF\n'
        with mock.patch.object(ledger.os, 'getpid', return_value=123):
            for raw in (good.replace('WRITE', 'READ'), good.replace('123', '124'),
                        good.replace('00:2a:7', '00:2a:8'), good + good, ''):
                with mock.patch.object(ledger.Path, 'read_text', return_value=raw):
                    with self.subTest(raw=raw), self.assertRaises(admission.AdmissionError):
                        ledger._exclusive_owned(info())
            with mock.patch.object(ledger.Path, 'read_text', return_value=good):
                ledger._exclusive_owned(info())


if __name__ == '__main__':
    unittest.main()
