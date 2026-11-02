"""Real proxy call into OCR failure receipt; the gateway is a local HTTP fixture."""
from contextlib import contextmanager
import hashlib
import http.client
import json
import socket
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from complete_ocr import CompleteOcrError, HttpCaptureFailure, _request, recognize_complete_page
import test_complete_ocr as fixtures


def read_fixture_request(connection):
    """Read one bounded HTTP request, including a fragmented request body."""
    connection.settimeout(3)
    received = bytearray()
    while b'\r\n\r\n' not in received:
        block = connection.recv(8192)
        if not block:
            raise EOFError('fixture client closed during HTTP headers')
        received.extend(block)
        if len(received) > 65536 and b'\r\n\r\n' not in received:
            raise ValueError('fixture HTTP headers exceed limit')
    header, body = bytes(received).split(b'\r\n\r\n', 1)
    if len(header) > 65536:
        raise ValueError('fixture HTTP headers exceed limit')
    lengths = [line.split(b':', 1)[1].strip() for line in header.split(b'\r\n')[1:]
               if line.lower().startswith(b'content-length:')]
    if len(lengths) != 1 or not lengths[0].isdigit():
        raise ValueError('fixture requires one numeric Content-Length')
    length = int(lengths[0])
    if length > 8 * 1024 * 1024:
        raise ValueError('fixture request body exceeds limit')
    body = bytearray(body)
    if len(body) > length:
        raise ValueError('fixture received bytes beyond request body')
    while len(body) < length:
        block = connection.recv(min(8192, length - len(body)))
        if not block:
            raise EOFError('fixture client closed during HTTP body')
        body.extend(block)
    return header, bytes(body)


class ProxyReceiptTest(unittest.TestCase):
    def test_fixture_reads_fragmented_request_larger_than_single_recv(self):
        receiver, sender = socket.socketpair()
        body = bytes(range(256)) * 1025
        header = b'POST /fixture HTTP/1.1\r\nContent-Length: ' + str(len(body)).encode() + b'\r\n\r\n'
        errors = []
        def send():
            try:
                for offset in range(0, len(header + body), 4093):
                    sender.sendall((header + body)[offset:offset + 4093])
            except Exception as error:
                errors.append(error)
            finally:
                sender.close()
        worker = threading.Thread(target=send, daemon=True)
        worker.start()
        try:
            observed_header, observed_body = read_fixture_request(receiver)
            self.assertEqual(observed_header, header[:-4])
            self.assertEqual(observed_body, body)
        finally:
            receiver.close()
            worker.join(3)
            self.assertFalse(worker.is_alive())
            if errors:
                raise errors[0]

    def test_independent_cancel_after_read_before_append_preserves_prefix(self):
        listener = socket.socket(); listener.bind(('127.0.0.1', 0)); listener.listen(1)
        port = listener.getsockname()[1]
        consumed, cancelled, finish = threading.Event(), threading.Event(), threading.Event()
        payload = b'consumed-\x00\xff'

        def serve():
            connection, _ = listener.accept()
            try:
                connection.recv(65536)
                connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 100\r\n\r\n' + payload)
                finish.wait(2)
            finally: connection.close(); listener.close()

        def trigger():
            if consumed.wait(2): cancelled.set()

        def connection(_path, timeout):
            value = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
            getresponse = value.getresponse
            def wrapped():
                response = getresponse(); read1 = response.read1
                def blocked_read(size):
                    result = read1(size)
                    if result:
                        consumed.set()
                        self.assertTrue(cancelled.wait(2), 'independent cancellation not triggered')
                    return result
                response.read1 = blocked_read
                return response
            value.getresponse = wrapped
            return value

        worker = threading.Thread(target=serve, daemon=True)
        cancel = threading.Thread(target=trigger, daemon=True)
        worker.start(); cancel.start()
        try:
            with patch('fixed_gpu_provider.UnixConnection', side_effect=connection):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    _request('http://localhost/provider', timeout=1.5, socket_path='fixture', cancelled=cancelled.is_set)
            self.assertTrue(consumed.is_set()); self.assertTrue(cancelled.is_set())
            self.assertEqual(caught.exception.raw, payload)
            self.assertEqual(caught.exception.status, 200)
            self.assertFalse(caught.exception.complete)
        finally:
            finish.set(); worker.join(3); cancel.join(3)
            self.assertFalse(worker.is_alive()); self.assertFalse(cancel.is_alive())

    def test_partial_proxy_body_keeps_its_exact_identity(self):
        fixture = fixtures.CompleteOcrTests()
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        port = listener.getsockname()[1]
        ready, cancelled, finish = threading.Event(), threading.Event(), threading.Event()
        payload = b'proxy-prefix-\x00\xff'
        requests = []

        def serve():
            connection, _ = listener.accept()
            try:
                request = connection.recv(65536)
                requests.append(request.split(b'\r\n', 1)[0])
                connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 100\r\n\r\n' + payload)
                ready.set()
                finish.wait(2)
            finally:
                connection.close()
                listener.close()

        def trigger():
            if ready.wait(2):
                time.sleep(.1)
                cancelled.set()

        @contextmanager
        def lifecycle(*args, **kwargs):
            yield {'mode': 'owned_fixed_gpu', 'operationId': 'a' * 32, 'operationBindingSha256': 'b' * 64, 'providerEvidenceProtocol': 'dispatch-v1'}

        worker = threading.Thread(target=serve, daemon=True)
        cancel = threading.Thread(target=trigger, daemon=True)
        worker.start(); cancel.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                store = ArtifactStore(directory)
                config = fixture.config()
                config['paddleocr-vl']['ownedFixed'] = {}
                with patch('complete_ocr.owned_native_provider', lifecycle), \
                     patch('fixed_gpu_provider.configuration', return_value=({'stage': 'paddle'}, 'fixture')), \
                     patch('fixed_gpu_provider._token', return_value='fixture-token'), \
                     patch('fixed_gpu_provider.UnixConnection', side_effect=lambda _path, timeout:
                           http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)):
                    started = time.monotonic()
                    with self.assertRaises(CompleteOcrError) as caught:
                        recognize_complete_page(fixture.image(), config, store, cancelled=cancelled.is_set)
                    self.assertTrue(cancelled.is_set())
                    self.assertLess(time.monotonic() - started, .9)
                receipt = json.loads(store.read_asset(caught.exception.receipt))
                attempt = receipt['attempts'][0]
                self.assertEqual(receipt['completedResponses'], [])
                self.assertEqual(attempt['httpStatus'], 200)
                self.assertFalse(attempt['responseComplete'])
                self.assertEqual(attempt['response']['identityScope'], 'actual_proxy_http_body_incomplete')
                self.assertEqual(store.read_asset(attempt['response']), payload)
                self.assertEqual(attempt['response']['sha256'], hashlib.sha256(payload).hexdigest())
                self.assertNotIn('nativeFailureCapture', attempt)
                self.assertEqual(requests, [b'POST /operations/' + b'a' * 32 + b'/provider/layout-parsing HTTP/1.1'])
        finally:
            finish.set()
            worker.join(3); cancel.join(3)
            self.assertFalse(worker.is_alive())
            self.assertFalse(cancel.is_alive())

    def test_ambiguous_gateway_wire_keeps_separate_wire_identity(self):
        fixture = fixtures.CompleteOcrTests()
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0)); listener.listen(1)
        port = listener.getsockname()[1]
        payload = b'gateway-\x00\xff'
        wire = (b'HTTP/1.0 503 Gateway\r\nX-Consense-Gateway-Error: 1\r\n'
                b'X-Ambiguous: yes\r\n\r\n' + payload)
        server_errors = []
        requests = []

        def serve():
            connection, _ = listener.accept()
            try:
                requests.append(read_fixture_request(connection))
                connection.sendall(wire)
            except Exception as error:
                server_errors.append(error)
            finally:
                connection.close(); listener.close()

        def connection(_path, timeout):
            value = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
            getresponse = value.getresponse
            def wrapped():
                response = getresponse()
                # Consume the whole synthetic payload so the wire-prefix assertion
                # does not depend on TCP packet or BufferedReader peek boundaries.
                self.assertEqual(response.fp.read(len(payload)), payload)
                return response
            value.getresponse = wrapped
            return value

        @contextmanager
        def lifecycle(*args, **kwargs):
            yield {'mode': 'owned_fixed_gpu', 'operationId': 'a' * 32, 'operationBindingSha256': 'b' * 64, 'providerEvidenceProtocol': 'dispatch-v1'}

        worker = threading.Thread(target=serve, daemon=True); worker.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                store = ArtifactStore(directory)
                config = fixture.config(); config['paddleocr-vl']['ownedFixed'] = {}
                with patch('complete_ocr.owned_native_provider', lifecycle), \
                     patch('fixed_gpu_provider.configuration', return_value=({'stage': 'paddle'}, 'fixture')), \
                     patch('fixed_gpu_provider._token', return_value='fixture-token'), \
                     patch('fixed_gpu_provider.UnixConnection', side_effect=connection):
                    with self.assertRaises(CompleteOcrError) as caught:
                        recognize_complete_page(fixture.image(), config, store)
                receipt = json.loads(store.read_asset(caught.exception.receipt))
                attempt = receipt['attempts'][0]
                self.assertEqual(receipt['completedResponses'], [])
                self.assertEqual(attempt['httpStatus'], 503)
                self.assertFalse(attempt['responseComplete'])
                self.assertTrue(attempt['gatewayError'])
                self.assertNotIn('response', attempt)
                self.assertNotIn('nativeFailureCapture', attempt)
                self.assertEqual(attempt['proxyWirePrefix']['identityScope'], 'actual_proxy_http_wire_prefix_incomplete')
                self.assertFalse(attempt['proxyWireCaptureTruncated'])
                self.assertEqual(store.read_asset(attempt['proxyWirePrefix']), wire)
                self.assertEqual(attempt['proxyWirePrefix']['sha256'], hashlib.sha256(wire).hexdigest())
                self.assertEqual(len(requests), 1)
                self.assertTrue(requests[0][0].startswith(b'POST /operations/'))
        finally:
            worker.join(3)
            self.assertFalse(worker.is_alive())
            if server_errors:
                raise server_errors[0]


if __name__ == '__main__': unittest.main()
