"""Native HTTP route with authored providers only; no live services or models."""
import copy
import unittest
import uuid
from unittest.mock import patch

from fastapi.testclient import TestClient
import test_window_server as window_tests
from structural_retrieval import StructuralRecipe
from token_windows import digest


class QueryMetadataWorkloadTests(unittest.TestCase):
    def setUp(self):
        window_tests.ServerTests.setUp(self)
        self.addCleanup(lambda: window_tests.ServerTests.tearDown(self))
        self.rt.structural_recipe = StructuralRecipe.from_environment({'CONSENSE_RETRIEVAL_GRANULARITY': 'dual-structure-v1'})
        from test_retrieval_source_units import parent
        self.p = parent(str(uuid.uuid4()), 'body:1', 'Authored clause content.', 'A1', 'body/1')
        self.p.update(originalSourceSha256='a' * 64)
        window_tests.ServerTests.index(self)
        self.client = TestClient(window_tests.s.app)
        self.addCleanup(self.client.close)

    def query(self, **changes):
        return self.client.post('/retrieve', json={'projectId': 'one', 'query': 'q', 'limit': 1, 'candidates': 2, **changes})

    def test_native_query_does_not_replan_and_full_validation_still_does(self):
        metadata = window_tests.s._projects['one']
        with patch.object(self.rt, 'prepared_structure', wraps=self.rt.prepared_structure) as full:
            self.rt.validate_metadata(metadata)
            self.assertEqual(full.call_count, 1)
        with patch.object(self.rt, 'prepared_structure', side_effect=AssertionError('Query must not replan')) as full, \
                patch.object(self.rt, 'signature_matches', wraps=self.rt.signature_matches) as signatures:
            response = self.query()
            self.assertEqual(response.status_code, 200, response.text)
            self.assertTrue(response.json()['hits'])
            self.assertEqual(full.call_count, 0)
            self.assertEqual(signatures.call_count, 1)

    def test_normal_seal_and_live_collection_count_tamper_fail_before_encoding(self):
        original = copy.deepcopy(window_tests.s._projects['one'])
        encoded = len(self.encodes)
        window_tests.s._projects['one']['vectorIntegrity']['sealSha256'] = '0' * 64
        response = self.query()
        self.assertEqual(response.status_code, 503, response.text)
        self.assertIn('INDEX_VECTOR_SEAL_CHANGED', response.text)
        self.assertEqual(encoded, len(self.encodes))
        window_tests.s._projects['one'] = original
        self.db.collections[original['collection']].pop(next(iter(self.db.collections[original['collection']])))
        response = self.query()
        self.assertEqual(response.status_code, 503, response.text)
        self.assertIn('INDEX_POINT_COUNT', response.text)
        self.assertEqual(encoded, len(self.encodes))

    def test_corpus_model_and_recipe_changes_still_fail_current_signature(self):
        metadata = window_tests.s._projects['one']
        original = copy.deepcopy(metadata)
        for kind in ('corpus', 'model', 'recipe'):
            with self.subTest(kind=kind):
                window_tests.s._projects['one'] = copy.deepcopy(original)
                if kind == 'corpus':
                    window_tests.s._projects['one']['chunks'][0]['content'] += ' changed'
                elif kind == 'model':
                    window_tests.s._projects['one']['signature']['embedding'] = {'name': 'unreviewed', 'revision': 'wrong'}
                else:
                    window_tests.s._projects['one']['signature']['windowRecipe']['max_tokens'] += 1
                encoded = len(self.encodes)
                response = self.query()
                self.assertEqual(response.status_code, 409, response.text)
                self.assertEqual(encoded, len(self.encodes))

    def test_selected_source_scope_stays_bound_and_rejects_unknown_source(self):
        with patch.object(self.rt, 'prepared_structure', side_effect=AssertionError('Query must not replan')):
            response = self.query(sourceSha256=['b' * 64])
            self.assertEqual(response.status_code, 422, response.text)
            response = self.query(sourceSha256=['a' * 64])
            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(response.json()['selectedSourceSha256'], ['a' * 64])

    def test_manifest_duplicate_and_unknown_structure_origin_tamper_fail_before_encoding(self):
        original = copy.deepcopy(window_tests.s._projects['one'])
        for kind in ('manifest', 'duplicate', 'origin'):
            with self.subTest(kind=kind):
                metadata = copy.deepcopy(original)
                window_tests.s._projects['one'] = metadata
                if kind == 'manifest':
                    metadata['windowManifestSha256'] = '0' * 64
                elif kind == 'duplicate':
                    metadata['windows'][1]['id'] = metadata['windows'][0]['id']
                    metadata['windowManifestSha256'] = digest(metadata['windows'])
                else:
                    metadata['windows'][0]['originParentIds'] = ['unknown-parent']
                    metadata['windowManifestSha256'] = digest(metadata['windows'])
                encoded = len(self.encodes)
                response = self.query()
                self.assertEqual(response.status_code, 503, response.text)
                self.assertEqual(encoded, len(self.encodes))

    def test_selected_dense_payload_must_still_equal_the_sealed_window(self):
        metadata = window_tests.s._projects['one']
        for point in self.db.collections[metadata['collection']].values():
            point.payload['content'] = 'wrong persisted source text'
        with patch.object(self.rt, 'prepared_structure', side_effect=AssertionError('Query must not replan')):
            response = self.query()
        self.assertEqual(response.status_code, 503, response.text)
        self.assertIn('DENSE_POINT_PAYLOAD_MISMATCH', response.text)


if __name__ == '__main__':
    unittest.main()
