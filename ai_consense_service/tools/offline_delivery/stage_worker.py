"""Run one manifest-bound worker and persist complete declared outputs before returning."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

from delivery import STAGES, require, sha, validate


def execute(manifest_path, artifacts, workspace, stage):
    raw = Path(manifest_path).read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    require(digest == os.environ.get('CONSENSE_MANIFEST_SHA256'), 'worker manifest differs from its assigned SHA256')
    manifest = validate(json.loads(raw), artifacts)
    require(stage in STAGES, 'unknown stage')
    settings = next(row for row in manifest['stages'] if row['name'] == stage)
    workspace = Path(workspace).resolve()
    require(workspace.is_dir(), 'persistent workspace must already be mounted')
    identities = {key: os.environ.get(env) for key, env in {'jobUid': 'CONSENSE_JOB_UID', 'podUid': 'CONSENSE_POD_UID', 'pvcUid': 'CONSENSE_WORKSPACE_PVC_UID'}.items()}
    require(all(identities.values()), 'actual job, pod and PVC identities required')
    receipt_path = Path(os.environ.get('CONSENSE_STAGE_RECEIPT', '')).resolve()
    require(receipt_path.is_relative_to(workspace) and receipt_path != workspace and not receipt_path.exists(), 'fresh stage receipt required within the workspace')
    outputs = []
    for relative in settings['outputs']:
        path = (workspace / relative).resolve()
        require(path.is_relative_to(workspace) and not path.exists() and path != receipt_path, 'stage output must be fresh within the workspace')
        outputs.append(path)
    operation = None
    if os.environ.get('CONSENSE_OPERATION_REQUEST'):
        from request_stage import verify_operation
        request_path = Path(os.environ['CONSENSE_OPERATION_REQUEST']).resolve()
        require(request_path.is_relative_to(workspace) and sha(request_path) == os.environ.get('CONSENSE_OPERATION_REQUEST_SHA256'), 'operation request bytes changed')
        operation = verify_operation(json.loads(request_path.read_bytes()))
        require(operation['stage'] == stage and len(outputs) == 1
                and Path(os.environ.get('CONSENSE_OPERATION_RESULT', '')).resolve() == outputs[0], 'operation stage or result path differs')
    log_root = workspace / ('stage-' + identities['jobUid'])
    require(log_root.resolve().is_relative_to(workspace) and not log_root.exists(), 'fresh owned stage log directory required')
    log_root.mkdir()
    started = time.monotonic()
    receipt = {'contract': 'consense-gpu-stage-result-v1', 'manifestSha256': digest, 'stage': stage, **identities,
               'imageDigest': manifest['images'][stage], 'imageIdentityScope': 'configured_pod_image_digest',
               'commandSha256': hashlib.sha256(json.dumps(settings['command'], separators=(',', ':')).encode()).hexdigest(),
               'status': 'failed', 'targetAccepted': False, 'outputs': [], 'logs': []}
    with (log_root / 'stdout.log').open('xb') as stdout, (log_root / 'stderr.log').open('xb') as stderr:
        try:
            result = subprocess.run(settings['command'], cwd=workspace, stdout=stdout, stderr=stderr,
                                    check=False, timeout=settings['timeoutSeconds'], env={**os.environ, 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'})
            receipt['exitCode'] = result.returncode
            require(result.returncode == 0, 'worker failed; retained outputs cannot admit the next GPU stage')
            require(all(path.is_file() for path in outputs), 'worker did not produce every complete declared output')
            require(all(not path.is_symlink() and path.resolve() == path and path.resolve().is_relative_to(workspace) for path in outputs), 'stage output path changed or escaped its assigned workspace')
            if operation is not None:
                from request_stage import verify_response, MAX_BODY
                require(outputs[0].stat().st_size <= 2*MAX_BODY, 'complete operation response record exceeds byte cap')
                require(sha(request_path) == os.environ['CONSENSE_OPERATION_REQUEST_SHA256'], 'operation request changed during execution')
                verify_response(json.loads(outputs[0].read_bytes()), operation)
                receipt['operationBindingSha256'] = operation['bindingSha256']
            for path in outputs:
                with path.open('r+b') as stream:
                    os.fsync(stream.fileno())
                receipt['outputs'].append({'path': path.relative_to(workspace).as_posix(), 'bytes': path.stat().st_size, 'sha256': sha(path)})
            require(all(row['bytes'] > 0 for row in receipt['outputs']), 'empty output cannot attest a persisted stage')
            receipt['status'] = 'persisted'
        except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as failure:
            receipt['errorType'] = type(failure).__name__
            receipt['error'] = str(failure) if not isinstance(failure, subprocess.TimeoutExpired) else 'Finite worker timeout; stage remains failed'
        finally:
            stdout.flush(); stderr.flush(); os.fsync(stdout.fileno()); os.fsync(stderr.fileno())
    receipt['seconds'] = time.monotonic() - started
    for path in log_root.iterdir():
        receipt['logs'].append({'path': path.relative_to(workspace).as_posix(), 'bytes': path.stat().st_size, 'sha256': sha(path)})
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    with receipt_path.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(receipt, indent=2) + '\n'); stream.flush(); os.fsync(stream.fileno())
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--artifacts', required=True)
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--stage', choices=STAGES, required=True)
    args = parser.parse_args()
    result = execute(args.manifest, args.artifacts, args.workspace, args.stage)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['status'] == 'persisted' else 1)
