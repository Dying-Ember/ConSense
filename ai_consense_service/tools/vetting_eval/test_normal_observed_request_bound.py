"""Authored request-bound publication transport; complete registry authority remains unchanged."""
import copy
import unittest
from unittest.mock import patch

from oer_source_registry import _serialize
from normal_observed_projection import OBSERVER, GRAPH_VERSION
import test_normal_observed as normal_tests


FORMAT = 'request-bound-normal-projection-v1'


class RequestBoundNormalHttpTests(unittest.TestCase):
    setUp = normal_tests.NormalObservedHttpTests.setUp
    tearDown = normal_tests.NormalObservedHttpTests.tearDown

    def test_negotiated_reply_omits_echoes_and_restores_the_exact_complete_v1_projection(self):
        body = copy.deepcopy(self.body)
        body['responseFormat'] = FORMAT
        original = _serialize(body)
        response = self.client.post('/normal-observed', json=body)
        self.assertEqual(200, response.status_code, response.text)
        receipt = response.json()
        self.assertEqual(FORMAT, receipt['responseFormat'])
        compact = receipt['projection']
        identity = compact['projectionIdentity']
        self.assertEqual({'$requestManifest': identity['observationManifestSha256']}, compact['manifest'])
        self.assertEqual({'$requestCanonical': compact['sourceHash']}, compact['canonical'])
        restored = self.restore(compact, body)
        canonical = self.client.get('/canonical/' + compact['projectionSha256'] + '?module=vetting')
        self.assertEqual(200, canonical.status_code, canonical.text)
        self.assertEqual(_serialize(restored), canonical.content)
        self.assertLess(len(response.content), len(canonical.content))
        self.assertEqual(original, _serialize(body))
        self.assertEqual(body['candidateGraph'], restored['manifest']['candidateGraph'])
        self.assertEqual(restored['manifest'], __import__('json').loads(self.store.read_asset(restored['observationManifestRef'])))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_capability_advertises_optional_format_and_unknown_format_is_rejected_before_publish(self):
        capability = self.client.get('/health').json()['oer']['normalObserved']
        self.assertIn(FORMAT, capability['publicationResponseFormats'])
        body = copy.deepcopy(self.body)
        body['responseFormat'] = 'unknown-wire-format'
        with patch.object(self.registry, 'publish', wraps=self.registry.publish) as published:
            rejected = self.client.post('/normal-observed', json=body)
        self.assertEqual(422, rejected.status_code, rejected.text)
        published.assert_not_called()

    def test_compact_publication_retains_native_inventory_and_expanded_selected_context(self):
        body = copy.deepcopy(self.body)
        graph = body['candidateGraph']
        graph['producerVersion'] = GRAPH_VERSION
        properties = {'sourcePosition': graph['units'][0]['parts'][0]['sourcePosition'],
                      'status': 'outline_not_declared', 'layoutEvidence': {'meaning': 'AUTHORED layout',
                      'raw': 'AUTHORED complete native observation ' * 2000}}
        graph['nativeDocxOutline'] = {'paragraphs': [properties], 'sourceClauseScopes': {
            'members': [{'id': 'AUTHORED source member', 'paragraphProperties': {'$nativeParagraph': 0}}]}}
        for unit in graph['units']:
            for part in unit['parts']:
                part['readingContext']['nativeDocxParagraph'] = {'$nativeParagraph': 0}
        body['responseFormat'] = FORMAT
        response = self.client.post('/normal-observed', json=body)
        self.assertEqual(200, response.status_code, response.text)
        restored = self.restore(response.json()['projection'], body)
        for chunk in restored['chunks']:
            for part in chunk['parts']:
                self.assertEqual(properties, part['readingContext']['nativeDocxParagraph'])
        complete = self.client.get('/canonical/' + restored['projectionSha256'] + '?module=vetting')
        self.assertEqual(200, complete.status_code, complete.text)
        self.assertEqual(_serialize(restored), complete.content)
        self.assertEqual(graph['nativeDocxOutline'], restored['manifest']['candidateGraph']['nativeDocxOutline'])
        for field in ('humanConfirmed', 'sourceQualityAccepted', 'semanticScopeVerified'):
            self.assertIs(False, restored[field])

    def test_compact_response_still_rejects_publication_changed_before_frame_exit(self):
        body = copy.deepcopy(self.body)
        body['responseFormat'] = FORMAT
        original_verify = self.registry.verify_normal_publication

        def tamper_after_readback(projection):
            original_verify(projection)
            self.registry._path(projection['projectionSha256']).write_bytes(b'changed before frame exit')

        with patch.object(self.registry, 'verify_normal_publication', side_effect=tamper_after_readback) as verified:
            rejected = self.client.post('/normal-observed', json=body)
        verified.assert_called_once()
        self.assertEqual(422, rejected.status_code, rejected.text)

    @staticmethod
    def restore(compact, body):
        restored = copy.deepcopy(compact)
        graph = body['candidateGraph']
        restored['manifest'] = {'candidateGraph': graph, 'sourceBinding': body['sourceBinding'], 'observer': OBSERVER}
        restored['canonical'] = {'units': graph['units'], 'relations': graph['relations'], 'assetRefs': []}
        return restored


if __name__ == '__main__':
    unittest.main()
