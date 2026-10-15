import json
from pathlib import Path
import tempfile
import unittest

import mysql_backup


class MySqlBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.defaults = self.root / 'private.cnf'
        self.defaults.write_text('[client]\nuser=offline-fixture\npassword=DO_NOT_PUBLISH\n')
        self.defaults.chmod(0o600)
        self.calls = []
        self.empty = True
        self.restricted = True

    def tearDown(self):
        self.temp.cleanup()

    def runner(self, argv, input_path=None):
        self.calls.append((argv, input_path))
        if argv[0] == 'mysqldump':
            path = next(arg.split('=', 1)[1] for arg in argv if arg.startswith('--result-file='))
            Path(path).write_bytes(b'CREATE TABLE source_record (id BIGINT);\nINSERT INTO source_record VALUES (1);\n')
            return ''
        if '--execute=SHOW GRANTS' in argv:
            return 'GRANT USAGE ON *.* TO recovery@localhost\nGRANT ALL PRIVILEGES ON `isolated_restore`.* TO recovery@localhost\n' if self.restricted else 'GRANT ALL PRIVILEGES ON *.* TO root@localhost\n'
        if any(arg.startswith('--execute=') for arg in argv):
            return '0\t0\n' if self.empty else '1\t0\n'
        return ''

    def test_backup_is_hash_bound_and_restore_requires_an_empty_explicit_target(self):
        self.assertFalse(mysql_backup.backup(self.defaults, 'consense', self.root / 'backup', False, self.runner)['executed'])
        self.assertEqual([], self.calls)
        saved = mysql_backup.backup(self.defaults, 'consense', self.root / 'backup', True, self.runner)
        receipt = self.root / 'backup/backup.json'
        result = mysql_backup.restore(self.defaults, 'isolated_restore', receipt, saved['receiptSha256'], True, self.runner)
        self.assertTrue(result['executed'])
        self.assertEqual('isolated_restore', result['targetDatabase'])
        self.assertFalse(result['targetAccepted'])
        self.assertNotIn('DO_NOT_PUBLISH', json.dumps(saved) + json.dumps(result) + json.dumps(self.calls, default=str))

    def test_changed_backup_or_existing_target_never_enters_restore(self):
        saved = mysql_backup.backup(self.defaults, 'consense', self.root / 'backup', True, self.runner)
        receipt = self.root / 'backup/backup.json'
        self.empty = False
        with self.assertRaisesRegex(ValueError, 'empty'):
            mysql_backup.restore(self.defaults, 'isolated_restore', receipt, saved['receiptSha256'], True, self.runner)
        self.assertFalse(any(input_path is not None for _, input_path in self.calls))
        self.calls.clear()
        (self.root / 'backup/snapshot.sql').write_bytes(b'changed bytes')
        with self.assertRaisesRegex(ValueError, 'asset'):
            mysql_backup.restore(self.defaults, 'isolated_restore', receipt, saved['receiptSha256'], True, self.runner)
        self.assertEqual([], self.calls)

    def test_global_restore_account_is_rejected_before_import(self):
        saved = mysql_backup.backup(self.defaults, 'consense', self.root / 'backup', True, self.runner)
        self.restricted = False
        with self.assertRaisesRegex(ValueError, 'restricted'):
            mysql_backup.restore(self.defaults, 'isolated_restore', self.root / 'backup/backup.json', saved['receiptSha256'], True, self.runner)
        self.assertFalse(any(input_path is not None for _, input_path in self.calls))


if __name__ == '__main__':
    unittest.main()
