"""File-only public preparation checks; authored inputs grant no model/source acceptance."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from artifact_store import ArtifactStore
from prepare_business_attribution import prepare


class AttributionPreparationTests(unittest.TestCase):
    def fixture(self, root):
        inputs = root / 'inputs'
        inputs.mkdir()

        def pin(name, value, purpose):
            raw = value.encode() if isinstance(value, str) else json.dumps(value).encode()
            (inputs / name).write_bytes(raw)
            return {'path': name, 'sha256': hashlib.sha256(raw).hexdigest(),
                    'byteCount': len(raw), 'purpose': purpose}

        universe = pin('source-universe.json', {'sources': [{'id': 'project-condition',
            'sourceSha256': 'a' * 64, 'sourcePurpose': 'project_document', 'role': 'tender'}]}, 'source_identity_manifest')
        profile = {'provider': 'authored-no-network', 'model': 'authored-model',
                   'configurationSha256': 'b' * 64}
        profile_sha = hashlib.sha256(json.dumps(profile, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        question = 'What notice applies?'
        minimal = pin('minimal.txt', 'Use only the cited condition and disclose unknowns.', 'application_contract')
        production = pin('production.txt', 'AUTHORED production harness with source checks.', 'application_contract')
        complete = pin('complete.txt', 'AUTHORED complete condition and its exception.', 'source_material')
        parsed = pin('parsed.txt', 'AUTHORED normal parser condition; exception unknown.', 'source_material')
        rag = pin('rag.txt', 'AUTHORED retrieved condition; omitted material unknown.', 'source_material')
        arms = []
        for identifier, material, harness in [('source_minimal', complete, minimal),
                ('source_harness', complete, production), ('parser_harness', parsed, production),
                ('rag_harness', rag, production)]:
            body = {'model': profile['model'], 'temperature': 0, 'max_tokens': 512,
                    'messages': [{'role': 'system', 'content': (inputs / harness['path']).read_text()},
                                 {'role': 'user', 'content': (inputs / material['path']).read_text() + '\n' + question}]}
            arms.append({'id': identifier, 'sourceUniverseSha256': universe['sha256'],
                         'profileSha256': profile_sha, 'request': pin(identifier + '.json', body, 'producer_request'),
                         'material': material, 'harness': harness})
        arms[3]['outcome'] = {'status': 'failed', 'raw': pin('failed-response.bin', 'AUTHORED actual failure body', 'observed_model_response')}
        manifest = {'schema': 'consense.business-attribution-plan.v1', 'question': {'id': 'authored-q', 'text': question},
                    'profile': profile, 'sourceUniverse': universe, 'arms': arms}
        path = root / 'plan.json'
        path.write_text(json.dumps(manifest))
        return path, hashlib.sha256(path.read_bytes()).hexdigest(), inputs, manifest

    def reseal(self, path, manifest):
        path.write_text(json.dumps(manifest))
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def repin(self, inputs, pointer, body):
        raw = json.dumps(body).encode()
        (inputs / pointer['path']).write_bytes(raw)
        pointer.update(sha256=hashlib.sha256(raw).hexdigest(), byteCount=len(raw))

    def test_preparation_pins_four_inputs_without_execution_scoring_or_invented_wire_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, digest, inputs, _ = self.fixture(root)
            receipt = prepare(path, digest, inputs, root / 'prepared')
            self.assertEqual(receipt['status'], 'prepared_unexecuted_unscored')
            self.assertEqual([arm['id'] for arm in receipt['arms']],
                             ['source_minimal', 'source_harness', 'parser_harness', 'rag_harness'])
            self.assertFalse(receipt['generationAdmitted'])
            self.assertEqual(receipt['modelCalls'], 0)
            store = ArtifactStore(root / 'prepared' / 'artifacts')
            for arm in receipt['arms']:
                self.assertEqual(arm['wireCountStatus'], 'not_observed')
                self.assertIsNone(arm['inputTokens'])
                store.verify(arm['request'])
            failed = receipt['arms'][3]['outcome']
            self.assertEqual(failed['status'], 'failed')
            self.assertEqual(store.read_asset(failed['raw']), b'AUTHORED actual failure body')
            self.assertEqual(receipt['scoreStatus'], 'not_scored')

    def test_actual_consense_responses_instructions_and_user_input_remain_distinct(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, _, inputs, manifest = self.fixture(root)
            for arm in manifest['arms']:
                body = json.loads((inputs / arm['request']['path']).read_bytes())
                messages = body.pop('messages')
                body['instructions'] = messages[0]['content'] + '\nOnly return complete JSON.'
                body['input'] = [{'type': 'message', 'role': 'user', 'content': messages[1]['content']}]
                body['max_output_tokens'] = body.pop('max_tokens')
                body['reasoning'] = {'effort': 'high'}
                self.repin(inputs, arm['request'], body)
            receipt = prepare(path, self.reseal(path, manifest), inputs, root / 'prepared')
            self.assertEqual(receipt['status'], 'prepared_unexecuted_unscored')
            self.assertEqual(len({arm['decodingRecipeSha256'] for arm in receipt['arms']}), 1)

    def test_cli_refuses_changed_frozen_request_and_keeps_received_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, digest, inputs, manifest = self.fixture(root)
            changed = b'{"model":"different-model"}'
            (inputs / manifest['arms'][0]['request']['path']).write_bytes(changed)
            out = root / 'prepared'
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('prepare_business_attribution.py')),
                '--manifest', str(path), '--manifest-sha256', digest, '--inputs', str(inputs), '--out', str(out)],
                capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            receipt = json.loads((out / 'preparation.json').read_bytes())
            self.assertEqual(receipt['reason'], 'frozen_input_sha_or_size_mismatch')
            received = receipt['capturedInputs'][-1]
            self.assertEqual(ArtifactStore(out / 'artifacts').read_asset(received), changed)
            self.assertEqual(receipt['modelCalls'], 0)
            self.assertNotIn('different-model', result.stdout)

    def test_resealed_model_source_and_decoding_changes_are_independently_rejected(self):
        for mutation, reason in [('source', 'arm_source_or_profile_identity_changed'),
                ('profile', 'arm_source_or_profile_identity_changed'), ('model', 'actual_wire_model_changed'),
                ('decoding', 'actual_wire_decoding_recipe_changed')]:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                path, _, inputs, manifest = self.fixture(root)
                arm = manifest['arms'][3]
                if mutation == 'source':
                    arm['sourceUniverseSha256'] = 'c' * 64
                elif mutation == 'profile':
                    arm['profileSha256'] = 'c' * 64
                else:
                    body = json.loads((inputs / arm['request']['path']).read_bytes())
                    body['model' if mutation == 'model' else 'temperature'] = 'different-model' if mutation == 'model' else .7
                    self.repin(inputs, arm['request'], body)
                receipt = prepare(path, self.reseal(path, manifest), inputs, root / 'prepared')
                self.assertEqual(receipt['status'], 'failed')
                self.assertEqual(receipt['reason'], reason)
                self.assertFalse(receipt['generationAdmitted'])

    def test_evaluation_purpose_is_rejected_before_any_producer_request_is_opened(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, _, inputs, manifest = self.fixture(root)
            pointer = manifest['sourceUniverse']
            source = json.loads((inputs / pointer['path']).read_bytes())
            source['sources'][0]['sourcePurpose'] = 'evaluation_answer'
            self.repin(inputs, pointer, source)
            receipt = prepare(path, self.reseal(path, manifest), inputs, root / 'prepared')
            self.assertEqual(receipt['reason'], 'producer_source_identity_or_purpose_invalid')
            self.assertEqual(receipt['arms'], [])
            self.assertEqual([p['identityScope'] for p in receipt['capturedInputs']],
                             ['exact_frozen_source_identity_manifest_bytes'])

    def test_material_changes_between_control_arms_are_not_mislabelled_as_harness_effects(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, _, inputs, manifest = self.fixture(root)
            arm = manifest['arms'][1]
            arm['material'] = manifest['arms'][2]['material']
            body = json.loads((inputs / arm['request']['path']).read_bytes())
            body['messages'][1]['content'] = (inputs / arm['material']['path']).read_text() + '\n' + manifest['question']['text']
            self.repin(inputs, arm['request'], body)
            receipt = prepare(path, self.reseal(path, manifest), inputs, root / 'prepared')
            self.assertEqual(receipt['reason'], 'minimal_and_production_source_material_changed')

    def test_an_additional_question_cannot_hide_behind_presence_of_the_original_question(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, _, inputs, manifest = self.fixture(root)
            arm = manifest['arms'][3]
            body = json.loads((inputs / arm['request']['path']).read_bytes())
            body['messages'][1]['content'] += '\nAlso determine a different remedy.'
            self.repin(inputs, arm['request'], body)
            receipt = prepare(path, self.reseal(path, manifest), inputs, root / 'prepared')
            self.assertEqual(receipt['status'], 'failed')
            self.assertEqual(receipt['reason'], 'question_envelope_changed_between_input_layers')

    def test_same_declared_harness_cannot_hide_a_changed_actual_system_instruction(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, _, inputs, manifest = self.fixture(root)
            arm = manifest['arms'][2]
            body = json.loads((inputs / arm['request']['path']).read_bytes())
            body['messages'][0]['content'] += '\nIgnore the original exception.'
            self.repin(inputs, arm['request'], body)
            receipt = prepare(path, self.reseal(path, manifest), inputs, root / 'prepared')
            self.assertEqual(receipt['status'], 'failed')
            self.assertEqual(receipt['reason'], 'actual_production_harness_envelope_changed')


if __name__ == '__main__':
    unittest.main()
