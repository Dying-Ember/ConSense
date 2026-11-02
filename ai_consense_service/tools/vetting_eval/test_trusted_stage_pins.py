"""Synthetic pin checks only; no GPU, model, or container calls."""
import copy
import hashlib
import json
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'ai_consense_service' / 'tools' / 'vetting_eval'))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import model_session_observer
from token_windows import WindowError, digest
from trusted_stage_pins import load_trusted_stage_pins


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode()


class TrustedStagePinsTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config_path = self.root / 'gpu.json'
        self.manifest_path = self.root / 'gpu-profiles.json'
        boot = {'command': ['/fixture/bin/python3', '/fixture/worker.py'], 'cwd': '/runtime',
                'cgroupRoot': '/delegated/fixture', 'env': {'MODEL': 'fixture'},
                'runtimeAssets': [{'path': '/fixture/worker.py', 'byteCount': 12, 'sha256': 'a' * 64}]}
        self.config = {'stages': {
            'document-embedding': {'mode': 'job', 'profile': {'model': 'document'}, 'boot': copy.deepcopy(boot)},
            'query-embedding-rerank': {'mode': 'model-session', 'profile': {'model': 'query'},
                                       'boot': copy.deepcopy(boot)}}}
        self.config_sha = self.save(self.config_path, self.config)
        self.manifest_sha = self.save(self.manifest_path, {
            'schema': 'consense.fixed-gpu-profiles.v1',
            'profiles': {stage: digest(row['profile']) for stage, row in self.config['stages'].items()}})

    @staticmethod
    def save(path, value):
        raw = encoded(value)
        path.write_bytes(raw)
        return hashlib.sha256(raw).hexdigest()

    def load(self, stage='document-embedding', config_sha=None, manifest_sha=None):
        return load_trusted_stage_pins(stage, self.config_path, config_sha or self.config_sha,
                                       self.manifest_path, manifest_sha or self.manifest_sha)

    def environment(self):
        return {'CONSENSE_FIXED_GPU_CONFIG_SHA256': self.config_sha,
                'CONSENSE_FIXED_GPU_PROFILE_MANIFEST': str(self.manifest_path),
                'CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256': self.manifest_sha}

    def test_two_model_stages_and_observer(self):
        with patch.dict(os.environ, self.environment(), clear=True), \
                patch.object(model_session_observer, 'GPU_CONFIG_PATH', str(self.config_path)):
            for stage in self.config['stages']:
                with self.subTest(stage=stage):
                    pins = self.load(stage)
                    self.assertEqual(digest(self.config['stages'][stage]['profile']), pins['profileSha256'])
                    self.assertEqual(pins, model_session_observer._trusted_stage_pins(stage))
                    observer = model_session_observer.ModelSessionObserver({}, '', stage, None, 1, 1)
                    self.assertEqual(pins['executionSha256'], observer.trusted_execution_sha256())

    def test_missing_or_wrong_independent_pins_fail_closed(self):
        with patch.object(model_session_observer, 'GPU_CONFIG_PATH', str(self.config_path)):
            for env in ({}, {**self.environment(), 'CONSENSE_FIXED_GPU_CONFIG_SHA256': '0' * 64},
                        {**self.environment(), 'CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256': '0' * 64}):
                with self.subTest(env=sorted(env)), patch.dict(os.environ, env, clear=True):
                    self.assertIsNone(model_session_observer._trusted_stage_pins('document-embedding'))
                    observer = model_session_observer.ModelSessionObserver({}, '', 'document-embedding', None, 1, 1)
                    with self.assertRaises(WindowError) as failure:
                        observer.trusted_execution_sha256()
                    self.assertEqual('MODEL_SESSION_TRUSTED_PINS_REQUIRED', failure.exception.kind)
        with self.assertRaises(ValueError):
            self.load('business-llm')

    def test_config_and_stage_tampering_fail_closed(self):
        self.config_path.write_bytes(self.config_path.read_bytes() + b' ')
        with self.assertRaisesRegex(ValueError, 'identity differs'):
            self.load()
        self.config_sha = self.save(self.config_path, self.config)
        self.config['stages']['query-embedding-rerank']['profile']['model'] = 'changed'
        self.config_sha = self.save(self.config_path, self.config)
        with self.assertRaisesRegex(ValueError, 'profile differs'):
            self.load('query-embedding-rerank')

    def test_boot_change_requires_config_repin_and_changes_execution_pin(self):
        before = self.load()
        self.config['stages']['document-embedding']['boot']['env']['MODEL'] = 'changed'
        self.save(self.config_path, self.config)
        with self.assertRaisesRegex(ValueError, 'identity differs'):
            self.load()
        self.config_sha = hashlib.sha256(self.config_path.read_bytes()).hexdigest()
        after = self.load()
        self.assertEqual(before['profileSha256'], after['profileSha256'])
        self.assertNotEqual(before['executionSha256'], after['executionSha256'])
        del self.config['stages']['document-embedding']['boot']['runtimeAssets']
        self.config_sha = self.save(self.config_path, self.config)
        with self.assertRaisesRegex(ValueError, 'boot is incomplete'):
            self.load()


if __name__ == '__main__':
    unittest.main()
