"""Synthetic pre-admission identity contract; no GPU or real services."""
import base64
import hashlib
import http.client
import http.server
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import MagicMock, patch

from fixed_gpu_client import Client, OperationError
from fixed_gpu_provider import owned_fixed_provider
from fixed_gpu_worker import Worker, make_server
from fixed_model_session import ModelSession
from request_stage import encoded

PROFILE = 'd' * 64
SESSION = {'requestId':'1'*32, 'sourceScopeSha256':'2'*64, 'modelKind':'embedding',
           'modelIdentity':{'id':'fixture'}, 'runtime':{'device':'cuda'}, 'inferenceCap':4,
           'deadlineBudgetMs':1000, 'maxBatches':2, 'maxTotalBytes':4096}


class CallersTest(unittest.TestCase):
    def test_every_client_admission_sends_expected_profile_and_mode(self):
        for kind in ('compute', 'business', 'stream', 'model-session'):
            with self.subTest(kind=kind):
                client = Client('http://127.0.0.1:1234', 'fixture',
                                {'document-embedding':PROFILE, 'business-llm':PROFILE})
                submitted = []
                def call(method, path, timeout, body=None, cancelled=None):
                    submitted.append((method,path,body))
                    raise ValueError('synthetic old gateway rejects fields')
                client._call = call
                with self.assertRaises(OperationError) as caught:
                    if kind == 'compute': client.compute('document-embedding', {'id':'fixture'}, {'kind':'dimension'}, 2)
                    elif kind == 'business': client.call_business('/v1/responses', b'{}', 2)
                    elif kind == 'stream': client.start_business('/v1/responses', b'{}', 2)
                    else: ModelSession.start(client, 'document-embedding', SESSION, time.monotonic()+2)
                self.assertEqual(len(submitted), 1)
                self.assertEqual(submitted[0][:2], ('POST', '/operations'))
                self.assertEqual(submitted[0][2]['expectedProfileSha256'], PROFILE)
                self.assertEqual(submitted[0][2]['expectedMode'], 'model-session' if kind == 'model-session' else 'job')
                self.assertIsNone(caught.exception.operation)
                self.assertEqual(caught.exception.exit_receipt['status'], 'release_unknown')

    def test_ocr_client_admission_sends_fixed_gateway_pin_and_session_mode(self):
        captured = []
        class Connection:
            sock = None
            def __init__(self, *args): pass
            def request(self, method, path, *, body, headers):
                captured.append((method,path,json.loads(body)))
                raise ValueError('synthetic old gateway rejects fields')
            def close(self): pass
        identity = {'parserVersion':'fixture'}
        task = {'parser':'paddleocr-vl', 'profileSha256':hashlib.sha256(encoded(identity)).hexdigest()}
        provider = {'identity':identity, 'taskBinding':task}
        boot = {'stage':'paddle', 'gatewayProfileSha256':PROFILE, 'readyTimeoutSeconds':2, 'cleanupTimeoutSeconds':2}
        with tempfile.TemporaryDirectory() as root, \
             patch('fixed_gpu_provider.configuration', return_value=(boot,Path(root)/'socket')), \
             patch('fixed_gpu_provider._token', return_value='fixture'), \
             patch('fixed_gpu_provider.UnixConnection', Connection):
            with self.assertRaises(ValueError):
                with owned_fixed_provider(provider, 2, Path(root)/'jobs'/'unique'/'paddle'):
                    self.fail('unexpected admission')
        self.assertEqual(len(captured), 1)
        self.assertEqual(captured[0][:2], ('POST','/operations'))
        self.assertEqual(captured[0][2]['expectedProfileSha256'], PROFILE)
        self.assertEqual(captured[0][2]['expectedMode'], 'session')

    def test_new_client_to_old_gateway_fails_closed_without_replay(self):
        calls = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                value = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                calls.append(value)
                response = b'old gateway rejects unknown admission fields'
                self.send_response(400 if set(value) != {'stage','binding','bodyBase64'} else 202)
                self.send_header('Content-Length', str(len(response)))
                self.end_headers(); self.wfile.write(response)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            client=Client('http://127.0.0.1:%d' % server.server_port, 'fixture', {'business-llm':PROFILE})
            with self.assertRaises(OperationError) as caught:
                client.start_business('/v1/responses', b'{}', 2)
            self.assertIsNone(caught.exception.operation)
            self.assertEqual('release_unknown', caught.exception.exit_receipt['status'])
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0]['expectedProfileSha256'], PROFILE)
        finally:
            server.shutdown(); server.server_close(); thread.join()


class WorkerTest(unittest.TestCase):
    def worker(self, root, stage, mode, inner):
        path = Path(root); executable = path/'fixture-executable'; executable.write_bytes(b'fixture')
        asset = {'path':str(executable), 'byteCount':executable.stat().st_size,
                 'sha256':hashlib.sha256(executable.read_bytes()).hexdigest()}
        profile = {'mode':mode, 'profile':inner, 'timeoutSeconds':2, 'gpuRequired':True,
                   'boot':{'command':[str(executable)], 'cwd':root, 'cgroupRoot':root,
                           'env':{}, 'runtimeAssets':[asset]}}
        worker = Worker({'workspace':root, 'stages':{stage:profile}, 'cgroupRoot':root,
                         'nvidiaSmi':str(executable), 'gpuUuid':'GPU-fixture'}, 'fixture')
        worker._validate_profile(profile)
        return worker, executable

    @staticmethod
    def request(stage, mode, inner):
        if mode == 'model-session': binding, raw = dict(SESSION), b'{}'
        elif mode == 'session':
            binding = {'parser':'paddleocr-vl' if stage == 'paddle' else 'mineru', 'inputSha256':'a'*64,
                       'inputByteCount':1, 'originalSourceSha256':'b'*64, 'physicalPageNo':1,
                       'profileSha256':hashlib.sha256(encoded(inner['nativeIdentity'])).hexdigest()}
            raw = b'{}'
        elif stage == 'business-llm': binding, raw = {'httpPath':'/v1/responses'}, b'{}'
        else: binding, raw = {'modelIdentity':{'id':'fixture'}, 'runtime':{'device':'cuda'}, 'inferenceCap':4}, b'{"kind":"dimension"}'
        return {'stage':stage, 'binding':binding, 'bodyBase64':base64.b64encode(raw).decode(),
                'expectedProfileSha256':hashlib.sha256(encoded(inner)).hexdigest(), 'expectedMode':mode}

    def invoke(self, worker, executable, request, *, accepted):
        real_open = os.open
        def fixture_open(target, flags, *args, **kwargs):
            if Path(target) == worker.workspace and flags == os.O_RDONLY:
                return real_open(executable, flags)
            return real_open(target, flags, *args, **kwargs)
        with patch('fixed_gpu_worker.os.open', side_effect=fixture_open), \
             patch('fixed_gpu_worker.os.fsync'), patch('fixed_gpu_worker.threading.Thread') as thread:
            if accepted:
                self.assertEqual(worker.start(request)['status'], 'starting')
                thread.return_value.start.assert_called_once()
                self.assertTrue(worker.marker.exists())
            else:
                with self.assertRaises(ValueError): worker.start(request)
                thread.assert_not_called()
                self.assertFalse(worker.marker.exists())
                self.assertEqual([], list(worker.operations.iterdir()))

    def test_correct_mode_and_profile_admit_existing_four_kinds(self):
        for stage, mode, inner in (
            ('document-embedding','job',{'adapter':'consense.model-compute.v1'}),
            ('query-embedding-rerank','model-session',{'adapter':'consense.model-session.v1'}),
            ('business-llm','job',{'adapter':'consense.native-chat.v1'}),
            ('paddle','session',{'nativeIdentity':{'id':'fixture'}}),
            ('mineru','session',{'nativeIdentity':{'id':'fixture'}})):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as root:
                worker, executable = self.worker(root,stage,mode,inner)
                self.invoke(worker,executable,self.request(stage,mode,inner),accepted=True)

    def test_missing_wrong_or_conflicting_profile_and_mode_never_claim_owner(self):
        for stage in ('document-embedding','query-embedding-rerank','business-llm','paddle','mineru'):
            mode = 'session' if stage in ('paddle','mineru') else 'job'
            inner = ({'nativeIdentity':{'id':'fixture'}} if mode == 'session' else
                     {'adapter':'consense.native-chat.v1' if stage == 'business-llm' else 'consense.model-compute.v1'})
            for mismatch in ('legacy','wrong-sha','invalid-sha','wrong-mode'):
                with self.subTest(stage=stage, mismatch=mismatch), tempfile.TemporaryDirectory() as root:
                    worker, executable = self.worker(root,stage,mode,inner)
                    request = self.request(stage,mode,inner)
                    if mismatch == 'legacy': del request['expectedProfileSha256']; del request['expectedMode']
                    elif mismatch == 'wrong-sha': request['expectedProfileSha256'] = 'e'*64
                    elif mismatch == 'invalid-sha': request['expectedProfileSha256'] = 'bad'
                    else: request['expectedMode'] = 'job' if mode == 'session' else 'model-session'
                    self.invoke(worker,executable,request,accepted=False)

    def test_conflicting_known_adapter_mode_and_stage_never_claim_owner(self):
        for stage, mode, adapter in (
            ('document-embedding','job','consense.model-session.v1'),
            ('query-embedding-rerank','job','consense.model-session.v1'),
            ('document-embedding','model-session','consense.model-compute.v1'),
            ('paddle','session','consense.model-compute.v1'),
            ('mineru','session','consense.model-compute.v1'),
            ('business-llm','job','consense.model-compute.v1'),
            ('document-embedding','job','consense.native-chat.v1')):
            with self.subTest(stage=stage, mode=mode, adapter=adapter), tempfile.TemporaryDirectory() as root:
                inner = {'adapter':adapter, 'nativeIdentity':{'id':'fixture'}}
                worker, executable = self.worker(root,stage,mode,inner)
                self.invoke(worker,executable,self.request(stage,mode,inner),accepted=False)

    def test_old_client_to_new_gateway_rejected_before_owner(self):
        with tempfile.TemporaryDirectory() as root:
            stage='document-embedding'; mode='job'; inner={'adapter':'consense.model-compute.v1'}
            worker, _ = self.worker(root,stage,mode,inner)
            server=make_server(worker,('127.0.0.1',0))
            thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            connection=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=2)
            request=self.request(stage,mode,inner)
            del request['expectedProfileSha256']; del request['expectedMode']
            try:
                connection.request('POST','/operations',body=encoded(request),
                                   headers={'Authorization':'Bearer fixture','Content-Type':'application/json'})
                response=connection.getresponse(); self.assertEqual(response.status,400); response.read()
                self.assertFalse(worker.marker.exists()); self.assertEqual([],list(worker.operations.iterdir()))
            finally:
                connection.close(); server.shutdown(); server.server_close(); thread.join()

    def test_profile_and_mode_pin_do_not_claim_boot_or_execution_identity(self):
        for variant in ('boot-a', 'boot-b'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as root:
                stage='document-embedding'; mode='job'; inner={'adapter':'consense.model-compute.v1'}
                worker, executable = self.worker(root,stage,mode,inner)
                worker.config['stages'][stage]['boot']['env']['FIXTURE_VARIANT']=variant
                self.invoke(worker,executable,self.request(stage,mode,inner),accepted=True)

    def test_admitted_profile_is_isolated_from_subsequent_config_mutation(self):
        with tempfile.TemporaryDirectory() as root:
            stage='document-embedding'; mode='job'; inner={'adapter':'consense.model-compute.v1'}
            worker, executable = self.worker(root,stage,mode,inner)
            original_validate = worker._validate_profile
            def validate_then_mutate(profile):
                result=original_validate(profile)
                worker.config['stages'][stage]['profile']['adapter']='changed-after-check'
                worker.config['stages'][stage]['mode']='session'
                return result
            real_open = os.open
            def fixture_open(target, flags, *args, **kwargs):
                if Path(target) == worker.workspace and flags == os.O_RDONLY:
                    return real_open(executable,flags)
                return real_open(target,flags,*args,**kwargs)
            with patch.object(worker,'_validate_profile',side_effect=validate_then_mutate), \
                 patch('fixed_gpu_worker.os.open',side_effect=fixture_open), \
                 patch('fixed_gpu_worker.os.fsync'), patch('fixed_gpu_worker.threading.Thread') as thread:
                worker.start(self.request(stage,mode,inner))
                actual=thread.call_args.kwargs['args'][2]
            self.assertEqual(actual['mode'],'job')
            self.assertEqual(actual['profile']['adapter'],'consense.model-compute.v1')


if __name__ == '__main__': unittest.main()
