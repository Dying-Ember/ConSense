"""Authored normal-observation CAS/HTTP contracts; fake vectors, no source files."""
import copy
import asyncio
import hashlib
import json
import unittest

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

    def test_http_capability_and_complete_unreviewed_cas_registration(self):
        capability = self.client.get('/health').json()['oer'].get('normalObserved')
        self.assertIsInstance(capability, dict)
        self.assertEqual('consense.normal-observed.v1', capability['schema'])
        self.assertEqual('/normal-observed', capability['projectionEndpoint'])
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


if __name__ == '__main__':
    unittest.main()
