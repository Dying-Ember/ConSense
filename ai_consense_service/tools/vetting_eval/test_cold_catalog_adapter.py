"""Exact software adapters over authored vectors only; no live index access."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
import portable_index_signature as adapter
from token_windows import WindowError
import test_normal_conditional_wire as normal_tests


CURRENT = Path(__file__).resolve().parent
BEFORE = Path(os.environ['CONSENSE_SINGLE_MANIFEST_PREDECESSOR_SOURCE']).resolve() \
    if os.environ.get('CONSENSE_SINGLE_MANIFEST_PREDECESSOR_SOURCE') else None


@unittest.skipUnless(BEFORE, 'requires the exact frozen predecessor software root; no live sources or models')
class SingleManifestAdapterTests(unittest.TestCase):
    def test_only_reviewed_normal_validation_pair_is_approved(self):
        before = (BEFORE / 'normal_observed_projection.py').read_text(encoding='utf-8')
        after = (CURRENT / 'normal_observed_projection.py').read_text(encoding='utf-8')
        adapter.computation_proof('normal_observed_projection.py', before, after)
        for name, source in (('normal_observed_projection.py', after + '\nAUTHORED_UNREVIEWED_AST = True\n'),
                ('unknown_third_module.py', after)):
            with self.subTest(name=name), self.assertRaises(WindowError):
                adapter.computation_proof(name, before, source)
        with self.assertRaises(WindowError):
            adapter.computation_proof('normal_observed_projection.py', before,
                after.replace('manifest = projection[\'manifest\']', 'manifest = {}'))

    def test_immediate_adapter_builds_with_exact_current_hashes(self):
        receipt = adapter.build(BEFORE, CURRENT)
        self.assertEqual({'normal_observed_projection.py', 'portable_index_signature.py'},
            set(receipt['predecessorSources']))
        for name in receipt['predecessorSources']:
            self.assertEqual(adapter.sha((CURRENT / name).read_bytes()), receipt['currentHashes'][name])

    @unittest.skipUnless(os.environ.get('CONSENSE_SINGLE_MANIFEST_INHERITED_ADAPTER'),
        'requires the pinned inherited software adapter; no operational index access')
    def test_inherited_domain_keeps_models_recipes_scope_and_unknown_modules_bound(self):
        receipt = json.loads(Path(os.environ['CONSENSE_SINGLE_MANIFEST_INHERITED_ADAPTER']).read_bytes())
        def signature(algorithms):
            return {'signatureVersion': 6, 'algorithms': copy.deepcopy(algorithms),
                'tokenizerAndModelFiles': {'files': [{'sha256': 'a' * 64}], 'dtype': 'float32'},
                'corpus': 'b' * 64, 'runtime': {'dtype': 'float32', 'device': 'cuda'},
                'windowRecipe': {'max_tokens': 512, 'overlap': 64},
                'competitionScope': {'manifestSha256': 'c' * 64}, 'oerTargetModule': 'advisory'}
        raw = json.dumps(receipt, sort_keys=True).encode('utf-8')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'adapter.json'
            path.write_bytes(raw)
            environment = {adapter.ENV_PATH: str(path), adapter.ENV_SHA: adapter.sha(raw)}
            stored, current = signature(receipt['predecessorHashes']), signature(receipt['currentHashes'])
            self.assertTrue(adapter.matches(stored, current, CURRENT, environment))
            for field, value in (('corpus', 'd' * 64), ('runtime', {'dtype': 'float16'}),
                    ('windowRecipe', {'max_tokens': 1024}),
                    ('tokenizerAndModelFiles', {'files': [{'sha256': 'e' * 64}]}),
                    ('competitionScope', {'manifestSha256': 'f' * 64}), ('oerTargetModule', 'vetting')):
                changed = copy.deepcopy(current)
                changed[field] = value
                self.assertFalse(adapter.matches(stored, changed, CURRENT, environment), field)
            changed = copy.deepcopy(current)
            changed['algorithms']['unknown_third_module.py'] = '0' * 64
            self.assertFalse(adapter.matches(stored, changed, CURRENT, environment))


@unittest.skipUnless(BEFORE, 'requires the exact frozen predecessor software root; authored fake vectors only')
class AuthoredVectorReuseTests(unittest.TestCase):
    setUp = normal_tests.NormalConditionalWireTests.setUp
    tearDown = normal_tests.NormalConditionalWireTests.tearDown
    publish = normal_tests.NormalConditionalWireTests.publish

    def test_reviewed_source_change_reuses_actual_authored_vectors_and_metadata(self):
        projection = self.publish()
        names = self.rt.algorithms()
        old = {name: adapter.sha((BEFORE / name).read_bytes()) if name.endswith('.py')
               else value for name, value in names.items()}
        original = self.rt.algorithms
        self.rt.algorithms = lambda: dict(old)
        body = {'projectId': 'AUTHORED-single-manifest-reuse', 'module': self.module,
            'projectionSha256': [projection['projectionSha256']]}
        first = self.client.post('/index-canonical', json=body)
        self.assertEqual(200, first.status_code, first.text)
        metadata = self.backend.metadata_path(body['projectId'])
        before_bytes = metadata.read_bytes()
        before_vectors = copy.deepcopy(self.db.collections)
        encoded = len(self.encodes)
        self.backend._projects.clear()
        self.rt.algorithms = original
        receipt = adapter.build(BEFORE, CURRENT)
        raw = json.dumps(receipt, sort_keys=True).encode('utf-8')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'adapter.json'
            path.write_bytes(raw)
            with patch.dict('os.environ', {adapter.ENV_PATH: str(path), adapter.ENV_SHA: adapter.sha(raw)}):
                again = self.client.post('/index-canonical', json=body)
        self.assertEqual(200, again.status_code, again.text)
        self.assertTrue(again.json()['cached'])
        self.assertEqual(first.json()['signature'], again.json()['signature'])
        self.assertEqual(encoded, len(self.encodes))
        self.assertEqual(before_vectors, self.db.collections)
        self.assertEqual(before_bytes, metadata.read_bytes())


if __name__ == '__main__':
    unittest.main()
