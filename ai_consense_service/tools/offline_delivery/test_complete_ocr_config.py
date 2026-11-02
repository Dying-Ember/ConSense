import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import sys
import os

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

    def test_local_cas_transport_is_explicit_pinned_and_reported_in_generated_plan(self):
        provider = self.config['paddleocr-vl']
        model = self.root/'authored-model.bin'; model.write_bytes(b'authored small identity fixture')
        executable = Path(sys.executable).resolve()
        provider['identity']['models'][0]['assetPaths'] = [str(model)]
        provider['ownedBoot'] = {'command': [str(executable)], 'cwd': str(self.root), 'env': {},
            'precision': 'bf16', 'readyPath': '/', 'readyTimeoutSeconds': 5, 'cleanupTimeoutSeconds': 2,
            'runtimeAssets': [{'path': str(path), 'byteCount': path.stat().st_size,
                'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'kind': kind}
                for path, kind in ((executable, 'executable'), (model, 'model'))]}
        provider['nativeCaptureTransport'] = {'schema': 'consense.paddle-native-cas-assets.v1',
                                              'artifactRoot': str(self.root)}
        receipt = self.prepare()
        self.assertEqual(receipt['providers']['paddleocr-vl']['nativeCaptureTransport'], provider['nativeCaptureTransport'])
        emitted = (self.root/'prepared/complete-ocr.json').read_bytes()
        self.assertEqual(json.loads(emitted)['paddleocr-vl']['nativeCaptureTransport'], provider['nativeCaptureTransport'])
        self.assertEqual(receipt['configSha256'], hashlib.sha256(emitted).hexdigest())

    def test_cas_transport_unknown_root_mode_or_remote_provider_is_rejected_before_emission(self):
        provider = self.config['paddleocr-vl']
        provider['nativeCaptureTransport'] = {'schema': 'consense.paddle-native-cas-assets.v1', 'artifactRoot': str(self.root)}
        for change in ({'nativeCaptureTransport': {'schema': 'unknown', 'artifactRoot': str(self.root)}},
                       {'nativeCaptureTransport': {'schema': 'consense.paddle-native-cas-assets.v1', 'artifactRoot': 'relative'}},
                       {'baseUrl': 'http://192.0.2.1:19101'}, {'ownedBoot': None}):
            original = dict(provider); provider.update(change)
            with self.subTest(change=change), self.assertRaises(ValueError): self.prepare()
            self.assertFalse((self.root/'prepared').exists())
            provider.clear(); provider.update(original)

    @unittest.skipIf(os.name == 'nt', 'fixed GPU uses POSIX private token permissions')
    def test_fixed_gpu_configuration_is_reported_without_token_contents(self):
        token = self.root/'token'; token.write_text('private-token'); os.chmod(token, 0o600)
        for name, stage, port in (('paddleocr-vl','paddle',28771), ('mineru','mineru',28772)):
            self.config[name]['baseUrl'] = f'http://127.0.0.1:{port}'
            self.config[name]['ownedFixed'] = {'gatewayProfileSha256':'d'*64, 'stage':stage, 'socketPath':str(self.root/'gateway.sock'),
                'tokenFile':str(token), 'readyTimeoutSeconds':10, 'cleanupTimeoutSeconds':2}
        receipt = self.prepare()
        self.assertEqual(receipt['handoffStatus'], 'owned_native_release_required')
        self.assertEqual(receipt['providers']['mineru']['ownedFixed'], self.config['mineru']['ownedFixed'])
        self.assertNotIn('private-token', json.dumps(receipt))

    @unittest.skipIf(os.name == 'nt', 'POSIX permission and symlink checks')
    def test_fixed_gpu_rejects_shared_token_and_unsafe_socket_path(self):
        token = self.root/'token'; token.write_text('private-token'); os.chmod(token, 0o644)
        provider = self.config['paddleocr-vl']; provider['baseUrl'] = 'http://127.0.0.1:28771'
        provider['ownedFixed'] = {'gatewayProfileSha256':'d'*64, 'stage':'paddle', 'socketPath':str(self.root/'gateway.sock'),
            'tokenFile':str(token), 'readyTimeoutSeconds':10, 'cleanupTimeoutSeconds':2}
        with self.assertRaisesRegex(ValueError, 'private'): self.prepare()
        os.chmod(token, 0o600)
        provider['ownedFixed']['socketPath'] = 'relative.sock'
        with self.assertRaisesRegex(ValueError, 'socket path'): self.prepare()
        provider['ownedFixed']['socketPath'] = str(self.root/'gateway.sock')
        link = self.root/'token-link'; link.symlink_to(token); provider['ownedFixed']['tokenFile'] = str(link)
        with self.assertRaisesRegex(ValueError, 'token file'): self.prepare()

    @unittest.skipIf(os.name == 'nt', 'fixed GPU uses POSIX private token permissions')
    def test_fixed_gpu_requires_independently_pinned_gateway_profile(self):
        token = self.root/'token'; token.write_text('private-token'); os.chmod(token, 0o600)
        provider = self.config['paddleocr-vl']; provider['baseUrl'] = 'http://127.0.0.1:28771'
        provider['ownedFixed'] = {'stage':'paddle', 'socketPath':str(self.root/'gateway.sock'),
            'tokenFile':str(token), 'readyTimeoutSeconds':10, 'cleanupTimeoutSeconds':2}
        for pin in (None, '', 'a'*63, 'A'*64, 'g'*64, 12):
            with self.subTest(pin=pin):
                if pin is None: provider['ownedFixed'].pop('gatewayProfileSha256', None)
                else: provider['ownedFixed']['gatewayProfileSha256'] = pin
                with self.assertRaisesRegex(ValueError, 'gateway profile SHA256 pin'): self.prepare()
                self.assertFalse((self.root/'prepared').exists())


if __name__ == '__main__':
    unittest.main()
