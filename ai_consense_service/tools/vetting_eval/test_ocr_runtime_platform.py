"""CPU identity OCR must not require a second GPU framework on Linux."""
import builtins
import types
import unittest
from unittest.mock import patch

import ocr_runtime


class OcrRuntimePlatformTests(unittest.TestCase):
    def test_cuda_health_keeps_actual_framework_memory(self):
        import server
        torch = types.ModuleType('torch')
        torch.cuda = types.SimpleNamespace(memory_allocated=lambda: 3 * 2**20)
        with patch.object(server, '_selected_device', 'cuda'), \
                patch.object(server, 'gpu_coordinator', return_value=None), \
                patch.dict('sys.modules', {'torch': torch}):
            health = server.health()
        self.assertEqual(health['device'], 'cuda')
        self.assertEqual(health['cudaAllocatedMiB'], 3.0)

    def test_explicit_cpu_health_device_does_not_import_torch(self):
        import server
        original_import = builtins.__import__

        def import_without_torch(name, *args, **kwargs):
            if name == 'torch':
                raise ImportError('OCR-only coordinator does not install Torch')
            return original_import(name, *args, **kwargs)

        with patch.object(server, '_selected_device', None), \
                patch.dict(server.os.environ, {'CONSENSE_MODEL_DEVICE': 'cpu'}), \
                patch('builtins.__import__', import_without_torch):
            self.assertEqual(server.device(), 'cpu')
            with patch.object(server, 'gpu_coordinator', return_value=None):
                health = server.health()
            self.assertEqual(health['device'], 'cpu')
            self.assertIsNone(health['cudaAllocatedMiB'])

    def test_linux_cpu_ocr_does_not_import_torch(self):
        original_import = builtins.__import__
        calls = []
        rapidocr = types.ModuleType('rapidocr')
        rapidocr.RapidOCR = lambda **kwargs: kwargs

        def import_without_torch(name, *args, **kwargs):
            calls.append(name)
            if name == 'torch':
                raise ImportError('Linux CPU OCR has no Torch dependency')
            return original_import(name, *args, **kwargs)

        with patch.object(ocr_runtime.os, 'name', 'posix'), patch.dict('sys.modules', {
                'onnxruntime': types.ModuleType('onnxruntime'), 'rapidocr': rapidocr}), \
                patch('builtins.__import__', import_without_torch):
            result = ocr_runtime.create_ocr_engine()
        self.assertNotIn('torch', calls)
        self.assertEqual(result['params'], ocr_runtime.ocr_parameters())


if __name__ == '__main__':
    unittest.main()
