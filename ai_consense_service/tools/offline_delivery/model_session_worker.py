"""Single-model, bounded batch computation; GPU ownership and release are external."""
import base64
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import signal
import socket
import stat
import struct
import sys
import threading
import time
import uuid

from delivery import require
from request_stage import MAX_BODY, encoded, verify_operation

MAX_BATCHES = 10000
MAX_TOTAL_BYTES = 4 * 1024 * 1024 * 1024


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def atomic_new(path, raw):
    """Publish complete bytes without replacing an existing identity."""
    temp = path.with_name('.' + path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temp.open('xb') as stream:
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        os.link(temp, path)
        if os.name == 'posix':
            fd = os.open(path.parent, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
            try: os.fsync(fd)
            finally: os.close(fd)
    finally:
        if temp.exists(): temp.unlink()


def fixed_reference_encode(backend, texts):
    # Preserve the frozen baseline's float32 L2 normalization after model inference.
    import numpy as np
    raw = backend.embedding_model().encode(texts,
        batch_size=backend.MODEL_SETTINGS['embedding']['batchSize'],
        normalize_embeddings=False, convert_to_numpy=True, show_progress_bar=False)
    value = np.asarray(raw, dtype=np.float32)
    norms = np.linalg.norm(value, axis=1, keepdims=True)
    require(np.isfinite(value).all() and np.isfinite(norms).all() and (norms > 0).all(),
            'Embedding must be finite and have positive FP32 norm')
    return value / norms


class ModelSession:
    def __init__(self, operation_raw, directory, *, operation_sha256, profile_sha256,
                 deadline, cancelled, backend, assigned_check=None):
        require(isinstance(operation_raw, bytes) and len(operation_raw) <= 2 * MAX_BODY
                and digest(operation_raw) == operation_sha256, 'assigned operation bytes differ')
        operation = verify_operation(json.loads(operation_raw))
        require(operation['profile'].get('adapter') == 'consense.model-session.v1'
                and re.fullmatch('[a-f0-9]{64}', profile_sha256 or '')
                and operation['profileSha256'] == profile_sha256
                and base64.b64decode(operation['bodyBase64'], validate=True) == b'{}',
                'assigned model session profile or body differs')
        source = operation['source']; kind = source.get('modelKind')
        require(set(source) == {'requestId', 'sourceScopeSha256', 'modelKind', 'modelIdentity', 'runtime',
                                'inferenceCap', 'deadlineBudgetMs', 'maxBatches', 'maxTotalBytes'}
                and isinstance(source['requestId'], str) and re.fullmatch('[a-f0-9]{32}', source['requestId'])
                and isinstance(source['sourceScopeSha256'], str) and re.fullmatch('[a-f0-9]{64}', source['sourceScopeSha256'])
                and kind in ('embedding', 'reranker')
                and ((operation['stage'] == 'document-embedding' and kind == 'embedding')
                     or operation['stage'] in ('query-embedding-rerank', 'fixed-reference-query'))
                and isinstance(source['modelIdentity'], dict) and source['modelIdentity']
                and isinstance(source['runtime'], dict) and source['runtime'].get('device') == 'cuda'
                and type(source['inferenceCap']) is int and 0 < source['inferenceCap'] <= 8192
                and type(source['deadlineBudgetMs']) is int and 0 < source['deadlineBudgetMs'] <= 86400000
                and type(source['maxBatches']) is int and 0 < source['maxBatches'] <= MAX_BATCHES
                and type(source['maxTotalBytes']) is int and 0 < source['maxTotalBytes'] <= MAX_TOTAL_BYTES,
                'invalid assigned model session binding')
        require(type(deadline) in (int, float) and math.isfinite(deadline) and callable(cancelled),
                'finite owned session deadline and cancellation required')
        self.deadline = min(deadline, time.monotonic() + source['deadlineBudgetMs'] / 1000)
        self.cancelled = cancelled
        self.backend, self.runtime, self.operation = backend, backend.window_runtime(), operation
        self.source, self.kind = source, kind
        self.directory = Path(directory)
        self.operation_sha256, self.assigned_check, self.initialized = operation_sha256, assigned_check, False
        self.lock = threading.Lock(); self.failed = False; self.finished = False
        self.receipts = []; self.total_bytes = 0
        self._check(); self._identity()
        self.directory.mkdir(mode=0o700, parents=False, exist_ok=False)
        atomic_new(self.directory / 'session.json', operation_raw)
        self.initialized = True

    def _check(self):
        require(not self.failed and not self.finished and not self.cancelled()
                and time.monotonic() < self.deadline, 'model session cancelled, expired or closed')
        require(self.assigned_check is None or self.assigned_check(), 'assigned operation changed')
        if self.initialized:
            require(digest((self.directory / 'session.json').read_bytes()) == self.operation_sha256,
                    'persisted session binding changed')

    def _identity(self):
        cap = self.runtime.inference_cap(self.kind)
        require(encoded(self.runtime.logical_identity(self.kind)) == encoded(self.source['modelIdentity'])
                and encoded(self.backend.model_runtime(self.kind)) == encoded(self.source['runtime'])
                and type(cap) is int and cap == self.source['inferenceCap'],
                'model identity or runtime differs')

    def _compute(self, value):
        action = value.get('kind')
        if self.kind == 'embedding' and action == 'dimension':
            require(set(value) == {'kind'}, 'dimension request cannot include other fields')
            dimension = int(self.backend.embedding_model().get_sentence_embedding_dimension())
            require(dimension > 0, 'embedding dimension unavailable')
            return {'dimension': dimension}
        if self.kind == 'embedding' and action == 'embedding':
            require(set(value) == {'kind', 'texts'} and isinstance(value['texts'], list) and value['texts']
                    and all(isinstance(text, str) and text for text in value['texts']), 'nonempty embedding texts required')
            vectors = (fixed_reference_encode(self.backend, value['texts']) if self.operation['stage'] == 'fixed-reference-query' else self.backend.encode_embeddings(value['texts'])).tolist()
            dimension = int(self.backend.embedding_model().get_sentence_embedding_dimension())
            require(dimension > 0 and len(vectors) == len(value['texts']) and all(
                len(row) == dimension and all(type(x) in (int, float) and math.isfinite(x) for x in row)
                for row in vectors), 'embedding result differs')
            return {'dimension': dimension, 'vectors': vectors}
        require(self.kind == 'reranker' and action == 'reranker' and set(value) == {'kind', 'pairs'}
                and isinstance(value['pairs'], list) and value['pairs']
                and all(isinstance(pair, list) and len(pair) == 2 and all(isinstance(x, str) and x for x in pair)
                        for pair in value['pairs']), 'nonempty rerank pairs required')
        scores = self.backend.rerank_model().predict(value['pairs'], batch_size=self.backend.MODEL_SETTINGS['reranker']['batchSize'],
            show_progress_bar=False, convert_to_numpy=True).reshape(-1).tolist()
        require(len(scores) == len(value['pairs']) and all(type(x) in (int, float) and math.isfinite(x) for x in scores),
                'rerank result differs')
        return {'scores': scores}

    def submit(self, batch):
        require(self.lock.acquire(blocking=False), 'model session already busy')
        try:
            self._check()
            require(isinstance(batch, dict) and set(batch) == {'ordinal', 'inputSha256', 'bodyBase64'}
                    and type(batch['ordinal']) is int and batch['ordinal'] == len(self.receipts)
                    and batch['ordinal'] < self.source['maxBatches'], 'model batch sequence differs')
            raw = base64.b64decode(batch['bodyBase64'], validate=True)
            require(len(raw) <= MAX_BODY and digest(raw) == batch['inputSha256']
                    and self.total_bytes + len(raw) <= self.source['maxTotalBytes'], 'model batch input or budget differs')
            value = json.loads(raw); require(isinstance(value, dict), 'model batch object required')
            ordinal = batch['ordinal']; stem = f'{ordinal:08d}'
            atomic_new(self.directory / (stem + '.input'), raw)
            self._identity(); self._check()
            result = self._compute(value)
            self._identity(); self._check()
            output = encoded(result)
            require(len(output) <= MAX_BODY and self.total_bytes + len(raw) + len(output) <= self.source['maxTotalBytes'],
                    'model batch output or total budget exceeds bound')
            atomic_new(self.directory / (stem + '.output'), output)
            receipt = {'operationId':self.operation['operationId'], 'bindingSha256':self.operation['bindingSha256'],
                       'ordinal':ordinal, 'inputSha256':digest(raw), 'outputSha256':digest(output)}
            self._check(); atomic_new(self.directory / (stem + '.receipt.json'), encoded(receipt))
            self.receipts.append(receipt); self.total_bytes += len(raw) + len(output)
            self._check()
            return {**receipt, 'result':result, 'provisional':True}
        except BaseException as error:
            self.failed = True
            try: atomic_new(self.directory / 'failure.json', encoded({'errorType':type(error).__name__}))
            except (OSError, ValueError): pass
            raise
        finally: self.lock.release()

    def read_completed(self, ordinal, input_sha256):
        with self.lock:
            self._check(); self._identity()
            require(type(ordinal) is int and 0 <= ordinal < len(self.receipts), 'model batch not complete')
            receipt = self.receipts[ordinal]; stem = f'{ordinal:08d}'
            raw = (self.directory / (stem + '.input')).read_bytes()
            output = (self.directory / (stem + '.output')).read_bytes()
            require(digest(raw) == input_sha256 == receipt['inputSha256']
                    and digest(output) == receipt['outputSha256']
                    and (self.directory / (stem + '.receipt.json')).read_bytes() == encoded(receipt),
                    'persisted model batch differs')
            self._identity(); self._check()
            return {**receipt, 'result':json.loads(output), 'provisional':True}

    def finish(self, batches):
        with self.lock:
            try:
                self._check(); self._identity()
                expected = [{key: row[key] for key in ('ordinal', 'inputSha256', 'outputSha256')} for row in self.receipts]
                require(bool(expected) and encoded(batches) == encoded(expected), 'complete model batch receipt list required')
                for row in self.receipts:
                    stem = f"{row['ordinal']:08d}"
                    require(digest((self.directory / (stem + '.input')).read_bytes()) == row['inputSha256']
                            and digest((self.directory / (stem + '.output')).read_bytes()) == row['outputSha256']
                            and (self.directory / (stem + '.receipt.json')).read_bytes() == encoded(row),
                            'persisted model batch differs')
                self._check()
                summary = {'operationId':self.operation['operationId'], 'bindingSha256':self.operation['bindingSha256'],
                           'requestId':self.source['requestId'], 'batchCount':len(expected),
                           'batchesSha256':digest(encoded(expected)), 'totalBytes':self.total_bytes,
                           'status':'computation_completed_release_unverified'}
                atomic_new(self.directory / 'finished.json', encoded(summary)); self.finished = True
                return summary
            except BaseException as error:
                self.failed = True
                try: atomic_new(self.directory / 'failure.json', encoded({'errorType':type(error).__name__}))
                except (OSError, ValueError): pass
                raise


def _read_token(path):
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and not info.st_mode & 0o077,
                'private session token required')
        token = stream.read(513).decode('ascii').strip()
    require(re.fullmatch(r'[A-Za-z0-9._~-]{1,512}', token) is not None, 'invalid session token')
    return token


def _read_operation(path):
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_size <= 2 * MAX_BODY, 'bounded assigned operation required')
        return stream.read(2 * MAX_BODY + 1)


def _receive(connection, count):
    chunks = []
    while count:
        chunk = connection.recv(count)
        require(bool(chunk), 'incomplete model session frame')
        chunks.append(chunk); count -= len(chunk)
    return b''.join(chunks)


def serve_assigned():
    require(os.name == 'posix' and os.environ.get('CONSENSE_MODEL_DEVICE') == 'cuda'
            and os.environ.get('CONSENSE_MODEL_OFFLINE') == '1'
            and os.environ.get('HF_HUB_OFFLINE') == '1' and os.environ.get('TRANSFORMERS_OFFLINE') == '1'
            and not any(name.startswith('CONSENSE_FIXED_GPU_') for name in os.environ),
            'isolated offline CUDA model process required')
    root = Path(os.environ['CONSENSE_FIXED_WORKSPACE']).resolve()
    operation_id = os.environ['CONSENSE_FIXED_OPERATION_ID']
    require(root.is_dir() and os.environ.get('CONSENSE_FIXED_CONTAINER_ID')
            and re.fullmatch('[a-f0-9]{32}', operation_id), 'assigned model operation required')
    directory = root / 'gpu-operations' / operation_id
    request_path = Path(os.environ['CONSENSE_OPERATION_REQUEST'])
    token_path = Path(os.environ['CONSENSE_MODEL_SESSION_TOKEN_FILE'])
    require(request_path.is_absolute() and request_path == directory / 'request.json'
            and token_path.is_absolute() and token_path == directory / 'model-session.token'
            and directory.is_dir() and not directory.is_symlink() and directory.resolve() == directory,
            'assigned operation paths differ')
    info = directory.stat()
    require(info.st_uid == os.geteuid() and not info.st_mode & 0o077, 'private operation directory required')
    deadline = float(os.environ['CONSENSE_MODEL_SESSION_DEADLINE_MONOTONIC'])
    require(math.isfinite(deadline) and deadline > time.monotonic(), 'finite assigned session deadline required')
    def expire():
        while True:
            left = deadline - time.monotonic()
            if left <= 0: os._exit(124)
            time.sleep(min(.05, left))
    threading.Thread(target=expire, daemon=True).start()
    token = _read_token(token_path)
    raw = _read_operation(request_path)
    operation_sha = os.environ['CONSENSE_OPERATION_REQUEST_SHA256']
    require(digest(raw) == operation_sha, 'assigned operation bytes differ')
    assigned = verify_operation(json.loads(raw))
    require(assigned['operationId'] == operation_id
            and assigned['profileSha256'] == os.environ['CONSENSE_MODEL_SESSION_PROFILE_SHA256']
            and assigned['profile'].get('adapter') == 'consense.model-session.v1',
            'assigned operation or profile differs')
    budget = assigned['source'].get('deadlineBudgetMs')
    require(math.isfinite(deadline) and deadline > time.monotonic()
            and type(budget) is int and 0 < budget <= 86400000, 'finite assigned session deadline required')
    deadline = min(deadline, time.monotonic() + budget / 1000)
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'vetting_eval'))
    import server
    session = ModelSession(raw, directory / 'model-session', operation_sha256=operation_sha,
        profile_sha256=os.environ['CONSENSE_MODEL_SESSION_PROFILE_SHA256'], deadline=deadline,
        cancelled=lambda: False, backend=server, assigned_check=lambda: digest(_read_operation(request_path)) == operation_sha)
    require(session.operation['operationId'] == operation_id, 'assigned operation identity differs')
    socket_path = directory / 'model-session.sock'
    require(not socket_path.exists() and not socket_path.is_symlink(), 'fresh model session socket required')
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        listener.bind(str(socket_path)); os.chmod(socket_path, 0o600); listener.listen(1); listener.settimeout(.1)
        start_ticks = int(Path('/proc/self/stat').read_text().rsplit(')', 1)[1].split()[19])
        ready = {'operationId':operation_id, 'requestSha256':operation_sha,
                 'bindingSha256':session.operation['bindingSha256'], 'profileSha256':session.operation['profileSha256'],
                 'pid':os.getpid(), 'processStartTicks':start_ticks, 'socketInode':socket_path.lstat().st_ino}
        atomic_new(directory / 'model-session.ready.json', encoded(ready))
        while not session.finished:
            session._check()
            try: connection, _ = listener.accept()
            except socket.timeout: continue
            with connection:
                try:
                    connection.settimeout(max(.01, session.deadline - time.monotonic()))
                    size = struct.unpack('!I', _receive(connection, 4))[0]
                    require(0 < size <= 2 * MAX_BODY + 65536, 'bounded session frame required')
                    value = json.loads(_receive(connection, size))
                    require(isinstance(value, dict) and set(value) == {'token', 'method', 'payload'}
                            and isinstance(value['token'], str) and hmac.compare_digest(value['token'], token),
                            'session authentication failed')
                    payload = value['payload']; require(isinstance(payload, dict), 'session payload required')
                    if value['method'] == 'submit': result = session.submit(payload)
                    elif value['method'] == 'read_completed':
                        require(set(payload) == {'ordinal', 'inputSha256'}, 'exact read request required')
                        result = session.read_completed(payload['ordinal'], payload['inputSha256'])
                    elif value['method'] == 'finish':
                        require(set(payload) == {'batches'}, 'exact finish request required')
                        result = session.finish(payload['batches'])
                    else: raise ValueError('unknown model session method')
                    reply = encoded({'ok':True, 'result':result})
                except Exception as error:
                    reply = encoded({'ok':False, 'errorType':type(error).__name__})
                connection.sendall(struct.pack('!I', len(reply)) + reply)
                if session.failed: raise ValueError('model session failed')
        listener.close()
        # The owning gateway terminates this process and independently proves release.
        while True: signal.pause()
    finally:
        listener.close()


if __name__ == '__main__':
    serve_assigned()
