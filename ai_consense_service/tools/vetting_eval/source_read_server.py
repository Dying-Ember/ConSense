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
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

from oer_server import assert_canonical_scope, canonical_scope_policy_sha
from oer_source_registry import _serialize
from retrieval_source_units import POLICY, SourceUnits
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
CAPABILITY = {
    'contract': CONTRACT, 'version': CONTRACT, 'endpoint': ENDPOINT,
    'operations': ['catalog', 'ids', 'scan'], 'views': ['canonical_units'],
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
    operation: Literal['catalog', 'ids', 'scan']
    originIds: list[OriginId] = Field(default_factory=list, max_length=256)
    cursor: Annotated[str, Field(min_length=1, max_length=8192)] | None = None
    maxItems: int = Field(default=64, ge=1, le=256)
    maxBytes: int = Field(default=4194304, ge=1024, le=MAX_BYTES)
    view: Annotated[str, Field(min_length=1, max_length=128)] = 'canonical_units'


def _unique(chunks, key):
    values = {chunk.get(key) for chunk in chunks}
    return next(iter(values)) if len(values) == 1 else None


def _source(address, snapshot, chunks):
    identity, source = snapshot.get('projectionIdentity', {}), snapshot.get('source', {})
    return {'projectionSha256': address,
        'documentId': identity.get('documentId', source.get('documentId', _unique(chunks, 'documentId'))),
        'originalSourceSha256': identity.get('originalSourceSha256', source.get('sourceSha256', _unique(chunks, 'originalSourceSha256'))),
        'sourceHash': snapshot.get('sourceHash', _unique(chunks, 'sourceHash')),
        'sourceRevisionSha256': identity.get('sourceRevisionSha256', source.get('sourceRevisionSha256', _unique(chunks, 'sourceRevisionSha256'))),
        'projectionRevisionSha256': snapshot.get('projectionRevisionSha256', _unique(chunks, 'projectionRevisionSha256')),
        'chunkCount': len(chunks), 'producerKind': snapshot.get('producerKind'),
        'sourceQualityStatus': _unique([chunk.get('sourceQuality', {}) for chunk in chunks], 'status'),
        'sourceQualityVerified': False}


def install_source_read_route(app, registry, policy, *, target_module, software_guard):
    """Install the production route into the same registry/runtime composition."""
    from fastapi import HTTPException
    secret = os.urandom(32)

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

    def read(request):
        try:
            fail(request.view == 'canonical_units', 'SOURCE_READ_VIEW_UNSUPPORTED',
                 'Only canonical_units is implemented; template observations and complete normal Word scope remain unsupported',
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
                scope = {'sources': sources, 'module': request.module, 'policyIdentity': policy.policyIdentity,
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
