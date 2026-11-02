import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from fixed_gpu_profile_manifest import generate
from request_stage import encoded, envelope

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'vetting_eval'))
from fixed_gpu_profiles import load_profiles


class ProfileManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / 'gpu.json'
        self.output = self.root / 'profiles.json'
        self.stages = {
            'document-embedding': {'mode': 'job', 'profile': {'label': '嵌入', 'nested': {'b': 2, 'a': 1}}},
            'query-embedding-rerank': {'mode': 'model-session', 'profile': {'adapter': 'model'}},
            'business-llm': {'mode': 'job', 'profile': {'model': 'fixture'}},
            'paddle': {'mode': 'session', 'profile': {'nativeIdentity': {'name': 'fixture'}}},
            'mineru': {'mode': 'session', 'profile': {'nativeIdentity': {'name': 'other'}}},
        }

    def write(self, value):
        raw = value if isinstance(value, bytes) else encoded(value)
        self.config.write_bytes(raw)
        return hashlib.sha256(raw).hexdigest()

    def test_envelope_and_loader_compatibility(self):
        pin = self.write({'stages': self.stages})
        digest = generate(self.config, pin, self.output)
        raw = self.output.read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), digest)
        manifest = json.loads(raw)
        self.assertEqual(set(manifest), {'schema', 'profiles'})
        for name, stage in self.stages.items():
            operation = envelope(name, b'{}', {'source': 'fixture'}, stage['profile'])
            self.assertEqual(operation['profileSha256'], manifest['profiles'][name])
        self.assertEqual({name: manifest['profiles'][name] for name in
                          ('document-embedding', 'query-embedding-rerank', 'business-llm')},
                         load_profiles(self.output, digest))

    def test_optional_fixed_reference_profile_preserves_delivery_stages(self):
        from delivery import STAGES
        self.assertNotIn('fixed-reference-query', STAGES)
        self.stages['fixed-reference-query'] = {'mode':'model-session', 'profile':{'adapter':'model'}}
        pin = self.write({'stages':self.stages})
        digest = generate(self.config, pin, self.output)
        loaded = load_profiles(self.output, digest)
        operation = envelope('fixed-reference-query', b'{}', {'source':'synthetic'}, self.stages['fixed-reference-query']['profile'])
        self.assertEqual(loaded['fixed-reference-query'], operation['profileSha256'])

    def test_invalid_configs_and_sha_do_not_create_output(self):
        invalid = [
            {'stages': {}},
            {'stages': {**self.stages, 'unknown': self.stages['paddle']}},
            {'stages': {**self.stages, 'paddle': {'mode': 'bad', 'profile': {'a': 1}}}},
            {'stages': {**self.stages, 'paddle': {'mode': 'job', 'profile': {}}}},
            {'stages': {**self.stages, 'paddle': {'mode': 'job', 'profile': []}}},
            b'{"stages":{},"stages":{}}',
            b'{"stages":{"document-embedding":{"mode":"job","profile":{"x":1,"x":2}}}}',
            b'{"stages":{"document-embedding":{"mode":"job","profile":{"x":NaN}}}}',
        ]
        for item in invalid:
            with self.subTest(item=item):
                pin = self.write(item)
                with self.assertRaises(ValueError):
                    generate(self.config, pin, self.output)
                self.assertFalse(self.output.exists())
        pin = self.write({'stages': self.stages})
        for bad_pin in ('0' * 64, pin.upper(), 'invalid'):
            with self.subTest(pin=bad_pin), self.assertRaises(ValueError):
                generate(self.config, bad_pin, self.output)
            self.assertFalse(self.output.exists())
        pin = self.write(b' ' * (1024 * 1024 + 1))
        with self.assertRaises(ValueError):
            generate(self.config, pin, self.output)
        self.assertFalse(self.output.exists())

    def test_exclusive_output_and_cli(self):
        pin = self.write({'stages': self.stages})
        script = Path(__file__).with_name('fixed_gpu_profile_manifest.py')
        result = subprocess.run([sys.executable, str(script), '--config', str(self.config),
                                 '--config-sha256', pin, '--output', str(self.output)],
                                capture_output=True, text=True, check=True)
        before = self.output.read_bytes()
        self.assertEqual(result.stdout.strip(), hashlib.sha256(before).hexdigest())
        with self.assertRaises(FileExistsError):
            generate(self.config, pin, self.output)
        self.assertEqual(self.output.read_bytes(), before)
        with self.assertRaises(ValueError):
            generate(self.config, pin, Path('relative.json'))

    def test_changed_output_readback_fails_and_preserves_file(self):
        pin = self.write({'stages': self.stages})
        open_file = os.open

        def corrupt_before_read(path, flags, *args):
            if Path(path) == self.output and flags & os.O_CREAT == 0:
                original = self.output.read_bytes()
                self.output.write_bytes(b'X' + original[1:])
            return open_file(path, flags, *args)

        with patch('fixed_gpu_profile_manifest.os.open', side_effect=corrupt_before_read):
            with self.assertRaisesRegex(ValueError, 'output readback differs'):
                generate(self.config, pin, self.output)
        self.assertTrue(self.output.exists())
        self.assertTrue(self.output.read_bytes().startswith(b'X'))

    @unittest.skipUnless(os.name == 'posix' and hasattr(os, 'mkfifo'), 'POSIX FIFO required')
    def test_fifo_without_writer_rejected_without_blocking(self):
        script = Path(__file__).with_name('fixed_gpu_profile_manifest.py')
        os.mkfifo(self.config)
        result = subprocess.run([sys.executable, str(script), '--config', str(self.config),
                                 '--config-sha256', '0' * 64, '--output', str(self.output)],
                                capture_output=True, text=True, timeout=3)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('configuration must be a bounded regular file', result.stderr)
        self.assertFalse(self.output.exists())

        self.config.unlink()
        pin = self.write({'stages': self.stages})
        child = '''\
import os, sys
from pathlib import Path
from unittest.mock import patch
from fixed_gpu_profile_manifest import generate
output = Path(sys.argv[3])
original_open = os.open
def replace_with_fifo(path, flags, *args):
    if Path(path) == output and flags & os.O_CREAT == 0:
        output.unlink()
        os.mkfifo(output)
    return original_open(path, flags, *args)
with patch('fixed_gpu_profile_manifest.os.open', side_effect=replace_with_fifo):
    generate(sys.argv[1], sys.argv[2], output)
'''
        result = subprocess.run([sys.executable, '-c', child, str(self.config), pin, str(self.output)],
                                cwd=str(script.parent), capture_output=True, text=True, timeout=3)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('output readback differs', result.stderr)
        self.assertTrue(self.output.exists())


if __name__ == '__main__':
    unittest.main()
