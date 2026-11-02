"""Exercise the release guard through its command-line interface."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ReleaseGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'release'
        self.root.mkdir()
        (self.root / 'web').mkdir()
        (self.root / 'app.jar').write_bytes(b'jar')
        (self.root / 'web/index.html').write_bytes(b'web')
        self.manifest = self.base / 'manifest.json'
        self.rows = [
            {'path': 'app.jar', 'sha256': hashlib.sha256(b'jar').hexdigest()},
            {'path': 'web/index.html', 'sha256': hashlib.sha256(b'web').hexdigest()},
        ]
        self.write_manifest()

    def write_manifest(self):
        self.manifest.write_text(json.dumps({'files': self.rows}), encoding='utf-8')
        self.pin = hashlib.sha256(self.manifest.read_bytes()).hexdigest()

    def run_guard(self, pin=None):
        script = Path(__file__).with_name('release_guard.py')
        return subprocess.run([sys.executable, str(script), '--root', str(self.root),
                               '--manifest', str(self.manifest), '--manifest-sha256',
                               self.pin if pin is None else pin], capture_output=True, text=True)

    def test_complete_release_passes(self):
        result = self.run_guard()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({'status': 'ok', 'files': 2, 'root': str(self.root.resolve()),
                          'manifest': str(self.manifest.resolve()), 'manifestSha256': self.pin},
                         json.loads(result.stdout))

    def test_wrong_manifest_pin_is_rejected(self):
        result = self.run_guard('0' * 64)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual('manifest SHA256 mismatch', json.loads(result.stdout)['error'])

    def test_manifest_pin_is_required_and_must_be_lowercase_sha256(self):
        script = Path(__file__).with_name('release_guard.py')
        result = subprocess.run([sys.executable, str(script), '--root', str(self.root),
                                 '--manifest', str(self.manifest)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        for pin in ('bad', 'A' * 64):
            with self.subTest(pin=pin):
                result = self.run_guard(pin)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertEqual('invalid manifest SHA256 pin', json.loads(result.stdout)['error'])

    def test_manifest_replacement_is_rejected_before_parsing(self):
        self.manifest.write_bytes(self.manifest.read_bytes() + b' ')
        result = self.run_guard()
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual('manifest SHA256 mismatch', json.loads(result.stdout)['error'])

    def assert_rejected(self):
        result = self.run_guard()
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual('error', json.loads(result.stdout)['status'])

    def test_missing_extra_and_changed_file_are_rejected(self):
        app = self.root / 'app.jar'
        app.unlink()
        self.assert_rejected()
        app.write_bytes(b'jar')
        extra = self.root / 'extra.jar'
        extra.write_bytes(b'extra')
        self.assert_rejected()
        extra.unlink()
        app.write_bytes(b'changed')
        self.assert_rejected()

    def test_invalid_paths_and_duplicate_entries_are_rejected(self):
        for path in ('../outside', '/outside', 'web/../app.jar', 'web//index.html',
                     'C:/outside', 'web\\index.html', 'web/./index.html'):
            with self.subTest(path=path):
                self.rows[0]['path'] = path
                self.write_manifest()
                self.assert_rejected()
        self.rows[0]['path'] = 'app.jar'
        self.rows.append(dict(self.rows[0]))
        self.write_manifest()
        self.assert_rejected()

    def test_manifest_inside_release_is_rejected(self):
        self.manifest = self.root / 'manifest.json'
        self.write_manifest()
        self.assert_rejected()

    def test_malformed_inventory_is_rejected(self):
        self.rows[0]['sha256'] = 'bad'
        self.write_manifest()
        self.assert_rejected()
        self.manifest.write_text('{"files": [], "files": []}', encoding='utf-8')
        self.pin = hashlib.sha256(self.manifest.read_bytes()).hexdigest()
        self.assert_rejected()

    def test_symbolic_link_is_rejected(self):
        target = self.root / 'web/index.html'
        target.unlink()
        try:
            target.symlink_to(self.root / 'app.jar')
        except OSError as error:
            self.skipTest('symbolic links unavailable: ' + str(error))
        self.assert_rejected()

    def test_directory_link_is_rejected(self):
        external = self.base / 'external'
        external.mkdir()
        (external / 'index.html').write_bytes(b'web')
        target = self.root / 'web/index.html'
        target.unlink()
        (self.root / 'web').rmdir()
        try:
            (self.root / 'web').symlink_to(external, target_is_directory=True)
        except OSError as error:
            self.skipTest('directory symbolic links unavailable: ' + str(error))
        self.assert_rejected()

    @unittest.skipUnless(hasattr(os, 'mkfifo'), 'requires FIFO support')
    def test_special_file_is_rejected(self):
        os.mkfifo(self.root / 'pipe')
        self.assert_rejected()


if __name__ == '__main__':
    unittest.main()
