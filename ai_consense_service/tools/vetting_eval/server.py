"""Local OCR and dense/BM25/rerank API. Run one worker: python server.py."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import hmac
import io
import inspect
import json
import math
import os
from pathlib import Path
import re
import stat
import threading
import time
import uuid
import sys
import weakref
from typing import Annotated

HERE = Path(__file__).resolve().parent
from workspace_root import resolve_workspace
WORKSPACE = resolve_workspace(HERE, os.environ)
os.environ.setdefault('HF_HOME', str(WORKSPACE / 'tmp/vetting_models'))
os.environ.setdefault('HF_HUB_DISABLE_TELEMETRY', '1')
os.environ.setdefault('TOKENIZERS_PARALLELISM', 'false')

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator
from ocr_runtime import create_ocr_engine

from window_runtime import WindowRuntime
from token_windows import WindowError, canonical_chunks
MODEL_DTYPE = os.environ.get('CONSENSE_MODEL_DTYPE', 'float32')
if MODEL_DTYPE not in {'float32', 'float16'}:
    raise ValueError('CONSENSE_MODEL_DTYPE must be explicit float32 or float16')
SERIAL_OFFLOAD = os.environ.get('CONSENSE_MODEL_SERIAL_OFFLOAD', '1') == '1'
_windows_runtime = None
_window_observer = None
_request_evidence = None

def window_runtime():
    global _windows_runtime
    if _windows_runtime is None:
        _windows_runtime = WindowRuntime(sys.modules[__name__])
    return _windows_runtime

def release_other_model(target):
    global _embedding, _reranker
    if device() != 'cuda' or not SERIAL_OFFLOAD:
        return
    other = 'reranker' if target == 'embedding' else 'embedding'
    loaded = _reranker if other == 'reranker' else _embedding
    if loaded is None:
        return
    import gc, torch
    torch.cuda.synchronize()
    before = {'allocated': torch.cuda.memory_allocated(), 'reserved': torch.cuda.memory_reserved()}
    if other == 'reranker':
        _reranker = None
    else:
        _embedding = None
    del loaded
    gc.collect()
    torch.cuda.empty_cache()
    window_runtime().emit('serial-model-offload', {'releasedKind': other, 'targetKind': target, 'beforeBytes': before,
        'afterBytes': {'allocated': torch.cuda.memory_allocated(), 'reserved': torch.cuda.memory_reserved()}})

def verify_loaded_model(model, kind):
    parameter = next(model.parameters())
    expected = model_runtime(kind)
    if str(parameter.dtype) != 'torch.' + expected['dtype'] or parameter.device.type != expected['device']:
        raise WindowError('MODEL_RUNTIME_MISMATCH', 'Loaded model dtype/device differs from frozen runtime')
    window_runtime().emit('window-model-loaded', {'kind': kind, 'runtime': expected, 'actualDtype': str(parameter.dtype),
        'actualDevice': parameter.device.type})


CONFIG = json.loads((HERE / 'samples.json').read_text(encoding='utf-8'))
MODELS = {key: CONFIG[key] for key in ('embedding', 'reranker')}
ROLES = {'tender', 'standard', 'project_fact', 'package_manifest'}


def model_settings(environ):
    """Resolve independent startup settings without importing an inference engine."""
    result = {}
    for kind, prefix in (('embedding', 'CONSENSE_EMBED'), ('reranker', 'CONSENSE_RERANK')):
        selected = {}
        for field, suffix, default in (('maxTokens', 'MAX_TOKENS', 512), ('batchSize', 'BATCH_SIZE', 4)):
            name = prefix + '_' + suffix
            try:
                value = int(environ.get(name, str(default)))
            except (ValueError, TypeError) as error:
                raise ValueError(name + ' must be a positive integer') from error
            if value <= 0 or (field == 'maxTokens' and value > 8192):
                raise ValueError(name + (' must be between 1 and 8192' if field == 'maxTokens' else ' must be positive'))
            selected[field] = value
        result[kind] = selected
    return result


MODEL_SETTINGS = model_settings(os.environ)
# Compatibility fields describe embedding only; independent values are in runtime.
MAX_TOKENS = MODEL_SETTINGS['embedding']['maxTokens']
BATCH_SIZE = MODEL_SETTINGS['embedding']['batchSize']
RRF_CONSTANT = 60
STATE = Path(os.environ.get('CONSENSE_RETRIEVAL_DATA', str(WORKSPACE / 'tmp/vetting_server'))).resolve()
OFFLINE = True  # Isolated window recipe never downloads model artifacts.
LOCK = threading.RLock()
_embedding = _reranker = _ocr = _database = None
_ocr_recipe_sha256 = None
_database_configuration = None
_database_binding = None
_database_release_unknown = False
_projects = {}
_missing_index_history = set()
_migrated_index = None
_selected_device = None
_remote_models = None
_business_config = None
app = FastAPI(title='ConSense local vetting models', version='1.0.0')


def forget_project(key):
    with LOCK:
        if key in _projects:
            _missing_index_history.add(key)
        _projects.pop(key, None)


def gpu_coordinator():
    if _remote_models is not None:
        return None
    path = str(HERE.parent / 'offline_delivery')
    if path not in sys.path:
        sys.path.insert(0, path)
    import coordinator
    return coordinator if coordinator.configured(os.environ) else None


class Chunk(BaseModel):
    model_config = ConfigDict(extra='allow')
    id: str
    content: str = Field(min_length=1, max_length=200000)
    role: str = 'tender'
    fileKey: str | None = None
    documentId: int | str | None = None
    anchor: str | None = None
    pageNo: int | None = None

    @field_validator('pageNo', mode='before')
    @classmethod
    def physical_page_number(cls, value):
        # The Java evidence DTO uses P12; normalize it to the same physical page
        # integer accepted from direct Python callers. Never accept Word anchors
        # or infer a page from a clause identifier.
        if value is None:
            return None
        if isinstance(value, str) and re.fullmatch(r'P?[1-9]\d*', value):
            return int(value.removeprefix('P'))
        if isinstance(value, int) and not isinstance(value, bool) and value > 0:
            return value
        raise ValueError('pageNo must be a positive physical page integer or P-prefixed page number')


class IndexRequest(BaseModel):
    projectId: int | str
    chunks: list[Chunk] = Field(max_length=50000)
    rebuild: bool = Field(default=False, strict=True)
    initializationDecision: bool = Field(default=False, strict=True)


class RetrieveRequest(BaseModel):
    projectId: int | str
    query: str = Field(min_length=1, max_length=20000)
    role: str | None = None
    limit: int = Field(default=10, ge=1, le=100)
    candidates: int = Field(default=50, ge=1, le=500)
    sourceSha256: list[str] | None = Field(default=None, min_length=1, max_length=128)


def project_key(value):
    key = str(value).strip()
    if not key or len(key) > 200:
        raise HTTPException(422, 'Invalid projectId')
    return key


def digest(value):
    result = hashlib.sha256()
    for part in canonical_chunks(value, allow_nan=True):
        result.update(part)
    return result.hexdigest()


LATIN_TOKEN_PATTERN = r'[a-z0-9]+(?:[./()-][a-z0-9]+)*'
# Fixed code-point ranges, independent of installed Unicode tables. Include
# Han common/extension/compatibility blocks, common kana and Hangul blocks.
CJK_TOKEN_RANGES = (
    r'\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff'
    r'\U00020000-\U0002a6df\U0002a700-\U0002b73f'
    r'\U0002b740-\U0002b81f\U0002b820-\U0002ceaf'
    r'\U0002ceb0-\U0002ebef\U0002ebf0-\U0002ee5f'
    r'\U0002f800-\U0002fa1f\U00030000-\U0003134f'
    r'\U00031350-\U000323af'
    r'\u3040-\u309f\u30a0-\u30ff\u31f0-\u31ff\uff66-\uff9f'
    r'\u1100-\u11ff\u3130-\u318f\ua960-\ua97f\uac00-\ud7af\ud7b0-\ud7ff'
)
TOKEN_PATTERN = '(?P<latin>' + LATIN_TOKEN_PATTERN + ')|(?P<cjk>[' + CJK_TOKEN_RANGES + ']+)'
TOKENIZER_ALGORITHM = 'casefold-latin-v1-cjk-unigram-bigram-source-order-v1'
_LEXICAL_TOKEN_RE = re.compile(TOKEN_PATTERN)


def tokens(value):
    result = []
    for match in _LEXICAL_TOKEN_RE.finditer(value.casefold()):
        run = match.group('cjk')
        if run is None:
            result.append(match.group('latin'))
            continue
        # At each source position emit the character, then its adjacent bigram.
        # Preserve repeated occurrences and do not bridge punctuation/Latin.
        for position, character in enumerate(run):
            result.append(character)
            if position + 1 < len(run):
                result.append(run[position:position + 2])
    return result


def device():
    global _selected_device
    if _selected_device is not None:
        return _selected_device
    choice = os.environ.get('CONSENSE_MODEL_DEVICE', 'auto')
    if choice not in {'auto', 'cpu', 'cuda'}:
        raise RuntimeError('CONSENSE_MODEL_DEVICE must be auto, cpu or cuda')
    if choice == 'cpu':
        _selected_device = 'cpu'
        return _selected_device
    import torch
    if choice == 'cuda' and not torch.cuda.is_available():
        raise RuntimeError('CUDA explicitly requested but unavailable')
    torch.set_num_threads(4)
    _selected_device = ('cuda' if torch.cuda.is_available() else 'cpu') if choice == 'auto' else choice
    return _selected_device


def model_options():
    import torch
    selected = device()
    if selected == 'cpu' and MODEL_DTYPE != 'float32':
        raise ValueError('CPU recipe requires float32; float16 is a separate unsupported CPU recipe')
    return selected, {'torch_dtype': getattr(torch, MODEL_DTYPE)}


def model_runtime(kind):
    selected = device()
    return {**MODEL_SETTINGS[kind], **MODELS[kind], 'device': selected, 'dtype': MODEL_DTYPE,
            'serialOffload': selected == 'cuda' and SERIAL_OFFLOAD}


def retrieval_settings(*, observe_tokenizer_identity=True):
    from rank_bm25 import BM25Okapi
    parameters = inspect.signature(BM25Okapi).parameters
    return {'requestDefaults': {'candidates': 50, 'limit': 10},
            'rrfConstant': RRF_CONSTANT, 'rrfWeights': {'dense': 1, 'bm25': 1},
            'bm25': {'implementation': 'rank_bm25.BM25Okapi',
                     'parameterSource': 'installed library defaults',
                     **{key: parameters[key].default for key in ('k1', 'b', 'epsilon')}},
            'tokenPattern': TOKEN_PATTERN, 'tokenizerAlgorithm': TOKENIZER_ALGORITHM,
            'latinTokenPattern': LATIN_TOKEN_PATTERN, 'roleFilter': True,
            'storage': 'Qdrant Server' if os.environ.get('CONSENSE_QDRANT_URL') else 'Qdrant Python local', 'workers': 1,
            'sourceWindows': (window_runtime().retrieval_recipe()if observe_tokenizer_identity
                              else window_runtime().retrieval_recipe(observe_tokenizer_identity=False))}


def model_path(config):
    # An absolute snapshot path prevents Transformers adapter probes reaching
    # Hugging Face even when individual loaders receive local_files_only=True.
    from huggingface_hub import snapshot_download
    return snapshot_download(config['name'], revision=config['revision'], local_files_only=OFFLINE)


def embedding_model():
    global _embedding
    release_other_model('embedding')
    if _embedding is None:
        from sentence_transformers import SentenceTransformer
        selected, options = model_options()
        loaded = SentenceTransformer(model_path(MODELS['embedding']), device=selected, model_kwargs=options, local_files_only=True)
        loaded.max_seq_length = window_runtime().inference_cap('embedding')
        if loaded[0].do_lower_case or getattr(loaded, 'default_prompt_name', None):
            raise WindowError('EMBEDDING_PREPROCESSING_MISMATCH', 'Recipe requires strip/no lower case/no implicit prompt')
        loaded[0].tokenizer = window_runtime().guard(loaded[0].tokenizer, 'embedding')
        verify_loaded_model(loaded, 'embedding')
        # A failed dtype/device/preprocessing check must never publish a model
        # that the next call could reuse without repeating validation.
        _embedding = loaded
    return _embedding


def rerank_model():
    global _reranker
    release_other_model('reranker')
    if _reranker is None:
        from sentence_transformers import CrossEncoder
        selected, options = model_options()
        loaded = CrossEncoder(model_path(MODELS['reranker']), device=selected, model_kwargs=options,
            max_length=window_runtime().inference_cap('reranker'), local_files_only=True)
        loaded.tokenizer = window_runtime().guard(loaded.tokenizer, 'reranker')
        verify_loaded_model(loaded, 'reranker')
        _reranker = loaded
    return _reranker


def encode_embeddings(texts):
    return embedding_model().encode(texts, batch_size=MODEL_SETTINGS['embedding']['batchSize'],
        normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False)


def rerank_candidates(query, candidates):
    return window_runtime().rerank(query, candidates)


def database():
    global _database, _database_configuration, _database_binding, _database_release_unknown
    from migrated_qdrant_index import configured, ENV
    from migration_project_registry import REGISTRY
    from token_windows import fail
    names = ('CONSENSE_QDRANT_URL', 'CONSENSE_QDRANT_API_KEY', ENV, ENV + '_SHA256', REGISTRY, REGISTRY + '_SHA256')
    def configuration():
        return {name: os.environ[name] for name in names if name in os.environ}
    with LOCK:
        fail(not _database_release_unknown, 'QDRANT_BACKEND_RELEASE_UNKNOWN', 'Previous Qdrant client release is unknown')
        snapshot, state = configuration(), STATE
        current = (str(state), snapshot.get('CONSENSE_QDRANT_URL'), snapshot.get('CONSENSE_QDRANT_API_KEY'))
        if _database is None:
            from qdrant_backend import connect
            try:
                client = connect(state, snapshot)
            except Exception:
                raise WindowError('QDRANT_BACKEND_UNAVAILABLE', 'Qdrant backend is unavailable') from None
            try:
                fail(str(STATE) == str(state) and configuration() == snapshot,
                     'QDRANT_BACKEND_CONFIG_CHANGED', 'Qdrant backend configuration changed during connection')
                _database, _database_configuration, _database_binding = client, current, (client, current)
            except BaseException as error:
                _database, _database_configuration, _database_binding = None, None, None
                try:
                    client.close()
                except BaseException as close_error:
                    _database_release_unknown = True
                    if not isinstance(close_error, Exception):
                        raise
                    raise WindowError('QDRANT_BACKEND_RELEASE_UNKNOWN', 'Qdrant client release is unknown') from None
                if not isinstance(error, Exception):
                    raise
                raise WindowError('QDRANT_BACKEND_CONFIG_CHANGED', 'Qdrant backend configuration changed during connection') from None
        else:
            fail(_database_configuration == current or (_database_configuration is None and not configured(snapshot)
                 and REGISTRY not in snapshot and REGISTRY + '_SHA256' not in snapshot),
                 'QDRANT_BACKEND_CONFIG_CHANGED', 'Cached Qdrant backend configuration differs; start a new runtime')
        return _database


def metadata_path(key):
    return STATE / 'projects' / (digest(key) + '.json')


def request_evidence():
    global _request_evidence
    from oer_request_evidence import RequestEvidence
    root = STATE / 'run-evidence'
    with LOCK:
        if _request_evidence is None:
            _request_evidence = RequestEvidence(root)
        elif _request_evidence.root != root:
            raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Request journal state root changed')
        return _request_evidence


def verify_project_publication(key, stored):
    journal = request_evidence()
    path = metadata_path(key)
    # A pending publication blocks even an absent or old metadata file.
    journal.verify_index(path, None, key)
    if stored is None:
        return
    if 'publicationJournal' in stored:
        journal.verify_index(path, stored, key)
    elif _remote_models is not None:
        raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN',
                          'Remote index without a verified publication journal is not accepted')


def recover_project_publication_for_read(key):
    """Recover a published ordinary index only inside its request-owned source frame."""
    if _remote_models is None:
        return
    journal = request_evidence()
    # Serialize the checked transition with the journal's existing recovery lock.
    with journal._lock:
        return _recover_project_publication_for_read_locked(key, journal)


def _recover_project_publication_for_read_locked(key, journal):
    if str(key) in journal._verified:
        # Each request using a recovered publication owns its own frame. A
        # later frame failure must revoke the earlier in-process verification.
        runtime = window_runtime()
        registry = getattr(runtime, 'source_registry', None)
        frame_var = getattr(registry, '_validation_frame', None)
        frame = frame_var.get() if frame_var is not None else None
        if isinstance(frame, dict):
            from contextlib import ExitStack
            if (not isinstance(frame.get('leaseStack'), ExitStack)
                    or frame.get('guard') != runtime.algorithms):
                raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN',
                                  'Recovery requires the current source-owned read frame')
            frame.setdefault('recoveredIndexKeys', set()).add(str(key))
        return
    path = metadata_path(key)
    if not path.exists() and not path.is_symlink():
        return
    from contextlib import ExitStack
    runtime = window_runtime()
    registry = getattr(runtime, 'source_registry', None)
    frame_var = getattr(registry, '_validation_frame', None)
    frame = frame_var.get() if frame_var is not None else None
    if (not isinstance(frame, dict) or not isinstance(frame.get('leaseStack'), ExitStack)
            or frame.get('guard') != runtime.algorithms):
        journal.verify_index(path, None, key)
        raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN',
                          'Recovery requires the current source-owned read frame')
    from migration_project_registry import runtime_route
    if runtime_route(sys.modules[__name__], key, runtime)['mode'] != 'ordinary':
        return
    if path.is_symlink():
        raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Index metadata path is unsafe')
    try:
        metadata = json.loads(path.read_bytes())
    except (OSError, ValueError) as error:
        raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN',
                          'Index metadata cannot be inspected for recovery') from error
    if not isinstance(metadata, dict) or 'publicationJournal' not in metadata:
        return
    frame.setdefault('recoveredIndexKeys', set()).add(str(key))
    journal.recover_index(path, metadata, key)


def require_project_metadata_for_load(key):
    if _remote_models is not None:
        request_evidence().require_verified_index_for_load(key)


def signature(chunks):
    return window_runtime().signature(chunks)


def load_project_metadata(key):
    """Reuse resident parents; changed records decode once and owner exit hashes once."""
    recover_project_publication_for_read(key)
    path = metadata_path(key)
    metrics = {'bytesRead': 0, 'jsonDecodes': 0}
    registry = getattr(window_runtime(), 'source_registry', None)
    frame = registry._validation_frame.get() if registry is not None and hasattr(registry, '_validation_frame') else None
    def stamp():
        stat = path.stat()
        return str(path.resolve()), stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns, stat.st_ino
    def identity():
        result, size = hashlib.sha256(), 0
        resolved = str(path.resolve())
        with path.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                size += len(block)
                result.update(block)
        metrics['bytesRead'] += size
        if str(path.resolve()) != resolved:
            raise HTTPException(409, 'Index metadata locator changed')
        return resolved, size, result.hexdigest()
    if frame is not None and key in frame['indexMetadata']:
        record = frame['indexMetadata'][key]
        if stamp() != record['stamp']:
            raise HTTPException(409, 'Index metadata changed during this operation')
        verify_project_publication(key, record['stored'])
        return record['stored']
    verify_project_publication(key, None)
    if not path.exists():
        raise HTTPException(404, 'Project is not indexed')
    require_project_metadata_for_load(key)
    before_stamp = stamp()
    stored = _projects.get(key)
    expected = stored.get('_metadataFileIdentity') if stored is not None else None
    if stored is None or stored.get('_metadataFileStamp') != before_stamp or expected is None or frame is None:
        raw = path.read_bytes()
        metrics['bytesRead'] += len(raw)
        actual = (str(path.resolve()), len(raw), hashlib.sha256(raw).hexdigest())
        expected = actual
        stored = json.loads(raw)
        metrics['jsonDecodes'] += 1
    if stamp() != before_stamp:
        raise HTTPException(409, 'Index metadata changed while loading')
    verify_project_publication(key, stored)
    stored['_metadataFileIdentity'] = expected
    stored['_metadataFileStamp'] = before_stamp
    _projects[key] = stored
    if frame is not None:
        frame['indexMetadata'][key] = {'stored': stored, 'stamp': before_stamp,
            'expected': expected, 'identity': identity, 'metrics': metrics}
    return stored


def read_project(key):
    global _migrated_index
    from migration_project_registry import runtime_route, REGISTRY
    from migrated_qdrant_index import configured
    from token_windows import fail
    try:
        if REGISTRY in os.environ or REGISTRY + '_SHA256' in os.environ or configured(os.environ) or _migrated_index is not None:
            verify_project_publication(key, None)
        runtime = window_runtime()
        route = runtime_route(sys.modules[__name__], key, runtime)
    except WindowError as error:
        raise HTTPException(409, {'kind':error.kind, 'message':str(error), 'metadata':error.metadata}) from error
    if route['mode'] != 'ordinary':
        try:
            registry = getattr(runtime, 'source_registry', None)
            frame_var = getattr(registry, '_validation_frame', None)
            frame = frame_var.get() if frame_var is not None else None
            record = frame.get('indexMetadata', {}).get(key) if isinstance(frame, dict) else None
            reader = record.get('migrationReader') if isinstance(record, dict) else None
            fail(isinstance(record, dict) and reader is not None
                 and (route['mode'] == 'migrated' or reader is _migrated_index)
                 and record.get('stored') is reader.metadata
                 and record.get('migrationSourceScopeVerified') is True
                 and callable(record.get('leaseCheck')),
                 'MIGRATED_INDEX_SOURCE_FRAME_REQUIRED',
                 'Migrated reads require a verified source frame and isolation lease')
            record['leaseCheck']()
            stored = reader.read(key, runtime, database)
            record['migrationObservation'] = getattr(reader, 'last_observation', None)
            return stored
        except WindowError as error:
            raise HTTPException(503 if error.kind in ('QDRANT_BACKEND_UNAVAILABLE', 'QDRANT_BACKEND_RELEASE_UNKNOWN') else 409,
                                {'kind':error.kind, 'message':str(error), 'metadata':error.metadata}) from error
    stored = load_project_metadata(key)
    if stored.get('projectId') != key or not window_runtime().signature_matches(stored.get('signature',{}),stored['chunks']):
        raise HTTPException(409, 'Index signature changed; re-index current documents')
    if stored['chunks'] and not database().collection_exists(stored['collection']):
        raise HTTPException(409, 'Index collection is missing; re-index current documents')
    _projects[key] = stored
    return stored


def validate_corpus(chunks):
    ids = set()
    for chunk in chunks:
        if chunk['id'] in ids:
            raise HTTPException(422, 'Duplicate chunk id: ' + chunk['id'])
        ids.add(chunk['id'])
        if chunk['role'] not in ROLES:
            raise HTTPException(422, 'Unsupported evidence role')
        if any(field in chunk for field in ('expected', 'gold_provenance', 'prohibited_claims')):
            raise HTTPException(422, 'Evaluation answers cannot enter the evidence index')
        for field in ('path', 'source', 'fileName', 'filename', 'sourcePath'):
            path = str(chunk.get(field, '')).replace('\\', '/').casefold()
            if any(marker in path for marker in ('2d_tender doc vetting comments',
                    '2e_questions and answers', 'vetting_cases.json')):
                raise HTTPException(422, 'Evaluation material cannot enter the evidence index')
        if not chunk['id'].strip() or not chunk['content'].strip():
            raise HTTPException(422, 'Chunk ids and contents must be nonempty')


@app.get('/health')
def health():
    coordinator = gpu_coordinator()
    if coordinator is not None:
        return coordinator.health(server=sys.modules[__name__])
    selected_device = 'cuda' if _remote_models is not None else device()
    cuda_allocated = None
    if selected_device == 'cuda' and _remote_models is None:
        import torch
        cuda_allocated = round(torch.cuda.memory_allocated()/2**20, 1)
    return {'status': 'ok', 'mode': 'remote-model-coordinator' if _remote_models is not None else ('server' if os.environ.get('CONSENSE_QDRANT_URL') else 'local'), 'device': selected_device, 'offline': OFFLINE,
        'models': MODELS, 'maxTokens': MODEL_SETTINGS['embedding']['maxTokens'],
        'batchSize': MODEL_SETTINGS['embedding']['batchSize'],
        'runtime': {kind: model_runtime(kind) for kind in ('embedding', 'reranker')},
        'retrieval': retrieval_settings(observe_tokenizer_identity=False),
        'indexSignatureVersion': window_runtime().signature_version(),
        'loaded': {'embedding': _embedding is not None, 'reranker': _reranker is not None, 'ocr': _ocr is not None},
        'cudaAllocatedMiB': cuda_allocated,
        **({'remoteModelReady': False, 'deviceScope': 'configured remote CUDA recipe; not a local GPU observation',
            'runtimeIdentityScope': 'configured remote recipe; each operation verifies the actual worker'} if _remote_models is not None else {}),
        'ocrEngine': 'Complete Paddle/MinerU API candidates' if os.environ.get('CONSENSE_COMPLETE_OCR_CONFIG') else 'RapidOCR CPU baseline',
        'limitations': ['OCR does not interpret table cells or deletion state',
            'Source-bound windows count query/special tokens; oversized queries fail without silent clipping',
            'Legacy maxTokens/batchSize health fields describe embedding only',
            'Run one server worker with one Qdrant storage owner']}


def _trusted_business_configuration():
    return _business_config


def _fixed_business_configuration(request):
    config = _trusted_business_configuration()
    if not isinstance(config, dict):
        raise HTTPException(503, 'Trusted business operation configuration is unavailable')
    config = dict(config)
    model, token, profile, execution, timeout = (config.get(name) for name in
        ('model', 'token', 'profileSha256', 'executionSha256', 'timeout'))
    verifier = config.get('verifyRelease')
    client = config.get('client', getattr(_remote_models, 'client', None))
    if (not isinstance(model, str) or not model.strip() or not isinstance(token, str) or not token
            or not isinstance(profile, str) or re.fullmatch('[a-f0-9]{64}', profile) is None
            or not isinstance(execution, str) or re.fullmatch('[a-f0-9]{64}', execution) is None
            or type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 < timeout <= 86400
            or not callable(verifier) or client is None
            or getattr(client, 'profile_sha256s', {}).get('business-llm') != profile):
        raise HTTPException(503, 'Trusted business operation configuration is unavailable')
    if request is not None:
        supplied = request.headers.get('authorization', '')
        if not hmac.compare_digest(supplied.encode('utf-8'), ('Bearer ' + token).encode('utf-8')):
            raise HTTPException(401, 'Business authorization is required')
        allowed = config.get('allowedPaths')
        if allowed is not None and (not isinstance(allowed, frozenset)
                or not allowed or not allowed <= {'/v1/models', '/v1/chat/completions',
                                                    '/v1/responses', '/v1/responses/input_tokens'}):
            raise HTTPException(503, 'Trusted business route allowlist is unavailable')
        if allowed is not None and request.url.path not in allowed:
            raise HTTPException(404, 'Business profile route is unavailable')
        saved = request.scope.get('consense.business.preflight')
        if saved is not None and not _business_snapshot_matches(saved, config, client):
            raise HTTPException(503, 'Trusted business operation configuration changed')
    return config, client


def _business_configuration_snapshot(config, client):
    return {**{name: config[name] for name in ('model', 'profileSha256', 'executionSha256', 'timeout')},
            'allowedPaths': config.get('allowedPaths'),
            'verifier': config['verifyRelease'], 'client': weakref.ref(client)}


def _business_snapshot_matches(saved, config, client):
    return (isinstance(saved, dict)
            and all(saved.get(name) == config[name] for name in ('model', 'profileSha256', 'executionSha256', 'timeout'))
            and saved.get('allowedPaths') == config.get('allowedPaths')
            and saved.get('verifier') is config['verifyRelease']
            and isinstance(saved.get('client'), weakref.ReferenceType) and saved['client']() is client)


@app.get('/v1/models')
def native_business_models(request: Request):
    if _remote_models is not None or 'consense.business.preflight' in request.scope:
        config, _ = _fixed_business_configuration(request)
        return {'object': 'list', 'data': [{'id': config['model'], 'object': 'model',
                                            'owned_by': 'configured-offline-profile'}],
                'modelReady': False, 'targetAccepted': False}
    coordinator = gpu_coordinator()
    if coordinator is None:
        raise HTTPException(404, 'Native business Job coordinator is not configured')
    try:
        return coordinator.model_catalog()
    except (ValueError, OSError) as failure:
        raise HTTPException(503, 'Configured native business model catalog is unavailable') from failure


def _pinned_cpu_ocr():
    """Reuse the existing fixed local CPU engine; caller owns LOCK."""
    global _ocr, _ocr_recipe_sha256
    if _ocr is None:
        from ocr_runtime import ocr_cache_recipe, local_model_paths, identity_hash
        directory = os.environ.get('CONSENSE_OCR_MODEL_DIRECTORY', '')
        expected_recipe = os.environ.get('CONSENSE_OCR_RECIPE_SHA256', '')
        if not directory or not Path(directory).is_absolute() or not re.fullmatch('[a-f0-9]{64}', expected_recipe):
            raise ValueError('Offline identity OCR requires an explicit local model directory and exact recipe SHA-256')
        recipe = ocr_cache_recipe(directory, require_verified_defaults=True)
        if identity_hash(recipe) != expected_recipe:
            raise ValueError('Offline identity OCR recipe differs before model initialization')
        engine = create_ocr_engine(directory, local_paths=local_model_paths(directory))
        if ocr_cache_recipe(directory, require_verified_defaults=True) != recipe:
            raise ValueError('Offline identity OCR weights/configuration changed during initialization')
        _ocr, _ocr_recipe_sha256 = engine, expected_recipe
    elif _ocr_recipe_sha256 is not None and os.environ.get('CONSENSE_OCR_RECIPE_SHA256') != _ocr_recipe_sha256:
        raise ValueError('Offline identity OCR configured recipe changed; restart with its reviewed local assets')
    return _ocr


def _observe_complete_cpu_lines(raw, store):
    from ocr_layout_coverage import observe_cpu_lines
    return observe_cpu_lines(_pinned_cpu_ocr(), raw, _ocr_recipe_sha256, store)


@app.post('/ocr')
def ocr(file: UploadFile = File(...), purpose: Annotated[str | None, Header(alias='X-Consense-Ocr-Purpose')] = None,
        source_context: Annotated[str | None, Form(alias='sourceContext')] = None,
        task_id: Annotated[str | None, Header(alias='X-Consense-Ocr-Task-Id')] = None,
        deadline_ms: Annotated[int | None, Header(alias='X-Consense-Ocr-Deadline-Ms')] = None):
    from artifact_store import ArtifactStore
    from ocr_request_lifecycle import OcrRequest, OcrRequestError
    admitted_at = time.time()
    if purpose not in (None, 'identity', 'parse'):
        raise HTTPException(422, 'Unsupported OCR observation purpose')
    config, config_sha = None, None
    complete_config = os.environ.get('CONSENSE_COMPLETE_OCR_CONFIG')
    if complete_config and purpose != 'identity':
        config_bytes = Path(complete_config).read_bytes()
        config_sha = hashlib.sha256(config_bytes).hexdigest()
        if config_sha != os.environ.get('CONSENSE_COMPLETE_OCR_CONFIG_SHA256'):
            raise HTTPException(503, 'Complete OCR configuration changed or lacks its exact SHA-256 pin')
        config = json.loads(config_bytes)
    maximum = config.get('timeoutSeconds', 600) if config is not None else 600
    if isinstance(maximum, bool) or not isinstance(maximum, (int, float)) or not 0 < maximum <= 86400:
        raise HTTPException(503, 'OCR server timeout must be positive finite and bounded')
    deadline_ms = min(deadline_ms, int((admitted_at + maximum) * 1000)) if deadline_ms is not None else int((admitted_at + maximum) * 1000)
    from PIL import Image, UnidentifiedImageError
    raw = file.file.read(25*1024*1024+1)
    if len(raw) > 25*1024*1024:
        raise HTTPException(413, 'Image exceeds 25 MiB')
    try:
        image = Image.open(io.BytesIO(raw))
        image_format = image.format
        if image.width*image.height > 40000000:
            raise HTTPException(413, 'Image exceeds 40 million pixels')
        image = image.convert('RGB')
    except (UnidentifiedImageError, OSError) as error:
        raise HTTPException(422, 'Upload a readable raster page image') from error
    if source_context is not None:
        from complete_ocr import validate_source_context
        try:
            input_name = 'page.' + {'PNG': 'png', 'JPEG': 'jpg', 'TIFF': 'tiff', 'WEBP': 'webp'}[image_format]
            source_context = validate_source_context(json.loads(source_context), raw, image.width, image.height, input_name)
        except (ValueError, TypeError, KeyError) as error:
            raise HTTPException(422, 'Invalid original source/raster binding: ' + str(error)) from error
    task = None
    try:
        store = ArtifactStore(STATE / 'ocr-observations')
        task = OcrRequest(store, task_id, {'inputSha256': hashlib.sha256(raw).hexdigest(),
            'purpose': purpose or 'parse', 'sourceContext': source_context, 'completeConfigSha256': config_sha},
            deadline_ms=deadline_ms, max_seconds=maximum)
        if not task.fresh:
            return task.existing_result()
        with task.acquire(LOCK):
            if config is not None:
                from complete_ocr import recognize_complete_page, CompleteOcrError
                payload = recognize_complete_page(raw, config, store,
                    source_context=source_context, deadline=task.deadline, cancelled=task.cancelled,
                    line_observer=_observe_complete_cpu_lines if source_context is not None else None)
            else:
                task.check()
                engine = _pinned_cpu_ocr()
                task.check()
                started = time.perf_counter()
                result = engine(image)
                from ocr_response import baseline_observation
                payload = baseline_observation(result, image, raw)
                payload['provenance']['observationPurpose'] = purpose or 'parse'
                payload['provenance']['ocrRecipeSha256'] = _ocr_recipe_sha256
                payload['provenance']['sourceContext'] = source_context
                payload['provenance']['sourceBindingStatus'] = 'caller_bound_original_source_unverified_original_bytes' if source_context else 'raster_input_only'
                payload.update(imageWidth=image.width, imageHeight=image.height,
                    seconds=time.perf_counter()-started, engine='RapidOCR CPU baseline',
                    limitations=['No table-cell or deletion-state interpretation'])
        payload.setdefault('provenance', {})['requestTaskId'] = task.task_id
        return task.success(payload)
    except OcrRequestError as error:
        if task is not None and task.fresh:
            task.fail(error)
        raise HTTPException(error.status_code, {'kind': 'ocr_request_not_completed', 'message': str(error),
            'task': task.record if task is not None else error.task}) from error
    except HTTPException:
        raise
    except Exception as error:
        if task is not None and task.fresh:
            task.fail(error)
        raise HTTPException(503, {'kind': 'ocr_failed', 'message': f'{type(error).__name__}: {error}',
            'task': task.record if task is not None else None, 'receipt': getattr(error, 'receipt', None)}) from error


@app.get('/ocr/tasks/{task_id}')
def ocr_task_status(task_id: str):
    from artifact_store import ArtifactStore
    from ocr_request_lifecycle import read_request, OcrRequestError
    try:
        return read_request(ArtifactStore(STATE / 'ocr-observations'), task_id)
    except OcrRequestError as error:
        raise HTTPException(error.status_code, str(error)) from error


@app.get('/ocr/tasks/{task_id}/result')
def ocr_task_result(task_id: str):
    from artifact_store import ArtifactStore
    from ocr_request_lifecycle import request_result, OcrRequestError
    try:
        return request_result(ArtifactStore(STATE / 'ocr-observations'), task_id)
    except OcrRequestError as error:
        raise HTTPException(error.status_code, {'message': str(error), 'task': error.task}) from error


@app.delete('/ocr/tasks/{task_id}')
def ocr_task_cancel(task_id: str):
    from artifact_store import ArtifactStore
    from ocr_request_lifecycle import cancel_request, OcrRequestError
    try:
        return cancel_request(ArtifactStore(STATE / 'ocr-observations'), task_id)
    except OcrRequestError as error:
        raise HTTPException(error.status_code, str(error)) from error


@app.post('/native-pdf/table-candidates')
def native_pdf_table_candidates(file: Annotated[UploadFile, File()],
        native_context: Annotated[UploadFile | str, File(alias='nativeContext')]):
    from artifact_store import ArtifactStore
    from native_pdf_candidates import capture_native_pdf_candidates, MAX_PDF_BYTES, MAX_CONTEXT_BYTES
    raw = file.file.read(MAX_PDF_BYTES + 1)
    if len(raw) > MAX_PDF_BYTES:
        raise HTTPException(413, 'Original PDF exceeds 64 MiB')
    context = native_context.encode('utf-8') if isinstance(native_context, str) else native_context.file.read(MAX_CONTEXT_BYTES + 1)
    if len(context) > MAX_CONTEXT_BYTES:
        raise HTTPException(413, 'Native context exceeds the configured complete byte limit')
    with LOCK:
        return capture_native_pdf_candidates(raw, context,
            artifact_store=ArtifactStore(STATE / 'native-pdf-candidates'))


@app.post('/index')
def index(request: IndexRequest):
    from migration_project_registry import require_writable, REGISTRY
    from migrated_qdrant_index import configured
    if REGISTRY not in os.environ and REGISTRY + '_SHA256' not in os.environ and (configured(os.environ) or _migrated_index is not None):
        raise HTTPException(409, 'Pinned migrated Server index is read-only')
    try:
        require_writable(sys.modules[__name__], project_key(request.projectId), window_runtime())
    except WindowError as error:
        raise HTTPException(409, {'kind':error.kind, 'message':str(error), 'metadata':error.metadata}) from error
    coordinator = gpu_coordinator()
    if coordinator is not None:
        if request.initializationDecision:
            raise HTTPException(409, 'Initialization requires the local index runtime')
        status, value = coordinator.execute('document-embedding', request.model_dump(mode='json', exclude={'initializationDecision'}), server=sys.modules[__name__])
        if status != 200:
            raise HTTPException(status, value)
        return value
    try:
        return window_runtime().index(request)
    except WindowError as error:
        raise HTTPException(503 if error.kind in ('SOURCE_REQUEST_CANCELLED','INDEX_PUBLICATION_DURABILITY_UNCERTAIN','INDEX_PUBLICATION_DURABILITY_UNKNOWN','QDRANT_BACKEND_UNAVAILABLE','QDRANT_BACKEND_RELEASE_UNKNOWN') else 422,
                            {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error


@app.post('/retrieve')
def retrieve(request: RetrieveRequest):
    coordinator = gpu_coordinator()
    if coordinator is not None:
        status, value = coordinator.execute('query-embedding-rerank', request.model_dump(mode='json'), server=sys.modules[__name__])
        if status != 200:
            raise HTTPException(status, value)
        return value
    try:
        result = window_runtime().retrieve(request)
    except WindowError as error:
        if error.kind not in ('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'QDRANT_BACKEND_UNAVAILABLE', 'QDRANT_BACKEND_RELEASE_UNKNOWN'):
            raise
        raise HTTPException(503, {'kind':error.kind, 'message':str(error), 'metadata':error.metadata}) from error
    runtime = window_runtime()
    registry = getattr(runtime, 'source_registry', None)
    frame_var = getattr(registry, '_validation_frame', None)
    frame = frame_var.get() if frame_var is not None else None
    record = frame.get('indexMetadata', {}).get(project_key(request.projectId)) if isinstance(frame, dict) else None
    observed = record.get('migrationObservation') if isinstance(record, dict) else None
    if observed is not None:
        result['indexAccess'] = {'mode':'pinned-migrated-server-read-only', 'readOnly':True,
                                'migrationReceiptSha256':observed['receipt']['sha256'],
                                'runtimeCompatibilityVerified':False,
                                **{name:observed[name] for name in ('queryRuntimeSignature','queryIdentity','queryRecipe','target','fullVectorIntegrity')}}
    return result


class _FixedBusinessResponse(Response):
    """One ASGI receive owner controls the lifetime of the bounded client call."""
    def __init__(self, client, config, path, raw, streaming, deadline):
        super().__init__()
        self.client, self.config, self.path, self.raw = client, config, path, raw
        self.streaming, self.deadline = streaming, deadline
        self.exit_receipt = {'status': 'release_unknown', 'operationId': None}

    async def __call__(self, scope, receive, send):
        cancelled = threading.Event()
        async_cancelled = asyncio.Event()
        session = None
        operation = None
        started = False
        send_uncertain = False
        def stopped():
            return cancelled.is_set() or time.monotonic() >= self.deadline
        def remaining():
            value = self.deadline - time.monotonic()
            if value <= 0 or cancelled.is_set():
                raise TimeoutError('Business request cancelled or expired')
            return value
        async def watch():
            while True:
                event = await receive()
                if event['type'] == 'http.disconnect':
                    cancelled.set()
                    async_cancelled.set()
                    return
        async def wait_operation(task, final_response=False):
            interrupted = asyncio.create_task(async_cancelled.wait())
            try:
                done, _ = await asyncio.wait((task, interrupted), timeout=remaining(),
                                             return_when=asyncio.FIRST_COMPLETED)
                # Uvicorn may report disconnect after accepting the final response body.
                finished_response = final_response and task in done and not task.cancelled() and task.exception() is None
                if task not in done or (stopped() and not finished_response):
                    cancelled.set()
                    raise TimeoutError('Business operation cancelled or expired')
                return task.result()
            finally:
                interrupted.cancel()
        watcher = asyncio.create_task(watch())
        claims = {'path': self.path, 'bodySha256': hashlib.sha256(self.raw).hexdigest(),
                  'profileSha256': self.config['profileSha256'], 'executionSha256': self.config['executionSha256'],
                  'model': self.config['model']}
        async def verified(receipt, expected_operation=None):
            operation_id = receipt.get('operationId') if isinstance(receipt, dict) else None
            if (stopped() or not isinstance(operation_id, str) or re.fullmatch('[a-f0-9]{32}', operation_id) is None
                    or (expected_operation is not None and expected_operation != operation_id)):
                return False
            proof = asyncio.create_task(asyncio.to_thread(self.config['verifyRelease'], receipt,
                                                         {**claims, 'operationId': operation_id}))
            proof.add_done_callback(lambda done: None if done.cancelled() else done.exception())
            interrupted = asyncio.create_task(async_cancelled.wait())
            try:
                done, _ = await asyncio.wait((proof, interrupted), timeout=remaining(),
                                             return_when=asyncio.FIRST_COMPLETED)
                return proof in done and not stopped() and proof.result() is True and not stopped()
            finally:
                interrupted.cancel()
        def safe_close(current):
            try:
                current.close()
            except BaseException:
                pass
        def open_stream():
            current = self.client.start_business(self.path, self.raw, remaining(), stopped)
            try:
                if stopped():
                    raise TimeoutError('Business request cancelled after admission')
                status, headers = current.open()
                if stopped():
                    raise TimeoutError('Business request cancelled while opening')
                return current, status, headers
            except BaseException:
                safe_close(current)
                raise
        def next_chunk(iterator):
            try:
                return next(iterator)
            except StopIteration:
                return None
        async def do_send(event):
            nonlocal started, send_uncertain
            if stopped():
                if async_cancelled.is_set():
                    raise asyncio.CancelledError()
                raise TimeoutError('Business response deadline elapsed')
            if event['type'] == 'http.response.start':
                started = True
            sending = asyncio.create_task(send(event))
            sending.add_done_callback(lambda done: None if done.cancelled() else done.exception())
            try:
                await wait_operation(sending, event['type'] == 'http.response.body' and not event.get('more_body', False))
            except TimeoutError:
                send_uncertain = True
                sending.cancel()
                if async_cancelled.is_set():
                    raise asyncio.CancelledError() from None
                raise TimeoutError('Business response send deadline elapsed') from None
            except asyncio.CancelledError:
                sending.cancel()
                raise
            except OSError:
                cancelled.set()
                async_cancelled.set()
                raise asyncio.CancelledError() from None
        try:
            if not self.streaming:
                operation = asyncio.create_task(asyncio.to_thread(self.client.call_business,
                    self.path, self.raw, remaining(), stopped))
                operation.add_done_callback(lambda done: None if done.cancelled() else done.exception())
                status, headers, body, receipt = await wait_operation(operation)
                if not await verified(receipt):
                    raise ValueError('Independent business release is unavailable')
                from request_stage import MAX_BODY
                content_type = headers.get('content-type', 'application/octet-stream') if isinstance(headers, dict) else None
                if (type(status) is not int or not 200 <= status <= 599 or not isinstance(body, bytes)
                        or len(body) > MAX_BODY or not isinstance(content_type, str)
                        or any(c in content_type for c in '\r\n\x00')):
                    raise ValueError('Invalid bounded business response')
                await do_send({'type': 'http.response.start', 'status': status,
                    'headers': [(b'content-type', content_type.encode('latin-1'))]})
                await do_send({'type': 'http.response.body', 'body': body, 'more_body': False})
                return
            operation = asyncio.create_task(asyncio.to_thread(open_stream))
            operation.add_done_callback(lambda done: None if done.cancelled() else done.exception())
            session, status, headers = await wait_operation(operation)
            operation = None
            content_type = headers.get('content-type', 'application/octet-stream')
            if (type(status) is not int or not 200 <= status <= 599 or not isinstance(content_type, str)
                    or any(c in content_type for c in '\r\n\x00')):
                raise ValueError('Invalid business stream headers')
            if 200 <= status < 300 and content_type.lower().split(';', 1)[0].strip() != 'text/event-stream':
                raise ValueError('Business streaming response requires SSE')
            iterator = session.read()
            withheld_error = []
            upstream_pending = b''
            terminal_verified = False
            size = 0
            if 200 <= status < 300:
                await do_send({'type': 'http.response.start', 'status': status,
                    'headers': [(b'content-type', content_type.encode('latin-1')),
                                (b'x-consense-gateway-events', b'1')]})
            while True:
                operation = asyncio.create_task(asyncio.to_thread(next_chunk, iterator))
                operation.add_done_callback(lambda done: None if done.cancelled() else done.exception())
                chunk = await wait_operation(operation)
                operation = None
                if chunk is None:
                    break
                from request_stage import MAX_BODY
                if not isinstance(chunk, bytes) or size + len(chunk) > MAX_BODY:
                    raise ValueError('Business response exceeds its finite bound')
                size += len(chunk)
                if session.finished:
                    if not await verified(session.exit_receipt, session.operation):
                        raise ValueError('Independent business release is unavailable')
                    terminal_verified = True
                if 200 <= status < 300:
                    upstream_pending += chunk
                    while True:
                        boundary = re.search(rb'\r\n\r\n|\n\n|\r\r', upstream_pending)
                        if not boundary:
                            break
                        frame, upstream_pending = upstream_pending[:boundary.end()], upstream_pending[boundary.end():]
                        if re.search(rb'(?:^|[\r\n])(?:\xef\xbb\xbf)?event:[ \t]*consense-gateway-timeout[ \t]*(?:[\r\n]|$)', frame):
                            raise ValueError('Reserved gateway event received from upstream')
                        await do_send({'type': 'http.response.body', 'body': frame, 'more_body': True})
                else:
                    withheld_error.append(chunk)
            if not session.finished or (200 <= status < 300 and not terminal_verified):
                raise ValueError('Business stream ended without a verified terminal')
            if 200 <= status < 300 and upstream_pending:
                raise ValueError('Business stream ended with an incomplete SSE frame')
            if not 200 <= status < 300:
                if not await verified(session.exit_receipt, session.operation):
                    raise ValueError('Independent business release is unavailable')
                await do_send({'type': 'http.response.start', 'status': status,
                    'headers': [(b'content-type', content_type.encode('latin-1'))]})
                await do_send({'type': 'http.response.body', 'body': b''.join(withheld_error), 'more_body': False})
            else:
                await do_send({'type': 'http.response.body', 'body': b'', 'more_body': False})
        except asyncio.CancelledError:
            cancelled.set()
            async_cancelled.set()
            raise
        except Exception as failure:
            from fixed_gpu_client import OperationError
            disconnected = async_cancelled.is_set()
            expired = (not disconnected and (isinstance(failure, TimeoutError)
                       or (isinstance(failure, OperationError) and isinstance(failure.__cause__, TimeoutError))))
            self.exit_receipt = {'status': 'release_unknown', 'operationId': getattr(failure, 'operation', None)}
            cancelled.set()
            async_cancelled.set()
            if not started and not disconnected:
                await Response(content=b'{"error":"Business operation unavailable or release unverified"}',
                               status_code=504 if expired else 503, media_type='application/json')(scope, receive, send)
            elif started and expired and self.streaming and not send_uncertain:
                try:
                    await asyncio.wait_for(send({'type': 'http.response.body',
                        'body': b'event: consense-gateway-timeout\ndata: TIMEOUT\n\n',
                        'more_body': False}), .25)
                except (OSError, asyncio.TimeoutError):
                    pass
            elif started:
                raise RuntimeError('Business response send deadline elapsed' if expired and send_uncertain
                                   else 'Business operation stream failed') from None
        finally:
            cancelled.set()
            async_cancelled.set()
            deferred_close = False
            if operation is not None:
                try:
                    result = await asyncio.wait_for(asyncio.shield(operation), .25)
                    if session is None and self.streaming and isinstance(result, tuple):
                        session = result[0]
                except (asyncio.TimeoutError, asyncio.CancelledError):
                    deferred_close = True
                    if self.streaming:
                        def close_late(done):
                            try:
                                value = done.result()
                                target = session if session is not None else value[0] if isinstance(value, tuple) else None
                            except BaseException:
                                target = session
                            if target is not None:
                                threading.Thread(target=safe_close, args=(target,), daemon=True).start()
                        operation.add_done_callback(close_late)
                except BaseException:
                    pass
            try:
                if session is not None and not deferred_close:
                    close = asyncio.create_task(asyncio.to_thread(safe_close, session))
                    try:
                        await asyncio.wait_for(asyncio.shield(close), .25)
                    except asyncio.TimeoutError:
                        pass
            finally:
                watcher.cancel()
                try:
                    await watcher
                except asyncio.CancelledError:
                    pass


@app.post('/v1/chat/completions')
@app.post('/v1/responses')
@app.post('/v1/responses/input_tokens')
async def native_business_operation(request: Request):
    if _remote_models is not None or 'consense.business.preflight' in request.scope:
        config, client = _fixed_business_configuration(request)
        from request_stage import MAX_BODY
        deadline = request.scope.get('consense.business.deadline', time.monotonic() + config['timeout'])
        if type(deadline) not in (int, float) or not math.isfinite(deadline) or deadline <= time.monotonic():
            raise HTTPException(504, 'Business request deadline elapsed')
        async def read_body():
            chunks = []; size = 0
            async for chunk in request.stream():
                size += len(chunk)
                if size > MAX_BODY:
                    raise HTTPException(413, 'Complete native business wire exceeds the finite byte limit')
                chunks.append(chunk)
            return b''.join(chunks)
        try:
            raw = await asyncio.wait_for(read_body(), max(0, deadline - time.monotonic()))
        except asyncio.TimeoutError as failure:
            raise HTTPException(504, 'Business request deadline elapsed') from failure
        def pairs(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise ValueError('Duplicate business request field')
                result[key] = value
            return result
        try:
            value = json.loads(raw, object_pairs_hook=pairs,
                parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Nonfinite number')))
            if (not isinstance(value, dict) or value.get('model') != config['model']
                    or ('stream' in value and type(value['stream']) is not bool)):
                raise ValueError('Business model or stream differs')
            pending = [value]
            while pending:
                item = pending.pop()
                if isinstance(item, str):
                    item.encode('utf-8')
                elif isinstance(item, float) and not math.isfinite(item):
                    raise ValueError('Nonfinite business request number')
                elif isinstance(item, dict):
                    pending.extend(item.keys())
                    pending.extend(item.values())
                elif isinstance(item, list):
                    pending.extend(item)
        except (ValueError, UnicodeError) as failure:
            raise HTTPException(422, 'Invalid business request') from failure
        return _FixedBusinessResponse(client, config, request.url.path, raw, value.get('stream') is True, deadline)
    coordinator = gpu_coordinator()
    if coordinator is None:
        raise HTTPException(404, 'Native business Job coordinator is not configured')
    from request_stage import MAX_BODY
    chunks = []; size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > MAX_BODY:
            raise HTTPException(413, 'Complete native business wire exceeds the finite byte limit')
        chunks.append(chunk)
    from starlette.concurrency import run_in_threadpool
    try:
        status, headers, body = await run_in_threadpool(coordinator.execute_wire, request.url.path, b''.join(chunks))
    except TimeoutError as failure:
        raise HTTPException(504,'Native GPU operation exceeded its finite deadline; no completed result was returned') from failure
    except (ValueError,OSError) as failure:
        raise HTTPException(503,'Native GPU operation was not accepted or completed; its private failure ledger is retained') from failure
    return Response(content=body,status_code=status,headers=headers)


def configure_remote_gpu():
    """Bind the existing model methods to a configured fixed GPU executor."""
    global _remote_models, _business_config, model_runtime, embedding_model, rerank_model, encode_embeddings
    names = ('CONSENSE_FIXED_GPU_URL', 'CONSENSE_FIXED_GPU_TOKEN_FILE', 'CONSENSE_FIXED_GPU_TIMEOUT_SECONDS',
             'CONSENSE_FIXED_GPU_PROFILE_MANIFEST', 'CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256')
    present = [bool(os.environ.get(name)) for name in names]
    if not any(present):
        if any(os.environ.get(name) for name in ('CONSENSE_BUSINESS_MODEL', 'CONSENSE_BUSINESS_TOKEN_FILE',
                'CONSENSE_BUSINESS_PROFILE_FILE', 'CONSENSE_BUSINESS_TIMEOUT_SECONDS',
                'CONSENSE_FIXED_GPU_CONFIG_SHA256')):
            raise ValueError('Business route requires the fixed GPU executor')
        return
    if not all(present) or _remote_models is not None or _windows_runtime is not None:
        raise ValueError('Complete remote GPU configuration is required before runtime initialization')
    if os.environ.get('CONSENSE_MODEL_DEVICE') != 'cuda':
        raise ValueError('Remote GPU execution requires an explicit CUDA recipe')
    if os.name != 'posix' or not os.environ[names[0]].startswith('unix:///'):
        raise ValueError('Remote GPU execution requires a private Unix socket')
    path = str(HERE.parent / 'offline_delivery')
    if path not in sys.path:
        sys.path.insert(0, path)
    import coordinator
    if any(os.environ.get(name) for name in coordinator.NAMES):
        raise ValueError('Remote GPU and Job coordinator configurations are mutually exclusive')
    from fixed_gpu_client import Client
    from remote_gpu_models import RemoteModels
    from fixed_gpu_profiles import load_profiles
    from business_configuration import configure as configure_business
    profiles = load_profiles(os.environ[names[3]], os.environ[names[4]])
    secret = Path(os.environ[names[1]])
    if not secret.is_absolute() or secret.is_symlink():
        raise ValueError('A bounded private GPU token file is required')
    fd = os.open(secret, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_size > 512
                or (os.name == 'posix' and (info.st_uid != os.getuid() or info.st_mode & 0o077))):
            raise ValueError('A bounded private GPU token file is required')
        with os.fdopen(fd, 'r', encoding='ascii') as stream:
            fd = None
            token = stream.read(513).strip()
    finally:
        if fd is not None:
            os.close(fd)
    timeout = float(os.environ[names[2]])
    remote = RemoteModels(sys.modules[__name__], Client(os.environ[names[0]], token, profiles),
                          dtype=MODEL_DTYPE, serial_offload=SERIAL_OFFLOAD, timeout=timeout)
    remote.require_request_scope()
    business = configure_business(os.environ, os.environ[names[0]], token, profiles)
    _remote_models = remote
    _business_config = business
    model_runtime, embedding_model, rerank_model, encode_embeddings = (
        remote.model_runtime, remote.embedding_model, remote.rerank_model, remote.encode_embeddings)


def install_remote_request_routes(target):
    """Apply the same owned deadline to standalone index and retrieval routes."""
    if _remote_models is None:
        return
    def wrap(handler, request_type):
        async def bounded(payload, http_request):
            raise HTTPException(503, {'kind':'SOURCE_FRAME_PROOF_REQUIRED',
                'message':'Standalone remote indexing and retrieval require a protected source frame'})
        bounded.__annotations__ = {'payload':request_type, 'http_request':Request}
        return bounded
    for path, handler, request_type in (('/index',index,IndexRequest),('/retrieve',retrieve,RetrieveRequest)):
        routes = [route for route in target.routes if getattr(route,'path',None) == path and 'POST' in getattr(route,'methods',set())]
        if len(routes) != 1:
            raise ValueError('Standalone remote route identity differs')
        target.router.routes.remove(routes[0])
        target.post(path)(wrap(handler,request_type))


configure_remote_gpu()
install_remote_request_routes(app)


if __name__ == '__main__':
    import uvicorn
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8868)
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port, workers=1)
