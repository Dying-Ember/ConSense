import unittest
import time
import hashlib
import json
from types import SimpleNamespace
from unittest.mock import patch

from model_session_observer import ModelSessionObserver
import model_session_observer
from token_windows import WindowError


class ModelSessionObserverTest(unittest.TestCase):
    def setUp(self):
        fake=patch.object(model_session_observer, '_trusted_stage_pins',
                          side_effect=lambda stage: {'profileSha256':'a'*64, 'executionSha256':'d'*64})
        fake.start(); self.addCleanup(fake.stop)

    def observer(self, stage='document-embedding'):
        runtime=SimpleNamespace(logical_identity=lambda kind:{'model':kind},inference_cap=lambda kind:512)
        proxy=SimpleNamespace(backend=SimpleNamespace(window_runtime=lambda:runtime),timeout=10,
                              model_runtime=lambda kind:{'device':'cuda'},client=SimpleNamespace(profile_sha256s={stage:'a'*64}))
        control={'requestId':'a'*32,'deadline':time.monotonic()+10}
        observer=ModelSessionObserver(control,'b'*64,stage,proxy,4,1024)
        observer.preflight_session(stage,'embedding')
        observer.begin_session(stage,{'requestId':'a'*32,'sourceScopeSha256':'b'*64,
            'modelKind':'embedding','modelIdentity':{'model':'embedding'},'runtime':{'device':'cuda'},
            'inferenceCap':512,'deadlineBudgetMs':5000,'maxBatches':4,'maxTotalBytes':1024})
        observer.bind_operation(stage,'embedding','a'*32)
        return observer

    def receipt(self, observer, operation='a'*32):
        def encoded(value):
            return json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()
        def sha(value): return hashlib.sha256(encoded(value)).hexdigest()
        binding=observer.bindings[observer.operation_kinds[operation]]
        rows=[row for row in observer.records if row['operationId']==operation]
        batches=[{key:row[key] for key in ('ordinal','inputSha256','outputSha256')} for row in rows]
        claims={name:binding[name] for name in ('requestId','sourceScopeSha256','modelKind','inferenceCap',
                                                'deadlineBudgetMs','maxBatches','maxTotalBytes')}
        claims.update(modelIdentitySha256=sha(binding['modelIdentity']),runtimeSha256=sha(binding['runtime']))
        summary={'operationId':operation,'bindingSha256':'c'*64,'requestId':binding['requestId'],
                 'batchCount':len(rows),'batchesSha256':sha(batches),
                 'totalBytes':sum(row['inputBytes']+row['outputBytes'] for row in rows),
                 'status':'computation_completed_release_unverified'}
        return {'schema':'consense.fixed-model-session-receipt.v1','operationId':operation,'stage':observer.stage,
                'sourceClaims':claims,'bodySha256':hashlib.sha256(b'{}').hexdigest(),'sourceSha256':sha(binding),
                'profileSha256':'a'*64,'bindingSha256':'c'*64,'modelSummarySha256':sha(summary),
                'summary':summary,'batches':batches,'release':{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}}

    def test_observes_bytes_without_retaining_them_and_fails_closed_on_receipt(self):
        observer=self.observer()
        metadata={'scopeOrdinal':0,'ordinal':0,'modelKind':'embedding','operationId':'a'*32}
        prepared=observer.prepare(metadata,{'input':'private'},{'result':1})
        observer.commit(metadata,prepared)
        self.assertEqual(1,len(observer.records))
        self.assertNotIn('private',str(observer.records))
        with self.assertRaises(WindowError):observer.verify_session({})
        with self.assertRaises(WindowError):observer.commit(metadata,prepared)

    def test_rejects_wrong_order_and_changed_observation(self):
        observer=self.observer()
        metadata={'scopeOrdinal':1,'ordinal':0,'modelKind':'embedding','operationId':'a'*32}
        with self.assertRaises(WindowError):observer.prepare(metadata,{}, {})
        metadata['scopeOrdinal']=0;prepared=observer.prepare(metadata,{}, {})
        with self.assertRaises(WindowError):observer.commit(metadata,{**prepared,'inputBytes':999})
        observer.commit(metadata,prepared)
        with self.assertRaises(WindowError):observer.prepare({**metadata,'scopeOrdinal':1,'ordinal':0}, {}, {})
        with self.assertRaises(WindowError):observer.prepare({**metadata,'scopeOrdinal':1,'ordinal':1,'operationId':'bad'}, {}, {})

    def test_preflight_and_operation_binding_are_required(self):
        observer=self.observer()
        with self.assertRaises(WindowError): observer.preflight_session(observer.stage,'embedding')
        with self.assertRaises(WindowError): observer.bind_operation(observer.stage,'embedding','b'*32)
        with self.assertRaises(WindowError): observer.prepare({'scopeOrdinal':0,'ordinal':0,'modelKind':'embedding',
                                                                 'operationId':'b'*32},{},{})
        with self.assertRaises(WindowError): observer.verify_session({'operationId': [], 'sourceClaims':{'modelKind':'embedding'}})

    def test_independent_preflight_rejects_binding_before_admission(self):
        observer=self.observer()
        candidate=ModelSessionObserver(observer.control,observer.source_sha,observer.stage,observer.proxy,4,1024)
        binding=observer.bindings['embedding']
        with self.assertRaises(WindowError): candidate.begin_session(candidate.stage,binding)
        observed=candidate.preflight_session(candidate.stage,'embedding')
        observed['modelIdentity']['model']='modified-external-copy'
        for change in ({'sourceScopeSha256':'c'*64}, {'modelIdentity':{'model':'other'}},
                       {'runtime':{'device':'cpu'}}, {'inferenceCap':513}):
            with self.subTest(change=change), self.assertRaises(WindowError):
                candidate.begin_session(candidate.stage,{**binding,**change})
        candidate.begin_session(candidate.stage,binding)
        with self.assertRaises(WindowError):
            candidate.prepare({'scopeOrdinal':0,'ordinal':0,'modelKind':'embedding','operationId':'a'*32},{},{})
        candidate.bind_operation(candidate.stage,'embedding','b'*32)
        with self.assertRaises(WindowError):candidate.bind_operation(candidate.stage,'embedding','b'*32)

    def test_preflight_identity_is_observed_once_and_binding_uses_snapshot(self):
        observer=self.observer()
        runtime=observer.proxy.backend.window_runtime()
        calls=[]
        runtime.logical_identity=lambda kind:(calls.append(kind) or {'model':kind})
        candidate=ModelSessionObserver(observer.control,observer.source_sha,observer.stage,observer.proxy,4,1024)
        observed=candidate.preflight_session(candidate.stage,'embedding')
        self.assertEqual(['embedding'],calls)
        candidate.begin_session(candidate.stage,observer.bindings['embedding'])
        self.assertEqual(['embedding'],calls)
        self.assertEqual({'model':'embedding'},observed['modelIdentity'])

    def test_missing_or_malformed_independent_pins_fail_before_model_identity(self):
        observer=self.observer()
        for pins in (None, {}, {'profileSha256':'a'*64,'executionSha256':'bad'}):
            candidate=ModelSessionObserver(observer.control,observer.source_sha,observer.stage,observer.proxy,4,1024)
            with patch.object(model_session_observer,'_trusted_stage_pins',return_value=pins), \
                 patch.object(observer.proxy.backend.window_runtime(),'logical_identity') as identity:
                with self.assertRaises(WindowError): candidate.preflight_session(candidate.stage,'embedding')
                identity.assert_not_called()

    def test_preflight_returns_independent_pins_and_is_copy_isolated(self):
        observer=self.observer()
        preflight=observer.preflights['embedding']
        self.assertEqual({'modelIdentity','runtime','inferenceCap','profileSha256','executionSha256'},set(preflight))
        self.assertEqual('a'*64,preflight['profileSha256'])
        self.assertEqual('d'*64,observer.trusted_execution_sha256())

    def test_two_distinct_operations_cannot_swap_or_reuse_identity(self):
        observer=self.observer('query-embedding-rerank')
        first={'scopeOrdinal':0,'ordinal':0,'modelKind':'embedding','operationId':'a'*32}
        observer.commit(first,observer.prepare(first,{'kind':'dimension'},{'dimension':2}))
        preflight=observer.preflight_session(observer.stage,'reranker')
        binding={'requestId':'a'*32,'sourceScopeSha256':'b'*64,'modelKind':'reranker',
                 **{key:preflight[key] for key in ('modelIdentity','runtime','inferenceCap')},
                 'deadlineBudgetMs':4000,'maxBatches':3,'maxTotalBytes':512}
        observer.begin_session(observer.stage,binding)
        with self.assertRaises(WindowError):observer.bind_operation(observer.stage,'reranker','a'*32)
        observer.bind_operation(observer.stage,'reranker','b'*32)
        second={'scopeOrdinal':1,'ordinal':0,'modelKind':'reranker','operationId':'b'*32}
        with self.assertRaises(WindowError): observer.prepare({**second,'operationId':'a'*32},{},{})
        with self.assertRaises(WindowError): observer.prepare({**second,'ordinal':1},{},{})
        observer.commit(second,observer.prepare(second,{'kind':'reranker'},{'scores':[1]}))
        self.assertEqual([('a'*32,0),('b'*32,0)],[(r['operationId'],r['ordinal']) for r in observer.records])
        with patch.object(observer,'_verify_gpu_release',return_value=True):
            self.assertTrue(observer.verify_session(self.receipt(observer,'a'*32)))
            self.assertTrue(observer.verify_session(self.receipt(observer,'b'*32)))
            with self.assertRaises(WindowError):observer.verify_session(self.receipt(observer,'a'*32))

    def test_missing_batch_and_failed_release_cannot_be_replayed(self):
        observer=self.observer()
        for ordinal in range(2):
            metadata={'scopeOrdinal':ordinal,'ordinal':ordinal,'modelKind':'embedding','operationId':'a'*32}
            observer.commit(metadata,observer.prepare(metadata,{'n':ordinal},{'n':ordinal}))
        complete=self.receipt(observer)
        incomplete={**complete,'batches':complete['batches'][:1]}
        with self.assertRaises(WindowError): observer.verify_session(incomplete)
        with self.assertRaises(WindowError): observer.verify_session(complete)
        observer=self.observer()
        metadata={'scopeOrdinal':0,'ordinal':0,'modelKind':'embedding','operationId':'a'*32}
        observer.commit(metadata,observer.prepare(metadata,{'n':0},{'n':0}))
        complete=self.receipt(observer)
        self.assertFalse(observer.verify_session(complete))
        with patch.object(observer,'_verify_gpu_release',return_value=True):
            with self.assertRaises(WindowError):observer.verify_session(complete)


if __name__=='__main__':unittest.main()
