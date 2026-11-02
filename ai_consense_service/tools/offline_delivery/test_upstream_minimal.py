"""Synthetic loopback provider capture, never a real OCR or GPU invocation."""
import base64
from contextlib import nullcontext
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from fixed_gpu_worker import Worker
from request_stage import encoded, envelope


class UpstreamCaptureTest(unittest.TestCase):
    def exchange(self, wire, *, hold=False, cancel=False, close_error=False, fragmented=False, max_body=None, fsync_fail_at=None, allow_missing_receipt=False):
        listener = socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1)
        release=threading.Event(); sent=threading.Event(); cancelled=threading.Event(); failures=[]
        def serve():
            peer,_=listener.accept()
            try:
                request=peer.makefile('rb'); request.readline(); length=0
                while True:
                    line=request.readline()
                    if line==b'\r\n': break
                    if not line: raise EOFError('short headers')
                    if line.lower().startswith(b'content-length:'): length=int(line.split(b':',1)[1])
                request.read(length)
                if fragmented:
                    for i in range(0,len(wire),3): peer.sendall(wire[i:i+3]);time.sleep(.001)
                else: peer.sendall(wire)
                sent.set()
                if hold: release.wait(3)
                request.close()
            except BaseException as error: failures.append(error)
            finally: peer.close(); listener.close()
        thread=threading.Thread(target=serve,daemon=True); thread.start()
        trigger=None
        if cancel:
            trigger=threading.Thread(target=lambda:(sent.wait(2),time.sleep(.1),cancelled.set()),daemon=True);trigger.start()
        try:
            with tempfile.TemporaryDirectory() as root:
                sample=b'authored-image'; sha=hashlib.sha256(sample).hexdigest()
                binding={'parser':'paddleocr-vl','inputSha256':sha,'inputByteCount':len(sample),
                         'originalSourceSha256':'a'*64,'physicalPageNo':1,'profileSha256':'b'*64}
                worker=Worker({'workspace':root,'stages':{'paddle':{'readyUrl':f'http://127.0.0.1:{listener.getsockname()[1]}/health',
                              'timeoutSeconds':3,'providerOptions':{}}}},secret='fixture-secret')
                op=envelope('paddle',b'{}',binding,{'model':'fixture'})
                directory=Path(root)/'gpu-operations'/op['operationId'];directory.mkdir()
                (directory/'request.json').write_bytes(encoded(op))
                (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],'stage':'paddle',
                    'status':'ready','bindingSha256':op['bindingSha256'],'deadlineUnixMs':int((time.time()+2)*1000)}))
                worker.active[op['operationId']]=(cancelled,threading.Event());worker.trees[op['operationId']]=object()
                lock=threading.Lock();worker.sessions[op['operationId']]={'lock':lock}
                from fixed_gpu_client import _ResponseReader
                original_close=_ResponseReader.close
                def fail_close(reader):
                    if not reader.closed:
                        original_close(reader);raise OSError('injected reader close')
                    return original_close(reader)
                failure=None; result=None; started=time.monotonic()
                real_fsync = os.fsync; sync_calls = []
                def injected_fsync(fd):
                    sync_calls.append(fd)
                    if len(sync_calls) == fsync_fail_at + 1 + (os.name == 'posix'): raise OSError('injected fsync failure')
                    return real_fsync(fd)
                with patch.object(worker,'_listener_owned',return_value=True),patch.object(worker,'_connection_owned',return_value=True), \
                     (patch.object(_ResponseReader,'close',fail_close) if close_error else nullcontext()), \
                     (patch('fixed_gpu_worker.MAX_PROVIDER_BODY',max_body) if max_body is not None else nullcontext()), \
                     (patch('fixed_gpu_worker.os.fsync',injected_fsync) if fsync_fail_at is not None else nullcontext()):
                    try: result=worker.provider_request(op['operationId'],'POST','/layout-parsing',
                             encoded({'file':base64.b64encode(sample).decode()}),'application/json','a'*32)
                    except Exception as error: failure=error
                if result is not None: result[2].close()
                elapsed=time.monotonic()-started
                receipts=[p for p in (directory/'provider-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json',
                                      directory/'provider-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.failure.json') if p.exists() and p.stat().st_size]
                if allow_missing_receipt and not receipts:
                    self.assertFalse(lock.locked()); return {'failure':failure, 'receipt':None}
                self.assertEqual(1,len(receipts),repr(failure))
                receipt=json.loads(receipts[0].read_bytes()); body_path=receipts[0].with_suffix('.bin')
                body=body_path.read_bytes() if body_path.exists() else b''
                if receipt['bytes'] is not None: self.assertEqual(len(body),receipt['bytes'])
                if receipt['sha256'] is not None: self.assertEqual(hashlib.sha256(body).hexdigest(),receipt['sha256'])
                self.assertFalse(lock.locked())
                wire_ref=receipt.get('wire');fragment=(directory/wire_ref['file']).read_bytes() if wire_ref else None
                if fragment is not None:
                    self.assertEqual(hashlib.sha256(fragment).hexdigest(),wire_ref['sha256'])
                overflow_ref=receipt.get('overflowBody');overflow=(directory/overflow_ref['file']).read_bytes() if overflow_ref else None
                if overflow is not None: self.assertEqual(hashlib.sha256(overflow).hexdigest(),overflow_ref['sha256'])
                write_ref=receipt.get('bodyWriteFragment');write_fragment=(directory/write_ref['file']).read_bytes() if write_ref else None
                if write_fragment is not None: self.assertEqual(hashlib.sha256(write_fragment).hexdigest(),write_ref['sha256'])
                cleanup_path=directory/'provider-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.cleanup.json'
                cleanup=json.loads(cleanup_path.read_bytes()) if cleanup_path.exists() and cleanup_path.stat().st_size else None
                return {'body':body,'receipt':receipt,'cleanup':cleanup,'fragment':fragment,'overflow':overflow,'failure':failure,'result':result,
                        'write_fragment':write_fragment,'elapsed':elapsed,'cancelled':cancelled.is_set(),
                        'outcome_unknown':worker.sessions[op['operationId']].get('providerOutcomeUnknown',False),
                        'wires':sum(p.stat().st_size > 0 for p in directory.glob('*.wire'))}
        finally:
            release.set();thread.join(3)
            if trigger:trigger.join(3)
            self.assertFalse(thread.is_alive());self.assertFalse(failures)

    def test_valid_multichunk_and_trailer_without_wire_copy(self):
        value=self.exchange(b'HTTP/1.0 503 Error\r\nTransfer-Encoding: chunked\r\n\r\n'
                            b'2;x="a b"\r\n\x00a\r\n2\r\nbc\r\n0\r\nX-Trace: ok\r\n\r\n')
        self.assertIsNone(value['failure']);self.assertEqual(value['body'],b'\x00abc')
        self.assertEqual(value['receipt']['status'],503);self.assertTrue(value['receipt']['complete'])
        self.assertEqual(value['wires'],0)

    def test_first_body_fsync_failure_remains_unknown_after_retry(self):
        value=self.exchange(b'HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc', fsync_fail_at=1)
        self.assertIsInstance(value['failure'], OSError)
        self.assertEqual(value['body'],b'abc')
        self.assertEqual(value['receipt']['bodyDurability'],'unknown')
        self.assertEqual(value['receipt']['bodySyncErrorType'],'OSError')
        self.assertFalse(value['receipt']['complete'])

    def test_wire_fsync_failure_is_in_failure_receipt(self):
        value=self.exchange(b'HTTP/1.0 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n', fsync_fail_at=1)
        self.assertEqual(value['receipt']['wireCaptureErrorType'],'OSError')
        self.assertIsNone(value['fragment'])
        self.assertFalse(value['receipt']['complete'])

    def test_overflow_fsync_failure_is_in_failure_receipt(self):
        value=self.exchange(b'HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc', max_body=2, fsync_fail_at=1)
        self.assertEqual(value['receipt']['overflowCaptureErrorType'],'OSError')
        self.assertIsNone(value['overflow'])
        self.assertFalse(value['receipt']['complete'])

    def test_bad_or_incomplete_chunked_never_complete(self):
        for frame in (b'1\na\r\n0\r\n\r\n',b'1\r\naXX0\r\n\r\n',b'1\r\na\r\n0\r\n',
                      b'1\r\na\r\n0',b'5\r\nab',b'1\r\na\r\n0\r\nTrailer: x\r\n'):
            with self.subTest(frame=frame):
                value=self.exchange(b'HTTP/1.0 503 Error\r\nTransfer-Encoding: chunked\r\n\r\n'+frame)
                self.assertIsNotNone(value['failure']);self.assertFalse(value['receipt']['complete'])

    def test_fragmented_valid_chunk_extensions_and_trailers(self):
        value=self.exchange(b'HTTP/1.0 503 Error\r\nTransfer-Encoding: chunked\r\n\r\n'
                            b'2;name=value\r\nxy\r\n3\r\nabc\r\n0\r\nTrailer: x\r\n\r\n',fragmented=True)
        self.assertIsNone(value['failure']);self.assertEqual(value['body'],b'xyabc')
        self.assertTrue(value['receipt']['complete']);self.assertEqual(value['wires'],0)

    def test_received_extra_bytes_are_never_misidentified_as_body(self):
        for head,body in ((b'Content-Length: 1\r\n',b'abc'),
                          (b'Transfer-Encoding: chunked\r\n',b'1\r\na\r\n0\r\n\r\nbc')):
            with self.subTest(head=head):
                value=self.exchange(b'HTTP/1.0 503 Error\r\n'+head+b'\r\n'+body)
                self.assertIsNotNone(value['failure']);self.assertFalse(value['receipt']['complete'])
                self.assertEqual(value['body'],b'a');self.assertTrue(value['fragment'].endswith(b'bc'))

    def test_partial_chunk_cancel_retains_original_body_and_is_prompt(self):
        value=self.exchange(b'HTTP/1.0 503 Error\r\nTransfer-Encoding: chunked\r\n\r\n3\r\na\x00b\r\n5\r\n',hold=True,cancel=True)
        self.assertTrue(value['cancelled']);self.assertLess(value['elapsed'],.9)
        self.assertIsNotNone(value['failure']);self.assertEqual(value['body'],b'a\x00b')
        self.assertFalse(value['receipt']['complete'])

    def test_last_body_chunk_survives_reader_close_error(self):
        value=self.exchange(b'HTTP/1.0 503 Error\r\nContent-Length: 3\r\n\r\nabc',close_error=True)
        self.assertIsNotNone(value['failure']);self.assertEqual(value['body'],b'abc')
        self.assertTrue(value['receipt']['complete'])

    def test_invalid_framing_retains_unparsed_wire_not_a_body(self):
        wire=b'HTTP/1.0 503 Error\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nabc'
        value=self.exchange(wire)
        self.assertIsNotNone(value['failure']);self.assertFalse(value['receipt']['complete'])
        self.assertEqual(value['body'],b'');self.assertEqual(value['fragment'],wire)

    def test_cancelled_in_header_keeps_unparsed_wire(self):
        wire=b'HTTP/1.0 503 Error\r\nContent-Length: '
        value=self.exchange(wire,hold=True,cancel=True)
        self.assertIsNotNone(value['failure']);self.assertTrue(value['cancelled'])
        self.assertFalse(value['receipt']['complete']);self.assertEqual(value['body'],b'')
        self.assertEqual(value['fragment'],wire)
        self.assertEqual(value['receipt']['wire']['scope'],'http_header_wire_prefix')

    def test_cancelled_in_chunk_size_keeps_consumed_framing_fragment(self):
        wire=b'HTTP/1.0 503 Error\r\nTransfer-Encoding: chunked\r\n\r\n1'
        value=self.exchange(wire,hold=True,cancel=True)
        self.assertIsNotNone(value['failure']);self.assertTrue(value['cancelled'])
        self.assertFalse(value['receipt']['complete']);self.assertEqual(value['body'],b'')
        self.assertEqual(value['fragment'],b'1')
        self.assertEqual(value['receipt']['wire']['scope'],'http_unprocessed_wire_fragment')

    def test_overlimit_body_fragment_is_preserved_separately(self):
        value=self.exchange(b'HTTP/1.0 503 Error\r\nContent-Length: 4\r\n\r\nabcd',max_body=3)
        self.assertIsNotNone(value['failure']);self.assertFalse(value['receipt']['complete'])
        self.assertEqual(value['body'],b'');self.assertEqual(value['overflow'],b'abcd')
        identity=value['receipt']['overflowBody']
        self.assertEqual((identity['scope'],identity['intendedOffset'],identity['bodyLimit'],identity['bytes']),
                         ('upstream_body_limit_crossing_chunk',0,3,4))
        self.assertEqual(value['body']+value['overflow'],b'abcd')

    def test_crossing_chunk_records_nonzero_offset_and_limit(self):
        real_read1=http.client.HTTPResponse.read1
        with patch.object(http.client.HTTPResponse,'read1',lambda response,n:real_read1(response,min(n,2))):
            value=self.exchange(b'HTTP/1.0 503 Error\r\nContent-Length: 4\r\n\r\nabcd',max_body=3)
        identity=value['receipt']['overflowBody']
        self.assertEqual((identity['intendedOffset'],identity['bodyLimit'],identity['bytes']),(2,3,2))
        self.assertEqual(value['body']+value['overflow'],b'abcd')


if __name__=='__main__': unittest.main()
