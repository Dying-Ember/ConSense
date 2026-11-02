"""Synthetic HTTP integration for numeric project identity; no real services."""
import hashlib
import json
import unittest
from unittest.mock import patch

import test_oer_session_integration as fixtures


class NumericProjectIdHttpTest(unittest.TestCase):
    def test_numeric_canonical_request_preserves_receipt_type(self):
        fixture = fixtures.OerSessionIntegrationTest('runTest')
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.addCleanup(fixture.tearDown)
        body = {**fixture.body, 'projectId': 7}
        # Windows fixture only: directory fsync and source/model verifiers are substitutes.
        with patch('window_runtime.sync_index_directory'):
            response = fixture.client.post('/index-canonical', json=body)
        self.assertEqual(200, response.status_code, response.text)
        metadata = json.loads(fixture.backend.metadata_path('7').read_bytes())
        directory = fixture.app.state.request_evidence.root / metadata['publicationJournal']
        raw = (directory / 'request.json').read_bytes()
        request = json.loads(raw)
        self.assertEqual(7, request['projectId'])
        self.assertIs(type(request['projectId']), int)
        receipt = json.loads((directory / 'receipt.json').read_bytes())
        self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt['request']['sha256'])
        self.assertEqual('7', receipt['publication']['prewrite']['projectId'])
