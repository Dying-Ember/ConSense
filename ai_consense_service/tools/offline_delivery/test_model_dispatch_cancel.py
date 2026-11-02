import base64
import hashlib
import os
from pathlib import Path
import socket
import struct
import threading
import time
import unittest
from tempfile import TemporaryDirectory
from unittest.mock import patch

from fixed_gpu_worker import Worker


@unittest.skipUnless(os.name == 'posix', 'Unix peer credentials required')
class ModelDispatchCancellationTest(unittest.TestCase):
    def test_cancel_or_deadline_before_dispatch_never_sends_batch(self):
        for mode in ('cancel_during_connect', 'expired'):
            with self.subTest(mode=mode), TemporaryDirectory() as root:
                worker = Worker({'workspace': root, 'stages': {}}, 'example-secret')
                cancel = threading.Event()
                operation = {'operationId': 'a' * 32}
                session = {'deadline': time.monotonic() + (10 if mode != 'expired' else -1), 'token': 'fixture'}

                class Connection:
                    sent = False
                    closed = False
                    def settimeout(self, _): pass
                    def connect(self, _):
                        if mode == 'cancel_during_connect':
                            with worker.lock:
                                cancel.set()
                    def getsockopt(self, *_): return struct.pack('3i', os.getpid(), os.geteuid(), os.getegid())
                    def sendall(self, _): self.sent = True
                    def close(self): self.closed = True
                    def recv(self, _): return b''

                conn = Connection()
                with patch('fixed_gpu_worker.socket.socket', return_value=conn), \
                     patch('fixed_gpu_worker.read_json', return_value={'pid': os.getpid()}), \
                     patch.object(worker, '_model_ready', return_value=True), \
                     patch.object(worker, '_tree_pids', return_value={os.getpid()}):
                    with self.assertRaisesRegex(ValueError, 'cancelled or expired'):
                        worker._model_exchange(Path(root), operation, object(), session, cancel, 'submit', {})
                self.assertFalse(conn.sent)
                self.assertTrue(conn.closed)

    def test_cancel_cannot_return_while_dispatch_is_in_progress(self):
        with TemporaryDirectory() as root:
            worker = Worker({'workspace': root, 'stages': {}}, 'example-secret')
            cancel = threading.Event(); operation = {'operationId': 'b' * 32}
            worker.active[operation['operationId']] = (cancel, threading.Event())
            session = {'deadline': time.monotonic() + 10, 'token': 'fixture'}
            sending = threading.Event(); unblock = threading.Event(); cancelled = threading.Event()
            failures = []

            class Connection:
                def settimeout(self, _): pass
                def connect(self, _): pass
                def getsockopt(self, *_): return struct.pack('3i', os.getpid(), os.geteuid(), os.getegid())
                def sendall(self, _):
                    sending.set()
                    if not unblock.wait(2): raise AssertionError('dispatch was not released')
                def recv(self, _):
                    if not cancelled.wait(2): raise AssertionError('cancellation did not finish')
                    return b''
                def close(self): pass

            def exchange():
                try: worker._model_exchange(Path(root), operation, object(), session, cancel, 'submit', {})
                except ValueError: pass
                except BaseException as error: failures.append(error)

            def stop():
                try: worker.cancel(operation['operationId']); cancelled.set()
                except BaseException as error: failures.append(error)

            with patch('fixed_gpu_worker.socket.socket', return_value=Connection()), \
                 patch('fixed_gpu_worker.read_json', return_value={'pid': os.getpid()}), \
                 patch.object(worker, '_model_ready', return_value=True), \
                 patch.object(worker, '_tree_pids', return_value={os.getpid()}), \
                 patch.object(worker, '_record', return_value={'status': 'ready'}):
                call = threading.Thread(target=exchange); call.start()
                self.assertTrue(sending.wait(2))
                stop_call = threading.Thread(target=stop); stop_call.start()
                try: self.assertFalse(cancelled.wait(.1))
                finally: unblock.set()
                stop_call.join(2); call.join(2)
                self.assertFalse(stop_call.is_alive()); self.assertFalse(call.is_alive())
                self.assertTrue(cancelled.is_set()); self.assertFalse(failures)

    def test_real_unix_backpressure_preserves_timeout_and_releases_session(self):
        with TemporaryDirectory(prefix='model-backpressure-', dir='/tmp') as root:
            worker = Worker({'workspace': root, 'stages': {}}, 'example-secret')
            operation_id = 'c' * 32; binding = 'd' * 64
            directory = worker.operations / operation_id
            directory.mkdir(parents=True)
            address = str(directory / 'model-session.sock')
            listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            listener.bind(address); listener.listen(1); listener.settimeout(3)
            raw = b'x' * (4 * 1024 * 1024)
            batch = {'ordinal': 0, 'inputSha256': hashlib.sha256(raw).hexdigest(),
                     'bodyBase64': base64.b64encode(raw).decode('ascii')}
            operation = {'operationId': operation_id, 'bindingSha256': binding,
                         'source': {'maxBatches': 1, 'maxTotalBytes': 16 * 1024 * 1024}}
            cancel = threading.Event(); sent = threading.Event(); accepted = threading.Event()
            session = {'lock': threading.Lock(), 'deadline': time.monotonic() + 5,
                       'token': 'fixture', 'receipts': [], 'totalBytes': 0}
            worker.active[operation_id] = (cancel, threading.Event())
            worker.trees[operation_id] = object(); worker.sessions[operation_id] = session
            real_socket = socket.socket; connections = []; errors = []

            class TrackedSocket:
                def __init__(self):
                    self.inner = real_socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    self.inner.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
                    connections.append(self)
                def __getattr__(self, name): return getattr(self.inner, name)
                def sendall(self, data): sent.set(); return self.inner.sendall(data)

            def exchange():
                try: worker._model_action(operation_id, 'submit', batch)
                except BaseException as error: errors.append(error)

            def hold_peer():
                peer, _ = listener.accept(); peer.settimeout(3); accepted.set()
                try:
                    if not sent.wait(3): raise AssertionError('batch was not dispatched')
                    if not cancel.wait(3): raise AssertionError('send timeout did not cancel the session')
                    while peer.recv(65536): pass
                except BaseException as error: errors.append(error)
                finally: peer.close()

            peer = threading.Thread(target=hold_peer); peer.start()
            try:
                with patch('fixed_gpu_worker.socket.socket', side_effect=lambda *args, **kwargs: real_socket(*args, **kwargs) if 'fileno' in kwargs else TrackedSocket()), \
                     patch('fixed_gpu_worker.verify_operation', return_value=operation), \
                     patch('fixed_gpu_worker.read_json', side_effect=lambda path: operation if path.name == 'request.json' else {'pid': os.getpid()}), \
                     patch.object(worker, '_model_ready', return_value=True), \
                     patch.object(worker, '_tree_pids', return_value={os.getpid()}), \
                     patch.object(worker, '_record', return_value={'status': 'ready', 'bindingSha256': binding}):
                    caller = threading.Thread(target=exchange); caller.start()
                    self.assertTrue(accepted.wait(3)); self.assertTrue(sent.wait(3))
                    caller.join(3); self.assertFalse(caller.is_alive())
                peer.join(3); self.assertFalse(peer.is_alive())
                self.assertEqual(1, len(errors))
                self.assertIsInstance(errors[0], socket.timeout)
                self.assertTrue(cancel.is_set())
                self.assertTrue(session['lock'].acquire(blocking=False)); session['lock'].release()
                self.assertTrue(worker.lock.acquire(blocking=False)); worker.lock.release()
                self.assertEqual(-1, connections[0].fileno())
            finally:
                listener.close()
                peer.join(3)


if __name__ == '__main__': unittest.main()
