"""Focused software checks for the isolated GPU release witness candidate."""
import json
import os
import subprocess
import sys
import threading
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(1, str(HERE.parents[2] / 'ai_consense_service' / 'tools' / 'offline_delivery'))
sys.path.insert(2, str(HERE.parents[2] / 'ai_consense_service' / 'tools' / 'vetting_eval'))

import gpu_observation as subject
import fixed_gpu_worker as worker_module
from request_stage import encoded, envelope

UUID = 'GPU-fixture-1'
OP = {'operationId':'a'*32, 'bindingSha256':'b'*64, 'profileSha256':'c'*64}
GROUP = {'path':'/fixture', 'device':1, 'inode':2}
DRIVER = {'path':'/fixture/nvidia-smi', 'sha256':'d'*64, 'device':1, 'inode':2, 'size':3, 'mtimeNs':4}
P33 = {'pid':33, 'startTicks':101, 'pidNamespace':'pid:[1]'}
P34 = {'pid':34, 'startTicks':102, 'pidNamespace':'pid:[1]'}


def sample(pids):
    rows = ''.join(f'{UUID}, {pid}, [N/A]\n' for pid in pids)
    return subject._parse_driver_output(UUID+'\n', rows, UUID)


class ObservationTest(unittest.TestCase):
    def setUp(self):
        self.tree = Mock()
        self.tree.active.return_value = 1
        self.tree.process.poll.return_value = None
        for name, value in [('_group_identity',GROUP),('_boot_identity','fixture-boot'),
                            ('_executable_identity',DRIVER)]:
            patcher = patch.object(subject,name,return_value=value)
            patcher.start(); self.addCleanup(patcher.stop)

    def running(self, owned, gpu, previous=None, identities=None):
        identities = identities or {33:P33,34:P34}
        with patch.object(subject,'_tree_pids',return_value=set(owned)), \
             patch.object(subject,'_process_identity',side_effect=lambda pid: identities[pid]), \
             patch.object(subject,'_driver_sample',return_value=sample(gpu)):
            return subject.observe_running(self.tree,DRIVER['path'],DRIVER['sha256'],UUID,OP,previous)

    def test_no_live_gpu_pid_cannot_become_release_evidence(self):
        self.assertIsNone(self.running({33},set()))

    def test_cpu_children_changing_during_empty_gpu_sample_keep_prior_witness(self):
        previous = self.running({33}, {33})
        with patch.object(subject, '_tree_pids', side_effect=[{33, 34}, {33}]), \
             patch.object(subject, '_process_identity', side_effect=lambda pid: {33:P33, 34:P34}[pid]), \
             patch.object(subject, '_driver_sample', return_value=sample(set())):
            self.assertIs(subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP, previous), previous)

    def test_disappearing_cpu_child_does_not_mask_unverified_gpu_pid(self):
        def identity(pid):
            if pid == 34: raise FileNotFoundError('child exited')
            return P33
        with patch.object(subject, '_tree_pids', return_value={33, 34}), \
             patch.object(subject, '_process_identity', side_effect=identity), \
             patch.object(subject, '_driver_sample', return_value=sample(set())):
            self.assertIsNone(subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP))
        with patch.object(subject, '_tree_pids', return_value={33, 34}), \
             patch.object(subject, '_process_identity', side_effect=identity), \
             patch.object(subject, '_driver_sample', return_value=sample({34})):
            with self.assertRaisesRegex(ValueError, 'GPU process identity unavailable'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)

    def test_disappearing_cpu_child_does_not_prevent_binding_stable_gpu_pid(self):
        def identity(pid):
            if pid == 34: raise ProcessLookupError('child exited')
            return P33
        with patch.object(subject, '_tree_pids', side_effect=[{33, 34}, {33}]), \
             patch.object(subject, '_process_identity', side_effect=identity), \
             patch.object(subject, '_driver_sample', return_value=sample({33})):
            result = subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)
        self.assertEqual(result['processes'], [P33])

    def test_unexpected_process_identity_error_is_not_ignored(self):
        with patch.object(subject, '_tree_pids', return_value={33}), \
             patch.object(subject, '_process_identity', side_effect=PermissionError('cannot inspect')), \
             patch.object(subject, '_driver_sample', return_value=sample(set())):
            with self.assertRaises(PermissionError):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)

    def test_late_owned_gpu_pid_requires_stable_resample(self):
        with patch.object(subject, '_tree_pids', side_effect=[{33}, {33, 34}, {33, 34}, {33, 34}]), \
             patch.object(subject, '_process_identity', side_effect=lambda pid: {33:P33, 34:P34}[pid]), \
             patch.object(subject, '_driver_sample', side_effect=[sample({34}), sample({34})]) as query:
            result = subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)
        self.assertEqual(result['processes'], [P34])
        self.assertEqual(query.call_count, 2)

    def test_late_foreign_gpu_pid_is_rejected(self):
        with patch.object(subject, '_tree_pids', side_effect=[{33}, {33}]), \
             patch.object(subject, '_process_identity', return_value=P33), \
             patch.object(subject, '_driver_sample', return_value=sample({34})):
            with self.assertRaisesRegex(ValueError, 'outside owned task'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)

    def test_owned_pid_exiting_during_sample_is_incomplete_not_foreign(self):
        def identity(pid):
            if identity.calls:
                raise ProcessLookupError('exited')
            identity.calls += 1
            return P33
        identity.calls = 0
        with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
             patch.object(subject, '_process_identity', side_effect=identity), \
             patch.object(subject.os, 'kill', side_effect=ProcessLookupError('exited')), \
             patch.object(subject, '_driver_sample', return_value=sample({33})):
            with self.assertRaises(subject.GpuObservationIncomplete):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)

    def test_owned_pid_still_alive_after_leaving_group_remains_rejected(self):
        with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
             patch.object(subject, '_process_identity', return_value=P33), \
             patch.object(subject.os, 'kill', return_value=None), \
             patch.object(subject, '_driver_sample', return_value=sample({33})):
            with self.assertRaises(ValueError) as failure:
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)
            self.assertNotIsInstance(failure.exception, subject.GpuObservationIncomplete)

    def test_missing_proc_namespace_does_not_establish_process_exit(self):
        # /proc/PID/ns/pid can disappear or be unreadable while PID is alive.
        with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
             patch.object(subject, '_process_identity', side_effect=FileNotFoundError('namespace unavailable')), \
             patch.object(subject.os, 'kill', return_value=None), \
             patch.object(subject, '_driver_sample', return_value=sample({33})):
            with self.assertRaises(ValueError) as failure:
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)
            self.assertNotIsInstance(failure.exception, subject.GpuObservationIncomplete)

    def test_kill_permission_error_does_not_establish_process_exit(self):
        with patch.object(subject.os, 'kill', side_effect=PermissionError('not permitted')):
            self.assertFalse(subject._process_exited(33))

    def test_outside_gpu_pid_error_has_bounded_diagnostics(self):
        with patch.object(subject, '_tree_pids', side_effect=[{33}, {33}]), \
             patch.object(subject, '_process_identity', return_value=P33), \
             patch.object(subject, '_driver_sample', return_value=sample({34})):
            with self.assertRaises(ValueError) as failure:
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)
        message = str(failure.exception)
        for value in ('target GPU process outside owned task cgroup', 'driverPids=[34]',
                      'groupBefore=[33]', 'groupAfter=[33]', 'witness=False',
                      'processAliveAfter=True', 'groupActiveAfter=1'):
            self.assertIn(value, message)

    def test_previously_witnessed_gpu_pid_may_outlive_exiting_task_sample(self):
        previous = self.running({33}, {33})
        self.tree.active.side_effect = [1, 0]
        self.tree.process.poll.side_effect = [None, 1]
        with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
             patch.object(subject, '_process_identity', return_value=P33), \
             patch.object(subject, '_driver_sample', return_value=sample({33})):
            self.assertIs(subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP, previous), previous)

    def test_witnessed_main_process_can_settle_after_group_snapshot(self):
        previous = self.running({33}, {33})
        self.tree.pid = 33
        self.tree.active.side_effect = [1, 1, 0]
        self.tree.process.poll.side_effect = [None, 124]
        self.tree.process.wait.return_value = 124
        with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
             patch.object(subject, '_process_identity', return_value=P33), \
             patch.object(subject, '_driver_sample', return_value=sample({33})):
            self.assertIs(subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP, previous), previous)
        self.tree.process.wait.assert_called_once_with(timeout=2)

    def test_exit_wait_is_bounded_and_nonempty_subtree_remains_unknown(self):
        for mode in ('wait_timeout', 'group_active'):
            with self.subTest(mode=mode):
                previous = self.running({33}, {33})
                self.tree.pid = 33
                self.tree.active.side_effect = [1, 1, 1] if mode == 'group_active' else [1, 1]
                self.tree.process.poll.return_value = None
                self.tree.process.wait.side_effect = subprocess.TimeoutExpired('worker', 2) if mode == 'wait_timeout' else None
                with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
                     patch.object(subject, '_process_identity', return_value=P33), \
                     patch.object(subject, '_driver_sample', return_value=sample({33})):
                    with self.assertRaises(subject.GpuObservationIncomplete):
                        subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP, previous)
                self.tree.active.side_effect = None
                self.tree.active.return_value = 1
                self.tree.process.wait.side_effect = None
                self.tree.process.wait.reset_mock()

    def test_exit_wait_never_accepts_reused_foreign_or_unwitnessed_pid(self):
        previous = self.running({33}, {33})
        self.tree.pid = 33
        with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
             patch.object(subject, '_process_identity', return_value={**P33, 'startTicks':999}), \
             patch.object(subject, '_driver_sample', return_value=sample({33})):
            with self.assertRaisesRegex(ValueError, 'reused or changed'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP, previous)
        self.tree.process.wait.assert_not_called()
        with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
             patch.object(subject, '_process_identity', return_value=P33), \
             patch.object(subject, '_driver_sample', return_value=sample({34})):
            with self.assertRaisesRegex(ValueError, 'outside owned task'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP, previous)
        self.tree.process.wait.assert_not_called()

    def test_previously_witnessed_task_can_exit_before_observation(self):
        previous = self.running({33}, {33})
        self.tree.active.return_value = 0
        self.tree.process.poll.return_value = 1
        self.assertIs(subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP, previous), previous)

    def test_exiting_task_still_rejects_unwitnessed_gpu_pid(self):
        previous = self.running({33}, {33})
        self.tree.active.side_effect = [1, 0]
        self.tree.process.poll.side_effect = [None, 1]
        with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
             patch.object(subject, '_process_identity', return_value=P33), \
             patch.object(subject, '_driver_sample', return_value=sample({34})):
            with self.assertRaisesRegex(ValueError, 'outside owned task'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP, previous)

    def test_exiting_task_rejects_previously_witnessed_pid_with_new_identity(self):
        previous = self.running({33}, {33})
        self.tree.active.side_effect = [1, 0]
        self.tree.process.poll.side_effect = [None, 1]
        with patch.object(subject, '_tree_pids', side_effect=[{33}, set()]), \
             patch.object(subject, '_process_identity', return_value={**P33, 'startTicks':999}), \
             patch.object(subject, '_driver_sample', return_value=sample({33})):
            with self.assertRaisesRegex(ValueError, 'reused or changed'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP, previous)

    def test_late_gpu_pid_must_remain_and_keep_its_identity(self):
        with patch.object(subject, '_tree_pids', side_effect=[{33}, {33, 34}, {33}]), \
             patch.object(subject, '_process_identity', side_effect=lambda pid: {33:P33, 34:P34}[pid]), \
             patch.object(subject, '_driver_sample', side_effect=[sample({34}), sample(set())]):
            with self.assertRaisesRegex(ValueError, 'unverified GPU process disappeared'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)
        identities = {33:P33, 34:P34}
        calls = []
        def identity(pid):
            calls.append(pid)
            if pid == 34 and calls.count(34) > 1:
                return {**P34, 'startTicks':999}
            return identities[pid]
        with patch.object(subject, '_tree_pids', side_effect=[{33}, {33, 34}, {33, 34}]), \
             patch.object(subject, '_process_identity', side_effect=identity), \
             patch.object(subject, '_driver_sample', side_effect=[sample({34}), sample({34})]):
            with self.assertRaisesRegex(ValueError, 'unverified GPU process disappeared or changed'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)

    def test_repeated_late_gpu_pids_exhaust_bounded_resampling(self):
        identities = {pid:{'pid':pid, 'startTicks':pid * 10, 'pidNamespace':'pid:[1]'} for pid in range(33, 37)}
        with patch.object(subject, '_tree_pids', side_effect=[{33}, {33,34}, {33,34}, {33,34,35}, {33,34,35}, {33,34,35,36}]), \
             patch.object(subject, '_process_identity', side_effect=lambda pid: identities[pid]), \
             patch.object(subject, '_driver_sample', side_effect=[sample({34}), sample({34,35}), sample({34,35,36})]) as query:
            with self.assertRaisesRegex(ValueError, 'bounded resampling'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)
        self.assertEqual(query.call_count, 3)

    def test_empty_gpu_sample_still_rejects_changed_group_or_boot(self):
        with patch.object(subject, '_tree_pids', return_value={33}), \
             patch.object(subject, '_process_identity', return_value=P33), \
             patch.object(subject, '_driver_sample', return_value=sample(set())), \
             patch.object(subject, '_group_identity', side_effect=[GROUP, {**GROUP, 'inode': 99}]):
            with self.assertRaisesRegex(ValueError, 'task process identity changed'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)
        with patch.object(subject, '_tree_pids', return_value={33}), \
             patch.object(subject, '_process_identity', return_value=P33), \
             patch.object(subject, '_driver_sample', return_value=sample(set())), \
             patch.object(subject, '_boot_identity', side_effect=['boot-a', 'boot-b']):
            with self.assertRaisesRegex(ValueError, 'task process identity changed'):
                subject.observe_running(self.tree, DRIVER['path'], DRIVER['sha256'], UUID, OP)

    def test_wsl_na_pid_is_bound_and_new_pids_accumulate(self):
        first = self.running({33}, {33})
        self.assertEqual(first['processes'],[P33])
        self.assertIs(self.running({33},set(),first),first)
        second = self.running({33,34},{33,34},first)
        self.assertEqual(second['processes'],[P33,P34])
        self.assertEqual(len(second['observations']),2)

    def test_foreign_gpu_pid_and_pid_reuse_fail_closed(self):
        with self.assertRaisesRegex(ValueError,'outside owned task'):
            self.running({33},{33,34})
        first = self.running({33},{33})
        with self.assertRaisesRegex(ValueError,'reused or changed'):
            self.running({33},{33},first,{33:{**P33,'startTicks':999}})

    def test_waited_child_and_absent_gpu_pid_complete_local_observation(self):
        running = self.running({33},{33})
        self.tree.active.return_value = 0
        self.tree.process.poll.return_value = 0
        with patch.object(subject,'_driver_sample',return_value=sample(set())):
            result = subject.observe_release(self.tree,DRIVER['path'],running,OP,UUID)
        self.assertEqual(result['status'],'local_observation_complete')
        self.assertEqual(result['terminalDriverSample']['processesMiB'],{})

    def test_terminal_foreign_pid_and_profile_change_remain_unknown(self):
        running = self.running({33},{33})
        self.tree.active.return_value = 0
        self.tree.process.poll.return_value = 0
        with patch.object(subject,'_driver_sample',return_value=sample({34})):
            self.assertEqual(subject.observe_release(self.tree,DRIVER['path'],running,OP,UUID)['status'],'release_unknown')
        with patch.object(subject,'_driver_sample',return_value=sample(set())):
            changed = {**OP,'profileSha256':'e'*64}
            self.assertEqual(subject.observe_release(self.tree,DRIVER['path'],running,changed,UUID)['status'],'release_unknown')

    def test_terminal_unwaited_or_populated_child_remains_unknown(self):
        running = self.running({33},{33})
        with patch.object(subject,'_driver_sample',return_value=sample(set())):
            self.tree.active.return_value = 1
            self.tree.process.poll.return_value = 0
            self.assertEqual(subject.observe_release(self.tree,DRIVER['path'],running,OP,UUID)['status'],
                             'release_unknown')
            self.tree.active.return_value = 0
            self.tree.process.poll.return_value = None
            self.assertEqual(subject.observe_release(self.tree,DRIVER['path'],running,OP,UUID)['status'],
                             'release_unknown')

    def test_pid_namespace_change_or_driver_query_failure_remains_unknown(self):
        running = self.running({33},{33})
        with self.assertRaisesRegex(ValueError,'reused or changed'):
            self.running({33},{33},running,{33:{**P33,'pidNamespace':'pid:[999]'}})
        self.tree.active.return_value = 0
        self.tree.process.poll.return_value = 0
        with patch.object(subject,'_driver_sample',side_effect=OSError('driver unavailable')):
            self.assertEqual(subject.observe_release(self.tree,DRIVER['path'],running,OP,UUID)['status'],
                             'release_unknown')


class WorkerIntegrationTest(unittest.TestCase):
    def execute(self, observation, root_pids=None, model_session=False, final_observation=None,
                initial_witness=False, cancel_after_sample=False, final_sample_event=None):
        with TemporaryDirectory() as root:
            driver = Path(root) / 'nvidia-smi'; driver.write_text('fixture')
            worker = worker_module.Worker({'workspace':root,'stages':{},'containerId':'fixture',
                'nvidiaSmi':str(driver),'gpuUuid':UUID},'example-secret')
            operation = envelope('document-embedding',b'{}',{'source':'fixture'},{'model':'fixture'})
            directory = worker.operations / operation['operationId']; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(operation))
            (directory/'status.json').write_bytes(encoded({'operationId':operation['operationId'],
                'stage':operation['stage'],'status':'starting','bindingSha256':operation['bindingSha256']}))
            worker.marker.write_bytes(encoded({'operationId':operation['operationId'],
                                               'state':'occupied_or_release_unknown'}))
            cancellation, finished = threading.Event(), threading.Event()
            if not model_session: finished.set()
            worker.active[operation['operationId']] = (cancellation,finished)
            if model_session:
                worker.sessions[operation['operationId']] = {'deadline':time.monotonic()+(0.2 if final_sample_event == 'expired' else 60)}
                (directory/'model-session').mkdir()
                (directory/'model-session'/'finished.json').write_text('{}')
            class Tree:
                pid=33; group=Path(root); process=SimpleNamespace(poll=lambda:0)
                def __init__(self): self.count=0; self.closed=False
                def active(self): self.count+=1; return 1 if self.count == 1 else 0
                def stop(self): pass
                def close(self): self.closed=True
            tree=Tree()
            boot={'cgroupRoot':root,'env':{},'runtimeAssets':[{'path':str(driver),'sha256':'d'*64}]}
            witness={'processes':[P33]}
            def running(*args):
                if not finished.is_set():
                    finished.set()
                    if cancel_after_sample: cancellation.set()
                    return witness if initial_witness else None
                if final_sample_event == 'cancelled': cancellation.set()
                if final_sample_event == 'expired': time.sleep(.25)
                if isinstance(final_observation, Exception): raise final_observation
                return witness if final_observation is None else final_observation
            with patch.object(worker,'_validate_profile',return_value=(boot,1)), \
                 patch.object(worker,'_delegated_root',return_value=Path(root)), \
                 patch.object(worker,'_cgroup_pids',return_value={os.getpid()} if root_pids is None else root_pids), \
                 patch.object(worker,'_model_ready',return_value=False), \
                 patch.object(worker_module,'_LinuxTree',return_value=tree), \
                 patch.object(worker_module,'sync_directory'), \
                 patch.object(Path,'read_text',return_value='0::/fixture'), \
                 patch.object(worker_module,'observe_running',side_effect=running if model_session else None,
                              return_value=witness) as observe, \
                 patch.object(worker_module,'observe_release',return_value=observation):
                worker._execute(directory,operation,{'mode':'model-session' if model_session else 'job','profile':{}},cancellation,finished)
            record=json.loads((directory/'status.json').read_bytes())
            return record, worker.marker.exists(), tree.closed, observe.call_count

    def test_unknown_retains_owner_and_closes_tree(self):
        record, marker, closed, _ = self.execute({'status':'release_unknown','reason':'capability unverified'})
        self.assertEqual(record['status'],'release_unknown')
        self.assertEqual(record['release'],{'status':'release_unknown'})
        self.assertTrue(marker and closed)

    def test_synthetic_positive_maps_to_existing_v1_receipt_shape(self):
        record, marker, closed, _ = self.execute({'status':'local_observation_complete'})
        self.assertEqual(record['release'],{'status':'released','cgroupPopulated':0,'observedGpuPids':[33]})
        self.assertFalse(marker)
        self.assertTrue(closed)

    def test_docker_child_residue_keeps_owner_even_after_empty_gpu_query(self):
        record, marker, closed, _ = self.execute({'status':'local_observation_complete'}, {os.getpid(),77})
        self.assertEqual(record['status'],'release_unknown')
        self.assertEqual(record['release'],{'status':'release_unknown'})
        self.assertTrue(marker and closed)

    def test_model_finish_after_empty_sample_gets_one_last_owned_sample(self):
        record, marker, closed, count = self.execute({'status':'local_observation_complete'}, model_session=True)
        self.assertEqual(count, 2)
        self.assertEqual(record['status'], 'completed')
        self.assertEqual(record['release']['status'], 'released')
        self.assertFalse(marker)
        self.assertTrue(closed)

    def test_model_finish_rechecks_cancel_and_expiry_after_last_sample(self):
        for event in ('cancelled', 'expired'):
            with self.subTest(event=event):
                record, marker, closed, count = self.execute({'status':'local_observation_complete'},
                    model_session=True, final_sample_event=event)
                self.assertEqual(count, 2)
                self.assertEqual(record['status'], event)
                self.assertEqual(record['release']['status'], 'released')
                self.assertFalse(marker)
                self.assertTrue(closed)

    def test_failed_final_model_sample_keeps_owner(self):
        record, marker, closed, count = self.execute({'status':'local_observation_complete'}, model_session=True,
                                                      final_observation=ValueError('GPU identity changed'))
        self.assertEqual(count, 2)
        self.assertEqual(record['release']['status'], 'release_unknown')
        self.assertTrue(marker and closed)

    def test_model_finish_with_existing_witness_does_not_resample(self):
        record, marker, closed, count = self.execute({'status':'local_observation_complete'}, model_session=True,
                                                      initial_witness=True)
        self.assertEqual(count, 1)
        self.assertEqual(record['release']['status'], 'released')
        self.assertFalse(marker)
        self.assertTrue(closed)

    def test_cancel_after_finish_wins_over_completion(self):
        record, marker, closed, count = self.execute({'status':'local_observation_complete'}, model_session=True,
                                                      cancel_after_sample=True)
        self.assertEqual(count, 1)
        self.assertEqual(record['status'], 'release_unknown')
        self.assertEqual(record['release']['status'], 'release_unknown')
        self.assertTrue(marker)
        self.assertTrue(closed)


if __name__ == '__main__': unittest.main()
