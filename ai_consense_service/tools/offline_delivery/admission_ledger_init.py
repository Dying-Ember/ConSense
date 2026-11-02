"""First ledger initialization candidate. It can only publish pending_init.

There is no implemented inventory verifier, clean transition, or recovery.
The calling operator must hold the shared lock and supply an independently
verified parent context; neither this module nor the lock controls root actors.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat

import admission_ledger as ledger
import container_admission as admission


SCOPE = 'ticket-02 admission ledger initialize attempt1 only'
OWNER = 'ticket-02'
NAME = 'admission-gate'
MARKER = '.admission-gate-init-attempt1.json'
PARENT = str(Path(ledger.DIRECTORY).parent)
FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC


def _scope():
    fd = os.open(ledger.STATE, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        admission.require(stat.S_ISREG(before.st_mode)
                          and ledger._identity(before) == ledger._identity(os.lstat(ledger.STATE)),
                          'coordinator state identity differs')
        raw = ledger._read_bounded(fd)
        after = os.fstat(fd)
        admission.require(ledger._identity(before) == ledger._identity(after) ==
                          ledger._identity(os.lstat(ledger.STATE))
                          and (before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                          (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'coordinator state changed')
        state = ledger._json(raw)
        admission.require(isinstance(state, dict) and state.get('coordinator') == ledger.COORDINATOR
                          and state.get('ticket01Gate') == 'closed'
                          and state.get('heavyTaskGate') == 'ordered-single-heavy-task'
                          and state.get('heavyTaskOwner') == OWNER and state.get('heavyTaskScope') == SCOPE,
                          'coordinator scope differs')
        return raw
    finally:
        os.close(fd)


def _open_parent():
    fd = os.open('/', FLAGS)
    try:
        table = admission._parse_mountinfo(Path('/proc/self/mountinfo').read_bytes())
        root = os.fstat(fd)
        mount = ledger._mount_id(fd)
        record = table.get(mount)
        admission.require(stat.S_ISDIR(root.st_mode) and root.st_uid == root.st_gid == 0
                          and not root.st_mode & 0o022 and record and record['filesystem'] == 'ext4'
                          and record['device'] == f'{os.major(root.st_dev)}:{os.minor(root.st_dev)}',
                          'untrusted root or mount')
        for part in PARENT.strip('/').split('/'):
            child = os.open(part, FLAGS, dir_fd=fd)
            try:
                info = os.fstat(child)
                named = os.stat(part, dir_fd=fd, follow_symlinks=False)
                admission.require(stat.S_ISDIR(info.st_mode) and info.st_uid == info.st_gid == 0
                                  and not info.st_mode & 0o022 and ledger._mount_id(child) == mount
                                  and ledger._identity(info) == ledger._identity(named), 'untrusted parent')
            except BaseException:
                os.close(child)
                raise
            os.close(fd)
            fd = child
        admission.require(ledger._identity(os.fstat(fd)) == ledger._identity(os.lstat(PARENT)),
                          'parent path changed')
        return fd
    except BaseException:
        os.close(fd)
        raise


def _parent_context(parent):
    environment = ledger._environment()
    fresh = _open_parent()
    try:
        held, live = os.fstat(parent), os.fstat(fresh)
        mount = ledger._mount_id(parent)
        admission.require(ledger._identity(held) == ledger._identity(live)
                          and mount == ledger._mount_id(fresh) and environment == ledger._environment(),
                          'parent context changed')
        return environment, ledger._identity(held), mount
    finally:
        os.close(fresh)


def _verify_inventory():
    raise admission.AdmissionError('trusted initialization inventory is not implemented')


def _absent(parent, name):
    try:
        os.stat(name, dir_fd=parent, follow_symlinks=False)
    except FileNotFoundError:
        return
    raise admission.AdmissionError('initialization target or marker already exists')


def _write_new(directory, name, raw):
    fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                 0o600, dir_fd=directory)
    try:
        info, parent = os.fstat(fd), os.fstat(directory)
        admission.require(stat.S_ISREG(info.st_mode) and info.st_uid == info.st_gid == 0
                          and info.st_nlink == 1 and stat.S_IMODE(info.st_mode) == 0o600
                          and info.st_dev == parent.st_dev and ledger._mount_id(fd) == ledger._mount_id(directory),
                          'initialization file identity differs')
        pos = 0
        while pos < len(raw):
            written = os.write(fd, raw[pos:])
            admission.require(written > 0, 'short initialization write')
            pos += written
        os.fsync(fd)
        admission.require(ledger._identity(info) == ledger._identity(os.stat(name, dir_fd=directory,
                                                                             follow_symlinks=False)),
                          'initialization file changed')
        return ledger._identity(info)
    finally:
        os.close(fd)


def _read_marker(parent, identity, raw):
    fd = os.open(MARKER, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=parent)
    try:
        before = os.fstat(fd)
        named = os.stat(MARKER, dir_fd=parent, follow_symlinks=False)
        admission.require(stat.S_ISREG(before.st_mode) and before.st_uid == before.st_gid == 0
                          and before.st_nlink == 1 and stat.S_IMODE(before.st_mode) == 0o600
                          and ledger._identity(before) == identity == ledger._identity(named)
                          and before.st_dev == os.fstat(parent).st_dev
                          and ledger._mount_id(fd) == ledger._mount_id(parent), 'marker identity differs')
        data = ledger._read_bounded(fd)
        after = os.fstat(fd)
        admission.require(data == raw and ledger._identity(after) == identity ==
                          ledger._identity(os.stat(MARKER, dir_fd=parent, follow_symlinks=False))
                          and (before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                          (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'marker changed')
    finally:
        os.close(fd)


def initialize(lock_fd, expected_context=None):
    """Create an attempt marker then pending_init; never marks the ledger clean."""
    ledger._expected_context(expected_context)
    ledger._held_lock(lock_fd)
    scope = _scope()
    parent = _open_parent()
    try:
        admission.require(_parent_context(parent) == expected_context, 'unexpected parent context')
        _absent(parent, NAME)
        _absent(parent, MARKER)
        inventory = _verify_inventory()
        admission.require(type(inventory) is bytes and len(inventory) <= 32768
                          and isinstance(ledger._json(inventory), dict), 'invalid inventory evidence')
        operation_id = secrets.token_hex(16)
        digest = hashlib.sha256(inventory).hexdigest()
        environment, identity, mount = expected_context
        boot, namespaces, mountinfo = environment
        marker = {'schema': 1, 'state': 'pending_init', 'operationId': operation_id,
                  'owner': OWNER, 'scope': SCOPE, 'inventorySha256': digest,
                  'inventory': ledger._json(inventory), 'boot': boot, 'namespaces': namespaces,
                  'mountinfoSha256': hashlib.sha256(mountinfo).hexdigest(), 'parent': identity, 'mountId': mount}
        marker_raw = json.dumps(marker, sort_keys=True, separators=(',', ':')).encode()
        admission.require(len(marker_raw) <= ledger.MAX_BYTES, 'initialization marker too large')
        admission.require(_scope() == scope and _parent_context(parent) == expected_context,
                          'scope or parent changed')
        ledger._held_lock(lock_fd)
        marker_id = _write_new(parent, MARKER, marker_raw)
        os.fsync(parent)
        _read_marker(parent, marker_id, marker_raw)
        admission.require(_scope() == scope and _parent_context(parent) == expected_context
                          and ledger._identity(os.stat(MARKER, dir_fd=parent, follow_symlinks=False)) == marker_id,
                          'initialization preconditions changed')
        ledger._held_lock(lock_fd)
        os.mkdir(NAME, 0o700, dir_fd=parent)
        os.fsync(parent)
        directory = ledger._open_directory()
        try:
            info = os.fstat(directory)
            named = os.stat(NAME, dir_fd=parent, follow_symlinks=False)
            directory_context = (environment, ledger._identity(info), mount)
            admission.require(ledger._identity(info) == ledger._identity(named)
                              and ledger._directory_context(directory) == directory_context
                              and ledger._mount_id(directory) == mount, 'new ledger directory differs')
            pending = {'schema': 1, 'state': 'pending_init', 'operationId': operation_id,
                       'owner': OWNER, 'scope': SCOPE, 'markerSha256': hashlib.sha256(marker_raw).hexdigest()}
            raw = json.dumps(pending, sort_keys=True, separators=(',', ':')).encode()
            admission.require(_scope() == scope and _parent_context(parent) == expected_context,
                              'scope or parent changed before pending')
            ledger._held_lock(lock_fd)
            _read_marker(parent, marker_id, marker_raw)
            _write_new(directory, 'gate.json', raw)
            os.fsync(directory)
            _read_marker(parent, marker_id, marker_raw)
            admission.require(ledger._read_gate(directory) == pending and _scope() == scope
                              and _parent_context(parent) == expected_context
                              and ledger._directory_context(directory) == directory_context,
                              'pending initialization readback differs')
            ledger._held_lock(lock_fd)
            return pending
        finally:
            os.close(directory)
    finally:
        os.close(parent)


def finish(*_args, **_kwargs):
    raise admission.AdmissionError('trusted initialization reconciliation is not implemented')


def reconcile(*_args, **_kwargs):
    raise admission.AdmissionError('trusted initialization reconciliation is not implemented')
