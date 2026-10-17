"""Actual NPY/CAS contract for native capture claims; no vendor model is loaded."""
import base64
import copy
import hashlib
import io
import json
import tempfile
import unittest
from unittest.mock import patch
import numpy as np
from artifact_store import ArtifactStore
from complete_ocr import _paddle_native_outputs


def native_result(raw, store):
    assets = {}
    def array(value, dtype, tensor=True):
        value = np.asarray(value)
        stream = io.BytesIO(); np.save(stream, value, allow_pickle=False)
        content = stream.getvalue(); name = 'arrays/' + hashlib.sha256(content).hexdigest() + '.npy'
        assets[name] = base64.b64encode(content).decode()
        return {'kind': 'actual_paddle_tensor' if tensor else 'actual_numpy_array', 'assetName': name,
            'artifact': store.put_bytes(content), 'dtype': dtype, 'arrayDtype': str(value.dtype),
            'shape': list(value.shape), 'place': 'Place(gpu:0)' if tensor else 'cpu_numpy_staging'}
    ids = array(np.array([[2, 3, 7]], dtype=np.int64), 'paddle.int64')
    scores = array(np.array([[.2]], dtype=np.float32), 'paddle.float32')
    prompt = array(np.array([[1, 4]], dtype=np.int64), 'paddle.int64')
    layout_input = array(np.ones((1, 3, 2, 2), dtype=np.float32), 'float32', False)
    layout_output = array(np.ones((1, 6), dtype=np.float32), 'float32', False)
    manifest = {'schema': 'consense.paddle-native-output-capture.v1',
        'captureStatus': 'complete_native_arrays_captured', 'input': store.put_bytes(raw), 'sourceQualityAccepted': False,
        'generationAttempts': [{'sequence': 1, 'status': 'completed', 'terminalStatus': 'eos_before_cap',
            'kwargs': {'max_new_tokens': 8},
            'actualResolvedGreedy': {'effectiveMaxNewTokens': 8, 'eosIds': [7], 'padId': 0, 'truncInput': True},
            'input': {'kind': 'mapping', 'members': [{'key': 'input_ids', 'value': prompt}]},
            'output': {'kind': 'tuple', 'members': [ids, scores]}}],
        'layoutRuns': [{'session': 1, 'status': 'completed',
            'inputs': {'image': layout_input}, 'outputs': {'layout': layout_output}}],
        'runtime': {'mainParameters': {'precisionStatus': 'actual_bf16_cuda_parameters', 'modelDir': 'authored-only',
            'modelClass': 'authored.Model', 'officialFp32Allowlist': [], 'officialFp32ActualParameterNames': [],
            'officialFp32ParameterCount': 0, 'bf16ParameterCount': 1, 'parameterCount': 1,
            'parameters': [{'name': 'model.weight', 'dtype': 'paddle.bfloat16', 'place': 'Place(gpu:0)', 'shape': [2, 2]}]},
            'layoutSessions': [{'session': 1, 'useGpu': True, 'device': 'gpu:0', 'inputs': ['image'], 'outputs': ['layout']}]}}
    return manifest, assets


def wire(manifest, assets):
    return {'rawOutputs': {'model_output': base64.b64encode(json.dumps(manifest).encode()).decode()},
        'rawAssets': assets, 'nativeCapture': {'status': manifest['captureStatus'],
            'inputSha256': manifest['input']['sha256'], 'runtime': copy.deepcopy(manifest['runtime'])}}


class PaddleNativeCodecTests(unittest.TestCase):
    def test_receiver_uses_the_same_exact_finite_array_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory); raw = b'authored exact native input'
            manifest, assets = native_result(raw, store)
            size = sum(len(base64.b64decode(value)) for value in assets.values())
            with patch('paddle_native_capture.MAX_NATIVE_ARRAY_BYTES', size, create=True):
                _paddle_native_outputs(wire(manifest, assets), hashlib.sha256(raw).hexdigest(), len(raw), store)
            with patch('paddle_native_capture.MAX_NATIVE_ARRAY_BYTES', size-1, create=True):
                with self.assertRaisesRegex(ValueError, 'array assets exceed complete capture limit'):
                    _paddle_native_outputs(wire(manifest, assets), hashlib.sha256(raw).hexdigest(), len(raw), store)

    def test_real_arrays_are_verified_and_false_native_statuses_are_refused(self):
        mutations = {
            'wrong_eos': lambda m: m['generationAttempts'][0]['actualResolvedGreedy'].update(eosIds=[9]),
            'cap_mismatch': lambda m: m['generationAttempts'][0]['kwargs'].update(max_new_tokens=9),
            'cap_boundary': lambda m: (m['generationAttempts'][0]['kwargs'].update(max_new_tokens=3),
                m['generationAttempts'][0]['actualResolvedGreedy'].update(effectiveMaxNewTokens=3)),
            'untruncated_prompt': lambda m: m['generationAttempts'][0]['actualResolvedGreedy'].update(truncInput=False),
            'wrong_pad': lambda m: m['generationAttempts'][0]['actualResolvedGreedy'].update(eosIds=[2], padId=0),
            'cpu_model': lambda m: m['runtime']['mainParameters']['parameters'][0].update(place='Place(cpu)'),
            'unofficial_fp32': lambda m: m['runtime']['mainParameters']['parameters'][0].update(dtype='paddle.float32'),
            'wrong_count': lambda m: m['runtime']['mainParameters'].update(parameterCount=2),
            'missing_score': lambda m: m['generationAttempts'][0]['output']['members'].pop(),
            'cpu_ids': lambda m: m['generationAttempts'][0]['output']['members'][0].update(place='Place(cpu)'),
            'layout_missing_input': lambda m: m['layoutRuns'][0]['inputs'].clear(),
            'layout_extra_name': lambda m: m['runtime']['layoutSessions'][0]['outputs'].append('missing')}
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory); raw = b'authored exact native input'
            manifest, assets = native_result(raw, store)
            outputs, _, capture = _paddle_native_outputs(wire(manifest, assets), hashlib.sha256(raw).hexdigest(), len(raw), store)
            self.assertIn('model_output', outputs)
            self.assertEqual(capture['status'], 'complete_native_array_bytes_verified')
            for name, mutate in mutations.items():
                altered = copy.deepcopy(manifest); mutate(altered)
                with self.subTest(case=name), self.assertRaises(ValueError):
                    _paddle_native_outputs(wire(altered, assets), hashlib.sha256(raw).hexdigest(), len(raw), store)
            altered_wire = wire(manifest, assets)
            altered_wire['nativeCapture']['runtime']['mainParameters']['modelDir'] = 'changed runtime'
            with self.assertRaises(ValueError):
                _paddle_native_outputs(altered_wire, hashlib.sha256(raw).hexdigest(), len(raw), store)


if __name__ == '__main__':
    unittest.main()
