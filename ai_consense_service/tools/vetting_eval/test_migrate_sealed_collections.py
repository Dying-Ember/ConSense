"""Owned Local fixtures exercise complete sealed collection transfer."""
import tempfile
import unittest
import hashlib
import shutil
from pathlib import Path
import json
import os
import subprocess
import sys
import time
from types import SimpleNamespace
from unittest.mock import patch

from qdrant_client import QdrantClient, models
from index_integrity import inspect_points
from token_windows import digest
from migrate_sealed_collections import (migrate_collection, load_inputs, load_single_inputs, verify_runtime_tree,
                                        prepare_runtime_tree, transfer_prepared, transfer_with_journal, server_target, main)
import migrate_sealed_collections as migration


class SealedCollectionMigrationTests(unittest.TestCase):
    def test_registered_advisory_preparation_rechecks_binding_and_original_metadata(self):
        def fingerprint(path):
            raw = path.read_bytes()
            return {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
        def save(path, value):
            path.write_text(json.dumps(value), encoding='utf-8')
            return fingerprint(path)['sha256']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); module = root / 'advisory'; module.mkdir()
            collection = 'selected'; local = root / 'source'
            client = QdrantClient(path=str(local))
            for name in (collection, 'other'):
                client.create_collection(name, vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
                client.upsert(name, points=[models.PointStruct(id=1, vector=[1., 0.], payload={'name': name})], wait=True)
            client.close()
            metadata = module / 'project.json'; metadata.write_text('{}')
            sqlite = module / collection / 'storage.sqlite'; sqlite.parent.mkdir()
            shutil.copyfile(local / 'collection' / collection / 'storage.sqlite', sqlite)
            meta = module / 'qdrant-meta.json'; shutil.copyfile(local / 'meta.json', meta)
            row = {'kind': 'collection', 'number': 2, 'collection': collection,
                   'metadata': fingerprint(metadata), 'sqlite': fingerprint(sqlite)}
            qmeta = {'kind': 'qdrant-meta', 'module': 'advisory', **fingerprint(meta)}
            phase_a = root / 'phase-a.json'; a_sha = save(phase_a, {'state': 'COPIED_UNVERIFIED', 'files': [row, qmeta]})
            phase_b = root / 'phase-b.json'; b_sha = save(phase_b, {'number': 2, 'status': 'PASS',
                'metadataSha256': row['metadata']['sha256'], 'sqliteSha256': row['sqlite']['sha256']})
            mappings = [{'kind': 'index-generation', 'identity': {'collection': collection, 'metadata': row['metadata'],
                         'sqlite': row['sqlite']}, 'futureWritableTarget': str(metadata)},
                        {'kind': 'index-sqlite', 'targetLinux': str(root / 'old-sqlite'),
                         'futureWritableTarget': str(metadata), 'identity': row['sqlite']},
                        {'kind': 'qdrant-meta', 'targetLinux': str(root / 'old-meta'), 'identity': qmeta}]
            register = root / 'register.json'; reg_sha = save(register, {'mappings': mappings})
            amendment = root / 'amendment.json'; amend_sha = save(amendment, {'originalRegisterSha256': reg_sha,
                'corrections': [{'kind': 'index-sqlite', 'mappingIndex': 1, 'baselineTarget': mappings[1]['targetLinux'],
                    'priorTarget': str(metadata), 'correctedTarget': str(sqlite), 'matchedMetadataTarget': str(metadata),
                    'sha256': row['sqlite']['sha256']},
                    {'kind': 'qdrant-meta', 'mappingIndex': 2, 'baselineTarget': mappings[2]['targetLinux'],
                     'priorTarget': None, 'correctedTarget': str(meta), 'sha256': qmeta['sha256']}]})
            args = (register, reg_sha, amendment, amend_sha, phase_a, a_sha, phase_b, b_sha, 2)
            inputs = load_single_inputs(*args); runtime = root / 'prepared'
            result = prepare_runtime_tree(inputs, runtime, 1000000, 10)
            self.assertEqual(meta.read_bytes(), (runtime / 'original-meta.json').read_bytes())
            self.assertEqual(['other'], result['metadataDerivation']['excludedCollections'])
            self.assertEqual(result, verify_runtime_tree(inputs, runtime))
            original_sqlite = sqlite.read_bytes(); sqlite.write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'Registered file bytes differ'): verify_runtime_tree(inputs, runtime)
            sqlite.write_bytes(original_sqlite)
            registered_meta = meta.read_bytes(); meta.write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'Registered file bytes differ'): verify_runtime_tree(inputs, runtime)
            meta.write_bytes(registered_meta)
            original_meta = (runtime / 'original-meta.json').read_bytes()
            (runtime / 'original-meta.json').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'Registered file bytes differ'): verify_runtime_tree(inputs, runtime)
            (runtime / 'original-meta.json').write_bytes(original_meta)
            altered = dict(inputs, collection='other')
            with self.assertRaisesRegex(ValueError, 'Registered Advisory identity changed'):
                verify_runtime_tree(altered, runtime)
            self.assertEqual(result['metadataDerivation'], verify_runtime_tree(inputs, runtime)['metadataDerivation'])
            opened = QdrantClient(path=str(runtime))
            try:
                self.assertEqual({'name': collection}, opened.retrieve(collection, ids=[1])[0].payload)
                self.assertFalse(opened.collection_exists('other'))
            finally:
                opened.close()
            (runtime / 'meta.json').write_bytes(b'{}')
            with self.assertRaises(ValueError): verify_runtime_tree(inputs, runtime)
            (runtime / 'meta.json').write_bytes(migration.derive_single_collection_meta(
                meta.read_bytes(), inputs['qdrantMeta']['sha256'], collection)[0])
            register.write_text('{}')
            with self.assertRaisesRegex(ValueError, 'Pinned input SHA differs'): verify_runtime_tree(inputs, runtime)
            with self.assertRaisesRegex(ValueError, 'Pinned input SHA differs'):
                prepare_runtime_tree(inputs, root / 'rejected', 1000000, 10)
            self.assertFalse((root / 'rejected').exists())

    def test_derived_metadata_opens_only_selected_authored_local_collection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / 'source'; target = root / 'derived'
            source_client = QdrantClient(path=str(source))
            for name, point_id in (('selected', 1), ('other', 2)):
                source_client.create_collection(name, vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
                source_client.upsert(name, points=[models.PointStruct(id=point_id, vector=[1., 0.],
                                                                      payload={'name': name})], wait=True)
            source_client.close()
            original = (source / 'meta.json').read_bytes()
            original_sha = hashlib.sha256(original).hexdigest()
            source_sqlite = source / 'collection' / 'selected' / 'storage.sqlite'
            source_sqlite_sha = hashlib.sha256(source_sqlite.read_bytes()).hexdigest()
            derived, evidence = migration.derive_single_collection_meta(original, original_sha, 'selected')
            self.assertEqual('derived_metadata_only_not_migrated', evidence['status'])
            self.assertEqual(['other', 'selected'], evidence['originalCollections'])
            self.assertEqual(['other'], evidence['excludedCollections'])
            self.assertEqual(json.loads(original)['collections']['selected'],
                             json.loads(derived)['collections']['selected'])
            migration.verify_single_collection_meta(original, original_sha, 'selected', derived, evidence)
            target.mkdir()
            (target / 'meta.json').write_bytes(derived)
            (target / 'collection' / 'selected').mkdir(parents=True)
            shutil.copyfile(source_sqlite, target / 'collection' / 'selected' / 'storage.sqlite')
            target_client = QdrantClient(path=str(target))
            try:
                self.assertTrue(target_client.collection_exists('selected'))
                self.assertFalse(target_client.collection_exists('other'))
                points = target_client.retrieve('selected', ids=[1], with_payload=True, with_vectors=True)
                self.assertEqual(1, len(points))
                self.assertEqual({'name': 'selected'}, points[0].payload)
            finally:
                target_client.close()
            self.assertEqual(original, (source / 'meta.json').read_bytes())
            self.assertEqual(source_sqlite_sha, hashlib.sha256(source_sqlite.read_bytes()).hexdigest())

    def test_derived_metadata_rejects_unpinned_ambiguous_or_aliased_input(self):
        valid = b'{"collections":{"one":{"vectors":{}},"two":{}},"aliases":{}}'
        sha = hashlib.sha256(valid).hexdigest()
        with self.assertRaisesRegex(ValueError, 'SHA differs'):
            migration.derive_single_collection_meta(valid, '0' * 64, 'one')
        with self.assertRaisesRegex(ValueError, 'missing'):
            migration.derive_single_collection_meta(valid, sha, 'absent')
        for raw in (b'{"collections":{"one":{},"one":{}},"aliases":{}}',
                    b'{"collections":{"one":{"a":1,"a":2}},"aliases":{}}',
                    b'{"collections":{"one":{}},"aliases":{"alias":"one"}}',
                    b'{"collections":{"one":{}},"aliases":{},"unexpected":1}',
                    b'{"collections":{"one":{"value":NaN}},"aliases":{}}',
                    b'{"collections":{"../outside":{}},"aliases":{}}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                migration.derive_single_collection_meta(raw, hashlib.sha256(raw).hexdigest(), 'one')

    def test_derived_metadata_and_provenance_must_recompute_exactly(self):
        original = b'{"collections":{"one":{"vectors":{}},"two":{}},"aliases":{}}'
        sha = hashlib.sha256(original).hexdigest()
        derived, evidence = migration.derive_single_collection_meta(original, sha, 'one')
        changed_evidence = dict(evidence, excludedCollections=[])
        for raw, receipt in ((derived + b' ', evidence), (derived, changed_evidence)):
            with self.subTest(raw=raw, receipt=receipt), self.assertRaisesRegex(ValueError, 'provenance differs'):
                migration.verify_single_collection_meta(original, sha, 'one', raw, receipt)
        with self.assertRaisesRegex(ValueError, 'SHA differs'):
            migration.verify_single_collection_meta(original + b' ', sha, 'one', derived, evidence)

    def test_expired_mutation_stops_before_next_scroll_and_preserves_receipt(self):
        import hashlib
        for expires_during in ('create', 'upsert'):
            with self.subTest(expires_during=expires_during), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); registered = root / 'registered'; registered.mkdir()
                local = root / 'local'; local.mkdir(); collection = 'authored_collection'
                signature = {'model': 'authored'}
                seal = {'pointCount': 2, 'dimension': 2, 'persistedPointsSha256': 'a' * 64}
                seal['sealSha256'] = digest(seal)
                metadata = {'projectId': 'authored', 'collection': collection, 'signature': signature,
                            'chunks': [{'id': 'parent'}], 'vectorIntegrity': seal}
                metadata_file = registered / 'metadata.json'
                metadata_file.write_text(json.dumps(metadata), encoding='utf-8')
                info = metadata_file.stat()
                expected = {'path': str(metadata_file), 'bytes': info.st_size,
                            'sha256': hashlib.sha256(metadata_file.read_bytes()).hexdigest(),
                            'device': info.st_dev, 'inode': info.st_ino}
                inputs = {'number': 1, 'collection': collection, 'metadata': expected,
                          'sqlite': {'path': str(registered / 'storage.sqlite')},
                          'qdrantMeta': {'path': str(registered / 'qdrant-meta.json')},
                          'phaseB': {'sealSha256': seal['sealSha256'], 'pointCount': 2, 'dimension': 2,
                                     'matchedRecordedValues': ['persistedPointsSha256']}}
                state = {'expired': False, 'exists': False, 'upserts': 0, 'scrolls': 0}
                class Source:
                    def scroll(self, *args, **kwargs):
                        state['scrolls'] += 1
                        number = state['scrolls']
                        point = SimpleNamespace(id='00000000-0000-0000-0000-%012d' % number,
                                                vector=[1., 0.], payload={'fixture': number})
                        return [point], number if number == 1 else None
                    def close(self): pass
                class Target:
                    def collection_exists(self, name): return state['exists']
                    def create_collection(self, *args, **kwargs):
                        state['exists'] = True
                        if expires_during == 'create': state['expired'] = True
                    def upsert(self, *args, **kwargs):
                        state['upserts'] += 1
                        if expires_during == 'upsert': state['expired'] = True
                    def close(self): pass
                source, target = Source(), Target()
                original = {'pointCount': 2, 'dimension': 2, 'pointsSha256': 'a' * 64}
                def checkpoint():
                    if state['expired']: raise ValueError('Operation deadline expired')
                with patch.object(migration, '_deadline', return_value=checkpoint), \
                        patch.object(migration, 'verify_runtime_tree'), \
                        patch.object(migration, 'verify_reuse', return_value=original), \
                        patch.object(migration, 'ColdLocalReader', return_value=source):
                    journal = root / 'journal'
                    with self.assertRaisesRegex(ValueError, 'deadline expired'):
                        transfer_with_journal(inputs, local, lambda: target, journal, 1, 10, 10)
                    events = [json.loads(line) for line in (journal / 'migration-receipt.jsonl').read_text().splitlines()]
                    self.assertEqual('transfer_intent', events[-2]['status'])
                    self.assertEqual('failed', events[-1]['status'])
                    self.assertTrue(events[-1]['targetMayExist'])
                    self.assertTrue(state['exists'])
                    self.assertEqual(0 if expires_during == 'create' else 1, state['scrolls'])
                    self.assertEqual(0 if expires_during == 'create' else 1, state['upserts'])
                    with self.assertRaises((ValueError, FileExistsError)):
                        transfer_with_journal(inputs, local, lambda: target, journal, 1, 10, 10)
                    state['expired'] = False
                    with self.assertRaisesRegex(ValueError, 'already exists'):
                        transfer_with_journal(inputs, local, lambda: target, root / 'retry', 1, 10, 10)
                    self.assertEqual(0 if expires_during == 'create' else 1, state['upserts'])

    def test_prepared_transfer_records_failed_partial_and_rejects_same_target(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);fixture=root/'fixture';fixture.mkdir();collection='project_fixture'
            point_id='00000000-0000-0000-0000-000000000001';signature={'model':'authored'}
            window={'id':point_id,'parentId':'parent'};chunks=[{'id':'parent'}]
            created=QdrantClient(path=str(fixture))
            created.create_collection(collection,vectors_config=models.VectorParams(size=2,distance=models.Distance.COSINE))
            created.upsert(collection,points=[models.PointStruct(id=point_id,vector=[1.,0.],
                payload={**window,'indexSignature':digest(signature)})],wait=True)
            seal=inspect_points(created,collection,[window],signature,2);created.close()
            seal['persistedPointsSha256']=seal['pointsSha256'];seal['sealSha256']=digest(seal)
            metadata={'projectId':'authored','collection':collection,'signature':signature,'chunks':chunks,
                'parentCount':1,'windows':[window],'vectorPoints':1,'vectorIntegrity':seal}
            def describe(p):
                s=p.stat();return {'path':str(p),'bytes':s.st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),
                                   'device':s.st_dev,'inode':s.st_ino}
            metadata_file=fixture/'metadata.json';metadata_file.write_text(json.dumps(metadata),encoding='utf-8')
            inputs={'number':1,'collection':collection,'sqlite':describe(fixture/'collection'/collection/'storage.sqlite'),
                    'qdrantMeta':describe(fixture/'meta.json'),'metadata':describe(metadata_file),'phaseB':{'sealSha256':seal['sealSha256'],
                    'pointCount':1,'dimension':2,'matchedRecordedValues':['persistedPointsSha256']}}
            local=root/'runtime';prepare_runtime_tree(inputs,local,1000000,10)
            bad_inputs={**inputs,'metadata':{**inputs['metadata'],'sha256':'0'*64}};bad_journal=root/'bad-metadata-evidence'
            with self.assertRaisesRegex(ValueError,'bytes differ'):
                transfer_with_journal(bad_inputs,local,lambda: self.fail('target opened'),bad_journal,128,10,100)
            bad_receipt=[json.loads(row) for row in (bad_journal/'migration-receipt.jsonl').read_text().splitlines()]
            self.assertEqual('failed',bad_receipt[-1]['status']);self.assertFalse(bad_receipt[-1]['targetMayExist'])
            target_dir=root/'target';target=QdrantClient(path=str(target_dir));journal=root/'transfer-evidence'
            def fail_upsert(*args,**kwargs):
                saved=[json.loads(row) for row in (journal/'migration-receipt.jsonl').read_text().splitlines()]
                self.assertEqual('transfer_intent',saved[-1]['status'])
                raise RuntimeError('injected write failure')
            with patch.object(target,'upsert',side_effect=fail_upsert):
                with self.assertRaisesRegex(RuntimeError,'injected write failure'):
                    transfer_with_journal(inputs,local,lambda:target,journal,128,10,100)
            events=[json.loads(row) for row in (journal/'migration-receipt.jsonl').read_text().splitlines()]
            self.assertEqual('failed',events[-1]['status']);self.assertTrue(events[-1]['targetMayExist'])
            self.assertEqual(inputs['metadata']['sha256'],events[1]['metadataSha256'])
            with self.assertRaises((ValueError,FileExistsError)):
                transfer_with_journal(inputs,local,lambda:None,journal,128,10,100)
            reopened=QdrantClient(path=str(target_dir))
            try:self.assertTrue(reopened.collection_exists(collection))
            finally:reopened.close()
            second=root/'runtime2';prepare_runtime_tree(inputs,second,1000000,10);retry_events=[]
            with self.assertRaisesRegex(ValueError,'already exists'):
                transfer_prepared(inputs,second,metadata,lambda:QdrantClient(path=str(target_dir)),retry_events.append,128,10,100)
            self.assertEqual('failed',retry_events[-1]['status'])
            third=root/'runtime3';prepare_runtime_tree(inputs,third,1000000,10);success_journal=root/'success-journal'
            transfer_with_journal(inputs,third,lambda:QdrantClient(path=str(root/'success-target')),
                                  success_journal,128,10,100)
            success_events=[json.loads(row) for row in (success_journal/'migration-receipt.jsonl').read_text().splitlines()]
            self.assertEqual('completed_candidate_not_adopted',success_events[-1]['status'])
            self.assertEqual(success_events[-1]['source'],success_events[-1]['target'])
            fourth=root/'runtime4';prepare_runtime_tree(inputs,fourth,1000000,10)
            bad={**inputs,'phaseB':{**inputs['phaseB'],'sealSha256':'0'*64}};bad_events=[]
            def forbidden_target():raise AssertionError('target must not be connected')
            with self.assertRaisesRegex(ValueError,'Cold seal'):
                transfer_prepared(bad,fourth,metadata,forbidden_target,bad_events.append,128,10,100)
            self.assertFalse(bad_events[-1]['targetMayExist'])
            fifth=root/'runtime5';prepare_runtime_tree(inputs,fifth,1000000,10);intent_target=root/'intent-target'
            def failing_journal(value):
                if value['status']=='transfer_intent':raise OSError('journal unavailable')
            with self.assertRaisesRegex(OSError,'journal unavailable'):
                transfer_prepared(inputs,fifth,metadata,lambda:QdrantClient(path=str(intent_target)),failing_journal,128,10,100)
            unopened=QdrantClient(path=str(intent_target))
            try:self.assertFalse(unopened.collection_exists(collection))
            finally:unopened.close()

            sixth=root/'runtime6';prepare_runtime_tree(inputs,sixth,1000000,10)
            slow_journal=root/'slow-metadata-journal';original_json=migration._pinned_json;calls=[]
            def slow_json(path, sha, checkpoint=lambda: None):
                if Path(path)==metadata_file:time.sleep(0.06)
                return original_json(path,sha,checkpoint)
            with patch.object(migration,'_pinned_json',side_effect=slow_json):
                with self.assertRaisesRegex(ValueError,'deadline expired'):
                    transfer_with_journal(inputs,sixth,lambda: calls.append('target'),slow_journal,128,0.02,100)
            self.assertEqual([],calls)
            events=[json.loads(line) for line in (slow_journal/'migration-receipt.jsonl').read_text().splitlines()]
            self.assertEqual('failed',events[-1]['status']);self.assertFalse(events[-1]['targetMayExist'])
            with self.assertRaises((ValueError,FileExistsError)):
                transfer_with_journal(inputs,sixth,lambda: None,slow_journal,128,10,100)

            seventh=root/'runtime7';prepare_runtime_tree(inputs,seventh,1000000,10)
            target_path=root/'side-effect-target';target_journal=root/'side-effect-journal'
            def side_effect_factory():
                events=[json.loads(line) for line in (target_journal/'migration-receipt.jsonl').read_text().splitlines()]
                self.assertEqual('transfer_intent',events[-1]['status'])
                client=QdrantClient(path=str(target_path))
                try:client.create_collection(collection,vectors_config=models.VectorParams(size=2,distance=models.Distance.COSINE))
                finally:client.close()
                raise RuntimeError('injected factory failure')
            with self.assertRaisesRegex(RuntimeError,'injected factory failure'):
                transfer_with_journal(inputs,seventh,side_effect_factory,target_journal,128,10,100)
            events=[json.loads(line) for line in (target_journal/'migration-receipt.jsonl').read_text().splitlines()]
            self.assertEqual('failed',events[-1]['status']);self.assertTrue(events[-1]['targetMayExist'])
            client=QdrantClient(path=str(target_path))
            try:self.assertTrue(client.collection_exists(collection))
            finally:client.close()
            with self.assertRaises((ValueError,FileExistsError)):
                transfer_with_journal(inputs,seventh,lambda: None,target_journal,128,10,100)

    def test_prepare_owns_new_files_and_refuses_existing_tree_or_multicollection_meta(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);registered=root/'registered';registered.mkdir();collection='project_fixture'
            source=registered/'storage.sqlite';source.write_bytes(b'fixture sqlite')
            meta=registered/'qdrant-meta.json';meta.write_text(json.dumps({'collections':{collection:{}},'aliases':{}}))
            def describe(p):
                s=p.stat();return {'path':str(p),'bytes':s.st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),
                                   'device':s.st_dev,'inode':s.st_ino}
            inputs={'number':1,'collection':collection,'sqlite':describe(source),'qdrantMeta':describe(meta)}
            result=prepare_runtime_tree(inputs,root/'runtime',1024,10)
            self.assertEqual(inputs['sqlite']['sha256'],result['sqlite']['sha256'])
            self.assertNotEqual(inputs['sqlite']['inode'],result['sqlite']['inode'])
            with self.assertRaises((ValueError,FileExistsError)):prepare_runtime_tree(inputs,root/'runtime',1024,10)
            with patch('migrate_sealed_collections.os.link',side_effect=PermissionError('fixture failure')):
                with self.assertRaises(PermissionError):prepare_runtime_tree(inputs,root/'partial',1024,10)
            self.assertTrue((root/'partial'/'meta.json.partial').exists())
            with self.assertRaises((ValueError,FileExistsError)):prepare_runtime_tree(inputs,root/'partial',1024,10)
            meta.write_text(json.dumps({'collections':{collection:{},'another':{}},'aliases':{}}))
            inputs['qdrantMeta']=describe(meta)
            with self.assertRaisesRegex(ValueError,'Single-collection'):prepare_runtime_tree(inputs,root/'invalid',1024,10)
            self.assertFalse((root/'invalid').exists())

    def test_server_factory_does_not_connect_and_rejects_other_endpoints(self):
        with patch('qdrant_client.QdrantClient') as client:
            factory=server_target('http://127.0.0.1:28683',10)
            client.assert_not_called();factory();client.assert_called_once_with(url='http://127.0.0.1:28683',timeout=10)
        for url in ('http://localhost:28683','http://127.0.0.1:1234','http://user@127.0.0.1:28683'):
            with self.assertRaises(ValueError):server_target(url,10)

    def test_runtime_tree_requires_separate_single_collection_bytes(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);registered=root/'registered';registered.mkdir();runtime=root/'runtime';runtime.mkdir()
            collection='project_fixture';source=registered/'storage.sqlite';source.write_bytes(b'fixture sqlite')
            raw=json.dumps({'collections':{collection:{'vectors':{'size':2,'distance':'Cosine'}}},'aliases':{}}).encode()
            meta=registered/'qdrant-meta.json';meta.write_bytes(raw)
            describe=lambda p:{'path':str(p),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),
                               'device':p.stat().st_dev,'inode':p.stat().st_ino}
            inputs={'collection':collection,'sqlite':describe(source),'qdrantMeta':describe(meta)}
            (runtime/'meta.json').write_bytes(raw)
            dest=runtime/'collection'/collection/'storage.sqlite';dest.parent.mkdir(parents=True);dest.write_bytes(source.read_bytes())
            result=verify_runtime_tree(inputs,runtime)
            self.assertEqual(inputs['sqlite']['sha256'],result['sqlite']['sha256'])
            dest.unlink();os.link(source,dest)
            with self.assertRaisesRegex(ValueError,'(alias|identity)'):verify_runtime_tree(inputs,runtime)

    def test_one_registered_collection_is_bound_to_both_receipts_and_actual_files(self):
        import hashlib
        def fingerprint(path):
            raw=path.read_bytes();return {'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
        def save(path,value):
            path.write_text(json.dumps(value),encoding='utf-8');return fingerprint(path)['sha256']
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);module=root/'vetting';module.mkdir();collection='project_fixture'
            metadata=module/'project.json';seal={'pointCount':1,'dimension':2,'persistedPointsSha256':'f'*64}
            seal['sealSha256']=digest(seal)
            metadata.write_text(json.dumps({'collection':collection,'vectorIntegrity':seal}),encoding='utf-8')
            sqlite=module/collection/'storage.sqlite';sqlite.parent.mkdir();sqlite.write_bytes(b'authored sqlite')
            meta=module/'qdrant-meta.json';meta.write_text(json.dumps({'collections':{collection:{}},'aliases':{}}),encoding='utf-8')
            row={'kind':'collection','number':1,'collection':collection,'metadata':fingerprint(metadata),'sqlite':fingerprint(sqlite)}
            qmeta={'kind':'qdrant-meta','module':'vetting',**fingerprint(meta)}
            phase_a=root/'phase-a.json';a_sha=save(phase_a,{'state':'COPIED_UNVERIFIED','files':[row,qmeta]})
            phase_b=root/'phase-b.json';b_sha=save(phase_b,{'number':1,'status':'PASS','metadataSha256':row['metadata']['sha256'],
                'sqliteSha256':row['sqlite']['sha256'],'sealSha256':seal['sealSha256'],'pointCount':1,'dimension':2,
                'matchedRecordedValues':['persistedPointsSha256']})
            mappings=[{'kind':'index-generation','identity':{'collection':collection,'metadata':row['metadata'],'sqlite':row['sqlite']},
                       'futureWritableTarget':str(metadata)},
                      {'kind':'index-sqlite','targetLinux':str(root/'baseline'/collection/'storage.sqlite'),'futureWritableTarget':str(metadata),
                       'identity':row['sqlite']},
                      {'kind':'qdrant-meta','targetLinux':str(root/'baseline'/'qdrant-meta.json'),'identity':qmeta}]
            register=root/'register.json';reg_sha=save(register,{'mappings':mappings})
            amendment=root/'amendment.json';amend_sha=save(amendment,{'originalRegisterSha256':reg_sha,'corrections':[
                {'kind':'index-sqlite','mappingIndex':1,'baselineTarget':mappings[1]['targetLinux'],'priorTarget':str(metadata),
                 'correctedTarget':str(sqlite),'matchedMetadataTarget':str(metadata),'sha256':row['sqlite']['sha256']},
                {'kind':'qdrant-meta','mappingIndex':2,'baselineTarget':mappings[2]['targetLinux'],'priorTarget':None,
                 'correctedTarget':str(meta),'sha256':qmeta['sha256']} ]})
            args=(register,reg_sha,amendment,amend_sha,phase_a,a_sha,phase_b,b_sha,1)
            result=load_single_inputs(*args)
            self.assertEqual(collection,result['collection'])
            self.assertEqual(str(metadata),result['metadata']['path'])
            self.assertEqual(str(sqlite),result['sqlite']['path'])
            local=root/'runtime';local.mkdir();(local/'meta.json').write_bytes(meta.read_bytes())
            local_sqlite=local/'collection'/collection/'storage.sqlite';local_sqlite.parent.mkdir(parents=True)
            local_sqlite.write_bytes(sqlite.read_bytes());out=root/'validation'
            command=[sys.executable,str(Path(__file__).with_name('migrate_sealed_collections.py')),'--number','1',
                '--register',str(register),'--register-sha256',reg_sha,'--amendment',str(amendment),'--amendment-sha256',amend_sha,
                '--phase-a',str(phase_a),'--phase-a-sha256',a_sha,'--phase-b',str(phase_b),'--phase-b-sha256',b_sha,
                '--local-state',str(local),'--out',str(out)]
            child=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(0,child.returncode,child.stderr)
            receipt=[json.loads(line) for line in (out/'migration-receipt.jsonl').read_text().splitlines()][-1]
            self.assertEqual('validated_not_migrated',receipt['status']);self.assertFalse(receipt['serverConnected'])
            newlocal=root/'prepared';newout=root/'prepare-receipt'
            command[command.index('--local-state')+1]=str(newlocal);command[command.index('--out')+1]=str(newout)
            command+=['--prepare','--max-copy-bytes','1000000','--max-seconds','10']
            child=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(0,child.returncode,child.stderr)
            receipt=[json.loads(line) for line in (newout/'migration-receipt.jsonl').read_text().splitlines()][-1]
            self.assertEqual('prepared_not_migrated',receipt['status']);self.assertFalse(receipt['serverConnected'])
            slow_out=root/'slow-prepare-receipt';slow_local=root/'slow-prepare-runtime'
            command[command.index('--out')+1]=str(slow_out);command[command.index('--local-state')+1]=str(slow_local)
            command[command.index('--max-seconds')+1]='0.02'
            original_json=migration._pinned_json
            def slow_json(path,sha,checkpoint=lambda: None):
                if Path(path)==metadata:time.sleep(0.06)
                return original_json(path,sha,checkpoint)
            with patch.object(sys,'argv',[command[1],*command[2:]]),patch.object(migration,'_pinned_json',side_effect=slow_json):
                self.assertEqual(1,main())
            failure=[json.loads(line) for line in (slow_out/'migration-receipt.jsonl').read_text().splitlines()][-1]
            self.assertEqual('failed',failure['status']);self.assertIn('deadline expired',failure['error']['message'])
            self.assertFalse(slow_local.exists())
            command[command.index('--max-seconds')+1]='10'
            command[command.index('--out')+1]=str(module/'forbidden-output')
            child=subprocess.run(command,capture_output=True,text=True)
            self.assertNotEqual(0,child.returncode);self.assertFalse((module/'forbidden-output').exists())
            failed_out=root/'failed-receipt';failed_local=root/'failed-runtime'
            command[command.index('--out')+1]=str(failed_out);command[command.index('--local-state')+1]=str(failed_local)
            with patch.object(sys,'argv',[command[1],*command[2:]]),patch('migrate_sealed_collections.os.link',side_effect=PermissionError('fixture failure')):
                self.assertEqual(1,main())
            failure=[json.loads(line) for line in (failed_out/'migration-receipt.jsonl').read_text().splitlines()][-1]
            self.assertEqual('failed',failure['status']);self.assertTrue((failed_local/'meta.json.partial').exists())
            sqlite.write_bytes(b'tampered sqlite')
            with self.assertRaisesRegex(ValueError,'(bytes|identity) differs?'):load_single_inputs(*args)

    def test_command_retains_failure_receipt_without_connecting_for_bad_source_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'input'
            root.mkdir()
            (root / 'register.json').write_text('{}')
            out = Path(directory) / 'output'
            command = [sys.executable, str(Path(__file__).with_name('migrate_sealed_collections.py')),
                '--number','1','--register',str(root/'register.json'),'--register-sha256','0'*64,
                '--amendment',str(root/'amendment.json'),'--amendment-sha256','0'*64,
                '--phase-a',str(root/'phase-a.json'),'--phase-a-sha256','0'*64,
                '--phase-b',str(root/'phase-b.json'),'--phase-b-sha256','0'*64,
                '--local-state',str(root/'local'),'--out',str(out)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            receipt = [json.loads(row) for row in (out / 'migration-receipt.jsonl').read_text().splitlines()][-1]
            self.assertEqual('failed', receipt['status'])
            self.assertIn('Pinned input SHA differs', receipt['error']['message'])
            self.assertFalse(receipt['serverConnected'])
            previous=(out/'migration-receipt.jsonl').read_bytes()
            self.assertNotEqual(0,subprocess.run(command,capture_output=True).returncode)
            self.assertEqual(previous,(out/'migration-receipt.jsonl').read_bytes())
            blocked=Path(directory)/'blocked';command[command.index('--out')+1]=str(blocked);command.append('--transfer')
            self.assertNotEqual(0,subprocess.run(command,capture_output=True).returncode)
            blocked_receipt=[json.loads(row) for row in (blocked/'migration-receipt.jsonl').read_text().splitlines()][-1]
            self.assertEqual('failed',blocked_receipt['status'])
            self.assertIn('transfer is blocked',blocked_receipt['error']['message'])
            self.assertFalse(blocked_receipt['serverConnected'])

    def test_unpinned_evidence_is_rejected_before_any_transfer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'phase-a-receipt.private.json').write_text(json.dumps({'state': 'COPIED_UNVERIFIED', 'files': []}))
            with self.assertRaisesRegex(ValueError, 'Phase A receipt SHA'):
                load_inputs(root, '0' * 64, ['0' * 64] * 5)
            self.assertEqual(['phase-a-receipt.private.json'], [p.name for p in root.iterdir()])

    def test_transfer_preserves_payload_and_original_seal_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            source = QdrantClient(path=str(Path(directory) / 'source'))
            target = QdrantClient(path=str(Path(directory) / 'target'))
            try:
                collection = 'project_authored'
                point_id = '00000000-0000-0000-0000-000000000001'
                signature = {'model': 'authored'}
                window = {'id': point_id, 'parentId': 'parent', 'sourceHash': 'authored'}
                chunks = [{'id': 'parent'}]
                source.create_collection(collection, vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
                source.upsert(collection, points=[models.PointStruct(id=point_id, vector=[1., 0.],
                    payload={**window, 'indexSignature': digest(signature)})], wait=True)
                seal = inspect_points(source, collection, [window], signature, 2)
                seal['persistedPointsSha256'] = seal['pointsSha256']
                seal['sealSha256'] = digest(seal)
                metadata = {'projectId': 'authored', 'collection': collection, 'signature': signature,
                    'chunks': chunks, 'parentCount': 1, 'windows': [window], 'vectorPoints': 1,
                    'vectorIntegrity': seal}
                result = migrate_collection(source, target, metadata, 128)
                self.assertEqual(result['source'], result['target'])
                self.assertEqual(seal['pointsSha256'], result['target']['pointsSha256'])
                with self.assertRaisesRegex(ValueError, 'already exists'):
                    migrate_collection(source, target, metadata, 128)
                self.assertEqual(1, target.count(collection, exact=True).count)
            finally:
                source.close()
                target.close()

    def test_failed_upsert_preserves_partial_and_rejects_retry(self):
        source=QdrantClient(':memory:');target=QdrantClient(':memory:')
        try:
            collection='partial_fixture';point_id='00000000-0000-0000-0000-000000000001'
            signature={'model':'authored'};window={'id':point_id,'parentId':'parent'};chunks=[{'id':'parent'}]
            source.create_collection(collection,vectors_config=models.VectorParams(size=2,distance=models.Distance.COSINE))
            source.upsert(collection,points=[models.PointStruct(id=point_id,vector=[1.,0.],
                payload={**window,'indexSignature':digest(signature)})],wait=True)
            seal=inspect_points(source,collection,[window],signature,2)
            seal['persistedPointsSha256']=seal['pointsSha256'];seal['sealSha256']=digest(seal)
            metadata={'projectId':'authored','collection':collection,'signature':signature,'chunks':chunks,
                'parentCount':1,'windows':[window],'vectorPoints':1,'vectorIntegrity':seal}
            with patch.object(target,'upsert',side_effect=RuntimeError('injected write failure')):
                with self.assertRaisesRegex(RuntimeError,'injected write failure'):
                    migrate_collection(source,target,metadata,128)
            self.assertTrue(target.collection_exists(collection))
            self.assertEqual(0,target.count(collection,exact=True).count)
            with self.assertRaisesRegex(ValueError,'already exists'):
                migrate_collection(source,target,metadata,128)
        finally:
            source.close();target.close()


if __name__ == '__main__':
    unittest.main()
