"""Cold semantic preparation fixtures; authored sources only, no models or live services."""
import copy
import unittest
from unittest.mock import patch

import retrieval_source_units as source_units
from retrieval_source_units import SourceUnits, semantic_unit, semantic_unit_template
from test_retrieval_source_units import parent
from test_typed_parent_hierarchy_context import corpus as hierarchy_corpus
from test_attachment_context_guards import native_corpus as attachment_corpus
import test_source_read_semantic as semantic_http
import source_read_server


PROJECTION = 'c' * 64


def clause_corpus():
    return [parent('head', 'body:1', 'Clause heading', 'A1', 'body/1'),
            parent('tail', 'body:2', 'Only when approved.', 'A1', 'body/1'),
            parent('other', 'body:3', 'Independent clause.', 'B1', 'body/3')]


class ColdPrepareSourceUnitTests(unittest.TestCase):
    def assert_no_member_bodies(self, value):
        if isinstance(value, dict):
            if 'members' in value:
                self.assertEqual([], value['members'], 'Thin closure retained expanded member bodies')
            for child in value.values():
                self.assert_no_member_bodies(child)
        elif isinstance(value, (list, tuple)):
            for child in value:
                self.assert_no_member_bodies(child)

    def compare_templates(self, parents):
        before = copy.deepcopy(parents)
        full = SourceUnits(parents)
        expected = [semantic_unit_template(semantic_unit(full.resolve(seed), PROJECTION))
                    for seed in parents]
        original_deepcopy = copy.deepcopy
        identities = {row['id'] for row in parents}

        def no_parent_copy(value, *args, **kwargs):
            if isinstance(value, dict):
                self.assertFalse(value.get('id') in identities and 'content' in value and 'parts' in value,
                                 'Thin resolver deep-copied a complete canonical parent payload')
                if 'members' in value:
                    self.assertEqual([], value['members'], 'Thin resolver copied a nonempty member container')
            return original_deepcopy(value, *args, **kwargs)

        with patch.object(source_units.copy, 'deepcopy', side_effect=no_parent_copy):
            thin = SourceUnits(parents, include_members=False)
            for seed, template in zip(parents, expected):
                result = thin.resolve(seed)
                self.assert_no_member_bodies(result)
                self.assertEqual(template, semantic_unit_template(semantic_unit(result, PROJECTION)))
            for cache in (thin.cache, thin.hierarchy_cache, thin.attachment_cache):
                self.assert_no_member_bodies(cache)
        self.assertEqual(before, parents, 'Neither resolver may modify the canonical parents')
        return thin, expected

    def test_default_resolver_retains_detached_complete_payloads(self):
        copied = 0
        for build in (clause_corpus, hierarchy_corpus, attachment_corpus):
            parents = build()
            catalog = SourceUnits(parents)
            by_id = {row['id']: row for row in parents}
            for seed in parents:
                result = catalog.resolve(seed)
                for context in (result, result.get('hierarchyContext'), result.get('attachmentContext')):
                    if context is None:
                        continue
                    for member in context['members']:
                        original = by_id[member['id']]
                        self.assertEqual(original, member)
                        self.assertIsNot(original, member)
                        self.assertIsNot(original['parts'], member['parts'])
                        copied += 1
        self.assertGreater(copied, 0, 'The default public resolver must still return complete bodies')

    def test_thin_clause_and_overlapping_block_templates_are_exact(self):
        self.compare_templates(clause_corpus())
        self.compare_templates([parent('start', 'body:1', 'ABCD'),
                                parent('continuation', 'body:1', 'CDEF', start=2)])

    def test_thin_native_hierarchy_and_attachment_templates_are_exact(self):
        for build in (hierarchy_corpus, attachment_corpus):
            with self.subTest(fixture=build.__name__):
                thin, expected = self.compare_templates(build())
                self.assertTrue(any('hierarchyContext' in row for row in expected))
                if build is attachment_corpus:
                    self.assertTrue(any('attachmentContext' in row for row in expected))
                self.assert_no_member_bodies(thin.cache)

    def test_thin_unknown_conflict_and_cross_scope_results_are_unchanged(self):
        cases = []
        parents = clause_corpus()
        parents[1]['clauseHeadingLocation'] = 'body/2'
        cases.append(('conflicting_clause_heading', parents))
        parents = clause_corpus()
        parents[1]['sourceHash'] = 'd' * 64
        cases.append(('cross_source_clause', parents))
        parents = clause_corpus()
        parents[0].pop('sourceQualityHash')
        cases.append(('missing_source_quality', parents))
        parents = hierarchy_corpus()
        parents = [parents[0], parents[2], parents[3]]
        cases.append(('missing_hierarchy_endpoint', parents))
        parents = hierarchy_corpus()
        parents[0]['relations'][0]['basis'] = 'AUTHORED conflicting declaration'
        cases.append(('conflicting_hierarchy_declaration', parents))
        parents = attachment_corpus()
        parents[0]['relations'][0]['basis'] = ''
        cases.append(('unreviewed_attachment', parents))
        parents = attachment_corpus()
        parents[1]['referenceSha256'] = '8' * 64
        cases.append(('cross_revision_attachment', parents))
        for name, parents in cases:
            with self.subTest(case=name):
                _, expected = self.compare_templates(parents)
                contexts = [context for row in expected
                            for context in (row, row.get('hierarchyContext'), row.get('attachmentContext'))
                            if context is not None]
                self.assertTrue(any(row['status'] == 'unknown' for row in contexts), name)

    def test_thin_cache_reuse_keeps_empty_bodies_and_detached_results(self):
        for build in (clause_corpus, hierarchy_corpus, attachment_corpus):
            with self.subTest(fixture=build.__name__):
                parents = build()
                thin, expected = self.compare_templates(parents)
                seed = parents[0]
                retained = thin.cache[seed['id']]
                first = thin.resolve(seed)
                first['requiredMemberIds'].append('AUTHORED caller-only mutation')
                for field in ('hierarchyContext', 'attachmentContext'):
                    if field in first:
                        first[field]['reason'] = 'AUTHORED caller-only mutation'
                with patch.object(thin, '_hierarchy_context', side_effect=AssertionError('Hierarchy cache rebuilt')), \
                        patch.object(thin, '_attachment_context', side_effect=AssertionError('Attachment cache rebuilt')):
                    again = thin.resolve(seed)
                self.assertIs(retained, thin.cache[seed['id']])
                self.assertEqual(expected[0], semantic_unit_template(semantic_unit(again, PROJECTION)))
                for cache in (thin.cache, thin.hierarchy_cache, thin.attachment_cache):
                    self.assert_no_member_bodies(cache)


class ColdPreparePublicReadEquivalenceTests(unittest.TestCase):
    setUp = semantic_http.SemanticSourceReadTests.setUp
    tearDown = semantic_http.SemanticSourceReadTests.tearDown
    read = semantic_http.SemanticSourceReadTests.read
    normal = semantic_http.SemanticSourceReadTests.normal

    def prepare(self, **profile):
        response = self.read('prepare', view='semantic_units', **profile)
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        return {**profile, 'readManifestSha256': result['readManifestSha256'],
                'readManifestToken': result['readManifestToken']}

    def test_dictionary_prepare_omits_closure_bodies_and_encodes_each_template_once(self):
        self.normal()
        modes, templates = [], []
        initialize, serialize = SourceUnits.__init__, source_read_server._serialize
        def observed(catalog, parents, **kwargs):
            modes.append(kwargs.get('include_members'))
            return initialize(catalog, parents, **kwargs)
        def encoded(value):
            if isinstance(value, dict) and 'unitId' in value and 'requiredMemberIds' in value:
                templates.append(value)
            return serialize(value)
        with patch.object(SourceUnits, '__init__', observed), patch.object(source_read_server, '_serialize', encoded):
            self.prepare(semanticReadVersion='source-semantic-dictionary-v2')
        self.assertTrue(modes)
        self.assertEqual([False] * len(modes), modes)
        self.assertTrue(templates)
        self.assertEqual(len(templates), len({id(value) for value in templates}))

    def test_dictionary_and_text_first_keep_legacy_and_public_ids_evidence(self):
        source = self.normal()
        ids = [row['id'] for row in source['chunks']]
        public = self.read('ids', originIds=ids, maxBytes=16777216)
        self.assertEqual(200, public.status_code, public.text)
        self.assertEqual(source['chunks'], public.json()['origins'])

        prepared = self.prepare()
        legacy = self.read('ids', view='semantic_units', **prepared, originIds=ids, maxBytes=16777216)
        self.assertEqual(200, legacy.status_code, legacy.text)
        legacy = legacy.json()
        expected_templates = []
        for origin, unit, public_unit in zip(legacy['origins'], legacy['units'], public.json()['units']):
            template = semantic_unit_template(unit)
            self.assertEqual(semantic_unit_template(semantic_unit(public_unit, source['projectionSha256'])),
                             template)
            expected_templates.append({**template, 'documentId': origin['documentId']})

        profiles = [{'semanticReadVersion': 'source-semantic-dictionary-v2'},
                    {'semanticReadVersion': 'source-semantic-dictionary-v2',
                     'semanticReadProfile': 'text-first-scan-v1'}]
        for profile in profiles:
            with self.subTest(profile=profile):
                prepared = self.prepare(**profile)
                response = self.read('ids', view='semantic_units', **prepared,
                                     originIds=ids, maxBytes=16777216)
                self.assertEqual(200, response.status_code, response.text)
                result = response.json()
                self.assertEqual(legacy['origins'], result['origins'],
                                 'Progressive ids must keep complete semantic text and table evidence')
                groups = {row['groupId']: row['unit'] for row in result['units']}
                self.assertEqual(expected_templates,
                                 [groups[result['originUnits'][identity]] for identity in ids])
                if 'semanticReadProfile' in profile:
                    scan = self.read('scan', view='semantic_units', **prepared,
                                     maxItems=1024, maxBytes=16777216)
                    self.assertEqual(200, scan.status_code, scan.text)
                    self.assertEqual(ids, [row['id'] for row in scan.json()['origins']])
                    self.assertEqual([row['text'] for row in legacy['origins']],
                                     [row['text'] for row in scan.json()['origins']])
                    self.assertEqual(source['chunks'][0]['tableGroup'],
                                     public.json()['origins'][0]['tableGroup'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)


if __name__ == '__main__':
    unittest.main()
