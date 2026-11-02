"""Cold-load journal ordering with authored fixtures; no real release is certified."""
from types import SimpleNamespace
from contextvars import ContextVar
import unittest
from unittest.mock import patch

import oer_request_evidence as evidence
import qdrant_client.models
import server
import source_page_index
import window_runtime
from fastapi import HTTPException
from source_request_context import request_context
import test_oer_publication_journal as journal_fixtures
from token_windows import WindowError


class MetadataColdLoadTest(unittest.TestCase):
    def setUp(self):
        fixture = journal_fixtures.PublicationJournalTest('test_cached_metadata_tampering_rejected_for_read_and_recovery')
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.fixture = fixture
        self.journal = fixture.journal
        self.state = fixture.root / 'state'
        self.patchers = [patch.object(server, 'STATE', self.state),
                         patch.object(server, '_request_evidence', self.journal),
                         patch.object(server, '_projects', {}),
                         patch.object(server, 'window_runtime', return_value=SimpleNamespace(source_registry=None))]
        for patcher in self.patchers:
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_remote_unverified_existing_metadata_is_not_read(self):
        path = self.journal.metadata_path('p1')
        path.write_bytes(b'{"projectId":"p1"}')
        with patch.object(server, '_remote_models', object()), \
                patch.object(type(path), 'read_bytes', side_effect=AssertionError('metadata read before proof')) as read:
            with self.assertRaisesRegex(WindowError, 'verified publication'):
                server.load_project_metadata('p1')
            read.assert_not_called()

    def test_remote_missing_metadata_remains_404_without_preflight(self):
        with patch.object(server, '_remote_models', object()), \
                patch.object(self.journal, 'require_verified_index_for_load', wraps=self.journal.require_verified_index_for_load) as gate:
            with self.assertRaises(HTTPException) as caught:
                server.load_project_metadata('absent')
            self.assertEqual(404, caught.exception.status_code)
            gate.assert_not_called()

    def test_local_legacy_metadata_does_not_enter_remote_preflight(self):
        path = self.journal.metadata_path('p1')
        path.write_bytes(b'{"projectId":"p1"}')
        with patch.object(server, '_remote_models', None), \
                patch.object(self.journal, 'require_verified_index_for_load', side_effect=AssertionError('remote gate')) as gate:
            self.assertEqual('p1', server.load_project_metadata('p1')['projectId'])
            gate.assert_not_called()

    def test_remote_simulated_verified_still_validates_loaded_metadata(self):
        fixture = self.fixture
        # Only durability and independent release proof are simulated; the actual journal validators run.
        with patch.object(evidence, '_sync_directory'), patch.object(evidence, '_sync_file'), \
                patch.object(evidence, '_sync_open_file'), \
                patch.object(self.journal, '_verify_publication_evidence', return_value=fixture.proof):
            with request_context(fixture.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = fixture.staged(record)
                fixture.complete(record)
            path = self.journal.metadata_path('p1')
            with patch.object(server, '_remote_models', object()), \
                    patch.object(self.journal, 'verify_index', wraps=self.journal.verify_index) as verify:
                self.assertEqual(value['generation'], server.load_project_metadata('p1')['generation'])
                self.assertTrue(any(call.args[1] is not None for call in verify.call_args_list))
                before = path.read_bytes()
                altered = before.replace(b'"n": 1', b'"n": 2')
                self.assertNotEqual(before, altered)
                path.write_bytes(altered)
                with self.assertRaises(WindowError):
                    server.load_project_metadata('p1')

    def test_index_remote_existing_metadata_is_not_opened(self):
        path = self.journal.metadata_path('p1')
        path.write_bytes(b'{"projectId":"p1"}')
        runtime = SimpleNamespace(s=server, structural_recipe=SimpleNamespace(enabled=False), signature=lambda chunks: {})
        request = SimpleNamespace(projectId='p1', chunks=[], rebuild=False)
        with patch.object(server, '_remote_models', object()), \
                patch.object(window_runtime, 'file_identity', side_effect=AssertionError('identity before proof')) as identity, \
                patch.object(type(path), 'read_text', side_effect=AssertionError('metadata before proof')) as read:
            with self.assertRaisesRegex(WindowError, 'verified publication'):
                window_runtime.WindowRuntime.index(runtime, request)
            identity.assert_not_called()
            read.assert_not_called()

    def test_index_local_existing_and_remote_missing_reach_next_stage(self):
        path = self.journal.metadata_path('p1')
        path.write_bytes(b'{"projectId":"p1"}')
        runtime = SimpleNamespace(s=server, structural_recipe=SimpleNamespace(enabled=False), signature=lambda chunks: {},
                                  index_collection=lambda *args: (_ for _ in ()).throw(RuntimeError('next index stage')))
        request = SimpleNamespace(projectId='p1', chunks=[], rebuild=False)
        with patch.object(server, '_remote_models', None), \
                patch.object(window_runtime, 'file_identity', side_effect=RuntimeError('local identity reached')), \
                patch.object(self.journal, 'require_verified_index_for_load', side_effect=AssertionError('remote gate')) as gate:
            with self.assertRaisesRegex(RuntimeError, 'local identity reached'):
                window_runtime.WindowRuntime.index(runtime, request)
            gate.assert_not_called()
        path.unlink()
        with patch.object(server, '_remote_models', object()), \
                patch.object(self.journal, 'require_verified_index_for_load', side_effect=AssertionError('missing gate')) as gate:
            with self.assertRaisesRegex(RuntimeError, 'next index stage'):
                window_runtime.WindowRuntime.index(runtime, request)
            gate.assert_not_called()

    def test_source_page_retrieve_cold_remote_preflight_and_local_or_missing_paths(self):
        class Base:
            def retrieve(self, request):
                raise RuntimeError('next retrieve stage')
        class Runtime(source_page_index.SourcePageIndexRuntime, Base):
            pass
        runtime = Runtime()
        runtime.s = server
        runtime.source_page_index_pins = ContextVar('authored-pins', default=None)
        path = self.journal.metadata_path('p1')
        path.write_bytes(b'{"projectId":"p1","signature":{}}')
        request = SimpleNamespace(projectId='p1')
        with patch.object(server, '_remote_models', object()), \
                patch.object(type(path), 'read_text', side_effect=AssertionError('metadata before proof')) as read:
            with self.assertRaisesRegex(WindowError, 'verified publication'):
                runtime.retrieve(request)
            read.assert_not_called()
        with patch.object(server, '_remote_models', None), \
                patch.object(self.journal, 'require_verified_index_for_load', side_effect=AssertionError('remote gate')) as gate:
            with self.assertRaisesRegex(RuntimeError, 'next retrieve stage'):
                runtime.retrieve(request)
            gate.assert_not_called()
        path.unlink()
        with patch.object(server, '_remote_models', object()), \
                patch.object(self.journal, 'require_verified_index_for_load', side_effect=AssertionError('missing gate')) as gate:
            with self.assertRaisesRegex(RuntimeError, 'next retrieve stage'):
                runtime.retrieve(request)
            gate.assert_not_called()


if __name__ == '__main__':
    unittest.main()
