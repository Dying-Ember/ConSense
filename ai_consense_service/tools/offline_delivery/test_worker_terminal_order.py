"""Synthetic owner cleanup ordering; no GPU or subprocess runs."""
import json
import hashlib
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import threading
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'ai_consense_service' / 'tools' / 'vetting_eval'))
sys.path.insert(0, str(ROOT / 'ai_consense_service' / 'tools' / 'offline_delivery'))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import fixed_gpu_worker as gpu
from request_stage import encoded, envelope


class TerminalOrderTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name)
        self.worker = gpu.Worker({'workspace': str(self.workspace), 'stages': {}}, 'synthetic-secret')
        self.operation = envelope('document-embedding', b'{}', {'requestId': 'a' * 32},
                                  {'adapter': 'consense.model-session.v1'})
        self.operation_id = self.operation['operationId']
        self.directory = self.worker.operations / self.operation_id
        self.directory.mkdir()
        (self.directory / 'request.json').write_bytes(encoded(self.operation))
        (self.directory / 'status.json').write_bytes(encoded({
            'operationId': self.operation_id, 'stage': self.operation['stage'],
            'status': 'settling', 'bindingSha256': self.operation['bindingSha256'],
            'release': {'status': 'pending'}}))
        self.worker.marker.write_bytes(encoded({'operationId': self.operation_id,
                                                 'state': 'occupied_or_release_unknown'}))
        self.worker.active[self.operation_id] = (threading.Event(), threading.Event())
        self.fields = {'status': 'completed', 'failure': None,
                       'release': {'status': 'released', 'cgroupPopulated': 0,
                                   'observedGpuPids': [123]}}

    def _post_failure_status(self):
        self.worker.active.pop(self.operation_id)
        raw = json.loads((self.directory / 'status.json').read_bytes())
        self.assertEqual('settling', raw['status'])
        self.assertEqual('release_unknown', self.worker.status(self.operation_id)['status'])

    def test_persisted_completion_precedes_owner_cleanup(self):
        calls = []
        original = gpu.persist

        def checked_persist(path, value):
            self.assertTrue(self.worker.marker.exists())
            calls.append('persist')
            return original(path, value)

        with patch.object(gpu, 'sync_directory', side_effect=lambda path: calls.append(
                'sync-workspace' if path == self.workspace else 'sync-status')), \
                patch.object(gpu, 'persist', side_effect=checked_persist):
            self.worker._publish_terminal(self.directory, self.fields)
        self.assertEqual(['persist', 'sync-status', 'sync-workspace'], calls)
        self.assertFalse(self.worker.marker.exists())
        self.worker.active.pop(self.operation_id)
        self.assertEqual('completed', self.worker.status(self.operation_id)['status'])
        self.assertEqual('released', self.worker.status(self.operation_id)['release']['status'])

    def test_unlink_failure_preserves_owner_and_persisted_release(self):
        with patch.object(Path, 'unlink', side_effect=OSError('unlink failed')):
            with self.assertRaises(OSError):
                self.worker._publish_terminal(self.directory, self.fields)
        self.assertTrue(self.worker.marker.exists())
        self.worker.active.pop(self.operation_id)
        self.assertEqual('completed', self.worker.status(self.operation_id)['status'])

    def test_owner_sync_failure_follows_persisted_completion(self):
        original = gpu.sync_directory
        def fail_workspace(path):
            if path == self.workspace:
                raise OSError('sync failed')
            return original(path)
        with patch.object(gpu, 'sync_directory', side_effect=fail_workspace):
            with self.assertRaises(OSError):
                self.worker._publish_terminal(self.directory, self.fields)
        self.assertFalse(self.worker.marker.exists())
        self.worker.active.pop(self.operation_id)
        self.assertEqual('completed', self.worker.status(self.operation_id)['status'])

    def test_persist_failure_preserves_owner_and_blocks_new_start(self):
        with patch.object(gpu, 'persist', side_effect=OSError('persist failed')):
            with self.assertRaises(OSError):
                self.worker._publish_terminal(self.directory, self.fields)
        self.assertTrue(self.worker.marker.exists())
        self._post_failure_status()
        profile = {'mode': 'job', 'profile': {'adapter': 'fixture'}, 'timeoutSeconds': 5}
        self.worker.config['stages']['business-llm'] = profile
        request = {'stage': 'business-llm', 'binding': {'requestId': 'new'}, 'bodyBase64': 'e30=',
                   'expectedProfileSha256': hashlib.sha256(encoded(profile['profile'])).hexdigest(), 'expectedMode': 'job'}
        with patch.object(self.worker, '_validate_profile'):
            with self.assertRaisesRegex(ValueError, 'GPU owner exists'):
                self.worker.start(request)

    def test_post_write_failure_still_preserves_owner(self):
        original = gpu.persist
        def write_then_fail(path, value):
            original(path, value)
            raise OSError('post-write failure')
        with patch.object(gpu, 'persist', side_effect=write_then_fail):
            with self.assertRaises(OSError):
                self.worker._publish_terminal(self.directory, self.fields)
        self.assertTrue(self.worker.marker.exists())
        self.assertEqual('completed', json.loads((self.directory / 'status.json').read_bytes())['status'])

    def test_mismatched_owner_is_never_removed(self):
        owner = encoded({'operationId': 'b' * 32, 'state': 'occupied_or_release_unknown'})
        self.worker.marker.write_bytes(owner)
        with self.assertRaisesRegex(ValueError, 'does not belong'):
            self.worker._publish_terminal(self.directory, self.fields)
        self.assertEqual(owner, self.worker.marker.read_bytes())
        self._post_failure_status()

    def test_unknown_release_retains_owner(self):
        self.worker._publish_terminal(self.directory, {'status': 'release_unknown',
                                      'release': {'status': 'release_unknown'}})
        self.assertTrue(self.worker.marker.exists())
        self.worker.active.pop(self.operation_id)
        self.assertEqual('release_unknown', self.worker.status(self.operation_id)['status'])

    def test_start_cannot_enter_while_terminal_is_being_persisted(self):
        profile = {'mode': 'job', 'profile': {'adapter': 'fixture'}, 'timeoutSeconds': 5}
        self.worker.config['stages']['business-llm'] = profile
        request = {'stage': 'business-llm', 'binding': {'requestId': 'new'}, 'bodyBase64': 'e30=',
                   'expectedProfileSha256': hashlib.sha256(encoded(profile['profile'])).hexdigest(), 'expectedMode': 'job'}
        in_persist, allow_persist, validated, started = (threading.Event() for _ in range(4))
        original = gpu.persist
        errors = []
        def blocked_persist(path, value):
            if path == self.directory / 'status.json':
                in_persist.set()
                if not allow_persist.wait(5): raise AssertionError('terminal persist timed out')
            return original(path, value)
        def publish():
            try: self.worker._publish_terminal(self.directory, self.fields)
            except BaseException as error: errors.append(error)
        def start():
            try: self.worker.start(request); started.set()
            except BaseException as error: errors.append(error)
        with patch.object(gpu, 'persist', side_effect=blocked_persist), \
                patch.object(self.worker, '_validate_profile', side_effect=lambda _: validated.set()), \
                patch.object(self.worker, '_execute'):
            publisher = threading.Thread(target=publish); publisher.start()
            self.assertTrue(in_persist.wait(5))
            starter = threading.Thread(target=start); starter.start()
            self.assertTrue(validated.wait(5))
            try:
                self.assertFalse(started.wait(.1))
                self.assertTrue(self.worker.marker.exists())
            finally:
                allow_persist.set(); publisher.join(5); starter.join(5)
        self.assertFalse(publisher.is_alive())
        self.assertFalse(starter.is_alive())
        self.assertEqual([], errors)
        self.assertTrue(started.is_set())
        self.assertNotEqual(self.operation_id, json.loads(self.worker.marker.read_bytes())['operationId'])


if __name__ == '__main__':
    unittest.main()
