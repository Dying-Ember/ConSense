import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from complete_ocr_config import prepare_complete_ocr


class CompleteOcrConfigTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = {'timeoutSeconds': 20, **{name: {'baseUrl': 'http://127.0.0.1:'+str(port),
            'identity': {'parserVersion': version, 'engine': name, 'models': [{'modelId': 'authored-fixture', 'revision': 'a'*40}],
                         'config': {'precision': 'bf16'}}} for name, port, version in
            (('paddleocr-vl', 18731, '3.6.0'), ('mineru', 18732, '4.0.10'))}}
        self.path = self.root / 'resolved.json'

    def tearDown(self):
        self.temp.cleanup()

    def prepare(self):
        self.path.write_text(json.dumps(self.config), encoding='utf-8')
        return prepare_complete_ocr(self.path, self.root / 'prepared')

    def test_emits_actual_configuration_sha_and_environment_without_runtime_claim(self):
        receipt = self.prepare()
        env = json.loads((self.root / 'prepared/environment.json').read_text())
        raw = Path(env['CONSENSE_COMPLETE_OCR_CONFIG']).read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), env['CONSENSE_COMPLETE_OCR_CONFIG_SHA256'])
        self.assertEqual(receipt['configSha256'], env['CONSENSE_COMPLETE_OCR_CONFIG_SHA256'])
        self.assertEqual('remote_handoff_unverified', receipt['handoffStatus'])
        self.assertFalse(receipt['targetAccepted'])
        self.assertEqual(self.config, json.loads(raw))

    def test_unknown_model_pin_and_reused_output_are_rejected(self):
        self.config['mineru']['identity']['models'][0]['revision'] = 'main'
        with self.assertRaisesRegex(ValueError, 'model'):
            self.prepare()
        self.assertFalse((self.root / 'prepared').exists())
        self.config['mineru']['identity']['models'][0]['revision'] = 'a'*40
        self.prepare()
        with self.assertRaisesRegex(ValueError, 'fresh'):
            self.prepare()

    def test_native_boot_requires_actual_sha_pinned_model_and_executable_assets(self):
        self.config['mineru']['ownedBoot'] = {'command': ['mineru'], 'cwd': str(self.root), 'env': {},
            'readyPath': '/v1/health', 'readyTimeoutSeconds': 10, 'cleanupTimeoutSeconds': 2}
        with self.assertRaisesRegex(ValueError, 'absolute executable'):
            self.prepare()
        self.assertFalse((self.root / 'prepared').exists())


if __name__ == '__main__':
    unittest.main()
