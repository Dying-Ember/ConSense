"""Synthetic credential drift must not escape an owning source frame."""
from contextlib import ExitStack
from contextvars import ContextVar
import hashlib
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import migration_project_registry as routing
import test_source_page_index as source_fixture
from token_windows import WindowError


KEY = 'CONSENSE_QDRANT_API_KEY'
ORIGINAL = 'AUTHORED-CREDENTIAL-ORIGINAL-ONLY'
CHANGED = 'AUTHORED-CREDENTIAL-CHANGED-ONLY'


class CredentialFrameTest(unittest.TestCase):
    def test_same_frame_reuse_and_exit_reject_drift_without_disclosure(self):
        for initial, changed in ((None, ''), ('', None), (ORIGINAL, CHANGED)):
            for mode in ('ordinary', 'legacy', 'migrated'):
                with self.subTest(initial_is_none=initial is None, changed_is_none=changed is None, mode=mode), \
                     patch.dict(os.environ, {}, clear=True), TemporaryDirectory() as directory:
                    if initial is not None:
                        os.environ[KEY] = initial
                    if mode == 'legacy':
                        os.environ[routing.LEGACY] = 'authored-receipt'
                    if mode == 'migrated':
                        raw = json.dumps({'schema': routing.SCHEMA, 'entries': [
                            {'module': 'vetting', 'projectId': 'authored', 'mode': 'migrated',
                             'receiptPath': str(Path(directory) / 'receipt.json'), 'receiptSha256': 'a' * 64}]}).encode()
                        file = Path(directory) / 'registry.json'
                        file.write_bytes(raw)
                        os.environ[routing.REGISTRY] = str(file)
                        os.environ[routing.REGISTRY + '_SHA256'] = hashlib.sha256(raw).hexdigest()
                    frame_var = ContextVar('authored_route_frame', default=None)
                    runtime = SimpleNamespace(target_module='vetting', source_registry=SimpleNamespace(_validation_frame=frame_var))
                    backend = SimpleNamespace(_migrated_index=None)
                    with ExitStack() as stack:
                        frame = {'indexMetadata': {}, 'leaseStack': stack}
                        token = frame_var.set(frame)
                        try:
                            first = routing.runtime_route(backend, 'authored', runtime)
                            self.assertEqual(first, routing.runtime_route(backend, 'authored', runtime))
                            if changed is None:
                                os.environ.pop(KEY, None)
                            else:
                                os.environ[KEY] = changed
                            with self.assertRaises(WindowError) as reuse:
                                routing.runtime_route(backend, 'authored', runtime)
                            with self.assertRaises(WindowError) as exit_error:
                                stack.close()
                            evidence = repr((first, frame, str(reuse.exception), str(exit_error.exception)))
                            self.assertNotIn(ORIGINAL, evidence)
                            self.assertNotIn(CHANGED, evidence)
                        finally:
                            frame_var.reset(token)
                    # A restored credential can be bound by a fresh frame.
                    if initial is None:
                        os.environ.pop(KEY, None)
                    else:
                        os.environ[KEY] = initial
                    with ExitStack() as stack:
                        token = frame_var.set({'indexMetadata': {}, 'leaseStack': stack})
                        try:
                            routing.runtime_route(backend, 'authored', runtime)
                        finally:
                            frame_var.reset(token)

    def test_unified_endpoints_withhold_result_when_credential_changes_at_frame_exit(self):
        for endpoint in ('/retrieve', '/index-canonical/status', '/index-canonical/availability'):
            with self.subTest(endpoint=endpoint), patch.dict(os.environ, {}, clear=True):
                fixture = source_fixture.SourcePageIndexHttpTests('runTest')
                fixture.setUp()
                try:
                    self.assertEqual(200, fixture.index().status_code)
                    key = 'AUTHORED-pins-index'
                    document = {'schema': routing.SCHEMA, 'entries': [
                        {'module': 'vetting', 'projectId': key, 'mode': 'ordinary'}]}
                    raw = json.dumps(document).encode()
                    registry_file = Path(fixture.root) / 'routes.json'
                    registry_file.write_bytes(raw)
                    values = {routing.REGISTRY: str(registry_file),
                              routing.REGISTRY + '_SHA256': hashlib.sha256(raw).hexdigest(), KEY: ORIGINAL}
                    body = {'projectId': key, 'module': 'vetting',
                            'projectionSha256': [fixture.projection['projectionSha256']]}
                    if endpoint == '/retrieve':
                        body = {'projectId': key, 'query': 'Visible', 'candidates': 4, 'limit': 2}
                    if endpoint.endswith('availability'):
                        body['coldBootstrap'] = True
                    registry = fixture.app.state.source_registry
                    original_check = registry._check_frame_files
                    calls = []
                    def change_after_final_io(frame):
                        original_check(frame)
                        calls.append(1)
                        os.environ[KEY] = CHANGED
                    with patch.dict(os.environ, values):
                        baseline = fixture.client.post(endpoint, json=body)
                        self.assertEqual(200, baseline.status_code, baseline.text)
                        with patch.object(registry, '_check_frame_files', side_effect=change_after_final_io):
                            rejected = fixture.client.post(endpoint, json=body)
                        self.assertTrue(calls)
                        self.assertEqual(422, rejected.status_code)
                        self.assertEqual('MIGRATION_ROUTE_CHANGED', rejected.json()['detail']['kind'])
                        self.assertNotIn(ORIGINAL, rejected.text)
                        self.assertNotIn(CHANGED, rejected.text)
                        self.assertNotIn(ORIGINAL, repr(fixture.events))
                        self.assertNotIn(CHANGED, repr(fixture.events))
                        os.environ[KEY] = ORIGINAL
                        self.assertEqual(200, fixture.client.post(endpoint, json=body).status_code)
                        for path in fixture.root.rglob('*'):
                            if path.is_file() and path.suffix in ('.json', '.jsonl'):
                                raw_file = path.read_bytes()
                                self.assertNotIn(ORIGINAL.encode(), raw_file)
                                self.assertNotIn(CHANGED.encode(), raw_file)
                finally:
                    fixture.tearDown()


if __name__ == '__main__':
    unittest.main()
