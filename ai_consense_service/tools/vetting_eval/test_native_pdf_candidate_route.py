"""Real multipart CPU candidate route with authored PDFs; no model acceptance."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from test_native_pdf_candidates import authored_pdf, context
import server


class NativePdfCandidateRouteTests(unittest.TestCase):
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
