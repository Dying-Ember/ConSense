"""One raw native chat/counter operation in its assigned GPU process."""
# consense-cpu-stub-process-only-v1
import base64
from contextlib import nullcontext
import hashlib
import hmac
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time
from urllib.parse import urlsplit

from delivery import require
from request_stage import MAX_BODY, save, verify_operation


def stub_token(path, expected_sha256):
    require(os.name == 'posix' and isinstance(path, str) and Path(path).is_absolute()
            and isinstance(expected_sha256, str) and re.fullmatch('[a-f0-9]{64}', expected_sha256),
            'fixed stub requires an absolute private token file')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and info.st_nlink == 1
                and not info.st_mode & 0o077 and 16 <= info.st_size <= 512, 'invalid private stub token file')
        raw = os.read(fd, 513)
        after = os.fstat(fd)
        require(len(raw) == info.st_size and (after.st_dev, after.st_ino, after.st_size,
                after.st_mtime_ns, after.st_ctime_ns) == (info.st_dev, info.st_ino, info.st_size,
                info.st_mtime_ns, info.st_ctime_ns), 'stub token changed or read incomplete')
        token = raw.decode('ascii')
        require(re.fullmatch(r'[A-Za-z0-9._~-]{16,512}', token) is not None, 'invalid stub token')
        require(hmac.compare_digest(hashlib.sha256(raw).hexdigest(), expected_sha256), 'stub token identity changed')
        return token
    finally:
        os.close(fd)


def verify_fixed_connection_until(connection, port, deadline):
    while True:
        try:
            verify_fixed_connection(connection, port)
            return
        except ValueError as error:
            if str(error) != 'fixed provider listener or connection is not owned by the task':
                raise
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('fixed provider ownership deadline elapsed') from error
            time.sleep(min(.01, remaining))


def fixed_ready(base, path, timeout, token=None):
    deadline = time.monotonic() + timeout
    connection = http.client.HTTPConnection(base.hostname, base.port, timeout=timeout)
    try:
        connection.connect()
        verify_fixed_connection_until(connection, base.port, deadline)
        remaining = deadline - time.monotonic()
        require(remaining > 0, 'fixed provider readiness deadline elapsed')
        connection.sock.settimeout(remaining)
        connection.request('GET', path, headers={'Authorization': 'Bearer ' + token} if token else {})
        response = connection.getresponse()
        return 200 <= response.status < 300
    finally:
        connection.close()


def completed_stream(raw, path):
    if path not in ('/v1/chat/completions', '/v1/responses'): return False
    completed = False
    for frame in raw.replace(b'\r\n', b'\n').split(b'\n\n'):
        lines = frame.splitlines()
        if any(line.strip() in (b'event: error', b'event: response.failed', b'event: response.incomplete') for line in lines):
            return False
        if path == '/v1/responses' and b'event: response.completed' in lines: completed = True
        for line in lines:
            if line.startswith(b'data:'):
                data = line[5:].strip()
                if path == '/v1/chat/completions' and data == b'[DONE]': completed = True
                try:
                    value = json.loads(data)
                    if not isinstance(value, dict): continue
                    if value.get('type') in ('error', 'response.failed', 'response.incomplete') or value.get('error'):
                        return False
                    if path == '/v1/responses' and value.get('type') == 'response.completed': completed = True
                except (ValueError, AttributeError): pass
    return completed


def verify_fixed_connection(connection, port):
    require(os.name == 'posix' and connection.sock is not None, 'fixed provider ownership requires Linux')
    rows = Path('/proc/self/cgroup').read_text().splitlines()
    members = [row[3:] for row in rows if row.startswith('0::/')]
    require(len(members) == 1, 'fixed provider cgroup identity unavailable')
    group = (Path('/sys/fs/cgroup') / members[0].lstrip('/')).resolve()
    root = Path(os.environ['CONSENSE_FIXED_CGROUP_ROOT']).resolve()
    require(group.parent.samefile(root) and str(os.getpid()) in (group/'cgroup.procs').read_text().split(),
            'fixed chat process is outside its assigned cgroup')
    pids = set()
    for procs in [group/'cgroup.procs', *group.rglob('cgroup.procs')]:
        pids.update(int(value) for value in procs.read_text().split())
    owned = set()
    for pid in pids:
        try:
            for descriptor in Path('/proc', str(pid), 'fd').iterdir():
                try: target = os.readlink(descriptor)
                except FileNotFoundError: continue
                match = re.fullmatch(r'socket:\[(\d+)\]', target)
                if match: owned.add(match[1])
        except FileNotFoundError: continue
    local_host, local_port = connection.sock.getsockname()
    require(local_host == '127.0.0.1' and connection.sock.getpeername() == ('127.0.0.1', port),
            'fixed provider connection must use IPv4 loopback')
    listeners, peers = set(), set()
    for protocol in ('tcp', 'tcp6'):
        for line in Path('/proc/net', protocol).read_text().splitlines()[1:]:
            row = line.split()
            if len(row) < 10: continue
            local, remote = row[1], row[2]
            if row[3] == '0A' and local.endswith(':%04X' % port):
                require(protocol == 'tcp' and local == '0100007F:%04X' % port,
                        'fixed provider listener must use IPv4 loopback only')
                listeners.add(row[9])
            if protocol == 'tcp' and local == '0100007F:%04X' % port:
                if row[3] == '01' and remote == '0100007F:%04X' % local_port: peers.add(row[9])
    require(len(listeners) == 1 and len(peers) == 1 and listeners <= owned and peers <= owned,
            'fixed provider listener or connection is not owned by the task')


def execute(request_path, result_path):
    raw = Path(request_path).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == os.environ.get('CONSENSE_OPERATION_REQUEST_SHA256'), 'assigned chat request changed')
    operation = verify_operation(json.loads(raw)); profile = operation['profile']
    require(operation['stage'] == 'business-llm' and profile.get('adapter') == 'consense.native-chat.v1',
            'native chat requires its assigned profile')
    fixed = bool(os.environ.get('CONSENSE_FIXED_WORKSPACE'))
    if fixed:
        workspace = Path(os.environ['CONSENSE_FIXED_WORKSPACE'])
        require(workspace.is_absolute() and workspace.is_dir() and os.environ.get('CONSENSE_FIXED_CONTAINER_ID')
                and os.environ.get('CONSENSE_FIXED_OPERATION_ID') == operation['operationId'],
                'fixed chat requires its configured container and assigned operation identities')
        directory = workspace.resolve() / 'gpu-operations' / operation['operationId']
        require(Path(request_path).resolve() == directory / 'request.json'
                and Path(result_path).resolve() == directory / 'response.json'
                and os.environ.get('CONSENSE_OPERATION_REQUEST') == str(request_path)
                and os.environ.get('CONSENSE_OPERATION_RESULT') == str(result_path)
                and Path(os.environ.get('CONSENSE_OPERATION_STREAM', '')).resolve() == directory / 'stream.bin',
                'fixed chat paths must belong to the assigned operation')
    else:
        require(all(os.environ.get(key) for key in ('CONSENSE_JOB_UID','CONSENSE_POD_UID','CONSENSE_WORKSPACE_PVC_UID')),
                'native chat requires its assigned GPU Job profile')
    body = base64.b64decode(operation['bodyBase64'], validate=True); value = json.loads(body)
    streaming = value.get('stream') is True
    require(value.get('model') == profile.get('model') and (fixed or not streaming),
            'exact native chat model required; this finite Job adapter does not provide live body streaming')
    path = profile.get('apiPaths',{}).get(operation['source'].get('httpPath'))
    require(isinstance(path,str) and path.startswith('/') and not any(char in path for char in '\r\n?#'), 'native API path must be explicitly bound; no counter protocol translation')
    base = urlsplit(profile['baseUrl']); require(base.scheme == 'http' and ipaddress.ip_address(base.hostname).is_loopback
        and base.port and not base.path and not base.username and not base.password, 'native Job server must use an explicit loopback API')
    command, environment = profile.get('command'), profile.get('environment')
    stub = profile.get('providerKind') == 'cpu-protocol-stub'
    require(profile.get('providerKind') in (None, 'cpu-protocol-stub'), 'unknown chat provider kind')
    token = None
    if stub:
        require(fixed and base.hostname == '127.0.0.1' and profile.get('readyPath') == '/v1/models',
                'stub requires fixed task and literal loopback readiness')
        token = stub_token(profile.get('stubTokenFile'), profile.get('stubTokenSha256'))
    require(isinstance(command,list) and command and all(isinstance(arg,str) and arg and '\x00' not in arg for arg in command)
        and isinstance(environment,dict) and environment.get('CUDA_VISIBLE_DEVICES') == ('' if stub else '0')
        and environment.get('HF_HUB_OFFLINE') == '1' and environment.get('TRANSFORMERS_OFFLINE') == '1'
        and all(isinstance(key,str) and isinstance(item,str) and '\x00' not in key+item
            and (not stub or (key != 'CONSENSE_QWEN_STUB_TOKEN'
                              and not key.upper().startswith(('LD_', 'PYTHON'))))
            and (not fixed or (key != 'CONSENSE_WORKSPACE_PVC_UID' and not key.startswith(
                ('CONSENSE_FIXED_', 'CONSENSE_OPERATION_', 'CONSENSE_JOB_', 'CONSENSE_POD_', 'CONSENSE_MANIFEST_'))))
            for key,item in environment.items()), 'explicit offline CUDA native command/environment required')
    timeout = profile.get('timeoutSeconds'); require(type(timeout) in (int,float) and 0 < timeout <= 86400, 'finite native chat timeout required')
    deadline = time.monotonic()+timeout; tree = process = None
    sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'vetting_eval'))
    gpu = nullcontext()
    gpu.__enter__()
    try:
        if fixed:
            child_env = ({**environment, 'CONSENSE_QWEN_STUB_TOKEN': token,
                          'PYTHONNOUSERSITE':'1', 'PYTHONSAFEPATH':'1'} if stub else {**os.environ, **environment})
            process = subprocess.Popen(command, cwd=profile.get('cwd',str(Path(request_path).parent)), env=child_env)
        elif os.name == 'nt':
            from native_provider_lifecycle import _WindowsTree
            tree = _WindowsTree({'command':command,'cwd':profile.get('cwd',str(Path(request_path).parent)),
                'env':{**os.environ,**environment}},sys.stdout.buffer,sys.stderr.buffer)
        else:
            process = subprocess.Popen(command,cwd=profile.get('cwd',str(Path(request_path).parent)),
                env={**os.environ,**environment},start_new_session=True)
        from native_provider_lifecycle import _wire_ready
        while time.monotonic() < deadline:
            require(tree.active() > 0 if tree else process.poll() is None, 'native model API exited before wire readiness')
            try:
                timeout = min(.25,max(.001,deadline-time.monotonic()))
                if (fixed_ready(base, profile['readyPath'], timeout, token) if fixed else
                    _wire_ready({'baseUrl':profile['baseUrl'],'ownedBoot':{'readyPath':profile['readyPath']}},timeout)): break
            except (OSError,http.client.HTTPException): pass
            time.sleep(.05)
        else: raise TimeoutError('native chat wire readiness deadline elapsed')
        connection = http.client.HTTPConnection(base.hostname,base.port,timeout=max(.001,deadline-time.monotonic()))
        chunks = []; size = 0; stream = None
        try:
            if fixed:
                connection.connect()
                verify_fixed_connection_until(connection, base.port, deadline)
                remaining = deadline - time.monotonic()
                require(remaining > 0, 'native chat total deadline elapsed')
                connection.sock.settimeout(remaining)
            connection.request('POST',path,body,{'Content-Type':'application/json',
                **({'Authorization': 'Bearer ' + token} if stub else {})})
            response = connection.getresponse(); status = response.status
            content_type = response.getheader('content-type','application/octet-stream')
            require(not any(char in content_type for char in '\r\n\x00'), 'invalid native response content type')
            if streaming:
                headers = directory/'stream.headers.json'; temporary = directory/'stream.headers.partial'
                require(not headers.exists(), 'fresh stream headers required')
                save(temporary, {'bindingSha256':operation['bindingSha256'], 'status':status,
                    'headers':{'content-type':content_type}})
                os.replace(temporary, headers)
                stream = (directory/'stream.bin').open('xb')
            while True:
                remaining = deadline-time.monotonic(); require(remaining > 0,'native chat total deadline elapsed')
                if connection.sock is not None: connection.sock.settimeout(remaining)
                chunk = response.read1(65536)
                if not chunk: break
                size += len(chunk); require(size <= MAX_BODY,'native chat response exceeds byte bound')
                if stream:
                    stream.write(chunk); stream.flush(); os.fsync(stream.fileno())
                chunks.append(chunk)
            require(not response.length,'native chat response ended before Content-Length')
            if streaming and 200 <= status < 300:
                require(content_type.lower().split(';',1)[0].strip() == 'text/event-stream'
                        and completed_stream(b''.join(chunks), operation['source'].get('httpPath')),
                        'native event stream ended without its terminal marker')
        finally:
            if stream: stream.close()
            connection.close()
        require(hashlib.sha256(Path(request_path).read_bytes()).hexdigest() == os.environ['CONSENSE_OPERATION_REQUEST_SHA256'], 'chat request changed before response persistence')
        save(Path(result_path),{'contract':'consense.gpu-operation-response.v1','bindingSha256':operation['bindingSha256'],
            'status':status,'headers':{'content-type':content_type, **({} if fixed else {'x-consense-body-stream':'unavailable-finite-job'})},
            'bodyBase64':base64.b64encode(b''.join(chunks)).decode(),'runtimeIdentityScope':
                'configured_fixed_profile_not_attested_loaded_model_identity' if fixed else 'configured_native_job_profile_not_attested_loaded_model_identity'})
    finally:
        try:
            if tree:
                try: tree.stop(); require(tree.active() == 0,'native Windows model descendants remain')
                finally: tree.close()
            if process:
                if fixed:
                    if process.poll() is None: process.terminate()
                else:
                    try: os.killpg(process.pid,signal.SIGKILL)
                    except ProcessLookupError: pass
                process.wait(timeout=2)
        finally:
            gpu.__exit__(None, None, None)


if __name__ == '__main__': execute(os.environ['CONSENSE_OPERATION_REQUEST'],os.environ['CONSENSE_OPERATION_RESULT'])
