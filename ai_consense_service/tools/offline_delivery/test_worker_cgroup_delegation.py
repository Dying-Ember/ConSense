"""Pure cgroup identity checks; no Docker, GPU or real cgroupfs writes."""
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
if HERE.name == 'offline_delivery':
    sys.path.insert(0, str(HERE.parent / 'vetting_eval'))
else:
    sys.path.insert(0, str(HERE))
    sys.path.insert(1, str(HERE.parents[2] / 'ai_consense_service/tools/offline_delivery'))

from native_provider_lifecycle import _child_cgroup_identity
from fixed_gpu_worker import Worker


class CgroupCandidateTest(unittest.TestCase):
    def test_bind_mount_path_uses_actual_cgroup_identity(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'cgroup.procs').write_text('123\n')
            self.assertEqual('0::/docker/gpu/' + 'a' * 64 + '/task',
                             _child_cgroup_identity(root, 'task', '0::/docker/gpu/' + 'a' * 64, 123))
            with self.assertRaisesRegex(ValueError, 'owner is not in delegated'):
                _child_cgroup_identity(root, 'task', '0::/docker/gpu/' + 'a' * 64, 456)

    def test_full_child_subtree_catches_sibling(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            sibling = root / 'unowned-sibling'
            sibling.mkdir()
            (root / 'cgroup.procs').write_text('123\n')
            (sibling / 'cgroup.procs').write_text('456\n')
            self.assertEqual({123, 456}, Worker._cgroup_pids(root))
            (sibling / 'cgroup.procs').write_text('')
            self.assertEqual({123}, Worker._cgroup_pids(root))

    @unittest.skipUnless(hasattr(os, 'chown'), 'Linux ownership fixture')
    def test_worker_root_requires_exact_child_and_clean_subtree(self):
        cid = 'a' * 64
        with TemporaryDirectory() as directory:
            parent = Path(directory) / 'delegated'
            root = parent / cid
            root.mkdir(parents=True)
            (parent / 'cgroup.procs').write_text('')
            (root / 'cgroup.procs').write_text('123\n')
            os.chown(root / 'cgroup.procs', 10001, 10001)
            worker = Worker({'workspace': directory, 'containerId': cid, 'stages': {}}, 'fixture-secret')
            real_read = Path.read_text

            def read(path, *args, **kwargs):
                if str(path) == '/proc/self/cgroup':
                    return '0::/docker/consense-gpu-serial04/' + cid + '\n'
                return real_read(path, *args, **kwargs)

            real_path = Path
            with patch('fixed_gpu_worker.Path', side_effect=lambda path: root if path == '/delegated/' + cid else real_path(path)), \
                 patch.object(Path, 'read_text', read), patch('fixed_gpu_worker.os.geteuid', return_value=10001), \
                 patch('fixed_gpu_worker.os.getpid', return_value=123), \
                 patch('fixed_gpu_worker.os.access', side_effect=lambda path, mode: Path(path) == root / 'cgroup.procs'):
                self.assertEqual(root, worker._delegated_root({'cgroupRoot': '/delegated/' + cid}))
                sibling = root / 'other'
                sibling.mkdir()
                (sibling / 'cgroup.procs').write_text('456\n')
                with self.assertRaisesRegex(ValueError, 'not initially isolated'):
                    worker._delegated_root({'cgroupRoot': '/delegated/' + cid})
            with self.assertRaisesRegex(ValueError, 'exact delegated Docker child path'):
                worker._delegated_root({'cgroupRoot': str(root)})

    @unittest.skipUnless(hasattr(os, 'chown'), 'Linux ownership fixture')
    def test_systemd_scope_requires_exact_identity_permissions_and_empty_siblings(self):
        cid = 'a' * 64
        leaf = 'docker-' + cid + '.scope'
        configured = '/delegated/' + leaf
        actual = '/sys/fs/cgroup/system.slice/' + leaf
        with TemporaryDirectory() as directory:
            parent = Path(directory) / 'delegated'
            root = parent / leaf
            root.mkdir(parents=True)
            (parent / 'cgroup.procs').write_text('')
            (root / 'cgroup.procs').write_text('123\n')
            os.chown(root / 'cgroup.procs', 10001, 10001)
            other = Path(directory) / 'other'; other.mkdir()
            link = Path(directory) / 'link'; link.symlink_to(root, target_is_directory=True)
            worker = Worker({'workspace': directory, 'containerId': cid, 'stages': {}}, 'fixture-secret')
            own = ['0::/system.slice/' + leaf + '\n']
            canonical = [root]
            real_path, real_read = Path, Path.read_text
            def mapped(path):
                if path == configured: return root
                if path == actual: return canonical[0]
                return real_path(path)
            def read(path, *args, **kwargs):
                return own[0] if str(path) == '/proc/self/cgroup' else real_read(path, *args, **kwargs)
            access = lambda path, mode: Path(path) == root / 'cgroup.procs'
            with patch('fixed_gpu_worker.Path', side_effect=mapped), patch.object(Path, 'read_text', read), \
                 patch('fixed_gpu_worker.os.geteuid', return_value=10001), \
                 patch('fixed_gpu_worker.os.getpid', return_value=123), \
                 patch('fixed_gpu_worker.os.access', side_effect=access):
                self.assertEqual(root, worker._delegated_root({'cgroupRoot': configured}))
                own[0] = '0::/system.slice/docker-' + 'b' * 64 + '.scope\n'
                with self.assertRaisesRegex(ValueError, 'identity differs'):
                    worker._delegated_root({'cgroupRoot': configured})
                own[0] = '0::/system.slice/' + leaf + '\n'
                for wrong in (other, link):
                    canonical[0] = wrong
                    with self.assertRaisesRegex(ValueError, 'does not match'):
                        worker._delegated_root({'cgroupRoot': configured})
                canonical[0] = root
                with patch('fixed_gpu_worker.os.access', return_value=True):
                    with self.assertRaisesRegex(ValueError, 'only the exact'):
                        worker._delegated_root({'cgroupRoot': configured})
                with patch('fixed_gpu_worker.os.geteuid', return_value=10002):
                    with self.assertRaisesRegex(ValueError, 'only the exact'):
                        worker._delegated_root({'cgroupRoot': configured})
                sibling = root / 'other'; sibling.mkdir(); (sibling / 'cgroup.procs').write_text('456\n')
                with self.assertRaisesRegex(ValueError, 'not initially isolated'):
                    worker._delegated_root({'cgroupRoot': configured})
                with self.assertRaisesRegex(ValueError, 'exact delegated Docker child path'):
                    worker._delegated_root({'cgroupRoot': '/delegated/docker-' + 'b' * 64 + '.scope'})


if __name__ == '__main__':
    unittest.main()
