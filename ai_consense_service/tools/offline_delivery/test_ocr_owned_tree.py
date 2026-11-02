import hashlib
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import fixed_gpu_worker as module
from fixed_gpu_client import gpu_release_proven
from request_stage import encoded, envelope


class OcrOwnedTreeTest(unittest.TestCase):
    @unittest.skipUnless(os.name == 'posix', 'Linux session supervision')
    def test_ocr_release_requires_own_process_tree_and_no_observation_conflict(self):
        for scenario in ('success', 'residual', 'driver_error', 'incomplete', 'external', 'injected'):
            with self.subTest(scenario=scenario), TemporaryDirectory() as root:
                driver = Path(root) / 'nvidia-smi'
                driver.write_bytes(b'fixture')
                env = {'CUDA_VISIBLE_DEVICES':'0'}
                if scenario == 'injected': env['LD_PRELOAD'] = '/other.so'
                boot = {'command':['/runtime/ocr-envs/mineru/bin/python3.12.0', '/runtime/tools/vetting_eval/mineru_native_server.py'],
                        'cgroupRoot':root, 'env':env,
                        'runtimeAssets':[{'path':str(driver), 'sha256':hashlib.sha256(b'fixture').hexdigest()}]}
                profile = {'mode':'session', 'profile':{'adapter':'consense.native-api-session.v1'}, 'boot':boot}
                worker = module.Worker({'workspace':root, 'stages':{'mineru':profile}, 'containerId':'fixture',
                                        'nvidiaSmi':str(driver), 'gpuUuid':'GPU-fixture'}, 'example-secret')
                operation = envelope('mineru', b'{}', {'parser':'mineru'}, profile['profile'])
                directory = worker.operations / operation['operationId']
                directory.mkdir()
                (directory / 'request.json').write_bytes(encoded(operation))
                (directory / 'status.json').write_bytes(encoded({'operationId':operation['operationId'],
                    'stage':'mineru', 'status':'starting', 'bindingSha256':operation['bindingSha256'],
                    'configSha256':worker.config_sha}))
                (directory / 'transaction.json').write_bytes(b'{}')
                worker.marker.write_bytes(encoded({'operationId':operation['operationId'], 'state':'occupied_or_release_unknown'}))
                cancellation, finished = threading.Event(), threading.Event()
                finished.set()
                worker.active[operation['operationId']] = cancellation, finished
                worker.sessions[operation['operationId']] = {}
                class Tree:
                    pid = 123
                    group = Path(root)
                    def __init__(self):
                        self.stopped = False
                        self.process = SimpleNamespace(poll=lambda: 0 if self.stopped else None)
                    def active(self): return 0 if self.stopped else 1
                    def stop(self): self.stopped = True
                    def close(self): pass
                tree = Tree()
                sample_error = (module.GpuObservationIncomplete('uncertain') if scenario == 'incomplete'
                                else ValueError('outside task') if scenario == 'external' else None)
                terminal_error = OSError('driver unavailable') if scenario == 'driver_error' else None
                with patch.object(worker, '_validate_profile', return_value=(boot, 5)), \
                     patch.object(worker, '_delegated_root', return_value=Path(root)), \
                     patch.object(worker, '_cgroup_pids', return_value={os.getpid(),77} if scenario == 'residual' else {os.getpid()}), \
                     patch.object(module, '_LinuxTree', return_value=tree), \
                     patch.object(module, 'observe_running', return_value=None, side_effect=sample_error), \
                     patch.object(module, 'observe_single_idle_device', return_value={'sha256':'a'*64}), \
                     patch.object(module, 'observe_owned_tree_release', side_effect=terminal_error):
                    worker._execute(directory, operation, profile, cancellation, finished)
                status = worker._record(operation['operationId'])
                if scenario != 'external':
                    self.assertEqual(status.get('transactionSha256'), hashlib.sha256(b'{}').hexdigest(), status)
                if scenario == 'success':
                    self.assertEqual(status['status'], 'completed')
                    self.assertTrue(gpu_release_proven(status['release'], owned_tree=True))
                    self.assertFalse(worker.marker.exists())
                else:
                    self.assertEqual(status['status'], 'release_unknown')
                    self.assertTrue(worker.marker.exists())

    def test_only_registered_local_ocr_sessions_use_fallback(self):
        boot = {'command':['/runtime/ocr-envs/mineru/bin/python3.12.0','/runtime/tools/vetting_eval/mineru_native_server.py'],
                'env':{'CUDA_VISIBLE_DEVICES':'0'}}
        profile = {'mode':'session','profile':{'adapter':'consense.native-api-session.v1'}}
        self.assertTrue(module.owned_ocr_boot('mineru', profile, boot))
        self.assertFalse(module.owned_ocr_boot('paddle', profile, boot))
        self.assertFalse(module.owned_ocr_boot('mineru', {**profile,'mode':'job'}, boot))
        self.assertFalse(module.owned_ocr_boot('mineru', profile, {**boot,'env':{'CUDA_VISIBLE_DEVICES':'1'}}))
        self.assertFalse(module.owned_ocr_boot('mineru', profile, {**boot,'command':['/other/python', boot['command'][1]]}))


if __name__ == '__main__':
    unittest.main()
