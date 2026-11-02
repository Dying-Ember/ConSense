"""One synthetic H2 TCP lifecycle candidate; all outcomes retain container evidence."""
import copy
import hashlib
import json
import select
import signal
import os
from pathlib import Path
import re
import socket
import stat
import time
from urllib.parse import quote

import container_admission as admission
import h2_synthetic_disk_admission as disk
import jre_probe_admission as jre


SCOPE = 'ticket-02 H2 synthetic TCP combined attempt1 only'
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
QDRANT_PROTECTED = Path('/srv/consense-rehearsal-20261029/instances/local01/qdrant-server-attempt2')
QDRANT_ENTRIES = (('storage', 56790, 10001, 10001, 0o700, True),
                  ('tmp', 56791, 10001, 10001, 0o700, True),
                  ('config', 56792, 0, 0, 0o755, True))
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
                              'Memory': 536870912, 'NanoCpus': 500000000, 'PidsLimit': 32,
                              'LogConfig': {'Type': 'json-file', 'Config': {'max-size': '1m', 'max-file': '1'}}}},
}


def _scope():
    jre._verify_scope(SCOPE)


def _qdrant_check(daemon, protected=()):
    """Check the preserved Qdrant tree at this instant, not continuous isolation."""
    jre._safe_parent(QDRANT_PROTECTED)
    flags = os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC
    root = os.open(QDRANT_PROTECTED, flags | os.O_DIRECTORY)
    children = []
    try:
        info = os.fstat(root)
        admission.require(stat.S_ISDIR(info.st_mode) and
                          (info.st_dev, info.st_ino, info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)) ==
                          (2112, 56779, 0, 0, 0o755) and
                          jre._identity(info) == jre._identity(QDRANT_PROTECTED.lstat()), 'Qdrant root identity differs')
        for name, inode, uid, gid, mode, directory in QDRANT_ENTRIES:
            fd = os.open(name, flags | (os.O_DIRECTORY if directory else 0), dir_fd=root)
            children.append((fd, root, name))
            row = os.fstat(fd)
            admission.require(stat.S_ISDIR(row.st_mode) and
                              (row.st_dev, row.st_ino, row.st_uid, row.st_gid, stat.S_IMODE(row.st_mode)) ==
                              (2112, inode, uid, gid, mode) and
                              jre._identity(row) == jre._identity(os.stat(name, dir_fd=root, follow_symlinks=False)),
                              'Qdrant directory identity differs')
        config = children[-1][0]
        fd = os.open('qdrant-server.yaml', flags, dir_fd=config)
        children.append((fd, config, 'qdrant-server.yaml'))
        row = os.fstat(fd)
        admission.require(stat.S_ISREG(row.st_mode) and
                          (row.st_dev, row.st_ino, row.st_uid, row.st_gid, row.st_size, row.st_nlink,
                           stat.S_IMODE(row.st_mode)) == (2112, 56793, 0, 0, 222, 1, 0o444) and
                          jre._identity(row) == jre._identity(os.stat('qdrant-server.yaml', dir_fd=config, follow_symlinks=False)),
                          'Qdrant config identity differs')
        before = [jre._identity(os.fstat(fd)) for fd, _, _ in children]
        admission._verify_mount_isolation(list(protected) + [str(QDRANT_PROTECTED)], [str(disk.FIXTURE)], daemon)
        admission.require(jre._identity(info) == jre._identity(os.fstat(root)) ==
                          jre._identity(QDRANT_PROTECTED.lstat()) and all(
                              identity == jre._identity(os.fstat(fd)) ==
                              jre._identity(os.stat(name, dir_fd=parent, follow_symlinks=False))
                              for identity, (fd, parent, name) in zip(before, children)),
                          'Qdrant identity changed during mount check')
        _event('qdrant_protection_checked', continuousIsolationVerified=False)
    finally:
        for fd, _, _ in reversed(children):
            os.close(fd)
        os.close(root)


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
    source = Path(CLIENT_SOURCE)
    _client_stage()
    total = _entry(source, ('file', 3087, CLIENT_SHA), started, total, identities)
    return {'bytesRead': total, 'identities': identities, 'fullClosureVerified': False}


def _recheck_runtime(result):
    _client_stage()
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
    admission.require(len(entries) == 1 and entries[0][:2] in (
        ('tcp', '0100007F'), ('tcp6', '0000000000000000FFFF00000100007F')), 'H2 listener differs')
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


QDRANT_ID = '858c92457010d90335fc3be7eb62f149d8feb76e45b5367c5c343870b0bb53b1'
QDRANT_PIN = '771d1bae72313644c3264cd3c2edf3eb6f4c25d4791e232d28daa941b2ac719c'
QDRANT_PROFILE = {'role': 'qdrant',
 'name': 'consense-qdrant-local01',
 'ports': [28683],
 'config': {'Image': 'sha256:d607e8ae9fbcbba5b5651ca13f722600d956fb420f6d0192f40ba08fd5778ef1',
            'User': 'consense:consense',
            'Entrypoint': ['/runtime/bin/qdrant'],
            'Cmd': ['--config-path', '/runtime/config/qdrant-server.yaml'],
            'Env': ['PATH=/usr/bin:/bin', 'HOME=/home/consense', 'LANG=C'],
            'Labels': {'consense.role': 'qdrant', 'consense.deployment': 'r1-20261029'},
            'HostConfig': {'NetworkMode': 'host',
                           'ReadonlyRootfs': True,
                           'Privileged': False,
                           'CapDrop': ['ALL'],
                           'SecurityOpt': ['no-new-privileges:true'],
                           'Mounts': [{'Type': 'bind',
                                       'Source': '/srv/consense-docker/test-rootfs/usr',
                                       'Target': '/usr',
                                       'ReadOnly': True},
                                      {'Type': 'bind',
                                       'Source': '/srv/consense-docker/test-rootfs/etc/ld.so.cache',
                                       'Target': '/etc/ld.so.cache',
                                       'ReadOnly': True},
                                      {'Type': 'bind',
                                       'Source': '/srv/consense-rehearsal-20261029/toolchains/qdrant-server-1.19.2-stage-attempt1/qdrant.partial',
                                       'Target': '/runtime/bin/qdrant',
                                       'ReadOnly': True},
                                      {'Type': 'bind',
                                       'Source': '/srv/consense-rehearsal-20261029/instances/local01/qdrant-server/config/qdrant-server.yaml',
                                       'Target': '/runtime/config/qdrant-server.yaml',
                                       'ReadOnly': True},
                                      {'Type': 'bind',
                                       'Source': '/srv/consense-rehearsal-20261029/instances/local01/qdrant-server/storage',
                                       'Target': '/qdrant/storage',
                                       'ReadOnly': False},
                                      {'Type': 'bind',
                                       'Source': '/srv/consense-rehearsal-20261029/instances/local01/qdrant-server/tmp',
                                       'Target': '/tmp',
                                       'ReadOnly': False}],
                           'Memory': 536870912,
                           'NanoCpus': 1000000000,
                           'PidsLimit': 64}}}
QDRANT_START = '2026-10-29T20:11:53.580158317Z'
QDRANT_FINISH = '2026-10-29T20:11:53.700652192Z'
CLIENT_SOURCE = '/srv/consense-rehearsal-20261029/toolchains/h2-tcp-client-attempt1/H2TcpSyntheticClient.java'
CLIENT_SHA = 'fb294a4c6c381efee5ac389321d4d57045a8cc16649e3d919c98746341d78e3f'
CLIENT_PINS = {'write': '7c0383e5eff3541738c1ba1a1cf9236e8bd425bd95dd35bbc314821c6f17c7bb',
               'read': '8eb7c44792c8c91b864e75db3d97b5b9cf2ce8978c728756a5f3715e7de0bd3d'}


def _client_stage():
    source = Path(CLIENT_SOURCE)
    jre._safe_parent(source)
    info, parent = source.lstat(), source.parent.lstat()
    admission.require(stat.S_ISDIR(parent.st_mode) and stat.S_ISREG(info.st_mode)
                      and (parent.st_dev, parent.st_ino, parent.st_uid, parent.st_gid, stat.S_IMODE(parent.st_mode))
                      == (2112, 56720, 0, 0, 0o700)
                      and (info.st_dev, info.st_ino, info.st_size, info.st_uid, info.st_gid, info.st_nlink,
                           stat.S_IMODE(info.st_mode)) == (2112, 56733, 3087, 0, 0, 1, 0o444),
                      'client source identity differs')


def _event(event, **data):
    print(json.dumps({'event': event, **data}, sort_keys=True), flush=True)


def _inventory(daemon, owned):
    admission.require(admission._pin(disk.GPU_PROFILE) == disk.GPU_PROFILE_PIN
                      and admission._pin(QDRANT_PROFILE) == QDRANT_PIN, 'fixed retained profile differs')
    rows = admission._request('GET', '/containers/json?all=1', daemon)
    admission.require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows), 'inventory unavailable')
    ids = [row.get('Id') for row in rows]
    expected = [disk.GPU_ID, QDRANT_ID] + list(owned)
    admission.require(len(expected) <= 5 and len(ids) == len(expected) and len(set(ids)) == len(ids)
                      and set(ids) == set(expected), 'unexpected or missing container')
    for cid, profile, exitcode, started, finished in (
        (disk.GPU_ID, disk.GPU_PROFILE, 1, disk.GPU_START, disk.GPU_FINISH),
        (QDRANT_ID, QDRANT_PROFILE, 101, QDRANT_START, QDRANT_FINISH)):
        row = admission._request('GET', '/containers/' + cid + '/json', daemon)
        state = jre._inspect(row, profile, cid, 'exited')
        admission.require(row['HostConfig'].get('Tmpfs') in (None, {}) and state['ExitCode'] == exitcode
                          and state.get('StartedAt') == started and state.get('FinishedAt') == finished,
                          'retained container terminal differs')
    return ids


def _client_profile(phase):
    admission.require(phase in ('write', 'read'), 'invalid client phase')
    mounts = [{'Type': 'bind', 'Source': source, 'Target': target, 'ReadOnly': True}
              for target, source in jre.MOUNTS.items()]
    mounts += [{'Type': 'bind', 'Source': JAR, 'Target': '/runtime/probe-input/h2.jar', 'ReadOnly': True},
               {'Type': 'bind', 'Source': CLIENT_SOURCE, 'Target': '/runtime/probe-input/H2TcpSyntheticClient.java',
                'ReadOnly': True}]
    return {'name': 'consense-h2-synthetic-tcp-' + phase + '-local01',
            'config': {'Image': disk.IMAGE, 'User': 'consense:consense', 'Entrypoint': ['/runtime/jre/bin/java'],
                       'Cmd': ['-Xmx128m', '-Djava.io.tmpdir=/tmp', '--class-path', '/runtime/probe-input/h2.jar',
                               '/runtime/probe-input/H2TcpSyntheticClient.java', phase],
                       'Env': list(jre.ENV), 'Labels': {'consense.role': 'h2-client-' + phase,
                                                       'consense.deployment': 'r1-20261029'},
                       'HostConfig': {'NetworkMode': 'host', 'ReadonlyRootfs': True, 'Privileged': False,
                                      'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'],
                                      'Mounts': mounts, 'Memory': 536870912, 'NanoCpus': 500000000,
                                      'PidsLimit': 32, 'Tmpfs': {'/tmp': 'rw,nosuid,nodev,noexec,size=16m,mode=1777'},
                                      'LogConfig': {'Type': 'json-file', 'Config': {'max-size': '1m', 'max-file': '1'}}}}}


def _client_output(phase, output):
    lines = output.splitlines()
    version = r'java\.version=\S+ h2\.database=2\.1\.214(?: \([^\r\n]*\))? h2\.driver=2\.1\.214(?: \([^\r\n]*\))?'
    expected = ([None, 'tcp synthetic commit starting; failure may be ambiguous',
                 'tcp synthetic commit returned', 'tcp synthetic write complete'] if phase == 'write'
                else [None, 'tcp synthetic read complete'])
    admission.require(len(lines) == len(expected) and all(
        re.fullmatch(version, line) is not None if item is None else line == item
        for line, item in zip(lines, expected)) and 'synthetic-only-new-password' not in output,
        'synthetic client output differs')
    return output


def _sample_server(pid):
    status = (Path('/proc') / str(pid) / 'status').read_text().splitlines()
    rss = [line.split() for line in status if line.startswith('VmRSS:')]
    admission.require(len(rss) == 1 and len(rss[0]) == 3 and rss[0][1].isdigit() and rss[0][2] == 'kB',
                      'server RSS unavailable')
    rss_bytes = int(rss[0][1]) * 1024
    admission.require(rss_bytes <= 512 * 1024 * 1024, 'server RSS budget exceeded')
    rootfd = os.open(disk.FIXTURE, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    dbfd = None
    try:
        root = os.fstat(rootfd)
        admission.require((root.st_dev, root.st_ino, root.st_uid, root.st_gid) == (2112, 56687, 10001, 10001),
                          'fixture root changed')
        dbfd = os.open('db', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=rootfd)
        db = os.fstat(dbfd)
        admission.require((db.st_dev, db.st_ino, db.st_uid, db.st_gid) == (2112, 56750, 10001, 10001),
                          'fixture db directory changed')
        total, count = 0, 0
        for fd, allowed_directory in ((rootfd, 'db'), (dbfd, None)):
            names = os.listdir(fd)
            admission.require(len(names) <= 32, 'fixture entry budget exceeded')
            for name in names:
                if name == allowed_directory:
                    continue
                row = os.stat(name, dir_fd=fd, follow_symlinks=False)
                admission.require(stat.S_ISREG(row.st_mode) and row.st_dev == 2112
                                  and row.st_uid == row.st_gid == 10001 and row.st_nlink == 1,
                                  'fixture contains unexpected object')
                count += 1; total += row.st_size
                admission.require(count <= 32 and total <= 64 * 1024 * 1024, 'fixture budget exceeded')
        return {'rssBytes': rss_bytes, 'fixtureBytes': total, 'fixtureFiles': count}
    finally:
        if dbfd is not None:
            os.close(dbfd)
        os.close(rootfd)


def _observe_server(daemon, owned, binding):
    _scope(); jre._verify_resource(daemon); _inventory(daemon, owned)
    row = admission._request('GET', '/containers/' + binding['id'] + '/json', daemon)
    state = jre._inspect(row, PROFILE, binding['id'], 'running')
    admission.require(state['Pid'] == binding['pid'] and state['StartedAt'] == binding['startedAt']
                      and jre._starttime(binding['pid']) == binding['starttime'], 'server identity changed')
    _process_identity(binding['pid'])
    exe = os.stat('/proc/' + str(binding['pid']) + '/exe')
    admission.require((exe.st_dev, exe.st_ino) == (2112, 56329), 'server Java executable differs')
    _network(daemon, binding['pid'], binding['starttime'])
    admission.require(_listener(binding['pid']), 'server listener unavailable')
    sample = _sample_server(binding['pid'])
    admission.require(jre._starttime(binding['pid']) == binding['starttime'], 'server process changed')
    return sample


def _wait_server(daemon, owned, cid, timeout, previous=None, observed=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        _scope(); jre._verify_resource(daemon); _inventory(daemon, owned)
        row = admission._request('GET', '/containers/' + cid + '/json', daemon)
        state = jre._inspect(row, PROFILE, cid, 'running')
        jre._timestamp(state.get('StartedAt'))
        pid = state['Pid']; start = jre._starttime(pid)
        _process_identity(pid)
        exe = os.stat('/proc/' + str(pid) + '/exe')
        admission.require((exe.st_dev, exe.st_ino) == (2112, 56329), 'server Java executable differs')
        _network(daemon, pid, start)
        binding = {'id': cid, 'pid': pid, 'starttime': start, 'startedAt': state['StartedAt']}
        if observed is not None:
            observed(binding)
        if _listener(pid):
            admission.require(previous is None or (binding['startedAt'] != previous['startedAt']
                              and (binding['pid'], binding['starttime']) != (previous['pid'], previous['starttime'])),
                              'server restart identity did not change')
            sample = _observe_server(daemon, owned, binding)
            _event('server_ready', binding=binding, sample=sample, cgroupVerified=False)
            return binding
        time.sleep(.1)
    raise admission.AdmissionError('server readiness deadline expired')


def _client(phase, daemon, owned, binding, timeout):
    profile = _client_profile(phase)
    admission.require(admission._pin(profile) == CLIENT_PINS[phase], 'fixed client profile differs')
    admission.require(len(owned) + 2 < 5, 'client container slot unavailable')
    _qdrant_check(daemon)
    admission._mounts(profile['config'])
    _observe_server(daemon, owned, binding)
    _event('client_create_intent', phase=phase, name=profile['name'])
    try:
        created = admission._request('POST', '/containers/create?name=' + quote(profile['name'], safe=''),
                                     daemon, profile['config'])
    except Exception as error:
        raise admission.AdmissionError('client create outcome unknown') from error
    cid = created.get('Id') if isinstance(created, dict) else None
    admission.require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid)
                      and cid not in owned and cid not in (disk.GPU_ID, QDRANT_ID), 'client identity unknown')
    owned.append(cid)
    _event('client_created', phase=phase, id=cid)
    path = '/containers/' + cid
    created_state = jre._inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'created')
    admission.require(created_state.get('StartedAt') == '0001-01-01T00:00:00Z'
                      and created_state.get('FinishedAt') == '0001-01-01T00:00:00Z', 'client already started')
    _observe_server(daemon, owned, binding)
    deadline = time.monotonic() + timeout
    _event('client_start_intent', phase=phase, id=cid)
    admission._request('POST', path + '/start', daemon)
    observed = None
    while time.monotonic() < deadline:
        _observe_server(daemon, owned, binding)
        row = admission._request('GET', path + '/json', daemon)
        status = row.get('State', {}).get('Status') if isinstance(row, dict) else None
        if status == 'running':
            state = jre._inspect(row, profile, cid, 'running')
            jre._timestamp(state.get('StartedAt'))
            pid = state['Pid']; start = jre._starttime(pid)
            _process_identity(pid)
            exe = os.stat('/proc/' + str(pid) + '/exe')
            admission.require((exe.st_dev, exe.st_ino) == (2112, 56329)
                              and jre._starttime(pid) == start, 'client Java identity differs')
            _network(daemon, pid, start)
            current = {'pid': pid, 'starttime': start, 'startedAt': state['StartedAt']}
            admission.require(observed is None or observed == current, 'client process changed')
            observed = current
        elif status == 'exited':
            state = jre._inspect(row, profile, cid, 'exited')
            admission.require(observed is not None and state['ExitCode'] == 0
                              and state['StartedAt'] == observed['startedAt']
                              and jre._timestamp(state['FinishedAt']) >= jre._timestamp(state['StartedAt']),
                              'client did not produce observed successful exit')
            output = _client_output(phase, jre._logs(cid, daemon))
            _observe_server(daemon, owned, binding)
            terminal = jre._inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'exited')
            admission.require(all(terminal.get(k) == state.get(k) for k in
                              ('ExitCode', 'StartedAt', 'FinishedAt', 'OOMKilled', 'Error')),
                              'client terminal changed')
            _event('client_completed', phase=phase, id=cid, process=observed, output=output,
                   tmpfsRuntimeVerified=False)
            return cid
        else:
            raise admission.AdmissionError('unexpected client state')
        time.sleep(.1)
    raise admission.AdmissionError('client deadline expired')


def _signal_stop(daemon, owned, binding, attempted, timeout):
    key = (binding['pid'], binding['starttime'])
    admission.require(key not in attempted, 'server stop was already attempted')
    attempted.add(key)
    _scope(); _inventory(daemon, owned)
    path = '/containers/' + binding['id']
    row = admission._request('GET', path + '/json', daemon)
    state = jre._inspect(row, PROFILE, binding['id'], 'running')
    admission.require(state['Pid'] == binding['pid'] and state['StartedAt'] == binding['startedAt']
                      and jre._starttime(binding['pid']) == binding['starttime'], 'server signal identity differs')
    _process_identity(binding['pid'])
    exe = os.stat('/proc/' + str(binding['pid']) + '/exe')
    admission.require((exe.st_dev, exe.st_ino) == (2112, 56329), 'server signal executable differs')
    pidfd = os.pidfd_open(binding['pid'], 0)
    try:
        poller = select.poll(); poller.register(pidfd, select.POLLIN)
        admission.require(not poller.poll(0) and jre._starttime(binding['pid']) == binding['starttime'],
                          'server exited before signal')
        _scope(); _inventory(daemon, owned)
        _event('server_stop_intent', binding=binding, signal='SIGTERM')
        signal.pidfd_send_signal(pidfd, signal.SIGTERM, None, 0)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            _scope(); _inventory(daemon, owned)
            events = poller.poll(100)
            if events:
                admission.require(len(events) == 1 and events[0][0] == pidfd and events[0][1] & select.POLLIN
                                  and not events[0][1] & (select.POLLERR | select.POLLNVAL),
                                  'pidfd exit event differs')
                row = admission._request('GET', path + '/json', daemon)
                if row.get('State', {}).get('Status') == 'exited':
                    final = jre._inspect(row, PROFILE, binding['id'], 'exited')
                    admission.require(final['StartedAt'] == binding['startedAt'] and final['ExitCode'] in (0, 143)
                                      and jre._timestamp(final['FinishedAt']) >= jre._timestamp(final['StartedAt'])
                                      and not _port_entries('/proc/self'), 'server stop evidence differs')
                    _event('server_stopped', binding=binding, exitCode=final['ExitCode'],
                           finishedAt=final['FinishedAt'], cgroupReleaseVerified=False)
                    return final
            time.sleep(.1)
        raise admission.AdmissionError('server stop deadline expired')
    finally:
        os.close(pidfd)


def run(profile, digest, daemon, timeout=60):
    """One scoped start/write/stop/restart/read/stop; no container is deleted."""
    profile, daemon = copy.deepcopy((profile, daemon))
    admission.require(profile == PROFILE and digest == admission._pin(PROFILE), 'H2 combined profile differs')
    admission.require(type(timeout) is int and 1 <= timeout <= 60, 'invalid phase timeout')
    admission.require(hasattr(os, 'pidfd_open') and hasattr(signal, 'pidfd_send_signal'), 'pidfd unavailable')
    admission._daemon_pin(daemon)
    with admission._lock():
        _scope(); jre._verify_resource(daemon); _network(daemon)
        admission._mounts(profile['config'])
        fixture = _closed_fixture()
        info = admission._request('GET', '/info', daemon)
        admission.require(isinstance(info, dict) and info.get('ID') == daemon['id']
                          and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        _inventory(daemon, [])
        jre._image(profile, daemon)
        protected = list(jre.MOUNTS.values()) + [JAR, CLIENT_SOURCE] + disk.PROTECTED_DATA
        _qdrant_check(daemon, protected)
        _ports_free()
        runtime = _runtime()
        _scope(); jre._verify_resource(daemon); _recheck_runtime(runtime); _closed_fixture(fixture)
        _qdrant_check(daemon, protected)
        _network(daemon); _ports_free(); _inventory(daemon, [])
        _scope(); jre._verify_resource(daemon)
        _event('server_create_intent', name=profile['name'])
        try:
            created = admission._request('POST', '/containers/create?name=' + quote(profile['name'], safe=''),
                                         daemon, profile['config'])
        except Exception as error:
            raise admission.AdmissionError('server create outcome unknown') from error
        cid = created.get('Id') if isinstance(created, dict) else None
        admission.require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid)
                          and cid not in (disk.GPU_ID, QDRANT_ID), 'server identity unknown')
        owned, binding, attempted, observed = [cid], None, set(), [None]
        _event('server_created', id=cid)
        try:
            path = '/containers/' + cid
            state = jre._inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'created')
            admission.require(state.get('StartedAt') == '0001-01-01T00:00:00Z'
                              and state.get('FinishedAt') == '0001-01-01T00:00:00Z', 'server already started')
            _inventory(daemon, owned); _scope(); jre._verify_resource(daemon)
            _recheck_runtime(runtime); _closed_fixture(fixture)
            _qdrant_check(daemon, protected)
            _network(daemon); _ports_free()
            _event('server_start_intent', id=cid, generation=1)
            admission._request('POST', path + '/start', daemon)
            binding = _wait_server(daemon, owned, cid, timeout, observed=lambda item: observed.__setitem__(0, item))
            _client('write', daemon, owned, binding, timeout)
            first = binding
            stopped = _signal_stop(daemon, owned, first, attempted, timeout)
            binding = None
            observed[0] = None
            _scope(); jre._verify_resource(daemon); _inventory(daemon, owned); _network(daemon); _ports_free()
            current = jre._inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'exited')
            admission.require(all(current.get(k) == stopped.get(k) for k in
                              ('ExitCode', 'StartedAt', 'FinishedAt', 'OOMKilled', 'Error')),
                              'stopped server changed before restart')
            file = (disk.FIXTURE / 'db' / 'fixture.mv.db').lstat()
            admission.require(stat.S_ISREG(file.st_mode) and (file.st_dev, file.st_ino, file.st_uid, file.st_gid,
                              file.st_nlink) == (2112, 56756, 10001, 10001, 1)
                              and 0 < file.st_size <= 64 * 1024 * 1024, 'closed fixture identity changed')
            _recheck_runtime(runtime)
            _qdrant_check(daemon)
            _event('server_start_intent', id=cid, generation=2)
            admission._request('POST', path + '/start', daemon)
            binding = _wait_server(daemon, owned, cid, timeout, previous=first,
                                   observed=lambda item: observed.__setitem__(0, item))
            _client('read', daemon, owned, binding, timeout)
            final = _signal_stop(daemon, owned, binding, attempted, timeout)
            binding = None
            _qdrant_check(daemon)
            _scope(); _inventory(daemon, owned)
            return {'serverId': cid, 'clientIds': owned[1:], 'finalExit': final['ExitCode'],
                    'runtimeBytesRead': runtime['bytesRead'], 'gpuRelease': 'unknown',
                    'cgroupReleaseVerified': False, 'diskQuotaVerified': False, 'containersRemoved': False}
        except Exception as error:
            current = binding if binding is not None else observed[0]
            if current is not None and (current['pid'], current['starttime']) not in attempted:
                try:
                    _signal_stop(daemon, owned, current, attempted, timeout)
                except Exception:
                    _event('failure_stop_unknown', serverId=cid)
            raise admission.AdmissionError('H2 combined server ' + cid + ' retained or outcome unknown') from error
