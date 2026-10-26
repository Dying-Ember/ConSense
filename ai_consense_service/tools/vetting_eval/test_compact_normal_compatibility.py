"""Exact two-module software domains with authored vectors; no operational state."""
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
from test_token_windows import CharacterTokenizer
from token_windows import WindowError
import test_normal_conditional_wire as normal_tests


CURRENT = Path(__file__).resolve().parent
ROOT = CURRENT.parent
STAGE37 = Path(os.environ['CONSENSE_COMPACT_NORMAL_STAGE37_ROOT']).resolve() \
    if os.environ.get('CONSENSE_COMPACT_NORMAL_STAGE37_ROOT') else None


def receipts(module):
    return [('inherited-' + module, json.loads((ROOT / 'adapters' / module / 'index-signature-adapter.json').read_bytes())),
            ('stage37-' + module, adapter.build(STAGE37 / 'code', CURRENT))]


@unittest.skipUnless(STAGE37, 'requires the pinned Stage37 software fixture; no live state or models')
class CompactNormalCompatibilityGuards(unittest.TestCase):
    def test_exact_two_source_read_pairs_are_approved_and_other_ast_changes_reject(self):
        after = (CURRENT / 'source_read_server.py').read_text(encoding='utf-8')
        before = (STAGE37 / 'code/source_read_server.py').read_text(encoding='utf-8')
        inherited = json.loads((ROOT / 'base-adapters/vetting.json').read_bytes())['predecessorSources']['source_read_server.py']
        for old in (before, inherited):
            adapter.computation_proof('source_read_server.py', old, after)
            with self.assertRaises(WindowError):
                adapter.computation_proof('source_read_server.py', old,
                    after + '\nAUTHORED_UNREVIEWED_SEMANTIC_CHANGE = True\n')
        old_portable = (STAGE37 / 'code/portable_index_signature.py').read_text(encoding='utf-8')
        new_portable = (CURRENT / 'portable_index_signature.py').read_text(encoding='utf-8')
        adapter.computation_proof('portable_index_signature.py', old_portable, new_portable)
        with self.assertRaises(WindowError):
            adapter.computation_proof('portable_index_signature.py', old_portable,
                new_portable + '\nAUTHORED_UNREVIEWED_GUARD_CHANGE = True\n')

    def test_both_actual_inherited_and_stage37_domains_keep_every_negative_boundary(self):
        def signature(algorithms, module):
            return {'signatureVersion': 6, 'algorithms': copy.deepcopy(algorithms),
                'corpus': 'a' * 64, 'runtime': {'dtype': 'float32', 'device': 'cuda'},
                'tokenizerAndModelFiles': {'files': [{'sha256': 'b' * 64}]},
                'windowRecipe': {'max_tokens': 512, 'overlap': 64},
                'competitionScope': {'manifestSha256': 'c' * 64}, 'oerTargetModule': module}
        for module in ('vetting', 'advisory'):
            for name, receipt in receipts(module):
                with self.subTest(domain=name), tempfile.TemporaryDirectory() as directory:
                    raw = json.dumps(receipt, sort_keys=True).encode()
                    path = Path(directory) / 'adapter.json'
                    path.write_bytes(raw)
                    environment = {adapter.ENV_PATH: str(path), adapter.ENV_SHA: adapter.sha(raw)}
                    before = signature(receipt['predecessorHashes'], module)
                    after = signature(receipt['currentHashes'], module)
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
                    altered = copy.deepcopy(after)
                    altered['algorithms']['source_read_server.py'] = '0' * 64
                    self.assertFalse(adapter.matches(before, altered, CURRENT, environment))
                    with self.assertRaises(WindowError):
                        adapter.matches(before, after, CURRENT,
                            {adapter.ENV_PATH: str(path), adapter.ENV_SHA: '0' * 64})


@unittest.skipUnless(STAGE37, 'requires the pinned Stage37 software fixture; authored fake vectors only')
class CompactNormalCompatibilityVectorReuse(unittest.TestCase):
    setUp = normal_tests.NormalConditionalWireTests.setUp
    tearDown = normal_tests.NormalConditionalWireTests.tearDown
    publish = normal_tests.NormalConditionalWireTests.publish

    def configure_module(self, module):
        self.client.close()
        self.app = create_unified_app(self.policy, backend=self.backend, target_module=module)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'AUTHORED-compact-proof-compatibility'}
        self.client = TestClient(self.app)
        self.store, self.registry = self.app.state.artifact_store, self.app.state.source_registry
        self.module = module
        self.body['sourceBinding']['module'] = module

    def test_actual_two_module_predecessor_domains_reuse_vectors_signature_and_metadata_bytes(self):
        results = []
        for module in ('vetting', 'advisory'):
            for domain, receipt in receipts(module):
                with self.subTest(domain=domain):
                    self.configure_module(module)
                    source = self.publish()
                    current_algorithms = self.rt.algorithms
                    actual = current_algorithms()
                    old_algorithms = {name: receipt['predecessorHashes'][name]
                        if name.endswith('.py') else digest for name, digest in actual.items()}
                    self.rt.algorithms = lambda: dict(old_algorithms)
                    body = {'projectId': 'AUTHORED-compact-proof-' + domain, 'module': module,
                            'projectionSha256': [source['projectionSha256']]}
                    first = self.client.post('/index-canonical', json=body)
                    self.assertEqual(200, first.status_code, first.text)
                    metadata = self.backend.metadata_path(body['projectId'])
                    before = metadata.read_bytes()
                    vectors = copy.deepcopy(self.db.collections)
                    encoded = len(self.encodes)
                    self.backend._projects.clear()
                    self.rt.algorithms = current_algorithms
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
                    wrong_module = self.client.post('/index-canonical/availability', json={**body,
                        'module': 'advisory' if module == 'vetting' else 'vetting', 'coldBootstrap': True})
                    self.assertNotEqual(200, wrong_module.status_code)
                    self.assertEqual(encoded, len(self.encodes))
                    results.append({'domain': domain, 'signature': again.json()['signature'],
                        'metadataSha256': adapter.sha(before), 'additionalEncodes': 0,
                        'vectorAndMetadataBytesUnchanged': True})
        print('COMPACT_NORMAL_AUTHORED_VECTOR_REUSE=' + json.dumps(results, sort_keys=True))


if __name__ == '__main__':
    unittest.main()
