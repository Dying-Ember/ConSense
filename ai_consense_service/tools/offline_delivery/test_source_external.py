"""Public startup contract, using disposable processes and no business runtime."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import source_service_entrypoint as entrypoint


class SourceExternalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tools = self.root / 'tools'
        (self.tools / 'vetting_eval').mkdir(parents=True)
        (self.tools / 'offline_delivery').mkdir()
        self.launcher = self.tools / 'offline_delivery/source_service_entrypoint.py'
        self.launcher.write_bytes(Path(entrypoint.__file__).read_bytes())
        (self.root / 'vetting').mkdir()
        (self.root / 'advisory').mkdir()
        (self.root / 'scope.json').write_text('{}', encoding='utf-8')
        self.env = {**os.environ, 'CONSENSE_WORKSPACE_ROOT': str(self.root),
            'CONSENSE_SL_SCOPE_MANIFEST': str(self.root / 'scope.json'),
            'CONSENSE_SOURCE_VETTING_STATE': str(self.root / 'vetting'),
            'CONSENSE_SOURCE_ADVISORY_STATE': str(self.root / 'advisory'),
            'CONSENSE_SOURCE_VETTING_PORT': '28681', 'CONSENSE_SOURCE_ADVISORY_PORT': '28685'}
        self.script = self.tools / 'vetting_eval/oer_unified_server.py'
        self.script.write_text('', encoding='utf-8')

    def test_image_uses_named_account_and_only_external_entrypoint(self):
        root = Path(__file__).parent
        dockerfile = (root / 'Dockerfile.source-external').read_text(encoding='utf-8')
        self.assertIn('USER consense:consense', dockerfile)
        self.assertEqual('consense:x:10001:10001::/home/consense:/bin/sh', (root / 'source-passwd').read_text().strip())
        self.assertEqual('consense:x:10001:', (root / 'source-group').read_text().strip())
        self.assertIn('/runtime/tools/offline_delivery/source_service_entrypoint.py', dockerfile)
        self.assertNotIn('COPY vetting_eval/', dockerfile)
        self.assertNotIn('COPY offline_delivery/ /', dockerfile)

    def test_invalid_overlapping_state_duplicate_port_and_missing_source_fail(self):
        with patch.object(entrypoint, '__file__', str(self.launcher)):
            entrypoint.configuration(self.env)
            for name, value in [('CONSENSE_SOURCE_ADVISORY_STATE', self.env['CONSENSE_SOURCE_VETTING_STATE']),
                                ('CONSENSE_SOURCE_ADVISORY_PORT', self.env['CONSENSE_SOURCE_VETTING_PORT']),
                                ('CONSENSE_SL_SCOPE_MANIFEST', str(self.root / 'missing'))]:
                with self.subTest(name=name), self.assertRaises(ValueError):
                    entrypoint.configuration({**self.env, name: value})

    def test_qdrant_key_file_is_private_and_docker_env_does_not_contain_key(self):
        if os.name != 'posix':
            self.skipTest('private file ownership is a Linux container contract')
        secret = self.root / 'qdrant-api-key'
        secret.write_text('q' * 64, encoding='ascii')
        secret.chmod(0o600)
        values = {**self.env, 'CONSENSE_QDRANT_URL': 'http://127.0.0.1:28683',
                  'CONSENSE_QDRANT_API_KEY_FILE': str(secret)}
        self.assertEqual('q' * 64, entrypoint.qdrant_credential(values))
        self.assertNotIn('CONSENSE_QDRANT_API_KEY', values)
        with self.assertRaisesRegex(ValueError, 'not a Docker environment value'):
            entrypoint.qdrant_credential({**values, 'CONSENSE_QDRANT_API_KEY': 'q' * 64})
        secret.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'private, owned and bounded'):
            entrypoint.qdrant_credential(values)
        secret.chmod(0o600)
        secret.write_bytes(b'')
        with self.assertRaisesRegex(ValueError, 'private, owned and bounded'):
            entrypoint.qdrant_credential(values)
        secret.write_bytes(b'q' * 513)
        with self.assertRaisesRegex(ValueError, 'private, owned and bounded'):
            entrypoint.qdrant_credential(values)
        link = self.root / 'qdrant-link'
        link.symlink_to(secret)
        with self.assertRaisesRegex(ValueError, 'private regular file'):
            entrypoint.qdrant_credential({**values, 'CONSENSE_QDRANT_API_KEY_FILE': str(link)})

    def test_two_external_entries_receive_distinct_scopes_and_peer_failure_stops_both(self):
        self.script.write_text('import json, os, pathlib, sys, time\n'
            'p = pathlib.Path(os.environ["CONSENSE_RETRIEVAL_DATA"])\n'
            '(p / "started.json").write_text(json.dumps({"module": os.environ["CONSENSE_OER_TARGET_MODULE"], "argv": sys.argv[1:]}))\n'
            'if os.environ["CONSENSE_OER_TARGET_MODULE"] == "vetting":\n'
            '    time.sleep(2)\n'
            '    sys.exit(7)\n'
            'time.sleep(30)\n', encoding='utf-8')
        result = subprocess.run([sys.executable, str(self.launcher)], env=self.env,
                                capture_output=True, text=True, timeout=12)
        self.assertEqual(7, result.returncode, result.stderr)
        for module, port in [('vetting', '28681'), ('advisory', '28685')]:
            observed = json.loads((self.root / module / 'started.json').read_text())
            self.assertEqual(module, observed['module'])
            expected = ['--host', '127.0.0.1', '--port', port]
            if module == 'vetting':
                expected.append('--with-ocr')
            self.assertEqual(expected, observed['argv'])

    def test_qdrant_key_reaches_only_child_processes(self):
        if os.name != 'posix':
            self.skipTest('private file ownership is a Linux container contract')
        secret = self.root / 'qdrant-api-key'
        secret.write_text('q' * 64, encoding='ascii')
        secret.chmod(0o600)
        self.script.write_text('import json, os, pathlib, sys, time\n'
            'p = pathlib.Path(os.environ["CONSENSE_RETRIEVAL_DATA"])\n'
            '(p / "auth.json").write_text(json.dumps({"auth": os.environ.get("CONSENSE_QDRANT_API_KEY") == "q" * 64}))\n'
            'if os.environ["CONSENSE_OER_TARGET_MODULE"] == "vetting":\n'
            '    time.sleep(1)\n'
            '    sys.exit(7)\n'
            'time.sleep(30)\n', encoding='utf-8')
        env = {**self.env, 'CONSENSE_QDRANT_URL': 'http://127.0.0.1:28683',
               'CONSENSE_QDRANT_API_KEY_FILE': str(secret)}
        self.assertNotIn('CONSENSE_QDRANT_API_KEY', env)
        result = subprocess.run([sys.executable, str(self.launcher)], env=env,
                                capture_output=True, text=True, timeout=12)
        self.assertEqual(7, result.returncode, result.stderr)
        for module in ('vetting', 'advisory'):
            self.assertTrue(json.loads((self.root / module / 'auth.json').read_text())['auth'])


if __name__ == '__main__':
    unittest.main()
