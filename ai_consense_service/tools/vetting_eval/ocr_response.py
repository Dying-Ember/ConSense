"""Lossless OCR result projection and optional vetting observations.

The caller owns the engine lock. Candidate cells and rejected text remain
separate from returned OCR lines and can never become trusted source content.
"""
import hashlib
import json
import math
import time


class OcrProjectionError(ValueError):
    pass


def result_lines(result, image, *, include_polygons=False):
    texts, scores, boxes = result.txts, result.scores, result.boxes
    if texts is None:
        if (scores is not None and len(scores)) or (boxes is not None and len(boxes)):
            raise OcrProjectionError('OCR has coordinates/scores without corresponding text')
        return []
    if scores is None or boxes is None or len(texts) != len(scores) or len(texts) != len(boxes):
        raise OcrProjectionError('OCR text, confidence and polygon counts differ; no partial zip projection')
    lines = []
    for ordinal, (text, score, box) in enumerate(zip(texts, scores, boxes)):
        if not isinstance(text, str) or not math.isfinite(float(score)):
            raise OcrProjectionError('OCR text/confidence has an unsupported value')
        polygon = box.tolist() if hasattr(box, 'tolist') else box
        if len(polygon) != 4 or any(len(p) != 2 or not all(math.isfinite(float(v)) for v in p) for p in polygon):
            raise OcrProjectionError('OCR polygon must contain four finite coordinate pairs')
        xs, ys = [float(p[0]) for p in polygon], [float(p[1]) for p in polygon]
        left, top = max(0., min(xs)), max(0., min(ys))
        right, bottom = min(float(image.width), max(xs)), min(float(image.height), max(ys))
        line = {'text': text, 'confidence': float(score),
                'bbox': [left, top, max(0., right-left), max(0., bottom-top)]}
        if include_polygons:
            line.update(lineId='ocr-line-' + str(ordinal), inputImagePolygon=polygon)
        lines.append(line)
    return lines


def baseline_observation(result, image, input_bytes):
    """Carry actual line observations; RapidOCR is not a complete layout parser."""
    observed = result_lines(result, image, include_polygons=True)
    lines = [{key: row[key] for key in ('text', 'confidence', 'bbox')} for row in observed]
    regions = []
    for row in observed:
        polygon = [[float(x), float(y)] for x, y in row['inputImagePolygon']]
        xs, ys = [p[0] for p in polygon], [p[1] for p in polygon]
        regions.append({'id': row['lineId'], 'kind': 'text', 'text': row['text'],
                        'originalText': row['text'], 'confidence': row['confidence'],
                        'polygon': polygon,
                        'bbox': [min(xs), min(ys), max(xs)-min(xs), max(ys)-min(ys)],
                        'geometryStatus': 'inside_input_frame' if all(
                            0 <= x <= image.width and 0 <= y <= image.height for x, y in polygon)
                            else 'outside_input_frame',
                        'channel': 'rapidocr', 'semanticStatus': 'unverified'})
    input_sha = hashlib.sha256(input_bytes).hexdigest()
    raw_lines = json.dumps(regions, ensure_ascii=False, sort_keys=True,
                           separators=(',', ':'), allow_nan=False).encode('utf-8')
    return {'text': '\n'.join(row['text'] for row in lines), 'lines': lines,
            'regions': regions,
            'frame': {'imageSha256': input_sha, 'width': image.width, 'height': image.height,
                      'coordinateUnit': 'pixel', 'bboxFormat': 'xywh'},
            'provenance': {'engine': 'RapidOCR CPU baseline', 'modelRevision': None,
                           'configSha256': None, 'inputSha256': input_sha,
                           'terminal': 'completed',
                           'rawResponseSha256': hashlib.sha256(raw_lines).hexdigest(),
                           'rawIdentityScope': 'canonical_json_from_actual_line_observations',
                           'limitations': ['Model/config identity not established by line output',
                                           'No complete layout or dual-parser execution']},
            'coverage': {'sourceContentStatus': 'unknown', 'structureStatus': 'unverified',
                         'dualParserComplete': False, 'observedRegionCount': len(regions)}}


def recognize_vetting_page(engine, image):
    from ocr_stage_probe import observe_ocr_stages, plain
    from ocr_table_geometry import recover_ruled_tables
    observations = {}
    started = time.perf_counter()
    with observe_ocr_stages(engine, observations):
        result = engine(image)
    # Project the actual OCR output first. Optional diagnostics must not erase it.
    lines = result_lines(result, image, include_polygons=True)
    payload = {'text': '\n'.join(line['text'] for line in lines), 'lines': lines,
               'imageWidth': image.width, 'imageHeight': image.height,
               'seconds': time.perf_counter()-started, 'engine': 'RapidOCR CPU baseline',
               'qualityStatus': 'needs_review', 'humanConfirmed': False,
               'lineStageDiagnostics': plain(observations['lineStageProbe'])}
    try:
        table_lines = [{'id': line['lineId'], 'text': line['text'], 'confidence': line['confidence'],
                        'polygon': line['inputImagePolygon']} for line in lines]
        candidates = recover_ruled_tables(image, table_lines)
        payload['rasterTableCandidates'] = {'coordinateFrame': 'exact OCR input raster',
            'applied': False, 'humanConfirmed': False, 'result': candidates}
    except Exception as error:
        payload['rasterTableCandidates'] = {'status': 'failed', 'applied': False,
            'humanConfirmed': False, 'errorType': type(error).__name__,
            'unavailableReason': 'raster_table_observation_failed'}
    payload['limitations'] = ['Returned OCR text requires review; engine confidence is not accuracy',
        'Rejected lines are diagnostics, not trusted body text',
        'Raster candidates are not native cells or verified headers',
        'No assigned text does not establish a blank cell']
    return payload
