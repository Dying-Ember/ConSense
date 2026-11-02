"""Barrier-driven complete-body cancellation through the real fixed proxy and CAS."""
import base64
from contextlib import contextmanager
import hashlib
import http.client
import json
import socket
import tempfile
import threading
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from complete_ocr import CompleteOcrError, recognize_complete_page
import test_complete_ocr as fixtures

class CompleteBodyCancelTest(unittest.TestCase):
    def test_complete_body_cancel_keeps_failed_terminal_and_native_capture(self):
        for mode in ('success', 'native_503', 'gateway_503', 'extra', 'extra_large', 'zero', 'zero_extra', 'cap', 'cleanup'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                fixture = fixtures.CompleteOcrTests()
                raw = fixture.image()
                store = ArtifactStore(directory + '/target')
                source = ArtifactStore(directory + '/source')
                array = b'authored native failure bytes\x00\xff'
                reference = source.put_bytes(array, identity_scope='actual_native_array_npy_without_pickle')
                manifest = {'schema':'consense.paddle-native-output-capture.v1', 'captureStatus':'failed_native_capture',
                            'input':{'sha256':hashlib.sha256(raw).hexdigest(),'byteCount':len(raw)},'failureStage':'fixture'}
                detail = {'kind':'native_capture_failed', 'rawOutputs':{'model_output':base64.b64encode(json.dumps(manifest).encode()).decode()},
                          'rawAssetTransport':'consense.paddle-native-cas-assets.v1',
                          'rawAssetRefs':{'arrays/fixture.npy': reference}}
                body = json.dumps({'detail':detail}).encode() if mode in ('native_503', 'gateway_503', 'extra', 'extra_large') else b'' if mode in ('zero', 'zero_extra') else b'abc'
                extra = b'x' * 4096 if mode == 'extra_large' else b'extra-wire-\x00\xff' if mode in ('extra', 'zero_extra') else b''
                status = 503 if mode in ('native_503', 'gateway_503', 'extra', 'extra_large') else 200
                gateway = b'X-Consense-Gateway-Error: 1\r\n' if mode == 'gateway_503' else b''
                head = b'HTTP/1.0 ' + str(status).encode() + b' fixture\r\n' + gateway + b'Content-Length: ' + str(len(body)).encode() + b'\r\n\r\n'
                listener = socket.socket(); listener.bind(('127.0.0.1', 0)); listener.listen(1)
                port = listener.getsockname()[1]
                consumed, cancelled, finish = threading.Event(), threading.Event(), threading.Event()
                def serve():
                    connection, _ = listener.accept()
                    try:
                        connection.recv(65536); connection.sendall(head + body + extra); finish.wait(3)
                    finally: connection.close(); listener.close()
                def trigger():
                    if consumed.wait(3): cancelled.set()
                def connection(_path, timeout):
                    value = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
                    getresponse = value.getresponse
                    def wrapped():
                        response = getresponse(); read1 = response.read1
                        if mode == 'zero_extra':
                            self.assertEqual(response.fp.peek(1), extra)
                        if mode in ('zero', 'zero_extra'):
                            consumed.set()
                            if not cancelled.wait(3): raise AssertionError('trigger did not fire')
                        def blocked_read(size):
                            result = read1(size)
                            if result and response.length == 0:
                                consumed.set()
                                if not cancelled.wait(3): raise AssertionError('trigger did not fire')
                            return result
                        response.read1 = blocked_read
                        if mode == 'cleanup':
                            close = response.close
                            def failing_close():
                                close()
                                raise RuntimeError('authored cleanup error')
                            response.close = failing_close
                        return response
                    value.getresponse = wrapped
                    return value
                @contextmanager
                def lifecycle(*args, **kwargs): yield {'mode':'owned_fixed_gpu','operationId':'a'*32,'operationBindingSha256':'b'*64,'providerEvidenceProtocol':'dispatch-v1'}
                worker = threading.Thread(target=serve, daemon=True); trigger_thread = threading.Thread(target=trigger, daemon=True)
                worker.start(); trigger_thread.start()
                try:
                    config = fixture.config(); config['paddleocr-vl'].update(ownedFixed={}, nativeCaptureTransport={
                        'schema':'consense.paddle-native-cas-assets.v1', 'artifactRoot':str(source.root)})
                    with patch('complete_ocr.owned_native_provider', lifecycle), \
                         patch('fixed_gpu_provider.configuration', return_value=({'stage':'paddle'}, 'fixture')), \
                         patch('fixed_gpu_provider._token', return_value='fixture-token'), \
                         patch('complete_ocr.MAX_RESPONSE_BYTES', 2 if mode == 'cap' else 1024 * 1024 * 1024), \
                         patch('fixed_gpu_provider.UnixConnection', side_effect=connection):
                        with self.assertRaises(CompleteOcrError) as caught:
                            recognize_complete_page(raw, config, store, cancelled=cancelled.is_set)
                    self.assertTrue(consumed.is_set()); self.assertTrue(cancelled.is_set())
                    receipt = json.loads(store.read_asset(caught.exception.receipt)); attempt = receipt['attempts'][0]
                    self.assertEqual(receipt['status'], 'failed'); self.assertEqual(receipt['completedResponses'], [])
                    self.assertEqual(attempt['httpStatus'], status)
                    self.assertEqual(store.read_asset(attempt['response']), body)
                    self.assertEqual(attempt['response']['sha256'], hashlib.sha256(body).hexdigest())
                    self.assertEqual(attempt['responseComplete'], mode not in ('extra', 'extra_large', 'zero_extra', 'cap'))
                    if mode == 'cleanup':
                        self.assertIn('cancelled', receipt['reason'])
                    if mode == 'native_503':
                        capture = attempt['nativeFailureCapture']
                        self.assertEqual(capture['status'], 'failed_capture_bytes_preserved')
                        self.assertFalse(capture['sourceQualityAccepted'])
                        self.assertEqual(json.loads(store.read_asset(capture['manifest'])), manifest)
                        self.assertEqual(store.read_asset(capture['assets']['arrays/fixture.npy']), array)
                    else: self.assertNotIn('nativeFailureCapture', attempt)
                    if mode in ('extra', 'extra_large', 'zero_extra'):
                        self.assertEqual(attempt['response']['identityScope'], 'actual_proxy_http_body_incomplete')
                        self.assertEqual(attempt['proxyExtraWire']['identityScope'], 'actual_proxy_http_extra_wire')
                        self.assertFalse(attempt['proxyExtraWireCaptureTruncated'])
                        self.assertEqual(store.read_asset(attempt['proxyExtraWire']), extra)
                    elif mode == 'cap':
                        self.assertEqual(attempt['response']['identityScope'], 'actual_proxy_http_body_incomplete')
                        self.assertIn('exceeds capture limit', receipt['reason'])
                    elif mode == 'gateway_503':
                        self.assertEqual(attempt['response']['identityScope'], 'actual_gateway_http_body_complete')
                        self.assertTrue(attempt['gatewayError'])
                    else:
                        self.assertEqual(attempt['response']['identityScope'], 'actual_received_http_body_complete')
                finally:
                    finish.set(); worker.join(4); trigger_thread.join(4)
                    self.assertFalse(worker.is_alive()); self.assertFalse(trigger_thread.is_alive())

if __name__ == '__main__': unittest.main()
