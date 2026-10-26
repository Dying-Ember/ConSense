"""Authored normal-observation CAS/HTTP contracts; fake vectors, no source files."""
import copy
import asyncio
import hashlib
import json
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from oer_source_registry import _serialize
from oer_unified_server import create_unified_app
from test_oer_source_registry import asset_fixture
from canonical_projection import project_canonical
from test_token_windows import CharacterTokenizer
import test_oer_server as base_tests


def fixture(policy):
    sha = lambda value: hashlib.sha256(value.encode()).hexdigest()
    original = sha('authored original identity only; never a competition source')
    parts = []
    for index, text in enumerate(('Visible row A', 'Visible row B', 'Visible paragraph \U0001f680')):
        size = len(text.encode('utf-16-le')) // 2
        parts.append({'blockId': 'authored-block-' + str(index), 'anchor': 'body/' + str(index),
            'text': text, 'startOffset': 0, 'endOffset': size, 'offsetUnit': 'utf16',
            'sourceSha256': original, 'physicalPageNo': None, 'extractionSource': 'native',
            'sourcePosition': {'kind': 'native_location', 'physicalPageNo': None,
                'nodePath': 'body/' + str(index), 'story': 'body'}, 'bbox': None,
            'readingContext': {'authoredObservationScores': [1e-6, 1e-7, 1.0, 0.0001]},
            'wordCells': [], 'wordNativeObjects': [], 'cells': []})
    units = []
    for index, members in enumerate((parts[:2], parts[2:])):
        units.append({'unitId': 'candidate-unit:' + sha('authored-unit-' + str(index)),
            'kind': 'native_table' if index == 0 else 'source_block',
            'status': 'observed_structure_candidate', 'adoptionStatus': 'candidate_unverified',
            'semanticScopeVerified': False, 'qualifiersComplete': 'unknown',
            'requiredMemberBlockIds': [part['blockId'] for part in members],
            'parts': members, 'sourceSpans': [{key: part[key] for key in (
                'blockId', 'startOffset', 'endOffset', 'offsetUnit', 'sourceSha256', 'sourcePosition')}
                for part in members], 'physicalPages': [],
            'unknowns': [{'path': 'meaning', 'status': 'unknown', 'reason': 'authored_unverified'}],
            'text': '\n'.join(part['text'] for part in members), 'sourceOrder': 0 if index == 0 else 2,
            'tableGroup': {'tableLocation': 'body/table/0', 'headerMeaning': 'unknown',
                'semanticAdoption': 'unknown', 'rows': [{'blockId': part['blockId'],
                    'location': part['anchor'], 'cells': [], 'wordCells': []} for part in members]}
                if index == 0 else None})
    graph = {'schema': 'consense.canonical-candidate.v1',
        'producerVersion': 'actual-normal-source-unit-whole-page-observations-v2',
        'status': 'actual_source_candidate_generation', 'adoptionStatus': 'candidate_unverified',
        'humanConfirmed': False, 'sourceQualityAccepted': False, 'semanticScopeVerified': False,
        'legalClosure': 'unknown', 'source': {'projectId': 'authored-normal', 'documentId': '901',
            'originalSourceSha256': original, 'parsedSourceHash': sha('authored parsed source'),
            'filename': 'authored-source.docx', 'role': 'tender', 'scope': None,
            'identitySha256': sha('authored routed identity')}, 'coverageSha256': sha('authored coverage'),
        'unknowns': [{'path': 'quality', 'status': 'unknown', 'reason': 'not_source_reviewed'}],
        'units': units, 'relations': [{'type': 'observed_candidate', 'fromUnitId': units[0]['unitId'],
            'toUnitId': units[1]['unitId'], 'status': 'unknown_not_source_assigned', 'humanConfirmed': False}]}
    graph['generationSha256'] = sha('authored Java generation pin, not a review')
    return {'candidateGraph': graph, 'sourceBinding': {'module': 'vetting',
        'eligibleModules': ['vetting', 'advisory'], 'sourcePurpose': 'project_document',
        'sourceClass': 'special_conditions_of_contract', 'routingSha256': sha('authored current routing'),
        'scopePolicySha256': policy.policyIdentity['manifestSha256']}}


def with_empty_unit(body):
    body = copy.deepcopy(body)
    empty = copy.deepcopy(body['candidateGraph']['units'][-1])
    empty.update(unitId='candidate-unit:' + hashlib.sha256(b'authored-empty-unit').hexdigest(),
                 text='', sourceOrder=3, requiredMemberBlockIds=['authored-empty'])
    empty['parts'][0].update(blockId='authored-empty', text='', endOffset=0)
    empty['sourceSpans'][0].update(blockId='authored-empty', endOffset=0)
    body['candidateGraph']['units'].append(empty)
    return body


def many_unit_fixture(policy, count=8):
    body = fixture(policy)
    graph, template = body['candidateGraph'], body['candidateGraph']['units'][-1]
    graph['units'] = []
    for index in range(count):
        unit = copy.deepcopy(template)
        block = 'authored-many-block-' + str(index)
        unit.update(unitId='candidate-unit:' + hashlib.sha256(block.encode()).hexdigest(),
                    sourceOrder=index, requiredMemberBlockIds=[block])
        unit['parts'][0].update(blockId=block, anchor='body/' + str(index))
        unit['parts'][0]['sourcePosition']['nodePath'] = 'body/' + str(index)
        unit['sourceSpans'][0].update(blockId=block,
            sourcePosition=copy.deepcopy(unit['parts'][0]['sourcePosition']))
        graph['units'].append(unit)
    graph['relations'] = [{'type': 'observed_candidate', 'humanConfirmed': False,
        'fromUnitId': unit['unitId'], 'toUnitId': graph['units'][(index + 1) % count]['unitId']}
        for index, unit in enumerate(graph['units'])]
    return body


class NormalObservedStreamingTests(unittest.TestCase):
    def manifest(self, count=8):
        from normal_observed_projection import OBSERVER
        body = many_unit_fixture(type('Policy', (), {'policyIdentity': {'manifestSha256': 'a' * 64}})(), count)
        return {**body, 'observer': copy.deepcopy(OBSERVER)}

    def test_assemble_relation_endpoints_read_once_per_relation(self):
        from normal_observed_projection import _assemble
        class CountedRelation(dict):
            reads = 0
            def __getitem__(self, key):
                if key == 'fromUnitId':
                    type(self).reads += 1
                return super().__getitem__(key)
        manifest = self.manifest()
        graph = manifest['candidateGraph']
        graph['relations'] = [CountedRelation(row) for row in graph['relations']]
        _assemble(manifest, {'authoredDescriptor': True}, 'b' * 64)
        self.assertEqual(len(graph['relations']), CountedRelation.reads)

    def test_assemble_quality_hash_computed_once(self):
        import normal_observed_projection as normal
        quality = {'status': 'PARSED', 'semanticQuality': 'unknown', **normal.DECLARATIONS}
        with patch.object(normal, '_hash', wraps=normal._hash) as hashed:
            normal._assemble(self.manifest(), {'authoredDescriptor': True}, 'b' * 64)
        self.assertEqual(1, sum(call.args == (quality,) for call in hashed.call_args_list))

    def test_relation_index_keeps_order_duplicates_self_and_unknown_targets(self):
        from normal_observed_projection import _assemble
        manifest = self.manifest(3)
        graph = manifest['candidateGraph']
        graph['relations'].extend([dict(graph['relations'][0], toUnitId=graph['units'][0]['unitId']),
            dict(graph['relations'][1], toUnitId=None), copy.deepcopy(graph['relations'][0])])
        missing_target = dict(graph['relations'][2])
        missing_target.pop('toUnitId')
        graph['relations'].append(missing_target)
        result = _assemble(manifest, {'authoredDescriptor': True}, 'b' * 64)
        for chunk in result['chunks']:
            self.assertEqual([relation for relation in graph['relations']
                if chunk['unitId'] in (relation['fromUnitId'], relation.get('toUnitId'))], chunk['relations'])
        result['chunks'][0]['relations'][0]['humanConfirmed'] = True
        self.assertTrue(all(relation['humanConfirmed'] is False for relation in graph['relations']))

    def test_relation_index_rejects_unknown_endpoint_before_assembly(self):
        from normal_observed_projection import _assemble
        from oer_source_registry import SourceRegistryError
        for endpoint in ('fromUnitId', 'toUnitId'):
            manifest = self.manifest()
            manifest['candidateGraph']['relations'][-1][endpoint] = 'candidate-unit:' + '0' * 64
            with self.subTest(endpoint=endpoint), self.assertRaises(SourceRegistryError) as error:
                _assemble(manifest, {'authoredDescriptor': True}, 'b' * 64)
            self.assertEqual('NORMAL_OBSERVED_RELATION_INVALID', error.exception.code)

    def test_streaming_hash_and_comparison_preserve_exact_canonical_bytes(self):
        from normal_observed_projection import _hash, _serialized_equal, _matches_serialized_bytes, _canonical_chunks
        values = [None, {}, [], {'z': '中文 🚀\n\x00', 'a': [1, 1.0, -0.0, 1e-7]},
                  {'z': ['large literal ' * 20000], 'a': {'nested': True}}]
        for value in values:
            raw = _serialize(value)
            with self.subTest(value_type=type(value).__name__):
                with patch('normal_observed_projection._serialize', side_effect=AssertionError('No whole encoded copy')):
                    self.assertEqual(hashlib.sha256(raw).hexdigest(), _hash(value))
                    self.assertEqual(len(raw), sum(len(part) for part in _canonical_chunks(value)))
                    self.assertTrue(_matches_serialized_bytes(value, raw))
                    self.assertTrue(_serialized_equal(value, copy.deepcopy(value)))
                    self.assertFalse(_matches_serialized_bytes(value, raw + b' '))
        for first, second in ((1, 1.0), (False, 0), ([1, 2], [2, 1]), ({'a': 1}, {'a': '1'})):
            self.assertFalse(_serialized_equal(first, second))

    def test_nested_scope_paragraph_references_validate_without_rejecting_shared_aliases(self):
        from normal_observed_projection import GRAPH_VERSION, _paragraph, _validate_paragraph_references
        from oer_source_registry import SourceRegistryError
        shared = {'literal': ['AUTHORED layout declaration']}
        properties = {'sourcePosition': {'nodePath': 'AUTHORED paragraph'},
                      'first': shared, 'second': shared}
        reference = {'$nativeParagraph': 0}
        graph = {'producerVersion': GRAPH_VERSION, 'nativeDocxOutline': {
            'paragraphs': [properties], 'sourceClauseScopes': {'members': [
                {'paragraphProperties': {'nested': [reference, {'alias': reference}]}}]}}}
        _validate_paragraph_references(graph)
        self.assertIs(properties, _paragraph(graph, reference))
        for ordinal in (-1, True, 0.0, '0', 1):
            with self.subTest(ordinal=ordinal):
                reference['$nativeParagraph'] = ordinal
                with self.assertRaises(SourceRegistryError) as rejected:
                    _validate_paragraph_references(graph)
                self.assertEqual('NORMAL_OBSERVED_PARAGRAPH_REF_INVALID', rejected.exception.code)

    def test_direct_python_cyclic_paragraph_input_reaches_canonical_encoder_rejection(self):
        import tempfile
        from artifact_store import ArtifactStore
        from normal_observed_projection import GRAPH_VERSION, _paragraph, produce_normal_observed
        body = fixture(type('Policy', (), {'policyIdentity': {'manifestSha256': 'a' * 64}})())
        graph = body['candidateGraph']
        graph['producerVersion'] = GRAPH_VERSION
        properties = {'sourcePosition': {'nodePath': 'AUTHORED paragraph'}}
        cycle = [properties]
        properties['cycle'] = cycle
        graph['nativeDocxOutline'] = {'paragraphs': [properties]}
        graph['units'][0]['parts'][0]['readingContext']['nativeDocxParagraph'] = {'$nativeParagraph': 0}
        self.assertIs(properties, _paragraph(graph, {'$nativeParagraph': 0}))
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            with patch.object(store, 'put_bytes', side_effect=AssertionError('Cyclic input must never enter CAS')):
                with self.assertRaisesRegex(ValueError, 'Circular reference'):
                    produce_normal_observed(graph, body['sourceBinding'], artifact_store=store)


class NormalObservedHttpTests(unittest.TestCase):
    tearDown = base_tests.OerHttpContractTest.tearDown

    def setUp(self):
        base_tests.OerHttpContractTest.setUp(self)
        self.client.close()
        self.app = create_unified_app(self.policy, backend=self.backend)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        self.client = TestClient(self.app)
        self.store, self.registry = self.app.state.artifact_store, self.app.state.source_registry
        self.body = fixture(self.policy)

    def publish(self, body=None):
        response = self.client.post('/normal-observed', json=self.body if body is None else body)
        self.assertEqual(200, response.status_code, response.text)
        return response.json()['projection']

    def test_producer_validates_once_before_retaining_manifest(self):
        from normal_observed_projection import produce_normal_observed, _validate_manifest
        from oer_source_registry import SourceRegistryError
        with patch('normal_observed_projection._validate_manifest', wraps=_validate_manifest) as validate:
            produce_normal_observed(self.body['candidateGraph'], self.body['sourceBinding'],
                artifact_store=self.store)
        self.assertEqual(1, validate.call_count)
        invalid = copy.deepcopy(self.body)
        invalid['candidateGraph']['relations'][0]['toUnitId'] = 'candidate-unit:' + '0' * 64
        with patch.object(self.store, 'put_bytes', wraps=self.store.put_bytes) as retained:
            with self.assertRaises(SourceRegistryError):
                produce_normal_observed(invalid['candidateGraph'], invalid['sourceBinding'],
                    artifact_store=self.store)
        retained.assert_not_called()

    def test_post_rechecks_published_bytes_without_rebuilding_then_get_rechecks_cas(self):
        from normal_observed_projection import _assemble
        with patch('normal_observed_projection._assemble', wraps=_assemble) as assembled:
            projection = self.publish()
        self.assertEqual(2, assembled.call_count)
        address = projection['projectionSha256']
        response = self.client.get('/canonical/' + address + '?module=vetting')
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(_serialize(projection), response.content)
        descriptor = projection['observationManifestRef']
        path = self.store.root / descriptor['relativePath']
        original = path.read_bytes()
        try:
            path.write_bytes(original.replace(b'Visible row A', b'changed row A'))
            self.assertNotEqual(200, self.client.get('/canonical/' + address + '?module=vetting').status_code)
        finally:
            path.write_bytes(original)
        self.assertEqual(200, self.client.get('/canonical/' + address + '?module=vetting').status_code)

    def test_post_rejects_root_alias_or_manifest_changed_after_publish(self):
        original_publish = self.registry.publish
        for changed in ('root', 'alias', 'manifest'):
            with self.subTest(changed=changed):
                body = copy.deepcopy(self.body)
                body['candidateGraph']['generationSha256'] = hashlib.sha256(changed.encode()).hexdigest()

                def tamper(projection):
                    receipt = original_publish(projection)
                    if changed == 'root':
                        path = self.registry._path(projection['projectionSha256'])
                    elif changed == 'alias':
                        path = self.registry._normal_revision_path(projection['projectionRevisionSha256'])
                        path.unlink()
                    else:
                        path = self.store.root / projection['observationManifestRef']['relativePath']
                    path.write_bytes(b'changed after publication')
                    return receipt

                with patch.object(self.registry, 'publish', side_effect=tamper):
                    response = self.client.post('/normal-observed', json=body)
                self.assertEqual(422, response.status_code, response.text)

    def test_post_rejects_public_root_changed_after_readback_before_frame_exit(self):
        original_verify = self.registry.verify_normal_publication

        def tamper_after_readback(projection):
            original_verify(projection)
            self.registry._path(projection['projectionSha256']).write_bytes(b'changed before frame exit')

        with patch.object(self.registry, 'verify_normal_publication', side_effect=tamper_after_readback):
            response = self.client.post('/normal-observed', json=self.body)
        self.assertEqual(422, response.status_code, response.text)

    def test_post_rejects_revision_alias_resolution_loop(self):
        from pathlib import Path
        original_publish, original_resolve = self.registry.publish, Path.resolve
        alias_path = None

        def record_alias(projection):
            nonlocal alias_path
            receipt = original_publish(projection)
            alias_path = self.registry._normal_revision_path(projection['projectionRevisionSha256'], pin=False)
            return receipt

        def resolve(path, *args, **kwargs):
            if alias_path is not None and path == alias_path:
                raise RuntimeError('Symlink loop')
            return original_resolve(path, *args, **kwargs)

        with patch.object(self.registry, 'publish', side_effect=record_alias), patch.object(Path, 'resolve', resolve):
            response = self.client.post('/normal-observed', json=self.body)
        self.assertEqual(422, response.status_code, response.text)
        self.assertIn('CANONICAL_FRAME_SOURCE_CHANGED', response.text)

    def test_post_rejects_revision_alias_resolution_loop_at_frame_exit(self):
        from pathlib import Path
        original_verify, original_resolve = self.registry.verify_normal_publication, Path.resolve
        alias_path = None

        def record_after_readback(projection):
            nonlocal alias_path
            original_verify(projection)
            alias_path = self.registry._normal_revision_path(projection['projectionRevisionSha256'], pin=False)

        def resolve(path, *args, **kwargs):
            if alias_path is not None and path == alias_path:
                raise RuntimeError('Symlink loop at frame exit')
            return original_resolve(path, *args, **kwargs)

        with patch.object(self.registry, 'verify_normal_publication', side_effect=record_after_readback), \
                patch.object(Path, 'resolve', resolve):
            response = self.client.post('/normal-observed', json=self.body)
        self.assertEqual(422, response.status_code, response.text)
        self.assertIn('CANONICAL_FRAME_SOURCE_CHANGED', response.text)

    def test_nonobject_cold_public_roots_report_schema_error(self):
        from oer_source_registry import SourceRegistryError
        from unified_source_registry import UnifiedCanonicalSourceRegistry
        for raw in (b'[]', b'[{}]', b'1', b'null', b'true', b'"wrong"'):
            with self.subTest(raw=raw):
                address = hashlib.sha256(b'authored-nonobject-root:' + raw).hexdigest()
                path = self.registry._path(address)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
                cold = UnifiedCanonicalSourceRegistry(self.registry.root, self.store, family_policy=self.policy)
                with patch('unified_source_registry.normal_observed_candidate') as dispatch:
                    with self.assertRaises(SourceRegistryError) as rejected:
                        cold.load(address)
                    dispatch.assert_not_called()
                self.assertEqual('SNAPSHOT_UNAVAILABLE', rejected.exception.code)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_nonobject_public_root_get_is_controlled_without_inference(self):
        with TestClient(self.app, raise_server_exceptions=False) as client:
            for raw in (b'[]', b'[{}]', b'1', b'null', b'true', b'"wrong"'):
                with self.subTest(raw=raw):
                    address = hashlib.sha256(b'authored-nonobject-get:' + raw).hexdigest()
                    path = self.registry._path(address)
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(raw)
                    response = client.get('/canonical/' + address)
                    self.assertEqual(404, response.status_code, response.text)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_http_capability_and_complete_unreviewed_cas_registration(self):
        capability = self.client.get('/health').json()['oer'].get('normalObserved')
        self.assertIsInstance(capability, dict)
        self.assertEqual('consense.normal-observed.v1', capability['schema'])
        self.assertEqual('/normal-observed', capability['projectionEndpoint'])
        self.assertEqual('actual-normal-source-unit-whole-page-observations-v3', capability['candidateGraphVersion'])
        from normal_observed_projection import SUPPORTED_GRAPH_VERSIONS
        self.assertEqual(list(SUPPORTED_GRAPH_VERSIONS), capability['supportedCandidateGraphVersions'])
        projection = self.publish()
        self.assertEqual('consense.normal-observed.v1', projection['schema'])
        self.assertEqual(self.body['candidateGraph'], projection['manifest']['candidateGraph'])
        self.assertEqual(self.body['sourceBinding'], projection['manifest']['sourceBinding'])
        self.assertEqual('application', projection['manifest']['observer']['actor'])
        self.assertEqual('java_current_source_reader', projection['manifest']['observer']['originalByteVerifier'])
        self.assertNotIn('reference', projection['manifest'])
        self.assertNotIn('adoption', projection['manifest'])
        self.assertEqual(projection['manifest'], json.loads(self.store.read_asset(projection['observationManifestRef'])))
        for field in ('humanConfirmed', 'sourceQualityAccepted', 'semanticScopeVerified'):
            self.assertIs(False, projection[field])
        self.assertEqual('candidate_unverified', projection['adoptionStatus'])
        self.assertEqual(2, len(projection['chunks']))
        for unit, chunk in zip(self.body['candidateGraph']['units'], projection['chunks']):
            for field in ('parts', 'sourceSpans', 'physicalPages', 'tableGroup', 'unknowns', 'sourceOrder'):
                self.assertEqual(unit[field], chunk.get(field))
            self.assertEqual('candidate_unverified', chunk['adoptionStatus'])
            self.assertIs(False, chunk['humanConfirmed'])
        response = self.client.get('/canonical/' + projection['projectionSha256'] + '?module=advisory')
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(_serialize(projection), response.content)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_compact_pdf_graph_roundtrips_without_source_quality_adoption(self):
        from normal_observed_projection import COMPACT_PDF_GRAPH_VERSION
        body = copy.deepcopy(self.body)
        graph = body['candidateGraph']
        graph['producerVersion'] = COMPACT_PDF_GRAPH_VERSION
        graph['source']['filename'] = 'authored-reading.pdf'
        projection = self.publish(body)
        self.assertEqual(graph, projection['manifest']['candidateGraph'])
        self.assertEqual(graph['units'][0]['tableGroup'], projection['chunks'][0]['tableGroup'])
        for field in ('humanConfirmed', 'sourceQualityAccepted', 'semanticScopeVerified'):
            self.assertIs(False, projection[field])
        response = self.client.get('/canonical/' + projection['projectionSha256'] + '?module=vetting')
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(_serialize(projection), response.content)
        graph['units'][0]['parts'][0]['readingContext']['nativeDocxParagraph'] = {'$nativeParagraph': 0}
        graph['nativeDocxOutline'] = {'paragraphs': [{'sourcePosition': {'story': 'body'}}]}
        rejected = self.client.post('/normal-observed', json=body)
        self.assertEqual(422, rejected.status_code, rejected.text)
        self.assertIn('NORMAL_OBSERVED_PARAGRAPH_REF_INVALID', rejected.text)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_compact_paragraph_references_keep_complete_chunk_context_and_original_manifest(self):
        from normal_observed_projection import GRAPH_VERSION
        body = copy.deepcopy(self.body)
        graph = body['candidateGraph']
        graph['producerVersion'] = GRAPH_VERSION
        properties = {'sourcePosition': graph['units'][0]['parts'][0]['sourcePosition'],
                      'status': 'outline_not_declared', 'layoutEvidence': {'meaning': 'AUTHORED layout',
                      'raw': 'AUTHORED shared declaration ' * 2000}}
        graph['nativeDocxOutline'] = {'paragraphs': [properties], 'sourceClauseScopes': {
            'members': [{'id': 'AUTHORED scope member', 'paragraphProperties': {'$nativeParagraph': 0}}]}}
        for unit in graph['units']:
            for part in unit['parts']:
                part['readingContext']['nativeDocxParagraph'] = {'$nativeParagraph': 0}
                part['readingContext']['nativeDocxParagraphs'] = [{'$nativeParagraph': 0}]
        before = _serialize(body)
        projection = self.publish(body)
        self.assertEqual(before, _serialize(body))
        self.assertEqual(graph, projection['manifest']['candidateGraph'])
        self.assertEqual(graph['units'], projection['canonical']['units'])
        for chunk in projection['chunks']:
            for part in chunk['parts']:
                self.assertEqual(properties, part['readingContext']['nativeDocxParagraph'])
                self.assertEqual([properties], part['readingContext']['nativeDocxParagraphs'])
        self.assertLess(len(_serialize(graph)), len(_serialize(projection['chunks'])))
        loaded = self.client.get('/canonical/' + projection['projectionSha256'] + '?module=vetting')
        self.assertEqual(200, loaded.status_code, loaded.text)
        self.assertEqual(_serialize(projection), loaded.content)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_compact_paragraph_invalid_references_never_publish(self):
        from normal_observed_projection import GRAPH_VERSION
        for reference in ({'$nativeParagraph': -1}, {'$nativeParagraph': True}, {'$nativeParagraph': 0.0},
                          {'$nativeParagraph': '0'}, {'$nativeParagraph': 1},
                          {'$nativeParagraph': 0, 'extra': 'untrusted'}):
            with self.subTest(reference=reference):
                body = copy.deepcopy(self.body)
                graph = body['candidateGraph']
                graph['producerVersion'] = GRAPH_VERSION
                graph['nativeDocxOutline'] = {'paragraphs': [{'sourcePosition': {'nodePath': 'AUTHORED'}}]}
                graph['units'][0]['parts'][0]['readingContext']['nativeDocxParagraph'] = reference
                rejected = self.client.post('/normal-observed', json=body)
                self.assertNotEqual(200, rejected.status_code)
                self.assertIn('NORMAL_OBSERVED_PARAGRAPH_REF_INVALID', rejected.text)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_compact_scope_nested_invalid_reference_is_rejected(self):
        from normal_observed_projection import GRAPH_VERSION
        body = copy.deepcopy(self.body)
        graph = body['candidateGraph']
        graph['producerVersion'] = GRAPH_VERSION
        graph['nativeDocxOutline'] = {'paragraphs': [{'sourcePosition': {'nodePath': 'AUTHORED'}}],
                                    'sourceClauseScopes': {'members': [{'paragraphProperties': {
                                        'nested': {'$nativeParagraph': -1}}}]}}
        rejected = self.client.post('/normal-observed', json=body)
        self.assertNotEqual(200, rejected.status_code)
        self.assertIn('NORMAL_OBSERVED_PARAGRAPH_REF_INVALID', rejected.text)

    def test_empty_units_validate_dict_shaped_paragraph_contexts_before_publication(self):
        from normal_observed_projection import GRAPH_VERSION
        for context in ({'$nativeParagraph': -1}, {'nested': [{'$nativeParagraph': -1}]}):
            with self.subTest(context=context):
                body = with_empty_unit(self.body)
                graph = body['candidateGraph']
                graph['producerVersion'] = GRAPH_VERSION
                graph['nativeDocxOutline'] = {'paragraphs': [{'sourcePosition': {'nodePath': 'AUTHORED'}}]}
                graph['units'][-1]['parts'][0]['readingContext']['nativeDocxParagraphs'] = context
                rejected = self.client.post('/normal-observed', json=body)
                self.assertEqual(422, rejected.status_code, rejected.text[:1000])
                self.assertIn('NORMAL_OBSERVED_PARAGRAPH_REF_INVALID', rejected.text)
        self.assertEqual([], list(self.registry.root.glob('*.json')))
        for context in ({'layoutHint': 'AUTHORED literal dict'}, {'$nativeParagraph': 0},
                        {'nested': [{'$nativeParagraph': 0}]}):
            with self.subTest(valid_context=context):
                body = with_empty_unit(self.body)
                graph = body['candidateGraph']
                graph['producerVersion'] = GRAPH_VERSION
                graph['nativeDocxOutline'] = {'paragraphs': [{'sourcePosition': {'nodePath': 'AUTHORED'}}]}
                graph['units'][-1]['parts'][0]['readingContext']['nativeDocxParagraphs'] = context
                projection = self.publish(body)
                self.assertEqual(1, len(projection['nonRetrievableUnits']))
                self.assertEqual(graph['units'][-1], projection['canonical']['units'][-1])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def _section_body(self, section):
        from normal_observed_projection import GRAPH_VERSION
        body = copy.deepcopy(self.body)
        graph = body['candidateGraph']
        graph['producerVersion'] = GRAPH_VERSION
        graph['nativeDocxSections'] = [section]
        for unit in graph['units']:
            for part in unit['parts']:
                part['readingContext']['nativeDocxSection'] = {'$nativeSection': 0}
        return body

    def test_compact_sections_share_the_inventory_and_restore_complete_chunk_context(self):
        section = {'sectionId': 'AUTHORED section', 'ownerStatus': 'unknown',
                   'headerFooterBindings': [{'status': 'unknown', 'raw': 'AUTHORED full binding ' * 2000}]}
        body = self._section_body(section)
        before = _serialize(body)
        projection = self.publish(body)
        graph = body['candidateGraph']
        self.assertEqual(before, _serialize(body))
        self.assertEqual(graph, projection['manifest']['candidateGraph'])
        self.assertEqual(graph['units'], projection['canonical']['units'])
        self.assertEqual([section], projection['manifest']['candidateGraph']['nativeDocxSections'])
        for chunk in projection['chunks']:
            for part in chunk['parts']:
                self.assertEqual(section, part['readingContext']['nativeDocxSection'])
        self.assertLess(len(_serialize(graph)), len(_serialize(projection['chunks'])))
        loaded = self.client.get('/canonical/' + projection['projectionSha256'] + '?module=vetting')
        self.assertEqual(200, loaded.status_code, loaded.text[:1000])
        self.assertEqual(_serialize(projection), loaded.content)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_compact_section_bad_references_and_targets_never_publish(self):
        invalid = ({'$nativeSection': -1}, {'$nativeSection': True}, {'$nativeSection': 0.0},
                   {'$nativeSection': '0'}, {'$nativeSection': None}, {'$nativeSection': 1},
                   {'$nativeSection': 2147483648}, {'$nativeSection': 0, 'extra': 'untrusted'})
        for reference in invalid:
            with self.subTest(reference=reference):
                body = self._section_body({'sectionId': 'AUTHORED'})
                body['candidateGraph']['units'][0]['parts'][0]['readingContext']['nativeDocxSection'] = reference
                rejected = self.client.post('/normal-observed', json=body)
                self.assertEqual(422, rejected.status_code, rejected.text[:1000])
                self.assertIn('NORMAL_OBSERVED_SECTION_REF_INVALID', rejected.text)
        for target in (None, [], 'untrusted', True, {'nested': {'$nativeSection': 0}},
                       {'nested': {'$nativeParagraph': 0}}):
            with self.subTest(target=target):
                rejected = self.client.post('/normal-observed', json=self._section_body(target))
                self.assertEqual(422, rejected.status_code, rejected.text[:1000])
                self.assertIn('NORMAL_OBSERVED_SECTION_REF_INVALID', rejected.text)
        body = self._section_body({'sectionId': 'AUTHORED'})
        del body['candidateGraph']['nativeDocxSections']
        self.assertEqual(422, self.client.post('/normal-observed', json=body).status_code)
        body = self._section_body({'sectionId': 'AUTHORED'})
        graph = body['candidateGraph']
        graph['nativeDocxOutline'] = {'paragraphs': [{'sourcePosition': {},
                                                   'nested': {'$nativeSection': 0}}]}
        graph['units'][0]['parts'][0]['readingContext']['nativeDocxParagraph'] = {'$nativeParagraph': 0}
        rejected = self.client.post('/normal-observed', json=body)
        self.assertEqual(422, rejected.status_code, rejected.text[:1000])
        self.assertIn('NORMAL_OBSERVED_PARAGRAPH_REF_INVALID', rejected.text)
        self.assertEqual([], list(self.registry.root.glob('*.json')))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_empty_units_reject_nested_section_refs_without_rejecting_inline_shapes(self):
        for context in ({'$nativeSection': -1}, {'unknown': [{'$nativeSection': -1}]}):
            with self.subTest(context=context):
                body = with_empty_unit(self._section_body({'sectionId': 'AUTHORED'}))
                body['candidateGraph']['units'][-1]['parts'][0]['readingContext']['nativeDocxSection'] = context
                rejected = self.client.post('/normal-observed', json=body)
                self.assertEqual(422, rejected.status_code, rejected.text[:1000])
                self.assertIn('NORMAL_OBSERVED_SECTION_REF_INVALID', rejected.text)
        self.assertEqual([], list(self.registry.root.glob('*.json')))
        for context in (None, 7, ['AUTHORED literal'], {'unknown': 'AUTHORED inline shape'}):
            with self.subTest(inline_context=context):
                body = with_empty_unit(self._section_body({'sectionId': 'AUTHORED'}))
                graph = body['candidateGraph']
                for unit in graph['units']:
                    for part in unit['parts']:
                        part['readingContext']['nativeDocxSection'] = context
                del graph['nativeDocxSections']
                projection = self.publish(body)
                self.assertEqual(1, len(projection['nonRetrievableUnits']))
                self.assertEqual(graph['units'][-1], projection['canonical']['units'][-1])
                for chunk in projection['chunks']:
                    for part in chunk['parts']:
                        self.assertEqual(context, part['readingContext']['nativeDocxSection'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_legacy_inline_sections_and_undefined_legacy_markers_remain_unchanged(self):
        for context in ({'sectionId': 'AUTHORED v2 inline section', 'ownerStatus': 'unknown'},
                        {'$nativeSection': 0}):
            with self.subTest(context=context):
                body = copy.deepcopy(self.body)
                for unit in body['candidateGraph']['units']:
                    for part in unit['parts']:
                        part['readingContext']['nativeDocxSection'] = context
                projection = self.publish(body)
                self.assertEqual(body['candidateGraph'], projection['manifest']['candidateGraph'])
                for chunk in projection['chunks']:
                    for part in chunk['parts']:
                        self.assertEqual(context, part['readingContext']['nativeDocxSection'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_real_registry_mixed_index_and_retrieval_preserve_whole_units(self):
        projection = self.publish()
        bundle, adoption, _ = asset_fixture(self.store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        legacy = project_canonical(bundle, adoption, artifact_store=self.store)
        self.registry.publish(legacy)
        result = self.client.post('/index-canonical', json={'projectId': 'authored-normal',
            'projectionSha256': [projection['projectionSha256'], legacy['projectionSha256']], 'module': 'vetting'})
        self.assertEqual(200, result.status_code, result.text)
        stored = self.backend._projects['authored-normal']['chunks']
        self.assertEqual(projection['chunks'] + legacy['chunks'], stored)
        self.assertEqual(1, self.registry.validate_native_chunks(projection['chunks']))
        queried = self.client.post('/retrieve', json={'projectId': 'authored-normal', 'query': 'Visible',
            'candidates': 10, 'limit': 10})
        self.assertEqual(200, queried.status_code, queried.text)
        self.assertTrue(self.encodes)
        self.assertTrue(self.predictions)

    def test_candidate_authority_and_complete_structure_cannot_be_promoted_or_truncated(self):
        mutations = (
            lambda body: body['candidateGraph'].__setitem__('humanConfirmed', True),
            lambda body: body['candidateGraph'].__setitem__('sourceQualityAccepted', True),
            lambda body: body['candidateGraph'].__setitem__('adoptionStatus', 'accepted'),
            lambda body: body['candidateGraph'].__setitem__('status', 'generation_binding_unknown'),
            lambda body: body['candidateGraph']['units'][0]['parts'].pop(),
            lambda body: body['candidateGraph']['units'][0]['sourceSpans'].reverse(),
            lambda body: body['sourceBinding'].__setitem__('scopePolicySha256', 'f' * 64),
            lambda body: body['sourceBinding'].__setitem__('eligibleModules', ['advisory']),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                body = copy.deepcopy(self.body)
                mutate(body)
                self.assertEqual(422, self.client.post('/normal-observed', json=body).status_code)
        self.assertEqual([], self.encodes)

    def test_registered_cas_and_exact_parent_order_are_checked_before_inference(self):
        projection = self.publish()
        for chunks in (projection['chunks'][:1], list(reversed(projection['chunks']))):
            response = self.client.post('/index', json={'projectId': 'authored-normal', 'chunks': chunks})
            self.assertEqual(422, response.status_code, response.text)
        downgraded = copy.deepcopy(projection['chunks'][:1])
        for field in ('producerKind', 'projectionVersion', 'metadataVersion', 'addressKind', 'observationManifestSha256'):
            del downgraded[0][field]
        response = self.client.post('/index', json={'projectId': 'authored-normal', 'chunks': downgraded})
        self.assertEqual(422, response.status_code, response.text)
        descriptor = projection['observationManifestRef']
        path = self.store.root / descriptor['relativePath']
        original = path.read_bytes()
        path.write_bytes(original.replace(b'Visible row A', b'changed row A'))
        response = self.client.get('/canonical/' + projection['projectionSha256'])
        self.assertNotEqual(200, response.status_code)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_old_review_registration_stays_strict_and_normal_extra_fields_are_rejected(self):
        bundle, adoption, _ = asset_fixture(self.store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        adoption['reference']['actor'] = 'application'
        response = self.client.post('/project', json={'bundle': bundle, 'adoptionManifest': adoption})
        self.assertEqual(422, response.status_code, response.text)
        body = copy.deepcopy(self.body)
        body['adoptionManifest'] = adoption
        self.assertEqual(422, self.client.post('/normal-observed', json=body).status_code)
        raw = _serialize(self.body).decode()
        duplicate = '{"candidateGraph":{},' + raw[1:]
        self.assertEqual(422, self.client.post('/normal-observed', content=duplicate,
            headers={'content-type': 'application/json'}).status_code)
        self.assertEqual([], self.encodes)

    def test_warm_reconstruction_still_rehashes_complete_actual_manifest_at_frame_exit(self):
        projection = self.publish()
        address = projection['projectionSha256']
        for _ in range(2):
            with self.registry.validation_frame(software_guard=self.rt.algorithms):
                self.assertEqual(projection, self.registry.load(address))
        descriptor = projection['observationManifestRef']
        path = self.store.root / descriptor['relativePath']
        original = path.read_bytes()
        try:
            with self.assertRaises(ValueError):
                with self.registry.validation_frame(software_guard=self.rt.algorithms):
                    self.registry.load(address)
                    path.write_bytes(original.replace(b'Visible row A', b'changed row A'))
        finally:
            path.write_bytes(original)
        with self.registry.validation_frame(software_guard=self.rt.algorithms):
            self.assertEqual(projection, self.registry.load(address))

    def test_complete_response_limit_fails_without_publishing_partial_root(self):
        self.client.close()
        app = create_unified_app(self.policy, backend=self.backend, maximum_message_bytes=10000)
        self.client = TestClient(app)
        response = self.client.post('/normal-observed', json=self.body)
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('NORMAL_OBSERVED_MESSAGE_TOO_LARGE', response.json()['detail']['kind'])
        self.assertEqual([], list(app.state.source_registry.root.glob('*.json')))
        self.assertEqual([], self.encodes)

    def test_empty_actual_unit_is_retained_and_explicitly_not_submitted_to_model(self):
        body = with_empty_unit(self.body)
        projection = self.publish(body)
        self.assertEqual(body['candidateGraph'], projection['manifest']['candidateGraph'])
        self.assertEqual(body['candidateGraph']['units'], projection['canonical']['units'])
        empty = body['candidateGraph']['units'][-1]
        self.assertEqual([{'unitId': empty['unitId'], 'sourceOrder': 3, 'reason': 'no_observed_text'}],
                         projection['nonRetrievableUnits'])
        self.assertEqual(2, len(projection['chunks']))
        result = self.client.post('/index-canonical', json={'projectId': 'authored-normal',
            'projectionSha256': [projection['projectionSha256']], 'module': 'vetting'})
        self.assertEqual(200, result.status_code, result.text)
        stored = self.backend._projects['authored-normal']['chunks']
        self.assertTrue(all(chunk['unitId'] != empty['unitId'] for chunk in stored))

    def test_chunked_or_forged_length_body_is_bounded_before_pydantic_and_publication(self):
        self.app = create_unified_app(self.policy, backend=self.backend, maximum_message_bytes=16 * 1024 * 1024)
        for headers in ([ (b'content-type', b'application/json') ],
                        [ (b'content-type', b'application/json'), (b'content-length', b'1') ]):
            with self.subTest(headers=headers):
                received, events = [], []
                async def receive():
                    received.append(1)
                    self.assertLessEqual(len(received), 17)
                    return {'type': 'http.request', 'body': b'x' * (1024 * 1024), 'more_body': True}
                async def send(event):
                    events.append(event)
                asyncio.run(self.app({'type': 'http', 'asgi': {'version': '3.0'}, 'method': 'POST',
                    'path': '/normal-observed', 'headers': headers, 'query_string': b'',
                    'scheme': 'http', 'http_version': '1.1', 'server': ('testserver', 80),
                    'client': ('testclient', 123)}, receive, send))
                self.assertEqual(17, len(received))
                self.assertEqual(413, events[0]['status'])
                self.assertEqual([], list(self.registry.root.glob('*.json')))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_boolean_source_span_offsets_are_rejected_before_publication(self):
        start_body = copy.deepcopy(self.body)
        start_body['candidateGraph']['units'][0]['sourceSpans'][0]['startOffset'] = False
        end_body = copy.deepcopy(self.body)
        unit = end_body['candidateGraph']['units'][-1]
        unit['parts'][0].update(text='x', endOffset=1)
        unit['sourceSpans'][0]['endOffset'] = True
        unit['text'] = 'x'
        for body in (start_body, end_body):
            with self.subTest(body=body['candidateGraph']['units'][-1]['text']):
                response = self.client.post('/normal-observed', json=body)
                self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], list(self.registry.root.glob('*.json')))
        self.assertEqual([], self.encodes)

    def test_canonical_byte_seal_does_not_treat_false_as_integer_zero(self):
        projection = self.publish()
        invalid = copy.deepcopy(projection)
        invalid['humanConfirmed'] = 0
        self.registry._path(projection['projectionSha256']).write_bytes(_serialize(invalid))
        with self.assertRaises(ValueError):
            self.registry.load(projection['projectionSha256'])
        self.assertNotEqual(200, self.client.get('/canonical/' + projection['projectionSha256']).status_code)

    def test_boolean_physical_page_is_not_an_integer_page(self):
        body = copy.deepcopy(self.body)
        unit = body['candidateGraph']['units'][-1]
        unit['parts'][0]['physicalPageNo'] = 1
        unit['parts'][0]['sourcePosition'].update(kind='physical_page', physicalPageNo=1)
        unit['sourceSpans'][0]['sourcePosition'] = copy.deepcopy(unit['parts'][0]['sourcePosition'])
        unit['physicalPages'] = [True]
        response = self.client.post('/normal-observed', json=body)
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], list(self.registry.root.glob('*.json')))

    def test_index_requires_exact_boolean_payload_of_published_parents(self):
        projection = self.publish()
        changed = copy.deepcopy(projection['chunks'])
        changed[0]['humanConfirmed'] = 0
        response = self.client.post('/index', json={'projectId': 'authored-normal', 'chunks': changed})
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_independent_module_registries_do_not_share_a_publication_or_index_target(self):
        other = base_tests.OerHttpContractTest('test_health_keeps_identity_inference_free_and_scope_in_fingerprint')
        other.setUp()
        other.client.close()
        try:
            app = create_unified_app(other.policy, backend=other.backend, target_module='advisory')
            runtime = other.backend._windows_runtime
            runtime.tokenizer = lambda kind: CharacterTokenizer()
            runtime.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
            other.client = TestClient(app)
            body = fixture(other.policy)
            body['sourceBinding']['module'] = 'advisory'
            response = other.client.post('/normal-observed', json=body)
            self.assertEqual(200, response.status_code, response.text)
            address = response.json()['projectionSha256']
            self.assertEqual(200, other.client.get('/canonical/' + address + '?module=advisory').status_code)
            self.assertEqual(404, self.client.get('/canonical/' + address + '?module=advisory').status_code)
            request = {'projectId': 'authored-normal', 'projectionSha256': [address], 'module': 'advisory'}
            self.assertEqual(200, other.client.post('/index-canonical', json=request).status_code)
            request['module'] = 'vetting'
            self.assertEqual(422, other.client.post('/index-canonical', json=request).status_code)
            self.assertEqual([], self.encodes)
            self.assertEqual([], self.predictions)
        finally:
            other.tearDown()


    def test_large_normal_whole_message_survives_publication_cold_alias_and_frame(self):
        maximum = 64 * 1024 * 1024
        self.app = create_unified_app(self.policy, backend=self.backend, maximum_message_bytes=maximum)
        self.client.close()
        self.client = TestClient(self.app)
        self.registry = self.app.state.source_registry
        self.store = self.app.state.artifact_store
        self.body['candidateGraph']['unknowns'].append({'path': 'authored.capacity',
            'status': 'unknown', 'reason': '\u754c' * (6 * 1024 * 1024)})
        request = _serialize(self.body)
        self.assertGreater(len(request), 16 * 1024 * 1024)
        response = self.client.post('/normal-observed', content=request,
            headers={'Content-Type': 'application/json'})
        self.assertEqual(200, response.status_code, response.text[:1000])
        self.assertGreater(len(response.content), 16 * 1024 * 1024)
        result = response.json()
        projection = result['projection']
        self.assertEqual(self.body['candidateGraph'], projection['manifest']['candidateGraph'])
        self.assertEqual(maximum, self.client.get('/health').json()['oer']['normalObserved']['maximumMessageBytes'])
        self.assertEqual(16 * 1024 * 1024,
            self.client.get('/health').json()['oer']['nativePdfSourceFamily']['maximumMessageBytes'])
        # A fresh registry cannot rely on publication-time mutable state or a derived cache.
        from unified_source_registry import UnifiedCanonicalSourceRegistry
        fresh = UnifiedCanonicalSourceRegistry(self.registry.root, self.store,
            family_policy=self.policy, maximum_message_bytes=maximum)
        for registry in (self.registry, fresh):
            with registry.validation_frame(software_guard=self.backend._windows_runtime.algorithms):
                wire, chunks = registry.wire(projection['projectionSha256'])
                self.assertEqual(_serialize(projection), _serialize(wire))
                self.assertEqual(projection['chunks'], chunks)
        canonical = self.client.get('/canonical/' + projection['projectionSha256'])
        self.assertEqual(200, canonical.status_code, canonical.text[:1000])
        self.assertEqual(_serialize(projection), canonical.content)
        print(json.dumps({'case': 'authored_normal_whole_capacity',
            'graphUtf8Bytes': len(_serialize(self.body['candidateGraph'])),
            'requestUtf8Bytes': len(request), 'receiptUtf8Bytes': len(response.content),
            'projectionUtf8Bytes': len(_serialize(projection)),
            'canonicalGetUtf8Bytes': len(canonical.content),
            'registeredRootBytes': self.registry._path(projection['projectionSha256']).stat().st_size,
            'normalAliasBytes': self.registry._normal_revision_path(projection['projectionRevisionSha256']).stat().st_size,
            'maximumMessageBytes': maximum, 'allSourceFieldsPreserved': True,
            'qualityAccepted': False, 'modelsCalled': 0}, sort_keys=True))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_normal_request_and_wrapped_receipt_use_actual_utf8_boundaries(self):
        request = _serialize(self.body)
        # Small injected caps keep wrapper-overhead failure observable without truncating a unit.
        self.app = create_unified_app(self.policy, backend=self.backend,
            maximum_message_bytes=len(request))
        self.client.close()
        self.client = TestClient(self.app)
        self.registry = self.app.state.source_registry
        result = self.client.post('/normal-observed', content=request,
            headers={'Content-Type': 'application/json'})
        self.assertEqual(422, result.status_code)
        self.assertEqual('NORMAL_OBSERVED_MESSAGE_TOO_LARGE', result.json()['detail']['kind'])
        self.assertEqual([], list(self.registry.root.glob('*.json')))
        result = self.client.post('/normal-observed', content=request + b' ',
            headers={'Content-Type': 'application/json', 'Content-Length': '1'})
        self.assertEqual(413, result.status_code)
        self.assertEqual([], list(self.registry.root.glob('*.json')))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)


    def test_complete_normal_root_and_wrapped_receipt_exact_limit_and_one_less(self):
        from normal_observed_projection import produce_normal_observed
        from unified_source_registry import UnifiedCanonicalSourceRegistry
        projection = produce_normal_observed(self.body['candidateGraph'], self.body['sourceBinding'],
            artifact_store=self.store)
        root_size = len(_serialize(projection))
        for maximum in (root_size - 1, root_size):
            registry = UnifiedCanonicalSourceRegistry(self.registry.root / ('bounded-' + str(maximum)),
                self.store, family_policy=self.policy, maximum_message_bytes=maximum)
            if maximum < root_size:
                with self.assertRaises(ValueError): registry.publish(projection)
                self.assertEqual([], list(registry.root.glob('*.json')))
            else:
                registry.publish(projection)
                with registry.validation_frame(software_guard=self.backend._windows_runtime.algorithms):
                    self.assertEqual(projection, registry.wire(projection['projectionSha256'])[0])
        result = {'projectionSha256': projection['projectionSha256'], 'chunks': len(projection['chunks']),
            'assets': 0, 'immutable': True, 'qualityVerifiedByRegistry': False,
            'projection': projection, 'sourceQualityChangedByRegistry': False}
        receipt_size = len(_serialize(result))
        for maximum in (receipt_size - 1, receipt_size):
            self.app = create_unified_app(self.policy, backend=self.backend, maximum_message_bytes=maximum)
            self.client.close()
            self.client = TestClient(self.app)
            response = self.client.post('/normal-observed', content=_serialize(self.body),
                headers={'Content-Type': 'application/json'})
            self.assertEqual(422 if maximum < receipt_size else 200, response.status_code, response.text[:1000])
            if maximum == receipt_size: self.assertEqual(receipt_size, len(response.content))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)


if __name__ == '__main__':
    unittest.main()
