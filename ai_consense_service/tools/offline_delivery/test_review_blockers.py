"""Independent-review regression cases against local loopback only."""
import http.client
import os
import unittest
from pathlib import Path
from unittest.mock import patch
from test_upstream_minimal import UpstreamCaptureTest


class ReviewBlockers(unittest.TestCase):
    def test_reserved_descriptor_close_failure_preserves_primary_or_fails_success(self):
        for wire,primary_type in ((b'HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc',OSError),
                                  (b'HTTP/1.0 503 Err\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n',ValueError)):
            with self.subTest(primary=primary_type.__name__):
                real_open,real_close=os.open,os.close; selected=set(); fired=[]
                def opened(path,flags,*args,**kwargs):
                    fd=real_open(path,flags,*args,**kwargs)
                    if str(path).endswith('.bin'): selected.add(fd)
                    return fd
                def close(fd):
                    if fd in selected:
                        selected.remove(fd); real_close(fd); fired.append(True); raise OSError('injected close failure')
                    return real_close(fd)
                with patch('fixed_gpu_worker.os.open',side_effect=opened),patch('fixed_gpu_worker.os.close',side_effect=close):
                    value=UpstreamCaptureTest().exchange(wire)
                self.assertTrue(fired); self.assertIsInstance(value['failure'],primary_type)
                if primary_type is ValueError: self.assertEqual(str(value['failure']),'invalid chunk size or extension')
                self.assertEqual(value['cleanup']['cleanupStatus'],'unknown')
                self.assertEqual(value['cleanup']['cleanupErrorTypes'],['OSError'])
                self.assertTrue(value['outcome_unknown'])

    def test_capture_exists_fault_does_not_mask_invalid_chunk_or_lose_receipt(self):
        import inspect
        original=Path.exists
        def exists(path):
            if path.name.startswith('provider-') and path.suffix=='.bin' and inspect.currentframe().f_back.f_code.co_filename.endswith('fixed_gpu_worker.py'):
                raise OSError('injected capture exists fault')
            return original(path)
        with patch.object(Path,'exists',exists):
            value=UpstreamCaptureTest().exchange(b'HTTP/1.0 503 Err\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n')
        self.assertIsInstance(value['failure'],ValueError)
        self.assertEqual(str(value['failure']),'invalid chunk size or extension')
        self.assertFalse(value['receipt']['complete']); self.assertEqual(value['receipt']['bodyDurability'],'synced')
        self.assertEqual(value['fragment'],b'Z\r\n')

    def test_capture_stat_fault_retains_primary_error_and_unknown_size(self):
        import inspect
        original=os.fstat
        def stat(fd):
            frame=inspect.currentframe().f_back
            if frame.f_code.co_name=='provider_request' and frame.f_code.co_filename.endswith('fixed_gpu_worker.py'):
                raise OSError('injected capture stat fault')
            return original(fd)
        with patch('fixed_gpu_worker.os.fstat',stat):
            value=UpstreamCaptureTest().exchange(b'HTTP/1.0 503 Err\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n')
        self.assertIsInstance(value['failure'],ValueError)
        self.assertEqual(str(value['failure']),'invalid chunk size or extension')
        self.assertIsNone(value['receipt']['bytes']); self.assertEqual(value['receipt']['bodyDurability'],'unknown')
        self.assertTrue(any('size unknown' in n for n in value['failure'].__notes__))

    def test_chunk_extension_and_trailer_grammar(self):
        for valid, frame in (
            (True, b'1 ;x=y\r\na\r\n0\r\n\r\n'),
            (True, b'1;foo = "a\\\"b";x\r\na\r\n0\r\nX-Trace: ok\r\n\r\n'),
            (False, b'1;=bad\r\na\r\n0\r\n\r\n'),
            (False, b'1;x="unterminated\r\na\r\n0\r\n\r\n'),
            (False, b'1\r\na\r\n0\r\nnot-a-header\r\n\r\n'),
            (False, b'1\r\na\r\n0\r\nBad : value\r\n\r\n'),
            (False, b'1\r\na\r\n0\r\nX: a\x00b\r\n\r\n'),
        ):
            with self.subTest(frame=frame):
                value = UpstreamCaptureTest().exchange(b'HTTP/1.0 503 Error\r\nTransfer-Encoding: chunked\r\n\r\n' + frame)
                self.assertEqual(value['receipt']['complete'], valid)
                if valid:
                    self.assertIsNone(value['failure']); self.assertEqual(value['body'],b'a')
                else: self.assertIsNotNone(value['failure'])

    def test_cleanup_failure_has_failure_receipt(self):
        class BrokenClose(http.client.HTTPConnection):
            def close(self):
                super().close(); raise OSError('injected connection close')
        with patch('fixed_gpu_worker.ProviderConnection', BrokenClose):
            value=UpstreamCaptureTest().exchange(b'HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc')
        self.assertIsInstance(value['failure'],OSError)
        self.assertEqual(value['receipt']['errorType'],'OSError')
        self.assertIn('OSError',value['receipt']['cleanupErrorTypes'])
        self.assertEqual(value['body'],b'abc')

    def partial_write_exchange(self, fail_fragment=False, fail_receipt=False, fail_close=False, prior=False, fail_fragment_sync=False):
        real_open, real_fdopen = os.open, os.fdopen; selected={}
        class BadWriter:
            def __init__(self,stream): self.stream=stream; self.writes=0
            def __enter__(self): return self
            def __exit__(self,*args):
                result=self.stream.__exit__(*args)
                if fail_close: raise RuntimeError('body stream close failed')
                return result
            def write(self,data):
                self.writes+=1
                if prior and self.writes == 1: return self.stream.write(data)
                self.stream.write(data[:1]); raise OSError('partial body write')
            def __getattr__(self,name): return getattr(self.stream,name)
        def tracked(path,flags,*args,**kwargs):
            fd=real_open(path,flags,*args,**kwargs)
            if str(path).endswith(('.bin','.body-fragment','.json')):
                stat=os.fstat(fd); selected[(stat.st_dev,stat.st_ino)]=str(path)
            return fd
        class FailedWrite:
            def __init__(self,stream,kind): self.stream=stream; self.kind=kind
            def __enter__(self): return self
            def __exit__(self,*args): return self.stream.__exit__(*args)
            def write(self,data):
                if self.kind=='fragment' or b'"errorType"' in data: raise OSError('injected evidence failure')
                return self.stream.write(data)
            def __getattr__(self,name): return getattr(self.stream,name)
        def wrapped(fd,*args,**kwargs):
            stream=real_fdopen(fd,*args,**kwargs)
            stat=os.fstat(fd); path=selected.get((stat.st_dev,stat.st_ino),'')
            if path.endswith('.bin') and args and args[0]=='wb': return BadWriter(stream)
            if fail_fragment and path.endswith('.body-fragment'): return FailedWrite(stream,'fragment')
            if fail_receipt and path.endswith('.json'): return FailedWrite(stream,'receipt')
            return stream
        import fixed_gpu_worker
        from contextlib import nullcontext
        real_read1=http.client.HTTPResponse.read1; real_fsync=os.fsync; calls=[]
        def fsync(fd):
            calls.append(fd)
            if len(calls)==2 + (os.name == 'posix'): raise OSError('fragment fsync failed')
            return real_fsync(fd)
        with patch('fixed_gpu_worker.os.open',side_effect=tracked), patch('fixed_gpu_worker.os.fdopen',side_effect=wrapped), \
             (patch.object(http.client.HTTPResponse,'read1',lambda response,n:real_read1(response,min(n,2))) if prior else nullcontext()), \
             (patch('fixed_gpu_worker.os.fsync',side_effect=fsync) if fail_fragment_sync else nullcontext()):
            return UpstreamCaptureTest().exchange(b'HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc',
                allow_missing_receipt=fail_receipt)

    def test_failed_partial_write_retains_whole_chunk_with_overlap_identity(self):
        value=self.partial_write_exchange()
        receipt=value['receipt']; ref=receipt['bodyWriteFragment']
        self.assertIsInstance(value['failure'],OSError)
        self.assertEqual(value['body'],b'a'); self.assertEqual(value['write_fragment'],b'abc')
        self.assertEqual(ref['intendedOffset'],0); self.assertTrue(ref['mayOverlapBody'])
        self.assertEqual(receipt['bodyDurability'],'unknown'); self.assertEqual(receipt['writtenBodyBytes'],0)
        self.assertFalse(receipt['complete'])

    def test_fragment_capture_failure_is_unknown_and_keeps_primary_error(self):
        value=self.partial_write_exchange(fail_fragment=True); receipt=value['receipt']
        self.assertEqual(str(value['failure']),'partial body write')
        self.assertEqual(receipt['bodyDurability'],'unknown')
        self.assertEqual(receipt['bodyWriteFragmentDurability'],'unknown')
        self.assertEqual(receipt['bodyWriteFragmentErrorType'],'OSError')
        self.assertEqual(receipt['bodyWriteFragmentBytes'],3)
        self.assertEqual(receipt['bodyWriteFragmentIntendedOffset'],0)
        self.assertNotIn('bodyWriteFragment',receipt)

    def test_fragment_fsync_failure_is_unknown(self):
        value=self.partial_write_exchange(fail_fragment_sync=True); receipt=value['receipt']
        self.assertEqual(str(value['failure']),'partial body write')
        self.assertEqual(receipt['bodyWriteFragmentDurability'],'unknown')
        self.assertEqual(receipt['bodyWriteFragmentErrorType'],'OSError')
        self.assertNotIn('bodyWriteFragment',receipt)

    def test_offset_tracks_prior_successful_body_write(self):
        value=self.partial_write_exchange(prior=True); receipt=value['receipt']; ref=receipt['bodyWriteFragment']
        self.assertEqual(receipt['writtenBodyBytes'],2); self.assertEqual(ref['intendedOffset'],2)
        self.assertEqual(value['body'],b'abc'); self.assertEqual(value['write_fragment'],b'c')
        self.assertTrue(ref['mayOverlapBody']); self.assertEqual(receipt['bodyDurability'],'unknown')

    def test_receipt_failure_keeps_primary_error_and_reports_unavailable(self):
        value=self.partial_write_exchange(fail_receipt=True)
        self.assertEqual(str(value['failure']),'partial body write')
        self.assertIsNone(value['receipt'])
        self.assertTrue(any('Failure receipt unavailable' in note for note in value['failure'].__notes__))

    def test_body_close_failure_does_not_mask_write_failure(self):
        value=self.partial_write_exchange(fail_close=True)
        self.assertEqual(str(value['failure']),'partial body write')
        self.assertEqual(value['receipt']['bodyWriteErrorType'],'OSError')
        self.assertEqual(value['receipt']['bodyStreamCleanupErrorType'],'RuntimeError')
        self.assertEqual(value['write_fragment'],b'abc')
        self.assertTrue(any('Body stream cleanup failed' in note for note in value['failure'].__notes__))

    def test_connection_cleanup_failure_does_not_mask_write_failure(self):
        class BrokenClose(http.client.HTTPConnection):
            def close(self):
                super().close(); raise RuntimeError('connection close failed')
        with patch('fixed_gpu_worker.ProviderConnection',BrokenClose):
            value=self.partial_write_exchange()
        self.assertEqual(str(value['failure']),'partial body write')
        self.assertIn('RuntimeError',value['receipt']['cleanupErrorTypes'])
        self.assertEqual(value['write_fragment'],b'abc')
