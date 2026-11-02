"""HTTP cancellation and capture at the OCR provider boundary."""
import socket
import http.client
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch

from complete_ocr import HttpCaptureFailure, _request

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'offline_delivery'))


class FixedGatewayCancellationTest(unittest.TestCase):
    def test_http10_framed_complete_and_failed_response_capture(self):
        for status in (200, 503):
            with self.subTest(status=status):
                listener = socket.socket()
                listener.bind(('127.0.0.1', 0))
                listener.listen(1)
                port = listener.getsockname()[1]
                def serve():
                    connection, _ = listener.accept()
                    try:
                        connection.recv(65536)
                        connection.sendall(('HTTP/1.0 %d Test\r\nContent-Length: 3\r\n\r\n' % status).encode() + b'abc')
                    finally:
                        connection.close()
                        listener.close()
                worker = threading.Thread(target=serve, daemon=True)
                worker.start()
                def connection(_path, timeout):
                    return http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
                try:
                    with patch('fixed_gpu_provider.UnixConnection', side_effect=connection):
                        if status == 200:
                            self.assertEqual(_request('http://localhost/provider', timeout=1,
                                                      socket_path='synthetic-fixed-socket'), b'abc')
                        else:
                            with self.assertRaises(HttpCaptureFailure) as captured:
                                _request('http://localhost/provider', timeout=1, socket_path='synthetic-fixed-socket')
                            self.assertEqual(captured.exception.raw, b'abc')
                            self.assertEqual(captured.exception.status, status)
                            self.assertTrue(captured.exception.complete)
                finally:
                    worker.join(3)

    def test_http10_framed_partial_response_cancels_and_retains_bytes(self):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        ready, cancelled, finish = (threading.Event() for _ in range(3))

        def serve():
            connection, _ = listener.accept()
            try:
                connection.recv(65536)
                connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 100\r\n\r\nabc')
                ready.set()
                finish.wait(3)
            finally:
                connection.close()
                listener.close()

        worker = threading.Thread(target=serve, daemon=True)
        worker.start()
        def cancel():
            if ready.wait(2):
                time.sleep(.1)
                cancelled.set()
        trigger = threading.Thread(target=cancel, daemon=True)
        trigger.start()
        started = time.monotonic()
        port = listener.getsockname()[1]
        def connection(_path, timeout):
            return http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
        try:
            with patch('fixed_gpu_provider.UnixConnection', side_effect=connection):
                with self.assertRaises(HttpCaptureFailure) as captured:
                    _request('http://localhost/provider', timeout=1.5,
                             socket_path='synthetic-fixed-socket', cancelled=cancelled.is_set)
            elapsed = time.monotonic() - started
            self.assertTrue(cancelled.is_set(), str(captured.exception))
            self.assertLess(elapsed, .9)
            self.assertEqual(captured.exception.raw, b'abc')
            self.assertEqual(captured.exception.status, 200)
            self.assertFalse(captured.exception.complete)
        finally:
            finish.set()
            worker.join(3)
            trigger.join(3)


    def test_header_prefetched_body_survives_cancel_before_first_body_read(self):
        listener = socket.socket(); listener.bind(('127.0.0.1', 0)); listener.listen(1)
        port = listener.getsockname()[1]; finished = threading.Event(); cancelled = threading.Event()
        payload = b'prefetched-body-\x00\xff'
        def serve():
            connection, _ = listener.accept()
            try:
                connection.recv(65536)
                connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 100\r\n\r\n' + payload)
                finished.wait(2)
            finally: connection.close(); listener.close()
        worker=threading.Thread(target=serve,daemon=True); worker.start()
        def connection(_path, timeout):
            result = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
            getresponse=result.getresponse
            def wrapped():
                response=getresponse()
                self.assertEqual(response.fp.peek(1), payload)
                cancelled.set()
                return response
            result.getresponse=wrapped
            return result
        try:
            with patch('fixed_gpu_provider.UnixConnection', side_effect=connection):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    _request('http://localhost/provider',timeout=1,socket_path='fixture',cancelled=cancelled.is_set)
            self.assertEqual(caught.exception.raw,payload)
            self.assertEqual(caught.exception.status,200)
            self.assertFalse(caught.exception.complete)
        finally: finished.set(); worker.join(2)

    def test_recv_and_cancel_simultaneously_retains_bytes(self):
        from fixed_gpu_client import _ResponseReader
        listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1)
        port=listener.getsockname()[1]; finished=threading.Event(); cancelled=threading.Event()
        payload=b'recv-race-\x00\xff'
        def serve():
            connection,_=listener.accept()
            try:
                connection.recv(65536)
                connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 100\r\n\r\n' + payload)
                finished.wait(2)
            finally: connection.close(); listener.close()
        worker=threading.Thread(target=serve,daemon=True); worker.start()
        original_init=_ResponseReader.__init__
        class SocketProxy:
            def __init__(self,sock): self.sock=sock
            def recv_into(self,buffer):
                size=self.sock.recv_into(buffer)
                if size: cancelled.set()
                return size
            def __getattr__(self,name): return getattr(self.sock,name)
        def init(reader,*args,**kwargs):
            original_init(reader,*args,**kwargs); reader.sock=SocketProxy(reader.sock)
        def connection(_path, timeout): return http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)
        try:
            with patch('fixed_gpu_provider.UnixConnection',side_effect=connection), patch.object(_ResponseReader,'__init__',init):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    _request('http://localhost/provider',timeout=1,socket_path='fixture',cancelled=cancelled.is_set)
            self.assertEqual(caught.exception.raw,payload)
            self.assertFalse(caught.exception.complete)
        finally: finished.set(); worker.join(2)


    def test_partial_body_deadline_and_truncated_content_length(self):
        for mode in ('deadline','truncated'):
            with self.subTest(mode=mode):
                listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1)
                port=listener.getsockname()[1]; finished=threading.Event()
                def serve():
                    connection,_=listener.accept()
                    try:
                        connection.recv(65536)
                        connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 100\r\n\r\nabc')
                        if mode == 'deadline': finished.wait(2)
                    finally: connection.close(); listener.close()
                worker=threading.Thread(target=serve,daemon=True); worker.start()
                try:
                    with patch('fixed_gpu_provider.UnixConnection',side_effect=lambda _path,timeout: http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)):
                        with self.assertRaises(HttpCaptureFailure) as caught:
                            _request('http://localhost/provider',timeout=.25,socket_path='fixture')
                    self.assertEqual(caught.exception.raw,b'abc')
                    self.assertEqual(caught.exception.status,200)
                    self.assertFalse(caught.exception.complete)
                finally: finished.set(); worker.join(2)

    def test_fixed_gateway_rejects_ambiguous_framing(self):
        for headers in (b'Transfer-Encoding: chunked\r\n', b'Content-Length: 1\r\nContent-Length: 1\r\n', b''):
            with self.subTest(headers=headers):
                listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1); port=listener.getsockname()[1]
                def serve():
                    connection,_=listener.accept()
                    try:
                        connection.recv(65536)
                        connection.sendall(b'HTTP/1.0 200 OK\r\n' + headers + b'\r\na')
                    finally: connection.close(); listener.close()
                worker=threading.Thread(target=serve,daemon=True); worker.start()
                try:
                    with patch('fixed_gpu_provider.UnixConnection',side_effect=lambda _path,timeout: http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)):
                        with self.assertRaises(HttpCaptureFailure) as caught:
                            _request('http://localhost/provider',timeout=1,socket_path='fixture')
                    self.assertFalse(caught.exception.complete)
                    self.assertEqual(caught.exception.raw,b'')
                    self.assertTrue(caught.exception.wire.startswith(b'HTTP/1.0 200 OK\r\n' + headers + b'\r\n'))
                    self.assertTrue((b'HTTP/1.0 200 OK\r\n' + headers + b'\r\na').startswith(caught.exception.wire))
                    self.assertFalse(caught.exception.wire_truncated)
                finally: worker.join(2)


    def test_multiple_recv_blocks_and_large_prefetch_preserve_exact_body_prefix(self):
        from fixed_gpu_client import _ResponseReader
        listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1)
        port=listener.getsockname()[1]; finished=threading.Event(); cancelled=threading.Event()
        payload=(bytes(range(256))*1024)
        received=bytearray()
        def serve():
            connection,_=listener.accept()
            try:
                connection.recv(65536)
                connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 262244\r\n\r\n' + payload)
                finished.wait(2)
            except OSError: pass
            finally: connection.close(); listener.close()
        worker=threading.Thread(target=serve,daemon=True); worker.start()
        original_init=_ResponseReader.__init__
        class SocketProxy:
            def __init__(self,sock): self.sock=sock
            def recv_into(self,buffer):
                size=self.sock.recv_into(buffer)
                received.extend(buffer[:size])
                if len(received) > 100000: cancelled.set()
                return size
            def __getattr__(self,name): return getattr(self.sock,name)
        def init(reader,*args,**kwargs):
            original_init(reader,*args,**kwargs); reader.sock=SocketProxy(reader.sock)
        def connection(_path,timeout): return http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)
        try:
            with patch('fixed_gpu_provider.UnixConnection',side_effect=connection), patch.object(_ResponseReader,'__init__',init):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    _request('http://localhost/provider',timeout=2,socket_path='fixture',cancelled=cancelled.is_set)
            wire=bytes(received); self.assertIn(b'\r\n\r\n',wire)
            consumed=wire.split(b'\r\n\r\n',1)[1]
            self.assertGreater(len(consumed),65536)
            self.assertEqual(caught.exception.raw,consumed)
            self.assertEqual(payload[:len(consumed)],consumed)
            self.assertFalse(caught.exception.complete)
        finally: finished.set(); worker.join(2)


    def test_cancel_in_incomplete_header_retains_wire_and_closes_reader(self):
        from fixed_gpu_client import _ResponseReader
        listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1)
        port=listener.getsockname()[1]; finished=threading.Event(); cancelled=threading.Event()
        wire=b'HTTP/1.0 200 OK\r\nContent-Length: 100\r\nX-Unfinished: '
        readers=[]; original_init=_ResponseReader.__init__
        def init(reader,*args,**kwargs):
            original_init(reader,*args,**kwargs); readers.append(reader)
        def serve():
            connection,_=listener.accept()
            try:
                connection.recv(65536); connection.sendall(wire)
                time.sleep(.1); cancelled.set(); finished.wait(2)
            finally: connection.close(); listener.close()
        worker=threading.Thread(target=serve,daemon=True); worker.start()
        try:
            with patch('fixed_gpu_provider.UnixConnection',side_effect=lambda _path,timeout: http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)), patch.object(_ResponseReader,'__init__',init):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    _request('http://localhost/provider',timeout=1,socket_path='fixture',cancelled=cancelled.is_set)
            self.assertEqual(caught.exception.raw,b'')
            self.assertEqual(caught.exception.wire,wire)
            self.assertIsNone(caught.exception.status)
            self.assertFalse(caught.exception.complete)
            self.assertFalse(caught.exception.wire_truncated)
            self.assertTrue(readers and all(reader.closed and reader.sock.fileno() == -1 for reader in readers))
            self.assertTrue(all(reader.selector.get_map() is None for reader in readers))
        finally: finished.set(); worker.join(2)


    def test_failure_receipt_preserves_fixed_proxy_bytes_in_separate_cas_scopes(self):
        import hashlib, json, tempfile
        from contextlib import contextmanager
        from artifact_store import ArtifactStore
        from complete_ocr import recognize_complete_page, CompleteOcrError
        from test_complete_ocr import CompleteOcrTests
        fixture=CompleteOcrTests()
        for mode in ('body','wire','extra'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1)
                port=listener.getsockname()[1]; finished=threading.Event(); cancelled=threading.Event()
                payload=b'abc' if mode == 'extra' else b'fixture-\x00\xff-proxy'
                header=(b'HTTP/1.0 200 OK\r\nContent-Length: 100\r\n\r\n' if mode == 'body'
                        else b'HTTP/1.0 200 OK\r\nContent-Length: 1\r\n\r\n' if mode == 'extra'
                        else b'HTTP/1.0 503 Gateway\r\nX-Consense-Gateway-Error: 1\r\nX-Ambiguous: yes\r\n\r\n')
                wire=header+payload
                def serve():
                    connection,_=listener.accept()
                    try:
                        connection.recv(65536); connection.sendall(wire)
                        if mode == 'body': time.sleep(.1); cancelled.set()
                        finished.wait(2)
                    except OSError: pass
                    finally: connection.close(); listener.close()
                worker=threading.Thread(target=serve,daemon=True); worker.start()
                def connection(_path,timeout):
                    result=http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)
                    if mode != 'body':
                        getresponse=result.getresponse
                        def wrapped():
                            response=getresponse()
                            self.assertEqual(response.fp.peek(1),payload)
                            return response
                        result.getresponse=wrapped
                    return result
                @contextmanager
                def lifecycle(*args, **kwargs):
                    yield {'mode':'owned_fixed_gpu','operationId':'a'*32,'operationBindingSha256':'b'*64,'providerEvidenceProtocol':'dispatch-v1'}
                def proxy(provider, active_lifecycle, url, *, body=None, timeout, method=None, cancelled=None, dispatch_id=None, content_type=None):
                    return _request('http://localhost/provider',body=body,timeout=timeout,method=method,
                                    socket_path='fixture',token='fixture-token',cancelled=cancelled,dispatch_id=dispatch_id,content_type=content_type)
                config=fixture.config(); config['paddleocr-vl']['ownedFixed']={}
                store=ArtifactStore(directory)
                try:
                    with patch('complete_ocr.owned_native_provider',lifecycle), patch('fixed_gpu_provider.proxy_request',side_effect=proxy), patch('fixed_gpu_provider.UnixConnection',side_effect=connection):
                        with self.assertRaises(CompleteOcrError) as caught:
                            recognize_complete_page(fixture.image(),config,store,cancelled=cancelled.is_set)
                    receipt=json.loads(store.read_asset(caught.exception.receipt)); attempt=receipt['attempts'][0]
                    self.assertEqual(receipt['completedResponses'],[])
                    self.assertFalse(attempt['responseComplete'])
                    self.assertNotIn('nativeFailureCapture',attempt)
                    if mode == 'body':
                        descriptor=attempt['response']; expected=payload
                        self.assertEqual(descriptor['identityScope'],'actual_proxy_http_body_incomplete')
                        self.assertNotIn('proxyWirePrefix',attempt)
                    elif mode == 'extra':
                        descriptor=attempt['proxyExtraWire']; expected=b'bc'
                        self.assertEqual(descriptor['identityScope'],'actual_proxy_http_extra_wire')
                        self.assertIs(attempt['proxyExtraWireCaptureTruncated'],False)
                        self.assertEqual(store.read_asset(attempt['response']),b'a')
                        self.assertEqual(attempt['response']['identityScope'],'actual_proxy_http_body_incomplete')
                        self.assertNotIn('proxyWirePrefix',attempt)
                    else:
                        descriptor=attempt['proxyWirePrefix']; expected=wire
                        self.assertEqual(descriptor['identityScope'],'actual_proxy_http_wire_prefix_incomplete')
                        self.assertIs(attempt['proxyWireCaptureTruncated'],False)
                        self.assertEqual(attempt['httpStatus'],503)
                        self.assertIs(attempt['gatewayError'],True)
                        self.assertNotIn('response',attempt)
                    self.assertEqual(store.read_asset(descriptor),expected)
                    self.assertEqual(descriptor['byteCount'],len(expected))
                    self.assertEqual(descriptor['sha256'],hashlib.sha256(expected).hexdigest())
                finally: finished.set(); worker.join(2)

    def test_fixed_response_rejects_extra_consumed_bytes_after_content_length(self):
        listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1); port=listener.getsockname()[1]
        wire=b'HTTP/1.0 200 OK\r\nContent-Length: 1\r\n\r\nabc'
        def serve():
            connection,_=listener.accept()
            try: connection.recv(65536); connection.sendall(wire)
            finally: connection.close(); listener.close()
        worker=threading.Thread(target=serve,daemon=True); worker.start()
        def connection(_path,timeout):
            result=http.client.HTTPConnection('127.0.0.1',port,timeout=timeout); getresponse=result.getresponse
            def wrapped():
                response=getresponse(); self.assertEqual(response.fp.peek(1),b'abc'); return response
            result.getresponse=wrapped; return result
        try:
            with patch('fixed_gpu_provider.UnixConnection',side_effect=connection):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    _request('http://localhost/provider',timeout=1,socket_path='fixture')
            self.assertFalse(caught.exception.complete)
            self.assertEqual(caught.exception.raw,b'a')
            self.assertEqual(caught.exception.extra_wire,b'bc')
        finally: worker.join(2)


    def test_response_close_error_does_not_replace_capture_or_skip_connection_close(self):
        listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1); port=listener.getsockname()[1]
        def serve():
            connection,_=listener.accept()
            try: connection.recv(65536); connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 100\r\n\r\nabc')
            finally: connection.close(); listener.close()
        worker=threading.Thread(target=serve,daemon=True); worker.start(); closed=[]
        def connection(_path,timeout):
            result=http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)
            getresponse=result.getresponse; close=result.close
            def wrapped():
                response=getresponse()
                def fail_close(): raise RuntimeError('authored close failure')
                response.close=fail_close; return response
            def close_connection(): closed.append(True); return close()
            result.getresponse=wrapped; result.close=close_connection; return result
        try:
            with patch('fixed_gpu_provider.UnixConnection',side_effect=connection):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    _request('http://localhost/provider',timeout=1,socket_path='fixture')
            self.assertEqual(caught.exception.raw,b'abc')
            self.assertFalse(caught.exception.complete)
            self.assertTrue(closed)
        finally: worker.join(2)


    def test_watcher_start_error_cancels_timer_and_closes_connection(self):
        from unittest.mock import Mock
        timer=Mock(); connection=Mock()
        with patch('fixed_gpu_provider.UnixConnection',return_value=connection), \
             patch('complete_ocr.threading.Timer',return_value=timer), \
             patch('complete_ocr.threading.Thread.start',side_effect=RuntimeError('watcher failed')):
            with self.assertRaises(HttpCaptureFailure) as caught:
                _request('http://localhost/provider',timeout=1,socket_path='fixture',cancelled=lambda: False)
        self.assertEqual(caught.exception.raw,b'')
        self.assertFalse(caught.exception.complete)
        timer.cancel.assert_called_once(); timer.join.assert_called_once(); connection.close.assert_called_once()
        connection.connect.assert_not_called()


    def test_cleanup_error_after_complete_body_preserves_complete_response_identity(self):
        listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1); port=listener.getsockname()[1]
        def serve():
            connection,_=listener.accept()
            try: connection.recv(65536); connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc')
            finally: connection.close(); listener.close()
        worker=threading.Thread(target=serve,daemon=True); worker.start(); closed=[]
        def connection(_path,timeout):
            result=http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)
            getresponse=result.getresponse; close=result.close
            def wrapped():
                response=getresponse()
                def fail_close(): raise RuntimeError('authored close failure')
                response.close=fail_close; return response
            def close_connection(): closed.append(True); return close()
            result.getresponse=wrapped; result.close=close_connection; return result
        try:
            with patch('fixed_gpu_provider.UnixConnection',side_effect=connection):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    _request('http://localhost/provider',timeout=1,socket_path='fixture')
            self.assertEqual(caught.exception.raw,b'abc')
            self.assertTrue(caught.exception.complete)
            self.assertEqual(caught.exception.status,200)
            self.assertTrue(closed)
        finally: worker.join(2)


    def test_cleanup_failure_inside_callers_except_does_not_raise_callers_error(self):
        listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1); port=listener.getsockname()[1]
        def serve():
            connection,_=listener.accept()
            try: connection.recv(65536); connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc')
            finally: connection.close(); listener.close()
        worker=threading.Thread(target=serve,daemon=True); worker.start()
        def connection(_path,timeout):
            result=http.client.HTTPConnection('127.0.0.1',port,timeout=timeout); getresponse=result.getresponse
            def wrapped():
                response=getresponse()
                def fail_close(): raise RuntimeError('authored close failure')
                response.close=fail_close; return response
            result.getresponse=wrapped; return result
        try:
            try: raise ValueError('unrelated caller error')
            except ValueError:
                with patch('fixed_gpu_provider.UnixConnection',side_effect=connection):
                    with self.assertRaises(HttpCaptureFailure) as caught:
                        _request('http://localhost/provider',timeout=1,socket_path='fixture')
            self.assertEqual(caught.exception.raw,b'abc')
            self.assertTrue(caught.exception.complete)
            self.assertIn('cleanup failed',str(caught.exception))
        finally: worker.join(2)


    def test_reader_close_error_after_last_read_preserves_returned_body(self):
        from fixed_gpu_client import _ResponseReader
        listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1); port=listener.getsockname()[1]
        def serve():
            connection,_=listener.accept()
            try: connection.recv(65536); connection.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc')
            finally: connection.close(); listener.close()
        worker=threading.Thread(target=serve,daemon=True); worker.start(); original_close=_ResponseReader.close
        def fail_close(reader):
            if reader.closed: return
            original_close(reader)
            raise RuntimeError('authored raw close failure')
        def connection(_path,timeout): return http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)
        try:
            with patch('fixed_gpu_provider.UnixConnection',side_effect=connection), patch.object(_ResponseReader,'close',fail_close):
                with self.assertRaises(HttpCaptureFailure) as caught:
                    _request('http://localhost/provider',timeout=1,socket_path='fixture')
            self.assertEqual(caught.exception.raw,b'abc')
            self.assertTrue(caught.exception.complete)
            self.assertEqual(caught.exception.status,200)
        finally: worker.join(2)


if __name__ == '__main__':
    unittest.main()
