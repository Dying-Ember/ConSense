import hashlib
import itertools
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import fixed_gpu_worker as worker_module
import gpu_observation
from fixed_gpu_client import gpu_release_proven
from model_session_receipt import valid_release_claim
from request_stage import encoded, envelope


class OwnedTreeReleaseTest(unittest.TestCase):
    def test_native_model_fallback_requires_explicit_read_only_driver_mount(self):
        env = {key:'fixture' for key in worker_module.OWNED_MODEL_ENV}
        boot = {'command':['/runtime/envs/retrieval/bin/python3.12.0',
                           '/runtime/tools/offline_delivery/model_session_worker.py'], 'env':env}
        env['LD_LIBRARY_PATH'] = '/usr/lib/wsl/lib'
        self.assertTrue(worker_module.owned_model_boot(boot))
        env['LD_LIBRARY_PATH'] = '/runtime/gpu-driver/lib'
        with patch.object(worker_module, '_read_only_driver_mount', return_value=True):
            self.assertTrue(worker_module.owned_model_boot(boot))
            env['LD_PRELOAD'] = '/untrusted.so'
            self.assertFalse(worker_module.owned_model_boot(boot))
            del env['LD_PRELOAD']
        with patch.object(worker_module, '_read_only_driver_mount', return_value=False):
            self.assertFalse(worker_module.owned_model_boot(boot))
        env['LD_LIBRARY_PATH'] = '/untrusted/lib'
        self.assertFalse(worker_module.owned_model_boot(boot))

    @unittest.skipUnless(hasattr(os, 'statvfs'), 'Linux mount flags')
    def test_driver_path_must_be_a_distinct_read_only_mount(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'driver'; root.mkdir()
            link = Path(directory) / 'link'; link.symlink_to(root, target_is_directory=True)
            record = [f'31 20 0:22 / {root} ro,nosuid - tmpfs tmpfs rw\n']
            read_text = Path.read_text
            def read(path, *args, **kwargs):
                return record[0] if str(path) == '/proc/self/mountinfo' else read_text(path, *args, **kwargs)
            flags = [os.ST_RDONLY]
            with patch.object(Path, 'read_text', read), \
                 patch.object(worker_module.os, 'statvfs', side_effect=lambda _: SimpleNamespace(f_flag=flags[0])):
                self.assertTrue(worker_module._read_only_driver_mount(root))
                self.assertFalse(worker_module._read_only_driver_mount(link))
                flags[0] = 0
                self.assertFalse(worker_module._read_only_driver_mount(root))
                flags[0] = os.ST_RDONLY
                record[0] = f'31 20 0:22 / {root} rw,nosuid - tmpfs tmpfs rw\n'
                self.assertFalse(worker_module._read_only_driver_mount(root))
                record[0] = (f'31 20 0:22 / {root} ro,nosuid - tmpfs tmpfs rw\n'
                             f'32 31 0:23 / {root}/nested rw,nosuid - tmpfs tmpfs rw\n')
                self.assertFalse(worker_module._read_only_driver_mount(root))
                record[0] = record[0].replace('/nested rw,nosuid', '/nested ro,nosuid')
                self.assertTrue(worker_module._read_only_driver_mount(root))
                (root / 'libcuda-real.so').write_bytes(b'fixture')
                inside = root / 'libcuda.so.1'; inside.symlink_to('libcuda-real.so')
                self.assertTrue(worker_module._read_only_driver_mount(root))
                outside = Path(directory) / 'outside.so'; outside.write_bytes(b'fixture')
                inside.unlink(); inside.symlink_to(outside)
                self.assertFalse(worker_module._read_only_driver_mount(root))
                inside.unlink(); inside.symlink_to('missing.so')
                self.assertFalse(worker_module._read_only_driver_mount(root))
                record[0] = '31 20 0:22 / /runtime ro,nosuid - tmpfs tmpfs rw\n'
                self.assertFalse(worker_module._read_only_driver_mount(root))

    def test_driver_conflicts_and_multiple_devices_are_not_tree_release(self):
        tree = SimpleNamespace(active=lambda:0, process=SimpleNamespace(poll=lambda:0))
        sample = {'devicesRaw':'GPU-one\n', 'processesMiB':{}}
        with patch.object(gpu_observation, '_group_identity', return_value={'inode':1}), \
             patch.object(gpu_observation, '_executable_identity', return_value={'sha256':'a'*64}), \
             patch.object(gpu_observation, '_driver_sample', return_value=sample):
            driver = {'sha256':'a'*64}
            self.assertIsNone(gpu_observation.observe_owned_tree_release(tree, '/driver', 'a'*64, 'GPU-one', driver))
            for devices, processes in (('GPU-one\nGPU-two\n', {}), ('GPU-one\n', {123:None})):
                sample.update(devicesRaw=devices, processesMiB=processes)
                with self.assertRaises(ValueError):
                    gpu_observation.observe_owned_tree_release(tree, '/driver', 'a'*64, 'GPU-one', driver)

    def test_release_claims_keep_explicit_evidence_and_reject_forged_shapes(self):
        valid = {'status':'released', 'cgroupPopulated':0,
                 'ownership':'delegated_cgroup_v2', 'processWaited':True}
        self.assertFalse(gpu_release_proven(valid))
        self.assertTrue(gpu_release_proven(valid, owned_tree=True))
        self.assertTrue(valid_release_claim(valid))
        for invalid in ({**valid, 'processWaited':False}, {**valid, 'cgroupPopulated':False},
                        {**valid, 'ownership':'other'}, {**valid, 'extra':1},
                        {'status':'released', 'cgroupPopulated':0, 'observedGpuPids':[]}):
            self.assertFalse(gpu_release_proven(invalid, owned_tree=True))
            self.assertFalse(valid_release_claim(invalid))

    @unittest.skipUnless(os.name == 'posix', 'Linux session supervision')
    def test_model_session_timeout_crash_and_uncertain_cleanup(self):
        for scenario, driver_path in itertools.product(
                ('completed', 'expired', 'cancelled', 'crashed', 'residual', 'driver_error', 'mps', 'incomplete'),
                ('/usr/lib/wsl/lib', '/runtime/gpu-driver/lib')):
            with self.subTest(scenario=scenario, driver_path=driver_path), TemporaryDirectory() as root:
                driver = Path(root) / 'nvidia-smi'; driver.write_bytes(b'fixture')
                profile = {'adapter':'consense.model-session.v1'}
                worker = worker_module.Worker({'workspace':root, 'stages':{'document-embedding':
                    {'mode':'model-session', 'profile':profile}}, 'containerId':'fixture',
                    'nvidiaSmi':str(driver), 'gpuUuid':'GPU-fixture'}, 'example-secret')
                operation = envelope('document-embedding', b'{}', {'source':'fixture'}, profile)
                directory = worker.operations / operation['operationId']; directory.mkdir()
                (directory / 'request.json').write_bytes(encoded(operation))
                (directory / 'status.json').write_bytes(encoded({'operationId':operation['operationId'],
                    'stage':operation['stage'], 'status':'starting', 'bindingSha256':operation['bindingSha256'],
                    'configSha256':worker.config_sha}))
                (directory / 'model-session').mkdir()
                (directory / 'model-session' / 'finished.json').write_bytes(b'{}')
                worker.marker.write_bytes(encoded({'operationId':operation['operationId'], 'state':'occupied_or_release_unknown'}))
                cancellation, finished = threading.Event(), threading.Event()
                worker.active[operation['operationId']] = cancellation, finished
                worker.sessions[operation['operationId']] = {'deadline':time.monotonic() + (-1 if scenario == 'expired' else 5)}
                if scenario == 'cancelled': cancellation.set()
                if scenario in ('completed', 'residual', 'driver_error', 'mps', 'incomplete'): finished.set()
                class Tree:
                    pid = 123; group = Path(root)
                    def __init__(self):
                        self.stopped = scenario == 'crashed'
                        self.process = SimpleNamespace(poll=lambda: 137 if self.stopped else None)
                    def active(self): return 0 if self.stopped else 1
                    def stop(self): self.stopped = True
                    def close(self): pass
                tree = Tree()
                env = {key:'fixture' for key in worker_module.OWNED_MODEL_ENV}
                env['LD_LIBRARY_PATH'] = driver_path
                if scenario == 'mps': env['CUDA_MPS_PIPE_DIRECTORY'] = '/tmp/shared'
                boot = {'command':['/runtime/envs/retrieval/bin/python3.12.0','/runtime/tools/offline_delivery/model_session_worker.py'],
                    'cgroupRoot':root, 'env':env,
                    'runtimeAssets':[{'path':str(driver), 'sha256':hashlib.sha256(b'fixture').hexdigest()}]}
                sample = OSError('driver unavailable') if scenario == 'driver_error' else {}
                with patch.object(worker, '_validate_profile', return_value=(boot,5)), \
                     patch.object(worker_module, '_read_only_driver_mount', return_value=True), \
                     patch.object(worker, '_delegated_root', return_value=Path(root)), \
                     patch.object(worker, '_cgroup_pids', return_value={os.getpid(),77} if scenario == 'residual' else {os.getpid()}), \
                     patch.object(worker_module, '_LinuxTree', return_value=tree), \
                     patch.object(worker_module, 'observe_running', return_value=None,
                                  side_effect=worker_module.GpuObservationIncomplete('exited during sample') if scenario == 'incomplete' else None), \
                     patch.object(worker_module, 'observe_single_idle_device', return_value={'sha256':'a'*64}), \
                     patch.object(worker_module, 'observe_owned_tree_release', side_effect=sample if isinstance(sample, Exception) else None, return_value=sample):
                    worker._execute(directory, operation, {'mode':'model-session','profile':{},'boot':boot}, cancellation, finished)
                status = worker._record(operation['operationId'])
                if scenario in ('residual', 'driver_error', 'mps', 'incomplete'):
                    self.assertEqual(status['status'], 'release_unknown')
                    self.assertTrue(worker.marker.exists())
                else:
                    self.assertEqual(status['status'], {'completed':'completed','expired':'expired',
                        'cancelled':'cancelled','crashed':'failed'}[scenario])
                    self.assertTrue(gpu_release_proven(status['release'], owned_tree=True))
                    self.assertFalse(worker.marker.exists())


if __name__ == '__main__':
    unittest.main()
