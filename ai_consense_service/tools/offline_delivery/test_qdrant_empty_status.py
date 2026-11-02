import hashlib
import sqlite3
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from qdrant_empty_status import StatusUnavailable, inspect_empty_catalogue
from qdrant_writer_authority import Authority, provision


class ReadClient:
    def __init__(self):
        self.collections = []
        self.aliases = []
        self.calls = []

    def get_collections(self):
        self.calls.append('get_collections')
        return types.SimpleNamespace(collections=self.collections)

    def get_aliases(self):
        self.calls.append('get_aliases')
        return types.SimpleNamespace(aliases=self.aliases)

    def close(self):
        self.calls.append('close')


class EmptyStatusTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'authority.sqlite'
        provision(self.path, 'synthetic-server')
        self.prefix = 'project_0123456789abcdef_'
        self.client = ReadClient()
        self.factory_calls = []
        self.factory = lambda identity: (self.factory_calls.append(identity) or self.client)
        self.identity = lambda value: value == 'synthetic-server'

    def status(self):
        return inspect_empty_catalogue(self.path, 'synthetic-server', self.prefix, self.factory, self.identity)

    def test_empty_catalogue_is_read_only_not_authorization(self):
        before = hashlib.sha256(self.path.read_bytes()).digest()
        self.assertEqual(self.status(), {'serverEmpty': True, 'projectClear': True,
                                         'writesAttempted': False, 'authorizesWrite': False})
        self.assertEqual(before, hashlib.sha256(self.path.read_bytes()).digest())
        self.assertEqual(self.client.calls, ['get_collections', 'get_aliases', 'close'])

    def test_prefix_and_entire_server_are_distinct_without_returning_names(self):
        self.client.collections = [types.SimpleNamespace(name='project_other')]
        self.assertEqual(self.status()['projectClear'], True)
        self.assertEqual(self.status()['serverEmpty'], False)
        self.client.aliases = [types.SimpleNamespace(alias_name=self.prefix + 'synthetic')]
        self.assertEqual(self.status()['projectClear'], False)

    def test_unresolved_or_busy_ledger_never_opens_client(self):
        authority = Authority(self.path, lambda _: self.client, lambda _: True, lambda *_: True)
        authority.open_writer({'collection': 'synthetic', 'operationId': 'busy', 'serverIdentity': 'synthetic-server'})
        with self.assertRaises(StatusUnavailable):
            self.status()
        self.assertEqual(self.factory_calls, [])

    def test_ready_but_unresolved_history_is_rejected_without_mutation(self):
        with sqlite3.connect(self.path) as db:
            db.execute("INSERT INTO operations VALUES ('op', 'synthetic-server', 'synthetic', 'UNKNOWN')")
        before = hashlib.sha256(self.path.read_bytes()).digest()
        with self.assertRaises(StatusUnavailable):
            self.status()
        self.assertEqual(before, hashlib.sha256(self.path.read_bytes()).digest())
        self.assertEqual(self.factory_calls, [])

    def test_unknown_state_is_rejected_without_reset(self):
        with sqlite3.connect(self.path) as db:
            db.execute("UPDATE authority SET state='UNKNOWN' WHERE id=1")
        with self.assertRaises(StatusUnavailable):
            self.status()
        with sqlite3.connect(self.path) as db:
            self.assertEqual(db.execute('SELECT state FROM authority').fetchone(), ('UNKNOWN',))
        self.assertEqual(self.factory_calls, [])

    def test_blank_or_different_server_binding_is_rejected_before_client(self):
        with sqlite3.connect(self.path) as db:
            db.execute("UPDATE server_binding SET server_identity='different-server'")
        with self.assertRaises(StatusUnavailable):
            self.status()
        with sqlite3.connect(self.path) as db:
            db.execute('DELETE FROM server_binding')
        with self.assertRaises(StatusUnavailable):
            self.status()
        self.assertEqual(self.factory_calls, [])

    def test_legacy_ledger_without_binding_is_not_adopted(self):
        with sqlite3.connect(self.path) as db:
            db.execute('DROP TABLE server_binding')
        with self.assertRaises(StatusUnavailable):
            self.status()
        self.assertEqual(self.factory_calls, [])

    def test_identity_or_read_failure_is_sanitized_and_closes(self):
        def bad_aliases():
            raise ValueError('SYNTHETIC_SECRET')
        self.client.get_aliases = bad_aliases
        with self.assertRaises(StatusUnavailable) as error:
            self.status()
        self.assertNotIn('SYNTHETIC_SECRET', str(error.exception))
        self.assertEqual(self.client.calls, ['get_collections', 'close'])

    def test_identity_change_after_read_discards_observation(self):
        checks = []
        self.identity = lambda _: (checks.append(1) or len(checks) == 1)
        with self.assertRaises(StatusUnavailable):
            self.status()
        self.assertEqual(self.client.calls, ['get_collections', 'get_aliases', 'close'])

    def test_writer_cycle_during_observation_is_detected(self):
        writer_client = ReadClient()
        authority = Authority(self.path, lambda _: writer_client, lambda _: True, lambda *_: True)
        def racing_aliases():
            authority.open_writer({'collection': 'synthetic', 'operationId': 'new',
                                   'serverIdentity': 'synthetic-server'}).close()
            return types.SimpleNamespace(aliases=[])
        self.client.get_aliases = racing_aliases
        with self.assertRaises(StatusUnavailable):
            self.status()
        self.assertEqual(self.client.calls, ['get_collections', 'close'])

    def test_wal_ledger_is_rejected(self):
        with sqlite3.connect(self.path) as db:
            self.assertEqual(db.execute('PRAGMA journal_mode=WAL').fetchone()[0].lower(), 'wal')
        with self.assertRaises(StatusUnavailable):
            self.status()
        self.assertEqual(self.factory_calls, [])

    def test_wal_switch_between_header_and_connection_is_rejected(self):
        connect = sqlite3.connect
        def change_then_connect(*args, **kwargs):
            with connect(self.path) as db:
                self.assertEqual(db.execute('PRAGMA journal_mode=WAL').fetchone()[0].lower(), 'wal')
            return connect(*args, **kwargs)
        with patch('qdrant_empty_status.sqlite3.connect', side_effect=change_then_connect):
            with self.assertRaises(StatusUnavailable):
                self.status()
        self.assertEqual(self.factory_calls, [])


if __name__ == '__main__':
    unittest.main()
