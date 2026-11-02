"""Use 03's actual project-storage preflight with a synthetic writer session."""
import os
import sqlite3
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from qdrant_writer_authority import Authority, WriterUnavailable, provision
from qdrant_writer_client import target_factory

VETTING = str(Path(__file__).resolve().parent.parent / 'vetting_eval')
sys.path.insert(0, VETTING)
import window_runtime
from token_windows import WindowError, digest


class SyntheticClient:
    def __init__(self):
        self.collections = []
        self.aliases = []
        self.events = []
        self.fail_catalogue = False

    def get_collections(self):
        self.events.append('get_collections')
        if self.fail_catalogue:
            raise RuntimeError('SYNTHETIC_SECRET')
        return types.SimpleNamespace(collections=self.collections)

    def get_aliases(self):
        self.events.append('get_aliases')
        return types.SimpleNamespace(aliases=self.aliases)

    def create_collection(self, collection, *, vectors_config):
        self.events.append('create')
        return True

    def upsert(self, collection, *, points, wait):
        self.events.append('upsert')
        return True

    def close(self):
        self.events.append('close')


class WindowPreflightIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env = patch.dict(os.environ, {'CONSENSE_QDRANT_URL': 'http://synthetic.invalid',
                                           'CONSENSE_QDRANT_API_KEY': 'synthetic-test-key'})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.path = Path(self.temp.name) / 'authority.sqlite'
        provision(self.path, 'synthetic-server')
        self.client = SyntheticClient()
        self.key = 'synthetic-project'
        self.prefix = 'project_' + digest(self.key)[:16] + '_'
        self.binding = {'collection': self.prefix + 'synthetic', 'operationId': 'op-1',
                        'serverIdentity': 'synthetic-server'}
        self.authority = Authority(self.path, lambda _: self.client, lambda _: True,
                                   lambda binding, write_id, method, args, kwargs, result: result is True)
        self.target = target_factory(self.authority, self.binding)()
        identity = (self.temp.name, os.environ['CONSENSE_QDRANT_URL'], os.environ['CONSENSE_QDRANT_API_KEY'])
        self.server = types.SimpleNamespace(STATE=self.temp.name, _database=self.target,
                                             _database_binding=(self.target, identity),
                                             _database_configuration=identity, digest=digest,
                                             database=lambda: self.target)

    def state(self):
        with sqlite3.connect(self.path) as db:
            return db.execute('SELECT state FROM authority').fetchone()[0]

    def test_actual_preflight_empty_then_create_upsert(self):
        self.assertIs(window_runtime.require_empty_project_storage(self.server, self.key), self.target)
        self.assertTrue(self.target.create_collection(self.binding['collection'], vectors_config='synthetic'))
        self.assertTrue(self.target.upsert(self.binding['collection'], points=['synthetic'], wait=True))
        self.target.close()
        self.assertEqual(self.client.events, ['get_collections', 'get_aliases', 'create', 'upsert', 'close'])

    def test_actual_preflight_rejects_same_prefix_collection_or_alias_before_write(self):
        for kind in ('collections', 'aliases'):
            with self.subTest(kind=kind):
                self.client.collections = ([types.SimpleNamespace(name=self.prefix + 'old')]
                                           if kind == 'collections' else [])
                self.client.aliases = ([types.SimpleNamespace(alias_name=self.prefix + 'old')]
                                       if kind == 'aliases' else [])
                with self.assertRaises(WindowError) as error:
                    window_runtime.require_empty_project_storage(self.server, self.key)
                self.assertEqual(error.exception.kind, 'INDEX_INITIALIZATION_UNAVAILABLE')
        self.assertNotIn('create', self.client.events)
        self.assertNotIn('upsert', self.client.events)
        self.target.close()

    def test_actual_preflight_accepts_unrelated_prefix_not_whole_server_emptiness(self):
        self.client.collections = [types.SimpleNamespace(name='project_other_existing')]
        self.client.aliases = [types.SimpleNamespace(alias_name='project_other_alias')]
        self.assertIs(window_runtime.require_empty_project_storage(self.server, self.key), self.target)
        self.assertTrue(self.client.collections and self.client.aliases)
        self.target.close()

    def test_read_error_latches_unknown_and_does_not_write(self):
        self.client.fail_catalogue = True
        with self.assertRaises(WindowError) as error:
            window_runtime.require_empty_project_storage(self.server, self.key)
        self.assertEqual(error.exception.kind, 'QDRANT_BACKEND_UNAVAILABLE')
        self.assertNotIn('SYNTHETIC_SECRET', str(error.exception))
        self.assertEqual(self.state(), 'UNKNOWN')
        with self.assertRaises(WriterUnavailable):
            target_factory(self.authority, {**self.binding, 'operationId': 'later'})()
        self.assertNotIn('create', self.client.events)
        self.assertNotIn('upsert', self.client.events)

    def test_preflight_requires_same_bound_database_object(self):
        self.server._database_binding = (object(), self.server._database_configuration)
        with self.assertRaises(WindowError) as error:
            window_runtime.require_empty_project_storage(self.server, self.key)
        self.assertEqual(error.exception.kind, 'INDEX_INITIALIZATION_UNAVAILABLE')
        self.assertEqual(self.client.events, [])
        self.target.close()


if __name__ == '__main__':
    unittest.main()
