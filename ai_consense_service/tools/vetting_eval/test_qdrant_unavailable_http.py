"""Qdrant availability failures retain their safe kind and HTTP semantics."""
from contextvars import ContextVar
from contextlib import ExitStack
import hashlib
import json
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from migrated_qdrant_index import ENV
from migration_project_registry import REGISTRY, SCHEMA
import test_source_page_index as source_fixture
import test_window_server as window_fixture
from token_windows import WindowError


KINDS = ('QDRANT_BACKEND_UNAVAILABLE', 'QDRANT_BACKEND_RELEASE_UNKNOWN')


class QdrantUnavailableHttpTest(unittest.TestCase):
    def test_reusable_index_and_retrieve_return_unavailable(self):
        for kind in KINDS:
            with self.subTest(kind=kind):
                fixture = window_fixture.ServerTests('runTest')
                fixture.setUp()
                try:
                    fixture.index()
                    backend = window_fixture.s
                    with TestClient(backend.app, raise_server_exceptions=False) as client, \
                         patch.object(backend, 'database', side_effect=WindowError(kind, 'Unavailable')):
                        results = [client.post('/index', json={'projectId':'one', 'chunks':[fixture.p]}),
                                   client.post('/retrieve', json={'projectId':'one', 'query':'q', 'limit':1, 'candidates':2})]
                    for response in results:
                        self.assertEqual(503, response.status_code, response.text)
                        self.assertEqual(kind, response.json()['detail']['kind'])
                        self.assertNotIn('authored-secret', response.text)
                finally:
                    fixture.tearDown()
                    fixture.doCleanups()

    def test_unified_status_and_availability_return_unavailable(self):
        for kind in KINDS:
            with self.subTest(kind=kind):
                fixture = source_fixture.SourcePageIndexHttpTests('runTest')
                fixture.setUp()
                try:
                    self.assertEqual(200, fixture.index().status_code)
                    body = {'projectId':'AUTHORED-pins-index', 'module':'vetting',
                            'projectionSha256':[fixture.projection['projectionSha256']]}
                    with patch.object(fixture.backend, 'database', side_effect=WindowError(kind, 'Unavailable')):
                        for endpoint in ('/index-canonical/status', '/index-canonical/availability'):
                            request = {**body, **({'coldBootstrap':True} if endpoint.endswith('availability') else {})}
                            response = fixture.client.post(endpoint, json=request)
                            self.assertEqual(503, response.status_code, response.text)
                            self.assertEqual(kind, response.json()['detail']['kind'])
                        response = fixture.client.post('/retrieve', json={'projectId':body['projectId'],
                                                       'query':'Visible', 'candidates':4, 'limit':2})
                        self.assertEqual(503, response.status_code, response.text)
                        self.assertEqual(kind, response.json()['detail']['kind'])
                finally:
                    fixture.tearDown()
                    fixture.doCleanups()

    def test_migrated_reader_maps_only_backend_faults_to_503(self):
        backend = window_fixture.s
        frame_var = ContextVar('migration_http_test_frame', default=None)
        metadata = {'projectId':'one'}
        reader = SimpleNamespace(metadata=metadata, read=Mock())
        runtime = SimpleNamespace(source_registry=SimpleNamespace(_validation_frame=frame_var))
        record = {'migrationReader':reader, 'stored':metadata,
                  'migrationSourceScopeVerified':True, 'leaseCheck':lambda:None}
        with patch.dict(backend.os.environ, {ENV:'authored'}), \
             patch.object(backend, '_migrated_index', reader), \
             patch.object(backend, 'window_runtime', return_value=runtime), \
             patch.object(backend, 'verify_project_publication'), ExitStack() as leases:
            token = frame_var.set({'indexMetadata':{'one':record}, 'leaseStack':leases})
            try:
                for kind in (*KINDS, 'OTHER_AUTHORED'):
                    reader.read.side_effect = WindowError(kind, 'Unavailable')
                    with self.assertRaises(backend.HTTPException) as error:
                        backend.read_project('one')
                    self.assertEqual(503 if kind in KINDS else 409, error.exception.status_code)
                    self.assertEqual(kind, error.exception.detail['kind'])
            finally:
                frame_var.reset(token)

    def test_blocked_journal_precedes_database(self):
        fixture = window_fixture.ServerTests('runTest')
        fixture.setUp()
        try:
            fixture.index()
            backend = window_fixture.s
            with TestClient(backend.app, raise_server_exceptions=False) as client, \
                 patch.object(backend, 'verify_project_publication',
                              side_effect=WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Blocked')), \
                 patch.object(backend, 'database', side_effect=AssertionError('must not open')) as database:
                response = client.post('/retrieve', json={'projectId':'one', 'query':'q', 'limit':1, 'candidates':2})
            self.assertEqual(503, response.status_code, response.text)
            self.assertEqual('INDEX_PUBLICATION_DURABILITY_UNKNOWN', response.json()['detail']['kind'])
            database.assert_not_called()
        finally:
            fixture.tearDown()
            fixture.doCleanups()

    def test_unregistered_project_cannot_write(self):
        fixture = source_fixture.SourcePageIndexHttpTests('runTest')
        fixture.setUp()
        try:
            document = {'schema':SCHEMA, 'entries':[{'module':'vetting', 'projectId':'known', 'mode':'ordinary'}]}
            raw = json.dumps(document).encode('utf-8')
            path = fixture.root / 'registry.json'
            path.write_bytes(raw)
            environment = {REGISTRY:str(path), REGISTRY + '_SHA256':hashlib.sha256(raw).hexdigest()}
            body = {'projectId':'unregistered', 'module':'vetting',
                    'projectionSha256':[fixture.projection['projectionSha256']]}
            with patch.dict(fixture.backend.os.environ, environment), \
                 patch.object(fixture.backend, 'database') as database, \
                 patch.object(fixture.backend, 'encode_embeddings') as encode:
                response = fixture.client.post('/index-canonical', json=body)
            self.assertNotEqual(200, response.status_code, response.text)
            self.assertEqual('MIGRATION_ROUTE_UNAVAILABLE', response.json()['detail']['kind'])
            self.assertFalse(fixture.backend.metadata_path('unregistered').exists())
            database.assert_not_called(); encode.assert_not_called()
        finally:
            fixture.tearDown()
            fixture.doCleanups()


if __name__ == '__main__':
    unittest.main()
