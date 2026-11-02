"""Synthetic checks for composing a transfer guard with the journal deadline."""
import hashlib
import json
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import migrate_sealed_collections as migration
from token_windows import digest


class GuardedTransferTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        registered = self.root / 'registered'
        registered.mkdir()
        self.local = self.root / 'local'
        self.local.mkdir()
        seal = {'pointCount': 2, 'dimension': 2, 'persistedPointsSha256': 'a' * 64}
        seal['sealSha256'] = digest(seal)
        metadata = {'projectId': 'fixture', 'collection': 'fixture', 'signature': {},
                    'chunks': [], 'vectorIntegrity': seal}
        metadata_file = registered / 'metadata.json'
        metadata_file.write_text(json.dumps(metadata), encoding='utf-8')
        stat = metadata_file.stat()
        expected = {'path': str(metadata_file), 'bytes': stat.st_size,
                    'sha256': hashlib.sha256(metadata_file.read_bytes()).hexdigest(),
                    'device': stat.st_dev, 'inode': stat.st_ino}
        self.inputs = {'number': 1, 'collection': 'fixture', 'metadata': expected,
                       'sqlite': {'path': str(registered / 'storage.sqlite')},
                       'qdrantMeta': {'path': str(registered / 'qdrant-meta.json')},
                       'phaseB': {'sealSha256': seal['sealSha256'], 'pointCount': 2, 'dimension': 2,
                                  'matchedRecordedValues': ['persistedPointsSha256']}}
        self.events = []
        self.invalid = False
        owner = self

        class Source:
            def scroll(self, *args, **kwargs):
                owner.events.append('scroll')
                n = owner.events.count('scroll')
                point = SimpleNamespace(id='00000000-0000-0000-0000-%012d' % n,
                                        vector=[1., 0.], payload={})
                return [point], n if n == 1 else None

            def close(self):
                owner.events.append('source.close')
                if owner.source_close_error: raise RuntimeError('source close secret')

        class Target:
            def collection_exists(self, name):
                owner.events.append('exists')
                return False

            def create_collection(self, *args, **kwargs):
                owner.events.append('create')
                if owner.expire_at == 'create': owner.invalid = True

            def upsert(self, *args, **kwargs):
                owner.events.append('upsert')
                if owner.expire_at == 'upsert': owner.invalid = True

            def close(self):
                owner.events.append('target.close')
                if owner.expire_at == 'close': owner.invalid = True
                if owner.close_error: raise RuntimeError('close failed')

        self.source, self.target = Source(), Target()
        self.expire_at = None
        self.close_error = False
        self.source_close_error = False
        original = {'pointCount': 2, 'dimension': 2, 'pointsSha256': 'a' * 64}
        for p in (patch.object(migration, 'verify_runtime_tree'),
                  patch.object(migration, 'verify_reuse', return_value=original),
                  patch.object(migration, 'ColdLocalReader', return_value=self.source)):
            p.start()
            self.addCleanup(p.stop)

    def guard(self):
        if self.invalid: raise RuntimeError('guard rejected')

    def factory(self):
        self.events.append('factory')
        return self.target

    def transfer(self, **kwargs):
        return migration.transfer_with_journal(self.inputs, self.local, self.factory,
                                               self.root / 'journal', 1, 10, 10, **kwargs)

    def receipt(self):
        return [json.loads(line) for line in (self.root / 'journal' / 'migration-receipt.jsonl').read_text().splitlines()]

    def test_guard_rejects_before_target_factory(self):
        self.invalid = True
        with self.assertRaisesRegex(RuntimeError, 'guard rejected'): self.transfer(checkpoint=self.guard)
        self.assertEqual([], self.events)
        self.assertEqual('failed', self.receipt()[-1]['status'])
        self.assertFalse(self.receipt()[-1]['targetMayExist'])

    def test_guard_rejects_after_intent_before_factory(self):
        def after_intent():
            if self.receipt()[-1]['status'] == 'transfer_intent':
                raise RuntimeError('guard rejected after intent')
        with self.assertRaisesRegex(RuntimeError, 'after intent'): self.transfer(checkpoint=after_intent)
        self.assertNotIn('factory', self.events)
        self.assertEqual('failed', self.receipt()[-1]['status'])
        self.assertTrue(self.receipt()[-1]['targetMayExist'])

    def test_guard_rejects_after_dispatched_create_or_upsert(self):
        for stage in ('create', 'upsert'):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as directory:
                # Each transfer needs a fresh receipt directory.
                self.root, original_root = Path(directory), self.root
                try:
                    self.events.clear(); self.invalid = False; self.expire_at = stage
                    with self.assertRaisesRegex(RuntimeError, 'guard rejected'): self.transfer(checkpoint=self.guard)
                    self.assertEqual(0 if stage == 'create' else 1, self.events.count('scroll'))
                    self.assertEqual(0 if stage == 'create' else 1, self.events.count('upsert'))
                    self.assertEqual('target.close', self.events[-1])
                    self.assertEqual('failed', self.receipt()[-1]['status'])
                    self.assertTrue(self.receipt()[-1]['targetMayExist'])
                finally: self.root = original_root

    def test_guard_after_close_prevents_completion(self):
        self.expire_at = 'close'
        with self.assertRaisesRegex(RuntimeError, 'guard rejected'): self.transfer(checkpoint=self.guard)
        self.assertEqual('failed', self.receipt()[-1]['status'])
        self.assertNotIn('completed_candidate_not_adopted', [e['status'] for e in self.receipt()])
        self.assertTrue(self.receipt()[-1]['targetMayExist'])

    def test_external_guard_cannot_extend_deadline(self):
        def slow_guard(): time.sleep(0.03)
        with self.assertRaisesRegex(ValueError, 'deadline expired'):
            migration.transfer_with_journal(self.inputs, self.local, self.factory, self.root / 'journal',
                                            1, 0.01, 10, checkpoint=slow_guard)
        self.assertEqual([], self.events)
        self.assertEqual('failed', self.receipt()[-1]['status'])

    def test_default_remains_compatible_and_close_failure_is_preserved(self):
        self.transfer()
        self.assertEqual('completed_candidate_not_adopted', self.receipt()[-1]['status'])
        self.assertEqual('target.close', self.events[-1])
        with tempfile.TemporaryDirectory() as directory:
            self.root = Path(directory); self.close_error = True; self.events.clear()
            with self.assertRaisesRegex(RuntimeError, 'close failed'): self.transfer(checkpoint=self.guard)
            self.assertEqual('failed', self.receipt()[-1]['status'])
            self.assertTrue(self.receipt()[-1]['targetMayExist'])
            self.assertEqual(['target'], self.receipt()[-1]['closeFailedClients'])
            self.assertNotIn('close failed', json.dumps(self.receipt()))

    def test_factory_failure_preserves_unknown_and_primary_failure(self):
        def failed_factory():
            self.events.append('factory-side-effect')
            raise RuntimeError('factory failed')
        with self.assertRaisesRegex(RuntimeError, 'factory failed'):
            migration.transfer_with_journal(self.inputs, self.local, failed_factory, self.root / 'journal',
                                            1, 10, 10, checkpoint=self.guard)
        self.assertTrue(self.receipt()[-1]['targetMayExist'])
        self.assertEqual('failed', self.receipt()[-1]['status'])
        self.assertIn('source.close', self.events)

    def test_close_failure_does_not_mask_guard_failure(self):
        self.expire_at = 'upsert'; self.close_error = True; self.source_close_error = True
        with self.assertRaisesRegex(RuntimeError, 'guard rejected'): self.transfer(checkpoint=self.guard)
        self.assertEqual('target.close', self.events[-1])
        self.assertEqual('failed', self.receipt()[-1]['status'])
        self.assertTrue(self.receipt()[-1]['targetMayExist'])
        self.assertEqual(['source', 'target'], self.receipt()[-1]['closeFailedClients'])
        self.assertEqual('RuntimeError', self.receipt()[-1]['errorType'])
        self.assertNotIn('secret', json.dumps(self.receipt()))
        self.assertNotIn('close failed', json.dumps(self.receipt()))

    def test_invalid_checkpoint_rejected_before_receipt(self):
        with self.assertRaises((TypeError, ValueError)): self.transfer(checkpoint=0)
        self.assertFalse((self.root / 'journal').exists())


if __name__ == '__main__': unittest.main()
