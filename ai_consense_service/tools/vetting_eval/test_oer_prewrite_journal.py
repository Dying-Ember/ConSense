"""Synthetic prewrite-state tests; no external verifier or Qdrant is used."""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import oer_request_evidence as evidence
from source_request_context import RequestControl, commit_pending, index_model_session, prewrite, publication_reference, request_context, stage
from token_windows import WindowError


class PrewriteJournalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.journal = evidence.RequestEvidence(self.root / 'state' / 'run-evidence')
        (self.root / 'state' / 'projects').mkdir(parents=True)
        self.binding = {'projectId': 'p1', 'generation': 'a' * 32, 'collection': 'project_fixture',
                        'priorMetadataSha256': None, 'module': 'vetting',
                        'sourceScopeSha256': 'b' * 64, 'routeIdentity': 'c' * 64}
        self.proof = {'sourceEvidenceSha256': 'd' * 64, 'modelReleaseEvidenceSha256': 'e' * 64}

    def context(self):
        return request_context(RequestControl(20))

    def stage_metadata(self, record, *, alter=None):
        metadata = {'projectId': 'p1', 'generation': 'a' * 32, 'publicationJournal': publication_reference(),
                    'collection': 'project_fixture', 'sourceScope': {'projectId': 'p1', 'module': 'vetting', 'sourceScopeSha256': 'b' * 64}}
        if alter:
            metadata.update(alter)
        raw = json.dumps(metadata).encode()
        pending = self.root / 'pending.json'
        pending.write_bytes(raw)
        intent = {'projectId': 'p1', 'generation': 'a' * 32, 'metadataSha256': hashlib.sha256(raw).hexdigest(),
                  'priorMetadataSha256': None}
        stage(lambda: pending.replace(self.journal.metadata_path('p1')), intent)
        commit_pending(source_frame_verified=True, model_session_verified=True)
        record['response'] = evidence._save(record['directory'] / 'response.json', {'projectId': 'p1'})
        return metadata

    def test_block_is_durable_before_first_write_and_failed_write_stays_blocked(self):
        with self.assertRaisesRegex(RuntimeError, 'synthetic write failure'):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}):
                prewrite(self.binding)
                self.assertEqual(self.binding, json.loads(self.journal.block_path('p1').read_bytes())['prewrite'])
                raise RuntimeError('synthetic write failure')
        fresh = evidence.RequestEvidence(self.journal.root)
        with self.assertRaisesRegex(WindowError, 'blocked'):
            fresh.verify_index(fresh.metadata_path('p1'), None, 'p1')

    def test_failed_block_sync_prevents_following_write(self):
        calls = []
        save = evidence._save
        def failing_save(path, value, *, durable=False):
            result = save(path, value, durable=durable)
            if path.name.endswith('.block'):
                raise OSError('synthetic fsync unknown')
            return result
        with patch.object(evidence, '_save', side_effect=failing_save), self.assertRaises(OSError):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}):
                prewrite(self.binding)
                calls.append('write')
        self.assertEqual([], calls)
        self.assertTrue(self.journal.block_path('p1').exists())

    def test_existing_block_cannot_be_replaced(self):
        with self.assertRaises(RuntimeError):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}):
                prewrite(self.binding)
                raise RuntimeError('interrupted')
        original = self.journal.block_path('p1').read_bytes()
        with self.assertRaises(FileExistsError):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}):
                prewrite({**self.binding, 'generation': 'f' * 32})
        self.assertEqual(original, self.journal.block_path('p1').read_bytes())

    def test_matching_final_intent_and_fake_proof_release_then_recover(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                prewrite(self.binding)
                value = self.stage_metadata(record)
        self.assertFalse(self.journal.block_path('p1').exists())
        fresh = evidence.RequestEvidence(self.journal.root)
        with patch.object(fresh, '_verify_publication_evidence', return_value=self.proof):
            fresh.recover_index(fresh.metadata_path('p1'), value, 'p1')
            fresh.verify_index(fresh.metadata_path('p1'), value, 'p1')

    def test_metadata_mismatch_and_default_verifier_keep_block(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with self.assertRaisesRegex(WindowError, 'Prewrite binding'):
                with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                    prewrite(self.binding)
                    self.stage_metadata(record, alter={'collection': 'different'})
        self.assertTrue(self.journal.block_path('p1').exists())

    def test_without_real_verifier_completed_publication_remains_blocked(self):
        with self.assertRaisesRegex(WindowError, 'not bound to a completed source scope'):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                prewrite(self.binding)
                self.stage_metadata(record)
        self.assertTrue(self.journal.block_path('p1').exists())

    def test_changed_block_stops_before_metadata_replace(self):
        with self.assertRaisesRegex(WindowError, 'Prewrite block identity differs'):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                prewrite(self.binding)
                block = self.journal.block_path('p1')
                value = json.loads(block.read_bytes())
                value['prewrite']['collection'] = 'different'
                block.write_text(json.dumps(value), encoding='utf-8')
                self.stage_metadata(record)
        self.assertFalse(self.journal.metadata_path('p1').exists())
        self.assertTrue(self.journal.block_path('p1').exists())

    def test_final_intent_failure_preserves_block_and_old_metadata(self):
        save = evidence._save
        def failing_save(path, value, *, durable=False):
            if path.name == 'publication-intent.json':
                raise OSError('synthetic intent failure')
            return save(path, value, durable=durable)
        with patch.object(evidence, '_save', side_effect=failing_save), self.assertRaises(OSError):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}) as record:
                prewrite(self.binding)
                self.stage_metadata(record)
        self.assertTrue(self.journal.block_path('p1').exists())
        self.assertFalse(self.journal.metadata_path('p1').exists())

    def test_runtime_creates_block_before_fake_collection_and_upsert(self):
        from window_runtime import WindowRuntime
        from token_windows import digest
        calls = []
        journal = self.journal
        class HttpError(Exception):
            def __init__(self, status, detail):
                self.status, self.detail = status, detail
        class Vector(list):
            def tolist(self): return list(self)
        class FakeDb:
            def collection_exists(self, name): return False
            def create_collection(self, name, **kwargs):
                block = json.loads(journal.block_path('p1').read_bytes())
                self_test.assertEqual(name, block['prewrite']['collection'])
                with self_test.assertRaisesRegex(WindowError, 'blocked'):
                    evidence.RequestEvidence(journal.root).verify_index(journal.metadata_path('p1'), None, 'p1')
                calls.append('create')
            def upsert(self, name, **kwargs):
                self_test.assertTrue(journal.block_path('p1').exists())
                calls.append('upsert')
                raise RuntimeError('synthetic upsert failure')
        self_test = self
        db = FakeDb()
        fake_numpy = SimpleNamespace(isfinite=lambda value: SimpleNamespace(all=lambda: True))
        fake_models = SimpleNamespace(VectorParams=lambda **kw: kw, Distance=SimpleNamespace(COSINE='cosine'),
                                      PointStruct=lambda **kw: kw)
        runtime = WindowRuntime.__new__(WindowRuntime)
        runtime.s = SimpleNamespace(project_key=lambda value: value, validate_corpus=lambda chunks: None,
            LOCK=threading.RLock(), metadata_path=journal.metadata_path, verify_project_publication=lambda key, value: journal.verify_index(journal.metadata_path(key), value, key),
            forget_project=lambda key: None, digest=digest, embedding_model=lambda: SimpleNamespace(get_sentence_embedding_dimension=lambda: 2),
            database=lambda: db, encode_embeddings=lambda texts: [Vector([0.1, 0.2])], HTTPException=HttpError)
        runtime.structural_recipe = SimpleNamespace(enabled=False)
        runtime.signature = lambda chunks: {'fixture': True}
        runtime.prepare_windows = lambda chunks, kind: [{'id': 'w', 'parentId': 'c', 'content': 'x', 'sourceHash': 's', 'role': 'r'}]
        runtime.cache_whitelist = None
        runtime.cache_root = None
        runtime.embedding_cache_identity = lambda dimension: None
        runtime.event_ordinals = {}
        request = SimpleNamespace(projectId='p1', chunks=[SimpleNamespace(model_dump=lambda **kw: {'id': 'c'})], rebuild=False)
        route = {'configurationIdentity': 'c' * 64}
        scope = {'projectId': 'p1', 'module': 'vetting', 'sourceScopeSha256': 'b' * 64}
        modules = {'qdrant_client': SimpleNamespace(models=fake_models), 'numpy': fake_numpy}
        with patch.dict(os.environ, {'CONSENSE_QDRANT_URL': ''}), patch.dict(sys.modules, modules), \
             patch('migration_project_registry.require_writable', return_value=route), \
             patch('window_runtime.vector_sha256', return_value='d' * 64):
            with self.assertRaises(HttpError):
                with self.context(), journal.operation('/index-canonical', {'projectId': 'p1'}), index_model_session(object(), scope):
                    runtime.index(request)
        self.assertEqual(['create', 'upsert'], calls)
        with self.assertRaisesRegex(WindowError, 'blocked'):
            evidence.RequestEvidence(journal.root).verify_index(journal.metadata_path('p1'), None, 'p1')

    def test_remote_server_is_rejected_before_backend_access(self):
        from window_runtime import WindowRuntime
        runtime = WindowRuntime.__new__(WindowRuntime)
        with patch.dict(os.environ, {'CONSENSE_QDRANT_URL': 'http://synthetic-server'}):
            with self.assertRaises(WindowError) as caught:
                runtime.index(object())
            self.assertEqual('MIGRATION_INDEX_WRITE_UNISOLATED', caught.exception.kind)

    def test_prewrite_refuses_request_project_mismatch_before_creating_block(self):
        with self.assertRaisesRegex(WindowError, 'journal request'):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'other'}):
                prewrite(self.binding)
        self.assertFalse(self.journal.block_path('p1').exists())

    def test_integer_request_retains_original_bytes_through_prewrite_and_release(self):
        binding = {**self.binding, 'projectId': '7'}
        request = {'projectId': 7}
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with self.context(), self.journal.operation('/index-canonical', request) as record:
                original = (record['directory'] / 'request.json').read_bytes()
                self.assertEqual(evidence._encode(request), original)
                prewrite(binding)
                self.assertEqual(original, (record['directory'] / 'request.json').read_bytes())
                metadata = {'projectId': '7', 'generation': 'a' * 32, 'publicationJournal': publication_reference(),
                            'collection': 'project_fixture', 'sourceScope': {'projectId': '7', 'module': 'vetting',
                                                                            'sourceScopeSha256': 'b' * 64}}
                raw = json.dumps(metadata).encode()
                pending = self.root / 'numeric-pending.json'
                pending.write_bytes(raw)
                intent = {'projectId': '7', 'generation': 'a' * 32, 'metadataSha256': hashlib.sha256(raw).hexdigest(),
                          'priorMetadataSha256': None}
                stage(lambda: pending.replace(self.journal.metadata_path('7')), intent)
                commit_pending(source_frame_verified=True, model_session_verified=True)
                record['response'] = evidence._save(record['directory'] / 'response.json', {'projectId': '7'})
        self.assertFalse(self.journal.block_path('7').exists())
        self.journal.verify_index(self.journal.metadata_path('7'), metadata, '7')
        self.assertEqual(7, json.loads((record['directory'] / 'request.json').read_bytes())['projectId'])

    def test_integer_mismatch_and_whitespace_are_not_silently_normalized(self):
        binding = {**self.binding, 'projectId': '7'}
        for raw_project in (8, ' 7 '):
            with self.subTest(raw_project=raw_project):
                with self.assertRaisesRegex(WindowError, 'journal request'):
                    with self.context(), self.journal.operation('/index-canonical', {'projectId': raw_project}):
                        prewrite(binding)
                self.assertFalse(self.journal.block_path('7').exists())

    def test_legacy_intent_journal_still_accepts_numeric_request(self):
        with patch.object(self.journal, '_verify_publication_evidence', return_value=self.proof):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 7}) as record:
                metadata = {'projectId': '7', 'generation': 'a' * 32, 'publicationJournal': publication_reference()}
                raw = json.dumps(metadata).encode()
                pending = self.root / 'legacy-numeric-pending.json'
                pending.write_bytes(raw)
                intent = {'projectId': '7', 'generation': 'a' * 32, 'metadataSha256': hashlib.sha256(raw).hexdigest(),
                          'priorMetadataSha256': None}
                stage(lambda: pending.replace(self.journal.metadata_path('7')), intent)
                commit_pending(source_frame_verified=True, model_session_verified=True)
                record['response'] = evidence._save(record['directory'] / 'response.json', {'projectId': '7'})
        self.assertNotIn('prewrite', record['publication'])
        self.journal.verify_index(self.journal.metadata_path('7'), metadata, '7')

    def test_crash_after_prewrite_stays_blocked_after_restart(self):
        program = '''
import json, os, sys
from oer_request_evidence import RequestEvidence
from source_request_context import RequestControl, prewrite, request_context
journal = RequestEvidence(sys.argv[1])
with request_context(RequestControl(20)), journal.operation('/index-canonical', {'projectId': 'p1'}):
    prewrite(json.loads(sys.argv[2]))
    os._exit(17)
'''
        child = subprocess.run([sys.executable, '-B', '-c', program, str(self.journal.root), json.dumps(self.binding)],
                               timeout=10, check=False)
        self.assertEqual(17, child.returncode)
        fresh = evidence.RequestEvidence(self.journal.root)
        with self.assertRaisesRegex(WindowError, 'blocked'):
            fresh.verify_index(fresh.metadata_path('p1'), None, 'p1')

    def test_prewrite_without_final_intent_cannot_complete(self):
        with self.assertRaisesRegex(WindowError, 'without a final intent'):
            with self.context(), self.journal.operation('/index-canonical', {'projectId': 'p1'}):
                prewrite(self.binding)
        self.assertTrue(self.journal.block_path('p1').exists())
