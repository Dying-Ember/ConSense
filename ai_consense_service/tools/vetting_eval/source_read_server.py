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
import re
from collections import OrderedDict
from contextvars import copy_context
from pathlib import Path
from threading import RLock
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field
from fastapi import Header, Request
from fastapi.responses import StreamingResponse
from fastapi.routing import APIRoute
from starlette.concurrency import run_in_threadpool
from anyio import CancelScope
from oer_native_pdf_server import STRICT_REQUEST_JSON

from oer_server import assert_canonical_scope, canonical_scope_policy_sha
from oer_source_registry import _serialize
from retrieval_source_units import POLICY, SEMANTIC_VERSION, SourceUnits, semantic_member, semantic_unit, semantic_value, semantic_unit_template
from token_windows import WindowError, fail


ENDPOINT = '/sources/read'
CONTRACT = 'source-bound-demand-read-v1'
MAX_BYTES = 16 * 1024 * 1024
DICTIONARY_VERSION = 'source-semantic-dictionary-v2'
TEXT_FIRST_PROFILE = 'text-first-scan-v1'
STREAM_VERSION = 'source-semantic-stream-v1'
NORMAL_PROOF_VERSION = 'compact-normal-source-proof-v1'
INLINE_GROUP_BYTES = MAX_BYTES // 2
Sha = Annotated[str, Field(min_length=64, max_length=64, pattern=r'^[0-9a-f]{64}$')]
OriginId = Annotated[str, Field(min_length=1, max_length=2048)]
LIMITATIONS = {
    'templateObservations': 'unsupported; guidance/marker/group/condition/native-target observation IDs are not canonical chunk IDs',
    'normalWordScope': 'unknown; selectedWordScopes/wordSourceScopeContext conditions, guidance and branches are not equivalent to SourceUnits',
    'semanticScopeVerified': False,
    'qualifiersComplete': 'unknown',
    'catalogInternalRead': 'compact normal-source metadata with fresh complete-byte guards; legacy sources retain full validation',
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
    'semanticReadVersions': ['source-semantic-records-v1', DICTIONARY_VERSION],
    'semanticReadProfiles': ['full', TEXT_FIRST_PROFILE],
    'semanticScanStreamVersions': [STREAM_VERSION],
    'semanticLimitations': SEMANTIC_LIMITATIONS,
    'semanticPreparation': {'operation': 'prepare', 'view': 'semantic_units',
        'subsequentRequestFields': ['readManifestSha256', 'readManifestToken'],
        'binding': 'process_signed_exact_sources_manifests_module_policy_software',
        'coldPreparation': 'explicit_full_validation_on_first_preparation_per_source_scope_and_process',
        'repeatedPreparation': 'bounded_derived_addresses_only_fresh_actual_byte_validation',
        'graphReloadOnPreparedRead': False, 'actualBytesStillStreamed': True,
        'normalProofVersion': NORMAL_PROOF_VERSION},
    'sourceUnitPolicy': POLICY, 'maximumResponseBytes': MAX_BYTES,
    'maximumItems': 256, 'dictionaryScanMaximumItems': 1024,
    'originOrder': 'request_projection_order_then_registered_source_order',
    'scanCoverage': 'every_canonical_origin_once; related_members_may_repeat',
    'cursorBinding': 'source_revisions_order_module_policy_software_signed_per_process',
    'sourceValidation': 'fresh_registry_request_frame_entry_and_exit',
    'limitations': LIMITATIONS,
}


class _SourceReadRoute(APIRoute):
    def get_route_handler(self):
        original = super().get_route_handler()
        async def parsed_request(request):
            if STRICT_REQUEST_JSON in request.scope:
                request._json = request.scope[STRICT_REQUEST_JSON]
            return await original(request)
        return parsed_request


class SourceReadRequest(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    module: Literal['drafting', 'vetting', 'advisory'] = 'vetting'
    projectionSha256: list[Sha] = Field(min_length=1, max_length=10000)
    operation: Literal['catalog', 'ids', 'scan', 'prepare']
    originIds: list[OriginId] = Field(default_factory=list, max_length=256)
    cursor: Annotated[str, Field(min_length=1, max_length=8192)] | None = None
    maxItems: int = Field(default=64, ge=1, le=1024)
    maxBytes: int = Field(default=4194304, ge=1024, le=MAX_BYTES)
    view: Annotated[str, Field(min_length=1, max_length=128)] = 'canonical_units'
    readManifestSha256: list[Sha] = Field(default_factory=list, max_length=10000)
    readManifestToken: Annotated[str, Field(min_length=64, max_length=64, pattern=r'^[0-9a-f]{64}$')] | None = None
    semanticReadVersion: Literal['source-semantic-records-v1', 'source-semantic-dictionary-v2'] = 'source-semantic-records-v1'
    semanticReadProfile: Literal['full', 'text-first-scan-v1'] = 'full'
    knownSemanticGroups: list[Sha] = Field(default_factory=list, max_length=10000)
    normalProofVersion: Literal['compact-normal-source-proof-v1'] | None = None


def _unique(chunks, key):
    values = {chunk.get(key) for chunk in chunks}
    return next(iter(values)) if len(values) == 1 else None


def _source_unknowns(snapshot, parent):
    rows = [row for row in snapshot.get('unknowns', [])
            if not isinstance(row, dict) or row.get('unitId') in (None, parent.get('unitId'))]
    pages, parts = parent.get('physicalPages'), parent.get('parts')
    if not (snapshot.get('producerKind') == parent.get('producerKind') == 'normal_observed'
            and isinstance(pages, list) and pages
            and all(type(page) is int and page > 0 for page in pages)
            and isinstance(parts, list) and parts
            and all(isinstance(part, dict) and type(part.get('physicalPageNo')) is int
                    and part['physicalPageNo'] > 0 for part in parts)
            and pages == sorted({part['physicalPageNo'] for part in parts})):
        return rows
    # Only exact page diagnostics can be scoped; whole-source and future declarations remain visible.
    paths = {'physical_page.' + str(page) for page in pages}
    return [row for row in rows if not (isinstance(row, dict)
        and set(row) == {'path', 'reason', 'status'} and isinstance(row['path'], str)
        and re.fullmatch(r'physical_page\.[1-9][0-9]*', row['path']) and row['path'] not in paths)]


def _table_text_is_in_reading_text(origin):
    """Drop native PDF table shape only when every cell text is already in source reading order."""
    if origin.get('producerKind') != 'native_pdf' or not isinstance(origin.get('text'), str):
        return False
    tables = origin.get('nativeTables')
    if not isinstance(tables, list) or not tables:
        return False
    position = 0
    matched_text = False
    for table in tables:
        if not isinstance(table, dict) or not isinstance(table.get('fragments'), list):
            return False
        for fragment in table['fragments']:
            if not isinstance(fragment, dict) or not isinstance(fragment.get('cells'), list):
                return False
            for cell in fragment['cells']:
                if not isinstance(cell, dict) or not isinstance(cell.get('text'), str):
                    return False
                value = cell['text']
                if value:
                    position = origin['text'].find(value, position)
                    if position < 0:
                        return False
                    matched_text = True
                    position += len(value)
    return matched_text


def _text_first_origin(origin):
    projected = {key: value for key, value in origin.items()
                 if key not in ('sourceContext', 'unknowns', 'sourceQuality')}
    if _table_text_is_in_reading_text(origin):
        projected.pop('nativeTables', None)
    return projected


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
        if request.semanticReadVersion == DICTIONARY_VERSION:
            binding['semanticReadVersion'] = DICTIONARY_VERSION
        if request.semanticReadProfile == TEXT_FIRST_PROFILE:
            binding['semanticReadProfile'] = TEXT_FIRST_PROFILE
        if not addresses and request.normalProofVersion is not None:
            binding['normalProofVersion'] = request.normalProofVersion
        return hmac.new(secret, _serialize(binding), hashlib.sha256).hexdigest()

    def cas_json(address, maximum):
        # stat supplies only a finite allocation bound, never a validity verdict.
        path = registry.artifact_store.root / 'sha256' / address[:2] / (address + '.blob')
        size = path.stat().st_size
        fail(size <= maximum, 'SOURCE_READ_MANIFEST_TOO_LARGE', 'Prepared source manifest exceeds its per-manifest finite bound')
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
        normal_proofs = [] if request.normalProofVersion is not None else None
        if preparation_key in prepared_addresses:
            manifests = prepared_addresses[preparation_key]
            catalog_request = request.model_copy(update={'operation': 'catalog',
                'readManifestSha256': manifests, 'readManifestToken': preparation_token(request, manifests),
                'normalProofVersion': None, 'maxBytes': MAX_BYTES})
            with registry.validation_frame(software_guard=software_guard):
                catalog = semantic_page(catalog_request, semantic_plan(catalog_request, normal_proofs=normal_proofs))
            prepared_addresses.move_to_end(preparation_key)
            result = {key: value for key, value in catalog.items() if key not in ('scopeIdentity', 'nextCursor')}
            result.update(operation='prepare', coverage={'status': 'prepared',
                'totalOrigins': catalog['coverage']['totalOrigins']},
                coldPreparation={'performed': False, 'fullGraphValidation': False,
                    'closureAlgorithm': POLICY, 'originalSealsChanged': False})
            if normal_proofs is not None:
                result.update(normalProofVersion=NORMAL_PROOF_VERSION, normalProofs=normal_proofs)
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
                    normal_proof = None
                    if normal_proofs is not None and snapshot.get('schema') == 'consense.normal-observed.v1' \
                            and snapshot.get('projectionIdentity', {}).get('module') == request.module:
                        # The owned load already validated this exact source; the compact reader also pins its revision alias.
                        metadata = registry.source_metadata(address, module=request.module)
                        normal_proof = {key: metadata[key] for key in ('projectionSha256', 'producerKind',
                            'projectionIdentity', 'projectionRevisionSha256', 'sourceHash', 'source',
                            'snapshotSha256', 'parentSha256')}
                        normal_proof['sourceBinding'] = metadata['manifest']['sourceBinding']
                        del metadata
                fail(canonical_scope_policy_sha(snapshot) == policy.policyIdentity['manifestSha256'],
                     'PROJECTION_SCOPE_POLICY_MISMATCH', 'Source was prepared against another scope policy')
                registry._module(snapshot, request.module)
                parents = snapshot['chunks']
                assert_canonical_scope(parents, policy, module=request.module)
                source = _source(address, snapshot, parents)
                fail(source['documentId'] not in documents, 'SOURCE_READ_AMBIGUOUS_DOCUMENT',
                     'Prepare one exact revision per document in a semantic read scope')
                documents.add(source['documentId'])
                catalog = SourceUnits(parents, include_members=request.semanticReadVersion != DICTIONARY_VERSION)
                entries = []
                projected_members, referenced_trees = {}, {}
                def member(parent):
                    if parent['id'] not in projected_members:
                        projected = semantic_member(parent, address)
                        projected['sourceContext'] = semantic_value({
                            'unknowns': _source_unknowns(snapshot, parent),
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
                groups, inline_group_bytes = {}, 0
                for parent in parents:
                    if request.semanticReadVersion == DICTIONARY_VERSION:
                        projected = member(parent)
                        descriptor = registry.artifact_store.put_bytes(_serialize(projected), identity_scope='source_semantic_member_v2')
                        template = semantic_unit_template(catalog.resolve(parent))
                        template['documentId'] = projected['documentId']
                        raw_template = _serialize(template)
                        group_sha = hashlib.sha256(raw_template).hexdigest()
                        if group_sha not in groups:
                            if inline_group_bytes + len(raw_template) <= INLINE_GROUP_BYTES:
                                groups[group_sha] = {'schema': 'consense.inline-semantic-group.v1',
                                    'storage': 'inline_prepared_manifest', 'sha256': group_sha,
                                    'byteCount': len(raw_template), 'unit': template}
                                inline_group_bytes += len(raw_template)
                            else:
                                groups[group_sha] = registry.artifact_store.put_bytes(raw_template, identity_scope='source_semantic_group_v2')
                        entries.append({'id': parent['id'], 'documentId': projected['documentId'], 'sourceHash': projected['sourceHash'], 'unitId': template['unitId'], 'groupId': group_sha,
                            'recordRef': descriptor})
                        projected_members.pop(parent['id'])
                        del projected
                        # No per-origin closure tree survives the preparation loop.
                        catalog.cache.clear()
                        catalog.hierarchy_cache.clear()
                        catalog.attachment_cache.clear()
                        continue
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
                if request.semanticReadVersion == DICTIONARY_VERSION:
                    manifest.update(schema='consense.source-semantic-read-manifest.v3', semanticReadVersion=DICTIONARY_VERSION, groups=groups)
                if request.semanticReadProfile == TEXT_FIRST_PROFILE:
                    manifest['semanticReadProfile'] = TEXT_FIRST_PROFILE
                if normal_proofs is not None:
                    manifest['normalProof'] = normal_proof
                    normal_proofs.append(normal_proof)
                raw = _serialize(manifest)
                if len(raw) > MAX_BYTES and inline_group_bytes:
                    # Keep the existing finite manifest allowance even when inline metadata consumes its remaining space.
                    for key, reference in groups.items():
                        if reference.get('storage') == 'inline_prepared_manifest':
                            groups[key] = registry.artifact_store.put_bytes(_serialize(reference['unit']), identity_scope='source_semantic_group_v2')
                    raw = _serialize(manifest)
                fail(len(raw) <= MAX_BYTES, 'SOURCE_READ_MANIFEST_TOO_LARGE',
                     'Prepared source metadata exceeds its finite bound')
                descriptor = registry.artifact_store.put_bytes(raw, identity_scope='source_semantic_read_manifest')
                manifests.append(descriptor['sha256']); sources.append(source)
                origin_ids.extend(entry['id'] for entry in entries)
                projected_members.clear()
                referenced_trees.clear()
                registry.artifact_store.release_bytes(assets.values())
                del snapshot, parents, catalog
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
        if request.semanticReadVersion == DICTIONARY_VERSION:
            result['semanticReadVersion'] = DICTIONARY_VERSION
        if request.semanticReadProfile == TEXT_FIRST_PROFILE:
            result['semanticReadProfile'] = TEXT_FIRST_PROFILE
        if normal_proofs is not None:
            result.update(normalProofVersion=NORMAL_PROOF_VERSION, normalProofs=normal_proofs)
        fail(len(_serialize(result)) <= request.maxBytes, 'SOURCE_READ_BUDGET_EXCEEDED',
             'Preparation receipt exceeds maxBytes', maxBytes=request.maxBytes)
        prepared_addresses[preparation_key] = manifests
        while len(prepared_addresses) > 128 or sum(len(key) + len(_serialize(value))
                for key, value in prepared_addresses.items()) > 4 * 1024 * 1024:
            prepared_addresses.popitem(last=False)
        return result

    def dictionary_read(request, result, entries, groups, start, scope_sha):
        """Members travel once in origin order; the consumer assembles complete closures after scan."""
        from native_observation import _strict_json
        known = set(request.knownSemanticGroups)
        fail(len(known) == len(request.knownSemanticGroups) and known <= groups.keys(),
             'SOURCE_READ_PREPARATION_STALE', 'Known groups must belong to these exact prepared manifests')
        result.update(semanticReadVersion=DICTIONARY_VERSION, originUnits={}, blockedOrigins=[])
        result['scopeIdentity']['semanticReadVersion'] = DICTIONARY_VERSION
        ledger = result['coverage']
        ledger['blockedOriginIds'] = []
        if request.operation == 'catalog':
            return result
        def scalar_bytes():
            return sum(len(_serialize(ledger[key])) for key in
                ('status', 'returnedOrigins', 'processedOrigins', 'remainingOrigins')) + len(_serialize(result['nextCursor']))
        def appended_bytes(container, value):
            return len(_serialize(value)) + bool(container)
        response_bytes = len(_serialize(result))
        by_id = {entry['id']: entry for entry in entries} if request.operation == 'ids' else {}
        fail(all(id_ in by_id for id_ in request.originIds), 'SOURCE_READ_ORIGIN_OUTSIDE_SCOPE', 'Origin outside prepared scope')
        selected = ([by_id[id_] for id_ in request.originIds] if request.operation == 'ids'
                    else entries[start:start + request.maxItems])
        def progress():
            count = len(result['origins']) + len(result['blockedOrigins'])
            ledger.update(returnedOrigins=len(result['origins']), processedOrigins=start + count,
                remainingOrigins=len(entries) - start - count)
            result['nextCursor'] = (cursor(scope_sha, start + count)
                if request.operation == 'scan' and start + count < len(entries) else None)
            ledger['status'] = ('partial' if result['nextCursor'] is not None else
                'complete_with_blocked' if result['blockedOrigins'] else 'complete')
        for entry in selected:
            descriptor, group_id = entry['recordRef'], entry['groupId']
            group_ref = groups[group_id]
            new_group = group_id not in known
            required = descriptor['byteCount'] + (group_ref['byteCount'] if new_group else 0)
            origin, group = None, None
            readable = (descriptor['byteCount'] <= MAX_BYTES and
                (not new_group or group_ref['byteCount'] <= MAX_BYTES))
            if readable and (required <= MAX_BYTES or request.semanticReadProfile == TEXT_FIRST_PROFILE):
                origin = _strict_json(registry.artifact_store.read_asset(descriptor))
                registry.artifact_store.release_bytes((descriptor,))
                fail(origin['id'] == entry['id'], 'SOURCE_READ_PREPARATION_STALE', 'Member differs from its prepared address')
                if request.operation == 'scan' and request.semanticReadProfile == TEXT_FIRST_PROFILE:
                    origin = _text_first_origin(origin)
                added = appended_bytes(result['origins'], origin)
                if new_group:
                    if group_ref.get('storage') == 'inline_prepared_manifest':
                        group = group_ref['unit']
                        raw_group = _serialize(group)
                        fail(group_ref.get('schema') == 'consense.inline-semantic-group.v1'
                             and type(group_ref['byteCount']) is int
                             and len(raw_group) == group_ref['byteCount']
                             and hashlib.sha256(raw_group).hexdigest() == group_id,
                             'SOURCE_READ_PREPARATION_STALE', 'Inline group differs from its prepared address')
                    else:
                        group = _strict_json(registry.artifact_store.read_asset(group_ref))
                        registry.artifact_store.release_bytes((group_ref,))
                    fail(group['unitId'] == entry['unitId'] and group['documentId'] == origin['documentId'],
                         'SOURCE_READ_PREPARATION_STALE', 'Group differs from its prepared address')
                    row = {'groupId': group_id, 'unit': group}
                    added += appended_bytes(result['units'], row)
                    result['units'].append(row)
                added += len(_serialize(entry['id'])) + 1 + len(_serialize(group_id)) + bool(result['originUnits'])
                added += appended_bytes(ledger['returnedOriginIds'], entry['id'])
                added += appended_bytes(ledger['requestedOriginIds'], entry['id'])
                before_progress = scalar_bytes()
                result['origins'].append(origin)
                result['originUnits'][entry['id']] = group_id
                ledger['returnedOriginIds'].append(entry['id'])
                ledger['requestedOriginIds'].append(entry['id'])
                progress()
                required = response_bytes + added + scalar_bytes() - before_progress
                if required <= request.maxBytes:
                    response_bytes = required
                    known.add(group_id)
                    continue
                result['origins'].pop()
                result['originUnits'].pop(entry['id'])
                ledger['returnedOriginIds'].pop()
                ledger['requestedOriginIds'].pop()
                if new_group:
                    result['units'].pop()
                progress()
                response_bytes += scalar_bytes() - before_progress
                if result['origins'] or result['blockedOrigins']:
                    break
            fail(request.operation != 'ids', 'SOURCE_READ_BUDGET_EXCEEDED',
                 'Requested member/group exceeds the finite read budget; no text is truncated', originId=entry['id'])
            blocked = {'id': entry['id'], 'documentId': entry['documentId'], 'sourceHash': entry['sourceHash'], 'unitId': entry['unitId'], 'groupId': group_id,
                'reason': 'semantic_member_or_group_too_large', 'requiredBytes': required, 'maxBytes': request.maxBytes}
            added = appended_bytes(result['blockedOrigins'], blocked)
            added += appended_bytes(ledger['blockedOriginIds'], entry['id'])
            added += appended_bytes(ledger['requestedOriginIds'], entry['id'])
            before_progress = scalar_bytes()
            result['blockedOrigins'].append(blocked)
            ledger['blockedOriginIds'].append(entry['id'])
            ledger['requestedOriginIds'].append(entry['id'])
            progress()
            response_bytes += added + scalar_bytes() - before_progress
            fail(response_bytes <= request.maxBytes, 'SOURCE_READ_BUDGET_EXCEEDED', 'Blocked-source ledger exceeds maxBytes')
        return result

    def semantic_plan(request, *, normal_proofs=None):
        fail(len(request.readManifestSha256) == len(request.projectionSha256) and request.readManifestToken is not None,
             'SOURCE_READ_BOUNDED_UNAVAILABLE',
             'Explicit prepare is required before a bounded semantic read; no legacy full-graph fallback is performed')
        fail(hmac.compare_digest(request.readManifestToken, preparation_token(request, request.readManifestSha256)),
             'SOURCE_READ_PREPARATION_STALE', 'Preparation belongs to another process, scope, source order, policy or software')
        sources, entries, groups = [], [], {}
        for address, prepared in zip(request.projectionSha256, request.readManifestSha256):
            manifest, _ = cas_json(prepared, MAX_BYTES)
            dictionary = request.semanticReadVersion == DICTIONARY_VERSION
            fail(manifest['schema'] in (('consense.source-semantic-read-manifest.v2', 'consense.source-semantic-read-manifest.v3')
                 if dictionary else ('consense.source-semantic-read-manifest.v1',))
                 and manifest['projectionSha256'] == address
                 and manifest['module'] == request.module
                 and manifest.get('semanticReadProfile', 'full') == request.semanticReadProfile
                 and manifest['scopePolicySha256'] == policy.policyIdentity['manifestSha256']
                 and manifest['softwareIdentitySha256'] == software_sha()
                 and manifest['sourceUnitPolicy'] == POLICY
                 and manifest['semanticProjectionVersion'] == SEMANTIC_VERSION,
                 'SOURCE_READ_PREPARATION_STALE', 'Prepared read identities do not match the current request')
            registry.verify_prepared_read_dependencies(manifest['fileDependencies'], manifest['casDependencies'])
            if normal_proofs is not None:
                fail('normalProof' in manifest, 'SOURCE_READ_PREPARATION_STALE',
                     'Prepared source has no requested compact normal proof')
                normal_proofs.append(manifest['normalProof'])
            sources.append(manifest['source']); entries.extend(manifest['origins'])
            if dictionary:
                for key, descriptor in manifest['groups'].items():
                    fail(isinstance(descriptor, dict)
                         and (manifest['schema'] == 'consense.source-semantic-read-manifest.v3'
                          or descriptor.get('storage') != 'inline_prepared_manifest')
                         and key == descriptor.get('sha256') and (key not in groups or groups[key] == descriptor),
                         'SOURCE_READ_PREPARATION_STALE', 'Group address is ambiguous')
                    if descriptor.get('storage') == 'inline_prepared_manifest':
                        fail(set(descriptor) == {'schema', 'storage', 'sha256', 'byteCount', 'unit'}
                             and descriptor.get('schema') == 'consense.inline-semantic-group.v1'
                             and type(descriptor.get('byteCount')) is int
                             and 0 <= descriptor['byteCount'] <= INLINE_GROUP_BYTES
                             and isinstance(descriptor.get('unit'), dict),
                             'SOURCE_READ_PREPARATION_STALE', 'Inline group metadata is malformed')
                    else:
                        fail(set(descriptor) == {'schema', 'storage', 'sha256', 'byteCount', 'relativePath', 'identityScope'},
                             'SOURCE_READ_PREPARATION_STALE', 'Group CAS metadata must not contain inline fields')
                    groups[key] = descriptor
        by_id = {entry['id']: entry for entry in entries}
        fail(len(by_id) == len(entries), 'SOURCE_READ_AMBIGUOUS_ORIGIN', 'Prepared origin IDs must be unique')
        scope = {'sources': sources, 'module': request.module, 'policyIdentity': policy.policyIdentity,
            'sourceUnitPolicy': POLICY, 'view': request.view, 'semanticProjectionVersion': SEMANTIC_VERSION,
            'readManifestSha256': request.readManifestSha256, 'softwareIdentitySha256': software_sha(),
            'originOrderSha256': hashlib.sha256(_serialize([entry['id'] for entry in entries])).hexdigest()}
        if request.semanticReadVersion == DICTIONARY_VERSION:
            scope['semanticReadVersion'] = DICTIONARY_VERSION
        if request.semanticReadProfile == TEXT_FIRST_PROFILE:
            scope['semanticReadProfile'] = TEXT_FIRST_PROFILE
        scope_sha = hashlib.sha256(_serialize(scope)).hexdigest()
        return sources, entries, groups, scope, scope_sha

    def semantic_page(request, prepared):
        sources, entries, groups, scope, scope_sha = prepared
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
        if request.semanticReadVersion == DICTIONARY_VERSION:
            result = dictionary_read(request, result, entries, groups, start, scope_sha)
            if request.semanticReadProfile == TEXT_FIRST_PROFILE:
                result['semanticReadProfile'] = TEXT_FIRST_PROFILE
            fail(len(_serialize(result)) <= request.maxBytes, 'SOURCE_READ_BUDGET_EXCEEDED', 'Semantic dictionary exceeds maxBytes')
            return result
        if request.operation != 'catalog':
            by_id = {entry['id']: entry for entry in entries}
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

    def semantic_read(request):
        if request.operation == 'prepare':
            return prepare(request)
        with registry.validation_frame(software_guard=software_guard):
            return semantic_page(request, semantic_plan(request))

    def validate_request(request):
        fail(request.normalProofVersion is None or request.operation == 'prepare' and request.view == 'semantic_units',
             'SOURCE_READ_OPERATION_INVALID', 'Compact normal proof belongs only to semantic preparation')
        fail(request.view in ('canonical_units', 'semantic_units'), 'SOURCE_READ_VIEW_UNSUPPORTED',
             'Only canonical_units and semantic_units are implemented; complete normal Word scope remains unknown',
             view=request.view, limitations=LIMITATIONS)
        fail(request.module == target_module, 'SOURCE_READ_TARGET_MODULE_MISMATCH',
             'Use the OER process for the requested module')
        fail(len(set(request.projectionSha256)) == len(request.projectionSha256),
             'DUPLICATE_CANONICAL_REVISION', 'Source addresses must be unique')
        fail(len(set(request.originIds)) == len(request.originIds),
             'SOURCE_READ_DUPLICATE_ORIGIN', 'Each requested origin must appear only once')
        fail(request.maxItems <= (1024 if request.view == 'semantic_units' and
             request.operation == 'scan' and request.semanticReadVersion == DICTIONARY_VERSION else 256),
             'SOURCE_READ_OPERATION_INVALID', 'Only dictionary-v2 scan supports more than 256 items')
        fail(request.semanticReadProfile == 'full' or
             (request.view == 'semantic_units' and request.semanticReadVersion == DICTIONARY_VERSION),
             'SOURCE_READ_OPERATION_INVALID', 'Text-first scan needs semantic dictionary-v2')
        fail((request.operation == 'ids' and request.originIds and request.cursor is None
              and len(request.originIds) <= request.maxItems)
             or (request.operation != 'ids' and not request.originIds
                 and (request.operation == 'scan' or request.cursor is None)),
             'SOURCE_READ_OPERATION_INVALID', 'ids needs bounded originIds without a cursor; catalog/scan do not accept originIds')

    def read(request):
        try:
            validate_request(request)
            if request.view == 'semantic_units':
                return semantic_read(request)
            fail(request.operation != 'prepare' and not request.readManifestSha256 and request.readManifestToken is None,
                 'SOURCE_READ_OPERATION_INVALID', 'Preparation bindings belong only to semantic_units')
            with registry.validation_frame(software_guard=software_guard):
                sources, origin_order, origin_sources, document_sources = [], [], {}, {}
                for address in request.projectionSha256:
                    metadata_reader = getattr(registry, 'source_metadata', None)
                    snapshot = (metadata_reader(address, module=request.module) if metadata_reader is not None
                                else registry.load(address))
                    fail(canonical_scope_policy_sha(snapshot) == policy.policyIdentity['manifestSha256'],
                         'PROJECTION_SCOPE_POLICY_MISMATCH', 'Source was prepared against another scope policy')
                    # Keep the existing module/eligibility gate and actual source order.
                    registry._module(snapshot, request.module)
                    chunks = snapshot['chunks']
                    assert_canonical_scope(chunks, policy, module=request.module)
                    sources.append(snapshot.get('sourceSummary') or _source(address, snapshot, chunks))
                    for parent in chunks:
                        fail(parent['id'] not in origin_sources, 'SOURCE_READ_AMBIGUOUS_ORIGIN',
                             'Origin IDs must be unique across the requested sources')
                        origin_order.append(parent['id'])
                        origin_sources[parent['id']] = address
                        document_sources.setdefault(parent.get('documentId'), set()).add(address)
                    del snapshot, chunks
                scope = {'sources': sources, 'module': request.module, 'view': request.view, 'policyIdentity': policy.policyIdentity,
                         'sourceUnitPolicy': POLICY, 'softwareIdentitySha256': software_sha(),
                         'originOrderSha256': hashlib.sha256(_serialize(origin_order)).hexdigest()}
                scope_sha = hashlib.sha256(_serialize(scope)).hexdigest()
                start = offset(request.cursor, scope_sha, len(origin_order))
                result = {'contract': CONTRACT, 'module': request.module, 'operation': request.operation,
                    'view': request.view, 'projectionSha256': request.projectionSha256,
                    'sourceUnitPolicy': POLICY, 'scopeIdentity': {**scope, 'scopeSha256': scope_sha},
                    'sources': sources, 'origins': [], 'units': [], 'nextCursor': None,
                    'coverage': {'status': 'catalog' if request.operation == 'catalog' else 'complete',
                        'totalOrigins': len(origin_order), 'requestedOriginIds': [], 'returnedOriginIds': [],
                        'returnedOrigins': 0, 'processedOrigins': start, 'remainingOrigins': len(origin_order) - start},
                    'limitations': LIMITATIONS, 'sourceQualityVerified': False}
                if request.operation != 'catalog':
                    fail(all(len(addresses) == 1 for addresses in document_sources.values()),
                         'SOURCE_READ_AMBIGUOUS_DOCUMENT',
                         'Complete-unit reads require one exact root per document; shared document roots need an explicit complete source family')
                    fail(all(origin in origin_sources for origin in request.originIds),
                         'SOURCE_READ_ORIGIN_OUTSIDE_SCOPE', 'Every requested ID must belong to the exact registered source scope')
                    selected = list(request.originIds) if request.operation == 'ids' else origin_order[start:start + request.maxItems]
                    if request.operation == 'ids':
                        result['coverage']['requestedOriginIds'] = list(request.originIds)
                    resolved = {}
                    body_bytes = 0
                    planned_bytes = 0
                    def response_bytes():
                        envelope = {**result, 'origins': [], 'units': []}
                        count = len(result['origins'])
                        return len(_serialize(envelope)) + body_bytes + 2 * max(count - 1, 0)
                    current_address, catalog = None, None
                    for origin in selected:
                        address = origin_sources[origin]
                        if origin not in resolved:
                            if current_address != address:
                                snapshot = registry.load(address)
                                registry._module(snapshot, request.module)
                                assert_canonical_scope(snapshot['chunks'], policy, module=request.module)
                                catalog = SourceUnits(snapshot['chunks'])
                                current_address = address
                                if request.operation == 'ids':
                                    # Interleaved caller IDs retain order without reopening a document.
                                    for selected_id in selected:
                                        if origin_sources[selected_id] == address and selected_id not in resolved:
                                            seed = catalog.parents[selected_id]
                                            unit = catalog.resolve(seed)
                                            size = len(_serialize(seed)) + len(_serialize(unit))
                                            planned_bytes += size
                                            fail(planned_bytes <= request.maxBytes, 'SOURCE_READ_BUDGET_EXCEEDED',
                                                 'Complete selected units exceed the bounded response budget',
                                                 originId=selected_id, maxBytes=request.maxBytes)
                                            resolved[selected_id] = (seed, unit, size)
                                del snapshot
                            if origin not in resolved:
                                seed = catalog.parents[origin]
                                unit = catalog.resolve(seed)
                                resolved[origin] = (seed, unit, len(_serialize(seed)) + len(_serialize(unit)))
                        seed, unit, added_bytes = resolved.pop(origin)
                        body_bytes += added_bytes
                        result['origins'].append(seed)
                        result['units'].append(unit)
                        ledger = result['coverage']
                        ledger['returnedOriginIds'].append(seed['id'])
                        ledger['returnedOrigins'] += 1
                        ledger['processedOrigins'] = start + ledger['returnedOrigins']
                        ledger['remainingOrigins'] = len(origin_order) - ledger['processedOrigins']
                        if request.operation == 'scan':
                            ledger['requestedOriginIds'].append(seed['id'])
                            result['nextCursor'] = (cursor(scope_sha, ledger['processedOrigins']) if ledger['remainingOrigins'] else None)
                            ledger['status'] = 'partial' if result['nextCursor'] else 'complete'
                        required_bytes = response_bytes()
                        if required_bytes <= request.maxBytes:
                            continue
                        fail(request.operation != 'ids', 'SOURCE_READ_BUDGET_EXCEEDED',
                             'The complete requested source units exceed maxBytes; request fewer IDs or a larger bounded budget',
                             originId=seed['id'], requiredBytes=required_bytes, maxBytes=request.maxBytes)
                        result['origins'].pop(); result['units'].pop()
                        body_bytes -= added_bytes
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
                required_bytes = response_bytes() if request.operation != 'catalog' else len(_serialize(result))
                fail(required_bytes <= request.maxBytes, 'SOURCE_READ_BUDGET_EXCEEDED',
                     'The compact source catalog/coverage exceeds maxBytes; request fewer sources or a larger bounded budget',
                     maxBytes=request.maxBytes)
                return result
        except WindowError as error:
            raise HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error
        except (ValueError, OSError, KeyError, TypeError) as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', 'SOURCE_READ_UNAVAILABLE'),
                'message': str(error), 'sourceQualityChanged': False}) from error

    def stream_line(value):
        raw = _serialize(value) + b'\n'
        fail(len(raw) <= MAX_BYTES, 'SOURCE_READ_STREAM_FRAME_TOO_LARGE',
             'A semantic stream frame exceeds the finite line bound')
        return raw

    def locator_stamp(path):
        stat = path.stat()
        return str(path.resolve()), stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns, stat.st_ino

    def stream_frames(request):
        journal = app.state.request_evidence
        summary = {'module': request.module, 'operation': 'scan', 'view': request.view,
            'projectionSha256': request.projectionSha256, 'semanticReadVersion': request.semanticReadVersion,
            'semanticReadProfile': request.semanticReadProfile, 'streamVersion': STREAM_VERSION,
            'recording': 'summary_only; complete members remain in sealed CAS'}
        try:
            with journal.operation(ENDPOINT, summary):
                page_count, processed, total, scope_sha = 0, 0, 0, None
                with registry.validation_frame(software_guard=software_guard):
                    prepared = semantic_plan(request)
                    total = len(prepared[1])
                    scope_sha = prepared[4]
                    frame = registry._validation_frame.get()
                    locators = {filename: locator_stamp(Path(filename)) for filename in frame['locations']}
                    def cheap_check():
                        # Stamps are early-abort hints only; final actual-byte proof remains authoritative.
                        fail(all(locator_stamp(Path(filename)) == expected for filename, expected in locators.items()),
                             'SOURCE_READ_STREAM_SOURCE_CHANGED', 'Source locator changed during the owned scan')
                    known = OrderedDict.fromkeys(request.knownSemanticGroups[-512:])
                    current = request.model_copy(deep=True)
                    while True:
                        cheap_check()
                        result = semantic_page(current, prepared)
                        next_processed = result['coverage']['processedOrigins']
                        fail(next_processed > processed or total == 0, 'SOURCE_READ_STREAM_NO_PROGRESS',
                             'A semantic stream must advance through the finite origin scope')
                        fail(next_processed <= total, 'SOURCE_READ_STREAM_COVERAGE', 'Stream exceeded its source scope')
                        processed = next_processed
                        page_count += 1
                        encoded = stream_line({'type': 'page', 'sequence': page_count, 'data': result})
                        next_cursor = result['nextCursor']
                        for row in result['units']:
                            known[row['groupId']] = None
                            while len(known) > 512:
                                known.popitem(last=False)
                        cheap_check()
                        del result
                        yield encoded
                        if next_cursor is None:
                            break
                        current = current.model_copy(update={'cursor': next_cursor,
                            'knownSemanticGroups': list(known)})
                    fail(processed == total, 'SOURCE_READ_STREAM_COVERAGE', 'Owned scan ended before complete source coverage')
                    cheap_check()
                # Only this record can certify successful source/CAS/software exit guards.
                complete = {'type': 'complete', 'pageCount': page_count, 'totalOrigins': total,
                    'processedOrigins': processed, 'scopeSha256': scope_sha,
                    'readManifestToken': request.readManifestToken, 'proofComplete': True}
                journal.observe('source-semantic-stream-complete', {**complete, 'streamVersion': STREAM_VERSION})
                yield stream_line(complete)
        except Exception as error:
            yield stream_line({'type': 'error', 'kind': getattr(error, 'kind', getattr(error, 'code', 'SOURCE_READ_STREAM_FAILED')),
                'message': 'The owned semantic scan did not complete its source proof', 'proofComplete': False})

    async def streamed_body(request):
        context = copy_context()
        iterator = stream_frames(request)
        def advance():
            return next(iterator, None)
        try:
            while (line := await run_in_threadpool(context.run, advance)) is not None:
                yield line
        finally:
            with CancelScope(shield=True):
                await run_in_threadpool(context.run, iterator.close)

    recorded_read = app.state.request_evidence.wrap(ENDPOINT, read)
    def dispatch(request: SourceReadRequest, accept: str | None = Header(default=None)):
        if accept != 'application/x-ndjson':
            return recorded_read(request)
        try:
            validate_request(request)
            fail(request.operation == 'scan' and request.view == 'semantic_units'
                 and request.semanticReadVersion == DICTIONARY_VERSION and request.cursor is None,
                 'SOURCE_READ_STREAM_OPERATION_INVALID', 'Streaming requires one complete prepared dictionary scan')
            current = request.model_copy(deep=True, update={'maxBytes': min(request.maxBytes, MAX_BYTES - 256)})
            return StreamingResponse(streamed_body(current), media_type='application/x-ndjson')
        except WindowError as error:
            raise HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error

    dispatch.__annotations__['request'] = SourceReadRequest
    app.router.add_api_route(ENDPOINT, dispatch, methods=['POST'], route_class_override=_SourceReadRoute)
