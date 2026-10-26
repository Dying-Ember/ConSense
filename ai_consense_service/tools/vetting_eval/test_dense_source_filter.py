"""Authored scope fixtures with substituted vectors/models; no live services or inference."""
import unittest
import uuid

import test_window_server as baseline
import test_structural_window_runtime as dual
from test_token_windows import parent
from window_runtime import dense_scope_filter


class RecordingDb(baseline.FakeDb):
    def __init__(self):
        super().__init__()
        self.filters = []

    def query_points(self, *args, **kwargs):
        self.filters.append(kwargs.get('query_filter'))
        return super().query_points(*args, **kwargs)


class DenseSourceFilterTests(unittest.TestCase):
    setUp = baseline.ServerTests.setUp
    tearDown = baseline.ServerTests.tearDown
    index = baseline.ServerTests.index
    query = baseline.ServerTests.query

    def fixture(self, *, collision=False):
        self.db = RecordingDb()
        baseline.s.database = lambda: self.db
        rows = []
        for text, source, original in [('q selected one', 'a', '1'),
                                        ('q selected two', 'a', '1'),
                                        ('q foreign edition has a longer dense score', 'a' if collision else 'b', '2')]:
            row = parent(text, str(uuid.uuid4()), source_hash=source * 64)
            row.update(originalSourceSha256=original * 64, fileKey='SL')
            rows.append(row)
        self.index(rows)
        return rows

    def test_compact_filter_reaches_real_retrieve_and_keeps_foreign_dense_winner_out(self):
        rows = self.fixture()
        result = self.query(sourceSha256=['1' * 64])
        self.assertTrue(self.db.filters)
        for value in self.db.filters:
            conditions = {condition.key: condition.match for condition in value.must}
            self.assertNotIn('parentId', conditions)
            self.assertEqual(['a' * 64], conditions['sourceHash'].any)
        self.assertTrue(result['hits'])
        self.assertTrue(all(hit['id'] in {row['id'] for row in rows[:2]} for hit in result['hits']))
        self.assertEqual(['1' * 64], result['selectedSourceSha256'])

    def test_shared_source_hash_falls_back_before_ranking_and_preserves_scope(self):
        rows = self.fixture(collision=True)
        result = self.query(sourceSha256=['1' * 64])
        for value in self.db.filters:
            conditions = {condition.key: condition.match for condition in value.must}
            self.assertNotIn('sourceHash', conditions)
            self.assertEqual(sorted(row['id'] for row in rows[:2]), conditions['parentId'].any)
        self.assertTrue(all(hit['id'] != rows[2]['id'] for hit in result['hits']))

    def test_retrieval_phases_are_small_and_name_dense_filter_cost(self):
        self.fixture()
        self.query(sourceSha256=['1' * 64])
        phases = [value for stage, value in self.events if stage == 'retrieval-phase']
        self.assertTrue(phases)
        self.assertEqual('metadata', phases[0]['phase'])
        self.assertEqual('complete', phases[-1]['phase'])
        dense = [value for value in phases if value['phase'] == 'dense_fetch']
        self.assertTrue(dense)
        self.assertTrue(all(value['filterKey'] == 'sourceHash' and value['filterValues'] == 1 for value in dense))
        self.assertTrue(all(isinstance(value['elapsedSeconds'], float) for value in phases))
        self.assertFalse(any(key in value for value in phases for key in ('query', 'content', 'windowBindings', 'vectors', 'parentIds')))


class DenseFilterProofTests(unittest.TestCase):
    def test_full_size_membership_proof_compacts_8844_parents_to_three_hashes(self):
        scope = {f'p{index}' for index in range(8844)}
        bound = {}
        for index in range(62242):
            parent_index = index % 52539
            source = '1' if parent_index < 8544 else '2' if parent_index < 8829 else '3' if parent_index < 8844 else '4'
            bound[f'w{index}'] = {'parentId': f'p{parent_index}', 'sourceHash': source * 64}
        key, values = dense_scope_filter(bound, scope)
        self.assertEqual(('sourceHash', ['1' * 64, '2' * 64, '3' * 64]), (key, values))
        self.assertEqual({id_ for id_, row in bound.items() if row['parentId'] in scope},
                         {id_ for id_, row in bound.items() if row[key] in set(values)})

    def test_missing_or_non_hash_window_identity_keeps_original_parent_filter(self):
        for invalid in (None, '', 'generic-source', [], 1, 'A' * 64):
            with self.subTest(identity=invalid):
                bound = {'one': {'parentId': 'selected1', 'sourceHash': 'a' * 64},
                         'two': {'parentId': 'selected2', 'sourceHash': 'a' * 64},
                         'other': {'parentId': 'foreign', 'sourceHash': invalid}}
                self.assertEqual(('parentId', ['selected1', 'selected2']),
                                 dense_scope_filter(bound, {'selected1', 'selected2'}))

    def test_cross_role_or_lane_collision_cannot_pass_the_global_window_proof(self):
        for field, value in [('role', 'tender'), ('granularity', 'structure')]:
            bound = {'one': {'parentId': 'selected1', 'sourceHash': 'a' * 64},
                     'two': {'parentId': 'selected2', 'sourceHash': 'a' * 64},
                     'other': {'parentId': 'foreign', 'sourceHash': 'a' * 64, field: value}}
            self.assertEqual(('parentId', ['selected1', 'selected2']),
                             dense_scope_filter(bound, {'selected1', 'selected2'}))

    def test_non_compact_partition_keeps_parent_filter(self):
        bound = {str(index): {'parentId': str(index), 'sourceHash': str(index) * 64} for index in range(2)}
        self.assertEqual(('parentId', ['0', '1']), dense_scope_filter(bound, {'0', '1'}))


class DualDenseSourceFilterTests(unittest.TestCase):
    setUp = dual.DualRuntimeTests.setUp
    tearDown = dual.DualRuntimeTests.tearDown
    index = dual.DualRuntimeTests.index
    query = dual.DualRuntimeTests.query

    def test_context_parent_ids_fall_back_and_keep_role_and_granularity_conditions(self):
        self.db = RecordingDb()
        baseline.s.database = lambda: self.db
        for index, row in enumerate(self.corpus):
            selected = index < 2
            row.update(role='standard', originalSourceSha256=('1' if selected else '2') * 64,
                       sourceHash=('a' if selected else 'b') * 64,
                       documentId='selected' if selected else 'foreign', fileKey='SL')
        self.index()
        result = self.query(sourceSha256=['1' * 64], role='standard')
        lanes = set()
        for value in self.db.filters:
            conditions = {condition.key: condition.match for condition in value.must}
            self.assertEqual('standard', conditions['role'].value)
            self.assertEqual(sorted(row['id'] for row in self.corpus[:2]), conditions['parentId'].any)
            self.assertNotIn('sourceHash', conditions)
            lanes.add(conditions['granularity'].value)
        self.assertEqual({'short'}, lanes)
        self.assertEqual({'short', 'structure'}, set(result['denseWindowExpansion']['lanes']))
        self.assertTrue(result['hits'])
        self.assertTrue(all(hit['payload']['originalSourceSha256'] == '1' * 64 for hit in result['hits']))


if __name__ == '__main__':
    unittest.main()
