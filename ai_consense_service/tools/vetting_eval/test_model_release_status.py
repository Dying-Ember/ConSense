"""Synthetic authenticated-status contract; no GPU, model or container calls."""
import copy
import hashlib
import json
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import time
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'ai_consense_service' / 'tools' / 'vetting_eval'))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from model_session_observer import ModelSessionObserver
import model_session_observer
from model_session_receipt import describe_batch
from token_windows import digest


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(',', ':'), allow_nan=False).encode()


def sha(value):
    return hashlib.sha256(encoded(value)).hexdigest()


class FakeClient:
    def __init__(self, status):
        self.status = status
        self.calls = []
        self.failure = None

    def _call(self, method, path, timeout, cancelled=None):
        self.calls.append((method, path, timeout))
        if self.failure:
            raise self.failure
        if cancelled is None or cancelled():
            raise ValueError('caller cancelled')
        return copy.deepcopy(self.status)


class FakeProxy:
    timeout = 3.0

    def __init__(self, client):
        self.client = client


class ModelReleaseStatusTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config_path = self.root / 'gpu.json'
        self.manifest_path = self.root / 'gpu-profiles.json'
        self.stage = 'document-embedding'
        self.operation = 'c' * 32
        self.profile = {'adapter': 'consense.model-session.v1', 'model': 'synthetic'}
        boot = {'command': ['/fixture/python', '/fixture/model.py'], 'cwd': '/runtime',
                'cgroupRoot': '/delegated/fixture', 'env': {},
                'runtimeAssets': [{'path': '/fixture/model.py', 'byteCount': 10, 'sha256': 'e' * 64}]}
        self.config = {'stages': {self.stage: {'mode': 'model-session',
                                              'profile': self.profile, 'boot': boot},
            'query-embedding-rerank': {'mode': 'model-session',
                'profile': {'adapter': 'consense.model-session.v1', 'model': 'query'},
                'boot': copy.deepcopy(boot)}}}
        self.config_sha = self.save(self.config_path, self.config)
        profiles = {stage: digest(row['profile']) for stage, row in self.config['stages'].items()}
        self.manifest_sha = self.save(self.manifest_path,
            {'schema': 'consense.fixed-gpu-profiles.v1', 'profiles': profiles})
        env = {'CONSENSE_FIXED_GPU_CONFIG_SHA256': self.config_sha,
               'CONSENSE_FIXED_GPU_PROFILE_MANIFEST': str(self.manifest_path),
               'CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256': self.manifest_sha}
        self.environ = patch.dict(os.environ, env, clear=True)
        self.environ.start(); self.addCleanup(self.environ.stop)
        self.config_bind = patch.object(model_session_observer, 'GPU_CONFIG_PATH', str(self.config_path))
        self.config_bind.start(); self.addCleanup(self.config_bind.stop)
        self.binding = {'requestId': 'a' * 32, 'sourceScopeSha256': 'b' * 64,
                        'modelKind': 'embedding', 'modelIdentity': {'model': 'synthetic'},
                        'runtime': {'device': 'cuda'}, 'inferenceCap': 512,
                        'deadlineBudgetMs': 5000, 'maxBatches': 2, 'maxTotalBytes': 1024}
        self.body, self.output = {'kind': 'embedding', 'texts': ['x']}, {'vectors': [[1.0]]}
        self.batch = describe_batch(0, self.body, self.output)
        batches = [{key: self.batch[key] for key in ('ordinal', 'inputSha256', 'outputSha256')}]
        self.binding_sha = 'd' * 64
        self.summary = {'operationId': self.operation, 'bindingSha256': self.binding_sha,
                        'requestId': self.binding['requestId'], 'batchCount': 1,
                        'batchesSha256': sha(batches),
                        'totalBytes': self.batch['inputBytes'] + self.batch['outputBytes'],
                        'status': 'computation_completed_release_unverified'}
        claims = {key: self.binding[key] for key in ('requestId', 'sourceScopeSha256', 'modelKind',
                                                     'inferenceCap', 'deadlineBudgetMs', 'maxBatches', 'maxTotalBytes')}
        claims.update(modelIdentitySha256=sha(self.binding['modelIdentity']),
                      runtimeSha256=sha(self.binding['runtime']))
        release = {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': [123]}
        self.receipt = {'schema': 'consense.fixed-model-session-receipt.v1',
                        'operationId': self.operation, 'stage': self.stage, 'sourceClaims': claims,
                        'bodySha256': hashlib.sha256(b'{}').hexdigest(),
                        'sourceSha256': sha(self.binding), 'profileSha256': digest(self.profile),
                        'bindingSha256': self.binding_sha, 'modelSummarySha256': sha(self.summary),
                        'summary': self.summary, 'batches': batches, 'release': release}
        self.status = {'operationId': self.operation, 'stage': self.stage,
                       'status': 'completed', 'configSha256': self.config_sha,
                       'configIdentityScope': 'config_file_bytes',
                       'bindingSha256': self.binding_sha, 'profileSha256': digest(self.profile),
                       'bodySha256': self.receipt['bodySha256'],
                       'sourceSha256': self.receipt['sourceSha256'],
                       'modelSummarySha256': self.receipt['modelSummarySha256'],
                       'release': copy.deepcopy(release)}
        self.client = FakeClient(self.status)

    @staticmethod
    def save(path, value):
        raw = encoded(value)
        path.write_bytes(raw)
        return hashlib.sha256(raw).hexdigest()

    def observer(self):
        observer = ModelSessionObserver({'requestId': 'a' * 32, 'deadline': time.monotonic() + 5,
                                         'cancelled': lambda: False},
                                        'b' * 64, self.stage, FakeProxy(self.client), 2, 1024)
        observer.trusted_execution_sha256()
        observer.bindings['embedding'] = copy.deepcopy(self.binding)
        observer.preflights['embedding'] = {'profileSha256': digest(self.profile)}
        observer.kind_operations['embedding'] = self.operation
        observer.records.append({'scopeOrdinal': 0, 'modelKind': 'embedding',
                                 'operationId': self.operation, **self.batch})
        return observer

    def test_authenticated_saved_status_accepts_exact_completion(self):
        observer = self.observer()
        self.assertIs(observer.verify_session(self.receipt), True)
        self.assertEqual([('GET', '/operations/' + self.operation)],
                         [(method, path) for method, path, _ in self.client.calls])
        self.assertTrue(0 < self.client.calls[0][2] <= 1)
        self.assertFalse(observer.receipt_checks[0]['gpuReleaseIndependentlyVerified'])

    def test_wrong_identity_or_release_is_unknown(self):
        for key, value in (('operationId', 'f' * 32), ('stage', 'query-embedding-rerank'),
                           ('status', 'settling'), ('configIdentityScope', 'canonical_config'),
                           ('configSha256', '0' * 64), ('bindingSha256', '0' * 64),
                           ('profileSha256', '0' * 64), ('bodySha256', '0' * 64),
                           ('sourceSha256', '0' * 64), ('modelSummarySha256', '0' * 64),
                           ('release', {'status': 'release_unknown'}),
                           ('release', {'status': 'released', 'cgroupPopulated': 0,
                                        'observedGpuPids': [456]})):
            with self.subTest(key=key, value=value):
                self.client.status = {**self.status, key: value}
                self.assertIs(self.observer().verify_session(self.receipt), False)
        self.client.status = self.status

    def test_changed_boot_missing_bind_and_failed_status_read_are_unknown(self):
        observer = self.observer()
        self.config['stages'][self.stage]['boot']['env']['MODEL'] = 'changed'
        self.client.status = {**self.status, 'configSha256': self.save(self.config_path, self.config)}
        self.assertIs(observer.verify_session(self.receipt), False)

        del self.config['stages'][self.stage]['boot']['env']['MODEL']
        self.save(self.config_path, self.config)
        self.client.status = self.status
        observer = self.observer()
        with patch.object(model_session_observer, 'GPU_CONFIG_PATH', str(self.root / 'missing.json')):
            self.assertIs(observer.verify_session(self.receipt), False)
        for failure in (TimeoutError('status timeout'), OSError('GPU UDS closed')):
            with self.subTest(failure=type(failure).__name__):
                self.client.failure = failure
                self.assertIs(self.observer().verify_session(self.receipt), False)


if __name__ == '__main__':
    unittest.main()
