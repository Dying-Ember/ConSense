"""Explicit old-ordinary initialization uses authored sources and a fake database."""
import hashlib
import json
import os
from types import FunctionType, SimpleNamespace
from threading import Event, Thread
from unittest import TestCase
from unittest.mock import Mock, patch

import test_source_page_index as fixture_module
from migrated_qdrant_index import ENV
from migration_project_registry import REGISTRY, SCHEMA
from token_windows import WindowError


class CanonicalInitializationDecisionTests(TestCase):
    def setUp(self):
        temp = {name: os.environ[name] for name in ('TEMP', 'TMP', 'TMPDIR') if name in os.environ}
        self.env = patch.dict(os.environ, temp, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        self.fixture = fixture_module.SourcePageIndexHttpTests('runTest')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.addCleanup(self.fixture.tearDown)
        self.f = self.fixture
        self.key = 'AUTHORED-pins-index'
        self.projection = self.f.projection['projectionSha256']
        self.f.backend._database = self.f.db
        self.f.backend._database_configuration = (str(self.f.backend.STATE), None, None)
        self.f.backend._database_binding = (self.f.db, self.f.backend._database_configuration)
        self.probe = Mock(return_value=SimpleNamespace(collections=[]))
        self.get_collections = patch.object(self.f.db, 'get_collections', self.probe, create=True)
        self.get_collections.start()
        self.addCleanup(self.get_collections.stop)
        self.aliases = Mock(return_value=SimpleNamespace(aliases=[]))
        self.get_aliases = patch.object(self.f.db, 'get_aliases', self.aliases, create=True)
        self.get_aliases.start()
        self.addCleanup(self.get_aliases.stop)

    def call(self, endpoint, **changes):
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection]}
        if endpoint.endswith('availability'):
            request['coldBootstrap'] = True
        request.update(changes)
        return self.f.client.post(endpoint, json=request)

    def test_requested_missing_status_and_cold_availability_are_bound_and_read_only(self):
        for endpoint in ('/index-canonical/status', '/index-canonical/availability'):
            with self.subTest(endpoint=endpoint):
                result = self.call(endpoint, initializationDecision=True)
                self.assertEqual(200, result.status_code, result.text)
                self.assertEqual({'initializationContract': 'consense.canonical-index-initialization.v1',
                    'projectId': self.key, 'targetModule': 'vetting',
                    'canonicalProjections': [self.projection], 'sourcePageEvidence': {},
                    'initializationAllowed': True, 'ready': False, 'readOnly': True,
                    'competitionScope': {'policyIdentity': {'manifestSha256': self.f.policy.policyIdentity['manifestSha256']},
                        'inputContract': 'canonical-source-chunks-upstream-scope-and-parser-projection-v1',
                        'targetModule': 'vetting', 'sourceQualityChanged': False}}, result.json())
        self.assertEqual(2, self.probe.call_count)
        self.assertEqual(2, self.aliases.call_count)
        self.assertFalse(self.f.backend.metadata_path(self.key).exists())
        self.assertEqual([], self.f.encodes)

    def test_legacy_request_keeps_404_and_no_database_probe(self):
        for endpoint in ('/index-canonical/status', '/index-canonical/availability'):
            result = self.call(endpoint)
            self.assertEqual(404, result.status_code, result.text)
            self.assertEqual('Project is not indexed', result.json()['detail'])
        self.probe.assert_not_called()

    def test_empty_legacy_variables_preserve_ordinary_mode_but_nonempty_or_registry_do_not(self):
        with patch.dict(os.environ, {ENV: '', ENV + '_SHA256': ''}):
            self.assertEqual(200, self.call('/index-canonical/status', initializationDecision=True).status_code)
        self.probe.reset_mock()
        for values in ({ENV: 'x'}, {ENV + '_SHA256': 'x'}, {REGISTRY: ''}, {REGISTRY + '_SHA256': ''}):
            with self.subTest(values=values), patch.dict(os.environ, values):
                result = self.call('/index-canonical/status', initializationDecision=True)
                self.assertNotEqual(200, result.status_code, result.text)
        self.probe.assert_not_called()

    def test_registered_ordinary_and_unknown_routes_never_grant_initialization(self):
        path = self.f.root / 'authored-registry.json'
        raw = json.dumps({'schema': SCHEMA, 'entries': [
            {'module': 'vetting', 'projectId': self.key, 'mode': 'ordinary'}]}).encode()
        path.write_bytes(raw)
        with patch.dict(os.environ, {REGISTRY: str(path), REGISTRY + '_SHA256': hashlib.sha256(raw).hexdigest()}):
            for key in (self.key, 'unknown'):
                with self.subTest(key=key):
                    result = self.call('/index-canonical/status', projectId=key, initializationDecision=True)
                    self.assertNotEqual(200, result.status_code, result.text)
        self.probe.assert_not_called()

    def test_remote_backend_or_existing_project_collection_or_alias_never_grants(self):
        with patch.dict(os.environ, {'CONSENSE_QDRANT_URL': 'http://authored'}):
            result = self.call('/index-canonical/status', initializationDecision=True)
            self.assertNotEqual(200, result.status_code, result.text)
        self.probe.assert_not_called()
        prefix = 'project_' + self.f.backend.digest(self.key)[:16] + '_'
        for kind in ('collection', 'alias'):
            with self.subTest(kind=kind):
                self.probe.return_value = SimpleNamespace(collections=[SimpleNamespace(name=prefix + 'old')] if kind == 'collection' else [])
                self.aliases.return_value = SimpleNamespace(aliases=[SimpleNamespace(alias_name=prefix + 'old')] if kind == 'alias' else [])
                result = self.call('/index-canonical/status', initializationDecision=True)
                self.assertNotEqual(200, result.status_code, result.text)

    def test_unbound_or_replaced_database_client_never_grants(self):
        for mode in ('unbound', 'replaced'):
            with self.subTest(mode=mode):
                if mode == 'unbound':
                    self.f.backend._database_configuration = None
                else:
                    self.f.backend._database_configuration = (str(self.f.backend.STATE), None, None)
                    self.f.backend._database = object()
                result = self.call('/index-canonical/status', initializationDecision=True)
                self.assertNotEqual(200, result.status_code, result.text)
        self.probe.assert_not_called()

    def test_same_api_client_replaced_before_request_cannot_inherit_binding(self):
        replacement = fixture_module.base_tests.DualFakeDb()
        replacement.get_collections = Mock(return_value=SimpleNamespace(collections=[]))
        replacement.get_aliases = Mock(return_value=SimpleNamespace(aliases=[]))
        with patch.object(self.f.backend, '_database', replacement), \
             patch.object(self.f.backend, 'database', return_value=replacement):
            result = self.call('/index-canonical/status', initializationDecision=True)
        self.assertNotEqual(200, result.status_code)
        replacement.get_collections.assert_not_called()
        replacement.get_aliases.assert_not_called()

    def test_small_qdrant_local_initializes_new_collection(self):
        from qdrant_client import QdrantClient
        local = QdrantClient(path=str(self.f.root / 'authored-qdrant'))
        try:
            self.f.backend._database = local
            self.f.backend._database_configuration = (str(self.f.backend.STATE), None, None)
            self.f.backend._database_binding = (local, self.f.backend._database_configuration)
            with patch.object(self.f.backend, 'database', return_value=local):
                decision = self.call('/index-canonical/status', initializationDecision=True)
                self.assertEqual(200, decision.status_code, decision.text)
                request = {'projectId': self.key, 'module': 'vetting',
                           'projectionSha256': [self.projection], 'initializationDecision': True}
                result = self.f.client.post('/index-canonical', json=request)
            self.assertEqual(200, result.status_code, result.text)
            self.assertTrue(self.f.backend.metadata_path(self.key).exists())
            self.assertEqual(1, len(local.get_collections().collections))
        finally:
            local.close()

    def test_production_database_connects_to_state_qdrant_for_initialization(self):
        import server
        backend = self.f.backend
        # The HTTP fixture replaces database(); restore the production function with
        # this isolated backend's globals so its normal connect path is exercised.
        production_database = FunctionType(server.database.__code__, vars(backend))
        backend._database = None
        backend._database_configuration = None
        backend._database_binding = None
        try:
            with patch.object(backend, 'database', production_database):
                decision = self.call('/index-canonical/status', initializationDecision=True)
                self.assertEqual(200, decision.status_code, decision.text)
                request = {'projectId': self.key, 'module': 'vetting',
                           'projectionSha256': [self.projection], 'initializationDecision': True}
                result = self.f.client.post('/index-canonical', json=request)
                self.assertEqual(200, result.status_code, result.text)
                self.assertEqual(1, len(backend.database().get_collections().collections))
                self.assertTrue((backend.STATE / 'qdrant').is_dir())
                self.assertTrue(backend.metadata_path(self.key).exists())
        finally:
            if backend._database is not None:
                backend._database.close()
            backend._database = None
            backend._database_configuration = None
            backend._database_binding = None

    def test_small_qdrant_local_rejects_collection_or_alias_added_after_decision(self):
        from qdrant_client import QdrantClient, models
        for kind in ('collection', 'alias'):
            with self.subTest(kind=kind):
                fixture = fixture_module.SourcePageIndexHttpTests('runTest')
                fixture.setUp()
                local = QdrantClient(path=str(fixture.root / 'authored-qdrant'))
                try:
                    fixture.backend._database = local
                    fixture.backend._database_configuration = (str(fixture.backend.STATE), None, None)
                    fixture.backend._database_binding = (local, fixture.backend._database_configuration)
                    request = {'projectId': self.key, 'module': 'vetting',
                               'projectionSha256': [fixture.projection['projectionSha256']],
                               'initializationDecision': True}
                    prefix = 'project_' + fixture.backend.digest(self.key)[:16] + '_'
                    with patch.object(fixture.backend, 'database', return_value=local):
                        decision = fixture.client.post('/index-canonical/status', json=request)
                        self.assertEqual(200, decision.status_code, decision.text)
                        name = prefix + 'prior' if kind == 'collection' else 'authored-other'
                        local.create_collection(name, vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
                        if kind == 'alias':
                            local.update_collection_aliases(change_aliases_operations=[
                                models.CreateAliasOperation(create_alias=models.CreateAlias(
                                    collection_name=name, alias_name=prefix + 'prior'))])
                        result = fixture.client.post('/index-canonical', json=request)
                    self.assertNotEqual(200, result.status_code, result.text)
                    self.assertEqual(0, local.count(name, exact=True).count)
                    self.assertFalse(fixture.backend.metadata_path(self.key).exists())
                finally:
                    local.close()
                    fixture.tearDown()
                    fixture.doCleanups()

    def test_bad_scope_and_journal_block_reject_before_database(self):
        for changes in ({'module': 'advisory'}, {'projectionSha256': ['0' * 64]},
                        {'projectionSha256': [self.projection, self.projection]},
                        {'sourcePageEvidence': {self.projection: '1' * 64}}):
            with self.subTest(changes=changes):
                result = self.call('/index-canonical/status', initializationDecision=True, **changes)
                self.assertNotEqual(200, result.status_code, result.text)
        with patch.object(self.f.backend, 'verify_project_publication',
                          side_effect=WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Blocked')):
            result = self.call('/index-canonical/status', initializationDecision=True)
        self.assertNotEqual(200, result.status_code, result.text)
        self.probe.assert_not_called()

    def test_mutated_source_is_rejected_before_database(self):
        descriptor = self.f.projection['nativeCaptureRefs']['sourcePackage']
        path = self.f.app.state.artifact_store.root / descriptor['relativePath']
        path.write_bytes(path.read_bytes() + b'changed')
        result = self.call('/index-canonical/status', initializationDecision=True)
        self.assertNotEqual(200, result.status_code, result.text)
        self.probe.assert_not_called()

    def test_database_failure_or_metadata_appearance_never_grants(self):
        self.probe.side_effect = WindowError('QDRANT_BACKEND_RELEASE_UNKNOWN', 'Unknown')
        result = self.call('/index-canonical/status', initializationDecision=True)
        self.assertEqual(503, result.status_code, result.text)
        self.assertEqual('QDRANT_BACKEND_RELEASE_UNKNOWN', result.json()['detail']['kind'])
        self.probe.reset_mock()
        path = self.f.backend.metadata_path(self.key)
        def appear():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('{}', encoding='utf-8')
        self.probe.side_effect = appear
        result = self.call('/index-canonical/status', initializationDecision=True)
        self.assertNotEqual(200, result.status_code, result.text)
        self.assertTrue(path.exists())

    def test_metadata_removed_from_warm_cache_does_not_grant(self):
        self.assertEqual(200, self.f.index().status_code)
        self.f.backend.metadata_path(self.key).unlink()
        for _ in range(2):
            result = self.call('/index-canonical/status', initializationDecision=True)
            self.assertNotEqual(200, result.status_code, result.text)
            self.assertIn(self.key, self.f.backend._missing_index_history)
        self.probe.assert_not_called()

    def test_old_missing_and_wrong_module_do_not_erase_warm_history(self):
        for endpoint, changes in (('/index-canonical/status', {}),
                                  ('/index-canonical/availability', {}),
                                  ('/index-canonical/status', {'module': 'advisory'})):
            fixture = fixture_module.SourcePageIndexHttpTests('runTest')
            fixture.setUp()
            try:
                self.assertEqual(200, fixture.index().status_code)
                fixture.backend.metadata_path(self.key).unlink()
                body = {'projectId': self.key, 'module': 'vetting',
                        'projectionSha256': [fixture.projection['projectionSha256']]}
                if endpoint.endswith('availability'):
                    body['coldBootstrap'] = True
                body.update(changes)
                self.assertNotEqual(200, fixture.client.post(endpoint, json=body).status_code)
                self.assertIn(self.key, fixture.backend._missing_index_history)
                body['module'] = 'vetting'
                body['initializationDecision'] = True
                result = fixture.client.post(endpoint, json=body)
                self.assertNotEqual(200, result.status_code, result.text)
            finally:
                fixture.tearDown()
                fixture.doCleanups()

    def test_failed_ordinary_index_does_not_erase_warm_history(self):
        self.assertEqual(200, self.f.index().status_code)
        self.f.backend.metadata_path(self.key).unlink()
        with patch.object(self.f.rt, 'prepared_structure', side_effect=WindowError('AUTHORED_FAILURE', 'Failed')):
            self.assertNotEqual(200, self.f.index().status_code)
        self.assertIn(self.key, self.f.backend._missing_index_history)
        result = self.call('/index-canonical/status', initializationDecision=True)
        self.assertNotEqual(200, result.status_code, result.text)
        self.probe.assert_not_called()

    def test_cache_eviction_is_atomic_with_a_competing_writer(self):
        backend = self.f.backend
        checked, attempted, finished = Event(), Event(), Event()
        observations = []
        class Observed(dict):
            def __contains__(inner, key):
                checked.set()
                self.assertTrue(attempted.wait(2))
                return super().__contains__(key)
        cache = Observed()
        def writer():
            self.assertTrue(checked.wait(2))
            got = backend.LOCK.acquire(blocking=False)
            observations.append(got)
            if got:
                cache[self.key] = {'chunks': []}
                backend.LOCK.release()
            attempted.set()
            if not got:
                with backend.LOCK:
                    cache[self.key] = {'chunks': []}
            finished.set()
        with patch.object(backend, '_projects', cache):
            thread = Thread(target=writer, daemon=True)
            thread.start()
            backend.forget_project(self.key)
            self.assertTrue(finished.wait(2))
            thread.join(2)
            self.assertEqual([False], observations)
            self.assertEqual({'chunks': []}, cache[self.key])

    def test_initialization_build_creates_its_own_collection(self):
        decision = self.call('/index-canonical/status', initializationDecision=True)
        self.assertEqual(200, decision.status_code, decision.text)
        original = self.f.db.create_collection
        def create(*args, **kwargs):
            original(*args, **kwargs)
            return True
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        with patch.object(self.f.db, 'create_collection', side_effect=create) as created, \
             patch.object(self.f.db, 'upsert', wraps=self.f.db.upsert) as upsert:
            result = self.f.client.post('/index-canonical', json=request)
        self.assertEqual(200, result.status_code, result.text)
        created.assert_called_once()
        self.assertGreater(upsert.call_count, 0)
        self.assertTrue(self.f.backend.metadata_path(self.key).exists())

    def test_initialization_build_never_upserts_existing_or_alias_target(self):
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        with patch.object(self.f.db, 'collection_exists', return_value=True), \
             patch.object(self.f.db, 'create_collection') as created, \
             patch.object(self.f.db, 'upsert') as upsert:
            result = self.f.client.post('/index-canonical', json=request)
        self.assertNotEqual(200, result.status_code, result.text)
        created.assert_not_called()
        upsert.assert_not_called()
        self.assertFalse(self.f.backend.metadata_path(self.key).exists())

    def test_model_preparation_binding_changes_reject_before_create_or_upsert(self):
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        backend = self.f.backend
        for changed in ('state', 'client', 'key'):
            with self.subTest(changed=changed), patch.object(backend, 'STATE', backend.STATE), \
                 patch.object(backend, '_database', self.f.db), patch.dict(os.environ, {}), \
                 patch.object(self.f.db, 'create_collection') as created, \
                 patch.object(self.f.db, 'upsert') as upsert:
                original_model = backend.embedding_model
                def change_then_model():
                    if changed == 'state':
                        backend.STATE = self.f.root / 'different-state'
                    elif changed == 'client':
                        backend._database = fixture_module.base_tests.DualFakeDb()
                    else:
                        os.environ['CONSENSE_QDRANT_API_KEY'] = 'authored-only'
                    return original_model()
                with patch.object(backend, 'embedding_model', side_effect=change_then_model):
                    result = self.f.client.post('/index-canonical', json=request)
                self.assertNotEqual(200, result.status_code)
                self.assertEqual('MIGRATION_ROUTE_CHANGED' if changed == 'key' else 'INDEX_INITIALIZATION_UNAVAILABLE',
                                 result.json()['detail']['kind'])
                created.assert_not_called()
                upsert.assert_not_called()
                self.assertFalse(backend.metadata_path(self.key).exists())

    def test_binding_change_during_encode_rejects_upsert(self):
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        original_create = self.f.db.create_collection
        original_encode = self.f.backend.encode_embeddings
        def create(*args, **kwargs):
            original_create(*args, **kwargs)
            return True
        def change_then_encode(texts):
            self.f.backend._database = fixture_module.base_tests.DualFakeDb()
            return original_encode(texts)
        with patch.object(self.f.backend, '_database', self.f.db), \
             patch.object(self.f.backend, 'encode_embeddings', side_effect=change_then_encode), \
             patch.object(self.f.db, 'create_collection', side_effect=create), \
             patch.object(self.f.db, 'upsert') as upsert:
            result = self.f.client.post('/index-canonical', json=request)
        self.assertNotEqual(200, result.status_code)
        self.assertEqual('INDEX_INITIALIZATION_UNAVAILABLE', result.json()['detail']['kind'])
        upsert.assert_not_called()
        self.assertFalse(self.f.backend.metadata_path(self.key).exists())

    def test_binding_change_after_inspection_rejects_before_pending(self):
        import window_runtime
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        original_inspect = window_runtime.inspect_points
        original_create = self.f.db.create_collection
        def create(*args, **kwargs):
            original_create(*args, **kwargs)
            return True
        def inspect_then_replace(*args, **kwargs):
            result = original_inspect(*args, **kwargs)
            self.f.backend._database = fixture_module.base_tests.DualFakeDb()
            return result
        with patch.object(self.f.backend, '_database', self.f.db), \
             patch.object(self.f.db, 'create_collection', side_effect=create), \
             patch('window_runtime.inspect_points', side_effect=inspect_then_replace):
            result = self.f.client.post('/index-canonical', json=request)
        self.assertNotEqual(200, result.status_code)
        self.assertEqual('INDEX_INITIALIZATION_UNAVAILABLE', result.json()['detail']['kind'])
        path = self.f.backend.metadata_path(self.key)
        self.assertFalse(path.exists())
        self.assertEqual([], list(path.parent.glob(path.name + '.pending-*')))

    def test_empty_chunks_binding_change_rejects_before_pending(self):
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        empty_plan = {'points': [], 'contexts': [], 'bindings': [], 'coverage': {}}
        def plan_then_change(*args):
            self.f.backend.STATE = self.f.root / 'different-state'
            return empty_plan
        original_path = self.f.backend.metadata_path(self.key)
        with patch.object(self.f.backend, 'STATE', self.f.backend.STATE), \
             patch.object(self.f.app.state.source_registry, 'chunks_from_loaded', return_value=[]), \
             patch.object(self.f.rt, 'prepared_structure', side_effect=plan_then_change):
            result = self.f.client.post('/index-canonical', json=request)
        self.assertNotEqual(200, result.status_code)
        self.assertEqual('INDEX_INITIALIZATION_UNAVAILABLE', result.json()['detail']['kind'])
        self.assertFalse(original_path.exists())
        self.assertEqual([], list(original_path.parent.glob(original_path.name + '.pending-*')))

    def test_empty_chunks_binding_change_at_commit_keeps_pending_unpublished(self):
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        empty_plan = {'points': [], 'contexts': [], 'bindings': [], 'coverage': {}}
        def change_then_commit(callback, intent):
            self.f.backend._database = fixture_module.base_tests.DualFakeDb()
            callback()
        with patch.object(self.f.backend, '_database', self.f.db), \
             patch.object(self.f.app.state.source_registry, 'chunks_from_loaded', return_value=[]), \
             patch.object(self.f.rt, 'prepared_structure', return_value=empty_plan), \
             patch('window_runtime.request_stage', side_effect=change_then_commit):
            result = self.f.client.post('/index-canonical', json=request)
        self.assertNotEqual(200, result.status_code)
        self.assertEqual('INDEX_INITIALIZATION_UNAVAILABLE', result.json()['detail']['kind'])
        path = self.f.backend.metadata_path(self.key)
        self.assertFalse(path.exists())
        self.assertEqual(1, len(list(path.parent.glob(path.name + '.pending-*'))))

    def test_initialization_build_cannot_reuse_existing_metadata(self):
        self.assertEqual(200, self.f.index().status_code)
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        with patch.object(self.f.db, 'upsert') as upsert:
            result = self.f.client.post('/index-canonical', json=request)
        self.assertNotEqual(200, result.status_code, result.text)
        upsert.assert_not_called()

    def test_failed_preflight_does_not_enter_remote_model_session(self):
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        proxy = SimpleNamespace(source_scope_ready=True, session_scope=Mock())
        with patch('source_request_context.active', return_value=True), \
             patch.object(self.f.backend, '_remote_models', proxy), \
             patch.object(self.f.rt, 'preflight_initialization',
                          side_effect=WindowError('INDEX_INITIALIZATION_UNAVAILABLE', 'Rejected')):
            result = self.f.client.post('/index-canonical', json=request)
        self.assertNotEqual(200, result.status_code, result.text)
        self.assertEqual('INDEX_INITIALIZATION_UNAVAILABLE', result.json()['detail']['kind'])
        proxy.session_scope.assert_not_called()

    def test_initialization_does_not_delegate_to_old_coordinator(self):
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        coordinator = SimpleNamespace(execute=Mock())
        with patch.object(self.f.backend, 'gpu_coordinator', return_value=coordinator):
            result = self.f.client.post('/index-canonical', json=request)
        self.assertNotEqual(200, result.status_code, result.text)
        self.assertEqual('INDEX_INITIALIZATION_UNAVAILABLE', result.json()['detail']['kind'])
        coordinator.execute.assert_not_called()
        self.probe.assert_not_called()

    def test_blocked_journal_precedes_existing_metadata_on_initialization_build(self):
        self.assertEqual(200, self.f.index().status_code)
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        with patch.object(self.f.backend, 'verify_project_publication',
                          side_effect=WindowError('INDEX_PUBLICATION_DURABILITY_UNKNOWN', 'Blocked')):
            result = self.f.client.post('/index-canonical', json=request)
        self.assertEqual(503, result.status_code, result.text)
        self.assertEqual('INDEX_PUBLICATION_DURABILITY_UNKNOWN', result.json()['detail']['kind'])
        self.probe.assert_not_called()

    def test_failed_empty_build_leaves_pending_and_blocks_later_initialization(self):
        request = {'projectId': self.key, 'module': 'vetting', 'projectionSha256': [self.projection],
                   'initializationDecision': True}
        empty_plan = {'points': [], 'contexts': [], 'bindings': [], 'coverage': {}}
        with patch.object(self.f.app.state.source_registry, 'chunks_from_loaded', return_value=[]), \
             patch.object(self.f.rt, 'prepared_structure', return_value=empty_plan), \
             patch('window_runtime.request_stage', side_effect=WindowError('AUTHORED_FAILURE', 'Failed')):
            result = self.f.client.post('/index-canonical', json=request)
        self.assertNotEqual(200, result.status_code, result.text)
        path = self.f.backend.metadata_path(self.key)
        self.assertFalse(path.exists())
        pending = list(path.parent.glob(path.name + '.pending-*'))
        self.assertEqual(1, len(pending))
        self.probe.reset_mock()
        result = self.call('/index-canonical/status', initializationDecision=True)
        self.assertNotEqual(200, result.status_code, result.text)
        self.probe.assert_not_called()
        self.assertTrue(pending[0].exists())

    def test_normal_existing_index_remains_the_existing_success_contract(self):
        self.assertEqual(200, self.f.index().status_code)
        result = self.call('/index-canonical/status', initializationDecision=True)
        self.assertEqual(200, result.status_code, result.text)
        self.assertTrue(result.json()['ready'])
        self.assertNotIn('initializationAllowed', result.json())
        self.probe.assert_not_called()

    def test_configuration_change_during_probe_withholds_decision(self):
        self.probe.side_effect = lambda: os.environ.__setitem__(REGISTRY, '')
        result = self.call('/index-canonical/status', initializationDecision=True)
        self.assertNotEqual(200, result.status_code, result.text)
        self.assertNotIn('initializationAllowed', result.text)

    def test_non_boolean_and_non_cold_availability_never_grant(self):
        result = self.call('/index-canonical/status', initializationDecision='true')
        self.assertEqual(422, result.status_code, result.text)
        result = self.call('/index-canonical/availability', initializationDecision=True,
                           coldBootstrap=False, expectedSignature='0' * 64)
        self.assertEqual(404, result.status_code, result.text)
        self.probe.assert_not_called()
