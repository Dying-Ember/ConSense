"""Authored saved-score fixtures; no source, model, database or HTTP calls."""
import copy
import unittest

from retrieval_source_units import select_source_units
from test_retrieval_source_units import parent, hit


def page_row(id_, page, text, *, document='authored-pdf', clause=None, heading=None):
    row = parent(id_, 'page:%s:%s' % (page, id_), text, clause=clause, heading=heading, doc=document)
    row.update(originalSourceSha256='c' * 64, physicalPages=[page], producerKind='normal_observed')
    return row


class RepeatedPageTextSeedsTest(unittest.TestCase):
    def test_repeated_unowned_page_text_does_not_consume_top_k(self):
        headers = [page_row('header-%s' % page, page, 'APPENDIX - REFERENCE LIST') for page in range(1, 13)]
        body = page_row('operative', 3, 'The reference documents do not form part of the Contract.')
        corpus = headers + [body]
        raw = [hit(row, 1.0 - index / 100) for index, row in enumerate(corpus)]
        unchanged = copy.deepcopy((corpus, raw))
        selected, episode = select_source_units(corpus, raw, 2, hierarchy_context=False)
        self.assertEqual(['header-1', 'operative'], [row['id'] for row in selected])
        self.assertEqual([1.0, .88], [row['score'] for row in selected])
        self.assertEqual(11, len(episode['skippedRepeatedPageTextSeeds']))
        self.assertEqual('header-1', episode['skippedRepeatedPageTextSeeds'][-1]['selectedSeedId'])
        self.assertEqual(12, episode['skippedRepeatedPageTextSeeds'][-1]['selectionOrdinal'])
        self.assertEqual([], episode['skippedDuplicateUnitSeeds'])
        self.assertEqual(unchanged, (corpus, raw))

    def test_known_different_clause_occurrences_remain_distinct(self):
        rows = [page_row('clause-%s' % i, i, 'The Contractor shall submit the record.', clause='A.%s' % i,
                         heading='page/%s/clause' % i) for i in range(1, 5)]
        selected, _ = select_source_units(rows, [hit(row, .9 - i / 10) for i, row in enumerate(rows)], 3)
        self.assertEqual(['clause-1', 'clause-2', 'clause-3'], [row['id'] for row in selected])

    def test_different_sources_never_share_a_text_representative(self):
        rows = [page_row('%s-%s' % (doc, page), page, 'REFERENCE LIST', document=doc)
                for doc in ('one', 'two') for page in range(1, 4)]
        selected, episode = select_source_units(rows, [hit(row, .9 - i / 100) for i, row in enumerate(rows)], 2)
        self.assertEqual(['one-1', 'two-1'], [row['id'] for row in selected])
        self.assertEqual(2, len(episode['skippedRepeatedPageTextSeeds']))

    def test_same_page_or_only_two_pages_does_not_trigger_diversity(self):
        for pages in ([1, 1, 1], [1, 2, 2]):
            with self.subTest(pages=pages):
                rows = [page_row('row-%s' % i, page, 'Repeated text') for i, page in enumerate(pages)]
                selected, _ = select_source_units(rows, [hit(row, .9 - i / 10) for i, row in enumerate(rows)], 3)
                self.assertEqual(3, len(selected))

    def test_long_repeated_body_and_unknown_source_identity_are_retained(self):
        for unknown in (False, True):
            with self.subTest(unknown=unknown):
                text = 'Repeated ordinary contractual text. ' * 8 if not unknown else 'REFERENCE LIST'
                rows = [page_row('row-%s' % page, page, text) for page in range(1, 4)]
                if unknown:
                    for row in rows:
                        row.pop('sourceQualityHash')
                selected, _ = select_source_units(rows, [hit(row, .9 - i / 10) for i, row in enumerate(rows)], 3)
                self.assertEqual(3, len(selected))

    def test_skipped_repeated_text_still_requires_an_exact_canonical_payload(self):
        rows = [page_row('row-%s' % page, page, 'REFERENCE LIST') for page in range(1, 4)]
        raw = [hit(row, .9 - i / 10) for i, row in enumerate(rows)]
        raw[1] = copy.deepcopy(raw[1])
        raw[1]['payload']['content'] = 'Invented text'
        with self.assertRaises(ValueError):
            select_source_units(rows, raw, 3)


if __name__ == '__main__':
    unittest.main()
