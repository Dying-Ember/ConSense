"""One finite caller-owned OCR request; immutable results use the existing CAS."""
from contextlib import contextmanager
import hashlib
import json
import math
import os
from pathlib import Path
import re
import time
import uuid

RESPONSE_CAP = 32 * 1024 * 1024
PROCESS_INSTANCE = uuid.uuid4().hex
ACTIVE = {'queued', 'running'}


class OcrRequestError(ValueError):
    def __init__(self, status_code, message, task=None):
        super().__init__(message)
        self.status_code, self.task = status_code, task


def _bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def _directory(store, task_id):
    if not isinstance(task_id, str) or not re.fullmatch('[a-f0-9]{32}', task_id):
        raise OcrRequestError(422, 'OCR task ID must be a random lowercase UUID hex capability')
    directory = store.root / 'request-tasks' / task_id
    if not directory.resolve().is_relative_to(store.root):
        raise OcrRequestError(422, 'OCR request path escaped its state directory')
    return directory


def read_request(store, task_id):
    directory = _directory(store, task_id)
    try:
        record = json.loads((directory / 'task.json').read_bytes())
    except (OSError, ValueError) as error:
        raise OcrRequestError(404 if not directory.exists() else 409,
                              'OCR task unknown; do not resubmit it') from error
    if record['status'] in ACTIVE and record['processInstance'] != PROCESS_INSTANCE:
        record['status'] = 'unknown'
    record['cancellationRequested'] = (directory / 'cancel').exists()
    return record


def cancel_request(store, task_id):
    record = read_request(store, task_id)
    if record['status'] in ACTIVE:
        (_directory(store, task_id) / 'cancel').touch(exist_ok=True)
    return read_request(store, task_id)


def request_result(store, task_id):
    record = read_request(store, task_id)
    if record['status'] != 'completed':
        raise OcrRequestError(409, 'OCR task has no completed result; do not resubmit it', record)
    if record['resultRef']['byteCount'] > RESPONSE_CAP:
        raise OcrRequestError(413, 'Complete OCR result exceeds inline response limit; retained in CAS', record)
    return json.loads(store.read_asset(record['resultRef']))


class OcrRequest:
    def __init__(self, store, task_id, binding, *, deadline_ms=None, max_seconds=600):
        if isinstance(max_seconds, bool) or not isinstance(max_seconds, (int, float)) \
                or not math.isfinite(max_seconds) or not 0 < max_seconds <= 86400:
            raise OcrRequestError(422, 'OCR server maximum must be positive finite and bounded')
        now, started = time.time(), time.monotonic()
        if deadline_ms is not None and (isinstance(deadline_ms, bool) or not isinstance(deadline_ms, int)
                                        or not 0 < deadline_ms < 10**15):
            raise OcrRequestError(422, 'OCR caller deadline must be positive Unix milliseconds')
        expiry = min(now + max_seconds, deadline_ms / 1000 if deadline_ms is not None else now + max_seconds)
        self.store, self.task_id = store, task_id or uuid.uuid4().hex
        self.directory = _directory(store, self.task_id)
        self.deadline = started + expiry - now
        binding_sha = hashlib.sha256(_bytes(binding)).hexdigest()
        self.directory.parent.mkdir(parents=True, exist_ok=True)
        try:
            self.directory.mkdir()
        except FileExistsError:
            self.fresh = False
            self.record = read_request(store, self.task_id)
            if self.record['bindingSha256'] != binding_sha:
                raise OcrRequestError(409, 'OCR task ID is already bound to different input, purpose or source context', self.record)
            self.deadline = started + self.record['deadlineUnixMs'] / 1000 - now
        else:
            self.fresh = True
            self.record = {'schema': 'consense.ocr-request.v1', 'taskId': self.task_id,
                           'bindingSha256': binding_sha, 'deadlineUnixMs': int(expiry * 1000),
                           'processInstance': PROCESS_INSTANCE, 'status': 'queued'}
            self._save()

    def _save(self):
        path = self.directory / ('.task-' + uuid.uuid4().hex)
        try:
            with path.open('xb') as stream:
                stream.write(_bytes(self.record)); stream.flush(); os.fsync(stream.fileno())
            os.replace(path, self.directory / 'task.json')
        finally:
            path.unlink(missing_ok=True)

    def cancelled(self):
        return (self.directory / 'cancel').exists()

    def check(self):
        if self.cancelled():
            raise OcrRequestError(409, 'OCR request cancelled', self.record)
        if time.monotonic() >= self.deadline:
            raise OcrRequestError(504, 'OCR caller/server deadline elapsed', self.record)

    @contextmanager
    def acquire(self, lock):
        while True:
            self.check()
            if lock.acquire(timeout=min(.1, max(.001, self.deadline - time.monotonic()))):
                break
        try:
            self.check()
            self.record['status'] = 'running'; self._save()
            yield
        finally:
            lock.release()

    def existing_result(self):
        return request_result(self.store, self.task_id)

    def success(self, value):
        self.record['resultRef'] = self.store.put_bytes(_bytes(value), identity_scope='complete_ocr_request_result')
        self.check()
        self.record['status'] = 'completed'; self._save()
        if self.record['resultRef']['byteCount'] > RESPONSE_CAP:
            raise OcrRequestError(413, 'Complete OCR result exceeds inline response limit; retained in CAS', self.record)
        return value

    def fail(self, error):
        if self.record['status'] == 'completed':
            return
        self.record['status'] = 'cancelled' if self.cancelled() else 'expired' if time.monotonic() >= self.deadline else 'failed'
        self.record['failureRef'] = self.store.put_bytes(_bytes({'errorType': type(error).__name__,
            'message': str(error), 'providerReceipt': getattr(error, 'receipt', None)}), identity_scope='ocr_request_failure')
        self._save()
