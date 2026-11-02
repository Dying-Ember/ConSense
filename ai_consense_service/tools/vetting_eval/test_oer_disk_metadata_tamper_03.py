"""Synthetic on-disk metadata tampering; no real model, vector store or fsync claim."""
import json
import os
import unittest
from unittest.mock import patch

import test_oer_server as fixtures


class DiskMetadataTamperTest(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        for key in ('CONSENSE_QDRANT_URL', 'CONSENSE_QDRANT_API_KEY',
                    'CONSENSE_QDRANT_MIGRATION_RECEIPT', 'CONSENSE_QDRANT_MIGRATION_RECEIPT_SHA256',
                    'CONSENSE_QDRANT_MIGRATION_REGISTRY', 'CONSENSE_QDRANT_MIGRATION_REGISTRY_SHA256'):
            os.environ.pop(key, None)
        self.fixture = fixtures.OerHttpContractTest(methodName='runTest')
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)

    def tamper_metadata(self, edit):
        fixture = self.fixture
        indexed = fixture.index()
        self.assertEqual(200, indexed.status_code, indexed.text)
        path = fixture.backend.metadata_path('oer-test')
        before = path.read_bytes()
        value = json.loads(before)
        edit(value)
        after = json.dumps(value, ensure_ascii=False).encode('utf-8')
        self.assertNotEqual(before, after)
        path.write_bytes(after)
        self.assertEqual(after, path.read_bytes())
        return len(fixture.encodes), len(fixture.predictions)

    def test_excluded_persisted_source_rejected_before_models(self):
        calls = self.tamper_metadata(lambda value: value['chunks'][0].update(sourceClass='works_related_pss'))
        response = self.fixture.query()
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('COMPETITION_SCOPE_UNFILTERED_REQUEST', response.json()['detail']['kind'])
        self.assertEqual(calls, (len(self.fixture.encodes), len(self.fixture.predictions)))

    def test_persisted_cpu_signature_rejected_before_models(self):
        def change(value):
            value['signature']['runtime']['device'] = 'cpu'

        calls = self.tamper_metadata(change)
        response = self.fixture.query()
        self.assertEqual(409, response.status_code, response.text)
        self.assertIn('Index signature changed', response.json()['detail'])
        self.assertEqual(calls, (len(self.fixture.encodes), len(self.fixture.predictions)))
