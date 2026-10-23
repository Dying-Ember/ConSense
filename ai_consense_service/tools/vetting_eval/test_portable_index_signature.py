"""Portable signatures keep model bytes and old vector seals authoritative."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import test_window_server as window_tests
from portable_index_signature import build, matches, sha, ENV_PATH, ENV_SHA, computation_proof
from token_windows import WindowError
from structural_retrieval import StructuralRecipe
from window_runtime import _loaded_source_hashes


class PortableCurrentIndexTests(unittest.TestCase):
    setUp = window_tests.ServerTests.setUp
    tearDown = window_tests.ServerTests.tearDown

    def test_move_only_model_directory_reuses_index_without_encoding(self):
        self.rt.identity = lambda kind: {'kind': kind, 'files': [{'sha256': 'a' * 64}], 'resolvedSnapshot': '/first/model'}
        response = window_tests.ServerTests.index(self)
        encoded = len(self.encodes); signature = self.rt.signature([self.p])
        self.rt.identity = lambda kind: {'kind': kind, 'files': [{'sha256': 'a' * 64}], 'resolvedSnapshot': '/moved/model'}
        self.assertEqual(signature, self.rt.signature([self.p]))
        again = window_tests.ServerTests.index(self)
        self.assertTrue(again['cached']); self.assertEqual(response['signature'], again['signature'])
        self.assertEqual(encoded, len(self.encodes))
        self.assertIsNotNone(window_tests.s.read_project('one'))

    @unittest.skipUnless(os.environ.get('CONSENSE_TEST_PREDECESSOR'), 'requires the exact audited predecessor source checkout, no models')
    def test_legacy_short_and_structural_indexes_relocate_without_encoding_or_metadata_rewrite(self):
        predecessor = Path(os.environ['CONSENSE_TEST_PREDECESSOR'])
        current = Path(__file__).parent
        receipt = Path(self.tmp.name) / 'adapter.json'
        receipt.write_bytes(json.dumps(build(predecessor, current)).encode('utf-8'))
        old_algorithms = {name: sha((predecessor / name).read_bytes()) for name in _loaded_source_hashes if name != 'portable_index_signature.py'}
        self.rt.identity = lambda kind: {'kind': kind, 'files': [{'sha256': 'a' * 64}], 'resolvedSnapshot': '/old/unavailable/model'}
        original_logical = self.rt.logical_identity; original_version = self.rt.signature_version
        for structural in (False, True):
            with self.subTest(structural=structural):
                self.rt.structural_recipe = StructuralRecipe.from_environment({'CONSENSE_RETRIEVAL_GRANULARITY': 'dual-structure-v1' if structural else 'windows-v1'})
                self.rt.algorithms = lambda: dict(old_algorithms)
                self.rt.logical_identity = self.rt.identity
                self.rt.signature_version = lambda: 4 if structural else 3
                key = 'legacy-structural' if structural else 'legacy-short'
                first = window_tests.ServerTests.index(self, project=key)
                path = window_tests.s.metadata_path(key); before = path.read_bytes(); encoded = len(self.encodes)
                self.rt.algorithms = lambda: dict(_loaded_source_hashes)
                self.rt.logical_identity = original_logical; self.rt.signature_version = original_version
                self.rt.identity = lambda kind: {'kind': kind, 'files': [{'sha256': 'a' * 64}], 'resolvedSnapshot': '/relocated/model'}
                with patch.dict(os.environ, {ENV_PATH: str(receipt), ENV_SHA: sha(receipt.read_bytes())}):
                    again = window_tests.ServerTests.index(self, project=key)
                    self.assertTrue(again['cached']); self.assertEqual(first['signature'], again['signature'])
                    self.assertIsNotNone(window_tests.s.read_project(key))
                self.assertEqual(encoded, len(self.encodes))
                self.assertEqual(before, path.read_bytes())


class PortableLegacyAdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.old = self.root / 'old'; self.new = self.root / 'new'
        self.old.mkdir(); self.new.mkdir()
        for root in (self.old, self.new):
            (root / 'token_windows.py').write_bytes(b'VALUE = 1\n')
        algorithms = {'token_windows.py': sha(b'VALUE = 1\n')}
        self.current = {'signatureVersion': 5, 'algorithms': algorithms,
                        'tokenizerAndModelFiles': {'files': [{'sha256': 'a' * 64}]}, 'corpus': 'b' * 64,
                        'runtime': {'dtype': 'float32'}, 'windowRecipe': {'max_tokens': 512}}
        self.stored = copy.deepcopy(self.current); self.stored['signatureVersion'] = 3
        self.stored['tokenizerAndModelFiles']['resolvedSnapshot'] = '/old/inaccessible/model'
        self.receipt = self.root / 'adapter.json'
        self.receipt.write_bytes(json.dumps(build(self.old, self.new)).encode('utf-8'))
        self.env = {ENV_PATH: str(self.receipt), ENV_SHA: sha(self.receipt.read_bytes())}

    def test_pinned_legacy_adapter_accepts_move_without_predecessor_checkout(self):
        (self.old / 'token_windows.py').unlink(); self.old.rmdir()
        self.assertTrue(matches(self.stored, self.current, self.new, self.env))

    def test_missing_receipt_does_not_authorize_legacy_reuse(self):
        self.assertFalse(matches(self.stored, self.current, self.new, {}))

    def test_model_recipe_source_and_runtime_changes_remain_rejected(self):
        for key, value in (('corpus', 'c' * 64), ('runtime', {'dtype': 'float16'}),
                           ('windowRecipe', {'max_tokens': 1024}),
                           ('tokenizerAndModelFiles', {'files': [{'sha256': 'd' * 64}]})):
            changed = copy.deepcopy(self.current); changed[key] = value
            self.assertFalse(matches(self.stored, changed, self.new, self.env))

    def test_receipt_or_current_dependency_change_is_rejected(self):
        self.receipt.write_bytes(self.receipt.read_bytes() + b' ')
        with self.assertRaises(WindowError):
            matches(self.stored, self.current, self.new, self.env)
        self.env[ENV_SHA] = sha(self.receipt.read_bytes())
        (self.new / 'token_windows.py').write_bytes(b'VALUE = 2\n')
        with self.assertRaises(WindowError):
            matches(self.stored, self.current, self.new, self.env)

    def test_omitting_algorithm_identity_is_rejected(self):
        self.stored['algorithms'] = {}
        with self.assertRaises(WindowError):
            matches(self.stored, self.current, self.new, self.env)

    def test_changed_actual_encode_function_cannot_receive_a_receipt(self):
        before = '\n'.join('def ' + name + '(value=None): return value' for name in
                           ('model_settings', 'device', 'model_options', 'model_runtime', 'model_path',
                            'embedding_model', 'encode_embeddings', 'validate_corpus', 'verify_loaded_model'))
        after = before.replace('def encode_embeddings(value=None): return value',
                               'def encode_embeddings(value=None): return [0]')
        with self.assertRaises(WindowError):
            computation_proof('server.py', before, after)

    def test_model_validation_or_module_execution_changes_cannot_receive_a_receipt(self):
        before = '\n'.join('def ' + name + '(value=None): return value' for name in
                           ('model_settings', 'device', 'model_options', 'model_runtime', 'model_path',
                            'embedding_model', 'encode_embeddings', 'validate_corpus', 'verify_loaded_model'))
        for after in (before.replace('def verify_loaded_model(value=None): return value', 'def verify_loaded_model(value=None): pass'),
                      before + '\nembedding_model = lambda: None\n'):
            with self.assertRaises(WindowError):
                computation_proof('server.py', before, after)
