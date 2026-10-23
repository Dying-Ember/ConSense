"""Capture native neural outputs inside the owned Paddle pipeline process."""
from contextlib import contextmanager
import copy
import hashlib
import inspect
import io
import json
from pathlib import Path
from collections.abc import Mapping

MAX_NATIVE_ARRAY_BYTES = 512 * 1024 * 1024
LOCAL_CAS_TRANSPORT = 'consense.paddle-native-cas-assets.v1'
FAILED_INLINE_TRANSPORT = 'consense.paddle-native-failed-inline-assets.v1'


def native_asset_root(transport):
    """Resolve only operator configuration; a response never selects a CAS root."""
    if transport is None:
        return None
    if (not isinstance(transport, dict) or set(transport) != {'schema', 'artifactRoot'}
            or transport.get('schema') != LOCAL_CAS_TRANSPORT
            or not isinstance(transport.get('artifactRoot'), str)):
        raise ValueError('Explicit native local CAS transport configuration required')
    root = Path(transport['artifactRoot'])
    if not root.is_absolute() or not root.is_dir():
        raise ValueError('Native local CAS root must be an existing absolute directory')
    return root.resolve()


class NativePaddleCaptureError(RuntimeError):
    def __init__(self, reason, manifest, assets):
        super().__init__(reason)
        self.manifest, self.assets = manifest, assets


def validate_parameter_rows(rows, allowlist):
    """Plain observed row gate shared by native producer and received codec.

    It verifies the recorded device/precision policy, never loaded weight values
    or the independent authority of a received class declaration.
    """
    if (not isinstance(allowlist, (list, tuple)) or any(not isinstance(name, str)
            or not name or any(not token.isidentifier() for token in name.split('.')) for name in allowlist)):
        raise ValueError('Official FP32 module declaration is unsupported')
    if not isinstance(rows, list) or not rows:
        raise ValueError('Complete actual named parameter observations required')
    def official_fp32(name):
        tokens = name.split('.')
        return any(any(tokens[index:index+len(marker.split('.'))] == marker.split('.')
            for index in range(len(tokens))) for marker in allowlist)
    names, bf16, fp32 = set(), [], []
    nonfloating = {'paddle.bool', 'paddle.int8', 'paddle.uint8', 'paddle.int16', 'paddle.int32', 'paddle.int64'}
    for row in rows:
        if (not isinstance(row, dict) or not isinstance(row.get('name'), str) or not row['name']
                or row['name'] in names or row.get('place') != 'Place(gpu:0)'
                or not isinstance(row.get('shape'), list)
                or any(type(dimension) is not int or dimension < 0 for dimension in row['shape'])):
            raise ValueError('Actual parameter name, shape or CUDA0 place is invalid')
        names.add(row['name'])
        if row.get('dtype') == 'paddle.bfloat16':
            bf16.append(row['name'])
        elif row.get('dtype') == 'paddle.float32' and official_fp32(row['name']):
            fp32.append(row['name'])
        elif row.get('dtype') not in nonfloating:
            raise ValueError('Actual main parameters require BF16 and class-declared official FP32 only on CUDA0; fallback is forbidden')
    if not bf16:
        raise ValueError('Actual main BF16 parameters unavailable')
    return bf16, fp32


class NativePaddleCapture:
    def __init__(self, store, paddle, vlm_directory, layout_directory=None):
        self.store, self.paddle, self.vlm_directory = store, paddle, vlm_directory
        self.layout_directory = layout_directory
        self.runtime = {'mainParameters': None, 'layoutSessions': [],
                        'loadedWeightValuesVerification': 'not_performed'}
        self.active, self.attempt = False, None
        self.assets, self.generations, self.layouts, self.byte_count = {}, [], [], 0

    def archive(self, value):
        import numpy as np
        tensor = isinstance(value, self.paddle.Tensor)
        if tensor or isinstance(value, np.ndarray):
            array = value.numpy() if tensor else value
            if tensor and str(value.dtype) == 'paddle.bfloat16' and array.dtype != np.uint16:
                raise ValueError('BF16 native bits unavailable; converted FP32 is not exact capture')
            stream = io.BytesIO()
            np.save(stream, array, allow_pickle=False)
            raw = stream.getvalue()
            digest = hashlib.sha256(raw).hexdigest()
            name = 'arrays/' + digest + '.npy'
            if name not in self.assets:
                if self.byte_count + len(raw) > MAX_NATIVE_ARRAY_BYTES:
                    raise ValueError('Native array capture limit exceeded; no complete capture claim')
                self.assets[name] = raw
                self.byte_count += len(raw)
            descriptor = self.store.put_bytes(raw, identity_scope='actual_native_array_npy_without_pickle')
            return {'kind': 'actual_paddle_tensor' if tensor else 'actual_numpy_array',
                    'assetName': name, 'artifact': descriptor, 'arrayDtype': str(array.dtype),
                    'dtype': str(value.dtype), 'shape': list(value.shape),
                    'place': str(value.place) if tensor else 'cpu_numpy_staging',
                    'bf16Encoding': 'uint16_native_bits' if tensor and str(value.dtype) == 'paddle.bfloat16' else None}
        if isinstance(value, Mapping):
            return {'kind': 'mapping', 'members': [{'key': self.archive(key), 'value': self.archive(item)}
                                                for key, item in value.items()]}
        if isinstance(value, (list, tuple)):
            return {'kind': type(value).__name__, 'members': [self.archive(item) for item in value]}
        if isinstance(value, np.generic):
            return self.archive(np.asarray(value))
        if value is None or isinstance(value, (str, int, float, bool)):
            json.dumps(value, allow_nan=False)
            return value
        raise ValueError('Unsupported native capture value: ' + type(value).__qualname__)

    def require_active(self):
        if not self.active:
            raise ValueError('Native inference outside an exact request capture is forbidden')

    def cuda_tensors(self, value):
        if isinstance(value, self.paddle.Tensor):
            return [str(value.place)]
        if isinstance(value, Mapping):
            return [place for item in value.values() for place in self.cuda_tensors(item)]
        if isinstance(value, (list, tuple)):
            return [place for item in value for place in self.cuda_tensors(item)]
        return []

    def parameters(self, model, predictor):
        if Path(predictor.model_dir).resolve() != Path(self.vlm_directory).resolve():
            raise ValueError('Actual VLM directory differs from configured local model')
        rows = [{'name': name, 'shape': list(value.shape), 'dtype': str(value.dtype), 'place': str(value.place)}
                for name, value in model.named_parameters()]
        # The installed official class keeps visual/mlp_AR in FP32 when its
        # remaining floating parameters load in BF16. Read the actual class
        # declaration; neither operator configuration nor substring similarity
        # authorizes an extra FP32 module or converts a native parameter.
        allowlist = getattr(type(model), '_keep_in_fp32_modules', None) or []
        bf16, fp32 = validate_parameter_rows(rows, allowlist)
        self.runtime['mainParameters'] = {'modelDir': str(Path(predictor.model_dir).resolve()),
            'precisionStatus': 'actual_bf16_cuda_with_official_fp32_parameters' if fp32 else 'actual_bf16_cuda_parameters',
            'officialFp32Allowlist': list(allowlist), 'officialFp32ActualParameterNames': fp32,
            'officialFp32ParameterCount': len(fp32), 'bf16ParameterCount': len(bf16),
            'modelClass': type(model).__module__+'.'+type(model).__qualname__, 'parameters': rows,
            'parameterCount': len(rows), 'weightIdentityStatus': 'directory_bound_values_unverified'}

    @contextmanager
    def install(self, predictor_module, generation_class):
        capture, patches = self, []
        def patch(target, name, replacement):
            patches.append((target, name, getattr(target, name)))
            setattr(target, name, replacement)
        original_build = predictor_module.DocVLMLocalPredictor._build
        class Model:
            def __init__(self, original): self.original = original
            def __getattr__(self, name): return getattr(self.original, name)
            def generate(self, inputs, **kwargs):
                capture.require_active()
                row = {'sequence': len(capture.generations)+1, 'status': 'running',
                       'kwargs': copy.deepcopy(kwargs), 'input': capture.archive(inputs)}
                capture.generations.append(row)
                places = capture.cuda_tensors(inputs)
                if not places or any('gpu:0' not in place for place in places):
                    raise ValueError('Actual native generation inputs must be CUDA0 tensors')
                if capture.attempt is not None:
                    raise ValueError('Nested native generation capture is unsupported')
                capture.attempt = row
                try:
                    result = self.original.generate(inputs, **kwargs)
                    row['output'] = capture.archive(result)
                    places = capture.cuda_tensors(result)
                    if not places or any('gpu:0' not in place for place in places):
                        raise ValueError('Actual native generation outputs must be CUDA0 tensors')
                    greedy = row.get('actualResolvedGreedy')
                    if not greedy or not isinstance(result, tuple) or len(result) != 2 or not isinstance(result[0], capture.paddle.Tensor):
                        raise ValueError('Actual native greedy IDs/scores boundary unavailable')
                    requested_cap = kwargs.get('max_new_tokens')
                    if type(requested_cap) is not int or requested_cap != greedy['effectiveMaxNewTokens']:
                        raise ValueError('Actual resolved generation budget differs from explicit request')
                    rows = result[0].numpy().tolist()
                    terminals = []
                    for ids in rows:
                        if len(ids) > greedy['effectiveMaxNewTokens']:
                            raise ValueError('Actual generated IDs exceed their resolved cap')
                        if greedy['truncInput'] is not True:
                            raise ValueError('Unknown generated versus input-token boundary')
                        eos = next((n for n, token in enumerate(ids) if token in greedy['eosIds']), None)
                        terminals.append('eos_before_cap' if eos is not None and eos+1 < greedy['effectiveMaxNewTokens']
                            and all(token == greedy['padId'] for token in ids[eos+1:]) else 'unknown_or_budget_boundary')
                    row['terminalStatus'] = 'eos_before_cap' if terminals and all(value == 'eos_before_cap' for value in terminals) else 'unknown_or_budget_boundary'
                    if row['terminalStatus'] != 'eos_before_cap':
                        raise ValueError('Native generation has no verified EOS before its actual cap')
                    row['status'] = 'completed'
                    return result
                finally:
                    capture.attempt = None
        def build(predictor, *args, **kwargs):
            model, processor = original_build(predictor, *args, **kwargs)
            capture.parameters(model, predictor)
            return Model(model), processor
        original_greedy = generation_class.greedy_search
        signature = inspect.signature(original_greedy)
        def greedy(model, *args, **kwargs):
            capture.require_active()
            if capture.attempt is None:
                raise ValueError('Greedy generation has no request-owned attempt')
            bound = signature.bind(model, *args, **kwargs); bound.apply_defaults()
            values = bound.arguments
            eos = values['eos_token_id']
            eos = [eos] if type(eos) is int else eos
            if not isinstance(eos, (list, tuple)) or not eos or any(type(value) is not int for value in eos):
                raise ValueError('Actual EOS identities unavailable')
            evidence = {'effectiveMaxNewTokens': int(values['max_length'])-int(values['input_ids'].shape[-1]),
                'eosIds': list(eos), 'padId': values['pad_token_id'], 'truncInput': values['trunc_input']}
            if evidence['effectiveMaxNewTokens'] <= 0 or type(evidence['padId']) is not int:
                raise ValueError('Actual generation budget or pad identity unavailable')
            capture.attempt['actualResolvedGreedy'] = evidence
            return original_greedy(model, *args, **kwargs)
        original_create = self.paddle.inference.create_predictor
        def create(config, *args, **kwargs):
            if not config.use_gpu() or config.gpu_device_id() != 0:
                raise ValueError('Actual layout predictor must use CUDA0; CPU fallback is forbidden')
            paths = {'program': config.prog_file(), 'parameters': config.params_file()}
            if capture.layout_directory is not None and any(not path or not Path(path).resolve().is_relative_to(
                    Path(capture.layout_directory).resolve()) for path in paths.values()):
                raise ValueError('Actual layout assets differ from configured model directory')
            original = original_create(config, *args, **kwargs)
            session = {'session': len(capture.runtime['layoutSessions'])+1, 'useGpu': True,
                       'device': 'gpu:0', 'precision': 'official_static_auxiliary_state', 'modelPaths': paths,
                       'inputs': list(original.get_input_names()), 'outputs': list(original.get_output_names())}
            capture.runtime['layoutSessions'].append(session)
            class Static:
                def __init__(self): self.inputs = {}
                def __getattr__(self, name): return getattr(original, name)
                def get_input_handle(self, name):
                    handle, proxy = original.get_input_handle(name), self
                    class Input:
                        def __getattr__(self, key): return getattr(handle, key)
                        def copy_from_cpu(self, array):
                            proxy.inputs[name] = array.copy()
                            return handle.copy_from_cpu(array)
                    return Input()
                def run(self, *args, **kwargs):
                    capture.require_active()
                    row = {'session': session['session'], 'status': 'running',
                           'inputs': {name: capture.archive(value) for name, value in self.inputs.items()}, 'outputs': {}}
                    capture.layouts.append(row)
                    result = original.run(*args, **kwargs)
                    row['outputs'] = {name: capture.archive(original.get_output_handle(name).copy_to_cpu()) for name in session['outputs']}
                    if set(row['inputs']) != set(session['inputs']):
                        raise ValueError('Complete static model inputs were not captured')
                    row['status'] = 'completed'
                    return result
            return Static()
        try:
            patch(predictor_module.DocVLMLocalPredictor, '_build', build)
            patch(generation_class, 'greedy_search', greedy)
            patch(self.paddle.inference, 'create_predictor', create)
            yield self
        finally:
            for target, name, original in reversed(patches):
                setattr(target, name, original)

    def run(self, callback):
        if self.active:
            raise ValueError('Native request captures must be serialized')
        self.assets, self.generations, self.layouts, self.byte_count = {}, [], [], 0
        self.active = True
        try:
            result = callback()
            if (not self.generations or not self.layouts or self.runtime['mainParameters'] is None
                    or any(row['status'] != 'completed' for row in self.generations+self.layouts)):
                raise ValueError('Native output observation is incomplete')
            return result, self.manifest('complete_native_arrays_captured'), self.assets.copy()
        except Exception as error:
            manifest = self.manifest('failed_native_capture')
            manifest.update(errorType=type(error).__name__, reason=str(error))
            raise NativePaddleCaptureError(str(error), manifest, self.assets.copy()) from error
        finally:
            self.active = False

    def manifest(self, status):
        return {'schema': 'consense.paddle-native-output-capture.v1', 'captureStatus': status,
                'generationAttempts': copy.deepcopy(self.generations), 'layoutRuns': copy.deepcopy(self.layouts),
                'runtime': copy.deepcopy(self.runtime), 'sourceQualityAccepted': False,
                'limitations': ['Neural output capture is not semantic accuracy or complete source coverage',
                    'Directory/parameter precision observation does not verify every loaded weight value']}
