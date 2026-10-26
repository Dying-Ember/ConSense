"""Actual authored CPU PDFs and public candidate capture controls, no models."""
import hashlib
import copy
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from native_pdf_candidates import capture_native_pdf_candidates, _context


def authored_pdf():
    from reportlab.pdfgen import canvas
    stream = io.BytesIO()
    document = canvas.Canvas(stream, pagesize=(400, 400), invariant=1)
    for number in range(1, 4):
        document.drawString(20, 370, 'AUTHORED CPU TEST ONLY page ' + str(number))
        if number == 2:
            for y in (300, 275, 250, 225): document.line(20, y, 320, y)
            for x in (20, 60, 220, 320): document.line(x, 225, x, 300)
            for row, values in enumerate((('Item', 'Condition', 'Value'),
                                          ('1', 'Use if needed', 'NA'), ('2', 'Other item', 'No'))):
                for x, text in zip((25, 65, 225), values):
                    document.drawString(x, 283 - row * 25, text)
            document.drawString(20, 200, 'Notes: subject to parent clause')
        document.showPage()
    document.save()
    return stream.getvalue()


def context(raw, **changes):
    value = {'source': {'sourceFilename': 'authored.pdf',
                        'sourceSha256': hashlib.sha256(raw).hexdigest(),
                        'mediaType': 'application/pdf', 'pageCount': 3},
             'selectedPhysicalPages': [2], 'readingPlan': {'schemaVersion': 'oer-reading-plan-v1',
                 'sourceSha256': hashlib.sha256(raw).hexdigest(), 'documentFamily': 'GCT',
                 'identityStatus': 'content_supported_non_drawing', 'scope': 'observed gap only'},
             'javaNativeEvidence': [{'physicalPageNo': 2, 'glyphId': 'java/page/2/glyph/0',
                                     'frame': 'rendered_crop_top_left_xywh', 'bbox': [1, 2, 3, 4]}]}
    value.update(changes)
    return json.dumps(value, ensure_ascii=False).encode()


class NativePdfReadingContextBoundaryTest(unittest.TestCase):
    """Pure context admission checks; no provider, PDF parser or model is invoked."""
    def test_explicit_manual_text_status_keeps_actual_source_family_and_unknown_quality(self):
        raw = b'authored immutable context input'
        value = json.loads(context(raw))
        value['readingPlan'].update(documentFamily='APH', identityStatus='human_confirmed_textual_material',
                                    versionStatus='unknown', sourceQualityStatus='unverified')
        actual = _context(json.dumps(value), hashlib.sha256(raw).hexdigest())
        self.assertEqual('APH', actual['readingPlan']['documentFamily'])
        self.assertEqual('human_confirmed_textual_material', actual['readingPlan']['identityStatus'])
        self.assertEqual('unknown', actual['readingPlan']['versionStatus'])
        self.assertEqual('unverified', actual['readingPlan']['sourceQualityStatus'])

    def test_manual_text_status_cannot_override_source_plan_or_drawing_boundaries(self):
        raw = b'authored immutable context input'
        baseline = json.loads(context(raw))
        baseline['readingPlan'].update(documentFamily='APH', identityStatus='human_confirmed_textual_material')
        for key, replacement in (('identityStatus', 'human_confirmed_source_use'), ('identityStatus', 'pending'),
                                 ('documentFamily', 'DRAWING'), ('documentFamily', ''),
                                 ('sourceSha256', '0' * 64), ('schemaVersion', 'foreign-reading-plan')):
            value = copy.deepcopy(baseline)
            value['readingPlan'][key] = replacement
            with self.subTest(key=key, replacement=replacement), self.assertRaisesRegex(ValueError, 'non_drawing_reading_plan_required'):
                _context(json.dumps(value), hashlib.sha256(raw).hexdigest())
        with self.assertRaisesRegex(ValueError, 'source_sha_mismatch'):
            _context(json.dumps(baseline), '0' * 64)


class NativePdfBatchBoundaryTest(unittest.TestCase):
    """Authored transport controls only; providers and continuation are spies."""
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.store = ArtifactStore(Path(self.root.name) / 'artifacts')
        self.raw = b'%PDF-authored-cross-batch-control-not-a-source-fixture'
        self.extractions, self.observations, self.continuations = [], [], []
        self.failed_method = None

    def capture(self, selected, predecessor=None, **changes):
        class Page:
            def __init__(self, number): self.number = number
            def close(self): pass
        class Document:
            pages = [Page(number) for number in range(1, 101)]
            def __enter__(self): return self
            def __exit__(self, *ignored): return False
        def observe(page):
            self.observations.append(page.number)
            return {}, [], {'glyphs': [], 'graphics': []}
        def extract(number, method):
            self.extractions.append((number, method))
            if self.failed_method == (number, method):
                raise RuntimeError('AUTHORED predecessor method failure')
            return [], {}
        def continuation(previous_source, previous_logical, source, logical, profile):
            self.continuations.append((previous_source['originalPage'], source['originalPage'], logical['method']))
            return {'groups': [{'authored': 'complete boundary proposal'}],
                    'status': 'observed_authored_boundary_control'}
        request = json.loads(context(self.raw))
        request['source']['pageCount'] = 100
        request['selectedPhysicalPages'] = selected
        if predecessor is not None:
            request['predecessorBundleRef'] = predecessor
        request.update(changes)
        with patch('native_pdf_candidates._open_pdf', return_value=Document()), \
                patch('native_pdf_candidates.observe_source_page', side_effect=observe), \
                patch('native_pdf_candidates.extract_pdfplumber', side_effect=lambda page, method, *_: extract(page.number, method)), \
                patch('native_pdf_candidates.extract_camelot', side_effect=lambda path, number, method, *_: extract(number, method)), \
                patch('native_pdf_candidates.recover_page', side_effect=lambda *_: {'logicalTables': [], 'observationGroups': {'groups': []}}), \
                patch('native_pdf_candidates.observe_continuations', side_effect=continuation):
            return capture_native_pdf_candidates(self.raw, json.dumps(request).encode(), artifact_store=self.store)

    def test_64_65_and_byte_split_reuse_exact_predecessor_without_reextracting_it(self):
        # Java's page-count and context-byte split paths both call this seam.
        for left, right in ((64, 65), (2, 3)):
            with self.subTest(boundary=(left, right)):
                joined = self.capture([left, right])
                self.continuations.clear()
                first = self.capture([left])
                self.continuations.clear(); self.extractions.clear(); self.observations.clear()
                second = self.capture([right], first['bundleRef'])
                self.assertEqual('candidate_observed', second['status'])
                self.assertEqual(3, len(self.continuations))
                self.assertEqual([right], self.observations)
                self.assertEqual([(right, method['method']) for method in second['pages'][0]['methods']], self.extractions)
                for before, after in zip(joined['pages'][1]['methods'], second['pages'][0]['methods']):
                    old = before['candidate']['observationGroups']['continuations']
                    new = after['candidate']['observationGroups']['continuations']
                    self.assertEqual(old['groups'], new['groups'])
                    self.assertEqual(left, new['anchorPhysicalPageNo'])
                    self.assertEqual(first['pages'][0]['sourceObservationRef'], new['anchorSourceObservationRef'])

    def test_wrong_source_recipe_reading_context_and_raw_method_reject_before_current_extraction(self):
        first = self.capture([64])
        bundle = json.loads(self.store.read_asset(first['bundleRef']))
        for mutation in ('source', 'recipe', 'provider', 'reading_context', 'source_context', 'raw_method'):
            with self.subTest(mutation=mutation):
                altered = copy.deepcopy(bundle)
                changes = {}
                if mutation == 'source': altered['source']['sourceSha256'] = '0' * 64
                if mutation == 'recipe': altered['codeRefs']['native_pdf_table_logic.py'] = self.store.put_bytes(b'changed recipe')
                if mutation == 'provider': altered['providerVersions']['pdfplumber'] = 'different-version'
                if mutation == 'reading_context':
                    plan = json.loads(context(self.raw))['readingPlan']
                    plan['scope'] = 'different source scope'; changes['readingPlan'] = plan
                if mutation == 'source_context': changes['additionalMetadata'] = 'different source context'
                if mutation == 'raw_method': altered['pages'][0]['methods'][0]['rawRef'] = altered['pages'][0]['methods'][1]['rawRef']
                reference = self.store.put_bytes(json.dumps(altered).encode())
                self.extractions.clear(); self.observations.clear()
                reply = self.capture([65], reference, **changes)
                self.assertEqual('failed', reply['status'])
                self.assertEqual([], reply['pages'])
                self.assertEqual([], self.extractions)
                self.assertEqual([], self.observations)

    def test_non_adjacent_or_overlapping_predecessor_rejects_before_current_extraction(self):
        first = self.capture([64])
        for selected in ([66], [64], [63]):
            with self.subTest(selected=selected):
                self.extractions.clear()
                reply = self.capture(selected, first['bundleRef'])
                self.assertEqual('failed', reply['status'])
                self.assertEqual([], self.extractions)

    def test_missing_or_corrupt_predecessor_cas_never_falls_back_to_extraction(self):
        for corruption in ('missing', 'corrupt'):
            with self.subTest(corruption=corruption):
                first = self.capture([64])
                ref = first['bundleRef']
                path = self.store.root / ref['relativePath']
                if corruption == 'missing': path.unlink()
                else: path.write_bytes(b'authored CAS corruption')
                self.extractions.clear()
                reply = self.capture([65], ref)
                self.assertEqual('failed', reply['status'])
                self.assertEqual([], reply['pages'])
                self.assertEqual([], self.extractions)

    def test_last_failed_method_and_absent_reference_mark_boundary_unknown_without_stale_fallback(self):
        self.failed_method = (64, 'camelot_network')
        first = self.capture([63, 64])
        self.failed_method = None
        self.continuations.clear()
        second = self.capture([65], first['bundleRef'])
        self.assertEqual('candidate_observed', second['status'])
        self.assertEqual([(64, 65, method) for method in ('pdfplumber_lines_lines', 'camelot_stream')], self.continuations)
        missing = next(method for method in second['pages'][0]['methods'] if method['method'] == 'camelot_network')
        self.assertEqual('unknown_predecessor_unavailable', missing['candidate']['observationGroups']['continuationStatus'])
        unbound = self.capture([65])
        self.assertTrue(all(method['candidate']['observationGroups']['continuationStatus'] == 'unknown_predecessor_unavailable'
                            for method in unbound['pages'][0]['methods']))


class NativePdfCandidatesTest(unittest.TestCase):
    def saved_com11_reply(self, selected, predecessor=None, replayed=None):
        root_value = os.environ.get('CONSENSE_SL_NOTE9_FIXTURE')
        if not root_value:
            self.skipTest('Explicit actual saved COM11 fixture not supplied')
        root = Path(root_value).resolve(strict=True)
        pins = json.loads((root / 'fixture-pins.json').read_text('utf-8'))['files']
        for name, expected in pins.items():
            raw = (root / name).read_bytes()
            self.assertEqual(expected['bytes'], len(raw))
            self.assertEqual(expected['sha256'], hashlib.sha256(raw).hexdigest())
        fixture = json.loads((root / 'saved-provider-pages.json').read_text('utf-8'))
        records = {name: {row['record']['localPage']: row['record'] for row in rows}
                   for name, rows in fixture['records'].items()}
        class SavedPage:
            def __init__(self, number): self.number = number
            def close(self): pass
        class SavedDocument:
            pages = [SavedPage(number) for number in range(1, 56)]
            def __enter__(self): return self
            def __exit__(self, *ignored): return False
        def observe(page):
            if replayed is not None: replayed.append(('source', page.number))
            saved = records['source-observations.pages.jsonl'][page.number]
            geometry = {key: copy.deepcopy(saved[key]) for key in ('pageWidth','pageHeight','rotation',
                'pdfplumberPageWidth','pdfplumberPageHeight','pdfplumberMediaBox','pdfplumberCropBox',
                'pdfplumberPageBbox','originalPdfMediaBox','originalPdfCropBox','originalPdfRotate',
                'pdfUserUnit','sourceCoordinateTransform')}
            observed = {key: copy.deepcopy(saved[key]) for key in ('glyphs','graphics',
                'sourceCharsAreNativeTextLayerObservations','sourceTextLayerQualityAccepted')}
            return geometry, observed['glyphs'], observed
        def tables(number, method):
            if replayed is not None: replayed.append((method, number))
            record=records[method + '.pages.jsonl'][number]
            values=copy.deepcopy(record['tables'])
            # The old complete-section recorder added this declared namespace after extraction.
            prefix=record['sectionId']+':'+str(record['localPage'])+':'
            for table in values:
                self.assertTrue(table['tableId'].startswith(prefix))
                table['tableId']=table['tableId'][len(prefix):]
            return values, {'savedWholeProviderReplay': True}
        raw = (root / 'original.pdf').read_bytes()
        request = {'source': {'sourceFilename': 'SL_architectural_COM11_complete-section.pdf',
            'sourceSha256': fixture['sourceSha256'], 'mediaType':'application/pdf','pageCount':55},
            'selectedPhysicalPages':selected,'readingPlan': {'schemaVersion':'oer-reading-plan-v1',
                'sourceSha256':fixture['sourceSha256'],'documentFamily':'SL','identityStatus':'sha_verified'},
            'javaNativeEvidence':[]}
        if predecessor is not None: request['predecessorBundleRef'] = predecessor
        with patch('native_pdf_candidates._open_pdf', return_value=SavedDocument()), \
             patch('native_pdf_candidates.observe_source_page', side_effect=observe), \
             patch('native_pdf_candidates.extract_pdfplumber', side_effect=lambda page,method,*rest: tables(page.number,method)), \
             patch('native_pdf_candidates.extract_camelot', side_effect=lambda path,number,method,*rest: tables(number,method)):
            reply = capture_native_pdf_candidates(raw, json.dumps(request).encode(), artifact_store=self.store)
        self.assertEqual('candidate_observed',reply['status'])
        return reply

    def test_saved_com11_cross_batch_note_nine_matches_same_input_without_reextracting_anchor(self):
        joined = self.saved_com11_reply([19, 20])
        first = self.saved_com11_reply([19])
        replayed = []
        second = self.saved_com11_reply([20], first['bundleRef'], replayed)
        self.assertEqual([('source', 20), ('pdfplumber_lines_lines', 20),
                          ('camelot_network', 20), ('camelot_stream', 20)], replayed)
        for before, after in zip(joined['pages'][1]['methods'], second['pages'][0]['methods']):
            old = before['candidate']['observationGroups'].get('continuations')
            new = after['candidate']['observationGroups'].get('continuations')
            self.assertEqual(old is None, new is None)
            if old is not None:
                self.assertEqual(old['groups'], new['groups'])
                self.assertEqual(19, new['anchorPhysicalPageNo'])
                self.assertEqual(first['pages'][0]['sourceObservationRef'], new['anchorSourceObservationRef'])

    def test_saved_com11_note_nine_suffix_reaches_its_actual_anchor_without_a_second_table(self):
        reply = self.saved_com11_reply([15,19,20,24,25,29,33])
        current = next(page for page in reply['pages'] if page['physicalPageNo']==20)
        for method in current['methods']:
            candidate = method['candidate']
            self.assertEqual([],candidate['logicalTables'])
            if method['method']=='pdfplumber_lines_lines': continue
            continuation = candidate['observationGroups'].get('continuations')
            self.assertIsNotNone(continuation,'Saved Note9 source suffix requires its explicit existing source-order relationship')
            self.assertEqual(19,continuation['anchorPhysicalPageNo'])
            observed = continuation['groups'][0]
            text = observed['members'][0]['text']
            self.assertIn('LM',text);self.assertIn('RS',text)
            self.assertIn("For other areas, abbreviation to be proposed by the Main Contractor for CMR's agreement.",text)
            edge=observed['relations'][0]
            self.assertEqual((19,20,'source_order'),(edge['fromOriginalPage'],edge['toOriginalPage'],edge['direction']))
            self.assertEqual(9,edge['guards']['lastExplicitNoteNumber'])
            self.assertEqual('unknown',edge['legalApplicability']);self.assertFalse(observed['wholeSourceGroupAccepted'])
        destination=os.environ.get('CONSENSE_SL_NOTE9_REPLAY_OUTPUT')
        if destination:
            root=Path(destination).resolve(strict=True)
            payload=json.dumps(reply,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode()
            path=root/'actual-native-candidate-replay.json'
            with path.open('xb') as stream: stream.write(payload)
            with (root/'actual-native-candidate-replay.pin.json').open('x',encoding='utf-8') as stream:
                json.dump({'bytes':len(payload),'sha256':hashlib.sha256(payload).hexdigest()},stream)

    def test_saved_com11_complete_facade_volumetric_and_closed_list_keep_their_same_page_observations(self):
        reply=self.saved_com11_reply([15,24,25,33])
        for page in reply['pages']:
            for method in page['methods']:
                candidate=method['candidate']
                self.assertNotIn('continuations',candidate['observationGroups'])
                if page['physicalPageNo'] in (15,33) and method['method']!='pdfplumber_lines_lines':
                    self.assertEqual(1,len(candidate['logicalTables']))
                    self.assertEqual((22 if page['physicalPageNo']==15 else 24,6),
                        (candidate['logicalTables'][0]['rowCount'],candidate['logicalTables'][0]['colCount']))
                    self.assertFalse(candidate['completeSourceGroupAccepted'])

    def test_saved_com11_optional_continuation_failure_retains_the_successful_same_page_candidate(self):
        baseline=self.saved_com11_reply([15,19,20])
        with patch('native_pdf_candidates.observe_continuations',create=True,
                   side_effect=RuntimeError('AUTHORED OPTIONAL CONTINUATION FAILURE')):
            failed=self.saved_com11_reply([15,19,20])
        for old,new in zip(baseline['pages'][1]['methods'],failed['pages'][1]['methods']):
            self.assertEqual('candidate_observed',new['status']);self.assertIsNone(new['failure'])
            candidate=copy.deepcopy(new['candidate']);observed=candidate['observationGroups']
            self.assertIn('AUTHORED OPTIONAL CONTINUATION FAILURE',json.dumps(observed['continuationFailure']))
            self.assertEqual('unknown_optional_continuation_observation_failed',observed.pop('continuationStatus'))
            observed.pop('continuationFailure')
            previous=copy.deepcopy(old['candidate']);previous['observationGroups'].pop('continuationStatus',None)
            self.assertEqual(previous,candidate,'An optional suffix failure must not clear or change the existing main table')

    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.store = ArtifactStore(Path(self.root.name) / 'artifacts')
        if not self._testMethodName.startswith('test_saved_com11_'):
            self.raw = authored_pdf()

    def capture(self, native_context=None, **kwargs):
        return capture_native_pdf_candidates(self.raw, native_context or context(self.raw),
                                             artifact_store=self.store, **kwargs)

    def bundle(self, reply):
        return json.loads(self.store.read_asset(reply['bundleRef']))

    def test_actual_complete_pdf_subset_and_whole_table_are_not_source_acceptance(self):
        # Camelot is absent in the bundled CPU runtime; its independent failures
        # must remain visible while real pdfplumber reads the actual PDF.
        with patch.dict('sys.modules', {'camelot': None}):
            reply = self.capture()
        self.assertEqual('partial', reply['status'])
        self.assertEqual([2], reply['selectedPhysicalPages'])
        self.assertEqual(3, reply['source']['pageCount'])
        self.assertEqual({'sourcePageCount': 3, 'selectedPageCount': 1,
                          'unselectedPagesStatus': 'unknown', 'sourceQualityAccepted': False,
                          'tableStructureAccepted': False}, reply['coverage'])
        page = reply['pages'][0]
        self.assertEqual(2, page['physicalPageNo'])
        self.assertEqual('pdfplumber/page/2', page['nativeGlyphNamespace'])
        self.assertEqual('PDF_points_crop_top_left', page['frame']['coordinateSpace'])
        methods = page['methods']
        self.assertEqual(['pdfplumber_lines_lines', 'camelot_network', 'camelot_stream'],
                         [method['method'] for method in methods])
        self.assertEqual(['candidate_observed', 'failed', 'failed'], [method['status'] for method in methods])
        candidate = methods[0]['candidate']
        self.assertEqual('unpublished_candidate', candidate['publicationStatus'])
        self.assertFalse(candidate['completeSourceGroupAccepted'])
        self.assertEqual(['Item', 'Condition', 'Value', '1', 'Use if needed', 'NA', '2', 'Other item', 'No'],
                         [cell['text'] for cell in candidate['tables'][0]['cells']])
        self.assertEqual('unknown', candidate['conditionCandidates'][0]['owner'])
        self.assertIsNotNone(candidate['tables'][0]['logicalTable'])
        self.assertTrue(all(type(gid) is int for table in candidate['tables']
                            for cell in table['cells'] for gid in cell['glyphIds']))
        bundle = self.bundle(reply)
        self.assertEqual(self.raw, self.store.read_asset(bundle['originalRef']))
        self.assertEqual(context(self.raw), self.store.read_asset(bundle['nativeContextRef']))
        observed = json.loads(self.store.read_asset(page['sourceObservationRef']))
        self.assertGreater(len(observed['observations']['glyphs']), 20)
        self.assertFalse(observed['observations']['sourceTextLayerQualityAccepted'])
        self.assertNotIn('java/page/2/glyph/0', json.dumps(candidate))
        self.assertEqual(0, bundle['modelCalls'])
        for method in methods: self.store.verify(method['rawRef'])

    def test_sha_mismatch_rejects_before_provider_and_retains_received_bytes(self):
        declared = json.loads(context(self.raw)); declared['source']['sourceSha256'] = '0' * 64
        with patch('native_pdf_candidates._open_pdf', side_effect=AssertionError('must not parse')):
            reply = self.capture(json.dumps(declared).encode())
        self.assertEqual('failed', reply['status'])
        self.assertIn('source_sha_mismatch', reply['failure']['message'])
        self.assertEqual([], reply['pages'])
        self.assertEqual(self.raw, self.store.read_asset(self.bundle(reply)['originalRef']))

    def test_actual_page_count_mismatch_is_visible_with_actual_denominator(self):
        declared = json.loads(context(self.raw)); declared['source']['pageCount'] = 2
        reply = self.capture(json.dumps(declared).encode())
        self.assertEqual('failed', reply['status'])
        self.assertEqual(3, reply['source']['pageCount'])
        self.assertEqual(3, reply['coverage']['sourcePageCount'])
        self.assertIn('source_page_count_mismatch', reply['failure']['message'])

    def test_duplicate_empty_out_of_range_and_boolean_page_selection_refuse(self):
        for selected in ([2, 2], [], [4], [True]):
            with self.subTest(selected=selected):
                reply = self.capture(context(self.raw, selectedPhysicalPages=selected))
                self.assertEqual('failed', reply['status'])
                self.assertEqual([], reply['pages'])
                self.assertFalse(reply['coverage']['sourceQualityAccepted'])

    def test_independent_provider_error_retains_table_and_raw_diagnostics(self):
        from native_pdf_table_candidate import extract_pdfplumber
        def broken(page, method, glyphs, geometry):
            tables, diagnostics = extract_pdfplumber(page, method, glyphs, geometry)
            tables[0]['extractionError'] = {'type': 'AuthoredFailure', 'message': 'text failed'}
            diagnostics['authoredMarker'] = 'complete diagnostics retained'
            return tables, diagnostics
        with patch('native_pdf_candidates.extract_pdfplumber', side_effect=broken), \
                patch.dict('sys.modules', {'camelot': None}):
            reply = self.capture()
        method = reply['pages'][0]['methods'][0]
        self.assertEqual('failed', method['status'])
        self.assertIsNone(method['candidate'])
        retained = json.loads(self.store.read_asset(method['rawRef']))
        self.assertEqual(1, len(retained['tables']))
        self.assertEqual('complete diagnostics retained', retained['diagnostics']['authoredMarker'])
        self.assertEqual('AuthoredFailure', retained['tables'][0]['extractionError']['type'])

    def test_response_cap_retains_whole_bundle_and_refuses_without_truncating_candidates(self):
        with patch.dict('sys.modules', {'camelot': None}):
            reply = self.capture(max_reply_bytes=4096)
        self.assertEqual('failed', reply['status'])
        self.assertIn('reply_byte_cap_exceeded', reply['failure']['message'])
        self.assertEqual([], reply['pages'])
        full = self.bundle(reply)
        self.assertEqual(9, len(full['pages'][0]['methods'][0]['candidate']['tables'][0]['cells']))
        self.assertFalse(reply['coverage']['tableStructureAccepted'])
        self.assertLessEqual(len(json.dumps(reply, separators=(',', ':')).encode()), 4096)

    def test_mutated_owned_camelot_input_invalidates_candidate_and_is_captured(self):
        def mutate(path, number, method, glyphs, geometry):
            Path(path).write_bytes(b'changed owned temporary file')
            return [], {'authoredMutation': True}
        with patch('native_pdf_candidates.extract_camelot', side_effect=mutate):
            reply = self.capture()
        methods = reply['pages'][0]['methods']
        self.assertEqual(['candidate_observed', 'failed', 'failed'], [method['status'] for method in methods])
        self.assertIn('owned_original_bytes_changed', methods[1]['failure']['message'])
        retained = json.loads(self.store.read_asset(methods[1]['rawRef']))
        self.assertEqual(b'changed owned temporary file', self.store.read_asset(retained['receivedOriginalRef']))
        self.assertEqual(self.raw, self.store.read_asset(self.bundle(reply)['originalRef']))

    def test_invalid_pdf_and_input_cap_have_visible_failures_and_no_quality_claim(self):
        raw = b'%PDF-1.7\ninvalid PDF'
        reply = capture_native_pdf_candidates(raw, context(raw), artifact_store=self.store)
        self.assertEqual('failed', reply['status'])
        self.assertEqual([], reply['pages'])
        reply = self.capture(max_pdf_bytes=100)
        self.assertEqual('failed', reply['status'])
        self.assertIn('original_byte_cap_exceeded', reply['failure']['message'])
        self.assertEqual('unknown', reply['coverage']['unselectedPagesStatus'])

    def test_three_successful_methods_remain_independent_unadopted_candidates(self):
        with patch('native_pdf_candidates.extract_camelot', return_value=([], {'authoredNoTables': True})):
            reply = self.capture()
        self.assertEqual('candidate_observed', reply['status'])
        methods = reply['pages'][0]['methods']
        self.assertEqual([1, 0, 0], [len(method['candidate']['tables']) for method in methods])
        self.assertTrue(all(method['failure'] is None for method in methods))
        self.assertTrue(all(method['candidate']['publicationStatus'] == 'unpublished_candidate' for method in methods))
        self.assertEqual('candidate_unverified', reply['adoptionStatus'])
        self.assertFalse(self.bundle(reply)['candidateFusion'])

    def test_source_observation_failure_preserves_all_method_failures(self):
        with patch('native_pdf_candidates.observe_source_page', side_effect=ValueError('authored glyph failure')):
            reply = self.capture()
        self.assertEqual('failed', reply['status'])
        self.assertEqual(1, len(reply['pages']))
        methods = reply['pages'][0]['methods']
        self.assertEqual(['failed'] * 3, [method['status'] for method in methods])
        observation = json.loads(self.store.read_asset(reply['pages'][0]['sourceObservationRef']))
        self.assertIn('authored glyph failure', observation['failure']['message'])
        self.assertTrue(all(method['candidate'] is None for method in methods))

    def test_rotated_crop_and_user_unit_keep_provider_frame_separate_from_java(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import FloatObject, NameObject, RectangleObject
        reader = PdfReader(io.BytesIO(self.raw))
        reader.pages[1].cropbox = RectangleObject([20, 30, 360, 370])
        reader.pages[1].rotate(90)
        reader.pages[1][NameObject('/UserUnit')] = FloatObject(2)
        writer = PdfWriter(); writer.append_pages_from_reader(reader)
        stream = io.BytesIO(); writer.write(stream); raw = stream.getvalue()
        with patch.dict('sys.modules', {'camelot': None}):
            reply = capture_native_pdf_candidates(raw, context(raw), artifact_store=self.store)
        page = reply['pages'][0]
        self.assertEqual('PDF_points_crop_top_left', page['frame']['coordinateSpace'])
        self.assertEqual([20., 30., 360., 370.], page['frame']['originalPdfCropBox'])
        self.assertEqual(90, page['frame']['originalPdfRotate'])
        self.assertEqual(2, page['frame']['pdfUserUnit'])
        self.assertEqual('pdfplumber_rotated_media_top_left', page['frame']['sourceCoordinateTransform']['from'])
        self.assertNotEqual('rendered_crop_top_left_xywh', page['frame']['coordinateSpace'])

    def test_unbound_drawing_or_changed_reading_plan_refuses_before_cpu_parsing(self):
        actual = json.loads(context(self.raw))['readingPlan']
        for plan in ({}, {**actual, 'documentFamily': 'DRAWING'},
                     {**actual, 'identityStatus': 'pending'}, {**actual, 'sourceSha256': '0' * 64}):
            with self.subTest(plan=plan), patch('native_pdf_candidates._open_pdf', side_effect=AssertionError('must not parse')):
                reply = self.capture(context(self.raw, readingPlan=plan))
                self.assertEqual('failed', reply['status'])
                self.assertIn('non_drawing_reading_plan_required', reply['failure']['message'])
                self.assertEqual([], reply['pages'])


if __name__ == '__main__': unittest.main()
