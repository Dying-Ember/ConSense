"""Bounded source reads over the registered, freshly verified OER sources.

SourceUnits owns the only structural closure algorithm. This transport does
not certify source quality, template observations or normal Word legal scope.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
from collections import OrderedDict
from threading import RLock
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

from oer_server import assert_canonical_scope, canonical_scope_policy_sha
from oer_source_registry import _serialize
from retrieval_source_units import POLICY, SEMANTIC_VERSION, SourceUnits, semantic_member, semantic_unit, semantic_value
from token_windows import WindowError, fail


ENDPOINT = '/sources/read'
CONTRACT = 'source-bound-demand-read-v1'
MAX_BYTES = 16 * 1024 * 1024
Sha = Annotated[str, Field(min_length=64, max_length=64, pattern=r'^[0-9a-f]{64}$')]
OriginId = Annotated[str, Field(min_length=1, max_length=2048)]
LIMITATIONS = {
    'templateObservations': 'unsupported; guidance/marker/group/condition/native-target observation IDs are not canonical chunk IDs',
    'normalWordScope': 'unknown; selectedWordScopes/wordSourceScopeContext conditions, guidance and branches are not equivalent to SourceUnits',
    'semanticScopeVerified': False,
    'qualifiersComplete': 'unknown',
    'catalogInternalRead': 'existing registry validates complete public roots and actual CAS; compact metadata-only internal reads are not yet supported',
}
SEMANTIC_LIMITATIONS = {**LIMITATIONS,
    'catalogInternalRead': 'prepared manifest metadata only; no source graph construction',
    'actualByteRead': 'complete source root and CAS dependencies are streamed at entry and successful exit; source I/O is not eliminated',
    'preparation': 'explicit prepare performs one cold full source validation and SourceUnits resolution; old seals are unchanged',
    'normalWordScope': 'unknown; Java selectedWordScopes/wordSourceScopeContext remains separately required for conditions, guidance and branches',
    'semanticProjection': 'exact text and observed semantic declarations; native layout/proof remains addressable by evidenceRef; legal completeness unknown'}
CAPABILITY = {
    'contract': CONTRACT, 'version': CONTRACT, 'endpoint': ENDPOINT,
    'operations': ['catalog', 'ids', 'scan', 'prepare'], 'views': ['canonical_units', 'semantic_units'],
    'semanticProjectionVersion': SEMANTIC_VERSION,
    'semanticLimitations': SEMANTIC_LIMITATIONS,
    'semanticPreparation': {'operation': 'prepare', 'view': 'semantic_units',
        'subsequentRequestFields': ['readManifestSha256', 'readManifestToken'],
        'binding': 'process_signed_exact_sources_manifests_module_policy_software',
        'coldPreparation': 'explicit_full_validation_on_first_preparation_per_source_scope_and_process',
        'repeatedPreparation': 'bounded_derived_addresses_only_fresh_actual_byte_validation',
        'graphReloadOnPreparedRead': False, 'actualBytesStillStreamed': True},
    'sourceUnitPolicy': POLICY, 'maximumResponseBytes': MAX_BYTES,
    'maximumItems': 256, 'originOrder': 'request_projection_order_then_registered_source_order',
    'scanCoverage': 'every_canonical_origin_once; related_members_may_repeat',
    'cursorBinding': 'source_revisions_order_module_policy_software_signed_per_process',
    'sourceValidation': 'fresh_registry_request_frame_entry_and_exit',
    'limitations': LIMITATIONS,
}


class SourceReadRequest(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    module: Literal['drafting', 'vetting', 'advisory'] = 'vetting'
    projectionSha256: list[Sha] = Field(min_length=1, max_length=10000)
    operation: Literal['catalog', 'ids', 'scan', 'prepare']
    originIds: list[OriginId] = Field(default_factory=list, max_length=256)
    cursor: Annotated[str, Field(min_length=1, max_length=8192)] | None = None
    maxItems: int = Field(default=64, ge=1, le=256)
    maxBytes: int = Field(default=4194304, ge=1024, le=MAX_BYTES)
    view: Annotated[str, Field(min_length=1, max_length=128)] = 'canonical_units'
    readManifestSha256: list[Sha] = Field(default_factory=list, max_length=10000)
    readManifestToken: Annotated[str, Field(min_length=64, max_length=64, pattern=r'^[0-9a-f]{64}$')] | None = None


def _unique(chunks, key):
    values = {chunk.get(key) for chunk in chunks}
    return next(iter(values)) if len(values) == 1 else None


def _source(address, snapshot, chunks):
    identity, source = snapshot.get('projectionIdentity', {}), snapshot.get('source', {})
    return {'projectionSha256': address,
        'documentId': identity.get('documentId', source.get('documentId', _unique(chunks, 'documentId'))),
        'originalSourceSha256': identity.get('originalSourceSha256', source.get('sourceSha256', _unique(chunks, 'originalSourceSha256'))),
        'sourceHash': snapshot.get('sourceHash', _unique(chunks, 'sourceHash')),
        'javaParsedSourceHash': identity.get('javaParsedSourceHash', source.get('parsedSourceHash')),
        'sourceRevisionSha256': identity.get('sourceRevisionSha256', source.get('sourceRevisionSha256', _unique(chunks, 'sourceRevisionSha256'))),
        'projectionRevisionSha256': snapshot.get('projectionRevisionSha256', _unique(chunks, 'projectionRevisionSha256')),
        'chunkCount': len(chunks), 'producerKind': snapshot.get('producerKind'),
        'nonRetrievableUnitCount': len(snapshot.get('nonRetrievableUnits', [])),
        'sourceUnknownCount': len(snapshot.get('unknowns', [])),
        'sourceQualityStatus': _unique([chunk.get('sourceQuality', {}) for chunk in chunks], 'status'),
        'sourceQualityVerified': False}


def install_source_read_route(app, registry, policy, *, target_module, software_guard):
    """Install the production route into the same registry/runtime composition."""
    from fastapi import HTTPException
    secret = os.urandom(32)
    preparation_lock = RLock()
    # Pure derived addresses only; no source tree or validity verdict survives.
    prepared_addresses = OrderedDict()

    def cursor(scope_sha, offset):
        raw = _serialize({'scopeSha256': scope_sha, 'offset': offset})
        signature = hmac.new(secret, raw, hashlib.sha256).hexdigest()
        return base64.urlsafe_b64encode(raw).decode('ascii') + '.' + signature

    def offset(token, scope_sha, count):
        if token is None:
            return 0
        try:
            encoded, signature = token.split('.')
            raw = base64.b64decode(encoded, altchars=b'-_', validate=True)
            fail(hmac.compare_digest(signature, hmac.new(secret, raw, hashlib.sha256).hexdigest()),
                 'SOURCE_READ_CURSOR_INVALID', 'Cursor signature does not match this OER process')
            value = json.loads(raw)
            fail(set(value) == {'scopeSha256', 'offset'} and value['scopeSha256'] == scope_sha,
                 'SOURCE_READ_CURSOR_SCOPE_MISMATCH', 'Cursor belongs to different source revisions, order, module, policy or software')
            index = value['offset']
            fail(type(index) is int and 0 <= index < count,
                 'SOURCE_READ_CURSOR_INVALID', 'Cursor is outside the registered source order')
            return index
        except WindowError:
            raise
        except (ValueError, TypeError, KeyError, UnicodeError) as error:
            raise WindowError('SOURCE_READ_CURSOR_INVALID', 'Cursor must be an intact server-issued token') from error

    def software_sha():
        frame = registry._validation_frame.get()
        # The owner already proved the current imported software at entry and
        # will check its bytes again after the actual-CAS exit rehash.
        raw = frame['softwareBytes'] if frame is not None else _serialize(software_guard())
        return hashlib.sha256(raw).hexdigest()

    def preparation_token(request, addresses):
        binding = {'projectionSha256': request.projectionSha256, 'readManifestSha256': addresses,
            'module': request.module, 'scopePolicySha256': policy.policyIdentity['manifestSha256'],
            'softwareIdentitySha256': software_sha(), 'semanticProjectionVersion': SEMANTIC_VERSION}
        return hmac.new(secret, _serialize(binding), hashlib.sha256).hexdigest()

    def cas_json(address, maximum):
        # stat supplies only a finite allocation bound, never a validity verdict.
        path = registry.artifact_store.root / 'sha256' / address[:2] / (address + '.blob')
        size = path.stat().st_size
        fail(size <= maximum, 'SOURCE_READ_MANIFEST_TOO_LARGE', 'Prepared read metadata exceeds its aggregate finite bound')
        descriptor = {'schema': 'consense.artifact-descriptor.v1', 'storage': 'content_addressed_disk',
            'sha256': address, 'byteCount': size, 'relativePath': 'sha256/' + address[:2] + '/' + address + '.blob',
            'identityScope': 'prepared_source_semantic_read'}
        from native_observation import _strict_json
        return _strict_json(registry.artifact_store.read_asset(descriptor)), size

    def prepare(request):
        # Coalesce simultaneous preparation of the same immutable scope; the
        # source/CAS/software proof still belongs to each individual request.
        with preparation_lock:
            return prepare_locked(request)

    def prepare_locked(request):
        fail(not request.readManifestSha256 and request.readManifestToken is None,
             'SOURCE_READ_OPERATION_INVALID', 'prepare does not accept an existing manifest binding')
        preparation_key = preparation_token(request, [])
        if preparation_key in prepared_addresses:
            manifests = prepared_addresses[preparation_key]
            catalog = semantic_read(request.model_copy(update={'operation': 'catalog',
                'readManifestSha256': manifests, 'readManifestToken': preparation_token(request, manifests),
                'maxBytes': MAX_BYTES}))
            prepared_addresses.move_to_end(preparation_key)
            result = {key: value for key, value in catalog.items() if key not in ('scopeIdentity', 'nextCursor')}
            result.update(operation='prepare', coverage={'status': 'prepared',
                'totalOrigins': catalog['coverage']['totalOrigins']},
                coldPreparation={'performed': False, 'fullGraphValidation': False,
                    'closureAlgorithm': POLICY, 'originalSealsChanged': False})
            fail(len(_serialize(result)) <= request.maxBytes, 'SOURCE_READ_BUDGET_EXCEEDED',
                 'Preparation receipt exceeds maxBytes', maxBytes=request.maxBytes)
            return result
        manifests, sources, origin_ids = [], [], []
        with registry.validation_frame(software_guard=software_guard):
            software = software_sha()
            documents = set()
            for address in request.projectionSha256:
                with registry._capture_file_dependencies() as files, registry.artifact_store.capture_read_dependencies() as assets:
                    snapshot = registry.load(address)
                fail(canonical_scope_policy_sha(snapshot) == policy.policyIdentity['manifestSha256'],
                     'PROJECTION_SCOPE_POLICY_MISMATCH', 'Source was prepared against another scope policy')
                registry._module(snapshot, request.module)
                parents = snapshot['chunks']
                assert_canonical_scope(parents, policy, module=request.module)
                source = _source(address, snapshot, parents)
                fail(source['documentId'] not in documents, 'SOURCE_READ_AMBIGUOUS_DOCUMENT',
                     'Prepare one exact revision per document in a semantic read scope')
                documents.add(source['documentId'])
                catalog, entries = SourceUnits(parents), []
                projected_members, referenced_trees = {}, {}
                def member(parent):
                    if parent['id'] not in projected_members:
                        projected = semantic_member(parent, address)
                        projected['sourceContext'] = semantic_value({
                            'unknowns': [row for row in snapshot.get('unknowns', [])
                                if not isinstance(row, dict) or row.get('unitId') in (None, parent.get('unitId'))],
                            'limitations': snapshot.get('limitations', []),
                            'visualReturnStatus': snapshot.get('visualReturnStatus'),
                            'sourceQualityVerified': False,
                            'nonRetrievableUnitCount': len(snapshot.get('nonRetrievableUnits', []))})
                        reference = parent.get('nativeTableGroupsRef')
                        if reference is not None:
                            # Native PDF public chunks deliberately externalize
                            # complete tables. A summary is insufficient for
                            # semantic review; resolve their existing sealed
                            # pointer once at explicit cold preparation.
                            descriptor = reference['artifactDescriptor']
                            key = descriptor['sha256']
                            if key not in referenced_trees:
                                from native_observation import _strict_json
                                referenced_trees[key] = _strict_json(registry.artifact_store.read_asset(descriptor))
                            assets[(str(registry.artifact_store._path(descriptor)), descriptor['sha256'],
                                    descriptor['byteCount'])] = descriptor
                            value = referenced_trees[key]
                            pointer = reference['jsonPointer']
                            fail(pointer == '' or pointer.startswith('/'), 'SOURCE_READ_SEMANTIC_REFERENCE_INVALID',
                                 'A semantic table reference must use its sealed JSON pointer')
                            for part in pointer.split('/')[1:] if pointer else []:
                                part = part.replace('~1', '/').replace('~0', '~')
                                value = value[int(part)] if isinstance(value, list) else value[part]
                            fail(hashlib.sha256(_serialize(value)).hexdigest() == reference['payloadSha256'],
                                 'SOURCE_READ_SEMANTIC_REFERENCE_INVALID', 'Table payload differs from its sealed reference')
                            projected['nativeTables'] = semantic_value(value)
                        projected_members[parent['id']] = projected
                    return projected_members[parent['id']]
                for parent in parents:
                    record = {'origin': member(parent),
                              'unit': semantic_unit(catalog.resolve(parent), address, member_projector=member)}
                    raw = _serialize(record)
                    descriptor = registry.artifact_store.put_bytes(raw,
                        identity_scope='complete_source_semantic_unit_with_closure')
                    entries.append({'id': parent['id'], 'unitId': record['unit']['unitId'],
                        'recordRef': descriptor})
                manifest = {'schema': 'consense.source-semantic-read-manifest.v1',
                    'semanticProjectionVersion': SEMANTIC_VERSION, 'sourceUnitPolicy': POLICY,
                    'projectionSha256': address, 'module': request.module,
                    'scopePolicySha256': policy.policyIdentity['manifestSha256'],
                    'softwareIdentitySha256': software, 'source': source,
                    'origins': entries, 'fileDependencies': list(files.values()),
                    'casDependencies': list(assets.values()), 'sourceQualityVerified': False}
                raw = _serialize(manifest)
                fail(len(raw) <= MAX_BYTES, 'SOURCE_READ_MANIFEST_TOO_LARGE',
                     'Prepared source metadata exceeds its finite bound')
                descriptor = registry.artifact_store.put_bytes(raw, identity_scope='source_semantic_read_manifest')
                manifests.append(descriptor['sha256']); sources.append(source)
                origin_ids.extend(entry['id'] for entry in entries)
            fail(len(set(origin_ids)) == len(origin_ids), 'SOURCE_READ_AMBIGUOUS_ORIGIN',
                 'Origin IDs must be unique across the prepared sources')
        # A manifest is published to a caller only after all actual source,
        # policy and imported-software exit guards have succeeded.
        result = {'contract': CONTRACT, 'view': request.view, 'operation': 'prepare', 'module': request.module,
            'projectionSha256': request.projectionSha256, 'semanticProjectionVersion': SEMANTIC_VERSION,
            'sourceUnitPolicy': POLICY, 'sources': sources, 'origins': [], 'units': [],
            'readManifestSha256': manifests, 'readManifestToken': preparation_token(request, manifests),
            'coverage': {'status': 'prepared', 'totalOrigins': len(origin_ids)},
            'coldPreparation': {'performed': True, 'fullGraphValidation': True,
                'closureAlgorithm': POLICY, 'originalSealsChanged': False},
            'limitations': SEMANTIC_LIMITATIONS, 'sourceQualityVerified': False}
        fail(len(_serialize(result)) <= request.maxBytes, 'SOURCE_READ_BUDGET_EXCEEDED',
             'Preparation receipt exceeds maxBytes', maxBytes=request.maxBytes)
        prepared_addresses[preparation_key] = manifests
        while len(prepared_addresses) > 128 or sum(len(key) + len(_serialize(value))
                for key, value in prepared_addresses.items()) > 4 * 1024 * 1024:
            prepared_addresses.popitem(last=False)
        return result

    def semantic_read(request):
        if request.operation == 'prepare':
            return prepare(request)
        fail(len(request.readManifestSha256) == len(request.projectionSha256) and request.readManifestToken is not None,
             'SOURCE_READ_BOUNDED_UNAVAILABLE',
             'Explicit prepare is required before a bounded semantic read; no legacy full-graph fallback is performed')
        fail(hmac.compare_digest(request.readManifestToken, preparation_token(request, request.readManifestSha256)),
             'SOURCE_READ_PREPARATION_STALE', 'Preparation belongs to another process, scope, source order, policy or software')
        with registry.validation_frame(software_guard=software_guard):
            sources, entries, metadata_bytes = [], [], 0
            for address, prepared in zip(request.projectionSha256, request.readManifestSha256):
                manifest, size = cas_json(prepared, MAX_BYTES - metadata_bytes)
                metadata_bytes += size
                fail(manifest['schema'] == 'consense.source-semantic-read-manifest.v1'
                     and manifest['projectionSha256'] == address
                     and manifest['module'] == request.module
                     and manifest['scopePolicySha256'] == policy.policyIdentity['manifestSha256']
                     and manifest['softwareIdentitySha256'] == software_sha()
                     and manifest['sourceUnitPolicy'] == POLICY
                     and manifest['semanticProjectionVersion'] == SEMANTIC_VERSION,
                     'SOURCE_READ_PREPARATION_STALE', 'Prepared read identities do not match the current request')
                registry.verify_prepared_read_dependencies(manifest['fileDependencies'], manifest['casDependencies'])
                sources.append(manifest['source']); entries.extend(manifest['origins'])
            by_id = {entry['id']: entry for entry in entries}
            fail(len(by_id) == len(entries), 'SOURCE_READ_AMBIGUOUS_ORIGIN', 'Prepared origin IDs must be unique')
            scope = {'sources': sources, 'module': request.module, 'policyIdentity': policy.policyIdentity,
                'sourceUnitPolicy': POLICY, 'view': request.view, 'semanticProjectionVersion': SEMANTIC_VERSION,
                'readManifestSha256': request.readManifestSha256, 'softwareIdentitySha256': software_sha(),
                'originOrderSha256': hashlib.sha256(_serialize([entry['id'] for entry in entries])).hexdigest()}
            scope_sha = hashlib.sha256(_serialize(scope)).hexdigest()
            start = offset(request.cursor, scope_sha, len(entries))
            ledger = {'status': 'catalog' if request.operation == 'catalog' else 'complete',
                'totalOrigins': len(entries), 'requestedOriginIds': [], 'returnedOriginIds': [],
                'returnedOrigins': 0, 'processedOrigins': start, 'remainingOrigins': len(entries) - start}
            result = {'contract': CONTRACT, 'module': request.module, 'operation': request.operation,
                'view': request.view, 'projectionSha256': request.projectionSha256,
                'semanticProjectionVersion': SEMANTIC_VERSION, 'sourceUnitPolicy': POLICY,
                'scopeIdentity': {**scope, 'scopeSha256': scope_sha}, 'sources': sources,
                'readManifestSha256': request.readManifestSha256, 'readManifestToken': request.readManifestToken,
                'origins': [], 'units': [], 'nextCursor': None, 'coverage': ledger,
                'limitations': SEMANTIC_LIMITATIONS, 'sourceQualityVerified': False}
            if request.operation != 'catalog':
                fail(all(origin in by_id for origin in request.originIds), 'SOURCE_READ_ORIGIN_OUTSIDE_SCOPE',
                     'Every requested ID must belong to the prepared source scope')
                selected = ([by_id[origin] for origin in request.originIds] if request.operation == 'ids'
                            else entries[start:start + request.maxItems])
                if request.operation == 'ids':
                    ledger['requestedOriginIds'] = list(request.originIds)
                from native_observation import _strict_json
                for entry in selected:
                    descriptor = entry['recordRef']
                    required_bytes = descriptor['byteCount']
                    record = None
                    if required_bytes <= MAX_BYTES:
                        record = _strict_json(registry.artifact_store.read_asset(descriptor))
                        fail(record['origin']['id'] == entry['id'] and record['unit']['unitId'] == entry['unitId'],
                             'SOURCE_READ_PREPARATION_STALE', 'Prepared whole-unit record differs from its manifest')
                        result['origins'].append(record['origin']); result['units'].append(record['unit'])
                        ledger['returnedOriginIds'].append(entry['id']); ledger['returnedOrigins'] += 1
                        ledger['processedOrigins'] = start + ledger['returnedOrigins']
                        ledger['remainingOrigins'] = len(entries) - ledger['processedOrigins']
                        if request.operation == 'scan':
                            ledger['requestedOriginIds'].append(entry['id'])
                            result['nextCursor'] = cursor(scope_sha, ledger['processedOrigins']) if ledger['remainingOrigins'] else None
                            ledger['status'] = 'partial' if result['nextCursor'] else 'complete'
                        required_bytes = len(_serialize(result))
                    if record is not None and required_bytes <= request.maxBytes:
                        continue
                    fail(request.operation != 'ids', 'SOURCE_READ_BUDGET_EXCEEDED',
                         'Complete semantic units exceed maxBytes; no unit is truncated',
                         originId=entry['id'], requiredBytes=required_bytes, maxBytes=request.maxBytes)
                    if record is not None:
                        result['origins'].pop(); result['units'].pop()
                        ledger['returnedOriginIds'].pop(); ledger['requestedOriginIds'].pop()
                        ledger['returnedOrigins'] -= 1; ledger['processedOrigins'] -= 1; ledger['remainingOrigins'] += 1
                    result['nextCursor'] = cursor(scope_sha, ledger['processedOrigins'])
                    ledger['status'] = 'partial' if result['units'] else 'unit_too_large'
                    if not result['units']:
                        result['blockedOrigin'] = {'id': entry['id'], 'unitId': entry['unitId'],
                            'requiredBytes': required_bytes, 'maxBytes': request.maxBytes}
                    break
            fail(len(_serialize(result)) <= request.maxBytes, 'SOURCE_READ_BUDGET_EXCEEDED',
                 'Compact semantic catalog/coverage exceeds maxBytes', maxBytes=request.maxBytes)
            return result

    def read(request):
        try:
            fail(request.view in ('canonical_units', 'semantic_units'), 'SOURCE_READ_VIEW_UNSUPPORTED',
                 'Only canonical_units and semantic_units are implemented; complete normal Word scope remains unknown',
                 view=request.view, limitations=LIMITATIONS)
            fail(request.module == target_module, 'SOURCE_READ_TARGET_MODULE_MISMATCH',
                 'Use the OER process for the requested module')
            fail(len(set(request.projectionSha256)) == len(request.projectionSha256),
                 'DUPLICATE_CANONICAL_REVISION', 'Source addresses must be unique')
            fail(len(set(request.originIds)) == len(request.originIds),
                 'SOURCE_READ_DUPLICATE_ORIGIN', 'Each requested origin must appear only once')
            fail((request.operation == 'ids' and request.originIds and request.cursor is None
                  and len(request.originIds) <= request.maxItems)
                 or (request.operation != 'ids' and not request.originIds
                     and (request.operation == 'scan' or request.cursor is None)),
                 'SOURCE_READ_OPERATION_INVALID', 'ids needs bounded originIds without a cursor; catalog/scan do not accept originIds')
            if request.view == 'semantic_units':
                return semantic_read(request)
            fail(request.operation != 'prepare' and not request.readManifestSha256 and request.readManifestToken is None,
                 'SOURCE_READ_OPERATION_INVALID', 'Preparation bindings belong only to semantic_units')
            with registry.validation_frame(software_guard=software_guard):
                sources, parents = [], []
                for address in request.projectionSha256:
                    snapshot = registry.load(address)
                    fail(canonical_scope_policy_sha(snapshot) == policy.policyIdentity['manifestSha256'],
                         'PROJECTION_SCOPE_POLICY_MISMATCH', 'Source was prepared against another scope policy')
                    # Keep the existing module/eligibility gate and actual source order.
                    registry._module(snapshot, request.module)
                    chunks = snapshot['chunks']
                    sources.append(_source(address, snapshot, chunks))
                    parents.extend(chunks)
                assert_canonical_scope(parents, policy, module=request.module)
                fail(len({p['id'] for p in parents}) == len(parents),
                     'SOURCE_READ_AMBIGUOUS_ORIGIN', 'Origin IDs must be unique across the requested sources')
                scope = {'sources': sources, 'module': request.module, 'view': request.view, 'policyIdentity': policy.policyIdentity,
                         'sourceUnitPolicy': POLICY, 'softwareIdentitySha256': hashlib.sha256(_serialize(software_guard())).hexdigest(),
                         'originOrderSha256': hashlib.sha256(_serialize([p['id'] for p in parents])).hexdigest()}
                scope_sha = hashlib.sha256(_serialize(scope)).hexdigest()
                start = offset(request.cursor, scope_sha, len(parents))
                result = {'contract': CONTRACT, 'module': request.module, 'operation': request.operation,
                    'view': request.view, 'projectionSha256': request.projectionSha256,
                    'sourceUnitPolicy': POLICY, 'scopeIdentity': {**scope, 'scopeSha256': scope_sha},
                    'sources': sources, 'origins': [], 'units': [], 'nextCursor': None,
                    'coverage': {'status': 'catalog' if request.operation == 'catalog' else 'complete',
                        'totalOrigins': len(parents), 'requestedOriginIds': [], 'returnedOriginIds': [],
                        'returnedOrigins': 0, 'processedOrigins': start, 'remainingOrigins': len(parents) - start},
                    'limitations': LIMITATIONS, 'sourceQualityVerified': False}
                if request.operation != 'catalog':
                    catalog = SourceUnits(parents)
                    fail(all(origin in catalog.parents for origin in request.originIds),
                         'SOURCE_READ_ORIGIN_OUTSIDE_SCOPE', 'Every requested ID must belong to the exact registered source scope')
                    selected = ([catalog.parents[origin] for origin in request.originIds] if request.operation == 'ids'
                                else parents[start:start + request.maxItems])
                    if request.operation == 'ids':
                        result['coverage']['requestedOriginIds'] = list(request.originIds)
                    for seed in selected:
                        unit = catalog.resolve(seed)
                        result['origins'].append(seed)
                        result['units'].append(unit)
                        ledger = result['coverage']
                        ledger['returnedOriginIds'].append(seed['id'])
                        ledger['returnedOrigins'] += 1
                        ledger['processedOrigins'] = start + ledger['returnedOrigins']
                        ledger['remainingOrigins'] = len(parents) - ledger['processedOrigins']
                        if request.operation == 'scan':
                            ledger['requestedOriginIds'].append(seed['id'])
                            result['nextCursor'] = (cursor(scope_sha, ledger['processedOrigins']) if ledger['remainingOrigins'] else None)
                            ledger['status'] = 'partial' if result['nextCursor'] else 'complete'
                        required_bytes = len(_serialize(result))
                        if required_bytes <= request.maxBytes:
                            continue
                        fail(request.operation != 'ids', 'SOURCE_READ_BUDGET_EXCEEDED',
                             'The complete requested source units exceed maxBytes; request fewer IDs or a larger bounded budget',
                             originId=seed['id'], requiredBytes=required_bytes, maxBytes=request.maxBytes)
                        result['origins'].pop(); result['units'].pop()
                        ledger['returnedOriginIds'].pop(); ledger['requestedOriginIds'].pop()
                        ledger['returnedOrigins'] -= 1
                        ledger['processedOrigins'] -= 1; ledger['remainingOrigins'] += 1
                        result['nextCursor'] = cursor(scope_sha, ledger['processedOrigins'])
                        ledger['status'] = 'partial' if result['units'] else 'unit_too_large'
                        if not result['units']:
                            result['blockedOrigin'] = {'id': seed['id'], 'unitId': unit['unitId'],
                                'documentId': seed.get('documentId'), 'anchor': seed.get('anchor'),
                                'requiredBytes': required_bytes, 'maxBytes': request.maxBytes}
                        break
                fail(len(_serialize(result)) <= request.maxBytes, 'SOURCE_READ_BUDGET_EXCEEDED',
                     'The compact source catalog/coverage exceeds maxBytes; request fewer sources or a larger bounded budget',
                     maxBytes=request.maxBytes)
                return result
        except WindowError as error:
            raise HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error
        except (ValueError, OSError, KeyError, TypeError) as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', 'SOURCE_READ_UNAVAILABLE'),
                'message': str(error), 'sourceQualityChanged': False}) from error

    read.__annotations__['request'] = SourceReadRequest
    app.post(ENDPOINT)(app.state.request_evidence.wrap(ENDPOINT, read))
