"""Exercise the actual frame's ownership of synthetic witness callbacks."""
from contextlib import contextmanager
from contextvars import ContextVar
from types import SimpleNamespace
import unittest

from unified_source_registry import UnifiedCanonicalSourceRegistry


@contextmanager
def session():
    yield


class MigrationWitnessFrameTest(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.registry = SimpleNamespace(
            _validation_frame=ContextVar('test_real_frame', default=None),
            artifact_store=SimpleNamespace(_read_session=ContextVar('test_cas', default=None),
                                           validation_session=session),
            _check_family_context=lambda frame: None,
            _check_frame_files=lambda frame: None,
            _derived_commit=lambda pending: self.events.append('commit'))
        self.guard = lambda: {'software': 'synthetic'}

    def frame(self):
        return UnifiedCanonicalSourceRegistry.validation_frame(self.registry, software_guard=self.guard)

    def attach(self, fail_close=False):
        frame = self.registry._validation_frame.get()
        def close():
            self.events.append('close')
            if fail_close:
                raise RuntimeError('synthetic close failure')
        frame['leaseStack'].callback(close)
        frame['indexMetadata']['synthetic'] = {
            'expected': 'same', 'identity': lambda: 'same',
            'leaseCheck': lambda: self.events.append('check')}
        return frame

    def test_nested_frame_holds_until_outer_commit(self):
        with self.frame():
            frame = self.attach()
            with self.frame():
                self.assertIs(frame, self.registry._validation_frame.get())
            self.assertEqual([], self.events)
        self.assertEqual(['commit', 'check', 'close'], self.events)
        self.assertIsNone(self.registry._validation_frame.get())

    def test_body_failure_closes_without_committing(self):
        with self.assertRaisesRegex(ValueError, 'synthetic body'):
            with self.frame():
                self.attach()
                raise ValueError('synthetic body')
        self.assertEqual(['close'], self.events)
        self.assertIsNone(self.registry._validation_frame.get())

    def test_close_failure_prevents_success_and_clears_frame(self):
        with self.assertRaisesRegex(RuntimeError, 'synthetic close'):
            with self.frame():
                self.attach(fail_close=True)
        self.assertEqual(['commit', 'check', 'close'], self.events)
        self.assertIsNone(self.registry._validation_frame.get())

    def test_body_and_close_failures_are_both_retained(self):
        with self.assertRaisesRegex(RuntimeError, 'synthetic close') as raised:
            with self.frame():
                self.attach(fail_close=True)
                raise ValueError('synthetic body')
        self.assertIsInstance(raised.exception.__cause__, ValueError)
        self.assertEqual('synthetic body', str(raised.exception.__cause__))
        self.assertIsNone(self.registry._validation_frame.get())


if __name__ == '__main__':
    unittest.main()
