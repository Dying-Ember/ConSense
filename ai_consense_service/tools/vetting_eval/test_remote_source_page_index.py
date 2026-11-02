"""Source-page HTTP fixtures use fake session release and fake publication witness."""
import asyncio
from contextlib import ExitStack, contextmanager
import json
import os
from pathlib import Path
import sys
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
from fastapi.testclient import TestClient
from oer_unified_server import create_unified_app
from test_source_page_index import SourcePageIndexHttpTests
from test_oer_server import OerHttpContractTest
from test_oer_remote_cancellation import RemoteCancellationHttpTest
from test_token_windows import CharacterTokenizer
from token_windows import WindowError
from source_request_context import _owned_workers, _owned_workers_lock
import window_runtime
import oer_request_evidence as evidence

sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'offline_delivery'))


class RemoteSourcePageIndexTest(unittest.TestCase):
    def setUp(self):
        SourcePageIndexHttpTests.setUp(self)
        self.publication_fakes=ExitStack();self.addCleanup(self.publication_fakes.close)
        self.publication_fakes.enter_context(patch('remote_gpu_models._trusted_stage_timeout',return_value=10))
        self.publication_fakes.enter_context(patch('model_session_observer._trusted_stage_pins',
            return_value={'profileSha256':'a'*64, 'executionSha256':'b'*64}))
        # Synthetic source-page ordering fixture; this is not a model release proof.
        self.publication_fakes.enter_context(patch('oer_server._require_publication_model_proxy'))
        self.publication_fakes.enter_context(patch('oer_server._PublicationModelObserver.publication_verified',return_value=True))
        self.publication_fakes.enter_context(patch.object(evidence.RequestEvidence,'_verify_publication_evidence',
            return_value={'sourceEvidenceSha256':'a'*64,'modelReleaseEvidenceSha256':'b'*64}))
        if os.name=='nt':
            self.publication_fakes.enter_context(patch.object(evidence,'_sync_file'))
            self.publication_fakes.enter_context(patch.object(evidence,'_sync_directory'))
        @contextmanager
        def fake_session(*args,**kwargs):yield SimpleNamespace(receipts=[])
        from remote_gpu_models import RemoteModels
        self.client.close();self.backend._remote_models=RemoteModels(self.backend,SimpleNamespace(),dtype='float32',serial_offload=True,timeout=5)
        self.backend._remote_models.session_scope=fake_session
        self.backend.model_runtime=self.backend._remote_models.model_runtime
        self.app=create_unified_app(self.policy,backend=self.backend);self.rt=self.backend._windows_runtime
        self.rt.tokenizer=lambda kind:CharacterTokenizer()
        self.rt.identity=lambda kind:{'kind':kind,'tokenizerSha256':'AUTHORED-only'}
        self.client=TestClient(self.app)
        self.address=self.projection['projectionSha256'];self.pins={self.address:'c'*64}
        self.body={'projectId':'AUTHORED-remote-pins','module':'vetting',
                   'projectionSha256':[self.address],'sourcePageEvidence':self.pins}

    def tearDown(self): OerHttpContractTest.tearDown(self)

    def test_pin_is_preserved_in_successful_remote_index_signature(self):
        pages=self.app.state.source_registry.source_pages
        with patch.object(pages,'_read',return_value={'authored':True}):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual(self.pins,response.json()['sourcePageEvidence'])
        self.assertEqual(self.pins,self.backend._projects['AUTHORED-remote-pins']['signature']['sourcePageEvidence'])

    def disconnect_at(self,entered):
        async def exercise():
            body=json.dumps(self.body).encode();first=True;messages=[];workers=[]
            async def receive():
                nonlocal first
                if first:
                    first=False;return {'type':'http.request','body':body,'more_body':False}
                if entered.is_set():
                    with _owned_workers_lock:
                        workers.extend(worker for worker in _owned_workers.values() if worker.is_alive())
                    return {'type':'http.disconnect'}
                await asyncio.sleep(10);return {'type':'http.disconnect'}
            async def send(message):messages.append(message)
            scope={'type':'http','asgi':{'version':'3.0','spec_version':'2.3'},'http_version':'1.1',
                   'method':'POST','scheme':'http','path':'/index-canonical','raw_path':b'/index-canonical',
                   'query_string':b'','headers':[(b'content-type',b'application/json')],
                   'client':('127.0.0.1',1),'server':('localhost',80)}
            await asyncio.wait_for(self.app(scope,receive,send),5)
            for worker in workers:
                await asyncio.to_thread(worker.join,3)
                self.assertFalse(worker.is_alive(), 'owned worker did not exit')
            return messages
        return asyncio.run(exercise())

    def test_disconnect_after_encoder_entry_never_publishes_pinned_index(self):
        entered=threading.Event();cancelled=threading.Event()
        def encoder(texts):
            entered.set();RemoteCancellationHttpTest.wait_for_cancellation(cancelled)
            return np.asarray([[1.,0.] for _ in texts],dtype=np.float32)
        self.backend.encode_embeddings=encoder
        with patch.object(self.app.state.source_registry.source_pages,'_read',return_value={'authored':True}):
            messages=self.disconnect_at(entered)
        self.assertTrue(entered.is_set());self.assertTrue(cancelled.is_set())
        self.assertEqual(503,next(x['status'] for x in messages if x['type']=='http.response.start'))
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_cancel_during_final_hash_recheck_refuses_replace(self):
        entered=threading.Event();cancelled=threading.Event();original=window_runtime.file_identity;visits=[0]
        def observe(path):
            result=original(path)
            if '.pending-' in str(path):
                visits[0]+=1
                if visits[0]==2:
                    entered.set();RemoteCancellationHttpTest.wait_for_cancellation(cancelled)
            return result
        with patch.object(self.app.state.source_registry.source_pages,'_read',return_value={'authored':True}), \
                patch.object(window_runtime,'file_identity',side_effect=observe):
            messages=self.disconnect_at(entered)
        self.assertTrue(entered.is_set());self.assertTrue(cancelled.is_set())
        self.assertEqual(503,next(x['status'] for x in messages if x['type']=='http.response.start'))
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_failure_after_frame_exit_keeps_replaced_index_blocked(self):
        registry=self.app.state.source_registry;original=registry.validation_frame
        @contextmanager
        def late_failure(*args,**kwargs):
            with original(*args,**kwargs):
                yield
            raise WindowError('AUTHORED_LATE_FRAME_FAILURE','fixture exit guard failed')
        with patch.object(registry,'validation_frame',side_effect=late_failure), \
                patch.object(registry.source_pages,'_read',return_value={'authored':True}):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(422,response.status_code,response.text)
        self.assertTrue(self.backend.metadata_path('AUTHORED-remote-pins').exists())
        self.assertTrue(self.backend.request_evidence().block_path('AUTHORED-remote-pins').exists())
        self.assertTrue(any(values for values in self.db.collections.values()))

    def test_directory_sync_failure_is_reported_as_uncertain_without_success(self):
        registry=self.app.state.source_registry
        with patch.object(registry.source_pages,'_read',return_value={'authored':True}), \
                patch('window_runtime.sync_index_directory',side_effect=OSError('authored sync failure')):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(503,response.status_code,response.text)
        self.assertEqual('INDEX_PUBLICATION_DURABILITY_UNCERTAIN',response.json()['detail']['kind'])
        self.assertTrue(self.backend.metadata_path('AUTHORED-remote-pins').exists())
        self.assertNotIn('AUTHORED-remote-pins',self.backend._projects)


if __name__=='__main__': unittest.main()
