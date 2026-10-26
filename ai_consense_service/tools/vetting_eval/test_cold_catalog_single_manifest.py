"""Authored complete-manifest validation contracts; no live sources or models."""
import copy
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from artifact_store import ArtifactStore
from oer_source_registry import SourceRegistryError, _serialize
import normal_observed_projection as normal
from test_normal_observed import fixture


class SingleManifestValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = ArtifactStore(Path(self.temp.name) / 'cas')
        body = fixture(SimpleNamespace(policyIdentity={'manifestSha256': 'a' * 64}))
        self.projection = normal.produce_normal_observed(body['candidateGraph'],
            body['sourceBinding'], artifact_store=self.store)

    def test_one_exact_cas_comparison_reuses_the_already_loaded_manifest(self):
        projection = self.projection
        before = _serialize(projection)
        with patch.object(normal, '_matches_serialized_bytes',
                wraps=normal._matches_serialized_bytes) as compared, \
                patch.object(normal, '_assemble', wraps=normal._assemble) as assembled:
            self.assertIs(projection, normal.validate_normal_observed(projection,
                artifact_store=self.store))
        self.assertEqual(1, compared.call_count)
        self.assertIs(projection['manifest'], compared.call_args.args[0])
        self.assertIs(projection['manifest'], assembled.call_args.args[0])
        self.assertEqual(before, _serialize(projection))

    def test_actual_manifest_bytes_remain_mandatory(self):
        descriptor = self.projection['observationManifestRef']
        path = self.store.root / descriptor['relativePath']
        path.write_bytes(path.read_bytes().replace(b'Visible row A', b'changed row A'))
        with self.assertRaises(ValueError):
            normal.validate_normal_observed(self.projection, artifact_store=self.store)

    def test_malformed_duplicate_noncanonical_and_wrong_root_cas_reject(self):
        raw = _serialize(self.projection['manifest'])
        alternatives = (b'{', b'{"observer":null,' + raw[1:],
            raw + b' ', b'[]', raw.replace(b'Visible row A', b'changed row A'))
        for altered in alternatives:
            with self.subTest(raw_kind=altered[:30]):
                projection = copy.deepcopy(self.projection)
                projection['observationManifestRef'] = self.store.put_bytes(altered,
                    identity_scope='authored_invalid_manifest_validation')
                with patch.object(normal, '_assemble',
                        side_effect=AssertionError('Invalid CAS reached reconstruction')), \
                        self.assertRaises(ValueError):
                    normal.validate_normal_observed(projection, artifact_store=self.store)

    def test_exact_manifest_type_and_reconstructed_output_checks_remain(self):
        for mutation in ('manifest_type', 'manifest_root', 'chunk_text', 'projection_identity'):
            with self.subTest(mutation=mutation):
                projection = copy.deepcopy(self.projection)
                if mutation == 'manifest_type':
                    projection['manifest']['candidateGraph']['humanConfirmed'] = 0
                elif mutation == 'manifest_root':
                    projection['manifest'] = []
                    projection['observationManifestRef'] = self.store.put_bytes(b'[]',
                        identity_scope='authored_invalid_manifest_root')
                elif mutation == 'chunk_text':
                    projection['chunks'][0]['content'] = 'AUTHORED corrupted derived text'
                else:
                    projection['projectionIdentity']['documentId'] = 'AUTHORED different identity'
                with self.assertRaises(SourceRegistryError):
                    normal.validate_normal_observed(projection, artifact_store=self.store)


if __name__ == '__main__':
    unittest.main()
