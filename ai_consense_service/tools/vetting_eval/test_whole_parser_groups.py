"""Authored immutable whole-region routing contracts; no original-source scoring."""
import copy
import json
import tempfile
import unittest
from artifact_store import ArtifactStore
from structured_parser_evidence import build_dual_parser_evidence, ParserEvidenceError
from test_structured_parser_evidence import run, accepted_manifest
from whole_parser_groups import select_whole_parser_groups


def policy():
    return {'schema': 'consense.whole-parser-feature-policy.v1', 'version': 'authored-policy-v1',
        'acceptance': {'status': 'accepted', 'acceptedBy': 'codex', 'method': 'independent_source_feature_evaluation',
                       'evidenceSha256': 'e'*64, 'referenceVersion': 'authored-independent-wire-contract'},
        'rules': [{'parser': 'mineru', 'requires': {'hasTables': True, 'allTableStructuresObserved': True,
                                                'allGeometryBound': True, 'completeRawCapture': True}}]}


class WholeParserGroupsTests(unittest.TestCase):
    def test_accepted_feature_policy_selects_entire_region_with_notes_without_cell_fusion(self):
        paddle, mineru = run('paddleocr-vl', table=True, text='authored alternative'), run('mineru', table=True)
        bundle = build_dual_parser_evidence([paddle, mineru])
        groups = select_whole_parser_groups(bundle, policy())
        self.assertEqual(groups[0]['selectedParser'], 'mineru')
        selected = next(row for row in groups[0]['candidates'] if row['parser'] == 'mineru')
        self.assertEqual(selected['blocks'], mineru['pages'][0]['blocks'])
        self.assertEqual(len(selected['blocks']), 2)
        self.assertEqual(selected['blocks'][1]['label'], 'footnote')
        self.assertFalse(groups[0]['sourceQualityAccepted'])
        self.assertIsNone(bundle['canonicalSelection'])

    def test_complete_existing_source_review_selects_the_reviewed_whole_page(self):
        paddle, mineru = run('paddleocr-vl'), run('mineru', text='Reviewed source wording')
        manifest = accepted_manifest(mineru)
        for block in mineru['pages'][0]['blocks'][1:]:
            route = copy.deepcopy(manifest['routes'][0]); route['blockId'] = block['blockId']
            manifest['routes'].append(route)
        bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=manifest)
        self.assertEqual(len(bundle['canonicalSelection']), 2)
        group = select_whole_parser_groups(bundle)[0]
        self.assertEqual(group['selectedParser'], 'mineru')
        self.assertEqual(group['selectionStatus'], 'source_review_selected_candidate')
        self.assertFalse(group['sourceQualityAccepted'])
        self.assertEqual(group['candidates'][1]['blocks'], mineru['pages'][0]['blocks'])

    def test_partial_source_review_does_not_authorize_an_entire_page(self):
        paddle, mineru = run('paddleocr-vl'), run('mineru')
        bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=accepted_manifest(mineru))
        self.assertIsNone(select_whole_parser_groups(bundle)[0]['selectedParser'])

    def test_conflicting_or_stale_review_never_selects_the_first_page_by_order(self):
        paddle, mineru = run('paddleocr-vl'), run('mineru')
        full = []
        for selected in (paddle, mineru):
            manifest = accepted_manifest(selected)
            for block in selected['pages'][0]['blocks'][1:]:
                route = copy.deepcopy(manifest['routes'][0]); route['blockId'] = block['blockId']
                manifest['routes'].append(route)
            full.append(manifest)
        for manifests in (full, list(reversed(full))):
            combined = {'schema': full[0]['schema'], 'routes': sum((m['routes'] for m in manifests), [])}
            bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=combined)
            self.assertIsNone(select_whole_parser_groups(bundle)[0]['selectedParser'])
        for key in ('sourceSha256', 'sourceRevisionSha256', 'configSha256', 'parserIdentitySha256', 'runId'):
            stale = copy.deepcopy(full[1]); stale['routes'][0][key] = 'f' * 64
            bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=stale)
            self.assertIsNone(select_whole_parser_groups(bundle)[0]['selectedParser'], key)

    def test_review_disagreeing_with_an_independent_feature_policy_stays_unknown(self):
        paddle, mineru = run('paddleocr-vl'), run('mineru')
        manifest = accepted_manifest(mineru)
        route = copy.deepcopy(manifest['routes'][0]); route['blockId'] = mineru['pages'][0]['blocks'][1]['blockId']; manifest['routes'].append(route)
        bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=manifest)
        feature = policy(); feature['rules'] = [{'parser': 'paddleocr-vl', 'requires': {'completeRawCapture': True}}]
        self.assertIsNone(select_whole_parser_groups(bundle, feature)[0]['selectedParser'])

    def test_no_policy_retains_both_whole_pages_without_adopting_either(self):
        bundle = build_dual_parser_evidence([run('paddleocr-vl'), run('mineru', text='different')])
        before = copy.deepcopy(bundle)
        group = select_whole_parser_groups(bundle)[0]
        self.assertIsNone(group['selectedParser'])
        self.assertEqual(group['selectionStatus'], 'candidate_unverified')
        self.assertEqual(group['source'], bundle['source'])
        self.assertEqual(group['candidates'][0]['page'], bundle['runs'][0]['pages'][0])
        self.assertEqual(bundle, before)

    def test_incomplete_raw_capture_cannot_match_an_accepted_feature_policy(self):
        paddle = run('paddleocr-vl', table=True, outputs={'page_json': {'page_index': 0,
            'parsing_res_list': []}})
        bundle = build_dual_parser_evidence([paddle, run('mineru', table=True)])
        group = select_whole_parser_groups(bundle, policy())[0]
        self.assertIsNone(group['selectedParser'])
        self.assertTrue(all(not candidate['features']['completeRawCapture'] for candidate in group['candidates']))

    def test_ambiguous_policy_cannot_combine_cells_or_choose_by_confidence(self):
        both = policy()
        both['rules'].append({'parser': 'paddleocr-vl', 'requires': both['rules'][0]['requires']})
        bundle = build_dual_parser_evidence([run('paddleocr-vl', table=True), run('mineru', table=True)])
        group = select_whole_parser_groups(bundle, both)[0]
        self.assertIsNone(group['selectedParser'])
        self.assertEqual(len(group['candidates']), 2)

    def test_source_answer_lookup_is_rejected_as_a_generic_feature_policy(self):
        for key, value in (('sourceSha256', 'c'*64), ('physicalPageNo', 3), ('answer', 'yes')):
            malicious = policy(); malicious['rules'][0]['requires'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                select_whole_parser_groups(build_dual_parser_evidence([run('paddleocr-vl'), run('mineru')]), malicious)

    def test_mutated_bundle_or_external_capture_is_rejected_not_relabelled_unknown(self):
        bundle = build_dual_parser_evidence([run('paddleocr-vl'), run('mineru')])
        bundle['runs'][0]['pages'][0]['blocks'][0]['text'] = 'changed after capture'
        with self.assertRaises(ParserEvidenceError):
            select_whole_parser_groups(bundle)
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            bundle = build_dual_parser_evidence([run('paddleocr-vl', artifact_store=store),
                run('mineru', artifact_store=store)], artifact_store=store)
            descriptor = bundle['runs'][0]['rawOutputs']['page_json']['artifactDescriptor']
            (store.root / descriptor['relativePath']).write_bytes(b'tampered actual CAS')
            with self.assertRaises(ParserEvidenceError):
                select_whole_parser_groups(bundle, artifact_store=store)


if __name__ == '__main__':
    unittest.main()
