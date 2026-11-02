"""Derive pinned client identities from an independently trusted worker config.

The caller must protect the worker configuration and its SHA independently; this
tool does not validate the deployability or trustworthiness of stage profiles.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat

from request_stage import encoded


ALLOWED = {'document-embedding', 'query-embedding-rerank', 'fixed-reference-query', 'business-llm', 'paddle', 'mineru'}
REQUIRED = {'document-embedding', 'query-embedding-rerank'}
MAX_CONFIG = 1024 * 1024


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate configuration field')
        result[key] = value
    return result


def generate(config_path, expected_sha256, output_path):
    if not isinstance(expected_sha256, str) or not re.fullmatch('[a-f0-9]{64}', expected_sha256):
        raise ValueError('independently pinned lowercase configuration SHA required')
    config_path, output_path = Path(config_path), Path(output_path)
    if not output_path.is_absolute():
        raise ValueError('absolute new output path required')
    if config_path.is_symlink():
        raise ValueError('configuration must be a regular file')
    fd = os.open(config_path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_CONFIG:
            raise ValueError('configuration must be a bounded regular file')
        raw = stream.read(MAX_CONFIG + 1)
    if len(raw) > MAX_CONFIG or hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise ValueError('configuration identity differs')
    def invalid_constant(_value):
        raise ValueError('nonfinite configuration value')
    config = json.loads(raw, object_pairs_hook=_unique, parse_constant=invalid_constant)
    stages = config.get('stages') if isinstance(config, dict) else None
    if not isinstance(stages, dict) or not REQUIRED <= stages.keys() or not stages.keys() <= ALLOWED:
        raise ValueError('configured stages differ')
    profiles = {}
    for name, stage in stages.items():
        if (not isinstance(stage, dict) or stage.get('mode') not in ('job', 'session', 'model-session')
                or not isinstance(stage.get('profile'), dict) or not stage['profile']):
            raise ValueError('invalid stage mode or profile')
        profiles[name] = hashlib.sha256(encoded(stage['profile'])).hexdigest()
    manifest = encoded({'schema': 'consense.fixed-gpu-profiles.v1', 'profiles': profiles})
    fd = os.open(output_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(manifest)
        stream.flush()
        os.fsync(stream.fileno())
    fd = os.open(output_path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size != len(manifest):
            raise ValueError('output readback differs')
        readback = stream.read(len(manifest) + 1)
    if readback != manifest:
        raise ValueError('output readback differs')
    return hashlib.sha256(readback).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--config-sha256', required=True, help='independently trusted SHA-256 of configuration bytes')
    parser.add_argument('--output', required=True, help='absolute path for a new manifest')
    args = parser.parse_args()
    print(generate(args.config, args.config_sha256, args.output))


if __name__ == '__main__':
    main()
