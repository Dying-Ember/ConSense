"""Small publication-journal fixtures; no external release is certified."""
import hashlib
import json
import os
import tempfile
import threading
import unittest
from contextlib import ExitStack, contextmanager
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

import oer_request_evidence as evidence
from source_request_context import RequestControl, commit_pending, publication_reference, request_context, stage
from token_windows import WindowError


class PublicationJournalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.journal = evidence.RequestEvidence(self.root / 'state' / 'run-evidence')
        (self.root / 'state' / 'projects').mkdir(parents=True)
        self.metadata = self.journal.metadata_path('p1')
        self.control = RequestControl(20)
        self.proof = {'sourceEvidenceSha256': 'b' * 64, 'modelReleaseEvidenceSha256': 'c' * 64}

    def staged(self, record):
        journal_id = publication_reference()
        value = {'projectId': 'p1', 'generation': 'a' * 32, 'publicationJournal': journal_id, 'counts': {'n': 1}}
        raw = json.dumps(value).encode()
        pending = self.root / 'pending.json'
        pending.write_bytes(raw)
        intent = {'projectId': 'p1', 'generation': 'a' * 32, 'metadataSha256': hashlib.sha256(raw).hexdigest(),
                  'priorMetadataSha256': None}
        stage(lambda: pending.replace(self.metadata), intent)
        return value

    def complete(self, record):
        commit_pending(source_frame_verified=True, model_session_verified=True)  # Fixture claim, not GPU evidence.
        record['response'] = evidence._save(record['directory'] / 'response.json', {'projectId': 'p1', 'ok': True})

    def test_cached_metadata_tampering_rejected_for_read_and_recovery(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        altered = {**value, 'chunks': [{'text': 'changed'}], '_metadataFileStamp': ('transient',)}
        with self.assertRaises(WindowError):
            self.journal.verify_index(self.metadata, altered, 'p1')
        with self.assertRaises(WindowError):
            self.journal.verify_index(self.metadata, {**value, 'counts': {'n': True}}, 'p1')
        with self.assertRaises(WindowError):
            self.journal.verify_index(self.metadata, {**value, 'counts': {'n': 1.0}}, 'p1')
        fresh = evidence.RequestEvidence(self.journal.root)
        with patch.object(fresh, '_verify_publication_evidence', return_value=self.proof):
            with self.assertRaises(WindowError):
                fresh.recover_index(self.metadata, altered, 'p1')
        self.assertTrue(fresh.block_path('p1').exists())

    def test_cached_json_number_and_boolean_types_are_distinct(self):
        self.assertFalse(evidence._same_json({'nested': [1]}, {'nested': [True]}))
        self.assertFalse(evidence._same_json({'nested': [0]}, {'nested': [False]}))
        self.assertFalse(evidence._same_json({'nested': [1]}, {'nested': [1.0]}))
        self.assertTrue(evidence._same_json({'nested': [1, True]}, {'nested': [1, True]}))

    @unittest.skipIf(os.name == 'nt', 'Windows denies replacing an open fixture file; separate platform test required')
    def test_metadata_replacement_after_read_is_rejected(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        original_stat = Path.stat
        replaced = False
        checks = 0
        def changed_stat(path, *args, **kwargs):
            nonlocal checks, replaced
            if path == self.metadata and kwargs.get('follow_symlinks') is False and not replaced:
                checks += 1
                if checks == 2:
                    replacement = self.root / 'replacement.json'
                    replacement.write_bytes(self.metadata.read_bytes())
                    replacement.replace(self.metadata)
                    replaced = True
            return original_stat(path, *args, **kwargs)
        with patch.object(Path, 'stat', changed_stat):
            with self.assertRaises(WindowError):
                self.journal.verify_index(self.metadata, value, 'p1')
        self.assertTrue(replaced)

    @unittest.skipIf(os.name == 'nt', 'POSIX ctime identity check')
    def test_in_place_change_with_restored_mtime_is_rejected(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        original_stat = Path.stat
        checks = 0
        changed = False
        def changed_stat(path, *args, **kwargs):
            nonlocal checks, changed
            if path == self.metadata and kwargs.get('follow_symlinks') is False and not changed:
                checks += 1
                if checks == 2:
                    before = original_stat(path)
                    raw = path.read_bytes()
                    path.write_bytes(raw.replace(b'p1', b'p2'))
                    os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
                    changed = True
            return original_stat(path, *args, **kwargs)
        with patch.object(Path, 'stat', changed_stat):
            with self.assertRaises(WindowError):
                self.journal.verify_index(self.metadata, value, 'p1')
        self.assertTrue(changed)

    @unittest.skipIf(os.name == 'nt', 'POSIX FIFO and O_NOFOLLOW check')
    def test_fifo_and_symlink_metadata_are_rejected(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        real = self.root / 'real.json'
        self.metadata.replace(real)
        os.mkfifo(self.metadata)
        with self.assertRaises(WindowError):
            self.journal.verify_index(self.metadata, value, 'p1')
        self.metadata.unlink()
        self.metadata.symlink_to(real)
        with self.assertRaises(WindowError):
            self.journal.verify_index(self.metadata, value, 'p1')

    def test_completed_receipt_still_blocked_in_fresh_journal(self):
        with self.assertRaisesRegex(WindowError, 'Source or model release witness'):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        receipt = json.loads((record['directory'] / 'receipt.json').read_bytes())
        self.assertEqual('completed', receipt['terminalStatus'])
        self.assertTrue(self.journal.block_path('p1').exists())
        with self.assertRaisesRegex(WindowError, 'blocked'):
            evidence.RequestEvidence(self.journal.root).verify_index(self.metadata, value, 'p1')
        with self.assertRaisesRegex(WindowError, 'blocked'):
            evidence.RequestEvidence(self.journal.root).verify_index(self.root / 'absent', None, 'p1')

    def test_source_frame_failure_does_not_publish(self):
        self.metadata.write_bytes(b'old')
        with self.assertRaisesRegex(ValueError, 'frame failed'):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                self.staged(record)
                raise ValueError('frame failed')
        self.assertEqual(b'old', self.metadata.read_bytes())
        self.assertFalse(self.journal.block_path('p1').exists())
        self.assertIsNone(self.journal.verify_index(self.root / 'absent', None, 'p1'))

    def test_receipt_fsync_failure_after_visible_write_stays_blocked(self):
        save = evidence._save
        def fail_receipt(path, value, *, durable=False):
            if path.name == 'receipt.json':
                save(path, value)
                raise OSError('receipt fsync failed after visible write')
            return save(path, value, durable=durable)
        with patch.object(evidence, '_save', side_effect=fail_receipt), self.assertRaisesRegex(OSError, 'fsync failed'):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        self.assertTrue((record['directory'] / 'receipt.json').exists())
        with self.assertRaisesRegex(WindowError, 'blocked'):
            evidence.RequestEvidence(self.journal.root).verify_index(self.metadata, value, 'p1')

    def test_block_directory_sync_failure_preserves_block_and_old_metadata(self):
        self.metadata.write_bytes(b'old')
        sync = evidence._sync_directory
        def fail_after_block(path):
            if path == self.journal.root and self.journal.block_path('p1').exists():
                raise OSError('block directory fsync failed')
            return sync(path)
        with patch.object(evidence, '_sync_directory', side_effect=fail_after_block), self.assertRaisesRegex(OSError, 'block directory'):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                self.staged(record)
                self.complete(record)
        self.assertEqual(b'old', self.metadata.read_bytes())
        self.assertTrue(self.journal.block_path('p1').exists())

    def test_receipt_directory_sync_failure_keeps_block(self):
        sync = evidence._sync_directory
        def fail_receipt_dir(path):
            if (path / 'receipt.json').exists():
                raise OSError('receipt directory fsync failed')
            return sync(path)
        with patch.object(evidence, '_sync_directory', side_effect=fail_receipt_dir), self.assertRaisesRegex(OSError, 'receipt directory'):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        self.assertTrue((record['directory'] / 'receipt.json').exists())
        with self.assertRaisesRegex(WindowError, 'blocked'):
            evidence.RequestEvidence(self.journal.root).verify_index(self.metadata, value, 'p1')

    def test_ambiguous_block_disappearance_is_unknown_in_new_instance(self):
        with self.assertRaises(WindowError):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        self.journal.block_path('p1').unlink()  # Simulate unlink becoming visible before an error or crash.
        original_open= os.open
        def guarded_open(path,*args,**kwargs):
            if Path(path)==self.metadata:raise AssertionError('unverified metadata must not be read')
            return original_open(path,*args,**kwargs)
        with patch.object(os,'open',guarded_open), self.assertRaisesRegex(WindowError, 'No independently verified'):
            evidence.RequestEvidence(self.journal.root).verify_index(self.metadata, value, 'p1')

    def test_fake_evidence_normal_success_and_explicit_new_instance_recovery(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        self.assertFalse(self.journal.block_path('p1').exists())
        publication_proof = json.loads((record['directory'] / 'publication-proof.json').read_bytes())
        self.assertEqual(self.proof, publication_proof['proof'])
        self.assertEqual(hashlib.sha256((record['directory'] / 'receipt.json').read_bytes()).hexdigest(), publication_proof['receiptSha256'])
        self.assertEqual('completed', self.journal.verify_index(self.metadata, value, 'p1')['terminalStatus'])
        fresh = evidence.RequestEvidence(self.journal.root)
        with self.assertRaisesRegex(WindowError, 'No independently verified'):
            fresh.verify_index(self.metadata, value, 'p1')
        with patch.object(fresh, '_verify_publication_evidence', return_value=self.proof):
            decision = fresh.recover_index(self.metadata, value, 'p1')
            self.assertEqual(self.proof, decision['proof'])
            self.assertEqual(publication_proof['receiptSha256'], decision['receiptSha256'])
            self.assertEqual(1, len(list(record['directory'].glob('recovery-*.json'))))
            self.assertEqual('completed', fresh.verify_index(self.metadata, value, 'p1')['terminalStatus'])
            with self.assertRaisesRegex(WindowError, 'already verified'):
                fresh.recover_index(self.metadata, value, 'p1')
            self.metadata.write_text(json.dumps({**value, 'extra': 'tampered'}))
            with self.assertRaisesRegex(WindowError, 'metadata bytes differ'):
                fresh.verify_index(self.metadata, value, 'p1')

    def test_unlink_followed_by_directory_sync_error_requires_recovery(self):
        sync = evidence._sync_directory
        def fail_after_unlink(path):
            if path == self.journal.root and not self.journal.block_path('p1').exists() and list(path.glob('*/receipt.json')):
                raise OSError('unlink durability unknown')
            return sync(path)
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof), \
             patch.object(evidence, '_sync_directory', side_effect=fail_after_unlink), \
             self.assertRaisesRegex(OSError, 'unlink durability unknown'):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        self.assertTrue(self.journal.block_path('p1').exists())
        with self.assertRaisesRegex(WindowError, 'blocked'):
            self.journal.verify_index(self.metadata, value, 'p1')
        self.journal.block_path('p1').unlink()  # Crash in the unlink/recreate window remains unknown.
        with self.assertRaisesRegex(WindowError, 'uncertain'):
            self.journal.verify_index(self.metadata, value, 'p1')
        fresh = evidence.RequestEvidence(self.journal.root)
        with self.assertRaisesRegex(WindowError, 'No independently verified'):
            fresh.verify_index(self.metadata, value, 'p1')
        with self.assertRaisesRegex(WindowError, 'Source or model release witness'):
            fresh.recover_index(self.metadata, value, 'p1')
        self.assertTrue(fresh.block_path('p1').exists())

    def test_boolean_proof_is_not_accepted(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=True), \
             self.assertRaisesRegex(WindowError, 'identities are required'):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                self.staged(record)
                self.complete(record)
        self.assertTrue(self.journal.block_path('p1').exists())

    def test_recovery_rejects_changed_response_and_keeps_block(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        (record['directory'] / 'response.json').write_text('{"projectId":"wrong"}')
        fresh = evidence.RequestEvidence(self.journal.root)
        with patch.object(fresh, '_verify_publication_evidence', return_value=self.proof), \
             self.assertRaisesRegex(WindowError, 'response changed'):
            fresh.recover_index(self.metadata, value, 'p1')
        self.assertTrue(fresh.block_path('p1').exists())
        self.assertFalse(list(record['directory'].glob('recovery-*.json')))

    def test_recovery_rejects_changed_request_and_keeps_block(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        (record['directory'] / 'request.json').write_text('{"projectId":"wrong"}')
        fresh = evidence.RequestEvidence(self.journal.root)
        with patch.object(fresh, '_verify_publication_evidence', return_value=self.proof), \
             self.assertRaisesRegex(WindowError, 'request or response changed'):
            fresh.recover_index(self.metadata, value, 'p1')
        self.assertTrue(fresh.block_path('p1').exists())

    def test_current_instance_rejects_receipt_tampering(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        receipt_path = record['directory'] / 'receipt.json'
        receipt = json.loads(receipt_path.read_bytes())
        receipt['elapsedSeconds'] += 1
        receipt_path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(WindowError, 'No independently verified'):
            self.journal.verify_index(self.metadata, value, 'p1')

    def test_same_instance_reader_waits_for_release_decision(self):
        entered, proceed = threading.Event(), threading.Event()
        def proof(*args):
            entered.set()
            if not proceed.wait(5):
                raise TimeoutError('fixture release barrier')
            return self.proof
        def publish():
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
            return value
        with patch.object(self.journal, '_verify_publication_evidence', side_effect=proof), ThreadPoolExecutor(max_workers=2) as pool:
            writer = pool.submit(publish)
            self.assertTrue(entered.wait(5))
            value = json.loads(self.metadata.read_bytes())
            reader = pool.submit(self.journal.verify_index, self.metadata, value, 'p1')
            self.assertFalse(reader.done())
            proceed.set()
            self.assertEqual(value, writer.result(timeout=5))
            self.assertEqual('completed', reader.result(timeout=5)['terminalStatus'])

    def test_existing_index_preflight_keeps_block_and_uncertain_priority(self):
        self.assertIsNone(self.journal.verify_index(self.metadata, None, 'p1'))
        self.metadata.write_bytes(b'authored metadata')
        with patch.object(Path, 'read_bytes', side_effect=AssertionError('metadata must not be read')):
            with self.assertRaisesRegex(WindowError, 'No independently verified') as failure:
                self.journal.require_verified_index_for_load('p1')
            self.assertEqual('INDEX_PUBLICATION_DURABILITY_UNKNOWN', failure.exception.kind)
            self.journal.root.mkdir(parents=True)
            self.journal.block_path('p1').write_bytes(b'block')
            with self.assertRaisesRegex(WindowError, 'blocked'):
                self.journal.require_verified_index_for_load('p1')
            self.journal.block_path('p1').unlink()
            self.journal._uncertain.add('p1')
            with self.assertRaisesRegex(WindowError, 'uncertain'):
                self.journal.require_verified_index_for_load('p1')

    def test_existing_index_preflight_does_not_replace_full_verification(self):
        with patch.object(evidence, '_sync_file'), patch.object(evidence, '_sync_directory'), \
                patch.object(evidence, '_sync_open_file'), \
                patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        with patch.object(Path, 'read_bytes', side_effect=AssertionError('preflight must not read metadata')):
            self.assertIsNone(self.journal.require_verified_index_for_load('p1'))
            with self.assertRaisesRegex(WindowError, 'No independently verified'):
                self.journal.require_verified_index_for_load('p2')
        self.metadata.write_text(json.dumps({**value, 'extra': 'tampered'}))
        with self.assertRaises(WindowError):
            self.journal.verify_index(self.metadata, value, 'p1')

    def test_existing_index_preflight_uses_publication_lock(self):
        started = threading.Event()
        def read():
            started.set()
            return self.journal.require_verified_index_for_load('p1')
        with ThreadPoolExecutor(max_workers=1) as pool:
            with self.journal._lock:
                reader = pool.submit(read)
                self.assertTrue(started.wait(5))
                self.assertFalse(reader.done())
                self.journal._uncertain.add('p1')
            with self.assertRaisesRegex(WindowError, 'uncertain'):
                reader.result(timeout=5)

    @contextmanager
    def pinned_fixture(self, mode):
        # All fsyncs below are fixture substitutes; no durability is certified.
        with tempfile.TemporaryDirectory(dir=self.root) as tmp, ExitStack() as patches:
            base = Path(tmp)
            journal = evidence.RequestEvidence(base / 'state' / 'run-evidence')
            (base / 'state' / 'projects').mkdir(parents=True)
            metadata = journal.metadata_path('p1')
            patches.enter_context(patch.object(evidence, '_sync_file'))
            patches.enter_context(patch.object(evidence, '_sync_directory'))
            patches.enter_context(patch.object(evidence, '_sync_open_file'))
            state = {'journal': journal, 'metadata': metadata}

            def publish():
                control = RequestControl(30)
                with request_context(control), journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                    state['directory'] = record['directory']
                    journal.observe('fixture', {'value': 'authored-event'})
                    value = {'projectId': 'p1', 'generation': 'a' * 32,
                             'publicationJournal': publication_reference(), 'counts': {'n': 1}}
                    raw = json.dumps(value).encode()
                    pending = base / 'pending.json'
                    pending.write_bytes(raw)
                    stage(lambda: pending.replace(metadata), {'projectId': 'p1', 'generation': 'a' * 32,
                        'metadataSha256': hashlib.sha256(raw).hexdigest(), 'priorMetadataSha256': None})
                    commit_pending(source_frame_verified=True, model_session_verified=True)
                    record['response'] = evidence._save(record['directory'] / 'response.json',
                                                        {'projectId': 'p1', 'ok': True})
                    state['value'] = value
                return value

            if mode == 'recovery':
                with patch.object(journal, '_verify_publication_evidence', return_value=self.proof):
                    publish()
                journal = evidence.RequestEvidence(journal.root)
                state['journal'] = journal
                state['run'] = lambda: journal.recover_index(metadata, state['value'], 'p1')
            else:
                state['run'] = publish
            yield state

    def assert_pinned_rejected(self, state):
        journal = state['journal']
        self.assertTrue(journal.block_path('p1').exists())
        self.assertNotIn('p1', journal._verified)
        self.assertIn('p1', journal._uncertain)

    def pinned_mutation_case(self, mode, name, mutation):
        with self.pinned_fixture(mode) as state:
            entered, proceed = threading.Event(), threading.Event()
            def proof(*args):
                entered.set()
                if not proceed.wait(5):
                    raise TimeoutError('pinned publication fixture barrier')
                return self.proof
            journal = state['journal']
            with patch.object(journal, '_verify_publication_evidence', side_effect=proof), \
                    ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(state['run'])
                try:
                    self.assertTrue(entered.wait(5), 'publication did not reach proof barrier')
                    target = (state['metadata'] if name == 'metadata' else journal.block_path('p1') if name == 'block'
                              else state['directory'] / (name + ('.jsonl' if name == 'events' else '.json')))
                    raw = target.read_bytes()
                    self.assertTrue(raw, name)
                    if mutation == 'replace':
                        replacement = target.with_name(target.name + '.fixture-replacement')
                        replacement.write_bytes(raw)
                        try:
                            replacement.replace(target)
                        except PermissionError:
                            if os.name != 'nt':
                                raise
                            self.skipTest('Windows denied replacement while the verified file was open')
                    else:
                        before = target.stat()
                        try:
                            with target.open('r+b') as stream:
                                stream.write(bytes([raw[0] ^ 1]))
                                stream.flush()
                            os.utime(target, ns=(before.st_atime_ns, before.st_mtime_ns))
                        except PermissionError:
                            if os.name != 'nt':
                                raise
                            self.skipTest('Windows denied in-place write while the verified file was open')
                        self.assertEqual(before.st_ino, target.stat().st_ino)
                finally:
                    proceed.set()
                with self.assertRaises((WindowError, OSError, ValueError)):
                    future.result(timeout=10)
            self.assert_pinned_rejected(state)

    def test_pinned_files_reject_replacement_after_proof(self):
        for mode in ('completion', 'recovery'):
            for name in ('metadata', 'request', 'response', 'events', 'receipt', 'block'):
                with self.subTest(mode=mode, name=name):
                    self.pinned_mutation_case(mode, name, 'replace')

    def test_pinned_files_reject_in_place_change_after_proof(self):
        for mode in ('completion', 'recovery'):
            for name in ('metadata', 'request', 'response', 'events', 'receipt', 'block'):
                with self.subTest(mode=mode, name=name):
                    self.pinned_mutation_case(mode, name, 'in-place')

    def test_success_syncs_the_same_open_files_that_were_verified(self):
        for mode in ('completion', 'recovery'):
            with self.subTest(mode=mode), self.pinned_fixture(mode) as state:
                read_files, synced_files = [], []
                original_read = evidence._PinnedPublication.read
                def remember_read(pinned, path, **kwargs):
                    result = original_read(pinned, path, **kwargs)
                    stream = pinned.files[-1]['stream']
                    read_files.append((id(stream), stream.fileno(), str(path)))
                    return result
                def remember_sync(stream):
                    synced_files.append((id(stream), stream.fileno()))
                with patch.object(state['journal'], '_verify_publication_evidence', return_value=self.proof), \
                        patch.object(evidence._PinnedPublication, 'read', new=remember_read), \
                        patch.object(evidence, '_sync_open_file', side_effect=remember_sync):
                    state['run']()
                self.assertEqual(6, len(read_files))  # Five evidence files and the block.
                self.assertEqual({(identity, fd) for identity, fd, _ in read_files}, set(synced_files))
                self.assertNotIn('p1', state['journal']._uncertain)
                self.assertIn('p1', state['journal']._verified)

    def test_pinned_fsync_and_close_failure_keep_block(self):
        for mode in ('completion', 'recovery'):
            for failure in ('sync', 'close'):
                with self.subTest(mode=mode, failure=failure), self.pinned_fixture(mode) as state:
                    journal = state['journal']
                    proof_reached = []
                    def fake_proof(*args):
                        proof_reached.append(True)
                        return self.proof
                    def fail_after_proof(stream):
                        if proof_reached:
                            raise OSError('fixture fsync failure after proof')
                    with patch.object(journal, '_verify_publication_evidence', side_effect=fake_proof), ExitStack() as failures:
                        if failure == 'sync':
                            failures.enter_context(patch.object(evidence, '_sync_open_file', side_effect=fail_after_proof))
                        else:
                            original_close = evidence._PinnedPublication.close
                            def fail_after_close(pinned):
                                original_close(pinned)
                                raise OSError('fixture close failure')
                            failures.enter_context(patch.object(evidence._PinnedPublication, 'close', new=fail_after_close))
                        with self.assertRaises((WindowError, OSError, ValueError)):
                            state['run']()
                    self.assertEqual([True], proof_reached)
                    self.assert_pinned_rejected(state)

    def test_incomplete_evidence_keeps_publication_blocked(self):
        for mode in ('completion', 'recovery'):
            with self.subTest(mode=mode), self.pinned_fixture(mode) as state:
                with self.assertRaises(WindowError) as caught:
                    state['run']()
                self.assertEqual('INDEX_PUBLICATION_EVIDENCE_UNAVAILABLE', caught.exception.kind)
                self.assert_pinned_rejected(state)

    def test_real_close_failure_attempts_other_closes_and_keeps_block(self):
        for mode in ('completion', 'recovery'):
            with self.subTest(mode=mode), self.pinned_fixture(mode) as state:
                streams = []
                original_read = evidence._PinnedPublication.read
                class CloseFails:
                    def __init__(self, stream): self.stream = stream
                    def __getattr__(self, name): return getattr(self.stream, name)
                    def close(self): raise OSError('fixture underlying close failed')
                def wrapped_read(pinned, *args, **kwargs):
                    value = original_read(pinned, *args, **kwargs)
                    entry = pinned.files[-1]
                    streams.append(entry['stream'])
                    if len(streams) == 1:
                        entry['stream'] = CloseFails(entry['stream'])
                    return value
                try:
                    with patch.object(state['journal'], '_verify_publication_evidence', return_value=self.proof), \
                         patch.object(evidence._PinnedPublication, 'read', new=wrapped_read):
                        with self.assertRaises(WindowError) as caught:
                            state['run']()
                    self.assertEqual('INDEX_PUBLICATION_DURABILITY_UNKNOWN', caught.exception.kind)
                    self.assertEqual(6, len(streams))
                    self.assertTrue(all(stream.closed for stream in streams[1:]))
                    self.assertFalse(streams[0].closed)
                    self.assert_pinned_rejected(state)
                finally:
                    for stream in streams:
                        if not stream.closed: stream.close()

    def test_recovery_decision_visible_but_sync_failure_keeps_block(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with request_context(self.control), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                value = self.staged(record)
                self.complete(record)
        fresh = evidence.RequestEvidence(self.journal.root)
        save = evidence._save
        def fail_decision(path, value, *, durable=False):
            if path.name.startswith('recovery-'):
                save(path, value)
                raise OSError('recovery decision sync failed')
            return save(path, value, durable=durable)
        with patch.object(fresh, '_verify_publication_evidence', return_value=self.proof), \
             patch.object(evidence, '_save', side_effect=fail_decision), \
             self.assertRaisesRegex(WindowError, 'could not be verified'):
            fresh.recover_index(self.metadata, value, 'p1')
        self.assertTrue(list(record['directory'].glob('recovery-*.json')))
        self.assertTrue(fresh.block_path('p1').exists())
        with self.assertRaisesRegex(WindowError, 'blocked'):
            evidence.RequestEvidence(self.journal.root).verify_index(self.metadata, value, 'p1')


if __name__ == '__main__':
    unittest.main()
