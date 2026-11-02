import uuid
import json
import base64
import copy
from collections import deque
from contextlib import ExitStack
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer, ThreadingHTTPServer
import os
import select
import socket
import struct
import subprocess
import sys
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
from pathlib import Path
from tempfile import TemporaryDirectory

from fixed_gpu_worker import Worker, make_server, validate_network
from gpu_observation import GpuObservationIncomplete
from request_stage import encoded, envelope


class FixedGpuApiTest(unittest.TestCase):
    @unittest.skipUnless(os.name == 'posix', 'Linux session supervision')
    def test_incomplete_gpu_observation_keeps_task_running_but_retains_owner(self):
        with TemporaryDirectory() as root:
            tool = Path(root) / 'nvidia-smi'; tool.write_bytes(b'fixture')
            worker = Worker({'workspace':root, 'stages':{}, 'containerId':'fixture',
                             'nvidiaSmi':str(tool), 'gpuUuid':'GPU-fixture'}, 'example-secret')
            op = envelope('document-embedding', b'{}', {'source':'fixture'}, {'model':'fixture'})
            directory = worker.operations / op['operationId']; directory.mkdir()
            (directory / 'request.json').write_bytes(encoded(op))
            (directory / 'status.json').write_bytes(encoded({'operationId':op['operationId'],
                'stage':op['stage'], 'status':'starting', 'bindingSha256':op['bindingSha256']}))
            (directory / 'model-session').mkdir()
            (directory / 'model-session' / 'finished.json').write_bytes(b'{}')
            worker.marker.write_bytes(encoded({'operationId':op['operationId'], 'state':'occupied_or_release_unknown'}))
            cancellation, finished = threading.Event(), threading.Event()
            worker.active[op['operationId']] = (cancellation, finished)
            worker.sessions[op['operationId']] = {'deadline':time.monotonic()+5, 'token':'fixture'}
            class Tree:
                pid = 123; group = Path(root)
                def __init__(self):
                    self.stopped = False; self.process = SimpleNamespace(poll=lambda: 0 if self.stopped else None)
                def active(self): return 0 if self.stopped else 1
                def stop(self): self.stopped = True
                def close(self): pass
            tree = Tree()
            boot = {'cgroupRoot':root, 'env':{}, 'runtimeAssets':[{'path':str(tool),
                'byteCount':7, 'sha256':hashlib.sha256(b'fixture').hexdigest()}]}
            calls = []
            def sample(*_):
                calls.append(1)
                if len(calls) == 1:
                    finished.set()
                    raise GpuObservationIncomplete('task PID exited during sample')
                return {'processes':[{'pid':123}]}
            with patch.object(worker, '_validate_profile', return_value=(boot, 5)), \
                 patch.object(worker, '_delegated_root', return_value=Path(root)), \
                 patch.object(worker, '_cgroup_pids', return_value={os.getpid()}), \
                 patch('fixed_gpu_worker._LinuxTree', return_value=tree), \
                 patch('fixed_gpu_worker.observe_running', side_effect=sample), \
                 patch('fixed_gpu_worker.observe_release') as release:
                worker._execute(directory, op, {'mode':'model-session', 'profile':{}}, cancellation, finished)
            result = worker._record(op['operationId'])
            self.assertGreaterEqual(len(calls), 2)
            self.assertEqual(result['status'], 'release_unknown')
            self.assertEqual(result['release']['status'], 'release_unknown')
            self.assertIn('GPU observation incomplete', result['failure'])
            self.assertTrue(worker.marker.exists())
            release.assert_not_called()

    @unittest.skipUnless(os.name == 'posix', 'Linux session supervision')
    def test_model_session_rechecks_deadline_and_cancel_after_gpu_sample(self):
        for cause, witnessed in (('expired', False), ('expired', True), ('cancelled', True)):
            with self.subTest(cause=cause, witnessed=witnessed), TemporaryDirectory() as root:
                tool = Path(root) / 'nvidia-smi'; tool.write_bytes(b'fixture')
                worker = Worker({'workspace':root, 'stages':{}, 'containerId':'fixture',
                                 'nvidiaSmi':str(tool), 'gpuUuid':'GPU-fixture'}, 'example-secret')
                op = envelope('document-embedding', b'{}', {'source':'fixture'}, {'model':'fixture'})
                directory = worker.operations / op['operationId']; directory.mkdir()
                (directory / 'request.json').write_bytes(encoded(op))
                (directory / 'status.json').write_bytes(encoded({'operationId':op['operationId'],
                    'stage':op['stage'], 'status':'starting', 'bindingSha256':op['bindingSha256']}))
                worker.marker.write_bytes(encoded({'operationId':op['operationId'], 'state':'occupied_or_release_unknown'}))
                cancellation, finished = threading.Event(), threading.Event()
                worker.active[op['operationId']] = (cancellation, finished)
                worker.sessions[op['operationId']] = {'deadline':time.monotonic()+.1, 'token':'fixture'}
                class Tree:
                    pid = 123; group = Path(root)
                    def __init__(self):
                        self.stopped = False; self.process = SimpleNamespace(poll=lambda: 0 if self.stopped else None)
                    def active(self): return 0 if self.stopped else 1
                    def stop(self): self.stopped = True
                    def close(self): pass
                tree = Tree()
                boot = {'cgroupRoot':root, 'env':{}, 'runtimeAssets':[{'path':str(tool),
                    'byteCount':7, 'sha256':hashlib.sha256(b'fixture').hexdigest()}]}
                def sample(*_):
                    if cause == 'cancelled': cancellation.set()
                    else: time.sleep(.15)
                    return {'processes':[{'pid':123}]} if witnessed else None
                with patch.object(worker, '_validate_profile', return_value=(boot, 2)), \
                     patch.object(worker, '_delegated_root', return_value=Path(root)), \
                     patch.object(worker, '_cgroup_pids', return_value={os.getpid()}), \
                     patch.object(worker, '_model_ready') as ready, \
                     patch('fixed_gpu_worker._LinuxTree', return_value=tree), \
                     patch('fixed_gpu_worker.observe_running', side_effect=sample), \
                     patch('fixed_gpu_worker.observe_release', return_value={'status':'local_observation_complete'}):
                    worker._execute(directory, op, {'mode':'model-session', 'profile':{}}, cancellation, finished)
                ready.assert_not_called()
                result = worker._record(op['operationId'])
                self.assertEqual(result['status'], cause if witnessed else 'release_unknown')
                self.assertEqual(result['release']['status'], 'released' if witnessed else 'release_unknown')
                self.assertEqual(worker.marker.exists(), not witnessed)

    @unittest.skipUnless(os.name == 'posix', 'Unix socket required')
    def test_model_ready_diagnostic_identifies_failed_condition_without_values(self):
        with TemporaryDirectory() as root:
            worker = Worker({'workspace':root, 'stages':{}}, 'example-secret')
            op = envelope('document-embedding', b'{}', {'source':'fixture'}, {'model':'fixture'})
            directory = Path(root) / 'session'; directory.mkdir()
            raw = encoded(op); (directory / 'request.json').write_bytes(raw)
            sock = socket.socket(socket.AF_UNIX)
            try:
                sock.bind(str(directory / 'model-session.sock'))
                ready = {'operationId':'private-value', 'requestSha256':hashlib.sha256(raw).hexdigest(),
                         'bindingSha256':op['bindingSha256'], 'profileSha256':op['profileSha256'],
                         'pid':os.getpid(), 'processStartTicks':1,
                         'socketInode':(directory / 'model-session.sock').stat().st_ino}
                (directory / 'model-session.ready.json').write_bytes(encoded(ready))
                with patch.object(worker, '_tree_pids', return_value={os.getpid()}):
                    with self.assertRaisesRegex(ValueError, 'operationId=False') as failure:
                        worker._model_ready(directory, op, object())
                self.assertNotIn('private-value', str(failure.exception))
            finally:
                sock.close()

    @unittest.skipUnless(os.name == 'posix', 'Linux session supervision')
    def test_busy_session_remains_ready_but_listener_loss_still_fails(self):
        for lose_listener in (False, True):
            with self.subTest(lose_listener=lose_listener), TemporaryDirectory() as root:
                busy, release, owned = threading.Event(), threading.Event(), threading.Event()
                owned.set()
                class Handler(BaseHTTPRequestHandler):
                    def log_message(self, *_): pass
                    def do_GET(self):
                        self.send_response(200); self.end_headers()
                    def do_POST(self):
                        busy.set(); release.wait(3)
                        self.send_response(200); self.end_headers()
                server = HTTPServer(('127.0.0.1', 0), Handler)
                server_thread = threading.Thread(target=server.serve_forever, daemon=True); server_thread.start()
                tool = Path(root)/'nvidia-smi'; tool.write_bytes(b'fixture')
                worker = Worker({'workspace':root, 'stages':{}, 'containerId':'fixture',
                                 'nvidiaSmi':str(tool), 'gpuUuid':'GPU-fixture'}, 'example-secret')
                op = envelope('paddle', b'{}', {'source':'fixture'}, {'model':'fixture'})
                directory = worker.operations/op['operationId']; directory.mkdir()
                (directory/'request.json').write_bytes(encoded(op))
                (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],
                    'stage':'paddle', 'status':'starting', 'bindingSha256':op['bindingSha256']}))
                (directory/'transaction.json').write_bytes(b'{}')
                worker.marker.write_bytes(encoded({'operationId':op['operationId'], 'state':'occupied_or_release_unknown'}))
                cancellation, finished = threading.Event(), threading.Event()
                worker.active[op['operationId']] = (cancellation, finished)
                class Tree:
                    pid=123; group=Path(root)
                    def __init__(self): self.stopped=False; self.process=SimpleNamespace(poll=lambda: 0 if self.stopped else None)
                    def active(self): return 0 if self.stopped else 1
                    def stop(self): self.stopped=True
                    def close(self): pass
                tree=Tree(); boot={'cgroupRoot':root, 'env':{}, 'runtimeAssets':[{'path':str(tool),
                    'byteCount':7, 'sha256':hashlib.sha256(b'fixture').hexdigest()}]}
                profile={'mode':'session', 'profile':{}, 'readyUrl':'http://127.0.0.1:%d/health' % server.server_port}
                client = None; runner = None
                try:
                    with patch.object(worker, '_validate_profile', return_value=(boot,5)), \
                         patch.object(worker, '_delegated_root', return_value=Path(root)), \
                         patch.object(worker, '_cgroup_pids', return_value={os.getpid()}), \
                         patch.object(worker, '_listener_owned', side_effect=lambda *_: owned.is_set()), \
                         patch('fixed_gpu_worker._LinuxTree', return_value=tree), \
                         patch('fixed_gpu_worker.observe_running', return_value={'processes':[{'pid':123}]}), \
                         patch('fixed_gpu_worker.observe_release', return_value={'status':'local_observation_complete'}):
                        runner=threading.Thread(target=worker._execute,args=(directory,op,profile,cancellation,finished)); runner.start()
                        deadline=time.monotonic()+3
                        while worker._record(op['operationId'])['status'] != 'ready' and time.monotonic() < deadline: time.sleep(.01)
                        self.assertEqual('ready', worker._record(op['operationId'])['status'], worker._record(op['operationId']))
                        def request():
                            conn=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
                            try: conn.request('POST','/parse'); conn.getresponse().read()
                            finally: conn.close()
                        client=threading.Thread(target=request,daemon=True); client.start()
                        self.assertTrue(busy.wait(2)); time.sleep(.5)
                        self.assertEqual('ready', worker._record(op['operationId'])['status'])
                        if lose_listener: owned.clear()
                        else: finished.set()
                        runner.join(3); self.assertFalse(runner.is_alive())
                        record=worker._record(op['operationId'])
                        self.assertEqual('failed' if lose_listener else 'completed', record['status'])
                        if lose_listener: self.assertIn('listener', record['failure'])
                finally:
                    release.set()
                    if runner and runner.is_alive(): cancellation.set(); runner.join(3)
                    if client: client.join(3)
                    server.shutdown(); server.server_close(); server_thread.join(3)

    def test_stub_credentials_and_command_rejected_before_operation_files(self):
        with TemporaryDirectory() as root:
            workspace = Path(root)
            executable, runner, stub = (workspace / name for name in ('python3', 'runner.py', 'stub.py'))
            for path in (executable, runner, stub): path.write_bytes(path.name.encode())
            runner.write_bytes(b'# consense-cpu-stub-process-only-v1\nfixture\n')
            self.enterContext(patch('fixed_gpu_worker.CPU_STUB_CHAT', str(runner)))
            self.enterContext(patch('fixed_gpu_worker.CPU_STUB_SERVER', str(stub)))
            assets = [{'path':str(path), 'byteCount':path.stat().st_size,
                       'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
                      for path in (executable, runner, stub)]
            token = 'private-token-value-1234'
            token_sha = hashlib.sha256(token.encode()).hexdigest()
            inner = {'adapter':'consense.native-chat.v1', 'providerKind':'cpu-protocol-stub', 'stubTokenFile':str(workspace / 'secret'),
                     'stubTokenSha256':token_sha,
                     'command':[str(executable), str(stub)], 'environment':{}}
            profile = {'mode':'job', 'profile':inner, 'timeoutSeconds':1, 'gpuRequired':True,
                       'boot':{'command':[str(executable), str(runner)], 'cwd':root,
                               'cgroupRoot':root, 'env':{}, 'runtimeAssets':assets}}
            config = {'workspace':root, 'stages':{'business-llm':profile, 'paddle':profile},
                      'cgroupRoot':root, 'nvidiaSmi':str(executable), 'gpuUuid':'GPU-fixture'}
            worker = Worker(config, secret='example-secret')
            request = {'stage':'business-llm', 'binding':{'httpPath':'/v1/chat/completions'}, 'bodyBase64':'e30=',
                       'expectedProfileSha256':hashlib.sha256(encoded(profile['profile'])).hexdigest(), 'expectedMode':'job'}
            with patch('chat_operation_worker.stub_token', return_value=token):
                worker._validate_profile(profile)
                cases = []
                for field in ('profile', 'argument', 'env_value', 'env_key', 'boot_argument', 'boot_env', 'boot_key', 'script', 'executable', 'pin_missing', 'pin_invalid'):
                    bad = copy.deepcopy(profile)
                    if field == 'profile': bad['profile']['model'] = token
                    elif field == 'argument': bad['profile']['command'].append('--key=' + token)
                    elif field == 'env_value': bad['profile']['environment']['OTHER'] = token
                    elif field == 'env_key': bad['profile']['environment']['CONSENSE_QWEN_STUB_TOKEN'] = 'placeholder'
                    elif field == 'boot_argument': bad['boot']['command'].append(token)
                    elif field == 'boot_env': bad['boot']['env']['OTHER'] = token
                    elif field == 'boot_key': bad['boot']['env']['CONSENSE_QWEN_STUB_TOKEN'] = 'placeholder'
                    elif field == 'script': bad['profile']['command'][1] = str(workspace / 'unregistered.py')
                    elif field == 'executable': bad['profile']['command'][0] = str(workspace / 'unregistered-python3')
                    elif field == 'pin_missing': del bad['profile']['stubTokenSha256']
                    else: bad['profile']['stubTokenSha256'] = 'invalid'
                    cases.append((field, bad))
                for field, bad in cases:
                    with self.subTest(field=field):
                        worker.config['stages']['business-llm'] = bad
                        request['expectedProfileSha256'] = hashlib.sha256(encoded(bad['profile'])).hexdigest()
                        with self.assertRaises(ValueError): worker.start(request)
                        self.assertFalse(worker.marker.exists())
                        self.assertEqual([], list(worker.operations.iterdir()))
                request['stage'] = 'paddle'
                request['expectedProfileSha256'] = hashlib.sha256(encoded(profile['profile'])).hexdigest()
                with self.assertRaisesRegex(ValueError, 'native chat stage or mode differs'):
                    worker.start(request)
                self.assertFalse(worker.marker.exists())
                self.assertEqual([], list(worker.operations.iterdir()))
            rotated = 'new-private-token-value-5678'
            current = [token]
            def read_token(path, expected):
                if hashlib.sha256(current[0].encode()).hexdigest() != expected: raise ValueError('token digest mismatch')
                return current[0]
            worker.config['stages']['business-llm'] = profile
            request['stage'] = 'business-llm'
            current[0] = rotated
            with patch('chat_operation_worker.stub_token', side_effect=read_token):
                with self.assertRaisesRegex(ValueError, 'digest mismatch'): worker.start(request)
            self.assertFalse(worker.marker.exists())
            self.assertEqual([], list(worker.operations.iterdir()))
            current[0] = token
            def rotate_after_check(path, expected):
                value = read_token(path, expected)
                profile['profile']['stubTokenSha256'] = hashlib.sha256(rotated.encode()).hexdigest()
                current[0] = rotated
                return value
            with ExitStack() as stack:
                stack.enter_context(patch('chat_operation_worker.stub_token', side_effect=rotate_after_check))
                thread = stack.enter_context(patch('fixed_gpu_worker.threading.Thread'))
                if os.name == 'nt':
                    real_open = os.open
                    def fixture_open(path, flags, *args, **kwargs):
                        if Path(path) == workspace and flags == os.O_RDONLY:
                            return real_open(runner, flags)
                        return real_open(path, flags, *args, **kwargs)
                    stack.enter_context(patch('fixed_gpu_worker.os.open', side_effect=fixture_open))
                    stack.enter_context(patch('fixed_gpu_worker.os.fsync'))
                started = worker.start(request)
            frozen = thread.call_args.kwargs['args'][2]
            self.assertEqual(token_sha, frozen['profile']['stubTokenSha256'])
            saved = (worker.operations / started['operationId'] / 'request.json').read_bytes()
            self.assertEqual(token_sha, json.loads(saved)['profile']['stubTokenSha256'])
            self.assertNotIn(token.encode(), saved)
            self.assertNotIn(rotated.encode(), saved)
            with patch('chat_operation_worker.stub_token', side_effect=read_token):
                with self.assertRaisesRegex(ValueError, 'digest mismatch'): worker._validate_profile(frozen)

    def test_network_modes_require_private_uds_and_preserve_none_isolation(self):
        with patch('fixed_gpu_worker.os.listdir', return_value=['lo']) as interfaces:
            validate_network({'networkMode':'none', 'socketPath':'/private/gpu.sock'})
            interfaces.assert_called_once_with('/sys/class/net')
        with patch('fixed_gpu_worker.os.listdir') as interfaces:
            validate_network({'networkMode':'host', 'socketPath':'/private/gpu.sock'})
            interfaces.assert_not_called()
        with patch('fixed_gpu_worker.os.listdir', return_value=['lo', 'eth0']):
            with self.assertRaisesRegex(ValueError, 'only loopback'):
                validate_network({'networkMode':'none', 'socketPath':'/private/gpu.sock'})
        for config in ({'networkMode':'host', 'socketPath':None},
                       {'networkMode':'host', 'socketPath':''},
                       {'networkMode':'bridge', 'socketPath':'/private/gpu.sock'},
                       {'socketPath':'/private/gpu.sock'}):
            with self.subTest(config=config), self.assertRaises(ValueError): validate_network(config)

    def test_provider_listener_is_owned_ipv4_loopback_only(self):
        worker = Worker.__new__(Worker)
        port = 28771
        def rows(address):
            return 'header\n0: ' + address + ' 00000000:0000 0A 0 0 0 0 0 42\n'
        def check(tcp, tcp6='header\n', owned=('42',)):
            def read(path, *args, **kwargs):
                return tcp if path.name == 'tcp' else tcp6
            with patch.object(Path, 'read_text', read), patch.object(worker, '_socket_inodes', return_value=set(owned)):
                return worker._listener_owned(object(), port)
        loopback = '0100007F:%04X' % port
        self.assertTrue(check(rows(loopback)))
        self.assertFalse(check(rows('00000000:%04X' % port)))
        self.assertFalse(check(rows('0200007F:%04X' % port)))
        self.assertFalse(check(rows(loopback), rows('00000000000000000000000000000000:%04X' % port)))
        self.assertFalse(check(rows(loopback), owned=('43',)))

    @unittest.skipUnless(os.name == 'posix', 'Unix peer credentials required')
    def test_model_exchange_receives_fragmented_frame_and_rejects_incomplete_or_cancelled(self):
        with TemporaryDirectory() as root:
            worker=Worker({'workspace':root,'stages':{}},'example-secret')
            operation={'operationId':'a'*32}; cancel=threading.Event()
            result={'value':'x'*65536}; raw=encoded({'ok':True,'result':result})
            header=struct.pack('!I',len(raw))
            class Connection:
                def __init__(self, parts, cancel_on_timeout=False):
                    self.parts=deque(parts); self.cancel_on_timeout=cancel_on_timeout; self.closed=False
                def settimeout(self, _): pass
                def connect(self, _): pass
                def getsockopt(self, *_): return struct.pack('3i',os.getpid(),os.geteuid(),os.getegid())
                def sendall(self, _): pass
                def recv(self, size):
                    if not self.parts: return b''
                    part=self.parts.popleft()
                    if part is None:
                        if self.cancel_on_timeout: cancel.set()
                        raise socket.timeout()
                    if len(part) > size: raise AssertionError('fragment exceeds requested bytes')
                    return part
                def close(self): self.closed=True
            def exchange(conn, deadline=None):
                session={'deadline':time.monotonic()+3 if deadline is None else deadline,'token':'fixture'}
                with patch('fixed_gpu_worker.socket.socket',return_value=conn), \
                     patch('fixed_gpu_worker.read_json',return_value={'pid':os.getpid()}), \
                     patch.object(worker,'_model_ready',return_value=True), \
                     patch.object(worker,'_tree_pids',return_value={os.getpid()}):
                    return worker._model_exchange(Path(root),operation,object(),session,cancel,'submit',{})
            parts=[header[:1],None,header[1:]]+[raw[i:i+113] for i in range(0,len(raw),113)]
            conn=Connection(parts); self.assertEqual(exchange(conn),result); self.assertTrue(conn.closed)
            tiny=encoded({'ok':True,'result':{'value':'y'*2048}})
            parts=[bytes([value]) for value in struct.pack('!I',len(tiny))+tiny]
            conn=Connection(parts); self.assertEqual(exchange(conn),{'value':'y'*2048}); self.assertTrue(conn.closed)
            conn=Connection([struct.pack('!I', 2*64*1024*1024+1)])
            with self.assertRaisesRegex(ValueError,'response exceeds bound'): exchange(conn)
            self.assertTrue(conn.closed)
            conn=Connection([header,raw[:5]])
            with self.assertRaisesRegex(ValueError,'response incomplete'): exchange(conn)
            self.assertTrue(conn.closed)
            conn=Connection([None],cancel_on_timeout=True)
            with self.assertRaisesRegex(ValueError,'cancelled or expired'): exchange(conn)
            self.assertTrue(conn.closed)
            cancel.clear(); conn=Connection([header])
            with self.assertRaisesRegex(ValueError,'cancelled or expired'): exchange(conn, time.monotonic()-1)
            self.assertTrue(conn.closed)

    @unittest.skipUnless(os.name == 'posix', 'Linux release gate')
    def test_gpu_pid_disappearance_alone_retains_unknown_and_owner(self):
        with TemporaryDirectory() as root:
            gpu_tool=Path(root)/'nvidia-smi'; gpu_tool.write_bytes(b'fixture')
            worker=Worker({'workspace':root,'stages':{},'containerId':'fixture',
                           'nvidiaSmi':str(gpu_tool),'gpuUuid':'GPU-fixture'},'example-secret')
            op=envelope('document-embedding',b'{}',{'source':'fixture'},{'model':'fixture'})
            directory=worker.operations/op['operationId']; directory.mkdir(); (directory/'request.json').write_bytes(encoded(op))
            (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],'stage':op['stage'],
                'status':'starting','bindingSha256':op['bindingSha256']}))
            worker.marker.write_bytes(encoded({'operationId':op['operationId'], 'state':'occupied_or_release_unknown'}))
            (Path(root)/'cgroup.procs').write_text('')
            cancellation=threading.Event(); finished=threading.Event(); finished.set()
            worker.active[op['operationId']]=(cancellation,finished)
            class Tree:
                pid=123; group=Path(root); process=SimpleNamespace(poll=lambda:0)
                def __init__(self): self.count=0
                def active(self): self.count+=1; return 1 if self.count == 1 else 0
                def stop(self): pass
                def close(self): pass
            tree=Tree(); boot={'cgroupRoot':root,'env':{},'runtimeAssets':[
                {'path':str(gpu_tool),'byteCount':7,'sha256':hashlib.sha256(b'fixture').hexdigest()}]}
            with patch.object(worker,'_validate_profile',return_value=(boot,1)), \
                 patch.object(worker,'_delegated_root',return_value=Path(root)), \
                 patch.object(worker,'_cgroup_pids',return_value={os.getpid()}), \
                 patch('fixed_gpu_worker._LinuxTree',return_value=tree), \
                 patch('fixed_gpu_worker.os.geteuid',return_value=-1), patch('fixed_gpu_worker.os.access',return_value=False), \
                 patch.object(worker,'_tree_pids',return_value={123}), \
                 patch('fixed_gpu_worker.observe_running',return_value={'processes':[{'pid':123}]}), \
                 patch('fixed_gpu_worker.observe_release',return_value={
                     'status':'release_unknown','reason':'not independently proven'}):
                worker._execute(directory,op,{'mode':'job','profile':{}},cancellation,finished)
            record=json.loads((directory/'status.json').read_bytes())
            self.assertEqual(record['status'],'release_unknown'); self.assertEqual(record['release'],{'status':'release_unknown'})
            self.assertIn('not independently proven',record['failure']); self.assertTrue(worker.marker.exists())

    def test_model_session_admission_pins_one_kind_and_owner(self):
        with TemporaryDirectory() as root:
            profile={'mode':'model-session','profile':{'adapter':'consense.model-session.v1'},'timeoutSeconds':10}
            worker=Worker({'workspace':root,'stages':{'document-embedding':profile}}, 'example-secret')
            binding={'requestId':'1'*32,'sourceScopeSha256':'2'*64,'modelKind':'embedding',
                     'modelIdentity':{'id':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':4,
                     'deadlineBudgetMs':5000,'maxBatches':2,'maxTotalBytes':4096}
            request={'stage':'document-embedding','binding':binding,'bodyBase64':base64.b64encode(b'{}').decode(),
                     'expectedProfileSha256':hashlib.sha256(encoded(profile['profile'])).hexdigest(), 'expectedMode':'model-session'}
            with ExitStack() as stack:
                stack.enter_context(patch.object(worker,'_validate_profile'))
                thread = stack.enter_context(patch('fixed_gpu_worker.threading.Thread'))
                if os.name == 'nt':
                    fixture = Path(root)/'directory-fsync-fixture'; fixture.write_bytes(b'fixture')
                    real_open = os.open
                    def fixture_open(path, flags, *args, **kwargs):
                        if Path(path) == Path(root) and flags == os.O_RDONLY:
                            return real_open(fixture, flags)
                        return real_open(path, flags, *args, **kwargs)
                    stack.enter_context(patch('fixed_gpu_worker.os.open', side_effect=fixture_open))
                    stack.enter_context(patch('fixed_gpu_worker.os.fsync'))
                wrong={**request,'binding':{**binding,'modelKind':'reranker'}}
                with self.assertRaises(ValueError): worker.start(wrong)
                self.assertFalse(worker.marker.exists())
                result=worker.start(request); thread.return_value.start.assert_called_once()
                with self.assertRaises(ValueError): worker.start(request)
            directory=worker.operations/result['operationId']; token=directory/'model-session.token'
            if os.name == 'posix': self.assertEqual(os.stat(token).st_mode & 0o777,0o600)
            self.assertEqual(worker.status(result['operationId'])['sourceSha256'],hashlib.sha256(encoded(binding)).hexdigest())
            self.assertLessEqual(worker.sessions[result['operationId']]['deadline']-time.monotonic(),5)

    def test_model_http_disconnect_requests_cancellation(self):
        old_stack=threading.stack_size(); threading.stack_size(1024*1024)
        try:
            with TemporaryDirectory() as root:
                worker=Worker({'workspace':root,'stages':{}},'example-secret')
                source={'requestId':'1'*32,'sourceScopeSha256':'2'*64,'modelKind':'embedding',
                        'modelIdentity':{'id':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':4,
                        'deadlineBudgetMs':5000,'maxBatches':4,'maxTotalBytes':4096}
                op=envelope('document-embedding',b'{}',source,{'adapter':'consense.model-session.v1'})
                directory=Path(root)/'gpu-operations'/op['operationId']; directory.mkdir()
                (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({
                    'operationId':op['operationId'],'stage':op['stage'],'status':'ready','bindingSha256':op['bindingSha256']}))
                cancellation=threading.Event(); worker.active[op['operationId']]=(cancellation,threading.Event())
                worker.trees[op['operationId']]=object(); worker.sessions[op['operationId']]={'lock':threading.Lock(),
                    'deadline':time.monotonic()+4,'token':'fixture','receipts':[],'totalBytes':0}
                entered=threading.Event()
                def blocking_exchange(*args):
                    entered.set(); cancellation.wait(2); raise ValueError('cancelled fixture')
                gateway=make_server(worker,('127.0.0.1',0)); thread=threading.Thread(target=gateway.serve_forever,daemon=True)
                thread.start()
                try:
                    raw=encoded({'kind':'dimension'}); body=encoded({'ordinal':0,'inputSha256':hashlib.sha256(raw).hexdigest(),
                        'bodyBase64':base64.b64encode(raw).decode()})
                    with patch.object(worker,'_model_exchange',side_effect=blocking_exchange):
                        conn=socket.create_connection(gateway.server_address,timeout=2)
                        conn.sendall(('POST /operations/'+op['operationId']+'/model-batches HTTP/1.1\r\nHost: localhost\r\n'
                            'Authorization: Bearer example-secret\r\nContent-Length: '+str(len(body))+'\r\n\r\n').encode()+body)
                        self.assertTrue(entered.wait(2)); conn.close(); self.assertTrue(cancellation.wait(2))
                finally: gateway.shutdown(); gateway.server_close(); thread.join(2)
        finally: threading.stack_size(old_stack)

    @unittest.skipUnless(os.name == 'posix' and hasattr(os, 'fork'), 'private UDS and fork required')
    def test_model_gateway_ipc_binds_actual_peer_before_batches(self):
        import signal
        from model_session_worker import serve_assigned
        from test_model_session_worker import FakeBackend
        old_stack = threading.stack_size(); threading.stack_size(1024*1024)
        try:
            with TemporaryDirectory() as root:
                backend = FakeBackend(); worker = Worker({'workspace':root, 'stages':{}}, 'example-secret')
                source = {'requestId':'1'*32, 'sourceScopeSha256':'2'*64, 'modelKind':'embedding',
                          'modelIdentity':backend.identity, 'runtime':backend.runtime, 'inferenceCap':512,
                          'deadlineBudgetMs':5000, 'maxBatches':2, 'maxTotalBytes':10000}
                op = envelope('document-embedding', b'{}', source, {'adapter':'consense.model-session.v1'})
                raw = encoded(op); directory = Path(root)/'gpu-operations'/op['operationId']; directory.mkdir(mode=0o700)
                (directory/'request.json').write_bytes(raw); (directory/'status.json').write_bytes(encoded({
                    'operationId':op['operationId'],'stage':op['stage'],'status':'ready','bindingSha256':op['bindingSha256']}))
                token_path = directory/'model-session.token'; token_path.write_text('authored-token'); os.chmod(token_path,0o600)
                deadline=time.monotonic()+5; pid=os.fork()
                if pid == 0:
                    try:
                        for key in list(os.environ):
                            if key.startswith('CONSENSE_FIXED_GPU_'): del os.environ[key]
                        os.environ.update({'CONSENSE_FIXED_WORKSPACE':root, 'CONSENSE_FIXED_CONTAINER_ID':'fixture',
                            'CONSENSE_FIXED_OPERATION_ID':op['operationId'], 'CONSENSE_OPERATION_REQUEST':str(directory/'request.json'),
                            'CONSENSE_OPERATION_REQUEST_SHA256':hashlib.sha256(raw).hexdigest(),
                            'CONSENSE_MODEL_SESSION_PROFILE_SHA256':op['profileSha256'],
                            'CONSENSE_MODEL_SESSION_DEADLINE_MONOTONIC':str(deadline), 'CONSENSE_MODEL_SESSION_TOKEN_FILE':str(token_path),
                            'CONSENSE_MODEL_DEVICE':'cuda', 'CONSENSE_MODEL_OFFLINE':'1', 'HF_HUB_OFFLINE':'1', 'TRANSFORMERS_OFFLINE':'1'})
                        sys.modules['server']=backend; serve_assigned()
                    except BaseException: os._exit(2)
                    os._exit(0)
                try:
                    group=Path(root)/'fake-cgroup'; group.mkdir(); (group/'cgroup.procs').write_text(str(pid))
                    cancellation=threading.Event(); finished=threading.Event()
                    worker.active[op['operationId']]=(cancellation,finished); worker.trees[op['operationId']]=SimpleNamespace(group=group)
                    worker.sessions[op['operationId']]={'lock':threading.Lock(), 'deadline':deadline, 'token':'authored-token',
                                                        'receipts':[], 'totalBytes':0}
                    while not (directory/'model-session.ready.json').exists() and time.monotonic() < deadline: time.sleep(.01)
                    batch_body=encoded({'kind':'dimension'}); batch={'ordinal':0, 'inputSha256':hashlib.sha256(batch_body).hexdigest(),
                                                                    'bodyBase64':base64.b64encode(batch_body).decode()}
                    result=worker._model_action(op['operationId'],'submit',batch)
                    self.assertEqual(result['result'],{'dimension':2}); self.assertTrue(result['provisional'])
                    self.assertEqual(result,worker._model_action(op['operationId'],'read_completed',
                                     {'ordinal':0,'inputSha256':batch['inputSha256']}))
                    rows=[{key:result[key] for key in ('ordinal','inputSha256','outputSha256')}]
                    self.assertEqual('finishing',worker._model_action(op['operationId'],'finish',{'batches':rows})['status'])
                    self.assertTrue(finished.is_set()); self.assertEqual(os.waitpid(pid,os.WNOHANG),(0,0))
                finally:
                    os.kill(pid,signal.SIGTERM); os.waitpid(pid,0)
        finally: threading.stack_size(old_stack)

    def test_model_session_gateway_binds_batches_and_finish_without_claiming_release(self):
        from model_session_worker import ModelSession
        with TemporaryDirectory() as root:
            workspace = Path(root); worker = Worker({'workspace':root, 'stages':{}}, 'example-secret')
            source = {'requestId':'1'*32, 'sourceScopeSha256':'2'*64, 'modelKind':'embedding',
                      'modelIdentity':{'id':'fixture'}, 'runtime':{'device':'cuda'}, 'inferenceCap':4,
                      'deadlineBudgetMs':5000, 'maxBatches':4, 'maxTotalBytes':4096}
            op = envelope('document-embedding', b'{}', source, {'adapter':'consense.model-session.v1'})
            directory = workspace / 'gpu-operations' / op['operationId']; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({
                'operationId':op['operationId'], 'stage':op['stage'], 'status':'ready', 'bindingSha256':op['bindingSha256'],
                'release':{'status':'pending'}}))
            cancellation = threading.Event(); finished = threading.Event(); deadline = time.monotonic()+4
            worker.active[op['operationId']] = (cancellation,finished); worker.trees[op['operationId']] = object()
            worker.sessions[op['operationId']] = {'lock':threading.Lock(), 'deadline':deadline, 'token':'fixture', 'receipts':[], 'totalBytes':0}
            runtime = SimpleNamespace(logical_identity=lambda kind:source['modelIdentity'], inference_cap=lambda kind:4)
            backend = SimpleNamespace(window_runtime=lambda:runtime, model_runtime=lambda kind:source['runtime'],
                                      embedding_model=lambda:SimpleNamespace(get_sentence_embedding_dimension=lambda:2))
            model = ModelSession(encoded(op), directory/'model-session', operation_sha256=hashlib.sha256(encoded(op)).hexdigest(),
                                 profile_sha256=op['profileSha256'], deadline=deadline, cancelled=cancellation.is_set, backend=backend)
            def exchange(directory, operation, tree, session, cancel, method, payload):
                if method == 'submit': return model.submit(payload)
                if method == 'read_completed': return model.read_completed(payload['ordinal'], payload['inputSha256'])
                return model.finish(payload['batches'])
            raw = encoded({'kind':'dimension'}); batch = {'ordinal':0,'inputSha256':hashlib.sha256(raw).hexdigest(),
                                                            'bodyBase64':base64.b64encode(raw).decode()}
            with patch.object(worker, '_model_exchange', side_effect=exchange):
                result = worker._model_action(op['operationId'], 'submit', batch)
                self.assertTrue(result['provisional']); self.assertEqual({'dimension':2}, result['result'])
                self.assertEqual(result, worker._model_action(op['operationId'], 'read_completed',
                                 {'ordinal':0,'inputSha256':batch['inputSha256']}))
                receipt = {key:result[key] for key in ('ordinal','inputSha256','outputSha256')}
                self.assertEqual('finishing', worker._model_action(op['operationId'], 'finish', {'batches':[receipt]})['status'])
            self.assertTrue(finished.is_set()); self.assertEqual({'status':'pending'}, worker.status(op['operationId'])['release'])
            with self.assertRaises(ValueError): worker.model_summary(op['operationId'])
            record = json.loads((directory/'status.json').read_bytes())
            record.update(status='completed', release={'status':'released','cgroupPopulated':0,'observedGpuPids':[123]},
                          modelSummarySha256=hashlib.sha256((directory/'model-session'/'finished.json').read_bytes()).hexdigest())
            (directory/'status.json').write_bytes(encoded(record))
            summary = worker.model_summary(op['operationId'])
            self.assertEqual(hashlib.sha256(encoded([receipt])).hexdigest(), summary['batchesSha256'])
            (directory/'model-session'/'finished.json').write_bytes(b'{}')
            with self.assertRaises(ValueError): worker.model_summary(op['operationId'])

    def test_model_session_gateway_duplicate_is_terminal_and_does_not_recompute(self):
        with TemporaryDirectory() as root:
            worker = Worker({'workspace':root, 'stages':{}}, 'example-secret')
            source = {'requestId':'1'*32, 'sourceScopeSha256':'2'*64, 'modelKind':'embedding',
                      'modelIdentity':{'id':'fixture'}, 'runtime':{'device':'cuda'}, 'inferenceCap':4,
                      'deadlineBudgetMs':5000, 'maxBatches':4, 'maxTotalBytes':4096}
            op = envelope('document-embedding', b'{}', source, {'adapter':'consense.model-session.v1'})
            directory = Path(root)/'gpu-operations'/op['operationId']; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({
                'operationId':op['operationId'], 'stage':op['stage'], 'status':'ready', 'bindingSha256':op['bindingSha256']}))
            cancellation = threading.Event(); worker.active[op['operationId']] = (cancellation,threading.Event())
            worker.trees[op['operationId']] = object(); worker.sessions[op['operationId']] = {
                'lock':threading.Lock(), 'deadline':time.monotonic()+4, 'token':'fixture',
                'receipts':[{'ordinal':0,'inputSha256':'a'*64,'outputSha256':'b'*64}], 'totalBytes':0}
            with patch.object(worker, '_model_exchange') as exchange:
                with self.assertRaises(ValueError): worker._model_action(op['operationId'], 'submit',
                    {'ordinal':0,'inputSha256':'a'*64,'bodyBase64':''})
                exchange.assert_not_called(); self.assertTrue(cancellation.is_set())

    @unittest.skipUnless(os.name == 'posix', 'Linux proc socket ownership required')
    def test_provider_connection_to_unowned_listener_never_receives_source(self):
        old_stack=threading.stack_size(); threading.stack_size(1024*1024)
        with TemporaryDirectory() as root:
            workspace=Path(root); sample=b'private-authored-image'; digest=hashlib.sha256(sample).hexdigest()
            listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1); port=listener.getsockname()[1]
            output=workspace/'received.bin'
            child_code = 'import os,socket,sys; s=socket.socket(fileno=int(sys.argv[1])); c,_=s.accept(); c.settimeout(3); data=c.recv(128); open(sys.argv[2],"wb").write(data); c.close(); s.close()'
            child=subprocess.Popen([sys.executable,'-c',child_code,str(listener.fileno()),str(output)],pass_fds=(listener.fileno(),))
            listener.close()
            worker=Worker({'workspace':root,'stages':{'paddle':{'readyUrl':'http://127.0.0.1:%d/health'%port,
                'timeoutSeconds':10,'providerOptions':{}}}},secret='example-secret')
            binding={'parser':'paddleocr-vl','inputSha256':digest,'inputByteCount':len(sample),
                     'originalSourceSha256':'a'*64,'physicalPageNo':1,'profileSha256':'b'*64}
            op=envelope('paddle',b'{}',binding,{'model':'fixture'}); directory=workspace/'gpu-operations'/op['operationId']; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({
                'operationId':op['operationId'],'stage':'paddle','status':'ready','bindingSha256':op['bindingSha256'],
                'deadlineUnixMs':int((time.time()+.8)*1000)}))
            group=workspace/'fake-cgroup'; group.mkdir(); (group/'cgroup.procs').write_text(str(os.getpid()))
            worker.active[op['operationId']]=(threading.Event(),threading.Event()); worker.trees[op['operationId']]=SimpleNamespace(group=group)
            worker.sessions[op['operationId']]={'lock':threading.Lock()}
            server=make_server(worker,('127.0.0.1',0)); thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            url='http://127.0.0.1:%d/operations/%s/provider/layout-parsing'%(server.server_port,op['operationId'])
            try:
                request=urllib.request.Request(url,data=encoded({'file':base64.b64encode(sample).decode()}),
                    headers={'Authorization':'Bearer example-secret','Content-Type':'application/json',
                             'X-Consense-Provider-Dispatch-Id':uuid.uuid4().hex})
                with patch.object(worker,'_listener_owned',return_value=True):
                    with self.assertRaises(urllib.error.HTTPError) as rejected: urllib.request.urlopen(request,timeout=3)
                    self.assertEqual(0,child.wait(timeout=3)); self.assertEqual(b'',output.read_bytes())
                    self.assertIn(b'provider operation failed',rejected.exception.read())
            finally:
                server.shutdown(); server.server_close(); thread.join()
                if child.poll() is None: child.terminate(); child.wait(timeout=3)
                threading.stack_size(old_stack)

    def test_status_derives_input_and_profile_hashes_from_bound_request(self):
        with TemporaryDirectory() as root:
            workspace=Path(root); worker=Worker({'workspace':root,'stages':{}},secret='example-secret')
            op=envelope('business-llm',b'original body',{'project':'first'},{'model':'first','private':'do-not-display'})
            directory=workspace/'gpu-operations'/op['operationId']; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op))
            state={'operationId':op['operationId'],'stage':op['stage'],'status':'completed','bindingSha256':op['bindingSha256']}
            (directory/'status.json').write_bytes(encoded(state))
            server=make_server(worker,('127.0.0.1',0)); thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            url='http://127.0.0.1:%d/operations/%s' % (server.server_port,op['operationId'])
            def get():
                req=urllib.request.Request(url,headers={'Authorization':'Bearer example-secret'})
                with urllib.request.urlopen(req,timeout=2) as result: return json.load(result)
            try:
                observed=get()
                self.assertEqual(hashlib.sha256(b'original body').hexdigest(),observed['bodySha256'])
                self.assertEqual(hashlib.sha256(encoded(op['source'])).hexdigest(),observed['sourceSha256'])
                self.assertEqual(hashlib.sha256(encoded(op['profile'])).hexdigest(),observed['profileSha256'])
                self.assertNotIn('do-not-display',json.dumps(observed))
                replacement=envelope('business-llm',b'other body',{'project':'second'},{'model':'second'})
                replacement['operationId']=op['operationId']
                replacement['bindingSha256']=hashlib.sha256(encoded({k:v for k,v in replacement.items() if k!='bindingSha256'})).hexdigest()
                (directory/'request.json').write_bytes(encoded(replacement))
                with self.assertRaises(urllib.error.HTTPError) as error: get()
                self.assertEqual(400,error.exception.code)
                state['bindingSha256']=replacement['bindingSha256']; (directory/'status.json').write_bytes(encoded(state))
                changed=get()
                self.assertNotEqual(observed['bodySha256'],changed['bodySha256'])
                self.assertNotEqual(observed['sourceSha256'],changed['sourceSha256'])
                self.assertNotEqual(observed['profileSha256'],changed['profileSha256'])
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_non_success_stream_waits_for_bound_result_and_release(self):
        for code, outcome in ((429,'released'), (503,'released'), (429,'unknown'), (503,'bad_hash'), (429,'malformed_release')):
            with self.subTest(code=code,outcome=outcome), TemporaryDirectory() as root:
                workspace=Path(root); worker=Worker({'workspace':root,'stages':{}},secret='example-secret')
                op=envelope('business-llm',b'{"stream":true}',{'httpPath':'/v1/chat/completions'},{'model':'fixture'})
                directory=workspace/'gpu-operations'/op['operationId']; directory.mkdir()
                body=('{"authored_error":%d}' % code).encode()
                (directory/'request.json').write_bytes(encoded(op))
                state={'operationId':op['operationId'],'stage':'business-llm','status':'running',
                       'bindingSha256':op['bindingSha256'],'release':{'status':'pending'}}
                (directory/'status.json').write_bytes(encoded(state))
                (directory/'stream.headers.json').write_bytes(encoded({'bindingSha256':op['bindingSha256'],
                    'status':code,'headers':{'content-type':'application/json'}}))
                (directory/'stream.bin').write_bytes(body)
                worker.active[op['operationId']]=(threading.Event(),threading.Event())
                server=make_server(worker,('127.0.0.1',0)); thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
                conn=socket.create_connection(('127.0.0.1',server.server_port),timeout=2); conn.settimeout(3)
                try:
                    conn.sendall(('GET /operations/'+op['operationId']+'/stream HTTP/1.0\r\nAuthorization: Bearer example-secret\r\n\r\n').encode())
                    time.sleep(.2)
                    self.assertFalse(select.select([conn],[],[],0)[0], 'upstream error leaked before release')
                    result={'contract':'consense.gpu-operation-response.v1','bindingSha256':op['bindingSha256'],
                            'status':code,'headers':{'content-type':'application/json'},'bodyBase64':base64.b64encode(body).decode()}
                    raw=encoded(result); (directory/'response.json').write_bytes(raw)
                    release={'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}
                    if outcome=='unknown': release={'status':'release_unknown'}
                    if outcome=='malformed_release': release={'status':'released','cgroupPopulated':False,'observedGpuPids':[123]}
                    state.update(status='failed',release=release,responseSha256='0'*64 if outcome=='bad_hash' else hashlib.sha256(raw).hexdigest())
                    temporary=directory/'next.json'; temporary.write_bytes(encoded(state)); temporary.replace(directory/'status.json')
                    wire=b''
                    while True:
                        part=conn.recv(4096)
                        if not part: break
                        wire+=part
                    if outcome=='released':
                        self.assertIn((' %d ' % code).encode(),wire.split(b'\r\n',1)[0]); self.assertIn(body,wire)
                    else:
                        self.assertNotIn(body,wire); self.assertIn(b'X-Consense-Gateway-Error: 1',wire)
                finally:
                    conn.close(); server.shutdown(); server.server_close(); thread.join()

    def test_cancel_does_not_wait_for_slow_finish_validation(self):
        with TemporaryDirectory() as root:
            workspace=Path(root); worker=Worker({'workspace':root,'artifactStoreRoot':root,'stages':{}},secret='example-secret')
            op=envelope('paddle',b'{}',{'source':'fixture'},{'model':'fixture'}); directory=workspace/'gpu-operations'/op['operationId']; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({
                'operationId':op['operationId'],'stage':'paddle','status':'ready','bindingSha256':op['bindingSha256']}))
            worker.active[op['operationId']]=(threading.Event(),threading.Event()); worker.sessions[op['operationId']]={'lock':threading.Lock()}
            server=make_server(worker,('127.0.0.1',0)); thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            url='http://127.0.0.1:%d/operations/%s' % (server.server_port,op['operationId']); entered=threading.Event(); release=threading.Event(); outcome=[]
            def slow_verify(*args): entered.set(); release.wait(3)
            def finish():
                try:
                    req=urllib.request.Request(url+'/finish',data=encoded({'transaction':{}}),headers={'Authorization':'Bearer example-secret','Content-Type':'application/json'})
                    urllib.request.urlopen(req,timeout=4)
                    outcome.append('accepted')
                except urllib.error.HTTPError as error: outcome.append(error.code)
            finisher=threading.Thread(target=finish,daemon=True)
            try:
                with patch('native_session_worker.verify_transaction',side_effect=slow_verify):
                    finisher.start(); self.assertTrue(entered.wait(2))
                    request=urllib.request.Request(url,method='DELETE',headers={'Authorization':'Bearer example-secret'})
                    with urllib.request.urlopen(request,timeout=1) as result: self.assertEqual(202,result.status)
                    release.set(); finisher.join(2); self.assertEqual([400],outcome)
                    self.assertFalse((directory/'transaction.json').exists())
            finally:
                release.set(); finisher.join(4); server.shutdown(); server.server_close(); thread.join()

    def test_finish_rejects_changed_operation_binding_before_cas(self):
        with TemporaryDirectory() as root:
            workspace=Path(root); worker=Worker({'workspace':root,'artifactStoreRoot':root,'stages':{}},secret='example-secret')
            op=envelope('paddle',b'{}',{'source':'original'},{'model':'fixture'}); directory=workspace/'gpu-operations'/op['operationId']; directory.mkdir()
            op['source']['source']='changed'; (directory/'request.json').write_bytes(encoded(op))
            (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],'stage':'paddle','status':'ready','bindingSha256':op['bindingSha256']}))
            worker.active[op['operationId']]=(threading.Event(),threading.Event()); worker.sessions[op['operationId']]={'lock':threading.Lock()}
            server=make_server(worker,('127.0.0.1',0)); thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            url='http://127.0.0.1:%d/operations/%s/finish' % (server.server_port,op['operationId'])
            try:
                req=urllib.request.Request(url,data=encoded({'transaction':{}}),headers={'Authorization':'Bearer example-secret','Content-Type':'application/json'})
                with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(req,timeout=2)
                self.assertIn(b'operation binding changed',error.exception.read())
            finally:
                server.shutdown(); server.server_close(); thread.join()

    @unittest.skipUnless(os.name == 'posix', 'Linux SO_PEERCRED Unix socket required')
    def test_unix_gateway_and_ocr_client_with_fake_providers(self):
        from fixed_gpu_provider import proxy_request
        from complete_ocr import HttpCaptureFailure
        sample = b'authored-image'; digest = hashlib.sha256(sample).hexdigest()
        replies = {
            '/layout-parsing':(503, b'{"detail":{"kind":"native_capture_failed"}}'),
            '/v1/uploads':(200,encoded({'id':'upload_1','status':'uploading'})),
            '/v1/uploads/upload_1/content':(200,b'{}'),
            '/v1/uploads/upload_1/complete':(200,encoded({'status':'completed','sha256sum':digest,'bytes':len(sample),'file':{'id':'file-1'}})),
            '/v1/parse/jobs':(200,encoded({'job_id':'job_1'})),
            '/v1/parse/jobs/job_1':(200,encoded({'job_id':'job_1','status':'completed','files':[{'file_id':'file-1','status':'completed','output_files':{'zip':{'file_id':'file-zip'}}}]})),
            '/v1/files/file-zip/content':(200,b'authored zip bytes')}
        class Provider(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def respond(self):
                length = int(self.headers.get('Content-Length','0')); self.rfile.read(length)
                status, body = replies[self.path]
                self.send_response(status); self.send_header('Content-Type','application/octet-stream' if self.path.endswith('/content') and self.command == 'GET' else 'application/json')
                self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
            do_GET = do_POST = do_PUT = respond
        with TemporaryDirectory() as root:
            workspace = Path(root); os.chmod(workspace,0o700)
            token = workspace/'token'; token.write_text('fixture-secret-123456'); os.chmod(token,0o600)
            providers = [ThreadingHTTPServer(('127.0.0.1',0),Provider) for _ in range(2)]
            threads = [threading.Thread(target=s.serve_forever,daemon=True) for s in providers]
            for t in threads: t.start()
            config = {'workspace':root,'peerUid':os.geteuid(),'peerGid':os.getegid(),'stages':{
                stage:{'readyUrl':'http://127.0.0.1:%d/health' % server.server_port,'timeoutSeconds':25,'providerOptions':{}}
                for stage,server in zip(('paddle','mineru'),providers)}}
            worker = Worker(config,secret='fixture-secret-123456')
            fake_group = workspace/'fake-cgroup'; fake_group.mkdir(); (fake_group/'cgroup.procs').write_text(str(os.getpid()))
            operations = {}
            for stage in ('paddle','mineru'):
                binding={'parser':'paddleocr-vl' if stage=='paddle' else 'mineru','inputSha256':digest,'inputByteCount':len(sample),
                         'originalSourceSha256':'a'*64,'physicalPageNo':1,'profileSha256':'b'*64}
                op=envelope(stage,b'{}',binding,{'model':'fixture'}); operations[stage]=op
                directory=workspace/'gpu-operations'/op['operationId']; directory.mkdir()
                (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({
                    'operationId':op['operationId'],'stage':stage,'status':'ready','bindingSha256':op['bindingSha256'],
                    'deadlineUnixMs':int((time.time()+20)*1000)}))
                worker.active[op['operationId']]=(threading.Event(),threading.Event()); worker.trees[op['operationId']]=SimpleNamespace(group=fake_group)
                worker.sessions[op['operationId']]={'lock':threading.Lock()}
            socket_path=workspace/'gateway.sock'; gateway=make_server(worker,uds_path=socket_path)
            gateway_thread=threading.Thread(target=gateway.serve_forever,daemon=True); gateway_thread.start()
            def provider(stage):
                return {'baseUrl':'http://127.0.0.1:'+('28771' if stage=='paddle' else '28772/v1'),
                        'ownedFixed':{'stage':stage,'socketPath':str(socket_path),'tokenFile':str(token),'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2,
                                      'gatewayProfileSha256':operations[stage]['profileSha256']}}
            def call(stage,path,body=None,method=None):
                p=provider(stage); lifecycle={'mode':'owned_fixed_gpu','operationId':operations[stage]['operationId'],'providerEvidenceProtocol':'dispatch-v1'}
                return proxy_request(p,lifecycle,p['baseUrl']+path,body=body,method=method,timeout=3,dispatch_id=uuid.uuid4().hex)
            try:
                bad_token = workspace/'bad-token'; bad_token.write_text('incorrect-token-123456'); os.chmod(bad_token,0o600)
                p = provider('paddle'); p['ownedFixed']['tokenFile'] = str(bad_token)
                lifecycle = {'mode':'owned_fixed_gpu','operationId':operations['paddle']['operationId'],'providerEvidenceProtocol':'dispatch-v1'}
                with self.assertRaises(HttpCaptureFailure) as failure:
                    proxy_request(p,lifecycle,p['baseUrl']+'/layout-parsing',body={'file':base64.b64encode(sample).decode()},timeout=3,dispatch_id=uuid.uuid4().hex)
                self.assertEqual(401,failure.exception.status); self.assertTrue(failure.exception.gateway)
                with self.assertRaises(HttpCaptureFailure) as failure:
                    call('paddle','/layout-parsing',{'file':base64.b64encode(b'wrong-image').decode()})
                self.assertEqual(503,failure.exception.status); self.assertTrue(failure.exception.gateway)
                p = provider('mineru'); lifecycle = {'mode':'owned_fixed_gpu','operationId':operations['paddle']['operationId'],'providerEvidenceProtocol':'dispatch-v1'}
                with self.assertRaises(HttpCaptureFailure) as failure:
                    proxy_request(p,lifecycle,p['baseUrl']+'/uploads',body={'bytes':len(sample),'sha256sum':digest},timeout=3,dispatch_id=uuid.uuid4().hex)
                self.assertEqual(503,failure.exception.status); self.assertTrue(failure.exception.gateway)
                with self.assertRaises(HttpCaptureFailure) as failure:
                    call('paddle','/layout-parsing',{'file':base64.b64encode(sample).decode()})
                self.assertEqual(503,failure.exception.status); self.assertTrue(failure.exception.complete)
                self.assertFalse(failure.exception.gateway); self.assertEqual(replies['/layout-parsing'][1],failure.exception.raw)
                call('mineru','/uploads',{'filename':'page.png','bytes':len(sample),'mime_type':'image/png','purpose':'parse','sha256sum':digest})
                with self.assertRaises(HttpCaptureFailure) as failure:
                    call('mineru','/uploads/upload_wrong/content',sample,'PUT')
                self.assertTrue(failure.exception.gateway)
                call('mineru','/uploads/upload_1/content',sample,'PUT')
                call('mineru','/uploads/upload_1/complete',{'sha256sum':digest})
                call('mineru','/parse/jobs',{'files':[{'source':{'type':'file_id','file_id':'file-1'}}]})
                call('mineru','/parse/jobs/job_1')
                self.assertEqual(b'authored zip bytes',call('mineru','/files/file-zip/content'))
            finally:
                gateway.shutdown(); gateway.server_close(); gateway_thread.join()
                for s in providers: s.shutdown(); s.server_close()
                for t in threads: t.join()

    def test_mineru_proxy_tracks_upload_job_and_zip_identity(self):
        with TemporaryDirectory() as root:
            workspace = Path(root); sample = b'authored-image'; digest = hashlib.sha256(sample).hexdigest()
            binding = {'parser':'mineru','inputSha256':digest,'inputByteCount':len(sample),
                       'originalSourceSha256':'a'*64,'physicalPageNo':1,'profileSha256':'b'*64}
            worker = Worker({'workspace':root,'stages':{'mineru':{'readyUrl':'http://127.0.0.1:28772/health','timeoutSeconds':30,'providerOptions':{}}}}, secret='example-secret')
            op = envelope('mineru',b'{}',binding,{'model':'fixture'}); directory = workspace/'gpu-operations'/op['operationId']; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({
                'operationId':op['operationId'],'stage':'mineru','status':'ready','bindingSha256':op['bindingSha256'],
                'deadlineUnixMs':int((time.time()+20)*1000)}))
            worker.active[op['operationId']] = (threading.Event(),threading.Event()); worker.trees[op['operationId']] = object()
            worker.sessions[op['operationId']] = {'lock':threading.Lock()}
            server = make_server(worker, ('127.0.0.1',0)); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            base = 'http://127.0.0.1:%d/operations/%s/provider' % (server.server_port,op['operationId'])
            replies = {
                '/v1/uploads':encoded({'id':'upload_1','status':'uploading'}),
                '/v1/uploads/upload_1/content':b'{}',
                '/v1/uploads/upload_1/complete':encoded({'status':'completed','sha256sum':digest,'bytes':len(sample),'file':{'id':'file-1'}}),
                '/v1/parse/jobs':encoded({'job_id':'job_1'}),
                '/v1/parse/jobs/job_1':encoded({'job_id':'job_1','status':'completed','files':[{'file_id':'file-1','status':'completed','output_files':{'zip':{'file_id':'file-zip'}}}]}),
                '/v1/files/file-zip/content':b'original zip bytes'}
            calls = []
            class Reply:
                status = 200
                def __init__(self,body):
                    from email.message import Message
                    self.data = body; self.length = len(body); self.chunked = False; self.fp = None
                    self.headers = Message(); self.headers['Content-Length'] = str(len(body))
                def getheader(self,name,default=None): return 'application/octet-stream' if self.data == b'original zip bytes' else 'application/json'
                def read1(self,size): data,self.data = self.data[:size],self.data[size:]; self.length -= len(data); return data
                def close(self): pass
            class Conn:
                def __init__(self,*args,**kwargs): self.sock=SimpleNamespace(shutdown=lambda *_:None, close=lambda:None); self.path=None
                def connect(self): pass
                def request(self,method,path,**kwargs): self.path=path; calls.append(path)
                def getresponse(self): return Reply(replies[self.path])
                def close(self): pass
            def call(method,path,value=None):
                body = value if isinstance(value,bytes) else encoded(value) if value is not None else None
                req = urllib.request.Request(base+path, data=body, method=method, headers={'Authorization':'Bearer example-secret','X-Consense-Provider-Dispatch-Id':uuid.uuid4().hex,
                    'Content-Type':'application/octet-stream' if isinstance(value,bytes) else 'application/json'})
                return urllib.request.urlopen(req,timeout=2).read()
            try:
                with patch.object(worker,'_listener_owned',return_value=True), patch.object(worker,'_connection_owned',return_value=True), patch('fixed_gpu_worker.ProviderConnection',Conn):
                    call('POST','/v1/uploads',{'sha256sum':digest,'bytes':len(sample)})
                    with self.assertRaises(urllib.error.HTTPError): call('PUT','/v1/uploads/upload_other/content',sample)
                    call('PUT','/v1/uploads/upload_1/content',sample)
                    call('POST','/v1/uploads/upload_1/complete',{'sha256sum':digest})
                    call('POST','/v1/parse/jobs',{'files':[{'source':{'type':'file_id','file_id':'file-1'}}]})
                    call('GET','/v1/parse/jobs/job_1')
                    self.assertEqual(b'original zip bytes',call('GET','/v1/files/file-zip/content'))
                self.assertEqual(6,len(calls)); self.assertNotIn('/v1/uploads/upload_other/content',calls)
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_provider_proxy_rejects_wrong_input_and_preserves_upstream_error(self):
        with TemporaryDirectory() as root:
            workspace = Path(root); sample = b'authored-image'
            binding = {'parser':'paddleocr-vl','inputSha256':hashlib.sha256(sample).hexdigest(), 'inputByteCount':len(sample),
                       'originalSourceSha256':'a'*64,'physicalPageNo':1,'profileSha256':'b'*64}
            config = {'workspace':root,'stages':{'paddle':{'readyUrl':'http://127.0.0.1:28771/health','timeoutSeconds':30,'providerOptions':{}}}}
            worker = Worker(config, secret='example-secret')
            op = envelope('paddle',b'{}',binding,{'model':'fixture'}); directory = workspace / 'gpu-operations' / op['operationId']; directory.mkdir()
            (directory / 'request.json').write_bytes(encoded(op))
            (directory / 'status.json').write_bytes(encoded({'operationId':op['operationId'],'stage':'paddle','status':'ready',
                'bindingSha256':op['bindingSha256'],'deadlineUnixMs':int((time.time()+20)*1000)}))
            worker.active[op['operationId']] = (threading.Event(),threading.Event()); worker.trees[op['operationId']] = object()
            worker.sessions[op['operationId']] = {'lock':threading.Lock()}
            server = make_server(worker, ('127.0.0.1', 0)); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            url = 'http://127.0.0.1:%d/operations/%s/provider/layout-parsing' % (server.server_port,op['operationId'])
            original = http.client.HTTPConnection; calls = []
            class Reply:
                status = 503
                def __init__(self):
                    from email.message import Message
                    self.data = b'{"detail":"native failure"}'; self.length = len(self.data); self.chunked = False; self.fp = None
                    self.headers = Message(); self.headers['Content-Length'] = str(len(self.data))
                def getheader(self, name, default=None): return 'application/json' if name == 'content-type' else default
                def read1(self, size): data,self.data = self.data[:size],self.data[size:]; self.length -= len(data); return data
                def close(self): pass
            class Conn:
                def __init__(self): self.sock = SimpleNamespace(shutdown=lambda *_:None, close=lambda:None)
                def connect(self): pass
                def request(self,*args,**kwargs): calls.append(args)
                def getresponse(self): return Reply()
                def close(self): pass
            def choose(host,port=None,*args,**kwargs): return Conn() if port == 28771 else original(host,port,*args,**kwargs)
            def post(data):
                req = urllib.request.Request(url, data=encoded({'file':base64.b64encode(data).decode()}),
                    headers={'Authorization':'Bearer example-secret','X-Consense-Provider-Dispatch-Id':uuid.uuid4().hex,'Content-Type':'application/json'})
                return urllib.request.urlopen(req, timeout=2)
            try:
                with patch.object(worker,'_listener_owned',return_value=True), patch.object(worker,'_connection_owned',return_value=True), patch('fixed_gpu_worker.ProviderConnection',side_effect=choose):
                    with self.assertRaises(urllib.error.HTTPError) as rejected: post(b'other')
                    self.assertEqual('1', rejected.exception.headers['X-Consense-Gateway-Error']); self.assertFalse(calls)
                    forged = urllib.request.Request(url, data=encoded({'file':base64.b64encode(sample).decode(), 'unexpectedOption':True}),
                        headers={'Authorization':'Bearer example-secret','X-Consense-Provider-Dispatch-Id':uuid.uuid4().hex,'Content-Type':'application/json'})
                    with self.assertRaises(urllib.error.HTTPError) as rejected: urllib.request.urlopen(forged,timeout=2)
                    self.assertEqual('1', rejected.exception.headers['X-Consense-Gateway-Error']); self.assertFalse(calls)
                    with self.assertRaises(urllib.error.HTTPError) as returned: post(sample)
                    self.assertEqual(503,returned.exception.code); self.assertIsNone(returned.exception.headers.get('X-Consense-Gateway-Error'))
                    self.assertEqual(b'{"detail":"native failure"}',returned.exception.read()); self.assertEqual(1,len(calls))
                receipts = [json.loads(p.read_bytes()) for p in directory.glob('provider-*.json') if p.stat().st_size]
                completed = [item for item in receipts if item.get('complete') is True]
                rejected = [item for item in receipts if item.get('providerOutcome') == 'not_dispatched']
                self.assertEqual(1, len(completed)); self.assertEqual(503, completed[0]['status'])
                self.assertEqual(2, len(rejected)); self.assertTrue(all(item['phase'] == 'route_validation' for item in rejected))
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_unpinned_python_entrypoint_is_rejected_before_launch(self):
        with TemporaryDirectory() as root:
            workspace = Path(root); executable = workspace / 'python'; script = workspace / 'entry.py'
            executable.write_bytes(b'python fixture'); script.write_bytes(b'entry fixture')
            asset = {'path':str(executable), 'byteCount':len(b'python fixture'), 'sha256':hashlib.sha256(b'python fixture').hexdigest()}
            profile = {'mode':'job','profile':{'model':'fixture'},'timeoutSeconds':1,'gpuRequired':True,
                       'boot':{'command':[str(executable),str(script)],'cwd':root,'cgroupRoot':root,'env':{},'runtimeAssets':[asset]}}
            worker = Worker({'workspace':root,'stages':{'business-llm':profile},'cgroupRoot':root,
                             'nvidiaSmi':str(executable),'gpuUuid':'GPU-fixture'}, secret='example-secret')
            server = make_server(worker, ('127.0.0.1', 0)); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            url = 'http://127.0.0.1:%d/operations' % server.server_port
            try:
                request = urllib.request.Request(url, data=encoded({'stage':'business-llm','binding':{'source':'fixture'},'bodyBase64':'e30='}),
                    headers={'Authorization':'Bearer example-secret','Content-Type':'application/json'})
                with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(request, timeout=2)
                self.assertEqual(400, error.exception.code)
                self.assertFalse((workspace / 'gpu-owner.json').exists())
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_streaming_request_rejects_successful_non_sse_before_headers(self):
        with TemporaryDirectory() as root:
            workspace = Path(root); worker = Worker({'workspace':root, 'stages':{}}, secret='example-secret')
            op = envelope('business-llm', b'{"stream":true}', {'httpPath':'/v1/chat/completions'}, {'model':'fixture'})
            directory = workspace / 'gpu-operations' / op['operationId']; directory.mkdir()
            (directory / 'request.json').write_bytes(encoded(op))
            (directory / 'status.json').write_bytes(encoded({'operationId':op['operationId'], 'stage':'business-llm',
                'status':'running', 'bindingSha256':op['bindingSha256']}))
            (directory / 'stream.headers.json').write_bytes(encoded({'bindingSha256':op['bindingSha256'],
                'status':200, 'headers':{'content-type':'application/json'}}))
            (directory / 'stream.bin').write_bytes(b'{"looks":"successful"}')
            worker.active[op['operationId']] = (threading.Event(), threading.Event())
            server = make_server(worker, ('127.0.0.1', 0)); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            url = 'http://127.0.0.1:%d/operations/%s/stream' % (server.server_port, op['operationId'])
            try:
                request = urllib.request.Request(url, headers={'Authorization':'Bearer example-secret'})
                with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(request, timeout=2)
                self.assertEqual(503, error.exception.code)
                self.assertTrue(worker.active[op['operationId']][0].is_set())
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_cancel_is_rejected_after_outcome_is_claimed(self):
        with TemporaryDirectory() as root:
            workspace = Path(root); worker = Worker({'workspace':root, 'stages':{}}, secret='example-secret')
            operation_id = 'a' * 32; directory = workspace / 'gpu-operations' / operation_id; directory.mkdir()
            (directory / 'status.json').write_bytes(encoded({'operationId':operation_id,'stage':'paddle','status':'settling'}))
            worker.active[operation_id] = (threading.Event(), threading.Event())
            server = make_server(worker, ('127.0.0.1', 0)); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            url = 'http://127.0.0.1:%d/operations/%s' % (server.server_port, operation_id)
            try:
                request = urllib.request.Request(url, method='DELETE', headers={'Authorization':'Bearer example-secret'})
                with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(request, timeout=2)
                self.assertEqual(400, error.exception.code)
                self.assertFalse(worker.active[operation_id][0].is_set())
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_stream_terminal_waits_for_release_and_does_not_escape_on_failure(self):
        for terminal, final, expected in [(b'data: [DONE]\n\n', 'completed', True), (b'data: [DONE]\n\n', 'release_unknown', False),
                                (b'event: response.completed\ndata: {"type":"response.completed"}\n\n', 'failed', False),
                                (b'event: response.completed\ndata: {"type":"response.completed"}\n\n', 'completed', True),
                                (b'data: {"choices":[{"finish_reason":"stop"}]}\n\n', 'release_unknown', False),
                                (b'event: response.failed\ndata: {"type":"response.failed"}\n\n', 'completed', False),
                                (b'data: [DONE]\n\n', 'completed_event_error', False),
                                (b'data: [DONE]\n\n', 'completed_type_error', False)]:
            with self.subTest(terminal=terminal, final=final), TemporaryDirectory() as root:
                workspace = Path(root); worker = Worker({'workspace':root, 'stages':{}}, secret='example-secret')
                api_path = '/v1/responses' if terminal.startswith(b'event:') else '/v1/chat/completions'
                op = envelope('business-llm', b'{"stream":true}', {'httpPath':api_path}, {'model':'fixture'})
                directory = workspace / 'gpu-operations' / op['operationId']; directory.mkdir()
                status = {'operationId':op['operationId'], 'stage':'business-llm', 'status':'running',
                          'bindingSha256':op['bindingSha256'], 'release':{'status':'pending'}}
                (directory / 'request.json').write_bytes(encoded(op))
                (directory / 'status.json').write_bytes(encoded(status))
                (directory / 'stream.headers.json').write_bytes(encoded({'bindingSha256':op['bindingSha256'],
                    'status':200, 'headers':{'content-type':'text/event-stream'}}))
                prefix = b'event: response.output_item.done\ndata: {"type":"response.output_item.done"}\n\ndata: {"delta":"hello"}\n\n'
                if final == 'completed_event_error': prefix += b'event: error\ndata: {"message":"upstream failed"}\n\n'
                if final == 'completed_type_error': prefix += b'data: {"type":"error","message":"upstream failed"}\n\n'
                stream = directory / 'stream.bin'
                stream.write_bytes(prefix + terminal[:5]); worker.active[op['operationId']] = (threading.Event(), threading.Event())
                server = make_server(worker, ('127.0.0.1', 0)); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
                conn = socket.create_connection(('127.0.0.1', server.server_port), timeout=2); conn.settimeout(2)
                try:
                    conn.sendall(('GET /operations/' + op['operationId'] + '/stream HTTP/1.0\r\nAuthorization: Bearer example-secret\r\n\r\n').encode())
                    wire = b''
                    while b'\r\n\r\n' not in wire or prefix not in wire: wire += conn.recv(4096)
                    with stream.open('ab') as writer: writer.write(terminal[5:]); writer.flush()
                    time.sleep(.2)
                    self.assertFalse(select.select([conn], [], [], 0)[0], 'success terminal escaped before release')
                    response = {'contract':'consense.gpu-operation-response.v1', 'bindingSha256':op['bindingSha256'],
                        'status':200, 'headers':{'content-type':'text/event-stream'},
                        'bodyBase64':base64.b64encode(prefix+terminal).decode()}
                    raw = encoded(response); (directory / 'response.json').write_bytes(raw)
                    committed = final.startswith('completed')
                    release = {'status':'released','cgroupPopulated':0,'observedGpuPids':[123]} if committed else {'status':'release_unknown'}
                    status.update(status='completed' if committed else final, release=release,
                                  responseSha256=hashlib.sha256(raw).hexdigest())
                    temporary = directory / 'next.json'; temporary.write_bytes(encoded(status)); temporary.replace(directory / 'status.json')
                    while True:
                        chunk = conn.recv(4096)
                        if not chunk: break
                        wire += chunk
                    self.assertEqual(expected, terminal in wire)
                finally:
                    conn.close(); server.shutdown(); server.server_close(); thread.join()

    def test_stream_preserves_upstream_status_headers_and_bytes(self):
        with TemporaryDirectory() as root:
            workspace = Path(root); worker = Worker({'workspace':root, 'stages':{}}, secret='example-secret')
            op = envelope('business-llm', b'{"stream":true}', {'httpPath':'/v1/chat/completions'}, {'model':'fixture'})
            directory = workspace / 'gpu-operations' / op['operationId']; directory.mkdir()
            response = {'contract':'consense.gpu-operation-response.v1','bindingSha256':op['bindingSha256'],
                        'status':429,'headers':{'content-type':'application/json'},
                        'bodyBase64':base64.b64encode(b'{"error":"quota"}').decode()}
            raw=encoded(response); (directory/'request.json').write_bytes(encoded(op)); (directory/'response.json').write_bytes(raw)
            (directory / 'status.json').write_bytes(encoded({'operationId':op['operationId'], 'stage':'business-llm',
                'status':'failed', 'bindingSha256':op['bindingSha256'],'responseSha256':hashlib.sha256(raw).hexdigest(),
                'release':{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}}))
            (directory / 'stream.headers.json').write_bytes(encoded({'bindingSha256':op['bindingSha256'],
                'status':429, 'headers':{'content-type':'application/json'}}))
            (directory / 'stream.bin').write_bytes(b'{"error":"quota"}')
            server = make_server(worker, ('127.0.0.1', 0)); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            url = 'http://127.0.0.1:%d/operations/%s/stream' % (server.server_port, op['operationId'])
            try:
                request = urllib.request.Request(url, headers={'Authorization':'Bearer example-secret'})
                with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(request, timeout=2)
                self.assertEqual(429, error.exception.code)
                self.assertEqual('application/json', error.exception.headers['Content-Type'])
                self.assertEqual(b'{"error":"quota"}', error.exception.read())
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    with worker.lock:
                        if op['operationId'] not in worker.streaming:
                            break
                    time.sleep(.01)
                else:
                    self.fail('first stream reader did not release')
                (directory / 'stream.headers.json').write_bytes(encoded({'bindingSha256':'0' * 64,
                    'status':200, 'headers':{'content-type':'text/event-stream'}}))
                with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(request, timeout=2)
                self.assertEqual(503, error.exception.code, error.exception.read())
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_restart_keeps_unknown_owner_and_rejects_injected_command(self):
        with TemporaryDirectory() as root:
            workspace = Path(root); executable = workspace / 'program'
            executable.write_bytes(b'fixture program')
            asset = {'path':str(executable), 'byteCount':15, 'sha256':hashlib.sha256(b'fixture program').hexdigest()}
            profile = {'mode':'job', 'profile':{'model':'fixture'}, 'timeoutSeconds':1, 'gpuRequired':True,
                       'boot':{'command':[str(executable)], 'cwd':root, 'cgroupRoot':root, 'env':{}, 'runtimeAssets':[asset]}}
            config = {'workspace':root, 'stages':{'business-llm':profile}, 'cgroupRoot':root,
                      'nvidiaSmi':str(executable), 'gpuUuid':'GPU-fixture'}
            marker = workspace / 'gpu-owner.json'; marker.write_bytes(b'{"state":"unknown"}')
            worker = Worker(config, secret='example-secret')
            operation_id = 'a' * 32
            directory = workspace / 'gpu-operations' / operation_id; directory.mkdir()
            op = envelope('business-llm',b'{}',{'source':'fixture'},{'model':'fixture'})
            op['operationId'] = operation_id
            op['bindingSha256'] = hashlib.sha256(encoded({k:v for k,v in op.items() if k!='bindingSha256'})).hexdigest()
            (directory / 'request.json').write_bytes(encoded(op))
            (directory / 'status.json').write_bytes(encoded({'operationId':operation_id, 'stage':'business-llm',
                'status':'running', 'bindingSha256':op['bindingSha256'], 'release':{'status':'pending'}}))
            server = make_server(worker, ('127.0.0.1', 0)); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            url = 'http://127.0.0.1:%d/operations' % server.server_port
            body = {'stage':'business-llm', 'binding':{'source':'fixture'}, 'bodyBase64':'e30='}
            def post(value):
                req = urllib.request.Request(url, data=encoded(value), headers={'Authorization':'Bearer example-secret', 'Content-Type':'application/json'})
                return urllib.request.urlopen(req, timeout=2)
            try:
                request = urllib.request.Request(url + '/' + operation_id, headers={'Authorization':'Bearer example-secret'})
                with urllib.request.urlopen(request, timeout=2) as observed:
                    self.assertEqual('release_unknown', json.load(observed)['status'])
                for request in (body, {**body, 'command':['untrusted']}):
                    with self.assertRaises(urllib.error.HTTPError) as error: post(request)
                    self.assertEqual(400, error.exception.code)
                self.assertEqual(b'{"state":"unknown"}', marker.read_bytes())
                self.assertEqual([directory], list((workspace / 'gpu-operations').iterdir()))
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_result_requires_released_and_exact_persisted_response(self):
        with TemporaryDirectory() as root:
            workspace = Path(root)
            worker = Worker({'workspace': str(workspace), 'stages': {}}, secret='example-secret')
            op = envelope('query-embedding-rerank', b'input', {'source':'example'}, {'model':'fixture'})
            directory = workspace / 'gpu-operations' / op['operationId']; directory.mkdir()
            response = {'contract':'consense.gpu-operation-response.v1', 'bindingSha256':op['bindingSha256'],
                        'status':200, 'headers':{'content-type':'application/json'}, 'bodyBase64':base64.b64encode(b'{"value":1}').decode()}
            result = encoded(response)
            (directory / 'request.json').write_bytes(encoded(op))
            (directory / 'response.json').write_bytes(result)
            status = {'operationId':op['operationId'], 'stage':op['stage'], 'status':'completed',
                      'bindingSha256':op['bindingSha256'], 'release':{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]},
                      'responseSha256':hashlib.sha256(result).hexdigest()}
            (directory / 'status.json').write_bytes(encoded(status))
            server = make_server(worker, ('127.0.0.1', 0))
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            url = 'http://127.0.0.1:%d/operations/%s/result' % (server.server_port, op['operationId'])
            def get():
                return urllib.request.urlopen(urllib.request.Request(url, headers={'Authorization':'Bearer example-secret'}), timeout=2)
            try:
                with get() as opened: self.assertEqual(response, json.load(opened))
                (directory / 'response.json').write_bytes(result + b' ')
                with self.assertRaises(urllib.error.HTTPError) as error: get()
                self.assertEqual(400, error.exception.code)
                (directory / 'response.json').write_bytes(result)
                status['release']['status'] = 'release_unknown'
                (directory / 'status.json').write_bytes(encoded(status))
                with self.assertRaises(urllib.error.HTTPError) as error: get()
                self.assertEqual(400, error.exception.code)
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_auth_and_undeclared_stage_never_start_an_operation(self):
        with TemporaryDirectory() as root:
            workspace = Path(root)
            worker = Worker({'workspace': str(workspace), 'stages': {}}, secret='example-secret')
            server = make_server(worker, ('127.0.0.1', 0))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            url = 'http://127.0.0.1:%d/operations' % server.server_port
            body = json.dumps({'stage': 'unknown', 'binding': {'input': 'a'}, 'bodyBase64': 'e30='}).encode()
            try:
                for token, code in [('wrong', 401), ('example-secret', 400)]:
                    request = urllib.request.Request(url, data=body, headers={'Authorization':'Bearer '+token, 'Content-Type':'application/json'})
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        urllib.request.urlopen(request, timeout=2)
                    self.assertEqual(code, error.exception.code)
                self.assertFalse((workspace / 'gpu-owner.json').exists())
            finally:
                server.shutdown(); server.server_close(); thread.join()

if __name__ == '__main__':
    unittest.main()
