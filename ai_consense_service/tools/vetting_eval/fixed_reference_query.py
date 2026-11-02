"""Read one configured frozen reference index using the existing retrieval runtime."""
import copy
import hashlib
import os
import re

from request_stage import encoded
from token_windows import fail
from window_runtime import WindowRuntime


class FixedReferenceRuntime(WindowRuntime):
    def validate_query_metadata(self, metadata):
        reader = self.s._migrated_index
        cached = getattr(self, '_fixed_validated_metadata', None)
        if cached is not None and cached[0] is metadata and cached[1] == reader._verification_generation:
            return cached[2]
        validated = super().validate_query_metadata(metadata)
        self._fixed_validated_metadata = (metadata, reader._verification_generation, validated)
        return validated


class FixedBackend:
    """Request-independent view: ordinary project's model recipe remains untouched."""
    def __init__(self, backend, reader):
        self._backend = backend
        self._migrated_index = reader
        self._window_observer = None
        self.MODEL_SETTINGS = copy.deepcopy(backend.MODEL_SETTINGS)
        self.MODEL_SETTINGS['embedding'].update(maxTokens=512, batchSize=16)
        self.MODEL_SETTINGS['reranker'].update(maxTokens=512, batchSize=4)
        self._runtime = None

    def __getattr__(self, name):
        return getattr(self._backend, name)

    def model_runtime(self, kind):
        return {**self.MODEL_SETTINGS[kind], **self.MODELS[kind],
                'device': 'cuda', 'dtype': 'float16', 'serialOffload': True}

    def window_runtime(self):
        return self._runtime

    def read_project(self, key):
        return self._migrated_index.read(key, self._runtime, self.database, reuse_verified=True)

    def rerank_candidates(self, query, candidates):
        return self._runtime.rerank(query, candidates)


class FixedReferenceQuery:
    def __init__(self, backend, reader, project_id, manifest_sha256):
        from structural_retrieval import StructuralRecipe
        fail(isinstance(project_id, str) and project_id and re.fullmatch('[a-f0-9]{64}', manifest_sha256 or '') is not None,
             'FIXED_REFERENCE_CONFIG', 'Fixed reference project and manifest identity required')
        fail(reader.metadata.get('projectId') == project_id and reader.receipt['frozenManifest']['sha256'] == manifest_sha256,
             'FIXED_REFERENCE_IDENTITY', 'Configured fixed reference project or manifest differs')
        self.reader, self.project_id, self.manifest_sha256 = reader, project_id, manifest_sha256
        self.backend = FixedBackend(backend, reader)
        self.backend._runtime = FixedReferenceRuntime(self.backend)
        self.backend._runtime.structural_recipe = StructuralRecipe(mode='windows-v1')
        self.backend._runtime.overlap = 64
        sources = reader.frozen['originalSources']
        editions = sources.get('slSources', [])
        self.sl_sources = {row['sha256'] for row in editions}
        if 'slParent' in sources:
            fail(not editions, 'FIXED_REFERENCE_IDENTITY', 'Frozen SL catalogue is ambiguous')
            self.sl_sources.add(sources['slParent']['sha256'])
        fail(bool(self.sl_sources), 'FIXED_REFERENCE_IDENTITY', 'Frozen SL source catalogue is incomplete')

    def health(self):
        runtime = self.backend.window_runtime()
        return {'status': 'ok', 'mode': 'fixed-reference-query', 'device': 'cuda', 'offline': True,
                'fixedReferenceManifestSha256': self.manifest_sha256,
                'fixedReferenceIndexSignature': self.reader.frozen['indexSignature'],
                'indexSignatureVersion': self.reader.metadata['signature']['signatureVersion'],
                'queryRuntimeSignatureVersion': runtime.signature_version(), 'models': self.backend.MODELS,
                'runtime': {kind: self.backend.model_runtime(kind) for kind in ('embedding', 'reranker')},
                'retrieval': {'sourceWindows': runtime.retrieval_recipe(observe_tokenizer_identity=False)},
                'sourceQualityVerifiedByHealth': False}

    def owns_project(self, project_id):
        return str(project_id) == self.project_id

    def health_for_manifest(self, manifest_sha256):
        fail(manifest_sha256 == self.manifest_sha256, 'FIXED_REFERENCE_IDENTITY',
             'Configured fixed reference manifest differs')
        return self.health()

    def retrieve(self, request):
        from source_request_context import model_session_control
        from model_session_worker import MAX_BATCHES, MAX_TOTAL_BYTES
        selected = request.sourceSha256
        fail(str(request.projectId) == self.project_id and request.role == 'standard'
             and isinstance(selected, list) and len(selected) == 1 and selected[0] in self.sl_sources,
             'FIXED_REFERENCE_SELECTION', 'Select exactly one SL source from the frozen catalogue')
        source_sha = hashlib.sha256(encoded({'projectId': self.project_id, 'manifestSha256': self.manifest_sha256,
                                             'sourceSha256': sorted(selected)})).hexdigest()
        proxy = self.backend._remote_models
        with proxy.session_scope(model_session_control(), source_sha, 'fixed-reference-query',
                                 max_batches=MAX_BATCHES, max_total_bytes=MAX_TOTAL_BYTES,
                                 fixed_backend=self.backend):
            return self.backend.window_runtime().retrieve(request)


class FixedReferenceRouter:
    """Route to separately pinned derived indexes without mixing their identity."""
    def __init__(self, references):
        self.by_project = {}
        self.by_manifest = {}
        sources = set()
        for reference in references:
            fail(reference.project_id not in self.by_project and reference.manifest_sha256 not in self.by_manifest
                 and not (sources & reference.sl_sources), 'FIXED_REFERENCE_IDENTITY',
                 'Each fixed reference needs unique project, manifest and SL editions')
            self.by_project[reference.project_id] = reference
            self.by_manifest[reference.manifest_sha256] = reference
            sources.update(reference.sl_sources)
        fail(bool(self.by_project), 'FIXED_REFERENCE_CONFIG', 'At least one fixed reference is required')

    def owns_project(self, project_id):
        return str(project_id) in self.by_project

    def health_for_manifest(self, manifest_sha256):
        fail(manifest_sha256 in self.by_manifest, 'FIXED_REFERENCE_IDENTITY',
             'Configured fixed reference manifest differs')
        return self.by_manifest[manifest_sha256].health()

    def retrieve(self, request):
        fail(self.owns_project(request.projectId), 'FIXED_REFERENCE_IDENTITY', 'Unknown fixed reference project')
        return self.by_project[str(request.projectId)].retrieve(request)


def from_environment(backend):
    from migrated_qdrant_index import MigratedIndex
    multi_path = os.environ.get('CONSENSE_FIXED_REFERENCE_CATALOG')
    multi_sha = os.environ.get('CONSENSE_FIXED_REFERENCE_CATALOG_SHA256')
    if multi_path or multi_sha:
        from migrated_qdrant_index import artifact_path, _pinned_json
        from sl_derived_index import SlDerivedIndex
        from verify_retrieval_offline import descriptor
        fail(bool(multi_path) and bool(multi_sha) and bool(os.environ.get('CONSENSE_QDRANT_URL')),
             'FIXED_REFERENCE_CONFIG', 'Pinned derived index catalog and Server URL required')
        pin = descriptor(artifact_path(multi_path))
        fail(pin['sha256'] == multi_sha, 'FIXED_REFERENCE_IDENTITY', 'Derived index catalog SHA differs')
        catalog = _pinned_json(pin)
        rows = catalog.get('references')
        fail(catalog.get('schema') == 'consense.sl-reference-catalog.v1'
             and isinstance(rows, list) and bool(rows), 'FIXED_REFERENCE_CONFIG', 'Derived index catalog required')
        result = []
        for row in rows:
            reader = SlDerivedIndex(row['manifestPath'], row['manifestSha256'], os.environ['CONSENSE_QDRANT_URL'])
            result.append(FixedReferenceQuery(backend, reader, row['projectId'], row['manifestSha256']))
        return FixedReferenceRouter(result)
    names = ('CONSENSE_FIXED_REFERENCE_PROJECT_ID', 'CONSENSE_FIXED_REFERENCE_MANIFEST_SHA256',
             'CONSENSE_FIXED_REFERENCE_MIGRATION_RECEIPT', 'CONSENSE_FIXED_REFERENCE_MIGRATION_RECEIPT_SHA256')
    values = [os.environ.get(name) for name in names]
    if not any(values):
        return None
    fail(all(values) and bool(os.environ.get('CONSENSE_QDRANT_URL')), 'FIXED_REFERENCE_CONFIG',
         'Fixed reference needs a complete project, manifest, migrated index and Server configuration')
    env = {'CONSENSE_QDRANT_MIGRATION_RECEIPT': values[2], 'CONSENSE_QDRANT_MIGRATION_RECEIPT_SHA256': values[3],
           'CONSENSE_QDRANT_URL': os.environ['CONSENSE_QDRANT_URL']}
    return FixedReferenceQuery(backend, MigratedIndex(env), values[0], values[1])
