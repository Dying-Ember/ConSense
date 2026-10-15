"""Finite request stages; Kubernetes CLI is replaced, outputs are real child files."""
import json
import hashlib
import os
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import patch
from test_delivery import DeliveryFixture
import request_stage


class RequestStageTest(DeliveryFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.workspace = self.root / 'workspace'
        self.workspace.mkdir()
        self.asset.write_text("import os,json,base64; from pathlib import Path; r=json.loads(Path(os.environ['CONSENSE_OPERATION_REQUEST']).read_bytes()); Path(os.environ['CONSENSE_OPERATION_RESULT']).write_text(json.dumps({'contract':'consense.gpu-operation-response.v1','bindingSha256':r['bindingSha256'],'status':200,'headers':{'content-type':'application/json'},'bodyBase64':base64.b64encode(b'actual child response').decode()}))")
        self.manifest['assets'][0].update(bytes=self.asset.stat().st_size, sha256=hashlib.sha256(self.asset.read_bytes()).hexdigest())
        self.manifest['requestStages'] = {name: {'command': [sys.executable, str(self.asset)],
            'runtimeAssets': ['worker.py'], 'profile': {'modelRevision': 'c'*40}} for name in request_stage.STAGES}
        self.preparation = self.prepare()
        self.calls = []

    def test_real_child_result_is_bound_and_gpu_release_precedes_return(self):
        operation = request_stage.envelope('query-embedding-rerank', b'actual HTTP request',
            {'originalSourceSha256': 'b'*64}, {'modelRevision': 'c'*40})
        def runner(argv, payload=None):
            self.calls.append(argv)
            if 'get' in argv and 'pvc' in argv:
                return {'metadata': {'uid': 'fixture-pvc'}}
            if 'get' in argv and 'pods' in argv:
                if any(value.startswith('job-name=') for value in argv) and not any('delete' in row for row in self.calls):
                    return {'items': [{'metadata': {'uid': 'fixture-pod', 'ownerReferences': [{'uid': 'fixture-job'}]}}]}
                return {'items': []}
            if 'create' in argv:
                job = json.loads(payload)
                env = {row['name']: row['value'] for row in job['spec']['template']['spec']['containers'][0]['env'] if 'value' in row}
                env.update(CONSENSE_JOB_UID='fixture-job', CONSENSE_POD_UID='fixture-pod')
                with patch.dict(os.environ, env):
                    request_stage.execute_operation(env['CONSENSE_OPERATION_REQUEST'], self.root, self.workspace)
                return {'metadata': {'uid': 'fixture-job'}}
            if 'get' in argv and 'job' in argv:
                return {'metadata': {'uid': 'fixture-job'}, 'status': {'succeeded': 1}}
            return {}
        result = request_stage.run_request(self.root/'prepared', self.root, self.workspace, operation,
            self.preparation['manifestSha256'], runner=runner)
        self.assertEqual(200, result['status'])
        self.assertEqual(operation['bindingSha256'], result['bindingSha256'])
        self.assertTrue(result['release']['podsAbsent'])
        self.assertLess(next(i for i, call in enumerate(self.calls) if 'delete' in call), len(self.calls)-1)

    def test_changed_request_or_missing_profile_is_rejected_before_cluster(self):
        operation = request_stage.envelope('business-llm', b'original request', {'source': 'actual'}, {'model': 'frozen'})
        operation['bodyBase64'] = 'dGFtcGVyZWQ='
        with self.assertRaisesRegex(ValueError, 'binding'):
            request_stage.run_request(self.root/'prepared', self.root, self.workspace, operation,
                self.preparation['manifestSha256'], runner=lambda *args: self.fail('cluster must not be contacted'))
        with self.assertRaisesRegex(ValueError, 'profile'):
            request_stage.envelope('business-llm', b'original request', {'source': 'actual'}, {})

    def test_existing_gpu_owner_blocks_launch_and_other_coordinator_cannot_take_lock(self):
        operation = request_stage.envelope('business-llm', b'request', {'source': 'actual'}, {'modelRevision': 'c'*40})
        def occupied(argv, payload=None):
            self.calls.append(argv)
            return {'metadata': {'uid': 'actual-pvc'}} if 'pvc' in argv else {'items': [{'metadata': {'uid': 'protected-owner'}}]}
        with self.assertRaisesRegex(ValueError, 'previous GPU owner'):
            request_stage.run_request(self.root/'prepared', self.root, self.workspace, operation,
                self.preparation['manifestSha256'], runner=occupied)
        self.assertFalse(any('create' in row or 'delete' in row for row in self.calls))
        with request_stage.owner(self.workspace), self.assertRaisesRegex(ValueError, 'another coordinator'):
            with request_stage.owner(self.workspace):
                self.fail('two owners')

    def test_failed_job_is_released_and_cannot_return_an_http_success(self):
        operation = request_stage.envelope('business-llm', b'request', {'source': 'actual'}, {'modelRevision': 'c'*40})
        def failed(argv, payload=None):
            self.calls.append(argv)
            if 'get' in argv and 'pvc' in argv:
                return {'metadata': {'uid': 'actual-pvc'}}
            if 'get' in argv and 'pods' in argv:
                return {'items': []}
            if 'create' in argv:
                return {'metadata': {'uid': 'failed-job'}}
            if 'get' in argv and 'job' in argv:
                return {'metadata': {'uid': 'failed-job'}, 'status': {'failed': 1}}
            return {}
        with self.assertRaisesRegex(ValueError, 'Job failed'):
            request_stage.run_request(self.root/'prepared', self.root, self.workspace, operation,
                self.preparation['manifestSha256'], runner=failed)
        receipt = json.loads((self.workspace/'gpu-operations'/operation['operationId']/'operation.receipt.json').read_bytes())
        self.assertEqual('failed', receipt['status'])
        self.assertEqual('released', receipt['release']['status'])
        self.assertFalse(receipt['targetAccepted'])

    def test_wrong_response_binding_never_becomes_a_persisted_worker_receipt(self):
        operation = request_stage.envelope('paddle', b'input image', {'sha256': 'b'*64}, {'model': 'frozen'})
        wrong = {'contract': 'consense.gpu-operation-response.v1', 'bindingSha256': '0'*64,
                 'status': 200, 'headers': {}, 'bodyBase64': 'e30='}
        with self.assertRaisesRegex(ValueError, 'response identity'):
            request_stage.verify_response(wrong, operation)

    def test_unproven_pod_cleanup_is_persisted_and_never_returns_a_response(self):
        operation = request_stage.envelope('business-llm', b'request', {'source':'actual'}, {'modelRevision':'c'*40})
        deleted = False
        def uncleared(argv,payload=None):
            nonlocal deleted
            self.calls.append(argv)
            if 'get' in argv and 'pvc' in argv: return {'metadata':{'uid':'actual-pvc'}}
            if 'get' in argv and 'pods' in argv: return {'items':[{'metadata':{'uid':'still-owned-pod'}}] if deleted else []}
            if 'create' in argv: return {'metadata':{'uid':'owned-job'}}
            if 'get' in argv and 'job' in argv: return {'metadata':{'uid':'owned-job'},'status':{'failed':1}}
            if 'delete' in argv: deleted = True
            return {}
        with self.assertRaisesRegex(ValueError,'owner Pod still exists'):
            request_stage.run_request(self.root/'prepared',self.root,self.workspace,operation,self.preparation['manifestSha256'],runner=uncleared)
        receipt = json.loads((self.workspace/'gpu-operations'/operation['operationId']/'operation.receipt.json').read_bytes())
        self.assertEqual('release_unknown',receipt['release']['status'])
        self.assertFalse(receipt['release']['podsAbsent'])
        self.assertFalse(receipt['targetAccepted'])

    def test_unapproved_runtime_profile_is_rejected_before_any_cluster_call(self):
        operation = request_stage.envelope('paddle',b'request',{'source':'actual'},{'modelRevision':'0'*40})
        with self.assertRaisesRegex(ValueError,'profile differs'):
            request_stage.run_request(self.root/'prepared',self.root,self.workspace,operation,self.preparation['manifestSha256'],
                runner=lambda *args:self.fail('unapproved profile cannot contact the cluster'))

    def test_cancelled_operation_cannot_contact_cluster_or_create_a_job(self):
        operation = request_stage.envelope('paddle',b'request',{'source':'actual'},{'modelRevision':'c'*40})
        cancellation = threading.Event(); cancellation.set()
        with self.assertRaisesRegex(ValueError,'cancelled before launch'):
            request_stage.run_request(self.root/'prepared',self.root,self.workspace,operation,self.preparation['manifestSha256'],
                cancellation=cancellation,runner=lambda *args:self.fail('cancelled operation cannot contact cluster'))


if __name__ == '__main__':
    unittest.main()
