"""Public ASGI request ownership checks with a synthetic bounded handler."""
import asyncio
from pathlib import Path
import sys
import threading
import time
import unittest
import anyio

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient
from source_request_context import check, execute_owned
from token_windows import WindowError

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'offline_delivery'))
from remote_gpu_models import RemoteModels


def application(started, stopped, timeout):
    app=FastAPI()
    def work():
        started.set()
        try:
            while True:
                check()
                time.sleep(.005)
        except WindowError:
            stopped.set()
            raise
    @app.post('/bounded')
    async def bounded(request: Request):
        try:
            return await execute_owned(request, work, timeout)
        except WindowError as error:
            return JSONResponse({'kind':error.kind},status_code=503)
    return app


class SourceRequestHttpTest(unittest.TestCase):
    def test_public_request_total_deadline_stops_the_owned_handler(self):
        started,stopped=threading.Event(),threading.Event()
        response=TestClient(application(started,stopped,.1)).post('/bounded')
        self.assertEqual(503,response.status_code)
        self.assertIn(response.json()['kind'], ('SOURCE_REQUEST_CANCELLED', 'SOURCE_REQUEST_TIMEOUT'))
        self.assertTrue(started.is_set());self.assertTrue(stopped.wait(1))

    def test_public_deadline_and_cancellation_reach_remote_model_client(self):
        class Runtime:
            def logical_identity(self,kind): return {'kind':kind}
            def inference_cap(self,kind): return 512
        class Backend:
            MODEL_SETTINGS={'embedding':{'batchSize':2,'maxTokens':512}}
            MODELS={'embedding':{'name':'fixture','revision':'1'}}
            def window_runtime(self): return Runtime()
        class Client:
            seen=None
            def compute(self,stage,binding,body,timeout,cancelled=None):
                self.seen=(stage,binding,body,timeout,cancelled)
                while not cancelled(): time.sleep(.005)
                raise TimeoutError('cancelled fixture')
        client=Client();models=RemoteModels(Backend(),client,dtype='float32',serial_offload=True,timeout=10)
        app=FastAPI()
        @app.post('/bounded')
        async def bounded(request: Request):
            try: return await execute_owned(request,models.embedding_model().get_sentence_embedding_dimension,.15)
            except (TimeoutError, WindowError): return JSONResponse({'cancelled':True},status_code=503)
        response=TestClient(app).post('/bounded')
        self.assertEqual(503,response.status_code);self.assertTrue(response.json()['cancelled'])
        stage,binding,body,timeout,cancelled=client.seen
        self.assertEqual('document-embedding',stage);self.assertEqual({'kind':'dimension'},body)
        self.assertEqual('cuda',binding['runtime']['device']);self.assertGreater(timeout,0);self.assertLess(timeout,.151);self.assertTrue(cancelled())

    def test_real_asgi_disconnect_reaches_the_owned_worker(self):
        started,stopped=threading.Event(),threading.Event();app=application(started,stopped,3)
        async def exercise():
            limiter=anyio.to_thread.current_default_thread_limiter();previous=limiter.total_tokens;limiter.total_tokens=1
            first=True
            async def receive():
                nonlocal first
                if first:
                    first=False
                    return {'type':'http.request','body':b'','more_body':False}
                if started.is_set(): return {'type':'http.disconnect'}
                await asyncio.sleep(10)
                return {'type':'http.disconnect'}
            async def send(_message): pass
            scope={'type':'http','asgi':{'version':'3.0','spec_version':'2.3'},'http_version':'1.1',
                   'method':'POST','scheme':'http','path':'/bounded','raw_path':b'/bounded',
                   'query_string':b'','headers':[], 'client':('127.0.0.1',1),'server':('localhost',80)}
            try: await asyncio.wait_for(app(scope,receive,send),2)
            finally: limiter.total_tokens=previous
        asyncio.run(exercise())
        self.assertTrue(started.is_set());self.assertTrue(stopped.wait(1))

    def test_server_task_cancellation_also_stops_the_owned_worker(self):
        started,stopped=threading.Event(),threading.Event();app=application(started,stopped,3)
        async def exercise():
            async def receive():
                await asyncio.sleep(10)
                return {'type':'http.disconnect'}
            async def send(_message): pass
            scope={'type':'http','asgi':{'version':'3.0','spec_version':'2.3'},'http_version':'1.1',
                   'method':'POST','scheme':'http','path':'/bounded','raw_path':b'/bounded',
                   'query_string':b'','headers':[], 'client':('127.0.0.1',1),'server':('localhost',80)}
            task=asyncio.create_task(app(scope,receive,send))
            self.assertTrue(await asyncio.to_thread(started.wait,1));task.cancel()
            with self.assertRaises(asyncio.CancelledError): await task
            self.assertTrue(await asyncio.to_thread(stopped.wait,1))
        asyncio.run(exercise())


if __name__ == '__main__': unittest.main()
