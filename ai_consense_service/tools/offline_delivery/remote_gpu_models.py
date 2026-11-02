"""Expose the existing model-method seam over the fixed GPU executor."""
import math
import hashlib
import json
import os
import re
import stat
import threading
import time
from contextlib import contextmanager
from contextvars import ContextVar
from pathlib import Path

from delivery import require
from request_stage import encoded
from fixed_gpu_client import OperationError, gpu_release_proven
from fixed_model_session import ModelSession
from model_session_worker import MAX_BATCHES, MAX_TOTAL_BYTES


_operation = ContextVar('remote_gpu_operation', default=None)
_model_scope = ContextVar('remote_model_session_scope', default=None)


def _trusted_stage_timeout(stage, profile_sha, execution_sha):
    from trusted_stage_pins import (GPU_CONFIG_PATH, MAX_CONFIG_BYTES, _invalid_constant, _unique,
                                    load_trusted_stage_pins)
    config_sha = os.environ.get('CONSENSE_FIXED_GPU_CONFIG_SHA256')
    fixed_reference = stage == 'fixed-reference-query' and execution_sha is None
    if not fixed_reference:
        pins = load_trusted_stage_pins(stage, GPU_CONFIG_PATH, config_sha,
            os.environ.get('CONSENSE_FIXED_GPU_PROFILE_MANIFEST'),
            os.environ.get('CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256'))
        require(pins == {'profileSha256':profile_sha, 'executionSha256':execution_sha},
                'independent GPU stage pins changed')
    path = Path(GPU_CONFIG_PATH)
    require(path.is_absolute() and not path.is_symlink(), 'absolute GPU configuration required')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_size <= MAX_CONFIG_BYTES, 'bounded GPU configuration required')
        raw = stream.read(MAX_CONFIG_BYTES + 1)
    require(len(raw) <= MAX_CONFIG_BYTES and hashlib.sha256(raw).hexdigest() == config_sha,
            'GPU configuration identity changed')
    config = json.loads(raw, object_pairs_hook=_unique, parse_constant=_invalid_constant)
    row = config['stages'][stage]
    if fixed_reference:
        from fixed_gpu_profiles import load_profiles
        profiles = load_profiles(os.environ.get('CONSENSE_FIXED_GPU_PROFILE_MANIFEST'),
                                 os.environ.get('CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256'))
        require(isinstance(row.get('profile'),dict) and row['profile'] and
                profiles.get(stage) == profile_sha == hashlib.sha256(encoded(row['profile'])).hexdigest(),
                'independent GPU stage pins changed')
    timeout = row.get('timeoutSeconds')
    require(row.get('mode') == 'model-session' and type(timeout) in (int, float)
            and math.isfinite(timeout) and 0 < timeout <= 86400, 'invalid GPU model session timeout')
    return timeout


class _RequestModels:
    def __init__(self, proxy, control, source_sha, stage, max_batches, max_bytes, observer, profile_sha, execution_sha, fixed_backend=None):
        self.proxy, self.control, self.source_sha, self.stage = proxy, control, source_sha, stage
        self.max_batches, self.max_bytes = max_batches, max_bytes
        self.thread = threading.get_ident(); self.session = None; self.kind = None
        self.started_kinds = set()
        self.batches = 0; self.total_bytes = 0; self.dimension = None; self.failed = False
        self.receipts = []; self.failure_receipt = None; self.failure_operation = None
        self.observer = observer; self.observations = []; self.pending = None; self.session_ordinal = 0
        self.profile_sha = profile_sha; self.execution_sha = execution_sha
        self.fixed_backend = fixed_backend

    def observe(self, result):
        if self.observer is None: return
        require(self.pending is not None, 'model observation is missing')
        metadata, raw, delta = self.pending
        try:
            output = encoded(result)
            expected = {'ordinal':metadata['ordinal'], 'inputSha256':hashlib.sha256(raw).hexdigest(), 'inputBytes':len(raw),
                        'outputSha256':hashlib.sha256(output).hexdigest(), 'outputBytes':len(output)}
            require(delta == len(raw)+len(output), 'model observation byte count differs')
            record = self.observer.prepare(dict(metadata),json.loads(raw),json.loads(output))
            require(isinstance(record,dict) and encoded(record) == encoded(expected), 'independent model observation differs')
            def commit():
                require(time.monotonic() < self.session.deadline, 'model session deadline elapsed before observation acceptance')
                self.observer.commit(dict(metadata),dict(expected))
                self.observations.append({**metadata,**expected})
            self.control['accept'](commit)
            self.session_ordinal += 1
        finally:
            self.pending = None

    def _verify_observations(self, receipt):
        rows = [r for r in self.observations if r['operationId'] == self.session.operation]
        expected = [{'ordinal':r['ordinal'],'inputSha256':r['inputSha256'],'outputSha256':r['outputSha256']}
                    for r in rows]
        summary = receipt.get('summary',{})
        binding = self.session.binding
        claims = {key:binding[key] for key in ('requestId','sourceScopeSha256','modelKind',
                                               'inferenceCap','deadlineBudgetMs','maxBatches','maxTotalBytes')}
        claims.update(modelIdentitySha256=hashlib.sha256(encoded(binding['modelIdentity'])).hexdigest(),
                      runtimeSha256=hashlib.sha256(encoded(binding['runtime'])).hexdigest())
        require(rows and [r['ordinal'] for r in rows] == list(range(len(rows)))
                and all(r['modelKind'] == self.kind for r in rows)
                and receipt.get('schema') == 'consense.fixed-model-session-receipt.v1'
                and receipt.get('operationId') == self.session.operation and receipt.get('stage') == self.stage
                and claims['requestId'] == self.control['requestId'] and claims['sourceScopeSha256'] == self.source_sha
                and claims['modelKind'] == self.kind and isinstance(receipt.get('sourceClaims'),dict)
                and encoded(receipt['sourceClaims']) == encoded(claims)
                and receipt.get('sourceSha256') == hashlib.sha256(encoded(binding)).hexdigest()
                and receipt.get('bodySha256') == hashlib.sha256(b'{}').hexdigest()
                and receipt.get('profileSha256') == self.profile_sha == self.session.expected['profileSha256']
                and receipt.get('bindingSha256') == self.session.binding_sha and gpu_release_proven(receipt.get('release'), owned_tree=True)
                and isinstance(receipt.get('batches'),list) and encoded(receipt['batches']) == encoded(expected)
                and type(summary.get('batchCount')) is int and summary['batchCount'] == len(rows)
                and summary.get('operationId') == self.session.operation and summary.get('requestId') == self.control['requestId']
                and summary.get('bindingSha256') == self.session.binding_sha
                and summary.get('status') == 'computation_completed_release_unverified'
                and summary.get('batchesSha256') == hashlib.sha256(encoded(expected)).hexdigest()
                and type(summary.get('totalBytes')) is int
                and summary['totalBytes'] == sum(r['inputBytes']+r['outputBytes'] for r in rows)
                and receipt.get('modelSummarySha256') == hashlib.sha256(encoded(summary)).hexdigest(),
                'local observations differ from model receipt')
        require(self.observer.verify_session(json.loads(encoded(receipt))) is True,
                'independent model observer rejected receipt')

    def finish(self):
        require(self.proxy._request_scope() is self, 'model scope ownership differs')
        if self.session is None: return
        session = self.session
        try:
            require(self.pending is None, 'model observation pending')
            receipt = session.finish()
            require(session.released, 'model session release not proven')
            if self.observer is not None: self._verify_observations(receipt)
            self.receipts.append(json.loads(encoded(receipt))); self.session = None; self.kind = None
        except BaseException as error:
            self.failed = True
            self.failure_receipt = getattr(error, 'exit_receipt', session.exit_receipt)
            raise

    def cancel(self):
        self.pending = None
        if self.session is not None:
            try: self.failure_receipt = self.session.cancel()
            except BaseException: self.failure_receipt = {'status':'release_unknown',
                                                          'operationId':getattr(self.session,'operation',None)}

    def compute(self, kind, body):
        require(self.proxy._request_scope() is self, 'model scope ownership differs')
        require(not self.failed, 'model session scope failed')
        require(self.stage != 'document-embedding' or kind == 'embedding', 'model kind differs from request stage')
        require(self.batches < self.max_batches and self.total_bytes < self.max_bytes, 'model scope budget exceeded')
        if self.session is None or self.kind != kind:
            require(kind not in self.started_kinds and not (kind == 'embedding' and 'reranker' in self.started_kinds),
                    'model session order differs')
        if self.session is not None and self.kind != kind: self.finish()
        if self.session is None:
            require(time.monotonic() < self.control['deadline'] and not self.control['cancelled'](),
                    'model scope cancelled or expired')
            runtime = (self.fixed_backend or self.proxy.backend).window_runtime()
            model_runtime = json.loads(encoded(self.fixed_backend.model_runtime(kind) if self.fixed_backend else self.proxy.model_runtime(kind)))
            inference_cap = runtime.inference_cap(kind)
            require(type(inference_cap) is int and 0 < inference_cap <= 8192, 'model inference cap differs')
            if self.observer is None:
                identity = runtime.logical_identity(kind)
            else:
                observed = self.observer.preflight_session(self.stage,kind)
                require(isinstance(observed,dict) and set(observed) == {'modelIdentity','runtime','inferenceCap',
                        'profileSha256','executionSha256'}
                        and isinstance(observed['modelIdentity'],dict) and observed['modelIdentity']
                        and isinstance(observed['runtime'],dict) and encoded(observed['runtime']) == encoded(model_runtime)
                        and type(observed['inferenceCap']) is int and observed['inferenceCap'] == inference_cap
                        and observed['profileSha256'] == self.profile_sha
                        and observed['executionSha256'] == self.execution_sha,
                        'independent model preflight differs')
                identity = json.loads(encoded(observed['modelIdentity']))
            stage_timeout = (_trusted_stage_timeout(self.stage, self.profile_sha, self.execution_sha)
                             if self.observer is not None or self.fixed_backend is not None else self.proxy.timeout)
            remaining = min(self.proxy.timeout, self.control['deadline'] - time.monotonic(), stage_timeout)
            require(remaining >= .001 and not self.control['cancelled'](), 'model scope cancelled or expired')
            binding = {'requestId':self.control['requestId'], 'sourceScopeSha256':self.source_sha,
                       'modelKind':kind, 'modelIdentity':identity,
                       'runtime':model_runtime, 'inferenceCap':inference_cap,
                       'deadlineBudgetMs':int(remaining * 1000), 'maxBatches':self.max_batches-self.batches,
                       'maxTotalBytes':self.max_bytes-self.total_bytes}
            if self.observer is not None:
                self.observer.begin_session(self.stage,json.loads(encoded(binding)))
                require(time.monotonic() < self.control['deadline'] and not self.control['cancelled'](),
                        'model scope cancelled or expired before admission')
                require(self.proxy.client.profile_sha256s.get(self.stage) == self.profile_sha,
                        'model stage profile changed before admission')
            try:
                self.session = ModelSession.start(self.proxy.client,self.stage,binding,self.control['deadline'],
                                                  self.control['cancelled'],self.control['accept'])
                self.kind = kind; self.session_ordinal = 0; self.started_kinds.add(kind)
                if self.observer is not None:
                    require(self.session.expected.get('profileSha256') == self.profile_sha,
                            'admitted model stage profile differs')
                    self.observer.bind_operation(self.stage,kind,self.session.operation)
            except BaseException as error:
                self.failed = True
                if isinstance(error, OperationError): self.failure_operation = error.operation
                self.failure_receipt = getattr(error, 'exit_receipt', {'status':'release_unknown'})
                raise
        require(self.pending is None, 'model observation pending')
        raw = encoded(body); submitted = json.loads(raw)
        previous = self.session.total_bytes
        result = self.session.batch(submitted)
        require(encoded(submitted) == raw, 'model batch input changed during submission')
        delta = self.session.total_bytes-previous
        require(type(delta) is int and delta > 0 and self.total_bytes + delta <= self.max_bytes,
                'model scope byte budget exceeded')
        self.batches += 1; self.total_bytes += delta
        if self.observer is not None:
            self.pending = ({'scopeOrdinal':self.batches-1,'ordinal':self.session_ordinal,
                             'modelKind':kind,'operationId':self.session.operation},raw,delta)
        return result


@contextmanager
def operation_context(cancelled, deadline):
    require(callable(cancelled) and type(deadline) in (int, float) and math.isfinite(deadline),
            'owned cancellation and finite monotonic deadline required')
    token = _operation.set((cancelled, deadline))
    try: yield
    finally: _operation.reset(token)


class RemoteModels:
    def __init__(self, backend, client, *, dtype, serial_offload, timeout):
        require(dtype in ('float16', 'float32') and type(serial_offload) is bool
                and type(timeout) in (int, float) and math.isfinite(timeout) and 0 < timeout <= 86400,
                'explicit finite remote CUDA profile required')
        self.backend, self.client, self.dtype, self.serial_offload, self.timeout = backend, client, dtype, serial_offload, timeout
        self.embedding = _Embedding(self)
        self.reranker = _Reranker(self)
        self._scope_lock = threading.Lock(); self._active_scope = None; self._scope_blocked = False; self._scope_required = False
        self._legacy_inflight = 0

    def require_request_scope(self):
        with self._scope_lock:
            require(self._active_scope is None and not self._legacy_inflight, 'model operation already active')
            self._scope_required = True

    @contextmanager
    def session_scope(self, control, source_scope_sha256, stage, *, max_batches, max_total_bytes, observer=None,
                      execution_sha256=None, fixed_backend=None):
        require(isinstance(control, dict) and set(control) == {'requestId','deadline','cancelled','accept'}
                and isinstance(control['requestId'], str) and re.fullmatch('[a-f0-9]{32}', control['requestId'])
                and type(control['deadline']) in (int,float) and math.isfinite(control['deadline'])
                and callable(control['cancelled']) and callable(control['accept'])
                and isinstance(source_scope_sha256,str) and re.fullmatch('[a-f0-9]{64}',source_scope_sha256)
                and stage in ('document-embedding','query-embedding-rerank','fixed-reference-query')
                and type(max_batches) is int and 0 < max_batches <= MAX_BATCHES
                and type(max_total_bytes) is int and 0 < max_total_bytes <= MAX_TOTAL_BYTES,
                'bounded request-owned model scope required')
        control = dict(control)
        require((fixed_backend is None and stage != 'fixed-reference-query') or
                (fixed_backend is not None and stage == 'fixed-reference-query' and observer is None),
                'fixed-reference model backend must be scoped to its own stage')
        require(not control['cancelled']() and time.monotonic() < control['deadline'], 'model scope cancelled or expired')
        require(observer is None or all(callable(getattr(observer,name,None)) for name in
                ('preflight_session','begin_session','bind_operation','prepare','commit','verify_session')),
                'independent model observer required')
        pins = getattr(self.client,'profile_sha256s',{}) if observer is not None or fixed_backend is not None else {}
        profile_sha = pins.get(stage) if isinstance(pins,dict) else None
        require(observer is None or (isinstance(profile_sha,str) and re.fullmatch('[a-f0-9]{64}',profile_sha)
                and isinstance(execution_sha256,str) and re.fullmatch('[a-f0-9]{64}',execution_sha256)),
                'independently pinned profile and execution identities required')
        require(fixed_backend is None or (isinstance(profile_sha,str) and re.fullmatch('[a-f0-9]{64}',profile_sha)),
                'independently pinned fixed-reference profile required')
        scope = _RequestModels(self,control,source_scope_sha256,stage,max_batches,max_total_bytes,observer,
                               profile_sha,execution_sha256,fixed_backend)
        with self._scope_lock:
            require(not self._scope_blocked, 'previous model session release unknown')
            require(not self._scope_required or observer is not None or fixed_backend is not None, 'independent model observer required')
            require(self._active_scope is None and not self._legacy_inflight and _model_scope.get() is None,
                    'model operation already active')
            self._active_scope = scope
        token = _model_scope.set(scope)
        try:
            yield scope
            require(not scope.failed, 'model session scope failed')
            scope.finish()
        except BaseException:
            if scope.session is not None or scope.failure_receipt is not None or scope.started_kinds:
                with self._scope_lock: self._scope_blocked = True
            scope.cancel()
            receipt = scope.failure_receipt
            operation = scope.session.operation if scope.session is not None else scope.failure_operation
            released = (isinstance(operation,str) and re.fullmatch('[a-f0-9]{32}',operation) is not None
                    and isinstance(receipt, dict) and receipt.get('operationId') == operation
                    and receipt.get('operationStatus') in ('completed', 'failed', 'cancelled', 'expired')
                    and gpu_release_proven({key:value for key,value in receipt.items()
                                            if key not in ('operationId', 'operationStatus')}, owned_tree=True))
            finished = (scope.session is None and scope.failure_receipt is None and scope.started_kinds
                        and len(scope.receipts) == len(scope.started_kinds)
                        and all(gpu_release_proven(item.get('release'), owned_tree=True) for item in scope.receipts))
            released_active = (released and scope.session is not None
                               and len(scope.receipts) + 1 == len(scope.started_kinds)
                               and all(gpu_release_proven(item.get('release'), owned_tree=True) for item in scope.receipts))
            if finished or released_active or (control['cancelled']() and released):
                with self._scope_lock: self._scope_blocked = False
            raise
        finally:
            _model_scope.reset(token)
            with self._scope_lock: self._active_scope = None

    def _request_scope(self):
        current = _model_scope.get()
        with self._scope_lock: active = self._active_scope; blocked = self._scope_blocked; required = self._scope_required
        require(not blocked, 'previous model session release unknown')
        require(not required or current is not None, 'request-owned model scope required')
        require((current is None and active is None)
                or (current is active and current is not None and current.proxy is self
                    and current.thread == threading.get_ident()), 'model scope ownership differs')
        return current

    @contextmanager
    def _checked(self):
        scope = self._request_scope()
        try: yield scope
        except BaseException:
            if scope is not None: scope.failed = True; scope.pending = None
            raise

    def model_runtime(self, kind):
        require(kind in ('embedding', 'reranker'), 'unknown model kind')
        return {**self.backend.MODEL_SETTINGS[kind], **self.backend.MODELS[kind], 'device':'cuda',
                'dtype':self.dtype, 'serialOffload':self.serial_offload}

    def _compute(self, kind, stage, body):
        scope = self._request_scope()
        if scope is not None: return scope.compute(kind,body)
        with self._scope_lock:
            require(not self._scope_blocked, 'previous model session release unknown')
            require(not self._scope_required and self._active_scope is None and not self._legacy_inflight
                    and _model_scope.get() is None,
                    'request-owned model scope required or another model operation is active')
            self._legacy_inflight += 1
        try:
            return self._compute_legacy(kind,stage,body)
        finally:
            with self._scope_lock: self._legacy_inflight -= 1

    def _compute_legacy(self, kind, stage, body):
        operation = _operation.get()
        cancelled = operation[0] if operation else None
        timeout = min(self.timeout, operation[1] - time.monotonic()) if operation else self.timeout
        if timeout <= 0 or (cancelled is not None and cancelled()):
            raise TimeoutError('remote GPU caller cancelled or deadline elapsed before admission')
        runtime = self.backend.window_runtime()
        binding = {'modelIdentity':runtime.logical_identity(kind), 'runtime':self.model_runtime(kind),
                   'inferenceCap':runtime.inference_cap(kind)}
        timeout = min(self.timeout, operation[1] - time.monotonic()) if operation else self.timeout
        if timeout <= 0 or (cancelled is not None and cancelled()):
            raise TimeoutError('remote GPU caller cancelled or deadline elapsed during identity validation')
        try:
            result = self.client.compute(stage, binding, body, timeout, cancelled=cancelled)
        except OperationError as failure:
            with self._scope_lock: self._scope_blocked = True
            if hasattr(runtime, 'emit'):
                try:
                    runtime.emit('remote-model-operation', {'kind':kind, 'inputSha256':hashlib.sha256(encoded(body)).hexdigest(),
                        'operationId':failure.operation, 'release':failure.exit_receipt, 'status':'failed'})
                except BaseException as error:
                    failure.add_note('operation failure receipt emission failed: '+type(error).__name__)
            raise
        except BaseException:
            with self._scope_lock: self._scope_blocked = True
            raise
        if hasattr(runtime, 'emit'):
            runtime.emit('remote-model-operation', {'kind':kind, 'inputSha256':hashlib.sha256(encoded(body)).hexdigest(),
                'receipt':result.get('_operationReceipt'), 'status':'transport_completed_result_validation_pending'})
        return result

    def embedding_model(self): return self.embedding
    def rerank_model(self): return self.reranker

    def encode_embeddings(self, texts):
        import numpy as np
        with self._checked() as scope:
            require(isinstance(texts, list) and texts and all(isinstance(text, str) and text for text in texts), 'embedding texts required')
            result = self._compute('embedding', 'document-embedding', {'kind':'embedding', 'texts':texts})
            dimension, vectors = result.get('dimension'), result.get('vectors')
            require(type(dimension) is int and dimension > 0 and isinstance(vectors, list) and len(vectors) == len(texts)
                    and all(isinstance(row, list) and len(row) == dimension and all(type(x) in (int, float) and math.isfinite(x) for x in row)
                            for row in vectors), 'embedding result dimension or finite values differ')
            expected = scope.dimension if scope is not None else getattr(self.embedding, 'dimension', None)
            require(expected is None or expected == dimension, 'embedding dimension changed after model admission')
            if scope is not None: scope.dimension = dimension
            with np.errstate(over='ignore'):
                values = np.asarray(vectors, dtype=np.float32)
            require(np.isfinite(values).all(), 'embedding result is not finite in float32')
            if scope is not None: scope.observe(result)
            return values


class _Embedding:
    def __init__(self, owner): self.owner = owner

    def get_sentence_embedding_dimension(self):
        with self.owner._checked() as scope:
            result = self.owner._compute('embedding', 'document-embedding', {'kind':'dimension'})
            dimension = result.get('dimension')
            require(type(dimension) is int and dimension > 0, 'embedding dimension unavailable')
            if scope is None: self.dimension = dimension
            else:
                require(scope.dimension is None or scope.dimension == dimension, 'embedding dimension changed after model admission')
                scope.dimension = dimension
            if scope is not None: scope.observe(result)
            return dimension


class _Reranker:
    def __init__(self, owner): self.owner = owner

    def predict(self, pairs, *, batch_size, show_progress_bar, convert_to_numpy):
        import numpy as np
        with self.owner._checked() as scope:
            backend = scope.fixed_backend if scope is not None and scope.fixed_backend is not None else self.owner.backend
            require(batch_size == backend.MODEL_SETTINGS['reranker']['batchSize']
                    and show_progress_bar is False and convert_to_numpy is True, 'rerank options differ from frozen profile')
            pairs = [list(pair) for pair in pairs]
            require(pairs and all(len(pair) == 2 and all(isinstance(value, str) and value for value in pair) for pair in pairs),
                    'rerank pairs required')
            result = self.owner._compute('reranker', 'query-embedding-rerank', {'kind':'reranker', 'pairs':pairs})
            scores = result.get('scores')
            require(isinstance(scores, list) and len(scores) == len(pairs)
                    and all(type(value) in (int, float) and math.isfinite(value) for value in scores), 'rerank result differs')
            scope = self.owner._request_scope()
            if scope is not None: scope.observe(result)
            return np.asarray(scores)
