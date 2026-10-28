"""Private, request-scoped receipts for actual OER inputs and model events.

Files belong in the isolated service state, never in an HTTP response or stdout.
The journal observes a run; it does not certify text, relevance or completeness.
"""
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
from functools import wraps
import hashlib
import json
from pathlib import Path
import time
import uuid


def _encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def _save(path, value):
    raw = _encode(value)
    with path.open('xb') as stream:
        stream.write(raw)
    return {'file': path.name, 'sha256': hashlib.sha256(raw).hexdigest(),
            'byteCount': len(raw)}


def _index_summary(request):
    # Canonical roots and index metadata already own the complete source graph.
    return {'projectId': request.projectId, 'rebuild': request.rebuild,
            'chunkCount': len(request.chunks),
            'sourceHashes': sorted({chunk.sourceHash for chunk in request.chunks}),
            'originalSourceSha256': sorted({value for chunk in request.chunks
                                          if (value := getattr(chunk, 'originalSourceSha256', None))}),
            'projectionSha256': sorted({value for chunk in request.chunks
                                      if (value := getattr(chunk, 'projectionSha256', None))}),
            'recording': 'summary_only; complete sources remain in canonical and index storage'}


def _identity_summary(value):
    if not isinstance(value, dict):
        return {}
    fields = ('schema', 'producerKind', 'producerVersion', 'projectionSha256',
        'projectionRevisionSha256', 'sourceHash', 'projectionIdentity',
        'observationManifestRef', 'rootRef', 'sourceBinding', 'responseFormat')
    result = {key: value[key] for key in fields if key in value}
    if isinstance(value.get('source'), dict):
        result['source'] = {key: value['source'][key] for key in (
            'documentId', 'sourceSha256', 'originalSourceSha256', 'parsedSourceHash',
            'sourceRevisionSha256', 'fileName', 'sourceEditionId') if key in value['source']}
    for key in ('chunks', 'units', 'assets', 'nonRetrievableUnits', 'unknowns', 'origins'):
        if isinstance(value.get(key), (list, dict)):
            result[key + 'Count'] = len(value[key])
    return result


def _unit_summary(value):
    if not isinstance(value, dict):
        return {}
    fields = ('id', 'unitId', 'documentId', 'sourceHash', 'projectionSha256', 'originIds',
        'evidenceRef', 'complete', 'status', 'originalRerankOrdinal', 'score')
    return {key: value[key] for key in fields if key in value}


def _request_summary(endpoint, request):
    if endpoint == '/index':
        return _index_summary(request)
    if endpoint == '/normal-observed':
        return {'sourceBinding': request.sourceBinding,
            'candidateGraph': _identity_summary(request.candidateGraph),
            'responseFormat': request.responseFormat, 'recording': 'summary_only'}
    if endpoint in ('/project', '/project/native', '/project/native-collection', '/project/native-pdf', '/project/native-pdf-family'):
        result = {'recording': 'summary_only'}
        descriptors = ('sourceFamilySpecRef', 'internalProjectionDescriptor', 'nativeBundleRef', 'dualBundleRef', 'adoptionManifestRef')
        for name in ('bundle', 'adoptionManifest', 'sourceBinding', *descriptors, 'children', 'sources'):
            value = getattr(request, name, None)
            if isinstance(value, dict):
                result[name] = value if name in descriptors else _identity_summary(value)
            elif isinstance(value, list):
                result[name] = [_identity_summary(item) for item in value]
        return result
    return request.model_dump(exclude_none=True)


def _response_summary(endpoint, result):
    if endpoint not in ('/sources/read', '/retrieve', '/project', '/normal-observed',
            '/project/native', '/project/native-collection', '/project/native-pdf', '/project/native-pdf-family'):
        return result
    fields = ('contract', 'view', 'operation', 'module', 'projectId', 'mode', 'projectionSha256',
        'projectionRevisionSha256', 'canonicalProjections', 'readManifestSha256', 'semanticProjectionVersion',
        'semanticReadVersion', 'semanticReadProfile', 'sourceUnitPolicy', 'coverage', 'blockedOrigin',
        'sourceQualityVerified', 'sourceQualityChangedByRegistry', 'indexSignature', 'seconds',
        'cached', 'chunks', 'assets', 'immutable', 'qualityVerifiedByRegistry', 'responseFormat')
    summary = {key: result[key] for key in fields if key in result}
    summary['recording'] = 'summary_only; complete sources and members remain in canonical, CAS and index storage'
    if 'projection' in result:
        summary['projection'] = _identity_summary(result['projection'])
    if 'sources' in result:
        summary['sources'] = result['sources']
    for name in ('origins', 'units', 'hits'):
        if isinstance(result.get(name), list):
            summary[name] = [_unit_summary(value) for value in result[name]]
    if isinstance(result.get('sourceUnits'), dict):
        units = result['sourceUnits']
        summary['sourceUnits'] = {'policy': units.get('policy'),
            'units': [_unit_summary(value) for value in units.get('units', [])],
            'rawParentTopKIds': units.get('rawParentTopKIds', [])}
    for name in ('groups', 'members'):
        if name in result:
            summary[name + 'Count'] = len(result[name])
    return summary


def _inspect_events(path):
    digest, count, byte_count, malformed = hashlib.sha256(), 0, 0, False
    with path.open('rb') as stream:
        for line in stream:
            digest.update(line)
            byte_count += len(line)
            try:
                event = json.loads(line)
                if not isinstance(event, dict) or event.get('ordinal') != count + 1 or not line.endswith(b'\n'):
                    malformed = True
                else:
                    count += 1
            except (ValueError, UnicodeError):
                malformed = True
    return {'file': path.name, 'count': count, 'sha256': digest.hexdigest(),
            'byteCount': byte_count, 'integrityStatus': 'malformed_or_interrupted' if malformed else 'complete'}


class RequestEvidence:
    def __init__(self, root):
        self.root = Path(root)
        self.active = ContextVar('oer_request_evidence_' + uuid.uuid4().hex, default=None)

    def observe(self, stage, payload):
        record = self.active.get()
        if record is None:
            return
        if stage == 'window-index-upsert':
            payload = {**{key: value for key, value in payload.items() if key != 'vectors'},
                       'vectorCount': len(payload.get('vectors', [])),
                       'recording': 'progress_only; vector integrity remains in the index seal'}
        elif stage == 'dual-granularity-source-plan':
            coverage = payload.get('coverage', {})
            payload = {'pointCount': len(payload.get('points', [])),
                       'contextCount': len(payload.get('contexts', [])),
                       'bindingCount': len(payload.get('bindings', [])),
                       'coverage': {key: value for key, value in coverage.items()
                                    if not isinstance(value, (list, dict))},
                       'contextUnavailableCount': len(coverage.get('contextUnavailable', [])),
                       'recording': 'summary_only; complete plan remains in index metadata'}
        elif stage == 'source-window-plan':
            payload = {key: value for key, value in payload.items() if key != 'windowBindings'}
            payload['recording'] = 'summary_only; complete windows remain in index metadata'
        elif stage == 'parent-bm25-candidates':
            scores = payload.get('allScopeScores', [])
            selected = set(payload.get('orderedCandidateIds', []))
            payload = {key: value for key, value in payload.items() if key != 'allScopeScores'}
            payload.update(scoredScopeCount=len(scores),
                           candidateScores=[row for row in scores if row['id'] in selected],
                           recording='selected_candidates_only')
        elif stage == 'source-unit-closure':
            payload = {'policy': payload.get('policy'),
                'units': [_unit_summary(value) for value in payload.get('units', [])],
                'rawParentTopKIds': payload.get('rawParentTopKIds', []),
                'recording': 'summary_only; complete units remain in canonical and index storage'}
        next_ordinal = record['events'] + 1
        raw = _encode({'ordinal': next_ordinal, 'stage': stage,
                       'elapsedSeconds': time.perf_counter() - record['started'],
                       'payload': payload}) + b'\n'
        with (record['directory'] / 'events.jsonl').open('ab') as stream:
            stream.write(raw)
        record['events'] = next_ordinal

    @contextmanager
    def operation(self, endpoint, request):
        # /index-canonical calls the same guarded /index implementation. Keep
        # one outer run receipt while retaining its exact derived input event.
        if self.active.get() is not None:
            self.observe('nested-operation-input', {'endpoint': endpoint, 'request': request})
            yield None
            return
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
        directory = self.root / (stamp + '-' + uuid.uuid4().hex)
        directory.mkdir(parents=True, exist_ok=False)
        record = {'directory': directory, 'started': time.perf_counter(), 'events': 0,
                  'request': _save(directory / 'request.json', request), 'response': None}
        (directory / 'events.jsonl').touch(exist_ok=False)
        token = self.active.set(record)
        status, error = 'completed', None
        try:
            yield record
        except BaseException as failure:
            status = 'failed'
            error = {'type': type(failure).__name__, 'message': str(failure)}
            for name in ('status_code', 'detail', 'kind', 'code', 'metadata'):
                if hasattr(failure, name):
                    error[name] = getattr(failure, name)
            raise
        finally:
            self.active.reset(token)
            _save(directory / 'receipt.json', {
                'schema': 'consense.oer-request-evidence.v1', 'endpoint': endpoint,
                'createdAt': stamp, 'terminalStatus': status, 'error': error,
                'elapsedSeconds': time.perf_counter() - record['started'],
                'request': record['request'], 'response': record['response'],
                'events': _inspect_events(directory / 'events.jsonl'),
                'qualityCertified': False, 'humanConfirmed': False})

    def wrap(self, endpoint, operation):
        @wraps(operation)
        def recorded(request):
            value = _request_summary(endpoint, request)
            with self.operation(endpoint, value) as record:
                result = operation(request)
                if record is not None:
                    record['response'] = _save(record['directory'] / 'response.json', _response_summary(endpoint, result))
                return result
        return recorded
