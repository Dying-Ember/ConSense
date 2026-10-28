"""Exact source-only transport adaptation with authored persisted vector doubles."""
import copy
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

import portable_index_signature as portable
import test_window_server as window_tests
from structural_retrieval import StructuralRecipe
from token_windows import WindowError


@unittest.skipUnless(os.environ.get('CONSENSE_TEST_DEFERRED_PREDECESSOR'),
                     'requires the sealed compact-text predecessor source, no operational index or models')
class DeferredClosureAdapterTests(unittest.TestCase):
    setUp = window_tests.ServerTests.setUp
    tearDown = window_tests.ServerTests.tearDown

    def adapter(self):
        predecessor = Path(os.environ['CONSENSE_TEST_DEFERRED_PREDECESSOR'])
        current = Path(__file__).resolve().parent
        value = portable.build(predecessor, current)
        path = Path(self.tmp.name) / 'deferred-closure-adapter.json'
        path.write_text(json.dumps(value), encoding='utf-8')
        return value, path, {portable.ENV_PATH: str(path), portable.ENV_SHA: portable.sha(path.read_bytes())}

    def test_exact_compact_text_predecessor_reuses_persisted_vectors_without_encoding(self):
        adapter, path, environment = self.adapter()
        self.rt.structural_recipe = StructuralRecipe.from_environment({'CONSENSE_RETRIEVAL_GRANULARITY': 'dual-structure-v1'})
        self.rt.algorithms = lambda: dict(adapter['predecessorHashes'])
        first = window_tests.ServerTests.index(self, project='deferred-closure-reuse')
        metadata = window_tests.s.metadata_path('deferred-closure-reuse')
        before, encodes = metadata.read_bytes(), len(self.encodes)
        self.rt.algorithms = lambda: dict(adapter['currentHashes'])
        with patch.dict(os.environ, environment):
            again = window_tests.ServerTests.index(self, project='deferred-closure-reuse')
            self.assertTrue(again['cached'])
            self.assertEqual(first['signature'], again['signature'])
            self.assertIsNotNone(window_tests.s.read_project('deferred-closure-reuse'))
        self.assertEqual(encodes, len(self.encodes))
        self.assertEqual(before, metadata.read_bytes(), 'Existing index metadata and vector seals remain unchanged')

    def test_transport_transition_does_not_admit_changed_model_recipe_corpus_or_unapproved_source(self):
        adapter, path, environment = self.adapter()
        stored = {'signatureVersion': 6, 'algorithms': adapter['predecessorHashes'], 'corpus': 'b' * 64,
            'tokenizerAndModelFiles': {'embedding': {'files': [{'sha256': 'a' * 64}]}},
            'runtime': {'dtype': 'float32'}, 'windowRecipe': {'max_tokens': 512}, 'module': 'vetting'}
        current = copy.deepcopy(stored)
        current['algorithms'] = adapter['currentHashes']
        self.assertTrue(portable.matches(stored, current, Path(__file__).resolve().parent, environment))
        for key, value in (('corpus', 'c' * 64), ('runtime', {'dtype': 'float16'}),
                           ('windowRecipe', {'max_tokens': 1024}), ('module', 'advisory'),
                           ('tokenizerAndModelFiles', {'embedding': {'files': [{'sha256': 'd' * 64}]}})):
            with self.subTest(field=key):
                changed = copy.deepcopy(current)
                changed[key] = value
                self.assertFalse(portable.matches(stored, changed, Path(__file__).resolve().parent, environment))
        changed = copy.deepcopy(current)
        changed['algorithms']['source_read_server.py'] = 'f' * 64
        self.assertFalse(portable.transport_candidate(stored, changed))


    def test_exact_transition_still_rejects_an_unreviewed_source_read_change(self):
        predecessor = Path(os.environ['CONSENSE_TEST_DEFERRED_PREDECESSOR'])
        name = 'source_read_server.py'
        before = (predecessor / name).read_text(encoding='utf-8')
        after = (Path(__file__).resolve().parent / name).read_text(encoding='utf-8')
        portable.computation_proof(name, before, after)
        with self.assertRaises(WindowError) as caught:
            portable.computation_proof(name, before, after + '\nUNREVIEWED = True\n')
        self.assertEqual('INDEX_ADAPTER_COMPUTATION_CHANGED', caught.exception.kind)


if __name__ == '__main__':
    unittest.main()
