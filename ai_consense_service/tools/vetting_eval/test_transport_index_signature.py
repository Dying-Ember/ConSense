"""Authored offline source/provider fixtures; no real models, APIs or databases."""
import ast
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import portable_index_signature as adapter
from token_windows import WindowError


class TransportIndexSignatureTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.old, self.baseline, self.new = [self.root / name for name in ('dual08', 'dual09', 'successor')]
        self.names = (*adapter.TRANSPORT_SOURCE_HASHES, 'portable_index_signature.py')
        fingerprints = lambda raw: adapter.sha(ast.dump(ast.parse(raw), include_attributes=False).encode())
        pairs = {}
        for folder in (self.old, self.baseline, self.new):
            folder.mkdir()
        for number, name in enumerate(self.names):
            before = ('VALUE = ' + str(number) + '\n').encode()
            after = before + b'JOURNAL = True\n'
            (self.old / name).write_bytes(before)
            (self.new / name).write_bytes(after)
            (self.baseline / name).write_bytes(before if name == 'portable_index_signature.py' else after)
            pairs[name] = [fingerprints(before), fingerprints(after)]
        self.before = self.hashes(self.old)
        self.after = self.hashes(self.new)
        self.addCleanup(patch.stopall)
        patch.object(adapter, 'APPROVED_SOURCE_ADAPTERS', {**adapter.APPROVED_SOURCE_ADAPTERS, **pairs}).start()
        patch.object(adapter, 'TRANSPORT_SOURCE_HASHES', {name: (self.before[name], self.after[name]) for name in self.names[:-1]}).start()
        patch.object(adapter, 'PORTABLE_PREDECESSOR_SHA', self.before['portable_index_signature.py']).start()
        self.virtual = adapter.sha(b'pypdf==6.19.0')
        self.current = {'signatureVersion': 6, 'corpus': 'b' * 64,
                        'runtime': {'dtype': 'float32'}, 'windowRecipe': {'max_tokens': 512},
                        'tokenizerAndModelFiles': {'files': [{'sha256': 'a' * 64}]},
                        'algorithms': {**self.after, 'nativePdfFrameReaderIdentity': self.virtual}}
        self.stored = copy.deepcopy(self.current)
        self.stored['algorithms'] = {**self.before, 'nativePdfFrameReaderIdentity': self.virtual}
        self.receipt = self.root / 'fixture.json'
        self.environment = self.receipt_for(self.old)

    def hashes(self, root):
        return {name: adapter.sha((root / name).read_bytes()) for name in self.names}

    def receipt_for(self, predecessor):
        self.receipt.write_text(json.dumps(adapter.build(predecessor, self.new)), encoding='utf-8')
        return {adapter.ENV_PATH: str(self.receipt), adapter.ENV_SHA: adapter.sha(self.receipt.read_bytes())}

    def test_exact_dual08_and_dual09_sources_match_same_version_without_signature_rewrite(self):
        original = copy.deepcopy(self.stored)
        self.assertTrue(adapter.matches(self.stored, self.current, self.new, self.environment))
        self.assertEqual(self.stored, original)
        stored = copy.deepcopy(self.current)
        stored['algorithms'] = {**self.hashes(self.baseline), 'nativePdfFrameReaderIdentity': self.virtual}
        self.assertTrue(adapter.matches(stored, self.current, self.new, self.receipt_for(self.baseline)))

    def test_missing_receipt_bad_external_sha_and_changed_current_bytes_fail(self):
        self.assertFalse(adapter.matches(self.stored, self.current, self.new, {}))
        with self.assertRaises(WindowError):
            adapter.matches(self.stored, self.current, self.new, {**self.environment, adapter.ENV_SHA: '0' * 64})
        (self.new / self.names[0]).write_text('VALUE = 999\n', encoding='utf-8')
        with self.assertRaises(WindowError):
            adapter.matches(self.stored, self.current, self.new, self.environment)

    def test_corpus_model_recipe_runtime_and_version_differences_remain_rejected(self):
        for key, value in (('corpus', 'c' * 64), ('runtime', {'dtype': 'float16'}),
                           ('windowRecipe', {'max_tokens': 1024}), ('signatureVersion', 5),
                           ('tokenizerAndModelFiles', {'files': [{'sha256': 'd' * 64}]})):
            with self.subTest(field=key):
                current = copy.deepcopy(self.current)
                current[key] = value
                self.assertFalse(adapter.matches(self.stored, current, self.new, self.environment))

    def test_unknown_new_removed_and_virtual_dependency_drift_fail(self):
        for kind in ('unknown', 'new', 'removed', 'virtual', 'unreviewed-pair'):
            with self.subTest(kind=kind):
                stored, current = copy.deepcopy(self.stored), copy.deepcopy(self.current)
                if kind == 'unknown':
                    stored['algorithms']['unknownDependency'] = current['algorithms']['unknownDependency'] = 'e' * 64
                elif kind == 'new':
                    current['algorithms']['new.py'] = 'e' * 64
                elif kind == 'removed':
                    current['algorithms'].pop(self.names[0])
                elif kind == 'virtual':
                    current['algorithms']['nativePdfFrameReaderIdentity'] = 'e' * 64
                else:
                    stored['algorithms'][self.names[0]] = 'e' * 64
                try:
                    self.assertFalse(adapter.matches(stored, current, self.new, self.environment))
                except WindowError:
                    pass

    def test_receipt_does_not_authorize_unreviewed_portable_or_registry_computation(self):
        for name in ('portable_index_signature.py', 'unified_source_registry.py'):
            with self.subTest(source=name), self.assertRaises(WindowError):
                adapter.computation_proof(name, (self.old / name).read_text(), (self.new / name).read_text() + 'UNREVIEWED = True\n')

    def test_provider_fixture_reuses_both_baselines_without_encoding_and_rejects_vector_tamper(self):
        import test_window_server as window_tests
        import window_runtime
        from structural_retrieval import StructuralRecipe
        window_tests.ServerTests.setUp(self)
        self.addCleanup(lambda: window_tests.ServerTests.tearDown(self))
        self.rt.structural_recipe = StructuralRecipe.from_environment({'CONSENSE_RETRIEVAL_GRANULARITY': 'dual-structure-v1'})
        real_matcher = adapter.matches
        with patch.object(window_runtime, 'portable_signature_matches',
                          side_effect=lambda stored, current, root: real_matcher(stored, current, self.new)):
            for number, predecessor in enumerate((self.old, self.baseline)):
                with self.subTest(predecessor=predecessor.name):
                    self.rt.algorithms = lambda: {**self.hashes(predecessor), 'nativePdfFrameReaderIdentity': self.virtual}
                    project = 'offline-' + str(number)
                    first = window_tests.ServerTests.index(self, project=project)
                    metadata_path = window_tests.s.metadata_path(project)
                    metadata_before = metadata_path.read_bytes()
                    encoded = len(self.encodes)
                    self.rt.algorithms = lambda: {**self.after, 'nativePdfFrameReaderIdentity': self.virtual}
                    with patch.dict(os.environ, self.receipt_for(predecessor)):
                        again = window_tests.ServerTests.index(self, project=project)
                        self.assertTrue(again['cached'])
                        self.assertEqual(first['signature'], again['signature'])
                        self.assertEqual(encoded, len(self.encodes))
                        self.assertEqual(metadata_before, metadata_path.read_bytes())
                        collection = window_tests.s._projects[project]['collection']
                        next(iter(self.db.collections[collection].values())).vector = [0.0, 1.0]
                        with self.assertRaises(window_tests.s.HTTPException):
                            window_tests.ServerTests.index(self, project=project)
                        self.assertEqual(encoded, len(self.encodes))


if __name__ == '__main__':
    unittest.main()
