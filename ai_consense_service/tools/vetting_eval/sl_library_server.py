"""Owned product SL library: complete-original native reading -> existing window index.

No extraction or inference runs at startup. Only the UI build/search routes may
start that work. Original PDFs, old indexes and competition evidence stay intact.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid

from build_fixed_development_baseline import parents_from_groups, quality
from fixed_development_baseline_runtime import bound, desc, settings
from token_windows import digest

SCHEMA = 'consense.sl-official-library-plan.v1'
VERSION = 'sl-official-native-clause-observations-v4'
EDITIONS = ('sl2022-original', 'sl2022-batch01', 'sl2022-batch02-rev1',
            'sl2022-batch03', 'sl2022-batch04', 'sl2026-original')
# Same literal syntax as NormalPdfSourceGroups.SL_LABEL; a dot distinguishes a clause from a worksection.
SL_LABEL = r'(?:TOL\.)?(?:(?:[A-Z]{3}|AP)[0-9]+(?:\.(?:APPEND[0-9]+|[A-Z]?[0-9]+|[A-Z]))+|[A-Z]{3}\.[A-Z][0-9]+\.[0-9]+(?:\.(?:[A-Z]?[0-9]+|[A-Z]))*)'
HEADING = re.compile(r'^[ \t]*(' + SL_LABEL + r')([ \t]*)(\S[^\r\n]*)', re.M)
# shortcut: only adjacent same-page all-caps titles move; source geometry is needed for wider hierarchy.
SECTION_BEFORE_CLAUSE = re.compile(r'(?m)^[ \t]*[A-Z][A-Z ,/()&+\-]{3,}[ \t]*\r?\n\Z')
CONTENTS_PAGE = re.compile(r'^[ \t]*(?:[^\r\n]*>[ \t]*)?TABLE[ \t]+OF[ \t]+CONTENTS[ \t]*\r?$', re.M | re.I)
RUNNING_FURNITURE = re.compile(r'\A[^\r\n]*\r?\n_{20,}[ \t]*\r?\nSpecification Library 20[^\r\n]*\r?\n')
MAX_REPLY_BYTES = 16 * 1024 * 1024
LIBRARY_PARENT_LIMIT = 100000


def utc():
    return datetime.now(timezone.utc).isoformat()


def log_event(event, **values):
    print(json.dumps({'event': event, **values}, separators=(',', ':')), flush=True)


def write_json(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2).encode('utf-8')
    pending = path.with_name(path.name + '.pending')
    pending.write_bytes(raw); pending.replace(path)
    return desc(path)


def fresh_native_pages(edition, destination, progress=lambda count: None, pdf_factory=None):
    """Fresh full-original PDFium reading, restricted to the declared technical pages."""
    started = time.perf_counter()
    source = edition['source']
    if type(source['bytes']) is not int or not 0 < source['bytes'] <= 96 * 1024 * 1024:
        raise ValueError('Fresh original exceeds the bounded whole-source reading budget')
    with Path(source['path']).open('rb') as stream: original = stream.read(source['bytes'] + 1)
    if len(original) != source['bytes'] or hashlib.sha256(original).hexdigest() != source['sha256']:
        raise ValueError('Fresh original bytes differ from the configured source pin')
    scope = json.loads(bound(edition['scope']).read_bytes())
    spans = scope['includedPageSpans']
    if (scope['source']['sourceSha256'] != source['sha256']
            or scope['source']['versionId'] != source['editionId']
            or len(spans) != 1 or spans[0]['physicalPageStart'] != edition['technicalStart']
            or spans[0]['physicalPageEnd'] != edition['technicalEnd']):
        raise ValueError('Fresh native reading requires the exact technical scope partition')
    if pdf_factory is None:
        import pypdfium2
        pdf_factory = pypdfium2.PdfDocument
    failures = []; read = 0
    document = pdf_factory(original)
    try:
        if len(document) != source['pageCount']: raise ValueError('Fresh original page count differs')
        with Path(destination).open('x', encoding='utf-8', newline='\n') as stream:
            for physical in range(1, len(document) + 1):
                text, status = '', 'excluded_sl_preliminaries_or_metadata'
                if edition['technicalStart'] <= physical <= edition['technicalEnd']:
                    page = textpage = None
                    try:
                        page = document[physical - 1]; textpage = page.get_textpage()
                        text = textpage.get_text_range(); status = 'fresh_native_text' if text.strip() else 'missing_native_text'
                    except Exception as error:
                        status = 'native_read_failed'; failures.append({'physicalPage': physical, 'error': str(error)})
                    finally:
                        if textpage is not None: textpage.close()
                        if page is not None: page.close()
                    read += 1; progress(read)
                stream.write(json.dumps({'page': physical, 'text': text, 'status': status,
                    'sourceSha256': source['sha256'], 'editionId': source['editionId']}, ensure_ascii=False) + '\n')
    finally: document.close()
    return {'seconds': time.perf_counter() - started, 'technicalPagesAttempted': read,
            'failedPages': failures, 'parser': 'pypdfium2_full_original_native_text',
            'completedAtUtc': utc(), 'freshOriginalRead': True, 'ocrCalls': 0, 'sourceQualityAccepted': False}


def index_request(backend, project_id, rows):
    if len(rows) <= 50000:
        return backend.IndexRequest(projectId=project_id, chunks=rows)
    if len(rows) > LIBRARY_PARENT_LIMIT:
        raise ValueError('The complete official library exceeds the finite 100000-parent budget; no text was truncated')
    # The UI accepts no chunks; only this fixed, pinned all-library producer needs the larger finite list.
    from pydantic import Field, create_model
    request = create_model('SlLibraryIndexRequest', __base__=backend.IndexRequest,
                           chunks=(list[backend.Chunk], Field(max_length=LIBRARY_PARENT_LIMIT)))
    return request(projectId=project_id, chunks=rows)


def prepare_edition(edition, progress=lambda count: None):
    """Literal source-order boundary observations; never legal clause adoption."""
    source = edition['source']; bound(source)
    inventory = json.loads(bound(edition['inventory']).read_bytes())
    scope = json.loads(bound(edition['scope']).read_bytes())
    if (inventory['pageCount'] != source['pageCount']
            or scope['source']['sourceSha256'] != source['sha256']
            or scope['source']['versionId'] != source['editionId']):
        raise ValueError('SL source, edition and page inventory differ')
    libraries = scope['includedLibraries']
    required = [page for span in scope['includedPageSpans']
                for page in range(span['physicalPageStart'], span['physicalPageEnd'] + 1)]
    if required != list(range(edition['technicalStart'], edition['technicalEnd'] + 1)):
        raise ValueError('SL technical page partition differs')
    groups, nodes, blocks, pages, gaps = defaultdict(list), [], [], [], []
    active = None; current_library = None; printed_section = None; ordinal = 0
    with bound(edition['pages']).open(encoding='utf-8') as stream:
        for expected, line in enumerate(stream, 1):
            page = json.loads(line)
            if page.get('page') != expected or not isinstance(page.get('text'), str):
                raise ValueError('Saved native pages are missing, repeated or unordered')
            if not edition['technicalStart'] <= expected <= edition['technicalEnd']:
                continue
            owners = [library for library in libraries if library['physicalPageStart'] <= expected <= library['physicalPageEnd']]
            if len(owners) != 1:
                raise ValueError('Technical page has no unique declared library')
            library = owners[0]['title']
            if library != current_library:
                active = None; current_library = library; printed_section = None
            text = page['text']; pages.append(expected)
            page_section = text.partition('\n')[0].strip()
            if ' > ' in page_section:
                # shortcut: missing printed breadcrumbs need source-geometry review.
                if printed_section and page_section != printed_section: active = None
                printed_section = page_section
            if not text.strip(): gaps.append(expected)
            contents_page = CONTENTS_PAGE.search(text) is not None
            if contents_page: active = None
            # The complete raw page stays pinned; only repeated printed furniture leaves a continuing clause.
            furniture = RUNNING_FURNITURE.match(text) if active is not None and not contents_page else None
            body_start = furniture.end() if furniture else 0
            # Wrapped contents leaders remain literal text, never clause boundaries.
            headings = []
            if not contents_page:
                for match in HEADING.finditer(text, body_start):
                    title = match[3]
                    if not (title[0].isupper() or title[0].isdigit()):
                        continue
                    if not match[2] and not re.fullmatch(r'(?:TOL\.)?[A-Z]{3}[0-9]+\.APPEND[0-9]+\.(?:[A-Z]?[0-9]+|[A-Z])', match[1]):
                        continue
                    following = text[match.end():].split('\n', 2)
                    next_line = following[1] if len(following) > 1 else ''
                    if re.search(r'\.{3,}', title) or (re.search(r'\.{3,}', next_line)
                            and next_line.upper() == next_line and not HEADING.match(next_line)):
                        continue
                    headings.append(match)
            starts = []
            previous = None
            for match in headings:
                start = match.start()
                if previous is not None:
                    section = SECTION_BEFORE_CLAUSE.search(text[previous.end():start])
                    if section:
                        start = previous.end() + section.start()
                starts.append((start, match[1]))
                previous = match
            segments = [(body_start, None), *starts, (len(text), None)]
            for (start, label), (end, _) in zip(segments, segments[1:]):
                if label is not None or active is None:
                    ordinal += 1
                    active = 'observed:' + str(ordinal)
                    nodes.append({'id': active, 'kind': 'clause' if label else 'context',
                                  'labelRaw': label, 'parentId': None, 'anchorSpanId': None,
                                  'library': library})
                if end <= start: continue
                block = {'id': f'P{expected}:cp{start}-{end}', 'pageNo': expected - edition['technicalStart'] + 1,
                         'text': text[start:end], 'source': 'pdfium_native_text_observation'}
                if nodes[-1]['anchorSpanId'] is None: nodes[-1]['anchorSpanId'] = block['id']
                blocks.append(block); groups[(active, 'body', None)].append(block)
            if contents_page: active = None
            progress(len(pages))
    if expected != source['pageCount'] or pages != required:
        raise ValueError('Saved native page coverage differs from the exact original')
    corpus, coverage = [], []
    status = {'totalPages': len(required), 'parsedPages': len(required) - len(gaps),
              'missingNativeTextPages': gaps, 'ocrPages': 0, 'complete': not gaps}
    sq = quality('SL', status, {'editionId': source['editionId'],
        'normalParseStatus': 'PARTIAL' if gaps else 'NATIVE_TEXT_AVAILABLE',
        'readingOrder': 'native_pdfium_observation_unverified',
        'clauseBoundaries': 'literal_numbering_proposals_not_legal_scope',
        'crossPageMembership': 'provisional_page_fragments_original_boundaries_retained',
        'complexLayoutTablesFigures': 'unverified_nonblocking'})
    parents_from_groups(groups, blocks, nodes, source, source['editionId'], 'SL', required,
                        sq, {'savedNativePages': edition['pages'], 'inventory': edition['inventory'],
                             'scope': edition['scope']}, corpus, coverage)
    node_map = {node['id']: node for node in nodes}
    for row in corpus:
        node = node_map[row['observedNodeId']]
        row['editionId'] = source['editionId']; row['chapter'] = node['library']
        row['observedClause'] = node['labelRaw']
        row['sourceQualityAccepted'] = False
    return corpus, {'technicalPages': len(required), 'parsedPages': len(required) - len(gaps),
                    'missingPages': gaps, 'clauseCount': sum(node['kind'] == 'clause' for node in nodes),
                    'parentCount': len(corpus), 'coverage': coverage}


class Library:
    def __init__(self, plan, plan_descriptor, backend_factory=None, port=18181):
        if plan.get('schema') != SCHEMA or tuple(e['source']['editionId'] for e in plan['editions']) != EDITIONS:
            raise ValueError('The six configured official SL editions are required')
        if sum(e['technicalEnd'] - e['technicalStart'] + 1 for e in plan['editions']) != 19040:
            raise ValueError('Official technical page denominator differs')
        if len({e['source']['sha256'] for e in plan['editions']}) != len(EDITIONS):
            raise ValueError('Official SL sources must retain distinct original-byte identities')
        workspace, output, state = Path(plan['workspace']), Path(plan['output']), Path(plan['state'])
        if (not workspace.is_absolute() or not output.is_absolute() or not state.is_absolute()
                or output.name != 'runtime-output' or not output.parent.name.startswith('sl-official-product-')
                or output.parent.parent.resolve() != (workspace / '.local').resolve()
                or state.resolve() != output.resolve() / 'state'):
            raise ValueError('Library state must use its configured task-owned runtime-output/state')
        self.plan, self.plan_descriptor = plan, plan_descriptor
        self.port = port
        self.root = Path(plan['output']); self.root.mkdir(parents=True, exist_ok=True)
        self.lock, self.work = threading.RLock(), threading.Lock()
        self.backend_factory = backend_factory; self.backend = None
        self.code = desc(__file__); self.state_file = self.root / 'status.json'
        self.versions = [{'editionId': e['source']['editionId'], 'label': e['source']['label'],
                          'technicalPages': e['technicalEnd'] - e['technicalStart'] + 1,
                          'parsedPages': 0, 'clauseCount': 0, 'indexedChunks': 0,
                          'status': 'original_available'} for e in plan['editions']]
        self.job = {'status': 'idle', 'stage': 'awaiting_build', 'currentEdition': None,
                    'processedPages': 0, 'totalPages': 19040, 'message': 'Ready to read complete official originals.'}
        self.artifacts = None
        if self.state_file.exists():
            saved = json.loads(self.state_file.read_bytes())
            if saved['planSha256'] != plan_descriptor['sha256'] or saved['producerSha256'] != self.code['sha256']:
                raise ValueError('Owned library state belongs to another source plan or producer')
            self.versions, self.job, self.artifacts = saved['versions'], saved['job'], saved.get('artifacts')
            if self.job['status'] == 'running':
                self.job.update(status='interrupted', message='The owned build was interrupted; resume explicitly.')

    def status(self):
        with self.lock:
            return json.loads(json.dumps({'versions': self.versions, 'job': self.job,
                'retrievalAvailable': bool(self.artifacts), 'artifacts': self.artifacts,
                'sourceQualityAccepted': False, 'scope': 'six_official_SL_technical_editions'}))

    def save(self):
        write_json(self.state_file, {**self.status(), 'planSha256': self.plan_descriptor['sha256'],
                                    'producerSha256': self.code['sha256']})

    def update(self, **values):
        with self.lock:
            self.job.update(values); self.save()

    def start(self):
        with self.lock:
            if self.job['status'] == 'running' or self.artifacts and self.job['status'] == 'completed':
                return self.status()
            self.job.update(status='running', stage='verify_originals_and_scope', startedAtUtc=utc(),
                            message='Starting fresh complete-original reading; source quality remains unverified.')
            self.save()
            threading.Thread(target=self.build, name='owned-sl-library-build', daemon=True).start()
            return self.status()

    def runtime(self):
        with self.lock:
            if self.backend is None:
                manifest = {'models': self.plan['models'], 'inputPlanWorkspace': self.plan['workspace'], 'code': self.code}
                self.backend = self.backend_factory(manifest) if self.backend_factory else settings(
                    manifest, self.plan['state'], self.plan.get('batchSize', 16), 'float16', owned_root=self.plan['output'])
                self.backend._window_observer = self.observe_window
            return self.backend

    def observe_window(self, stage, value):
        if stage == 'window-index-upsert':
            self.update(stage='embedding_and_persistence', encodedWindows=value['encodedWindowsCumulative'],
                        message='Persisted embedding windows: ' + str(value['begin'] + len(value['windowIds'])))
        elif stage == 'retrieval-phase':
            fields = ('phase', 'status', 'querySha256', 'elapsedSeconds', 'durationSeconds', 'stageOrdinal',
                      'parents', 'windows', 'selectedParents', 'selectedSources', 'granularity', 'fetchOrdinal',
                      'filterKey', 'filterValues', 'requestedWindows', 'returnedWindows', 'candidateParents', 'returnedParents')
            log_event(stage, **{key: value[key] for key in fields if key in value})

    def build(self):
        with self.work:
            try:
                attempt = self.root / 'attempts' / uuid.uuid4().hex; attempt.mkdir(parents=True)
                rows, prepared, failures = [], [], []
                completed_pages = 0
                for edition, version in zip(self.plan['editions'], self.versions):
                    name = edition['source']['editionId']
                    self.update(stage='native_clause_preparation', currentEdition=name, processedPages=completed_pages)
                    try:
                        target = self.root / 'prepared' / name; target.mkdir(parents=True, exist_ok=True)
                        marker = target / 'prepared.json'
                        if marker.exists():
                            cached = json.loads(marker.read_bytes())
                            if cached['inputs'] != edition or cached['producer'] != self.code:
                                raise ValueError('Saved edition preparation identity differs')
                            for field in ('source', 'pages', 'inventory', 'scope'): bound(edition[field])
                            corpus = [json.loads(line) for line in bound(cached['corpus']).open(encoding='utf-8')]
                            info = cached['info']
                        else:
                            self.update(stage='fresh_native_read', currentEdition=name,
                                        message='Reading every technical page from the complete original PDF.')
                            fresh_path = attempt / (name + '-fresh-pages.jsonl')
                            reading = fresh_native_pages(edition, fresh_path, lambda count: self.update(
                                processedPages=completed_pages + count) if count % 100 == 0 else None)
                            fresh_edition = {**edition, 'pages': desc(fresh_path)}
                            self.update(stage='native_clause_preparation', currentEdition=name)
                            corpus, info = prepare_edition(fresh_edition)
                            info['freshReading'] = reading
                            path = target / ('corpus-' + uuid.uuid4().hex + '.jsonl')
                            with path.open('x', encoding='utf-8', newline='\n') as stream:
                                for row in corpus: stream.write(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n')
                            write_json(marker, {'inputs': edition, 'producer': self.code, 'corpus': desc(path), 'info': info})
                        rows.extend(corpus); prepared.append(edition)
                        with self.lock:
                            version.update(parsedPages=info['parsedPages'], clauseCount=info['clauseCount'],
                                           status='prepared', missingPages=info['missingPages'], parentCount=len(corpus),
                                           freshReading=info.get('freshReading'))
                    except Exception as error:
                        failures.append({'editionId': name, 'error': str(error)})
                        with self.lock: version.update(status='failed', message=str(error))
                    completed_pages += edition['technicalEnd'] - edition['technicalStart'] + 1
                    self.update(processedPages=completed_pages)
                if not rows: raise ValueError('No official SL native text is available for indexing')
                # Preserve GCC/GCT reference parents; omit the distinct competition SL edition.
                for source in self.plan['baseSources'].values(): bound(source)
                with bound(self.plan['baseCorpus']).open(encoding='utf-8') as stream:
                    for line in stream:
                        row = json.loads(line)
                        if row['sourceQuality']['family'] in ('GCC', 'GCT'): rows.append(row)
                corpus_path = attempt / 'corpus.jsonl'
                with corpus_path.open('x', encoding='utf-8', newline='\n') as stream:
                    for row in rows: stream.write(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n')
                self.update(stage='embedding_and_persistence', currentEdition=None, preparedParents=len(rows),
                            message='Indexing native clause parents with the existing BGE-M3 window runtime.')
                backend = self.runtime()
                index_started = time.perf_counter()
                result = backend.window_runtime().index(index_request(backend, self.plan['projectId'], rows))
                self.update(embeddingAndPersistenceSeconds=time.perf_counter() - index_started,
                            indexedWindows=result['vectorPoints'])
                metadata_path = backend.metadata_path(self.plan['projectId'])
                metadata = json.loads(metadata_path.read_bytes())
                with self.lock:
                    for version in self.versions:
                        if version['status'] == 'prepared': version.update(status='indexed', indexedChunks=version['parentCount'])
                runtime_plan = {'schema': 'consense.fixed-development-baseline-runtime-plan.v1',
                    'projectId': self.plan['projectId'], 'signature': metadata['signature'],
                    'indexSignatureSha256': result['signature'], 'parentCount': len(rows),
                    'windowCount': result['vectorPoints'], 'state': self.plan['state'], 'models': self.plan['models']}
                runtime_descriptor = write_json(attempt / 'runtime-plan.json', runtime_plan)
                manifest = {'schema': 'consense.frozen-fixed-development-baseline.v1', 'version': VERSION,
                    'frozenAtUtc': utc(), 'developmentBaseline': True, 'finalParsingAccepted': False,
                    'businessAccepted': False, 'H800Accepted': False, 'scope': ['GCC', 'GCT', 'all-applicable-SL-technical'],
                    'originalSources': {**self.plan['baseSources'], 'slSources': [e['source'] for e in self.plan['editions']]},
                    'inputPlan': self.plan_descriptor, 'corpus': desc(corpus_path), 'runtimePlan': runtime_descriptor,
                    'state': self.plan['state'], 'collection': metadata['collection'],
                    'indexSignature': result['signature'], 'persistedVectorCount': result['vectorPoints'],
                    'persistedMetadata': desc(metadata_path), 'vectorIntegrity': metadata['vectorIntegrity'],
                    'modelPublications': {kind: desc(value['manifestPath']) for kind, value in self.plan['models']['publications'].items()},
                    'coverage': {'SLRequiredPagePositions': 19040, 'SLAvailablePagePositions': sum(v['parsedPages'] for v in self.versions),
                                 'totalParents': len(rows), 'embeddingWindows': result['vectorPoints'], 'versions': self.versions},
                    'knownGaps': ['Native PDFium reading order, formulas, figures, tables and legal ownership remain unverified.',
                                  'Clause boundaries are literal proposals; original page fragments remain separately addressable.',
                                  'Competition Batch 2 is retained historically and is outside this official product catalog.', *failures]}
                frozen = write_json(attempt / 'frozen-baseline-manifest.json', manifest)
                write_json(attempt / 'index-receipt.json', result)
                with self.lock:
                    self.artifacts = {'manifestPath': frozen['path'], 'manifestSha256': frozen['sha256'],
                        'projectId': self.plan['projectId'], 'indexSignature': result['signature'],
                        'baseUrl': f'http://127.0.0.1:{self.port}', 'corpus': manifest['corpus'], 'runtimePlan': runtime_descriptor}
                self.update(status='partial' if failures else 'completed', stage='retrieval_ready',
                    completedAtUtc=utc(), message='Official SL native text is indexed; quality gaps remain explicit.', failures=failures)
            except Exception as error:
                self.update(status='failed', stage='failed', message=str(error), completedAtUtc=utc())

    def retrieve(self, value):
        started = time.perf_counter()
        fields = {'querySha256': hashlib.sha256(str(value.get('query', '')).encode('utf-8')).hexdigest(),
                  'selectedSources': len(value.get('sourceSha256') or [])}
        log_event('sl-library-retrieve', status='started', **fields)
        try:
            with self.work:
                if not self.artifacts: raise ValueError('Build the official SL library before searching')
                if value.get('projectId') != self.plan['projectId']: raise ValueError('Project is outside the configured library')
                hashes = {e['source']['sha256'] for e in self.plan['editions']} | {s['sha256'] for s in self.plan['baseSources'].values()}
                if not value.get('sourceSha256') or not set(value['sourceSha256']) <= hashes:
                    raise ValueError('Select exact configured source editions before ranking')
                backend = self.runtime()
                result = backend.window_runtime().retrieve(backend.RetrieveRequest(**value))
                if len(json.dumps(result, ensure_ascii=False).encode()) > MAX_REPLY_BYTES:
                    raise ValueError('Complete retrieval response exceeds the fixed reading budget')
                log_event('sl-library-retrieve', status='completed', elapsedSeconds=time.perf_counter() - started,
                          returnedParents=len(result.get('hits', [])), **fields)
                return result
        except Exception as error:
            log_event('sl-library-retrieve', status='failed', elapsedSeconds=time.perf_counter() - started,
                      failureType=type(error).__name__, **fields)
            raise

    def search(self, value):
        query, ids, limit = value.get('query'), value.get('editionIds'), value.get('limit', 10)
        if (not isinstance(query, str) or not query.strip() or len(query) > 20000
                or not isinstance(ids, list) or not ids or len(ids) != len(set(ids))
                or not set(ids) <= set(EDITIONS) or type(limit) is not int or not 1 <= limit <= 10):
            raise ValueError('Provide a query, explicit official editions and limit 1..10')
        selected = [e['source'] for e in self.plan['editions'] if e['source']['editionId'] in ids]
        result = self.retrieve({'projectId': self.plan['projectId'], 'query': query, 'role': 'standard',
            'sourceSha256': [s['sha256'] for s in selected], 'limit': limit, 'candidates': 50})
        hits = []
        for hit in result['hits']:
            row = hit['payload']; pages = row.get('originalPageNos', [row['pageNo']])
            hits.append({'id': hit['id'], 'editionId': row.get('editionId', row['sourceQuality'].get('editionId')),
                'chapter': row.get('chapter', ''), 'clause': row.get('observedClause') or row.get('clauseId'),
                'pageStart': min(pages), 'pageEnd': max(pages), 'text': row['content'], 'score': hit['score'],
                'sourceSha256': row['originalSourceSha256'], 'sourceQualityAccepted': False})
        return {'hits': hits, 'editionIds': ids, 'sourceQualityAccepted': False, 'indexSignature': result['indexSignature']}


def create_app(library):
    from fastapi import FastAPI, HTTPException
    app = FastAPI(title='ConSense official SL product library')
    def call(function, *args):
        try: return function(*args)
        except (ValueError, OSError, KeyError) as error: raise HTTPException(422, str(error)) from error
    @app.get('/sl-library/status')
    def status(): return library.status()
    @app.post('/sl-library/build')
    def build(): return library.start()
    @app.post('/sl-library/search')
    def search(value: dict): return call(library.search, value)
    @app.get('/health')
    def health():
        if not library.artifacts: raise HTTPException(503, 'Official library build has not completed')
        return library.runtime().health()
    @app.post('/retrieve')
    def retrieve(value: dict): return call(library.retrieve, value)
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', required=True); parser.add_argument('--plan-sha256', required=True)
    parser.add_argument('--port', type=int, default=18181)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535: raise ValueError('Library listener port must be 1..65535')
    plan_descriptor = desc(args.plan)
    if plan_descriptor['sha256'] != args.plan_sha256: raise ValueError('Configured library plan SHA differs')
    plan = json.loads(Path(args.plan).read_bytes())
    import uvicorn
    uvicorn.run(create_app(Library(plan, plan_descriptor, port=args.port)), host='127.0.0.1', port=args.port, workers=1)


if __name__ == '__main__': main()
