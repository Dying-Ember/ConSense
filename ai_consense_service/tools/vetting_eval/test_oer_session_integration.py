"""Public source/frame to 04 model-session seam with entirely synthetic providers."""
from contextlib import ExitStack, contextmanager
import base64
import hashlib
import json
import threading
from unittest.mock import patch
import unittest

import oer_request_evidence as evidence
REAL_PUBLICATION_VERIFIER = evidence.RequestEvidence._verify_publication_evidence
import test_remote_source_page_index as source_fixture
import test_oer_server as oer_fixture
import remote_gpu_models
import model_session_observer
from request_stage import encoded
from fixed_model_session import ModelSession
from token_windows import WindowError


class SyntheticGateway:
    def __init__(self):
        self.operations={};self.calls=[];self.fail_release=False;self.on_batch=None;self.on_start=None
        self.profile_sha256s={'document-embedding':'a'*64,'query-embedding-rerank':'b'*64}
    def _identity(self,stage,binding,body):return {'bodySha256':hashlib.sha256(body).hexdigest(),
        'sourceSha256':hashlib.sha256(encoded(binding)).hexdigest(),'profileSha256':self.profile_sha256s[stage]}
    def _matches(self,state,expected):return all(state.get(key)==value for key,value in expected.items())
    def cancel_and_observe(self,operation,expected,*,model_stage=None):return {'status':'release_unknown','operationId':operation}
    def _call(self,method,path,timeout,value=None,cancelled=None):
        if path=='/operations':
            if self.on_start is not None:self.on_start()
            operation=('%032x' % (len(self.operations)+1));self.operations[operation]={
                'stage':value['stage'],'binding':value['binding'],'rows':[],'total':0,'finished':False,
                'expected':self._identity(value['stage'],value['binding'],b'{}')}
            self.calls.append(('start',operation));return {'operationId':operation}
        operation=path.split('/')[2];data=self.operations[operation]
        if path.endswith('/model-batches'):
            body=json.loads(base64.b64decode(value['bodyBase64']))
            result=({'dimension':2} if body['kind']=='dimension' else
                    {'dimension':2,'vectors':[[1.,0.] for _ in body['texts']]} if body['kind']=='embedding'
                    else {'scores':[.7 for _ in body['pairs']]})
            row={'ordinal':value['ordinal'],'inputSha256':value['inputSha256'],
                 'outputSha256':hashlib.sha256(encoded(result)).hexdigest()}
            data['rows'].append(row);data['total']+=len(encoded(body))+len(encoded(result))
            self.calls.append(('batch',operation))
            if self.on_batch is not None:self.on_batch(body,result)
            return {'operationId':operation,'bindingSha256':'e'*64,**row,'result':result,'provisional':True}
        if path.endswith('/finish-model'):
            data['finished']=True;self.calls.append(('finish',operation))
            return {'operationId':operation,'status':'finishing'}
        summary={'operationId':operation,'bindingSha256':'e'*64,'requestId':data['binding']['requestId'],
                 'batchCount':len(data['rows']),'batchesSha256':hashlib.sha256(encoded(data['rows'])).hexdigest(),
                 'totalBytes':data['total'],'status':'computation_completed_release_unverified'}
        if path.endswith('/model-summary'):return summary
        return {'operationId':operation,'stage':data['stage'],'bindingSha256':'e'*64,
                **data['expected'],
                'status':'completed' if data['finished'] else 'ready',
                'configIdentityScope':'config_file_bytes','configSha256':'c'*64,
                'modelSummarySha256':hashlib.sha256(encoded(summary)).hexdigest(),
                'release':{'status':'release_unknown' if self.fail_release else 'released',
                           'cgroupPopulated':0,'observedGpuPids':[123]}}


class OerSessionIntegrationTest(unittest.TestCase):
    def setUp(self):
        source_fixture.RemoteSourcePageIndexTest.setUp(self)
        self.publication_fakes.close()
        self.stack=ExitStack();self.addCleanup(self.stack.close)
        self.stack.enter_context(patch('remote_gpu_models._trusted_stage_timeout',return_value=10))
        self.stack.enter_context(patch.object(evidence,'_sync_file'))
        self.stack.enter_context(patch.object(evidence,'_sync_directory'))
        self.stack.enter_context(patch.object(evidence.RequestEvidence,'_verify_publication_evidence',
            return_value={'sourceEvidenceSha256':'a'*64,'modelReleaseEvidenceSha256':'b'*64}))
        self.stack.enter_context(patch.object(model_session_observer,'_trusted_stage_pins',
            side_effect=lambda stage: {'profileSha256':{'document-embedding':'a'*64,
                'query-embedding-rerank':'b'*64}[stage], 'executionSha256':'d'*64}))
        self.stack.enter_context(patch.object(model_session_observer,'load_trusted_stage_pins',
            side_effect=lambda stage,*args: {'profileSha256':{'document-embedding':'a'*64,
                'query-embedding-rerank':'b'*64}[stage], 'executionSha256':'d'*64}))
        self.stack.enter_context(patch.object(self.app.state.source_registry.source_pages,'_read',return_value={'authored':True}))
        self.gateway=SyntheticGateway()
        proxy=remote_gpu_models.RemoteModels(self.backend,self.gateway,dtype='float32',serial_offload=True,timeout=5)
        proxy.require_request_scope();self.backend._remote_models=proxy
        self.backend.embedding_model=proxy.embedding_model;self.backend.rerank_model=proxy.rerank_model
        self.backend.encode_embeddings=proxy.encode_embeddings

    def tearDown(self): oer_fixture.OerHttpContractTest.tearDown(self)

    def test_public_index_and_retrieve_bind_source_scope_and_finish_sessions(self):
        block=self.backend.request_evidence().block_path('AUTHORED-remote-pins')
        admission_blocks=[]
        self.gateway.on_start=lambda: admission_blocks.append(block.is_file())
        response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual([True],admission_blocks)
        self.assertFalse(block.exists())
        self.gateway.on_start=None
        metadata=json.loads(self.backend.metadata_path('AUTHORED-remote-pins').read_text(encoding='utf-8'))
        scope=metadata['sourceScope']
        self.assertEqual(self.body['projectionSha256'],scope['projectionSha256'])
        self.assertEqual(self.pins,scope['sourcePageEvidence'])
        self.assertEqual(64,len(scope['sourceScopeSha256']))
        starts=[row for row in self.gateway.calls if row[0]=='start']
        self.assertEqual(1,len(starts));self.assertEqual(scope['sourceScopeSha256'],self.gateway.operations[starts[0][1]]['binding']['sourceScopeSha256'])
        self.assertTrue(any(row[0]=='finish' for row in self.gateway.calls))
        response=self.client.post('/retrieve',json={'projectId':'AUTHORED-remote-pins','query':'approval','candidates':2,'limit':1})
        self.assertEqual(200,response.status_code,response.text)
        stages=[self.gateway.operations[row[1]]['stage'] for row in self.gateway.calls if row[0]=='start']
        self.assertEqual(['document-embedding','query-embedding-rerank','query-embedding-rerank'],stages)

    def test_actual_journal_verifier_accepts_source_and_worker_session_events(self):
        with patch.object(evidence.RequestEvidence, '_verify_publication_evidence', new=REAL_PUBLICATION_VERIFIER):
            response=self.client.post('/index-canonical',json=self.body)
            self.assertEqual(200,response.status_code,response.text)
            self.assertFalse(self.backend.request_evidence().block_path('AUTHORED-remote-pins').exists())
            self.assertTrue(any(row[0] == 'finish' for row in self.gateway.calls))
            path=self.backend.metadata_path('AUTHORED-remote-pins')
            metadata=json.loads(path.read_text(encoding='utf-8'))
            fresh=evidence.RequestEvidence(self.backend.STATE/'run-evidence')
            calls=len(self.gateway.calls)
            fresh.recover_index(path,metadata,'AUTHORED-remote-pins')
            receipt=fresh.verify_index(path,metadata,'AUTHORED-remote-pins')
            self.assertEqual('completed',receipt['terminalStatus'])
            self.assertEqual(calls,len(self.gateway.calls))

    def test_missing_session_scope_rejects_before_admission(self):
        with patch.object(self.backend._remote_models, 'session_scope', None):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(503,response.status_code,response.text)
        self.assertEqual('MODEL_SESSION_RELEASE_REQUIRED',response.json()['detail']['kind'])
        self.assertEqual([],self.gateway.calls)
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_retrieve_without_session_scope_rejects_before_query_admission(self):
        response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,response.status_code,response.text)
        calls=len(self.gateway.calls)
        with patch.object(self.backend._remote_models, 'session_scope', None):
            response=self.client.post('/retrieve',json={'projectId':'AUTHORED-remote-pins','query':'approval','candidates':2,'limit':1})
        self.assertEqual(503,response.status_code,response.text)
        self.assertEqual('MODEL_SESSION_RELEASE_REQUIRED',response.json()['detail']['kind'])
        self.assertEqual(calls,len(self.gateway.calls))

    def test_reverse_source_order_indexes_and_retrieves_the_stored_declaration(self):
        from structural_retrieval import StructuralRecipe
        self.backend._windows_runtime.structural_recipe=StructuralRecipe(mode='windows-v1')
        self.assertFalse(self.backend._windows_runtime.structural_recipe.enabled)
        registry=self.app.state.source_registry;original=registry.chunks_from_loaded;seen=[]
        def reverse(snapshot,*,module):
            chunks=sorted(original(snapshot,module=module),key=lambda row:row['id'],reverse=True)
            seen.extend(row['id'] for row in chunks);return chunks
        # The native-order guard is isolated here to exercise the declaration seam.
        with patch.object(registry,'validate_native_chunks',return_value=None), \
                patch.object(registry,'chunks_from_loaded',side_effect=reverse):
            response=self.client.post('/index-canonical',json=self.body)
            self.assertEqual(200,response.status_code,response.text)
            self.assertGreater(len(seen),1);self.assertEqual(seen,sorted(seen,reverse=True))
            self.assertNotEqual(seen,sorted(seen))
            metadata=json.loads(self.backend.metadata_path('AUTHORED-remote-pins').read_text(encoding='utf-8'))
            self.assertEqual(sorted(seen),[row['id'] for row in metadata['chunks']])
            response=self.client.post('/retrieve',json={'projectId':'AUTHORED-remote-pins','query':'approval','candidates':2,'limit':1})
            self.assertEqual(200,response.status_code,response.text)

    def test_release_failure_keeps_metadata_unpublished(self):
        self.gateway.fail_release=True
        response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_self_reported_release_without_independent_evidence_refuses_publication(self):
        with patch.object(model_session_observer.ModelSessionObserver,'_verify_gpu_release',return_value=False):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertTrue(any(row[0]=='finish' for row in self.gateway.calls))
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_missing_or_mismatched_independent_pins_reject_before_admission(self):
        for pins in (None, {'profileSha256':'f'*64,'executionSha256':'d'*64}):
            with self.subTest(pins=pins), patch.object(model_session_observer,'_trusted_stage_pins',return_value=pins):
                response=self.client.post('/index-canonical',json=self.body)
                self.assertNotEqual(200,response.status_code)
                self.assertEqual('MODEL_SESSION_TRUSTED_PINS_REQUIRED' if pins is None else 'WINDOW_INDEX_UNAVAILABLE',
                                 response.json()['detail']['kind'])
                if pins is not None:
                    self.assertEqual('ValueError',response.json()['detail']['type'])
                    self.assertEqual('independent model preflight differs',response.json()['detail']['message'])
                self.assertEqual([],self.gateway.calls)
                self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_execution_pin_mismatch_rejects_before_admission(self):
        original=model_session_observer.ModelSessionObserver.preflight_session
        def changed(observer,stage,kind):
            return {**original(observer,stage,kind),'executionSha256':'e'*64}
        with patch.object(model_session_observer.ModelSessionObserver,'preflight_session',new=changed):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual('WINDOW_INDEX_UNAVAILABLE',response.json()['detail']['kind'])
        self.assertEqual('ValueError',response.json()['detail']['type'])
        self.assertEqual('independent model preflight differs',response.json()['detail']['message'])
        self.assertEqual([],self.gateway.calls)
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_session_scope_preserves_cancellation_kind_and_status(self):
        @contextmanager
        def cancelled(*args, **kwargs):
            raise WindowError('SOURCE_REQUEST_CANCELLED', 'authored cancellation')
            yield
        with patch('oer_server._require_publication_model_proxy'), \
             patch.object(self.backend._remote_models,'session_scope',new=cancelled):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(503,response.status_code)
        self.assertEqual('SOURCE_REQUEST_CANCELLED',response.json()['detail']['kind'])
        self.assertEqual([],self.gateway.calls)

    def test_legacy_missing_execution_pin_is_classified_as_model_scope_failure(self):
        original=self.backend._remote_models.session_scope
        observed=[]
        @contextmanager
        def legacy_call(*args, **kwargs):
            kwargs.pop('execution_sha256')
            try:
                with original(*args, **kwargs) as scope:
                    yield scope
            except ValueError as error:
                observed.append((type(error).__name__, str(error)))
                raise
        with patch('oer_server._require_publication_model_proxy'), \
             patch.object(self.backend._remote_models,'session_scope',new=legacy_call):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual([('ValueError','independently pinned profile and execution identities required')],observed)
        self.assertEqual('MODEL_SESSION_INVALID',response.json()['detail']['kind'])
        self.assertEqual([],self.gateway.calls)

    def test_independent_scope_mismatch_rejects_before_gateway_admission(self):
        original=model_session_observer.ModelSessionObserver.begin_session
        def wrong_scope(observer,stage,binding):
            return original(observer,stage,{**binding,'sourceScopeSha256':'f'*64})
        with patch.object(model_session_observer.ModelSessionObserver,'begin_session',new=wrong_scope):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertEqual([],self.gateway.calls)
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_operation_binding_failure_cancels_admitted_session(self):
        with patch.object(model_session_observer.ModelSessionObserver,'bind_operation',
                          side_effect=ValueError('authored operation identity mismatch')), \
             patch.object(self.gateway,'cancel_and_observe',wraps=self.gateway.cancel_and_observe) as cancel:
            response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertEqual(1,len([call for call in self.gateway.calls if call[0]=='start']))
        self.assertEqual(1,cancel.call_count)
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_real_session_client_over_fake_gateway_finishes_before_publication(self):
        gateway=SyntheticGateway();self.backend._remote_models.client=gateway
        with patch.object(remote_gpu_models,'ModelSession',ModelSession):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual('finish',gateway.calls[-1][0])
        metadata=json.loads(self.backend.metadata_path('AUTHORED-remote-pins').read_text(encoding='utf-8'))
        binding=next(iter(gateway.operations.values()))['binding']
        self.assertEqual(metadata['sourceScope']['sourceScopeSha256'],binding['sourceScopeSha256'])

    def test_disconnect_before_batch_acceptance_does_not_publish(self):
        entered=threading.Event();cancelled=threading.Event();workers=[]
        def blocked(body,result):
            workers.append(threading.current_thread());entered.set()
            source_fixture.RemoteCancellationHttpTest.wait_for_cancellation(cancelled)
        self.gateway.on_batch=blocked
        messages=source_fixture.RemoteSourcePageIndexTest.disconnect_at(self,entered)
        self.assertTrue(entered.is_set());self.assertTrue(cancelled.wait(3))
        workers[0].join(3);self.assertFalse(workers[0].is_alive())
        self.assertEqual(503,next(row['status'] for row in messages if row['type']=='http.response.start'))
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())

    def test_new_source_dependency_during_session_refuses_publication(self):
        registry=self.app.state.source_registry
        def expand(body,result):
            registry._validation_frame.get()['locations']['authored-new-locator']=('authored',0,'c'*64)
            registry._validation_frame.get()['normalLocations']['authored-new-locator']=False
        self.gateway.on_batch=expand
        response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(422,response.status_code,response.text)
        self.assertEqual('SOURCE_SCOPE_CHANGED',response.json()['detail']['kind'])
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())


if __name__=='__main__':unittest.main()
