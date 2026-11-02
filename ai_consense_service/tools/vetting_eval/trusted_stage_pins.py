"""Derive Source's declared execution pin from independently pinned GPU files."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat

from fixed_gpu_profiles import load_profiles
from token_windows import digest


GPU_CONFIG_PATH = '/runtime/config/gpu.json'
MAX_CONFIG_BYTES = 1024 * 1024
MODEL_STAGES = {'document-embedding', 'query-embedding-rerank', 'fixed-reference-query'}


def _unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('GPU configuration has duplicate fields')
        value[key] = item
    return value


def _invalid_constant(_value):
    raise ValueError('GPU configuration has nonfinite values')


def load_trusted_stage_pins(stage, config_path, config_sha256, manifest_path, manifest_sha256):
    if stage not in MODEL_STAGES:
        raise ValueError('model stage is not supported')
    if not isinstance(config_sha256, str) or not re.fullmatch('[a-f0-9]{64}', config_sha256):
        raise ValueError('independent GPU configuration SHA is required')
    path = Path(config_path)
    if not path.is_absolute() or path.is_symlink():
        raise ValueError('absolute regular GPU configuration is required')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_CONFIG_BYTES:
            raise ValueError('GPU configuration must be a bounded regular file')
        raw = stream.read(MAX_CONFIG_BYTES + 1)
    if len(raw) > MAX_CONFIG_BYTES or hashlib.sha256(raw).hexdigest() != config_sha256:
        raise ValueError('GPU configuration identity differs')
    config = json.loads(raw, object_pairs_hook=_unique, parse_constant=_invalid_constant)
    stages = config.get('stages') if isinstance(config, dict) else None
    row = stages.get(stage) if isinstance(stages, dict) else None
    if not isinstance(row, dict) or row.get('mode') not in ('job', 'model-session'):
        raise ValueError('GPU model stage is absent or invalid')
    profile, boot = row.get('profile'), row.get('boot')
    if not isinstance(profile, dict) or not profile or not isinstance(boot, dict):
        raise ValueError('GPU model profile or boot is absent')
    if (set(boot) != {'command', 'cwd', 'cgroupRoot', 'env', 'runtimeAssets'}
            or not isinstance(boot['command'], list) or not boot['command']
            or any(not isinstance(item, str) or not item for item in boot['command'])
            or any(not isinstance(boot[key], str) or not boot[key] for key in ('cwd', 'cgroupRoot'))
            or not isinstance(boot['env'], dict)
            or any(not isinstance(key, str) or not isinstance(value, str)
                   for key, value in boot['env'].items())
            or not isinstance(boot['runtimeAssets'], list) or not boot['runtimeAssets']
            or any(not isinstance(asset, dict) or set(asset) != {'path', 'byteCount', 'sha256'}
                   or not isinstance(asset['path'], str) or not asset['path']
                   or type(asset['byteCount']) is not int or asset['byteCount'] <= 0
                   or not isinstance(asset['sha256'], str)
                   or not re.fullmatch('[a-f0-9]{64}', asset['sha256'])
                   for asset in boot['runtimeAssets'])):
        raise ValueError('GPU model boot is incomplete')
    profiles = load_profiles(manifest_path, manifest_sha256)
    profile_sha256 = digest(profile)
    if profiles.get(stage) != profile_sha256:
        raise ValueError('GPU model stage profile differs from manifest')
    execution_sha256 = digest({'schema': 'consense.model-execution-pin.v1',
                               'workerConfigSha256': config_sha256, 'stage': stage,
                               'profileSha256': profile_sha256, 'boot': boot})
    return {'profileSha256': profile_sha256, 'executionSha256': execution_sha256}
