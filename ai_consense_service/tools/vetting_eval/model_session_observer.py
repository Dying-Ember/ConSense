"""Request-local independent model observations; source text is not retained."""
import copy
import hashlib
import math
import os
import re
import time

from model_session_receipt import describe_batch, verify_model_session_receipt, valid_release_claim
from token_windows import WindowError, digest
from trusted_stage_pins import GPU_CONFIG_PATH, load_trusted_stage_pins


def _trusted_stage_pins(stage):
    try:
        return load_trusted_stage_pins(stage, GPU_CONFIG_PATH,
            os.environ.get('CONSENSE_FIXED_GPU_CONFIG_SHA256'),
            os.environ.get('CONSENSE_FIXED_GPU_PROFILE_MANIFEST'),
            os.environ.get('CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256'))
    except (OSError, ValueError, TypeError, RecursionError):
        return None


class ModelSessionObserver:
    def __init__(self, control, source_sha, stage, proxy, max_batches, max_bytes):
        self.control, self.source_sha, self.stage, self.proxy = control, source_sha, stage, proxy
        self.max_batches, self.max_bytes = max_batches, max_bytes
        self.started = time.monotonic()
        self.records, self.bindings, self.receipt_checks = [], {}, []
        self.preflights = {}
        self._stage_pins = None
        self.release_statuses = {}
        self.operation_counts, self.operation_kinds, self.kind_operations = {}, {}, {}
        self.closed_operations = set()
        self.pending = None

    def trusted_execution_sha256(self):
        if self._stage_pins is None:
            pins = _trusted_stage_pins(self.stage)
            if (not isinstance(pins, dict) or set(pins) != {'profileSha256', 'executionSha256'}
                    or any(not isinstance(value, str) or not re.fullmatch('[a-f0-9]{64}', value)
                           for value in pins.values())):
                raise WindowError('MODEL_SESSION_TRUSTED_PINS_REQUIRED',
                                  'Independent trusted stage and execution pins are unavailable')
            self._stage_pins = copy.deepcopy(pins)
        return self._stage_pins['executionSha256']

    def preflight_session(self, stage, kind):
        if (stage != self.stage or kind not in ('embedding', 'reranker') or kind in self.preflights
                or stage == 'document-embedding' and kind != 'embedding'):
            raise WindowError('MODEL_SESSION_BINDING_INVALID', 'Independent model preflight differs')
        self.trusted_execution_sha256()
        runtime = self.proxy.backend.window_runtime()
        observed = {'modelIdentity': runtime.logical_identity(kind), 'runtime': self.proxy.model_runtime(kind),
                    'inferenceCap': runtime.inference_cap(kind), **self._stage_pins}
        if (not isinstance(observed['modelIdentity'], dict) or not observed['modelIdentity']
                or not isinstance(observed['runtime'], dict) or observed['runtime'].get('device') != 'cuda'
                or type(observed['inferenceCap']) is not int or not 0 < observed['inferenceCap'] <= 8192):
            raise WindowError('MODEL_SESSION_BINDING_INVALID', 'Independent model preflight is invalid')
        self.preflights[kind] = copy.deepcopy(observed)
        return copy.deepcopy(observed)

    def begin_session(self, stage, binding):
        fields={'requestId','sourceScopeSha256','modelKind','modelIdentity','runtime',
                'inferenceCap','deadlineBudgetMs','maxBatches','maxTotalBytes'}
        if not isinstance(binding,dict) or set(binding)!=fields:
            raise WindowError('MODEL_SESSION_BINDING_INVALID', 'Exact request model binding is required')
        kind=binding['modelKind']
        if not isinstance(kind, str):
            raise WindowError('MODEL_SESSION_BINDING_INVALID', 'Model kind must be explicit')
        preflight = self.preflights.get(kind)
        try:
            same_identity = (preflight is not None and digest(binding['modelIdentity']) == digest(preflight['modelIdentity'])
                             and digest(binding['runtime']) == digest(preflight['runtime']))
        except (TypeError, ValueError, UnicodeError, RecursionError) as error:
            raise WindowError('MODEL_SESSION_BINDING_INVALID', 'Model preflight binding is not finite JSON') from error
        used=sum(row['inputBytes']+row['outputBytes'] for row in self.records)
        if (stage!=self.stage or kind not in ('embedding','reranker') or kind in self.bindings or preflight is None
                or stage=='document-embedding' and kind!='embedding'
                or binding['requestId']!=self.control['requestId'] or binding['sourceScopeSha256']!=self.source_sha
                or not same_identity
                or type(binding['inferenceCap']) is not int or binding['inferenceCap']!=preflight['inferenceCap']
                or any(type(binding[name]) is not int or binding[name]<=0
                       for name in ('deadlineBudgetMs','maxBatches','maxTotalBytes'))
                or binding['maxBatches']>self.max_batches-len(self.records)
                or binding['maxTotalBytes']>self.max_bytes-used
                or binding['deadlineBudgetMs']>int(min(self.proxy.timeout,self.control['deadline']-self.started)*1000)):
            raise WindowError('MODEL_SESSION_BINDING_INVALID', 'Model session differs from the independent request scope')
        self.bindings[kind]=copy.deepcopy(binding)

    def bind_operation(self, stage, kind, operation):
        if (stage != self.stage or not isinstance(kind, str) or kind not in self.bindings or kind in self.kind_operations
                or not isinstance(operation, str) or not re.fullmatch('[a-f0-9]{32}', operation)
                or operation in self.operation_kinds):
            raise WindowError('MODEL_SESSION_BINDING_INVALID', 'A unique request-owned model operation is required')
        self.kind_operations[kind] = operation
        self.operation_kinds[operation] = kind
        self.operation_counts[operation] = 0

    def prepare(self, metadata, body, output):
        fields={'scopeOrdinal','ordinal','modelKind','operationId'}
        if (self.pending is not None or not isinstance(metadata,dict) or set(metadata)!=fields
                or type(metadata['scopeOrdinal']) is not int or metadata['scopeOrdinal']!=len(self.records)
                or not isinstance(metadata['modelKind'],str) or metadata['modelKind'] not in self.bindings
                or not isinstance(metadata['operationId'],str)
                or not re.fullmatch('[a-f0-9]{32}',metadata['operationId'])
                or type(metadata['ordinal']) is not int
                or metadata['ordinal']!=self.operation_counts.get(metadata['operationId'],0)
                or self.operation_kinds.get(metadata['operationId'])!=metadata['modelKind']
                or self.kind_operations.get(metadata['modelKind'])!=metadata['operationId']
                or metadata['operationId'] in self.closed_operations):
            raise WindowError('MODEL_BATCH_OBSERVATION_INVALID', 'Exact request-owned model batch observation is required')
        descriptor=describe_batch(metadata['ordinal'],body,output)
        self.pending=(dict(metadata),descriptor)
        return dict(descriptor)

    def commit(self, metadata, record):
        if self.pending is None or self.pending!=(metadata,record):
            raise WindowError('MODEL_BATCH_OBSERVATION_INVALID', 'Model batch observation changed before acceptance')
        self.records.append({**metadata,**record})
        operation=metadata['operationId']
        self.operation_counts[operation]=self.operation_counts.get(operation,0)+1
        self.operation_kinds[operation]=metadata['modelKind']
        self.kind_operations[metadata['modelKind']]=operation
        self.pending = None

    def _verify_gpu_release(self, receipt):
        try:
            operation = receipt['operationId']
            budget = min(1.0, self.proxy.timeout, self.control['deadline'] - time.monotonic())
            if not math.isfinite(budget) or budget <= 0 or not callable(self.control['cancelled']):
                return False
            status = self.proxy.client._call('GET', '/operations/' + operation, budget,
                                             cancelled=self.control['cancelled'])
            if not isinstance(status, dict) or self._stage_pins is None:
                return False
            kind = receipt['sourceClaims']['modelKind']
            binding = self.bindings[kind]
            expected = {
                'operationId': operation,
                'stage': self.stage,
                'status': 'completed',
                'configIdentityScope': 'config_file_bytes',
                'bindingSha256': receipt['bindingSha256'],
                'profileSha256': self._stage_pins['profileSha256'],
                'bodySha256': hashlib.sha256(b'{}').hexdigest(),
                'sourceSha256': digest(binding),
                'modelSummarySha256': receipt['modelSummarySha256'],
            }
            if any(status.get(key) != value for key, value in expected.items()):
                return False
            release = status.get('release')
            if not valid_release_claim(release) or digest(release) != digest(receipt['release']):
                return False
            pinned = load_trusted_stage_pins(
                self.stage, GPU_CONFIG_PATH, status.get('configSha256'),
                os.environ.get('CONSENSE_FIXED_GPU_PROFILE_MANIFEST'),
                os.environ.get('CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256'))
            if pinned == self._stage_pins:
                self.release_statuses[operation] = copy.deepcopy(status)
                return True
            return False
        except Exception:
            # A missing status, failed read or changed identity leaves release unknown.
            return False

    def verify_session(self, receipt):
        if not isinstance(receipt,dict):
            raise WindowError('MODEL_SESSION_RECEIPT_INVALID','Model session receipt is required')
        operation=receipt.get('operationId');claims=receipt.get('sourceClaims',{})
        kind=claims.get('modelKind') if isinstance(claims,dict) else None
        if not isinstance(kind, str) or not isinstance(operation, str) or not re.fullmatch('[a-f0-9]{32}', operation):
            raise WindowError('MODEL_SESSION_RECEIPT_INVALID','Model session receipt identity is invalid')
        binding=self.bindings.get(kind)
        rows=[row for row in self.records if row['operationId']==operation]
        if (binding is None or self.pending is not None or operation in self.closed_operations
                or self.kind_operations.get(kind)!=operation or not rows
                or any(row['modelKind']!=kind for row in rows)
                or len(rows)!=sum(row['modelKind']==kind for row in self.records)):
            raise WindowError('MODEL_SESSION_RECEIPT_INVALID','Independent model batch observations are unavailable')
        descriptors=[{key:row[key] for key in ('ordinal','inputSha256','outputSha256','inputBytes','outputBytes')}
                     for row in rows]
        profile=self.preflights[kind]['profileSha256']
        self.closed_operations.add(operation)
        checked=verify_model_session_receipt(receipt,expected_stage=self.stage,expected_binding=binding,
            expected_profile_sha256=profile,expected_operation_id=operation,batch_descriptors=descriptors)
        self.receipt_checks.append(checked)
        if self._verify_gpu_release(receipt) is not True:
            return False
        return True
