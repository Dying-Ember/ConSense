"""Request cancellation arbitrates session acceptance and source publication."""
import hashlib
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'vetting_eval'))
from source_request_context import RequestControl, model_session_control, request_context
from fixed_gpu_client import OperationError
from fixed_model_session import ModelSession
from request_stage import encoded


class FakeGateway:
    def __init__(self):
        self.rows = []
        self.finished = False
        self.posts = 0
        self.cancels = 0
        self.request_id = None
        self.tamper_summary = False
        self.extra_summary = False; self.extra_release = False

    def _identity(self, stage, binding, body):
        return {'bodySha256':hashlib.sha256(body).hexdigest(),
                'sourceSha256':hashlib.sha256(encoded(binding)).hexdigest(), 'profileSha256':'d'*64}
    def _matches(self, state, expected): return True
    def cancel_and_observe(self, operation, expected, *, model_stage=None):
        self.cancels += 1
        return {'status':'release_unknown', 'operationId':operation}

    def _call(self, method, path, timeout, value=None, cancelled=None):
        if path == '/operations':
            self.posts += 1
            self.request_id = value['binding']['requestId']
            return {'operationId':'b'*32}
        if path.endswith('/model-batches'):
            result = {'dimension':2}
            row = {'ordinal':value['ordinal'], 'inputSha256':value['inputSha256'],
                   'outputSha256':hashlib.sha256(encoded(result)).hexdigest()}
            self.rows.append(row)
            return {'operationId':'b'*32, 'bindingSha256':'e'*64, **row,
                    'result':result, 'provisional':True}
        if path.endswith('/finish-model'):
            self.finished = True
            return {'operationId':'b'*32, 'status':'finishing'}
        summary = {'operationId':'b'*32, 'bindingSha256':'e'*64, 'requestId':self.request_id,
                   'batchCount':len(self.rows), 'batchesSha256':hashlib.sha256(encoded(self.rows)).hexdigest(),
                   'totalBytes':len(encoded({'kind':'dimension'}))+len(encoded({'dimension':2})),
                   'status':'computation_completed_release_unverified'}
        if self.extra_summary: summary['secret']='private-extra-sentinel'
        if path.endswith('/model-summary'):
            return {**summary, 'totalBytes':summary['totalBytes']+1} if self.tamper_summary else summary
        release={'status':'released', 'cgroupPopulated':0, 'observedGpuPids':[123]}
        if self.extra_release: release['secret']='private-extra-sentinel'
        return {'operationId':'b'*32, 'stage':'document-embedding', 'bindingSha256':'e'*64,
                'status':'completed' if self.finished else 'ready',
                'modelSummarySha256':hashlib.sha256(encoded(summary)).hexdigest(),
                'release':release}


def binding():
    return {'requestId':'a'*32, 'sourceScopeSha256':'c'*64, 'modelKind':'embedding',
            'modelIdentity':{'model':'fixture'}, 'runtime':{'device':'cuda'}, 'inferenceCap':512,
            'deadlineBudgetMs':4000, 'maxBatches':3, 'maxTotalBytes':1024}


def owner(control):
    with request_context(control):
        return model_session_control()


def bound_source(owner):
    value = binding(); value['requestId'] = owner['requestId']; return value


class RequestAcceptanceTest(unittest.TestCase):
    def test_external_cancel_requires_accept_gate_before_admission(self):
        control = RequestControl(4); gateway = FakeGateway(); current = owner(control)
        with self.assertRaises(ValueError):
            ModelSession.start(gateway, 'document-embedding', bound_source(current), current['deadline'], current['cancelled'])
        self.assertEqual(gateway.posts, 0)

    def test_model_control_requires_owned_request(self):
        with self.assertRaises(ValueError): model_session_control()

    def test_tampered_summary_never_yields_a_receipt(self):
        control=RequestControl(4); gateway=FakeGateway(); current=owner(control)
        session=ModelSession.start(gateway,'document-embedding',bound_source(current),current['deadline'],
                                   current['cancelled'],current['accept'])
        session.batch({'kind':'dimension'}); gateway.tamper_summary=True
        with self.assertRaisesRegex(OperationError,'summary differs'): session.finish()
        self.assertFalse(session.released); self.assertEqual(gateway.cancels,1)

    def test_tampered_source_never_yields_a_receipt(self):
        control=RequestControl(4); gateway=FakeGateway(); current=owner(control)
        session=ModelSession.start(gateway,'document-embedding',bound_source(current),current['deadline'],
                                   current['cancelled'],current['accept'])
        session.batch({'kind':'dimension'}); session.binding['sourceScopeSha256']='f'*64
        with self.assertRaisesRegex(OperationError,'receipt identity differs'): session.finish()
        self.assertFalse(session.released); self.assertEqual(gateway.cancels,1)

    def test_unexpected_summary_or_release_fields_do_not_escape_into_receipt(self):
        for field in ('extra_summary','extra_release'):
            with self.subTest(field=field):
                control=RequestControl(4); gateway=FakeGateway(); current=owner(control)
                session=ModelSession.start(gateway,'document-embedding',bound_source(current),current['deadline'],
                                           current['cancelled'],current['accept'])
                session.batch({'kind':'dimension'}); setattr(gateway,field,True)
                with self.assertRaises(OperationError): session.finish()
                self.assertFalse(session.released); self.assertEqual(gateway.cancels,1)

    def test_receipt_serialization_occurs_before_acceptance_gate(self):
        control=RequestControl(4); gateway=FakeGateway(); current=owner(control); inside=[]
        def gate(action):
            def wrapped():
                inside.append(True)
                try: return action()
                finally: inside.pop()
            return current['accept'](wrapped)
        session=ModelSession.start(gateway,'document-embedding',bound_source(current),current['deadline'],
                                   current['cancelled'],gate)
        session.batch({'kind':'dimension'})
        def guarded(value):
            if inside and isinstance(value,dict) and value.get('schema')=='consense.fixed-model-session-receipt.v1':
                raise AssertionError('receipt serialized while holding acceptance gate')
            return encoded(value)
        with patch('fixed_model_session.encoded',side_effect=guarded): receipt=session.finish()
        self.assertEqual(receipt['schema'],'consense.fixed-model-session-receipt.v1')

    def test_cancel_before_gate_rejects_batch_and_finish_and_publication(self):
        for phase in ('batch', 'finish'):
            with self.subTest(phase=phase):
                control = RequestControl(4); gateway = FakeGateway(); current = owner(control)
                entered = threading.Event(); resume = threading.Event(); armed = threading.Event()
                def gate(action):
                    if armed.is_set(): entered.set(); resume.wait(2)
                    return current['accept'](action)
                session = ModelSession.start(gateway, 'document-embedding', bound_source(current), current['deadline'],
                                             current['cancelled'], gate)
                if phase == 'finish': session.batch({'kind':'dimension'})
                armed.set(); results = []; errors = []
                def invoke():
                    try: results.append(session.batch({'kind':'dimension'}) if phase == 'batch' else session.finish())
                    except Exception as error: errors.append(error)
                thread = threading.Thread(target=invoke); thread.start()
                try:
                    self.assertTrue(entered.wait(1)); control.request_cancel(); resume.set(); thread.join(2)
                    self.assertFalse(thread.is_alive()); self.assertEqual(results, [])
                    self.assertEqual(len(errors), 1); self.assertIsInstance(errors[0], OperationError)
                    self.assertEqual(gateway.cancels, 1); self.assertFalse(session.released)
                    published = []
                    with self.assertRaises(ValueError): control.publish(lambda: published.append(True))
                    self.assertEqual(published, [])
                finally:
                    resume.set(); thread.join(2)

    def test_cancel_before_ready_acceptance_cleans_known_operation(self):
        control = RequestControl(4); gateway = FakeGateway(); current = owner(control)
        entered = threading.Event(); resume = threading.Event()
        def gate(action):
            entered.set(); resume.wait(2)
            return current['accept'](action)
        results = []; errors = []
        def invoke():
            try: results.append(ModelSession.start(gateway, 'document-embedding', bound_source(current), current['deadline'],
                                                   current['cancelled'], gate))
            except Exception as error: errors.append(error)
        thread = threading.Thread(target=invoke); thread.start()
        try:
            self.assertTrue(entered.wait(1)); control.request_cancel(); resume.set(); thread.join(2)
            self.assertFalse(thread.is_alive()); self.assertEqual(results, [])
            self.assertEqual(len(errors), 1); self.assertIsInstance(errors[0], OperationError)
            self.assertEqual(errors[0].operation, 'b'*32); self.assertEqual(gateway.cancels, 1)
            self.assertEqual(errors[0].exit_receipt['status'], 'release_unknown')
        finally:
            resume.set(); thread.join(2)

    def test_acceptance_before_cancel_but_publication_stays_blocked(self):
        for phase in ('batch', 'finish'):
            with self.subTest(phase=phase):
                control = RequestControl(4); gateway = FakeGateway(); current = owner(control)
                accepted = threading.Event(); resume = threading.Event()
                armed = threading.Event()
                def gate(action):
                    def run():
                        value = action()
                        if armed.is_set(): accepted.set(); resume.wait(2)
                        return value
                    return current['accept'](run)
                session = ModelSession.start(gateway, 'document-embedding', bound_source(current), current['deadline'], current['cancelled'], gate)
                if phase == 'finish': session.batch({'kind':'dimension'})
                armed.set(); results = []; errors = []
                def invoke():
                    try: results.append(session.batch({'kind':'dimension'}) if phase == 'batch' else session.finish())
                    except Exception as error: errors.append(error)
                thread = threading.Thread(target=invoke); thread.start(); cancellations = []
                cancel_thread = threading.Thread(target=lambda: cancellations.append(session.cancel()))
                try:
                    self.assertTrue(accepted.wait(1)); control.request_cancel()
                    cancel_thread.start(); cancel_thread.join(1)
                    self.assertFalse(cancel_thread.is_alive())
                    if phase == 'batch': self.assertEqual(cancellations[0]['status'], 'release_unknown')
                    resume.set(); thread.join(2)
                    self.assertFalse(thread.is_alive()); self.assertEqual(len(results), 1); self.assertEqual(errors, [])
                    if phase == 'batch': self.assertEqual(results[0], {'dimension':2})
                    else: self.assertEqual(results[0]['release']['status'], 'released')
                    with self.assertRaises(ValueError): control.publish(lambda: self.fail('publication after cancellation'))
                finally:
                    resume.set(); thread.join(2)
                    if cancel_thread.ident is not None: cancel_thread.join(2)


if __name__ == '__main__': unittest.main()
