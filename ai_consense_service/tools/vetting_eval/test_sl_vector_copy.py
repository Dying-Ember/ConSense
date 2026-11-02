import copy
from types import SimpleNamespace
import unittest

from sl_vector_copy import copy_and_verify
from test_sl_only_subset import fixture
from sl_only_subset import prepare
from token_windows import WindowError, digest
from index_integrity import PROTOCOL, vector_sha256


class FakeQdrant:
    def __init__(self,collections=None,*,tamper=False):
        self.collections=copy.deepcopy(collections or {});self.tamper=tamper;self.created=[]
    def collection_exists(self,name):return name in self.collections
    def create_collection(self,name,vectors_config):
        self.created.append(name);self.collections[name]={};self.config=vectors_config
    def get_collection(self,name):
        return SimpleNamespace(config=SimpleNamespace(params=SimpleNamespace(vectors=self.config)))
    def upsert(self,name,points,wait):
        for point in points:
            point=copy.deepcopy(point)
            if self.tamper:point.vector=[0.,1.]
            self.collections[name][str(point.id)]=point
    def count(self,name,exact):return SimpleNamespace(count=len(self.collections[name]))
    def scroll(self,name,limit,offset,with_payload,with_vectors):
        rows=list(self.collections[name].values());begin=offset or 0;end=min(begin+limit,len(rows))
        return rows[begin:end],(end if end<len(rows) else None)
    def retrieve(self,name,ids,with_payload,with_vectors):return [self.collections[name][str(id_)] for id_ in ids]


class SlVectorCopyTests(unittest.TestCase):
    def setUp(self):
        self.frozen,self.source_metadata,points=fixture()
        self.source=FakeQdrant({'mixed_collection':{p.id:p for p in points}})
        self.before=copy.deepcopy(self.source.collections)
        self.output,self.manifest=prepare(self.frozen,self.source_metadata,frozen_sha256='f'*64,
            source_collection='mixed_collection',editions=['v1','v2'],project_id='sl-only',collection='sl_subset')

    def test_copy_retains_source_and_writes_only_selected_existing_vectors(self):
        target=FakeQdrant()
        meta,manifest,receipt=copy_and_verify(self.source,target,self.source_metadata,self.output,self.manifest,batch_size=2)
        self.assertEqual(self.before,self.source.collections)
        self.assertEqual({'wa','wb'},set(target.collections['sl_subset']))
        self.assertEqual((2,2,0),(receipt['pointCount'],receipt['exactFloat32Vectors'],receipt['modelCalls']))
        self.assertEqual(meta['vectorIntegrity'],manifest['vectorIntegrity'])
        self.assertNotEqual(receipt['sourceIndexSignature'],receipt['targetIndexSignature'])
        self.assertEqual([.6,.8],target.collections['sl_subset']['wa'].vector)

    def test_source_seal_is_checked_during_copy(self):
        points=self.source.collections['mixed_collection']
        seal={'schema':PROTOCOL,'dimension':2,'dtype':'float32-le','pointCount':len(points),
              'embeddingSignatureSha256':digest(self.source_metadata['signature']),
              'pointsSha256':digest([{'id':key,'vectorSha256':vector_sha256(points[key].vector,2)} for key in sorted(points)])}
        target=FakeQdrant()
        _,_,receipt=copy_and_verify(self.source,target,self.source_metadata,self.output,self.manifest,expected_source_integrity=seal,batch_size=2)
        self.assertEqual(receipt['sourceVectorIntegrity'],seal)
        wrong=dict(seal,pointsSha256='0'*64)
        target=FakeQdrant()
        with self.assertRaises(WindowError):
            copy_and_verify(self.source,target,self.source_metadata,self.output,self.manifest,expected_source_integrity=wrong,batch_size=2)
        self.assertTrue(target.collection_exists('sl_subset'))
        self.assertEqual(self.before,self.source.collections)
        self.source.collections['mixed_collection']['wc'].payload['content']='changed GCC source'
        copy_and_verify(self.source,FakeQdrant(),self.source_metadata,self.output,self.manifest,expected_source_integrity=seal,batch_size=2)

    def test_selected_ledger_ignores_unselected_payload_but_pins_sl_vectors(self):
        points=self.source.collections['mixed_collection']
        ledger={k:{'payloadSha256':digest(points[k].payload),'targetFloat32Sha256':vector_sha256(points[k].vector,2)} for k in ('wa','wb')}
        points['wc'].payload['content']='changed excluded GCC'
        _,_,receipt=copy_and_verify(self.source,FakeQdrant(),self.source_metadata,self.output,self.manifest,expected_source_ledger=ledger,batch_size=2)
        self.assertEqual(receipt['sourceVectorIntegrity']['selectedPointCount'],2)
        points['wb'].vector=[0.,1.]
        with self.assertRaises(WindowError):
            copy_and_verify(self.source,FakeQdrant(),self.source_metadata,self.output,self.manifest,expected_source_ledger=ledger,batch_size=2)

    def test_existing_target_and_changed_vector_rejected(self):
        with self.assertRaises(WindowError):
            copy_and_verify(self.source,FakeQdrant({'sl_subset':{}}),self.source_metadata,self.output,self.manifest)
        target=FakeQdrant(tamper=True)
        with self.assertRaises(ValueError):copy_and_verify(self.source,target,self.source_metadata,self.output,self.manifest)
        self.assertTrue(target.collection_exists('sl_subset'))
        self.assertEqual(self.before,self.source.collections)
