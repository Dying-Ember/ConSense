import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('restore_models', Path(__file__).resolve().parents[1] / 'restore-competition-models.py')
restore_models = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore_models)


def record(path, data):
    return {'path': path, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


class RestoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'nested').mkdir()
        self.parts = [b'first-', b'second']
        for index, data in enumerate(self.parts):
            (self.root / f'nested/model.bin.part-{index:03d}').write_bytes(data)
        self.model = record('nested/model.bin', b''.join(self.parts))
        self.model['parts'] = [record(f'nested/model.bin.part-{index:03d}', data) for index, data in enumerate(self.parts)]

    def test_restore_and_existing_mismatch(self):
        restore_models.restore(self.root, {'models': [self.model]})
        target = self.root / 'nested/model.bin'
        self.assertEqual(target.read_bytes(), b'first-second')
        restore_models.restore(self.root, {'models': [self.model]})
        target.write_bytes(b'other')
        with self.assertRaises(FileExistsError):
            restore_models.restore(self.root, {'models': [self.model]})
        self.assertEqual(target.read_bytes(), b'other')

    def test_corruption_and_traversal_are_rejected(self):
        (self.root / 'nested/model.bin.part-001').write_bytes(b'broken')
        with self.assertRaises(ValueError):
            restore_models.restore(self.root, {'models': [self.model]})
        self.assertFalse((self.root / 'nested/model.bin').exists())
        self.model['path'] = '../outside'
        with self.assertRaises(ValueError):
            restore_models.restore(self.root, {'models': [self.model]})

    def test_symlinked_part_is_rejected(self):
        part = self.root / 'nested/model.bin.part-000'
        part.unlink()
        os.symlink(self.root / 'nested/model.bin.part-001', part)
        with self.assertRaises(OSError):
            restore_models.restore(self.root, {'models': [self.model]})
        self.assertFalse((self.root / 'nested/model.bin').exists())


if __name__ == '__main__':
    unittest.main()
