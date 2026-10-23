"""Production ASGI route over authored actual CAS; no model or live service."""
import json
import unittest
from unittest.mock import patch

from retrieval_source_units import POLICY, SourceUnits
from source_read_server import CAPABILITY, CONTRACT
import test_oer_unified_server as base


class SourceReadHttpTests(unittest.TestCase):
    setUp = base.UnifiedHttpTests.setUp
    tearDown = base.UnifiedHttpTests.tearDown
    publish_pdf = base.UnifiedHttpTests.publish_pdf
    publish_all = base.UnifiedHttpTests.publish_all

    def read(self, operation='catalog', sources=None, **changes):
        sources = self.sources if sources is None else sources
        body = {'module': 'vetting', 'projectionSha256': [s['projectionSha256'] for s in sources],
                'operation': operation, 'view': 'canonical_units'}
        body.update(changes)
        return self.client.post('/sources/read', json=body)

    def publish(self):
        self.sources = self.publish_all()
        return [p for s in self.sources for p in self.registry.chunks(s['projectionSha256'])]

    def test_health_catalog_are_compact_and_do_not_resolve_or_infer(self):
        parents = self.publish()
        with patch.object(SourceUnits, 'resolve', side_effect=AssertionError('catalog resolved content')), \
                patch.object(self.rt, 'tokenizer', side_effect=AssertionError('model forbidden')):
            health = self.client.get('/health')
            response = self.read()
        self.assertEqual(200, health.status_code, health.text)
        self.assertEqual(CAPABILITY, health.json()['oer']['sourceRead'])
        self.assertIn('/sources/read', health.json()['oer']['requestSourceValidation']['routes'])
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual(CONTRACT, result['contract'])
        self.assertEqual(POLICY, result['sourceUnitPolicy'])
        self.assertEqual([], result['units']); self.assertEqual([], result['origins'])
        self.assertEqual(len(parents), sum(s['chunkCount'] for s in result['sources']))
        for source, expected in zip(result['sources'], self.sources):
            self.assertEqual(source['projectionSha256'], expected['projectionSha256'])
            self.assertTrue({'documentId', 'originalSourceSha256', 'sourceHash', 'sourceRevisionSha256',
                             'chunkCount'} <= set(source))
        for parent in parents:
            self.assertNotIn(parent['content'], response.text)
        self.assertNotIn('candidateGraph', response.text); self.assertNotIn('chunks', response.text)
        self.assertIn('unsupported', result['limitations']['templateObservations'])
        self.assertIn('unknown', result['limitations']['normalWordScope'])
        self.assertEqual([], self.encodes); self.assertEqual([], self.predictions)

    def test_ids_preserve_request_order_exact_units_members_and_relations(self):
        parents = self.publish()
        ids = [parents[-1]['id'], parents[0]['id'], parents[1]['id']]
        response = self.read('ids', originIds=ids)
        self.assertEqual(200, response.status_code, response.text)
        result = response.json(); catalog = SourceUnits(parents)
        self.assertEqual(ids, result['coverage']['requestedOriginIds'])
        self.assertEqual(ids, [p['id'] for p in result['origins']])
        self.assertEqual([catalog.resolve(catalog.parents[id_]) for id_ in ids], result['units'])
        for origin in result['origins']:
            self.assertEqual(catalog.parents[origin['id']], origin)
        self.assertIsNone(result['nextCursor'])
        self.assertEqual([], self.encodes); self.assertEqual([], self.predictions)

    def test_scan_all_origins_once_in_actual_order_with_bounded_pages(self):
        parents = self.publish(); expected = SourceUnits(parents)
        cursor, origins, units = None, [], []
        for _ in range(len(parents) + 1):
            response = self.read('scan', cursor=cursor, maxItems=1, maxBytes=250000)
            self.assertEqual(200, response.status_code, response.text)
            self.assertLessEqual(len(response.content), 250000)
            result = response.json(); self.assertEqual(1, len(result['origins']))
            origins.extend(result['origins']); units.extend(result['units'])
            self.assertEqual(len(origins), result['coverage']['processedOrigins'])
            cursor = result['nextCursor']
            if cursor is None:
                self.assertEqual('complete', result['coverage']['status'])
                self.assertEqual(0, result['coverage']['remainingOrigins'])
                break
        self.assertEqual(parents, origins)
        self.assertEqual(len(parents), len({p['id'] for p in origins}))
        self.assertEqual([expected.resolve(p) for p in parents], units)

    def test_budget_never_truncates_a_source_unit_and_can_resume(self):
        parents = self.publish()
        catalog = self.read(sources=[self.sources[0]])
        complete = self.read('ids', sources=[self.sources[0]], originIds=[parents[0]['id']])
        self.assertEqual(200, complete.status_code, complete.text)
        budget = len(catalog.content) + 1400
        self.assertLess(budget, len(complete.content))
        response = self.read('scan', sources=[self.sources[0]], maxBytes=budget)
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual('unit_too_large', result['coverage']['status'])
        self.assertEqual([], result['units']); self.assertEqual([], result['origins'])
        self.assertEqual(parents[0]['id'], result['blockedOrigin']['id'])
        self.assertEqual(0, result['coverage']['processedOrigins'])
        self.assertLessEqual(len(response.content), budget)
        resumed = self.read('scan', sources=[self.sources[0]], cursor=result['nextCursor'])
        self.assertEqual(200, resumed.status_code, resumed.text)
        self.assertEqual(parents[0], resumed.json()['origins'][0])
        rejected = self.read('ids', sources=[self.sources[0]], originIds=[parents[0]['id']], maxBytes=budget)
        self.assertEqual(422, rejected.status_code, rejected.text)
        self.assertEqual('SOURCE_READ_BUDGET_EXCEEDED', rejected.json()['detail']['kind'])

    def test_partial_byte_budget_preserves_completed_units_and_resumes_next_origin(self):
        parents = self.publish()
        sources = [self.sources[0]]
        first = self.read('scan', sources=sources, maxItems=1)
        self.assertEqual(200, first.status_code, first.text)
        budget = len(first.content) + 512
        response = self.read('scan', sources=sources, maxBytes=budget)
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual(first.json()['units'], result['units'])
        self.assertEqual('partial', result['coverage']['status'])
        self.assertEqual(1, result['coverage']['processedOrigins'])
        self.assertLessEqual(len(response.content), budget)
        resumed = self.read('scan', sources=sources, cursor=result['nextCursor'], maxItems=1)
        self.assertEqual(200, resumed.status_code, resumed.text)
        self.assertEqual(parents[1], resumed.json()['origins'][0])

    def test_normal_observations_keep_unknown_authority_and_complete_table_payload(self):
        from normal_observed_projection import produce_normal_observed
        from test_normal_observed import fixture
        body = fixture(self.policy)
        source = produce_normal_observed(body['candidateGraph'], body['sourceBinding'], artifact_store=self.store)
        self.registry.publish(source); self.sources = [source]
        ids = [p['id'] for p in source['chunks']]
        response = self.read('ids', originIds=ids)
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual(source['chunks'], result['origins'])
        self.assertEqual([SourceUnits(source['chunks']).resolve(p) for p in source['chunks']], result['units'])
        self.assertEqual(source['chunks'][0]['tableGroup'], result['origins'][0]['tableGroup'])
        self.assertEqual('candidate_unverified', result['origins'][0]['adoptionStatus'])
        self.assertIs(False, result['origins'][0]['humanConfirmed'])
        self.assertIn('unknown', result['limitations']['normalWordScope'])

    def test_family_uses_registered_family_revision_and_same_resolver(self):
        import test_native_pdf_source_family_http as family_tests
        fixture = family_tests.NativePdfSourceFamilyHttpTests(
            'test_normal_publication_has_distinct_family_pins_and_preserves_complete_child_identity')
        fixture.setUp()
        try:
            fixture.published()
            source = fixture.expected
            response = fixture.client.post('/sources/read', json={'operation': 'scan', 'module': 'vetting',
                'projectionSha256': [source['projectionSha256']]})
            self.assertEqual(200, response.status_code, response.text)
            result = response.json()
            self.assertEqual(source['chunks'], result['origins'])
            self.assertEqual([SourceUnits(source['chunks']).resolve(p) for p in source['chunks']], result['units'])
            self.assertEqual(source['projectionIdentity']['sourceRevisionSha256'],
                             result['sources'][0]['sourceRevisionSha256'])
            self.assertEqual('complete', result['coverage']['status'])
        finally:
            fixture.tearDown()

    def test_current_scope_policy_and_imported_software_drift_are_rejected(self):
        import oer_unified_server
        from unittest.mock import PropertyMock
        self.publish()
        with patch.dict(oer_unified_server._UNIFIED_HASHES, {'source_read_server.py': '0' * 64}):
            response = self.read()
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOFTWARE_CHANGED_SINCE_IMPORT', response.json()['detail']['kind'])
        changed = dict(self.policy.policyIdentity, manifestSha256='0' * 64)
        with patch.object(type(self.policy), 'policyIdentity', new_callable=PropertyMock, return_value=changed):
            response = self.read()
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('PROJECTION_SCOPE_POLICY_MISMATCH', response.json()['detail']['kind'])

    def test_invalid_ids_modules_views_cursor_and_duplicates_are_rejected(self):
        parents = self.publish(); address = self.sources[0]['projectionSha256']
        cases = [dict(operation='ids', originIds=['outside']),
            dict(operation='ids', originIds=[]),
            dict(operation='ids', originIds=[parents[0]['id']] * 2),
            dict(operation='ids', originIds=[parents[0]['id'], parents[1]['id']], maxItems=1),
            dict(operation='catalog', originIds=[parents[0]['id']]),
            dict(operation='catalog', cursor='bad'), dict(operation='scan', cursor='bad'),
            dict(operation='catalog', module='advisory'), dict(operation='catalog', module='drafting'),
            dict(operation='catalog', projectionSha256=[address, address]),
            dict(operation='catalog', projectionSha256=['0' * 64]),
            dict(operation='catalog', view='template_observations'),
            dict(operation='catalog', unknown='value'), dict(operation='catalog', maxItems=True),
            dict(operation='catalog', maxBytes=16777217)]
        for changes in cases:
            with self.subTest(changes=changes):
                response = self.read(**changes)
                self.assertEqual(422, response.status_code, response.text)
        first = self.read('scan', maxItems=1).json()['nextCursor']
        response = self.read('scan', cursor=first, sources=list(reversed(self.sources)))
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_CURSOR_SCOPE_MISMATCH', response.json()['detail']['kind'])
        response = self.read('scan', cursor=first[:-1] + ('0' if first[-1] != '0' else '1'))
        self.assertEqual(422, response.status_code, response.text)

    def test_json_duplicate_keys_and_trailing_data_use_production_strict_middleware(self):
        self.publish()
        body = json.dumps({'operation': 'catalog', 'projectionSha256': [self.sources[0]['projectionSha256']]})
        for raw in (body + '{}', body[:-1] + ',"operation":"catalog"}'):
            response = self.client.post('/sources/read', content=raw, headers={'content-type': 'application/json'})
            self.assertEqual(422, response.status_code, response.text)
            self.assertEqual('NATIVE_PDF_REQUEST_JSON_INVALID', response.json()['detail']['kind'])

    def test_actual_cas_change_before_read_or_after_resolve_fails_and_journals_failure(self):
        parents = self.publish()
        descriptor = self.single['nativeCaptureRefs']['sourcePackage']
        path = self.store.root / descriptor['relativePath']; original = path.read_bytes()
        try:
            path.write_bytes(original + b' changed')
            response = self.read()
            self.assertEqual(422, response.status_code, response.text)
            path.write_bytes(original)
            resolve = SourceUnits.resolve
            def changed(catalog, seed):
                value = resolve(catalog, seed)
                path.write_bytes(original + b' changed-after-resolve')
                return value
            with patch.object(SourceUnits, 'resolve', changed):
                response = self.read('ids', originIds=[parents[0]['id']])
            self.assertEqual(422, response.status_code, response.text)
            receipts = [json.loads(p.read_text(encoding='utf-8'))
                        for p in self.app.state.request_evidence.root.glob('*/receipt.json')]
            latest = sorted([r for r in receipts if r['endpoint'] == '/sources/read'], key=lambda r: r['createdAt'])[-1]
            self.assertEqual('failed', latest['terminalStatus'])
            self.assertIsNone(latest['response'])
        finally:
            path.write_bytes(original)
        self.assertEqual([], self.encodes); self.assertEqual([], self.predictions)


if __name__ == '__main__':
    unittest.main()
