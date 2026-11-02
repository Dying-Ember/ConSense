"""Copy a prepared SL subset between provided Qdrant clients; no model execution.

This module has no CLI and is not run during preparation. The caller owns a
fresh target collection, verified source identities, permissions and receipts.
"""
from token_windows import digest, fail
from index_integrity import PROTOCOL, vector_sha256
from migrate_qdrant_server import read_points, compare_vector
from sl_only_subset import remap_points, finalize


def copy_and_verify(source, target, source_metadata, output, manifest, *, batch_size=128, expected_source_integrity=None, expected_source_ledger=None):
    from qdrant_client import models
    fail(type(batch_size) is int and 1<=batch_size<=256, 'SL_SUBSET_COPY', 'Valid transfer batch size required')
    source_collection=source_metadata['collection']; target_collection=output['collection']
    fail(source_collection!=target_collection and not target.collection_exists(target_collection),
         'SL_SUBSET_COPY', 'A fresh target collection is required')
    dimension=manifest['dimension']
    target.create_collection(target_collection, vectors_config=models.VectorParams(size=dimension,distance=models.Distance.COSINE))
    vectors=target.get_collection(target_collection).config.params.vectors
    fail(not isinstance(vectors,dict) and getattr(vectors,'size',None)==dimension
         and str(getattr(vectors,'distance','')).lower()=='cosine'
         and getattr(vectors,'multivector_config',None) is None,
         'SL_SUBSET_COPY','Target vector schema differs')
    pages=read_points(source,source_collection,source_metadata['vectorPoints'],batch_size)
    source_actual=None
    def source_points():
        nonlocal source_actual
        if expected_source_integrity is None and expected_source_ledger is None:
            for page in pages:
                yield from page
            return
        fail(not (expected_source_integrity is not None and expected_source_ledger is not None),
             'SL_SUBSET_SOURCE_SEAL', 'One source proof mode required')
        selected={str(w['id']):{**w,'indexSignature':digest(source_metadata['signature'])}
                  for w in output['windows']}
        fail(len(selected)==output['vectorPoints'], 'SL_SUBSET_SOURCE_SEAL', 'Unique selected windows required')
        if expected_source_ledger is not None:
            fail(set(expected_source_ledger)==set(selected), 'SL_SUBSET_SOURCE_SEAL', 'Pinned ledger must cover selected windows')
        seen={};selected_seen=set()
        for page in pages:
            for point in page:
                key=str(point.id)
                if expected_source_integrity is not None:
                    fail(key not in seen, 'SL_SUBSET_SOURCE_SEAL', 'Duplicate source vector ID')
                    seen[key]=vector_sha256(point.vector,dimension)
                if key in selected:
                    fail(key not in selected_seen and point.payload==selected[key],
                         'SL_SUBSET_SOURCE_SEAL', 'Selected source point identity or payload changed')
                    selected_seen.add(key)
                    if expected_source_ledger is not None:
                        row=expected_source_ledger[key]
                        fail(row.get('payloadSha256')==digest(point.payload)
                             and row.get('targetFloat32Sha256')==vector_sha256(point.vector,dimension),
                             'SL_SUBSET_SOURCE_SEAL', 'Selected vector differs from pinned migration ledger')
                yield point
        fail(selected_seen==set(selected), 'SL_SUBSET_SOURCE_SEAL', 'Selected source points missing')
        if expected_source_integrity is not None:
            source_actual={'schema':PROTOCOL,'dimension':dimension,'dtype':'float32-le','pointCount':len(seen),
                'embeddingSignatureSha256':digest(source_metadata['signature']),
                'pointsSha256':digest([{'id':key,'vectorSha256':seen[key]} for key in sorted(seen)])}
            fail(source_actual==expected_source_integrity, 'SL_SUBSET_SOURCE_SEAL', 'Source persisted vector seal changed')
        else:
            source_actual={'mode':'selected-points-pinned-migration-ledger','selectedPointCount':len(selected_seen)}
    pending=[]
    for row in remap_points(source_points(),source_metadata,output,dimension=dimension):
        pending.append(models.PointStruct(**row))
        if len(pending)==batch_size:
            target.upsert(target_collection,points=pending,wait=True);pending=[]
    if pending:target.upsert(target_collection,points=pending,wait=True)
    fail(target.count(target_collection,exact=True).count==output['vectorPoints'],
         'SL_SUBSET_COPY','Target vector count differs')
    windows={row['id']:row for row in output['windows']}
    source_sig=digest(source_metadata['signature']);target_sig=digest(output['signature'])
    seen={};max_difference=0.;max_cosine_delta=0.;exact=0
    for page in read_points(target,target_collection,output['vectorPoints'],batch_size):
        ids=[str(point.id) for point in page]
        sources=source.retrieve(source_collection,ids=ids,with_payload=True,with_vectors=True)
        by_id={str(point.id):point for point in sources}
        fail(len(by_id)==len(ids) and set(by_id)==set(ids), 'SL_SUBSET_COPY','Source readback IDs differ')
        for point in page:
            key=str(point.id); window=windows.get(key); original=by_id[key]
            fail(window is not None and key not in seen
                 and point.payload=={**window,'indexSignature':target_sig}
                 and original.payload=={**window,'indexSignature':source_sig},
                 'SL_SUBSET_COPY','Source or target payload identity differs')
            comparison=compare_vector(original.vector,point.vector,dimension)
            seen[key]=vector_sha256(point.vector,dimension)
            exact+=int(comparison['float32BytesExact'])
            max_difference=max(max_difference,comparison['maxAbsDifference'])
            max_cosine_delta=max(max_cosine_delta,comparison['cosineDelta'])
    fail(set(seen)==set(windows) and target.count(target_collection,exact=True).count==len(windows),
         'SL_SUBSET_COPY','Target readback does not contain the complete selected windows')
    actual={'schema':PROTOCOL,'dimension':dimension,'dtype':'float32-le','pointCount':len(seen),
            'embeddingSignatureSha256':target_sig,
            'pointsSha256':digest([{'id':key,'vectorSha256':seen[key]} for key in sorted(seen)])}
    metadata,derived_manifest=finalize(output,manifest,actual)
    return metadata,derived_manifest,{'pointCount':len(seen),'exactFloat32Vectors':exact,
        'maximumVectorDifference':max_difference,'maximumCosineDelta':max_cosine_delta,
        'sourceIndexSignature':source_sig,'targetIndexSignature':target_sig,'sourceVectorIntegrity':source_actual,'modelCalls':0}
