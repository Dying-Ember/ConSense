"""Durable, single-writer coordination core; deployment isolation is external."""
import os
from copy import deepcopy
from pathlib import Path
import sqlite3
import threading
from types import MappingProxyType
import uuid


class WriterUnavailable(RuntimeError):
    pass


def provision(path, server_identity):
    """Create a new authority ledger in an already trusted, exclusive directory."""
    if os.name != 'posix':
        raise WriterUnavailable('Linux ledger provisioning required')
    if type(server_identity) is not str or not server_identity or len(server_identity) > 256:
        raise WriterUnavailable('non-secret server identity required')
    path = Path(path)
    if not path.is_absolute() or not path.parent.is_dir():
        raise WriterUnavailable('trusted ledger parent required')
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    os.close(fd)
    db = sqlite3.connect(str(path), isolation_level=None, timeout=0)
    try:
        db.execute('PRAGMA synchronous=FULL')
        db.execute('CREATE TABLE server_binding (id INTEGER PRIMARY KEY CHECK (id=1), server_identity TEXT NOT NULL)')
        db.execute('INSERT INTO server_binding VALUES (1, ?)', (server_identity,))
        db.execute('CREATE TABLE authority (id INTEGER PRIMARY KEY CHECK (id=1), state TEXT NOT NULL, token TEXT, operation_id TEXT, server_identity TEXT, collection TEXT, method TEXT, write_id TEXT)')
        db.execute('CREATE TABLE operations (operation_id TEXT PRIMARY KEY, server_identity TEXT NOT NULL, collection TEXT NOT NULL, status TEXT NOT NULL)')
        db.execute('CREATE TABLE writes (write_id TEXT PRIMARY KEY, operation_id TEXT NOT NULL, method TEXT NOT NULL, status TEXT NOT NULL)')
        db.execute("INSERT INTO authority VALUES (1, 'READY', NULL, NULL, NULL, NULL, NULL, NULL)")
    finally:
        db.close()
    directory = os.open(path.parent, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


class Authority:
    """One owner per ledger, with injected identity and completion checks.

    The caller must enforce Server-wide writer isolation and protect this ledger.
    A persisted WRITING or INFLIGHT state is unresolved after a crash; never reset it.
    """
    def __init__(self, path, client_factory, identity_check, completion_check):
        if not all(callable(x) for x in (client_factory, identity_check, completion_check)):
            raise ValueError('checks and client factory required')
        self.path = Path(path)
        if not self.path.is_absolute() or not self.path.is_file() or self.path.is_symlink():
            raise WriterUnavailable('existing ledger required')
        self.client_factory = client_factory
        self.identity_check = identity_check
        self.completion_check = completion_check

    def _db(self):
        try:
            db = sqlite3.connect(self.path.as_uri() + '?mode=rw', uri=True, isolation_level=None, timeout=0)
            try:
                db.execute('PRAGMA synchronous=FULL')
            except BaseException:
                db.close()
                raise
        except sqlite3.Error:
            raise WriterUnavailable('writer ledger unavailable') from None
        return db

    def open_writer(self, binding):
        if (type(binding) is not dict or set(binding) != {'collection', 'operationId', 'serverIdentity'}
                or any(type(binding[k]) is not str or not binding[k] or len(binding[k]) > 256 for k in binding)):
            raise WriterUnavailable('invalid writer binding')
        binding = dict(binding)
        token = uuid.uuid4().hex
        db = self._db()
        try:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT server_identity FROM server_binding WHERE id=1').fetchone() != (binding['serverIdentity'],):
                raise WriterUnavailable('authority server binding differs')
            row = db.execute('SELECT state FROM authority WHERE id=1').fetchone()
            if row != ('READY',):
                raise WriterUnavailable('writer state unresolved or busy')
            db.execute('INSERT INTO operations VALUES (?, ?, ?, ?)',
                       (binding['operationId'], binding['serverIdentity'], binding['collection'], 'WRITING'))
            db.execute("UPDATE authority SET state='WRITING', token=?, operation_id=?, server_identity=?, collection=?, method=NULL, write_id=NULL WHERE id=1",
                       (token, binding['operationId'], binding['serverIdentity'], binding['collection']))
            db.execute('COMMIT')
        except sqlite3.Error:
            if db.in_transaction:
                db.execute('ROLLBACK')
            raise WriterUnavailable('writer ledger unavailable') from None
        except BaseException:
            if db.in_transaction:
                db.execute('ROLLBACK')
            raise
        finally:
            db.close()
        session = _Session(self, binding, token)
        try:
            if self.identity_check(dict(binding)) is not True:
                raise WriterUnavailable('server identity changed')
            session.client = self.client_factory(dict(binding))
            if session.client is None:
                raise WriterUnavailable('writer client unavailable')
            return session
        except BaseException:
            session._unknown()
            raise WriterUnavailable('writer opening failed; outcome unresolved') from None


class _Session:
    READS = frozenset(('collection_exists', 'get_collection', 'count', 'scroll'))
    CATALOGUE_READS = frozenset(('get_collections', 'get_aliases'))
    WRITES = frozenset(('create_collection', 'upsert'))

    def __init__(self, authority, binding, token):
        self.authority, self.binding, self.token = authority, MappingProxyType(dict(binding)), token
        self.client = None
        self.closed = False
        self.lock = threading.RLock()

    def _transition(self, old, new, method=None, *, check_binding=True):
        db = self.authority._db()
        try:
            db.execute('BEGIN IMMEDIATE')
            if (check_binding and db.execute('SELECT server_identity FROM server_binding WHERE id=1').fetchone()
                    != (self.binding['serverIdentity'],)):
                raise WriterUnavailable('authority server binding changed')
            row = db.execute('SELECT operation_id, method, write_id, server_identity, collection FROM authority WHERE id=1 AND token=? AND state=?',
                             (self.token, old)).fetchone()
            if row is None:
                raise WriterUnavailable('writer ownership or state changed')
            operation_id, previous_method, write_id, server_identity, collection = row
            if check_binding and (operation_id, server_identity, collection) != (
                    self.binding['operationId'], self.binding['serverIdentity'], self.binding['collection']):
                raise WriterUnavailable('writer binding changed')
            operation = db.execute('SELECT server_identity, collection, status FROM operations WHERE operation_id=?',
                                   (self.binding['operationId'],)).fetchone()
            if check_binding and operation != (self.binding['serverIdentity'], self.binding['collection'], 'WRITING'):
                raise WriterUnavailable('writer operation binding changed')
            if old == 'WRITING' and new == 'INFLIGHT':
                write_id = uuid.uuid4().hex
                db.execute('INSERT INTO writes VALUES (?, ?, ?, ?)', (write_id, operation_id, method, 'INFLIGHT'))
            elif old == 'INFLIGHT':
                status = 'COMPLETED' if new == 'WRITING' else 'UNKNOWN'
                if db.execute('UPDATE writes SET status=? WHERE write_id=? AND status=?',
                              (status, write_id, 'INFLIGHT')).rowcount != 1:
                    raise WriterUnavailable('write intent changed')
            if new == 'UNKNOWN':
                method = previous_method
            elif new != 'INFLIGHT':
                method, write_id = None, None
            if new in ('UNKNOWN', 'READY'):
                status = 'UNKNOWN' if new == 'UNKNOWN' else 'CLOSED'
                if db.execute('UPDATE operations SET status=? WHERE operation_id=? AND status=?',
                              (status, self.binding['operationId'], 'WRITING')).rowcount != 1:
                    raise WriterUnavailable('writer operation changed')
            changed = db.execute('UPDATE authority SET state=?, method=?, write_id=? WHERE id=1 AND token=? AND state=?',
                                 (new, method, write_id, self.token, old)).rowcount
            if changed != 1:
                raise WriterUnavailable('writer ownership or state changed')
            db.execute('COMMIT')
            return write_id
        except BaseException:
            if db.in_transaction:
                db.execute('ROLLBACK')
            raise
        finally:
            db.close()

    def _unknown(self):
        try:
            for old in ('WRITING', 'INFLIGHT'):
                try:
                    self._transition(old, 'UNKNOWN', check_binding=False)
                    return
                except WriterUnavailable:
                    continue
        except BaseException:
            pass  # The existing non-ready state still blocks a new writer.

    def _owner(self):
        db = self.authority._db()
        try:
            db.execute('BEGIN')
            if db.execute('SELECT server_identity FROM server_binding WHERE id=1').fetchone() != (self.binding['serverIdentity'],):
                raise WriterUnavailable('authority server binding changed')
            row = db.execute('SELECT state, token, operation_id, server_identity, collection FROM authority WHERE id=1').fetchone()
            if row != ('WRITING', self.token, self.binding['operationId'], self.binding['serverIdentity'], self.binding['collection']):
                raise WriterUnavailable('writer ownership or state changed')
            operation = db.execute('SELECT server_identity, collection, status FROM operations WHERE operation_id=?',
                                   (self.binding['operationId'],)).fetchone()
            if operation != (self.binding['serverIdentity'], self.binding['collection'], 'WRITING'):
                raise WriterUnavailable('writer operation binding changed')
            db.execute('COMMIT')
        finally:
            db.close()

    def _check(self, method, args, kwargs, allowed):
        if self.closed or method not in allowed:
            raise WriterUnavailable('unsupported or closed writer')
        self._owner()
        if method in self.CATALOGUE_READS:
            if args or kwargs:
                raise WriterUnavailable('catalogue reads require no arguments')
        else:
            collection = args[0] if args else kwargs.get('collection_name')
            if collection != self.binding['collection']:
                raise WriterUnavailable('collection differs from binding')
        if method == 'upsert' and kwargs.get('wait') is not True:
            raise WriterUnavailable('upsert must wait')
        if self.authority.identity_check(dict(self.binding)) is not True:
            raise WriterUnavailable('server identity or isolation changed')

    def read(self, method, *args, **kwargs):
        with self.lock:
            try:
                self._check(method, args, kwargs, self.READS | self.CATALOGUE_READS)
                result = getattr(self.client, method)(*args, **kwargs)
                if self.authority.identity_check(dict(self.binding)) is not True:
                    raise WriterUnavailable('server identity changed')
                self._owner()
                return result
            except BaseException:
                self._unknown()
                raise WriterUnavailable('writer read failed; state unresolved') from None

    def write(self, method, *args, **kwargs):
        with self.lock:
            try:
                self._check(method, args, kwargs, self.WRITES)
                request_args, request_kwargs = deepcopy((args, kwargs))
                call_args, call_kwargs = deepcopy((request_args, request_kwargs))
                write_id = self._transition('WRITING', 'INFLIGHT', method)
                if self.authority.identity_check(dict(self.binding)) is not True:
                    raise WriterUnavailable('server identity changed before write')
                result = getattr(self.client, method)(*call_args, **call_kwargs)
                if (self.authority.identity_check(dict(self.binding)) is not True
                        or self.authority.completion_check(dict(self.binding), write_id, method,
                                                           request_args, request_kwargs, result) is not True):
                    raise WriterUnavailable('write completion unverified')
                self._transition('INFLIGHT', 'WRITING')
                return result
            except BaseException:
                self._unknown()
                raise WriterUnavailable('writer operation failed; outcome unresolved') from None

    def close(self):
        with self.lock:
            if self.closed:
                raise WriterUnavailable('writer already closed')
            self.closed = True
            try:
                self.client.close()
                if self.authority.identity_check(dict(self.binding)) is not True:
                    raise WriterUnavailable('server identity changed at close')
                self._transition('WRITING', 'READY')
            except BaseException:
                self._unknown()
                raise WriterUnavailable('writer close failed; state unresolved') from None
