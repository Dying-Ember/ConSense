"""Narrow migration target adapter for the separately owned writer authority."""
from threading import Lock


def target_factory(authority, binding):
    """Return the one-shot, zero-argument factory expected by migration."""
    if (type(binding) is not dict or set(binding) != {'collection', 'operationId', 'serverIdentity'}
            or any(type(value) is not str or not value for value in binding.values())
            or not callable(getattr(authority, 'open_writer', None))):
        raise ValueError('writer authority and exact binding are required')
    frozen = dict(binding)
    lock = Lock()
    attempted = False

    def open_target():
        nonlocal attempted
        with lock:
            if attempted:
                raise RuntimeError('writer factory already attempted')
            attempted = True
        session = authority.open_writer(dict(frozen))
        if not all(callable(getattr(session, name, None)) for name in ('read', 'write', 'close')):
            close = getattr(session, 'close', None)
            if callable(close):
                close()
            raise ValueError('writer session is invalid')
        return _Target(session, frozen['collection'])
    return open_target


class _Target:
    def __init__(self, session, collection):
        self._session = session
        self._collection = collection
        self._lock = Lock()
        self._closed = False
        self._close_unknown = False

    def _call(self, kind, method, collection, **kwargs):
        with self._lock:
            allowed = {'read': ('collection_exists', 'get_collection', 'count', 'scroll'),
                       'write': ('create_collection', 'upsert')}
            if (method not in allowed.get(kind, ()) or self._closed or type(collection) is not str
                    or collection != self._collection):
                raise ValueError('writer session or collection differs')
            return getattr(self._session, kind)(method, collection, **kwargs)

    def collection_exists(self, collection):
        return self._call('read', 'collection_exists', collection)

    def get_collections(self):
        return self._catalogue('get_collections')

    def get_aliases(self):
        return self._catalogue('get_aliases')

    def _catalogue(self, method):
        with self._lock:
            if self._closed or method not in ('get_collections', 'get_aliases'):
                raise ValueError('writer session or catalogue read differs')
            return self._session.read(method)

    def get_collection(self, collection):
        return self._call('read', 'get_collection', collection)

    def count(self, collection, *, exact):
        return self._call('read', 'count', collection, exact=exact)

    def scroll(self, collection, *, limit, offset, with_payload, with_vectors):
        return self._call('read', 'scroll', collection, limit=limit, offset=offset,
                          with_payload=with_payload, with_vectors=with_vectors)

    def create_collection(self, collection, *, vectors_config):
        return self._call('write', 'create_collection', collection, vectors_config=vectors_config)

    def upsert(self, collection, *, points, wait):
        if wait is not True:
            raise ValueError('writer upsert requires wait=True')
        return self._call('write', 'upsert', collection, points=points, wait=True)

    def close(self):
        with self._lock:
            if self._closed:
                if self._close_unknown:
                    raise RuntimeError('writer release remains unknown')
                return
            self._closed = True
            try:
                self._session.close()
            except BaseException:
                self._close_unknown = True
                raise
