"""Actual delegated Linux CPU subtree admission/release; never load a model."""
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

from native_provider_lifecycle import owned_native_provider, _LinuxTree, ProviderLifecycleError


@unittest.skipUnless(sys.platform == 'linux', 'Actual delegated Linux cgroup tests')
class LinuxNativeProviderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.worker = self.root / 'worker.py'
        self.worker.write_text('''import http.server,json,os,subprocess,sys,time
from pathlib import Path
mode,port,ledger=sys.argv[1:]
if mode=='failure':
    print('authored native startup failure',file=sys.stderr,flush=True)
    raise SystemExit(7)
with open(ledger,'a') as f:
    f.write(json.dumps(dict(pid=os.getpid(),cgroup=Path('/proc/self/cgroup').read_text()))+'\\n')
if mode in ('server','branch'):
    subprocess.Popen([sys.executable,__file__,'branch' if mode=='server' else 'leaf',port,ledger])
if mode!='server':
    time.sleep(30)
else:
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200 if len(Path(ledger).read_text().splitlines())==3 else 503)
            self.end_headers()
    http.server.HTTPServer(('127.0.0.1',int(port)),Handler).serve_forever()
''')

    def tearDown(self):
        self.temp.cleanup()

    def provider(self, cgroup_root, mode='server'):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        assets = []
        for path, kind in ((Path(sys.executable), 'executable'), (self.worker, 'model')):
            assets.append(dict(path=str(path), byteCount=path.stat().st_size,
                sha256=hashlib.sha256(path.read_bytes()).hexdigest(), kind=kind))
        return dict(baseUrl='http://127.0.0.1:'+str(port), identity=dict(parserVersion='authored-test-v1',
            engine='cpu-http-fixture', models=[dict(modelId='authored-no-model', revision='a'*40,
            assetPaths=[str(self.worker)])], config=dict(precision='bf16')),
            ownedBoot=dict(command=[sys.executable,str(self.worker),mode,str(port),str(self.root/'pids.jsonl')],
                cwd=str(self.root),env={'PATH':'/usr/bin:/bin','PYTHONUNBUFFERED':'1'},runtimeAssets=assets,
                readyPath='/',readyTimeoutSeconds=3,cleanupTimeoutSeconds=2,precision='bf16',cgroupRoot=cgroup_root))

    def test_real_parent_child_grandchild_admission_and_release(self):
        cgroup_root = os.environ.get('CONSENSE_TEST_CGROUP_ROOT')
        if not cgroup_root:
            self.skipTest('Explicit delegated test cgroup root required')
        with owned_native_provider(self.provider(cgroup_root), 5, self.root/'receipt') as receipt:
            rows = [json.loads(row) for row in (self.root/'pids.jsonl').read_text().splitlines()]
            self.assertEqual(len(rows), 3)
            actual = (Path('/proc')/str(receipt['pid'])/'cgroup').read_text()
            self.assertIn('consense-ocr-', actual)
            self.assertTrue(all(row['cgroup'] == actual for row in rows))
        self.assertEqual(receipt['exitReceipt']['status'], 'released')
        self.assertEqual(receipt['exitReceipt']['cgroupPopulated'], 0)
        for row in rows:
            status = Path('/proc', str(row['pid']), 'status')
            self.assertTrue(not status.exists() or '\nState:\tZ' in status.read_text())

    def test_failed_native_command_retains_stderr_and_releases(self):
        cgroup_root = os.environ.get('CONSENSE_TEST_CGROUP_ROOT')
        if not cgroup_root:
            self.skipTest('Explicit delegated test cgroup root required')
        with self.assertRaisesRegex(ProviderLifecycleError, 'exited before wire readiness'):
            with owned_native_provider(self.provider(cgroup_root, 'failure'), 5, self.root/'receipt'):
                self.fail('Failed command cannot be admitted as a ready parser')
        receipt = json.loads((self.root/'receipt/exit.json').read_text())
        self.assertEqual(receipt['status'], 'released')
        self.assertEqual(receipt['primaryError']['type'], 'ProviderLifecycleError')
        self.assertIn('authored native startup failure', (self.root/'receipt/stderr.log').read_text())

    def test_lost_admission_ack_releases_actual_descendants(self):
        cgroup_root = os.environ.get('CONSENSE_TEST_CGROUP_ROOT')
        if not cgroup_root:
            self.skipTest('Explicit delegated test cgroup root required')
        before = set(Path(cgroup_root).glob('consense-ocr-*'))
        read = os.read
        def lose_ack(fd, length):
            result = read(fd, length)
            if length != 1:
                return result
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                path = self.root/'pids.jsonl'
                if path.exists() and len(path.read_text().splitlines()) == 3:
                    return b'0'
                time.sleep(.01)
            self.fail('Real admitted CPU descendants did not start')
        with (self.root/'out.log').open('wb') as stdout, (self.root/'err.log').open('wb') as stderr:
            with patch('native_provider_lifecycle.os.read', side_effect=lose_ack):
                with self.assertRaisesRegex(ProviderLifecycleError, 'did not confirm cgroup admission'):
                    _LinuxTree(self.provider(cgroup_root)['ownedBoot'], stdout, stderr, 5)
        rows = [json.loads(row) for row in (self.root/'pids.jsonl').read_text().splitlines()]
        self.assertEqual(len(rows), 3)
        self.assertEqual(before, set(Path(cgroup_root).glob('consense-ocr-*')))
        for row in rows:
            status = Path('/proc', str(row['pid']), 'status')
            self.assertTrue(not status.exists() or '\nState:\tZ' in status.read_text())

    def test_primary_and_close_failure_are_both_preserved(self):
        cgroup_root = os.environ.get('CONSENSE_TEST_CGROUP_ROOT')
        if not cgroup_root:
            self.skipTest('Explicit delegated test cgroup root required')
        close = _LinuxTree.close
        def fail_after_actual_close(tree):
            close(tree)
            raise OSError('authored close failure')
        with patch('native_provider_lifecycle._LinuxTree.close', fail_after_actual_close):
            with self.assertRaisesRegex(ProviderLifecycleError, 'primary error:.*exited before wire readiness.*cleanup error:.*authored close failure'):
                with owned_native_provider(self.provider(cgroup_root, 'failure'), 5, self.root/'receipt'):
                    self.fail('Failed startup cannot yield')
        receipt = json.loads((self.root/'receipt/exit.json').read_text())
        self.assertEqual(receipt['status'], 'release_unknown')
        self.assertIn('exited before wire readiness', receipt['primaryError']['message'])
        self.assertIn('authored close failure', receipt['error'])

    def test_denied_admission_never_executes_native_command(self):
        cgroup_root = os.environ.get('CONSENSE_TEST_DENIED_CGROUP_ROOT')
        if not cgroup_root:
            self.skipTest('Explicit denied test cgroup root required')
        before = set(Path(cgroup_root).glob('consense-ocr-*'))
        provider = self.provider(cgroup_root)
        with (self.root/'out.log').open('wb') as stdout, (self.root/'err.log').open('wb') as stderr:
            with self.assertRaisesRegex(ProviderLifecycleError, 'did not confirm cgroup admission'):
                _LinuxTree(provider['ownedBoot'], stdout, stderr, 5)
        self.assertFalse((self.root/'pids.jsonl').exists())
        self.assertEqual(before, set(Path(cgroup_root).glob('consense-ocr-*')))
        self.assertIn('PermissionError', (self.root/'err.log').read_text())


if __name__ == '__main__':
    unittest.main()
