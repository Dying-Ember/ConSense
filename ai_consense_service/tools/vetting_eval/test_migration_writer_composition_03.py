"""Synthetic end-to-end journal/02 authority composition; not a Server authorization test."""
import hashlib
import importlib.util
import json
import sqlite3
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from qdrant_writer_authority import Authority, WriterUnavailable, provision
from qdrant_writer_client import target_factory


def synthetic_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


class MigrationWriterCompositionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.local = self.root / 'local'; self.local.mkdir()
        registered = self.root / 'registered'; registered.mkdir()
        self.binding = {'collection': 'synthetic', 'operationId': 'synthetic-op',
                        'serverIdentity': 'synthetic-server'}
        self.path = self.root / 'authority.sqlite'; provision(self.path, self.binding['serverIdentity'])
        self.events = []; self.fail = None; self.bad_target_seal = False
        self.original = {'pointCount': 1, 'dimension': 2, 'pointsSha256': 'a' * 64}
        seal = {'pointCount': 1, 'dimension': 2, 'persistedPointsSha256': 'a' * 64}
        seal['sealSha256'] = synthetic_digest(seal)
        metadata = {'projectId': 'synthetic-project', 'collection': 'synthetic',
                    'signature': {}, 'chunks': [], 'vectorIntegrity': seal}
        file = registered / 'metadata.json'; file.write_text(json.dumps(metadata), encoding='utf-8')
        stat = file.stat()
        desc = {'path': str(file), 'bytes': stat.st_size, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest(),
                'device': stat.st_dev, 'inode': stat.st_ino}
        self.inputs = {'number': 1, 'collection': 'synthetic', 'metadata': desc,
                       'sqlite': {'path': str(registered / 'storage.sqlite')},
                       'qdrantMeta': {'path': str(registered / 'qdrant-meta.json')},
                       'phaseB': {'sealSha256': seal['sealSha256'], 'pointCount': 1, 'dimension': 2,
                                  'matchedRecordedValues': ['persistedPointsSha256']}}
        self.journal = self.root / 'journal'
        self.point = types.SimpleNamespace(id='synthetic-point', vector=[1., 0.], payload={})
        owner = self

        class Source:
            def close(self): owner.events.append('source-close')

        class Client:
            def collection_exists(self, collection):
                owner.events.append(('exists', collection)); return False
            def create_collection(self, collection, *, vectors_config):
                owner.before_write('create_collection', collection)
                return 'synthetic-complete'
            def upsert(self, collection, *, points, wait):
                owner.before_write('upsert', collection)
                owner.assertIs(wait, True); owner.assertEqual(1, len(points))
                if owner.fail == 'upsert': raise TimeoutError('synthetic response lost')
                return 'synthetic-complete'
            def close(self):
                owner.events.append('target-close')
                if owner.fail == 'close': raise OSError('synthetic close failure')

        self.client = Client()
        def require(value, message):
            if not value: raise ValueError(message)
        models = types.SimpleNamespace(VectorParams=lambda **kw: kw, Distance=types.SimpleNamespace(COSINE='cosine'),
                                       PointStruct=lambda **kw: types.SimpleNamespace(**kw))
        self.modules = {
            'qdrant_client': types.SimpleNamespace(models=models, QdrantClient=lambda **kw: Source()),
            'fixed_development_baseline_runtime': types.SimpleNamespace(desc=lambda _: None),
            'index_integrity': types.SimpleNamespace(verify_reuse=self.verify_reuse),
            'migrate_qdrant_server': types.SimpleNamespace(read_points=lambda *args: [[self.point]], require=require),
            'token_windows': types.SimpleNamespace(digest=synthetic_digest),
        }
        self.modules_patch = patch.dict(sys.modules, self.modules); self.modules_patch.start()
        self.addCleanup(self.modules_patch.stop)
        path = Path(__file__).with_name('migrate_sealed_collections.py')
        spec = importlib.util.spec_from_file_location('synthetic_migration_03', path)
        self.migration = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.migration)
        self.migration.verify_runtime_tree = lambda *args: None
        self.migration.ColdLocalReader = lambda *args: Source()

    def query(self, sql):
        with sqlite3.connect(self.path) as db: return db.execute(sql).fetchall()

    def before_write(self, method, collection):
        self.assertEqual('synthetic', collection)
        self.assertEqual('transfer_intent', self.receipt()[-1]['status'])
        self.assertEqual([('INFLIGHT', method, self.binding['operationId'], self.binding['serverIdentity'],
                           self.binding['collection'])],
                         self.query('SELECT state, method, operation_id, server_identity, collection FROM authority'))
        self.assertEqual([(method, 'INFLIGHT')], self.query(
            "SELECT method, status FROM writes WHERE status='INFLIGHT'"))
        self.events.append(method)

    def verify_reuse(self, client, *args, **kwargs):
        if self.bad_target_seal and hasattr(client, '_session'):
            return dict(self.original, pointsSha256='b' * 64)
        return self.original

    def identity(self, binding):
        self.assertEqual(self.binding, binding)
        return binding == self.binding

    def completion(self, binding, write_id, method, args, kwargs, result):
        self.assertEqual(self.binding, binding)
        self.assertEqual([(method, 'INFLIGHT')], self.query(
            "SELECT method, status FROM writes WHERE write_id='%s'" % write_id))
        return (args[0] == binding['collection'] and result == 'synthetic-complete'
                and (method != 'upsert' or kwargs.get('wait') is True))

    def authority(self):
        def client_factory(binding):
            self.assertEqual(self.binding, binding)
            self.assertEqual('transfer_intent', self.receipt()[-1]['status'])
            self.assertEqual([('WRITING', binding['operationId'], binding['serverIdentity'], binding['collection'])],
                             self.query('SELECT state,operation_id,server_identity,collection FROM authority'))
            return self.client
        return Authority(self.path, client_factory, self.identity, self.completion)

    def run_transfer(self):
        factory = target_factory(self.authority(), self.binding)
        return self.migration.transfer_with_journal(self.inputs, self.local, factory, self.journal, 1, 10, 10)

    def receipt(self):
        return [json.loads(row) for row in (self.journal / 'migration-receipt.jsonl').read_text().splitlines()]

    def test_synthetic_success_binds_intents_wait_and_closes_session(self):
        self.run_transfer()
        self.assertEqual(['source-close', 'target-close'], self.events[-2:])
        self.assertEqual([('create_collection', 'COMPLETED'), ('upsert', 'COMPLETED')],
                         self.query('SELECT method,status FROM writes ORDER BY rowid'))
        self.assertEqual([('CLOSED',)], self.query('SELECT status FROM operations'))
        self.assertEqual('completed_candidate_not_adopted', self.receipt()[-1]['status'])

    def test_unknown_write_records_failure_closes_and_blocks_new_writer(self):
        self.fail = 'upsert'
        with self.assertRaises(WriterUnavailable): self.run_transfer()
        self.assertEqual([('UNKNOWN', 'upsert')], self.query('SELECT state,method FROM authority'))
        self.assertEqual([('create_collection', 'COMPLETED'), ('upsert', 'UNKNOWN')],
                         self.query('SELECT method,status FROM writes ORDER BY rowid'))
        self.assertEqual('failed', self.receipt()[-1]['status'])
        self.assertTrue(self.receipt()[-1]['targetMayExist'])
        self.assertEqual(['target'], self.receipt()[-1]['closeFailedClients'])
        self.assertIn('target-close', self.events)
        with self.assertRaises(WriterUnavailable):
            target_factory(self.authority(), dict(self.binding, operationId='another-synthetic-op'))()

    def test_caller_seal_failure_is_not_session_success(self):
        self.bad_target_seal = True
        with self.assertRaisesRegex(ValueError, 'seal differs'): self.run_transfer()
        self.assertEqual([('CLOSED',)], self.query('SELECT status FROM operations'))
        self.assertEqual('failed', self.receipt()[-1]['status'])
        self.assertTrue(self.receipt()[-1]['targetMayExist'])

    def test_close_failure_is_unknown_and_recorded(self):
        self.fail = 'close'
        with self.assertRaises(WriterUnavailable): self.run_transfer()
        self.assertEqual([('UNKNOWN',)], self.query('SELECT state FROM authority'))
        self.assertEqual(['target'], self.receipt()[-1]['closeFailedClients'])
        self.assertTrue(self.receipt()[-1]['targetMayExist'])
        self.assertNotIn('completed_candidate_not_adopted', [r['status'] for r in self.receipt()])


if __name__ == '__main__': unittest.main()
