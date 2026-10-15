"""Real multipart CPU candidate route with authored PDFs; no model acceptance."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from artifact_store import ArtifactStore
from test_native_pdf_candidates import authored_pdf, context
import server


class NativePdfCandidateRouteTests(unittest.TestCase):
    def test_openapi_serializes_required_multipart_parts_without_ellipsis(self):
        response = TestClient(server.app).get('/openapi.json')
        self.assertEqual(response.status_code, 200)
        schema = response.json()
        body = schema['paths']['/native-pdf/table-candidates']['post']['requestBody']
        self.assertTrue(body['required'])
        ref = body['content']['multipart/form-data']['schema']['$ref']
        parts = schema['components']['schemas'][ref.rsplit('/', 1)[-1]]
        self.assertEqual(set(parts['required']), {'file', 'nativeContext'})

    def test_complete_original_and_caller_native_context_reach_actual_cpu_capture(self):
        raw = authored_pdf()
        with tempfile.TemporaryDirectory() as directory, patch.object(server, 'STATE', Path(directory)), \
                patch.dict('sys.modules', {'camelot': None}), \
                patch.object(server, 'create_ocr_engine', side_effect=AssertionError('OCR forbidden')):
            response = TestClient(server.app).post('/native-pdf/table-candidates',
                files={'file': ('authored.pdf', raw, 'application/pdf')},
                data={'nativeContext': context(raw).decode()})
        self.assertEqual(response.status_code, 200)
        value = response.json()
        self.assertEqual(value['schema'], 'consense.native-pdf-candidates.v1')
        self.assertEqual(value['source']['pageCount'], 3)
        self.assertEqual(value['selectedPhysicalPages'], [2])
        self.assertFalse(value['coverage']['sourceQualityAccepted'])
        self.assertEqual(value['pages'][0]['methods'][0]['status'], 'candidate_observed')

    def test_complete_context_file_above_multipart_text_limit_is_captured_byte_exact(self):
        raw = authored_pdf()
        native = context(raw, javaNativeEvidence=[{'physicalPageNo': 2, 'glyphText': 'x'*(1024*1024+1)}])
        self.assertGreater(len(native), 1024*1024)
        with tempfile.TemporaryDirectory() as directory, patch.object(server, 'STATE', Path(directory)), \
                patch.dict('sys.modules', {'camelot': None}), \
                patch.object(server, 'create_ocr_engine', side_effect=AssertionError('OCR forbidden')):
            response = TestClient(server.app).post('/native-pdf/table-candidates', files={
                'file': ('authored.pdf', raw, 'application/pdf'),
                'nativeContext': ('native-context.json', native, 'application/json')})
            self.assertEqual(response.status_code, 200)
            value = response.json()
            store = ArtifactStore(Path(directory)/'native-pdf-candidates')
            bundle = json.loads(store.read_asset(value['bundleRef']))
            self.assertEqual(store.read_asset(bundle['originalRef']), raw)
            self.assertEqual(store.read_asset(bundle['nativeContextRef']), native)
        self.assertEqual(value['selectedPhysicalPages'], [2])
        self.assertEqual(value['source']['pageCount'], 3)
        self.assertFalse(value['coverage']['sourceQualityAccepted'])

    def test_missing_required_parts_return_422_before_capture_without_ellipsis(self):
        requests = ({}, {'files': {'file': ('authored.pdf', b'%PDF-', 'application/pdf')}},
                    {'data': {'nativeContext': '{}'}})
        with patch('native_pdf_candidates.capture_native_pdf_candidates') as capture, \
                patch.object(server, 'create_ocr_engine', side_effect=AssertionError('OCR forbidden')):
            for parts in requests:
                with self.subTest(parts=parts):
                    response = TestClient(server.app).post('/native-pdf/table-candidates', **parts)
                    self.assertEqual(response.status_code, 422)
                    self.assertTrue(response.json()['detail'])
        capture.assert_not_called()

    def test_invalid_context_file_preserves_original_bytes_and_does_not_parse(self):
        raw = authored_pdf()
        native = b'not-json-\xff'
        with tempfile.TemporaryDirectory() as directory, patch.object(server, 'STATE', Path(directory)), \
                patch('native_pdf_candidates._open_pdf', side_effect=AssertionError('parse forbidden')), \
                patch.object(server, 'create_ocr_engine', side_effect=AssertionError('OCR forbidden')):
            response = TestClient(server.app).post('/native-pdf/table-candidates', files={
                'file': ('authored.pdf', raw, 'application/pdf'),
                'nativeContext': ('native-context.json', native, 'application/json')})
            self.assertEqual(response.status_code, 200)
            value = response.json()
            self.assertEqual(value['status'], 'failed')
            self.assertEqual(value['pages'], [])
            store = ArtifactStore(Path(directory)/'native-pdf-candidates')
            bundle = json.loads(store.read_asset(value['bundleRef']))
            self.assertEqual(store.read_asset(bundle['originalRef']), raw)
            self.assertEqual(store.read_asset(bundle['nativeContextRef']), native)

    def test_context_file_limit_preserves_exact_cap_before_candidate_capture(self):
        with patch('native_pdf_candidates.MAX_CONTEXT_BYTES', 8), \
                patch('native_pdf_candidates.capture_native_pdf_candidates') as capture:
            response = TestClient(server.app).post('/native-pdf/table-candidates', files={
                'file': ('authored.pdf', b'%PDF-', 'application/pdf'),
                'nativeContext': ('native-context.json', b'123456789', 'application/json')})
        self.assertEqual(response.status_code, 413)
        capture.assert_not_called()

    def test_context_file_at_exact_limit_passes_complete_bytes_to_capture(self):
        native = b'12345678'
        with patch('native_pdf_candidates.MAX_CONTEXT_BYTES', len(native)), \
                patch('native_pdf_candidates.capture_native_pdf_candidates', return_value={'status': 'authored-control'}) as capture:
            response = TestClient(server.app).post('/native-pdf/table-candidates', files={
                'file': ('authored.pdf', b'%PDF-', 'application/pdf'),
                'nativeContext': ('native-context.json', native, 'application/json')})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(capture.call_args.args, (b'%PDF-', native))

    def test_over_limit_stream_is_refused_before_candidate_capture(self):
        with patch('native_pdf_candidates.MAX_PDF_BYTES', 8), \
                patch('native_pdf_candidates.capture_native_pdf_candidates') as capture:
            response = TestClient(server.app).post('/native-pdf/table-candidates',
                files={'file': ('authored.pdf', b'%PDF-too-large', 'application/pdf')},
                data={'nativeContext': '{}'})
        self.assertEqual(response.status_code, 413)
        capture.assert_not_called()

    def test_utf8_context_cap_uses_actual_bytes_before_candidate_capture(self):
        with patch('native_pdf_candidates.MAX_CONTEXT_BYTES', 8), \
                patch('native_pdf_candidates.capture_native_pdf_candidates') as capture:
            response = TestClient(server.app).post('/native-pdf/table-candidates',
                files={'file': ('authored.pdf', b'%PDF-', 'application/pdf')},
                data={'nativeContext': '汉'*3})
        self.assertEqual(response.status_code, 413)
        capture.assert_not_called()

    def test_invalid_admission_returns_visible_failed_receipt_without_ocr(self):
        raw = authored_pdf()
        with tempfile.TemporaryDirectory() as directory, patch.object(server, 'STATE', Path(directory)), \
                patch('native_pdf_candidates._open_pdf', side_effect=AssertionError('parse forbidden')):
            response = TestClient(server.app).post('/native-pdf/table-candidates',
                files={'file': ('authored.pdf', raw, 'application/pdf')},
                data={'nativeContext': context(raw, readingPlan={}).decode()})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['status'], 'failed')
        self.assertIn('non_drawing_reading_plan_required', response.json()['failure']['message'])
        self.assertEqual(response.json()['pages'], [])


if __name__ == '__main__':
    unittest.main()
