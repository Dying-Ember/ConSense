"""Fixed GPU session contract through the public lifecycle and HTTP boundary."""
import uuid
import hashlib
import base64
from http.server import BaseHTTPRequestHandler
import json
import os
import socket
import socketserver
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'vetting_eval'))
from artifact_store import ArtifactStore
from native_provider_lifecycle import owned_native_provider
from request_stage import encoded

GATEWAY_PROFILE_SHA = 'd' * 64


def status_identity(admission):
    return {'providerEvidenceProtocol':'dispatch-v1', 'bodySha256': hashlib.sha256(base64.b64decode(admission['bodyBase64'])).hexdigest(),
            'sourceSha256': hashlib.sha256(encoded(admission['binding'])).hexdigest(),
            'profileSha256': GATEWAY_PROFILE_SHA}


class UnixServer(socketserver.ThreadingMixIn, getattr(socketserver, "UnixStreamServer", socketserver.TCPServer)):
    daemon_threads = True
    def __init__(self, handler):
        self.temporary = tempfile.TemporaryDirectory()
        self.socket_path = str(Path(self.temporary.name)/'gateway.sock')
        super().__init__(self.socket_path, handler)
        os.chmod(self.socket_path, 0o600)
    def server_close(self):
        super().server_close(); self.temporary.cleanup()


@unittest.skipIf(os.name == 'nt', 'fixed GPU uses POSIX private token permissions')
class FixedProviderTests(unittest.TestCase):
    def test_unix_socket_with_shared_permissions_is_rejected(self):
        from fixed_gpu_provider import UnixConnection
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self): self.send_error(500)
        server=UnixServer(Handler)
        try:
            os.chmod(server.socket_path,0o666)
            with self.assertRaisesRegex(ValueError,'socket must be private'):
                UnixConnection(server.socket_path,1).connect()
        finally: server.server_close()

    def test_proxy_cancellation_interrupts_inflight_request(self):
        from fixed_gpu_provider import proxy_request
        from complete_ocr import HttpCaptureFailure
        received=threading.Event(); cancelled=threading.Event(); finish=threading.Event()
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self): received.set(); finish.wait(3)
        server=UnixServer(Handler); worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
        trigger=threading.Thread(target=lambda:(received.wait(2),cancelled.set()),daemon=True); trigger.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                token=Path(directory)/'token'; token.write_text('local-secret'); os.chmod(token,0o600)
                provider={'baseUrl':'http://127.0.0.1:28772/v1',
                    'ownedFixed':{'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':'mineru','socketPath':server.socket_path,'tokenFile':str(token),
                                  'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}}
                start=time.monotonic()
                with self.assertRaises(HttpCaptureFailure) as caught:
                    proxy_request(provider,{'mode':'owned_fixed_gpu','operationId':'a'*32,'providerEvidenceProtocol':'dispatch-v1'},
                        provider['baseUrl']+'/parse/jobs/job_a',timeout=2,cancelled=cancelled.is_set,dispatch_id=uuid.uuid4().hex)
                self.assertLess(time.monotonic()-start,1); self.assertFalse(caught.exception.complete)
        finally:
            finish.set(); server.shutdown(); server.server_close(); worker.join(); trigger.join()

    def test_complete_ocr_uses_bound_uds_proxy_for_both_native_apis(self):
        from complete_ocr import recognize_complete_page
        import test_complete_ocr as fixtures
        fixture=fixtures.CompleteOcrTests(); raw=fixture.image(); requests, native=fixture.transport(raw)
        operations={}; calls=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def reply(self,status,body):
                self.send_response(status); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
            def dispatch(self):
                import re
                self.assert_token = self.headers.get('Authorization')
                length=int(self.headers.get('Content-Length','0')); data=self.rfile.read(length)
                if self.path == '/operations':
                    body=json.loads(data); operation=f'{len(operations)+1:032x}'; operations[operation]=body
                    return self.reply(202,json.dumps({'operationId':operation,'status':'starting'}).encode())
                match=re.fullmatch(r'/operations/([a-f0-9]{32})(/finish|/provider/.*)?',self.path)
                if not match: return self.reply(404,b'{}')
                operation, suffix=match.groups(); row=operations[operation]
                if suffix == '/finish':
                    row['transaction']=json.loads(data)['transaction']
                    return self.reply(202,json.dumps({'operationId':operation,'status':'finishing'}).encode())
                if not suffix:
                    transaction=row.get('transaction'); state={**status_identity(row),'operationId':operation,'stage':row['stage'],
                        'bindingSha256':'c'*64,'status':'completed' if transaction else 'ready'}
                    if transaction:
                        state['transactionSha256']=hashlib.sha256(json.dumps(transaction,sort_keys=True,ensure_ascii=False,
                            separators=(',',':'),allow_nan=False).encode()).hexdigest()
                        state['release']={'status':'released','cgroupPopulated':0,'observedGpuPids':[42]}
                    return self.reply(200,json.dumps(state).encode())
                path=suffix[len('/provider'):]; stage=row['stage']
                source=('http://127.0.0.1:28771' if stage == 'paddle' else 'http://127.0.0.1:28772')+path
                body=json.loads(data) if data and self.headers.get('Content-Type') == 'application/json' else data if data else None
                calls.append((operation,self.command,path,self.assert_token))
                return self.reply(200,native(source,body=body,method=self.command))
            do_GET=do_POST=do_PUT=dispatch
        server=UnixServer(Handler); worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory); token=root/'token'; token.write_text('local-secret'); os.chmod(token,0o600)
                config=fixture.config(); config['timeoutSeconds']=10
                for parser,stage,port in (('paddleocr-vl','paddle',28771),('mineru','mineru',28772)):
                    config[parser]['baseUrl']=f'http://127.0.0.1:{port}'+('/v1' if stage == 'mineru' else '')
                    config[parser]['ownedFixed']={'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':stage,'socketPath':server.socket_path,'tokenFile':str(token),
                                                 'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}
                result=recognize_complete_page(raw,config,ArtifactStore(root/'cas'),source_context=fixture.source_context(raw),
                    request=lambda *args,**kwargs: self.fail('fixed mode must never call direct TCP'))
                self.assertEqual(len(requests),7); self.assertTrue(all(row[3] == 'Bearer local-secret' for row in calls))
                self.assertEqual([row[2] for row in calls],['/layout-parsing','/v1/uploads',
                    '/v1/uploads/upload_authored/content','/v1/uploads/upload_authored/complete',
                    '/v1/parse/jobs','/v1/parse/jobs/job_authored','/v1/files/file-authored/content'])
                self.assertEqual([x['taskBinding']['originalSourceSha256'] for x in result['provenance']['providerLifecycles']],['c'*64]*2)
        finally:
            server.shutdown(); server.server_close(); worker.join()

    def test_proxy_preserves_binary_and_upstream_failure_without_direct_tcp(self):
        from fixed_gpu_provider import proxy_request
        from complete_ocr import HttpCaptureFailure
        calls=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_PUT(self):
                raw=self.rfile.read(int(self.headers['Content-Length']))
                calls.append((self.command,self.path,self.headers.get('Authorization'),raw))
                result=b'\x00ZIP\xff'; self.send_response(200); self.send_header('Content-Length',str(len(result)))
                self.end_headers(); self.wfile.write(result)
            def do_POST(self):
                raw=self.rfile.read(int(self.headers['Content-Length']))
                calls.append((self.command,self.path,self.headers.get('Authorization'),raw))
                result=b'{"detail":{"kind":"native_capture_failed"}}'; self.send_response(503)
                if self.path.endswith('/gateway-error'): self.send_header('X-Consense-Gateway-Error','1')
                self.send_header('Content-Length',str(len(result))); self.end_headers(); self.wfile.write(result)
            def do_GET(self):
                self.send_response(200); self.send_header('Content-Length','10'); self.end_headers()
                self.wfile.write(b'\x00ZIP'); self.close_connection=True
        server=UnixServer(Handler); worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                token=Path(directory)/'token'; token.write_text('local-secret'); os.chmod(token,0o600)
                provider={'baseUrl':'http://127.0.0.1:28772/v1',
                    'ownedFixed':{'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':'mineru','socketPath':server.socket_path,'tokenFile':str(token),
                                  'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}}
                lifecycle={'mode':'owned_fixed_gpu','operationId':'a'*32,'providerEvidenceProtocol':'dispatch-v1'}
                result=proxy_request(provider,lifecycle,'http://127.0.0.1:28772/v1/uploads/upload_x/content',
                                     body=b'\x00input\xff',method='PUT',timeout=2,dispatch_id=uuid.uuid4().hex)
                self.assertEqual(result,b'\x00ZIP\xff')
                with self.assertRaises(HttpCaptureFailure) as caught:
                    proxy_request(provider,lifecycle,'http://127.0.0.1:28772/v1/parse/jobs',body={'files':[]},timeout=2,dispatch_id=uuid.uuid4().hex)
                self.assertEqual(caught.exception.status,503); self.assertTrue(caught.exception.complete)
                self.assertEqual(caught.exception.raw,b'{"detail":{"kind":"native_capture_failed"}}')
                self.assertFalse(caught.exception.gateway)
                with self.assertRaises(HttpCaptureFailure) as gateway:
                    proxy_request(provider,lifecycle,'http://127.0.0.1:28772/v1/gateway-error',body={},timeout=2,dispatch_id=uuid.uuid4().hex)
                self.assertTrue(gateway.exception.gateway); self.assertTrue(gateway.exception.complete)
                with self.assertRaises(HttpCaptureFailure) as incomplete:
                    proxy_request(provider,lifecycle,'http://127.0.0.1:28772/v1/files/file-a/content',timeout=2,dispatch_id=uuid.uuid4().hex)
                self.assertEqual(incomplete.exception.raw,b'\x00ZIP'); self.assertFalse(incomplete.exception.complete)
                self.assertEqual(calls[0],('PUT','/operations/'+'a'*32+'/provider/v1/uploads/upload_x/content',
                                           'Bearer local-secret',b'\x00input\xff'))
        finally:
            server.shutdown(); server.server_close(); worker.join()

    def test_failed_mineru_job_is_cancelled_while_fixed_session_is_owned(self):
        from complete_ocr import recognize_complete_page, CompleteOcrError
        import test_complete_ocr as fixtures
        fixture=fixtures.CompleteOcrTests(); raw=fixture.image(); _, native=fixture.transport(raw)
        operations={}; calls=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def reply(self,status,body):
                self.send_response(status); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
            def dispatch(self):
                import re
                data=self.rfile.read(int(self.headers.get('Content-Length','0')))
                if self.path == '/operations':
                    row=json.loads(data); op=f'{len(operations)+1:032x}'; operations[op]=row
                    return self.reply(202,json.dumps({'operationId':op}).encode())
                match=re.fullmatch(r'/operations/([a-f0-9]{32})(/finish|/provider/.*)?',self.path)
                if not match: return self.reply(404,b'{}')
                op,suffix=match.groups(); row=operations[op]
                if suffix == '/finish':
                    row['transaction']=json.loads(data)['transaction']; return self.reply(202,b'{}')
                if not suffix and self.command == 'DELETE':
                    calls.append('operation-delete'); return self.reply(202,b'{}')
                if not suffix:
                    transaction=row.get('transaction'); state={**status_identity(row),'operationId':op,'stage':row['stage'],'bindingSha256':'c'*64,
                        'status':'completed' if transaction else 'ready'}
                    if transaction:
                        state['transactionSha256']=hashlib.sha256(json.dumps(transaction,sort_keys=True,ensure_ascii=False,
                            separators=(',',':'),allow_nan=False).encode()).hexdigest()
                        state['release']={'status':'released','cgroupPopulated':0,'observedGpuPids':[42]}
                    return self.reply(200,json.dumps(state).encode())
                path=suffix[len('/provider'):]
                if path == '/v1/parse/jobs/job_authored':
                    calls.append('job-'+self.command)
                    if self.command == 'DELETE': return self.reply(200,b'{"job_id":"job_authored","status":"canceled"}')
                    return self.reply(200,b'{"job_id":"job_authored","status":"failed"}')
                source=('http://127.0.0.1:28771' if row['stage']=='paddle' else 'http://127.0.0.1:28772')+path
                body=json.loads(data) if data and self.headers.get('Content-Type')=='application/json' else data if data else None
                return self.reply(200,native(source,body=body,method=self.command))
            do_GET=do_POST=do_PUT=do_DELETE=dispatch
        server=UnixServer(Handler); worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory); token=root/'token'; token.write_text('local-secret'); os.chmod(token,0o600)
                config=fixture.config(); config['timeoutSeconds']=10
                for parser,stage,port in (('paddleocr-vl','paddle',28771),('mineru','mineru',28772)):
                    config[parser]['baseUrl']=f'http://127.0.0.1:{port}'+('/v1' if stage=='mineru' else '')
                    config[parser]['ownedFixed']={'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':stage,'socketPath':server.socket_path,'tokenFile':str(token),
                                                 'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}
                store=ArtifactStore(root/'cas')
                with self.assertRaises(CompleteOcrError) as caught:
                    recognize_complete_page(raw,config,store,request=lambda *a,**kw:self.fail('direct TCP'))
                receipt=json.loads(store.read_asset(caught.exception.receipt))
                self.assertEqual(receipt['remoteCleanup']['status'],'cancelled')
                self.assertEqual(calls,['job-GET','job-DELETE','operation-delete'])
        finally:
            server.shutdown(); server.server_close(); worker.join()

    def test_persisted_transaction_is_finished_before_release(self):
        calls = []; settling = [False]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def respond(self, value, code=200):
                data = json.dumps(value).encode(); self.send_response(code)
                self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                calls.append((self.command, self.path, self.headers.get('Authorization'), body))
                self.respond({'operationId': 'a'*32, 'status': 'starting' if self.path == '/operations' else 'finishing'}, 202)
            def do_GET(self):
                calls.append((self.command, self.path, self.headers.get('Authorization'), None))
                finished = any(row[1].endswith('/finish') for row in calls)
                transaction = next((row[3]['transaction'] for row in calls if row[1].endswith('/finish')), None)
                transaction_sha = hashlib.sha256(json.dumps(transaction,sort_keys=True,ensure_ascii=False,
                    separators=(',',':'),allow_nan=False).encode()).hexdigest() if finished else None
                state = 'ready' if not finished else 'settling' if not settling[0] else 'completed'
                if finished: settling[0] = True
                self.respond({**status_identity(calls[0][3]), 'operationId': 'a'*32, 'stage':'paddle', 'bindingSha256':'c'*64,
                              'status': state,
                              'transactionSha256': transaction_sha,
                              'release': {'status':'released','cgroupPopulated':0,'ownership':'delegated_cgroup_v2',
                                          'processWaited':True} if finished else None})
        server = UnixServer(Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); token = root/'token'; token.write_text('local-secret'); os.chmod(token, 0o600)
            store = ArtifactStore(root/'cas'); input_ref = store.put_bytes(b'input'); output = store.put_bytes(b'output')
            identity = {'parserVersion':'3.6.0','engine':'authored','models':[{'modelId':'fixture','revision':'a'*40}],'config':{}}
            digest = lambda value: hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()).hexdigest()
            task = {'parser':'paddleocr-vl','inputSha256':input_ref['sha256'],'inputByteCount':5,
                    'originalSourceSha256':'b'*64,'physicalPageNo':1,'profileSha256':digest(identity)}
            provider = {'baseUrl':'http://127.0.0.1:28771','identity':identity, 'taskBinding':task,
                        'ownedFixed':{'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':'paddle','socketPath':server.socket_path,
                                      'tokenFile':str(token), 'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}}
            with owned_native_provider(provider, 5, store.root/'jobs'/'a'/'paddleocr-vl') as lifecycle:
                lifecycle['transaction'] = {'status':'persisted','taskBinding':task,'input':input_ref,
                    'attempts':[{'parser':'paddleocr-vl','status':'completed','response':output}],
                    'rawOutputs':{'page_json':output}}
            self.assertEqual(lifecycle['mode'], 'owned_fixed_gpu')
            self.assertEqual(lifecycle['exitReceipt']['status'], 'released')
            self.assertEqual(lifecycle['exitReceipt']['ownership'], 'delegated_cgroup_v2')
            self.assertNotIn('observedGpuPids', lifecycle['exitReceipt'])
            self.assertEqual([row[1] for row in calls], ['/operations', '/operations/'+'a'*32,
                '/operations/'+'a'*32+'/finish', '/operations/'+'a'*32, '/operations/'+'a'*32])
            self.assertTrue(all(row[2] == 'Bearer local-secret' for row in calls))
            self.assertEqual(calls[0][3]['binding'], task)

    def test_same_stage_wrong_input_or_gateway_profile_is_never_admitted(self):
        for phase, field in (('ready', 'bodySha256'), ('ready', 'sourceSha256'),
                             ('ready', 'profileSha256'), ('ready', 'missing'),
                             ('completed', 'bodySha256'), ('completed', 'sourceSha256'),
                             ('completed', 'profileSha256')):
            with self.subTest(phase=phase, field=field), tempfile.TemporaryDirectory() as directory:
                admission=[]; finished=[]; deleted=[]
                class Handler(BaseHTTPRequestHandler):
                    def log_message(self,*args): pass
                    def respond(self, value, code=200):
                        raw=json.dumps(value).encode(); self.send_response(code)
                        self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
                    def do_POST(self):
                        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                        if self.path == '/operations': admission.append(body)
                        else: finished.append(body['transaction'])
                        self.respond({'operationId':'a'*32},202)
                    def do_GET(self):
                        state={**status_identity(admission[0]),'operationId':'a'*32,'stage':'paddle',
                               'bindingSha256':'c'*64,'status':'completed' if finished else 'ready'}
                        if finished:
                            state['transactionSha256']=hashlib.sha256(encoded(finished[0])).hexdigest()
                            state['release']={'status':'released','cgroupPopulated':0,'observedGpuPids':[42]}
                        if (phase == 'completed') == bool(finished):
                            if field == 'missing': state.pop('sourceSha256')
                            elif field == 'bodySha256': state[field]=hashlib.sha256(b'{"other":1}').hexdigest()
                            elif field == 'sourceSha256':
                                state[field]=hashlib.sha256(encoded({**admission[0]['binding'],'inputSha256':'f'*64})).hexdigest()
                            else: state[field]=hashlib.sha256(encoded({'other':'gateway profile'})).hexdigest()
                        self.respond(state)
                    def do_DELETE(self):
                        deleted.append(self.path); self.respond({},202)
                server=UnixServer(Handler); worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
                try:
                    root=Path(directory); token=root/'token'; token.write_text('local-secret'); os.chmod(token,0o600)
                    store=ArtifactStore(root/'cas'); inp=store.put_bytes(b'input'); out=store.put_bytes(b'output')
                    identity={'parserVersion':'3.6.0','engine':'authored','models':[{'modelId':'fixture','revision':'a'*40}],'config':{}}
                    task={'parser':'paddleocr-vl','inputSha256':inp['sha256'],'inputByteCount':5,
                          'originalSourceSha256':'b'*64,'physicalPageNo':1,
                          'profileSha256':hashlib.sha256(encoded(identity)).hexdigest()}
                    provider={'baseUrl':'http://127.0.0.1:28771','identity':identity,'taskBinding':task,
                        'ownedFixed':{'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':'paddle',
                            'socketPath':server.socket_path,'tokenFile':str(token),'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}}
                    receipt=store.root/'jobs'/'a'/'paddleocr-vl'; entered=[]
                    with self.assertRaisesRegex(ValueError, 'input or gateway profile differs'):
                        with owned_native_provider(provider, 4, receipt) as lifecycle:
                            entered.append(True)
                            lifecycle['transaction']={'status':'persisted','taskBinding':task,'input':inp,
                                'attempts':[{'parser':'paddleocr-vl','status':'completed','response':out}],
                                'rawOutputs':{'page_json':out}}
                    self.assertEqual(bool(entered), phase == 'completed')
                    self.assertEqual(bool(finished), phase == 'completed')
                    self.assertTrue(deleted)
                    self.assertEqual(json.loads((receipt/'exit.json').read_text())['status'],'release_unknown')
                finally:
                    server.shutdown(); server.server_close(); worker.join()

    def test_cancellation_and_readiness_timeout_cancel_without_publishing_release(self):
        for scenario in ('cancel', 'timeout', 'slow_http', 'truncated_http'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                calls = []; admission = []
                class Handler(BaseHTTPRequestHandler):
                    def log_message(self, *args): pass
                    def respond(self, value, code=200):
                        data = json.dumps(value).encode(); self.send_response(code)
                        self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
                    def do_POST(self):
                        admission.append(json.loads(self.rfile.read(int(self.headers['Content-Length'])))); calls.append(self.command)
                        self.respond({'operationId':'a'*32, 'status':'starting'}, 202)
                    def do_GET(self):
                        if scenario == 'truncated_http':
                            raw=json.dumps({'operationId':'a'*32,'stage':'paddle','bindingSha256':'c'*64,'status':'ready'}).encode()
                            self.send_response(200); self.send_header('Content-Length',str(len(raw)+10)); self.end_headers()
                            self.wfile.write(raw); self.close_connection=True
                            return
                        if scenario == 'slow_http':
                            self.send_response(200); self.send_header('Content-Length','128'); self.end_headers()
                            self.wfile.write(b'{'); self.wfile.flush(); time.sleep(.5)
                            try: self.wfile.write(b' ' * 127)
                            except OSError: pass
                            return
                        self.respond({**status_identity(admission[0]),'operationId':'a'*32,'stage':'paddle','bindingSha256':'c'*64,
                                      'status':'ready' if scenario == 'cancel' else 'starting'})
                    def do_DELETE(self):
                        calls.append(self.command); self.respond({'operationId':'a'*32,'status':'cancelled'}, 202)
                server = UnixServer(Handler)
                worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
                try:
                    root=Path(directory); token=root/'token'; token.write_text('local-secret'); os.chmod(token,0o600)
                    store=ArtifactStore(root/'cas'); input_ref=store.put_bytes(b'input')
                    identity={'parserVersion':'3.6.0','engine':'authored','models':[{'modelId':'fixture','revision':'a'*40}],'config':{}}
                    profile=hashlib.sha256(json.dumps(identity,sort_keys=True,separators=(',',':')).encode()).hexdigest()
                    task={'parser':'paddleocr-vl','inputSha256':input_ref['sha256'],'inputByteCount':5,
                          'originalSourceSha256':'b'*64,'physicalPageNo':1,'profileSha256':profile}
                    provider={'baseUrl':'http://127.0.0.1:28771','identity':identity,'taskBinding':task,
                        'ownedFixed':{'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':'paddle','socketPath':server.socket_path,
                                      'tokenFile':str(token),'readyTimeoutSeconds':.15,'cleanupTimeoutSeconds':2}}
                    receipt=store.root/'jobs'/'a'/'paddleocr-vl'; cancelled=threading.Event()
                    started = time.monotonic(); entered = []
                    with self.assertRaises((ValueError, TimeoutError)):
                        with owned_native_provider(provider, 2, receipt, cancelled=cancelled.is_set):
                            entered.append(True); cancelled.set()
                    if scenario == 'slow_http': self.assertLess(time.monotonic()-started, 1.0)
                    if scenario == 'truncated_http': self.assertEqual(entered, [])
                    self.assertIn('DELETE', calls)
                    self.assertEqual(json.loads((receipt/'exit.json').read_text())['status'], 'release_unknown')
                finally:
                    server.shutdown(); server.server_close(); worker.join()

    def test_changed_cas_or_unknown_release_never_produces_a_success(self):
        for scenario in ('corrupt_cas', 'unknown_release'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                calls=[]; admission=[]
                class Handler(BaseHTTPRequestHandler):
                    def log_message(self,*args): pass
                    def respond(self,value,code=200):
                        raw=json.dumps(value).encode(); self.send_response(code)
                        self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
                    def do_POST(self):
                        body=json.loads(self.rfile.read(int(self.headers['Content-Length']))); calls.append(self.path)
                        if self.path == '/operations': admission.append(body)
                        self.respond({'operationId':'a'*32,'status':'starting' if self.path == '/operations' else 'finishing'},202)
                    def do_GET(self):
                        self.respond({**status_identity(admission[0]),'operationId':'a'*32,'stage':'paddle','bindingSha256':'c'*64,
                            'status':'release_unknown' if any(x.endswith('/finish') for x in calls) else 'ready',
                            'release':{'status':'release_unknown'}})
                    def do_DELETE(self):
                        calls.append('DELETE'); self.respond({'operationId':'a'*32,'status':'cancellation_requested'},202)
                server=UnixServer(Handler)
                worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
                try:
                    root=Path(directory); token=root/'token'; token.write_text('local-secret'); os.chmod(token,0o600)
                    store=ArtifactStore(root/'cas'); inp=store.put_bytes(b'input'); out=store.put_bytes(b'output')
                    identity={'parserVersion':'3.6.0','engine':'authored','models':[{'modelId':'fixture','revision':'a'*40}],'config':{}}
                    profile=hashlib.sha256(json.dumps(identity,sort_keys=True,separators=(',',':')).encode()).hexdigest()
                    task={'parser':'paddleocr-vl','inputSha256':inp['sha256'],'inputByteCount':5,
                          'originalSourceSha256':'b'*64,'physicalPageNo':1,'profileSha256':profile}
                    provider={'baseUrl':'http://127.0.0.1:28771','identity':identity,'taskBinding':task,
                        'ownedFixed':{'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':'paddle','socketPath':server.socket_path,
                                      'tokenFile':str(token),'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}}
                    receipt=store.root/'jobs'/'a'/'paddleocr-vl'
                    with self.assertRaises(ValueError):
                        with owned_native_provider(provider, 4, receipt) as lifecycle:
                            lifecycle['transaction']={'status':'persisted','taskBinding':task,'input':inp,
                                'attempts':[{'parser':'paddleocr-vl','status':'completed','response':out}],
                                'rawOutputs':{'page_json':out}}
                            if scenario == 'corrupt_cas': (store.root/out['relativePath']).write_bytes(b'damage')
                    self.assertEqual(json.loads((receipt/'exit.json').read_text())['status'],'release_unknown')
                    self.assertIn('DELETE',calls)
                    self.assertEqual(any(x.endswith('/finish') for x in calls), scenario == 'unknown_release')
                finally:
                    server.shutdown(); server.server_close(); worker.join()

    def test_lost_admission_response_is_unknown_and_never_retried(self):
        calls=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length'])); calls.append(self.path)
                self.connection.shutdown(socket.SHUT_RDWR); self.connection.close()
        server=UnixServer(Handler)
        worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory); token=root/'token'; token.write_text('local-secret'); os.chmod(token,0o600)
                store=ArtifactStore(root/'cas'); inp=store.put_bytes(b'input')
                identity={'parserVersion':'3.6.0','engine':'authored','models':[{'modelId':'fixture','revision':'a'*40}],'config':{}}
                profile=hashlib.sha256(json.dumps(identity,sort_keys=True,separators=(',',':')).encode()).hexdigest()
                task={'parser':'paddleocr-vl','inputSha256':inp['sha256'],'inputByteCount':5,
                      'originalSourceSha256':'b'*64,'physicalPageNo':1,'profileSha256':profile}
                provider={'baseUrl':'http://127.0.0.1:28771','identity':identity,'taskBinding':task,
                    'ownedFixed':{'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':'paddle','socketPath':server.socket_path,
                                  'tokenFile':str(token),'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}}
                receipt=store.root/'jobs'/'a'/'paddleocr-vl'
                with self.assertRaises(Exception):
                    with owned_native_provider(provider,4,receipt): self.fail('unknown admission cannot be ready')
                result=json.loads((receipt/'exit.json').read_text())
                self.assertEqual(result['status'],'release_unknown'); self.assertIsNone(result['operationId'])
                self.assertEqual(calls,['/operations'])
        finally:
            server.shutdown(); server.server_close(); worker.join()

    def test_cancel_interrupts_pending_admission_without_retry_or_release_claim(self):
        calls=[]; received=threading.Event(); cancelled=threading.Event(); finish=threading.Event()
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length'])); calls.append(self.path); received.set()
                finish.wait(3)
        server=UnixServer(Handler)
        worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
        trigger=threading.Thread(target=lambda: (received.wait(2), cancelled.set()),daemon=True); trigger.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory); token=root/'token'; token.write_text('local-secret'); os.chmod(token,0o600)
                store=ArtifactStore(root/'cas'); inp=store.put_bytes(b'input')
                identity={'parserVersion':'3.6.0','engine':'authored','models':[{'modelId':'fixture','revision':'a'*40}],'config':{}}
                profile=hashlib.sha256(json.dumps(identity,sort_keys=True,separators=(',',':')).encode()).hexdigest()
                task={'parser':'paddleocr-vl','inputSha256':inp['sha256'],'inputByteCount':5,
                      'originalSourceSha256':'b'*64,'physicalPageNo':1,'profileSha256':profile}
                provider={'baseUrl':'http://127.0.0.1:28771','identity':identity,'taskBinding':task,
                    'ownedFixed':{'gatewayProfileSha256':GATEWAY_PROFILE_SHA,'stage':'paddle','socketPath':server.socket_path,
                                  'tokenFile':str(token),'readyTimeoutSeconds':2,'cleanupTimeoutSeconds':2}}
                receipt=store.root/'jobs'/'a'/'paddleocr-vl'; started=time.monotonic()
                with self.assertRaises(Exception):
                    with owned_native_provider(provider,2,receipt,cancelled=cancelled.is_set): self.fail('cancelled')
                self.assertLess(time.monotonic()-started,1)
                result=json.loads((receipt/'exit.json').read_text())
                self.assertEqual(result['status'],'release_unknown'); self.assertIsNone(result['operationId'])
                self.assertEqual(calls,['/operations'])
        finally:
            finish.set(); server.shutdown(); server.server_close(); worker.join(); trigger.join()


if __name__ == '__main__': unittest.main()
