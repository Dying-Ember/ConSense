"""Prepared authored controls for per-operation verified bytes; never provider acceptance."""
import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _hash
from native_pdf_canonical_projection import produce_native_pdf, validate_native_pdf_projection
from native_pdf_projection_transport import hydrate_native_pdf_transport, publish_native_pdf_transport
from native_pdf_source_registry import NativePdfCanonicalSourceRegistry
from test_native_pdf_canonical_projection import fixture


class NativePdfValidationSessionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.store = ArtifactStore(self.root / 'artifacts')
        self.native, self.dual, self.adoption = fixture(self.store)
        self.registry = NativePdfCanonicalSourceRegistry(self.root / 'registry', self.store)
        self.raw_descriptor = self.dual['runs'][0]['rawOutputs']['page_json']['artifactDescriptor']

    def produce(self):
        return produce_native_pdf(self.native, self.dual, self.adoption, artifact_store=self.store)

    def prepare_public(self):
        internal = self.produce()
        public = publish_native_pdf_transport(internal, artifact_store=self.store)
        self.registry.publish(public)
        return internal, public

    def checked_operation(self, operation):
        """Every operation starts cold, deduplicates reads, and freshly rehashes at exit."""
        events = {}
        original = self.store._read_uncached
        def observe(descriptor, *, retain):
            key = (descriptor['relativePath'], descriptor['sha256'], descriptor['byteCount'])
            events.setdefault(key, []).append(retain)
            return original(descriptor, retain=retain)
        with patch.object(self.store, '_read_uncached', side_effect=observe):
            result = operation()
        self.assertTrue(events, 'A successful read must inspect real CAS bytes')
        for key, reads in events.items():
            self.assertEqual([True, False], reads,
                'One immutable read and a separate complete exit rehash per actual dependency: ' + key[0])
        self.assertIsNone(self.store._read_session.get(), 'No bytes or verdict survive the public operation')
        return result

    def rewrite_preserving_metadata(self, path, raw, stat):
        path.write_bytes(raw)
        os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))

    def test_each_public_operation_retains_literal_and_fresh_exit_hashes_without_persistent_cache(self):
        internal = self.checked_operation(self.produce)
        self.assertEqual('Visible fi cafe\nSecond page', internal['chunks'][0]['content'])
        self.assertFalse(internal['semanticScopeVerified'])
        self.assertEqual(internal, self.checked_operation(
            lambda: validate_native_pdf_projection(internal, artifact_store=self.store)))
        public = self.checked_operation(lambda: publish_native_pdf_transport(internal, artifact_store=self.store))
        self.assertFalse(public['humanConfirmed'])
        self.assertEqual(internal, self.checked_operation(
            lambda: hydrate_native_pdf_transport(public, artifact_store=self.store)))
        self.checked_operation(lambda: self.registry.publish(public))
        self.assertEqual(public, self.checked_operation(lambda: self.registry.load(public['projectionSha256'])))
        self.assertEqual(public['chunks'], self.checked_operation(lambda: self.registry.chunks(public['projectionSha256'])))

    def test_changed_same_size_same_mtime_raw_is_rejected_on_each_new_cold_read(self):
        internal, public = self.prepare_public()
        path = self.store.root / self.raw_descriptor['relativePath']
        raw, stat = path.read_bytes(), path.stat()
        corrupt = raw[:-1] + bytes([raw[-1] ^ 1])
        for operation in (
                lambda: validate_native_pdf_projection(internal, artifact_store=self.store),
                lambda: hydrate_native_pdf_transport(public, artifact_store=self.store),
                lambda: self.registry.load(public['projectionSha256'])):
            with self.subTest(operation=operation):
                self.rewrite_preserving_metadata(path, corrupt, stat)
                try:
                    with self.assertRaises(CanonicalProjectionError): operation()
                    self.assertIsNone(self.store._read_session.get())
                finally:
                    self.rewrite_preserving_metadata(path, raw, stat)
        self.assertEqual(internal, self.checked_operation(
            lambda: hydrate_native_pdf_transport(public, artifact_store=self.store)))

    def test_missing_raw_is_rejected_and_no_validator_recreates_evidence(self):
        _, public = self.prepare_public()
        path = self.store.root / self.raw_descriptor['relativePath']
        raw = path.read_bytes()
        path.unlink()
        try:
            with self.assertRaises(CanonicalProjectionError):
                hydrate_native_pdf_transport(public, artifact_store=self.store)
            self.assertFalse(path.exists(), 'Validation may not recreate a missing capture')
            self.assertIsNone(self.store._read_session.get())
        finally:
            path.write_bytes(raw)

    def test_mutation_after_cached_read_is_caught_by_real_exit_rehash_and_cache_is_cleared(self):
        internal, public = self.prepare_public()
        path = self.store.root / self.raw_descriptor['relativePath']
        raw, stat = path.read_bytes(), path.stat()
        original, seen = self.store._read_uncached, []
        def mutate_at_exit(descriptor, *, retain):
            if descriptor['sha256'] == self.raw_descriptor['sha256']:
                seen.append(retain)
                if not retain:
                    self.rewrite_preserving_metadata(path, raw[:-1] + bytes([raw[-1] ^ 1]), stat)
            return original(descriptor, retain=retain)
        try:
            with patch.object(self.store, '_read_uncached', side_effect=mutate_at_exit):
                with self.assertRaises(CanonicalProjectionError):
                    hydrate_native_pdf_transport(public, artifact_store=self.store)
            self.assertEqual([True, False], seen, 'Exercise actual initial read and separate exit hash')
            self.assertIsNone(self.store._read_session.get())
        finally:
            self.rewrite_preserving_metadata(path, raw, stat)
        self.assertEqual(internal, self.checked_operation(
            lambda: hydrate_native_pdf_transport(public, artifact_store=self.store)))

    def test_resealed_derived_forgery_still_reconstructs_and_leaves_no_success_cache(self):
        internal, _ = self.prepare_public()
        changed = copy.deepcopy(internal)
        changed['semanticScopeVerified'] = True
        changed['projectionSha256'] = _hash({key: value for key, value in changed.items() if key != 'projectionSha256'})
        with self.assertRaises(CanonicalProjectionError):
            validate_native_pdf_projection(changed, artifact_store=self.store)
        self.assertIsNone(self.store._read_session.get())
        self.assertEqual(internal, self.checked_operation(
            lambda: validate_native_pdf_projection(internal, artifact_store=self.store)))


if __name__ == '__main__': unittest.main()
