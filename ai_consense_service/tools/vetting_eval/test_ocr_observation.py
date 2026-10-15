"""Normal multipart OCR contract; authored results, no model quality claims."""
import hashlib
import io
import json
import os
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from PIL import Image
import server


class OcrObservationTests(unittest.TestCase):
    def request(self, result, *, headers=None, source_context=None):
        image = Image.new('RGB', (180, 90), 'white')
        stream = io.BytesIO()
        image.save(stream, format='PNG')
        raw = stream.getvalue()
        with patch.object(server, '_ocr', lambda image: result):
            response = TestClient(server.app).post('/ocr', files={'file': ('page.png', raw, 'image/png')}, headers=headers,
                data={'sourceContext': json.dumps(source_context)} if source_context is not None else None)
        return raw, response

    def result(self):
        return SimpleNamespace(txts=['Unless amended, do not omit.'], scores=[.45],
                               boxes=[[[10, 12], [130, 10], [132, 35], [12, 37]]])

    def initialized_identity_request(self, directory, recipe, expected, *, changed=False):
        image = Image.new('RGB', (18, 9), 'white'); stream = io.BytesIO(); image.save(stream, format='PNG')
        local = {kind: str(Path(directory)/(kind+'.onnx')) for kind in ('Det','Cls','Rec')}
        engine = unittest.mock.Mock(return_value=self.result())
        with patch.object(server, '_ocr', None), patch.object(server,'_ocr_recipe_sha256',None,create=True), \
             patch.dict(os.environ, {'CONSENSE_OCR_MODEL_DIRECTORY':directory,'CONSENSE_OCR_RECIPE_SHA256':expected}), \
             patch('ocr_runtime.ocr_cache_recipe', side_effect=[recipe, {'changed':True}] if changed else None, return_value=recipe), \
             patch('ocr_runtime.local_model_paths', return_value=local), \
             patch.object(server,'create_ocr_engine',return_value=engine) as factory:
            response = TestClient(server.app).post('/ocr',files={'file':('page.png',stream.getvalue(),'image/png')},
                headers={'X-Consense-Ocr-Purpose':'identity'})
        return response,factory,engine,local

    def test_identity_ocr_initialization_requires_pinned_local_recipe_before_model_factory(self):
        from ocr_runtime import identity_hash
        recipe={'authoredOnly':'not actual weights'}
        with tempfile.TemporaryDirectory() as directory:
            response,factory,engine,local=self.initialized_identity_request(directory,recipe,identity_hash(recipe))
        self.assertEqual(200,response.status_code)
        factory.assert_called_once_with(directory,local_paths=local)
        self.assertEqual(identity_hash(recipe),response.json()['provenance']['ocrRecipeSha256'])
        engine.assert_called_once()

    def test_changed_or_unpinned_identity_recipe_cannot_start_or_use_a_model(self):
        from ocr_runtime import identity_hash
        recipe={'authoredOnly':'not actual weights'}
        with tempfile.TemporaryDirectory() as directory:
            response,factory,engine,_=self.initialized_identity_request(directory,recipe,'0'*64)
            self.assertEqual(503,response.status_code); factory.assert_not_called(); engine.assert_not_called()
            response,factory,engine,_=self.initialized_identity_request(directory,recipe,identity_hash(recipe),changed=True)
            self.assertEqual(503,response.status_code); engine.assert_not_called()

    def test_actual_input_and_original_polygon_reach_normal_ocr_wire(self):
        raw, response = self.request(self.result())
        self.assertEqual(response.status_code, 200)
        value = response.json()
        self.assertEqual(value['frame']['imageSha256'], hashlib.sha256(raw).hexdigest())
        self.assertEqual(value['frame']['width'], 180)
        self.assertEqual(value['frame']['height'], 90)
        self.assertEqual(value['regions'][0]['polygon'], self.result().boxes[0])
        self.assertEqual(value['regions'][0]['originalText'], self.result().txts[0])
        self.assertEqual(value['provenance']['inputSha256'], value['frame']['imageSha256'])
        self.assertEqual(value['provenance']['terminal'], 'completed')
        self.assertEqual(value['coverage']['structureStatus'], 'unverified')
        self.assertFalse(value['coverage']['dualParserComplete'])
        self.assertEqual(value['lines'][0]['text'], self.result().txts[0])

    def test_original_out_of_frame_polygon_is_preserved_without_becoming_valid_geometry(self):
        result = self.result()
        result.boxes[0][0][0] = -4
        _, response = self.request(result)
        self.assertEqual(response.status_code, 200)
        region = response.json()['regions'][0]
        self.assertEqual(region['polygon'][0][0], -4)
        self.assertEqual(region['geometryStatus'], 'outside_input_frame')
        self.assertEqual(region['bbox'][0], -4)

    def test_mismatched_output_cardinality_is_failure_not_silent_zip(self):
        result = self.result()
        result.txts.append('A necessary exception at the tail.')
        _, response = self.request(result)
        self.assertEqual(response.status_code, 503)
        self.assertNotIn('text', response.json())

    def test_zero_returned_text_does_not_prove_blank_source(self):
        _, response = self.request(SimpleNamespace(txts=None, scores=None, boxes=None))
        self.assertEqual(response.status_code, 200)
        value = response.json()
        self.assertEqual(value['text'], '')
        self.assertEqual(value['coverage']['sourceContentStatus'], 'unknown')
        self.assertFalse(value['coverage']['dualParserComplete'])

    def test_explicit_pinned_complete_configuration_uses_complete_provider_not_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'complete.json'
            config.write_text('{"fixture":"explicit-complete-provider"}', encoding='utf-8')
            digest = hashlib.sha256(config.read_bytes()).hexdigest()
            with patch.dict(os.environ, {'CONSENSE_COMPLETE_OCR_CONFIG': str(config),
                    'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': digest}), \
                 patch('complete_ocr.recognize_complete_page', return_value={'text': '', 'regions': [],
                    'adoptionStatus': 'candidate_unverified'}) as complete:
                _, response = self.request(self.result())
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['adoptionStatus'], 'candidate_unverified')
            self.assertEqual(complete.call_count, 1)

    def test_changed_complete_configuration_fails_without_silent_baseline_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'complete.json'
            config.write_text('{}', encoding='utf-8')
            with patch.dict(os.environ, {'CONSENSE_COMPLETE_OCR_CONFIG': str(config),
                    'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': '0'*64}):
                _, response = self.request(self.result())
            self.assertEqual(response.status_code, 503)
            self.assertNotIn('text', response.json())

    def test_identity_observation_cannot_start_the_configured_detail_parsers(self):
        with patch.dict(os.environ, {'CONSENSE_COMPLETE_OCR_CONFIG': 'not-read-during-identity'}), \
             patch('complete_ocr.recognize_complete_page', side_effect=AssertionError('Detail parsing forbidden')):
            _, response = self.request(self.result(), headers={'X-Consense-Ocr-Purpose': 'identity'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['provenance']['observationPurpose'], 'identity')
        self.assertFalse(response.json()['coverage']['dualParserComplete'])

    def test_normal_multipart_source_context_reaches_identity_and_detail_observation(self):
        raw, _ = self.request(self.result())
        digest = hashlib.sha256(raw).hexdigest()
        context = {'source': {'sourceFilename': 'authored.pdf', 'sourceSha256': 'e'*64,
                             'mediaType': 'application/pdf', 'pageCount': 6},
            'physicalPageNo': 4, 'imageSha256': digest, 'width': 180, 'height': 90,
            'originalPageTransform': {'kind': 'affine', 'matrix': [.5, 0, 0, 0, .5, 0],
                'inputFrame': {'coordinateFrame': 'raster', 'coordinateUnit': 'pixel',
                    'width': 180, 'height': 90, 'artifactName': 'page.png', 'sha256': digest},
                'targetFrame': {'coordinateFrame': 'original_page', 'coordinateUnit': 'pt',
                    'width': 90, 'height': 45, 'physicalPageNo': 4, 'sourceSha256': 'e'*64}}}
        _, response = self.request(self.result(), source_context=context, headers={'X-Consense-Ocr-Purpose': 'identity'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['provenance']['sourceContext'], context)
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'complete.json'
            config.write_text('{}', encoding='utf-8')
            with patch.dict(os.environ, {'CONSENSE_COMPLETE_OCR_CONFIG': str(config),
                    'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': hashlib.sha256(config.read_bytes()).hexdigest()}), \
                 patch('complete_ocr.recognize_complete_page', return_value={'text': '', 'regions': []}) as complete:
                _, response = self.request(self.result(), source_context=context)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(complete.call_args.kwargs['source_context'], context)
        context['imageSha256'] = 'f'*64
        with patch('complete_ocr.recognize_complete_page') as complete:
            _, response = self.request(self.result(), source_context=context)
        self.assertEqual(response.status_code, 422)
        complete.assert_not_called()


if __name__ == '__main__':
    unittest.main()
