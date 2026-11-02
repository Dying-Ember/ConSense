"""Isolated HTTP/1.0 OCR upstream cancellation diagnostic; no GPU or containers."""
import base64
import hashlib
import http.client
import http.server
import socket
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from fixed_gpu_worker import Worker
from request_stage import encoded, envelope


class WorkerCancellationTest(unittest.TestCase):
    def test_incomplete_chunked_terminator_is_never_marked_complete(self):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        port = listener.getsockname()[1]
        def serve():
            peer, _ = listener.accept()
            try:
                request = peer.makefile('rb')
                request.readline()
                length = 0
                while True:
                    line = request.readline()
                    if line == b'\r\n': break
                    if line.lower().startswith(b'content-length:'): length = int(line.split(b':', 1)[1])
                request.read(length)
                peer.sendall(b'HTTP/1.0 503 Test\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\n')
                request.close()
            finally:
                peer.close()
                listener.close()
        server = threading.Thread(target=serve, daemon=True)
        server.start()
        try:
            with tempfile.TemporaryDirectory() as root:
                sample = b'authored-image'
                binding = {'parser':'paddleocr-vl', 'inputSha256':hashlib.sha256(sample).hexdigest(),
                           'inputByteCount':len(sample), 'originalSourceSha256':'a'*64,
                           'physicalPageNo':1, 'profileSha256':'b'*64}
                worker = Worker({'workspace':root, 'stages':{'paddle':{
                    'readyUrl':f'http://127.0.0.1:{port}/health',
                    'timeoutSeconds':3, 'providerOptions':{}}}}, secret='example-secret')
                op = envelope('paddle', b'{}', binding, {'model':'fixture'})
                directory = Path(root) / 'gpu-operations' / op['operationId']
                directory.mkdir()
                (directory/'request.json').write_bytes(encoded(op))
                (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'], 'stage':'paddle',
                    'status':'ready', 'bindingSha256':op['bindingSha256'], 'deadlineUnixMs':int((time.time()+2)*1000)}))
                worker.active[op['operationId']] = (threading.Event(), threading.Event())
                worker.trees[op['operationId']] = object()
                worker.sessions[op['operationId']] = {'lock':threading.Lock()}
                with patch.object(worker, '_listener_owned', return_value=True), \
                     patch.object(worker, '_connection_owned', return_value=True):
                    try:
                        worker.provider_request(op['operationId'], 'POST', '/layout-parsing',
                            encoded({'file':base64.b64encode(sample).decode()}), 'application/json', 'a'*32)
                    except (OSError, ValueError, http.client.HTTPException):
                        pass
                self.assertTrue(list(directory.glob('provider-*.json')))
                receipt = json.loads(next(p for p in directory.glob('provider-*.json') if p.stat().st_size).read_bytes())
                self.assertFalse(receipt['complete'])
        finally: server.join(3)

    def test_header_prefetched_body_is_retained_on_cancel(self):
        payload = b'prefetched-upstream-\x00\xff'
        release = threading.Event()
        cancelled = threading.Event()

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                self.send_response(200)
                self.send_header('Content-Length', '100')
                self.end_headers()
                self.wfile.write(payload)
                self.wfile.flush()
                release.wait(3)

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        with tempfile.TemporaryDirectory() as root:
            sample = b'authored-image'
            binding = {'parser':'paddleocr-vl', 'inputSha256':hashlib.sha256(sample).hexdigest(),
                       'inputByteCount':len(sample), 'originalSourceSha256':'a'*64,
                       'physicalPageNo':1, 'profileSha256':'b'*64}
            worker = Worker({'workspace':root, 'stages':{'paddle':{
                'readyUrl':f'http://127.0.0.1:{server.server_port}/health',
                'timeoutSeconds':3, 'providerOptions':{}}}}, secret='example-secret')
            op = envelope('paddle', b'{}', binding, {'model':'fixture'})
            directory = Path(root) / 'gpu-operations' / op['operationId']
            directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op))
            (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'], 'stage':'paddle',
                'status':'ready', 'bindingSha256':op['bindingSha256'], 'deadlineUnixMs':int((time.time()+2)*1000)}))
            worker.active[op['operationId']] = (cancelled, threading.Event())
            worker.trees[op['operationId']] = object()
            worker.sessions[op['operationId']] = {'lock':threading.Lock()}
            class Connection(http.client.HTTPConnection):
                def getresponse(self):
                    response = super().getresponse()
                    self_test.assertEqual(response.fp.peek(1), payload)
                    cancelled.set()
                    return response
            self_test = self
            try:
                with patch.object(worker, '_listener_owned', return_value=True), \
                     patch.object(worker, '_connection_owned', return_value=True), \
                     patch('fixed_gpu_worker.ProviderConnection', Connection):
                    with self.assertRaises(Exception):
                        worker.provider_request(op['operationId'], 'POST', '/layout-parsing',
                            encoded({'file':base64.b64encode(sample).decode()}), 'application/json', 'a'*32)
                captures = list(directory.glob('provider-*.bin'))
                receipts = [p for p in directory.glob('provider-*.json') if p.stat().st_size]
                self.assertEqual(1, len(captures))
                self.assertEqual(1, len(receipts))
                receipt = json.loads(receipts[0].read_bytes())
                self.assertFalse(receipt['complete'])
                self.assertEqual(payload, captures[0].read_bytes())
                self.assertEqual(len(payload), receipt['bytes'])
                self.assertEqual(hashlib.sha256(payload).hexdigest(), receipt['sha256'])
                self.assertFalse(worker.sessions[op['operationId']]['lock'].locked())
            finally:
                release.set()
                server.shutdown()
                server.server_close()
                server_thread.join(3)

    def test_http10_partial_capture_interrupts_promptly_and_retains_prefix(self):
        seen = threading.Event()
        release = threading.Event()
        cancelled = threading.Event()
        payload = b'abc\x00\xff'

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                self.send_response(200)
                self.send_header('Content-Length', '100')
                self.end_headers()
                self.wfile.write(payload)
                self.wfile.flush()
                release.wait(3)

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        with tempfile.TemporaryDirectory() as root:
            sample = b'authored-image'
            binding = {'parser':'paddleocr-vl', 'inputSha256':hashlib.sha256(sample).hexdigest(),
                       'inputByteCount':len(sample), 'originalSourceSha256':'a'*64,
                       'physicalPageNo':1, 'profileSha256':'b'*64}
            config = {'workspace':root, 'stages':{'paddle':{'readyUrl':f'http://127.0.0.1:{server.server_port}/health',
                      'timeoutSeconds':3, 'providerOptions':{}}}}
            worker = Worker(config, secret='example-secret')
            op = envelope('paddle', b'{}', binding, {'model':'fixture'})
            directory = Path(root) / 'gpu-operations' / op['operationId']
            directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op))
            (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'], 'stage':'paddle',
                'status':'ready', 'bindingSha256':op['bindingSha256'], 'deadlineUnixMs':int((time.time()+1.5)*1000)}))
            worker.active[op['operationId']] = (cancelled, threading.Event())
            worker.trees[op['operationId']] = object()
            worker.sessions[op['operationId']] = {'lock':threading.Lock()}
            class Connection(http.client.HTTPConnection):
                def getresponse(self):
                    response = super().getresponse()
                    read = response.read1
                    def read1(size):
                        result = read(size)
                        if result: seen.set()
                        return result
                    response.read1 = read1
                    return response
            trigger = threading.Thread(target=lambda: (seen.wait(2), time.sleep(.1), cancelled.set()), daemon=True)
            trigger.start()
            started = time.monotonic()
            try:
                with patch.object(worker, '_listener_owned', return_value=True), \
                     patch.object(worker, '_connection_owned', return_value=True), \
                     patch('fixed_gpu_worker.ProviderConnection', Connection):
                    with self.assertRaises(Exception):
                        worker.provider_request(op['operationId'], 'POST', '/layout-parsing',
                            encoded({'file':base64.b64encode(sample).decode()}), 'application/json', 'a'*32)
                elapsed = time.monotonic() - started
                self.assertTrue(seen.is_set())
                self.assertTrue(cancelled.is_set())
                receipts = [p for p in directory.glob('provider-*.json') if p.stat().st_size]
                captures = list(directory.glob('provider-*.bin'))
                self.assertEqual(1, len(receipts))
                self.assertEqual(1, len(captures))
                receipt = json.loads(receipts[0].read_bytes())
                self.assertEqual(payload, captures[0].read_bytes())
                self.assertFalse(receipt['complete'])
                self.assertEqual(len(payload), receipt['bytes'])
                self.assertEqual(hashlib.sha256(payload).hexdigest(), receipt['sha256'])
                self.assertFalse(worker.sessions[op['operationId']]['lock'].locked())
                self.assertLess(elapsed, .9, f'cancelled response returned after {elapsed:.3f}s')
            finally:
                release.set()
                server.shutdown()
                server.server_close()
                server_thread.join(3)
                trigger.join(3)

if __name__ == '__main__': unittest.main()
