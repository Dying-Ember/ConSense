"""Synthetic release observations at the owned model publication seam."""
from pathlib import Path
import os
import sys
from contextlib import ExitStack
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'offline_delivery'))
import remote_gpu_models
import model_session_observer
from oer_server import _PublicationModelObserver, _require_publication_model_proxy
from token_windows import WindowError
from test_remote_model_session_scope import FakeSession, owner, setup


class PublicationModelReleaseTest(unittest.TestCase):
    def setUp(self):
        FakeSession.calls = []
        FakeSession.fail_release = False
        FakeSession.bad_result = False
        FakeSession.dimension = 2
        stack = ExitStack(); self.addCleanup(stack.close)
        stack.enter_context(patch('remote_gpu_models._trusted_stage_timeout',return_value=10))
        stack.enter_context(patch('remote_gpu_models.ModelSession', FakeSession))
        stack.enter_context(patch.object(model_session_observer, '_trusted_stage_pins', return_value={
            'profileSha256': 'd' * 64, 'executionSha256': 'f' * 64}))
        self.proxy = setup()
        self.proxy.require_request_scope()
        self.control = owner()
        self.source = 'b' * 64
        self.observer = _PublicationModelObserver(self.control, self.source,
            'document-embedding', self.proxy, 4, 4096)
        self.observer._verify_gpu_release = lambda receipt: True  # Synthetic independent witness.
        self.backend = self.proxy.backend
        self.backend.embedding_model = self.proxy.embedding_model
        self.backend.encode_embeddings = self.proxy.encode_embeddings

    def scope(self):
        return self.proxy.session_scope(self.control, self.source, 'document-embedding',
            max_batches=4, max_total_bytes=4096, observer=self.observer, execution_sha256='f' * 64)

    def test_actual_scope_and_observer_verify_each_started_operation(self):
        _require_publication_model_proxy(self.backend, self.proxy)
        with self.scope():
            self.assertEqual(2, self.proxy.embedding_model().get_sentence_embedding_dimension())
            self.proxy.encode_embeddings(['authored input'])
        self.assertTrue(self.observer.publication_verified())
        self.assertEqual(['start', 'batch', 'batch', 'finish'], [row[0] for row in FakeSession.calls])

    def test_no_model_operation_is_distinct_from_release(self):
        _require_publication_model_proxy(self.backend, self.proxy)
        with self.scope():
            pass
        self.assertEqual([], FakeSession.calls)
        self.assertTrue(self.observer.publication_verified())

    def test_unknown_release_cannot_become_publication_proof(self):
        self.observer._verify_gpu_release = lambda receipt: False
        with self.assertRaisesRegex(ValueError, 'observer rejected receipt'):
            with self.scope():
                self.proxy.embedding_model().get_sentence_embedding_dimension()
        with self.assertRaises(WindowError):
            self.observer.publication_verified()

    def test_wrong_request_receipt_and_repeated_receipt_are_rejected(self):
        captured = []
        original = FakeSession.finish
        def wrong_request(session):
            result = original(session)
            result['sourceClaims']['requestId'] = 'c' * 32
            return result
        with patch.object(FakeSession, 'finish', wrong_request):
            with self.assertRaises((ValueError, WindowError)):
                with self.scope():
                    self.proxy.embedding_model().get_sentence_embedding_dimension()
        with self.assertRaises(WindowError):
            self.observer.publication_verified()

        proxy = setup(); proxy.require_request_scope()
        observer = _PublicationModelObserver(self.control, self.source, 'document-embedding', proxy, 4, 4096)
        observer._verify_gpu_release = lambda receipt: True
        def remember(session):
            result = original(session); captured.append(result); return result
        with patch.object(FakeSession, 'finish', remember):
            with proxy.session_scope(self.control, self.source, 'document-embedding', max_batches=4,
                    max_total_bytes=4096, observer=observer, execution_sha256='f' * 64):
                proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertTrue(observer.publication_verified())
        with self.assertRaises(WindowError):
            observer.verify_session(captured[0])

    def test_fake_scope_or_other_backend_cannot_claim_no_model(self):
        fake = SimpleNamespace(session_scope=lambda *args, **kwargs: None)
        with self.assertRaises(WindowError):
            _require_publication_model_proxy(self.backend, fake)
        other = SimpleNamespace(embedding_model=lambda: None, encode_embeddings=self.proxy.encode_embeddings)
        with self.assertRaises(WindowError):
            _require_publication_model_proxy(other, self.proxy)
        with patch.object(self.proxy, 'session_scope', lambda *args, **kwargs: None):
            with self.assertRaises(WindowError):
                _require_publication_model_proxy(self.backend, self.proxy)

    def test_bound_methods_cannot_hide_a_different_proxy_backend(self):
        other = SimpleNamespace(embedding_model=self.proxy.embedding_model,
                                encode_embeddings=self.proxy.encode_embeddings)
        self.assertIsNot(other, self.proxy.backend)
        with self.assertRaises(WindowError):
            _require_publication_model_proxy(other, self.proxy)

    def test_replaced_embedding_object_cannot_claim_trusted_scope(self):
        self.proxy.embedding = SimpleNamespace(get_sentence_embedding_dimension=lambda: 2)
        with self.assertRaises(WindowError):
            _require_publication_model_proxy(self.backend, self.proxy)
        self.proxy.embedding = setup().embedding
        with self.assertRaises(WindowError):
            _require_publication_model_proxy(self.backend, self.proxy)

    def test_replaced_embedding_dimension_method_cannot_claim_trusted_scope(self):
        self.proxy.embedding.get_sentence_embedding_dimension = lambda: 2
        with self.assertRaises(WindowError):
            _require_publication_model_proxy(self.backend, self.proxy)

    def test_real_checkpoint_wrapper_retains_proxy_and_rejects_tampering(self):
        from test_checkpoint_publication import CheckpointPublicationTests
        from window_runtime import WindowRuntime
        fixture = CheckpointPublicationTests()
        fixture.setUp(); self.addCleanup(fixture.tearDown)
        self.backend.MODELS['embedding'] = fixture.model
        self.backend.model_path = lambda model: 'unused'
        self.backend.rerank_model = self.proxy.rerank_model
        self.backend._embedding = self.backend._reranker = None
        settings = {'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST': fixture.receipt['manifestPath'],
                    'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST_SHA256': fixture.receipt['manifestSha256'],
                    'CONSENSE_RERANKER_CHECKPOINT_MANIFEST': '',
                    'CONSENSE_RERANKER_CHECKPOINT_MANIFEST_SHA256': ''}
        with patch.dict(os.environ, settings):
            runtime = WindowRuntime(self.backend)
            self.backend._windows_runtime = runtime
            _require_publication_model_proxy(self.backend, self.proxy)
            wrapper = self.backend.embedding_model
            self.assertIs(self.proxy.embedding, wrapper())
            with patch.dict(os.environ, {'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST_SHA256': '1' * 64}):
                with self.assertRaises(WindowError):
                    wrapper()
            wrapper.__defaults__ = (lambda: self.proxy.embedding, 'embedding')
            with self.assertRaises(WindowError):
                _require_publication_model_proxy(self.backend, self.proxy)
            self.backend.embedding_model = lambda: self.proxy.embedding
            with self.assertRaises(WindowError):
                _require_publication_model_proxy(self.backend, self.proxy)

    def test_admission_failure_cannot_claim_no_model_and_preserves_error(self):
        with patch.object(FakeSession, 'start', side_effect=RuntimeError('authored admission failed')):
            with self.assertRaisesRegex(RuntimeError, 'authored admission failed'):
                with self.scope():
                    self.proxy.embedding_model().get_sentence_embedding_dimension()
        with self.assertRaises(WindowError):
            self.observer.publication_verified()


if __name__ == '__main__':
    unittest.main()
