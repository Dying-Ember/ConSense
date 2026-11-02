"""Fixed GPU client through its authenticated public HTTP seam."""
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socketserver
import stat
import struct
import tempfile
import threading
import time
import unittest
from unittest import mock

import fixed_gpu_client
from request_stage import encoded


class LocalDeadlineMockTest(unittest.TestCase):
    def setUp(self):
        self.client = fixed_gpu_client.Client('http://127.0.0.1:1234', 'token',
            {'business-llm':'e'*64, 'document-embedding':'c'*64})
        self.now = 0.0
        self.clock = mock.patch.object(fixed_gpu_client.time, 'monotonic', side_effect=lambda: self.now)
        self.clock.start()
        self.addCleanup(self.clock.stop)

    def test_identity_exhaustion_prevents_admission(self):
        def identity(*args):
            self.now = 2.0
            return {'profileSha256':'e'*64}
        with mock.patch.object(self.client, '_identity', side_effect=identity), \
             mock.patch.object(self.client, '_call') as call:
            for invoke in (lambda: self.client.start_business('/v1/responses', b'{}', 1),
                           lambda: self.client._execute('document-embedding', {'x':1}, b'{}', 1)):
                self.now = 0
                with self.assertRaises(fixed_gpu_client.OperationError) as caught: invoke()
                self.assertIsNone(caught.exception.operation)
                self.assertEqual(caught.exception.exit_receipt['status'], 'release_unknown')
            call.assert_not_called()

    def test_business_admission_uses_remaining_budget_and_never_retries(self):
        def identity(*args):
            self.now = 0.4
            return {'profileSha256':'e'*64}
        with mock.patch.object(self.client, '_identity', side_effect=identity), \
             mock.patch.object(self.client, '_call', side_effect=OSError('lost response')) as call:
            with self.assertRaises(fixed_gpu_client.OperationError) as caught:
                self.client.start_business('/v1/responses', b'{}', 1)
            self.assertIsNone(caught.exception.operation)
            self.assertEqual(caught.exception.exit_receipt['status'], 'release_unknown')
            self.assertEqual(call.call_count, 1)
            self.assertAlmostEqual(call.call_args.args[2], 0.6)

    def test_encoding_that_exhausts_budget_does_not_dispatch(self):
        original = fixed_gpu_client.base64.b64encode
        def slow_base64(raw):
            self.now = 2
            return original(raw)
        with mock.patch.object(fixed_gpu_client.base64, 'b64encode', side_effect=slow_base64), \
             mock.patch.object(self.client, '_call') as call:
            with self.assertRaises(fixed_gpu_client.OperationError):
                self.client.start_business('/v1/responses', b'{}', 1)
            call.assert_not_called()
        self.now = 0
        connection = mock.Mock(sock=mock.Mock())
        def slow_encoding(body):
            self.now = 2
            return b'{}'
        with mock.patch.object(self.client, '_connection', return_value=connection), \
             mock.patch.object(fixed_gpu_client, 'encoded', side_effect=slow_encoding):
            with self.assertRaises(TimeoutError): self.client._call('POST', '/fixture', 1, {})
        connection.request.assert_not_called()

    def test_compute_encoding_consumes_original_deadline(self):
        def slow_encoding(body):
            self.now = 2
            return b'{}'
        with mock.patch.object(fixed_gpu_client, 'encoded', side_effect=slow_encoding), \
             mock.patch.object(self.client, '_call') as call:
            with self.assertRaises(fixed_gpu_client.OperationError) as caught:
                self.client.compute('document-embedding', {'x':1}, {'x':1}, 1)
            self.assertIsNone(caught.exception.operation)
            self.assertEqual(caught.exception.exit_receipt['status'], 'release_unknown')
            call.assert_not_called()

    def test_unix_path_checks_consume_connect_budget(self):
        class Parent:
            def __str__(self): return '/private'
            def resolve(self): return '/private'
            def stat(self): return mock.Mock(st_mode=stat.S_IFDIR | 0o700, st_uid=100)
        class PathStub:
            parent = Parent()
        path = PathStub()
        info = mock.Mock(st_mode=stat.S_IFSOCK | 0o600, st_uid=100)
        for elapsed in (2.0, 0.4):
            with self.subTest(elapsed=elapsed):
                self.now = 0
                def lstat(unused):
                    self.now = elapsed
                    return info
                peer = mock.Mock()
                peer.getsockopt.return_value = struct.pack('3i', 1, 100, 100)
                connection = fixed_gpu_client.UnixConnection('/private/gpu.sock', 1)
                with mock.patch.object(fixed_gpu_client, 'Path', return_value=path), \
                     mock.patch.object(fixed_gpu_client.os, 'geteuid', return_value=100, create=True), \
                     mock.patch.object(fixed_gpu_client.os, 'getegid', return_value=100, create=True), \
                     mock.patch.object(fixed_gpu_client.os, 'lstat', side_effect=lstat), \
                     mock.patch.object(fixed_gpu_client.socket, 'socket', return_value=peer) as create_socket, \
                     mock.patch.object(fixed_gpu_client.socket, 'AF_UNIX', 1, create=True), \
                     mock.patch.object(fixed_gpu_client.socket, 'SO_PEERCRED', 17, create=True):
                    if elapsed > 1:
                        with self.assertRaises(TimeoutError): connection.connect()
                        create_socket.assert_not_called()
                    else:
                        connection.connect()
                        self.assertAlmostEqual(peer.settimeout.call_args.args[0], 0.6)
                        peer.connect.assert_called_once_with('/private/gpu.sock')

    def test_http_stages_recompute_and_stop_when_budget_expires(self):
        for expired_stage in ('connect', 'request', 'getresponse', 'read', None):
            with self.subTest(expired_stage=expired_stage):
                self.now = 0
                steps, timeouts = [], []
                sock = mock.Mock()
                sock.settimeout.side_effect = timeouts.append
                response = mock.Mock(status=200, length=0)
                response.getheader.return_value = '2'
                response.isclosed.side_effect = [False, True]
                def advance(stage):
                    steps.append(stage)
                    self.now += 1.1 if stage == expired_stage else 0.2
                response.read1.side_effect = lambda size: (advance('read') or b'{}')
                connection = mock.Mock(sock=sock)
                connection.connect.side_effect = lambda: advance('connect')
                connection.request.side_effect = lambda *args, **kwargs: advance('request')
                connection.getresponse.side_effect = lambda: (advance('getresponse') or response)
                with mock.patch.object(self.client, '_connection', return_value=connection):
                    if expired_stage:
                        with self.assertRaises(TimeoutError): self.client._call('GET', '/fixture', 1)
                        self.assertEqual(steps[-1], expired_stage)
                    else:
                        self.assertEqual(self.client._call('GET', '/fixture', 1), {})
                        self.assertEqual(steps, ['connect', 'request', 'getresponse', 'read'])
                        self.assertEqual(len(timeouts), 2)
                        for actual, expected in zip(timeouts, (0.8, 0.6)):
                            self.assertAlmostEqual(actual, expected)
                connection.close.assert_called_once()

    def test_stream_open_recomputes_timeout_after_each_stage(self):
        state = {'operationId':'a'*32, 'stage':'business-llm', 'bindingSha256':'b'*64}
        stream = fixed_gpu_client.BusinessStream(self.client, 'a'*32, 1, {}, None)
        sock = mock.Mock()
        response = mock.Mock(status=200)
        response.getheader.return_value = 'text/event-stream'
        connection = mock.Mock(sock=sock)
        def advance(): self.now += 0.2
        connection.connect.side_effect = advance
        connection.request.side_effect = lambda *args, **kwargs: advance()
        connection.getresponse.return_value = response
        with mock.patch.object(self.client, '_call', return_value=state), \
             mock.patch.object(self.client, '_connection', return_value=connection):
            self.assertEqual(stream.open()[0], 200)
        self.assertAlmostEqual(sock.settimeout.call_args_list[0].args[0], 0.8)
        self.assertAlmostEqual(sock.settimeout.call_args_list[1].args[0], 0.6)

    def test_stream_open_rejects_late_headers(self):
        state = {'operationId':'a'*32, 'stage':'business-llm', 'bindingSha256':'b'*64}
        stream = fixed_gpu_client.BusinessStream(self.client, 'a'*32, 1, {}, None)
        connection = mock.Mock(sock=mock.Mock())
        def late_response():
            self.now = 2
            return mock.Mock(status=200)
        connection.getresponse.side_effect = late_response
        with mock.patch.object(self.client, '_call', return_value=state), \
             mock.patch.object(self.client, '_connection', return_value=connection), \
             mock.patch.object(stream, 'close') as close:
            with self.assertRaises(TimeoutError): stream.open()
            close.assert_called_once()


def fixture_client(url, token):
    class FixtureClient(fixed_gpu_client.Client):
        expected = None
        def _call(self, method, path, timeout, body=None, cancelled=None):
            if method == 'POST' and path == '/operations' and isinstance(body, dict) and 'stage' in body:
                self.expected = self._identity(body['stage'], body['binding'], base64.b64decode(body['bodyBase64']))
            value = super()._call(method, path, timeout, body, cancelled)
            if method == 'GET' and path.startswith('/operations/') and path.count('/') == 2 and self.expected:
                return {**self.expected, **value}
            return value
    return FixtureClient(url, token, {'document-embedding':'c'*64, 'query-embedding-rerank':'d'*64, 'business-llm':'e'*64})


class FixedGpuClientTest(unittest.TestCase):
    def test_release_boolean_is_not_a_cgroup_empty_count(self):
        self.assertFalse(fixed_gpu_client.gpu_release_proven(
            {'status':'released','cgroupPopulated':False,'observedGpuPids':[123]}))

    def test_same_stage_wrong_body_source_or_profile_is_rejected(self):
        body = {'kind':'dimension'}; binding = {'identity':'fixture'}
        expected = {'bodySha256':hashlib.sha256(encoded(body)).hexdigest(),
                    'sourceSha256':hashlib.sha256(encoded(binding)).hexdigest(), 'profileSha256':'c'*64}
        for wrong in expected:
            with self.subTest(wrong=wrong):
                calls = []
                class Handler(BaseHTTPRequestHandler):
                    def log_message(self, *args): pass
                    def reply(self, value, status=200):
                        raw = encoded(value); self.send_response(status); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
                    def do_POST(self):
                        self.rfile.read(int(self.headers['Content-Length'])); self.reply({'operationId':'a'*32},202)
                    def do_DELETE(self): calls.append('cancel'); self.reply({},202)
                    def do_GET(self):
                        calls.append(self.path)
                        self.reply({**expected, **({} if 'cancel' in calls else {wrong:'0'*64}),
                            'operationId':'a'*32,'stage':'document-embedding','bindingSha256':'b'*64,
                            'status':'cancelled' if 'cancel' in calls else 'completed','release':{'status':'release_unknown'}})
                server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
                thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
                try:
                    client = fixed_gpu_client.Client('http://127.0.0.1:%s' % server.server_port,'test-token',{'document-embedding':'c'*64})
                    with self.assertRaisesRegex(fixed_gpu_client.OperationError,'identity changed'):
                        client.compute('document-embedding',binding,body,2)
                    self.assertFalse(any(path.endswith('/result') for path in calls))
                finally:
                    server.shutdown(); server.server_close(); thread.join()

    def test_result_requires_the_same_operation_binding_and_released_status(self):
        seen = []
        result = {'contract':'consense.gpu-operation-response.v1', 'bindingSha256':'b'*64,
                  'status':200, 'headers':{'content-type':'application/json'},
                  'bodyBase64':base64.b64encode(b'{"vectors":[[0.25,0.75]],"dimension":2}').decode()}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, value, status=200):
                data = json.dumps(value).encode(); self.send_response(status)
                self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                seen.append((self.headers['Authorization'], body))
                self.reply({'operationId':'a'*32, 'status':'starting'}, 202)
            def do_GET(self):
                if self.path.endswith('/result'):
                    self.reply(result)
                else:
                    self.reply({'operationId':'a'*32, 'stage':'document-embedding', 'bindingSha256':'b'*64,
                        'status':'completed', 'responseSha256':hashlib.sha256(encoded(result)).hexdigest(),
                        'release':{'status':'released', 'cgroupPopulated':0, 'observedGpuPids':[123]}})
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            client = fixture_client('http://127.0.0.1:%s' % server.server_port, 'test-token')
            value = client.compute('document-embedding', {'modelIdentity':{'model':'fixture'}}, {'kind':'embedding','texts':['one']}, 2)
            self.assertEqual([[0.25,0.75]], value['vectors']); self.assertEqual(2, value['dimension'])
            self.assertEqual('a'*32, value['_operationReceipt']['operationId'])
            self.assertEqual('Bearer test-token', seen[0][0])
            self.assertEqual(['one'], json.loads(base64.b64decode(seen[0][1]['bodyBase64']))['texts'])
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_unknown_release_is_not_returned_as_success(self):
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, value, status=200):
                data = json.dumps(value).encode(); self.send_response(status)
                self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                self.reply({'operationId':'a'*32}, 202)
            def do_GET(self):
                calls.append(self.path)
                self.reply({'operationId':'a'*32, 'stage':'document-embedding', 'bindingSha256':'b'*64,
                            'status':'release_unknown', 'release':{'status':'release_unknown'}})
            def do_DELETE(self):
                calls.append('cancel'); self.reply({'status':'cancellation_requested'}, 202)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            client = fixture_client('http://127.0.0.1:%s' % server.server_port, 'test-token')
            with self.assertRaisesRegex(fixed_gpu_client.OperationError, 'release is unknown') as failure:
                client.compute('document-embedding', {'identity':'fixture'}, {'kind':'dimension'}, 2)
            self.assertEqual(['/operations/'+'a'*32, 'cancel', '/operations/'+'a'*32], calls)
            self.assertEqual('release_unknown', failure.exception.exit_receipt['status'])
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_nonstream_business_error_preserves_exact_status_and_body_after_release(self):
        result = {'contract':'consense.gpu-operation-response.v1', 'bindingSha256':'b'*64, 'status':429,
                  'headers':{'content-type':'application/json'}, 'bodyBase64':base64.b64encode(b'{"error":"limited"}').decode()}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, value, status=200):
                raw = encoded(value); self.send_response(status); self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length'])); self.reply({'operationId':'a'*32}, 202)
            def do_GET(self):
                if self.path.endswith('/result'): self.reply(result)
                else: self.reply({'operationId':'a'*32, 'stage':'business-llm', 'bindingSha256':'b'*64,
                    'status':'failed', 'responseSha256':hashlib.sha256(encoded(result)).hexdigest(),
                    'release':{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}})
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            client = fixture_client('http://127.0.0.1:%s' % server.server_port, 'test-token')
            status, headers, body, receipt = client.call_business('/v1/chat/completions', b'{"stream":false}', 2)
            self.assertEqual((429, {'content-type':'application/json'}, b'{"error":"limited"}'), (status, headers, body))
            self.assertEqual('a'*32, receipt['operationId'])
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_streamed_upstream_error_waits_for_persisted_result_and_release(self):
        calls = []
        result = {'contract':'consense.gpu-operation-response.v1', 'bindingSha256':'b'*64, 'status':429,
                  'headers':{'content-type':'application/json'}, 'bodyBase64':base64.b64encode(b'{"error":"limited"}').decode()}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, status, value, content_type='application/json'):
                raw = value if isinstance(value,bytes) else encoded(value)
                self.send_response(status); self.send_header('Content-Type',content_type)
                self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length'])); self.reply(202,{'operationId':'a'*32})
            def do_GET(self):
                if self.path.endswith('/stream'): self.reply(429,b'{"error":"limited"}')
                elif self.path.endswith('/result'): calls.append('result'); self.reply(200,result)
                else:
                    calls.append('status')
                    self.reply(200,{'operationId':'a'*32,'stage':'business-llm','bindingSha256':'b'*64,'status':'failed',
                        'responseSha256':hashlib.sha256(encoded(result)).hexdigest(),
                        'release':{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}})
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            session = fixture_client('http://127.0.0.1:%s' % server.server_port,'test-token').start_business(
                '/v1/chat/completions',b'{"stream":true}',2)
            self.assertEqual(429,session.open()[0]); stream = session.read()
            self.assertEqual(b'{"error":"limited"}',next(stream))
            self.assertEqual(['status','status','result'],calls)
            with self.assertRaises(StopIteration): next(stream)
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_settling_waits_for_release_before_accepting_result(self):
        states = []
        result = {'contract':'consense.gpu-operation-response.v1', 'bindingSha256':'b'*64, 'status':200,
                  'headers':{'content-type':'application/json'}, 'bodyBase64':base64.b64encode(b'{"dimension":2}').decode()}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, value, status=200):
                raw = encoded(value); self.send_response(status); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length'])); self.reply({'operationId':'a'*32},202)
            def do_GET(self):
                if self.path.endswith('/result'): self.reply(result); return
                states.append('polled')
                self.reply({'operationId':'a'*32,'stage':'document-embedding','bindingSha256':'b'*64,
                    'status':'settling' if len(states) == 1 else 'completed',
                    'responseSha256':hashlib.sha256(encoded(result)).hexdigest(),
                    'release':{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}})
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            value = fixture_client('http://127.0.0.1:%s' % server.server_port, 'test-token').compute(
                'document-embedding', {'identity':'fixture'}, {'kind':'dimension'}, 2)
            self.assertEqual(2,value['dimension']); self.assertEqual(2,len(states))
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_lost_admission_response_is_unknown_and_not_retried(self):
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                calls.append('admission'); self.rfile.read(int(self.headers['Content-Length']))
                self.connection.close()
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            client = fixture_client('http://127.0.0.1:%s' % server.server_port,'test-token')
            with self.assertRaises(fixed_gpu_client.OperationError) as failure:
                client.compute('document-embedding',{'identity':'fixture'},{'kind':'dimension'},2)
            self.assertEqual(['admission'],calls)
            self.assertIsNone(failure.exception.operation)
            self.assertEqual('release_unknown',failure.exception.exit_receipt['status'])
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_cancellation_interrupts_incomplete_close_delimited_http_body(self):
        cancelled = threading.Event(); first_byte = threading.Event()
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                self.send_response(200); self.send_header('Content-Length','20'); self.end_headers()
                self.wfile.write(b'{'); self.wfile.flush(); first_byte.set(); time.sleep(1.5)
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        timer = threading.Thread(target=lambda: (first_byte.wait(),cancelled.set()),daemon=True); timer.start()
        try:
            client = fixture_client('http://127.0.0.1:%s' % server.server_port,'test-token')
            started = time.monotonic()
            with self.assertRaises((OSError, ValueError)):
                client._call('GET','/fixture',1.2,cancelled=cancelled.is_set)
            self.assertLess(time.monotonic()-started,1.0)
        finally:
            server.shutdown(); server.server_close(); thread.join(); timer.join()

    def test_business_stream_withholds_terminal_until_release_readback(self):
        calls = []
        wire = b'data: {"choices":[{"delta":{"content":"one"},"finish_reason":null}]}\n\ndata: [DONE]\n\n'
        result = {'contract':'consense.gpu-operation-response.v1', 'bindingSha256':'b'*64, 'status':200,
                  'headers':{'content-type':'text/event-stream'}, 'bodyBase64':base64.b64encode(wire).decode()}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                calls.append(body['binding']['httpPath'])
                raw = b'{"operationId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
                self.send_response(202); self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
            def do_GET(self):
                if self.path.endswith('/stream'):
                    raw = wire
                    self.send_response(200); self.send_header('Content-Type','text/event-stream')
                elif self.path.endswith('/result'):
                    calls.append('result-readback'); raw = encoded(result)
                    self.send_response(200); self.send_header('Content-Type','application/json')
                else:
                    calls.append('release-readback' if calls.count('status') else 'status')
                    raw = json.dumps({'operationId':'a'*32, 'stage':'business-llm', 'status':'completed',
                                      'bindingSha256':'b'*64, 'responseSha256':hashlib.sha256(encoded(result)).hexdigest(),
                                      'release':{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}}).encode()
                    self.send_response(200); self.send_header('Content-Type','application/json')
                self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            client = fixture_client('http://127.0.0.1:%s' % server.server_port, 'test-token')
            session = client.start_business('/v1/chat/completions', b'{"stream":true}', 3)
            self.assertEqual(200, session.open()[0])
            stream = session.read()
            self.assertIn(b'one', next(stream)); self.assertEqual(['/v1/chat/completions','status'], calls)
            self.assertIn(b'[DONE]', next(stream)); self.assertEqual(['release-readback','result-readback'], calls[-2:])
            with self.assertRaises(StopIteration): next(stream)
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_closing_stream_cancels_owned_operation_and_records_readback(self):
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, status, body):
                self.send_response(status); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length'])); self.reply(202, b'{"operationId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}')
            def do_DELETE(self):
                calls.append('cancel'); self.reply(202, b'{}')
            def do_GET(self):
                if self.path.endswith('/stream'):
                    self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
                    self.wfile.write(b'data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}\n\n'); self.wfile.flush()
                    time.sleep(.3)
                else:
                    calls.append('readback' if 'cancel' in calls else 'status')
                    self.reply(200, json.dumps({'operationId':'a'*32, 'stage':'business-llm', 'bindingSha256':'b'*64, 'status':'cancelled',
                                               'release':{'status':'released'}}).encode())
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            client = fixture_client('http://127.0.0.1:%s' % server.server_port, 'test-token')
            session = client.start_business('/v1/chat/completions', b'{"stream":true}', 3); session.open()
            stream = session.read(); self.assertIn(b'partial', next(stream)); stream.close()
            self.assertEqual(['status', 'cancel', 'readback'], calls)
            self.assertEqual('cancelled', session.exit_receipt['operationStatus'])
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_stream_does_not_release_terminal_without_gpu_proof(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, status, body, content_type='application/json'):
                self.send_response(status); self.send_header('Content-Type',content_type)
                self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length'])); self.reply(202,b'{"operationId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}')
            def do_DELETE(self): self.reply(202,b'{}')
            def do_GET(self):
                if self.path.endswith('/stream'):
                    self.reply(200,b'data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}\n\ndata: [DONE]\n\n','text/event-stream')
                else:
                    self.reply(200,encoded({'operationId':'a'*32,'stage':'business-llm','bindingSha256':'b'*64,
                                            'status':'completed','release':{'status':'released'}}))
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            client = fixture_client('http://127.0.0.1:%s' % server.server_port,'test-token')
            session = client.start_business('/v1/chat/completions',b'{"stream":true}',2); session.open()
            stream = session.read(); self.assertIn(b'partial',next(stream))
            with self.assertRaisesRegex(ValueError,'release is unknown'): next(stream)
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_business_cancellation_callback_interrupts_stream_and_reads_terminal(self):
        calls, cancelled = [], threading.Event()
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, status, body):
                self.send_response(status); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length'])); self.reply(202,b'{"operationId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}')
            def do_DELETE(self): calls.append('cancel'); self.reply(202,b'{}')
            def do_GET(self):
                if self.path.endswith('/stream'):
                    self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
                    self.wfile.write(b'data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}\n\n'); self.wfile.flush()
                    time.sleep(.5)
                else:
                    self.reply(200,encoded({'operationId':'a'*32,'stage':'business-llm','bindingSha256':'b'*64,
                        'status':'cancelled' if calls else 'running','release':{'status':'release_unknown'}}))
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            client = fixture_client('http://127.0.0.1:%s' % server.server_port,'test-token')
            session = client.start_business('/v1/chat/completions',b'{"stream":true}',2,cancelled.is_set); session.open()
            stream = session.read(); self.assertIn(b'partial',next(stream)); cancelled.set()
            with self.assertRaisesRegex(ValueError,'cancelled'): next(stream)
            self.assertEqual(['cancel'],calls); self.assertEqual('cancelled',session.exit_receipt['operationStatus'])
        finally:
            server.shutdown(); server.server_close(); thread.join()

    @unittest.skipUnless(os.name == 'posix', 'Unix socket ownership requires POSIX')
    def test_private_unix_socket_uses_same_authenticated_operation(self):
        seen = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                seen.append(self.headers['Authorization'])
                self.rfile.read(int(self.headers['Content-Length']))
                raw = b'{"operationId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
                self.send_response(202); self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
        with tempfile.TemporaryDirectory() as root:
            socket_path = str(Path(root)/'gateway.sock')
            server = socketserver.UnixStreamServer(socket_path, Handler); os.chmod(socket_path, 0o600)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                client = fixture_client('unix://'+socket_path, 'test-token')
                reply = client._call('POST', '/operations', 2, {'fixture':True})
                self.assertEqual('a'*32, reply['operationId']); self.assertEqual(['Bearer test-token'], seen)
                os.chmod(socket_path, 0o666)
                with self.assertRaisesRegex(ValueError, 'private owned GPU socket'):
                    client._call('POST', '/operations', 2, {'fixture':True})
            finally:
                server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__': unittest.main()
