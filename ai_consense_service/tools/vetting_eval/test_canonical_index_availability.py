"""Live availability over authored CAS and fake vectors; no service/model calls."""
import copy
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import test_source_page_index as source_tests


class CanonicalAvailabilityTests(unittest.TestCase):
    setUp = source_tests.SourcePageIndexHttpTests.setUp
    tearDown = source_tests.SourcePageIndexHttpTests.tearDown
    index = source_tests.SourcePageIndexHttpTests.index

    def availability(self, **changes):
        stored = self.backend._projects.get('AUTHORED-pins-index')
        if stored is None:
            stored = json.loads(self.backend.metadata_path('AUTHORED-pins-index').read_text(encoding='utf-8'))
        body = {'projectId': 'AUTHORED-pins-index', 'module': 'vetting',
            'projectionSha256': [self.projection['projectionSha256']],
            'expectedSignature': self.backend.digest(stored['signature']),
            'expectedGeneration': stored.get('generation')}
        body.update(changes)
        return self.client.post('/index-canonical/availability', json=body)

    def test_live_checks_do_not_scroll_vectors_reconstruct_structure_or_reparse_warm_metadata(self):
        self.assertEqual(200, self.index().status_code)
        path = self.backend.metadata_path('AUTHORED-pins-index')
        before = (path.read_bytes(), path.stat().st_mtime_ns, len(self.encodes), copy.deepcopy(self.db.collections))
        forbidden = lambda *a, **k: (_ for _ in ()).throw(AssertionError('availability performed full work'))
        with patch.object(self.db, 'scroll', forbidden), patch.object(self.rt, 'validate_metadata', forbidden), \
                patch.object(self.rt, 'prepared_structure', forbidden):
            first = self.availability()
            self.assertEqual(200, first.status_code, first.text)
            reads = []
            read = Path.read_bytes
            def traced(current):
                if current == path:
                    reads.append(str(current))
                return read(current)
            with patch.object(Path, 'read_bytes', traced):
                second = self.availability()
            self.assertEqual(200, second.status_code, second.text)
            self.assertEqual([], reads, 'Warm availability streams current bytes but does not materialize metadata again')
        result = second.json()
        self.assertTrue(result['ready'])
        self.assertFalse(result['fullVectorIntegrityChecked'])
        self.assertEqual('live-canonical-index-availability-v1', result['indexAvailabilityContract'])
        self.assertEqual(2 * len(before[0]), result['metadataBytesRead'])
        self.assertEqual(before[:3], (path.read_bytes(), path.stat().st_mtime_ns, len(self.encodes)))
        self.assertEqual(before[3], self.db.collections)
        self.assertNotIn('chunks', result)
        self.assertNotIn('windows', result)
        self.assertLess(len(second.content), 4096)
        print('AVAILABILITY_RESIDUAL', json.dumps({'metadataFileBytes': len(before[0]),
            'warmMetadataBytesHashed': result['metadataBytesRead'], 'responseBytes': len(second.content),
            'elapsedSeconds': result['elapsedSeconds'], 'vectorScrollCalls': 0}))

    def test_stale_signature_and_generation_are_rejected(self):
        self.assertEqual(200, self.index().status_code)
        for changes in ({'expectedSignature': '0' * 64}, {'expectedGeneration': '0' * 32}):
            response = self.availability(**changes)
            self.assertNotEqual(200, response.status_code, response.text)

    def test_live_count_schema_and_dimension_are_checked_on_every_call(self):
        for kind in ('missing_point', 'missing_collection', 'wrong_dimension', 'wrong_distance'):
            with self.subTest(kind=kind):
                fixture = CanonicalAvailabilityTests('test_stale_signature_and_generation_are_rejected')
                fixture.setUp()
                try:
                    self.assertEqual(200, fixture.index().status_code)
                    self.assertEqual(200, fixture.availability().status_code)
                    stored = fixture.backend._projects['AUTHORED-pins-index']
                    if kind == 'missing_point':
                        fixture.db.collections[stored['collection']].pop(next(iter(fixture.db.collections[stored['collection']])))
                    elif kind == 'missing_collection':
                        fixture.db.collections.pop(stored['collection'])
                    else:
                        original = fixture.db.get_collection
                        def changed(name):
                            result = original(name)
                            vectors = result.config.params.vectors
                            if kind == 'wrong_dimension':
                                vectors.size += 1
                            else:
                                vectors.distance = 'dot'
                            return result
                        fixture.db.get_collection = changed
                    response = fixture.availability()
                    self.assertNotEqual(200, response.status_code, (kind, response.text))
                finally:
                    fixture.tearDown()

    def test_same_size_metadata_change_with_restored_mtime_is_not_hidden_by_warm_tree(self):
        self.assertEqual(200, self.index().status_code)
        self.assertEqual(200, self.availability().status_code)
        path = self.backend.metadata_path('AUTHORED-pins-index')
        before, stat = path.read_bytes(), path.stat()
        changed = before.replace(b'AUTHORED-pins-index', b'AUTHORED-pins-INDEX', 1)
        self.assertNotEqual(before, changed)
        self.assertEqual(len(before), len(changed))
        path.write_bytes(changed)
        os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        response = self.availability()
        self.assertNotEqual(200, response.status_code, response.text)

    def test_current_source_cas_change_still_rejects(self):
        self.assertEqual(200, self.index().status_code)
        self.assertEqual(200, self.availability().status_code)
        descriptor = self.projection['nativeCaptureRefs']['sourcePackage']
        path = self.app.state.artifact_store.root / descriptor['relativePath']
        raw, stat = path.read_bytes(), path.stat()
        path.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
        os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        response = self.availability()
        self.assertNotEqual(200, response.status_code, response.text)

    def test_availability_does_not_claim_full_vector_integrity_and_full_status_still_detects_corruption(self):
        self.assertEqual(200, self.index().status_code)
        stored = self.backend._projects['AUTHORED-pins-index']
        point = next(iter(self.db.collections[stored['collection']].values()))
        point.vector = [0., 1.]
        response = self.availability()
        self.assertEqual(200, response.status_code, response.text)
        self.assertFalse(response.json()['fullVectorIntegrityChecked'])
        full = self.client.post('/index-canonical/status', json={'projectId': 'AUTHORED-pins-index',
            'module': 'vetting', 'projectionSha256': [self.projection['projectionSha256']]})
        self.assertNotEqual(200, full.status_code, full.text)


if __name__ == '__main__':
    unittest.main()
