import copy
import hashlib
import json
import tempfile
import unittest
import uuid
from unittest.mock import patch
from types import SimpleNamespace
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'ai_consense_service' / 'tools' / 'vetting_eval'))

from token_windows import digest
from index_integrity import inspect_points
from same_project_vector_reuse import SameProjectVectorReuse


OLD = 'project-a::canonical::advisory::old'
NEW = 'project-a::canonical::advisory::new'


class Database:
    def __init__(self, row, signature):
        self.point = SimpleNamespace(id=row['id'], vector=[1.0, 0.0],
                                     payload={**row, 'indexSignature': digest(signature)})
    def scroll(self, collection, **kwargs):
        return [copy.deepcopy(self.point)], None
    def retrieve(self, collection, ids, **kwargs):
        return [copy.deepcopy(self.point)] if self.point.id in ids else []


class Reader:
    def __init__(self, row):
        self.receipt = {'h2Project': 'project-a', 'module': 'advisory'}
        self.metadata = {'projectId': OLD, 'collection': 'old', 'signature': {'model': 'pinned'},
                         'vectorIntegrity': {'dimension': 2}, 'windows': [row]}
    def read(self, key, runtime, database):
        # In production the existing reader verifies the complete sealed collection here.
        database().scroll('old', limit=256)
        return self.metadata


class SameProjectReuseTest(unittest.TestCase):
    def setUp(self):
        self.gpu = patch('same_project_vector_reuse.gpu_code_matches', return_value=True)
        self.gpu.start()
        self.addCleanup(self.gpu.stop)
        self.row = {'id': 'one', 'sourceHash': 'revision-a', 'content': 'original',
                    'modelTextSha256': 'hash', 'recipeFingerprint': 'recipe'}
        self.reader = Reader(self.row)
        self.db = Database(self.row, self.reader.metadata['signature'])

    def create(self, project=NEW, module='advisory'):
        return SameProjectVectorReuse(self.reader, None, self.db, project, module)

    def test_exact_window_reused_and_changed_input_is_encoded(self):
        reuse = self.create()
        self.assertEqual({0: [1.0, 0.0]}, reuse.vectors([self.row, {**self.row, 'id': 'new'}]))
        for changed in ({'sourceHash': 'revision-b'}, {'content': 'changed'}, {'recipeFingerprint': 'other'}):
            self.assertEqual({}, reuse.vectors([{**self.row, **changed}]))

    def test_project_or_module_mismatch_is_rejected(self):
        with self.assertRaises(Exception): self.create('project-b::canonical::advisory::new')
        with self.assertRaises(Exception): self.create('project-a::canonical::vetting::new', 'vetting')

    def test_predecessor_change_after_seal_is_rejected(self):
        reuse = self.create()
        self.db.point.vector = [0.0, 1.0]
        with self.assertRaises(Exception): reuse.vectors([self.row])

    def test_gpu_change_during_reuse_is_rejected(self):
        reuse = self.create()
        with patch('same_project_vector_reuse.gpu_code_matches', return_value=False):
            with self.assertRaises(Exception): reuse.vectors([self.row])

    def test_missing_or_changed_payload_is_rejected(self):
        reuse = self.create()
        self.db.point.payload['sourceHash'] = 'different'
        with self.assertRaises(Exception): reuse.vectors([self.row])
        self.db.point.id = 'missing'
        with self.assertRaises(Exception): reuse.vectors([self.row])

    def test_new_collection_seal_preserves_the_old_collection(self):
        old = copy.deepcopy(self.db.point)
        reuse = self.create()
        new_row = {**self.row, 'id': 'new', 'sourceHash': 'revision-b', 'content': 'new'}
        rows = [self.row, new_row]
        reused = reuse.vectors(rows)
        signature = {'corpus': 'new-scope'}
        points = [SimpleNamespace(id=row['id'], vector=reused.get(i, [0.0, 1.0]),
                  payload={**row, 'indexSignature': digest(signature)}) for i, row in enumerate(rows)]

        class NewCollection:
            def collection_exists(self, name): return name == 'new'
            def get_collection(self, name):
                return SimpleNamespace(config=SimpleNamespace(params=SimpleNamespace(vectors=
                    SimpleNamespace(size=2, distance='Cosine', multivector_config=None))))
            def count(self, name, exact): return SimpleNamespace(count=len(points))
            def scroll(self, name, **kwargs): return points, None

        seal = inspect_points(NewCollection(), 'new', rows, signature, 2)
        self.assertEqual(2, seal['pointCount'])
        self.assertEqual([1.0, 0.0], points[0].vector)
        self.assertEqual([0.0, 1.0], points[1].vector)
        self.assertEqual(vars(old), vars(self.db.point))

    def test_real_local_qdrant_copy_and_seals(self):
        try:
            from qdrant_client import QdrantClient, models
        except ImportError:
            self.skipTest('qdrant-client is not installed')
        db = QdrantClient(':memory:')
        try:
            old_row = {**self.row, 'id': str(uuid.uuid4())}
            added = {**old_row, 'id': str(uuid.uuid4()), 'sourceHash': 'revision-b', 'content': 'new'}
            reader = Reader(old_row)
            old_sig = reader.metadata['signature']
            db.create_collection('old', vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
            db.upsert('old', [models.PointStruct(id=old_row['id'], vector=[1.0, 0.0],
                      payload={**old_row, 'indexSignature': digest(old_sig)})], wait=True)
            old_seal = inspect_points(db, 'old', [old_row], old_sig, 2)
            def verify(key, runtime, factory):
                self.assertEqual(old_seal, inspect_points(factory(), 'old', [old_row], old_sig, 2))
                return reader.metadata
            reader.read = verify
            reuse = SameProjectVectorReuse(reader, None, db, NEW, 'advisory')
            values = reuse.vectors([old_row, added])
            self.assertEqual({0: [1.0, 0.0]}, values)
            new_sig = {'corpus': 'new-scope'}
            db.create_collection('new', vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
            db.upsert('new', [models.PointStruct(id=row['id'], vector=values.get(i, [0.0, 1.0]),
                      payload={**row, 'indexSignature': digest(new_sig)})
                      for i, row in enumerate([old_row, added])], wait=True)
            self.assertEqual(2, inspect_points(db, 'new', [old_row, added], new_sig, 2)['pointCount'])
            self.assertEqual(old_seal, inspect_points(db, 'old', [old_row], old_sig, 2))
        finally:
            db.close()


class GpuImplementationTest(unittest.TestCase):
    def test_exact_worker_and_gpu_binding_required(self):
        from same_project_vector_reuse import gpu_code_matches
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'worker.py').write_bytes(b'known worker')
            config = root / 'gpu.json'
            gpu_id = 'a' * 64
            config.write_text(json.dumps({'containerId': gpu_id}), encoding='utf8')
            files = {'worker.py': hashlib.sha256(b'known worker').hexdigest()}
            with patch('same_project_vector_reuse.GPU_FILES', files), patch.dict('os.environ', {'CONSENSE_REUSE_ATTESTED_GPU_CONTAINER_ID': gpu_id}):
                self.assertTrue(gpu_code_matches(root, config))
                (root / 'worker.py').write_bytes(b'changed worker')
                self.assertFalse(gpu_code_matches(root, config))
                (root / 'worker.py').write_bytes(b'known worker')
                config.write_text(json.dumps({'containerId': 'b' * 64}), encoding='utf8')
                self.assertFalse(gpu_code_matches(root, config))
                for malformed in ('[]', 'null', '"text"', 'not json'):
                    config.write_text(malformed, encoding='utf8')
                    self.assertFalse(gpu_code_matches(root, config))
                config.write_text(json.dumps({'containerId': gpu_id}), encoding='utf8')
                with patch.dict('os.environ', {'CONSENSE_REUSE_ATTESTED_GPU_CONTAINER_ID': ''}):
                    self.assertFalse(gpu_code_matches(root, config))


if __name__ == '__main__':
    unittest.main()
