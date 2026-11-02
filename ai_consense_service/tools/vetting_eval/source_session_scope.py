"""Bind one model session to the complete, currently owned source frame."""
import hashlib
import re

from token_windows import WindowError, digest


def source_declaration_sha256(project, module, projections, pins, chunks, policy_sha256):
    if (not isinstance(project, str) or not project or module not in ('vetting','advisory')
            or not isinstance(projections, list) or not projections
            or any(not isinstance(value,str) or not re.fullmatch('[a-f0-9]{64}',value) for value in projections)
            or len(set(projections)) != len(projections)
            or not isinstance(pins,dict) or not set(pins).issubset(projections)
            or any(not isinstance(value,str) or not re.fullmatch('[a-f0-9]{64}',value) for value in pins.values())
            or not isinstance(chunks,list) or not isinstance(policy_sha256,str)
            or not re.fullmatch('[a-f0-9]{64}',policy_sha256)):
        raise WindowError('SOURCE_SCOPE_IDENTITY_INVALID', 'Exact project, projections, pins and corpus are required')
    return digest({'schema':'consense.model-source-declaration.v1','projectId':project,'module':module,
                   'projectionSha256':projections,'sourcePageEvidence':pins,
                   'corpusSha256':digest(chunks),'scopePolicySha256':policy_sha256})


def source_scope_sha256(registry, project, module, projections, pins, chunks, policy_sha256):
    declaration=source_declaration_sha256(project,module,projections,pins,chunks,policy_sha256)
    frame_var = getattr(registry, '_validation_frame', None)
    session_var = getattr(getattr(registry, 'artifact_store', None), '_read_session', None)
    frame = frame_var.get() if frame_var is not None else None
    cas = session_var.get() if session_var is not None else None
    if not isinstance(frame, dict) or not isinstance(cas, dict):
        raise WindowError('SOURCE_FRAME_PROOF_REQUIRED', 'A complete owned source and CAS frame is required')
    locations = frame.get('locations')
    classifications = frame.get('normalLocations')
    family = frame.get('familyContext')
    software = frame.get('softwareIdentity')
    if (not isinstance(locations,dict) or not locations or not isinstance(classifications,dict)
            or set(classifications)!=set(locations) or any(type(value) is not bool for value in classifications.values())
            or not (family is None or isinstance(family,bytes)) or not isinstance(software,dict) or not software):
        raise WindowError('SOURCE_FRAME_PROOF_REQUIRED', 'Source locator and imported software identity are required')
    try:
        if any(not isinstance(key,tuple) or len(key)!=3 or not isinstance(key[0],str)
               or not isinstance(key[1],str) or not re.fullmatch('[a-f0-9]{64}',key[1])
               or type(key[2]) is not int or key[2]<0 for key in cas):
            raise ValueError('Invalid CAS dependency identity')
        dependencies = sorted((str(path), sha, size) for path,sha,size in cas)
        value = {'schema':'consense.model-source-scope.v1', 'sourceDeclarationSha256':declaration,
                 'sourceLocations':locations, 'sourceClasses':classifications,
                 'familyContextSha256':hashlib.sha256(family).hexdigest() if family is not None else None,
                 'casDependencies':dependencies, 'softwareIdentity':software}
        return digest(value)
    except (ValueError, TypeError, KeyError) as error:
        raise WindowError('SOURCE_SCOPE_IDENTITY_INVALID', 'Owned source frame cannot be deterministically bound') from error
