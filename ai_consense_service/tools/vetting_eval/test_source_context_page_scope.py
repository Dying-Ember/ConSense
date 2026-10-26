"""Authored ASGI/CAS contracts for relevant page diagnostics; no live service."""
import copy
import unittest

from normal_observed_projection import produce_normal_observed
from retrieval_source_units import semantic_member, semantic_value
from test_normal_observed import fixture
import test_source_read_server as legacy


class SourceContextPageScopeHttpTests(unittest.TestCase):
    setUp = legacy.SourceReadHttpTests.setUp
    tearDown = legacy.SourceReadHttpTests.tearDown
    read = legacy.SourceReadHttpTests.read

    def normal(self, mixed=False):
        body = fixture(self.policy)
        graph = body['candidateGraph']
        graph['unknowns'].extend([
            {'path': 'source_coverage', 'status': 'unknown', 'reason': 'whole_source_incomplete'},
            *({'path': 'physical_page.' + str(page), 'status': 'unknown', 'reason': 'page_requires_review'}
              for page in (1, 2, 3, 4)),
            {'path': 'physical_page.4', 'status': 'unknown', 'reason': 'future_shape', 'future': True},
            {'path': 'physical_page.04', 'status': 'unknown', 'reason': 'unrecognized_path'},
            {'path': 'physical_page.0', 'status': 'unknown', 'reason': 'unrecognized_path'},
            {'path': 'physical_page.4.extra', 'status': 'unknown', 'reason': 'unrecognized_path'},
            {'path': 'physical_page.4', 'status': 'unknown', 'reason': 'unit_specific',
             'unitId': graph['units'][0]['unitId']},
        ])
        page_sequence = iter((1, None if mixed else 2, 3))
        for unit in graph['units']:
            pages = []
            for part, span in zip(unit['parts'], unit['sourceSpans']):
                page = next(page_sequence)
                part['physicalPageNo'] = page
                part['sourcePosition']['physicalPageNo'] = page
                span['sourcePosition']['physicalPageNo'] = page
                if page is not None:
                    pages.append(page)
            unit['physicalPages'] = sorted(set(pages))
        source = produce_normal_observed(graph, body['sourceBinding'], artifact_store=self.store)
        self.registry.publish(source)
        self.sources = [source]
        return source

    def prepared(self, version):
        response = self.read('prepare', view='semantic_units', semanticReadVersion=version)
        self.assertEqual(200, response.status_code, response.text)
        return {key: response.json()[key] for key in ('readManifestSha256', 'readManifestToken')}

    def test_scan_and_ids_keep_exact_text_structure_and_relevant_cross_page_warnings(self):
        source = self.normal()
        original_unknowns = copy.deepcopy(source['unknowns'])
        for version in ('source-semantic-records-v1', 'source-semantic-dictionary-v2'):
            prepared = self.prepared(version)
            for operation in ('scan', 'ids'):
                request = {'originIds': [parent['id'] for parent in source['chunks']]} if operation == 'ids' else {}
                response = self.read(operation, view='semantic_units', semanticReadVersion=version,
                                     **prepared, **request)
                self.assertEqual(200, response.status_code, response.text)
                result = response.json()
                for parent, origin in zip(source['chunks'], result['origins']):
                    expected = semantic_member(parent, source['projectionSha256'])
                    actual = {key: value for key, value in origin.items() if key != 'sourceContext'}
                    self.assertEqual(expected, actual)
                    retained = [row for row in original_unknowns
                                if row.get('unitId') in (None, parent['unitId'])
                                and (set(row) != {'path', 'status', 'reason'}
                                     or row['path'] not in {'physical_page.' + str(page)
                                                           for page in set((1, 2, 3, 4)) - set(parent['physicalPages'])})]
                    self.assertEqual(semantic_value(retained), origin['sourceContext']['unknowns'])
                    self.assertIs(False, origin['sourceContext']['sourceQualityVerified'])
                    self.assertIs(False, origin['semanticScopeVerified'])
                self.assertEqual(len(source['unknowns']), result['sources'][0]['sourceUnknownCount'])
                self.assertIs(False, result['sourceQualityVerified'])
                self.assertIsNone(result['nextCursor'])
        self.assertEqual(original_unknowns, self.registry.load(source['projectionSha256'])['unknowns'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_mixed_known_and_unknown_part_pages_keep_all_source_diagnostics(self):
        source = self.normal(mixed=True)
        for version in ('source-semantic-records-v1', 'source-semantic-dictionary-v2'):
            prepared = self.prepared(version)
            response = self.read('scan', view='semantic_units', semanticReadVersion=version, **prepared)
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(semantic_value(source['unknowns']),
                             response.json()['origins'][0]['sourceContext']['unknowns'])

    def test_prepared_original_cas_mutation_is_still_rejected(self):
        source = self.normal()
        prepared = self.prepared('source-semantic-dictionary-v2')
        descriptor = source['observationManifestRef']
        path = self.store.root / descriptor['relativePath']
        raw = path.read_bytes()
        try:
            path.write_bytes(raw + b' changed')
            response = self.read('scan', view='semantic_units',
                                 semanticReadVersion='source-semantic-dictionary-v2', **prepared)
            self.assertEqual(422, response.status_code, response.text)
        finally:
            path.write_bytes(raw)

    def test_uncertain_or_foreign_page_bindings_keep_every_source_warning(self):
        from source_read_server import _source_unknowns
        source = self.normal()
        template = source['chunks'][0]
        changes = [
            {'physicalPages': None}, {'physicalPages': []}, {'physicalPages': [True]},
            {'physicalPages': [0]}, {'physicalPages': ['1']}, {'physicalPages': [1, 1, 2]},
            {'physicalPages': [2, 1]}, {'physicalPages': [1]}, {'parts': []}, {'parts': None},
            {'parts': [{'physicalPageNo': None}]}, {'parts': [{'physicalPageNo': True}]},
            {'parts': [{'physicalPageNo': 0}]}, {'parts': [{'physicalPageNo': '1'}]},
            {'parts': [None]}, {'producerKind': 'native_pdf_canonical'},
        ]
        for change in changes:
            parent = dict(template, **change)
            with self.subTest(change=change):
                self.assertEqual(source['unknowns'], _source_unknowns(source, parent))
        self.assertEqual(source['unknowns'],
                         _source_unknowns(dict(source, producerKind='native_pdf_canonical'), template))

    def test_retained_diagnostics_preserve_object_identity_order_and_unknown_shapes(self):
        from source_read_server import _source_unknowns
        source = self.normal()
        source['unknowns'].extend([{'path': 'physical_page.4', 'reason': 'missing_status'},
                                   {'path': 'physical_page.' + '9' * 5000,
                                    'reason': 'well_formed_other_page', 'status': 'unknown'},
                                   'unknown_future_scalar', {'path': None, 'status': 'unknown'}])
        rows = _source_unknowns(source, source['chunks'][0])
        self.assertTrue(all(any(row is original for original in source['unknowns']) for row in rows))
        self.assertEqual([source['unknowns'].index(row) for row in rows],
                         sorted(source['unknowns'].index(row) for row in rows))
        self.assertIn('unknown_future_scalar', rows)
        self.assertIn({'path': 'physical_page.4', 'reason': 'missing_status'}, rows)
        self.assertIn({'path': None, 'status': 'unknown'}, rows)


if __name__ == '__main__':
    unittest.main()
