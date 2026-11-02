"""Parse explicit project routes without opening receipts or starting services."""
import hashlib
import json
from pathlib import PurePosixPath, PureWindowsPath
import re


REGISTRY = 'CONSENSE_QDRANT_MIGRATION_REGISTRY'
LEGACY = 'CONSENSE_QDRANT_MIGRATION_RECEIPT'
HISTORY = 'CONSENSE_QDRANT_HISTORY_RECEIPT'
SCHEMA = 'consense.migration-project-registry.v1'
MODULES = frozenset(('drafting', 'vetting', 'advisory'))
SHA256 = re.compile(r'[0-9a-f]{64}\Z')


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result


def _path(value):
    if not isinstance(value, str) or not value or '\x00' in value or value != value.strip():
        raise ValueError('Invalid absolute path')
    path = PureWindowsPath(value) if re.match(r'^[A-Za-z]:', value) else PurePosixPath(value)
    if not path.is_absolute() or '..' in path.parts or (isinstance(path, PurePosixPath) and '\\' in value):
        raise ValueError('Invalid absolute path')
    return value


def _sha(value):
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        raise ValueError('Invalid SHA256')
    return value


def _key(module, project):
    if not isinstance(module, str) or module not in MODULES or not isinstance(project, str) or not project or project != project.strip() or len(project) > 200:
        raise ValueError('Invalid module or project')
    return module, project


def parse_registry(raw, expected_sha256):
    """Verify the supplied bytes against an external pin and validate all entries."""
    _sha(expected_sha256)
    if not isinstance(raw, bytes) or hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise ValueError('Registry SHA256 mismatch')
    try:
        document = json.loads(raw.decode('utf-8'), object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError('Invalid registry JSON') from error
    if not isinstance(document, dict) or set(document) != {'schema', 'entries'} or document['schema'] != SCHEMA or not isinstance(document['entries'], list):
        raise ValueError('Invalid registry schema')
    routes = {}
    for entry in document['entries']:
        if not isinstance(entry, dict):
            raise ValueError('Invalid registry entry')
        mode = entry.get('mode')
        fields = {'module', 'projectId', 'mode'}
        if mode == 'migrated':
            fields |= {'receiptPath', 'receiptSha256'}
        elif mode != 'ordinary':
            raise ValueError('Invalid registry mode')
        if set(entry) != fields:
            raise ValueError('Invalid registry fields')
        key = _key(entry['module'], entry['projectId'])
        if key in routes:
            raise ValueError('Duplicate project route')
        route = {'mode': mode}
        if mode == 'migrated':
            route.update(receiptPath=_path(entry['receiptPath']), receiptSha256=_sha(entry['receiptSha256']))
        routes[key] = route
    return routes


def select_route(environ, module, project, registry_bytes=None):
    """Select an explicit registry entry; preserve legacy or unconfigured mode."""
    key = _key(module, project)
    registry_names = (REGISTRY, REGISTRY + '_SHA256')
    legacy_names = (LEGACY, LEGACY + '_SHA256')
    history_names = (HISTORY, HISTORY + '_SHA256', HISTORY + '_PROJECT_ID')
    registry_present = any(name in environ for name in registry_names)
    legacy_present = any(environ.get(name) for name in legacy_names)
    history_present = any(name in environ for name in history_names)
    if history_present:
        if registry_present or any(name in environ for name in legacy_names):
            raise ValueError('Mixed migration configuration')
        receipt = _path(environ.get(HISTORY))
        receipt_sha = _sha(environ.get(HISTORY + '_SHA256'))
        target = _key('vetting', environ.get(HISTORY + '_PROJECT_ID'))
        if key == target:
            return {'mode': 'migrated', 'receiptPath': receipt, 'receiptSha256': receipt_sha}
        return {'mode': 'ordinary'}
    if registry_present and any(name in environ for name in legacy_names):
        raise ValueError('Mixed migration configuration')
    if registry_present:
        _path(environ.get(REGISTRY))
        routes = parse_registry(registry_bytes, environ.get(REGISTRY + '_SHA256'))
        if key not in routes:
            raise ValueError('Project route not registered for module')
        return dict(routes[key])
    if legacy_present:
        # Leave legacy configuration validation to its existing reader.
        return {'mode': 'legacy', 'receiptPath': environ.get(LEGACY), 'receiptSha256': environ.get(LEGACY + '_SHA256')}
    return {'mode': 'ordinary'}


def runtime_route(backend, key, runtime=None, *, bind=True):
    """Resolve only explicitly pinned routes and retain them for an owning source frame."""
    import os
    from migrated_qdrant_index import artifact_path, configured
    from token_windows import WindowError
    runtime = runtime or getattr(backend, '_windows_runtime', None)
    names = (REGISTRY, REGISTRY + '_SHA256', LEGACY, LEGACY + '_SHA256', 'CONSENSE_QDRANT_URL',
             HISTORY, HISTORY + '_SHA256', HISTORY + '_PROJECT_ID')
    configuration = tuple(os.environ.get(name) for name in names)
    registry_present = any(name in os.environ for name in names[:2])
    history_present = any(name in os.environ for name in names[5:])
    frame_var = getattr(getattr(runtime, 'source_registry', None), '_validation_frame', None)
    frame = frame_var.get() if frame_var is not None else None
    if history_present:
        try:
            if getattr(backend, '_migrated_index', None) is not None:
                raise ValueError('Legacy reader already initialized')
            route = select_route(os.environ, getattr(runtime, 'target_module', None), key)
            if route['mode'] == 'migrated':
                route['singleHistory'] = True
        except (ValueError, OSError, TypeError, RuntimeError) as error:
            raise WindowError('MIGRATION_ROUTE_UNAVAILABLE', 'Explicit project route is unavailable') from error
    elif not registry_present:
        route = {'mode': 'legacy' if configured(os.environ) or getattr(backend, '_migrated_index', None) is not None else 'ordinary'}
    else:
        try:
            if getattr(backend, '_migrated_index', None) is not None:
                raise ValueError('Legacy reader already initialized')
            path = artifact_path(_path(os.environ.get(REGISTRY)))
            with path.open('rb') as source:
                raw = source.read(16 * 1024 * 1024 + 1)
            if len(raw) > 16 * 1024 * 1024:
                raise ValueError('Registry exceeds size limit')
            route = select_route(os.environ, getattr(runtime, 'target_module', None), key, raw)
            route['registryConfigured'] = True
        except (ValueError, OSError, TypeError, RuntimeError) as error:
            raise WindowError('MIGRATION_ROUTE_UNAVAILABLE', 'Explicit project route is unavailable') from error
    route['configurationIdentity'] = hashlib.sha256(json.dumps(configuration, ensure_ascii=False).encode('utf-8')).hexdigest()
    route['moduleIdentity'] = getattr(runtime, 'target_module', None)
    from contextlib import ExitStack
    if bind and (registry_present or history_present and route['mode'] == 'migrated') and (not isinstance(frame, dict) or not isinstance(frame.get('leaseStack'), ExitStack)):
        raise WindowError('MIGRATION_ROUTE_FRAME_REQUIRED', 'Registry access requires an owning source frame')
    if bind and isinstance(frame, dict):
        if not isinstance(frame.get('leaseStack'), ExitStack):
            raise WindowError('MIGRATION_ROUTE_FRAME_REQUIRED', 'Route binding requires an owning source frame')
        bindings = frame.setdefault('migrationRoutes', {})
        expected = (configuration, getattr(runtime, 'target_module', None), route)
        if key in bindings:
            if bindings[key][:3] != expected:
                raise WindowError('MIGRATION_ROUTE_CHANGED', 'Project route changed during request')
            bindings[key][3]()
        else:
            credential = os.environ.get('CONSENSE_QDRANT_API_KEY')
            def check_credential():
                if os.environ.get('CONSENSE_QDRANT_API_KEY') != credential:
                    raise WindowError('MIGRATION_ROUTE_CHANGED', 'Project route changed during request')
            bindings[key] = (*expected, check_credential)
            def recheck():
                check_credential()
                current = runtime_route(backend, key, runtime, bind=False)
                now = tuple(os.environ.get(name) for name in names)
                if (now, getattr(runtime, 'target_module', None), current) != expected:
                    raise WindowError('MIGRATION_ROUTE_CHANGED', 'Project route changed during request')
            frame.setdefault('precommitChecks', []).append(recheck)
            frame['leaseStack'].callback(recheck)
    return route


def frame_reader(runtime, key):
    frame_var = getattr(getattr(runtime, 'source_registry', None), '_validation_frame', None)
    frame = frame_var.get() if frame_var is not None else None
    record = frame.get('indexMetadata', {}).get(key) if isinstance(frame, dict) else None
    return record.get('migrationReader') if isinstance(record, dict) else None


def require_writable(backend, key, runtime=None):
    from token_windows import fail
    route = runtime_route(backend, key, runtime)
    fail(route['mode'] == 'ordinary' and not route.get('registryConfigured', False),
         'MIGRATION_INDEX_WRITE_UNISOLATED',
         'Index writes require independently verified isolation from migrated targets')
    return route
