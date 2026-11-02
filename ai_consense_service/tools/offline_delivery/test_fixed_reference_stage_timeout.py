import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from remote_gpu_models import _trusted_stage_timeout
from request_stage import encoded


class FixedReferenceStageTimeoutTest(unittest.TestCase):
    def test_stage_budget_and_pins_remain_checked(self):
        profile = {'adapter': 'consense.model-session.v1'}
        profile_sha = hashlib.sha256(encoded(profile)).hexdigest()
        config = {'stages': {
            'fixed-reference-query': {'mode': 'model-session', 'timeoutSeconds': 900, 'profile': profile},
            'query-embedding-rerank': {'mode': 'model-session', 'timeoutSeconds': 600},
        }}
        raw = json.dumps(config).encode()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'gpu.json'
            path.write_bytes(raw)
            pins = {'profileSha256': profile_sha, 'executionSha256': 'e'*64}
            with patch('trusted_stage_pins.GPU_CONFIG_PATH', str(path)), \
                 patch('trusted_stage_pins.load_trusted_stage_pins', return_value=pins), \
                 patch('fixed_gpu_profiles.load_profiles', return_value={'fixed-reference-query': profile_sha}), \
                 patch.dict(os.environ, {'CONSENSE_FIXED_GPU_CONFIG_SHA256': hashlib.sha256(raw).hexdigest()}):
                self.assertEqual(_trusted_stage_timeout('fixed-reference-query', profile_sha, None), 900)
                self.assertEqual(_trusted_stage_timeout('query-embedding-rerank', profile_sha, 'e'*64), 600)
                with self.assertRaises(ValueError):
                    _trusted_stage_timeout('fixed-reference-query', 'a'*64, None)
                with self.assertRaises(ValueError):
                    _trusted_stage_timeout('query-embedding-rerank', profile_sha, None)
                with self.assertRaises(ValueError):
                    _trusted_stage_timeout('query-embedding-rerank', profile_sha, 'f'*64)
