"""Actual authored CPU PDFs and public candidate capture controls, no models."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from native_pdf_candidates import capture_native_pdf_candidates


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


class NativePdfCandidatesTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.store = ArtifactStore(Path(self.root.name) / 'artifacts')
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
