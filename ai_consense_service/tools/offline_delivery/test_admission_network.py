"""Real HTTP to the candidate Worker with synthetic execution; no GPU or models."""
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from fixed_gpu_client import Client, OperationError
from fixed_gpu_provider import owned_fixed_provider
from fixed_gpu_worker import make_server
from fixed_model_session import ModelSession
from request_stage import encoded
import test_admission_identity as fixtures


class AdmissionNetworkTest(unittest.TestCase):
    def scenario(self, kind, *, mismatch=None):
        stage = {'compute':'document-embedding', 'model-session':'document-embedding',
                 'business':'business-llm', 'stream':'business-llm', 'ocr':'paddle'}[kind]
        requested_mode = {'compute':'job', 'model-session':'model-session', 'business':'job',
                          'stream':'job', 'ocr':'session'}[kind]
        configured_mode = requested_mode
        if mismatch == 'mode': configured_mode = 'job' if requested_mode != 'job' else 'model-session'
        identity = {'parserVersion':'fixture'}
        inner = ({'nativeIdentity':identity} if kind == 'ocr' else
                 {'adapter':'consense.model-session.v1' if configured_mode == 'model-session'
                  else 'consense.native-chat.v1' if stage == 'business-llm'
                  else 'consense.model-compute.v1'})
        pin = hashlib.sha256(encoded(inner)).hexdigest()
        if mismatch == 'profile': pin = 'e'*64
        with tempfile.TemporaryDirectory() as root:
            worker, executable = fixtures.WorkerTest().worker(root, stage, configured_mode, inner)
            invocations, arrivals, failures, completed = [], [], [], threading.Event()
            def fake_execute(directory, operation, profile, cancellation, finished):
                try:
                    invocations.append((operation['stage'], profile['mode'], operation['profileSha256']))
                    if profile['mode'] in ('session', 'model-session'):
                        worker._set(directory,status='ready')
                    else:
                        body = b'{"dimension":2}' if stage != 'business-llm' else b'{"fixture":true}'
                        response = {'contract':'consense.gpu-operation-response.v1',
                                    'bindingSha256':operation['bindingSha256'], 'status':200,
                                    'headers':{'content-type':'application/json'},
                                    'bodyBase64':base64.b64encode(body).decode()}
                        wire = encoded(response); (directory/'response.json').write_bytes(wire)
                        worker._set(directory,status='completed',responseSha256=hashlib.sha256(wire).hexdigest(),
                                    release={'status':'released','cgroupPopulated':0,'observedGpuPids':[123]})
                finally: completed.set()
            worker._execute = fake_execute
            original_start = worker.start
            def start_waited(request):
                arrivals.append(request)
                try: result=original_start(request)
                except Exception as error:
                    failures.append((type(error).__name__,str(error)))
                    raise
                if not completed.wait(2): raise TimeoutError('fake executor failed to finish')
                return result
            worker.start = start_waited
            server = make_server(worker, ('127.0.0.1',0))
            thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            real_open=os.open
            def fixture_open(target, flags, *args, **kwargs):
                if Path(target) == worker.workspace and flags == os.O_RDONLY:
                    return real_open(executable,flags)
                return real_open(target,flags,*args,**kwargs)
            url='http://127.0.0.1:%d' % server.server_port
            try:
                with patch('fixed_gpu_worker.os.open',side_effect=fixture_open), patch('fixed_gpu_worker.os.fsync'):
                    if kind == 'ocr':
                        task={'parser':'paddleocr-vl','inputSha256':'a'*64,'inputByteCount':1,
                              'originalSourceSha256':'b'*64,'physicalPageNo':1,
                              'profileSha256':hashlib.sha256(encoded(identity)).hexdigest()}
                        provider={'identity':identity,'taskBinding':task}
                        boot={'stage':'paddle','gatewayProfileSha256':pin,
                              'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}
                        def connection(_path,timeout):
                            return http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=timeout)
                        with patch('fixed_gpu_provider.configuration',return_value=(boot,Path(root)/'socket')), \
                             patch('fixed_gpu_provider._token',return_value='fixture'), \
                             patch('fixed_gpu_provider.UnixConnection',side_effect=connection):
                            if mismatch:
                                with self.assertRaises(Exception):
                                    with owned_fixed_provider(provider,2,Path(root)/'jobs'/'unique'/'paddle'): pass
                            else:
                                with self.assertRaisesRegex(RuntimeError,'synthetic stop'):
                                    with owned_fixed_provider(provider,2,Path(root)/'jobs'/'unique'/'paddle') as lifecycle:
                                        self.assertEqual(lifecycle['wireReady']['status'],'wire_ready_model_readiness_unverified')
                                        raise RuntimeError('synthetic stop')
                    else:
                        client=Client(url,'fixture',{stage:pin})
                        def invoke():
                            if kind == 'compute': return client.compute(stage,{'modelIdentity':{'id':'fixture'},
                                'runtime':{'device':'cuda'},'inferenceCap':4},{'kind':'dimension'},2)
                            if kind == 'model-session': return ModelSession.start(client,stage,fixtures.SESSION,time.monotonic()+2)
                            if kind == 'business': return client.call_business('/v1/responses',b'{}',2)
                            return client.start_business('/v1/responses',b'{}',2)
                        if mismatch:
                            with self.assertRaises(OperationError) as caught: invoke()
                            self.assertIsNone(caught.exception.operation)
                            self.assertEqual(caught.exception.exit_receipt['status'],'release_unknown')
                        else:
                            result=invoke()
                            if kind == 'compute': self.assertEqual(result['dimension'],2)
                            elif kind == 'business': self.assertEqual(result[0],200)
                            elif kind == 'model-session': self.assertEqual(result.stage,stage)
                            else: self.assertEqual(result.expected['profileSha256'],pin)
                self.assertEqual(len(arrivals),1)
                self.assertEqual(arrivals[0]['stage'],stage)
                self.assertEqual(arrivals[0]['expectedProfileSha256'],pin)
                self.assertEqual(arrivals[0]['expectedMode'],requested_mode)
                if mismatch:
                    self.assertEqual(failures,[('ValueError','expected operation profile or mode differs')])
                    self.assertEqual(invocations,[])
                    self.assertFalse(worker.marker.exists())
                    self.assertEqual([],list(worker.operations.iterdir()))
                else:
                    self.assertEqual(invocations,[(stage,requested_mode,pin)])
                    self.assertTrue(worker.marker.exists())
            finally:
                server.shutdown(); server.server_close(); thread.join(2)
                self.assertFalse(thread.is_alive())

    def test_matching_callers_reach_worker_with_their_pinned_profile_and_mode(self):
        for kind in ('compute','model-session','business','stream','ocr'):
            with self.subTest(kind=kind): self.scenario(kind)

    def test_wrong_profile_is_rejected_before_owner_or_execution(self):
        for kind in ('compute','model-session','business','stream','ocr'):
            with self.subTest(kind=kind): self.scenario(kind,mismatch='profile')

    def test_wrong_mode_is_rejected_before_owner_or_execution(self):
        for kind in ('compute','model-session','business','stream','ocr'):
            with self.subTest(kind=kind): self.scenario(kind,mismatch='mode')


if __name__ == '__main__': unittest.main()
