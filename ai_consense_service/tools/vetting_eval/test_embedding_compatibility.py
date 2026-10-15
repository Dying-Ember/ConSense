"""Current safety contracts with authored identity/AST fixtures; no historical replay.

No model, tokenizer, vector DB or HTTP calls. Invalid temporary receipts exercise
rejection gates and never establish an approved predecessor or source proof.
"""
import ast
import copy
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import embedding_compatibility as compat
from token_windows import WindowError, digest
import window_runtime as current


def fixture_runtime():
    model = {'name': 'controlled-offline-fixture', 'revision': 'fixture-revision'}
    runtime_settings = {'dtype': 'float32', 'device': 'cuda', 'batchSize': 4,
                        'serialOffload': True, 'executionMath': {'tf32': False}}
    server = SimpleNamespace(MODELS={'embedding': model, 'reranker': model}, digest=digest,
                             MODEL_SETTINGS={'embedding': {'maxTokens': 512}, 'reranker': {'maxTokens': 512}},
                             model_runtime=lambda kind: runtime_settings)
    runtime = current.WindowRuntime(server)
    for kind in ('embedding', 'reranker'):
        runtime.identities[kind] = {'model': model, 'resolvedSnapshot': '/controlled/same-snapshot',
                                   'files': [{'relativePath': 'tokenizer.json', 'sha256': 'a' * 64, 'bytes': 1}],
                                   'backendSha256': 'b' * 64, 'packages': {'controlled': 'fixture-only'}}
    # Signature composition uses authored identities; actual source checks stay live.
    runtime.identity = lambda kind: runtime.identities[kind]
    return runtime


class CurrentCompatibilitySafety(unittest.TestCase):
    def setUp(self):
        self.clean = patch.dict(os.environ, {
            compat.ENV_PATH: '', compat.ENV_SHA: '',
            'CONSENSE_FIXED_VECTOR_CACHE': '', 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST': '',
            'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256': '', 'CONSENSE_WINDOW_OVERLAP_TOKENS': '64',
            'CONSENSE_RETRIEVAL_GRANULARITY': 'windows-v1',
            'CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS': '2048', 'CONSENSE_STRUCTURAL_RERANK_MAX_TOKENS': '2048',
            'CONSENSE_STRUCTURAL_RERANK_STRICT': 'true',
            'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST': '', 'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST_SHA256': '',
            'CONSENSE_RERANKER_CHECKPOINT_MANIFEST': '', 'CONSENSE_RERANKER_CHECKPOINT_MANIFEST_SHA256': ''})
        self.clean.start(); self.addCleanup(self.clean.stop)
        self.runtime = fixture_runtime()
        self.root = Path(current.__file__).parent
        self.corpus = [{'id': 'fixture-a', 'documentId': 1, 'sourceHash': 'c' * 64, 'role': 'tender',
                        'content': 'General clause. 新材料 🌱', 'parts': [{'blockId': 'paragraph-1', 'text': 'General clause. 新材料 🌱'}]}]

    def assert_kind(self, kind, operation):
        with self.assertRaises(WindowError) as caught:
            operation()
        self.assertEqual(caught.exception.kind, kind)

    def verify_invalid_receipt(self, raw, expected_sha=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'invalid-receipt.json'; path.write_bytes(raw)
            return compat.verified_embedding_algorithms(self.root, self.runtime.algorithms(), {
                compat.ENV_PATH: str(path), compat.ENV_SHA: expected_sha or compat.sha(raw)})

    def test_no_receipt_returns_independent_current_algorithm_hashes(self):
        algorithms = self.runtime.algorithms()
        accepted = compat.verified_embedding_algorithms(self.root, algorithms, environ={})
        self.assertEqual(accepted, algorithms)
        self.assertIsNot(accepted, algorithms)
        accepted['window_runtime.py'] = '0' * 64
        self.assertEqual(algorithms, self.runtime.algorithms())

    def test_signature_and_cache_bind_full_current_source_inventory(self):
        algorithms = self.runtime.algorithms()
        self.assertEqual(algorithms, {name: compat.sha((self.root / name).read_bytes())
                                      for name in current._loaded_source_hashes})
        self.assertEqual(self.runtime.signature(self.corpus)['algorithms'], algorithms)
        self.assertEqual(self.runtime.embedding_cache_identity(1024)['preprocessing']['algorithms'], algorithms)

    def test_current_source_drift_is_rejected_without_writing_sources(self):
        changed = {**current._loaded_source_hashes, 'window_runtime.py': '0' * 64}
        with patch.object(current, '_loaded_source_hashes', changed):
            self.assert_kind('SOFTWARE_CHANGED_SINCE_IMPORT', self.runtime.algorithms)
        real_identity = current.file_identity
        def changed_identity(path):
            value = real_identity(path)
            return {**value, 'sha256': '0' * 64} if Path(path).name == 'window_runtime.py' else value
        with patch.object(current, 'file_identity', changed_identity):
            self.assert_kind('SOFTWARE_CHANGED_SINCE_IMPORT', self.runtime.algorithms)

    def test_corpus_content_source_and_role_invalidate_signature(self):
        before = digest(self.runtime.signature(self.corpus))
        for field, value in (('content', 'Revised clause'), ('sourceHash', 'd' * 64), ('role', 'standard')):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.corpus); changed[0][field] = value
                self.assertNotEqual(before, digest(self.runtime.signature(changed)))

    def test_model_tokenizer_window_and_dtype_invalidate_signature_and_cache(self):
        signature = digest(self.runtime.signature(self.corpus))
        cache = digest(self.runtime.embedding_cache_identity(1024))
        changes = (
            ('model', lambda runtime: runtime.s.MODELS['embedding'].update(revision='new-revision')),
            ('tokenizer', lambda runtime: runtime.identities['embedding']['files'][0].update(sha256='d' * 64)),
            ('window', lambda runtime: setattr(runtime, 'overlap', 63)),
            ('dtype', lambda runtime: runtime.s.model_runtime('embedding').update(dtype='float16')))
        for name, change in changes:
            with self.subTest(name=name):
                runtime = fixture_runtime(); change(runtime)
                self.assertNotEqual(signature, digest(runtime.signature(self.corpus)))
                self.assertNotEqual(cache, digest(runtime.embedding_cache_identity(1024)))

    def test_runtime_math_and_cache_dimension_keep_their_identity_bindings(self):
        signature = digest(self.runtime.signature(self.corpus))
        self.runtime.s.model_runtime('embedding')['executionMath']['tf32'] = True
        self.assertNotEqual(signature, digest(self.runtime.signature(self.corpus)))
        self.assertNotEqual(self.runtime.embedding_cache_identity(1024), self.runtime.embedding_cache_identity(512))

    def test_partial_external_binding_is_rejected_before_file_access(self):
        for env in ({compat.ENV_PATH: 'absent-receipt.json'}, {compat.ENV_SHA: '0' * 64}):
            with self.subTest(env=env):
                self.assert_kind('EMBEDDING_COMPATIBILITY_CONFIG',
                                 lambda: compat.verified_embedding_algorithms(self.root, {}, environ=env))

    def test_wrong_raw_receipt_sha_is_rejected_before_json(self):
        self.assert_kind('EMBEDDING_COMPATIBILITY_RECEIPT_CHANGED',
                         lambda: self.verify_invalid_receipt(b'not JSON', '0' * 64))

    def test_duplicate_json_key_is_rejected_with_matching_external_sha(self):
        self.assert_kind('EMBEDDING_COMPATIBILITY_JSON',
                         lambda: self.verify_invalid_receipt(b'{"proof": 1, "proof": 2}'))

    def test_missing_final_proof_is_rejected(self):
        self.assert_kind('EMBEDDING_COMPATIBILITY_PROOF', lambda: self.verify_invalid_receipt(b'{}'))

    def test_unknown_protocol_or_status_is_rejected(self):
        for protocol, status in (('unknown', 'explicit_source_equivalence_no_neural_calls'),
                                 (compat.PROTOCOL, 'unapproved')):
            with self.subTest(protocol=protocol, status=status):
                raw = json.dumps({'proof': {}, 'protocol': protocol, 'status': status}).encode()
                self.assert_kind('EMBEDDING_COMPATIBILITY_PROTOCOL', lambda: self.verify_invalid_receipt(raw))

    def test_unapproved_predecessor_is_rejected_before_descriptor_reads(self):
        for predecessor in ('legacyProvenance', 'structuralProvenance'):
            with self.subTest(predecessor=predecessor):
                receipt = {'proof': {}, 'protocol': compat.PROTOCOL,
                           'status': 'explicit_source_equivalence_no_neural_calls',
                           'legacyProvenance': {'sha256': compat.LEGACY_PROVENANCE_SHA},
                           'structuralProvenance': {'sha256': compat.STRUCTURAL_PROVENANCE_SHA}}
                receipt[predecessor]['sha256'] = '0' * 64
                self.assert_kind('EMBEDDING_COMPATIBILITY_PREDECESSOR',
                                 lambda: self.verify_invalid_receipt(json.dumps(receipt).encode()))

    def test_descriptor_requires_exact_shape_and_unchanged_bytes(self):
        self.assert_kind('EMBEDDING_COMPATIBILITY_DESCRIPTOR', lambda: compat.read_descriptor({'path': 'absent'}))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'authored.bin'; path.write_bytes(b'first')
            bound = compat.descriptor(path)
            self.assertEqual(compat.read_descriptor(bound), b'first')
            path.write_bytes(b'other')
            self.assert_kind('EMBEDDING_COMPATIBILITY_BYTES', lambda: compat.read_descriptor(bound))

    def test_ast_normalization_allows_only_exact_self_call(self):
        original = ast.parse('self.embedding_algorithms()')
        self.assertEqual(compat.dump(compat.normalize_calls(original)), compat.dump(ast.parse('self.algorithms()')))
        self.assertEqual(compat.dump(original), compat.dump(ast.parse('self.embedding_algorithms()')))
        for source in ('other.embedding_algorithms()', 'self.embedding_algorithms(1)',
                       'self.embedding_algorithms(option=True)'):
            with self.subTest(source=source):
                self.assert_kind('EMBEDDING_COMPATIBILITY_AST', lambda: compat.normalize_calls(ast.parse(source)))

    def test_runtime_ast_requires_unique_class_and_only_unique_methods(self):
        module = ast.parse('class WindowRuntime:\n    def signature(self):\n        return 1\n')
        self.assertEqual(set(compat.methods(module)), {'signature'})
        for source in ('class WindowRuntime:\n    pass\n',
                       'class WindowRuntime:\n    def signature(self): pass\n    def signature(self): pass\n',
                       'class WindowRuntime:\n    pass\nclass WindowRuntime:\n    pass\n'):
            with self.subTest(source=source):
                self.assert_kind('EMBEDDING_COMPATIBILITY_AST', lambda: compat.methods(ast.parse(source)))


if __name__ == '__main__':
    unittest.main()