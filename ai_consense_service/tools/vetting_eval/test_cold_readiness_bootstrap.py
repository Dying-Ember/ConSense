"""Authored CAS and fake-vector bootstrap contracts; no live services or models."""
import copy
import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from oer_unified_server import create_unified_app
from test_source_page_index import SourcePageIndexHttpTests
from test_token_windows import CharacterTokenizer


class ColdReadinessBootstrapTests(unittest.TestCase):
    setUp = SourcePageIndexHttpTests.setUp
    tearDown = SourcePageIndexHttpTests.tearDown
    index = SourcePageIndexHttpTests.index

    def bootstrap(self, **changes):
        body = {'projectId': 'AUTHORED-pins-index', 'module': 'vetting',
            'projectionSha256': [self.projection['projectionSha256']], 'coldBootstrap': True}
        body.update(changes)
        return self.client.post('/index-canonical/availability', json=body)

    def cold_restart(self):
        self.client.close()
        self.backend._projects = {}
        self.app = create_unified_app(self.policy, backend=self.backend)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'AUTHORED-only'}
        self.client = TestClient(self.app)

    def test_cold_bootstrap_restores_exact_receipt_without_vector_scan_structure_model_or_writes(self):
        indexed = self.index()
        self.assertEqual(200, indexed.status_code, indexed.text)
        path = self.backend.metadata_path('AUTHORED-pins-index')
        stored = self.backend._projects['AUTHORED-pins-index']
        before = (path.read_bytes(), path.stat().st_mtime_ns, len(self.encodes),
            len(self.predictions), copy.deepcopy(self.db.collections))
        expected_generation = stored.get('generation')
        self.cold_restart()
        forbidden = lambda *a, **k: (_ for _ in ()).throw(AssertionError('bootstrap invoked full work'))
        with (patch.object(self.db, 'scroll', forbidden),
                patch.object(self.rt, 'validate_metadata', forbidden),
                patch.object(self.rt, 'prepared_structure', forbidden),
                patch('index_integrity.verify_reuse', forbidden),
                patch.object(self.backend, 'embedding_model', forbidden),
                patch.object(self.backend, 'rerank_model', forbidden),
                patch.object(self.backend, 'encode_embeddings', forbidden),
                patch.object(self.backend, 'index', forbidden),
                patch.object(self.db, 'upsert', forbidden),
                patch.object(self.db, 'create_collection', forbidden)):
            response = self.bootstrap()
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertTrue(result['ready'])
        self.assertTrue(result['readOnly'])
        self.assertTrue(result['coldBootstrap'])
        self.assertFalse(result['fullVectorIntegrityChecked'])
        self.assertEqual('live-canonical-index-availability-v1', result['indexAvailabilityContract'])
        self.assertEqual(indexed.json()['signature'], result['signature'])
        self.assertEqual(expected_generation, result['indexGeneration'])
        self.assertEqual(indexed.json()['indexed'], result['indexed'])
        self.assertEqual([self.projection['projectionSha256']], result['canonicalProjections'])
        self.assertEqual(before[:4], (path.read_bytes(), path.stat().st_mtime_ns,
            len(self.encodes), len(self.predictions)))
        self.assertEqual(before[4], self.db.collections)
        self.assertNotIn('chunks', result)
        self.assertNotIn('windows', result)
        self.assertLess(len(response.content), 4096)

    def test_only_explicit_strict_bootstrap_may_omit_both_expectations(self):
        self.assertEqual(200, self.index().status_code)
        stored = self.backend._projects['AUTHORED-pins-index']
        signed = self.backend.digest(stored['signature'])
        cases = [
            {'coldBootstrap': False}, {'coldBootstrap': 'true'}, {'coldBootstrap': 1},
            {'expectedSignature': signed}, {'expectedSignature': None},
            {'expectedGeneration': None}, {'expectedGeneration': 'a' * 32},
            {'unexpected': True},
        ]
        for changes in cases:
            with self.subTest(changes=changes):
                response = self.bootstrap(**changes)
                self.assertEqual(422, response.status_code, response.text)
        normal = {'projectId': 'AUTHORED-pins-index', 'module': 'vetting',
            'projectionSha256': [self.projection['projectionSha256']]}
        for extras in ({}, {'expectedSignature': None}, {'coldBootstrap': False}):
            response = self.client.post('/index-canonical/availability', json={**normal, **extras})
            self.assertEqual(422, response.status_code, response.text)

    def test_warm_mode_keeps_signature_generation_pins_and_echoes_false(self):
        self.assertEqual(200, self.index().status_code)
        stored = self.backend._projects['AUTHORED-pins-index']
        body = {'projectId': 'AUTHORED-pins-index', 'module': 'vetting',
            'projectionSha256': [self.projection['projectionSha256']],
            'expectedSignature': self.backend.digest(stored['signature']),
            'expectedGeneration': stored.get('generation')}
        response = self.client.post('/index-canonical/availability', json=body)
        self.assertEqual(200, response.status_code, response.text)
        self.assertFalse(response.json()['coldBootstrap'])
        for changed in ({'expectedSignature': '0' * 64}, {'expectedGeneration': '0' * 32}):
            response = self.client.post('/index-canonical/availability', json={**body, **changed})
            self.assertNotEqual(200, response.status_code, response.text)

    def test_health_advertises_bootstrap_without_inference(self):
        response = self.client.get('/health')
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual('source-verified-cold-canonical-availability-v1',
            response.json()['oer']['indexAvailabilityBootstrapContract'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_cold_bootstrap_rejects_scope_module_duplicate_or_missing_index(self):
        self.assertEqual(200, self.index().status_code)
        address = self.projection['projectionSha256']
        for changes in ({'module': 'advisory'}, {'projectionSha256': [address, address]},
                {'projectionSha256': ['0' * 64]}, {'projectId': 'AUTHORED-missing'},
                {'sourcePageEvidence': {'0' * 64: '1' * 64}}):
            response = self.bootstrap(**changes)
            self.assertNotEqual(200, response.status_code, response.text)

    def test_cold_bootstrap_rejects_live_seal_count_collection_or_schema_drift(self):
        for kind in ('missing_seal', 'changed_seal', 'missing_point', 'missing_collection',
                'wrong_dimension', 'wrong_distance'):
            with self.subTest(kind=kind):
                fixture = ColdReadinessBootstrapTests()
                fixture.setUp()
                try:
                    self.assertEqual(200, fixture.index().status_code)
                    key = 'AUTHORED-pins-index'
                    path = fixture.backend.metadata_path(key)
                    stored = json.loads(path.read_text(encoding='utf-8'))
                    if kind == 'missing_seal':
                        stored.pop('vectorIntegrity')
                        path.write_text(json.dumps(stored), encoding='utf-8')
                    elif kind == 'changed_seal':
                        stored['vectorIntegrity']['pointsSha256'] = '0' * 64
                        path.write_text(json.dumps(stored), encoding='utf-8')
                    elif kind == 'missing_point':
                        points = fixture.db.collections[stored['collection']]
                        points.pop(next(iter(points)))
                    elif kind == 'missing_collection':
                        fixture.db.collections.pop(stored['collection'])
                    else:
                        original = fixture.db.get_collection
                        def changed(name):
                            result = original(name)
                            if kind == 'wrong_dimension':
                                result.config.params.vectors.size += 1
                            else:
                                result.config.params.vectors.distance = 'dot'
                            return result
                        fixture.db.get_collection = changed
                    fixture.cold_restart()
                    encodes = len(fixture.encodes)
                    response = fixture.bootstrap()
                    self.assertNotEqual(200, response.status_code, (kind, response.text))
                    self.assertEqual(encodes, len(fixture.encodes))
                finally:
                    fixture.tearDown()

    def test_cold_bootstrap_rejects_current_source_runtime_and_stored_parent_drift(self):
        for kind in ('source_cas', 'runtime', 'parent', 'policy'):
            with self.subTest(kind=kind):
                fixture = ColdReadinessBootstrapTests()
                fixture.setUp()
                try:
                    self.assertEqual(200, fixture.index().status_code)
                    if kind == 'source_cas':
                        descriptor = fixture.projection['nativeCaptureRefs']['sourcePackage']
                        path = fixture.app.state.artifact_store.root / descriptor['relativePath']
                        raw, stat = path.read_bytes(), path.stat()
                        path.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
                        os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                    elif kind == 'parent':
                        path = fixture.backend.metadata_path('AUTHORED-pins-index')
                        stored = json.loads(path.read_text(encoding='utf-8'))
                        stored['chunks'][0]['sourceHash'] = '0' * 64
                        path.write_text(json.dumps(stored), encoding='utf-8')
                    elif kind == 'policy':
                        from dataclasses import replace
                        fixture.policy = replace(fixture.policy, manifest_sha256='0' * 64)
                    fixture.cold_restart()
                    if kind == 'runtime':
                        fixture.rt.identity = lambda current: {'kind': current, 'tokenizerSha256': 'AUTHORED-different-current-files'}
                    encodes = len(fixture.encodes)
                    response = fixture.bootstrap()
                    self.assertNotEqual(200, response.status_code, (kind, response.text))
                    self.assertEqual(encodes, len(fixture.encodes))
                finally:
                    fixture.tearDown()

    def test_bootstrap_exit_rejects_same_stamp_source_metadata_and_software_drift(self):
        for kind in ('source_cas', 'metadata', 'software'):
            with self.subTest(kind=kind):
                fixture = ColdReadinessBootstrapTests()
                fixture.setUp()
                try:
                    self.assertEqual(200, fixture.index().status_code)
                    import index_integrity
                    original = index_integrity.verify_availability
                    calls = 0
                    software_changed = False
                    algorithms = fixture.rt.algorithms
                    if kind == 'software':
                        def guarded_algorithms():
                            values = algorithms()
                            return {**values, 'bootstrap-authored-drift': '0' * 64} if software_changed else values
                        fixture.rt.algorithms = guarded_algorithms
                    def mutate(*args, **kwargs):
                        nonlocal calls, software_changed
                        result = original(*args, **kwargs)
                        calls += 1
                        if calls == 1:
                            if kind == 'source_cas':
                                descriptor = fixture.projection['nativeCaptureRefs']['sourcePackage']
                                path = fixture.app.state.artifact_store.root / descriptor['relativePath']
                                raw, stat = path.read_bytes(), path.stat()
                                path.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
                                os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                            elif kind == 'metadata':
                                path = fixture.backend.metadata_path('AUTHORED-pins-index')
                                raw, stat = path.read_bytes(), path.stat()
                                changed = raw.replace(b'AUTHORED-pins-index', b'AUTHORED-pins-INDEX', 1)
                                self.assertEqual(len(raw), len(changed))
                                self.assertNotEqual(raw, changed)
                                path.write_bytes(changed)
                                os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                            else:
                                software_changed = True
                        return result
                    with patch.object(index_integrity, 'verify_availability', mutate):
                        response = fixture.bootstrap()
                    self.assertNotEqual(200, response.status_code, (kind, response.text))
                    self.assertGreater(calls, 0)
                finally:
                    fixture.tearDown()

    def test_bootstrap_does_not_claim_full_integrity_for_same_count_vector_corruption(self):
        self.assertEqual(200, self.index().status_code)
        stored = self.backend._projects['AUTHORED-pins-index']
        point = next(iter(self.db.collections[stored['collection']].values()))
        point.vector = [0., 1.]
        response = self.bootstrap()
        self.assertEqual(200, response.status_code, response.text)
        self.assertFalse(response.json()['fullVectorIntegrityChecked'])
        full = self.client.post('/index-canonical/status', json={'projectId': 'AUTHORED-pins-index',
            'module': 'vetting', 'projectionSha256': [self.projection['projectionSha256']]})
        self.assertNotEqual(200, full.status_code, full.text)


class NormalColdReadinessBootstrapTests(unittest.TestCase):
    import test_normal_conditional_wire as _normal
    def setUp(self):
        module = patch.dict(os.environ, {'NORMAL_CONDITIONAL_TEST_MODULE': 'advisory'})
        module.start()
        self.addCleanup(module.stop)
        self._normal.NormalConditionalWireTests.setUp(self)
    tearDown = _normal.NormalConditionalWireTests.tearDown
    publish = _normal.NormalConditionalWireTests.publish
    warm = _normal.NormalConditionalWireTests.warm
    url = _normal.NormalConditionalWireTests.url

    def test_advisory_normal_bootstrap_reuses_exact_registered_source_without_full_work(self):
        source, _ = self.warm()
        self.assertEqual('advisory', self.module)
        body = {'projectId': 'AUTHORED-normal-bootstrap', 'module': self.module,
            'projectionSha256': [source['projectionSha256']]}
        indexed = self.client.post('/index-canonical', json=body)
        self.assertEqual(200, indexed.status_code, indexed.text)
        stored = self.backend._projects.pop(body['projectId'])
        before = (len(self.encodes), len(self.predictions), copy.deepcopy(self.db.collections))
        forbidden = lambda *a, **k: (_ for _ in ()).throw(AssertionError('normal bootstrap invoked full work'))
        with (patch.object(self.db, 'scroll', forbidden),
                patch.object(self.rt, 'validate_metadata', forbidden),
                patch.object(self.rt, 'prepared_structure', forbidden),
                patch('index_integrity.verify_reuse', forbidden),
                patch.object(self.backend, 'encode_embeddings', forbidden),
                patch.object(self.db, 'upsert', forbidden)):
            response = self.client.post('/index-canonical/availability', json={**body, 'coldBootstrap': True})
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual(indexed.json()['signature'], result['signature'])
        self.assertEqual(stored.get('generation'), result['indexGeneration'])
        self.assertEqual(indexed.json()['indexed'], result['indexed'])
        self.assertEqual(body['projectionSha256'], result['canonicalProjections'])
        self.assertTrue(result['coldBootstrap'])
        self.assertFalse(result['fullVectorIntegrityChecked'])
        self.assertEqual(before, (len(self.encodes), len(self.predictions), self.db.collections))
