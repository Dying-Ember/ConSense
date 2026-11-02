"""Bounded, request-owned provisional model batches on the fixed GPU gateway."""
import base64
import hashlib
import json
import math
import re
import threading
import time

from delivery import require
from fixed_gpu_client import AdmissionCancelled, OperationError, gpu_release_proven
from request_stage import MAX_BODY, encoded


class ModelSession:
    def __init__(self, client, stage, binding, operation, deadline, cancelled, expected, accept):
        self.client, self.stage, self.binding, self.operation = client, stage, binding, operation
        self.deadline, self.cancelled = deadline, cancelled
        self.expected = expected
        self.accept = accept
        self.binding_sha = None
        self.batches, self.total_bytes = [], 0
        self.released = False; self.closed = False
        self.exit_receipt = {'status':'release_unknown', 'operationId':operation}
        self.lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.cancel_requested = threading.Event()

    @classmethod
    def start(cls, client, stage, binding, deadline, cancelled=None, accept=None):
        require((cancelled is None and accept is None)
                or (callable(cancelled) and callable(accept)), 'request cancellation acceptance gate required')
        require(stage in ('document-embedding', 'query-embedding-rerank', 'fixed-reference-query') and isinstance(binding, dict)
                and set(binding) == {'requestId','sourceScopeSha256','modelKind','modelIdentity','runtime',
                                     'inferenceCap','deadlineBudgetMs','maxBatches','maxTotalBytes'}, 'exact session binding required')
        binding = json.loads(encoded(binding))
        require(isinstance(binding['requestId'], str) and re.fullmatch('[a-f0-9]{32}', binding['requestId'])
                and isinstance(binding['sourceScopeSha256'], str) and re.fullmatch('[a-f0-9]{64}', binding['sourceScopeSha256'])
                and binding['modelKind'] in ('embedding','reranker')
                and (stage != 'document-embedding' or binding['modelKind'] == 'embedding')
                and isinstance(binding['modelIdentity'], dict) and binding['modelIdentity']
                and isinstance(binding['runtime'], dict) and binding['runtime'].get('device') == 'cuda'
                and all(type(binding[k]) is int and binding[k] > 0 for k in ('inferenceCap','deadlineBudgetMs','maxBatches','maxTotalBytes'))
                and type(deadline) in (int, float) and math.isfinite(deadline), 'bounded model session identity required')
        remaining = deadline - time.monotonic()
        require(remaining > 0 and (cancelled is None or not cancelled()), 'session cancelled or expired before admission')
        deadline = min(deadline, time.monotonic() + binding['deadlineBudgetMs'] / 1000.)
        expected = client._identity(stage, binding, b'{}')
        require(cancelled is None or not cancelled(), 'session cancelled before admission')
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise TimeoutError('model session deadline elapsed before admission')
        try:
            reply = client._call('POST','/operations',remaining,
                {'stage':stage,'binding':binding,'bodyBase64':base64.b64encode(b'{}').decode(),
                 'expectedProfileSha256':expected['profileSha256'], 'expectedMode':'model-session'}, cancelled=cancelled)
        except AdmissionCancelled as error:
            raise client._cancelled_admission(error, expected, model_stage=stage) from error
        except Exception as error:
            raise OperationError('GPU session admission response is unknown: '+str(error), None,
                                 {'status':'release_unknown','operationId':None}) from error
        operation = reply.get('operationId')
        if not isinstance(operation, str) or not re.fullmatch('[a-f0-9]{32}', operation):
            raise OperationError('GPU session admission identity unknown', None, {'status':'release_unknown','operationId':None})
        session = None
        try:
            session = cls(client, stage, binding, operation, deadline, cancelled, expected, accept)
            while True:
                state = session._status()
                if state['status'] == 'ready':
                    def ready():
                        session._remaining()
                        return session
                    return session._accept(ready)
                require(state['status'] in ('starting','running'), 'model session failed before readiness')
                time.sleep(min(.05, session._remaining()))
        except Exception as error:
            if session is not None: session._fail(error)
            receipt = {'status':'release_unknown','operationId':operation}
            try: receipt = client.cancel_and_observe(operation, expected, model_stage=stage)
            except Exception: pass
            raise OperationError(str(error), operation, receipt) from error

    def _remaining(self):
        if self._is_cancelled(): raise ValueError('model session cancelled')
        remaining = self.deadline - time.monotonic()
        if remaining <= 0: raise TimeoutError('model session deadline elapsed')
        return remaining

    def _is_cancelled(self):
        return self.cancel_requested.is_set() or (self.cancelled is not None and self.cancelled())

    def _accept(self, action):
        return self.accept(action) if self.accept is not None else action()

    def _status(self):
        value = self.client._call('GET','/operations/'+self.operation,min(1.,self._remaining()),cancelled=self._is_cancelled)
        digest = value.get('bindingSha256')
        require(value.get('operationId') == self.operation and value.get('stage') == self.stage
                and self.client._matches(value,self.expected) and isinstance(digest,str) and re.fullmatch('[a-f0-9]{64}',digest)
                and self.binding_sha in (None,digest), 'model session identity changed')
        self.binding_sha = digest
        return value

    def _fail(self, error):
        try: receipt = self.cancel()
        except Exception: receipt = {'status':'release_unknown','operationId':self.operation}
        raise OperationError(str(error),self.operation,receipt) from error

    def batch(self, body):
        require(self.lock.acquire(blocking=False), 'model session already has an active operation')
        try:
            require(not self.closed and isinstance(body,dict), 'active session and model body required')
            require((self.binding['modelKind'] == 'embedding' and body.get('kind') in ('dimension','embedding'))
                    or (self.binding['modelKind'] == 'reranker' and body.get('kind') == 'reranker'), 'model batch kind differs')
            raw = encoded(body); ordinal = len(self.batches); digest = hashlib.sha256(raw).hexdigest()
            require(len(raw) <= MAX_BODY and ordinal < self.binding['maxBatches']
                    and self.total_bytes + len(raw) <= self.binding['maxTotalBytes'], 'model session budget exceeded')
            reply = self.client._call('POST','/operations/'+self.operation+'/model-batches',self._remaining(),
                {'ordinal':ordinal,'inputSha256':digest,'bodyBase64':base64.b64encode(raw).decode()},cancelled=self._is_cancelled)
            result = reply.get('result')
            output = encoded(result)
            require(reply.get('operationId') == self.operation and reply.get('bindingSha256') == self.binding_sha
                    and type(reply.get('ordinal')) is int and reply['ordinal'] == ordinal and reply.get('inputSha256') == digest
                    and isinstance(result,dict) and reply.get('provisional') is True
                    and reply.get('outputSha256') == hashlib.sha256(output).hexdigest(), 'model batch identity differs')
            require(len(output) <= MAX_BODY and self.total_bytes + len(raw) + len(output) <= self.binding['maxTotalBytes'],
                    'model session budget exceeded')
            self._remaining()
            def accept_batch():
                with self.state_lock:
                    require(not self.closed and not self.cancel_requested.is_set() and time.monotonic() < self.deadline,
                            'model session closed, cancelled or expired')
                    self.batches.append({'ordinal':ordinal,'inputSha256':digest,'outputSha256':reply['outputSha256']})
                    self.total_bytes += len(raw) + len(output)
                    return result
            return self._accept(accept_batch)
        except Exception as error:
            self._fail(error)
        finally:
            self.lock.release()

    def finish(self):
        require(self.lock.acquire(blocking=False), 'model session already has an active operation')
        try:
            require(not self.closed and self.batches, 'nonempty active model session required')
            reply = self.client._call('POST','/operations/'+self.operation+'/finish-model',self._remaining(),
                                      {'batches':self.batches},cancelled=self._is_cancelled)
            require(reply.get('operationId') == self.operation and reply.get('status') == 'finishing', 'model finish identity differs')
            while True:
                state = self._status()
                if state.get('status') == 'completed': break
                require(state.get('status') in ('ready','finishing','settling'), 'model session failed or release unknown')
                time.sleep(min(.05,self._remaining()))
            require(gpu_release_proven(state.get('release'), owned_tree=True)
                    and set(state['release']) in ({'status','cgroupPopulated','observedGpuPids'},
                                                  {'status','cgroupPopulated','ownership','processWaited'}),
                    'model session GPU release not proven')
            summary = self.client._call('GET','/operations/'+self.operation+'/model-summary',self._remaining(),cancelled=self._is_cancelled)
            require(set(summary) == {'operationId','bindingSha256','requestId','batchCount','batchesSha256','totalBytes','status'}
                    and hashlib.sha256(encoded(summary)).hexdigest() == state.get('modelSummarySha256')
                    and summary.get('operationId') == self.operation and summary.get('bindingSha256') == self.binding_sha
                    and summary.get('requestId') == self.binding['requestId'] and summary.get('batchCount') == len(self.batches)
                    and summary.get('batchesSha256') == hashlib.sha256(encoded(self.batches)).hexdigest()
                    and summary.get('totalBytes') == self.total_bytes
                    and summary.get('status') == 'computation_completed_release_unverified', 'model session summary differs')
            source = json.loads(encoded(self.binding)); batches = json.loads(encoded(self.batches))
            require(self.expected.get('bodySha256') == hashlib.sha256(b'{}').hexdigest()
                    and self.expected.get('sourceSha256') == hashlib.sha256(encoded(source)).hexdigest()
                    and isinstance(self.expected.get('profileSha256'),str)
                    and re.fullmatch('[a-f0-9]{64}',self.expected['profileSha256']), 'model receipt identity differs')
            summary = json.loads(encoded(summary)); release = json.loads(encoded(state['release']))
            source_claims = {key:source[key] for key in ('requestId','sourceScopeSha256','modelKind',
                'inferenceCap','deadlineBudgetMs','maxBatches','maxTotalBytes')}
            source_claims.update(modelIdentitySha256=hashlib.sha256(encoded(source['modelIdentity'])).hexdigest(),
                                 runtimeSha256=hashlib.sha256(encoded(source['runtime'])).hexdigest())
            receipt = {'schema':'consense.fixed-model-session-receipt.v1',
                'operationId':self.operation,'stage':self.stage,'sourceClaims':source_claims,
                'bodySha256':self.expected['bodySha256'],'sourceSha256':self.expected['sourceSha256'],
                'profileSha256':self.expected['profileSha256'],'bindingSha256':self.binding_sha,
                'modelSummarySha256':state['modelSummarySha256'],'summary':summary,'batches':batches,'release':release}
            returned_receipt = json.loads(encoded(receipt))
            self._remaining()
            def accept_finish():
                with self.state_lock:
                    require(not self.closed and not self.cancel_requested.is_set() and time.monotonic() < self.deadline,
                            'model session closed, cancelled or expired')
                    self.exit_receipt = receipt
                    self.released = True; self.closed = True
                    return returned_receipt
            return self._accept(accept_finish)
        except Exception as error:
            self._fail(error)
        finally:
            self.lock.release()

    def cancel(self):
        with self.state_lock:
            if self.closed: return self.exit_receipt
            self.cancel_requested.set()
            self.closed = True
        receipt = self.client.cancel_and_observe(self.operation,self.expected,model_stage=self.stage)
        with self.state_lock:
            self.exit_receipt = receipt
            return receipt
