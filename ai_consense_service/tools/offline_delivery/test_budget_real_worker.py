"""Real worker admission and SHA-pinned config; isolated fake execution only."""
import base64
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
sys.path[:0]=[str(HERE),str(ROOT/'ai_consense_service/tools/offline_delivery'),
              str(ROOT/'ai_consense_service/tools/vetting_eval')]
import remote_gpu_models
import trusted_stage_pins
import model_session_observer
from model_session_observer import ModelSessionObserver
from fixed_gpu_worker import Worker
from fixed_gpu_client import Client
from request_stage import encoded


class Admitted(Exception): pass


class BudgetTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name); self.profile={'adapter':'consense.model-session.v1'}
        self.profile_sha=hashlib.sha256(encoded(self.profile)).hexdigest()
        boot={'command':['/fixture/python'], 'cwd':'/fixture','cgroupRoot':'/unused','env':{},
              'runtimeAssets':[{'path':'/fixture/python','byteCount':1,'sha256':'a'*64}]}
        self.config={'workspace':str(self.root),'cgroupRoot':'/unused','stages':{
            'document-embedding':{'mode':'model-session','profile':self.profile,'timeoutSeconds':600,'boot':boot},
            'query-embedding-rerank':{'mode':'model-session','profile':self.profile,'timeoutSeconds':300,'boot':boot}}}
        self.config_path=self.root/'gpu.json'; self.manifest_path=self.root/'profiles.json'
        self.config_path.write_bytes(encoded(self.config))
        self.manifest_path.write_bytes(encoded({'schema':'consense.fixed-gpu-profiles.v1',
            'profiles':{'document-embedding':self.profile_sha,'query-embedding-rerank':self.profile_sha}}))
        self.env={'CONSENSE_FIXED_GPU_CONFIG_SHA256':hashlib.sha256(self.config_path.read_bytes()).hexdigest(),
                  'CONSENSE_FIXED_GPU_PROFILE_MANIFEST':str(self.manifest_path),
                  'CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256':hashlib.sha256(self.manifest_path.read_bytes()).hexdigest()}
        for p in (patch.dict(os.environ,self.env,clear=False),
                  patch.object(trusted_stage_pins,'GPU_CONFIG_PATH',str(self.config_path)),
                  patch.object(model_session_observer,'GPU_CONFIG_PATH',str(self.config_path))):
            p.start(); self.addCleanup(p.stop)
        self.pins=trusted_stage_pins.load_trusted_stage_pins('document-embedding',self.config_path,
            self.env['CONSENSE_FIXED_GPU_CONFIG_SHA256'],self.manifest_path,self.env['CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256'])

    def verify_admission(self, request_seconds, low, high):
        worker=Worker(self.config,'fixture-secret')
        runtime=SimpleNamespace(logical_identity=lambda kind:{'model':kind}, inference_cap=lambda kind:512)
        backend=SimpleNamespace(MODELS={'embedding':{'name':'fixture'}}, MODEL_SETTINGS={'embedding':{'batchSize':2}},
                                window_runtime=lambda:runtime)
        client=SimpleNamespace(profile_sha256s={'document-embedding':self.profile_sha})
        proxy=remote_gpu_models.RemoteModels(backend,client,dtype='float32',serial_offload=True,timeout=660)
        control={'requestId':'a'*32,'deadline':time.monotonic()+request_seconds,
                 'cancelled':lambda:False,'accept':lambda action:action()}
        observer=ModelSessionObserver(control,'b'*64,'document-embedding',proxy,2,1024)
        def call_worker(client,stage,binding,deadline,cancelled,accept):
            worker.start({'stage':stage,'binding':binding,'bodyBase64':base64.b64encode(b'{}').decode(),
                          'expectedProfileSha256':self.profile_sha,'expectedMode':'model-session'})
            raise Admitted()
        with patch.object(worker,'_validate_profile',return_value=None), \
                patch('fixed_gpu_worker.threading.Thread.start',return_value=None), \
                patch.object(remote_gpu_models.ModelSession,'start',side_effect=call_worker):
            with self.assertRaises(Admitted):
                with proxy.session_scope(control,'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,
                                         observer=observer,execution_sha256=self.pins['executionSha256']):
                    proxy.embedding_model().get_sentence_embedding_dimension()
        budget=observer.bindings['embedding']['deadlineBudgetMs']
        self.assertLessEqual(low,budget); self.assertLessEqual(budget,high)
        self.assertTrue((self.root/'gpu-owner.json').is_file())

    def test_660_is_capped_at_exactly_600_seconds(self): self.verify_admission(660,600000,600000)
    def test_shorter_request_deadline_remains_authoritative(self): self.verify_admission(2,1500,2000)

    def test_timeout_from_pinned_stage_and_tampering_fails_closed(self):
        stage='document-embedding'; profile=self.pins['profileSha256']; execution=self.pins['executionSha256']
        self.assertEqual(remote_gpu_models._trusted_stage_timeout(stage,profile,execution),600)
        self.config['stages'][stage]['timeoutSeconds']=601; self.config_path.write_bytes(encoded(self.config))
        with self.assertRaisesRegex(ValueError,'identity differs'):
            remote_gpu_models._trusted_stage_timeout(stage,profile,execution)
        self.config['stages'][stage]['timeoutSeconds']=True; self.config_path.write_bytes(encoded(self.config))
        self.env['CONSENSE_FIXED_GPU_CONFIG_SHA256']=hashlib.sha256(self.config_path.read_bytes()).hexdigest()
        with patch.dict(os.environ,self.env), self.assertRaises(ValueError):
            remote_gpu_models._trusted_stage_timeout(stage,profile,execution)

    def test_query_stage_has_its_own_pinned_limit(self):
        pins=trusted_stage_pins.load_trusted_stage_pins('query-embedding-rerank',self.config_path,
            self.env['CONSENSE_FIXED_GPU_CONFIG_SHA256'],self.manifest_path,self.env['CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256'])
        self.assertEqual(remote_gpu_models._trusted_stage_timeout('query-embedding-rerank',
                         pins['profileSha256'],pins['executionSha256']),300)
        with self.assertRaisesRegex(ValueError,'pins changed'):
            remote_gpu_models._trusted_stage_timeout('query-embedding-rerank',
                                                     self.pins['profileSha256'],self.pins['executionSha256'])

    def test_config_change_between_trusted_reads_is_rejected(self):
        original=trusted_stage_pins.load_trusted_stage_pins
        def change_after_pins(*args):
            value=original(*args)
            self.config['stages']['document-embedding']['timeoutSeconds']=601
            self.config_path.write_bytes(encoded(self.config))
            return value
        with patch.object(trusted_stage_pins,'load_trusted_stage_pins',side_effect=change_after_pins), \
                self.assertRaisesRegex(ValueError,'GPU configuration identity changed'):
            remote_gpu_models._trusted_stage_timeout('document-embedding',self.pins['profileSha256'],
                                                     self.pins['executionSha256'])

    def test_cancel_during_preflight_never_admits(self):
        cancelled=[False]
        def identity(kind): cancelled[0]=True; return {'model':kind}
        runtime=SimpleNamespace(logical_identity=identity,inference_cap=lambda kind:512)
        backend=SimpleNamespace(MODELS={'embedding':{'name':'fixture'}},MODEL_SETTINGS={'embedding':{'batchSize':2}},
                                window_runtime=lambda:runtime)
        client=SimpleNamespace(profile_sha256s={'document-embedding':self.profile_sha})
        proxy=remote_gpu_models.RemoteModels(backend,client,dtype='float32',serial_offload=True,timeout=660)
        control={'requestId':'a'*32,'deadline':time.monotonic()+660,'cancelled':lambda:cancelled[0],
                 'accept':lambda action:action()}
        observer=ModelSessionObserver(control,'b'*64,'document-embedding',proxy,2,1024)
        with patch.object(remote_gpu_models.ModelSession,'start') as start:
            with self.assertRaisesRegex(ValueError,'cancelled'):
                with proxy.session_scope(control,'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,
                                         observer=observer,execution_sha256=self.pins['executionSha256']):
                    proxy.embedding_model().get_sentence_embedding_dimension()
        start.assert_not_called()

    def test_cancel_during_pinned_read_never_admits(self):
        cancelled=[False]
        runtime=SimpleNamespace(logical_identity=lambda kind:{'model':kind},inference_cap=lambda kind:512)
        backend=SimpleNamespace(MODELS={'embedding':{'name':'fixture'}},MODEL_SETTINGS={'embedding':{'batchSize':2}},
                                window_runtime=lambda:runtime)
        client=SimpleNamespace(profile_sha256s={'document-embedding':self.profile_sha})
        proxy=remote_gpu_models.RemoteModels(backend,client,dtype='float32',serial_offload=True,timeout=660)
        control={'requestId':'a'*32,'deadline':time.monotonic()+660,'cancelled':lambda:cancelled[0],
                 'accept':lambda action:action()}
        observer=ModelSessionObserver(control,'b'*64,'document-embedding',proxy,2,1024)
        original=remote_gpu_models._trusted_stage_timeout
        def reading(*args):
            timeout=original(*args); cancelled[0]=True; return timeout
        with patch.object(remote_gpu_models,'_trusted_stage_timeout',side_effect=reading), \
                patch.object(remote_gpu_models.ModelSession,'start') as start:
            with self.assertRaisesRegex(ValueError,'cancelled'):
                with proxy.session_scope(control,'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,
                                         observer=observer,execution_sha256=self.pins['executionSha256']):
                    proxy.embedding_model().get_sentence_embedding_dimension()
        start.assert_not_called()

    def test_non_2xx_status_does_not_expose_body(self):
        class Response:
            status=400; length=6
            def getheader(self,name,default=None): return '6'
            def isclosed(self): return self.length==0
            def read1(self,amount): self.length=0; return b'secret'
            def close(self): pass
        class Socket:
            def settimeout(self,value): pass
        class Connection:
            sock=Socket()
            def connect(self): pass
            def request(self,*args,**kwargs): pass
            def getresponse(self): return Response()
            def close(self): pass
        client=Client('http://127.0.0.1:1234','fixture-token',{'document-embedding':'d'*64})
        with patch.object(client,'_connection',return_value=Connection()):
            with self.assertRaisesRegex(ValueError,'HTTP status 400') as error:
                client._call('POST','/operations',2,{})
        self.assertNotIn('secret',str(error.exception))

if __name__=='__main__': unittest.main()
