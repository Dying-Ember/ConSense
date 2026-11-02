from types import SimpleNamespace
import time
from unittest import TestCase
from unittest.mock import Mock, patch

from fixed_reference_query import FixedBackend, FixedReferenceQuery, FixedReferenceRouter, FixedReferenceRuntime
from test_remote_model_session_scope import FakeSession, FixtureObserver, setup, owner
from token_windows import WindowError


class FixedReferenceQueryTest(TestCase):
    def setUp(self):
        FakeSession.calls = []
        FakeSession.fail_release = FakeSession.bad_result = False
        self.proxy = setup()
        self.proxy.client.profile_sha256s['fixed-reference-query'] = 'd'*64
        timeout_pin = patch('remote_gpu_models._trusted_stage_timeout', return_value=10)
        timeout_pin.start()
        self.addCleanup(timeout_pin.stop)
        self.proxy.require_request_scope()
        self.reader = SimpleNamespace()
        self.backend = FixedBackend(self.proxy.backend, self.reader)
        self.backend._remote_models = self.proxy
        self.backend._runtime = SimpleNamespace(logical_identity=lambda kind: {'model': kind},
                                                inference_cap=lambda kind: 512)
        def retrieve(request):
            vectors = self.proxy.encode_embeddings(['query'])
            scores = self.proxy.rerank_model().predict([['q', 'text']], batch_size=4,
                                                       show_progress_bar=False, convert_to_numpy=True)
            return {'vectors': vectors.tolist(), 'scores': scores.tolist()}
        self.backend._runtime.retrieve = retrieve
        self.fixed = FixedReferenceQuery.__new__(FixedReferenceQuery)
        self.fixed.project_id = 'fixed-project'
        self.fixed.manifest_sha256 = 'a'*64
        self.fixed.sl_sources = {'d'*64, 'e'*64}
        self.fixed.backend = self.backend
        self.request = SimpleNamespace(projectId='fixed-project', role='standard', sourceSha256=['d'*64])

    def test_fixed_recipe_uses_same_session_owner_and_preserves_ordinary_recipe(self):
        with patch('remote_gpu_models.ModelSession', FakeSession), \
             patch('source_request_context.model_session_control', return_value=owner()):
            response = self.fixed.retrieve(self.request)
        self.assertEqual(response['scores'], [.7])
        starts = [row for row in FakeSession.calls if row[0] == 'start']
        self.assertEqual([row[1] for row in starts], ['fixed-reference-query', 'fixed-reference-query'])
        self.assertEqual([row[2]['runtime']['dtype'] for row in starts], ['float16', 'float16'])
        self.assertEqual([row[2]['runtime']['batchSize'] for row in starts], [16, 4])
        self.assertEqual(self.proxy.model_runtime('embedding')['dtype'], 'float32')
        self.assertEqual(self.proxy.model_runtime('reranker')['batchSize'], 2)
        self.assertEqual([row[0] for row in FakeSession.calls], ['start', 'batch', 'finish', 'start', 'batch', 'finish'])

    def test_missing_wrong_or_duplicate_edition_never_starts_model(self):
        for sources in ([], ['f'*64], ['d'*64, 'e'*64], ['d'*64, 'd'*64], ['b'*64, 'd'*64]):
            self.request.sourceSha256 = sources
            with self.assertRaises(WindowError):
                self.fixed.retrieve(self.request)
        self.assertEqual([], FakeSession.calls)

    def test_fixed_reference_uses_pinned_stage_budget_not_proxy_budget(self):
        self.proxy.timeout = 2400
        control = owner()
        control['deadline'] = time.monotonic() + 3000
        with patch('remote_gpu_models.ModelSession', FakeSession), \
             patch('remote_gpu_models._trusted_stage_timeout', return_value=900) as stage_timeout, \
             patch('source_request_context.model_session_control', return_value=control):
            self.fixed.retrieve(self.request)
        starts = [row for row in FakeSession.calls if row[0] == 'start']
        self.assertEqual(len(starts), 2)
        self.assertTrue(all(0 < row[2]['deadlineBudgetMs'] <= 900000 for row in starts))
        self.assertEqual(stage_timeout.call_count, 2)
        stage_timeout.assert_called_with('fixed-reference-query', 'd'*64, None)

    def test_fixed_reference_without_profile_pin_cannot_start(self):
        del self.proxy.client.profile_sha256s['fixed-reference-query']
        with patch('remote_gpu_models.ModelSession', FakeSession), \
             patch('source_request_context.model_session_control', return_value=owner()):
            with self.assertRaisesRegex(ValueError, 'fixed-reference profile'):
                self.fixed.retrieve(self.request)
        self.assertEqual(FakeSession.calls, [])

    def test_released_fixed_failure_allows_ordinary_request_but_unknown_blocks(self):
        for release_state in ('completed', 'cancelled', 'unknown', 'wrong-operation', 'not-terminal'):
            with self.subTest(release=release_state):
                proxy = setup()
                proxy.client.profile_sha256s['fixed-reference-query'] = 'd'*64
                proxy.require_request_scope()
                self.backend._remote_models = proxy
                def broken_retrieval(request):
                    proxy.encode_embeddings(['query'])
                    if release_state == 'completed':
                        proxy._active_scope.finish()
                    raise RuntimeError('vector store unavailable')
                self.backend._runtime.retrieve = broken_retrieval
                def cancel(session):
                    if release_state == 'unknown':
                        return {'status': 'release_unknown', 'operationId': session.operation}
                    return {'status': 'released',
                            'operationId': 'f'*32 if release_state == 'wrong-operation' else session.operation,
                            'operationStatus': 'running' if release_state == 'not-terminal' else 'cancelled',
                            'cgroupPopulated': 0, 'observedGpuPids': [123]}
                with patch('remote_gpu_models.ModelSession', FakeSession), \
                     patch.object(FakeSession, 'cancel', cancel), \
                     patch('remote_gpu_models._trusted_stage_timeout', return_value=10), \
                     patch('source_request_context.model_session_control', return_value=owner()):
                    with self.assertRaisesRegex(RuntimeError, 'vector store unavailable'):
                        self.fixed.retrieve(self.request)
                    ordinary = lambda: proxy.session_scope(owner(), 'b'*64, 'query-embedding-rerank',
                        max_batches=4, max_total_bytes=2048, observer=FixtureObserver(proxy), execution_sha256='f'*64)
                    if release_state in ('unknown', 'wrong-operation', 'not-terminal'):
                        with self.assertRaisesRegex(ValueError, 'release unknown'):
                            with ordinary(): pass
                    else:
                        with ordinary():
                            proxy.encode_embeddings(['ordinary query'])

    def test_fixed_backend_explicitly_requests_verified_index_reuse(self):
        self.reader.read = Mock(return_value={'authored': True})
        self.backend.database = Mock()
        self.assertEqual({'authored': True}, self.backend.read_project('fixed-project'))
        self.reader.read.assert_called_once_with('fixed-project', self.backend._runtime,
                                                 self.backend.database, reuse_verified=True)

    def test_legacy_index_and_query_runtime_report_distinct_versions(self):
        self.fixed.reader=SimpleNamespace(metadata={'signature':{'signatureVersion':3}},
                                          frozen={'indexSignature':'a'*64})
        self.backend._runtime.signature_version=lambda:5
        self.backend._runtime.retrieval_recipe=lambda **kwargs:{'contract':'authored'}
        result=self.fixed.health()
        self.assertEqual(3,result['indexSignatureVersion'])
        self.assertEqual(5,result['queryRuntimeSignatureVersion'])

    def test_windows_and_parent_validation_reused_only_for_verified_generation(self):
        from window_runtime import WindowRuntime
        self.reader._verification_generation = 1
        runtime = FixedReferenceRuntime.__new__(FixedReferenceRuntime)
        runtime.s = self.backend
        metadata = {'authored': True}
        first = ({'p': 'parent'}, {'w': 'window'})
        with patch.object(WindowRuntime, 'validate_query_metadata', return_value=first) as validate:
            self.assertIs(runtime.validate_query_metadata(metadata), first)
            self.assertIs(runtime.validate_query_metadata(metadata), first)
            validate.assert_called_once_with(metadata)
            self.reader._verification_generation += 1
            self.assertIs(runtime.validate_query_metadata(metadata), first)
            self.assertEqual(2, validate.call_count)
            self.assertIs(runtime.validate_query_metadata({'authored': True}), first)
            self.assertEqual(3, validate.call_count)


class FixedReferenceHttpTest(TestCase):
    def setUp(self):
        import test_oer_server
        self.fixture = test_oer_server.OerHttpContractTest
        self.calls = []
        fixed = SimpleNamespace(project_id='fixed-project', manifest_sha256='a'*64,
                                health=lambda: {'status':'ok', 'mode':'fixed-reference-query'},
                                health_for_manifest=lambda sha: {'status':'ok', 'mode':'fixed-reference-query'},
                                owns_project=lambda project: str(project)=='fixed-project',
                                retrieve=lambda request: self.calls.append(request.projectId) or {'projectId':request.projectId})
        with patch('fixed_reference_query.from_environment', return_value=fixed):
            self.fixture.setUp(self)

    def tearDown(self):
        self.fixture.tearDown(self)

    def test_fixed_route_and_health_do_not_change_normal_health(self):
        health = self.client.get('/health')
        self.assertEqual(200, health.status_code)
        self.assertEqual(6, health.json()['indexSignatureVersion'])
        fixed_health = self.client.get('/health', params={'fixedReference':'a'*64})
        self.assertEqual({'status':'ok', 'mode':'fixed-reference-query'}, fixed_health.json())
        response = self.client.post('/retrieve', json={'projectId':'fixed-project', 'query':'test',
                                                        'role':'standard', 'sourceSha256':['b'*64]})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual({'projectId':'fixed-project'}, response.json())
        self.assertEqual(['fixed-project'], self.calls)


class FixedReferenceRouterTest(TestCase):
    def test_each_manifest_and_project_routes_to_its_own_reference(self):
        def item(project, manifest, sha):
            return SimpleNamespace(project_id=project, manifest_sha256=manifest, sl_sources={sha},
                                   health=lambda: {'manifest': manifest},
                                   retrieve=lambda request: {'project': project, 'source': request.sourceSha256})
        first = item('p1', 'a'*64, '1'*64)
        second = item('p2', 'b'*64, '2'*64)
        router = FixedReferenceRouter([first, second])
        self.assertEqual({'manifest':'b'*64}, router.health_for_manifest('b'*64))
        self.assertEqual({'project':'p2', 'source':['2'*64]},
                         router.retrieve(SimpleNamespace(projectId='p2', sourceSha256=['2'*64])))
        self.assertFalse(router.owns_project('unknown'))
        with self.assertRaises(WindowError): router.health_for_manifest('c'*64)
        with self.assertRaises(WindowError): FixedReferenceRouter([first, item('p3','c'*64,'1'*64)])
