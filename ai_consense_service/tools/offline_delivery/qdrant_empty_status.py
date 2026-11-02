"""Read-only, non-authorizing catalogue observation for a dedicated test server."""
import os
from pathlib import Path
import re
import sqlite3
import stat


class StatusUnavailable(RuntimeError):
    pass


def _ledger_snapshot(path, server_identity):
    # External ownership and SQLite filesystem semantics are prerequisites.
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise StatusUnavailable('authority ledger unavailable')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    try:
        before = os.fstat(fd)
        header = os.read(fd, 20)
        if (not stat.S_ISREG(before.st_mode) or len(header) != 20 or header[:16] != b'SQLite format 3\x00'
                or header[18:20] != b'\x01\x01'):
            raise StatusUnavailable('authority ledger mode unavailable')
        db = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, isolation_level=None, timeout=0)
        try:
            db.execute('PRAGMA query_only=ON')
            db.execute('BEGIN')
            if db.execute('PRAGMA journal_mode').fetchone() != ('delete',):
                raise StatusUnavailable('authority ledger mode changed')
            if db.execute('SELECT server_identity FROM server_binding WHERE id=1').fetchone() != (server_identity,):
                raise StatusUnavailable('authority server binding differs')
            row = db.execute('SELECT state, token, operation_id, server_identity, collection, method, write_id, '
                             '(SELECT COUNT(*) FROM operations), (SELECT COUNT(*) FROM writes), '
                             "(SELECT COUNT(*) FROM operations WHERE status <> 'CLOSED'), "
                             "(SELECT COUNT(*) FROM writes WHERE status <> 'COMPLETED') "
                             'FROM authority WHERE id=1').fetchone()
            os.lseek(fd, 0, os.SEEK_SET)
            header_after = os.read(fd, 20)
            after = os.fstat(fd)
            named = path.stat(follow_symlinks=False)
            if (header_after != header or (before.st_dev, before.st_ino) != (after.st_dev, after.st_ino)
                    or (before.st_dev, before.st_ino) != (named.st_dev, named.st_ino)):
                raise StatusUnavailable('authority ledger identity or mode changed')
            db.execute('COMMIT')
        finally:
            db.close()
    finally:
        os.close(fd)
    if row is None or row[0] != 'READY' or row[-2:] != (0, 0):
        raise StatusUnavailable('authority state unavailable')
    return row


def inspect_empty_catalogue(ledger_path, server_identity, project_prefix, readonly_client_factory, identity_check):
    """Return booleans only; observation never authorizes writes."""
    if (type(server_identity) is not str or not server_identity or len(server_identity) > 256
            or type(project_prefix) is not str or not re.fullmatch(r'project_[0-9a-f]{16}_', project_prefix)
            or not callable(readonly_client_factory) or not callable(identity_check)):
        raise ValueError('status identity and project prefix required')
    try:
        path = Path(ledger_path)
        first = _ledger_snapshot(path, server_identity)
        if identity_check(server_identity) is not True:
            raise StatusUnavailable('server identity unavailable')
        client = readonly_client_factory(server_identity)
        try:
            collections = client.get_collections().collections
            aliases = client.get_aliases().aliases
            if (type(collections) is not list or type(aliases) is not list
                    or len(collections) > 10000 or len(aliases) > 10000
                    or any(type(getattr(row, 'name', None)) is not str for row in collections)
                    or any(type(getattr(row, 'alias_name', None)) is not str for row in aliases)):
                raise StatusUnavailable('catalogue unavailable')
            project_clear = (not any(row.name.startswith(project_prefix) for row in collections)
                             and not any(row.alias_name.startswith(project_prefix) for row in aliases))
            server_empty = not collections and not aliases
        finally:
            client.close()
        if identity_check(server_identity) is not True or _ledger_snapshot(path, server_identity) != first:
            raise StatusUnavailable('status identity or authority changed')
        return {'serverEmpty': server_empty, 'projectClear': project_clear, 'writesAttempted': False,
                'authorizesWrite': False}
    except Exception:
        raise StatusUnavailable('read-only status unavailable') from None
