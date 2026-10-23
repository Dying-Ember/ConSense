"""Finite request ownership with authored images, clocks and providers only."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from unittest.mock import MagicMock
from types import SimpleNamespace
from PIL import Image
from fastapi import HTTPException

from artifact_store import ArtifactStore
from ocr_request_lifecycle import OcrRequest, OcrRequestError, cancel_request, read_request, RESPONSE_CAP


class OcrRequestTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.store = ArtifactStore(self.temporary.name)
        self.task_id = 'a' * 32
        self.binding = {'inputSha256': 'b' * 64, 'purpose': 'parse', 'sourceContext': {'page': 1}}

    def task(self, **kwargs):
        return OcrRequest(self.store, self.task_id, self.binding, **kwargs)

    def test_queue_time_counts_and_expired_request_never_acquires_or_dispatches(self):
        clock = [10.0]
        class Lock:
            acquisitions = 0
            def acquire(inner, timeout):
                inner.acquisitions += 1
                clock[0] += timeout
                return False
            def release(inner): raise AssertionError('Unacquired lock must not be released')
        lock = Lock()
        with patch('ocr_request_lifecycle.time.monotonic', side_effect=lambda: clock[0]), \
                patch('ocr_request_lifecycle.time.time', return_value=100):
            task = self.task(deadline_ms=100200, max_seconds=30)
            with self.assertRaises(OcrRequestError):
                with task.acquire(lock): self.fail('Expired queued work must not dispatch')
        self.assertLessEqual(lock.acquisitions, 3)

    def test_server_maximum_and_caller_deadline_cannot_extend_each_other(self):
        with patch('ocr_request_lifecycle.time.monotonic', return_value=10), \
                patch('ocr_request_lifecycle.time.time', return_value=100):
            task = self.task(deadline_ms=999999, max_seconds=3)
            self.assertEqual(task.deadline, 13)
            duplicate = self.task(deadline_ms=9999999, max_seconds=30)
            self.assertFalse(duplicate.fresh)
            self.assertEqual(duplicate.record['deadlineUnixMs'], 103000)

    def test_same_id_requires_exact_binding_and_never_claims_a_second_dispatch(self):
        self.assertTrue(self.task().fresh)
        self.assertFalse(self.task().fresh)
        for key, value in (('inputSha256', 'c' * 64), ('purpose', 'identity'), ('sourceContext', {'page': 2})):
            with self.subTest(key=key), self.assertRaises(OcrRequestError):
                OcrRequest(self.store, self.task_id, {**self.binding, key: value})
        with self.assertRaises(OcrRequestError): self.task().existing_result()

    def test_concurrent_same_id_claims_once_and_restart_unknown_never_dispatches(self):
        claims = []
        def claim():
            try: claims.append(self.task().fresh)
            except OcrRequestError: claims.append(False)
        threads = [threading.Thread(target=claim) for _ in range(2)]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
        self.assertEqual(sorted(claims), [False, True])
        with patch('ocr_request_lifecycle.PROCESS_INSTANCE', 'authored-new-process'):
            self.assertEqual(read_request(self.store, self.task_id)['status'], 'unknown')
            with self.assertRaises(OcrRequestError): self.task().existing_result()

    def test_cancellation_is_owned_and_terminal_only_after_release(self):
        task = self.task()
        other = OcrRequest(self.store, 'c' * 32, self.binding)
        with task.acquire(threading.RLock()):
            cancel_request(self.store, self.task_id)
            self.assertTrue(task.cancelled())
            self.assertFalse(other.cancelled())
            self.assertEqual(read_request(self.store, self.task_id)['status'], 'running')
            with self.assertRaises(OcrRequestError) as failure: task.check()
        task.fail(failure.exception)
        self.assertEqual(read_request(self.store, self.task_id)['status'], 'cancelled')

    def test_full_response_over_cap_is_retained_in_cas_and_never_replayed(self):
        task = self.task()
        value = {'completeObservation': 'x' * RESPONSE_CAP}
        with self.assertRaises(OcrRequestError): task.success(value)
        record = read_request(self.store, self.task_id)
        self.assertEqual(record['status'], 'completed')
        self.assertGreater(record['resultRef']['byteCount'], RESPONSE_CAP)
        self.assertEqual(json.loads(self.store.read_asset(record['resultRef'])), value)
        with self.assertRaises(OcrRequestError): self.task().existing_result()


class OcrRouteTests(unittest.TestCase):
    def setUp(self):
        import server
        self.server = server
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        stream = io.BytesIO(); Image.new('RGB', (2, 2), 'white').save(stream, format='PNG')
        self.raw = stream.getvalue()
        self.task_id = 'a' * 32
        self.state = patch.object(server, 'STATE', self.root); self.state.start(); self.addCleanup(self.state.stop)
        self.environment = patch.dict('os.environ', {'CONSENSE_COMPLETE_OCR_CONFIG': ''})
        self.environment.start(); self.addCleanup(self.environment.stop)

    def invoke(self, purpose='identity', deadline=None):
        return self.server.ocr(SimpleNamespace(file=io.BytesIO(self.raw)), purpose, None, self.task_id, deadline)

    def test_actual_route_counts_wait_and_never_loads_engine_after_late_lock_acquisition(self):
        clock = [0.0]
        class Lock:
            def acquire(self, timeout): clock[0] = 60; return True
            def release(self): pass
        with patch.object(self.server, 'LOCK', Lock()), \
                patch('time.monotonic', side_effect=lambda: clock[0]), patch('time.time', return_value=100), \
                patch.object(self.server, '_pinned_cpu_ocr') as engine:
            with self.assertRaises(HTTPException) as failure: self.invoke(deadline=103000)
        self.assertEqual(failure.exception.status_code, 504)
        engine.assert_not_called()
        self.assertEqual(self.server.ocr_task_status(self.task_id)['status'], 'expired')

    def test_actual_route_passes_same_deadline_and_cancellation_to_complete_provider(self):
        from test_complete_ocr import CompleteOcrTests
        config_bytes = json.dumps(CompleteOcrTests().config()).encode()
        config = self.root / 'complete.json'; config.write_bytes(config_bytes)
        clock = [0.0]
        class Lock:
            def acquire(self, timeout): clock[0] = 1; return True
            def release(self): pass
        def recognize(raw, config, store, **kwargs):
            self.assertEqual(kwargs['deadline'], 3)
            self.assertFalse(kwargs['cancelled']())
            return {'text': 'authored', 'provenance': {}}
        with patch.dict('os.environ', {'CONSENSE_COMPLETE_OCR_CONFIG': str(config),
                    'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': hashlib.sha256(config_bytes).hexdigest()}), \
                patch.object(self.server, 'LOCK', Lock()), patch('time.monotonic', side_effect=lambda: clock[0]), \
                patch('time.time', return_value=100), patch('complete_ocr.recognize_complete_page', side_effect=recognize) as provider:
            self.assertEqual(self.invoke('parse', 103000)['text'], 'authored')
            self.assertEqual(self.invoke('parse', 999999)['text'], 'authored')
        self.assertEqual(provider.call_count, 1)
        self.assertEqual(self.server.ocr_task_status(self.task_id)['status'], 'completed')
        self.assertEqual(self.server.ocr_task_result(self.task_id)['provenance']['requestTaskId'], self.task_id)

    def test_actual_route_retains_cancellation_until_owned_provider_has_released(self):
        from test_complete_ocr import CompleteOcrTests
        config_bytes = json.dumps(CompleteOcrTests().config()).encode()
        config = self.root / 'complete.json'; config.write_bytes(config_bytes)
        def recognize(raw, config, store, **kwargs):
            self.server.ocr_task_cancel(self.task_id)
            self.assertTrue(kwargs['cancelled']())
            self.assertEqual(self.server.ocr_task_status(self.task_id)['status'], 'running')
            raise RuntimeError('Authored owned provider finally released before propagating failure')
        with patch.dict('os.environ', {'CONSENSE_COMPLETE_OCR_CONFIG': str(config),
                    'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': hashlib.sha256(config_bytes).hexdigest()}), \
                patch('complete_ocr.recognize_complete_page', side_effect=recognize):
            with self.assertRaises(HTTPException): self.invoke('parse')
        self.assertEqual(self.server.ocr_task_status(self.task_id)['status'], 'cancelled')


class OwnedCancellationTests(unittest.TestCase):
    def test_only_owned_tree_is_stopped_and_release_is_proven_before_failure_returns(self):
        import native_provider_lifecycle as module
        cancelled, stopped = threading.Event(), threading.Event()
        class Tree:
            pid = 987654
            process = SimpleNamespace(poll=lambda: 0)
            def __init__(self, *args): pass
            def stop(self): stopped.set()
            def active(self): return 0 if stopped.is_set() else 1
            def close(self): pass
        provider = {'baseUrl': 'http://127.0.0.1:1', 'ownedBoot': {
            'cleanupTimeoutSeconds': .2, 'readyTimeoutSeconds': 1}}
        probe = MagicMock(); probe.__enter__.return_value.connect_ex.return_value = 1
        with tempfile.TemporaryDirectory() as directory, patch.object(module, 'validate_provider'), \
                patch.object(module, '_WindowsTree', Tree), patch.object(module, '_LinuxTree', Tree), \
                patch.object(module.socket, 'socket', return_value=probe), patch.object(module, '_wire_ready', return_value=True):
            with self.assertRaises(module.ProviderLifecycleError):
                with module.owned_native_provider(provider, 3, Path(directory) / 'owned', cancelled=cancelled.is_set) as lifecycle:
                    cancelled.set()
                    self.assertTrue(stopped.wait(.5))
            self.assertEqual(lifecycle['exitReceipt']['status'], 'released')
            self.assertEqual(lifecycle['exitReceipt']['pid'], Tree.pid)
            self.assertIsNotNone(lifecycle['exitReceipt']['primaryError'])

    def test_cancellation_does_not_adopt_or_stop_existing_remote_provider(self):
        import native_provider_lifecycle as module
        cancelled = threading.Event()
        with tempfile.TemporaryDirectory() as directory, patch.object(module, 'validate_provider'), \
                patch.object(module, '_WindowsTree') as windows, patch.object(module, '_LinuxTree') as linux:
            with module.owned_native_provider({'baseUrl': 'http://127.0.0.1:1'}, 3,
                    Path(directory) / 'remote', cancelled=cancelled.is_set) as lifecycle:
                cancelled.set()
            self.assertEqual(lifecycle['exitReceipt']['status'], 'remote_handoff_unverified')
            windows.assert_not_called(); linux.assert_not_called()

if __name__ == '__main__':
    unittest.main()
