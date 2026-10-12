"""Offline boundary tests for the LAN relay; no real credentials or API calls."""

import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import sys
import threading
from types import SimpleNamespace
import unittest
import urllib.error
from unittest.mock import Mock, patch


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "minimax-relay.py"
SPEC = importlib.util.spec_from_file_location("consense_minimax_relay", SCRIPT_PATH)
relay = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = relay
SPEC.loader.exec_module(relay)

PROVIDER_KEY = "dummy-provider-key-for-offline-tests"
RELAY_TOKEN = "dummy-relay-token-for-offline-tests"


class FakeResponse(io.BytesIO):
    def __init__(self, status, body):
        super().__init__(body)
        self.status = status

    def getcode(self):
        return self.status


class RelayHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.calls = []
        cls.response = relay.UpstreamResponse(
            200,
            json.dumps({"id": "chat-test", "object": "chat.completion", "choices": [{"message": {"role": "assistant", "content": "Hello"}}]}).encode(),
        )

        def transport(payload, provider_key, timeout):
            cls.calls.append((payload, provider_key, timeout))
            if cls.block:
                cls.started.release()
                cls.release.wait(5)
            if isinstance(cls.response, Exception):
                raise cls.response
            return cls.response

        cls.server = relay.create_server("127.0.0.1", 0, PROVIDER_KEY, RELAY_TOKEN, transport=transport)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(2)

    def setUp(self):
        type(self).calls.clear()
        type(self).block = False
        type(self).release = threading.Event()
        type(self).started = threading.Semaphore(0)
        type(self).response = relay.UpstreamResponse(200, b'{"id":"chat-test","choices":[{"message":{"role":"assistant","content":"Hello"}}]}')

    def request(self, method="POST", path="/v1/chat/completions", payload=None, token=RELAY_TOKEN, headers=None, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        request_headers = {"Content-Type": "application/json"}
        if token is not None:
            request_headers["Authorization"] = "Bearer " + token
        if headers:
            request_headers.update(headers)
        if body is None and method == "POST":
            payload = payload if payload is not None else {"model": relay.DEFAULT_MODEL, "messages": [{"role": "user", "content": "Hi"}], "max_tokens": 128}
            body = json.dumps(payload).encode()
        connection.request(method, path, body=body, headers=request_headers)
        response = connection.getresponse()
        raw = response.read()
        response_headers = dict(response.getheaders())
        connection.close()
        return response.status, json.loads(raw), response_headers

    def test_public_health_contains_no_credentials(self):
        status, data, headers = self.request("GET", "/health", token=None)
        self.assertEqual(status, 200)
        self.assertEqual(data["status"], "ok")
        self.assertNotIn(PROVIDER_KEY, json.dumps(data))
        self.assertNotIn(RELAY_TOKEN, json.dumps(data))
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(self.calls, [])

    def test_models_require_relay_auth(self):
        status, _, _ = self.request("GET", "/v1/models", token=None)
        self.assertEqual(status, 401)
        status, data, _ = self.request("GET", "/v1/models")
        self.assertEqual(status, 200)
        self.assertEqual([row["id"] for row in data["data"]], [relay.DEFAULT_MODEL])
        self.assertEqual(self.calls, [])

    def test_provider_key_cannot_authenticate_the_relay(self):
        for token in (None, "wrong-token", PROVIDER_KEY, RELAY_TOKEN + "-suffix"):
            with self.subTest(token=token):
                status, data, _ = self.request(token=token)
                self.assertEqual(status, 401)
                self.assertEqual(data["error"]["code"], "invalid_relay_token")
        self.assertEqual(self.calls, [])

    def test_duplicate_authorization_is_refused(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        body = b'{"messages":[{"role":"user","content":"Hi"}]}'
        connection.putrequest("POST", "/v1/chat/completions")
        connection.putheader("Content-Type", "application/json")
        connection.putheader("Content-Length", str(len(body)))
        connection.putheader("Authorization", "Bearer " + RELAY_TOKEN)
        connection.putheader("Authorization", "Bearer " + PROVIDER_KEY)
        connection.endheaders(body)
        response = connection.getresponse()
        self.assertEqual(response.status, 401)
        response.read()
        connection.close()
        self.assertEqual(self.calls, [])

    def test_valid_completion_swaps_auth_and_preserves_openai_payload(self):
        payload = {"model": relay.DEFAULT_MODEL, "messages": [{"role": "user", "content": "中文"}], "max_tokens": 128, "temperature": 0.2}
        status, data, headers = self.request(payload=payload, headers={"X-Api-Key": "ignored-client-provider-credential"})
        self.assertEqual(status, 200)
        self.assertEqual(data["choices"][0]["message"]["content"], "Hello")
        self.assertEqual(self.calls, [(payload, PROVIDER_KEY, 600)])
        self.assertNotIn("Authorization", headers)
        self.assertNotIn("Set-Cookie", headers)

    def test_missing_model_and_budget_use_server_defaults(self):
        status, _, _ = self.request(payload={"messages": [{"role": "user", "content": "Hi"}]})
        self.assertEqual(status, 200)
        self.assertEqual(self.calls[0][0]["model"], relay.DEFAULT_MODEL)
        self.assertEqual(self.calls[0][0]["max_tokens"], 16384)

    def test_paths_queries_and_absolute_targets_never_call_provider(self):
        cases = [("/v1/completions", 404), ("/v1/chat/completions?key=" + PROVIDER_KEY, 400), ("/health?", 400), ("http://malicious.example/v1/chat/completions", 400), ("/v1/chat/completions#fragment", 400)]
        for path, expected in cases:
            with self.subTest(path=path):
                status, data, _ = self.request(path=path)
                self.assertEqual(status, expected)
                self.assertNotIn(PROVIDER_KEY, json.dumps(data))
        self.assertEqual(self.calls, [])

    def test_wrong_methods_are_rejected(self):
        for method, path in (("GET", "/v1/chat/completions"), ("POST", "/v1/models"), ("POST", "/health"), ("DELETE", "/v1/chat/completions"), ("OPTIONS", "/v1/chat/completions")):
            with self.subTest(method=method, path=path):
                status, _, _ = self.request(method, path)
                self.assertIn(status, (405, 501))
        self.assertEqual(self.calls, [])

    def test_unsupported_model_stream_and_n(self):
        baseline = {"messages": [{"role": "user", "content": "Hi"}]}
        for field, value, code in (("model", "other-model", "unsupported_model"), ("stream", True, "unsupported_stream"), ("stream", "false", "unsupported_stream"), ("stream", 0, "unsupported_stream"), ("n", 2, "unsupported_n"), ("n", True, "unsupported_n")):
            with self.subTest(field=field, value=value):
                status, data, _ = self.request(payload={**baseline, field: value})
                self.assertEqual(status, 400)
                self.assertEqual(data["error"]["code"], code)
        self.assertEqual(self.calls, [])

    def test_output_budgets_are_bounded(self):
        for field in ("max_tokens", "max_completion_tokens"):
            for value in (0, -1, 16385, 10.0, "128", True, None):
                with self.subTest(field=field, value=value):
                    status, data, _ = self.request(payload={"messages": [{"role": "user", "content": "Hi"}], field: value})
                    self.assertEqual(status, 400)
                    self.assertEqual(data["error"]["code"], "invalid_token_budget")
        status, data, _ = self.request(payload={"messages": [{"role": "user", "content": "Hi"}], "max_tokens": 16, "max_completion_tokens": 16})
        self.assertEqual(status, 400)
        self.assertEqual(data["error"]["code"], "conflicting_token_budget")
        self.assertEqual(self.calls, [])
        status, _, _ = self.request(payload={"messages": [{"role": "user", "content": "Hi"}], "max_tokens": 16384})
        self.assertEqual(status, 200)

    def test_client_cannot_configure_upstream(self):
        for field in ("api_key", "base_url", "upstream", "upstream_url", "headers"):
            with self.subTest(field=field):
                status, data, _ = self.request(payload={"messages": [{"role": "user", "content": "Hi"}], field: "attacker-controlled"})
                self.assertEqual(status, 400)
                self.assertEqual(data["error"]["code"], "unsupported_upstream_configuration")
        self.assertEqual(self.calls, [])

    def test_invalid_json_and_messages(self):
        for body in (b"broken-json", b"[]", b'{"messages":[]}', b'{"messages":[1]}', b'{"messages":[{}],"temperature":NaN}', b"\xff"):
            with self.subTest(body=body):
                status, _, _ = self.request(body=body)
                self.assertEqual(status, 400)
        self.assertEqual(self.calls, [])

    def test_wrong_content_type_and_chunked_are_refused(self):
        status, _, _ = self.request(headers={"Content-Type": "text/plain"})
        self.assertEqual(status, 415)
        status, _, _ = self.request(headers={"Transfer-Encoding": "chunked"})
        self.assertEqual(status, 400)
        self.assertEqual(self.calls, [])

    def test_oversized_body_is_refused_before_reading(self):
        status, data, _ = self.request(body=b"", headers={"Content-Length": str(relay.MAX_BODY_BYTES + 1)})
        self.assertEqual(status, 413)
        self.assertEqual(data["error"]["code"], "request_too_large")
        self.assertEqual(self.calls, [])

    def test_nested_provider_and_relay_secrets_are_redacted(self):
        data = {"choices": [{"message": {"content": "provider=" + PROVIDER_KEY + " relay=" + RELAY_TOKEN}}], PROVIDER_KEY: [RELAY_TOKEN], "unicode": "保持中文"}
        type(self).response = relay.UpstreamResponse(200, json.dumps(data).encode())
        status, actual, _ = self.request()
        self.assertEqual(status, 200)
        self.assertNotIn(PROVIDER_KEY, json.dumps(actual))
        self.assertNotIn(RELAY_TOKEN, json.dumps(actual))
        self.assertEqual(actual["unicode"], "保持中文")
        self.assertEqual(actual["[REDACTED]"], ["[REDACTED]"])

    def test_provider_error_status_is_preserved_and_redacted(self):
        type(self).response = relay.UpstreamResponse(429, json.dumps({"error": {"message": PROVIDER_KEY + RELAY_TOKEN, "code": "rate_limit_exceeded"}}).encode())
        status, data, _ = self.request()
        self.assertEqual(status, 429)
        self.assertEqual(data["error"]["message"], "[REDACTED][REDACTED]")
        self.assertEqual(data["error"]["code"], "rate_limit_exceeded")

    def test_upstream_errors_do_not_echo_exceptions(self):
        for failure, expected, code in ((RuntimeError(PROVIDER_KEY + RELAY_TOKEN), 502, "upstream_unavailable"), (relay.UpstreamFailure("upstream_timeout"), 504, "upstream_timeout")):
            with self.subTest(expected=expected):
                type(self).response = failure
                status, data, _ = self.request()
                self.assertEqual(status, expected)
                self.assertEqual(data["error"]["code"], code)
                self.assertNotIn(PROVIDER_KEY, json.dumps(data))
                self.assertNotIn(RELAY_TOKEN, json.dumps(data))

    def test_malformed_provider_response_is_not_reflected(self):
        for body in ((PROVIDER_KEY + RELAY_TOKEN).encode(), b"[]", b'{"data":NaN}'):
            with self.subTest(body=body):
                type(self).response = relay.UpstreamResponse(200, body)
                status, data, _ = self.request()
                self.assertEqual(status, 502)
                self.assertNotIn(PROVIDER_KEY, json.dumps(data))
                self.assertNotIn(RELAY_TOKEN, json.dumps(data))

    def test_provider_informational_and_redirect_statuses_are_not_forwarded(self):
        for status in (100, 302, 307):
            with self.subTest(status=status):
                type(self).response = relay.UpstreamResponse(status, b"{}")
                actual_status, data, _ = self.request()
                self.assertEqual(actual_status, 502)
                self.assertEqual(data["error"]["code"], "invalid_upstream_response")

    def test_two_concurrent_calls_are_allowed_third_is_rejected(self):
        type(self).block = True
        results = []
        workers = [threading.Thread(target=lambda: results.append(self.request()[0])) for _ in range(2)]
        try:
            for worker in workers:
                worker.start()
            self.assertTrue(self.started.acquire(timeout=3))
            self.assertTrue(self.started.acquire(timeout=3))
            status, data, _ = self.request()
            self.assertEqual(status, 429)
            self.assertEqual(data["error"]["code"], "relay_busy")
            self.assertEqual(len(self.calls), 2)
        finally:
            self.release.set()
            for worker in workers:
                worker.join(5)
        self.assertEqual(sorted(results), [200, 200])
        # Slots are released, including after upstream errors.
        type(self).block = False
        type(self).response = RuntimeError("private")
        self.assertEqual(self.request()[0], 502)
        type(self).response = relay.UpstreamResponse(200, b"{}")
        self.assertEqual(self.request()[0], 200)

    def test_explicit_overload_retries_then_preserves_success_and_request(self):
        overload = relay.UpstreamResponse(529, b'{"type":"error","error":{"type":"overloaded_error","message":"cluster busy","http_code":529}}')
        success = relay.UpstreamResponse(200, b'{"id":"after-overload","choices":[{"message":{"role":"assistant","content":"Recovered"}}]}')
        transport = Mock(side_effect=[overload, success])
        payload = {"model": relay.DEFAULT_MODEL, "messages": [{"role": "user", "content": "Same prompt"}], "max_tokens": 128}
        with patch.object(self.server, "transport", transport), patch("time.sleep") as sleep:
            status, data, _ = self.request(payload=payload)
        self.assertEqual(status, 200)
        self.assertEqual(data, json.loads(success.body))
        self.assertEqual(transport.call_count, 2)
        self.assertEqual(sleep.call_args_list, [unittest.mock.call(2)])
        for call in transport.call_args_list:
            self.assertEqual(call.args, (payload, PROVIDER_KEY, 600))

    def test_overload_exhaustion_returns_third_original_error(self):
        responses = [relay.UpstreamResponse(529, json.dumps({"type": "error", "error": {"type": "overloaded_error", "message": "busy attempt " + str(attempt), "http_code": 529}}).encode()) for attempt in range(1, 4)]
        transport = Mock(side_effect=responses)
        with patch.object(self.server, "transport", transport), patch("time.sleep") as sleep:
            status, data, _ = self.request()
        self.assertEqual(status, 529)
        self.assertEqual(data, json.loads(responses[-1].body))
        self.assertEqual(transport.call_count, 3)
        self.assertEqual(sleep.call_args_list, [unittest.mock.call(2), unittest.mock.call(5)])

    def test_only_explicit_http529_overloaded_error_is_retryable(self):
        overloaded_body = b'{"error":{"type":"overloaded_error","message":"busy"}}'
        cases = [(relay.UpstreamResponse(status, overloaded_body), status) for status in (200, 401, 429, 500, 503)]
        cases += [
            (relay.UpstreamResponse(529, b'{"error":{"type":"other_error"}}'), 529),
            (relay.UpstreamResponse(529, b'{"error":{"message":"overloaded_error"}}'), 529),
            (relay.UpstreamResponse(529, b'{"type":"overloaded_error"}'), 529),
            (relay.UpstreamResponse(529, b'{"error":{"type":"Overloaded_Error"}}'), 529),
            (relay.UpstreamResponse(529, b'not-json'), 502),
            (relay.UpstreamResponse(529, b'{"error":{"type":"overloaded_error"},"invalid":NaN}'), 502),
            (relay.UpstreamFailure("upstream_timeout"), 504),
            (RuntimeError("network failure"), 502),
        ]
        for response, expected_status in cases:
            with self.subTest(response=response):
                transport = Mock(side_effect=response) if isinstance(response, Exception) else Mock(return_value=response)
                with patch.object(self.server, "transport", transport), patch("time.sleep") as sleep:
                    status, _, _ = self.request()
                self.assertEqual(status, expected_status)
                self.assertEqual(transport.call_count, 1)
                sleep.assert_not_called()

    def test_overload_backoff_keeps_both_upstream_slots_occupied(self):
        started = threading.Semaphore(0)
        release = threading.Event()
        attempts = {}
        calls = []
        lock = threading.Lock()

        def transport(payload, provider_key, timeout):
            thread_id = threading.get_ident()
            with lock:
                attempt = attempts.get(thread_id, 0) + 1
                attempts[thread_id] = attempt
                calls.append((payload, provider_key, timeout))
            if attempt == 1:
                return relay.UpstreamResponse(529, b'{"error":{"type":"overloaded_error"}}')
            return relay.UpstreamResponse(200, b'{"choices":[]}')

        def hold_backoff(seconds):
            self.assertEqual(seconds, 2)
            started.release()
            release.wait(5)

        results = []
        workers = [threading.Thread(target=lambda: results.append(self.request()[0])) for _ in range(2)]
        with patch.object(self.server, "transport", transport), patch("time.sleep", side_effect=hold_backoff) as sleep:
            try:
                for worker in workers:
                    worker.start()
                self.assertTrue(started.acquire(timeout=3))
                self.assertTrue(started.acquire(timeout=3))
                status, data, _ = self.request()
                self.assertEqual(status, 429)
                self.assertEqual(data["error"]["code"], "relay_busy")
                self.assertEqual(len(calls), 2)
            finally:
                release.set()
                for worker in workers:
                    worker.join(5)
        self.assertEqual(sorted(results), [200, 200])
        self.assertEqual(len(calls), 4)
        self.assertEqual(sorted(attempts.values()), [2, 2])
        self.assertEqual(sleep.call_count, 2)
        self.assertTrue(all(key == PROVIDER_KEY and timeout == 600 for _, key, timeout in calls))
        self.assertTrue(all(payload == calls[0][0] for payload, _, _ in calls))


class RelayAdmissionTests(unittest.TestCase):
    def test_incomplete_headers_and_body_cannot_exceed_handler_limit_and_recover(self):
        class AdmissionProbeServer(relay.MiniMaxRelayServer):
            def __init__(self):
                self.condition = threading.Condition()
                self.active = 0
                self.started = 0
                self.peak = 0
                super().__init__(("127.0.0.1", 0), PROVIDER_KEY, RELAY_TOKEN, relay.DEFAULT_MODEL, lambda *_: relay.UpstreamResponse(200, b"{}"))

            def process_request_thread(self, request, client_address):
                with self.condition:
                    self.active += 1
                    self.started += 1
                    self.peak = max(self.peak, self.active)
                    self.condition.notify_all()
                try:
                    super().process_request_thread(request, client_address)
                finally:
                    with self.condition:
                        self.active -= 1
                        self.condition.notify_all()

        server = AdmissionProbeServer()
        errors = Mock()
        server.handle_error = errors
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        sockets = []
        try:
            address = server.server_address
            # Four clients stop in header parsing, four after authentication in
            # a body read. These use the actual HTTP handler, not a semaphore mock.
            for index in range(relay.MAX_HANDLER_THREADS):
                client = socket.create_connection(address, timeout=3)
                sockets.append(client)
                if index % 2:
                    incomplete = (
                        "POST /v1/chat/completions HTTP/1.1\r\n"
                        "Content-Type: application/json\r\nContent-Length: 100\r\n"
                        "Authorization: Bearer " + RELAY_TOKEN + "\r\n\r\n{"
                    ).encode()
                else:
                    incomplete = b"GET /health HTTP/1.1\r\nX-Incomplete: "
                client.sendall(incomplete)
            with server.condition:
                self.assertTrue(server.condition.wait_for(lambda: server.active == relay.MAX_HANDLER_THREADS, timeout=3))
            with socket.create_connection(address, timeout=3) as extra:
                try:
                    extra.sendall(b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    result = extra.recv(1024)
                except (ConnectionResetError, ConnectionAbortedError):
                    result = b""
                self.assertEqual(result, b"")
            self.assertEqual(server.started, relay.MAX_HANDLER_THREADS)
            self.assertEqual(server.peak, relay.MAX_HANDLER_THREADS)
            for client in sockets:
                client.shutdown(socket.SHUT_RDWR)
                client.close()
            sockets.clear()
            with server.condition:
                self.assertTrue(server.condition.wait_for(lambda: server.active == 0, timeout=3))
            connection = http.client.HTTPConnection(*address, timeout=3)
            connection.request("GET", "/v1/models", headers={"Authorization": "Bearer " + RELAY_TOKEN})
            response = connection.getresponse()
            self.assertEqual(response.status, 200)
            self.assertEqual(json.loads(response.read())["data"][0]["id"], relay.DEFAULT_MODEL)
            connection.close()
            with server.condition:
                self.assertTrue(server.condition.wait_for(lambda: server.active == 0, timeout=3))
            self.assertEqual(server.started, relay.MAX_HANDLER_THREADS + 1)
            errors.assert_not_called()
        finally:
            for client in sockets:
                try:
                    client.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                client.close()
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_aborted_response_header_and_body_writes_do_not_raise(self):
        for fail_headers in (True, False):
            with self.subTest(fail_headers=fail_headers):
                handler = relay.MiniMaxRelayHandler.__new__(relay.MiniMaxRelayHandler)
                handler.server = SimpleNamespace(provider_key=PROVIDER_KEY, access_token=RELAY_TOKEN)
                handler.send_response = Mock()
                handler.send_header = Mock()
                handler.end_headers = Mock(side_effect=OSError("disconnected") if fail_headers else None)
                handler.wfile = SimpleNamespace(write=Mock(side_effect=OSError("disconnected")))
                handler._json(200, {"status": "ok"})
                self.assertTrue(handler.close_connection)
                if fail_headers:
                    handler.wfile.write.assert_not_called()
                else:
                    handler.wfile.write.assert_called_once()


class RelayTransportTests(unittest.TestCase):
    def test_fixed_official_url_provider_auth_and_no_header_forwarding(self):
        captured = []

        class Opener:
            def open(self, request, timeout):
                captured.append((request, timeout))
                return FakeResponse(200, b'{"choices":[]}')

        with patch.object(relay.urllib.request, "build_opener", return_value=Opener()) as factory:
            response = relay.send_upstream({"messages": [{"role": "user", "content": "Hi"}]}, PROVIDER_KEY, 600)
        request, timeout = captured[0]
        self.assertEqual(request.full_url, "https://api.minimax.cn/v1/chat/completions")
        self.assertEqual(request.get_header("Authorization"), "Bearer " + PROVIDER_KEY)
        self.assertEqual(timeout, 600)
        self.assertIsInstance(factory.call_args.args[0], relay._NoRedirect)
        self.assertEqual(response, relay.UpstreamResponse(200, b'{"choices":[]}'))
        self.assertEqual(set(response.__dataclass_fields__), {"status", "body"})

    def test_upstream_redirect_is_refused_without_retry(self):
        class Opener:
            calls = 0

            def open(self, request, timeout):
                self.calls += 1
                raise urllib.error.HTTPError(request.full_url, 302, "redirect", {"Location": "https://attacker.example"}, io.BytesIO(b"redirect"))

        opener = Opener()
        with patch.object(relay.urllib.request, "build_opener", return_value=opener):
            with self.assertRaisesRegex(relay.UpstreamFailure, "upstream_redirect_refused"):
                relay.send_upstream({}, PROVIDER_KEY, 600)
        self.assertEqual(opener.calls, 1)
        self.assertIsNone(relay._NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://attacker.example"))

    def test_provider_http_error_body_and_status_preserved(self):
        class Opener:
            def open(self, request, timeout):
                raise urllib.error.HTTPError(request.full_url, 401, "private", {}, io.BytesIO(b'{"error":{"code":"invalid_api_key"}}'))

        with patch.object(relay.urllib.request, "build_opener", return_value=Opener()):
            response = relay.send_upstream({}, PROVIDER_KEY, 600)
        self.assertEqual(response.status, 401)
        self.assertEqual(json.loads(response.body)["error"]["code"], "invalid_api_key")

    def test_upstream_network_error_is_generic_and_not_retried(self):
        class Opener:
            calls = 0

            def open(self, request, timeout):
                self.calls += 1
                raise urllib.error.URLError(PROVIDER_KEY)

        opener = Opener()
        with patch.object(relay.urllib.request, "build_opener", return_value=opener):
            with self.assertRaisesRegex(relay.UpstreamFailure, "upstream_unavailable"):
                relay.send_upstream({}, PROVIDER_KEY, 600)
        self.assertEqual(opener.calls, 1)

    def test_upstream_response_size_is_bounded(self):
        class Opener:
            def open(self, request, timeout):
                return FakeResponse(200, b"x" * (relay.MAX_RESPONSE_BYTES + 1))

        with patch.object(relay.urllib.request, "build_opener", return_value=Opener()):
            with self.assertRaisesRegex(relay.UpstreamFailure, "upstream_response_too_large"):
                relay.send_upstream({}, PROVIDER_KEY, 600)


class EnvironmentTests(unittest.TestCase):
    def test_machine_environment_has_priority(self):
        with patch.object(relay, "_registry_environment", side_effect=lambda name, machine: "machine-value" if machine else "user-value"), patch.dict(os.environ, {"TEST_RELAY_ENV": "process-value"}):
            self.assertEqual(relay.read_environment("TEST_RELAY_ENV"), "machine-value")

    def test_process_fallback_then_user_and_missing(self):
        with patch.object(relay, "_registry_environment", side_effect=lambda name, machine: None if machine else "user-value"), patch.dict(os.environ, {"TEST_RELAY_ENV": "process-value"}):
            self.assertEqual(relay.read_environment("TEST_RELAY_ENV"), "process-value")
        with patch.object(relay, "_registry_environment", side_effect=lambda name, machine: None if machine else "user-value"), patch.dict(os.environ, {"TEST_RELAY_ENV": ""}):
            self.assertEqual(relay.read_environment("TEST_RELAY_ENV"), "user-value")
        with patch.object(relay, "_registry_environment", return_value=None), patch.dict(os.environ, {"TEST_RELAY_ENV": ""}):
            self.assertIsNone(relay.read_environment("TEST_RELAY_ENV"))

    def test_empty_or_shared_credentials_cannot_start(self):
        for provider, token in (("", RELAY_TOKEN), (PROVIDER_KEY, " "), (PROVIDER_KEY, PROVIDER_KEY)):
            with self.subTest(provider=provider, token=token):
                with self.assertRaises(ValueError):
                    relay.create_server("127.0.0.1", 0, provider, token)


if __name__ == "__main__":
    unittest.main()
