"""Authored exact-identifier fixtures; fake vectors/models only, no live calls."""
import copy
import unittest

import test_window_server as baseline
from test_retrieval_source_units import parent
from window_runtime import exact_clause_candidates


IDENTIFIER = 'COM7.D010.9'


def clause(id_, text, source='a', **changes):
    row = parent(id_, 'body:' + id_, text, IDENTIFIER, 'body/' + id_, doc=source)
    row.update(role='standard', fileKey='SL', sourceHash=source * 64,
               originalSourceSha256=source * 64, observedClause=IDENTIFIER,
               observedNodeKind='clause', observedNodeId='node:' + id_,
               observedSourceRole='body')
    row.update(changes)
    return row


class ExactClauseQueryTests(unittest.TestCase):
    setUp = baseline.ServerTests.setUp
    tearDown = baseline.ServerTests.tearDown
    index = baseline.ServerTests.index

    def fixture(self):
        baseline.s.MODEL_SETTINGS = {'embedding': {'maxTokens': 128, 'batchSize': 4},
                                     'reranker': {'maxTokens': 128, 'batchSize': 4}}
        self.body = clause('exact-body', 'Required cover is 40 mm.')
        self.toc = clause('toc', IDENTIFIER + ' cover requirements .... 42; ' * 3,
                          clauseId=None, clauseHeadingLocation=None,
                          observedClause=None, observedNodeKind='context')
        self.other = clause('other-body', 'Unrelated material requirements have a much longer explanatory description.',
                            observedClause='COM7.D010.8', clauseId='COM7.D010.8')
        return [self.body, self.toc, self.other]

    def search(self, query=IDENTIFIER, candidates=3, limit=2, **changes):
        return baseline.s.retrieve(baseline.s.RetrieveRequest(projectId='one', query=query,
                    candidates=candidates, limit=limit, sourceSha256=['a' * 64], **changes))

    def test_exact_bound_body_beats_toc_while_preserving_actual_rerank_scores(self):
        rows = self.fixture(); self.index(rows)
        result = self.search()
        self.assertEqual('exact-body', result['hits'][0]['id'])
        final = next(value for stage, value in self.events if stage == 'parent-final-ranking')
        self.assertEqual('toc', final['allRerankedInTrueScoreOrder'][0]['id'])
        score = next(hit['score'] for hit in final['allRerankedInTrueScoreOrder'] if hit['id'] == 'exact-body')
        self.assertEqual(score, result['hits'][0]['score'])
        self.assertEqual(self.body, result['hits'][0]['payload'])
        self.assertTrue(any(stage == 'rerank-window-scores' for stage, _ in self.events))
        unit = result['sourceUnits']['units'][0]
        self.assertEqual(1, unit['selectionOrdinal'])
        self.assertEqual(3, unit['originalRerankOrdinal'])
        self.assertEqual(['toc', 'other-body', 'exact-body'], result['sourceUnits']['ranking']['modelScoreOrderIds'])
        self.assertEqual(['exact-body', 'toc', 'other-body'], result['sourceUnits']['ranking']['selectionOrderIds'])
        self.assertEqual(result['sourceUnits']['ranking'], final['ranking'])

    def test_exact_parent_is_admitted_even_outside_dense_and_lexical_candidate_cap(self):
        self.index(self.fixture())
        result = self.search(candidates=1, limit=1)
        self.assertEqual(['exact-body'], [hit['id'] for hit in result['hits']])
        dense = next(value for stage, value in self.events if stage == 'dense-parent-candidates')
        self.assertNotIn('exact-body', [row['id'] for row in dense['orderedParents']])
        lexical = next(value for stage, value in self.events if stage == 'parent-bm25-candidates')
        self.assertNotIn('exact-body', lexical['orderedCandidateIds'])

    def test_case_and_surrounding_whitespace_do_not_change_identifier_lookup(self):
        self.index(self.fixture())
        result = self.search('  cOm7.d010.9  ', candidates=1, limit=1)
        self.assertEqual(['exact-body'], [hit['id'] for hit in result['hits']])

    def test_natural_query_keeps_score_order_and_has_no_exact_priority(self):
        self.index(self.fixture())
        result = self.search(IDENTIFIER + ' requirements')
        self.assertEqual('toc', result['hits'][0]['id'])
        final = next(value for stage, value in self.events if stage == 'parent-final-ranking')
        self.assertEqual([], final['exactClauseCandidateIds'])
        self.assertEqual([row['id'] for row in final['allRerankedInTrueScoreOrder']], final['selectionOrderIds'])
        self.assertNotIn('ranking', result['sourceUnits'])
        self.assertTrue(all('selectionOrdinal' not in unit for unit in result['sourceUnits']['units']))

    def test_absent_or_partial_identifier_does_not_invent_a_match(self):
        self.index(self.fixture())
        for query in ('COM7.D999.9', 'COM7.D010'):
            with self.subTest(query=query):
                result = self.search(query)
                self.assertEqual('toc', result['hits'][0]['id'])
                final = next(value for stage, value in reversed(self.events) if stage == 'parent-final-ranking')
                self.assertEqual([], final['exactClauseCandidateIds'])

    def test_toc_context_unbound_label_and_conflicting_labels_are_not_exact_owners(self):
        rows = self.fixture()
        invalid = [copy.deepcopy(self.body) for _ in range(5)]
        invalid[0]['observedNodeKind'] = 'context'
        invalid[1]['observedSourceRole'] = 'table_of_contents'
        invalid[2].update(clauseHeadingLocation=None, observedNodeId=None)
        invalid[3]['clauseId'] = 'COM7.D010.8'
        invalid[4].update(observedNodeKind=None, observedClause=None, clauseHeadingLocation=None)
        self.assertEqual([], exact_clause_candidates(IDENTIFIER, [rows[1], *invalid]))

    def test_observed_clause_and_canonical_clause_id_paths_both_support_lookup(self):
        observed = clause('observed', 'Observed text.', clauseId=None, clauseHeadingLocation=None)
        canonical = clause('canonical', 'Canonical text.', observedClause=None,
                           observedNodeKind=None, observedNodeId=None, observedSourceRole=None)
        self.assertEqual(['observed', 'canonical'],
                         [row['id'] for row in exact_clause_candidates(IDENTIFIER, [observed, canonical])])

    def test_selected_versions_keep_distinct_matches_before_continuation_fragments(self):
        self.fixture()
        rows = [clause('a-head', 'First edition.'),
                clause('a-tail', 'First edition continuation.', clauseHeadingLocation='body/a-head'),
                clause('b-head', 'Second edition has its own exact clause.', source='b')]
        self.index(rows)
        result = baseline.s.retrieve(baseline.s.RetrieveRequest(projectId='one', query=IDENTIFIER,
                      candidates=2, limit=2, sourceSha256=['a' * 64, 'b' * 64]))
        self.assertEqual({'a' * 64, 'b' * 64},
                         {hit['payload']['originalSourceSha256'] for hit in result['hits']})
        self.assertEqual(2, len(result['hits']))
        self.assertEqual({'a-head', 'a-tail'}, set(next(unit for unit in result['sourceUnits']['units']
                         if unit['sourceIdentity']['documentId'] == 'a')['requiredMemberIds']))

    def test_unselected_version_and_role_cannot_enter_exact_candidate_lane(self):
        rows = self.fixture()
        rows += [clause('foreign-version', 'Foreign exact clause with a higher score.', source='b'),
                 clause('foreign-role', 'Foreign role exact clause with a higher score.', role='tender')]
        self.index(rows)
        result = self.search(candidates=1, limit=1, role='standard')
        self.assertEqual(['exact-body'], [hit['id'] for hit in result['hits']])
        final = next(value for stage, value in self.events if stage == 'parent-final-ranking')
        self.assertEqual(['exact-body'], final['exactClauseCandidateIds'])

    def test_full_dotted_formats_preserve_literal_identifier_without_prefix_matching(self):
        for label in ('COM7.D010.9', 'BRI.D010.10', 'TOL.COM7.D010.9', 'GIN.M010.10', 'COM7.1'):
            with self.subTest(label=label):
                row = clause(label, 'Literal source body.', clauseId=label, observedClause=label)
                self.assertEqual([row], exact_clause_candidates(label.lower(), [row]))
                self.assertEqual([], exact_clause_candidates('See ' + label, [row]))


if __name__ == '__main__':
    unittest.main()
