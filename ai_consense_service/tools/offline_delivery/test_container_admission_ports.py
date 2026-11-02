"""Real loopback port admission without contacting Docker."""
import socket
import sys
import unittest

import container_admission as admission


@unittest.skipUnless(sys.platform.startswith('linux'), 'Linux TCP reuse semantics')
class PortAdmissionTest(unittest.TestCase):
    def test_recent_health_connection_does_not_block_replacement(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
            listener.listen(1)
            with socket.create_connection(('127.0.0.1', port)) as client:
                accepted, _ = listener.accept()
                with accepted:
                    accepted.shutdown(socket.SHUT_WR)
                    self.assertEqual(client.recv(1), b'')
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as bare:
            with self.assertRaises(OSError):
                bare.bind(('127.0.0.1', port))
        admission._ports_available([port])

    def test_existing_listener_still_blocks_replacement(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            with self.assertRaises(OSError):
                admission._ports_available([listener.getsockname()[1]])


if __name__ == '__main__':
    unittest.main()
