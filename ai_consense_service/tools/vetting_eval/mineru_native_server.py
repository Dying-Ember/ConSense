"""Isolated MinerU native API candidate with the recovered R153 LoRA."""
import functools
import hashlib
import json
import os
from pathlib import Path

MAX_TOKENS = 8192
ENGINE_OPTIONS = {'dtype': 'bfloat16', 'max_model_len': MAX_TOKENS,
    'max_num_seqs': 1, 'max_num_batched_tokens': MAX_TOKENS, 'tensor_parallel_size': 1,
    'gpu_memory_utilization': 0.45, 'kv_cache_memory_bytes': 536870912,
    'enforce_eager': True, 'mm_processor_cache_gb': 0,
    'disable_log_stats': True, 'enable_prefix_caching': False,
    'enable_lora': True, 'max_lora_rank': 8}
ADAPTER_HASHES = {
    'adapter_config.json': 'b06eba9565ef62ee2b01c9378b354fbb4f315f878953fe413182c0fd2e929314',
    'adapter_model.safetensors': '8aeb6d6086191eee85f062be1edb0559e1e5a305ec1ce9a78ba3e2dbe8d96168',
}


def check_adapter(path):
    path = Path(path)
    if not path.is_absolute() or not path.is_dir():
        raise ValueError('An absolute existing R153 adapter directory is required')
    for name, expected in ADAPTER_HASHES.items():
        with (path / name).open('rb') as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        if digest != expected:
            raise ValueError('R153 adapter identity differs: ' + name)
    return path


def install_runtime_profile(model_singleton, sampling_params):
    create_model = model_singleton._create_model

    @functools.wraps(create_model)
    def bounded_model(self, backend, *args, **kwargs):
        if backend not in ('vllm-engine', 'vllm-async-engine'):
            raise ValueError('The selected complete MinerU profile requires native vLLM')
        for key, value in ENGINE_OPTIONS.items():
            if key in kwargs and kwargs[key] != value:
                raise ValueError('Conflicting selected MinerU runtime value: ' + key)
            kwargs[key] = value
        return create_model(self, backend, *args, **kwargs)

    for params in sampling_params.values():
        if params.max_new_tokens not in (None, MAX_TOKENS):
            raise ValueError('Conflicting selected MinerU generation limit')
    model_singleton._create_model = bounded_model
    for params in sampling_params.values():
        params.max_new_tokens = MAX_TOKENS


def install_adapter(engine_class, adapter_request):
    generate = engine_class.generate

    @functools.wraps(generate)
    def generate_with_adapter(self, *args, **kwargs):
        if kwargs.get('lora_request') is not None:
            raise ValueError('Unexpected competing MinerU LoRA request')
        kwargs['lora_request'] = adapter_request
        return generate(self, *args, **kwargs)

    engine_class.generate = generate_with_adapter


if __name__ == '__main__':
    adapter = check_adapter(os.environ['CONSENSE_MINERU_LORA_PATH'])
    from mineru.model.vlm.runtime import ModelSingleton
    from mineru_vl_utils.mineru_client import DEFAULT_SAMPLING_PARAMS
    from vllm.lora.request import LoRARequest
    from vllm.v1.engine.async_llm import AsyncLLM
    install_runtime_profile(ModelSingleton, DEFAULT_SAMPLING_PARAMS)
    install_adapter(AsyncLLM, LoRARequest('r153-retrained', 1, str(adapter)))
    print(json.dumps({'schema': 'consense.mineru-r153-candidate.v1',
        'adapterSha256': ADAPTER_HASHES['adapter_model.safetensors'],
        'engineOptions': ENGINE_OPTIONS, 'modelLoaded': False,
        'sourceQualityAccepted': False}), flush=True)
    from mineru.parser.api_server import main
    main()
