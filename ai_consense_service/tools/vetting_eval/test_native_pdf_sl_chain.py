"""Root-captured authored four-page SL chain contracts; never source-quality acceptance.

This opt-in suite requires an actual new native/dual/raster capture and Root
source review. Ordinary discovery skips only when the fixture variable is absent;
explicit inputs must pass every original fixture validation.
No old two-page native export is rebound to a padded or different PDF.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _hash
from competition_scope import load_policy
from native_pdf_canonical_projection import (
    SL_PROJECTION_VERSION, SL_TABLE_PROJECTION_VERSION, VERSION, produce_native_pdf,
    validate_native_pdf_projection,
)
from native_pdf_projection_transport import (
    VERSION as WIRE_VERSION, TABLE_VERSION as TABLE_WIRE_VERSION,
    hydrate_native_pdf_transport, publish_native_pdf_transport,
)
from native_pdf_source_registry import NativePdfCanonicalSourceRegistry
from oer_native_pdf_server import create_native_pdf_app
from oer_server import assert_canonical_scope
from token_windows import WindowError
from test_native_pdf_canonical_projection import fixture as ordinary_fixture
import test_oer_server as http_fixture


class NativePdfSlChainTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get('CONSENSE_AUTHORED_SL_CHAIN_FIXTURE')
        if supplied is None:
            raise unittest.SkipTest('Opt-in actual-capture full8 not executed: set CONSENSE_AUTHORED_SL_CHAIN_FIXTURE to the Root-reviewed four-page fixture')
        if not supplied:
            raise RuntimeError('Root must capture and review the fresh four-page authored fixture before this suite runs')
        cls.source = Path(supplied).resolve(strict=True)
        if any(p.is_symlink() for p in cls.source.rglob('*')):
            raise RuntimeError('Authored fixture must not contain symlinked inputs')
        cls.manifest = json.loads((cls.source / 'fixture-manifest.json').read_bytes())
        if (cls.manifest.get('schema') != 'consense.authored-sl-chain-fixture.v1'
                or cls.manifest.get('status') != 'ROOT_ACCEPTED_ACTUAL_AUTHORED_NATIVE_DUAL_AND_SOURCE_REVIEW'):
            raise RuntimeError('Actual authored capture and independent Root source-review receipt are required')
        cls.inputs = {}
        for key in ('native', 'dual', 'prose', 'table', 'scope'):
            item = cls.manifest['inputs'][key]
            path = (cls.source / item['path']).resolve(strict=True)
            if not path.is_relative_to(cls.source):
                raise RuntimeError('Fixture input escaped its frozen root')
            raw = path.read_bytes()
            if len(raw) != item['byteCount'] or hashlib.sha256(raw).hexdigest() != item['sha256']:
                raise RuntimeError('Fixture input changed: ' + key)
            cls.inputs[key] = json.loads(raw)
        if cls.inputs['native']['source']['pageCount'] != 4:
            raise RuntimeError('Use the newly generated four-page PDF and its actual observer output')
        cls.policy = load_policy(cls.source / cls.manifest['inputs']['scope']['path'],
                                 cls.manifest['inputs']['scope']['sha256'])

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copytree(self.source / 'artifacts', self.root / 'artifacts')
        self.store = ArtifactStore(self.root / 'artifacts')
        self.registry = NativePdfCanonicalSourceRegistry(self.root / 'registry', self.store)

    def case(self, kind='prose'):
        return tuple(copy.deepcopy(self.inputs[key]) for key in ('native', 'dual', kind))

    def produce(self, kind='prose'):
        return produce_native_pdf(*self.case(kind), artifact_store=self.store)

    def public(self, kind='prose'):
        internal = self.produce(kind)
        public = publish_native_pdf_transport(internal, artifact_store=self.store)
        self.registry.publish(public)
        return internal, public

    def ordinary_adoption(self, *, page=3, partial=False):
        native, dual, adoption = self.case()
        adoption['sourceClass'] = 'project_contract'
        adoption['scope'].pop('slTechnicalUnit')
        adoption['scope']['requiredPhysicalPages'] = [page]
        adoption['tableGroups'] = []; adoption['assets'] = []; adoption['sourceRelationIndices'] = []
        # Root reviewed all four authored pages; only the chosen legacy region is adopted.
        reference = json.loads(self.store.read_asset(adoption['reference']['artifactDescriptor']))
        rid = next(r['regionId'] for r in reference['regions'] if r['sourceAnchor']['physicalPageNo'] == page)
        old = next(r for r in adoption['regionReview']['regions'] if r['physicalPageNo'] == 3)
        row = copy.deepcopy(old)
        observed = next(p for p in native['observation']['pageObservations'] if p['physicalPage'] == page)
        ids = [g['id'] for g in observed['nativePdfEvidence']['glyphs']]
        if partial: ids = ids[:max(1, len(ids) // 2)]
        row.update(regionId=rid, physicalPageNo=page, capturedGlyphIds=ids, glyphIdsInReadingOrder=ids,
                   nativeFrameSha256=_hash(observed['frame']), separators=[])
        runs = {run['runId']: run for run in dual['runs']}
        reference_page = next(p for p in reference['pages'] if p['sourceAnchor']['physicalPageNo'] == page)
        layouts = {}; reviews = {}
        for run_id, run in runs.items():
            run_page = next(p for p in run['pages'] if p['physicalPageNo'] == page)
            layouts[run_id] = [b['blockId'] for b in run_page['blocks']]
            image_sha = reference_page['sourceImage']['sha256']
            transform = run_page['mapping']['originalPageTransform']
            reviews[run_id] = dict(status='source_reviewed_declared', method='original_page_raster_geometry_review',
                referenceRasterSha256=image_sha, inputRasterSha256=run_page['inputArtifact']['sha256'],
                originalPageTransformSha256=_hash(transform), relationship=(
                    'same_original_raster' if transform['kind'] == 'identity'
                    else 'source_reviewed_affine_to_reference_raster'))
            if transform['targetFrame']['coordinateUnit'] == 'pt':
                # Fixture rebinding retains the same Root-reviewed original page
                # and exact raster; do not drop its explicit unit bridge.
                target, image = transform['targetFrame'], reference_page['sourceImage']
                reviews[run_id]['referenceRasterBridge'] = dict(
                    schema='consense.original-page-reference-raster-affine.v1',
                    nativeFrameSha256=_hash(observed['frame']), sourceFrame=copy.deepcopy(target),
                    referenceFrame=dict(coordinateFrame='reference_raster', coordinateUnit='pixel',
                        width=image['width'], height=image['height'], sourceSha256=target['sourceSha256'],
                        physicalPageNo=page, rasterSha256=image['sha256']),
                    matrix=[image['width']/target['width'], 0, 0, 0, image['height']/target['height'], 0])
        row['layout'] = dict(selectedRunId=next(iter(runs)), candidateBlockIds=layouts, rasterMappingReview=reviews)
        adoption['regionReview']['regions'] = [row]
        adoption['regionReview']['unadoptedGlyphs'] = [dict(physicalPageNo=p['physicalPage'],
            glyphIds=[g['id'] for g in p['nativePdfEvidence']['glyphs'] if g['id'] not in set(ids)],
            status='unadopted_unknown') for p in native['observation']['pageObservations']]
        adoption['units'] = [dict(unitId='AUTHORED-legacy-' + str(page), scope='whole_text_unit', regionIds=[rid],
                                 partSeparator=dict(kind='reviewed_region_boundary', text='\n'))]
        return native, dual, adoption

    def test_prose_and_table_complete_publish_hydrate_and_registry_chain(self):
        for kind, internal_version, wire_version in (
                ('prose', SL_PROJECTION_VERSION, WIRE_VERSION),
                ('table', SL_TABLE_PROJECTION_VERSION, TABLE_WIRE_VERSION)):
            with self.subTest(kind=kind):
                internal, public = self.public(kind)
                self.assertEqual(internal_version, internal['projectionIdentity']['projectionVersion'])
                self.assertEqual(internal_version, public['projectionIdentity']['internalProjectionVersion'])
                self.assertEqual(wire_version, public['projectionIdentity']['projectionVersion'])
                self.assertEqual(self.inputs[kind]['scope']['slTechnicalUnit']['editionId'],
                                 public['projectionIdentity']['sourceEditionId'])
                self.assertEqual(self.inputs[kind]['scope']['slTechnicalUnit']['unitId'],
                                 public['projectionIdentity']['sourceUnitId'])
                self.assertEqual(self.policy.manifest_sha256, public['projectionIdentity']['slUnitScopeManifestSha256'])
                with patch.object(self.store, 'put_bytes', side_effect=AssertionError('cold reads must not create evidence')):
                    self.assertEqual(internal, hydrate_native_pdf_transport(public, artifact_store=self.store))
                    self.assertEqual(public, self.registry.load(public['projectionSha256']))
                    self.assertEqual(public['chunks'], self.registry.chunks(public['projectionSha256'], module='advisory'))
                    self.assertEqual(1, self.registry.validate_native_chunks(public['chunks']))
                self.assertEqual([], self.policy.apply(public['chunks'])['excluded'])
                self.assertEqual('SL', public['chunks'][0]['fileKey'])
                self.assertFalse(public['semanticScopeVerified']); self.assertFalse(public['humanConfirmed'])
                self.assertEqual('unknown', public['wholeOriginalSourceCoverage'])

    def test_scope_cas_missing_or_corrupt_refuses_cold_hydration_and_registry_load(self):
        for kind in ('prose', 'table'):
            _, public = self.public(kind)
            descriptor = public['manifest']['scope']['slTechnicalUnit']['scopeManifestRef']
            path = self.store.root / descriptor['relativePath']; raw = path.read_bytes()
            for mutation in ('missing', 'corrupt'):
                with self.subTest(kind=kind, mutation=mutation):
                    if mutation == 'missing': path.unlink()
                    else: path.write_bytes(raw + b'changed scope')
                    try:
                        with patch.object(self.store, 'put_bytes', side_effect=AssertionError('no evidence repair')):
                            with self.assertRaises(CanonicalProjectionError):
                                hydrate_native_pdf_transport(public, artifact_store=self.store)
                            with self.assertRaises(ValueError): self.registry.load(public['projectionSha256'])
                        if mutation == 'missing': self.assertFalse(path.exists())
                    finally: path.write_bytes(raw)

    def test_resealed_edition_unit_or_recipe_identity_changes_are_rejected(self):
        for kind in ('prose', 'table'):
            internal = self.produce(kind)
            for field, value in (('sourceEditionId', 'another-edition'), ('sourceUnitId', 'another-unit'),
                                 ('slUnitScopeManifestSha256', 'f' * 64), ('projectionVersion', VERSION)):
                with self.subTest(kind=kind, field=field):
                    changed = copy.deepcopy(internal); changed['projectionIdentity'][field] = value
                    changed['projectionSha256'] = _hash({k: v for k, v in changed.items() if k != 'projectionSha256'})
                    with self.assertRaises(CanonicalProjectionError):
                        validate_native_pdf_projection(changed, artifact_store=self.store)
            public = publish_native_pdf_transport(internal, artifact_store=self.store)
            for field, value in (('internalProjectionVersion', VERSION), ('projectionVersion', 'future-recipe')):
                with self.subTest(kind=kind, wire_field=field):
                    changed = copy.deepcopy(public); changed['projectionIdentity'][field] = value
                    changed['projectionSha256'] = _hash({k: v for k, v in changed.items() if k != 'projectionSha256'})
                    with self.assertRaises(CanonicalProjectionError):
                        hydrate_native_pdf_transport(changed, artifact_store=self.store)

    def test_sl_still_requires_actual_dual_reference_region_raster_and_table(self):
        for kind in ('prose', 'table'):
            for mutation in ('dual', 'reference', 'region', 'raster', 'table'):
                if kind == 'prose' and mutation == 'table': continue
                with self.subTest(kind=kind, mutation=mutation):
                    native, dual, adoption = self.case(kind); restore = None
                    if mutation == 'dual': adoption['dualBundleSha256'] = 'f' * 64
                    if mutation == 'reference': adoption['reference']['evidenceSha256'] = 'f' * 64
                    if mutation == 'region': adoption['regionReview']['regions'][0]['capturedGlyphIds'].pop()
                    if mutation == 'raster':
                        descriptor = adoption['reference']['pageImages'][0]
                        path = self.store.root / descriptor['relativePath']; raw = path.read_bytes()
                        restore = (path, raw); path.write_bytes(b'corrupt original raster')
                    if mutation == 'table': adoption['tableGroups'][0]['fragments'][0]['cells'].pop()
                    try:
                        with self.assertRaises(CanonicalProjectionError):
                            produce_native_pdf(native, dual, adoption, artifact_store=self.store)
                    finally:
                        if restore: restore[0].write_bytes(restore[1])

    def test_direct_registered_ordinary_recipe_cannot_hide_known_sl_or_partial_unit(self):
        for page, partial in ((1, False), (2, False), (3, False), (3, True)):
            with self.subTest(page=page, partial=partial):
                internal = produce_native_pdf(*self.ordinary_adoption(page=page, partial=partial), artifact_store=self.store)
                public = publish_native_pdf_transport(internal, artifact_store=self.store)
                self.registry.publish(public)
                with self.assertRaises(WindowError) as rejected:
                    assert_canonical_scope(self.registry.chunks(public['projectionSha256']), self.policy)
                self.assertEqual('known_sl_native_requires_explicit_unit', rejected.exception.metadata['code'])

    def test_server_refuses_known_original_without_sl_binding_before_publishing(self):
        backend_case = http_fixture.OerHttpContractTest()
        backend_case.setUp(); self.addCleanup(backend_case.tearDown)
        app = create_native_pdf_app(self.policy, backend=backend_case.backend, artifact_store=self.store)
        with TestClient(app) as client:
            for page, partial in ((1, False), (2, False), (3, False), (3, True)):
                with self.subTest(page=page, partial=partial):
                    body = {key: self.store.put_bytes(_bytes(value), identity_scope='canonical_json_object')
                            for key, value in zip(('nativeBundleRef', 'dualBundleRef', 'adoptionManifestRef'),
                                                  self.ordinary_adoption(page=page, partial=partial))}
                    response = client.post('/project/native-pdf', json=body)
                    self.assertEqual(422, response.status_code, response.text)
                    self.assertEqual('KNOWN_SL_NATIVE_REQUIRES_EXPLICIT_UNIT', response.json()['detail']['kind'])
            self.assertFalse(app.state.source_registry.root.exists())
        self.assertEqual([], backend_case.encodes); self.assertEqual([], backend_case.predictions)

    def test_ordinary_project_original_keeps_existing_full_public_chain(self):
        native, dual, adoption = ordinary_fixture(self.store)
        self.assertNotEqual(self.policy.source_sha256, native['source']['sourceSha256'])
        adoption['scope']['scopePolicySha256'] = self.policy.manifest_sha256
        internal = produce_native_pdf(native, dual, adoption, artifact_store=self.store)
        public = publish_native_pdf_transport(internal, artifact_store=self.store)
        self.registry.publish(public)
        self.assertEqual(internal, hydrate_native_pdf_transport(public, artifact_store=self.store))
        self.assertEqual(public, self.registry.load(public['projectionSha256']))
        self.assertEqual([], self.policy.apply(public['chunks'])['excluded'])
        self.assertNotIn('sourceEditionId', public['projectionIdentity'])

    def test_unknown_inventory_and_semantic_limits_cannot_be_resealed_as_acceptance(self):
        internal, public = self.public()
        self.assertEqual(internal['unadoptedGlyphs'], json.loads(self.store.read_asset(
            public['internalProjectionRef']['artifactDescriptor']))['unadoptedGlyphs'])
        self.assertTrue(any(row['glyphCount'] for row in public['unadoptedGlyphSummary']))
        for field, value in (('semanticScopeVerified', True), ('humanConfirmed', True),
                             ('wholeOriginalSourceCoverage', 'complete')):
            with self.subTest(field=field):
                changed = copy.deepcopy(public); changed[field] = value
                changed['projectionSha256'] = _hash({k: v for k, v in changed.items() if k != 'projectionSha256'})
                with self.assertRaises(CanonicalProjectionError):
                    hydrate_native_pdf_transport(changed, artifact_store=self.store)


if __name__ == '__main__': unittest.main()
