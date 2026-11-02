import copy
import json
from types import SimpleNamespace
import unittest

from sl_only_subset import prepare, remap_points, finalize
from token_windows import digest, WindowError


def fixture(version=5, legacy=False):
    sources=[{'editionId':'v1','sha256':'a'*64,'path':'v1.pdf'},
             {'editionId':'v2','sha256':'b'*64,'path':'v2.pdf'}]
    parents=[{'id':'a','fileKey':'architectural-COM11','originalSourceSha256':'a'*64,'sourceQuality':{'family':'SL'},'content':'SL-ONE'},
             {'id':'b','fileKey':'sl2022-batch01','originalSourceSha256':'b'*64,'sourceQuality':{'family':'SL'},'content':'SL-TWO'},
             {'id':'c','fileKey':'GCC','originalSourceSha256':'c'*64,'sourceQuality':{'family':'GCC'},'content':'GCC-PRIVATE'}]
    if legacy:sources=sources[:1];parents=[parents[0],parents[2]]
    windows=[{'id':'w'+p['id'],'parentId':p['id'],'content':p['content']} for p in parents]
    sig={'signatureVersion':version,'contract':'window-v1','corpus':digest(parents),'embedding':{'revision':'fixed'}}
    frozen={'indexSignature':digest(sig),'originalSources':{'gccSource':{'sha256':'c'*64,'path':'gcc.pdf'}}}
    if legacy:frozen['originalSources']['slParent']={k:v for k,v in sources[0].items() if k!='editionId'}
    else:frozen['originalSources']['slSources']=sources
    meta={'projectId':'mixed','collection':'mixed_collection','contract':'window-v1','signature':sig,
          'chunks':parents,'windows':windows,'parentCount':len(parents),'vectorPoints':len(windows),
          'windowManifestSha256':digest(windows),'vectorIntegrity':{'dimension':2}}
    points=[SimpleNamespace(id=w['id'],vector=[.6,.8],payload={**w,'indexSignature':digest(sig)}) for w in windows]
    return frozen,meta,points


class SlOnlySubsetTests(unittest.TestCase):
    def make(self,frozen,meta,editions,**extra):
        return prepare(frozen,meta,frozen_sha256='f'*64,source_collection='mixed_collection',
                       editions=editions,project_id='sl-only',collection='sl_subset',**extra)

    def test_subset_excludes_other_families_without_reencoding_vectors(self):
        frozen,meta,points=fixture();before=copy.deepcopy(meta)
        out,manifest=self.make(frozen,meta,['v1','v2'])
        self.assertEqual(before,meta)
        self.assertEqual(['a','b'],[row['id'] for row in out['chunks']])
        self.assertEqual(['wa','wb'],[row['id'] for row in out['windows']])
        self.assertEqual({'v1':1,'v2':1},manifest['editionParentCounts'])
        self.assertNotEqual(frozen['indexSignature'],manifest['indexSignature'])
        self.assertEqual(frozen['indexSignature'],manifest['sourceLineage']['indexSignature'])
        wire=json.dumps([out,manifest]);self.assertNotIn('GCC-PRIVATE',wire);self.assertNotIn('gcc.pdf',wire)
        selected=list(remap_points(points,meta,out,dimension=2))
        self.assertEqual(['wa','wb'],[p['id'] for p in selected])
        self.assertEqual([.6,.8],selected[0]['vector'])
        self.assertEqual(manifest['indexSignature'],selected[0]['payload']['indexSignature'])

    def test_selection_and_identity_reject_unknown_or_wrong_version(self):
        frozen,meta,points=fixture()
        for editions in ([],['v1','v1'],['v3']):
            with self.subTest(editions=editions),self.assertRaises(WindowError):self.make(frozen,meta,editions)
        wrong=copy.deepcopy(meta);wrong['chunks'][0]['sourceQuality']['family']='GCC'
        with self.assertRaises(WindowError):self.make(frozen,wrong,['v1'])
        wrong=copy.deepcopy(meta);wrong['signature']['embedding']['revision']='other'
        with self.assertRaises(WindowError):self.make(frozen,wrong,['v1'])

    def test_missing_window_or_bad_point_never_produces_complete_subset(self):
        frozen,meta,points=fixture();out,_=self.make(frozen,meta,['v1','v2'])
        with self.assertRaises(WindowError):list(remap_points(points[:1],meta,out,dimension=2))
        points[0].payload['content']='changed'
        with self.assertRaises(WindowError):list(remap_points(points,meta,out,dimension=2))
        broken=copy.deepcopy(meta);broken['windows']=broken['windows'][1:];broken['vectorPoints']-=1
        with self.assertRaises(WindowError):self.make(frozen,broken,['v1','v2'])

    def test_old_competition_edition_keeps_separate_signature_lineage(self):
        frozen,meta,points=fixture(version=3,legacy=True)
        with self.assertRaises(WindowError):self.make(frozen,meta,['v1'])
        out,manifest=self.make(frozen,meta,['v1'],legacy_edition_id='v1')
        self.assertEqual(3,out['signature']['signatureVersion'])
        self.assertEqual('a'*64,manifest['editions'][0]['sha256'])
        self.assertEqual(frozen['indexSignature'],manifest['sourceLineage']['indexSignature'])
        self.assertEqual(1,len(list(remap_points(points,meta,out,dimension=2))))

    def test_final_seal_requires_actual_target_identity(self):
        frozen,meta,points=fixture();out,manifest=self.make(frozen,meta,['v1'])
        actual={'schema':'consense.persisted-vector-index-integrity.v1','dimension':2,'dtype':'float32-le',
                'pointCount':1,'embeddingSignatureSha256':digest(out['signature']),'pointsSha256':'d'*64}
        result,final=finalize(out,manifest,actual)
        self.assertEqual(result['vectorIntegrity'],final['vectorIntegrity'])
        self.assertEqual('d'*64,result['vectorIntegrity']['persistedPointsSha256'])
        with self.assertRaises(WindowError):finalize(out,manifest,{**actual,'pointCount':2})
        with self.assertRaises(WindowError):finalize(out,manifest,{**actual,'embeddingSignatureSha256':'0'*64})
