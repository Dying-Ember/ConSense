"""Authored HTTP fixtures: real source path, fake Qdrant and model gateway."""
import json, os, threading, unittest
from types import SimpleNamespace
from unittest.mock import patch
from test_oer_session_integration import OerSessionIntegrationTest

class DirectServerTest(OerSessionIntegrationTest):
    def setUp(self):
        super().setUp()
        self.env = patch.dict(os.environ, {'CONSENSE_QDRANT_URL':'http://authored-server'}, clear=False)
        self.env.start(); self.addCleanup(self.env.stop)
        identity=(str(self.backend.STATE),'http://authored-server',os.environ.get('CONSENSE_QDRANT_API_KEY'))
        self.backend._database=self.db
        self.backend._database_configuration=identity
        self.backend._database_binding=(self.db,identity)
        self.db.get_collections=lambda:SimpleNamespace(collections=[SimpleNamespace(name=n) for n in self.db.collections])
        self.db.get_aliases=lambda:SimpleNamespace(aliases=[])
        create=self.db.create_collection
        def created(name, **kwargs): create(name, **kwargs); return True
        self.db.create_collection=created
        self.body={**self.body,'initializationDecision':True}

    def test_unrelated_collections_preserved_and_new_project_created(self):
        self.db.collections['other-project']={}
        status=self.client.post('/index-canonical/status',json=self.body)
        self.assertEqual(200,status.status_code,status.text)
        self.assertTrue(status.json()['initializationAllowed'])
        response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual({},self.db.collections['other-project'])
        meta=json.loads(self.backend.metadata_path(self.body['projectId']).read_text(encoding='utf8'))
        self.assertIn(meta['collection'],self.db.collections)
        self.assertGreater(len(self.db.collections[meta['collection']]),0)

    def test_other_module_of_same_business_project_is_preserved(self):
        vetting='project-7::canonical::vetting::'+'a'*64
        advisory='project-7::canonical::advisory::'+'a'*64
        name='project_'+self.backend.digest(advisory)[:16]+'_prior'
        self.db.collections[name]={}
        self.body={**self.body,'projectId':vetting}
        response=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual({},self.db.collections[name])
        self.assertEqual(2,len(self.db.collections))

    def test_prior_project_collection_rejected_without_writes(self):
        name='project_'+self.backend.digest(self.body['projectId'])[:16]+'_old'
        self.db.collections[name]={}
        response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertEqual({name},set(self.db.collections)); self.assertEqual([],self.gateway.calls)
        self.assertFalse(self.backend.metadata_path(self.body['projectId']).exists())

    def test_create_race_fails_before_upsert(self):
        calls=[]
        self.db.create_collection=lambda *args,**kwargs:False
        self.db.upsert=lambda *args,**kwargs:calls.append('upsert')
        response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertEqual([],calls)
        self.assertTrue(self.backend.request_evidence().block_path(self.body['projectId']).exists())
        self.assertFalse(self.backend.metadata_path(self.body['projectId']).exists())

    def test_lost_upsert_response_keeps_partial_without_publishing(self):
        upsert=self.db.upsert
        def lost(*args, **kwargs):
            upsert(*args, **kwargs)
            raise OSError('authored lost response')
        self.db.upsert=lost
        response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertTrue(any(self.db.collections.values()))
        self.assertTrue(self.backend.request_evidence().block_path(self.body['projectId']).exists())
        self.assertFalse(self.backend.metadata_path(self.body['projectId']).exists())

    def test_failed_upsert_keeps_partial_and_journal_blocked(self):
        def fail(*args,**kwargs): raise OSError('authored upsert failure')
        self.db.upsert=fail
        response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertEqual(1,len(self.db.collections))
        self.assertTrue(self.backend.request_evidence().block_path(self.body['projectId']).exists())
        self.assertFalse(self.backend.metadata_path(self.body['projectId']).exists())

    def test_concurrent_same_project_cannot_create_two_collections(self):
        started=threading.Event(); second_entered=threading.Event(); allow=threading.Event(); results=[]
        arrivals=[]; arrival_lock=threading.Lock()
        @self.app.middleware('http')
        async def observe_request(request, call_next):
            with arrival_lock:
                arrivals.append(request.url.path)
                if len(arrivals)==2: second_entered.set()
            return await call_next(request)
        def hold():
            started.set()
            if not allow.wait(8): raise RuntimeError('fixture timeout')
        self.gateway.on_start=hold
        def run(): results.append(self.client.post('/index-canonical',json=self.body).status_code)
        a=threading.Thread(target=run); b=threading.Thread(target=run)
        a.start(); self.assertTrue(started.wait(5)); b.start()
        self.assertTrue(second_entered.wait(5)); allow.set(); a.join(15); b.join(15)
        self.assertFalse(a.is_alive() or b.is_alive()); self.assertEqual(2,len(results))
        self.assertLessEqual(results.count(200),1); self.assertEqual(1,len(self.db.collections))

    def test_existing_index_reuse_and_rebuild_preserves_old_collection(self):
        first=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,first.status_code,first.text)
        path=self.backend.metadata_path(self.body['projectId'])
        old_bytes=path.read_bytes(); old=json.loads(old_bytes)
        old_points=dict(self.db.collections[old['collection']])
        reuse=self.client.post('/index-canonical',json={**self.body,'initializationDecision':False})
        self.assertEqual(200,reuse.status_code,reuse.text)
        self.assertTrue(reuse.json()['cached'])
        self.assertEqual(old_bytes,path.read_bytes())
        rebuilt=self.client.post('/index-canonical',json={**self.body,'initializationDecision':False,'rebuild':True})
        self.assertEqual(200,rebuilt.status_code,rebuilt.text)
        current=json.loads(path.read_bytes())
        self.assertNotEqual(old['collection'],current['collection'])
        self.assertEqual(old_points,self.db.collections[old['collection']])
        history=path.parent/current['previousMetadataFile']
        self.assertEqual(old_bytes,history.read_bytes())

    def test_changed_signature_keeps_prior_metadata_without_explicit_rebuild(self):
        first=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,first.status_code,first.text)
        path=self.backend.metadata_path(self.body['projectId']); old_bytes=path.read_bytes()
        old=json.loads(old_bytes); old_points=dict(self.db.collections[old['collection']])
        signature=self.backend._windows_runtime.signature
        with patch.object(self.backend._windows_runtime,'signature',side_effect=lambda chunks:{**signature(chunks),'authoredChangedRecipe':True}):
            response=self.client.post('/index-canonical',json={**self.body,'initializationDecision':False})
        self.assertEqual(200,response.status_code,response.text)
        current=json.loads(path.read_bytes())
        self.assertNotEqual(old['collection'],current['collection'])
        self.assertEqual(old_bytes,(path.parent/current['previousMetadataFile']).read_bytes())
        self.assertEqual(old_points,self.db.collections[old['collection']])

    def test_failed_rebuild_keeps_original_metadata_and_collection(self):
        first=self.client.post('/index-canonical',json=self.body)
        self.assertEqual(200,first.status_code,first.text)
        path=self.backend.metadata_path(self.body['projectId']); old_bytes=path.read_bytes()
        old=json.loads(old_bytes); old_points=dict(self.db.collections[old['collection']])
        self.db.upsert=lambda *args,**kwargs: (_ for _ in ()).throw(OSError('authored failure'))
        failed=self.client.post('/index-canonical',json={**self.body,'initializationDecision':False,'rebuild':True})
        self.assertNotEqual(200,failed.status_code,failed.text)
        self.assertEqual(old_bytes,path.read_bytes())
        self.assertEqual(old_points,self.db.collections[old['collection']])
        self.assertTrue(self.backend.request_evidence().block_path(self.body['projectId']).exists())

    def test_real_qdrant_client_memory_keeps_unrelated_collection(self):
        from qdrant_client import QdrantClient, models
        db=QdrantClient(':memory:')
        db.create_collection('unrelated', vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE))
        identity=self.backend._database_configuration
        self.backend._database=db; self.backend._database_binding=(db,identity)
        self.backend.database=lambda:db
        try:
            response=self.client.post('/index-canonical',json=self.body)
            self.assertEqual(200,response.status_code,response.text)
            meta=json.loads(self.backend.metadata_path(self.body['projectId']).read_text(encoding='utf8'))
            self.assertTrue(db.collection_exists('unrelated'))
            self.assertGreater(db.count(meta['collection'],exact=True).count,0)
        finally: db.close()

    def test_nonwritable_route_initialization_rebuild_and_unowned_request_are_rejected(self):
        from contextlib import ExitStack
        from token_windows import WindowError
        for mode in ('route','rebuild','unowned'):
            with self.subTest(mode=mode), ExitStack() as stack:
                if mode=='route':
                    stack.enter_context(patch('migration_project_registry.require_writable',
                        side_effect=WindowError('MIGRATION_INDEX_WRITE_UNISOLATED','authored rejected route')))
                if mode=='unowned':
                    stack.enter_context(patch('source_request_context.active',return_value=False))
                body={**self.body,'rebuild':mode=='rebuild'}
                response=self.client.post('/index-canonical',json=body)
                self.assertNotEqual(200,response.status_code,response.text)
                self.assertEqual([],self.gateway.calls)
                self.assertEqual({},self.db.collections)
                self.assertFalse(self.backend.metadata_path(self.body['projectId']).exists())

    def test_replaced_qdrant_client_is_rejected_before_model(self):
        self.backend._database=object()
        response=self.client.post('/index-canonical',json=self.body)
        self.assertNotEqual(200,response.status_code)
        self.assertEqual([],self.gateway.calls)
        self.assertFalse(self.backend.metadata_path(self.body['projectId']).exists())

if __name__=='__main__': unittest.main()
