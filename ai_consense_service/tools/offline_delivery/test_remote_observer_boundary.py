"""03 source/control/receipt API joined to 04 methods with a fake model process."""
from contextvars import ContextVar
import hashlib
import json
from pathlib import Path
import sys
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'vetting_eval'))
from model_session_observer import ModelSessionObserver
from source_request_context import RequestControl, model_session_control, request_context
from source_session_scope import source_scope_sha256
from test_remote_model_session_scope import FakeSession, setup


def make_context():
    frame=ContextVar('observer_fixture_frame'); cas=ContextVar('observer_fixture_cas')
    frame.set({'locations':{'fixture-source':['fixture-id',0,'c'*64]},'normalLocations':{'fixture-source':True},
               'familyContext':None,'softwareIdentity':{'fixture':'pinned'}})
    cas.set({('fixture-cas','d'*64,7):True})
    registry=SimpleNamespace(_validation_frame=frame,artifact_store=SimpleNamespace(_read_session=cas))
    source=source_scope_sha256(registry,'project','vetting',['e'*64],{},[{'content':'source fixture'}],'a'*64)
    control=RequestControl(5)
    with request_context(control): owner=model_session_control()
    return control,owner,source


class ObserverBoundaryTest(unittest.TestCase):
    def setUp(self):
        FakeSession.calls=[]; FakeSession.fail_release=False; FakeSession.bad_result=False; FakeSession.dimension=2
        timeout_pin=patch('remote_gpu_models._trusted_stage_timeout',return_value=10); timeout_pin.start(); self.addCleanup(timeout_pin.stop)
        self.patcher=patch('remote_gpu_models.ModelSession',FakeSession); self.patcher.start(); self.addCleanup(self.patcher.stop)
        # Synthetic pins exercise the observer without claiming production trust.
        pins=patch('model_session_observer._trusted_stage_pins', return_value={'profileSha256':'d'*64,'executionSha256':'f'*64})
        pins.start(); self.addCleanup(pins.stop)
        self.proxy=setup(); self.proxy.client=SimpleNamespace(profile_sha256s={'query-embedding-rerank':'d'*64})
        self.proxy.require_request_scope()
        self.control,self.owner,self.source=make_context()
        self.observer=ModelSessionObserver(self.owner,self.source,'query-embedding-rerank',self.proxy,5,4096)
        self.observer._verify_gpu_release=lambda receipt:True

    def scope(self,observer=None):
        return self.proxy.session_scope(self.owner,self.source,'query-embedding-rerank',max_batches=5,
                                        max_total_bytes=4096,observer=self.observer if observer is None else observer,
                                        execution_sha256='f'*64)

    def test_actual_03_descriptors_reconcile_two_sequential_kinds_without_text(self):
        with self.scope() as scope:
            self.assertEqual(self.proxy.embedding_model().get_sentence_embedding_dimension(),2)
            self.proxy.encode_embeddings(['secret-input-sentinel'])
            self.proxy.rerank_model().predict([['question-sentinel','source-sentinel']],batch_size=2,
                show_progress_bar=False,convert_to_numpy=True)
        self.assertEqual([r['scopeOrdinal'] for r in self.observer.records],[0,1,2])
        self.assertEqual([r['ordinal'] for r in self.observer.records],[0,1,0])
        self.assertEqual([r['modelKind'] for r in self.observer.records],['embedding','embedding','reranker'])
        self.assertEqual(len(scope.receipts),2); self.assertEqual(len(self.observer.receipt_checks),2)
        self.assertTrue(all(not item['gpuReleaseIndependentlyVerified'] for item in self.observer.receipt_checks))
        private_json=json.dumps(self.observer.records)
        for sentinel in ('secret-input-sentinel','question-sentinel','source-sentinel','0.25','0.75'):
            self.assertNotIn(sentinel,private_json)
            self.assertNotIn(sentinel,json.dumps(scope.observations))

    def test_observer_begin_failure_prevents_admission(self):
        def reject(stage,binding): raise RuntimeError('observer binding rejected')
        self.observer.begin_session=reject
        with self.assertRaisesRegex(RuntimeError,'binding rejected'):
            with self.scope(): self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(FakeSession.calls,[])

    def test_observer_cannot_mutate_validated_session_receipt(self):
        original=self.observer.verify_session
        def mutate(receipt):
            result=original(receipt); receipt['batches'][0]['outputSha256']='f'*64; return result
        self.observer.verify_session=mutate
        with self.scope() as scope: self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(scope.receipts[0]['batches'][0]['outputSha256'],self.observer.records[0]['outputSha256'])

    def test_forced_scope_without_observer_rejects_before_admission(self):
        with self.assertRaisesRegex(ValueError,'observer required'):
            with self.proxy.session_scope(self.owner,self.source,'document-embedding',max_batches=2,max_total_bytes=1024): pass
        self.assertEqual(FakeSession.calls,[])

    def test_observer_tampered_digest_or_bytes_fails_and_never_retries(self):
        for field in ('inputSha256','outputSha256','inputBytes','outputBytes'):
            with self.subTest(field=field):
                proxy=setup(); proxy.client=SimpleNamespace(profile_sha256s={'query-embedding-rerank':'d'*64}); proxy.require_request_scope()
                observer=ModelSessionObserver(self.owner,self.source,'query-embedding-rerank',proxy,2,1024)
                observer._verify_gpu_release=lambda receipt:True
                original=observer.prepare
                def bad(metadata,body,result):
                    row=original(metadata,body,result); row[field]='0'*64 if field.endswith('Sha256') else row[field]+1; return row
                observer.prepare=bad; FakeSession.calls=[]
                with self.assertRaisesRegex(ValueError,'observation differs'):
                    with proxy.session_scope(self.owner,self.source,'query-embedding-rerank',max_batches=2,max_total_bytes=1024,
                                             observer=observer,execution_sha256='f'*64):
                        proxy.embedding_model().get_sentence_embedding_dimension()
                self.assertEqual(observer.records,[]); self.assertEqual([r[0] for r in FakeSession.calls],['start','batch','cancel'])

    def test_cancellation_between_preparation_and_commit_rejects_record(self):
        original=self.observer.prepare
        def cancel(metadata,body,result):
            row=original(metadata,body,result); self.control.request_cancel(); return row
        self.observer.prepare=cancel
        with self.assertRaises(ValueError):
            with self.scope(): self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(self.observer.records,[])

    def test_session_deadline_elapsed_before_observation_is_not_accepted(self):
        original=self.observer.prepare; current=[]
        def expire(metadata,body,result):
            row=original(metadata,body,result); current[0].session.deadline=time.monotonic()-1; return row
        self.observer.prepare=expire
        with self.assertRaisesRegex(ValueError,'deadline elapsed'):
            with self.scope() as scope:
                current.append(scope); self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(self.observer.records,[])

    def test_observer_commit_failure_and_caught_error_still_refuse_scope(self):
        def reject(metadata,descriptor): raise RuntimeError('observer storage failed')
        self.observer.commit=reject
        with self.assertRaisesRegex(ValueError,'scope failed'):
            with self.scope():
                with self.assertRaisesRegex(RuntimeError,'storage failed'):
                    self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(self.observer.records,[]); self.assertIn(('cancel','embedding'),FakeSession.calls)

    def test_consistent_gateway_forgery_rejected_against_observed_hash(self):
        original=FakeSession.finish
        def forged(session):
            receipt=original(session); receipt['batches'][0]['outputSha256']='f'*64
            receipt['summary']['batchesSha256']=hashlib.sha256(json.dumps(receipt['batches'],sort_keys=True,
                ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
            receipt['modelSummarySha256']=hashlib.sha256(json.dumps(receipt['summary'],sort_keys=True,
                ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
            return receipt
        with patch.object(FakeSession,'finish',forged):
            with self.assertRaisesRegex(ValueError,'observations differ'):
                with self.scope(): self.proxy.embedding_model().get_sentence_embedding_dimension()
        self.assertEqual(len(self.observer.records),1); self.assertEqual(self.observer.receipt_checks,[])

    def test_boolean_ordinal_cannot_equal_integer_zero(self):
        original=FakeSession.finish
        def forged(session):
            receipt=original(session); receipt['batches'][0]['ordinal']=False
            receipt['summary']['batchesSha256']=hashlib.sha256(json.dumps(receipt['batches'],sort_keys=True,
                ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
            receipt['modelSummarySha256']=hashlib.sha256(json.dumps(receipt['summary'],sort_keys=True,
                ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
            return receipt
        with patch.object(FakeSession,'finish',forged):
            with self.assertRaisesRegex(ValueError,'observations differ'):
                with self.scope(): self.proxy.embedding_model().get_sentence_embedding_dimension()

    def test_self_reported_release_after_independent_rejection_never_unblocks(self):
        self.observer._verify_gpu_release=lambda receipt:False
        forged={'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}
        with patch.object(FakeSession,'cancel',return_value=forged):
            with self.assertRaisesRegex(ValueError,'observer rejected receipt'):
                with self.scope(): self.proxy.embedding_model().get_sentence_embedding_dimension()
        starts=sum(row[0]=='start' for row in FakeSession.calls)
        with self.assertRaisesRegex(ValueError,'release unknown'):
            with self.scope(): pass
        self.assertEqual(sum(row[0]=='start' for row in FakeSession.calls),starts)


if __name__ == '__main__': unittest.main()
