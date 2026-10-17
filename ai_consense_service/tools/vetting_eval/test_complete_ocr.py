"""Actual vendor API shapes with transport doubles; not model acceptance."""
import base64
import hashlib
import io
import json
import tempfile
import unittest
import zipfile
import random
import numpy as np
import threading
import time
from contextlib import contextmanager
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from PIL import Image
from artifact_store import ArtifactStore
from complete_ocr import recognize_complete_page, CompleteOcrError


def identity(parser):
    return {'parserVersion': '1.6' if parser == 'paddleocr-vl' else '4.0.10',
            'engine': 'authored-native-api-contract',
            'models': [{'modelId': 'authored-contract-model', 'revision': 'b'*40}],
            'config': {'fixture': True, 'device': 'cuda', 'precision': 'bf16'}}


class CompleteOcrTests(unittest.TestCase):
    def test_active_child_gaps_leave_provider_prefix_and_body_byte_identical(self):
        from test_ocr_layout_coverage import reread_capture_fixture
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory); raw, capture = reread_capture_fixture(store)
            _, request = self.transport(raw)
            original = recognize_complete_page(raw, self.config(), store, request=request,
                source_context=self.source_context(raw))
            value = recognize_complete_page(raw, self.config(), store, request=request,
                source_context=self.source_context(raw), line_observer=lambda *_: capture)
            for key in ('text', 'lines', 'wholePageGroups', 'parserRuns', 'dualEvidenceRef'):
                self.assertEqual(value[key], original[key], key)
            self.assertEqual(value['regions'][:len(original['regions'])], original['regions'])
            self.assertEqual([r['originalText'] for r in value['regions'][len(original['regions']):]],
                             [' ', 'literal low', 'ordinary'])
            self.assertTrue(all(r['text'] == '' for r in value['regions'][len(original['regions']):]))
            self.assertEqual(value['coverage']['auxiliaryLineCoverage']['lineReread']['groups'][0]['disposition'],
                             'complete_child_group_active')

    def test_bound_detail_appends_only_geometry_after_both_providers_release(self):
        from test_ocr_layout_coverage import capture_fixture
        raw = self.image(); _, request = self.transport(raw)
        events = []
        @contextmanager
        def lifecycle(provider, remaining_seconds, receipt_dir):
            name = provider['taskBinding']['parser']; events.append('enter:' + name)
            yield {'mode': 'authored_lifecycle'}
            events.append('release:' + name)
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            with patch('complete_ocr.owned_native_provider', lifecycle):
                original = recognize_complete_page(raw, self.config(), store, request=request, source_context=self.source_context(raw))
                events.clear()
                def observer(actual, actual_store):
                    self.assertEqual(actual, raw); self.assertIs(actual_store, store)
                    self.assertEqual(events, ['enter:paddleocr-vl', 'release:paddleocr-vl', 'enter:mineru', 'release:mineru'])
                    events.append('cpu')
                    return capture_fixture(store, raw)
                value = recognize_complete_page(raw, self.config(), store, request=request,
                    source_context=self.source_context(raw), line_observer=observer)
            self.assertEqual(events.count('cpu'), 1)
            self.assertEqual(value['regions'][:-1], original['regions'])
            self.assertEqual(value['regions'][-1]['text'], '')
            self.assertEqual(value['regions'][-1]['originalText'], 'UNVERIFIED fixture')
            for key in ('text', 'lines', 'wholePageGroups', 'parserRuns', 'dualEvidenceRef'):
                self.assertEqual(value[key], original[key], key)
            coverage = value['coverage']['auxiliaryLineCoverage']
            self.assertEqual(coverage['addedRegions'], 1)
            self.assertEqual(coverage['synchronousNativeCancellation'], 'not_supported')
            self.assertFalse(coverage['sourceQualityAccepted'])

    def test_auxiliary_failure_preserves_successful_independent_provider_outputs(self):
        raw = self.image(); _, request = self.transport(raw)
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            def fail(*args): raise RuntimeError('Authored auxiliary failure')
            value = recognize_complete_page(raw, self.config(), store, request=request,
                source_context=self.source_context(raw), line_observer=fail)
            self.assertEqual(value['text'], 'A mandatory exception.')
            self.assertEqual(len(value['wholePageGroups'][0]['candidates']), 2)
            self.assertEqual(value['coverage']['auxiliaryLineCoverage']['status'], 'failed')
            self.assertIn('Authored auxiliary failure', value['coverage']['auxiliaryLineCoverage']['error'])

    def test_auxiliary_budget_is_gated_and_overrun_is_observed_without_claiming_cancellation(self):
        from test_ocr_layout_coverage import capture_fixture
        from unittest.mock import Mock
        raw = self.image()
        for expire_during in ('provider', 'cpu'):
            with self.subTest(expire_during=expire_during), tempfile.TemporaryDirectory() as directory:
                clock = [0.0]; store = ArtifactStore(directory); _, transport = self.transport(raw)
                def request(url, **kwargs):
                    response = transport(url, **kwargs)
                    if expire_during == 'provider' and url.endswith('/files/file-authored/content'):
                        clock[0] = 4.0
                    return response
                def observe(actual, actual_store):
                    clock[0] = 4.0
                    return capture_fixture(actual_store, actual)
                observer = Mock(side_effect=observe)
                with patch('complete_ocr.time.monotonic', side_effect=lambda: clock[0]):
                    value = recognize_complete_page(raw, self.config(), store, request=request,
                        source_context=self.source_context(raw), line_observer=observer)
                self.assertEqual(len(value['regions']), 1)
                self.assertEqual(value['text'], 'A mandatory exception.')
                coverage = value['coverage']['auxiliaryLineCoverage']
                self.assertTrue(coverage['deadlineExceeded'])
                self.assertEqual(coverage['synchronousNativeCancellation'], 'not_supported')
                if expire_during == 'provider':
                    observer.assert_not_called()
                    self.assertEqual(coverage['status'], 'not_started_budget_exhausted')
                else:
                    observer.assert_called_once()
                    self.assertEqual(coverage['status'], 'observed_after_deadline_unapplied')
                    self.assertEqual(coverage['addedRegions'], 0)

    def test_unbound_or_failed_detail_never_invokes_auxiliary_observer(self):
        raw = self.image(); _, request = self.transport(raw)
        from unittest.mock import Mock
        observer = Mock(side_effect=AssertionError('No auxiliary call expected'))
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            recognize_complete_page(raw, self.config(), store, request=request, line_observer=observer)
            _, failed_request = self.transport(raw, fail_mineru=True)
            with self.assertRaises(CompleteOcrError):
                recognize_complete_page(raw, self.config(), store, request=failed_request,
                    source_context=self.source_context(raw), line_observer=observer)
            observer.assert_not_called()

    def test_both_unavailable_pages_fail_closed_instead_of_completed_empty_text(self):
        raw = self.image()
        _, fixture = self.transport(raw)
        def request(url, **kwargs):
            response = fixture(url, **kwargs)
            if url.endswith('/layout-parsing'):
                value = json.loads(response)
                value['result']['layoutParsingResults'][0]['prunedResult']['page_index'] = 'unbound'
                return json.dumps(value).encode()
            if url.endswith('/files/file-authored/content'):
                stream = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(response)) as original, zipfile.ZipFile(stream, 'w') as archive:
                    for name in original.namelist():
                        content = original.read(name)
                        if name == 'middle_json.json':
                            value = json.loads(content); value['pages'][0]['page_idx'] = 'unbound'
                            content = json.dumps(value).encode()
                        archive.writestr(name, content)
                return stream.getvalue()
            return response
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            with self.assertRaisesRegex(CompleteOcrError, 'Requested parser page is unavailable'):
                recognize_complete_page(raw, self.config(), store, request=request)

    def test_missing_primary_page_fails_clearly_without_switching_to_other_parser(self):
        raw = self.image()
        _, fixture = self.transport(raw)
        def request(url, **kwargs):
            response = fixture(url, **kwargs)
            if url.endswith('/layout-parsing'):
                value = json.loads(response)
                value['result']['layoutParsingResults'][0]['prunedResult']['page_index'] = 'unbound'
                return json.dumps(value).encode()
            return response
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            with self.assertRaisesRegex(CompleteOcrError, 'Primary parser page is unavailable') as failure:
                recognize_complete_page(raw, self.config(), store, request=request)
            receipt = json.loads(store.read_asset(failure.exception.receipt))
            self.assertEqual(receipt['errorType'], 'ValueError')
            self.assertEqual(len(receipt['completedResponses']), 7)

    def test_http_capture_accepts_exact_wire_boundary_and_fails_closed_above_it(self):
        from complete_ocr import _request, HttpCaptureFailure
        raw = b'authored exact complete provider body'
        def respond(handler, _):
            self.reply(handler, 200, raw)
        with self.native_server(respond) as base:
            with patch('complete_ocr.MAX_RESPONSE_BYTES', len(raw)):
                self.assertEqual(_request(base, timeout=2), raw)
            with patch('complete_ocr.MAX_RESPONSE_BYTES', len(raw)-1):
                with self.assertRaisesRegex(HttpCaptureFailure, 'response exceeds capture limit') as failure:
                    _request(base, timeout=2)
            self.assertFalse(failure.exception.complete)
            self.assertEqual(failure.exception.raw, raw)

    @contextmanager
    def native_server(self, respond):
        class Handler(BaseHTTPRequestHandler):
            def handle_request(self):
                raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                respond(self, raw)
            do_POST = do_PUT = do_GET = do_DELETE = handle_request
            def log_message(self, *_):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
        worker.start()
        try:
            yield 'http://127.0.0.1:' + str(server.server_port)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)
            self.assertFalse(worker.is_alive())

    def reply(self, handler, status, raw):
        handler.send_response(status)
        handler.send_header('Content-Length', str(len(raw)))
        handler.end_headers()
        handler.wfile.write(raw)
        handler.wfile.flush()

    def image(self):
        stream = io.BytesIO()
        Image.new('RGB', (100, 200), 'white').save(stream, format='PNG')
        return stream.getvalue()

    def config(self):
        return {'paddleocr-vl': {'baseUrl': 'http://127.0.0.1:19101', 'identity': identity('paddleocr-vl')},
                'mineru': {'baseUrl': 'http://127.0.0.1:19102/v1', 'identity': identity('mineru')},
                'timeoutSeconds': 3}

    def source_context(self, raw):
        digest = hashlib.sha256(raw).hexdigest()
        return {'source': {'sourceFilename': 'authored-original.pdf', 'sourceSha256': 'c'*64,
                           'mediaType': 'application/pdf', 'pageCount': 5},
                'physicalPageNo': 3, 'imageSha256': digest, 'width': 100, 'height': 200,
                'originalPageTransform': {'kind': 'affine', 'matrix': [.5, 0, 0, 0, .5, 0],
                    'inputFrame': {'coordinateFrame': 'raster', 'coordinateUnit': 'pixel',
                        'width': 100, 'height': 200, 'artifactName': 'page.png', 'sha256': digest},
                    'targetFrame': {'coordinateFrame': 'original_page', 'coordinateUnit': 'pt',
                        'width': 50, 'height': 100, 'physicalPageNo': 3, 'sourceSha256': 'c'*64}}}

    def transport(self, raw, *, fail_mineru=False):
        requests = []
        def request(url, body=None, timeout=None, method=None):
            requests.append((url, body))
            if url.endswith('/layout-parsing'):
                self.assertEqual(base64.b64decode(body['file']), raw)
                self.assertEqual(body['fileType'], 1)
                self.assertFalse(body['restructurePages'])
                return json.dumps({'result': {'layoutParsingResults': [{'prunedResult': {
                    'page_index': 0, 'parsing_res_list': [
                        {'block_label': 'text', 'block_content': 'A mandatory exception.',
                         'block_bbox': [10, 20, 90, 40], 'block_order': 0}]
                }}]}}).encode()
            if fail_mineru:
                raise TimeoutError('Authored transport timeout')
            if url.endswith('/uploads'):
                self.assertEqual(body['bytes'], len(raw))
                self.assertEqual(body['sha256sum'], hashlib.sha256(raw).hexdigest())
                return b'{"id":"upload_authored","status":"pending"}'
            if url.endswith('/uploads/upload_authored/content'):
                self.assertEqual(method, 'PUT')
                self.assertEqual(body, raw)
                return b''
            if url.endswith('/uploads/upload_authored/complete'):
                return json.dumps({'id': 'upload_authored', 'status': 'completed', 'bytes': len(raw),
                    'sha256sum': hashlib.sha256(raw).hexdigest(), 'file': {'id': 'file-source'}}).encode()
            if url.endswith('/parse/jobs'):
                self.assertEqual(body['files'][0]['source'], {'type': 'file_id', 'file_id': 'file-source'})
                self.assertEqual(body['output_formats'], ['zip'])
                return b'{"job_id":"job_authored","status":"running"}'
            if url.endswith('/parse/jobs/job_authored'):
                return b'{"job_id":"job_authored","status":"completed","files":[{"file_id":"file-source","status":"completed","output_files":{"zip":{"file_id":"file-authored","bytes":1}}}]}'
            if url.endswith('/files/file-authored/content'):
                stream = io.BytesIO()
                page = {'page_idx': 0, 'blocks': [{'type': 'text', 'content': 'A different exception.',
                                                  'bbox': [.1, .1, .9, .2], 'index': 0}]}
                with zipfile.ZipFile(stream, 'w') as archive:
                    archive.writestr('middle_json.json', json.dumps({'schema': 'docvortex.middle',
                        'schema_version': '2.0', 'pages': [page]}))
                    archive.writestr('model_output.json', json.dumps({'schema': 'docvortex.model',
                        'schema_version': '2.0', 'pages': [[{'authored_raw_model_item': True}]], 'page_index_map': [0]}))
                return stream.getvalue()
            raise AssertionError(url)
        return requests, request

    def test_both_native_apis_receive_same_bytes_and_keep_disagreement_without_adoption(self):
        raw = self.image()
        requests, request = self.transport(raw)
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            value = recognize_complete_page(raw, self.config(), store, request=request)
            self.assertEqual(len(requests), 7)
            self.assertEqual(value['frame']['imageSha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual(value['adoptionStatus'], 'candidate_unverified')
            self.assertEqual(value['regions'][0]['originalText'], 'A mandatory exception.')
            self.assertEqual(len(value['parserRuns']), 2)
            bundle = json.loads(store.read_asset(value['dualEvidenceRef']))
            self.assertIsNone(bundle['canonicalSelection'])
            self.assertEqual(bundle['observations'][0]['comparisonStatus'], 'different_observation_unverified')
            self.assertFalse(value['coverage']['sourceQualityAccepted'])
            group = value['wholePageGroups'][0]
            self.assertIsNone(group['selectedParser'])
            self.assertEqual([candidate['parser'] for candidate in group['candidates']], ['paddleocr-vl', 'mineru'])
            self.assertEqual(value['regions'][0]['selectionStatus'], 'legacy_primary_candidate_unverified')

    def test_legal_large_raster_uses_vendor_upload_instead_of_one_mib_inline_limit(self):
        stream = io.BytesIO()
        Image.frombytes('RGB', (800, 600), random.Random(17).randbytes(800*600*3)).save(stream, format='PNG')
        raw = stream.getvalue()
        self.assertGreater(len(raw), 1024*1024)
        _, request = self.transport(raw)
        with tempfile.TemporaryDirectory() as directory:
            value = recognize_complete_page(raw, self.config(), ArtifactStore(directory), request=request)
        self.assertEqual(value['frame']['imageSha256'], hashlib.sha256(raw).hexdigest())

    def test_original_pdf_page_is_distinct_from_provider_single_image_page(self):
        raw = self.image()
        _, request = self.transport(raw)
        context = self.source_context(raw)
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            value = recognize_complete_page(raw, self.config(), store, request=request, source_context=context)
            bundle = json.loads(store.read_asset(value['dualEvidenceRef']))
        self.assertEqual(bundle['source']['sourceSha256'], 'c'*64)
        self.assertEqual(bundle['source']['pageCount'], 5)
        for run in bundle['runs']:
            self.assertEqual(run['pages'][0]['physicalPageNo'], 3)
            self.assertEqual(run['pages'][0]['providerInputPageNo'], 1)
        region = value['regions'][0]
        self.assertEqual(region['bbox'], [10, 20, 80, 20])
        self.assertEqual(region['originalGeometry']['polygonOriginal'], [[5, 10], [45, 10], [45, 20], [5, 20]])
        self.assertEqual(value['provenance']['sourceContext'], context)

    def test_same_dimensions_different_input_cannot_use_old_original_binding(self):
        raw = self.image()
        calls, request = self.transport(raw)
        context = self.source_context(raw)
        context['imageSha256'] = 'd'*64
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            recognize_complete_page(raw, self.config(), ArtifactStore(directory), request=request, source_context=context)
        self.assertEqual(calls, [])

    def test_unresolved_hostname_is_rejected_before_dispatching_a_budgeted_parser_call(self):
        config = self.config()
        config['paddleocr-vl']['baseUrl'] = 'http://authored-parser.invalid:19101'
        calls, request = self.transport(self.image())
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, 'Pinned parser IP'):
            recognize_complete_page(self.image(), config, ArtifactStore(directory), request=request)
        self.assertEqual(calls, [])

    def test_non_generic_source_policy_is_rejected_before_provider_execution(self):
        from test_whole_parser_groups import policy
        config = self.config(); config['wholeParserFeaturePolicy'] = policy()
        config['wholeParserFeaturePolicy']['rules'][0]['requires']['sourceSha256'] = 'a'*64
        calls, request = self.transport(self.image())
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            recognize_complete_page(self.image(), config, ArtifactStore(directory), request=request)
        self.assertEqual(calls, [])

    def test_unproven_first_provider_release_blocks_second_provider_and_retains_output(self):
        raw = self.image()
        calls, request = self.transport(raw)
        @contextmanager
        def unreleased(provider, remaining_seconds, receipt_dir):
            self.assertEqual(provider['identity']['parserVersion'], '1.6')
            yield {'mode': 'authored_owned_lifecycle', 'exitReceipt': {'status': 'release_unknown'}}
            raise RuntimeError('Authored owned tree release could not be proven')
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            with patch('complete_ocr.owned_native_provider', unreleased, create=True), self.assertRaises(CompleteOcrError) as failure:
                recognize_complete_page(raw, self.config(), store, request=request)
            receipt = json.loads(store.read_asset(failure.exception.receipt))
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(receipt['completedResponses']), 1)
        self.assertEqual(receipt['providerLifecycles'][0]['exitReceipt']['status'], 'release_unknown')

    def test_native_paddle_arrays_are_verified_and_retained_before_model_output_alias(self):
        raw = self.image()
        _, fixture = self.transport(raw)
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            from test_paddle_native_codec import native_result, wire
            manifest, arrays = native_result(raw, store)
            descriptor = manifest['generationAttempts'][0]['output']['members'][0]['artifact']
            def request(url, body=None, timeout=None, method=None):
                response = fixture(url, body, timeout, method)
                if url.endswith('/layout-parsing'):
                    value = json.loads(response)
                    value['result'].update(wire(manifest, arrays))
                    return json.dumps(value).encode()
                return response
            value = recognize_complete_page(raw, self.config(), store, request=request)
            bundle = json.loads(store.read_asset(value['dualEvidenceRef']))
            paddle = next(run for run in bundle['runs'] if run['parser'] == 'paddleocr-vl')
            self.assertIn('model_output', paddle['rawOutputs'])
            self.assertTrue(all('paddle/'+name in paddle['artifacts'] for name in arrays))
            self.assertFalse(value['coverage']['sourceQualityAccepted'])
            from test_whole_parser_groups import policy
            config = self.config(); config['wholeParserFeaturePolicy'] = policy()
            config['wholeParserFeaturePolicy']['rules'][0]['requires'] = {
                'hasTables': False, 'allGeometryBound': True, 'completeRawCapture': True}
            chosen = recognize_complete_page(raw, config, store, request=request, source_context=self.source_context(raw))
            self.assertEqual(chosen['wholePageGroups'][0]['selectedParser'], 'mineru')
            self.assertEqual(chosen['wholePageGroups'][0]['physicalPageNo'], 3)
            self.assertEqual(chosen['regions'][0]['channel'], 'mineru')
            self.assertEqual(chosen['regions'][0]['text'], 'A different exception.')
            self.assertEqual(chosen['regions'][0]['bbox'], [10, 20, 80, 20])
            self.assertEqual(chosen['regions'][0]['originalGeometry']['targetFrame']['coordinateUnit'], 'pt')
            self.assertFalse(chosen['coverage']['sourceQualityAccepted'])
            # Same metadata with changed native bytes cannot enter a complete run.
            descriptor['sha256'] = 'f'*64
            with self.assertRaises(CompleteOcrError):
                recognize_complete_page(raw, self.config(), store, request=request)

    def test_second_provider_failure_preserves_first_exact_response_and_actual_failure(self):
        raw = self.image()
        _, request = self.transport(raw, fail_mineru=True)
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            with self.assertRaises(CompleteOcrError) as failure:
                recognize_complete_page(raw, self.config(), store, request=request)
            receipt = json.loads(store.read_asset(failure.exception.receipt))
            self.assertEqual(receipt['status'], 'failed')
            self.assertEqual(len(receipt['completedResponses']), 1)
            self.assertEqual(receipt['failedProvider'], 'mineru')
            store.verify(receipt['completedResponses'][0]['response'])

    def test_actual_http_failure_keeps_status_body_and_exact_dispatched_input(self):
        raw = self.image()
        failed_body = b'{"error":"authored provider unavailable"}'
        sent = []
        def respond(handler, body):
            sent.append(body)
            self.reply(handler, 503, failed_body)
        with self.native_server(respond) as base, tempfile.TemporaryDirectory() as directory:
            config = self.config()
            config['paddleocr-vl']['baseUrl'] = base
            store = ArtifactStore(directory)
            with self.assertRaises(CompleteOcrError) as failure:
                recognize_complete_page(raw, config, store)
            receipt = json.loads(store.read_asset(failure.exception.receipt))
            attempt = receipt['attempts'][0]
            self.assertEqual(attempt['httpStatus'], 503)
            self.assertTrue(attempt['responseComplete'])
            self.assertEqual(store.read_asset(attempt['response']), failed_body)
            self.assertEqual(store.read_asset(receipt['input']), raw)
            self.assertEqual(store.read_asset(attempt['request']), sent[0])
            self.assertEqual(base64.b64decode(json.loads(sent[0])['file']), raw)

    def test_slow_trickle_obeys_total_deadline_and_preserves_incomplete_body(self):
        finished = threading.Event()
        def respond(handler, _):
            handler.send_response(200)
            handler.send_header('Content-Length', '100')
            handler.end_headers()
            try:
                for _ in range(100):
                    handler.wfile.write(b'x')
                    handler.wfile.flush()
                    time.sleep(.03)
            except OSError:
                pass
            finally:
                finished.set()
        with self.native_server(respond) as base, tempfile.TemporaryDirectory() as directory:
            config = self.config()
            config['paddleocr-vl']['baseUrl'] = base
            config['timeoutSeconds'] = .2
            store = ArtifactStore(directory)
            started = time.monotonic()
            with self.assertRaises(CompleteOcrError) as failure:
                recognize_complete_page(self.image(), config, store)
            self.assertLess(time.monotonic() - started, 2)
            receipt = json.loads(store.read_asset(failure.exception.receipt))
            attempt = receipt['attempts'][0]
            self.assertFalse(attempt['responseComplete'])
            captured = store.read_asset(attempt['response'])
            self.assertGreater(len(captured), 0)
            self.assertLess(len(captured), 100)
            self.assertEqual(captured, b'x' * len(captured))
            self.assertTrue(finished.wait(2))

    def test_closed_short_body_remains_incomplete_instead_of_a_successful_response(self):
        def respond(handler, _):
            handler.send_response(200)
            handler.send_header('Content-Length', '100')
            handler.end_headers()
            handler.wfile.write(b'partial')
            handler.wfile.flush()
        with self.native_server(respond) as base, tempfile.TemporaryDirectory() as directory:
            config = self.config()
            config['paddleocr-vl']['baseUrl'] = base
            store = ArtifactStore(directory)
            with self.assertRaises(CompleteOcrError) as failure:
                recognize_complete_page(self.image(), config, store)
            receipt = json.loads(store.read_asset(failure.exception.receipt))
            attempt = receipt['attempts'][0]
            self.assertFalse(attempt['responseComplete'])
            self.assertEqual(store.read_asset(attempt['response']), b'partial')
            self.assertEqual(len(receipt['completedResponses']), 0)

    def test_failed_owned_job_is_cancelled_once_through_actual_http_boundary(self):
        raw = self.image()
        _, fixture = self.transport(raw)
        cancelled = []
        def respond(handler, body):
            if handler.command == 'DELETE':
                cancelled.append(handler.path)
                self.reply(handler, 200, b'{"job_id":"job_authored","status":"canceled"}')
            elif handler.command == 'GET' and handler.path.endswith('/parse/jobs/job_authored'):
                self.reply(handler, 503, b'{"error":"authored job poll failed"}')
            else:
                value = body if handler.command == 'PUT' else json.loads(body) if body else None
                self.reply(handler, 200, fixture('http://authored' + handler.path, value, method=handler.command))
        with self.native_server(respond) as base, tempfile.TemporaryDirectory() as directory:
            config = self.config()
            config['paddleocr-vl']['baseUrl'] = base
            config['mineru']['baseUrl'] = base + '/v1'
            store = ArtifactStore(directory)
            with self.assertRaises(CompleteOcrError) as failure:
                recognize_complete_page(raw, config, store)
            receipt = json.loads(store.read_asset(failure.exception.receipt))
            self.assertEqual(receipt['ownedJobId'], 'job_authored')
            self.assertEqual(receipt['remoteCleanup']['status'], 'cancelled')
            self.assertEqual(cancelled, ['/v1/parse/jobs/job_authored'])
            self.assertEqual(receipt['attempts'][-1]['httpStatus'], 503)


if __name__ == '__main__':
    unittest.main()
