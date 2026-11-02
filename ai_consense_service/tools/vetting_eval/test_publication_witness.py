"""Small offline checks for the proposed journal witness; no model or Docker call."""
import ast
import copy
import hashlib
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, contextmanager
from contextvars import ContextVar
from io import BytesIO
import json
import threading
import unittest
from types import SimpleNamespace

from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import oer_request_evidence as evidence
from oer_request_evidence import RequestEvidence, _encode
import model_session_observer
import migration_project_registry
import server
import test_model_release_status as fixture_module
from source_request_context import (RequestControl, commit_pending, prewrite,
                                    publication_reference, request_context, stage)
from token_windows import WindowError


class PublicationWitnessTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture_module.ModelReleaseStatusTest(
            'test_authenticated_saved_status_accepts_exact_completion')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.scope = {'sourceScopeSha256': 'b' * 64, 'sourceDeclarationSha256': 'f' * 64}
        self.metadata = {'sourceScope': {'projectId': 'p1', 'module': 'vetting', **self.scope}}
        self.receipt = {'publication': {
            'prewrite': {'projectId': 'p1', 'module': 'vetting', 'sourceScopeSha256': 'b' * 64},
            'intent': {'schema': 'consense.index-publication-intent.v2',
                       'sourceFramePhase': 'final_checks_passed_before_lease_close',
                       'projectId': 'p1', 'generation': 'a' * 32,
                       'metadataSha256': 'c' * 64, 'priorMetadataSha256': None}}}
        f = self.fixture
        self.model = {'schema': 'consense.model-release-evidence.v1', **self.scope,
                      'profileSha256': f.receipt['profileSha256'],
                      'executionSha256': 'e' * 64,
                      'bindings': {'embedding': copy.deepcopy(f.binding)},
                      'records': [{'scopeOrdinal': 0, 'modelKind': 'embedding',
                                   'operationId': f.operation, **copy.deepcopy(f.batch)}],
                      'receipts': [copy.deepcopy(f.receipt)],
                      'statuses': {f.operation: copy.deepcopy(f.status)}}
        self.source = {'schema': 'consense.source-frame-evidence.v1',
                       'phase': 'final_checks_passed_before_lease_close', **self.scope}
        self.journal = RequestEvidence(Path('unused'))

    def pinned(self, model=None, source=None, *, reverse=False):
        events = [('model-release-verified', self.model if model is None else model),
                  ('source-frame-verified', self.source if source is None else source)]
        if reverse:
            events.reverse()
        raw = b''.join(_encode({'ordinal': ordinal, 'stage': stage,
                                'elapsedSeconds': 0.0, 'payload': payload}) + b'\n'
                       for ordinal, (stage, payload) in enumerate(events, 1))
        return type('Pinned', (), {'files': [{'path': Path('events.jsonl'),
                                             'stream': BytesIO(raw)}]})()

    def verify(self, pinned):
        return self.journal._verify_publication_evidence(
            self.metadata, {}, {}, self.receipt, pinned)

    def test_exact_source_and_released_model_pass(self):
        proof = self.verify(self.pinned())
        self.assertEqual({'sourceEvidenceSha256', 'modelReleaseEvidenceSha256'}, set(proof))
        self.assertTrue(all(len(value) == 64 for value in proof.values()))

    def test_wrong_source_or_worker_release_fails(self):
        wrong_source = {**self.source, 'sourceScopeSha256': '0' * 64}
        with self.assertRaises(WindowError):
            self.verify(self.pinned(source=wrong_source))
        wrong_model = copy.deepcopy(self.model)
        wrong_model['statuses'][self.fixture.operation]['release']['status'] = 'release_unknown'
        with self.assertRaises(WindowError):
            self.verify(self.pinned(model=wrong_model))

    def test_wrong_order_or_missing_status_fails(self):
        with self.assertRaises(WindowError):
            self.verify(self.pinned(reverse=True))
        missing = copy.deepcopy(self.model)
        missing['statuses'].clear()
        with self.assertRaises(WindowError):
            self.verify(self.pinned(model=missing))

    def test_observer_saves_independent_status_copy(self):
        observer = self.fixture.observer()
        self.assertIs(observer.verify_session(self.fixture.receipt), True)
        self.assertEqual(self.fixture.status, observer.release_statuses[self.fixture.operation])
        self.fixture.status['release']['status'] = 'changed'
        self.assertEqual('released', observer.release_statuses[self.fixture.operation]['release']['status'])

    def test_publication_and_restart_auto_recovery_read_same_witness(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary) / 'state'
            journal = RequestEvidence(root / 'run-evidence')
            (root / 'projects').mkdir(parents=True)
            metadata_path = journal.metadata_path('p1')
            with patch.object(evidence, '_sync_file'), patch.object(evidence, '_sync_directory'):
                with request_context(RequestControl(20)), journal.operation(
                        '/index-canonical', {'projectId': 'p1'}) as record:
                    prewrite({'projectId': 'p1', 'generation': 'a' * 32,
                              'collection': 'fixture', 'priorMetadataSha256': None,
                              'module': 'vetting', 'sourceScopeSha256': 'b' * 64,
                              'routeIdentity': '9' * 64})
                    journal.observe('model-release-verified', self.model)
                    journal.observe('source-frame-verified', self.source)
                    metadata = {'projectId': 'p1', 'generation': 'a' * 32,
                                'collection': 'fixture', 'publicationJournal': publication_reference(),
                                'sourceScope': {'projectId': 'p1', 'module': 'vetting', **self.scope}}
                    pending = root / 'pending.json'
                    raw = json.dumps(metadata).encode()
                    pending.write_bytes(raw)
                    stage(lambda: pending.replace(metadata_path),
                          {'projectId': 'p1', 'generation': 'a' * 32,
                           'metadataSha256': hashlib.sha256(raw).hexdigest(),
                           'priorMetadataSha256': None})
                    commit_pending(source_frame_preclose_verified=True, model_session_verified=True)
                    record['response'] = evidence._save(record['directory'] / 'response.json',
                                                        {'projectId': 'p1', 'ok': True})
                self.assertEqual('completed', journal.verify_index(metadata_path, metadata, 'p1')['terminalStatus'])
                fresh = RequestEvidence(journal.root)
                frame_var = ContextVar('fixture_source_frame', default=None)
                guard = lambda: {'software': 'fixture'}
                runtime = SimpleNamespace(algorithms=guard,
                    source_registry=SimpleNamespace(_validation_frame=frame_var))
                with patch.object(server, 'STATE', root), patch.object(server, '_request_evidence', fresh), \
                     patch.object(server, '_remote_models', object()), patch.object(server, '_projects', {}), \
                     patch.object(server, 'window_runtime', return_value=runtime), \
                     patch.object(migration_project_registry, 'runtime_route', return_value={'mode': 'ordinary'}):
                    with self.assertRaises(WindowError):
                        server.load_project_metadata('p1')
                    self.assertFalse(fresh.block_path('p1').exists())
                    frame = {'guard': guard, 'leaseStack': ExitStack(), 'indexMetadata': {}}
                    token = frame_var.set(frame)
                    try:
                        self.assertEqual('a' * 32, server.load_project_metadata('p1')['generation'])
                        self.assertEqual({'p1'}, frame['recoveredIndexKeys'])
                    finally:
                        frame_var.reset(token)
                    self.assertEqual('completed', fresh.verify_index(metadata_path, metadata, 'p1')['terminalStatus'])
                    # A later request reuses the already-verified publication.
                    # Its frame must still revoke that verification on failed close.
                    source = ast.parse((Path(__file__).parent / 'oer_server.py').read_text(encoding='utf-8'))
                    app_node = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == 'create_app')
                    frame_node = next(node for node in app_node.body if isinstance(node, ast.FunctionDef) and node.name == 'source_frame')
                    class Registry:
                        _validation_frame = frame_var
                        @contextmanager
                        def validation_frame(self, **_):
                            token = frame_var.set({'guard': guard, 'leaseStack': ExitStack(), 'indexMetadata': {}})
                            try:
                                yield
                            finally:
                                frame_var.reset(token)
                                raise OSError('later frame close failed')
                    namespace = {'contextmanager': contextmanager, 'source_registry': Registry(),
                                 'backend': SimpleNamespace(_windows_runtime=runtime),
                                 'journal': fresh, 'WindowError': WindowError}
                    module = ast.Module(body=[frame_node], type_ignores=[])
                    exec(compile(ast.fix_missing_locations(module), '<candidate-source-frame>', 'exec'), namespace)
                    with self.assertRaises(WindowError):
                        with namespace['source_frame']():
                            server.recover_project_publication_for_read('p1')
                    self.assertTrue(fresh.block_path('p1').exists())
                    with self.assertRaises(WindowError):
                        fresh.verify_index(metadata_path, metadata, 'p1')
                    self.assertEqual('p1', fresh.recover_index(metadata_path, metadata, 'p1')['projectId'])

    def test_source_frame_close_failure_dispatches_durable_reblock(self):
        source = ast.parse((Path(__file__).parent / 'oer_server.py').read_text(encoding='utf-8'))
        create_app = next(node for node in source.body
                          if isinstance(node, ast.FunctionDef) and node.name == 'create_app')
        source_frame_node = next(node for node in create_app.body
                                 if isinstance(node, ast.FunctionDef) and node.name == 'source_frame')
        frame = {'recoveredIndexKeys': {'p1'}}
        frame_var = ContextVar('fixture_source_frame', default=None)
        class Registry:
            _validation_frame = frame_var
            @contextmanager
            def validation_frame(self, **_):
                token = frame_var.set(frame)
                try:
                    yield
                finally:
                    frame_var.reset(token)
                    raise OSError('fixture lease close failed')
        namespace = {'contextmanager': contextmanager, 'source_registry': Registry(),
                     'backend': SimpleNamespace(_windows_runtime=SimpleNamespace(algorithms=lambda: {})),
                     'journal': self.journal, 'WindowError': WindowError}
        module = ast.Module(body=[source_frame_node], type_ignores=[])
        exec(compile(ast.fix_missing_locations(module), '<candidate-source-frame>', 'exec'), namespace)
        with patch.object(self.journal, 'block_after_frame_failure') as block:
            with self.assertRaises(WindowError):
                with namespace['source_frame']():
                    pass
            block.assert_called_once_with('p1')
        with patch.object(self.journal, 'block_after_frame_failure') as block:
            with self.assertRaises(WindowError):
                with namespace['source_frame']():
                    raise WindowError('FIXTURE_BUSINESS_FAILURE', 'request rejected')
            block.assert_called_once_with('p1')

    def test_two_frame_failures_keep_the_same_block(self):
        with TemporaryDirectory() as temporary:
            journal = RequestEvidence(Path(temporary) / 'run-evidence')
            journal.root.mkdir()
            journal._verified['p1'] = ('journal-one', 'generation', 'metadata', 'receipt')
            with patch.object(evidence, '_sync_file'), patch.object(evidence, '_sync_directory'):
                with ThreadPoolExecutor(max_workers=2) as pool:
                    list(pool.map(journal.block_after_frame_failure, ['p1', 'p1']))
                self.assertEqual({'recovery': 'journal-one', 'projectId': 'p1'},
                                 json.loads(journal.block_path('p1').read_text(encoding='utf-8')))
                self.assertNotIn('p1', journal._verified)
                self.assertIn('p1', journal._uncertain)
                with self.assertRaises(WindowError):
                    journal.verify_index(journal.metadata_path('p1'), None, 'p1')

    def test_business_exception_does_not_reblock_verified_index(self):
        source = ast.parse((Path(__file__).parent / 'oer_server.py').read_text(encoding='utf-8'))
        app_node = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == 'create_app')
        frame_node = next(node for node in app_node.body if isinstance(node, ast.FunctionDef) and node.name == 'source_frame')
        frame_var = ContextVar('business_frame', default=None)
        class Registry:
            _validation_frame = frame_var
            @contextmanager
            def validation_frame(self, **_):
                token = frame_var.set({'recoveredIndexKeys': {'p1'}})
                try:
                    yield
                finally:
                    frame_var.reset(token)
        namespace = {'contextmanager': contextmanager, 'source_registry': Registry(),
                     'backend': SimpleNamespace(_windows_runtime=SimpleNamespace(algorithms=lambda: {})),
                     'journal': self.journal, 'WindowError': WindowError}
        module = ast.Module(body=[frame_node], type_ignores=[])
        exec(compile(ast.fix_missing_locations(module), '<candidate-source-frame>', 'exec'), namespace)
        with patch.object(self.journal, 'block_after_frame_failure') as block:
            with self.assertRaises(WindowError):
                with namespace['source_frame']():
                    raise WindowError('FIXTURE_BUSINESS_FAILURE', 'request rejected')
            block.assert_not_called()

    def test_first_recovery_concurrent_requests_do_not_conflict(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = root / 'metadata.json'
            metadata.write_text('{"publicationJournal":"fixture"}', encoding='utf-8')
            frame_var = ContextVar('concurrent_frame', default=None)
            guard = lambda: {}
            runtime = SimpleNamespace(algorithms=guard, source_registry=SimpleNamespace(_validation_frame=frame_var))
            journal = RequestEvidence(root / 'run-evidence')
            entered, resume, overlap = threading.Event(), threading.Event(), threading.Event()
            calls = []
            def recover(*_):
                calls.append(1)
                if len(calls) > 1:
                    overlap.set()
                    raise WindowError('INDEX_PUBLICATION_RECOVERY_CONFLICT', 'duplicate')
                entered.set()
                if not resume.wait(3):
                    raise RuntimeError('fixture timeout')
                journal._verified['p1'] = ('j', 'g', 'm', 'r')
            def run():
                token = frame_var.set({'guard': guard, 'leaseStack': ExitStack()})
                try:
                    server.recover_project_publication_for_read('p1')
                    return frame_var.get()['recoveredIndexKeys']
                finally:
                    frame_var.reset(token)
            with patch.object(server, '_remote_models', object()), patch.object(server, 'request_evidence', return_value=journal), \
                 patch.object(server, 'metadata_path', return_value=metadata), patch.object(server, 'window_runtime', return_value=runtime), \
                 patch.object(journal, 'recover_index', side_effect=recover), \
                 patch.object(migration_project_registry, 'runtime_route', return_value={'mode': 'ordinary'}):
                with ThreadPoolExecutor(max_workers=2) as pool:
                    first = pool.submit(run)
                    self.assertTrue(entered.wait(3))
                    second = pool.submit(run)
                    overlap.wait(0.3)
                    resume.set()
                    self.assertEqual({'p1'}, first.result(timeout=3))
                    self.assertEqual({'p1'}, second.result(timeout=3))
            self.assertFalse(overlap.is_set())
            self.assertEqual(1, len(calls))

    def test_retrieve_holds_backend_lock_until_frame_exit_and_rejects_cancelled_waiter(self):
        source = ast.parse((Path(__file__).parent / 'oer_server.py').read_text(encoding='utf-8'))
        app_node = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == 'create_app')
        retrieve_node = next(node for node in app_node.body if isinstance(node, ast.FunctionDef) and node.name == 'retrieve')
        entered, allow_close, second_entered = threading.Event(), threading.Event(), threading.Event()
        lock = threading.RLock()
        @contextmanager
        def frame():
            yield
            entered.set()
            if not allow_close.wait(3):
                raise RuntimeError('fixture timeout')
            raise WindowError('FIXTURE_FRAME_FAILURE', 'failed exit')
        backend = SimpleNamespace(LOCK=lock, project_key=lambda p: p, _windows_runtime=object(),
                                  retrieve=lambda request: {})
        def metadata(_):
            return None
        namespace = {'backend': backend, 'source_frame': frame, 'current_metadata': metadata,
                     'assert_canonical_scope': lambda *args, **kwargs: None,
                     'policy': SimpleNamespace(policyIdentity={}), 'target_module': 'vetting',
                     'copy': copy, 'fail': lambda *args, **kwargs: None,
                     'WindowError': WindowError, 'rejected': lambda error: error}
        module = ast.Module(body=[retrieve_node], type_ignores=[])
        exec(compile(ast.fix_missing_locations(module), '<candidate-retrieve>', 'exec'), namespace)
        request = SimpleNamespace(projectId='p1')
        control = RequestControl(10)
        def waiting_read():
            with request_context(control):
                try:
                    namespace['retrieve'](request)
                except WindowError:
                    return 'cancelled'
                second_entered.set()
                return 'returned'
        def first_read():
            try:
                namespace['retrieve'](request)
            except WindowError:
                return 'frame-failed'
        def index_lock():
            with lock:
                second_entered.set()
        with patch.object(migration_project_registry, 'runtime_route', return_value={'mode': 'ordinary'}):
            with ThreadPoolExecutor(max_workers=3) as pool:
                first = pool.submit(first_read)
                self.assertTrue(entered.wait(3))
                second = pool.submit(waiting_read)
                self.assertFalse(second_entered.wait(0.2))
                control.cancel()
                allow_close.set()
                self.assertEqual('frame-failed', first.result(timeout=3))
                self.assertEqual('cancelled', second.result(timeout=3))
                self.assertFalse(second_entered.is_set())

    def test_guarded_status_holds_backend_lock_until_frame_exit(self):
        source = ast.parse((Path(__file__).parent / 'oer_server.py').read_text(encoding='utf-8'))
        app_node = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == 'create_app')
        status_node = next(node for node in app_node.body if isinstance(node, ast.FunctionDef) and node.name == 'guarded_status')
        closing, resume, second_entered = threading.Event(), threading.Event(), threading.Event()
        lock = threading.RLock()
        @contextmanager
        def frame():
            yield
            closing.set()
            if not resume.wait(3):
                raise RuntimeError('fixture timeout')
        backend = SimpleNamespace(LOCK=lock, project_key=lambda p: p, _windows_runtime=object())
        namespace = {'backend': backend, 'source_frame': frame, 'fail': lambda *args, **kwargs: None,
                     'WindowError': WindowError, 'rejected': lambda error: error}
        module = ast.Module(body=[status_node], type_ignores=[])
        exec(compile(ast.fix_missing_locations(module), '<candidate-status>', 'exec'), namespace)
        def index_lock():
            with lock:
                second_entered.set()
        with patch.object(migration_project_registry, 'runtime_route', return_value={'mode': 'ordinary'}):
            with ThreadPoolExecutor(max_workers=2) as pool:
                first = pool.submit(namespace['guarded_status'], lambda request: 'ok', SimpleNamespace(projectId='p1'))
                self.assertTrue(closing.wait(3))
                second = pool.submit(index_lock)
                self.assertFalse(second_entered.wait(0.2))
                resume.set()
                self.assertEqual('ok', first.result(timeout=3))
                second.result(timeout=3)
                self.assertTrue(second_entered.is_set())


if __name__ == '__main__':
    unittest.main()
