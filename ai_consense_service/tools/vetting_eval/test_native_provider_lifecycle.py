"""Real local HTTP child trees; no OCR/model/runtime acceptance."""
import hashlib
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from native_provider_lifecycle import owned_native_provider, ProviderLifecycleError, validate_provider


class NativeProviderLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.worker = self.root / 'authored_server.py'
        self.worker.write_text('''import http.server, os, subprocess, sys, time
if len(sys.argv) == 4:
    child = subprocess.Popen([sys.executable, __file__, sys.argv[1], sys.argv[2]])
    open(sys.argv[2], "w").write(str(child.pid))
    while True: time.sleep(.05)
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b"authored availability only")
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
''', encoding='utf-8')

    def tearDown(self):
        self.temp.cleanup()

    def provider(self, name):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        return {'baseUrl': 'http://127.0.0.1:' + str(port),
            'identity': {'parserVersion': 'authored-test-v1', 'engine': name,
                         'models': [{'modelId': 'authored-no-model', 'revision': 'a'*40,
                                     'assetPaths': [str(self.worker)]}], 'config': {'precision': 'bf16'}},
            'ownedBoot': {'command': [sys.executable, str(self.worker), str(port), str(self.root / (name+'.pid')), 'parent'],
                'cwd': str(self.root), 'env': {'SYSTEMROOT': os.environ.get('SYSTEMROOT', '')} if os.name == 'nt' else {},
                'runtimeAssets': [{'path': str(Path(path).resolve()), 'byteCount': Path(path).stat().st_size,
                    'sha256': hashlib.sha256(Path(path).read_bytes()).hexdigest(), 'kind': kind}
                    for path, kind in ((sys.executable, 'executable'), (self.worker, 'model'))],
                'readyPath': '/', 'readyTimeoutSeconds': 5, 'cleanupTimeoutSeconds': 2, 'precision': 'bf16'}}

    @unittest.skipUnless(os.name == 'nt', 'Windows Job Object tree test; Linux requires an explicitly delegated cgroup')
    def test_real_child_tree_exits_before_next_provider_starts(self):
        receipts = []
        for name in ('paddleocr-vl', 'mineru'):
            with owned_native_provider(self.provider(name), 10, self.root / name) as receipt:
                receipts.append(receipt)
                self.assertEqual('wire_ready_model_readiness_unverified', receipt['wireReady']['status'])
                self.assertTrue(receipt['pid'] > 0)
                if len(receipts) == 2:
                    self.assertEqual('released', receipts[0]['exitReceipt']['status'])
                    self.assertEqual(0, receipts[0]['exitReceipt']['activeProcesses'])
            self.assertEqual('released', receipt['exitReceipt']['status'])
            self.assertEqual(0, receipt['exitReceipt']['activeProcesses'])
            saved = json.loads((self.root / name / 'exit.json').read_text())
            self.assertEqual('released', saved['status'])
            self.assertFalse(saved['modelReady'])
            self.assertFalse(saved['targetAccepted'])

    def test_modified_pinned_asset_cannot_start(self):
        row = self.provider('fixture')
        self.worker.write_text('changed asset')
        with self.assertRaisesRegex(ValueError, 'SHA'):
            with owned_native_provider(row, 10, self.root / 'rejected'):
                self.fail('must never start')
        self.assertFalse((self.root / 'rejected').exists())

    def test_remote_existing_provider_does_not_claim_owned_handoff(self):
        row = self.provider('remote'); row.pop('ownedBoot')
        with owned_native_provider(row, 1, self.root / 'remote') as receipt:
            self.assertEqual('remote_handoff_unverified', receipt['mode'])
            self.assertNotIn('pid', receipt)
        self.assertEqual('remote_handoff_unverified', receipt['exitReceipt']['status'])

    def test_unpinned_identity_and_unbounded_boot_are_rejected(self):
        row = self.provider('bad')
        row['identity']['models'][0]['revision'] = 'main'
        with self.assertRaisesRegex(ValueError, 'model'):
            validate_provider(row)
        row = self.provider('bad'); row['ownedBoot']['readyTimeoutSeconds'] = float('inf')
        with self.assertRaisesRegex(ValueError, 'finite'):
            validate_provider(row)

    def test_existing_listener_is_never_adopted_or_killed(self):
        row = self.provider('occupied')
        port = int(row['baseUrl'].rsplit(':', 1)[1])
        with socket.socket() as server:
            server.bind(('127.0.0.1', port)); server.listen()
            with self.assertRaisesRegex(ProviderLifecycleError, 'occupied'):
                with owned_native_provider(row, 2, self.root / 'occupied'):
                    self.fail('must never start')
            self.assertEqual(port, server.getsockname()[1])

    @unittest.skipUnless(os.name == 'nt', 'Windows native subtree release boundary')
    def test_unproven_tree_release_raises_before_next_provider(self):
        entered_next = False
        manager = patch('native_provider_lifecycle._WindowsTree.active', return_value=1)
        try:
            with self.assertRaisesRegex(ProviderLifecycleError, 'release not proven'):
                with owned_native_provider(self.provider('uncertain'), 10, self.root / 'uncertain'):
                    # Inject uncertainty only after the real local service is running.
                    manager.start()
                entered_next = True
        finally:
            manager.stop()
        self.assertFalse(entered_next)
        self.assertEqual('release_unknown', json.loads((self.root / 'uncertain/exit.json').read_text())['status'])

    @unittest.skipUnless(os.name == 'nt', 'Windows finite native subprocess deadline')
    def test_total_deadline_terminates_owned_tree_and_preserves_receipt(self):
        with self.assertRaisesRegex(TimeoutError, 'total deadline'):
            with owned_native_provider(self.provider('deadline'), 1, self.root / 'deadline'):
                time.sleep(1.1)
        receipt = json.loads((self.root / 'deadline/exit.json').read_text())
        self.assertEqual('released', receipt['status'])
        self.assertTrue(receipt['deadlineExpired'])

    @unittest.skipUnless(os.name == 'nt', 'Windows native receipt filesystem failure cleanup')
    def test_receipt_write_failure_still_releases_the_real_owned_http_subtree(self):
        import native_provider_lifecycle as lifecycle_module
        row=self.provider('disk-failure');save=lifecycle_module._save
        def fail_exit(path, value):
            if path.name=='exit.json': raise OSError('authored receipt disk failure')
            return save(path,value)
        entered_next=False
        with patch('native_provider_lifecycle._save',side_effect=fail_exit):
            with self.assertRaisesRegex(OSError,'receipt disk failure'):
                with owned_native_provider(row,10,self.root/'disk-failure'):
                    pass
                entered_next=True
        self.assertFalse(entered_next)
        with socket.socket() as probe:
            probe.settimeout(.2)
            self.assertNotEqual(0,probe.connect_ex(('127.0.0.1',int(row['baseUrl'].rsplit(':',1)[1]))),'The owned real child server must not survive receipt persistence failure')


if __name__ == '__main__':
    unittest.main()
