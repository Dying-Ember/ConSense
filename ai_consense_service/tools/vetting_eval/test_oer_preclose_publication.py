"""Synthetic v2 publication ordering; no real release verifier or writer isolation."""
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import oer_request_evidence as evidence
from artifact_store import ArtifactStore
from source_request_context import RequestControl, commit_pending, prewrite, publication_reference, request_context, stage
from token_windows import WindowError
from unified_source_registry import UnifiedCanonicalSourceRegistry
import oer_server
import source_request_context
import test_oer_session_integration as session_fixture
import test_oer_server as oer_fixture


class PrecloseFrameTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.store = ArtifactStore(root / 'cas')
        self.registry = UnifiedCanonicalSourceRegistry(root / 'canonical', self.store)
        self.guard = lambda: {'software':'authored'}

    def test_cas_exit_and_checks_precede_callback_and_lease_close(self):
        descriptor = self.store.put_bytes(b'authored bytes')
        calls = []
        read = self.store._read_uncached
        def observed_read(*args, **kwargs):
            calls.append('cas-read')
            return read(*args, **kwargs)
        def commit():
            self.assertIsNone(self.store._read_session.get())
            self.assertIsNotNone(self.registry._validation_frame.get())
            calls.append('commit')
        with patch.object(self.store, '_read_uncached', side_effect=observed_read):
            with self.registry.validation_frame(software_guard=self.guard, after_verified=commit):
                self.store.verify(descriptor)
                frame = self.registry._validation_frame.get()
                frame.setdefault('precommitChecks', []).append(lambda: calls.append('route-check'))
                frame['leaseStack'].callback(lambda: calls.append('lease-close'))
        self.assertEqual(['cas-read','cas-read','route-check','commit','lease-close'], calls)

    def test_cas_or_route_failure_prevents_callback(self):
        for failure in ('cas', 'route'):
            with self.subTest(failure=failure):
                descriptor = self.store.put_bytes(('authored-'+failure).encode())
                calls = []
                with self.assertRaises((ValueError, WindowError)):
                    with self.registry.validation_frame(software_guard=self.guard, after_verified=lambda: calls.append('commit')):
                        self.store.verify(descriptor)
                        if failure == 'cas':
                            self.store._path(descriptor).write_bytes(b'changed')
                        else:
                            def reject(): raise WindowError('MIGRATION_ROUTE_CHANGED', 'authored')
                            self.registry._validation_frame.get().setdefault('precommitChecks', []).append(reject)
                self.assertEqual([], calls)

    def test_nested_frame_cannot_register_final_callback(self):
        with self.registry.validation_frame(software_guard=self.guard):
            with self.assertRaisesRegex(ValueError, 'outer frame'):
                with self.registry.validation_frame(software_guard=self.guard, after_verified=lambda: None):
                    pass

    def test_failed_lease_check_prevents_callback(self):
        calls=[]
        with self.assertRaisesRegex(RuntimeError,'authored lease check'):
            with self.registry.validation_frame(software_guard=self.guard,after_verified=lambda: calls.append('commit')):
                def reject():raise RuntimeError('authored lease check')
                self.registry._validation_frame.get()['indexMetadata']['p1']={
                    'identity':lambda:'same','expected':'same','leaseCheck':reject}
        self.assertEqual([],calls)


class PrecloseJournalTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.journal=evidence.RequestEvidence(self.root/'state'/'run-evidence')
        (self.root/'state'/'projects').mkdir(parents=True)
        self.binding={'projectId':'p1','generation':'a'*32,'collection':'project_fixture',
                      'priorMetadataSha256':None,'module':'vetting','sourceScopeSha256':'b'*64,
                      'routeIdentity':'c'*64}
        self.proof={'sourceEvidenceSha256':'d'*64,'modelReleaseEvidenceSha256':'e'*64}
        # Windows fsync substitution exercises protocol ordering only, not durable storage.
        self.file_sync=patch.object(evidence,'_sync_file');self.file_sync.start();self.addCleanup(self.file_sync.stop)
        self.dir_sync=patch.object(evidence,'_sync_directory');self.dir_sync.start();self.addCleanup(self.dir_sync.stop)

    def stage_metadata(self, record, *, preclose):
        metadata={'projectId':'p1','generation':'a'*32,'publicationJournal':publication_reference(),
                  'collection':'project_fixture','sourceScope':{'projectId':'p1','module':'vetting','sourceScopeSha256':'b'*64}}
        raw=json.dumps(metadata).encode();pending=self.root/'pending.json';pending.write_bytes(raw)
        intent={'projectId':'p1','generation':'a'*32,'metadataSha256':hashlib.sha256(raw).hexdigest(),
                'priorMetadataSha256':None}
        stage(lambda: pending.replace(self.journal.metadata_path('p1')),intent)
        commit_pending(source_frame_preclose_verified=preclose,source_frame_verified=not preclose,model_session_verified=True)
        record['response']=evidence._save(record['directory']/'response.json',{'projectId':'p1'})
        return metadata

    def test_v2_success_and_independent_fake_proof(self):
        with patch.object(self.journal,'_verify_publication_evidence',return_value=self.proof):
            with request_context(RequestControl(20)),self.journal.operation('/index-canonical',{'projectId':'p1'}) as record:
                prewrite(self.binding);metadata=self.stage_metadata(record,preclose=True)
                intent=record['publication']['intent']
                self.assertEqual('consense.index-publication-intent.v2',intent['schema'])
                self.assertEqual('final_checks_passed_before_lease_close',intent['sourceFramePhase'])
                self.assertNotIn('sourceFrameVerified',intent)
            self.assertFalse(self.journal.block_path('p1').exists())
            self.journal.verify_index(self.journal.metadata_path('p1'),metadata,'p1')

    def test_post_replace_failure_keeps_block_and_failed_receipt(self):
        with patch.object(self.journal,'_verify_publication_evidence',return_value=self.proof):
            with self.assertRaisesRegex(RuntimeError,'authored close failure'):
                with request_context(RequestControl(20)),self.journal.operation('/index-canonical',{'projectId':'p1'}) as record:
                    prewrite(self.binding);metadata=self.stage_metadata(record,preclose=True)
                    raise RuntimeError('authored close failure')
            self.assertTrue(self.journal.metadata_path('p1').exists())
            self.assertTrue(self.journal.block_path('p1').exists())
            receipt=json.loads((record['directory']/'receipt.json').read_text())
            self.assertEqual('failed',receipt['terminalStatus'])
            with self.assertRaises(WindowError):self.journal.recover_index(self.journal.metadata_path('p1'),metadata,'p1')
            self.assertTrue(self.journal.block_path('p1').exists())

    def test_completed_v2_without_real_verifier_keeps_block(self):
        with self.assertRaises(WindowError):
            with request_context(RequestControl(20)),self.journal.operation('/index-canonical',{'projectId':'p1'}) as record:
                prewrite(self.binding);self.stage_metadata(record,preclose=True)
        receipt=json.loads((record['directory']/'receipt.json').read_text())
        self.assertEqual('completed',receipt['terminalStatus'])
        self.assertTrue(self.journal.block_path('p1').exists())

    def test_v2_requires_prewrite_and_rejects_mixed_or_unknown_shapes(self):
        base={'projectId':'p1','generation':'a'*32,'metadataSha256':'d'*64,'priorMetadataSha256':None}
        v2={**base,'schema':'consense.index-publication-intent.v2',
            'sourceFramePhase':'final_checks_passed_before_lease_close'}
        for intent in (v2,{**v2,'sourceFrameVerified':True},{**v2,'schema':'other'},
                       {**v2,'sourceFramePhase':'complete'}):
            with self.subTest(intent=intent),self.assertRaises(WindowError):
                self.journal.prepare_intent({'publication':None},intent)
        for intent in ({**v2,'sourceFrameVerified':True},{**v2,'schema':'other'},
                       {**v2,'sourceFramePhase':'complete'}):
            self.assertIsNone(evidence._publication_intent_version(intent))

    def test_v2_mixed_or_unknown_shapes_cannot_attach_to_real_prewrite(self):
        base={'projectId':'p1','generation':'a'*32,'metadataSha256':'d'*64,'priorMetadataSha256':None}
        v2={**base,'schema':'consense.index-publication-intent.v2',
            'sourceFramePhase':'final_checks_passed_before_lease_close'}
        with self.assertRaisesRegex(RuntimeError,'fixture stop'):
            with request_context(RequestControl(20)),self.journal.operation('/index-canonical',{'projectId':'p1'}) as record:
                prewrite(self.binding)
                for intent in ({**v2,'sourceFrameVerified':True},{**v2,'schema':'other'},
                               {**v2,'sourceFramePhase':'complete'}):
                    with self.assertRaises(WindowError):self.journal.prepare_intent(record,intent)
                self.assertNotIn('intent',record['publication'])
                raise RuntimeError('fixture stop')
        self.assertTrue(self.journal.block_path('p1').exists())

    def test_legacy_prewrite_intent_number_does_not_equal_boolean(self):
        with patch.object(self.journal,'_verify_publication_evidence',return_value=self.proof):
            with request_context(RequestControl(20)),self.journal.operation('/index-canonical',{'projectId':'p1'}) as record:
                prewrite(self.binding);metadata=self.stage_metadata(record,preclose=False)
            path=record['directory']/'publication-intent.json'
            intent=json.loads(path.read_text());self.assertIs(intent['sourceFrameVerified'],True)
            intent['sourceFrameVerified']=1;path.write_text(json.dumps(intent),encoding='utf-8')
            fresh=evidence.RequestEvidence(self.journal.root)
            with patch.object(fresh,'_verify_publication_evidence',return_value=self.proof),self.assertRaises(WindowError):
                fresh.recover_index(self.journal.metadata_path('p1'),metadata,'p1')


class PrecloseHttpTest(unittest.TestCase):
    def setUp(self): session_fixture.OerSessionIntegrationTest.setUp(self)
    def tearDown(self): oer_fixture.OerHttpContractTest.tearDown(self)

    def test_unified_factory_checkpoint_wrappers_keep_real_proxy_binding(self):
        from checkpoint_publication import publish_checkpoint
        from fastapi.testclient import TestClient
        from oer_unified_server import create_unified_app
        from test_checkpoint_publication import CheckpointPublicationTests
        from test_token_windows import CharacterTokenizer
        fixture=CheckpointPublicationTests();fixture.setUp();self.addCleanup(fixture.tearDown)
        publication=publish_checkpoint(fixture.source,fixture.root/'unified-publications',self.backend.MODELS['embedding'])
        proxy=self.backend._remote_models
        self.backend.model_runtime=proxy.model_runtime
        self.backend.embedding_model=proxy.embedding_model
        self.backend.encode_embeddings=proxy.encode_embeddings
        self.backend.rerank_model=proxy.rerank_model
        settings={'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST':publication['manifestPath'],
                  'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST_SHA256':publication['manifestSha256'],
                  'CONSENSE_RERANKER_CHECKPOINT_MANIFEST':'','CONSENSE_RERANKER_CHECKPOINT_MANIFEST_SHA256':''}
        with patch.dict(os.environ,settings):
            app=create_unified_app(self.policy,backend=self.backend)
            outer=self.backend.embedding_model;middle=outer.__defaults__[0];inner=middle.__defaults__[0]
            self.assertIs(inner.__defaults__[0].__self__,proxy)
            oer_server._require_publication_model_proxy(self.backend,proxy)
            self.assertIs(proxy.embedding,outer())
            runtime=self.backend._windows_runtime
            runtime.tokenizer=lambda kind:CharacterTokenizer()
            runtime.identity=lambda kind:{'kind':kind,'tokenizerSha256':'AUTHORED-only'}
            with TestClient(app) as client,patch.object(app.state.source_registry.source_pages,'_read',return_value={'authored':True}):
                response=client.post('/index-canonical',json=self.body)
            self.assertEqual(200,response.status_code,response.text)
            middle.__defaults__=(lambda:proxy.embedding,'embedding')
            with self.assertRaises(WindowError):oer_server._require_publication_model_proxy(self.backend,proxy)

    def test_http_release_source_commit_close_and_receipt_order(self):
        registry=self.app.state.source_registry;events=[]
        verify=oer_server._PublicationModelObserver.publication_verified
        check_files=registry._check_frame_files
        save=evidence._save
        replace=Path.replace
        release=evidence.RequestEvidence._release_after_completion
        def model_done(observer):
            result=verify(observer);events.append('model-verified');return result
        def source_checks(frame):
            self.assertIsNone(registry.artifact_store._read_session.get())
            check_files(frame);events.append('cas-exit-and-source-check')
            frame.setdefault('precommitChecks', []).append(lambda: events.append('precommit-check'))
            frame['leaseStack'].callback(lambda: events.append('lease-close'))
        def save_record(path,value,**kwargs):
            if path.name in ('publication-intent.json','response.json','receipt.json'):
                events.append(path.name)
            return save(path,value,**kwargs)
        def replace_metadata(path,target):
            if '.pending-' in path.name:events.append('replace')
            return replace(path,target)
        def release_block(journal,*args):
            events.append('release-block');return release(journal,*args)
        with patch.object(oer_server._PublicationModelObserver,'publication_verified',new=model_done), \
             patch.object(registry,'_check_frame_files',side_effect=source_checks), \
             patch.object(evidence,'_save',side_effect=save_record), \
             patch.object(Path,'replace',new=replace_metadata), \
             patch.object(evidence.RequestEvidence,'_release_after_completion',new=release_block):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,response.status_code,response.text)
        names=['model-verified','cas-exit-and-source-check','precommit-check','publication-intent.json',
               'replace','lease-close','response.json','receipt.json','release-block']
        indexes=[events.index(name) for name in names]
        self.assertEqual(sorted(indexes),indexes,events)
        self.assertFalse(self.backend.request_evidence().block_path('AUTHORED-remote-pins').exists())

    def test_precommit_route_failure_cannot_replace(self):
        registry=self.app.state.source_registry;check_files=registry._check_frame_files
        def source_checks(frame):
            check_files(frame)
            def reject():raise WindowError('MIGRATION_ROUTE_CHANGED','authored precommit route failure')
            frame.setdefault('precommitChecks', []).append(reject)
        with patch.object(registry,'_check_frame_files',side_effect=source_checks):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())
        self.assertTrue(self.backend.request_evidence().block_path('AUTHORED-remote-pins').exists())

    def test_unknown_model_release_cannot_create_final_intent(self):
        self.gateway.fail_release=True
        response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())
        journal=self.backend.request_evidence()
        block=json.loads(journal.block_path('AUTHORED-remote-pins').read_text(encoding='utf-8'))
        self.assertFalse((journal.root/block['journalId']/'publication-intent.json').exists())

    def test_lease_close_failure_after_replace_keeps_block(self):
        registry=self.app.state.source_registry;check_files=registry._check_frame_files
        def source_checks(frame):
            check_files(frame)
            def reject():raise WindowError('AUTHORED_LEASE_CLOSE','authored lease close failure')
            frame['leaseStack'].callback(reject)
        with patch.object(registry,'_check_frame_files',side_effect=source_checks):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        path=self.backend.metadata_path('AUTHORED-remote-pins')
        self.assertTrue(path.exists())
        metadata=json.loads(path.read_text(encoding='utf-8'))
        journal=self.backend.request_evidence()
        self.assertTrue(journal.block_path('AUTHORED-remote-pins').exists())
        receipt=json.loads((journal.root/metadata['publicationJournal']/'receipt.json').read_text(encoding='utf-8'))
        self.assertEqual('failed',receipt['terminalStatus'])
        with self.assertRaises(WindowError):journal.recover_index(path,metadata,'AUTHORED-remote-pins')

    def test_cancellation_after_intent_before_replace_keeps_block(self):
        original=evidence.RequestEvidence.prepare_intent
        def cancel_after_intent(journal,record,intent):
            original(journal,record,intent)
            source_request_context._current.get().intent.set()
        with patch.object(evidence.RequestEvidence,'prepare_intent',new=cancel_after_intent):
            response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertFalse(self.backend.metadata_path('AUTHORED-remote-pins').exists())
        journal=self.backend.request_evidence()
        block=journal.block_path('AUTHORED-remote-pins')
        self.assertTrue(block.exists())
        publication=json.loads(block.read_text(encoding='utf-8'))
        self.assertTrue((journal.root/publication['journalId']/'publication-intent.json').exists())


if __name__ == '__main__': unittest.main()
