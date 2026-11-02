import json, os, unittest
from unittest.mock import patch
import fixed_gpu_worker
import test_dispatch_binding as fixtures
from test_upstream_minimal import UpstreamCaptureTest

class DescriptorCleanupTest(unittest.TestCase):
    def test_fdopen_and_duplicate_close_failure_keep_primary_and_cleanup_unknown(self):
        for suffix in ('.reservation','.bin'):
            with self.subTest(suffix=suffix):
                fixture=fixtures.Contract('test_capability'); fixture.setUp()
                try:
                    ident='4'*32; original_open=os.open; original_fdopen=os.fdopen; original_close=os.close
                    identities=set(); bad=set(); fired=[]
                    def opened(path,flags,*args,**kwargs):
                        fd=original_open(path,flags,*args,**kwargs)
                        if str(path).endswith('provider-'+ident+suffix):
                            st=os.fstat(fd); identities.add((st.st_dev,st.st_ino))
                        return fd
                    def fdopen(fd,*args,**kwargs):
                        st=os.fstat(fd)
                        if (st.st_dev,st.st_ino) in identities and not fired:
                            fired.append(True); bad.add(fd); raise OSError('PRIMARY_FDOPEN')
                        return original_fdopen(fd,*args,**kwargs)
                    def close(fd):
                        if fd in bad:
                            bad.remove(fd); original_close(fd); raise RuntimeError('SECONDARY_CLOSE')
                        return original_close(fd)
                    with patch('fixed_gpu_worker.os.open',side_effect=opened),patch('fixed_gpu_worker.os.fdopen',side_effect=fdopen),patch('fixed_gpu_worker.os.close',side_effect=close):
                        with self.assertRaisesRegex(OSError,'PRIMARY_FDOPEN') as caught:
                            fixture.worker.provider_request(fixture.op['operationId'],'GET','/v1/parse/jobs/job_1',b'','application/json',ident)
                    self.assertTrue(fired); self.assertFalse(bad)
                    self.assertTrue(any('descriptor cleanup unknown: RuntimeError' in n for n in caught.exception.__notes__))
                    cleanup=json.loads((fixture.dir/('provider-'+ident+'.cleanup.json')).read_bytes())
                    self.assertEqual(cleanup['cleanupStatus'],'unknown'); self.assertIn('RuntimeError',cleanup['cleanupErrorTypes'])
                    self.assertEqual(len(fixtures.Upstream.calls),0 if suffix=='.reservation' else 1)
                    if suffix=='.bin':
                        failure=json.loads((fixture.dir/('provider-'+ident+'.json')).read_bytes())
                        self.assertEqual((failure['errorType'],failure['providerOutcome']),('OSError','unknown'))
                finally: fixture.tearDown()

    def test_output_seek_and_close_combined_fault_keep_seek_and_close_handle(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='5'*32; original_open=os.open; original_fdopen=os.fdopen; identity=[]; read_count=[]; observed=[]
            class Fault:
                def __init__(self,stream): self.stream=stream
                def seek(self,*args,**kwargs): raise OSError('PRIMARY_SEEK')
                def close(self): self.stream.close(); raise RuntimeError('SECONDARY_CLOSE')
                def __getattr__(self,name): return getattr(self.stream,name)
            def opened(path,flags,*args,**kwargs):
                fd=original_open(path,flags,*args,**kwargs)
                if str(path).endswith('provider-'+ident+'.bin'):
                    st=os.fstat(fd); identity.append((st.st_dev,st.st_ino))
                return fd
            def fdopen(fd,mode='r',*args,**kwargs):
                stream=original_fdopen(fd,mode,*args,**kwargs); st=os.fstat(fd)
                if identity and (st.st_dev,st.st_ino)==identity[0] and mode=='rb':
                    read_count.append(True)
                    if len(read_count)==2:
                        observed.append(Fault(stream)); return observed[-1]
                return stream
            with patch('fixed_gpu_worker.os.open',side_effect=opened),patch('fixed_gpu_worker.os.fdopen',side_effect=fdopen):
                with self.assertRaisesRegex(OSError,'PRIMARY_SEEK') as caught:
                    fixture.worker.provider_request(fixture.op['operationId'],'GET','/v1/parse/jobs/job_1',b'','application/json',ident)
            self.assertEqual(len(observed),1); self.assertTrue(observed[0].closed)
            self.assertTrue(any('Capture stream cleanup unknown: RuntimeError' in n for n in caught.exception.__notes__))
            cleanup=json.loads((fixture.dir/('provider-'+ident+'.cleanup.json')).read_bytes())
            self.assertEqual(cleanup['cleanupStatus'],'unknown'); self.assertEqual(cleanup['cleanupErrorTypes'],['RuntimeError'])
            failure=json.loads((fixture.dir/('provider-'+ident+'.failure.json')).read_bytes())
            self.assertEqual((failure['errorType'],failure['providerOutcome']),('OSError','unknown'))
            self.assertEqual(len(fixtures.Upstream.calls),1)
        finally: fixture.tearDown()

    def test_directory_fsync_and_close_fault_preserve_primary(self):
        with patch('fixed_gpu_worker.os.open',return_value=991),patch('fixed_gpu_worker.os.fsync',side_effect=OSError('PRIMARY_FSYNC')),patch('fixed_gpu_worker.os.close',side_effect=RuntimeError('SECONDARY_CLOSE')):
            with self.assertRaisesRegex(OSError,'PRIMARY_FSYNC') as caught: fixed_gpu_worker.sync_directory('synthetic')
        self.assertTrue(any('Directory descriptor cleanup unknown: RuntimeError' in n for n in caught.exception.__notes__))

    def test_fdopen_owned_failure_and_close_fault_preserve_primary(self):
        with patch('fixed_gpu_worker.os.fdopen',side_effect=OSError('PRIMARY_FDOPEN')),patch('fixed_gpu_worker.os.close',side_effect=RuntimeError('SECONDARY_CLOSE')) as close:
            with self.assertRaisesRegex(OSError,'PRIMARY_FDOPEN') as caught: fixed_gpu_worker.fdopen_owned(991,'wb')
        close.assert_called_once_with(991)
        self.assertTrue(any('Descriptor cleanup unknown: RuntimeError' in n for n in caught.exception.__notes__))

    def test_body_read_or_fsync_error_and_close_fault_preserve_primary(self):
        for failure_kind in ('chunk','fsync'):
            with self.subTest(failure_kind=failure_kind):
                original_open,original_fdopen,original_fsync=os.open,os.fdopen,os.fsync; keys=set(); calls=[]
                class CloseFault:
                    def __init__(self,stream): self.stream=stream
                    def __enter__(self): return self
                    def __exit__(self,*args): self.stream.__exit__(*args); raise RuntimeError('SECONDARY_BODY_CLOSE')
                    def __getattr__(self,name): return getattr(self.stream,name)
                def opened(path,flags,*args,**kwargs):
                    fd=original_open(path,flags,*args,**kwargs)
                    if str(path).endswith('provider-'+'a'*32+'.bin'):
                        st=os.fstat(fd); keys.add((st.st_dev,st.st_ino))
                    return fd
                def fdopen(fd,mode='r',*args,**kwargs):
                    stream=original_fdopen(fd,mode,*args,**kwargs); st=os.fstat(fd)
                    return CloseFault(stream) if (st.st_dev,st.st_ino) in keys and mode=='wb' else stream
                def fsync(fd):
                    calls.append(fd)
                    if failure_kind=='fsync' and len(calls)==2 + (os.name=='posix'): raise OSError('PRIMARY_FSYNC')
                    return original_fsync(fd)
                wire=(b'HTTP/1.0 503 Err\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n' if failure_kind=='chunk'
                      else b'HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc')
                with patch('fixed_gpu_worker.os.open',side_effect=opened),patch('fixed_gpu_worker.os.fdopen',side_effect=fdopen),patch('fixed_gpu_worker.os.fsync',side_effect=fsync):
                    value=UpstreamCaptureTest().exchange(wire)
                expected=ValueError if failure_kind=='chunk' else OSError
                self.assertIsInstance(value['failure'],expected)
                self.assertEqual(str(value['failure']),'invalid chunk size or extension' if failure_kind=='chunk' else 'PRIMARY_FSYNC')
                self.assertEqual(value['receipt']['errorType'],expected.__name__)
                self.assertEqual(value['receipt']['bodyStreamCleanupErrorType'],'RuntimeError')
                self.assertEqual(value['receipt']['bodyDurability'],'unknown')

    def test_capture_read_and_close_fault_preserve_primary(self):
        fixture=fixtures.Contract('test_capability'); fixture.setUp()
        try:
            ident='6'*32; original_open=os.open; original_fdopen=os.fdopen; identities=set(); observed=[]
            class Fault:
                def __init__(self,stream): self.stream=stream
                def __enter__(self): return self
                def __exit__(self,*args): self.stream.__exit__(*args); raise RuntimeError('SECONDARY_READ_CLOSE')
                def read(self,*args): raise OSError('PRIMARY_READ')
                def __getattr__(self,name): return getattr(self.stream,name)
            def opened(path,flags,*args,**kwargs):
                fd=original_open(path,flags,*args,**kwargs)
                if str(path).endswith('provider-'+ident+'.bin'):
                    st=os.fstat(fd); identities.add((st.st_dev,st.st_ino))
                return fd
            def fdopen(fd,mode='r',*args,**kwargs):
                stream=original_fdopen(fd,mode,*args,**kwargs); st=os.fstat(fd)
                if (st.st_dev,st.st_ino) in identities and mode=='rb': observed.append(Fault(stream)); return observed[-1]
                return stream
            with patch('fixed_gpu_worker.os.open',side_effect=opened),patch('fixed_gpu_worker.os.fdopen',side_effect=fdopen):
                with self.assertRaisesRegex(OSError,'PRIMARY_READ') as caught:
                    fixture.worker.provider_request(fixture.op['operationId'],'GET','/v1/parse/jobs/job_1',b'','application/json',ident)
            self.assertTrue(observed[0].closed)
            self.assertTrue(any('Capture read stream cleanup unknown: RuntimeError' in n for n in caught.exception.__notes__))
            info=json.loads((fixture.dir/('provider-'+ident+'.json')).read_bytes())
            self.assertEqual((info['errorType'],info['providerOutcome']),('OSError','unknown'))
            cleanup=json.loads((fixture.dir/('provider-'+ident+'.cleanup.json')).read_bytes())
            self.assertEqual(cleanup['cleanupStatus'],'unknown')
        finally: fixture.tearDown()

    def test_evidence_write_and_close_fault_retains_write_error_identity(self):
        original_open,original_fdopen=os.open,os.fdopen; identities=set()
        class Fault:
            def __init__(self,stream): self.stream=stream
            def __enter__(self): return self
            def __exit__(self,*args): self.stream.__exit__(*args); raise RuntimeError('SECONDARY_EVIDENCE_CLOSE')
            def write(self,data): raise OSError('PRIMARY_EVIDENCE_WRITE')
            def __getattr__(self,name): return getattr(self.stream,name)
        def opened(path,flags,*args,**kwargs):
            fd=original_open(path,flags,*args,**kwargs)
            if str(path).endswith('.wire'):
                st=os.fstat(fd); identities.add((st.st_dev,st.st_ino))
            return fd
        def fdopen(fd,mode='r',*args,**kwargs):
            stream=original_fdopen(fd,mode,*args,**kwargs); st=os.fstat(fd)
            return Fault(stream) if (st.st_dev,st.st_ino) in identities and mode=='wb' else stream
        with patch('fixed_gpu_worker.os.open',side_effect=opened),patch('fixed_gpu_worker.os.fdopen',side_effect=fdopen):
            value=UpstreamCaptureTest().exchange(b'HTTP/1.0 503 Err\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n')
        self.assertIsInstance(value['failure'],ValueError)
        self.assertEqual(value['receipt']['wireCaptureErrorType'],'OSError')
        self.assertEqual(value['cleanup']['cleanupStatus'],'unknown')
        self.assertIn('RuntimeError',value['cleanup']['cleanupErrorTypes'])

if __name__=='__main__': unittest.main()
