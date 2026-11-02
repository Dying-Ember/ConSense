"""Single scoped H2 listener observation; never sends a database request."""
import hashlib
import json
import os
from pathlib import Path
import re
import select
import signal
import stat
import time
from urllib.parse import quote

import container_admission as admission
import h2_synthetic_disk_admission as disk
import h2_tcp_combined_admission as h2
import jre_probe_admission as jre
import h2_listener_observation as observation

SCOPE = 'ticket-02 H2 listener diagnostic attempt1 only'
FAILED = 'a1ab08dbc43b89647518f2a2bda554ff698a78cd7ddd6d87377b8b1c28e6050d'
FAILED_STARTED = '2026-10-29T21:22:45.577853389Z'
FAILED_FINISHED = '2026-10-29T21:22:45.87023124Z'
DAEMON = {'id': '0cd972de-c05e-4fb6-bc8c-d9afcf907935', 'socketDevice': 110,
          'socketInode': 13, 'pid': 13, 'startTicks': 2083903, 'uid': 0, 'gid': 0}
TMPFS = {'/runtime/probe': 'rw,nosuid,nodev,noexec,size=16m,uid=10001,gid=10001,mode=0700'}
PROFILE = {
    'role': 'h2', 'name': 'consense-h2-listener-diagnostic-local01', 'ports': [28682],
    'config': {'Image': disk.IMAGE, 'User': 'consense:consense',
               'Entrypoint': ['/runtime/jre/bin/java'],
               'Cmd': ['-Xmx256m', '-Dh2.bindAddress=127.0.0.1', '-Djava.io.tmpdir=/runtime/probe',
                       '-cp', '/runtime/probe-input/h2.jar', 'org.h2.tools.Server', '-tcp',
                       '-tcpPort', '28682', '-ifExists', '-baseDir', '/runtime/probe'],
               'Env': list(jre.ENV),
               'Labels': {'consense.role': 'h2-listener-diagnostic', 'consense.deployment': 'r1-20261029'},
               'HostConfig': {'NetworkMode': 'host', 'ReadonlyRootfs': True, 'Privileged': False,
                              'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'],
                              'Mounts': ([{'Type': 'bind', 'Source': source, 'Target': target, 'ReadOnly': True}
                                          for target, source in jre.MOUNTS.items()] +
                                         [{'Type': 'bind', 'Source': h2.JAR, 'Target': '/runtime/probe-input/h2.jar',
                                           'ReadOnly': True}]),
                              'Memory': 536870912, 'NanoCpus': 500000000, 'PidsLimit': 32, 'Tmpfs': TMPFS,
                              'LogConfig': {'Type': 'json-file', 'Config': {'max-size': '1m', 'max-file': '1'}}}}}
EVIDENCE_PARENT = Path('/srv/consense-rehearsal-20261029/evidence/local01')
EVIDENCE_NAME = 'h2-listener-diagnostic-attempt1'
DIRFLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC


def scope():
    jre._verify_scope(SCOPE)


def inventory(owned=()):
    h2._inventory(DAEMON, [FAILED, *owned])
    row = admission._request('GET', '/containers/' + FAILED + '/json', DAEMON)
    state = jre._inspect(row, h2.PROFILE, FAILED, 'exited')
    admission.require(state['ExitCode'] == 143 and state['StartedAt'] == FAILED_STARTED
                      and state['FinishedAt'] == FAILED_FINISHED
                      and row['HostConfig'].get('Tmpfs') in (None, {})
                      and row['HostConfig'].get('AutoRemove') is False
                      and row['HostConfig'].get('RestartPolicy') == {'Name': 'no', 'MaximumRetryCount': 0},
                      'retained H2 terminal identity differs')


def _entry(path, row, started, total, identities, scope_id=None):
    jre._safe_parent(path)
    before = path.lstat()
    admission.require(before.st_uid == 0, 'runtime input owner differs')
    if row[0] == 'symlink':
        admission.require(stat.S_ISLNK(before.st_mode) and os.readlink(path) == row[1]
                          and jre._identity(path.lstat()) == jre._identity(before), 'runtime link differs')
    else:
        size = row[1]
        admission.require(type(size) is int and 0 <= size <= h2.MAX_READ - total
                          and stat.S_ISREG(before.st_mode) and not before.st_mode & 0o022
                          and before.st_size == size, 'runtime file differs')
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            admission.require(jre._identity(before) == jre._identity(os.fstat(fd)), 'runtime file changed')
            digest, done = hashlib.sha256(), 0
            while done < size:
                block = os.read(fd, min(1024 * 1024, size - done))
                admission.require(block, 'runtime short read')
                previous = total
                done += len(block); total += len(block); digest.update(block)
                if total // (32 * 1024 * 1024) != previous // (32 * 1024 * 1024):
                    scope() if scope_id is None else jre._verify_scope(scope_id)
                delay = total / h2.RATE - (time.monotonic() - started)
                if delay > 0:
                    time.sleep(delay)
            admission.require(digest.hexdigest() == row[2] and jre._identity(before) ==
                              jre._identity(os.fstat(fd)) == jre._identity(path.lstat()), 'runtime bytes changed')
        finally:
            os.close(fd)
    identities[str(path)] = list(jre._identity(before))
    return total


def runtime(scope_id=None):
    external = jre._read_manifest(jre.EXTERNAL_MANIFEST, jre.MANIFEST_HASHES[0])
    manifest = jre._read_manifest(jre.JRE_MANIFEST, jre.MANIFEST_HASHES[1])
    admission.require(isinstance(external, list) and isinstance(manifest, dict)
                      and manifest.get('status') == 'inventory-only', 'runtime manifests unavailable')
    for target, source in jre.MOUNTS.items():
        path = Path(source); jre._safe_parent(path); info = path.lstat()
        admission.require((info.st_dev, info.st_ino) == jre.ROOT_IDENTITIES[target] and info.st_uid == 0
                          and not info.st_mode & 0o022, 'runtime mount identity differs')
    ext = {row[0]: row for row in external if isinstance(row, list) and len(row) == 3}
    entries = {row['path']: row for row in manifest['entries']}
    admission.require(len(ext) == len(external) and len(entries) == len(manifest['entries']), 'duplicate runtime entry')
    root = Path('/srv/consense-docker/test-rootfs')
    stage = Path('/srv/consense-rehearsal-20261029/toolchains/jre-runtime-stage-attempt1/rootfs')
    selected = [(root / name, ext[name]) for name in jre.EXTERNAL_ENTRIES]
    selected += [(stage / 'usr/lib/jvm/java-17-openjdk-amd64' / name,
                  entries['usr/lib/jvm/java-17-openjdk-amd64/' + name]) for name in jre.JRE_ENTRIES]
    selected.append((stage / 'etc/java-17-openjdk/security/java.security', entries['etc/java-17-openjdk/security/java.security']))
    started, total, identities = time.monotonic(), 0, {}
    for path, item in selected:
        row = (('symlink', item[2]) if item[1] == 'link' else ('file', item[1], item[2])) if isinstance(item, list) else (
            ('symlink', item['target']) if item['type'] == 'symlink' else ('file', item['size'], item['sha256']))
        total = _entry(path, row, started, total, identities, scope_id)
    jar = Path(h2.JAR); info = jar.lstat()
    admission.require((info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_nlink,
                       stat.S_IMODE(info.st_mode)) == (2112, 56137, 0, 0, 1, 0o444), 'H2 jar identity differs')
    total = _entry(jar, ('file', 2543012, 'd623cdc0f61d218cf549a8d09f1c391ff91096116b22e2475475fce4fbe72bd0'),
                   started, total, identities, scope_id)
    return {'bytesRead': total, 'identities': identities, 'fullClosureVerified': False}


def recheck_inputs(result):
    for target, source in jre.MOUNTS.items():
        path = Path(source); jre._safe_parent(path); info = path.lstat()
        admission.require((info.st_dev, info.st_ino) == jre.ROOT_IDENTITIES[target] and info.st_uid == 0
                          and not info.st_mode & 0o022, 'runtime mount changed')
    for name, expected in result['identities'].items():
        path = Path(name); jre._safe_parent(path)
        admission.require(list(jre._identity(path.lstat())) == expected, 'runtime input changed')


def make_evidence(name=EVIDENCE_NAME):
    jre._safe_parent(EVIDENCE_PARENT)
    before = EVIDENCE_PARENT.lstat()
    admission.require((before.st_dev, before.st_ino, before.st_uid, before.st_gid, stat.S_IMODE(before.st_mode))
                      == (2112, 55843, 0, 0, 0o755), 'evidence parent differs')
    parent = os.open(EVIDENCE_PARENT, DIRFLAGS)
    try:
        admission.require(jre._identity(os.fstat(parent)) == jre._identity(before), 'evidence parent changed')
        admission.require(isinstance(name, str) and re.fullmatch('[a-z0-9][a-z0-9-]{0,63}', name), 'evidence name differs')
        os.mkdir(name, 0o700, dir_fd=parent)
        os.fsync(parent)
        folder = os.open(name, DIRFLAGS, dir_fd=parent)
        info = os.fstat(folder)
        admission.require((info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)) == (0, 0, 0o700),
                          'evidence directory differs')
        return folder
    finally:
        os.close(parent)


def record(folder, name, obj):
    raw = (json.dumps(obj, sort_keys=True, separators=(',', ':')) + '\n').encode()
    admission.require(len(raw) <= 512 * 1024, 'diagnostic receipt exceeds limit')
    fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600, dir_fd=folder)
    try:
        view = memoryview(raw)
        while view:
            n = os.write(fd, view)
            admission.require(n > 0, 'receipt write stalled')
            view = view[n:]
        os.fsync(fd); written = os.fstat(fd)
    finally:
        os.close(fd)
    os.fsync(folder)
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=folder)
    try:
        actual, info = os.read(fd, len(raw) + 1), os.fstat(fd)
        admission.require(actual == raw and info.st_size == len(raw) and info.st_uid == info.st_gid == 0
                          and info.st_nlink == 1 and stat.S_IMODE(info.st_mode) == 0o600
                          and jre._identity(info) == jre._identity(written)
                          == jre._identity(os.stat(name, dir_fd=folder, follow_symlinks=False)), 'receipt readback differs')
    finally:
        os.close(fd)
    return hashlib.sha256(raw).hexdigest()


def inspect(row, cid, state, profile=None):
    result = jre._inspect(row, PROFILE if profile is None else profile, cid, state)
    host = row['HostConfig']
    admission.require(host.get('Tmpfs') == TMPFS and host.get('AutoRemove') is False
                      and host.get('RestartPolicy') == {'Name': 'no', 'MaximumRetryCount': 0},
                      'diagnostic retention or tmpfs profile differs')
    return result


def verdict(sample):
    if sample.get('status') != 'observed':
        return 'unknown'
    rows = sample.get('listeners')
    if not isinstance(rows, list):
        return 'unknown'
    if not rows:
        return 'no_listener'
    if (len(rows) == 1 and rows[0].get('family') == 'tcp' and rows[0].get('address') == '0100007F'
            and rows[0].get('ownership') == 'owned'):
        return 'strict_ipv4_loopback_owned'
    return 'rejected'


def bound(cid, profile=None):
    row = admission._request('GET', '/containers/' + cid + '/json', DAEMON)
    state = inspect(row, cid, 'running') if profile is None else inspect(row, cid, 'running', profile)
    pid, started = state['Pid'], state['StartedAt']
    jre._timestamp(started); start = jre._starttime(pid)
    h2._process_identity(pid)
    exe = os.stat('/proc/' + str(pid) + '/exe')
    admission.require((exe.st_dev, exe.st_ino) == (2112, 56329), 'diagnostic Java executable differs')
    netns = h2._network(DAEMON, pid, start)
    admission.require(jre._starttime(pid) == start, 'diagnostic process changed')
    return {'id': cid, 'pid': pid, 'starttime': start, 'startedAt': started, 'networkNamespace': netns}


def verify_tmpfs(binding):
    pid = binding['pid']
    path = Path('/proc') / str(pid) / 'root/runtime/probe'
    flags = os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open(path, flags)
    try:
        info = os.fstat(fd)
        admission.require(stat.S_ISDIR(info.st_mode) and info.st_uid == info.st_gid == 10001
                          and stat.S_IMODE(info.st_mode) == 0o700, 'diagnostic tmpfs identity differs')
        fdinfo = (Path('/proc/self/fdinfo') / str(fd)).read_text().splitlines()
        ids = [line.split(':', 1)[1].strip() for line in fdinfo if line.startswith('mnt_id:')]
        admission.require(len(ids) == 1 and ids[0].isdigit(), 'diagnostic tmpfs mount ID unavailable')
        mountinfo = Path('/proc') / str(pid) / 'mountinfo'
        with mountinfo.open('rb') as stream:
            raw = stream.read(1024 * 1024 + 1)
        admission.require(len(raw) <= 1024 * 1024 and raw.endswith(b'\n'), 'diagnostic mountinfo oversized')
        table = admission._parse_mountinfo(raw)
        mid = int(ids[0]); record = table.get(mid)
        admission.require(record is not None and record['point'] == '/runtime/probe'
                          and record['filesystem'] == 'tmpfs'
                          and record['device'] == str(os.major(info.st_dev)) + ':' + str(os.minor(info.st_dev)),
                          'diagnostic tmpfs mount differs')
        lines = [line for line in os.fsdecode(raw).splitlines() if line.split(' ', 1)[0] == ids[0]]
        admission.require(len(lines) == 1 and {'rw', 'nosuid', 'nodev', 'noexec'} <= set(lines[0].split()[5].split(',')),
                          'diagnostic tmpfs flags differ')
        filesystem = os.fstatvfs(fd)
        admission.require(filesystem.f_blocks * filesystem.f_frsize == 16 * 1024 * 1024,
                          'diagnostic tmpfs capacity differs')
        with mountinfo.open('rb') as stream:
            after = stream.read(1024 * 1024 + 1)
        admission.require(raw == after and jre._starttime(pid) == binding['starttime']
                          and jre._identity(os.fstat(fd)) == jre._identity(info), 'diagnostic tmpfs changed')
        return {'mountId': mid, 'device': info.st_dev, 'inode': info.st_ino, 'capacityBytes': 16 * 1024 * 1024}
    finally:
        os.close(fd)


def stop(binding, attempted, timeout=20, *, guard=None, profile=None):
    key = (binding['pid'], binding['starttime'])
    admission.require(key not in attempted, 'diagnostic stop already attempted')
    attempted.add(key)
    if guard is None:
        scope(); inventory([binding['id']])
    else:
        guard()
    current = bound(binding['id']) if profile is None else bound(binding['id'], profile)
    admission.require(current == binding, 'diagnostic process changed before stop')
    pidfd = os.pidfd_open(binding['pid'], 0)
    try:
        poller = select.poll(); poller.register(pidfd, select.POLLIN)
        admission.require(not poller.poll(0) and jre._starttime(binding['pid']) == binding['starttime'],
                          'diagnostic exited before signal')
        if guard is None:
            scope(); inventory([binding['id']])
        else:
            guard()
        print(json.dumps({'event': 'diagnostic_stop_intent', 'binding': binding}), flush=True)
        signal.pidfd_send_signal(pidfd, signal.SIGTERM, None, 0)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if guard is None:
                scope(); inventory([binding['id']])
            else:
                guard()
            events = poller.poll(100)
            if events:
                admission.require(len(events) == 1 and events[0][0] == pidfd and events[0][1] & select.POLLIN
                                  and not events[0][1] & (select.POLLERR | select.POLLNVAL), 'pidfd event differs')
                row = admission._request('GET', '/containers/' + binding['id'] + '/json', DAEMON)
                if row.get('State', {}).get('Status') == 'exited':
                    final = inspect(row, binding['id'], 'exited') if profile is None else inspect(row, binding['id'], 'exited', profile)
                    admission.require(final['StartedAt'] == binding['startedAt'] and final['ExitCode'] in (0, 143)
                                      and jre._timestamp(final['FinishedAt']) >= jre._timestamp(final['StartedAt'])
                                      and not h2._port_entries('/proc/self'), 'diagnostic terminal differs')
                    return {'status': 'exited', 'exitCode': final['ExitCode'], 'finishedAt': final['FinishedAt']}
            time.sleep(.1)
        raise admission.AdmissionError('diagnostic stop deadline expired')
    finally:
        os.close(pidfd)


def run(profile_pin):
    admission.require(admission._pin(PROFILE) == profile_pin, 'diagnostic profile pin differs')
    admission._daemon_pin(DAEMON)
    admission.require(hasattr(os, 'pidfd_open') and hasattr(signal, 'pidfd_send_signal'), 'pidfd unavailable')
    with admission._lock():
        scope(); jre._verify_resource(DAEMON); h2._network(DAEMON); inventory()
        info = admission._request('GET', '/info', DAEMON)
        admission.require(isinstance(info, dict) and info.get('ID') == DAEMON['id']
                          and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        admission._mounts(PROFILE['config']); jre._image(PROFILE, DAEMON)
        protected = list(jre.MOUNTS.values()) + [h2.JAR] + disk.PROTECTED_DATA
        h2._qdrant_check(DAEMON, protected); fixture = h2._closed_fixture(); h2._ports_free()
        inputs = runtime()
        scope(); jre._verify_resource(DAEMON); recheck_inputs(inputs); h2._closed_fixture(fixture)
        h2._qdrant_check(DAEMON, protected); h2._network(DAEMON); h2._ports_free(); inventory()
        folder = make_evidence()
        try:
            scope(); jre._verify_resource(DAEMON)
            print(json.dumps({'event': 'diagnostic_create_intent', 'name': PROFILE['name']}), flush=True)
            created = admission._request('POST', '/containers/create?name=' + quote(PROFILE['name'], safe=''), DAEMON, PROFILE['config'])
            cid = created.get('Id') if isinstance(created, dict) else None
            admission.require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid)
                              and cid not in (FAILED, disk.GPU_ID, h2.QDRANT_ID), 'diagnostic creation unknown')
            print(json.dumps({'event': 'diagnostic_created', 'id': cid}), flush=True)
            binding, attempted = None, set()
            try:
                state = inspect(admission._request('GET', '/containers/' + cid + '/json', DAEMON), cid, 'created')
                admission.require(state['StartedAt'] == state['FinishedAt'] == '0001-01-01T00:00:00Z', 'diagnostic already started')
                scope(); inventory([cid]); recheck_inputs(inputs); h2._closed_fixture(fixture)
                h2._qdrant_check(DAEMON, protected); h2._network(DAEMON); h2._ports_free()
                print(json.dumps({'event': 'diagnostic_start_intent', 'id': cid}), flush=True)
                admission._request('POST', '/containers/' + cid + '/start', DAEMON)
                deadline = time.monotonic() + 60
                sample = {'status': 'unknown', 'listeners': [], 'reason': 'no observation before deadline'}
                tmpfs = None
                resource_sample = None
                for _ in range(30):
                    if time.monotonic() >= deadline:
                        break
                    scope(); jre._verify_resource(DAEMON); inventory([cid])
                    binding = bound(cid)
                    tmpfs = verify_tmpfs(binding)
                    resource_sample = h2._sample_server(binding['pid'])
                    sample = observation.capture(binding['pid'], binding['starttime'], h2.BOOT, binding['networkNamespace'])
                    if sample['status'] != 'observed' or sample['listeners']:
                        break
                    time.sleep(.5)
                admission.require(binding is not None and tmpfs is not None and resource_sample is not None,
                                  'diagnostic process was not observed')
                receipt = {'binding': binding, 'sample': sample, 'scope': SCOPE,
                           'verdict': verdict(sample),
                           'resourceSample': resource_sample, 'cgroupVerified': False,
                           'noSqlSentByRunner': True, 'continuousIsolationVerified': False,
                           'tmpfs': tmpfs, 'tmpfsRuntimeVerified': True,
                           'initialEmptyVerified': False, 'networkClientsExcluded': False}
                digest = record(folder, 'observation.json', receipt)
                print(json.dumps({'event': 'diagnostic_observed', 'receiptSha256': digest, 'sample': sample}), flush=True)
                final = stop(binding, attempted)
                binding = None
                h2._closed_fixture(fixture); h2._qdrant_check(DAEMON, protected); inventory([cid]); scope()
                end = record(folder, 'end.json', {'id': cid, 'observationSha256': digest, 'stop': final,
                                                  'verdict': verdict(sample),
                                                  'continuousIsolationVerified': False, 'removed': False})
                return {'id': cid, 'observationSha256': digest, 'endSha256': end, 'stop': final,
                        'verdict': verdict(sample), 'sqlSentByRunner': False, 'businessAccepted': False, 'removed': False}
            except Exception as error:
                if binding is not None and (binding['pid'], binding['starttime']) not in attempted:
                    try:
                        stop(binding, attempted)
                    except Exception:
                        print(json.dumps({'event': 'diagnostic_failure_stop_unknown', 'id': cid}), flush=True)
                raise admission.AdmissionError('diagnostic server ' + cid + ' retained or outcome unknown') from error
        finally:
            os.close(folder)
