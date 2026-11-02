"""One synthetic H2 TCP server candidate; does not prove client or persistence."""
import copy
import hashlib
import os
from pathlib import Path
import socket
import stat
import time
from urllib.parse import quote

import container_admission as admission
import h2_synthetic_disk_admission as disk
import jre_probe_admission as jre


SCOPE = 'ticket-02 H2 synthetic TCP server start attempt1 only'
BOOT = '2ef1599f-2285-492a-ad96-656237895214'
JAR = '/srv/consense-rehearsal-20261029/toolchains/h2-2.1.214-stage-20261029-attempt2.jar'
JAVA = '/srv/consense-rehearsal-20261029/toolchains/jre-runtime-stage-attempt1/rootfs/usr/lib/jvm/java-17-openjdk-amd64/bin/java'
MAX_READ = 192 * 1024 * 1024
RATE = 4 * 1024 * 1024
DB_DIR_ID = (2112, 56750)
DB_ROWS = {
    'fixture.mv.db': (56756, 28672, 1793304112930047810, 1793304112930047810),
    'fixture.trace.db': (56753, 3636, 1793304112041813589, 1793304112041813589),
    'phase1.complete': (56773, 14, 1793304109102276123, 1793304109102276123),
}
PROFILE = {
    'role': 'h2', 'name': 'consense-h2-synthetic-tcp-local01', 'ports': [28682],
    'config': {'Image': disk.IMAGE, 'User': 'consense:consense', 'Entrypoint': ['/runtime/jre/bin/java'],
               'Cmd': ['-Xmx256m', '-Dh2.bindAddress=127.0.0.1', '-Djava.io.tmpdir=/runtime/probe',
                       '-cp', '/runtime/probe-input/h2.jar', 'org.h2.tools.Server',
                       '-tcp', '-tcpPort', '28682', '-ifExists', '-baseDir', '/runtime/probe/db'],
               'Env': list(jre.ENV), 'Labels': {'consense.role': 'h2', 'consense.deployment': 'r1-20261029'},
               'HostConfig': {'NetworkMode': 'host', 'ReadonlyRootfs': True, 'Privileged': False,
                              'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'],
                              'Mounts': ([{'Type': 'bind', 'Source': source, 'Target': target, 'ReadOnly': True}
                                          for target, source in jre.MOUNTS.items()] +
                                         [{'Type': 'bind', 'Source': JAR, 'Target': '/runtime/probe-input/h2.jar',
                                           'ReadOnly': True},
                                          {'Type': 'bind', 'Source': str(disk.FIXTURE), 'Target': '/runtime/probe',
                                           'ReadOnly': False}]),
                              'Memory': 536870912, 'NanoCpus': 500000000, 'PidsLimit': 32}},
}


def _scope():
    jre._verify_scope(SCOPE)


def _closed_fixture(prior=None):
    root = disk.FIXTURE
    jre._safe_parent(root)
    parent, before = root.parent.lstat(), root.lstat()
    admission.require(stat.S_ISDIR(parent.st_mode) and (parent.st_dev, parent.st_ino) == disk.FIXTURE_PARENT_ID
                      and parent.st_uid == parent.st_gid == 0 and stat.S_IMODE(parent.st_mode) == 0o700
                      and stat.S_ISDIR(before.st_mode) and (before.st_dev, before.st_ino) == disk.FIXTURE_ID
                      and before.st_uid == before.st_gid == 10001 and stat.S_IMODE(before.st_mode) == 0o700,
                      'synthetic fixture root differs')
    rootfd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    dbfd = None
    try:
        admission.require(jre._identity(before) == jre._identity(os.fstat(rootfd)) and os.listdir(rootfd) == ['db'],
                          'synthetic fixture contents differ')
        dbfd = os.open('db', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=rootfd)
        db = os.fstat(dbfd)
        admission.require(stat.S_ISDIR(db.st_mode) and (db.st_dev, db.st_ino, db.st_uid, db.st_gid,
                          stat.S_IMODE(db.st_mode)) == (*DB_DIR_ID, 10001, 10001, 0o755)
                          and jre._identity(db) == jre._identity(os.stat('db', dir_fd=rootfd, follow_symlinks=False))
                          and sorted(os.listdir(dbfd)) == sorted(DB_ROWS), 'synthetic database directory differs')
        identities = {'root': list(jre._identity(before)), 'db': list(jre._identity(db))}
        for name, (inode, size, mtime, ctime) in DB_ROWS.items():
            row = os.stat(name, dir_fd=dbfd, follow_symlinks=False)
            admission.require(stat.S_ISREG(row.st_mode) and (row.st_dev, row.st_ino, row.st_size, row.st_uid,
                              row.st_gid, stat.S_IMODE(row.st_mode), row.st_nlink, row.st_mtime_ns, row.st_ctime_ns)
                              == (disk.FIXTURE_ID[0], inode, size, 10001, 10001, 0o644, 1, mtime, ctime),
                              'synthetic database file identity differs')
            identities[name] = list(jre._identity(row))
        admission.require(jre._identity(before) == jre._identity(os.fstat(rootfd))
                          == jre._identity(root.lstat()) and jre._identity(db) == jre._identity(os.fstat(dbfd)),
                          'synthetic fixture changed during inspection')
        admission.require(prior is None or identities == prior, 'synthetic fixture changed after preflight')
        return identities
    finally:
        if dbfd is not None:
            os.close(dbfd)
        os.close(rootfd)


def _entry(path, row, started, count, identities):
    jre._safe_parent(path)
    before = path.lstat()
    admission.require(before.st_uid == 0, 'runtime input owner differs')
    if row[0] == 'symlink':
        admission.require(stat.S_ISLNK(before.st_mode) and os.readlink(path) == row[1]
                          and jre._identity(path.lstat()) == jre._identity(before), 'runtime link differs')
    else:
        size = row[1]
        admission.require(type(size) is int and 0 <= size and type(count) is int and 0 <= count <= MAX_READ
                          and stat.S_ISREG(before.st_mode) and not before.st_mode & 0o022
                          and before.st_size == size and size <= MAX_READ - count, 'runtime input metadata differs')
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            admission.require(jre._identity(before) == jre._identity(os.fstat(fd)), 'runtime input changed')
            digest, done = hashlib.sha256(), 0
            while done < size:
                length = min(1024 * 1024, size - done, MAX_READ - count)
                block = os.read(fd, length)
                admission.require(0 < len(block) <= length, 'runtime input short read')
                old = count
                count += len(block); done += len(block); digest.update(block)
                if count // (32 * 1024 * 1024) != old // (32 * 1024 * 1024):
                    _scope()
                delay = count / RATE - (time.monotonic() - started)
                if delay > 0:
                    time.sleep(delay)
            admission.require(digest.hexdigest() == row[2] and jre._identity(before) == jre._identity(os.fstat(fd))
                              == jre._identity(path.lstat()), 'runtime input bytes changed')
        finally:
            os.close(fd)
    identities[str(path)] = list(jre._identity(before))
    return count


def _runtime():
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
        total = _entry(path, row, started, total, identities)
    jar = Path(JAR); jarinfo = jar.lstat()
    admission.require((jarinfo.st_dev, jarinfo.st_ino, jarinfo.st_uid, jarinfo.st_gid, jarinfo.st_nlink,
                       stat.S_IMODE(jarinfo.st_mode)) == (2112, 56137, 0, 0, 1, 0o444), 'H2 jar identity differs')
    total = _entry(jar, ('file', 2543012, 'd623cdc0f61d218cf549a8d09f1c391ff91096116b22e2475475fce4fbe72bd0'),
                   started, total, identities)
    return {'bytesRead': total, 'identities': identities, 'fullClosureVerified': False}


def _recheck_runtime(result):
    for target, source in jre.MOUNTS.items():
        path = Path(source); jre._safe_parent(path); info = path.lstat()
        admission.require((info.st_dev, info.st_ino) == jre.ROOT_IDENTITIES[target] and info.st_uid == 0
                          and not info.st_mode & 0o022, 'runtime mount identity changed')
    for name, expected in result['identities'].items():
        path = Path(name); jre._safe_parent(path)
        admission.require(list(jre._identity(path.lstat())) == expected, 'runtime input changed after hashing')


def _ports_free():
    admission.require(not _port_entries('/proc/self'), 'H2 port already has a TCP listener')
    for family, address in ((socket.AF_INET, ('127.0.0.1', 28682)), (socket.AF_INET6, ('::1', 28682))):
        with socket.socket(family, socket.SOCK_STREAM) as sock:
            if family == socket.AF_INET6:
                sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
            sock.bind(address)


def _network(daemon, pid=None, start=None):
    admission.require(Path('/proc/sys/kernel/random/boot_id').read_text().strip() == BOOT
                      and jre._starttime(daemon['pid']) == daemon['startTicks'], 'boot or daemon differs')
    names = ['/proc/self/ns/net', '/proc/' + str(daemon['pid']) + '/ns/net']
    if pid is not None:
        admission.require(jre._starttime(pid) == start, 'server process changed')
        names.append('/proc/' + str(pid) + '/ns/net')
    observed = [(row.st_dev, row.st_ino) for row in (os.stat(name) for name in names)]
    again = [(row.st_dev, row.st_ino) for row in (os.stat(name) for name in names)]
    admission.require(len(set(observed)) == 1 and again == observed
                      and Path('/proc/sys/kernel/random/boot_id').read_text().strip() == BOOT
                      and jre._starttime(daemon['pid']) == daemon['startTicks']
                      and (pid is None or jre._starttime(pid) == start), 'network namespace differs')
    return observed[0]


def _port_entries(proc):
    entries = []
    for family in ('tcp', 'tcp6'):
        for line in (Path(proc) / 'net' / family).read_text().splitlines()[1:]:
            row = line.split()
            admission.require(len(row) > 9, 'invalid TCP table')
            if row[3] == '0A' and int(row[1].split(':')[1], 16) == 28682:
                entries.append((family, row[1].split(':')[0], row[9]))
    return entries


def _listener(pid):
    entries = _port_entries('/proc/' + str(pid))
    if not entries:
        return False
    admission.require(len(entries) == 1 and entries[0][:2] == ('tcp', '0100007F'), 'H2 listener differs')
    sockets = set()
    for fd in (Path('/proc') / str(pid) / 'fd').iterdir():
        try:
            sockets.add(os.readlink(fd))
        except FileNotFoundError:
            continue
    admission.require('socket:[' + entries[0][2] + ']' in sockets, 'listener not owned by H2 PID')
    return True


def _process_identity(pid):
    admission._process_identity(pid)
    fields = {}
    for line in (Path('/proc') / str(pid) / 'status').read_text().splitlines():
        if ':' in line:
            key, value = line.split(':', 1)
            fields[key] = value.split()
    admission.require(fields.get('Uid') == ['10001'] * 4 and fields.get('Gid') == ['10001'] * 4,
                      'H2 Java uid/gid differ')


def run(profile, digest, daemon, timeout=60):
    """Start one synthetic server; retain exact container on every outcome."""
    profile, daemon = copy.deepcopy((profile, daemon))
    admission.require(profile == PROFILE and digest == admission._pin(PROFILE), 'H2 server profile differs')
    admission.require(type(timeout) is int and 1 <= timeout <= 60, 'invalid H2 timeout')
    admission._daemon_pin(daemon)
    with admission._lock():
        _scope(); jre._verify_resource(daemon); _network(daemon)
        admission._profile(profile, digest)
        fixture = _closed_fixture()
        info = admission._request('GET', '/info', daemon)
        admission.require(isinstance(info, dict) and info.get('ID') == daemon['id']
                          and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        before = disk._known_inventory({}, daemon)
        admission.require(len(before) < 5, 'container slot unavailable')
        jre._image(profile, daemon)
        protected = list(jre.MOUNTS.values()) + [JAR] + disk.PROTECTED_DATA
        admission._verify_mount_isolation(protected, [str(disk.FIXTURE)], daemon)
        _ports_free()
        runtime = _runtime()
        _scope(); jre._verify_resource(daemon)
        _recheck_runtime(runtime); _closed_fixture(fixture); _network(daemon)
        admission._verify_mount_isolation(protected, [str(disk.FIXTURE)], daemon)
        admission.require(disk._known_inventory({}, daemon) == before, 'inventory changed')
        _ports_free(); _scope(); jre._verify_resource(daemon)
        try:
            created = admission._request('POST', '/containers/create?name=' + quote(profile['name'], safe=''),
                                         daemon, profile['config'])
        except Exception as error:
            raise admission.AdmissionError('H2 create outcome unknown; inspect exact name before retry') from error
        cid = created.get('Id') if isinstance(created, dict) else None
        admission.require(isinstance(cid, str) and len(cid) == 64 and all(c in '0123456789abcdef' for c in cid)
                          and cid not in before, 'invalid H2 container identity')
        try:
            path = '/containers/' + cid
            created_state = jre._inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'created')
            admission.require(created_state.get('StartedAt') == '0001-01-01T00:00:00Z'
                              and created_state.get('FinishedAt') == '0001-01-01T00:00:00Z', 'H2 already started')
            disk._known_inventory({}, daemon, cid)
            _scope(); jre._verify_resource(daemon); _closed_fixture(fixture); _recheck_runtime(runtime)
            admission._verify_mount_isolation(protected, [str(disk.FIXTURE)], daemon)
            _network(daemon); _ports_free()
            disk._known_inventory({}, daemon, cid)
            _scope(); jre._verify_resource(daemon)
            deadline = time.monotonic() + timeout
            admission._request('POST', path + '/start', daemon)
            while time.monotonic() < deadline:
                _scope(); jre._verify_resource(daemon)
                row = admission._request('GET', path + '/json', daemon)
                state = jre._inspect(row, profile, cid, 'running')
                jre._timestamp(state.get('StartedAt'))
                pid = state['Pid']; start = jre._starttime(pid)
                _process_identity(pid)
                exe = os.stat('/proc/' + str(pid) + '/exe')
                admission.require((exe.st_dev, exe.st_ino) == (2112, 56329), 'H2 Java executable differs')
                net = _network(daemon, pid, start)
                if _listener(pid):
                    admission.require(jre._starttime(pid) == start, 'H2 process changed')
                    disk._known_inventory({}, daemon, cid)
                    _scope(); jre._verify_resource(daemon)
                    return {'id': cid, 'pid': pid, 'starttime': start, 'startedAt': state['StartedAt'],
                            'netNamespace': net, 'runtime': runtime, 'listener': '127.0.0.1:28682',
                            'gpuRelease': 'unknown', 'cgroupVerified': False,
                            'clientVerified': False, 'persistenceVerified': False}
                time.sleep(.1)
            raise admission.AdmissionError('H2 listener not observed before deadline')
        except Exception as error:
            raise admission.AdmissionError('H2 server ' + cid + ' retained or outcome unknown') from error
