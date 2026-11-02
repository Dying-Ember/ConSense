"""Direct Qdrant source binding and initialization checks."""
import os, unittest
from types import SimpleNamespace
from unittest.mock import patch
from token_windows import WindowError
from window_runtime import require_empty_project_storage

class BindingTest(unittest.TestCase):
    def setUp(self):
        self.client=SimpleNamespace(
            get_collections=lambda:SimpleNamespace(collections=[]),
            get_aliases=lambda:SimpleNamespace(aliases=[]))
        self.server=SimpleNamespace(STATE='/fixture',_database=self.client,
            _database_configuration=('/fixture','http://fixture',None),
            _database_binding=(self.client,('/fixture','http://fixture',None)),
            database=lambda:self.client,digest=lambda key:'1234567890123456')
        self.env=patch.dict(os.environ,{'CONSENSE_QDRANT_URL':'http://fixture'},clear=True)
        self.env.start();self.addCleanup(self.env.stop)
    def test_unrelated_collections_and_aliases_are_allowed(self):
        self.client.get_collections=lambda:SimpleNamespace(collections=[SimpleNamespace(name='unrelated')])
        self.client.get_aliases=lambda:SimpleNamespace(aliases=[SimpleNamespace(alias_name='other')])
        self.assertIs(self.client,require_empty_project_storage(self.server,'one'))
    def test_existing_project_collection_or_alias_fails(self):
        for kind in ('collection','alias'):
            with self.subTest(kind=kind):
                name='project_1234567890123456_old'
                self.client.get_collections=lambda:SimpleNamespace(collections=[SimpleNamespace(name=name)] if kind=='collection' else [])
                self.client.get_aliases=lambda:SimpleNamespace(aliases=[SimpleNamespace(alias_name=name)] if kind=='alias' else [])
                with self.assertRaises(WindowError):require_empty_project_storage(self.server,'one')
    def test_replaced_client_or_configuration_fails(self):
        for mode in ('client','configuration'):
            with self.subTest(mode=mode):
                if mode=='client': self.server._database=object()
                else: self.server._database=self.client;os.environ['CONSENSE_QDRANT_URL']='http://changed'
                with self.assertRaises(WindowError):require_empty_project_storage(self.server,'one')
    def test_collection_query_failure_is_structured(self):
        self.client.get_collections=lambda:(_ for _ in ()).throw(OSError('private canary'))
        with self.assertRaises(WindowError) as error:require_empty_project_storage(self.server,'one')
        self.assertEqual('QDRANT_BACKEND_UNAVAILABLE',error.exception.kind)
        self.assertNotIn('private canary',str(error.exception))

if __name__=='__main__': unittest.main()
