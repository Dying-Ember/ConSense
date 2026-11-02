"""Read a pinned SL-only derived index with the existing fixed-index runtime."""
import copy

from index_integrity import PROTOCOL
from migrated_qdrant_index import MigratedIndex, ENV, artifact_path, _pinned_json, _metadata_digest
from token_windows import digest, fail
from verify_retrieval_offline import descriptor


class SlDerivedIndex(MigratedIndex):
    def __init__(self, manifest_path, manifest_sha256, qdrant_url):
        self.environ={ENV:manifest_path,ENV+'_SHA256':manifest_sha256,'CONSENSE_QDRANT_URL':qdrant_url}
        self.configuration=(manifest_path,manifest_sha256,qdrant_url)
        fail(bool(manifest_path) and bool(manifest_sha256) and bool(qdrant_url),
             'SL_DERIVED_CONFIG','Pinned SL-only manifest and Qdrant URL are required')
        self.receipt_file=descriptor(artifact_path(manifest_path))
        fail(self.receipt_file['sha256']==manifest_sha256, 'SL_DERIVED_MANIFEST','SL-only manifest SHA differs')
        self.frozen=_pinned_json(self.receipt_file)
        f=self.frozen
        fail(f.get('schema')=='consense.frozen-fixed-development-baseline.v1'
             and f.get('scope')==['all-applicable-SL-technical'] and f.get('developmentBaseline') is True
             and f.get('finalParsingAccepted') is False and f.get('businessAccepted') is False,
             'SL_DERIVED_MANIFEST','SL-only frozen manifest is required')
        self.metadata=_pinned_json(f['persistedMetadata']);m=self.metadata
        plan=_pinned_json(f['runtimePlan']);receipt=_pinned_json(f['copyReceipt'])
        signature=m['signature'];seal=m['vectorIntegrity']
        fail(m.get('projectId')==f.get('projectId')==plan.get('projectId')
             and m.get('collection')==f.get('collection')
             and digest(signature)==f.get('indexSignature')==plan.get('indexSignatureSha256')
             and signature==plan.get('signature') and m.get('vectorIntegrity')==f.get('vectorIntegrity')
             and seal.get('schema')==PROTOCOL and seal.get('sealSha256')==digest({k:v for k,v in seal.items() if k!='sealSha256'})
             and receipt.get('targetIndexSignature')==f.get('indexSignature')
             and receipt.get('pointCount')==m.get('vectorPoints')==f.get('persistedVectorCount')
             and receipt.get('sourceIndexSignature')==f.get('sourceLineage',{}).get('indexSignature')
             and receipt.get('modelCalls')==0, 'SL_DERIVED_IDENTITY','SL-only metadata, target or lineage differs')
        selected={row['sha256'] for row in f['originalSources']['slSources']}
        parents=m['chunks'];windows=m['windows'];ids={row['id'] for row in parents}
        fail(bool(selected) and len(ids)==len(parents)==m.get('parentCount')==f['coverage']['totalParents']
             and signature.get('corpus')==digest(parents) and m.get('windowManifestSha256')==digest(windows)
             and len({row['id'] for row in windows})==len(windows)==m['vectorPoints']
             and all(row.get('originalSourceSha256') in selected and row.get('sourceQuality',{}).get('family')=='SL' for row in parents)
             and all(row.get('parentId') in ids for row in windows),
             'SL_DERIVED_IDENTITY','SL-only parents, source editions or window identities differ')
        target_integrity={key:value for key,value in seal.items() if key not in ('persistedPointsSha256','sealSha256')}
        self.receipt={'frozenManifest':copy.deepcopy(self.receipt_file),'candidateMetadata':copy.deepcopy(f['persistedMetadata']),
                      'target':{'url':qdrant_url,'collection':m['collection']},'targetVectorIntegrity':target_integrity}
        self._receipt_descriptor=tuple(self.receipt_file[name] for name in ('path','bytes','sha256'))
        self._receipt_sha256=_metadata_digest(self.receipt);self._frozen_sha256=_metadata_digest(f)
        self._metadata_sha256=_metadata_digest(m);self._metadata_stamp=self._candidate_metadata_stamp()
        self._verified_vector_integrity=None;self._verification_generation=0;self._fixed_query_signature=None
        self.last_observation=None
