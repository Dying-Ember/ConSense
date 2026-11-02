"""Load the independently pinned fixed-GPU stage identities."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('GPU profile manifest has duplicate fields')
        result[key] = value
    return result


def load_profiles(path, expected_sha256):
    path = Path(path)
    if (not path.is_absolute() or path.is_symlink()
            or not isinstance(expected_sha256, str)
            or not re.fullmatch('[a-f0-9]{64}', expected_sha256)):
        raise ValueError('A pinned absolute GPU profile manifest is required')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > 8192:
            raise ValueError('GPU profile manifest is not a bounded file')
        raw = stream.read(8193)
    if len(raw) > 8192 or hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise ValueError('GPU profile manifest identity differs')
    manifest = json.loads(raw, object_pairs_hook=_unique)
    if not isinstance(manifest, dict) or set(manifest) != {'schema', 'profiles'} or manifest['schema'] != 'consense.fixed-gpu-profiles.v1':
        raise ValueError('GPU profile manifest schema differs')
    profiles = manifest['profiles']
    allowed = {'document-embedding', 'query-embedding-rerank', 'fixed-reference-query', 'business-llm', 'paddle', 'mineru'}
    required = {'document-embedding', 'query-embedding-rerank'}
    if (not isinstance(profiles, dict) or not required <= profiles.keys() or not profiles.keys() <= allowed
            or any(not isinstance(value, str) or not re.fullmatch('[a-f0-9]{64}', value) for value in profiles.values())):
        raise ValueError('GPU profile identities differ')
    return {key: value for key, value in profiles.items() if key in allowed - {'paddle', 'mineru'}}
