"""Explicit Qwen protocol stand-in; no model weights, inference, or real token counts."""
import argparse
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import ipaddress
import json
import os
import time


MODEL = "qwen3.8-27b"
STUB_ID = "consense-qwen-protocol-stub"
CONTENT = '{"protocol_stub":true,"message":"No model inference; token counts are synthetic."}'
MAX_REQUEST_BYTES = 64 * 1024 * 1024
MAX_FIXTURE_BYTES = 1024 * 1024


def load_fixture(path):
    """Read a bounded, explicit local JSON object; this does not infer business content."""
    with open(path, "rb") as source:
        raw = source.read(MAX_FIXTURE_BYTES + 1)
    if not raw or len(raw) > MAX_FIXTURE_BYTES:
        raise ValueError("fixture must be a nonempty JSON object within the size limit")

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("fixture contains duplicate keys")
            result[key] = value
        return result

    def reject_constant(value):
        raise ValueError("fixture contains a non-finite number")

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                           parse_constant=reject_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as invalid:
        raise ValueError("fixture must be valid UTF-8 JSON") from invalid
    if not isinstance(value, dict):
        raise ValueError("fixture must be a JSON object")
    return raw.decode("utf-8")


class StubServer(HTTPServer):
    def __init__(self, address, token, allow_non_loopback=False, content=CONTENT, ordinary_content=None, stream_content=None):
        try:
            host = ipaddress.IPv4Address(address[0])
        except ipaddress.AddressValueError as invalid:
            raise ValueError("stub bind must be an IPv4 address") from invalid
        if not token or (not host.is_loopback and not allow_non_loopback):
            raise ValueError("stub requires a test token and explicit non-loopback opt-in")
        self.token = token
        self.content = content
        self.ordinary_content = ordinary_content
        self.stream_content = stream_content
        super().__init__(address, StubHandler)


class StubHandler(BaseHTTPRequestHandler):
    server_version = "ConSense-Qwen-Protocol-Stub/1"

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, format, *args):
        pass

    def end_headers(self):
        self.send_header("Connection", "close")
        super().end_headers()

    def _reply(self, status, data):
        body = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-ConSense-Provider", STUB_ID)
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        actual = self.headers.get("Authorization", "")
        expected = "Bearer " + self.server.token
        if not hmac.compare_digest(actual.encode("utf-8"), expected.encode("utf-8")):
            self._reply(401, {"error": "protocol stub authorization failed", "provider": STUB_ID})
            return False
        return True

    def do_GET(self):
        if not self._authorized():
            return
        if self.path != "/v1/models":
            self._reply(404, {"error": "protocol stub endpoint not found", "provider": STUB_ID})
            return
        self._reply(200, {"object": "list", "data": [{"id": MODEL, "object": "model", "owned_by": STUB_ID}]})

    def do_POST(self):
        if not self._authorized():
            return
        if self.path != "/v1/chat/completions":
            self._reply(404, {"error": "protocol stub endpoint not found", "provider": STUB_ID})
            return
        try:
            if self.headers.get("Transfer-Encoding") is not None:
                raise ValueError("unsupported transfer encoding")
            size = int(self.headers.get("Content-Length", ""))
            if size <= 0 or size > MAX_REQUEST_BYTES:
                raise ValueError("request size")
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise ValueError("incomplete request body")
            request = json.loads(raw)
            if not isinstance(request, dict) or request.get("model") != MODEL:
                raise ValueError("model")
            n = request.get("n", 1)
            if request.get("enable_thinking") is not False or type(n) is not int or n != 1:
                raise ValueError("profile")
            maximum = request.get("max_tokens")
            if type(maximum) is not int or maximum < 1 or type(request.get("stream")) is not bool:
                raise ValueError("generation options")
            messages = request.get("messages")
            if not isinstance(messages, list) or not messages or any(
                not isinstance(item, dict) or not isinstance(item.get("role"), str)
                or not isinstance(item.get("content"), str) for item in messages
            ):
                raise ValueError("messages")
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            self._reply(400, {"error": "invalid protocol stub request", "provider": STUB_ID})
            return

        preflight = maximum == 1
        ordinary = getattr(self.server, "ordinary_content", None)
        json_object = isinstance(request.get("response_format"), dict) and request["response_format"].get("type") == "json_object"
        content = "!" if preflight else (ordinary if ordinary is not None and not json_object else self.server.content)
        stream_content = getattr(self.server, "stream_content", None)
        advice_stream = not preflight and json_object and request["stream"] and stream_content is not None
        if advice_stream:
            content = stream_content
        finish = "length" if preflight else "stop"
        usage = {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
        base = {"id": STUB_ID, "model": MODEL, "consense_protocol_stub": True,
                "synthetic_token_counts": True}
        if not request["stream"]:
            self._reply(200, {**base, "object": "chat.completion", "choices": [{
                "index": 0, "message": {"role": "assistant", "content": content},
                "finish_reason": finish}], "usage": usage})
            return

        split = len(content) * 3 // 4 if advice_stream else len(content) // 2
        chunks = (content[:split], content[split:]) if len(content) > 1 else (content,)
        frames = [{**base, "object": "chat.completion.chunk", "choices": [
            {"index": 0, "delta": {"content": part}, "finish_reason": None}]} for part in chunks]
        frames.append({**base, "object": "chat.completion.chunk", "choices": [
            {"index": 0, "delta": {}, "finish_reason": finish}], "usage": usage})
        encoded = [b"data: " + json.dumps(frame, separators=(",", ":")).encode("utf-8") + b"\n\n"
                   for frame in frames] + [b"data: [DONE]\n\n"]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Content-Length", str(sum(map(len, encoded))))
        self.send_header("X-ConSense-Provider", STUB_ID)
        self.end_headers()
        try:
            for index, frame in enumerate(encoded):
                if advice_stream and index == 1:
                    time.sleep(1)
                self.wfile.write(frame)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--bind", default="127.0.0.1", help="IPv4 bind address; default is loopback")
    parser.add_argument("--allow-non-loopback", action="store_true",
                        help="explicitly permit a container network bind; restrict network exposure separately")
    parser.add_argument("--fixture-file", help="explicit local synthetic JSON object for generation; no inference")
    parser.add_argument("--ordinary-fixture-file", help="optional local JSON object for requests without json_object format")
    parser.add_argument("--stream-fixture-file", help="optional synthetic JSON object for streaming json_object requests")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be in 1..65535")
    token = os.environ.get("CONSENSE_QWEN_STUB_TOKEN", "")
    content = load_fixture(args.fixture_file) if args.fixture_file else CONTENT
    ordinary = load_fixture(args.ordinary_fixture_file) if args.ordinary_fixture_file else None
    stream_content = load_fixture(args.stream_fixture_file) if args.stream_fixture_file else None
    with StubServer((args.bind, args.port), token, args.allow_non_loopback, content, ordinary, stream_content) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
