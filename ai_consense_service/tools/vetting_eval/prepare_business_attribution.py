"""Freeze the four attribution inputs; never dispatch, grade, adopt or read gold.

Pinned requests remain complete bytes in the existing CAS. Preparation does not
establish source completeness, effective model identity or a token admission.

The plan is consense.business-attribution-plan.v1: question {id,text}, profile
{provider,model,configurationSha256}, a sourceUniverse pointer, and four ordered
arms. Each arm has id, sourceUniverseSha256, profileSha256, request/material/harness
pointers, and optional outcome {status,raw}. A pointer is exactly
{path,sha256,byteCount,purpose}; paths are relative to --inputs. The source universe
contains sources [{id,sourceSha256,sourcePurpose,role}]. First two arms require the
same material bytes; last three require the same actual system/output contract.
All arms require the same question envelope outside their complete material and
the same decoding settings. History and multimodal requests need their own later
controlled plan; this tool refuses them instead of flattening or truncating.
"""
import argparse
import json
from pathlib import Path
import re

from artifact_store import ArtifactStore
from official_business_capture import CaptureError, decode, digest, frozen, require


ARMS = ('source_minimal', 'source_harness', 'parser_harness', 'rag_harness')
LAYERS = ('source_reviewed_material_minimal_contract', 'same_material_production_harness',
          'normal_parser_material_same_harness', 'actual_rag_material_same_harness')


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'),
                      allow_nan=False).encode()


def prepare(manifest_path, expected_sha256, inputs, output):
    inputs, output = Path(inputs).resolve(), Path(output).resolve()
    require(inputs.is_dir() and not output.exists() and not output.is_relative_to(inputs),
            'fresh_output_outside_frozen_inputs_required')
    output.mkdir(parents=True)
    store = ArtifactStore(output / 'artifacts')
    receipt = {'schema': 'consense.business-attribution-preparation.v1', 'status': 'failed',
               'modelCalls': 0, 'generationAdmitted': False, 'scoreStatus': 'not_scored',
               'sourceQualityAccepted': False, 'arms': [], 'capturedInputs': []}
    originals = []

    def capture(pointer, purpose):
        require(isinstance(pointer, dict) and set(pointer) == {'path', 'sha256', 'byteCount', 'purpose'}
                and pointer['purpose'] == purpose, 'input_purpose_or_descriptor_invalid')
        path = (inputs / pointer['path']).resolve()
        require(path.is_relative_to(inputs) and path.is_file()
                and not any(re.match(r'^2[de](?:_|\s|$)', part, re.I) for part in path.parts),
                'evaluation_or_outside_input_forbidden')
        ref = store.put_file(path, identity_scope='exact_frozen_' + purpose + '_bytes')
        receipt['capturedInputs'].append(ref)
        require(type(pointer['byteCount']) is int and pointer['byteCount'] == ref['byteCount']
                and pointer['sha256'] == ref['sha256'], 'frozen_input_sha_or_size_mismatch')
        originals.append((path, ref))
        return ref, store.read_asset(ref)

    try:
        manifest = frozen(manifest_path, expected_sha256)
        ref = store.put_file(manifest_path, identity_scope='exact_attribution_plan_bytes')
        require(ref['sha256'] == expected_sha256.lower(), 'manifest_changed_during_capture')
        receipt['manifest'] = ref
        originals.append((Path(manifest_path), ref))
        require(set(manifest) == {'schema', 'question', 'profile', 'sourceUniverse', 'arms'}
                and manifest['schema'] == 'consense.business-attribution-plan.v1', 'plan_contract_invalid')
        question, profile = manifest['question'], manifest['profile']
        require(isinstance(question, dict) and set(question) == {'id', 'text'}
                and all(isinstance(v, str) and v.strip() for v in question.values()), 'question_identity_required')
        require(isinstance(profile, dict) and set(profile) == {'provider', 'model', 'configurationSha256'}
                and all(isinstance(v, str) and v.strip() for v in profile.values())
                and re.fullmatch('[a-f0-9]{64}', profile['configurationSha256']), 'profile_identity_required')
        profile_sha = digest(encoded(profile))
        universe_ref, universe_raw = capture(manifest['sourceUniverse'], 'source_identity_manifest')
        universe = decode(universe_raw)
        require(isinstance(universe, dict) and set(universe) == {'sources'}
                and isinstance(universe['sources'], list) and universe['sources'], 'source_universe_required')
        source_ids = set()
        for source in universe['sources']:
            require(isinstance(source, dict) and set(source) == {'id', 'sourceSha256', 'sourcePurpose', 'role'}
                    and isinstance(source['id'], str) and source['id'] and source['id'] not in source_ids
                    and source['sourcePurpose'] in ('project_document', 'reference_standard')
                    and source['role'] in ('tender', 'standard', 'project_fact', 'package_manifest')
                    and re.fullmatch('[a-f0-9]{64}', source['sourceSha256']), 'producer_source_identity_or_purpose_invalid')
            source_ids.add(source['id'])
        arms = manifest['arms']
        require(isinstance(arms, list) and len(arms) == 4 and all(isinstance(a, dict) for a in arms)
                and [arm.get('id') for arm in arms] == list(ARMS), 'exact_four_ordered_arms_required')
        recipe_sha, question_envelope_sha = None, None
        for index, arm in enumerate(arms):
            require(set(arm) in ({'id', 'sourceUniverseSha256', 'profileSha256', 'request', 'material', 'harness'},
                                {'id', 'sourceUniverseSha256', 'profileSha256', 'request', 'material', 'harness', 'outcome'})
                    and arm['sourceUniverseSha256'] == universe_ref['sha256']
                    and arm['profileSha256'] == profile_sha, 'arm_source_or_profile_identity_changed')
            row = {'id': arm['id'], 'inputLayer': LAYERS[index], 'wireCountStatus': 'not_observed',
                   'inputTokens': None, 'outcome': {'status': 'not_executed', 'raw': None}}
            receipt['arms'].append(row)
            row['request'], raw = capture(arm['request'], 'producer_request')
            body = decode(raw)
            row['material'], material = capture(arm['material'], 'source_material')
            row['harness'], harness = capture(arm['harness'], 'application_contract')
            require(isinstance(body, dict) and body.get('model') == profile['model'], 'actual_wire_model_changed')
            require(('messages' in body) != ('input' in body), 'supported_single_request_protocol_required')
            turns = body.get('messages', body.get('input'))
            require(isinstance(turns, list) and turns and all(isinstance(t, dict) for t in turns)
                    and sum(t.get('role') == 'user' for t in turns) == 1
                    and all(t.get('role') in ('system', 'developer', 'user') and isinstance(t.get('content'), str) for t in turns),
                    'single_question_text_request_without_history_required')
            user = next(t['content'] for t in turns if t['role'] == 'user')
            system = '\n'.join(t['content'] for t in turns if t['role'] != 'user') + str(body.get('system', '')) + str(body.get('instructions', ''))
            material_text, harness_text = material.decode('utf-8'), harness.decode('utf-8')
            require(material_text and harness_text and user.count(material_text) == 1
                    and harness_text in system, 'actual_wire_question_material_or_harness_differs')
            envelope = user.replace(material_text, '', 1)
            require(question['text'] in envelope, 'actual_wire_question_material_or_harness_differs')
            actual_envelope = digest(envelope.encode())
            require(question_envelope_sha is None or question_envelope_sha == actual_envelope,
                    'question_envelope_changed_between_input_layers')
            question_envelope_sha = actual_envelope
            row['questionEnvelopeSha256'] = actual_envelope
            row['harnessEnvelopeSha256'] = digest(encoded({'system': system,
                'responseFormat': body.get('response_format'), 'textFormat': body.get('text')}))
            # Output schemas belong to the arm's contract; decoding settings must remain identical.
            recipe = {k: v for k, v in body.items() if k not in ('messages', 'input', 'system', 'instructions', 'response_format', 'text')}
            actual_recipe = digest(encoded({'protocol': 'messages' if 'messages' in body else 'input', 'settings': recipe}))
            require(recipe_sha is None or recipe_sha == actual_recipe, 'actual_wire_decoding_recipe_changed')
            recipe_sha = actual_recipe
            row['wireSha256'] = row['request']['sha256']
            row['decodingRecipeSha256'] = actual_recipe
            if 'outcome' in arm:
                outcome = arm['outcome']
                require(isinstance(outcome, dict) and set(outcome) == {'status', 'raw'}
                        and outcome['status'] in ('failed', 'incomplete', 'cancelled', 'unknown', 'model_returned'),
                        'outcome_contract_invalid')
                row['outcome'] = {'status': outcome['status'], 'raw': None, 'identityScope': 'supplied_observation_not_active_execution'}
                if outcome['raw'] is not None:
                    row['outcome']['raw'], _ = capture(outcome['raw'], 'observed_model_response')
        require(receipt['arms'][0]['material']['sha256'] == receipt['arms'][1]['material']['sha256'],
                'minimal_and_production_source_material_changed')
        require(len({row['harness']['sha256'] for row in receipt['arms'][1:]}) == 1,
                'production_harness_changed_between_input_layers')
        require(len({row['harnessEnvelopeSha256'] for row in receipt['arms'][1:]}) == 1,
                'actual_production_harness_envelope_changed')
        for path, descriptor in originals:
            require(digest(path.read_bytes()) == descriptor['sha256'], 'frozen_input_changed_before_preparation_commit')
            store.verify(descriptor)
        receipt.update(status='prepared_unexecuted_unscored', questionSha256=digest(encoded(question)),
                       sourceUniverse=universe_ref, profileSha256=profile_sha,
                       identityScope='operator_declared_profile_and_source_universe_not_active_model_or_semantic_acceptance',
                       producerIsolation='only_declared_project_or_reference_sources_allowed; no_evaluation_inputs_read; request_content_isolation_requires_independent_review')
    except (CaptureError, ValueError, TypeError, KeyError, OSError) as error:
        receipt['failureType'] = type(error).__name__
        receipt['reason'] = str(error) if isinstance(error, CaptureError) else 'invalid_or_unreadable_frozen_input'
    with (output / 'preparation.json').open('xb') as stream:
        stream.write(encoded(receipt) + b'\n')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--inputs', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    try:
        receipt = prepare(args.manifest, args.manifest_sha256, args.inputs, args.out)
    except CaptureError as error:
        print(json.dumps({'status': 'failed', 'reason': str(error)}))
        return 1
    print(json.dumps({'status': receipt['status'], 'receipt': str(Path(args.out).resolve() / 'preparation.json')}))
    return 0 if receipt['status'] == 'prepared_unexecuted_unscored' else 1


if __name__ == '__main__':
    raise SystemExit(main())
