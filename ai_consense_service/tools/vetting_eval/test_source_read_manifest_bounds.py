"""Finite authored ASGI/CAS bounds; no TCP, live state, model or cap override."""
from contextlib import contextmanager
import hashlib
import json
import unittest
from unittest.mock import patch

import source_read_server as server
import test_source_read_semantic as fixtures


class SourceReadManifestBoundsTests(unittest.TestCase):
    setUp = fixtures.SemanticSourceReadTests.setUp
    tearDown = fixtures.SemanticSourceReadTests.tearDown
    read = fixtures.SemanticSourceReadTests.read
    publish = fixtures.SemanticSourceReadTests.publish
    publish_pdf = fixtures.SemanticSourceReadTests.publish_pdf
    publish_all = fixtures.SemanticSourceReadTests.publish_all

    def prepare(self, version=server.DICTIONARY_VERSION, padding=0):
        capture = self.registry._capture_file_dependencies

        @contextmanager
        def authored_metadata():
            with capture() as dependencies:
                yield dependencies
            # Test-only opaque metadata enlarges the serialized manifest before
            # its real production creation bound. All actual file paths, hashes,
            # byte counts, CAS descriptors and dependency checks stay intact.
            if padding and dependencies:
                next(iter(dependencies.values()))['authoredFixturePadding'] = 'x' * padding

        with patch.object(self.registry, '_capture_file_dependencies', authored_metadata):
            response = self.read('prepare', view='semantic_units', semanticReadVersion=version)
        self.assertEqual(200, response.status_code, response.text)
        return response, {key: response.json()[key] for key in
            ('readManifestSha256', 'readManifestToken', 'semanticReadVersion')
            if key in response.json()}

    def manifest_path(self, address):
        return self.store.root / 'sha256' / address[:2] / (address + '.blob')

    def test_sum_over_max_valid_manifests_support_warm_prepare_catalog_scan_and_ids(self):
        self.publish()
        self.sources = self.sources[:2]
        expected_ids = [parent['id'] for source in self.sources
                        for parent in self.registry.chunks(source['projectionSha256'])]
        for version in ('source-semantic-records-v1', server.DICTIONARY_VERSION):
            cold, binding = self.prepare(version, padding=9 * 1024 * 1024)
            sizes = []
            for address in binding['readManifestSha256']:
                raw = self.manifest_path(address).read_bytes()
                self.assertEqual(address, hashlib.sha256(raw).hexdigest())
                self.assertLessEqual(len(raw), server.MAX_BYTES)
                sizes.append(len(raw))
            self.assertGreater(sum(sizes), server.MAX_BYTES)
            budget = 250000
            with patch.object(self.registry, 'load', side_effect=AssertionError('No warm graph reconstruction')):
                warm = self.read('prepare', view='semantic_units', semanticReadVersion=version, maxBytes=budget)
                self.assertEqual(200, warm.status_code, warm.text)
                self.assertFalse(warm.json()['coldPreparation']['performed'])
                self.assertEqual(binding['readManifestSha256'], warm.json()['readManifestSha256'])
                catalog = self.read('catalog', view='semantic_units', semanticReadVersion=version,
                                    **{k: v for k, v in binding.items() if k != 'semanticReadVersion'}, maxBytes=budget)
                self.assertEqual(200, catalog.status_code, catalog.text)
                self.assertEqual([], catalog.json()['origins'])
                self.assertEqual(len(expected_ids), catalog.json()['coverage']['totalOrigins'])
                scan = self.read('scan', view='semantic_units', semanticReadVersion=version,
                                 **{k: v for k, v in binding.items() if k != 'semanticReadVersion'}, maxBytes=budget)
                self.assertEqual(200, scan.status_code, scan.text)
                self.assertEqual(expected_ids, [row['id'] for row in scan.json()['origins']])
                selected = list(reversed(expected_ids))
                ids = self.read('ids', view='semantic_units', semanticReadVersion=version,
                                **{k: v for k, v in binding.items() if k != 'semanticReadVersion'},
                                originIds=selected, maxBytes=budget)
                self.assertEqual(200, ids.status_code, ids.text)
                self.assertEqual(selected, [row['id'] for row in ids.json()['origins']])
            for response in (cold, warm, catalog, scan, ids):
                self.assertLessEqual(len(response.content), budget)
            print(json.dumps({'check': 'aggregate_manifest_route', 'version': version,
                'manifestBytes': sizes, 'sumBytes': sum(sizes), 'perManifestLimit': server.MAX_BYTES,
                'originCount': len(expected_ids), 'responseBytes': [len(r.content) for r in
                    (cold, warm, catalog, scan, ids)], 'responseBudget': budget}))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_one_oversized_manifest_rejects_before_cas_read(self):
        self.publish()
        self.sources = self.sources[:1]
        _, binding = self.prepare()
        address = binding['readManifestSha256'][0]
        path = self.manifest_path(address)
        original = path.read_bytes()
        try:
            path.write_bytes(original + b' ' * (server.MAX_BYTES + 1 - len(original)))
            read_asset = self.store.read_asset
            seen = []

            def traced(descriptor):
                seen.append(descriptor['sha256'])
                return read_asset(descriptor)

            with patch.object(self.store, 'read_asset', traced):
                response = self.read('catalog', view='semantic_units', **binding)
            self.assertEqual(422, response.status_code, response.text)
            self.assertEqual('SOURCE_READ_MANIFEST_TOO_LARGE', response.json()['detail']['kind'])
            self.assertNotIn(address, seen)
        finally:
            path.write_bytes(original)

    def test_cold_oversized_manifest_still_rejects_at_creation(self):
        self.publish()
        self.sources = self.sources[:1]
        with self.assertRaises(AssertionError) as failure:
            self.prepare(padding=server.MAX_BYTES)
        self.assertIn('SOURCE_READ_MANIFEST_TOO_LARGE', str(failure.exception))

    def test_same_size_manifest_cas_tamper_and_actual_source_dependency_drift_reject(self):
        self.publish()
        self.sources = self.sources[:1]
        _, binding = self.prepare()
        path = self.manifest_path(binding['readManifestSha256'][0])
        original = path.read_bytes()
        manifest = json.loads(original)
        try:
            path.write_bytes(bytes([original[0] ^ 1]) + original[1:])
            response = self.read('catalog', view='semantic_units', **binding)
            self.assertEqual(422, response.status_code, response.text)
            self.assertIn('SHA-256 or byteCount', response.text)
        finally:
            path.write_bytes(original)
        dependency = manifest['fileDependencies'][0]
        from pathlib import Path
        source = Path(dependency['path'])
        actual = source.read_bytes()
        try:
            source.write_bytes(actual + b' authored-source-drift')
            response = self.read('catalog', view='semantic_units', **binding)
            self.assertEqual(422, response.status_code, response.text)
            self.assertIn('CANONICAL_FRAME_SOURCE', response.text)
        finally:
            source.write_bytes(actual)

    def test_response_cap_still_rejects_catalog_prepare_and_ids(self):
        self.publish()
        self.sources = self.sources[:1]
        _, binding = self.prepare()
        parent = self.registry.chunks(self.sources[0]['projectionSha256'])[0]
        for operation, changes in (('prepare', {}), ('catalog', binding),
                ('ids', {**binding, 'originIds': [parent['id']]})):
            response = self.read(operation, view='semantic_units', maxBytes=1024,
                **({'semanticReadVersion': server.DICTIONARY_VERSION} if operation == 'prepare' else changes))
            self.assertEqual(422, response.status_code, response.text)
            self.assertEqual('SOURCE_READ_BUDGET_EXCEEDED', response.json()['detail']['kind'])
        too_large = self.read('catalog', view='semantic_units', **binding, maxBytes=server.MAX_BYTES + 1)
        self.assertEqual(422, too_large.status_code, too_large.text)


if __name__ == '__main__':
    unittest.main()
