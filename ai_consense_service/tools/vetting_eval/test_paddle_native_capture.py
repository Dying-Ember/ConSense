"""Native provider capture boundary doubles; no model, CUDA or source-quality run."""
import io
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import numpy as np
from artifact_store import ArtifactStore
from paddle_native_capture import NativePaddleCapture, NativePaddleCaptureError


class Tensor:
    def __init__(self, values, dtype='paddle.int64'):
        self.values = np.asarray(values)
        self.dtype, self.shape, self.place = dtype, self.values.shape, 'Place(gpu:0)'
    def numpy(self):
        return self.values


class NativePaddleCaptureTests(unittest.TestCase):
    def test_array_budget_accepts_exact_boundary_and_rejects_one_more_complete_array(self):
        arrays = [np.arange(count, dtype=np.int32) for count in (4, 5, 6)]
        sizes = []
        for array in arrays:
            stream = io.BytesIO(); np.save(stream, array, allow_pickle=False)
            sizes.append(len(stream.getvalue()))
        limit = sizes[0] + sizes[1]
        with tempfile.TemporaryDirectory() as directory:
            capture = NativePaddleCapture(ArtifactStore(directory), SimpleNamespace(Tensor=Tensor), 'authored-model')
            with patch('paddle_native_capture.MAX_NATIVE_ARRAY_BYTES', limit, create=True):
                captured = [capture.archive(array) for array in arrays[:2]]
                self.assertEqual(capture.byte_count, limit)
                self.assertEqual(capture.archive(arrays[0]), captured[0])
                with self.assertRaisesRegex(ValueError, 'capture limit exceeded'):
                    capture.archive(arrays[2])
            self.assertEqual(capture.byte_count, limit)
            self.assertEqual(len(capture.assets), 2)
            for source, record in zip(arrays, captured):
                self.assertTrue(np.array_equal(source, np.load(io.BytesIO(capture.assets[record['assetName']]), allow_pickle=False)))

    def test_official_class_fp32_modules_remain_native_while_other_parameters_require_bf16_cuda(self):
        class Model:
            _keep_in_fp32_modules = ['visual', 'mlp_AR']
            def named_parameters(self):
                return [('model.weight', Tensor(np.ones((2, 2), dtype=np.uint16), 'paddle.bfloat16')),
                        ('visual.encoder.weight', Tensor(np.ones((2, 2), dtype=np.float32), 'paddle.float32')),
                        ('mlp_AR.linear.weight', Tensor(np.ones((2, 2), dtype=np.float32), 'paddle.float32'))]
        predictor = SimpleNamespace(model_dir='authored-model')
        with tempfile.TemporaryDirectory() as directory:
            capture = NativePaddleCapture(ArtifactStore(directory), SimpleNamespace(Tensor=Tensor), 'authored-model')
            capture.parameters(Model(), predictor)
            runtime = capture.runtime['mainParameters']
            self.assertEqual(runtime['precisionStatus'], 'actual_bf16_cuda_with_official_fp32_parameters')
            self.assertEqual(runtime['officialFp32Allowlist'], ['visual', 'mlp_AR'])
            self.assertEqual(runtime['officialFp32ActualParameterNames'], ['visual.encoder.weight', 'mlp_AR.linear.weight'])
            self.assertEqual(runtime['bf16ParameterCount'], 1)
            self.assertEqual(runtime['officialFp32ParameterCount'], 2)
            self.assertEqual(runtime['weightIdentityStatus'], 'directory_bound_values_unverified')
            for name, tensor in Model().named_parameters():
                self.assertEqual(tensor.dtype, 'paddle.bfloat16' if name == 'model.weight' else 'paddle.float32')
            for name in ('notvisual.weight', 'mlp_AR_extra.weight', 'model.weight'):
                class Wrong(Model):
                    def named_parameters(self):
                        return [('core.weight', Tensor(np.ones((2, 2), dtype=np.uint16), 'paddle.bfloat16')),
                                (name, Tensor(np.ones((2, 2), dtype=np.float32), 'paddle.float32'))]
                with self.subTest(name=name), self.assertRaises(ValueError): capture.parameters(Wrong(), predictor)
            class CPU(Model):
                def named_parameters(self):
                    tensor = Tensor(np.ones((2, 2), dtype=np.float32), 'paddle.float32')
                    tensor.place = 'Place(cpu)'
                    return [('core.weight', Tensor(np.ones((2, 2), dtype=np.uint16), 'paddle.bfloat16')),
                            ('visual.weight', tensor)]
            with self.assertRaises(ValueError): capture.parameters(CPU(), predictor)

    def test_bf16_native_bits_are_preserved_and_fp32_conversion_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            capture = NativePaddleCapture(ArtifactStore(directory), SimpleNamespace(Tensor=Tensor), 'authored-model')
            bits = Tensor(np.array([0x3f80, 0xbf80], dtype=np.uint16), 'paddle.bfloat16')
            descriptor = capture.archive(bits)
            raw = capture.assets[descriptor['assetName']]
            self.assertEqual(np.load(io.BytesIO(raw), allow_pickle=False).tolist(), [0x3f80, 0xbf80])
            self.assertEqual(descriptor['bf16Encoding'], 'uint16_native_bits')
            with self.assertRaisesRegex(ValueError, 'BF16 native bits'):
                capture.archive(Tensor(np.array([1.0], dtype=np.float32), 'paddle.bfloat16'))

    def test_exact_generation_before_decode_and_complete_layout_io_are_captured(self):
        class Generation:
            generated_ids = [[2, 3, 7]]
            def greedy_search(self, input_ids, max_length, eos_token_id, pad_token_id, trunc_input=True):
                return Tensor(self.generated_ids), Tensor([[.2]])
        class Model(Generation):
            resolved_length = 10
            def named_parameters(self):
                return [('authored.weight', Tensor(np.zeros((2, 2), dtype=np.uint16), 'paddle.bfloat16'))]
            def generate(self, inputs, **kwargs):
                return self.greedy_search(inputs['input_ids'], self.resolved_length, [7], 0)
        class Predictor:
            model_dir = 'authored-model'
            def _build(self):
                return Model(), object()
        class Static:
            def __init__(self):
                self.input = None
            def get_input_names(self): return ['image']
            def get_output_names(self): return ['layout']
            def get_input_handle(self, _):
                return SimpleNamespace(copy_from_cpu=lambda array: setattr(self, 'input', array))
            def get_output_handle(self, _):
                return SimpleNamespace(copy_to_cpu=lambda: np.array([[0, .9, 10, 20, 90, 40]], dtype=np.float32))
            def run(self): return True
        config = SimpleNamespace(use_gpu=lambda: True, gpu_device_id=lambda: 0,
            prog_file=lambda: 'authored-layout.json', params_file=lambda: 'authored-layout.pdiparams')
        paddle = SimpleNamespace(Tensor=Tensor, inference=SimpleNamespace(create_predictor=lambda config: Static()))
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            capture = NativePaddleCapture(store, paddle, 'authored-model')
            with capture.install(SimpleNamespace(DocVLMLocalPredictor=Predictor), Generation):
                model, _ = Predictor()._build()
                static = paddle.inference.create_predictor(config)
                def pipeline():
                    static.get_input_handle('image').copy_from_cpu(np.ones((1, 3, 2, 2), dtype=np.float32))
                    static.run()
                    result = model.generate({'input_ids': Tensor([[1, 4]])}, max_new_tokens=8)
                    self.assertEqual(result[0].numpy().tolist(), [[2, 3, 7]])
                    return [{'actual_vendor_decoded_result': 'authored source observation'}]
                result, manifest, assets = capture.run(pipeline)
                Generation.generated_ids = [[2, 3, 7] + [0] * 6]
                with self.assertRaises(NativePaddleCaptureError) as failure:
                    capture.run(lambda: (static.get_input_handle('image').copy_from_cpu(np.ones((1, 3, 2, 2), dtype=np.float32)),
                        static.run(), model.generate({'input_ids': Tensor([[1, 4]])}, max_new_tokens=8)))
                self.assertIn('exceed', str(failure.exception).lower())
                self.assertTrue(failure.exception.manifest['generationAttempts'][0]['output'])
                Generation.generated_ids = [[2, 3, 7]]
                Model.resolved_length = 11
                with self.assertRaises(NativePaddleCaptureError) as failure:
                    capture.run(pipeline)
                self.assertIn('budget', str(failure.exception).lower())
            self.assertEqual(result, [{'actual_vendor_decoded_result': 'authored source observation'}])
            self.assertEqual(manifest['captureStatus'], 'complete_native_arrays_captured')
            self.assertEqual(manifest['generationAttempts'][0]['terminalStatus'], 'eos_before_cap')
            self.assertEqual(manifest['runtime']['mainParameters']['precisionStatus'], 'actual_bf16_cuda_parameters')
            output = manifest['generationAttempts'][0]['output']['members'][0]
            self.assertEqual(np.load(io.BytesIO(assets[output['assetName']]), allow_pickle=False).tolist(), [[2, 3, 7]])
            layout = manifest['layoutRuns'][0]
            self.assertEqual(set(layout['inputs']), {'image'})
            self.assertEqual(set(layout['outputs']), {'layout'})


if __name__ == '__main__':
    unittest.main()
