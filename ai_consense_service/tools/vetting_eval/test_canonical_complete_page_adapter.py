"""Focused local candidate checks; saved source fidelity is audited separately."""
import copy
import json
import unittest

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, project_canonical, _hash
from structured_parser_evidence import build_dual_parser_evidence
from test_canonical_projection import fixture as table_fixture
from test_structured_parser_evidence import run, mineru_page, paddle_page
from canonical_projection import selected_complete_page_blocks

def mixed_fixture():
    primary_page = {'page_index': 0, 'parsing_res_list': [
        {'block_id': 0, 'block_label': 'header_image', 'block_content': '', 'block_bbox': [20, 20, 50, 50], 'block_order': 0},
        {'block_id': 1, 'block_label': 'doc_title', 'block_content': 'AUTHORED document title', 'block_bbox': [100, 20, 900, 80], 'block_order': 1},
        {'block_id': 2, 'block_label': 'text', 'block_content': 'An entire independent body paragraph.', 'block_bbox': [100, 200, 800, 600], 'block_order': 2},
        {'block_id': 3, 'block_label': 'footer', 'block_content': 'AUTHORED 2026 / 3', 'block_bbox': [100, 1700, 900, 1800], 'block_order': 3},
        {'block_id': 4, 'block_label': 'aside_text', 'block_content': '(Not adopted)', 'block_bbox': [850, 200, 950, 600], 'block_order': 4}]}
    primary = run('paddleocr-vl', outputs={'page_json': primary_page, 'model_output': {'stage': 'authored'}})
    secondary_page = {'page_idx': 0, 'blocks': [
        {'type': 'header', 'content': 'C.', 'bbox': [.02, .01, .05, .025], 'index': 0},
        {'type': 'doc_title', 'content': 'AUTHORED document title', 'bbox': [.1, .01, .9, .04], 'index': 1},
        {'type': 'aside_text', 'content': '(Not adopted', 'bbox': [.85, .1, .95, .3], 'index': 2}]}
    secondary = run('mineru', outputs={
        'model_json': {'schema': 'docvortex.model', 'schema_version': '2.0', 'pages': [secondary_page], 'page_index_map': [2]},
        'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [secondary_page]},
        'model_output': {'stage': 'authored'}})
    bundle = build_dual_parser_evidence([primary, secondary])
    _, manifest = table_fixture()
    manifest.update(bundleSha256=bundle['bundleSha256'], sourceSha256=bundle['source']['sourceSha256'],
        sourceRevisionSha256=bundle['source']['sourceRevisionSha256'], tableGroups=[], relations=[], assets=[])
    manifest['scope']['requiredPhysicalPages'] = [3]
    pb, sb = primary['pages'][0]['blocks'], secondary['pages'][0]['blocks']
    manifest['units'] = [{'unitId': 'page-header-with-asset-context', 'scope': 'region',
        'runId': primary['runId'], 'blockId': pb[0]['blockId'], 'associatedBlockIds': [pb[1]['blockId']],
        'reviewedAttributes': ['text', 'geometry', 'assetBoundary']}]
    manifest['units'].extend({'unitId': 'retained-primary-' + str(index), 'scope': 'region',
        'runId': primary['runId'], 'blockId': pb[index]['blockId'], 'reviewedAttributes': ['text', 'geometry']}
        for index in (2, 3))
    manifest['units'].append({'unitId': 'reviewed-aside', 'scope': 'region',
        'runId': secondary['runId'], 'blockId': sb[2]['blockId'], 'reviewedAttributes': ['text', 'geometry']})
    manifest['reference'].update(sourceSha256=bundle['source']['sourceSha256'], physicalPages=[3],
        evidence={'purpose': 'Authored complete-page source-review contract; no original document claim',
            'completePageCoverage': [{'physicalPageNo': 3, 'primaryRunId': primary['runId'],
                'replacements': [{'primaryBlockIds': [pb[4]['blockId']], 'selectedUnitIds': ['reviewed-aside']}]}]})
    manifest['reference']['evidenceSha256'] = _hash(manifest['reference']['evidence'])
    return bundle, manifest


def replacement_fixture(*, table=True, primary_label='text', alternate_label='footnote',
                        alternate_bbox=(.1, .1, .9, .101), reclassification=None,
                        include_body=False):
    primary_output = paddle_page(table=table)
    if not table:
        primary_output['parsing_res_list'][0]['block_label'] = primary_label
    if include_body:
        primary_output['parsing_res_list'].append({'block_id': 'body-inside-table-box',
            'block_label': 'text', 'block_content': 'Independent body paragraph',
            'block_bbox': [110, 220, 800, 240], 'block_order': 5})
    primary = run('paddleocr-vl', outputs={'page_json': primary_output, 'model_output': {'stage': 'authored'}})
    alternate_page = mineru_page()
    alternative = alternate_page['blocks'][0]
    alternative.update(type=alternate_label, content='Complete authored alternative unit',
                       bbox=list(alternate_bbox), index=0)
    alternate_page['blocks'] = [alternative]
    outputs = {'model_json': {'schema': 'docvortex.model', 'schema_version': '2.0',
        'pages': [alternate_page], 'page_index_map': [2]},
        'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [alternate_page]},
        'model_output': {'stage': 'authored, no OCR'}}
    alternate = run('mineru', outputs=outputs)
    bundle = build_dual_parser_evidence([primary, alternate])
    _, manifest = table_fixture()
    manifest.update(bundleSha256=bundle['bundleSha256'], sourceSha256=bundle['source']['sourceSha256'],
        sourceRevisionSha256=bundle['source']['sourceRevisionSha256'], tableGroups=[], relations=[], assets=[])
    manifest['scope']['requiredPhysicalPages'] = [3]
    manifest['units'] = [{'unitId': 'retained-primary-footnote', 'scope': 'region',
        'runId': primary['runId'], 'blockId': primary['pages'][0]['blocks'][1]['blockId'],
        'reviewedAttributes': ['text', 'geometry']},
        {'unitId': 'alternative', 'scope': 'region', 'runId': alternate['runId'],
         'blockId': alternate['pages'][0]['blocks'][0]['blockId'],
         'reviewedAttributes': ['text', 'geometry']}]
    replacement = {'primaryBlockIds': [primary['pages'][0]['blocks'][0]['blockId']],
        'selectedUnitIds': ['alternative']}
    if include_body:
        replacement['primaryBlockIds'].append(primary['pages'][0]['blocks'][2]['blockId'])
    if reclassification is not None:
        replacement['reclassification'] = reclassification
    manifest['reference'].update(sourceSha256=bundle['source']['sourceSha256'], physicalPages=[3],
        evidence={'purpose': 'Authored replacement guard, not a real source review',
            'completePageCoverage': [{'physicalPageNo': 3, 'primaryRunId': primary['runId'],
                'replacements': [replacement]}]})
    manifest['reference']['evidenceSha256'] = _hash(manifest['reference']['evidence'])
    return bundle, project_canonical(bundle, manifest)


class CompletePageAdapterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bundle, cls.manifest = mixed_fixture()
        cls.store = None
        cls.partial_manifest = copy.deepcopy(cls.manifest)
        cls.partial_manifest['units'] = [cls.partial_manifest['units'][-1]]
        cls.partial_manifest['reference']['evidence'].pop('completePageCoverage')
        cls.partial_manifest['reference']['evidenceSha256'] = _hash(cls.partial_manifest['reference']['evidence'])

    def project(self, manifest):
        manifest = copy.deepcopy(manifest)
        manifest['reference']['evidenceSha256'] = _hash(manifest['reference']['evidence'])
        return project_canonical(self.bundle, manifest, artifact_store=self.store)

    def test_complete_page_preserves_original_objects_and_empty_asset_context(self):
        result = selected_complete_page_blocks(self.bundle, self.project(self.manifest))
        self.assertEqual(len(result), 5)
        self.assertEqual(result[0][2]['text'], '')
        self.assertEqual(result[-1][0]['parser'], 'mineru')
        self.assertFalse(result[-1][2]['text'].endswith(')'))
        self.assertFalse(any(block['text'] == 'C.' for _, _, block in result))
        self.assertTrue(all(block is next(b for b in page['blocks'] if b['blockId'] == block['blockId'])
                            for _, page, block in result))

    def test_single_aside_review_cannot_replace_full_page(self):
        manifest = copy.deepcopy(self.partial_manifest)
        with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_coverage_required'):
            selected_complete_page_blocks(self.bundle, self.project(manifest))

    def test_missing_primary_block_is_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest['units'] = [u for u in manifest['units'] if u['unitId'] != 'retained-primary-3']
        with self.assertRaisesRegex(CanonicalProjectionError, 'incomplete_primary_page_coverage'):
            selected_complete_page_blocks(self.bundle, self.project(manifest))

    def test_replacing_retained_primary_twice_is_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        r = manifest['reference']['evidence']['completePageCoverage'][0]['replacements'][0]
        r['primaryBlockIds'].append(manifest['units'][1]['blockId'])
        with self.assertRaisesRegex(CanonicalProjectionError, 'incomplete_primary_page_coverage'):
            selected_complete_page_blocks(self.bundle, self.project(manifest))

    def test_unbound_extra_foreign_unit_is_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        mineru = next(r for r in self.bundle['runs'] if r['parser'] == 'mineru')
        manifest['units'][0] = {'unitId': 'not-approved-title', 'scope': 'region',
            'runId': mineru['runId'], 'blockId': mineru['pages'][0]['blocks'][1]['blockId'],
            'reviewedAttributes': ['text', 'geometry']}
        row = manifest['reference']['evidence']['completePageCoverage'][0]
        row['replacements'][0]['primaryBlockIds'].extend([
            next(r for r in self.bundle['runs'] if r['parser'] == 'paddleocr-vl')['pages'][0]['blocks'][i]['blockId']
            for i in (0, 1)])
        with self.assertRaisesRegex(CanonicalProjectionError, 'unbound_complete_page_foreign_selection'):
            selected_complete_page_blocks(self.bundle, self.project(manifest))

    def test_stale_bundle_is_rejected(self):
        projection = self.project(self.manifest)
        stale = copy.deepcopy(self.bundle)
        stale['qualityStatus'] = 'changed'
        stale['bundleSha256'] = _hash({k: v for k, v in stale.items() if k != 'bundleSha256'})
        with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_projection_source_mismatch'):
            selected_complete_page_blocks(stale, projection)

    def test_boolean_physical_page_is_not_integer(self):
        manifest = copy.deepcopy(self.manifest)
        manifest['reference']['evidence']['completePageCoverage'][0]['physicalPageNo'] = True
        with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_coverage_page_mismatch'):
            selected_complete_page_blocks(self.bundle, self.project(manifest))

    def test_aside_cannot_claim_all_unrelated_primary_blocks(self):
        manifest = copy.deepcopy(self.manifest)
        manifest['units'] = [manifest['units'][-1]]
        manifest['relations'] = []
        primary = next(r for r in self.bundle['runs'] if r['parser'] == 'paddleocr-vl')
        manifest['reference']['evidence']['completePageCoverage'][0]['replacements'][0]['primaryBlockIds'] = [
            b['blockId'] for b in primary['pages'][0]['blocks']]
        with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_replacement_role_mismatch'):
            selected_complete_page_blocks(self.bundle, self.project(manifest))

    def test_replacement_cannot_add_an_unrelated_foreign_region(self):
        manifest = copy.deepcopy(self.manifest)
        mineru = next(r for r in self.bundle['runs'] if r['parser'] == 'mineru')
        manifest['units'][0] = {'unitId': 'foreign-title-with-unrelated-aside', 'scope': 'region',
            'runId': mineru['runId'], 'blockId': mineru['pages'][0]['blocks'][1]['blockId'],
            'reviewedAttributes': ['text', 'geometry']}
        manifest['reference']['evidence']['completePageCoverage'][0]['replacements'][0]['selectedUnitIds'].append(
            'foreign-title-with-unrelated-aside')
        primary = next(r for r in self.bundle['runs'] if r['parser'] == 'paddleocr-vl')
        manifest['reference']['evidence']['completePageCoverage'][0]['replacements'][0]['primaryBlockIds'].extend(
            [primary['pages'][0]['blocks'][i]['blockId'] for i in (0, 1)])
        with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_replacement_role_mismatch'):
            selected_complete_page_blocks(self.bundle, self.project(manifest))

    def test_complete_cross_page_table_group_preserves_all_parts(self):
        bundle, manifest = table_fixture()
        primary = bundle['runs'][0]
        table = manifest['units'][1]
        manifest['units'] = [manifest['units'][0]]
        for page in primary['pages']:
            for block in page['blocks']:
                if 'table' in block or block['blockId'] == manifest['units'][0]['blockId']:
                    continue
                manifest['units'].append({'unitId': block['blockId'], 'scope': 'region',
                    'runId': primary['runId'], 'blockId': block['blockId'],
                    'reviewedAttributes': ['text', 'geometry']})
        manifest['units'].append(table)
        manifest['reference']['evidence']['completePageCoverage'] = [{
            'physicalPageNo': page['physicalPageNo'], 'primaryRunId': primary['runId'],
            'replacements': [{'primaryBlockIds': [page['blocks'][1]['blockId']],
                'selectedUnitIds': ['table']}]} for page in primary['pages']]
        manifest['reference']['evidenceSha256'] = _hash(manifest['reference']['evidence'])
        projection = project_canonical(bundle, manifest)
        result = selected_complete_page_blocks(bundle, projection)
        self.assertEqual(len(result), 6)
        self.assertEqual(sum('table' in block for _, _, block in result), 2)
        self.assertEqual({page['physicalPageNo'] for _, page, block in result if 'table' in block}, {3, 4})

    def test_table_cannot_be_adopted_as_individual_region(self):
        bundle, manifest = table_fixture()
        table = manifest['units'][1]
        run = next(r for r in bundle['runs'] if r['runId'] == table['runId'])
        manifest['units'][1] = {'unitId': 'single-table-cell-style-region',
            'scope': 'region', 'runId': run['runId'],
            'blockId': run['pages'][0]['blocks'][1]['blockId'],
            'reviewedAttributes': ['text', 'geometry']}
        with self.assertRaisesRegex(CanonicalProjectionError, 'table_cannot_be_region_or_cell_adopted'):
            project_canonical(bundle, manifest)

    def test_tiny_footnote_cannot_replace_a_whole_table(self):
        bundle, projection = replacement_fixture()
        with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_replacement_role_mismatch'):
            selected_complete_page_blocks(bundle, projection)

    def test_body_header_footer_cannot_be_replaced_by_tiny_aside(self):
        for label in ('text', 'header', 'footer', 'paragraph_title'):
            with self.subTest(label=label):
                bundle, projection = replacement_fixture(table=False, primary_label=label,
                    alternate_label='aside_text')
                with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_replacement_role_mismatch'):
                    selected_complete_page_blocks(bundle, projection)

    def test_explicit_whole_formula_can_replace_a_single_misclassified_table(self):
        bundle, projection = replacement_fixture(alternate_label='equation',
            alternate_bbox=(.1, .1, .9, .3), reclassification='whole_formula')
        result = selected_complete_page_blocks(bundle, projection)
        self.assertEqual(result[-1][2]['label'], 'equation')

    def test_formula_reclassification_must_be_explicit(self):
        bundle, projection = replacement_fixture(alternate_label='equation', alternate_bbox=(.1, .1, .9, .3))
        with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_replacement_role_mismatch'):
            selected_complete_page_blocks(bundle, projection)

    def test_formula_cannot_swallow_table_and_independent_body(self):
        bundle, projection = replacement_fixture(alternate_label='equation',
            alternate_bbox=(.1, .1, .9, .3), reclassification='whole_formula', include_body=True)
        with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_replacement_role_mismatch'):
            selected_complete_page_blocks(bundle, projection)

    def test_false_reclassification_does_not_make_footnote_a_formula(self):
        bundle, projection = replacement_fixture(reclassification='whole_formula')
        with self.assertRaisesRegex(CanonicalProjectionError, 'complete_page_replacement_role_mismatch'):
            selected_complete_page_blocks(bundle, projection)


class CompleteOcrCanonicalSeamTests(unittest.TestCase):
    def test_complete_review_skips_auxiliary_observer_and_preserves_candidates(self):
        import tempfile
        from unittest.mock import Mock
        from test_complete_ocr import CompleteOcrTests
        from complete_ocr import recognize_complete_page
        helper = CompleteOcrTests()
        raw = helper.image()
        _, transport = helper.transport(raw)
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            from test_paddle_native_codec import native_result, wire
            native_manifest, arrays = native_result(raw, store)

            def request(url, body=None, timeout=None, method=None):
                response = transport(url, body, timeout, method)
                if url.endswith('/layout-parsing'):
                    value = json.loads(response)
                    value['result'].update(wire(native_manifest, arrays))
                    return json.dumps(value).encode()
                return response

            config = helper.config()
            context = helper.source_context(raw)
            original = recognize_complete_page(raw, config, store, request=request, source_context=context)
            bundle = json.loads(store.read_asset(original['dualEvidenceRef']))
            primary = next(run for run in bundle['runs'] if run['parser'] == 'paddleocr-vl')
            _, manifest = table_fixture()
            manifest.update(bundleSha256=bundle['bundleSha256'], sourceSha256=bundle['source']['sourceSha256'],
                sourceRevisionSha256=bundle['source']['sourceRevisionSha256'], tableGroups=[], relations=[], assets=[])
            manifest['scope']['requiredPhysicalPages'] = [3]
            manifest['units'] = [{'unitId': 'complete-main', 'scope': 'region', 'runId': primary['runId'],
                'blockId': primary['pages'][0]['blocks'][0]['blockId'], 'reviewedAttributes': ['text', 'geometry']}]
            manifest['reference'].update(sourceSha256=bundle['source']['sourceSha256'], physicalPages=[3],
                evidence={'purpose': 'Authored wire review, no source quality claim',
                    'completePageCoverage': [{'physicalPageNo': 3, 'primaryRunId': primary['runId'], 'replacements': []}]})
            manifest['reference']['evidenceSha256'] = _hash(manifest['reference']['evidence'])
            config['canonicalAdoptionManifest'] = manifest
            observer = Mock(side_effect=AssertionError('Unreviewed auxiliary observations must not enter a complete reviewed page'))
            selected = recognize_complete_page(raw, config, store, request=request,
                source_context=context, line_observer=observer)
            observer.assert_not_called()
            self.assertEqual(selected['text'], original['text'])
            self.assertEqual(selected['wholePageGroups'], original['wholePageGroups'])
            self.assertEqual(selected['dualEvidenceRef'], original['dualEvidenceRef'])
            self.assertEqual(len(selected['regions']), 1)
            self.assertEqual(selected['regions'][0]['selectionStatus'], 'source_review_selected_region')
            self.assertEqual(selected['regions'][0]['providerBlock'], original['regions'][0]['providerBlock'])
            self.assertEqual(selected['coverage']['auxiliaryLineCoverage']['status'], 'not_executed_source_reviewed_complete_page')
            self.assertEqual(selected['coverage']['auxiliaryLineCoverage']['addedRegions'], 0)
            self.assertFalse(selected['coverage']['sourceQualityAccepted'])
            projection = json.loads(store.read_asset(selected['provenance']['canonicalProjectionRef']))
            self.assertEqual(selected['regions'][0]['canonicalSelection']['projectionSha256'], projection['projectionSha256'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
