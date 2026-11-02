"""CPU-stub terminal contract through a real Unix socket and synthetic task tree.

The test owns its temporary processes and socket; the cgroup boundary is mocked.
It never starts a container, model, driver, or GPU task.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import fixed_gpu_client as client_module
import fixed_gpu_worker as worker_module
from request_stage import encoded, envelope

TOKEN = 'fixture-token'
PROFILE = {'adapter': 'consense.native-chat.v1', 'providerKind': 'cpu-protocol-stub', 'model': 'fixture',
           'command': ['/usr/bin/python3', '/runtime/tools/offline_delivery/qwen_protocol_stub.py']}
PROFILE_SHA = hashlib.sha256(encoded(PROFILE)).hexdigest()
CPU_RELEASE = {'status': 'released_cpu_only', 'cgroupPopulated': 0}


@unittest.skipUnless(os.name == 'posix', 'Unix socket integration requires POSIX')
class CpuStubUnixIntegration(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.driver = self.root / 'fixture-driver'
        self.driver.write_text('not a GPU executable')
        self.profile = {'mode': 'job', 'profile': PROFILE, 'gpuRequired': True, 'timeoutSeconds': 10,
                        'boot': {'command': ['/usr/bin/python3', '/runtime/tools/offline_delivery/chat_operation_worker.py'],
                                 'env': {}}}
        self.worker = worker_module.Worker({'workspace': str(self.root), 'stages': {'business-llm': self.profile},
            'containerId': 'fixture', 'nvidiaSmi': str(self.driver), 'gpuUuid': 'GPU-fixture',
            'peerUid': os.geteuid(), 'peerGid': os.getegid()}, TOKEN)
        self.boot = {'cgroupRoot': str(self.root), 'env': {}, 'runtimeAssets': [
            {'path': str(self.driver), 'sha256': 'd' * 64}]}
        self.hold = False
        self.child_processes = []
        self.residue = False
        owner = self
        class Tree:
            def __init__(self, boot, stdout, stderr, timeout):
                self.group = owner.root
                self.closed = False
                request = boot['env']['CONSENSE_OPERATION_REQUEST']
                result = boot['env']['CONSENSE_OPERATION_RESULT']
                program = ("import base64,json,pathlib,sys,time\n"
                    "req=json.load(open(sys.argv[1])); streaming=json.loads(base64.b64decode(req['bodyBase64'])).get('stream') is True\n"
                    "content_type='text/event-stream' if streaming else 'application/json'\n"
                    "body=b'data: {\"choices\":[{\"delta\":{\"content\":\"fixture\"},\"finish_reason\":null}]}\\n\\n' if streaming else b'fixture CPU response'\n"
                    "if streaming:\n"
                    " d=pathlib.Path(sys.argv[2]).parent\n"
                    " (d/'stream.headers.json').write_text(json.dumps({'bindingSha256':req['bindingSha256'],'status':200,'headers':{'content-type':content_type}}))\n"
                    " (d/'stream.bin').write_bytes(body)\n"
                    " time.sleep(.25 if float(sys.argv[3]) == 0 else float(sys.argv[3]))\n"
                    " body+=b'data: [DONE]\\n\\n'; (d/'stream.bin').write_bytes(body)\n"
                    "output={'contract':'consense.gpu-operation-response.v1','bindingSha256':req['bindingSha256'],"
                    "'status':200,'headers':{'content-type':content_type},'bodyBase64':base64.b64encode(body).decode()}\n"
                    "open(sys.argv[2],'w').write(json.dumps(output,sort_keys=True,separators=(',',':')))\n"
                    "if not streaming: time.sleep(float(sys.argv[3]))\n")
                self.process = subprocess.Popen([sys.executable, '-c', program, request, result,
                    '30' if owner.hold else '0'], stdout=stdout, stderr=stderr)
                self.pid = self.process.pid
                owner.child_processes.append(self.process)
            def active(self): return int(self.process.poll() is None)
            def stop(self):
                if self.process.poll() is None:
                    self.process.terminate()
                self.process.wait(timeout=5)
            def close(self): self.closed = True
        self.patches = [
            patch.object(self.worker, '_validate_profile', return_value=(self.boot, 10)),
            patch.object(self.worker, '_validate_cpu_stub_code'),
            patch.object(self.worker, '_delegated_root', return_value=self.root),
            patch.object(self.worker, '_cgroup_pids', side_effect=lambda root: {os.getpid(), 55} if self.residue else {os.getpid()}),
            patch.object(worker_module, '_LinuxTree', Tree),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)
        self.sock = self.root / 'gpu.sock'
        self.server = worker_module.make_server(self.worker, uds_path=str(self.sock))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        for child in self.child_processes:
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=5)

    def client(self, trusted=True):
        kwargs = {'cpu_business_profile': PROFILE} if trusted else {}
        return client_module.Client('unix://' + str(self.sock), TOKEN, {'business-llm': PROFILE_SHA}, **kwargs)

    def test_cpu_success_has_distinct_persisted_release_and_releases_owner(self):
        status, _, body, receipt = self.client().call_business('/v1/chat/completions', b'{}', 5)
        self.assertEqual((status, body), (200, b'fixture CPU response'))
        self.assertEqual(receipt['release'], CPU_RELEASE)
        self.assertFalse(client_module.gpu_release_proven(receipt['release']))
        self.assertFalse(self.worker.marker.exists())
        self.assertTrue(self.child_processes and all(p.poll() is not None for p in self.child_processes))
        persisted = json.loads((self.worker.operations / receipt['operationId'] / 'status.json').read_bytes())
        self.assertEqual((persisted['status'], persisted['release']), ('completed', CPU_RELEASE))

    def test_cancel_waits_for_owned_cpu_child_then_clears_owner(self):
        self.hold = True
        client = self.client()
        stream = client.start_business('/v1/chat/completions', b'{"stream":true}', 5)
        for _ in range(100):
            state = client._call('GET', '/operations/' + stream.operation, 1)
            if state['status'] == 'running': break
            time.sleep(.02)
        else: self.fail('synthetic child did not start')
        self.assertTrue(self.worker.marker.exists())
        release = client.cancel_and_observe(stream.operation, stream.expected)
        self.assertEqual(release['status'], 'released_cpu_only')
        self.assertEqual(release['operationStatus'], 'cancelled')
        self.assertFalse(self.worker.marker.exists())
        self.assertTrue(self.child_processes and all(p.poll() is not None for p in self.child_processes))

    def test_stream_terminal_waits_for_cpu_release_and_persisted_result(self):
        client = self.client()
        stream = client.start_business('/v1/chat/completions', b'{"stream":true}', 5)
        self.assertEqual(200, stream.open()[0])
        chunks = list(stream.read())
        self.assertIn(b'fixture', b''.join(chunks))
        self.assertIn(b'[DONE]', b''.join(chunks))
        self.assertEqual(stream.exit_receipt['status'], 'released_cpu_only')
        self.assertEqual(stream.exit_receipt['operationStatus'], 'completed')
        self.assertFalse(self.worker.marker.exists())

    def test_same_named_untrusted_program_cannot_claim_cpu_stub(self):
        for target in ('boot', 'stub'):
            with self.subTest(target=target):
                stage = json.loads(json.dumps(self.profile))
                if target == 'boot':
                    stage['boot']['command'][1] = '/untrusted/chat_operation_worker.py'
                else:
                    stage['profile']['command'][1] = '/untrusted/qwen_protocol_stub.py'
                self.worker.config['stages']['business-llm'] = stage
                digest = hashlib.sha256(encoded(stage['profile'])).hexdigest()
                request = {'stage': 'business-llm', 'binding': {'httpPath': '/v1/chat/completions'},
                           'bodyBase64': base64.b64encode(b'{}').decode(),
                           'expectedProfileSha256': digest, 'expectedMode': 'job'}
                with self.assertRaises(ValueError):
                    self.worker.start(request)
                self.assertFalse(self.worker.marker.exists())
        self.worker.config['stages']['business-llm'] = self.profile

    def test_cpu_result_rejected_by_legacy_client_without_trusted_profile(self):
        with self.assertRaises(client_module.OperationError):
            self.client(trusted=False).call_business('/v1/chat/completions', b'{}', 5)

    def test_residue_keeps_unknown_and_blocks_next_owner(self):
        self.residue = True
        client = self.client()
        with self.assertRaises(client_module.OperationError):
            client.call_business('/v1/chat/completions', b'{}', 5)
        self.assertTrue(self.worker.marker.exists())
        states = list(self.worker.operations.glob('*/status.json'))
        self.assertEqual(len(states), 1)
        self.assertEqual(json.loads(states[0].read_bytes())['status'], 'release_unknown')
        with self.assertRaises(client_module.OperationError):
            client.call_business('/v1/chat/completions', b'{}', 2)


class CpuStubCodeIdentity(unittest.TestCase):
    def test_only_marked_and_pinned_scripts_are_accepted(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            paths = {name: root / name for name in ('python3', 'chat_operation_worker.py',
                                                      'qwen_protocol_stub.py', 'nvidia-smi')}
            paths['python3'].write_bytes(b'fixture interpreter')
            paths['chat_operation_worker.py'].write_bytes(b'\"\"\"fixture\"\"\"\n# consense-cpu-stub-process-only-v1\n')
            paths['qwen_protocol_stub.py'].write_bytes(b'fixture CPU stub')
            paths['nvidia-smi'].write_bytes(b'fixture driver inspector')
            def asset(path):
                raw = path.read_bytes()
                return {'path': str(path), 'sha256': hashlib.sha256(raw).hexdigest(), 'byteCount': len(raw)}
            assets = [asset(path) for path in paths.values()]
            inner = {'adapter': 'consense.native-chat.v1', 'providerKind': 'cpu-protocol-stub',
                     'stubTokenFile': str(root / 'token'), 'stubTokenSha256': 'a' * 64,
                     'environment': {'CUDA_VISIBLE_DEVICES': ''},
                     'command': [str(paths['python3']), str(paths['qwen_protocol_stub.py'])]}
            profile = {'mode': 'job', 'profile': inner, 'gpuRequired': True, 'timeoutSeconds': 5,
                       'boot': {'command': [str(paths['python3']), str(paths['chat_operation_worker.py'])],
                                'cwd': str(root), 'cgroupRoot': str(root), 'env': {}, 'runtimeAssets': assets}}
            worker = worker_module.Worker({'workspace': str(root), 'stages': {}, 'cgroupRoot': str(root),
                                           'nvidiaSmi': str(paths['nvidia-smi']), 'gpuUuid': 'GPU-fixture'}, TOKEN)
            with patch.object(worker_module, 'CPU_STUB_CHAT', str(paths['chat_operation_worker.py'])), \
                 patch.object(worker_module, 'CPU_STUB_SERVER', str(paths['qwen_protocol_stub.py'])), \
                 patch('chat_operation_worker.stub_token', return_value='fixture-token-at-least-16'):
                worker._validate_profile(profile)
                worker._validate_cpu_stub_code(profile)
                fixture = root / 'ordinary.json'
                fixture.write_text('{"nodes":[]}')
                for option in ('--fixture-file', '--ordinary-fixture-file'):
                    inner['command'].extend([option, str(fixture)])
                    with self.assertRaises(ValueError): worker._validate_profile(profile)
                    assets.append(asset(fixture))
                    worker._validate_profile(profile)
                    inner['command'].extend([option, str(fixture)])
                    with self.assertRaises(ValueError): worker._validate_profile(profile)
                    del inner['command'][-4:]
                    inner['command'].append(option + '=' + str(fixture))
                    with self.assertRaises(ValueError): worker._validate_profile(profile)
                    inner['command'].pop()
                    assets.pop()
                paths['qwen_protocol_stub.py'].write_bytes(b'tampered CPU stub')
                with self.assertRaises(ValueError): worker._validate_profile(profile)
                paths['qwen_protocol_stub.py'].write_bytes(b'fixture CPU stub')
                paths['chat_operation_worker.py'].write_bytes(b'old worker without the CPU-only marker')
                assets[1] = asset(paths['chat_operation_worker.py'])
                worker._validate_profile(profile)
                with self.assertRaises(ValueError): worker._validate_cpu_stub_code(profile)
                paths['chat_operation_worker.py'].write_bytes(b'\"\"\"fixture\"\"\"\n# consense-cpu-stub-process-only-v1\n')
                with self.assertRaises(ValueError): worker._validate_cpu_stub_code(profile)


class CpuReceiptBoundary(unittest.TestCase):
    def test_cpu_receipt_is_not_a_gpu_release(self):
        self.assertFalse(client_module.gpu_release_proven(CPU_RELEASE))
        self.assertFalse(client_module.gpu_release_proven({'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': []}))
        self.assertTrue(client_module.gpu_release_proven({'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': [123]}))

    def test_client_cpu_trust_is_pinned_and_business_only(self):
        profiles = {'business-llm': PROFILE_SHA, 'document-embedding': 'a' * 64}
        client = client_module.Client('http://127.0.0.1:12345', TOKEN, profiles,
                                      cpu_business_profile=PROFILE)
        self.assertTrue(client._release_proven('business-llm', PROFILE_SHA, CPU_RELEASE))
        self.assertFalse(client._release_proven('business-llm', 'b' * 64, CPU_RELEASE))
        self.assertFalse(client._release_proven('document-embedding', PROFILE_SHA, CPU_RELEASE))
        self.assertFalse(client._release_proven('business-llm', PROFILE_SHA,
                         {'status': 'released_cpu_only', 'cgroupPopulated': False}))
        self.assertFalse(client._release_proven('business-llm', PROFILE_SHA,
                         {**CPU_RELEASE, 'observedGpuPids': [123]}))
        with self.assertRaises(ValueError):
            client_module.Client('http://127.0.0.1:12345', TOKEN, profiles,
                                 cpu_business_profile={**PROFILE, 'model': 'other'})

    def test_source_verifies_cpu_receipt_only_for_pinned_stub(self):
        import test_business_stub_integration as source_tests
        fixture = source_tests.BusinessConfigurationTest('test_independent_readback_requires_exact_status_response_and_release')
        fixture.setUp()
        fixture.release = dict(CPU_RELEASE)
        fixture.state['release'] = fixture.release
        config = fixture.configure()
        self.assertTrue(config['verifyRelease'](fixture.ordinary(), fixture.claims()))
        streaming = {**fixture.release, 'operationStatus': 'completed', 'operationId': fixture.operation}
        self.assertTrue(config['verifyRelease'](streaming, fixture.claims()))
        fixture.state['profileSha256'] = 'e' * 64
        self.assertFalse(config['verifyRelease'](fixture.ordinary(), fixture.claims()))
        fixture.state['profileSha256'] = 'a' * 64
        fixture.profile['providerKind'] = None
        config = fixture.configure()
        self.assertFalse(config['verifyRelease'](fixture.ordinary(), fixture.claims()))

    def test_persisted_cpu_receipt_cannot_be_used_by_other_stages_or_profiles(self):
        bad_profiles = [
            ('business-llm', {'adapter': 'consense.native-chat.v1', 'model': 'real'}),
            ('document-embedding', {'adapter': 'consense.model-session.v1', 'model': 'fixture'}),
            ('paddle', {'adapter': 'consense.native-session.v1', 'model': 'fixture'}),
        ]
        for stage, profile in bad_profiles:
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as root:
                worker = worker_module.Worker({'workspace': root, 'stages': {
                    stage: {'mode': 'job', 'profile': profile}}}, TOKEN)
                op = envelope(stage, b'{}', {'httpPath': '/v1/chat/completions'}, profile)
                directory = worker.operations / op['operationId']
                directory.mkdir()
                response = {'contract': 'consense.gpu-operation-response.v1', 'bindingSha256': op['bindingSha256'],
                            'status': 200, 'headers': {}, 'bodyBase64': base64.b64encode(b'fixture').decode()}
                raw = encoded(response)
                (directory / 'request.json').write_bytes(encoded(op))
                (directory / 'response.json').write_bytes(raw)
                (directory / 'status.json').write_bytes(encoded({'operationId': op['operationId'], 'stage': stage,
                    'status': 'completed', 'bindingSha256': op['bindingSha256'],
                    'responseSha256': hashlib.sha256(raw).hexdigest(), 'release': CPU_RELEASE}))
                with self.assertRaises(ValueError):
                    worker.result(op['operationId'])


if __name__ == '__main__': unittest.main()
