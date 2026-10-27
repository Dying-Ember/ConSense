"""Broad authored text-scan preparation counts and exact full-evidence fallback."""
import copy
import hashlib
import json
import os
import time
import unittest
from collections import Counter
from pathlib import Path
from unittest.mock import patch

import source_read_server as reader
from normal_observed_projection import produce_normal_observed
from test_normal_observed import many_unit_fixture
from test_compact_normal_preparation import CompactNormalPreparationTests as Base
from test_source_read_semantic import SemanticSourceReadTests
from oer_source_registry import _serialize


class CompactTextScanTests(unittest.TestCase):
    setUp = Base.setUp
    tearDown = Base.tearDown
    read = Base.read
    prepare = Base.prepare
    normal_sources = Base.normal_sources
    normal = SemanticSourceReadTests.normal

    def prepared(self, profile='text-first-scan-v1'):
        return self.binding(self.read('prepare', view='semantic_units',
            semanticReadVersion='source-semantic-dictionary-v2', semanticReadProfile=profile))

    def ids(self, binding, identities, **changes):
        return self.read('ids', view='semantic_units', originIds=identities,
            **{**binding, 'maxBytes': 16 * 1024 * 1024, **changes})

    def manifest(self, binding):
        address = binding['readManifestSha256'][0]
        return address, json.loads((self.store.root / 'sha256' / address[:2] / (address + '.blob')).read_bytes())

    def test_complex_normal_scan_is_exact_old_text_projection_and_ids_are_full(self):
        source = self.normal()
        identities = [parent['id'] for parent in source['chunks']]
        full = self.ids(self.prepared('full'), identities)
        self.assertEqual(200, full.status_code, full.text)
        compact = self.prepared()
        scan = self.read('scan', view='semantic_units', maxItems=1024, maxBytes=16777216, **compact)
        self.assertEqual(200, scan.status_code, scan.text)
        expected = [reader._text_first_origin(origin) for origin in full.json()['origins']]
        self.assertEqual(expected, scan.json()['origins'])
        self.assertEqual(full.json()['units'], scan.json()['units'])
        result = self.ids(compact, identities)
        self.assertEqual(200, result.status_code, result.text)
        self.assertEqual(full.json()['origins'], result.json()['origins'])
        self.assertEqual(full.json()['units'], result.json()['units'])
        self.assertIn('sourceContext', result.json()['origins'][0])
        self.assertIn('sourceQuality', result.json()['origins'][0])
        self.assertIn('unknowns', result.json()['origins'][0])
        self.assertEqual('(iii)', expected[0]['parts'][0]['readingContext']['nativeDocxParagraph']['numbering']['label'])
        self.assertEqual(['unless approved'], expected[0]['tableGroup']['conditions'])
        self.assertEqual(['except for Class B'], expected[0]['tableGroup']['exceptions'])
        self.assertEqual('cross-page-1', expected[0]['tableGroup']['continuationGroupId'])

    def test_interleaved_ids_load_each_document_once_and_project_only_requested_members(self):
        sources = self.normal_sources(2)
        identities = [sources[1]['chunks'][1]['id'], sources[0]['chunks'][0]['id'], sources[1]['chunks'][0]['id']]
        full = self.ids(self.prepared('full'), identities)
        self.assertEqual(200, full.status_code, full.text)
        compact = self.prepared()
        load, member, context = self.registry.load, reader.semantic_member, reader._source_unknowns
        loads, projections, contexts = [], [], []
        observation_hashes = {source['projectionSha256']: source['observationManifestRef']['sha256'] for source in sources}
        def observed_load(address):
            prepared_raw = [raw for (descriptor, raw) in self.store._read_session.get().values()
                            if descriptor['identityScope'] == 'prepared_source_semantic_read']
            self.assertTrue(prepared_raw)
            self.assertTrue(all(raw is None for raw in prepared_raw), 'Parsed prepared manifest raw bodies must be released')
            if loads:
                previous = observation_hashes[loads[-1]]
                retained = [raw for (path, sha, size), (descriptor, raw) in self.store._read_session.get().items()
                            if sha == previous]
                self.assertEqual([None], retained, 'Previous document CAS body must be released before next load')
            loads.append(address)
            return load(address)
        def observed_member(parent, address):
            projections.append(parent['id'])
            return member(parent, address)
        def observed_context(snapshot, parent):
            contexts.append(parent['id'])
            return context(snapshot, parent)
        with patch.object(self.registry, 'load', observed_load), patch.object(reader, 'semantic_member', observed_member), \
                patch.object(reader, '_source_unknowns', observed_context):
            result = self.ids(compact, identities)
        self.assertEqual(200, result.status_code, result.text)
        self.assertEqual(identities, [origin['id'] for origin in result.json()['origins']])
        self.assertEqual(full.json()['origins'], result.json()['origins'])
        self.assertEqual(full.json()['units'], result.json()['units'])
        self.assertEqual(Counter({source['projectionSha256']: 1 for source in sources}), Counter(loads))
        self.assertEqual(Counter(identities), Counter(projections))
        self.assertEqual(Counter(identities), Counter(contexts))

    def test_oversized_multi_id_enrichment_refuses_before_retaining_every_requested_member(self):
        sources = self.normal_sources(2)
        binding = self.prepared()
        identities = [parent['id'] for source in sources for parent in source['chunks']]
        self.assertGreaterEqual(len(identities), 3)
        member, projected = reader.semantic_member, []
        def oversized(parent, address):
            origin = member(parent, address)
            origin['unknowns'] = [{'reason': 'Authored oversized unknown ' + 'X' * 6000}]
            projected.append(parent['id'])
            return origin
        with patch.object(reader, 'semantic_member', oversized):
            result = self.ids(binding, identities, maxBytes=12000)
        self.assertEqual(422, result.status_code, result.text)
        self.assertEqual('SOURCE_READ_BUDGET_EXCEEDED', result.json()['detail']['kind'])
        self.assertLess(len(projected), len(identities))
        self.assertNotIn('origins', result.json())

    def test_private_v4_rejects_mixed_profile_source_descriptor_and_origin_bindings(self):
        self.normal_sources()
        binding = self.prepared()
        address, manifest = self.manifest(binding)
        self.assertEqual('consense.source-semantic-read-manifest.v4', manifest['schema'])
        read_asset = self.store.read_asset
        changes = {
            'legacy_schema_with_scan_ref': lambda m: m.update(schema='consense.source-semantic-read-manifest.v3'),
            'full_profile': lambda m: m.update(semanticReadProfile='full'),
            'native_pdf_source': lambda m: m['source'].update(producerKind='native_pdf'),
            'both_refs': lambda m: m['origins'][0].update(recordRef=m['origins'][0]['scanRecordRef']),
            'neither_ref': lambda m: m['origins'][0].pop('scanRecordRef'),
            'wrong_scope': lambda m: m['origins'][0]['scanRecordRef'].update(identityScope='source_semantic_member_v2'),
            'descriptor_extra': lambda m: m['origins'][0]['scanRecordRef'].update(unit={}),
            'inline_cas_mix': lambda m: m['origins'][0]['scanRecordRef'].update(relativePath='sha256/outside.blob'),
            'inline_schema': lambda m: m['origins'][0]['scanRecordRef'].update(schema='consense.inline-semantic-group.v1'),
            'inline_size': lambda m: m['origins'][0]['scanRecordRef'].update(byteCount=True),
            'wrong_document': lambda m: m['origins'][0].update(documentId='outside-document'),
            'wrong_source': lambda m: m['origins'][0].update(sourceHash='f' * 64),
            'outside_group': lambda m: m['origins'][0].update(groupId='f' * 64),
            'outside_origin': lambda m: m['origins'][0].update(id='outside-origin'),
            'wrong_unit': lambda m: m['origins'][0].update(unitId='outside-unit')}
        for name, change in changes.items():
            with self.subTest(case=name):
                poisoned = copy.deepcopy(manifest)
                change(poisoned)
                def changed(descriptor):
                    return _serialize(poisoned) if descriptor['sha256'] == address else read_asset(descriptor)
                with patch.object(self.store, 'read_asset', changed):
                    result = self.ids(binding, [poisoned['origins'][0]['id']])
                self.assertEqual(422, result.status_code, result.text)
                self.assertEqual('SOURCE_READ_PREPARATION_STALE', result.json()['detail']['kind'])

    def test_inline_and_cas_scan_members_preserve_exact_content_and_bounded_manifest_fallback(self):
        source = self.normal()
        identities = [parent['id'] for parent in source['chunks']]
        inline = self.prepared()
        _, manifest = self.manifest(inline)
        self.assertEqual({'inline_prepared_manifest'}, {entry['scanRecordRef']['storage'] for entry in manifest['origins']})
        expected = self.read('scan', view='semantic_units', maxBytes=16777216, **inline)
        self.assertEqual(200, expected.status_code, expected.text)
        with patch.object(reader, 'INLINE_GROUP_BYTES', 0):
            fallback = self.binding(self.prepare())
        _, cas_manifest = self.manifest(fallback)
        self.assertEqual({'content_addressed_disk'}, {entry['scanRecordRef']['storage'] for entry in cas_manifest['origins']})
        actual = self.read('scan', view='semantic_units', maxBytes=16777216, **fallback)
        self.assertEqual(200, actual.status_code, actual.text)
        for field in ('sources', 'origins', 'units', 'originUnits', 'coverage'):
            self.assertEqual(expected.json()[field], actual.json()[field])
        self.assertEqual(self.ids(inline, identities).json()['origins'], self.ids(fallback, identities).json()['origins'])
        # Combined inline bodies/groups can exceed the allowance even when each body fits.
        fallback_bound = len(_serialize(cas_manifest))
        self.assertGreater(len(_serialize(manifest)), fallback_bound)
        # A new app has no prepared-address cache and still uses the same authored CAS.
        from fastapi.testclient import TestClient
        from oer_unified_server import create_unified_app
        self.client.close()
        self.app = create_unified_app(self.policy, backend=self.backend)
        self.client = TestClient(self.app)
        self.store, self.registry = self.app.state.artifact_store, self.app.state.source_registry
        with patch.object(reader, 'MAX_BYTES', fallback_bound):
            bounded = self.prepared()
        address, bounded_manifest = self.manifest(bounded)
        self.assertLessEqual(len(_serialize(bounded_manifest)), fallback_bound)
        self.assertEqual({'content_addressed_disk'}, {entry['scanRecordRef']['storage'] for entry in bounded_manifest['origins']})
        self.assertEqual(expected.json()['origins'], self.read('scan', view='semantic_units', maxBytes=16777216, **bounded).json()['origins'])

    def test_selected_inline_member_hash_and_size_are_verified_without_cas_body_reads(self):
        self.normal_sources()
        binding = self.prepared()
        address, manifest = self.manifest(binding)
        read_asset = self.store.read_asset
        for field in ('sha256', 'byteCount', 'origin'):
            with self.subTest(field=field):
                changed = copy.deepcopy(manifest)
                descriptor = changed['origins'][0]['scanRecordRef']
                if field == 'sha256':
                    descriptor[field] = 'f' * 64
                elif field == 'byteCount':
                    descriptor[field] += 1
                else:
                    descriptor[field]['text'] += ' Authored tamper'
                def poisoned(reference):
                    return _serialize(changed) if reference['sha256'] == address else read_asset(reference)
                with patch.object(self.store, 'read_asset', poisoned):
                    result = self.read('scan', view='semantic_units', **binding)
                self.assertEqual(422, result.status_code, result.text)
                self.assertEqual('SOURCE_READ_PREPARATION_STALE', result.json()['detail']['kind'])

    def test_scope_wide_inline_scan_allowance_falls_back_across_documents(self):
        sources = self.normal_sources(2)
        bound = sum(len(_serialize(reader.semantic_scan_member(parent, sources[0]['projectionSha256'])))
                    for parent in sources[0]['chunks'])
        with patch.object(reader, 'INLINE_GROUP_BYTES', bound), patch.object(reader, 'INLINE_SCAN_SCOPE_BYTES', bound):
            binding = self.prepared()
        inline_bytes, storage = 0, []
        for address in binding['readManifestSha256']:
            manifest = json.loads((self.store.root / 'sha256' / address[:2] / (address + '.blob')).read_bytes())
            storage.append({entry['scanRecordRef']['storage'] for entry in manifest['origins']})
            inline_bytes += sum(entry['scanRecordRef']['byteCount'] for entry in manifest['origins']
                                if entry['scanRecordRef']['storage'] == 'inline_prepared_manifest')
        self.assertLessEqual(inline_bytes, bound)
        self.assertEqual([{'inline_prepared_manifest'}, {'content_addressed_disk'}], storage)
        scan = self.read('scan', view='semantic_units', maxBytes=16777216, **binding)
        self.assertEqual(200, scan.status_code, scan.text)
        self.assertEqual([parent['id'] for source in sources for parent in source['chunks']],
                         [origin['id'] for origin in scan.json()['origins']])

    def test_compact_scan_and_full_ids_keep_root_and_cas_entry_exit_guards(self):
        source = self.normal_sources()[0]
        binding = self.prepared()
        root = self.registry._path(source['projectionSha256'])
        cas = self.store.root / source['observationManifestRef']['relativePath']
        for path in (root, cas):
            original, stamp = path.read_bytes(), path.stat()
            try:
                path.write_bytes(bytes([original[0] ^ 1]) + original[1:])
                os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                result = self.ids(binding, [source['chunks'][0]['id']])
                self.assertEqual(422, result.status_code, result.text)
            finally:
                path.write_bytes(original)
                os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        original, stamp = root.read_bytes(), root.stat()
        observation = source['observationManifestRef']['sha256']
        read_uncached = self.store._read_uncached
        for operation in ('scan', 'ids'):
            reads = []
            def late(descriptor, *, retain):
                if descriptor['sha256'] == observation and not retain:
                    reads.append(retain)
                    if len(reads) == 2:
                        root.write_bytes(bytes([original[0] ^ 1]) + original[1:])
                        os.utime(root, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                return read_uncached(descriptor, retain=retain)
            try:
                with patch.object(self.store, '_read_uncached', late):
                    result = (self.ids(binding, [source['chunks'][0]['id']]) if operation == 'ids' else
                        self.read('scan', view='semantic_units', **binding))
                self.assertEqual([False, False], reads)
                self.assertEqual(422, result.status_code, result.text)
                self.assertEqual('CANONICAL_FRAME_SOURCE_CHANGED', result.json()['detail']['kind'])
            finally:
                root.write_bytes(original)
                os.utime(root, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))

    def broad_sources(self):
        sources = []
        for document in range(33):
            body = many_unit_fixture(self.policy, 32)
            graph = body['candidateGraph']
            original = hashlib.sha256(('AUTHORED compact original ' + str(document)).encode()).hexdigest()
            graph['source'].update(documentId=str(3000 + document), originalSourceSha256=original,
                filename='AUTHORED English source ' + str(document) + '.docx')
            for ordinal, unit in enumerate(graph['units']):
                text = 'Authored clause ' + str(document) + '-' + str(ordinal) + ': retain unless approved; except Class B.'
                unit['text'] = text
                for part in unit['parts']:
                    part.update(sourceSha256=original, text=text, endOffset=len(text))
                for span in unit['sourceSpans']:
                    span.update(sourceSha256=original, endOffset=len(text))
            source = produce_normal_observed(graph, body['sourceBinding'], artifact_store=self.store)
            self.registry.publish(source)
            sources.append(source)
        self.sources = sources
        return sources

    def binding(self, response):
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        return {**{key: result[key] for key in (
            'readManifestSha256', 'readManifestToken', 'semanticReadVersion')},
            'semanticReadProfile': result.get('semanticReadProfile', 'full')}

    def test_complete_33_source_text_scan_defers_full_member_work(self):
        sources = self.broad_sources()
        expected = [chunk['id'] for source in sources for chunk in source['chunks']]
        self.assertEqual(1056, len(expected))
        counts, timings = Counter(), {}
        phase = 'cold_prepare'
        original_member, original_context = reader.semantic_member, reader._source_unknowns
        original_put, original_read = self.store.put_bytes, self.store._read_uncached
        original_stream, original_file = self.registry._stream_file_identity, self.registry._read_frame_file
        roots = {self.registry._path(source['projectionSha256']).resolve() for source in sources}

        def member(*args, **kwargs):
            counts[(phase, 'full_member_projection')] += 1
            return original_member(*args, **kwargs)

        def context(*args, **kwargs):
            counts[(phase, 'full_source_context')] += 1
            return original_context(*args, **kwargs)

        def put(raw, *, identity_scope='exact_supplied_bytes'):
            counts[(phase, 'cas_put_total')] += 1
            counts[(phase, 'cas_put_bytes_total')] += len(raw)
            counts[(phase, 'put:' + identity_scope)] += 1
            counts[(phase, 'put_bytes:' + identity_scope)] += len(raw)
            return original_put(raw, identity_scope=identity_scope)

        def read(descriptor, *, retain):
            counts[(phase, 'cas_actual_open_total')] += 1
            counts[(phase, 'cas_actual_read_bytes_total')] += descriptor['byteCount']
            counts[(phase, 'read:' + descriptor['identityScope'] + ':' + str(retain))] += 1
            return original_read(descriptor, retain=retain)

        def stream(path, *, maximum):
            actual = original_stream(path, maximum=maximum)
            kind = 'published_root_stream' if path.resolve() in roots else 'alias_stream'
            counts[(phase, kind + '_opens')] += 1
            counts[(phase, kind + '_bytes')] += actual[1]
            return actual

        def file_read(path, *, normal=False):
            raw = original_file(path, normal=normal)
            kind = 'published_root_retained' if path.resolve() in roots else 'alias_retained'
            counts[(phase, kind + '_opens')] += 1
            counts[(phase, kind + '_bytes')] += len(raw)
            return raw

        with patch.object(reader, 'semantic_member', member), patch.object(reader, '_source_unknowns', context), \
                patch.object(self.store, 'put_bytes', put), patch.object(self.store, '_read_uncached', read), \
                patch.object(self.registry, '_stream_file_identity', stream), \
                patch.object(self.registry, '_read_frame_file', file_read), \
                patch.object(self.rt, 'tokenizer', side_effect=AssertionError('Model calls forbidden')):
            for temperature in ('cold', 'warm'):
                phase = temperature + '_prepare'
                started = time.perf_counter()
                prepared = self.prepare(maxBytes=16 * 1024 * 1024)
                binding = self.binding(prepared)
                timings[phase + 'Seconds'] = time.perf_counter() - started
                self.assertEqual(temperature == 'cold', prepared.json()['coldPreparation']['performed'])
                self.assertEqual(1056, prepared.json()['coverage']['totalOrigins'])
                phase = temperature + '_scan'
                started = time.perf_counter()
                request = {'module': 'vetting', 'projectionSha256': [s['projectionSha256'] for s in sources],
                    'operation': 'scan', 'view': 'semantic_units', 'maxItems': 1024,
                    'maxBytes': 16 * 1024 * 1024, **binding}
                response = self.client.post('/sources/read', json=request, headers={'Accept': 'application/x-ndjson'})
                self.assertEqual(200, response.status_code, response.text)
                frames = [json.loads(line) for line in response.content.splitlines()]
                self.assertEqual('complete', frames[-1]['type'])
                self.assertTrue(frames[-1]['proofComplete'])
                origins = [origin for frame in frames if frame['type'] == 'page' for origin in frame['data']['origins']]
                self.assertEqual(expected, [origin['id'] for origin in origins])
                self.assertTrue(all('unless approved; except Class B.' in origin['text'] for origin in origins))
                timings[phase + 'Seconds'] = time.perf_counter() - started
                self.assertEqual(66, counts[(phase, 'published_root_stream_opens')])
        metrics = {'schema': 'consense.compact-text-scan-authored-work.v1', 'authoredSources': 33,
            'authoredOrigins': 1056, 'completeOrderAndExitProof': True,
            'exactSealedPredecessorBaseline': os.environ.get('CONSENSE_COMPACT_TEXT_BASELINE') == '1',
            'counts': [{'phase': p, 'operation': operation, 'count': value}
                for (p, operation), value in sorted(counts.items())],
            'timingAuthoredOnlyNotRealSourceBenchmark': timings,
            'realModelCalls': 0, 'liveBusinessCalls': 0, 'operationalStateRead': False}
        target = os.environ.get('CONSENSE_COMPACT_TEXT_METRICS')
        if target:
            target = Path(target).resolve()
            target.write_text(json.dumps(metrics, indent=2) + '\n', encoding='utf-8')
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)
        baseline = os.environ.get('CONSENSE_COMPACT_TEXT_BASELINE') == '1'
        self.assertEqual(1056 if baseline else 0, counts[('cold_prepare', 'full_member_projection')],
            'Text-first prepare must not project a full member for every origin')
        self.assertEqual(1056 if baseline else 0, counts[('cold_prepare', 'full_source_context')])
        self.assertEqual(1056 if baseline else 0, counts[('cold_prepare', 'put:source_semantic_member_v2')])
        self.assertEqual(0, counts[('cold_prepare', 'put:source_semantic_text_scan_v1')])
        self.assertEqual(1089 if baseline else 33, counts[('cold_prepare', 'cas_put_total')])
        self.assertEqual(2244 if baseline else 132, counts[('cold_scan', 'cas_actual_open_total')])
        self.assertEqual(2244 if baseline else 132, counts[('warm_scan', 'cas_actual_open_total')])
        self.assertEqual(0, counts[('warm_prepare', 'cas_put_total')])


if __name__ == '__main__':
    unittest.main()
