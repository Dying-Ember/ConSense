"""Read a verified Qdrant Local collection in bounded pages without loading it."""
import io
import json
import pickle
import sqlite3
from pathlib import Path
from types import SimpleNamespace

from qdrant_client import models


class _PointOnly(pickle.Unpickler):
    def find_class(self, module, name):
        if (module, name) == ('qdrant_client.http.models.models', 'PointStruct'):
            return models.PointStruct
        raise pickle.UnpicklingError('Unexpected stored point class')


class ColdLocalReader:
    """Narrow read interface used by the existing sealed-index verifier."""
    def __init__(self, path, collection, checkpoint=lambda: None):
        self.collection = collection
        self.checkpoint = checkpoint
        root = Path(path)
        self.checkpoint()
        meta = json.loads((root / 'meta.json').read_bytes())
        if set(meta) != {'collections', 'aliases'} or set(meta['collections']) != {collection} or meta['aliases']:
            raise ValueError('Exactly one unaliased Local collection is required')
        config = meta['collections'][collection]
        self.vectors = models.VectorParams.model_validate(config['vectors'])
        if config.get('sparse_vectors'):
            raise ValueError('Sparse vectors are not supported')
        dbpath = root / 'collection' / collection / 'storage.sqlite'
        self.db = sqlite3.connect(dbpath.resolve().as_uri() + '?mode=ro', uri=True, timeout=0)
        try:
            self.db.execute('PRAGMA query_only = ON')
            self.db.execute('BEGIN')
            self.db.execute('SELECT COUNT(*) FROM points').fetchone()
            self.checkpoint()
        except BaseException:
            self.db.close()
            raise

    def collection_exists(self, collection):
        self.checkpoint()
        return collection == self.collection

    def get_collection(self, collection):
        if not self.collection_exists(collection):
            raise ValueError('Unknown collection')
        return SimpleNamespace(config=SimpleNamespace(params=SimpleNamespace(vectors=self.vectors)))

    def count(self, collection, exact):
        if not exact or not self.collection_exists(collection):
            raise ValueError('Exact count for the selected collection is required')
        result = self.db.execute('SELECT COUNT(*) FROM points').fetchone()[0]
        self.checkpoint()
        return SimpleNamespace(count=result)

    def scroll(self, collection, *, limit, offset=None, with_payload=True, with_vectors=True):
        if not self.collection_exists(collection) or type(limit) is not int or not 1 <= limit <= 256:
            raise ValueError('Selected collection and bounded page are required')
        if (offset is not None and (type(offset) is not int or offset <= 0)) or not with_payload or not with_vectors:
            raise ValueError('Full point and valid cursor are required')
        rows = self.db.execute('SELECT rowid, point FROM points WHERE rowid > ? ORDER BY rowid LIMIT ?',
                               (offset or 0, limit + 1)).fetchall()
        more = len(rows) > limit
        result = []
        for rowid, blob in rows[:limit]:
            self.checkpoint()
            point = _PointOnly(io.BytesIO(blob)).load()
            if type(point) is not models.PointStruct:
                raise ValueError('Stored point has unexpected type')
            result.append(point)
        self.checkpoint()
        return result, rows[limit - 1][0] if more else None

    def close(self):
        self.db.close()
