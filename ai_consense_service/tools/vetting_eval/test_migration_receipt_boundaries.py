"""Incompatible migration receipts must not become readable baselines."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from migrated_qdrant_index import ENV, MigratedIndex
from token_windows import WindowError, digest


class MigrationReceiptBoundariesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.url = 'http://127.0.0.1:28683'

    def pin(self, name, value):
        path = self.root / name
        raw = value if isinstance(value, bytes) else json.dumps(value).encode('utf-8')
        path.write_bytes(raw)
        return {'path': str(path), 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}

    def reader(self, receipt):
        return MigratedIndex({ENV: receipt['path'], ENV + '_SHA256': receipt['sha256'],
                              'CONSENSE_QDRANT_URL': self.url})

    def test_journal_and_completed_tail_are_not_baseline_receipts(self):
        seal = {'schema': 'authored', 'dimension': 2, 'pointCount': 1, 'pointsSha256': 'a' * 64}
        events = [
            {'status': 'initializing', 'collection': 'authored', 'targetMayExist': False},
            {'status': 'metadata_verified', 'collection': 'authored', 'metadataSha256': 'b' * 64, 'targetMayExist': False},
            {'status': 'running', 'collection': 'authored', 'targetMayExist': False},
            {'status': 'transfer_intent', 'collection': 'authored', 'targetMayExist': True},
            {'status': 'completed_candidate_not_adopted', 'collection': 'authored',
             'source': seal, 'target': seal, 'businessAccepted': False},
        ]
        journal = b''.join(json.dumps(event).encode('utf-8') + b'\n' for event in events)
        with patch('qdrant_client.QdrantClient') as client, \
             patch('migrated_qdrant_index.verify_reuse') as verify, \
             patch('migrated_qdrant_index.validate_metadata') as validate:
            with self.assertRaises(json.JSONDecodeError):
                self.reader(self.pin('journal.jsonl', journal))
            with self.assertRaises(WindowError) as error:
                self.reader(self.pin('completed-tail.json', events[-1]))
            self.assertEqual('MIGRATED_INDEX_RECEIPT_INVALID', error.exception.kind)
            client.assert_not_called(); verify.assert_not_called(); validate.assert_not_called()

    def test_baseline_receipt_cannot_claim_same_source_and_target_collection(self):
        integrity = {'schema': 'authored', 'dimension': 2, 'dtype': 'float32-le',
                     'pointCount': 1, 'embeddingSignatureSha256': 'a' * 64, 'pointsSha256': 'b' * 64}
        seal = {**integrity, 'persistedPointsSha256': integrity['pointsSha256']}
        seal['sealSha256'] = digest(seal)
        frozen = {'schema': 'consense.frozen-fixed-development-baseline.v1',
                  'collection': 'authored', 'indexSignature': 'c' * 64, 'vectorIntegrity': seal}
        metadata = {'collection': 'authored', 'parentCount': 1, 'vectorPoints': 1, 'vectorIntegrity': seal}
        receipt = {'schema': 'consense.qdrant-server-migration.v1',
                   'status': 'completed_full_storage_readback_candidate_not_adopted',
                   'target': {'url': self.url, 'collection': 'authored'}, 'targetAdopted': False,
                   'activeConfigurationChanged': False, 'allOriginalPersistentStateBytesUnchanged': True,
                   'frozenManifest': self.pin('frozen.json', frozen),
                   'candidateMetadata': self.pin('metadata.json', metadata),
                   'sourceVectorIntegrity': integrity, 'targetVectorIntegrity': integrity,
                   'indexSignature': frozen['indexSignature'], 'parentCount': 1, 'vectorWindowCount': 1}
        with patch('qdrant_client.QdrantClient') as client, \
             patch('migrated_qdrant_index.verify_reuse') as verify, \
             patch('migrated_qdrant_index.validate_metadata') as validate:
            with self.assertRaises(WindowError) as error:
                self.reader(self.pin('baseline.json', receipt))
            self.assertEqual('MIGRATED_INDEX_IDENTITY', error.exception.kind)
            client.assert_not_called(); verify.assert_not_called(); validate.assert_not_called()
            different = 'authored_target'
            metadata['collection'] = different
            receipt['target'] = {'url': self.url, 'collection': different}
            receipt['candidateMetadata'] = self.pin('different-metadata.json', metadata)
            reader = self.reader(self.pin('different-baseline.json', receipt))
            self.assertEqual(different, reader.metadata['collection'])
            validate.assert_called_once()
            client.assert_not_called(); verify.assert_not_called()


if __name__ == '__main__':
    unittest.main()
