"""Unexecuted authored controls plus opt-in saved-real14 numeric contracts.

No renderer/Java/provider is invoked. Authored recipe bytes prove only integrity
behavior. Real14 tests use saved original bytes and authored layout observations;
they are not actual dual, source-review, affine or canonical acceptance.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from artifact_store import ArtifactStore, ArtifactStoreError
from canonical_projection import CanonicalProjectionError, _bytes, _hash
from native_pdf_canonical_projection import _layout, _native_region_geometry, _reference
from source_reference import SourceReferenceError, read_source_reference, validate_source_reference
from structured_parser_evidence import project_bbox_to_original
from test_native_pdf_reference_raster_bridge import authored_layout

BOUND = 'retained_recipe_bytes_bound_not_affine_or_source_acceptance'
BRIDGE = 'consense.original-page-reference-raster-144dpi-ceil-affine.v1'
DPI_CEIL_SCHEMA = 'consense.original-pdf-144dpi-ceil-raster-evidence.v1'


def descriptor(raw):
    digest = hashlib.sha256(raw).hexdigest()
    return {'schema': 'consense.artifact-descriptor.v1', 'storage': 'content_addressed_disk',
        'sha256': digest, 'byteCount': len(raw), 'relativePath': 'sha256/' + digest[:2] + '/' + digest + '.blob',
        'identityScope': 'AUTHORED_integrity_fixture_not_actual_renderer'}


def reference_for(witness, source_sha, png_sha, page_no):
    page_id = witness['pageId']; width, height = witness['integerCanvasPixels']
    return {'schema': 'consense.source-review-reference.v2', 'referenceVersion': 'AUTHORED-dpi-ceil-seam',
        'sourceSha256': source_sha, 'sourceMediaType': 'application/pdf', 'actor': 'codex',
        'method': 'original_source_review', 'split': 'authored_contract_fixture', 'humanConfirmed': False,
        'physicalPages': [page_no], 'geometryEvidence': {'schema': DPI_CEIL_SCHEMA, 'pages': [witness]},
        'pages': [{'pageId': page_id, 'sourceAnchor': {'kind': 'pdf_physical_page', 'sourceSha256': source_sha,
             'physicalPageNo': page_no}, 'regionIdsInReadingOrder': ['r'], 'sourceImage': {'sha256': png_sha,
             'width': width, 'height': height, 'sourceRelation': {'kind': 'original_pdf_page_raster',
             'originalSourceSha256': source_sha, 'originalPhysicalPageNo': page_no}}}],
        'regions': [{'regionId': 'r', 'text': 'INDEPENDENT ANNOTATION', 'role': 'body', 'bboxNormalized': [0, 0, 1, 1],
             'sourceAnchor': {'kind': 'pdf_physical_region', 'sourceSha256': source_sha, 'physicalPageNo': page_no,
                 'pageId': page_id, 'renditionSha256': png_sha, 'geometryStatus': 'declared_not_exact_glyph'}}], 'relations': []}


def authored_recipe():
    """A deliberately authored complete recipe, never an observed render claim."""
    def png_chunk(kind, raw):
        return struct.pack('>I', len(raw)) + kind + raw + struct.pack('>I', zlib.crc32(kind + raw))
    width, height = 1191, 1684
    png = b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 0, 0, 0, 0))
    png += png_chunk(b'pHYs', struct.pack('>IIB', 5669, 5669, 1))
    png += png_chunk(b'IDAT', zlib.compress((b'\0' + b'\xff' * width) * height)) + png_chunk(b'IEND', b'')
    original = b'%PDF-AUTHORED-SEAM-ONLY-NOT-A-PARSED-PDF'
    def pin(path, raw): return {'path': path, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
    renderer, runner = b'AUTHORED renderer bytes', b'AUTHORED recipe bytes'
    renderer_pin, runner_pin = pin('AUTHORED/pdftoppm.exe', renderer), pin('AUTHORED/run_raster_phase.py', runner)
    source_pin = pin('AUTHORED/original.pdf', original)
    frame = {'mediaBox': [0, 0, 595.3200073242188, 841.9199829101562],
        'cropBox': [0, 0, 595.3200073242188, 841.9199829101562], 'rotation': 0, 'userUnit': 1,
        'renderedCropSizePoints72': [595.3200073242188, 841.9199829101562],
        'transformStatus': 'derived_page_frame_mapping_not_raster_verified'}
    argv = [renderer_pin['path'], '-f', '3', '-l', '3', '-singlefile', '-r', '144', '-cropbox', '-png', source_pin['path'], 'AUTHORED/original-page']
    bundle_raw = _bytes({'scope': 'AUTHORED', 'count': 1, 'files': [renderer_pin]})
    bundle_pin = pin('AUTHORED/renderer-bundle.json', bundle_raw)
    planned = {'pageId': 'AUTHORED-p3', 'versionId': 'AUTHORED-version', 'sourceRevisionSha256': '7' * 64,
        'physicalPage': 3, 'source': source_pin, 'nativeCapture': {'sha256': '8' * 64}, 'nativeFrame': frame,
        'cropPolicy': 'actual_original_crop_box', 'declaredDpi': 144, 'expectedWidthPixels': width, 'expectedHeightPixels': height,
        'argv': argv, 'outputPrefix': 'AUTHORED/original-page'}
    pins = [source_pin, runner_pin, renderer_pin, bundle_pin]
    plan = {'schema': 'consense.sl-seven-real-sources.original-page-raster.v1', 'phaseId': 'AUTHORED-phase',
        'pages': [planned], 'renderer': renderer_pin, 'rendererBundle': bundle_pin, 'runner': runner_pin, 'inputPins': pins}
    receipt = {key: copy.deepcopy(planned[key]) for key in ('pageId', 'versionId', 'physicalPage', 'source', 'nativeCapture', 'argv')}
    receipt.update(status='RASTER_RETURNED_UNREVIEWED', exitCode=0, naturalJobZero=True,
        parent={'imagePath': renderer_pin['path']}, cleanup={'allObservedOwnedProcessesExited': True,
        'errors': [], 'beforeClose': {'active': 0, 'members': []}}, png=pin('AUTHORED/original-page.png', png),
        widthPixels=width, heightPixels=height)
    phase = {'status': 'SEVEN_RASTERS_RETURNED_UNREVIEWED', 'phaseId': plan['phaseId'], 'plan': pin('AUTHORED/plan.json', _bytes(plan)),
        'allInputPinsPreserved': True, 'rootReleasePreserved': True, 'priorPhaseReceiptPreserved': True,
        'cleanupConfirmedForAllStartedPages': True, 'unexecutedPages': [], 'maximumConcurrency': 1,
        'retryMaximum': 0, 'rendererCalls': 1, 'inputPinsBefore': pins, 'inputPinsAfter': pins, 'pages': [receipt]}
    payloads = {'phasePlan': _bytes(plan), 'phaseReceipt': _bytes(phase), 'pageReceipt': _bytes(receipt),
        'renderer': renderer, 'rendererBundle': bundle_raw, 'rendererRecipe': runner}
    witness = {'pageId': 'AUTHORED-p3', 'rendererPageId': 'AUTHORED-p3', 'versionId': 'AUTHORED-version',
        'sourceRevisionSha256': '7' * 64, 'nativeFrame': frame, 'nativeFrameSha256': _hash(frame), 'dpi': 144,
        'physicalContentExtentPixels': [n * 2 for n in frame['renderedCropSizePoints72']], 'integerCanvasPixels': [width, height],
        'artifacts': {key: descriptor(raw) for key, raw in payloads.items()}}
    reference = reference_for(witness, source_pin['sha256'], hashlib.sha256(png).hexdigest(), 3)
    evidence = {reference['sourceSha256']: original, reference['pages'][0]['sourceImage']['sha256']: png,
        **{hashlib.sha256(raw).hexdigest(): raw for raw in payloads.values()}}
    return reference, evidence


def layout_for(reference, proof):
    row, region, _, dual, _ = authored_layout()
    page = reference['pages'][0]; frame = reference['geometryEvidence']['pages'][0]['nativeFrame']
    image, anchor = page['sourceImage'], page['sourceAnchor']; width, height = image['width'], image['height']
    row['physicalPageNo'] = anchor['physicalPageNo']
    for run in dual['runs']:
        run['source']['sourceSha256'] = anchor['sourceSha256']
        parser_page = run['pages'][0]; mapping = parser_page['mapping']; transform = mapping['originalPageTransform']
        parser_page['physicalPageNo'] = mapping['physicalPageNo'] = anchor['physicalPageNo']
        mapping.update(width=width, height=height)
        parser_page['inputArtifact']['sha256'] = image['sha256']
        run['artifacts'][mapping['inputArtifactName']]['sha256'] = image['sha256']
        transform['inputFrame'].update(width=width, height=height, sha256=image['sha256'])
        transform['targetFrame'].update(width=frame['renderedCropSizePoints72'][0], height=frame['renderedCropSizePoints72'][1],
            sourceSha256=anchor['sourceSha256'], physicalPageNo=anchor['physicalPageNo'])
        transform['matrix'] = [0.5, 0, 0, 0, 0.5, 0]
        block = parser_page['blocks'][0]; block['bbox'].update(pageWidth=width, pageHeight=height)
        block['bbox']['xyxy'] = [80, 220, 720, 380] if block['bbox']['coordinateFrame'] == 'raster' else [80 / width, 220 / height, 720 / width, 380 / height]
        review = row['layout']['rasterMappingReview'][run['runId']]
        review.update(referenceRasterSha256=image['sha256'], inputRasterSha256=image['sha256'], originalPageTransformSha256=_hash(transform))
        bridge = review['referenceRasterBridge']; bridge.update(schema=BRIDGE, nativeFrameSha256=_hash(frame),
            sourceFrame=copy.deepcopy(transform['targetFrame']), matrix=[2, 0, 0, 0, 2, 0])
        bridge['referenceFrame'].update(width=width, height=height, sourceSha256=anchor['sourceSha256'],
            physicalPageNo=anchor['physicalPageNo'], rasterSha256=image['sha256'])
    return row, region, page, dual, frame, proof


def proof_for(reference, evidence):
    report = validate_source_reference(reference, evidence_bytes=evidence)
    return report['dpiCeilRasterGeometry'][reference['pages'][0]['pageId']]


def canonical_reference_fixture(store, reference, evidence):
    witness = reference['geometryEvidence']['pages'][0]
    for raw in evidence.values(): store.put_bytes(raw)
    source = reference['sourceSha256']; raw = _bytes(reference); ref = store.put_bytes(raw)
    native = {'source': {'sourceSha256': source, 'sourceRevisionSha256': witness['sourceRevisionSha256']},
        'sourcePackage': descriptor(evidence[source]), 'rawObservation': {'sha256': '8' * 64},
        'observation': {'pageObservations': [{'physicalPage': 3, 'frame': witness['nativeFrame']}]}}
    manifest = {'reference': {'actor': 'codex', 'method': 'original_source_review', 'sourceSha256': source,
        'referenceVersion': reference['referenceVersion'], 'evidenceSha256': ref['sha256'], 'artifactDescriptor': ref,
        'pageImages': [descriptor(evidence[page['sourceImage']['sha256']]) for page in reference['pages']]}}
    return manifest, native


class DpiCeilBridgeTest(unittest.TestCase):
    def test_authored_physical_scale_preserves_points_raw_and_distinct_raster_normalization(self):
        reference, evidence = authored_recipe(); proof = proof_for(reference, evidence)
        row, region, page, dual, frame, _ = layout_for(reference, proof)
        before = copy.deepcopy((reference, evidence, row, dual, frame))
        parts = _layout(row, region, page, dual, native_frame=frame, raster_geometry=proof)
        self.assertEqual(before, (reference, evidence, row, dual, frame))
        self.assertEqual(2, len(parts)); self.assertEqual(BOUND, proof['status'])
        for part in parts:
            original, raster = part['originalGeometry'], part['referenceRasterGeometry']
            for expected, actual in zip([40, 110, 360, 190], original['xyxyOriginal']): self.assertAlmostEqual(expected, actual)
            for expected, actual in zip([80, 220, 720, 380], raster['xyxyReferenceRaster']): self.assertAlmostEqual(expected, actual)
            self.assertNotEqual(original['xyxyNormalized'], raster['xyxyNormalized'])
            self.assertEqual(_hash(original), raster['pointGeometrySha256'])

    def test_shape_read_with_no_actual_bytes_cannot_authorize_physical_bridge(self):
        reference, _ = authored_recipe(); loaded = read_source_reference(_bytes(reference))
        proof = loaded.validation['dpiCeilRasterGeometry']['AUTHORED-p3']
        self.assertEqual('unverified_actual_bytes_missing', proof['status'])
        row, region, page, dual, frame, _ = layout_for(reference, proof)
        with self.assertRaisesRegex(CanonicalProjectionError, 'dpi_ceil_verified_page_geometry_required'):
            _layout(row, region, page, dual, native_frame=frame, raster_geometry=proof)
        self.assertFalse(loaded.validation['parserAdoptionPerformed'])
        self.assertEqual('not_evaluated', loaded.validation['semanticAcceptance'])

    def test_old_geometry_shapes_and_missing_geometry_keep_identical_report_shape(self):
        reference, evidence = authored_recipe(); del reference['geometryEvidence']
        before = validate_source_reference(reference, evidence_bytes=evidence)
        reference['geometryEvidence'] = {'schema': 'HISTORICAL-preserved-only', 'arbitrary': ['unchanged']}
        self.assertEqual(before, validate_source_reference(reference, evidence_bytes=evidence))
        self.assertNotIn('dpiCeilRasterGeometry', before)

    def test_wrong_dpi_crop_rotation_unit_and_canvas_plus_minus_one_fail(self):
        for case in ('dpi72', 'dpi143', 'dpi145', 'crop', 'rotation90', 'rotation180', 'unit2', 'width+1', 'width-1', 'height+1', 'height-1'):
            with self.subTest(case=case):
                reference, evidence = authored_recipe(); witness = reference['geometryEvidence']['pages'][0]
                frame = witness['nativeFrame']
                if case.startswith('dpi'): witness['dpi'] = int(case[3:])
                elif case == 'crop': frame['cropBox'][0] = 1
                elif case.startswith('rotation'): frame['rotation'] = int(case[8:])
                elif case == 'unit2': frame['userUnit'] = 2
                else:
                    dimension = 'width' if case.startswith('width') else 'height'
                    reference['pages'][0]['sourceImage'][dimension] += 1 if '+' in case else -1
                witness['nativeFrameSha256'] = _hash(frame)
                with self.assertRaises(SourceReferenceError): validate_source_reference(reference, evidence_bytes=evidence)

    def test_new_evidence_cannot_downgrade_to_legacy_canvas_fit_or_pixel_identity(self):
        for case in ('legacy-fit', 'pixel-identity', 'new-schema-without-proof'):
            with self.subTest(case=case):
                reference, evidence = authored_recipe(); proof = proof_for(reference, evidence)
                row, region, page, dual, frame, _ = layout_for(reference, proof)
                for run in dual['runs']:
                    transform = run['pages'][0]['mapping']['originalPageTransform']; review = row['layout']['rasterMappingReview'][run['runId']]
                    if case == 'legacy-fit':
                        transform['matrix'] = [frame['renderedCropSizePoints72'][0] / 1191, 0, 0, 0, frame['renderedCropSizePoints72'][1] / 1684, 0]
                        review['referenceRasterBridge']['schema'] = 'consense.original-page-reference-raster-affine.v1'
                        review['referenceRasterBridge']['matrix'] = [1191 / frame['renderedCropSizePoints72'][0], 0, 0, 0, 1684 / frame['renderedCropSizePoints72'][1], 0]
                    elif case == 'pixel-identity':
                        transform['kind'] = 'identity'; transform.pop('matrix'); transform['targetFrame'].update(coordinateUnit='pixel', width=1191, height=1684)
                        review.pop('referenceRasterBridge'); review['relationship'] = 'same_original_raster'
                    review['originalPageTransformSha256'] = _hash(transform)
                with self.assertRaises(CanonicalProjectionError):
                    _layout(row, region, page, dual, native_frame=frame, raster_geometry=None if case == 'new-schema-without-proof' else proof)

    def test_nonphysical_noninverse_anisotropic_translated_sheared_and_boolean_matrices_fail(self):
        for matrix in ([2.1, 0, 0, 0, 2, 0], [2, 0, 0, 1, 2, 0], [2, 0.1, 0, 0, 2, 0], [True, 0, 0, 0, 2, 0], [2, 0, 0, 0, 2]):
            with self.subTest(matrix=matrix):
                reference, evidence = authored_recipe(); proof = proof_for(reference, evidence)
                row, region, page, dual, frame, _ = layout_for(reference, proof)
                row['layout']['rasterMappingReview']['miner']['referenceRasterBridge']['matrix'] = matrix
                with self.assertRaisesRegex(CanonicalProjectionError, 'reference_raster_bridge_not_exact_inverse'):
                    _layout(row, region, page, dual, native_frame=frame, raster_geometry=proof)

    def test_native_glyph_overlap_uses_same_reference_canvas_frame_as_layout(self):
        reference, evidence = authored_recipe(); proof = proof_for(reference, evidence); page = reference['pages'][0]
        frame = reference['geometryEvidence']['pages'][0]['nativeFrame']
        glyphs = {'g': ({'eligibleForNativeText': True, 'visibilityStatus': 'operator_visible_unverified',
            'coordinateSpace': 'normalized_rendered_crop_top_left', 'bbox': [0.9998, 0.1, 0.0001, 0.1]}, None, None)}
        native_page = {'frame': frame, 'observationStatus': 'observed_native_operators_unreviewed'}
        region = {'bboxNormalized': [0.99949, 0.0999, 0.99961, 0.21]}; before = copy.deepcopy(glyphs)
        _native_region_geometry(['g'], glyphs, native_page, region, page, raster_geometry=proof)
        self.assertEqual(before, glyphs)
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_glyph_source_region_disjoint'):
            _native_region_geometry(['g'], glyphs, native_page, region, page)

    def test_unselected_boundary_unknown_is_preserved_and_selected_unknown_still_refuses(self):
        reference, evidence = authored_recipe(); proof = proof_for(reference, evidence)
        row, region, page, dual, frame, _ = layout_for(reference, proof)
        run = next(r for r in dual['runs'] if r['runId'] == 'paddle'); parser_page = run['pages'][0]
        unknown = copy.deepcopy(parser_page['blocks'][0]); unknown['blockId'] = 'AUTHORED-crosses-physical-right'
        unknown['bbox']['xyxy'] = [1180, 80, 1191, 100]; parser_page['blocks'].append(unknown)
        bottom = copy.deepcopy(unknown); bottom['blockId'] = 'AUTHORED-crosses-physical-bottom'
        bottom['bbox']['xyxy'] = [80, 1670, 100, 1684]; parser_page['blocks'].append(bottom)
        before = copy.deepcopy(dual)
        for unknown_block in (unknown, bottom):
            projected = project_bbox_to_original(run, parser_page, unknown_block['bbox'])
            self.assertNotEqual('verified', projected['status'])
            self.assertEqual(2, len(_layout(row, region, page, dual, native_frame=frame, raster_geometry=proof)))
            row['layout']['candidateBlockIds']['paddle'].append(unknown_block['blockId'])
            with self.assertRaisesRegex(CanonicalProjectionError, 'visual_original_geometry_unknown'):
                _layout(row, region, page, dual, native_frame=frame, raster_geometry=proof)
            row['layout']['candidateBlockIds']['paddle'].pop()
        self.assertEqual(before, dual)

    def test_actual_cas_closure_is_required_and_native_source_revision_raw_are_bound(self):
        for case in ('ok', 'revision', 'raw', 'source', 'page', 'png', 'missing-recipe'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                reference, evidence = authored_recipe(); store = ArtifactStore(directory)
                manifest, native = canonical_reference_fixture(store, reference, evidence)
                if case == 'revision': native['source']['sourceRevisionSha256'] = 'f' * 64
                elif case == 'raw': native['rawObservation']['sha256'] = 'f' * 64
                elif case == 'source': native['source']['sourceSha256'] = 'f' * 64
                elif case == 'page': native['observation']['pageObservations'][0]['physicalPage'] = 4
                elif case == 'png':
                    descriptor_ = manifest['reference']['pageImages'][0]
                    (Path(directory) / descriptor_['relativePath']).write_bytes(b'changed')
                elif case == 'missing-recipe':
                    descriptor_ = reference['geometryEvidence']['pages'][0]['artifacts']['rendererRecipe']
                    (Path(directory) / descriptor_['relativePath']).unlink()
                if case == 'ok':
                    loaded, report = _reference(manifest, native, [3], ArtifactStore(directory))
                    self.assertEqual(reference, loaded); self.assertEqual(BOUND, report['dpiCeilRasterGeometry']['AUTHORED-p3']['status'])
                else:
                    with self.assertRaises((CanonicalProjectionError, SourceReferenceError, ArtifactStoreError)):
                        _reference(manifest, native, [3], ArtifactStore(directory))

    def test_actual_recipe_source_page_png_and_version_swaps_are_rejected(self):
        for key, value in (('versionId', 'wrong-version'), ('rendererPageId', 'wrong-page'), ('sourceRevisionSha256', 'f' * 64)):
            reference, evidence = authored_recipe(); reference['geometryEvidence']['pages'][0][key] = value
            with self.subTest(key=key), self.assertRaises(SourceReferenceError):
                validate_source_reference(reference, evidence_bytes=evidence)

    def test_wrong_retained_recipe_or_receipt_sha_cannot_be_resealed_only_in_declaration(self):
        for key in ('phasePlan', 'phaseReceipt', 'pageReceipt', 'renderer', 'rendererBundle', 'rendererRecipe'):
            reference, evidence = authored_recipe(); witness = reference['geometryEvidence']['pages'][0]
            raw = evidence[witness['artifacts'][key]['sha256']]
            if key in ('renderer', 'rendererRecipe'): changed = raw + b' changed'
            else:
                value = json.loads(raw)
                if key == 'phasePlan': value['renderer']['sha256'] = 'f' * 64
                elif key == 'phaseReceipt': value['plan']['sha256'] = 'f' * 64
                elif key == 'pageReceipt': value['argv'][7] = '145'
                else: value['files'] = []
                changed = _bytes(value)
            witness['artifacts'][key] = descriptor(changed); evidence[hashlib.sha256(changed).hexdigest()] = changed
            with self.subTest(key=key), self.assertRaises(SourceReferenceError):
                validate_source_reference(reference, evidence_bytes=evidence)


class SavedRealFourteenDpiCeilTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = os.environ.get('CONSENSE_DPI_CEIL_REAL14')
        if not path: raise unittest.SkipTest('Saved real14 catalog was not explicitly provided')
        cls.cases = json.loads(Path(path).read_text(encoding='utf-8'))['cases']
        if len(cls.cases) != 14 or len({r['source']['sha256'] for r in cls.cases}) != 7:
            raise AssertionError('Exact seven-source/fourteen-page denominator required')

    def test_all_fourteen_actual_saved_recipe_bytes_and_physical_numeric_seam(self):
        observed = []
        for index, case in enumerate(self.cases):
            with self.subTest(version=case['versionId'], physicalPage=case['physicalPage']):
                evidence = {}
                for pin in [case['source'], case['raster'], *case['evidencePins'].values()]:
                    raw = Path(pin['path']).read_bytes()
                    self.assertEqual((pin['bytes'], pin['sha256']), (len(raw), hashlib.sha256(raw).hexdigest()))
                    evidence[pin['sha256']] = raw
                reference = reference_for(copy.deepcopy(case['witness']), case['source']['sha256'], case['raster']['sha256'], case['physicalPage'])
                proof = proof_for(reference, evidence)
                self.assertEqual(BOUND, proof['status']); self.assertEqual(case['nativeCapture']['sha256'], proof['nativeCaptureSha256'])
                row, region, page, dual, frame, _ = layout_for(reference, proof)
                parts = _layout(row, region, page, dual, native_frame=frame, raster_geometry=proof)
                self.assertEqual(2, len(parts)); self.assertEqual([1191, 1684], proof['integerCanvasPixels'])
                self.assertTrue(all(0 <= c - e < 1 for c, e in zip(proof['integerCanvasPixels'], proof['physicalContentExtentPixels'])))
                other = self.cases[(index + 2) % 14]
                for identity, replacement in (('sourceSha256', other['source']['sha256']),
                    ('physicalPageNo', case['physicalPage'] + 1), ('rasterSha256', other['raster']['sha256'])):
                    changed = copy.deepcopy(proof); changed[identity] = replacement
                    with self.subTest(negative=identity), self.assertRaises(CanonicalProjectionError):
                        _layout(row, region, page, dual, native_frame=frame, raster_geometry=changed)
                for key in ('versionId', 'sourceRevisionSha256', 'rendererPageId'):
                    changed = copy.deepcopy(reference)
                    changed['geometryEvidence']['pages'][0][key] = 'f' * 64 if key == 'sourceRevisionSha256' else 'WRONG-SOURCE-VERSION-OR-PAGE'
                    with self.subTest(negative=key), self.assertRaises(SourceReferenceError):
                        validate_source_reference(changed, evidence_bytes=evidence)
                fit_row, fit_dual = copy.deepcopy(row), copy.deepcopy(dual)
                for run in fit_dual['runs']:
                    transform = run['pages'][0]['mapping']['originalPageTransform']
                    transform['matrix'] = [frame['renderedCropSizePoints72'][0] / 1191, 0, 0, 0, frame['renderedCropSizePoints72'][1] / 1684, 0]
                    review = fit_row['layout']['rasterMappingReview'][run['runId']]
                    review['originalPageTransformSha256'] = _hash(transform)
                    review['referenceRasterBridge'].update(schema='consense.original-page-reference-raster-affine.v1',
                        matrix=[1191 / frame['renderedCropSizePoints72'][0], 0, 0, 0, 1684 / frame['renderedCropSizePoints72'][1], 0])
                with self.subTest(negative='legacy-schema-plus-fit'), self.assertRaises(CanonicalProjectionError):
                    _layout(fit_row, region, page, fit_dual, native_frame=frame, raster_geometry=proof)
                observed.append((case['source']['sha256'], case['physicalPage'], case['raster']['sha256']))
        self.assertEqual(14, len(set(observed)))

    def test_all_fourteen_reject_wrong_dpi_crop_rotation_unit_or_integer_canvas(self):
        self.assertEqual(1, len({r['witness']['nativeFrameSha256'] for r in self.cases}))
        for case in self.cases:
            for control in ('dpi72', 'dpi143', 'dpi145', 'crop', 'rotation90', 'rotation180', 'unit2', 'width+1', 'width-1', 'height+1', 'height-1'):
                witness = copy.deepcopy(case['witness']); frame = witness['nativeFrame']
                reference = reference_for(witness, case['source']['sha256'], case['raster']['sha256'], case['physicalPage'])
                if control.startswith('dpi'): witness['dpi'] = int(control[3:])
                elif control == 'crop': frame['cropBox'][0] = 1
                elif control.startswith('rotation'): frame['rotation'] = int(control[8:])
                elif control == 'unit2': frame['userUnit'] = 2
                else:
                    dimension = 'width' if control.startswith('width') else 'height'
                    reference['pages'][0]['sourceImage'][dimension] += 1 if '+' in control else -1
                witness['nativeFrameSha256'] = _hash(frame)
                with self.subTest(version=case['versionId'], page=case['physicalPage'], control=control), self.assertRaises(SourceReferenceError):
                    read_source_reference(_bytes(reference))


if __name__ == '__main__':
    unittest.main()
