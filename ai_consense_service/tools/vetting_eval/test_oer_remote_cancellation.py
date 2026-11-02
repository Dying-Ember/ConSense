"""Public OER cancellation with fake session admission, vectors and database."""
import asyncio
import json
import threading
import time
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
from fastapi.testclient import TestClient
from oer_server import create_app
from oer_unified_server import create_unified_app
from test_oer_server import OerHttpContractTest
from test_token_windows import CharacterTokenizer
import source_request_context as source_context
from source_request_context import check, _owned_workers, _owned_workers_lock
import oer_request_evidence
from token_windows import WindowError
import window_runtime

sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'offline_delivery'))


class RemoteCancellationHttpTest(unittest.TestCase):
    def setUp(self):
        OerHttpContractTest.setUp(self)
        self.client.close()
        self.session_gate=patch.object(window_runtime,'require_index_session',return_value={
            'projectId':'oer-test','module':'vetting','sourceScopeSha256':'a'*64})
        self.session_gate.start();self.addCleanup(self.session_gate.stop)
        for name in ('_sync_file', '_sync_directory'):
            substitute=patch.object(oer_request_evidence,name)
            substitute.start();self.addCleanup(substitute.stop)
        from remote_gpu_models import RemoteModels
        self.backend._remote_models=RemoteModels(self.backend,SimpleNamespace(),dtype='float32',serial_offload=True,timeout=5)
        self.backend.model_runtime=self.backend._remote_models.model_runtime
        self.app=create_app(self.policy,backend=self.backend)
        self.rt=self.backend._windows_runtime
        self.rt.tokenizer=lambda kind:CharacterTokenizer()
        self.rt.identity=lambda kind:{'kind':kind,'tokenizerSha256':'authored-contract'}
        self.client=TestClient(self.app)

    def tearDown(self):
        OerHttpContractTest.tearDown(self)

    def disconnect_at(self,entered):
        async def exercise():
            body=json.dumps({'projectId':'oer-test','chunks':self.corpus}).encode();first=True;messages=[];workers=[]
            async def receive():
                nonlocal first
                if first:
                    first=False;return {'type':'http.request','body':body,'more_body':False}
                if entered.is_set():
                    with _owned_workers_lock:
                        workers.extend(worker for worker in _owned_workers.values() if worker.is_alive())
                    return {'type':'http.disconnect'}
                await asyncio.sleep(10)
                return {'type':'http.disconnect'}
            async def send(message):messages.append(message)
            scope={'type':'http','asgi':{'version':'3.0','spec_version':'2.3'},'http_version':'1.1',
                   'method':'POST','scheme':'http','path':'/index','raw_path':b'/index',
                   'query_string':b'','headers':[(b'content-type',b'application/json')],
                   'client':('127.0.0.1',1),'server':('localhost',80)}
            await asyncio.wait_for(self.app(scope,receive,send),5)
            for worker in workers:
                await asyncio.to_thread(worker.join, 3)
                self.assertFalse(worker.is_alive(), 'owned worker did not exit')
            return messages
        return asyncio.run(exercise())

    @staticmethod
    def wait_for_cancellation(cancelled):
        end=time.monotonic()+3
        while time.monotonic()<end:
            try: check()
            except WindowError:
                cancelled.set();return
            time.sleep(.005)

    def test_owned_http_timeout_and_capacity_map_to_503(self):
        entered, release = threading.Event(), threading.Event()
        self.backend._remote_models.timeout = .1
        def stalled(_request):
            entered.set(); release.wait(3)
            return {'projectId':'oer-test'}
        self.backend.index = stalled
        payload={'projectId':'oer-test','chunks':self.corpus}
        try:
            response=self.client.post('/index',json=payload)
            self.assertTrue(entered.is_set())
            self.assertEqual(503,response.status_code)
            detail=response.json()['detail']
            self.assertEqual('SOURCE_REQUEST_TIMEOUT',detail['kind'])
            self.assertEqual('unknown',detail['metadata']['outcome'])
            with _owned_workers_lock:
                worker=_owned_workers[detail['metadata']['requestId']]
            self.assertTrue(worker.is_alive())
            refused=self.client.post('/index',json=payload)
            self.assertEqual(503,refused.status_code)
            self.assertEqual('SOURCE_REQUEST_CAPACITY',refused.json()['detail']['kind'])
        finally:
            release.set()
            if 'worker' in locals():
                worker.join(3)
                self.assertFalse(worker.is_alive())

    def test_disconnected_index_does_not_publish_metadata_or_upsert_late_vectors(self):
        entered=threading.Event();cancelled=threading.Event()
        def slow_encoder(texts):
            self.encodes.append(texts);entered.set();self.wait_for_cancellation(cancelled)
            return np.asarray([[1.,0.] for _ in texts],dtype=np.float32)
        self.backend.encode_embeddings=slow_encoder
        messages=self.disconnect_at(entered)
        self.assertTrue(entered.is_set());self.assertTrue(cancelled.is_set())
        self.assertEqual(503,next(x['status'] for x in messages if x['type']=='http.response.start'))
        self.assertFalse(self.backend.metadata_path('oer-test').exists())
        self.assertTrue(self.db.collections)
        self.assertTrue(all(not values for values in self.db.collections.values()))

    def test_disconnect_after_partial_upsert_never_publishes_index_metadata(self):
        entered=threading.Event();cancelled=threading.Event();original=self.db.upsert
        def partial(*args,**kwargs):
            result=original(*args,**kwargs);entered.set();self.wait_for_cancellation(cancelled);return result
        self.db.upsert=partial
        messages=self.disconnect_at(entered)
        self.assertTrue(entered.is_set());self.assertTrue(cancelled.is_set())
        self.assertEqual(503,next(x['status'] for x in messages if x['type']=='http.response.start'))
        self.assertFalse(self.backend.metadata_path('oer-test').exists())
        self.assertTrue(any(values for values in self.db.collections.values()))


class UnifiedRemoteCancellationHttpTest(RemoteCancellationHttpTest):
    def setUp(self):
        super().setUp()
        self.client.close();self.app=create_unified_app(self.policy,backend=self.backend)
        self.rt=self.backend._windows_runtime
        self.rt.tokenizer=lambda kind:CharacterTokenizer()
        self.rt.identity=lambda kind:{'kind':kind,'tokenizerSha256':'authored-contract'}
        self.client=TestClient(self.app)


if __name__ == '__main__': unittest.main()
