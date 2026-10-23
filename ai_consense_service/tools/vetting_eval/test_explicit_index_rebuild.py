"""Explicit recovery builds a fresh sealed generation; fake encoders and owned CAS."""
import copy
import json
import unittest

import test_source_page_index as source_tests


class ExplicitIndexRebuildTests(unittest.TestCase):
    setUp = source_tests.SourcePageIndexHttpTests.setUp
    tearDown = source_tests.SourcePageIndexHttpTests.tearDown

    def index(self, rebuild=False):
        return self.client.post('/index-canonical', json={
            'projectId': 'AUTHORED-pins-index', 'module': 'vetting',
            'projectionSha256': [self.projection['projectionSha256']],
            **({'rebuild': rebuild} if rebuild is not False else {})})

    def test_explicit_rebuild_recovers_corruption_and_preserves_previous_evidence(self):
        self.assertEqual(200, self.index().status_code)
        key = 'AUTHORED-pins-index'; path = self.backend.metadata_path(key)
        before = path.read_bytes(); previous = json.loads(before)
        old_collection = previous['collection']
        self.db.collections[old_collection].pop(next(iter(self.db.collections[old_collection])))
        damaged = copy.deepcopy(self.db.collections[old_collection]); encoded = len(self.encodes)
        self.assertNotEqual(200, self.index().status_code)
        self.assertEqual(encoded, len(self.encodes))
        response = self.index(True)
        self.assertEqual(200, response.status_code, response.text)
        current = json.loads(path.read_bytes())
        self.assertNotEqual(old_collection, current['collection'])
        self.assertEqual(32, len(current['generation']))
        self.assertGreater(len(self.encodes), encoded)
        self.assertEqual(damaged, self.db.collections[old_collection])
        history = path.parent / current['previousMetadataFile']
        self.assertEqual(before, history.read_bytes())
        encoded = len(self.encodes)
        cached = self.index()
        self.assertEqual(200, cached.status_code, cached.text)
        self.assertTrue(cached.json()['cached'])
        self.assertEqual(encoded, len(self.encodes))
        status = self.client.post('/index-canonical/status', json={
            'projectId': key, 'module': 'vetting',
            'projectionSha256': [self.projection['projectionSha256']]})
        self.assertEqual(200, status.status_code, status.text)
        self.assertTrue(status.json()['ready'])

    def test_failed_explicit_rebuild_keeps_published_metadata_and_old_collection(self):
        self.assertEqual(200, self.index().status_code)
        path = self.backend.metadata_path('AUTHORED-pins-index')
        before = path.read_bytes(); previous = json.loads(before)
        old_points = copy.deepcopy(self.db.collections[previous['collection']])
        def failed_encode(values):
            raise RuntimeError('AUTHORED encode failure')
        self.backend.encode_embeddings = failed_encode
        response = self.index(True)
        self.assertNotEqual(200, response.status_code)
        self.assertEqual(before, path.read_bytes())
        self.assertEqual(old_points, self.db.collections[previous['collection']])
        self.assertNotIn('AUTHORED-pins-index', self.backend._projects)

    def test_rebuild_flag_requires_a_real_boolean(self):
        before = len(self.encodes)
        for value in ('true', 1, {}, None):
            response = self.index(value)
            self.assertEqual(422, response.status_code, response.text)
        self.assertEqual(before, len(self.encodes))

    def test_tampered_generation_is_rejected_without_encoding(self):
        self.assertEqual(200, self.index(True).status_code)
        path = self.backend.metadata_path('AUTHORED-pins-index')
        saved = json.loads(path.read_bytes()); saved['generation'] = '../another-index'
        path.write_text(json.dumps(saved), encoding='utf-8')
        before = len(self.encodes)
        self.assertNotEqual(200, self.index().status_code)
        self.assertEqual(before, len(self.encodes))
