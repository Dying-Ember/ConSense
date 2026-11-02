import base64, hashlib, json, os, sys, tempfile, threading, unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
sys.path[:0]=[str(HERE),str(ROOT/'ai_consense_service/tools/offline_delivery'),str(ROOT/'ai_consense_service/tools/vetting_eval')]
import fixed_gpu_worker as module
from request_stage import encoded
RealThread=threading.Thread

class TokenAdmission(unittest.TestCase):
    def setup_case(self, directory):
        profile={'mode':'model-session','profile':{'adapter':'consense.model-session.v1'},'timeoutSeconds':10}
        worker=module.Worker({'workspace':directory,'stages':{'document-embedding':profile}},'fixture-secret')
        binding={'requestId':'1'*32,'sourceScopeSha256':'2'*64,'modelKind':'embedding','modelIdentity':{'id':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':4,'deadlineBudgetMs':5000,'maxBatches':2,'maxTotalBytes':4096}
        request={'stage':'document-embedding','binding':binding,'bodyBase64':base64.b64encode(b'{}').decode(),
                 'expectedProfileSha256':hashlib.sha256(encoded(profile['profile'])).hexdigest(),'expectedMode':'model-session'}
        return worker,request

    def exercise(self, mode, *, receipt_failure=False, marker_changed=False):
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            worker,request=self.setup_case(directory)
            fixture=Path(directory)/'directory-fsync-fixture';fixture.write_bytes(b'x')
            real_open,real_fdopen=os.open,os.fdopen; token_fds=set(); token_sync_fds=set()
            def opened(path,flags,*args,**kw):
                if Path(path)==Path(directory) and flags==os.O_RDONLY:return real_open(fixture,flags)
                if Path(path).name=='model-session.token' and mode=='open':raise OSError('injected token open')
                fd=real_open(path,flags,*args,**kw)
                if Path(path).name=='model-session.token':token_fds.add(fd);token_sync_fds.add(fd)
                return fd
            class Broken:
                def __init__(self,stream):self.stream=stream
                def __enter__(self):return self
                def __exit__(self,*a):return self.stream.__exit__(*a)
                def __getattr__(self,n):return getattr(self.stream,n)
                def write(self,text):
                    if marker_changed: worker.marker.write_bytes(encoded({'operationId':'f'*32,'state':'foreign'}))
                    if mode=='write':raise OSError('injected token write')
                    return self.stream.write(text[:1] if mode=='short' else text[:0] if mode=='zero' else text)
                def flush(self):
                    if mode=='flush':raise OSError('injected token flush')
                    return self.stream.flush()
            def fdopen(fd,*args,**kw):
                stream=real_fdopen(fd,*args,**kw)
                if fd in token_fds:token_fds.remove(fd);return Broken(stream)
                return stream
            def fsync(fd):
                if mode=='fsync' and fd in token_sync_fds:
                    token_sync_fds.remove(fd);raise OSError('injected token fsync')
            persist=module.persist
            def status_persist(path,value,**kwargs):
                failure_status=isinstance(value,dict) and 'startup' in value
                if receipt_failure=='after_replace' and failure_status:
                    persist(path,value,**kwargs);raise OSError('injected post-replace directory fsync failure')
                if receipt_failure and failure_status: raise OSError('injected failure status persist')
                return persist(path,value,**kwargs)
            stack.enter_context(patch.object(worker,'_validate_profile'))
            actor=stack.enter_context(patch.object(module.threading,'Thread'))
            if mode=='constructor': actor.side_effect=RuntimeError('injected constructor error')
            if mode=='launch': actor.return_value.start.side_effect=RuntimeError('injected ambiguous launch')
            stack.enter_context(patch.object(module.os,'open',side_effect=opened))
            stack.enter_context(patch.object(module.os,'fdopen',side_effect=fdopen))
            stack.enter_context(patch.object(module.os,'fsync',side_effect=fsync))
            stack.enter_context(patch.object(module,'persist',side_effect=status_persist))
            stack.enter_context(patch.object(module.secrets,'token_hex',return_value='a'*64))
            try:
                if mode=='full':result=worker.start(request); error=None
                else:
                    with self.assertRaises(Exception) as caught:worker.start(request)
                    error=caught.exception;result=None
                marker=json.loads(worker.marker.read_bytes()); directories=list(worker.operations.iterdir());self.assertEqual(len(directories),1)
                opid=directories[0].name; state=worker._record(opid)
                with self.assertRaisesRegex(ValueError,'owner exists'):worker.start(request)
                restarted,_=self.setup_case(directory); resumed=restarted._record(opid)
                self.assertTrue(worker.marker.exists())
                if marker_changed:self.assertEqual(marker['operationId'],'f'*32)
                else:self.assertEqual(marker['operationId'],opid)
                if mode=='full':
                    self.assertEqual(result['status'],'starting');self.assertEqual(actor.return_value.start.call_count,1)
                    self.assertEqual(len(worker.active),1);self.assertEqual(len(worker.sessions),1)
                elif mode=='launch':
                    self.assertEqual(actor.return_value.start.call_count,1)
                    if not receipt_failure:self.assertEqual(state['startup']['status'],'launch_unknown')
                    else:self.assertTrue(any('Startup failure status unavailable' in n for n in error.__notes__))
                    self.assertEqual(state['status'],'release_unknown');self.assertTrue(worker.active[opid][0].is_set())
                    self.assertEqual(len(worker.sessions),1)
                else:
                    if mode!='constructor':self.assertEqual(actor.return_value.start.call_count,0)
                    self.assertEqual(len(worker.active),0);self.assertEqual(len(worker.sessions),0)
                    if receipt_failure and receipt_failure!='after_replace':
                        self.assertEqual(state['status'],'release_unknown')
                        self.assertTrue(any('Startup failure status unavailable' in n for n in error.__notes__))
                    else:
                        self.assertEqual(state['status'],'failed');self.assertEqual(state['startup']['status'],'not_started')
                        self.assertEqual(resumed['startup']['status'],'not_started')
                        self.assertEqual(state['startup']['evidenceDurability'],'unknown')
                        self.assertEqual(resumed['startup']['evidenceDurability'],'unknown')
                        if receipt_failure:self.assertTrue(any('Startup failure status unavailable' in n for n in error.__notes__))
                    self.assertEqual(state['release']['status'],'release_unknown')
            finally:pass
    def test_full(self):self.exercise('full')
    def test_short(self):self.exercise('short')
    def test_zero(self):self.exercise('zero')
    def test_open_error(self):self.exercise('open')
    def test_write_error(self):self.exercise('write')
    def test_flush_error(self):self.exercise('flush')
    def test_fsync_error(self):self.exercise('fsync')
    def test_thread_constructor_error(self):self.exercise('constructor')
    def test_thread_launch_error_is_unknown(self):self.exercise('launch')
    def test_ambiguous_launch_without_failure_receipt_stays_unknown(self):self.exercise('launch',receipt_failure=True)
    def test_failure_receipt_unavailable_preserves_owner(self):self.exercise('short',receipt_failure=True)
    def test_post_replace_durability_is_unknown(self):self.exercise('short',receipt_failure='after_replace')
    def test_changed_owner_is_never_deleted(self):self.exercise('short',marker_changed=True)

    def test_concurrent_workers_share_owner_gate(self):
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            first,request=self.setup_case(directory);second,_=self.setup_case(directory)
            fixture=Path(directory)/'directory-fsync-fixture';fixture.write_bytes(b'x');real_open=os.open
            def opened(path,flags,*args,**kwargs):
                if Path(path)==Path(directory) and flags==os.O_RDONLY:return real_open(fixture,flags)
                return real_open(path,flags,*args,**kwargs)
            stack.enter_context(patch.object(first,'_validate_profile'));stack.enter_context(patch.object(second,'_validate_profile'))
            actor=stack.enter_context(patch.object(module.threading,'Thread'))
            stack.enter_context(patch.object(module.os,'open',side_effect=opened));stack.enter_context(patch.object(module.os,'fsync'))
            gate=threading.Barrier(2);outcomes=[]
            def start(worker):
                gate.wait()
                try:outcomes.append(('started',worker.start(request)))
                except Exception as e:outcomes.append(('error',type(e).__name__))
            runners=[RealThread(target=start,args=(w,)) for w in (first,second)]
            for runner in runners:runner.start()
            for runner in runners:runner.join(5);self.assertFalse(runner.is_alive())
            self.assertEqual(sorted(value[0] for value in outcomes),['error','started'])
            self.assertEqual(actor.return_value.start.call_count,1);self.assertTrue(first.marker.exists())

    def test_checked_failure_status_rejects_short_write(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'status.json';path.write_bytes(b'original')
            original=module.os.fdopen
            class Short:
                def __init__(self,s):self.s=s
                def __enter__(self):return self
                def __exit__(self,*args):return self.s.__exit__(*args)
                def __getattr__(self,n):return getattr(self.s,n)
                def write(self,data):return self.s.write(data[:1])
            with patch.object(module.os,'fdopen',side_effect=lambda fd,*args,**kw:Short(original(fd,*args,**kw))):
                with self.assertRaisesRegex(OSError,'short persisted record write'):module.persist(path,{'status':'failed'})
            self.assertEqual(path.read_bytes(),b'original')
    def test_thread_can_run_before_start_raises(self):
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            worker,request=self.setup_case(directory)
            fixture=Path(directory)/'directory-fsync-fixture';fixture.write_bytes(b'x');real_open=os.open
            def opened(path,flags,*args,**kwargs):
                if Path(path)==Path(directory) and flags==os.O_RDONLY:return real_open(fixture,flags)
                return real_open(path,flags,*args,**kwargs)
            entered=threading.Event();finished=threading.Event();threads=[]
            def execution(directory,operation,profile,cancellation,done):
                entered.set();cancellation.wait(3);finished.set()
            class RaisesAfterLaunch:
                def __init__(self,target,args,daemon):self.target=target;self.args=args
                def start(self):
                    thread=RealThread(target=self.target,args=self.args,daemon=True);threads.append(thread);thread.start()
                    if not entered.wait(2):raise RuntimeError('fixture thread did not start')
                    raise RuntimeError('injected error after thread began')
            stack.enter_context(patch.object(worker,'_validate_profile'));stack.enter_context(patch.object(worker,'_execute',side_effect=execution))
            stack.enter_context(patch.object(module.threading,'Thread',RaisesAfterLaunch))
            stack.enter_context(patch.object(module.os,'open',side_effect=opened));stack.enter_context(patch.object(module.os,'fsync'))
            with self.assertRaisesRegex(RuntimeError,'after thread began'):worker.start(request)
            self.assertTrue(finished.wait(2))
            for thread in threads:thread.join(2);self.assertFalse(thread.is_alive())
            operation_id=next(iter(worker.active));self.assertTrue(worker.active[operation_id][0].is_set())
            self.assertEqual(worker._record(operation_id)['status'],'release_unknown');self.assertTrue(worker.marker.exists())
if __name__=='__main__':unittest.main()
