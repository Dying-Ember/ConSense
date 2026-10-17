"""Geometry-only line rereads using an existing, caller-locked pinned engine.

Inputs are the complete uint8 RGB raster and its actual mapped stage page.
Every eligible group is fresh; no source cache, model construction or file IO.
Returned text and geometry remain candidates, never field/state/body claims.
"""
import copy
import math
import re
import time

from ocr_stage_probe import image_identity, plain


def _geometry(polygon, width, height):
    import cv2
    import numpy as np
    if (not isinstance(polygon, list) or len(polygon) != 4 or not all(
            isinstance(point, list) and len(point) == 2 and all(
                not isinstance(v, bool) and isinstance(v, (int, float)) and math.isfinite(v)
                for v in point) for point in polygon)):
        return 'unknown_invalid_polygon', None
    p = np.asarray(polygon, dtype=np.float32)
    if not np.isfinite(p).all() or not cv2.isContourConvex(p):
        return 'unknown_nonconvex_or_nonfinite_polygon', None
    if not ((p >= 0).all() and (p[:, 0] <= width).all() and (p[:, 1] <= height).all()):
        return 'unknown_outside_raster', None
    sums, diffs = p.sum(axis=1), p[:, 0] - p[:, 1]
    if ([int(np.argmin(sums)), int(np.argmax(diffs)), int(np.argmax(sums)), int(np.argmin(diffs))]
            != [0, 1, 2, 3] or any(list(v).count(v[i]) != 1
                for v, i in ((sums, 0), (diffs, 1), (sums, 2), (diffs, 3)))):
        return 'unknown_point_order_or_corner_ambiguity', None
    w = int(max(np.linalg.norm(p[0]-p[1]), np.linalg.norm(p[2]-p[3])))
    h = int(max(np.linalg.norm(p[0]-p[3]), np.linalg.norm(p[1]-p[2])))
    if w <= 0 or h <= 0:
        return 'unknown_degenerate_polygon', None
    return ('eligible_tall' if h/w >= 1.5 else 'unchanged_not_tall'), p


def _groups(lines, width, height):
    import cv2
    import numpy as np
    eligible, unchanged = [], []
    for row in lines:
        status, polygon = _geometry(row.get('originalImagePolygonBeforeFiltering'), width, height)
        if status == 'eligible_tall':
            eligible.append((row, polygon))
        else:
            unchanged.append({'parentId': row['lineId'], 'status': status})
    edges = []
    for i, (a, p) in enumerate(eligible):
        for j, (b, q) in enumerate(eligible[i+1:], i+1):
            area, _ = cv2.intersectConvexConvex(p, q, handleNested=True)
            if not math.isfinite(float(area)) or area < 0:
                raise ValueError('Invalid actual polygon intersection')
            if area > 0:
                edges.append({'firstIndex': i, 'secondIndex': j, 'firstParentId': a['lineId'],
                              'secondParentId': b['lineId'], 'areaPixelsSquared': float(area)})
    remaining, result = set(range(len(eligible))), []
    while remaining:
        members = {min(remaining)}
        remaining -= members
        pending = list(members)
        while pending:
            current = pending.pop()
            joined = {edge['secondIndex'] if edge['firstIndex'] == current else edge['firstIndex']
                      for edge in edges if current in (edge['firstIndex'], edge['secondIndex'])} & remaining
            members |= joined
            remaining -= joined
            pending.extend(sorted(joined))
        rows = [eligible[i][0] for i in sorted(members)]
        points = np.concatenate([eligible[i][1] for i in sorted(members)])
        box = [math.floor(float(points[:, 0].min())), math.floor(float(points[:, 1].min())),
               math.ceil(float(points[:, 0].max())), math.ceil(float(points[:, 1].max()))]
        if not (0 <= box[0] < box[2] <= width and 0 <= box[1] < box[3] <= height):
            raise ValueError('Union context outside complete raster')
        polygon = [[box[0], box[1]], [box[2], box[1]], [box[2], box[3]], [box[0], box[3]]]
        risks = []
        if len(rows) > 1:
            for row in lines:
                if row['lineId'] in {member['lineId'] for member in rows}:
                    continue
                _, p = _geometry(row.get('originalImagePolygonBeforeFiltering'), width, height)
                if p is not None:
                    area, _ = cv2.intersectConvexConvex(np.float32(polygon), p, handleNested=True)
                    if area > 0:
                        risks.append({'parentId': row['lineId'], 'areaPixelsSquared': float(area),
                                      'disposition': 'retained_without_deletion_or_deduplication'})
        result.append({'groupId': 'line-reread-group-' + str(len(result)),
            'memberParentIds': [row['lineId'] for row in rows],
            'polygonIntersectionEdges': [edge for edge in edges if edge['firstIndex'] in members
                                         and edge['secondIndex'] in members],
            'unionContextBboxXYXY': box, 'unionContextPolygon': polygon,
            'splitPolygon': rows[0]['originalImagePolygonBeforeFiltering'] if len(rows) == 1 else polygon,
            'contextMeaning': 'whole parent/context ROI; not glyph, field or writing-area geometry',
            'overlappingOtherParentRisks': risks, 'origin': 'fresh', 'children': [],
            'automaticBodyReplacement': False})
    return result, unchanged


def _bands(binary):
    import numpy as np
    transitions = np.diff(np.r_[False, (binary != 0).any(axis=1), False].astype(int))
    merged = []
    for start, end in zip(np.flatnonzero(transitions == 1), np.flatnonzero(transitions == -1)):
        start, end = int(start), int(end)
        if merged and start-merged[-1][1] < .5 * max(merged[-1][1]-merged[-1][0], end-start):
            merged[-1][1] = end
        else:
            merged.append([start, end])
    return merged


def _split(image, polygon):
    import cv2
    import numpy as np
    p = np.float32(polygon)
    width = int(max(np.linalg.norm(p[0]-p[1]), np.linalg.norm(p[2]-p[3])))
    height = int(max(np.linalg.norm(p[0]-p[3]), np.linalg.norm(p[1]-p[2])))
    if height/width < 1.5:
        return {'status': 'not_tall_context', 'uprightWidth': width, 'uprightHeight': height,
                'hOverW': height/width, 'cropRotation90': False}, []
    matrix = cv2.getPerspectiveTransform(p, np.float32([[0, 0], [width, 0], [width, height], [0, height]]))
    if abs(float(np.linalg.det(matrix))) <= 1e-12:
        raise ValueError('Singular parent transform')
    inverse = np.linalg.inv(matrix)
    upright = cv2.warpPerspective(image, matrix, (width, height),
                                 borderMode=cv2.BORDER_REPLICATE, flags=cv2.INTER_CUBIC)
    threshold, binary = cv2.threshold(cv2.cvtColor(upright, cv2.COLOR_RGB2GRAY), 0, 255,
                                     cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    bands = _bands(binary)
    detail = {'uprightWidth': width, 'uprightHeight': height, 'hOverW': height/width,
        'perspectiveOriginalToUpright': matrix.tolist(), 'perspectiveUprightToOriginal': inverse.tolist(),
        'uprightPixels': image_identity(upright), 'otsuThreshold': float(threshold), 'inkBands': bands,
        'cropRotation90': False, 'orientationStatus': 'unknown_may_be_true_vertical_writing'}
    if len(bands) < 2:
        return dict(detail, status='unknown_no_safe_multiband_split'), []
    boundaries = [0] + [(a[1]+b[0])//2 for a, b in zip(bands, bands[1:])] + [height]
    detail.update(status='candidate_multiband_split_unverified',
                  actualChildBoundsRows=[[a, b] for a, b in zip(boundaries, boundaries[1:])])
    return detail, [(upright[a:b].copy(), [0, a, width, b]) for a, b in detail['actualChildBoundsRows']]


def _mapping(inverse, bounds):
    import cv2
    import numpy as np
    x0, y0, x1, y1 = bounds
    transform = np.asarray(inverse) @ np.array([[1, 0, x0], [0, 1, y0], [0, 0, 1]], dtype=float)
    corners = np.float32([[[0, 0], [x1-x0, 0], [x1-x0, y1-y0], [0, y1-y0]]])
    polygon = cv2.perspectiveTransform(corners, transform)[0]
    if not np.isfinite(polygon).all():
        raise ValueError('Nonfinite source transform')
    return {'inputToOriginalPage': transform.tolist(), 'originalPageToInput': np.linalg.inv(transform).tolist(),
            'originalImagePolygon': polygon.tolist()}


def _trim(image, parent_edges):
    import cv2
    import numpy as np
    h, w = image.shape[:2]
    threshold, binary = cv2.threshold(cv2.cvtColor(image, cv2.COLOR_RGB2GRAY), 0, 255,
                                     cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    ys, xs = np.nonzero(binary)
    detail = {'otsuThreshold': float(threshold), 'sourceRoiPixels': image_identity(image),
              'edgePixelsMaximum': 2, 'newPixelsAdded': False}
    if not len(xs):
        return dict(detail, status='unknown_no_otsu_foreground', trimBoundsXYXY=None,
                    possibleParentBoundaryCut=False, meaning='no foreground is not proof of blank'), None
    sides = {'top': binary[0], 'bottom': binary[-1], 'left': binary[:, 0], 'right': binary[:, -1]}
    touching = [side for side in parent_edges if sides[side].any()]
    x0, y0, x1, y1 = max(0, int(xs.min())-2), max(0, int(ys.min())-2), min(w, int(xs.max())+3), min(h, int(ys.max())+3)
    nonwhite = np.any(image != 255, axis=2)
    foreground, retained = int((binary != 0).sum()), int((binary[y0:y1, x0:x1] != 0).sum())
    if foreground != retained:
        raise ValueError('Trim discarded Otsu foreground')
    crop = image[y0:y1, x0:x1].copy()
    detail.update(status='candidate_trimmed_recognition_input', trimBoundsXYXY=[x0, y0, x1, y1],
        otsuForegroundBoundsXYXY=[int(xs.min()), int(ys.min()), int(xs.max())+1, int(ys.max())+1],
        otsuForegroundPixels=foreground, retainedOtsuForegroundPixels=retained,
        removedNonwhitePixels=int(nonwhite.sum()-nonwhite[y0:y1, x0:x1].sum()),
        recognitionInputPixels=image_identity(crop), possibleParentBoundaryCut=bool(touching),
        parentBoundaryInkSides=touching,
        boundaryCutMeaning='edge contact is a possible incomplete glyph; rereading does not prove completeness')
    return detail, crop


def reread_line_groups(engine, image, stage_page, recipe_sha256):
    """Return candidates; caller owns engine lock, image provenance and process timeout.

    The recipe digest binds the caller's existing verified engine; this function
    checks live CPU providers/batches/thresholds, without rehashing model files.
    ORT microbatches are a computed upper bound, never an observed call count.
    """
    import numpy as np
    started = time.monotonic()
    record = {'schema': 'consense.cpu-line-reread.v1', 'status': 'failed', 'recipeSha256': recipe_sha256,
        'recipeBinding': 'caller_verified_pinned_engine; model_identity_not_reverified_here',
        'methodCalls': {'cls': 0, 'rec': 0}, 'detectorMethodCalls': 0, 'retries': 0,
        'actualOrtMicrobatches': None, 'computedOrtMicrobatchUpperBound': {'cls': 0, 'rec': 0},
        'microbatchEvidence': 'not_observed; ceil(selected_recognition_inputs / frozen_batch_6)',
        'selectedChildCrops': 0, 'recognitionInputs': 0, 'maximumChildCrops': 64,
        'rule': {'tallRatio': 1.5, 'minimumWhiteGapAdjacentHeightFraction': .5,
                 'trimEdgePixelsMaximum': 2, 'intersection': 'strict_positive_polygon_area'},
        'automaticBodyReplacement': False, 'sourceQualityAccepted': False,
        'semanticStatus': 'unknown; no field, writing-area, state, value or reading-order assignment',
        'synchronousNativeCancellation': 'not_supported', 'groups': [], 'unchangedParents': []}
    try:
        if not isinstance(image, np.ndarray) or image.dtype != np.uint8 or image.ndim != 3 or image.shape[2] != 3 or min(image.shape[:2]) <= 0:
            raise ValueError('Complete nonempty uint8 RGB raster required')
        record['sourceImagePixels'] = image_identity(image)
        if (not isinstance(stage_page, dict) or stage_page.get('mappingVerified') is not True
                or stage_page.get('complete') is not True or stage_page.get('originalImageShape') != list(image.shape)
                or not isinstance(stage_page.get('lines'), list)):
            raise ValueError('Complete mapped actual stage page must match the raster frame')
        record['originalParentObservations'] = copy.deepcopy(stage_page['lines'])
        lines = record['originalParentObservations']
        ids = [row.get('lineId') if isinstance(row, dict) else None for row in lines]
        if any(not isinstance(key, str) or not key.strip() for key in ids) or len(set(ids)) != len(ids):
            raise ValueError('Mapped parent IDs must be nonempty and unique')
        if not isinstance(recipe_sha256, str) or not re.fullmatch('[a-f0-9]{64}', recipe_sha256):
            raise ValueError('Existing pinned recipe SHA256 required')
        if (not callable(getattr(engine, 'cls_and_rotate', None)) or not callable(getattr(engine, 'recognize_txt', None))
                or engine.text_score != .5 or engine.text_cls.cls_thresh != .9
                or engine.text_cls.cls_batch_num != 6 or engine.text_rec.rec_batch_num != 6):
            raise ValueError('Pinned classifier/recognizer interface, thresholds or batches differ')
        for name in ('text_det', 'text_cls', 'text_rec'):
            inference = getattr(getattr(engine, name), 'session', None)
            session = getattr(inference, 'session', None)
            if session is None and hasattr(inference, 'get_providers'):
                session = inference
            if session is None or session.get_providers() != ['CPUExecutionProvider']:
                raise ValueError('Pinned engine must use existing CPU-only sessions')
        height, width = image.shape[:2]
        record['groups'], record['unchangedParents'] = _groups(lines, width, height)
        arrays, children = [], []
        for group in record['groups']:
            detail, split_children = _split(image, group['splitPolygon'])
            group['split'] = detail
            if not split_children:
                group['status'] = 'unknown_no_safe_split'
                continue
            if record['selectedChildCrops'] + len(split_children) > 64:
                group['status'] = 'budget_exhausted_complete_group_not_executed'
                continue
            group['status'] = 'fresh_candidate_children'
            record['selectedChildCrops'] += len(split_children)
            for ordinal, (roi, bounds) in enumerate(split_children):
                mapping = _mapping(detail['perspectiveUprightToOriginal'], bounds)
                polygon = np.asarray(mapping['originalImagePolygon'])
                if not ((polygon >= -.01).all() and (polygon[:, 0] <= width+.01).all() and (polygon[:, 1] <= height+.01).all()):
                    raise ValueError('Child source mapping outside complete raster')
                child = {'childId': group['groupId'] + '-child-' + str(ordinal), 'groupId': group['groupId'],
                    'parentIds': group['memberParentIds'][:], 'origin': 'fresh', 'rectifiedBoundsXYXY': bounds,
                    'sourceRoiPixels': image_identity(roi), 'sourceMapping': mapping,
                    'automaticBodyReplacement': False, 'sourceCorrectness': 'unknown_not_independently_scored'}
                group['children'].append(child)
                edges = ['left', 'right'] + (['top'] if bounds[1] == 0 else []) + (['bottom'] if bounds[3] == detail['uprightHeight'] else [])
                trim, crop = _trim(roi, edges)
                child['trim'] = trim
                if crop is None:
                    continue
                child['trimMapping'] = _mapping(mapping['inputToOriginalPage'], trim['trimBoundsXYXY'])
                arrays.append(crop)
                children.append(child)
        record['recognitionInputs'] = len(arrays)
        upper = math.ceil(len(arrays)/6)
        record['computedOrtMicrobatchUpperBound'] = {'cls': upper, 'rec': upper}
        if arrays:
            record['methodCalls']['cls'] += 1
            rotated, classified = engine.cls_and_rotate([array.copy() for array in arrays])
            record['rawClassificationResponse'] = plain(classified.cls_res)
            if len(rotated) != len(children) or len(classified.cls_res) != len(children):
                raise ValueError('Classifier response count differs from complete selected groups')
            for row, before, after, (label, score) in zip(children, arrays, rotated, classified.cls_res):
                if not isinstance(label, str) or not math.isfinite(float(score)):
                    raise ValueError('Invalid raw classification')
                rotation = bool('180' in label and score > .9)
                if (not isinstance(after, np.ndarray) or after.dtype != np.uint8 or after.shape != before.shape
                        or not np.array_equal(after, np.rot90(before, 2) if rotation else before)):
                    raise ValueError('Classified pixels do not match the recorded rotation policy')
                undo = np.array([[-1, 0, after.shape[1]], [0, -1, after.shape[0]], [0, 0, 1]]) if rotation else np.eye(3)
                transform = np.asarray(row['trimMapping']['inputToOriginalPage']) @ undo
                row['classification'] = {'label': label, 'confidence': float(score), 'threshold': .9,
                    'rotation180Triggered': rotation, 'pixelsAfterClassification': image_identity(after),
                    'actualRecognizerInputToOriginalPage': transform.tolist(),
                    'originalPageToActualRecognizerInput': np.linalg.inv(transform).tolist()}
            record['methodCalls']['rec'] += 1
            recognized = engine.recognize_txt(rotated)
            record['rawRecognitionResponse'] = plain({'texts': recognized.txts, 'scores': recognized.scores})
            if len(recognized.txts) != len(children) or len(recognized.scores) != len(children):
                raise ValueError('Recognizer response count differs from complete selected groups')
            for row, text, score in zip(children, recognized.txts, recognized.scores):
                if not isinstance(text, str) or not math.isfinite(float(score)):
                    raise ValueError('Invalid raw recognition')
                row['recognition'] = {'rawText': text, 'rawConfidence': float(score), 'nonempty': bool(text.strip()),
                    'fixedTextScoreThreshold': .5, 'passesExistingFilter': bool(text.strip()) and score >= .5,
                    'filterDisposition': 'blank_recognition_text' if not text.strip() else 'below_text_score' if score < .5 else 'filter_pass_candidate'}
        record['status'] = 'completed_candidate_observations_unscored'
    except Exception as error:
        record.update(status='failed_no_retry', errorType=type(error).__name__, error=str(error))
    record['elapsedSeconds'] = time.monotonic()-started
    return plain(record)
