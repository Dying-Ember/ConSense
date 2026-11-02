"""Request-owned cancellation and finite publication boundary for remote models."""
from contextlib import contextmanager
from contextvars import ContextVar, copy_context
import asyncio
import threading
import time
import math
import uuid

from token_windows import WindowError


_current = ContextVar('source_request_control', default=None)
_deferred = ContextVar('source_index_publication_deferred', default=False)

# Entries are pruned only after the actual owning thread has exited.
_owned_workers = {}
_owned_worker_loops = {}
_owned_workers_lock = threading.Lock()


class RequestControl:
    def __init__(self, timeout):
        if type(timeout) not in (int, float) or not math.isfinite(timeout) or timeout <= 0:
            raise ValueError('A finite positive request deadline is required')
        self.deadline = time.monotonic() + timeout
        self.request_id = uuid.uuid4().hex
        self.event = threading.Event()
        self.intent = threading.Event()
        self.lock = threading.Lock()
        self.cancel_lock = threading.Lock()
        self.cancel_started = False
        self.pending = None
        self.journal_id = None
        self.prepare_intent = None
        self.prepare_prewrite = None
        self.index_scope = None

    def cancelled(self):
        return self.intent.is_set() or self.event.is_set() or time.monotonic() >= self.deadline

    def cancel(self):
        self.intent.set()
        with self.lock:
            self.event.set()

    def request_cancel(self):
        self.intent.set()
        with self.cancel_lock:
            if not self.cancel_started:
                self.cancel_started = True
                threading.Thread(target=self.cancel, daemon=True).start()

    def check(self):
        if self.cancelled():
            raise WindowError('SOURCE_REQUEST_CANCELLED', 'The owning request was cancelled or its deadline elapsed')

    def publish(self, action):
        with self.lock:
            # The deadline and cancel order are fixed before the atomic publication starts.
            self.check()
            return action()


@contextmanager
def request_context(control):
    token = _current.set(control)
    try:
        yield
    finally:
        _current.reset(token)


def check():
    control = _current.get()
    if control is not None:
        control.check()


def publish(action):
    control = _current.get()
    return action() if control is None else control.publish(action)


def active():
    return _current.get() is not None


def model_session_control():
    """Bind provisional result acceptance to the same gate as cancellation and publication."""
    control = _current.get()
    if control is None:
        raise WindowError('SOURCE_REQUEST_OWNER_REQUIRED', 'A request-owned model session is required')
    control.check()
    return {'requestId': control.request_id, 'deadline': control.deadline,
            'cancelled': control.cancelled, 'accept': control.publish}


def require_index_session():
    control = _current.get()
    if control is None or control.index_scope is None:
        raise WindowError('MODEL_SESSION_RELEASE_REQUIRED', 'Request-owned model session is required for indexing')
    control.check()
    return control.index_scope


@contextmanager
def index_model_session(scope, binding):
    control = _current.get()
    if control is None or control.index_scope is not None or scope is None or not isinstance(binding,dict):
        raise WindowError('MODEL_SESSION_OWNER_INVALID', 'One request-owned index model session is required')
    control.check()
    control.index_scope = binding
    try:
        yield
    finally:
        control.index_scope = None


def bind_journal(journal_id, prepare_intent, prepare_prewrite=None):
    control = _current.get()
    if control is not None:
        if control.journal_id is not None or not journal_id or not callable(prepare_intent):
            raise WindowError('SOURCE_JOURNAL_BINDING_INVALID', 'A unique request journal is required')
        control.journal_id, control.prepare_intent = journal_id, prepare_intent
        control.prepare_prewrite = prepare_prewrite


def prewrite(binding):
    control = _current.get()
    if control is None or control.journal_id is None or not callable(control.prepare_prewrite):
        raise WindowError('SOURCE_JOURNAL_BINDING_INVALID', 'A durable request journal is required before index writes')
    control.publish(lambda: control.prepare_prewrite(binding))


def publication_reference():
    control = _current.get()
    if control is None:
        return None
    if control.journal_id is None or control.prepare_intent is None:
        raise WindowError('SOURCE_JOURNAL_BINDING_INVALID', 'A durable request journal is required')
    return control.journal_id


def stage(action, intent=None):
    control = _current.get()
    if control is None:
        return action()
    control.check()
    if control.pending is not None:
        raise WindowError('SOURCE_PUBLICATION_CONFLICT', 'One request cannot stage multiple index publications')
    if intent is None or publication_reference() is None:
        raise WindowError('SOURCE_JOURNAL_BINDING_INVALID', 'A bound publication intent is required')
    control.pending = (action, intent)


def commit_pending(source_frame_verified=False, model_session_verified=False, *, source_frame_preclose_verified=False):
    control = _current.get()
    if control is not None and control.pending is not None:
        if ((source_frame_verified is True) == (source_frame_preclose_verified is True)):
            raise WindowError('SOURCE_FRAME_PROOF_REQUIRED', 'Final source frame verification is required before publication')
        if model_session_verified is not True:
            raise WindowError('MODEL_SESSION_RELEASE_REQUIRED', 'Verified request-owned model release is required before publication')
        action, intent = control.pending
        def durable_publication():
            proof = ({'schema':'consense.index-publication-intent.v2',
                      'sourceFramePhase':'final_checks_passed_before_lease_close'}
                     if source_frame_preclose_verified is True
                     else {'sourceFrameVerified':True})
            control.prepare_intent({**intent, **proof})
            control.check()
            return action()
        result = control.publish(durable_publication)
        control.pending = None
        return result


def commit_unless_deferred():
    if not _deferred.get():
        commit_pending()


@contextmanager
def deferred_publication():
    token = _deferred.set(True)
    try:
        yield
    finally:
        _deferred.reset(token)


async def execute_owned(request, handler, timeout):
    """Keep the shared AnyIO token until the actual owned thread exits."""
    import anyio
    from remote_gpu_models import operation_context
    control = RequestControl(timeout)
    loop = asyncio.get_running_loop()
    limiter = anyio.to_thread.current_default_thread_limiter()
    result = loop.create_future()
    context = copy_context()
    state = {'transferred': False, 'released': False, 'aborted': False}

    async def disconnected():
        while True:
            if await request.is_disconnected():
                return True
            await asyncio.sleep(.05)

    monitor = asyncio.create_task(disconnected())
    monitor.add_done_callback(lambda done: None if done.cancelled() else done.exception())
    acquire = asyncio.create_task(limiter.acquire())

    def release_if_untransferred(done):
        if not done.cancelled() and done.exception() is None and state['aborted'] and not state['transferred'] and not state['released']:
            state['released'] = True
            limiter.release_on_behalf_of(acquire)
    acquire.add_done_callback(release_if_untransferred)

    def expired(outcome):
        return WindowError('SOURCE_REQUEST_TIMEOUT', 'Request deadline elapsed; worker outcome is ' + outcome,
                           requestId=control.request_id, journalId=control.journal_id, outcome=outcome)

    try:
        while not acquire.done():
            remaining = control.deadline - time.monotonic()
            if remaining <= 0:
                raise expired('not_started')
            done, _ = await asyncio.wait((acquire, monitor), timeout=remaining,
                                         return_when=asyncio.FIRST_COMPLETED)
            if monitor in done and acquire not in done:
                monitor.result()
                raise WindowError('SOURCE_REQUEST_CANCELLED', 'Client disconnected before worker started',
                                  requestId=control.request_id, journalId=None, outcome='not_started')
        acquire.result()
        if time.monotonic() >= control.deadline:
            raise expired('not_started')
        if monitor.done():
            monitor.result()
            raise WindowError('SOURCE_REQUEST_CANCELLED', 'Client disconnected before worker started',
                              requestId=control.request_id, journalId=None, outcome='not_started')

        def invoke():
            try:
                with request_context(control), operation_context(control.cancelled, control.deadline):
                    control.check()
                    value = handler()
                    commit_pending()
                outcome = (time.monotonic(), value, None)
            except BaseException as error:
                outcome = (time.monotonic(), None, error)
            def deliver():
                if not result.done():
                    result.set_result(outcome)
                release_after_exit()
            try:
                loop.call_soon_threadsafe(deliver)
            except RuntimeError:
                pass

        def release_after_exit():
            if worker.is_alive():
                loop.call_later(.001, release_after_exit)
                return
            with _owned_workers_lock:
                _owned_workers.pop(control.request_id, None)
                _owned_worker_loops.pop(control.request_id, None)
            if not state['released']:
                state['released'] = True
                limiter.release_on_behalf_of(acquire)

        worker = threading.Thread(target=lambda: context.run(invoke), daemon=True)
        with _owned_workers_lock:
            for key, running in list(_owned_workers.items()):
                if not running.is_alive():
                    del _owned_workers[key]
                    _owned_worker_loops.pop(key, None)
            if any(owner is not loop for owner in _owned_worker_loops.values()):
                raise WindowError('SOURCE_REQUEST_CAPACITY', 'A worker from another loop is still running',
                                  requestId=control.request_id, journalId=None, outcome='not_started')
            _owned_workers[control.request_id] = worker
            _owned_worker_loops[control.request_id] = loop
            try:
                worker.start()
            except BaseException:
                del _owned_workers[control.request_id]
                del _owned_worker_loops[control.request_id]
                raise
            state['transferred'] = True

        while True:
            remaining = control.deadline - time.monotonic()
            if result.done():
                finished, value, error = result.result()
                if finished < control.deadline:
                    if error is not None:
                        raise error
                    return value
            if remaining <= 0:
                raise expired('unknown')
            done, _ = await asyncio.wait((result, monitor), timeout=remaining,
                                         return_when=asyncio.FIRST_COMPLETED)
            if result in done:
                continue
            if monitor in done:
                monitor.result()
                raise WindowError('SOURCE_REQUEST_CANCELLED', 'Client disconnected; worker outcome is unknown',
                                  requestId=control.request_id, journalId=control.journal_id, outcome='unknown')
    finally:
        control.request_cancel()
        monitor.cancel()
        state['aborted'] = True
        if not acquire.done():
            acquire.cancel()
        else:
            release_if_untransferred(acquire)
