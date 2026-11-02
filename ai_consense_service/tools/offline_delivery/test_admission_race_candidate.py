import http.client
import http.server
import threading
import time
import unittest
from unittest import mock

from fixed_gpu_client import Client, OperationError, _ResponseReader
from fixed_model_session import ModelSession

OPERATION = 'a'*32
BINDING = {'requestId':'a'*32,'sourceScopeSha256':'c'*64,'modelKind':'embedding',
           'modelIdentity':{'model':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':512,
           'deadlineBudgetMs':2000,'maxBatches':3,'maxTotalBytes':1024}


class AdmissionRaceTest(unittest.TestCase):
    def make_client(self, raw, response_status=202, complete=True):
        cancelled = threading.Event()
        client = Client('http://127.0.0.1:1234', 'fixture',
                        {'business-llm':'e'*64,'document-embedding':'c'*64})
        response = mock.Mock(status=response_status, length=0 if complete else 1)
        response.getheader.return_value = str(len(raw) if complete else len(raw)+1)
        def read(size):
            cancelled.set()
            return raw
        response.read1.side_effect = read
        # getresponse has not yet consumed this mock's body.
        response.isclosed.side_effect = lambda: cancelled.is_set() and complete
        connection = mock.Mock(sock=mock.Mock())
        connection.getresponse.return_value = response
        client._connection = lambda timeout: connection
        client.cancel_and_observe = mock.Mock(return_value={'status':'release_unknown','operationId':OPERATION})
        return client, cancelled

    def test_callers_cancel_known_id_but_do_not_return_success(self):
        raw = b'{"operationId":"'+OPERATION.encode()+b'"}'
        for kind in ('execute', 'business', 'session'):
            for complete in (True, False):
                with self.subTest(kind=kind, complete=complete):
                    client, cancelled = self.make_client(raw, complete=complete)
                    with self.assertRaises(OperationError) as caught:
                        if kind == 'execute': client.compute('document-embedding', {'x':1}, {'x':1}, 2, cancelled.is_set)
                        elif kind == 'business': client.start_business('/v1/responses', b'{}', 2, cancelled.is_set)
                        else: ModelSession.start(client, 'document-embedding', BINDING, time.monotonic()+2,
                                                 cancelled.is_set, lambda action: action())
                    self.assertEqual(caught.exception.operation, OPERATION if complete else None)
                    self.assertEqual(caught.exception.exit_receipt['status'], 'release_unknown')
                    self.assertEqual(client.cancel_and_observe.call_count, int(complete))
                    if complete: self.assertEqual(client.cancel_and_observe.call_args.args[0], OPERATION)

    def test_invalid_admission_never_cancels_guessed_id(self):
        for raw, status in ((b'{"operationId":"bad"}', 202), (b'{', 202),
                            (b'{"operationId":"'+OPERATION.encode()+b'"}', 500)):
            with self.subTest(raw=raw, status=status):
                client, cancelled = self.make_client(raw, status)
                with self.assertRaises(OperationError) as caught:
                    client.start_business('/v1/responses', b'{}', 2, cancelled.is_set)
                self.assertIsNone(caught.exception.operation)
                self.assertEqual('release_unknown', caught.exception.exit_receipt['status'])
                client.cancel_and_observe.assert_not_called()

    def test_ready_admission_bytes_remain_available_after_recv_cancellation(self):
        cancelled = threading.Event()
        original, peer, selector = mock.Mock(), mock.Mock(), mock.Mock()
        original.dup.return_value = peer
        selector.select.return_value = [(mock.Mock(), 1)]
        peer.recv_into.side_effect = lambda buf: (cancelled.set() or 1)
        with mock.patch('fixed_gpu_client.selectors.DefaultSelector', return_value=selector):
            reader = _ResponseReader(original, time.monotonic()+1, cancelled.is_set, True)
            try: self.assertEqual(1, reader.readinto(bytearray(1)))
            finally: reader.close()

    def test_cancellation_after_admission_return_still_cleans_known_business_id(self):
        cancelled = threading.Event()
        client = Client('http://127.0.0.1:1234','fixture',{'business-llm':'e'*64})
        client._call = lambda *args, **kwargs: (cancelled.set() or {'operationId':OPERATION})
        client.cancel_and_observe = mock.Mock(return_value={'status':'release_unknown','operationId':OPERATION})
        with self.assertRaises(OperationError) as caught:
            client.start_business('/v1/responses', b'{}', 2, cancelled.is_set)
        self.assertEqual(OPERATION, caught.exception.operation)
        client.cancel_and_observe.assert_called_once()

    def test_real_http_complete_reply_then_cancel_for_each_caller(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                body = b'{"operationId":"'+OPERATION.encode()+b'"}'
                self.send_response(202); self.send_header('Content-Length',str(len(body)))
                self.end_headers(); self.wfile.write(body); self.wfile.flush()
        server = http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            for kind in ('execute','business','session'):
                with self.subTest(kind=kind):
                    cancelled = threading.Event()
                    client = Client('http://127.0.0.1:%s' % server.server_port,'fixture',
                                    {'business-llm':'e'*64,'document-embedding':'c'*64})
                    def connection(timeout):
                        result = http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=timeout)
                        getresponse = result.getresponse
                        def wrapped_response():
                            response = getresponse(); read = response.read1
                            def wrapped_read(size):
                                chunk = read(size); cancelled.set(); return chunk
                            response.read1 = wrapped_read
                            return response
                        result.getresponse = wrapped_response
                        return result
                    client._connection = connection
                    client.cancel_and_observe = mock.Mock(return_value={'status':'release_unknown','operationId':OPERATION})
                    with self.assertRaises(OperationError) as caught:
                        if kind == 'execute': client.compute('document-embedding', {'x':1}, {'x':1}, 2, cancelled.is_set)
                        elif kind == 'business': client.start_business('/v1/responses', b'{}', 2, cancelled.is_set)
                        else: ModelSession.start(client,'document-embedding',BINDING,time.monotonic()+2,
                                                 cancelled.is_set,lambda action: action())
                    self.assertEqual(OPERATION, caught.exception.operation)
                    client.cancel_and_observe.assert_called_once()
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__': unittest.main()
