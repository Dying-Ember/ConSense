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
import os
from pathlib import Path
import re
import stat
import threading
import time
import uuid


def _encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def _same_json(left, right):
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(_same_json(left[key], right[key]) for key in left)
    if isinstance(left, list):
        return len(left) == len(right) and all(_same_json(a, b) for a, b in zip(left, right))
    return left == right


def _publication_intent_version(intent):
    if not isinstance(intent, dict):
        return None
    common = {'projectId', 'generation', 'metadataSha256', 'priorMetadataSha256'}
    if set(intent) == common | {'sourceFrameVerified'} and type(intent['sourceFrameVerified']) is bool:
        return 1
    if (set(intent) == common | {'schema', 'sourceFramePhase'}
            and intent['schema'] == 'consense.index-publication-intent.v2'
            and intent['sourceFramePhase'] == 'final_checks_passed_before_lease_close'):
        return 2
    return None


def _sync_directory(path):
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _sync_file(target):
    if hasattr(target, 'fileno'):
        os.fsync(target.fileno())
    else:
        with target.open('rb') as stream:
            os.fsync(stream.fileno())


def _sync_open_file(stream):
    _sync_file(stream)


def _save(path, value, *, durable=False):
    raw = _encode(value)
    with path.open('xb') as stream:
        stream.write(raw)
        if durable:
            stream.flush()
            os.fsync(stream.fileno())
    if durable:
        _sync_directory(path.parent)
    return {'file': path.name, 'sha256': hashlib.sha256(raw).hexdigest(),
            'byteCount': len(raw)}


def _clear_block(block, value):
    try:
        block.unlink()
        _sync_directory(block.parent)
    except BaseException:
        if not block.exists() and not block.is_symlink():
            try:
                _save(block, value, durable=True)
            except OSError:
                pass
        raise


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
    result = request.model_dump(exclude_none=True)
    if endpoint == '/index-canonical' and 'initializationDecision' not in request.model_fields_set:
        result.pop('initializationDecision', None)
    return result


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


def _inspect_events_stream(stream, name):
    digest, count, byte_count, malformed = hashlib.sha256(), 0, 0, False
    stream.seek(0)
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
    return {'file': name, 'count': count, 'sha256': digest.hexdigest(),
            'byteCount': byte_count, 'integrityStatus': 'malformed_or_interrupted' if malformed else 'complete'}


def _inspect_events(path):
    with path.open('rb') as stream:
        return _inspect_events_stream(stream, path.name)


class _PinnedPublication:
    """Hold the verified files through sync and the final consistency checks.

    This detects changes; it is not a cross-process exclusion or a trusted directory.
    """
    def __init__(self):
        self.files = []

    @staticmethod
    def _identity(value):
        return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)

    @classmethod
    def _matches_path(cls, fd_stat, path_stat):
        # Windows fd and path ctime semantics differ after rename.
        fields = 4 if os.name == 'nt' else 5
        return cls._identity(fd_stat)[:fields] == cls._identity(path_stat)[:fields]

    def read(self, path, *, events=False):
        path = Path(path)
        flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0)
        fd = os.open(path, flags)
        try:
            stream = os.fdopen(fd, 'rb')
        except BaseException:
            os.close(fd)
            raise
        self.files.append({'path': path, 'stream': stream})
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or path.is_symlink():
            raise ValueError('Publication file is not a regular no-follow file')
        if events:
            value = _inspect_events_stream(stream, path.name)
            sha = value['sha256']
        else:
            value = stream.read()
            sha = hashlib.sha256(value).hexdigest()
        after = os.fstat(stream.fileno())
        current = path.stat(follow_symlinks=False)
        if (path.is_symlink() or self._identity(before) != self._identity(after)
                or not self._matches_path(after, current)):
            raise ValueError('Publication file changed while reading')
        self.files[-1].update(identity=self._identity(after), sha=sha)
        return value

    def check(self):
        for entry in self.files:
            stream, path = entry['stream'], entry['path']
            before = os.fstat(stream.fileno())
            stream.seek(0)
            digest = hashlib.sha256()
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
            after = os.fstat(stream.fileno())
            current = path.stat(follow_symlinks=False)
            if (path.is_symlink() or self._identity(before) != entry['identity']
                    or self._identity(after) != entry['identity'] or not self._matches_path(after, current)
                    or digest.hexdigest() != entry['sha']):
                raise ValueError('Publication file changed after verification')

    def sync(self):
        for entry in self.files:
            _sync_open_file(entry['stream'])

    def close(self):
        error = None
        for entry in reversed(self.files):
            try:
                entry['stream'].close()
            except BaseException as caught:
                if error is None:
                    error = caught
        self.files.clear()
        if error is not None:
            raise error

    def __enter__(self):
        return self

    def __exit__(self, kind, error, traceback):
        try:
            self.close()
        except OSError as failure:
            from token_windows import WindowError
            raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Publication file close failed') from failure


def _locked(operation):
    @wraps(operation)
    def guarded(self, *args, **kwargs):
        with self._lock:
            return operation(self, *args, **kwargs)
    return guarded


class RequestEvidence:
    def __init__(self, root):
        self.root = Path(root)
        self.active = ContextVar('oer_request_evidence_' + uuid.uuid4().hex, default=None)
        self._verified = {}
        self._uncertain = set()
        self._lock = threading.RLock()

    def block_path(self, project):
        return self.root / ('publication-' + hashlib.sha256(_encode(str(project))).hexdigest() + '.block')

    @_locked
    def prepare_prewrite(self, record, binding):
        from token_windows import WindowError
        sha = lambda value: isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value)
        fields = {'projectId', 'generation', 'collection', 'priorMetadataSha256', 'module',
                  'sourceScopeSha256', 'routeIdentity'}
        if (not isinstance(binding, dict) or set(binding) != fields
                or not isinstance(binding['projectId'], str) or not binding['projectId'] or len(binding['projectId']) > 200
                or not isinstance(binding['generation'], str) or not re.fullmatch('[a-f0-9]{32}', binding['generation'])
                or not isinstance(binding['collection'], str) or not binding['collection']
                or binding['module'] not in ('drafting', 'vetting', 'advisory')
                or not sha(binding['sourceScopeSha256']) or not sha(binding['routeIdentity'])
                or not (binding['priorMetadataSha256'] is None or sha(binding['priorMetadataSha256']))
                or record.get('publication') is not None):
            raise WindowError('INDEX_PUBLICATION_INTENT_INVALID', 'Exact owned prewrite binding is required')
        with _PinnedPublication() as pinned:
            raw = pinned.read(record['directory'] / 'request.json')
            descriptor = record['request']
            request = json.loads(raw)
            if (descriptor != {'file': 'request.json', 'sha256': hashlib.sha256(raw).hexdigest(), 'byteCount': len(raw)}
                    or not isinstance(request, dict) or type(request.get('projectId')) not in (int, str)
                    or str(request['projectId']) != binding['projectId']):
                raise WindowError('INDEX_PUBLICATION_INTENT_INVALID', 'Prewrite project differs from the journal request')
            pinned.check()
        _sync_file(record['directory'] / 'request.json')
        _sync_file(record['directory'] / 'events.jsonl')
        _sync_directory(record['directory'])
        _sync_directory(self.root)
        publication = {'journalId': record['directory'].name, 'prewrite': dict(binding), 'request': record['request']}
        record['publication'] = publication
        _save(self.block_path(binding['projectId']), publication, durable=True)

    @_locked
    def prepare_intent(self, record, intent):
        from token_windows import WindowError
        version = _publication_intent_version(intent)
        sha = lambda value: isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value)
        if (version is None or not isinstance(intent['projectId'], str)
                or not intent['projectId'] or len(intent['projectId']) > 200
                or not isinstance(intent['generation'], str) or not re.fullmatch('[a-f0-9]{32}', intent['generation'])
                or not sha(intent['metadataSha256']) or not (intent['priorMetadataSha256'] is None or sha(intent['priorMetadataSha256']))):
            raise WindowError('INDEX_PUBLICATION_INTENT_INVALID', 'Exact owned index publication intent is required')
        current = record.get('publication')
        if version == 2 and (current is None or 'prewrite' not in current):
            raise WindowError('INDEX_PUBLICATION_INTENT_INVALID', 'Version 2 requires its own prewrite binding')
        if current is not None:
            prewrite = current.get('prewrite', {})
            if ('intent' in current or any(prewrite.get(key) != intent[key]
                    for key in ('projectId', 'generation', 'priorMetadataSha256'))):
                raise WindowError('INDEX_PUBLICATION_INTENT_INVALID', 'Final intent differs from prewrite binding')
            with _PinnedPublication() as pinned:
                if json.loads(pinned.read(self.block_path(intent['projectId']))) != current:
                    raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Prewrite block identity differs')
                _save(record['directory'] / 'publication-intent.json', intent, durable=True)
                pinned.check()
            current['intent'] = dict(intent)
            return
        _sync_file(record['directory'] / 'request.json')
        _sync_file(record['directory'] / 'events.jsonl')
        _sync_directory(record['directory'])
        _sync_directory(self.root)
        block = self.block_path(intent['projectId'])
        publication = {'journalId': record['directory'].name, 'intent': intent, 'request': record['request']}
        record['publication'] = publication
        _save(block, publication, durable=True)

    def metadata_path(self, project):
        digest = hashlib.sha256(_encode(str(project))).hexdigest()
        return self.root.parent / 'projects' / (digest + '.json')

    def _verify_publication_evidence(self, metadata, request, response, receipt, pinned):
        from model_session_receipt import verify_model_session_receipt
        from token_windows import WindowError

        def reject(message):
            raise WindowError('INDEX_PUBLICATION_EVIDENCE_UNAVAILABLE', message)

        try:
            publication = receipt['publication']
            scope = metadata['sourceScope']
            prewrite = publication['prewrite']
            if (_publication_intent_version(publication['intent']) != 2
                    or not isinstance(scope, dict)
                    or scope.get('sourceScopeSha256') != prewrite['sourceScopeSha256']
                    or scope.get('projectId') != prewrite['projectId']
                    or scope.get('module') != prewrite['module']):
                reject('Publication is not bound to a completed source scope')
            streams = [entry['stream'] for entry in pinned.files
                       if entry['path'].name == 'events.jsonl']
            if len(streams) != 1:
                reject('Pinned publication events are unavailable')
            stream = streams[0]
            stream.seek(0)
            source_events, model_events = [], []
            for line in stream:
                event = json.loads(line)
                if event.get('stage') == 'source-frame-verified':
                    source_events.append(event)
                elif event.get('stage') == 'model-release-verified':
                    model_events.append(event)
            if (len(source_events) != 1 or len(model_events) != 1
                    or model_events[0]['ordinal'] >= source_events[0]['ordinal']):
                reject('One ordered source frame and model release witness is required')
            source, model = source_events[0]['payload'], model_events[0]['payload']
            common = {'sourceScopeSha256': scope['sourceScopeSha256'],
                      'sourceDeclarationSha256': scope['sourceDeclarationSha256']}
            if (source != {'schema': 'consense.source-frame-evidence.v1',
                           'phase': 'final_checks_passed_before_lease_close', **common}
                    or not isinstance(model, dict)
                    or set(model) != {'schema', 'sourceScopeSha256', 'sourceDeclarationSha256',
                                      'profileSha256', 'executionSha256', 'bindings', 'records',
                                      'receipts', 'statuses'}
                    or model['schema'] != 'consense.model-release-evidence.v1'
                    or any(model.get(key) != value for key, value in common.items())
                    or not isinstance(model['bindings'], dict)
                    or set(model['bindings']) != {'embedding'}
                    or not isinstance(model['records'], list) or not model['records']
                    or not isinstance(model['receipts'], list) or len(model['receipts']) != 1
                    or not isinstance(model['statuses'], dict)):
                reject('Model release witness differs from the published source scope')
            binding = model['bindings']['embedding']
            model_receipt = model['receipts'][0]
            operation = model_receipt['operationId']
            records = model['records']
            if (not isinstance(binding, dict)
                    or binding.get('sourceScopeSha256') != scope['sourceScopeSha256']
                    or not isinstance(operation, str) or not re.fullmatch('[a-f0-9]{32}', operation)
                    or any(not isinstance(row, dict)
                           or set(row) != {'scopeOrdinal', 'ordinal', 'modelKind', 'operationId',
                                           'inputSha256', 'outputSha256', 'inputBytes', 'outputBytes'}
                           or row.get('modelKind') != 'embedding'
                           or row.get('operationId') != operation or row.get('scopeOrdinal') != ordinal
                           for ordinal, row in enumerate(records))
                    or set(model['statuses']) != {operation}
                    or not isinstance(model['profileSha256'], str)
                    or not re.fullmatch('[a-f0-9]{64}', model['profileSha256'])
                    or not isinstance(model['executionSha256'], str)
                    or not re.fullmatch('[a-f0-9]{64}', model['executionSha256'])):
                reject('Model operation or independent observation identity differs')
            descriptors = [{key: row[key] for key in
                            ('ordinal', 'inputSha256', 'outputSha256', 'inputBytes', 'outputBytes')}
                           for row in records]
            verify_model_session_receipt(model_receipt, expected_stage='document-embedding',
                expected_binding=binding, expected_profile_sha256=model['profileSha256'],
                expected_operation_id=operation, batch_descriptors=descriptors)
            status = model['statuses'][operation]
            expected = {'operationId': operation, 'stage': 'document-embedding',
                        'status': 'completed', 'configIdentityScope': 'config_file_bytes',
                        'bindingSha256': model_receipt['bindingSha256'],
                        'profileSha256': model['profileSha256'],
                        'bodySha256': hashlib.sha256(b'{}').hexdigest(),
                        'sourceSha256': model_receipt['sourceSha256'],
                        'modelSummarySha256': model_receipt['modelSummarySha256'],
                        'release': model_receipt['release']}
            if (not isinstance(status, dict)
                    or any(status.get(key) != value for key, value in expected.items())
                    or not isinstance(status.get('configSha256'), str)
                    or not re.fullmatch('[a-f0-9]{64}', status['configSha256'])):
                reject('Persisted worker terminal status differs from the verified model receipt')
            return {'sourceEvidenceSha256': hashlib.sha256(_encode(source)).hexdigest(),
                    'modelReleaseEvidenceSha256': hashlib.sha256(_encode(model)).hexdigest()}
        except (OSError, KeyError, TypeError, ValueError, RecursionError) as error:
            if isinstance(error, WindowError):
                raise
            raise WindowError('INDEX_PUBLICATION_EVIDENCE_UNAVAILABLE',
                              'Source or model release witness could not be verified') from error

    def _validated_proof(self, metadata, request, response, receipt, pinned):
        proof = self._verify_publication_evidence(metadata, request, response, receipt, pinned)
        if (not isinstance(proof, dict) or set(proof) != {'sourceEvidenceSha256', 'modelReleaseEvidenceSha256'}
                or any(not isinstance(value, str) or not re.fullmatch('[a-f0-9]{64}', value) for value in proof.values())):
            from token_windows import WindowError
            raise WindowError('INDEX_PUBLICATION_EVIDENCE_UNAVAILABLE', 'Exact verified source and model release identities are required')
        return proof

    @contextmanager
    def _validate_publication(self, path, metadata, project, receipt):
        from token_windows import WindowError
        def reject(message):
            raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', message)
        # The caller has already checked the journal identifier and path.
        journal_id = (receipt.get('publication', {}).get('journalId') if receipt is not None
                      else metadata.get('publicationJournal') if isinstance(metadata, dict) else None)
        if not isinstance(journal_id, str) or not re.fullmatch(r'\d{8}T\d{6}\.\d{6}Z-[a-f0-9]{32}', journal_id):
            reject('Publication journal identity is invalid')
        directory = self.root / journal_id
        if (directory.is_symlink() or not directory.is_dir() or Path(path) != self.metadata_path(project)):
            reject('Publication path or journal identity differs')
        with _PinnedPublication() as pinned:
            try:
                receipt_raw = pinned.read(directory / 'receipt.json')
                actual_receipt = json.loads(receipt_raw)
                if receipt is not None and actual_receipt != receipt:
                    reject('Publication receipt differs')
                receipt = actual_receipt
                publication = receipt['publication']
                intent = publication['intent']
                intent_version = _publication_intent_version(intent)
                sha = lambda value: isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value)
                if (publication['journalId'] != journal_id or intent_version is None
                        or not isinstance(intent['generation'], str) or not re.fullmatch('[a-f0-9]{32}', intent['generation'])
                        or not sha(intent['metadataSha256'])
                        or not (intent['priorMetadataSha256'] is None or sha(intent['priorMetadataSha256']))
                        or receipt.get('endpoint') not in ('/index', '/index-canonical')
                        or receipt.get('schema') != 'consense.oer-request-evidence.v1'):
                    reject('Publication intent is invalid')
                raw = pinned.read(path)
                actual_metadata = json.loads(raw)
                prewrite = publication.get('prewrite')
                if intent_version == 2 and prewrite is None:
                    reject('Version 2 publication requires prewrite binding')
                if prewrite is not None:
                    final_intent = json.loads(pinned.read(directory / 'publication-intent.json'))
                    scope = actual_metadata.get('sourceScope') if isinstance(actual_metadata, dict) else None
                    if (not _same_json(final_intent, intent) or not isinstance(prewrite, dict)
                            or set(publication) != {'journalId', 'request', 'prewrite', 'intent'}
                            or set(prewrite) != {'projectId', 'generation', 'collection', 'priorMetadataSha256',
                                                'module', 'sourceScopeSha256', 'routeIdentity'}
                            or prewrite.get('module') not in ('drafting', 'vetting', 'advisory')
                            or not sha(prewrite.get('routeIdentity')) or not sha(prewrite.get('sourceScopeSha256'))
                            or any(prewrite.get(key) != intent[key] for key in ('projectId', 'generation', 'priorMetadataSha256'))
                            or prewrite.get('collection') != actual_metadata.get('collection')
                            or not isinstance(scope, dict) or prewrite.get('module') != scope.get('module')
                            or scope.get('projectId') != prewrite.get('projectId')
                            or prewrite.get('sourceScopeSha256') != scope.get('sourceScopeSha256')):
                        reject('Prewrite binding or final intent differs')
                reserved = {'_metadataFileIdentity', '_metadataFileStamp'}
                if (not isinstance(actual_metadata, dict) or reserved.intersection(actual_metadata)
                        or (metadata is not None and (not isinstance(metadata, dict)
                            or not _same_json({key: value for key, value in metadata.items() if key not in reserved}, actual_metadata)))
                        or receipt['terminalStatus'] != 'completed'
                        or intent_version == 1 and intent['sourceFrameVerified'] is not True
                        or intent['projectId'] != str(project) or actual_metadata.get('projectId') != str(project)
                        or intent['generation'] != actual_metadata.get('generation')
                        or actual_metadata.get('publicationJournal') != journal_id
                        or hashlib.sha256(raw).hexdigest() != intent['metadataSha256']):
                    reject('Publication identity or metadata bytes differ')
                values = {}
                for name in ('request', 'response'):
                    descriptor = receipt[name]
                    if not isinstance(descriptor, dict) or descriptor['file'] != name + '.json':
                        reject('Publication request or response is unavailable')
                    raw = pinned.read(directory / descriptor['file'])
                    if len(raw) != descriptor['byteCount'] or hashlib.sha256(raw).hexdigest() != descriptor['sha256']:
                        reject('Publication request or response changed')
                    values[name] = json.loads(raw)
                events = pinned.read(directory / 'events.jsonl', events=True)
                if (not isinstance(values['request'], dict) or not isinstance(values['response'], dict)
                        or publication['request'] != receipt['request']
                        or str(values['request'].get('projectId')) != str(project)
                        or str(values['response'].get('projectId')) != str(project)
                        or events != receipt['events'] or receipt['events']['integrityStatus'] != 'complete'):
                    reject('Publication request, response or event binding differs')
                yield actual_metadata, values['request'], values['response'], receipt, hashlib.sha256(receipt_raw).hexdigest(), pinned
            except (OSError, ValueError) as error:
                if isinstance(error, WindowError):
                    raise
                raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Publication files could not be verified') from error

    @_locked
    def _release_after_completion(self, block, receipt):
        publication = receipt['publication']
        project = publication['intent']['projectId']
        self._verified.pop(project, None)
        self._uncertain.add(project)
        path = self.metadata_path(project)
        directory = self.root / publication['journalId']
        with self._validate_publication(path, None, project, receipt) as (actual, request, response, _, receipt_sha, pinned):
            expected_block = {key: value for key, value in publication.items() if key != 'intent'} if 'prewrite' in publication else publication
            if json.loads(pinned.read(block)) != expected_block:
                from token_windows import WindowError
                raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Publication block identity differs')
            proof = self._validated_proof(actual, request, response, receipt, pinned)
            pinned.check()
            pinned.sync()
            _sync_directory(path.parent)
            _sync_directory(directory)
            _save(directory / 'publication-proof.json', {'journalId': publication['journalId'],
                'projectId': project, 'metadataSha256': publication['intent']['metadataSha256'],
                'request': receipt['request'], 'response': receipt['response'],
                'receiptSha256': receipt_sha, 'proof': proof}, durable=True)
            pinned.check()
        _clear_block(block, expected_block)
        self._verified[project] = (publication['journalId'], publication['intent']['generation'],
                                   publication['intent']['metadataSha256'], receipt_sha)
        self._uncertain.discard(project)
        return True

    @_locked
    def recover_index(self, path, metadata, project):
        from token_windows import WindowError
        project = str(project)
        if project in self._verified:
            raise WindowError('INDEX_PUBLICATION_RECOVERY_CONFLICT', 'Index is already verified in this process')
        self._verified.pop(project, None)
        self._uncertain.add(project)
        block = self.block_path(project)
        journal_id = metadata.get('publicationJournal') if isinstance(metadata, dict) else None
        if not isinstance(journal_id, str) or not re.fullmatch(r'\d{8}T\d{6}\.\d{6}Z-[a-f0-9]{32}', journal_id):
            raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Recovery journal identity is invalid')
        try:
            if not block.exists() and not block.is_symlink():
                _save(block, {'recovery': journal_id, 'projectId': project}, durable=True)
            elif block.is_symlink():
                raise ValueError('Recovery block path is unsafe')
            directory = self.root / journal_id
            if directory.is_symlink() or not directory.is_dir() or (directory / 'receipt.json').is_symlink():
                raise ValueError('Recovery journal path is unsafe')
            with self._validate_publication(path, metadata, project, None) as (actual, request, response, receipt, receipt_sha, pinned):
                publication = receipt['publication']
                expected_block = ({key: value for key, value in publication.items() if key != 'intent'}
                                  if 'prewrite' in publication else publication)
                saved_block = json.loads(pinned.read(block))
                if saved_block not in (expected_block, {'recovery': journal_id, 'projectId': project}):
                    raise ValueError('Recovery block identity differs')
                _sync_open_file(pinned.files[-1]['stream'])
                _sync_directory(block.parent)
                proof = self._validated_proof(actual, request, response, receipt, pinned)
                pinned.check()
                pinned.sync()
                _sync_directory(Path(path).parent)
                _sync_directory(directory)
                decision = {'schema': 'consense.index-recovery-decision.v1', 'journalId': journal_id,
                            'projectId': project, 'generation': actual['generation'],
                            'metadataSha256': receipt['publication']['intent']['metadataSha256'],
                            'request': receipt['request'], 'response': receipt['response'],
                            'receiptSha256': receipt_sha, 'proof': proof}
                _save(directory / ('recovery-' + uuid.uuid4().hex + '.json'), decision, durable=True)
                pinned.check()
            _clear_block(block, saved_block)
            self._verified[project] = (journal_id, actual['generation'], decision['metadataSha256'], decision['receiptSha256'])
            self._uncertain.discard(project)
            return decision
        except (OSError, KeyError, TypeError, ValueError, RecursionError) as error:
            if isinstance(error, WindowError):
                raise
            raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Index recovery could not be verified') from error

    @_locked
    def block_after_frame_failure(self, project):
        """Retain a durable block when the source frame fails after recovery."""
        from token_windows import WindowError
        project = str(project)
        verified = self._verified.pop(project, None)
        self._uncertain.add(project)
        if verified is None:
            return
        block = self.block_path(project)
        if block.is_symlink():
            raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Recovery block path is unsafe')
        if not block.exists():
            _save(block, {'recovery': verified[0], 'projectId': project}, durable=True)

    @_locked
    def require_verified_index_for_load(self, project):
        """Reject an unverified existing index before loading its metadata."""
        from token_windows import WindowError
        self.verify_index(self.metadata_path(project), None, project)
        if str(project) not in self._verified:
            raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'No independently verified publication release')

    @_locked
    def verify_index(self, path, metadata, project):
        from token_windows import WindowError
        def reject(message):
            raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', message)
        block = self.block_path(project)
        if block.exists() or block.is_symlink():
            reject('Index publication remains blocked')
        if str(project) in self._uncertain:
            reject('Index publication release remains uncertain')
        if metadata is None:
            return None
        journal_id = metadata.get('publicationJournal') if isinstance(metadata, dict) else None
        if not isinstance(journal_id, str) or not re.fullmatch(r'\d{8}T\d{6}\.\d{6}Z-[a-f0-9]{32}', journal_id):
            reject('Publication journal identity is invalid')
        if str(project) not in self._verified:
            reject('No independently verified publication release')
        try:
            directory = self.root / journal_id
            if directory.is_symlink() or not directory.is_dir():
                reject('Publication journal directory is unavailable')
            receipt_path = directory / 'receipt.json'
            if receipt_path.is_symlink():
                reject('Publication receipt path is unsafe')
            with self._validate_publication(path, metadata, project, None) as (_, _, _, receipt, receipt_sha, pinned):
                intent = receipt['publication']['intent']
                if self._verified.get(str(project)) != (journal_id, intent['generation'], intent['metadataSha256'], receipt_sha):
                    reject('No independently verified publication release')
                pinned.check()
            return receipt
        except (OSError, KeyError, TypeError, ValueError, RecursionError) as error:
            if isinstance(error, WindowError):
                raise
            raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Publication journal cannot be verified') from error

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
                  'request': _save(directory / 'request.json', request), 'response': None, 'publication': None}
        (directory / 'events.jsonl').touch(exist_ok=False)
        token = self.active.set(record)
        status, error = 'completed', None
        try:
            if endpoint in ('/index', '/index-canonical'):
                from source_request_context import bind_journal
                bind_journal(directory.name, lambda intent: self.prepare_intent(record, intent),
                             lambda binding: self.prepare_prewrite(record, binding))
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
            publication = record['publication']
            if publication is not None and record['response'] is not None:
                _sync_file(directory / 'response.json')
            if publication is not None:
                _sync_file(directory / 'events.jsonl')
            receipt = {
                'schema': 'consense.oer-request-evidence.v1', 'endpoint': endpoint,
                'createdAt': stamp, 'terminalStatus': status, 'error': error,
                'elapsedSeconds': time.perf_counter() - record['started'],
                'request': record['request'], 'response': record['response'],
                'events': _inspect_events(directory / 'events.jsonl'),
                'qualityCertified': False, 'humanConfirmed': False,
                **({'publication': publication} if publication is not None else {})}
            _save(directory / 'receipt.json', receipt, durable=publication is not None)
            if publication is not None and status == 'completed':
                if 'intent' not in publication:
                    from token_windows import WindowError
                    raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Prewrite remains blocked without a final intent')
                block = self.block_path(publication['intent']['projectId'])
                if not self._release_after_completion(block, receipt):
                    from token_windows import WindowError
                    raise WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN',
                                      'Index remains blocked pending an independently verified release')

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
