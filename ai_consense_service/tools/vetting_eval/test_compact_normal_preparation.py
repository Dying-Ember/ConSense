"""Optional compact preparation proof over authored CAS only; no live services/models."""
import hashlib
import json
import unittest
from unittest.mock import patch

from normal_observed_projection import produce_normal_observed
from canonical_projection import project_canonical
from fastapi.testclient import TestClient
from oer_source_registry import _serialize
from oer_unified_server import create_unified_app
from retrieval_source_units import SourceUnits
from test_normal_observed import fixture
from test_oer_source_registry import asset_fixture
from test_token_windows import CharacterTokenizer
import test_oer_server as fake_server
import test_source_read_semantic as semantic


VERSION = 'compact-normal-source-proof-v1'
PROOF_FIELDS = {'projectionSha256', 'producerKind', 'projectionIdentity',
                'projectionRevisionSha256', 'sourceHash', 'source', 'sourceBinding',
                'snapshotSha256', 'parentSha256'}


class CompactNormalPreparationTests(unittest.TestCase):
    tearDown = fake_server.OerHttpContractTest.tearDown
    read = semantic.SemanticSourceReadTests.read

    def setUp(self):
        fake_server.OerHttpContractTest.setUp(self)
        self.client.close()
        self.app = create_unified_app(self.policy, backend=self.backend)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        self.client = TestClient(self.app)
        self.store, self.registry = self.app.state.artifact_store, self.app.state.source_registry
        bundle, adoption, _ = asset_fixture(self.store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        self.scan = project_canonical(bundle, adoption, artifact_store=self.store)

    def normal_sources(self, count=1):
        sources = []
        for index in range(count):
            body = fixture(self.policy)
            graph = body['candidateGraph']
            original = hashlib.sha256(('AUTHORED original ' + str(index)).encode()).hexdigest()
            graph['source'].update(documentId=str(900 + index), originalSourceSha256=original,
                                   filename='AUTHORED source ' + str(index) + '.docx')
            for unit in graph['units']:
                for part in unit['parts']:
                    part['sourceSha256'] = original
                for span in unit['sourceSpans']:
                    span['sourceSha256'] = original
            source = produce_normal_observed(graph, body['sourceBinding'], artifact_store=self.store)
            self.registry.publish(source)
            sources.append(source)
        self.sources = sources
        return sources

    def prepare(self, **changes):
        return self.read('prepare', view='semantic_units',
                         semanticReadVersion='source-semantic-dictionary-v2',
                         semanticReadProfile='text-first-scan-v1',
                         normalProofVersion=VERSION, **changes)

    def assert_proof(self, proof, source):
        self.assertEqual(PROOF_FIELDS, set(proof))
        for name in ('projectionSha256', 'producerKind', 'projectionIdentity',
                     'projectionRevisionSha256', 'sourceHash', 'source'):
            self.assertEqual(source[name], proof[name])
        self.assertEqual(source['manifest']['sourceBinding'], proof['sourceBinding'])
        self.assertEqual(hashlib.sha256(_serialize(source)).hexdigest(), proof['snapshotSha256'])
        self.assertEqual(hashlib.sha256(_serialize(source['chunks'])).hexdigest(), proof['parentSha256'])
        self.assertEqual(hashlib.sha256(_serialize(source['manifest']['candidateGraph'])).hexdigest(),
                         proof['projectionIdentity']['candidateGraphSha256'])
        raw = _serialize(proof).decode()
        self.assertNotIn('candidateGraph"', raw)
        self.assertNotIn('"chunks"', raw)
        self.assertNotIn('"parts"', raw)
        self.assertNotIn('"manifest"', raw)
        for chunk in source['chunks']:
            self.assertNotIn(chunk['content'], raw)

    def test_complete_33_source_prepare_returns_compact_proof_with_one_load_per_source(self):
        sources = self.normal_sources(33)
        load = self.registry.load
        loaded = []

        def once(address):
            loaded.append(address)
            return load(address)

        with patch.object(self.registry, 'load', once), \
                patch.object(self.rt, 'tokenizer', side_effect=AssertionError('model forbidden')):
            health = self.client.get('/health')
            response = self.prepare(maxBytes=16 * 1024 * 1024)
        self.assertEqual(200, health.status_code, health.text)
        self.assertEqual(VERSION, health.json()['oer']['sourceRead']['semanticPreparation']['normalProofVersion'])
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual(VERSION, result['normalProofVersion'])
        self.assertEqual([source['projectionSha256'] for source in sources], loaded)
        self.assertEqual(33, len(result['normalProofs']))
        self.assertEqual(sum(len(source['chunks']) for source in sources), result['coverage']['totalOrigins'])
        for source, proof in zip(sources, result['normalProofs']):
            self.assert_proof(proof, source)
        self.assertEqual([], result['origins'])
        self.assertEqual([], result['units'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_legacy_request_and_mixed_sources_keep_the_existing_prepare_path(self):
        source = self.normal_sources()[0]
        legacy = self.read('prepare', view='semantic_units')
        self.assertEqual(200, legacy.status_code, legacy.text)
        self.assertNotIn('normalProofVersion', legacy.json())
        self.assertNotIn('normalProofs', legacy.json())
        self.registry.publish(self.scan)
        self.sources = [source, self.scan]
        response = self.prepare()
        self.assertEqual(200, response.status_code, response.text)
        self.assert_proof(response.json()['normalProofs'][0], source)
        self.assertIsNone(response.json()['normalProofs'][1])
        self.assertEqual(len(source['chunks']) + len(self.scan['chunks']),
                         response.json()['coverage']['totalOrigins'])

    def test_warm_proof_prepare_and_scan_never_rebuild_the_source_graph_or_repeat_proof(self):
        source = self.normal_sources()[0]
        first = self.prepare()
        self.assertEqual(200, first.status_code, first.text)
        prepared = {name: first.json()[name] for name in
                    ('readManifestSha256', 'readManifestToken', 'semanticReadVersion', 'semanticReadProfile')}
        with patch.object(self.registry, 'load', side_effect=AssertionError('repeat full graph load')), \
                patch.object(SourceUnits, '__init__', side_effect=AssertionError('repeat source resolver')):
            second = self.prepare()
            scanned = self.read('scan', view='semantic_units', **prepared)
        self.assertEqual(200, second.status_code, second.text)
        self.assertEqual(first.json()['normalProofs'], second.json()['normalProofs'])
        self.assertFalse(second.json()['coldPreparation']['performed'])
        self.assertEqual(first.json()['readManifestSha256'], second.json()['readManifestSha256'])
        self.assertEqual(200, scanned.status_code, scanned.text)
        self.assertNotIn('normalProofVersion', scanned.json())
        self.assertNotIn('normalProofs', scanned.json())
        self.assertEqual([chunk['id'] for chunk in source['chunks']],
                         [origin['id'] for origin in scanned.json()['origins']])

    def test_request_proof_is_strictly_optional_and_only_valid_for_semantic_prepare(self):
        self.normal_sources()
        for changes in ({'operation': 'catalog'}, {'view': 'canonical_units'},
                        {'normalProofVersion': True}, {'normalProofVersion': 'unrecognized-proof'},
                        {'module': 'advisory'}):
            body = {'operation': 'prepare', 'view': 'semantic_units', 'module': 'vetting',
                    'projectionSha256': [self.sources[0]['projectionSha256']],
                    'normalProofVersion': VERSION}
            body.update(changes)
            response = self.client.post('/sources/read', json=body)
            self.assertEqual(422, response.status_code, response.text)
        bounded = self.prepare(maxBytes=1024)
        self.assertEqual(422, bounded.status_code, bounded.text)
        self.assertEqual('SOURCE_READ_BUDGET_EXCEEDED', bounded.json()['detail']['kind'])

    def test_warm_proof_prepare_rejects_changed_root_alias_and_manifest_cas(self):
        source = self.normal_sources()[0]
        prepared = self.prepare()
        self.assertEqual(200, prepared.status_code, prepared.text)
        root = self.registry._path(source['projectionSha256'])
        alias = self.registry._normal_revision_path(source['projectionRevisionSha256'], pin=False)
        cas = self.store.root / source['observationManifestRef']['relativePath']
        for path in (root, alias, cas):
            original = path.read_bytes()
            try:
                path.write_bytes(original + b' AUTHORED corruption')
                response = self.prepare()
                self.assertEqual(422, response.status_code, response.text)
            finally:
                path.write_bytes(original)

    def test_cold_proof_cannot_escape_a_failed_actual_source_exit_check(self):
        source = self.normal_sources()[0]
        root = self.registry._path(source['projectionSha256'])
        original = root.read_bytes()
        metadata = self.registry.source_metadata

        def changed(address, *, module):
            value = metadata(address, module=module)
            root.write_bytes(original + b' AUTHORED changed after compact proof')
            return value

        try:
            with patch.object(self.registry, 'source_metadata', changed):
                response = self.prepare()
            self.assertEqual(422, response.status_code, response.text)
            receipts = [json.loads(path.read_text()) for path in
                        self.app.state.request_evidence.root.glob('*/receipt.json')]
            latest = sorted((r for r in receipts if r['endpoint'] == '/sources/read'),
                            key=lambda r: r['createdAt'])[-1]
            self.assertEqual('failed', latest['terminalStatus'])
            self.assertIsNone(latest['response'])
        finally:
            root.write_bytes(original)

    def test_proof_request_rechecks_policy_and_imported_software(self):
        import oer_unified_server
        from unittest.mock import PropertyMock
        self.normal_sources()
        with patch.dict(oer_unified_server._UNIFIED_HASHES, {'source_read_server.py': '0' * 64}):
            response = self.prepare()
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOFTWARE_CHANGED_SINCE_IMPORT', response.json()['detail']['kind'])
        with patch.object(type(self.policy), 'policyIdentity', new_callable=PropertyMock,
                          return_value={'manifestSha256': 'f' * 64}):
            response = self.prepare()
        self.assertEqual(422, response.status_code, response.text)

    def test_proof_order_and_existing_read_token_remain_bound_to_the_exact_source_order(self):
        sources = self.normal_sources(2)
        response = self.prepare()
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual([source['projectionSha256'] for source in sources],
                         [proof['projectionSha256'] for proof in response.json()['normalProofs']])
        prepared = {name: response.json()[name] for name in
                    ('readManifestSha256', 'readManifestToken', 'semanticReadVersion', 'semanticReadProfile')}
        wrong = self.read('scan', sources=list(reversed(sources)), view='semantic_units', **prepared)
        self.assertEqual(422, wrong.status_code, wrong.text)


if __name__ == '__main__':
    unittest.main()
