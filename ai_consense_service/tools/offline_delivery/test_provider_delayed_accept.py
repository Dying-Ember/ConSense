import base64
import hashlib
import os
import socket
import subprocess
import sys
import threading
import time
import unittest
from contextlib import nullcontext
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

from fixed_gpu_worker import ProviderConnection, Worker
from request_stage import encoded, envelope


class DelayedAcceptTest(unittest.TestCase):
    def test_unowned_peer_never_receives_source(self):
        with TemporaryDirectory() as root:
            sample = b'authored private input'
            listener = socket.socket()
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            result = Path(root) / 'received.bin'
            program = 'import socket,sys; s=socket.socket(fileno=int(sys.argv[1])); c,_=s.accept(); c.settimeout(3); data=c.recv(4096); open(sys.argv[2],"wb").write(data); c.close(); s.close()'
            child = subprocess.Popen([sys.executable, '-c', program, str(listener.fileno()), str(result)], pass_fds=(listener.fileno(),))
            listener.close()
            worker = Worker({'workspace': root, 'stages': {'paddle': {
                'readyUrl': f'http://127.0.0.1:{port}/health', 'timeoutSeconds': 5, 'providerOptions': {}}}}, 'fixture')
            binding = {'parser': 'paddleocr-vl', 'inputSha256': hashlib.sha256(sample).hexdigest(),
                       'inputByteCount': len(sample), 'originalSourceSha256': 'a' * 64,
                       'physicalPageNo': 1, 'profileSha256': 'b' * 64}
            op = envelope('paddle', b'{}', binding, {'model': 'fixture'})
            directory = Path(root) / 'gpu-operations' / op['operationId']
            directory.mkdir()
            (directory / 'request.json').write_bytes(encoded(op))
            (directory / 'status.json').write_bytes(encoded({'operationId': op['operationId'], 'stage': 'paddle',
                'status': 'ready', 'bindingSha256': op['bindingSha256'], 'deadlineUnixMs': int((time.time() + .7) * 1000)}))
            group = Path(root) / 'fake-cgroup'
            group.mkdir()
            (group / 'cgroup.procs').write_text(str(os.getpid()))
            worker.active[op['operationId']] = (threading.Event(), threading.Event())
            worker.trees[op['operationId']] = SimpleNamespace(group=group)
            worker.sessions[op['operationId']] = {'lock': threading.Lock()}
            payload = encoded({'file': base64.b64encode(sample).decode()})
            try:
                # Simulate a listener identity changing after the initial check.
                with patch.object(worker, '_listener_owned', return_value=True):
                    with self.assertRaisesRegex(ValueError, 'provider connection is not owned'):
                        worker.provider_request(op['operationId'], 'POST', '/layout-parsing', payload, 'application/json', '2' * 32)
                self.assertEqual(0, child.wait(timeout=3))
                self.assertEqual(b'', result.read_bytes())
            finally:
                if child.poll() is None:
                    child.terminate()
                    child.wait(timeout=3)

    def exercise(self, mode):
        with TemporaryDirectory() as root:
            sample = b'authored input'
            listener = socket.socket()
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            listener.settimeout(5)
            port = listener.getsockname()[1]
            received = []

            def serve():
                try:
                    time.sleep(1.4)
                    client, _ = listener.accept()
                    with client:
                        client.settimeout(2)
                        data = client.recv(65536)
                        received.append(data)
                        if data:
                            client.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: application/json\r\n\r\n{}')
                except OSError:
                    pass

            server = threading.Thread(target=serve)
            server.start()
            worker = Worker({'workspace': root, 'stages': {'paddle': {
                'readyUrl': f'http://127.0.0.1:{port}/health', 'timeoutSeconds': 5, 'providerOptions': {}}}}, 'fixture')
            binding = {'parser': 'paddleocr-vl', 'inputSha256': hashlib.sha256(sample).hexdigest(),
                       'inputByteCount': len(sample), 'originalSourceSha256': 'a' * 64,
                       'physicalPageNo': 1, 'profileSha256': 'b' * 64}
            op = envelope('paddle', b'{}', binding, {'model': 'fixture'})
            directory = Path(root) / 'gpu-operations' / op['operationId']
            directory.mkdir()
            (directory / 'request.json').write_bytes(encoded(op))
            deadline = time.time() + (0.5 if mode == 'deadline' else 0.8 if mode == 'slow-connect-deadline' else 4)
            (directory / 'status.json').write_bytes(encoded({'operationId': op['operationId'], 'stage': 'paddle',
                'status': 'ready', 'bindingSha256': op['bindingSha256'], 'deadlineUnixMs': int(deadline * 1000)}))
            group = Path(root) / 'fake-cgroup'
            group.mkdir()
            (group / 'cgroup.procs').write_text(str(os.getpid()))
            cancel = threading.Event()
            worker.active[op['operationId']] = (cancel, threading.Event())
            worker.trees[op['operationId']] = SimpleNamespace(group=group)
            worker.sessions[op['operationId']] = {'lock': threading.Lock()}
            timer = threading.Timer(.3, cancel.set) if mode == 'cancel' else None
            if timer:
                timer.start()
            payload = encoded({'file': base64.b64encode(sample).decode()})
            original_connect = ProviderConnection.connect
            def slow_connect(connection):
                time.sleep(.45)
                return original_connect(connection)
            connect_patch = patch.object(ProviderConnection, 'connect', slow_connect) if mode == 'slow-connect-deadline' else nullcontext()
            try:
                with connect_patch:
                    before = time.monotonic()
                    if mode == 'success':
                        status, _, response, size = worker.provider_request(op['operationId'], 'POST', '/layout-parsing', payload, 'application/json', '1' * 32)
                        with response:
                            self.assertEqual((200, 2, b'{}'), (status, size, response.read()))
                    else:
                        with self.assertRaises(ValueError):
                            worker.provider_request(op['operationId'], 'POST', '/layout-parsing', payload, 'application/json', '1' * 32)
                        if mode == 'slow-connect-deadline':
                            self.assertLess(time.monotonic() - before, 1.2)
                        if mode in ('cancel', 'deadline'):
                            self.assertLess(time.monotonic() - before, .9)
            finally:
                if timer:
                    timer.cancel()
                server.join(4)
                listener.close()
            self.assertFalse(server.is_alive())
            if mode == 'success':
                self.assertTrue(received and b'POST /layout-parsing' in received[0])
            else:
                self.assertTrue(not received or received == [b''])

    def test_delayed_accept_and_cancellation_boundaries(self):
        for mode in ('success', 'cancel', 'deadline', 'slow-connect-deadline'):
            with self.subTest(mode=mode):
                self.exercise(mode)


if __name__ == '__main__':
    unittest.main()
