"""Authored migration/source-frame contracts; no live Qdrant, GPU or source acceptance."""
import copy
import json
import os
from contextlib import ExitStack, contextmanager
from contextvars import ContextVar
from pathlib import Path
from threading import RLock
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import migrated_qdrant_index as migration
import test_source_page_index as source_fixture
import test_oer_server as oer_fixture
import test_oer_native_server as native_fixture
import window_runtime
import index_integrity
from source_page_index import SourcePageIndexRuntime
from index_integrity import inspect_points
from oer_source_registry import _serialize
from source_request_context import RequestControl, request_context
from source_session_scope import source_declaration_sha256, source_scope_sha256
from token_windows import digest, WindowError
from verify_retrieval_offline import descriptor


class AuthoredWitnessProvider:
    def __init__(self):
        self.state = {'valid': True}

    @contextmanager
    def acquire(self, binding):
        lease = SimpleNamespace(verify=lambda actual: self.state['valid'] and actual == binding)
        yield lease


class UnifiedMigratedRequestTest(unittest.TestCase):
    def setUp(self):
        self.fixture = source_fixture.SourcePageIndexHttpTests('test_unpinned_request_preserves_legacy_source_and_echoes_empty_map')
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.witness = AuthoredWitnessProvider()
        witness = patch.object(migration, '_source_frame_witness_provider', return_value=self.witness)
        witness.start(); self.addCleanup(witness.stop)
        f = self.fixture
        self.backend, self.runtime = f.backend, f.rt
        self.key = 'AUTHORED-pins-index'
        identities = {kind: {'model': self.backend.MODELS[kind],
                    'files': [{'relativePath':'authored.bin', 'bytes':1, 'sha256':'a'*64}],
                    'backendSha256':'b'*64, 'tokenizerClass':'CharacterTokenizer',
                    'fast':True, 'modelMaxLength':512} for kind in ('embedding', 'reranker')}
        self.runtime.identity = lambda kind: copy.deepcopy(identities[kind])
        indexed = f.index()
        self.assertEqual(200, indexed.status_code, indexed.text)
        source = copy.deepcopy(self.backend._projects[self.key])
        original_collection = source['collection']
        self.target = 'oer_server_authored'
        f.db.collections[self.target] = copy.deepcopy(f.db.collections[original_collection])
        f.db.vector_params[self.target] = copy.deepcopy(f.db.vector_params[original_collection])
        actual = inspect_points(f.db, original_collection, source['windows'], source['signature'],
                                source['vectorIntegrity']['dimension'])
        self.candidate = {**source, 'collection':self.target}
        self.metadata_file = f.root / 'migration-candidate.json'
        self.metadata_file.write_text(json.dumps(self.candidate), encoding='utf-8')
        model_publications = {kind: {'sha256': ('c' if kind == 'embedding' else 'd') * 64}
                              for kind in ('embedding','reranker')}
        self.runtime.model_publications = {kind:object() for kind in model_publications}
        self.runtime.publication_identity = lambda kind: {'manifestSha256':model_publications[kind]['sha256']}
        frozen = {'schema':'consense.frozen-fixed-development-baseline.v1',
                  'indexSignature':digest(source['signature']), 'collection':original_collection,
                  'vectorIntegrity':source['vectorIntegrity'], 'modelPublications':model_publications}
        self.frozen_file = f.root / 'migration-frozen.json'
        self.frozen_file.write_text(json.dumps(frozen), encoding='utf-8')
        receipt = {'schema':'consense.qdrant-server-migration.v1',
            'status':'completed_full_storage_readback_candidate_not_adopted',
            'target':{'url':'http://127.0.0.1:16333','collection':self.target}, 'targetAdopted':False,
            'activeConfigurationChanged':False, 'allOriginalPersistentStateBytesUnchanged':True,
            'frozenManifest':descriptor(self.frozen_file), 'candidateMetadata':descriptor(self.metadata_file),
            'indexSignature':frozen['indexSignature'], 'parentCount':source['parentCount'],
            'vectorWindowCount':source['vectorPoints'], 'sourceVectorIntegrity':actual,
            'targetVectorIntegrity':copy.deepcopy(actual)}
        self.receipt_file = f.root / 'migration-receipt.json'
        self.receipt_file.write_text(json.dumps(receipt), encoding='utf-8')
        self.environ = {migration.ENV:str(self.receipt_file), migration.ENV+'_SHA256':descriptor(self.receipt_file)['sha256'],
                        'CONSENSE_QDRANT_URL':receipt['target']['url']}
        # The old development freeze uses sourcePath; canonical fixture chunks use CAS descriptors.
        # This substitutes only that legacy freeze validation, never vector or source-frame checks.
        with patch.object(migration, 'validate_metadata'):
            self.reader = migration.MigratedIndex(self.environ)
        self.reader.environ = os.environ
        self.backend._migrated_index = self.reader
        self.backend._projects[self.key] = {'chunks':[{'id':'WRONG-LOCAL'}], 'signature':{}}
        self.query = {'projectId':self.key, 'query':'Visible', 'candidates':4, 'limit':2}
        self.candidate = {**self.candidate, 'sourceScope':self.valid_scope()}
        self.repin(self.candidate)

    def query_migrated(self):
        with patch.dict(os.environ, self.environ):
            return self.fixture.client.post('/retrieve', json=self.query)

    def repin(self, candidate):
        self.metadata_file.write_text(json.dumps(candidate), encoding='utf-8')
        receipt = copy.deepcopy(self.reader.receipt)
        receipt['candidateMetadata'] = descriptor(self.metadata_file)
        self.receipt_file.write_text(json.dumps(receipt), encoding='utf-8')
        self.environ[migration.ENV+'_SHA256'] = descriptor(self.receipt_file)['sha256']
        with patch.object(migration, 'validate_metadata'):
            self.reader = migration.MigratedIndex(self.environ)
        self.reader.environ = os.environ
        self.backend._migrated_index = self.reader

    def valid_scope(self):
        f = self.fixture; registry = f.app.state.source_registry
        projection = f.projection['projectionSha256']; chunks = self.candidate['chunks']
        policy_sha = f.policy.policyIdentity['manifestSha256']; pins = {}
        declaration = source_declaration_sha256(self.key, 'vetting', [projection], pins, chunks, policy_sha)
        with registry.validation_frame(software_guard=self.runtime.algorithms):
            registry.validate_index_scope(chunks, [projection], module='vetting',
                                          sorted_order=not self.runtime.structural_recipe.enabled)
            self.runtime._validate_source_page_pins(chunks)
            frame_sha = source_scope_sha256(registry, self.key, 'vetting', [projection], pins, chunks, policy_sha)
        return {'projectId':self.key, 'module':'vetting', 'projectionSha256':[projection],
                'sourcePageEvidence':pins, 'sourceScopeSha256':frame_sha, 'sourceDeclarationSha256':declaration}

    def test_one_verified_metadata_binds_both_tails_without_local_fallback(self):
        original = migration.verify_reuse
        with patch.object(migration, 'verify_reuse', wraps=original) as seal:
            response = self.query_migrated()
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(1, seal.call_count)
        self.assertEqual('pinned-migrated-server-read-only', response.json()['indexAccess']['mode'])
        self.assertEqual('WRONG-LOCAL', self.backend._projects[self.key]['chunks'][0]['id'])

    def test_response_fields_are_detached_before_frame_exit(self):
        candidate = copy.deepcopy(self.candidate)
        candidate['structuralCoverage'] = {'contextUnavailable':[{'reason':'original'}]}
        self.repin(candidate)
        registry = self.runtime.source_registry
        original_frame, original_retrieve = registry.validation_frame, self.backend.retrieve

        @contextmanager
        def mutate_after_exit(**kwargs):
            with original_frame(**kwargs):
                yield
            self.reader.metadata['structuralCoverage']['contextUnavailable'][0]['reason'] = 'changed-after-exit'
            self.reader.receipt['target']['collection'] = 'changed-after-exit'

        def with_structural_coverage(request):
            self.assertTrue(self.backend.LOCK._is_owned())
            result = original_retrieve(request)
            # Exercise the same reference returned by the structural retrieval branch.
            result['structuralCoverage'] = self.reader.metadata['structuralCoverage']
            return result

        with patch.object(registry, 'validation_frame', side_effect=mutate_after_exit), \
                patch.object(self.backend, 'retrieve', side_effect=with_structural_coverage):
            response = self.query_migrated()
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertEqual('original', body['structuralCoverage']['contextUnavailable'][0]['reason'])
        self.assertEqual(self.target, body['indexAccess']['target']['collection'])
        self.assertEqual('changed-after-exit', self.reader.receipt['target']['collection'])

    def test_candidate_metadata_and_receipt_mutation_fail_before_model(self):
        for target in ('metadata-memory', 'source-memory', 'receipt-memory', 'frozen-memory',
                       'receipt-descriptor', 'receipt-file', 'metadata-file', 'configuration'):
            with self.subTest(target=target):
                reader = self.reader
                metadata_before = copy.deepcopy(reader.metadata)
                receipt_before, frozen_before = copy.deepcopy(reader.receipt), copy.deepcopy(reader.frozen)
                descriptor_before = copy.deepcopy(reader.receipt_file)
                receipt_bytes, metadata_bytes = self.receipt_file.read_bytes(), self.metadata_file.read_bytes()
                before = len(self.fixture.encodes)
                if target == 'metadata-memory': reader.metadata['chunks'][0]['content'] += ' tampered'
                elif target == 'source-memory': reader.metadata['chunks'][0]['sourceSha256'] = '0' * 64
                elif target == 'receipt-memory': reader.receipt['target']['collection'] = 'wrong-target'
                elif target == 'frozen-memory': reader.frozen['indexSignature'] = '0' * 64
                elif target == 'receipt-descriptor': reader.receipt_file['sha256'] = '0' * 64
                elif target == 'receipt-file': self.receipt_file.write_bytes(receipt_bytes + b' ')
                elif target == 'metadata-file': self.metadata_file.write_bytes(metadata_bytes + b' ')
                else: reader.environ = {**self.environ, 'CONSENSE_QDRANT_URL':'http://127.0.0.1:9999'}
                with patch.object(migration, 'verify_reuse', side_effect=AssertionError('must fail before seal')):
                    response = self.query_migrated()
                self.assertNotEqual(200, response.status_code)
                self.assertEqual(before, len(self.fixture.encodes))
                reader.metadata = metadata_before
                reader.receipt, reader.frozen, reader.receipt_file = receipt_before, frozen_before, descriptor_before
                self.receipt_file.write_bytes(receipt_bytes); self.metadata_file.write_bytes(metadata_bytes)
                reader.environ = os.environ

    def test_frame_exit_rejects_changed_receipt_after_query(self):
        original = self.backend.encode_embeddings
        def changed(texts):
            result = original(texts)
            self.receipt_file.write_bytes(self.receipt_file.read_bytes() + b' ')
            return result
        with patch.object(self.backend, 'encode_embeddings', side_effect=changed):
            response = self.query_migrated()
        self.assertNotEqual(200, response.status_code)

    def test_frame_exit_rejects_metadata_memory_and_reader_swap(self):
        for change in ('metadata-memory', 'reader'):
            with self.subTest(change=change):
                before = copy.deepcopy(self.reader.metadata)
                original = self.backend.encode_embeddings
                def changed(texts):
                    result = original(texts)
                    if change == 'metadata-memory':
                        self.reader.metadata['signature']['invented'] = 'tampered'
                    else:
                        self.backend._migrated_index = SimpleNamespace(last_observation=self.reader.last_observation)
                    return result
                with patch.object(self.backend, 'encode_embeddings', side_effect=changed):
                    response = self.query_migrated()
                self.assertNotEqual(200, response.status_code)
                self.reader.metadata = before
                self.backend._migrated_index = self.reader

    def test_bad_source_is_rejected_before_model_or_vector_seal(self):
        with patch.object(self.runtime, '_validate_native_sources',
                          side_effect=WindowError('NATIVE_CANONICAL_SOURCE_UNAVAILABLE','authored bad source')), \
                patch.object(migration, 'verify_reuse', side_effect=AssertionError('seal before source proof')) as seal:
            before = len(self.fixture.encodes)
            response = self.query_migrated()
        self.assertNotEqual(200, response.status_code)
        self.assertEqual(before, len(self.fixture.encodes))
        seal.assert_not_called()

    def test_missing_migration_source_scope_fails_before_model_or_seal(self):
        candidate = copy.deepcopy(self.candidate)
        candidate.pop('sourceScope')
        self.repin(candidate)
        before = len(self.fixture.encodes)
        with patch.object(migration, 'verify_reuse', side_effect=AssertionError('seal before source scope')) as seal:
            response = self.query_migrated()
        self.assertNotEqual(200, response.status_code)
        self.assertEqual(before, len(self.fixture.encodes))
        seal.assert_not_called()

    def test_declared_local_scope_binds_projection_declaration_and_actual_frame(self):
        valid = self.valid_scope()
        self.repin({**self.candidate, 'sourceScope':valid})
        response = self.query_migrated()
        self.assertEqual(200, response.status_code, response.text)
        for name, value in (('projectionSha256',['0'*64]), ('sourceDeclarationSha256','0'*64),
                            ('sourceScopeSha256','0'*64)):
            with self.subTest(name=name):
                self.repin({**self.candidate, 'sourceScope':{**valid, name:value}})
                before = len(self.fixture.encodes)
                with patch.object(migration, 'verify_reuse', side_effect=AssertionError('seal before scope')):
                    response = self.query_migrated()
                self.assertNotEqual(200, response.status_code)
                self.assertEqual(before, len(self.fixture.encodes))

    def test_changed_vector_is_rejected_before_query_embedding(self):
        point = next(iter(self.fixture.db.collections[self.target].values()))
        point.vector = [0., 1.]
        before = len(self.fixture.encodes)
        response = self.query_migrated()
        self.assertNotEqual(200, response.status_code)
        self.assertEqual(before, len(self.fixture.encodes))

    def test_default_unverified_isolation_rejects_before_seal_or_model(self):
        before = len(self.fixture.encodes)
        with patch.dict(self.witness.state, {'valid':False}), \
                patch.object(migration, 'verify_reuse', side_effect=AssertionError('seal before isolation')) as seal:
            response = self.query_migrated()
        self.assertEqual(503, response.status_code)
        self.assertEqual('MIGRATED_INDEX_ISOLATION_UNVERIFIED', response.json()['detail']['kind'])
        self.assertEqual(before, len(self.fixture.encodes))
        seal.assert_not_called()

    def test_missing_witness_does_not_construct_reader_or_read_metadata(self):
        before = len(self.fixture.encodes)
        with patch.object(self.backend, '_migrated_index', None), \
                patch.object(migration, '_source_frame_witness_provider', return_value=None), \
                patch.object(migration, 'MigratedIndex', side_effect=AssertionError('reader must not open')) as constructor, \
                patch.object(migration, 'verify_reuse', side_effect=AssertionError('seal must not run')) as seal:
            response = self.query_migrated()
        self.assertEqual(503, response.status_code)
        self.assertEqual('MIGRATED_INDEX_ISOLATION_UNVERIFIED', response.json()['detail']['kind'])
        self.assertEqual(before, len(self.fixture.encodes))
        constructor.assert_not_called(); seal.assert_not_called()

    def test_publication_block_precedes_unavailable_witness_without_opening_reader(self):
        journal = self.backend.request_evidence()
        journal.root.mkdir(parents=True, exist_ok=True)
        journal.block_path(self.key).write_bytes(b'authored-block')
        with patch.object(self.backend, '_migrated_index', None), \
                patch.object(migration, '_source_frame_witness_provider', return_value=None), \
                patch.object(migration, 'MigratedIndex', side_effect=AssertionError('reader must not open')) as constructor:
            response = self.query_migrated()
        self.assertEqual(503, response.status_code)
        self.assertEqual('INDEX_PUBLICATION_DURABILITY_UNKNOWN', response.json()['detail']['kind'])
        self.assertIn('blocked', response.json()['detail']['message'])
        constructor.assert_not_called()

    def test_changed_receipt_rejects_before_hashing_metadata_object(self):
        self.receipt_file.write_bytes(self.receipt_file.read_bytes() + b' ')
        original = migration._metadata_digest
        def digest_without_metadata(value):
            if value is self.reader.metadata: raise AssertionError('large metadata hash before receipt')
            return original(value)
        with patch.object(migration, '_metadata_digest', side_effect=digest_without_metadata) as observed:
            response = self.query_migrated()
        self.assertNotEqual(200, response.status_code)
        self.assertTrue(observed.called)

    def test_witness_loss_and_vector_change_during_query_withhold_result(self):
        witness = self.witness.state
        original = self.backend.encode_embeddings
        def changed(texts):
            result = original(texts)
            point = next(iter(self.fixture.db.collections[self.target].values()))
            point.vector = [0., 1.]
            witness['valid'] = False
            return result
        with patch.object(self.backend, 'encode_embeddings', side_effect=changed):
            response = self.query_migrated()
        self.assertEqual(503, response.status_code)
        self.assertEqual('MIGRATED_INDEX_ISOLATION_UNVERIFIED', response.json()['detail']['kind'])

    def readiness_request(self, path, **extra):
        payload = {'projectId':self.key, 'module':'vetting',
                   'projectionSha256':self.candidate['sourceScope']['projectionSha256'], **extra}
        with patch.dict(os.environ, self.environ):
            return self.fixture.client.post(path, json=payload)

    def test_migrated_readiness_uses_current_target_and_correct_seal_budget(self):
        signature = digest(self.reader.metadata['signature'])
        status_path = '/index-canonical/status'
        availability_path = '/index-canonical/availability'
        with patch.object(index_integrity, 'verify_reuse', wraps=index_integrity.verify_reuse) as seal:
            status = self.readiness_request(status_path)
        self.assertEqual(200, status.status_code, status.text)
        self.assertEqual(signature, status.json()['signature'])
        self.assertTrue(status.json()['fullVectorIntegrityChecked'])
        self.assertFalse(status.json()['migrationRuntimeCompatibilityVerified'])
        self.assertEqual(1, seal.call_count)
        self.assertEqual(self.target, seal.call_args.kwargs['collection'])
        with patch.object(index_integrity, 'verify_reuse', side_effect=AssertionError('no full seal in availability')) as seal, \
                patch.object(migration, 'verify_reuse', side_effect=AssertionError('no reader seal in availability')) as reader_seal, \
                patch.object(index_integrity, 'verify_availability', wraps=index_integrity.verify_availability) as light:
            availability = self.readiness_request(availability_path, expectedSignature=signature,
                                                  expectedGeneration=None)
        self.assertEqual(200, availability.status_code, availability.text)
        self.assertFalse(availability.json()['fullVectorIntegrityChecked'])
        self.assertFalse(availability.json()['migrationRuntimeCompatibilityVerified'])
        self.assertEqual('unavailable', availability.json()['migrationMetadataMetrics'])
        self.assertNotIn('metadataBytesRead', availability.json())
        self.assertNotIn('metadataJsonDecodes', availability.json())
        self.assertEqual(self.target, availability.json()['collection'])
        self.assertEqual(2, light.call_count)
        seal.assert_not_called(); reader_seal.assert_not_called()
        with patch.object(index_integrity, 'verify_reuse', side_effect=AssertionError('no full seal in bootstrap')):
            bootstrap = self.readiness_request(availability_path, coldBootstrap=True)
        self.assertEqual(200, bootstrap.status_code, bootstrap.text)
        self.assertTrue(bootstrap.json()['coldBootstrap'])

    def test_cold_reader_metrics_are_unknown_not_zero(self):
        with patch.object(self.backend, '_migrated_index', None), \
                patch.object(migration, 'validate_metadata'):
            response = self.readiness_request('/index-canonical/availability', coldBootstrap=True)
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual('unavailable', response.json()['migrationMetadataMetrics'])
        self.assertNotIn('metadataBytesRead', response.json())
        self.assertNotIn('metadataJsonDecodes', response.json())

    def test_migrated_availability_rejects_incompatible_contract_and_signature_version(self):
        candidate = copy.deepcopy(self.candidate)
        candidate['contract'] = 'authored-incompatible-contract'
        self.repin(candidate)
        with patch.object(index_integrity, 'verify_reuse', side_effect=AssertionError('no full seal')), \
                patch.object(index_integrity, 'verify_availability', side_effect=AssertionError('must reject first')):
            response = self.readiness_request('/index-canonical/availability', coldBootstrap=True)
        self.assertNotEqual(200, response.status_code, response.text)
        self.repin(self.candidate)
        with patch.object(self.runtime, 'signature_version', return_value=999), \
                patch.object(index_integrity, 'verify_availability', side_effect=AssertionError('must reject first')):
            response = self.readiness_request('/index-canonical/availability', coldBootstrap=True)
        self.assertNotEqual(200, response.status_code, response.text)

    def test_availability_does_not_assert_model_compatibility_and_retrieve_checks_it(self):
        identity = self.runtime.identity
        def changed(kind):
            result = identity(kind)
            if kind == 'embedding': result['model'] = 'authored-incompatible-model'
            return result
        with patch.object(self.runtime, 'identity', side_effect=changed):
            response = self.readiness_request('/index-canonical/availability', coldBootstrap=True)
            self.assertEqual(200, response.status_code, response.text)
            self.assertFalse(response.json()['migrationRuntimeCompatibilityVerified'])
            retrieval = self.query_migrated()
        self.assertNotEqual(200, retrieval.status_code, retrieval.text)

    def test_migrated_readiness_rejects_bad_expectations_scope_and_memory(self):
        path = '/index-canonical/availability'
        signature = digest(self.reader.metadata['signature'])
        for extra in ({'expectedSignature':'0'*64}, {'expectedSignature':signature, 'expectedGeneration':'1'*32},
                      {'expectedSignature':signature, 'sourcePageEvidence':{self.fixture.projection['projectionSha256']:'2'*64}}):
            with self.subTest(extra=extra):
                response = self.readiness_request(path, **extra)
                self.assertNotEqual(200, response.status_code, response.text)
        for change in ('collection', 'scope', 'metadata', 'receipt'):
            with self.subTest(change=change):
                metadata, receipt = copy.deepcopy(self.reader.metadata), copy.deepcopy(self.reader.receipt)
                if change == 'collection': self.reader.metadata['collection'] = 'wrong-target'
                elif change == 'scope': self.reader.metadata['sourceScope']['projectId'] = 'wrong-project'
                elif change == 'metadata': self.reader.metadata['chunks'][0]['content'] += 'tampered'
                else: self.reader.receipt['target']['collection'] = 'wrong-target'
                with patch.object(index_integrity, 'verify_reuse', side_effect=AssertionError('seal before candidate proof')):
                    response = self.readiness_request('/index-canonical/status')
                self.assertNotEqual(200, response.status_code, response.text)
                self.reader.metadata, self.reader.receipt = metadata, receipt

    def test_migrated_readiness_requires_witness_and_rechecks_frame_exit(self):
        for path, extra in (('/index-canonical/status', {}),
                            ('/index-canonical/availability', {'coldBootstrap':True})):
            with self.subTest(path=path), patch.dict(self.witness.state, {'valid':False}), \
                    patch.object(index_integrity, 'verify_reuse', side_effect=AssertionError('seal before witness')):
                response = self.readiness_request(path, **extra)
            self.assertEqual(503, response.status_code, response.text)
            self.assertEqual('MIGRATED_INDEX_ISOLATION_UNVERIFIED', response.json()['detail']['kind'])
        original = index_integrity.verify_availability
        witness = self.witness.state
        def lose_witness(*args, **kwargs):
            result = original(*args, **kwargs)
            witness['valid'] = False
            return result
        with patch.object(index_integrity, 'verify_availability', side_effect=lose_witness):
            response = self.readiness_request('/index-canonical/availability', coldBootstrap=True)
        self.assertEqual(503, response.status_code, response.text)
        self.assertEqual('MIGRATED_INDEX_ISOLATION_UNVERIFIED', response.json()['detail']['kind'])

    def test_migrated_readiness_rejects_cold_missing_witness_before_reader(self):
        for path, extra in (('/index-canonical/status', {}),
                            ('/index-canonical/availability', {'coldBootstrap':True})):
            with self.subTest(path=path), patch.object(self.backend, '_migrated_index', None), \
                    patch.object(migration, '_source_frame_witness_provider', return_value=None), \
                    patch.object(migration, 'MigratedIndex', side_effect=AssertionError('must not construct')) as reader:
                response = self.readiness_request(path, **extra)
            self.assertEqual(503, response.status_code, response.text)
            self.assertEqual('MIGRATED_INDEX_ISOLATION_UNVERIFIED', response.json()['detail']['kind'])
            reader.assert_not_called()

    def test_migrated_readiness_rejects_validly_pinned_but_wrong_source_scope(self):
        original = copy.deepcopy(self.candidate)
        for field, value in (('projectId','wrong-project'), ('module','advisory'),
                             ('sourceDeclarationSha256','0'*64), ('sourceScopeSha256','0'*64),
                             ('sourcePageEvidence',{self.fixture.projection['projectionSha256']:'2'*64})):
            with self.subTest(field=field):
                candidate = copy.deepcopy(original)
                candidate['sourceScope'][field] = value
                self.repin(candidate)
                with patch.object(index_integrity, 'verify_reuse', side_effect=AssertionError('seal before scope')):
                    response = self.readiness_request('/index-canonical/status')
                self.assertNotEqual(200, response.status_code, response.text)
        self.repin(original)

    def test_migrated_readiness_rejects_reader_swap_and_metadata_mutation_on_exit(self):
        original = index_integrity.verify_reuse
        for change in ('reader', 'metadata'):
            with self.subTest(change=change):
                metadata = copy.deepcopy(self.reader.metadata)
                def alter(*args, **kwargs):
                    actual = original(*args, **kwargs)
                    if change == 'reader': self.backend._migrated_index = SimpleNamespace()
                    else: self.reader.metadata['signature']['invented'] = 'tampered'
                    return actual
                with patch.object(index_integrity, 'verify_reuse', side_effect=alter):
                    response = self.readiness_request('/index-canonical/status')
                self.assertNotEqual(200, response.status_code, response.text)
                self.reader.metadata = metadata
                self.backend._migrated_index = self.reader

    def test_migrated_status_checks_this_request_seal_not_previous_observation(self):
        self.reader.last_observation = {'target':{'collection':self.target}}
        wrong = copy.deepcopy(self.reader.receipt['targetVectorIntegrity'])
        wrong['pointsSha256'] = '0'*64
        with patch.object(index_integrity, 'verify_reuse', return_value=wrong) as seal:
            response = self.readiness_request('/index-canonical/status')
        self.assertNotEqual(200, response.status_code, response.text)
        self.assertEqual(1, seal.call_count)

    def test_missing_frame_direct_runtime_fails_closed(self):
        with patch.dict(os.environ, self.environ):
            with self.assertRaisesRegex(WindowError, 'source validation frame'):
                self.runtime.retrieve(self.backend.RetrieveRequest(**self.query))


class MigratedPagePinsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = source_fixture.SourcePagePinLifecycleTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.witness = AuthoredWitnessProvider()
        witness = patch.object(migration, '_source_frame_witness_provider', return_value=self.witness)
        witness.start(); self.addCleanup(witness.stop)
        f = self.fixture
        signature = f.save()
        self.metadata = {'chunks':copy.deepcopy(f.chunks), 'signature':signature,
                         'sourceScope':{'projectId':f.key, 'module':'vetting', 'sourcePageEvidence':dict(f.pins)}}
        class Reader:
            def __init__(inner):
                inner.metadata = self.metadata; inner.calls = 0
                inner.receipt_file = {'sha256':'a'*64}
                inner.receipt = {'target':{'url':'http://127.0.0.1:28683','collection':'authored'}, 'targetVectorIntegrity':{}}
            def candidate_identity(inner, key): return ('authored-candidate', key)
            def read(inner, key, runtime):
                inner.calls += 1
                self.assertEqual(self.metadata['signature'], runtime.signature(self.metadata['chunks']))
                return inner.metadata
        self.reader = Reader()
        self.backend = SimpleNamespace(LOCK=RLock(), _projects={f.key:{'chunks':[{'id':'WRONG'}]}},
            _migrated_index=self.reader, project_key=lambda value:value,
            metadata_path=lambda key: (_ for _ in ()).throw(AssertionError('local metadata must not be read')))
        frame = ContextVar('authored_migration_frame', default=None)
        f.fixture.canonical._validation_frame = frame
        class Base:
            def __init__(inner):
                inner.s = self.backend; inner.source_registry = f.fixture.canonical
                inner.target_module = 'vetting'
                inner.scope_policy = SimpleNamespace(policyIdentity={'manifestSha256':f.fixture.policy_sha})
            def signature(inner, chunks): return {'corpus':digest(chunks)}
            def _validate_native_sources(inner, chunks): pass
            def retrieve(inner, request):
                value = self.reader.read(request.projectId, inner)
                holder = window_runtime._retrieved_metadata.get()
                holder.append(value)
                return {'hits':[]}
        class Runtime(SourcePageIndexRuntime, Base): pass
        self.runtime = Runtime()
        self.frame = frame

    def test_actual_registered_page_pins_survive_single_scoped_read(self):
        with patch.dict(os.environ, {migration.ENV:'authored-pin'}):
            token = self.frame.set({'indexMetadata':{}, 'leaseStack':ExitStack()})
            try:
                migration.migration_candidate_for_frame(self.backend, self.fixture.key, self.runtime.source_registry)
                self.frame.get()['indexMetadata'][self.fixture.key]['migrationSourceScopeVerified'] = True
                self.assertEqual({'hits':[]}, self.runtime.retrieve(SimpleNamespace(projectId=self.fixture.key)))
                self.assertEqual(1, self.reader.calls)
                record = self.frame.get()['indexMetadata'][self.fixture.key]
                self.assertEqual(record['expected'], record['identity']())
                self.assertIsNone(self.runtime.source_page_index_pins.get())
            finally:
                self.frame.get()['leaseStack'].close()
                self.frame.reset(token)

    def test_wrong_page_pin_is_rejected_before_reader_or_model(self):
        self.metadata['signature']['sourcePageEvidence'][self.fixture.fixture.projection_sha] = '1' * 64
        self.metadata['sourceScope']['sourcePageEvidence'][self.fixture.fixture.projection_sha] = '1' * 64
        with patch.dict(os.environ, {migration.ENV:'authored-pin'}):
            token = self.frame.set({'indexMetadata':{}, 'leaseStack':ExitStack()})
            try:
                migration.migration_candidate_for_frame(self.backend, self.fixture.key, self.runtime.source_registry)
                self.frame.get()['indexMetadata'][self.fixture.key]['migrationSourceScopeVerified'] = True
                with self.assertRaises((WindowError, ValueError)):
                    self.runtime.retrieve(SimpleNamespace(projectId=self.fixture.key))
                self.assertEqual(0, self.reader.calls)
                self.assertIsNone(self.runtime.source_page_index_pins.get())
            finally:
                self.frame.get()['leaseStack'].close()
                self.frame.reset(token)

    def test_individually_valid_but_different_scope_and_signature_pins_fail(self):
        page = self.fixture.fixture
        first, second = page.evidence, '2' * 64
        overlay = {**page.overlay, 'combinedEvidenceIdentitySha256':second, 'overlaySha256':'3'*64}
        overlay_ref = page.store.put_bytes(_serialize(overlay))
        def proof(value, projection, store):
            store.read_asset(page.source_ref); store.read_asset(page.image_ref)
            return [{**copy.deepcopy(page.handle),
                     'combinedEvidenceIdentitySha256':value['combinedEvidenceIdentitySha256']}]
        with patch('source_page_registry.validate_source_render_overlay', side_effect=proof):
            response = page.publish({'overlayRef':overlay_ref}, '?module=advisory&evidence=' + second)
            self.assertEqual(200, response.status_code, response.text)
            for evidence in (first, second):
                page.pages._read(page.projection_sha, page.source_sha, module='vetting',
                    evidence=evidence, policy_sha256=page.policy_sha)
            for signature_pin, scope_pin in ((first, second), (second, first)):
                with self.subTest(signature_pin=signature_pin), patch.dict(os.environ, {migration.ENV:'authored-pin'}):
                    self.metadata['signature']['sourcePageEvidence'] = {page.projection_sha:signature_pin}
                    self.metadata['sourceScope'] = {'projectId':self.fixture.key, 'module':'vetting',
                                                   'sourcePageEvidence':{page.projection_sha:scope_pin}}
                    token = self.frame.set({'indexMetadata':{}, 'leaseStack':ExitStack()})
                    outer = self.runtime.source_page_index_pins.set({page.projection_sha:scope_pin})
                    try:
                        with request_context(RequestControl(10)):
                            with self.assertRaisesRegex(WindowError, 'pins must agree'):
                                self.runtime.retrieve(SimpleNamespace(projectId=self.fixture.key))
                        self.assertEqual(0, self.reader.calls)
                    finally:
                        self.runtime.source_page_index_pins.reset(outer)
                        self.frame.get()['leaseStack'].close()
                        self.frame.reset(token)

    def test_empty_scope_pins_are_valid_but_missing_or_wrong_identity_is_not(self):
        self.metadata['signature'].pop('sourcePageEvidence')
        with patch.dict(os.environ, {migration.ENV:'authored-pin'}):
            for scope in ({'projectId':self.fixture.key, 'module':'vetting'},
                          {'projectId':'other', 'module':'vetting', 'sourcePageEvidence':{}},
                          {'projectId':self.fixture.key, 'module':'advisory', 'sourcePageEvidence':{}}):
                self.metadata['sourceScope'] = scope
                token = self.frame.set({'indexMetadata':{}, 'leaseStack':ExitStack()})
                try:
                    with self.assertRaises(WindowError):
                        self.runtime.retrieve(SimpleNamespace(projectId=self.fixture.key))
                    self.assertEqual(0, self.reader.calls)
                finally:
                    self.frame.get()['leaseStack'].close()
                    self.frame.reset(token)
            self.metadata['sourceScope'] = {'projectId':self.fixture.key, 'module':'vetting', 'sourcePageEvidence':{}}
            token = self.frame.set({'indexMetadata':{}, 'leaseStack':ExitStack()})
            outer = self.runtime.source_page_index_pins.set({})
            try:
                migration.migration_candidate_for_frame(self.backend, self.fixture.key, self.runtime.source_registry)
                self.frame.get()['indexMetadata'][self.fixture.key]['migrationSourceScopeVerified'] = True
                with request_context(RequestControl(10)):
                    self.assertEqual({'hits':[]}, self.runtime.retrieve(SimpleNamespace(projectId=self.fixture.key)))
                self.assertEqual(1, self.reader.calls)
            finally:
                self.runtime.source_page_index_pins.reset(outer)
                self.frame.get()['leaseStack'].close()
                self.frame.reset(token)

    def test_remote_scope_and_inherited_pins_are_required(self):
        self.metadata['signature'].pop('sourcePageEvidence')
        self.metadata.pop('sourceScope')
        with patch.dict(os.environ, {migration.ENV:'authored-pin'}):
            token = self.frame.set({'indexMetadata':{}, 'leaseStack':ExitStack()})
            outer = self.runtime.source_page_index_pins.set({})
            try:
                with request_context(RequestControl(10)):
                    with self.assertRaises(WindowError):
                        self.runtime.retrieve(SimpleNamespace(projectId=self.fixture.key))
                self.assertEqual(0, self.reader.calls)
            finally:
                self.runtime.source_page_index_pins.reset(outer)
                self.frame.get()['leaseStack'].close()
                self.frame.reset(token)
            self.metadata['sourceScope'] = {'projectId':self.fixture.key, 'module':'vetting', 'sourcePageEvidence':{}}
            token = self.frame.set({'indexMetadata':{}, 'leaseStack':ExitStack()})
            try:
                with request_context(RequestControl(10)):
                    with self.assertRaises(WindowError):
                        self.runtime.retrieve(SimpleNamespace(projectId=self.fixture.key))
                self.assertEqual(0, self.reader.calls)
            finally:
                self.frame.get()['leaseStack'].close()
                self.frame.reset(token)


class BasicOerMigrationGuardTest(unittest.TestCase):
    def test_missing_scope_is_rejected_without_source_page_wrapper(self):
        fixture = oer_fixture.OerHttpContractTest('test_canonical_revision_duplicates_stale_source_and_module_reject_before_models')
        fixture.setUp(); self.addCleanup(fixture.tearDown)
        registry = fixture.app.state.source_registry
        current = ContextVar('authored_basic_oer_frame', default=None)
        registry._validation_frame = current
        @contextmanager
        def source_frame(**kwargs):
            token = current.set({'indexMetadata':{}, 'leaseStack':ExitStack()})
            try: yield
            finally:
                current.get()['leaseStack'].close()
                current.reset(token)
        registry.validation_frame = source_frame
        class Reader:
            metadata = {'projectId':'AUTHORED-basic', 'chunks':[], 'signature':{}}
            receipt_file = {'sha256':'a'*64}
            receipt = {'target':{'url':'http://127.0.0.1:28683','collection':'authored'}, 'targetVectorIntegrity':{}}
            def candidate_identity(self, key): return ('authored', key)
        fixture.backend._migrated_index = Reader()
        self.assertNotIsInstance(fixture.backend._windows_runtime, SourcePageIndexRuntime)
        with patch.dict(os.environ, {migration.ENV:'authored-pin'}), \
                patch.object(migration, '_source_frame_witness_provider', return_value=AuthoredWitnessProvider()), \
                patch.object(fixture.backend, 'verify_project_publication'), \
                patch.object(fixture.backend, 'read_project', side_effect=AssertionError('unexpected project read')) as read, \
                patch.object(fixture.backend, 'database', side_effect=AssertionError('unexpected database')) as db:
            response = fixture.client.post('/retrieve', json={'projectId':'AUTHORED-basic',
                'query':'authored', 'candidates':1, 'limit':1})
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('SOURCE_SCOPE_IDENTITY_INVALID', response.json()['detail']['kind'])
        read.assert_not_called(); db.assert_not_called(); self.assertEqual([], fixture.encodes)

    def test_native_only_holder_clears_on_success_and_tail_failure(self):
        fixture = native_fixture.NativeHttpTest('test_native_actual_wire_snapshot_and_index_retain_exact_parent_and_blank_parts')
        fixture.setUp(); self.addCleanup(fixture.tearDown)
        projection = fixture.project()
        indexed = fixture.client.post('/index-canonical', json={'projectId':'AUTHORED-native-only',
            'projectionSha256':[projection['projectionSha256']], 'module':'vetting'})
        self.assertEqual(200, indexed.status_code, indexed.text)
        request = fixture.backend.RetrieveRequest(projectId='AUTHORED-native-only', query='Visible', candidates=4, limit=2)
        self.assertIsNone(window_runtime._retrieved_metadata.get())
        self.assertIsInstance(fixture.rt.retrieve(request), dict)
        self.assertIsNone(window_runtime._retrieved_metadata.get())
        original = fixture.rt._validate_native_sources
        calls = []
        def reject_tail(chunks):
            calls.append(True)
            if len(calls) == 2:
                raise WindowError('AUTHORED_TAIL_FAILURE', 'authored failure')
            return original(chunks)
        with patch.object(fixture.rt, '_validate_native_sources', side_effect=reject_tail):
            with self.assertRaises(WindowError):
                fixture.rt.retrieve(request)
        self.assertEqual(2, len(calls))
        self.assertIsNone(window_runtime._retrieved_metadata.get())


if __name__ == '__main__': unittest.main()
