"""Whole-page candidate routing; selected observations are never quality approval."""
import copy
import hashlib
import json
import re
from structured_parser_evidence import (assert_complete_dual, verify_dual_integrity,
                                       ParserEvidenceError, project_bbox_to_original)


FEATURES = {'hasTables', 'allTableStructuresObserved', 'allGeometryBound', 'completeRawCapture'}


def validate_policy(policy):
    if not isinstance(policy, dict) or set(policy) != {'schema', 'version', 'acceptance', 'rules'} or (
            policy['schema'] != 'consense.whole-parser-feature-policy.v1' or not isinstance(policy['version'], str) or not policy['version']):
        raise ValueError('Explicit whole-parser feature policy required; no source/page/answer lookup')
    accepted = policy['acceptance']
    if (not isinstance(accepted, dict) or set(accepted) != {'status', 'acceptedBy', 'method', 'evidenceSha256', 'referenceVersion'}
            or accepted.get('status') != 'accepted' or accepted.get('acceptedBy') not in ('user', 'codex')
            or accepted.get('method') != 'independent_source_feature_evaluation'
            or not re.fullmatch('[a-f0-9]{64}', accepted.get('evidenceSha256', ''))
            or not isinstance(accepted.get('referenceVersion'), str) or not accepted['referenceVersion']):
        raise ValueError('Identity-bound independent feature evaluation required; not a source accuracy claim')
    rules = policy['rules']
    if not isinstance(rules, list) or not 1 <= len(rules) <= 16:
        raise ValueError('Bounded whole-parser feature rules required')
    for rule in rules:
        if (not isinstance(rule, dict) or set(rule) != {'parser', 'requires'} or rule['parser'] not in ('paddleocr-vl', 'mineru')
                or not isinstance(rule['requires'], dict) or not rule['requires']
                or not set(rule['requires']).issubset(FEATURES) or any(type(value) is not bool for value in rule['requires'].values())
                or rule['requires'].get('completeRawCapture') is not True):
            raise ValueError('Only complete whole-group observations may match approved boolean feature rules')
    return hashlib.sha256(json.dumps(policy, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def select_whole_parser_groups(bundle, policy=None, *, artifact_store=None):
    policy_sha = validate_policy(policy) if policy is not None else None
    verify_dual_integrity(bundle, artifact_store=artifact_store)
    try:
        assert_complete_dual(bundle, artifact_store=artifact_store)
        complete = True
    except ParserEvidenceError:
        complete = False
    pages = {}
    for run in bundle['runs']:
        for page in run['pages']:
            tables = [block['table'] for block in page['blocks'] if 'table' in block]
            features = {'hasTables': bool(tables), 'allTableStructuresObserved': bool(tables) and all(
                table.get('structureStatus') != 'unknown' for table in tables),
                'allGeometryBound': bool(page['blocks']) and all(project_bbox_to_original(run, page, block.get('bbox'))['status'] == 'verified'
                    for block in page['blocks']), 'completeRawCapture': complete}
            pages.setdefault(page['physicalPageNo'], []).append({'parser': run['parser'],
                'runId': run['runId'], 'pageId': page['pageId'], 'features': features,
                'page': copy.deepcopy(page), 'blocks': copy.deepcopy(page['blocks'])})
    groups = []
    for page, candidates in sorted(pages.items()):
        matches = {candidate['parser'] for candidate in candidates for rule in policy['rules']
            if candidate['parser'] == rule['parser'] and all(candidate['features'][key] == value
                for key, value in rule['requires'].items())} if policy else set()
        selected = next(iter(matches)) if len(matches) == 1 else None
        groups.append({'physicalPageNo': page, 'scope': 'whole_page', 'source': copy.deepcopy(bundle['source']),
            'selectionStatus': 'feature_policy_selected_candidate' if selected else 'candidate_unverified',
            'selectedParser': selected, 'policySha256': policy_sha, 'candidates': candidates,
            'sourceQualityAccepted': False, 'adoptionStatus': 'candidate_unverified',
            'reason': 'One independently accepted generic feature rule selects a coherent complete page candidate' if selected else
                'No unique admitted whole-group rule; both complete observations retained without cell fusion'})
    return groups
