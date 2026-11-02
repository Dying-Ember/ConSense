"""One request-owned model session through the fixed gateway seam."""
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time
import unittest
from unittest.mock import patch

from fixed_gpu_client import Client, OperationError
from fixed_model_session import ModelSession
from request_stage import encoded


class ModelSessionTest(unittest.TestCase):
    def _identity_after_cancel_or_deadline(self, expire):
        binding={'requestId':'a'*32,'sourceScopeSha256':'c'*64,'modelKind':'embedding',
                 'modelIdentity':{'model':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':512,
                 'deadlineBudgetMs':2000,'maxBatches':3,'maxTotalBytes':1024}
        entered=threading.Event(); resume=threading.Event(); external_cancel=threading.Event(); calls=[]
        class FakeClient:
            def _identity(self, stage, source, body):
                entered.set(); resume.wait(2); return {'profileSha256':'d'*64}
            def _call(self, method, path, timeout, value=None, cancelled=None):
                calls.append((method,path)); return {'operationId':'b'*32}
            def cancel_and_observe(self, operation, expected, *, model_stage=None):
                calls.append(('cancel',operation)); return {'status':'release_unknown','operationId':operation}
        results=[]; errors=[]; deadline=time.monotonic()+(0.15 if expire else 1.5)
        def start():
            try: results.append(ModelSession.start(FakeClient(),'document-embedding',binding,deadline,external_cancel.is_set,lambda action: action()))
            except Exception as error: errors.append(error)
        thread=threading.Thread(target=start); thread.start()
        try:
            self.assertTrue(entered.wait(1))
            if expire:
                while time.monotonic() <= deadline: external_cancel.wait(min(.05,max(.001,deadline-time.monotonic())))
            else: external_cancel.set()
            resume.set(); thread.join(2)
            self.assertFalse(thread.is_alive()); self.assertEqual(results,[]); self.assertEqual(len(errors),1)
            self.assertEqual(calls,[])
            self.assertIsInstance(errors[0],TimeoutError if expire else ValueError)
            self.assertNotIsInstance(errors[0],OperationError)
        finally: resume.set(); thread.join(2)

    def test_identity_after_deadline_never_posts(self):
        self._identity_after_cancel_or_deadline(True)

    def test_identity_after_cancellation_never_posts(self):
        self._identity_after_cancel_or_deadline(False)

    def test_binding_is_snapshotted_before_admission(self):
        binding={'requestId':'a'*32,'sourceScopeSha256':'c'*64,'modelKind':'embedding',
                 'modelIdentity':{'model':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':512,
                 'deadlineBudgetMs':2000,'maxBatches':3,'maxTotalBytes':1024}
        original=json.loads(encoded(binding)); seen=[]
        class FakeClient:
            def _identity(self, stage, source, body):
                return {'sourceSha256':hashlib.sha256(encoded(source)).hexdigest(), 'profileSha256':'d'*64}
            def _matches(self, state, expected): return state.get('sourceSha256') == expected['sourceSha256']
            def cancel_and_observe(self, operation, expected, *, model_stage=None): return {'status':'release_unknown','operationId':operation}
            def _call(self, method, path, timeout, value=None, cancelled=None):
                if path == '/operations':
                    binding['modelKind']='reranker'; binding['runtime']['device']='cpu'; binding['maxBatches']=0
                    seen.append(json.loads(encoded(value['binding'])))
                    return {'operationId':'b'*32}
                if method == 'GET': return {'operationId':'b'*32,'stage':'document-embedding','bindingSha256':'e'*64,
                    'status':'ready','sourceSha256':hashlib.sha256(encoded(seen[0])).hexdigest()}
                output={'dimension':2}
                return {'operationId':'b'*32,'bindingSha256':'e'*64,'ordinal':value['ordinal'],
                        'inputSha256':value['inputSha256'],'outputSha256':hashlib.sha256(encoded(output)).hexdigest(),
                        'result':output,'provisional':True}
        session=ModelSession.start(FakeClient(),'document-embedding',binding,time.monotonic()+2)
        self.assertEqual(seen,[original]); self.assertEqual(session.binding,original)
        self.assertEqual(session.batch({'kind':'dimension'}),{'dimension':2})

    def test_admission_uses_identity_fixed_before_post(self):
        binding={'requestId':'a'*32,'sourceScopeSha256':'c'*64,'modelKind':'embedding',
                 'modelIdentity':{'model':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':512,
                 'deadlineBudgetMs':2000,'maxBatches':3,'maxTotalBytes':1024}
        class FakeClient:
            def __init__(self): self.identities=0; self.posted=False
            def _identity(self, stage, source, body):
                self.identities+=1
                if self.identities > 1: raise ValueError('second identity failed')
                return {'profileSha256':'d'*64}
            def _matches(self, state, expected): return expected == {'profileSha256':'d'*64}
            def _call(self, method, path, timeout, value=None, cancelled=None):
                if path == '/operations': self.posted=True; return {'operationId':'b'*32}
                return {'operationId':'b'*32,'stage':'document-embedding','bindingSha256':'e'*64,'status':'ready'}
        client=FakeClient(); session=ModelSession.start(client,'document-embedding',binding,time.monotonic()+2)
        self.assertTrue(client.posted); self.assertEqual(client.identities,1)
        self.assertEqual(session.expected,{'profileSha256':'d'*64})

    def test_constructor_failure_after_admission_cleans_known_operation(self):
        binding={'requestId':'a'*32,'sourceScopeSha256':'c'*64,'modelKind':'embedding',
                 'modelIdentity':{'model':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':512,
                 'deadlineBudgetMs':2000,'maxBatches':3,'maxTotalBytes':1024}
        for cleanup_fails in (False,True):
            with self.subTest(cleanup_fails=cleanup_fails):
                calls=[]
                class FakeClient:
                    def _identity(self, stage, source, body): return {'profileSha256':'d'*64}
                    def _call(self, method, path, timeout, value=None, cancelled=None): return {'operationId':'b'*32}
                    def cancel_and_observe(self, operation, expected, *, model_stage=None):
                        calls.append((operation,expected))
                        if cleanup_fails: raise OSError('cleanup unavailable')
                        return {'status':'release_unknown','operationId':operation}
                with patch.object(ModelSession,'__init__',side_effect=ValueError('constructor failed')):
                    with self.assertRaises(OperationError) as failure:
                        ModelSession.start(FakeClient(),'document-embedding',binding,time.monotonic()+2)
                self.assertEqual(failure.exception.operation,'b'*32)
                self.assertEqual(failure.exception.exit_receipt['status'],'release_unknown')
                self.assertEqual(calls,[('b'*32,{'profileSha256':'d'*64})])

    def _ready_response_after_cancel_or_deadline(self, expire, cleanup_fails=False):
        binding={'requestId':'a'*32,'sourceScopeSha256':'c'*64,'modelKind':'embedding',
                 'modelIdentity':{'model':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':512,
                 'deadlineBudgetMs':2000,'maxBatches':3,'maxTotalBytes':1024}
        entered=threading.Event(); resume=threading.Event(); external_cancel=threading.Event(); calls=[]
        class FakeClient:
            def _identity(self, stage, source, body): return {'profileSha256':'d'*64}
            def _matches(self, state, expected): return True
            def cancel_and_observe(self, operation, expected, *, model_stage=None):
                calls.append(('cancel',operation))
                if cleanup_fails: raise OSError('cleanup unavailable')
                return {'status':'release_unknown','operationId':operation}
            def _call(self, method, path, timeout, value=None, cancelled=None):
                if path == '/operations': return {'operationId':'b'*32}
                entered.set(); resume.wait(2)
                return {'operationId':'b'*32,'stage':'document-embedding','bindingSha256':'e'*64,'status':'ready'}
        results=[]; errors=[]; deadline=time.monotonic()+(0.15 if expire else 1.5)
        def start():
            try: results.append(ModelSession.start(FakeClient(),'document-embedding',binding,deadline,external_cancel.is_set,lambda action: action()))
            except Exception as error: errors.append(error)
        thread=threading.Thread(target=start); thread.start()
        try:
            self.assertTrue(entered.wait(1))
            if expire:
                while time.monotonic() <= deadline: external_cancel.wait(min(.05,max(.001,deadline-time.monotonic())))
            else: external_cancel.set()
            resume.set(); thread.join(2)
            self.assertFalse(thread.is_alive()); self.assertEqual(results,[]); self.assertEqual(len(errors),1)
            self.assertIsInstance(errors[0],OperationError); self.assertEqual(errors[0].operation,'b'*32)
            self.assertEqual(errors[0].exit_receipt['status'],'release_unknown'); self.assertEqual(calls,[('cancel','b'*32)])
        finally: resume.set(); thread.join(2)

    def test_ready_response_after_external_cancellation_cleans_up(self):
        self._ready_response_after_cancel_or_deadline(False)

    def test_ready_response_after_deadline_cleans_up(self):
        self._ready_response_after_cancel_or_deadline(True)

    def test_ready_cleanup_failure_preserves_known_operation_unknown(self):
        self._ready_response_after_cancel_or_deadline(False,cleanup_fails=True)

    def test_cancel_does_not_wait_for_batch_or_duplicate_cleanup(self):
        binding={'requestId':'a'*32,'sourceScopeSha256':'c'*64,'modelKind':'embedding',
                 'modelIdentity':{'model':'fixture'},'runtime':{'device':'cuda'},'inferenceCap':512,
                 'deadlineBudgetMs':4000,'maxBatches':3,'maxTotalBytes':1024}
        batch_entered=threading.Event(); batch_continue=threading.Event(); cleanup_entered=threading.Event()
        cleanup_continue=threading.Event(); calls=[]; failures=[]
        class FakeClient:
            def _identity(self, stage, source, body): return {'profileSha256':'d'*64}
            def _matches(self, state, expected): return True
            def cancel_and_observe(self, operation, expected, *, model_stage=None):
                calls.append('cancel'); cleanup_entered.set(); cleanup_continue.wait(2)
                return {'status':'release_unknown'}
            def _call(self, method, path, timeout, value=None, cancelled=None):
                if path == '/operations': return {'operationId':'b'*32}
                if method == 'GET': return {'operationId':'b'*32,'stage':'document-embedding','bindingSha256':'e'*64,'status':'ready'}
                batch_entered.set(); batch_continue.wait(2); output={'dimension':2}
                return {'operationId':'b'*32,'bindingSha256':'e'*64,'ordinal':value['ordinal'],
                        'inputSha256':value['inputSha256'],'outputSha256':hashlib.sha256(encoded(output)).hexdigest(),
                        'result':output,'provisional':True}
        session=ModelSession.start(FakeClient(),'document-embedding',binding,time.monotonic()+4)
        def batch():
            try: session.batch({'kind':'dimension'})
            except Exception as error: failures.append(error)
        task=threading.Thread(target=batch); task.start(); cancel_thread=threading.Thread(target=session.cancel)
        try:
            self.assertTrue(batch_entered.wait(1)); cancel_thread.start(); self.assertTrue(cleanup_entered.wait(1))
            batch_continue.set(); task.join(1)
            self.assertFalse(task.is_alive()); self.assertTrue(failures)
            self.assertEqual(failures[0].exit_receipt['status'],'release_unknown'); self.assertEqual(calls,['cancel'])
        finally:
            batch_continue.set(); cleanup_continue.set(); task.join(2); cancel_thread.join(2)
        self.assertFalse(cancel_thread.is_alive()); self.assertFalse(session.released)

    def test_cancel_wins_against_inflight_batch_and_finish(self):
        binding = {'requestId':'a'*32, 'sourceScopeSha256':'c'*64, 'modelKind':'embedding',
                   'modelIdentity':{'model':'fixture'}, 'runtime':{'device':'cuda'}, 'inferenceCap':512,
                   'deadlineBudgetMs':4000, 'maxBatches':3, 'maxTotalBytes':1024}
        for action in ('batch','finish'):
            with self.subTest(action=action):
                entered=threading.Event(); resume=threading.Event(); cancelled=threading.Event(); calls=[]
                class FakeClient:
                    def _identity(self, stage, source, body): return {'profileSha256':'d'*64}
                    def _matches(self, state, expected): return True
                    def cancel_and_observe(self, operation, expected, *, model_stage=None):
                        calls.append('cancel'); cancelled.set(); return {'status':'release_unknown'}
                    def _call(self, method, path, timeout, value=None, cancelled=None):
                        if path == '/operations': return {'operationId':'b'*32}
                        if method == 'GET':
                            calls.append('status')
                            return {'operationId':'b'*32,'stage':'document-embedding','bindingSha256':'e'*64,
                                    'status':'ready' if not entered.is_set() else 'release_unknown'}
                        if path.endswith('/model-batches'):
                            if action == 'batch': entered.set(); resume.wait(2)
                            output={'dimension':2}
                            return {'operationId':'b'*32,'bindingSha256':'e'*64,'ordinal':value['ordinal'],
                                    'inputSha256':value['inputSha256'],'outputSha256':hashlib.sha256(encoded(output)).hexdigest(),
                                    'result':output,'provisional':True}
                        entered.set(); resume.wait(2)
                        return {'operationId':'b'*32,'status':'finishing'}
                session=ModelSession.start(FakeClient(),'document-embedding',binding,time.monotonic()+4)
                if action == 'finish': session.batch({'kind':'dimension'})
                calls.clear(); results=[]; failures=[]
                def run():
                    try: results.append(session.batch({'kind':'dimension'}) if action == 'batch' else session.finish())
                    except Exception as error: failures.append(error)
                thread=threading.Thread(target=run); thread.start(); cancel_result=[]
                cancel_thread=threading.Thread(target=lambda:cancel_result.append(session.cancel()))
                try:
                    self.assertTrue(entered.wait(1)); cancel_thread.start()
                    self.assertTrue(cancelled.wait(1)); cancel_thread.join(1); self.assertFalse(cancel_thread.is_alive())
                    self.assertEqual(cancel_result[0]['status'],'release_unknown'); resume.set(); thread.join(2)
                    self.assertFalse(thread.is_alive()); self.assertEqual(results,[])
                    self.assertTrue(failures); self.assertFalse(session.released)
                    self.assertEqual(calls,['cancel'])
                finally:
                    resume.set(); thread.join(2)
                    if cancel_thread.ident is not None: cancel_thread.join(2)

    def test_batches_are_provisional_until_summary_and_release_are_verified(self):
        binding = {'requestId':'a'*32, 'sourceScopeSha256':'c'*64, 'modelKind':'embedding',
                   'modelIdentity':{'model':'fixture','token':'private-model-sentinel'},
                   'runtime':{'device':'cuda','private':'private-runtime-sentinel'}, 'inferenceCap':512,
                   'deadlineBudgetMs':2000, 'maxBatches':3, 'maxTotalBytes':1024}
        claims = {'bodySha256':hashlib.sha256(b'{}').hexdigest(), 'sourceSha256':hashlib.sha256(encoded(binding)).hexdigest(),
                  'profileSha256':'d'*64}
        batch_list = []; finished = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, value, status=200):
                raw = encoded(value); self.send_response(status); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
            def do_POST(self):
                value = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if self.path == '/operations': self.reply({'operationId':'b'*32,'status':'starting'},202); return
                if self.path.endswith('/model-batches'):
                    output = {'dimension':2}; digest = hashlib.sha256(encoded(output)).hexdigest()
                    batch_list.append({'ordinal':value['ordinal'],'inputSha256':value['inputSha256'],'outputSha256':digest})
                    self.reply({'operationId':'b'*32,'bindingSha256':'e'*64,**batch_list[-1], 'result':output,'provisional':True}); return
                self.assert_batches(value); finished.append(True); self.reply({'operationId':'b'*32,'status':'finishing'},202)
            def assert_batches(self, value):
                if value != {'batches':batch_list}: raise AssertionError('finish inventory differs')
            def do_GET(self):
                summary = {'operationId':'b'*32,'bindingSha256':'e'*64,'requestId':'a'*32,
                    'batchCount':len(batch_list),'batchesSha256':hashlib.sha256(encoded(batch_list)).hexdigest(),
                    'totalBytes':len(b'{"kind":"dimension"}')+len(encoded({'dimension':2})),
                    'status':'computation_completed_release_unverified'}
                if self.path.endswith('/model-summary'): self.reply(summary); return
                self.reply({**claims,'operationId':'b'*32,'stage':'document-embedding','bindingSha256':'e'*64,
                    'status':'completed' if finished else 'ready', 'modelSummarySha256':hashlib.sha256(encoded(summary)).hexdigest(),
                    'release':{'status':'released','cgroupPopulated':0,'observedGpuPids':[123]}})
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            client = Client('http://127.0.0.1:%s' % server.server_port,'test-token',{'document-embedding':'d'*64})
            session = ModelSession.start(client,'document-embedding',binding,time.monotonic()+2)
            self.assertEqual({'dimension':2},session.batch({'kind':'dimension'}))
            self.assertFalse(session.released)
            receipt = session.finish()
            self.assertTrue(session.released); self.assertEqual('released',receipt['release']['status'])
            self.assertEqual(receipt['schema'],'consense.fixed-model-session-receipt.v1')
            self.assertEqual(receipt['stage'],'document-embedding')
            claims=receipt['sourceClaims']
            self.assertEqual(claims['requestId'],binding['requestId'])
            self.assertEqual(claims['sourceScopeSha256'],binding['sourceScopeSha256'])
            self.assertEqual(claims['modelKind'],binding['modelKind'])
            self.assertEqual(claims['modelIdentitySha256'],hashlib.sha256(encoded(binding['modelIdentity'])).hexdigest())
            self.assertEqual(claims['runtimeSha256'],hashlib.sha256(encoded(binding['runtime'])).hexdigest())
            for key in ('inferenceCap','deadlineBudgetMs','maxBatches','maxTotalBytes'): self.assertEqual(claims[key],binding[key])
            self.assertNotIn('private-model-sentinel',json.dumps(receipt))
            self.assertNotIn('private-runtime-sentinel',json.dumps(receipt))
            self.assertEqual(receipt['sourceSha256'],hashlib.sha256(encoded(binding)).hexdigest())
            self.assertEqual(receipt['bodySha256'],hashlib.sha256(b'{}').hexdigest())
            self.assertEqual(receipt['profileSha256'],'d'*64)
            self.assertEqual(receipt['modelSummarySha256'],hashlib.sha256(encoded(receipt['summary'])).hexdigest())
            self.assertEqual(receipt['summary']['batchesSha256'],hashlib.sha256(encoded(receipt['batches'])).hexdigest())
            receipt['sourceClaims']['requestId']='f'*32; receipt['batches'][0]['ordinal']=999
            self.assertEqual(session.exit_receipt['sourceClaims']['requestId'],'a'*32)
            self.assertEqual(session.exit_receipt['batches'][0]['ordinal'],0)
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_forged_batch_result_is_cancelled_and_never_retried(self):
        binding = {'requestId':'a'*32, 'sourceScopeSha256':'c'*64, 'modelKind':'embedding',
                   'modelIdentity':{'model':'fixture'}, 'runtime':{'device':'cuda'}, 'inferenceCap':512,
                   'deadlineBudgetMs':2000, 'maxBatches':3, 'maxTotalBytes':1024}
        claims = {'bodySha256':hashlib.sha256(b'{}').hexdigest(), 'sourceSha256':hashlib.sha256(encoded(binding)).hexdigest(),
                  'profileSha256':'d'*64}
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def reply(self, value, status=200):
                raw = encoded(value); self.send_response(status); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
            def do_POST(self):
                value = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if self.path == '/operations': self.reply({'operationId':'b'*32},202); return
                calls.append('batch')
                self.reply({'operationId':'b'*32,'bindingSha256':'e'*64,'ordinal':value['ordinal'],
                    'inputSha256':value['inputSha256'],'outputSha256':'0'*64,'result':{'dimension':2},'provisional':True})
            def do_DELETE(self): calls.append('cancel'); self.reply({},202)
            def do_GET(self):
                self.reply({**claims,'operationId':'b'*32,'stage':'document-embedding','bindingSha256':'e'*64,
                    'status':'cancelled' if 'cancel' in calls else 'ready','release':{'status':'release_unknown'}})
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            client = Client('http://127.0.0.1:%s' % server.server_port,'test-token',{'document-embedding':'d'*64})
            session = ModelSession.start(client,'document-embedding',binding,time.monotonic()+2)
            with self.assertRaisesRegex(ValueError,'batch identity differs'): session.batch({'kind':'dimension'})
            self.assertEqual(['batch','cancel'],calls); self.assertFalse(session.released)
            with self.assertRaises(ValueError): session.batch({'kind':'dimension'})
            self.assertEqual(['batch','cancel'],calls)
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_batch_output_counts_toward_total_budget_before_acceptance(self):
        binding = {'requestId':'a'*32, 'sourceScopeSha256':'c'*64, 'modelKind':'embedding',
                   'modelIdentity':{'model':'fixture'}, 'runtime':{'device':'cuda'}, 'inferenceCap':512,
                   'deadlineBudgetMs':2000, 'maxBatches':3, 'maxTotalBytes':20}
        calls = []
        class FakeClient:
            def _identity(self, stage, source, body): return {'profileSha256':'d'*64}
            def _matches(self, state, expected): return True
            def cancel_and_observe(self, operation, expected, *, model_stage=None):
                calls.append('cancel'); return {'status':'release_unknown'}
            def _call(self, method, path, timeout, value=None, cancelled=None):
                if path == '/operations': return {'operationId':'b'*32}
                if method == 'GET':
                    return {'operationId':'b'*32,'stage':'document-embedding','bindingSha256':'e'*64,'status':'ready'}
                calls.append('batch')
                return {'operationId':'b'*32,'bindingSha256':'e'*64,'ordinal':value['ordinal'],
                        'inputSha256':value['inputSha256'],
                        'outputSha256':hashlib.sha256(encoded({'dimension':2})).hexdigest(),
                        'result':{'dimension':2},'provisional':True}
        session = ModelSession.start(FakeClient(),'document-embedding',binding,time.monotonic()+2)
        with self.assertRaisesRegex(ValueError,'budget exceeded'): session.batch({'kind':'dimension'})
        self.assertEqual(['batch','cancel'],calls)
        self.assertFalse(session.released)


if __name__ == '__main__': unittest.main()
