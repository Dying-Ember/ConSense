#!/usr/bin/env python3
"""Small authenticated, non-streaming MiniMax relay using only Python stdlib.

Provider credentials stay in this process. Clients use a separate relay token.
The official upstream is fixed; arbitrary URLs and upstream redirects are refused.
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import socket
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable
from urllib.parse import urlsplit


UPSTREAM_URL = "https://api.minimax.cn/v1/chat/completions"
MAX_BODY_BYTES = 2 * 1024 * 1024
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_OUTPUT_TOKENS = 16384
MAX_HANDLER_THREADS = 8
UPSTREAM_TIMEOUT_SECONDS = 600
OVERLOAD_RETRY_DELAYS = (2, 5)
DEFAULT_MODEL = "MiniMax-M3"


def _registry_environment(name: str, machine: bool) -> str | None:
    """Read Windows environment storage without logging its contents."""
    try:
        import winreg

        root = winreg.HKEY_LOCAL_MACHINE if machine else winreg.HKEY_CURRENT_USER
        path = (
            r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment"
            if machine
            else "Environment"
        )
        with winreg.OpenKey(root, path) as key:
            value, kind = winreg.QueryValueEx(key, name)
        if kind in (winreg.REG_SZ, winreg.REG_EXPAND_SZ) and isinstance(value, str):
            # Do not expand secrets containing percent signs as variable references.
            return value if value.strip() else None
    except (ImportError, OSError):
        pass
    return None


def read_environment(name: str) -> str | None:
    """Machine environment first, then this process, then the Windows user."""
    machine_value = _registry_environment(name, machine=True)
    if machine_value:
        return machine_value
    process_value = os.environ.get(name)
    if process_value and process_value.strip():
        return process_value
    return _registry_environment(name, machine=False)


@dataclass(frozen=True)
class UpstreamResponse:
    status: int
    body: bytes


class UpstreamFailure(Exception):
    """A failure with a fixed public code; raw exception details stay private."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def send_upstream(payload: dict, provider_key: str, timeout: int) -> UpstreamResponse:
    """One request, no retries, and no provider headers returned to the client."""
    request = urllib.request.Request(
        UPSTREAM_URL,
        data=json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + provider_key,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    opener = urllib.request.build_opener(_NoRedirect())
    try:
        try:
            response = opener.open(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.getcode()
            if 300 <= status < 400:
                raise UpstreamFailure("upstream_redirect_refused")
            body = response.read(MAX_RESPONSE_BYTES + 1)
            if len(body) > MAX_RESPONSE_BYTES:
                raise UpstreamFailure("upstream_response_too_large")
            return UpstreamResponse(status, body)
    except (TimeoutError, socket.timeout):
        raise UpstreamFailure("upstream_timeout") from None
    except urllib.error.URLError as error:
        code = "upstream_timeout" if isinstance(error.reason, (TimeoutError, socket.timeout)) else "upstream_unavailable"
        raise UpstreamFailure(code) from None


def _reject_json_constant(value: str):
    raise ValueError("Invalid JSON constant")


def _is_explicit_overload(response: UpstreamResponse) -> bool:
    if not isinstance(response, UpstreamResponse) or response.status != 529:
        return False
    if not isinstance(response.body, bytes) or len(response.body) > MAX_RESPONSE_BYTES:
        return False
    try:
        data = json.loads(response.body.decode("utf-8"), parse_constant=_reject_json_constant)
    except (ValueError, UnicodeError, RecursionError):
        return False
    return (
        isinstance(data, dict)
        and isinstance(data.get("error"), dict)
        and data["error"].get("type") == "overloaded_error"
    )


def request_with_overload_retry(payload: dict, provider_key: str, transport: Callable) -> UpstreamResponse:
    """Retry only explicit cluster overload; caller holds its upstream slot."""
    for attempt in range(len(OVERLOAD_RETRY_DELAYS) + 1):
        response = transport(payload, provider_key, UPSTREAM_TIMEOUT_SECONDS)
        if not _is_explicit_overload(response) or attempt == len(OVERLOAD_RETRY_DELAYS):
            return response
        time.sleep(OVERLOAD_RETRY_DELAYS[attempt])


def _redact(value, secrets: tuple[str, ...]):
    if isinstance(value, str):
        for secret in secrets:
            value = value.replace(secret, "[REDACTED]")
        return value
    if isinstance(value, list):
        return [_redact(item, secrets) for item in value]
    if isinstance(value, dict):
        return {_redact(key, secrets): _redact(item, secrets) for key, item in value.items()}
    return value


class MiniMaxRelayServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, provider_key: str, access_token: str, model: str, transport: Callable):
        if not provider_key.strip() or not access_token.strip():
            raise ValueError("Both provider credential and relay token are required")
        if hmac.compare_digest(provider_key.encode(), access_token.encode()):
            raise ValueError("The relay token must differ from the provider credential")
        self.provider_key = provider_key
        self.access_token = access_token
        self.model = model
        self.transport = transport
        self.inflight = threading.BoundedSemaphore(2)
        self.handlers = threading.BoundedSemaphore(MAX_HANDLER_THREADS)
        super().__init__(address, MiniMaxRelayHandler)

    def process_request(self, request, client_address):
        # Admit before creating a handler thread, including before headers/body
        # are read. Slow or incomplete requests cannot spawn unlimited threads.
        if not self.handlers.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.handlers.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.handlers.release()


class MiniMaxRelayHandler(BaseHTTPRequestHandler):
    server: MiniMaxRelayServer
    protocol_version = "HTTP/1.0"
    server_version = "ConSenseMiniMaxRelay"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def log_message(self, format, *args):
        # BaseHTTPRequestHandler logs request targets; never log untrusted paths,
        # prompts, headers, responses, or credentials.
        pass

    def _json(self, status: int, data: dict):
        data = _redact(data, (self.server.provider_key, self.server.access_token))
        encoded = json.dumps(data, ensure_ascii=False, allow_nan=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(encoded)
        except OSError:
            # A client disconnecting during header/body writes is routine.
            pass
        self.close_connection = True

    def _error(self, status: int, message: str, code: str):
        self._json(status, {"error": {"message": message, "type": "relay_error", "code": code}})

    def send_error(self, code, message=None, explain=None):
        # Also replace stdlib parser errors (which can echo request text) with JSON.
        self._error(code, "Invalid HTTP request", "invalid_http_request")

    def _route(self) -> str | None:
        try:
            route = urlsplit(self.path)
        except ValueError:
            self._error(400, "Invalid request target", "invalid_path")
            return None
        if not self.path.startswith("/") or route.scheme or route.netloc or route.query or route.fragment or "?" in self.path or "#" in self.path:
            self._error(400, "Queries and absolute request targets are not supported", "unsupported_path")
            return None
        if route.path not in ("/health", "/v1/models", "/v1/chat/completions"):
            self._error(404, "Endpoint not found", "not_found")
            return None
        return route.path

    def _authenticate(self) -> bool:
        values = self.headers.get_all("Authorization", [])
        parts = values[0].split(" ", 1) if len(values) == 1 else []
        candidate = parts[1] if len(parts) == 2 and parts[0].lower() == "bearer" else ""
        authorized = hmac.compare_digest(candidate.encode("utf-8"), self.server.access_token.encode("utf-8"))
        if not authorized:
            self._error(401, "A valid relay bearer token is required", "invalid_relay_token")
        return authorized

    def do_GET(self):
        route = self._route()
        if route is None:
            return
        if route == "/health":
            self._json(200, {"status": "ok", "service": "consense-minimax-relay", "model": self.server.model})
            return
        if not self._authenticate():
            return
        if route == "/v1/models":
            self._json(200, {"object": "list", "data": [{"id": self.server.model, "object": "model", "created": 0, "owned_by": "minimax"}]})
        else:
            self._error(405, "Use POST for chat completions", "method_not_allowed")

    def do_POST(self):
        route = self._route()
        if route is None or not self._authenticate():
            return
        if route != "/v1/chat/completions":
            self._error(405, "Use GET for this endpoint", "method_not_allowed")
            return
        if self.headers.get("Transfer-Encoding") is not None:
            self._error(400, "Transfer-Encoding is not supported", "unsupported_transfer_encoding")
            return
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1:
            self._error(411 if not lengths else 400, "One Content-Length header is required", "invalid_content_length")
            return
        if not lengths[0].isascii() or not lengths[0].isdigit():
            self._error(400, "Invalid Content-Length", "invalid_content_length")
            return
        try:
            length = int(lengths[0])
        except ValueError:
            self._error(400, "Invalid Content-Length", "invalid_content_length")
            return
        if length > MAX_BODY_BYTES:
            self._error(413, "Request body exceeds 2 MiB", "request_too_large")
            return
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            self._error(415, "Content-Type must be application/json", "unsupported_media_type")
            return
        try:
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError("Incomplete body")
            payload = json.loads(raw.decode("utf-8"), parse_constant=_reject_json_constant)
        except (ValueError, UnicodeError, OSError, RecursionError):
            self._error(400, "Invalid JSON request body", "invalid_json")
            return
        if not isinstance(payload, dict):
            self._error(400, "JSON request body must be an object", "invalid_request")
            return
        if payload.get("model", self.server.model) != self.server.model:
            self._error(400, "Only the configured MiniMax model is available", "unsupported_model")
            return
        if payload.get("stream", False) is not False:
            self._error(400, "Streaming is not supported by this relay", "unsupported_stream")
            return
        if type(payload.get("n", 1)) is not int or payload.get("n", 1) != 1:
            self._error(400, "Only n=1 is supported", "unsupported_n")
            return
        if "max_tokens" in payload and "max_completion_tokens" in payload:
            self._error(400, "Use only one output token budget field", "conflicting_token_budget")
            return
        for field in ("max_tokens", "max_completion_tokens"):
            if field in payload and (type(payload[field]) is not int or not 1 <= payload[field] <= MAX_OUTPUT_TOKENS):
                self._error(400, "Output token budget must be between 1 and 16384", "invalid_token_budget")
                return
        messages = payload.get("messages")
        if not isinstance(messages, list) or not messages or any(not isinstance(message, dict) for message in messages):
            self._error(400, "A non-empty messages array is required", "invalid_messages")
            return
        if any(field in payload for field in ("api_key", "base_url", "upstream", "upstream_url", "headers")):
            self._error(400, "Client upstream configuration is not supported", "unsupported_upstream_configuration")
            return
        payload["model"] = self.server.model
        if "max_tokens" not in payload and "max_completion_tokens" not in payload:
            payload["max_tokens"] = MAX_OUTPUT_TOKENS
        if not self.server.inflight.acquire(blocking=False):
            self._error(429, "The relay is busy; try again later", "relay_busy")
            return
        try:
            try:
                response = request_with_overload_retry(payload, self.server.provider_key, self.server.transport)
                if not isinstance(response, UpstreamResponse) or not 200 <= response.status <= 599 or 300 <= response.status < 400 or len(response.body) > MAX_RESPONSE_BYTES:
                    raise UpstreamFailure("invalid_upstream_response")
                result = json.loads(response.body.decode("utf-8"), parse_constant=_reject_json_constant)
                if not isinstance(result, dict):
                    raise UpstreamFailure("invalid_upstream_response")
                self._json(response.status, result)
            except UpstreamFailure as error:
                if error.code == "upstream_timeout":
                    self._error(504, "MiniMax request timed out", error.code)
                else:
                    self._error(502, "MiniMax request failed", error.code)
            except Exception:
                # Neither HTTP client exceptions nor provider malformed payloads
                # are reflected to callers or stderr; they can contain secrets.
                self._error(502, "MiniMax request failed", "upstream_unavailable")
        finally:
            self.server.inflight.release()

    def do_OPTIONS(self):
        self._error(405, "Cross-origin browser calls are not supported", "method_not_allowed")

    def do_HEAD(self):
        self._error(405, "Use GET or POST", "method_not_allowed")


def create_server(
    host: str,
    port: int,
    provider_key: str,
    access_token: str,
    model: str = DEFAULT_MODEL,
    transport: Callable | None = None,
) -> MiniMaxRelayServer:
    return MiniMaxRelayServer((host, port), provider_key, access_token, model, transport or send_upstream)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8092)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--api-key-env", default="MiniMaxCN-token-plan")
    parser.add_argument("--access-token-env", default="ConSense_MiniMax_Relay_Token")
    args = parser.parse_args()
    provider_key = read_environment(args.api_key_env)
    access_token = read_environment(args.access_token_env)
    if not provider_key or not access_token:
        parser.exit(2, "Missing provider credential or relay access token environment variable.\n")
    try:
        server = create_server(args.host, args.port, provider_key, access_token, args.model)
    except (ValueError, OSError):
        parser.exit(2, "Unable to start relay; check credentials, listen address, and port.\n")
    print(f"MiniMax relay listening on {args.host}:{args.port}; model={args.model}; non-streaming; concurrency=2; handlers={MAX_HANDLER_THREADS}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
