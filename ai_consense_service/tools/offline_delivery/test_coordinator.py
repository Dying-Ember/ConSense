"""Normal HTTP index/query routes select GPU operations without local model loading."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

from test_delivery import DeliveryFixture
import coordinator
import window_operation_worker


class CoordinatorTest(DeliveryFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        profile = {'adapter': 'consense.window-runtime.v1', 'environment': {
            'CONSENSE_MODEL_DEVICE': 'cuda', 'CONSENSE_MODEL_DTYPE': 'float32', 'CONSENSE_MODEL_OFFLINE': '1'}}
        self.manifest['requestStages'] = {stage: {'command': ['python', '/app/offline_delivery/window_operation_worker.py'],
            'runtimeAssets': ['worker.py'], 'profile': profile} for stage in ('document-embedding', 'query-embedding-rerank')}
        receipt = self.prepare()
        self.env = dict(zip(coordinator.NAMES, [str(self.root/'prepared'), str(self.root), str(self.root), receipt['manifestSha256']]))

    def test_public_index_and_retrieve_routes_dispatch_exact_operations_without_local_runtime(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'vetting_eval'))
        import server
        from fastapi.testclient import TestClient
        observed = []
        def execute(prepared, artifacts, workspace, operation, expected):
            observed.append(operation)
            return {'status': 200, 'bodyBase64': base64.b64encode(b'{"actualStageBoundary":true}').decode()}
        with patch.dict(os.environ, self.env), patch.object(coordinator, 'run_request', side_effect=execute), \
                patch.object(server, 'embedding_model', side_effect=AssertionError('CPU coordinator must not load embedding')), \
                patch.object(server, 'rerank_model', side_effect=AssertionError('CPU coordinator must not load reranker')):
            client = TestClient(server.app)
            self.assertEqual(200, client.get('/health').status_code)
            self.assertFalse(client.get('/health').json()['modelReady'])
            response = client.post('/index', json={'projectId': 'authored', 'chunks': [{'id': 'one', 'content': 'Complete source clause'}]})
            self.assertEqual(200, response.status_code)
            self.assertEqual({'actualStageBoundary': True}, response.json())
            self.assertEqual(200, client.post('/retrieve', json={'projectId': 'authored', 'query': 'Source question'}).status_code)
        self.assertEqual(['document-embedding', 'query-embedding-rerank'], [row['stage'] for row in observed])
        request = json.loads(base64.b64decode(observed[0]['bodyBase64']))
        self.assertEqual('Complete source clause', request['chunks'][0]['content'])
        self.assertEqual(self.manifest['requestStages']['document-embedding']['profile'], observed[0]['profile'])

    def test_partial_coordinator_configuration_and_changed_manifest_fail_before_dispatch(self):
        with self.assertRaisesRegex(ValueError, 'complete GPU'):
            coordinator.configured({coordinator.NAMES[0]: 'present'})
        (self.root/'prepared/manifest.json').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'manifest changed'):
            coordinator.execute('document-embedding', {'projectId': 'authored'}, self.env)

    def test_public_routes_use_the_actual_server_module_when_not_imported_as_server(self):
        import server
        from fastapi.testclient import TestClient
        name = 'authored_cli_entrypoint'
        spec = importlib.util.spec_from_file_location(name, server.__file__)
        entrypoint = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {name: entrypoint, 'server': None}):
            spec.loader.exec_module(entrypoint)
            result = {'status': 200, 'bodyBase64': base64.b64encode(b'{"actualModule":true}').decode()}
            with patch.dict(os.environ, self.env), patch.object(coordinator, 'run_request', return_value=result):
                client = TestClient(entrypoint.app)
                self.assertEqual({'actualModule': True}, client.post('/index', json={'projectId': 'authored', 'chunks': []}).json())
                self.assertEqual({'actualModule': True}, client.post('/retrieve', json={'projectId': 'authored', 'query': 'Question'}).json())

    def test_cpu_profile_is_rejected_before_any_vendor_import_or_result(self):
        from request_stage import envelope, save, sha
        request = envelope('document-embedding', b'{"projectId":"authored","chunks":[]}', {'source': 'actual'},
            {'adapter': 'consense.window-runtime.v1', 'environment': {'CONSENSE_MODEL_DEVICE': 'cpu', 'CONSENSE_MODEL_DTYPE': 'float32', 'CONSENSE_MODEL_OFFLINE': '1'}})
        path = self.root/'request.json'; save(path, request)
        env = {'CONSENSE_OPERATION_REQUEST_SHA256': sha(path), 'CONSENSE_JOB_UID': 'actual', 'CONSENSE_POD_UID': 'actual', 'CONSENSE_WORKSPACE_PVC_UID': 'actual'}
        with patch.dict(os.environ, env), self.assertRaisesRegex(ValueError, 'CUDA'):
            window_operation_worker.execute(path, self.root/'result.json')
        self.assertFalse((self.root/'result.json').exists())

    def test_configured_model_catalog_matches_java_availability_without_starting_a_gpu_job(self):
        import server
        from fastapi.testclient import TestClient
        self.manifest['requestStages']['business-llm'] = {'command':['python','/app/offline_delivery/chat_operation_worker.py'],
            'runtimeAssets':['worker.py'],'profile':{'adapter':'consense.native-chat.v1','model':'authored-only',
                'apiPaths':{'/v1/chat/completions':'/v1/chat/completions'}}}
        path = self.root/'prepared/manifest.json'; path.write_text(json.dumps(self.manifest))
        self.env[coordinator.NAMES[3]] = hashlib.sha256(path.read_bytes()).hexdigest()
        with patch.dict(os.environ,self.env), patch.object(coordinator,'run_request',side_effect=AssertionError('Catalog must not load a model')):
            response = TestClient(server.app).get('/v1/models')
        self.assertEqual(200,response.status_code)
        value = response.json()
        self.assertEqual(['authored-only'],[model['id'] for model in value['data']])
        self.assertFalse(value['modelReady']); self.assertFalse(value['targetAccepted'])

    def test_public_native_business_wire_is_exact_and_stream_claim_is_rejected_before_job(self):
        import server
        from fastapi.testclient import TestClient
        self.manifest['requestStages']['business-llm'] = {'command':['python','/app/offline_delivery/chat_operation_worker.py'],
            'runtimeAssets':['worker.py'],'profile':{'adapter':'consense.native-chat.v1','model':'authored-only',
                'apiPaths':{'/v1/chat/completions':'/v1/chat/completions'}}}
        path = self.root/'prepared/manifest.json'; path.write_text(json.dumps(self.manifest))
        self.env[coordinator.NAMES[3]] = hashlib.sha256(path.read_bytes()).hexdigest()
        observed = []
        def invoke(prepared,artifacts,workspace,operation,expected):
            observed.append(operation)
            return {'status':201,'headers':{'content-type':'application/json'},'bodyBase64':base64.b64encode(b'{"fixtureOnly":true}').decode()}
        with patch.dict(os.environ,self.env), patch.object(coordinator,'run_request',side_effect=invoke):
            client = TestClient(server.app)
            raw = b'{"model":"authored-only", "messages":[],"stream":false}'
            response = client.post('/v1/chat/completions',content=raw,headers={'content-type':'application/json'})
            self.assertEqual(201,response.status_code)
            self.assertEqual(raw,base64.b64decode(observed[0]['bodyBase64']))
            rejected = client.post('/v1/chat/completions',json={'model':'authored-only','messages':[],'stream':True})
            self.assertEqual(409,rejected.status_code)
            self.assertEqual(1,len(observed))


if __name__ == '__main__':
    unittest.main()
