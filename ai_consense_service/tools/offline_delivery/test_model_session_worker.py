"""Public model-session seam tests with fake models only."""
import base64
import json
import os
import signal
import socket
import struct
import sys
import tempfile
import threading
import time
from pathlib import Path
from types import SimpleNamespace
import unittest

from request_stage import encoded, envelope
from model_session_worker import ModelSession, digest, serve_assigned


class Rows:
    def __init__(self, values): self.values = values
    def tolist(self): return self.values
    def reshape(self, _): return self


class FakeBackend:
    def __init__(self):
        self.identity = {'model':'authored', 'files':[{'sha256':'a'*64}]}
        self.runtime = {'device':'cuda', 'dtype':'float32', 'batchSize':2}
        self.MODEL_SETTINGS = {'reranker':{'batchSize':2}}
        self.calls = []; self.result = None
    def window_runtime(self): return self
    def logical_identity(self, _): return self.identity
    def inference_cap(self, _): return 512
    def model_runtime(self, _): return self.runtime
    def embedding_model(self):
        self.calls.append('dimension')
        return SimpleNamespace(get_sentence_embedding_dimension=lambda: 2)
    def encode_embeddings(self, texts):
        self.calls.append(('embedding', texts))
        return Rows(self.result if self.result is not None else [[.25,.75] for _ in texts])
    def rerank_model(self):
        def predict(pairs, **options):
            self.calls.append(('reranker', pairs, options))
            return Rows(self.result if self.result is not None else [.7 for _ in pairs])
        return SimpleNamespace(predict=predict)


def batch(ordinal, value):
    raw = encoded(value)
    return {'ordinal':ordinal, 'inputSha256':digest(raw), 'bodyBase64':base64.b64encode(raw).decode()}


class ModelSessionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name); self.backend = FakeBackend(); self.cancel = threading.Event()

    def make(self, kind='embedding', stage=None, **overrides):
        source = {'requestId':'1'*32, 'sourceScopeSha256':'2'*64, 'modelKind':kind,
                  'modelIdentity':self.backend.identity, 'runtime':self.backend.runtime, 'inferenceCap':512,
                  'deadlineBudgetMs':10000, 'maxBatches':5, 'maxTotalBytes':10000}
        source.update(overrides)
        profile = {'adapter':'consense.model-session.v1', 'fixture':'declared'}
        operation = envelope(stage or ('document-embedding' if kind == 'embedding' else 'query-embedding-rerank'),
                             b'{}', source, profile)
        raw = encoded(operation)
        session = ModelSession(raw, self.root/'session', operation_sha256=digest(raw),
            profile_sha256=digest(encoded(profile)), deadline=time.monotonic()+10,
            cancelled=self.cancel.is_set, backend=self.backend)
        return session

    def test_session_byte_limit_accepts_four_gib_and_rejects_more(self):
        self.make(maxTotalBytes=4 * 1024**3)
        with self.assertRaises(ValueError):
            self.make(maxTotalBytes=4 * 1024**3 + 1)

    def test_embedding_batches_are_bound_persisted_and_finish_is_not_release(self):
        session = self.make(); first = batch(0, {'kind':'dimension'})
        r0 = session.submit(first); r1 = session.submit(batch(1, {'kind':'embedding','texts':['one','two']}))
        self.assertEqual(r0['result'], {'dimension':2}); self.assertTrue(r1['provisional'])
        self.assertEqual(r1['result']['vectors'], [[.25,.75],[.25,.75]])
        self.assertEqual(session.read_completed(0, first['inputSha256']), r0)
        rows = [{key:r[key] for key in ('ordinal','inputSha256','outputSha256')} for r in (r0,r1)]
        summary = session.finish(rows)
        self.assertEqual(summary['status'],'computation_completed_release_unverified')
        self.assertNotIn('release',summary); self.assertEqual(summary['batchCount'],2)
        self.assertEqual(json.loads((self.root/'session'/'finished.json').read_bytes()), summary)
        with self.assertRaises(ValueError): session.submit(batch(2, {'kind':'dimension'}))

    def test_reranker_and_stage_kind_validation(self):
        session = self.make('reranker')
        result = session.submit(batch(0, {'kind':'reranker','pairs':[['q','a'],['q','b']]}))
        self.assertEqual(result['result'], {'scores':[.7,.7]})
        self.assertEqual(self.backend.calls[0][1], [['q','a'],['q','b']])
        self.assertEqual(self.backend.calls[0][2], {'batch_size':2,'show_progress_bar':False,'convert_to_numpy':True})

    def test_fixed_reference_stage_accepts_embedding_session(self):
        session = self.make('embedding', stage='fixed-reference-query')
        from unittest.mock import patch
        with patch('model_session_worker.fixed_reference_encode', return_value=Rows([[.25,.75]])) as encode:
            result = session.submit(batch(0, {'kind':'embedding', 'texts':['synthetic']}))
        encode.assert_called_once_with(self.backend, ['synthetic'])
        self.assertEqual(result['result']['vectors'], [[.25,.75]])

    def test_fixed_reference_normalization_matches_frozen_recipe(self):
        import numpy as np
        from model_session_worker import fixed_reference_encode
        from unittest.mock import Mock
        model = Mock(); model.encode.return_value = np.array([[3., 4.]], dtype=np.float16)
        backend = SimpleNamespace(embedding_model=lambda:model, MODEL_SETTINGS={'embedding':{'batchSize':16}})
        np.testing.assert_allclose(fixed_reference_encode(backend, ['synthetic']), [[.6,.8]])
        model.encode.assert_called_once_with(['synthetic'], batch_size=16, normalize_embeddings=False,
                                            convert_to_numpy=True, show_progress_bar=False)
        model.encode.return_value = np.array([[0., 0.]], dtype=np.float16)
        with self.assertRaises(ValueError): fixed_reference_encode(backend, ['synthetic'])

    def test_fixed_reference_stage_accepts_rerank_session(self):
        session = self.make('reranker', stage='fixed-reference-query')
        result = session.submit(batch(0, {'kind':'reranker', 'pairs':[['q','a']]}))
        self.assertEqual(result['result']['scores'], [.7])

    def test_wrong_stage_model_or_profile_rejected_before_compute(self):
        with self.assertRaisesRegex(ValueError, 'invalid assigned'):
            self.make('reranker', stage='document-embedding')
        self.assertEqual(self.backend.calls, [])
        source={'requestId':'1'*32,'sourceScopeSha256':'2'*64,'modelKind':'embedding',
                'modelIdentity':{'wrong':True},'runtime':self.backend.runtime,'inferenceCap':512,
                'deadlineBudgetMs':1000,'maxBatches':2,'maxTotalBytes':1000}
        profile={'adapter':'consense.model-session.v1'}; raw=encoded(envelope('document-embedding',b'{}',source,profile))
        for profile_sha in (digest(encoded(profile)), 'b'*64):
            with self.assertRaises(ValueError):
                ModelSession(raw,self.root/'bad',operation_sha256=digest(raw),profile_sha256=profile_sha,
                    deadline=time.monotonic()+1,cancelled=lambda:False,backend=self.backend)
        self.assertEqual(self.backend.calls, [])

    def test_failed_batch_is_never_recomputed(self):
        session=self.make(); self.backend.result=[[float('inf'), 1]]
        item=batch(0,{'kind':'embedding','texts':['one']})
        with self.assertRaisesRegex(ValueError,'embedding result differs'): session.submit(item)
        self.assertTrue((self.root/'session'/'00000000.input').exists())
        self.assertFalse((self.root/'session'/'00000000.output').exists())
        self.assertTrue((self.root/'session'/'failure.json').exists())
        self.backend.result=None
        with self.assertRaises(ValueError): session.submit(item)
        self.assertEqual(len([x for x in self.backend.calls if isinstance(x,tuple)]),1)

    def test_sequence_budget_cancel_and_identity_drift_fail_closed(self):
        for scenario in ('ordinal','bytes','cancel','identity','deadline'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                self.root=Path(directory); self.backend=FakeBackend(); self.cancel=threading.Event()
                session=self.make(maxTotalBytes=1 if scenario=='bytes' else 10000)
                if scenario=='cancel': self.cancel.set()
                if scenario=='identity': self.backend.identity={'changed':True}
                if scenario=='deadline': session.deadline=time.monotonic()-1
                with self.assertRaises(ValueError):
                    session.submit(batch(1 if scenario=='ordinal' else 0,{'kind':'dimension'}))
                self.assertEqual(self.backend.calls,[])
                with self.assertRaises(ValueError): session.submit(batch(0,{'kind':'dimension'}))

    def test_tampered_result_or_incomplete_receipts_never_finish(self):
        for scenario in ('tamper','missing','boolean_ordinal'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                self.root=Path(directory); self.backend=FakeBackend(); self.cancel=threading.Event()
                session=self.make(); result=session.submit(batch(0,{'kind':'dimension'}))
                rows=[{key:result[key] for key in ('ordinal','inputSha256','outputSha256')}]
                if scenario=='tamper': (self.root/'session'/'00000000.output').write_bytes(b'{}')
                if scenario=='missing': rows=[]
                if scenario=='boolean_ordinal': rows[0]['ordinal']=False
                with self.assertRaises(ValueError): session.finish(rows)
                self.assertFalse((self.root/'session'/'finished.json').exists())
                with self.assertRaises(ValueError): session.submit(batch(1,{'kind':'dimension'}))

    def test_duplicate_ordinal_is_never_recomputed_and_changed_read_is_rejected(self):
        session=self.make(); request=batch(0,{'kind':'dimension'}); session.submit(request)
        with self.assertRaises(ValueError): session.read_completed(0,'f'*64)
        with self.assertRaisesRegex(ValueError,'sequence'): session.submit(request)
        self.assertEqual(self.backend.calls,['dimension'])
        with self.assertRaises(ValueError): session.submit(batch(1,{'kind':'dimension'}))

    def test_cancel_or_model_change_during_compute_discards_result(self):
        for scenario in ('cancel','identity'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                self.root=Path(directory); self.backend=FakeBackend(); self.cancel=threading.Event()
                session=self.make()
                def encode(texts):
                    if scenario=='cancel': self.cancel.set()
                    else: self.backend.identity={'changed':True}
                    return Rows([[.25,.75]])
                self.backend.encode_embeddings=encode
                with self.assertRaises(ValueError): session.submit(batch(0,{'kind':'embedding','texts':['one']}))
                self.assertTrue((self.root/'session'/'00000000.input').exists())
                self.assertFalse((self.root/'session'/'00000000.output').exists())
                self.assertTrue((self.root/'session'/'failure.json').exists())

    def test_output_bytes_count_against_total_budget(self):
        raw=encoded({'kind':'dimension'})
        session=self.make(maxTotalBytes=len(raw))
        with self.assertRaisesRegex(ValueError,'total budget'): session.submit(batch(0,{'kind':'dimension'}))
        self.assertTrue((self.root/'session'/'00000000.input').exists())
        self.assertFalse((self.root/'session'/'00000000.output').exists())

    def test_changed_session_binding_is_rejected_before_next_batch(self):
        session=self.make(); session.submit(batch(0,{'kind':'dimension'}))
        (self.root/'session'/'session.json').write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'session binding changed'):
            session.submit(batch(1,{'kind':'dimension'}))
        self.assertEqual(self.backend.calls,['dimension'])

    @unittest.skipUnless(os.name == 'posix' and hasattr(os, 'fork'), 'private UDS and fork required')
    def test_private_process_ipc_uses_assigned_operation_and_waits_for_owner(self):
        source={'requestId':'1'*32,'sourceScopeSha256':'2'*64,'modelKind':'embedding',
                'modelIdentity':self.backend.identity,'runtime':self.backend.runtime,'inferenceCap':512,
                'deadlineBudgetMs':10000,'maxBatches':2,'maxTotalBytes':10000}
        profile={'adapter':'consense.model-session.v1'}; operation=envelope('document-embedding',b'{}',source,profile)
        raw=encoded(operation); directory=self.root/'gpu-operations'/operation['operationId']
        directory.mkdir(parents=True,mode=0o700); (directory/'request.json').write_bytes(raw)
        token_path=directory/'model-session.token'; token_path.write_text('authored-token'); os.chmod(token_path,0o600)
        pid=os.fork()
        if pid == 0:
            try:
                for key in list(os.environ):
                    if key.startswith('CONSENSE_FIXED_GPU_'): del os.environ[key]
                os.environ.update({'CONSENSE_FIXED_WORKSPACE':str(self.root),'CONSENSE_FIXED_CONTAINER_ID':'fixture',
                    'CONSENSE_FIXED_OPERATION_ID':operation['operationId'],'CONSENSE_OPERATION_REQUEST':str(directory/'request.json'),
                    'CONSENSE_OPERATION_REQUEST_SHA256':digest(raw),'CONSENSE_MODEL_SESSION_PROFILE_SHA256':digest(encoded(profile)),
                    'CONSENSE_MODEL_SESSION_DEADLINE_MONOTONIC':str(time.monotonic()+10),
                    'CONSENSE_MODEL_SESSION_TOKEN_FILE':str(token_path),'CONSENSE_MODEL_DEVICE':'cuda',
                    'CONSENSE_MODEL_OFFLINE':'1','HF_HUB_OFFLINE':'1','TRANSFORMERS_OFFLINE':'1'})
                sys.modules['server']=self.backend
                serve_assigned()
            except BaseException:
                os._exit(2)
            os._exit(0)
        try:
            ready_path=directory/'model-session.ready.json'; end=time.monotonic()+3
            while not ready_path.exists() and time.monotonic()<end: time.sleep(.01)
            self.assertTrue(ready_path.exists()); ready=json.loads(ready_path.read_bytes())
            self.assertEqual(ready['pid'],pid); self.assertEqual(ready['requestSha256'],digest(raw))
            sock_path=directory/'model-session.sock'
            self.assertEqual(ready['socketInode'],sock_path.lstat().st_ino)
            def call(method,payload,token='authored-token'):
                raw=encoded({'token':token,'method':method,'payload':payload})
                with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as conn:
                    conn.settimeout(2); conn.connect(str(sock_path))
                    peer=struct.unpack('3i',conn.getsockopt(socket.SOL_SOCKET,socket.SO_PEERCRED,12))
                    self.assertEqual(peer[0],pid)
                    conn.sendall(struct.pack('!I',len(raw))+raw)
                    def read(n):
                        parts=[]
                        while n:
                            chunk=conn.recv(n); self.assertTrue(chunk); parts.append(chunk); n-=len(chunk)
                        return b''.join(parts)
                    size=struct.unpack('!I',read(4))[0]
                    return json.loads(read(size))
            denied=call('submit',batch(0,{'kind':'dimension'}),token='wrong')
            self.assertEqual(denied,{'ok':False,'errorType':'ValueError'})
            result=call('submit',batch(0,{'kind':'dimension'}))['result']
            self.assertEqual(result['result'],{'dimension':2})
            rows=[{key:result[key] for key in ('ordinal','inputSha256','outputSha256')}]
            final=call('finish',{'batches':rows})['result']
            self.assertEqual(final['status'],'computation_completed_release_unverified')
            self.assertEqual(os.waitpid(pid,os.WNOHANG),(0,0))
        finally:
            os.kill(pid,signal.SIGTERM); os.waitpid(pid,0)

    @unittest.skipUnless(os.name == 'posix' and hasattr(os, 'fork'), 'private UDS and fork required')
    def test_total_deadline_kills_stalled_fake_computation_without_result(self):
        source={'requestId':'1'*32,'sourceScopeSha256':'2'*64,'modelKind':'embedding',
                'modelIdentity':self.backend.identity,'runtime':self.backend.runtime,'inferenceCap':512,
                'deadlineBudgetMs':1500,'maxBatches':2,'maxTotalBytes':10000}
        profile={'adapter':'consense.model-session.v1'}; operation=envelope('document-embedding',b'{}',source,profile)
        raw=encoded(operation); directory=self.root/'gpu-operations'/operation['operationId']
        directory.mkdir(parents=True,mode=0o700); (directory/'request.json').write_bytes(raw)
        token_path=directory/'model-session.token'; token_path.write_text('authored-token'); os.chmod(token_path,0o600)
        pid=os.fork()
        if pid==0:
            try:
                for key in list(os.environ):
                    if key.startswith('CONSENSE_FIXED_GPU_'): del os.environ[key]
                os.environ.update({'CONSENSE_FIXED_WORKSPACE':str(self.root),'CONSENSE_FIXED_CONTAINER_ID':'fixture',
                    'CONSENSE_FIXED_OPERATION_ID':operation['operationId'],'CONSENSE_OPERATION_REQUEST':str(directory/'request.json'),
                    'CONSENSE_OPERATION_REQUEST_SHA256':digest(raw),'CONSENSE_MODEL_SESSION_PROFILE_SHA256':digest(encoded(profile)),
                    'CONSENSE_MODEL_SESSION_DEADLINE_MONOTONIC':str(time.monotonic()+1.5),
                    'CONSENSE_MODEL_SESSION_TOKEN_FILE':str(token_path),'CONSENSE_MODEL_DEVICE':'cuda',
                    'CONSENSE_MODEL_OFFLINE':'1','HF_HUB_OFFLINE':'1','TRANSFORMERS_OFFLINE':'1'})
                def stall(_): time.sleep(5); return Rows([[.25,.75]])
                self.backend.encode_embeddings=stall; sys.modules['server']=self.backend
                serve_assigned()
            except BaseException: os._exit(2)
            os._exit(0)
        reaped=False
        try:
            ready=directory/'model-session.ready.json'; end=time.monotonic()+1
            while not ready.exists() and time.monotonic()<end: time.sleep(.01)
            self.assertTrue(ready.exists())
            request=encoded({'token':'authored-token','method':'submit',
                             'payload':batch(0,{'kind':'embedding','texts':['one']})})
            with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as conn:
                conn.settimeout(3); conn.connect(str(directory/'model-session.sock'))
                conn.sendall(struct.pack('!I',len(request))+request)
                self.assertEqual(conn.recv(4),b'')
            _,status=os.waitpid(pid,0); reaped=True
            self.assertEqual(os.waitstatus_to_exitcode(status),124)
            self.assertTrue((directory/'model-session'/'00000000.input').exists())
            self.assertFalse((directory/'model-session'/'00000000.output').exists())
        finally:
            if not reaped: os.kill(pid,signal.SIGTERM); os.waitpid(pid,0)


if __name__ == '__main__': unittest.main()
