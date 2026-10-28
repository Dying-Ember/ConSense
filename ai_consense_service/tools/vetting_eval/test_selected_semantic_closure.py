"""Selected closure proves demanded inputs without rereading unrelated source bytes."""
from collections import Counter
from pathlib import Path
from unittest.mock import patch
import unittest

import source_read_server as reader
from test_deferred_normal_closure import DeferredNormalClosureTests as Base


class SelectedSemanticClosureTests(unittest.TestCase):
    setUp = Base.setUp
    tearDown = Base.tearDown
    read = Base.read
    normal_sources = Base.normal_sources
    prepared = Base.prepared
    ids = Base.ids

    def test_selected_closure_skips_unselected_manifest_root_and_cas_bytes(self):
        sources = self.normal_sources(3)
        binding = self.prepared()
        requested = [sources[2]['chunks'][-1]['id'], sources[0]['chunks'][0]['id'], sources[2]['chunks'][0]['id']]
        roots, assets = Counter(), Counter()
        stream, read_asset = self.registry._stream_file_identity, self.store._read_uncached

        def observed_root(path, *args, **kwargs):
            roots[str(Path(path).resolve())] += 1
            return stream(path, *args, **kwargs)

        def observed_asset(descriptor, **kwargs):
            assets[descriptor['sha256']] += 1
            return read_asset(descriptor, **kwargs)

        with patch.object(self.registry, '_stream_file_identity', observed_root), \
                patch.object(self.store, '_read_uncached', observed_asset):
            response = self.ids(binding, requested)
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual(requested, [origin['id'] for origin in result['origins']])
        self.assertEqual(self.preparation['sources'], result['sources'])
        self.assertEqual(self.preparation['coverage']['totalOrigins'], result['coverage']['totalOrigins'])
        for index, source in enumerate(sources):
            root = str(self.registry._path(source['projectionSha256']).resolve())
            observation = source['observationManifestRef']['sha256']
            if index == 1:
                self.assertEqual(0, roots[root], 'Unselected complete source root was reread')
                self.assertEqual(0, assets[observation], 'Unselected observation CAS was reread')
                self.assertEqual(0, assets[binding['readManifestSha256'][index]], 'Unselected manifest was reread')
            else:
                self.assertGreaterEqual(roots[root], 2, 'Selected source must retain entry and exit byte checks')
                self.assertGreaterEqual(assets[observation], 2, 'Selected observation must retain entry and exit byte checks')
        proof = result['selectedSourceValidation']
        self.assertEqual('selected-semantic-source-inputs-v1', proof['contract'])
        self.assertEqual([sources[i]['projectionSha256'] for i in (0, 2)], proof['projectionSha256'])
        self.assertFalse(proof['unselectedSourceBytesRevalidated'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_unknown_id_rejects_the_whole_selection(self):
        source = self.normal_sources()[0]
        response = self.ids(self.prepared(), [source['chunks'][0]['id'], 'canonical:unknown'])
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_ORIGIN_OUTSIDE_SCOPE', response.json()['detail']['kind'])
        self.assertNotIn('origins', response.json())

    def test_selected_ids_keep_the_full_scan_scope_identity(self):
        sources = self.normal_sources(3)
        binding = self.prepared()
        scan = self.read('scan', view='semantic_units', maxBytes=16777216, **binding)
        self.assertEqual(200, scan.status_code, scan.text)
        selected = self.ids(binding, [sources[1]['chunks'][0]['id']])
        self.assertEqual(200, selected.status_code, selected.text)
        self.assertEqual(scan.json()['scopeIdentity'], selected.json()['scopeIdentity'])
        self.assertEqual(scan.json()['coverage']['totalOrigins'], selected.json()['coverage']['totalOrigins'])

    def test_selected_preparation_cannot_be_forged_reordered_or_reprofiled(self):
        sources = self.normal_sources(2)
        binding = self.prepared()
        requested = [sources[0]['chunks'][0]['id']]
        for changes in ({'readManifestToken': 'f' * 64},
                        {'projectionSha256': [source['projectionSha256'] for source in reversed(sources)]},
                        {'readManifestSha256': list(reversed(binding['readManifestSha256']))},
                        {'semanticReadProfile': 'text-first-scan-v1'}):
            with self.subTest(changes=changes):
                response = self.ids(binding, requested, **changes)
                self.assertEqual(422, response.status_code, response.text)
                self.assertEqual('SOURCE_READ_PREPARATION_STALE', response.json()['detail']['kind'])

    def test_evicted_lookup_uses_existing_complete_validation_without_claiming_selected_proof(self):
        sources = self.normal_sources(2)
        original = self.prepared()
        self.sources = [sources[0]]
        with patch.object(reader, 'SELECTED_SOURCE_CACHE_BYTES', 1024):
            self.prepared()
        self.sources = sources
        response = self.ids(original, [sources[0]['chunks'][0]['id']])
        self.assertEqual(200, response.status_code, response.text)
        self.assertNotIn('selectedSourceValidation', response.json())


if __name__ == '__main__':
    unittest.main()
