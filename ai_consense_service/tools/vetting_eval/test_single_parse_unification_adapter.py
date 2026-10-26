"""Two-module authored index reuse and exact-source bridge guards; no live state."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from oer_unified_server import create_unified_app
import portable_index_signature as adapter
from token_windows import WindowError
from test_token_windows import CharacterTokenizer
import test_normal_conditional_wire as normal_tests


CURRENT = Path(__file__).resolve().parent
DELIVERY = Path(os.environ['CONSENSE_SINGLE_PARSE_DELIVERY_ROOT']).resolve() \
    if os.environ.get('CONSENSE_SINGLE_PARSE_DELIVERY_ROOT') else None


@unittest.skipUnless(DELIVERY, 'requires the frozen native delivery software root; no live indexes or models')
class NativeUnificationSourceGuards(unittest.TestCase):
    def test_two_private_domains_and_exact_current_pairs_preserve_all_negatives(self):
        preparation = json.loads((DELIVERY / 'evidence/source-preparation-receipt.json').read_bytes())
        for module in ('vetting', 'advisory'):
            with self.subTest(module=module):
                old_root = Path(preparation['adapters'][module]['priorAdapter']['path']).parent / 'code'
                immediate = adapter.build(old_root, CURRENT)
                inherited_path = DELIVERY / 'adapters' / module / 'index-signature-adapter.json'
                inherited = json.loads(inherited_path.read_bytes())
                for receipt in (immediate, inherited):
                    def signature(algorithms):
                        return {'signatureVersion': 6, 'algorithms': copy.deepcopy(algorithms),
                            'corpus': 'a' * 64, 'runtime': {'dtype': 'float32', 'device': 'cuda'},
                            'tokenizerAndModelFiles': {'files': [{'sha256': 'b' * 64}]},
                            'windowRecipe': {'max_tokens': 512, 'overlap': 64},
                            'competitionScope': {'manifestSha256': 'c' * 64}, 'oerTargetModule': module}
                    raw = json.dumps(receipt, sort_keys=True).encode()
                    with tempfile.TemporaryDirectory() as directory:
                        path = Path(directory) / 'adapter.json'
                        path.write_bytes(raw)
                        environment = {adapter.ENV_PATH: str(path), adapter.ENV_SHA: adapter.sha(raw)}
                        before, after = signature(receipt['predecessorHashes']), signature(receipt['currentHashes'])
                        self.assertTrue(adapter.matches(before, after, CURRENT, environment))
                        for field, changed in (('corpus', 'd' * 64), ('runtime', {'dtype': 'float16'}),
                                ('tokenizerAndModelFiles', {'files': [{'sha256': 'e' * 64}]}),
                                ('windowRecipe', {'max_tokens': 1024}),
                                ('competitionScope', {'manifestSha256': 'f' * 64}),
                                ('oerTargetModule', 'advisory' if module == 'vetting' else 'vetting')):
                            altered = copy.deepcopy(after)
                            altered[field] = changed
                            self.assertFalse(adapter.matches(before, altered, CURRENT, environment), field)
                        altered = copy.deepcopy(after)
                        altered['algorithms']['unknown_third_module.py'] = '0' * 64
                        self.assertFalse(adapter.matches(before, altered, CURRENT, environment))
                for name in ('oer_server.py', 'oer_source_registry.py', 'oer_unified_server.py'):
                    before = (old_root / name).read_text(encoding='utf-8')
                    after = (CURRENT / name).read_text(encoding='utf-8')
                    adapter.computation_proof(name, before, after)
                    with self.assertRaises(WindowError):
                        adapter.computation_proof(name, before, after + '\nAUTHORED_UNREVIEWED_SEMANTIC_CHANGE = True\n')


@unittest.skipUnless(DELIVERY, 'requires the frozen native delivery; authored fake-vector providers only')
class NativeUnificationVectorReuse(unittest.TestCase):
    setUp = normal_tests.NormalConditionalWireTests.setUp
    tearDown = normal_tests.NormalConditionalWireTests.tearDown
    publish = normal_tests.NormalConditionalWireTests.publish

    def configure_module(self, module):
        self.client.close()
        self.app = create_unified_app(self.policy, backend=self.backend, target_module=module)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'AUTHORED-unification-only'}
        self.client = TestClient(self.app)
        self.store, self.registry = self.app.state.artifact_store, self.app.state.source_registry
        self.module = module
        self.body['sourceBinding']['module'] = module

    def test_actual_old_vetting_and_advisory_software_reuse_authored_vectors_without_rewrite(self):
        preparation = json.loads((DELIVERY / 'evidence/source-preparation-receipt.json').read_bytes())
        for module in ('vetting', 'advisory'):
            with self.subTest(module=module):
                self.configure_module(module)
                source = self.publish()
                old_root = Path(preparation['adapters'][module]['priorAdapter']['path']).parent / 'code'
                current_algorithms = self.rt.algorithms
                old_algorithms = {name: adapter.sha((old_root / name).read_bytes())
                    if name.endswith('.py') else digest for name, digest in current_algorithms().items()}
                self.rt.algorithms = lambda: dict(old_algorithms)
                body = {'projectId': 'AUTHORED-old-' + module, 'module': module,
                    'projectionSha256': [source['projectionSha256']]}
                first = self.client.post('/index-canonical', json=body)
                self.assertEqual(200, first.status_code, first.text)
                metadata = self.backend.metadata_path(body['projectId'])
                before = metadata.read_bytes()
                vectors = copy.deepcopy(self.db.collections)
                encoded = len(self.encodes)
                self.backend._projects.clear()
                self.rt.algorithms = current_algorithms
                receipt = adapter.build(old_root, CURRENT)
                raw = json.dumps(receipt, sort_keys=True).encode()
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / 'adapter.json'
                    path.write_bytes(raw)
                    with patch.dict(os.environ, {adapter.ENV_PATH: str(path), adapter.ENV_SHA: adapter.sha(raw)}):
                        again = self.client.post('/index-canonical', json=body)
                self.assertEqual(200, again.status_code, again.text)
                self.assertTrue(again.json()['cached'])
                self.assertEqual(first.json()['signature'], again.json()['signature'])
                self.assertEqual(encoded, len(self.encodes))
                self.assertEqual(before, metadata.read_bytes())
                self.assertEqual(vectors, self.db.collections)

    def test_current_fresh_two_module_availability_is_scoped_and_read_only(self):
        for module in ('vetting', 'advisory'):
            with self.subTest(module=module):
                self.configure_module(module)
                source = self.publish()
                body = {'projectId': 'AUTHORED-fresh-' + module, 'module': module,
                    'projectionSha256': [source['projectionSha256']]}
                indexed = self.client.post('/index-canonical', json=body)
                self.assertEqual(200, indexed.status_code, indexed.text)
                metadata = self.backend.metadata_path(body['projectId'])
                before = metadata.read_bytes()
                encoded = len(self.encodes)
                available = self.client.post('/index-canonical/availability', json={**body, 'coldBootstrap': True})
                self.assertEqual(200, available.status_code, available.text)
                self.assertTrue(available.json()['ready'])
                self.assertTrue(available.json()['readOnly'])
                self.assertFalse(available.json()['fullVectorIntegrityChecked'])
                self.assertEqual(encoded, len(self.encodes))
                self.assertEqual(before, metadata.read_bytes())
                rejected = self.client.post('/index-canonical/availability', json={**body,
                    'module': 'advisory' if module == 'vetting' else 'vetting', 'coldBootstrap': True})
                self.assertNotEqual(200, rejected.status_code)


if __name__ == '__main__':
    unittest.main()
