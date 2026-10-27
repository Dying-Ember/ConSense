"""Inline/fallback/legacy exact-output and late-byte guards over authored CAS."""
import copy
import json
import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from oer_source_registry import _serialize
import source_read_server as source_read
from test_compact_normal_preparation import CompactNormalPreparationTests as Base
from test_owned_source_scan_stream import InterceptSend


class InlineGroupContractTests(unittest.TestCase):
    setUp = Base.setUp
    tearDown = Base.tearDown
    read = Base.read
    normal_sources = Base.normal_sources

    def prepare(self, **changes):
        fields = {'view': 'semantic_units', 'semanticReadVersion': 'source-semantic-dictionary-v2',
            'semanticReadProfile': 'text-first-scan-v1', 'normalProofVersion': 'compact-normal-source-proof-v1'}
        return self.read('prepare', **{**fields, **changes})

    def binding(self, response):
        self.assertEqual(200, response.status_code, response.text)
        return {key: response.json()[key] for key in (
            'readManifestSha256', 'readManifestToken', 'semanticReadVersion', 'semanticReadProfile')}

    def manifest(self, binding):
        address = binding['readManifestSha256'][0]
        path = self.store.root / 'sha256' / address[:2] / (address + '.blob')
        return path, json.loads(path.read_bytes())

    def scan_objects(self, binding):
        result = self.read('scan', view='semantic_units', maxItems=1024, **binding)
        self.assertEqual(200, result.status_code, result.text)
        body = result.json()
        return {key: body[key] for key in ('sources', 'origins', 'units', 'originUnits',
            'blockedOrigins', 'coverage', 'semanticReadVersion', 'semanticReadProfile',
            'semanticProjectionVersion', 'sourceUnitPolicy', 'limitations', 'sourceQualityVerified')}

    def stream(self, binding, callback):
        request = {'module': 'vetting', 'projectionSha256': [s['projectionSha256'] for s in self.sources],
            'operation': 'scan', 'view': 'semantic_units', 'maxItems': 1, 'maxBytes': 16 * 1024 * 1024, **binding}
        with TestClient(InterceptSend(self.app, callback)) as client:
            response = client.post('/sources/read', json=request, headers={'Accept': 'application/x-ndjson'})
        self.assertEqual(200, response.status_code, response.text)
        return [json.loads(line) for line in response.content.splitlines()]

    def test_inline_and_legacy_descriptor_manifests_return_identical_business_objects(self):
        self.normal_sources()
        inline = self.binding(self.prepare())
        _, inline_manifest = self.manifest(inline)
        self.assertEqual({'inline_prepared_manifest'}, {r['storage'] for r in inline_manifest['groups'].values()})
        expected = self.scan_objects(inline)
        put = self.store.put_bytes

        def legacy_manifest(raw, *, identity_scope='exact_supplied_bytes'):
            if identity_scope == 'source_semantic_read_manifest':
                body = json.loads(raw)
                body['schema'] = 'consense.source-semantic-read-manifest.v2'
                for entry in body['origins']:
                    descriptor = entry.pop('scanRecordRef')
                    entry['recordRef'] = (put(_serialize(descriptor['origin']), identity_scope='source_semantic_member_v2')
                        if descriptor.get('storage') == 'inline_prepared_manifest' else descriptor)
                raw = _serialize(body)
            return put(raw, identity_scope=identity_scope)

        with patch.object(source_read, 'INLINE_GROUP_BYTES', 0), patch.object(self.store, 'put_bytes', legacy_manifest):
            legacy = self.binding(self.prepare(normalProofVersion=None))
        _, legacy_saved = self.manifest(legacy)
        self.assertEqual('consense.source-semantic-read-manifest.v2', legacy_saved['schema'])
        self.assertEqual({'content_addressed_disk'}, {r['storage'] for r in legacy_saved['groups'].values()})
        self.assertEqual(expected, self.scan_objects(legacy))

    def test_total_inline_cap_uses_descriptor_fallback_without_losing_origins(self):
        sources = self.normal_sources()
        with patch.object(source_read, 'INLINE_GROUP_BYTES', 1):
            prepared = self.binding(self.prepare())
        _, manifest = self.manifest(prepared)
        self.assertEqual({'content_addressed_disk'}, {r['storage'] for r in manifest['groups'].values()})
        body = self.scan_objects(prepared)
        self.assertEqual([p['id'] for p in sources[0]['chunks']], [p['id'] for p in body['origins']])

    def test_mixed_inline_and_descriptor_groups_keep_exact_business_objects(self):
        self.normal_sources()
        inline = self.binding(self.prepare())
        _, manifest = self.manifest(inline)
        self.assertGreaterEqual(len(manifest['groups']), 2)
        cap = sum(reference['byteCount'] for reference in manifest['groups'].values()) - 1
        with patch.object(source_read, 'INLINE_GROUP_BYTES', cap):
            mixed = self.binding(self.prepare(normalProofVersion=None))
        _, saved = self.manifest(mixed)
        self.assertEqual({'inline_prepared_manifest', 'content_addressed_disk'},
            {reference['storage'] for reference in saved['groups'].values()})
        self.assertEqual(self.scan_objects(inline), self.scan_objects(mixed))

    def test_malformed_inline_metadata_is_refused_before_returning_any_origin(self):
        mutations = (
            lambda reference: reference.update(schema='unsupported-inline-format'),
            lambda reference: reference.update(byteCount=True),
            lambda reference: reference.update(byteCount=-1),
            lambda reference: reference.update(unit=[]),
            lambda reference: reference.pop('unit'),
            lambda reference: reference.update(relativePath='authored-cas-only-path'),
            lambda reference: reference.update(identityScope='authored-cas-only-scope'),
        )
        put = self.store.put_bytes
        for index, mutation in enumerate(mutations):
            with self.subTest(malformedCase=index):
                self.normal_sources(index + 1)

                def malformed_manifest(raw, *, identity_scope='exact_supplied_bytes'):
                    if identity_scope == 'source_semantic_read_manifest':
                        manifest = json.loads(raw)
                        for reference in manifest['groups'].values():
                            mutation(reference)
                        raw = _serialize(manifest)
                    return put(raw, identity_scope=identity_scope)

                with patch.object(self.store, 'put_bytes', malformed_manifest):
                    prepared = self.binding(self.prepare())
                response = self.read('scan', view='semantic_units', **prepared)
                self.assertEqual(422, response.status_code, response.text)
                self.assertEqual('SOURCE_READ_PREPARATION_STALE', response.json()['detail']['kind'])

    def test_descriptor_group_cannot_carry_an_inline_unit(self):
        self.normal_sources()
        put = self.store.put_bytes

        def mixed_manifest(raw, *, identity_scope='exact_supplied_bytes'):
            if identity_scope == 'source_semantic_read_manifest':
                manifest = json.loads(raw)
                for reference in manifest['groups'].values():
                    reference['unit'] = {'unitId': 'authored-inline-value-on-a-CAS-record'}
                raw = _serialize(manifest)
            return put(raw, identity_scope=identity_scope)

        with patch.object(source_read, 'INLINE_GROUP_BYTES', 0), patch.object(self.store, 'put_bytes', mixed_manifest):
            prepared = self.binding(self.prepare())
        response = self.read('scan', view='semantic_units', **prepared)
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_PREPARATION_STALE', response.json()['detail']['kind'])

    def test_final_manifest_bound_falls_back_to_the_existing_descriptor_size(self):
        self.normal_sources()
        inline = self.binding(self.prepare())
        _, manifest = self.manifest(inline)
        fallback = copy.deepcopy(manifest)
        fallback.pop('normalProof')
        for key, reference in fallback['groups'].items():
            fallback['groups'][key] = self.store.put_bytes(_serialize(reference['unit']), identity_scope='source_semantic_group_v2')
        bound = len(_serialize(fallback))
        inline_without_proof = {key: value for key, value in manifest.items() if key != 'normalProof'}
        self.assertGreater(len(_serialize(inline_without_proof)), bound)
        with patch.object(source_read, 'MAX_BYTES', bound):
            prepared = self.binding(self.prepare(normalProofVersion=None))
        path, saved = self.manifest(prepared)
        self.assertLessEqual(path.stat().st_size, bound)
        self.assertEqual({'content_addressed_disk'}, {r['storage'] for r in saved['groups'].values()})
        self.assertEqual(self.scan_objects(inline), self.scan_objects(prepared))

    def test_selected_inline_template_must_match_its_original_group_digest(self):
        self.normal_sources()
        put = self.store.put_bytes

        def poisoned_manifest(raw, *, identity_scope='exact_supplied_bytes'):
            if identity_scope == 'source_semantic_read_manifest':
                manifest = json.loads(raw)
                for reference in manifest['groups'].values():
                    reference['unit']['unitId'] += '-authored-tamper'
                raw = _serialize(manifest)
            return put(raw, identity_scope=identity_scope)

        with patch.object(self.store, 'put_bytes', poisoned_manifest):
            prepared = self.binding(self.prepare())
        response = self.read('scan', view='semantic_units', **prepared)
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_PREPARATION_STALE', response.json()['detail']['kind'])

    def test_inline_manifest_same_size_timestamp_change_after_page_cannot_complete(self):
        self.normal_sources()
        prepared = self.binding(self.prepare())
        path, _ = self.manifest(prepared)
        raw, stamp = path.read_bytes(), path.stat()
        changed = []

        def mutate(line):
            if line['type'] == 'page' and not changed:
                path.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
                os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                changed.append(True)
        try:
            frames = self.stream(prepared, mutate)
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        self.assertTrue(changed)
        self.assertNotIn('complete', [row['type'] for row in frames])
        self.assertEqual('error', frames[-1]['type'])
        self.assertFalse(frames[-1]['proofComplete'])

    def test_inline_dictionary_root_change_during_manifest_exit_cannot_complete(self):
        source = self.normal_sources()[0]
        prepared = self.binding(self.prepare())
        target = self.registry._path(source['projectionSha256'])
        raw, stamp = target.read_bytes(), target.stat()
        original_read = self.store._read_uncached
        changed = []

        def mutate_on_exit(descriptor, *, retain):
            if descriptor['identityScope'] == 'prepared_source_semantic_read' and not retain and not changed:
                target.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
                os.utime(target, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                changed.append(True)
            return original_read(descriptor, retain=retain)
        try:
            with patch.object(self.store, '_read_uncached', mutate_on_exit):
                frames = self.stream(prepared, lambda _: None)
        finally:
            target.write_bytes(raw)
            os.utime(target, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        self.assertTrue(changed)
        self.assertNotIn('complete', [row['type'] for row in frames])
        self.assertEqual('CANONICAL_FRAME_SOURCE_CHANGED', frames[-1]['kind'])

    def test_descriptor_fallback_group_late_change_cannot_complete(self):
        self.normal_sources()
        with patch.object(source_read, 'INLINE_GROUP_BYTES', 0):
            prepared = self.binding(self.prepare())
        _, manifest = self.manifest(prepared)
        reference = next(iter(manifest['groups'].values()))
        path = self.store._path(reference)
        raw, stamp = path.read_bytes(), path.stat()
        changed = []

        def mutate(line):
            if line['type'] == 'page' and not changed:
                path.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
                os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                changed.append(True)
        try:
            frames = self.stream(prepared, mutate)
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        self.assertTrue(changed)
        self.assertNotIn('complete', [row['type'] for row in frames])
        self.assertFalse(frames[-1]['proofComplete'])

    def test_inline_dictionary_still_refuses_the_wrong_module(self):
        self.normal_sources()
        prepared = self.binding(self.prepare())
        response = self.read('scan', view='semantic_units', module='advisory', **prepared)
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_TARGET_MODULE_MISMATCH', response.json()['detail']['kind'])


if __name__ == '__main__':
    unittest.main()
