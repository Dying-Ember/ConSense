"""A registry must not claim an unbound cached Qdrant client."""
import importlib.util
from pathlib import Path
import sys
from threading import Event, Thread, current_thread
import traceback
import unittest
from unittest.mock import Mock, patch

from migration_project_registry import REGISTRY
from migrated_qdrant_index import ENV
from token_windows import WindowError


class RegistryBackendIdentityTest(unittest.TestCase):
    def setUp(self):
        name = 'registry_backend_identity_test_server'
        spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name('server.py'))
        self.server = importlib.util.module_from_spec(spec)
        sys.modules[name] = self.server
        self.addCleanup(sys.modules.pop, name, None)
        spec.loader.exec_module(self.server)

    def test_registry_refuses_unbound_cached_client_without_connecting(self):
        for name in (REGISTRY, REGISTRY + '_SHA256'):
            for value in ('', 'authored'):
                with self.subTest(name=name, value=value), patch.dict(self.server.os.environ, {name: value}, clear=True), \
                     patch('qdrant_backend.connect') as connect:
                    self.server._database = object()
                    self.server._database_configuration = None
                    with self.assertRaises(WindowError) as error:
                        self.server.database()
                    self.assertEqual('QDRANT_BACKEND_CONFIG_CHANGED', error.exception.kind)
                    connect.assert_not_called()

    def test_legacy_refuses_unbound_client_and_unconfigured_keeps_existing_behavior(self):
        self.server._database = object()
        self.server._database_configuration = None
        with patch.dict(self.server.os.environ, {ENV: 'authored'}, clear=True):
            with self.assertRaises(WindowError):
                self.server.database()
        with patch.dict(self.server.os.environ, {}, clear=True):
            self.assertIs(self.server._database, self.server.database())

    def test_bound_client_checks_server_url_and_credential_change(self):
        client = object()
        env = {REGISTRY: 'authored', 'CONSENSE_QDRANT_URL': 'http://fixture.invalid',
               'CONSENSE_QDRANT_API_KEY': 'authored-key'}
        with patch.dict(self.server.os.environ, env, clear=True), \
             patch('qdrant_backend.connect', return_value=client) as connect:
            self.assertIs(client, self.server.database())
            self.assertIs(client, self.server.database())
            for name in ('CONSENSE_QDRANT_URL', 'CONSENSE_QDRANT_API_KEY'):
                old = self.server.os.environ[name]
                self.server.os.environ[name] = 'changed'
                for _ in range(2):
                    with self.assertRaises(WindowError):
                        self.server.database()
                self.server.os.environ[name] = old
                self.assertIs(client, self.server.database())
            connect.assert_called_once()

    def test_changed_backend_or_route_during_connect_closes_unpublished_client(self):
        values = {'CONSENSE_QDRANT_URL': 'http://fixture.invalid', 'CONSENSE_QDRANT_API_KEY': 'authored-key'}
        for field in ('url', 'key', 'state', 'registry', 'legacy'):
            with self.subTest(field=field), patch.dict(self.server.os.environ, values, clear=True):
                self.server._database = None
                self.server._database_configuration = None
                original_state = self.server.STATE
                client = Mock()
                def connect(state, environ):
                    self.assertEqual(original_state, state)
                    self.assertEqual(values['CONSENSE_QDRANT_URL'], environ['CONSENSE_QDRANT_URL'])
                    self.assertEqual(values['CONSENSE_QDRANT_API_KEY'], environ['CONSENSE_QDRANT_API_KEY'])
                    if field == 'url': self.server.os.environ['CONSENSE_QDRANT_URL'] = 'changed'
                    elif field == 'key': self.server.os.environ['CONSENSE_QDRANT_API_KEY'] = 'changed'
                    elif field == 'state': self.server.STATE = original_state / 'changed'
                    elif field == 'registry': self.server.os.environ[REGISTRY] = 'authored'
                    else: self.server.os.environ[ENV] = 'authored'
                    return client
                try:
                    with patch('qdrant_backend.connect', side_effect=connect):
                        with self.assertRaises(WindowError): self.server.database()
                    self.assertIsNone(self.server._database)
                    self.assertIsNone(self.server._database_configuration)
                    client.close.assert_called_once_with()
                finally:
                    self.server.STATE = original_state

    def test_failed_close_latches_unknown_without_exposing_error(self):
        client = Mock()
        client.close.side_effect = RuntimeError('authored-secret')
        def connect(_state, _environ):
            self.server.os.environ[REGISTRY] = 'authored'
            return client
        with patch.dict(self.server.os.environ, {}, clear=True), patch('qdrant_backend.connect', side_effect=connect) as factory:
            with self.assertRaises(WindowError) as first: self.server.database()
            self.assertEqual('QDRANT_BACKEND_RELEASE_UNKNOWN', first.exception.kind)
            self.assertNotIn('authored-secret', str(first.exception))
            with self.assertRaises(WindowError) as second: self.server.database()
            self.assertEqual('QDRANT_BACKEND_RELEASE_UNKNOWN', second.exception.kind)
            self.assertIsNone(self.server._database)
            factory.assert_called_once()

    def test_connect_exception_is_sanitized_and_interrupt_propagates(self):
        for exception in (RuntimeError('authored-secret'), OSError('authored-secret')):
            with self.subTest(type=type(exception).__name__), patch.dict(self.server.os.environ, {}, clear=True), \
                 patch('qdrant_backend.connect', side_effect=exception):
                with self.assertRaises(WindowError) as failure: self.server.database()
                self.assertEqual('QDRANT_BACKEND_UNAVAILABLE', failure.exception.kind)
                self.assertNotIn('authored-secret', ''.join(traceback.format_exception(failure.exception)))
                self.assertIsNone(self.server._database)
                self.assertFalse(self.server._database_release_unknown)
        with patch.dict(self.server.os.environ, {}, clear=True), patch('qdrant_backend.connect', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt): self.server.database()
            self.assertFalse(self.server._database_release_unknown)

    def test_real_index_entry_returns_503_without_connect_exception_text(self):
        import test_window_server as fixtures
        fixture = fixtures.ServerTests('runTest')
        fixture.setUp()
        try:
            self.server.STATE = Path(fixture.tmp.name)
            with patch.object(fixtures.s, 'database', self.server.database), \
                 patch('qdrant_backend.connect', side_effect=RuntimeError('authored-secret')):
                with self.assertRaises(fixtures.s.HTTPException) as failure: fixture.index()
            self.assertEqual(503, failure.exception.status_code)
            self.assertEqual('QDRANT_BACKEND_UNAVAILABLE', failure.exception.detail['kind'])
            self.assertNotIn('authored-secret', str(failure.exception.detail))
            self.assertIsNone(self.server._database)
        finally:
            fixture.tearDown()
            fixture.doCleanups()

    def test_recheck_exception_closes_client_and_failed_close_latches_unknown(self):
        class ChangingEnv(dict):
            fail = False
            def __contains__(self, name):
                if self.fail: raise KeyError('authored-secret')
                return super().__contains__(name)
        for close_failure in (False, True):
            with self.subTest(close_failure=close_failure):
                self.server._database = None
                self.server._database_configuration = None
                self.server._database_release_unknown = False
                environ = ChangingEnv()
                client = Mock()
                if close_failure: client.close.side_effect = RuntimeError('authored-secret')
                def connect(_state, _snapshot):
                    environ.fail = True
                    return client
                with patch.object(self.server.os, 'environ', environ), patch('qdrant_backend.connect', side_effect=connect) as factory:
                    with self.assertRaises(WindowError) as failure: self.server.database()
                    self.assertEqual('QDRANT_BACKEND_RELEASE_UNKNOWN' if close_failure else 'QDRANT_BACKEND_CONFIG_CHANGED',
                                     failure.exception.kind)
                    self.assertNotIn('authored-secret', str(failure.exception))
                    self.assertIsNone(self.server._database)
                    self.assertIsNone(self.server._database_configuration)
                    client.close.assert_called_once_with()
                    if close_failure:
                        environ.fail = False
                        with self.assertRaises(WindowError) as again: self.server.database()
                        self.assertEqual('QDRANT_BACKEND_RELEASE_UNKNOWN', again.exception.kind)
                    factory.assert_called_once()

    def test_concurrent_first_connect_constructs_one_client(self):
        started, release, second_start, attempted = Event(), Event(), Event(), Event()
        client, calls, results, errors = Mock(), [], [], []
        lock = self.server.LOCK
        class ObservedLock:
            def __enter__(self):
                if current_thread().name == 'second':
                    if not lock.acquire(blocking=False):
                        attempted.set()
                        lock.acquire()
                else:
                    lock.acquire()
                return self
            def __exit__(self, *_args):
                lock.release()
        def connect(_state, _environ):
            calls.append(1)
            if len(calls) == 1:
                started.set()
                if not release.wait(2): raise AssertionError('first connection timed out')
            return client
        def run(second=False):
            if second: second_start.set()
            try: results.append(self.server.database())
            except BaseException as error: errors.append(error)
        with patch.dict(self.server.os.environ, {}, clear=True), patch.object(self.server, 'LOCK', ObservedLock()), \
             patch('qdrant_backend.connect', side_effect=connect):
            first = Thread(target=run)
            second = Thread(target=lambda: run(True), name='second')
            first.start()
            try:
                self.assertTrue(started.wait(2))
                second.start()
                self.assertTrue(second_start.wait(2))
                self.assertTrue(attempted.wait(1))
            finally:
                release.set()
                first.join(2)
                if second.ident is not None: second.join(2)
            self.assertFalse(first.is_alive())
            self.assertFalse(second.is_alive())
            self.assertEqual([], errors)
            self.assertEqual([client, client], results)
            self.assertEqual(1, len(calls))


if __name__ == '__main__':
    unittest.main()
