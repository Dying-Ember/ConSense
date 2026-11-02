"""In-memory syscall substitutes; no real gate or filesystem writes."""
from contextlib import ExitStack
import hashlib
import json
import stat
from types import SimpleNamespace
from unittest import mock
import unittest

import admission_ledger as ledger
import admission_ledger_init as init
import container_admission as admission


class InitializationTest(unittest.TestCase):
    def setUp(self):
        self.environment = ('2ef1599f-2285-492a-ad96-656237895214',
                            ((1, 2), (1, 3), (1, 4), (1, 5), (1, 6)), b'mountinfo')
        self.context = (self.environment, (42, 11), 9)
        self.parent = SimpleNamespace(st_dev=42, st_ino=11)
        self.directory = SimpleNamespace(st_dev=42, st_ino=12)
        self.marker = SimpleNamespace(st_dev=42, st_ino=13)
        self.writes = {}
        self.events = []
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.patch(ledger, '_held_lock')
        self.patch(init, '_scope', return_value=b'scope')
        self.patch(init, '_open_parent', return_value=10)
        self.patch(init, '_parent_context', return_value=self.context)
        self.patch(init, '_absent')
        self.patch(init, '_verify_inventory', return_value=b'{"inventory":"fixture"}')
        self.patch(init.secrets, 'token_hex', return_value='a' * 32)
        self.patch(init.os, 'close')
        self.patch(init.os, 'fstat', side_effect=lambda fd: self.directory if fd == 20 else self.parent)
        self.patch(init.os, 'stat', side_effect=lambda name, **kw: self.marker if name == init.MARKER else self.directory)
        self.patch(init.os, 'mkdir', side_effect=lambda *a, **kw: self.events.append('mkdir'))
        self.patch(init.os, 'fsync', side_effect=lambda fd: self.events.append('fsync:' + str(fd)))
        self.patch(init, '_read_marker')
        self.patch(ledger, '_open_directory', return_value=20)
        self.patch(ledger, '_mount_id', return_value=9)
        self.patch(ledger, '_directory_context', return_value=(self.environment, (42, 12), 9))
        self.patch(ledger, '_read_gate', side_effect=lambda _fd: json.loads(self.writes['gate.json']))
        self.patch(init, '_write_new', side_effect=self.write)

    def patch(self, target, name, **kwargs):
        return self.stack.enter_context(mock.patch.object(target, name, **kwargs))

    def write(self, fd, name, raw):
        self.events.append('write:' + name)
        self.writes[name] = raw
        return (42, 13) if name == init.MARKER else (42, 14)

    def test_pending_only_and_durable_marker_precedes_directory(self):
        result = init.initialize(3, self.context)
        marker = json.loads(self.writes[init.MARKER])
        self.assertEqual('pending_init', result['state'])
        self.assertEqual('a' * 32, result['operationId'])
        self.assertEqual(result['operationId'], marker['operationId'])
        self.assertEqual(hashlib.sha256(self.writes[init.MARKER]).hexdigest(), result['markerSha256'])
        self.assertEqual(hashlib.sha256(b'{"inventory":"fixture"}').hexdigest(), marker['inventorySha256'])
        self.assertEqual(['write:' + init.MARKER, 'fsync:10', 'mkdir', 'fsync:10',
                          'write:gate.json', 'fsync:20'], self.events)
        self.assertNotEqual('clean', result['state'])

    def test_default_inventory_and_missing_context_refuse_before_mutation(self):
        init._verify_inventory.side_effect = admission.AdmissionError('not implemented')
        with self.assertRaises(admission.AdmissionError):
            init.initialize(3, self.context)
        self.assertFalse(self.events)
        with self.assertRaises(admission.AdmissionError):
            init.initialize(3)
        self.assertFalse(self.events)

    def test_context_or_scope_drift_refuses_without_cleanup(self):
        for point in ('context', 'scope'):
            with self.subTest(point=point):
                init._parent_context.side_effect = ([self.context, self.context, None]
                                                    if point == 'context' else None)
                init._scope.side_effect = ([b'scope', b'scope', b'changed'] if point == 'scope' else None)
                with mock.patch.object(init.os, 'unlink') as unlink, mock.patch.object(init.os, 'rmdir') as rmdir:
                    with self.assertRaises(admission.AdmissionError):
                        init.initialize(3, self.context)
                    unlink.assert_not_called()
                    rmdir.assert_not_called()
                self.events.clear()
                self.writes.clear()
                init._parent_context.side_effect = None
                init._scope.side_effect = None

    def test_failure_at_every_write_boundary_never_cleans_or_marks_clean(self):
        for target in ('marker', 'parent-fsync', 'mkdir', 'gate', 'directory-fsync', 'readback'):
            with self.subTest(target=target):
                def write(fd, name, raw):
                    if (target == 'marker' and name == init.MARKER) or (target == 'gate' and name == 'gate.json'):
                        raise OSError('injected write failure')
                    return self.write(fd, name, raw)
                def sync(fd):
                    if (target == 'parent-fsync' and fd == 10) or (target == 'directory-fsync' and fd == 20):
                        raise OSError('injected fsync failure')
                    self.events.append('fsync:' + str(fd))
                init._write_new.side_effect = write
                init.os.fsync.side_effect = sync
                init.os.mkdir.side_effect = OSError('injected mkdir') if target == 'mkdir' else lambda *a, **k: None
                ledger._read_gate.side_effect = (lambda _fd: {}) if target == 'readback' else lambda _fd: json.loads(self.writes['gate.json'])
                with mock.patch.object(init.os, 'unlink') as unlink, mock.patch.object(init.os, 'rmdir') as rmdir:
                    with self.assertRaises((OSError, admission.AdmissionError)):
                        init.initialize(3, self.context)
                    unlink.assert_not_called()
                    rmdir.assert_not_called()
                self.events.clear()
                self.writes.clear()

    def test_existing_attempt_or_gate_is_not_overwritten(self):
        init._absent.side_effect = admission.AdmissionError('exists')
        with self.assertRaises(admission.AdmissionError):
            init.initialize(3, self.context)
        self.assertFalse(self.events)

    def test_changed_marker_refuses_before_directory(self):
        init._read_marker.side_effect = admission.AdmissionError('changed')
        with self.assertRaises(admission.AdmissionError):
            init.initialize(3, self.context)
        self.assertEqual(['write:' + init.MARKER, 'fsync:10'], self.events)

    def test_finish_and_reconciliation_always_reject(self):
        for fn in (init.finish, init.reconcile):
            with self.assertRaises(admission.AdmissionError):
                fn({'verified': True})


class FileBoundaryTest(unittest.TestCase):
    def test_unimplemented_inventory_always_refuses(self):
        with self.assertRaises(admission.AdmissionError):
            init._verify_inventory()

    def test_absence_check_never_accepts_an_existing_path(self):
        with mock.patch.object(init.os, 'stat', side_effect=FileNotFoundError):
            init._absent(8, 'target')
        with mock.patch.object(init.os, 'stat', return_value=object()):
            with self.assertRaises(admission.AdmissionError):
                init._absent(8, 'target')

    def test_write_is_exclusive_nofollow_and_fsynced(self):
        file_info = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_gid=0,
                                    st_nlink=1, st_dev=42, st_ino=21)
        parent_info = SimpleNamespace(st_dev=42, st_ino=22)
        with mock.patch.object(init.os, 'open', return_value=9) as opened, \
             mock.patch.object(init.os, 'fstat', side_effect=lambda fd: file_info if fd == 9 else parent_info), \
             mock.patch.object(init.os, 'stat', return_value=file_info), \
             mock.patch.object(init.os, 'write', side_effect=lambda fd, raw: len(raw)), \
             mock.patch.object(init.os, 'fsync') as sync, mock.patch.object(init.os, 'close'), \
             mock.patch.object(ledger, '_mount_id', return_value=1):
            self.assertEqual((42, 21), init._write_new(8, 'x', b'evidence'))
            flags = opened.call_args.args[1]
            self.assertTrue(flags & init.os.O_EXCL)
            self.assertTrue(flags & init.os.O_NOFOLLOW)
            sync.assert_called_once_with(9)

    def test_marker_readback_rejects_changed_bytes(self):
        info = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_gid=0,
                               st_nlink=1, st_dev=42, st_ino=21, st_size=3,
                               st_mtime_ns=1, st_ctime_ns=2)
        parent = SimpleNamespace(st_dev=42, st_ino=22)
        with mock.patch.object(init.os, 'open', return_value=9), \
             mock.patch.object(init.os, 'fstat', side_effect=lambda fd: info if fd == 9 else parent), \
             mock.patch.object(init.os, 'stat', return_value=info), mock.patch.object(init.os, 'close'), \
             mock.patch.object(ledger, '_mount_id', return_value=1), \
             mock.patch.object(ledger, '_read_bounded', return_value=b'bad'):
            with self.assertRaises(admission.AdmissionError):
                init._read_marker(8, (42, 21), b'expected')


if __name__ == '__main__':
    unittest.main()
