import hashlib
import json
from pathlib import Path
import unittest

from test_delivery import DeliveryFixture
import gpu_stage


class GpuStageTest(DeliveryFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.prepared = self.prepare()
        self.output = self.root / 'result.bin'
        self.output.write_bytes(b'persisted stage output')
        self.receipt = {'contract': 'consense-gpu-stage-result-v1', 'manifestSha256': self.prepared['manifestSha256'],
                        'stage': 'paddle', 'status': 'persisted', 'jobUid': 'previous-job', 'pvcUid': 'actual-pvc',
                        'outputs': [{'path': 'result.bin', 'bytes': self.output.stat().st_size,
                                     'sha256': hashlib.sha256(self.output.read_bytes()).hexdigest()}]}
        self.receipt_path = self.root / 'previous.receipt.json'
        self.receipt_path.write_text(json.dumps(self.receipt))
        self.calls = []
        self.pods_remain = False

    def runner(self, argv, payload=None):
        self.calls.append((argv, payload))
        if 'get' in argv and 'pvc' in argv:
            return {'metadata': {'uid': 'actual-pvc'}}
        if 'get' in argv and 'job' in argv:
            return {'metadata': {'uid': 'previous-job', 'annotations': {'consense-manifest-sha256': self.prepared['manifestSha256']}}, 'status': {'succeeded': 1}}
        if 'get' in argv and 'pods' in argv:
            return {'items': [{'metadata': {'uid': 'still-present'}}] if self.pods_remain else []}
        if 'create' in argv:
            return {'metadata': {'uid': 'new-job'}}
        return {}

    def handoff(self, execute=True):
        return gpu_stage.handoff(self.root / 'prepared', self.root, self.root, 'mineru',
                                 self.prepared['manifestSha256'], self.receipt_path, execute, self.runner)

    def test_default_plan_does_not_contact_cluster_and_execution_waits_for_old_owner_deletion(self):
        plan = self.handoff(False)
        self.assertFalse(plan['executed'])
        self.assertEqual([], self.calls)
        result = self.handoff()
        self.assertTrue(result['executed'])
        operations = [argv[3] for argv, _ in self.calls]
        self.assertLess(operations.index('delete'), operations.index('wait'))
        self.assertLess(operations.index('wait'), operations.index('create'))
        created = json.loads(self.calls[-1][1])
        self.assertEqual('consense-authored-test-mineru', created['metadata']['name'])

    def test_existing_old_pod_blocks_next_gpu_stage_even_after_job_reports_success(self):
        self.pods_remain = True
        with self.assertRaisesRegex(ValueError, 'owner'):
            self.handoff()
        self.assertFalse(any('create' in argv for argv, _ in self.calls))

    def test_changed_output_or_pvc_binding_blocks_before_deleting_old_owner(self):
        self.output.write_bytes(b'changed output')
        with self.assertRaisesRegex(ValueError, 'asset'):
            self.handoff()
        self.assertEqual([], self.calls)

    def test_another_workspace_pvc_cannot_release_the_saved_gpu_owner(self):
        self.receipt['pvcUid'] = 'another-pvc'
        self.receipt_path.write_text(json.dumps(self.receipt))
        with self.assertRaisesRegex(ValueError, 'PVC'):
            self.handoff()
        self.assertFalse(any('delete' in argv or 'create' in argv for argv, _ in self.calls))


if __name__ == '__main__':
    unittest.main()
