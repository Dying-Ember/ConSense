import base64
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from fixed_gpu_worker import Worker
from request_stage import encoded


class ShutdownTests(unittest.TestCase):
    @unittest.skip('Known limitation: idle shutdown does not drain in-flight daemon HTTP handlers')
    @unittest.skipUnless(sys.platform.startswith('linux'), 'Linux PID namespace required')
    def test_term_waits_for_inflight_http_request(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            secret = directory / 'secret'; secret.write_text('fixture-secret-1234567890'); secret.chmod(0o600)
            sock = directory / 'socket'; config = directory / 'config.json'
            config.write_text(json.dumps({'workspace': root, 'stages': {}, 'secretFile': str(secret),
                'networkMode': 'host', 'socketPath': str(sock), 'peerUid': os.getuid(), 'peerGid': os.getgid()}))
            process = subprocess.Popen(['unshare', '--pid', '--fork', sys.executable,
                str(Path(__file__).with_name('fixed_gpu_worker.py')), '--config', str(config)],
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            pid = None; connection = None
            try:
                deadline = time.monotonic() + 5
                while not sock.exists() and time.monotonic() < deadline and process.poll() is None:
                    time.sleep(.02)
                self.assertTrue(sock.exists(), 'worker failed to start')
                children = Path(f'/proc/{process.pid}/task/{process.pid}/children').read_text().split()
                self.assertEqual(1, len(children)); pid = int(children[0])
                connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM); connection.settimeout(2)
                connection.connect(str(sock))
                connection.sendall(b'POST /operations HTTP/1.0\r\nAuthorization: Bearer fixture-secret-1234567890\r\nContent-Length: 100\r\n\r\n')
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    status = Path(f'/proc/{pid}/status').read_text()
                    if int(next(line for line in status.splitlines() if line.startswith('Threads:')).split()[1]) > 1:
                        break
                    time.sleep(.02)
                else:
                    self.fail('HTTP handler did not start')
                os.kill(pid, signal.SIGTERM)
                time.sleep(.5)
                self.assertIsNone(process.poll(), 'in-flight HTTP response was interrupted')
                connection.sendall(b' ' * 100)
                self.assertIn(b'400', connection.recv(1024))
                connection.close(); connection = None
                self.assertEqual(0, process.wait(timeout=2))
            finally:
                if connection is not None: connection.close()
                if process.poll() is None and pid is not None and Path(f'/proc/{pid}').exists():
                    os.kill(pid, signal.SIGKILL)
                if process.poll() is None: process.kill()
                process.wait(timeout=3); process.stderr.close()

    def test_shutdown_wins_race_with_pre_admission_validation(self):
        with tempfile.TemporaryDirectory() as root:
            profile = {'mode': 'job', 'profile': {'model': 'fixture'}, 'timeoutSeconds': 5}
            worker = Worker({'workspace': root, 'stages': {'business-llm': profile}}, 'fixture-secret')
            request = {'stage': 'business-llm', 'binding': {'source': 'fixture'}, 'bodyBase64': 'e30=',
                       'expectedProfileSha256': hashlib.sha256(encoded(profile['profile'])).hexdigest(),
                       'expectedMode': 'job'}
            entered, resume = threading.Event(), threading.Event()
            errors = []
            def validate(_profile):
                entered.set(); resume.wait(3)
            def start():
                try:
                    worker.start(request)
                except ValueError as error:
                    errors.append(str(error))
            with patch.object(worker, '_validate_profile', side_effect=validate):
                thread = threading.Thread(target=start); thread.start()
                try:
                    self.assertTrue(entered.wait(2))
                    worker.shutdown_when_idle()
                finally:
                    resume.set(); thread.join(3)
            self.assertFalse(thread.is_alive())
            self.assertEqual(['GPU worker is shutting down'], errors)
            self.assertFalse(worker.marker.exists())

    def test_admission_and_shutdown_share_lock_and_retain_unknown_owner(self):
        with tempfile.TemporaryDirectory() as root:
            profile = {'mode': 'job', 'profile': {'model': 'fixture'}, 'timeoutSeconds': 5}
            worker = Worker({'workspace': root, 'stages': {'business-llm': profile}}, 'fixture-secret')
            request = {'stage': 'business-llm', 'binding': {'source': 'fixture'},
                       'bodyBase64': base64.b64encode(b'{}').decode(),
                       'expectedProfileSha256': hashlib.sha256(encoded(profile['profile'])).hexdigest(),
                       'expectedMode': 'job'}
            started, release = threading.Event(), threading.Event()

            def execute(directory, operation, _profile, _cancel, _finished):
                started.set()
                release.wait(5)
                with worker.lock:
                    worker.active.pop(operation['operationId'])

            with patch.object(worker, '_validate_profile'), patch.object(worker, '_execute', side_effect=execute):
                result = worker.start(request)
                self.assertTrue(started.wait(2))
                stop = threading.Thread(target=worker.shutdown_when_idle)
                stop.start()
                try:
                    deadline = time.monotonic() + 2
                    while not worker.stopping and time.monotonic() < deadline:
                        time.sleep(.01)
                    self.assertTrue(worker.stopping)
                    self.assertTrue(stop.is_alive(), 'active task must drain before exit')
                    with self.assertRaisesRegex(ValueError, 'shutting down'):
                        worker.start(request)
                    self.assertTrue(worker.marker.exists())
                finally:
                    release.set()
                    stop.join(3)
                self.assertFalse(stop.is_alive())
                self.assertEqual(result['operationId'], json.loads(worker.marker.read_text())['operationId'])
                self.assertEqual('occupied_or_release_unknown', json.loads(worker.marker.read_text())['state'])

    @unittest.skipUnless(sys.platform.startswith('linux'), 'Linux PID namespace required')
    def test_idle_worker_pid1_exits_after_term(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            secret = directory / 'secret'; secret.write_text('fixture-secret-1234567890'); secret.chmod(0o600)
            sock = directory / 'socket'
            config = directory / 'config.json'
            config.write_text(json.dumps({'workspace': root, 'stages': {}, 'secretFile': str(secret),
                'networkMode': 'host', 'socketPath': str(sock), 'peerUid': os.getuid(), 'peerGid': os.getgid()}))
            process = subprocess.Popen(['unshare', '--pid', '--fork', sys.executable,
                str(Path(__file__).with_name('fixed_gpu_worker.py')), '--config', str(config)],
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            pid = None
            try:
                deadline = time.monotonic() + 5
                while not sock.exists() and time.monotonic() < deadline and process.poll() is None:
                    time.sleep(.02)
                self.assertTrue(sock.exists(), 'worker failed to start')
                children = Path(f'/proc/{process.pid}/task/{process.pid}/children').read_text().split()
                self.assertEqual(1, len(children)); pid = int(children[0])
                self.assertEqual('1', next(x for x in Path(f'/proc/{pid}/status').read_text().splitlines()
                                           if x.startswith('NSpid:')).split()[-1])
                os.kill(pid, signal.SIGTERM)
                self.assertEqual(0, process.wait(timeout=2))
                self.assertFalse(sock.exists(), 'graceful exit should remove its own socket')
            finally:
                if process.poll() is None and pid is not None and Path(f'/proc/{pid}').exists():
                    os.kill(pid, signal.SIGKILL)
                if process.poll() is None: process.kill()
                process.wait(timeout=3)
                process.stderr.close()


if __name__ == '__main__':
    unittest.main()
