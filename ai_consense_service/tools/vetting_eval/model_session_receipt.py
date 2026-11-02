"""Check a private model-session receipt against independently observed inputs.

The caller must construct the binding and batch descriptors at the owning
request boundary, not copy them from the receipt. This checks consistency only;
it does not attest a GPU release, the execution closure or the caller's observations.
"""
import hashlib
import json
import re

from token_windows import WindowError


MAX_BODY = 64 * 1024 * 1024  # Same wire limit as offline_delivery/request_stage.py.


def _require(condition, message):
    if not condition:
        raise WindowError('MODEL_SESSION_RECEIPT_INVALID', message)


def _encoded(value):
    try:
        return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'),
                          allow_nan=False).encode('utf-8')
    except (TypeError, ValueError, UnicodeError, RecursionError) as error:
        raise WindowError('MODEL_SESSION_RECEIPT_INVALID', 'Value is not canonical finite JSON') from error


def _sha(value):
    return hashlib.sha256(_encoded(value)).hexdigest()


def _hex(value, length):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{%d}' % length, value) is not None


def describe_batch(ordinal, body, output):
    """Build one ephemeral descriptor from actually observed request/result objects."""
    _require(type(ordinal) is int and ordinal >= 0 and isinstance(body, dict) and isinstance(output, dict),
             'A nonnegative ordinal and request/result objects are required')
    body_bytes, output_bytes = _encoded(body), _encoded(output)
    _require(len(body_bytes) <= MAX_BODY and len(output_bytes) <= MAX_BODY, 'A batch exceeds the wire limit')
    return {'ordinal': ordinal, 'inputSha256': hashlib.sha256(body_bytes).hexdigest(),
            'outputSha256': hashlib.sha256(output_bytes).hexdigest(),
            'inputBytes': len(body_bytes), 'outputBytes': len(output_bytes)}


def valid_release_claim(release):
    if (not isinstance(release, dict) or release.get('status') != 'released'
            or type(release.get('cgroupPopulated')) is not int or release['cgroupPopulated'] != 0):
        return False
    if set(release) == {'status', 'cgroupPopulated', 'ownership', 'processWaited'}:
        return release['ownership'] == 'delegated_cgroup_v2' and release['processWaited'] is True
    if set(release) != {'status', 'cgroupPopulated', 'observedGpuPids'}:
        return False
    pids = release['observedGpuPids']
    return (isinstance(pids, list) and bool(pids) and all(type(pid) is int and pid > 0 for pid in pids)
            and len(set(pids)) == len(pids))


def verify_model_session_receipt(receipt, *, expected_stage, expected_binding, expected_profile_sha256,
                                 expected_operation_id, batch_descriptors):
    """Return an identity for a structurally valid receipt, not a GPU attestation."""
    binding_fields = {'requestId', 'sourceScopeSha256', 'modelKind', 'modelIdentity', 'runtime',
                      'inferenceCap', 'deadlineBudgetMs', 'maxBatches', 'maxTotalBytes'}
    _require(expected_stage in ('document-embedding', 'query-embedding-rerank')
             and isinstance(expected_binding, dict) and set(expected_binding) == binding_fields
             and _hex(expected_operation_id, 32) and _hex(expected_profile_sha256, 64),
             'Independent stage, binding, operation and profile identities are required')
    b = expected_binding
    _require(_hex(b['requestId'], 32) and _hex(b['sourceScopeSha256'], 64)
             and b['modelKind'] in ('embedding', 'reranker')
             and (expected_stage != 'document-embedding' or b['modelKind'] == 'embedding')
             and isinstance(b['modelIdentity'], dict) and bool(b['modelIdentity'])
             and isinstance(b['runtime'], dict) and b['runtime'].get('device') == 'cuda'
             and all(type(b[name]) is int and b[name] > 0 for name in
                     ('inferenceCap', 'deadlineBudgetMs', 'maxBatches', 'maxTotalBytes')),
             'Independent source/model binding is invalid')
    fields = {'schema', 'operationId', 'stage', 'sourceClaims', 'bodySha256', 'sourceSha256',
              'profileSha256', 'bindingSha256', 'modelSummarySha256', 'summary', 'batches', 'release'}
    _require(isinstance(receipt, dict) and set(receipt) == fields
             and receipt['schema'] == 'consense.fixed-model-session-receipt.v1'
             and receipt['operationId'] == expected_operation_id and receipt['stage'] == expected_stage
             and receipt['profileSha256'] == expected_profile_sha256
             and receipt['bodySha256'] == hashlib.sha256(b'{}').hexdigest()
             and receipt['sourceSha256'] == _sha(b)
             and _hex(receipt['bindingSha256'], 64) and _hex(receipt['modelSummarySha256'], 64),
             'Receipt operation, body, source or profile identity differs')
    claims = {name: b[name] for name in ('requestId', 'sourceScopeSha256', 'modelKind', 'inferenceCap',
                                         'deadlineBudgetMs', 'maxBatches', 'maxTotalBytes')}
    claims.update(modelIdentitySha256=_sha(b['modelIdentity']), runtimeSha256=_sha(b['runtime']))
    _require(_encoded(receipt['sourceClaims']) == _encoded(claims), 'Receipt source claims differ')
    batches = receipt['batches']
    _require(isinstance(batches, list) and isinstance(batch_descriptors, list)
             and 0 < len(batches) == len(batch_descriptors) <= b['maxBatches'], 'Batch count differs or exceeds budget')
    total = 0
    for ordinal, (batch, observed) in enumerate(zip(batches, batch_descriptors)):
        _require(isinstance(batch, dict) and set(batch) == {'ordinal', 'inputSha256', 'outputSha256'}
                 and isinstance(observed, dict) and set(observed) == {'ordinal', 'inputSha256', 'outputSha256', 'inputBytes', 'outputBytes'}
                 and type(batch['ordinal']) is int and batch['ordinal'] == ordinal
                 and type(observed['ordinal']) is int and observed['ordinal'] == ordinal
                 and _hex(batch['inputSha256'], 64) and _hex(batch['outputSha256'], 64)
                 and batch['inputSha256'] == observed['inputSha256'] and batch['outputSha256'] == observed['outputSha256']
                 and all(type(observed[name]) is int and 0 < observed[name] <= MAX_BODY for name in ('inputBytes', 'outputBytes')),
                 'Batch identity, ordinal or observed size differs')
        total += observed['inputBytes'] + observed['outputBytes']
    _require(total <= b['maxTotalBytes'], 'Observed batch bytes exceed the session budget')
    summary = receipt['summary']
    summary_fields = {'operationId', 'bindingSha256', 'requestId', 'batchCount', 'batchesSha256', 'totalBytes', 'status'}
    _require(isinstance(summary, dict) and set(summary) == summary_fields
             and summary['operationId'] == expected_operation_id and summary['bindingSha256'] == receipt['bindingSha256']
             and summary['requestId'] == b['requestId'] and type(summary['batchCount']) is int
             and summary['batchCount'] == len(batches) and summary['batchesSha256'] == _sha(batches)
             and type(summary['totalBytes']) is int and summary['totalBytes'] == total
             and summary['status'] == 'computation_completed_release_unverified'
             and receipt['modelSummarySha256'] == _sha(summary), 'Model summary identity or budget differs')
    release = receipt['release']
    _require(valid_release_claim(release), 'Release claims are incomplete or invalid')
    return {'receiptSha256': _sha(receipt), 'bindingSha256IndependentlyRecomputed': False,
            'batchObservationIndependentlyAttested': False, 'gpuReleaseIndependentlyVerified': False,
            'executionClosureVerified': False}
