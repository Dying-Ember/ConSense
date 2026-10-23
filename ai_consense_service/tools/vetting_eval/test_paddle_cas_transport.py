"""Small authored native-array transport fixtures; no model or service is started."""
import base64
import copy
from contextlib import contextmanager
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import numpy as np

from fastapi.testclient import TestClient
from artifact_store import ArtifactStore
import complete_ocr
from complete_ocr import _paddle_native_outputs, recognize_complete_page, CompleteOcrError, HttpCaptureFailure
from paddle_native_capture import NativePaddleCaptureError
from paddle_native_server import NativePaddleRuntime, create_app, _native_assets
import test_complete_ocr as fixtures
import test_provider_task_cache as cache_fixtures
from test_paddle_native_codec import native_result, wire


TRANSPORT = 'consense.paddle-native-cas-assets.v1'


class PaddleCasTransportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = ArtifactStore(self.root/'provider')
        self.target = ArtifactStore(self.root/'coordinator')
        self.raw = fixtures.CompleteOcrTests().image()
        self.digest = hashlib.sha256(self.raw).hexdigest()
        self.manifest, self.arrays = native_result(self.raw, self.source)
        self.config = {'schema': TRANSPORT, 'artifactRoot': str(self.source.root)}
        self.provider = {'baseUrl': 'http://127.0.0.1:19101', 'ownedBoot': {},
                         'nativeCaptureTransport': self.config}

    def references(self, manifest=None):
        value = wire(manifest or self.manifest, self.arrays)
        value.pop('rawAssets')
        value.update(rawAssetTransport=TRANSPORT, rawAssetRefs={name:
            self.source.put_bytes(base64.b64decode(content), identity_scope='actual_native_array_npy_without_pickle')
            for name, content in self.arrays.items()})
        return value

    def receive(self, value, provider=None):
        return _paddle_native_outputs(value, self.digest, len(self.raw), self.target,
                                      provider=self.provider if provider is None else provider)

    def test_ref_and_legacy_preserve_exact_manifest_arrays_and_capture_claims(self):
        expected = _paddle_native_outputs(wire(self.manifest, self.arrays), self.digest,
                                         len(self.raw), self.target)
        with patch.object(self.target, 'put_bytes', wraps=self.target.put_bytes) as imports:
            actual = self.receive(self.references())
        self.assertEqual(actual, expected)
        self.assertEqual(imports.call_count, len(self.arrays))
        for name, encoded in self.arrays.items():
            self.assertEqual(actual[1]['paddle/'+name], base64.b64decode(encoded))

    def test_repeated_reference_imports_once_and_checks_each_reference(self):
        manifest = copy.deepcopy(self.manifest)
        first = manifest['generationAttempts'][0]['input']['members'][0]['value']
        manifest['anotherObservedReference'] = copy.deepcopy(first)
        value = self.references(manifest)
        with patch.object(self.target, 'put_bytes', wraps=self.target.put_bytes) as imports:
            self.receive(value)
        self.assertEqual(imports.call_count, len(self.arrays))
        manifest['anotherObservedReference']['shape'] = [99]
        with self.assertRaisesRegex(ValueError, 'dtype/shape'):
            self.receive(self.references(manifest))

    def test_provider_reuses_captured_refs_and_retains_orphaned_failure_array(self):
        arrays = {name: base64.b64decode(value) for name, value in self.arrays.items()}
        with patch.object(self.source, 'put_bytes', wraps=self.source.put_bytes) as writes:
            value = _native_assets(arrays, self.manifest, {'nativeCaptureTransport': self.config}, self.source)
        self.assertEqual(writes.call_count, 0)
        arrays['arrays/authored-partial.npy'] = b'authored partial native bytes'
        with patch.object(self.source, 'put_bytes', wraps=self.source.put_bytes) as writes:
            partial = _native_assets(arrays, self.manifest, {'nativeCaptureTransport': self.config}, self.source)
        self.assertEqual(writes.call_count, 1)
        self.assertEqual(self.source.read_asset(partial['rawAssetRefs']['arrays/authored-partial.npy']), arrays['arrays/authored-partial.npy'])
        self.assertEqual(set(value['rawAssetRefs']), set(self.arrays))

    def test_ref_mode_is_explicit_local_config_and_rejects_mixed_or_unknown_shapes(self):
        for provider in ({}, {**self.provider, 'ownedBoot': None},
                         {**self.provider, 'baseUrl': 'http://192.0.2.1:19101'},
                         {**self.provider, 'nativeCaptureTransport': {**self.config, 'artifactRoot': 'relative'}}):
            with self.subTest(provider=provider), self.assertRaises(ValueError):
                self.receive(self.references(), provider)
        for change in ({'rawAssetTransport': 'unknown.v9'}, {'rawAssets': self.arrays},
                       {'rawAssetRefs': []}):
            value = self.references(); value.update(change)
            with self.subTest(change=change), self.assertRaises(ValueError): self.receive(value)
        with self.assertRaises(ValueError): self.receive(wire(self.manifest, self.arrays))
        with self.assertRaises(ValueError): self.receive({})

    def test_missing_extra_escaped_and_changed_arrays_fail_closed(self):
        original = self.references()
        name, reference = next(iter(original['rawAssetRefs'].items()))
        cases = {}
        value = copy.deepcopy(original); value['rawAssetRefs'].pop(name); cases['missing_name'] = value
        value = copy.deepcopy(original); value['rawAssetRefs']['arrays/extra.npy'] = reference; cases['extra_name'] = value
        value = copy.deepcopy(original); value['rawAssetRefs'][name]['relativePath'] = '../outside.blob'; cases['escaped'] = value
        value = copy.deepcopy(original); value['rawAssetRefs'][name]['byteCount'] += 1; cases['wrong_size'] = value
        for label, value in cases.items():
            with self.subTest(case=label), self.assertRaises(ValueError): self.receive(value)
        path = self.source.root/reference['relativePath']; raw = path.read_bytes()
        path.write_bytes(b'x'*len(raw))
        with self.assertRaises(ValueError): self.receive(original)
        path.unlink()
        with self.assertRaises(ValueError): self.receive(original)
        path.write_bytes(raw)

    def test_descriptor_conflicts_limits_input_and_native_guards_are_not_bypassed(self):
        mutations = [
            lambda m: m['input'].update(sha256='0'*64),
            lambda m: m['runtime']['mainParameters']['parameters'][0].update(place='Place(cpu)'),
            lambda m: m['generationAttempts'][0]['actualResolvedGreedy'].update(eosIds=[9]),
            lambda m: m['generationAttempts'][0]['actualResolvedGreedy'].update(effectiveMaxNewTokens=3),
            lambda m: m['layoutRuns'][0]['inputs'].clear(),
            lambda m: m['generationAttempts'][0]['output']['members'][0]['artifact'].update(byteCount=1)]
        for mutate in mutations:
            manifest = copy.deepcopy(self.manifest); mutate(manifest)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError): self.receive(self.references(manifest))
        size = sum(len(base64.b64decode(content)) for content in self.arrays.values())
        with patch('paddle_native_capture.MAX_NATIVE_ARRAY_BYTES', size): self.receive(self.references())
        with patch('paddle_native_capture.MAX_NATIVE_ARRAY_BYTES', size-1), self.assertRaises(ValueError):
            self.receive(self.references())

    def test_native_bf16_bits_and_encoding_remain_required_in_ref_mode(self):
        stream = io.BytesIO(); np.save(stream, np.array([0x3f80, 0xbf80], dtype=np.uint16), allow_pickle=False)
        raw = stream.getvalue(); name = 'arrays/'+hashlib.sha256(raw).hexdigest()+'.npy'
        self.arrays[name] = base64.b64encode(raw).decode()
        row = {'kind': 'actual_paddle_tensor', 'assetName': name, 'artifact': self.source.put_bytes(raw),
            'arrayDtype': 'uint16', 'dtype': 'paddle.bfloat16', 'shape': [2],
            'place': 'Place(gpu:0)', 'bf16Encoding': 'uint16_native_bits'}
        self.manifest['generationAttempts'][0]['input']['members'].append({'key': 'pixels', 'value': row})
        self.receive(self.references())
        row['bf16Encoding'] = 'float32_conversion'
        with self.assertRaisesRegex(ValueError, 'BF16 bits'): self.receive(self.references())

    def runtime(self, failure=None):
        outer = self
        class Result:
            @property
            def json(self):
                if failure == 'vendor_json': raise ValueError('authored vendor failure')
                return {'res': {'page_index': 0, 'parsing_res_list': []}}
            def _to_markdown(self, **kwargs):
                if failure == 'vendor_markdown': raise ValueError('authored markdown failure')
                return {'markdown_texts': 'authored complete decoded text', 'markdown_images': {}}
            @property
            def img(self):
                if failure == 'vendor_images': raise ValueError('authored image failure')
                return {}
        class Capture:
            def run(self, callback):
                return callback(), copy.deepcopy(outer.manifest), {
                    name: base64.b64decode(raw) for name, raw in outer.arrays.items()}
        runtime = NativePaddleRuntime({'maxNewTokens': 8, 'nativeCaptureTransport': self.config}, self.source)
        runtime.capture = Capture()
        runtime.pipeline = type('Pipeline', (), {'_merged_paddlex_config': {'invalid': {1}} if failure == 'wire_serialization' else {},
            'predict_iter': lambda self, *a, **k: iter([] if failure == 'result_count' else [Result()])})()
        return runtime

    def test_provider_success_and_every_post_inference_failure_emit_refs_to_full_bytes(self):
        body = {'file': base64.b64encode(self.raw).decode(), **complete_ocr.PADDLE_OPTIONS}
        for failure in (None, 'result_count', 'vendor_json', 'vendor_markdown', 'vendor_images', 'wire_serialization'):
            runtime = self.runtime(failure)
            with TestClient(create_app({'nativeCaptureTransport': self.config}, self.source, lambda *_: runtime)) as client:
                response = client.post('/layout-parsing', json=body)
            self.assertEqual(response.status_code, 503 if failure else 200)
            value = response.json()['detail' if failure else 'result']
            self.assertEqual(value['rawAssetTransport'], TRANSPORT)
            self.assertNotIn('rawAssets', value)
            self.assertEqual(set(value['rawAssetRefs']), set(self.arrays))
            for name, reference in value['rawAssetRefs'].items():
                self.assertEqual(self.source.read_asset(reference), base64.b64decode(self.arrays[name]))
            if failure:
                manifest = json.loads(base64.b64decode(value['rawOutputs']['model_output']))
                self.assertEqual(manifest['failureStage'], failure)
                self.assertEqual(manifest['input']['sha256'], self.digest)

    def test_coordinator_retains_failed_http_capture_arrays_without_trying_mineru(self):
        runtime = self.runtime('vendor_json')
        app = create_app({'nativeCaptureTransport': self.config}, self.source, lambda *_: runtime)
        calls = []
        @contextmanager
        def lifecycle(*args, **kwargs):
            value = {'mode': 'owned_native', 'pid': 123, 'ownership': 'delegated_cgroup_v2'}
            try: yield value
            finally: value['exitReceipt'] = {'status': 'released'}
        with TestClient(app) as client:
            def request(url, body=None, timeout=None, method=None):
                calls.append(url)
                response = client.post('/layout-parsing', json=body)
                raise HttpCaptureFailure('authored native provider failure', response.content, response.status_code, True)
            config = fixtures.CompleteOcrTests().config(); config['paddleocr-vl'].update(self.provider)
            with patch('complete_ocr.owned_native_provider', lifecycle), self.assertRaises(CompleteOcrError) as caught:
                recognize_complete_page(self.raw, config, self.target, request=request)
        self.assertEqual(len(calls), 1)
        receipt = json.loads(self.target.read_asset(caught.exception.receipt))
        self.assertEqual(receipt['status'], 'failed')
        self.assertEqual(receipt['failedProvider'], 'paddleocr-vl')
        attempt = receipt['attempts'][0]
        self.assertEqual(attempt['httpStatus'], 503)
        capture = attempt['nativeFailureCapture']
        self.assertEqual(capture['status'], 'failed_capture_bytes_preserved')
        self.assertFalse(capture['sourceQualityAccepted'])
        failed = json.loads(self.target.read_asset(capture['manifest']))
        self.assertEqual(failed['failureStage'], 'vendor_json')
        for name, reference in capture['assets'].items():
            self.assertEqual(self.target.read_asset(reference), base64.b64decode(self.arrays[name]))

    def test_full_ref_run_is_cached_and_reuses_only_coordinator_bytes_without_new_http(self):
        fixture = cache_fixtures.ProviderTaskCacheTests()
        fixture.setUp(); self.addCleanup(fixture.doCleanups)
        fixture.config['paddleocr-vl']['nativeCaptureTransport'] = self.config
        calls, transport = fixture.fixture.transport(self.raw)
        def request(url, body=None, timeout=None, method=None):
            raw = transport(url, body, timeout, method)
            if url.endswith('/layout-parsing'):
                value = json.loads(raw); value['result'].update(self.references())
                return json.dumps(value).encode()
            return raw
        first = recognize_complete_page(self.raw, fixture.config, self.target,
            request=request, source_context=fixture.source())
        self.assertEqual(len(calls), 7)
        self.assertFalse(first['coverage']['sourceQualityAccepted'])
        first_paddle = json.loads(self.target.read_asset(first['dualEvidenceRef']))['runs'][0]
        self.assertEqual(first_paddle['completeness']['status'], 'complete')
        for reference in self.references()['rawAssetRefs'].values():
            (self.source.root/reference['relativePath']).unlink()
        def no_http(*args, **kwargs): self.fail('A successful exact task must not send another request')
        second = recognize_complete_page(self.raw, fixture.config, self.target,
            request=no_http, source_context=fixture.source())
        self.assertEqual(second['coverage']['completedProviderTaskHits'], 2)
        for key in ('text', 'lines', 'regions', 'wholePageGroups', 'parserRuns', 'dualEvidenceRef'):
            self.assertEqual(first[key], second[key], key)

    def test_failed_cas_publication_retains_explicit_failure_only_inline_bytes(self):
        runtime = self.runtime('vendor_json')
        body = {'file': base64.b64encode(self.raw).decode(), **complete_ocr.PADDLE_OPTIONS}
        with TestClient(create_app({'nativeCaptureTransport': self.config}, self.source, lambda *_: runtime)) as client:
            with patch('paddle_native_server._native_assets', side_effect=ValueError('authored CAS unavailable')):
                response = client.post('/layout-parsing', json=body)
        detail = response.json()['detail']
        self.assertEqual(response.status_code, 503)
        self.assertEqual(detail['rawAssetTransport'], 'consense.paddle-native-failed-inline-assets.v1')
        self.assertNotIn('rawAssetRefs', detail)
        capture = complete_ocr._paddle_failed_capture(detail, self.digest, len(self.raw), self.target, self.provider)
        self.assertEqual(capture['status'], 'failed_capture_bytes_preserved')
        for name, reference in capture['assets'].items():
            self.assertEqual(self.target.read_asset(reference), base64.b64decode(self.arrays[name]))
        value = self.references(); value.update(rawAssetTransport=detail['rawAssetTransport'], rawAssets=detail['rawAssets'],
                                               assetReferenceError=detail['assetReferenceError'])
        value.pop('rawAssetRefs')
        with self.assertRaises(ValueError): self.receive(value)


if __name__ == '__main__': unittest.main()
