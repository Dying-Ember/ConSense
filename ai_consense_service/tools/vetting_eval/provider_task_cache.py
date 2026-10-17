"""Exact, immutable successful provider tasks over the existing verified CAS.

No inference, retries, model readiness or source-quality claims. The caller
keeps its existing lock and admits a full normalized run only after release.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time

from native_provider_lifecycle import validate_provider


def _bytes(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


def _sha(value):
    return hashlib.sha256(_bytes(value)).hexdigest()


def task_binding(parser, source, mapping, input_identity, provider, options, source_context):
    here = Path(__file__).resolve().parent
    dependencies = [here/name for name in ('complete_ocr.py', 'provider_task_cache.py',
        'structured_parser_evidence.py', 'parser_capture.py', 'paddle_native_capture.py',
        'native_provider_lifecycle.py', 'artifact_store.py')]
    dependencies += [here.parent/'offline_delivery'/name for name in
                     ('native_session_worker.py', 'native_job_provider.py', 'request_stage.py')]
    return {'schema': 'consense.provider-task-binding.v1', 'parser': parser,
        'source': copy.deepcopy(source), 'pageMap': copy.deepcopy(mapping),
        'input': copy.deepcopy(input_identity), 'provider': copy.deepcopy(provider),
        'requestOptions': copy.deepcopy(options), 'sourceContext': copy.deepcopy(source_context),
        'implementation': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in dependencies}}


def _released(lifecycle, provider):
    if not isinstance(lifecycle, dict):
        return False
    receipt = lifecycle.get('exitReceipt', {})
    if not isinstance(receipt, dict) or receipt.get('status') != 'released':
        return False
    if provider.get('ownedBoot') is not None:
        return (lifecycle.get('mode') == 'owned_native' and receipt.get('primaryError') is None
            and receipt.get('deadlineExpired') is False and receipt.get('pid') == lifecycle.get('pid')
            and type(lifecycle.get('pid')) is int and lifecycle['pid'] > 0
            and receipt.get('ownership') == lifecycle.get('ownership')
            and ((receipt.get('ownership') == 'windows_job_kill_on_close' and receipt.get('activeProcesses') == 0)
                 or (receipt.get('ownership') == 'delegated_cgroup_v2' and receipt.get('cgroupPopulated') == 0)))
    if provider.get('ownedJob') is not None:
        return (lifecycle.get('mode') == 'owned_kubernetes_job' and receipt.get('podsAbsent') is True
            and isinstance(receipt.get('jobUid'), str) and bool(receipt['jobUid'])
            and lifecycle.get('taskBinding') == lifecycle.get('transaction', {}).get('taskBinding')
            and isinstance(lifecycle.get('operationBindingSha256'), str)
            and bool(re.fullmatch('[a-f0-9]{64}', lifecycle['operationBindingSha256']))
            and receipt.get('transactionStatus') != 'failed')
    return False


def _verify_transaction(transaction, binding, store):
    directory = str(Path(__file__).resolve().parent.parent/'offline_delivery')
    if directory not in sys.path:
        sys.path.insert(0, directory)
    from native_session_worker import verify_transaction
    task = {'parser': binding['parser'], 'inputSha256': binding['input']['sha256'],
        'inputByteCount': binding['input']['byteCount'], 'originalSourceSha256': binding['source']['sourceSha256'],
        'physicalPageNo': binding['pageMap'][0]['physicalPageNo'],
        'profileSha256': _sha(binding['provider']['identity'])}
    return verify_transaction(transaction, task, store.root)


def _index_path(store, binding):
    directory = store.root/'provider-tasks'
    path = directory/(_sha(binding)+'.json')
    if not path.resolve().is_relative_to(store.root):
        raise ValueError('Provider task index escaped CAS root')
    return path


def load_success(store, binding):
    """Return verified bytes and historical evidence, or None for an absent task.

    Corrupt existing records fail closed and retain their evidence; they are not
    silently deleted, replaced or treated as an empty successful response.
    """
    provider = binding['provider']
    if provider.get('ownedBoot') is None and provider.get('ownedJob') is None:
        return None
    path = _index_path(store, binding)
    if not path.exists():
        return None
    validate_provider(provider)
    with store.validation_session():
        reference = json.loads(path.read_bytes())
        record = json.loads(store.read_asset(reference))
        if (record.get('schema') != 'consense.provider-task-success.v1'
                or record.get('binding') != binding or record.get('taskSha256') != _sha(binding)):
            raise ValueError('Provider success binding differs from exact current task')
        lifecycle = json.loads(store.read_asset(record['lifecycleRef']))
        if not _released(lifecycle, provider):
            raise ValueError('Cached provider owned release is not proven')
        transaction = _verify_transaction(lifecycle.get('transaction', {}), binding, store)
        if transaction['rawOutputs'] != record.get('rawOutputs'):
            raise ValueError('Cached provider output inventory differs from its transaction')
        outputs = {name: store.read_asset(ref) for name, ref in record['rawOutputs'].items()}
        assets = {name: store.read_asset(ref) for name, ref in record['assets'].items()}
        return {'outputs': outputs, 'assets': assets, 'captureMetadata': record.get('captureMetadata'),
                'successRef': reference, 'historicalLifecycleRef': record['lifecycleRef']}


def commit_success(store, binding, outputs, assets, lifecycle, completeness, capture_metadata, *, deadline):
    """Publish one verified success after release; incomplete/remote is no entry."""
    if (time.monotonic() >= deadline or completeness.get('status') != 'complete'
            or not _released(lifecycle, binding['provider'])):
        return None
    validate_provider(binding['provider'])
    transaction = _verify_transaction(lifecycle.get('transaction', {}), binding, store)
    raw = {name: store.put_bytes(content, identity_scope='exact_native_parser_output_bytes')
           for name, content in outputs.items()}
    if raw != transaction['rawOutputs']:
        raise ValueError('Provider cache must preserve the complete transaction output inventory')
    record = {'schema': 'consense.provider-task-success.v1', 'taskSha256': _sha(binding),
        'binding': copy.deepcopy(binding), 'rawOutputs': raw,
        'assets': {name: store.put_bytes(content, identity_scope='exact_provider_task_asset_bytes')
                   for name, content in assets.items()},
        'lifecycleRef': store.put_bytes(_bytes(lifecycle), identity_scope='historical_owned_provider_lifecycle'),
        'captureMetadata': copy.deepcopy(capture_metadata), 'sourceQualityAccepted': False}
    reference = store.put_bytes(_bytes(record), identity_scope='exact_successful_provider_task_record')
    path = _index_path(store, binding)
    path.parent.mkdir(parents=True, exist_ok=True)
    path = _index_path(store, binding)
    if time.monotonic() >= deadline:
        return None
    fd, temporary = tempfile.mkstemp(prefix='.task-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(_bytes(reference)); stream.flush(); os.fsync(stream.fileno())
        if time.monotonic() >= deadline:
            return None
        try:
            os.link(temporary, path)
        except FileExistsError:
            # Keep the first verified success; never overwrite another run.
            load_success(store, binding)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return reference
