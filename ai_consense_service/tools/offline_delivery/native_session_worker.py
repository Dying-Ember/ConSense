"""Keep one native API alive for one bound transaction inside its assigned GPU Job."""
import base64
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

from delivery import require
from request_stage import encoded, save, verify_operation


def verify_transaction(transaction, task, store_root):
    require(transaction.get('status') == 'persisted' and transaction.get('taskBinding') == task
            and isinstance(transaction.get('rawOutputs'), dict) and transaction['rawOutputs'], 'complete native transaction was not persisted')
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'vetting_eval'))
    from artifact_store import ArtifactStore
    store = ArtifactStore(store_root)
    require(transaction.get('input', {}).get('sha256') == task['inputSha256']
            and transaction['input'].get('byteCount') == task['inputByteCount'], 'native transaction input identity differs')
    store.verify(transaction['input'])
    for reference in transaction['rawOutputs'].values(): store.verify(reference)
    require(isinstance(transaction.get('attempts'),list) and transaction['attempts'] and all(
        row.get('parser') == task['parser'] and row.get('status') == 'completed' and isinstance(row.get('response'),dict)
        for row in transaction['attempts']), 'actual completed native HTTP attempts required')
    for row in transaction['attempts']: store.verify(row['response'])
    def verify(value):
        if isinstance(value, list):
            for row in value: verify(row)
        elif isinstance(value, dict):
            if value.get('schema') == 'consense.artifact-descriptor.v1': store.verify(value)
            else:
                for row in value.values(): verify(row)
    verify(transaction)
    return transaction


def execute(request_path, result_path):
    request_path, result_path = Path(request_path), Path(result_path)
    raw = request_path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == os.environ.get('CONSENSE_OPERATION_REQUEST_SHA256'), 'native operation request changed')
    operation = verify_operation(json.loads(raw)); profile = operation['profile']
    require(profile.get('adapter') == 'consense.native-api-session.v1' and operation['stage'] in ('paddle', 'mineru')
            and all(os.environ.get(key) for key in ('CONSENSE_JOB_UID', 'CONSENSE_POD_UID', 'CONSENSE_WORKSPACE_PVC_UID')),
            'native API session requires its exact assigned Job profile')
    command, environment = profile.get('command'), profile.get('environment')
    require(isinstance(command, list) and command and all(isinstance(arg, str) and arg and '\x00' not in arg for arg in command)
            and isinstance(environment, dict) and all(isinstance(key, str) and isinstance(value, str) and '\x00' not in key+value for key,value in environment.items()), 'exact native command/environment required')
    timeout = profile.get('timeoutSeconds')
    require(type(timeout) in (int,float) and 0 < timeout <= 86400, 'finite native session deadline required')
    body = json.loads(base64.b64decode(operation['bodyBase64'], validate=True))
    task = body['taskBinding']; marker = request_path.with_name('transaction.json')
    tree = process = None
    try:
        if os.name == 'nt':
            from native_provider_lifecycle import _WindowsTree
            tree = _WindowsTree({'command': command, 'cwd': profile.get('cwd', str(request_path.parent)),
                'env': {**os.environ, **environment}}, sys.stdout.buffer, sys.stderr.buffer)
        else:
            process = subprocess.Popen(command, cwd=profile.get('cwd', str(request_path.parent)),
                env={**os.environ, **environment, 'HF_HUB_OFFLINE':'1', 'TRANSFORMERS_OFFLINE':'1'}, start_new_session=True)
        deadline = time.monotonic()+timeout
        while time.monotonic() < deadline:
            require(tree.active() > 0 if tree else process.poll() is None, 'native server exited before completing its bound transaction')
            if marker.exists():
                transaction = verify_transaction(json.loads(marker.read_bytes()), task, body['artifactStoreRoot'])
                save(result_path, {'contract':'consense.gpu-operation-response.v1', 'bindingSha256':operation['bindingSha256'],
                    'status':200, 'headers':{'content-type':'application/json'}, 'bodyBase64':base64.b64encode(encoded(transaction)).decode()})
                return
            time.sleep(.05)
        raise TimeoutError('native session total deadline elapsed')
    finally:
        if tree is not None:
            try:
                tree.stop(); require(tree.active() == 0, 'native Windows Job descendants remain')
            finally: tree.close()
        if process is not None:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            process.wait(timeout=2)
        # On Linux, only the controller's subsequent Kubernetes Pod readback proves full release.


if __name__ == '__main__':
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'vetting_eval'))
    execute(os.environ['CONSENSE_OPERATION_REQUEST'], os.environ['CONSENSE_OPERATION_RESULT'])
