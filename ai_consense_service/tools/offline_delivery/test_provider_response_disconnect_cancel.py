"""Synthetic loopback: response-write failure vs a new MinerU cleanup dispatch."""
import hashlib
import http.client
import socket
import socketserver
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
OFFLINE = ROOT / 'ai_consense_service/tools/offline_delivery'
VETTING = ROOT / 'ai_consense_service/tools/vetting_eval'
sys.path[:0] = [str(OFFLINE), str(VETTING)]
import fixed_gpu_worker as current
from request_stage import encoded, envelope
class ResponseFailureInterleave(unittest.TestCase):
    def exercise(self, module):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0)); listener.listen(1); listener.settimeout(5)
        accepted = []
        def upstream():
            peer, _ = listener.accept()
            with peer:
                peer.settimeout(4)
                data = peer.recv(4096)
                accepted.append(data)
                if data:
                    peer.sendall(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}')
            listener.close()
        upstream_thread = threading.Thread(target=upstream, daemon=True); upstream_thread.start()
        capture_started = threading.Event(); fail_capture = threading.Event()
        dispatch_started = threading.Event(); resume_dispatch = threading.Event()
        errors = []; failing_thread = []; write_faults = []
        with tempfile.TemporaryDirectory() as root:
            worker = module.Worker({'workspace':root, 'stages':{'mineru':{
                'readyUrl':f'http://127.0.0.1:{listener.getsockname()[1]}/health',
                'timeoutSeconds':8, 'providerOptions':{}}}}, secret='test-secret')
            binding = {'parser':'mineru', 'inputSha256':hashlib.sha256(b'image').hexdigest(),
                       'inputByteCount':5, 'originalSourceSha256':'a'*64,
                       'physicalPageNo':1, 'profileSha256':'b'*64}
            op = envelope('mineru', b'{}', binding, {'model':'fixture'})
            oid = op['operationId']; directory = Path(root) / 'gpu-operations' / oid; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op))
            (directory/'status.json').write_bytes(encoded({'operationId':oid, 'stage':'mineru', 'status':'ready',
                'bindingSha256':op['bindingSha256'], 'deadlineUnixMs':int((time.time()+7)*1000)}))
            cancel = threading.Event(); worker.active[oid] = (cancel, threading.Event())
            worker.trees[oid] = object(); worker.sessions[oid] = {'lock':threading.Lock(), 'jobId':'job_test'}
            real_provider_request = worker.provider_request

            class InterruptedCapture:
                def __enter__(self): return self
                def __exit__(self, *args): return False
                def read(self, count):
                    capture_started.set()
                    if not fail_capture.wait(4): raise TimeoutError('capture barrier')
                    failing_thread.append(threading.get_ident())
                    return b'x'

            original_write = socketserver._SocketWriter.write
            def fail_first_response_write(stream, data):
                if failing_thread and threading.get_ident() == failing_thread[0]:
                    write_faults.append('BrokenPipeError')
                    raise BrokenPipeError('synthetic gateway response write failure')
                return original_write(stream, data)

            def provider(oid_, method, path, body, content_type, dispatch_id):
                if dispatch_id == 'a'*32:
                    return 200, 'application/json', InterruptedCapture(), 1
                return real_provider_request(oid_, method, path, body, content_type, dispatch_id)

            class HeldConnection(http.client.HTTPConnection):
                def request(self, *args, **kwargs):
                    dispatch_started.set()
                    if not resume_dispatch.wait(4): raise TimeoutError('dispatch barrier')
                    return super().request(*args, **kwargs)

            def call(method, dispatch):
                conn = http.client.HTTPConnection('127.0.0.1', gateway.server_address[1], timeout=5)
                try:
                    conn.request(method, '/operations/'+oid+'/provider/v1/parse/jobs/job_test',
                        headers={'Authorization':'Bearer test-secret',
                                 'X-Consense-Provider-Dispatch-Id':dispatch})
                    response = conn.getresponse(); response.read()
                except Exception as error:
                    errors.append(type(error).__name__)
                finally: conn.close()

            worker.provider_request = provider
            gateway = module.make_server(worker, ('127.0.0.1', 0))
            gateway_thread = threading.Thread(target=gateway.serve_forever, daemon=True); gateway_thread.start()
            # Delay the polling watcher deterministically, representing an allowed scheduling interleave.
            with patch.object(worker, '_listener_owned', return_value=True), \
                 patch.object(worker, '_connection_owned', return_value=True), \
                 patch.object(module, 'ProviderConnection', HeldConnection), \
                 patch.object(socketserver._SocketWriter, 'write', fail_first_response_write), \
                 patch('native_provider_lifecycle._watch_cancellation', return_value=lambda: None):
                first = threading.Thread(target=call, args=('GET', 'a'*32), daemon=True); first.start()
                self.assertTrue(capture_started.wait(3))
                second = threading.Thread(target=call, args=('DELETE', 'b'*32), daemon=True); second.start()
                self.assertTrue(dispatch_started.wait(4), 'second call failed to reach dispatch barrier')
                fail_capture.set()
                self.assertTrue(cancel.wait(3), 'gateway did not observe response failure')
                resume_dispatch.set()
                first.join(4); second.join(4)
                self.assertFalse(first.is_alive() or second.is_alive())
            gateway.shutdown(); gateway.server_close(); gateway_thread.join(3)
        upstream_thread.join(5)
        self.assertFalse(upstream_thread.is_alive())
        self.assertEqual(['BrokenPipeError'], write_faults)
        return accepted

    def test_response_failure_closes_other_admitted_provider_socket(self):
        self.assertEqual([b''], self.exercise(current))


if __name__ == '__main__': unittest.main()
