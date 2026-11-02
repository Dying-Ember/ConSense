"""Write a verified subset to a new caller-owned directory; no vector DB or model calls."""
import hashlib
import json
from pathlib import Path

from token_windows import digest, fail


def write_json(path,value):
    sha=hashlib.sha256();size=0
    encoder=json.JSONEncoder(ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
    with path.open('xb') as stream:
        for text in encoder.iterencode(value):
            raw=text.encode('utf-8');stream.write(raw);sha.update(raw);size+=len(raw)
        stream.write(b'\n');sha.update(b'\n');size+=1
    return {'path':str(path.resolve()),'bytes':size,'sha256':sha.hexdigest()}


def write_subset(directory,metadata,manifest,copy_receipt):
    """Create descriptors and source-bound files after target copy/readback succeeds."""
    directory=Path(directory)
    fail(directory.is_absolute() and not directory.exists(), 'SL_SUBSET_OUTPUT','Fresh absolute output directory required')
    seal=metadata.get('vectorIntegrity',{})
    fail(manifest.get('schema')=='consense.sl-only-derived-index.v1'
         and metadata.get('projectId')==manifest.get('projectId')
         and metadata.get('collection')==manifest.get('collection')
         and digest(metadata.get('signature'))==manifest.get('indexSignature')
         and metadata.get('parentCount')==manifest.get('parentCount')==len(metadata.get('chunks',[]))
         and metadata.get('vectorPoints')==manifest.get('vectorPoints')==len(metadata.get('windows',[]))
         and metadata['signature'].get('corpus')==digest(metadata['chunks'])
         and metadata.get('windowManifestSha256')==digest(metadata['windows'])
         and seal==manifest.get('vectorIntegrity')
         and seal.get('sealSha256')==digest({k:v for k,v in seal.items() if k!='sealSha256'})
         and copy_receipt.get('pointCount')==metadata['vectorPoints']
         and copy_receipt.get('targetIndexSignature')==manifest['indexSignature']
         and copy_receipt.get('modelCalls')==0,
         'SL_SUBSET_OUTPUT','Completed target readback and matching subset required')
    allowed={row['sha256'] for row in manifest['editions']}
    parent_ids={row['id'] for row in metadata['chunks']}
    fail(allowed and all(row.get('originalSourceSha256') in allowed
         and row.get('sourceQuality',{}).get('family')=='SL' for row in metadata['chunks'])
         and len(parent_ids)==len(metadata['chunks'])
         and all(row.get('parentId') in parent_ids for row in metadata['windows']),
         'SL_SUBSET_OUTPUT','Only declared SL source parents can be delivered')
    directory.mkdir(parents=True)
    corpus_path=directory/'corpus.jsonl';corpus_sha=hashlib.sha256();corpus_bytes=0
    encoder=json.JSONEncoder(ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
    with corpus_path.open('xb') as stream:
        for row in metadata['chunks']:
            for text in encoder.iterencode(row):
                raw=text.encode('utf-8');stream.write(raw);corpus_sha.update(raw);corpus_bytes+=len(raw)
            stream.write(b'\n');corpus_sha.update(b'\n');corpus_bytes+=1
    corpus={'path':str(corpus_path.resolve()),'bytes':corpus_bytes,'sha256':corpus_sha.hexdigest()}
    plan=write_json(directory/'runtime-plan.json',{'projectId':metadata['projectId'],
        'indexSignatureSha256':manifest['indexSignature'],'signature':metadata['signature']})
    meta=write_json(directory/'metadata.json',metadata)
    receipt=write_json(directory/'copy-receipt.json',copy_receipt)
    frozen={'schema':'consense.frozen-fixed-development-baseline.v1','version':'sl-only-derived-v1',
        'developmentBaseline':True,'finalParsingAccepted':False,'businessAccepted':False,'H800Accepted':False,
        'scope':['all-applicable-SL-technical'],'originalSources':{'slSources':manifest['editions']},
        'projectId':metadata['projectId'],'collection':metadata['collection'],'indexSignature':manifest['indexSignature'],
        'corpus':corpus,'runtimePlan':plan,'persistedMetadata':meta,'vectorIntegrity':seal,
        'persistedVectorCount':metadata['vectorPoints'],'coverage':{'totalParents':metadata['parentCount'],
            'embeddingWindows':metadata['vectorPoints'],'editionParentCounts':manifest['editionParentCounts']},
        'sourceLineage':manifest['sourceLineage'],'modelPublications':manifest['modelPublications'],'copyReceipt':receipt}
    frozen_file=write_json(directory/'frozen-manifest.json',frozen)
    return {'manifest':frozen_file,'corpus':corpus,'metadata':meta,'runtimePlan':plan,'copyReceipt':receipt}
