"""CUDA-only OER retrieval entry point; canonical scope is checked, never filtered.

Run with scripts/oer-local.ps1 or configure the source-bound SL manifest before
starting this module. Importing it does not import server or load neural models.
Native/canonical chunks retain their quality declarations. Full scan-parser
candidate ledgers require an upstream, source-bound canonical projection; route
selection alone does not turn parser observations into verified contract facts.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib
import json
import os
import time
from types import SimpleNamespace
from collections import Counter
from contextlib import contextmanager
from pathlib import Path

from competition_scope import CompetitionScopeError, load_policy
from structural_retrieval import StructuralRecipe
from token_windows import WindowError, fail
from window_runtime import WindowRuntime
from workspace_root import resolve_workspace
from model_session_observer import ModelSessionObserver
from pydantic import BaseModel, ConfigDict, Field, model_validator
from typing import Annotated


HERE = Path(__file__).resolve().parent
INPUT_CONTRACT = 'canonical-source-chunks-upstream-scope-and-parser-projection-v1'
PARSER_SCHEMA = 'consense.structured-parser-evidence.v1'
FRESH_RECIPE = 'scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1'
_WRAPPER_HASHES = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                   for name in ('oer_server.py', 'competition_scope.py', 'artifact_store.py',
                                'structured_parser_evidence.py', 'canonical_projection.py',
                                'oer_source_registry.py', 'oer_request_evidence.py',
                                'ordinary_predecessor_compat.py')}


class _PublicationModelObserver(ModelSessionObserver):
    """Keep this request's independently verified model completions."""
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._publication_begun = set()
        self._publication_operations = {}
        self._publication_verified = set()

    def begin_session(self, stage, binding):
        super().begin_session(stage, binding)
        self._publication_begun.add(binding['modelKind'])

    def bind_operation(self, stage, kind, operation):
        super().bind_operation(stage, kind, operation)
        fail(kind in self._publication_begun and kind not in self._publication_operations,
             'MODEL_SESSION_RELEASE_REQUIRED', 'Model publication operation differs')
        self._publication_operations[kind] = operation

    def verify_session(self, receipt):
        verified = super().verify_session(receipt)
        if verified is True:
            operation = receipt['operationId']
            fail(operation in self._publication_operations.values()
                 and operation not in self._publication_verified,
                 'MODEL_SESSION_RELEASE_REQUIRED', 'Model publication completion differs')
            self._publication_verified.add(operation)
        return verified

    def publication_verified(self):
        fail(self.stage == 'document-embedding'
             and self._publication_begun == set(self._publication_operations)
             and self._publication_verified == set(self._publication_operations.values()),
             'MODEL_SESSION_RELEASE_REQUIRED', 'Every started model operation requires independent completion')
        # No begun operation means the trusted scope did not admit a model session.
        return True


def _require_publication_model_proxy(backend, proxy):
    from remote_gpu_models import RemoteModels, _Embedding
    from window_runtime import WindowRuntime
    embedding_loader = getattr(backend, 'embedding_model', None)
    runtime = getattr(backend, '_windows_runtime', None)
    seen = set()
    while getattr(embedding_loader, '__self__', None) is not proxy:
        if id(embedding_loader) in seen or not isinstance(runtime, WindowRuntime):
            break
        seen.add(id(embedding_loader))
        loaders = getattr(runtime, '_model_publication_loaders', {})
        entry = loaders.get('embedding') if isinstance(loaders, dict) else None
        code = getattr(embedding_loader, '__code__', None)
        closure = getattr(embedding_loader, '__closure__', None)
        if not (isinstance(entry, tuple) and len(entry) == 4 and entry[0] is embedding_loader
                and getattr(runtime, 's', None) is backend and code is not None and code is entry[2]
                and getattr(embedding_loader, '__defaults__', None) is entry[3]
                and entry[3] == (entry[1], 'embedding') and code.co_freevars == ('self',)
                and closure is not None and len(closure) == 1 and closure[0].cell_contents is runtime):
            break
        embedding_loader = entry[1]
        next_closure = getattr(embedding_loader, '__closure__', None)
        runtime = next_closure[0].cell_contents if next_closure and len(next_closure) == 1 else None
    fail(type(proxy) is RemoteModels and proxy.backend is backend
         and type(proxy.embedding) is _Embedding and proxy.embedding.owner is proxy
         and getattr(proxy.embedding.get_sentence_embedding_dimension, '__self__', None) is proxy.embedding
         and getattr(proxy.embedding.get_sentence_embedding_dimension, '__func__', None) is _Embedding.get_sentence_embedding_dimension
         and getattr(proxy.session_scope, '__self__', None) is proxy
         and getattr(proxy.session_scope, '__func__', None) is RemoteModels.session_scope
         and getattr(embedding_loader, '__self__', None) is proxy
         and getattr(embedding_loader, '__func__', None) is RemoteModels.embedding_model
         and getattr(getattr(backend, 'encode_embeddings', None), '__self__', None) is proxy
         and getattr(getattr(backend, 'encode_embeddings', None), '__func__', None) is RemoteModels.encode_embeddings,
         'MODEL_SESSION_RELEASE_REQUIRED', 'A trusted request-owned model scope is required')


class ProjectionRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    bundle: dict
    adoptionManifest: dict


class CanonicalIndexRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    projectId: int | str
    projectionSha256: list[str] = Field(min_length=1, max_length=10000)
    module: str = 'vetting'
    rebuild: bool = Field(default=False, strict=True)
    initializationDecision: bool = Field(default=False, strict=True)



INDEX_STATUS_CONTRACT = 'read-only-existing-canonical-index-v1'
INDEX_AVAILABILITY_CONTRACT = 'live-canonical-index-availability-v1'
INDEX_AVAILABILITY_BOOTSTRAP_CONTRACT = 'source-verified-cold-canonical-availability-v1'
StatusSha = Annotated[str, Field(min_length=64, max_length=64, pattern=r'^[0-9a-f]{64}$')]

class CanonicalIndexStatusRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    projectId: int | Annotated[str, Field(min_length=1, max_length=256)]
    projectionSha256: list[StatusSha] = Field(min_length=1, max_length=10000)
    module: Annotated[str, Field(pattern=r'^(vetting|advisory)$')] = 'vetting'
    sourcePageEvidence: dict[StatusSha, StatusSha] = Field(default_factory=dict, max_length=10000)
    initializationDecision: bool = Field(default=False, strict=True)


class CanonicalIndexAvailabilityRequest(CanonicalIndexStatusRequest):
    expectedSignature: StatusSha | None = None
    expectedGeneration: Annotated[str, Field(min_length=32, max_length=32, pattern=r'^[0-9a-f]{32}$')] | None = None
    coldBootstrap: bool = Field(default=False, strict=True)

    @model_validator(mode='after')
    def validate_expectations(self):
        if self.coldBootstrap:
            if {'expectedSignature', 'expectedGeneration'} & self.model_fields_set:
                raise ValueError('Cold bootstrap requires omitted signature and generation expectations')
        elif self.expectedSignature is None:
            raise ValueError('Normal availability requires expectedSignature')
        return self


def configure_environment(environ, workspace):
    """Return explicit startup settings; reject inherited incompatible recipes."""
    values = dict(environ)
    required = {'CONSENSE_MODEL_DEVICE': 'cuda', 'CONSENSE_MODEL_DTYPE': 'float32',
                'CONSENSE_MODEL_SERIAL_OFFLOAD': '1',
                'CONSENSE_RETRIEVAL_GRANULARITY': 'dual-structure-v1',
                'CONSENSE_EMBED_MAX_TOKENS': '512', 'CONSENSE_RERANK_MAX_TOKENS': '512',
                'CONSENSE_WINDOW_OVERLAP_TOKENS': '64',
                'CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS': '2048',
                'CONSENSE_STRUCTURAL_RERANK_MAX_TOKENS': '2048',
                'CONSENSE_STRUCTURAL_RERANK_STRICT': 'true',
                'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'}
    for name, expected in required.items():
        if values.get(name) not in (None, '', expected):
            raise ValueError(f'{name} must be {expected} for the explicit OER entry point')
        values[name] = expected
    for name in ('CONSENSE_FIXED_VECTOR_CACHE', 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST',
                 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256',
                 'CONSENSE_EMBEDDING_COMPATIBILITY_RECEIPT',
                 'CONSENSE_EMBEDDING_COMPATIBILITY_RECEIPT_SHA256'):
        if values.get(name):
            raise ValueError(f'{name} is incompatible with the fresh OER index recipe; clear it explicitly')
    for name in ('CONSENSE_EMBED_BATCH_SIZE', 'CONSENSE_RERANK_BATCH_SIZE'):
        values.setdefault(name, '2')
        try:
            if int(values[name]) < 1:
                raise ValueError()
        except (TypeError, ValueError) as error:
            raise ValueError(name + ' must be a positive integer') from error
    values.setdefault('CONSENSE_OER_TARGET_MODULE', 'vetting')
    if values['CONSENSE_OER_TARGET_MODULE'] not in ('drafting', 'vetting', 'advisory'):
        raise ValueError('CONSENSE_OER_TARGET_MODULE must be drafting, vetting or advisory')
    workspace = Path(workspace).resolve()
    values.setdefault('CONSENSE_RETRIEVAL_DATA', str(workspace / '.local/oer/retrieval-state'))
    if not isinstance(values['CONSENSE_RETRIEVAL_DATA'], str) or not values['CONSENSE_RETRIEVAL_DATA'].strip():
        raise ValueError('CONSENSE_RETRIEVAL_DATA must name a separate OER state directory')
    state = Path(values['CONSENSE_RETRIEVAL_DATA']).expanduser().resolve()
    if state in (workspace, (workspace / 'tmp/vetting_server').resolve()):
        raise ValueError('The OER entry point requires a separate state path from the legacy server')
    values['CONSENSE_RETRIEVAL_DATA'] = str(state)
    values.setdefault('HF_HOME', str(workspace / '.local/oer/hf'))
    values['CONSENSE_WORKSPACE_ROOT'] = str(workspace)
    if not values.get('CONSENSE_SL_SCOPE_MANIFEST'):
        raise ValueError('CONSENSE_SL_SCOPE_MANIFEST is required; prepare the original-source scope upstream')
    return values


def assert_canonical_scope(chunks, policy, *, module='vetting'):
    """Reject a whole unfiltered request before signatures, model calls or writes."""
    for chunk in chunks:
        candidate = chunk.get('parserEvidence')
        if (chunk.get('schema') == PARSER_SCHEMA
                or isinstance(candidate, dict) and candidate.get('schema') == PARSER_SCHEMA):
            raise WindowError('PARSER_CANDIDATE_REQUIRES_UPSTREAM_PROJECTION',
                              'Full dual-parser evidence is a candidate ledger, not a canonical chunk',
                              chunkId=chunk.get('id'), inputContract=INPUT_CONTRACT,
                              qualityStatusChanged=False,
                              required='source-bound canonical projection preserving unknown and review status')
    try:
        result = policy.apply(chunks, module=module)
    except CompetitionScopeError as error:
        raise WindowError('COMPETITION_SCOPE_INVALID',
                          'Source scope cannot be established; prepare canonical input upstream',
                          code=error.code, chunkId=error.chunk_id,
                          details=error.details, policyIdentity=policy.policyIdentity) from error
    if result['excluded']:
        raise WindowError('COMPETITION_SCOPE_UNFILTERED_REQUEST',
                          'Excluded inputs must be removed upstream before the Java corpus/index receipt is formed',
                          excluded=result['excluded'], policyIdentity=policy.policyIdentity,
                          suppliedChunks=len(chunks), inputUnchanged=True,
                          partialIndexWritten=False)
    return result


def canonical_scope_policy_sha(snapshot):
    if snapshot.get('schema') == 'consense.normal-observed.v1':
        return snapshot['manifest']['sourceBinding']['scopePolicySha256']
    return snapshot['manifest']['scope']['scopePolicySha256']


class OerWindowRuntime(WindowRuntime):
    """Scope and wrapper bytes are part of both index and health identities."""

    def __init__(self, backend, policy, *, target_module='vetting'):
        super().__init__(backend)
        self.scope_policy = policy
        fail(target_module in ('drafting', 'vetting', 'advisory'), 'INVALID_TARGET_MODULE',
             'OER index target must be drafting, vetting or advisory')
        self.target_module = target_module
        self.structural_recipe = StructuralRecipe('dual-structure-v1', 2048, 2048, True)
        self.overlap = 64
        fail(not self.cache_root, 'OER_STARTUP_RECIPE', 'OER cannot reuse a legacy fixed-vector cache')
        remote = getattr(backend, '_remote_models', None)
        if remote is not None:
            from remote_gpu_models import RemoteModels
            runtime = getattr(backend, 'model_runtime', None)
            fail(type(remote) is RemoteModels and remote.backend is backend and getattr(runtime, '__self__', None) is remote
                 and getattr(runtime, '__func__', None) is RemoteModels.model_runtime
                 and remote.dtype == 'float32' and remote.serial_offload is True,
                 'OER_STARTUP_RECIPE', 'Remote CUDA configuration must bind the exact FP32 serial model proxy')
        else:
            fail(backend.device() == 'cuda', 'OER_CUDA_REQUIRED',
                 'OER requires CUDA; CPU execution and fallback are disabled')
        fail(backend.SERIAL_OFFLOAD is True, 'OER_STARTUP_RECIPE',
             'OER CUDA FP32 requires serial model offload')
        fail(backend.MODEL_DTYPE == 'float32' and all(
            backend.MODEL_SETTINGS[kind]['maxTokens'] == 512 for kind in ('embedding', 'reranker')),
            'OER_STARTUP_RECIPE', 'OER requires short 512-token windows and FP32')

    def algorithms(self):
        algorithms = super().algorithms()
        current = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                   for name in _WRAPPER_HASHES}
        fail(current == _WRAPPER_HASHES, 'SOFTWARE_CHANGED_SINCE_IMPORT',
             'Scope/wrapper bytes differ from the imported OER entry point')
        return {**algorithms, **current}

    def signature_matches(self, stored, chunks):
        from portable_index_signature import matches
        from ordinary_predecessor_compat import compatible
        current = self.signature(chunks)
        return matches(stored, current, HERE) or compatible(stored, current, HERE)

    def signature(self, chunks):
        assert_canonical_scope(chunks, self.scope_policy, module=self.target_module)
        return {**super().signature(chunks), 'competitionScope': self.scope_policy.policyIdentity,
                'oerInputContract': INPUT_CONTRACT, 'oerIndexRecipe': FRESH_RECIPE,
                'oerTargetModule': self.target_module}

    def retrieval_recipe(self, *, observe_tokenizer_identity=True):
        return {**super().retrieval_recipe(observe_tokenizer_identity=observe_tokenizer_identity),
                'competitionScope': self.scope_policy.policyIdentity,
                'oerInputContract': INPUT_CONTRACT, 'oerIndexRecipe': FRESH_RECIPE,
                'oerTargetModule': self.target_module}

    def validate_metadata(self, metadata):
        assert_canonical_scope(metadata['chunks'], self.scope_policy, module=self.target_module)
        return super().validate_metadata(metadata)


def create_app(policy, *, backend=None, artifact_store=None, source_registry=None, target_module='vetting'):
    """Create one isolated HTTP app; backend imports remain explicit and lazy."""
    from fastapi import FastAPI, HTTPException, Request
    from fastapi.responses import Response
    from artifact_store import ArtifactStore
    from canonical_projection import project_canonical
    from oer_source_registry import CanonicalSourceRegistry
    from oer_request_evidence import RequestEvidence
    backend = importlib.import_module('server') if backend is None else backend
    fail(all(getattr(backend, name, None) is None for name in ('_embedding', '_reranker', '_database')),
         'OER_STARTUP_RECIPE', 'Start OER in its own process before any legacy model or database is loaded')
    backend._windows_runtime = OerWindowRuntime(backend, policy, target_module=target_module)
    from fixed_reference_query import from_environment as fixed_reference_from_environment
    fixed_reference = fixed_reference_from_environment(backend)
    app = FastAPI(title='ConSense explicit OER retrieval', version='1.0.0')
    artifact_store = artifact_store or ArtifactStore(Path(backend.STATE) / 'artifacts')
    source_registry = source_registry or CanonicalSourceRegistry(Path(backend.STATE) / 'canonical', artifact_store)
    app.state.artifact_store = artifact_store
    app.state.source_registry = source_registry
    journal = (backend.request_evidence() if hasattr(backend, 'request_evidence')
               else RequestEvidence(Path(backend.STATE) / 'run-evidence'))
    previous_observer = getattr(backend, '_window_observer', None)
    def observe_run(stage, payload):
        if previous_observer is not None:
            previous_observer(stage, payload)
        journal.observe(stage, payload)
    backend._window_observer = observe_run
    app.state.request_evidence = journal

    def rejected(error):
        return HTTPException(503 if error.kind in ('SOURCE_REQUEST_CANCELLED','SOURCE_REQUEST_TIMEOUT','SOURCE_REQUEST_CAPACITY','INDEX_PUBLICATION_DURABILITY_UNCERTAIN','INDEX_PUBLICATION_DURABILITY_UNKNOWN','INDEX_PUBLICATION_EVIDENCE_UNAVAILABLE','MODEL_SESSION_RELEASE_REQUIRED','MIGRATED_INDEX_ISOLATION_UNVERIFIED','QDRANT_BACKEND_UNAVAILABLE','QDRANT_BACKEND_RELEASE_UNKNOWN') else 422,
                             {'kind': error.kind, 'message': str(error), 'metadata': error.metadata})

    def scope_receipt(chunks=None):
        if chunks is not None:
            assert_canonical_scope(chunks, policy, module=target_module)
        receipt = {'policyIdentity': {'manifestSha256': policy.policyIdentity['manifestSha256']},
                   'inputContract': INPUT_CONTRACT,
                   'targetModule': target_module, 'sourceQualityChanged': False}
        if chunks is not None:
            receipt.update(suppliedChunks=len(chunks), indexedChunks=len(chunks),
                           filtering='none; reject any excluded input')
        return receipt

    @contextmanager
    def source_frame(after_verified=None):
        factory = getattr(source_registry, 'validation_frame', None)
        if factory is None:
            from source_request_context import active as request_active
            fail(not request_active(), 'SOURCE_FRAME_PROOF_REQUIRED',
                 'A request-owned source validation frame is required')
            yield
            return
        frame = None
        body_failure = None
        try:
            options = {'software_guard':backend._windows_runtime.algorithms}
            if after_verified is not None:
                options['after_verified'] = after_verified
            with factory(**options):
                frame_var = getattr(source_registry, '_validation_frame', None)
                frame = frame_var.get() if frame_var is not None else None
                try:
                    yield
                except BaseException as error:
                    body_failure = error
                    raise
        except BaseException as error:
            # A body failure is not evidence that frame or lease exit failed.
            # If exit itself raises, its exception differs from the body error.
            if error is not body_failure and isinstance(frame, dict):
                for key in frame.get('recoveredIndexKeys', ()):
                    journal.block_after_frame_failure(key)
            if isinstance(error, WindowError):
                raise
            if isinstance(error, (ValueError, OSError)):
                raise WindowError('CANONICAL_REQUEST_FRAME_UNAVAILABLE',
                    'Current complete source/CAS/software/binding proof cannot be verified',
                    sourceQualityChanged=False) from error
            raise

    @contextmanager
    def model_scope(project, module, projections, pins, chunks, stage):
        from source_request_context import active, model_session_control
        if not active():
            yield None, None, None
            return
        proxy = getattr(backend, '_remote_models', None)
        fail(proxy is not None and callable(getattr(proxy, 'session_scope', None)),
             'MODEL_SESSION_RELEASE_REQUIRED', 'Protected remote model sessions are not ready')
        from source_session_scope import source_declaration_sha256, source_scope_sha256
        from model_session_worker import MAX_BATCHES, MAX_TOTAL_BYTES
        source_sha = source_scope_sha256(source_registry, project, module, projections, pins, chunks,
                                         policy.policyIdentity['manifestSha256'])
        binding = {'projectId':project, 'module':module, 'projectionSha256':list(projections),
                   'sourcePageEvidence':dict(pins), 'sourceScopeSha256':source_sha,
                   'sourceDeclarationSha256':source_declaration_sha256(project,module,projections,pins,chunks,
                       policy.policyIdentity['manifestSha256'])}
        control = model_session_control()
        if stage == 'document-embedding':
            _require_publication_model_proxy(backend, proxy)
            observer = _PublicationModelObserver(control, source_sha, stage, proxy, MAX_BATCHES, MAX_TOTAL_BYTES)
            completion = [False]
        else:
            observer = ModelSessionObserver(control, source_sha, stage, proxy, MAX_BATCHES, MAX_TOTAL_BYTES)
            completion = None
        execution_sha = observer.trusted_execution_sha256()
        try:
            with proxy.session_scope(control, source_sha, stage,
                                     max_batches=MAX_BATCHES, max_total_bytes=MAX_TOTAL_BYTES,
                                     observer=observer, execution_sha256=execution_sha) as session:
                yield session, binding, completion
        except WindowError:
            raise
        except ValueError as error:
            raise WindowError('MODEL_SESSION_INVALID', 'Remote model session could not be verified') from error
        fail(source_scope_sha256(source_registry, project, module, projections, pins, chunks,
                                 policy.policyIdentity['manifestSha256']) == source_sha,
             'SOURCE_SCOPE_CHANGED', 'Source or CAS scope changed while the model session was active')
        if completion is not None:
            completion[0] = observer.publication_verified()
            if observer._publication_operations:
                fail(len(session.receipts) == len(observer._publication_operations)
                     and set(observer.release_statuses) == set(observer._publication_operations.values()),
                     'MODEL_SESSION_RELEASE_REQUIRED', 'Durable model release evidence is incomplete')
                journal.observe('model-release-verified', {
                    'schema': 'consense.model-release-evidence.v1',
                    'sourceScopeSha256': source_sha,
                    'sourceDeclarationSha256': binding['sourceDeclarationSha256'],
                    'profileSha256': observer._stage_pins['profileSha256'],
                    'executionSha256': execution_sha,
                    'bindings': copy.deepcopy(observer.bindings),
                    'records': copy.deepcopy(observer.records),
                    'receipts': copy.deepcopy(session.receipts),
                    'statuses': copy.deepcopy(observer.release_statuses)})

    def health(fixedReference: str | None = None):
        if fixedReference is not None:
            fail(fixed_reference is not None,
                 'FIXED_REFERENCE_IDENTITY', 'Configured fixed reference manifest differs')
            return fixed_reference.health_for_manifest(fixedReference)
        result = backend.health()
        result['ocrEngine'] = 'External source-bound complete parsers; no OCR endpoint in this OER service'
        result['ocrRouteExposed'] = False
        result['loaded'].pop('ocr', None)
        result['limitations'] = [item for item in result['limitations']
                                if item != 'OCR does not interpret table cells or deletion state']
        result['oer'] = {'recipe': FRESH_RECIPE, 'inputContract': INPUT_CONTRACT,
                         'targetModule': target_module,
                         'scopePolicy': policy.policyIdentity, 'sourceQualityVerifiedByHealth': False,
                         'parserCandidatesAcceptedByRetrieval': False,
                         'canonicalProjectionEndpoint': '/project',
                         'indexStatusContract': INDEX_STATUS_CONTRACT,
                         'indexAvailabilityContract': INDEX_AVAILABILITY_CONTRACT,
                          'indexAvailabilityBootstrapContract': INDEX_AVAILABILITY_BOOTSTRAP_CONTRACT,
                         'requestEvidence': 'private request/response and actual window/model events in service state',
                         'assetReturnContract': 'source-and-projection-bound-content-addressed-assets-v1',
                         'limitations': ['Health does not run either BGE model or certify source quality',
                             'SL requires original-file SHA and physical-page mapping from canonical upstream',
                             'Dual-parser candidates require upstream review/projection; unknown stays unknown']}
        if getattr(source_registry, 'validation_frame', None) is not None:
            reuse_policy = getattr(source_registry, 'derived_reconstruction_policy', None)
            result['oer']['requestSourceValidation'] = {
                'version': 'complete-actual-source-request-frame-v1',
                'routes': ['/index-canonical', '/index-canonical/status', '/retrieve'],
                'validationScope': 'one_normal_journalled_request',
                'completeReconstruction': ('cold_once_per_source_within_request_or_byte_bound_replay'
                    if reuse_policy is not None else 'once_per_source_within_request'),
                'publicWireRead': 'strict_bounded_each_reuse',
                'actualCasVerification': 'all_complete_used_files_at_entry_and_fresh_exit',
                'exitGuards': ['public_roots', 'revision_aliases', 'actual_cas', 'runtime_software'],
                'crossRequestVerdictReuse': False,
                'unrelatedOuterCasSession': 'reject_before_body',
                'lateFailure': 'failed_normal_journal_before_response',
                'healthPerformsSourceValidation': False}
            if reuse_policy is not None:
                result['oer']['requestSourceValidation']['crossRequestDerivedResultReuse'] = reuse_policy()
        return result

    def index(request):
        try:
            chunks = [chunk.model_dump(exclude_none=True) for chunk in request.chunks]
            receipt = scope_receipt(chunks)
            result = backend.index(request)
            from source_request_context import commit_unless_deferred
            commit_unless_deferred()
            return {**result, 'competitionScope': receipt}
        except WindowError as error:
            raise rejected(error) from error

    def current_metadata(key):
        from migrated_qdrant_index import migration_candidate_for_frame
        from migration_project_registry import runtime_route
        guard = getattr(backend, 'verify_project_publication', None)
        recover = getattr(backend, 'recover_project_publication_for_read', None)
        if recover is not None:
            recover(key)
        if guard is not None:
            guard(key, None)
        route = runtime_route(backend, key, backend._windows_runtime)
        if route['mode'] != 'ordinary':
            return migration_candidate_for_frame(backend, key, source_registry, route)
        loader = getattr(backend, 'load_project_metadata', None)
        if loader is not None and backend.metadata_path(key).exists():
            return loader(key)
        stored = backend._projects.get(key)
        if stored is None:
            path = backend.metadata_path(key)
            if path.exists():
                stored = json.loads(path.read_text(encoding='utf-8'))
                backend._projects[key] = stored
        return stored

    def unframed_metadata_exit(key, stored):
        active = getattr(source_registry, '_validation_frame', None)
        if active is not None:
            return
        expected = stored.get('_metadataFileIdentity')
        if expected is not None:
            path = backend.metadata_path(key)
            digest, size = hashlib.sha256(), 0
            with path.open('rb') as handle:
                for block in iter(lambda: handle.read(1024 * 1024), b''):
                    digest.update(block)
                    size += len(block)
            fail((str(path.resolve()), size, digest.hexdigest()) == expected,
                 'INDEX_STATUS_METADATA_CHANGED', 'Persisted index metadata changed during status checks')

    def selected_index_chunks(stored, request):
        chunks = stored['chunks']
        guard = getattr(source_registry, 'validate_index_scope', None)
        if guard is not None:
            guard(chunks, request.projectionSha256, module=request.module,
                  sorted_order=not backend._windows_runtime.structural_recipe.enabled)
        else:
            expected = []
            for address in request.projectionSha256:
                snapshot = source_registry.load(address)
                fail(canonical_scope_policy_sha(snapshot) == policy.policyIdentity['manifestSha256'],
                     'PROJECTION_SCOPE_POLICY_MISMATCH', 'Current sources must bind this exact active scope')
                expected.extend(source_registry.chunks(address, module=request.module))
            if not backend._windows_runtime.structural_recipe.enabled:
                expected.sort(key=lambda chunk: chunk['id'])
            fail(chunks == expected, 'INDEX_STATUS_CURRENT_SOURCE_MISMATCH',
                 'Complete persisted parents differ from the exact current selected sources')
        assert_canonical_scope(chunks, policy, module=request.module)
        return chunks

    def read_status_metadata(key, request, route, *, availability):
        try:
            recover = getattr(backend, 'recover_project_publication_for_read', None)
            if recover is not None:
                recover(key)
            return backend.read_project(key), None
        except HTTPException as error:
            if error.status_code == 404 and error.detail == 'Project is not indexed' and key in backend._projects:
                backend._missing_index_history.add(key)
            if (not request.initializationDecision or availability and not request.coldBootstrap
                    or error.status_code != 404 or error.detail != 'Project is not indexed'):
                raise
            from migrated_qdrant_index import ENV
            from migration_project_registry import REGISTRY
            from contextlib import ExitStack
            frame_var = getattr(source_registry, '_validation_frame', None)
            frame = frame_var.get() if frame_var is not None else None
            fail(route['mode'] == 'ordinary' and not route.get('registryConfigured', False)
                 and not any(os.environ.get(name) for name in (ENV, ENV + '_SHA256'))
                 and not any(name in os.environ for name in (REGISTRY, REGISTRY + '_SHA256'))
                 and getattr(backend, '_migrated_index', None) is None
                 and getattr(backend._windows_runtime, 'source_registry', None) is source_registry
                 and isinstance(frame, dict) and isinstance(frame.get('leaseStack'), ExitStack),
                 'INDEX_INITIALIZATION_UNAVAILABLE', 'Initialization requires the ordinary source-owned route')
            from window_runtime import require_unused_index_metadata, require_empty_project_storage
            require_unused_index_metadata(backend, key)
            chunks = []
            for address in request.projectionSha256:
                snapshot = source_registry.load(address)
                fail(canonical_scope_policy_sha(snapshot) == policy.policyIdentity['manifestSha256'],
                     'PROJECTION_SCOPE_POLICY_MISMATCH', 'Current sources must bind this exact active scope')
                chunks.extend(source_registry.chunks_from_loaded(snapshot, module=request.module))
            if not backend._windows_runtime.structural_recipe.enabled:
                chunks.sort(key=lambda chunk: chunk['id'])
            selected_index_chunks({'chunks': chunks}, request)
            validate_pins = getattr(backend._windows_runtime, '_validate_source_page_pins', None)
            if validate_pins is not None:
                validate_pins(chunks)
            require_empty_project_storage(backend, key)
            backend.verify_project_publication(key, None)
            require_unused_index_metadata(backend, key)
            return None, {'initializationContract': 'consense.canonical-index-initialization.v1',
                          'projectId': key, 'targetModule': request.module,
                          'canonicalProjections': list(request.projectionSha256),
                          'sourcePageEvidence': dict(sorted(request.sourcePageEvidence.items())),
                          'initializationAllowed': True, 'ready': False, 'readOnly': True,
                          'competitionScope': scope_receipt()}

    def retrieve(request):
        try:
            # Inspect persisted originals before read_project can calculate a
            # tokenizer-bound signature or any vector/query inference occurs.
            with backend.LOCK:
                from source_request_context import check
                check()
                with source_frame():
                    key = backend.project_key(request.projectId)
                    with backend.LOCK:
                        stored = current_metadata(key)
                        if stored is not None:
                            assert_canonical_scope(stored['chunks'], policy, module=target_module)
                        from source_request_context import active, publish
                        from migration_project_registry import runtime_route
                        migrated = runtime_route(backend, key, backend._windows_runtime)['mode'] != 'ordinary'
                        scope = stored.get('sourceScope') if isinstance(stored,dict) else None
                        frame = source_registry._validation_frame.get() if migrated else None
                        record = frame['indexMetadata'].get(key) if isinstance(frame, dict) else None
                        reader = record.get('migrationReader') if isinstance(record, dict) else None
                        legacy = migrated and scope is None and callable(getattr(reader, 'legacy_source_scope', None))
                        if legacy:
                            scope = reader.legacy_source_scope(key)
                        if active() or migrated:
                            fields = {'projectId','module','projectionSha256','sourcePageEvidence'}
                            if not legacy: fields |= {'sourceScopeSha256','sourceDeclarationSha256'}
                            fail(isinstance(scope,dict) and set(scope)==fields and scope['projectId']==key
                                 and scope['module']==target_module,
                                 'SOURCE_SCOPE_IDENTITY_INVALID', 'Persisted index must bind its complete canonical source scope')
                            if active():
                                proxy = getattr(backend, '_remote_models', None)
                                fail(proxy is not None and callable(getattr(proxy, 'session_scope', None)),
                                     'MODEL_SESSION_RELEASE_REQUIRED', 'Protected remote model sessions are not ready')
                            chunks = selected_index_chunks(stored, SimpleNamespace(projectionSha256=scope['projectionSha256'], module=target_module))
                            pins = scope['sourcePageEvidence']
                            from source_session_scope import source_declaration_sha256
                            declaration = source_declaration_sha256(key,target_module,scope['projectionSha256'],pins,chunks,
                                 policy.policyIdentity['manifestSha256'])
                            if not legacy:
                                fail(declaration == scope['sourceDeclarationSha256'],
                                     'SOURCE_SCOPE_IDENTITY_INVALID', 'Current canonical source declaration differs from the index')
                            runtime = backend._windows_runtime
                            pin_context = getattr(runtime,'source_page_index_pins',None)
                            token = pin_context.set(pins) if pin_context is not None else None
                            try:
                                validate_pins = getattr(runtime,'_validate_source_page_pins',None)
                                if validate_pins is not None: validate_pins(chunks)
                                if migrated:
                                    from source_session_scope import source_scope_sha256
                                    current_scope = source_scope_sha256(source_registry,key,target_module,scope['projectionSha256'],pins,chunks,
                                         policy.policyIdentity['manifestSha256'])
                                    if legacy:
                                        scope = {**scope, 'sourceDeclarationSha256':declaration, 'sourceScopeSha256':current_scope}
                                        record['derivedLegacySourceScope'] = scope
                                    else:
                                        fail(current_scope == scope['sourceScopeSha256'],
                                             'SOURCE_SCOPE_IDENTITY_INVALID', 'Current migrated source frame differs from its recorded scope')
                                    fail(isinstance(record,dict) and record.get('stored') is stored,
                                         'MIGRATED_INDEX_FRAME_CONFLICT', 'Migrated source scope lost its frame binding')
                                    record['migrationSourceScopeVerified'] = True
                                if active():
                                    with model_scope(key,target_module,scope['projectionSha256'],pins,chunks,
                                                     'query-embedding-rerank'):
                                        result = backend.retrieve(request)
                                else:
                                    result = backend.retrieve(request)
                            finally:
                                if pin_context is not None: pin_context.reset(token)
                        else:
                            result = backend.retrieve(request)
                        if migrated:
                            for field in ('structuralCoverage', 'indexAccess'):
                                if field in result:
                                    result[field] = copy.deepcopy(result[field])
                return publish(lambda:{**result, 'competitionScope': {
                    'policyIdentity': policy.policyIdentity, 'inputContract': INPUT_CONTRACT,
                    'targetModule': target_module, 'sourceQualityChanged': False}})
        except WindowError as error:
            raise rejected(error) from error

    def project(request):
        try:
            fail(request.adoptionManifest.get('scope', {}).get('scopePolicySha256')
                 == policy.policyIdentity['manifestSha256'], 'PROJECTION_SCOPE_POLICY_MISMATCH',
                 'Adoption must bind the actual policy of this OER process')
            projection = project_canonical(request.bundle, request.adoptionManifest,
                                           artifact_store=artifact_store)
            module = projection['projectionIdentity']['module']
            assert_canonical_scope(projection['chunks'], policy, module=module)
            receipt = source_registry.publish(projection)
            return {**receipt, 'projection': projection, 'sourceQualityChangedByRegistry': False}
        except WindowError as error:
            raise rejected(error) from error
        except ValueError as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', 'CANONICAL_PROJECTION_REJECTED'),
                                      'message': str(error)}) from error

    def canonical_index(request):
        try:
            fail(request.module == target_module, 'INDEX_TARGET_MODULE_MISMATCH',
                 'This process indexes only its configured module; use the matching OER process',
                 requestedModule=request.module, targetModule=target_module)
            fail(len(set(request.projectionSha256)) == len(request.projectionSha256),
                 'DUPLICATE_CANONICAL_REVISION', 'One immutable projection can appear only once in a corpus')
            from source_request_context import deferred_publication, commit_pending, active, index_model_session, check
            with deferred_publication():
                completion = None
                source_binding = [None]
                def commit_after_source_checks():
                    fail(isinstance(completion, list) and completion == [True],
                         'MODEL_SESSION_RELEASE_REQUIRED', 'Model publication evidence is unavailable')
                    check()
                    record = journal.active.get()
                    if record is not None and record.get('publication') is not None:
                        fail(isinstance(source_binding[0], dict), 'SOURCE_FRAME_PROOF_REQUIRED',
                             'Current source binding is unavailable at publication')
                        journal.observe('source-frame-verified', {
                            'schema': 'consense.source-frame-evidence.v1',
                            'phase': 'final_checks_passed_before_lease_close',
                            'sourceScopeSha256': source_binding[0]['sourceScopeSha256'],
                            'sourceDeclarationSha256': source_binding[0]['sourceDeclarationSha256']})
                    commit_pending(source_frame_preclose_verified=True, model_session_verified=True)
                with source_frame(after_verified=commit_after_source_checks if active() else None):
                    chunks = []
                    for address in request.projectionSha256:
                        snapshot = source_registry.load(address)
                        fail(canonical_scope_policy_sha(snapshot)
                             == policy.policyIdentity['manifestSha256'], 'PROJECTION_SCOPE_POLICY_MISMATCH',
                             'Re-project sources reviewed against a different scope policy before indexing',
                             projectionSha256=address)
                        chunks.extend(source_registry.chunks_from_loaded(snapshot, module=request.module))
                    body = backend.IndexRequest(projectId=request.projectId, chunks=chunks, rebuild=request.rebuild,
                                                initializationDecision=request.initializationDecision)
                    if request.initializationDecision:
                        fail(backend.gpu_coordinator() is None, 'INDEX_INITIALIZATION_UNAVAILABLE',
                             'Initialization requires the local index runtime')
                        if os.environ.get('CONSENSE_QDRANT_URL'):
                            from window_runtime import require_unused_index_metadata
                            from migration_project_registry import require_writable
                            from contextlib import ExitStack
                            key = backend.project_key(request.projectId)
                            registry = getattr(backend._windows_runtime, 'source_registry', None)
                            frame_var = getattr(registry, '_validation_frame', None)
                            frame = frame_var.get() if frame_var is not None else None
                            fail(active() and not request.rebuild and registry is source_registry
                                 and isinstance(frame, dict) and isinstance(frame.get('leaseStack'), ExitStack),
                                 'INDEX_INITIALIZATION_UNAVAILABLE', 'Server initialization requires an owned source frame')
                            with backend.LOCK:
                                route = require_writable(backend, key, backend._windows_runtime)
                                backend.verify_project_publication(key, None)
                                require_unused_index_metadata(backend, key)
                                from window_runtime import require_empty_project_storage
                                require_empty_project_storage(backend, key)
                                fail(require_writable(backend, key, backend._windows_runtime) == route,
                                     'MIGRATION_ROUTE_CHANGED', 'Project route changed during initialization')
                                require_unused_index_metadata(backend, key)
                        else:
                            with backend.LOCK:
                                backend._windows_runtime.preflight_initialization(backend.project_key(request.projectId), request.rebuild)
                    if active():
                        runtime = backend._windows_runtime
                        from contextlib import ExitStack
                        frame_var = getattr(source_registry, '_validation_frame', None)
                        frame = frame_var.get() if frame_var is not None else None
                        fail(getattr(runtime, 'source_registry', None) is source_registry
                             and isinstance(frame, dict) and isinstance(frame.get('leaseStack'), ExitStack),
                             'SOURCE_FRAME_PROOF_REQUIRED', 'A request-owned source frame is required')
                        pin_context = getattr(runtime, 'source_page_index_pins', None)
                        pins = dict(pin_context.get() or {}) if pin_context is not None else {}
                        validate_pins = getattr(runtime, '_validate_source_page_pins', None)
                        if validate_pins is not None:
                            validate_pins(chunks)
                        validate_native = getattr(source_registry, 'validate_native_chunks', None)
                        if validate_native is not None:
                            validate_native(chunks)
                        exact_chunks = [chunk.model_dump(exclude_none=True) for chunk in body.chunks]
                        if not runtime.structural_recipe.enabled:
                            exact_chunks.sort(key=lambda chunk:chunk['id'])
                        with model_scope(backend.project_key(request.projectId), request.module,
                                         list(request.projectionSha256), pins, exact_chunks,
                                         'document-embedding') as (session, binding, completion):
                            source_binding[0] = binding
                            with index_model_session(session, binding):
                                result = index(body)
                    else:
                        result = index(body)
                if not active():
                    commit_pending(source_frame_verified=True)
                return {**result, 'canonicalProjections': request.projectionSha256,
                        'targetModule': request.module}
        except WindowError as error:
            raise rejected(error) from error
        except ValueError as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', 'CANONICAL_INDEX_REJECTED'),
                                      'message': str(error)}) from error

    def migrated_index_readiness(request, *, availability):
        from migrated_qdrant_index import migration_candidate_for_frame
        from source_session_scope import source_declaration_sha256, source_scope_sha256
        from index_integrity import verify_availability, verify_reuse
        from portable_index_signature import migration_signature_version
        key = backend.project_key(request.projectId)
        started = time.perf_counter()
        runtime = backend._windows_runtime
        fail(request.module == target_module and len(set(request.projectionSha256)) == len(request.projectionSha256)
             and set(request.sourcePageEvidence).issubset(request.projectionSha256),
             'INDEX_AVAILABILITY_SCOPE', 'Readiness requires the exact selected module and unique source scope')
        pins_context = getattr(runtime, 'source_page_index_pins', None)
        fail(pins_context is not None or not request.sourcePageEvidence,
             'SOURCE_PAGE_INDEX_PINS_UNSUPPORTED', 'Current runtime cannot validate source-page pins')
        pins = dict(sorted(request.sourcePageEvidence.items()))
        token = pins_context.set(pins) if pins_context is not None else None
        try:
            with source_frame(), backend.LOCK:
                from migration_project_registry import runtime_route, frame_reader
                route = runtime_route(backend, key, runtime)
                stored = migration_candidate_for_frame(backend, key, source_registry, route,
                                                       availability_only=availability)
                reader = frame_reader(runtime, key)
                scope = stored.get('sourceScope')
                legacy = scope is None and callable(getattr(reader, 'legacy_source_scope', None))
                if legacy: scope = reader.legacy_source_scope(key)
                fields = {'projectId','module','projectionSha256','sourcePageEvidence'}
                if not legacy: fields |= {'sourceScopeSha256','sourceDeclarationSha256'}
                fail(isinstance(scope, dict) and set(scope) == fields
                     and scope['projectId'] == key and scope['module'] == request.module
                     and scope['projectionSha256'] == list(request.projectionSha256)
                     and scope['sourcePageEvidence'] == pins
                     and stored['signature'].get('sourcePageEvidence', {}) == pins,
                     'SOURCE_SCOPE_IDENTITY_INVALID', 'Migrated index must bind the exact requested source scope and pins')
                chunks = selected_index_chunks(stored, request)
                validate_pins = getattr(runtime, '_validate_source_page_pins', None)
                if validate_pins is not None: validate_pins(chunks)
                policy_sha = policy.policyIdentity['manifestSha256']
                declaration = source_declaration_sha256(key, request.module, scope['projectionSha256'], pins, chunks, policy_sha)
                current_scope = source_scope_sha256(source_registry, key, request.module, scope['projectionSha256'], pins,
                                                    chunks, policy_sha)
                if not legacy:
                    fail(declaration == scope['sourceDeclarationSha256'] and current_scope == scope['sourceScopeSha256'],
                         'SOURCE_SCOPE_IDENTITY_INVALID', 'Current migrated source declaration or frame differs from the index')
                frame = source_registry._validation_frame.get()
                record = frame['indexMetadata'].get(key) if isinstance(frame, dict) else None
                fail(isinstance(record, dict) and record.get('stored') is stored
                     and record.get('migrationReader') is reader,
                     'MIGRATED_INDEX_FRAME_CONFLICT', 'Migrated readiness lost its frame binding')
                if legacy:
                    record['derivedLegacySourceScope'] = {**scope, 'sourceDeclarationSha256':declaration,
                                                          'sourceScopeSha256':current_scope}
                record['migrationSourceScopeVerified'] = True
                signed = stored['signature']
                collection = stored['collection']
                fail(collection == reader.receipt['target']['collection'], 'MIGRATED_INDEX_IDENTITY',
                     'Readiness target collection differs from its migration receipt')
                fail(stored.get('contract') == runtime.contract()
                     and migration_signature_version(signed.get('signatureVersion'), runtime.signature_version()),
                     'OLD_INDEX_CONTRACT', 'Migrated index contract or signature version differs from the runtime')
                runtime.index_collection(key, signed, stored.get('generation'))
                if availability:
                    if not request.coldBootstrap:
                        fail(backend.digest(signed) == request.expectedSignature, 'INDEX_AVAILABILITY_SIGNATURE',
                             'Current index signature differs from the expected index')
                    if 'expectedGeneration' in request.model_fields_set:
                        fail(stored.get('generation') == request.expectedGeneration, 'INDEX_AVAILABILITY_GENERATION',
                             'Index generation changed; refresh the current scope')
                    live = verify_availability(backend.database() if chunks else None, stored,
                                               key=key, collection=collection, signature=signed)
                    fail(live == verify_availability(backend.database() if chunks else None, stored,
                         key=key, collection=collection, signature=signed), 'INDEX_AVAILABILITY_CHANGED',
                         'Live index changed during availability checks')
                    result = {'indexAvailabilityContract': INDEX_AVAILABILITY_CONTRACT,
                        'coldBootstrap':request.coldBootstrap, 'fullVectorIntegrityChecked':False,
                        'indexed':stored['parentCount'], 'vectorPoints':live['pointCount'],
                        'vectorDimension':live['dimension'], 'indexGeneration':stored.get('generation'),
                        'collection':collection, 'migrationMetadataMetrics':'unavailable'}
                else:
                    runtime.validate_metadata(stored)
                    actual = verify_reuse(backend.database() if chunks else None, stored, key=key,
                                          collection=collection, chunks=chunks, signature=signed)
                    fail(actual == reader.receipt['targetVectorIntegrity'], 'MIGRATED_INDEX_VECTOR_CHANGED',
                         'Actual complete Server vector seal differs from this migration receipt')
                    result = {'indexStatusContract':INDEX_STATUS_CONTRACT, 'indexed':len(chunks),
                              'vectorPoints':stored['vectorPoints'], 'fullVectorIntegrityChecked':True}
                result.update(projectId=key, ready=True, readOnly=True, signature=backend.digest(signed),
                    contract=runtime.contract(), canonicalProjections=list(request.projectionSha256),
                    targetModule=request.module, sourcePageEvidence=pins, competitionScope=scope_receipt(),
                    migrationRuntimeCompatibilityVerified=False)
            if availability: result['elapsedSeconds'] = time.perf_counter() - started
            return result
        finally:
            if pins_context is not None: pins_context.reset(token)

    def _canonical_index_status(request):
        from migration_project_registry import runtime_route
        try:
            route = runtime_route(backend, backend.project_key(request.projectId), backend._windows_runtime)
        except WindowError as error:
            raise rejected(error) from error
        if route['mode'] != 'ordinary':
            try:
                return migrated_index_readiness(request, availability=False)
            except WindowError as error:
                raise rejected(error) from error
            except (ValueError, OSError, KeyError, TypeError) as error:
                raise HTTPException(422, {'kind':'CANONICAL_INDEX_STATUS_UNAVAILABLE',
                    'message':'Existing exact source-bound migrated index could not be verified'}) from error
        key = backend.project_key(request.projectId)
        try:
            fail(request.module == target_module, 'INDEX_TARGET_MODULE_MISMATCH',
                 'Status must use its configured module process')
            fail(len(set(request.projectionSha256)) == len(request.projectionSha256),
                 'DUPLICATE_CANONICAL_REVISION', 'Status requires exact unique selected projections')
            fail(set(request.sourcePageEvidence).issubset(request.projectionSha256),
                 'SOURCE_PAGE_INDEX_PINS_OUTSIDE_SCOPE', 'Every explicit pin must name a selected projection')
            runtime = backend._windows_runtime
            pins_context = getattr(runtime, 'source_page_index_pins', None)
            fail(pins_context is not None or not request.sourcePageEvidence,
                 'SOURCE_PAGE_INDEX_PINS_UNSUPPORTED', 'This runtime cannot validate source-page pins')
            token = pins_context.set(dict(sorted(request.sourcePageEvidence.items()))) if pins_context is not None else None
            try:
                with source_frame(), backend.LOCK:
                    stored, initialization = read_status_metadata(key, request, route, availability=False)
                    if initialization is not None:
                        return initialization
                    chunks = selected_index_chunks(stored, request)
                    runtime.validate_metadata(stored)
                    signed = stored['signature']
                    from index_integrity import verify_reuse
                    collection = runtime.index_collection(key, signed, stored.get('generation'))
                    verify_reuse(backend.database() if chunks else None, stored, key=key,
                                 collection=collection, chunks=chunks, signature=signed)
                    unframed_metadata_exit(key, stored)
                    return {'projectId': key, 'ready': True, 'readOnly': True,
                        'indexStatusContract': INDEX_STATUS_CONTRACT, 'indexed': len(chunks),
                        'vectorPoints': stored['vectorPoints'], 'signature': backend.digest(signed),
                        'contract': runtime.contract(), 'canonicalProjections': list(request.projectionSha256),
                        'targetModule': request.module, 'sourcePageEvidence': dict(sorted(request.sourcePageEvidence.items())),
                        'competitionScope': scope_receipt()}
            finally:
                if pins_context is not None:
                    pins_context.reset(token)
        except WindowError as error:
            backend.forget_project(key)
            raise rejected(error) from error
        except HTTPException:
            backend.forget_project(key)
            raise
        except (ValueError, OSError, KeyError, TypeError) as error:
            backend.forget_project(key)
            raise HTTPException(422, {'kind': 'CANONICAL_INDEX_STATUS_UNAVAILABLE',
                'message': 'Existing exact source-bound index could not be verified'}) from error

    def _canonical_index_availability(request):
        from migration_project_registry import runtime_route
        try:
            route = runtime_route(backend, backend.project_key(request.projectId), backend._windows_runtime)
        except WindowError as error:
            raise rejected(error) from error
        if route['mode'] != 'ordinary':
            try:
                return migrated_index_readiness(request, availability=True)
            except WindowError as error:
                raise rejected(error) from error
            except (ValueError, OSError, KeyError, TypeError) as error:
                raise HTTPException(422, {'kind':'CANONICAL_INDEX_AVAILABILITY_UNAVAILABLE',
                    'message':'Current source-bound migrated index is unavailable'}) from error
        key = backend.project_key(request.projectId)
        started = time.perf_counter()
        try:
            fail(request.module == target_module and len(set(request.projectionSha256)) == len(request.projectionSha256)
                 and set(request.sourcePageEvidence).issubset(request.projectionSha256),
                 'INDEX_AVAILABILITY_SCOPE', 'Availability requires the exact selected module and unique source scope')
            runtime = backend._windows_runtime
            pins_context = getattr(runtime, 'source_page_index_pins', None)
            fail(pins_context is not None or not request.sourcePageEvidence,
                 'SOURCE_PAGE_INDEX_PINS_UNSUPPORTED', 'Current runtime cannot validate source-page pins')
            token = pins_context.set(dict(sorted(request.sourcePageEvidence.items()))) if pins_context is not None else None
            try:
                with source_frame(), backend.LOCK:
                    stored, initialization = read_status_metadata(key, request, route, availability=True)
                    if initialization is not None:
                        return initialization
                    signed = stored['signature']
                    if not request.coldBootstrap:
                        fail(backend.digest(signed) == request.expectedSignature,
                             'INDEX_AVAILABILITY_SIGNATURE', 'Current index signature differs from the expected normal index')
                    if 'expectedGeneration' in request.model_fields_set:
                        fail(stored.get('generation') == request.expectedGeneration,
                             'INDEX_AVAILABILITY_GENERATION', 'Index generation changed; refresh the current scope')
                    chunks = selected_index_chunks(stored, request)
                    collection = runtime.index_collection(key, signed, stored.get('generation'))
                    from index_integrity import verify_availability
                    live = verify_availability(backend.database() if chunks else None, stored,
                        key=key, collection=collection, signature=signed)
                    frame = getattr(source_registry, '_validation_frame', None)
                    owned = frame.get() if frame is not None else None
                    metrics = owned['indexMetadata'][key]['metrics'] if owned is not None and key in owned['indexMetadata'] else {}
                    result = {'projectId': key, 'ready': True, 'readOnly': True,
                        'indexAvailabilityContract': INDEX_AVAILABILITY_CONTRACT,
                         'coldBootstrap': request.coldBootstrap,
                        'fullVectorIntegrityChecked': False, 'indexed': stored['parentCount'],
                        'vectorPoints': live['pointCount'], 'vectorDimension': live['dimension'],
                        'signature': backend.digest(signed), 'indexGeneration': stored.get('generation'),
                        'collection': collection, 'contract': runtime.contract(),
                        'canonicalProjections': list(request.projectionSha256), 'targetModule': request.module,
                        'sourcePageEvidence': dict(sorted(request.sourcePageEvidence.items())),
                        'competitionScope': scope_receipt()}
                unframed_metadata_exit(key, stored)
                fail(live == verify_availability(backend.database() if chunks else None, stored,
                    key=key, collection=collection, signature=signed),
                     'INDEX_AVAILABILITY_CHANGED', 'Live index changed during availability checks')
                result.update(metadataBytesRead=metrics.get('bytesRead', 0),
                    metadataJsonDecodes=metrics.get('jsonDecodes', 0), elapsedSeconds=time.perf_counter() - started)
                return result
            finally:
                if pins_context is not None:
                    pins_context.reset(token)
        except WindowError as error:
            backend.forget_project(key)
            raise rejected(error) from error
        except HTTPException:
            backend.forget_project(key)
            raise
        except (ValueError, OSError, KeyError, TypeError) as error:
            backend.forget_project(key)
            raise HTTPException(422, {'kind': 'CANONICAL_INDEX_AVAILABILITY_UNAVAILABLE',
                'message': 'Current source-bound index is unavailable'}) from error

    def canonical_snapshot(projection_sha256: str, module: str = 'vetting'):
        try:
            chunks = source_registry.chunks(projection_sha256, module=module)
            assert_canonical_scope(chunks, policy, module=module)
            return source_registry.load(projection_sha256)
        except (ValueError, WindowError) as error:
            raise HTTPException(404, 'Canonical source revision is unavailable') from error

    def source_asset(projection_sha256: str, source_sha256: str, asset_id: str, module: str = 'vetting'):
        try:
            chunks = source_registry.chunks(projection_sha256, module=module)
            assert_canonical_scope(chunks, policy, module=module)
            data, media, reference = source_registry.asset(projection_sha256, source_sha256,
                                                          asset_id, module=module)
            return Response(data, media_type=media, headers={'X-Content-Type-Options': 'nosniff',
                'ETag': '"' + reference['sha256'] + '"', 'Cache-Control': 'private, no-cache',
                'X-Source-Sha256': source_sha256, 'X-Projection-Sha256': projection_sha256})
        except (ValueError, WindowError, OSError) as error:
            raise HTTPException(404, 'Source-bound evidence asset is unavailable') from error

    def guarded_status(handler, request):
        from migration_project_registry import runtime_route
        try:
            with backend.LOCK:
                from source_request_context import check
                check()
                with source_frame():
                    key = backend.project_key(request.projectId)
                    route = runtime_route(backend, key, backend._windows_runtime)
                    result = handler(request)
                    fail(runtime_route(backend, key, backend._windows_runtime) == route,
                         'MIGRATION_ROUTE_CHANGED', 'Project route changed during status request')
                    return result
        except WindowError as error:
            raise rejected(error) from error

    def canonical_index_status(request):
        return guarded_status(_canonical_index_status, request)

    def canonical_index_availability(request):
        return guarded_status(_canonical_index_availability, request)

    # Avoid deferred dynamic request annotations: FastAPI must see the actual
    # backend Pydantic types when registering these explicit endpoints.
    index.__annotations__['request'] = backend.IndexRequest
    retrieve.__annotations__['request'] = backend.RetrieveRequest
    project.__annotations__['request'] = ProjectionRequest
    canonical_index.__annotations__['request'] = CanonicalIndexRequest
    canonical_index_status.__annotations__['request'] = CanonicalIndexStatusRequest
    canonical_index_availability.__annotations__['request'] = CanonicalIndexAvailabilityRequest
    index = journal.wrap('/index', index)
    retrieve = journal.wrap('/retrieve', retrieve)
    ordinary_retrieve = retrieve
    def retrieve(payload):
        if fixed_reference is not None and fixed_reference.owns_project(payload.projectId):
            try:
                return fixed_reference.retrieve(payload)
            except WindowError as error:
                raise rejected(error) from error
        return ordinary_retrieve(payload)
    retrieve.__annotations__['payload'] = backend.RetrieveRequest
    canonical_index = journal.wrap('/index-canonical', canonical_index)
    canonical_index_status = journal.wrap('/index-canonical/status', canonical_index_status)
    app.get('/health')(health)
    def remote_owned(handler, request_type):
        async def bounded(payload, http_request):
            from source_request_context import execute_owned
            try:
                return await execute_owned(http_request, lambda:handler(payload), backend._remote_models.timeout)
            except WindowError as error:
                raise rejected(error) from error
        bounded.__annotations__ = {'payload':request_type, 'http_request':Request}
        return bounded
    remote = getattr(backend, '_remote_models', None) is not None
    app.post('/index')(remote_owned(index,backend.IndexRequest) if remote else index)
    app.post('/retrieve')(remote_owned(retrieve,backend.RetrieveRequest) if remote else retrieve)
    app.post('/project')(project)
    app.post('/index-canonical')(remote_owned(canonical_index,CanonicalIndexRequest) if remote else canonical_index)
    app.post('/index-canonical/status')(canonical_index_status)
    app.post('/index-canonical/availability')(canonical_index_availability)
    app.get('/canonical/{projection_sha256}')(canonical_snapshot)
    app.get('/canonical/{projection_sha256}/sources/{source_sha256}/assets/{asset_id}')(source_asset)
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8868)
    parser.add_argument('--scope-manifest')
    parser.add_argument('--scope-manifest-sha256')
    args = parser.parse_args()
    supplied = dict(os.environ)
    if args.scope_manifest:
        supplied['CONSENSE_SL_SCOPE_MANIFEST'] = args.scope_manifest
    if args.scope_manifest_sha256:
        supplied['CONSENSE_SL_SCOPE_MANIFEST_SHA256'] = args.scope_manifest_sha256
    values = configure_environment(supplied, resolve_workspace(HERE, supplied))
    policy = load_policy(values['CONSENSE_SL_SCOPE_MANIFEST'],
                         values.get('CONSENSE_SL_SCOPE_MANIFEST_SHA256'))
    os.environ.update(values)
    import uvicorn
    uvicorn.run(create_app(policy, target_module=values['CONSENSE_OER_TARGET_MODULE']),
                host=args.host, port=args.port, workers=1)


if __name__ == '__main__':
    main()
