"""Software-only candidate checks; fake CUDA never counts as GPU release evidence."""
import ast
import base64
import hashlib
import hmac
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
TOOLS = REPO / 'ai_consense_service' / 'tools'
sys.path[:0] = [str(TOOLS / 'offline_delivery'), str(TOOLS / 'vetting_eval')]
import request_stage

spec = importlib.util.spec_from_file_location('candidate_chat_operation_worker', HERE / 'chat_operation_worker.py')
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)
business_spec = importlib.util.spec_from_file_location('candidate_business_configuration', HERE / 'business_configuration.py')
business = importlib.util.module_from_spec(business_spec)
business_spec.loader.exec_module(business)
entry_spec = importlib.util.spec_from_file_location('candidate_source_service_entrypoint', HERE / 'source_service_entrypoint.py')
entrypoint = importlib.util.module_from_spec(entry_spec)
entry_spec.loader.exec_module(entrypoint)


GPU = 'GPU-0395511c-46f7-c8d6-6599-8e4208eda5fe'


class StubWireLifecycleTest(unittest.TestCase):
    def exercise(self, failure=None):
        with tempfile.TemporaryDirectory(dir=HERE) as temporary:
            workspace = Path(temporary)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
                port = sock.getsockname()[1]
            profile = {
                'adapter': 'consense.native-chat.v1', 'providerKind': 'cpu-protocol-stub',
                'model': 'qwen3.8-27b', 'baseUrl': f'http://127.0.0.1:{port}',
                'readyPath': '/v1/models', 'apiPaths': {'/v1/chat/completions': '/v1/chat/completions'},
                'command': [sys.executable, str(TOOLS / 'offline_delivery' / 'qwen_protocol_stub.py'),
                            '--port', str(port)], 'cwd': str(workspace),
                'environment': {'CUDA_VISIBLE_DEVICES': '', 'HF_HUB_OFFLINE': '1',
                                'TRANSFORMERS_OFFLINE': '1'},
                'stubTokenFile': str(workspace / 'fixture-token'),
                'stubTokenSha256': hashlib.sha256(b'fixture-stub-token').hexdigest(),
                'timeoutSeconds': .15 if failure == 'timeout' else 5,
            }
            raw = json.dumps({'model': profile['model'], 'messages': [{'role': 'user', 'content': 'fixture'}],
                              'max_tokens': 2, 'enable_thinking': False, 'stream': False}).encode()
            operation = request_stage.envelope('business-llm', raw,
                                               {'httpPath': '/v1/chat/completions'}, profile)
            directory = workspace / 'gpu-operations' / operation['operationId']
            directory.mkdir(parents=True)
            request, result = directory / 'request.json', directory / 'response.json'
            request_stage.save(request, operation)
            env = {'CONSENSE_FIXED_WORKSPACE': str(workspace),
                   'CONSENSE_FIXED_CONTAINER_ID': 'fixture-container',
                   'CONSENSE_FIXED_OPERATION_ID': operation['operationId'],
                   'CONSENSE_FIXED_GPU_UUID': GPU,
                   'CONSENSE_OPERATION_REQUEST': str(request),
                   'CONSENSE_OPERATION_REQUEST_SHA256': request_stage.sha(request),
                   'CONSENSE_OPERATION_RESULT': str(result),
                   'CONSENSE_OPERATION_STREAM': str(directory / 'stream.bin')}
            events = []

            class Context:
                def __enter__(self):
                    events.append('context-enter')
                    return self

                def __exit__(self, *_):
                    events.append('context-exit')

            real_popen = subprocess.Popen

            class Process:
                def __init__(self, *args, **kwargs):
                    # Windows needs its system directory to initialize Winsock in this fixture.
                    kwargs['env'] = {**kwargs['env'], **({'SystemRoot': os.environ['SystemRoot']}
                                                     if 'SystemRoot' in os.environ else {})}
                    self.child = real_popen(*args, **kwargs)
                    events.append('provider-launch')

                def poll(self):
                    return self.child.poll()

                def terminate(self):
                    return self.child.terminate()

                def wait(self, **kwargs):
                    try:
                        return self.child.wait(**kwargs)
                    finally:
                        events.append('provider-wait')

            with patch.dict(os.environ, env), \
                    patch.object(candidate, 'nullcontext', Context), \
                    patch.object(candidate, 'stub_token', return_value='fixture-stub-token'), \
                    patch.object(candidate, 'verify_fixed_connection'), \
                    patch.object(candidate.subprocess, 'Popen', Process):
                if failure == 'timeout':
                    with patch.object(candidate, 'fixed_ready', return_value=False):
                        with self.assertRaises(TimeoutError):
                            candidate.execute(str(request), str(result))
                elif failure == 'interrupt':
                    with patch.object(candidate, 'fixed_ready', side_effect=KeyboardInterrupt):
                        with self.assertRaises(KeyboardInterrupt):
                            candidate.execute(str(request), str(result))
                else:
                    candidate.execute(str(request), str(result))
            return events, json.loads(result.read_bytes()) if result.exists() else None

    def test_real_protocol_stub_child_is_reaped(self):
        events, response = self.exercise()
        self.assertLess(events.index('context-enter'), events.index('provider-launch'))
        self.assertLess(events.index('provider-wait'), events.index('context-exit'))
        body = json.loads(base64.b64decode(response['bodyBase64']))
        self.assertTrue(body['consense_protocol_stub'])
        self.assertTrue(body['synthetic_token_counts'])

    def test_readiness_timeout_reaps_provider_before_context_close(self):
        events, response = self.exercise('timeout')
        self.assertIsNone(response)
        self.assertLess(events.index('provider-wait'), events.index('context-exit'))

    def test_interrupt_reaps_provider_before_context_close(self):
        events, response = self.exercise('interrupt')
        self.assertIsNone(response)
        self.assertLess(events.index('provider-wait'), events.index('context-exit'))


class BusinessConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.profiles = {'business-llm': 'a' * 64}
        self.env = {'CONSENSE_BUSINESS_MODEL': 'qwen3.8-27b',
                    'CONSENSE_BUSINESS_TOKEN_FILE': '/private/business-token',
                    'CONSENSE_BUSINESS_PROFILE_FILE': '/config/business-profile.json',
                    'CONSENSE_BUSINESS_TIMEOUT_SECONDS': '12',
                    'CONSENSE_FIXED_GPU_CONFIG_SHA256': 'b' * 64}
        self.profile = {'adapter': 'consense.native-chat.v1', 'providerKind': 'cpu-protocol-stub',
                        'model': 'qwen3.8-27b',
                        'apiPaths': {'/v1/chat/completions': '/v1/chat/completions'}}
        self.body = b'{"model":"qwen3.8-27b","stream":false}'
        self.operation = 'c' * 32
        self.path = '/v1/chat/completions'
        self.release = {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': [123]}
        self.response = {'contract': 'consense.gpu-operation-response.v1', 'bindingSha256': 'd' * 64,
                         'status': 200, 'headers': {'content-type': 'application/json'},
                         'bodyBase64': base64.b64encode(b'{"consense_protocol_stub":true}').decode()}
        self.state = {'operationId': self.operation, 'stage': 'business-llm', 'status': 'completed',
                      'configSha256': 'b' * 64, 'bodySha256': hashlib.sha256(self.body).hexdigest(),
                      'sourceSha256': hashlib.sha256(request_stage.encoded({'httpPath': self.path})).hexdigest(),
                      'profileSha256': 'a' * 64, 'bindingSha256': 'd' * 64,
                      'responseSha256': hashlib.sha256(request_stage.encoded(self.response)).hexdigest(),
                      'release': self.release}
        self.calls = []

    def configure(self):
        def make_client(*_args, **kwargs):
            parent = self
            from fixed_gpu_client import Client
            client = Client('http://127.0.0.1:1234', 'fixture', self.profiles)
            if kwargs.get('cpu_business_profile') is not None:
                self.assertEqual(self.profile, kwargs['cpu_business_profile'])
                client.cpu_business_profile_sha256 = self.profiles['business-llm']
            def call(method, path, _timeout):
                parent.calls.append((method, path))
                return parent.response if path.endswith('/result') else parent.state
            client._call = call
            return client

        with patch.object(business, '_private_token', return_value='separate-business-token'), \
                patch.object(business, '_profile_file', return_value=self.profile), \
                patch.object(business, 'Client', side_effect=make_client):
            return business.configure(self.env, 'unix:///private/gpu.sock', 'separate-gpu-token', self.profiles)

    def claims(self):
        return {'path': self.path, 'bodySha256': hashlib.sha256(self.body).hexdigest(),
                'profileSha256': 'a' * 64, 'executionSha256': 'b' * 64,
                'model': 'qwen3.8-27b', 'operationId': self.operation}

    def ordinary(self):
        return {'operationId': self.operation, 'bindingSha256': 'd' * 64,
                'responseSha256': self.state['responseSha256'], 'release': self.release}

    def test_default_closed_and_partial_config_rejected(self):
        self.assertIsNone(business.configure({}, 'unix:///private/gpu.sock', 'gpu-token', self.profiles))
        with self.assertRaisesRegex(ValueError, 'complete pinned'):
            business.configure({'CONSENSE_BUSINESS_MODEL': 'qwen3.8-27b'},
                               'unix:///private/gpu.sock', 'gpu-token', self.profiles)

    def test_profile_allowed_paths_are_bound_to_canonical_profile_sha(self):
        raw = request_stage.encoded(self.profile)
        digest = hashlib.sha256(raw).hexdigest()
        self.assertEqual(self.profile, business._profile_from_bytes(raw, digest))
        with self.assertRaisesRegex(ValueError, 'identity'):
            business._profile_from_bytes(raw, '0' * 64)
        expanded_stub = {**self.profile, 'apiPaths': {
            '/v1/chat/completions': '/v1/chat/completions', '/v1/responses': '/v1/responses'}}
        expanded = request_stage.encoded(expanded_stub)
        with self.assertRaisesRegex(ValueError, 'API paths'):
            business._profile_from_bytes(expanded, hashlib.sha256(expanded).hexdigest())
        real_profile = {**expanded_stub, 'providerKind': None}
        real = request_stage.encoded(real_profile)
        self.assertEqual(real_profile, business._profile_from_bytes(real, hashlib.sha256(real).hexdigest()))

    def test_source_model_mismatch_fails_before_client_creation(self):
        self.profile['model'] = 'wrong-model'
        with self.assertRaisesRegex(ValueError, 'business model'):
            self.configure()

    def test_nonstub_profile_preserves_its_pinned_responses_route(self):
        self.profile['providerKind'] = None
        self.profile['apiPaths']['/v1/responses'] = '/v1/responses'
        config = self.configure()
        self.assertIn('/v1/responses', config['allowedPaths'])
        self.state['sourceSha256'] = hashlib.sha256(request_stage.encoded({'httpPath': '/v1/responses'})).hexdigest()
        self.assertTrue(config['verifyRelease'](self.ordinary(), {**self.claims(), 'path': '/v1/responses'}))

    def test_independent_readback_requires_exact_status_response_and_release(self):
        config = self.configure()
        self.assertTrue(config['verifyRelease'](self.ordinary(), self.claims()))
        self.assertEqual([('GET', '/operations/' + self.operation),
                          ('GET', '/operations/' + self.operation + '/result')], self.calls)
        streaming = {**self.release, 'operationStatus': 'completed', 'operationId': self.operation}
        self.assertTrue(config['verifyRelease'](streaming, self.claims()))
        self.state['configSha256'] = 'e' * 64
        self.assertFalse(config['verifyRelease'](self.ordinary(), self.claims()))
        self.state['configSha256'] = 'b' * 64
        self.state['release'] = {'status': 'release_unknown'}
        self.assertFalse(config['verifyRelease'](self.ordinary(), self.claims()))

    def test_forged_receipt_or_claims_fail_before_acceptance(self):
        config = self.configure()
        forged = {**self.ordinary(), 'responseSha256': 'f' * 64}
        self.assertFalse(config['verifyRelease'](forged, self.claims()))
        claims = {**self.claims(), 'bodySha256': None}
        self.assertFalse(config['verifyRelease'](self.ordinary(), claims))
        self.assertFalse(config['verifyRelease'](self.ordinary(), {**self.claims(), 'path': '/other'}))
        self.assertFalse(config['verifyRelease'](self.ordinary(), {**self.claims(), 'path': '/v1/responses'}))


class SourceBusinessAdmissionTest(unittest.TestCase):
    def test_local_stub_rejects_unsupported_routes_before_gpu_dispatch(self):
        class HTTPError(Exception):
            def __init__(self, status_code, detail):
                self.status_code, self.detail = status_code, detail

        class Client:
            profile_sha256s = {'business-llm': 'a' * 64}

            def __init__(self):
                self.dispatched = False

            def start_business(self, *_args):
                self.dispatched = True

        server_path = HERE / 'server.py' if (HERE / 'server.py').is_file() else TOOLS / 'vetting_eval/server.py'
        node = next(item for item in ast.parse(server_path.read_text(encoding='utf-8')).body
                    if isinstance(item, ast.FunctionDef) and item.name == '_fixed_business_configuration')
        namespace = {'HTTPException': HTTPError, 'hmac': hmac, 're': re, 'math': math,
                     '_business_snapshot_matches': lambda *_args: True}
        exec(compile(ast.Module(body=[node], type_ignores=[]), 'candidate-server-admission', 'exec'), namespace)
        client = Client()
        config = {'model': 'qwen3.8-27b', 'token': 'separate-business-token',
                  'allowedPaths': frozenset({'/v1/models', '/v1/chat/completions'}),
                  'profileSha256': 'a' * 64,
                  'executionSha256': 'b' * 64, 'timeout': 5,
                  'verifyRelease': lambda *_args: True}
        namespace.update(_remote_models=SimpleNamespace(client=client),
                         _trusted_business_configuration=lambda: config)

        def request(path, token='separate-business-token'):
            return SimpleNamespace(headers={'authorization': 'Bearer ' + token}, scope={},
                                   url=SimpleNamespace(path=path))

        for path in ('/v1/responses', '/v1/responses/input_tokens'):
            with self.assertRaises(HTTPError) as failure:
                namespace['_fixed_business_configuration'](request(path))
            self.assertEqual(404, failure.exception.status_code)
        self.assertFalse(client.dispatched)
        self.assertIs(client, namespace['_fixed_business_configuration'](
            request('/v1/chat/completions'))[1])
        with self.assertRaises(HTTPError) as unauthenticated:
            namespace['_fixed_business_configuration'](request('/v1/responses', 'wrong'))
        self.assertEqual(401, unauthenticated.exception.status_code)
        config.pop('allowedPaths')
        self.assertIs(client, namespace['_fixed_business_configuration'](request('/v1/responses'))[1])


class SourceEntryPointTest(unittest.TestCase):
    def test_business_route_opt_in_only_on_vetting_source(self):
        commands = []

        class Child:
            def poll(self):
                return 1

            def wait(self, **_kwargs):
                return 1

        def start(command, **_kwargs):
            commands.append(command)
            return Child()

        with patch.dict(os.environ, {'CONSENSE_BUSINESS_MODEL': 'qwen3.8-27b'}), \
                patch.object(entrypoint, 'configuration', return_value=(TOOLS, [HERE, HERE], [28681, 28685])), \
                patch.object(entrypoint, 'qdrant_credential', return_value=None), \
                patch.object(entrypoint.subprocess, 'Popen', side_effect=start), \
                patch.object(entrypoint.signal, 'signal'):
            self.assertEqual(1, entrypoint.main())
        self.assertEqual(2, len(commands))
        self.assertIn('--with-business', commands[0])
        self.assertIn('--with-ocr', commands[0])
        self.assertNotIn('--with-business', commands[1])


if __name__ == '__main__':
    unittest.main()
