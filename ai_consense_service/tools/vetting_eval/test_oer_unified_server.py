"""Mixed actual authored CAS and fake vectors through one ASGI base; no TCP/models."""
import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from canonical_projection import _bytes, project_canonical
from native_canonical_projection import produce_native
from native_pdf_source_registry import _pdf_candidate
from native_projection_collection import publish_native_collection
from native_projection_transport import MAX_SNAPSHOT_BYTES, publish_native_transport
from native_source_registry import _native_candidate
from oer_native_collection_server import COLLECTION_CAPABILITY, COLLECTION_ALGORITHMS
from oer_native_pdf_server import NATIVE_PDF_CAPABILITY, NATIVE_PDF_ALGORITHMS
from oer_native_server import NATIVE_CAPABILITY
from oer_unified_server import (UNIFIED_ALGORITHMS, UNIFIED_CAPABILITY,
    configure_unified_environment, create_unified_app)
from oer_source_registry import SourceRegistryError
from test_native_canonical_projection import fixture as docx_fixture
from test_native_pdf_canonical_projection import fixture as pdf_fixture
from test_oer_source_registry import asset_fixture
import test_oer_server as base_tests
from test_token_windows import CharacterTokenizer


class UnifiedHttpTests(unittest.TestCase):
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
        bundle, adoption = docx_fixture(self.store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        self.collection_internal = produce_native(bundle, adoption, artifact_store=self.store)
        self.collection = publish_native_collection(self.collection_internal, artifact_store=self.store, max_batch_bytes=60000)
        bundle, adoption = docx_fixture(self.store, adapter_suffix=b'-unified-single')
        adoption['documentId'] = 'AUTHORED-single-docx'
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        self.single_body = {'bundle': bundle, 'adoptionManifest': adoption}
        self.single = publish_native_transport(produce_native(bundle, adoption, artifact_store=self.store), artifact_store=self.store)
        native, dual, adoption = pdf_fixture(self.store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        reference = json.loads(self.store.read_asset(adoption['reference']['artifactDescriptor']))
        # Explicit authored contract declaration, not a claim that this hierarchy
        # was observed in a competition source or accepted by a human reviewer.
        reference['relations'] = [{'type': 'parent', 'fromRegionId': 'r2', 'toRegionId': 'r1',
            'humanConfirmed': False, 'sourceReview': 'authored_contract_fixture_declared_parent',
            'basis': 'Authored contract-only hierarchy: native fixture r2 (physical page 2; second unit) '
                     'is the declared child of r1 (physical page 1; A1 unit). '
                     'No official contract, answer, or semantic acceptance is asserted.'}]
        descriptor = self.store.put_bytes(_bytes(reference))
        adoption['reference'].update(artifactDescriptor=descriptor, evidenceSha256=descriptor['sha256'])
        adoption['regionReview']['referenceSha256'] = descriptor['sha256']
        adoption['units'][0]['regionIds'] = ['r1']
        adoption['units'].append({'unitId': 'second', 'scope': 'whole_text_unit', 'regionIds': ['r2'],
            'partSeparator': {'kind': 'reviewed_region_boundary', 'text': '\n'}, 'clause': None})
        self.pdf_body = {key: self.store.put_bytes(_bytes(value), identity_scope='canonical_json_object')
            for key, value in zip(('nativeBundleRef', 'dualBundleRef', 'adoptionManifestRef'), (native, dual, adoption))}
        bundle, adoption, self.scan_assets = asset_fixture(self.store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        self.scan_body = {'bundle': bundle, 'adoptionManifest': adoption}
        self.scan = project_canonical(bundle, adoption, artifact_store=self.store)

    def publish_pdf(self):
        response = self.client.post('/project/native-pdf', json=self.pdf_body)
        self.assertEqual(200, response.status_code, response.text)
        return response.json()['projection']

    def publish_all(self):
        pdf = self.publish_pdf()
        for source in (self.collection, self.single, self.scan):
            self.registry.publish(source)
        return [self.collection, pdf, self.single, self.scan]

    def index(self, sources, module='vetting'):
        return self.client.post('/index-canonical', json={'projectId': 'unified-test',
            'projectionSha256': [s['projectionSha256'] for s in sources], 'module': module})

    def query(self):
        return self.client.post('/retrieve', json={'projectId': 'unified-test', 'query': 'Visible',
            'candidates': 10, 'limit': 10})

    def test_ocr_routes_are_opt_in_and_reuse_task_lifecycle(self):
        import io
        from PIL import Image

        self.assertEqual(404, self.client.post('/ocr').status_code)
        self.assertFalse(self.client.get('/health').json()['ocrRouteExposed'])
        app = create_unified_app(self.policy, backend=self.backend, with_ocr=True)
        with self.assertRaisesRegex(ValueError, 'Vetting'):
            create_unified_app(self.policy, backend=self.backend, target_module='advisory', with_ocr=True)
        stream = io.BytesIO()
        Image.new('RGB', (2, 2), 'white').save(stream, format='PNG')
        config = self.root / 'complete-ocr.json'
        raw = b'{"timeoutSeconds":30}'
        config.write_bytes(raw)
        task_id = 'a' * 32
        headers = {'X-Consense-Ocr-Purpose': 'parse', 'X-Consense-Ocr-Task-Id': task_id}
        with TestClient(app) as client, patch.dict('os.environ', {
                'CONSENSE_COMPLETE_OCR_CONFIG': str(config),
                'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': '0' * 64}):
            self.assertTrue(client.get('/health').json()['ocrRouteExposed'])
            bad = client.post('/ocr', files={'file': ('page.png', stream.getvalue(), 'image/png')}, headers=headers)
            self.assertEqual(503, bad.status_code)
            with patch.dict('os.environ', {'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': hashlib.sha256(raw).hexdigest()}), \
                    patch('complete_ocr.recognize_complete_page', return_value={'text': 'authored', 'provenance': {}}) as provider:
                response = client.post('/ocr', files={'file': ('page.png', stream.getvalue(), 'image/png')}, headers=headers)
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual('authored', response.json()['text'])
            self.assertEqual(1, provider.call_count)
            self.assertEqual('completed', client.get('/ocr/tasks/' + task_id).json()['status'])
            self.assertEqual('authored', client.get('/ocr/tasks/' + task_id + '/result').json()['text'])
            self.assertEqual('completed', client.delete('/ocr/tasks/' + task_id).json()['status'])

    def test_ocr_keeps_image_and_result_limits_without_weakening_oer_limits(self):
        import io
        from PIL import Image

        stream = io.BytesIO()
        Image.new('RGB', (2, 2), 'white').save(stream, format='PNG')
        png = stream.getvalue()
        image = png + b'\0' * (25 * 1024 * 1024 - len(png))
        config = self.root / 'complete-ocr-limit.json'
        raw = b'{"timeoutSeconds":30}'
        config.write_bytes(raw)
        text = 'x' * (16 * 1024 * 1024 + 1)
        app = create_unified_app(self.policy, backend=self.backend, with_ocr=True, maximum_message_bytes=1024)
        headers = {'X-Consense-Ocr-Purpose': 'parse', 'X-Consense-Ocr-Task-Id': 'b' * 32}
        with TestClient(app) as client, patch.dict('os.environ', {
                'CONSENSE_COMPLETE_OCR_CONFIG': str(config),
                'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': hashlib.sha256(raw).hexdigest()}), \
                patch('complete_ocr.recognize_complete_page', return_value={'text': text, 'provenance': {}}) as provider:
            response = client.post('/ocr', files={'file': ('page.png', image, 'image/png')}, headers=headers)
            self.assertEqual(200, response.status_code, response.text[:200])
            self.assertEqual(len(text), len(response.json()['text']))
            result = client.get('/ocr/tasks/' + 'b' * 32 + '/result')
            self.assertEqual(200, result.status_code)
            self.assertEqual(len(text), len(result.json()['text']))
            oversized = client.post('/ocr', files={'file': ('page.png', image + b'x', 'image/png')}, headers=headers)
            self.assertEqual(413, oversized.status_code)
            self.assertEqual('Image exceeds 25 MiB', oversized.json()['detail'])
            excess_form = client.post('/ocr', files={'file': ('page.png', image, 'image/png')},
                                      data={'sourceContext': 'x' * (1024 * 1024)}, headers=headers)
            self.assertEqual(413, excess_form.status_code)
            self.assertEqual('NATIVE_PDF_MESSAGE_TOO_LARGE', excess_form.json()['detail']['kind'])
            self.assertEqual(1, provider.call_count)
            self.assertEqual(413, client.post('/project/native-pdf', content=b' ' * 1025).status_code)

    def test_ocr_registration_refuses_missing_task_routes(self):
        routes = [route for route in self.backend.app.routes if route.path != '/ocr/tasks/{task_id}/result']
        with patch.object(self.backend.app.router, 'routes', routes):
            with self.assertRaisesRegex(ValueError, 'OCR task routes'):
                create_unified_app(self.policy, backend=self.backend, with_ocr=True)

    def test_native_table_candidate_route_is_opt_in_and_uses_original_handler(self):
        path = '/native-pdf/table-candidates'
        self.assertEqual(404, self.client.post(path).status_code)
        advisory = create_unified_app(self.policy, backend=self.backend, target_module='advisory')
        with TestClient(advisory) as client:
            self.assertEqual(404, client.post(path).status_code)
        with self.assertRaises(ValueError):
            create_unified_app(self.policy, backend=self.backend, target_module='advisory', with_ocr=True)
        app = create_unified_app(self.policy, backend=self.backend, with_ocr=True)
        source = [r for r in self.backend.app.routes if r.path == path and 'POST' in getattr(r, 'methods', ())]
        selected = [r for r in app.routes if r.path == path and 'POST' in getattr(r, 'methods', ())]
        self.assertEqual(1, len(source))
        self.assertEqual(1, len(selected))
        self.assertIs(source[0].endpoint, selected[0].endpoint)

    def test_native_table_candidate_registration_rejects_missing_and_conflicting_routes(self):
        import oer_unified_server

        path = '/native-pdf/table-candidates'
        routes = [route for route in self.backend.app.routes if route.path != path]
        with patch.object(self.backend.app.router, 'routes', routes):
            with self.assertRaises(ValueError):
                create_unified_app(self.policy, backend=self.backend, with_ocr=True)
        original_route = next(route for route in self.backend.app.routes if route.path == path)
        with patch.object(self.backend.app.router, 'routes', [*self.backend.app.routes, original_route]):
            with self.assertRaises(ValueError):
                create_unified_app(self.policy, backend=self.backend, with_ocr=True)
        original = oer_unified_server.create_native_pdf_app
        def with_conflict(*args, **kwargs):
            app = original(*args, **kwargs)
            app.add_api_route(path, lambda: {}, methods=['POST'])
            return app
        with patch.object(oer_unified_server, 'create_native_pdf_app', side_effect=with_conflict):
            with self.assertRaises(ValueError):
                create_unified_app(self.policy, backend=self.backend, with_ocr=True)

    def test_native_table_candidate_multipart_preserves_pdf_and_both_context_forms(self):
        path = '/native-pdf/table-candidates'
        app = create_unified_app(self.policy, backend=self.backend, with_ocr=True, maximum_message_bytes=1024)
        raw, native = b'%PDF-authored', '{"note":"汉"}'.encode('utf-8')
        with TestClient(app) as client, patch('native_pdf_candidates.capture_native_pdf_candidates',
                return_value={'status': 'authored-control', 'payload': 'x' * 2048}) as capture:
            for context_part in ({'data': {'nativeContext': native.decode('utf-8')}},
                    {'files': {'nativeContext': ('context.json', native, 'application/json')}}):
                files = {'file': ('authored.pdf', raw, 'application/pdf'), **context_part.get('files', {})}
                response = client.post(path, files=files, data=context_part.get('data'))
                self.assertEqual(200, response.status_code, response.text)
                self.assertEqual('authored-control', response.json()['status'])
                self.assertEqual((raw, native), capture.call_args.args)
            self.assertEqual(2, capture.call_count)

    def test_native_table_candidate_accepts_multipart_above_16_mib(self):
        path = '/native-pdf/table-candidates'
        app = create_unified_app(self.policy, backend=self.backend, with_ocr=True)
        raw = b'%PDF-' + b'x' * (MAX_SNAPSHOT_BYTES + 1)
        with TestClient(app) as client, patch('native_pdf_candidates.capture_native_pdf_candidates',
                return_value={'status': 'authored-control'}) as capture:
            response = client.post(path, files={'file': ('authored.pdf', raw, 'application/pdf')},
                                   data={'nativeContext': '{}'})
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(hashlib.sha256(raw).digest(), hashlib.sha256(capture.call_args.args[0]).digest())
            self.assertEqual(b'{}', capture.call_args.args[1])

    def test_native_table_candidate_field_and_whole_message_limits(self):
        import oer_native_pdf_server

        path = '/native-pdf/table-candidates'
        self.assertEqual(97 * 1024 * 1024, oer_native_pdf_server.NATIVE_CANDIDATE_REQUEST_MAXIMUM)
        app = create_unified_app(self.policy, backend=self.backend, with_ocr=True, maximum_message_bytes=1024)
        with patch.object(oer_native_pdf_server, 'NATIVE_CANDIDATE_REQUEST_MAXIMUM', 4096), \
                patch('native_pdf_candidates.MAX_PDF_BYTES', 8), \
                patch('native_pdf_candidates.MAX_CONTEXT_BYTES', 8), \
                patch('native_pdf_candidates.capture_native_pdf_candidates', return_value={'status': 'ok'}) as capture, \
                TestClient(app) as client:
            for pdf, native in ((b'123456789', b'{}'), (b'%PDF-', b'123456789')):
                response = client.post(path, files={'file': ('authored.pdf', pdf, 'application/pdf'),
                    'nativeContext': ('context.json', native, 'application/json')})
                self.assertEqual(413, response.status_code, response.text)
            response = client.post(path, files={'file': ('authored.pdf', b'%PDF-', 'application/pdf')},
                                   data={'nativeContext': '汉' * 3})
            self.assertEqual(413, response.status_code, response.text)
            self.assertEqual(422, client.post(path, content=b'x' * 4096).status_code)
            self.assertEqual(413, client.post(path, content=b'x' * 4097).status_code)
            self.assertEqual(413, client.request('GET', path, content=b'x' * 1025).status_code)
            self.assertEqual(413, client.post('/project/native-pdf', content=b'x' * 1025).status_code)
            capture.assert_not_called()

    def test_native_table_candidate_response_still_has_finite_snapshot_cap(self):
        import oer_native_pdf_server

        self.assertEqual(16 * 1024 * 1024, oer_native_pdf_server.MAX_SNAPSHOT_BYTES)
        app = create_unified_app(self.policy, backend=self.backend, with_ocr=True, maximum_message_bytes=1024)
        with patch.object(oer_native_pdf_server, 'MAX_SNAPSHOT_BYTES', 1024), \
                patch('native_pdf_candidates.capture_native_pdf_candidates', return_value={'payload': 'x' * 2048}), \
                TestClient(app) as client:
            response = client.post('/native-pdf/table-candidates',
                files={'file': ('authored.pdf', b'%PDF-', 'application/pdf')}, data={'nativeContext': '{}'})
            self.assertEqual(422, response.status_code, response.text)
            self.assertEqual('NATIVE_PDF_MESSAGE_TOO_LARGE', response.json()['detail']['kind'])

    def test_legacy_trailing_slash_redirects_keep_route_limits_and_handlers(self):
        import io
        from PIL import Image
        import oer_native_pdf_server

        app = create_unified_app(self.policy, backend=self.backend, with_ocr=True, maximum_message_bytes=1024)
        config = self.root / 'complete-ocr-redirect.json'
        raw_config = b'{"timeoutSeconds":30}'
        config.write_bytes(raw_config)
        stream = io.BytesIO()
        Image.new('RGB', (2, 2), 'white').save(stream, format='PNG')
        image = stream.getvalue() + b'\0' * 2048
        headers = {'X-Consense-Ocr-Purpose': 'parse', 'X-Consense-Ocr-Task-Id': 'd' * 32}
        with patch.object(oer_native_pdf_server, 'NATIVE_CANDIDATE_REQUEST_MAXIMUM', 8192), \
                patch.dict('os.environ', {'CONSENSE_COMPLETE_OCR_CONFIG': str(config),
                    'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': hashlib.sha256(raw_config).hexdigest()}), \
                patch('native_pdf_candidates.capture_native_pdf_candidates', return_value={'status': 'authored'}) as capture, \
                patch('complete_ocr.recognize_complete_page', return_value={'text': 'authored', 'provenance': {}}) as ocr, \
                TestClient(app) as client:
            response = client.post('/native-pdf/table-candidates/',
                files={'file': ('authored.pdf', b'%PDF-' + b'x' * 2048, 'application/pdf')},
                data={'nativeContext': '{}'}, follow_redirects=True)
            self.assertEqual([307], [item.status_code for item in response.history])
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual((b'%PDF-' + b'x' * 2048, b'{}'), capture.call_args.args)
            self.assertEqual(1, capture.call_count)
            response = client.post('/ocr/', files={'file': ('page.png', image, 'image/png')},
                                   headers=headers, follow_redirects=True)
            self.assertEqual([307], [item.status_code for item in response.history])
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual('authored', response.json()['text'])
            self.assertEqual(1, ocr.call_count)
            for path in ('/ocr//', '/ocr-extra', '/native-pdf/table-candidates//',
                         '/native-pdf/table-candidates-extra'):
                with self.subTest(path=path):
                    self.assertEqual(413, client.post(path, content=b'x' * 1025,
                                                      follow_redirects=False).status_code)

    def test_all_health_capabilities_and_source_algorithms_without_inference(self):
        with patch.object(self.rt, 'tokenizer', side_effect=AssertionError('model forbidden')):
            response = self.client.get('/health')
        self.assertEqual(200, response.status_code, response.text)
        health = response.json()
        for key, capability in (('nativeCanonical', NATIVE_CAPABILITY),
                ('nativeDocumentCollection', COLLECTION_CAPABILITY), ('nativePdfCanonical', NATIVE_PDF_CAPABILITY),
                ('unifiedCanonical', UNIFIED_CAPABILITY)):
            expected = dict(capability)
            if key != 'unifiedCanonical':
                expected.update(artifactReadValidation=UNIFIED_CAPABILITY['artifactReadValidation'],
                    artifactReadValidationScope='canonical_load')
                self.assertEqual('actual-cas-every-read', capability['artifactReadValidation'])
                self.assertNotIn('artifactReadValidationScope', capability)
            self.assertEqual(expected, health['oer'][key])
        algorithms = health['retrieval']['sourceWindows']['algorithms']
        for name in (*COLLECTION_ALGORITHMS, *NATIVE_PDF_ALGORITHMS, *UNIFIED_ALGORITHMS):
            self.assertRegex(algorithms[name], r'^[a-f0-9]{64}$')
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_same_base_serves_all_roots_whole_batches_and_legacy_scan_assets(self):
        sources = self.publish_all()
        for source in sources:
            address = source['projectionSha256']
            response = self.client.get('/canonical/' + address + '?module=advisory')
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(_bytes(source), response.content)
            self.assertEqual(address, response.headers['x-projection-sha256'])
            self.assertEqual(404, self.client.get('/canonical/' + address + '?module=drafting').status_code)
        for batch in self.collection['batches']:
            response = self.client.get('/canonical/' + self.collection['projectionSha256']
                + '/batches/' + str(batch['ordinal']) + '?module=advisory')
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(batch['artifactDescriptor']['sha256'], hashlib.sha256(response.content).hexdigest())
        self.assertNotIn('chunks', self.client.get('/canonical/' + self.collection['projectionSha256']).json())
        for source in sources[1:]:
            self.assertEqual(404, self.client.get('/canonical/' + source['projectionSha256'] + '/batches/0').status_code)
        ref = self.scan['assetRefs'][0]
        path = '/canonical/' + self.scan['projectionSha256'] + '/sources/' + self.scan['source']['sourceSha256'] + '/assets/' + ref['assetId']
        response = self.client.get(path + '?module=advisory')
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(ref['sha256'], hashlib.sha256(response.content).hexdigest())

    def test_mixed_index_and_retrieval_retain_complete_parent_order_and_exact_payloads(self):
        sources = self.publish_all()
        response = self.index(sources)
        self.assertEqual(200, response.status_code, response.text)
        expected = [chunk for source in sources for chunk in self.registry.chunks(source['projectionSha256'])]
        stored = self.backend._projects[self.backend.project_key('unified-test')]['chunks']
        self.assertEqual(expected, stored)
        self.assertEqual(4, len({c['documentId'] for c in stored}))
        pdf = sources[1]
        self.assertTrue(_native_candidate(pdf['chunks'][0]), 'Existing DOCX detector also recognizes PDF prefixes')
        self.assertTrue(_pdf_candidate(pdf['chunks'][0]))
        declared = pdf['chunks'][0]['relations'][0]
        self.assertIs(False, declared['humanConfirmed'])
        self.assertEqual('authored_contract_fixture_declared_parent', declared['sourceReview'])
        self.assertEqual(('r2', 'r1'), (declared['fromRegionId'], declared['toRegionId']))
        self.assertEqual(3, self.registry.validate_native_chunks(stored))
        result = self.query()
        self.assertEqual(200, result.status_code, result.text)
        by_id = {c['id']: c for c in expected}
        self.assertTrue(result.json()['hits'])
        self.assertEqual({c['documentId'] for c in expected},
                         {hit['payload']['documentId'] for hit in result.json()['hits']})
        for hit in result.json()['hits']:
            self.assertEqual(by_id[hit['id']], hit['payload'])
        for unit in result.json()['sourceUnits']['units']:
            for member in unit['members']:
                self.assertEqual(by_id[member['id']], member)

    def test_bare_mixed_parent_declaration_withholds_structural_query_before_rerank(self):
        # Preserve the original bare declaration as an explicit trust-gate
        # negative, with the same endpoints and complete native source payloads.
        adoption = json.loads(self.store.read_asset(self.pdf_body['adoptionManifestRef']))
        reference = json.loads(self.store.read_asset(adoption['reference']['artifactDescriptor']))
        reference['relations'] = [{'type': 'parent', 'fromRegionId': 'r2', 'toRegionId': 'r1'}]
        descriptor = self.store.put_bytes(_bytes(reference))
        adoption['reference'].update(artifactDescriptor=descriptor, evidenceSha256=descriptor['sha256'])
        adoption['regionReview']['referenceSha256'] = descriptor['sha256']
        self.pdf_body['adoptionManifestRef'] = self.store.put_bytes(_bytes(adoption),
            identity_scope='canonical_json_object')
        sources = self.publish_all()
        self.assertEqual(200, self.index(sources).status_code)
        response = self.query()
        self.assertEqual(503, response.status_code, response.text)
        detail = response.json()['detail']
        self.assertEqual('STRUCTURAL_CONTEXT_UNAVAILABLE', detail['kind'])
        unavailable = detail['metadata']['contextUnavailable']
        self.assertEqual(2, len(unavailable))
        self.assertTrue(all(row['reason'] == 'hierarchy_parent_declaration_untrusted'
                            for row in unavailable))
        self.assertEqual([], self.predictions)

    def test_existing_project_endpoints_publish_into_same_registry(self):
        for path, body in (('/project', self.scan_body), ('/project/native', self.single_body)):
            response = self.client.post(path, json=body)
            self.assertEqual(200, response.status_code, response.text)
            fetched = self.client.get('/canonical/' + response.json()['projectionSha256'])
            self.assertEqual(response.json()['projection'], fetched.json())
        descriptor = self.store.put_bytes(_bytes(self.collection_internal), identity_scope='canonical_json_object')
        response = self.client.post('/project/native-collection', json={'internalProjectionDescriptor': descriptor})
        self.assertEqual(200, response.status_code, response.text)
        self.assertNotIn('chunks', response.json()['projection'])
        fetched = self.client.get('/canonical/' + response.json()['projectionSha256'] + '/batches/0')
        self.assertEqual(200, fetched.status_code, fetched.text)
        self.assertEqual(self.registry.chunks(response.json()['projectionSha256']), fetched.json()['chunks'])

    def test_partial_or_reordered_mixed_parent_payloads_are_rejected_before_fake_inference(self):
        sources = self.publish_all()
        whole = [chunk for source in sources for chunk in self.registry.chunks(source['projectionSha256'])]
        candidates = [whole[1:], list(reversed(whole[:3])) + whole[3:]]
        # Omit or reverse only PDF parents while retaining complete collection parents.
        candidates.extend([whole[:3] + whole[4:], whole[:3] + list(reversed(whole[3:5])) + whole[5:]])
        for chunks in candidates:
            response = self.client.post('/index', json={'projectId': 'bad-mixed', 'chunks': chunks})
            self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes)

    def test_independent_pdf_markers_cannot_downgrade_to_docx_collection_or_scan(self):
        pdf = self.publish_pdf()
        for schema in ('consense.canonical-projection.v1', self.collection['schema'], self.single['schema']):
            changed = copy.deepcopy(pdf)
            changed['schema'] = schema
            for key in ('producerKind', 'storageShape', 'projectionVersion'):
                changed.pop(key, None)
            with self.assertRaises(ValueError):
                self.registry.publish(changed)
        self.registry.publish(self.collection)
        chunks = self.registry.chunks(self.collection['projectionSha256']) + copy.deepcopy(pdf['chunks'])
        for chunk in chunks[3:]:
            for key in ('producerKind', 'storageShape', 'projectionVersion', 'metadataVersion'):
                chunk.pop(key, None)
        response = self.client.post('/index', json={'projectId': 'stripped-mixed', 'chunks': chunks})
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes)

    def test_illegal_collection_wire_shapes_and_duplicate_keys_are_controlled(self):
        self.registry.publish(self.collection)
        address = self.collection['projectionSha256']
        path = self.registry._path(address)
        original = path.read_bytes()
        try:
            for raw in (b'[]', b'null', b'"wrong"', b'{"projectionSha256": "' + address.encode() + b'", "projectionSha256": "' + address.encode() + b'"}'):
                path.write_bytes(raw)
                with self.assertRaises(SourceRegistryError): self.registry.load(address)
                self.assertEqual(404, self.client.get('/canonical/' + address).status_code)
                self.assertEqual(422, self.index([self.collection]).status_code)
        finally:
            path.write_bytes(original)
        self.assertEqual([], self.encodes)

    def test_missing_nonrequested_collection_batch_invalidates_whole_mixed_source(self):
        pdf = self.publish_pdf()
        self.registry.publish(self.collection)
        descriptor = self.collection['batches'][2]['artifactDescriptor']
        (self.store.root / descriptor['relativePath']).unlink()
        address = self.collection['projectionSha256']
        self.assertEqual(404, self.client.get('/canonical/' + address + '/batches/0').status_code)
        self.assertEqual(422, self.index([self.collection, pdf]).status_code)
        self.assertEqual([], self.encodes)

    def test_pdf_actual_cas_mutation_after_disk_index_restore_prevents_query_inference(self):
        sources = self.publish_all()
        self.assertEqual(200, self.index(sources).status_code)
        before = copy.deepcopy(self.encodes)
        descriptor = sources[1]['nativeCaptureRefs']['rawObservation']
        (self.store.root / descriptor['relativePath']).write_bytes(b'corrupted actual PDF observation')
        self.backend._projects.clear()
        response = self.query()
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual(before, self.encodes)
        self.assertEqual([], self.predictions)

    def test_collection_cas_drift_during_mixed_query_withholds_stale_result(self):
        sources = self.publish_all()
        self.assertEqual(200, self.index(sources).status_code)
        descriptor = self.collection_internal['nativeObservationBundle']['rawObservation']
        encode = self.backend.encode_embeddings
        def mutate(texts):
            result = encode(texts)
            (self.store.root / descriptor['relativePath']).write_bytes(b'changed during mixed query')
            return result
        self.backend.encode_embeddings = mutate
        response = self.query()
        self.assertEqual(422, response.status_code, response.text)
        self.assertTrue(self.predictions)

    def test_pdf_root_cap_also_applies_to_persisted_index_proof_reads(self):
        sources = self.publish_all()
        self.assertEqual(200, self.index(sources).status_code)
        before = copy.deepcopy(self.encodes)
        path = self.registry._path(sources[1]['projectionSha256'])
        # Semantically unchanged JSON padding cannot evade the whole-wire cap.
        raw = path.read_bytes()
        path.write_bytes(raw + b' ' * (MAX_SNAPSHOT_BYTES + 1 - len(raw)))
        self.backend._projects.clear()
        response = self.query()
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual(before, self.encodes)
        self.assertEqual([], self.predictions)

    def test_matching_advisory_runtime_indexes_the_same_eligible_mixed_sources(self):
        sources = self.publish_all()
        advisory = create_unified_app(self.policy, backend=self.backend, artifact_store=self.store, target_module='advisory')
        runtime = self.backend._windows_runtime
        runtime.tokenizer = lambda kind: CharacterTokenizer()
        runtime.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        with TestClient(advisory) as client:
            self.assertEqual('advisory', client.get('/health').json()['oer']['targetModule'])
            response = client.post('/index-canonical', json={'projectId': 'advisory-mixed',
                'projectionSha256': [s['projectionSha256'] for s in sources], 'module': 'advisory'})
            self.assertEqual(200, response.status_code, response.text)
            wrong = client.post('/index-canonical', json={'projectId': 'wrong-target',
                'projectionSha256': [s['projectionSha256'] for s in sources], 'module': 'vetting'})
            self.assertEqual(422, wrong.status_code, wrong.text)

    def test_requested_module_and_templates_do_not_escape_source_scope(self):
        sources = self.publish_all()
        response = self.index(sources, module='advisory')
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('INDEX_TARGET_MODULE_MISMATCH', response.json()['detail']['kind'])
        for source in sources:
            for module in ('drafting', 'unknown'):
                self.assertEqual(404, self.client.get('/canonical/' + source['projectionSha256'] + '?module=' + module).status_code)
        for source in (self.single, self.collection):
            changed = copy.deepcopy(source)
            changed['projectionIdentity']['sourcePurpose'] = 'standard_template'
            with self.assertRaises(ValueError): self.registry.publish(changed)
        self.assertEqual([], self.encodes)

    def test_fixed_message_cap_request_response_and_invalid_configuration(self):
        response = self.client.post('/index', content=b' ' * (MAX_SNAPSHOT_BYTES + 1), headers={'Content-Type': 'application/json'})
        self.assertEqual(413, response.status_code, response.text)
        for maximum in (1024 * 1024 * 1024 + 1, True, 1023):
            with self.assertRaises(ValueError):
                create_unified_app(self.policy, backend=self.backend, artifact_store=self.store, maximum_message_bytes=maximum)
        # A complete response envelope must fit, not just the projection field.
        with TestClient(create_unified_app(self.policy, backend=self.backend, artifact_store=self.store,
                                          maximum_message_bytes=8192)) as bounded:
            response = bounded.post('/project/native-pdf', json=self.pdf_body)
            self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes)

    def test_oversized_stored_root_is_not_a_hydrated_http_escape(self):
        self.registry.publish(self.collection)
        path = self.registry._path(self.collection['projectionSha256'])
        path.write_bytes(b' ' * (MAX_SNAPSHOT_BYTES + 1))
        with self.assertRaisesRegex(SourceRegistryError, '16MiB'):
            self.registry.load(self.collection['projectionSha256'])
        self.assertEqual(404, self.client.get('/canonical/' + self.collection['projectionSha256']).status_code)

    def test_all_composed_json_routes_reject_duplicate_request_keys(self):
        for path, raw in (('/project/native-collection', b'{"internalProjectionDescriptor":{},"internalProjectionDescriptor":{}}'),
                          ('/index', b'{"projectId":"a","projectId":"b","chunks":[]}')):
            response = self.client.post(path, content=raw, headers={'Content-Type': 'application/json'})
            self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes)

    def test_unified_wrapper_hash_and_all_old_default_state_directories_are_guarded(self):
        with patch('oer_unified_server._UNIFIED_HASHES', {}):
            with self.assertRaisesRegex(ValueError, 'Unified wrapper bytes changed'): self.rt.algorithms()
        supplied = {'CONSENSE_SL_SCOPE_MANIFEST': str(self.scope_path)}
        values = configure_unified_environment(supplied, self.root)
        self.assertEqual(str((self.root / '.local/oer/unified-retrieval-state').resolve()), values['CONSENSE_RETRIEVAL_DATA'])
        for name in ('retrieval-state', 'native-retrieval-state', 'native-collection-retrieval-state', 'native-pdf-retrieval-state'):
            with self.assertRaisesRegex(ValueError, 'separate state'):
                configure_unified_environment({**supplied, 'CONSENSE_RETRIEVAL_DATA': str(self.root / '.local/oer' / name)}, self.root)


if __name__ == '__main__':
    unittest.main()
