"""Bounded GPU PID observations for one owned task on the fixed worker.

Inputs come from the worker's owned tree and pinned driver executable, never
from an HTTP/config receipt. This is an operational release check, not a claim
that NVIDIA exposes every possible GPU context on every platform.
"""
import csv
import hashlib
import io
import os
import re
import stat
import subprocess
from pathlib import Path


class GpuObservationIncomplete(ValueError):
    """A task process exited during a driver sample; ownership is unresolved."""


def _require(value, message):
    if not value:
        raise ValueError(message)


def _parse_driver_output(devices, raw, uuid):
    _require(isinstance(uuid, str) and re.fullmatch(r'GPU-[A-Za-z0-9-]+', uuid), 'invalid GPU UUID')
    _require(isinstance(devices, str) and isinstance(raw, str), 'driver output unavailable')
    _require(len(devices) <= 262144 and len(raw) <= 262144, 'driver output exceeds bound')
    uuids = [row.strip() for row in devices.splitlines() if row.strip()]
    _require(uuids.count(uuid) == 1, 'target GPU UUID not uniquely visible')
    processes = {}
    try:
        for row in csv.reader(io.StringIO(raw), skipinitialspace=True, strict=True):
            _require(len(row) == 3, 'malformed compute process row')
            gpu, pid, memory = (part.strip() for part in row)
            if gpu != uuid:
                continue
            _require(pid.isdigit() and int(pid) > 0 and (memory.isdigit() or memory == '[N/A]'),
                     'PID or per-process memory malformed')
            pid = int(pid)
            _require(pid not in processes, 'duplicate GPU PID is ambiguous')
            processes[pid] = None if memory == '[N/A]' else int(memory)
    except csv.Error as error:
        raise ValueError('malformed compute process CSV') from error
    return {'uuid': uuid, 'processesMiB': processes, 'devicesRaw': devices, 'processesRaw': raw}


def _executable_identity(executable, expected_sha256):
    path = Path(executable)
    _require(path.is_absolute() and not path.is_symlink(), 'driver executable path is not pinned')
    _require(isinstance(expected_sha256, str) and re.fullmatch('[a-f0-9]{64}', expected_sha256), 'driver executable digest unavailable')
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags)
    try:
        before = os.fstat(fd)
        _require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= 32 * 1024 * 1024, 'driver executable not regular or bounded')
        digest = hashlib.sha256()
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            for chunk in iter(lambda: stream.read(65536), b''): digest.update(chunk)
        after = os.fstat(fd)
        current = path.stat()
        key = lambda item: (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns)
        _require(key(before) == key(after) == key(current) and digest.hexdigest() == expected_sha256,
                 'driver executable identity changed')
        return {'path': str(path.resolve()), 'device':before.st_dev, 'inode':before.st_ino,
                'size':before.st_size, 'mtimeNs':before.st_mtime_ns, 'sha256':digest.hexdigest()}
    finally:
        os.close(fd)


def _driver_sample(executable, uuid, driver_identity, timeout=5):
    _require(type(timeout) in (int, float) and 0 < timeout <= 5, 'bounded driver timeout required')
    def query(flag):
        _require(_executable_identity(executable, driver_identity['sha256']) == driver_identity, 'driver executable changed before query')
        result = subprocess.run([str(executable), flag, '--format=csv,noheader,nounits'],
                                capture_output=True, text=True, check=True, timeout=timeout)
        _require(_executable_identity(executable, driver_identity['sha256']) == driver_identity, 'driver executable changed during query')
        return result.stdout
    return _parse_driver_output(query('--query-gpu=uuid'),
                                query('--query-compute-apps=gpu_uuid,pid,used_gpu_memory'), uuid)


def _tree_pids(tree):
    group = Path(tree.group)
    files = [group / 'cgroup.procs', *group.rglob('cgroup.procs')]
    pids = set()
    for file in files:
        for pid in file.read_text().split():
            _require(pid.isdigit() and int(pid) > 0, 'bad cgroup PID')
            pids.add(int(pid))
    return pids


def _process_identity(pid):
    path = Path('/proc') / str(pid)
    stat = (path / 'stat').read_text()
    end = stat.rfind(')')
    _require(end >= 0, 'malformed process stat')
    fields = stat[end+1:].split()
    _require(len(fields) > 19 and fields[19].isdigit(), 'process start ticks unavailable')
    namespace = os.readlink(path / 'ns' / 'pid')
    _require(namespace == os.readlink('/proc/self/ns/pid'), 'PID namespace differs')
    return {'pid': pid, 'startTicks': int(fields[19]), 'pidNamespace': namespace}


def _process_exited(pid):
    try:
        os.kill(pid, 0)
        return False
    except ProcessLookupError:
        return True
    except PermissionError:
        return False


def _group_identity(tree):
    group = Path(tree.group)
    info = group.stat()
    return {'path': str(group.resolve()), 'device': info.st_dev, 'inode': info.st_ino}


def _boot_identity():
    boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    _require(re.fullmatch(r'[0-9a-fA-F-]{36}', boot), 'boot identity unavailable')
    return boot.lower()


def observe_running(tree, executable, executable_sha256, gpu_uuid, operation, previous=None):
    """Accumulate every task GPU PID identity; missing GPU use remains unproven."""
    _require(type(operation) is dict and set(operation) == {'operationId','bindingSha256','profileSha256'}
             and isinstance(operation['operationId'], str) and re.fullmatch('[a-f0-9]{32}', operation['operationId'])
             and all(isinstance(operation[key], str) and re.fullmatch('[a-f0-9]{64}', operation[key])
                     for key in ('bindingSha256','profileSha256')), 'invalid operation identity')
    group = _group_identity(tree)
    boot = _boot_identity()
    driver = _executable_identity(executable, executable_sha256)
    if previous is not None:
        _require(previous['_tree'] is tree and previous['operation'] == operation
                 and previous['gpuUuid'] == gpu_uuid and previous['group'] == group
                 and previous['bootId'] == boot and previous['driver'] == driver,
                 'running task witness identity changed')
    if tree.process.poll() is not None or tree.active() != 1:
        _require(previous is not None and tree.process.poll() is not None and tree.active() == 0,
                 'task not active')
        return previous
    pending = {}
    for attempt in range(3):
        before = _tree_pids(tree)
        before_identity = {}
        for pid in before:
            try:
                before_identity[pid] = _process_identity(pid)
            except (FileNotFoundError, ProcessLookupError):
                # Short-lived CPU children may exit while the task remains active.
                pass
        if previous is not None:
            _require(all(before_identity[row['pid']] == row for row in previous['processes']
                         if row['pid'] in before_identity), 'observed GPU PID reused or changed identity')
        sample = _driver_sample(executable, gpu_uuid, driver)
        gpu_pids = set(sample['processesMiB'])
        _require(group == _group_identity(tree) and boot == _boot_identity(),
                 'task process identity changed during observation')
        _require(set(pending) <= gpu_pids and all(before_identity.get(pid) == identity for pid, identity in pending.items()),
                 'unverified GPU process disappeared or changed identity')
        if not gpu_pids:
            return previous
        # WSL reports [N/A] for each compute process even when a CUDA allocation is active.
        _require(all(sample['processesMiB'][pid] is None or sample['processesMiB'][pid] > 0 for pid in gpu_pids),
                 'task GPU process has zero reported memory')
        after = _tree_pids(tree)
        if (previous is not None and not after and tree.active() == 0 and tree.process.poll() is not None
                and gpu_pids <= {row['pid'] for row in previous['processes']}):
            # The driver may report the just-exited owned process until its context disappears.
            return previous
        if not gpu_pids <= after:
            known = {} if previous is None else {row['pid']: row for row in previous['processes']}
            if (previous is not None and not after and before == gpu_pids == {tree.pid}
                    and before_identity.get(tree.pid) == known.get(tree.pid) and tree.pid in known):
                # The witnessed main process can leave cgroup.procs before poll observes exit.
                try:
                    tree.process.wait(timeout=2)
                except subprocess.TimeoutExpired as error:
                    raise GpuObservationIncomplete('previously witnessed task exit did not settle') from error
                _require(group == _group_identity(tree) and boot == _boot_identity(),
                         'task process identity changed during exit wait')
                if tree.active() != 0 or tree.process.poll() is None:
                    raise GpuObservationIncomplete('previously witnessed task subtree remains active')
                return previous
            try:
                alive, active = tree.process.poll() is None, tree.active()
            except Exception:
                alive, active = None, None
            missing = gpu_pids - after
            # A driver sample can outlive a task child seen immediately before
            # it. This does not establish an external owner or a release.
            error_type = (GpuObservationIncomplete
                          if missing <= before and all(_process_exited(pid) for pid in missing)
                          else ValueError)
            raise error_type('target GPU process outside owned task cgroup; '
                f'driverPids={sorted(gpu_pids)[:16]} driverCount={len(gpu_pids)} '
                f'groupBefore={sorted(before)[:16]} beforeCount={len(before)} '
                f'groupAfter={sorted(after)[:16]} afterCount={len(after)} witness={previous is not None} '
                f'processAliveAfter={alive} groupActiveAfter={active}')
        _require(gpu_pids & before <= before_identity.keys(), 'GPU process identity unavailable during observation')
        after_identity = {pid: _process_identity(pid) for pid in gpu_pids}
        _require(all(before_identity[pid] == after_identity[pid] for pid in gpu_pids & before)
                 and group == _group_identity(tree) and boot == _boot_identity(),
                 'task process identity changed during observation')
        if gpu_pids <= before:
            break
        _require(attempt < 2, 'GPU process could not be bound in bounded resampling')
        # A process first seen after the query is not evidence until a stable resample binds it.
        pending = after_identity
    seen = {} if previous is None else {row['pid']: row for row in previous['processes']}
    _require(all(pid not in seen or seen[pid] == before_identity[pid] for pid in gpu_pids),
             'observed GPU PID reused or changed identity')
    new = [before_identity[pid] for pid in sorted(gpu_pids - set(seen))]
    seen.update({row['pid']: row for row in new})
    _require(len(seen) <= 256, 'observed GPU process count exceeds bound')
    observations = [] if previous is None else list(previous['observations'])
    if new: observations.append({'processes':new, 'sample':sample})
    return {'operation': dict(operation), 'gpuUuid': gpu_uuid, 'group': group,
            'processes': [seen[pid] for pid in sorted(seen)], 'observations': observations,
            'bootId':boot, '_tree':tree, 'driver':driver}


def observe_release(tree, executable, running, expected_operation, expected_gpu_uuid):
    """Accept only a waited child, empty task group and absent target GPU PIDs."""
    sample = None
    try:
        _require(type(running) is dict and running.get('_tree') is tree and running.get('bootId') == _boot_identity()
                 and running.get('operation') == expected_operation
                 and running.get('gpuUuid') == expected_gpu_uuid and type(running.get('operation')) is dict
                 and type(running.get('driver')) is dict and isinstance(running['driver'].get('sha256'), str)
                 and _executable_identity(executable, running['driver']['sha256']) == running['driver']
                 and set(running['operation']) == {'operationId','bindingSha256','profileSha256'}
                 and isinstance(running['operation']['operationId'], str)
                 and re.fullmatch('[a-f0-9]{32}', running['operation']['operationId'])
                 and isinstance(running['operation']['bindingSha256'], str)
                 and re.fullmatch('[a-f0-9]{64}', running['operation']['bindingSha256'])
                 and isinstance(running['operation']['profileSha256'], str)
                 and re.fullmatch('[a-f0-9]{64}', running['operation']['profileSha256'])
                 and type(running.get('processes')) is list and bool(running['processes'])
                 and all(type(row) is dict and set(row) == {'pid','startTicks','pidNamespace'}
                         and type(row['pid']) is int and row['pid'] > 0
                         and type(row['startTicks']) is int and row['startTicks'] > 0
                         and isinstance(row['pidNamespace'], str) and row['pidNamespace']
                         for row in running['processes'])
                 and len({row['pid'] for row in running['processes']}) == len(running['processes'])
                 and type(running.get('observations')) is list and bool(running['observations'])
                 and len(running['observations']) <= 256
                 and all(type(entry) is dict and set(entry) == {'processes','sample'}
                         and type(entry['processes']) is list and bool(entry['processes'])
                         and type(entry['sample']) is dict
                         and _parse_driver_output(entry['sample']['devicesRaw'],
                                                  entry['sample']['processesRaw'], expected_gpu_uuid) == entry['sample']
                         and all(row in running['processes']
                                 and row['pid'] in entry['sample']['processesMiB']
                                 and (entry['sample']['processesMiB'][row['pid']] is None
                                      or type(entry['sample']['processesMiB'][row['pid']]) is int
                                      and entry['sample']['processesMiB'][row['pid']] > 0)
                                 for row in entry['processes'])
                         for entry in running['observations'])
                 and {row['pid'] for entry in running['observations'] for row in entry['processes']}
                     == {row['pid'] for row in running['processes']}
                 and running['group'] == _group_identity(tree), 'task evidence or cgroup identity differs')
        _require(tree.active() == 0 and tree.process.poll() is not None, 'task subtree or child remains')
        sample = _driver_sample(executable, running['gpuUuid'], running['driver'])
        _require(not sample['processesMiB'], 'target GPU still has compute processes')
        _require(tree.active() == 0 and tree.process.poll() is not None
                 and running['group'] == _group_identity(tree), 'task state changed during driver query')
        # The worker separately checks the entire delegated Docker child after
        # closing this task group, before projecting its existing v1 receipt.
        return {'status':'local_observation_complete', 'terminalDriverSample':sample}
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        result = {'status':'release_unknown', 'reason':str(error), 'errorType':type(error).__name__}
        if sample is not None: result['terminalDriverSample'] = sample
        return result


def observe_single_idle_device(executable, executable_sha256, gpu_uuid, expected_driver=None):
    driver = _executable_identity(executable, executable_sha256)
    _require(expected_driver is None or driver == expected_driver, 'driver executable identity changed')
    sample = _driver_sample(executable, gpu_uuid, driver)
    _require([line.strip() for line in sample['devicesRaw'].splitlines() if line.strip()] == [gpu_uuid],
             'owned-tree release requires exactly the configured GPU to be visible')
    _require(not sample['processesMiB'], 'target GPU still reports compute processes')
    return driver


def observe_owned_tree_release(tree, executable, executable_sha256, gpu_uuid, expected_driver):
    """Check a waited task and target device without assuming WSL exposes GPU PIDs."""
    group = _group_identity(tree)
    _require(tree.active() == 0 and tree.process.poll() is not None, 'task subtree or child remains')
    observe_single_idle_device(executable, executable_sha256, gpu_uuid, expected_driver)
    _require(tree.active() == 0 and tree.process.poll() is not None
             and group == _group_identity(tree), 'task state changed during driver query')
