"""One bound OCR transaction on the fixed, independently owned GPU service."""
from contextlib import contextmanager
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import stat
import struct
import threading
import time
from urllib.parse import urlsplit

from delivery import require
from fixed_gpu_client import gpu_release_proven
from request_stage import encoded, save


def configuration(provider):
    boot = provider['ownedFixed']
    require('gatewayUrl' not in boot, 'fixed GPU mode requires a Unix socket, not a TCP gateway')
    require(isinstance(boot.get('gatewayProfileSha256'), str)
            and re.fullmatch('[a-f0-9]{64}', boot['gatewayProfileSha256']),
            'fixed GPU gateway profile SHA256 pin required')
    require(os.name != 'nt', 'fixed GPU token permissions require a POSIX runtime')
    socket_path = Path(boot.get('socketPath', ''))
    require(socket_path.is_absolute() and socket_path.parent.is_dir()
            and socket_path.parent.resolve() == socket_path.parent and not socket_path.is_symlink(),
            'fixed GPU gateway requires an absolute protected Unix socket path')
    parent = socket_path.parent.stat()
    require(parent.st_uid == os.geteuid() and not parent.st_mode & 0o077,
            'fixed GPU socket directory must be private and owned by this process')
    endpoint = urlsplit(provider['baseUrl']); stage = boot.get('stage')
    require(stage in ('paddle', 'mineru') and endpoint.scheme == 'http'
            and endpoint.hostname == '127.0.0.1' and endpoint.port == (28771 if stage == 'paddle' else 28772)
            and not any((endpoint.username, endpoint.password, endpoint.query, endpoint.fragment)),
            'fixed OCR provider must use its assigned loopback endpoint')
    from native_provider_lifecycle import _finite
    _finite(boot.get('readyTimeoutSeconds'), 'readyTimeoutSeconds')
    _finite(boot.get('cleanupTimeoutSeconds'), 'cleanupTimeoutSeconds', 2)
    token_path = Path(boot.get('tokenFile', ''))
    require(token_path.is_absolute() and token_path.is_file() and not token_path.is_symlink(), 'private gateway token file required')
    info = token_path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and not info.st_mode & 0o077,
            'gateway token file must be private and owned by this process')
    return boot, socket_path


class UnixConnection(http.client.HTTPConnection):
    def __init__(self, path, timeout):
        super().__init__('localhost', timeout=timeout); self.path = Path(path)

    def connect(self):
        info = self.path.lstat()
        require(stat.S_ISSOCK(info.st_mode) and info.st_uid == os.geteuid() and not info.st_mode & 0o077,
                'fixed GPU socket must be private and owned by this process')
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            self.sock.settimeout(self.timeout); self.sock.connect(str(self.path))
            _, uid, gid = struct.unpack('3i', self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize('3i')))
            require(uid == os.geteuid() and gid == os.getegid(), 'fixed GPU socket peer identity differs')
        except BaseException:
            self.close(); raise


def _token(boot):
    descriptor = os.open(boot['tokenFile'], os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and not info.st_mode & 0o077,
                'gateway token file is not private')
        token = stream.read(513).decode('utf-8').strip()
    require(re.fullmatch(r'[A-Za-z0-9._~-]{1,512}', token) is not None, 'invalid gateway token')
    return token


def proxy_request(provider, lifecycle, url, *, dispatch_id, body=None, timeout, method=None, cancelled=None, content_type=None):
    from complete_ocr import _request
    boot, socket_path = configuration(provider)
    operation = lifecycle.get('operationId')
    require(isinstance(dispatch_id, str) and re.fullmatch('[a-f0-9]{32}', dispatch_id), 'valid provider dispatch ID required')
    require(lifecycle.get('providerEvidenceProtocol') == 'dispatch-v1', 'provider evidence protocol unavailable')
    require(lifecycle.get('mode') == 'owned_fixed_gpu' and isinstance(operation, str)
            and re.fullmatch('[a-f0-9]{32}', operation), 'owned fixed GPU operation required')
    base = urlsplit(provider['baseUrl']); target = urlsplit(url)
    require((target.scheme, target.hostname, target.port) == (base.scheme, base.hostname, base.port)
            and not any((target.username, target.password, target.query, target.fragment))
            and (not base.path or target.path.startswith(base.path.rstrip('/')+'/')),
            'provider request must remain inside its configured endpoint')
    proxy_url = 'http://localhost/operations/' + operation + '/provider' + target.path
    return _request(proxy_url, body=body, timeout=timeout, method=method, socket_path=socket_path,
                    token=_token(boot), cancelled=cancelled, dispatch_id=dispatch_id, content_type=content_type)


@contextmanager
def owned_fixed_provider(provider, remaining_seconds, receipt_dir, *, cancelled=None):
    from native_provider_lifecycle import ProviderLifecycleError, _finite
    from native_session_worker import verify_transaction
    deadline = time.monotonic() + _finite(remaining_seconds, 'remaining_seconds')
    boot, socket_path = configuration(provider); task = provider.get('taskBinding', {})
    gateway_profile_sha = boot['gatewayProfileSha256']
    require(task.get('parser') == ('paddleocr-vl' if boot['stage'] == 'paddle' else 'mineru')
            and task.get('profileSha256') == hashlib.sha256(encoded(provider['identity'])).hexdigest(),
            'fixed provider task parser or model profile differs')
    task = json.loads(encoded(task))
    task_sha = hashlib.sha256(encoded(task)).hexdigest()
    token = _token(boot)
    directory = Path(receipt_dir).resolve(); directory.mkdir(parents=True, exist_ok=False)
    store_root = directory.parents[2]
    lifecycle = {'schema':'consense.native-provider-lifecycle.v1', 'mode':'owned_fixed_gpu',
                 'taskBinding':task, 'modelReady':False, 'targetAccepted':False}
    operation = None; binding_sha = None; stop_watch = threading.Event(); watcher = None

    def call(method, path, body=None, timeout=1, cancel_pending=None):
        connection = UnixConnection(socket_path, timeout)
        expired = threading.Event(); aborted = threading.Event(); finished = threading.Event(); sockets = []
        def interrupt():
            for stream in sockets or ([connection.sock] if connection.sock is not None else []):
                try:
                    if stream is not None: stream.shutdown(socket.SHUT_RDWR)
                except OSError: pass
        def stop():
            expired.set(); interrupt()
        def watch_pending():
            while not finished.wait(.05):
                if cancel_pending():
                    aborted.set(); interrupt(); return
        timer = threading.Timer(timeout, stop); timer.daemon = True; timer.start()
        pending_watcher = None
        if cancel_pending is not None:
            pending_watcher = threading.Thread(target=watch_pending, daemon=True); pending_watcher.start()
        try:
            data = encoded(body) if body is not None else None
            connection.request(method, path, body=data, headers={'Authorization':'Bearer '+token, 'Content-Type':'application/json'})
            sockets.append(connection.sock)
            if aborted.is_set(): raise ProviderLifecycleError('fixed GPU admission cancelled; outcome unknown')
            response = connection.getresponse(); raw = response.read(65537)
            if aborted.is_set(): raise ProviderLifecycleError('fixed GPU admission cancelled; outcome unknown')
            if expired.is_set(): raise TimeoutError('fixed GPU gateway request deadline elapsed')
            require(len(raw) <= 65536 and type(response.length) is int and response.length == 0
                    and 200 <= response.status < 300, 'fixed GPU gateway response incomplete or failed')
            value = json.loads(raw); require(isinstance(value, dict), 'fixed GPU gateway response must be an object')
            return value
        except Exception as error:
            if aborted.is_set():
                raise ProviderLifecycleError('fixed GPU admission cancelled; outcome unknown') from error
            if expired.is_set():
                raise TimeoutError('fixed GPU gateway request deadline elapsed') from error
            raise
        finally:
            finished.set()
            if pending_watcher is not None: pending_watcher.join()
            timer.cancel()
            connection.close()

    def status(end=deadline):
        nonlocal binding_sha
        value = call('GET', '/operations/'+operation, timeout=min(1., max(.01, end-time.monotonic())))
        require(value.get('operationId') == operation and value.get('stage') == boot['stage'], 'fixed GPU operation identity changed')
        require(value.get('providerEvidenceProtocol') == 'dispatch-v1', 'provider evidence protocol unavailable')
        require(value.get('bodySha256') == hashlib.sha256(b'{}').hexdigest()
                and value.get('sourceSha256') == task_sha
                and value.get('profileSha256') == gateway_profile_sha,
                'fixed GPU operation input or gateway profile differs')
        current = value.get('bindingSha256')
        require(isinstance(current, str) and re.fullmatch('[a-f0-9]{64}', current), 'fixed GPU operation binding missing')
        require(binding_sha in (None, current), 'fixed GPU operation binding changed')
        binding_sha = current
        return value

    def cancel():
        if operation:
            try: call('DELETE', '/operations/'+operation, timeout=boot['cleanupTimeoutSeconds'])
            except Exception: pass

    def watch():
        while not stop_watch.wait(.05):
            if time.monotonic() >= deadline or (cancelled is not None and cancelled()):
                cancel(); return

    failed = True
    try:
        require(time.monotonic() < deadline and not (cancelled is not None and cancelled()), 'fixed GPU admission cancelled or expired')
        reply = call('POST', '/operations', {'stage':boot['stage'], 'binding':task, 'bodyBase64':'e30=',
                                            'expectedProfileSha256':gateway_profile_sha, 'expectedMode':'session'},
                     timeout=max(.01, deadline-time.monotonic()), cancel_pending=cancelled)
        admitted_id = reply.get('operationId')
        require(isinstance(admitted_id, str) and re.fullmatch('[a-f0-9]{32}', admitted_id), 'fixed GPU operation identity missing')
        operation = admitted_id
        lifecycle['operationId'] = operation; save(directory/'start.json', lifecycle)
        watcher = threading.Thread(target=watch, daemon=True); watcher.start()
        ready_deadline = min(deadline, time.monotonic()+boot['readyTimeoutSeconds'])
        while time.monotonic() < ready_deadline:
            require(not (cancelled is not None and cancelled()), 'fixed GPU caller cancelled')
            value = status(ready_deadline)
            if value.get('status') == 'ready': break
            require(value.get('status') in ('starting', 'running'), 'fixed GPU session failed before readiness')
            time.sleep(.05)
        else: raise TimeoutError('fixed GPU readiness deadline elapsed')
        lifecycle['operationBindingSha256'] = binding_sha
        lifecycle['providerEvidenceProtocol'] = 'dispatch-v1'
        lifecycle['wireReady'] = {'status':'wire_ready_model_readiness_unverified'}
        yield lifecycle
        require(time.monotonic() < deadline and not (cancelled is not None and cancelled()), 'fixed GPU caller cancelled or expired')
        verify_transaction(lifecycle.get('transaction', {}), task, store_root)
        call('POST', '/operations/'+operation+'/finish', {'transaction':lifecycle['transaction']},
             timeout=max(.01, deadline-time.monotonic()))
        while time.monotonic() < deadline:
            require(not (cancelled is not None and cancelled()), 'fixed GPU caller cancelled')
            value = status()
            if value.get('status') == 'completed':
                require(time.monotonic() < deadline and not (cancelled is not None and cancelled()),
                        'fixed GPU caller cancelled or expired before release confirmation')
                transaction_sha = hashlib.sha256(encoded(lifecycle['transaction'])).hexdigest()
                require(value.get('transactionSha256') == transaction_sha, 'fixed GPU persisted transaction identity differs')
                release = value.get('release', {})
                require(gpu_release_proven(release, owned_tree=True), 'fixed GPU release not proven')
                lifecycle['exitReceipt'] = {**release, 'operationId':operation, 'transactionStatus':'persisted',
                                            'transactionSha256':transaction_sha}
                failed = False; break
            require(value.get('status') in ('finishing', 'settling', 'running', 'ready'), 'fixed GPU transaction or release failed')
            time.sleep(.05)
        if failed: raise TimeoutError('fixed GPU release deadline elapsed')
    finally:
        stop_watch.set()
        if watcher is not None: watcher.join()
        if failed:
            cancel()
            lifecycle['exitReceipt'] = {'status':'release_unknown', 'operationId':operation, 'transactionStatus':'failed'}
        save(directory/'exit.json', lifecycle['exitReceipt'])

