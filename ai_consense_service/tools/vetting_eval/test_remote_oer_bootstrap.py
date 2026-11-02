"""Source service startup with a configured remote CUDA recipe; no inference."""
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from fastapi.testclient import TestClient
from oer_server import create_app
from oer_unified_server import create_unified_app
from remote_gpu_models import RemoteModels
import test_oer_server as fixture
from token_windows import WindowError


class RemoteOerBootstrapTest(unittest.TestCase):
    def setUp(self):
        fixture.OerHttpContractTest.setUp(self)
        self.client.close()
        self.backend._windows_runtime = None

    def tearDown(self):
        fixture.OerHttpContractTest.tearDown(self)

    def bind_remote(self, *, dtype='float32', serial=True):
        proxy=RemoteModels(self.backend,SimpleNamespace(),dtype=dtype,serial_offload=serial,timeout=30)
        proxy.require_request_scope()
        self.backend._remote_models=proxy
        self.backend.model_runtime=proxy.model_runtime
        self.backend.embedding_model=proxy.embedding_model
        self.backend.rerank_model=proxy.rerank_model
        self.backend.encode_embeddings=proxy.encode_embeddings
        return proxy

    def test_configured_remote_recipe_starts_unified_without_local_cuda(self):
        self.bind_remote()
        with patch.object(self.backend,'device',side_effect=AssertionError('local CUDA probe')):
            app=create_unified_app(self.policy,backend=self.backend,target_module='vetting')
            response=TestClient(app).get('/health')
        self.assertEqual(200,response.status_code)
        self.assertFalse(response.json()['remoteModelReady'])
        self.assertIn('not a local GPU observation',response.json()['deviceScope'])
        self.assertFalse(response.json()['loaded']['embedding'])

    def test_local_cpu_is_still_rejected(self):
        with patch.object(self.backend,'device',return_value='cpu'):
            with self.assertRaises(WindowError) as error:
                create_app(self.policy,backend=self.backend)
        self.assertEqual('OER_CUDA_REQUIRED',error.exception.kind)

    def test_wrong_remote_proxy_or_binding_cannot_use_local_cuda_to_bypass_gate(self):
        for mutation in ('fake_proxy','other_backend','model_runtime'):
            with self.subTest(mutation=mutation):
                self.backend._windows_runtime=None
                proxy=self.bind_remote()
                if mutation=='fake_proxy': self.backend._remote_models=SimpleNamespace()
                elif mutation=='other_backend': proxy.backend=SimpleNamespace()
                else: self.backend.model_runtime=lambda kind: {'device':'cuda'}
                with patch.object(self.backend,'device',return_value='cuda'):
                    with self.assertRaises(WindowError) as error:
                        create_app(self.policy,backend=self.backend)
                self.assertEqual('OER_STARTUP_RECIPE',error.exception.kind)

    def test_remote_recipe_cannot_change_precision_or_serial_offload(self):
        for dtype,serial in (('float16',True),('float32',False)):
            with self.subTest(dtype=dtype,serial=serial):
                self.backend._windows_runtime=None
                self.bind_remote(dtype=dtype,serial=serial)
                with patch.object(self.backend,'device',side_effect=AssertionError('local CUDA probe')):
                    with self.assertRaises(WindowError) as error:
                        create_app(self.policy,backend=self.backend)
                self.assertEqual('OER_STARTUP_RECIPE',error.exception.kind)


if __name__ == '__main__': unittest.main()
