"""ASGI business wiring with authored fakes; no trusted release or GPU is certified."""
import asyncio
import hashlib
from pathlib import Path
import sys
import threading
import traceback
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import server
sys.path.insert(0, str(Path(server.__file__).resolve().parent.parent / 'offline_delivery'))
from fixed_gpu_client import OperationError


class FakeSession:
    def __init__(self, cancelled, *, block_open=False, block_read=False, closed_event=None, fail_read=False,
                 uncooperative_open=False, uncooperative_read=False):
        self.cancelled = cancelled
        self.block_open, self.block_read = block_open, block_read
        self.opened, self.first, self.release, self.blocked = (threading.Event() for _ in range(4))
        self.closed, self.finished = False, False
        self.operation = 'a' * 32
        self.close_count, self.closed_event, self.fail_read = 0, closed_event, fail_read
        self.uncooperative_open, self.uncooperative_read = uncooperative_open, uncooperative_read
        self.exit_receipt = {'status': 'released', 'operationId': 'a' * 32}

    def open(self):
        self.opened.set()
        if self.uncooperative_open:
            self.blocked.set()
            self.release.wait(2)
        while self.block_open and not self.cancelled():
            self.release.wait(.01)
        if self.cancelled():
            raise ValueError('cancelled during open')
        return 200, {'content-type': 'text/event-stream'}

    def read(self):
        self.first.set()
        yield b'data: partial\n\n'
        if self.fail_read:
            raise ValueError('authored-secret-sentinel')
        if self.uncooperative_read:
            self.blocked.set()
            self.release.wait(2)
        while self.block_read and not self.cancelled() and not self.release.wait(.01):
            pass
        if self.cancelled():
            raise ValueError('cancelled during read')
        self.finished = True
        yield b'data: [DONE]\n\n'

    def close(self):
        self.close_count += 1
        self.closed = True
        self.release.set()
        if self.closed_event:
            self.closed_event.set()


class FakeClient:
    profile_sha256s = {'business-llm': 'a' * 64}
    def __init__(self):
        self.calls = []
        self.session = None
        self.started = threading.Event()
        self.allow_start = threading.Event()
        self.closed_event = threading.Event()
        self.session_ready = threading.Event()
        self.block_start = self.block_open = self.block_read = False
        self.block_call = self.uncooperative_open = self.uncooperative_read = False
        self.fail_read = False

    def call_business(self, path, raw, timeout, cancelled):
        self.calls.append((path, raw))
        self.started.set()
        if self.block_call:
            self.allow_start.wait(2)
        return 200, {'content-type': 'application/json'}, b'{"result":"authored"}', {'operationId': 'a' * 32}

    def start_business(self, path, raw, timeout, cancelled):
        self.calls.append((path, raw))
        self.started.set()
        if self.block_start:
            self.allow_start.wait(2)
        self.session = FakeSession(cancelled, block_open=self.block_open, block_read=self.block_read,
                                   closed_event=self.closed_event, fail_read=self.fail_read,
                                   uncooperative_open=self.uncooperative_open, uncooperative_read=self.uncooperative_read)
        self.session_ready.set()
        return self.session


class BusinessAdapterTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.client = FakeClient()
        self.proofs = []
        self.config = {'model': 'authored-model', 'token': 'outer-authored', 'profileSha256': 'a' * 64,
                       'executionSha256': 'b' * 64,
                       'timeout': 2, 'verifyRelease': self.verify}
        self.proof_ok = True
        self.block_proof = False
        self.proof_entered, self.proof_release = threading.Event(), threading.Event()
        self.remote = patch.object(server, '_remote_models', SimpleNamespace(client=self.client))
        self.remote.start()
        self.addCleanup(self.remote.stop)

    def verify(self, receipt, claims):
        self.proofs.append((receipt, claims))
        if self.block_proof:
            self.proof_entered.set()
            self.proof_release.wait(2)
        return self.proof_ok

    def request(self, raw=b'{"model":"authored-model","stream":false}', *, path='/v1/chat/completions',
                auth='Bearer outer-authored', spec='2.4', send_failure=False):
        scope = {'type': 'http', 'asgi': {'version': '3.0', 'spec_version': spec}, 'http_version': '1.1',
                 'method': 'GET' if path == '/v1/models' else 'POST', 'scheme': 'http', 'path': path,
                 'raw_path': path.encode(), 'query_string': b'', 'headers': [(b'authorization', auth.encode())],
                 'server': ('test', 80), 'client': ('test', 1)}
        queue = asyncio.Queue()
        queue.put_nowait({'type': 'http.request', 'body': raw, 'more_body': False})
        sent = []
        readers = [0, 0]
        async def receive():
            readers[0] += 1
            readers[1] = max(readers)
            try:
                return await queue.get()
            finally:
                readers[0] -= 1
        async def send(event):
            if send_failure == 'start_runtime' and event['type'] == 'http.response.start':
                sent.append(event)
                raise RuntimeError('authored-send-secret')
            if send_failure and event['type'] == 'http.response.body':
                raise OSError('authored disconnected sender')
            sent.append(event)
        return scope, queue, sent, readers, receive, send

    async def wait_thread(self, event):
        self.assertTrue(await asyncio.wait_for(asyncio.to_thread(event.wait, 1), 1.5))

    async def test_default_closed_and_authentication_precede_admission(self):
        for path in ('/v1/models', '/v1/chat/completions'):
            scope, _, sent, _, receive, send = self.request(path=path)
            await server.app(scope, receive, send)
            self.assertEqual(503, sent[0]['status'])
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, _, sent, _, receive, send = self.request(auth='Bearer wrong')
            await server.app(scope, receive, send)
            self.assertEqual(401, sent[0]['status'])
        self.assertEqual([], self.client.calls)

    async def test_final_body_completed_with_disconnect_does_not_abort_response(self):
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, queue, sent, _, receive, _ = self.request()
            async def send(event):
                sent.append(event)
                if event['type'] == 'http.response.body' and not event.get('more_body', False):
                    queue.put_nowait({'type': 'http.disconnect'})
                    await asyncio.sleep(0)
            await server.app(scope, receive, send)
            self.assertEqual(200, sent[0]['status'])
            self.assertEqual(b'{"result":"authored"}', sent[-1]['body'])

    async def test_strict_request_and_nonstream_independent_proof(self):
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            for raw in (b'{"model":"authored-model","model":"authored-model"}', b'{"model":"wrong"}',
                        b'{"model":"authored-model","stream":1}', b'{"model":"authored-model","x":NaN}',
                        b'{"model":"authored-model","x":1e400}',
                        b'{"model":"authored-model","x":"\\ud800"}', b'[]'):
                scope, _, sent, _, receive, send = self.request(raw)
                await server.app(scope, receive, send)
                self.assertEqual(422, sent[0]['status'])
            self.assertEqual([], self.client.calls)
            raw = b'{ "stream": false, "model": "authored-model" }'
            self.proof_ok = False
            scope, _, sent, _, receive, send = self.request(raw)
            await server.app(scope, receive, send)
            self.assertEqual(503, sent[0]['status'])
            self.proof_ok = True
            scope, _, sent, _, receive, send = self.request(raw)
            await server.app(scope, receive, send)
            self.assertEqual(200, sent[0]['status'])
            self.assertNotIn((b'x-consense-gateway-events', b'1'), sent[0]['headers'])
            self.assertEqual(b'{"result":"authored"}', sent[-1]['body'])
            self.assertEqual(raw, self.client.calls[-1][1])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), self.proofs[-1][1]['bodySha256'])

    async def test_stream_is_progressive_and_terminal_needs_independent_proof(self):
        raw = b'{"model":"authored-model","stream":true}'
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            self.client.block_read = True
            scope, _, sent, readers, receive, send = self.request(raw, spec='2.3')
            task = asyncio.create_task(server.app(scope, receive, send))
            await self.wait_thread(self.client.started)
            await self.wait_thread(self.client.session_ready)
            await self.wait_thread(self.client.session.first)
            for _ in range(100):
                if any(e.get('body') == b'data: partial\n\n' for e in sent): break
                await asyncio.sleep(.01)
            self.assertTrue(any(e.get('body') == b'data: partial\n\n' for e in sent))
            self.assertFalse(self.proofs)
            self.client.session.release.set()
            await asyncio.wait_for(task, 2)
            self.assertTrue(any(e.get('body') == b'data: [DONE]\n\n' for e in sent))
            self.assertTrue(self.proofs)
            self.assertEqual(1, readers[1])
            self.assertTrue(self.client.session.closed)
            self.assertEqual(1, self.client.session.close_count)
            self.proof_ok = False
            self.client = FakeClient()
            server._remote_models.client = self.client
            scope, _, sent, _, receive, send = self.request(raw)
            with self.assertRaisesRegex(RuntimeError, 'Business operation stream failed'):
                await server.app(scope, receive, send)
            self.assertTrue(any(e.get('body') == b'data: partial\n\n' for e in sent))
            self.assertFalse(any(e.get('body') == b'data: [DONE]\n\n' for e in sent))
            self.assertTrue(self.client.session.closed)
            self.assertEqual(1, self.client.session.close_count)

    async def test_disconnect_interrupts_open_and_read_for_both_asgi_versions(self):
        raw = b'{"model":"authored-model","stream":true}'
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            for mode, spec in (('open', '2.3'), ('read', '2.4')):
                self.client = FakeClient()
                server._remote_models.client = self.client
                self.client.block_open = mode == 'open'
                self.client.block_read = mode == 'read'
                scope, queue, sent, readers, receive, send = self.request(raw, spec=spec)
                task = asyncio.create_task(server.app(scope, receive, send))
                await self.wait_thread(self.client.started)
                await self.wait_thread(self.client.session_ready)
                await self.wait_thread(self.client.session.opened)
                if mode == 'read':
                    await self.wait_thread(self.client.session.first)
                await queue.put({'type': 'http.disconnect'})
                try:
                    await asyncio.wait_for(task, 2)
                except (RuntimeError, asyncio.CancelledError):
                    pass
                self.assertTrue(self.client.session.closed)
                self.assertEqual(1, self.client.session.close_count)
                self.assertFalse(any(e.get('body') == b'data: [DONE]\n\n' for e in sent))
                self.assertFalse(any(b'consense-gateway-timeout' in e.get('body', b'') for e in sent))
                self.assertEqual(1, readers[1])

    async def test_send_failure_and_late_admission_are_closed(self):
        raw = b'{"model":"authored-model","stream":true}'
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, _, _, _, receive, send = self.request(raw, spec='2.4', send_failure=True)
            with self.assertRaises(asyncio.CancelledError):
                await server.app(scope, receive, send)
            self.assertTrue(self.client.session.closed)
            self.assertEqual(1, self.client.session.close_count)
            self.client = FakeClient()
            server._remote_models.client = self.client
            self.client.block_start = True
            scope, _, _, _, receive, send = self.request(raw)
            task = asyncio.create_task(server.app(scope, receive, send))
            await self.wait_thread(self.client.started)
            task.cancel()
            self.client.allow_start.set()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertIsNotNone(self.client.session)
            self.assertTrue(self.client.session.closed)
            self.assertEqual(1, self.client.session.close_count)

    async def test_late_admission_after_cleanup_timeout_closes_exactly_once(self):
        raw = b'{"model":"authored-model","stream":true}'
        self.client.block_start = True
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, _, _, _, receive, send = self.request(raw)
            task = asyncio.create_task(server.app(scope, receive, send))
            await self.wait_thread(self.client.started)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(task, 1)
            self.assertIsNone(self.client.session)
            self.client.allow_start.set()
            await self.wait_thread(self.client.closed_event)
            self.assertEqual(1, self.client.session.close_count)

    async def test_disconnect_during_verification_hides_terminal(self):
        raw = b'{"model":"authored-model","stream":true}'
        self.block_proof = True
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, queue, sent, readers, receive, send = self.request(raw)
            task = asyncio.create_task(server.app(scope, receive, send))
            await self.wait_thread(self.proof_entered)
            await queue.put({'type': 'http.disconnect'})
            with self.assertRaisesRegex(RuntimeError, 'Business operation stream failed'):
                await asyncio.wait_for(task, 1)
            self.proof_release.set()
            self.assertFalse(any(event.get('body') == b'data: [DONE]\n\n' for event in sent))
            self.assertEqual(1, readers[1])
            self.assertEqual(1, self.client.session.close_count)

    async def test_stream_failure_hides_dependency_exception_details(self):
        self.client.fail_read = True
        raw = b'{"model":"authored-model","stream":true}'
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, _, _, _, receive, send = self.request(raw)
            with self.assertRaises(RuntimeError) as caught:
                await server.app(scope, receive, send)
            self.assertNotIn('authored-secret-sentinel', ''.join(traceback.format_exception(caught.exception)))
            self.assertTrue(caught.exception.__suppress_context__)
            self.assertEqual(1, self.client.session.close_count)

    async def test_uncooperative_calls_return_on_disconnect_and_late_session_closes(self):
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            for mode in ('call', 'open', 'read'):
                self.client = FakeClient()
                server._remote_models.client = self.client
                self.client.block_call = mode == 'call'
                self.client.uncooperative_open = mode == 'open'
                self.client.uncooperative_read = mode == 'read'
                raw = ('{"model":"authored-model","stream":' + ('false' if mode == 'call' else 'true') + '}').encode()
                scope, queue, sent, readers, receive, send = self.request(raw)
                task = asyncio.create_task(server.app(scope, receive, send))
                await self.wait_thread(self.client.started)
                if mode != 'call':
                    await self.wait_thread(self.client.session_ready)
                    await self.wait_thread(self.client.session.blocked)
                await queue.put({'type': 'http.disconnect'})
                try:
                    await asyncio.wait_for(task, 1)
                except (RuntimeError, asyncio.CancelledError):
                    pass
                self.assertEqual(1, len(self.client.calls))
                self.assertFalse(any(event.get('body') == b'data: [DONE]\n\n' for event in sent))
                self.assertEqual(1, readers[1])
                if mode == 'call':
                    self.client.allow_start.set()
                else:
                    self.assertFalse(self.client.session.closed)
                    self.client.session.release.set()
                    await self.wait_thread(self.client.closed_event)
                    self.assertEqual(1, self.client.session.close_count)

    async def test_started_send_error_never_sends_second_start_or_secrets(self):
        raw = b'{"model":"authored-model","stream":true}'
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, _, sent, _, receive, send = self.request(raw, send_failure='start_runtime')
            with self.assertRaises(RuntimeError) as caught:
                await server.app(scope, receive, send)
            self.assertEqual(1, sum(event['type'] == 'http.response.start' for event in sent))
            self.assertNotIn('authored-send-secret', ''.join(traceback.format_exception(caught.exception)))
            self.assertEqual(1, self.client.session.close_count)

    async def test_deadline_interrupts_uncooperative_client(self):
        self.config['timeout'] = .1
        self.client.block_call = True
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, _, sent, _, receive, send = self.request()
            task = asyncio.create_task(server.app(scope, receive, send))
            await self.wait_thread(self.client.started)
            await asyncio.wait_for(task, 1)
            self.assertEqual(504, sent[0]['status'])
            self.assertEqual(1, len(self.client.calls))
            self.assertFalse(self.proofs)
            self.client.allow_start.set()

    async def test_deadline_after_partial_sends_explicit_incomplete_timeout(self):
        self.config['timeout'] = 1.0
        self.client.block_read = True
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, _, sent, _, receive, send = self.request(b'{"model":"authored-model","stream":true}')
            await asyncio.wait_for(server.app(scope, receive, send), 2)
            self.assertEqual(200, sent[0]['status'])
            self.assertIn((b'x-consense-gateway-events', b'1'), sent[0]['headers'])
            self.assertTrue(any(e.get('body') == b'data: partial\n\n' for e in sent))
            self.assertEqual(b'event: consense-gateway-timeout\ndata: TIMEOUT\n\n', sent[-1]['body'])
            self.assertFalse(sent[-1]['more_body'])
            self.assertFalse(any(e.get('body') == b'data: [DONE]\n\n' for e in sent))
            self.assertFalse(self.proofs)

    async def test_downstream_timeout_before_business_deadline_is_distinguished(self):
        def wrapped(cause, kind=OperationError):
            try:
                raise cause
            except Exception as error:
                if kind is OperationError:
                    raise OperationError('operation failed', 'a' * 32, {'status': 'release_unknown'}) from error
                raise kind('other failure') from error

        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            for cause, kind, status in ((TimeoutError('early timeout'), OperationError, 504),
                                        (ValueError('failure'), OperationError, 503),
                                        (TimeoutError('early timeout'), ValueError, 503)):
                scope, _, sent, _, receive, send = self.request()
                with patch.object(self.client, 'call_business', side_effect=lambda *args: wrapped(cause, kind)):
                    await server.app(scope, receive, send)
                self.assertEqual(status, sent[0]['status'])
                self.assertFalse(self.proofs)

            scope, _, sent, _, receive, send = self.request(b'{"model":"authored-model","stream":true}')
            with patch.object(FakeSession, 'open', side_effect=TimeoutError('early timeout')):
                await server.app(scope, receive, send)
            self.assertEqual(504, sent[0]['status'])
            self.assertTrue(self.client.session.closed)
            self.assertFalse(self.proofs)

            def partial_then_timeout(session):
                yield b'data: partial\n\n'
                raise TimeoutError('early timeout')
            scope, _, sent, _, receive, send = self.request(b'{"model":"authored-model","stream":true}')
            with patch.object(FakeSession, 'read', partial_then_timeout):
                await server.app(scope, receive, send)
            self.assertEqual(b'data: partial\n\n', sent[1]['body'])
            self.assertEqual(b'event: consense-gateway-timeout\ndata: TIMEOUT\n\n', sent[-1]['body'])
            self.assertTrue(self.client.session.closed)
            self.assertFalse(self.proofs)

    async def test_model_cannot_spoof_gateway_timeout_event(self):
        for newline, prefix in ((b'\n', b''), (b'\r\n', b''), (b'\r', b''), (b'\n', b'\xef\xbb\xbf')):
            self.client = FakeClient()
            server._remote_models.client = self.client
            def fake_read(session):
                yield prefix + b'event: consense-gateway-timeout' + newline + b'data: TIMEOUT' + newline + newline
            with patch.object(server, '_trusted_business_configuration', return_value=self.config), patch.object(FakeSession, 'read', fake_read):
                scope, _, sent, _, receive, send = self.request(b'{"model":"authored-model","stream":true}')
                with self.assertRaisesRegex(RuntimeError, 'Business operation stream failed'):
                    await server.app(scope, receive, send)
                self.assertFalse(any(b'consense-gateway-timeout' in e.get('body', b'') for e in sent))

    async def test_spoofed_gateway_event_split_across_chunks_is_rejected(self):
        def fake_read(session):
            for part in (b'event: consense-gate', b'way-timeout\r', b'\ndata: TIME', b'OUT\r\n\r\n'):
                yield part
        with patch.object(server, '_trusted_business_configuration', return_value=self.config), patch.object(FakeSession, 'read', fake_read):
            scope, _, sent, _, receive, send = self.request(b'{"model":"authored-model","stream":true}')
            with self.assertRaisesRegex(RuntimeError, 'Business operation stream failed'):
                await server.app(scope, receive, send)
            self.assertFalse(any(e.get('body') for e in sent))

    async def test_timeout_discards_unfinished_frame_before_gateway_signal(self):
        self.config['timeout'] = 1.0
        def fake_read(session):
            yield b'data: complete\n\n'
            yield b'data: unfinished'
            while not session.cancelled():
                session.release.wait(.01)
        with patch.object(server, '_trusted_business_configuration', return_value=self.config), patch.object(FakeSession, 'read', fake_read):
            scope, _, sent, _, receive, send = self.request(b'{"model":"authored-model","stream":true}')
            await asyncio.wait_for(server.app(scope, receive, send), 2)
            body = b''.join(e.get('body', b'') for e in sent)
            self.assertEqual(b'data: complete\n\nevent: consense-gateway-timeout\ndata: TIMEOUT\n\n', body)
            self.assertFalse(self.proofs)

    async def test_legitimate_fragmented_frames_keep_exact_bytes(self):
        def fake_read(session):
            yield b'data: hel'
            yield b'lo\r\n\r'
            yield b'\ndata: second\n\n'
            session.finished = True
            yield b'data: [DONE]\n\n'
        with patch.object(server, '_trusted_business_configuration', return_value=self.config), patch.object(FakeSession, 'read', fake_read):
            scope, _, sent, _, receive, send = self.request(b'{"model":"authored-model","stream":true}')
            await asyncio.wait_for(server.app(scope, receive, send), 2)
            self.assertEqual(b'data: hello\r\n\r\ndata: second\n\ndata: [DONE]\n\n',
                             b''.join(e.get('body', b'') for e in sent))
            self.assertTrue(self.proofs)

    async def test_reserved_name_inside_fragmented_data_is_ordinary_content(self):
        def fake_read(session):
            yield b'data: {"text":"'
            yield b'event: consense-gateway-timeout'
            yield b'"}\n\n'
            session.finished = True
            yield b'data: [DONE]\n\n'
        with patch.object(server, '_trusted_business_configuration', return_value=self.config), patch.object(FakeSession, 'read', fake_read):
            scope, _, sent, _, receive, send = self.request(b'{"model":"authored-model","stream":true}')
            await asyncio.wait_for(server.app(scope, receive, send), 2)
            self.assertEqual(b'data: {"text":"event: consense-gateway-timeout"}\n\ndata: [DONE]\n\n',
                             b''.join(e.get('body', b'') for e in sent))

    async def test_timeout_during_blocked_send_is_not_called_disconnect(self):
        self.config['timeout'] = 1.0
        scope, _, sent, _, receive, base_send = self.request(b'{"model":"authored-model","stream":true}')
        entered, stopped = asyncio.Event(), asyncio.Event()
        async def slow_send(event):
            if event.get('body') == b'data: partial\n\n':
                entered.set()
                try:
                    await asyncio.Event().wait()
                finally:
                    stopped.set()
            await base_send(event)
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            task = asyncio.create_task(server.app(scope, receive, slow_send))
            await asyncio.wait_for(entered.wait(), 2)
            with self.assertRaisesRegex(RuntimeError, 'send deadline elapsed'):
                await asyncio.wait_for(task, 2)
            await asyncio.wait_for(stopped.wait(), 1)
            self.assertFalse(any(b'consense-gateway-timeout' in e.get('body', b'') for e in sent))
            self.assertFalse(any(e.get('body') == b'data: [DONE]\n\n' for e in sent))

    async def test_timeout_while_sending_headers_never_sends_second_response(self):
        self.config['timeout'] = 1.0
        scope, _, _, _, receive, _ = self.request(b'{"model":"authored-model","stream":true}')
        attempted = []
        async def blocked_start(event):
            attempted.append(event)
            if event['type'] == 'http.response.start':
                await asyncio.Event().wait()
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            with self.assertRaisesRegex(RuntimeError, 'send deadline elapsed'):
                await asyncio.wait_for(server.app(scope, receive, blocked_start), 2)
        self.assertEqual(['http.response.start'], [event['type'] for event in attempted])

    async def test_slow_send_is_cancelled_on_disconnect(self):
        raw = b'{"model":"authored-model","stream":true}'
        with patch.object(server, '_trusted_business_configuration', return_value=self.config):
            scope, queue, sent, _, receive, base_send = self.request(raw)
            entered, stopped = asyncio.Event(), asyncio.Event()
            async def slow_send(event):
                if event.get('body') == b'data: partial\n\n':
                    entered.set()
                    try:
                        await asyncio.Event().wait()
                    finally:
                        stopped.set()
                await base_send(event)
            task = asyncio.create_task(server.app(scope, receive, slow_send))
            await asyncio.wait_for(entered.wait(), 1)
            await queue.put({'type': 'http.disconnect'})
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(task, 1)
            await asyncio.wait_for(stopped.wait(), 1)
            self.assertFalse(any(event.get('body') == b'data: [DONE]\n\n' for event in sent))
            self.assertEqual(1, self.client.session.close_count)


if __name__ == '__main__':
    unittest.main()
