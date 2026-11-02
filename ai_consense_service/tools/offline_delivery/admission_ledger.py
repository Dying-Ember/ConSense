"""Candidate crash ledger for the single ticket-04 probe; not wired to launchers.

The ledger must already exist on the dedicated ext4 filesystem. Initialization,
completion and recovery require a separately reviewed implementation. This is
an advisory boundary for cooperating, trusted root processes, not a defense
against privileged writers or an atomic lease on the Windows state file.
"""
import errno
import json
import os
from pathlib import Path
import re
import secrets
import stat

import container_admission as admission
import release_guard


DIRECTORY = '/srv/consense-rehearsal-20261029/evidence/local01/admission-gate'
STATE = '/mnt/d/R&D/ConSense/.local/container-rehearsal-20261029/parallel-state.json'
SCOPE = 'ticket-04 CPU-only cgroup probe only'
OWNER = 'ticket-04'
COORDINATOR = '01a18801-fddb-7490-87fa-3a51352d8c75'
MAX_BYTES = 65536


def _identity(info):
    return info.st_dev, info.st_ino


def _read_bounded(fd):
    os.lseek(fd, 0, os.SEEK_SET)
    chunks, size = [], 0
    while size <= MAX_BYTES:
        block = os.read(fd, min(8192, MAX_BYTES + 1 - size))
        if not block:
            break
        chunks.append(block)
        size += len(block)
    admission.require(size <= MAX_BYTES, 'ledger input too large')
    return b''.join(chunks)


def _json(raw):
    try:
        return json.loads(raw.decode('utf-8'), object_pairs_hook=release_guard.unique_object)
    except (ValueError, UnicodeError) as error:
        raise admission.AdmissionError('invalid ledger JSON') from error


def _exclusive_owned(info):
    matches = 0
    for line in Path('/proc/locks').read_text().splitlines():
        fields = line.split()
        if len(fields) < 8 or fields[1:4] != ['FLOCK', 'ADVISORY', 'WRITE']:
            continue
        device = fields[5].split(':')
        try:
            same = (len(device) == 3 and int(fields[4]) == os.getpid()
                    and int(device[0], 16) == os.major(info.st_dev)
                    and int(device[1], 16) == os.minor(info.st_dev)
                    and int(device[2]) == info.st_ino and fields[6:8] == ['0', 'EOF'])
        except ValueError:
            continue
        matches += int(same)
    admission.require(matches == 1, 'current process does not own the exclusive lock')


def _held_lock(fd):
    import fcntl

    admission.require(type(fd) is int and fd >= 0, 'lock FD required')
    held = os.fstat(fd)
    live = os.lstat(admission.LOCK)
    admission.require(stat.S_ISREG(held.st_mode) and held.st_uid == 0 and held.st_gid == 0
                      and held.st_nlink == 1 and stat.S_IMODE(held.st_mode) == 0o600
                      and _identity(held) == _identity(live)
                      and fcntl.fcntl(fd, fcntl.F_GETFD) & fcntl.FD_CLOEXEC,
                      'lock identity or descriptor differs')
    _exclusive_owned(held)
    independent = os.open(admission.LOCK, os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        admission.require(_identity(os.fstat(independent)) == _identity(held), 'lock was replaced')
        try:
            fcntl.flock(independent, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            admission.require(error.errno in (errno.EACCES, errno.EAGAIN), 'lock observation failed')
        else:
            fcntl.flock(independent, fcntl.LOCK_UN)
            raise admission.AdmissionError('lock is not held')
        # An independent description conflicts; the supplied description must
        # itself be the owner, rather than another descriptor in this process.
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise admission.AdmissionError('supplied FD does not hold lock') from error
        admission.require(_identity(os.lstat(admission.LOCK)) == _identity(held), 'lock changed')
        _exclusive_owned(held)
    finally:
        os.close(independent)


def _mount_id(fd):
    lines = (Path('/proc/self/fdinfo') / str(fd)).read_text().splitlines()
    ids = [line.split(':', 1)[1].strip() for line in lines if line.startswith('mnt_id:')]
    admission.require(len(ids) == 1 and ids[0].isdigit(), 'mount identity unavailable')
    return int(ids[0])


def _open_directory():
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open('/', flags)
    try:
        mounts = admission._parse_mountinfo(Path('/proc/self/mountinfo').read_bytes())
        root = os.fstat(fd)
        admission.require(stat.S_ISDIR(root.st_mode) and root.st_uid == 0 and root.st_gid == 0
                          and not root.st_mode & 0o022, 'untrusted filesystem root')
        root_mount = _mount_id(fd)
        record = mounts.get(root_mount)
        admission.require(record and record['filesystem'] == 'ext4'
                          and record['device'] == f'{os.major(root.st_dev)}:{os.minor(root.st_dev)}',
                          'filesystem root is not verified ext4')
        for part in DIRECTORY.strip('/').split('/'):
            child = os.open(part, flags, dir_fd=fd)
            try:
                info = os.fstat(child)
                named = os.stat(part, dir_fd=fd, follow_symlinks=False)
                admission.require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and info.st_gid == 0
                                  and not info.st_mode & 0o022 and _identity(info) == _identity(named),
                                  'untrusted ledger ancestor')
                mount = _mount_id(child)
                record = mounts.get(mount)
                admission.require(mount == root_mount and record and record['filesystem'] == 'ext4'
                                  and record['device'] == f'{os.major(info.st_dev)}:{os.minor(info.st_dev)}',
                                  'ledger mount is not verified ext4')
            except BaseException:
                os.close(child)
                raise
            os.close(fd)
            fd = child
        info = os.fstat(fd)
        admission.require(stat.S_IMODE(info.st_mode) == 0o700
                          and _identity(info) == _identity(os.lstat(DIRECTORY)), 'ledger directory differs')
        return fd
    except BaseException:
        os.close(fd)
        raise


def _environment():
    boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    admission.require(re.fullmatch(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}', boot) is not None,
                      'boot identity unavailable')
    namespaces = tuple(_identity(os.stat('/proc/self/ns/' + name))
                       for name in ('mnt', 'user', 'pid', 'cgroup', 'time'))
    return boot, namespaces, Path('/proc/self/mountinfo').read_bytes()


def _directory_context(directory):
    environment = _environment()
    fresh = _open_directory()
    try:
        held = os.fstat(directory)
        live = os.fstat(fresh)
        admission.require(_identity(held) == _identity(live) and _mount_id(directory) == _mount_id(fresh),
                          'ledger directory path or mount changed')
        admission.require(environment == _environment(), 'ledger environment changed')
        return environment, _identity(held), _mount_id(directory)
    finally:
        os.close(fresh)


def _read_gate(directory):
    admission.require(os.listdir(directory) == ['gate.json'], 'ledger contains missing or unknown entries')
    fd = os.open('gate.json', os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=directory)
    try:
        before = os.fstat(fd)
        named = os.stat('gate.json', dir_fd=directory, follow_symlinks=False)
        admission.require(stat.S_ISREG(before.st_mode) and before.st_uid == 0 and before.st_gid == 0
                          and before.st_nlink == 1 and stat.S_IMODE(before.st_mode) == 0o600
                          and before.st_dev == os.fstat(directory).st_dev and _mount_id(fd) == _mount_id(directory)
                          and _identity(before) == _identity(named), 'ledger identity differs')
        raw = _read_bounded(fd)
        after = os.fstat(fd)
        admission.require(_identity(before) == _identity(after) == _identity(os.stat('gate.json', dir_fd=directory, follow_symlinks=False))
                          and (before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                          == (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'ledger changed')
        return _json(raw)
    finally:
        os.close(fd)


def _scope():
    fd = os.open(STATE, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        admission.require(stat.S_ISREG(before.st_mode) and _identity(before) == _identity(os.lstat(STATE)),
                          'coordinator state identity differs')
        raw = _read_bounded(fd)
        after = os.fstat(fd)
        admission.require(_identity(before) == _identity(after) == _identity(os.lstat(STATE))
                          and (before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                          == (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'coordinator state changed')
        value = _json(raw)
        admission.require(isinstance(value, dict) and value.get('coordinator') == COORDINATOR
                          and value.get('ticket01Gate') == 'closed'
                          and value.get('heavyTaskGate') == 'ordered-single-heavy-task'
                          and value.get('heavyTaskOwner') == OWNER and value.get('heavyTaskScope') == SCOPE,
                          'coordinator scope differs')
        return raw
    finally:
        os.close(fd)


def _expected_context(value):
    def identity(item):
        return (type(item) is tuple and len(item) == 2 and type(item[0]) is int and item[0] >= 0
                and type(item[1]) is int and item[1] > 0)

    admission.require(type(value) is tuple and len(value) == 3, 'trusted expected context required')
    environment, directory, mount = value
    admission.require(type(environment) is tuple and len(environment) == 3, 'invalid expected environment')
    boot, namespaces, mountinfo = environment
    admission.require(isinstance(boot, str)
                      and re.fullmatch(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}', boot) is not None
                      and type(namespaces) is tuple and len(namespaces) == 5 and all(identity(n) for n in namespaces)
                      and type(mountinfo) is bytes and 0 < len(mountinfo) <= 4 * 1024 * 1024
                      and identity(directory) and type(mount) is int and mount > 0,
                      'invalid expected context')


def begin(lock_fd, expected_context=None):
    """Persist pending only against an independently attested outer context.

    The trusted outer supplying that context is not implemented or wired here.
    """
    _expected_context(expected_context)
    _held_lock(lock_fd)
    scope = _scope()
    directory = _open_directory()
    try:
        admission.require(_directory_context(directory) == expected_context, 'ledger context differs before pending')
        current = _read_gate(directory)
        admission.require(isinstance(current, dict) and set(current) == {'schema', 'generation', 'state'}
                          and type(current['schema']) is int and current['schema'] == 1 and type(current['generation']) is int
                          and 0 <= current['generation'] < 2**63 - 1 and current['state'] == 'clean',
                          'ledger is not clean')
        pending = {'schema': 1, 'generation': current['generation'] + 1, 'state': 'pending',
                   'operationId': secrets.token_hex(16), 'owner': OWNER, 'scope': SCOPE}
        raw = json.dumps(pending, sort_keys=True, separators=(',', ':')).encode('utf-8')
        name = '.pending-' + pending['operationId']
        fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                     0o600, dir_fd=directory)
        try:
            info = os.fstat(fd)
            admission.require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_gid == 0
                              and info.st_nlink == 1 and stat.S_IMODE(info.st_mode) == 0o600
                              and info.st_dev == os.fstat(directory).st_dev and _mount_id(fd) == _mount_id(directory),
                              'pending file identity differs')
            count = 0
            while count < len(raw):
                written = os.write(fd, raw[count:])
                admission.require(written > 0, 'short ledger write')
                count += written
            os.fsync(fd)
            admission.require(_identity(info) == _identity(os.stat(name, dir_fd=directory, follow_symlinks=False)),
                              'pending file changed')
        finally:
            os.close(fd)
        admission.require(_scope() == scope, 'coordinator scope changed')
        _held_lock(lock_fd)
        admission.require(_directory_context(directory) == expected_context, 'ledger context changed before replace')
        os.replace(name, 'gate.json', src_dir_fd=directory, dst_dir_fd=directory)
        os.fsync(directory)
        admission.require(_read_gate(directory) == pending and _scope() == scope, 'pending readback differs')
        _held_lock(lock_fd)
        admission.require(_directory_context(directory) == expected_context, 'ledger context changed before return')
        return pending
    finally:
        os.close(directory)


def finish(*_args, **_kwargs):
    raise admission.AdmissionError('role-specific completion and recovery are not implemented')
