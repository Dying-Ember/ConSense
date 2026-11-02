import http.client
import io
import json
import socket
import threading
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from qwen_protocol_stub import CONTENT, MAX_FIXTURE_BYTES, MAX_REQUEST_BYTES, MODEL, STUB_ID, StubHandler, StubServer, load_fixture, main


class QwenProtocolStubTest(unittest.TestCase):
    def test_fixture_options_require_full_names(self):
        for option in ("--fixture-f", "--ordinary-fixture-f", "--stream-fixture-f"):
            with self.subTest(option=option), patch("sys.argv", ["stub", "--port", "12345", option, "fixture.json"]), \
                 patch("sys.stderr", new_callable=io.StringIO), patch("qwen_protocol_stub.load_fixture") as load:
                with self.assertRaises(SystemExit) as error:
                    main()
                self.assertEqual(error.exception.code, 2)
                load.assert_not_called()

    def test_full_fixture_options_remain_supported(self):
        argv = ["stub", "--port", "12345", "--fixture-file", "structured.json",
                "--ordinary-fixture-file", "ordinary.json", "--stream-fixture-file", "stream.json"]
        with patch("sys.argv", argv), patch("qwen_protocol_stub.load_fixture", side_effect=["structured", "ordinary", "stream"]) as load, \
             patch("qwen_protocol_stub.StubServer") as server:
            main()
        self.assertEqual([call.args[0] for call in load.call_args_list], ["structured.json", "ordinary.json", "stream.json"])
        self.assertEqual(server.call_args.args[-3:], ("structured", "ordinary", "stream"))
        server.return_value.__enter__.return_value.serve_forever.assert_called_once_with()

    @classmethod
    def setUpClass(cls):
        cls.server = StubServer(("127.0.0.1", 0), "TEST_ONLY_TOKEN")
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=5)
        cls.server.server_close()
        if cls.thread.is_alive():
            raise AssertionError("stub server thread did not stop")

    def call(self, method, path, body=None, token="TEST_ONLY_TOKEN", headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            actual_headers = {"Authorization": "Bearer " + token, **(headers or {})}
            conn.request(method, path, body=body, headers=actual_headers)
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def request(self, **updates):
        body = {"model": MODEL, "enable_thinking": False, "n": 1, "max_tokens": 64,
                "stream": False, "messages": [{"role": "user", "content": "fixture"}]}
        body.update(updates)
        return json.dumps(body).encode()

    def test_model_discovery_is_explicitly_marked_and_authenticated(self):
        status, headers, raw = self.call("GET", "/v1/models")
        self.assertEqual(status, 200)
        self.assertEqual(headers["X-ConSense-Provider"], STUB_ID)
        self.assertEqual(json.loads(raw)["data"], [{"id": MODEL, "object": "model", "owned_by": STUB_ID}])
        self.assertEqual(self.call("GET", "/v1/models", token="bad")[0], 401)

    def test_preflight_has_client_compatible_but_synthetic_usage(self):
        status, _, raw = self.call("POST", "/v1/chat/completions", self.request(max_tokens=1))
        result = json.loads(raw)
        self.assertEqual(status, 200)
        self.assertEqual(result["object"], "chat.completion")
        self.assertEqual(result["model"], MODEL)
        self.assertEqual(result["choices"], [{"index": 0, "message": {"role": "assistant", "content": "!"}, "finish_reason": "length"}])
        self.assertEqual(result["usage"], {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2})
        self.assertTrue(result["consense_protocol_stub"] and result["synthetic_token_counts"])

    def test_stream_fixture_does_not_change_nonstream_or_preflight(self):
        self.server.content = '{"records":[]}'
        self.server.ordinary_content = '{"nodes":[]}'
        self.server.stream_content = '{"sections":[]}'
        try:
            for is_json, streaming, preflight, expected in (
                (True, False, False, self.server.content),
                (True, True, False, self.server.stream_content),
                (False, False, False, self.server.ordinary_content),
                (False, True, False, self.server.ordinary_content),
                (True, False, True, '!'), (True, True, True, '!'),
            ):
                options = {"stream": streaming, "max_tokens": 1 if preflight else 64}
                if is_json:
                    options["response_format"] = {"type": "json_object"}
                status, _, raw = self.call("POST", "/v1/chat/completions", self.request(**options))
                self.assertEqual(status, 200)
                if streaming:
                    frames = [json.loads(frame[6:]) for frame in raw.decode().split("\n\n")[:-2]]
                    self.assertTrue(all(frame["consense_protocol_stub"] for frame in frames))
                    actual = "".join(frame["choices"][0]["delta"].get("content", "") for frame in frames)
                else:
                    reply = json.loads(raw)
                    self.assertTrue(reply["consense_protocol_stub"])
                    actual = reply["choices"][0]["message"]["content"]
                self.assertEqual(actual, expected)
        finally:
            self.server.content = CONTENT
            self.server.ordinary_content = None
            self.server.stream_content = None

    def test_responses_signal_the_http10_connection_will_close(self):
        for method, path, body, token in (
            ("GET", "/v1/models", None, "TEST_ONLY_TOKEN"),
            ("POST", "/v1/chat/completions", self.request(), "TEST_ONLY_TOKEN"),
            ("POST", "/v1/chat/completions", self.request(stream=True), "TEST_ONLY_TOKEN"),
            ("GET", "/v1/models", None, "bad"),
        ):
            with self.subTest(method=method, path=path, token=token, body=body):
                _, headers, _ = self.call(method, path, body, token)
                self.assertEqual(headers.get("Connection"), "close")

    def test_generation_and_stream_return_marked_fixed_response(self):
        status, _, raw = self.call("POST", "/v1/chat/completions", self.request())
        self.assertEqual(status, 200)
        reply = json.loads(raw)
        self.assertEqual(reply["choices"][0]["message"]["content"], CONTENT)
        self.assertTrue(json.loads(CONTENT)["protocol_stub"])
        status, headers, raw = self.call("POST", "/v1/chat/completions", self.request(stream=True))
        self.assertEqual(status, 200)
        self.assertTrue(headers["Content-Type"].startswith("text/event-stream"))
        frames = raw.decode().split("\n\n")
        self.assertEqual(frames[-2], "data: [DONE]")
        events = [json.loads(frame[6:]) for frame in frames[:-2]]
        self.assertGreaterEqual(len(events), 3)
        self.assertTrue(all(event["model"] == MODEL and event["consense_protocol_stub"] for event in events))
        self.assertEqual("".join(e["choices"][0]["delta"].get("content", "") for e in events), CONTENT)
        self.assertEqual(events[-1]["choices"][0]["finish_reason"], "stop")

    def test_stream_with_one_token_reports_incomplete(self):
        status, _, raw = self.call("POST", "/v1/chat/completions", self.request(stream=True, max_tokens=1))
        self.assertEqual(status, 200)
        events = [json.loads(frame[6:]) for frame in raw.decode().split("\n\n")[:-2]]
        self.assertEqual(events[-1]["choices"][0]["finish_reason"], "length")

    def test_stream_tolerates_client_connection_abort(self):
        class AbortedWriter:
            def write(self, value):
                raise ConnectionAbortedError("client disconnected")

        handler = SimpleNamespace(
            headers={"Content-Length": str(len(self.request(stream=True)))},
            rfile=io.BytesIO(self.request(stream=True)), server=SimpleNamespace(content=CONTENT),
            path="/v1/chat/completions", _authorized=lambda: True,
            send_response=lambda *args: None, send_header=lambda *args: None,
            end_headers=lambda: None, wfile=AbortedWriter())
        StubHandler.do_POST(handler)

    def test_explicit_fixture_supplies_same_synthetic_json_for_plain_and_stream(self):
        try:
            for fixture in (b'{"records":[]}', b'{"nodes":[]}'):
                with patch("builtins.open", return_value=io.BytesIO(fixture)):
                    self.server.content = load_fixture("explicit-local-fixture.json")
                status, headers, raw = self.call("POST", "/v1/chat/completions", self.request())
                reply = json.loads(raw)
                self.assertEqual(status, 200)
                self.assertEqual(headers["X-ConSense-Provider"], STUB_ID)
                self.assertTrue(reply["consense_protocol_stub"] and reply["synthetic_token_counts"])
                self.assertEqual(reply["choices"][0]["message"]["content"].encode(), fixture)
                status, _, raw = self.call("POST", "/v1/chat/completions", self.request(stream=True))
                self.assertEqual(status, 200)
                events = [json.loads(frame[6:]) for frame in raw.decode().split("\n\n")[:-2]]
                self.assertTrue(all(e["consense_protocol_stub"] and e["synthetic_token_counts"] for e in events))
                content = "".join(e["choices"][0]["delta"].get("content", "") for e in events)
                self.assertEqual(content.encode(), fixture)
                _, _, raw = self.call("POST", "/v1/chat/completions", self.request(max_tokens=1))
                self.assertEqual(json.loads(raw)["choices"][0]["message"]["content"], "!")
        finally:
            self.server.content = CONTENT

    def test_optional_ordinary_fixture_routes_by_response_format(self):
        self.server.content = '{"records":[{"id":"structured"}]}'
        self.server.ordinary_content = '{"nodes":[{"id":"ordinary"}]}'
        try:
            for response_format, expected in ((None, self.server.ordinary_content),
                                              ({"type": "json_object"}, self.server.content)):
                options = {} if response_format is None else {"response_format": response_format}
                for stream in (False, True):
                    status, _, raw = self.call("POST", "/v1/chat/completions", self.request(stream=stream, **options))
                    self.assertEqual(status, 200)
                    if stream:
                        frames = [json.loads(frame[6:]) for frame in raw.decode().split("\n\n")[:-2]]
                        content = "".join(frame["choices"][0]["delta"].get("content", "") for frame in frames)
                    else:
                        content = json.loads(raw)["choices"][0]["message"]["content"]
                    self.assertEqual(content, expected)
                _, _, raw = self.call("POST", "/v1/chat/completions", self.request(max_tokens=1, **options))
                self.assertEqual(json.loads(raw)["choices"][0]["message"]["content"], "!")
        finally:
            self.server.content = CONTENT
            self.server.ordinary_content = None

    def test_explicit_advice_stream_delays_after_complete_opening_section(self):
        fixture='{"sections":[{"kind":"limits","content":"opening"},{"kind":"analysis","content":"'+('x'*2000)+'"}]}'
        self.server.stream_content=fixture
        conn=http.client.HTTPConnection("127.0.0.1",self.server.server_port,timeout=5)
        try:
            conn.request("POST","/v1/chat/completions",body=self.request(stream=True,response_format={"type":"json_object"}),
                         headers={"Authorization":"Bearer TEST_ONLY_TOKEN"})
            response=conn.getresponse();self.assertEqual(response.status,200)
            first=response.readline().decode();self.assertEqual(response.readline(),b"\n")
            opening=json.loads(first[6:])["choices"][0]["delta"]["content"]
            first_end=fixture.index('},{"kind":"analysis"')
            self.assertGreater(len(opening)-first_end,256)
            self.assertFalse(opening.endswith('}]}'))
            started=time.monotonic();remaining=response.read().decode();self.assertGreaterEqual(time.monotonic()-started,.7)
            frames=[json.loads(frame[6:]) for frame in remaining.split("\n\n") if frame and frame!="data: [DONE]"]
            content=opening+"".join(frame["choices"][0]["delta"].get("content","") for frame in frames)
            self.assertEqual(content,fixture)
        finally:
            conn.close();self.server.stream_content=None

    def test_fixture_rejects_invalid_non_object_duplicate_and_oversize_input(self):
        for raw in (b"", b"[]", b"null", b"{broken", b"\xff", b'{"a":1,"a":2}',
                    b'{"nested":{"a":1,"a":2}}', b'{"x":NaN}', b"x" * (MAX_FIXTURE_BYTES + 1)):
            with self.subTest(raw=raw[:30]), patch("builtins.open", return_value=io.BytesIO(raw)):
                with self.assertRaises(ValueError):
                    load_fixture("explicit-local-fixture.json")

    def test_truncated_request_body_is_rejected_even_when_prefix_is_valid_json(self):
        body = self.request()
        for claimed, expected in ((len(body), 200), (len(body) + 1, 400)):
            responses = []
            handler = SimpleNamespace(
                headers={"Content-Length": str(claimed)}, rfile=io.BytesIO(body),
                server=SimpleNamespace(content=CONTENT), path="/v1/chat/completions",
                _authorized=lambda: True, _reply=lambda status, data: responses.append(status))
            StubHandler.do_POST(handler)
            self.assertEqual(responses, [expected])

    def test_short_http_body_returns_400_and_server_accepts_next_request(self):
        body = self.request()
        with socket.create_connection(("127.0.0.1", self.server.server_port), timeout=5) as conn:
            conn.sendall(b"POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n"
                         b"Authorization: Bearer TEST_ONLY_TOKEN\r\nContent-Length: "
                         + str(len(body) + 1).encode() + b"\r\n\r\n" + body)
            conn.shutdown(socket.SHUT_WR)
            response = b""
            while True:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                response += chunk
        self.assertTrue(response.startswith(b"HTTP/1.0 400 "))
        self.assertEqual(self.call("GET", "/v1/models")[0], 200)

    def test_invalid_requests_fail_closed_without_echo(self):
        for body in (self.request(model="wrong"), self.request(enable_thinking=True),
                     self.request(max_tokens=True), self.request(n=True), self.request(messages=[]), b"{broken"):
            status, _, raw = self.call("POST", "/v1/chat/completions", body)
            self.assertEqual(status, 400)
            self.assertNotIn(b"fixture", raw)
        status, _, _ = self.call("POST", "/v1/chat/completions", b"x", headers={"Content-Length": str(MAX_REQUEST_BYTES + 1)})
        self.assertEqual(status, 400)
        self.assertEqual(self.call("POST", "/v1/chat/completions", self.request(), token="bad")[0], 401)
        self.assertEqual(self.call("GET", "/v1/unknown")[0], 404)

    def test_server_does_not_allow_public_bind_or_empty_token(self):
        with self.assertRaises(ValueError):
            StubServer(("0.0.0.0", 0), "test")
        with self.assertRaises(ValueError):
            StubServer(("127.0.0.1", 0), "")
        with self.assertRaises(ValueError):
            StubServer(("localhost", 0), "test", allow_non_loopback=True)

    def test_non_loopback_bind_requires_explicit_opt_in(self):
        with StubServer(("0.0.0.0", 0), "test", allow_non_loopback=True) as server:
            self.assertEqual(server.server_address[0], "0.0.0.0")


if __name__ == "__main__":
    unittest.main()
