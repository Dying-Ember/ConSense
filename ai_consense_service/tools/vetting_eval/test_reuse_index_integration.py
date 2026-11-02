import copy
import os
import uuid
from unittest.mock import patch
from qdrant_client import QdrantClient
from index_integrity import verify_reuse
from test_token_windows import parent
from test_window_server import ServerTests, s
from token_windows import WindowError


class ReuseIndexIntegration(ServerTests):
    def setUp(self):
        super().setUp()
        gpu = patch('same_project_vector_reuse.gpu_code_matches', return_value=True)
        gpu.start()
        self.addCleanup(gpu.stop)

    def test_full_index_path_reuses_only_same_source_windows(self):
        old_key = 'project-a::canonical::advisory::old'
        new_key = 'project-a::canonical::advisory::new'
        client = QdrantClient(':memory:')
        self.addCleanup(client.close)
        self.db = client
        self.rt.target_module = 'advisory'
        old_result = self.index(project=old_key)
        old = copy.deepcopy(s.read_project(old_key))
        old_collection = old['collection']
        before = client.count(old_collection, exact=True).count
        source = parent('new independent source', str(uuid.uuid4()), source_hash='b' * 64)
        start = len(self.encodes)

        class VerifiedReader:
            receipt = {'h2Project': 'project-a', 'module': 'advisory'}
            metadata = old
            def read(reader, key, runtime, database):
                verify_reuse(database(), old, key=old_key, collection=old_collection,
                             chunks=old['chunks'], signature=old['signature'])
                return old

        environment = {'CONSENSE_QDRANT_HISTORY_RECEIPT': '/tmp/fake',
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_SHA256': 'a' * 64,
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_PROJECT_ID': old_key,
                       'CONSENSE_QDRANT_URL': ''}
        with patch.dict(os.environ, environment), patch('migrated_qdrant_index.SealedHistoryIndex', return_value=VerifiedReader()):
            result = self.index([self.p, source], project=new_key)
        self.assertEqual(old_result['vectorPoints'], result['reusedWindows'])
        self.assertEqual(result['vectorPoints'] - result['reusedWindows'], result['encodedWindows'])
        self.assertEqual(result['encodedWindows'], sum(len(batch) for batch in self.encodes[start:]))
        self.assertEqual(before, client.count(old_collection, exact=True).count)
        self.assertNotEqual(old_collection, s.read_project(new_key)['collection'])

    def test_changed_model_falls_back_to_encoding(self):
        self.rt.target_module = 'advisory'
        old_key = 'project-a::canonical::advisory::old'
        new_key = 'project-a::canonical::advisory::new'
        class IncompatibleReader:
            receipt = {'h2Project': 'project-a', 'module': 'advisory'}
            metadata = {'projectId': old_key, 'collection': 'old'}
            def read(self, key, runtime, database):
                raise WindowError('MIGRATED_INDEX_QUERY_MODEL', 'model changed')
        environment = {'CONSENSE_QDRANT_HISTORY_RECEIPT': '/tmp/fake',
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_SHA256': 'a' * 64,
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_PROJECT_ID': old_key,
                       'CONSENSE_QDRANT_URL': ''}
        with patch.dict(os.environ, environment), patch('migrated_qdrant_index.SealedHistoryIndex', return_value=IncompatibleReader()):
            result = self.index(project=new_key)
        self.assertEqual(0, result['reusedWindows'])
        self.assertEqual(result['vectorPoints'], result['encodedWindows'])

    def test_changed_gpu_implementation_falls_back_to_encoding(self):
        self.rt.target_module = 'advisory'
        old_key = 'project-a::canonical::advisory::old'
        environment = {'CONSENSE_QDRANT_HISTORY_RECEIPT': '/tmp/fake',
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_SHA256': 'a' * 64,
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_PROJECT_ID': old_key,
                       'CONSENSE_QDRANT_URL': ''}
        with patch.dict(os.environ, environment), patch('same_project_vector_reuse.gpu_code_matches', return_value=False), \
             patch('migrated_qdrant_index.SealedHistoryIndex') as reader:
            result = self.index(project='project-a::canonical::advisory::new')
        reader.assert_not_called()
        self.assertEqual(0, result['reusedWindows'])
        self.assertEqual(result['vectorPoints'], result['encodedWindows'])

    def test_changed_old_vector_seal_stops_before_new_collection(self):
        self.rt.target_module = 'advisory'
        old_key = 'project-a::canonical::advisory::old'
        class DamagedReader:
            receipt = {'h2Project': 'project-a', 'module': 'advisory'}
            metadata = {'projectId': old_key, 'collection': 'old'}
            def read(self, key, runtime, database):
                raise WindowError('MIGRATED_INDEX_VECTOR_CHANGED', 'old seal changed')
        environment = {'CONSENSE_QDRANT_HISTORY_RECEIPT': '/tmp/fake',
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_SHA256': 'a' * 64,
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_PROJECT_ID': old_key,
                       'CONSENSE_QDRANT_URL': ''}
        with patch.dict(os.environ, environment), patch('migrated_qdrant_index.SealedHistoryIndex', return_value=DamagedReader()):
            with self.assertRaises(s.HTTPException) as error:
                self.index(project='project-a::canonical::advisory::new')
        self.assertEqual('MIGRATED_INDEX_VECTOR_CHANGED', error.exception.detail['kind'])
        self.assertFalse(self.db.collections)

    def test_gpu_change_before_publication_keeps_new_index_unpublished(self):
        self.rt.target_module = 'advisory'
        old_key = 'project-a::canonical::advisory::old'
        new_key = 'project-a::canonical::advisory::new'
        client = QdrantClient(':memory:')
        self.addCleanup(client.close)
        self.db = client
        self.index(project=old_key)
        old = copy.deepcopy(s.read_project(old_key))
        old_collection = old['collection']
        class VerifiedReader:
            receipt = {'h2Project': 'project-a', 'module': 'advisory'}
            metadata = old
            def read(reader, key, runtime, database):
                verify_reuse(database(), old, key=old_key, collection=old_collection,
                             chunks=old['chunks'], signature=old['signature'])
                return old
        environment = {'CONSENSE_QDRANT_HISTORY_RECEIPT': '/tmp/fake',
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_SHA256': 'a' * 64,
                       'CONSENSE_QDRANT_HISTORY_RECEIPT_PROJECT_ID': old_key,
                       'CONSENSE_QDRANT_URL': ''}
        with patch.dict(os.environ, environment), patch('migrated_qdrant_index.SealedHistoryIndex', return_value=VerifiedReader()), \
             patch('same_project_vector_reuse.gpu_code_matches', side_effect=[True, True, False]):
            with self.assertRaises(s.HTTPException) as error:
                self.index(project=new_key)
        self.assertEqual('REUSE_GPU_IMPLEMENTATION_CHANGED', error.exception.detail['kind'])
        self.assertFalse(s.metadata_path(new_key).exists())
        self.assertEqual(old['vectorPoints'], client.count(old_collection, exact=True).count)
