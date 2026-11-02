"""Attempt2 combined-scope Qdrant observation and exact-process stop candidate.

The runner supplies the same-scope start receipt, binding and observation result.
No operation is automatic, and an exception never retries or removes anything.
"""
import copy
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import select
import signal
import socket
import stat
import time

import container_admission as admission
import jre_probe_admission as jre
import qdrant_ca_attempt2_admission as qdrant

COMBINED_SCOPE = qdrant.COMBINED_SCOPE
RECEIPT_DIR = Path('/srv/consense-rehearsal-20261029/evidence/local01/qdrant-ca-combined-attempt2')
RECEIPT = RECEIPT_DIR / 'start.json'
CGROOT = Path('/sys/fs/cgroup')
MAX_ENTRIES = 256
MAX_TCP_BYTES = 256 * 1024
DAEMON = {'id': '0cd972de-c05e-4fb6-bc8c-d9afcf907935', 'socketDevice': 110, 'socketInode': 13,
          'pid': 13, 'startTicks': 2083903, 'uid': 0, 'gid': 0}


def _scope(scope):
    admission.require(scope == COMBINED_SCOPE, 'unsupported Qdrant observation scope')
    qdrant._scope(scope)


def _binding(binding):
    admission.require(isinstance(binding, dict) and set(binding) == {'id', 'pid', 'starttime', 'startedAt', 'boot', 'receiptSha256'}
                      and isinstance(binding['id'], str) and re.fullmatch('[0-9a-f]{64}', binding['id'])
                      and all(type(binding[k]) is int and binding[k] > 0 for k in ('pid', 'starttime'))
                      and binding['boot'] == qdrant.BOOT and isinstance(binding['receiptSha256'], str)
                      and re.fullmatch('[0-9a-f]{64}', binding['receiptSha256']), 'invalid start binding')
    jre._timestamp(binding['startedAt'])
    _verify_receipt(binding)


def _verify_receipt(binding):
    admission.require(isinstance(binding, dict) and isinstance(binding.get('receiptSha256'), str)
                      and re.fullmatch('[0-9a-f]{64}', binding['receiptSha256']), 'combined receipt pin unavailable')
    jre._safe_parent(RECEIPT_DIR)
    directory = RECEIPT_DIR.lstat()
    admission.require(stat.S_ISDIR(directory.st_mode) and directory.st_uid == directory.st_gid == 0
                      and stat.S_IMODE(directory.st_mode) == 0o700, 'combined receipt directory differs')
    fd = os.open(RECEIPT, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        admission.require(stat.S_ISREG(before.st_mode) and before.st_uid == before.st_gid == 0
                          and stat.S_IMODE(before.st_mode) == 0o600 and before.st_nlink == 1
                          and before.st_size <= 16384, 'combined start receipt unsafe')
        raw = os.read(fd, 16385)
        admission.require(len(raw) == before.st_size and jre._identity(before) == jre._identity(os.fstat(fd))
                          == jre._identity(RECEIPT.lstat()) and
                          hashlib.sha256(raw).hexdigest() == binding['receiptSha256'], 'combined receipt changed')
        receipt = json.loads(raw, object_pairs_hook=jre._unique)
    finally:
        os.close(fd)
    admission.require(isinstance(receipt, dict) and set(receipt) == {'scope', 'boot', 'daemon', 'result'}
                      and receipt['scope'] == COMBINED_SCOPE and receipt['boot'] == qdrant.BOOT
                      and receipt['daemon'] == DAEMON and isinstance(receipt['result'], dict)
                      and all(receipt['result'].get(k) == binding.get(k) for k in ('id', 'pid', 'starttime', 'startedAt')),
                      'combined start receipt binding differs')


def _request(path, daemon):
    client = admission._daemon_peer(daemon)
    connection = http.client.HTTPConnection('localhost', timeout=5)
    connection.sock = client
    try:
        connection.request('GET', path)
        response = connection.getresponse()
        raw = response.read(65537)
        admission.require(response.status == 200 and len(raw) <= 65536, 'Engine response unavailable or oversized')
        return json.loads(raw, object_pairs_hook=jre._unique)
    finally:
        connection.close()


def _identity(binding, daemon, require_listener=True):
    _binding(binding)
    admission.require(daemon == DAEMON, 'Qdrant daemon pin differs')
    admission._daemon_pin(daemon)
    info = _request('/info', daemon)
    admission.require(isinstance(info, dict) and info.get('ID') == daemon['id']
                      and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
    profile = qdrant.fixed_profile()
    admission.require(admission._pin(profile) == qdrant.PROFILE_SHA, 'Qdrant profile differs')
    admission._profile(profile, qdrant.PROFILE_SHA)
    qdrant._inventory(daemon, binding['id'])
    result = _request('/containers/' + binding['id'] + '/json', daemon)
    state = qdrant._inspect_instance(result, profile, binding['id'], 'running')
    admission.require(state['Pid'] == binding['pid'] and state.get('StartedAt') == binding['startedAt']
                      and jre._starttime(binding['pid']) == binding['starttime'], 'Qdrant process changed')
    admission._process_identity(binding['pid'])
    qdrant._process_binary(binding['pid'])
    qdrant._network(daemon, binding['pid'], binding['starttime'])
    for path, inode in ((qdrant.ROOT + '/storage', 56790), (qdrant.ROOT + '/tmp', 56791)):
        info = os.lstat(path)
        admission.require(stat.S_ISDIR(info.st_mode) and (info.st_dev, info.st_ino, info.st_uid, info.st_gid,
                          stat.S_IMODE(info.st_mode)) == (2112, inode, 10001, 10001, 0o700), 'data root differs')
    if require_listener:
        _listener(binding['pid'])
    return result


def _pseudo(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        raw = os.read(fd, 4097)
        admission.require(len(raw) <= 4096, 'resource file oversized')
        return raw.decode('ascii').strip()
    finally:
        os.close(fd)


def _tcp_entries(pid):
    entries = []
    for family in ('tcp', 'tcp6'):
        fd = os.open('/proc/' + str(pid) + '/net/' + family, os.O_RDONLY | os.O_CLOEXEC)
        try:
            raw = bytearray()
            while len(raw) <= MAX_TCP_BYTES:
                block = os.read(fd, min(16384, MAX_TCP_BYTES + 1 - len(raw)))
                if not block:
                    break
                raw.extend(block)
            admission.require(len(raw) <= MAX_TCP_BYTES, 'TCP table oversized')
        finally:
            os.close(fd)
        for line in raw.decode('ascii').splitlines()[1:]:
            row = line.split()
            admission.require(len(row) > 9, 'invalid TCP table')
            if row[3] == '0A' and int(row[1].split(':')[1], 16) == 28683:
                entries.append((family, row[1].split(':')[0], row[9]))
    return entries


def _listener(pid):
    entries = _tcp_entries(pid)
    admission.require(len(entries) == 1 and entries[0][:2] == ('tcp', '0100007F'), 'Qdrant listener differs')
    found = False
    with os.scandir('/proc/' + str(pid) + '/fd') as fds:
        for number, fd in enumerate(fds, 1):
            admission.require(number <= 512, 'Qdrant fd inventory oversized')
            try:
                found = found or os.readlink(fd.path) == 'socket:[' + entries[0][2] + ']'
            except FileNotFoundError:
                pass
    admission.require(found, 'Qdrant listener not owned by PID')


def _cgroup(binding):
    raw = _pseudo('/proc/' + str(binding['pid']) + '/cgroup')
    lines = raw.splitlines()
    admission.require(len(lines) == 1 and lines[0].startswith('0::/'), 'cgroup v2 identity unavailable')
    rel = lines[0][4:]
    admission.require(rel and all(part not in ('', '.', '..') for part in rel.split('/')), 'invalid cgroup path')
    path = CGROOT / rel
    admission.require(os.path.realpath(path) == str(path), 'cgroup path contains links')
    before = os.stat(path, follow_symlinks=False)
    admission.require(stat.S_ISDIR(before.st_mode), 'cgroup missing')
    values = {name: _pseudo(path / name) for name in
              ('memory.current', 'memory.max', 'memory.events', 'pids.current', 'pids.max', 'cpu.max')}
    cpu = values['cpu.max'].split()
    admission.require(values['memory.max'] == '536870912' and values['pids.max'] == '64'
                      and values['memory.current'].isdigit() and values['pids.current'].isdigit()
                      and len(cpu) == 2 and all(part.isdigit() and int(part) > 0 for part in cpu)
                      and int(cpu[0]) == int(cpu[1]),
                      'cgroup resource limits differ')
    after = os.stat(path, follow_symlinks=False)
    admission.require((before.st_dev, before.st_ino) == (after.st_dev, after.st_ino)
                      and _pseudo('/proc/' + str(binding['pid']) + '/cgroup') == raw,
                      'cgroup changed')
    return {'device': before.st_dev, 'inode': before.st_ino, 'path': str(path), **values}


def _events(raw):
    result = {}
    for line in raw.splitlines():
        fields = line.split()
        admission.require(len(fields) == 2 and fields[0] not in result and fields[1].isdigit(),
                          'invalid memory events')
        result[fields[0]] = int(fields[1])
    admission.require('oom' in result and 'oom_kill' in result, 'memory events unavailable')
    return result


def _mount_id(fd):
    rows = [line.split() for line in _pseudo('/proc/self/fdinfo/' + str(fd)).splitlines()
            if line.startswith('mnt_id:')]
    admission.require(len(rows) == 1 and len(rows[0]) == 2 and rows[0][1].isdigit(), 'mount identity unavailable')
    return int(rows[0][1])


def _tree_size(path, expected_inode):
    """Bounded metadata-only observation; active writes can make the sum inconsistent."""
    root = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    pending, total, count = [root], 0, 0
    try:
        first = os.fstat(root)
        admission.require((first.st_dev, first.st_ino, first.st_uid, first.st_gid, stat.S_IMODE(first.st_mode)) ==
                          (2112, expected_inode, 10001, 10001, 0o700), 'data root differs')
        mount_id = _mount_id(root)
        while pending:
            fd = pending.pop()
            try:
                with os.scandir(fd) as entries:
                    for entry in entries:
                        count += 1
                        admission.require(count <= MAX_ENTRIES, 'data entry budget exceeded')
                        info = entry.stat(follow_symlinks=False)
                        admission.require(info.st_dev == 2112 and info.st_uid == 10001 and info.st_gid == 10001,
                                          'data entry ownership or device differs')
                        if stat.S_ISDIR(info.st_mode):
                            child = os.open(entry.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
                            try:
                                opened = os.fstat(child)
                                admission.require((opened.st_dev, opened.st_ino) == (info.st_dev, info.st_ino)
                                                  and _mount_id(child) == mount_id, 'data directory changed')
                                pending.append(child)
                            except BaseException:
                                os.close(child)
                                raise
                        else:
                            admission.require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, 'unsafe data entry')
                            child = os.open(entry.name, os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
                            try:
                                opened = os.fstat(child)
                                admission.require((opened.st_dev, opened.st_ino) == (info.st_dev, info.st_ino)
                                                  and _mount_id(child) == mount_id, 'data file identity differs')
                                total += info.st_size
                            finally:
                                os.close(child)
            finally:
                os.close(fd)
    finally:
        for fd in pending:
            os.close(fd)
    named = os.lstat(path)
    admission.require((named.st_dev, named.st_ino) == (first.st_dev, first.st_ino), 'data root path changed')
    return {'entries': count, 'logicalBytes': total}


def _log_size(result, binding):
    path = admission.DATA_ROOT + '/containers/' + binding['id'] + '/' + binding['id'] + '-json.log'
    host = result.get('HostConfig')
    admission.require(isinstance(host, dict) and isinstance(host.get('LogConfig'), dict)
                      and host['LogConfig'].get('Type') == 'json-file' and result.get('LogPath') == path,
                      'container log path or driver unavailable')
    p = Path(path)
    jre._safe_parent(p)
    fd = os.open(p, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        s = os.fstat(fd)
        admission.require(stat.S_ISREG(s.st_mode) and s.st_uid == 0 and s.st_nlink == 1
                          and jre._identity(s) == jre._identity(p.lstat()), 'container log identity differs')
        return {'device': s.st_dev, 'inode': s.st_ino, 'bytes': s.st_size}
    finally:
        os.close(fd)


def _http_version():
    deadline = time.monotonic() + 4
    with socket.create_connection(('127.0.0.1', 28683), timeout=2) as sock:
        sock.sendall(b'GET / HTTP/1.1\r\nHost: 127.0.0.1:28683\r\nConnection: close\r\n\r\n')
        data = bytearray()
        while b'\r\n\r\n' not in data:
            remaining = 16384 - len(data)
            admission.require(remaining > 0 and time.monotonic() < deadline, 'HTTP header budget or deadline')
            sock.settimeout(min(2, deadline - time.monotonic()))
            block = sock.recv(min(4096, remaining))
            admission.require(bool(block), 'HTTP truncated headers')
            data.extend(block)
        header, body = bytes(data).split(b'\r\n\r\n', 1)
        rows = header.split(b'\r\n')
        admission.require(rows[0].split(b' ', 2)[:2] == [b'HTTP/1.1', b'200'], 'HTTP status differs')
        headers = {}
        for line in rows[1:]:
            key, sep, value = line.partition(b':')
            key = key.strip().lower()
            admission.require(sep and key and key not in headers, 'invalid or duplicate HTTP header')
            headers[key] = value.strip()
        admission.require(b'transfer-encoding' not in headers and headers.get(b'content-length', b'').isdigit(),
                          'HTTP length unavailable')
        size = int(headers[b'content-length'])
        admission.require(size <= 32768 and len(body) <= size, 'HTTP body budget exceeded')
        while len(body) < size:
            admission.require(time.monotonic() < deadline, 'HTTP body deadline')
            sock.settimeout(min(2, deadline - time.monotonic()))
            block = sock.recv(min(4096, size - len(body)))
            admission.require(bool(block), 'HTTP body truncated')
            body += block
        data = json.loads(body, object_pairs_hook=jre._unique)
        admission.require(isinstance(data, dict) and data.get('version') == '1.19.2', 'Qdrant version differs')
        return {'status': 200, 'version': data['version'], 'bodyBytes': size}


def observe(binding, daemon, scope=COMBINED_SCOPE):
    """Observe at most 13 samples in 60 seconds; a threshold only reports alert."""
    binding, daemon = copy.deepcopy((binding, daemon))
    admission.require(scope == COMBINED_SCOPE, 'unsupported observation scope')
    _binding(binding)
    admission.require(daemon == DAEMON, 'Qdrant daemon pin differs')
    with admission._lock():
        _scope(scope)
        _identity(binding, daemon)
        jre._verify_resource(daemon)
        http = _http_version()
        _identity(binding, daemon)
        _emit({'event': 'qdrant_http_observation', **http})
        start = time.monotonic()
        samples, log_id, cgroup_id = [], None, None
        for number in range(13):
            _scope(scope)
            current = _identity(binding, daemon)
            jre._verify_resource(daemon)
            cg = _cgroup(binding)
            log = _log_size(current, binding)
            group_key, log_key = (cg['device'], cg['inode']), (log['device'], log['inode'])
            admission.require((cgroup_id is None or cgroup_id == group_key) and (log_id is None or log_id == log_key),
                              'cgroup or log rotated')
            cgroup_id, log_id = group_key, log_key
            storage = _tree_size(qdrant.ROOT + '/storage', 56790)
            tmp = _tree_size(qdrant.ROOT + '/tmp', 56791)
            sample = {'memoryBytes': int(cg['memory.current']), 'pids': int(cg['pids.current']),
                      'memoryEvents': cg['memory.events'], 'cpuMax': cg['cpu.max'], 'logBytes': log['bytes'],
                      'storage': storage, 'tmp': tmp, 'monotonicNs': time.monotonic_ns()}
            samples.append(sample)
            admission.require(time.monotonic() <= start + 65, 'observation deadline exceeded')
            _identity(binding, daemon)
            _scope(scope)
            _emit({'event': 'qdrant_resource_sample', 'sample': sample})
            events = _events(cg['memory.events'])
            alerts = []
            if sample['memoryBytes'] >= 384 * 1024**2: alerts.append('memory')
            if sample['pids'] >= 64: alerts.append('pids')
            if events['oom'] > 0 or events['oom_kill'] > 0: alerts.append('oom')
            if log['bytes'] >= 8 * 1024**2: alerts.append('log')
            if storage['logicalBytes'] >= 128 * 1024**2: alerts.append('storage')
            if alerts:
                return _bounded({'status': 'alert', 'http': http, 'sampleCount': len(samples), 'automaticStop': False,
                                 'alerts': alerts, 'continuousIsolationVerified': False, 'storageAccountingExact': False,
                                 'retainedGpuRelease': 'unknown'})
            if number < 12:
                delay = start + (number + 1) * 5 - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
        _identity(binding, daemon)
        _scope(scope)
        return _bounded({'status': 'observed', 'http': http, 'sampleCount': len(samples), 'automaticStop': False,
                         'continuousIsolationVerified': False, 'storageAccountingExact': False,
                         'retainedGpuRelease': 'unknown'})


def _emit(value):
    text = json.dumps(value)
    admission.require(len(text.encode()) <= 8192, 'sample output exceeds budget')
    print(text, flush=True)


def _bounded(value):
    admission.require(len(json.dumps(value).encode()) <= 128 * 1024, 'observation output exceeds budget')
    return value


def _no_listener():
    admission.require(not _tcp_entries('self'), 'Qdrant port remains in use')


def stop(binding, daemon, observation, scope=COMBINED_SCOPE):
    """One SIGTERM to the bound pidfd; no force, retry or removal."""
    binding, daemon = copy.deepcopy((binding, daemon))
    admission.require(scope == COMBINED_SCOPE, 'unsupported stop scope')
    admission.require(isinstance(observation, dict) and observation.get('status') in ('observed', 'alert')
                      and type(observation.get('sampleCount')) is int and 1 <= observation['sampleCount'] <= 13
                      and isinstance(observation.get('http'), dict)
                      and observation['http'].get('status') == 200
                      and observation['http'].get('version') == '1.19.2', 'successful observation required')
    _binding(binding)
    admission.require(daemon == DAEMON, 'Qdrant daemon pin differs')
    with admission._lock():
        _scope(scope)
        inspected = _identity(binding, daemon, require_listener=False)
        restart = inspected['HostConfig'].get('RestartPolicy')
        admission.require(isinstance(restart, dict) and restart.get('Name') in ('', 'no'), 'restart policy unsafe')
        pidfd = os.pidfd_open(binding['pid'], 0)
        try:
            poller = select.poll()
            poller.register(pidfd, select.POLLIN)
            admission.require(not poller.poll(0), 'Qdrant process already exited')
            _identity(binding, daemon, require_listener=False)
            _scope(scope)
            print(json.dumps({'event': 'qdrant_stop_intent', 'id': binding['id'], 'pid': binding['pid']}), flush=True)
            signal.pidfd_send_signal(pidfd, signal.SIGTERM, None, 0)
            print(json.dumps({'event': 'qdrant_stop_signal_submitted', 'id': binding['id']}), flush=True)
            events = poller.poll(20000)
            admission.require(len(events) == 1 and events[0][0] == pidfd and events[0][1] & select.POLLIN
                              and not events[0][1] & (select.POLLERR | select.POLLNVAL), 'Qdrant exit not observed')
            result = _request('/containers/' + binding['id'] + '/json', daemon)
            state = qdrant._inspect_instance(result, qdrant.fixed_profile(), binding['id'], 'exited')
            admission.require(state.get('StartedAt') == binding['startedAt'] and state['ExitCode'] == 0
                              and jre._timestamp(state.get('FinishedAt')) >= jre._timestamp(binding['startedAt']),
                              'Qdrant terminal evidence differs')
            qdrant._network(daemon)
            _no_listener()
            qdrant._inventory(daemon, binding['id'])
            again = qdrant._inspect_instance(_request('/containers/' + binding['id'] + '/json', daemon),
                                             qdrant.fixed_profile(), binding['id'], 'exited')
            admission.require(all(again.get(k) == state.get(k) for k in
                                  ('StartedAt', 'FinishedAt', 'ExitCode', 'OOMKilled', 'Error')),
                              'Qdrant terminal evidence changed')
            _scope(scope)
            return {'id': binding['id'], 'status': 'exited', 'exitCode': 0,
                    'listenerReleasedVerified': True, 'descendantsReleasedVerified': False, 'removed': False,
                    'retainedGpuRelease': 'unknown'}
        finally:
            os.close(pidfd)
