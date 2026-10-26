"""Owned complete-scan and strict request transport fixtures; no live services."""
import asyncio
from collections import Counter
import copy
import hashlib
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from oer_native_pdf_server import _WireLimits
import oer_native_pdf_server as pdf_server
from source_read_server import _SourceReadRoute, STREAM_VERSION
from test_normal_observed import many_unit_fixture
import test_normal_conditional_wire as normal


class InterceptSend:
    def __init__(self, app, callback):
        self.app, self.callback, self.lines = app, callback, []

    async def __call__(self, scope, receive, send):
        async def observed(event):
            if event['type'] == 'http.response.body' and event.get('body'):
                for line in event['body'].splitlines():
                    value = json.loads(line)
                    self.lines.append(value)
                    self.callback(value)
            await send(event)
        return await self.app(scope, receive, observed)


class OwnedSemanticScanTests(unittest.TestCase):
    setUp = normal.NormalConditionalWireTests.setUp
    tearDown = normal.NormalConditionalWireTests.tearDown
    publish = normal.NormalConditionalWireTests.publish

    def prepare_scope(self, documents=1, units=7):
        sources = []
        for index in range(documents):
            body = many_unit_fixture(self.policy, units)
            body['sourceBinding']['module'] = self.module
            graph = body['candidateGraph']
            graph['source']['documentId'] = str(900 + index)
            graph['source']['parsedSourceHash'] = hashlib.sha256(('authored-parsed-' + str(index)).encode()).hexdigest()
            graph['generationSha256'] = hashlib.sha256(('authored-generation-' + str(index)).encode()).hexdigest()
            sources.append(self.publish(body))
        request = {'module': self.module, 'projectionSha256': [source['projectionSha256'] for source in sources],
            'view': 'semantic_units', 'operation': 'prepare',
            'semanticReadVersion': 'source-semantic-dictionary-v2', 'semanticReadProfile': 'text-first-scan-v1'}
        response = self.client.post('/sources/read', json=request)
        self.assertEqual(200, response.status_code, response.text)
        prepared = response.json()
        request.update(operation='scan', maxItems=2, maxBytes=65536,
            readManifestSha256=prepared['readManifestSha256'], readManifestToken=prepared['readManifestToken'])
        self.sources = sources
        return request

    def stream(self, request, client=None):
        response = (client or self.client).post('/sources/read', json=request,
            headers={'Accept': 'application/x-ndjson'})
        self.assertEqual(200, response.status_code, response.text)
        return [json.loads(line) for line in response.content.splitlines()]

    def test_complete_33_source_scan_has_one_entry_exit_proof_and_exact_text_order(self):
        request = self.prepare_scope(documents=33, units=2)
        request['maxItems'] = 7
        request['maxBytes'] = 1024 * 1024
        files, cas, manifest_decodes = Counter(), Counter(), Counter()
        original_identity, original_read = self.registry._stream_file_identity, self.store._read_uncached
        import native_observation
        import window_runtime
        original_json = native_observation._strict_json
        original_software_file = window_runtime.file_identity
        software_calls = [0]
        def software_file(path):
            if path.name == 'window_runtime.py':
                software_calls[0] += 1
            return original_software_file(path)
        def identity(path, *, maximum):
            files[str(path)] += 1
            return original_identity(path, maximum=maximum)
        def read(descriptor, *, retain):
            cas[descriptor['sha256']] += 1
            return original_read(descriptor, retain=retain)
        def decode(raw):
            value = original_json(raw)
            if value.get('schema') in ('consense.source-semantic-read-manifest.v2', 'consense.source-semantic-read-manifest.v3'):
                manifest_decodes[value['projectionSha256']] += 1
            return value
        with patch.object(self.registry, '_stream_file_identity', side_effect=identity), \
                patch.object(self.store, '_read_uncached', side_effect=read), \
                patch.object(window_runtime, 'file_identity', side_effect=software_file), \
                patch.object(native_observation, '_strict_json', side_effect=decode):
            lines = self.stream(request)
        self.assertEqual('complete', lines[-1]['type'], lines[-1])
        pages = [line['data'] for line in lines if line['type'] == 'page']
        self.assertGreater(len(pages), 1)
        self.assertEqual(list(range(1, len(pages) + 1)), [line['sequence'] for line in lines[:-1]])
        self.assertEqual({2}, set(files.values()), 'Root/alias hash-count histogram: ' + str(Counter(files.values())))
        self.assertEqual({2}, set(cas.values()), 'Actual-CAS hash-count histogram: ' + str(Counter(cas.values())))
        self.assertEqual(33, len(manifest_decodes))
        self.assertTrue(all(count == 1 for count in manifest_decodes.values()))
        self.assertEqual(2, software_calls[0])
        expected = [chunk for source in self.sources for chunk in source['chunks']]
        origins = [origin for page in pages for origin in page['origins']]
        self.assertEqual([row['id'] for row in expected], [row['id'] for row in origins])
        self.assertEqual([row['content'] for row in expected], [row['text'] for row in origins])
        complete = lines[-1]
        self.assertEqual(len(expected), complete['totalOrigins'])
        self.assertEqual(len(expected), complete['processedOrigins'])
        self.assertEqual(pages[0]['scopeIdentity']['scopeSha256'], complete['scopeSha256'])
        self.assertEqual(request['readManifestToken'], complete['readManifestToken'])
        self.assertTrue(complete['proofComplete'])
        print('OWNED_STREAM_AUTHORED_WORK=' + json.dumps({'sources': 33, 'pages': len(pages),
            'rootAliasDistinctFiles': len(files), 'rootAliasActualHashCalls': sum(files.values()),
            'casDistinctFiles': len(cas), 'casActualHashCalls': sum(cas.values()),
            'preparedManifestDecodes': sum(manifest_decodes.values()), 'softwareEntryExitCalls': software_calls[0]}))

    def test_json_compatibility_and_stream_capability_remain_available(self):
        request = self.prepare_scope()
        result = self.client.post('/sources/read', json=request)
        self.assertEqual(200, result.status_code, result.text)
        self.assertEqual('source-bound-demand-read-v1', result.json()['contract'])
        health = self.client.get('/health').json()
        self.assertIn(STREAM_VERSION, health['oer']['sourceRead']['semanticScanStreamVersions'])

    def test_same_size_same_timestamp_cas_change_at_exit_cannot_emit_complete(self):
        request = self.prepare_scope()
        source = self.sources[0]
        descriptor = source['observationManifestRef']
        target = self.store._path(descriptor)
        raw, stat = target.read_bytes(), target.stat()
        changed = [False]
        def mutate(line):
            if line['type'] == 'page' and not changed[0]:
                target.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
                os.utime(target, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                changed[0] = True
        observed = InterceptSend(self.app, mutate)
        try:
            with TestClient(observed) as client:
                lines = self.stream(request, client)
        finally:
            target.write_bytes(raw)
        self.assertTrue(changed[0])
        self.assertNotIn('complete', [line['type'] for line in lines])
        self.assertEqual('error', lines[-1]['type'])
        self.assertFalse(lines[-1]['proofComplete'])

    def test_root_locator_change_between_pages_is_rejected_before_complete(self):
        request = self.prepare_scope()
        target = self.registry._path(self.sources[0]['projectionSha256'])
        raw = target.read_bytes()
        changed = [False]
        def mutate(line):
            if line['type'] == 'page' and not changed[0]:
                target.write_bytes(raw + b' ')
                changed[0] = True
        try:
            with TestClient(InterceptSend(self.app, mutate)) as client:
                lines = self.stream(request, client)
        finally:
            target.write_bytes(raw)
        self.assertNotIn('complete', [line['type'] for line in lines])
        self.assertEqual('SOURCE_READ_STREAM_SOURCE_CHANGED', lines[-1]['kind'])

    def test_changed_software_at_owner_exit_rejects_complete(self):
        request = self.prepare_scope()
        target = Path(pdf_server.__file__)
        raw = target.read_bytes()
        original = self.registry._check_frame_files
        def changed(frame):
            original(frame)
            target.write_bytes(raw + b'\n# Authored isolated gate software mutation.\n')
        try:
            with patch.object(self.registry, '_check_frame_files', side_effect=changed):
                lines = self.stream(request)
        finally:
            target.write_bytes(raw)
        self.assertNotIn('complete', [line['type'] for line in lines])
        self.assertEqual('error', lines[-1]['type'])

    def test_invalid_scope_token_and_wrong_operation_do_not_complete(self):
        request = self.prepare_scope()
        bad = {**request, 'readManifestToken': '0' * 64}
        lines = self.stream(bad)
        self.assertEqual(['error'], [line['type'] for line in lines])
        response = self.client.post('/sources/read', json={**request, 'operation': 'catalog'},
            headers={'Accept': 'application/x-ndjson'})
        self.assertEqual(422, response.status_code)

    def test_disconnect_clears_owned_cas_and_never_completes_or_journals_success(self):
        request = self.prepare_scope()
        raw = json.dumps(request).encode()
        frames, caches = [], []
        original = self.store._read_uncached
        def capture(descriptor, *, retain):
            active = self.store._read_session.get()
            if active is not None and not any(active is prior for prior in caches):
                caches.append(active)
            return original(descriptor, retain=retain)
        async def run():
            supplied = False
            async def receive():
                nonlocal supplied
                if not supplied:
                    supplied = True
                    return {'type': 'http.request', 'body': raw, 'more_body': False}
                return {'type': 'http.disconnect'}
            async def send(event):
                if event['type'] == 'http.response.body' and event.get('body'):
                    frames.append(json.loads(event['body']))
                    raise OSError('Authored disconnected receiver')
            scope = {'type': 'http', 'asgi': {'version': '3.0', 'spec_version': '2.4'},
                'http_version': '1.1', 'method': 'POST', 'scheme': 'http', 'path': '/sources/read',
                'raw_path': b'/sources/read', 'query_string': b'',
                'headers': [(b'content-type', b'application/json'), (b'accept', b'application/x-ndjson')],
                'client': ('127.0.0.1', 1), 'server': ('authored-fixture', 80)}
            try:
                await self.app(scope, receive, send)
            except Exception:
                pass
        with patch.object(self.store, '_read_uncached', side_effect=capture):
            asyncio.run(run())
        self.assertEqual(['page'], [line['type'] for line in frames])
        self.assertTrue(caches)
        self.assertTrue(all(not cache for cache in caches), 'Disconnected generator must release each owned byte cache')
        receipts = [json.loads(path.read_bytes()) for path in self.app.state.request_evidence.root.glob('*/receipt.json')]
        self.assertTrue(any(row['terminalStatus'] == 'failed' for row in receipts))


class StrictRequestTransportTests(unittest.TestCase):
    def client(self):
        app = FastAPI()
        async def echo(body: dict):
            return body
        app.router.add_api_route('/sources/read', echo, methods=['POST'], route_class_override=_SourceReadRoute)
        app.add_middleware(_WireLimits, maximum=4096, strict_json_paths=('/sources/read',))
        return TestClient(app)

    def test_source_request_is_decoded_once_without_canonical_reencode(self):
        raw = b'{"module":"vetting","values":[1,2,"visible text"]}'
        original = json.loads
        seen = []
        def decoded(value, *args, **kwargs):
            if value == raw.decode():
                seen.append(value)
            return original(value, *args, **kwargs)
        with self.client() as client, patch.object(pdf_server, '_json', side_effect=AssertionError('Discarded canonical encode forbidden')), \
                patch.object(json, 'loads', side_effect=decoded):
            response = client.post('/sources/read', content=raw, headers={'Content-Type': 'application/json'})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(1, len(seen))
        self.assertEqual(original(raw), response.json())

    def test_duplicate_nonfinite_overflow_surrogate_and_utf8_inputs_remain_rejected(self):
        values = [b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}', b'{"x":1e10000}',
            b'{"x":"\\ud800"}', b'{"\\udfff":1}', b'{"x":"\xff"}']
        with self.client() as client:
            for raw in values:
                with self.subTest(raw=raw):
                    response = client.post('/sources/read', content=raw, headers={'Content-Type': 'application/json'})
                    self.assertEqual(422, response.status_code, response.text)

    def test_request_and_json_response_size_caps_remain(self):
        with self.client() as client:
            response = client.post('/sources/read', content=b' ' * 4097,
                headers={'Content-Type': 'application/json'})
        self.assertEqual(413, response.status_code)
        app = FastAPI()
        async def large(body: dict):
            return {'authoredPadding': 'x' * 4097}
        app.router.add_api_route('/sources/read', large, methods=['POST'], route_class_override=_SourceReadRoute)
        app.add_middleware(_WireLimits, maximum=4096, strict_json_paths=('/sources/read',))
        with TestClient(app) as client:
            response = client.post('/sources/read', json={})
        self.assertEqual(422, response.status_code)
