"""Synthetic raster/engine contracts; no model loading or source references."""
import copy
import json
from types import SimpleNamespace
import unittest

import numpy as np

from ocr_line_reread import _bands, _geometry, _groups, _mapping, _split, _trim, reread_line_groups


def line(key, box, text='old observation'):
    x0, y0, x1, y1 = box
    return {'lineId': key, 'originalImagePolygonBeforeFiltering':
        [[x0, y0], [x1, y0], [x1, y1], [x0, y1]],
        'recognition': {'text': text, 'confidence': .1}, 'terminalStage': 'below_text_score'}


def stage(image, lines):
    return {'mappingVerified': True, 'complete': True,
            'originalImageShape': list(image.shape), 'lines': lines}


class Engine:
    def __init__(self, responses=None, rotate=False):
        session = SimpleNamespace(session=SimpleNamespace(get_providers=lambda: ['CPUExecutionProvider']))
        self.text_det = SimpleNamespace(session=session)
        self.text_cls = SimpleNamespace(session=session, cls_thresh=.9, cls_batch_num=6)
        self.text_rec = SimpleNamespace(session=session, rec_batch_num=6)
        self.text_score = .5
        self.responses, self.rotate, self.cls_calls, self.rec_calls = responses, rotate, 0, 0

    def cls_and_rotate(self, arrays):
        self.cls_calls += 1
        return ([np.rot90(array, 2).copy() if self.rotate else array for array in arrays],
                SimpleNamespace(cls_res=[('180' if self.rotate else '0', .99) for _ in arrays]))

    def recognize_txt(self, arrays):
        self.rec_calls += 1
        values = self.responses or [('fixture', .9)]*len(arrays)
        return SimpleNamespace(txts=[x[0] for x in values], scores=[x[1] for x in values])


def two_line_fixture():
    image = np.full((160, 100, 3), 255, dtype=np.uint8)
    image[20:30, 25:50] = 0
    image[70:80, 25:50] = 0
    return image, stage(image, [line('a', [20, 10, 60, 110])])


class GeometryTests(unittest.TestCase):
    def test_positive_intersection_transitive_components_and_touch_only(self):
        rows = [line('a', [1, 1, 11, 31]), line('b', [10, 1, 20, 31]),
                line('c', [19, 1, 29, 31]), line('touch', [29, 1, 39, 31]),
                line('ordinary', [5, 0, 15, 5])]
        before = copy.deepcopy(rows)
        groups, unchanged = _groups(rows, 100, 100)
        self.assertEqual([g['memberParentIds'] for g in groups], [['a', 'b', 'c'], ['touch']])
        self.assertEqual(groups[0]['unionContextBboxXYXY'], [1, 1, 29, 31])
        self.assertEqual(len(groups[0]['polygonIntersectionEdges']), 2)
        self.assertEqual(groups[0]['overlappingOtherParentRisks'][0]['parentId'], 'ordinary')
        self.assertEqual(unchanged, [{'parentId': 'ordinary', 'status': 'unchanged_not_tall'}])
        self.assertEqual(rows, before)

    def test_nested_and_fractional_union_enclose_all_vertices(self):
        rows = [line('a', [1.2, 2.3, 11.4, 40.5]), line('b', [3.1, 3.2, 9.3, 30.4])]
        groups, _ = _groups(rows, 100, 100)
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0]['unionContextBboxXYXY'], [1, 2, 12, 41])
        self.assertGreater(groups[0]['polygonIntersectionEdges'][0]['areaPixelsSquared'], 0)

    def test_polygon_overlap_not_bbox_overlap_and_wide_union_still_uses_tall_rule(self):
        # Parallel slanted strips have overlapping envelopes but disjoint polygons.
        a = {'lineId': 'a', 'originalImagePolygonBeforeFiltering': [[0, 0], [5, 0], [25, 60], [20, 60]]}
        b = {'lineId': 'b', 'originalImagePolygonBeforeFiltering': [[10, 0], [15, 0], [35, 60], [30, 60]]}
        groups, _ = _groups([a, b], 100, 100)
        self.assertEqual(len(groups), 2)
        image = np.full((100, 100, 3), 255, dtype=np.uint8)
        detail, children = _split(image, [[0, 0], [80, 0], [80, 30], [0, 30]])
        self.assertEqual(detail['status'], 'not_tall_context')
        self.assertEqual(children, [])

    def test_unsafe_polygons_unknown_and_exact_tall_boundary(self):
        self.assertEqual(_geometry([[0, 0], [10, 0], [10, 15], [0, 15]], 100, 100)[0], 'eligible_tall')
        cases = [None, [[0, 0]]*4, [[0, 0], [10, 15], [10, 0], [0, 15]],
            [[0, 0], [0, 15], [10, 15], [10, 0]], [[-1, 0], [10, 0], [10, 15], [-1, 15]],
            [[20, 0], [30, 10], [20, 20], [10, 10]], [[0, 0], [True, 0], [10, 15], [0, 15]],
            [[0, 0], [float('nan'), 0], [10, 15], [0, 15]]]
        for polygon in cases:
            with self.subTest(polygon=polygon):
                self.assertTrue(_geometry(polygon, 100, 100)[0].startswith('unknown_'))

    def test_bands_merge_fragments_preserve_all_roi_rows(self):
        binary = np.zeros((80, 25), dtype=np.uint8)
        binary[5:7, 5:7] = 255
        binary[9:21, 5:7] = 255
        binary[50:63, 5:20] = 255
        self.assertEqual(_bands(binary), [[5, 21], [50, 63]])
        image, page = two_line_fixture()
        detail, children = _split(image, page['lines'][0]['originalImagePolygonBeforeFiltering'])
        self.assertEqual(len(children), 2)
        self.assertEqual(detail['actualChildBoundsRows'], [[0, 40], [40, 100]])
        self.assertFalse(detail['cropRotation90'])
        self.assertEqual(sum(roi.shape[0] for roi, _ in children), 100)
        self.assertIn('unknown', detail['orientationStatus'])

    def test_trim_keeps_foreground_records_weak_pixels_and_parent_cut(self):
        image = np.full((24, 30, 3), 255, dtype=np.uint8)
        image[8:16, 10:20] = 0
        image[0, 0] = 250
        detail, crop = _trim(image, [])
        self.assertEqual(detail['trimBoundsXYXY'], [8, 6, 22, 18])
        np.testing.assert_array_equal(crop, image[6:18, 8:22])
        self.assertEqual(detail['otsuForegroundPixels'], detail['retainedOtsuForegroundPixels'])
        self.assertEqual(detail['removedNonwhitePixels'], 1)
        image[:8, :8] = 0
        detail, _ = _trim(image, ['top', 'left'])
        self.assertTrue(detail['possibleParentBoundaryCut'])
        self.assertEqual(detail['parentBoundaryInkSides'], ['top', 'left'])

    def test_blank_and_connected_vertical_ink_stay_unknown(self):
        image = np.full((100, 50, 3), 255, dtype=np.uint8)
        detail, children = _split(image, [[0, 0], [30, 0], [30, 90], [0, 90]])
        self.assertEqual(children, [])
        self.assertEqual(detail['status'], 'unknown_no_safe_multiband_split')
        trim, crop = _trim(image, [])
        self.assertIsNone(crop)
        self.assertIn('not proof of blank', trim['meaning'])
        image[1:89, 10:20] = 0
        self.assertEqual(_split(image, [[0, 0], [30, 0], [30, 90], [0, 90]])[1], [])

    def test_crop_mapping_roundtrip(self):
        mapping = _mapping([[1, 0, 100], [0, 1, 200], [0, 0, 1]], [12, 36, 26, 48])
        self.assertEqual(mapping['originalImagePolygon'], [[112, 236], [126, 236], [126, 248], [112, 248]])
        np.testing.assert_allclose(np.asarray(mapping['originalPageToInput']) @ mapping['inputToOriginalPage'], np.eye(3))

    def test_skew_source_roi_mapping_stays_in_full_raster(self):
        image, _ = two_line_fixture()
        detail, children = _split(image, [[20, 10], [60, 12], [58, 112], [18, 110]])
        self.assertEqual(len(children), 2)
        for _, bounds in children:
            mapping = _mapping(detail['perspectiveUprightToOriginal'], bounds)
            np.testing.assert_allclose(np.asarray(mapping['originalPageToInput']) @ mapping['inputToOriginalPage'], np.eye(3), atol=1e-10)
            self.assertTrue((np.asarray(mapping['originalImagePolygon']) >= 0).all())


class RereadTests(unittest.TestCase):
    def test_fresh_each_source_retains_raw_blank_low_score_and_originals(self):
        image, page = two_line_fixture()
        image_before, page_before = image.copy(), copy.deepcopy(page)
        engine = Engine(responses=[('  ', .99), ('literal low', .2)])
        for _ in range(2):
            result = reread_line_groups(engine, image, page, 'e'*64)
            self.assertEqual(result['status'], 'completed_candidate_observations_unscored')
            self.assertEqual(result['selectedChildCrops'], 2)
            self.assertEqual(result['methodCalls'], {'cls': 1, 'rec': 1})
            self.assertIsNone(result['actualOrtMicrobatches'])
            self.assertEqual(result['computedOrtMicrobatchUpperBound'], {'cls': 1, 'rec': 1})
            self.assertFalse(result['sourceQualityAccepted'])
            self.assertFalse(result['automaticBodyReplacement'])
            self.assertEqual(result['originalParentObservations'], page_before['lines'])
            children = result['groups'][0]['children']
            self.assertEqual([c['recognition']['rawText'] for c in children], ['  ', 'literal low'])
            self.assertEqual([c['recognition']['filterDisposition'] for c in children], ['blank_recognition_text', 'below_text_score'])
            self.assertEqual([c['parentIds'] for c in children], [['a'], ['a']])
            json.dumps(result, allow_nan=False)
        self.assertEqual((engine.cls_calls, engine.rec_calls), (2, 2))
        np.testing.assert_array_equal(image, image_before)
        self.assertEqual(page, page_before)

    def test_rotation_mapping_tracks_actual_pixels(self):
        image, page = two_line_fixture()
        result = reread_line_groups(Engine(rotate=True), image, page, 'e'*64)
        self.assertEqual(result['status'], 'completed_candidate_observations_unscored')
        child = result['groups'][0]['children'][0]
        mapping, classification = child['trimMapping'], child['classification']
        self.assertTrue(classification['rotation180Triggered'])
        shape = child['trim']['recognitionInputPixels']['shape']
        np.testing.assert_allclose(np.asarray(classification['actualRecognizerInputToOriginalPage']) @ [0, 0, 1],
                                   np.asarray(mapping['inputToOriginalPage']) @ [shape[1], shape[0], 1])

    def test_full_overlapping_group_fresh_and_nontall_retained(self):
        image, _ = two_line_fixture()
        rows = [line('a', [20, 10, 50, 100]), line('b', [30, 10, 60, 110]), line('ordinary', [25, 5, 55, 20])]
        result = reread_line_groups(Engine(), image, stage(image, rows), 'e'*64)
        self.assertEqual(result['status'], 'completed_candidate_observations_unscored')
        self.assertEqual(len(result['groups']), 1)
        self.assertEqual(result['groups'][0]['memberParentIds'], ['a', 'b'])
        self.assertTrue(all(c['parentIds'] == ['a', 'b'] for c in result['groups'][0]['children']))
        self.assertEqual(result['unchangedParents'][0]['parentId'], 'ordinary')
        self.assertEqual(result['originalParentObservations'], rows)

    def test_budget_skips_whole_group_then_continues_to_smaller_group(self):
        image = np.full((210, 420, 3), 255, dtype=np.uint8)
        rows = []
        for i in range(33):
            x = i*12
            count = 3 if i == 31 else 2
            for y in (10, 40, 70)[:count]:
                image[y:y+8, x+2:x+6] = 0
            rows.append(line(str(i), [x, 0, x+10, 100]))
        engine = Engine()
        result = reread_line_groups(engine, image, stage(image, rows), 'e'*64)
        self.assertEqual(result['status'], 'completed_candidate_observations_unscored')
        self.assertEqual(result['selectedChildCrops'], 64)
        self.assertEqual(result['groups'][31]['status'], 'budget_exhausted_complete_group_not_executed')
        self.assertEqual(result['groups'][31]['children'], [])
        self.assertEqual(len(result['groups'][32]['children']), 2)
        self.assertEqual(result['computedOrtMicrobatchUpperBound'], {'cls': 11, 'rec': 11})
        self.assertEqual((engine.cls_calls, engine.rec_calls), (1, 1))

    def test_frame_id_recipe_and_engine_fail_before_calls(self):
        image, original = two_line_fixture()
        cases = []
        bad = copy.deepcopy(original); bad['mappingVerified'] = False; cases.append((bad, 'e'*64))
        bad = copy.deepcopy(original); bad['originalImageShape'] = [1, 1, 3]; cases.append((bad, 'e'*64))
        bad = copy.deepcopy(original); bad['lines'] *= 2; cases.append((bad, 'e'*64))
        cases.append((original, 'not-a-sha'))
        for page, recipe in cases:
            engine = Engine()
            self.assertEqual(reread_line_groups(engine, image, page, recipe)['status'], 'failed_no_retry')
            self.assertEqual((engine.cls_calls, engine.rec_calls), (0, 0))
        engine = Engine(); engine.text_cls.cls_batch_num = 7
        self.assertEqual(reread_line_groups(engine, image, original, 'e'*64)['status'], 'failed_no_retry')
        self.assertEqual(engine.cls_calls, 0)
        engine = Engine(); engine.text_rec.session = SimpleNamespace(get_providers=lambda: ['CUDAExecutionProvider'])
        self.assertEqual(reread_line_groups(engine, image, original, 'e'*64)['status'], 'failed_no_retry')
        self.assertEqual(engine.cls_calls, 0)
        self.assertEqual(reread_line_groups(Engine(), image.astype(float), original, 'e'*64)['status'], 'failed_no_retry')

    def test_classifier_failure_and_wrong_pixel_mapping_preserve_receipt_without_retry(self):
        image, page = two_line_fixture()
        engine = Engine()
        def fail(arrays):
            engine.cls_calls += 1
            raise RuntimeError('authored classifier failure')
        engine.cls_and_rotate = fail
        result = reread_line_groups(engine, image, page, 'e'*64)
        self.assertEqual(result['methodCalls'], {'cls': 1, 'rec': 0})
        self.assertEqual(result['error'], 'authored classifier failure')
        self.assertEqual(engine.cls_calls, 1)
        engine = Engine()
        engine.cls_and_rotate = lambda arrays: ([a.astype(float) for a in arrays], SimpleNamespace(cls_res=[('0', .99)]*len(arrays)))
        result = reread_line_groups(engine, image, page, 'e'*64)
        self.assertEqual(result['status'], 'failed_no_retry')
        self.assertEqual(result['methodCalls'], {'cls': 1, 'rec': 0})
        self.assertEqual(len(result['rawClassificationResponse']), 2)

    def test_no_safe_split_makes_zero_calls_and_failure_does_not_retry(self):
        image = np.full((100, 50, 3), 255, dtype=np.uint8)
        engine = Engine()
        result = reread_line_groups(engine, image, stage(image, [line('a', [0, 0, 30, 90])]), 'e'*64)
        self.assertEqual(result['recognitionInputs'], 0)
        self.assertEqual(result['methodCalls'], {'cls': 0, 'rec': 0})
        image, page = two_line_fixture()
        engine = Engine(responses=[('missing row', .9)])
        result = reread_line_groups(engine, image, page, 'e'*64)
        self.assertEqual(result['status'], 'failed_no_retry')
        self.assertEqual(result['methodCalls'], {'cls': 1, 'rec': 1})
        self.assertEqual(result['retries'], 0)
        self.assertEqual(result['rawRecognitionResponse']['texts'], ['missing row'])
        self.assertEqual(len(result['groups'][0]['children']), 2)
        self.assertTrue(all('classification' in c for c in result['groups'][0]['children']))


if __name__ == '__main__':
    unittest.main()
