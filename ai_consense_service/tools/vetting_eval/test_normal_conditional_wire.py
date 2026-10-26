"""Authored NORMAL conditional GET work and trust checks; no live calls/models."""
from dataclasses import replace
import hashlib
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from oer_source_registry import _serialize
from oer_unified_server import create_unified_app
import test_normal_observed as normal_tests
from test_token_windows import CharacterTokenizer
from test_unified_canonical_snapshot_frame import ObserveSend


class NormalConditionalWireTests(unittest.TestCase):
    tearDown = normal_tests.NormalObservedHttpTests.tearDown
    publish = normal_tests.NormalObservedHttpTests.publish

    def setUp(self):
        normal_tests.NormalObservedHttpTests.setUp(self)
        self.module = os.environ.get('NORMAL_CONDITIONAL_TEST_MODULE', 'vetting')
        if self.module != 'vetting':
            self.client.close()
            self.app = create_unified_app(self.policy, backend=self.backend, target_module=self.module)
            self.rt = self.backend._windows_runtime
            self.rt.tokenizer = lambda kind: CharacterTokenizer()
            self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
            self.client = TestClient(self.app)
            self.store, self.registry = self.app.state.artifact_store, self.app.state.source_registry
            self.body['sourceBinding']['module'] = self.module

    def url(self, source, module=None):
        return '/canonical/' + source['projectionSha256'] + '?module=' + (module or self.module)

    def warm(self):
        source = self.publish()
        full = self.client.get(self.url(source))
        self.assertEqual(200, full.status_code, full.text)
        self.assertEqual(_serialize(source), full.content)
        return source, full

    def root_dumps(self, source, action):
        real, sizes = json.dumps, []
        def encode(value, *args, **kwargs):
            raw = real(value, *args, **kwargs)
            if isinstance(value, dict) and value.get('projectionSha256') == source['projectionSha256']:
                sizes.append(len(raw.encode('utf-8')))
            return raw
        with patch.object(json, 'dumps', encode):
            response = action()
        return response, sizes

    def assert_no_inference(self):
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)
        self.assertIsNone(self.registry._validation_frame.get())
        self.assertIsNone(self.store._read_session.get())

    def test_warm_304_streams_actual_bytes_without_full_root_decode_or_encode(self):
        source, full = self.warm()
        reads, read = [], self.store._read_uncached
        def traced(descriptor, *, retain):
            reads.append((descriptor['sha256'], retain))
            return read(descriptor, retain=retain)
        import oer_native_collection_server as transport
        import native_observation
        with patch.object(self.store, '_read_uncached', traced), \
                patch.object(transport, 'assert_canonical_scope', wraps=transport.assert_canonical_scope) as scope, \
                patch.object(native_observation, '_bytes', wraps=native_observation._bytes) as strict_bytes, \
                patch.object(self.registry, '_validate_normal', wraps=self.registry._validate_normal) as validate:
            response, sizes = self.root_dumps(source, lambda: self.client.get(
                self.url(source), headers={'If-None-Match': full.headers['etag']}))
        self.assertEqual(304, response.status_code, response.text)
        self.assertEqual(b'', response.content)
        self.assertEqual([], sizes, 'Stream exact unchanged root bytes without a complete JSON encode')
        strict_roots = [call for call in strict_bytes.call_args_list if isinstance(call.args[0], dict)
                        and call.args[0].get('projectionSha256') == source['projectionSha256']]
        self.assertEqual(0, len(strict_roots), 'An exact warm proof must not decode and re-encode the whole root')
        self.assertEqual(0, validate.call_count)
        scope.assert_called_once()
        self.assertEqual([source['observationManifestRef']['sha256']] * 2, [sha for sha, retain in reads],
                         'Actual complete CAS descriptor must be verified at entry and successful exit')
        for name in ('etag', 'x-projection-sha256', 'cache-control', 'content-type', 'x-content-type-options'):
            self.assertEqual(full.headers[name], response.headers[name])
        self.assertNotIn('content-length', response.headers)
        self.assert_no_inference()

    def test_cold_matching_etag_keeps_complete_validation_and_serialization_fallback(self):
        source = self.publish()
        etag = '"' + hashlib.sha256(_serialize(source)).hexdigest() + '"'
        with patch.object(self.registry, '_validate_normal', wraps=self.registry._validate_normal) as validate:
            response, sizes = self.root_dumps(source, lambda: self.client.get(
                self.url(source), headers={'If-None-Match': etag}))
        self.assertEqual(304, response.status_code, response.text)
        self.assertEqual(1, validate.call_count)
        self.assertGreaterEqual(len(sizes), 2)
        self.assert_no_inference()

    def test_generic_load_proof_without_canonical_alias_proof_keeps_full_wire_fallback(self):
        source = self.publish()
        with self.registry.validation_frame(software_guard=self.rt.algorithms):
            self.registry.load(source['projectionSha256'])
        self.assertTrue(self.registry._derived_results)
        etag = '"' + hashlib.sha256(_serialize(source)).hexdigest() + '"'
        response, sizes = self.root_dumps(source, lambda: self.client.get(
            self.url(source), headers={'If-None-Match': etag}))
        self.assertEqual(304, response.status_code, response.text)
        self.assertGreaterEqual(len(sizes), 2)
        self.assert_no_inference()

    def test_nonmatching_etag_retains_complete_200_body_and_headers(self):
        source, full = self.warm()
        response = self.client.get(self.url(source), headers={'If-None-Match': '"' + '0' * 64 + '"'})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(full.content, response.content)
        self.assertEqual(full.headers['etag'], response.headers['etag'])
        self.assert_no_inference()

    def dependency(self, source, kind):
        if kind == 'root':
            return self.registry._path(source['projectionSha256'])
        if kind == 'alias':
            return self.registry._normal_revision_path(source['projectionRevisionSha256'], pin=False)
        return self.store.root / source['observationManifestRef']['relativePath']

    def tamper(self, path, *, detach=False):
        raw, stat = path.read_bytes(), path.stat()
        changed = raw.replace(b'Visible row A', b'changed row A')
        self.assertNotEqual(raw, changed)
        self.assertEqual(len(raw), len(changed))
        if detach:
            path.unlink()
        path.write_bytes(changed)
        os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        return raw, stat

    def assert_entry_tamper_rejected(self, kind):
        source, full = self.warm()
        path = self.dependency(source, kind)
        raw, stat = self.tamper(path, detach=kind == 'alias')
        try:
            response = self.client.get(self.url(source), headers={'If-None-Match': full.headers['etag']})
            self.assertEqual(404, response.status_code, response.text)
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        self.assert_no_inference()

    def test_actual_root_tamper_with_restored_mtime_refuses_warm_304(self):
        self.assert_entry_tamper_rejected('root')

    def test_actual_alias_tamper_with_restored_mtime_refuses_warm_304(self):
        self.assert_entry_tamper_rejected('alias')

    def test_actual_cas_tamper_with_restored_mtime_refuses_warm_304(self):
        self.assert_entry_tamper_rejected('cas')

    def test_successful_exit_rehash_rejects_root_alias_and_cas_tamper_before_asgi_304(self):
        source, full = self.warm()
        observer = ObserveSend(self.app)
        self.client.close()
        self.client = TestClient(observer)
        original_check = self.registry._check_family_context
        for kind in ('root', 'alias', 'cas'):
            with self.subTest(kind=kind):
                path, backup, invoked = self.dependency(source, kind), [], []
                def at_exit(frame):
                    original_check(frame)
                    if not invoked:
                        invoked.append(True)
                        backup.append(self.tamper(path, detach=kind == 'alias'))
                observer.events.clear()
                try:
                    with patch.object(self.registry, '_check_family_context', at_exit):
                        response = self.client.get(self.url(source), headers={'If-None-Match': full.headers['etag']})
                    self.assertEqual(404, response.status_code, response.text)
                    self.assertNotIn(304, [event['status'] for event in observer.events
                                          if event['type'] == 'http.response.start'])
                finally:
                    if backup:
                        raw, stat = backup[0]
                        path.write_bytes(raw)
                        os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        self.assert_no_inference()

    def test_complete_policy_context_change_misses_proof_and_manifest_change_rejects(self):
        source, full = self.warm()
        original = self.registry.family_policy
        changed = replace(original, metadata_spans=original.metadata_spans + ((999, 999),))
        self.assertEqual(original.policyIdentity, changed.policyIdentity)
        try:
            self.registry.family_policy = changed
            with patch.object(self.registry, '_validate_normal', wraps=self.registry._validate_normal) as validate:
                response, sizes = self.root_dumps(source, lambda: self.client.get(
                    self.url(source), headers={'If-None-Match': full.headers['etag']}))
            self.assertEqual(304, response.status_code, response.text)
            self.assertEqual(1, validate.call_count)
            self.assertGreaterEqual(len(sizes), 2)
            self.registry.family_policy = replace(original, manifest_sha256='0' * 64)
            self.assertEqual(404, self.client.get(self.url(source), headers={'If-None-Match': full.headers['etag']}).status_code)
        finally:
            self.registry.family_policy = original
        self.assert_no_inference()

    def test_module_and_current_scope_checks_still_refuse_304(self):
        source, full = self.warm()
        headers = {'If-None-Match': full.headers['etag']}
        self.assertEqual(404, self.client.get(self.url(source, 'drafting'), headers=headers).status_code)
        import competition_scope
        with patch.object(competition_scope, 'EXCLUDED_CLASSES',
                          competition_scope.EXCLUDED_CLASSES | {'special_conditions_of_contract'}):
            self.assertEqual(404, self.client.get(self.url(source), headers=headers).status_code)
        import oer_native_collection_server as transport
        scope = transport.assert_canonical_scope
        original_module = self.registry.family_target_module
        def change_module(*args, **kwargs):
            scope(*args, **kwargs)
            self.registry.family_target_module = 'advisory' if original_module == 'vetting' else 'vetting'
        try:
            with patch.object(transport, 'assert_canonical_scope', change_module):
                self.assertEqual(404, self.client.get(self.url(source), headers=headers).status_code)
        finally:
            self.registry.family_target_module = original_module
        self.assert_no_inference()

    def test_actual_runtime_software_change_at_entry_and_exit_refuses_304(self):
        source, full = self.warm()
        import unified_source_registry as registry_module
        import oer_native_collection_server as transport
        path = Path(registry_module.__file__)
        raw, stat = path.read_bytes(), path.stat()
        scope = transport.assert_canonical_scope
        def change_software(*args, **kwargs):
            scope(*args, **kwargs)
            path.write_bytes(raw + b'\n# authored temporary runtime identity change\n')
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        try:
            path.write_bytes(raw + b'\n# authored temporary runtime identity change\n')
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            self.assertEqual(404, self.client.get(self.url(source), headers={'If-None-Match': full.headers['etag']}).status_code)
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            with patch.object(transport, 'assert_canonical_scope', change_software):
                self.assertEqual(404, self.client.get(self.url(source), headers={'If-None-Match': full.headers['etag']}).status_code)
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        self.assert_no_inference()

    def test_noncanonical_root_and_alias_cannot_promote_a_generic_load_proof(self):
        source = self.publish()
        root = self.registry._path(source['projectionSha256'])
        alias = self.registry._normal_revision_path(source['projectionRevisionSha256'], pin=False)
        raw = b' ' + _serialize(source)
        root.write_bytes(raw)
        self.assertEqual(raw, alias.read_bytes())
        with self.registry.validation_frame(software_guard=self.rt.algorithms):
            self.registry.load(source['projectionSha256'])
        etag = '"' + hashlib.sha256(raw).hexdigest() + '"'
        response = self.client.get(self.url(source), headers={'If-None-Match': etag})
        self.assertEqual(404, response.status_code, response.text)
        self.assert_no_inference()


if __name__ == '__main__':
    unittest.main()
