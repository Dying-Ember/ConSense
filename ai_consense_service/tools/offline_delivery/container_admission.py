"""Dedicated-daemon ordinary container admission; all creators must share this lock.

This is a point-in-time Linux seam, not an enforcement boundary against clients
that can independently access the daemon. Replacement, build, init and cleanup
are intentionally unsupported.
"""
from contextlib import contextmanager
import copy
import hashlib
import http.client
import json
import os
from pathlib import Path
import posixpath
import re
import socket
import stat
import struct
import sys
import time
from urllib.parse import quote

import release_guard


SOCKET = '/run/consense-docker/docker.sock'
DATA_ROOT = '/srv/consense-docker/data'
LOCK = '/run/consense-docker/container-admission.lock'
GPU_CGROUP_PARENT_ROOT = '/sys/fs/cgroup/docker'
PROC_ROOT = '/proc'
ROLES = {'app', 'source', 'h2', 'qdrant', 'gpu'}
PORTS = {'app': {28680}, 'source': {28681, 28685}, 'h2': {28682},
         'qdrant': {28683}, 'gpu': {28684, 28771, 28772}}
APP_COMMANDS = {'java', 'find', 'sort', 'cmp', 'mktemp', 'cat', 'cut', 'sha256sum', 'grep', 'rm'}
INJECTION_ENV = {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTIONS',
                 'BASH_ENV', 'ENV', 'IFS', 'SHELLOPTS', 'BASHOPTS', 'CDPATH', 'GCONV_PATH',
                 'LOCPATH', 'NLSPATH', 'GLIBC_TUNABLES', 'CLASSPATH', 'PYTHONPATH', 'PYTHONHOME'}


class AdmissionError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise AdmissionError(message)


def _pin(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


@contextmanager
def _lock():
    import fcntl
    parent = Path(LOCK).parent
    for path in (parent, *parent.parents):
        info = path.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
                'untrusted lock directory')
    flags = os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open(LOCK, flags, 0o600)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                and stat.S_IMODE(info.st_mode) == 0o600, 'untrusted lock file')
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise AdmissionError('container admission is busy') from error
        live = os.lstat(LOCK)
        require((live.st_dev, live.st_ino) == (info.st_dev, info.st_ino), 'lock identity changed')
        yield fd
    finally:
        os.close(fd)


def _daemon_peer(expected):
    info = os.lstat(SOCKET)
    require(stat.S_ISSOCK(info.st_mode) and (info.st_dev, info.st_ino) ==
            (expected['socketDevice'], expected['socketInode']), 'daemon socket identity changed')
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        client.settimeout(5)
        client.connect(SOCKET)
        pid, uid, gid = struct.unpack('3i', client.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        require((pid, uid, gid) == (expected['pid'], expected['uid'], expected['gid']), 'daemon peer identity changed')
        proc = Path('/proc') / str(pid) / 'stat'
        line = proc.read_text()
        require(int(line[line.rfind(')') + 2:].split()[19]) == expected['startTicks'], 'daemon process identity changed')
        again = os.lstat(SOCKET)
        require((again.st_dev, again.st_ino) == (info.st_dev, info.st_ino), 'daemon socket changed')
        return client
    except BaseException:
        client.close()
        raise


def _request(method, path, expected, payload=None):
    client = _daemon_peer(expected)
    connection = http.client.HTTPConnection('localhost', timeout=5)
    connection.sock = client
    try:
        body = None if payload is None else json.dumps(payload, separators=(',', ':')).encode()
        connection.request(method, path, body=body, headers={'Content-Type': 'application/json'})
        response = connection.getresponse()
        data = response.read(4 * 1024 * 1024 + 1)
        require(200 <= response.status < 300 and len(data) <= 4 * 1024 * 1024, 'daemon request failed')
        return json.loads(data) if data else None
    finally:
        connection.close()


def _daemon_pin(expected):
    keys = {'id', 'socketDevice', 'socketInode', 'pid', 'startTicks', 'uid', 'gid'}
    require(isinstance(expected, dict) and set(expected) == keys and isinstance(expected['id'], str)
            and expected['id'] and all(type(expected[k]) is int and expected[k] >= 0 for k in keys - {'id'})
            and expected['pid'] > 0 and expected['startTicks'] > 0 and expected['uid'] == 0,
            'complete dedicated daemon identity required')


def _mounts(config):
    host = config['HostConfig']
    mounts = host['Mounts']
    require(isinstance(mounts, list), 'explicit mounts required')
    seen = set()
    for row in mounts:
        require(isinstance(row, dict) and set(row) == {'Type', 'Source', 'Target', 'ReadOnly'}
                and row['Type'] == 'bind' and isinstance(row['ReadOnly'], bool), 'invalid bind mount')
        source, target = row['Source'], row['Target']
        require(isinstance(source, str) and isinstance(target, str) and os.path.isabs(source)
                and posixpath.isabs(target) and not target.startswith('//') and os.path.normpath(source) == source
                and posixpath.normpath(target) == target and os.path.realpath(source) == source
                and target not in seen, 'invalid mount path')
        require(os.path.commonpath([source, SOCKET]) != source and target not in ('/', '/run', '/var/run')
                and not target.endswith('/docker.sock'), 'Docker socket or broad runtime mount forbidden')
        info = os.lstat(source)
        require(stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode), 'mount source must be a file or directory')
        seen.add(target)
    for row in mounts:
        if not row['ReadOnly']:
            for parent in mounts:
                require(not parent['ReadOnly'] or posixpath.commonpath([parent['Target'], row['Target']]) != parent['Target'],
                        'writable mount cannot cover a read-only mount')
    return mounts


def _profile(profile, digest):
    require(isinstance(profile, dict) and set(profile) == ({'role', 'name', 'ports', 'config', 'appBinding'}
            if profile.get('role') == 'app' else {'role', 'name', 'ports', 'config'})
            and isinstance(digest, str) and re.fullmatch('[a-f0-9]{64}', digest)
            and _pin(profile) == digest, 'verified profile required')
    role, name, ports, config = profile['role'], profile['name'], profile['ports'], profile['config']
    require(role in ROLES and isinstance(name, str) and re.fullmatch('consense-[a-z0-9][a-z0-9-]{0,63}', name)
            and isinstance(ports, list) and len(set(ports)) == len(ports)
            and all(type(p) is int and p in PORTS[role] for p in ports)
            and (role == 'gpu' or ports), 'invalid role, name or ports')
    require(isinstance(config, dict) and set(config) == {'Image', 'User', 'Entrypoint', 'Cmd', 'Env', 'Labels', 'HostConfig'}
            and re.fullmatch('sha256:[a-f0-9]{64}', config['Image']) and config['User'] == 'consense:consense'
            and all(isinstance(config[k], list) and all(isinstance(v, str) and v and '\x00' not in v
                    for v in config[k]) for k in ('Entrypoint', 'Cmd', 'Env'))
            and config['Entrypoint'] and config['Cmd'] and isinstance(config['Labels'], dict)
            and config['Labels'] == {'consense.role': role, 'consense.deployment': 'r1-20261029'}, 'invalid container config')
    host = config['HostConfig']
    fields = {'NetworkMode', 'ReadonlyRootfs', 'Privileged', 'CapDrop', 'SecurityOpt', 'Mounts', 'Memory', 'NanoCpus', 'PidsLimit'}
    require(isinstance(host, dict) and (set(host) == fields or role == 'gpu' and set(host) in
            (fields | {'Devices'}, fields | {'Devices', 'CgroupnsMode', 'CgroupParent'}))
            and host['NetworkMode'] in ('host', 'none')
            and (host['NetworkMode'] != 'none' or not ports)
            and (role != 'gpu' or host['NetworkMode'] != 'host' or 28684 in ports)
            and host['ReadonlyRootfs'] is True and host['Privileged'] is False and host['CapDrop'] == ['ALL']
            and host['SecurityOpt'] == ['no-new-privileges:true']
            and all(type(host[k]) is int and host[k] > 0 for k in ('Memory', 'NanoCpus'))
            and type(host['PidsLimit']) is int and host['PidsLimit'] >= 0,
            'unsafe host config')
    _gpu_device(host)
    _mounts(config)


def _gpu_device(host):
    if 'Devices' not in host:
        return None
    require(host['Devices'] == [{'PathOnHost': '/dev/dxg', 'PathInContainer': '/dev/dxg',
                                 'CgroupPermissions': 'rw'}], 'invalid GPU device')
    info = os.lstat('/dev/dxg')
    require(stat.S_ISCHR(info.st_mode), 'GPU device is not a character device')
    return info.st_dev, info.st_ino, info.st_rdev


def _app_binding(profile, expected_daemon):
    binding = profile['appBinding']
    require(isinstance(binding, dict) and set(binding) == {'root', 'manifest', 'manifestSha256', 'entry', 'jar', 'web', 'appFiles', 'execution'},
            'complete app binding required')
    for key in ('entry', 'jar', 'web', 'appFiles'):
        path = binding[key]
        require(isinstance(path, str) and re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9._/-]*', path)
                and all(part not in ('', '.', '..') for part in path.split('/')), 'invalid app binding path')
    try:
        receipt, files = release_guard.verify(binding['root'], binding['manifest'], binding['manifestSha256'], reject_hardlinks=True)
    except (OSError, UnicodeError, ValueError, TypeError) as error:
        raise AdmissionError('app release verification failed') from error
    require(receipt['root'] == binding['root'] and receipt['manifest'] == binding['manifest'],
            'app binding paths are not canonical')
    entry, jar, web, app_files = (binding[k] for k in ('entry', 'jar', 'web', 'appFiles'))
    web_paths = sorted(path for path in files if path.startswith(web + '/'))
    required = {entry, jar, web + '/index.html', app_files + '/web-files', app_files + '/sha256sums'}
    require(required.issubset(files) and entry != jar and not entry.startswith(web + '/')
            and not jar.startswith(web + '/') and not app_files.startswith(web + '/')
            and not entry.startswith(app_files + '/') and not jar.startswith(app_files + '/')
            and not web.startswith(app_files + '/') and web != app_files and web_paths,
            'app binding does not select distinct registered files')
    require(all(re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9._/-]*', path)
            and all(part not in ('', '.', '..') for part in path.split('/')) for path in web_paths),
            'invalid registered web path')
    expected_lists = {
        app_files + '/web-files': ''.join(path + '\n' for path in web_paths).encode(),
        app_files + '/sha256sums': ''.join(files[path] + '  ' + path + '\n' for path in sorted([jar, *web_paths])).encode(),
    }
    for path, expected in expected_lists.items():
        raw = Path(binding['root']).joinpath(*path.split('/')).read_bytes()
        require(hashlib.sha256(raw).hexdigest() == files[path] and raw == expected,
                'app file inventory differs from registered release')
    config = profile['config']
    require(config['Entrypoint'] == ['/bin/sh'] and config['Cmd'] == ['/runtime/release/' + entry],
            'app entrypoint differs from registered release')
    env = {}
    for item in config['Env']:
        key, sep, value = item.partition('=')
        require(sep and key and key not in env, 'invalid or duplicate app environment key')
        env[key] = value
    expected_env = {'APP_ARTIFACT_ROOT': '/runtime/release', 'APP_JAR': jar, 'APP_WEB_DIRECTORY': web,
                    'APP_FILE_MANIFEST': '/runtime/release/' + app_files}
    require(all(env.get(key) == value for key, value in expected_env.items()), 'app environment differs from registered release')
    require(not any(key in INJECTION_ENV or key.startswith('LD_') for key in env), 'executable injection environment forbidden')
    mounts = config['HostConfig']['Mounts']
    release_mounts = [m for m in mounts if m['Target'] == '/runtime/release']
    require(len(release_mounts) == 1 and release_mounts[0]['Source'] == binding['root']
            and release_mounts[0]['ReadOnly'] is True and not any(m['Target'].startswith('/runtime/release/') for m in mounts),
            'app release mount differs from registered release')
    runtime_targets = binding['execution'].get('path') if isinstance(binding['execution'], dict) else None
    require(isinstance(runtime_targets, list), 'complete app execution binding required')
    protected = [binding['root']]
    for directory in runtime_targets:
        if isinstance(directory, str):
            protected.extend(m['Source'] for m in mounts if directory == m['Target'] or directory.startswith(m['Target'] + '/'))
    for row in mounts:
        if not row['ReadOnly']:
            for root in protected:
                left, right = Path(root), Path(row['Source'])
                require(left != right and not left.is_relative_to(right) and not right.is_relative_to(left),
                        'writable source overlaps protected release or runtime')
    _verify_mount_isolation(protected, [m['Source'] for m in mounts if not m['ReadOnly']], expected_daemon)
    try:
        _app_execution(binding, mounts, env)
    except (OSError, UnicodeError, ValueError, TypeError) as error:
        raise AdmissionError('app execution verification failed') from error


def _app_execution(binding, mounts, env):
    execution = binding['execution']
    require(isinstance(execution, dict) and set(execution) == {'path', 'manifest', 'manifestSha256', 'scanBudget'},
            'complete app execution binding required')
    budget = execution['scanBudget']
    require(isinstance(budget, dict) and set(budget) == {'maxEntries', 'maxSeconds'}
            and type(budget['maxEntries']) is int and 1 <= budget['maxEntries'] <= 20000
            and type(budget['maxSeconds']) is int and 1 <= budget['maxSeconds'] <= 10,
            'bounded runtime metadata scan required')
    directories = execution['path']
    require(isinstance(directories, list) and directories and len(directories) <= 8
            and all(isinstance(d, str) and posixpath.isabs(d) and not d.startswith('//')
                    and posixpath.normpath(d) == d and ':' not in d for d in directories)
            and len(set(directories)) == len(directories) and env.get('PATH') == ':'.join(directories),
            'app PATH differs from registered runtime')
    manifest = Path(execution['manifest']).absolute()
    for path in (manifest, *manifest.parents):
        release_guard.no_link(path)
    manifest = manifest.resolve(strict=True)
    require(str(manifest) == execution['manifest'] and manifest.is_file()
            and manifest.lstat().st_nlink == 1
            and not any(manifest == Path(m['Source']) or manifest.is_relative_to(Path(m['Source'])) for m in mounts),
            'execution manifest must be outside mounted sources')
    raw = manifest.read_bytes()
    digest = execution['manifestSha256']
    require(isinstance(digest, str) and re.fullmatch('[a-f0-9]{64}', digest)
            and hashlib.sha256(raw).hexdigest() == digest, 'execution manifest SHA256 mismatch')
    try:
        registered = json.loads(raw.decode('utf-8'), object_pairs_hook=release_guard.unique_object)
    except (UnicodeError, ValueError) as error:
        raise AdmissionError('invalid execution manifest') from error
    require(isinstance(registered, dict) and set(registered) == {'commands'}
            and isinstance(registered['commands'], dict) and set(registered['commands']) == APP_COMMANDS,
            'complete app executable inventory required')
    sources = []
    runtime_roots = set()
    for directory in directories:
        candidates = [m for m in mounts if directory == m['Target'] or directory.startswith(m['Target'] + '/')]
        require(len(candidates) == 1 and candidates[0]['ReadOnly'] is True
                and candidates[0]['Target'] != '/runtime/release'
                and not any(m['Target'].startswith(directory + '/') for m in mounts),
                'PATH directory must have one unshadowed read-only runtime mount')
        mount = candidates[0]
        source, release = Path(mount['Source']), Path(binding['root'])
        require(source != release and not source.is_relative_to(release) and not release.is_relative_to(source),
                'PATH runtime mount cannot overlap the release tree')
        runtime_roots.add(source)
        relative = posixpath.relpath(directory, mount['Target'])
        path = Path(mount['Source'])
        if relative != '.':
            for part in relative.split('/'):
                path = path / part
                require(stat.S_ISDIR(release_guard.no_link(path).st_mode), 'PATH component is not a real directory')
        require(stat.S_ISDIR(release_guard.no_link(path).st_mode), 'PATH directory unavailable')
        sources.append((directory, path, set(os.listdir(path))))
    _scan_runtime_links(runtime_roots, budget)
    for name, item in registered['commands'].items():
        require(isinstance(item, dict) and set(item) == {'path', 'sha256'}
                and isinstance(item['path'], str) and isinstance(item['sha256'], str)
                and re.fullmatch('[a-f0-9]{64}', item['sha256']), 'invalid app executable identity')
        selected = next(((directory, path) for directory, path, names in sources if name in names), None)
        require(selected is not None and item['path'] == selected[0] + '/' + name, 'executable resolution differs from inventory')
        path = selected[1] / name
        info = release_guard.no_link(path)
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1
                and (os.name != 'posix' or _executable_for_app(info)),
                'app executable is not a runnable regular file')
        flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0)
        with os.fdopen(os.open(path, flags), 'rb') as stream:
            before = os.fstat(stream.fileno())
            require((before.st_dev, before.st_ino) == (info.st_dev, info.st_ino) and before.st_nlink == 1,
                    'app executable changed')
            sha = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                sha.update(block)
            after = os.fstat(stream.fileno())
            require((before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns) and after.st_nlink == 1,
                    'app executable changed')
        require(sha.hexdigest() == item['sha256'], 'app executable SHA256 mismatch')


def _executable_for_app(info):
    if info.st_uid == 10001:
        return bool(info.st_mode & 0o100)
    if info.st_gid == 10001:
        return bool(info.st_mode & 0o010)
    return bool(info.st_mode & 0o001)


def _scan_runtime_links(roots, budget):
    deadline = time.monotonic() + budget['maxSeconds']
    count = 0
    def visit(directory, device):
        nonlocal count
        before = release_guard.no_link(directory)
        require(stat.S_ISDIR(before.st_mode) and before.st_dev == device, 'runtime directory identity differs')
        with os.scandir(directory) as entries:
            for entry in entries:
                count += 1
                require(count <= budget['maxEntries'] and time.monotonic() <= deadline, 'runtime metadata scan budget exceeded')
                path = Path(entry.path)
                info = release_guard.no_link(path)
                require(info.st_dev == device, 'runtime mount boundary is not established')
                if stat.S_ISDIR(info.st_mode):
                    visit(path, device)
                else:
                    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, 'runtime file type or link count is unsafe')
        after = release_guard.no_link(directory)
        require((before.st_dev, before.st_ino, before.st_mtime_ns, before.st_ctime_ns) ==
                (after.st_dev, after.st_ino, after.st_mtime_ns, after.st_ctime_ns), 'runtime directory changed during scan')
        require(time.monotonic() <= deadline, 'runtime metadata scan budget exceeded')
    for root in roots:
        visit(root, release_guard.no_link(root).st_dev)


def _parse_mountinfo(raw):
    records = {}
    def decode(value):
        require(not re.search(r'\\(?!040|011|012|134)', value), 'invalid mountinfo escape')
        return re.sub(r'\\(040|011|012|134)', lambda m: chr(int(m.group(1), 8)), value)
    for line in os.fsdecode(raw).splitlines():
        sections = line.split(' - ', 1)
        require(len(sections) == 2, 'invalid mountinfo')
        fields = sections[0].split()
        filesystem = sections[1].split()
        require(len(fields) >= 6 and len(filesystem) >= 3 and fields[0].isdigit() and fields[1].isdigit()
                and re.fullmatch(r'\d+:\d+', fields[2]), 'invalid mountinfo identity')
        mount_id = int(fields[0])
        root, point = decode(fields[3]), decode(fields[4])
        namespace_root = filesystem[:2] == ['nsfs', 'nsfs'] and re.fullmatch(r'net:\[[0-9]+\]', root)
        require(mount_id not in records and (namespace_root or (posixpath.isabs(root) and posixpath.normpath(root) == root))
                and posixpath.isabs(point) and posixpath.normpath(point) == point, 'ambiguous mountinfo')
        records[mount_id] = {'device': fields[2], 'root': root, 'point': point, 'filesystem': filesystem[0]}
    require(records, 'empty mountinfo')
    return records


def _mount_table(expected_daemon):
    require(sys.platform.startswith('linux'), 'Linux mount visibility required')
    proc_stat = (Path('/proc') / str(expected_daemon['pid']) / 'stat').read_text()
    close = proc_stat.rfind(')')
    fields = proc_stat[close + 2:].split() if close >= 0 else []
    require(len(fields) > 19 and fields[19].isdigit() and int(fields[19]) == expected_daemon['startTicks'],
            'daemon process identity changed')
    own = os.stat('/proc/self/ns/mnt')
    peer = os.stat('/proc/' + str(expected_daemon['pid']) + '/ns/mnt')
    require((own.st_dev, own.st_ino) == (peer.st_dev, peer.st_ino), 'daemon mount namespace differs')
    own_root = os.stat('/proc/self/root')
    peer_root = os.stat('/proc/' + str(expected_daemon['pid']) + '/root')
    require((own_root.st_dev, own_root.st_ino) == (peer_root.st_dev, peer_root.st_ino), 'daemon filesystem root differs')
    raw = Path('/proc/self/mountinfo').read_bytes()
    return {'namespace': (own.st_dev, own.st_ino), 'root': (own_root.st_dev, own_root.st_ino),
            'raw': raw, 'records': _parse_mountinfo(raw)}


def _mount_ref(source, table):
    flags = os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open(source, flags)
    try:
        info, visible = os.fstat(fd), os.lstat(source)
        require((info.st_dev, info.st_ino) == (visible.st_dev, visible.st_ino), 'mount source identity changed')
        lines = (Path('/proc/self/fdinfo') / str(fd)).read_text().splitlines()
        ids = [line.split(':', 1)[1].strip() for line in lines if line.startswith('mnt_id:')]
        require(len(ids) == 1 and ids[0].isdigit() and int(ids[0]) in table['records'], 'mount ID unavailable')
        return _mount_backing(source, info, table['records'][int(ids[0])], table['records'])
    finally:
        os.close(fd)


def _mount_backing(source, info, record, records):
    require(record['filesystem'] in ('ext4', 'tmpfs')
            and record['device'] == str(os.major(info.st_dev)) + ':' + str(os.minor(info.st_dev))
            and posixpath.commonpath([source, record['point']]) == record['point'], 'mount source cannot be mapped')
    require(not any(row['point'] != source and posixpath.commonpath([source, row['point']]) == source
                    for row in records.values()), 'nested mount visibility is not established')
    backing = posixpath.normpath(posixpath.join(record['root'], posixpath.relpath(source, record['point'])))
    return {'device': record['device'], 'inode': info.st_ino, 'backing': backing}


def _verify_mount_isolation(protected, writable, expected_daemon):
    table = _mount_table(expected_daemon)
    refs = {source: _mount_ref(source, table) for source in set(protected + writable)}
    for protected_source in protected:
        for writable_source in writable:
            left, right = refs[protected_source], refs[writable_source]
            require(left['device'] != right['device'] or (left['inode'] != right['inode']
                    and posixpath.commonpath([left['backing'], right['backing']]) not in (left['backing'], right['backing'])),
                    'writable mount aliases a protected source')
    after = _mount_table(expected_daemon)
    require(table['namespace'] == after['namespace'] and table['root'] == after['root']
            and table['raw'] == after['raw'], 'mount visibility changed')


def _ports_available(ports):
    for port in ports:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            # Recent health connections leave TIME_WAIT sockets on the server port.
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('127.0.0.1', port))
            probe.listen(1)


def _process_identity(pid):
    fields = {}
    for line in (Path('/proc') / str(pid) / 'status').read_text().splitlines():
        if ':' in line:
            key, value = line.split(':', 1)
            fields[key] = value.split()
    require(fields.get('Uid', [None, None])[1] == '10001' and fields.get('Gid', [None, None])[1] == '10001'
            and fields.get('CapEff') == ['0000000000000000'] and fields.get('NoNewPrivs') == ['1'],
            'actual process security identity differs')


def _inspect(result, profile, cid, running):
    require(isinstance(result, dict) and result.get('Id') == cid and result.get('Name') == '/' + profile['name']
            and result.get('Image') == profile['config']['Image'], 'container identity differs')
    cfg, host = result.get('Config'), result.get('HostConfig')
    expected = profile['config']
    require(isinstance(cfg, dict) and all(cfg.get(k) == v for k, v in expected.items() if k != 'HostConfig')
            and isinstance(host, dict) and all(
                ((type(host.get(k)) is int and host[k] == v) or (type(v) is int and v == 0
                  and k in host and host[k] is None)) if k == 'PidsLimit' else host.get(k) == v
                for k, v in expected['HostConfig'].items() if k != 'Mounts')
            and all(k in host and not host[k] for k in ('CapAdd', 'Binds', 'DeviceRequests'))
            and not host.get('DeviceCgroupRules')
            and ('Devices' in host and (host['Devices'] == expected['HostConfig']['Devices']
                 if 'Devices' in expected['HostConfig'] else not host['Devices']))
            and host.get('PidMode') in ('', 'private'), 'container security profile differs')
    declared = host.get('Mounts')
    require(isinstance(declared, list) and all(isinstance(m, dict)
            and {'Type', 'Source', 'Target'}.issubset(m) and set(m).issubset({'Type', 'Source', 'Target', 'ReadOnly'})
            and type(m.get('ReadOnly', False)) is bool for m in declared), 'invalid inspected mount configuration')
    require(sorted((m['Type'], m['Source'], m['Target'], m.get('ReadOnly', False)) for m in declared)
            == sorted((m['Type'], m['Source'], m['Target'], m['ReadOnly']) for m in expected['HostConfig']['Mounts']),
            'inspected mount configuration differs')
    mounts = result.get('Mounts')
    require(isinstance(mounts, list) and sorted((m.get('Type'), m.get('Source'), m.get('Destination'), m.get('RW')) for m in mounts)
            == sorted((m['Type'], m['Source'], m['Target'], not m['ReadOnly']) for m in expected['HostConfig']['Mounts']),
            'actual mounts differ')
    state = result.get('State')
    require(isinstance(state, dict) and state.get('Status') == ('running' if running else 'created')
            and state.get('Running') is running and all(state.get(k) is False for k in ('Paused', 'Restarting', 'Dead'))
            and type(state.get('Pid')) is int and (state['Pid'] > 0 if running else state['Pid'] == 0),
            'container state differs')
    if running:
        _process_identity(state['Pid'])
    return state['Pid']


def _inventory(known, daemon, pending_gpu=None):
    rows = _request('GET', '/containers/json?all=1', daemon)
    require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows), 'invalid container inventory')
    ids = [row.get('Id') for row in rows]
    require(all(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid) for cid in ids)
            and len(set(ids)) == len(ids), 'invalid container IDs')
    require(isinstance(known, dict) and set(known).issubset(ROLES), 'explicit role inventory required')
    pending_id = None
    if pending_gpu is not None:
        require(isinstance(pending_gpu, dict) and set(pending_gpu) == {'id', 'profile', 'profileSha256'}
                and isinstance(pending_gpu['profile'], dict) and pending_gpu['profile'].get('role') == 'gpu'
                and 'gpu' not in known, 'one explicit pending GPU identity required')
        pending_id = pending_gpu['id']
        known = {**known, 'gpu': pending_gpu}
    recorded = {}
    for role, row in known.items():
        require(isinstance(row, dict) and set(row) == {'id', 'profile', 'profileSha256'}, 'incomplete role identity')
        _profile(row['profile'], row['profileSha256'])
        if role == 'gpu' and row['id'] == pending_id:
            _gpu_cgroup_parent(row['profile'])
        require(row['profile']['role'] == role and isinstance(row['id'], str)
                and re.fullmatch('[a-f0-9]{64}', row['id']) and row['id'] not in recorded, 'invalid role identity')
        recorded[row['id']] = row['profile']
    running = []
    for cid in ids:
        inspected = _request('GET', '/containers/' + cid + '/json', daemon)
        if cid in recorded:
            _inspect(inspected, recorded[cid], cid, cid != pending_id)
            if cid != pending_id:
                running.append(cid)
        else:
            state = inspected.get('State') if isinstance(inspected, dict) and inspected.get('Id') == cid else None
            require(isinstance(state, dict) and state.get('Status') == 'exited'
                    and state.get('Running') is False and type(state.get('Pid')) is int and state['Pid'] == 0
                    and all(state.get(k) is False for k in ('Paused', 'Restarting', 'Dead')),
                    'unknown or live container')
    require(len(running) <= 5 and set(running) == set(recorded) - ({pending_id} if pending_id else set())
            and (pending_id is None or pending_id in ids), 'unknown or missing running container')
    return running


def start(profile, profile_sha256, known_containers, expected_daemon, defer_start=False, pending_gpu=None):
    """Start one pinned ordinary role. Inputs come from an independently trusted caller."""
    profile, known_containers, expected_daemon, pending_gpu = copy.deepcopy(
        (profile, known_containers, expected_daemon, pending_gpu))
    _profile(profile, profile_sha256)
    _daemon_pin(expected_daemon)
    require(type(defer_start) is bool and (not defer_start or profile['role'] == 'gpu'),
            'only the GPU role may be created without starting')
    require(pending_gpu is None or profile['role'] == 'source' and not defer_start,
            'only source may start beside one prepared GPU')
    with _lock():
        info = _request('GET', '/info', expected_daemon)
        require(isinstance(info, dict) and info.get('ID') == expected_daemon['id']
                and info.get('DockerRootDir') == DATA_ROOT, 'dedicated daemon identity differs')
        before = _inventory(known_containers, expected_daemon, pending_gpu)
        require(profile['role'] not in known_containers and len(before) < (4 if pending_gpu else 5),
                'role or container slot unavailable')
        _ports_available(profile['ports'])
        _profile(profile, profile_sha256)
        gpu_device = _gpu_device(profile['config']['HostConfig'])
        require(not defer_start or gpu_device is not None, 'deferred GPU requires the pinned device')
        if defer_start:
            _gpu_config_path(profile)
            _gpu_cgroup_parent(profile)
        if profile['role'] == 'app':
            _app_binding(profile, expected_daemon)
        try:
            created = _request('POST', '/containers/create?name=' + quote(profile['name'], safe=''), expected_daemon, profile['config'])
        except Exception as error:
            raise AdmissionError('create outcome unknown; inspect the exact requested name before retry') from error
        cid = created.get('Id') if isinstance(created, dict) else None
        require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid) and cid not in before,
                'invalid created identity; inspect the exact requested name before retry')
        try:
            _inspect(_request('GET', '/containers/' + cid + '/json', expected_daemon), profile, cid, False)
            require(_gpu_device(profile['config']['HostConfig']) == gpu_device, 'GPU device changed before start')
            if defer_start:
                pending = {'id': cid, 'profile': profile, 'profileSha256': profile_sha256}
                require(set(_inventory(known_containers, expected_daemon, pending)) == set(before),
                        'container inventory changed during create')
                return {'role': 'gpu', 'id': cid, 'profileSha256': profile_sha256,
                        'gpuDevice': list(gpu_device), 'status': 'created'}
            _request('POST', '/containers/' + cid + '/start', expected_daemon)
            pid = _inspect(_request('GET', '/containers/' + cid + '/json', expected_daemon), profile, cid, True)
            require(_gpu_device(profile['config']['HostConfig']) == gpu_device, 'GPU device changed during start')
            now = dict(known_containers)
            now[profile['role']] = {'id': cid, 'profile': profile, 'profileSha256': profile_sha256}
            after = _inventory(now, expected_daemon, pending_gpu)
            require(set(after) == set(before) | {cid} and len(after) == len(before) + 1,
                    'container inventory changed during start')
            return {'role': profile['role'], 'id': cid, 'pid': pid, 'status': 'running'}
        except Exception as error:
            raise AdmissionError('created container ' + cid + ' retained; admission failed') from error


def _gpu_config_path(profile):
    config = profile['config']
    require(config['Entrypoint'][-2:] == ['/runtime/tools/offline_delivery/fixed_gpu_worker.py', '--config']
            and config['Cmd'] == ['/runtime/config/gpu.json'], 'fixed GPU worker command required')
    mounts = [item for item in config['HostConfig']['Mounts']
              if item['Type'] == 'bind' and item['Target'] == '/runtime/config' and item['ReadOnly'] is True]
    require(len(mounts) == 1, 'one read-only GPU config directory required')
    directory = os.lstat(mounts[0]['Source'])
    require(stat.S_ISDIR(directory.st_mode) and directory.st_uid == 0
            and directory.st_mode & 0o022 == 0, 'untrusted GPU config directory')
    return os.path.join(mounts[0]['Source'], 'gpu.json')


def _gpu_cgroup_parent(profile):
    host = profile['config']['HostConfig']
    name = profile['name']
    root = Path(GPU_CGROUP_PARENT_ROOT)
    parent = root / name
    require(host.get('CgroupnsMode') == 'host' and host.get('CgroupParent') == '/docker/' + name,
            'exact Docker cgroup namespace and parent required')
    mounts = [row for row in host['Mounts'] if row['Target'] == '/delegated']
    require(len(mounts) == 1 and mounts[0] == {'Type': 'bind', 'Source': str(parent),
            'Target': '/delegated', 'ReadOnly': False}, 'exact delegated cgroup bind required')
    base = root.lstat()
    info = parent.lstat()
    ancestor = (parent / 'cgroup.procs').lstat()
    require(stat.S_ISDIR(base.st_mode) and base.st_uid == 0 and base.st_mode & 0o022 == 0
            and (root / 'cgroup.controllers').is_file()
            and stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and info.st_mode & 0o022 == 0
            and stat.S_ISREG(ancestor.st_mode) and ancestor.st_uid == 0 and ancestor.st_mode & 0o022 == 0,
            'untrusted Docker cgroup parent')
    return parent


def _gpu_running_cgroup(profile, cid, pid):
    parent = _gpu_cgroup_parent(profile)
    require(Path(PROC_ROOT, str(pid), 'cgroup').read_text().strip() ==
            '0::/docker/' + profile['name'] + '/' + cid,
            'actual Docker child cgroup differs')
    child = parent / cid
    require(child.is_dir() and (child / 'cgroup.procs').is_file(), 'exact Docker child cgroup missing')
    return child


def _gpu_config_pin(profile, digest, cid):
    require(isinstance(digest, str) and re.fullmatch('[a-f0-9]{64}', digest), 'pinned GPU config SHA required')
    path = _gpu_config_path(profile)
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    except OSError as error:
        raise AdmissionError('GPU config unavailable') from error
    try:
        before = os.fstat(fd)
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and before.st_size <= 1024 * 1024
                and before.st_uid == 0 and not before.st_mode & 0o022,
                'unsafe GPU config file')
        raw = os.read(fd, 1024 * 1024 + 1)
        after, visible = os.fstat(fd), os.lstat(path)
        identity = lambda row: (row.st_dev, row.st_ino, row.st_size, row.st_mtime_ns,
                                row.st_ctime_ns, row.st_uid, row.st_gid, stat.S_IMODE(row.st_mode))
        require(len(raw) == before.st_size and identity(before) == identity(after) == identity(visible)
                and hashlib.sha256(raw).hexdigest() == digest, 'GPU config identity or SHA changed')
        try:
            config = json.loads(raw.decode('utf-8'), object_pairs_hook=release_guard.unique_object)
        except (UnicodeError, ValueError) as error:
            raise AdmissionError('GPU config is invalid') from error
        require(isinstance(config, dict) and config.get('containerId') == cid
                and config.get('cgroupRoot') == '/delegated/' + cid,
                'GPU config does not bind the exact created container and cgroup')
        return identity(after)
    finally:
        os.close(fd)


def start_created_gpu(profile, profile_sha256, created, config_sha256, known_containers, expected_daemon):
    """Start only the exact GPU object returned by a prior deferred create."""
    profile, created, known_containers, expected_daemon = copy.deepcopy(
        (profile, created, known_containers, expected_daemon))
    _profile(profile, profile_sha256)
    _daemon_pin(expected_daemon)
    require(profile['role'] == 'gpu' and isinstance(created, dict)
            and set(created) == {'role', 'id', 'profileSha256', 'gpuDevice', 'status'}
            and created['role'] == 'gpu' and created['status'] == 'created'
            and created['profileSha256'] == profile_sha256
            and isinstance(created['id'], str) and re.fullmatch('[a-f0-9]{64}', created['id'])
            and isinstance(created['gpuDevice'], list) and len(created['gpuDevice']) == 3
            and all(type(part) is int and part >= 0 for part in created['gpuDevice']),
            'exact deferred GPU receipt required')
    cid = created['id']
    pending = {'id': cid, 'profile': profile, 'profileSha256': profile_sha256}
    with _lock():
        info = _request('GET', '/info', expected_daemon)
        require(isinstance(info, dict) and info.get('ID') == expected_daemon['id']
                and info.get('DockerRootDir') == DATA_ROOT, 'dedicated daemon identity differs')
        before = _inventory(known_containers, expected_daemon, pending)
        require(len(before) < 5, 'container slot unavailable')
        _ports_available(profile['ports'])
        _profile(profile, profile_sha256)
        require(_gpu_device(profile['config']['HostConfig']) == tuple(created['gpuDevice']),
                'GPU device changed after create')
        _gpu_cgroup_parent(profile)
        _inspect(_request('GET', '/containers/' + cid + '/json', expected_daemon), profile, cid, False)
        config_identity = _gpu_config_pin(profile, config_sha256, cid)
        try:
            _request('POST', '/containers/' + cid + '/start', expected_daemon)
            pid = _inspect(_request('GET', '/containers/' + cid + '/json', expected_daemon), profile, cid, True)
            child = _gpu_running_cgroup(profile, cid, pid)
            os.chown(child, 10001, 10001)
            os.chown(child / 'cgroup.procs', 10001, 10001)
            require(child.stat().st_uid == 10001 and (child / 'cgroup.procs').stat().st_uid == 10001
                    and (child.parent / 'cgroup.procs').stat().st_uid == 0,
                    'exact Docker child delegation failed')
            require(_gpu_device(profile['config']['HostConfig']) == tuple(created['gpuDevice']),
                    'GPU device changed during start')
            require(_gpu_config_pin(profile, config_sha256, cid) == config_identity,
                    'GPU config changed during start')
            now = dict(known_containers)
            now['gpu'] = pending
            after = _inventory(now, expected_daemon)
            require(set(after) == set(before) | {cid} and len(after) == len(before) + 1,
                    'container inventory changed during start')
            return {'role': 'gpu', 'id': cid, 'pid': pid, 'status': 'running'}
        except Exception as error:
            raise AdmissionError('prepared container ' + cid + ' retained; start outcome unknown or admission failed') from error
