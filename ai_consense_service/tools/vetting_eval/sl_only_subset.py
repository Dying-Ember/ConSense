"""Pure SL-only projection of one already validated fixed index; no I/O or model calls.

The caller owns source receipt verification and later target writes/readback.  Keep
different frozen source index signatures in separate projections.
"""
import copy
import re
from collections import Counter

from token_windows import digest, fail
from index_integrity import vector_sha256, PROTOCOL


def source_catalog(frozen, *, legacy_edition_id=None):
    originals=frozen['originalSources']; rows=originals.get('slSources')
    if rows is None:
        fail(isinstance(legacy_edition_id,str) and bool(legacy_edition_id.strip()) and 'slParent' in originals,
             'SL_SUBSET_SOURCE', 'A source-backed legacy edition identifier is required')
        rows=[{**originals['slParent'],'editionId':legacy_edition_id}]
    fail(isinstance(rows,list) and bool(rows), 'SL_SUBSET_SOURCE', 'SL source catalogue required')
    result={}
    for row in rows:
        edition=row.get('editionId'); source=row.get('sha256')
        fail(isinstance(edition,str) and bool(edition.strip()) and edition not in result
             and isinstance(source,str) and re.fullmatch('[a-f0-9]{64}',source) is not None,
             'SL_SUBSET_SOURCE', 'Each SL edition needs one unique source identity')
        result[edition]=copy.deepcopy(row)
    fail(len({row['sha256'] for row in result.values()})==len(result),
         'SL_SUBSET_SOURCE', 'Distinct editions must not share a source SHA')
    return result


def prepare(frozen, metadata, *, frozen_sha256, source_collection, editions, project_id, collection,
            legacy_edition_id=None):
    """Select complete source-bound parents/windows and derive a distinct signature."""
    fail(isinstance(frozen_sha256,str) and re.fullmatch('[a-f0-9]{64}',frozen_sha256) is not None
         and isinstance(project_id,str) and bool(project_id) and isinstance(collection,str) and bool(collection)
         and collection!=source_collection, 'SL_SUBSET_CONFIG', 'Distinct output and frozen identity are required')
    signature=metadata['signature']; source_signature=digest(signature)
    fail(source_signature==frozen['indexSignature'] and metadata['collection']==source_collection
         and metadata.get('contract')==signature.get('contract')
         and metadata.get('parentCount')==len(metadata['chunks'])
         and metadata.get('vectorPoints')==len(metadata['windows'])
         and signature.get('corpus')==digest(metadata['chunks'])
         and metadata.get('windowManifestSha256')==digest(metadata['windows'])
         and type(metadata.get('vectorIntegrity',{}).get('dimension')) is int
         and metadata['vectorIntegrity']['dimension']>0,
         'SL_SUBSET_SOURCE', 'Source signature, collection or count differs')
    catalog=source_catalog(frozen, legacy_edition_id=legacy_edition_id)
    fail(isinstance(editions,list) and bool(editions) and len(set(editions))==len(editions)
         and all(name in catalog for name in editions), 'SL_SUBSET_EDITION', 'Explicit known SL editions required')
    expected={catalog[name]['sha256']:name for name in editions}
    parents=[]; parents_by_id={}; counts=Counter()
    for parent in metadata['chunks']:
        if parent.get('originalSourceSha256') not in expected: continue
        fail(parent.get('sourceQuality',{}).get('family')=='SL'
             and isinstance(parent.get('id'),str) and parent['id'] not in parents_by_id,
             'SL_SUBSET_PARENT', 'Selected parents must preserve SL family and unique source identity')
        row=copy.deepcopy(parent);parents.append(row);parents_by_id[row['id']]=row
        counts[expected[row['originalSourceSha256']]]+=1
    fail(set(counts)==set(editions) and all(counts[name]>0 for name in editions),
         'SL_SUBSET_EDITION', 'Every selected edition requires actual indexed parents')
    declared={row['editionId']:row['parentCount'] for row in frozen.get('coverage',{}).get('versions',[])}
    if declared:
        fail(all(name in declared and counts[name]==declared[name] for name in editions),
             'SL_SUBSET_EDITION', 'Selected edition parent count differs from frozen coverage')
    windows=[]; windows_by_id={}; window_parents=set()
    for window in metadata['windows']:
        if window.get('parentId') not in parents_by_id: continue
        fail(isinstance(window.get('id'),str) and window['id'] not in windows_by_id,
             'SL_SUBSET_WINDOW', 'Selected windows must have unique identities')
        row=copy.deepcopy(window);windows.append(row);windows_by_id[row['id']]=row;window_parents.add(row['parentId'])
    fail(window_parents==set(parents_by_id) and bool(windows), 'SL_SUBSET_WINDOW',
         'Each selected parent needs its original persisted windows')
    signed=copy.deepcopy(signature);signed['corpus']=digest(parents)
    target_signature=digest(signed)
    fail(target_signature!=source_signature, 'SL_SUBSET_SIGNATURE', 'Subset must not reuse the mixed corpus identity')
    output={'projectId':project_id,'collection':collection,'contract':metadata['contract'],
            'signature':signed,'chunks':parents,'windows':windows,'parentCount':len(parents),
            'vectorPoints':len(windows),'windowManifestSha256':digest(windows)}
    manifest={'schema':'consense.sl-only-derived-index.v1','scope':['all-applicable-SL-technical'],
              'sourceLineage':{'frozenManifestSha256':frozen_sha256,'indexSignature':source_signature,
                               'sourceCollection':source_collection},
              'projectId':project_id,'collection':collection,'indexSignature':target_signature,
              'editions':[catalog[name] for name in editions], 'editionParentCounts':dict(counts),
              'parentCount':len(parents),'vectorPoints':len(windows),
              'dimension':metadata['vectorIntegrity']['dimension'],
              'modelPublications':copy.deepcopy(frozen.get('modelPublications',{})), 'sourceQualityAccepted':False}
    return output,manifest


def remap_points(records, source_metadata, output, *, dimension):
    """Yield existing SL vectors with their original window and the new subset signature."""
    wanted={row['id']:row for row in output['windows']};seen=set()
    fail(dimension==source_metadata['vectorIntegrity']['dimension'], 'SL_SUBSET_POINT',
         'Source vector dimension differs from the frozen index')
    old_signature=digest(source_metadata['signature']);new_signature=digest(output['signature'])
    for point in records:
        key=str(point.id)
        if key not in wanted: continue
        window=wanted[key]
        fail(key not in seen and point.payload=={**window,'indexSignature':old_signature},
             'SL_SUBSET_POINT', 'Source point payload or identity differs')
        vector_sha256(point.vector,dimension)
        seen.add(key)
        yield {'id':key,'vector':point.vector,'payload':{**window,'indexSignature':new_signature}}
    fail(seen==set(wanted), 'SL_SUBSET_POINT', 'All selected source vectors are required')


def finalize(output, manifest, actual):
    """Bind the actual target readback seal; caller obtains it after writing, not here."""
    required={'schema','dimension','dtype','pointCount','embeddingSignatureSha256','pointsSha256'}
    fail(isinstance(actual,dict) and set(actual)==required and actual['schema']==PROTOCOL
         and actual['pointCount']==output['vectorPoints']
         and actual['embeddingSignatureSha256']==digest(output['signature'])
         and actual['dtype']=='float32-le' and actual['dimension']==manifest['dimension']
         and isinstance(actual['pointsSha256'],str) and re.fullmatch('[a-f0-9]{64}',actual['pointsSha256']) is not None,
         'SL_SUBSET_TARGET', 'Actual target vector identity and counts required')
    result=copy.deepcopy(output);seal={**actual,'persistedPointsSha256':actual['pointsSha256']}
    seal['sealSha256']=digest(seal);result['vectorIntegrity']=seal
    final=copy.deepcopy(manifest);final['vectorIntegrity']=copy.deepcopy(seal)
    return result,final
