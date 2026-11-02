import json
import hashlib
import pickle
import sqlite3
import tempfile
import unittest
from pathlib import Path

from qdrant_client import QdrantClient, models
from index_integrity import inspect_points, verify_reuse
from migrate_sealed_collections import migrate_collection, prepare_runtime_tree, transfer_with_journal
from sealed_local_stream import ColdLocalReader
from token_windows import digest


class ColdLocalStreamTest(unittest.TestCase):
    def test_existing_journal_transfer_uses_streamed_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source_dir = root / 'registered'
            collection = 'selected'
            signature = {'model': 'synthetic'}
            windows = [{'id': f'00000000-0000-0000-0000-{i:012d}', 'parentId': 'p'} for i in range(3)]
            writer = QdrantClient(path=str(source_dir))
            writer.create_collection(collection, vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
            writer.upsert(collection, points=[models.PointStruct(id=w['id'], vector=[1., 0.],
                payload={**w, 'indexSignature': digest(signature)}) for w in windows], wait=True)
            seal = inspect_points(writer, collection, windows, signature, 2)
            writer.close()
            seal['persistedPointsSha256'] = seal['pointsSha256']
            seal['sealSha256'] = digest(seal)
            metadata = {'projectId': 'fixture', 'collection': collection, 'signature': signature,
                'chunks': [{'id': 'p'}], 'parentCount': 1, 'windows': windows, 'vectorPoints': 3,
                'vectorIntegrity': seal}
            metadata_path = source_dir / 'metadata.json'
            metadata_path.write_text(json.dumps(metadata))
            def descriptor(path):
                stat = path.stat()
                return {'path': str(path), 'bytes': stat.st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                        'device': stat.st_dev, 'inode': stat.st_ino}
            inputs = {'number': 1, 'collection': collection, 'metadata': descriptor(metadata_path),
                'sqlite': descriptor(source_dir / 'collection' / collection / 'storage.sqlite'),
                'qdrantMeta': descriptor(source_dir / 'meta.json'),
                'phaseB': {'sealSha256': seal['sealSha256'], 'pointCount': 3, 'dimension': 2,
                           'matchedRecordedValues': ['persistedPointsSha256']}}
            local = root / 'work'
            prepare_runtime_tree(inputs, local, 1000000, 30)
            result = transfer_with_journal(inputs, local, lambda: QdrantClient(path=str(root / 'target')),
                root / 'journal', 2, 30, 3)
            self.assertEqual(result['source'], result['target'])
            events = [json.loads(line) for line in (root / 'journal/migration-receipt.jsonl').read_text().splitlines()]
            self.assertEqual('completed_candidate_not_adopted', events[-1]['status'])
            target = QdrantClient(path=str(root / 'target'))
            try:
                self.assertEqual(3, target.count(collection, exact=True).count)
            finally:
                target.close()

    def test_pages_transfer_and_exact_seal(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'local'
            name = 'project_fixture'
            signature = {'model': 'synthetic'}
            windows = [{'id': f'00000000-0000-0000-0000-{i:012d}', 'parentId': 'parent'} for i in range(300)]
            writer = QdrantClient(path=str(root))
            writer.create_collection(name, vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
            writer.upsert(name, points=[models.PointStruct(id=w['id'], vector=[1., 0.],
                payload={**w, 'indexSignature': digest(signature)}) for w in windows], wait=True)
            seal = inspect_points(writer, name, windows, signature, 2)
            writer.close()
            source = ColdLocalReader(root, name)
            target = QdrantClient(':memory:')
            try:
                cold = inspect_points(source, name, windows, signature, 2)
                seal['persistedPointsSha256'] = cold['pointsSha256']
                seal['sealSha256'] = digest(seal)
                chunks = [{'id': 'parent'}]
                metadata = {'projectId': 'fixture', 'collection': name, 'signature': signature,
                    'chunks': chunks, 'parentCount': 1, 'windows': windows, 'vectorPoints': 300,
                    'vectorIntegrity': seal}
                self.assertEqual(cold, verify_reuse(source, metadata, key='fixture', collection=name,
                                                     chunks=chunks, signature=signature))
                result = migrate_collection(source, target, metadata, 37)
                self.assertEqual(result['source'], result['target'])
                self.assertEqual(300, target.count(name, exact=True).count)
                with self.assertRaisesRegex(ValueError, 'already exists'):
                    migrate_collection(source, target, metadata, 37)
            finally:
                source.close()
                target.close()

    def test_read_transaction_holds_snapshot_against_sqlite_writer(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            writer = QdrantClient(path=str(root))
            writer.create_collection('selected', vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
            writer.upsert('selected', points=[models.PointStruct(id=1, vector=[1., 0.], payload={})], wait=True)
            writer.close()
            source = ColdLocalReader(root, 'selected')
            other = sqlite3.connect(root / 'collection/selected/storage.sqlite', timeout=0)
            try:
                other.execute('DELETE FROM points')
                with self.assertRaises(sqlite3.OperationalError):
                    other.commit()
                self.assertEqual(1, source.count('selected', exact=True).count)
                other.rollback()
            finally:
                other.close()
                source.close()

    def test_rejects_unexpected_pickled_class_before_transfer(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            writer = QdrantClient(path=str(root))
            writer.create_collection('selected', vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
            writer.upsert('selected', points=[models.PointStruct(id=1, vector=[1., 0.], payload={})], wait=True)
            writer.close()
            db = sqlite3.connect(root / 'collection/selected/storage.sqlite')
            db.execute('UPDATE points SET point=?', (pickle.dumps(Path('unexpected')),))
            db.commit()
            db.close()
            source = ColdLocalReader(root, 'selected')
            try:
                with self.assertRaises(pickle.UnpicklingError):
                    source.scroll('selected', limit=2)
            finally:
                source.close()

    def test_rejects_invalid_collection_layout(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'meta.json').write_text(json.dumps({'collections': {'a': {}, 'b': {}}, 'aliases': {}}))
            with self.assertRaisesRegex(ValueError, 'Exactly one'):
                ColdLocalReader(root, 'a')


if __name__ == '__main__':
    unittest.main()
