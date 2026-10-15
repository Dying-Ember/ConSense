"""Prepare an offline, digest-bound deployment candidate without contacting a runtime."""
import argparse
import hashlib
import json
import ipaddress
from pathlib import Path
import re

STAGES = ('paddle', 'mineru', 'document-embedding', 'query-embedding-rerank', 'business-llm')
CONTRACT = 'consense-offline-delivery-v1'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def asset(root, row):
    root = Path(root).resolve()
    relative = Path(row['path'])
    path = (root / relative).resolve()
    require(not relative.is_absolute() and path.is_relative_to(root) and path.is_file(), 'asset must stay within its artifact root')
    require(isinstance(row.get('bytes'), int) and row['bytes'] > 0 and path.stat().st_size == row['bytes']
            and re.fullmatch('[a-f0-9]{64}', row.get('sha256', '')) and sha(path) == row['sha256'], 'asset bytes or SHA256 changed')
    return path


def validate(manifest, root):
    require(manifest.get('contract') == CONTRACT, 'unsupported delivery contract')
    require(re.fullmatch('[a-f0-9]{40}', manifest.get('sourceCommit', '')), 'exact source commit required')
    for key in ('namespace', 'release'):
        require(re.fullmatch('[a-z0-9](?:[a-z0-9-]{0,28}[a-z0-9])?', manifest.get(key, '')), 'bounded Kubernetes identity required')
    for key in ('mysql', 'qdrant', 'backend', 'web', *STAGES):
        require(re.fullmatch(r'[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}', manifest.get('images', {}).get(key, '')), 'exact image digest required: ' + key)
    require(set(manifest.get('storageClass', {})) == {'fast', 'general'} and all(
        re.fullmatch('[a-z0-9][a-z0-9.-]*', value) for value in manifest['storageClass'].values()), 'explicit storage classes required')
    require(isinstance(manifest.get('gpuNodeSelector'), dict) and manifest['gpuNodeSelector'] and all(
        isinstance(key, str) and isinstance(value, str) and key and value for key, value in manifest['gpuNodeSelector'].items()), 'explicit target GPU node selector required')
    rows = manifest.get('assets', [])
    require(rows and len({row['path'] for row in rows}) == len(rows), 'nonempty distinct asset inventory required')
    for row in rows:
        asset(root, row)
    stages = manifest.get('stages', [])
    require([stage.get('name') for stage in stages] == list(STAGES), 'complete ordered GPU stages required')
    paths = {row['path'] for row in rows}
    for stage in stages:
        require(isinstance(stage.get('command'), list) and stage['command'] and all(
            isinstance(arg, str) and arg and '\x00' not in arg for arg in stage['command']), 'exact worker command required')
        require(stage.get('runtimeAssets') and set(stage['runtimeAssets']).issubset(paths), 'worker runtime assets must be SHA pinned')
        outputs = stage.get('outputs', [])
        require(outputs and len(set(outputs)) == len(outputs) and all(isinstance(path, str) and path and not Path(path).is_absolute()
                and '..' not in Path(path).parts for path in outputs), 'complete distinct stage output paths required')
        require(isinstance(stage.get('timeoutSeconds'), int) and 1 <= stage['timeoutSeconds'] <= 86400, 'finite stage timeout required')
    for name, adapter in manifest.get('requestStages', {}).items():
        require(name in STAGES and isinstance(adapter, dict) and isinstance(adapter.get('command'), list)
                and adapter['command'] and all(isinstance(arg, str) and arg and '\x00' not in arg for arg in adapter['command'])
                and adapter.get('runtimeAssets') and set(adapter['runtimeAssets']).issubset(paths)
                and isinstance(adapter.get('profile'), dict) and adapter['profile'], 'pinned concrete request stage command, profile and assets required')
    runtime = manifest.get('coordinator')
    for stage, service in manifest.get('nativeServices', {}).items():
        require(stage in ('paddle', 'mineru') and re.fullmatch('[a-z0-9][a-z0-9-]{0,50}', service.get('name',''))
                and type(service.get('port')) is int and 1 <= service['port'] <= 65535, 'bounded native Job Service identity required')
        ipaddress.ip_address(service.get('clusterIP',''))
    if runtime is not None:
        require(re.fullmatch(r'[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}', manifest['images'].get('coordinator', '')), 'coordinator exact image digest required')
        require(type(runtime.get('port')) is int and 1 <= runtime['port'] <= 65535
                and re.fullmatch('/artifacts/[A-Za-z0-9_/-]+', runtime.get('preparedPath', ''))
                and '..' not in Path(runtime['preparedPath']).parts and runtime.get('runtimeAssets')
                and set(runtime['runtimeAssets']).issubset(paths), 'coordinator port, mounted prepared path and pinned runtime assets required')
        api_network = ipaddress.ip_network(runtime.get('apiServerCidr', ''), strict=True)
        require(api_network.num_addresses == 1 and type(runtime.get('apiServerPort')) is int
                and 1 <= runtime['apiServerPort'] <= 65535, 'explicit single API-server address and port required for coordinator egress')
        require(isinstance(runtime.get('environment'), dict) and all(isinstance(key, str) and isinstance(value, str)
                and not key.startswith('CONSENSE_GPU_STAGE_') and '\x00' not in key+value for key, value in runtime['environment'].items()), 'exact coordinator environment required')
        config = runtime.get('backendConfig', {}).get('consense', {})
        endpoint = 'http://consense-coordinator:'+str(runtime['port'])
        require(config.get('ocr', {}).get('mode') == 'local' and config['ocr'].get('base-url') == endpoint
                and config.get('vetting', {}).get('retrieval-url') == endpoint
                and config.get('minimax-cn', {}).get('enabled') is False, 'backend must use internal coordinator routes and disable default public MiniMax')
        llm = config.get('llm', {})
        require(llm.get('enabled') is False or llm.get('enabled') is True and llm.get('provider') == 'openai'
                and llm.get('base-url') == endpoint and llm.get('body-streaming-enabled') is False,
                'offline finite-Job LLM must be explicitly disabled or use its internal final-output API')
        if llm.get('enabled') is True:
            profile = manifest.get('requestStages', {}).get('business-llm', {}).get('profile', {})
            require(profile.get('adapter') == 'consense.native-chat.v1' and isinstance(profile.get('model'), str)
                    and profile['model'].strip() and llm.get('chat-model') == profile['model']
                    and '/v1/chat/completions' in profile.get('apiPaths', {}),
                    'enabled offline LLM must bind its exact native model and chat API')
        identity_environment = runtime['environment']
        directory = identity_environment.get('CONSENSE_OCR_MODEL_DIRECTORY', '')
        require(directory.startswith('/artifacts/') and '..' not in Path(directory).parts
                and re.fullmatch('[a-f0-9]{64}', identity_environment.get('CONSENSE_OCR_RECIPE_SHA256', '')),
                'offline identity OCR requires its read-only local model directory and exact recipe pin')
    return manifest


def documents(manifest, manifest_sha):
    namespace, release = manifest['namespace'], manifest['release']
    docs = [{'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': namespace}}]

    def metadata(name, labels=None):
        return {'name': name, 'namespace': namespace, 'labels': {'consense-release': release, **(labels or {})},
                'annotations': {'consense-manifest-sha256': manifest_sha}}

    for name, size, storage in (('mysql', '50G', 'fast'), ('qdrant', '200G', 'fast'), ('workspace', '400G', 'fast'), ('artifacts', '1250G', 'general')):
        docs.append({'apiVersion': 'v1', 'kind': 'PersistentVolumeClaim', 'metadata': metadata('consense-' + name),
                     'spec': {'accessModes': ['ReadWriteOnce'], 'storageClassName': manifest['storageClass'][storage],
                              'resources': {'requests': {'storage': size}}}})

    def secret(key):
        return {'secretKeyRef': {'name': 'consense-database', 'key': key}}

    cpu = (
        ('mysql', 3306, '4', '16G', '/var/lib/mysql', 'mysql',
         [{'name': 'MYSQL_DATABASE', 'value': 'consense'}, {'name': 'MYSQL_USER', 'value': 'consense'},
          {'name': 'MYSQL_PASSWORD', 'valueFrom': secret('password')}, {'name': 'MYSQL_ROOT_PASSWORD', 'valueFrom': secret('root-password')}]),
        ('qdrant', 6333, '4', '32G', '/qdrant/storage', 'qdrant', []),
        ('backend', 8080, '3', '12G', '/data/consense', 'workspace',
         [{'name': 'SPRING_PROFILES_ACTIVE', 'value': 'mysql'}, {'name': 'MYSQL_HOST', 'value': 'consense-mysql'},
          {'name': 'MYSQL_DATABASE', 'value': 'consense'}, {'name': 'MYSQL_USER', 'value': 'consense'},
          {'name': 'MYSQL_PASSWORD', 'valueFrom': secret('password')}, {'name': 'CONSENSE_STORAGE_ROOT', 'value': '/data/consense'},
          {'name': 'CONSENSE_VECTOR_PROVIDER', 'value': 'qdrant'}, {'name': 'CONSENSE_VECTOR_BASE_URL', 'value': 'http://consense-qdrant:6333'}]),
        ('web', 80, '1', '2G', None, None, []),
    )
    for name, port, cores, memory, mount, claim, env in cpu:
        if name == 'backend' and manifest.get('coordinator'):
            env.append({'name': 'SPRING_APPLICATION_JSON', 'value': json.dumps(manifest['coordinator']['backendConfig'], separators=(',', ':'))})
        labels = {'app': 'consense-' + name, 'consense-release': release}
        container = {'name': name, 'image': manifest['images'][name], 'imagePullPolicy': 'Never', 'env': env,
                     'ports': [{'containerPort': port}], 'resources': {'requests': {'cpu': cores, 'memory': memory}, 'limits': {'cpu': cores, 'memory': memory}},
                     'readinessProbe': {'tcpSocket': {'port': port}, 'initialDelaySeconds': 10, 'periodSeconds': 5}}
        pod = {'containers': [container], 'nodeSelector': manifest['gpuNodeSelector']}
        if name == 'backend':
            pod['securityContext'] = {'runAsUser': 10001, 'runAsGroup': 10001, 'fsGroup': 10001}
        if mount:
            container['volumeMounts'] = [{'name': 'data', 'mountPath': mount}]
            pod['volumes'] = [{'name': 'data', 'persistentVolumeClaim': {'claimName': 'consense-' + claim}}]
        docs.append({'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': metadata('consense-' + name),
                     'spec': {'replicas': 1, 'strategy': {'type': 'Recreate'}, 'selector': {'matchLabels': labels},
                              'template': {'metadata': {'labels': labels}, 'spec': pod}}})
        docs.append({'apiVersion': 'v1', 'kind': 'Service', 'metadata': metadata('consense-' + name),
                     'spec': {'selector': labels, 'ports': [{'port': port, 'targetPort': port}]}})
    if manifest.get('coordinator'):
        runtime = manifest['coordinator']; port = runtime['port']; labels = {'app': 'consense-coordinator', 'consense-release': release}
        query_environment = manifest.get('requestStages',{}).get('query-embedding-rerank',{}).get('profile',{}).get('environment',{})
        env = {**query_environment, **runtime['environment'], 'CONSENSE_GPU_STAGE_PREPARED': runtime['preparedPath'],
               'CONSENSE_GPU_STAGE_ARTIFACTS': '/artifacts', 'CONSENSE_GPU_STAGE_WORKSPACE': '/workspace',
               'CONSENSE_GPU_STAGE_MANIFEST_SHA256': manifest_sha, 'CONSENSE_WORKSPACE_ROOT': '/workspace',
               'CONSENSE_MODEL_DEVICE': 'cpu', 'CONSENSE_MODEL_OFFLINE': '1',
               'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'}
        docs.extend([
            {'apiVersion': 'v1', 'kind': 'ServiceAccount', 'metadata': metadata('consense-gpu-coordinator')},
            {'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'Role', 'metadata': metadata('consense-gpu-coordinator'),
             'rules': [{'apiGroups': ['batch'], 'resources': ['jobs'], 'verbs': ['create', 'get', 'list', 'watch', 'delete']},
                       {'apiGroups': [''], 'resources': ['pods'], 'verbs': ['get', 'list', 'watch']},
                       {'apiGroups': [''], 'resources': ['endpoints', 'services'], 'verbs': ['get']},
                       {'apiGroups': [''], 'resources': ['persistentvolumeclaims'], 'resourceNames': ['consense-workspace'], 'verbs': ['get']}]},
            {'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'RoleBinding', 'metadata': metadata('consense-gpu-coordinator'),
             'subjects': [{'kind': 'ServiceAccount', 'name': 'consense-gpu-coordinator', 'namespace': namespace}],
             'roleRef': {'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': 'consense-gpu-coordinator'}},
            {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': metadata('consense-coordinator'),
             'spec': {'replicas': 1, 'strategy': {'type': 'Recreate'}, 'selector': {'matchLabels': labels}, 'template': {
                 'metadata': {'labels': labels}, 'spec': {'serviceAccountName': 'consense-gpu-coordinator',
                     'securityContext': {'runAsUser': 10001, 'runAsGroup': 10001, 'fsGroup': 10001},
                     'nodeSelector': manifest['gpuNodeSelector'], 'containers': [{
                     'name': 'coordinator', 'image': manifest['images']['coordinator'], 'imagePullPolicy': 'Never',
                     'command': ['python', '/app/vetting_eval/server.py', '--host', '0.0.0.0', '--port', str(port)],
                     'env': [{'name': key, 'value': value} for key, value in env.items()], 'ports': [{'containerPort': port}],
                     'resources': {'requests': {'cpu': '1', 'memory': '4G'}, 'limits': {'cpu': '1', 'memory': '4G'}},
                     'readinessProbe': {'httpGet': {'path': '/health', 'port': port}},
                     'volumeMounts': [{'name': 'artifacts', 'mountPath': '/artifacts', 'readOnly': True}, {'name': 'workspace', 'mountPath': '/workspace'}]}],
                     'volumes': [{'name': 'artifacts', 'persistentVolumeClaim': {'claimName': 'consense-artifacts', 'readOnly': True}},
                                 {'name': 'workspace', 'persistentVolumeClaim': {'claimName': 'consense-workspace'}}]}}}},
            {'apiVersion': 'v1', 'kind': 'Service', 'metadata': metadata('consense-coordinator'),
             'spec': {'selector': labels, 'ports': [{'port': port, 'targetPort': port}]}},
        ])
    for stage in manifest['stages']:
        name = 'consense-' + release + '-' + stage['name']
        labels = {'consense-stage': stage['name'], 'consense-gpu-owner': release}
        env = [{'name': key, 'value': value} for key, value in {'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1',
               'CONSENSE_MANIFEST_SHA256': manifest_sha, 'CONSENSE_STAGE_RECEIPT': '/workspace/' + name + '.receipt.json'}.items()]
        docs.append({'apiVersion': 'batch/v1', 'kind': 'Job', 'metadata': metadata(name, labels),
                     'spec': {'parallelism': 1, 'completions': 1, 'backoffLimit': 0, 'activeDeadlineSeconds': stage['timeoutSeconds'],
                              'template': {'metadata': {'labels': {**labels, 'consense-release': release}}, 'spec': {
                                  'restartPolicy': 'Never', 'nodeSelector': manifest['gpuNodeSelector'], 'containers': [{'name': stage['name'], 'image': manifest['images'][stage['name']],
                                  'imagePullPolicy': 'Never', 'command': ['python', '/app/offline_delivery/stage_worker.py',
                                      '--manifest', '/artifacts/delivery-manifest.json', '--artifacts', '/artifacts', '--workspace', '/workspace', '--stage', stage['name']], 'env': env,
                                  'resources': {'requests': {'cpu': '14' if manifest.get('coordinator') else '15', 'memory': '180G', 'nvidia.com/gpu': 1},
                                                'limits': {'cpu': '14' if manifest.get('coordinator') else '15', 'memory': '180G', 'nvidia.com/gpu': 1}},
                                  'volumeMounts': [{'name': 'artifacts', 'mountPath': '/artifacts', 'readOnly': True}, {'name': 'workspace', 'mountPath': '/workspace'}]}],
                                  'volumes': [{'name': 'artifacts', 'persistentVolumeClaim': {'claimName': 'consense-artifacts', 'readOnly': True}},
                                              {'name': 'workspace', 'persistentVolumeClaim': {'claimName': 'consense-workspace'}}]}}}})
    for stage, service in manifest.get('nativeServices', {}).items():
        docs.append({'apiVersion':'v1','kind':'Service','metadata':metadata(service['name']),
            'spec':{'clusterIP':service['clusterIP'],'selector':{'consense-stage':stage,'consense-gpu-owner':release},
                    'ports':[{'port':service['port'],'targetPort':service['port']}]}})
    docs.append({'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': metadata('consense-offline'),
                 'spec': {'podSelector': {}, 'policyTypes': ['Ingress', 'Egress'],
                          'ingress': [{'from': [{'podSelector': {}}]}],
                          'egress': [{'to': [{'podSelector': {}}]}, {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}},
                            'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}], 'ports': [{'protocol': 'UDP', 'port': 53}, {'protocol': 'TCP', 'port': 53}]}]}})
    if manifest.get('coordinator'):
        runtime = manifest['coordinator']
        docs.append({'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': metadata('consense-coordinator-api'),
            'spec': {'podSelector': {'matchLabels': {'app': 'consense-coordinator'}}, 'policyTypes': ['Egress'],
                     'egress': [{'to': [{'ipBlock': {'cidr': runtime['apiServerCidr']}}],
                                 'ports': [{'protocol': 'TCP', 'port': runtime['apiServerPort']}]}]}})
    return docs


def prepare(manifest_path, artifact_root, output):
    manifest_path, output = Path(manifest_path), Path(output)
    raw = manifest_path.read_bytes()
    manifest = validate(json.loads(raw), artifact_root)
    digest = hashlib.sha256(raw).hexdigest()
    require(not output.exists(), 'fresh output directory required')
    output.mkdir(parents=True)
    (output / 'manifest.json').write_bytes(raw)
    resources = documents(manifest, digest)
    (output / 'kubernetes.json').write_text(json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': [doc for doc in resources if doc['kind'] != 'Job']}, indent=2) + '\n')
    (output / 'gpu-jobs').mkdir()
    stages = {}
    for job in (doc for doc in resources if doc['kind'] == 'Job'):
        name = job['metadata']['labels']['consense-stage']
        path = output / 'gpu-jobs' / (name + '.json')
        path.write_text(json.dumps(job, indent=2) + '\n')
        stages[name] = sha(path)
    receipt = {'contract': CONTRACT, 'manifestSha256': digest, 'sourceCommit': manifest['sourceCommit'],
               'assetsVerified': manifest['assets'], 'kubernetesSha256': sha(output / 'kubernetes.json'),
               'gpuJobSha256': stages,
               'targetAccepted': False, 'boundary': 'Preparation only; no image build, cluster, model or database operation.'}
    receipt['completeNewSourcePipeline'] = False
    receipt['implementedRuntimeAdapters'] = ['native_session_job_adapter', 'window_index_query_job_adapter', 'business_llm_job_adapter']
    receipt['openRuntimeAdapters'] = [name for stage,name in (('paddle','native_session_job_adapter'),('mineru','native_session_job_adapter'),
        ('document-embedding','window_index_job_adapter'),('query-embedding-rerank','window_query_job_adapter'),('business-llm','business_llm_job_adapter'))
        if stage not in manifest.get('requestStages',{})]
    receipt['openRuntimeAdapters'] = list(dict.fromkeys(receipt['openRuntimeAdapters']))
    receipt['runtimeConfigurationComplete'] = bool(manifest.get('coordinator') and not receipt['openRuntimeAdapters']
        and set(manifest.get('nativeServices',{})) == {'paddle','mineru'})
    (output / 'preparation.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--artifacts', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.manifest, args.artifacts, args.out), indent=2))
