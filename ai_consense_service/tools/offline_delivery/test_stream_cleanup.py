import json, os, tempfile, threading, unittest
from pathlib import Path
from unittest.mock import patch
import fixed_gpu_worker as module
from test_model_token_prelaunch import TokenAdmission
from request_stage import encoded, envelope

class StreamCleanupTest(unittest.TestCase):
    def test_failed_enter_closes_stream_and_preserves_primary(self):
        class Fault:
            def __enter__(self): raise OSError('PRIMARY_ENTER')
            def __exit__(self,*args): raise AssertionError('unentered stream cannot exit')
            def close(self): raise RuntimeError('SECONDARY_CLOSE')
        with self.assertRaisesRegex(OSError,'PRIMARY_ENTER') as caught:
            with module.preserve_stream_error(Fault()): self.fail('not entered')
        self.assertTrue(any('Stream cleanup unknown: RuntimeError' in n for n in caught.exception.__notes__))

    def test_persist_primary_write_or_sync_survives_close(self):
        for phase in ('write','fsync'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as root:
                target=Path(root)/'status.json'; target.write_bytes(b'old'); real_fdopen=os.fdopen; real_fsync=os.fsync
                class Fault:
                    def __init__(self,stream): self.stream=stream
                    def __enter__(self): return self
                    def __exit__(self,*args): self.stream.__exit__(*args); raise RuntimeError('SECONDARY_CLOSE')
                    def write(self,data):
                        if phase=='write': raise OSError('PRIMARY_WRITE')
                        return self.stream.write(data)
                    def __getattr__(self,name): return getattr(self.stream,name)
                def fsync(fd):
                    if phase=='fsync': raise OSError('PRIMARY_FSYNC')
                    return real_fsync(fd)
                with patch.object(module.os,'fdopen',side_effect=lambda fd,*a,**kw:Fault(real_fdopen(fd,*a,**kw))),patch.object(module.os,'fsync',side_effect=fsync):
                    with self.assertRaisesRegex(OSError,'PRIMARY_'+phase.upper()) as caught: module.persist(target,{'new':True})
                self.assertTrue(any('Stream cleanup unknown: RuntimeError' in n for n in caught.exception.__notes__))
                self.assertEqual(target.read_bytes(),b'old'); self.assertTrue((Path(root)/'.status.json.tmp').exists())

    def test_owner_and_token_write_close_fault_keep_primary_and_owner(self):
        for target in ('gpu-owner.json','model-session.token'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as root:
                worker,request=TokenAdmission().setup_case(root); real_open,real_fdopen=os.open,os.fdopen; selected=set()
                class Fault:
                    def __init__(self,stream): self.stream=stream
                    def __enter__(self): return self
                    def __exit__(self,*args): self.stream.__exit__(*args); raise RuntimeError('SECONDARY_CLOSE')
                    def write(self,data): raise OSError('PRIMARY_WRITE')
                    def __getattr__(self,name): return getattr(self.stream,name)
                def opened(path,flags,*args,**kwargs):
                    fd=real_open(path,flags,*args,**kwargs)
                    if Path(path).name==target: selected.add(fd)
                    return fd
                def fdopen(fd,*args,**kwargs):
                    stream=real_fdopen(fd,*args,**kwargs)
                    if fd in selected: selected.remove(fd); return Fault(stream)
                    return stream
                with patch.object(worker,'_validate_profile'),patch.object(module,'sync_directory'),patch.object(module.threading,'Thread') as thread,patch.object(module.os,'open',side_effect=opened),patch.object(module.os,'fdopen',side_effect=fdopen):
                    with self.assertRaisesRegex(OSError,'PRIMARY_WRITE') as caught: worker.start(request)
                self.assertTrue(any('Stream cleanup unknown: RuntimeError' in n for n in caught.exception.__notes__))
                self.assertTrue(worker.marker.exists()); self.assertEqual(thread.return_value.start.call_count,0); self.assertFalse(worker.active)
                operations=list(worker.operations.iterdir())
                if operations:
                    status=json.loads((operations[0]/'status.json').read_bytes())
                    self.assertEqual((status['startup']['errorType'],status['startup']['cleanupStatus'],status['release']['status']),('OSError','unknown','release_unknown'))

    def test_execute_log_close_does_not_mask_execution_error(self):
        with tempfile.TemporaryDirectory() as root:
            (Path(root)/'cgroup.procs').write_bytes(b'')
            worker=module.Worker({'workspace':root,'containerId':'fixture','stages':{}},secret='fixture-secret')
            op=envelope('mineru',b'{}',{'inputSha256':'a'*64,'inputByteCount':1},{'adapter':'fixture'})
            directory=worker.operations/op['operationId']; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op)); (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],'stage':'mineru','status':'starting','bindingSha256':op['bindingSha256']}))
            worker.marker.write_bytes(b'occupied'); cancel,finished=threading.Event(),threading.Event(); worker.active[op['operationId']]=(cancel,finished); worker.sessions[op['operationId']]={'lock':threading.Lock()}
            real_open,real_fdopen=os.open,os.fdopen; selected=set()
            class Fault:
                def __init__(self,stream): self.stream=stream
                def __enter__(self): return self
                def __exit__(self,*args): self.stream.__exit__(*args); raise RuntimeError('SECONDARY_LOG_CLOSE')
                def __getattr__(self,name): return getattr(self.stream,name)
            def opened(path,flags,*args,**kwargs):
                fd=real_open(path,flags,*args,**kwargs)
                if Path(path).name=='stdout.log': selected.add(fd)
                return fd
            def fdopen(fd,*args,**kwargs):
                stream=real_fdopen(fd,*args,**kwargs)
                if fd in selected: selected.remove(fd); return Fault(stream)
                return stream
            profile={'mode':'session','readyUrl':'http://127.0.0.1:28772/health'}; boot={'cgroupRoot':root,'env':{}}
            with patch.object(worker,'_validate_profile',return_value=(boot,2)),patch.object(worker,'_delegated_root',return_value=Path(root)),patch.object(module,'_LinuxTree',side_effect=OSError('PRIMARY_EXECUTION')),patch.object(module.os,'open',side_effect=opened),patch.object(module.os,'fdopen',side_effect=fdopen),patch.object(module.os,'geteuid',return_value=1,create=True),patch.object(module.os,'access',return_value=False):
                worker._execute(directory,op,profile,cancel,finished)
            status=json.loads((directory/'status.json').read_bytes())
            self.assertIn('OSError: PRIMARY_EXECUTION',status['failure']); self.assertIn('stream cleanup unknown',status['failure'])
            self.assertEqual(status['release']['status'],'release_unknown'); self.assertTrue(worker.marker.exists())

if __name__=='__main__': unittest.main()
