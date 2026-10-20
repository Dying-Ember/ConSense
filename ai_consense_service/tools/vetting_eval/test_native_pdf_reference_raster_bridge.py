"""Authored bridge controls; no neural/source-quality acceptance or engine call."""
import copy
import json
import tempfile
import unittest

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _hash
from native_pdf_canonical_projection import _layout, produce_native_pdf, validate_native_pdf_projection
from native_pdf_projection_transport import hydrate_native_pdf_transport, publish_native_pdf_transport
from structured_parser_evidence import build_dual_parser_evidence, normalize_parser_run, read_artifact_record
from test_native_pdf_canonical_projection import fixture


def bridge_review(target, image, anchor, native_frame):
    return {'schema': 'consense.original-page-reference-raster-affine.v1',
            'nativeFrameSha256': _hash(native_frame), 'sourceFrame': copy.deepcopy(target),
            'referenceFrame': {'coordinateFrame': 'reference_raster', 'coordinateUnit': 'pixel',
                'width': image['width'], 'height': image['height'], 'sourceSha256': anchor['sourceSha256'],
                'physicalPageNo': anchor['physicalPageNo'], 'rasterSha256': image['sha256']},
            'matrix': [image['width'] / target['width'], 0, 0, 0, image['height'] / target['height'], 0]}


def authored_layout(*, points=True):
    """Independent seam fixture: two whole observations and explicit source identities."""
    source_sha, image_sha = 'a' * 64, 'b' * 64
    reference = {'sourceImage': {'width': 800, 'height': 800, 'sha256': image_sha},
                 'sourceAnchor': {'sourceSha256': source_sha, 'physicalPageNo': 3}}
    native_frame = {'transformStatus': 'derived_page_frame_mapping_not_raster_verified',
                    'renderedCropSizePoints72': [400, 400]}
    row = {'physicalPageNo': 3, 'layout': {'selectedRunId': 'paddle', 'candidateBlockIds': {},
                                          'rasterMappingReview': {}}}
    runs = []
    for rid, frame, coords, unit in [('paddle', 'raster', [80, 220, 720, 380], 'pixel'),
                                    ('miner', 'normalized', [0.1, 0.275, 0.9, 0.475], 'ratio')]:
        name = 'original-page-3.png'
        target = {'coordinateFrame': 'original_page', 'coordinateUnit': 'pt' if points else 'pixel',
                  'width': 400 if points else 800, 'height': 400 if points else 800,
                  'sourceSha256': source_sha, 'physicalPageNo': 3}
        transform = {'kind': 'affine' if points else 'identity',
            'inputFrame': {'coordinateFrame': 'raster', 'coordinateUnit': 'pixel', 'width': 800,
                           'height': 800, 'artifactName': name, 'sha256': image_sha},
            'targetFrame': target}
        if points:
            transform['matrix'] = [0.5, 0, 0, 0, 0.5, 0]
        mapping = {'physicalPageNo': 3, 'inputArtifactName': name, 'width': 800, 'height': 800,
                   'coordinateUnit': 'pixel', 'providerBboxFrame': frame, 'originalPageTransform': transform}
        block = {'blockId': rid + '-whole', 'label': 'text', 'text': 'AUTHORED WHOLE OBSERVATION',
                 'provenance': {'authored': True}, 'bbox': {'xyxy': coords, 'coordinateFrame': frame,
                     'coordinateUnit': unit, 'origin': 'top-left', 'pageWidth': 800, 'pageHeight': 800}}
        page = {'physicalPageNo': 3, 'mapping': mapping, 'blocks': [block],
                'inputToOriginalMappingStatus': 'caller_bound_input_page_to_original_page',
                'inputArtifact': {'name': name, 'sha256': image_sha}}
        runs.append({'runId': rid, 'source': {'sourceSha256': source_sha}, 'pages': [page],
                     'requestPageMap': [mapping], 'artifacts': {name: {'sha256': image_sha,
                         'projectionStatus': 'available', 'contentOmitted': False}}})
        row['layout']['candidateBlockIds'][rid] = [block['blockId']]
        review = {'status': 'source_reviewed_declared', 'method': 'original_page_raster_geometry_review',
                  'referenceRasterSha256': image_sha, 'inputRasterSha256': image_sha,
                  'relationship': 'source_reviewed_affine_to_reference_raster' if points else 'same_original_raster',
                  'originalPageTransformSha256': _hash(transform)}
        if points:
            review['referenceRasterBridge'] = bridge_review(target, reference['sourceImage'],
                                                            reference['sourceAnchor'], native_frame)
        row['layout']['rasterMappingReview'][rid] = review
    return row, {'bboxNormalized': [0, 0, 1, 1]}, reference, {'runs': runs}, native_frame


class NativePdfReferenceRasterBridgeTest(unittest.TestCase):
    def test_point_geometry_and_both_whole_parser_observations_remain_unchanged(self):
        row, region, reference, dual, native_frame = authored_layout()
        before = copy.deepcopy((row, reference, dual, native_frame))
        result = _layout(row, region, reference, dual, native_frame=native_frame)
        self.assertEqual(before, (row, reference, dual, native_frame))
        self.assertEqual(2, len(result))
        for part in result:
            original, raster = part['originalGeometry'], part['referenceRasterGeometry']
            self.assertEqual('pt', original['targetFrame']['coordinateUnit'])
            for expected, actual in zip([40, 110, 360, 190], original['xyxyOriginal']):
                self.assertAlmostEqual(expected, actual, delta=1e-12)
            for expected, actual in zip([80, 220, 720, 380], raster['xyxyReferenceRaster']):
                self.assertAlmostEqual(expected, actual, delta=1e-12)
            for expected, actual in zip(original['xyxyNormalized'], raster['xyxyNormalized']):
                self.assertAlmostEqual(expected, actual, delta=1e-15)
            self.assertEqual(_hash(original), raster['pointGeometrySha256'])
            self.assertEqual('source_reviewed_declared_affine_bridge_not_exact_glyph', raster['status'])

    def test_existing_pixel_path_has_no_added_geometry_and_requires_no_bridge(self):
        row, region, reference, dual, native_frame = authored_layout(points=False)
        result = _layout(row, region, reference, dual, native_frame=native_frame)
        self.assertEqual(2, len(result))
        self.assertTrue(all('referenceRasterGeometry' not in part for part in result))
        row['layout']['rasterMappingReview']['miner']['referenceRasterBridge'] = {}
        with self.assertRaisesRegex(CanonicalProjectionError, 'unexpected_reference_raster_bridge'):
            _layout(row, region, reference, dual, native_frame=native_frame)

    def test_missing_or_stale_native_frame_and_bridge_are_rejected(self):
        for case in ('missing_bridge', 'missing_frame', 'frame_sha', 'frame_size', 'frame_status'):
            with self.subTest(case=case):
                row, region, reference, dual, native_frame = authored_layout()
                bridge = row['layout']['rasterMappingReview']['miner']['referenceRasterBridge']
                if case == 'missing_bridge':
                    del row['layout']['rasterMappingReview']['miner']['referenceRasterBridge']
                elif case == 'missing_frame': native_frame = None
                elif case == 'frame_sha': bridge['nativeFrameSha256'] = 'c' * 64
                elif case == 'frame_size':
                    native_frame['renderedCropSizePoints72'] = [399, 400]
                    bridge['nativeFrameSha256'] = _hash(native_frame)
                else:
                    native_frame['transformStatus'] = 'unknown'
                    bridge['nativeFrameSha256'] = _hash(native_frame)
                code = 'explicit_reference_raster_bridge_required' if case == 'missing_bridge' else 'reference_raster_native_frame_mismatch'
                with self.assertRaisesRegex(CanonicalProjectionError, code):
                    _layout(row, region, reference, dual, native_frame=native_frame)

    def test_reference_identity_units_and_unknown_bridge_fields_are_rejected(self):
        for case in ('reference_sha', 'reference_page', 'reference_source', 'reference_unit',
                     'reference_frame', 'reference_size', 'source_frame', 'schema', 'extra'):
            with self.subTest(case=case):
                row, region, reference, dual, native_frame = authored_layout()
                bridge = row['layout']['rasterMappingReview']['miner']['referenceRasterBridge']
                frame = bridge['referenceFrame']
                if case == 'reference_sha': frame['rasterSha256'] = 'c' * 64
                elif case == 'reference_page': frame['physicalPageNo'] = 4
                elif case == 'reference_source': frame['sourceSha256'] = 'c' * 64
                elif case == 'reference_unit': frame['coordinateUnit'] = 'pt'
                elif case == 'reference_frame': frame['coordinateFrame'] = 'original_page'
                elif case == 'reference_size': frame['width'] = 400
                elif case == 'source_frame': bridge['sourceFrame']['width'] = 800
                elif case == 'schema': bridge['schema'] = 'unknown'
                else: bridge['guessedScale'] = 2
                code = 'illegal_reference_raster_bridge' if case == 'extra' else (
                    'reference_raster_native_frame_mismatch' if case == 'schema' else 'reference_raster_bridge_frame_identity_mismatch')
                with self.assertRaisesRegex(CanonicalProjectionError, code):
                    _layout(row, region, reference, dual, native_frame=native_frame)

    def test_noninverse_nonfinite_boolean_translated_and_wrong_length_matrices_are_rejected(self):
        for matrix in ([1, 0, 0, 0, 1, 0], [2, 0, 1, 0, 2, 0], [2, True, 0, 0, 2, 0],
                       [2, 0, 0, 0, float('nan'), 0], [2, 0, 0, 0, float('inf'), 0], [2, 0]):
            with self.subTest(matrix=matrix):
                row, region, reference, dual, native_frame = authored_layout()
                row['layout']['rasterMappingReview']['miner']['referenceRasterBridge']['matrix'] = matrix
                with self.assertRaisesRegex(CanonicalProjectionError, 'reference_raster_bridge_not_exact_inverse'):
                    _layout(row, region, reference, dual, native_frame=native_frame)

    def test_original_affine_rotation_or_translation_cannot_use_this_full_raster_inverse(self):
        for matrix in ([0.5, 0, 1, 0, 0.5, 0], [0, -0.5, 400, 0.5, 0, 0]):
            with self.subTest(matrix=matrix):
                row, region, reference, dual, native_frame = authored_layout()
                transform = dual['runs'][1]['pages'][0]['mapping']['originalPageTransform']
                transform['matrix'] = matrix
                row['layout']['rasterMappingReview']['miner']['originalPageTransformSha256'] = _hash(transform)
                with self.assertRaisesRegex(CanonicalProjectionError, 'reference_raster_bridge_not_exact_inverse'):
                    _layout(row, region, reference, dual, native_frame=native_frame)

    def test_other_input_raster_is_rejected_even_if_dimensions_match(self):
        row, region, reference, dual, native_frame = authored_layout()
        run = dual['runs'][1]
        page = run['pages'][0]
        page['inputArtifact']['sha256'] = 'c' * 64
        run['artifacts']['original-page-3.png']['sha256'] = 'c' * 64
        transform = page['mapping']['originalPageTransform']
        transform['inputFrame']['sha256'] = 'c' * 64
        review = row['layout']['rasterMappingReview']['miner']
        review['inputRasterSha256'] = 'c' * 64
        review['originalPageTransformSha256'] = _hash(transform)
        with self.assertRaisesRegex(CanonicalProjectionError, 'reference_raster_bridge_original_input_mismatch'):
            _layout(row, region, reference, dual, native_frame=native_frame)

    def test_native_fixture_produce_and_cold_validation_preserve_literal_and_raw_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            native, old_dual, manifest = fixture(store)
            reference = json.loads(store.read_asset(manifest['reference']['artifactDescriptor']))
            runs = []
            for run in old_dual['runs']:
                mappings = copy.deepcopy(run['requestPageMap'])
                for index, mapping in enumerate(mappings):
                    frame = native['observation']['pageObservations'][index]['frame']
                    width, height = frame['renderedCropSizePoints72']
                    target = mapping['originalPageTransform']['targetFrame']
                    target.update(coordinateUnit='pt', width=width, height=height)
                    mapping['originalPageTransform'].update(kind='affine', matrix=[
                        width / mapping['width'], 0, 0, 0, height / mapping['height'], 0])
                raw = {name: read_artifact_record(record, artifact_store=store)
                       for name, record in run['rawOutputs'].items()}
                assets = {name: store.read_asset(record['artifactDescriptor'])
                          for name, record in run['artifacts'].items()}
                replacement = normalize_parser_run(run['parser'], raw, run['source'], run['identity'], mappings,
                    artifacts=assets, required_artifacts=list(assets), artifact_store=store,
                    provider_input_identity={'mediaType': 'application/pdf',
                        'inputSha256': native['source']['sourceSha256'], 'inputArtifactName': 'original.pdf',
                        'pageCount': 2, 'pageIndexMapMeaning': 'provider_input_document'},
                    terminal={'status': 'completed', 'pageResults': [
                        {'parserPageIndex': index, 'status': 'completed'} for index in (0, 1)]})
                self.assertEqual({k: v['sha256'] for k, v in run['rawOutputs'].items()},
                                 {k: v['sha256'] for k, v in replacement['rawOutputs'].items()})
                runs.append(replacement)
            dual = build_dual_parser_evidence(runs, artifact_store=store)
            manifest['dualBundleSha256'] = dual['bundleSha256']
            for index, row in enumerate(manifest['regionReview']['regions']):
                image, anchor = reference['pages'][index]['sourceImage'], reference['pages'][index]['sourceAnchor']
                frame = native['observation']['pageObservations'][index]['frame']
                row['layout'] = {'selectedRunId': runs[0]['runId'], 'candidateBlockIds': {
                    run['runId']: [run['pages'][index]['blocks'][0]['blockId']] for run in runs},
                    'rasterMappingReview': {}}
                for run in runs:
                    transform = run['pages'][index]['mapping']['originalPageTransform']
                    row['layout']['rasterMappingReview'][run['runId']] = {
                        'status': 'source_reviewed_declared', 'method': 'original_page_raster_geometry_review',
                        'referenceRasterSha256': image['sha256'], 'inputRasterSha256': image['sha256'],
                        'originalPageTransformSha256': _hash(transform),
                        'relationship': 'source_reviewed_affine_to_reference_raster',
                        'referenceRasterBridge': bridge_review(transform['targetFrame'], image, anchor, frame)}
            result = produce_native_pdf(native, dual, manifest, artifact_store=store)
            self.assertEqual('Visible fi cafe\nSecond page', result['chunks'][0]['content'])
            self.assertFalse(result['semanticScopeVerified'])
            validate_native_pdf_projection(result, artifact_store=ArtifactStore(directory))
            public = publish_native_pdf_transport(result, artifact_store=store)
            self.assertFalse(public['humanConfirmed'])
            self.assertEqual(result, hydrate_native_pdf_transport(public, artifact_store=ArtifactStore(directory)))


if __name__ == '__main__':
    unittest.main()
