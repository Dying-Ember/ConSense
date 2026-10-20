"""Exact saved numeric public bytes; no producer/CAS/engine/model/network call."""
import copy
import hashlib
import json
from pathlib import Path
import os
import unittest

from retrieval_source_units import SourceUnits


PINS = {
    'numeric-family-public-wire.json': 'bf9eefb1692346ef48763c7202e9bb22a2ca4b52d9c03bf4338397e48a139c24',
    'prose-numeric-public-wire.json': 'a0b6ac9445748d119f8adb93264ba43087e1900cc7bba8e76fb4687ba6cb35d0',
    'table-numeric-public-wire.json': '8884f6934c5c8ccd6ef2fd88ebab47e90876fed8c30e4819fb89441b971e1858'}


def root(name):
    # Ordinary discovery uses the tracked authored resources; an explicit
    # fixture directory remains strict and never falls back after failure.
    directory = os.environ.get('CONSENSE_OER_NUMERIC_FIXTURE_ROOT')
    if directory is None:
        directory = Path(__file__).resolve().parents[2] / 'src/test/resources/oer/native-pdf-mixed-family'
    elif not directory:
        raise AssertionError('Explicit numeric fixture root must not be empty')
    raw = (Path(directory) / name).read_bytes()
    if hashlib.sha256(raw).hexdigest() != PINS[name]:
        raise AssertionError('Saved numeric public bytes changed: ' + name)
    return json.loads(raw)


class NativePdfMixedSourceUnitTests(unittest.TestCase):
    def complete_family(self, ordinal, metadata):
        value = root('numeric-family-public-wire.json')
        before = copy.deepcopy(value)
        catalog = SourceUnits(value['chunks'])
        seed = value['chunks'][ordinal]
        result = catalog.resolve(seed)
        self.assertEqual('complete_observed_unit', result['status'], result['reason'])
        self.assertIsNone(result['reason'])
        self.assertEqual([seed], result['members'])
        self.assertEqual(metadata, result['sourceIdentity']['nativeTableMetadataVersion'])
        self.assertEqual(before, value)
        self.assertFalse(result['derivedMembersCanBecomeOrigins'])
        self.assertIsNone(result['memberScores'])
        self.assertEqual('unknown', result['qualifiersComplete'])
        self.assertFalse(result['semanticScopeVerified'])
        self.assertNotIn('hierarchyContext', result)
        self.assertNotIn('attachmentContext', result)

    def test_actual_mixed_family_prose_is_a_complete_original_observed_unit(self):
        self.complete_family(0, 'native-pdf-no-table-adoption-v1')

    def test_actual_mixed_family_table_keeps_its_whole_pointer_and_aliases(self):
        self.complete_family(1, 'source-reviewed-native-pdf-whole-table-groups-v1')

    def test_actual_standalone_roots_keep_previously_correct_observed_hashes(self):
        expected = {'prose': '15728b5e85557b4f9cab7b5e81ab6355238147b46771d98763063836cadc319a',
                    'table': '98b53ef37d0ce9bc83b652018392854436fb20cfc726bb0e7b556c964b89fa6e'}
        for kind in expected:
            value = root(kind + '-numeric-public-wire.json')
            seed = value['chunks'][0]
            result = SourceUnits(value['chunks'])._resolve_observed(seed)
            self.assertEqual('complete_observed_unit', result['status'])
            self.assertEqual(expected[kind], result['unitId'])
            self.assertEqual([seed], result['members'])

    def assert_unknown(self, value, label):
        catalog = SourceUnits(value['chunks'])
        for seed in value['chunks']:
            self.assertEqual('unknown', catalog._resolve_observed(seed)['status'], label)

    def test_changed_root_revision_scope_modules_or_sl_alias_cannot_join(self):
        for field in ('sourceHash', 'role', 'metadataVersion', 'segmentationVersion',
                'sourceQualityMetadataVersion', 'sourceQualityHash', 'originalSourceSha256',
                'sourceSha256', 'projectionRevisionSha256', 'sourceRevisionSha256', 'scopeSha256',
                'sourceEditionId', 'slUnitScopeManifestSha256', 'module', 'eligibleModules'):
            value = root('numeric-family-public-wire.json')
            value['chunks'][1][field] = ['vetting'] if field == 'eligibleModules' else 'a' * 64
            self.assert_unknown(value, field)

    def test_missing_unknown_or_wrong_unit_metadata_keeps_strict_boundary(self):
        for mutation in ('missing', 'unknown', 'scope', 'producer', 'storage'):
            value = root('numeric-family-public-wire.json')
            changed = value['chunks'][1]
            if mutation == 'missing':
                del changed['nativeTableMetadataVersion']
            else:
                field, new = {'unknown': ('nativeTableMetadataVersion', 'table-supported'),
                    'scope': ('canonicalScope', 'whole_text_unit'), 'producer': ('producerKind', 'native_docx'),
                    'storage': ('storageShape', 'legacy')}[mutation]
                changed[field] = new
            self.assert_unknown(value, mutation)

    def test_ordinary_and_unknown_producers_keep_the_old_strict_tuple(self):
        for producer in ('native_docx', 'dual_parser', 'unknown'):
            value = root('numeric-family-public-wire.json')
            for chunk in value['chunks']:
                chunk['producerKind'] = producer
            self.assert_unknown(value, producer)


if __name__ == '__main__':
    unittest.main()
