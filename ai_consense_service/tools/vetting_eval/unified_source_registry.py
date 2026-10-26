"""One actual-CAS registry coordinating existing PDF, DOCX and scanned gates.

Collection roots are bounded HTTP identities; hydrated complete parents are an
internal retrieval view only. PDF signals are dispatched before the broader
native-DOCX candidate detector, which also matches PDF recipe/block prefixes.
"""
from contextlib import contextmanager
from contextvars import ContextVar
from collections import OrderedDict
import hashlib
import json
import os
from threading import RLock
from typing import NamedTuple
from dataclasses import asdict
from pathlib import Path
from competition_scope import CompetitionScopePolicy

from native_collection_registry import NativeCollectionSourceRegistry
from native_observation import _strict_json
from native_pdf_source_registry import NativePdfCanonicalSourceRegistry, _pdf_candidate
from native_projection_collection import SCHEMA as COLLECTION_SCHEMA, hydrate_native_collection
from native_projection_transport import MAX_SNAPSHOT_BYTES
from oer_source_registry import SourceRegistryError, _serialize
from source_page_registry import SourcePageRenderRegistry
from native_pdf_source_family import (SCHEMA as FAMILY_SCHEMA, source_family_candidate,
    validate_native_pdf_source_family)
from oer_source_registry import CanonicalSourceRegistry, _identity
from normal_observed_projection import (SCHEMA as NORMAL_SCHEMA, normal_observed_candidate,
    validate_normal_observed, MAX_NORMAL_MESSAGE_BYTES, _canonical_chunks)


class _DerivedResult(NamedTuple):
    serialized: bytes
    dependencies: tuple
    file_dependencies: tuple
    collection: bool
    retained_bytes: int
    conditional_wire: bytes = b''
    normal_revision: str = ''
    canonical_wire: bool = False


class _PdfRegistry(NativePdfCanonicalSourceRegistry):
    """The reused PDF locator gate reads through the same bounded strict wire reader."""
    def __init__(self, root, artifact_store, wire_reader, frame_active, frame_loader, pin_file):
        super().__init__(root, artifact_store)
        self._wire_reader = wire_reader
        self._frame_active, self._frame_loader, self._pin_file = frame_active, frame_loader, pin_file

    def load(self, address):
        if self._frame_active():
            return self._frame_loader(address)
        with self.artifact_store.validation_session():
            wire = self._wire_reader(address)
            self.validator(wire)
            return wire

    def _pdf_revision_path(self, revision):
        path = super()._pdf_revision_path(revision)
        self._pin_file(path)
        return path


class UnifiedCanonicalSourceRegistry(NativeCollectionSourceRegistry):
    def __init__(self, root, artifact_store, *, derived_cache_max_bytes=64 * 1024 * 1024,
                 derived_cache_max_entries=64, family_policy=None, family_target_module='vetting',
                 maximum_message_bytes=MAX_NORMAL_MESSAGE_BYTES):
        if type(maximum_message_bytes) is not int or not 1024 <= maximum_message_bytes <= MAX_NORMAL_MESSAGE_BYTES:
            raise ValueError('Normal message limit must be an integer within the finite 1GiB maximum')
        self.normal_maximum_bytes = maximum_message_bytes
        self._validation_frame = ContextVar('consense_unified_source_validation_frame', default=None)
        self._file_collectors = ContextVar('consense_unified_file_dependency_collectors', default=())
        if (type(derived_cache_max_bytes) is not int or derived_cache_max_bytes < 0
                or type(derived_cache_max_entries) is not int or derived_cache_max_entries < 0):
            raise ValueError('Derived-cache budgets must be nonnegative integers')
        self._derived_max_bytes = derived_cache_max_bytes
        self._derived_max_entries = derived_cache_max_entries
        self._derived_results = OrderedDict()
        self._derived_bytes = 0
        self._derived_lock = RLock()
        super().__init__(root, artifact_store)
        self.pdf_registry = _PdfRegistry(root, artifact_store, self._wire,
            lambda: self._validation_frame.get() is not None, self.load, self._pin_frame_file)
        self.source_pages = SourcePageRenderRegistry(self.root / 'source-pages', self, artifact_store)
        self.family_policy, self.family_target_module = family_policy, family_target_module
        prior_validator = self.validator
        def validate(snapshot):
            if normal_observed_candidate(snapshot):
                return self._validate_normal(snapshot)
            if source_family_candidate(snapshot):
                return self._validate_family(snapshot)
            return prior_validator(snapshot)
        self.validator = validate

    def _validate_normal(self, snapshot):
        self._family_context()
        validate_normal_observed(snapshot, artifact_store=self.artifact_store)
        binding = snapshot['manifest']['sourceBinding']
        if (binding['scopePolicySha256'] != self.family_policy.policyIdentity['manifestSha256']
                or self.family_target_module not in binding['eligibleModules']):
            raise SourceRegistryError('NORMAL_OBSERVED_POLICY_MISMATCH',
                'Normal observations must retain the active scope policy and eligible process module')
        return snapshot

    def _normal_revision_path(self, revision, *, pin=True):
        path = self.root / 'normal-observed-revisions' / (_identity(revision, 'projectionRevisionSha256') + '.json')
        if pin:
            self._pin_frame_file(path, normal=True)
        return path

    def _validate_family(self, snapshot):
        self._family_context()
        return validate_native_pdf_source_family(snapshot, registry=self,
            artifact_store=self.artifact_store, policy=self.family_policy,
            target_module=self.family_target_module)

    def _family_revision_path(self, revision):
        path = self.root / 'native-pdf-family-revisions' / (_identity(revision, 'projectionRevisionSha256') + '.json')
        self._pin_frame_file(path)
        return path

    def _validate_family_and_native_chunks(self, chunks):
        families, normal, pdf, ordinary = {}, {}, [], []
        for chunk in chunks:
            self._object(chunk)
            if normal_observed_candidate(chunk):
                revision = _identity(chunk.get('projectionRevisionSha256'), 'projectionRevisionSha256')
                normal.setdefault(revision, []).append(chunk)
            elif source_family_candidate(chunk):
                revision = _identity(chunk.get('projectionRevisionSha256'), 'projectionRevisionSha256')
                families.setdefault(revision, []).append(chunk)
            elif _pdf_candidate(chunk):
                pdf.append(chunk)
            else:
                ordinary.append(chunk)
        count = super().validate_native_chunks(ordinary)
        count += self.pdf_registry.validate_native_chunks(pdf)
        for revision, members in normal.items():
            try:
                result = self._normal_proof(revision=revision)
            except (OSError, ValueError, KeyError, TypeError) as error:
                raise SourceRegistryError('NORMAL_OBSERVED_SOURCE_UNAVAILABLE',
                    'Normal source is unregistered or its complete current observation CAS is invalid') from error
            proof = json.loads(result.conditional_wire)
            frame = self._validation_frame.get()
            owned = frame['normalMembers'].get(revision) if frame is not None else None
            same_members = owned is not None and len(members) == len(owned[0]) and all(
                left is right for left, right in zip(members, owned[0]))
            if not same_members and self._json_sha(members) != proof['parentSha256']:
                raise SourceRegistryError('NORMAL_OBSERVED_INDEX_PARENT_MISMATCH',
                    'Normal index must retain every complete exact observed parent in source order')
            if frame is not None and not same_members:
                frame['normalMembers'][revision] = (members, proof['parentSha256'])
        for revision, members in families.items():
            try:
                locator = _strict_json(self._family_revision_path(revision).read_bytes())
                snapshot = self.load(locator['projectionSha256'])
            except (OSError, ValueError, KeyError, TypeError) as error:
                raise SourceRegistryError('SOURCE_FAMILY_SOURCE_UNAVAILABLE',
                    'Family source is unregistered or its complete child proof is invalid') from error
            if (snapshot.get('schema') != FAMILY_SCHEMA or snapshot.get('projectionRevisionSha256') != revision
                    or _serialize(locator) != _serialize(snapshot) or _serialize(members) != _serialize(snapshot['chunks'])):
                raise SourceRegistryError('SOURCE_FAMILY_INDEX_PARENT_MISMATCH',
                    'Family index must retain every complete exact derived parent in declared order')
        return count + len(families) + len(normal)

    @staticmethod
    def _json_sha(value):
        digest = hashlib.sha256()
        for part in _canonical_chunks(value):
            digest.update(part)
        return digest.hexdigest()

    def _derived_get(self, key):
        with self._derived_lock:
            result = self._derived_results.get(key)
            if result is not None:
                self._derived_results.move_to_end(key)
            return result

    def _derived_commit(self, pending):
        # Call only after complete current CAS/root/alias/software exit guards.
        # All retained payloads and keys are immutable private bytes. The byte
        # budget accounts payloads, descriptors and keys, not Python overhead;
        # the independent entry limit bounds that fixed per-entry overhead.
        with self._derived_lock:
            for key, result in pending.items():
                if (not self._derived_max_entries or result.retained_bytes > self._derived_max_bytes):
                    continue
                old = self._derived_results.pop(key, None)
                if old is not None:
                    self._derived_bytes -= old.retained_bytes
                self._derived_results[key] = result
                self._derived_bytes += result.retained_bytes
                while (len(self._derived_results) > self._derived_max_entries
                       or self._derived_bytes > self._derived_max_bytes):
                    _, removed = self._derived_results.popitem(last=False)
                    self._derived_bytes -= removed.retained_bytes

    def _fresh_dependencies(self, result):
        # Every complete actual object is freshly read/hashed on first use in
        # this frame and rehashed by its owning ArtifactStore at successful exit.
        # Only deterministic reconstruction is reused, never source validity.
        for raw in result.file_dependencies:
            dependency = json.loads(raw)
            path = Path(dependency['path'])
            actual = self._read_frame_file(path, normal=dependency.get('normal', False))
            if (str(path.resolve()), len(actual), hashlib.sha256(actual).hexdigest()) != (
                    dependency['resolvedPath'], dependency['byteCount'], dependency['sha256']):
                raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                    'A complete reconstruction file dependency differs from its exact prior bytes or location')
            self._pin_frame_file(path, actual, normal=dependency.get('normal', False))
        for raw in result.dependencies:
            self.artifact_store.verify(json.loads(raw))

    @contextmanager
    def _capture_file_dependencies(self):
        dependencies = {}
        token = self._file_collectors.set((*self._file_collectors.get(), dependencies))
        try:
            yield dependencies
        finally:
            self._file_collectors.reset(token)

    def _family_context(self):
        # Every field of the actual immutable policy matters: its abbreviated
        # public health identity does not enumerate metadata/excluded spans.
        if type(self.family_policy) is not CompetitionScopePolicy or self.family_target_module not in ('vetting', 'advisory'):
            raise SourceRegistryError('SOURCE_FAMILY_ACTIVE_POLICY_REQUIRED',
                'Family reconstruction requires the actual immutable policy and process module')
        return _serialize({'policy': asdict(self.family_policy),
            'policyIdentity': self.family_policy.policyIdentity, 'module': self.family_target_module,
            'registryRoot': str(self.root.resolve()), 'artifactRoot': str(self.artifact_store.root.resolve()),
            'normalMaximumBytes': self.normal_maximum_bytes})

    def _pin_family_context(self, frame):
        current = self._family_context()
        if frame['familyContext'] is not None and frame['familyContext'] != current:
            raise SourceRegistryError('CANONICAL_FRAME_POLICY_CHANGED',
                'Family policy, module or registry ownership changed during this request')
        frame['familyContext'] = current
        return current

    def _check_family_context(self, frame):
        if frame['familyContext'] is not None:
            self._pin_family_context(frame)

    def derived_reconstruction_policy(self):
        """Describe pure reuse without claiming this health read proves sources."""
        return {'version': 'immutable-byte-bound-derived-reconstruction-v1',
                'enabled': bool(self._derived_max_bytes and self._derived_max_entries),
                'maximumRetainedPayloadBytes': self._derived_max_bytes,
                'maximumEntries': self._derived_max_entries,
                'identity': ['exact_public_root_bytes', 'complete_current_runtime_software'],
                'fileDependencies': 'exact_bounded_child_root_and_locator_bytes_and_resolved_paths',
                'familyIdentity': 'complete_immutable_policy_module_and_registry_ownership',
                'completeActualByteDependencies': 'fresh_entry_and_successful_exit_each_request',
                'publishBoundary': 'after_all_successful_frame_exit_guards',
                'callerOwnership': 'fresh_public_root_or_detached_collection_materialization',
                'cachedSourceValidity': False, 'unframedReadsUseCache': False}

    def _pin_frame_file(self, path, raw=None, *, normal=False):
        frame = self._validation_frame.get()
        if frame is None:
            return
        if raw is None:
            raw = self._read_frame_file(path, normal=normal)
        maximum = self.normal_maximum_bytes if normal else MAX_SNAPSHOT_BYTES
        if len(raw) > maximum:
            raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE',
                'Normal source dependency exceeds its configured maximum' if normal
                else 'Public source dependency exceeds the fixed 16MiB maximum')
        current = (len(raw), hashlib.sha256(raw).hexdigest())
        key = str(path.resolve())
        prior = frame['files'].get(key)
        if prior is not None and prior != current:
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                'Published root or revision binding changed during this request')
        frame['files'][key] = current
        # Preserve the requested locator as well as its actual resolved path;
        # checking only the old target would miss a symlink/alias retarget.
        locator = str(path.absolute())
        location = (key, *current)
        prior_location = frame['locations'].get(locator)
        if prior_location is not None and (prior_location != location
                or frame['normalLocations'][locator] != normal):
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                'Published source locator changed during this request')
        frame['locations'][locator] = location
        frame['normalLocations'][locator] = normal
        if normal:
            frame.setdefault('streamLocations', set()).add(locator)
        dependency = {'path': locator, 'resolvedPath': key, 'byteCount': current[0], 'sha256': current[1],
                      'normal': normal}
        for collector in self._file_collectors.get():
            collector[locator] = dependency

    def _pin_stream_file(self, path, actual, *, normal):
        frame = self._validation_frame.get()
        if frame is None:
            return
        locator = str(path.absolute())
        prior = frame['locations'].get(locator)
        if prior is not None and (prior != actual or frame['normalLocations'][locator] != normal):
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED', 'Published source locator changed')
        frame['locations'][locator] = actual
        frame['normalLocations'][locator] = normal
        frame['files'][actual[0]] = actual[1:]
        frame.setdefault('streamLocations', set()).add(locator)
        dependency = {'path': locator, 'resolvedPath': actual[0], 'byteCount': actual[1], 'sha256': actual[2], 'normal': normal}
        for collector in self._file_collectors.get():
            collector[locator] = dependency

    def _read_frame_file(self, path, *, normal=False):
        # Only an unclassified root gets the finite outer read bound; schema dispatch follows immediately.
        maximum = (max(MAX_SNAPSHOT_BYTES, self.normal_maximum_bytes) if normal is None
                   else self.normal_maximum_bytes if normal else MAX_SNAPSHOT_BYTES)
        message = ('Normal source dependency exceeds its configured maximum' if normal
                   else 'Public source dependency exceeds the fixed 16MiB maximum')
        try:
            if path.stat().st_size > maximum:
                raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE', message)
            raw = path.read_bytes()
            if len(raw) > maximum:
                raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE', message)
            return raw
        except OSError as error:
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_UNAVAILABLE',
                'Published source dependency is no longer available') from error

    def _check_frame_files(self, frame):
        for filename, expected in frame['locations'].items():
            path = Path(filename)
            if filename in frame.get('streamLocations', set()):
                actual = self._stream_file_identity(path, maximum=expected[1])
                if actual != expected:
                    raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                        'Published root or revision binding changed during this request')
                continue
            raw = self._read_frame_file(path, normal=frame['normalLocations'][filename])
            if (str(path.resolve()), len(raw), hashlib.sha256(raw).hexdigest()) != expected:
                raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                    'Published root or revision binding changed during this request')

    @staticmethod
    def _stream_file_identity(path, *, maximum):
        """Hash actual dependency bytes with bounded memory; no stat verdict."""
        digest, size = hashlib.sha256(), 0
        try:
            resolved = str(path.resolve())
            with path.open('rb') as stream:
                while chunk := stream.read(1024 * 1024):
                    size += len(chunk)
                    if size > maximum:
                        raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                            'Published source dependency exceeds its sealed byte count')
                    digest.update(chunk)
            if str(path.resolve()) != resolved:
                raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED', 'Source locator changed during hashing')
        except RuntimeError as error:
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                'Published source locator cannot be resolved') from error
        except OSError as error:
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_UNAVAILABLE',
                'Published source dependency is no longer available') from error
        return resolved, size, digest.hexdigest()

    def verify_prepared_read_dependencies(self, files, assets):
        """Reprove prepared derivative inputs without parsing the source graph.

        This still streams every actual input byte at entry and successful
        exit. It saves graph construction and retained bytes, not source I/O.
        Only process-authenticated preparation manifests may call this seam.
        """
        frame = self._validation_frame.get()
        if frame is None:
            raise SourceRegistryError('CANONICAL_FRAME_REQUIRED', 'Prepared reads require a fresh source frame')
        self._pin_family_context(frame)
        for dependency in files:
            path = Path(dependency['path'])
            # Prepared locators belong to this registry, including revision
            # aliases. Do not let a manifest become an arbitrary file reader.
            if not path.resolve().is_relative_to(self.root):
                raise SourceRegistryError('SNAPSHOT_PATH_INVALID', 'Prepared dependency is outside this registry')
            expected = (dependency['resolvedPath'], dependency['byteCount'], dependency['sha256'])
            locator = str(path.absolute())
            prior = frame['locations'].get(locator)
            if prior is not None and prior != expected:
                raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED', 'Prepared source locator changed')
            if prior is None and self._stream_file_identity(path, maximum=dependency['byteCount']) != expected:
                raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED', 'Prepared source bytes changed')
            frame['locations'][locator] = expected
            frame['normalLocations'][locator] = dependency['normal']
            frame['files'][str(path.resolve())] = (expected[1], expected[2])
            frame.setdefault('streamLocations', set()).add(locator)
        for descriptor in assets:
            self.artifact_store.verify(descriptor)

    @contextmanager
    def validation_frame(self, *, software_guard):
        """Fresh request-only proof with complete CAS/root/alias/software exit checks.

        Each use rereads its published wire. A bounded immutable derived result
        may survive the frame; its entire recorded actual-byte dependency
        closure must be freshly verified within every new complete frame.
        """
        if not callable(software_guard):
            raise SourceRegistryError('CANONICAL_FRAME_SOFTWARE_GUARD_REQUIRED',
                'A request frame requires the actual imported-runtime software guard')
        active = self._validation_frame.get()
        if active is not None:
            if active['guard'] != software_guard:
                raise SourceRegistryError('CANONICAL_FRAME_GUARD_MISMATCH',
                    'Nested source frame must retain the same runtime guard')
            yield
            return
        # A fresh request frame must own the outer actual-CAS boundary. An
        # unrelated preexisting session would defer its fresh exit rehash
        # beyond this frame and could let a successful response escape first.
        if self.artifact_store._read_session.get() is not None:
            raise SourceRegistryError('CANONICAL_FRAME_CAS_BOUNDARY_REQUIRED',
                'A fresh request frame requires its own complete actual-CAS exit boundary')
        identity = software_guard()
        frame = {'files': {}, 'values': {}, 'pending': {}, 'guard': software_guard,
                 'softwareIdentity': identity, 'softwareBytes': _serialize(identity),
                 'locations': {}, 'normalLocations': {}, 'familyContext': None,
                 'normalMembers': {}, 'indexMetadata': {}, 'corpusDigests': {}}
        token = self._validation_frame.set(frame)
        try:
            with self.artifact_store.validation_session():
                yield
                for members, expected in frame['normalMembers'].values():
                    if self._json_sha(members) != expected:
                        raise SourceRegistryError('NORMAL_OBSERVED_INDEX_PARENT_MISMATCH',
                            'Normal index parents changed during this operation')
                for chunks, expected, digest in frame['corpusDigests'].values():
                    if digest(chunks) != expected:
                        raise SourceRegistryError('CANONICAL_FRAME_CORPUS_CHANGED',
                            'The indexed corpus changed during this operation')
                self._check_family_context(frame)
            # The existing session has now rehashed every complete actual CAS
            # file. Recheck roots/aliases/software after that final I/O too.
            self._check_frame_files(frame)
            for record in frame['indexMetadata'].values():
                if record['identity']() != record['expected']:
                    raise SourceRegistryError('INDEX_STATUS_METADATA_CHANGED',
                        'Persisted index metadata changed during this operation')
            self._check_family_context(frame)
            if _serialize(software_guard()) != frame['softwareBytes']:
                raise SourceRegistryError('CANONICAL_FRAME_SOFTWARE_CHANGED',
                    'Imported runtime software identity changed during this request')
            self._derived_commit(frame['pending'])
        finally:
            self._validation_frame.reset(token)
            frame['values'].clear()
            frame['pending'].clear()
            frame['files'].clear()
            frame['locations'].clear()
            frame['normalLocations'].clear()
            frame['normalMembers'].clear()
            frame['indexMetadata'].clear()
            frame['corpusDigests'].clear()

    def _revision_path(self, revision):
        path = super()._revision_path(revision)
        self._pin_frame_file(path)
        return path

    @staticmethod
    def _object(value):
        if not isinstance(value, dict):
            raise SourceRegistryError('CANONICAL_SCHEMA_UNSUPPORTED', 'Canonical snapshot must be a JSON object')

    def _wire(self, address):
        try:
            path = self._path(address)
            raw = self._read_frame_file(path, normal=None)
            try:
                wire = _strict_json(raw)
            except (UnicodeError, ValueError):
                if len(raw) > MAX_SNAPSHOT_BYTES:
                    raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE', 'Public root exceeds the fixed 16MiB maximum')
                raise
            maximum = self.normal_maximum_bytes if normal_observed_candidate(wire) else MAX_SNAPSHOT_BYTES
            if len(raw) > maximum:
                raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE',
                    'Normal public root exceeds its configured maximum' if normal_observed_candidate(wire)
                    else 'Public root exceeds the fixed 16MiB maximum')
            self._pin_frame_file(path, raw, normal=normal_observed_candidate(wire))
        except (OSError, UnicodeError, ValueError) as error:
            if isinstance(error, SourceRegistryError):
                raise
            raise SourceRegistryError('SNAPSHOT_UNAVAILABLE', 'Canonical snapshot is missing or unreadable') from error
        self._object(wire)
        if wire.get('projectionSha256') != address:
            raise SourceRegistryError('SNAPSHOT_IDENTITY_MISMATCH', 'Stored revision does not match its address')
        return wire

    def publish(self, projection):
        self._object(projection)
        maximum = self.normal_maximum_bytes if normal_observed_candidate(projection) else MAX_SNAPSHOT_BYTES
        if sum(len(part) for part in _canonical_chunks(projection)) > maximum:
            raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE',
                'Normal public root exceeds its configured maximum' if normal_observed_candidate(projection)
                else 'Public root exceeds the fixed 16MiB maximum')
        if normal_observed_candidate(projection):
            receipt = CanonicalSourceRegistry.publish(self, projection)
            alias = self._normal_revision_path(projection['projectionRevisionSha256'])
            alias.parent.mkdir(parents=True, exist_ok=True)
            try:
                os.link(self._path(projection['projectionSha256']), alias)
            except FileExistsError:
                if alias.read_bytes() != self._path(projection['projectionSha256']).read_bytes():
                    raise SourceRegistryError('NORMAL_OBSERVED_REVISION_CONFLICT',
                        'Normal source revision already refers to different exact bytes')
            return receipt
        # Family's nested native references cannot fall through to a child gate.
        if source_family_candidate(projection):
            receipt = CanonicalSourceRegistry.publish(self, projection)
            alias = self._family_revision_path(projection['projectionRevisionSha256'])
            alias.parent.mkdir(parents=True, exist_ok=True)
            try:
                os.link(self._path(projection['projectionSha256']), alias)
            except FileExistsError:
                if alias.read_bytes() != self._path(projection['projectionSha256']).read_bytes():
                    raise SourceRegistryError('SOURCE_FAMILY_REVISION_CONFLICT',
                        'Family revision already refers to different exact bytes')
            return receipt
        # Independent PDF markers forbid fallback to the collection/DOCX/scan gates.
        if _pdf_candidate(projection):
            return self.pdf_registry.publish(projection)
        return super().publish(projection)

    def verify_normal_publication(self, projection):
        """Recheck exact published bytes without rebuilding the observed graph."""
        if not normal_observed_candidate(projection):
            raise SourceRegistryError('NORMAL_OBSERVED_SCHEMA_INVALID',
                'Publication readback requires a normal observed projection')
        try:
            digest, size = hashlib.sha256(), 0
            for chunk in _canonical_chunks(projection):
                digest.update(chunk)
                size += len(chunk)
            address = projection['projectionSha256']
            revision = projection['projectionRevisionSha256']
            dependencies = []
            for path in (self._path(address), self._normal_revision_path(revision, pin=False)):
                dependencies.append({'path': str(path.absolute()), 'resolvedPath': str(path.resolve()),
                    'byteCount': size, 'sha256': digest.hexdigest(), 'normal': True})
            self.verify_prepared_read_dependencies(dependencies, [projection['observationManifestRef']])
        except RuntimeError as error:
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                'Published root or revision binding cannot be resolved') from error

    def load(self, address):
        frame = self._validation_frame.get()
        if frame is not None:
            with self._capture_file_dependencies() as root_files:
                wire = self._wire(address)
            return self._load_framed(address, wire, root_files)
        with self.artifact_store.validation_session():
            return self._load_current(address)

    def _load_framed(self, address, wire, root_files):
        frame = self._validation_frame.get()
        policy_bound = source_family_candidate(wire) or normal_observed_candidate(wire)
        family_context = self._pin_family_context(frame) if policy_bound else b''
        root_sha = frame['files'][str(self._path(address).resolve())][1]
        key = (address.encode(), root_sha.encode(), frame['softwareBytes'], family_context)
        if address not in frame['values']:
            result = self._derived_get(key)
            if result is not None:
                if result.normal_revision:
                    self.verify_prepared_read_dependencies([json.loads(raw) for raw in result.file_dependencies],
                        [json.loads(raw) for raw in result.dependencies])
                else:
                    self._fresh_dependencies(result)
            else:
                with self._capture_file_dependencies() as files, self.artifact_store.capture_read_dependencies() as dependencies:
                    full = self._load_current(address, wire=wire)
                    collection = wire.get('schema') == COLLECTION_SCHEMA
                    serialized = _serialize(full) if collection else b''
                captured = tuple(_serialize(descriptor) for descriptor in dependencies.values())
                files = {**root_files, **files}
                captured_files = tuple(_serialize(files[path]) for path in sorted(files))
                compact = self._normal_record(wire) if normal_observed_candidate(wire) else b''
                retained_bytes = len(serialized) + len(compact) + sum(map(len, captured)) + sum(map(len, captured_files)) + sum(map(len, key))
                result = _DerivedResult(serialized, captured, captured_files,
                    collection, retained_bytes, compact, wire['projectionRevisionSha256'] if compact else '')
                frame['pending'][key] = result
                self.artifact_store.release_bytes(dependencies.values())
            frame['values'][address] = result
        result = frame['values'][address]
        return json.loads(result.serialized) if result.collection else wire

    def _normal_record(self, wire):
        # No reading text, layout tree, table body or manifest body is retained.
        scope_fields = ('id', 'schema', 'documentId', 'fileKey', 'sourceHash', 'sourceClass',
            'documentClass', 'sourcePurpose', 'eligibleModules', 'category', 'originalSourceSha256',
            'sourceSha256', 'originalHashMeaning', 'producerKind', 'physicalPageNo', 'pageNo')
        part_fields = ('physicalPageNo', 'pageNo', 'originalSourceSha256', 'sourceSha256',
            'originalHashMeaning', 'sourceHash', 'documentId', 'fileKey')
        chunks = []
        for chunk in wire['chunks']:
            row = {key: chunk[key] for key in scope_fields if key in chunk}
            if 'parserEvidence' in chunk:
                candidate = chunk['parserEvidence']
                row['parserEvidence'] = {'schema': candidate.get('schema')} if isinstance(candidate, dict) else candidate
            if 'parts' in chunk:
                row['parts'] = [{key: part[key] for key in part_fields if key in part} for part in chunk['parts']]
            chunks.append(row)
        from source_read_server import _source
        return _serialize({'schema': NORMAL_SCHEMA, 'projectionSha256': wire['projectionSha256'],
            'projectionIdentity': wire['projectionIdentity'], 'producerKind': wire.get('producerKind'),
            'projectionRevisionSha256': wire['projectionRevisionSha256'],
            'manifest': {'sourceBinding': wire['manifest']['sourceBinding']},
            'sourceHash': wire['sourceHash'], 'source': wire['source'], 'chunks': chunks,
            'parentSha256': self._json_sha(wire['chunks']), 'snapshotSha256': self._json_sha(wire),
            'sourceSummary': _source(wire['projectionSha256'], wire, wire['chunks'])})

    def _normal_proof(self, *, address=None, revision=None):
        frame = self._validation_frame.get()
        if frame is None:
            locator = _strict_json(self._normal_revision_path(revision).read_bytes()) if revision else self.load(address)
            if not normal_observed_candidate(locator):
                return locator
            snapshot = self.load(locator['projectionSha256'])
            if self._json_sha(locator) != self._json_sha(snapshot):
                raise SourceRegistryError('NORMAL_OBSERVED_INDEX_PARENT_MISMATCH', 'Normal revision differs from its root')
            return _DerivedResult(b'', (), (), False, 0, self._normal_record(snapshot), snapshot['projectionRevisionSha256'])
        context = self._pin_family_context(frame)
        result = frame['values'].get(address) if address else next(
            (value for value in frame['values'].values() if value.normal_revision == revision), None)
        if result is None:
            with self._derived_lock:
                candidates = list(self._derived_results.items())
            for key, candidate in reversed(candidates):
                if (candidate.normal_revision and key[2:] == (frame['softwareBytes'], context)
                        and (key[0] == address.encode() if address else candidate.normal_revision == revision)):
                    result = candidate
                    self.verify_prepared_read_dependencies([json.loads(raw) for raw in result.file_dependencies],
                        [json.loads(raw) for raw in result.dependencies])
                    address = key[0].decode()
                    frame['values'][address] = result
                    break
        if result is None:
            if address:
                wire = self.load(address)
                if not normal_observed_candidate(wire):
                    return wire
            else:
                with self._capture_file_dependencies() as root_files:
                    alias = self._normal_revision_path(revision, pin=False)
                    raw = self._read_frame_file(alias, normal=True)
                    wire = _strict_json(raw)
                    self._object(wire)
                    if not normal_observed_candidate(wire) or wire.get('projectionRevisionSha256') != revision:
                        raise SourceRegistryError('NORMAL_OBSERVED_INDEX_PARENT_MISMATCH', 'Normal revision identity differs')
                    address = _identity(wire.get('projectionSha256'), 'projectionSha256')
                    self._pin_frame_file(alias, raw, normal=True)
                    root = self._path(address)
                    actual = self._stream_file_identity(root, maximum=self.normal_maximum_bytes)
                    if actual[1:] == (len(raw), hashlib.sha256(raw).hexdigest()):
                        self._pin_stream_file(root, actual, normal=True)
                    else:
                        # Legacy noncanonical whitespace keeps the conservative semantic comparison.
                        current = self._wire(address)
                        if self._json_sha(current) != self._json_sha(wire):
                            raise SourceRegistryError('NORMAL_OBSERVED_INDEX_PARENT_MISMATCH', 'Normal alias differs from its root')
                        wire = current
                self._load_framed(address, wire, root_files)
            result = frame['values'][address]
        if not result.normal_revision:
            return None
        compact = json.loads(result.conditional_wire)
        address = compact['projectionSha256']
        alias = self._normal_revision_path(result.normal_revision, pin=False)
        locator = str(alias.absolute())
        if locator not in frame['locations']:
            actual = self._stream_file_identity(alias, maximum=self.normal_maximum_bytes)
            root = frame['locations'][str(self._path(address).absolute())]
            if actual[1:] != root[1:]:
                raw = self._read_frame_file(alias, normal=True)
                if self._json_sha(_strict_json(raw)) != compact['snapshotSha256']:
                    raise SourceRegistryError('NORMAL_OBSERVED_INDEX_PARENT_MISMATCH', 'Normal alias differs from its root')
            dependency = {'path': locator, 'resolvedPath': actual[0], 'byteCount': actual[1], 'sha256': actual[2], 'normal': True}
            self._pin_stream_file(alias, actual, normal=True)
            encoded = _serialize(dependency)
            if encoded not in result.file_dependencies:
                result = result._replace(file_dependencies=(*result.file_dependencies, encoded),
                    retained_bytes=result.retained_bytes + len(encoded))
                frame['values'][address] = result
                key = (address.encode(), root[2].encode(), frame['softwareBytes'], context)
                frame['pending'][key] = result
        return result

    def source_metadata(self, address, *, module):
        """Return a compact normal catalog with fresh complete-byte ownership."""
        result = self._normal_proof(address=address)
        snapshot = (json.loads(result.conditional_wire) if isinstance(result, _DerivedResult)
                    else result if isinstance(result, dict) else self.load(address))
        self._module(snapshot, module)
        return snapshot

    def validate_index_scope(self, chunks, addresses, *, module, sorted_order=False):
        """Bind resident index parents to exact selected roots without copying those roots."""
        from oer_server import assert_canonical_scope, canonical_scope_policy_sha
        by_id = {chunk['id']: chunk for chunk in chunks}
        expected = []
        if len(by_id) != len(chunks):
            raise SourceRegistryError('INDEX_STATUS_CURRENT_SOURCE_MISMATCH', 'Persisted parent IDs are ambiguous')
        for address in addresses:
            source = self.source_metadata(address, module=module)
            if canonical_scope_policy_sha(source) != self.family_policy.policyIdentity['manifestSha256']:
                raise SourceRegistryError('PROJECTION_SCOPE_POLICY_MISMATCH', 'Current sources bind a different scope policy')
            assert_canonical_scope(source['chunks'], self.family_policy, module=module)
            expected.extend(chunk['id'] for chunk in source['chunks'])
            if not normal_observed_candidate(source):
                if any(by_id.get(chunk['id']) != chunk for chunk in source['chunks']):
                    raise SourceRegistryError('INDEX_STATUS_CURRENT_SOURCE_MISMATCH', 'Persisted legacy source parents differ')
        if sorted_order:
            expected.sort()
        if expected != [chunk['id'] for chunk in chunks]:
            raise SourceRegistryError('INDEX_STATUS_CURRENT_SOURCE_MISMATCH', 'Persisted source order or selected scope differs')
        self.validate_native_chunks(chunks)

    def _load_current(self, address, *, wire=None):
        wire = self._wire(address) if wire is None else wire
        if normal_observed_candidate(wire):
            self._validate_normal(wire)
            return wire
        if source_family_candidate(wire):
            self._validate_family(wire)
            return wire
        if _pdf_candidate(wire):
            self.pdf_registry.validator(wire)
            return wire
        if wire.get('schema') == COLLECTION_SCHEMA:
            return hydrate_native_collection(wire, artifact_store=self.artifact_store)
        self.validator(wire)
        return wire

    def wire(self, address, *, module='vetting'):
        with self._capture_file_dependencies() as root_files:
            wire = self._wire(address)
        if normal_observed_candidate(wire):
            path = self._normal_revision_path(wire['projectionRevisionSha256'], pin=False)
            alias = self._stream_file_identity(path, maximum=self.normal_maximum_bytes)
            digest, size = hashlib.sha256(), 0
            for part in _canonical_chunks(wire):
                digest.update(part)
                size += len(part)
            if alias[1:] != (size, digest.hexdigest()):
                raise SourceRegistryError('NORMAL_OBSERVED_REVISION_MISMATCH',
                    'Normal wire must retain its exact registered observation revision')
            self._pin_stream_file(path, alias, normal=True)
        if source_family_candidate(wire):
            try:
                alias = self._read_frame_file(self._family_revision_path(wire['projectionRevisionSha256']))
                if len(alias) > MAX_SNAPSHOT_BYTES or alias != _serialize(wire):
                    raise SourceRegistryError('SOURCE_FAMILY_REVISION_MISMATCH',
                        'Family wire must retain its exact registered derived revision')
            except (OSError, KeyError, TypeError) as error:
                raise SourceRegistryError('SOURCE_FAMILY_REVISION_UNAVAILABLE',
                    'Exact family derived revision is unavailable') from error
        full = (self._load_framed(address, wire, root_files) if self._validation_frame.get() is not None
                else self._load_current(address, wire=wire))
        self._module(full, module)
        if normal_observed_candidate(wire):
            self._remember_normal_wire_alias(address, wire)
        return wire, full['chunks']

    def _remember_normal_wire_alias(self, address, wire):
        frame = self._validation_frame.get()
        if frame is None:
            return
        root = frame['files'][str(self._path(address).resolve())]
        alias = self._normal_revision_path(wire['projectionRevisionSha256'], pin=False)
        locator = str(alias.absolute())
        location = frame['locations'][locator]
        # The full wire path proved canonical alias bytes; require exact root identity too.
        if root != location[1:]:
            return
        result = frame['values'][address]
        dependency = _serialize({'path': locator, 'resolvedPath': location[0],
            'byteCount': root[0], 'sha256': root[1], 'normal': True})
        added = dependency not in result.file_dependencies
        result = result._replace(file_dependencies=(*result.file_dependencies, dependency) if added else result.file_dependencies,
            canonical_wire=True, retained_bytes=result.retained_bytes + (len(dependency) if added else 0))
        frame['values'][address] = result
        key = (address.encode(), root[1].encode(), frame['softwareBytes'], frame['familyContext'])
        frame['pending'][key] = result

    def normal_conditional_wire(self, address, *, module, if_none_match):
        """Use only a committed canonical-wire proof, with fresh actual byte guards."""
        frame = self._validation_frame.get()
        if frame is None:
            return None
        if (not isinstance(if_none_match, str) or len(if_none_match) != 66
                or if_none_match[0] != '"' or if_none_match[-1] != '"'
                or any(value not in '0123456789abcdef' for value in if_none_match[1:-1])):
            return None
        context = self._pin_family_context(frame)
        key = (address.encode(), if_none_match[1:-1].encode(), frame['softwareBytes'], context)
        result = self._derived_get(key)
        if result is None or result.collection or not result.canonical_wire:
            return None
        wire = json.loads(result.conditional_wire)
        files = [json.loads(raw) for raw in result.file_dependencies]
        paths = (self._path(address), self._normal_revision_path(wire['projectionRevisionSha256'], pin=False))
        for path in paths:
            if not any(item['path'] == str(path.absolute()) and item.get('normal') is True
                       and item['sha256'] == if_none_match[1:-1] for item in files):
                return None
        self.verify_prepared_read_dependencies(files, [json.loads(raw) for raw in result.dependencies])
        self._module(wire, module)
        return wire, wire['chunks'], if_none_match

    def validate_native_chunks(self, chunks):
        # Dispatch family before its nested child-PDF markers. Each old source
        # kind keeps its exact existing complete-parent proof gate.
        return self._validate_family_and_native_chunks(chunks)
