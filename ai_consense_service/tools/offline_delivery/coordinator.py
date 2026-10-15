"""Existing CPU HTTP routes hand exact finite index/query operations to GPU Jobs."""
import base64
import hashlib
import json
import os
from pathlib import Path

from delivery import require, validate
from request_stage import encoded, envelope, run_request

NAMES = ('CONSENSE_GPU_STAGE_PREPARED', 'CONSENSE_GPU_STAGE_ARTIFACTS',
         'CONSENSE_GPU_STAGE_WORKSPACE', 'CONSENSE_GPU_STAGE_MANIFEST_SHA256')


def configured(environ):
    present = [bool(environ.get(name)) for name in NAMES]
    require(not any(present) or all(present), 'complete GPU coordinator configuration required')
    return all(present)


def settings(environ):
    require(configured(environ), 'GPU coordinator is not configured')
    prepared, artifacts, workspace = (Path(environ[name]).resolve() for name in NAMES[:3])
    expected = environ[NAMES[3]]
    raw = (prepared/'manifest.json').read_bytes()
    require(hashlib.sha256(raw).hexdigest() == expected, 'coordinator approved manifest changed')
    return prepared, artifacts, workspace, expected, validate(json.loads(raw), artifacts)


def execute(stage, value, environ=None, *, server=None):
    selected = os.environ if environ is None else environ
    prepared, artifacts, workspace, expected, manifest = settings(selected)
    adapter = manifest.get('requestStages', {}).get(stage)
    require(isinstance(adapter, dict) and isinstance(adapter.get('profile'), dict), 'pinned concrete request stage profile required')
    source = {'projectId': str(value.get('projectId', '')), 'sourceIdentityScope': 'actual_coordinator_API_request_backend_source_guards_remain_required'}
    source['expectedHealthSha256'] = health_identity(health(selected, server))
    if stage == 'document-embedding':
        source['originalSourceSha256s'] = sorted({chunk['originalSourceSha256'] for chunk in value.get('chunks', [])
            if isinstance(chunk.get('originalSourceSha256'), str)})
    request = envelope(stage, encoded(value), source, adapter['profile'])
    response = run_request(prepared, artifacts, workspace, request, expected)
    return response['status'], json.loads(base64.b64decode(response['bodyBase64'], validate=True))


def execute_wire(path, body, environ=None):
    selected = os.environ if environ is None else environ
    prepared, artifacts, workspace, expected, manifest = settings(selected)
    adapter = manifest.get('requestStages',{}).get('business-llm',{})
    profile = adapter.get('profile',{})
    require(profile.get('adapter') == 'consense.native-chat.v1' and path in profile.get('apiPaths',{}), 'explicit native business API adapter required')
    value = json.loads(body)
    require(value.get('model') == profile.get('model'), 'native business request selected another model')
    if value.get('stream') is True:
        return 409, {'content-type':'application/json'}, encoded({'error':{'message':'This finite GPU Job profile provides status and final output; live body streaming is unavailable.'}})
    operation = envelope('business-llm',body,{'httpPath':path,'sourceIdentityScope':'exact_business_wire_backend_fresh_source_guards_remain_required'},profile)
    result = run_request(prepared,artifacts,workspace,operation,expected)
    return result['status'], result['headers'], base64.b64decode(result['bodyBase64'],validate=True)


def model_catalog(environ=None):
    _, _, _, _, manifest = settings(os.environ if environ is None else environ)
    profile = manifest.get('requestStages', {}).get('business-llm', {}).get('profile', {})
    model = profile.get('model')
    require(profile.get('adapter') == 'consense.native-chat.v1' and isinstance(model, str) and model.strip(),
            'pinned native business model required for configured catalog')
    return {'object': 'list', 'data': [{'id': model, 'object': 'model', 'owned_by': 'configured-offline-profile'}],
            'modelReady': False, 'targetAccepted': False,
            'runtimeIdentityScope': 'configured_model_only_each_request_requires_verified_GPU_Job'}


def health_identity(value):
    return hashlib.sha256(encoded({key:value[key] for key in ('models','runtime','retrieval','indexSignatureVersion','offline')})).hexdigest()


def health(environ=None, server=None):
    _, _, _, expected, manifest = settings(os.environ if environ is None else environ)
    require(server is not None, 'existing inference-free server health seam required')
    profile = manifest.get('requestStages',{}).get('query-embedding-rerank',{}).get('profile',{})
    environment = profile.get('environment',{})
    require(environment.get('CONSENSE_MODEL_DEVICE') == 'cuda', 'coordinator must declare the actual GPU worker profile')
    declared = {}
    for kind, prefix in (('embedding','CONSENSE_EMBED'),('reranker','CONSENSE_RERANK')):
        declared[kind] = {**server.MODELS[kind], 'maxTokens':int(environment.get(prefix+'_MAX_TOKENS',server.MODEL_SETTINGS[kind]['maxTokens'])),
            'batchSize':int(environment.get(prefix+'_BATCH_SIZE',server.MODEL_SETTINGS[kind]['batchSize'])),
            'device':'cuda','dtype':environment.get('CONSENSE_MODEL_DTYPE','float32'),
            'serialOffload':environment.get('CONSENSE_MODEL_SERIAL_OFFLOAD','1') == '1'}
    # The shared health recipe is explicitly inference-free; only declared worker fields differ from the CPU coordinator.
    with server.LOCK:
        original_runtime, original_settings = server.model_runtime, server.MODEL_SETTINGS
        try:
            server.model_runtime = lambda kind: declared[kind]
            server.MODEL_SETTINGS = {kind:{key:declared[kind][key] for key in ('maxTokens','batchSize')} for kind in declared}
            retrieval = server.retrieval_settings(observe_tokenizer_identity=False)
            version = server.window_runtime().signature_version()
        finally:
            server.model_runtime, server.MODEL_SETTINGS = original_runtime, original_settings
    return {'status': 'ok', 'mode': 'gpu_job_coordinator', 'manifestSha256': expected,
            'offline':True,'models':server.MODELS,'runtime':declared,'retrieval':retrieval,'indexSignatureVersion':version,
            'configuredStages': sorted(manifest.get('requestStages', {})), 'modelReady': False,
            'targetAccepted': False, 'runtimeIdentityScope':'configured_worker_profile_checked_against_actual_worker_health_before_execution',
            'limits': ['Wire readiness only; each actual request requires a persisted GPU Job result and verified Pod release']}
