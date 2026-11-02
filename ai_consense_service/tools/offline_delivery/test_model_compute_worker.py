"""Fixed GPU model-computation seam; no model or container is started."""
import base64
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import request_stage
import model_compute_worker


class ModelComputeTest(unittest.TestCase):
    def test_embedding_uses_only_bound_model_computation_and_persists_vectors(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            identity = {'model': {'name': 'fixture'}, 'files': [{'sha256': 'a' * 64}]}
            runtime = {'device': 'cuda', 'dtype': 'float32', 'batchSize': 2}
            server = types.SimpleNamespace(
                window_runtime=lambda: types.SimpleNamespace(logical_identity=lambda kind: identity, inference_cap=lambda kind: 8192),
                model_runtime=lambda kind: runtime,
                encode_embeddings=lambda texts: types.SimpleNamespace(tolist=lambda: [[0.25, 0.75] for text in texts]),
                embedding_model=lambda: types.SimpleNamespace(get_sentence_embedding_dimension=lambda: 2))
            binding = {'modelIdentity': identity, 'runtime': runtime, 'inferenceCap': 8192}
            profile = {'adapter': 'consense.model-compute.v1'}
            operation = request_stage.envelope('document-embedding', json.dumps({'kind':'embedding','texts':['one']}).encode(), binding, profile)
            directory = root/'gpu-operations'/operation['operationId']; directory.mkdir(parents=True)
            request, result = directory/'request.json', directory/'response.json'
            request_stage.save(request, operation)
            env = {'CONSENSE_FIXED_WORKSPACE':str(root), 'CONSENSE_FIXED_CONTAINER_ID':'fixture',
                   'CONSENSE_FIXED_OPERATION_ID':operation['operationId'], 'CONSENSE_OPERATION_REQUEST':str(request),
                   'CONSENSE_OPERATION_RESULT':str(result), 'CONSENSE_OPERATION_REQUEST_SHA256':request_stage.sha(request)}
            with patch.dict(os.environ, env, clear=True), patch.dict(sys.modules, {'server':server}):
                response = model_compute_worker.execute(request, result)
            self.assertEqual({'dimension':2,'vectors':[[0.25,0.75]]}, json.loads(base64.b64decode(response['bodyBase64'])))
            self.assertEqual(response, json.loads(result.read_bytes()))

    def test_changed_model_identity_is_rejected_before_model_loading(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            server = types.SimpleNamespace(
                window_runtime=lambda: types.SimpleNamespace(logical_identity=lambda kind: {'model':'other'}, inference_cap=lambda kind: 512),
                model_runtime=lambda kind: {'device':'cuda'},
                encode_embeddings=lambda texts: self.fail('mismatched model must not be called'))
            operation = request_stage.envelope('document-embedding', b'{"kind":"embedding","texts":["one"]}',
                {'modelIdentity':{'model':'expected'}, 'runtime':{'device':'cuda'}, 'inferenceCap':512},
                {'adapter':'consense.model-compute.v1'})
            directory = root/'gpu-operations'/operation['operationId']; directory.mkdir(parents=True)
            request, result = directory/'request.json', directory/'response.json'
            request_stage.save(request, operation)
            env = {'CONSENSE_FIXED_WORKSPACE':str(root), 'CONSENSE_FIXED_CONTAINER_ID':'fixture',
                   'CONSENSE_FIXED_OPERATION_ID':operation['operationId'], 'CONSENSE_OPERATION_REQUEST':str(request),
                   'CONSENSE_OPERATION_RESULT':str(result), 'CONSENSE_OPERATION_REQUEST_SHA256':request_stage.sha(request)}
            with patch.dict(os.environ, env, clear=True), patch.dict(sys.modules, {'server':server}):
                with self.assertRaisesRegex(ValueError, 'model identity or runtime differs'):
                    model_compute_worker.execute(request, result)
            self.assertFalse(result.exists())

    def test_reranker_scores_only_the_bound_pairs_in_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            identity, runtime = {'model':'reranker'}, {'device':'cuda', 'batchSize':2}
            calls = []
            class Scores:
                def reshape(self, shape):
                    self.assert_shape = shape
                    return self
                def tolist(self): return [0.7, 0.2]
            def predict(pairs, **options):
                calls.append((pairs, options))
                return Scores()
            server = types.SimpleNamespace(
                window_runtime=lambda: types.SimpleNamespace(logical_identity=lambda kind: identity, inference_cap=lambda kind: 1024),
                model_runtime=lambda kind: runtime, MODEL_SETTINGS={'reranker':{'batchSize':2}},
                rerank_model=lambda: types.SimpleNamespace(predict=predict))
            operation = request_stage.envelope('query-embedding-rerank',
                b'{"kind":"reranker","pairs":[["q","one"],["q","two"]]}',
                {'modelIdentity':identity, 'runtime':runtime, 'inferenceCap':1024}, {'adapter':'consense.model-compute.v1'})
            directory = root/'gpu-operations'/operation['operationId']; directory.mkdir(parents=True)
            request, result = directory/'request.json', directory/'response.json'
            request_stage.save(request, operation)
            env = {'CONSENSE_FIXED_WORKSPACE':str(root), 'CONSENSE_FIXED_CONTAINER_ID':'fixture',
                   'CONSENSE_FIXED_OPERATION_ID':operation['operationId'], 'CONSENSE_OPERATION_REQUEST':str(request),
                   'CONSENSE_OPERATION_RESULT':str(result), 'CONSENSE_OPERATION_REQUEST_SHA256':request_stage.sha(request)}
            with patch.dict(os.environ, env, clear=True), patch.dict(sys.modules, {'server':server}):
                response = model_compute_worker.execute(request, result)
            self.assertEqual({'scores':[0.7, 0.2]}, json.loads(base64.b64decode(response['bodyBase64'])))
            self.assertEqual([['q', 'one'], ['q', 'two']], calls[0][0])

    def test_dimension_comes_from_the_bound_loaded_embedding_model(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            identity, runtime = {'model':'embedding'}, {'device':'cuda'}
            server = types.SimpleNamespace(
                window_runtime=lambda: types.SimpleNamespace(logical_identity=lambda kind: identity, inference_cap=lambda kind: 8192),
                model_runtime=lambda kind: runtime,
                embedding_model=lambda: types.SimpleNamespace(get_sentence_embedding_dimension=lambda: 1024))
            operation = request_stage.envelope('document-embedding', b'{"kind":"dimension"}',
                {'modelIdentity':identity, 'runtime':runtime, 'inferenceCap':8192}, {'adapter':'consense.model-compute.v1'})
            directory = root/'gpu-operations'/operation['operationId']; directory.mkdir(parents=True)
            request, result = directory/'request.json', directory/'response.json'
            request_stage.save(request, operation)
            env = {'CONSENSE_FIXED_WORKSPACE':str(root), 'CONSENSE_FIXED_CONTAINER_ID':'fixture',
                   'CONSENSE_FIXED_OPERATION_ID':operation['operationId'], 'CONSENSE_OPERATION_REQUEST':str(request),
                   'CONSENSE_OPERATION_RESULT':str(result), 'CONSENSE_OPERATION_REQUEST_SHA256':request_stage.sha(request)}
            with patch.dict(os.environ, env, clear=True), patch.dict(sys.modules, {'server':server}):
                response = model_compute_worker.execute(request, result)
            self.assertEqual({'dimension':1024}, json.loads(base64.b64decode(response['bodyBase64'])))


if __name__ == '__main__': unittest.main()
