"""Normal, CPU-only PDF proposals from complete uploaded original bytes.

Selected pages are a native reading-plan subset; unselected source coverage is
unknown. Methods remain independent, unpublished candidates. Integer glyph IDs
refer only to the retained pdfplumber observation, never to Java glyphs/widgets.
No OCR, model, renderer, source registry, adoption or gold input is invoked.
"""
from __future__ import annotations

import hashlib
import importlib.metadata
import io
import json
from pathlib import Path
import re
import tempfile
import threading

from native_pdf_table_candidate import (FRAME, METHODS, capture, exception_record,
                                        extract_camelot, extract_pdfplumber,
                                        observe_source_page, safe)
from native_pdf_table_logic import recover_page
from native_pdf_table_continuations import build_margin_profile, observe_continuations


SCHEMA = 'consense.native-pdf-candidates.v1'
MAX_PDF_BYTES = 64 * 1024 * 1024
MAX_CONTEXT_BYTES = 32 * 1024 * 1024
MAX_REPLY_BYTES = 8 * 1024 * 1024
MAX_SELECTED_PAGES = 64
_CAPTURE_LOCK = threading.RLock()


def _encoded(value):
    return json.dumps(safe(value), ensure_ascii=False, allow_nan=False,
                      sort_keys=True, separators=(',', ':')).encode('utf-8')


def _open_pdf(raw):
    import pdfplumber
    return pdfplumber.open(io.BytesIO(raw))


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _version(provider):
    try:
        return importlib.metadata.version('camelot-py' if provider == 'camelot' else provider)
    except importlib.metadata.PackageNotFoundError:
        return None


def _context(raw, original_sha):
    value = json.loads(raw)
    _require(isinstance(value, dict), 'native_context_object_required')
    source = value.get('source')
    _require(isinstance(source, dict) and isinstance(source.get('sourceFilename'), str)
             and 0 < len(source['sourceFilename']) <= 512
             and source.get('mediaType') == 'application/pdf'
             and isinstance(source.get('sourceSha256'), str)
             and re.fullmatch('[a-f0-9]{64}', source['sourceSha256'])
             and type(source.get('pageCount')) is int and source['pageCount'] > 0,
             'source_identity_invalid')
    _require(source['sourceSha256'] == original_sha, 'source_sha_mismatch')
    selected = value.get('selectedPhysicalPages')
    _require(isinstance(selected, list) and 0 < len(selected) <= MAX_SELECTED_PAGES
             and all(type(number) is int and 1 <= number <= source['pageCount'] for number in selected)
             and len(set(selected)) == len(selected), 'selected_physical_pages_invalid')
    _require(isinstance(value.get('readingPlan'), dict)
             and isinstance(value.get('javaNativeEvidence'), list), 'native_reading_context_required')
    plan = value['readingPlan']
    _require(plan.get('schemaVersion') == 'oer-reading-plan-v1'
             and plan.get('sourceSha256') == original_sha
             and isinstance(plan.get('documentFamily'), str) and plan['documentFamily']
             and plan['documentFamily'].upper() != 'DRAWING'
             and plan.get('identityStatus') in ('sha_verified', 'content_supported',
                 'content_supported_non_drawing', 'format_supported_non_drawing'),
             'non_drawing_reading_plan_required')
    return value


def capture_native_pdf_candidates(original_bytes, native_context, *, artifact_store,
                                  max_pdf_bytes=MAX_PDF_BYTES,
                                  max_reply_bytes=MAX_REPLY_BYTES):
    """Capture whole inputs/raw failures and return bounded unadopted proposals.

    The caller owns the CAS and must serialize this CPU work with other uses of
    the legacy provider stdout/log observer. Invalid context/provider/size is a
    visible failed receipt, never an empty successful extraction. The full
    candidate bundle remains cold-verifiable when the HTTP reply cap refuses it.
    """
    _require(type(max_pdf_bytes) is int and max_pdf_bytes > 0
             and type(max_reply_bytes) is int and max_reply_bytes >= 4096,
             'positive_pdf_and_minimum_4096_reply_caps_required')
    with _CAPTURE_LOCK:
        return _capture(original_bytes, native_context, artifact_store,
                        max_pdf_bytes, max_reply_bytes)


def _capture(original_bytes, native_context, store, max_pdf_bytes, max_reply_bytes):
    _require(isinstance(original_bytes, bytes), 'complete_original_bytes_required')
    context_bytes = native_context.encode('utf-8') if isinstance(native_context, str) else native_context
    _require(isinstance(context_bytes, bytes), 'exact_native_context_bytes_required')
    original_sha = hashlib.sha256(original_bytes).hexdigest()
    original_ref = store.put_bytes(original_bytes, identity_scope='actual_complete_original_pdf_bytes')
    context_ref = store.put_bytes(context_bytes, identity_scope='exact_java_native_reading_context_bytes')
    source = {'sourceFilename': None, 'sourceSha256': original_sha,
              'mediaType': 'application/pdf', 'pageCount': None}
    reply = {'schema': SCHEMA, 'status': 'failed', 'source': source,
             'selectedPhysicalPages': [], 'pages': [],
             'coverage': {'sourcePageCount': None, 'selectedPageCount': 0,
                          'unselectedPagesStatus': 'unknown', 'sourceQualityAccepted': False,
                          'tableStructureAccepted': False},
             'adoptionStatus': 'candidate_unverified'}
    bundle = {'schema': 'consense.native-pdf-candidate-bundle.v1',
              'originalRef': original_ref, 'nativeContextRef': context_ref,
              'modelCalls': 0, 'rendererCalls': 0, 'sourceQualityAccepted': False,
              'candidateFusion': False, 'javaNativeEvidence': 'retained_in_separate_context_namespace',
              'sourceRoutingStatus': 'caller_bound_java_admission_not_independently_reclassified',
              'codeRefs': {}, 'providerVersions': {p: _version(p) for p in ('pdfplumber', 'camelot')}}
    refs = [original_ref, context_ref]

    def retain(value, scope):
        ref = store.put_bytes(_encoded(value), identity_scope=scope)
        refs.append(ref)
        return ref

    try:
        _require(0 < len(original_bytes) <= max_pdf_bytes, 'original_byte_cap_exceeded')
        _require(0 < len(context_bytes) <= MAX_CONTEXT_BYTES, 'native_context_byte_cap_exceeded')
        _require(b'%PDF-' in original_bytes[:1024], 'original_pdf_header_missing')
        context = _context(context_bytes, original_sha)
        source['sourceFilename'] = context['source']['sourceFilename']
        selected = context['selectedPhysicalPages']
        reply['selectedPhysicalPages'] = list(selected)
        reply['coverage']['selectedPageCount'] = len(selected)
        code_before = {}
        for name in ('native_pdf_candidates.py', 'native_pdf_table_candidate.py',
                     'native_pdf_table_logic.py', 'native_pdf_table_conditions.py',
                     'native_pdf_table_continuations.py'):
            path = Path(__file__).with_name(name)
            raw = path.read_bytes(); code_before[path] = raw
            ref = store.put_bytes(raw, identity_scope='actual_cpu_candidate_recipe_code_bytes')
            bundle['codeRefs'][name] = ref; refs.append(ref)
        with _open_pdf(original_bytes) as document:
            actual_count = len(document.pages)
            source['pageCount'] = actual_count
            reply['coverage']['sourcePageCount'] = actual_count
            _require(actual_count == context['source']['pageCount'], 'source_page_count_mismatch')
            # Camelot needs a path. This owned copy contains complete unchanged
            # source bytes; no page extraction/crop or source-path replacement.
            with tempfile.TemporaryDirectory(prefix='native-pdf-candidate-', dir=store.root) as directory:
                owned_root = Path(directory).resolve()
                _require(owned_root.is_relative_to(store.root.resolve()), 'owned_directory_outside_store')
                path = owned_root / 'original.pdf'; path.write_bytes(original_bytes)
                previous_methods = {}
                for number in selected:
                    page = document.pages[number - 1]
                    glyphs, geometry, observations, observation_error = [], {}, {}, None
                    with capture() as observation_execution:
                        try:
                            geometry, glyphs, observations = observe_source_page(page)
                        except Exception as error:
                            observation_error = exception_record(error)
                    namespace = 'pdfplumber/page/' + str(number)
                    observation_ref = retain({'physicalPageNo': number,
                        'nativeGlyphNamespace': namespace, 'frame': {'coordinateSpace': FRAME, **geometry},
                        'observations': observations, 'failure': observation_error,
                        'execution': observation_execution}, 'actual_native_pdf_source_observation')
                    page_reply = {'physicalPageNo': number, 'frame': {'coordinateSpace': FRAME, **geometry},
                                  'nativeGlyphNamespace': namespace, 'sourceObservationRef': observation_ref,
                                  'methods': []}
                    reply['pages'].append(page_reply)
                    for method, specification in METHODS.items():
                        tables, diagnostics, failure, candidate = [], {}, None, None
                        changed_ref = None
                        with capture() as execution:
                            try:
                                _require(not observation_error, 'native_source_observation_failed')
                                if specification['provider'] == 'pdfplumber':
                                    tables, diagnostics = extract_pdfplumber(page, method, glyphs, geometry)
                                else:
                                    _require(path.resolve().is_relative_to(owned_root), 'owned_path_outside_directory')
                                    _require(path.read_bytes() == original_bytes, 'owned_original_bytes_changed')
                                    tables, diagnostics = extract_camelot(path, number, method, glyphs, geometry)
                                for table in tables:
                                    table['tableId'] = original_sha + ':' + str(number) + ':' + table['tableId']
                                errors = [table['extractionError'] for table in tables if table.get('extractionError')]
                                _require(not errors, 'provider_table_text_extraction_failed')
                                candidate = recover_page(tables, observations)
                                candidate.update(publicationStatus='unpublished_candidate',
                                    nativeGlyphNamespace=namespace, provider=specification['provider'],
                                    method=method, physicalPageNo=number,
                                    sourceSha256=original_sha, frame={'coordinateSpace': FRAME, **geometry})
                                source_page = dict(observations, sourceSha256=original_sha,
                                    sectionId=original_sha, originalPage=number, localPage=number,
                                    status='succeeded')
                                logical_page = dict(candidate, sectionId=original_sha,
                                    originalPage=number, localPage=number)
                                prior = previous_methods.get(method)
                                if prior is not None:
                                    try:
                                        continuation = observe_continuations(prior['source'], prior['logical'],
                                            source_page, logical_page, build_margin_profile([prior['source'], source_page]))
                                        if continuation['groups']:
                                            continuation.update(anchorPhysicalPageNo=prior['page'],
                                                anchorRawRef=prior['rawRef'], anchorSourceObservationRef=prior['sourceRef'],
                                                sourceSha256=original_sha, method=method, provider=specification['provider'])
                                            candidate['observationGroups']['continuations'] = continuation
                                    except Exception as error:
                                        candidate['observationGroups']['continuationFailure'] = exception_record(error)
                                        candidate['observationGroups']['continuationStatus'] = 'unknown_optional_continuation_observation_failed'
                            except Exception as error:
                                failure = exception_record(error)
                            finally:
                                # Capture a provider-modified copy as received evidence;
                                # no result based on changed original bytes can survive.
                                try:
                                    _require(path.resolve().is_relative_to(owned_root), 'owned_path_outside_directory')
                                    received = path.read_bytes()
                                    if received != original_bytes:
                                        changed_ref = store.put_bytes(received, identity_scope='received_changed_owned_pdf_bytes')
                                        refs.append(changed_ref)
                                        raise ValueError('owned_original_bytes_changed')
                                except Exception as error:
                                    failure = exception_record(error)
                                if failure:
                                    candidate = None
                        raw = {'method': method, 'provider': specification['provider'],
                               'physicalPageNo': number, 'nativeGlyphNamespace': namespace,
                               'sourceObservationRef': observation_ref,
                               'tables': tables, 'diagnostics': diagnostics, 'candidate': candidate,
                               'failure': failure, 'execution': execution}
                        if changed_ref:
                            raw['receivedOriginalRef'] = changed_ref
                        raw_ref = retain(raw, 'whole_independent_native_pdf_provider_result')
                        if failure:
                            previous_methods.pop(method, None)
                        else:
                            previous_methods[method] = {'source': source_page, 'logical': logical_page,
                                'page': number, 'rawRef': raw_ref, 'sourceRef': observation_ref}
                        page_reply['methods'].append({'method': method, 'provider': specification['provider'],
                            'status': 'failed' if failure else 'candidate_observed',
                            'candidate': candidate, 'failure': failure, 'rawRef': raw_ref})
                    page.close()
                # TemporaryDirectory removes only its freshly owned path.
                _require(owned_root.resolve().is_relative_to(store.root.resolve()), 'cleanup_target_outside_store')
        _require(all(path.read_bytes() == raw for path, raw in code_before.items()), 'cpu_candidate_recipe_changed')
        statuses = [method['status'] for page in reply['pages'] for method in page['methods']]
        successes = statuses.count('candidate_observed')
        reply['status'] = ('candidate_observed' if successes == len(statuses) and successes
                           else 'partial' if successes else 'failed')
    except Exception as error:
        reply['status'] = 'failed'
        reply['failure'] = exception_record(error)
        # A fatal provenance failure cannot leave usable proposals in HTTP.
        bundle['partialPages'] = reply['pages']
        reply['pages'] = []
    for ref in refs:
        store.verify(ref)
    bundle.update(reply)
    reply['bundleRef'] = retain(bundle, 'whole_native_pdf_candidate_bundle')
    store.verify(reply['bundleRef'])
    if len(_encoded(reply)) > max_reply_bytes:
        reply = {**reply, 'status': 'failed', 'pages': [], 'failure': {
            'type': 'ReplyByteCapExceeded', 'message': 'reply_byte_cap_exceeded; whole candidates retained in bundleRef'}}
        if len(_encoded(reply)) > max_reply_bytes:
            # Retain the entire requested selection in context/bundle without
            # returning a truncated list that could pretend to be its scope.
            reply['selectedPhysicalPages'] = []
            reply['selectionStatus'] = 'whole_selection_retained_in_bundle_not_returned'
    return reply
