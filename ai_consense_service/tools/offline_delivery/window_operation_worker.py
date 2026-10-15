"""GPU Job adapter for the existing complete index/retrieval API, never a second pipeline."""
import base64
import hashlib
import json
import os
from pathlib import Path
import sys

from delivery import require
from request_stage import encoded, save, verify_operation, verify_response


def execute(request_path, result_path):
    raw = Path(request_path).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == os.environ.get('CONSENSE_OPERATION_REQUEST_SHA256'), 'assigned operation request changed')
    operation = verify_operation(json.loads(raw))
    require(operation['stage'] in ('document-embedding', 'query-embedding-rerank'), 'window adapter does not execute this GPU stage')
    require(all(os.environ.get(key) for key in ('CONSENSE_JOB_UID', 'CONSENSE_POD_UID', 'CONSENSE_WORKSPACE_PVC_UID')),
            'window operation requires its assigned Kubernetes Job and shared PVC identities')
    profile = operation['profile']
    require(profile.get('adapter') == 'consense.window-runtime.v1' and isinstance(profile.get('environment'), dict), 'explicit window runtime profile required')
    environment = profile['environment']
    require(environment.get('CONSENSE_MODEL_DEVICE') == 'cuda' and environment.get('CONSENSE_MODEL_DTYPE') in ('float32', 'float16')
            and environment.get('CONSENSE_MODEL_OFFLINE') == '1'
            and environment.get('CONSENSE_QDRANT_URL')
            and environment.get('CONSENSE_WORKSPACE_ROOT') == '/workspace'
            and environment.get('CONSENSE_RETRIEVAL_DATA', '').startswith('/workspace/')
            and all(isinstance(key, str) and isinstance(value, str) and '\x00' not in key + value
                and not key.startswith(('CONSENSE_OPERATION_', 'CONSENSE_GPU_STAGE_', 'CONSENSE_JOB_', 'CONSENSE_POD_', 'CONSENSE_MANIFEST_'))
                for key, value in environment.items()), 'offline CUDA window environment, persistent state and Server URL must be exact and cannot override operation ownership')
    os.environ.update(environment)
    # Loading remains inside the GPU Job. CPU coordinator HTTP routes must never recurse here.
    for key in ('CONSENSE_GPU_STAGE_PREPARED', 'CONSENSE_GPU_STAGE_ARTIFACTS', 'CONSENSE_GPU_STAGE_WORKSPACE', 'CONSENSE_GPU_STAGE_MANIFEST_SHA256'):
        os.environ.pop(key, None)
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'vetting_eval'))
    import server
    from coordinator import health_identity
    from fastapi import HTTPException
    value = json.loads(base64.b64decode(operation['bodyBase64'], validate=True))
    response = {'contract': 'consense.gpu-operation-response.v1', 'bindingSha256': operation['bindingSha256'],
                'headers': {'content-type': 'application/json'}, 'status': 200}
    require(health_identity(server.health()) == operation['source'].get('expectedHealthSha256'), 'actual worker health recipe differs from coordinator declaration')
    try:
        if operation['stage'] == 'document-embedding':
            body = server.index(server.IndexRequest.model_validate(value))
        else:
            body = server.retrieve(server.RetrieveRequest.model_validate(value))
    except HTTPException as error:
        response['status'] = error.status_code; body = {'detail': error.detail}
    require(health_identity(server.health()) == operation['source'].get('expectedHealthSha256'), 'actual worker health recipe changed during execution')
    response['bodyBase64'] = base64.b64encode(encoded(body)).decode()
    verify_response(response, operation)
    require(hashlib.sha256(Path(request_path).read_bytes()).hexdigest() == os.environ['CONSENSE_OPERATION_REQUEST_SHA256'], 'operation source request changed before persistence')
    save(Path(result_path), response)
    return response


if __name__ == '__main__':
    execute(os.environ['CONSENSE_OPERATION_REQUEST'], os.environ['CONSENSE_OPERATION_RESULT'])
