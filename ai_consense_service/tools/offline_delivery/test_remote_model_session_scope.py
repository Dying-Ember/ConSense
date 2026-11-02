"""Request-scoped remote model lifetime at the existing model-method boundary."""
from contextvars import copy_context
import hashlib
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
from remote_gpu_models import RemoteModels
from fixed_gpu_client import OperationError
from request_stage import encoded


class FakeSession:
    calls = []
    fail_release = False
    bad_result = False
    dimension = 2
    def __init__(self, kind, stage, binding, operation, deadline):
        self.kind = kind; self.stage=stage; self.binding=binding; self.operation=operation
        self.deadline=deadline
        self.binding_sha='e'*64; self.expected={'profileSha256':'d'*64}
        self.batches = []; self.total_bytes = 0; self.released = False
        self.exit_receipt = {'status':'release_unknown'}
    @classmethod
    def start(cls, client, stage, binding, deadline, cancelled, accept):
        cls.calls.append(('start', stage, binding, deadline))
        operation=('%032x' % sum(row[0]=='start' for row in cls.calls))
        return cls(binding['modelKind'],stage,binding,operation,deadline)
    def batch(self, body):
        self.calls.append(('batch',self.kind,body))
        result = ({'dimension':self.dimension} if body['kind'] == 'dimension' else
                  {'dimension':2,'vectors':[[.25,.75] for _ in body['texts']]} if body['kind'] == 'embedding' else
                  {'scores':[.7 for _ in body['pairs']]})
        if self.bad_result and body['kind'] == 'embedding': result = {'dimension':2, 'vectors':[[1.0]]}
        self.batches.append({'ordinal':len(self.batches),'inputSha256':hashlib.sha256(encoded(body)).hexdigest(),
                             'outputSha256':hashlib.sha256(encoded(result)).hexdigest()})
        self.total_bytes += len(encoded(body))+len(encoded(result))
        return result
    def finish(self):
        self.calls.append(('finish',self.kind))
        if self.fail_release: raise ValueError('release_unknown')
        summary={'operationId':self.operation,'bindingSha256':'e'*64,'requestId':self.binding['requestId'],
                 'batchCount':len(self.batches),'batchesSha256':hashlib.sha256(encoded(self.batches)).hexdigest(),
                 'totalBytes':self.total_bytes,'status':'computation_completed_release_unverified'}
        source_claims={key:self.binding[key] for key in ('requestId','sourceScopeSha256','modelKind',
            'inferenceCap','deadlineBudgetMs','maxBatches','maxTotalBytes')}
        source_claims.update(modelIdentitySha256=hashlib.sha256(encoded(self.binding['modelIdentity'])).hexdigest(),
                             runtimeSha256=hashlib.sha256(encoded(self.binding['runtime'])).hexdigest())
        self.released = True; self.exit_receipt = {'schema':'consense.fixed-model-session-receipt.v1',
            'operationId':self.operation,'stage':self.stage,'sourceClaims':source_claims,
            'sourceSha256':hashlib.sha256(encoded(self.binding)).hexdigest(),'bodySha256':hashlib.sha256(b'{}').hexdigest(),
            'bindingSha256':self.binding_sha,'profileSha256':self.expected['profileSha256'],
            'batches':list(self.batches),'summary':summary,
            'modelSummarySha256':hashlib.sha256(encoded(summary)).hexdigest(),
            'release':{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}}
        return self.exit_receipt
    def cancel(self):
        self.calls.append(('cancel',self.kind)); return {'status':'release_unknown'}


def setup():
    runtime = SimpleNamespace(logical_identity=lambda kind: {'model':kind}, inference_cap=lambda kind:512)
    backend = SimpleNamespace(MODELS={'embedding':{'name':'embed'},'reranker':{'name':'rank'}},
        MODEL_SETTINGS={'embedding':{'batchSize':2,'maxTokens':512},'reranker':{'batchSize':2,'maxTokens':512}},
        window_runtime=lambda:runtime)
    client=SimpleNamespace(profile_sha256s={'document-embedding':'d'*64,'query-embedding-rerank':'d'*64})
    return RemoteModels(backend, client, dtype='float32', serial_offload=True, timeout=10)


def owner():
    return {'requestId':'a'*32,'deadline':time.monotonic()+5,'cancelled':lambda:False,'accept':lambda action:action()}


class FixtureObserver:
    def __init__(self,proxy): self.records=[]; self.proxy=proxy
    def preflight_session(self,stage,kind):
        runtime=self.proxy.backend.window_runtime()
        return {'modelIdentity':runtime.logical_identity(kind),'runtime':self.proxy.model_runtime(kind),
                'inferenceCap':runtime.inference_cap(kind),'profileSha256':self.proxy.client.profile_sha256s[stage],
                'executionSha256':'f'*64}
    def begin_session(self,stage,binding): pass
    def bind_operation(self,stage,kind,operation): pass
    def prepare(self,metadata,body,result):
        input_bytes=encoded(body); output_bytes=encoded(result)
        return {'ordinal':metadata['ordinal'],'inputSha256':hashlib.sha256(input_bytes).hexdigest(),'inputBytes':len(input_bytes),
                'outputSha256':hashlib.sha256(output_bytes).hexdigest(),'outputBytes':len(output_bytes)}
    def commit(self,metadata,record): self.records.append({**metadata,**record})
    def verify_session(self,receipt):
        rows=[row for row in self.records if row['operationId']==receipt['operationId']]
        batches=[{'ordinal':row['ordinal'],'inputSha256':row['inputSha256'],'outputSha256':row['outputSha256']} for row in rows]
        return bool(rows) and receipt['batches']==batches and receipt['summary']['totalBytes']==sum(
            row['inputBytes']+row['outputBytes'] for row in rows)


class RemoteSessionScopeTest(unittest.TestCase):
    def setUp(self):
        FakeSession.calls=[]; FakeSession.fail_release=False; FakeSession.bad_result=False; FakeSession.dimension=2
        timeout_pin=patch('remote_gpu_models._trusted_stage_timeout',return_value=10); timeout_pin.start(); self.addCleanup(timeout_pin.stop)
        self.patch=patch('remote_gpu_models.ModelSession',FakeSession); self.patch.start(); self.addCleanup(self.patch.stop)
        self.proxy=setup(); self.control=owner()

    def scope(self, stage='query-embedding-rerank', max_batches=4, max_total_bytes=2048):
        return self.proxy.session_scope(self.control,'b'*64,stage,max_batches=max_batches,max_total_bytes=max_total_bytes,
                                        observer=FixtureObserver(self.proxy),execution_sha256='f'*64)

    def test_one_session_per_kind_and_release_before_switch_and_return(self):
        with self.scope() as scope:
            self.assertEqual(self.proxy.embedding_model().get_sentence_embedding_dimension(),2)
            np.testing.assert_allclose(self.proxy.encode_embeddings(['text']),[[.25,.75]])
            np.testing.assert_allclose(self.proxy.rerank_model().predict([['q','x']],batch_size=2,
                show_progress_bar=False,convert_to_numpy=True),[.7])
            self.assertEqual([r['sourceClaims']['modelKind'] for r in scope.receipts],['embedding'])
        self.assertEqual([r['sourceClaims']['modelKind'] for r in scope.receipts],['embedding','reranker'])
        self.assertEqual([x[0] for x in FakeSession.calls],['start','batch','batch','finish','start','batch','finish'])
        starts=[x for x in FakeSession.calls if x[0]=='start']
        self.assertEqual([x[1] for x in starts],['query-embedding-rerank']*2)
        self.assertEqual(starts[0][2]['sourceScopeSha256'],'b'*64)
        self.assertEqual(starts[1][2]['maxBatches'],2)

    def test_two_query_stage_sessions_share_request_deadline(self):
        now=[10.]; self.control['deadline']=20.
        with patch('remote_gpu_models.time.monotonic',side_effect=lambda:now[0]), \
                patch('remote_gpu_models._trusted_stage_timeout',return_value=6):
            with self.scope():
                self.proxy.embedding_model().get_sentence_embedding_dimension()
                now[0]=15.
                self.proxy.rerank_model().predict([['q','x']],batch_size=2,
                    show_progress_bar=False,convert_to_numpy=True)
        starts=[row for row in FakeSession.calls if row[0]=='start']
        self.assertEqual([row[2]['deadlineBudgetMs'] for row in starts],[6000,5000])

    def test_unknown_release_prevents_switch(self):
        with self.assertRaisesRegex(ValueError,'release_unknown'):
            with self.scope():
                self.proxy.embedding_model().get_sentence_embedding_dimension()
                FakeSession.fail_release=True
                self.proxy.rerank_model().predict([['q','x']],batch_size=2,show_progress_bar=False,convert_to_numpy=True)
        self.assertEqual([x[0] for x in FakeSession.calls].count('start'),1)
        with self.assertRaisesRegex(ValueError,'release unknown'):
            with self.scope(): pass
        with self.assertRaisesRegex(ValueError,'release unknown'):
            self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual([x[0] for x in FakeSession.calls].count('start'),1)

    def test_cancel_unblocks_only_after_owned_operation_release_is_proven(self):
        cancelled=threading.Event();self.control['cancelled']=cancelled.is_set
        for released in (True,False):
            proxy=setup();cancelled.clear()
            def finish_cancel(session):
                return {'status':'released' if released else 'release_unknown',
                        'cgroupPopulated':0,'ownership':'delegated_cgroup_v2','processWaited':True,
                        'operationStatus':'cancelled','operationId':session.operation}
            with patch.object(FakeSession,'cancel',finish_cancel):
                with self.assertRaisesRegex(ValueError,'request cancelled'):
                    with proxy.session_scope(self.control,'b'*64,'query-embedding-rerank',max_batches=4,max_total_bytes=2048,
                                             observer=FixtureObserver(proxy),execution_sha256='f'*64):
                        proxy.embedding_model().get_sentence_embedding_dimension();cancelled.set();raise ValueError('request cancelled')
            cancelled.clear()
            if released:
                with proxy.session_scope(self.control,'b'*64,'query-embedding-rerank',max_batches=4,max_total_bytes=2048,
                                         observer=FixtureObserver(proxy),execution_sha256='f'*64): pass
            else:
                with self.assertRaisesRegex(ValueError,'release unknown'):
                    with proxy.session_scope(self.control,'b'*64,'query-embedding-rerank',max_batches=4,max_total_bytes=2048,
                                             observer=FixtureObserver(proxy),execution_sha256='f'*64): pass

    def test_cancel_during_start_requires_matching_operation_release(self):
        cancelled=threading.Event();self.control['cancelled']=cancelled.is_set
        for matches in (True,False):
            proxy=setup();cancelled.clear();operation='1'*32
            def failed_start(*args,**kwargs):
                cancelled.set()
                raise OperationError('cancelled during admission',operation,
                    {'status':'released','cgroupPopulated':0,'ownership':'delegated_cgroup_v2','processWaited':True,
                     'operationStatus':'cancelled','operationId':operation if matches else '2'*32})
            with patch.object(FakeSession,'start',side_effect=failed_start):
                with self.assertRaises(OperationError):
                    with proxy.session_scope(self.control,'b'*64,'query-embedding-rerank',max_batches=4,max_total_bytes=2048,
                                             observer=FixtureObserver(proxy),execution_sha256='f'*64):
                        proxy.embedding_model().get_sentence_embedding_dimension()
            cancelled.clear()
            if matches:
                with proxy.session_scope(self.control,'b'*64,'query-embedding-rerank',max_batches=4,max_total_bytes=2048,
                                         observer=FixtureObserver(proxy),execution_sha256='f'*64): pass
            else:
                with self.assertRaisesRegex(ValueError,'release unknown'):
                    with proxy.session_scope(self.control,'b'*64,'query-embedding-rerank',max_batches=4,max_total_bytes=2048,
                                             observer=FixtureObserver(proxy),execution_sha256='f'*64): pass

    def test_cancel_between_verified_model_sessions_does_not_block_next_request(self):
        cancelled=threading.Event();self.control['cancelled']=cancelled.is_set
        with self.assertRaisesRegex(ValueError,'request cancelled'):
            with self.scope() as scope:
                self.proxy.embedding_model().get_sentence_embedding_dimension();scope.finish()
                cancelled.set();raise ValueError('request cancelled')
        cancelled.clear()
        with self.scope(): pass
    def test_invalid_result_cancels_even_if_caller_catches_error(self):
        with self.assertRaisesRegex(ValueError,'failed'):
            with self.scope():
                FakeSession.bad_result=True
                with self.assertRaises(ValueError): self.proxy.encode_embeddings(['text'])
        self.assertIn(('cancel','embedding'),FakeSession.calls)
        self.assertNotIn(('finish','embedding'),FakeSession.calls)

    def test_request_scope_rejects_cross_thread_and_reentry(self):
        with self.scope():
            with self.assertRaises(ValueError):
                with self.scope(): pass
            copied=copy_context(); errors=[]
            def invoke(use_copy):
                try:
                    if use_copy: copied.run(self.proxy.embedding_model().get_sentence_embedding_dimension)
                    else: self.proxy.embedding_model().get_sentence_embedding_dimension()
                except Exception as error: errors.append(error)
            for use_copy in (False, True):
                thread=threading.Thread(target=invoke,args=(use_copy,)); thread.start(); thread.join(1)
                self.assertFalse(thread.is_alive())
            self.assertEqual(len(errors),2)
            self.assertEqual(FakeSession.calls,[])

    def test_dimension_is_request_scoped_and_ignores_legacy_proxy_dimension(self):
        self.proxy.embedding_model().dimension = 99
        with self.scope('document-embedding'):
            self.assertEqual(self.proxy.embedding_model().get_sentence_embedding_dimension(),2)
            np.testing.assert_allclose(self.proxy.encode_embeddings(['text']),[[.25,.75]])
        FakeSession.dimension=3
        with self.scope('document-embedding'):
            self.assertEqual(self.proxy.embedding_model().get_sentence_embedding_dimension(),3)
        self.assertEqual(self.proxy.embedding_model().dimension,99)

    def test_index_stage_rejects_reranker_and_total_batches_are_bounded(self):
        with self.assertRaises(ValueError):
            with self.scope('document-embedding'):
                self.proxy.rerank_model().predict([['q','x']],batch_size=2,show_progress_bar=False,convert_to_numpy=True)
        self.assertEqual(FakeSession.calls,[])
        with self.assertRaisesRegex(ValueError,'budget'):
            with self.scope(max_batches=1):
                self.proxy.embedding_model().get_sentence_embedding_dimension()
                self.proxy.encode_embeddings(['text'])
        self.assertIn(('cancel','embedding'),FakeSession.calls)

    def test_unknown_admission_blocks_new_scope_and_keeps_unknown_receipt(self):
        saved = []
        with patch.object(FakeSession,'start',side_effect=OperationError('admission unknown',None,
                            {'status':'release_unknown','operationId':None})):
            with self.assertRaises(OperationError):
                with self.scope() as scope:
                    saved.append(scope)
                    self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(saved[0].failure_receipt['status'],'release_unknown')
        with self.assertRaisesRegex(ValueError,'release unknown'):
            with self.scope(): pass

    def test_finished_scope_cannot_admit_a_later_model(self):
        with self.scope() as scope: pass
        with self.assertRaises(ValueError): scope.compute('embedding',{'kind':'dimension'})
        self.assertEqual(FakeSession.calls,[])

    def test_explicit_scope_mode_rejects_legacy_fallback(self):
        self.proxy.require_request_scope()
        with self.assertRaisesRegex(ValueError,'scope required'):
            self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(FakeSession.calls,[])
        with self.scope('document-embedding'):
            self.assertEqual(self.proxy.embedding_model().get_sentence_embedding_dimension(),2)

    def test_dimension_changes_within_one_session_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'dimension changed'):
            with self.scope('document-embedding'):
                self.assertEqual(self.proxy.embedding_model().get_sentence_embedding_dimension(),2)
                FakeSession.dimension=3
                self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertIn(('cancel','embedding'),FakeSession.calls)

    def test_reranker_to_embedding_and_repeated_embedding_are_rejected(self):
        for first_embedding in (False,True):
            with self.subTest(first_embedding=first_embedding):
                proxy=setup(); FakeSession.calls=[]
                with self.assertRaisesRegex(ValueError,'model session order'):
                    with proxy.session_scope(owner(),'b'*64,'query-embedding-rerank',max_batches=5,max_total_bytes=2048):
                        if first_embedding: proxy.embedding_model().get_sentence_embedding_dimension()
                        proxy.rerank_model().predict([['q','x']],batch_size=2,show_progress_bar=False,convert_to_numpy=True)
                        proxy.embedding_model().get_sentence_embedding_dimension()
                starts=[x[2]['modelKind'] for x in FakeSession.calls if x[0]=='start']
                self.assertEqual(starts,['embedding','reranker'] if first_embedding else ['reranker'])

    def test_identity_elapsed_time_is_deducted_before_admission(self):
        for end in (17.,21.):
            with self.subTest(identity_finished=end):
                now=[10.]; proxy=setup(); control=owner(); control['deadline']=20.; FakeSession.calls=[]
                runtime=proxy.backend.window_runtime()
                def identity(kind): now[0]=end; return {'model':kind}
                runtime.logical_identity=identity; proxy.backend.window_runtime=lambda:runtime
                with patch('remote_gpu_models.time.monotonic',side_effect=lambda:now[0]):
                    if end > control['deadline']:
                        with self.assertRaisesRegex(ValueError,'expired'):
                            with proxy.session_scope(control,'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024):
                                proxy.embedding_model().get_sentence_embedding_dimension()
                        self.assertEqual(FakeSession.calls,[])
                    else:
                        with proxy.session_scope(control,'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024):
                            proxy.embedding_model().get_sentence_embedding_dimension()
                        self.assertEqual(FakeSession.calls[0][2]['deadlineBudgetMs'],3000)

    def test_legacy_unknown_release_blocks_next_admission_and_preserves_error(self):
        failure=OperationError('GPU outcome unknown','b'*32,{'status':'release_unknown','operationId':'b'*32})
        class Client:
            def __init__(self): self.calls=0
            def compute(self,*args,**kwargs): self.calls+=1; raise failure
        client=Client(); proxy=setup(); proxy.client=client
        runtime=proxy.backend.window_runtime(); runtime.emit=lambda *args: (_ for _ in ()).throw(RuntimeError('emit failed'))
        proxy.backend.window_runtime=lambda:runtime
        with self.assertRaises(OperationError) as caught: proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertIs(caught.exception,failure); self.assertEqual(caught.exception.exit_receipt['status'],'release_unknown')
        with self.assertRaisesRegex(ValueError,'release unknown'):
            proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(client.calls,1)

    def test_interrupted_scope_cleanup_keeps_unknown_and_original_failure(self):
        saved=[]
        def interrupted(session):
            with self.assertRaisesRegex(ValueError,'release unknown'):
                self.proxy.embedding_model().get_sentence_embedding_dimension()
            raise KeyboardInterrupt('cleanup interrupted')
        with patch.object(FakeSession,'cancel',interrupted):
            with self.assertRaisesRegex(ValueError,'original failure'):
                with self.scope() as scope:
                    saved.append(scope); self.proxy.embedding_model().get_sentence_embedding_dimension()
                    raise ValueError('original failure')
        self.assertEqual(saved[0].failure_receipt['status'],'release_unknown')
        with self.assertRaisesRegex(ValueError,'release unknown'):
            with self.scope(): pass

    def test_legacy_unwrapped_exception_preserves_error_and_blocks_following_admission(self):
        for failure in (ValueError('unwrapped failure'),KeyboardInterrupt('interrupted')):
            with self.subTest(error=type(failure).__name__):
                class Client:
                    calls=0
                    def compute(self,*args,**kwargs): self.calls+=1; raise failure
                client=Client(); proxy=setup(); proxy.client=client
                with self.assertRaises(type(failure)) as caught:
                    proxy.embedding_model().get_sentence_embedding_dimension()
                self.assertIs(caught.exception,failure)
                with self.assertRaisesRegex(ValueError,'release unknown'):
                    proxy.embedding_model().get_sentence_embedding_dimension()
                self.assertEqual(client.calls,1)

    def test_legacy_self_reported_release_on_failure_never_unblocks(self):
        class Client:
            calls=0
            def compute(self,*args,**kwargs):
                self.calls+=1
                raise OperationError('failed','b'*32,{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]})
        client=Client(); proxy=setup(); proxy.client=client
        with self.assertRaises(OperationError): proxy.embedding_model().get_sentence_embedding_dimension()
        with self.assertRaisesRegex(ValueError,'release unknown'):
            proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(client.calls,1)

    def test_observer_identity_preflight_and_binding_rejection_precede_admission(self):
        for field in ('source','profile','model','cap','binding'):
            with self.subTest(field=field):
                proxy=setup(); observer=FixtureObserver(proxy); calls=[]; FakeSession.calls=[]
                original=observer.preflight_session
                def preflight(stage,kind):
                    calls.append('preflight')
                    if field!='binding': raise ValueError('independent '+field+' mismatch')
                    return original(stage,kind)
                def begin(stage,binding): calls.append('begin'); raise ValueError('independent binding mismatch')
                observer.preflight_session=preflight; observer.begin_session=begin
                with self.assertRaisesRegex(ValueError,'independent'):
                    with proxy.session_scope(owner(),'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,observer=observer,execution_sha256='f'*64):
                        proxy.embedding_model().get_sentence_embedding_dimension()
                self.assertEqual(FakeSession.calls,[])
                self.assertEqual(calls,['preflight','begin'] if field=='binding' else ['preflight'])

    def test_preflight_profile_and_execution_mismatch_never_admit(self):
        for field in ('profileSha256','executionSha256'):
            with self.subTest(field=field):
                proxy=setup(); observer=FixtureObserver(proxy); FakeSession.calls=[]; original=observer.preflight_session
                def wrong(stage,kind): return {**original(stage,kind),field:'0'*64}
                observer.preflight_session=wrong
                with self.assertRaisesRegex(ValueError,'preflight differs'):
                    with proxy.session_scope(owner(),'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,
                                             observer=observer,execution_sha256='f'*64):
                        proxy.embedding_model().get_sentence_embedding_dimension()
                self.assertEqual(FakeSession.calls,[])
        proxy=setup(); observer=FixtureObserver(proxy); FakeSession.calls=[]
        with self.assertRaisesRegex(ValueError,'pinned profile and execution'):
            with proxy.session_scope(owner(),'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,observer=observer): pass
        self.assertEqual(FakeSession.calls,[])

    def test_profile_drift_before_post_is_rejected_and_after_admission_is_cancelled(self):
        proxy=setup(); observer=FixtureObserver(proxy); FakeSession.calls=[]
        observer.begin_session=lambda stage,binding:proxy.client.profile_sha256s.update({stage:'0'*64})
        with self.assertRaisesRegex(ValueError,'profile changed before admission'):
            with proxy.session_scope(owner(),'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,
                                     observer=observer,execution_sha256='f'*64):
                proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(FakeSession.calls,[])
        proxy=setup(); observer=FixtureObserver(proxy); FakeSession.calls=[]; original=FakeSession.start
        def drift(*args):
            session=original(*args); session.expected['profileSha256']='0'*64; return session
        with patch.object(FakeSession,'start',side_effect=drift):
            with self.assertRaisesRegex(ValueError,'admitted model stage profile differs'):
                with proxy.session_scope(owner(),'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,
                                         observer=observer,execution_sha256='f'*64):
                    proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual([row[0] for row in FakeSession.calls],['start','cancel'])

    def test_observer_bind_failure_cancels_known_operation(self):
        proxy=setup(); observer=FixtureObserver(proxy); FakeSession.calls=[]
        def reject(stage,kind,operation): raise ValueError('observer operation binding failed')
        observer.bind_operation=reject
        with self.assertRaisesRegex(ValueError,'operation binding failed'):
            with proxy.session_scope(owner(),'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,observer=observer,execution_sha256='f'*64):
                proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual([row[0] for row in FakeSession.calls],['start','cancel'])
        with self.assertRaisesRegex(ValueError,'release unknown'):
            proxy.embedding_model().get_sentence_embedding_dimension()

    def test_observer_identity_is_fingerprinted_once_per_request_kind(self):
        proxy=setup(); runtime=proxy.backend.window_runtime(); calls=[]
        runtime.logical_identity=lambda kind: (calls.append(kind) or {'model':kind})
        proxy.backend.window_runtime=lambda:runtime
        observer=FixtureObserver(proxy)
        with proxy.session_scope(owner(),'b'*64,'document-embedding',max_batches=3,max_total_bytes=1024,observer=observer,execution_sha256='f'*64):
            proxy.embedding_model().get_sentence_embedding_dimension()
            proxy.encode_embeddings(['x'])
        self.assertEqual(calls,['embedding'])

    def test_observer_preflight_or_begin_crossing_deadline_never_admits(self):
        for phase in ('preflight','begin'):
            with self.subTest(phase=phase):
                now=[10.]; proxy=setup(); control=owner(); control['deadline']=20.; observer=FixtureObserver(proxy)
                FakeSession.calls=[]; original_preflight=observer.preflight_session
                def preflight(stage,kind):
                    snapshot=original_preflight(stage,kind)
                    if phase=='preflight': now[0]=21.
                    return snapshot
                def begin(stage,binding):
                    if phase=='begin': now[0]=21.
                observer.preflight_session=preflight; observer.begin_session=begin
                with patch('remote_gpu_models.time.monotonic',side_effect=lambda:now[0]):
                    with self.assertRaisesRegex(ValueError,'expired'):
                        with proxy.session_scope(control,'b'*64,'document-embedding',max_batches=2,max_total_bytes=1024,observer=observer,execution_sha256='f'*64):
                            proxy.embedding_model().get_sentence_embedding_dimension()
                self.assertEqual(FakeSession.calls,[])

    def test_legacy_lease_rejects_overlapping_scope_mode_change_and_legacy(self):
        entered=threading.Event(); resume=threading.Event(); failures=[]; results=[]; calls=[]
        class Client:
            profile_sha256s={'document-embedding':'d'*64,'query-embedding-rerank':'d'*64}
            def compute(self,*args,**kwargs):
                calls.append('compute'); entered.set(); resume.wait(2)
                return {'dimension':2}
        self.proxy.client=Client()
        def legacy():
            try: results.append(self.proxy.embedding_model().get_sentence_embedding_dimension())
            except Exception as error: failures.append(error)
        thread=threading.Thread(target=legacy); thread.start()
        try:
            self.assertTrue(entered.wait(1))
            with self.assertRaisesRegex(ValueError,'operation already active'):
                with self.scope(): pass
            with self.assertRaisesRegex(ValueError,'operation already active'):
                self.proxy.require_request_scope()
            with self.assertRaisesRegex(ValueError,'operation is active'):
                self.proxy.embedding_model().get_sentence_embedding_dimension()
            self.assertEqual(calls,['compute']); self.assertEqual(FakeSession.calls,[])
        finally:
            resume.set(); thread.join(2)
        self.assertFalse(thread.is_alive()); self.assertEqual(results,[2]); self.assertEqual(failures,[])
        self.proxy.require_request_scope()
        with self.scope('document-embedding'):
            self.assertEqual(self.proxy.embedding_model().get_sentence_embedding_dimension(),2)


if __name__ == '__main__': unittest.main()
