"""Public endpoint remains closed until journal and model-session ownership are complete."""
from contextlib import ExitStack, contextmanager
import json
from unittest.mock import patch
import unittest

import oer_request_evidence as evidence
REAL_PUBLICATION_VERIFIER = evidence.RequestEvidence._verify_publication_evidence
import model_session_observer
import test_remote_source_page_index as remote_fixture
import test_oer_server as oer_fixture
from token_windows import WindowError


class JournalGuardHttpTest(unittest.TestCase):
    def setUp(self):
        remote_fixture.RemoteSourcePageIndexTest.setUp(self)
        self.publication_fakes.close()
    def tearDown(self): oer_fixture.OerHttpContractTest.tearDown(self)

    def test_canonical_index_rejects_before_model_and_qdrant_without_release(self):
        registry = self.app.state.source_registry
        with patch.object(self.backend._remote_models, 'session_scope', None), \
             patch.object(registry.source_pages, '_read', return_value={'authored':True}):
            response = self.client.post('/index-canonical', json=self.body)
        self.assertEqual(503, response.status_code, response.text)
        self.assertIn(response.json()['detail']['kind'], ('SOURCE_JOURNAL_BINDING_INVALID', 'MODEL_SESSION_RELEASE_REQUIRED'))
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())
        self.assertFalse(self.db.collections)

    def simulated_boundaries(self):
        stack=ExitStack()
        stack.enter_context(patch('remote_gpu_models._trusted_stage_timeout',return_value=10))
        # These fixtures only exercise ordering: no GPU release or durable witness is certified.
        stack.enter_context(patch.object(model_session_observer, '_trusted_stage_pins',
            return_value={'profileSha256':'a'*64, 'executionSha256':'d'*64}))
        stack.enter_context(patch('oer_server._require_publication_model_proxy'))
        stack.enter_context(patch('oer_server._PublicationModelObserver.publication_verified',return_value=True))
        stack.enter_context(patch.object(evidence,'_sync_directory'))
        stack.enter_context(patch.object(evidence,'_sync_file'))
        stack.enter_context(patch.object(evidence.RequestEvidence,'_verify_publication_evidence',
            return_value={'sourceEvidenceSha256':'a'*64,'modelReleaseEvidenceSha256':'b'*64}))
        stack.enter_context(patch.object(self.app.state.source_registry.source_pages,'_read',return_value={'authored':True}))
        return stack

    def test_canonical_index_journal_and_fresh_reader_with_fake_witness(self):
        with self.simulated_boundaries():
            response=self.client.post('/index-canonical',json=self.body)
            self.assertEqual(200,response.status_code,response.text)
            path=self.backend.metadata_path('AUTHORED-remote-pins')
            metadata=json.loads(path.read_text(encoding='utf-8'))
            fresh=evidence.RequestEvidence(self.backend.STATE/'run-evidence')
            with self.assertRaises(WindowError):fresh.verify_index(path,metadata,'AUTHORED-remote-pins')
            fresh.recover_index(path,metadata,'AUTHORED-remote-pins')
            receipt=fresh.verify_index(path,metadata,'AUTHORED-remote-pins')
            self.assertEqual('completed',receipt['terminalStatus'])
            self.assertEqual(metadata['generation'],receipt['publication']['intent']['generation'])

    def test_failure_after_source_frame_replacement_keeps_block(self):
        registry=self.app.state.source_registry;original=registry.validation_frame
        @contextmanager
        def failure(*args,**kwargs):
            with original(*args,**kwargs):yield
            raise WindowError('AUTHORED_FRAME_FAILURE','fixture source frame failure')
        with self.simulated_boundaries(),patch.object(registry,'validation_frame',side_effect=failure):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(422,response.status_code,response.text)
        path=self.backend.metadata_path('AUTHORED-remote-pins')
        self.assertTrue(path.exists())
        metadata=json.loads(path.read_text(encoding='utf-8'))
        journal=self.backend.request_evidence()
        self.assertTrue(journal.block_path('AUTHORED-remote-pins').exists())
        receipt=json.loads((journal.root/metadata['publicationJournal']/'receipt.json').read_text(encoding='utf-8'))
        self.assertEqual('failed',receipt['terminalStatus'])
        with self.assertRaises(WindowError):journal.verify_index(path,metadata,'AUTHORED-remote-pins')

    def test_receipt_failure_after_replace_stays_blocked(self):
        original=evidence._save
        def fail_receipt(path,value,*,durable=False):
            if path.name=='receipt.json' and durable:
                original(path,value)
                raise OSError('authored receipt sync failure after visible write')
            return original(path,value,durable=durable)
        with self.simulated_boundaries(),patch.object(evidence,'_save',side_effect=fail_receipt):
            response=self.client.post('/index-canonical',json=self.body)
            self.assertEqual(503,response.status_code,response.text)
            path=self.backend.metadata_path('AUTHORED-remote-pins')
            self.assertTrue(path.exists())
            metadata=json.loads(path.read_text(encoding='utf-8'))
            fresh=evidence.RequestEvidence(self.backend.STATE/'run-evidence')
            with self.assertRaisesRegex(WindowError,'blocked'):
                fresh.verify_index(path,metadata,'AUTHORED-remote-pins')
            with self.assertRaisesRegex(WindowError,'blocked'):
                self.backend.read_project('AUTHORED-remote-pins')
            with patch.object(evidence.RequestEvidence, '_verify_publication_evidence', new=REAL_PUBLICATION_VERIFIER):
                query=self.client.post('/retrieve',json={'projectId':'AUTHORED-remote-pins','query':'fixture'})
            self.assertEqual(503,query.status_code,query.text)
            self.assertEqual('INDEX_PUBLICATION_EVIDENCE_UNAVAILABLE',query.json()['detail']['kind'])
            self.assertTrue(self.backend.request_evidence().block_path('AUTHORED-remote-pins').exists())
            retry=self.client.post('/index-canonical',json=self.body)
            self.assertEqual(503,retry.status_code,retry.text)
            self.assertEqual('INDEX_PUBLICATION_DURABILITY_UNKNOWN',retry.json()['detail']['kind'])


if __name__ == '__main__': unittest.main()
