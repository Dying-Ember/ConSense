"""Explicit read-only access to one externally pinned Server migration.

The historical index-build signature stays intact. Query model identity is
verified and observed separately; this does not certify semantic compatibility.
"""
from dataclasses import asdict
from contextlib import ExitStack
import hashlib
import json
import math
import copy
import os
from pathlib import Path
from threading import Lock

from index_integrity import verify_reuse, verify_availability
from migrate_qdrant_server import validate_metadata
from token_windows import digest, fail, WindowError
from verify_retrieval_offline import descriptor
from portable_index_signature import migration_signature_version

def _require_sealed_history_dependencies():
    # Lazy imports preserve fixed-development users that do not ship these files.
    import importlib
    try:
        bridge = importlib.import_module('sealed_index_bridge_candidate')
        migrator = importlib.import_module('migrate_sealed_collections')
        unchanged = (hashlib.sha256(Path(bridge.__file__).read_bytes()).hexdigest()
                     == bridge._IMPORTED_SOURCE_SHA256
                     and hashlib.sha256(Path(migrator.__file__).read_bytes()).hexdigest()
                     == migrator._IMPORTED_SOURCE_SHA256)
    except (OSError, ImportError, AttributeError, SyntaxError, ValueError, TypeError):
        unchanged = False
    fail(unchanged, 'MIGRATED_INDEX_SOFTWARE_CHANGED',
         'Sealed history reader dependencies changed since import')


ENV = 'CONSENSE_QDRANT_MIGRATION_RECEIPT'
_witness_lock = Lock()
_witness_slot = Lock()
_witness_release_unknown = False


def configured(environ):
    return bool(environ.get(ENV) or environ.get(ENV+'_SHA256'))


def artifact_path(value):
    text = str(value).replace('\\', '/')
    # shortcut: Windows receipts on Linux require the existing WSL /mnt/<drive> mounts.
    if os.name != 'nt' and len(text) > 2 and text[1] == ':':
        text = '/mnt/'+text[0].lower()+text[2:]
    return Path(text).resolve()


def bound(value):
    path = artifact_path(value['path']); actual = descriptor(path)
    fail(type(value['bytes']) is int and actual['bytes'] == value['bytes'] and actual['sha256'] == value['sha256'],
         'MIGRATED_INDEX_ARTIFACT_CHANGED', 'Pinned migration artifact bytes changed')
    return path


def _pinned_json(value):
    raw = bound(value).read_bytes()
    fail(len(raw) == value['bytes'] and hashlib.sha256(raw).hexdigest() == value['sha256'],
         'MIGRATED_INDEX_ARTIFACT_CHANGED', 'Pinned migration JSON bytes changed while reading')
    return json.loads(raw)


def _metadata_digest(value):
    sha = hashlib.sha256()
    encoder = json.JSONEncoder(ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
    for part in encoder.iterencode(value):
        sha.update(part.encode('utf-8'))
    return sha.hexdigest()


class MigratedIndex:
    def __init__(self, environ):
        self.environ = environ
        self.configuration = tuple(environ.get(name) for name in (ENV, ENV+'_SHA256', 'CONSENSE_QDRANT_URL'))
        path, expected, url = self.configuration
        fail(bool(path) and bool(expected) and bool(url), 'MIGRATED_INDEX_CONFIG',
             'Read-only migration requires receipt path, external SHA256 and Server URL')
        self.receipt_file = descriptor(artifact_path(path))
        fail(self.receipt_file['sha256'] == expected, 'MIGRATED_INDEX_RECEIPT_CHANGED', 'Migration receipt external SHA differs')
        self._receipt_descriptor = tuple(self.receipt_file[name] for name in ('path', 'bytes', 'sha256'))
        self.receipt = _pinned_json(self.receipt_file)
        r = self.receipt
        fail(r.get('schema') == 'consense.qdrant-server-migration.v1'
             and r.get('status') == 'completed_full_storage_readback_candidate_not_adopted'
             and r['target']['url'] == url and r.get('targetAdopted') is False
             and r.get('activeConfigurationChanged') is False and r.get('allOriginalPersistentStateBytesUnchanged') is True,
             'MIGRATED_INDEX_RECEIPT_INVALID', 'Complete unchanged-source migration bound to this Server URL required')
        self.frozen = _pinned_json(r['frozenManifest'])
        self.metadata = _pinned_json(r['candidateMetadata'])
        f, m = self.frozen, self.metadata
        source = {name:f['vectorIntegrity'][name] for name in ('schema','dimension','dtype','pointCount','embeddingSignatureSha256')}
        source['pointsSha256'] = f['vectorIntegrity']['persistedPointsSha256']
        target = r['targetVectorIntegrity']
        fail(r.get('sourceVectorIntegrity') == source
             and all(target.get(name) == source[name] for name in ('schema','dimension','dtype','pointCount','embeddingSignatureSha256')),
             'MIGRATED_INDEX_SOURCE_VECTOR_CHANGED', 'Source or target vector identity differs from the frozen index')
        if target != source:
            differences = (r.get('maximumVectorDifference'), r.get('maximumCosineDelta'))
            fail(all(type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 1e-6 for value in differences)
                 and isinstance(r.get('fullPointLedger'), dict), 'MIGRATED_INDEX_SOURCE_VECTOR_CHANGED',
                 'Migrated vectors lack complete bounded float32 comparison evidence')
            bound(r['fullPointLedger'])
        seal = {**r['targetVectorIntegrity'], 'persistedPointsSha256':r['targetVectorIntegrity']['pointsSha256']}
        seal['sealSha256'] = digest(seal)
        fail(f['schema'] == 'consense.frozen-fixed-development-baseline.v1'
             and m['collection'] == r['target']['collection'] and m['collection'] != f['collection']
             and m['vectorIntegrity'] == seal and r['indexSignature'] == f['indexSignature']
             and r['parentCount'] == m['parentCount'] and r['vectorWindowCount'] == m['vectorPoints'],
             'MIGRATED_INDEX_IDENTITY', 'Target collection, full metadata and reviewed seal differ')
        # Only these two fields change during migration; source identity remains the original freeze.
        validate_metadata(f, {**m, 'collection':f['collection'], 'vectorIntegrity':f['vectorIntegrity']})
        self._receipt_sha256 = _metadata_digest(r)
        self._frozen_sha256 = _metadata_digest(f)
        self._metadata_sha256 = _metadata_digest(m)
        self._metadata_stamp = self._candidate_metadata_stamp()
        self._verified_vector_integrity = None
        self._verification_generation = 0
        self._fixed_query_signature = None
        self.last_observation = None

    def _candidate_metadata_stamp(self):
        info = artifact_path(self.receipt['candidateMetadata']['path']).stat()
        return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)

    def candidate_identity(self, key, *, reuse_verified=False):
        fail(self.configuration == tuple(self.environ.get(name) for name in (ENV, ENV+'_SHA256', 'CONSENSE_QDRANT_URL')),
             'MIGRATED_INDEX_CONFIG_CHANGED', 'Migration configuration changed; start a new runtime')
        fail(key == self.metadata['projectId'], 'MIGRATED_INDEX_PROJECT', 'Only the pinned migrated project can be queried')
        fail(tuple(self.receipt_file.get(name) for name in ('path', 'bytes', 'sha256')) == self._receipt_descriptor
             and _metadata_digest(self.receipt) == self._receipt_sha256
             and _metadata_digest(self.frozen) == self._frozen_sha256,
             'MIGRATED_INDEX_METADATA_CHANGED', 'Migration candidate or receipt changed in memory')
        bound(self.receipt_file); bound(self.receipt['frozenManifest'])
        stamp = self._candidate_metadata_stamp()
        if not reuse_verified or self._verified_vector_integrity is None or stamp != self._metadata_stamp:
            actual = _metadata_digest(self.metadata)
            fail(actual == self._metadata_sha256, 'MIGRATED_INDEX_METADATA_CHANGED',
                 'Migration candidate or receipt changed in memory')
            bound(self.receipt['candidateMetadata'])
            fail(stamp == self._candidate_metadata_stamp(), 'MIGRATED_INDEX_ARTIFACT_CHANGED',
                 'Migration metadata changed while checking its bytes')
            if stamp != self._metadata_stamp:
                self._verified_vector_integrity = None
                self._fixed_query_signature = None
                self._metadata_stamp = stamp
        else:
            actual = self._metadata_sha256
        return (self._receipt_descriptor,
                *(tuple(self.receipt[key][name] for name in ('path', 'bytes', 'sha256'))
                  for key in ('frozenManifest', 'candidateMetadata')), actual)

    def read(self, key, runtime, database, *, reuse_verified=False):
        self.candidate_identity(key, reuse_verified=reuse_verified)
        m = self.metadata
        fail(m['contract'] == runtime.contract() and migration_signature_version(m['signature']['signatureVersion'], runtime.signature_version())
             and asdict(runtime.recipe('embedding')) == m['signature']['windowRecipe'],
             'MIGRATED_INDEX_QUERY_RECIPE', 'Query contract or full source window recipe differs')
        identity = runtime.identity('embedding'); original = m['signature']['tokenizerAndModelFiles']
        fields = ('model', 'files', 'backendSha256', 'tokenizerClass', 'fast', 'modelMaxLength')
        fail(all(identity.get(name) == original.get(name) for name in fields),
             'MIGRATED_INDEX_QUERY_MODEL', 'Query model/tokenizer file content differs from the sealed index')
        for kind in ('embedding', 'reranker'):
            fail(kind in runtime.model_publications, 'MIGRATED_INDEX_QUERY_PUBLICATION', 'Both frozen model publications are required')
            publication = runtime.publication_identity(kind)
            fail(publication['manifestSha256'] == self.frozen['modelPublications'][kind]['sha256'],
                 'MIGRATED_INDEX_QUERY_PUBLICATION', 'Query model publication differs from the frozen release')
        db = database()
        full_checked = not (reuse_verified and self._verified_vector_integrity is not None)
        if not full_checked:
            verify_availability(db, m, key=key, collection=m['collection'], signature=m['signature'])
            actual = self._verified_vector_integrity
        else:
            actual = verify_reuse(db, m, key=key, collection=m['collection'], chunks=m['chunks'], signature=m['signature'])
            fail(actual == self.receipt['targetVectorIntegrity'], 'MIGRATED_INDEX_VECTOR_CHANGED',
                 'Actual complete Server vector seal differs from the reviewed migration')
            if reuse_verified:
                self._verified_vector_integrity = dict(actual)
                self._verification_generation += 1
        if reuse_verified and self._fixed_query_signature is not None:
            saved_runtime, saved_signature = self._fixed_query_signature
            from portable_index_signature import portable
            fail(saved_runtime is runtime
                 and saved_signature['signatureVersion'] == runtime.signature_version()
                 and saved_signature['contract'] == runtime.contract()
                 and saved_signature['embedding'] == runtime.s.MODELS['embedding']
                 and saved_signature['runtime'] == runtime.s.model_runtime('embedding')
                 and saved_signature['windowRecipe'] == asdict(runtime.recipe('embedding'))
                 and saved_signature['structuralSelectionPolicy'] == runtime.selection_policy()
                 and saved_signature['tokenizerAndModelFiles'] == portable(identity)
                 and saved_signature['algorithms'] == runtime.embedding_algorithms(),
                 'MIGRATED_INDEX_QUERY_RECIPE', 'Fixed query runtime identity changed after verification')
            query_signature = copy.deepcopy(saved_signature)
        else:
            query_signature = runtime.signature(m['chunks'])
            if reuse_verified:
                self._fixed_query_signature = (runtime, copy.deepcopy(query_signature))
        self.last_observation = {'receipt':self.receipt_file, 'indexSignature':digest(m['signature']),
                                 'queryRuntimeSignature':digest(query_signature), 'queryIdentity':query_signature,
                                 'queryRecipe':runtime.retrieval_recipe(), 'target':self.receipt['target'],
                                 'fullVectorIntegrity':actual, 'fullVectorIntegrityCheckedThisRead':full_checked,
                                 'readOnly':True, 'runtimeCompatibilityVerified':False}
        runtime.emit('migrated-server-index-read', self.last_observation)
        return m


class SealedHistoryIndex:
    """Read a pinned sealed-history migration without inventing an older publication manifest."""
    def __init__(self, environ):
        from sealed_index_bridge_candidate import SCHEMA, candidate_artifacts
        _require_sealed_history_dependencies()
        self.environ = environ
        self.configuration = tuple(environ.get(name) for name in (ENV, ENV+'_SHA256', 'CONSENSE_QDRANT_URL'))
        path, expected, url = self.configuration
        fail(bool(path) and bool(expected) and bool(url), 'MIGRATED_INDEX_CONFIG', 'Pinned migration and Server URL required')
        fail(artifact_path(path).name == 'candidate-receipt.json', 'MIGRATED_INDEX_RECEIPT_INVALID',
             'Published sealed history receipt required')
        self.receipt_file = descriptor(artifact_path(path))
        fail(self.receipt_file['sha256'] == expected, 'MIGRATED_INDEX_RECEIPT_CHANGED', 'Migration receipt external SHA differs')
        self.receipt = _pinned_json(self.receipt_file)
        fail(self.receipt.get('schema') == SCHEMA, 'MIGRATED_INDEX_RECEIPT_INVALID', 'Sealed history receipt required')
        try:
            self.metadata, self._legacy_scope = candidate_artifacts(self.receipt, url, self.receipt['h2Project'],
                self.receipt['module'], include_legacy_scope=True)
        except (ValueError, KeyError, OSError, TypeError) as error:
            fail(False, 'MIGRATED_INDEX_RECEIPT_INVALID', 'Sealed history identity could not be verified')
        self._receipt_descriptor = tuple(self.receipt_file[name] for name in ('path', 'bytes', 'sha256'))
        self._receipt_sha256 = _metadata_digest(self.receipt)
        self._metadata_sha256 = _metadata_digest(self.metadata)
        self._legacy_scope_sha256 = _metadata_digest(self._legacy_scope)
        self.last_observation = None

    def candidate_identity(self, key):
        _require_sealed_history_dependencies()
        fail(self.configuration == tuple(self.environ.get(name) for name in (ENV, ENV+'_SHA256', 'CONSENSE_QDRANT_URL'))
            and key == self.metadata.get('projectId'), 'MIGRATED_INDEX_PROJECT', 'Pinned project and configuration required')
        fail(tuple(self.receipt_file.get(name) for name in ('path', 'bytes', 'sha256')) == self._receipt_descriptor
            and _metadata_digest(self.receipt) == self._receipt_sha256
            and _metadata_digest(self.metadata) == self._metadata_sha256
            and _metadata_digest(self._legacy_scope) == self._legacy_scope_sha256,
            'MIGRATED_INDEX_METADATA_CHANGED', 'Pinned candidate changed in memory')
        bound(self.receipt_file)
        for item in (self.receipt['inputs']['metadata'], self.receipt['phaseB'], self.receipt['bindingAudit'],
                     self.receipt['bindings'], self.receipt['journal'], self.receipt['candidateMetadata']):
            bound(item)
        return (self._receipt_descriptor, self._metadata_sha256)

    def legacy_source_scope(self, key):
        import copy
        fail(key == self.metadata.get('projectId') and 'sourceScope' not in self.metadata,
             'MIGRATED_INDEX_PROJECT', 'Legacy scope must belong to this pinned index')
        fail(isinstance(self._legacy_scope, dict) and _metadata_digest(self._legacy_scope) == self._legacy_scope_sha256,
             'MIGRATED_INDEX_METADATA_CHANGED',
             'Legacy source bindings differ from the pinned index')
        return copy.deepcopy(self._legacy_scope)

    def read(self, key, runtime, database):
        registry = getattr(runtime, 'source_registry', None)
        frame_var = getattr(registry, '_validation_frame', None)
        frame = frame_var.get() if frame_var is not None else None
        record = frame.get('indexMetadata', {}).get(key) if isinstance(frame, dict) else None
        if isinstance(record, dict) and record.get('migrationReader') is self and record.get('stored') is self.metadata:
            fail(record.get('availabilityOnly') is False, 'MIGRATED_INDEX_FRAME_CONFLICT',
                 'A lightweight availability frame cannot serve a retrieval')
            record['leaseCheck']()
        else:
            self.candidate_identity(key)
        m = self.metadata
        fail(getattr(runtime, 'target_module', None) == self.receipt['module'],
             'MIGRATED_INDEX_PROJECT', 'Sealed history belongs to another module')
        signature = m['signature']
        fail(m['contract'] == runtime.contract() and migration_signature_version(signature['signatureVersion'], runtime.signature_version())
            and asdict(runtime.recipe('embedding')) == signature['windowRecipe'],
            'MIGRATED_INDEX_QUERY_RECIPE', 'Query contract or source window recipe differs')
        identity, original = runtime.identity('embedding'), signature['tokenizerAndModelFiles']
        fields = ('model', 'files', 'backendSha256', 'tokenizerClass', 'fast', 'modelMaxLength')
        fail(all(identity.get(name) == original.get(name) for name in fields),
             'MIGRATED_INDEX_QUERY_MODEL', 'Query model/tokenizer file content differs from the sealed index')
        query_signature = runtime.signature(m['chunks'])
        compatible_fields = ('normalized', 'embeddingPreprocessing', 'runtime', 'windowRecipe',
                             'structuralRecipe', 'structuralSelectionPolicy', 'sourceOrderBasis')
        fail(all(signature.get(name) == query_signature.get(name) for name in compatible_fields),
             'MIGRATED_INDEX_QUERY_RECIPE', 'Query preprocessing, normalization, runtime or recipe differs')
        actual = verify_reuse(database(), m, key=key, collection=m['collection'], chunks=m['chunks'], signature=signature)
        fail(actual == self.receipt['targetVectorIntegrity'], 'MIGRATED_INDEX_VECTOR_CHANGED', 'Actual Server vector seal differs')
        self.last_observation = {'receipt': self.receipt_file, 'indexSignature': digest(signature),
            'queryRuntimeSignature': digest(query_signature), 'queryIdentity': query_signature,
            'queryRecipe': runtime.retrieval_recipe(), 'target': self.receipt['target'],
            'fullVectorIntegrity': actual, 'readOnly': True, 'runtimeCompatibilityVerified': False,
            'continuousWriterIsolationVerified': False}
        runtime.emit('migrated-server-index-read', self.last_observation)
        return m


def load_migration_reader(environ):
    """Select the existing fixed-baseline or the explicitly pinned sealed-history format."""
    path, expected = environ.get(ENV), environ.get(ENV+'_SHA256')
    fail(bool(path) and bool(expected), 'MIGRATED_INDEX_CONFIG', 'Pinned migration receipt required')
    receipt_file = descriptor(artifact_path(path))
    fail(receipt_file['sha256'] == expected, 'MIGRATED_INDEX_RECEIPT_CHANGED', 'Migration receipt external SHA differs')
    receipt = _pinned_json(receipt_file)
    fail(isinstance(receipt, dict), 'MIGRATED_INDEX_RECEIPT_INVALID', 'Migration receipt must be an object')
    return SealedHistoryIndex(environ) if receipt.get('schema') == 'consense.sealed-index-server-migration-candidate.v1' else MigratedIndex(environ)


def _configured_sealed_history(environ):
    """Recognize the externally pinned format without constructing an old reader."""
    path, expected = environ.get(ENV), environ.get(ENV + '_SHA256')
    if not path or not expected or artifact_path(path).name != 'candidate-receipt.json':
        return False
    try:
        item = descriptor(artifact_path(path))
        if item['sha256'] != expected or item['bytes'] > 1024 * 1024:
            return False
        receipt = _pinned_json(item)
        return isinstance(receipt, dict) and receipt.get('schema') == 'consense.sealed-index-server-migration-candidate.v1'
    except (OSError, ValueError, TypeError, KeyError, WindowError):
        return False


def _source_frame_witness_provider():
    # shortcut: 02 must supply a trusted continuous isolation provider before enabling migration.
    return None


def _acquire_witness(provider, binding):
    global _witness_release_unknown
    try:
        manager = provider.acquire(binding)
        return manager, manager.__enter__()
    except BaseException as error:
        with _witness_lock:
            _witness_release_unknown = True
        if isinstance(error, Exception):
            fail(False, 'MIGRATED_INDEX_ISOLATION_UNVERIFIED', 'Target isolation admission is unknown')
        raise


def _close_witness(manager):
    global _witness_release_unknown
    try:
        manager.__exit__(None, None, None)
    except BaseException:
        with _witness_lock:
            _witness_release_unknown = True
        raise


def migration_candidate_for_frame(backend, key, source_registry, route=None, *, availability_only=False):
    """Bind a candidate to one source frame; full index verification remains in read()."""
    global _witness_release_unknown
    fail(route is None or route.get('mode') in ('legacy', 'migrated'),
         'MIGRATION_ROUTE_CHANGED', 'Migrated candidate requires a migrated route')
    frame_var = getattr(source_registry, '_validation_frame', None)
    frame = frame_var.get() if frame_var is not None else None
    fail(frame is not None, 'MIGRATED_INDEX_SOURCE_FRAME_REQUIRED',
         'Migrated index scope requires a request-owned source validation frame')
    guard = getattr(backend, 'verify_project_publication', None)
    if guard is not None:
        guard(key, None)
    provider = _source_frame_witness_provider()
    candidate_environment = dict(os.environ)
    if route and route.get('mode') == 'migrated':
        candidate_environment[ENV] = route['receiptPath']
        candidate_environment[ENV + '_SHA256'] = route['receiptSha256']
    sealed_detection = provider is None and (
        isinstance(getattr(backend, '_migrated_index', None), SealedHistoryIndex)
        or _configured_sealed_history(candidate_environment))
    fail((sealed_detection or (provider is not None and callable(getattr(provider, 'acquire', None))))
         and isinstance(frame.get('leaseStack'), ExitStack), 'MIGRATED_INDEX_ISOLATION_UNVERIFIED',
         'Migrated index requires a source frame and supported target verification')
    record = frame['indexMetadata'].get(key)
    if record is not None:
        reader = record.get('migrationReader') if route and route['mode'] == 'migrated' else getattr(backend, '_migrated_index', None)
        fail(reader is not None and record.get('migrationReader') is reader and record.get('stored') is reader.metadata
             and record.get('witnessProvider') is provider
             and record.get('availabilityOnly') is availability_only,
             'MIGRATED_INDEX_FRAME_CONFLICT', 'Request frame already binds another index metadata object')
        record['leaseCheck']()
        return record['stored']
    pending = ExitStack()
    frame['leaseStack'].callback(pending.close)
    try:
        fail(_witness_slot.acquire(blocking=False), 'MIGRATED_INDEX_ISOLATION_UNVERIFIED',
             'Another target isolation lease is active')
        pending.callback(_witness_slot.release)
        with _witness_lock:
            fail(not _witness_release_unknown, 'MIGRATED_INDEX_ISOLATION_UNVERIFIED',
                 'A prior target isolation lease has unknown release')
        if route and route['mode'] == 'migrated':
            environ = dict(os.environ)
            environ[ENV], environ[ENV+'_SHA256'] = route['receiptPath'], route['receiptSha256']
            if route.get('singleHistory'):
                cache_key = (key, route['receiptPath'], route['receiptSha256'], environ.get('CONSENSE_QDRANT_URL'))
                cached = getattr(backend, '_single_history_reader', None)
                if not (isinstance(cached, tuple) and len(cached) == 2 and cached[0] == cache_key):
                    reader = load_migration_reader(environ)
                    fail(isinstance(reader, SealedHistoryIndex), 'MIGRATED_INDEX_RECEIPT_INVALID',
                         'Single history route requires a sealed history receipt')
                    cached = (cache_key, reader)
                    backend._single_history_reader = cached
                reader = cached[1]
                fail(isinstance(reader, SealedHistoryIndex), 'MIGRATED_INDEX_RECEIPT_INVALID',
                     'Single history route requires a sealed history receipt')
            else:
                reader = load_migration_reader(environ)
        else:
            reader = getattr(backend, '_migrated_index', None)
            if reader is None:
                reader = load_migration_reader(os.environ)
                backend._migrated_index = reader
        if isinstance(reader, SealedHistoryIndex):
            fail(getattr(source_registry, 'family_target_module', None) == reader.receipt['module'],
                 'MIGRATED_INDEX_PROJECT', 'Sealed history belongs to another source module')
        expected = reader.candidate_identity(key)
        stored = reader.metadata
        target = reader.receipt['target']
        binding = (key, reader.receipt_file['sha256'], target['url'], target['collection'],
                   stored.get('generation'), digest(reader.receipt['targetVectorIntegrity']))
        if sealed_detection:
            # This is detection at two points, not a claim of cross-process isolation.
            fail(isinstance(reader, SealedHistoryIndex), 'MIGRATED_INDEX_ISOLATION_UNVERIFIED',
                 'Detection-only mode is limited to sealed history')
            from window_runtime import require_initial_database_binding
            lock = backend.LOCK
            lock.acquire()
            pending.callback(lock.release)
            configuration = (str(backend.STATE), os.environ.get('CONSENSE_QDRANT_URL'),
                             os.environ.get('CONSENSE_QDRANT_API_KEY'))
            database = backend.database()
            def check_lease():
                require_initial_database_binding(backend, database, configuration)
            def check_seal():
                check_lease()
                if availability_only:
                    from index_integrity import verify_availability
                    actual = verify_availability(database if stored['chunks'] else None, stored,
                        key=key, collection=target['collection'], signature=stored['signature'])
                else:
                    actual = verify_reuse(database, stored, key=key, collection=target['collection'],
                        chunks=stored['chunks'], signature=stored['signature'])
                check_lease()
                if not availability_only:
                    fail(actual == reader.receipt['targetVectorIntegrity'], 'MIGRATED_INDEX_VECTOR_CHANGED',
                         'Sealed history target vector integrity changed')
            check_seal()
        else:
            with _witness_lock:
                _witness_release_unknown = True
            manager, lease = _acquire_witness(provider, binding)
            # Explicit close cannot suppress a failure from the protected frame.
            pending.callback(_close_witness, manager)
            with _witness_lock:
                _witness_release_unknown = False
            def check_lease():
                fail(not _witness_release_unknown and callable(getattr(lease, 'verify', None))
                     and lease.verify(binding) is True and not _witness_release_unknown,
                     'MIGRATED_INDEX_ISOLATION_UNVERIFIED', 'Target isolation lease is invalid or expired')
            check_lease()
    except BaseException:
        pending.close()
        raise
    def identity():
        fail((route and route['mode'] == 'migrated' or getattr(backend, '_migrated_index', None) is reader)
             and reader.metadata is stored,
             'MIGRATED_INDEX_FRAME_CONFLICT', 'Migrated index reader changed during the request')
        check_lease()
        current = reader.candidate_identity(key)
        if sealed_detection:
            check_seal()
        return current
    frame['indexMetadata'][key] = {'stored':stored, 'migrationReader':reader, 'witnessProvider':provider,
        'expected':expected, 'identity':identity, 'leaseCheck':check_lease, 'metrics':{},
        'availabilityOnly':availability_only,
        'continuousWriterIsolationVerified': False if sealed_detection else None}
    return stored
