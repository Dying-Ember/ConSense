"""Actual fourteen-page scope metadata and separate authored interface contracts.

These views are not native bundles, dual evidence or accepted projections.
The existing native PDF suites separately exercise full authored project bundles.
No PDF extraction, Java, OCR or model runs are used by these new scope contracts.
"""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError
from competition_scope import _json_bytes, load_policy, load_policy_bytes
from native_pdf_canonical_projection import (
    ADOPTION_SCHEMA, _scope, _sl_technical_unit, _sl_whole_unit,
)

DATA = Path(__file__).resolve().parents[2] / 'src/test/resources/oer/sl-native-scope'


class NativePdfSlScopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inputs = json.loads((DATA / 'scope-only-inputs.json').read_bytes())['versions']

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = ArtifactStore(self.tmp.name)

    def case(self, index=0):
        row = self.inputs[index]
        native = copy.deepcopy(row['native'])
        # A scope checker receives only this metadata view. This sentinel is
        # never passed to produce_native_pdf or a bundle validator.
        native['bundleSha256'] = 'f' * 64
        descriptor = self.store.put_file(DATA / row['scopeFile'])
        manifest = dict(schema=ADOPTION_SCHEMA, nativeBundleSha256='f' * 64,
            sourceSha256=native['source']['sourceSha256'],
            sourceRevisionSha256=native['source']['sourceRevisionSha256'],
            documentId='scope-contract-' + row['versionId'], role='standard',
            module='vetting', eligibleModules=['vetting', 'advisory'],
            sourcePurpose='reference_standard', sourceClass='specification_library',
            scope=dict(requiredPhysicalPages=native['observation']['selectedPhysicalPages'],
                scopePolicySha256=descriptor['sha256'], slTechnicalUnit=dict(
                    scopeManifestRef=descriptor, editionId=row['versionId'], unitId=row['unitId'])),
            quality=dict(parseStatus='PARSED', semanticQuality='unknown', version='scope-contract-only'),
            tableGroups=[], assets=[])
        return row, native, manifest

    def rejected(self, operation):
        with self.assertRaises((CanonicalProjectionError, ValueError)):
            operation()

    def replace_scope(self, scope, manifest):
        scope['manifestContentSha256'] = hashlib.sha256(_json_bytes(
            {k: v for k, v in scope.items() if k != 'manifestContentSha256'})).hexdigest()
        descriptor = self.store.put_bytes(_json_bytes(scope))
        manifest['scope']['slTechnicalUnit']['scopeManifestRef'] = descriptor
        manifest['scope']['scopePolicySha256'] = descriptor['sha256']

    def authored(self, pages=(3,), *, table=False, multiple_ranges=False, clause=False):
        """Authored channel metadata, never a captured native or source-quality fixture."""
        _, native, manifest = self.case()
        native['source'].update(sourceSha256='a' * 64, sourceRevisionSha256='b' * 64, pageCount=6)
        native['observation'] = dict(selectedPhysicalPages=list(pages), pageObservations=[])
        ranges = []
        for page in pages:
            text = 'AUTHORED Body One; Body Two; NEXT'
            channel = dict(text=text, spans=[dict(glyphId=f'authored-{page}-{i}', start=i, end=i+1)
                                            for i in range(len(text))])
            native['observation']['pageObservations'].append(dict(physicalPage=page, glyphOrderChannel=channel))
            intervals = [(9, 17), (19, 27)] if multiple_ranges else [(9, len(text))]
            for left, right in intervals:
                ranges.append(dict(physicalPage=page, start=left, end=right,
                    startMarker=dict(kind='text', physicalPage=page, start=left, end=left+1, literal=text[left]),
                    endMarker=dict(kind='text', physicalPage=page, start=right-1, end=right, literal=text[right-1])))
        # Explicit page-edge closure is supported for a final whole unit.
        if not multiple_ranges:
            ranges[-1]['endMarker'] = dict(kind='page_end', physicalPage=pages[-1])
        unit = dict(unitId='technical-table-17' if table else 'BSX.12.text-unit', editionId='AUTHORED-edition',
            nativeSourceRevisionSha256='b' * 64, physicalPages=list(pages), completeness='whole_unit',
            scope='whole_table_group' if table else 'whole_text_unit', glyphRanges=ranges)
        if clause:
            unit['clauseId'] = 'BSX.12'
        scope = dict(schema='consense.sl-library-scope.v1', scopeVersion='AUTHORED-contract-only',
            source=dict(versionId='AUTHORED-edition', sourceSha256='a' * 64, pageCount=6),
            pageNumbering=dict(basis='original_pdf_physical_page', indexBase=1, rangeEndsInclusive=True),
            includedPageSpans=[dict(physicalPageStart=3, physicalPageEnd=6)],
            metadataSpans=[dict(physicalPageStart=1, physicalPageEnd=1)],
            excludedSpans=[dict(physicalPageStart=2, physicalPageEnd=2)], sourceUnits=[unit])
        manifest.update(sourceSha256='a' * 64, sourceRevisionSha256='b' * 64)
        manifest['scope']['requiredPhysicalPages'] = list(pages)
        manifest['scope']['slTechnicalUnit'].update(editionId=unit['editionId'], unitId=unit['unitId'])
        self.replace_scope(scope, manifest)
        return native, manifest, scope

    def adopted(self, binding, manifest):
        unit = dict(unitId=binding['unitId'], physicalPages=binding['physicalPages'],
                    scope=binding['scope'], regionIds=['region-' + str(p) for p in binding['physicalPages']])
        if binding['clauseId'] is not None:
            unit['clause'] = dict(clauseId=binding['clauseId'])
        manifest['regionReview'] = dict(regions=[dict(regionId=rid, physicalPageNo=page,
            capturedGlyphIds=list(binding['glyphIdsByPage'][page]))
            for rid, page in zip(unit['regionIds'], binding['physicalPages'])])
        return unit

    def test_same_policy_recipe_accepts_exact_file_or_cas_bytes(self):
        for row in self.inputs:
            with self.subTest(version=row['versionId']):
                path = DATA / row['scopeFile']
                self.assertEqual(load_policy(path), load_policy_bytes(path.read_bytes()))

    def test_all_seven_separate_editions_bind_actual_fourteen_page_members(self):
        seen = set()
        for index in range(7):
            row, native, manifest = self.case(index)
            with self.subTest(version=row['versionId']):
                _scope(manifest, native)
                binding = _sl_technical_unit(manifest, native, self.store)
                self.assertEqual({int(k): v for k, v in row['expectedGlyphIdsByPage'].items()}, binding['glyphIdsByPage'])
                self.assertEqual(row['versionId'], binding['editionId'])
                self.assertEqual(row['unitId'], binding['unitId'])
                seen.add((native['source']['sourceSha256'], binding['editionId']))
        self.assertEqual(7, len(seen))

    def test_missing_scope_or_edition_is_rejected_for_each_sl_class(self):
        for kind in ('sl_library', 'sl_technical_library', 'specification_library'):
            for missing in ('scope', 'edition'):
                with self.subTest(kind=kind, missing=missing):
                    _, native, manifest = self.case()
                    manifest['sourceClass'] = kind
                    if missing == 'scope':
                        del manifest['scope']['slTechnicalUnit']
                        self.rejected(lambda: _scope(manifest, native))
                    else:
                        del manifest['scope']['slTechnicalUnit']['editionId']
                        self.rejected(lambda: _sl_technical_unit(manifest, native, self.store))

    def test_other_version_scope_original_revision_and_edition_cannot_be_substituted(self):
        for index in range(7):
            for mutation in ('edition', 'original', 'revision', 'scope'):
                with self.subTest(version=index, mutation=mutation):
                    _, native, manifest = self.case(index)
                    other, _, another = self.case((index + 1) % 7)
                    if mutation == 'edition': manifest['scope']['slTechnicalUnit']['editionId'] = other['versionId']
                    if mutation == 'original': native['source']['sourceSha256'] = 'a' * 64
                    if mutation == 'revision': native['source']['sourceRevisionSha256'] = 'a' * 64
                    if mutation == 'scope':
                        manifest['scope']['slTechnicalUnit']['scopeManifestRef'] = another['scope']['slTechnicalUnit']['scopeManifestRef']
                        manifest['scope']['scopePolicySha256'] = another['scope']['scopePolicySha256']
                    self.rejected(lambda: _sl_technical_unit(manifest, native, self.store))

    def test_partial_pre_or_shifted_pages_cannot_replace_whole_technical_unit(self):
        for pages in ([1, 2], [3372], [3371, 3372], [3372, 3374]):
            with self.subTest(pages=pages):
                _, native, manifest = self.case()
                manifest['scope']['requiredPhysicalPages'] = pages
                self.rejected(lambda: _sl_technical_unit(manifest, native, self.store))

    def test_project_role_or_purpose_does_not_bypass_sl_gate(self):
        for mutation in ('role', 'purpose', 'class'):
            _, native, manifest = self.case()
            if mutation == 'role': manifest['role'] = 'tender'
            if mutation == 'purpose': manifest['sourcePurpose'] = 'project_document'
            if mutation == 'class': manifest['sourceClass'] = 'project_contract'
            self.rejected(lambda: _scope(manifest, native))

    def test_undeclared_unit_and_unknown_edition_are_rejected(self):
        for value in ('FWP3.1.5.030.10', 'FWP3.1.5.040.9', 'PRE.BS1.205', 'unknown'):
            _, native, manifest = self.case()
            manifest['scope']['slTechnicalUnit']['unitId'] = value
            self.rejected(lambda: _sl_technical_unit(manifest, native, self.store))
        _, native, manifest = self.case()
        manifest['scope']['slTechnicalUnit']['editionId'] = 'UNKNOWN'
        self.rejected(lambda: _sl_technical_unit(manifest, native, self.store))

    def test_missing_duplicate_or_changed_declared_source_unit_is_rejected(self):
        for mutation in ('missing', 'duplicate', 'heading', 'next', 'first', 'last', 'closure', 'unknown_scope'):
            with self.subTest(mutation=mutation):
                row, native, manifest = self.case()
                scope = json.loads((DATA / row['scopeFile']).read_bytes())
                unit = scope['sourceUnits'][0]
                if mutation == 'missing': scope['sourceUnits'] = []
                if mutation == 'duplicate': scope['sourceUnits'].append(copy.deepcopy(unit))
                if mutation == 'heading': unit['glyphRanges'][0]['startMarker']['literal'] = 'invented CONSTRUCTION'
                if mutation == 'next': unit['glyphRanges'][-1]['endMarker']['literal'] = 'invented END CONNECTIONS'
                if mutation == 'first': unit['glyphRanges'][0]['start'] += 1
                if mutation == 'last': unit['glyphRanges'][-1]['end'] -= 1
                if mutation == 'closure': del unit['completeness']
                if mutation == 'unknown_scope': unit['scope'] = 'unknown'
                # Re-seal only the declared candidate policy; it remains subject
                # to native source/marker/member checks, not trusted as evidence.
                self.replace_scope(scope, manifest)
                self.rejected(lambda: _sl_technical_unit(manifest, native, self.store))

    def test_authored_non_fwp_single_multi_page_and_multi_range_units_use_same_interface(self):
        for pages, table, multiple, clause in (((3,), False, False, False),
                ((3, 4, 5), False, False, True), ((3,), False, True, True),
                ((3, 4, 5), True, True, False)):
            with self.subTest(pages=pages, table=table, multiple=multiple, clause=clause):
                native, manifest, _ = self.authored(pages, table=table, multiple_ranges=multiple, clause=clause)
                _scope(manifest, native)
                binding = _sl_technical_unit(manifest, native, self.store)
                unit = self.adopted(binding, manifest)
                _sl_whole_unit(binding, manifest, [unit])
                self.assertEqual(list(pages), binding['physicalPages'])
                self.assertEqual('whole_table_group' if table else 'whole_text_unit', binding['scope'])
                self.assertEqual('BSX.12' if clause else None, binding['clauseId'])

    def test_authored_marker_range_and_complete_page_inventory_remain_mandatory(self):
        for mutation in ('missing_marker', 'wrong_kind', 'wrong_literal', 'overlap', 'reverse',
                         'missing_page', 'false_edge', 'pre', 'partial', 'split_marker'):
            with self.subTest(mutation=mutation):
                native, manifest, scope = self.authored((3, 4, 5), multiple_ranges=True)
                unit = scope['sourceUnits'][0]; spans = unit['glyphRanges']
                if mutation == 'missing_marker': del spans[0]['startMarker']
                if mutation == 'wrong_kind': spans[0]['startMarker']['kind'] = 'unknown'
                if mutation == 'wrong_literal': spans[0]['startMarker']['literal'] = 'fabricated'
                if mutation == 'overlap': spans.insert(1, copy.deepcopy(spans[0]))
                if mutation == 'reverse': spans.reverse()
                if mutation == 'missing_page': unit['glyphRanges'] = [s for s in spans if s['physicalPage'] != 4]
                if mutation == 'false_edge': spans[0]['endMarker'] = dict(kind='page_end', physicalPage=3)
                if mutation == 'pre':
                    manifest['scope']['requiredPhysicalPages'] = unit['physicalPages'] = [2]
                if mutation == 'partial': unit['completeness'] = 'partial'
                if mutation == 'split_marker': native['observation']['pageObservations'][0]['glyphOrderChannel']['spans'][9]['end'] = 11
                self.replace_scope(scope, manifest)
                self.rejected(lambda: _sl_technical_unit(manifest, native, self.store))

    def test_authored_optional_clause_and_table_scope_must_match_adoption(self):
        for clause in (False, True):
            native, manifest, _ = self.authored(table=True, clause=clause)
            binding = _sl_technical_unit(manifest, native, self.store)
            unit = self.adopted(binding, manifest)
            for mutation in ('clause', 'scope'):
                changed = copy.deepcopy(unit)
                if mutation == 'clause': changed['clause'] = dict(clauseId='invented')
                if mutation == 'scope': changed['scope'] = 'whole_text_unit'
                self.rejected(lambda: _sl_whole_unit(binding, manifest, [changed]))

    def test_authored_page_start_and_included_end_markers_bind_exact_edges(self):
        native, manifest, scope = self.authored()
        span = scope['sourceUnits'][0]['glyphRanges'][0]
        text = native['observation']['pageObservations'][0]['glyphOrderChannel']['text']
        span.update(start=0, startMarker=dict(kind='page_start', physicalPage=3),
                    endMarker=dict(kind='text', physicalPage=3, start=len(text)-1, end=len(text), literal=text[-1]))
        self.replace_scope(scope, manifest)
        binding = _sl_technical_unit(manifest, native, self.store)
        self.assertEqual(len(text), len(binding['glyphIdsByPage'][3]))

    def test_adoption_must_keep_whole_clause_and_all_declared_glyph_members(self):
        for index in range(7):
            _, native, manifest = self.case(index)
            binding = _sl_technical_unit(manifest, native, self.store)
            unit = self.adopted(binding, manifest)
            _sl_whole_unit(binding, manifest, [unit])
            for mutation in ('drop', 'duplicate', 'neighbor', 'clause', 'unit', 'split'):
                with self.subTest(version=index, mutation=mutation):
                    changed = copy.deepcopy(manifest); units = [copy.deepcopy(unit)]
                    ids = changed['regionReview']['regions'][0]['capturedGlyphIds']
                    if mutation == 'drop': ids.pop()
                    if mutation == 'duplicate': ids.append(ids[0])
                    if mutation == 'neighbor': ids.append('foreign-neighbor-glyph')
                    if mutation == 'clause': units[0]['clause']['clauseId'] += '-wrong'
                    if mutation == 'unit': units[0]['unitId'] += '-wrong'
                    if mutation == 'split': units.append(copy.deepcopy(unit))
                    self.rejected(lambda: _sl_whole_unit(binding, changed, units))


if __name__ == '__main__':
    unittest.main()
