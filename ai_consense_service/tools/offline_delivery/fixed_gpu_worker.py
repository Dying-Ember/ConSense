"""Single, configuration-bound fixed GPU executor. A missing release proof blocks reuse."""
import argparse
import base64
import copy
from contextlib import contextmanager
import hashlib
import hmac
import http.client
import io
from http.client import HTTPConnection as ProviderConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import select
import secrets
import socket
import socketserver
import signal
import stat
import struct
import subprocess
import sys
import threading
import time
import uuid
from urllib.parse import urlsplit

from delivery import require, sha
from fixed_gpu_client import cpu_business_release_proven, gpu_release_proven
from gpu_observation import (GpuObservationIncomplete, observe_running, observe_release,
                             observe_single_idle_device, observe_owned_tree_release)
from request_stage import MAX_BODY, encoded, envelope, verify_operation, verify_response

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'vetting_eval'))
from native_provider_lifecycle import _LinuxTree

MAX_PROVIDER_BODY = 1024 * 1024 * 1024
CPU_STUB_CHAT = '/runtime/tools/offline_delivery/chat_operation_worker.py'
CPU_STUB_SERVER = '/runtime/tools/offline_delivery/qwen_protocol_stub.py'
CPU_STUB_CONTRACT = b'# consense-cpu-stub-process-only-v1'
OWNED_MODEL_ENV = frozenset(('CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST',
    'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST_SHA256', 'CONSENSE_EMBED_BATCH_SIZE',
    'CONSENSE_EMBED_MAX_TOKENS', 'CONSENSE_MODEL_DEVICE', 'CONSENSE_MODEL_DTYPE',
    'CONSENSE_MODEL_OFFLINE', 'CONSENSE_MODEL_SERIAL_OFFLOAD', 'CONSENSE_RERANKER_CHECKPOINT_MANIFEST',
    'CONSENSE_RERANKER_CHECKPOINT_MANIFEST_SHA256', 'CONSENSE_RERANK_BATCH_SIZE',
    'CONSENSE_RERANK_MAX_TOKENS', 'CONSENSE_RETRIEVAL_GRANULARITY',
    'CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS', 'CONSENSE_STRUCTURAL_RERANK_MAX_TOKENS',
    'CONSENSE_STRUCTURAL_RERANK_STRICT', 'CONSENSE_WINDOW_OVERLAP_TOKENS',
    'CONSENSE_WORKSPACE_ROOT', 'HF_HOME', 'HF_HUB_OFFLINE', 'HOME', 'LD_LIBRARY_PATH',
    'PATH', 'PYTHONDONTWRITEBYTECODE', 'TMPDIR', 'TRANSFORMERS_OFFLINE'))


def _read_only_driver_mount(path):
    try:
        if not path.is_dir() or path.is_symlink() or path.resolve() != path:
            return False
        mounts = []
        for line in Path('/proc/self/mountinfo').read_text().splitlines():
            before, separator, _ = line.partition(' - ')
            fields = before.split()
            if separator and len(fields) >= 6 and (fields[4] == str(path) or fields[4].startswith(str(path) + '/')):
                mounts.append((fields[4], 'ro' in fields[5].split(',')))
        if not (any(target == str(path) for target, _ in mounts) and all(read_only for _, read_only in mounts)
                and bool(os.statvfs(path).f_flag & os.ST_RDONLY)):
            return False
        def walk_error(error):
            raise error
        for directory, dirs, files in os.walk(path, followlinks=False, onerror=walk_error):
            for name in dirs + files:
                entry = Path(directory) / name
                if entry.is_symlink():
                    entry.resolve(strict=True).relative_to(path)
        return True
    except (OSError, RuntimeError, ValueError):
        return False


def owned_model_boot(boot):
    env = boot.get('env', {})
    return (boot.get('command') == ['/runtime/envs/retrieval/bin/python3.12.0',
                                    '/runtime/tools/offline_delivery/model_session_worker.py']
            and isinstance(env, dict) and set(env) == OWNED_MODEL_ENV
            and (env['LD_LIBRARY_PATH'] == '/usr/lib/wsl/lib'
                 or env['LD_LIBRARY_PATH'] == '/runtime/gpu-driver/lib'
                 and _read_only_driver_mount(Path('/runtime/gpu-driver/lib'))))


def owned_ocr_boot(stage, profile, boot):
    command, env = boot.get('command'), boot.get('env')
    executable = {'paddle':['/runtime/ocr-envs/paddle/bin/python3.12.0', '/runtime/tools/vetting_eval/paddle_native_server.py'],
                  'mineru':['/runtime/ocr-envs/mineru/bin/python3.12.0', '/runtime/tools/vetting_eval/mineru_native_server.py']}.get(stage)
    return (executable is not None and profile.get('mode') == 'session'
            and profile.get('profile', {}).get('adapter') == 'consense.native-api-session.v1'
            and isinstance(command, list) and command[:2] == executable
            and isinstance(env, dict) and env.get('CUDA_VISIBLE_DEVICES') == '0'
            and not any(key.startswith(('CUDA_MPS_', 'PYTORCH_ALLOC_CONF', 'PYTORCH_CUDA_ALLOC_CONF'))
                        or key in ('PYTHONPATH', 'PYTHONHOME', 'LD_PRELOAD', 'LD_AUDIT') for key in env))


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    except BaseException as error:
        try: os.close(fd)
        except BaseException as cleanup_error:
            error.add_note('Directory descriptor cleanup unknown: ' + type(cleanup_error).__name__)
        raise
    else:
        os.close(fd)


def fdopen_owned(fd, *args, **kwargs):
    try: return os.fdopen(fd, *args, **kwargs)
    except BaseException as error:
        try: os.close(fd)
        except BaseException as cleanup_error:
            error.add_note('Descriptor cleanup unknown: ' + type(cleanup_error).__name__)
        raise


@contextmanager
def preserve_stream_error(stream):
    primary = None; entered = False
    try:
        with stream as opened:
            entered = True
            try: yield opened
            except BaseException as error: primary = error; raise
        if primary is not None: raise primary
    except BaseException as error:
        if not entered:
            try: stream.close()
            except BaseException as cleanup_error:
                error.add_note('Stream cleanup unknown: ' + type(cleanup_error).__name__)
        if primary is not None and error is not primary:
            primary.add_note('Stream cleanup unknown: ' + type(error).__name__)
            raise primary
        raise


def persist(path, value):
    path = Path(path)
    temporary = path.with_name('.' + path.name + '.tmp')
    with preserve_stream_error(fdopen_owned(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb')) as stream:
        data = encoded(value)
        if stream.write(data) != len(data): raise OSError('short persisted record write')
        stream.flush(); os.fsync(stream.fileno())
    os.replace(temporary, path)
    if os.name == 'posix':
        sync_directory(path.parent)


def read_json(path):
    return json.loads(Path(path).read_bytes())


def success_event(frame):
    lines = frame.decode('utf-8').splitlines()
    events = [line[6:].strip() for line in lines if line.startswith('event:')]
    data = '\n'.join(line[5:].lstrip(' ') for line in lines if line.startswith('data:'))
    require(all(name in ('message', 'error', 'ping', 'message_stop', 'done') or name.startswith('response.')
                for name in events), 'unsupported SSE event type')
    if data == '[DONE]' or any(name in ('message_stop', 'done', 'response.completed', 'response.failed', 'response.incomplete') for name in events):
        return True
    if not data: return False
    value = json.loads(data)
    require(isinstance(value, dict), 'unsupported SSE data')
    kind = value.get('type')
    require(kind is None or isinstance(kind, str) and (kind in ('error', 'message_stop') or kind.startswith('response.')),
            'unsupported SSE data type')
    if kind in ('message_stop', 'response.completed', 'response.failed', 'response.incomplete'): return True
    choices = value.get('choices', [])
    require(isinstance(choices, list), 'unsupported SSE choices')
    return any(isinstance(choice, dict) and choice.get('finish_reason') is not None for choice in choices)


def stream_completed(body, api_path):
    pending = body; completed = False; failed = False
    while pending:
        boundary = re.search(b'\r\n\r\n|\n\n|\r\r', pending)
        require(boundary is not None, 'incomplete SSE frame')
        frame, pending = pending[:boundary.end()], pending[boundary.end():]
        success_event(frame)
        lines = frame.decode('utf-8').splitlines()
        events = [line[6:].strip() for line in lines if line.startswith('event:')]
        data = '\n'.join(line[5:].lstrip(' ') for line in lines if line.startswith('data:'))
        value = json.loads(data) if data and data != '[DONE]' else {}
        kind = value.get('type') if isinstance(value, dict) else None
        failed = failed or any(name in ('error','response.failed','response.incomplete') for name in events) \
            or kind in ('error','response.failed','response.incomplete') or bool(value.get('error'))
        choices = value.get('choices', [])
        failed = failed or any(isinstance(choice,dict) and choice.get('finish_reason') in ('length','content_filter') for choice in choices)
        if api_path == '/v1/chat/completions' and data == '[DONE]': completed = True
        if api_path == '/v1/responses' and ('response.completed' in events or kind == 'response.completed'):
            completed = True
            response = value.get('response', {})
            if isinstance(response, dict) and response.get('status') not in (None, 'completed'): failed = True
    return completed and not failed


class Worker:
    def __init__(self, config, secret, config_path=None, config_sha=None):
        self.config = config
        require(Path(config['workspace']).is_absolute(), 'absolute workspace required')
        self.workspace = Path(config['workspace']).resolve()
        require(self.workspace.is_dir() and self.workspace.is_absolute(), 'existing absolute workspace required')
        require(isinstance(secret, str) and secret and '\n' not in secret, 'secret required')
        self.secret = secret
        self.config_path = Path(config_path).resolve() if config_path else None
        self.config_sha = config_sha if self.config_path else hashlib.sha256(encoded(config)).hexdigest()
        require(self.config_path is None or isinstance(self.config_sha, str) and re.fullmatch('[a-f0-9]{64}', self.config_sha),
                'exact loaded configuration hash required')
        self.lock = threading.Lock()
        self.active = {}
        self.stopping = False
        self.launch_unknown = set()
        self.streaming = set()
        self.trees = {}
        self.sessions = {}
        self.operations = self.workspace / 'gpu-operations'
        self.operations.mkdir(mode=0o700, exist_ok=True)
        require(not self.operations.is_symlink(), 'operation directory must not be a symlink')
        if os.name == 'posix':
            info = self.operations.stat()
            require(info.st_uid == os.geteuid() and not (info.st_mode & 0o077), 'private owned operation directory required')
        self.marker = self.workspace / 'gpu-owner.json'

    def _record(self, operation_id):
        require(re.fullmatch('[a-f0-9]{32}', operation_id or '') is not None, 'invalid operation ID')
        record = read_json(self.operations / operation_id / 'status.json')
        if operation_id in self.launch_unknown:
            record.update(status='release_unknown', release={'status':'release_unknown'},
                          failure='thread launch outcome is unknown')
        elif record.get('status') in ('starting', 'running', 'ready', 'finishing', 'settling') and operation_id not in self.active:
            record.update(status='release_unknown', release={'status':'release_unknown'},
                          failure='owning executor is no longer active')
        return record

    def status(self, operation_id):
        record = self._record(operation_id)
        path = self.operations / operation_id / 'request.json'
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 2 * MAX_BODY,
                'bounded persisted operation request required')
        operation = verify_operation(read_json(path))
        require(operation['operationId'] == operation_id and record.get('operationId') == operation_id
                and operation['stage'] == record.get('stage') and operation['bindingSha256'] == record.get('bindingSha256'),
                'persisted operation identity changed')
        return {**record, 'bodySha256':operation['bodySha256'],
                'sourceSha256':hashlib.sha256(encoded(operation['source'])).hexdigest(),
                'profileSha256':operation['profileSha256'],
                **({'providerEvidenceProtocol':'dispatch-v1'} if operation['stage'] in ('paddle','mineru') else {})}

    def _cpu_stub_operation(self, record, operation):
        profile = self.config['stages'].get('business-llm', {})
        inner = profile.get('profile', {})
        return (operation.get('stage') == record.get('stage') == 'business-llm'
                and profile.get('mode') == 'job' and inner.get('adapter') == 'consense.native-chat.v1'
                and inner.get('providerKind') == 'cpu-protocol-stub'
                and operation.get('profileSha256') == hashlib.sha256(encoded(inner)).hexdigest()
                and record.get('configSha256') == self.config_sha)

    def _release_proven(self, record, operation):
        return (gpu_release_proven(record.get('release'), owned_tree=(self._owned_model_operation(record, operation)
                                 or self._owned_ocr_operation(record, operation))) or
                (self._cpu_stub_operation(record, operation) and cpu_business_release_proven(record.get('release'))))

    def _owned_model_operation(self, record, operation):
        stage = operation.get('stage')
        configured = self.config['stages'].get(stage, {})
        profile = configured.get('profile', {})
        return (stage in ('document-embedding', 'query-embedding-rerank', 'fixed-reference-query') and record.get('stage') == stage
                and configured.get('mode') == 'model-session' and profile.get('adapter') == 'consense.model-session.v1'
                and operation.get('operationId') == record.get('operationId')
                and operation.get('bindingSha256') == record.get('bindingSha256')
                and operation.get('profileSha256') == hashlib.sha256(encoded(profile)).hexdigest()
                and record.get('configSha256') == self.config_sha)

    def _owned_ocr_operation(self, record, operation):
        stage = operation.get('stage')
        configured = self.config['stages'].get(stage, {})
        profile = configured.get('profile', {})
        return (stage in ('paddle', 'mineru') and record.get('stage') == stage
                and owned_ocr_boot(stage, configured, configured.get('boot', {}))
                and operation.get('operationId') == record.get('operationId')
                and operation.get('bindingSha256') == record.get('bindingSha256')
                and operation.get('profileSha256') == hashlib.sha256(encoded(profile)).hexdigest()
                and record.get('configSha256') == self.config_sha)

    def _validate_cpu_stub_code(self, profile):
        # This pinned release contract is not GPU device isolation.
        assets = profile['boot']['runtimeAssets']
        chat = [row for row in assets if row['path'] == CPU_STUB_CHAT]
        server = [row for row in assets if row['path'] == CPU_STUB_SERVER]
        require(len(chat) == len(server) == 1, 'CPU stub script identity differs')
        data = Path(CPU_STUB_CHAT).read_bytes()
        require(hashlib.sha256(data).hexdigest() == chat[0]['sha256']
                and CPU_STUB_CONTRACT in data.splitlines()[:4], 'CPU stub contract differs')

    def result(self, operation_id):
        record = self._record(operation_id); directory = self.operations / operation_id
        result_path = directory / 'response.json'
        operation = verify_operation(read_json(directory / 'request.json'))
        require(record['status'] in ('completed', 'failed') and self._release_proven(record, operation)
                and result_path.is_file() and not result_path.is_symlink()
                and result_path.stat().st_size <= 2 * MAX_BODY
                and record.get('responseSha256') == sha(result_path), 'completed persisted result unavailable')
        require(operation['operationId'] == operation_id == record.get('operationId') and operation['bindingSha256'] == record['bindingSha256']
                and operation['stage'] == record['stage'], 'persisted operation identity changed')
        return verify_response(read_json(result_path), operation)

    def model_summary(self, operation_id):
        record = self._record(operation_id); directory = self.operations / operation_id
        path = directory / 'model-session' / 'finished.json'
        operation = verify_operation(read_json(directory / 'request.json'))
        require(record.get('status') == 'completed' and self._release_proven(record, operation)
                and path.is_file() and not path.is_symlink() and path.stat().st_size <= 4096
                and record.get('modelSummarySha256') == sha(path), 'released model summary unavailable')
        summary = read_json(path)
        require(operation['operationId'] == operation_id and operation['bindingSha256'] == record['bindingSha256']
                and summary.get('operationId') == operation_id and summary.get('bindingSha256') == operation['bindingSha256']
                and summary.get('requestId') == operation['source'].get('requestId')
                and summary.get('status') == 'computation_completed_release_unverified', 'model summary identity differs')
        return summary

    def _set(self, directory, **values):
        with self.lock:
            record = read_json(directory / 'status.json')
            record.update(values)
            persist(directory / 'status.json', record)
            return record

    def _publish_terminal(self, directory, fields):
        with self.lock:
            record = read_json(directory / 'status.json')
            released = fields['release']['status'] == 'released'
            if fields['release'].get('ownership') == 'delegated_cgroup_v2':
                operation = verify_operation(read_json(directory / 'request.json'))
                require((self._owned_model_operation(record, operation) or self._owned_ocr_operation(record, operation))
                        and gpu_release_proven(fields['release'], owned_tree=True), 'owned session release identity differs')
            if cpu_business_release_proven(fields['release']):
                operation = verify_operation(read_json(directory / 'request.json'))
                released = self._cpu_stub_operation(record, operation)
                require(released and operation['operationId'] == record['operationId']
                        and operation['bindingSha256'] == record['bindingSha256'], 'CPU stub identity differs')
            if released:
                require(not self.marker.is_symlink() and read_json(self.marker) == {
                    'operationId': record['operationId'], 'state': 'occupied_or_release_unknown'},
                    'GPU owner does not belong to this operation')
            record.update(fields)
            # Persist the terminal evidence before allowing another operation to claim the GPU.
            persist(directory / 'status.json', record)
            if released:
                self.marker.unlink()
                sync_directory(self.workspace)

    def start(self, request):
        require(self.config_path is None or sha(self.config_path) == self.config_sha, 'configured file changed; restart required')
        require(isinstance(request, dict) and set(request) == {'stage', 'binding', 'bodyBase64',
                'expectedProfileSha256', 'expectedMode'}, 'exact operation fields required')
        stage = request['stage']; binding = request['binding']
        require(isinstance(stage, str) and stage in self.config['stages'], 'stage is not configured')
        require(isinstance(binding, dict) and binding, 'nonempty binding required')
        body = base64.b64decode(request['bodyBase64'], validate=True)
        require(len(body) <= MAX_BODY, 'operation body exceeds bound')
        profile = copy.deepcopy(self.config['stages'][stage])
        require(isinstance(profile, dict) and profile.get('mode') in ('job', 'session', 'model-session'), 'invalid stage mode')
        inner = profile.get('profile')
        expected = request['expectedProfileSha256']
        require(isinstance(inner, dict) and isinstance(expected, str) and re.fullmatch('[a-f0-9]{64}', expected)
                and request['expectedMode'] == profile['mode']
                and hmac.compare_digest(expected, hashlib.sha256(encoded(inner)).hexdigest()),
                'expected operation profile or mode differs')
        if inner.get('adapter') == 'consense.model-compute.v1':
            require(stage in ('document-embedding', 'query-embedding-rerank') and profile['mode'] == 'job',
                    'model computation stage or mode differs')
        if inner.get('adapter') == 'consense.model-session.v1':
            require(stage in ('document-embedding', 'query-embedding-rerank', 'fixed-reference-query') and profile['mode'] == 'model-session',
                    'model session stage or mode differs')
        if inner.get('adapter') == 'consense.native-chat.v1':
            require(stage == 'business-llm' and profile['mode'] == 'job', 'native chat stage or mode differs')
        if isinstance(inner, dict) and inner.get('providerKind') == 'cpu-protocol-stub':
            require(stage == 'business-llm' and profile['mode'] == 'job', 'stub is only permitted for business jobs')
            require(inner.get('adapter') == 'consense.native-chat.v1'
                    and len(profile.get('boot', {}).get('command', [])) == 2
                    and profile['boot']['command'][1] == CPU_STUB_CHAT
                    and isinstance(inner.get('command'), list) and len(inner['command']) >= 2
                    and inner['command'][1] == CPU_STUB_SERVER
                    and profile['boot']['command'][0] == inner['command'][0]
                    and isinstance(profile['boot'].get('env'), dict)
                    and all(not key.startswith(('PYTHON', 'LD_')) or key in
                            ('PYTHONDONTWRITEBYTECODE', 'PYTHONNOUSERSITE', 'PYTHONSAFEPATH', 'LD_LIBRARY_PATH')
                            for key in profile['boot']['env']),
                    'CPU stub requires the fixed chat and protocol stub programs')
            profile = copy.deepcopy(profile)
        self._validate_profile(profile)
        if inner.get('providerKind') == 'cpu-protocol-stub':
            self._validate_cpu_stub_code(profile)
        if profile['mode'] == 'model-session':
            from model_session_worker import MAX_BATCHES, MAX_TOTAL_BYTES
            require(body == b'{}' and profile['profile'].get('adapter') == 'consense.model-session.v1'
                    and set(binding) == {'requestId','sourceScopeSha256','modelKind','modelIdentity','runtime',
                                         'inferenceCap','deadlineBudgetMs','maxBatches','maxTotalBytes'}
                    and isinstance(binding['requestId'], str) and re.fullmatch('[a-f0-9]{32}', binding['requestId'])
                    and isinstance(binding['sourceScopeSha256'], str) and re.fullmatch('[a-f0-9]{64}', binding['sourceScopeSha256'])
                    and binding['modelKind'] in ('embedding','reranker')
                    and ((stage == 'document-embedding' and binding['modelKind'] == 'embedding')
                         or stage in ('query-embedding-rerank', 'fixed-reference-query'))
                    and isinstance(binding['modelIdentity'], dict) and binding['modelIdentity']
                    and isinstance(binding['runtime'], dict) and binding['runtime'].get('device') == 'cuda'
                    and type(binding['inferenceCap']) is int and 0 < binding['inferenceCap'] <= 8192
                    and type(binding['deadlineBudgetMs']) is int and 0 < binding['deadlineBudgetMs'] <= profile['timeoutSeconds'] * 1000
                    and type(binding['maxBatches']) is int and 0 < binding['maxBatches'] <= MAX_BATCHES
                    and type(binding['maxTotalBytes']) is int and 0 < binding['maxTotalBytes'] <= MAX_TOTAL_BYTES,
                    'invalid model session binding')
        if profile['mode'] == 'session':
            require(set(binding) == {'parser','inputSha256','inputByteCount','originalSourceSha256','physicalPageNo','profileSha256'}
                    and stage in ('paddle', 'mineru') and binding.get('parser') == ('paddleocr-vl' if stage == 'paddle' else 'mineru')
                    and re.fullmatch('[a-f0-9]{64}', binding.get('inputSha256', ''))
                    and re.fullmatch('[a-f0-9]{64}', binding.get('originalSourceSha256', ''))
                    and re.fullmatch('[a-f0-9]{64}', binding.get('profileSha256', ''))
                    and type(binding.get('inputByteCount')) is int and binding['inputByteCount'] > 0
                    and type(binding.get('physicalPageNo')) is int and binding['physicalPageNo'] > 0, 'invalid OCR task binding')
            require(binding['profileSha256'] == hashlib.sha256(encoded(profile['profile']['nativeIdentity'])).hexdigest(),
                    'OCR declared model profile differs from configured profile')
        operation = envelope(stage, body, binding, profile['profile'])
        operation_id = operation['operationId']
        with self.lock:
            require(not self.stopping, 'GPU worker is shutting down')
            try:
                fd = os.open(self.marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            except FileExistsError as error:
                raise ValueError('GPU owner exists or previous release is unknown') from error
            directory = self.operations / operation_id
            cancellation = None
            launch_attempted = False
            try:
                with preserve_stream_error(fdopen_owned(fd, 'wb')) as stream:
                    marker = encoded({'operationId': operation_id, 'state': 'occupied_or_release_unknown'})
                    if stream.write(marker) != len(marker): raise OSError('short owner marker write')
                    stream.flush(); os.fsync(stream.fileno())
                sync_directory(self.workspace)
                directory.mkdir(mode=0o700)
                persist(directory / 'request.json', operation)
                budget = binding['deadlineBudgetMs'] / 1000 if profile['mode'] == 'model-session' else profile['timeoutSeconds']
                deadline = time.monotonic() + budget
                persist(directory / 'status.json', {'operationId': operation_id, 'stage': stage, 'status': 'starting',
                    'bindingSha256': operation['bindingSha256'], 'configSha256': self.config_sha,
                    'deadlineUnixMs': int((time.time() + budget) * 1000),
                    'configIdentityScope': 'config_file_bytes' if self.config_path else 'canonical_config',
                    'release': {'status': 'pending'}, 'targetAccepted': False})
                cancellation, finished = threading.Event(), threading.Event()
                self.active[operation_id] = (cancellation, finished)
                if profile['mode'] == 'session': self.sessions[operation_id] = {'lock':threading.Lock()}
                if profile['mode'] == 'model-session':
                    token = secrets.token_hex(32)
                    token_fd = os.open(directory / 'model-session.token', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    with preserve_stream_error(fdopen_owned(token_fd, 'w', encoding='ascii')) as stream:
                        require(stream.write(token) == len(token), 'model session token write incomplete')
                        stream.flush(); os.fsync(stream.fileno())
                    self.sessions[operation_id] = {'lock':threading.Lock(), 'deadline':deadline, 'token':token,
                                                   'receipts':[], 'totalBytes':0}
                thread = threading.Thread(target=self._execute, args=(directory, operation, profile, cancellation, finished), daemon=True)
                launch_attempted = True
                thread.start()
            except BaseException as error:
                if profile['mode'] == 'model-session':
                    if launch_attempted:
                        self.launch_unknown.add(operation_id)
                        if cancellation is not None: cancellation.set()
                    else:
                        self.active.pop(operation_id, None)
                        self.sessions.pop(operation_id, None)
                    try:
                        record = read_json(directory / 'status.json')
                        require(record.get('operationId') == operation_id
                                and record.get('bindingSha256') == operation['bindingSha256'], 'startup status identity differs')
                        record.update(status='release_unknown' if launch_attempted else 'failed',
                                      release={'status':'release_unknown'},
                                      startup={'status':'launch_unknown' if launch_attempted else 'not_started',
                                               'errorType':type(error).__name__, 'evidenceDurability':'unknown'})
                        if any('cleanup unknown:' in note for note in getattr(error, '__notes__', [])):
                            record['startup']['cleanupStatus'] = 'unknown'
                        persist(directory / 'status.json', record)
                    except BaseException as evidence_error:
                        error.add_note('Startup failure status unavailable: ' + type(evidence_error).__name__)
                raise
        return {'operationId': operation_id, 'status': 'starting'}

    def shutdown_when_idle(self, poll=None):
        with self.lock:
            self.stopping = True
        while True:
            with self.lock:
                if not self.active:
                    return
            if poll is None:
                time.sleep(.05)
            else:
                poll()

    def _cancel_locked(self, operation_id):
        if operation_id not in self.active:
            return
        self.active[operation_id][0].set()
        provider_socket = self.sessions.get(operation_id, {}).get('providerSocket')
        if provider_socket is not None:
            try:
                provider_socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                provider_socket.close()

    def cancel(self, operation_id):
        with self.lock:
            record = self._record(operation_id)
            require(operation_id in self.active and record['status'] in ('starting','running','ready','finishing'),
                    'operation has already claimed its outcome')
            self._cancel_locked(operation_id)
        return {'operationId': operation_id, 'status': 'cancellation_requested'}

    def finish(self, operation_id, value):
        from native_session_worker import verify_transaction
        require(isinstance(value, dict) and set(value) == {'transaction'}, 'transaction required')
        with self.lock:
            record = self._record(operation_id)
            require(record['status'] == 'ready' and operation_id in self.active
                    and not self.active[operation_id][0].is_set(), 'session is not ready or was cancelled')
            require(operation_id in self.sessions and self.sessions[operation_id]['lock'].acquire(blocking=False),
                    'provider request is still active')
            session = self.sessions[operation_id]
        directory = self.operations / operation_id
        try:
            require(not session.get('providerOutcomeUnknown'), 'provider outcome is unknown; cannot finish successfully')
            operation = verify_operation(read_json(directory / 'request.json'))
            require(operation['operationId'] == operation_id and operation['stage'] == record['stage']
                    and operation['bindingSha256'] == record['bindingSha256'], 'persisted operation identity changed')
            root = Path(self.config['artifactStoreRoot'])
            require(root.is_absolute() and root.is_dir() and root.resolve().is_relative_to(self.workspace),
                    'configured artifact store must be within the persistent workspace')
            verify_transaction(value['transaction'], operation['source'], root)
            with self.lock:
                require(operation_id in self.active and not self.active[operation_id][0].is_set(), 'session was cancelled')
            persist(directory / 'transaction.json', value['transaction'])
            with self.lock:
                record = self._record(operation_id)
                require(record['status'] == 'ready' and operation_id in self.active
                        and not self.active[operation_id][0].is_set()
                        and record['bindingSha256'] == operation['bindingSha256'], 'session was cancelled or changed')
                record['status'] = 'finishing'; persist(directory / 'status.json', record)
                self.active[operation_id][1].set()
        finally: session['lock'].release()
        return {'operationId': operation_id, 'status': 'finishing'}

    def _model_ready(self, directory, operation, tree):
        path = directory / 'model-session.ready.json'
        if not path.is_file() or path.is_symlink(): return False
        require(path.stat().st_size <= 4096, 'model ready marker exceeds bound')
        ready = read_json(path); sock = directory / 'model-session.sock'
        require(type(ready) is dict, 'model session ready marker must be an object')
        try: socket_info = sock.lstat()
        except FileNotFoundError: socket_info = None
        checks = {
            'fields': set(ready) == {'operationId','requestSha256','bindingSha256','profileSha256','pid','processStartTicks','socketInode'},
            'operationId': ready.get('operationId') == operation['operationId'],
            'requestSha256': ready.get('requestSha256') == sha(directory / 'request.json'),
            'bindingSha256': ready.get('bindingSha256') == operation['bindingSha256'],
            'profileSha256': ready.get('profileSha256') == operation['profileSha256'],
            'ownedPid': type(ready.get('pid')) is int and ready['pid'] in self._tree_pids(tree),
            'socket': socket_info is not None and stat.S_ISSOCK(socket_info.st_mode)
                      and socket_info.st_ino == ready.get('socketInode'),
        }
        require(all(checks.values()), 'model session ready identity differs: ' +
                ', '.join(f'{name}={value}' for name, value in checks.items()))
        # comm may contain spaces or parentheses; fields after the final ')' start at field 3.
        proc = Path('/proc', str(ready['pid']), 'stat').read_text().rsplit(')', 1)[1].split()
        require(int(proc[19]) == ready['processStartTicks'], 'model worker process identity changed')
        return True

    def _model_exchange(self, directory, operation, tree, session, cancellation, method, payload):
        require(self._model_ready(directory, operation, tree), 'model session not ready')
        conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            conn.settimeout(max(.001, min(.2, session['deadline'] - time.monotonic())))
            conn.connect(str(directory / 'model-session.sock'))
            pid, uid, gid = struct.unpack('3i', conn.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            ready = read_json(directory / 'model-session.ready.json')
            require(pid == ready['pid'] and pid in self._tree_pids(tree) and uid == os.geteuid()
                    and gid == os.getegid() and self._model_ready(directory, operation, tree), 'model session peer differs')
            body = encoded({'token':session['token'], 'method':method, 'payload':payload})
            require(len(body) <= 2 * MAX_BODY, 'model request exceeds bound')
            with self.lock:
                require(not cancellation.is_set() and time.monotonic() < session['deadline'],
                        'model session cancelled or expired')
                conn.sendall(struct.pack('!I', len(body)) + body)
            def receive(count):
                data = bytearray(); remaining = count
                while remaining:
                    require(not cancellation.is_set() and time.monotonic() < session['deadline'], 'model session cancelled or expired')
                    try: part = conn.recv(remaining)
                    except socket.timeout: continue
                    require(part, 'model session response incomplete')
                    data.extend(part); remaining -= len(part)
                return data
            size = struct.unpack('!I', receive(4))[0]
            require(0 < size <= 2 * MAX_BODY, 'model response exceeds bound')
            response = json.loads(receive(size))
            require(isinstance(response, dict) and set(response) == {'ok','result'} and response['ok'] is True,
                    'model session failed')
            return response['result']
        finally: conn.close()

    def _model_action(self, operation_id, method, value, *, defer_finish=False):
        with self.lock:
            record = self._record(operation_id)
            require(record['status'] == 'ready' and operation_id in self.active and operation_id in self.trees
                    and operation_id in self.sessions, 'model session is not ready')
            session = self.sessions[operation_id]; cancellation = self.active[operation_id][0]
            require('deadline' in session and not cancellation.is_set() and time.monotonic() < session['deadline']
                    and session['lock'].acquire(blocking=False), 'model session busy, cancelled or expired')
            tree = self.trees[operation_id]
        directory = self.operations / operation_id
        try:
            operation = verify_operation(read_json(directory / 'request.json'))
            require(operation['operationId'] == operation_id and operation['bindingSha256'] == record['bindingSha256'],
                    'model session operation changed')
            source = operation['source']; receipts = session['receipts']
            if method == 'submit':
                require(isinstance(value, dict) and set(value) == {'ordinal','inputSha256','bodyBase64'}
                        and type(value['ordinal']) is int and value['ordinal'] == len(receipts)
                        and value['ordinal'] < source['maxBatches'], 'model batch sequence differs')
                raw = base64.b64decode(value['bodyBase64'], validate=True)
                require(len(raw) <= MAX_BODY and hashlib.sha256(raw).hexdigest() == value['inputSha256']
                        and session['totalBytes'] + len(raw) <= source['maxTotalBytes'], 'model batch input differs')
            elif method == 'read_completed':
                require(isinstance(value, dict) and set(value) == {'ordinal','inputSha256'}
                        and type(value['ordinal']) is int and 0 <= value['ordinal'] < len(receipts)
                        and receipts[value['ordinal']]['inputSha256'] == value['inputSha256'], 'model batch receipt unavailable')
            else:
                require(method == 'finish' and isinstance(value, dict) and set(value) == {'batches'}
                        and receipts and value['batches'] == receipts, 'complete ordered batch receipts required')
            result = self._model_exchange(directory, operation, tree, session, cancellation, method, value)
            require(not cancellation.is_set() and time.monotonic() < session['deadline'], 'model session cancelled or expired')
            folder = directory / 'model-session'
            if method in ('submit','read_completed'):
                ordinal = value['ordinal']; stem = f'{ordinal:08d}'
                require(isinstance(result, dict) and set(result) == {'operationId','bindingSha256','ordinal','inputSha256','outputSha256','result','provisional'}
                        and result['operationId'] == operation_id and result['bindingSha256'] == operation['bindingSha256']
                        and result['ordinal'] == ordinal and result['inputSha256'] == value['inputSha256']
                        and result['provisional'] is True, 'model batch response identity differs')
                output = encoded(result['result']); input_path = folder / (stem + '.input'); output_path = folder / (stem + '.output')
                receipt_path = folder / (stem + '.receipt.json')
                require(all(p.is_file() and not p.is_symlink() for p in (input_path,output_path,receipt_path))
                        and input_path.stat().st_size <= MAX_BODY and output_path.stat().st_size <= MAX_BODY
                        and sha(input_path) == result['inputSha256'] and output_path.read_bytes() == output
                        and hashlib.sha256(output).hexdigest() == result['outputSha256']
                        and read_json(receipt_path) == {k:result[k] for k in ('operationId','bindingSha256','ordinal','inputSha256','outputSha256')},
                        'persisted model batch differs')
                row = {k:result[k] for k in ('ordinal','inputSha256','outputSha256')}
                with self.lock:
                    require(operation_id in self.active and not cancellation.is_set() and time.monotonic() < session['deadline']
                            and self._record(operation_id)['status'] == 'ready', 'model session cancelled or expired')
                    if method == 'submit':
                        require(input_path.read_bytes() == raw and session['totalBytes'] + len(raw) + len(output) <= source['maxTotalBytes'], 'model total budget differs')
                        receipts.append(row); session['totalBytes'] += len(raw) + len(output)
                    else: require(receipts[ordinal] == row, 'completed model receipt changed')
                return result
            summary = folder / 'finished.json'
            verified_bytes = 0
            for row in receipts:
                stem = f"{row['ordinal']:08d}"
                input_path = folder / (stem + '.input'); output_path = folder / (stem + '.output')
                receipt_path = folder / (stem + '.receipt.json')
                require(all(p.is_file() and not p.is_symlink() for p in (input_path,output_path,receipt_path))
                        and input_path.stat().st_size <= MAX_BODY and output_path.stat().st_size <= MAX_BODY
                        and receipt_path.stat().st_size <= 4096 and sha(input_path) == row['inputSha256']
                        and sha(output_path) == row['outputSha256']
                        and read_json(receipt_path) == {'operationId':operation_id, 'bindingSha256':operation['bindingSha256'], **row},
                        'model finish batch bytes changed')
                verified_bytes += input_path.stat().st_size + output_path.stat().st_size
            require(summary.is_file() and not summary.is_symlink() and summary.stat().st_size <= 4096
                    and read_json(summary) == result and result.get('operationId') == operation_id
                    and result.get('bindingSha256') == operation['bindingSha256'] and result.get('requestId') == source['requestId']
                    and result.get('batchCount') == len(receipts) and result.get('totalBytes') == session['totalBytes'] == verified_bytes
                    and result.get('batchesSha256') == hashlib.sha256(encoded(receipts)).hexdigest()
                    and result.get('status') == 'computation_completed_release_unverified', 'model finish receipt differs')
            with self.lock:
                require(operation_id in self.active and not cancellation.is_set() and time.monotonic() < session['deadline'], 'model session cancelled or expired')
                record = self._record(operation_id); require(record['status'] == 'ready', 'model session state changed')
                record.update(status='finishing', modelSummarySha256=sha(summary)); persist(directory / 'status.json', record)
                if not defer_finish: self.active[operation_id][1].set()
            return {'operationId':operation_id, 'status':'finishing'}
        except BaseException:
            cancellation.set()
            raise
        finally: session['lock'].release()

    def _gpu_pids(self):
        command = [self.config['nvidiaSmi'], '--query-compute-apps=gpu_uuid,pid', '--format=csv,noheader,nounits']
        output = subprocess.run(command, text=True, capture_output=True, check=True, timeout=5).stdout
        result = set()
        for row in output.splitlines():
            parts = [value.strip() for value in row.split(',')]
            require(len(parts) == 2 and parts[1].isdigit(), 'GPU process query is unavailable or incomplete')
            if parts[0] == self.config['gpuUuid']: result.add(int(parts[1]))
        return result

    @staticmethod
    def _cgroup_pids(root):
        return {int(pid) for path in root.rglob('cgroup.procs') for pid in path.read_text().split()}

    @staticmethod
    def _tree_pids(tree):
        return Worker._cgroup_pids(tree.group)

    def _delegated_root(self, boot):
        cid = self.config['containerId']
        require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid),
                'exact delegated Docker child path required')
        classic = boot.get('cgroupRoot') == '/delegated/' + cid
        systemd = boot.get('cgroupRoot') == '/delegated/docker-' + cid + '.scope'
        require(classic or systemd, 'exact delegated Docker child path required')
        root = Path(boot['cgroupRoot'])
        own = Path('/proc/self/cgroup').read_text().strip()
        expected_own = (re.fullmatch(r'0::/docker/consense-[a-z0-9-]+/' + cid, own) if classic
                        else own == '0::/system.slice/docker-' + cid + '.scope')
        require(root.name == (cid if classic else 'docker-' + cid + '.scope')
                and expected_own
                and root.is_dir() and not root.is_symlink() and not root.parent.is_symlink(),
                'worker Docker child cgroup identity differs')
        if systemd:
            real = Path('/sys/fs/cgroup/system.slice/docker-' + cid + '.scope')
            require(real.is_dir() and not real.is_symlink() and not real.parent.is_symlink()
                    and (root.stat().st_dev, root.stat().st_ino) == (real.stat().st_dev, real.stat().st_ino),
                    'delegated cgroup does not match worker systemd scope')
        source, ancestor = root / 'cgroup.procs', root.parent / 'cgroup.procs'
        require(source.is_file() and source.stat().st_uid == os.geteuid() and os.access(source, os.W_OK)
                and ancestor.is_file() and ancestor.stat().st_uid == 0 and not os.access(ancestor, os.W_OK),
                'only the exact Docker child cgroup may be delegated')
        require(self._cgroup_pids(root) == {os.getpid()}, 'delegated cgroup is not initially isolated')
        return root

    def _listener_owned(self, tree, port):
        inodes = set()
        for protocol in ('tcp', 'tcp6'):
            for line in Path('/proc/net', protocol).read_text().splitlines()[1:]:
                row = line.split()
                if len(row) >= 10 and row[3] == '0A' and int(row[1].rsplit(':', 1)[1], 16) == port:
                    if protocol != 'tcp' or row[1].split(':', 1)[0] != '0100007F': return False
                    inodes.add(row[9])
        if len(inodes) != 1: return False
        return inodes <= self._socket_inodes(tree)

    def _socket_inodes(self, tree):
        owned = set()
        for pid in self._tree_pids(tree):
            try:
                for descriptor in Path('/proc', str(pid), 'fd').iterdir():
                    try: target = os.readlink(descriptor)
                    except FileNotFoundError: continue
                    match = re.fullmatch(r'socket:\[(\d+)\]', target)
                    if match: owned.add(match[1])
            except FileNotFoundError: continue
        return owned

    def _connection_owned(self, tree, connection, port):
        require(connection.sock is not None, 'provider connection socket unavailable')
        local_host, local_port = connection.sock.getsockname()
        require(local_host == '127.0.0.1' and connection.sock.getpeername() == ('127.0.0.1', port),
                'provider connection must use the configured IPv4 loopback')
        peers = set()
        for line in Path('/proc/net/tcp').read_text().splitlines()[1:]:
            row = line.split()
            if len(row) >= 10 and row[3] == '01' and row[1] == '0100007F:%04X' % port \
                    and row[2] == '0100007F:%04X' % local_port:
                peers.add(row[9])
        return len(peers) == 1 and peers <= self._socket_inodes(tree)

    def provider_request(self, operation_id, method, path, body, content_type, dispatch_id):
        """Run one authenticated, task-bound OCR provider transaction and retain its raw reply."""
        require(isinstance(dispatch_id, str) and re.fullmatch('[a-f0-9]{32}', dispatch_id), 'invalid provider dispatch ID')
        from native_provider_lifecycle import _watch_cancellation
        with self.lock:
            record = self._record(operation_id)
            require(record['status'] == 'ready' and operation_id in self.active and operation_id in self.trees
                    and operation_id in self.sessions, 'owned provider is not ready')
            cancellation = self.active[operation_id][0]; tree = self.trees[operation_id]; session = self.sessions[operation_id]
            require(not cancellation.is_set(), 'owned provider was cancelled')
        require(session['lock'].acquire(blocking=False), 'provider operation is already in progress')
        directory = self.operations / operation_id
        connection = response = timer = None; stop_watching = lambda: None
        readers, header_wire, extra_wire, framing_wire, overflow_body, close_errors = [], bytearray(), bytearray(), bytearray(), bytearray(), []
        header_truncated = False
        parsing_header = True
        operation_error = body_sync_error = body_write_error = body_primary_error = body_cleanup_error = None
        failure_record = None; early_cleanup_errors = []; descriptor_cleanup_errors = []; captured_stream = None
        pending_body = b''; resources_cleaned = False; dispatched = False
        capture_created = capture_open_attempted = reservation_ready = False
        phase = 'reservation'
        capture = directory / ('provider-' + dispatch_id + '.bin')
        receipt = capture.with_suffix('.json'); count = 0; digest = hashlib.sha256(); upstream_status = None; body_complete = False
        reservation = capture.with_suffix('.reservation')
        reserved = {}
        receipt_write_attempted = False
        evidence_paths = [capture, receipt, capture.with_suffix('.failure.json'), capture.with_suffix('.wire'), capture.with_suffix('.overflow'),
                          capture.with_suffix('.body-fragment'), capture.with_suffix('.cleanup.json')]
        def safe_error_type(error):
            categories = (TimeoutError, ConnectionRefusedError, ConnectionResetError, BrokenPipeError,
                          OSError, ValueError, http.client.HTTPException)
            return next((kind.__name__ for kind in categories if isinstance(error, kind)), 'other')
        def fd_stream(path, mode):
            fd = os.dup(reserved[path])
            try: return os.fdopen(fd, mode)
            except BaseException as error:
                try: os.close(fd)
                except BaseException as cleanup_error:
                    descriptor_cleanup_errors.append(cleanup_error)
                    error.add_note('Duplicated evidence descriptor cleanup unknown: ' + type(cleanup_error).__name__)
                raise
        def verify_one(evidence_path):
            require(os.path.samestat(os.fstat(reserved[evidence_path]), os.stat(evidence_path, follow_symlinks=False)),
                    'provider evidence reservation identity changed')
        def write_owned(path, data):
            verify_one(path)
            primary = None
            try:
                with fd_stream(path, 'wb') as stream:
                    try:
                        if stream.write(data) != len(data): raise OSError('short evidence write')
                        stream.flush(); os.fsync(stream.fileno())
                    except BaseException as error:
                        primary = error; raise
            except BaseException as error:
                if primary is not None and error is not primary:
                    descriptor_cleanup_errors.append(error)
                    primary.add_note('Evidence stream cleanup unknown: ' + type(error).__name__)
                    raise primary
                raise
            verify_one(path)
        def read_capture():
            verify_one(capture)
            primary = None
            try:
                with fd_stream(capture, 'rb') as stream:
                    try: stream.seek(0); data = stream.read()
                    except BaseException as error: primary = error; raise
            except BaseException as error:
                if primary is not None and error is not primary:
                    descriptor_cleanup_errors.append(error)
                    primary.add_note('Capture read stream cleanup unknown: ' + type(error).__name__)
                    raise primary
                raise
            verify_one(capture)
            return data
        def verify_owned():
            for evidence_path in reserved: verify_one(evidence_path)
        def cleanup_resources():
            nonlocal resources_cleaned
            if resources_cleaned: return []
            resources_cleaned = True; errors = []
            def cleanup(action):
                try: action()
                except BaseException as error: errors.append(error)
            if timer is not None: cleanup(timer.cancel); cleanup(timer.join)
            cleanup(stop_watching)
            if response is not None: cleanup(response.close)
            if connection is not None: cleanup(connection.close)
            errors.extend(close_errors)
            return errors
        try:
            operation = verify_operation(read_json(directory / 'request.json')); task = operation['source']
            require(operation['operationId'] == operation_id and operation['bindingSha256'] == record['bindingSha256'],
                    'provider operation identity changed')
            request_identity = {'requestByteCount':len(body), 'requestSha256':hashlib.sha256(body).hexdigest(),
                                'requestContentType':content_type}
            reserved[reservation] = os.open(reservation, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                for evidence_path in evidence_paths:
                    reserved[evidence_path] = os.open(evidence_path, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
            except BaseException as claim_error:
                try:
                    write_owned(reservation, encoded({'operationId':operation_id,'bindingSha256':operation['bindingSha256'],
                        'dispatchId':dispatch_id,'method':method,'path':path,**request_identity,'status':'claim_failed',
                        'reservedEmptyFiles':[p.name for p in reserved if p != reservation], 'errorType':type(claim_error).__name__}))
                except BaseException as evidence_error:
                    claim_error.add_note('Reservation failure evidence unavailable: ' + type(evidence_error).__name__)
                raise
            reservation_body = encoded({'operationId':operation_id, 'bindingSha256':operation['bindingSha256'],
                'dispatchId':dispatch_id, 'method':method, 'path':path, **request_identity, 'status':'reserved',
                'reservedEmptyFiles':[p.name for p in evidence_paths], 'emptyFileMeaning':'reserved_placeholder_until_receipt_identifies_content'})
            write_owned(reservation, reservation_body)
            reservation_ready = True
            phase = 'evidence_validation'
            if os.name == 'posix':
                sync_directory(directory)
            verify_owned()
            stage = record['stage']; profile = self.config['stages'][stage]
            url = urlsplit(profile['readyUrl'])
            phase = 'listener_ownership'
            require(url.scheme == 'http' and url.hostname == '127.0.0.1' and url.port
                    and self._listener_owned(tree, url.port), 'provider listener is not owned by the operation')
            require(not cancellation.is_set(), 'owned provider was cancelled')
            phase = 'route_validation'
            payload = None
            if content_type == 'application/json' and body: payload = json.loads(body)
            if stage == 'paddle':
                require(method == 'POST' and path == '/layout-parsing' and content_type == 'application/json'
                        and isinstance(payload,dict) and not session.get('paddleDispatched')
                        and isinstance(profile.get('providerOptions'),dict)
                        and {key:value for key,value in payload.items() if key != 'file'} == profile['providerOptions'],
                        'unsupported Paddle request or parser options')
                raw = base64.b64decode(payload['file'], validate=True)
                require(len(raw) == task['inputByteCount'] and hashlib.sha256(raw).hexdigest() == task['inputSha256'],
                        'Paddle raster differs from bound source')
                session['paddleDispatched'] = True
                route = 'paddle'
            else:
                require(stage == 'mineru', 'unsupported native provider stage')
                upload = session.get('uploadId'); file_id = session.get('fileId'); job_id = session.get('jobId')
                if method == 'POST' and path == '/v1/uploads' and not upload and not file_id:
                    require(isinstance(payload,dict) and payload.get('sha256sum') == task['inputSha256']
                            and payload.get('bytes') == task['inputByteCount'], 'MinerU upload input differs from bound source')
                    route = 'upload'
                elif upload and method == 'PUT' and path == '/v1/uploads/' + upload + '/content':
                    require(content_type == 'application/octet-stream' and len(body) == task['inputByteCount']
                            and hashlib.sha256(body).hexdigest() == task['inputSha256'], 'MinerU upload content differs from bound source')
                    route = 'upload-content'
                elif upload and method == 'POST' and path == '/v1/uploads/' + upload + '/complete':
                    require(isinstance(payload,dict) and payload.get('sha256sum') == task['inputSha256'], 'MinerU completion identity differs')
                    route = 'upload-complete'
                elif file_id and not job_id and method == 'POST' and path == '/v1/parse/jobs':
                    require(isinstance(payload,dict) and isinstance(profile.get('providerOptions'),dict)
                            and {key:value for key,value in payload.items() if key != 'files'} == profile['providerOptions']
                            and payload.get('files') == [{'source':{'type':'file_id','file_id':file_id}}],
                            'MinerU job source differs from owned upload')
                    route = 'job-create'
                elif job_id and method in ('GET','DELETE') and path == '/v1/parse/jobs/' + job_id:
                    route = 'job-status' if method == 'GET' else 'job-delete'
                elif session.get('zipId') and method == 'GET' and path == '/v1/files/' + session['zipId'] + '/content':
                    route = 'zip'
                else: raise ValueError('unsupported or unowned MinerU provider request')
            require(not session.get('providerOutcomeUnknown') or route == 'job-delete',
                    'previous provider outcome is unknown')
            remaining = (record['deadlineUnixMs'] / 1000) - time.time()
            require(0 < remaining <= profile['timeoutSeconds'], 'provider operation deadline elapsed')
            phase = 'connection'
            connection = ProviderConnection('127.0.0.1', url.port, timeout=remaining)
            connection.connect()
            phase = 'connection_ownership'
            ownership_deadline = time.monotonic() + max(0, record['deadlineUnixMs'] / 1000 - time.time())
            while time.monotonic() < ownership_deadline:
                require(self._listener_owned(tree, url.port) and not cancellation.is_set(), 'provider listener ownership changed')
                if self._connection_owned(tree, connection, url.port): break
                time.sleep(min(.05, max(0, ownership_deadline - time.monotonic())))
            else: raise ValueError('provider connection is not owned by the task')
            phase = 'dispatch_preparation'
            def shutdown():
                if connection.sock:
                    try: connection.sock.shutdown(socket.SHUT_RDWR)
                    except OSError: pass
            remaining = (record['deadlineUnixMs'] / 1000) - time.time()
            require(remaining > 0, 'provider operation deadline elapsed')
            timer = threading.Timer(remaining, shutdown); timer.daemon = True; timer.start()
            stop_watching = _watch_cancellation(cancellation.is_set, shutdown)
            from fixed_gpu_client import _ResponseReader
            read_deadline = time.monotonic() + remaining
            class BufferedOnly(Exception): pass
            class CaptureReader(_ResponseReader):
                def readinto(self, buffer):
                    nonlocal header_truncated
                    while True:
                        if getattr(self, 'buffered_only', False): raise BufferedOnly()
                        if cancellation.is_set() or time.monotonic() >= read_deadline:
                            raise TimeoutError('provider request cancelled or expired')
                        if self.selector.select(min(.05, max(0, read_deadline-time.monotonic()))):
                            if cancellation.is_set() or time.monotonic() >= read_deadline:
                                raise TimeoutError('provider request cancelled or expired')
                            try: size = self.sock.recv_into(buffer)
                            except BlockingIOError: continue
                            if parsing_header and size:
                                available = max(0, 8 * 1024 * 1024 - len(header_wire))
                                header_wire.extend(buffer[:min(size, available)])
                                if size > available: header_truncated = True
                            return size
            class CaptureSocket:
                def __init__(self, sock): self.sock = sock
                def makefile(self, mode):
                    reader = CaptureReader(self.sock, read_deadline, cancellation.is_set)
                    readers.append(reader)
                    return io.BufferedReader(reader)
            class CaptureResponse(http.client.HTTPResponse):
                def framing_line(self):
                    line = bytearray()
                    while len(line) <= http.client._MAXLINE:
                        if cancellation.is_set() or time.monotonic() >= read_deadline:
                            raise TimeoutError('provider request cancelled or expired')
                        part = self.fp.read1(1)
                        if not part: return bytes(line)
                        line.extend(part); framing_wire.extend(part)
                        if part == b'\n': return bytes(line)
                    raise http.client.LineTooLong('chunk framing')
                def _read_next_chunk_size(self):
                    line = self.framing_line()
                    token = rb"[!#$%&'*+\-.^_`|~0-9A-Za-z]+"
                    quoted = rb'"(?:[\x09\x20\x21\x23-\x5b\x5d-\x7e\x80-\xff]|\\[\x09\x20-\x7e\x80-\xff])*"'
                    match = re.fullmatch(rb'([0-9a-fA-F]+)(?:[ \t]*;[ \t]*' + token
                        + rb'(?:[ \t]*=[ \t]*(?:' + token + b'|' + quoted + rb'))?)*\r\n', line)
                    if not match: raise ValueError('invalid chunk size or extension')
                    return int(match.group(1), 16)
                def _read_and_discard_trailer(self):
                    for _ in range(http.client._MAXHEADERS + 1):
                        line = self.framing_line()
                        if line == b'\r\n': return
                        if not re.fullmatch(rb"[!#$%&'*+\-.^_`|~0-9A-Za-z]+:[\x09\x20-\x7e\x80-\xff]*\r\n", line):
                            raise http.client.HTTPException('invalid chunk trailer')
                    raise http.client.HTTPException('too many chunk trailers')
                def _get_chunk_left(self):
                    left = self.chunk_left
                    if not left:
                        if left is not None:
                            ending = bytearray()
                            while len(ending) < 2:
                                part = self.fp.read1(2-len(ending))
                                if not part: raise http.client.IncompleteRead(bytes(ending))
                                ending.extend(part); framing_wire.extend(part)
                            if ending != b'\r\n': raise http.client.HTTPException('invalid chunk terminator')
                        left = self._read_next_chunk_size()
                        if left == 0:
                            self._read_and_discard_trailer(); self._close_conn(); left = None
                        self.chunk_left = left
                    return left
                def _close_conn(self):
                    if self.fp is not None and readers:
                        readers[-1].buffered_only = True
                        try:
                            while True:
                                value = self.fp.read1(65536)
                                if not value: break
                                extra_wire.extend(value)
                        except BufferedOnly: pass
                        except Exception as error: close_errors.append(error)
                    try: return super()._close_conn()
                    except Exception as error: close_errors.append(error)
            connection.response_class = lambda sock, *args, **kwargs: CaptureResponse(CaptureSocket(sock), *args, **kwargs)
            verify_owned()
            phase = 'dispatch'
            with self.lock:
                require(operation_id in self.active and self.active[operation_id][0] is cancellation
                        and self.sessions.get(operation_id) is session and not cancellation.is_set(),
                        'owned provider was cancelled or changed')
                provider_socket = connection.sock
                require(provider_socket is not None, 'owned provider socket unavailable')
                session['providerSocket'] = provider_socket
                dispatched = True
            connection.request(method, path, body=body if body else None, headers={'Content-Type':content_type})
            response = connection.getresponse(); upstream_status = response.status
            lengths = response.headers.get_all('Content-Length', [])
            transfers = response.headers.get_all('Transfer-Encoding', [])
            if upstream_status == 204:
                require(not lengths and not transfers, '204 provider response cannot contain framing fields')
                response.chunked = False; response.length = 0
            elif upstream_status == 304:
                require((not lengths and not transfers)
                        or (len(lengths) == 1 and lengths[0].isdigit() and not transfers)
                        or (not lengths and len(transfers) == 1 and transfers[0].lower() == 'chunked'),
                        '304 provider metadata framing is ambiguous or unsupported')
                response.chunked = False; response.length = 0
            else:
                require((len(lengths) == 1 and lengths[0].isdigit() and not transfers)
                        or (not lengths and len(transfers) == 1 and transfers[0].lower() == 'chunked'),
                        'provider response framing is ambiguous or unsupported')
            parsing_header = False; header_wire.clear()
            require(response.length is not None or response.chunked, 'provider response framing cannot prove completeness')
            content_type_out = response.getheader('content-type','application/octet-stream')
            require(not any(c in content_type_out for c in '\r\n\x00'), 'invalid upstream content type')
            capture_open_attempted = True
            capture_created = True
            with fd_stream(capture, 'wb') as stream:
                try:
                    while True:
                        chunk = response.read1(65536)
                        if not chunk: break
                        if count + len(chunk) > MAX_PROVIDER_BODY:
                            overflow_body.extend(chunk)
                            raise ValueError('provider response exceeds existing 1 GiB bound')
                        pending_body = chunk
                        try:
                            if stream.write(chunk) != len(chunk): raise OSError('short provider body write')
                        except BaseException as error:
                            body_write_error = type(error).__name__
                            raise
                        digest.update(chunk); count += len(chunk); pending_body = b''
                        framing_wire.clear()
                    require(response.length in (None, 0), 'provider response ended before Content-Length')
                    try: stream.flush(); os.fsync(stream.fileno())
                    except Exception as sync_error:
                        body_sync_error = type(sync_error).__name__
                        raise
                except BaseException as error:
                    body_primary_error = error
                    raise
            require(not extra_wire, 'provider response exceeded its declared framing')
            body_complete = True
            require(not cancellation.is_set() and time.time() * 1000 < record['deadlineUnixMs'], 'provider request cancelled or expired')
            if close_errors: raise close_errors[0]
            if 200 <= upstream_status < 300 and route in ('upload','upload-complete','job-create','job-status'):
                result = json.loads(read_capture())
                if route in ('upload','upload-complete'):
                    if result.get('status') == 'completed':
                        fid = result.get('file',{}).get('id','')
                        require(result.get('sha256sum') == task['inputSha256'] and result.get('bytes') == task['inputByteCount']
                                and re.fullmatch(r'file-[A-Za-z0-9_-]+', fid), 'MinerU completed upload identity differs')
                        session['fileId'] = fid
                    else:
                        uid = result.get('id',''); require(re.fullmatch(r'upload_[A-Za-z0-9_-]+', uid), 'MinerU upload ID invalid')
                        session['uploadId'] = uid
                elif route == 'job-create':
                    jid = result.get('job_id',''); require(re.fullmatch(r'job_[A-Za-z0-9_-]+', jid), 'MinerU job ID invalid')
                    session['jobId'] = jid
                else:
                    require(result.get('job_id') == job_id, 'MinerU response job identity differs')
                    if result.get('status') == 'completed':
                        files = result.get('files',[])
                        require(len(files) == 1 and files[0].get('file_id') == file_id and files[0].get('status') == 'completed',
                                'MinerU completed source differs')
                        zid = files[0].get('output_files',{}).get('zip',{}).get('file_id','')
                        require(re.fullmatch(r'file-[A-Za-z0-9_-]+', zid), 'MinerU ZIP identity invalid')
                        session['zipId'] = zid; session['jobCompleted'] = True
            if 200 <= upstream_status < 300 and route == 'job-delete':
                result = json.loads(read_capture())
                if result.get('status') == 'canceled' and result.get('job_id') == job_id: session['jobCancelled'] = True
            early_cleanup_errors = cleanup_resources()
            if early_cleanup_errors: raise early_cleanup_errors[0]
            verify_owned()
            receipt_write_attempted = True
            write_owned(receipt, encoded({'operationId':operation_id, 'bindingSha256':operation['bindingSha256'], 'dispatchId':dispatch_id, 'method':method,
                'path':path, **request_identity, 'status':upstream_status, 'complete':True, 'bytes':count, 'sha256':digest.hexdigest()}))
            captured_stream = fd_stream(capture, 'rb')
            try: captured_stream.seek(0)
            except BaseException as error:
                try: captured_stream.close()
                except BaseException as cleanup_error:
                    descriptor_cleanup_errors.append(cleanup_error)
                    error.add_note('Capture stream cleanup unknown: ' + type(cleanup_error).__name__)
                captured_stream = None
                raise
            return upstream_status, content_type_out, captured_stream, count
        except BaseException as error:
            if body_primary_error is not None and error is not body_primary_error:
                body_cleanup_error = type(error).__name__
                body_primary_error.add_note('Body stream cleanup failed; outcome unknown: ' + body_cleanup_error)
                error = body_primary_error
            operation_error = error
            if dispatched: session['providerOutcomeUnknown'] = True
            if response is not None and getattr(response, 'fp', None) is not None and readers and not parsing_header:
                readers[-1].buffered_only = True
                try:
                    while True:
                        value = response.fp.read1(65536)
                        if not value: break
                        extra_wire.extend(value)
                except BufferedOnly: pass
                except Exception as cleanup_error: error.add_note('Buffered capture failed: ' + type(cleanup_error).__name__)
            cleanup_errors = early_cleanup_errors + cleanup_resources()
            if cleanup_errors: error.add_note('Provider cleanup failed: ' + type(cleanup_errors[0]).__name__)
            if capture_open_attempted or header_wire or extra_wire or framing_wire or overflow_body or pending_body or cleanup_errors:
                wire = bytes(header_wire if parsing_header else framing_wire + extra_wire)
                wire_ref = None
                overflow_ref = None
                wire_error = overflow_error = fragment_error = None
                write_ref = None
                if wire:
                    wire_path = capture.with_suffix('.wire')
                    try:
                        write_owned(wire_path, wire)
                        wire_ref = {'file':wire_path.name, 'bytes':len(wire), 'sha256':hashlib.sha256(wire).hexdigest(),
                                    'scope':'http_header_wire_prefix' if parsing_header else 'http_unprocessed_wire_fragment',
                                    'truncated':header_truncated if parsing_header else False}
                    except Exception as sync_error:
                        wire_error = type(sync_error).__name__
                        error.add_note('Wire evidence durability unknown: ' + type(sync_error).__name__)
                if overflow_body:
                    overflow_path = capture.with_suffix('.overflow')
                    try:
                        write_owned(overflow_path, overflow_body)
                        overflow_ref = {'file':overflow_path.name, 'bytes':len(overflow_body),
                            'sha256':hashlib.sha256(overflow_body).hexdigest(), 'scope':'upstream_body_limit_crossing_chunk',
                            'intendedOffset':count, 'bodyLimit':MAX_PROVIDER_BODY}
                    except Exception as sync_error:
                        overflow_error = type(sync_error).__name__
                        error.add_note('Overflow evidence durability unknown: ' + type(sync_error).__name__)
                if pending_body:
                    try:
                        fragment_path = capture.with_suffix('.body-fragment')
                        write_owned(fragment_path, pending_body)
                        write_ref = {'file':fragment_path.name, 'bytes':len(pending_body),
                            'sha256':hashlib.sha256(pending_body).hexdigest(), 'intendedOffset':count,
                            'mayOverlapBody':True, 'scope':'upstream_body_write_uncertain'}
                    except Exception as sync_error:
                        fragment_error = type(sync_error).__name__
                        error.add_note('Body fragment evidence durability unknown: ' + fragment_error)
                actual_bytes = 0
                body_durability = 'unknown' if capture_open_attempted and not capture_created else 'reserved_placeholder'
                body_path_identity = 'reserved_placeholder'
                if capture_created:
                    try:
                        verify_one(capture); body_path_identity = 'verified'
                    except Exception as identity_error:
                        body_path_identity = 'unknown'
                        error.add_note('Body evidence path identity unknown: ' + type(identity_error).__name__)
                    try:
                        os.fsync(reserved[capture])
                        body_durability = 'unknown' if body_sync_error or body_write_error or body_cleanup_error or body_path_identity != 'verified' else 'synced'
                    except Exception as sync_error:
                        body_durability = 'unknown'
                        error.add_note('Body evidence durability unknown: ' + type(sync_error).__name__)
                    try: actual_bytes = os.fstat(reserved[capture]).st_size
                    except Exception as stat_error:
                        actual_bytes = None; body_durability = 'unknown'
                        error.add_note('Body evidence size unknown: ' + type(stat_error).__name__)
                failed = {'operationId':operation_id, 'bindingSha256':operation['bindingSha256'], 'dispatchId':dispatch_id,
                    'method':method, 'path':path, **request_identity, 'status':upstream_status,
                    'complete':body_complete, 'bytes':actual_bytes,
                    'sha256':digest.hexdigest() if actual_bytes == count else None,
                    'errorType':type(error).__name__, 'bodyDurability':body_durability, 'bodyPathIdentity':body_path_identity, 'writtenBodyBytes':count}
                if body_sync_error is not None: failed['bodySyncErrorType'] = body_sync_error
                if dispatched: failed['providerOutcome'] = 'unknown'
                elif reservation_ready:
                    failed.update(providerOutcome='not_dispatched', phase=phase, errorType=safe_error_type(error))
                if body_write_error is not None: failed['bodyWriteErrorType'] = body_write_error
                if body_cleanup_error is not None: failed['bodyStreamCleanupErrorType'] = body_cleanup_error
                if cleanup_errors: failed['cleanupErrorTypes'] = [type(e).__name__ for e in cleanup_errors]
                if pending_body:
                    failed['bodyWriteFragmentDurability'] = 'unknown' if fragment_error else 'synced'
                    failed['bodyWriteFragmentBytes'] = len(pending_body)
                    failed['bodyWriteFragmentIntendedOffset'] = count
                if fragment_error is not None: failed['bodyWriteFragmentErrorType'] = fragment_error
                if write_ref is not None: failed['bodyWriteFragment'] = write_ref
                if wire_error is not None: failed['wireCaptureErrorType'] = wire_error
                if overflow_error is not None: failed['overflowCaptureErrorType'] = overflow_error
                if wire_ref is not None: failed['wire'] = wire_ref
                if overflow_ref is not None: failed['overflowBody'] = overflow_ref
                failure_record = failed
                try: write_owned(capture.with_suffix('.failure.json') if receipt_write_attempted else receipt, encoded(failed))
                except Exception as receipt_error: error.add_note('Failure receipt unavailable: ' + type(receipt_error).__name__)
            elif reservation_ready:
                failed = {'operationId':operation_id, 'bindingSha256':operation['bindingSha256'],
                          'dispatchId':dispatch_id, 'phase':phase, 'errorType':safe_error_type(error),
                          'providerOutcome':'unknown' if dispatched else 'not_dispatched'}
                failure_record = failed
                try: write_owned(capture.with_suffix('.failure.json'), encoded(failed))
                except Exception as receipt_error: error.add_note('Failure receipt unavailable: ' + type(receipt_error).__name__)
            raise error
        finally:
            cleanup_errors = cleanup_resources() + descriptor_cleanup_errors
            with self.lock:
                if session.get('providerSocket') is not None:
                    session.pop('providerSocket')
            try: session['lock'].release()
            except BaseException as error: cleanup_errors.append(error)
            cleanup_path = capture.with_suffix('.cleanup.json')
            for evidence_path, evidence_fd in reserved.items():
                if evidence_path == cleanup_path: continue
                try: os.close(evidence_fd)
                except BaseException as error: cleanup_errors.append(error)
            primary = operation_error or (cleanup_errors[0] if cleanup_errors else None)
            if cleanup_errors:
                if dispatched: session['providerOutcomeUnknown'] = True
                primary.add_note('Provider cleanup outcome unknown: ' + type(cleanup_errors[0]).__name__)
                if cleanup_path in reserved:
                    try:
                        write_owned(cleanup_path, encoded({'operationId':operation_id,'dispatchId':dispatch_id,
                            'cleanupStatus':'unknown','cleanupErrorTypes':[type(e).__name__ for e in cleanup_errors],
                            'providerOutcome':'unknown' if dispatched else 'not_dispatched'}))
                    except BaseException as receipt_error:
                        primary.add_note('Cleanup receipt unavailable: ' + type(receipt_error).__name__)
            if cleanup_path in reserved:
                try: os.close(reserved[cleanup_path])
                except BaseException as error:
                    if primary is None: primary = error
                    primary.add_note('Provider cleanup outcome unknown: ' + type(error).__name__)
                    if dispatched: session['providerOutcomeUnknown'] = True
            if primary is not None and operation_error is None:
                if captured_stream is not None:
                    try: captured_stream.close()
                    except BaseException as error: primary.add_note('Capture stream cleanup failed: ' + type(error).__name__)
                raise primary

    def _validate_profile(self, profile):
        boot = profile['boot']
        require(isinstance(boot, dict) and isinstance(boot.get('command'), list) and boot['command']
                and all(isinstance(x, str) and x and '\x00' not in x for x in boot['command']), 'invalid configured command')
        require(Path(boot['command'][0]).is_absolute() and Path(boot['cwd']).is_absolute(), 'absolute command and cwd required')
        require(boot.get('cgroupRoot') == self.config['cgroupRoot'], 'wrong cgroup root')
        require(isinstance(boot.get('env'), dict) and all(isinstance(k,str) and isinstance(v,str)
                and not k.startswith(('CONSENSE_FIXED_', 'CONSENSE_OPERATION_', 'CONSENSE_MODEL_SESSION_', 'CONSENSE_JOB_', 'CONSENSE_POD_', 'CONSENSE_WORKSPACE_PVC_'))
                and '\x00' not in k + v for k,v in boot['env'].items()), 'invalid configured environment')
        assets = boot.get('runtimeAssets')
        require(isinstance(assets,list) and assets, 'pinned runtime assets required')
        for row in assets:
            path = Path(row['path'])
            require(path.is_absolute() and path.is_file() and 0 < row['byteCount'] <= 32 * 1024 * 1024
                    and row['byteCount'] == path.stat().st_size
                    and re.fullmatch('[a-f0-9]{64}', row['sha256']) and sha(path) == row['sha256'], 'configured asset changed')
        require(str(Path(boot['command'][0]).resolve()) in {str(Path(x['path']).resolve()) for x in assets}, 'command is not pinned')
        if re.fullmatch('python(?:[0-9.]+)?(?:\\.exe)?', Path(boot['command'][0]).name.lower()):
            require(len(boot['command']) > 1 and Path(boot['command'][1]).is_absolute()
                    and str(Path(boot['command'][1]).resolve()) in {str(Path(x['path']).resolve()) for x in assets},
                    'Python entrypoint must be an explicitly pinned file')
        inner = profile.get('profile')
        if isinstance(inner, dict) and inner.get('providerKind') == 'cpu-protocol-stub':
            from chat_operation_worker import stub_token
            token_sha = inner.get('stubTokenSha256')
            require(isinstance(token_sha, str) and re.fullmatch('[a-f0-9]{64}', token_sha), 'fixed stub token digest required')
            token = stub_token(inner.get('stubTokenFile'), token_sha)
            require(len(token) >= 16 and token.encode('ascii') not in encoded(inner)
                    and token.encode('ascii') not in encoded({'command':boot['command'], 'env':boot['env']}),
                    'stub credentials must not appear in the operation profile or boot command')
            environment, command = inner.get('environment'), inner.get('command')
            require(isinstance(environment, dict) and 'CONSENSE_QWEN_STUB_TOKEN' not in environment
                    and 'CONSENSE_QWEN_STUB_TOKEN' not in boot['env'], 'stub credentials must come from the token file')
            pinned = {str(Path(row['path']).resolve()) for row in assets}
            require(isinstance(command, list) and len(command) >= 2 and all(isinstance(arg, str) and arg for arg in command)
                    and Path(command[0]).is_absolute() and Path(command[1]).is_absolute()
                    and re.fullmatch(r'python(?:[0-9.]+)?(?:\.exe)?', Path(command[0]).name.lower())
                    and str(Path(command[0]).resolve()) in pinned and str(Path(command[1]).resolve()) in pinned,
                    'stub Python command and entrypoint must be pinned runtime assets')
            for option in ('--fixture-file', '--ordinary-fixture-file'):
                require(not any(arg.startswith(option + '=') for arg in command), 'stub fixture option must be separate')
                positions = [index for index, arg in enumerate(command) if arg == option]
                require(len(positions) <= 1, 'stub fixture option must be unique')
                if positions:
                    index = positions[0] + 1
                    require(index < len(command) and Path(command[index]).is_absolute()
                            and str(Path(command[index]).resolve()) in pinned, 'stub fixture must be a pinned runtime asset')
        require(str(Path(self.config['nvidiaSmi']).resolve()) in {str(Path(x['path']).resolve()) for x in assets},
                'GPU inspection executable must be pinned')
        require(re.fullmatch('GPU-[A-Za-z0-9-]+', self.config['gpuUuid']) is not None, 'configured GPU UUID required')
        timeout = profile['timeoutSeconds']
        require(type(timeout) in (int,float) and 0 < timeout <= 86400, 'finite timeout required')
        require(profile.get('gpuRequired') is True, 'GPU release evidence required')
        return boot, timeout

    def _execute(self, directory, operation, profile, cancellation, finished):
        tree = None; witness = None; witness_trusted = True; observation_failure = None; tree_evidence_trusted = True
        owned_tree_driver = None
        cpu_stub = (operation['stage'] == 'business-llm' and profile['mode'] == 'job'
                    and profile['profile'].get('adapter') == 'consense.native-chat.v1'
                    and profile['profile'].get('providerKind') == 'cpu-protocol-stub')
        outcome = 'failed'; failure = None; response_valid = False; release = {'status': 'release_unknown'}
        try:
            boot, timeout = self._validate_profile(profile)
            root = self._delegated_root(boot)
            root_stat = root.stat()
            root_identity = (root_stat.st_dev, root_stat.st_ino)
            driver_assets = [row for row in boot['runtimeAssets']
                             if Path(row['path']).resolve() == Path(self.config['nvidiaSmi']).resolve()]
            require(len(driver_assets) == 1, 'unique pinned GPU inspection executable required')
            driver_sha256 = driver_assets[0]['sha256']
            if ((profile['mode'] == 'model-session' and owned_model_boot(boot))
                    or owned_ocr_boot(operation['stage'], profile, boot)):
                mps = Path('/tmp/nvidia-mps/control')
                if not mps.exists() and not mps.is_symlink():
                    try:
                        owned_tree_driver = observe_single_idle_device(self.config['nvidiaSmi'], driver_sha256,
                                                                       self.config['gpuUuid'])
                    except (OSError, ValueError, subprocess.SubprocessError):
                        pass
            witness_operation = {key:operation[key] for key in ('operationId','bindingSha256','profileSha256')}
            environment = dict(boot['env'])
            environment.update(CONSENSE_FIXED_OPERATION_ID=operation['operationId'],
                CONSENSE_FIXED_CONTAINER_ID=self.config['containerId'], CONSENSE_FIXED_WORKSPACE=str(self.workspace),
                CONSENSE_FIXED_CGROUP_ROOT=boot['cgroupRoot'],
                CONSENSE_OPERATION_REQUEST=str(directory / 'request.json'),
                CONSENSE_OPERATION_REQUEST_SHA256=sha(directory / 'request.json'),
                CONSENSE_OPERATION_RESULT=str(directory / 'response.json'),
                CONSENSE_OPERATION_STREAM=str(directory / 'stream.bin'))
            if profile['mode'] == 'model-session':
                session = self.sessions[operation['operationId']]
                environment.update(CONSENSE_MODEL_SESSION_PROFILE_SHA256=operation['profileSha256'],
                    CONSENSE_MODEL_SESSION_DEADLINE_MONOTONIC=str(session['deadline']),
                    CONSENSE_MODEL_SESSION_TOKEN_FILE=str(directory / 'model-session.token'))
            boot = {**boot, 'env': environment,
                    'cgroupIdentity': Path('/proc/self/cgroup').read_text().strip()}
            deadline = session['deadline'] if profile['mode'] == 'model-session' else time.monotonic() + timeout
            with preserve_stream_error(fdopen_owned(os.open(directory / 'stdout.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb')) as stdout, \
                    preserve_stream_error(fdopen_owned(os.open(directory / 'stderr.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb')) as stderr:
                tree = _LinuxTree(boot, stdout, stderr, timeout)
                with self.lock: self.trees[operation['operationId']] = tree
                self._set(directory, status='running', pid=tree.pid, cgroup=str(tree.group),
                          containerId=self.config['containerId'], containerIdentityScope='configured_not_independently_attested')
                while time.monotonic() < deadline:
                    if cancellation.is_set(): outcome = 'cancelled'; break
                    active = tree.active()
                    if active and not cpu_stub:
                        try:
                            witness = observe_running(tree, self.config['nvidiaSmi'], driver_sha256,
                                                      self.config['gpuUuid'], witness_operation, witness)
                        except GpuObservationIncomplete as error:
                            witness_trusted = False
                            tree_evidence_trusted = False
                            observation_failure = str(error)
                        except BaseException:
                            witness_trusted = False
                            tree_evidence_trusted = False
                            raise
                    if cancellation.is_set(): outcome = 'cancelled'; break
                    if time.monotonic() >= deadline: outcome = 'expired'; break
                    if profile['mode'] == 'model-session' and active and not finished.is_set():
                        if self._model_ready(directory, operation, tree) and self._record(operation['operationId'])['status'] == 'running':
                            self._set(directory, status='ready')
                    if profile['mode'] == 'session' and active and not finished.is_set():
                        url = urlsplit(profile['readyUrl'])
                        require(url.scheme == 'http' and url.hostname == '127.0.0.1' and url.port and not url.query and not url.fragment, 'literal loopback readiness URL required')
                        status = self._record(operation['operationId'])['status']
                        if not self._listener_owned(tree, url.port):
                            require(status != 'ready', 'owned provider listener is no longer provable')
                            time.sleep(.05); continue
                        if status == 'running':
                            conn = http.client.HTTPConnection(url.hostname, url.port, timeout=.2)
                            try:
                                conn.request('GET', url.path or '/')
                                ready = conn.getresponse().status == 200
                            except (OSError, http.client.HTTPException):
                                ready = False
                            finally:
                                conn.close()
                            if ready and self._listener_owned(tree, url.port) and self._record(operation['operationId'])['status'] == 'running':
                                self._set(directory, status='ready')
                    if finished.is_set():
                        # A model may finish after the preceding empty GPU sample.
                        if profile['mode'] == 'model-session' and active and witness is None:
                            try:
                                witness = observe_running(tree, self.config['nvidiaSmi'], driver_sha256,
                                                          self.config['gpuUuid'], witness_operation, witness)
                            except GpuObservationIncomplete as error:
                                witness_trusted = False
                                tree_evidence_trusted = False
                                observation_failure = str(error)
                            except BaseException:
                                witness_trusted = False
                                tree_evidence_trusted = False
                                raise
                            if cancellation.is_set(): outcome = 'cancelled'; break
                            if time.monotonic() >= deadline: outcome = 'expired'; break
                        outcome = 'completed'; break
                    if not active:
                        if profile['mode'] == 'job' and tree.process.poll() == 0:
                            response_path = directory / 'response.json'
                            require(response_path.is_file() and not response_path.is_symlink()
                                    and response_path.stat().st_size <= 2 * MAX_BODY, 'bounded regular response required')
                            require(sha(directory / 'request.json') == environment['CONSENSE_OPERATION_REQUEST_SHA256'], 'request changed during execution')
                            response = verify_response(read_json(response_path), operation)
                            with response_path.open('r+b') as stream: os.fsync(stream.fileno())
                            response_valid = True
                            request_body = json.loads(base64.b64decode(operation['bodyBase64'], validate=True)) if operation['stage'] == 'business-llm' else {}
                            if 200 <= response['status'] < 300 and request_body.get('stream') is True:
                                raw = base64.b64decode(response['bodyBase64'], validate=True)
                                stream_path = directory / 'stream.bin'
                                require(response['headers'].get('content-type','').split(';',1)[0].strip().lower() == 'text/event-stream'
                                        and stream_path.is_file() and not stream_path.is_symlink() and stream_path.stat().st_size <= MAX_BODY
                                        and stream_path.read_bytes() == raw
                                        and stream_completed(raw, operation['source'].get('httpPath')), 'upstream SSE did not complete successfully')
                            if 200 <= response['status'] < 300: outcome = 'completed'
                            else: failure = 'upstream HTTP ' + str(response['status'])
                        else: failure = 'owned task exited before completion'
                        break
                    time.sleep(.1)
                else: outcome = 'expired'
                stdout.flush(); stderr.flush(); os.fsync(stdout.fileno()); os.fsync(stderr.fileno())
        except BaseException as error:
            failure = type(error).__name__ + ': ' + str(error)
            if any('cleanup unknown:' in note for note in getattr(error, '__notes__', [])):
                failure += '; stream cleanup unknown'
        finally:
            try:
                with self.lock:
                    if cancellation.is_set(): outcome = 'cancelled'
                    record = read_json(directory / 'status.json'); record['status'] = 'settling'
                    persist(directory / 'status.json', record)
            except BaseException as error:
                outcome = 'failed'
                failure = (failure + '; ' if failure else '') + 'outcome arbitration failed: ' + str(error)
            if observation_failure:
                failure = (failure + '; ' if failure else '') + 'GPU observation incomplete: ' + observation_failure
            if tree is not None:
                try:
                    tree.stop()
                    until = time.monotonic() + 2
                    while time.monotonic() < until and (tree.active() or tree.process.poll() is None): time.sleep(.02)
                    require(tree.active() == 0 and tree.process.poll() is not None, 'owned cgroup remains populated')
                    observation = None
                    try:
                        if witness is not None and witness_trusted:
                            observation = observe_release(tree, self.config['nvidiaSmi'], witness,
                                                          witness_operation, self.config['gpuUuid'])
                        elif (witness is None and tree_evidence_trusted and owned_tree_driver is not None
                              and ((profile['mode'] == 'model-session' and owned_model_boot(profile['boot']))
                                   or owned_ocr_boot(operation['stage'], profile, profile['boot']))):
                            mps = Path('/tmp/nvidia-mps/control')
                            require(not mps.exists() and not mps.is_symlink(), 'CUDA MPS ownership is not isolated')
                            observe_owned_tree_release(tree, self.config['nvidiaSmi'], driver_sha256,
                                                       self.config['gpuUuid'], owned_tree_driver)
                            observation = {'status':'owned_cgroup_v2'}
                    finally:
                        tree.close()
                    require(self._cgroup_pids(root) == {os.getpid()},
                            'unexpected process remains in delegated Docker child subtree')
                    if observation is not None and observation['status'] == 'owned_cgroup_v2':
                        root_stat = root.stat()
                        require((root_stat.st_dev, root_stat.st_ino) == root_identity
                                and self._delegated_root(boot) == root, 'delegated cgroup identity or permissions changed')
                    for name in ('stdout.log', 'stderr.log'):
                        with (directory / name).open('r+b') as log: os.fsync(log.fileno())
                    if cpu_stub:
                        release = {'status':'released_cpu_only','cgroupPopulated':0}
                    else:
                        require(observation is not None, 'no trusted owned GPU PID was observed')
                        require(observation['status'] in ('local_observation_complete', 'owned_cgroup_v2'),
                                observation.get('reason','GPU release observation incomplete'))
                        if observation['status'] == 'owned_cgroup_v2':
                            release = {'status':'released','cgroupPopulated':0,
                                       'ownership':'delegated_cgroup_v2','processWaited':True}
                        else:
                            release = {'status':'released','cgroupPopulated':0,
                                       'observedGpuPids':[row['pid'] for row in witness['processes']]}
                except BaseException as error:
                    failure = (failure + '; ' if failure else '') + type(error).__name__ + ': ' + str(error)
            try:
                fields = {'status': outcome if release['status'] in ('released', 'released_cpu_only') else 'release_unknown',
                          'failure': failure, 'release': release}
                session = self.sessions.get(operation['operationId'], {})
                if operation['stage'] == 'mineru' and session.get('jobId') and not session.get('jobCompleted'):
                    fields['providerJobCancellation'] = 'confirmed' if session.get('jobCancelled') else 'unknown'
                if response_valid:
                    fields['responseSha256'] = sha(directory / 'response.json')
                if outcome == 'completed' and profile['mode'] == 'session':
                    fields['transactionSha256'] = sha(directory / 'transaction.json')
                if outcome == 'completed' and profile['mode'] == 'model-session':
                    fields['modelSummarySha256'] = sha(directory / 'model-session' / 'finished.json')
                self._publish_terminal(directory, fields)
            finally:
                with self.lock:
                    self.active.pop(operation['operationId'], None)
                    self.trees.pop(operation['operationId'], None)
                    self.sessions.pop(operation['operationId'], None)


def make_server(worker, address=('127.0.0.1', 28684), *, uds_path=None):
    if uds_path is None:
        require(address[0] == '127.0.0.1', 'GPU executor must bind loopback')
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def reply(self, status, value):
            body = encoded(value); self.send_response(status); self.send_header('Content-Type', 'application/json')
            if status >= 400: self.send_header('X-Consense-Gateway-Error', '1')
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
        def run_request(self):
            if getattr(self.server, 'require_peer_credentials', False):
                try:
                    _, uid, gid = struct.unpack('3i', self.connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                    require(uid == worker.config['peerUid'] and gid == worker.config['peerGid'], 'untrusted peer identity')
                except (OSError, ValueError, KeyError):
                    return self.reply(401, {'error':'unauthorized'})
            if not hmac.compare_digest(self.headers.get('Authorization',''), 'Bearer ' + worker.secret):
                return self.reply(401, {'error':'unauthorized'})
            proxy = None
            try:
                proxy = re.fullmatch('/operations/([a-f0-9]{32})/provider(/[^?#]*)', self.path)
                if proxy and self.command in ('GET','POST','PUT','DELETE'):
                    ids = self.headers.get_all('X-Consense-Provider-Dispatch-Id', [])
                    require(len(ids) == 1 and re.fullmatch('[a-f0-9]{32}', ids[0]), 'one valid provider dispatch ID required')
                    length = int(self.headers.get('Content-Length', '0'))
                    require(0 <= length <= MAX_BODY, 'provider request exceeds 64 MiB gateway bound')
                    content_type = self.headers.get('Content-Type', 'application/json')
                    require(content_type in ('application/json','application/octet-stream'), 'unsupported provider content type')
                    body = self.rfile.read(length); require(len(body) == length, 'provider request body incomplete')
                    try:
                        status, returned_type, capture, count = worker.provider_request(proxy[1], self.command, proxy[2], body, content_type, ids[0])
                    except Exception:
                        return self.reply(503, {'error':'provider operation failed'})
                    try:
                        with capture as stream:
                            self.send_response(status); self.send_header('Content-Type', returned_type)
                            self.send_header('Content-Length', str(count)); self.end_headers()
                            for chunk in iter(lambda: stream.read(65536), b''): self.wfile.write(chunk)
                            self.wfile.flush()
                    except OSError:
                        with worker.lock:
                            if proxy[1] in worker.active: worker._cancel_locked(proxy[1])
                    return
                match = re.fullmatch('/operations/([a-f0-9]{32})(/finish|/stream|/result|/model-batches|/model-batch-read|/finish-model|/model-summary)?', self.path)
                if self.command == 'GET' and match and not match[2]: return self.reply(200, worker.status(match[1]))
                if self.command == 'GET' and match and match[2] == '/stream': return self.stream_operation(match[1])
                if self.command == 'GET' and match and match[2] == '/result':
                    return self.reply(200, worker.result(match[1]))
                if self.command == 'GET' and match and match[2] == '/model-summary':
                    return self.reply(200, worker.model_summary(match[1]))
                if self.command == 'DELETE' and match and not match[2]: return self.reply(202, worker.cancel(match[1]))
                if self.command == 'POST' and match and match[2] in ('/model-batches','/model-batch-read','/finish-model'):
                    length = int(self.headers.get('Content-Length', '-1'))
                    require(0 <= length <= 2 * MAX_BODY, 'bounded Content-Length required')
                    raw = self.rfile.read(length); require(len(raw) == length, 'model request incomplete')
                    method = {'/model-batches':'submit','/model-batch-read':'read_completed','/finish-model':'finish'}[match[2]]
                    from native_provider_lifecycle import _watch_cancellation
                    def disconnected():
                        try: return bool(select.select([self.connection], [], [], 0)[0]) and not self.connection.recv(1, socket.MSG_PEEK)
                        except OSError: return True
                    def cancel_model():
                        with worker.lock:
                            if match[1] in worker.active: worker.active[match[1]][0].set()
                    stop_watching = _watch_cancellation(disconnected, cancel_model)
                    try:
                        result = worker._model_action(match[1], method, json.loads(raw), defer_finish=True)
                        require(not disconnected(), 'model client disconnected')
                        self.reply(202 if method == 'finish' else 200, result)
                        stop_watching(); stop_watching = lambda: None
                        if method == 'finish':
                            with worker.lock:
                                require(match[1] in worker.active and not worker.active[match[1]][0].is_set(), 'model session cancelled')
                                worker.active[match[1]][1].set()
                        return
                    except BaseException:
                        cancel_model(); raise
                    finally: stop_watching()
                if self.command == 'POST' and (self.path == '/operations' or match and match[2] == '/finish'):
                    length = int(self.headers.get('Content-Length', '-1'))
                    require(0 <= length <= 2 * MAX_BODY, 'bounded Content-Length required')
                    value = json.loads(self.rfile.read(length))
                    return self.reply(202, worker.start(value) if self.path == '/operations' else worker.finish(match[1], value))
                return self.reply(404, {'error':'unknown route'})
            except (ValueError, KeyError, TypeError, OSError, http.client.HTTPException) as error:
                try: return self.reply(503 if proxy else 400, {'error':'provider operation failed' if proxy else str(error)})
                except OSError: return
        def stream_operation(self, operation_id):
            with worker.lock:
                record = worker._record(operation_id)
                require(record['stage'] == 'business-llm' and operation_id not in worker.streaming,
                        'one business stream reader required')
                worker.streaming.add(operation_id)
            path = worker.operations / operation_id / 'stream.bin'
            headers_path = worker.operations / operation_id / 'stream.headers.json'
            offset = 0; headers_sent = False
            try:
                while not headers_path.exists():
                    record = worker._record(operation_id)
                    with worker.lock: active = operation_id in worker.active
                    if not active or record['status'] in ('completed', 'failed', 'cancelled', 'expired', 'release_unknown'):
                        return self.reply(503, {'error':'upstream stream headers unavailable'})
                    if select.select([self.connection], [], [], 0)[0] and not self.connection.recv(1, socket.MSG_PEEK):
                        with worker.lock:
                            if operation_id in worker.active: worker.active[operation_id][0].set()
                        return
                    time.sleep(.05)
                require(headers_path.is_file() and not headers_path.is_symlink() and headers_path.stat().st_size <= 16384,
                        'bounded regular upstream stream headers required')
                metadata = read_json(headers_path)
                require(set(metadata) == {'bindingSha256','status','headers'} and metadata['bindingSha256'] == record['bindingSha256']
                        and type(metadata['status']) is int and 200 <= metadata['status'] <= 599
                        and isinstance(metadata['headers'], dict) and set(metadata['headers']) == {'content-type'}
                        and isinstance(metadata['headers']['content-type'], str)
                        and not any(char in metadata['headers']['content-type'] for char in '\r\n\x00'), 'invalid upstream stream headers')
                if not 200 <= metadata['status'] < 300:
                    while True:
                        record = worker._record(operation_id)
                        if record['status'] in ('completed', 'failed', 'cancelled', 'expired', 'release_unknown'):
                            operation = verify_operation(read_json(worker.operations / operation_id / 'request.json'))
                            require(record['status'] == 'failed' and worker._release_proven(record, operation),
                                    'upstream error release was not proven')
                            response = worker.result(operation_id)
                            require(response['status'] == metadata['status']
                                    and response['headers'].get('content-type') == metadata['headers']['content-type']
                                    and path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_BODY,
                                    'persisted upstream error identity differs')
                            body = base64.b64decode(response['bodyBase64'], validate=True)
                            require(path.read_bytes() == body, 'persisted upstream error bytes differ')
                            self.send_response(response['status']); self.send_header('Content-Type', metadata['headers']['content-type'])
                            self.send_header('Content-Length', str(len(body))); self.send_header('Cache-Control','no-store')
                            self.send_header('Connection','close'); self.end_headers(); headers_sent = True
                            self.wfile.write(body); self.wfile.flush(); return
                        with worker.lock: active = operation_id in worker.active
                        require(active, 'upstream error owner is no longer active')
                        if select.select([self.connection], [], [], 0)[0] and not self.connection.recv(1, socket.MSG_PEEK):
                            with worker.lock:
                                if operation_id in worker.active: worker.active[operation_id][0].set()
                            return
                        time.sleep(.05)
                sse = metadata['headers']['content-type'].split(';', 1)[0].strip().lower() == 'text/event-stream'
                if sse or 200 <= metadata['status'] < 300:
                    operation = verify_operation(read_json(worker.operations / operation_id / 'request.json'))
                    require(operation['operationId'] == operation_id and operation['bindingSha256'] == record['bindingSha256']
                            and operation['source'].get('httpPath') in ('/v1/chat/completions', '/v1/responses'),
                            'unsupported streaming operation')
                    request_body = json.loads(base64.b64decode(operation['bodyBase64'], validate=True))
                    require(request_body.get('stream') is True and (sse or metadata['status'] >= 300),
                            'successful stream response must use the expected SSE protocol')
                pending = b''; withheld = b''; terminal_seen = False
                self.send_response(metadata['status']); self.send_header('Content-Type', metadata['headers']['content-type'])
                self.send_header('Cache-Control', 'no-store'); self.send_header('Connection', 'close'); self.end_headers()
                headers_sent = True
                while True:
                    if path.exists():
                        require(path.is_file() and not path.is_symlink(), 'regular stream file required')
                        with path.open('r+b') as stream:
                            size = os.fstat(stream.fileno()).st_size
                            require(size <= MAX_BODY, 'business stream exceeds bound')
                            stream.seek(offset); chunk = stream.read(min(65536, size - offset))
                            if chunk:
                                os.fsync(stream.fileno())
                                offset += len(chunk)
                                if not sse:
                                    self.wfile.write(chunk); self.wfile.flush()
                                else:
                                    pending += chunk
                                    while True:
                                        boundary = re.search(b'\r\n\r\n|\n\n|\r\r', pending)
                                        if not boundary: break
                                        frame, pending = pending[:boundary.end()], pending[boundary.end():]
                                        terminal_seen = terminal_seen or success_event(frame)
                                        if terminal_seen: withheld += frame
                                        else: self.wfile.write(frame); self.wfile.flush()
                                continue
                    record = worker._record(operation_id)
                    if record['status'] in ('completed', 'failed', 'cancelled', 'expired', 'release_unknown'):
                        if sse and terminal_seen and not pending and record['status'] == 'completed':
                            response = worker.result(operation_id)
                            require(200 <= response['status'] < 300 and response['status'] == metadata['status']
                                    and response['headers'].get('content-type') == metadata['headers']['content-type']
                                    and path.stat().st_size <= MAX_BODY
                                    and base64.b64decode(response['bodyBase64'], validate=True) == path.read_bytes()
                                    and stream_completed(path.read_bytes(), operation['source']['httpPath']),
                                    'terminal stream differs from persisted upstream response')
                            self.wfile.write(withheld); self.wfile.flush()
                        break
                    with worker.lock: active = operation_id in worker.active
                    if not active: break
                    if select.select([self.connection], [], [], 0)[0] and not self.connection.recv(1, socket.MSG_PEEK):
                        with worker.lock:
                            if operation_id in worker.active: worker.active[operation_id][0].set()
                        break
                    time.sleep(.05)
            except (BrokenPipeError, ConnectionResetError, OSError, ValueError, TypeError, KeyError):
                with worker.lock:
                    if operation_id in worker.active: worker.active[operation_id][0].set()
                if not headers_sent:
                    try: self.reply(503, {'error':'upstream stream unavailable or invalid'})
                    except OSError: pass
            finally:
                with worker.lock: worker.streaming.discard(operation_id)
        do_GET = do_POST = do_PUT = do_DELETE = run_request
    if uds_path is None: return ThreadingHTTPServer(address, Handler)
    path = Path(uds_path)
    require(os.name == 'posix' and path.is_absolute() and path.parent.resolve().is_relative_to(worker.workspace)
            and path.parent.resolve() == path.parent and path.parent.is_dir()
            and not path.exists() and not path.is_symlink(), 'fresh UDS path within workspace required')
    parent = path.parent.stat()
    require(parent.st_uid == os.geteuid() and not (parent.st_mode & 0o077), 'private owned UDS parent required')
    require(type(worker.config.get('peerUid')) is int and type(worker.config.get('peerGid')) is int,
            'explicit UDS peer UID/GID required')
    class UnixServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
        daemon_threads = True
        require_peer_credentials = True
    server = UnixServer(str(path), Handler)
    os.chmod(path, 0o600)
    return server


def validate_network(config):
    require(config.get('networkMode') in ('none', 'host'), 'explicit network mode required')
    require(isinstance(config.get('socketPath'), str) and bool(config['socketPath']), 'private UDS required')
    if config['networkMode'] == 'none':
        require(set(os.listdir('/sys/class/net')) == {'lo'}, 'private network namespace with only loopback required')


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--config', required=True)
    args = parser.parse_args()
    raw_config = Path(args.config).read_bytes()
    require(len(raw_config) <= 1024 * 1024, 'configuration exceeds bound')
    config = json.loads(raw_config)
    require(os.name == 'posix', 'fixed GPU execution requires Linux')
    os.umask(0o077)
    fd = os.open(config['secretFile'], os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and not (info.st_mode & 0o077)
                and 16 <= info.st_size <= 513, 'private owned regular secret file required')
        secret = os.read(fd, 514).decode('utf-8').strip()
        require(re.fullmatch(r'[A-Za-z0-9._~-]{16,512}', secret) is not None, 'valid bounded bearer secret required')
    finally: os.close(fd)
    validate_network(config)
    worker = Worker(config, secret, config_path=args.config, config_sha=hashlib.sha256(raw_config).hexdigest())
    server = make_server(worker, uds_path=config['socketPath'])
    socket_path = Path(config['socketPath'])
    socket_info = socket_path.lstat()
    stopping = False
    def stop(_signum, _frame):
        nonlocal stopping
        stopping = True
    signal.signal(signal.SIGTERM, stop)
    server.timeout = .2
    try:
        while not stopping:
            server.handle_request()
    finally:
        worker.shutdown_when_idle(server.handle_request)
        server.server_close()
        try:
            current = socket_path.lstat()
        except FileNotFoundError:
            pass
        else:
            if stat.S_ISSOCK(current.st_mode) and (current.st_dev, current.st_ino) == (socket_info.st_dev, socket_info.st_ino):
                socket_path.unlink()


if __name__ == '__main__': main()
