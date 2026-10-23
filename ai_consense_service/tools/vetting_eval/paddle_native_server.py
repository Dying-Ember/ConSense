"""Owned, offline Paddle native API with decode-before outputs retained separately."""
import argparse
import base64
import copy
import hashlib
import io
import inspect
import json
import os
from pathlib import Path
import threading
import tempfile

from fastapi import FastAPI, HTTPException
from PIL import Image
from artifact_store import ArtifactStore
from paddle_native_capture import (NativePaddleCapture, NativePaddleCaptureError,
                                   LOCAL_CAS_TRANSPORT, FAILED_INLINE_TRANSPORT, native_asset_root)


def _native_assets(assets, manifest, config, store):
    root = native_asset_root(config.get('nativeCaptureTransport'))
    if root is None:
        return {'rawAssets': {name: base64.b64encode(raw).decode('ascii') for name, raw in assets.items()}}
    if root != store.root:
        raise ValueError('Native provider CAS differs from its pinned transport root')
    references = {}
    def collect(value):
        if isinstance(value, dict):
            if value.get('kind') in ('actual_paddle_tensor', 'actual_numpy_array'):
                name, reference = value.get('assetName'), value.get('artifact')
                if name in references and references[name] != reference:
                    raise ValueError('Native array repeated reference descriptors differ')
                references[name] = reference
            for child in value.values(): collect(child)
        elif isinstance(value, list):
            for child in value: collect(child)
    collect(manifest)
    result = {}
    for name, raw in assets.items():
        reference = references.get(name)
        if reference is None:
            # A failed capture may retain an array before its parent record was appended.
            reference = store.put_bytes(raw, identity_scope='actual_native_array_npy_without_pickle')
        else:
            if reference.get('sha256') != hashlib.sha256(raw).hexdigest() or reference.get('byteCount') != len(raw):
                raise ValueError('Native CAS array differs from captured bytes')
            store.verify(reference)
        result[name] = reference
    return {'rawAssetTransport': LOCAL_CAS_TRANSPORT, 'rawAssetRefs': result}


def load_config(path, expected_sha):
    raw = Path(path).read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected_sha:
        raise ValueError('Native Paddle configuration differs from its exact SHA')
    config = json.loads(raw)
    if config.get('schema') != 'consense.paddle-native-provider.v1' or config.get('precision') != 'bf16':
        raise ValueError('Explicit native Paddle provider schema and BF16 required')
    native_asset_root(config.get('nativeCaptureTransport'))
    assets = config.get('assets', [])
    if not assets:
        raise ValueError('Complete model/runtime/font assets must be pinned')
    files = set()
    for asset in assets:
        file = Path(asset.get('path', ''))
        if not file.is_absolute() or not file.is_file() or str(file.resolve()) in files:
            raise ValueError('Distinct absolute native Paddle asset required')
        digest = hashlib.sha256()
        with file.open('rb') as stream:
            for block in iter(lambda: stream.read(1024*1024), b''):
                digest.update(block)
        if digest.hexdigest() != asset.get('sha256') or file.stat().st_size != asset.get('byteCount'):
            raise ValueError('Native Paddle asset changed: ' + str(file))
        files.add(str(file.resolve()))
    for name in ('vlmModelDir', 'layoutModelDir'):
        directory = Path(config.get(name, ''))
        if not directory.is_absolute() or not directory.is_dir():
            raise ValueError('Explicit local native model directory required')
        actual = {str(file.resolve()) for file in directory.rglob('*') if file.is_file()}
        if not actual or not actual.issubset(files):
            raise ValueError('Every local model-directory file must be in the pinned asset inventory')
    if str(Path(config.get('fontPath', '')).resolve()) not in files:
        raise ValueError('Native font must be pinned without implicit download')
    if type(config.get('maxNewTokens')) is not int or not 1 <= config['maxNewTokens'] <= 8192:
        raise ValueError('Explicit finite native generation cap required')
    return config


class NativePaddleRuntime:
    def __init__(self, config, store):
        self.config, self.store, self.pipeline, self.capture = config, store, None, None
        self.observer_context = None

    def recognize(self, raw):
        if self.pipeline is None:
            for key, value in {'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1',
                    'PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK': 'True',
                    'PADDLE_PDX_LOCAL_FONT_FILE_PATH': self.config['fontPath']}.items():
                os.environ[key] = value
            import paddle
            import paddlex.inference.models.doc_vlm.predictor as predictor_module
            from paddlex.inference.models.common.transformers.transformers import PretrainedModel
            from paddlex.inference.models.common.transformers.generation import GenerationMixin
            from paddleocr import PaddleOCRVL
            if not issubclass(PretrainedModel, GenerationMixin):
                raise ValueError('Official model does not use the observed native generation class')
            declared_sources = {str(Path(asset['path']).resolve()): asset for asset in self.config['assets']}
            for source in (Path(predictor_module.__file__), Path(inspect.getfile(GenerationMixin)),
                           Path(inspect.getfile(PaddleOCRVL)), Path(__file__), Path(inspect.getfile(NativePaddleCapture))):
                asset = declared_sources.get(str(source.resolve()))
                if asset is None or hashlib.sha256(source.read_bytes()).hexdigest() != asset['sha256']:
                    raise ValueError('Actual native observer/SDK source lacks its exact pin: ' + str(source))
            if not paddle.is_compiled_with_cuda():
                raise ValueError('Native Paddle CUDA unavailable; CPU fallback is forbidden')
            paddle.set_device('gpu:0')
            self.capture = NativePaddleCapture(self.store, paddle, self.config['vlmModelDir'], self.config['layoutModelDir'])
            self.observer_context = self.capture.install(predictor_module, GenerationMixin)
            self.observer_context.__enter__()
            try:
                self.pipeline = PaddleOCRVL(pipeline_version='v1.6', vl_rec_backend='native', device='gpu:0',
                    layout_detection_model_dir=self.config['layoutModelDir'], vl_rec_model_dir=self.config['vlmModelDir'],
                    use_doc_orientation_classify=False, use_doc_unwarping=False, use_layout_detection=True,
                    use_chart_recognition=False, use_seal_recognition=False, use_ocr_for_image_block=False,
                    markdown_ignore_labels=[], use_queues=False)
            except Exception:
                self.observer_context.__exit__(None, None, None)
                self.observer_context = None
                raise
        input_descriptor = self.store.put_bytes(raw, identity_scope='actual_native_provider_input_raster')
        with Image.open(io.BytesIO(raw)) as image:
            extension = {'PNG': 'png', 'JPEG': 'jpg', 'TIFF': 'tiff', 'WEBP': 'webp'}[image.format]
        input_root = self.store.root / 'inputs'; input_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='raster-', dir=input_root) as directory:
            path = Path(directory) / ('page.' + extension)
            with path.open('xb') as stream:
                stream.write(raw); stream.flush(); os.fsync(stream.fileno())
            try:
                return self._capture_page(path, input_descriptor)
            except NativePaddleCaptureError as error:
                self.store.put_bytes(json.dumps(error.manifest, ensure_ascii=False, allow_nan=False).encode(),
                    identity_scope='actual_failed_native_capture_manifest')
                for content in error.assets.values():
                    self.store.put_bytes(content, identity_scope='actual_failed_native_capture_asset')
                raise

    def _capture_page(self, path, input_descriptor):
        try:
            results, manifest, assets = self.capture.run(lambda: list(self.pipeline.predict_iter(str(path),
                max_new_tokens=self.config['maxNewTokens'], markdown_ignore_labels=[], use_queues=False)))
        except NativePaddleCaptureError as error:
            error.manifest['input'] = input_descriptor
            raise
        captured_manifest = copy.deepcopy(manifest)
        stage = 'result_count'
        try:
            if len(results) != 1:
                raise ValueError('Native image request did not return exactly one complete page result')
            manifest['input'] = input_descriptor
            manifest['pipelineConfig'] = getattr(self.pipeline, '_merged_paddlex_config', {})
            manifest['softwareIdentityScope'] = 'operator-pinned-owned-provider-assets_not_full_loaded_value_verification'
            result = results[0]
            stage = 'vendor_json'
            vendor = result.json['res']
            stage = 'vendor_markdown'
            markdown = result._to_markdown(pretty=False, show_formula_number=False)
            images = {}
            for name, image in (markdown.get('markdown_images') or {}).items():
                stream = io.BytesIO(); image.save(stream, format='PNG')
                images[name] = base64.b64encode(stream.getvalue()).decode('ascii')
            stage = 'vendor_images'
            output_images = {}
            for name, image in result.img.items():
                stream = io.BytesIO(); image.save(stream, format='PNG')
                output_images[name] = base64.b64encode(stream.getvalue()).decode('ascii')
            stage = 'wire_serialization'
            response = {'layoutParsingResults': [{'prunedResult': vendor,
                'markdown': {'text': markdown.get('markdown_texts', ''), 'images': images},
                'outputImages': output_images}],
                'rawOutputs': {'model_output': base64.b64encode(json.dumps(manifest, ensure_ascii=False,
                    allow_nan=False, sort_keys=True).encode()).decode('ascii')},
                **_native_assets(assets, manifest, self.config, self.store),
                'nativeCapture': {'status': manifest['captureStatus'], 'runtime': manifest['runtime'],
                                  'inputSha256': input_descriptor['sha256'], 'sourceQualityAccepted': False}}
            json.dumps(response, ensure_ascii=False, allow_nan=False)
            self.store.put_bytes(base64.b64decode(response['rawOutputs']['model_output']),
                identity_scope='actual_successful_native_capture_manifest')
            return response
        except Exception as error:
            captured_manifest.update(input=input_descriptor, captureStatus='failed_native_capture',
                nativeCaptureStatusBeforeProviderFailure=captured_manifest.get('captureStatus'),
                failureStage=stage, errorType=type(error).__name__, reason=str(error))
            raise NativePaddleCaptureError(str(error), captured_manifest, assets) from error


def create_app(config, store, runtime_factory=NativePaddleRuntime):
    root = native_asset_root(config.get('nativeCaptureTransport'))
    if root is not None and root != store.root:
        raise ValueError('Native provider CAS differs from its pinned transport root')
    app = FastAPI(title='ConSense owned Paddle native capture provider')
    runtime, lock = runtime_factory(config, store), threading.Lock()
    @app.get('/health')
    def health():
        return {'status': 'wire_available', 'modelReady': False,
                'meaning': 'HTTP availability is not model/quality acceptance'}
    @app.post('/layout-parsing')
    def parse(body: dict):
        try:
            if (body.get('fileType') != 1 or body.get('useLayoutDetection') is not True
                    or any(body.get(key) is not False for key in ('useDocOrientationClassify', 'useDocUnwarping', 'restructurePages'))
                    or body.get('markdownIgnoreLabels') != []):
                raise ValueError('Exact full-raster native switches required')
            encoded = body.get('file')
            if not isinstance(encoded, str) or len(encoded) > 35*1024*1024:
                raise ValueError('Bounded inline raster required')
            raw = base64.b64decode(encoded, validate=True)
            with Image.open(io.BytesIO(raw)) as image:
                if len(raw) > 25*1024*1024 or image.width*image.height > 40000000 or image.format not in ('PNG', 'JPEG', 'TIFF', 'WEBP'):
                    raise ValueError('Native raster exceeds input limits or has an unsupported format')
        except (ValueError, OSError) as error:
            raise HTTPException(422, str(error)) from error
        try:
            with lock:
                return {'errorCode': 0, 'result': runtime.recognize(raw)}
        except NativePaddleCaptureError as error:
            failed_manifest = json.dumps(error.manifest, ensure_ascii=False, allow_nan=False).encode()
            receipt = store.put_bytes(failed_manifest,
                identity_scope='actual_failed_native_capture_manifest')
            try:
                assets = _native_assets(error.assets, error.manifest, config, store)
            except Exception as asset_error:
                # Failed CAS publication must not discard bytes already captured in memory.
                assets = {'rawAssetTransport': FAILED_INLINE_TRANSPORT,
                    'assetReferenceError': {'errorType': type(asset_error).__name__, 'reason': str(asset_error)},
                    'rawAssets': {name: base64.b64encode(raw).decode('ascii') for name, raw in error.assets.items()}}
            raise HTTPException(503, {'kind': 'native_capture_failed', 'reason': str(error), 'receipt': receipt,
                'rawOutputs': {'model_output': base64.b64encode(failed_manifest).decode('ascii')},
                **assets}) from error
        except Exception as error:
            raise HTTPException(503, {'kind': 'native_provider_failed', 'reason': str(error)}) from error
    return app


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--config-sha256', required=True)
    parser.add_argument('--state', required=True)
    parser.add_argument('--port', type=int, required=True)
    args = parser.parse_args()
    import uvicorn
    uvicorn.run(create_app(load_config(args.config, args.config_sha256), ArtifactStore(args.state)),
                host='127.0.0.1', port=args.port, workers=1)
