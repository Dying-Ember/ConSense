"""Bounded client for model operations on the fixed GPU executor."""
import base64
import hashlib
import http.client
import ipaddress
import io
import json
import math
import os
from pathlib import Path
import re
import selectors
import socket
import stat
import struct
import threading
import time
from urllib.parse import urlsplit

from delivery import require
from request_stage import MAX_BODY, encoded, verify_response


class OperationError(ValueError):
    def __init__(self, message, operation, receipt):
        super().__init__(message)
        self.operation, self.exit_receipt = operation, receipt


class AdmissionCancelled(ValueError):
    def __init__(self, operation):
        super().__init__('GPU admission cancelled after complete response')
        self.operation = operation


class AdmissionCleanupError(AdmissionCancelled):
    def __init__(self, operation):
        ValueError.__init__(self, 'GPU admission cleanup failed after complete response')
        self.operation = operation


def gpu_release_proven(release, *, owned_tree=False):
    return (isinstance(release, dict) and release.get('status') == 'released'
            and type(release.get('cgroupPopulated')) is int and release['cgroupPopulated'] == 0
            and ((isinstance(release.get('observedGpuPids'), list) and bool(release['observedGpuPids'])
                  and all(type(pid) is int and pid > 0 for pid in release['observedGpuPids']))
                 or (owned_tree and set(release) == {'status', 'cgroupPopulated', 'ownership', 'processWaited'}
                     and release['ownership'] == 'delegated_cgroup_v2' and release['processWaited'] is True)))


def cpu_business_release_proven(release):
    return (isinstance(release, dict) and set(release) == {'status', 'cgroupPopulated'}
            and release['status'] == 'released_cpu_only'
            and type(release['cgroupPopulated']) is int and release['cgroupPopulated'] == 0)


class UnixConnection(http.client.HTTPConnection):
    def __init__(self, path, timeout):
        super().__init__('localhost', timeout=timeout)
        self.path = path

    def connect(self):
        deadline = time.monotonic() + self.timeout
        parent = Path(self.path).parent
        require(str(parent.resolve()) == str(parent), 'GPU socket parent cannot be a symlink')
        parent_info = parent.stat()
        require(stat.S_ISDIR(parent_info.st_mode) and parent_info.st_uid == os.geteuid()
                and not parent_info.st_mode & 0o077, 'private owned GPU socket directory required')
        info = os.lstat(self.path)
        require(stat.S_ISSOCK(info.st_mode) and info.st_uid == os.geteuid() and not info.st_mode & 0o077,
                'private owned GPU socket required')
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise TimeoutError('GPU executor HTTP deadline elapsed')
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise TimeoutError('GPU executor HTTP deadline elapsed')
        self.sock.settimeout(remaining)
        self.sock.connect(self.path)
        _, uid, gid = struct.unpack('3i', self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        require(uid == os.geteuid() and gid == os.getegid(), 'GPU socket peer owner differs')


class _ResponseReader(io.RawIOBase):
    def __init__(self, sock, deadline, cancelled, preserve_ready=False):
        self.sock = None
        self.selector = None
        try:
            self.sock = sock.dup()
            self.sock.setblocking(False)
            self.selector = selectors.DefaultSelector()
            self.selector.register(self.sock, selectors.EVENT_READ)
        except BaseException:
            try:
                if self.selector is not None: self.selector.close()
            finally:
                try:
                    if self.sock is not None: self.sock.close()
                finally: super().close()
            raise
        self.deadline, self.cancelled, self.preserve_ready = deadline, cancelled, preserve_ready

    def readable(self):
        return True

    def readinto(self, buffer):
        while True:
            require(self.cancelled is None or not self.cancelled(), 'GPU request cancelled')
            remaining = self.deadline - time.monotonic()
            if remaining <= 0: raise TimeoutError('GPU executor HTTP deadline elapsed')
            if self.selector.select(min(remaining, .05)):
                require(self.cancelled is None or not self.cancelled(), 'GPU request cancelled')
                try: size = self.sock.recv_into(buffer)
                except BlockingIOError: continue
                require(self.preserve_ready and size > 0 or self.cancelled is None or not self.cancelled(), 'GPU request cancelled')
                if time.monotonic() >= self.deadline: raise TimeoutError('GPU executor HTTP deadline elapsed')
                return size

    def close(self):
        if not self.closed:
            try: self.selector.close()
            finally:
                try: self.sock.close()
                finally: super().close()


class _ResponseSocket:
    def __init__(self, sock, deadline, cancelled, preserve_ready):
        self.sock, self.deadline, self.cancelled, self.preserve_ready = sock, deadline, cancelled, preserve_ready

    def makefile(self, mode):
        return io.BufferedReader(_ResponseReader(self.sock, self.deadline, self.cancelled, self.preserve_ready))


def _response_factory(deadline, cancelled, preserve_ready=False):
    def response(sock, *args, **kwargs):
        return http.client.HTTPResponse(_ResponseSocket(sock, deadline, cancelled, preserve_ready), *args, **kwargs)
    return response


class Client:
    def __init__(self, gateway_url, token, profile_sha256s, cpu_business_profile=None):
        url = urlsplit(gateway_url)
        local = (url.scheme == 'http' and url.hostname and ipaddress.ip_address(url.hostname).is_loopback
                 and url.port and url.path in ('', '/') and not url.username and not url.password)
        unix = (os.name == 'posix' and url.scheme == 'unix' and not url.netloc and Path(url.path).is_absolute()
                and str(Path(url.path)) == url.path and not Path(url.path).is_symlink())
        require((local or unix) and not url.query and not url.fragment, 'literal loopback or private Unix GPU gateway required')
        require(isinstance(token, str) and re.fullmatch(r'[A-Za-z0-9._~-]{1,512}', token), 'private GPU token required')
        require(isinstance(profile_sha256s, dict) and profile_sha256s and all(
            stage in ('document-embedding', 'query-embedding-rerank', 'fixed-reference-query', 'business-llm')
            and isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) for stage, value in profile_sha256s.items()),
            'independently pinned GPU stage profiles required')
        self.url, self.token, self.profile_sha256s = url, token, dict(profile_sha256s)
        if cpu_business_profile is not None:
            require(isinstance(cpu_business_profile, dict)
                    and cpu_business_profile.get('adapter') == 'consense.native-chat.v1'
                    and cpu_business_profile.get('providerKind') == 'cpu-protocol-stub'
                    and hashlib.sha256(encoded(cpu_business_profile)).hexdigest() == self.profile_sha256s.get('business-llm'),
                    'CPU stub profile must be independently pinned')
        self.cpu_business_profile_sha256 = (self.profile_sha256s['business-llm']
                                            if cpu_business_profile is not None else None)

    def _release_proven(self, stage, profile_sha256, release, *, owned_tree=False):
        return gpu_release_proven(release, owned_tree=owned_tree and stage in ('document-embedding', 'query-embedding-rerank', 'fixed-reference-query')) or (stage == 'business-llm'
                and self.cpu_business_profile_sha256 is not None
                and profile_sha256 == self.cpu_business_profile_sha256
                and cpu_business_release_proven(release))

    def _identity(self, stage, binding, raw):
        require(stage in self.profile_sha256s, 'GPU stage profile is not independently pinned')
        return {'bodySha256':hashlib.sha256(raw).hexdigest(), 'sourceSha256':hashlib.sha256(encoded(binding)).hexdigest(),
                'profileSha256':self.profile_sha256s[stage]}

    @staticmethod
    def _matches(state, expected):
        return all(state.get(key) == value for key, value in expected.items())

    def _connection(self, timeout):
        return (UnixConnection(self.url.path, timeout) if self.url.scheme == 'unix'
                else http.client.HTTPConnection(self.url.hostname, self.url.port, timeout=timeout))

    def _call(self, method, path, timeout, body=None, cancelled=None):
        deadline = time.monotonic() + timeout
        admission = method == 'POST' and path == '/operations'
        def remaining(check_cancel=True):
            require(not check_cancel or cancelled is None or not cancelled(), 'GPU request cancelled')
            value = deadline - time.monotonic()
            if value <= 0: raise TimeoutError('GPU executor HTTP deadline elapsed')
            return value
        connection = self._connection(remaining())
        connection.response_class = _response_factory(deadline, cancelled, admission)
        done = threading.Event()
        peer = [None]
        response = None
        original_error = None
        known_operation = None
        def watch():
            while not done.wait(.05):
                if cancelled is not None and cancelled():
                    try:
                        if peer[0] is not None: peer[0].shutdown(socket.SHUT_RDWR)
                    except OSError: pass
                    connection.close(); return
        watcher = threading.Thread(target=watch, daemon=True) if cancelled is not None else None
        watcher_started = False
        try:
            if watcher:
                watcher.start()
                watcher_started = True
            connection.timeout = remaining()
            connection.connect()
            peer[0] = connection.sock
            require(cancelled is None or not cancelled(), 'GPU request cancelled before dispatch')
            data = encoded(body) if body is not None else None
            timeout = remaining()
            if peer[0] is not None: peer[0].settimeout(timeout)
            connection.request(method, path, body=data, headers={'Authorization':'Bearer '+self.token, 'Content-Type':'application/json'})
            timeout = remaining()
            if peer[0] is not None: peer[0].settimeout(timeout)
            response = connection.getresponse()
            remaining(not admission)
            length = response.getheader('Content-Length', '')
            require(length.isdigit() and int(length) <= 2 * MAX_BODY, 'bounded GPU response length required')
            chunks, size = [], 0
            while True:
                if response.isclosed(): break
                remaining(not admission)
                chunk = response.read1(min(65536, 2 * MAX_BODY + 1 - size))
                remaining(not admission)
                if not chunk: break
                chunks.append(chunk); size += len(chunk)
                require(size <= 2 * MAX_BODY, 'GPU executor response exceeds bound')
                if admission and cancelled is not None and cancelled() and not response.isclosed():
                    raise ValueError('GPU admission cancelled before complete response')
            require(not response.length, 'GPU executor response truncated')
            require(200 <= response.status < 300, f'GPU executor HTTP status {response.status}')
            raw = b''.join(chunks)
            if admission: require(len(raw) == int(length), 'GPU admission response length differs')
            value = json.loads(raw)
            require(isinstance(value, dict), 'GPU executor response must be an object')
            if admission:
                operation = value.get('operationId')
                if isinstance(operation, str) and re.fullmatch('[a-f0-9]{32}', operation):
                    known_operation = operation
            remaining(not admission)
            if admission and cancelled is not None and cancelled():
                if known_operation is not None: raise AdmissionCancelled(known_operation)
                raise ValueError('GPU admission cancelled without valid identity')
            return value
        except BaseException as error:
            original_error = error
            raise
        finally:
            done.set()
            try:
                try:
                    if watcher_started: watcher.join()
                finally:
                    try:
                        if response is not None: response.close()
                    finally: connection.close()
            except BaseException as cleanup_error:
                if original_error is not None: raise original_error from cleanup_error
                if known_operation is not None: raise AdmissionCleanupError(known_operation) from cleanup_error
                raise

    def compute(self, stage, binding, body, timeout, cancelled=None):
        require(stage in ('document-embedding', 'query-embedding-rerank') and isinstance(binding, dict) and binding
                and isinstance(body, dict), 'bound model computation required')
        require(type(timeout) in (int, float) and math.isfinite(timeout) and 0 < timeout <= 86400, 'finite GPU deadline required')
        deadline = time.monotonic() + timeout
        response, receipt = self._execute(stage, binding, encoded(body), timeout, cancelled, deadline)
        require(response['status'] == 200, 'GPU model computation failed')
        result = json.loads(base64.b64decode(response['bodyBase64'], validate=True))
        require(isinstance(result, dict) and '_operationReceipt' not in result, 'GPU result must be an object without reserved fields')
        return {**result, '_operationReceipt':receipt}

    def call_business(self, path, raw_body, timeout, cancelled=None):
        require(path in ('/v1/chat/completions', '/v1/responses', '/v1/responses/input_tokens')
                and isinstance(raw_body, bytes), 'exact business wire required')
        response, receipt = self._execute('business-llm', {'httpPath':path}, raw_body, timeout, cancelled)
        return response['status'], response['headers'], base64.b64decode(response['bodyBase64'], validate=True), receipt

    def _execute(self, stage, binding, raw, timeout, cancelled=None, deadline=None):
        require(type(timeout) in (int, float) and math.isfinite(timeout) and 0 < timeout <= 86400, 'finite GPU deadline required')
        require(len(raw) <= MAX_BODY, 'GPU input exceeds bound')
        if deadline is None: deadline = time.monotonic() + timeout
        source_bytes = encoded(binding)
        binding = json.loads(source_bytes)
        require(encoded(binding) == source_bytes, 'GPU source identity changes under JSON encoding')
        expected = self._identity(stage, binding, raw)
        def remaining(limit=1.0):
            value = deadline - time.monotonic()
            if value <= 0: raise TimeoutError('GPU operation deadline elapsed')
            return min(value, limit) if limit is not None else value
        require(cancelled is None or not cancelled(), 'GPU operation cancelled before admission')
        try:
            body = {'stage':stage, 'binding':binding, 'bodyBase64':base64.b64encode(raw).decode(),
                    'expectedProfileSha256':expected['profileSha256'], 'expectedMode':'job'}
            started = self._call('POST', '/operations', remaining(None), body, cancelled=cancelled)
        except AdmissionCancelled as error:
            raise self._cancelled_admission(error, expected) from error
        except Exception as error:
            raise OperationError('GPU admission response is unknown: ' + str(error), None,
                                 {'status':'release_unknown', 'operationId':None}) from error
        operation = started.get('operationId')
        if not isinstance(operation, str) or not re.fullmatch('[a-f0-9]{32}', operation):
            raise OperationError('GPU admission response has no valid operation identity', None,
                                 {'status':'release_unknown', 'operationId':None})
        binding_sha = None
        try:
            while True:
                require(cancelled is None or not cancelled(), 'GPU operation cancelled')
                state = self._call('GET', '/operations/'+operation, remaining(), cancelled=cancelled)
                digest = state.get('bindingSha256')
                require(state.get('operationId') == operation and state.get('stage') == stage
                        and self._matches(state, expected)
                        and isinstance(digest, str) and re.fullmatch('[a-f0-9]{64}', digest)
                        and binding_sha in (None, digest), 'GPU operation identity changed')
                binding_sha = digest
                if state.get('status') == 'completed' or (stage == 'business-llm' and state.get('status') == 'failed'
                                                          and state.get('responseSha256')):
                    release = state.get('release', {})
                    require(self._release_proven(stage, expected['profileSha256'], release)
                            and isinstance(state.get('responseSha256'), str)
                            and re.fullmatch('[a-f0-9]{64}', state['responseSha256']), 'GPU release or persisted response not proven')
                    break
                require(state.get('status') in ('starting', 'running', 'settling'), 'GPU operation failed or release is unknown')
                time.sleep(min(.05, remaining()))
            response = self._call('GET', '/operations/'+operation+'/result', remaining(None), cancelled=cancelled)
            require(cancelled is None or not cancelled(), 'GPU operation cancelled before result acceptance')
            verify_response(response, {'bindingSha256':binding_sha})
            require(hashlib.sha256(encoded(response)).hexdigest() == state['responseSha256'], 'GPU persisted response digest differs')
            require((state['status'] == 'completed' and 200 <= response['status'] < 300)
                    or (stage == 'business-llm' and state['status'] == 'failed' and not 200 <= response['status'] < 300),
                    'GPU result status differs from operation state')
            return response, {'operationId':operation, 'bindingSha256':binding_sha,
                              'responseSha256':state['responseSha256'], 'release':release}
        except Exception as error:
            raise OperationError(str(error), operation, self.cancel_and_observe(operation, expected)) from error

    def cancel_and_observe(self, operation, expected, *, model_stage=None):
        receipt = {'status':'release_unknown', 'operationId':operation}
        try: self._call('DELETE', '/operations/'+operation, 1.0)
        except (OSError, ValueError, http.client.HTTPException): pass
        until = time.monotonic() + 2.0
        while time.monotonic() < until:
            try:
                state = self._call('GET', '/operations/'+operation, min(0.25, until-time.monotonic()))
                if state.get('operationId') == operation and self._matches(state, expected) and state.get('status') in ('completed', 'failed', 'cancelled', 'expired', 'release_unknown'):
                    release = state.get('release', {'status':'release_unknown'})
                    if not self._release_proven(state.get('stage'), expected['profileSha256'], release,
                                                owned_tree=model_stage is not None and state.get('stage') == model_stage):
                        release = {'status':'release_unknown'}
                    return {**release,
                            'operationStatus':state['status'], 'operationId':operation}
            except (OSError, ValueError, http.client.HTTPException): pass
            time.sleep(.05)
        return receipt

    def _cancelled_admission(self, error, expected, *, model_stage=None):
        try:
            receipt = (self.cancel_and_observe(error.operation, expected) if model_stage is None else
                       self.cancel_and_observe(error.operation, expected, model_stage=model_stage))
        except Exception: receipt = {'status':'release_unknown', 'operationId':error.operation}
        return OperationError(str(error), error.operation, receipt)

    def start_business(self, path, raw_body, timeout, cancelled=None):
        require(path in ('/v1/chat/completions', '/v1/responses', '/v1/responses/input_tokens')
                and isinstance(raw_body, bytes) and len(raw_body) <= MAX_BODY, 'exact bounded business request required')
        require(type(timeout) in (int, float) and math.isfinite(timeout) and 0 < timeout <= 86400,
                'finite business deadline required')
        deadline = time.monotonic() + timeout
        require(cancelled is None or not cancelled(), 'GPU operation cancelled before admission')
        expected = self._identity('business-llm', {'httpPath':path}, raw_body)
        try:
            body = {'stage':'business-llm', 'binding':{'httpPath':path}, 'bodyBase64':base64.b64encode(raw_body).decode(),
                    'expectedProfileSha256':expected['profileSha256'], 'expectedMode':'job'}
            remaining = deadline - time.monotonic()
            if remaining <= 0: raise TimeoutError('GPU business deadline elapsed')
            reply = self._call('POST', '/operations', remaining, body, cancelled=cancelled)
        except AdmissionCancelled as error:
            raise self._cancelled_admission(error, expected) from error
        except Exception as error:
            raise OperationError('GPU admission response is unknown: ' + str(error), None,
                                 {'status':'release_unknown', 'operationId':None}) from error
        operation = reply.get('operationId')
        if not isinstance(operation, str) or not re.fullmatch('[a-f0-9]{32}', operation):
            raise OperationError('GPU admission response has no valid operation identity', None,
                                 {'status':'release_unknown', 'operationId':None})
        if cancelled is not None and cancelled():
            error = AdmissionCancelled(operation)
            raise self._cancelled_admission(error, expected) from error
        return BusinessStream(self, operation, deadline, expected, cancelled)


class BusinessStream:
    def __init__(self, client, operation, deadline, expected, cancelled=None):
        self.client, self.operation = client, operation
        self.deadline = deadline
        self.expected = expected
        self.connection = self.response = self.socket = None
        self.finished = False
        self.closed = False
        self.exit_receipt = {'status':'release_unknown'}
        self.binding_sha = None
        self.cancelled = cancelled
        self.stop_watching = threading.Event()
        self.watcher = None

    def _remaining(self):
        value = self.deadline - time.monotonic()
        if value <= 0: raise TimeoutError('GPU business deadline elapsed')
        return value

    def open(self):
        try:
            state = self.client._call('GET', '/operations/'+self.operation, min(self._remaining(), 1.0), cancelled=self.cancelled)
            binding = state.get('bindingSha256')
            require(state.get('operationId') == self.operation and state.get('stage') == 'business-llm'
                    and self.client._matches(state, self.expected)
                    and isinstance(binding, str) and re.fullmatch('[a-f0-9]{64}', binding), 'business operation identity differs')
            self.binding_sha = binding
            self.connection = self.client._connection(self._remaining())
            self.connection.response_class = _response_factory(self.deadline, self.cancelled)
            self.connection.timeout = self._remaining()
            self.connection.connect()
            self.socket = self.connection.sock
            if self.cancelled is not None:
                def watch():
                    while not self.stop_watching.wait(.05):
                        if self.cancelled():
                            try:
                                if self.socket is not None: self.socket.shutdown(socket.SHUT_RDWR)
                            except OSError: pass
                            return
                self.watcher = threading.Thread(target=watch, daemon=True); self.watcher.start()
            require(self.cancelled is None or not self.cancelled(), 'GPU business operation cancelled before stream')
            timeout = self._remaining()
            if self.socket is not None: self.socket.settimeout(timeout)
            self.connection.request('GET', '/operations/'+self.operation+'/stream',
                                    headers={'Authorization':'Bearer '+self.client.token})
            timeout = self._remaining()
            if self.socket is not None: self.socket.settimeout(timeout)
            self.response = self.connection.getresponse()
            self._remaining()
            require(self.cancelled is None or not self.cancelled(), 'GPU business operation cancelled')
            return self.response.status, {'content-type':self.response.getheader('content-type', 'application/octet-stream')}
        except BaseException as error:
            try: self.close()
            except BaseException as cleanup_error: raise error from cleanup_error
            raise

    def read(self):
        original_error = None
        try:
            require(self.response is not None, 'business stream is not open')
            from fixed_gpu_worker import success_event
            count = 0; pending = withheld = b''; terminal = False; stream_digest = hashlib.sha256()
            sse = self.response.getheader('content-type', '').lower().split(';', 1)[0].strip() == 'text/event-stream'
            success_status = 200 <= self.response.status < 300
            while True:
                if self.response.isclosed(): break
                require(self.cancelled is None or not self.cancelled(), 'GPU business operation cancelled')
                self._remaining()
                chunk = self.response.read1(65536)
                self._remaining()
                require(self.cancelled is None or not self.cancelled(), 'GPU business operation cancelled')
                if not chunk: break
                count += len(chunk); require(count <= MAX_BODY, 'business stream exceeds byte limit')
                stream_digest.update(chunk)
                if not success_status:
                    withheld += chunk
                    continue
                if not sse:
                    require(self.cancelled is None or not self.cancelled(), 'GPU business operation cancelled')
                    yield chunk
                    continue
                pending += chunk
                while True:
                    boundary = re.search(b'\r\n\r\n|\n\n|\r\r', pending)
                    if not boundary: break
                    frame, pending = pending[:boundary.end()], pending[boundary.end():]
                    terminal = terminal or success_event(frame)
                    if terminal: withheld += frame
                    else:
                        require(self.cancelled is None or not self.cancelled(), 'GPU business operation cancelled')
                        yield frame
            require(self.cancelled is None or not self.cancelled(), 'GPU business operation cancelled')
            state = self.client._call('GET', '/operations/'+self.operation, min(self._remaining(), 1.0), cancelled=self.cancelled)
            require(state.get('operationId') == self.operation and state.get('stage') == 'business-llm'
                    and self.client._matches(state, self.expected)
                    and state.get('bindingSha256') == self.binding_sha
                    and state.get('status') == ('completed' if success_status else 'failed')
                    and self.client._release_proven('business-llm', self.expected['profileSha256'], state.get('release')),
                    'business operation failed or release is unknown')
            require(not success_status or sse and terminal and not pending, 'business stream terminal is incomplete')
            response = self.client._call('GET', '/operations/'+self.operation+'/result', self._remaining(), cancelled=self.cancelled)
            verify_response(response, {'bindingSha256':self.binding_sha})
            require(isinstance(state.get('responseSha256'), str)
                    and hashlib.sha256(encoded(response)).hexdigest() == state['responseSha256']
                    and response['status'] == self.response.status
                    and hashlib.sha256(base64.b64decode(response['bodyBase64'], validate=True)).digest() == stream_digest.digest(),
                    'business persisted response differs from streamed bytes')
            require(self.cancelled is None or not self.cancelled(), 'GPU business operation cancelled before result acceptance')
            self.exit_receipt = {**state['release'], 'operationStatus':state['status'], 'operationId':self.operation}
            if withheld:
                require(self.cancelled is None or not self.cancelled(), 'GPU business operation cancelled')
                self.finished = True
                yield withheld
            else: self.finished = True
        except BaseException as error:
            original_error = error
            raise
        finally:
            try: self.close()
            except BaseException as cleanup_error:
                if original_error is not None and not isinstance(original_error, GeneratorExit):
                    raise original_error from cleanup_error
                raise

    def close(self):
        if self.closed: return
        self.closed = True
        self.stop_watching.set()
        try:
            if self.watcher is not None: self.watcher.join()
        finally:
            try:
                response, self.response = self.response, None
                if response is not None: response.close()
            finally:
                try:
                    connection, self.connection = self.connection, None
                    if connection is not None: connection.close()
                finally:
                    if not self.finished:
                        self.exit_receipt = {'status':'release_unknown', 'operationId':self.operation}
                        self.exit_receipt = self.client.cancel_and_observe(self.operation, self.expected)
