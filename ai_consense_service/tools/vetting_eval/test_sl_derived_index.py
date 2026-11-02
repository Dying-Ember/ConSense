from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import patch

from sl_artifacts import write_json, write_subset
from sl_derived_index import SlDerivedIndex
from sl_only_subset import prepare
from sl_vector_copy import copy_and_verify
from test_sl_only_subset import fixture
from test_sl_vector_copy import FakeQdrant
from token_windows import WindowError


class SlDerivedIndexTest(TestCase):
    def test_loads_written_subset_and_checks_pinned_metadata(self):
        frozen, source, points = fixture()
        client = FakeQdrant({'mixed_collection': {p.id: p for p in points}})
        output, manifest = prepare(frozen, source, frozen_sha256='f'*64,
                                   source_collection='mixed_collection', editions=['v1'],
                                   project_id='sl-only', collection='sl_subset')
        metadata, derived, receipt = copy_and_verify(client, FakeQdrant(), source, output, manifest)
        with TemporaryDirectory() as root:
            files = write_subset(Path(root)/'new', metadata, derived, receipt)
            reader = SlDerivedIndex(files['manifest']['path'], files['manifest']['sha256'], 'http://qdrant')
            self.assertEqual('sl-only', reader.metadata['projectId'])
            reader.candidate_identity('sl-only')
            with self.assertRaises(WindowError):
                reader.candidate_identity('wrong-project')
            with open(files['metadata']['path'], 'ab') as stream:
                stream.write(b' ')
            with self.assertRaises(WindowError):
                reader.candidate_identity('sl-only')

    def test_catalog_loads_both_pinned_manifests_without_mixing_sources(self):
        from fixed_reference_query import from_environment
        frozen, source, points = fixture()
        client = FakeQdrant({'mixed_collection': {p.id: p for p in points}})
        with TemporaryDirectory() as root:
            rows = []
            for edition in ('v1', 'v2'):
                output, manifest = prepare(frozen, source, frozen_sha256='f'*64,
                    source_collection='mixed_collection', editions=[edition],
                    project_id=edition, collection='subset-'+edition)
                metadata, derived, receipt = copy_and_verify(client, FakeQdrant(), source, output, manifest)
                files = write_subset(Path(root)/edition, metadata, derived, receipt)
                rows.append({'projectId':edition, 'manifestPath':files['manifest']['path'],
                             'manifestSha256':files['manifest']['sha256']})
            catalog = write_json(Path(root)/'catalog.json',
                                 {'schema':'consense.sl-reference-catalog.v1', 'references':rows})
            env = {'CONSENSE_FIXED_REFERENCE_CATALOG':catalog['path'],
                   'CONSENSE_FIXED_REFERENCE_CATALOG_SHA256':catalog['sha256'],
                   'CONSENSE_QDRANT_URL':'http://qdrant'}
            def reference(backend, reader, project, sha):
                return SimpleNamespace(project_id=project, manifest_sha256=sha,
                    sl_sources={x['sha256'] for x in reader.frozen['originalSources']['slSources']})
            with patch.dict('os.environ', env, clear=True), patch('fixed_reference_query.FixedReferenceQuery', reference):
                router = from_environment(None)
                self.assertEqual({'v1','v2'}, set(router.by_project))
                self.assertEqual({'a'*64}, router.by_project['v1'].sl_sources)
                self.assertEqual({'b'*64}, router.by_project['v2'].sl_sources)
            with patch.dict('os.environ', {**env,'CONSENSE_FIXED_REFERENCE_CATALOG_SHA256':'0'*64}, clear=True):
                with self.assertRaises(WindowError): from_environment(None)
