"""Public compact reads over authored actual CAS, without models or a server."""
import json
import os
import unittest
from unittest.mock import patch

from normal_observed_projection import produce_normal_observed
from retrieval_source_units import SourceUnits
from test_normal_observed import fixture
import test_source_read_server as legacy


class SemanticSourceReadTests(unittest.TestCase):
    setUp = legacy.SourceReadHttpTests.setUp
    tearDown = legacy.SourceReadHttpTests.tearDown
    read = legacy.SourceReadHttpTests.read
    publish = legacy.SourceReadHttpTests.publish
    publish_pdf = legacy.SourceReadHttpTests.publish_pdf
    publish_all = legacy.SourceReadHttpTests.publish_all
    def prepare_dictionary(self):
        response = self.read('prepare', view='semantic_units', semanticReadVersion='source-semantic-dictionary-v2')
        self.assertEqual(200, response.status_code, response.text)
        return {key: response.json()[key] for key in ('readManifestSha256', 'readManifestToken', 'semanticReadVersion')}

    def prepare_text_first(self):
        response = self.read('prepare', view='semantic_units', semanticReadVersion='source-semantic-dictionary-v2',
            semanticReadProfile='text-first-scan-v1')
        self.assertEqual(200, response.status_code, response.text)
        return {key: response.json()[key] for key in
            ('readManifestSha256', 'readManifestToken', 'semanticReadVersion', 'semanticReadProfile')}

    def test_text_first_scan_keeps_complete_reading_text_and_full_ids_are_available(self):
        parents = self.normal()['chunks']
        prepared = self.prepare_text_first()
        scan = self.read('scan', view='semantic_units', **prepared, maxItems=1024)
        self.assertEqual(200, scan.status_code, scan.text)
        body = scan.json()
        self.assertEqual([p['id'] for p in parents], [p['id'] for p in body['origins']])
        self.assertEqual([p['content'] for p in parents], [p['text'] for p in body['origins']])
        self.assertEqual('text-first-scan-v1', body['scopeIdentity']['semanticReadProfile'])
        first = body['origins'][0]
        self.assertNotIn('sourceContext', first)
        self.assertNotIn('unknowns', first)
        self.assertNotIn('sourceQuality', first)
        self.assertIn('tableGroup', first)
        self.assertEqual(parents[0]['parts'][0]['text'], first['parts'][0]['text'])
        full = self.read('ids', view='semantic_units', **prepared, originIds=[first['id']])
        self.assertEqual(200, full.status_code, full.text)
        self.assertEqual(first['text'], full.json()['origins'][0]['text'])
        self.assertIn('sourceContext', full.json()['origins'][0])
        self.assertIn('sourceQuality', full.json()['origins'][0])
        self.assertLess(len(scan.content), len(full.content) * len(parents))

    def test_text_first_profile_is_bound_to_preparation_and_cursor(self):
        self.publish()
        thin = self.prepare_text_first()
        full = self.prepare_dictionary()
        first = self.read('scan', view='semantic_units', **thin, maxItems=1)
        self.assertEqual(200, first.status_code, first.text)
        self.assertIsNotNone(first.json()['nextCursor'])
        mismatched = self.read('scan', view='semantic_units', **full, maxItems=1,
            cursor=first.json()['nextCursor'])
        self.assertEqual(422, mismatched.status_code)
        self.assertEqual('SOURCE_READ_CURSOR_SCOPE_MISMATCH', mismatched.json()['detail']['kind'])
        changed_profile = self.read('scan', view='semantic_units',
            **{**thin, 'semanticReadProfile': 'full'}, maxItems=1)
        self.assertEqual(422, changed_profile.status_code)

    def test_text_first_retains_uncovered_native_pdf_table_text(self):
        from source_read_server import _text_first_origin
        origin = {'id': 'one', 'producerKind': 'native_pdf', 'text': 'Visible text',
            'nativeTables': [{'fragments': [{'cells': [{'text': 'Hidden text'}]}]}],
            'sourceContext': {'unknowns': []}}
        self.assertIn('nativeTables', _text_first_origin(origin))
        origin['nativeTables'][0]['fragments'][0]['cells'][0]['text'] = 'Visible text'
        self.assertNotIn('nativeTables', _text_first_origin(origin))
        origin['nativeTables'][0]['fragments'][0]['cells'][0]['text'] = ''
        self.assertIn('nativeTables', _text_first_origin(origin))
        origin['nativeTables'][0]['fragments'][0]['cells'] = []
        self.assertIn('nativeTables', _text_first_origin(origin))

    def test_dictionary_cas_and_scan_share_groups_without_repeating_member_bodies(self):
        parents = self.publish()
        prepared = self.prepare_dictionary()
        saved_members, saved_groups = [], {}
        for address in prepared['readManifestSha256']:
            manifest = json.loads((self.store.root / 'sha256' / address[:2] / (address + '.blob')).read_bytes())
            self.assertEqual('consense.source-semantic-read-manifest.v3', manifest['schema'])
            saved_members.extend(manifest['origins'])
            saved_groups.update(manifest['groups'])
            for entry in manifest['origins']:
                stored = json.loads(self.store.read_asset(entry['recordRef']))
                self.assertEqual(entry['id'], stored['id'])
                self.assertNotIn('unit', stored)
        self.assertEqual(len(parents), len(saved_members))
        self.assertLess(len(saved_groups), len(saved_members))
        groups, origins, cursor = {}, [], None
        for _ in range(len(parents) + 1):
            response = self.read('scan', view='semantic_units', **prepared, cursor=cursor,
                maxItems=1, knownSemanticGroups=list(groups))
            self.assertEqual(200, response.status_code, response.text)
            result = response.json()
            for row in result['units']:
                self.assertNotIn(row['groupId'], groups)
                groups[row['groupId']] = row['unit']
                self.assertNotIn('members', row['unit'])
                self.assertNotIn('originIds', row['unit'])
            origins.extend(result['origins'])
            self.assertTrue(set(result['originUnits'].values()) <= groups.keys())
            cursor = result['nextCursor']
            if cursor is None:
                break
        self.assertEqual([p['id'] for p in parents], [p['id'] for p in origins])
        self.assertLess(len(groups), len(origins))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_dictionary_resume_without_known_groups_retransmits_the_needed_template(self):
        self.publish()
        prepared = self.prepare_dictionary()
        first = self.read('scan', view='semantic_units', **prepared, maxItems=1).json()
        resumed = self.read('scan', view='semantic_units', **prepared, maxItems=1, cursor=first['nextCursor'])
        self.assertEqual(200, resumed.status_code, resumed.text)
        self.assertEqual(1, len(resumed.json()['units']))
        forged = self.read('scan', view='semantic_units', **prepared, maxItems=1, knownSemanticGroups=['0' * 64])
        self.assertEqual(422, forged.status_code)

    def test_dictionary_scan_uses_1024_only_for_versioned_scan_and_exact_byte_budget(self):
        self.publish()
        prepared = self.prepare_dictionary()
        complete = self.read('scan', view='semantic_units', **prepared, maxItems=1024)
        self.assertEqual(200, complete.status_code, complete.text)
        self.assertIsNone(complete.json()['nextCursor'])
        budget = len(complete.content) - 1
        split = self.read('scan', view='semantic_units', **prepared, maxItems=1024, maxBytes=budget)
        self.assertEqual(200, split.status_code, split.text)
        self.assertLessEqual(len(split.content), budget)
        self.assertIsNotNone(split.json()['nextCursor'])
        exact = self.read('scan', view='semantic_units', **prepared, maxItems=1024,
            maxBytes=len(complete.content))
        self.assertEqual(200, exact.status_code, exact.text)
        self.assertEqual(complete.json(), exact.json())
        for changes in ({'view': 'semantic_units', 'semanticReadVersion': 'source-semantic-records-v1'},
                        {'view': 'semantic_units', **prepared, 'operation': 'catalog'},
                        {'view': 'semantic_units', **prepared, 'operation': 'ids', 'originIds': ['member']},
                        {'view': 'canonical_units'}):
            response = self.read(maxItems=1024, **changes)
            self.assertEqual(422, response.status_code, response.text)

    def test_dictionary_small_budget_records_blocked_origins_and_advances_without_truncation(self):
        self.normal()
        prepared = self.prepare_dictionary()
        result = self.read('scan', view='semantic_units', **prepared, maxItems=1, maxBytes=9000)
        self.assertEqual(200, result.status_code, result.text)
        body = result.json()
        self.assertEqual([], body['origins'])
        self.assertEqual(1, len(body['blockedOrigins']))
        self.assertEqual(1, body['coverage']['processedOrigins'])
        self.assertIsNotNone(body['nextCursor'])
        resumed = self.read('scan', view='semantic_units', **prepared, cursor=body['nextCursor'], maxBytes=9000)
        self.assertEqual(200, resumed.status_code, resumed.text)
        self.assertTrue(resumed.json()['origins'])

    def normal(self):
        body = fixture(self.policy)
        first = body['candidateGraph']['units'][0]
        text = 'Do not use unless approved; except for Class B. 5 mm'
        part = first['parts'][0]
        part.update(text=text, endOffset=len(text))
        first['sourceSpans'][0]['endOffset'] = len(text)
        first['text'] = '\n'.join(p['text'] for p in first['parts'])
        first['tableGroup'].update(headerRows=[0, 1], units='mm', continuationGroupId='cross-page-1',
            conditions=['unless approved'], exceptions=['except for Class B'],
            references=[{'clauseRef': 'Clause 1 NOT USED'}],
            rows=[{'blockId': first['parts'][0]['blockId'], 'rowIndex': 0, 'cells': [{'columnIndex': 0, 'rowSpan': 2,
                'columnSpan': 2, 'text': text, 'blankState': 'observed_nonblank',
                'sourcePosition': {'nodePath': 'SENSITIVE_NATIVE_LAYOUT'},
                'bbox': [1, 2, 3, 4]}]}, {'blockId': first['parts'][1]['blockId'], 'rowIndex': 1, 'cells': [
                {'columnIndex': 2, 'text': '', 'blankState': 'observed_blank'}]}])
        for member in first['parts']:
            member['readingContext']['nativeDocxParagraph'] = {
                'sourcePosition': {'nodePath': 'SENSITIVE_NATIVE_LAYOUT'},
                'layoutEvidence': {'raw': 'SENSITIVE_NATIVE_LAYOUT' * 1000,
                    'numbering': {'numbered': True, 'label': '(iii)', 'level': 2,
                        'resolutionStatus': 'observed', 'format': 'lowerRoman'}}}
        source = produce_normal_observed(body['candidateGraph'], body['sourceBinding'], artifact_store=self.store)
        self.registry.publish(source)
        self.sources = [source]
        return source

    def prepare(self):
        response = self.read('prepare', view='semantic_units')
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        return {key: result[key] for key in ('readManifestSha256', 'readManifestToken')}

    def test_semantic_prepare_then_reads_never_reload_full_graph_and_keep_meaning(self):
        source = self.normal()
        manifests = self.prepare()
        with patch.object(self.registry, 'load', side_effect=AssertionError('full graph reload')), \
                patch.object(SourceUnits, '__init__', side_effect=AssertionError('full units rebuild')):
            catalog = self.read(view='semantic_units', **manifests)
            response = self.read('ids', view='semantic_units', **manifests,
                originIds=[source['chunks'][0]['id']])
        self.assertEqual(200, catalog.status_code, catalog.text)
        self.assertEqual([], catalog.json()['origins'])
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual('source-semantic-units-v1', result['semanticProjectionVersion'])
        origin = result['origins'][0]
        self.assertEqual(source['chunks'][0]['content'], origin['text'])
        self.assertEqual('mm', origin['tableGroup']['units'])
        self.assertEqual([0, 1], origin['tableGroup']['headerRows'])
        self.assertEqual(2, origin['tableGroup']['rows'][0]['cells'][0]['rowSpan'])
        self.assertEqual('observed_blank', origin['tableGroup']['rows'][1]['cells'][0]['blankState'])
        self.assertEqual(['unless approved'], origin['tableGroup']['conditions'])
        self.assertEqual(['except for Class B'], origin['tableGroup']['exceptions'])
        self.assertEqual([{'clauseRef': 'Clause 1 NOT USED'}], origin['tableGroup']['references'])
        self.assertEqual('cross-page-1', origin['tableGroup']['continuationGroupId'])
        self.assertEqual('candidate_unverified', origin['adoptionStatus'])
        self.assertEqual('unknown', result['units'][0]['qualifiersComplete'])
        self.assertFalse(origin['humanConfirmed'])
        self.assertIn('evidenceRef', origin)
        self.assertEqual(source['projectionSha256'], origin['evidenceRef']['projectionSha256'])
        self.assertEqual('(iii)', origin['parts'][0]['readingContext']['nativeDocxParagraph']['numbering']['label'])
        self.assertEqual('not_source_reviewed', origin['sourceContext']['unknowns'][0]['reason'])
        for excluded in ('sourcePosition', 'bbox', 'SENSITIVE_NATIVE_LAYOUT', 'layoutEvidence', 'candidateGraph'):
            self.assertNotIn(excluded, response.text)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_unprepared_semantic_read_is_explicit_and_does_not_fallback(self):
        self.normal()
        with patch.object(self.registry, 'load', side_effect=AssertionError('hidden legacy fallback')):
            response = self.read(view='semantic_units')
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_BOUNDED_UNAVAILABLE', response.json()['detail']['kind'])

    def test_repeat_prepare_reuses_only_derived_addresses_but_rechecks_actual_bytes(self):
        source = self.normal()
        prepared = self.prepare()
        with patch.object(self.registry, 'load', side_effect=AssertionError('repeat cold full graph')):
            response = self.read('prepare', view='semantic_units')
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual(prepared['readManifestSha256'], result['readManifestSha256'])
        self.assertFalse(result['coldPreparation']['performed'])
        descriptor = source['observationManifestRef']
        path = self.store.root / descriptor['relativePath']
        raw = path.read_bytes()
        try:
            path.write_bytes(raw + b' changed')
            with patch.object(self.registry, 'load', side_effect=AssertionError('invalid source must not reprepare')):
                response = self.read('prepare', view='semantic_units')
            self.assertEqual(422, response.status_code, response.text)
        finally:
            path.write_bytes(raw)

    def test_prepared_scan_preserves_all_source_order_and_ids_request_order(self):
        parents = self.publish()
        prepared = self.prepare()
        returned, cursor = [], None
        retained = []
        read_uncached = self.store._read_uncached
        def observed(descriptor, *, retain):
            if retain:
                retained.append(descriptor['identityScope'])
            return read_uncached(descriptor, retain=retain)
        with patch.object(self.registry, 'load', side_effect=AssertionError('full graph reload')), \
                patch.object(SourceUnits, '__init__', side_effect=AssertionError('full units rebuild')), \
                patch.object(self.store, '_read_uncached', observed):
            for _ in range(len(parents) + 1):
                response = self.read('scan', view='semantic_units', **prepared, cursor=cursor, maxItems=1)
                self.assertEqual(200, response.status_code, response.text)
                result = response.json()
                returned.extend(origin['id'] for origin in result['origins'])
                self.assertEqual(len(returned), result['coverage']['processedOrigins'])
                cursor = result['nextCursor']
                if cursor is None:
                    break
            requested = [parents[-1]['id'], parents[0]['id']]
            response = self.read('ids', view='semantic_units', **prepared, originIds=requested)
        self.assertEqual([parent['id'] for parent in parents], returned)
        self.assertEqual(len(returned), len(set(returned)))
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(requested, [origin['id'] for origin in response.json()['origins']])
        self.assertTrue(set(retained) <= {'prepared_source_semantic_read', 'complete_source_semantic_unit_with_closure'})
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_prepared_binding_cannot_be_forged_reordered_or_reused_as_canonical_cursor(self):
        self.publish()
        prepared = self.prepare()
        bad = dict(prepared, readManifestToken='0' * 64)
        response = self.read(view='semantic_units', **bad)
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_PREPARATION_STALE', response.json()['detail']['kind'])
        response = self.read(view='semantic_units', **prepared, sources=list(reversed(self.sources)))
        self.assertEqual(422, response.status_code, response.text)
        first = self.read('scan', view='semantic_units', **prepared, maxItems=1).json()
        response = self.read('scan', cursor=first['nextCursor'])
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_CURSOR_SCOPE_MISMATCH', response.json()['detail']['kind'])
        response = self.read(view='canonical_units', **prepared)
        self.assertEqual(422, response.status_code, response.text)

    def test_semantic_budget_never_splits_a_complete_unit_and_resumes(self):
        source = self.normal()
        prepared = self.prepare()
        catalog = self.read(view='semantic_units', **prepared)
        budget = len(catalog.content) + 1200
        blocked = self.read('scan', view='semantic_units', **prepared, maxBytes=budget)
        self.assertEqual(200, blocked.status_code, blocked.text)
        result = blocked.json()
        self.assertEqual('unit_too_large', result['coverage']['status'])
        self.assertEqual([], result['origins'])
        self.assertEqual([], result['units'])
        self.assertEqual(0, result['coverage']['processedOrigins'])
        self.assertLessEqual(len(blocked.content), budget)
        resumed = self.read('scan', view='semantic_units', **prepared, cursor=result['nextCursor'], maxItems=1)
        self.assertEqual(200, resumed.status_code, resumed.text)
        self.assertEqual(source['chunks'][0]['content'], resumed.json()['origins'][0]['text'])
        rejected = self.read('ids', view='semantic_units', **prepared,
            originIds=[source['chunks'][0]['id']], maxBytes=budget)
        self.assertEqual(422, rejected.status_code, rejected.text)
        self.assertEqual('SOURCE_READ_BUDGET_EXCEEDED', rejected.json()['detail']['kind'])

    def test_actual_source_cas_and_root_changes_fail_before_or_at_successful_exit(self):
        source = self.normal()
        prepared = self.prepare()
        descriptor = source['observationManifestRef']
        cas_path = self.store.root / descriptor['relativePath']
        root_path = self.registry._path(source['projectionSha256'])
        read_asset = self.store.read_asset
        for path in (cas_path, root_path):
            original = path.read_bytes()
            try:
                path.write_bytes(original + b' changed-before-read')
                response = self.read(view='semantic_units', **prepared)
                self.assertEqual(422, response.status_code, response.text)
                path.write_bytes(original)
                def changed(reference):
                    value = read_asset(reference)
                    if reference.get('identityScope') == 'complete_source_semantic_unit_with_closure':
                        path.write_bytes(original + b' changed-after-unit-read')
                    return value
                with patch.object(self.store, 'read_asset', changed):
                    response = self.read('ids', view='semantic_units', **prepared,
                        originIds=[source['chunks'][0]['id']])
                self.assertEqual(422, response.status_code, response.text)
            finally:
                path.write_bytes(original)
        receipts = [json.loads(path.read_text(encoding='utf-8'))
                    for path in self.app.state.request_evidence.root.glob('*/receipt.json')]
        latest = sorted((r for r in receipts if r['endpoint'] == '/sources/read'), key=lambda r: r['createdAt'])[-1]
        self.assertEqual('failed', latest['terminalStatus'])
        self.assertIsNone(latest['response'])

    def test_prepared_read_hashes_root_at_entry_and_after_cas_exit(self):
        source = self.normal()
        prepared = self.prepare()
        root = self.registry._path(source['projectionSha256']).resolve()
        observation_sha = source['observationManifestRef']['sha256']
        stream = self.registry._stream_file_identity
        read_uncached = self.store._read_uncached
        root_reads, observation_reads = [], []

        def trace_root(path, *, maximum):
            if path.resolve() == root:
                root_reads.append(path)
            return stream(path, maximum=maximum)

        def trace_cas(descriptor, *, retain):
            if descriptor['sha256'] == observation_sha:
                observation_reads.append(retain)
            return read_uncached(descriptor, retain=retain)

        with patch.object(self.registry, '_stream_file_identity', trace_root), \
                patch.object(self.store, '_read_uncached', trace_cas):
            response = self.read('scan', view='semantic_units', **prepared, maxItems=1)
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(2, len(root_reads))
        self.assertEqual([False, False], observation_reads)

    def test_prepared_read_rejects_root_change_during_cas_exit(self):
        source = self.normal()
        prepared = self.prepare()
        root = self.registry._path(source['projectionSha256'])
        original, stamp = root.read_bytes(), root.stat()
        observation_sha = source['observationManifestRef']['sha256']
        read_uncached = self.store._read_uncached
        observation_reads = 0

        def change_root_during_cas_exit(descriptor, *, retain):
            nonlocal observation_reads
            if descriptor['sha256'] == observation_sha and not retain:
                observation_reads += 1
                if observation_reads == 2:
                    root.write_bytes(bytes([original[0] ^ 1]) + original[1:])
                    os.utime(root, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
            return read_uncached(descriptor, retain=retain)

        try:
            with patch.object(self.store, '_read_uncached', change_root_during_cas_exit):
                response = self.read('scan', view='semantic_units', **prepared, maxItems=1)
            self.assertEqual(2, observation_reads)
            self.assertEqual(422, response.status_code, response.text)
            self.assertEqual('CANONICAL_FRAME_SOURCE_CHANGED', response.json()['detail']['kind'])
        finally:
            root.write_bytes(original)
            os.utime(root, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))

    def test_public_pdf_table_reference_is_resolved_to_complete_semantic_cells_at_prepare(self):
        from native_pdf_canonical_projection import produce_native_pdf
        from native_pdf_projection_transport import publish_native_pdf_transport
        from test_native_pdf_table_projection import NativePdfTableProjectionTest
        table = NativePdfTableProjectionTest()
        table.setUp()
        self.addCleanup(table.temporary.cleanup)
        table.manifest['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        # Use the existing authored table builder with this app's actual CAS.
        for descriptor in list(table.store.root.glob('sha256/*/*.blob')):
            self.store.put_bytes(descriptor.read_bytes())
        internal = produce_native_pdf(table.native, table.dual, table.manifest, artifact_store=self.store)
        source = publish_native_pdf_transport(internal, artifact_store=self.store)
        self.registry.publish(source)
        self.sources = [source]
        prepared = self.prepare()
        with patch.object(self.registry, 'load', side_effect=AssertionError('full table graph reload')):
            response = self.read('ids', view='semantic_units', **prepared,
                originIds=[source['chunks'][0]['id']])
        self.assertEqual(200, response.status_code, response.text)
        origin = response.json()['origins'][0]
        cells = origin['nativeTables'][0]['fragments'][0]['cells']
        self.assertEqual(['Visible fi cafe', ''], [cell['text'] for cell in cells])
        self.assertEqual('source_reviewed_blank', cells[1]['valueState'])
        self.assertEqual(['r1', 'r2'], origin['nativeTables'][0]['completeness']['required'])
        self.assertNotIn('bboxNormalized', response.text)
        self.assertNotIn('glyphIds', response.text)


if __name__ == '__main__':
    unittest.main()
