"""Run isolated Vetting and Advisory source services in one container."""
import os
from pathlib import Path
import runpy
import signal
import stat
import subprocess
import sys
import time


def configuration(environ):
    tools = Path(__file__).resolve().parent.parent
    workspace = Path(environ['CONSENSE_WORKSPACE_ROOT'])
    scope = Path(environ['CONSENSE_SL_SCOPE_MANIFEST'])
    if (not tools.is_absolute() or not (tools / 'vetting_eval/oer_unified_server.py').is_file()
            or not (tools / 'offline_delivery').is_dir()):
        raise ValueError('The external release must contain the adjacent source tools')
    if not workspace.is_absolute() or not workspace.is_dir() or not scope.is_absolute() or not scope.is_file():
        raise ValueError('Workspace and scope manifest must be existing absolute paths')
    states, ports = [], []
    for module in ('VETTING', 'ADVISORY'):
        state = Path(environ['CONSENSE_SOURCE_' + module + '_STATE'])
        port = int(environ['CONSENSE_SOURCE_' + module + '_PORT'])
        if not state.is_absolute() or not state.is_dir() or not os.access(state, os.W_OK | os.X_OK) or not 1 <= port <= 65535:
            raise ValueError('Each module requires a writable existing absolute state directory and valid port')
        states.append(state.resolve())
        ports.append(port)
    if states[0].is_relative_to(states[1]) or states[1].is_relative_to(states[0]) or ports[0] == ports[1]:
        raise ValueError('Module state directories and ports must be independent')
    return tools.resolve(), states, ports


def qdrant_credential(environ):
    url = environ.get('CONSENSE_QDRANT_URL')
    path_value = environ.get('CONSENSE_QDRANT_API_KEY_FILE')
    if not url and not path_value:
        return None
    if not url or not path_value or 'CONSENSE_QDRANT_API_KEY' in environ:
        raise ValueError('Qdrant Server requires a private key file, not a Docker environment value')
    path = Path(path_value)
    if not path.is_absolute() or path.is_symlink():
        raise ValueError('Qdrant key must be a private regular file')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                or before.st_uid != os.geteuid() or before.st_gid != os.getegid()
                or stat.S_IMODE(before.st_mode) != 0o600 or not 16 <= before.st_size <= 512):
            raise ValueError('Qdrant key must be private, owned and bounded')
        raw = os.read(fd, 513)
        after, visible = os.fstat(fd), path.lstat()
        identity = lambda row: (row.st_dev, row.st_ino, row.st_size, row.st_mtime_ns, row.st_ctime_ns)
        if (len(raw) != before.st_size or identity(before) != identity(after)
                or identity(before) != identity(visible)
                or not all(33 <= byte <= 126 for byte in raw)):
            raise ValueError('Qdrant key changed or contains invalid bytes')
        return raw.decode('ascii')
    finally:
        os.close(fd)



def create_app_with_fixed_health(factory, *args, **kwargs):
    import fixed_reference_query
    original = fixed_reference_query.from_environment
    references = []
    def capture(backend):
        reference = original(backend)
        references.append(reference)
        return reference
    fixed_reference_query.from_environment = capture
    try:
        app = factory(*args, **kwargs)
    finally:
        fixed_reference_query.from_environment = original
    if len(references) != 1:
        raise ValueError('Expected one fixed reference routing configuration')
    existing = next(route for route in app.routes if getattr(route, 'path', None) == '/health')
    old_health = existing.endpoint
    app.router.routes.remove(existing)
    def health(fixedReference: str | None = None):
        if fixedReference is None:
            return old_health()
        if references[0] is None:
            from fastapi import HTTPException
            raise HTTPException(422, 'Fixed reference is not configured')
        return references[0].health_for_manifest(fixedReference)
    app.get('/health')(health)
    return app

def main():
    tools, states, ports = configuration(os.environ)
    key = qdrant_credential(os.environ)
    children = []
    stopping = False

    def stop(_signum, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        for module, state, port in zip(('vetting', 'advisory'), states, ports):
            if stopping:
                break
            env = {**os.environ, 'CONSENSE_OER_TARGET_MODULE': module, 'CONSENSE_RETRIEVAL_DATA': str(state)}
            if key is not None:
                env['CONSENSE_QDRANT_API_KEY'] = key
            command = [sys.executable, '-u', str(Path(__file__).resolve()), '--serve',
                str(tools / 'vetting_eval/oer_unified_server.py'),
                '--host', '127.0.0.1', '--port', str(port)]
            if module == 'vetting':
                command.append('--with-ocr')
                if os.environ.get('CONSENSE_BUSINESS_MODEL'):
                    command.append('--with-business')
            children.append(subprocess.Popen(command, env=env))
        while not stopping:
            for child in children:
                code = child.poll()
                if code is not None:
                    print('Source service exited unexpectedly:', code, file=sys.stderr, flush=True)
                    return code or 1
            time.sleep(.1)
        return 0
    finally:
        for child in children:
            if child.poll() is None:
                child.terminate()
        for child in children:
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()


if __name__ == '__main__':
    if len(sys.argv) > 2 and sys.argv[1] == '--serve':
        import uvicorn.config
        # Request targets may contain source text or credentials.
        uvicorn.config.LOGGING_CONFIG['formatters']['access']['fmt'] = '%(levelprefix)s %(status_code)s'
        sys.argv = sys.argv[2:]
        sys.path.insert(0, str(Path(sys.argv[0]).resolve().parent))
        namespace = runpy.run_path(sys.argv[0], run_name='consense_source_service')
        entry = namespace['main']
        original_factory = entry.__globals__['create_unified_app']
        entry.__globals__['create_unified_app'] = lambda *args, **kwargs: create_app_with_fixed_health(original_factory, *args, **kwargs)
        entry()
    else:
        sys.exit(main())
