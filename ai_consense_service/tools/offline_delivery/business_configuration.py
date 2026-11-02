"""Source-side business route config and independent GPU readback."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat

from fixed_gpu_client import Client
from request_stage import encoded, verify_response


NAMES = ('CONSENSE_BUSINESS_MODEL', 'CONSENSE_BUSINESS_TOKEN_FILE',
         'CONSENSE_BUSINESS_PROFILE_FILE', 'CONSENSE_BUSINESS_TIMEOUT_SECONDS',
         'CONSENSE_FIXED_GPU_CONFIG_SHA256')


def _private_token(path):
    file = Path(path)
    if not file.is_absolute() or file.is_symlink():
        raise ValueError('business token file must be absolute and private')
    fd = os.open(file, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                or before.st_uid != os.geteuid() or before.st_gid != os.getegid()
                or stat.S_IMODE(before.st_mode) != 0o600 or not 16 <= before.st_size <= 512):
            raise ValueError('business token file owner, mode or size differs')
        raw = os.read(fd, 513)
        after = os.fstat(fd)
        visible = file.lstat()
        identity = lambda item: (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        if (len(raw) != before.st_size or identity(before) != identity(after)
                or identity(before) != identity(visible)
                or re.fullmatch(rb'[A-Za-z0-9._~-]{16,512}', raw) is None):
            raise ValueError('business token changed or contains invalid bytes')
        return raw.decode('ascii')
    finally:
        os.close(fd)


def _profile_from_bytes(raw, expected_sha256):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('duplicate business profile field')
            result[key] = value
        return result

    def nonfinite(_value):
        raise ValueError('nonfinite business profile value')

    profile = json.loads(raw, object_pairs_hook=unique, parse_constant=nonfinite)
    if (not isinstance(profile, dict)
            or hashlib.sha256(encoded(profile)).hexdigest() != expected_sha256
            or profile.get('adapter') != 'consense.native-chat.v1'
            or profile.get('providerKind') not in (None, 'cpu-protocol-stub')):
        raise ValueError('business profile identity or adapter differs')
    paths = profile.get('apiPaths')
    supported = {'/v1/chat/completions', '/v1/responses', '/v1/responses/input_tokens'}
    if (not isinstance(paths, dict) or not paths or not paths.keys() <= supported
            or any(not isinstance(value, str) or not value.startswith('/')
                   or any(char in value for char in '\r\n?#\x00') for value in paths.values())
            or (profile.get('providerKind') == 'cpu-protocol-stub'
                and paths != {'/v1/chat/completions': '/v1/chat/completions'})):
        raise ValueError('business profile API paths differ')
    return profile


def _profile_file(path, expected_sha256):
    file = Path(path)
    if not file.is_absolute() or file.is_symlink():
        raise ValueError('business profile file must be absolute and regular')
    fd = os.open(file, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                or not 1 <= before.st_size <= 65536 or before.st_mode & 0o222):
            raise ValueError('business profile file must be bounded and read-only')
        raw = os.read(fd, 65537)
        after = os.fstat(fd)
        visible = file.lstat()
        identity = lambda item: (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        if len(raw) != before.st_size or identity(before) != identity(after) or identity(before) != identity(visible):
            raise ValueError('business profile file changed during read')
        return _profile_from_bytes(raw, expected_sha256)
    finally:
        os.close(fd)


def configure(environ, gpu_url, gpu_token, profiles):
    present = [bool(environ.get(name)) for name in NAMES]
    if not any(present):
        return None
    if not all(present) or 'business-llm' not in profiles:
        raise ValueError('complete pinned business profile is required')
    model = environ[NAMES[0]]
    token = _private_token(environ[NAMES[1]])
    profile = _profile_file(environ[NAMES[2]], profiles['business-llm'])
    timeout = float(environ[NAMES[3]])
    config_sha = environ[NAMES[4]]
    profile_sha = profiles['business-llm']
    if (not model or len(model) > 128 or any(char in model for char in '\r\n\x00')
            or profile.get('model') != model
            or not math.isfinite(timeout) or not 0 < timeout <= 86400
            or re.fullmatch('[a-f0-9]{64}', config_sha) is None
            or re.fullmatch('[a-f0-9]{64}', profile_sha) is None
            or token == gpu_token):
        raise ValueError('business model, timeout, pin or separate token differs')
    allowed_paths = frozenset({'/v1/models', *profile['apiPaths']})
    cpu_profile = profile if profile.get('providerKind') == 'cpu-protocol-stub' else None
    readback = Client(gpu_url, gpu_token, {'business-llm': profile_sha}, cpu_business_profile=cpu_profile)

    def verify_release(receipt, claims):
        if (not isinstance(receipt, dict) or not isinstance(claims, dict)
                or set(claims) != {'path', 'bodySha256', 'profileSha256', 'executionSha256',
                                  'model', 'operationId'}
                or claims['path'] not in allowed_paths - {'/v1/models'}
                or claims['model'] != model or claims['profileSha256'] != profile_sha
                or claims['executionSha256'] != config_sha
                or not isinstance(claims['bodySha256'], str)
                or re.fullmatch('[a-f0-9]{64}', claims['bodySha256']) is None
                or not isinstance(claims['operationId'], str)
                or re.fullmatch('[a-f0-9]{32}', claims['operationId']) is None):
            return False
        operation = claims['operationId']
        state = readback._call('GET', '/operations/' + operation, min(timeout, 5))
        release = state.get('release')
        if (state.get('operationId') != operation or state.get('stage') != 'business-llm'
                or state.get('status') not in ('completed', 'failed')
                or state.get('configSha256') != config_sha
                or state.get('bodySha256') != claims['bodySha256']
                or state.get('sourceSha256') != hashlib.sha256(encoded({'httpPath': claims['path']})).hexdigest()
                or state.get('profileSha256') != profile_sha
                or not isinstance(state.get('bindingSha256'), str)
                or re.fullmatch('[a-f0-9]{64}', state['bindingSha256']) is None
                or not isinstance(state.get('responseSha256'), str)
                or re.fullmatch('[a-f0-9]{64}', state['responseSha256']) is None
                or not readback._release_proven('business-llm', profile_sha, release)):
            return False
        ordinary = {'operationId': operation, 'bindingSha256': state['bindingSha256'],
                    'responseSha256': state['responseSha256'], 'release': release}
        streaming = {**release, 'operationStatus': state['status'], 'operationId': operation}
        if receipt not in (ordinary, streaming):
            return False
        response = readback._call('GET', '/operations/' + operation + '/result', min(timeout, 5))
        verify_response(response, {'bindingSha256': state['bindingSha256']})
        if (hashlib.sha256(encoded(response)).hexdigest() != state['responseSha256']
                or (state['status'] == 'completed') != (200 <= response['status'] < 300)):
            return False
        return True

    return {'model': model, 'token': token, 'client': readback, 'allowedPaths': allowed_paths, 'profileSha256': profile_sha,
            'executionSha256': config_sha, 'timeout': timeout,
            'verifyRelease': verify_release}
