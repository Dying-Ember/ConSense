"""One raw native chat/counter operation inside an assigned GPU Job; no protocol guessing."""
import base64
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from urllib.parse import urlsplit

from delivery import require
from request_stage import MAX_BODY, save, verify_operation


def execute(request_path, result_path):
    raw = Path(request_path).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == os.environ.get('CONSENSE_OPERATION_REQUEST_SHA256'), 'assigned chat request changed')
    operation = verify_operation(json.loads(raw)); profile = operation['profile']
    require(operation['stage'] == 'business-llm' and profile.get('adapter') == 'consense.native-chat.v1'
            and all(os.environ.get(key) for key in ('CONSENSE_JOB_UID','CONSENSE_POD_UID','CONSENSE_WORKSPACE_PVC_UID')),
            'native chat requires its assigned GPU Job profile')
    body = base64.b64decode(operation['bodyBase64'], validate=True); value = json.loads(body)
    require(value.get('model') == profile.get('model') and value.get('stream') is not True,
            'exact native chat model required; this finite Job adapter does not provide live body streaming')
    path = profile.get('apiPaths',{}).get(operation['source'].get('httpPath'))
    require(isinstance(path,str) and path.startswith('/') and not any(char in path for char in '\r\n?#'), 'native API path must be explicitly bound; no counter protocol translation')
    base = urlsplit(profile['baseUrl']); require(base.scheme == 'http' and ipaddress.ip_address(base.hostname).is_loopback
        and base.port and not base.path and not base.username and not base.password, 'native Job server must use an explicit loopback API')
    command, environment = profile.get('command'), profile.get('environment')
    require(isinstance(command,list) and command and all(isinstance(arg,str) and arg and '\x00' not in arg for arg in command)
        and isinstance(environment,dict) and environment.get('CUDA_VISIBLE_DEVICES') == '0'
        and environment.get('HF_HUB_OFFLINE') == '1' and environment.get('TRANSFORMERS_OFFLINE') == '1'
        and all(isinstance(key,str) and isinstance(item,str) and '\x00' not in key+item for key,item in environment.items()), 'explicit offline CUDA native command/environment required')
    timeout = profile.get('timeoutSeconds'); require(type(timeout) in (int,float) and 0 < timeout <= 86400, 'finite native chat timeout required')
    deadline = time.monotonic()+timeout; tree = process = None
    sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'vetting_eval'))
    try:
        if os.name == 'nt':
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
                if _wire_ready({'baseUrl':profile['baseUrl'],'ownedBoot':{'readyPath':profile['readyPath']}},min(.25,max(.001,deadline-time.monotonic()))): break
            except (OSError,http.client.HTTPException): pass
            time.sleep(.05)
        else: raise TimeoutError('native chat wire readiness deadline elapsed')
        connection = http.client.HTTPConnection(base.hostname,base.port,timeout=max(.001,deadline-time.monotonic()))
        chunks = []; size = 0
        try:
            connection.request('POST',path,body,{'Content-Type':'application/json'})
            response = connection.getresponse(); status = response.status
            while True:
                remaining = deadline-time.monotonic(); require(remaining > 0,'native chat total deadline elapsed')
                if connection.sock is not None: connection.sock.settimeout(remaining)
                chunk = response.read1(65536)
                if not chunk: break
                chunks.append(chunk); size += len(chunk); require(size <= MAX_BODY,'native chat response exceeds byte bound')
            require(not response.length,'native chat response ended before Content-Length')
            content_type = response.getheader('content-type','application/octet-stream')
        finally: connection.close()
        require(hashlib.sha256(Path(request_path).read_bytes()).hexdigest() == os.environ['CONSENSE_OPERATION_REQUEST_SHA256'], 'chat request changed before response persistence')
        save(Path(result_path),{'contract':'consense.gpu-operation-response.v1','bindingSha256':operation['bindingSha256'],
            'status':status,'headers':{'content-type':content_type,'x-consense-body-stream':'unavailable-finite-job'},
            'bodyBase64':base64.b64encode(b''.join(chunks)).decode(),'runtimeIdentityScope':'configured_native_job_profile_not_attested_loaded_model_identity'})
    finally:
        if tree:
            try: tree.stop(); require(tree.active() == 0,'native Windows model descendants remain')
            finally: tree.close()
        if process:
            try: os.killpg(process.pid,signal.SIGKILL)
            except ProcessLookupError: pass
            process.wait(timeout=2)


if __name__ == '__main__': execute(os.environ['CONSENSE_OPERATION_REQUEST'],os.environ['CONSENSE_OPERATION_RESULT'])
