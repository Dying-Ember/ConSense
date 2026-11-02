import http.client, threading, time, unittest, tempfile, json, hashlib
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
import complete_ocr
from complete_ocr import HttpCaptureFailure

class EarlyResponseTest(unittest.TestCase):
    def run_case(self, status, body, *, gateway=False, declared=None, cancel=False, stall=False):
        ready=threading.Event(); cancelled=threading.Event(); release=threading.Event(); value=body
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                if stall:
                    ready.set(); release.wait(2)
                self.send_response(status)
                if gateway: self.send_header('X-Consense-Gateway-Error','1')
                self.send_header('Content-Length',str(len(value) if declared is None else declared))
                try: self.end_headers(); self.wfile.write(value); self.wfile.flush()
                except OSError: pass
                if cancel: cancelled.set()
                ready.set()
        class Connection(http.client.HTTPConnection):
            def __init__(self,path,timeout): super().__init__('127.0.0.1',server.server_port,timeout=timeout); self.sent_header=False
            def send(self,data):
                if self.sent_header:
                    if not ready.wait(2): raise AssertionError('server did not reply')
                    raise BrokenPipeError(32,'fixture body send failed')
                self.sent_header=True
                return super().send(data)
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            with patch('fixed_gpu_provider.UnixConnection',Connection):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    complete_ocr._request('http://localhost/operations/'+('a'*32)+'/provider/layout-parsing',body=b'request body',timeout=.2 if stall else 2,socket_path='fixture',token='wrong-token',dispatch_id='b'*32,cancelled=cancelled.is_set if cancel else None)
            return caught.exception
        finally:
            release.set(); server.shutdown(); server.server_close(); thread.join()
    def test_gateway_401_survives_body_send_failure(self):
        e=self.run_case(401,b'{"error":"unauthorized"}',gateway=True)
        self.assertEqual((e.status,e.gateway,e.complete,e.raw),(401,True,True,b'{"error":"unauthorized"}'))
        self.assertIsInstance(e.__cause__,BrokenPipeError)
    def test_native_503_preserves_origin_and_bytes(self):
        e=self.run_case(503,b'{"native":503}',gateway=False)
        self.assertEqual((e.status,e.gateway,e.complete,e.raw),(503,False,True,b'{"native":503}'))
        self.assertIsInstance(e.__cause__,BrokenPipeError)
    def test_gateway_503_preserves_origin_and_bytes(self):
        e=self.run_case(503,b'{"gateway":503}',gateway=True)
        self.assertEqual((e.status,e.gateway,e.complete,e.raw),(503,True,True,b'{"gateway":503}'))
        self.assertIsInstance(e.__cause__,BrokenPipeError)
    def test_2xx_never_succeeds_after_body_send_failure(self):
        e=self.run_case(200,b'OK')
        self.assertEqual((e.status,e.gateway,e.complete,e.raw),(200,False,True,b'OK'))
        self.assertIsInstance(e.__cause__,BrokenPipeError)
    def test_truncated_error_retains_partial_and_send_cause(self):
        e=self.run_case(401,b'ab',gateway=True,declared=5)
        self.assertEqual((e.status,e.gateway,e.complete,e.raw),(401,True,False,b'ab'))
        self.assertIsInstance(e.__cause__,BrokenPipeError)
    def test_response_wait_after_send_failure_obeys_deadline(self):
        started=time.monotonic(); e=self.run_case(401,b'late',gateway=True,stall=True)
        self.assertLess(time.monotonic()-started,1.5)
        self.assertIsNone(e.status); self.assertFalse(e.complete)
        self.assertIsInstance(e.__cause__,BrokenPipeError)
        self.assertTrue(isinstance(e.__context__,(TimeoutError,OSError)))
    def test_real_gateway_401_after_failed_send(self):
        from fixed_gpu_worker import Worker, make_server
        ready=threading.Event()
        with tempfile.TemporaryDirectory() as directory:
            server=make_server(Worker({'workspace':directory,'stages':{}},secret='expected-token'),('127.0.0.1',0))
            handler=server.RequestHandlerClass; original=handler.reply
            def reply(instance,status,value):
                result=original(instance,status,value)
                if status==401: ready.set()
                return result
            handler.reply=reply
            class Connection(http.client.HTTPConnection):
                def __init__(self,path,timeout): super().__init__('127.0.0.1',server.server_port,timeout=timeout); self.sent_header=False
                def send(self,data):
                    if self.sent_header:
                        if not ready.wait(2): raise AssertionError('gateway did not reply')
                        raise BrokenPipeError(32,'fixture body send failed')
                    self.sent_header=True
                    return super().send(data)
            thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            try:
                with patch('fixed_gpu_provider.UnixConnection',Connection):
                    with self.assertRaises(HttpCaptureFailure) as caught:
                        complete_ocr._request('http://localhost/operations/'+('a'*32)+'/provider/layout-parsing',body=b'request',timeout=2,socket_path='fixture',token='wrong-token',dispatch_id='b'*32)
                e=caught.exception
                self.assertEqual((e.status,e.gateway,e.complete,e.raw),(401,True,True,b'{"error":"unauthorized"}'))
                self.assertIsInstance(e.__cause__,BrokenPipeError)
            finally:
                server.shutdown(); server.server_close(); thread.join()
    def test_source_receipt_keeps_request_uncertain_and_response_distinct(self):
        from artifact_store import ArtifactStore
        import test_complete_ocr as fixtures
        fixture=fixtures.CompleteOcrTests()
        @contextmanager
        def lifecycle(*args,**kwargs):
            yield {'mode':'owned_fixed_gpu','operationId':'a'*32,'operationBindingSha256':'b'*64,'providerEvidenceProtocol':'dispatch-v1'}
        for status,gateway in ((401,True),(200,False)):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                ready=threading.Event(); raw=b'{"response":"fixture"}'
                class Handler(BaseHTTPRequestHandler):
                    def log_message(self,*args): pass
                    def do_POST(self):
                        self.send_response(status)
                        if gateway: self.send_header('X-Consense-Gateway-Error','1')
                        self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw); self.wfile.flush(); ready.set()
                server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
                class Connection(http.client.HTTPConnection):
                    def __init__(self,path,timeout): super().__init__('127.0.0.1',server.server_port,timeout=timeout); self.sent_header=False
                    def send(self,data):
                        if self.sent_header:
                            if not ready.wait(2): raise AssertionError('fixture did not reply')
                            raise BrokenPipeError(32,'fixture body send failed')
                        self.sent_header=True
                        return super().send(data)
                thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
                try:
                    store=ArtifactStore(directory); config=fixture.config(); config['paddleocr-vl']['ownedFixed']={}
                    with patch('complete_ocr.owned_native_provider',lifecycle), patch('fixed_gpu_provider.configuration',return_value=({'stage':'paddle'},'fixture')), patch('fixed_gpu_provider._token',return_value='fixture-token'), patch('fixed_gpu_provider.UnixConnection',Connection):
                        with self.assertRaises(complete_ocr.CompleteOcrError) as caught:
                            complete_ocr.recognize_complete_page(fixture.image(),config,store)
                    receipt=json.loads(store.read_asset(caught.exception.receipt)); attempt=receipt['attempts'][0]
                    self.assertEqual(receipt['completedResponses'],[])
                    self.assertEqual((attempt['status'],attempt['httpStatus'],attempt['responseComplete']),('failed',status,True))
                    self.assertEqual((attempt['requestSendErrorType'],attempt['requestSendOutcome']),('BrokenPipeError','unknown'))
                    self.assertEqual(store.read_asset(attempt['response']),raw)
                    self.assertEqual(attempt['response']['sha256'],hashlib.sha256(raw).hexdigest())
                    self.assertNotEqual(attempt['request']['sha256'],attempt['response']['sha256'])
                    self.assertEqual(attempt.get('gatewayError',False),gateway)
                    self.assertIsInstance(caught.exception.__cause__.request_send_error,BrokenPipeError)
                finally: server.shutdown(); server.server_close(); thread.join()
    def test_cleanup_failure_keeps_send_exception(self):
        from fixed_gpu_client import _ResponseReader
        original=_ResponseReader.close
        def failed(reader):
            original(reader)
            raise OSError('fixture cleanup')
        with patch.object(_ResponseReader,'close',failed):
            e=self.run_case(401,b'failure',gateway=True)
        self.assertEqual((e.status,e.gateway,e.complete,e.raw),(401,True,True,b'failure'))
        self.assertIsInstance(e.__cause__,BrokenPipeError)
        self.assertIsInstance(e.request_send_error,BrokenPipeError)
        self.assertTrue(any('cleanup failed: OSError' in note for note in e.__notes__))
    def test_incomplete_header_keeps_wire_and_send_exception(self):
        ready=threading.Event(); fragment=b'HTTP/1.0 401'
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self): self.connection.sendall(fragment); ready.set()
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        class Connection(http.client.HTTPConnection):
            def __init__(self,path,timeout): super().__init__('127.0.0.1',server.server_port,timeout=timeout); self.sent_header=False
            def send(self,data):
                if self.sent_header:
                    if not ready.wait(2): raise AssertionError('no fixture wire')
                    raise BrokenPipeError(32,'fixture body send failed')
                self.sent_header=True
                return super().send(data)
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            with patch('fixed_gpu_provider.UnixConnection',Connection):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    complete_ocr._request('http://localhost/provider',body=b'x',timeout=2,socket_path='fixture')
            e=caught.exception
            self.assertEqual(e.status,401); self.assertFalse(e.gateway); self.assertFalse(e.complete); self.assertEqual(e.raw,b''); self.assertEqual(e.wire,fragment)
            self.assertIsInstance(e.__cause__,BrokenPipeError)
        finally: server.shutdown(); server.server_close(); thread.join()
    def test_nonfixed_broken_pipe_does_not_read_response(self):
        connection=unittest.mock.Mock(); connection.request.side_effect=BrokenPipeError(32,'fixture')
        with patch('complete_ocr.http.client.HTTPConnection',return_value=connection):
            with self.assertRaises(HttpCaptureFailure) as caught: complete_ocr._request('http://localhost/',body=b'x',timeout=1)
        connection.getresponse.assert_not_called()
        self.assertIsInstance(caught.exception.__cause__,BrokenPipeError)
        self.assertFalse(hasattr(caught.exception,'request_send_error'))
    def test_other_send_exception_does_not_read_response(self):
        connection=unittest.mock.Mock(); connection.request.side_effect=ConnectionResetError(104,'fixture')
        with patch('fixed_gpu_provider.UnixConnection',return_value=connection):
            with self.assertRaises(HttpCaptureFailure) as caught: complete_ocr._request('http://localhost/',body=b'x',timeout=1,socket_path='fixture')
        connection.getresponse.assert_not_called()
        self.assertIsInstance(caught.exception.__cause__,ConnectionResetError)
    def test_cancel_never_reports_success_and_preserves_send_cause(self):
        e=self.run_case(200,b'OK',cancel=True)
        self.assertFalse(e.complete)
        self.assertIsInstance(e.__cause__,BrokenPipeError)

if __name__=='__main__': unittest.main(verbosity=2)