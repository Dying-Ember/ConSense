"""Targeted synthetic regression tests for isolated successor."""
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

import fixed_gpu_worker
from fixed_gpu_worker import Worker, make_server
from request_stage import encoded, envelope
from test_upstream_minimal import UpstreamCaptureTest


class SuccessorTest(unittest.TestCase):
    def test_short_owner_marker_blocks_admission_and_retains_marker(self):
        real_open,real_fdopen=os.open,os.fdopen; selected=set()
        class Short:
            def __init__(self,stream): self.stream=stream
            def __enter__(self): return self
            def __exit__(self,*args): return self.stream.__exit__(*args)
            def write(self,data): return self.stream.write(data[:1])
            def __getattr__(self,name): return getattr(self.stream,name)
        def tracked(path,flags,*args,**kwargs):
            fd=real_open(path,flags,*args,**kwargs)
            if str(path).endswith('gpu-owner.json'): selected.add(fd)
            return fd
        def wrapped(fd,*args,**kwargs):
            stream=real_fdopen(fd,*args,**kwargs)
            if fd in selected: selected.remove(fd); return Short(stream)
            return stream
        with tempfile.TemporaryDirectory() as root:
            inner={'model':'fixture'};profile={'mode':'job','profile':inner,'timeoutSeconds':30}
            worker=Worker({'workspace':root,'stages':{'business-llm':profile}},secret='fixture-secret')
            request={'stage':'business-llm','binding':{'id':'fixture'},'bodyBase64':base64.b64encode(b'{}').decode(),
                'expectedProfileSha256':hashlib.sha256(encoded(inner)).hexdigest(),'expectedMode':'job'}
            with patch.object(worker,'_validate_profile',return_value=({},30)), \
                 patch('fixed_gpu_worker.os.open',side_effect=tracked),patch('fixed_gpu_worker.os.fdopen',side_effect=wrapped):
                with self.assertRaisesRegex(OSError,'short owner marker write'): worker.start(request)
            self.assertTrue(worker.marker.exists());self.assertEqual(worker.marker.read_bytes(),b'{')
            self.assertFalse(worker.active);self.assertFalse(list(worker.operations.iterdir()))
            with patch.object(worker,'_validate_profile',return_value=({},30)):
                with self.assertRaisesRegex(ValueError,'owner exists'): worker.start(request)

    def test_persist_short_write_preserves_destination_and_retains_temporary(self):
        real_fdopen=os.fdopen
        class Short:
            def __init__(self,stream): self.stream=stream
            def __enter__(self): return self
            def __exit__(self,*args): return self.stream.__exit__(*args)
            def write(self,data): return self.stream.write(data[:1])
            def __getattr__(self,name): return getattr(self.stream,name)
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'result.json'; path.write_bytes(b'old')
            with patch('fixed_gpu_worker.os.fdopen',side_effect=lambda fd,*a,**k:Short(real_fdopen(fd,*a,**k))):
                with self.assertRaises(OSError): fixed_gpu_worker.persist(path,{'new':True})
            self.assertEqual(path.read_bytes(),b'old')
            self.assertEqual((Path(root)/'.result.json.tmp').read_bytes(),b'{')
            with self.assertRaises(FileExistsError): fixed_gpu_worker.persist(path,{'new':True})
            self.assertEqual(path.read_bytes(),b'old')

    def test_wire_and_overflow_short_writes_are_not_published(self):
        real_open,real_fdopen=os.open,os.fdopen; selected=set()
        class Short:
            def __init__(self,stream): self.stream=stream
            def __enter__(self): return self
            def __exit__(self,*args): return self.stream.__exit__(*args)
            def write(self,data): return self.stream.write(data[:1])
            def __getattr__(self,name): return getattr(self.stream,name)
        def tracked(path,flags,*args,**kwargs):
            fd=real_open(path,flags,*args,**kwargs)
            if str(path).endswith(('.wire','.overflow')):
                stat=os.fstat(fd); selected.add((stat.st_dev,stat.st_ino))
            return fd
        def wrapped(fd,*args,**kwargs):
            stream=real_fdopen(fd,*args,**kwargs)
            stat=os.fstat(fd); key=(stat.st_dev,stat.st_ino)
            if key in selected: selected.remove(key); return Short(stream)
            return stream
        with patch('fixed_gpu_worker.os.open',side_effect=tracked),patch('fixed_gpu_worker.os.fdopen',side_effect=wrapped):
            wire=UpstreamCaptureTest().exchange(b'HTTP/1.0 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZZ\r\n')
            overflow=UpstreamCaptureTest().exchange(b'HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc',max_body=2)
        self.assertEqual(wire['receipt']['wireCaptureErrorType'],'OSError'); self.assertIsNone(wire['fragment'])
        self.assertEqual(overflow['receipt']['overflowCaptureErrorType'],'OSError'); self.assertIsNone(overflow['overflow'])

    def test_no_body_http_statuses(self):
        for wire in (b'HTTP/1.1 204 No Content\r\n\r\n', b'HTTP/1.1 304 Not Modified\r\n\r\n',
                     b'HTTP/1.1 304 Not Modified\r\nContent-Length: 123\r\n\r\n',
                     b'HTTP/1.1 304 Not Modified\r\nTransfer-Encoding: chunked\r\n\r\n'):
            with self.subTest(wire=wire):
                value=UpstreamCaptureTest().exchange(wire)
                self.assertIsNone(value['failure']); self.assertTrue(value['receipt']['complete'])
                self.assertEqual(value['body'],b'')
        for wire in (b'HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n',
                     b'HTTP/1.1 204 No Content\r\nTransfer-Encoding: chunked\r\n\r\n'):
            with self.subTest(wire=wire):
                value=UpstreamCaptureTest().exchange(wire)
                self.assertIsNotNone(value['failure']); self.assertFalse(value['receipt']['complete'])

    def test_no_body_with_prefetched_extra_bytes_is_rejected(self):
        for code in (b'204 No Content',b'304 Not Modified'):
            with self.subTest(code=code):
                value=UpstreamCaptureTest().exchange(b'HTTP/1.1 '+code+b'\r\n\r\nextra')
                self.assertIsNotNone(value['failure']);self.assertFalse(value['receipt']['complete'])
                self.assertEqual(value['body'],b'');self.assertEqual(value['fragment'],b'extra')

    def handler_failure(self, fail_receipt=False):
        listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1); calls=[]
        def provider():
            peer,_=listener.accept(); calls.append(1)
            with peer:
                file=peer.makefile('rb'); file.readline(); length=0
                while True:
                    line=file.readline()
                    if line == b'\r\n': break
                    if line.lower().startswith(b'content-length:'): length=int(line.split(b':',1)[1])
                file.read(length); body=b'{"id":"upload_1","status":"uploading"}'
                peer.sendall(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: '+str(len(body)).encode()+b'\r\n\r\n'+body)
                file.close()
            listener.close()
        provider_thread=threading.Thread(target=provider,daemon=True);provider_thread.start()
        class BrokenClose(http.client.HTTPConnection):
            def close(self): super().close(); raise RuntimeError('sensitive close detail')
        from contextlib import nullcontext
        real_open, real_fsync=os.open, os.fsync; receipt_keys=set()
        def tracked_open(path,flags,*args,**kwargs):
            fd=real_open(path,flags,*args,**kwargs)
            if str(path).endswith('.json') and Path(path).name.startswith('provider-'):
                stat=os.fstat(fd); receipt_keys.add((stat.st_dev,stat.st_ino))
            return fd
        def receipt_fail(fd):
            stat=os.fstat(fd)
            if (stat.st_dev,stat.st_ino) in receipt_keys: raise OSError('sensitive receipt failure')
            return real_fsync(fd)
        try:
            with tempfile.TemporaryDirectory() as root:
                sample=b'fixture'; digest=hashlib.sha256(sample).hexdigest()
                binding={'parser':'mineru','inputSha256':digest,'inputByteCount':len(sample),
                    'originalSourceSha256':'a'*64,'physicalPageNo':1,'profileSha256':'b'*64}
                worker=Worker({'workspace':root,'stages':{'mineru':{'readyUrl':f'http://127.0.0.1:{listener.getsockname()[1]}/health',
                    'timeoutSeconds':30,'providerOptions':{}}}},secret='fixture-secret')
                op=envelope('mineru',b'{}',binding,{'model':'fixture'}); directory=Path(root)/'gpu-operations'/op['operationId'];directory.mkdir()
                (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],
                    'stage':'mineru','status':'ready','bindingSha256':op['bindingSha256'],'deadlineUnixMs':int((time.time()+20)*1000)}))
                worker.active[op['operationId']]=(threading.Event(),threading.Event());worker.trees[op['operationId']]=object()
                lock=threading.Lock();session={'lock':lock};worker.sessions[op['operationId']]=session
                gateway=make_server(worker,('127.0.0.1',0)); thread=threading.Thread(target=gateway.serve_forever,daemon=True);thread.start()
                url=f'http://127.0.0.1:{gateway.server_port}/operations/{op["operationId"]}/provider/v1/uploads'
                def send():
                    req=urllib.request.Request(url,data=encoded({'sha256sum':digest,'bytes':len(sample)}),
                        headers={'Authorization':'Bearer fixture-secret','Content-Type':'application/json','X-Consense-Provider-Dispatch-Id':__import__('uuid').uuid4().hex})
                    return urllib.request.urlopen(req,timeout=2)
                try:
                    with patch.object(worker,'_listener_owned',return_value=True),patch.object(worker,'_connection_owned',return_value=True), \
                         (patch('fixed_gpu_worker.ProviderConnection',BrokenClose) if not fail_receipt else nullcontext()), \
                         (patch('fixed_gpu_worker.os.open',side_effect=tracked_open) if fail_receipt else nullcontext()), \
                         (patch('fixed_gpu_worker.os.fsync',side_effect=receipt_fail) if fail_receipt else nullcontext()):
                        with self.assertRaises(urllib.error.HTTPError) as first: send()
                        self.assertEqual(first.exception.code,503); self.assertEqual(first.exception.headers['X-Consense-Gateway-Error'],'1')
                        self.assertNotIn(b'sensitive',first.exception.read())
                        self.assertTrue(session['providerOutcomeUnknown']); self.assertEqual(session['uploadId'],'upload_1')
                        with self.assertRaises(urllib.error.HTTPError) as second: send()
                        self.assertEqual(second.exception.code,503)
                    self.assertEqual(len(calls),1); self.assertFalse(lock.locked())
                    self.assertEqual(worker.cancel(op['operationId'])['status'],'cancellation_requested')
                    self.assertTrue(worker.active[op['operationId']][0].is_set())
                finally: gateway.shutdown();gateway.server_close();thread.join(2)
        finally:
            provider_thread.join(2)
            if provider_thread.is_alive(): listener.close()

    def test_cleanup_runtime_error_is_generic_gateway_failure_and_blocks_replay(self):
        self.handler_failure()

    def test_receipt_failure_marks_session_unknown_and_blocks_replay(self):
        self.handler_failure(fail_receipt=True)

    def test_unknown_session_allows_only_exact_owned_job_delete(self):
        from email.message import Message
        class Reply:
            status=200; chunked=False; fp=None
            def __init__(self):
                self.data=encoded({'job_id':'job_1','status':'canceled'}); self.length=len(self.data)
                self.headers=Message(); self.headers['Content-Length']=str(self.length)
            def getheader(self,*args): return 'application/json'
            def read1(self,n): data,self.data=self.data[:n],self.data[n:];self.length-=len(data);return data
            def close(self): pass
        class Conn:
            sock=SimpleNamespace(shutdown=lambda *_: None, close=lambda: None)
            def __init__(self,*args,**kwargs): pass
            def connect(self): pass
            def request(self,*args,**kwargs): pass
            def getresponse(self): return Reply()
            def close(self): pass
        with tempfile.TemporaryDirectory() as root:
            sample=b'fixture';digest=hashlib.sha256(sample).hexdigest()
            binding={'parser':'mineru','inputSha256':digest,'inputByteCount':len(sample),
                'originalSourceSha256':'a'*64,'physicalPageNo':1,'profileSha256':'b'*64}
            worker=Worker({'workspace':root,'artifactStoreRoot':root,'stages':{'mineru':{'readyUrl':'http://127.0.0.1:28772/health',
                'timeoutSeconds':30,'providerOptions':{}}}},secret='fixture-secret')
            op=envelope('mineru',b'{}',binding,{'model':'fixture'});directory=Path(root)/'gpu-operations'/op['operationId'];directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op));(directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],
                'stage':'mineru','status':'ready','bindingSha256':op['bindingSha256'],'deadlineUnixMs':int((time.time()+20)*1000)}))
            worker.active[op['operationId']]=(threading.Event(),threading.Event());worker.trees[op['operationId']]=object()
            lock=threading.Lock();session={'lock':lock,'jobId':'job_1','providerOutcomeUnknown':True};worker.sessions[op['operationId']]=session
            with patch('native_session_worker.verify_transaction',return_value=None):
                with self.assertRaisesRegex(ValueError,'provider outcome is unknown'):
                    worker.finish(op['operationId'],{'transaction':{}})
            self.assertFalse(lock.locked());self.assertFalse(worker.active[op['operationId']][1].is_set())
            self.assertFalse((directory/'transaction.json').exists())
            with patch.object(worker,'_listener_owned',return_value=True),patch.object(worker,'_connection_owned',return_value=True), \
                 patch('fixed_gpu_worker.ProviderConnection',Conn):
                with self.assertRaises(ValueError): worker.provider_request(op['operationId'],'DELETE','/v1/parse/jobs/job_other',b'', 'application/json','a'*32)
                status,_,stream,_=worker.provider_request(op['operationId'],'DELETE','/v1/parse/jobs/job_1',b'', 'application/json','b'*32)
                stream.close()
            self.assertEqual(status,200);self.assertTrue(session['jobCancelled']);self.assertTrue(session['providerOutcomeUnknown'])
            self.assertFalse(lock.locked())

    def test_readiness_persist_error_exits_and_retains_owner(self):
        class Tree:
            pid=1
            def __init__(self,*args): self.group=Path(root);self.process=self;self.stopped=False
            def active(self): return 0 if self.stopped else 1
            def poll(self): return 0 if self.stopped else None
            def stop(self): self.stopped=True
            def close(self): pass
        class Connection:
            def __init__(self,*args,**kwargs): pass
            def request(self,*args): pass
            def getresponse(self): return type('Reply',(),{'status':200})()
            def close(self): pass
        with tempfile.TemporaryDirectory() as root:
            parent=Path(root)/'cgroup.procs';parent.write_bytes(b'')
            tool=Path(root)/'nvidia-smi'; tool.write_bytes(b'fixture')
            worker=Worker({'workspace':root,'containerId':'fixture','stages':{}, 'nvidiaSmi':str(tool),
                           'gpuUuid':'GPU-fixture'},secret='fixture-secret')
            sample=b'fixture';digest=hashlib.sha256(sample).hexdigest()
            binding={'parser':'mineru','inputSha256':digest,'inputByteCount':len(sample),
                'originalSourceSha256':'a'*64,'physicalPageNo':1,'profileSha256':'b'*64}
            op=envelope('mineru',b'{}',binding,{'model':'fixture'});directory=Path(root)/'gpu-operations'/op['operationId'];directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op));(directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],
                'stage':'mineru','status':'starting','bindingSha256':op['bindingSha256']}))
            worker.marker.write_bytes(b'occupied'); cancellation=threading.Event(); finished=threading.Event()
            worker.active[op['operationId']]=(cancellation,finished);worker.sessions[op['operationId']]={'lock':threading.Lock()}
            calls=[];real_set=worker._set
            def injected_set(directory,**values):
                if values.get('status')=='ready': calls.append('ready'); raise OSError('ready persistence failed')
                return real_set(directory,**values)
            boot={'cgroupRoot':root,'env':{},'runtimeAssets':[{'path':str(tool),'byteCount':7,
                  'sha256':hashlib.sha256(b'fixture').hexdigest()}]}
            profile={'mode':'session','profile':{},'readyUrl':'http://127.0.0.1:28772/health'}
            with patch.object(worker,'_validate_profile',return_value=(boot,2)),patch.object(worker,'_listener_owned',return_value=True), \
                 patch.object(worker,'_delegated_root',return_value=Path(root)), \
                 patch.object(worker,'_cgroup_pids',return_value={os.getpid()}), \
                 patch.object(worker,'_tree_pids',return_value=set()),patch.object(worker,'_gpu_pids',return_value=set()), \
                 patch.object(worker,'_set',side_effect=injected_set),patch('fixed_gpu_worker._LinuxTree',Tree), \
                 patch('fixed_gpu_worker.observe_running',return_value={'processes':[{'pid':123}]}), \
                 patch('fixed_gpu_worker.observe_release',side_effect=ValueError('release not proven')), \
                 patch('fixed_gpu_worker.http.client.HTTPConnection',Connection), \
                 patch('fixed_gpu_worker.os.geteuid',return_value=1,create=True),patch('fixed_gpu_worker.os.access',return_value=False):
                worker._execute(directory,op,profile,cancellation,finished)
            self.assertEqual(calls,['ready']); self.assertTrue(worker.marker.exists())
            self.assertEqual(json.loads((directory/'status.json').read_bytes())['release']['status'],'release_unknown')
