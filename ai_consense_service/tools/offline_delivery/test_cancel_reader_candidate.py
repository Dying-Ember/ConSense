import http.server
import os
import selectors
import socket
import sys
import threading
import time
import unittest
from unittest import mock

import fixed_gpu_client as client_module


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass

    def do_GET(self):
        try:
            if self.path == '/headers':
                self.server.started.set(); time.sleep(.7)
            self.send_response(200)
            if self.path.endswith('/stream'): self.send_header('Content-Type', 'text/event-stream')
            elif self.path in ('/slow', '/deadline', '/body', '/truncated'):
                self.send_header('Content-Length', '2')
            self.end_headers()
            if self.path.endswith('/stream'):
                self.wfile.write(b'data: {"choices":[{"delta":{"content":"x"}}]}\n\n'); self.wfile.flush()
                self.server.started.set(); time.sleep(.7)
            elif self.path in ('/slow', '/deadline', '/body', '/truncated'):
                self.wfile.write(b'{'); self.wfile.flush(); self.server.started.set()
                if self.path != '/truncated':
                    time.sleep(.35 if self.path == '/slow' else .7)
                    self.wfile.write(b'}'); self.wfile.flush()
        except OSError: pass


class CandidateTest(unittest.TestCase):
    def setUp(self):
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.started = threading.Event()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.client = client_module.Client('http://127.0.0.1:%s' % self.server.server_port,
            'fixture', {'business-llm':'e'*64})
        self.readers = []
        original = client_module._ResponseReader
        readers = self.readers
        class TrackedReader(original):
            def __init__(self, *args):
                super().__init__(*args); readers.append(self)
        self.patcher = mock.patch.object(client_module, '_ResponseReader', TrackedReader)
        self.patcher.start()

    def tearDown(self):
        self.patcher.stop()
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.assertTrue(self.readers)
        self.assertTrue(all(r.closed and r.sock.fileno() == -1 for r in self.readers))
        self.assertTrue(all(r.selector.get_map() is None for r in self.readers))

    def test_cancel_during_headers_and_incomplete_body(self):
        for path in ('/headers', '/body'):
            with self.subTest(path=path):
                self.server.started.clear(); cancelled = threading.Event()
                timer = threading.Thread(target=lambda: (self.server.started.wait(), cancelled.set()), daemon=True)
                timer.start(); started = time.monotonic()
                with self.assertRaises((ValueError, OSError)):
                    self.client._call('GET', path, 1.2, cancelled=cancelled.is_set)
                self.assertLess(time.monotonic() - started, 1.0)
                timer.join()

    def test_slow_success_and_truncated_response(self):
        self.assertEqual({}, self.client._call('GET', '/slow', 1.2))
        with self.assertRaises(ValueError): self.client._call('GET', '/truncated', 1.2)

    def test_deadline_still_applies_to_incomplete_body(self):
        started = time.monotonic()
        with self.assertRaises(TimeoutError): self.client._call('GET', '/deadline', .2)
        self.assertLess(time.monotonic()-started, .9)

    def test_cancel_while_stream_body_blocked(self):
        cancelled = threading.Event(); expected = {'profileSha256':'e'*64}
        state = {'operationId':'a'*32, 'stage':'business-llm', 'bindingSha256':'b'*64, **expected}
        self.client._call = lambda *args, **kwargs: state
        self.client.cancel_and_observe = lambda *args: {'status':'release_unknown'}
        stream = client_module.BusinessStream(self.client, 'a'*32, time.monotonic()+2, expected, cancelled.is_set)
        self.assertEqual(200, stream.open()[0])
        output = stream.read(); self.assertIn(b'data:', next(output))
        timer = threading.Timer(.1, cancelled.set); timer.start(); started = time.monotonic()
        with self.assertRaises((ValueError, OSError)): next(output)
        timer.join(); self.assertLess(time.monotonic()-started, 1.0)
        self.assertEqual('release_unknown', stream.exit_receipt['status'])


class SelectorTest(unittest.TestCase):
    def test_cancel_during_select_or_recv_rejects_ready_data(self):
        for stage in ('select', 'recv'):
            with self.subTest(stage=stage):
                cancelled = threading.Event()
                original, peer, selector = mock.Mock(), mock.Mock(), mock.Mock()
                original.dup.return_value = peer
                def ready(*args):
                    if stage == 'select': cancelled.set()
                    return [(mock.Mock(), selectors.EVENT_READ)]
                def received(*args):
                    if stage == 'recv': cancelled.set()
                    return 1
                selector.select.side_effect = ready
                peer.recv_into.side_effect = received
                with mock.patch.object(client_module.selectors, 'DefaultSelector', return_value=selector):
                    reader = client_module._ResponseReader(original, time.monotonic()+1, cancelled.is_set)
                    try:
                        with self.assertRaisesRegex(ValueError, 'cancelled'): reader.readinto(bytearray(1))
                    finally: reader.close()
                if stage == 'select': peer.recv_into.assert_not_called()

    def test_cancel_during_buffered_call_read_rejects_result(self):
        cancelled = threading.Event()
        client = client_module.Client('http://127.0.0.1:1234','token',{'business-llm':'e'*64})
        response = mock.Mock(status=200, length=0)
        response.getheader.return_value = '2'
        response.isclosed.side_effect = [False, True]
        response.read1.side_effect = lambda size: (cancelled.set() or b'{}')
        connection = mock.Mock(sock=mock.Mock())
        connection.getresponse.return_value = response
        with mock.patch.object(client, '_connection', return_value=connection):
            with self.assertRaisesRegex(ValueError, 'cancelled'):
                client._call('GET', '/fixture', 1, cancelled=cancelled.is_set)

    def test_cancel_between_buffered_stream_frames_never_yields_next(self):
        cancelled = threading.Event()
        client = client_module.Client('http://127.0.0.1:1234','token',{'business-llm':'e'*64})
        client.cancel_and_observe = lambda *args: {'status':'release_unknown'}
        stream = client_module.BusinessStream(client, 'a'*32, time.monotonic()+2, {}, cancelled.is_set)
        stream.response = mock.Mock(status=200)
        stream.response.getheader.return_value = 'text/event-stream'
        stream.response.isclosed.return_value = False
        stream.response.read1.return_value = (b'data: {"choices":[{"delta":{"content":"a"}}]}\n\n'
                                               b'data: {"choices":[{"delta":{"content":"b"}}]}\n\n')
        output = stream.read()
        self.assertIn(b'"a"', next(output))
        cancelled.set()
        with self.assertRaisesRegex(ValueError, 'cancelled'): next(output)
        self.assertEqual('release_unknown', stream.exit_receipt['status'])

    def test_cancel_during_stream_read_never_yields_frame(self):
        cancelled = threading.Event()
        client = client_module.Client('http://127.0.0.1:1234','token',{'business-llm':'e'*64})
        client.cancel_and_observe = lambda *args: {'status':'release_unknown'}
        stream = client_module.BusinessStream(client, 'a'*32, time.monotonic()+2, {}, cancelled.is_set)
        stream.response = mock.Mock(status=200)
        stream.response.getheader.return_value = 'text/event-stream'
        stream.response.isclosed.return_value = False
        stream.response.read1.side_effect = lambda size: (cancelled.set() or b'data: hello\n\n')
        with self.assertRaisesRegex(ValueError, 'cancelled'): next(stream.read())
        self.assertEqual('release_unknown', stream.exit_receipt['status'])

    def test_large_descriptor_is_registered_without_select_fdset(self):
        original, peer = mock.Mock(), mock.Mock()
        peer.fileno.return_value = 5000
        original.dup.return_value = peer
        selector = mock.Mock()
        selector.select.return_value = [(mock.Mock(), selectors.EVENT_READ)]
        peer.recv_into.return_value = 1
        with mock.patch.object(client_module.selectors, 'DefaultSelector', return_value=selector):
            reader = client_module._ResponseReader(original, time.monotonic()+1, None)
            self.assertEqual(1, reader.readinto(bytearray(1)))
            reader.close()
        selector.register.assert_called_once_with(peer, selectors.EVENT_READ)
        selector.close.assert_called_once()
        peer.close.assert_called_once()

    def test_failed_registration_closes_selector_and_duplicate(self):
        original, peer, selector = mock.Mock(), mock.Mock(), mock.Mock()
        original.dup.return_value = peer
        selector.register.side_effect = OSError('registration failed')
        with mock.patch.object(client_module.selectors, 'DefaultSelector', return_value=selector):
            with self.assertRaisesRegex(OSError, 'registration failed'):
                client_module._ResponseReader(original, time.monotonic()+1, None)
        selector.close.assert_called_once()
        peer.close.assert_called_once()

    def test_failed_socket_setup_closes_duplicate(self):
        original, peer = mock.Mock(), mock.Mock()
        original.dup.return_value = peer
        peer.setblocking.side_effect = OSError('socket setup failed')
        with self.assertRaisesRegex(OSError, 'socket setup failed'):
            client_module._ResponseReader(original, time.monotonic()+1, None)
        peer.close.assert_called_once()

    def test_failed_dup_propagates(self):
        original = mock.Mock()
        original.dup.side_effect = OSError('dup failed')
        with self.assertRaisesRegex(OSError, 'dup failed'):
            client_module._ResponseReader(original, time.monotonic()+1, None)

    @unittest.skipUnless(sys.platform.startswith('linux'), 'actual epoll/high-FD check requires Linux')
    def test_linux_epoll_receives_with_descriptor_above_fd_setsize(self):
        import resource
        if resource.getrlimit(resource.RLIMIT_NOFILE)[0] < 1100:
            self.skipTest('insufficient descriptor limit for high-FD test')
        handles = []
        left = right = reader = None
        try:
            while not handles or handles[-1] < 1030:
                handles.append(os.open(os.devnull, os.O_RDONLY))
            left, right = socket.socketpair()
            reader = client_module._ResponseReader(left, time.monotonic()+2, None)
            self.assertGreaterEqual(reader.sock.fileno(), 1024)
            self.assertIsInstance(reader.selector, selectors.EpollSelector)
            right.sendall(b'x')
            output = bytearray(1)
            self.assertEqual(1, reader.readinto(output))
            self.assertEqual(b'x', bytes(output))
        finally:
            if reader is not None: reader.close()
            if left is not None: left.close()
            if right is not None: right.close()
            for handle in handles: os.close(handle)


if __name__ == '__main__': unittest.main()
