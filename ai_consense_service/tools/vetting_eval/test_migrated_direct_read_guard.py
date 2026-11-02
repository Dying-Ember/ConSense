"""Standalone migrated access must use the verified owning source frame."""
from contextlib import ExitStack
from contextvars import ContextVar
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from migrated_qdrant_index import ENV
from token_windows import WindowError
import test_window_server as fixtures


s = fixtures.s


class MigratedDirectReadGuardTest(unittest.TestCase):
    def setUp(self):
        self.frame = ContextVar('direct_read_test_frame', default=None)
        self.metadata = {'projectId': 'one'}
        self.reader = SimpleNamespace(metadata=self.metadata, read=Mock(return_value=self.metadata))
        self.runtime = SimpleNamespace(source_registry=SimpleNamespace(_validation_frame=self.frame))
        self.guard = Mock()

    def patches(self):
        from contextlib import ExitStack
        stack = ExitStack()
        stack.enter_context(patch.dict(s.os.environ, {ENV: 'pinned'}, clear=True))
        stack.enter_context(patch.object(s, '_migrated_index', self.reader))
        stack.enter_context(patch.object(s, 'window_runtime', return_value=self.runtime))
        stack.enter_context(patch.object(s, 'verify_project_publication', self.guard))
        stack.enter_context(patch.object(s, 'database', side_effect=AssertionError('unexpected database')))
        return stack

    def test_missing_or_unverified_frame_rejects_before_reader(self):
        with self.patches():
            for frame in (None, {'indexMetadata': {}},
                          {'indexMetadata': {'one': {'migrationReader': self.reader, 'stored': self.metadata}}}):
                with self.subTest(frame=frame):
                    if frame is not None: frame['leaseStack'] = ExitStack()
                    token = self.frame.set(frame)
                    try:
                        with self.assertRaises(s.HTTPException) as error:
                            s.read_project('one')
                        self.assertEqual('MIGRATED_INDEX_SOURCE_FRAME_REQUIRED', error.exception.detail['kind'])
                    finally:
                        if frame is not None: frame['leaseStack'].close()
                        self.frame.reset(token)
            self.reader.read.assert_not_called()
            self.assertEqual(3, self.guard.call_count)

    def test_exact_verified_record_checks_lease_before_reader(self):
        check = Mock()
        record = {'migrationReader': self.reader, 'stored': self.metadata,
                  'migrationSourceScopeVerified': True, 'leaseCheck': check}
        with self.patches():
            stack = ExitStack()
            token = self.frame.set({'indexMetadata': {'one': record}, 'leaseStack': stack})
            try:
                self.assertIs(self.metadata, s.read_project('one'))
                check.assert_called_once_with()
                self.reader.read.assert_called_once_with('one', self.runtime, s.database)
                check.side_effect = WindowError('LEASE_EXPIRED', 'expired')
                with self.assertRaises(s.HTTPException):
                    s.read_project('one')
                self.assertEqual(1, self.reader.read.call_count)
            finally:
                stack.close()
                self.frame.reset(token)

    def test_journal_block_takes_priority(self):
        with self.patches():
            self.guard.side_effect = WindowError('JOURNAL_BLOCK', 'blocked')
            with self.assertRaises(s.HTTPException) as error:
                s.read_project('one')
            self.assertEqual('JOURNAL_BLOCK', error.exception.detail['kind'])
            self.reader.read.assert_not_called()

    def test_standalone_http_retrieve_cannot_bypass_guard(self):
        self.runtime.retrieve = lambda request: s.read_project(request.projectId)
        with self.patches(), patch.object(s, 'gpu_coordinator', return_value=None):
            response = TestClient(s.app).post('/retrieve', json={'projectId': 'one', 'query': 'hello'})
            self.assertEqual(409, response.status_code)
            self.assertEqual('MIGRATED_INDEX_SOURCE_FRAME_REQUIRED', response.json()['detail']['kind'])
            self.reader.read.assert_not_called()

    def test_ordinary_read_keeps_existing_path(self):
        metadata = {'projectId': 'one', 'signature': {}, 'chunks': []}
        runtime = SimpleNamespace(signature_matches=lambda signature, chunks: True)
        with patch.dict(s.os.environ, {}, clear=True), patch.object(s, '_migrated_index', None), \
                patch.object(s, 'window_runtime', return_value=runtime), \
                patch.object(s, 'load_project_metadata', return_value=metadata), \
                patch.object(s, 'database', side_effect=AssertionError('unexpected database')):
            self.assertIs(metadata, s.read_project('one'))


if __name__ == '__main__':
    unittest.main()
