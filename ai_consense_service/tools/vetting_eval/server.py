"""Local OCR and dense/BM25/rerank API. Run one worker: python server.py."""
from __future__ import annotations

import argparse
import hashlib
import io
import inspect
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid
import sys
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
from token_windows import WindowError
MODEL_DTYPE = os.environ.get('CONSENSE_MODEL_DTYPE', 'float32')
if MODEL_DTYPE not in {'float32', 'float16'}:
    raise ValueError('CONSENSE_MODEL_DTYPE must be explicit float32 or float16')
SERIAL_OFFLOAD = os.environ.get('CONSENSE_MODEL_SERIAL_OFFLOAD', '1') == '1'
_windows_runtime = None
_window_observer = None

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
_projects = {}
_migrated_index = None
_selected_device = None
app = FastAPI(title='ConSense local vetting models', version='1.0.0')


def gpu_coordinator():
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
    encoder = json.JSONEncoder(ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    for part in encoder.iterencode(value):
        result.update(part.encode('utf-8'))
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
    global _database, _database_configuration
    from migrated_qdrant_index import configured
    from token_windows import fail
    current = (str(STATE), os.environ.get('CONSENSE_QDRANT_URL'), os.environ.get('CONSENSE_QDRANT_API_KEY'))
    if _database is None:
        from qdrant_backend import connect
        _database = connect(STATE, os.environ)
        _database_configuration = current
    else:
        fail(_database_configuration == current or (_database_configuration is None and not configured(os.environ)),
             'QDRANT_BACKEND_CONFIG_CHANGED', 'Cached Qdrant backend configuration differs; start a new runtime')
    return _database


def metadata_path(key):
    return STATE / 'projects' / (digest(key) + '.json')


def signature(chunks):
    return window_runtime().signature(chunks)


def load_project_metadata(key):
    """Reuse resident parents; changed records decode once and owner exit hashes once."""
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
        return record['stored']
    if not path.exists():
        raise HTTPException(404, 'Project is not indexed')
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
    stored['_metadataFileIdentity'] = expected
    stored['_metadataFileStamp'] = before_stamp
    _projects[key] = stored
    if frame is not None:
        frame['indexMetadata'][key] = {'stored': stored, 'stamp': before_stamp,
            'expected': expected, 'identity': identity, 'metrics': metrics}
    return stored


def read_project(key):
    global _migrated_index
    from migrated_qdrant_index import MigratedIndex, configured
    if configured(os.environ) or _migrated_index is not None:
        try:
            if _migrated_index is None:
                _migrated_index = MigratedIndex(os.environ)
            return _migrated_index.read(key, window_runtime(), database)
        except WindowError as error:
            raise HTTPException(409, {'kind':error.kind, 'message':str(error), 'metadata':error.metadata}) from error
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
    selected_device = device()
    cuda_allocated = None
    if selected_device == 'cuda':
        import torch
        cuda_allocated = round(torch.cuda.memory_allocated()/2**20, 1)
    return {'status': 'ok', 'mode': 'server' if os.environ.get('CONSENSE_QDRANT_URL') else 'local', 'device': selected_device, 'offline': OFFLINE,
        'models': MODELS, 'maxTokens': MODEL_SETTINGS['embedding']['maxTokens'],
        'batchSize': MODEL_SETTINGS['embedding']['batchSize'],
        'runtime': {kind: model_runtime(kind) for kind in ('embedding', 'reranker')},
        'retrieval': retrieval_settings(observe_tokenizer_identity=False),
        'indexSignatureVersion': window_runtime().signature_version(),
        'loaded': {'embedding': _embedding is not None, 'reranker': _reranker is not None, 'ocr': _ocr is not None},
        'cudaAllocatedMiB': cuda_allocated,
        'ocrEngine': 'Complete Paddle/MinerU API candidates' if os.environ.get('CONSENSE_COMPLETE_OCR_CONFIG') else 'RapidOCR CPU baseline',
        'limitations': ['OCR does not interpret table cells or deletion state',
            'Source-bound windows count query/special tokens; oversized queries fail without silent clipping',
            'Legacy maxTokens/batchSize health fields describe embedding only',
            'Run one server worker with one Qdrant storage owner']}


@app.get('/v1/models')
def native_business_models():
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
    from migrated_qdrant_index import configured
    if configured(os.environ) or _migrated_index is not None:
        raise HTTPException(409, 'Pinned migrated Server index is read-only')
    coordinator = gpu_coordinator()
    if coordinator is not None:
        status, value = coordinator.execute('document-embedding', request.model_dump(mode='json'), server=sys.modules[__name__])
        if status != 200:
            raise HTTPException(status, value)
        return value
    try:
        return window_runtime().index(request)
    except WindowError as error:
        raise HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error


@app.post('/retrieve')
def retrieve(request: RetrieveRequest):
    coordinator = gpu_coordinator()
    if coordinator is not None:
        status, value = coordinator.execute('query-embedding-rerank', request.model_dump(mode='json'), server=sys.modules[__name__])
        if status != 200:
            raise HTTPException(status, value)
        return value
    result = window_runtime().retrieve(request)
    if _migrated_index is not None:
        observed = _migrated_index.last_observation
        result['indexAccess'] = {'mode':'pinned-migrated-server-read-only', 'readOnly':True,
                                'migrationReceiptSha256':observed['receipt']['sha256'],
                                'runtimeCompatibilityVerified':False,
                                **{name:observed[name] for name in ('queryRuntimeSignature','queryIdentity','queryRecipe','target','fullVectorIntegrity')}}
    return result


@app.post('/v1/chat/completions')
@app.post('/v1/responses')
@app.post('/v1/responses/input_tokens')
async def native_business_operation(request: Request):
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


if __name__ == '__main__':
    import uvicorn
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8868)
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port, workers=1)
