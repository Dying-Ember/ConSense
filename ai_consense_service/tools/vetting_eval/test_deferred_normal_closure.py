"""Authored exact-text scans and requested full-context upgrades; no live sources or models."""
import copy
import hashlib
import json
import os
import unittest
from collections import Counter
from unittest.mock import patch

import source_read_server as reader
from oer_source_registry import _serialize
from retrieval_source_units import SourceUnits
from test_compact_normal_preparation import CompactNormalPreparationTests as Base
from test_source_read_semantic import SemanticSourceReadTests


PROFILE = 'text-first-deferred-closure-v1'
SCHEMA = 'consense.source-semantic-read-manifest.v5'


class DeferredNormalClosureTests(unittest.TestCase):
    setUp = Base.setUp
    tearDown = Base.tearDown
    read = Base.read
    normal_sources = Base.normal_sources
    normal = SemanticSourceReadTests.normal

    def prepared(self, profile=PROFILE):
        response = self.read('prepare', view='semantic_units',
            semanticReadVersion='source-semantic-dictionary-v2', semanticReadProfile=profile)
        self.assertEqual(200, response.status_code, response.text)
        self.preparation = response.json()
        return {key: response.json()[key] for key in
            ('readManifestSha256', 'readManifestToken', 'semanticReadVersion', 'semanticReadProfile')
            if key in response.json()}

    def ids(self, binding, identities, **changes):
        return self.read('ids', view='semantic_units', originIds=identities, **{**binding, 'maxBytes': 16777216, **changes})

    def manifest(self, binding):
        address = binding['readManifestSha256'][0]
        return address, json.loads((self.store.root / 'sha256' / address[:2] / (address + '.blob')).read_bytes())

    def test_normal_prepare_and_complete_scan_do_not_construct_or_resolve_full_units(self):
        sources = self.normal_sources(2)
        expected = [reader.semantic_scan_member(parent, source['projectionSha256'])
                    for source in sources for parent in source['chunks']]
        groups, origins, cursor = {}, [], None
        profile = PROFILE if PROFILE in reader.CAPABILITY['semanticReadProfiles'] else 'text-first-scan-v1'
        initialize, constructed = SourceUnits.__init__, []
        def observed(catalog, parents, **changes):
            constructed.append(len(parents))
            return initialize(catalog, parents, **changes)
        with patch.object(reader.SourceUnits, '__init__', observed), \
                patch.object(reader.SourceUnits, 'resolve', side_effect=AssertionError('complete source resolved before evidence was requested')), \
                patch.object(self.rt, 'tokenizer', side_effect=AssertionError('model forbidden')):
            binding = self.prepared(profile)
            self.assertFalse(self.preparation['coldPreparation']['fullGraphValidation'])
            for _ in range(len(expected) + 1):
                response = self.read('scan', view='semantic_units', maxItems=1, maxBytes=16777216,
                    cursor=cursor, knownSemanticGroups=list(groups), **binding)
                self.assertEqual(200, response.status_code, response.text)
                result = response.json()
                self.assertEqual(PROFILE, result['semanticReadProfile'])
                self.assertEqual(PROFILE, result['scopeIdentity']['semanticReadProfile'])
                origins.extend(result['origins'])
                for group in result['units']:
                    self.assertNotIn(group['groupId'], groups)
                    groups[group['groupId']] = group['unit']
                    source = next(source for source in result['sources']
                                  if source['documentId'] == group['unit']['documentId'])
                    self.assertEqual(reader.deferred_semantic_unit(source), group['unit'])
                cursor = result['nextCursor']
                if cursor is None:
                    self.assertEqual(len(expected), result['coverage']['processedOrigins'])
                    self.assertEqual(0, result['coverage']['remainingOrigins'])
                    break
        self.assertEqual(expected, origins)
        self.assertEqual([], constructed, 'Deferred normal scan must not construct full source-unit catalogs')
        self.assertEqual(2, len(groups), 'Only one explicitly unknown group is needed per exact normal source')
        for address in binding['readManifestSha256']:
            manifest = json.loads((self.store.root / 'sha256' / address[:2] / (address + '.blob')).read_bytes())
            self.assertEqual(SCHEMA, manifest['schema'])
            self.assertEqual(1, len(manifest['groups']))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_requested_ids_rebuild_complete_current_groups_and_preserve_full_semantics(self):
        source = self.normal()
        identities = [parent['id'] for parent in reversed(source['chunks'])]
        complete = self.ids(self.prepared('full'), identities)
        self.assertEqual(200, complete.status_code, complete.text)
        binding = self.prepared()
        _, manifest = self.manifest(binding)
        unknown_group = next(iter(manifest['groups']))
        resolve = SourceUnits.resolve
        calls = []
        def observed(catalog, parent):
            calls.append(parent['id'])
            return resolve(catalog, parent)
        with patch.object(SourceUnits, 'resolve', observed):
            response = self.ids(binding, identities)
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertEqual(identities, calls)
        self.assertEqual(identities, [origin['id'] for origin in result['origins']])
        for key in ('origins', 'units', 'originUnits'):
            self.assertEqual(complete.json()[key], result[key], key)
        self.assertNotIn(unknown_group, result['originUnits'].values())
        self.assertIn('sourceContext', result['origins'][0])
        self.assertTrue(any('tableGroup' in origin for origin in result['origins']))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_interleaved_requested_ids_reload_only_their_exact_documents_once(self):
        sources = self.normal_sources(2)
        requested = [sources[1]['chunks'][-1]['id'], sources[0]['chunks'][0]['id'], sources[1]['chunks'][0]['id']]
        binding = self.prepared()
        load = self.registry.load
        loaded = []
        def observed(address):
            loaded.append(address)
            return load(address)
        with patch.object(self.registry, 'load', observed):
            response = self.ids(binding, requested)
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(requested, [origin['id'] for origin in response.json()['origins']])
        self.assertEqual(Counter({source['projectionSha256']: 1 for source in sources}), Counter(loaded))

    def test_mixed_source_profile_keeps_non_normal_complete_groups_unchanged(self):
        normal = self.normal_sources()[0]
        self.registry.publish(self.scan)
        self.sources = [normal, self.scan]
        requested = [self.scan['chunks'][0]['id'], normal['chunks'][0]['id']]
        old = self.ids(self.prepared('text-first-scan-v1'), requested)
        current = self.ids(self.prepared(), requested)
        self.assertEqual(200, old.status_code, old.text)
        self.assertEqual(200, current.status_code, current.text)
        for key in ('origins', 'units', 'originUnits'):
            self.assertEqual(old.json()[key], current.json()[key], key)

    def test_requested_complete_material_is_bounded_before_retaining_all_requested_ids(self):
        sources = self.normal_sources(2)
        requested = [parent['id'] for source in sources for parent in source['chunks']]
        binding = self.prepared()
        member, seen = reader.semantic_member, []
        def oversized(parent, address):
            origin = member(parent, address)
            origin['unknowns'] = [{'reason': 'Authored requested complete context ' + 'X' * 6000}]
            seen.append(parent['id'])
            return origin
        with patch.object(reader, 'semantic_member', oversized):
            response = self.ids(binding, requested, maxBytes=12000)
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_BUDGET_EXCEEDED', response.json()['detail']['kind'])
        self.assertLess(len(seen), len(requested))
        self.assertNotIn('origins', response.json())

    def test_manifest_profile_and_deferred_group_are_not_public_completeness_markers(self):
        self.normal_sources()
        binding = self.prepared()
        address, manifest = self.manifest(binding)
        read_asset = self.store.read_asset
        group = next(iter(manifest['groups']))
        changes = {
            'old_profile': lambda value: value.update(semanticReadProfile='text-first-scan-v1'),
            'old_schema': lambda value: value.update(schema='consense.source-semantic-read-manifest.v4'),
            'other_producer': lambda value: value['source'].update(producerKind='native_pdf'),
            'wrong_source': lambda value: value['source'].update(sourceHash='f' * 64),
            'wrong_group': lambda value: value['origins'][0].update(groupId='f' * 64),
            'wrong_unit': lambda value: value['origins'][0].update(unitId='f' * 64),
            'public_complete_status': lambda value: value['groups'][group]['unit'].update(status='complete_observed_unit'),
            'public_required_members': lambda value: value['groups'][group]['unit'].update(requiredMemberIds=[value['origins'][0]['id']]),
            'extra_group_field': lambda value: value['groups'][group]['unit'].update(hierarchyContext={}),
        }
        for name, change in changes.items():
            with self.subTest(case=name):
                poisoned = copy.deepcopy(manifest)
                change(poisoned)
                def changed(descriptor):
                    return _serialize(poisoned) if descriptor['sha256'] == address else read_asset(descriptor)
                with patch.object(self.store, 'read_asset', changed):
                    response = self.ids(binding, [manifest['origins'][0]['id']])
                self.assertEqual(422, response.status_code, response.text)
                self.assertEqual('SOURCE_READ_PREPARATION_STALE', response.json()['detail']['kind'])
        old = self.prepared('text-first-scan-v1')
        self.assertTrue(self.preparation['coldPreparation']['fullGraphValidation'])
        _, old_manifest = self.manifest(old)
        self.assertEqual('consense.source-semantic-read-manifest.v4', old_manifest['schema'])
        self.assertEqual(200, self.ids(old, [manifest['origins'][0]['id']]).status_code)

    def test_upgrade_compares_current_text_to_the_exact_prepared_scan_seed(self):
        self.normal_sources()
        binding = self.prepared()
        address, manifest = self.manifest(binding)
        entry = manifest['origins'][0]
        self.assertEqual('inline_prepared_manifest', entry['scanRecordRef']['storage'])
        poisoned = copy.deepcopy(manifest)
        descriptor = poisoned['origins'][0]['scanRecordRef']
        descriptor['origin']['text'] += ' AUTHORED changed prepared text'
        raw = _serialize(descriptor['origin'])
        descriptor.update(sha256=hashlib.sha256(raw).hexdigest(), byteCount=len(raw))
        read_asset = self.store.read_asset
        def changed(reference):
            return _serialize(poisoned) if reference['sha256'] == address else read_asset(reference)
        with patch.object(self.store, 'read_asset', changed):
            response = self.ids(binding, [entry['id']])
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_READ_PREPARATION_STALE', response.json()['detail']['kind'])

    def test_deferred_scan_and_requested_ids_preserve_actual_root_and_cas_guards(self):
        source = self.normal_sources()[0]
        binding = self.prepared()
        paths = [self.registry._path(source['projectionSha256']), self.store.root / source['observationManifestRef']['relativePath']]
        for operation in ('scan', 'ids'):
            for path in paths:
                with self.subTest(operation=operation, path=path.name):
                    original, stamp = path.read_bytes(), path.stat()
                    try:
                        path.write_bytes(bytes([original[0] ^ 1]) + original[1:])
                        os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                        response = self.ids(binding, [source['chunks'][0]['id']]) if operation == 'ids' else self.read('scan', view='semantic_units', **binding)
                        self.assertEqual(422, response.status_code, response.text)
                    finally:
                        path.write_bytes(original)
                        os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))

    def test_root_change_at_successful_exit_is_rejected_for_scan_and_upgrade(self):
        source = self.normal_sources()[0]
        binding = self.prepared()
        root = self.registry._path(source['projectionSha256'])
        original, stamp = root.read_bytes(), root.stat()
        observation = source['observationManifestRef']['sha256']
        read_uncached = self.store._read_uncached
        for operation in ('scan', 'ids'):
            reads = []
            def late(descriptor, *, retain):
                if descriptor['sha256'] == observation and not retain:
                    reads.append(retain)
                    if len(reads) == 2:
                        root.write_bytes(bytes([original[0] ^ 1]) + original[1:])
                        os.utime(root, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                return read_uncached(descriptor, retain=retain)
            try:
                with patch.object(self.store, '_read_uncached', late):
                    response = self.ids(binding, [source['chunks'][0]['id']]) if operation == 'ids' else self.read('scan', view='semantic_units', **binding)
                self.assertEqual([False, False], reads)
                self.assertEqual(422, response.status_code, response.text)
                self.assertEqual('CANONICAL_FRAME_SOURCE_CHANGED', response.json()['detail']['kind'])
            finally:
                root.write_bytes(original)
                os.utime(root, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))


if __name__ == '__main__':
    unittest.main()
