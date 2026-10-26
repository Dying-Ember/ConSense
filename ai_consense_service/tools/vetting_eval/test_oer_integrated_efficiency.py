"""Authored work counters and mutation boundaries for the integrated candidate."""
import copy
import hashlib
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import weakref

from artifact_store import ArtifactStoreError
from retrieval_source_units import SourceUnits
import test_normal_conditional_wire as conditional_tests
from test_normal_observed import many_unit_fixture


class IntegratedEfficiencyTests(unittest.TestCase):
    setUp = conditional_tests.NormalConditionalWireTests.setUp
    tearDown = conditional_tests.NormalConditionalWireTests.tearDown
    publish = conditional_tests.NormalConditionalWireTests.publish
    warm = conditional_tests.NormalConditionalWireTests.warm
    url = conditional_tests.NormalConditionalWireTests.url

    def test_current_health_reports_explicit_dual_contract_version6_without_inference(self):
        with patch.object(self.registry, 'load', side_effect=AssertionError('Health must not load sources')), \
                patch.object(self.rt, 'tokenizer', side_effect=AssertionError('Health must not prepare a tokenizer')):
            response = self.client.get('/health')
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(6, response.json()['indexSignatureVersion'])
        self.assertEqual('source-bound-dual-granularity-structure-results-v2', response.json()['retrieval']['sourceWindows']['contract'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_changed_runtime_modules_reject_unreviewed_executable_changes(self):
        import portable_index_signature as adapter
        from token_windows import WindowError
        root = Path(adapter.__file__).resolve().parent
        for name in ('artifact_store.py', 'unified_source_registry.py', 'server.py',
                'window_runtime.py', 'source_read_server.py', 'oer_request_evidence.py',
                'oer_server.py', 'portable_index_signature.py'):
            with self.subTest(module=name):
                current = (root / name).read_text(encoding='utf-8')
                adapter.computation_proof(name, current, current)
                with self.assertRaises(WindowError):
                    adapter.computation_proof(name, current, current + '\nUNREVIEWED_INTEGRATED_CHANGE = True\n')

    def indexed(self):
        self.body['candidateGraph']['units'][0]['parts'][0]['readingContext']['authoredUnusedLayout'] = 'x' * (512 * 1024)
        source, _ = self.warm()
        key = 'authored-integrated-work'
        body = {'projectId': key, 'module': self.module, 'projectionSha256': [source['projectionSha256']]}
        response = self.client.post('/index-canonical', json=body)
        self.assertEqual(200, response.status_code, response.text)
        return source, key, body, response.json()['signature']

    def counts(self, key, action):
        result = {'rootDecodes': 0, 'metadataDecodes': 0, 'metadataBytes': 0}
        original_loads = json.loads
        original_loader = self.backend.load_project_metadata
        metrics = []
        def loads(raw, *args, **kwargs):
            value = original_loads(raw, *args, **kwargs)
            if isinstance(value, dict):
                if value.get('schema') == 'consense.normal-observed.v1' and 'canonical' in value:
                    result['rootDecodes'] += 1
                if value.get('projectId') == key and 'signature' in value and 'windows' in value:
                    result['metadataDecodes'] += 1
            return value
        def load(project):
            value = original_loader(project)
            frame = self.registry._validation_frame.get()
            if frame is not None:
                record = frame['indexMetadata'][project]['metrics']
                if all(record is not prior for prior in metrics):
                    metrics.append(record)
            return value
        with patch.object(json, 'loads', loads), patch.object(self.backend, 'load_project_metadata', load):
            response = action()
        result['metadataBytes'] = sum(record['bytesRead'] for record in metrics)
        result['status'] = response.status_code
        return response, result

    def test_status_availability_query_share_proof_and_metadata_owner(self):
        _, key, body, signature = self.indexed()
        self.backend._projects.pop(key, None)
        path = self.backend.metadata_path(key)
        rows = []
        status, measured = self.counts(key, lambda: self.client.post('/index-canonical/status', json=body))
        self.assertEqual(200, status.status_code, status.text)
        self.assertEqual((0, 1, 2 * path.stat().st_size),
            (measured['rootDecodes'], measured['metadataDecodes'], measured['metadataBytes']))
        rows.append({'operation': 'status_cold_metadata', **measured})
        operations = [('/index-canonical/status', body),
                      ('/retrieve', {'projectId': key, 'query': 'Visible', 'candidates': 4, 'limit': 2})]
        if self.module == 'advisory':
            operations.insert(1, ('/index-canonical/availability', {**body, 'expectedSignature': signature}))
        for endpoint, request in operations:
            with self.subTest(endpoint=endpoint), patch.object(self.registry, '_wire',
                    side_effect=AssertionError('A warm normal proof must not decode a complete root')):
                response, measured = self.counts(key, lambda: self.client.post(endpoint, json=request))
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual((0, 0, path.stat().st_size),
                (measured['rootDecodes'], measured['metadataDecodes'], measured['metadataBytes']))
            rows.append({'operation': endpoint + '_warm', **measured})
        if os.environ.get('CONSENSE_WARM304_EVIDENCE'):
            evidence = Path(os.environ['CONSENSE_WARM304_EVIDENCE']).with_name('integrated-call-path-work.json')
            evidence.write_text(json.dumps({'authoredOnly': True, 'metadataBytes': path.stat().st_size,
                'rows': rows, 'liveAcceptance': False}, indent=2), encoding='utf-8')

    def test_cold_query_decodes_one_alias_and_one_metadata_tree(self):
        _, key, _, _ = self.indexed()
        self.backend._projects.pop(key, None)
        self.registry._derived_results.clear()
        self.registry._derived_bytes = 0
        response, measured = self.counts(key, lambda: self.client.post('/retrieve', json={
            'projectId': key, 'query': 'Visible', 'candidates': 4, 'limit': 2}))
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(1, measured['rootDecodes'])
        self.assertEqual(1, measured['metadataDecodes'])
        self.assertEqual(2 * self.backend.metadata_path(key).stat().st_size, measured['metadataBytes'])

    def test_metadata_same_stamp_mutation_is_rejected_by_single_final_hash(self):
        _, key, body, signature = self.indexed()
        self.assertEqual(200, self.client.post('/index-canonical/status', json=body).status_code)
        path = self.backend.metadata_path(key)
        raw, stat = path.read_bytes(), path.stat()
        changed = raw.replace(b'Visible row A', b'changed row A')
        self.assertEqual(len(raw), len(changed)); self.assertNotEqual(raw, changed)
        real_stat = Path.stat
        def pinned_stamp(current, *args, **kwargs):
            return stat if current == path else real_stat(current, *args, **kwargs)
        path.write_bytes(changed)
        os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        endpoint = '/index-canonical/availability' if self.module == 'advisory' else '/index-canonical/status'
        request = {**body, 'expectedSignature': signature} if self.module == 'advisory' else body
        try:
            with patch.object(Path, 'stat', pinned_stamp):
                response, measured = self.counts(key, lambda: self.client.post(endpoint, json=request))
            self.assertNotEqual(200, response.status_code, response.text)
            self.assertEqual(0, measured['metadataDecodes'])
            self.assertEqual(len(raw), measured['metadataBytes'])
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))

    def test_parent_mutation_after_initial_native_proof_cannot_escape(self):
        _, key, body, _ = self.indexed()
        self.assertEqual(200, self.client.post('/index-canonical/status', json=body).status_code)
        original = self.rt._validate_native_sources
        changed = []
        def mutate(chunks):
            original(chunks)
            if not changed:
                changed.append(chunks[0]['content'])
                chunks[0]['content'] += ' AUTHORED late mutation'
        with patch.object(self.rt, '_validate_native_sources', mutate):
            response = self.client.post('/index-canonical/status', json=body)
        self.assertNotEqual(200, response.status_code, response.text)
        self.assertTrue(changed)

    def test_releasing_cas_bodies_retains_actual_exit_rehash(self):
        descriptor = self.store.put_bytes(b'authored consumed CAS body')
        path = self.store._path(descriptor)
        with self.assertRaises(ArtifactStoreError):
            with self.store.validation_session():
                self.assertEqual(b'authored consumed CAS body', self.store.read_asset(descriptor))
                self.store.release_bytes([descriptor])
                cache = self.store._read_session.get()
                self.assertEqual(1, len(cache))
                self.assertIsNone(next(iter(cache.values()))[1])
                path.write_bytes(b'authored changed CAS bytes')

    def test_catalog_decodes_no_warm_normal_graph_and_ids_load_only_selected_document(self):
        sources = []
        for number in range(3):
            body = copy.deepcopy(self.body)
            body['candidateGraph']['source']['documentId'] = str(910 + number)
            source = self.publish(body)
            self.assertEqual(200, self.client.get(self.url(source)).status_code)
            sources.append(source)
        request = {'module': self.module, 'projectionSha256': [source['projectionSha256'] for source in sources],
            'operation': 'catalog', 'view': 'canonical_units'}
        with patch.object(self.registry, 'load', side_effect=AssertionError('Warm catalog loaded a full source')):
            catalog = self.client.post('/sources/read', json=request)
        self.assertEqual(200, catalog.status_code, catalog.text)
        self.assertEqual(6, catalog.json()['coverage']['totalOrigins'])
        selected = sources[-1]['chunks'][0]['id']
        original = self.registry.load
        with patch.object(self.registry, 'load', wraps=original) as load:
            response = self.client.post('/sources/read', json={**request, 'operation': 'ids', 'originIds': [selected]})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual([sources[-1]['projectionSha256']], [call.args[0] for call in load.call_args_list])
        expected = SourceUnits(sources[-1]['chunks'])
        self.assertEqual([expected.resolve(expected.parents[selected])], response.json()['units'])

    def test_interleaved_ids_preserve_order_without_reopening_a_selected_document(self):
        sources = []
        for number in range(2):
            body = copy.deepcopy(self.body)
            body['candidateGraph']['source']['documentId'] = str(920 + number)
            source = self.publish(body)
            self.assertEqual(200, self.client.get(self.url(source)).status_code)
            sources.append(source)
        selected = [sources[0]['chunks'][0]['id'], sources[1]['chunks'][0]['id'], sources[0]['chunks'][1]['id']]
        with patch.object(self.registry, 'load', wraps=self.registry.load) as load:
            response = self.client.post('/sources/read', json={'module': self.module,
                'projectionSha256': [source['projectionSha256'] for source in sources],
                'operation': 'ids', 'view': 'canonical_units', 'originIds': selected})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(selected, [origin['id'] for origin in response.json()['origins']])
        self.assertEqual([source['projectionSha256'] for source in sources], [call.args[0] for call in load.call_args_list])

    def test_dictionary_preparation_releases_each_persisted_member(self):
        import source_read_server
        class Tracked(dict):
            pass
        body = many_unit_fixture(self.policy, 8)
        body['sourceBinding']['module'] = self.module
        source = self.publish(body)
        original = source_read_server.semantic_member
        references, observed = [], []
        def project(*args, **kwargs):
            observed.append(sum(reference() is not None for reference in references))
            value = Tracked(original(*args, **kwargs))
            references.append(weakref.ref(value))
            return value
        with patch.object(source_read_server, 'semantic_member', project):
            response = self.client.post('/sources/read', json={'module': self.module,
                'projectionSha256': [source['projectionSha256']], 'operation': 'prepare',
                'view': 'semantic_units', 'semanticReadVersion': 'source-semantic-dictionary-v2'})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(8, len(references))
        self.assertEqual(0, max(observed))
        self.assertTrue(all(reference() is None for reference in references))

    def test_split_document_addresses_block_complete_unit_reads_explicitly(self):
        sources = []
        for number in range(2):
            body = copy.deepcopy(self.body)
            body['candidateGraph']['generationSha256'] = hashlib.sha256(str(number).encode()).hexdigest()
            body['candidateGraph']['source']['parsedSourceHash'] = hashlib.sha256(('parsed-' + str(number)).encode()).hexdigest()
            source = self.publish(body)
            self.assertEqual(200, self.client.get(self.url(source)).status_code)
            sources.append(source)
        response = self.client.post('/sources/read', json={'module': self.module,
            'projectionSha256': [source['projectionSha256'] for source in sources],
            'operation': 'ids', 'view': 'canonical_units', 'originIds': [sources[0]['chunks'][0]['id']]})
        self.assertEqual(422, response.status_code, response.text)
        self.assertIn('SOURCE_READ_AMBIGUOUS_DOCUMENT', response.text)

    def test_heavy_publication_and_read_journals_keep_refs_without_source_bodies(self):
        marker = 'AUTHORED_UNUSED_LAYOUT_' + 'x' * 4096
        self.body['candidateGraph']['units'][0]['parts'][0]['readingContext']['authoredUnusedLayout'] = marker
        source = self.publish()
        response = self.client.post('/sources/read', json={'module': self.module,
            'projectionSha256': [source['projectionSha256']], 'operation': 'ids',
            'view': 'canonical_units', 'originIds': [source['chunks'][0]['id']]})
        self.assertEqual(200, response.status_code, response.text)
        self.assertIn(marker, response.text)
        journal = self.app.state.request_evidence
        for path in journal.root.glob('*/*.json*'):
            self.assertNotIn(marker, path.read_text(encoding='utf-8'), str(path))
        original = self.store.read_asset(source['observationManifestRef'])
        self.assertIn(marker.encode(), original)
        self.assertIn(source['projectionSha256'], next(journal.root.glob('*/response.json')).read_text())
