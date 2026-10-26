"""Existing-object publication costs and exact-byte race guards on authored disk CAS."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore, ArtifactStoreError


class ExistingCasPutTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.store = ArtifactStore(self.directory.name)
        self.raw = b'authored semantic member with complete source text'
        self.descriptor = self.store.put_bytes(self.raw, identity_scope='source_semantic_member_v2')
        self.path = self.store.root / self.descriptor['relativePath']

    def no_publication(self):
        return patch('artifact_store.tempfile.mkstemp', side_effect=AssertionError('existing CAS must not create a temporary'))

    def put_existing(self):
        return self.store.put_bytes(self.raw, identity_scope='source_semantic_member_v2')

    def test_existing_put_verifies_once_per_owner_then_rehashes_at_exit_without_temp(self):
        reads, uncached = [], self.store._read_uncached
        def observed(descriptor, *, retain):
            reads.append((descriptor['sha256'], retain))
            return uncached(descriptor, retain=retain)
        with self.no_publication(), patch.object(self.store, '_read_uncached', observed):
            with self.store.validation_session():
                self.assertEqual(self.descriptor, self.put_existing())
                self.assertEqual(self.descriptor, self.put_existing())
                self.assertEqual([(self.descriptor['sha256'], False)], reads)
            self.assertEqual([(self.descriptor['sha256'], False)] * 2, reads)

    def test_existing_corruption_is_rejected_without_replacing_or_creating_temp(self):
        damaged = b'X' + self.raw[1:]
        self.path.write_bytes(damaged)
        with self.no_publication(), self.assertRaisesRegex(ArtifactStoreError, 'SHA-256 or byteCount'):
            self.put_existing()
        self.assertEqual(damaged, self.path.read_bytes())

    def test_same_size_restored_mtime_corruption_after_put_rejects_successful_exit(self):
        completed = []
        before = self.path.stat()
        with self.no_publication():
            with self.assertRaisesRegex(ArtifactStoreError, 'SHA-256 or byteCount'):
                with self.store.validation_session():
                    self.assertEqual(self.descriptor, self.put_existing())
                    self.path.write_bytes(b'X' + self.raw[1:])
                    os.utime(self.path, ns=(before.st_atime_ns, before.st_mtime_ns))
                completed.append(True)
        self.assertEqual([], completed)

    def test_delete_between_existing_path_check_and_verify_fails_closed(self):
        verify = self.store.verify
        def removed(descriptor):
            self.path.unlink()
            return verify(descriptor)
        with self.no_publication(), patch.object(self.store, 'verify', removed):
            with self.assertRaisesRegex(ArtifactStoreError, 'missing or unreadable'):
                self.put_existing()
        self.assertFalse(self.path.exists())

    def test_missing_object_uses_atomic_publication_and_keeps_descriptor_contract(self):
        self.path.unlink()
        import artifact_store
        create, sync, link = artifact_store.tempfile.mkstemp, artifact_store.os.fsync, artifact_store.os.link
        with patch('artifact_store.tempfile.mkstemp', wraps=create) as temporary, \
                patch('artifact_store.os.fsync', wraps=sync) as synced, \
                patch('artifact_store.os.link', wraps=link) as linked:
            self.assertEqual(self.descriptor, self.put_existing())
        self.assertEqual((1, 1, 1), (temporary.call_count, synced.call_count, linked.call_count))
        self.assertEqual(self.raw, self.store.read_asset(self.descriptor))

    def test_competing_atomic_publication_verifies_winner_bytes(self):
        for damaged in (False, True):
            with self.subTest(damaged=damaged):
                self.path.unlink(missing_ok=True)
                def compete(temporary, destination):
                    Path(destination).write_bytes((b'X' + self.raw[1:]) if damaged else self.raw)
                    raise FileExistsError(str(destination))
                with patch('artifact_store.os.link', compete):
                    if damaged:
                        with self.assertRaisesRegex(ArtifactStoreError, 'SHA-256 or byteCount'):
                            self.put_existing()
                    else:
                        self.assertEqual(self.descriptor, self.put_existing())
                self.assertEqual([], list(self.store.root.glob('.capture-*')))

    def test_invalid_identity_scope_cannot_reuse_existing_content(self):
        with self.no_publication(), self.assertRaisesRegex(ArtifactStoreError, 'identity scope'):
            self.store.put_bytes(self.raw, identity_scope='')
