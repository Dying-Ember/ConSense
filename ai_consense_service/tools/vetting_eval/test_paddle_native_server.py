"""Normal native provider HTTP/config contracts; no vendor model is loaded."""
import base64
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from fastapi.testclient import TestClient
from PIL import Image
import numpy as np
from artifact_store import ArtifactStore
from paddle_native_capture import NativePaddleCaptureError
from paddle_native_server import NativePaddleRuntime, create_app, load_config


class NativePaddleServerTests(unittest.TestCase):
    def test_owned_raster_work_file_is_released_on_success_capture_and_postprocessing_failure(self):
        raw, _ = self.body()
        for failure in (None, 'capture', 'postprocessing'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                store = ArtifactStore(directory)
                paths = []
                class Result:
                    json = {'res': {'authored': True}}
                    img = {}
                    def _to_markdown(self, **kwargs):
                        if failure == 'postprocessing': raise ValueError('Authored postprocessing failure')
                        return {'markdown_texts': 'authored', 'markdown_images': {}}
                class Capture:
                    def run(self, callback):
                        values = callback()
                        if failure == 'capture':
                            raise NativePaddleCaptureError('Authored capture failure', {'captureStatus': 'failed'}, {})
                        return values, {'captureStatus': 'complete_native_arrays_captured', 'runtime': {}}, {}
                def predict(path, **kwargs):
                    path = Path(path); paths.append(path)
                    self.assertEqual(path.read_bytes(), raw)
                    path.write_bytes(b'Authored provider may modify its private input')
                    return iter([Result()])
                runtime = NativePaddleRuntime({'maxNewTokens': 8}, store)
                runtime.capture = Capture()
                runtime.pipeline = type('Pipeline', (), {'predict_iter': lambda _, path, **kwargs: predict(path, **kwargs)})()
                if failure:
                    with self.assertRaises(NativePaddleCaptureError): runtime.recognize(raw)
                else: runtime.recognize(raw)
                self.assertEqual(len(paths), 1)
                self.assertFalse(paths[0].exists())
                descriptor = store.put_bytes(raw, identity_scope='actual_native_provider_input_raster')
                self.assertEqual(store.read_asset(descriptor), raw)

    def test_every_post_inference_failure_retains_native_manifest_arrays_and_exact_input(self):
        raw, body = self.body()
        stream = io.BytesIO(); np.save(stream, np.array([[2, 3, 7]], dtype=np.int64), allow_pickle=False)
        actual_array = stream.getvalue(); name = 'arrays/' + hashlib.sha256(actual_array).hexdigest() + '.npy'
        for stage in ('result_count', 'vendor_json', 'vendor_markdown', 'vendor_images', 'wire_serialization'):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as directory:
                store = ArtifactStore(directory)
                ref = store.put_bytes(actual_array, identity_scope='authored_native_array_fixture')
                native = {'schema': 'consense.paddle-native-output-capture.v1',
                    'captureStatus': 'complete_native_arrays_captured', 'sourceQualityAccepted': False,
                    'generationAttempts': [{'output': {'assetName': name, 'artifact': ref}}]}
                class Result:
                    @property
                    def json(self):
                        if stage == 'vendor_json': raise ValueError('AUTHORED vendor JSON failure')
                        return {'res': {'authored': 'complete decoded observation'}}
                    def _to_markdown(self, **kwargs):
                        if stage == 'vendor_markdown': raise ValueError('AUTHORED markdown failure')
                        return {'markdown_texts': 'authored', 'markdown_images': {}}
                    @property
                    def img(self):
                        if stage == 'vendor_images': raise ValueError('AUTHORED visualization failure')
                        return {}
                class Capture:
                    def run(self, callback): return callback(), native, {name: actual_array}
                runtime = NativePaddleRuntime({'maxNewTokens': 8}, store)
                runtime.capture = Capture()
                runtime.pipeline = type('Pipeline', (), {
                    '_merged_paddlex_config': {'bad': {1, 2}} if stage == 'wire_serialization' else {'authored': True},
                    'predict_iter': lambda self, *args, **kwargs: iter([] if stage == 'result_count' else [Result()])})()
                with TestClient(create_app({}, store, lambda config, store: runtime)) as client:
                    response = client.post('/layout-parsing', json=body)
                self.assertEqual(response.status_code, 503)
                detail = response.json()['detail']
                manifest_bytes = base64.b64decode(detail['rawOutputs']['model_output'], validate=True)
                failed = json.loads(manifest_bytes)
                self.assertEqual(failed['failureStage'], stage)
                self.assertEqual(failed['input']['sha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(failed['captureStatus'], 'failed_native_capture')
                self.assertEqual(failed['nativeCaptureStatusBeforeProviderFailure'], 'complete_native_arrays_captured')
                self.assertEqual(failed['generationAttempts'], native['generationAttempts'])
                self.assertEqual(base64.b64decode(detail['rawAssets'][name], validate=True), actual_array)
                self.assertEqual(store.read_asset(detail['receipt']), manifest_bytes)
                self.assertFalse(failed['sourceQualityAccepted'])

    def body(self):
        stream = io.BytesIO(); Image.new('RGB', (90, 60), 'white').save(stream, format='PNG')
        raw = stream.getvalue()
        return raw, {'file': base64.b64encode(raw).decode(), 'fileType': 1, 'useLayoutDetection': True,
            'useDocOrientationClassify': False, 'useDocUnwarping': False, 'restructurePages': False,
            'markdownIgnoreLabels': [], 'visualize': True}

    def test_exact_normal_raster_and_failed_native_output_remain_recoverable(self):
        calls = []
        class Runtime:
            def __init__(self, config, store): pass
            def recognize(self, raw):
                calls.append(raw)
                raise NativePaddleCaptureError('Authored native generation reached its cap',
                    {'captureStatus': 'failed_native_capture', 'sourceQualityAccepted': False},
                    {'arrays/actual.npy': b'authored actual incomplete native array bytes'})
        raw, body = self.body()
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            client = TestClient(create_app({}, store, Runtime))
            self.assertFalse(client.get('/health').json()['modelReady'])
            self.assertEqual(calls, [])
            invalid = {**body, 'fileType': 2}
            self.assertEqual(client.post('/layout-parsing', json=invalid).status_code, 422)
            self.assertEqual(calls, [])
            response = client.post('/layout-parsing', json=body)
            self.assertEqual(response.status_code, 503)
            self.assertEqual(calls, [raw])
            detail = response.json()['detail']
            self.assertEqual(json.loads(store.read_asset(detail['receipt']))['captureStatus'], 'failed_native_capture')
            self.assertEqual(base64.b64decode(detail['rawAssets']['arrays/actual.npy']), b'authored actual incomplete native array bytes')

    def test_configuration_requires_complete_exact_assets_and_rejects_late_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets = []
            for name in ('vlm/model.bin', 'layout/inference.pdiparams', 'font.ttf'):
                path = root / name; path.parent.mkdir(exist_ok=True)
                path.write_bytes(('authored '+name).encode())
                assets.append({'path': str(path), 'byteCount': path.stat().st_size,
                    'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
            value = {'schema': 'consense.paddle-native-provider.v1', 'precision': 'bf16',
                'vlmModelDir': str(root / 'vlm'), 'layoutModelDir': str(root / 'layout'),
                'fontPath': str(root / 'font.ttf'), 'assets': assets, 'maxNewTokens': 8192}
            config = root / 'config.json'; config.write_text(json.dumps(value), encoding='utf-8')
            digest = hashlib.sha256(config.read_bytes()).hexdigest()
            self.assertEqual(load_config(config, digest), value)
            with self.assertRaises(ValueError): load_config(config, '0'*64)
            (root / 'vlm' / 'model.bin').write_bytes(b'late changed source weights')
            with self.assertRaises(ValueError): load_config(config, digest)


if __name__ == '__main__':
    unittest.main()
