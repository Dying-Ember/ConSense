"""Native HTTP wire captured through a real tiny child; no LLM or cluster runs."""
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
from types import SimpleNamespace
import socket
import sys
import threading
import time
import unittest
from unittest.mock import MagicMock, patch

from test_delivery import DeliveryFixture
import chat_operation_worker
import request_stage
import stage_worker


class ChatOperationWorkerTest(DeliveryFixture,unittest.TestCase):
    def setUp(self):
        super().setUp(); self.workspace = self.root/'workspace'; self.workspace.mkdir()
        with socket.socket() as sock: sock.bind(('127.0.0.1',0)); port = sock.getsockname()[1]
        script = "import hashlib,json\nfrom http.server import BaseHTTPRequestHandler,HTTPServer\nclass H(BaseHTTPRequestHandler):\n def do_GET(self):\n  self.send_response(200);self.end_headers();self.wfile.write(b'ok')\n def do_POST(self):\n  raw=self.rfile.read(int(self.headers['Content-Length']));self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(json.dumps({'actualRequestSha256':hashlib.sha256(raw).hexdigest(),'fixtureOnly':True}).encode())\nHTTPServer(('127.0.0.1',"+str(port)+"),H).serve_forever()"
        self.profile = {'adapter':'consense.native-chat.v1','model':'authored-only','baseUrl':'http://127.0.0.1:'+str(port),
            'command':[sys.executable,'-c',script],'environment':{'CUDA_VISIBLE_DEVICES':'0','HF_HUB_OFFLINE':'1','TRANSFORMERS_OFFLINE':'1'},
            'readyPath':'/health','timeoutSeconds':5,'apiPaths':{'/v1/chat/completions':'/v1/chat/completions'}}
        self.manifest['requestStages'] = {'business-llm':{'command':[sys.executable,str(Path(chat_operation_worker.__file__).resolve())],
            'runtimeAssets':['worker.py'],'profile':self.profile}}
        self.preparation = self.prepare(); self.deleted = False

    def runner(self,argv,payload=None):
        if 'get' in argv and 'pvc' in argv: return {'metadata':{'uid':'fixture-pvc'}}
        if 'get' in argv and 'pods' in argv:
            return {'items':[] if self.deleted or not any(value.startswith('job-name=') for value in argv) else [
                {'metadata':{'uid':'fixture-pod','ownerReferences':[{'uid':'fixture-job'}]}}]}
        if 'create' in argv:
            job = json.loads(payload); env = {row['name']:row['value'] for row in job['spec']['template']['spec']['containers'][0]['env'] if 'value' in row}
            env.update(CONSENSE_JOB_UID='fixture-job',CONSENSE_POD_UID='fixture-pod')
            with patch.dict(os.environ,env): self.worker_result = stage_worker.execute(env['CONSENSE_OPERATION_MANIFEST'],self.root,self.workspace,'business-llm')
            return {'metadata':{'uid':'fixture-job'}}
        if 'get' in argv and 'job' in argv: return {'metadata':{'uid':'fixture-job'},'status':{'succeeded':1} if self.worker_result['status'] == 'persisted' else {'failed':1}}
        if 'delete' in argv: self.deleted = True
        return {}

    def test_exact_raw_wire_reaches_owned_http_child_then_persisted_job_releases(self):
        body = b'{"model":"authored-only", "messages":[{"role":"user","content":"Actual raw wire"}],"stream":false}'
        operation = request_stage.envelope('business-llm',body,{'httpPath':'/v1/chat/completions'},self.profile)
        result = request_stage.run_request(self.root/'prepared',self.root,self.workspace,operation,self.preparation['manifestSha256'],runner=self.runner)
        self.assertEqual(200,result['status']); self.assertTrue(self.deleted)
        response = json.loads(base64.b64decode(result['bodyBase64']))
        self.assertEqual(hashlib.sha256(body).hexdigest(),response['actualRequestSha256'])
        self.assertEqual('unavailable-finite-job',result['headers']['x-consense-body-stream'])
        self.assertEqual('configured_native_job_profile_not_attested_loaded_model_identity',result['runtimeIdentityScope'])

    def test_another_model_cannot_launch_native_server_or_publish_a_result(self):
        body = b'{"model":"another-model","messages":[],"stream":false}'
        operation = request_stage.envelope('business-llm',body,{'httpPath':'/v1/chat/completions'},self.profile)
        with self.assertRaisesRegex(ValueError,'Job failed'):
            request_stage.run_request(self.root/'prepared',self.root,self.workspace,operation,self.preparation['manifestSha256'],runner=self.runner)
        self.assertTrue(self.deleted)
        self.assertEqual('failed',self.worker_result['status'])
        self.assertFalse((self.workspace/'gpu-operations'/operation['operationId']/'response.json').exists())


class FixedChatOperationTest(DeliveryFixture, unittest.TestCase):
    STUB_GPU_UUID = 'GPU-0395511c-46f7-c8d6-6599-8e4208eda5fe'

    def setUp(self):
        super().setUp()
        self.workspace = self.root / 'workspace'; self.workspace.mkdir()
        with socket.socket() as sock: sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        script = '''import time
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
 def do_GET(self):
  self.send_response(200); self.end_headers()
 def do_POST(self):
  self.rfile.read(int(self.headers['Content-Length']))
  self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
  self.wfile.write(b'data: first\\n\\n'); self.wfile.flush(); time.sleep(0.8)
  self.wfile.write(b'data: [DONE]\\n\\n'); self.wfile.flush()
HTTPServer(('127.0.0.1', PORT), H).serve_forever()
'''.replace('PORT', str(port))
        self.profile = {'adapter': 'consense.native-chat.v1', 'model': 'authored-only',
            'baseUrl': 'http://127.0.0.1:' + str(port), 'command': [sys.executable, '-c', script],
            'environment': {'CUDA_VISIBLE_DEVICES': '0', 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'},
            'readyPath': '/health', 'timeoutSeconds': 5, 'apiPaths': {'/v1/chat/completions': '/v1/chat/completions'}}

    def prepare(self, path='/v1/chat/completions', body=None):
        if body is None:
            body = json.dumps({'model': self.profile['model'], 'messages': [], 'stream': True}, separators=(',', ':')).encode()
        operation = request_stage.envelope('business-llm', body,
            {'httpPath': path}, self.profile)
        directory = self.workspace / 'gpu-operations' / operation['operationId']; directory.mkdir(parents=True)
        request, result, stream = (directory / name for name in ('request.json', 'response.json', 'stream.bin'))
        request_stage.save(request, operation)
        env = {'CONSENSE_FIXED_WORKSPACE': str(self.workspace), 'CONSENSE_FIXED_CONTAINER_ID': 'configured-only',
            'CONSENSE_FIXED_OPERATION_ID': operation['operationId'], 'CONSENSE_OPERATION_REQUEST': str(request),
            'CONSENSE_OPERATION_REQUEST_SHA256': request_stage.sha(request), 'CONSENSE_OPERATION_RESULT': str(result),
            'CONSENSE_OPERATION_STREAM': str(stream)}
        return operation, request, result, stream, env

    def test_fixed_stream_is_visible_before_provider_finishes_and_headers_match(self):
        operation, request, result, stream, env = self.prepare(); errors = []
        def run():
            try: chat_operation_worker.execute(request, result)
            except Exception as error: errors.append(error)
        with patch.dict(os.environ, env), patch.object(chat_operation_worker, 'verify_fixed_connection'):
            worker = threading.Thread(target=run); worker.start()
            deadline = time.monotonic() + 4
            while time.monotonic() < deadline and (not stream.exists() or not stream.stat().st_size): time.sleep(.01)
            try:
                self.assertTrue(worker.is_alive())
                self.assertEqual(b'data: first\n\n', stream.read_bytes())
                headers = json.loads((stream.parent / 'stream.headers.json').read_bytes())
                self.assertEqual({'bindingSha256': operation['bindingSha256'], 'status': 200,
                    'headers': {'content-type': 'text/event-stream'}}, headers)
            finally: worker.join(6)
        self.assertFalse(worker.is_alive()); self.assertEqual([], errors)
        self.assertEqual(b'data: first\n\ndata: [DONE]\n\n', stream.read_bytes())
        self.assertEqual(stream.read_bytes(), base64.b64decode(json.loads(result.read_bytes())['bodyBase64']))

    def test_fixed_stream_size_limit_retains_prefix_without_success_result(self):
        _, request, result, stream, env = self.prepare()
        with patch.dict(os.environ, env), patch.object(chat_operation_worker, 'verify_fixed_connection'), patch.object(chat_operation_worker, 'MAX_BODY', 14):
            with self.assertRaisesRegex(ValueError, 'exceeds byte bound'):
                chat_operation_worker.execute(request, result)
        self.assertEqual(b'data: first\n\n', stream.read_bytes())
        self.assertFalse(result.exists())

    def test_wrong_fixed_operation_identity_cannot_launch(self):
        _, request, result, stream, env = self.prepare(); env['CONSENSE_FIXED_OPERATION_ID'] = '0' * 32
        with patch.dict(os.environ, env), self.assertRaises(ValueError):
            chat_operation_worker.execute(request, result)
        self.assertFalse(result.exists()); self.assertFalse(stream.exists())

    def test_responses_completed_event_without_done_is_a_valid_terminal(self):
        self.profile['command'][-1] = self.profile['command'][-1].replace(
            "b'data: [DONE]\\n\\n'", "b'event: response.completed\\ndata: {\"type\":\"response.completed\"}\\n\\n'")
        self.profile['apiPaths']['/v1/responses'] = '/v1/responses'
        _, request, result, stream, env = self.prepare('/v1/responses')
        with patch.dict(os.environ, env), patch.object(chat_operation_worker, 'verify_fixed_connection'):
            chat_operation_worker.execute(request, result)
        self.assertIn(b'event: response.completed', stream.read_bytes())
        self.assertEqual(stream.read_bytes(), base64.b64decode(json.loads(result.read_bytes())['bodyBase64']))

    def test_unowned_provider_connection_cannot_receive_business_request(self):
        _, request, result, stream, env = self.prepare()
        with patch.dict(os.environ, env), patch.object(chat_operation_worker, 'verify_fixed_connection',
                side_effect=ValueError('fixed provider connection is not owned')):
            with self.assertRaisesRegex(ValueError, 'not owned'):
                chat_operation_worker.execute(request, result)
        self.assertFalse(result.exists()); self.assertFalse(stream.exists())

    def test_error_event_followed_by_done_is_not_saved_as_success(self):
        self.profile['command'][-1] = self.profile['command'][-1].replace(
            "b'data: first\\n\\n'", "b'event: error\\ndata: {\"type\":\"error\"}\\n\\n'")
        _, request, result, stream, env = self.prepare()
        with patch.dict(os.environ, env), patch.object(chat_operation_worker, 'verify_fixed_connection'):
            with self.assertRaisesRegex(ValueError, 'terminal marker'):
                chat_operation_worker.execute(request, result)
        self.assertIn(b'event: error', stream.read_bytes())
        self.assertFalse(result.exists())

    def test_stub_readiness_checks_task_ownership_before_sending_bearer(self):
        events = []
        connection = MagicMock()
        connection.connect.side_effect = lambda: events.append('connect')
        connection.request.side_effect = lambda *args, **kwargs: events.append('request')
        connection.getresponse.return_value.status = 200
        with patch.object(chat_operation_worker.http.client, 'HTTPConnection', return_value=connection), \
                patch.object(chat_operation_worker, 'verify_fixed_connection', side_effect=lambda *_: events.append('verify')):
            self.assertTrue(chat_operation_worker.fixed_ready(
                chat_operation_worker.urlsplit(self.profile['baseUrl']), '/v1/models', 1, 'fixture-stub-token'))
        self.assertEqual(['connect', 'verify', 'request'], events)
        connection.request.assert_called_once_with('GET', '/v1/models', headers={'Authorization': 'Bearer fixture-stub-token'})

    def test_stub_token_requires_the_independently_pinned_value(self):
        raw = b'fixture-stub-token'; metadata = SimpleNamespace(st_mode=0o100600, st_uid=10001, st_nlink=1,
            st_size=len(raw), st_dev=1, st_ino=2, st_mtime_ns=3, st_ctime_ns=4)
        absolute = MagicMock(); absolute.is_absolute.return_value = True
        with patch.object(chat_operation_worker.os, 'name', 'posix'), \
                patch.object(chat_operation_worker, 'Path', return_value=absolute), \
                patch.object(chat_operation_worker.os, 'O_NOFOLLOW', 0, create=True), \
                patch.object(chat_operation_worker.os, 'O_CLOEXEC', 0, create=True), \
                patch.object(chat_operation_worker.os, 'O_NONBLOCK', 0x1000000, create=True), \
                patch.object(chat_operation_worker.os, 'geteuid', return_value=10001, create=True), \
                patch.object(chat_operation_worker.os, 'open', return_value=7), \
                patch.object(chat_operation_worker.os, 'fstat', return_value=metadata), \
                patch.object(chat_operation_worker.os, 'read', return_value=raw), \
                patch.object(chat_operation_worker.os, 'close'):
            self.assertEqual(raw.decode(), chat_operation_worker.stub_token('/private/token', hashlib.sha256(raw).hexdigest()))
            with self.assertRaisesRegex(ValueError, 'identity changed'):
                chat_operation_worker.stub_token('/private/token', hashlib.sha256(b'other-stub-token').hexdigest())

    def test_stub_token_uses_nonblocking_open_and_rejects_nonregular_file(self):
        raw = b'fixture-stub-token'; nonblocking = 0x1000000
        metadata = SimpleNamespace(st_mode=0o100600, st_uid=10001, st_nlink=1,
            st_size=len(raw), st_dev=1, st_ino=2, st_mtime_ns=3, st_ctime_ns=4)
        absolute = MagicMock(); absolute.is_absolute.return_value = True
        with patch.object(chat_operation_worker.os, 'name', 'posix'), \
                patch.object(chat_operation_worker, 'Path', return_value=absolute), \
                patch.object(chat_operation_worker.os, 'O_NOFOLLOW', 0, create=True), \
                patch.object(chat_operation_worker.os, 'O_CLOEXEC', 0, create=True), \
                patch.object(chat_operation_worker.os, 'O_NONBLOCK', nonblocking, create=True), \
                patch.object(chat_operation_worker.os, 'geteuid', return_value=10001, create=True), \
                patch.object(chat_operation_worker.os, 'open', return_value=7) as opened, \
                patch.object(chat_operation_worker.os, 'fstat', return_value=metadata), \
                patch.object(chat_operation_worker.os, 'read', return_value=raw) as read, \
                patch.object(chat_operation_worker.os, 'close') as closed:
            pin = hashlib.sha256(raw).hexdigest()
            self.assertEqual(raw.decode(), chat_operation_worker.stub_token('/private/token', pin))
            self.assertTrue(opened.call_args.args[1] & nonblocking)
            metadata.st_mode = 0o010600
            read.reset_mock()
            with self.assertRaisesRegex(ValueError, 'invalid private stub token file'):
                chat_operation_worker.stub_token('/private/token', pin)
            read.assert_not_called()
            self.assertEqual(closed.call_count, 2)

    @unittest.skipUnless(os.name == 'posix' and hasattr(os, 'mkfifo'), 'POSIX FIFO required')
    def test_stub_token_fifo_without_writer_fails_without_waiting(self):
        import subprocess
        fifo = self.root/'token-fifo'; os.mkfifo(fifo, 0o600)
        code = ("import sys; from chat_operation_worker import stub_token; "
                "stub_token(sys.argv[1], 'a' * 64)")
        result = subprocess.run([sys.executable, '-B', '-c', code, str(fifo)],
                                cwd=str(Path(chat_operation_worker.__file__).resolve().parent),
                                capture_output=True, timeout=2)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'invalid private stub token file', result.stderr)

    def test_stub_readiness_never_sends_bearer_to_unowned_peer(self):
        connection = MagicMock()
        with patch.object(chat_operation_worker.http.client, 'HTTPConnection', return_value=connection), \
                patch.object(chat_operation_worker, 'verify_fixed_connection', side_effect=ValueError('unowned')):
            with self.assertRaisesRegex(ValueError, 'unowned'):
                chat_operation_worker.fixed_ready(
                    chat_operation_worker.urlsplit(self.profile['baseUrl']), '/v1/models', 1, 'fixture-stub-token')
        connection.request.assert_not_called()
        connection.close.assert_called_once()

    def test_stub_readiness_waits_for_owned_accept_before_bearer(self):
        connection = MagicMock(); connection.getresponse.return_value.status = 200
        events = []
        def verify(*_args):
            events.append('verify')
            if events.count('verify') == 1:
                raise ValueError('fixed provider listener or connection is not owned by the task')
        connection.request.side_effect = lambda *args, **kwargs: events.append('request')
        with patch.object(chat_operation_worker.http.client, 'HTTPConnection', return_value=connection), \
                patch.object(chat_operation_worker, 'verify_fixed_connection', side_effect=verify), \
                patch.object(chat_operation_worker.time, 'sleep'):
            self.assertTrue(chat_operation_worker.fixed_ready(
                chat_operation_worker.urlsplit(self.profile['baseUrl']), '/v1/models', 1, 'fixture-stub-token'))
        self.assertEqual(['verify', 'verify', 'request'], events)

    def test_fixed_readiness_timeout_is_retryable_without_sending(self):
        connection = MagicMock()
        with patch.object(chat_operation_worker.http.client, 'HTTPConnection', return_value=connection), \
                patch.object(chat_operation_worker, 'verify_fixed_connection',
                             side_effect=ValueError('fixed provider listener or connection is not owned by the task')), \
                patch.object(chat_operation_worker.time, 'monotonic', side_effect=[0, 1]):
            with self.assertRaises(TimeoutError):
                chat_operation_worker.fixed_ready(
                    chat_operation_worker.urlsplit(self.profile['baseUrl']), '/v1/models', 1, 'fixture-stub-token')
        connection.request.assert_not_called()

    def test_fixed_native_readiness_keeps_unauthenticated_wire_after_ownership_check(self):
        connection = MagicMock(); connection.getresponse.return_value.status = 200
        with patch.object(chat_operation_worker.http.client, 'HTTPConnection', return_value=connection), \
                patch.object(chat_operation_worker, 'verify_fixed_connection') as verify:
            self.assertTrue(chat_operation_worker.fixed_ready(
                chat_operation_worker.urlsplit(self.profile['baseUrl']), '/health', 1))
        verify.assert_called_once()
        connection.request.assert_called_once_with('GET', '/health', headers={})

    def test_fixed_connection_checks_cgroup_identity_and_listener(self):
        tcp = ('header\n0: 0100007F:3039 00000000:0000 0A 0 0 0 0 0 55\n'
               '1: 0100007F:3039 0100007F:A8CA 01 0 0 0 0 0 56\n')
        extra = {'tcp': '', 'tcp6': '', 'members': '123'}
        identities = {'/sys/fs/cgroup/x': (7, 11), '/delegated/x': (7, 11),
                      '/delegated/other': (7, 12), '/delegated/other-device': (8, 11)}
        class FakePath(PurePosixPath):
            def resolve(self): return self
            def samefile(self, other):
                left, right = identities[str(self)], identities[str(other)]
                return os.path.samestat(SimpleNamespace(st_dev=left[0], st_ino=left[1]),
                                        SimpleNamespace(st_dev=right[0], st_ino=right[1]))
            def rglob(self, _pattern): return []
            def iterdir(self): return [FakePath('/proc/123/fd/7'), FakePath('/proc/123/fd/8')]
            def read_text(self):
                if str(self) == '/proc/self/cgroup': return '0::/x/task\n'
                if str(self) == '/sys/fs/cgroup/x/task/cgroup.procs': return extra['members'] + '\n'
                if str(self) == '/proc/net/tcp': return tcp + extra['tcp']
                if str(self) == '/proc/net/tcp6': return 'header\n' + extra['tcp6']
                raise AssertionError(str(self))
        connection = MagicMock()
        connection.sock.getsockname.return_value = ('127.0.0.1', 43210)
        connection.sock.getpeername.return_value = ('127.0.0.1', 12345)
        with patch.object(chat_operation_worker.os, 'name', 'posix'), \
                patch.object(chat_operation_worker, 'Path', FakePath), \
                patch.object(chat_operation_worker.os, 'getpid', return_value=123), \
                patch.object(chat_operation_worker.os, 'readlink', side_effect=lambda path:
                             'socket:[55]' if str(path).endswith('/7') else 'socket:[56]'), \
                patch.dict(os.environ, {'CONSENSE_FIXED_CGROUP_ROOT': '/sys/fs/cgroup/x'}):
            chat_operation_worker.verify_fixed_connection(connection, 12345)
            with patch.dict(os.environ, {'CONSENSE_FIXED_CGROUP_ROOT': '/delegated/x'}):
                chat_operation_worker.verify_fixed_connection(connection, 12345)
                extra['members'] = '456'
                with self.assertRaisesRegex(ValueError, 'outside its assigned cgroup'):
                    chat_operation_worker.verify_fixed_connection(connection, 12345)
                extra['members'] = '123'
            for other in ('/delegated/other', '/delegated/other-device'):
                with patch.dict(os.environ, {'CONSENSE_FIXED_CGROUP_ROOT': other}):
                    with self.assertRaisesRegex(ValueError, 'outside its assigned cgroup'):
                        chat_operation_worker.verify_fixed_connection(connection, 12345)
            for protocol, line in (('tcp', '2: 00000000:3039 00000000:0000 0A 0 0 0 0 0 57\n'),
                                   ('tcp6', '2: 00000000000000000000000000000000:3039 0:0 0A 0 0 0 0 0 57\n')):
                extra[protocol] = line
                with self.assertRaisesRegex(ValueError, 'IPv4 loopback only'):
                    chat_operation_worker.verify_fixed_connection(connection, 12345)
                extra[protocol] = ''

    def test_stub_sends_bearer_to_verified_provider_and_preserves_stream(self):
        self.profile.update(providerKind='cpu-protocol-stub', stubTokenFile='/private/stub-token',
                            stubTokenSha256=hashlib.sha256(b'fixture-stub-token').hexdigest(), readyPath='/v1/models')
        self.profile['environment']['CUDA_VISIBLE_DEVICES'] = ''
        _, request, result, stream, env = self.prepare(); env['UNRELATED_SECRET'] = 'do-not-propagate'
        env['CONSENSE_FIXED_GPU_UUID'] = self.STUB_GPU_UUID
        connection = MagicMock(); response = MagicMock()
        response.status = 200; response.length = None
        response.getheader.return_value = 'text/event-stream'
        response.read1.side_effect = [b'data: [DONE]\n\n', b'']
        connection.getresponse.return_value = response
        events = []
        connection.connect.side_effect = lambda: events.append('connect')
        connection.request.side_effect = lambda *args, **kwargs: events.append('request')
        process = MagicMock(); process.poll.return_value = None
        with patch.dict(os.environ, env), patch.object(chat_operation_worker, 'stub_token', return_value='fixture-stub-token'), \
                patch.object(chat_operation_worker, 'fixed_ready', side_effect=[TimeoutError(), True]) as ready, \
                patch.object(chat_operation_worker.subprocess, 'Popen', return_value=process) as launch, \
                patch.object(chat_operation_worker.http.client, 'HTTPConnection', return_value=connection), \
                patch.object(chat_operation_worker, 'verify_fixed_connection', side_effect=lambda *_: events.append('verify')), \
                patch.object(chat_operation_worker, 'nullcontext') as cuda:
            cuda.return_value.__enter__.side_effect = lambda: events.append('context-enter')
            cuda.return_value.__exit__.side_effect = lambda *_: events.append('context-exit')
            chat_operation_worker.execute(request, result)
        cuda.assert_called_once_with()
        self.assertEqual(['context-enter', 'connect', 'verify', 'request', 'context-exit'], events)
        self.assertEqual('fixture-stub-token', launch.call_args.kwargs['env']['CONSENSE_QWEN_STUB_TOKEN'])
        self.assertNotIn('UNRELATED_SECRET', launch.call_args.kwargs['env'])
        self.assertNotIn('CONSENSE_FIXED_GPU_UUID', launch.call_args.kwargs['env'])
        self.assertEqual('fixture-stub-token', ready.call_args.args[3])
        self.assertEqual(2, ready.call_count)
        self.assertEqual('Bearer fixture-stub-token', connection.request.call_args.args[3]['Authorization'])
        self.assertEqual(b'data: [DONE]\n\n', stream.read_bytes())
        self.assertNotIn(b'fixture-stub-token', result.read_bytes())

    def test_stub_token_in_profile_is_rejected_before_launch(self):
        self.profile.update(providerKind='cpu-protocol-stub', stubTokenFile='/private/stub-token',
                            stubTokenSha256=hashlib.sha256(b'fixture-stub-token').hexdigest(), readyPath='/v1/models')
        self.profile['environment'].update(CUDA_VISIBLE_DEVICES='', CONSENSE_QWEN_STUB_TOKEN='do-not-persist')
        _, request, result, _, env = self.prepare()
        with patch.dict(os.environ, env), patch.object(chat_operation_worker, 'stub_token', return_value='fixture-stub-token'), \
                patch.object(chat_operation_worker.subprocess, 'Popen') as launch:
            with self.assertRaisesRegex(ValueError, 'explicit offline CUDA'):
                chat_operation_worker.execute(request, result)
        launch.assert_not_called()

    def test_stub_rejects_python_and_loader_injection_before_launch(self):
        self.profile.update(providerKind='cpu-protocol-stub', stubTokenFile='/private/stub-token',
                            stubTokenSha256=hashlib.sha256(b'fixture-stub-token').hexdigest(), readyPath='/v1/models')
        self.profile['environment']['CUDA_VISIBLE_DEVICES'] = ''
        for key in ('PYTHONPATH', 'LD_PRELOAD'):
            self.profile['environment'][key] = '/untrusted'
            _, request, result, _, env = self.prepare()
            with patch.dict(os.environ, env), patch.object(chat_operation_worker, 'stub_token', return_value='fixture-stub-token'), \
                    patch.object(chat_operation_worker.subprocess, 'Popen') as launch:
                with self.assertRaisesRegex(ValueError, 'explicit offline CUDA'):
                    chat_operation_worker.execute(request, result)
            launch.assert_not_called()
            del self.profile['environment'][key]

    def test_actual_protocol_stub_authenticates_and_streams(self):
        self.profile.update(providerKind='cpu-protocol-stub', stubTokenFile='/private/stub-token',
                            stubTokenSha256=hashlib.sha256(b'fixture-stub-token').hexdigest(),
                            readyPath='/v1/models', model='qwen3.8-27b')
        self.profile['environment']['CUDA_VISIBLE_DEVICES'] = ''
        if os.name == 'nt': self.profile['environment']['SystemRoot'] = os.environ['SystemRoot']
        self.profile['command'] = [sys.executable, str(Path(chat_operation_worker.__file__).with_name('qwen_protocol_stub.py')),
                                   '--port', str(chat_operation_worker.urlsplit(self.profile['baseUrl']).port)]
        body = json.dumps({'model':'qwen3.8-27b', 'messages':[{'role':'user','content':'synthetic'}],
                           'stream':True, 'enable_thinking':False, 'max_tokens':32}).encode()
        _, request, result, stream, env = self.prepare(body=body)
        env['CONSENSE_FIXED_GPU_UUID'] = self.STUB_GPU_UUID
        with patch.dict(os.environ, env), patch.object(chat_operation_worker, 'stub_token', return_value='fixture-stub-token'), \
                patch.object(chat_operation_worker, 'verify_fixed_connection'), \
                patch.object(chat_operation_worker, 'nullcontext') as cuda:
            chat_operation_worker.execute(request, result)
        cuda.assert_called_once_with()
        cuda.return_value.__enter__.assert_called_once_with()
        cuda.return_value.__exit__.assert_called_once_with(None, None, None)
        self.assertIn(b'data: [DONE]', stream.read_bytes())
        self.assertEqual(200, json.loads(result.read_bytes())['status'])
        self.assertNotIn(b'fixture-stub-token', result.read_bytes())


if __name__ == '__main__': unittest.main()
