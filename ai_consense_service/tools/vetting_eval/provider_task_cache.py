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


# This pin approves only the exact release-validation change in these two files.
_OWNED_FIXED_SELF_PIN = '3d08c47d247b3a1c48409acd07d709f4f712cf36db85aa2527cac9a32478c6d0'
_OWNED_FIXED_PROVIDER_SHA = '625aa6ca047aa3544513d3805e2b09655e20ebb7185e7304d6c08c7eff87d772'
_OWNED_FIXED_OLD_SELF_SHA = 'fcbd551173dee4f39390b81eb3af4a48abd39baa1244fb05ea203112ce05826f'
_OWNED_FIXED_OLD_PROVIDER_SHA = '67a0d1a2b43f6549ee7296446ed670cf04fda732df21416147cae93b3960229b'


def _approved_owned_fixed_recipe(raw, provider_sha):
    pin = ("_OWNED_FIXED_SELF_PIN = '" + _OWNED_FIXED_SELF_PIN + "'").encode('ascii')
    return (provider_sha == _OWNED_FIXED_PROVIDER_SHA and raw.count(pin) == 1
            and hashlib.sha256(raw.replace(pin, b"_OWNED_FIXED_SELF_PIN = '<self>'", 1)).hexdigest()
            == _OWNED_FIXED_SELF_PIN)


def task_binding(parser, source, mapping, input_identity, provider, options, source_context):
    here = Path(__file__).resolve().parent
    dependencies = [here/name for name in ('complete_ocr.py', 'provider_task_cache.py',
        'structured_parser_evidence.py', 'parser_capture.py', 'paddle_native_capture.py',
        'native_provider_lifecycle.py', 'artifact_store.py')]
    dependencies += [here.parent/'offline_delivery'/name for name in
                     ('native_session_worker.py', 'native_job_provider.py', 'request_stage.py')]
    if provider.get('ownedFixed') is not None:
        dependencies.append(here.parent/'offline_delivery'/'fixed_gpu_provider.py')
    implementation = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in dependencies}
    if (provider.get('ownedFixed') is not None
            and _approved_owned_fixed_recipe(Path(__file__).read_bytes(), implementation['fixed_gpu_provider.py'])):
        implementation['provider_task_cache.py'] = _OWNED_FIXED_OLD_SELF_SHA
        implementation['fixed_gpu_provider.py'] = _OWNED_FIXED_OLD_PROVIDER_SHA
    return {'schema': 'consense.provider-task-binding.v1', 'parser': parser,
        'source': copy.deepcopy(source), 'pageMap': copy.deepcopy(mapping),
        'input': copy.deepcopy(input_identity), 'provider': copy.deepcopy(provider),
        'requestOptions': copy.deepcopy(options), 'sourceContext': copy.deepcopy(source_context),
        'implementation': implementation}


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
    if provider.get('ownedFixed') is not None:
        operation = lifecycle.get('operationId')
        directory = str(Path(__file__).resolve().parent.parent/'offline_delivery')
        if directory not in sys.path:
            sys.path.insert(0, directory)
        from fixed_gpu_client import gpu_release_proven
        release = {key:value for key,value in receipt.items()
                   if key not in ('operationId', 'transactionStatus', 'transactionSha256')}
        return (lifecycle.get('mode') == 'owned_fixed_gpu'
            and isinstance(operation, str) and bool(re.fullmatch('[a-f0-9]{32}', operation))
            and receipt.get('operationId') == operation and gpu_release_proven(release, owned_tree=True)
            and receipt.get('transactionStatus') == 'persisted'
            and receipt.get('transactionSha256') == _sha(lifecycle.get('transaction', {}))
            and lifecycle.get('taskBinding') == lifecycle.get('transaction', {}).get('taskBinding')
            and isinstance(lifecycle.get('operationBindingSha256'), str)
            and bool(re.fullmatch('[a-f0-9]{64}', lifecycle['operationBindingSha256'])))
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
    if all(provider.get(key) is None for key in ('ownedBoot', 'ownedJob', 'ownedFixed')):
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
