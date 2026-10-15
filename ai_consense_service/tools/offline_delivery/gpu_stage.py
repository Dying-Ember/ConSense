"""Plan a single GPU handoff; execute only with --execute and a pinned prepared manifest."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess

from delivery import STAGES, asset, documents, require, sha, validate


def kubectl(argv, payload=None):
    result = subprocess.run(argv, input=payload, text=True, capture_output=True, check=True, timeout=150)
    return json.loads(result.stdout) if result.stdout.strip().startswith('{') else {}


def handoff(prepared, artifacts, workspace, stage, expected_manifest, previous_receipt=None, execute=False, runner=kubectl):
    prepared = Path(prepared)
    raw = (prepared / 'manifest.json').read_bytes()
    require(hashlib.sha256(raw).hexdigest() == expected_manifest, 'prepared manifest differs from the approved SHA256')
    preparation = json.loads((prepared / 'preparation.json').read_bytes())
    require(preparation['manifestSha256'] == expected_manifest and sha(prepared / 'kubernetes.json') == preparation['kubernetesSha256'], 'prepared resource bytes changed')
    manifest = validate(json.loads(raw), artifacts)
    require(stage in STAGES, 'unknown GPU stage')
    job_path = prepared / 'gpu-jobs' / (stage + '.json')
    require(sha(job_path) == preparation['gpuJobSha256'][stage], 'prepared GPU job bytes changed')
    template = json.loads(job_path.read_bytes())
    require(template == next(doc for doc in documents(manifest, expected_manifest) if doc.get('kind') == 'Job'
                             and doc['metadata']['labels']['consense-stage'] == stage), 'GPU job differs from the pinned manifest')
    index = STAGES.index(stage)
    receipt = None
    if index:
        require(previous_receipt is not None, 'persisted previous stage receipt required')
        receipt = json.loads(Path(previous_receipt).read_bytes())
        require(receipt.get('contract') == 'consense-gpu-stage-result-v1' and receipt.get('manifestSha256') == expected_manifest
                and receipt.get('stage') == STAGES[index - 1] and receipt.get('status') == 'persisted'
                and receipt.get('jobUid') and receipt.get('pvcUid') and receipt.get('outputs'), 'previous stage result identity or persistence incomplete')
        for row in receipt['outputs']:
            asset(workspace, row)
    name = 'consense-' + manifest['release'] + '-' + stage
    plan = {'stage': stage, 'jobName': name, 'manifestSha256': expected_manifest, 'executed': False,
            'previousStage': STAGES[index - 1] if index else None,
            'boundary': 'GPU release requires deletion and readback of every previous owner pod, never a sleep.'}
    if not execute:
        return plan
    command = ['kubectl', '-n', manifest['namespace']]
    pvc = runner(command + ['get', 'pvc', 'consense-workspace', '-o', 'json'])
    pvc_uid = pvc.get('metadata', {}).get('uid')
    require(pvc_uid, 'workspace PVC identity unavailable')
    if index:
        require(pvc_uid == receipt['pvcUid'], 'previous output belongs to another workspace PVC')
        previous = 'consense-' + manifest['release'] + '-' + STAGES[index - 1]
        job = runner(command + ['get', 'job', previous, '-o', 'json'])
        require(job.get('metadata', {}).get('uid') == receipt['jobUid'] and job.get('status', {}).get('succeeded') == 1
                and job.get('metadata', {}).get('annotations', {}).get('consense-manifest-sha256') == expected_manifest,
                'previous job must have succeeded for the exact persisted receipt')
        runner(command + ['delete', 'job', previous, '--cascade=foreground', '--wait=true', '--timeout=120s'])
        runner(command + ['wait', '--for=delete', 'pod', '-l', 'job-name=' + previous, '--timeout=120s'])
    owners = runner(command + ['get', 'pods', '-l', 'consense-gpu-owner', '-o', 'json'])
    require(not owners.get('items'), 'previous GPU owner pod still exists; next stage cannot load')
    job = copy.deepcopy(template)
    container = job['spec']['template']['spec']['containers'][0]
    container['env'].extend([
        {'name': 'CONSENSE_WORKSPACE_PVC_UID', 'value': pvc_uid},
        {'name': 'CONSENSE_POD_UID', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.uid'}}},
        {'name': 'CONSENSE_JOB_UID', 'valueFrom': {'fieldRef': {'fieldPath': "metadata.labels['batch.kubernetes.io/controller-uid']"}}},
    ])
    result = runner(command + ['create', '-f', '-', '-o', 'json'], json.dumps(job))
    require(result.get('metadata', {}).get('uid'), 'created GPU job identity unavailable')
    plan.update(executed=True, jobUid=result['metadata']['uid'], pvcUid=pvc_uid, targetAccepted=False)
    return plan


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepared', required=True)
    parser.add_argument('--artifacts', required=True)
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--stage', choices=STAGES, required=True)
    parser.add_argument('--expected-manifest-sha256', required=True)
    parser.add_argument('--previous-receipt')
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    print(json.dumps(handoff(args.prepared, args.artifacts, args.workspace, args.stage,
                            args.expected_manifest_sha256, args.previous_receipt, args.execute), indent=2))
