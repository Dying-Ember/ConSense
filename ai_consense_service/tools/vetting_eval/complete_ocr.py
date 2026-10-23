"""Run the two complete native APIs and retain their unadopted source evidence.

PaddleX 3.6 uses /layout-parsing; MinerU 4.0.10 uses /v1/parse/jobs.
Endpoints and model identities come from trusted operator configuration, never
from uploaded files. No confidence-based selection or cell fusion is performed.
"""
import base64
import copy
import hashlib
import io
import ipaddress
import http.client
import json
import math
import re
import socket
import threading
import time
import uuid
import paddle_native_capture
from native_provider_lifecycle import owned_native_provider
from urllib.parse import urlsplit
import zipfile

from PIL import Image
from structured_parser_evidence import (normalize_parser_run, build_dual_parser_evidence,
                                       project_bbox_to_original)
from whole_parser_groups import select_whole_parser_groups, validate_policy
from provider_task_cache import task_binding, load_success, commit_success

MAX_RESPONSE_BYTES = 1024 * 1024 * 1024
PADDLE_OPTIONS = {'fileType': 1, 'useLayoutDetection': True, 'useDocOrientationClassify': False,
    'useDocUnwarping': False, 'restructurePages': False, 'markdownIgnoreLabels': [], 'visualize': True}
MINERU_OPTIONS = {'ocr_mode': 'ocr', 'output_formats': ['zip']}


class CompleteOcrError(ValueError):
    def __init__(self, message, receipt):
        super().__init__(message)
        self.receipt = receipt


def _bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'),
                      allow_nan=False).encode('utf-8')


def _json(raw):
    def invalid(value):
        raise ValueError('Nonfinite parser JSON: ' + value)
    return json.loads(raw, parse_constant=invalid)


class HttpCaptureFailure(OSError):
    def __init__(self, reason, raw, status=None, complete=False):
        super().__init__(reason)
        self.raw, self.status, self.complete = raw, status, complete


def _request(url, body=None, timeout=None, method=None):
    target = urlsplit(url)
    deadline = time.monotonic() + timeout
    connection_type = http.client.HTTPSConnection if target.scheme == 'https' else http.client.HTTPConnection
    connection = connection_type(target.hostname, target.port, timeout=timeout)
    sockets, chunks = [], []
    expired = threading.Event()
    status = None
    response = None
    def stop():
        expired.set()
        for stream in sockets:
            try:
                stream.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
    timer = threading.Timer(timeout, stop)
    timer.daemon = True
    timer.start()
    try:
        connection.connect()
        stream = connection.sock
        sockets.append(stream)
        if expired.is_set():
            raise TimeoutError('Parser total HTTP deadline elapsed')
        data = body if isinstance(body, bytes) else _bytes(body) if body is not None else None
        connection.request(method or ('POST' if data is not None else 'GET'),
            target.path or '/', body=data,
            headers={'Content-Type': 'application/octet-stream' if isinstance(body, bytes) else 'application/json'})
        response = connection.getresponse()
        status = response.status
        size = 0
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or expired.is_set():
                raise TimeoutError('Parser total HTTP deadline elapsed')
            if response.isclosed():
                if response.length:
                    raise OSError('Parser HTTP body ended before Content-Length')
                break
            stream.settimeout(remaining)
            chunk = response.read1(64*1024)
            if not chunk:
                if response.length:
                    raise OSError('Parser HTTP body ended before Content-Length')
                break
            chunks.append(chunk)
            size += len(chunk)
            if size > MAX_RESPONSE_BYTES:
                raise ValueError('Parser response exceeds capture limit; no truncated result')
        raw = b''.join(chunks)
        if expired.is_set():
            raise TimeoutError('Parser total HTTP deadline elapsed')
        if not 200 <= status < 300:
            raise HttpCaptureFailure('Parser HTTP ' + str(status), raw, status, True)
        return raw
    except HttpCaptureFailure:
        raise
    except Exception as error:
        raise HttpCaptureFailure(type(error).__name__ + ': ' + str(error), b''.join(chunks), status, False) from error
    finally:
        timer.cancel()
        if response is not None:
            response.close()
        connection.close()


def _config(config):
    if 'sourceReviews' in config:
        reviews = config['sourceReviews']
        if (not isinstance(reviews, list) or any(key in config for key in
                ('parserRouteAcceptance', 'canonicalAdoptionManifest'))):
            raise ValueError('Use one source-review configuration form')
        keys = set()
        for review in reviews:
            if (not isinstance(review, dict) or set(review) - {
                    'runIds', 'parserRouteAcceptance', 'canonicalAdoptionManifest'}
                    or not any(key in review for key in ('parserRouteAcceptance', 'canonicalAdoptionManifest'))
                    or any(not isinstance(review[key], dict) for key in
                           ('parserRouteAcceptance', 'canonicalAdoptionManifest') if key in review)
                    or not isinstance(review.get('runIds'), list) or len(review['runIds']) != 2
                    or not all(isinstance(key, str) and re.fullmatch(
                        r'(?:paddleocr-vl|mineru):[a-f0-9]{64}', key) for key in review['runIds'])
                    or {key.split(':', 1)[0] for key in review['runIds']} != {'paddleocr-vl', 'mineru'}
                    or len(set(review['runIds'])) != 2):
                raise ValueError('Exact complete dual-run identities required for each source review')
            key = tuple(sorted(review['runIds']))
            if key in keys:
                raise ValueError('Conflicting source reviews for the same complete dual runs')
            keys.add(key)
    if config.get('wholeParserFeaturePolicy') is not None:
        validate_policy(config['wholeParserFeaturePolicy'])
    timeout = config.get('timeoutSeconds', 600)
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
        raise ValueError('Positive finite complete-parser timeout required')
    for parser in ('paddleocr-vl', 'mineru'):
        row = config.get(parser, {})
        if row.get('nativeCaptureTransport') is not None:
            if parser != 'paddleocr-vl':
                raise ValueError('Native local CAS transport applies to Paddle only')
            _paddle_asset_store(row)
        url = urlsplit(row.get('baseUrl', ''))
        if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError('Explicit trusted parser base URL required')
        try:
            ipaddress.ip_address(url.hostname)
        except ValueError as error:
            raise ValueError('Pinned parser IP required; DNS lookup has no enforceable total deadline') from error
        identity = row.get('identity', {})
        if not identity.get('parserVersion') or not identity.get('engine') or not identity.get('models') or not isinstance(identity.get('config'), dict):
            raise ValueError('Explicit parser, model and configuration identity required')
    return float(timeout)


def validate_source_context(context, raw, width, height, input_name):
    """Bind caller-supplied PDF lineage to actual raster bytes; never infer it."""
    if not isinstance(context, dict):
        raise ValueError('Source context must be an object')
    context = _json(_bytes(context))
    digest = hashlib.sha256(raw).hexdigest()
    if any(context.get(key) != value for key, value in (
            ('imageSha256', digest), ('width', width), ('height', height))):
        raise ValueError('Source context differs from actual raster bytes or dimensions')
    source = context.get('source', {})
    page = context.get('physicalPageNo')
    count = source.get('pageCount') if isinstance(source, dict) else None
    if (not isinstance(source, dict) or not isinstance(source.get('sourceFilename'), str)
            or not source['sourceFilename'].strip()
            or not re.fullmatch(r'[0-9a-f]{64}', source.get('sourceSha256', ''))
            or source.get('mediaType') != 'application/pdf'
            or isinstance(page, bool) or not isinstance(page, int)
            or isinstance(count, bool) or not isinstance(count, int) or not 1 <= page <= count):
        raise ValueError('Explicit original PDF identity and physical page required')
    transform = context.get('originalPageTransform', {})
    if not isinstance(transform, dict) or transform.get('kind') != 'affine':
        raise ValueError('Explicit raster-to-original affine transform required')
    frame, target = transform.get('inputFrame', {}), transform.get('targetFrame', {})
    if not isinstance(frame, dict) or any(frame.get(key) != value for key, value in (
            ('coordinateFrame', 'raster'), ('coordinateUnit', 'pixel'), ('width', width),
            ('height', height), ('artifactName', input_name), ('sha256', digest))):
        raise ValueError('Transform input frame is not bound to actual raster')
    if (not isinstance(target, dict) or target.get('coordinateFrame') != 'original_page'
            or target.get('coordinateUnit') != 'pt' or target.get('physicalPageNo') != page
            or target.get('sourceSha256') != source['sourceSha256']
            or any(isinstance(target.get(key), bool) or not isinstance(target.get(key), (int, float))
                   or not math.isfinite(target[key]) or target[key] <= 0 for key in ('width', 'height'))):
        raise ValueError('Transform target frame is not bound to original PDF page')
    matrix = transform.get('matrix')
    if (not isinstance(matrix, list) or len(matrix) != 6
            or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in matrix)
            or matrix[0]*matrix[4] == matrix[1]*matrix[3]):
        raise ValueError('Finite nonsingular affine transform required')
    return context


def _mineru_outputs(raw):
    outputs, assets = {}, {}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        members = archive.infolist()
        if len(members) > 4096 or sum(row.file_size for row in members) > MAX_RESPONSE_BYTES:
            raise ValueError('Complete MinerU archive exceeds capture limit')
        for member in members:
            if member.is_dir():
                continue
            if member.filename.startswith(('/', '\\')) or '..' in member.filename.replace('\\', '/').split('/'):
                raise ValueError('MinerU artifact name escapes its source archive')
            if member.filename in assets:
                raise ValueError('Duplicate MinerU artifact name')
            content = archive.read(member)
            assets[member.filename] = content
            if member.filename.endswith('.json'):
                value = _json(content)
                schema = value.get('schema') if isinstance(value, dict) else None
                key = {'docvortex.model': 'model_json', 'docvortex.middle': 'middle_json'}.get(schema)
                if key:
                    if key in outputs:
                        raise ValueError('Ambiguous complete MinerU output: ' + key)
                    outputs[key] = content
                if 'model_output' in member.filename or 'model.output' in member.filename:
                    if 'model_output' in outputs:
                        raise ValueError('Ambiguous earliest MinerU output')
                    outputs['model_output'] = content
    return outputs, assets


def _paddle_asset_store(provider):
    root = paddle_native_capture.native_asset_root((provider or {}).get('nativeCaptureTransport'))
    if root is None:
        return None
    address = urlsplit(provider.get('baseUrl', ''))
    if (not isinstance(provider.get('ownedBoot'), dict) or address.scheme != 'http'
            or not address.hostname or not ipaddress.ip_address(address.hostname).is_loopback):
        raise ValueError('Native CAS references require a pinned owned local provider')
    from artifact_store import ArtifactStore
    return ArtifactStore(root)


def _paddle_assets(result, store, provider, *, failed=False):
    source = _paddle_asset_store(provider)
    failure_inline = (failed and source is not None
        and result.get('rawAssetTransport') == paddle_native_capture.FAILED_INLINE_TRANSPORT
        and isinstance(result.get('assetReferenceError'), dict) and 'rawAssetRefs' not in result)
    if failure_inline:
        source = None
    if source is None:
        if not failure_inline and ('rawAssetTransport' in result or 'rawAssetRefs' in result):
            raise ValueError('Native CAS reference transport was not configured')
        supplied = result.get('rawAssets')
    else:
        if result.get('rawAssetTransport') != paddle_native_capture.LOCAL_CAS_TRANSPORT or 'rawAssets' in result:
            raise ValueError('Native CAS response transport differs from its configuration')
        supplied = result.get('rawAssetRefs')
    if not isinstance(supplied, dict):
        raise ValueError('Native Paddle array assets missing')
    assets, imported, size = {}, {}, 0
    for name, value in supplied.items():
        if (not isinstance(name, str) or not name.startswith('arrays/') or not name.endswith('.npy')
                or '..' in name.split('/')):
            raise ValueError('Invalid native Paddle array artifact')
        if source is not None:
            count = value.get('byteCount') if isinstance(value, dict) else None
            if type(count) is not int or count < 0 or size+count > paddle_native_capture.MAX_NATIVE_ARRAY_BYTES:
                raise ValueError('Native Paddle array assets exceed complete capture limit')
            content = source.read_asset(value)
        else:
            if not isinstance(value, str):
                raise ValueError('Invalid native Paddle array artifact')
            content = base64.b64decode(value, validate=True)
        size += len(content)
        if size > paddle_native_capture.MAX_NATIVE_ARRAY_BYTES:
            raise ValueError('Native Paddle array assets exceed complete capture limit')
        assets[name] = content
        imported[name] = store.put_bytes(content, identity_scope='actual_returned_native_array_npy')
    return assets, imported


def _paddle_failed_capture(result, digest, byte_count, store, provider):
    supplied = result.get('rawOutputs')
    if not isinstance(supplied, dict) or set(supplied) != {'model_output'} or not isinstance(supplied['model_output'], str):
        raise ValueError('Failed native Paddle model-output manifest missing')
    raw = base64.b64decode(supplied['model_output'], validate=True)
    manifest = _json(raw)
    if (manifest.get('schema') != 'consense.paddle-native-output-capture.v1'
            or manifest.get('captureStatus') != 'failed_native_capture'
            or manifest.get('input', {}).get('sha256') != digest or manifest['input'].get('byteCount') != byte_count):
        raise ValueError('Failed native Paddle manifest is not bound to the dispatched input')
    _, references = _paddle_assets(result, store, provider, failed=True)
    return {'status': 'failed_capture_bytes_preserved', 'sourceQualityAccepted': False,
            'manifest': store.put_bytes(raw, identity_scope='actual_failed_native_capture_manifest'),
            'assets': references}


def _paddle_native_outputs(result, digest, byte_count, store, *, provider=None):
    supplied = result.get('rawOutputs')
    if supplied is None:
        if ('rawAssetTransport' in result or 'rawAssetRefs' in result
                or (provider or {}).get('nativeCaptureTransport') is not None):
            raise ValueError('Native CAS assets have no model-output manifest')
        return {}, {}, None
    if not isinstance(supplied, dict) or set(supplied) != {'model_output'} or not isinstance(supplied['model_output'], str):
        raise ValueError('Native Paddle must supply an exact model-output manifest')
    raw = base64.b64decode(supplied['model_output'], validate=True)
    manifest = _json(raw)
    capture = result.get('nativeCapture', {})
    if (manifest.get('schema') != 'consense.paddle-native-output-capture.v1'
            or manifest.get('captureStatus') != 'complete_native_arrays_captured'
            or capture.get('status') != manifest['captureStatus'] or capture.get('inputSha256') != digest
            or manifest.get('input', {}).get('sha256') != digest or manifest['input'].get('byteCount') != byte_count
            or not manifest.get('generationAttempts') or not manifest.get('layoutRuns')
            or any(row.get('status') != 'completed' or row.get('terminalStatus') != 'eos_before_cap' for row in manifest['generationAttempts'])
            or any(row.get('status') != 'completed' or not row.get('inputs') or not row.get('outputs') for row in manifest['layoutRuns'])):
        raise ValueError('Native Paddle input/terminal/capture identity is incomplete')
    assets, imported = _paddle_assets(result, store, provider)
    records = []
    def collect(value):
        if isinstance(value, dict):
            if value.get('kind') in ('actual_paddle_tensor', 'actual_numpy_array'):
                records.append(value)
            for item in value.values(): collect(item)
        elif isinstance(value, list):
            for item in value: collect(item)
    collect(manifest)
    if not records or set(assets) != {row.get('assetName') for row in records}:
        raise ValueError('Native array manifest and returned complete asset set differ')
    import numpy as np
    verified = {}
    for row in records:
        content = assets[row['assetName']]
        descriptor = row.get('artifact', {})
        if row['assetName'] not in verified:
            store.verify(descriptor)
            verified[row['assetName']] = descriptor
        elif descriptor != verified[row['assetName']]:
            raise ValueError('Native array repeated reference descriptors differ')
        if descriptor.get('sha256') != imported[row['assetName']]['sha256'] or descriptor.get('byteCount') != len(content):
            raise ValueError('Native array actual bytes differ from their manifest')
        stream = io.BytesIO(content)
        version = np.lib.format.read_magic(stream)
        if version not in ((1, 0), (2, 0)):
            raise ValueError('Unsupported complete native array header')
        shape, _, dtype = (np.lib.format.read_array_header_1_0(stream) if version == (1, 0)
                           else np.lib.format.read_array_header_2_0(stream))
        if (dtype.hasobject or list(shape) != row.get('shape') or str(dtype) != row.get('arrayDtype')
                or math.prod(shape)*dtype.itemsize != len(content)-stream.tell()):
            raise ValueError('Native array dtype/shape/content length differs')
        if row.get('dtype') == 'paddle.bfloat16' and (dtype != np.uint16 or row.get('bf16Encoding') != 'uint16_native_bits'):
            raise ValueError('Native BF16 bits were converted instead of captured')
        expected_dtype = 'uint16' if row.get('dtype') == 'paddle.bfloat16' else str(row.get('dtype', '')).removeprefix('paddle.')
        if str(dtype) != expected_dtype:
            raise ValueError('Native tensor dtype declaration differs from exact array dtype')
    _verify_paddle_capture_claims(manifest, capture, assets)
    return {'model_output': raw}, {'paddle/'+name: content for name, content in assets.items()}, {
        'status': 'complete_native_array_bytes_verified', 'manifestSha256': hashlib.sha256(raw).hexdigest(),
        'assetCount': len(assets), 'runtimeObservation': manifest.get('runtime'),
        'identityScope': 'received_native_capture_bytes_not_independent_loaded_model_or_source_accuracy_verification'}


def _verify_paddle_capture_claims(manifest, capture, assets):
    from paddle_native_capture import validate_parameter_rows
    import numpy as np
    runtime = manifest.get('runtime')
    if not isinstance(runtime, dict) or capture.get('runtime') != runtime:
        raise ValueError('Native runtime declarations differ')
    main = runtime.get('mainParameters', {})
    bf16, fp32 = validate_parameter_rows(main.get('parameters'), main.get('officialFp32Allowlist'))
    if (main.get('parameterCount') != len(main['parameters']) or main.get('bf16ParameterCount') != len(bf16)
            or main.get('officialFp32ParameterCount') != len(fp32) or main.get('officialFp32ActualParameterNames') != fp32
            or main.get('precisionStatus') != ('actual_bf16_cuda_with_official_fp32_parameters' if fp32 else 'actual_bf16_cuda_parameters')):
        raise ValueError('Native parameter counts or precision status disagree with observed rows')

    def tensors(value):
        if isinstance(value, dict):
            if value.get('kind') == 'actual_paddle_tensor':
                return [value]
            return [row for child in value.values() for row in tensors(child)]
        if isinstance(value, list):
            return [row for child in value for row in tensors(child)]
        return []

    for generation in manifest['generationAttempts']:
        inputs, output = generation.get('input'), generation.get('output', {})
        observed = tensors(inputs) + tensors(output)
        if not tensors(inputs) or not observed or any(row.get('place') != 'Place(gpu:0)' for row in observed):
            raise ValueError('Native generation requires actual CUDA0 input/output tensors')
        members = output.get('members')
        if (output.get('kind') != 'tuple' or not isinstance(members, list) or len(members) != 2
                or any(row.get('kind') != 'actual_paddle_tensor' for row in members)
                or members[0].get('dtype') not in ('paddle.int32', 'paddle.int64')):
            raise ValueError('Native generation must retain its actual IDs/scores tuple')
        ids = np.load(io.BytesIO(assets[members[0]['assetName']]), allow_pickle=False)
        scores = np.load(io.BytesIO(assets[members[1]['assetName']]), allow_pickle=False)
        greedy = generation.get('actualResolvedGreedy', {})
        cap, eos, pad = greedy.get('effectiveMaxNewTokens'), greedy.get('eosIds'), greedy.get('padId')
        if (type(cap) is not int or not 1 <= cap <= 8192 or generation.get('kwargs', {}).get('max_new_tokens') != cap
                or not isinstance(eos, list) or not eos or any(type(token) is not int for token in eos)
                or type(pad) is not int or greedy.get('truncInput') is not True
                or ids.ndim != 2 or not ids.shape[0] or ids.shape[1] > cap
                or scores.ndim < 1 or scores.shape[0] != ids.shape[0]):
            raise ValueError('Native resolved generation budget or token boundary is invalid')
        for row in ids.tolist():
            terminal = next((index for index, token in enumerate(row) if token in eos), None)
            if terminal is None or terminal+1 >= cap or any(token != pad for token in row[terminal+1:]):
                raise ValueError('Exact native IDs do not prove EOS before cap with pad-only suffix')
    sessions = runtime.get('layoutSessions')
    if not isinstance(sessions, list) or not sessions:
        raise ValueError('Actual native layout sessions required')
    by_id = {}
    for session in sessions:
        if (type(session.get('session')) is not int or session['session'] in by_id
                or session.get('useGpu') is not True or session.get('device') != 'gpu:0'
                or any(not isinstance(session.get(kind), list) or not session[kind]
                    or any(not isinstance(name, str) or not name for name in session[kind])
                    or len(set(session[kind])) != len(session[kind]) for kind in ('inputs', 'outputs'))):
            raise ValueError('Native layout session identity or complete IO inventory is invalid')
        by_id[session['session']] = session
    for row in manifest['layoutRuns']:
        session = by_id.get(row.get('session'))
        if session is None or any(set(row.get(kind, {})) != set(session[kind]) for kind in ('inputs', 'outputs')):
            raise ValueError('Native layout invocation differs from actual session IO inventory')


def project_complete_regions(bundle, whole_groups, width, height, *, canonical_projection=None):
    """The same bound region projection for live recognition and saved-observation replay."""
    runs = bundle['runs']
    regions, selections = [], []
    if canonical_projection is not None:
        from canonical_projection import selected_complete_page_blocks
        if canonical_projection['manifest']['scope']['requiredPhysicalPages'] != sorted(
                group['physicalPageNo'] for group in whole_groups):
            raise ValueError('Canonical complete-page scope differs from actual page groups')
        units = {part['blockId']: unit['unitId'] for unit in canonical_projection['canonical']['units']
                 for part in unit['parts']}
        selections = [(run, page, block, 'source_review_selected_region', {
            'projectionSha256': canonical_projection['projectionSha256'],
            'manifestSha256': canonical_projection['projectionIdentity']['manifestSha256'],
            'referenceEvidenceSha256': canonical_projection['manifest']['reference']['evidenceSha256'],
            'unitId': units[block['blockId']]})
            for run, page, block in selected_complete_page_blocks(bundle, canonical_projection)]
    else:
        for group in whole_groups:
            channel = group['selectedParser'] or 'paddleocr-vl'
            run = next(run for run in runs if run['parser'] == channel)
            page = next((page for page in run['pages'] if page['physicalPageNo'] == group['physicalPageNo']), None)
            if page is None:
                raise ValueError('Primary parser page is unavailable for ' + channel + ' at physical page '
                                 + str(group['physicalPageNo']) + '; complete parser evidence retained')
            status = group['selectionStatus'] if group['selectedParser'] else 'legacy_primary_candidate_unverified'
            selections.extend((run, page, block, status, None) for block in page['blocks'])
    for run, page, block, status, selection in selections:
        geometry = project_bbox_to_original(run, page, block['bbox'])
        region = {'id': block['blockId'], 'kind': block['label'], 'text': block['text'],
                  'originalText': block['text'], 'readingOrder': block['readingOrder'],
                  'channel': run['parser'], 'semanticStatus': 'unverified',
                  'selectionStatus': status,
                  'geometryStatus': 'unknown', 'providerBlock': block,
                  'originalGeometry': geometry}
        box = block.get('bbox')
        if box and box['coordinateFrame'] in ('raster', 'normalized', 'normalized_1000'):
            x0, y0, x1, y1 = box['xyxy']
            if box['coordinateFrame'] != 'raster':
                scale = 1000 if box['coordinateFrame'] == 'normalized_1000' else 1
                x0, x1 = x0*width/scale, x1*width/scale
                y0, y1 = y0*height/scale, y1*height/scale
            polygon = [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]
            region['polygon'] = polygon
            region['bbox'] = [x0, y0, x1-x0, y1-y0]
            region['geometryStatus'] = 'observed_input_frame' if 0 <= x0 <= x1 <= width and 0 <= y0 <= y1 <= height else 'outside_input_frame'
        if 'table' in block:
            region['table'] = block['table']
        if selection is not None:
            region['canonicalSelection'] = selection
        regions.append(region)
    return regions


def recognize_complete_page(raw, config, store, *, request=_request, source_context=None, line_observer=None,
                            deadline=None, cancelled=None):
    timeout = _config(config)
    configured_deadline = time.monotonic() + timeout
    if deadline is not None and (isinstance(deadline, bool) or not isinstance(deadline, (int, float)) or not math.isfinite(deadline)):
        raise ValueError('Finite monotonic caller deadline required')
    deadline = min(deadline, configured_deadline) if deadline is not None else configured_deadline
    with Image.open(io.BytesIO(raw)) as image:
        width, height = image.size
        media_type, extension = {'PNG': ('image/png', 'png'), 'JPEG': ('image/jpeg', 'jpg'),
                                 'TIFF': ('image/tiff', 'tiff'), 'WEBP': ('image/webp', 'webp')}[image.format]
    input_name = 'page.' + extension
    digest = hashlib.sha256(raw).hexdigest()
    source = {'sourceFilename': input_name, 'mediaType': media_type,
              'sourceSha256': digest, 'pageCount': 1}
    physical_page = 1
    transform = {'kind': 'identity',
                 'inputFrame': {'coordinateFrame': 'raster', 'coordinateUnit': 'pixel',
                    'width': width, 'height': height, 'artifactName': input_name, 'sha256': digest},
                 'targetFrame': {'coordinateFrame': 'original_page', 'coordinateUnit': 'pixel',
                    'width': width, 'height': height, 'physicalPageNo': 1, 'sourceSha256': digest}}
    if source_context is not None:
        source_context = validate_source_context(source_context, raw, width, height, input_name)
        source = source_context['source']
        physical_page = source_context['physicalPageNo']
        transform = source_context['originalPageTransform']
    frame = {'imageSha256': digest, 'width': width, 'height': height,
             'coordinateUnit': 'pixel', 'bboxFormat': 'xywh'}
    responses, attempts, runs, lifecycles = [], [], [], []
    provider_tasks = []
    input_ref = store.put_bytes(raw, identity_scope='exact_parser_input_image_bytes')
    job_id = None
    job_completed = False
    parser = 'paddleocr-vl'
    paddle_capture = None

    def send(url, body=None, method=None):
        if cancelled is not None and cancelled():
            raise RuntimeError('Complete-parser caller cancelled')
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Complete-parser deadline elapsed')
        request_ref = store.put_bytes(body if isinstance(body, bytes) else _bytes(body)) if body is not None else None
        attempt = {'parser': parser, 'url': url, 'method': method or ('POST' if body is not None else 'GET'),
                   'request': request_ref, 'status': 'dispatched_response_unknown'}
        attempts.append(attempt)
        provider_tasks[-1]['httpDispatches'] += 1
        try:
            response = request(url, body=body, timeout=remaining, **({'method': method} if method else {}))
        except Exception as error:
            attempt.update(status='failed', errorType=type(error).__name__,
                httpStatus=getattr(error, 'status', None), responseComplete=getattr(error, 'complete', False))
            if hasattr(error, 'raw'):
                attempt['response'] = store.put_bytes(error.raw, identity_scope='actual_received_http_body_complete' if error.complete else 'actual_received_http_body_incomplete')
                if parser == 'paddleocr-vl' and error.complete and getattr(error, 'status', None) == 503:
                    try:
                        detail = _json(error.raw).get('detail', {})
                        if isinstance(detail, dict) and detail.get('kind') == 'native_capture_failed':
                            attempt['nativeFailureCapture'] = _paddle_failed_capture(detail, digest, len(raw), store, config[parser])
                    except Exception as capture_error:
                        attempt['nativeFailureCapture'] = {'status': 'failed_capture_import_failed',
                            'errorType': type(capture_error).__name__, 'reason': str(capture_error), 'sourceQualityAccepted': False}
            raise
        if not isinstance(response, bytes) or len(response) > MAX_RESPONSE_BYTES:
            raise ValueError('Complete parser must return bounded actual response bytes')
        response_ref = store.put_bytes(response, identity_scope='exact_parser_http_response_bytes')
        attempt.update(status='completed', response=response_ref)
        responses.append({'parser': parser, 'url': url,
                          'request': request_ref,
                          'response': response_ref})
        return response

    try:
        for parser in ('paddleocr-vl', 'mineru'):
            if time.monotonic() >= deadline or cancelled is not None and cancelled():
                raise TimeoutError('Complete-parser caller cancelled or deadline elapsed before provider admission')
            assets = {input_name: raw}
            mapping = [{'parserPageIndex': 0, 'providerInputPageNo': 1, 'physicalPageNo': physical_page,
                        'width': width, 'height': height, 'coordinateUnit': 'pixel',
                        'providerBboxFrame': 'raster' if parser == 'paddleocr-vl' else 'normalized',
                        'inputArtifactName': input_name, 'originalPageTransform': transform}]
            upload_body = {'filename': input_name, 'bytes': len(raw), 'mime_type': media_type,
                           'purpose': 'parse', 'sha256sum': digest}
            options = PADDLE_OPTIONS if parser == 'paddleocr-vl' else {'upload': upload_body, 'parse': MINERU_OPTIONS}
            binding = task_binding(parser, source, mapping,
                {'sha256': digest, 'byteCount': len(raw), 'mediaType': media_type}, config[parser], options, source_context)
            task_record = {'parser': parser, 'taskSha256': hashlib.sha256(_bytes(binding)).hexdigest(),
                           'mode': 'lookup_not_completed', 'status': 'started', 'httpDispatches': 0}
            provider_tasks.append(task_record)
            cached = load_success(store, binding)
            native_binding={'parser':parser,'inputSha256':digest,'inputByteCount':len(raw),
                'originalSourceSha256':source['sourceSha256'],'physicalPageNo':physical_page,
                'profileSha256':hashlib.sha256(_bytes(config[parser]['identity'])).hexdigest()}
            if cached is not None:
                task_record.update(mode='completed_task_reuse', successRef=cached['successRef'],
                    historicalLifecycleRef=cached['historicalLifecycleRef'])
                outputs, assets = cached['outputs'], cached['assets']
                if parser == 'paddleocr-vl': paddle_capture = cached['captureMetadata']
            else:
                task_record['mode'] = 'fresh_provider'
                provider_config=dict(config[parser],taskBinding=native_binding)
                with owned_native_provider(provider_config, deadline-time.monotonic(),
                        receipt_dir=store.root / 'jobs' / uuid.uuid4().hex / parser,
                        **({'cancelled': cancelled} if cancelled is not None else {})) as lifecycle:
                    lifecycles.append(lifecycle)
                    base = config[parser]['baseUrl'].rstrip('/')
                    if parser == 'paddleocr-vl':
                        body = {'file': base64.b64encode(raw).decode('ascii'), **PADDLE_OPTIONS}
                        wire = send(base + '/layout-parsing', body)
                        value = _json(wire)
                        if value.get('errorCode', 0) != 0 or len(value.get('result', {}).get('layoutParsingResults', [])) != 1:
                            raise ValueError('Paddle full-page response is failed or incomplete')
                        outputs = {'page_json': wire}
                        captured_outputs, captured_assets, paddle_capture = _paddle_native_outputs(value['result'], digest, len(raw), store,
                            provider=config[parser])
                        if config[parser].get('requireNativeOutputCapture') and paddle_capture is None:
                            raise ValueError('Configured native Paddle output capture is missing; pruned JSON is insufficient')
                        outputs.update(captured_outputs); assets.update(captured_assets)
                        for result in value['result']['layoutParsingResults']:
                            images = dict(result.get('markdown', {}).get('images') or {})
                            images.update(result.get('outputImages') or {})
                            for name, encoded in images.items():
                                if not isinstance(encoded, str) or encoded.startswith(('http:', 'https:')):
                                    raise ValueError('Paddle artifacts must be returned inline, not fetched implicitly')
                                if encoded.startswith('data:'):
                                    encoded = encoded.split(',', 1)[1]
                                assets['paddle/' + name] = base64.b64decode(encoded, validate=True)
                    else:
                        upload = _json(send(base + '/uploads', upload_body))
                        if upload.get('status') != 'completed':
                            upload_id = upload.get('id', '')
                            if not re.fullmatch(r'upload_[A-Za-z0-9_-]+', upload_id):
                                raise ValueError('MinerU upload identity invalid')
                            send(base + '/uploads/' + upload_id + '/content', raw, method='PUT')
                            upload = _json(send(base + '/uploads/' + upload_id + '/complete', {'sha256sum': digest}))
                        source_file_id = upload.get('file', {}).get('id', '')
                        if (upload.get('status') != 'completed' or upload.get('sha256sum') != digest or upload.get('bytes') != len(raw)
                                or not re.fullmatch(r'file-[A-Za-z0-9_-]+', source_file_id)):
                            raise ValueError('MinerU uploaded source identity differs')
                        created = _json(send(base + '/parse/jobs', {
                            'files': [{'source': {'type': 'file_id', 'file_id': source_file_id}}], **MINERU_OPTIONS}))
                        job_id = created.get('job_id', '')
                        if not re.fullmatch(r'job_[A-Za-z0-9_-]+', job_id):
                            raise ValueError('MinerU returned an invalid job identity')
                        while True:
                            value = _json(send(base + '/parse/jobs/' + job_id))
                            if value.get('job_id') != job_id:
                                raise ValueError('MinerU job identity changed')
                            status = value.get('status')
                            if status == 'completed':
                                job_completed = True
                                break
                            if status not in ('queued', 'running'):
                                raise ValueError('MinerU job did not complete: ' + str(status))
                            time.sleep(min(.2, max(0, deadline-time.monotonic())))
                        files = value.get('files', [])
                        if len(files) != 1 or files[0].get('status') != 'completed' or files[0].get('file_id') != source_file_id:
                            raise ValueError('MinerU full input did not complete')
                        file_id = files[0].get('output_files', {}).get('zip', {}).get('file_id', '')
                        if not re.fullmatch(r'file-[A-Za-z0-9_-]+', file_id):
                            raise ValueError('MinerU complete artifact identity unavailable')
                        outputs, captured = _mineru_outputs(send(base + '/files/' + file_id + '/content'))
                        assets.update({'mineru/' + name: value for name, value in captured.items()})
                    lifecycle['transaction']={'status':'persisted','taskBinding':native_binding,'input':input_ref,
                        'attempts':copy.deepcopy([attempt for attempt in attempts if attempt['parser']==parser]),
                        'rawOutputs':{name:store.put_bytes(content,identity_scope='exact_native_parser_output_bytes')
                            for name,content in outputs.items()}}
            outputs, assets = dict(sorted(outputs.items())), dict(sorted(assets.items()))
            run = normalize_parser_run(parser, outputs, source, config[parser]['identity'], mapping,
                artifacts=assets, required_artifacts=list(assets), artifact_store=store,
                provider_input_identity={'mediaType': media_type, 'inputSha256': digest,
                    'inputArtifactName': input_name, 'pageCount': 1, 'pageIndexMapMeaning': 'provider_input_document'},
                    terminal={'status': 'completed', 'pageResults': [{'parserPageIndex': 0, 'status': 'completed'}]})
            within_deadline = time.monotonic() < deadline
            if cached is not None and not within_deadline:
                raise TimeoutError('Complete-parser deadline elapsed during provider capture validation')
            if cached is not None and run['completeness']['status'] != 'complete':
                raise ValueError('Cached successful provider no longer has complete material')
            if cached is None and within_deadline:
                success = commit_success(store, binding, outputs, assets, lifecycle, run['completeness'],
                                         paddle_capture if parser == 'paddleocr-vl' else None, deadline=deadline)
                task_record.update(cacheDisposition='success_committed' if success else 'not_eligible_for_success_reuse')
                if success is not None: task_record['successRef'] = success
            elif cached is None:
                task_record['cacheDisposition'] = 'not_stored_total_deadline_elapsed'
            task_record['status'] = 'completed'
            runs.append(run)
        review_config = config
        if 'sourceReviews' in config:
            run_ids = {run['runId'] for run in runs}
            review_config = next((review for review in config['sourceReviews']
                                  if set(review['runIds']) == run_ids), {})
        bundle = build_dual_parser_evidence(runs, route_acceptance_manifest=review_config.get('parserRouteAcceptance'), artifact_store=store)
        bundle_ref = store.put_bytes(_bytes(bundle))
        whole_groups = select_whole_parser_groups(bundle, config.get('wholeParserFeaturePolicy'), artifact_store=store)
        if not whole_groups:
            raise ValueError('Requested parser page is unavailable at physical page '
                             + str(physical_page) + '; complete parser evidence retained')
        for group in whole_groups:
            group['dualEvidenceRef'] = bundle_ref
        canonical = None
        if review_config.get('canonicalAdoptionManifest') is not None:
            from canonical_projection import project_canonical
            canonical = project_canonical(bundle, review_config['canonicalAdoptionManifest'], artifact_store=store)
        regions = project_complete_regions(bundle, whole_groups, width, height, canonical_projection=canonical)
        original_text = '\n'.join(region['text'] for region in regions if isinstance(region['text'], str))
        auxiliary = None
        if canonical is not None and line_observer is not None:
            auxiliary = {'status': 'not_executed_source_reviewed_complete_page', 'addedRegions': 0,
                         'sourceQualityAccepted': False}
        elif line_observer is not None and source_context is not None:
            started = time.monotonic()
            auxiliary = {'status': 'not_started_budget_exhausted', 'remainingSecondsBefore': deadline-started,
                         'synchronousNativeCancellation': 'not_supported', 'sourceQualityAccepted': False}
            if started < deadline and not (cancelled is not None and cancelled()):
                try:
                    from ocr_layout_coverage import layout_gap_regions
                    capture = line_observer(raw, store)
                    auxiliary['observationRef'] = capture['artifactDescriptor']
                    additions, coverage = layout_gap_regions(capture, runs, frame, physical_page, store)
                    auxiliary.update(coverage)
                    if time.monotonic() < deadline and not (cancelled is not None and cancelled()):
                        regions.extend(additions)
                    else:
                        auxiliary.update(status='observed_after_deadline_unapplied', addedRegions=0)
                except Exception as error:
                    auxiliary.update(status='failed', errorType=type(error).__name__, error=str(error))
            auxiliary['elapsedSeconds'] = time.monotonic()-started
            auxiliary['deadlineExceeded'] = time.monotonic() >= deadline
        value = {'text': original_text,
                'lines': [], 'regions': regions, 'wholePageGroups': whole_groups, 'frame': frame, 'adoptionStatus': 'candidate_unverified',
                'parserRuns': [{'parser': run['parser'], 'runId': run['runId'],
                                'identity': run['identity'], 'captureStatus': run['captureStatus']} for run in runs],
                'dualEvidenceRef': bundle_ref,
                'provenance': {'engine': 'complete-paddle-mineru-native-apis', 'inputSha256': digest,
                    'terminal': 'completed', 'modelRevision': None, 'observationPurpose': 'parse',
                    'sourceContext': source_context,
                    'sourceBindingStatus': 'caller_bound_original_source_unverified_original_bytes' if source_context else 'raster_input_only',
                    'configSha256': hashlib.sha256(_bytes(config)).hexdigest(),
                    'runtimeIdentityStatus': 'operator_declaration_not_active_runtime_verification',
                    'paddleNativeCapture': paddle_capture,
                    'rawResponseSha256': bundle_ref['sha256'], 'rawResponses': responses,
                    'providerLifecycles': lifecycles, 'providerTasks': provider_tasks},
                'coverage': {'sourceContentStatus': 'unknown', 'structureStatus': 'unverified',
                    'dualExecutionComplete': all(task['mode'] == 'fresh_provider' for task in provider_tasks),
                    'freshProviderTasks': sum(task['mode'] == 'fresh_provider' for task in provider_tasks),
                    'completedProviderTaskHits': sum(task['mode'] == 'completed_task_reuse' for task in provider_tasks),
                    'dualResultAssemblyComplete': True, 'dualParserComplete': False,
                    'sourceQualityAccepted': False, 'captureCompleteness': bundle['dualCompleteness']}}
        if auxiliary is not None:
            value['coverage']['auxiliaryLineCoverage'] = auxiliary
        if canonical is not None:
            value['provenance']['canonicalProjectionRef'] = store.put_bytes(_bytes(canonical))
        return value
    except Exception as error:
        if provider_tasks and provider_tasks[-1]['status'] != 'completed':
            provider_tasks[-1].update(status='failed', errorType=type(error).__name__)
        submitted = any(attempt['parser'] == 'mineru' and attempt['method'] == 'POST'
                        and attempt['url'].endswith('/parse/jobs') for attempt in attempts)
        cleanup = {'status': 'not_applicable' if job_completed or not submitted else 'remote_submission_or_cancellation_unknown'}
        if job_id and not job_completed:
            try:
                cancellation = request(config['mineru']['baseUrl'].rstrip('/') + '/parse/jobs/' + job_id,
                                       body=None, method='DELETE', timeout=min(2., timeout))
                cancellation_ref = store.put_bytes(cancellation, identity_scope='exact_owned_job_cancellation_response')
                cancelled = _json(cancellation)
                cleanup = {'status': 'cancelled' if cancelled.get('status') == 'canceled' and cancelled.get('job_id') == job_id else 'cancellation_not_confirmed',
                           'response': cancellation_ref}
            except Exception as cleanup_error:
                cleanup = {'status': 'cancellation_unknown', 'errorType': type(cleanup_error).__name__,
                           'httpStatus': getattr(cleanup_error, 'status', None)}
                if hasattr(cleanup_error, 'raw'):
                    cleanup.update(response=store.put_bytes(cleanup_error.raw,
                        identity_scope='actual_owned_job_cancellation_http_body'),
                        responseComplete=cleanup_error.complete)
        receipt = store.put_bytes(_bytes({'status': 'failed', 'inputSha256': digest,
            'input': input_ref, 'attempts': attempts, 'completedResponses': responses, 'failedProvider': parser,
            'ownedJobId': job_id, 'remoteCleanup': cleanup, 'providerLifecycles': lifecycles, 'providerTasks': provider_tasks,
            'errorType': type(error).__name__, 'reason': str(error)}))
        raise CompleteOcrError(str(error), receipt) from error
