"""Actual tiny native HTTP child, CAS and transaction; Kubernetes API is a CLI fixture."""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import unittest
from unittest.mock import patch
from urllib.request import urlopen

from test_delivery import DeliveryFixture
import native_job_provider
import native_session_worker
import stage_worker

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'vetting_eval'))
from artifact_store import ArtifactStore


class NativeJobProviderTest(DeliveryFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        with socket.socket() as temporary:
            temporary.bind(('127.0.0.1',0)); self.port = temporary.getsockname()[1]
        self.workspace = self.root/'workspace'; self.workspace.mkdir()
        self.store = ArtifactStore(self.workspace/'cas')
        script = "from http.server import BaseHTTPRequestHandler,HTTPServer\nclass H(BaseHTTPRequestHandler):\n def do_GET(self):\n  self.send_response(200);self.end_headers();self.wfile.write(b'authored native fixture')\nHTTPServer(('127.0.0.1',"+str(self.port)+"),H).serve_forever()"
        self.identity = {'parserVersion':'authored-only','engine':'authored-tiny-http','models':[{'modelId':'no-model','revision':'a'*40}],'config':{}}
        self.profile = {'adapter':'consense.native-api-session.v1','command':[sys.executable,'-c',script],'nativeIdentity':self.identity,
            'environment':{},'readyPath':'/health','timeoutSeconds':10}
        self.manifest['requestStages'] = {'paddle': {'command':[sys.executable,str(Path(native_session_worker.__file__).resolve())],
            'runtimeAssets':['worker.py'],'profile':self.profile}}
        self.manifest['nativeServices'] = {'paddle': {'name':'authored-native','clusterIP':'127.0.0.1','port':self.port}}
        receipt = self.prepare()
        self.input = self.store.put_bytes(b'actual source image')
        self.output = self.store.put_bytes(b'actual native raw response')
        self.task = {'parser':'paddleocr-vl','inputSha256':self.input['sha256'],'inputByteCount':self.input['byteCount'],
            'originalSourceSha256':'b'*64,'physicalPageNo':5,'profileSha256':hashlib.sha256(native_job_provider.encoded(self.identity)).hexdigest()}
        self.provider = {'baseUrl':'http://127.0.0.1:'+str(self.port), 'taskBinding':self.task,'identity':self.identity,
            'ownedJob': {'prepared':str(self.root/'prepared'),'artifacts':str(self.root),'workspace':str(self.workspace),
                'manifestSha256':receipt['manifestSha256'],'stage':'paddle','serviceName':'authored-native',
                'readyTimeoutSeconds':5,'cleanupTimeoutSeconds':10}}
        self.created = self.deleted = self.finished = False
        self.calls = []

    def runner(self, argv, payload=None):
        self.calls.append(argv)
        if 'get' in argv and 'pvc' in argv: return {'metadata':{'uid':'actual-fixture-pvc'}}
        if 'get' in argv and 'service' in argv: return {'spec':{'clusterIP':'127.0.0.1','ports':[{'port':self.port,'targetPort':self.port}]}}
        if 'get' in argv and 'pods' in argv:
            return {'items':[{'metadata':{'uid':'actual-fixture-pod','ownerReferences':[{'uid':'actual-fixture-job'}]}}]
                if self.created and not self.deleted else []}
        if 'get' in argv and 'endpoints' in argv:
            return {'subsets':[{'addresses':[{'targetRef':{'uid':'actual-fixture-pod'}}]}] if self.created else []}
        if 'create' in argv:
            self.created = True; job = json.loads(payload)
            env = {row['name']:row['value'] for row in job['spec']['template']['spec']['containers'][0]['env'] if 'value' in row}
            env.update(CONSENSE_JOB_UID='actual-fixture-job',CONSENSE_POD_UID='actual-fixture-pod')
            with patch.dict(os.environ,env):
                result = stage_worker.execute(env['CONSENSE_OPERATION_MANIFEST'],self.root,self.workspace,'paddle')
            self.finished = True; self.succeeded = result['status'] == 'persisted'
            return {'metadata':{'uid':'actual-fixture-job'}}
        if 'get' in argv and 'job' in argv:
            if not self.created:
                if '--ignore-not-found=true' in argv: return {}
                raise subprocess.CalledProcessError(1, argv, stderr='Error from server (NotFound): jobs.batch not found')
            return {'metadata':{'uid':'actual-fixture-job'},'status':{'succeeded':1} if self.finished and self.succeeded else {'failed':1} if self.finished else {}}
        if 'delete' in argv: self.deleted = True
        return {}

    def transaction(self):
        return {'status':'persisted','taskBinding':self.task,'input':self.input,
                'attempts':[{'parser':'paddleocr-vl','status':'completed','response':self.output}], 'rawOutputs':{'model_output':self.output}}

    def test_running_owned_job_is_released_while_caller_http_is_still_waiting(self):
        from native_provider_lifecycle import ProviderLifecycleError
        cancelled, deleted = threading.Event(), threading.Event()
        cancelled_at = [None]
        def running(argv, payload=None):
            if 'create' in argv:
                self.calls.append(argv); self.created = True
                return {'metadata': {'uid': 'actual-fixture-job'}}
            if 'delete' in argv:
                self.calls.append(argv); self.deleted = True; deleted.set(); return {}
            if 'get' in argv and 'job' in argv and self.created and cancelled.is_set():
                if cancelled_at[0] is None: cancelled_at[0] = time.monotonic()
                if time.monotonic() - cancelled_at[0] > 1:
                    return {'metadata': {'uid': 'actual-fixture-job'}, 'status': {'failed': 1}}
            return self.runner(argv, payload)
        with self.assertRaises(ProviderLifecycleError):
            with native_job_provider.owned_gpu_provider(self.provider, 3,
                    self.store.root/'jobs'/'cancel-running'/'paddle', runner=running,
                    ready=lambda _: True, cancelled=cancelled.is_set) as lifecycle:
                cancelled.set()
                self.assertTrue(deleted.wait(.5), 'An outstanding native HTTP call must not delay owned Job cancellation')
        self.assertEqual(lifecycle['exitReceipt']['status'], 'released')
        self.assertTrue(lifecycle['exitReceipt']['podsAbsent'])
        self.assertEqual(sum('create' in row for row in self.calls), 1)
        self.assertEqual(sum('delete' in row for row in self.calls), 1)

    def test_complete_http_session_and_exact_cas_persist_before_pod_release(self):
        with native_job_provider.owned_gpu_provider(self.provider,10,self.store.root/'jobs'/'first'/'paddle',runner=self.runner) as lifecycle:
            with urlopen(self.provider['baseUrl']+'/health', timeout=2) as response:
                self.assertEqual(b'authored native fixture', response.read())
            self.assertFalse(self.deleted)
            lifecycle['transaction'] = self.transaction()
        self.assertTrue(self.finished); self.assertTrue(self.deleted)
        self.assertEqual('released',lifecycle['exitReceipt']['status'])
        self.assertEqual(5,lifecycle['taskBinding']['physicalPageNo'])
        receipt = next(self.workspace.glob('gpu-operations/*/operation.receipt.json'))
        self.assertFalse(json.loads(receipt.read_bytes())['targetAccepted'])

    def test_missing_transaction_fails_and_cannot_admit_a_next_provider(self):
        with self.assertRaisesRegex(ValueError,'transaction was not persisted'):
            with native_job_provider.owned_gpu_provider(self.provider,10,self.store.root/'jobs'/'missing'/'paddle',runner=self.runner):
                pass
        self.assertTrue(self.deleted)
        self.assertEqual(1,sum('create' in row for row in self.calls))

    def test_job_not_created_yet_is_absent_then_actual_ready_session_completes(self):
        creation_observed = threading.Event()
        absent_queries = []
        def delayed_runner(argv, payload=None):
            if 'create' in argv:
                self.assertTrue(creation_observed.wait(2), 'readiness must observe actual pre-creation absence')
            if 'get' in argv and 'job' in argv and not self.created:
                absent_queries.append(list(argv))
                creation_observed.set()
                return self.runner(argv, payload)
            return self.runner(argv, payload)
        with native_job_provider.owned_gpu_provider(self.provider,10,self.store.root/'jobs'/'delayed'/'paddle',runner=delayed_runner) as lifecycle:
            lifecycle['transaction'] = self.transaction()
        self.assertTrue(absent_queries)
        self.assertTrue(all('--ignore-not-found=true' in argv for argv in absent_queries))
        self.assertTrue(self.finished); self.assertTrue(self.deleted)

    def test_terminal_dispatch_failure_is_preserved_before_readiness(self):
        with patch.object(native_job_provider,'run_request',side_effect=RuntimeError('actual terminal dispatch failure')):
            with self.assertRaisesRegex(RuntimeError,'actual terminal dispatch failure'):
                with native_job_provider.owned_gpu_provider(self.provider,10,self.store.root/'jobs'/'dispatch-failed'/'paddle',runner=self.runner):
                    self.fail('failed dispatch cannot admit HTTP calls')

    def test_cli_authorization_failure_is_not_treated_as_transient_absence(self):
        initialized = threading.Event(); cancellation = None
        actual_run = native_job_provider.run_request
        def dispatch(*args, **kwargs):
            nonlocal cancellation
            cancellation = kwargs['cancellation']; initialized.set()
            return actual_run(*args, **kwargs)
        def forbidden(argv, payload=None):
            if 'get' in argv and 'pvc' in argv:
                self.assertTrue(cancellation.wait(2), 'failed readiness must cancel pending dispatch before launch')
            if 'get' in argv and 'job' in argv:
                self.assertTrue(initialized.wait(2))
                raise subprocess.CalledProcessError(1,argv,stderr='Error from server (Forbidden): jobs.batch is forbidden')
            return self.runner(argv,payload)
        with patch.object(native_job_provider,'run_request',side_effect=dispatch):
            with self.assertRaises(subprocess.CalledProcessError):
                with native_job_provider.owned_gpu_provider(self.provider,10,self.store.root/'jobs'/'forbidden'/'paddle',runner=forbidden):
                    self.fail('Forbidden cannot admit a native HTTP session')
        self.assertFalse(any('create' in row for row in self.calls), 'cancelled dispatch cannot create a late GPU Job')

    def test_changed_actual_cas_or_physical_page_rejected(self):
        transaction = self.transaction(); transaction['taskBinding'] = {**self.task,'physicalPageNo':6}
        with self.assertRaisesRegex(ValueError,'transaction was not persisted'):
            native_session_worker.verify_transaction(transaction,self.task,self.store.root)
        (self.store.root/self.output['relativePath']).write_bytes(b'tampered actual output')
        with self.assertRaisesRegex(ValueError,'SHA-256'):
            native_session_worker.verify_transaction(self.transaction(),self.task,self.store.root)


if __name__ == '__main__': unittest.main()
