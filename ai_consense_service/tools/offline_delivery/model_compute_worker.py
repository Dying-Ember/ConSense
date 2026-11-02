"""Run only the existing model computation for one assigned fixed-GPU operation."""
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import sys

from delivery import require
from request_stage import encoded, save, verify_operation, verify_response


def execute(request_path, result_path):
    raw = Path(request_path).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == os.environ.get('CONSENSE_OPERATION_REQUEST_SHA256'), 'assigned request changed')
    operation = verify_operation(json.loads(raw))
    root = Path(os.environ['CONSENSE_FIXED_WORKSPACE']).resolve()
    directory = root / 'gpu-operations' / operation['operationId']
    require(root.is_dir() and os.environ.get('CONSENSE_FIXED_CONTAINER_ID')
            and os.environ.get('CONSENSE_FIXED_OPERATION_ID') == operation['operationId']
            and Path(request_path).resolve() == directory / 'request.json'
            and Path(result_path).resolve() == directory / 'response.json'
            and os.environ.get('CONSENSE_OPERATION_REQUEST') == str(request_path)
            and os.environ.get('CONSENSE_OPERATION_RESULT') == str(result_path), 'fixed operation identity differs')
    require(operation['profile'].get('adapter') == 'consense.model-compute.v1', 'model computation profile required')
    value = json.loads(base64.b64decode(operation['bodyBase64'], validate=True))
    action = value.get('kind')
    require((action in ('embedding', 'dimension') and operation['stage'] in ('document-embedding', 'query-embedding-rerank'))
            or (action == 'reranker' and operation['stage'] == 'query-embedding-rerank'), 'model computation stage differs')
    kind = 'embedding' if action == 'dimension' else action
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'vetting_eval'))
    import server
    runtime = server.window_runtime()
    binding = operation['source']
    require(runtime.logical_identity(kind) == binding.get('modelIdentity')
            and server.model_runtime(kind) == binding.get('runtime')
            and binding['runtime'].get('device') == 'cuda'
            and runtime.inference_cap(kind) == binding.get('inferenceCap'), 'model identity or runtime differs')
    if action == 'dimension':
        require(set(value) == {'kind'}, 'dimension request cannot include other fields')
        dimension = int(server.embedding_model().get_sentence_embedding_dimension())
        require(dimension > 0, 'embedding dimension unavailable')
        body = {'dimension':dimension}
    elif kind == 'embedding':
        require(set(value) == {'kind', 'texts'} and isinstance(value['texts'], list) and value['texts']
                and all(isinstance(text, str) and text for text in value['texts']), 'nonempty embedding texts required')
        vectors = server.encode_embeddings(value['texts']).tolist()
        dimension = int(server.embedding_model().get_sentence_embedding_dimension())
        require(dimension > 0 and len(vectors) == len(value['texts']) and all(
            len(row) == dimension and all(isinstance(x, (int, float)) and math.isfinite(x) for x in row) for row in vectors), 'embedding result differs')
        body = {'dimension': dimension, 'vectors': vectors}
    else:
        require(set(value) == {'kind', 'pairs'} and isinstance(value['pairs'], list) and value['pairs']
                and all(isinstance(pair, list) and len(pair) == 2 and all(isinstance(x, str) and x for x in pair)
                        for pair in value['pairs']), 'nonempty rerank pairs required')
        scores = server.rerank_model().predict(value['pairs'], batch_size=server.MODEL_SETTINGS['reranker']['batchSize'],
            show_progress_bar=False, convert_to_numpy=True).reshape(-1).tolist()
        require(len(scores) == len(value['pairs']) and all(isinstance(x, (int, float)) and math.isfinite(x) for x in scores), 'rerank result differs')
        body = {'scores': scores}
    require(runtime.logical_identity(kind) == binding['modelIdentity'] and server.model_runtime(kind) == binding['runtime']
            and hashlib.sha256(Path(request_path).read_bytes()).hexdigest() == os.environ['CONSENSE_OPERATION_REQUEST_SHA256'],
            'model identity or request changed during execution')
    response = {'contract':'consense.gpu-operation-response.v1', 'bindingSha256':operation['bindingSha256'],
                'status':200, 'headers':{'content-type':'application/json'}, 'bodyBase64':base64.b64encode(encoded(body)).decode()}
    verify_response(response, operation)
    save(Path(result_path), response)
    return response


if __name__ == '__main__':
    execute(os.environ['CONSENSE_OPERATION_REQUEST'], os.environ['CONSENSE_OPERATION_RESULT'])
