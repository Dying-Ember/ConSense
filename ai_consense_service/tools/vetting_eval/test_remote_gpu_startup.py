"""The source entry accepts an explicit remote CUDA recipe without a local GPU."""
import os
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient

import server


class RemoteGpuStartupTest(unittest.TestCase):
    @unittest.skipUnless(os.name == 'posix', 'private Unix GPU socket requires Linux')
    def test_remote_profile_is_explicit_and_does_not_probe_local_cuda(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / 'token'
            secret.write_text('authored-test-token', encoding='ascii')
            secret.chmod(0o600)
            manifest=Path(directory)/'profiles.json'
            raw=json.dumps({'schema':'consense.fixed-gpu-profiles.v1','profiles':{
                'document-embedding':'a'*64,'query-embedding-rerank':'b'*64}}).encode()
            manifest.write_bytes(raw)
            env = {'CONSENSE_FIXED_GPU_URL':'unix:///tmp/consense-test.sock',
                   'CONSENSE_FIXED_GPU_TOKEN_FILE':str(secret), 'CONSENSE_FIXED_GPU_TIMEOUT_SECONDS':'30',
                   'CONSENSE_FIXED_GPU_PROFILE_MANIFEST':str(manifest),
                   'CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256':hashlib.sha256(raw).hexdigest(),
                   'CONSENSE_MODEL_DEVICE':'cuda'}
            names = ('_remote_models', '_windows_runtime', 'model_runtime', 'embedding_model', 'rerank_model', 'encode_embeddings')
            saved = {name:getattr(server, name) for name in names}
            try:
                server._windows_runtime = None
                server._remote_models = None
                with patch.dict(os.environ, env, clear=True), patch.object(server, 'device', side_effect=AssertionError('local CUDA probe')):
                    server.configure_remote_gpu()
                    self.assertEqual('cuda', server.model_runtime('embedding')['device'])
                    self.assertIsNone(server.gpu_coordinator())
                    class Runtime:
                        def logical_identity(self, kind): return {'model':'authored'}
                        def inference_cap(self, kind): return 512
                        def signature_version(self): return 6
                    server._windows_runtime = Runtime()
                    with patch.object(server, 'retrieval_settings', return_value={}):
                        health = TestClient(server.app).get('/health')
                    self.assertEqual(200, health.status_code)
                    self.assertEqual('cuda', health.json()['device'])
                    self.assertIn('not a local GPU observation', health.json()['deviceScope'])
                    self.assertFalse(health.json()['remoteModelReady'])
                    with patch.object(server._remote_models.client, 'compute', side_effect=ValueError('worker runtime differs')) as compute:
                        with self.assertRaisesRegex(ValueError, 'request-owned model scope required'):
                            server.embedding_model().get_sentence_embedding_dimension()
                        compute.assert_not_called()
            finally:
                for name, value in saved.items(): setattr(server, name, value)

    def test_partial_and_conflicting_configuration_fail_closed(self):
        with patch.dict(os.environ, {'CONSENSE_FIXED_GPU_URL':'http://127.0.0.1:28684'}, clear=True):
            with self.assertRaises(ValueError): server.configure_remote_gpu()
        with tempfile.TemporaryDirectory() as directory:
            secret=Path(directory)/'token';secret.write_text('token', encoding='ascii');secret.chmod(0o600)
            manifest=Path(directory)/'profiles.json';raw=json.dumps({'schema':'consense.fixed-gpu-profiles.v1','profiles':{
                'document-embedding':'a'*64,'query-embedding-rerank':'b'*64}}).encode();manifest.write_bytes(raw)
            env={'CONSENSE_FIXED_GPU_URL':'unix:///tmp/consense-test.sock', 'CONSENSE_FIXED_GPU_TOKEN_FILE':str(secret),
                 'CONSENSE_FIXED_GPU_TIMEOUT_SECONDS':'30', 'CONSENSE_MODEL_DEVICE':'cuda', 'CONSENSE_GPU_STAGE_PREPARED':'old-job',
                 'CONSENSE_FIXED_GPU_PROFILE_MANIFEST':str(manifest),
                 'CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256':hashlib.sha256(raw).hexdigest()}
            with patch.dict(os.environ, env, clear=True):
                with self.assertRaises(ValueError): server.configure_remote_gpu()
            if os.name == 'posix':
                env.pop('CONSENSE_GPU_STAGE_PREPARED');env['CONSENSE_FIXED_GPU_URL']='http://127.0.0.1:28684'
                with patch.dict(os.environ, env, clear=True):
                    with self.assertRaisesRegex(ValueError,'private Unix socket'): server.configure_remote_gpu()
                env['CONSENSE_FIXED_GPU_URL']='unix:///tmp/consense-test.sock';secret.chmod(0o644)
                with patch.dict(os.environ, env, clear=True):
                    with self.assertRaises(ValueError): server.configure_remote_gpu()


if __name__ == '__main__': unittest.main()
