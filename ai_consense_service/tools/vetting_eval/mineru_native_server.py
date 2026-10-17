"""Start the official MinerU API with the selected finite BF16 runtime profile."""
import functools
import json

MAX_TOKENS = 8192
ENGINE_OPTIONS = {'dtype': 'bfloat16', 'max_model_len': MAX_TOKENS,
    'max_num_seqs': 1, 'max_num_batched_tokens': MAX_TOKENS, 'tensor_parallel_size': 1,
    'gpu_memory_utilization': 0.45, 'kv_cache_memory_bytes': 536870912,
    'enforce_eager': True, 'mm_processor_cache_gb': 0,
    'disable_log_stats': True, 'enable_prefix_caching': False}


def install_runtime_profile(model_singleton, sampling_params):
    """Keep official parsing/prompts/sampling; supply only selected runtime limits."""
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


if __name__ == '__main__':
    from mineru.model.vlm.runtime import ModelSingleton
    from mineru_vl_utils.mineru_client import DEFAULT_SAMPLING_PARAMS
    install_runtime_profile(ModelSingleton, DEFAULT_SAMPLING_PARAMS)
    print(json.dumps({'schema': 'consense.mineru-native-runtime-profile.v1',
        'precision': 'bfloat16', 'maxModelLength': MAX_TOKENS, 'maxNewTokens': MAX_TOKENS,
        'engineOptions': ENGINE_OPTIONS,
        'samplingProfiles': list(DEFAULT_SAMPLING_PARAMS),
        'modelLoaded': False, 'sourceQualityAccepted': False}), flush=True)
    from mineru.parser.api_server import main
    main()
