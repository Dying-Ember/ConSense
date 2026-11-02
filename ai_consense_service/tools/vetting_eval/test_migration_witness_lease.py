"""Synthetic request-frame lease contracts; no real Server or exclusion proof."""
from contextlib import ExitStack, contextmanager
from contextvars import ContextVar
from types import SimpleNamespace
from threading import Thread
import unittest
from unittest.mock import patch

import migrated_qdrant_index as migration
from token_windows import WindowError, digest


class FakeReader:
    def __init__(self):
        self.receipt_file = {'sha256': 'a' * 64}
        self.receipt = {'target': {'url': 'http://127.0.0.1:28683', 'collection': 'target'},
                        'targetVectorIntegrity': {'pointCount': 2, 'pointsSha256': 'b' * 64}}
        self.metadata = {'projectId': 'project', 'collection': 'target', 'generation': 'gen'}

    def candidate_identity(self, key):
        return (key, self.receipt_file['sha256'], self.receipt['target']['url'],
                self.receipt['target']['collection'], self.metadata['generation'],
                digest(self.receipt['targetVectorIntegrity']))


class FakeLease:
    def __init__(self, expected):
        self.expected = expected
        self.alive = True
        self.calls = []

    def verify(self, binding):
        self.calls.append(binding)
        return self.alive and binding == self.expected


class FakeProvider:
    def __init__(self, *, expected=None, fail_exit=False):
        self.expected = expected
        self.fail_exit = fail_exit
        self.leases = []
        self.bindings = []
        self.closed = []

    @contextmanager
    def acquire(self, binding):
        self.bindings.append(binding)
        lease = FakeLease(binding if self.expected is None else self.expected)
        self.leases.append(lease)
        try:
            yield lease
        finally:
            lease.alive = False
            self.closed.append(lease)
            if self.fail_exit:
                raise RuntimeError('authored release failure')


class MigrationWitnessLeaseTest(unittest.TestCase):
    def setUp(self):
        unknown = patch.object(migration, '_witness_release_unknown', False)
        unknown.start()
        self.addCleanup(unknown.stop)
        self.reader = FakeReader()
        self.backend = SimpleNamespace(_migrated_index=self.reader,
                                       verify_project_publication=lambda key, value: None)
        self.frame = ContextVar('test_migration_witness_frame', default=None)
        self.registry = SimpleNamespace(_validation_frame=self.frame)
        self.binding = self.reader.candidate_identity('project')

    @contextmanager
    def request_frame(self):
        with ExitStack() as lease_stack:
            frame = {'indexMetadata': {}, 'leaseStack': lease_stack}
            token = self.frame.set(frame)
            try:
                yield frame
                for record in frame['indexMetadata'].values():
                    self.assertEqual(record['expected'], record['identity']())
            finally:
                self.frame.reset(token)

    def candidate(self):
        return migration.migration_candidate_for_frame(self.backend, 'project', self.registry)

    def test_missing_provider_rejects_before_constructing_or_reading_reader(self):
        self.backend._migrated_index = None
        with patch.object(migration, '_source_frame_witness_provider', return_value=None, create=True), \
                patch.object(migration, 'MigratedIndex', side_effect=AssertionError('must not construct')) as reader:
            with self.request_frame(), self.assertRaises(WindowError):
                self.candidate()
        reader.assert_not_called()

    def test_same_frame_reuses_one_bound_lease_and_releases_after_exit(self):
        provider = FakeProvider()
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider, create=True):
            with self.request_frame():
                self.assertIs(self.candidate(), self.reader.metadata)
                self.assertIs(self.candidate(), self.reader.metadata)
                self.assertEqual([self.binding], provider.bindings)
                self.assertTrue(provider.leases[0].alive)
            self.assertEqual(provider.leases, provider.closed)
            self.assertGreaterEqual(len(provider.leases[0].calls), 2)
            self.assertTrue(all(call == self.binding for call in provider.leases[0].calls))

    def test_incorrect_bound_identity_is_rejected_and_closed(self):
        for position in range(len(self.binding)):
            wrong = list(self.binding)
            wrong[position] = 'incorrect'
            provider = FakeProvider(expected=tuple(wrong))
            with self.subTest(position=position), \
                    patch.object(migration, '_source_frame_witness_provider', return_value=provider, create=True):
                with self.assertRaises(WindowError):
                    with self.request_frame():
                        self.candidate()
                self.assertEqual(provider.leases, provider.closed)

    def test_expired_lease_rejects_at_frame_exit(self):
        provider = FakeProvider()
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider, create=True):
            with self.assertRaises(WindowError):
                with self.request_frame():
                    self.candidate()
                    provider.leases[0].alive = False
            self.assertEqual(provider.leases, provider.closed)

    def test_truthy_non_boolean_is_not_a_valid_witness(self):
        provider = FakeProvider()
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider, create=True), \
                patch.object(FakeLease, 'verify', return_value=1):
            with self.assertRaises(WindowError):
                with self.request_frame():
                    self.candidate()
            self.assertEqual(provider.leases, provider.closed)

    def test_source_exception_releases_lease_and_is_not_swallowed(self):
        provider = FakeProvider()
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider, create=True):
            with self.assertRaisesRegex(RuntimeError, 'source failure'):
                with self.request_frame():
                    self.candidate()
                    raise RuntimeError('source failure')
            self.assertEqual(provider.leases, provider.closed)

    def test_distinct_frames_get_distinct_leases(self):
        provider = FakeProvider()
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider, create=True):
            with self.request_frame():
                self.candidate()
            with self.request_frame():
                self.candidate()
            self.assertEqual(2, len(provider.leases))
            self.assertIsNot(provider.leases[0], provider.leases[1])
            self.assertEqual(provider.leases, provider.closed)

    def test_release_error_prevents_success(self):
        provider = FakeProvider(fail_exit=True)
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider, create=True):
            with self.assertRaisesRegex(RuntimeError, 'authored release failure'):
                with self.request_frame():
                    self.candidate()
            self.assertEqual(provider.leases, provider.closed)
            with self.assertRaises(WindowError):
                with self.request_frame():
                    self.candidate()
            self.assertEqual(1, len(provider.leases))

    def test_admission_exception_latches_unknown_before_next_attempt(self):
        provider = FakeProvider()
        with patch.object(provider, 'acquire', side_effect=OSError('synthetic admission failure')) as acquire, \
                patch.object(migration, '_source_frame_witness_provider', return_value=provider):
            for _ in range(2):
                with self.assertRaises(WindowError):
                    with self.request_frame():
                        self.candidate()
            self.assertEqual(1, acquire.call_count)

    def test_interrupted_admission_latches_unknown_and_preserves_interrupt(self):
        provider = FakeProvider()
        with patch.object(provider, 'acquire', side_effect=KeyboardInterrupt), \
                patch.object(migration, '_source_frame_witness_provider', return_value=provider):
            with self.assertRaises(KeyboardInterrupt):
                with self.request_frame():
                    self.candidate()
            with self.assertRaises(WindowError):
                with self.request_frame():
                    self.candidate()

    def test_interrupt_after_provider_entry_keeps_unknown_even_if_slot_is_released(self):
        provider = FakeProvider()
        entered = []
        original = migration._acquire_witness
        def interrupted(provider, binding):
            entered.append(original(provider, binding))
            raise KeyboardInterrupt
        with patch.object(migration, '_acquire_witness', side_effect=interrupted), \
                patch.object(migration, '_source_frame_witness_provider', return_value=provider):
            try:
                with self.assertRaises(KeyboardInterrupt):
                    with self.request_frame(): self.candidate()
                self.assertTrue(entered[0][1].alive)
                self.assertTrue(migration._witness_release_unknown)
                with self.assertRaises(WindowError):
                    with self.request_frame(): self.candidate()
                self.assertEqual(1, len(provider.leases))
            finally:
                entered[0][0].__exit__(None, None, None)

    def test_interrupt_after_cleanup_registration_closes_lease_and_releases_slot(self):
        provider = FakeProvider()
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider):
            with patch.object(FakeLease, 'verify', side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    with self.request_frame(): self.candidate()
            self.assertEqual(provider.leases, provider.closed)
            self.assertFalse(migration._witness_release_unknown)
            with self.request_frame(): self.candidate()
            self.assertEqual(provider.leases, provider.closed)

    def test_concurrent_request_fails_without_blocking_current_release(self):
        provider = FakeProvider()
        results = []
        def second_request():
            try:
                with self.request_frame():
                    self.candidate()
            except WindowError:
                results.append('busy')
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider):
            with self.request_frame():
                self.candidate()
                thread = Thread(target=second_request, daemon=True)
                thread.start()
                thread.join(timeout=2)
                self.assertFalse(thread.is_alive())
                self.assertEqual(['busy'], results)
            self.assertEqual(provider.leases, provider.closed)

    def test_busy_request_does_not_rehash_existing_candidate(self):
        provider = FakeProvider()
        results = []
        def second_request():
            try:
                with self.request_frame():
                    self.candidate()
            except BaseException as error:
                results.append(error)
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider):
            with self.request_frame():
                self.candidate()
                with patch.object(self.reader, 'candidate_identity', side_effect=AssertionError('must not rehash')):
                    thread = Thread(target=second_request, daemon=True)
                    thread.start(); thread.join(timeout=2)
                    self.assertFalse(thread.is_alive())
                self.assertEqual(1, len(results))
                self.assertIsInstance(results[0], WindowError)

    def test_busy_request_does_not_construct_cold_reader(self):
        provider = FakeProvider()
        results = []
        def second_request():
            try:
                with self.request_frame():
                    self.candidate()
            except BaseException as error:
                results.append(error)
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider):
            with self.request_frame():
                self.candidate()
                self.backend._migrated_index = None
                try:
                    with patch.object(migration, 'MigratedIndex', side_effect=AssertionError('must not construct')):
                        thread = Thread(target=second_request, daemon=True)
                        thread.start(); thread.join(timeout=2)
                        self.assertFalse(thread.is_alive())
                finally:
                    self.backend._migrated_index = self.reader
                self.assertEqual(1, len(results))
                self.assertIsInstance(results[0], WindowError)

    def test_candidate_failure_releases_slot_without_marking_admission_unknown(self):
        provider = FakeProvider()
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider):
            with patch.object(self.reader, 'candidate_identity', side_effect=ValueError('candidate failure')):
                with self.assertRaisesRegex(ValueError, 'candidate failure'):
                    with self.request_frame(): self.candidate()
            self.assertFalse(migration._witness_release_unknown)
            self.assertEqual([], provider.leases)
            with self.request_frame(): self.candidate()
            self.assertEqual(provider.leases, provider.closed)


if __name__ == '__main__':
    unittest.main()
