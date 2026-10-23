"""The existing complete OCR transaction owns one Kubernetes native session, then releases it."""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import threading
import time
from urllib.parse import urlsplit

from delivery import require, validate
from gpu_stage import kubectl
from request_stage import encoded, envelope, run_request, save, sha


def configuration(provider):
    boot = provider['ownedJob']; raw = (Path(boot['prepared'])/'manifest.json').read_bytes()
    require(sha(Path(boot['prepared'])/'manifest.json') == boot['manifestSha256'], 'native Job approved manifest changed')
    manifest = validate(json.loads(raw), boot['artifacts']); stage = boot['stage']
    require(stage in ('paddle','mineru') and not provider.get('ownedBoot'), 'native provider requires one Job ownership mode')
    service = manifest.get('nativeServices', {}).get(stage); adapter = manifest.get('requestStages', {}).get(stage)
    require(service and adapter and adapter.get('profile', {}).get('adapter') == 'consense.native-api-session.v1'
            and service['name'] == boot['serviceName'] and adapter['profile'].get('nativeIdentity') == provider.get('identity'),
            'pinned native session Service, model identity and adapter required')
    url = urlsplit(provider['baseUrl'])
    require(url.scheme == 'http' and url.hostname == service['clusterIP'] and url.port == service['port'], 'native API endpoint differs from pinned Service')
    return boot, manifest, service, adapter


@contextmanager
def owned_gpu_provider(provider, remaining_seconds, receipt_dir, *, runner=kubectl, ready=None, cancelled=None):
    from native_provider_lifecycle import ProviderLifecycleError, _finite, _wire_ready, _watch_cancellation
    deadline = time.monotonic() + _finite(remaining_seconds, 'remaining_seconds')
    boot, manifest, service, adapter = configuration(provider)
    _finite(remaining_seconds, 'remaining_seconds'); _finite(boot.get('cleanupTimeoutSeconds'), 'cleanupTimeoutSeconds', 360)
    task = provider.get('taskBinding')
    require(isinstance(task, dict) and task.get('parser') in ('paddleocr-vl','mineru') and task.get('inputSha256')
            and task.get('originalSourceSha256') and type(task.get('physicalPageNo')) is int, 'actual native input/source/page task binding required')
    require(task['parser'] == ('paddleocr-vl' if boot['stage'] == 'paddle' else 'mineru')
            and task.get('profileSha256') == hashlib.sha256(encoded(provider['identity'])).hexdigest(), 'native source task parser or profile differs')
    store_root = Path(receipt_dir).resolve().parents[2]
    receipt_dir = Path(receipt_dir).resolve(); receipt_dir.mkdir(parents=True, exist_ok=False)
    workspace = Path(boot['workspace']).resolve(); require(store_root.is_relative_to(workspace), 'native CAS must be on shared workspace PVC')
    operation = envelope(boot['stage'], encoded({'taskBinding':task,'artifactStoreRoot':str(store_root)}), task, adapter['profile'])
    directory = workspace/'gpu-operations'/operation['operationId']; outcome = {}; done = threading.Event(); cancellation = threading.Event()
    command = ['kubectl','-n',manifest['namespace']]; name = 'consense-op-'+operation['operationId']
    actual_service = runner(command+['get','service',service['name'],'-o','json']).get('spec',{})
    require(actual_service.get('clusterIP') == service['clusterIP']
            and any(row.get('port') == service['port'] and row.get('targetPort',row.get('port')) == service['port']
                for row in actual_service.get('ports',[])), 'actual native Service address or port differs; no Job may start')
    def dispatch():
        try: outcome['result'] = run_request(boot['prepared'], boot['artifacts'], workspace, operation, boot['manifestSha256'], runner=runner, cancellation=cancellation)
        except BaseException as error: outcome['error'] = error
        finally: done.set()
    require(time.monotonic() < deadline and not (cancelled is not None and cancelled()),
            'Caller cancelled or native GPU Job deadline elapsed before dispatch')
    timer = threading.Timer(max(.001, deadline-time.monotonic()), cancellation.set); timer.daemon = True; timer.start()
    worker = threading.Thread(target=dispatch, name='consense-native-gpu-job', daemon=True); worker.start()
    lifecycle = {'schema':'consense.native-provider-lifecycle.v1','mode':'owned_kubernetes_job','taskBinding':task,
                 'operationBindingSha256':operation['bindingSha256'],'modelReady':False,'targetAccepted':False}
    failed = True
    stop_watching = _watch_cancellation(cancelled, cancellation.set)
    try:
        save(receipt_dir/'start.json', lifecycle)
        ready_deadline = min(deadline, time.monotonic()+_finite(boot.get('readyTimeoutSeconds'), 'readyTimeoutSeconds'))
        while time.monotonic() < ready_deadline:
            if cancelled is not None and cancelled():
                raise ProviderLifecycleError('Caller cancelled owned native GPU Job before readiness')
            if done.is_set(): raise outcome.get('error', ProviderLifecycleError('native Job terminated before readiness'))
            try:
                pods = runner(command+['get','pods','-l','job-name='+name,'-o','json']).get('items', [])
                job_uid = runner(command+['get','job',name,'--ignore-not-found=true','-o','json']).get('metadata',{}).get('uid')
                endpoint = runner(command+['get','endpoints',service['name'],'--ignore-not-found=true','-o','json'])
                targets = {row.get('targetRef',{}).get('uid') for subset in endpoint.get('subsets',[]) for row in subset.get('addresses',[])}
                if len(pods) == 1 and job_uid and pods[0].get('metadata',{}).get('uid') in targets \
                        and any(row.get('uid') == job_uid for row in pods[0].get('metadata',{}).get('ownerReferences',[])):
                    probe = {'baseUrl':provider['baseUrl'], 'ownedBoot':{'readyPath':adapter['profile']['readyPath']}}
                    if ready(probe) if ready else _wire_ready(probe, min(.25,max(.001,ready_deadline-time.monotonic()))):
                        lifecycle['wireReady'] = {'status':'wire_ready_model_readiness_unverified', 'podUid':pods[0]['metadata']['uid']}
                        break
            except (OSError, ValueError): pass
            time.sleep(.05)
        else: raise TimeoutError('native GPU Job readiness deadline elapsed')
        yield lifecycle
        if cancelled is not None and cancelled():
            raise ProviderLifecycleError('Caller cancelled owned native GPU Job')
        if time.monotonic() >= deadline:
            raise TimeoutError('Native GPU Job caller deadline elapsed')
        from native_session_worker import verify_transaction
        verify_transaction(lifecycle.get('transaction',{}), task, store_root)
        failed = False
    finally:
        timer.cancel(); timer.join()
        stop_watching()
        if failed: cancellation.set()
        transaction = lifecycle.get('transaction') if not failed else {'status':'aborted','taskBinding':task}
        if directory.is_dir(): save(directory/'transaction.json', transaction)
        worker.join(boot['cleanupTimeoutSeconds'])
        if not done.is_set():
            lifecycle['exitReceipt'] = {'status':'release_unknown'}
            raise ProviderLifecycleError('native GPU Job release remains unknown; next provider is prohibited')
        if 'error' in outcome:
            receipt = directory/'operation.receipt.json'
            lifecycle['exitReceipt'] = json.loads(receipt.read_bytes()).get('release',{'status':'release_unknown'}) if receipt.is_file() else {'status':'release_unknown'}
            lifecycle['exitReceipt']['transactionStatus'] = 'failed'
            if not failed: raise outcome['error']
        else:
            lifecycle['exitReceipt'] = outcome['result']['release']
            require(lifecycle['exitReceipt']['status'] == 'released', 'native GPU Pod release was not proven')
        save(receipt_dir/'exit.json', lifecycle['exitReceipt'])
