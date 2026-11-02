"""Public preparation and handoff boundaries; no Docker, cluster or database calls."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
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
    def external_app(self):
        for path, content in [('release/app.jar', b'fixture jar'), ('release/web/index.html', b'fixture html'),
                              ('release/web/assets/main.js', b'fixture js')]:
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            self.manifest['assets'].append({'path': path, 'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()})
        self.manifest['externalApp'] = {'jar': 'release/app.jar', 'webDirectory': 'release/web'}
        del self.manifest['images']['web']

    def test_external_app_uses_pinned_mounted_release_and_no_web_container(self):
        self.external_app()
        self.prepare()
        docs = json.loads((self.root / 'prepared/kubernetes.json').read_bytes())['items']
        deployments = [d for d in docs if d['kind'] == 'Deployment']
        self.assertEqual({'consense-mysql', 'consense-qdrant', 'consense-backend'}, {d['metadata']['name'] for d in deployments})
        backend = next(d for d in deployments if d['metadata']['name'] == 'consense-backend')['spec']['template']['spec']
        container = backend['containers'][0]
        self.assertEqual(['/bin/sh', '/app/app-entrypoint.sh'], container['command'])
        self.assertEqual({'release/app.jar', 'release/web'}, {v['value'] for v in container['env'] if v['name'] in ('APP_JAR', 'APP_WEB_DIRECTORY')})
        self.assertTrue(next(m for m in container['volumeMounts'] if m['name'] == 'artifacts')['readOnly'])
        config = next(d for d in docs if d['kind'] == 'ConfigMap' and d['metadata']['name'] == 'consense-app-files-authored-test')
        self.assertIn('release/app.jar\n', config['data']['sha256sums'])
        self.assertEqual('release/web/assets/main.js\nrelease/web/index.html\n', config['data']['web-files'])
        self.assertEqual('app.jar\nweb/assets/main.js\nweb/index.html\n', config['data']['release-files'])
        values = {row['name']: row.get('value') for row in container['env']}
        self.assertEqual('release', values['APP_RELEASE_DIRECTORY'])
        self.assertEqual(hashlib.sha256(config['data']['release-files'].encode()).hexdigest(), values['APP_RELEASE_FILES_SHA256'])
        self.assertEqual(hashlib.sha256(config['data']['sha256sums'].encode()).hexdigest(), values['APP_CONTENT_MANIFEST_SHA256'])
        self.assertFalse(any(d['metadata']['name'] == 'consense-web' for d in docs if d['kind'] == 'Service'))

    def test_external_app_rejects_unlisted_web_files_and_path_escape(self):
        self.external_app()
        (self.root / 'release/web/unlisted.js').write_text('unexpected')
        with self.assertRaisesRegex(ValueError, 'inventory'):
            delivery.validate(self.manifest, self.root)
        self.manifest['externalApp']['webDirectory'] = '../outside'
        with self.assertRaisesRegex(ValueError, 'path'):
            delivery.validate(self.manifest, self.root)

    def test_external_app_rejects_unlisted_nonweb_inside_release(self):
        self.external_app()
        (self.root / 'release/unlisted.txt').write_text('unexpected')
        with self.assertRaisesRegex(ValueError, 'App release file inventory'):
            self.prepare()
        self.assertFalse((self.root / 'prepared').exists())

    def test_configmap_inventory_limits_combined_payload(self):
        files = [f'web/{i:04d}' + 'a' * 85 for i in range(3000)]
        self.assertLess(len(delivery.app_release_list(files)), 800000)
        with self.assertRaisesRegex(ValueError, 'combined App inventory too large'):
            delivery.validate_app_inventory_size('app.jar', files, files)

    def test_sibling_jar_and_web_include_declared_shared_assets(self):
        self.external_app()
        (self.root / 'release/app.jar').rename(self.root / 'app.jar')
        jar = next(row for row in self.manifest['assets'] if row['path'] == 'release/app.jar')
        jar['path'] = 'app.jar'
        self.manifest['externalApp']['jar'] = 'app.jar'
        delivery.validate(self.manifest, self.root)
        docs = delivery.documents(self.manifest, 'a' * 64)
        config = next(d for d in docs if d['kind'] == 'ConfigMap')['data']
        self.assertEqual('app.jar\nrelease/web/assets/main.js\nrelease/web/index.html\nworker.py\n', config['release-files'])
        with tempfile.TemporaryDirectory() as external:
            manifest_path = Path(external) / 'manifest.json'
            manifest_path.write_text(json.dumps(self.manifest))
            with self.assertRaisesRegex(ValueError, 'outside App release root'):
                delivery.prepare(manifest_path, self.root, self.root / 'prepared')

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_external_entrypoint_checks_actual_files_before_starting_java(self):
        self.external_app()
        self.prepare()
        docs = json.loads((self.root / 'prepared/kubernetes.json').read_bytes())['items']
        config = next(d for d in docs if d['kind'] == 'ConfigMap')['data']
        checks = self.root / 'checks'; checks.mkdir()
        for name, value in config.items():
            (checks / name).write_text(value)
        binaries = self.root / 'bin'; binaries.mkdir()
        java = binaries / 'java'
        java.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$JAVA_ARGS"\n')
        java.chmod(0o755)
        marker = self.root / 'java-args'
        env = {**os.environ, 'APP_ARTIFACT_ROOT': str(self.root), 'APP_FILE_MANIFEST': str(checks),
               'APP_JAR': 'release/app.jar', 'APP_WEB_DIRECTORY': 'release/web', 'JAVA_ARGS': str(marker),
               'APP_RELEASE_DIRECTORY': 'release', 'APP_RELEASE_FILES_SHA256': hashlib.sha256((checks / 'release-files').read_bytes()).hexdigest(),
               'APP_CONTENT_MANIFEST_SHA256': hashlib.sha256((checks / 'sha256sums').read_bytes()).hexdigest(),
               'PATH': str(binaries) + os.pathsep + os.environ['PATH']}
        entrypoint = Path(__file__).with_name('app-entrypoint.sh')
        def run(cwd=None):
            return subprocess.run(['sh', str(entrypoint)], env=env, cwd=cwd, capture_output=True, text=True)
        self.assertEqual(0, run().returncode)
        self.assertIn('--spring.web.resources.static-locations=file:' + str(self.root) + '/release/web/', marker.read_text())
        marker.unlink()
        env['APP_ARTIFACT_ROOT'] = self.root.name
        self.assertNotEqual(0, run(self.root.parent).returncode, 'a relative root cannot resolve the same web location')
        self.assertFalse(marker.exists())
        env['APP_ARTIFACT_ROOT'] = str(self.root)
        alias = self.root / 'root-link'
        alias.symlink_to(self.root, target_is_directory=True)
        env['APP_ARTIFACT_ROOT'] = str(alias)
        self.assertNotEqual(0, run().returncode, 'a symlinked artifact root must not start')
        self.assertFalse(marker.exists())
        alias.unlink()
        alias.symlink_to(self.root.parent, target_is_directory=True)
        env['APP_ARTIFACT_ROOT'] = str(alias / self.root.name)
        self.assertNotEqual(0, run().returncode, 'a symlinked artifact-root parent must not start')
        self.assertFalse(marker.exists())
        alias.unlink()
        env['APP_ARTIFACT_ROOT'] = str(self.root)
        (self.root / 'release').rename(self.root / 'other-release')
        (self.root / 'release').symlink_to('other-release', target_is_directory=True)
        self.assertNotEqual(0, run().returncode, 'a symlinked release parent must not start')
        self.assertFalse(marker.exists())
        (self.root / 'release').unlink()
        (self.root / 'other-release').rename(self.root / 'release')
        (self.root / 'release/other.jar').write_bytes(b'unlisted jar')
        release_list = checks / 'release-files'
        original_release_list = release_list.read_text()
        release_list.write_text('app.jar\nother.jar\nweb/assets/main.js\nweb/index.html\n')
        env['APP_RELEASE_FILES_SHA256'] = hashlib.sha256(release_list.read_bytes()).hexdigest()
        env['APP_JAR'] = 'release/other.jar'
        self.assertNotEqual(0, run().returncode, 'an unlisted JAR must not start')
        self.assertFalse(marker.exists())
        sums = checks / 'sha256sums'
        original_sums = sums.read_text()
        with sums.open('a') as stream:
            stream.write(hashlib.sha256(b'unlisted jar').hexdigest() + '  release/other.jar\n')
        env['APP_CONTENT_MANIFEST_SHA256'] = hashlib.sha256(sums.read_bytes()).hexdigest()
        self.assertNotEqual(0, run().returncode, 'a second non-web file must not select another JAR')
        self.assertFalse(marker.exists())
        sums.write_text(original_sums)
        env['APP_CONTENT_MANIFEST_SHA256'] = hashlib.sha256(sums.read_bytes()).hexdigest()
        (self.root / 'release/other.jar').unlink()
        release_list.write_text(original_release_list)
        env['APP_RELEASE_FILES_SHA256'] = hashlib.sha256(release_list.read_bytes()).hexdigest()
        env['APP_JAR'] = 'release/web/index.html'
        self.assertNotEqual(0, run().returncode, 'a listed web asset must not run as the JAR')
        self.assertFalse(marker.exists())
        env['APP_JAR'] = 'release/app.jar'
        (self.root / 'release/app.jar').unlink()
        self.assertNotEqual(0, run().returncode, 'a missing JAR must not start')
        self.assertFalse(marker.exists())
        (self.root / 'other.jar').write_bytes(b'fixture jar')
        (self.root / 'release/app.jar').symlink_to('../other.jar')
        self.assertNotEqual(0, run().returncode, 'a symlinked JAR must not start')
        self.assertFalse(marker.exists())
        (self.root / 'release/app.jar').unlink()
        (self.root / 'release/app.jar').write_bytes(b'fixture jar')
        sums.write_text(original_sums.replace(hashlib.sha256(b'fixture html').hexdigest(), 'z' * 64))
        env['APP_CONTENT_MANIFEST_SHA256'] = hashlib.sha256(sums.read_bytes()).hexdigest()
        (self.root / 'release/web/index.html').write_text('tampered')
        self.assertNotEqual(0, run().returncode, 'a malformed checksum must not skip a changed web file')
        self.assertFalse(marker.exists())
        sums.write_text(original_sums)
        env['APP_CONTENT_MANIFEST_SHA256'] = hashlib.sha256(sums.read_bytes()).hexdigest()
        (self.root / 'release/web/index.html').write_bytes(b'fixture html')
        (self.root / 'release/web/assets/main.js').write_text('tampered')
        self.assertNotEqual(0, run().returncode)
        self.assertFalse(marker.exists())
        (self.root / 'release/web/assets/main.js').write_bytes(b'fixture js')
        (self.root / 'release/web/extra.js').write_text('unexpected')
        self.assertNotEqual(0, run().returncode)
        self.assertFalse(marker.exists())

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
        self.external_app()
        delivery.validate(self.manifest, self.root)
        external = delivery.documents(self.manifest, 'a' * 64)
        deployments = [row for row in external if row['kind'] == 'Deployment']
        self.assertEqual(4, len(deployments))
        resources = [row['spec']['template']['spec']['containers'][0]['resources']['requests'] for row in deployments]
        resources.append(job['spec']['template']['spec']['containers'][0]['resources']['requests'])
        self.assertEqual(26, sum(int(row['cpu']) for row in resources))
        self.assertEqual(244, sum(int(row['memory'].removesuffix('G')) for row in resources))


ENTRY = Path(__file__).with_name('app-entrypoint.sh')

def sha(data):
    return hashlib.sha256(data).hexdigest()

class ReleaseScopeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.release = self.base / 'release'
        (self.release / 'web').mkdir(parents=True)
        self.jar = self.release / 'app.jar'
        self.web = self.release / 'web/index.html'
        self.jar.write_bytes(b'jar fixture')
        self.web.write_bytes(b'web fixture')
        self.checks = self.base / 'checks'
        self.checks.mkdir()
        (self.checks / 'web-files').write_text('release/web/index.html\n')
        (self.checks / 'sha256sums').write_text(
            f'{sha(self.jar.read_bytes())}  release/app.jar\n{sha(self.web.read_bytes())}  release/web/index.html\n')
        self.list = self.checks / 'release-files'
        self.list.write_text('app.jar\nweb/index.html\n')
        binpath = self.base / 'bin'
        binpath.mkdir()
        java = binpath / 'java'
        java.write_text('#!/bin/sh\nprintf invoked > "$MARKER"\n')
        java.chmod(0o755)
        self.marker = self.base / 'marker'
        self.env = {'PATH': str(binpath) + ':/usr/bin:/bin', 'APP_ARTIFACT_ROOT': str(self.base),
                    'APP_FILE_MANIFEST': str(self.checks), 'APP_JAR': 'release/app.jar',
                    'APP_WEB_DIRECTORY': 'release/web', 'APP_RELEASE_DIRECTORY': 'release',
                    'APP_RELEASE_FILES_SHA256': sha(self.list.read_bytes()),
                    'APP_CONTENT_MANIFEST_SHA256': sha((self.checks / 'sha256sums').read_bytes()), 'MARKER': str(self.marker)}

    def tearDown(self):
        self.tmp.cleanup()

    def run_entry(self):
        result = subprocess.run(['/bin/sh', str(ENTRY)], env=self.env, capture_output=True, timeout=10)
        return result.returncode, self.marker.exists()

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_complete_release_allows_other_shared_assets_outside_scope(self):
        (self.base / 'worker.py').write_text('outside App release')
        self.assertEqual((0, True), self.run_entry())

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_extra_nonweb_in_release_is_rejected(self):
        (self.release / 'extra.txt').write_text('unexpected')
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_missing_inventory_or_pin_is_rejected(self):
        self.list.unlink()
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())
        self.list.write_text('app.jar\nweb/index.html\n')
        del self.env['APP_RELEASE_FILES_SHA256']
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_missing_content_pin_and_scope_are_rejected(self):
        content_pin = self.env.pop('APP_CONTENT_MANIFEST_SHA256')
        self.assertNotEqual(0, self.run_entry()[0])
        self.env['APP_CONTENT_MANIFEST_SHA256'] = content_pin
        del self.env['APP_RELEASE_DIRECTORY']
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_scope_cannot_exclude_jar_or_web(self):
        self.env['APP_RELEASE_DIRECTORY'] = 'release/web'
        self.list.write_text('index.html\n')
        self.env['APP_RELEASE_FILES_SHA256'] = sha(self.list.read_bytes())
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_changed_inventory_pin_rejected(self):
        (self.release / 'extra.txt').write_text('unexpected')
        self.list.write_text('app.jar\nextra.txt\nweb/index.html\n')
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_newline_filename_rejected(self):
        (self.release / 'two\nfiles.txt').write_text('unexpected')
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_jar_and_content_manifest_changed_together_are_rejected(self):
        self.jar.write_bytes(b'changed jar')
        (self.checks / 'sha256sums').write_text(
            f'{sha(self.jar.read_bytes())}  release/app.jar\n{sha(self.web.read_bytes())}  release/web/index.html\n')
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_link_and_special_file_rejected(self):
        (self.release / 'link').symlink_to('app.jar')
        self.assertNotEqual(0, self.run_entry()[0])
        (self.release / 'link').unlink()
        os.mkfifo(self.release / 'pipe')
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())

    @unittest.skipIf(os.name == 'nt', 'requires Linux shell utilities')
    def test_local_same_source_manifest_layout(self):
        entry = self.release / 'app-entrypoint.sh'
        entry.write_bytes(ENTRY.read_bytes())
        manifest = self.release / 'manifest'
        manifest.mkdir()
        (manifest / 'web-files').write_text('web/index.html\n')
        (manifest / 'sha256sums').write_text(
            f'{sha(self.jar.read_bytes())}  app.jar\n{sha(self.web.read_bytes())}  web/index.html\n')
        pins = delivery.prepare_local_app_release_inventory(self.release, sha(entry.read_bytes()))
        self.env.update(APP_ARTIFACT_ROOT=str(self.release), APP_FILE_MANIFEST=str(manifest), APP_JAR='app.jar',
                        APP_WEB_DIRECTORY='web', APP_RELEASE_DIRECTORY='.', APP_RELEASE_FILES_SHA256=pins['releaseFilesSha256'],
                        APP_CONTENT_MANIFEST_SHA256=pins['contentManifestSha256'])
        self.assertEqual((0, True), self.run_entry())
        self.marker.unlink()
        (self.release / 'extra.txt').write_text('unexpected')
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())
        (self.release / 'extra.txt').unlink()
        self.jar.write_bytes(b'changed jar')
        (manifest / 'sha256sums').write_text(
            f'{sha(self.jar.read_bytes())}  app.jar\n{sha(self.web.read_bytes())}  web/index.html\n')
        self.assertNotEqual(0, self.run_entry()[0])
        self.assertFalse(self.marker.exists())

    def test_local_generator_refuses_unknown_file(self):
        entry = self.release / 'app-entrypoint.sh'
        entry.write_bytes(ENTRY.read_bytes())
        manifest = self.release / 'manifest'
        manifest.mkdir()
        (manifest / 'web-files').write_text('web/index.html\n')
        (manifest / 'sha256sums').write_text(
            f'{sha(self.jar.read_bytes())}  app.jar\n{sha(self.web.read_bytes())}  web/index.html\n')
        (self.release / 'unknown.txt').write_text('unexpected')
        with self.assertRaisesRegex(ValueError, 'release inventory differs'):
            delivery.prepare_local_app_release_inventory(self.release, sha(entry.read_bytes()))
        self.assertFalse((manifest / 'release-files').exists())

    def test_local_generator_emits_complete_inventory_and_two_pins(self):
        entry = self.release / 'app-entrypoint.sh'
        entry.write_bytes(ENTRY.read_bytes())
        manifest = self.release / 'manifest'
        manifest.mkdir()
        (manifest / 'web-files').write_text('web/index.html\n')
        (manifest / 'sha256sums').write_text(
            f'{sha(self.jar.read_bytes())}  app.jar\n{sha(self.web.read_bytes())}  web/index.html\n')
        pins = delivery.prepare_local_app_release_inventory(self.release, sha(entry.read_bytes()))
        expected = b'app-entrypoint.sh\napp.jar\nmanifest/release-files\nmanifest/sha256sums\nmanifest/web-files\nweb/index.html\n'
        self.assertEqual(expected, (manifest / 'release-files').read_bytes())
        self.assertEqual(sha(expected), pins['releaseFilesSha256'])
        self.assertEqual(sha((manifest / 'sha256sums').read_bytes()), pins['contentManifestSha256'])


if __name__ == '__main__':
    unittest.main()
