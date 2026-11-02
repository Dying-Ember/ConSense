"""Real socket cancellation barriers; no GPU or containers."""
import base64
import hashlib
import http.client
import http.server
import json
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from fixed_gpu_worker import Worker
from request_stage import encoded, envelope


class DispatchCancelRaceTest(unittest.TestCase):
    def run_case(self, cancel_at):
        listener = socket.socket()
        listener.settimeout(4)
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        port = listener.getsockname()[1]
        reached = threading.Event()
        resume = threading.Event()
        received = []
        errors = []

        def serve():
            try:
                peer, _ = listener.accept()
                peer.settimeout(4)
                with peer:
                    received.append(peer.recv(4096))
            except Exception as error:
                errors.append(error)
            finally:
                listener.close()

        server = threading.Thread(target=serve, daemon=True)
        server.start()
        with tempfile.TemporaryDirectory() as root:
            sample = b'authored-image'
            binding = {'parser':'paddleocr-vl', 'inputSha256':hashlib.sha256(sample).hexdigest(),
                       'inputByteCount':len(sample), 'originalSourceSha256':'a'*64,
                       'physicalPageNo':1, 'profileSha256':'b'*64}
            worker = Worker({'workspace':root, 'stages':{'paddle':{
                'readyUrl':f'http://127.0.0.1:{port}/health', 'timeoutSeconds':5,
                'providerOptions':{}}}}, secret='example-secret')
            op = envelope('paddle', b'{}', binding, {'model':'fixture'})
            directory = Path(root) / 'gpu-operations' / op['operationId']
            directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op))
            (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],
                'stage':'paddle','status':'ready','bindingSha256':op['bindingSha256'],
                'deadlineUnixMs':int((time.time()+4)*1000)}))
            worker.active[op['operationId']] = (threading.Event(), threading.Event())
            worker.trees[op['operationId']] = object()
            worker.sessions[op['operationId']] = {'lock':threading.Lock()}

            class HeldConnection(http.client.HTTPConnection):
                def request(self, *args, **kwargs):
                    if cancel_at == 'after_admission':
                        reached.set()
                        if not resume.wait(3): raise TimeoutError('test barrier timed out')
                    return super().request(*args, **kwargs)

            def held_watcher(cancelled, close):
                if cancel_at == 'before_admission':
                    reached.set()
                    if not resume.wait(3): raise TimeoutError('test barrier timed out')
                return lambda: None

            def send():
                try:
                    worker.provider_request(op['operationId'], 'POST', '/layout-parsing',
                        encoded({'file':base64.b64encode(sample).decode()}), 'application/json', 'a'*32)
                    errors.append(AssertionError('cancelled request unexpectedly succeeded'))
                except Exception:
                    pass

            with patch.object(worker, '_listener_owned', return_value=True), \
                 patch.object(worker, '_connection_owned', return_value=True), \
                 patch('fixed_gpu_worker.ProviderConnection', HeldConnection), \
                 patch('native_provider_lifecycle._watch_cancellation', held_watcher):
                thread = threading.Thread(target=send, daemon=True)
                thread.start()
                self.assertTrue(reached.wait(3), 'provider did not reach barrier')
                start = time.monotonic()
                self.assertEqual('cancellation_requested', worker.cancel(op['operationId'])['status'])
                self.assertLess(time.monotonic() - start, .5)
                resume.set()
                thread.join(3)
                self.assertFalse(thread.is_alive(), 'provider remained blocked')
            server.join(4)
            self.assertFalse(server.is_alive())
            self.assertEqual([], errors)
            self.assertEqual([b''], received, 'upstream received bytes after cancellation')
            receipt = json.loads((directory/('provider-'+'a'*32+'.failure.json')).read_bytes())
            self.assertEqual('not_dispatched' if cancel_at == 'before_admission' else 'unknown',
                             receipt['providerOutcome'])
            self.assertNotIn('providerSocket', worker.sessions[op['operationId']])

    def test_cancel_before_dispatch_admission_prevents_send(self):
        self.run_case('before_admission')

    def test_cancel_after_admission_shuts_socket_before_return(self):
        self.run_case('after_admission')

    def test_cancel_interrupts_inflight_request_without_waiting_for_server(self):
        received = threading.Event()
        release = threading.Event()

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                received.set()
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
                'readyUrl':f'http://127.0.0.1:{server.server_port}/health', 'timeoutSeconds':5,
                'providerOptions':{}}}}, secret='example-secret')
            op = envelope('paddle', b'{}', binding, {'model':'fixture'})
            directory = Path(root) / 'gpu-operations' / op['operationId']
            directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op))
            (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],
                'stage':'paddle','status':'ready','bindingSha256':op['bindingSha256'],
                'deadlineUnixMs':int((time.time()+4)*1000)}))
            worker.active[op['operationId']] = (threading.Event(), threading.Event())
            worker.trees[op['operationId']] = object()
            worker.sessions[op['operationId']] = {'lock':threading.Lock()}
            results = []
            def send():
                try:
                    worker.provider_request(op['operationId'], 'POST', '/layout-parsing',
                        encoded({'file':base64.b64encode(sample).decode()}), 'application/json', 'b'*32)
                except Exception as error:
                    results.append(type(error).__name__)
            try:
                with patch.object(worker, '_listener_owned', return_value=True), \
                     patch.object(worker, '_connection_owned', return_value=True):
                    thread = threading.Thread(target=send, daemon=True)
                    thread.start()
                    self.assertTrue(received.wait(3))
                    start = time.monotonic()
                    worker.cancel(op['operationId'])
                    thread.join(1)
                    self.assertFalse(thread.is_alive(), 'cancel did not interrupt in-flight request')
                    self.assertLess(time.monotonic() - start, 1)
                    self.assertTrue(results)
                    self.assertTrue(worker.sessions[op['operationId']]['providerOutcomeUnknown'])
            finally:
                release.set()
                server.shutdown()
                server.server_close()
                server_thread.join(3)


if __name__ == '__main__': unittest.main()
