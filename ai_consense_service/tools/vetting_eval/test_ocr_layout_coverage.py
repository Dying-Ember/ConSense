"""Authored geometry/stage contracts only; no OCR weights or neural forwards."""
import copy
import hashlib
import io
import json
import tempfile
from types import SimpleNamespace
import unittest
import numpy as np

from PIL import Image
from artifact_store import ArtifactStore
from ocr_layout_coverage import layout_gap_regions, line_coverage, observe_cpu_lines, _json, _raster_box


def image_bytes(size=(100, 200)):
    stream = io.BytesIO()
    Image.new('RGB', size, 'black').save(stream, format='PNG')
    return stream.getvalue()


def line(box, ordinal=0, text='UNVERIFIED fixture', stage='retained'):
    a, b, c, d = box
    return {'lineId': 'authored-line-' + str(ordinal),
            'originalImagePolygonBeforeFiltering': [[a, b], [c, b], [c, d], [a, d]],
            'recognition': {'text': text, 'confidence': .2 if stage == 'below_text_score' else .9},
            'terminalStage': stage}


def capture_fixture(store, raw, lines=None, **changes):
    with Image.open(io.BytesIO(raw)) as image:
        width, height = image.size
    digest = hashlib.sha256(raw).hexdigest()
    record = {'schema': 'consense.cpu-page-line-observation.v1', 'status': 'observed',
        'inputSha256': digest, 'recipeSha256': 'e'*64, 'fullPageOcrCalls': 1,
        'frame': {'imageSha256': digest, 'width': width, 'height': height,
                  'coordinateUnit': 'pixel', 'bboxFormat': 'xywh'},
        'stages': {'lineStageProbe': {'status': 'complete', 'wrappedInstanceRestored': True,
            'pages': [{'mappingVerified': True, 'complete': True,
                       'lines': lines if lines is not None else [line([5, 60, 20, 75])]}]}}}
    record.update(changes)
    reference = store.put_bytes(_json(record), identity_scope='authored_contract_only')
    return {'observation': record, 'artifactDescriptor': reference}


def provider_runs(raw):
    digest = hashlib.sha256(raw).hexdigest()
    source = {'sourceSha256': 'd'*64}
    transform = {'kind': 'affine', 'matrix': [.5, 0, 0, 0, .5, 0],
        'inputFrame': {'coordinateFrame': 'raster', 'coordinateUnit': 'pixel',
            'width': 100, 'height': 200, 'artifactName': 'page.png', 'sha256': digest},
        'targetFrame': {'coordinateFrame': 'original_page', 'coordinateUnit': 'pt',
            'width': 50, 'height': 100, 'physicalPageNo': 3, 'sourceSha256': source['sourceSha256']}}
    runs = []
    for parser, frame, coords, unit in [('paddleocr-vl', 'raster', [10, 20, 90, 40], 'pixel'),
                                       ('mineru', 'normalized', [.1, .1, .9, .2], 'ratio')]:
        mapping = {'physicalPageNo': 3, 'width': 100, 'height': 200, 'coordinateUnit': 'pixel',
                   'providerBboxFrame': frame, 'inputArtifactName': 'page.png',
                   'originalPageTransform': copy.deepcopy(transform)}
        page = {'physicalPageNo': 3, 'mapping': mapping,
            'inputArtifact': {'name': 'page.png', 'sha256': digest},
            'inputToOriginalMappingStatus': 'caller_bound_input_page_to_original_page',
            'blocks': [{'bbox': {'xyxy': coords, 'coordinateFrame': frame, 'coordinateUnit': unit,
                                'origin': 'top-left', 'pageWidth': 100, 'pageHeight': 200}}]}
        runs.append({'parser': parser, 'source': source, 'pages': [page], 'requestPageMap': [mapping],
                     'artifacts': {'page.png': {'sha256': digest, 'projectionStatus': 'available'}}})
    return runs


def reread_capture_fixture(store):
    from ocr_line_reread import reread_line_groups
    from ocr_stage_probe import image_identity
    from test_ocr_line_reread import Engine
    image = np.full((200, 100, 3), 255, dtype=np.uint8)
    image[60:70, 15:25] = 0
    image[125:135, 15:25] = 0
    stream = io.BytesIO(); Image.fromarray(image).save(stream, format='PNG')
    raw = stream.getvalue()
    parents = [line([10, 60, 30, 150], 0, 'old merged'),
               line([15, 70, 35, 160], 1, 'old overlapping'),
               line([60, 90, 80, 105], 2, 'ordinary')]
    capture = capture_fixture(store, raw, parents)
    record = capture['observation']
    page = record['stages']['lineStageProbe']['pages'][0]
    page['originalImageShape'] = list(image.shape)
    record['sourceImagePixels'] = image_identity(image)
    record['lineReread'] = reread_line_groups(Engine(responses=[(' ', .99), ('literal low', .2)]), image, page, record['recipeSha256'])
    capture['artifactDescriptor'] = store.put_bytes(_json(record), identity_scope='authored_contract_only')
    return raw, capture


class LayoutGapTests(unittest.TestCase):
    def test_complete_fresh_group_replaces_whole_parent_group_in_active_gaps_only(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory); raw, capture = reread_capture_fixture(store)
            record = capture['observation']; before = copy.deepcopy(record)
            self.assertEqual(record['lineReread']['status'], 'completed_candidate_observations_unscored')
            additions, ledger = layout_gap_regions(capture, provider_runs(raw), record['frame'], 3, store)
            self.assertEqual([region['originalText'] for region in additions], [' ', 'literal low', 'ordinary'])
            self.assertTrue(all(region['text'] == '' for region in additions))
            self.assertEqual(ledger['lineReread']['groups'][0]['disposition'], 'complete_child_group_active')
            self.assertEqual(record, before)
            saved = json.loads(store.read_asset(capture['artifactDescriptor']))
            self.assertEqual(saved['stages']['lineStageProbe']['pages'][0]['lines'], before['lineReread']['originalParentObservations'])
            for region in additions[:2]:
                ref = region['assetRefs'][0]
                self.assertEqual(ref['parentIds'], ['authored-line-0', 'authored-line-1'])
                self.assertEqual(ref['observation']['kind'], 'complete_cpu_line_reread')
                target = saved
                for part in ref['observation']['jsonPointer'].strip('/').split('/'):
                    target = target[int(part)] if isinstance(target, list) else target[part]
                self.assertEqual(target['recognition']['rawText'], region['originalText'])
                self.assertEqual(target['sourceMapping']['originalImagePolygon'], region['polygon'])
                self.assertFalse(target['recognition']['passesExistingFilter'])
            self.assertTrue(saved['lineReread']['groups'][0]['children'][0]['trim']['possibleParentBoundaryCut'])

    def test_incomplete_unknown_budget_and_failed_groups_retain_all_original_parents(self):
        mutations = [lambda r: r.update(status='failed_no_retry'),
            lambda r: r['groups'][0].update(status='budget_exhausted_complete_group_not_executed'),
            lambda r: r['groups'][0].update(status='unknown_no_safe_split'),
            lambda r: r['groups'][0]['children'][1].pop('recognition'),
            lambda r: r['groups'][0]['children'][1].pop('trim'),
            lambda r: r['groups'][0].update(split=None),
            lambda r: r['groups'][0]['split'].update(actualChildBoundsRows=[None, None])]
        for mutate in mutations:
            with self.subTest(mutation=mutations.index(mutate)), tempfile.TemporaryDirectory() as directory:
                store = ArtifactStore(directory); raw, capture = reread_capture_fixture(store)
                mutate(capture['observation']['lineReread'])
                capture['artifactDescriptor'] = store.put_bytes(_json(capture['observation']))
                additions, _ = layout_gap_regions(capture, provider_runs(raw), capture['observation']['frame'], 3, store)
                self.assertEqual([r['originalText'] for r in additions], ['old merged', 'old overlapping', 'ordinary'])
                self.assertTrue(all(r['assetRefs'][0]['observation']['kind'] == 'complete_cpu_line_stages' for r in additions))

    def test_mismatched_reread_binding_ids_counts_and_actual_members_never_partially_replace(self):
        mutations = [lambda r: r.update(recipeSha256='f'*64),
            lambda r: r['sourceImagePixels'].update(pixelsSha256='f'*64),
            lambda r: r['originalParentObservations'][0]['recognition'].update(text='altered old'),
            lambda r: r.update(selectedChildCrops=1),
            lambda r: r['groups'][0]['children'][1].update(childId=r['groups'][0]['children'][0]['childId']),
            lambda r: r['groups'][0]['children'][1].update(childId='authored-line-2'),
            lambda r: r['groups'][0].update(memberParentIds=['authored-line-0']),
            lambda r: r['groups'][0].update(unionContextBboxXYXY=[0, 0, 1, 1]),
            lambda r: r['groups'][0]['children'][1].update(parentIds=['authored-line-0']),
            lambda r: r['groups'][0]['children'][1]['sourceMapping'].update(originalImagePolygon=[[0, 0]]*4)]
        for mutate in mutations:
            with self.subTest(mutation=mutations.index(mutate)), tempfile.TemporaryDirectory() as directory:
                store = ArtifactStore(directory); raw, capture = reread_capture_fixture(store)
                mutate(capture['observation']['lineReread'])
                capture['artifactDescriptor'] = store.put_bytes(_json(capture['observation']))
                additions, _ = layout_gap_regions(capture, provider_runs(raw), capture['observation']['frame'], 3, store)
                self.assertEqual([r['originalText'] for r in additions], ['old merged', 'old overlapping', 'ordinary'])

    def test_existing_prefix_is_untouched_and_gap_text_never_becomes_body_text(self):
        raw = image_bytes()
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            capture = capture_fixture(store, raw, [line([15, 25, 25, 35]),
                line([5, 60, 20, 75], 1, 'MERGED ??', 'below_text_score'),
                line([5, 90, 20, 105], 2, ' ', 'blank_recognition_text')])
            runs = provider_runs(raw)
            before = copy.deepcopy((capture, runs))
            additions, ledger = layout_gap_regions(capture, runs, capture['observation']['frame'], 3, store)
            self.assertEqual((capture, runs), before)
            self.assertEqual(ledger['addedRegions'], 2)
            self.assertEqual([r['text'] for r in additions], ['', ''])
            self.assertEqual([r['originalText'] for r in additions], ['MERGED ??', ' '])
            self.assertEqual(additions[0]['originalGeometry']['xyxyOriginal'], [2.5, 30, 10, 37.5])
            self.assertEqual(additions[0]['polygon'], capture['observation']['stages']['lineStageProbe']['pages'][0]['lines'][1]['originalImagePolygonBeforeFiltering'])
            self.assertEqual(additions[0]['assetRefs'][0]['terminalStage'], 'below_text_score')
            self.assertEqual(additions[0]['assetRefs'][0]['observation']['artifactDescriptor'], capture['artifactDescriptor'])
            self.assertIsNone(additions[0]['readingOrder']['declared'])
            self.assertFalse(ledger['sourceQualityAccepted'])

    def test_one_provider_covering_a_line_prevents_fusion(self):
        raw = image_bytes()
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory); capture = capture_fixture(store, raw)
            runs = provider_runs(raw)
            runs[1]['pages'][0]['blocks'][0]['bbox']['xyxy'] = [.05, .3, .2, .375]
            additions, ledger = layout_gap_regions(capture, runs, capture['observation']['frame'], 3, store)
            self.assertEqual(additions, [])
            self.assertEqual(ledger['lines'][0]['maximumLineAreaCoverage'], {'paddleocr-vl': 0, 'mineru': 1})

    def test_frozen_half_area_boundary_and_normalized_thousand_frame(self):
        self.assertEqual(line_coverage([0, 0, 20, 20], [0, 0, 10, 20]), .5)
        self.assertEqual(line_coverage([0, 0, 20, 20], [30, 30, 40, 40]), 0)
        raw = image_bytes()
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory); capture = capture_fixture(store, raw, [line([0, 60, 20, 80])])
            runs = provider_runs(raw)
            for run in runs:
                page = run['pages'][0]
                page['blocks'][0]['bbox'].update(xyxy=[0, 60, 10, 80], coordinateFrame='raster', coordinateUnit='pixel')
                page['mapping']['providerBboxFrame'] = 'raster'
            additions, ledger = layout_gap_regions(capture, runs, capture['observation']['frame'], 3, store)
            self.assertEqual(additions, [])
            self.assertEqual(set(ledger['lines'][0]['maximumLineAreaCoverage'].values()), {.5})
        self.assertEqual(_raster_box({'xyxy': [100, 200, 300, 400], 'coordinateFrame': 'normalized_1000',
            'coordinateUnit': 'ratio_1000', 'origin': 'top-left', 'pageWidth': 100, 'pageHeight': 200}, 100, 200), [10, 40, 30, 80])

    def test_unknown_provider_geometry_never_means_zero_coverage(self):
        for change in ('missing_box', 'wrong_image', 'wrong_page', 'wrong_source'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                raw = image_bytes(); store = ArtifactStore(directory); capture = capture_fixture(store, raw)
                runs = provider_runs(raw); page = runs[1]['pages'][0]
                if change == 'missing_box': page['blocks'][0]['bbox'] = None
                elif change == 'wrong_image': page['inputArtifact']['sha256'] = '0'*64
                elif change == 'wrong_page': page['physicalPageNo'] = 4
                else: page['mapping']['originalPageTransform']['targetFrame']['sourceSha256'] = '0'*64
                additions, ledger = layout_gap_regions(capture, runs, capture['observation']['frame'], 3, store)
                self.assertEqual(additions, [])
                self.assertEqual(ledger['status'], 'unknown')
                self.assertEqual(ledger['reason'], 'provider_geometry_unknown')

    def test_unbound_cpu_frame_and_tampered_capture_are_rejected(self):
        raw = image_bytes()
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory); capture = capture_fixture(store, raw)
            other_frame = dict(capture['observation']['frame'], imageSha256='0'*64)
            with self.assertRaisesRegex(ValueError, 'image differ'):
                layout_gap_regions(capture, provider_runs(raw), other_frame, 3, store)
            capture['observation']['status'] = 'tampered'
            with self.assertRaisesRegex(ValueError, 'captured bytes'):
                layout_gap_regions(capture, provider_runs(raw), capture['observation']['frame'], 3, store)

    def test_bad_line_geometry_stays_unknown_without_clipping_or_splitting(self):
        raw = image_bytes()
        for polygon in ([[0, 60]]*4, [[-1, 60], [5, 60], [5, 70], [-1, 70]], [[0, 60]],
                        [[5, 60], [20, 75], [20, 60], [5, 75]],
                        [[5, 60], [20, 60], [10, 65], [5, 75]],
                        [[5, 60], [20, 60], [20, 60], [5, 75]]):
            with self.subTest(polygon=polygon), tempfile.TemporaryDirectory() as directory:
                store = ArtifactStore(directory); row = line([5, 60, 20, 75])
                row['originalImagePolygonBeforeFiltering'] = polygon
                capture = capture_fixture(store, raw, [row])
                additions, ledger = layout_gap_regions(capture, provider_runs(raw), capture['observation']['frame'], 3, store)
                self.assertEqual(additions, [])
                self.assertEqual(ledger['lines'][0]['status'], 'unknown')
                self.assertIn('not polygon or glyph coverage', ledger['overlapMethod'])

    def test_cpu_failure_remains_an_explicit_failure_with_its_capture(self):
        raw = image_bytes()
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            capture = capture_fixture(store, raw, status='failed', errorType='AuthoredFailure', error='fixture')
            additions, ledger = layout_gap_regions(capture, provider_runs(raw), capture['observation']['frame'], 3, store)
            self.assertEqual(additions, [])
            self.assertEqual(ledger['status'], 'failed')
            self.assertEqual(ledger['observationRef'], capture['artifactDescriptor'])


class CpuStageCaptureTests(unittest.TestCase):
    def test_reread_uses_same_engine_recipe_raster_after_stage_wrappers_restore(self):
        from unittest.mock import patch
        engine, raw = self.engine(), image_bytes((100, 100))
        original = engine.cls_and_rotate
        def reread(actual_engine, rgb, page, recipe):
            self.assertIs(actual_engine, engine)
            self.assertEqual(recipe, 'e'*64)
            self.assertEqual(list(rgb.shape), [100, 100, 3])
            self.assertTrue(page['mappingVerified'])
            self.assertEqual(engine.cls_and_rotate, original)
            self.assertIsNone(getattr(engine, '_consense_ocr_stage_probe_active', None))
            return {'status': 'failed_no_retry', 'authored': 'independent reread failure'}
        with tempfile.TemporaryDirectory() as directory, patch('ocr_line_reread.reread_line_groups', side_effect=reread) as call:
            store = ArtifactStore(directory); capture = observe_cpu_lines(engine, raw, 'e'*64, store)
            self.assertEqual(capture['observation']['status'], 'observed')
            self.assertEqual(capture['observation']['lineReread']['authored'], 'independent reread failure')
            self.assertEqual(capture['observation'], json.loads(store.read_asset(capture['artifactDescriptor'])))
            call.assert_called_once()

    def test_reread_exception_or_non_json_response_preserves_successful_original_capture(self):
        from unittest.mock import patch
        for response in (RuntimeError('authored reread failure'), {'status': 'failed', 'raw': float('nan')}):
            with self.subTest(response=str(response)), tempfile.TemporaryDirectory() as directory:
                engine, raw = self.engine(), image_bytes((100, 100))
                kwargs = {'side_effect': response} if isinstance(response, Exception) else {'return_value': response}
                with patch('ocr_line_reread.reread_line_groups', **kwargs):
                    store = ArtifactStore(directory); capture = observe_cpu_lines(engine, raw, 'e'*64, store)
                record = json.loads(store.read_asset(capture['artifactDescriptor']))
                self.assertEqual(record['status'], 'observed')
                self.assertEqual(record['fullPageOcrCalls'], 1)
                self.assertEqual(record['lineReread']['status'], 'failed_no_retry')
                self.assertEqual(len(record['stages']['lineStageProbe']['pages'][0]['lines']), 5)
                if isinstance(response, dict):
                    self.assertIn('nan', store.read_asset(record['lineReread']['diagnosticRef']).decode())

    def test_invalid_numeric_and_nonserializable_outputs_preserve_failed_json_and_diagnostic(self):
        from unittest.mock import patch
        for invalid, expected in [(float('nan'), 'nan'), (float('inf'), 'inf'), ({'unserializable'}, 'unserializable')]:
            with self.subTest(invalid=expected), tempfile.TemporaryDirectory() as directory:
                engine = self.engine(); original = type(engine).__call__
                def return_invalid(instance, image):
                    result = original(instance, image)
                    result.scores = [invalid] + list(result.scores[1:])
                    return result
                raw = image_bytes((100, 100)); store = ArtifactStore(directory)
                with patch.object(type(engine), '__call__', return_invalid):
                    capture = observe_cpu_lines(engine, raw, 'e'*64, store)
                record = json.loads(store.read_asset(capture['artifactDescriptor']))
                self.assertEqual(record['status'], 'failed')
                self.assertEqual(record['fullPageOcrCalls'], 1)
                self.assertEqual(record['inputSha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(record['diagnosticFormat'], 'non_json_diagnostic_repr')
                self.assertIn('not_raw_model_bytes', record['diagnosticRef']['identityScope'])
                diagnostic = store.read_asset(record['diagnosticRef']).decode('utf-8')
                self.assertIn(expected, diagnostic)
                self.assertIn('rawFinal', diagnostic)
                additions, ledger = layout_gap_regions(capture, [], {}, 3, store)
                self.assertEqual(additions, [])
                self.assertEqual(ledger['status'], 'failed')

    def engine(self, provider='CPUExecutionProvider'):
        from test_ocr_stage_probe import FixtureEngine
        class Engine(FixtureEngine):
            calls = 0
            def __call__(self, image):
                self.calls += 1
                return self.run()
        engine = Engine()
        session = SimpleNamespace(get_providers=lambda: [provider])
        engine.text_det.session = engine.text_cls.session = session
        engine.text_rec = SimpleNamespace(session=session)
        return engine

    def test_complete_stage_capture_keeps_blank_low_score_and_actual_sessions(self):
        raw, engine = image_bytes((100, 100)), self.engine()
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory); capture = observe_cpu_lines(engine, raw, 'e'*64, store)
            record = json.loads(store.read_asset(capture['artifactDescriptor']))
            self.assertEqual(record, capture['observation'])
            self.assertEqual(record['status'], 'observed')
            self.assertEqual(engine.calls, 1)
            self.assertEqual(record['fullPageOcrCalls'], 1)
            probe = record['stages']['lineStageProbe']
            self.assertEqual(probe['counts']['detected'], 5)
            self.assertEqual(probe['counts']['blankDeleted'], 1)
            self.assertEqual(probe['counts']['lowScoreDeleted'], 1)
            self.assertTrue(probe['wrappedInstanceRestored'])
            self.assertEqual(len(record['finalObservation']['regions']), 3)
            self.assertTrue(all(x['providers'] == ['CPUExecutionProvider'] for x in record['actualSessions']))

    def test_non_cpu_or_unpinned_engine_cannot_start_an_auxiliary_call(self):
        for provider, recipe in [('CUDAExecutionProvider', 'e'*64), ('CPUExecutionProvider', None)]:
            with self.subTest(provider=provider, recipe=recipe), tempfile.TemporaryDirectory() as directory:
                engine = self.engine(provider); store = ArtifactStore(directory)
                capture = observe_cpu_lines(engine, image_bytes((100, 100)), recipe, store)
                self.assertEqual(engine.calls, 0)
                self.assertEqual(capture['observation']['status'], 'failed')
                self.assertEqual(capture['observation']['fullPageOcrCalls'], 0)

    def test_partial_stage_failure_is_captured_and_instance_is_restored(self):
        engine = self.engine()
        def fail(images): raise RuntimeError('Authored recognition failure')
        engine.recognize_txt = fail
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            capture = observe_cpu_lines(engine, image_bytes((100, 100)), 'e'*64, store)
            record = json.loads(store.read_asset(capture['artifactDescriptor']))
            self.assertEqual(record['status'], 'failed')
            self.assertEqual(record['fullPageOcrCalls'], 1)
            self.assertEqual(record['stages']['lineStageProbe']['counts']['detected'], 5)
            self.assertTrue(record['stages']['lineStageProbe']['wrappedInstanceRestored'])
            self.assertIs(engine.recognize_txt, fail)
            self.assertIn('Authored recognition failure', record['error'])


if __name__ == '__main__':
    unittest.main()
