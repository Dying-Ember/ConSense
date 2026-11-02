"""Small arbitration checks; public HTTP cancellation still needs integration testing."""
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from source_request_context import (RequestControl, bind_journal, check, commit_pending,
                                    model_session_control, publish, request_context, stage)
from token_windows import WindowError


class RequestContextTest(unittest.TestCase):
    def test_cancel_before_publication_refuses_the_commit(self):
        control=RequestControl(30);writes=[]
        with request_context(control):
            control.cancel()
            with self.assertRaises(WindowError): publish(lambda:writes.append('committed'))
        self.assertEqual([],writes)
        self.assertEqual('local',publish(lambda:'local'))

    def test_completed_commit_wins_a_concurrent_cancel_without_replay(self):
        control=RequestControl(30);entered=threading.Event();finish=threading.Event();writes=[]
        def commit():
            with request_context(control):
                publish(lambda:(entered.set(),finish.wait(2),writes.append('committed')))
        worker=threading.Thread(target=commit);worker.start();self.assertTrue(entered.wait(1))
        attempting=threading.Event()
        def cancel(): attempting.set();control.cancel()
        cancelled=threading.Thread(target=cancel);cancelled.start();self.assertTrue(attempting.wait(1))
        self.assertFalse(control.event.is_set());finish.set()
        worker.join(2);cancelled.join(2)
        self.assertFalse(worker.is_alive());self.assertFalse(cancelled.is_alive());self.assertEqual(['committed'],writes)
        with request_context(control):
            with self.assertRaises(WindowError): check()

    def test_deadline_crossing_while_waiting_to_publish_refuses_commit(self):
        now=[10.0];writes=[];attempting=threading.Event();errors=[]
        with patch('source_request_context.time.monotonic',side_effect=lambda:now[0]):
            control=RequestControl(1)
            def worker():
                with request_context(control):
                    attempting.set()
                    try: publish(lambda:writes.append('committed'))
                    except WindowError as error: errors.append(error.kind)
            with control.lock:
                thread=threading.Thread(target=worker);thread.start();self.assertTrue(attempting.wait(1));now[0]=11.0
            thread.join(2)
        self.assertFalse(thread.is_alive());self.assertEqual([],writes);self.assertEqual(['SOURCE_REQUEST_CANCELLED'],errors)

    def test_invalid_deadlines_are_rejected(self):
        for value in (0,-1,float('inf'),float('nan'),True):
            with self.subTest(value=value),self.assertRaises(ValueError): RequestControl(value)

    def test_model_result_and_publication_share_cancel_gate(self):
        control=RequestControl(30);accepted=[]
        with self.assertRaises(WindowError):model_session_control()
        with request_context(control):
            owner=model_session_control()
            self.assertEqual(control.request_id,owner['requestId'])
            self.assertEqual(control.deadline,owner['deadline'])
            owner['accept'](lambda:accepted.append('batch'))
            control.cancel()
            self.assertTrue(owner['cancelled']())
            with self.assertRaises(WindowError):owner['accept'](lambda:accepted.append('late'))
            with self.assertRaises(WindowError):publish(lambda:accepted.append('metadata'))
        self.assertEqual(['batch'],accepted)

    def test_remote_index_without_durable_journal_fails_before_backend_access(self):
        from window_runtime import WindowRuntime
        class HttpError(Exception):
            def __init__(self,status,detail):self.status_code=status;self.detail=detail
        runtime=WindowRuntime.__new__(WindowRuntime)
        runtime.s=SimpleNamespace(HTTPException=HttpError)
        with request_context(RequestControl(30)):
            with self.assertRaises(HttpError) as raised:runtime.index(object())
        self.assertEqual(503,raised.exception.status_code)
        self.assertEqual('SOURCE_JOURNAL_BINDING_INVALID',raised.exception.detail['kind'])
        with request_context(RequestControl(30)):
            bind_journal('fixture',lambda intent:None)
            with self.assertRaises(HttpError) as raised:runtime.index(object())
        self.assertEqual(503,raised.exception.status_code)
        self.assertEqual('MODEL_SESSION_RELEASE_REQUIRED',raised.exception.detail['kind'])

    def test_unverified_source_frame_never_creates_intent_or_publishes(self):
        control=RequestControl(30);events=[]
        with request_context(control):
            bind_journal('journal',lambda intent:events.append(('intent',intent)))
            stage(lambda:events.append(('publish',)),{'projectId':'fixture'})
            with self.assertRaises(WindowError):commit_pending()
            self.assertEqual([],events)
            with self.assertRaises(WindowError):commit_pending(source_frame_verified=True)
            self.assertEqual([],events)
            commit_pending(source_frame_verified=True,model_session_verified=True)
        self.assertEqual([('intent',{'projectId':'fixture','sourceFrameVerified':True}),('publish',)],events)


if __name__ == '__main__': unittest.main()
