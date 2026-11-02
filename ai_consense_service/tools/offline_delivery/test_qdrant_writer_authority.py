import sqlite3
import tempfile
import unittest
from pathlib import Path

from qdrant_writer_authority import Authority, WriterUnavailable, provision


class Client:
    def __init__(self, state):
        self.state = state
        self.fail = False

    def create_collection(self, collection, **kwargs):
        self.state.append('called')
        if self.fail:
            raise TimeoutError('response lost')
        return True

    def collection_exists(self, collection):
        return False

    def close(self):
        if self.fail:
            raise OSError('close failed')


class AuthorityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'state.sqlite'
        provision(self.path, 'server-1')
        self.binding = {'collection': 'synthetic', 'operationId': 'op-1', 'serverIdentity': 'server-1'}
        self.events = []
        self.client = Client(self.events)
        self.authority = Authority(self.path, lambda _: self.client, lambda _: True, self.completed)

    def state(self):
        with sqlite3.connect(self.path) as db:
            return db.execute('SELECT state, method FROM authority').fetchone()

    def completed(self, binding, write_id, method, args, kwargs, result):
        self.assertEqual(('INFLIGHT', method), self.state())
        with sqlite3.connect(self.path) as db:
            self.assertEqual((write_id,), db.execute('SELECT write_id FROM writes').fetchone())
        self.assertEqual(('synthetic',), args)
        self.events.append('verified')
        return result is True

    def test_intent_then_completion_and_release(self):
        session = self.authority.open_writer(self.binding)
        self.assertEqual(('WRITING', None), self.state())
        self.assertFalse(session.read('collection_exists', 'synthetic'))
        self.assertTrue(session.write('create_collection', 'synthetic'))
        self.assertEqual(['called', 'verified'], self.events)
        session.close()
        self.assertEqual(('READY', None), self.state())
        with sqlite3.connect(self.path) as db:
            self.assertEqual([('op-1', 'create_collection', 'COMPLETED')],
                             db.execute('SELECT operation_id, method, status FROM writes').fetchall())
            self.assertEqual([('op-1', 'server-1', 'synthetic', 'CLOSED')],
                             db.execute('SELECT operation_id, server_identity, collection, status FROM operations').fetchall())
        self.authority.open_writer({**self.binding, 'operationId': 'op-2'}).close()

    def test_one_owner_and_crash_remains_unavailable(self):
        self.authority.open_writer(self.binding)
        second = Authority(self.path, lambda _: self.client, lambda _: True, self.completed)
        with self.assertRaises(WriterUnavailable):
            second.open_writer(self.binding)
        self.assertEqual(('WRITING', None), self.state())

    def test_operation_id_cannot_be_reused(self):
        self.authority.open_writer(self.binding).close()
        with self.assertRaises(WriterUnavailable):
            self.authority.open_writer(self.binding)
        self.assertEqual(('READY', None), self.state())

    def test_server_binding_is_required_before_writer_admission(self):
        with self.assertRaises(WriterUnavailable):
            self.authority.open_writer({**self.binding, 'serverIdentity': 'another-server'})
        self.assertEqual(('READY', None), self.state())
        with sqlite3.connect(self.path) as db:
            db.execute('DELETE FROM server_binding')
        with self.assertRaises(WriterUnavailable):
            self.authority.open_writer(self.binding)
        self.assertEqual(('READY', None), self.state())

    def test_server_binding_drift_during_session_latches_unknown(self):
        session = self.authority.open_writer(self.binding)
        with sqlite3.connect(self.path) as db:
            db.execute("UPDATE server_binding SET server_identity='another-server'")
        with self.assertRaises(WriterUnavailable):
            session.write('create_collection', 'synthetic')
        self.assertEqual(('UNKNOWN', None), self.state())
        self.assertEqual([], self.events)

    def test_legacy_ledger_missing_binding_is_not_adopted(self):
        with sqlite3.connect(self.path) as db:
            db.execute('DROP TABLE server_binding')
        with self.assertRaises(WriterUnavailable):
            self.authority.open_writer(self.binding)
        self.assertEqual(('READY', None), self.state())
        self.assertEqual([], self.events)

    def test_removed_ledger_is_never_recreated_during_open(self):
        self.path.unlink()
        with self.assertRaises(WriterUnavailable):
            self.authority.open_writer(self.binding)
        self.assertFalse(self.path.exists())
        self.assertEqual([], self.events)

    def test_lost_write_response_latches_unknown_across_new_authority(self):
        session = self.authority.open_writer(self.binding)
        self.client.fail = True
        with self.assertRaises(WriterUnavailable):
            session.write('create_collection', 'synthetic')
        self.assertEqual(('UNKNOWN', 'create_collection'), self.state())
        with sqlite3.connect(self.path) as db:
            self.assertEqual([('op-1', 'create_collection', 'UNKNOWN')],
                             db.execute('SELECT operation_id, method, status FROM writes').fetchall())
        with self.assertRaises(WriterUnavailable):
            Authority(self.path, lambda _: self.client, lambda _: True, self.completed).open_writer(self.binding)
        with self.assertRaises(WriterUnavailable):
            session.read('collection_exists', 'synthetic')
        self.assertEqual(['called'], self.events)

    def test_unverified_completion_and_close_failure_latch_unknown(self):
        self.authority.completion_check = lambda *_: False
        session = self.authority.open_writer(self.binding)
        with self.assertRaises(WriterUnavailable):
            session.write('create_collection', 'synthetic')
        self.assertEqual(('UNKNOWN', 'create_collection'), self.state())

    def test_wrong_collection_and_nonwaiting_write_never_reach_client(self):
        session = self.authority.open_writer(self.binding)
        with self.assertRaises(WriterUnavailable):
            session.write('create_collection', 'another')
        self.assertEqual([], self.events)
        self.assertEqual(('UNKNOWN', None), self.state())

    def test_catalogue_does_not_accept_arguments_or_bypass_binding(self):
        session = self.authority.open_writer(self.binding)
        with self.assertRaises(WriterUnavailable):
            session.read('get_collections', 'another')
        self.assertEqual([], self.events)
        self.assertEqual(('UNKNOWN', None), self.state())

    def test_close_failure_latches_unknown(self):
        session = self.authority.open_writer(self.binding)
        self.client.fail = True
        with self.assertRaises(WriterUnavailable):
            session.close()
        self.assertEqual(('UNKNOWN', None), self.state())

    def test_callbacks_cannot_mutate_bound_collection(self):
        def mutate(binding):
            binding['collection'] = 'another'
            return True
        self.authority.identity_check = mutate
        session = self.authority.open_writer(self.binding)
        with self.assertRaises(WriterUnavailable):
            session.write('create_collection', 'another')
        self.assertEqual([], self.events)
        self.assertEqual(('UNKNOWN', None), self.state())

    def test_identity_failure_latches_unknown(self):
        self.authority.identity_check = lambda _: False
        with self.assertRaises(WriterUnavailable):
            self.authority.open_writer(self.binding)
        self.assertEqual(('UNKNOWN', None), self.state())

    def test_binding_drift_during_write_latches_unknown(self):
        def change_binding(collection, **kwargs):
            with sqlite3.connect(self.path) as db:
                db.execute("UPDATE authority SET collection='another' WHERE id=1")
            return True
        self.client.create_collection = change_binding
        session = self.authority.open_writer(self.binding)
        with self.assertRaises(WriterUnavailable):
            session.write('create_collection', 'synthetic')
        self.assertEqual(('UNKNOWN', 'create_collection'), self.state())
        with sqlite3.connect(self.path) as db:
            self.assertEqual([('UNKNOWN',)], db.execute('SELECT status FROM writes').fetchall())

    def test_operation_history_drift_during_write_latches_unknown(self):
        def change_history(collection, **kwargs):
            with sqlite3.connect(self.path) as db:
                db.execute("UPDATE operations SET collection='another' WHERE operation_id='op-1'")
            return True
        self.client.create_collection = change_history
        session = self.authority.open_writer(self.binding)
        with self.assertRaises(WriterUnavailable):
            session.write('create_collection', 'synthetic')
        self.assertEqual(('UNKNOWN', 'create_collection'), self.state())
        with sqlite3.connect(self.path) as db:
            self.assertEqual([('UNKNOWN',)], db.execute('SELECT status FROM writes').fetchall())


if __name__ == '__main__':
    unittest.main()
