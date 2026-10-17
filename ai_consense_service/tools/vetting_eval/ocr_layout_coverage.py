"""Actual CPU line observations and conservative, geometry-only layout gaps.

No OCR text is adopted. Existing independent provider pages remain unchanged.
"""
import hashlib
import io
import json
import math
from pathlib import Path
import re
import time


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def observe_cpu_lines(engine, raw, recipe_sha256, store):
    """One normal full-page call under the caller's engine lock; retain failures."""
    from PIL import Image
    import numpy as np
    from ocr_response import baseline_observation
    from ocr_stage_probe import observe_ocr_stages, plain, image_identity

    started, stages = time.monotonic(), {}
    record = {'schema': 'consense.cpu-page-line-observation.v1', 'status': 'failed',
              'inputSha256': hashlib.sha256(raw).hexdigest(), 'recipeSha256': recipe_sha256,
              'observerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'fullPageOcrCalls': 0, 'sourceQualityAccepted': False, 'actualSessions': []}
    try:
        if not isinstance(recipe_sha256, str) or not re.fullmatch('[a-f0-9]{64}', recipe_sha256):
            raise ValueError('Auxiliary OCR requires the existing pinned CPU recipe')
        for name in ('text_det', 'text_cls', 'text_rec'):
            inference = getattr(getattr(engine, name), 'session', None)
            session = getattr(inference, 'session', None)
            if session is None and hasattr(inference, 'get_providers'):
                session = inference
            providers = session.get_providers() if session is not None else None
            record['actualSessions'].append({'component': name, 'providers': providers})
            if providers != ['CPUExecutionProvider']:
                raise ValueError('Auxiliary OCR actual session is not CPU-only: ' + name)
        with Image.open(io.BytesIO(raw)) as opened:
            image = opened.convert('RGB')
        record['frame'] = {'imageSha256': record['inputSha256'], 'width': image.width,
                           'height': image.height, 'coordinateUnit': 'pixel', 'bboxFormat': 'xywh'}
        rgb = np.asarray(image)
        record['sourceImagePixels'] = image_identity(rgb)
        with observe_ocr_stages(engine, stages):
            if stages['lineStageProbe']['status'] != 'observing':
                raise ValueError('Actual CPU runtime does not support line-stage observation')
            record['fullPageOcrCalls'] = 1
            result = engine(rgb)
        record['rawFinal'] = plain({'texts': result.txts, 'scores': result.scores, 'polygons': result.boxes})
        record['finalObservation'] = baseline_observation(result, image, raw)
        record['status'] = 'observed' if stages['lineStageProbe']['status'] == 'complete' else 'unknown_incomplete_stages'
        if record['status'] == 'observed':
            reread = None
            try:
                from ocr_line_reread import reread_line_groups
                record['lineRereadModuleSha256'] = hashlib.sha256(
                    Path(__file__).with_name('ocr_line_reread.py').read_bytes()).hexdigest()
                reread = reread_line_groups(engine, rgb, stages['lineStageProbe']['pages'][0], recipe_sha256)
                _json(reread)
                record['lineReread'] = reread
            except Exception as error:
                record['lineReread'] = {'status': 'failed_no_retry', 'errorType': type(error).__name__,
                    'error': str(error), 'automaticBodyReplacement': False, 'sourceQualityAccepted': False}
                if reread is not None:
                    record['lineReread']['diagnosticRef'] = store.put_bytes(
                        repr(reread).encode('utf-8', errors='backslashreplace'),
                        identity_scope='non_json_reread_diagnostic_repr_not_raw_model_bytes')
    except Exception as error:
        record.update(errorType=type(error).__name__, error=str(error))
    record.update(stages=plain(stages), elapsedSeconds=time.monotonic()-started,
                  synchronousNativeCancellation='not_supported')
    try:
        serialized = _json(record)
    except (TypeError, ValueError) as error:
        diagnostic = store.put_bytes(repr(record).encode('utf-8', errors='backslashreplace'),
            identity_scope='non_json_diagnostic_repr_not_raw_model_bytes')
        record = {'schema': 'consense.cpu-page-line-observation.v1', 'status': 'failed',
            'inputSha256': hashlib.sha256(raw).hexdigest(),
            'recipeSha256': recipe_sha256 if isinstance(recipe_sha256, str) else None,
            'fullPageOcrCalls': record['fullPageOcrCalls'], 'sourceQualityAccepted': False,
            'errorType': type(error).__name__, 'error': 'Observation JSON serialization failed: ' + str(error),
            'diagnosticFormat': 'non_json_diagnostic_repr', 'diagnosticRef': diagnostic,
            'synchronousNativeCancellation': 'not_supported'}
        serialized = _json(record)
    descriptor = store.put_bytes(serialized, identity_scope='actual_complete_cpu_ocr_line_stages')
    return {'observation': record, 'artifactDescriptor': descriptor}


def _numbers(values, count):
    return isinstance(values, list) and len(values) == count and all(
        not isinstance(v, bool) and isinstance(v, (int, float)) and math.isfinite(v) for v in values)


def _raster_box(box, width, height):
    if not isinstance(box, dict) or box.get('origin') != 'top-left' or (
            box.get('pageWidth') != width or box.get('pageHeight') != height):
        raise ValueError('Provider bbox is not in the actual complete-page frame')
    frame = box.get('coordinateFrame')
    units = {'raster': 'pixel', 'normalized': 'ratio', 'normalized_1000': 'ratio_1000'}
    if frame not in units or box.get('coordinateUnit') != units[frame] or not _numbers(box.get('xyxy'), 4):
        raise ValueError('Provider bbox frame or values are unknown')
    sx, sy = (1, 1) if frame == 'raster' else (width, height) if frame == 'normalized' else (width/1000, height/1000)
    a, b, c, d = box['xyxy']
    result = [a*sx, b*sy, c*sx, d*sy]
    if not (0 <= result[0] < result[2] <= width and 0 <= result[1] < result[3] <= height):
        raise ValueError('Provider bbox is degenerate or outside the actual page')
    return result


def line_coverage(line, box):
    area = (line[2]-line[0])*(line[3]-line[1])
    if area <= 0:
        raise ValueError('Detected line must have positive area')
    return (max(0, min(line[2], box[2])-max(line[0], box[0])) *
            max(0, min(line[3], box[3])-max(line[1], box[1])) / area)


def _convex_quad(polygon):
    if len({tuple(point) for point in polygon}) != 4:
        return False
    crosses = []
    for i in range(4):
        a, b, c = polygon[i], polygon[(i+1) % 4], polygon[(i+2) % 4]
        crosses.append((b[0]-a[0])*(c[1]-b[1]) - (b[1]-a[1])*(c[0]-b[0]))
    return all(value > 0 for value in crosses) or all(value < 0 for value in crosses)


def _active_gap_lines(record, page):
    """Use whole complete child groups; preserve every original parent in CAS."""
    originals = [(line, '/stages/lineStageProbe/pages/0/lines/' + str(i), {})
                 for i, line in enumerate(page['lines'])]
    reread = record.get('lineReread', {})
    if not isinstance(reread, dict):
        return originals, {'status': 'unknown_invalid_reread_record', 'groups': []}
    ledger = {'status': reread.get('status', 'not_present'), 'groups': []}
    if reread.get('status') != 'completed_candidate_observations_unscored':
        return originals, ledger
    ids = [line.get('lineId') for line in page['lines']]
    groups = reread.get('groups')
    pixels = record.get('sourceImagePixels')
    if (reread.get('originalParentObservations') != page['lines']
            or reread.get('recipeSha256') != record.get('recipeSha256')
            or not isinstance(pixels, dict) or pixels.get('dtype') != 'uint8'
            or pixels.get('shape') != [record['frame']['height'], record['frame']['width'], 3]
            or page.get('originalImageShape') != pixels.get('shape')
            or reread.get('sourceImagePixels') != pixels
            or not isinstance(groups, list) or any(not isinstance(key, str) or not key.strip() for key in ids)
            or len(set(ids)) != len(ids)):
        return originals, dict(ledger, status='unknown_inconsistent_reread_binding')
    member_ids, group_ids, child_ids = [], [], []
    for group in groups:
        if (not isinstance(group, dict) or not isinstance(group.get('memberParentIds'), list)
                or not group['memberParentIds'] or not isinstance(group.get('children'), list)
                or not isinstance(group.get('groupId'), str) or not group['groupId'].strip()):
            return originals, dict(ledger, status='unknown_inconsistent_group_identity')
        member_ids.extend(group['memberParentIds'])
        group_ids.append(group['groupId'])
        child_ids.extend(child.get('childId') if isinstance(child, dict) else None for child in group['children'])
    if (any(not isinstance(key, str) or key not in ids for key in member_ids)
            or len(set(member_ids)) != len(member_ids) or len(set(group_ids)) != len(group_ids)
            or any(not isinstance(key, str) or not key.strip() for key in child_ids)
            or len(set(child_ids)) != len(child_ids) or set(child_ids) & set(ids)
            or isinstance(reread.get('selectedChildCrops'), bool)
            or reread.get('selectedChildCrops') != len(child_ids) or len(child_ids) > 64):
        return originals, dict(ledger, status='unknown_inconsistent_group_membership_or_budget')
    replacements = {}
    width, height = record['frame']['width'], record['frame']['height']
    from ocr_line_reread import _groups
    expected, _ = _groups(page['lines'], width, height)
    if [(group['memberParentIds'], group.get('splitPolygon'), group.get('unionContextBboxXYXY')) for group in groups] != [
            (group['memberParentIds'], group['splitPolygon'], group['unionContextBboxXYXY']) for group in expected]:
        return originals, dict(ledger, status='unknown_incomplete_actual_geometry_groups')
    for i, group in enumerate(groups):
        children, split = group['children'], group.get('split')
        split = split if isinstance(split, dict) else {}
        bounds = split.get('actualChildBoundsRows', [])
        valid_bounds = (isinstance(bounds, list) and len(bounds) >= 2
            and all(_numbers(bound, 2) and 0 <= bound[0] < bound[1] for bound in bounds)
            and bounds[0][0] == 0 and bounds[-1][1] == split.get('uprightHeight')
            and all(a[1] == b[0] for a, b in zip(bounds, bounds[1:])))
        complete = (group.get('status') == 'fresh_candidate_children' and len(children) >= 2
                    and valid_bounds and len(children) == len(bounds))
        active = []
        for j, child in enumerate(children):
            mapping, recognition = child.get('sourceMapping'), child.get('recognition')
            mapping = mapping if isinstance(mapping, dict) else {}
            recognition = recognition if isinstance(recognition, dict) else {}
            polygon = mapping.get('originalImagePolygon')
            child_bounds = child.get('rectifiedBoundsXYXY')
            valid = (child.get('groupId') == group['groupId'] and child.get('parentIds') == group['memberParentIds']
                and child.get('origin') == 'fresh' and valid_bounds and j < len(bounds)
                and child_bounds == [0, bounds[j][0], split.get('uprightWidth'), bounds[j][1]]
                and isinstance(polygon, list) and len(polygon) == 4
                and all(_numbers(point, 2) for point in polygon) and _convex_quad(polygon)
                and all(0 <= point[0] <= width and 0 <= point[1] <= height for point in polygon)
                and all(isinstance(child.get(key), dict) for key in ('trim', 'trimMapping', 'classification'))
                and isinstance(recognition.get('rawText'), str)
                and _numbers([recognition.get('rawConfidence')], 1))
            complete = complete and valid
            if valid:
                line = {'lineId': child['childId'], 'originalImagePolygonBeforeFiltering': polygon,
                    'recognition': {'text': recognition['rawText'], 'confidence': recognition['rawConfidence']},
                    'terminalStage': recognition.get('filterDisposition', 'unknown')}
                active.append((line, '/lineReread/groups/' + str(i) + '/children/' + str(j),
                               {'groupId': group['groupId'], 'parentIds': group['memberParentIds']}))
        disposition = 'complete_child_group_active' if complete else 'whole_original_parent_group_retained'
        ledger['groups'].append({'groupId': group['groupId'], 'parentIds': group['memberParentIds'],
                                 'disposition': disposition, 'rereadStatus': group.get('status')})
        if complete:
            for key in group['memberParentIds']:
                replacements[key] = active if key == group['memberParentIds'][0] else []
    active = []
    for line, pointer, relation in originals:
        active.extend(replacements.get(line['lineId'], [(line, pointer, relation)]))
    return active, ledger


def layout_gap_regions(capture, runs, frame, physical_page, store):
    """Compare actual lines against both complete layouts; never mutate inputs."""
    from structured_parser_evidence import project_bbox_to_original

    record, reference = capture['observation'], capture['artifactDescriptor']
    if store.read_asset(reference) != _json(record):
        raise ValueError('CPU line observation differs from its captured bytes')
    ledger = {'status': 'unknown', 'observationRef': reference, 'threshold': 0.5,
              'overlapMethod': 'bbox-envelope max intersection / detected-line envelope area; not polygon or glyph coverage',
              'sourceQualityAccepted': False, 'lines': [], 'addedRegions': 0,
              'fullPageOcrCalls': record.get('fullPageOcrCalls'), 'observationStatus': record.get('status')}
    if record.get('status') != 'observed':
        ledger['reason'] = 'cpu_observation_failed_or_incomplete'
        if record.get('status') == 'failed':
            ledger.update(status='failed', errorType=record.get('errorType'), error=record.get('error'))
        return [], ledger
    if record.get('frame') != frame or record.get('inputSha256') != frame.get('imageSha256'):
        raise ValueError('CPU observation and actual parser image differ')
    probe = record.get('stages', {}).get('lineStageProbe', {})
    pages = probe.get('pages', [])
    if (probe.get('status') != 'complete' or not probe.get('wrappedInstanceRestored')
            or len(pages) != 1 or not pages[0].get('mappingVerified') or not pages[0].get('complete')):
        ledger['reason'] = 'cpu_line_geometry_unverified'
        return [], ledger
    width, height = frame['width'], frame['height']
    provider_pages, boxes = {}, {}
    try:
        for run in runs:
            parser = run['parser']
            if parser in provider_pages:
                raise ValueError('Duplicated provider page')
            matched = [p for p in run['pages'] if p['physicalPageNo'] == physical_page]
            if len(matched) != 1:
                raise ValueError('Provider physical page is unavailable')
            page = matched[0]
            if (page['mapping']['width'] != width or page['mapping']['height'] != height
                    or page.get('inputArtifact', {}).get('sha256') != frame['imageSha256']):
                raise ValueError('Provider and CPU complete-page image differ')
            provider_pages[parser] = (run, page)
            boxes[parser] = []
            for block in page['blocks']:
                if project_bbox_to_original(run, page, block.get('bbox'))['status'] != 'verified':
                    raise ValueError('Provider original geometry is not bound')
                boxes[parser].append(_raster_box(block.get('bbox'), width, height))
        if set(provider_pages) != {'paddleocr-vl', 'mineru'}:
            raise ValueError('Both independent provider layouts are required')
    except (KeyError, TypeError, ValueError) as error:
        ledger.update(reason='provider_geometry_unknown', detail=str(error))
        return [], ledger
    active_lines, ledger['lineReread'] = _active_gap_lines(record, pages[0])
    additions, seen = [], set()
    for index, (line, pointer, relation) in enumerate(active_lines):
        row = {'lineId': line.get('lineId'), 'status': 'unknown'}
        ledger['lines'].append(row)
        polygon = line.get('originalImagePolygonBeforeFiltering')
        if (not isinstance(row['lineId'], str) or row['lineId'] in seen
                or not isinstance(polygon, list) or len(polygon) != 4
                or not all(_numbers(point, 2) for point in polygon) or not _convex_quad(polygon)):
            row['reason'] = 'line_identity_or_polygon_invalid'
            continue
        seen.add(row['lineId'])
        xs, ys = [p[0] for p in polygon], [p[1] for p in polygon]
        box = [min(xs), min(ys), max(xs), max(ys)]
        if not (0 <= box[0] < box[2] <= width and 0 <= box[1] < box[3] <= height):
            row['reason'] = 'line_degenerate_or_outside_input'
            continue
        row['maximumLineAreaCoverage'] = {parser: max((line_coverage(box, b) for b in values), default=0)
                                           for parser, values in boxes.items()}
        if any(value >= 0.5 for value in row['maximumLineAreaCoverage'].values()):
            row['status'] = 'represented_by_existing_layout'
            continue
        run, page = provider_pages['paddleocr-vl']
        geometry = project_bbox_to_original(run, page, {'xyxy': box, 'coordinateFrame': 'raster',
            'coordinateUnit': 'pixel', 'origin': 'top-left', 'pageWidth': width, 'pageHeight': height})
        if geometry['status'] != 'verified':
            row['reason'] = 'line_original_geometry_unknown'
            continue
        recognition = line.get('recognition', {})
        if not isinstance(recognition.get('text'), str):
            row['reason'] = 'actual_recognition_text_unavailable'
            continue
        row['status'] = 'unassigned_geometry_candidate'
        region = {'id': 'rapidocr-gap:' + frame['imageSha256'] + '/' + row['lineId'],
            'kind': 'unassigned_text_line', 'text': '', 'originalText': recognition['text'],
            'bbox': [box[0], box[1], box[2]-box[0], box[3]-box[1]], 'polygon': polygon,
            'channel': 'rapidocr', 'geometryStatus': 'observed_input_frame', 'originalGeometry': geometry,
            'semanticStatus': 'unverified',
            'readingOrder': {'declared': None, 'outputSequence': index, 'status': 'detection_sequence_unverified'},
            'unknowns': ['Detector geometry and OCR text are independent unverified observations; no writing-area, state, owner, value or ordering assignment'],
            'assetRefs': [{'kind': 'actual_cpu_line_observation', 'lineId': row['lineId'],
                **relation,
                'terminalStage': line.get('terminalStage', 'unknown'), 'recognitionConfidence': recognition.get('confidence'),
                'recipeSha256': record.get('recipeSha256'),
                'observation': {'kind': 'complete_cpu_line_reread' if relation else 'complete_cpu_line_stages',
                    'jsonPointer': pointer,
                    'artifactDescriptor': reference}}]}
        additions.append(region)
    ledger.update(status='observed_unverified', addedRegions=len(additions))
    return additions, ledger
