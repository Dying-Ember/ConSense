"""Authored immutable whole-region routing contracts; no original-source scoring."""
import copy
import json
import tempfile
import unittest
from artifact_store import ArtifactStore
from structured_parser_evidence import build_dual_parser_evidence, ParserEvidenceError
from test_structured_parser_evidence import run
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
