"""One counted, networkless Java version probe. This is not a runtime verifier.

The probe verifies a fixed set of runtime files, not the full runtime closure.
The shared lock only serializes cooperating daemon clients.
"""
import copy
from datetime import datetime
import http.client
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import time
from urllib.parse import quote

import container_admission as admission


COMMAND = ['-c', 'sleep 2; exec /runtime/jre/bin/java -version']
ENV = ['PATH=/usr/bin:/bin', 'HOME=/home/consense', 'LANG=C']
IMAGE_ENV = ENV[:2]
MOUNTS = {
    '/usr': '/srv/consense-docker/test-rootfs/usr',
    '/runtime/jre': '/srv/consense-rehearsal-20261029/toolchains/jre-runtime-stage-attempt1/rootfs/usr/lib/jvm/java-17-openjdk-amd64',
    '/etc/java-17-openjdk': '/srv/consense-rehearsal-20261029/toolchains/jre-runtime-stage-attempt1/rootfs/etc/java-17-openjdk',
    '/etc/ld.so.cache': '/srv/consense-docker/test-rootfs/etc/ld.so.cache',
}
STATE = Path('/mnt/d/R&D/ConSense/.local/container-rehearsal-20261029/parallel-state.json')
SCOPE = 'ticket-02 JRE version probe attempt1 only'
COMPILER_SCOPE = 'ticket-02 JRE compiler smoke attempt1 only'
H2_SYNTHETIC_SCOPE = 'ticket-02 H2 synthetic credential probe attempt1 only'
H2_DISK_BOOTSTRAP_SCOPE = 'ticket-02 H2 synthetic disk bootstrap attempt1 only'
RESUME_SCOPE = 'ticket-02 JRE existing probe resume attempt1 only'
RESUME_ID = 'eabb529b32fa9c65432b2fd91c92a3d07b2401cbbc2e65d23f8180e5a03dd033'
EXTERNAL_MANIFEST = STATE.parent / 'isolated-docker-01/external-runtime-e41f/external-before.json'
JRE_MANIFEST = Path('/srv/consense-rehearsal-20261029/evidence/local01/jre-stage-manifest-attempt1.json')
RESOURCE = Path('/srv/consense-rehearsal-20261029/evidence/local01/ticket02-dockerd-start-attempt4/resource-current.json')
MANIFEST_HASHES = ('7038803d437eca7a33bf98bbb21ebd107dd13b7bcef17edba1b7a21277923cbe',
                   '9fe8be55c80843c94533d7f6d201bc9e076256f27e1ac91a733901c1282e3a3f')
ROOT_IDENTITIES = {'/usr': (2112, 29372), '/etc/ld.so.cache': (2112, 28815),
                   '/runtime/jre': (2112, 56327), '/etc/java-17-openjdk': (2112, 56287)}
EXTERNAL_ENTRIES = ('usr/bin/sh', 'usr/bin/dash', 'usr/bin/sleep', 'usr/lib64/ld-linux-x86-64.so.2',
                    'usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2', 'usr/lib/x86_64-linux-gnu/libc.so.6',
                    'usr/lib/x86_64-linux-gnu/libm.so.6', 'usr/lib/x86_64-linux-gnu/libgcc_s.so.1',
                    'usr/lib/x86_64-linux-gnu/libstdc++.so.6', 'usr/lib/x86_64-linux-gnu/libstdc++.so.6.0.33',
                    'usr/lib/x86_64-linux-gnu/libz.so.1', 'usr/lib/x86_64-linux-gnu/libz.so.1.3', 'etc/ld.so.cache')
JRE_ENTRIES = ('bin/java', 'lib/modules', 'release', 'lib/libjli.so', 'lib/server/libjvm.so',
               'lib/libjava.so', 'lib/libzip.so', 'lib/libnet.so', 'lib/libnio.so', 'conf/security/java.security')


def _profile(profile, pin):
    admission.require(isinstance(profile, dict) and set(profile) == {'name', 'config'}
                      and isinstance(pin, str) and re.fullmatch('[a-f0-9]{64}', pin)
                      and admission._pin(profile) == pin, 'verified probe profile required')
    config = profile['config']
    admission.require(profile['name'] == 'consense-jre-probe-local01'
                      and isinstance(config, dict)
                      and set(config) == {'Image', 'User', 'Entrypoint', 'Cmd', 'Env', 'Labels', 'HostConfig'}
                      and isinstance(config['Image'], str) and re.fullmatch('sha256:[a-f0-9]{64}', config['Image'])
                      and config['User'] == 'consense:consense' and config['Entrypoint'] == ['/bin/sh']
                      and config['Cmd'] == COMMAND and config['Env'] == ENV
                      and config['Labels'] == {'consense.role': 'jre-probe', 'consense.deployment': 'r1-20261029'},
                      'invalid probe command or identity')
    host = config['HostConfig']
    admission.require(isinstance(host, dict) and set(host) == {
        'NetworkMode', 'ReadonlyRootfs', 'Privileged', 'CapDrop', 'SecurityOpt', 'Mounts', 'Memory', 'NanoCpus', 'PidsLimit'}
        and host['NetworkMode'] == 'none' and host['ReadonlyRootfs'] is True and host['Privileged'] is False
        and host['CapDrop'] == ['ALL'] and host['SecurityOpt'] == ['no-new-privileges:true']
        and all(type(host[k]) is int and 0 < host[k] <= limit for k, limit in
                (('Memory', 536870912), ('NanoCpus', 1000000000), ('PidsLimit', 64))), 'unsafe probe limits')
    mounts = admission._mounts(config)
    admission.require(len(mounts) == len(MOUNTS) and {row['Target'] for row in mounts} == set(MOUNTS)
                      and all(row['ReadOnly'] and MOUNTS.get(row['Target']) == row['Source']
                      for row in mounts), 'probe mounts differ')


def _inspect(result, profile, cid, state):
    actual = result.get('State') if isinstance(result, dict) else None
    admission.require(isinstance(actual, dict) and actual.get('Status') == state
                      and actual.get('Running') is (state == 'running')
                      and all(actual.get(k) is False for k in ('Paused', 'Restarting', 'Dead')),
                      'probe state differs')
    if state == 'exited':
        admission.require(actual.get('Pid') == 0 and type(actual.get('ExitCode')) is int
                          and actual.get('OOMKilled') is False and actual.get('Error') == '', 'missing exit evidence')
        # Reuse ordinary admission's exact configuration and mount checks.
        normalized = copy.deepcopy(result)
        normalized['State']['Status'] = 'created'
        admission._inspect(normalized, profile, cid, False)
    else:
        admission._inspect(result, profile, cid, state == 'running')
    host, cfg = result['HostConfig'], result['Config']
    bindings, exposed = host.get('PortBindings'), cfg.get('ExposedPorts')
    admission.require('PortBindings' in host and (bindings is None or isinstance(bindings, dict) and not bindings)
                      and host.get('PublishAllPorts') is False
                      and (exposed is None or isinstance(exposed, dict) and not exposed), 'probe cannot expose ports')
    return actual


def _timestamp(value):
    admission.require(isinstance(value, str) and re.fullmatch(
        r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', value), 'invalid Docker timestamp')
    try:
        parsed = datetime.fromisoformat(value[:-1] + '+00:00')
        admission.require(parsed.year > 1, 'unset Docker timestamp')
        return parsed
    except ValueError as error:
        raise admission.AdmissionError('invalid Docker timestamp') from error


def _image(profile, daemon):
    image = profile['config']['Image']
    result = admission._request('GET', '/images/' + quote(image, safe=':') + '/json', daemon)
    cfg = result.get('Config') if isinstance(result, dict) else None
    admission.require(isinstance(result, dict) and result.get('Id') == image and isinstance(cfg, dict)
                      and cfg.get('User') == 'consense:consense' and cfg.get('Env') == IMAGE_ENV
                      and cfg.get('WorkingDir') == '/home/consense' and not cfg.get('Volumes')
                      and not cfg.get('ExposedPorts'), 'probe image metadata differs')


def _starttime(pid):
    raw = (Path('/proc') / str(pid) / 'stat').read_text()
    fields = raw[raw.rfind(')') + 2:].split()
    admission.require(len(fields) > 19 and fields[19].isdigit(), 'process identity unavailable')
    return int(fields[19])


def _logs(cid, daemon):
    client = admission._daemon_peer(daemon)
    connection = http.client.HTTPConnection('localhost', timeout=5)
    connection.sock = client
    try:
        connection.request('GET', '/containers/' + cid + '/logs?stdout=1&stderr=1')
        response = connection.getresponse()
        data = response.read(65537)
        admission.require(response.status == 200 and len(data) <= 65536, 'probe logs unavailable or oversized')
    finally:
        connection.close()
    output = bytearray()
    while data:
        admission.require(len(data) >= 8 and data[0] in (1, 2) and data[1:4] == b'\0\0\0', 'invalid log frame')
        size = struct.unpack('>I', data[4:8])[0]
        admission.require(size <= len(data) - 8, 'truncated log frame')
        output.extend(data[8:8 + size])
        data = data[8 + size:]
    return bytes(output).decode('utf-8', errors='strict')


def _check_inventory(before, cid, known, daemon):
    rows = admission._request('GET', '/containers/json?all=1', daemon)
    admission.require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows)
                      and sorted(row.get('Id', '') for row in rows) == sorted(before + [cid]),
                      'container inventory changed')
    for item in known.values():
        admission._inspect(admission._request('GET', '/containers/' + item['id'] + '/json', daemon),
                           item['profile'], item['id'], True)


def _identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid,
            info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_nlink)


def _read_manifest(path, digest, private=False):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        admission.require(stat.S_ISREG(before.st_mode) and before.st_size <= 2 * 1024 * 1024, 'unsafe manifest')
        admission.require(not private or (before.st_uid == 0 and stat.S_IMODE(before.st_mode) == 0o600),
                          'unsafe private resource record')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            data = stream.read(2 * 1024 * 1024 + 1)
        admission.require(len(data) == before.st_size and (digest is None or hashlib.sha256(data).hexdigest() == digest)
                          and _identity(os.fstat(fd)) == _identity(before)
                          and _identity(os.stat(path, follow_symlinks=False)) == _identity(before),
                          'manifest identity differs')
        return json.loads(data, object_pairs_hook=_unique)
    finally:
        os.close(fd)


def _safe_parent(path):
    for parent in (path.parent, *path.parent.parents):
        info = parent.lstat()
        admission.require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
                          'untrusted runtime ancestor')


def _verify_entry(path, row, started, bytes_read, hash_contents=True, scope=SCOPE):
    admission.require(scope in (SCOPE, COMPILER_SCOPE, H2_SYNTHETIC_SCOPE, H2_DISK_BOOTSTRAP_SCOPE), 'unsupported runtime scope')
    _safe_parent(path)
    before = path.lstat()
    admission.require(before.st_uid == 0, 'runtime owner differs')
    if row[0] == 'symlink':
        admission.require(stat.S_ISLNK(before.st_mode) and os.readlink(path) == row[1]
                          and _identity(path.lstat()) == _identity(before), 'runtime link differs')
        return bytes_read
    admission.require(stat.S_ISREG(before.st_mode) and not before.st_mode & 0o022
                      and before.st_size == row[1], 'runtime file metadata differs')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        admission.require(_identity(os.fstat(fd)) == _identity(before), 'runtime file changed')
        if not hash_contents:
            admission.require(_identity(path.lstat()) == _identity(before), 'runtime file changed')
            return bytes_read
        limit = 192 * 1024 * 1024
        admission.require(type(bytes_read) is int and 0 <= bytes_read <= limit
                          and type(row[1]) is int and 0 <= row[1] <= limit - bytes_read,
                          'runtime read budget exceeded')
        digest, count = hashlib.sha256(), 0
        while count < row[1]:
            remaining = min(1024 * 1024, row[1] - count, limit - bytes_read)
            block = os.read(fd, remaining)
            admission.require(0 < len(block) <= remaining, 'runtime bytes changed')
            previous = bytes_read
            digest.update(block); count += len(block); bytes_read += len(block)
            if bytes_read // (32 * 1024 * 1024) != previous // (32 * 1024 * 1024):
                _verify_scope(scope)
            pause = bytes_read / (4 * 1024 * 1024) - (time.monotonic() - started)
            if pause > 0:
                time.sleep(pause)
        admission.require(count == row[1] and digest.hexdigest() == row[2]
                          and _identity(os.fstat(fd)) == _identity(before)
                          and _identity(path.lstat()) == _identity(before), 'runtime bytes changed')
    finally:
        os.close(fd)
    return bytes_read


def _verify_runtime(profile, hash_contents=True, scope=SCOPE):
    admission.require(scope in (SCOPE, COMPILER_SCOPE, H2_SYNTHETIC_SCOPE, H2_DISK_BOOTSTRAP_SCOPE), 'unsupported runtime scope')
    external = _read_manifest(EXTERNAL_MANIFEST, MANIFEST_HASHES[0])
    jre = _read_manifest(JRE_MANIFEST, MANIFEST_HASHES[1])
    admission.require(isinstance(external, list) and isinstance(jre, dict)
                      and jre.get('status') == 'inventory-only', 'runtime manifests unavailable')
    for target, source in MOUNTS.items():
        path = Path(source); _safe_parent(path); info = path.lstat()
        admission.require((info.st_dev, info.st_ino) == ROOT_IDENTITIES[target] and info.st_uid == 0
                          and not info.st_mode & 0o022, 'runtime mount identity differs')
    external_rows = {row[0]: row for row in external if isinstance(row, list) and len(row) == 3}
    jre_rows = {row['path']: row for row in jre['entries']}
    admission.require(len(external_rows) == len(external) and len(jre_rows) == len(jre['entries']),
                      'duplicate runtime manifest entry')
    root = Path('/srv/consense-docker/test-rootfs')
    stage = Path('/srv/consense-rehearsal-20261029/toolchains/jre-runtime-stage-attempt1/rootfs')
    selected = [(root / name, external_rows[name]) for name in EXTERNAL_ENTRIES]
    selected += [(stage / 'usr/lib/jvm/java-17-openjdk-amd64' / name,
                  jre_rows['usr/lib/jvm/java-17-openjdk-amd64/' + name]) for name in JRE_ENTRIES]
    selected.append((stage / 'etc/java-17-openjdk/security/java.security', jre_rows['etc/java-17-openjdk/security/java.security']))
    started, count = time.monotonic(), 0
    for path, item in selected:
        if isinstance(item, list):
            row = ('symlink', item[2]) if item[1] == 'link' else ('file', item[1], item[2])
        else:
            row = ('symlink', item['target']) if item['type'] == 'symlink' else ('file', item['size'], item['sha256'])
        count = _verify_entry(path, row, started, count, hash_contents, scope)
    return {'bytesRead': count, 'scope': 'selected_version_probe_files', 'hashesRechecked': hash_contents,
            'fullClosureVerified': False}


def _unique(pairs):
    result = {}
    for key, value in pairs:
        admission.require(key not in result, 'duplicate scope field')
        result[key] = value
    return result


def _verify_resource(expected):
    snapshot = _read_manifest(RESOURCE, None, private=True)
    admission.require(isinstance(snapshot, dict) and type(snapshot.get('monotonicNs')) is int,
                      'resource sample unavailable or stale')
    boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    observed = snapshot.get('observation')
    age = time.monotonic_ns() - snapshot['monotonicNs']
    admission.require(snapshot.get('status') == 'ok' and snapshot.get('reason') is None
                      and snapshot.get('boot') == boot and type(age) is int and 0 <= age <= 10_000_000_000
                      and snapshot.get('daemon') == {'pid': expected['pid'], 'starttime': expected['startTicks'],
                                                    'exe': '/usr/bin/dockerd'}
                      and isinstance(observed, dict) and all(type(observed.get(k)) is int for k in
                          ('rssBytes', 'processCount', 'memAvailableBytes', 'ext4AvailableBytes', 'dAvailableBytes'))
                      and 0 <= observed['rssBytes'] < 2 * 1024**3 and 0 < observed['processCount'] < 64
                      and observed['memAvailableBytes'] >= 8 * 1024**3
                      and observed['ext4AvailableBytes'] >= 10 * 1024**3
                      and observed['dAvailableBytes'] >= 20 * 1024**3, 'resource sample unavailable or stale')


def _verify_scope(scope=SCOPE):
    fd = os.open(STATE, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        admission.require(stat.S_ISREG(before.st_mode) and before.st_size <= 65536, 'invalid scope record')
        with os.fdopen(fd, 'rb', closefd=False) as source:
            raw = source.read(65537)
        admission.require(len(raw) == before.st_size and _identity(os.fstat(fd)) == _identity(before)
                          and _identity(os.stat(STATE, follow_symlinks=False)) == _identity(before),
                          'scope record changed')
        state = json.loads(raw, object_pairs_hook=_unique)
    finally:
        os.close(fd)
    admission.require(state.get('ticket01Gate') == 'closed' and state.get('heavyTaskGate') == 'ordered-single-heavy-task'
                      and state.get('heavyTaskOwner') == 'ticket-02' and state.get('heavyTaskScope') == scope,
                      'probe scope unavailable')


def run(profile, profile_sha256, known_containers, expected_daemon, timeout=30):
    """Run one fixed probe; any failure retains its exact container when created.

    The observed process is the wrapper; it does not prove Java's process
    identity after exec. Full runtime closure and TLS are not established.
    """
    profile, known_containers, expected_daemon = copy.deepcopy((profile, known_containers, expected_daemon))
    admission.require(type(timeout) is int and 1 <= timeout <= 60, 'bounded timeout required')
    _profile(profile, profile_sha256)
    admission._daemon_pin(expected_daemon)
    with admission._lock():
        _verify_scope()
        _verify_resource(expected_daemon)
        info = admission._request('GET', '/info', expected_daemon)
        admission.require(isinstance(info, dict) and info.get('ID') == expected_daemon['id']
                          and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        before = admission._inventory(known_containers, expected_daemon)
        admission.require(len(before) < 5, 'container slot unavailable')
        _profile(profile, profile_sha256)
        _image(profile, expected_daemon)
        admission._verify_mount_isolation(list(MOUNTS.values()), [], expected_daemon)
        verified_runtime = _verify_runtime(profile)
        _verify_scope()
        _verify_resource(expected_daemon)
        admission._verify_mount_isolation(list(MOUNTS.values()), [], expected_daemon)
        try:
            created = admission._request('POST', '/containers/create?name=' + quote(profile['name'], safe=''),
                                         expected_daemon, profile['config'])
        except Exception as error:
            raise admission.AdmissionError('probe create outcome unknown; inspect exact name before retry') from error
        cid = created.get('Id') if isinstance(created, dict) else None
        admission.require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid) and cid not in before,
                          'invalid created identity; inspect exact name before retry')
        return _finish_created(profile, cid, before, known_containers, expected_daemon, timeout, verified_runtime, SCOPE)


def _finish_created(profile, cid, before, known_containers, expected_daemon, timeout, verified_runtime, scope):
    try:
        path = '/containers/' + cid
        _inspect(admission._request('GET', path + '/json', expected_daemon), profile, cid, 'created')
        _check_inventory(before, cid, known_containers, expected_daemon)
        deadline = time.monotonic() + timeout
        _verify_scope(scope)
        _verify_resource(expected_daemon)
        admission._verify_mount_isolation(list(MOUNTS.values()), [], expected_daemon)
        admission._request('POST', path + '/start', expected_daemon)
        admission.require(time.monotonic() < deadline, 'probe timed out during start')
        observed = None
        while True:
            row = admission._request('GET', path + '/json', expected_daemon)
            admission.require(time.monotonic() < deadline, 'probe timed out')
            status = row.get('State', {}).get('Status') if isinstance(row, dict) else None
            if status == 'running':
                state = _inspect(row, profile, cid, 'running')
                _timestamp(state.get('StartedAt'))
                pid = state['Pid']
                start = _starttime(pid)
                admission._process_identity(pid)
                admission.require(_starttime(pid) == start, 'probe process identity changed')
                if observed is None:
                    observed = {'pid': pid, 'starttime': start, 'startedAt': state['StartedAt']}
                admission.require(observed == {'pid': pid, 'starttime': start, 'startedAt': state['StartedAt']},
                                  'probe process changed')
            elif status == 'exited':
                state = _inspect(row, profile, cid, 'exited')
                admission.require(observed is not None and state['ExitCode'] == 0
                                  and state.get('StartedAt') == observed['startedAt']
                                  and _timestamp(state.get('FinishedAt')) >= _timestamp(state['StartedAt']),
                                  'probe did not produce observed successful exit')
                break
            else:
                raise admission.AdmissionError('unexpected probe state')
            time.sleep(0.1)
        output = _logs(cid, expected_daemon)
        admission.require(re.search(r'^openjdk version "17\.0\.20\.1"(?:\s|$)', output, re.MULTILINE),
                          'unexpected Java version output')
        _check_inventory(before, cid, known_containers, expected_daemon)
        terminal = _inspect(admission._request('GET', path + '/json', expected_daemon), profile, cid, 'exited')
        admission.require(all(terminal.get(k) == state.get(k) for k in ('ExitCode', 'StartedAt', 'FinishedAt', 'OOMKilled', 'Error')),
                          'probe exit evidence changed')
        _verify_scope(scope)
        admission._request('DELETE', path, expected_daemon)
        after = admission._inventory(known_containers, expected_daemon)
        admission.require(sorted(after) == sorted(before), 'container inventory changed after cleanup')
        return {'id': cid, 'wrapperProcess': observed, 'exitCode': state['ExitCode'],
                'finishedAt': state['FinishedAt'], 'output': output, 'removed': True,
                'runtimeFileCheck': verified_runtime}
    except Exception as error:
        raise admission.AdmissionError('probe ' + cid + ' retained or cleanup outcome unknown') from error


def resume_created(profile, profile_sha256, cid, expected_daemon, timeout=30):
    """Continue only the already-created ticket probe; file contents are not rehashed."""
    profile, expected_daemon = copy.deepcopy((profile, expected_daemon))
    admission.require(type(timeout) is int and 1 <= timeout <= 60 and cid == RESUME_ID,
                      'fixed existing probe identity and bounded timeout required')
    _profile(profile, profile_sha256)
    admission._daemon_pin(expected_daemon)
    with admission._lock():
        _verify_scope(RESUME_SCOPE)
        _verify_resource(expected_daemon)
        info = admission._request('GET', '/info', expected_daemon)
        admission.require(isinstance(info, dict) and info.get('ID') == expected_daemon['id']
                          and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        _check_inventory([], cid, {}, expected_daemon)
        _image(profile, expected_daemon)
        admission._verify_mount_isolation(list(MOUNTS.values()), [], expected_daemon)
        verified_runtime = _verify_runtime(profile, hash_contents=False)
        _verify_scope(RESUME_SCOPE)
        _verify_resource(expected_daemon)
        admission._verify_mount_isolation(list(MOUNTS.values()), [], expected_daemon)
        state = _inspect(admission._request('GET', '/containers/' + cid + '/json', expected_daemon),
                         profile, cid, 'created')
        admission.require(state.get('StartedAt') == '0001-01-01T00:00:00Z'
                          and state.get('FinishedAt') == '0001-01-01T00:00:00Z',
                          'existing probe has already started')
        return _finish_created(profile, cid, [], {}, expected_daemon, timeout, verified_runtime, RESUME_SCOPE)
