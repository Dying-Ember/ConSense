"""Unified business ASGI contract with authored fakes and no production trust."""
import asyncio
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import oer_native_pdf_server
import oer_unified_server
import test_oer_server as oer_fixtures
import test_server_business_adapter as business_fixtures


class UnifiedBusinessTest(unittest.IsolatedAsyncioTestCase):
    request = business_fixtures.BusinessAdapterTest.request
    wait_thread = business_fixtures.BusinessAdapterTest.wait_thread

    def setUp(self):
        self.fixture = oer_fixtures.OerHttpContractTest('test_project_pre_and_contract_references_keep_full_payload_and_unknown_quality')
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.addCleanup(self.fixture.doCleanups)
        self.backend = self.fixture.backend
        self.client = business_fixtures.FakeClient()
        from remote_gpu_models import RemoteModels
        self.backend._remote_models = RemoteModels(self.backend,self.client,dtype='float32',serial_offload=True,timeout=5)
        self.backend.model_runtime = self.backend._remote_models.model_runtime
        self.proofs = []
        self.proof_ok = True
        self.config = {'model': 'authored-model', 'token': 'outer-authored', 'profileSha256': 'a' * 64,
                       'executionSha256': 'b' * 64, 'timeout': 2, 'verifyRelease': self.verify}

    def verify(self, receipt, claims):
        self.proofs.append((receipt, claims))
        return self.proof_ok

    def app(self, **kwargs):
        return oer_unified_server.create_unified_app(self.fixture.policy, backend=self.backend, **kwargs)

    async def test_default_off_and_missing_trust_refuses_startup(self):
        with self.assertRaisesRegex(ValueError, 'Trusted business configuration'):
            self.app(with_business=True)
        app = self.app()
        scope, _, sent, _, receive, send = self.request()
        await app(scope, receive, send)
        self.assertEqual(404, sent[0]['status'])
        self.assertEqual([], self.client.calls)

    async def test_exact_routes_missing_duplicate_and_conflicts_fail(self):
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config):
            app = self.app(with_business=True)
            required = {('/v1/models', 'GET'), ('/v1/chat/completions', 'POST'),
                        ('/v1/responses', 'POST'), ('/v1/responses/input_tokens', 'POST')}
            actual = [(route.path, method) for route in app.routes for method in getattr(route, 'methods', ())
                      if (route.path, method) in required]
            self.assertEqual(required, set(actual))
            self.assertEqual(len(required), len(actual))
            for route in [r for r in self.backend.app.routes if r.path == '/v1/responses']:
                without = [r for r in self.backend.app.routes if r is not route]
                with patch.object(self.backend.app.router, 'routes', without), self.assertRaises(ValueError):
                    self.app(with_business=True)
                with patch.object(self.backend.app.router, 'routes', [*self.backend.app.routes, route]), self.assertRaises(ValueError):
                    self.app(with_business=True)
            original = oer_unified_server.create_native_pdf_app
            def conflict(*args, **kwargs):
                app = original(*args, **kwargs)
                app.add_api_route('/v1/models', lambda: {}, methods=['GET'])
                return app
            with patch.object(oer_unified_server, 'create_native_pdf_app', side_effect=conflict), self.assertRaises(ValueError):
                self.app(with_business=True)

    async def test_authentication_precedes_body_and_scope_has_no_secret(self):
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config):
            app = self.app(with_business=True, maximum_message_bytes=1024)
            for path in ('/v1/models', '/v1/chat/completions'):
                scope, _, sent, readers, receive, send = self.request(path=path, auth='Bearer wrong')
                await app(scope, receive, send)
                self.assertEqual(401, sent[0]['status'])
                self.assertEqual(0, readers[1])
            scope, _, sent, _, receive, send = self.request(path='/v1/models')
            await app(scope, receive, send)
            self.assertEqual(200, sent[0]['status'])
            self.assertNotIn('outer-authored', repr(scope['consense.business.preflight']))
            self.assertNotIn('token', scope['consense.business.preflight'])
            self.assertEqual([], self.client.calls)
            with patch.object(self.backend, '_trusted_business_configuration', return_value=None):
                scope, _, sent, readers, receive, send = self.request()
                await app(scope, receive, send)
                self.assertEqual(503, sent[0]['status'])
                self.assertEqual(0, readers[1])

    async def test_bounded_business_body_does_not_relax_other_paths(self):
        self.assertEqual(64 * 1024 * 1024, oer_native_pdf_server.BUSINESS_REQUEST_MAXIMUM)
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config), \
                patch.object(oer_native_pdf_server, 'BUSINESS_REQUEST_MAXIMUM', 1024):
            app = self.app(with_business=True, maximum_message_bytes=1024)
            raw = b'{"model":"authored-model"}' + b' ' * (1024 - len(b'{"model":"authored-model"}'))
            scope, _, sent, _, receive, send = self.request(raw)
            await app(scope, receive, send)
            self.assertEqual(200, sent[0]['status'])
            self.assertEqual(raw, self.client.calls[-1][1])
            scope, _, sent, _, receive, send = self.request(raw + b' ')
            await app(scope, receive, send)
            self.assertEqual(413, sent[0]['status'])
            scope, _, sent, _, receive, send = self.request(b'x' * 1025, path='/v1/chat/completions-extra')
            await app(scope, receive, send)
            self.assertEqual(413, sent[0]['status'])
            self.assertEqual(1, len(self.client.calls))

    async def test_body_timeout_and_shared_deadline(self):
        self.config['timeout'] = .25
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config):
            app = self.app(with_business=True)
            scope, queue, sent, _, receive, send = self.request()
            queue.get_nowait()
            queue.put_nowait({'type': 'http.request', 'body': b'{', 'more_body': True})
            await asyncio.wait_for(app(scope, receive, send), 1)
            self.assertEqual(504, sent[0]['status'])
            self.assertEqual([], self.client.calls)

            class TimedClient(business_fixtures.FakeClient):
                def call_business(inner, path, raw, timeout, cancelled):
                    inner.seen_timeout = timeout
                    return super(TimedClient, inner).call_business(path, raw, timeout, cancelled)
            timed = TimedClient()
            timed.block_call = True
            self.client = timed
            self.backend._remote_models.client = timed
            scope, queue, sent, _, receive, send = self.request()
            queue.get_nowait()
            queue.put_nowait({'type': 'http.request', 'body': b'{"model":', 'more_body': True})
            task = asyncio.create_task(app(scope, receive, send))
            await asyncio.sleep(.14)
            await queue.put({'type': 'http.request', 'body': b'"authored-model"}', 'more_body': False})
            await self.wait_thread(timed.started)
            self.assertLess(timed.seen_timeout, .2)
            await asyncio.wait_for(task, 1)
            timed.allow_start.set()
            self.assertEqual(504, sent[0]['status'])
            self.assertEqual(1, len(timed.calls))

    async def test_configuration_change_during_body_never_falls_back_to_legacy(self):
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config), \
                patch.object(self.backend, 'gpu_coordinator', side_effect=AssertionError('legacy fallback')):
            app = self.app(with_business=True)
            for change in ('remote-none', 'verifier'):
                self.backend._remote_models = SimpleNamespace(client=self.client)
                old_verifier = self.config['verifyRelease']
                scope, queue, sent, _, base_receive, send = self.request()
                queue.get_nowait()
                queue.put_nowait({'type': 'http.request', 'body': b'{"model":', 'more_body': True})
                consumed = asyncio.Event()
                async def receive():
                    event = await base_receive()
                    consumed.set()
                    return event
                task = asyncio.create_task(app(scope, receive, send))
                await asyncio.wait_for(consumed.wait(), 1)
                if change == 'remote-none':
                    self.backend._remote_models = None
                else:
                    self.config['verifyRelease'] = lambda receipt, claims: True
                await queue.put({'type': 'http.request', 'body': b'"authored-model"}', 'more_body': False})
                await asyncio.wait_for(task, 1)
                self.assertEqual(503, sent[0]['status'])
                self.config['verifyRelease'] = old_verifier
            self.assertEqual([], self.client.calls)

    async def test_inflight_configuration_is_immutable_per_request(self):
        self.client.block_call = True
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config):
            app = self.app(with_business=True)
            scope, _, sent, _, receive, send = self.request()
            task = asyncio.create_task(app(scope, receive, send))
            await self.wait_thread(self.client.started)
            self.config.update(model='replaced-model', profileSha256='d' * 64, executionSha256='e' * 64,
                               timeout=.01, verifyRelease=lambda receipt, claims: False)
            self.client.allow_start.set()
            await asyncio.wait_for(task, 1)
            self.assertEqual(200, sent[0]['status'])
            self.assertEqual('authored-model', self.proofs[0][1]['model'])
            self.assertEqual('a' * 64, self.proofs[0][1]['profileSha256'])
            self.assertEqual('b' * 64, self.proofs[0][1]['executionSha256'])

    async def test_progressive_stream_and_disconnect_has_one_receive_owner(self):
        raw = b'{"model":"authored-model","stream":true}'
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config):
            app = self.app(with_business=True)
            for spec in ('2.3', '2.4'):
                self.client = business_fixtures.FakeClient()
                self.client.block_read = True
                self.backend._remote_models.client = self.client
                scope, queue, sent, readers, receive, send = self.request(raw, spec=spec)
                task = asyncio.create_task(app(scope, receive, send))
                await self.wait_thread(self.client.session_ready)
                await self.wait_thread(self.client.session.first)
                for _ in range(100):
                    if any(e.get('body') == b'data: partial\n\n' for e in sent): break
                    await asyncio.sleep(.01)
                self.assertTrue(any(e.get('body') == b'data: partial\n\n' for e in sent))
                self.assertFalse(any(e.get('body') == b'data: [DONE]\n\n' for e in sent))
                await queue.put({'type': 'http.disconnect'})
                try:
                    await asyncio.wait_for(task, 1)
                except (RuntimeError, asyncio.CancelledError):
                    pass
                self.assertTrue(self.client.session.closed)
                self.assertEqual(1, self.client.session.close_count)
                self.assertEqual(1, readers[1])

    async def test_proven_terminal_and_slow_send_cancellation(self):
        raw = b'{"model":"authored-model","stream":true}'
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config):
            app = self.app(with_business=True)
            scope, _, sent, _, receive, send = self.request(raw)
            await app(scope, receive, send)
            self.assertTrue(any(e.get('body') == b'data: [DONE]\n\n' for e in sent))
            self.assertEqual(1, len(self.proofs))
            self.client = business_fixtures.FakeClient()
            self.backend._remote_models.client = self.client
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
            task = asyncio.create_task(app(scope, receive, slow_send))
            await asyncio.wait_for(entered.wait(), 1)
            await queue.put({'type': 'http.disconnect'})
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(task, 1)
            await asyncio.wait_for(stopped.wait(), 1)
            self.assertFalse(any(e.get('body') == b'data: [DONE]\n\n' for e in sent))
            self.assertEqual(1, self.client.session.close_count)

    async def test_unverified_terminal_and_sender_disconnect_stay_incomplete(self):
        raw = b'{"model":"authored-model","stream":true}'
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config):
            app = self.app(with_business=True)
            self.proof_ok = False
            scope, _, sent, _, receive, send = self.request(raw)
            with self.assertRaisesRegex(RuntimeError, 'Business operation stream failed'):
                await app(scope, receive, send)
            self.assertTrue(any(e.get('body') == b'data: partial\n\n' for e in sent))
            self.assertFalse(any(e.get('body') == b'data: [DONE]\n\n' for e in sent))
            self.proof_ok = True
            self.client = business_fixtures.FakeClient()
            self.backend._remote_models.client = self.client
            scope, _, _, _, receive, send = self.request(raw, spec='2.4', send_failure=True)
            with self.assertRaises(asyncio.CancelledError):
                await app(scope, receive, send)
            self.assertEqual(1, self.client.session.close_count)

    async def test_ocr_remains_opt_in_when_business_enabled(self):
        with patch.object(self.backend, '_trusted_business_configuration', return_value=self.config):
            for enabled, expected in ((False, 404), (True, 422)):
                app = self.app(with_business=True, with_ocr=enabled)
                scope, _, sent, _, receive, send = self.request(b'', path='/ocr')
                await app(scope, receive, send)
                self.assertEqual(expected, sent[0]['status'])


if __name__ == '__main__':
    unittest.main()
