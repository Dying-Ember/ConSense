"""The external Source launcher keeps HTTP status without logging request targets."""
import http.client
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


class SourceAccessPrivacyTest(unittest.TestCase):
    def test_real_uvicorn_serves_routes_without_logging_path_or_query(self):
        launcher = Path(__file__).with_name('source_service_entrypoint.py')
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture = root / 'app.py'
            fixture.write_text(
                'import uvicorn\n'
                'async def app(scope, receive, send):\n'
                '    status = 200 if scope["path"] == "/health" else 404\n'
                '    await send({"type":"http.response.start", "status":status, "headers":[]})\n'
                '    await send({"type":"http.response.body", "body":b"healthy" if status == 200 else b"missing"})\n'
                '    if status == 404: server.should_exit = True\n'
                'server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=int(__import__("sys").argv[1])))\n'
                'if __name__ == "__main__": server.run()\n',
                encoding='utf-8')
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
                port = sock.getsockname()[1]
            with (root / 'stdout.log').open('w+b') as stdout, (root / 'stderr.log').open('w+b') as stderr:
                process = subprocess.Popen([sys.executable, str(launcher), '--serve', str(fixture), str(port)],
                                           stdout=stdout, stderr=stderr)
                try:
                    deadline = time.monotonic() + 15
                    while True:
                        try:
                            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=1)
                            connection.request('GET', '/health?token=SYNTHETIC_QUERY_SECRET',
                                               headers={'X-Forwarded-For': 'SYNTHETIC_FORWARDED_SECRET'})
                            response = connection.getresponse()
                            self.assertEqual((200, b'healthy'), (response.status, response.read()))
                            connection.close()
                            break
                        except (ConnectionError, OSError):
                            if process.poll() is not None or time.monotonic() >= deadline:
                                self.fail('fixture server failed to start')
                            time.sleep(.1)
                    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=2)
                    connection.request('GET', '/SYNTHETIC_PATH_SECRET')
                    response = connection.getresponse()
                    self.assertEqual((404, b'missing'), (response.status, response.read()))
                    connection.close()
                    self.assertEqual(0, process.wait(timeout=10))
                finally:
                    if process.poll() is None:
                        process.terminate()
                        process.wait(timeout=10)
                stdout.seek(0)
                stderr.seek(0)
                logs = stdout.read() + stderr.read()
            self.assertIn(b'200 OK', logs)
            self.assertIn(b'404 Not Found', logs)
            self.assertNotIn(b'SYNTHETIC_QUERY_SECRET', logs)
            self.assertNotIn(b'SYNTHETIC_PATH_SECRET', logs)
            self.assertNotIn(b'SYNTHETIC_FORWARDED_SECRET', logs)


if __name__ == '__main__':
    unittest.main()
