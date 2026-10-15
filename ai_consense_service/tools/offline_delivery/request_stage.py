"""One exact HTTP operation per GPU Job; persist, verify and release before returning."""
from contextlib import contextmanager
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import time
import uuid

from delivery import STAGES, asset, documents, require, sha, validate
from gpu_stage import kubectl

MAX_BODY = 64 * 1024 * 1024


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()


def save(path, value):
    with path.open('xb') as stream:
        stream.write(encoded(value)); stream.flush(); os.fsync(stream.fileno())


def envelope(stage, body, source, profile):
    require(stage in STAGES and isinstance(body, bytes) and len(body) <= MAX_BODY, 'finite exact stage request required')
    require(isinstance(source, dict) and source and isinstance(profile, dict) and profile, 'explicit source and runtime profile identities required')
    value = {'contract': 'consense.gpu-operation.v1', 'operationId': uuid.uuid4().hex, 'stage': stage,
             'source': source, 'profile': profile, 'bodySha256': hashlib.sha256(body).hexdigest(),
             'bodyBase64': base64.b64encode(body).decode(), 'profileSha256': hashlib.sha256(encoded(profile)).hexdigest()}
    value['bindingSha256'] = hashlib.sha256(encoded(value)).hexdigest()
    return value


def verify_operation(value):
    unsigned = {key: item for key, item in value.items() if key != 'bindingSha256'}
    require(value.get('contract') == 'consense.gpu-operation.v1' and value.get('stage') in STAGES
            and isinstance(value.get('operationId'), str) and len(value['operationId']) == 32
            and all(char in '0123456789abcdef' for char in value['operationId'])
            and value.get('bindingSha256') == hashlib.sha256(encoded(unsigned)).hexdigest(), 'operation binding changed')
    require(isinstance(value.get('source'), dict) and value['source'] and isinstance(value.get('profile'), dict)
            and value['profile'], 'explicit operation source and profile required')
    body = base64.b64decode(value['bodyBase64'], validate=True)
    require(len(body) <= MAX_BODY and hashlib.sha256(body).hexdigest() == value['bodySha256']
            and value['profileSha256'] == hashlib.sha256(encoded(value['profile'])).hexdigest(), 'operation body or profile changed')
    return value


def verify_response(value, operation):
    require(value.get('contract') == 'consense.gpu-operation-response.v1'
            and value.get('bindingSha256') == operation['bindingSha256']
            and type(value.get('status')) is int and 100 <= value['status'] <= 599
            and isinstance(value.get('headers'), dict) and all(isinstance(key, str) and isinstance(item, str)
                and not any(char in key + item for char in '\r\n\x00') for key, item in value['headers'].items()),
            'operation response identity or HTTP status differs')
    require(len(base64.b64decode(value['bodyBase64'], validate=True)) <= MAX_BODY, 'operation response body exceeds bound')
    return value


@contextmanager
def owner(workspace):
    # The kernel releases this lock on process death; a stale JSON file never grants ownership.
    with (workspace / '.gpu-operation-owner.lock').open('a+b') as stream:
        try:
            if os.name == 'nt':
                import msvcrt
                stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as failure:
            raise ValueError('another coordinator owns the GPU operation workspace') from failure
        try:
            yield
        finally:
            if os.name == 'nt':
                stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def run_request(prepared, artifacts, workspace, operation, expected_manifest, *, runner=kubectl, cancellation=None):
    """Execute one pinned adapter. The CLI boundary is replaceable without granting model acceptance."""
    require(cancellation is None or not cancellation.is_set(), 'GPU operation cancelled before launch')
    operation = verify_operation(copy.deepcopy(operation))
    prepared, workspace = Path(prepared).resolve(), Path(workspace).resolve()
    require(workspace.is_dir(), 'shared persistent workspace must already exist')
    raw = (prepared / 'manifest.json').read_bytes()
    require(hashlib.sha256(raw).hexdigest() == expected_manifest, 'approved manifest SHA differs')
    manifest = validate(json.loads(raw), artifacts)
    adapter = manifest.get('requestStages', {}).get(operation['stage'])
    require(isinstance(adapter, dict) and isinstance(adapter.get('command'), list) and adapter['command']
            and all(isinstance(arg, str) and arg and '\x00' not in arg for arg in adapter['command'])
            and adapter.get('runtimeAssets') and set(adapter['runtimeAssets']).issubset({row['path'] for row in manifest['assets']}),
            'pinned request adapter command and runtime assets required')
    require(adapter.get('profile') == operation['profile'], 'operation runtime profile differs from approved adapter')
    with owner(workspace):
        directory = workspace / 'gpu-operations' / operation['operationId']
        directory.mkdir(parents=True, exist_ok=False)
        request_path, result_path = directory/'request.json', directory/'response.json'
        save(request_path, operation)
        derivative = copy.deepcopy(manifest)
        settings = next(row for row in derivative['stages'] if row['name'] == operation['stage'])
        settings.update(command=adapter['command'], runtimeAssets=adapter['runtimeAssets'],
                        outputs=[result_path.relative_to(workspace).as_posix()])
        worker_manifest = directory/'worker-manifest.json'; save(worker_manifest, derivative)
        worker_sha = sha(worker_manifest)
        template = next(doc for doc in documents(derivative, worker_sha) if doc.get('kind') == 'Job'
                        and doc['metadata']['labels']['consense-stage'] == operation['stage'])
        name = 'consense-op-' + operation['operationId']
        template['metadata']['name'] = name
        template['metadata']['annotations']['consense-operation-binding'] = operation['bindingSha256']
        pod = template['spec']['template']['spec']; container = pod['containers'][0]
        container['command'] = ['python', '/app/offline_delivery/stage_worker.py', '--manifest', str(worker_manifest),
                                '--artifacts', str(Path(artifacts).resolve()), '--workspace', str(workspace), '--stage', operation['stage']]
        receipt_path = directory/'stage.receipt.json'
        env = {row['name']: row for row in container['env']}
        for key, value in {'CONSENSE_STAGE_RECEIPT': str(receipt_path), 'CONSENSE_OPERATION_REQUEST': str(request_path),
                'CONSENSE_OPERATION_REQUEST_SHA256': sha(request_path), 'CONSENSE_OPERATION_RESULT': str(result_path),
                'CONSENSE_OPERATION_MANIFEST': str(worker_manifest)}.items():
            env[key] = {'name': key, 'value': value}
        command = ['kubectl', '-n', manifest['namespace']]
        require(cancellation is None or not cancellation.is_set(), 'GPU operation cancelled before launch')
        pvc_uid = runner(command + ['get', 'pvc', 'consense-workspace', '-o', 'json']).get('metadata', {}).get('uid')
        require(pvc_uid, 'actual shared workspace PVC identity required')
        require(not runner(command + ['get', 'pods', '-l', 'consense-gpu-owner', '-o', 'json']).get('items'),
                'previous GPU owner still exists; no next request may load')
        env['CONSENSE_WORKSPACE_PVC_UID'] = {'name': 'CONSENSE_WORKSPACE_PVC_UID', 'value': pvc_uid}
        for key, path in {'CONSENSE_POD_UID': 'metadata.uid', 'CONSENSE_JOB_UID': "metadata.labels['batch.kubernetes.io/controller-uid']"}.items():
            env[key] = {'name': key, 'valueFrom': {'fieldRef': {'fieldPath': path}}}
        container['env'] = list(env.values())
        job_uid, result, release = None, None, {'status': 'creation_not_attempted', 'podsAbsent': False}
        ledger = {'contract': 'consense.gpu-operation-receipt.v1', 'bindingSha256': operation['bindingSha256'],
                  'manifestSha256': expected_manifest, 'workerManifestSha256': worker_sha, 'targetAccepted': False}
        try:
            require(cancellation is None or not cancellation.is_set(), 'GPU operation cancelled before launch')
            release = {'status': 'creation_or_release_unknown', 'podsAbsent': False}
            job_uid = runner(command + ['create', '-f', '-', '-o', 'json'], json.dumps(template)).get('metadata', {}).get('uid')
            require(job_uid, 'created operation Job UID unavailable')
            deadline = time.monotonic() + settings['timeoutSeconds']
            while time.monotonic() < deadline:
                job = runner(command + ['get', 'job', name, '-o', 'json'])
                require(job.get('metadata', {}).get('uid') == job_uid, 'operation Job UID changed')
                if job.get('status', {}).get('failed'):
                    raise ValueError('GPU operation Job failed; outputs do not admit a next stage')
                if job.get('status', {}).get('succeeded') == 1:
                    break
                time.sleep(min(.1, max(0, deadline-time.monotonic())))
            else:
                raise TimeoutError('finite GPU operation deadline elapsed')
            receipt = json.loads(receipt_path.read_bytes())
            require(receipt.get('contract') == 'consense-gpu-stage-result-v1' and receipt.get('status') == 'persisted'
                    and receipt.get('manifestSha256') == worker_sha and receipt.get('stage') == operation['stage']
                    and receipt.get('jobUid') == job_uid and receipt.get('pvcUid') == pvc_uid
                    and receipt.get('operationBindingSha256') == operation['bindingSha256']
                    and len(receipt.get('outputs', [])) == 1, 'persisted operation worker receipt differs')
            pods = runner(command + ['get', 'pods', '-l', 'job-name='+name, '-o', 'json']).get('items', [])
            require(len(pods) == 1 and pods[0].get('metadata', {}).get('uid') == receipt.get('podUid')
                    and any(row.get('uid') == job_uid for row in pods[0].get('metadata', {}).get('ownerReferences', [])),
                    'worker receipt does not identify the actual operation Job Pod')
            require(asset(workspace, receipt['outputs'][0]) == result_path, 'operation worker persisted another result')
            require(sha(request_path) == env['CONSENSE_OPERATION_REQUEST_SHA256']['value'], 'exact operation request changed during execution')
            result = verify_response(json.loads(result_path.read_bytes()), operation)
            ledger.update(status='persisted', jobUid=job_uid, pvcUid=pvc_uid, responseSha256=sha(result_path),
                          workerReceiptSha256=sha(receipt_path))
        except BaseException as failure:
            ledger.update(status='failed', failureType=type(failure).__name__)
            raise
        finally:
            try:
                if job_uid is not None:
                    release = {'status': 'release_unknown', 'podsAbsent': False, 'jobUid': job_uid}
                    current = runner(command + ['get', 'job', name, '-o', 'json'])
                    require(current.get('metadata', {}).get('uid') == job_uid, 'cannot release a replaced GPU operation Job')
                    runner(command + ['delete', 'job', name, '--cascade=foreground', '--wait=true', '--timeout=120s'])
                    runner(command + ['wait', '--for=delete', 'pod', '-l', 'job-name='+name, '--timeout=120s'])
                    require(not runner(command + ['get', 'pods', '-l', 'job-name='+name, '-o', 'json']).get('items'),
                            'GPU operation owner Pod still exists after deletion')
                    release = {'status': 'released', 'podsAbsent': True, 'jobUid': job_uid}
            finally:
                ledger['release'] = release; save(directory/'operation.receipt.json', ledger)
        require(release['status'] == 'released', 'GPU release not proven; response cannot admit another stage')
        return {**result, 'release': release, 'operationReceiptSha256': sha(directory/'operation.receipt.json')}


def execute_operation(request_path, artifacts, workspace):
    """The assigned immutable worker manifest, never an HTTP caller, selects the command."""
    import stage_worker
    manifest_path = Path(os.environ['CONSENSE_OPERATION_MANIFEST'])
    operation = verify_operation(json.loads(Path(request_path).read_bytes()))
    return stage_worker.execute(manifest_path, artifacts, workspace, operation['stage'])
