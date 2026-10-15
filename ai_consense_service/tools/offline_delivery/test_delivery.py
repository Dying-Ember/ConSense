"""Public preparation and handoff boundaries; no Docker, cluster or database calls."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import delivery


class DeliveryFixture:
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.asset = self.root / 'worker.py'
        self.asset.write_text('print("authored offline fixture")\n')
        self.manifest = {
            'contract': 'consense-offline-delivery-v1', 'sourceCommit': 'a' * 40,
            'namespace': 'consense-test', 'release': 'authored-test',
            'images': {name: 'fixture/' + name + '@sha256:' + 'a' * 64
                       for name in ('mysql', 'qdrant', 'backend', 'web', *delivery.STAGES)},
            'storageClass': {'fast': 'test-fast', 'general': 'test-general'},
            'gpuNodeSelector': {'nvidia.com/gpu.product': 'NVIDIA-H800'},
            'assets': [{'path': 'worker.py', 'bytes': self.asset.stat().st_size,
                        'sha256': hashlib.sha256(self.asset.read_bytes()).hexdigest()}],
            'stages': [{'name': name, 'command': ['python', '/artifacts/worker.py'],
                        'runtimeAssets': ['worker.py'], 'outputs': [name + '.result.json'], 'timeoutSeconds': 60}
                       for name in delivery.STAGES],
        }
        self.path = self.root / 'manifest.json'

    def tearDown(self):
        self.temp.cleanup()

    def prepare(self):
        self.path.write_text(json.dumps(self.manifest))
        return delivery.prepare(self.path, self.root, self.root / 'prepared')


class DeliveryPreparationTest(DeliveryFixture, unittest.TestCase):
    def test_prepares_digest_bound_offline_cpu_services_and_complete_gpu_stages(self):
        receipt = self.prepare()
        self.assertFalse(receipt['targetAccepted'])
        docs = json.loads((self.root / 'prepared/kubernetes.json').read_text())['items']
        self.assertFalse(any(doc['kind'] == 'Job' for doc in docs), 'Applying CPU resources cannot start concurrent GPU stages')
        jobs = [json.loads((self.root / 'prepared/gpu-jobs' / (name + '.json')).read_text()) for name in delivery.STAGES]
        self.assertEqual(list(delivery.STAGES), [job['metadata']['labels']['consense-stage'] for job in jobs])
        for job in jobs:
            pod = job['spec']['template']['spec']
            self.assertEqual(1, pod['containers'][0]['resources']['limits']['nvidia.com/gpu'])
            self.assertEqual('Never', pod['containers'][0]['imagePullPolicy'])
            self.assertTrue(next(m for m in pod['containers'][0]['volumeMounts'] if m['name'] == 'artifacts')['readOnly'])
        self.assertEqual(4, len([doc for doc in docs if doc['kind'] == 'PersistentVolumeClaim']))
        self.assertEqual(self.path.read_bytes(), (self.root / 'prepared/manifest.json').read_bytes())

    def test_mutated_runtime_or_unpinned_image_cannot_prepare_a_candidate(self):
        self.asset.write_text('changed source')
        with self.assertRaisesRegex(ValueError, 'asset'):
            self.prepare()
        self.assertFalse((self.root / 'prepared').exists())

    def test_tag_only_image_and_reordered_or_missing_stage_are_rejected(self):
        self.manifest['images']['mysql'] = 'mysql:8.4'
        with self.assertRaisesRegex(ValueError, 'digest'):
            self.prepare()
        self.manifest['images']['mysql'] = 'fixture/mysql@sha256:' + 'a' * 64
        self.manifest['stages'].reverse()
        with self.assertRaisesRegex(ValueError, 'stage'):
            self.prepare()

    def test_asset_escape_and_existing_output_are_rejected(self):
        self.manifest['assets'][0]['path'] = '../worker.py'
        with self.assertRaisesRegex(ValueError, 'asset'):
            self.prepare()
        self.manifest['assets'][0]['path'] = 'worker.py'
        self.prepare()
        with self.assertRaisesRegex(ValueError, 'fresh'):
            self.prepare()

    def test_enabled_offline_llm_binds_java_base_path_and_exact_native_model(self):
        endpoint='http://consense-coordinator:8868'
        self.manifest['images']['coordinator']='fixture/coordinator@sha256:'+'c'*64
        self.manifest['requestStages']={'business-llm':{'command':['python','/app/offline_delivery/chat_operation_worker.py'],
            'runtimeAssets':['worker.py'],'profile':{'adapter':'consense.native-chat.v1','model':'authored-only',
                'apiPaths':{'/v1/chat/completions':'/v1/chat/completions'}}}}
        config={'ocr':{'mode':'local','base-url':endpoint},'vetting':{'retrieval-url':endpoint},
            'minimax-cn':{'enabled':False},'llm':{'enabled':True,'provider':'openai','base-url':endpoint,
                'body-streaming-enabled':False,'chat-model':'authored-only'}}
        self.manifest['coordinator']={'port':8868,'preparedPath':'/artifacts/prepared','runtimeAssets':['worker.py'],
            'apiServerCidr':'10.96.0.1/32','apiServerPort':443,'environment':{
                'CONSENSE_OCR_MODEL_DIRECTORY':'/artifacts/identity-ocr','CONSENSE_OCR_RECIPE_SHA256':'d'*64},
            'backendConfig':{'consense':config}}
        delivery.validate(self.manifest,self.root)
        config['llm']['base-url']=endpoint+'/v1'
        with self.assertRaisesRegex(ValueError,'finite-Job LLM'):
            delivery.validate(self.manifest,self.root)
        config['llm']['base-url']=endpoint; config['llm']['chat-model']='another-model'
        with self.assertRaisesRegex(ValueError,'native model'):
            delivery.validate(self.manifest,self.root)

    def test_coordinator_uses_cpu_and_explicit_internal_application_routes(self):
        self.manifest['images']['coordinator'] = 'fixture/coordinator@sha256:'+'c'*64
        self.manifest['requestStages'] = {stage: {'command': ['python', '/app/offline_delivery/window_operation_worker.py'],
            'runtimeAssets': ['worker.py'], 'profile': {'adapter': 'consense.window-runtime.v1', 'environment': {
                'CONSENSE_MODEL_DEVICE': 'cuda', 'CONSENSE_MODEL_DTYPE': 'float32', 'CONSENSE_MODEL_OFFLINE': '1'}}}
            for stage in ('document-embedding', 'query-embedding-rerank')}
        self.manifest['coordinator'] = {'port': 8868, 'preparedPath': '/artifacts/prepared', 'runtimeAssets': ['worker.py'],
            'apiServerCidr': '10.96.0.1/32', 'apiServerPort': 443,
            'environment': {'CONSENSE_OCR_MODEL_DIRECTORY':'/artifacts/identity-ocr','CONSENSE_OCR_RECIPE_SHA256':'d'*64}, 'backendConfig': {'consense': {'ocr': {'mode': 'local', 'base-url': 'http://consense-coordinator:8868'},
                'vetting': {'retrieval-url': 'http://consense-coordinator:8868'}, 'minimax-cn': {'enabled': False},
                'llm': {'enabled': False}}}}
        self.prepare()
        docs = json.loads((self.root/'prepared/kubernetes.json').read_bytes())['items']
        cpu = next(row for row in docs if row['kind'] == 'Deployment' and row['metadata']['name'] == 'consense-coordinator')
        container = cpu['spec']['template']['spec']['containers'][0]
        self.assertNotIn('nvidia.com/gpu', container['resources']['limits'])
        self.assertEqual('consense-gpu-coordinator', cpu['spec']['template']['spec']['serviceAccountName'])
        backend = next(row for row in docs if row['kind'] == 'Deployment' and row['metadata']['name'] == 'consense-backend')
        environment = {row['name']: row.get('value') for row in backend['spec']['template']['spec']['containers'][0]['env']}
        runtime = json.loads(environment['SPRING_APPLICATION_JSON'])['consense']
        self.assertFalse(runtime['minimax-cn']['enabled'])
        self.assertEqual('local', runtime['ocr']['mode'])
        self.assertEqual('http://consense-coordinator:8868', runtime['vetting']['retrieval-url'])
        receipt = json.loads((self.root/'prepared/preparation.json').read_bytes())
        self.assertFalse(receipt['completeNewSourcePipeline'])
        self.assertIn('native_session_job_adapter', receipt['openRuntimeAdapters'])
        policy = next(row for row in docs if row['kind'] == 'NetworkPolicy' and row['metadata']['name'] == 'consense-coordinator-api')
        self.assertEqual([{'ipBlock': {'cidr': '10.96.0.1/32'}}], policy['spec']['egress'][0]['to'])
        resources = [row['spec']['template']['spec']['containers'][0]['resources']['requests'] for row in docs if row['kind'] == 'Deployment']
        job = json.loads((self.root/'prepared/gpu-jobs/paddle.json').read_bytes())
        resources.append(job['spec']['template']['spec']['containers'][0]['resources']['requests'])
        self.assertEqual(27, sum(int(row['cpu']) for row in resources))
        self.assertEqual(246, sum(int(row['memory'].removesuffix('G')) for row in resources))


if __name__ == '__main__':
    unittest.main()
