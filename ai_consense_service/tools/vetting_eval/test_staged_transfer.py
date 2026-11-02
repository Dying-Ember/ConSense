import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from qdrant_client import QdrantClient, models
import migrate_sealed_collections as migration
from index_integrity import inspect_points
from qdrant_writer_client import target_factory
from token_windows import digest

SOURCE = 'project_fixture'
TARGET = 'ticket03_stage_' + 'a' * 32
URL = 'http://127.0.0.1:28683'


class Session:
    def __init__(self, path):
        self.client = QdrantClient(path=str(path))
    def read(self, method, *args, **kwargs):
        return getattr(self.client, method)(*args, **kwargs)
    def write(self, method, *args, **kwargs):
        getattr(self.client, method)(*args, **kwargs)
        return None  # The authority adapter does not promise a boolean result.
    def close(self):
        self.client.close()


class Authority:
    def __init__(self, path): self.path = path
    def open_writer(self, binding):
        if binding['collection'] != TARGET: raise ValueError('wrong binding')
        return Session(self.path)


class TransferTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        fixture = self.root / 'fixture'
        client = QdrantClient(path=str(fixture))
        point_id = '00000000-0000-0000-0000-000000000001'
        signature = {'model': 'authored'}
        window = {'id': point_id, 'parentId': 'p', 'content': 'synthetic'}
        client.create_collection(SOURCE, vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
        client.upsert(SOURCE, points=[models.PointStruct(id=point_id, vector=[1., 0.],
                      payload={**window, 'indexSignature': digest(signature)})], wait=True)
        seal = inspect_points(client, SOURCE, [window], signature, 2)
        client.close()
        seal['persistedPointsSha256'] = seal['pointsSha256']
        seal['sealSha256'] = digest(seal)
        self.metadata = {'projectId': 'test', 'collection': SOURCE, 'signature': signature,
                         'chunks': [{'id': 'p'}], 'parentCount': 1, 'windows': [window],
                         'vectorPoints': 1, 'vectorIntegrity': seal}
        meta = fixture / 'metadata.json'; meta.write_text(json.dumps(self.metadata), encoding='utf-8')
        def describe(p):
            s = p.stat()
            return {'path': str(p), 'bytes': s.st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
                    'device': s.st_dev, 'inode': s.st_ino}
        self.inputs = {'number': 1, 'collection': SOURCE, 'metadata': describe(meta),
                       'sqlite': describe(fixture / 'collection' / SOURCE / 'storage.sqlite'),
                       'qdrantMeta': describe(fixture / 'meta.json'),
                       'phaseB': {'sealSha256': seal['sealSha256'], 'pointCount': 1, 'dimension': 2,
                                  'matchedRecordedValues': ['persistedPointsSha256']}}
        self.runtime = self.root / 'runtime'
        migration.prepare_runtime_tree(self.inputs, self.runtime, 1000000, 10)
        self.dest = self.root / 'target'
        self.binding = {'collection': TARGET, 'operationId': 'op-1', 'serverIdentity': 's1'}

    def transfer(self, out, factory=None):
        return migration.transfer_with_journal(self.inputs, self.runtime,
            factory or target_factory(Authority(self.dest), self.binding), out, 8, 30, 1,
            target_collection=TARGET, target_url=URL)

    def test_full_staged_seal_preserves_original_and_adapter_contract(self):
        receipt = self.root / 'receipt'
        result = self.transfer(receipt)
        self.assertEqual({'source', 'target'}, set(result))
        self.assertEqual(result['source'], result['target'])
        self.assertEqual(SOURCE, self.metadata['collection'])
        events = [json.loads(row) for row in (receipt / 'migration-receipt.jsonl').read_text().splitlines()]
        self.assertTrue(all(e['collection'] == SOURCE and e['targetCollection'] == TARGET and e['targetUrl'] == URL for e in events))
        self.assertEqual('completed_candidate_not_adopted', events[-1]['status'])
        client = QdrantClient(path=str(self.dest))
        try:
            self.assertFalse(client.collection_exists(SOURCE))
            self.assertTrue(client.collection_exists(TARGET))
            got = client.retrieve(TARGET, ids=[self.metadata['windows'][0]['id']], with_payload=True, with_vectors=True)[0]
            self.assertEqual({**self.metadata['windows'][0], 'indexSignature': digest(self.metadata['signature'])}, got.payload)
            self.assertEqual([1., 0.], got.vector)
        finally: client.close()
        with self.assertRaises((ValueError, FileExistsError)):
            self.transfer(receipt)

    def test_failure_keeps_partial_staged_collection_and_bound_receipt(self):
        class Failing(Session):
            def write(self, method, *args, **kwargs):
                if method == 'upsert': raise RuntimeError('synthetic')
                return super().write(method, *args, **kwargs)
        class FailingAuthority(Authority):
            def open_writer(self, binding): return Failing(self.path)
        out = self.root / 'failed'
        with self.assertRaises(RuntimeError):
            self.transfer(out, target_factory(FailingAuthority(self.dest), self.binding))
        events = [json.loads(row) for row in (out / 'migration-receipt.jsonl').read_text().splitlines()]
        self.assertEqual('failed', events[-1]['status'])
        self.assertTrue(events[-1]['targetMayExist'])
        self.assertTrue(all(e['targetCollection'] == TARGET and e['targetUrl'] == URL for e in events))
        client = QdrantClient(path=str(self.dest))
        try:
            self.assertTrue(client.collection_exists(TARGET))
            self.assertFalse(client.collection_exists(SOURCE))
        finally: client.close()

    def test_invalid_destination_rejected_before_target(self):
        for name in (SOURCE, 'ticket03_stage_short', '../other', 'ticket03_stage_' + 'A' * 32):
            with self.subTest(name=name), self.assertRaises(ValueError):
                migration.transfer_with_journal(self.inputs, self.runtime, lambda: self.fail('target opened'),
                    self.root / 'no-receipt', 8, 10, 1, target_collection=name, target_url=URL)
        self.assertFalse((self.root / 'no-receipt').exists())

    def test_legacy_journal_keeps_original_field_shapes(self):
        out = self.root / 'legacy'
        migration.transfer_with_journal(self.inputs, self.runtime,
            lambda: QdrantClient(path=str(self.dest)), out, 8, 30, 1)
        events = [json.loads(row) for row in (out / 'migration-receipt.jsonl').read_text().splitlines()]
        self.assertEqual([
            ('initializing', {'status', 'collection', 'targetMayExist'}),
            ('metadata_verified', {'status', 'collection', 'metadataSha256', 'targetMayExist'}),
            ('running', {'status', 'collection', 'targetMayExist'}),
            ('transfer_intent', {'status', 'collection', 'targetMayExist'}),
            ('completed_candidate_not_adopted', {'status', 'collection', 'source', 'target', 'businessAccepted'}),
        ], [(e['status'], set(e)) for e in events])
        client = QdrantClient(path=str(self.dest))
        try: self.assertTrue(client.collection_exists(SOURCE))
        finally: client.close()

    def test_untrusted_url_is_rejected_before_receipt_for_legacy_and_staging(self):
        class ImpersonatingUrl(str):
            def __eq__(self, other): return True
        for destination in (None, TARGET):
            for url in ('http://name:secret@127.0.0.1:28683', 'http://other:28683',
                        'http://127.0.0.1:28683/?key=secret', ImpersonatingUrl('secret')):
                with self.subTest(destination=destination, url=url), self.assertRaises(ValueError):
                    migration.transfer_with_journal(self.inputs, self.runtime, lambda: self.fail('target opened'),
                        self.root / 'bad-url', 8, 10, 1, target_collection=destination, target_url=url)
        self.assertFalse((self.root / 'bad-url').exists())


if __name__ == '__main__': unittest.main()
