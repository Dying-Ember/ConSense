"""Seal complete application observations without manufacturing source review.

CAS proves retained observation bytes. Java's current-source reader owns the
original-byte/generation binding before publication and on every consumption.
"""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

from artifact_store import ArtifactStore
from competition_scope import MODULES, SOURCE_PURPOSES
from native_observation import _strict_json
from oer_source_registry import SourceRegistryError, _identity, _serialize
from token_windows import paragraph_map, utf16_length


MAX_NORMAL_MESSAGE_BYTES = 1024 * 1024 * 1024
SCHEMA = 'consense.normal-observed.v1'
PRODUCER = 'normal_observed'
VERSION = 'complete-bound-normal-observations-v1'
GRAPH_SCHEMA = 'consense.canonical-candidate.v1'
GRAPH_VERSION = 'actual-normal-source-unit-whole-page-observations-v3'
LEGACY_GRAPH_VERSION = 'actual-normal-source-unit-whole-page-observations-v2'
OBSERVER = {'actor': 'application', 'method': 'normal_parser_observation',
            'originalByteVerifier': 'java_current_source_reader'}
DECLARATIONS = {'adoptionStatus': 'candidate_unverified', 'humanConfirmed': False,
                'sourceQualityAccepted': False, 'semanticScopeVerified': False, 'legalClosure': 'unknown'}
BINDING_FIELDS = {'module', 'eligibleModules', 'sourcePurpose', 'sourceClass',
                  'routingSha256', 'scopePolicySha256'}


def _fail(condition, code, message):
    if not condition:
        raise SourceRegistryError(code, message)


def _hash(value):
    digest = hashlib.sha256()
    for chunk in _canonical_chunks(value):
        digest.update(chunk)
    return digest.hexdigest()


def _canonical_chunks(value):
    encoder = json.JSONEncoder(ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
    return (chunk.encode('utf-8') for chunk in encoder.iterencode(value))


def _serialized_equal(left, right):
    """Exact canonical bytes, without two whole-document encoded copies."""
    streams = iter(_canonical_chunks(left)), iter(_canonical_chunks(right))
    a, b = next(streams[0], b''), next(streams[1], b'')
    while a or b:
        if not a or not b:
            return False
        size = min(len(a), len(b))
        if a[:size] != b[:size]:
            return False
        a, b = a[size:], b[size:]
        if not a:
            a = next(streams[0], b'')
        if not b:
            b = next(streams[1], b'')
    return True


def _matches_serialized_bytes(value, raw):
    offset = 0
    for chunk in _canonical_chunks(value):
        if not raw.startswith(chunk, offset):
            return False
        offset += len(chunk)
    return offset == len(raw)


def _paragraph(graph, value):
    if not isinstance(value, dict):
        return value
    marker = next((key for key in ('$nativeParagraph', '$nativeSection') if key in value), None)
    if marker is None:
        return value
    ordinal = value[marker]
    if marker == '$nativeParagraph':
        outline = graph.get('nativeDocxOutline')
        pool = outline.get('paragraphs', []) if isinstance(outline, dict) else []
        code = 'NORMAL_OBSERVED_PARAGRAPH_REF_INVALID'
    else:
        pool = graph.get('nativeDocxSections', [])
        code = 'NORMAL_OBSERVED_SECTION_REF_INVALID'
    _fail(graph.get('producerVersion') == GRAPH_VERSION and set(value) == {marker}
          and isinstance(pool, list) and type(ordinal) is int and 0 <= ordinal < len(pool)
          and isinstance(pool[ordinal], dict)
          and (marker != '$nativeParagraph' or isinstance(pool[ordinal].get('sourcePosition'), dict)),
          code, 'A native context reference must resolve in its bound source inventory')
    stack, visited = [pool[ordinal]], set()
    while stack:
        node = stack.pop()
        if isinstance(node, (dict, list)):
            if id(node) in visited:
                continue
            visited.add(id(node))
        if isinstance(node, dict):
            _fail('$nativeParagraph' not in node and '$nativeSection' not in node, code,
                  'The native context inventory cannot contain nested references')
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)
    return pool[ordinal]


def _expand_paragraph_contexts(graph, value):
    if graph.get('producerVersion') != GRAPH_VERSION:
        return copy.deepcopy(value)
    resolved = _paragraph(graph, value)
    if resolved is not value:
        return copy.deepcopy(resolved)
    if isinstance(value, dict):
        return {key: _expand_paragraph_contexts(graph, child) for key, child in value.items()}
    if isinstance(value, list):
        return [_expand_paragraph_contexts(graph, child) for child in value]
    return value


def _validate_paragraph_references(graph):
    if graph.get('producerVersion') != GRAPH_VERSION:
        return
    outline = graph.get('nativeDocxOutline') or {}
    _fail(isinstance(outline, dict), 'NORMAL_OBSERVED_PARAGRAPH_REF_INVALID', 'The paragraph inventory must be an object')
    scopes = outline.get('sourceClauseScopes') or {}
    _fail(isinstance(scopes, dict), 'NORMAL_OBSERVED_PARAGRAPH_REF_INVALID', 'The source scopes must be an object')
    containers = [member.get('paragraphProperties') for member in scopes.get('members', [])
                  if isinstance(member, dict)]
    for unit in graph.get('units', []):
        if not isinstance(unit, dict):
            continue
        for part in unit.get('parts', []):
            if not isinstance(part, dict):
                continue
            context = part.get('readingContext') or {}
            if not isinstance(context, dict):
                continue
            containers.append(context.get('nativeDocxParagraph'))
            containers.append(context.get('nativeDocxParagraphs'))
            containers.append(context.get('nativeDocxSection'))
    visited = set()
    while containers:
        value = containers.pop()
        if isinstance(value, (dict, list)):
            if id(value) in visited:
                continue
            visited.add(id(value))
        if isinstance(value, dict):
            if '$nativeParagraph' in value or '$nativeSection' in value:
                _paragraph(graph, value)
            else:
                containers.extend(value.values())
        elif isinstance(value, list):
            containers.extend(value)


def normal_observed_candidate(value):
    return (value.get('schema') == SCHEMA or value.get('producerKind') == PRODUCER
        or value.get('projectionVersion') == VERSION or value.get('metadataVersion') == VERSION
        or value.get('addressKind') == 'normal_observed_unit'
        or 'observationManifestSha256' in value or 'observationManifestRef' in value
        or isinstance(value.get('unitId'), str) and value['unitId'].startswith('candidate-unit:')
        or any(isinstance(part, dict) and part.get('extractionSource') == 'normal_observed_candidate'
               for part in value.get('parts') or []))


def _name(value):
    return isinstance(value, str) and bool(value.strip())


def _unknowns(value):
    return isinstance(value, list) and all(isinstance(row, dict) for row in value)


def _validate_manifest(manifest):
    _fail(isinstance(manifest, dict) and set(manifest) == {'candidateGraph', 'sourceBinding', 'observer'}
          and manifest['observer'] == OBSERVER, 'NORMAL_OBSERVED_AUTHORITY_MISMATCH',
          'Only explicit application normal-parser observations are supported')
    graph, binding = manifest['candidateGraph'], manifest['sourceBinding']
    _fail(isinstance(graph, dict) and graph.get('schema') == GRAPH_SCHEMA
          and graph.get('producerVersion') in (GRAPH_VERSION, LEGACY_GRAPH_VERSION)
          and graph.get('status') == 'actual_source_candidate_generation'
          and all(graph.get(key) == value and type(graph.get(key)) is type(value)
                  for key, value in DECLARATIONS.items()), 'NORMAL_OBSERVED_GRAPH_AUTHORITY_INVALID',
          'A complete bound candidate generation must remain unreviewed and unaccepted')
    _fail(isinstance(binding, dict) and set(binding) == BINDING_FIELDS,
          'NORMAL_OBSERVED_BINDING_INVALID', 'Provide the complete explicit routed source binding')
    module, eligible = binding['module'], binding['eligibleModules']
    _fail(module in MODULES and isinstance(eligible, list) and eligible
          and all(isinstance(item, str) and item in MODULES for item in eligible)
          and len(eligible) == len(set(eligible)) and module in eligible
          and binding['sourcePurpose'] in SOURCE_PURPOSES and _name(binding['sourceClass']),
          'NORMAL_OBSERVED_MODULE_INVALID', 'The current module must be explicitly source-eligible')
    for key in ('routingSha256', 'scopePolicySha256'):
        _identity(binding[key], key)
    source = graph.get('source')
    _fail(isinstance(source, dict) and all(_name(source.get(key)) for key in (
        'projectId', 'documentId', 'filename', 'role')) and 'scope' in source
        and (source['scope'] is None or _name(source['scope']))
        and source['role'] in ('tender', 'standard', 'project_fact', 'package_manifest'),
        'NORMAL_OBSERVED_SOURCE_INVALID', 'A normal generation requires its exact source identity and role')
    for key in ('originalSourceSha256', 'parsedSourceHash', 'identitySha256'):
        _identity(source.get(key), key)
    for key in ('coverageSha256', 'generationSha256'):
        _identity(graph.get(key), key)
    _fail(_unknowns(graph.get('unknowns')) and isinstance(graph.get('units'), list) and graph['units']
          and isinstance(graph.get('relations'), list), 'NORMAL_OBSERVED_GRAPH_INCOMPLETE',
          'Keep complete source units, relations and explicit unknowns')
    _validate_paragraph_references(graph)
    unit_ids, block_ids, previous_order = set(), set(), -1
    for unit in graph['units']:
        _fail(isinstance(unit, dict), 'NORMAL_OBSERVED_UNIT_INVALID', 'Source units must be objects')
        unit_id = unit.get('unitId')
        _fail(_name(unit_id) and unit_id.startswith('candidate-unit:') and unit_id not in unit_ids,
              'NORMAL_OBSERVED_UNIT_INVALID', 'Each actual unit must have one distinct bound identity')
        _identity(unit_id.removeprefix('candidate-unit:'), 'unitId')
        unit_ids.add(unit_id)
        order = unit.get('sourceOrder')
        _fail(type(order) is int and order > previous_order
              and unit.get('status') == 'observed_structure_candidate'
              and unit.get('adoptionStatus') == 'candidate_unverified'
              and unit.get('semanticScopeVerified') is False
              and unit.get('humanConfirmed', False) is False
              and unit.get('qualifiersComplete') == 'unknown'
              and _unknowns(unit.get('unknowns')), 'NORMAL_OBSERVED_UNIT_AUTHORITY_INVALID',
              'Complete ordered source units remain candidate observations with unknown closure')
        previous_order = order
        parts, members, spans = unit.get('parts'), unit.get('requiredMemberBlockIds'), unit.get('sourceSpans')
        _fail(isinstance(parts, list) and parts and isinstance(members, list)
              and isinstance(spans, list) and len(parts) == len(members) == len(spans),
              'NORMAL_OBSERVED_UNIT_INCOMPLETE', 'Every required source member and source span must be retained')
        pages = set()
        for part, member, span in zip(parts, members, spans):
            _fail(isinstance(part, dict) and isinstance(span, dict) and _name(member)
                  and member == part.get('blockId') == span.get('blockId') and member not in block_ids,
                  'NORMAL_OBSERVED_MEMBER_MISMATCH', 'Actual member identities and source order must be complete and unique')
            block_ids.add(member)
            text, start, end = part.get('text'), part.get('startOffset'), part.get('endOffset')
            _fail(isinstance(text, str) and type(start) is int and start == 0
                  and type(end) is int and end == utf16_length(text)
                  and type(span.get('startOffset')) is int and type(span.get('endOffset')) is int
                  and span.get('startOffset') == start and span.get('endOffset') == end
                  and part.get('offsetUnit') == span.get('offsetUnit') == 'utf16'
                  and part.get('sourceSha256') == span.get('sourceSha256') == source['originalSourceSha256']
                  and isinstance(part.get('sourcePosition'), dict)
                  and _serialize(span.get('sourcePosition')) == _serialize(part['sourcePosition']),
                  'NORMAL_OBSERVED_PART_MISMATCH', 'Each complete actual part must retain its source identity and exact UTF16 span')
            page = part.get('physicalPageNo')
            _fail(page is None or type(page) is int and page > 0,
                  'NORMAL_OBSERVED_PAGE_INVALID', 'Native locations must not fabricate physical pages')
            if page is not None:
                pages.add(page)
        _fail(isinstance(unit.get('physicalPages'), list)
              and all(type(page) is int for page in unit['physicalPages'])
              and unit['physicalPages'] == sorted(pages)
              and unit.get('text') == '\n'.join(part['text'] for part in parts),
              'NORMAL_OBSERVED_UNIT_MISMATCH', 'Complete unit text and physical pages must match every ordered member')
        if unit.get('kind') == 'native_table':
            group = unit.get('tableGroup')
            _fail(isinstance(group, dict) and isinstance(group.get('rows'), list)
                  and [row.get('blockId') for row in group['rows']] == members,
                  'NORMAL_OBSERVED_TABLE_INCOMPLETE', 'A native table must retain all exact observed rows')
        else:
            _fail(unit.get('kind') in ('source_block', 'word_native_object', 'whole_parser_page',
                  'native_table_method', 'visual_region', 'native_form') and unit.get('tableGroup') is None,
                  'NORMAL_OBSERVED_UNIT_KIND_INVALID', 'Unsupported structure cannot be promoted to an observed unit')
    for relation in graph['relations']:
        _fail(isinstance(relation, dict) and relation.get('humanConfirmed') is False
              and relation.get('fromUnitId') in unit_ids
              and (relation.get('toUnitId') is None or relation['toUnitId'] in unit_ids),
              'NORMAL_OBSERVED_RELATION_INVALID', 'Keep observed and unknown relations without granting confirmation')


def _assemble(manifest, descriptor, code_sha):
    _validate_manifest(manifest)
    graph, binding = manifest['candidateGraph'], manifest['sourceBinding']
    source, graph_sha, manifest_sha = graph['source'], _hash(graph), _hash(manifest)
    revision = _hash({'originalSourceSha256': source['originalSourceSha256'],
        'javaParsedSourceHash': source['parsedSourceHash'], 'routingSha256': binding['routingSha256'],
        'generationSha256': graph['generationSha256'], 'candidateGraphSha256': graph_sha})
    identity = {'version': VERSION, 'producerKind': PRODUCER, 'projectionCodeSha256': code_sha,
        'documentId': source['documentId'], 'role': source['role'], **copy.deepcopy(binding),
        'originalSourceSha256': source['originalSourceSha256'], 'javaParsedSourceHash': source['parsedSourceHash'],
        'generationSha256': graph['generationSha256'], 'candidateGraphSha256': graph_sha,
        'observationManifestSha256': manifest_sha, 'sourceRevisionSha256': revision}
    # The detached manifest owns these immutable observations; derived views need no second full tree.
    canonical = {'units': graph['units'], 'relations': graph['relations'], 'assetRefs': []}
    source_hash = _hash({'projectionIdentity': identity, 'canonical': canonical})
    projection_revision = _hash({'sourceHash': source_hash, 'projectionIdentity': identity})
    chunks, non_retrievable = [], []
    quality = {'status': 'PARSED', 'semanticQuality': 'unknown', **DECLARATIONS}
    quality_sha = _hash(quality)
    relations_by_unit = {}
    for relation in graph['relations']:
        origin, target = relation['fromUnitId'], relation.get('toUnitId')
        relations_by_unit.setdefault(origin, []).append(relation)
        if target is not None and target != origin:
            relations_by_unit.setdefault(target, []).append(relation)
    for unit in graph['units']:
        if not unit['text'].strip():
            non_retrievable.append({'unitId': unit['unitId'], 'sourceOrder': unit['sourceOrder'],
                                    'reason': 'no_observed_text'})
            continue
        chunk = {'id': 'canonical:' + _hash({'revision': projection_revision, 'unitId': unit['unitId']}),
            'producerKind': PRODUCER, 'projectionVersion': VERSION, 'metadataVersion': VERSION,
            'segmentationVersion': 'complete-normal-source-unit-boundaries-v1',
            'nativeTableMetadataVersion': 'normal-parser-preserved-table-candidates-v1',
            'sourceQualityMetadataVersion': 'unreviewed-normal-observation-quality-v1',
            'addressKind': 'normal_observed_unit', 'content': unit['text'],
            'anchor': unit['parts'][0].get('anchor'), 'sourceHash': source_hash,
            'sourceSha256': source['originalSourceSha256'], 'originalSourceSha256': source['originalSourceSha256'],
            'sourceRevisionSha256': revision, 'projectionRevisionSha256': projection_revision,
            'observationManifestSha256': manifest_sha, 'sourceQuality': copy.deepcopy(quality),
            'sourceQualityHash': quality_sha, 'assetRefs': [], **DECLARATIONS}
        for key in ('unitId', 'kind', 'parts', 'sourceSpans', 'physicalPages', 'sourceOrder',
                    'tableGroup', 'unknowns', 'requiredMemberBlockIds', 'qualifiersComplete'):
            if unit[key] is not None:
                chunk[key] = (_expand_paragraph_contexts(graph, unit[key]) if key == 'parts'
                              else copy.deepcopy(unit[key]))
        if chunk['anchor'] is None:
            del chunk['anchor']
        for key in ('documentId', 'role', 'module', 'eligibleModules', 'sourcePurpose', 'sourceClass'):
            chunk[key] = copy.deepcopy(identity[key])
        chunk['relations'] = [copy.deepcopy(relation) for relation in relations_by_unit.get(unit['unitId'], [])]
        paragraph_map(chunk)
        chunks.append(chunk)
    result = {'schema': SCHEMA, 'producerKind': PRODUCER, 'projectionIdentity': identity,
        'sourceHash': source_hash, 'projectionRevisionSha256': projection_revision,
        'source': {'sourceSha256': source['originalSourceSha256'], 'sourceRevisionSha256': revision,
            'filename': source['filename'], 'sourceFilename': source['filename'],
            'parsedSourceHash': source['parsedSourceHash'], 'projectId': source['projectId'],
            'documentId': source['documentId']}, 'manifest': manifest,
        'observationManifestRef': copy.deepcopy(descriptor), 'canonical': canonical, 'chunks': chunks,
        'nonRetrievableUnits': non_retrievable,
        'assetRefs': [], 'unknowns': copy.deepcopy(graph['unknowns']), **DECLARATIONS}
    result['projectionSha256'] = _hash(result)
    return result


def produce_normal_observed(candidate_graph, source_binding, *, artifact_store):
    _fail(isinstance(artifact_store, ArtifactStore), 'NORMAL_OBSERVED_CAS_REQUIRED',
          'Normal observations require a complete actual-CAS manifest')
    manifest = {'candidateGraph': copy.deepcopy(candidate_graph), 'sourceBinding': copy.deepcopy(source_binding),
                'observer': copy.deepcopy(OBSERVER)}
    _validate_manifest(manifest)
    descriptor = artifact_store.put_bytes(_serialize(manifest), identity_scope='complete_normal_observation_manifest')
    return _assemble(manifest, descriptor, hashlib.sha256(Path(__file__).read_bytes()).hexdigest())


def validate_normal_observed(projection, *, artifact_store):
    _fail(isinstance(projection, dict) and projection.get('schema') == SCHEMA
          and projection.get('producerKind') == PRODUCER, 'NORMAL_OBSERVED_SCHEMA_INVALID',
          'Only the explicit normal-observed authority is supported')
    descriptor = projection['observationManifestRef']
    raw_manifest = artifact_store.read_asset(descriptor)
    manifest = _strict_json(raw_manifest)
    _fail(_matches_serialized_bytes(manifest, raw_manifest)
          and _matches_serialized_bytes(projection['manifest'], raw_manifest), 'NORMAL_OBSERVED_CAS_MANIFEST_MISMATCH',
          'Every load requires the exact complete retained observation manifest bytes')
    code = _identity(projection['projectionIdentity']['projectionCodeSha256'], 'projectionCodeSha256')
    _fail(_serialized_equal(_assemble(manifest, descriptor, code), projection), 'NORMAL_OBSERVED_DERIVED_MISMATCH',
          'Normal observed source seals and complete parents must reconstruct exactly from actual CAS')
    return projection
