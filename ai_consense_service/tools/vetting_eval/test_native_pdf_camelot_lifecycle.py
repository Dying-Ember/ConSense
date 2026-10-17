"""Real optional Camelot/PLAYA ownership regressions; no models or source fixtures."""
from contextlib import contextmanager
import hashlib
import importlib.util
import io
import json
import mmap
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
import native_pdf_table_candidate as producer
from native_pdf_candidates import capture_native_pdf_candidates
from test_native_pdf_candidates import authored_pdf, context


@unittest.skipUnless(importlib.util.find_spec('camelot') and importlib.util.find_spec('playa'),
                     'Optional real native-table CPU providers are not installed')
class CamelotLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.provider_temp = self.root / 'provider-temp'
        self.provider_temp.mkdir()
        self.addCleanup(patch.stopall)
        patch('tempfile.tempdir', str(self.provider_temp)).start()
        self.raw = authored_pdf()
        self.path = self.root / 'input.pdf'
        self.path.write_bytes(self.raw)
        self.documents = []
        import playa
        original_open = playa.open
        def tracked_open(*args, **kwargs):
            document = original_open(*args, **kwargs)
            self.documents.append(document)
            return document
        patch('playa.open', side_effect=tracked_open).start()
        self.addCleanup(self.release_after_assertions)

    def release_after_assertions(self):
        for document in self.documents:
            document.close()
            if isinstance(document.buffer, mmap.mmap) and not document.buffer.closed:
                document.buffer.close()

    def assert_released(self):
        self.assertTrue(self.documents)
        self.assertTrue(all(document._fp is None for document in self.documents))
        self.assertTrue(all(not isinstance(document.buffer, mmap.mmap) or document.buffer.closed
                            for document in self.documents))
        self.assertEqual([], list(self.provider_temp.rglob('*.pdf')))

    def extract(self, method='camelot_stream'):
        import pdfplumber
        with pdfplumber.open(io.BytesIO(self.raw)) as pdf:
            geometry, glyphs, _ = producer.observe_source_page(pdf.pages[1])
            return producer.extract_camelot(self.path, 2, method, glyphs, geometry)

    def test_complete_real_capture_releases_closed_documents_and_owned_pdfs(self):
        store = ArtifactStore(self.root / 'artifacts')
        reply = capture_native_pdf_candidates(self.raw, context(self.raw, selectedPhysicalPages=[1, 2, 3]),
                                             artifact_store=store)
        self.assertEqual('candidate_observed', reply['status'])
        self.assertEqual([1, 2, 3], [page['physicalPageNo'] for page in reply['pages']])
        self.assertEqual(['candidate_observed'] * 9,
                         [method['status'] for page in reply['pages'] for method in page['methods']])
        self.assertEqual([], list((self.root / 'artifacts').rglob('*.pdf')))
        self.assertEqual(self.raw, store.read_asset(json.loads(store.read_asset(reply['bundleRef']))['originalRef']))
        self.assert_released()

    def test_complete_projection_matches_public_camelot_for_both_methods(self):
        import camelot
        @contextmanager
        def public_tables(path, local_page, flavor):
            start = len(self.documents)
            try:
                yield camelot.read_pdf(str(path), pages=str(local_page), flavor=flavor)
            finally:
                for document in self.documents[start:]:
                    if isinstance(document.buffer, mmap.mmap) and not document.buffer.closed:
                        document.buffer.close()
        for method in ('camelot_network', 'camelot_stream'):
            with self.subTest(method=method):
                actual, _ = self.extract(method)
                with patch.object(producer, '_camelot_tables', public_tables):
                    expected, _ = self.extract(method)
                self.assertEqual(expected, actual)
                self.assertTrue(actual)
        self.assert_released()

    def test_lazy_table_iteration_and_projection_finish_before_mapping_close(self):
        from camelot.handlers import PDFHandler
        original = PDFHandler._parse_page
        iterations = []
        def delayed(handler, page, *args, **kwargs):
            tables = original(handler, page, *args, **kwargs)
            def rows():
                self.assertFalse(self.documents[-1].buffer.closed)
                iterations.append('consumed')
                yield from tables
            return rows()
        original_projection = producer.native_textlines
        def project(*args):
            self.assertFalse(self.documents[-1].buffer.closed)
            return original_projection(*args)
        with patch.object(PDFHandler, '_parse_page', delayed), patch.object(producer, 'native_textlines', project):
            tables, _ = self.extract()
        self.assertTrue(tables)
        self.assertEqual(['consumed'], iterations)
        self.assert_released()

    def test_parser_exception_releases_owned_mapping(self):
        from camelot.handlers import PDFHandler
        with patch.object(PDFHandler, '_parse_page', side_effect=ValueError('authored parser failure')):
            with self.assertRaisesRegex(ValueError, 'authored parser failure'):
                self.extract()
        self.assert_released()

    def test_projection_exception_releases_owned_mapping(self):
        with patch.object(producer, 'native_textlines', side_effect=ValueError('authored projection failure')):
            with self.assertRaisesRegex(ValueError, 'authored projection failure'):
                self.extract()
        self.assert_released()

    def test_real_no_copy_permission_remains_a_method_failure(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.constants import UserAccessPermissions
        writer = PdfWriter()
        writer.append_pages_from_reader(PdfReader(io.BytesIO(self.raw)))
        writer.encrypt(user_password='', owner_password='authored-owner-only', permissions_flag=UserAccessPermissions.PRINT)
        stream = io.BytesIO()
        writer.write(stream)
        raw = stream.getvalue()
        store = ArtifactStore(self.root / 'restricted-artifacts')
        reply = capture_native_pdf_candidates(raw, context(raw), artifact_store=store)
        self.assertEqual('partial', reply['status'])
        self.assertEqual([2], [page['physicalPageNo'] for page in reply['pages']])
        methods = reply['pages'][0]['methods']
        self.assertEqual(['candidate_observed', 'failed', 'failed'], [method['status'] for method in methods])
        self.assertEqual(['PDFTextExtractionNotAllowed'] * 2, [method['failure']['type'] for method in methods[1:]])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), reply['source']['sourceSha256'])
        self.assertEqual(raw, store.read_asset(json.loads(store.read_asset(reply['bundleRef']))['originalRef']))
        self.assertEqual([], list((self.root / 'restricted-artifacts').rglob('*.pdf')))
        self.assert_released()


if __name__ == '__main__':
    unittest.main()
