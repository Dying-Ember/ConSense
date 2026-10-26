"""Bound normal-generation publication; no review, inference or original reparse."""
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict
from typing import Literal, Optional

from normal_observed_projection import SCHEMA, VERSION, OBSERVER, GRAPH_VERSION, SUPPORTED_GRAPH_VERSIONS, MAX_NORMAL_MESSAGE_BYTES, produce_normal_observed, _canonical_chunks
from oer_server import assert_canonical_scope
from token_windows import WindowError, fail


ENDPOINT = '/normal-observed'
REQUEST_BOUND_FORMAT = 'request-bound-normal-projection-v1'
CAPABILITY = {'schema': SCHEMA, 'version': VERSION, 'producerKind': 'normal_observed',
    'projectionEndpoint': ENDPOINT, 'candidateGraphSchema': 'consense.canonical-candidate.v1',
    'candidateGraphVersion': GRAPH_VERSION,
    'supportedCandidateGraphVersions': list(SUPPORTED_GRAPH_VERSIONS),
    'publicationResponseFormats': [REQUEST_BOUND_FORMAT],
    'completeUnitPolicy': 'complete-bound-generation-all-units-in-source-order',
    'nonTextUnitPolicy': 'retained-in-complete-graph-not-submitted-to-model',
    'artifactReadValidation': 'complete-manifest-actual-cas-reconstruction-every-request',
    'originalByteVerifier': OBSERVER['originalByteVerifier'], 'observerActor': OBSERVER['actor'],
    'sourceQualityAccepted': False, 'semanticScopeVerified': False, 'humanConfirmed': False,
    'adoptionStatus': 'candidate_unverified', 'maximumMessageBytes': MAX_NORMAL_MESSAGE_BYTES}


class NormalObservedRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    candidateGraph: dict
    sourceBinding: dict
    responseFormat: Optional[Literal['request-bound-normal-projection-v1']] = None


def install_normal_observed_route(app, registry, artifact_store, policy, *, target_module,
        software_guard, maximum_message_bytes=MAX_NORMAL_MESSAGE_BYTES):
    fail(app.state.source_registry is registry and app.state.artifact_store is artifact_store,
         'NORMAL_OBSERVED_FACTORY_MISMATCH', 'Normal observations must use the normal shared registry and CAS')

    def project(request):
        try:
            binding = request.sourceBinding
            fail(binding.get('module') == target_module
                 and binding.get('scopePolicySha256') == policy.policyIdentity['manifestSha256'],
                 'NORMAL_OBSERVED_POLICY_MISMATCH', 'The source binding must name the active module and scope policy')
            with registry.validation_frame(software_guard=software_guard):
                projection = produce_normal_observed(request.candidateGraph, binding, artifact_store=artifact_store)
                assert_canonical_scope(projection['chunks'], policy, module=target_module)
                preview = {'projectionSha256': projection['projectionSha256'], 'chunks': len(projection['chunks']),
                    'assets': 0, 'immutable': True, 'qualityVerifiedByRegistry': False}
                result = {**preview, 'projection': projection, 'sourceQualityChangedByRegistry': False}
                fail(sum(len(part) for part in _canonical_chunks(result)) <= maximum_message_bytes, 'NORMAL_OBSERVED_MESSAGE_TOO_LARGE',
                     'Complete observation projection and receipt exceed the fixed whole-message cap; no unit may be truncated')
            receipt = registry.publish(projection)
            fail(receipt == preview, 'NORMAL_OBSERVED_RECEIPT_MISMATCH', 'Published observation receipt differs')
            with registry.validation_frame(software_guard=software_guard):
                registry.verify_normal_publication(projection)
            if request.responseFormat == REQUEST_BOUND_FORMAT:
                # The caller already owns these exact observations. The complete v1 root and CAS remain authoritative.
                result = {**result, 'responseFormat': REQUEST_BOUND_FORMAT, 'projection': {**projection,
                    'manifest': {'$requestManifest': projection['projectionIdentity']['observationManifestSha256']},
                    'canonical': {'$requestCanonical': projection['sourceHash']}}}
            return result
        except (WindowError, ValueError, OSError, KeyError, TypeError) as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', getattr(error, 'kind', 'NORMAL_OBSERVED_REJECTED')),
                'message': str(error)}) from error

    project.__annotations__['request'] = NormalObservedRequest
    app.post(ENDPOINT)(app.state.request_evidence.wrap(ENDPOINT, project))
