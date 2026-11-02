"""The existing model methods use the bound fixed-GPU computation interface."""
import unittest
import time
from types import SimpleNamespace

import numpy as np
from remote_gpu_models import RemoteModels, operation_context


class RemoteModelsTest(unittest.TestCase):
    def test_existing_dimension_encode_and_predict_preserve_order(self):
        calls = []
        class Client:
            def compute(self, stage, binding, body, timeout, cancelled=None):
                calls.append((stage, binding, body))
                if body['kind'] == 'dimension': return {'dimension':2}
                if body['kind'] == 'embedding': return {'dimension':2, 'vectors':[[.25,.75]]}
                return {'scores':[.7,.2]}
        runtime = SimpleNamespace(logical_identity=lambda kind: {'model':kind}, inference_cap=lambda kind: 512)
        backend = SimpleNamespace(MODELS={'embedding':{'name':'embed'}, 'reranker':{'name':'rank'}},
            MODEL_SETTINGS={'embedding':{'batchSize':2,'maxTokens':512}, 'reranker':{'batchSize':2,'maxTokens':512}},
            window_runtime=lambda: runtime)
        proxy = RemoteModels(backend, Client(), dtype='float32', serial_offload=True, timeout=10)
        self.assertEqual(2, proxy.embedding_model().get_sentence_embedding_dimension())
        np.testing.assert_allclose([[.25,.75]], proxy.encode_embeddings(['text']))
        np.testing.assert_allclose([.7,.2], proxy.rerank_model().predict([['q','one'],['q','two']], batch_size=2,
            convert_to_numpy=True, show_progress_bar=False))
        self.assertEqual(['dimension','embedding','reranker'], [row[2]['kind'] for row in calls])
        self.assertTrue(all(row[1]['runtime']['device'] == 'cuda' for row in calls))

    def test_wrong_dimension_is_rejected_before_index_use(self):
        class Client:
            def compute(self, *args, **kwargs): return {'dimension':2, 'vectors':[[1.0]]}
        backend = SimpleNamespace(MODELS={'embedding':{},'reranker':{}},
            MODEL_SETTINGS={'embedding':{'batchSize':2,'maxTokens':512},'reranker':{'batchSize':2,'maxTokens':512}},
            window_runtime=lambda: SimpleNamespace(logical_identity=lambda kind: {'model':kind}, inference_cap=lambda kind: 512))
        proxy = RemoteModels(backend, Client(), dtype='float32', serial_offload=True, timeout=10)
        with self.assertRaisesRegex(ValueError, 'embedding result'):
            proxy.encode_embeddings(['text'])

    def test_dimension_cannot_change_between_setup_and_encoding(self):
        class Client:
            def compute(self, stage, binding, body, timeout, cancelled=None):
                if body['kind'] == 'dimension': return {'dimension':2}
                return {'dimension':3, 'vectors':[[1.0, 0.0, 0.0]]}
        backend = SimpleNamespace(MODELS={'embedding':{},'reranker':{}},
            MODEL_SETTINGS={'embedding':{'batchSize':2,'maxTokens':512},'reranker':{'batchSize':2,'maxTokens':512}},
            window_runtime=lambda: SimpleNamespace(logical_identity=lambda kind: {'model':kind}, inference_cap=lambda kind: 512))
        proxy = RemoteModels(backend, Client(), dtype='float32', serial_offload=True, timeout=10)
        self.assertEqual(2, proxy.embedding_model().get_sentence_embedding_dimension())
        with self.assertRaisesRegex(ValueError, 'dimension changed'):
            proxy.encode_embeddings(['text'])

    def test_owned_cancellation_and_deadline_reach_compute(self):
        calls = []
        class Client:
            def compute(self, stage, binding, body, timeout, cancelled=None):
                calls.append((timeout, cancelled))
                return {'dimension':2}
        backend = SimpleNamespace(MODELS={'embedding':{},'reranker':{}},
            MODEL_SETTINGS={'embedding':{'batchSize':2,'maxTokens':512},'reranker':{'batchSize':2,'maxTokens':512}},
            window_runtime=lambda: SimpleNamespace(logical_identity=lambda kind: {'model':kind}, inference_cap=lambda kind: 512))
        proxy = RemoteModels(backend, Client(), dtype='float32', serial_offload=True, timeout=10)
        cancelled = lambda: False
        with operation_context(cancelled, time.monotonic()+2):
            self.assertEqual(2, proxy.embedding_model().get_sentence_embedding_dimension())
        self.assertIs(cancelled, calls[0][1]); self.assertGreater(calls[0][0],0); self.assertLessEqual(calls[0][0],2)

    def test_finite_double_outside_float32_range_is_rejected(self):
        class Client:
            def compute(self, *args, **kwargs): return {'dimension':1, 'vectors':[[1e300]]}
        backend = SimpleNamespace(MODELS={'embedding':{},'reranker':{}},
            MODEL_SETTINGS={'embedding':{'batchSize':2,'maxTokens':512},'reranker':{'batchSize':2,'maxTokens':512}},
            window_runtime=lambda: SimpleNamespace(logical_identity=lambda kind: {'model':kind}, inference_cap=lambda kind: 512))
        proxy = RemoteModels(backend, Client(), dtype='float32', serial_offload=True, timeout=10)
        with self.assertRaisesRegex(ValueError, 'float32'):
            proxy.encode_embeddings(['text'])


if __name__ == '__main__': unittest.main()
