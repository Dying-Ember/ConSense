"""One fixed empty-instance Qdrant startup; does not establish HTTP or persistence."""
import copy
import hashlib
import json
import os
from pathlib import Path
import socket
import stat
import time
from urllib.parse import quote

import container_admission as admission
import jre_probe_admission as jre

SCOPE = COMBINED_SCOPE = 'ticket-02 Qdrant CA start observe graceful stop attempt2 only'
BOOT = '2ef1599f-2285-492a-ad96-656237895214'
IMAGE = 'sha256:d607e8ae9fbcbba5b5651ca13f722600d956fb420f6d0192f40ba08fd5778ef1'
OLD_ROOT = '/srv/consense-rehearsal-20261029/instances/local01/qdrant-server'
ROOT = '/srv/consense-rehearsal-20261029/instances/local01/qdrant-server-attempt2'
BINARY = '/srv/consense-rehearsal-20261029/toolchains/qdrant-server-1.19.2-stage-attempt1/qdrant.partial'
STATE = Path('/mnt/d/R&D/ConSense/.local/container-rehearsal-20261029/parallel-state.json')
MAX_READ = 95100421
OLD_ID = '858c92457010d90335fc3be7eb62f149d8feb76e45b5367c5c343870b0bb53b1'
OLD_PROFILE_SHA = '771d1bae72313644c3264cd3c2edf3eb6f4c25d4791e232d28daa941b2ac719c'
OLD_STARTED = '2026-10-29T20:11:53.580158317Z'
OLD_FINISHED = '2026-10-29T20:11:53.700652192Z'
CA = '/srv/consense-docker/test-rootfs/etc/ssl/certs/ca-certificates.crt'
CA_TARGET = '/etc/ssl/certs/ca-certificates.crt'
GPU_ID = '5caf726699a497bff26741b11c41c8ae0a614d00da841262b90a6e33acbaa887'
GPU_PROFILE_SHA = '422a6b2872d053d77b66422e64c0823659ffa70fc4f320ae64424458a0851304'
GPU_PROFILE = {'config': {'Cmd': ['/probe/code/ticket04-cuda-container-probe.py'], 'Entrypoint': ['/usr/bin/python3', '-I', '-S', '-B'], 'Env': ['PATH=/usr/bin:/bin', 'HOME=/home/consense', 'LANG=C'], 'HostConfig': {'CapDrop': ['ALL'], 'Devices': [{'CgroupPermissions': 'rw', 'PathInContainer': '/dev/dxg', 'PathOnHost': '/dev/dxg'}], 'Memory': 268435456, 'Mounts': [{'ReadOnly': True, 'Source': '/srv/consense-docker/test-rootfs/usr', 'Target': '/usr', 'Type': 'bind'}, {'ReadOnly': True, 'Source': '/srv/consense-docker/test-rootfs/etc/ld.so.cache', 'Target': '/etc/ld.so.cache', 'Type': 'bind'}, {'ReadOnly': True, 'Source': '/srv/consense-rehearsal-20261029/t04-gpu-positive-control-attempt1', 'Target': '/probe', 'Type': 'bind'}, {'ReadOnly': True, 'Source': '/usr/lib/wsl/lib', 'Target': '/probe/driver/lib', 'Type': 'bind'}, {'ReadOnly': True, 'Source': '/usr/lib/wsl/drivers', 'Target': '/probe/driver/drivers', 'Type': 'bind'}], 'NanoCpus': 250000000, 'NetworkMode': 'none', 'PidsLimit': 20, 'Privileged': False, 'ReadonlyRootfs': True, 'SecurityOpt': ['no-new-privileges:true']}, 'Image': 'sha256:d607e8ae9fbcbba5b5651ca13f722600d956fb420f6d0192f40ba08fd5778ef1', 'Labels': {'consense.deployment': 'r1-20261029', 'consense.role': 'gpu'}, 'User': 'consense:consense'}, 'name': 'consense-gpu-positive-control-local01', 'ports': [], 'role': 'gpu'}
GPU_STARTED = '2026-10-29T19:47:15.079984583Z'
GPU_FINISHED = '2026-10-29T19:47:15.23980952Z'
RATE = 4 * 1024 * 1024
FILES = (
    (BINARY, 2112, 56664, 91409504, 0o555, '7dba403e3b1e1b68e8b4ec9f241b6152361a12c58f17d2d8146d1ce343d091a6'),
    ('/srv/consense-docker/test-rootfs/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2', 2112, 35457, 236616, 0o755, 'c20a2dc8917c755f02b94049356320fe1f62ac7d9f8994731f807d9df39302da'),
    ('/srv/consense-docker/test-rootfs/usr/lib/x86_64-linux-gnu/libgcc_s.so.1', 2112, 35540, 183024, 0o644, 'd93224d2b0dab4247598be683adca02f5cf00586f99c187579cd7e92058fb7cb'),
    ('/srv/consense-docker/test-rootfs/usr/lib/x86_64-linux-gnu/libm.so.6', 2112, 35591, 952616, 0o644, 'fce00b6f25f459cf4ae0b7fae4257909a1a8a86f9149e7c029a5d617baf1ccd0'),
    ('/srv/consense-docker/test-rootfs/usr/lib/x86_64-linux-gnu/libc.so.6', 2112, 35490, 2129424, 0o755, '3a15d66867d83762c7f2f1e37359cb8f6c5743edb369c65285cb0b1c4f7498bf'),
    ('/srv/consense-docker/test-rootfs/etc/ld.so.cache', 2112, 28815, 8271, 0o644, '87457b1fec59953ac1ea2881fbb2e26ef2a7463587888e76d2fe75b681ffc804'),
    (ROOT + '/config/qdrant-server.yaml', 2112, 56793, 222, 0o444, 'af9edc999b0be032d63805378c83fcae7ef0ae5eefec3538eff19f0ca3cfea3f'),
    (CA, 2112, 29168, 180744, 0o644, '64e7c24b0536b525fb19b145768bd5e6e12ced0df3db837c34553ea789acd143'),
)
DIRECTORIES = ((ROOT, 56779, 0, 0, 0o755), (ROOT + '/config', 56792, 0, 0, 0o755),
               (ROOT + '/storage', 56790, 10001, 10001, 0o700), (ROOT + '/tmp', 56791, 10001, 10001, 0o700))
LOADER_LINK = '/srv/consense-docker/test-rootfs/usr/lib64/ld-linux-x86-64.so.2'
MOUNTS = [
    {'Type': 'bind', 'Source': '/srv/consense-docker/test-rootfs/usr', 'Target': '/usr', 'ReadOnly': True},
    {'Type': 'bind', 'Source': '/srv/consense-docker/test-rootfs/etc/ld.so.cache', 'Target': '/etc/ld.so.cache', 'ReadOnly': True},
    {'Type': 'bind', 'Source': BINARY, 'Target': '/runtime/bin/qdrant', 'ReadOnly': True},
    {'Type': 'bind', 'Source': ROOT + '/config/qdrant-server.yaml', 'Target': '/runtime/config/qdrant-server.yaml', 'ReadOnly': True},
    {'Type': 'bind', 'Source': CA, 'Target': CA_TARGET, 'ReadOnly': True},
    {'Type': 'bind', 'Source': ROOT + '/storage', 'Target': '/qdrant/storage', 'ReadOnly': False},
    {'Type': 'bind', 'Source': ROOT + '/tmp', 'Target': '/tmp', 'ReadOnly': False},
]
PROFILE = {
    'role': 'qdrant', 'name': 'consense-qdrant-local01-attempt2', 'ports': [28683],
    'config': {'Image': IMAGE, 'User': 'consense:consense', 'Entrypoint': ['/runtime/bin/qdrant'],
               'Cmd': ['--config-path', '/runtime/config/qdrant-server.yaml'],
               'Env': ['PATH=/usr/bin:/bin', 'HOME=/home/consense', 'LANG=C'],
               'Labels': {'consense.role': 'qdrant', 'consense.deployment': 'r1-20261029'},
               'HostConfig': {'NetworkMode': 'host', 'ReadonlyRootfs': True, 'Privileged': False,
                              'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'],
                              'Mounts': MOUNTS, 'Memory': 536870912, 'NanoCpus': 1000000000, 'PidsLimit': 64}},
}
OLD_PROFILE = copy.deepcopy(PROFILE)
OLD_PROFILE['name'] = 'consense-qdrant-local01'
OLD_PROFILE['config']['HostConfig']['Mounts'] = [
    dict(m, Source=m['Source'].replace(ROOT, OLD_ROOT))
    for m in MOUNTS if m['Source'] != CA
]
PROFILE_SHA = '4dfa4ac1537cd62370a235cab50d58717ac1e173485180607c2ed88ab98cbf9b'


def fixed_profile():
    return copy.deepcopy(PROFILE)


def _retention(row):
    host = row.get('HostConfig') if isinstance(row, dict) else None
    policy = host.get('RestartPolicy') if isinstance(host, dict) else None
    admission.require(isinstance(host, dict) and host.get('Tmpfs') in (None, {})
                      and host.get('AutoRemove') is False and isinstance(policy, dict)
                      and set(policy) == {'Name', 'MaximumRetryCount'}
                      and policy.get('Name') == 'no' and type(policy.get('MaximumRetryCount')) is int
                      and policy['MaximumRetryCount'] == 0, 'container retention policy differs')


def _inspect_instance(row, profile, cid, state):
    _retention(row)
    return jre._inspect(row, profile, cid, state)


def _gpu_state(row):
    admission.require(admission._pin(GPU_PROFILE) == GPU_PROFILE_SHA, 'retained GPU profile differs')
    state = jre._inspect(row, GPU_PROFILE, GPU_ID, 'exited')
    _retention(row)
    admission.require(state['ExitCode'] == 1 and state.get('StartedAt') == GPU_STARTED
                      and state.get('FinishedAt') == GPU_FINISHED, 'retained GPU terminal identity differs')


def _old_state(row):
    admission.require(admission._pin(OLD_PROFILE) == OLD_PROFILE_SHA, 'retained Qdrant profile differs')
    state = jre._inspect(row, OLD_PROFILE, OLD_ID, 'exited')
    _retention(row)
    admission.require(state['ExitCode'] == 101 and state.get('StartedAt') == OLD_STARTED
                      and state.get('FinishedAt') == OLD_FINISHED, 'retained Qdrant terminal identity differs')


def _inventory(daemon, qdrant_id=None):
    rows = admission._request('GET', '/containers/json?all=1', daemon)
    admission.require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows), 'container inventory unavailable')
    ids = [row.get('Id') for row in rows]
    expected = [GPU_ID, OLD_ID] + ([qdrant_id] if qdrant_id is not None else [])
    admission.require(len(ids) == len(expected) and set(ids) == set(expected) and len(set(ids)) == len(ids)
                      and len(ids) <= 5, 'unexpected or missing retained container')
    _gpu_state(admission._request('GET', '/containers/' + GPU_ID + '/json', daemon))
    _old_state(admission._request('GET', '/containers/' + OLD_ID + '/json', daemon))
    return sorted(ids)


def _scope(scope=SCOPE):
    admission.require(scope in (SCOPE, COMBINED_SCOPE), 'unsupported Qdrant scope')
    state = jre._read_manifest(STATE, None)
    admission.require(isinstance(state, dict) and state.get('coordinator') == '01a18801-fddb-7490-87fa-3a51352d8c75'
                      and state.get('ticket01Gate') == 'closed' and state.get('heavyTaskGate') == 'ordered-single-heavy-task'
                      and state.get('heavyTaskOwner') == 'ticket-02' and state.get('heavyTaskScope') == scope,
                      'Qdrant scope unavailable')


def _directories():
    identities = {}
    for path, inode, uid, gid, mode in DIRECTORIES:
        p = Path(path)
        jre._safe_parent(p)
        fd = os.open(p, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            info = os.fstat(fd)
            admission.require(stat.S_ISDIR(info.st_mode) and (info.st_dev, info.st_ino, info.st_uid, info.st_gid,
                              stat.S_IMODE(info.st_mode)) == (2112, inode, uid, gid, mode)
                              and jre._identity(info) == jre._identity(p.lstat()), 'Qdrant directory differs')
            if path in (ROOT + '/storage', ROOT + '/tmp'):
                admission.require(not os.listdir(fd), 'Qdrant directory is not empty')
            identities[path] = list(jre._identity(info))
        finally:
            os.close(fd)
    return identities


def _hash_files(scope=SCOPE):
    admission.require(scope in (SCOPE, COMBINED_SCOPE), 'unsupported Qdrant scope')
    started = time.monotonic()
    total = 0
    identities = {}
    for name, device, inode, size, mode, expected in FILES:
        path = Path(name)
        jre._safe_parent(path)
        before = path.lstat()
        admission.require(stat.S_ISREG(before.st_mode) and (before.st_dev, before.st_ino, before.st_size,
                          before.st_uid, before.st_gid, before.st_nlink, stat.S_IMODE(before.st_mode)) ==
                          (device, inode, size, 0, 0, 1, mode), 'Qdrant input identity differs')
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            admission.require(jre._identity(os.fstat(fd)) == jre._identity(before), 'Qdrant input changed')
            digest, count = hashlib.sha256(), 0
            while count < size:
                length = min(1024 * 1024, size - count, MAX_READ - total)
                admission.require(length > 0, 'Qdrant read budget exceeded')
                block = os.read(fd, length)
                admission.require(0 < len(block) <= length, 'Qdrant input short read')
                old = total
                count += len(block)
                total += len(block)
                digest.update(block)
                if total // (16 * 1024 * 1024) != old // (16 * 1024 * 1024):
                    _scope(scope)
                delay = total / RATE - (time.monotonic() - started)
                if delay > 0:
                    time.sleep(delay)
            admission.require(digest.hexdigest() == expected and jre._identity(before) == jre._identity(os.fstat(fd))
                              == jre._identity(path.lstat()), 'Qdrant input bytes changed')
            identities[name] = list(jre._identity(before))
        finally:
            os.close(fd)
    admission.require(total == MAX_READ, 'Qdrant total read differs')
    return {'bytesRead': total, 'identities': identities, 'fullClosureVerified': False}


def _recheck_files(identities):
    for name, *_ in FILES:
        path = Path(name)
        jre._safe_parent(path)
        admission.require(list(jre._identity(path.lstat())) == identities[name], 'Qdrant input metadata changed')
    link = Path(LOADER_LINK)
    jre._safe_parent(link)
    before = link.lstat()
    admission.require(stat.S_ISLNK(before.st_mode) and before.st_uid == 0 and
                      os.readlink(link) == '../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2'
                      and jre._identity(before) == jre._identity(link.lstat()), 'Qdrant loader link differs')


def _ports_free():
    for family, address in ((socket.AF_INET, ('127.0.0.1', 28683)), (socket.AF_INET6, ('::1', 28683))):
        with socket.socket(family, socket.SOCK_STREAM) as sock:
            if family == socket.AF_INET6:
                sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
            sock.bind(address)


def _network(daemon, pid=None, start=None):
    boot = Path('/proc/sys/kernel/random/boot_id')
    admission.require(boot.read_text().strip() == BOOT and jre._starttime(daemon['pid']) == daemon['startTicks'],
                      'Qdrant boot or daemon identity differs')
    if pid is not None:
        admission.require(jre._starttime(pid) == start, 'Qdrant process identity differs')
    names = ['/proc/self/ns/net', '/proc/' + str(daemon['pid']) + '/ns/net']
    if pid is not None:
        names.append('/proc/' + str(pid) + '/ns/net')
    def snapshot():
        return [(s.st_dev, s.st_ino) for s in (os.stat(name) for name in names)]
    observed = snapshot()
    admission.require(len(set(observed)) == 1 and snapshot() == observed
                      and boot.read_text().strip() == BOOT and jre._starttime(daemon['pid']) == daemon['startTicks']
                      and (pid is None or jre._starttime(pid) == start), 'Qdrant network namespace differs')
    return observed[0]


def _listener(pid):
    """Require only a loopback IPv4 listener owned by the observed process."""
    entries = []
    for family in ('tcp', 'tcp6'):
        for line in (Path('/proc') / str(pid) / 'net' / family).read_text().splitlines()[1:]:
            row = line.split()
            admission.require(len(row) > 9, 'invalid TCP table')
            if row[3] == '0A' and int(row[1].split(':')[1], 16) == 28683:
                entries.append((family, row[1].split(':')[0], row[9]))
    if not entries:
        return False
    admission.require(len(entries) == 1 and entries[0][:2] == ('tcp', '0100007F'), 'Qdrant listener differs')
    fds = Path('/proc') / str(pid) / 'fd'
    sockets = set()
    for fd in fds.iterdir():
        try:
            sockets.add(os.readlink(fd))
        except FileNotFoundError:
            continue
    admission.require('socket:[' + entries[0][2] + ']' in sockets, 'listener not owned by Qdrant PID')
    return True


def _process_binary(pid):
    info = os.stat('/proc/' + str(pid) + '/exe')
    admission.require((info.st_dev, info.st_ino) == (2112, 56664), 'Qdrant executable identity differs')


def _ca_binding(pid, start):
    admission.require(jre._starttime(pid) == start, 'Qdrant process changed before CA check')
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    fds = []
    try:
        fds.append(os.open('/proc/' + str(pid) + '/root', os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC))
        for component in ('etc', 'ssl', 'certs'):
            fds.append(os.open(component, flags, dir_fd=fds[-1]))
        fds.append(os.open('ca-certificates.crt', os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fds[-1]))
        info = os.fstat(fds[-1])
        admission.require(stat.S_ISREG(info.st_mode) and
                          (info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_size,
                           stat.S_IMODE(info.st_mode), info.st_nlink) ==
                          (2112, 29168, 0, 0, 180744, 0o644, 1), 'Qdrant CA target differs')
        lines = (Path('/proc/self/fdinfo') / str(fds[-1])).read_text().splitlines()
        ids = [line.split()[1] for line in lines if line.startswith('mnt_id:')]
        rows = (Path('/proc') / str(pid) / 'mountinfo').read_text().splitlines()
        selected = [row.split() for row in rows if row.split()[0] in ids]
        admission.require(len(ids) == 1 and len(selected) == 1 and selected[0][4] == CA_TARGET
                          and 'ro' in selected[0][5].split(',') and selected[0][2] == '8:64'
                          and jre._identity(info) == jre._identity(Path(CA).lstat())
                          and jre._starttime(pid) == start, 'Qdrant CA mount identity differs')
        return {'device': info.st_dev, 'inode': info.st_ino, 'mountId': ids[0],
                'target': CA_TARGET, 'readOnly': True, 'processReadObserved': False}
    finally:
        for fd in reversed(fds):
            os.close(fd)


def run(profile, digest, known_containers, daemon, timeout=60, scope=SCOPE):
    """Start one fixed role; preserve a created or running container on every error."""
    profile, known_containers, daemon = copy.deepcopy((profile, known_containers, daemon))
    admission.require(profile == PROFILE and digest == PROFILE_SHA == admission._pin(PROFILE), 'Qdrant fixed profile differs')
    admission._daemon_pin(daemon)
    admission.require(type(timeout) is int and 1 <= timeout <= 60, 'invalid Qdrant timeout')
    admission.require(scope in (SCOPE, COMBINED_SCOPE), 'unsupported Qdrant scope')
    admission.require(known_containers == {}, 'only the fixed retained GPU and Qdrant containers are allowed')
    with admission._lock():
        _scope(scope)
        jre._verify_resource(daemon)
        _network(daemon)
        admission._profile(profile, digest)
        before_dirs = _directories()
        info = admission._request('GET', '/info', daemon)
        admission.require(isinstance(info, dict) and info.get('ID') == daemon['id']
                          and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        before = _inventory(daemon)
        admission.require(len(before) < 5, 'Qdrant slot unavailable')
        jre._image(profile, daemon)
        protected = [m['Source'] for m in MOUNTS if m['ReadOnly']] + [
            '/srv/consense-rehearsal-20261029/t04-gpu-positive-control-attempt1', OLD_ROOT]
        admission._verify_mount_isolation(protected,
                                          [m['Source'] for m in MOUNTS if not m['ReadOnly']], daemon)
        checked = _hash_files(scope)
        _scope(scope)
        jre._verify_resource(daemon)
        _recheck_files(checked['identities'])
        admission.require(_directories() == before_dirs, 'Qdrant directory changed')
        admission._verify_mount_isolation(protected,
                                          [m['Source'] for m in MOUNTS if not m['ReadOnly']], daemon)
        _network(daemon)
        _ports_free()
        admission.require(_inventory(daemon) == before, 'container inventory changed')
        _scope(scope)
        jre._verify_resource(daemon)
        try:
            created = admission._request('POST', '/containers/create?name=' + quote(profile['name'], safe=''),
                                         daemon, profile['config'])
        except Exception as error:
            raise admission.AdmissionError('Qdrant create outcome unknown; inspect exact name') from error
        cid = created.get('Id') if isinstance(created, dict) else None
        admission.require(isinstance(cid, str) and len(cid) == 64 and all(c in '0123456789abcdef' for c in cid)
                          and cid not in before, 'Qdrant create identity unknown')
        print(json.dumps({'event': 'qdrant_created', 'id': cid}), flush=True)
        try:
            created_state = _inspect_instance(admission._request('GET', '/containers/' + cid + '/json', daemon),
                                              profile, cid, 'created')
            admission.require(created_state.get('StartedAt') == '0001-01-01T00:00:00Z'
                              and created_state.get('FinishedAt') == '0001-01-01T00:00:00Z', 'Qdrant already started')
            _inventory(daemon, cid)
            _scope(scope)
            jre._verify_resource(daemon)
            _recheck_files(checked['identities'])
            admission.require(_directories() == before_dirs, 'Qdrant directory changed')
            admission._verify_mount_isolation(protected, [m['Source'] for m in MOUNTS if not m['ReadOnly']], daemon)
            _network(daemon)
            _ports_free()
            deadline = time.monotonic() + timeout
            print(json.dumps({'event': 'qdrant_start_intent', 'id': cid}), flush=True)
            admission._request('POST', '/containers/' + cid + '/start', daemon)
            while time.monotonic() < deadline:
                row = admission._request('GET', '/containers/' + cid + '/json', daemon)
                state = _inspect_instance(row, profile, cid, 'running')
                jre._timestamp(state.get('StartedAt'))
                pid = state['Pid']
                start = jre._starttime(pid)
                admission._process_identity(pid)
                _process_binary(pid)
                network = _network(daemon, pid, start)
                if _listener(pid):
                    admission.require(jre._starttime(pid) == start, 'Qdrant process changed')
                    _process_binary(pid)
                    ca_binding = _ca_binding(pid, start)
                    admission.require(_network(daemon, pid, start) == network, 'Qdrant network changed')
                    _inventory(daemon, cid)
                    _scope(scope)
                    return {'id': cid, 'pid': pid, 'starttime': start, 'startedAt': state['StartedAt'],
                            'listener': '127.0.0.1:28683', 'networkNamespace': list(network), 'selectedFiles': checked,
                            'caMount': ca_binding,
                            'httpVerified': False, 'persistenceVerified': False, 'businessAccepted': False,
                            'retainedGpuRelease': 'unknown'}
                time.sleep(.25)
            raise admission.AdmissionError('Qdrant listener deadline exceeded')
        except Exception as error:
            raise admission.AdmissionError('Qdrant container ' + cid + ' retained or outcome unknown') from error
