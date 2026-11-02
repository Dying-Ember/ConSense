import sqlite3
import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from qdrant_writer_authority import Authority, WriterUnavailable, provision
from qdrant_writer_client import target_factory


class SyntheticClient:
    def __init__(self, state):
        self.state = state
        self.fail_write = False
        self.collections = []
        self.aliases = []

    def get_collections(self):
        self.state.append(('get_collections',))
        return types.SimpleNamespace(collections=self.collections)

    def get_aliases(self):
        self.state.append(('get_aliases',))
        return types.SimpleNamespace(aliases=self.aliases)

    def collection_exists(self, collection):
        self.state.append(('read', collection))
        return False

    def create_collection(self, collection, *, vectors_config):
        self.state.append(('create', collection, vectors_config))
        return True

    def upsert(self, collection, *, points, wait):
        self.state.append(('upsert', collection, wait))
        if self.fail_write:
            raise TimeoutError('SYNTHETIC_SECRET response lost')
        return True

    def close(self):
        self.state.append(('close',))


class WriterIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'authority.sqlite'
        provision(self.path, 'server-1')
        self.binding = {'collection': 'synthetic', 'operationId': 'op-1', 'serverIdentity': 'server-1'}
        self.events = []
        self.client = SyntheticClient(self.events)
        self.authority = self.new_authority()

    def state(self):
        with sqlite3.connect(self.path) as db:
            return db.execute('SELECT state, method FROM authority').fetchone()

    def completed(self, binding, write_id, method, args, kwargs, result):
        self.assertEqual(('INFLIGHT', method), self.state())
        with sqlite3.connect(self.path) as db:
            self.assertEqual((write_id, 'INFLIGHT'), db.execute('SELECT write_id, status FROM writes WHERE write_id=?',
                                                               (write_id,)).fetchone())
        self.assertEqual(args[0], 'synthetic')
        if method == 'upsert':
            self.assertEqual(kwargs['points'], ['synthetic-point'])
            self.assertIs(kwargs['wait'], True)
        self.events.append(('verified', method))
        return result is True

    def new_authority(self):
        return Authority(self.path, lambda _: self.client, lambda _: True, self.completed)

    def test_factory_read_create_upsert_close(self):
        target = target_factory(self.authority, self.binding)()
        self.assertFalse(target.collection_exists('synthetic'))
        self.assertTrue(target.create_collection('synthetic', vectors_config='synthetic-config'))
        self.assertTrue(target.upsert('synthetic', points=['synthetic-point'], wait=True))
        target.close()
        self.assertEqual(('READY', None), self.state())
        self.assertEqual(self.events, [('read', 'synthetic'), ('create', 'synthetic', 'synthetic-config'),
            ('verified', 'create_collection'), ('upsert', 'synthetic', True), ('verified', 'upsert'), ('close',)])

    def test_empty_synthetic_initialization_under_one_writer_session(self):
        target = target_factory(self.authority, self.binding)()
        collections = target.get_collections().collections
        aliases = target.get_aliases().aliases
        self.assertEqual(collections, [])
        self.assertEqual(aliases, [])
        self.assertFalse(target.collection_exists('synthetic'))
        with self.assertRaises(WriterUnavailable):
            target_factory(self.new_authority(), {**self.binding, 'operationId': 'concurrent'})()
        self.assertTrue(target.create_collection('synthetic', vectors_config='synthetic-config'))
        self.assertTrue(target.upsert('synthetic', points=['synthetic-point'], wait=True))
        target.close()
        self.assertEqual([event[0] for event in self.events],
                         ['get_collections', 'get_aliases', 'read', 'create', 'verified', 'upsert', 'verified', 'close'])

    def test_caller_preflight_rejects_existing_synthetic_collection_or_alias(self):
        for kind in ('collections', 'aliases'):
            with self.subTest(kind=kind):
                if kind == 'collections':
                    self.client.collections = [types.SimpleNamespace(name='project_test_existing')]
                else:
                    self.client.collections = []
                    self.client.aliases = [types.SimpleNamespace(alias_name='project_test_existing')]
                binding = {**self.binding, 'operationId': 'reject-' + kind}
                target = target_factory(self.authority, binding)()
                collections = target.get_collections().collections
                aliases = target.get_aliases().aliases
                self.assertTrue(collections or aliases)
                target.close()
        self.assertFalse(any(event[0] in ('create', 'upsert') for event in self.events))

    def test_catalogue_read_failure_latches_unknown(self):
        def failing_catalogue():
            raise RuntimeError('SYNTHETIC_SECRET')
        self.client.get_collections = failing_catalogue
        target = target_factory(self.authority, self.binding)()
        with self.assertRaises(WriterUnavailable) as error:
            target.get_collections()
        self.assertNotIn('SYNTHETIC_SECRET', str(error.exception))
        self.assertEqual(('UNKNOWN', None), self.state())
        with self.assertRaises(WriterUnavailable):
            target_factory(self.new_authority(), {**self.binding, 'operationId': 'later'})()

    def test_uncertain_write_blocks_restarted_authority(self):
        target = target_factory(self.authority, self.binding)()
        self.client.fail_write = True
        with self.assertRaises(WriterUnavailable) as error:
            target.upsert('synthetic', points=['synthetic-point'], wait=True)
        self.assertNotIn('SYNTHETIC_SECRET', str(error.exception))
        self.assertEqual(('UNKNOWN', 'upsert'), self.state())
        with self.assertRaises(WriterUnavailable):
            target_factory(self.new_authority(), self.binding)()
        self.assertEqual([event[0] for event in self.events], ['upsert'])

    def test_crashed_open_session_blocks_restarted_authority(self):
        target_factory(self.authority, self.binding)()
        self.assertEqual(('WRITING', None), self.state())
        with self.assertRaises(WriterUnavailable):
            target_factory(self.new_authority(), self.binding)()

    def test_close_does_not_claim_migration_success_after_caller_failure(self):
        target = target_factory(self.authority, self.binding)()
        with self.assertRaisesRegex(ValueError, 'synthetic seal failure'):
            try:
                target.create_collection('synthetic', vectors_config='synthetic-config')
                raise ValueError('synthetic seal failure')
            finally:
                target.close()
        with sqlite3.connect(self.path) as db:
            self.assertEqual(('CLOSED',), db.execute('SELECT status FROM operations').fetchone())
            self.assertEqual(('COMPLETED',), db.execute('SELECT status FROM writes').fetchone())

    def test_current_migration_caller_uses_adapter(self):
        def require(condition, message):
            if not condition:
                raise ValueError(message)
        point = types.SimpleNamespace(id='point-1', vector=[1.0, 0.0], payload={'synthetic': True})
        seal = {'dimension': 2, 'pointCount': 1}
        modules = {
            'qdrant_client': types.SimpleNamespace(models=types.SimpleNamespace(
                VectorParams=lambda **kw: kw, Distance=types.SimpleNamespace(COSINE='cosine'),
                PointStruct=lambda **kw: types.SimpleNamespace(**kw))),
            'fixed_development_baseline_runtime': types.SimpleNamespace(desc=lambda _: None),
            'index_integrity': types.SimpleNamespace(verify_reuse=lambda *a, **kw: seal),
            'migrate_qdrant_server': types.SimpleNamespace(read_points=lambda *a: [[point]], require=require),
            'token_windows': types.SimpleNamespace(digest=lambda _: 'synthetic'),
        }
        caller_path = Path(__file__).resolve().parent.parent / 'vetting_eval' / 'migrate_sealed_collections.py'
        spec = importlib.util.spec_from_file_location('synthetic_migrate_sealed_collections', caller_path)
        caller = importlib.util.module_from_spec(spec)
        self.authority.completion_check = lambda binding, write_id, method, args, kwargs, result: result is True
        with patch.dict(sys.modules, modules):
            spec.loader.exec_module(caller)
            target = target_factory(self.authority, self.binding)()
            metadata = {'projectId': 'project', 'collection': 'synthetic', 'chunks': ['chunk'], 'signature': {}}
            result = caller.migrate_collection(object(), target, metadata, 1)
            target.close()
        self.assertEqual(result, {'source': seal, 'target': seal})
        self.assertEqual([event[0] for event in self.events], ['read', 'create', 'upsert', 'close'])


if __name__ == '__main__':
    unittest.main()
