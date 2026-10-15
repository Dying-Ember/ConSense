import hashlib
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

from test_delivery import DeliveryFixture
import stage_worker


class StageWorkerTest(DeliveryFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.manifest['stages'][0]['command'] = [sys.executable, '-c', "from pathlib import Path; Path('result.json').write_text('{\"fixtureOnly\":true}'); print('actual child output')"]
        self.manifest['stages'][0]['outputs'] = ['result.json']
        for stage in self.manifest['stages'][1:]:
            stage['outputs'] = [stage['name'] + '.json']
        self.prepared = self.prepare()
        self.workspace = self.root / 'workspace'
        self.workspace.mkdir()
        self.env = {'CONSENSE_MANIFEST_SHA256': self.prepared['manifestSha256'], 'CONSENSE_JOB_UID': 'actual-job-uid',
                    'CONSENSE_POD_UID': 'actual-pod-uid', 'CONSENSE_WORKSPACE_PVC_UID': 'actual-pvc-uid',
                    'CONSENSE_STAGE_RECEIPT': str(self.workspace / 'paddle.receipt.json')}

    def test_real_child_outputs_and_logs_are_fsynced_before_persisted_stage_receipt(self):
        with patch.dict(os.environ, self.env):
            receipt = stage_worker.execute(self.root / 'prepared/manifest.json', self.root, self.workspace, 'paddle')
        self.assertEqual('persisted', receipt['status'])
        self.assertEqual(0, receipt['exitCode'])
        self.assertEqual('actual-job-uid', receipt['jobUid'])
        self.assertEqual(hashlib.sha256((self.workspace / 'result.json').read_bytes()).hexdigest(), receipt['outputs'][0]['sha256'])
        stdout = next(row for row in receipt['logs'] if row['path'].endswith('/stdout.log'))
        self.assertIn('actual child output', (self.workspace / stdout['path']).read_text())
        self.assertEqual(receipt, json.loads((self.workspace / 'paddle.receipt.json').read_bytes()))
        self.assertFalse(receipt['targetAccepted'])

    def test_changed_manifest_is_rejected_before_worker_launch(self):
        (self.root / 'prepared/manifest.json').write_bytes(b'changed manifest')
        with patch.dict(os.environ, self.env), self.assertRaisesRegex(ValueError, 'manifest'):
            stage_worker.execute(self.root / 'prepared/manifest.json', self.root, self.workspace, 'paddle')
        self.assertFalse((self.workspace / 'paddle.receipt.json').exists())

    def test_real_child_failure_preserves_raw_logs_and_cannot_overwrite_its_receipt(self):
        self.manifest['stages'][0]['command'] = [sys.executable, '-c', "print('retained actual failure'); raise SystemExit(7)"]
        path = self.root / 'failed-manifest.json'
        path.write_text(json.dumps(self.manifest))
        self.env['CONSENSE_MANIFEST_SHA256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        with patch.dict(os.environ, self.env):
            receipt = stage_worker.execute(path, self.root, self.workspace, 'paddle')
            original = (self.workspace / 'paddle.receipt.json').read_bytes()
            with self.assertRaisesRegex(ValueError, 'fresh'):
                stage_worker.execute(path, self.root, self.workspace, 'paddle')
        self.assertEqual('failed', receipt['status'])
        self.assertEqual(7, receipt['exitCode'])
        self.assertEqual([], receipt['outputs'])
        self.assertEqual(original, (self.workspace / 'paddle.receipt.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
