"""Explicit routing and request ownership using only synthetic records."""
from contextlib import ExitStack
from contextvars import ContextVar
import hashlib
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import migration_project_registry as routing
import migrated_qdrant_index as migration
import test_window_server as fixtures
from token_windows import WindowError
from window_runtime import WindowRuntime

s = fixtures.s

class RuntimeRoutesTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.file = Path(self.temp.name) / 'routes.json'
        self.document = {'schema': routing.SCHEMA, 'entries': [
            {'module':'vetting', 'projectId':'a', 'mode':'migrated', 'receiptPath':str(Path(self.temp.name)/'a.json'), 'receiptSha256':'a'*64},
            {'module':'vetting', 'projectId':'b', 'mode':'migrated', 'receiptPath':str(Path(self.temp.name)/'b.json'), 'receiptSha256':'b'*64},
            {'module':'vetting', 'projectId':'ordinary', 'mode':'ordinary'}]}
        self.frame_var = ContextVar('route_test_frame', default=None)
        self.runtime = SimpleNamespace(target_module='vetting', source_registry=SimpleNamespace(_validation_frame=self.frame_var))
        self.write_registry()
        self.env = {routing.REGISTRY:str(self.file), routing.REGISTRY+'_SHA256':hashlib.sha256(self.file.read_bytes()).hexdigest(), 'CONSENSE_QDRANT_URL':'http://synthetic'}

    def write_registry(self):
        self.file.write_text(json.dumps(self.document), encoding='utf-8')

    def test_explicit_routes_unknown_module_and_tampering(self):
        with patch.dict(os.environ, self.env, clear=True):
            self.assertEqual('migrated', routing.runtime_route(s, 'a', self.runtime, bind=False)['mode'])
            self.assertEqual('ordinary', routing.runtime_route(s, 'ordinary', self.runtime, bind=False)['mode'])
            for key, module in [('unknown','vetting'), ('a','advisory'), ('a',None)]:
                with self.subTest(key=key, module=module):
                    self.runtime.target_module = module
                    with self.assertRaises(WindowError): routing.runtime_route(s, key, self.runtime, bind=False)
            self.runtime.target_module = 'vetting'
            self.file.write_bytes(self.file.read_bytes() + b' ')
            with self.assertRaises(WindowError): routing.runtime_route(s, 'ordinary', self.runtime, bind=False)

    def test_registry_requires_real_owning_frame(self):
        with patch.dict(os.environ, self.env, clear=True):
            with self.assertRaises(WindowError) as error: routing.runtime_route(s, 'ordinary', self.runtime)
            self.assertEqual('MIGRATION_ROUTE_FRAME_REQUIRED', error.exception.kind)

    def test_deep_registry_json_is_a_controlled_failure(self):
        raw = b'[' * 2000 + b']' * 2000
        self.file.write_bytes(raw); self.env[routing.REGISTRY+'_SHA256'] = hashlib.sha256(raw).hexdigest()
        with patch.dict(os.environ, self.env, clear=True):
            with self.assertRaises(WindowError) as error: routing.runtime_route(s, 'a', self.runtime, bind=False)
            self.assertEqual('MIGRATION_ROUTE_UNAVAILABLE', error.exception.kind)

    def test_ordinary_read_uses_ordinary_metadata_not_a_migrated_reader(self):
        metadata = {'projectId':'ordinary', 'signature':{}, 'chunks':[]}
        self.runtime.signature_matches = lambda signature, chunks: True
        with patch.dict(os.environ, self.env, clear=True), patch.object(s, '_migrated_index', None), \
             patch.object(s, 'window_runtime', return_value=self.runtime), \
             patch.object(s, 'verify_project_publication'), \
             patch.object(s, 'load_project_metadata', return_value=metadata), \
             patch.object(s, 'database', side_effect=AssertionError('unexpected database')):
            with ExitStack() as stack:
                token = self.frame_var.set({'indexMetadata':{}, 'leaseStack':stack})
                try:
                    self.assertIs(metadata, s.read_project('ordinary'))
                    with self.assertRaises(s.HTTPException): s.read_project('unknown')
                finally: self.frame_var.reset(token)

    def test_frame_rechecks_configuration_at_exit(self):
        with patch.dict(os.environ, self.env, clear=True), ExitStack() as leases:
            token = self.frame_var.set({'indexMetadata':{}, 'leaseStack':leases})
            try:
                routing.runtime_route(s, 'ordinary', self.runtime)
                os.environ['CONSENSE_QDRANT_URL'] = 'http://changed'
                with self.assertRaises(WindowError): leases.close()
            finally: self.frame_var.reset(token)

    def test_unconfigured_or_legacy_frame_refuses_configuration_switch(self):
        for initial in ({}, {routing.LEGACY:'synthetic'}):
            with self.subTest(initial=initial), patch.dict(os.environ, initial, clear=True), \
                 patch.object(s, '_migrated_index', None), ExitStack() as leases:
                token = self.frame_var.set({'indexMetadata':{}, 'leaseStack':leases})
                try:
                    routing.runtime_route(s, 'ordinary', self.runtime)
                    os.environ.update(self.env)
                    with self.assertRaises(WindowError): routing.runtime_route(s, 'ordinary', self.runtime)
                    with self.assertRaises(WindowError): leases.close()
                finally: self.frame_var.reset(token)

    def test_frame_refuses_removed_registry_before_fallback(self):
        with patch.dict(os.environ, self.env, clear=True), ExitStack() as leases:
            token = self.frame_var.set({'indexMetadata':{}, 'leaseStack':leases})
            try:
                routing.runtime_route(s, 'ordinary', self.runtime)
                del os.environ[routing.REGISTRY]; del os.environ[routing.REGISTRY+'_SHA256']
                with self.assertRaises(WindowError): routing.runtime_route(s, 'ordinary', self.runtime)
                with self.assertRaises(WindowError): leases.close()
            finally: self.frame_var.reset(token)

    def test_mixed_registry_blocks_direct_runtime_write_before_model_or_database(self):
        runtime = WindowRuntime.__new__(WindowRuntime)
        runtime.s = SimpleNamespace(project_key=lambda key:key)
        runtime.target_module = 'vetting'
        with patch.dict(os.environ, self.env, clear=True):
            with self.assertRaises(WindowError): runtime.index(SimpleNamespace(projectId='ordinary', chunks=[]))
            with self.assertRaises(WindowError): routing.require_writable(runtime.s, 'a', runtime)
            with self.assertRaises(WindowError): routing.require_writable(runtime.s, 'unknown', runtime)
        self.document['entries'] = [self.document['entries'][-1]]
        self.write_registry(); self.env[routing.REGISTRY+'_SHA256'] = hashlib.sha256(self.file.read_bytes()).hexdigest()
        with patch.dict(os.environ, self.env, clear=True):
            with self.assertRaises(WindowError): routing.require_writable(runtime.s, 'ordinary', runtime)

    def test_write_rechecks_when_configuration_changes_before_create_or_upsert(self):
        for phase in ('create', 'upsert'):
            with self.subTest(phase=phase), patch.dict(os.environ, {}, clear=True), patch.object(s, '_migrated_index', None):
                fixture = fixtures.ServerTests('runTest')
                fixture.setUp()
                try:
                    fixture.rt.target_module = 'vetting'
                    self.document['entries'][-1]['projectId'] = 'one'
                    self.write_registry()
                    self.env[routing.REGISTRY+'_SHA256'] = hashlib.sha256(self.file.read_bytes()).hexdigest()
                    def switch(): os.environ.update(self.env)
                    def exists(_name):
                        if phase == 'create': switch()
                        return False
                    def encode(texts):
                        switch()
                        import numpy as np
                        return np.asarray([[1.,0.] for _ in texts], dtype=np.float32)
                    with patch.object(fixture.db, 'collection_exists', side_effect=exists), \
                         patch.object(fixture.db, 'create_collection', wraps=fixture.db.create_collection) as create, \
                         patch.object(fixture.db, 'upsert', wraps=fixture.db.upsert) as upsert:
                        with patch.object(s, 'encode_embeddings', side_effect=encode if phase == 'upsert' else s.encode_embeddings):
                            with self.assertRaises(s.HTTPException): fixture.index()
                        self.assertEqual(0 if phase == 'create' else 1, create.call_count)
                        upsert.assert_not_called()
                finally: fixture.tearDown()

    def test_reuse_checks_configuration_before_cache_update_and_return(self):
        import window_runtime
        with patch.dict(os.environ, {}, clear=True), patch.object(s, '_migrated_index', None):
            fixture = fixtures.ServerTests('runTest')
            fixture.setUp()
            try:
                fixture.rt.target_module = 'vetting'
                fixture.index()
                self.document['entries'][-1]['projectId'] = 'one'
                self.write_registry()
                self.env[routing.REGISTRY+'_SHA256'] = hashlib.sha256(self.file.read_bytes()).hexdigest()
                actual = window_runtime.verify_reuse
                def switch(*args, **kwargs):
                    result = actual(*args, **kwargs)
                    os.environ.update(self.env)
                    return result
                with patch.object(window_runtime, 'verify_reuse', side_effect=switch):
                    with self.assertRaises(s.HTTPException): fixture.index()
                self.assertNotIn('one', s._projects)
            finally: fixture.tearDown()

    def test_registered_ordinary_unified_retrieve_uses_ordinary_index(self):
        import test_source_page_index as source_fixture
        with patch.dict(os.environ, {}, clear=True):
            fixture = source_fixture.SourcePageIndexHttpTests('runTest')
            fixture.setUp()
            try:
                self.assertEqual(200, fixture.index().status_code)
                key = 'AUTHORED-pins-index'
                doc = {'schema':routing.SCHEMA, 'entries':[
                    {'module':'vetting', 'projectId':key, 'mode':'ordinary'},
                    {'module':'vetting', 'projectId':'other', 'mode':'migrated',
                     'receiptPath':str(Path(self.temp.name)/'other.json'), 'receiptSha256':'b'*64}]}
                raw = json.dumps(doc).encode(); self.file.write_bytes(raw)
                values = {routing.REGISTRY:str(self.file), routing.REGISTRY+'_SHA256':hashlib.sha256(raw).hexdigest(), 'CONSENSE_QDRANT_URL':'http://synthetic'}
                with patch.dict(os.environ, values), patch.object(fixture.backend, '_migrated_index', None, create=True):
                    result = fixture.client.post('/retrieve', json={'projectId':key, 'query':'Visible', 'candidates':4, 'limit':2})
                self.assertEqual(200, result.status_code, result.text)
                self.assertEqual(key, result.json()['projectId'])
                self.assertNotIn('indexAccess', result.json())
            finally: fixture.tearDown()

    def test_status_and_availability_reject_same_mode_registry_switch(self):
        import test_source_page_index as source_fixture
        for mode in ('ordinary', 'migrated'):
            for endpoint in ('/index-canonical/status', '/index-canonical/availability'):
                with self.subTest(mode=mode, endpoint=endpoint), patch.dict(os.environ, {}, clear=True):
                    fixture = source_fixture.SourcePageIndexHttpTests('runTest')
                    fixture.setUp()
                    try:
                        self.assertEqual(200, fixture.index().status_code)
                        key = 'AUTHORED-pins-index'
                        entry = {'module':'vetting', 'projectId':key, 'mode':mode}
                        if mode == 'migrated':
                            entry.update(receiptPath=str(Path(self.temp.name)/'a.json'), receiptSha256='a'*64)
                        raw_a = json.dumps({'schema':routing.SCHEMA, 'entries':[entry]}).encode()
                        raw_b = raw_a + b' '
                        self.file.write_bytes(raw_a)
                        values = {routing.REGISTRY:str(self.file), routing.REGISTRY+'_SHA256':hashlib.sha256(raw_a).hexdigest(), 'CONSENSE_QDRANT_URL':'http://synthetic'}
                        original = routing.runtime_route
                        calls = []
                        def switch(*args, **kwargs):
                            if kwargs.get('bind', True):
                                calls.append(1)
                                if len(calls) == 2:
                                    self.file.write_bytes(raw_b)
                                    os.environ[routing.REGISTRY+'_SHA256'] = hashlib.sha256(raw_b).hexdigest()
                            return original(*args, **kwargs)
                        body = {'projectId':key, 'module':'vetting', 'projectionSha256':[fixture.projection['projectionSha256']]}
                        if endpoint.endswith('availability'): body['coldBootstrap'] = True
                        with patch.dict(os.environ, values), patch.object(routing, 'runtime_route', side_effect=switch):
                            result = fixture.client.post(endpoint, json=body)
                        self.assertGreaterEqual(len(calls), 2)
                        self.assertNotEqual(200, result.status_code, result.text)
                    finally: fixture.tearDown()

    def test_interleaved_frame_owned_readers_do_not_use_global_reader(self):
        class Reader:
            def __init__(self, environ):
                self.receipt_file = {'sha256':environ[migration.ENV+'_SHA256']}
                self.metadata = {'projectId':'a' if self.receipt_file['sha256']=='a'*64 else 'b', 'generation':None}
                self.receipt = {'target':{'url':'http://synthetic','collection':self.metadata['projectId']}, 'targetVectorIntegrity':{}}
                self.last_observation = {'receipt':self.receipt_file}
            def candidate_identity(self, key):
                if key != self.metadata['projectId']: raise AssertionError('wrong project')
                return key
            def read(self, key, runtime, database): return self.metadata
        class Lease:
            def verify(self, binding): return True
        class Provider:
            def acquire(self, binding):
                class Manager:
                    def __enter__(self): return Lease()
                    def __exit__(self, *args): return False
                return Manager()
        provider = Provider()
        with patch.dict(os.environ, self.env, clear=True), patch.object(s, '_migrated_index', None), \
             patch.object(s, 'window_runtime', return_value=self.runtime), patch.object(s, 'verify_project_publication'), \
             patch.object(migration, 'MigratedIndex', Reader), patch.object(migration, '_source_frame_witness_provider', return_value=provider):
            for key in ('a','b','a'):
                with ExitStack() as leases:
                    frame = {'indexMetadata':{}, 'leaseStack':leases}
                    token = self.frame_var.set(frame)
                    try:
                        route = routing.runtime_route(s, key, self.runtime)
                        candidate = migration.migration_candidate_for_frame(s, key, self.runtime.source_registry, route)
                        frame['indexMetadata'][key]['migrationSourceScopeVerified'] = True
                        self.assertIs(candidate, s.read_project(key))
                        self.assertEqual(key, candidate['projectId'])
                        self.assertEqual(route['receiptSha256'], frame['indexMetadata'][key]['migrationObservation']['receipt']['sha256'])
                        self.assertIsNone(s._migrated_index)
                    finally: self.frame_var.reset(token)

if __name__ == '__main__': unittest.main()
