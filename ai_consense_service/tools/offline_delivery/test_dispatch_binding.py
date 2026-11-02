import sys, os, json, hashlib, threading, time, http.client, socket, unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
sys.path[:0]=[str(HERE),str(ROOT/'ai_consense_service/tools/offline_delivery'),str(ROOT/'ai_consense_service/tools/vetting_eval')]
from fixed_gpu_worker import Worker, make_server
from request_stage import encoded, envelope
class Upstream(BaseHTTPRequestHandler):
    calls=[]
    def log_message(self,*args): pass
    def do_GET(self):
        self.calls.append(self.path)
        data=b'{"job_id":"job_1","status":"running"}'
        self.send_response(200); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
class Contract(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory(); self.root=Path(self.temp.name)
        self.up=ThreadingHTTPServer(('127.0.0.1',0),Upstream); Upstream.calls=[]
        self.ut=threading.Thread(target=self.up.serve_forever,daemon=True); self.ut.start()
        profile={'readyUrl':'http://127.0.0.1:%d'%self.up.server_port,'timeoutSeconds':30,'providerOptions':{}}
        self.worker=Worker({'workspace':str(self.root),'stages':{'mineru':profile}},'secret')
        self.op=envelope('mineru',b'{}',{'inputSha256':'a'*64,'inputByteCount':1},{'adapter':'fixture'})
        self.dir=self.worker.operations/self.op['operationId']; self.dir.mkdir()
        (self.dir/'request.json').write_bytes(encoded(self.op))
        (self.dir/'status.json').write_bytes(encoded({'operationId':self.op['operationId'],'stage':'mineru','status':'ready','bindingSha256':self.op['bindingSha256'],'deadlineUnixMs':int((time.time()+20)*1000)}))
        self.worker.active[self.op['operationId']]=(threading.Event(),threading.Event())
        self.worker.trees[self.op['operationId']]=object()
        self.worker.sessions[self.op['operationId']]={'lock':threading.Lock(),'jobId':'job_1','fileId':'file-1'}
        self.p1=patch.object(self.worker,'_listener_owned',return_value=True); self.p2=patch.object(self.worker,'_connection_owned',return_value=True); self.p1.start(); self.p2.start()
        self.gateway=make_server(self.worker,('127.0.0.1',0)); self.gt=threading.Thread(target=self.gateway.serve_forever,daemon=True); self.gt.start()
    def tearDown(self):
        self.gateway.shutdown(); self.gateway.server_close(); self.gt.join(2); self.up.shutdown(); self.up.server_close(); self.ut.join(2); self.p1.stop(); self.p2.stop(); self.temp.cleanup()
    def req(self, ids, body=b'', content_type='application/json'):
        c=http.client.HTTPConnection(*self.gateway.server_address,timeout=3)
        c.putrequest('GET','/operations/'+self.op['operationId']+'/provider/v1/parse/jobs/job_1'); c.putheader('Authorization','Bearer secret')
        for i in ids: c.putheader('X-Consense-Provider-Dispatch-Id',i)
        c.putheader('Content-Type',content_type); c.putheader('Content-Length',str(len(body)))
        c.endheaders(body); r=c.getresponse(); data=r.read(); c.close(); return r.status,data
    def test_identified_dispatches(self):
        a,b='1'*32,'2'*32
        self.assertGreaterEqual(self.req([])[0],400)
        self.assertGreaterEqual(self.req([a,a])[0],400)
        self.assertGreaterEqual(self.req(['A'*32])[0],400)
        self.assertGreaterEqual(self.req([a+','+b])[0],400)
        self.assertEqual(self.req([a])[0],200)
        self.assertEqual(self.req([b])[0],200)
        first=(self.dir/('provider-'+a+'.bin')).read_bytes(); receipt=(self.dir/('provider-'+a+'.json')).read_bytes()
        self.assertGreaterEqual(self.req([a])[0],400)
        self.assertEqual(first,(self.dir/('provider-'+a+'.bin')).read_bytes()); self.assertEqual(receipt,(self.dir/('provider-'+a+'.json')).read_bytes())
        self.assertEqual(len(Upstream.calls),2)
        for i in (a,b):
            info=json.loads((self.dir/('provider-'+i+'.json')).read_bytes())
            self.assertEqual((info['operationId'],info['bindingSha256'],info['dispatchId']),(self.op['operationId'],self.op['bindingSha256'],i))
            self.assertEqual(info['sha256'],hashlib.sha256((self.dir/('provider-'+i+'.bin')).read_bytes()).hexdigest())
    def test_reserved_failure_is_not_replayed(self):
        ident='3'*32
        with patch('fixed_gpu_worker.ProviderConnection',side_effect=OSError('injected')):
            self.assertGreaterEqual(self.req([ident])[0],400)
        self.assertTrue((self.dir/('provider-'+ident+'.reservation')).exists())
        self.assertGreaterEqual(self.req([ident])[0],400)
        self.assertEqual(Upstream.calls,[])
    def test_short_reservation_write_never_dispatches_or_replays(self):
        ident='9'*32; original=os.fdopen
        class ShortWrite:
            def __init__(self, stream): self.stream=stream
            def __enter__(self): return self
            def __exit__(self,*args): self.stream.close()
            def write(self,data): return self.stream.write(data[:-1])
            def __getattr__(self,name): return getattr(self.stream,name)
        with patch('fixed_gpu_worker.os.fdopen',side_effect=lambda fd,*args,**kwargs: ShortWrite(original(fd,*args,**kwargs))):
            self.assertGreaterEqual(self.req([ident])[0],400)
        reservation=self.dir/('provider-'+ident+'.reservation')
        self.assertTrue(reservation.exists())
        with self.assertRaises(json.JSONDecodeError): json.loads(reservation.read_bytes())
        self.assertGreaterEqual(self.req([ident])[0],400)
        self.assertEqual(Upstream.calls,[])
    def test_request_identity_and_tamper_detection(self):
        a,b='4'*32,'5'*32; bodies=(b'alpha',b'bravo')
        for ident,body in zip((a,b),bodies):
            status, response = self.req([ident],body,'application/octet-stream')
            self.assertEqual(status,200,response)
            reservation=json.loads((self.dir/('provider-'+ident+'.reservation')).read_bytes())
            receipt=json.loads((self.dir/('provider-'+ident+'.json')).read_bytes())
            for record in (reservation,receipt):
                self.assertEqual((record['requestByteCount'],record['requestSha256'],record['requestContentType']),
                                 (len(body),hashlib.sha256(body).hexdigest(),'application/octet-stream'))
        ra=json.loads((self.dir/('provider-'+a+'.json')).read_bytes()); rb=json.loads((self.dir/('provider-'+b+'.json')).read_bytes())
        self.assertNotEqual(ra['requestSha256'],rb['requestSha256'])
        self.assertNotEqual(ra['requestSha256'],hashlib.sha256(b'wrong').hexdigest())
    def test_empty_body_metadata_after_reserved_failure(self):
        ident='6'*32
        with patch('fixed_gpu_worker.ProviderConnection',side_effect=OSError('injected')):
            self.assertGreaterEqual(self.req([ident])[0],400)
        reservation=json.loads((self.dir/('provider-'+ident+'.reservation')).read_bytes())
        self.assertEqual((reservation['requestByteCount'],reservation['requestSha256'],reservation['requestContentType']),
                         (0,hashlib.sha256(b'').hexdigest(),'application/json'))
    def test_proxy_explicit_empty_bytes_reaches_gateway_as_binary(self):
        import fixed_gpu_provider
        class LocalConnection(http.client.HTTPConnection):
            def __init__(self,path,timeout): super().__init__(*gateway,timeout=timeout)
        gateway=self.gateway.server_address; ident='8'*32
        lifecycle={'mode':'owned_fixed_gpu','operationId':self.op['operationId'],'providerEvidenceProtocol':'dispatch-v1'}
        provider={'baseUrl':'http://127.0.0.1:28772/v1'}
        with patch.object(fixed_gpu_provider,'configuration',return_value=({},Path('fixture'))), patch.object(fixed_gpu_provider,'_token',return_value='secret'), patch.object(fixed_gpu_provider,'UnixConnection',LocalConnection):
            fixed_gpu_provider.proxy_request(provider,lifecycle,provider['baseUrl']+'/parse/jobs/job_1',body=b'',method='GET',timeout=3,dispatch_id=ident,content_type='application/octet-stream')
        info=json.loads((self.dir/('provider-'+ident+'.json')).read_bytes())
        self.assertEqual((info['requestByteCount'],info['requestSha256'],info['requestContentType']),(0,hashlib.sha256(b'').hexdigest(),'application/octet-stream'))
    def test_real_source_and_worker_request_binding_detects_tamper(self):
        from contextlib import contextmanager
        import complete_ocr, fixed_gpu_provider
        from artifact_store import ArtifactStore
        from test_complete_ocr import CompleteOcrTests
        fixture=CompleteOcrTests(); raw=fixture.image(); config=fixture.config(); config['paddleocr-vl']['ownedFixed']={'stage':'paddle'}
        import copy
        self.worker.config['stages']['paddle']={'readyUrl':'http://127.0.0.1:%d'%self.up.server_port,'timeoutSeconds':30,'providerOptions':copy.deepcopy(complete_ocr.PADDLE_OPTIONS)}
        binding={'inputSha256':hashlib.sha256(raw).hexdigest(),'inputByteCount':len(raw)}
        op=envelope('paddle',b'{}',binding,{'adapter':'fixture'}); directory=self.worker.operations/op['operationId']; directory.mkdir()
        (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],'stage':'paddle','status':'ready','bindingSha256':op['bindingSha256'],'deadlineUnixMs':int((time.time()+20)*1000)}))
        self.worker.active[op['operationId']]=(threading.Event(),threading.Event()); self.worker.trees[op['operationId']]=object(); self.worker.sessions[op['operationId']]={'lock':threading.Lock()}
        @contextmanager
        def owned(*args,**kwargs): yield {'mode':'owned_fixed_gpu','operationId':op['operationId'],'operationBindingSha256':op['bindingSha256'],'providerEvidenceProtocol':'dispatch-v1'}
        class LocalConnection(http.client.HTTPConnection):
            def __init__(self,path,timeout): super().__init__(*self_gateway,timeout=timeout)
        self_gateway=self.gateway.server_address
        def post(handler):
            body=handler.rfile.read(int(handler.headers['Content-Length'])); Upstream.calls.append(body)
            response=b'{"error":"fixture"}'; handler.send_response(503); handler.send_header('Content-Length',str(len(response))); handler.end_headers(); handler.wfile.write(response)
        config['paddleocr-vl']['baseUrl']='http://127.0.0.1:28771'
        with TemporaryDirectory() as root, patch.object(Upstream,'do_POST',post,create=True), patch.object(complete_ocr,'owned_native_provider',owned), patch.object(fixed_gpu_provider,'configuration',return_value=({},Path('fixture'))), patch.object(fixed_gpu_provider,'_token',return_value='secret'), patch.object(fixed_gpu_provider,'UnixConnection',LocalConnection):
            class MutatingStore(ArtifactStore):
                mutated=False
                def put_bytes(self, data, **kwargs):
                    result=super().put_bytes(data, **kwargs)
                    if b'"markdownIgnoreLabels":[]' in data:
                        complete_ocr.PADDLE_OPTIONS['markdownIgnoreLabels'].append('late mutation'); self.mutated=True
                    return result
            store=MutatingStore(root)
            try:
                with self.assertRaises(complete_ocr.CompleteOcrError) as failure: complete_ocr.recognize_complete_page(raw,config,store)
            finally: complete_ocr.PADDLE_OPTIONS['markdownIgnoreLabels'].clear()
            self.assertTrue(store.mutated)
            source=json.loads(store.read_asset(failure.exception.receipt))['attempts'][0]
            worker=json.loads((directory/('provider-'+source['dispatchId']+'.json')).read_bytes())
            original=store.read_asset(source['request'])
            self.assertEqual(json.loads(original)['markdownIgnoreLabels'],[])
            self.assertEqual(original,Upstream.calls[-1])
            expected=(len(original),hashlib.sha256(original).hexdigest(),source['requestContentType'])
            self.assertEqual(expected,(worker['requestByteCount'],worker['requestSha256'],worker['requestContentType']))
            self.assertEqual(expected,(source['requestByteCount'],source['requestSha256'],source['requestContentType']))
            self.assertNotEqual(hashlib.sha256(original+b'tamper').hexdigest(),worker['requestSha256'])
            self.assertEqual(worker['operationId'],source['operationId']); self.assertEqual(worker['bindingSha256'],source['operationBindingSha256'])
    def test_partial_upstream_failure_receipt_binds_request(self):
        ident='7'*32; body=b'actual request'
        class Response:
            status=200; length=10; chunked=False; fp=None
            def __init__(self):
                from email.message import Message
                self.calls=0; self.headers=Message(); self.headers['Content-Length']='10'
            def getheader(self,name,default=None): return default
            def read1(self,n):
                self.calls+=1
                if self.calls==1: return b'prefix'
                raise OSError('injected partial response')
            def close(self): pass
        class Connection:
            sock=SimpleNamespace(shutdown=lambda *_: None, close=lambda: None)
            def __init__(self,*args,**kwargs): pass
            def connect(self): pass
            def request(self,*args,**kwargs): pass
            def getresponse(self): return Response()
            def close(self): pass
        with patch('fixed_gpu_worker.ProviderConnection',Connection):
            self.assertGreaterEqual(self.req([ident],body,'application/octet-stream')[0],400)
        info=json.loads((self.dir/('provider-'+ident+'.json')).read_bytes())
        self.assertFalse(info['complete']); self.assertEqual(info['bytes'],6)
        self.assertEqual((info['requestByteCount'],info['requestSha256'],info['requestContentType']),
                         (len(body),hashlib.sha256(body).hexdigest(),'application/octet-stream'))
        self.assertEqual((self.dir/('provider-'+ident+'.bin')).read_bytes(),b'prefix')
    def test_mineru_source_gateway_binary_empty_and_cleanup_binding(self):
        from contextlib import contextmanager
        import complete_ocr, fixed_gpu_provider
        from artifact_store import ArtifactStore
        from test_complete_ocr import CompleteOcrTests
        fixture=CompleteOcrTests(); raw=fixture.image(); config=fixture.config(); config['timeoutSeconds']=15
        config['mineru']['ownedFixed']={'stage':'mineru'}
        self.worker.config['stages']['mineru']['providerOptions']=complete_ocr.MINERU_OPTIONS
        op=envelope('mineru',b'{}',{'inputSha256':hashlib.sha256(raw).hexdigest(),'inputByteCount':len(raw)},{'adapter':'fixture'})
        directory=self.worker.operations/op['operationId']; directory.mkdir()
        (directory/'request.json').write_bytes(encoded(op))
        (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],'stage':'mineru','status':'ready','bindingSha256':op['bindingSha256'],'deadlineUnixMs':int((time.time()+20)*1000)}))
        self.worker.active[op['operationId']]=(threading.Event(),threading.Event()); self.worker.trees[op['operationId']]=object(); self.worker.sessions[op['operationId']]={'lock':threading.Lock()}
        @contextmanager
        def owned(provider,*args,**kwargs):
            if provider.get('ownedFixed') is None: yield {'mode':'fixture'}
            else: yield {'mode':'owned_fixed_gpu','operationId':op['operationId'],'operationBindingSha256':op['bindingSha256'],'providerEvidenceProtocol':'dispatch-v1'}
        _,native=fixture.transport(raw)
        def serve(handler):
            body=handler.rfile.read(int(handler.headers.get('Content-Length','0')))
            content=handler.headers.get('Content-Type'); method=handler.command
            if handler.path.endswith('/parse/jobs/job_authored'):
                response=b'{"job_id":"job_authored","status":"canceled"}' if method=='DELETE' else b'{"job_id":"job_authored","status":"failed"}'
            else:
                value=json.loads(body) if body and content=='application/json' else body if body else None
                response=native('http://fixture'+handler.path,body=value,method=method)
            handler.send_response(200); handler.send_header('Content-Length',str(len(response))); handler.end_headers(); handler.wfile.write(response)
        class LocalConnection(http.client.HTTPConnection):
            def __init__(self,path,timeout): super().__init__(*gateway,timeout=timeout)
        gateway=self.gateway.server_address
        with TemporaryDirectory() as root, patch.object(Upstream,'do_POST',serve,create=True), patch.object(Upstream,'do_PUT',serve,create=True), patch.object(Upstream,'do_GET',serve), patch.object(Upstream,'do_DELETE',serve,create=True), patch.object(complete_ocr,'owned_native_provider',owned), patch.object(fixed_gpu_provider,'configuration',return_value=({},Path('fixture'))), patch.object(fixed_gpu_provider,'_token',return_value='secret'), patch.object(fixed_gpu_provider,'UnixConnection',LocalConnection):
            store=ArtifactStore(root)
            with self.assertRaises(complete_ocr.CompleteOcrError) as failure: complete_ocr.recognize_complete_page(raw,config,store,request=native)
            receipt=json.loads(store.read_asset(failure.exception.receipt)); attempts=[a for a in receipt['attempts'] if a['parser']=='mineru']; cleanup=receipt['remoteCleanup']
            for source in [*attempts,cleanup]:
                worker=json.loads((directory/('provider-'+source['dispatchId']+'.json')).read_bytes())
                path=source.get('path') or __import__('urllib.parse',fromlist=['urlsplit']).urlsplit(source['url']).path
                self.assertEqual((source['operationId'],source['operationBindingSha256'],source['method'],path),(worker['operationId'],worker['bindingSha256'],worker['method'],worker['path']))
                self.assertEqual(tuple(source[k] for k in ('requestByteCount','requestSha256','requestContentType')),tuple(worker[k] for k in ('requestByteCount','requestSha256','requestContentType')))
                if source.get('request') is not None: self.assertEqual(hashlib.sha256(store.read_asset(source['request'])).hexdigest(),worker['requestSha256'])
            binary=next(a for a in attempts if a['method']=='PUT')
            self.assertEqual(store.read_asset(binary['request']),raw); self.assertEqual(binary['requestContentType'],'application/octet-stream')
            self.assertFalse(next(a for a in attempts if a['method']=='GET')['sourceBodyProvided'])
            self.assertEqual((cleanup['status'],cleanup['method'],cleanup['path']),('cancelled','DELETE','/v1/parse/jobs/job_authored'))
            self.assertFalse(cleanup['sourceBodyProvided'])
    def test_capability(self):
        self.assertEqual(self.worker.status(self.op['operationId'])['providerEvidenceProtocol'],'dispatch-v1')
class SourceContract(unittest.TestCase):
    def test_partial_proxy_body_and_binding_remain_distinct(self):
        from contextlib import contextmanager
        import complete_ocr, fixed_gpu_provider
        from artifact_store import ArtifactStore
        from test_complete_ocr import CompleteOcrTests
        fixture=CompleteOcrTests(); raw=fixture.image(); config=fixture.config()
        config['paddleocr-vl']['ownedFixed']={'stage':'paddle'}
        lifecycle={'mode':'owned_fixed_gpu','operationId':'a'*32,'operationBindingSha256':'b'*64,'providerEvidenceProtocol':'dispatch-v1'}
        @contextmanager
        def owned(*args,**kwargs): yield lifecycle
        sent=[]
        def failure(*args,**kwargs):
            sent.append(kwargs['dispatch_id']); raise complete_ocr.HttpCaptureFailure('fixture',b'\x00prefix',None,False,False)
        with TemporaryDirectory() as root, patch.object(complete_ocr,'owned_native_provider',owned), patch.object(fixed_gpu_provider,'proxy_request',side_effect=failure):
            store=ArtifactStore(root)
            with self.assertRaises(complete_ocr.CompleteOcrError) as error: complete_ocr.recognize_complete_page(raw,config,store)
            receipt=json.loads(store.read_asset(error.exception.receipt)); attempt=receipt['attempts'][0]
            self.assertEqual((attempt['operationId'],attempt['operationBindingSha256'],attempt['dispatchId']),('a'*32,'b'*64,sent[0]))
            self.assertEqual(attempt['workerCaptureStatus'],'unknown')
            self.assertTrue(attempt['sourceBodyProvided'])
            self.assertEqual((attempt['requestByteCount'],attempt['requestSha256']), (attempt['request']['byteCount'],attempt['request']['sha256']))
            self.assertEqual(attempt['requestContentType'],'application/json')
            self.assertEqual(attempt['response']['identityScope'],'actual_proxy_http_body_incomplete')
            self.assertEqual(store.read_asset(attempt['response']),b'\x00prefix')
            self.assertFalse(attempt['responseComplete'])
    def test_legacy_gateway_is_not_dispatched(self):
        import fixed_gpu_provider
        provider={'baseUrl':'http://127.0.0.1:28771','ownedFixed':{}}
        with patch.object(fixed_gpu_provider,'configuration',return_value=({},Path('fixture'))), patch.object(fixed_gpu_provider,'_token') as token:
            with self.assertRaisesRegex(ValueError,'protocol unavailable'):
                fixed_gpu_provider.proxy_request(provider,{'mode':'owned_fixed_gpu','operationId':'a'*32},provider['baseUrl']+'/layout-parsing',timeout=1,dispatch_id='b'*32)
            token.assert_not_called()

    def test_real_lifecycle_rejects_old_gateway_capability(self):
        import fixed_gpu_provider
        identity={'fixture':True}; profile='c'*64
        task={'parser':'paddleocr-vl','profileSha256':hashlib.sha256(encoded(identity)).hexdigest()}
        boot={'gatewayProfileSha256':profile,'stage':'paddle','readyTimeoutSeconds':1,'cleanupTimeoutSeconds':2}
        calls=[]
        class Response:
            status=200; length=0
            def __init__(self,value): self.value=value
            def read(self,n): return encoded(self.value)
        class Connection:
            sock=None
            def __init__(self,*args): pass
            def request(self,method,path,body=None,headers=None): self.method=method; self.path=path; calls.append((method,path))
            def getresponse(self):
                if self.method=='POST': return Response({'operationId':'a'*32})
                if self.method=='DELETE': return Response({'status':'cancelled'})
                return Response({'operationId':'a'*32,'stage':'paddle','status':'ready','bindingSha256':'b'*64,
                    'bodySha256':hashlib.sha256(b'{}').hexdigest(),'sourceSha256':hashlib.sha256(encoded(task)).hexdigest(),'profileSha256':profile})
            def close(self): pass
        with TemporaryDirectory() as root, patch.object(fixed_gpu_provider,'configuration',return_value=(boot,Path('fixture'))), patch.object(fixed_gpu_provider,'_token',return_value='secret'), patch.object(fixed_gpu_provider,'UnixConnection',Connection):
            with self.assertRaisesRegex(ValueError,'protocol unavailable'):
                with fixed_gpu_provider.owned_fixed_provider({'identity':identity,'taskBinding':task},3,Path(root)/'cas/jobs/a/paddle'):
                    self.fail('old gateway must not reach dispatch')
        self.assertEqual([method for method,path in calls],['POST','GET','DELETE'])

    def test_cleanup_has_a_distinct_dispatch_identity(self):
        from contextlib import contextmanager
        import complete_ocr, fixed_gpu_provider
        from artifact_store import ArtifactStore
        from test_complete_ocr import CompleteOcrTests
        fixture=CompleteOcrTests(); raw=fixture.image(); config=fixture.config(); _,native=fixture.transport(raw)
        for parser,stage in [('paddleocr-vl','paddle'),('mineru','mineru')]: config[parser]['ownedFixed']={'stage':stage}
        seen=[]
        @contextmanager
        def owned(provider,*args,**kwargs):
            yield {'mode':'owned_fixed_gpu','operationId':('a' if provider['ownedFixed']['stage']=='paddle' else 'b')*32,
                   'operationBindingSha256':'c'*64,'providerEvidenceProtocol':'dispatch-v1'}
        def dispatch(provider,lifecycle,url,**kwargs):
            seen.append((kwargs.get('method') or ('POST' if kwargs.get('body') is not None else 'GET'),url,kwargs['dispatch_id']))
            if url.endswith('/parse/jobs/job_authored'):
                if kwargs.get('method')=='DELETE': return b'{"job_id":"job_authored","status":"canceled"}'
                return b'{"job_id":"job_authored","status":"failed"}'
            body=kwargs.get('body')
            if body is not None and kwargs.get('content_type')=='application/json': body=json.loads(body)
            return native(url,body=body,method=kwargs.get('method'))
        with TemporaryDirectory() as root, patch.object(complete_ocr,'owned_native_provider',owned), patch.object(fixed_gpu_provider,'proxy_request',side_effect=dispatch):
            store=ArtifactStore(root)
            with self.assertRaises(complete_ocr.CompleteOcrError) as error: complete_ocr.recognize_complete_page(raw,config,store)
            receipt=json.loads(store.read_asset(error.exception.receipt)); cleanup=receipt['remoteCleanup']
            self.assertEqual(cleanup['status'],'cancelled'); self.assertEqual(cleanup['workerCaptureStatus'],'unknown')
            self.assertFalse(cleanup['sourceBodyProvided']); self.assertEqual(cleanup['requestByteCount'],0)
            self.assertEqual(cleanup['requestSha256'],hashlib.sha256(b'').hexdigest()); self.assertEqual(cleanup['requestContentType'],'application/json')
            self.assertEqual(cleanup['dispatchId'],seen[-1][2]); self.assertEqual(seen[-1][0],'DELETE')
            self.assertEqual((cleanup['method'],cleanup['path']),('DELETE','/v1/parse/jobs/job_authored'))
            self.assertEqual(len({r[2] for r in seen}),len(seen))

    def test_transport_emits_only_valid_private_gateway_header(self):
        import complete_ocr, fixed_gpu_provider
        seen=[]
        class Capture(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self):
                seen.append((self.headers.get_all('X-Consense-Provider-Dispatch-Id'),self.headers.get('Authorization')))
                self.send_response(200); self.send_header('Content-Length','2'); self.end_headers(); self.wfile.write(b'ok')
        server=ThreadingHTTPServer(('127.0.0.1',0),Capture); thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        class LocalConnection(http.client.HTTPConnection):
            def __init__(self,path,timeout): super().__init__(*server.server_address,timeout=timeout)
        try:
            with patch.object(fixed_gpu_provider,'UnixConnection',LocalConnection):
                self.assertEqual(complete_ocr._request('http://localhost/provider',timeout=2,socket_path='fixture',token='secret',dispatch_id='d'*32),b'ok')
                with self.assertRaises(ValueError): complete_ocr._request('http://localhost/provider',timeout=2,dispatch_id='d'*32)
                with self.assertRaises(ValueError): complete_ocr._request('http://localhost/provider',timeout=2,socket_path='fixture',token='secret',dispatch_id='D'*32)
            self.assertEqual(seen,[(['d'*32],'Bearer secret')])
        finally: server.shutdown(); server.server_close(); thread.join(2)

if __name__=='__main__': unittest.main(verbosity=2)
