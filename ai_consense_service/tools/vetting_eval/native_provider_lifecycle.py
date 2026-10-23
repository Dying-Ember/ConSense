"""Bounded, asset-pinned native API ownership. Availability is not model readiness."""
from contextlib import contextmanager
import hashlib
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit
import uuid


class ProviderLifecycleError(RuntimeError):
    pass


def _require(condition, reason):
    if not condition:
        raise ValueError(reason)


def _finite(value, name, maximum=86400):
    _require(not isinstance(value, bool) and isinstance(value, (int, float))
             and math.isfinite(value) and 0 < value <= maximum, name + ' must be positive finite and bounded')
    return float(value)


def _sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            digest.update(block)
    return digest.hexdigest()


def _bytes(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


def _watch_cancellation(cancelled, cancel_owned):
    if cancelled is None:
        return lambda: None
    finished = threading.Event()
    def observe():
        while not finished.is_set():
            if cancelled():
                try: cancel_owned()
                except OSError: pass  # The owner's final release readback remains authoritative.
                return
            finished.wait(.05)
    watcher = threading.Thread(target=observe, name='consense-owned-ocr-cancellation', daemon=True)
    watcher.start()
    def release():
        finished.set(); watcher.join()
    return release


def _save(path, value):
    with path.open('xb') as stream:
        stream.write(_bytes(value)); stream.flush(); os.fsync(stream.fileno())
    return {'path': str(path), 'sha256': _sha(path)}


def validate_provider(provider):
    """Validate declared command/assets without starting a provider or contacting an API."""
    url = urlsplit(provider.get('baseUrl', ''))
    _require(url.scheme in ('http', 'https') and url.hostname and not any(
        (url.username, url.password, url.query, url.fragment)), 'explicit literal-IP provider URL required')
    address = ipaddress.ip_address(url.hostname)
    identity = provider.get('identity', {})
    _require(all(isinstance(identity.get(key), str) and identity[key].strip()
                 and identity[key].lower() not in ('unknown', 'latest') for key in ('parserVersion', 'engine')),
             'exact parser version and engine declaration required')
    models = identity.get('models')
    _require(isinstance(models, list) and models and all(isinstance(model, dict)
        and isinstance(model.get('modelId'), str) and model['modelId'].strip()
        and (re.fullmatch('[a-fA-F0-9]{64}', model.get('sha256', '')) or
             re.fullmatch('[a-fA-F0-9]{40,64}', model.get('revision', ''))) for model in models),
        'immutable model SHA or revision identity required')
    _require(isinstance(identity.get('config'), dict), 'resolved parser configuration required')
    config_sha = hashlib.sha256(_bytes(identity['config'])).hexdigest()
    _require(identity.get('configSha256') in (None, config_sha), 'resolved configuration SHA differs')
    if provider.get('ownedJob') is not None:
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'offline_delivery'))
        from native_job_provider import configuration
        configuration(provider)
        return provider
    boot = provider.get('ownedBoot')
    if boot is None:
        return provider
    _require(isinstance(boot, dict) and address.is_loopback, 'owned provider must use a loopback endpoint')
    _require(boot.get('precision') in ('bf16', 'bfloat16'), 'explicit BF16 native boot declaration required')
    _finite(boot.get('readyTimeoutSeconds'), 'readyTimeoutSeconds')
    _finite(boot.get('cleanupTimeoutSeconds'), 'cleanupTimeoutSeconds', 2)
    command = boot.get('command')
    _require(isinstance(command, list) and command and all(isinstance(arg, str) and arg and '\x00' not in arg for arg in command),
             'exact argument-array command required')
    executable = Path(command[0])
    _require(executable.is_absolute() and executable.is_file() and executable.suffix.lower() not in ('.cmd', '.bat'),
             'native absolute executable required; no shell or WSL bridge')
    _require(executable.name.lower() not in ('wsl.exe', 'wsl', 'cmd.exe', 'powershell.exe', 'pwsh.exe', 'sh', 'bash'),
             'provider must run natively within its owned OS boundary')
    cwd = Path(boot.get('cwd', ''))
    _require(cwd.is_absolute() and cwd.is_dir(), 'absolute existing native working directory required')
    env = boot.get('env')
    _require(isinstance(env, dict) and all(isinstance(key, str) and key and '=' not in key and '\x00' not in key
             and isinstance(value, str) and '\x00' not in value for key, value in env.items()), 'explicit complete native environment required')
    _require(isinstance(boot.get('readyPath'), str) and boot['readyPath'].startswith('/')
             and not any(char in boot['readyPath'] for char in ('?', '#', '\r', '\n')), 'literal bounded readiness path required')
    assets = boot.get('runtimeAssets')
    _require(isinstance(assets, list) and assets, 'pinned runtime assets required')
    paths = set()
    for asset in assets:
        path = Path(asset.get('path', ''))
        _require(path.is_absolute() and path.is_file() and str(path.resolve()) not in paths, 'distinct absolute runtime asset required')
        _require(isinstance(asset.get('byteCount'), int) and asset['byteCount'] > 0
                 and asset['byteCount'] == path.stat().st_size and re.fullmatch('[a-f0-9]{64}', asset.get('sha256', ''))
                 and _sha(path) == asset['sha256'], 'runtime asset byte count or SHA changed')
        _require(asset.get('kind') in ('executable', 'runtime', 'config', 'model'), 'runtime asset kind required')
        paths.add(str(path.resolve()))
    _require(str(executable.resolve()) in paths, 'native executable must be SHA pinned')
    model_paths = {str(Path(asset['path']).resolve()) for asset in assets if asset['kind'] == 'model'}
    _require(all(isinstance(model.get('assetPaths'), list) and model['assetPaths'] and all(
        str(Path(path).resolve()) in model_paths for path in model['assetPaths']) for model in models),
        'each owned model identity must bind declared pinned model assets')
    config_paths = {str(Path(asset['path']).resolve()) for asset in assets if asset['kind'] == 'config'}
    supplied = command[1:] + list(env.values())
    _require(all(any(path == arg or path in arg for arg in supplied) for path in config_paths),
             'pinned parser configuration asset must be referenced by command or environment')
    return provider


class _WindowsTree:
    """Assign a suspended native process before it can create any descendants."""
    def __init__(self, boot, stdout, stderr):
        import ctypes as c
        from ctypes import wintypes as w
        import msvcrt
        self.c, self.w = c, w
        kernel = self.kernel = c.WinDLL('kernel32', use_last_error=True)
        class Startup(c.Structure):
            _fields_ = [('cb', w.DWORD), ('reserved', w.LPWSTR), ('desktop', w.LPWSTR), ('title', w.LPWSTR),
                ('x', w.DWORD), ('y', w.DWORD), ('cx', w.DWORD), ('cy', w.DWORD), ('xc', w.DWORD), ('yc', w.DWORD),
                ('fill', w.DWORD), ('flags', w.DWORD), ('show', w.WORD), ('reserved2size', w.WORD),
                ('reserved2', c.POINTER(c.c_byte)), ('stdin', w.HANDLE), ('stdout', w.HANDLE), ('stderr', w.HANDLE)]
        class StartupEx(c.Structure):
            _fields_ = [('startup', Startup), ('attributes', c.c_void_p)]
        class ProcessInfo(c.Structure):
            _fields_ = [('process', w.HANDLE), ('thread', w.HANDLE), ('pid', w.DWORD), ('tid', w.DWORD)]
        class BasicLimit(c.Structure):
            _fields_ = [('processTime', c.c_longlong), ('jobTime', c.c_longlong), ('flags', w.DWORD),
                ('minWorking', c.c_size_t), ('maxWorking', c.c_size_t), ('activeLimit', w.DWORD),
                ('affinity', c.c_size_t), ('priority', w.DWORD), ('scheduling', w.DWORD)]
        class Io(c.Structure):
            _fields_ = [(name, c.c_ulonglong) for name in ('readOps','writeOps','otherOps','readBytes','writeBytes','otherBytes')]
        class Extended(c.Structure):
            _fields_ = [('basic', BasicLimit), ('io', Io), ('processMem', c.c_size_t), ('jobMem', c.c_size_t),
                ('peakProcessMem', c.c_size_t), ('peakJobMem', c.c_size_t)]
        signatures = {'CreateJobObjectW': ([c.c_void_p, w.LPCWSTR], w.HANDLE),
            'SetInformationJobObject': ([w.HANDLE, c.c_int, c.c_void_p, w.DWORD], w.BOOL),
            'AssignProcessToJobObject': ([w.HANDLE, w.HANDLE], w.BOOL),
            'TerminateJobObject': ([w.HANDLE, w.UINT], w.BOOL), 'CloseHandle': ([w.HANDLE], w.BOOL),
            'ResumeThread': ([w.HANDLE], w.DWORD), 'TerminateProcess': ([w.HANDLE, w.UINT], w.BOOL),
            'QueryInformationJobObject': ([w.HANDLE, c.c_int, c.c_void_p, w.DWORD, c.c_void_p], w.BOOL),
            'GetExitCodeProcess': ([w.HANDLE, c.POINTER(w.DWORD)], w.BOOL),
            'InitializeProcThreadAttributeList': ([c.c_void_p, w.DWORD, w.DWORD, c.POINTER(c.c_size_t)], w.BOOL),
            'UpdateProcThreadAttribute': ([c.c_void_p, w.DWORD, c.c_size_t, c.c_void_p, c.c_size_t, c.c_void_p, c.c_void_p], w.BOOL),
            'DeleteProcThreadAttributeList': ([c.c_void_p], None),
            'CreateProcessW': ([w.LPCWSTR, w.LPWSTR, c.c_void_p, c.c_void_p, w.BOOL, w.DWORD, c.c_void_p, w.LPCWSTR,
                                c.POINTER(StartupEx), c.POINTER(ProcessInfo)], w.BOOL)}
        for name, (args, result) in signatures.items():
            getattr(kernel, name).argtypes = args; getattr(kernel, name).restype = result
        def check(ok):
            if not ok:
                raise c.WinError(c.get_last_error())
        self.check = check
        self.job = kernel.CreateJobObjectW(None, None); check(self.job)
        self.process = None
        limit = Extended(); limit.basic.flags = 0x2000  # KILL_ON_JOB_CLOSE, no breakaway permission.
        check(kernel.SetInformationJobObject(self.job, 9, c.byref(limit), c.sizeof(limit)))
        null = open(os.devnull, 'rb')
        handles = (w.HANDLE * 3)(*[msvcrt.get_osfhandle(stream.fileno()) for stream in (null, stdout, stderr)])
        size = c.c_size_t(); kernel.InitializeProcThreadAttributeList(None, 1, 0, c.byref(size))
        buffer = c.create_string_buffer(size.value)
        attributes = c.cast(buffer, c.c_void_p)
        check(kernel.InitializeProcThreadAttributeList(attributes, 1, 0, c.byref(size)))
        info = ProcessInfo()
        try:
            for handle in handles: os.set_handle_inheritable(handle, True)
            check(kernel.UpdateProcThreadAttribute(attributes, 0, 0x20002, handles, c.sizeof(handles), None, None))
            startup = StartupEx(); startup.startup.cb = c.sizeof(startup); startup.attributes = attributes
            startup.startup.flags = 0x100; startup.startup.stdin, startup.startup.stdout, startup.startup.stderr = handles
            environment = c.create_unicode_buffer(''.join(key+'='+value+'\0' for key, value in sorted(boot['env'].items()))+'\0')
            check(kernel.CreateProcessW(boot['command'][0], c.create_unicode_buffer(subprocess.list2cmdline(boot['command'])),
                None, None, True, 0x4 | 0x400 | 0x80000 | 0x8000000, environment, boot['cwd'], c.byref(startup), c.byref(info)))
            self.process = info.process; self.pid = info.pid
            check(kernel.AssignProcessToJobObject(self.job, self.process))
            if kernel.ResumeThread(info.thread) == 0xFFFFFFFF: check(False)
        except BaseException:
            if info.process: kernel.TerminateProcess(info.process, 125)
            if self.process: kernel.CloseHandle(self.process)
            kernel.CloseHandle(self.job)
            raise
        finally:
            if info.thread: kernel.CloseHandle(info.thread)
            kernel.DeleteProcThreadAttributeList(attributes)
            for handle in handles: os.set_handle_inheritable(handle, False)
            null.close()

    def active(self):
        c = self.c
        class Accounting(c.Structure):
            _fields_ = [('user', c.c_longlong), ('kernel', c.c_longlong), ('periodUser', c.c_longlong), ('periodKernel', c.c_longlong),
                ('faults', self.w.DWORD), ('total', self.w.DWORD), ('active', self.w.DWORD), ('terminated', self.w.DWORD)]
        row = Accounting(); self.check(self.kernel.QueryInformationJobObject(self.job, 1, c.byref(row), c.sizeof(row), None))
        return row.active

    def stop(self):
        self.check(self.kernel.TerminateJobObject(self.job, 124))

    def close(self):
        self.kernel.CloseHandle(self.process); self.kernel.CloseHandle(self.job)


class _LinuxTree:
    def __init__(self, boot, stdout, stderr, remaining):
        import select
        root = Path(boot.get('cgroupRoot', ''))
        _require(root.is_absolute() and (root / 'cgroup.controllers').is_file(), 'delegated cgroup v2 root required for native Linux tree ownership')
        _require(str(Path(sys.executable).resolve()) in {str(Path(asset['path']).resolve()) for asset in boot['runtimeAssets']},
                 'native Linux admission bootstrap Python executable must be SHA pinned')
        self.group = root / ('consense-ocr-' + uuid.uuid4().hex)
        self.group.mkdir()
        if not (self.group / 'cgroup.kill').is_file():
            self.group.rmdir()
            raise ValueError('cgroup.kill required for complete subtree release')
        read_fd, write_fd = os.pipe()
        wrapper = '''import os,sys
with open(sys.argv[1], "w") as stream:
    stream.write(str(os.getpid()))
    stream.flush()
expected = "0::/" + os.path.relpath(os.path.dirname(sys.argv[1]), "/sys/fs/cgroup")
with open("/proc/self/cgroup") as stream:
    if expected not in stream.read().splitlines():
        raise RuntimeError("Native Linux cgroup admission readback differs")
with open(sys.argv[1]) as stream:
    if str(os.getpid()) not in stream.read().split():
        raise RuntimeError("Native Linux cgroup does not contain admitted process")
os.write(int(sys.argv[2]), b"1")
os.close(int(sys.argv[2]))
os.execvpe(sys.argv[3], sys.argv[3:], os.environ)
'''
        try:
            self.process = subprocess.Popen([sys.executable, '-c', wrapper, str(self.group / 'cgroup.procs'), str(write_fd), *boot['command']],
                cwd=boot['cwd'], env=boot['env'], stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr,
                pass_fds=(write_fd,), start_new_session=True)
            self.pid = self.process.pid
            os.close(write_fd); write_fd = None
            if not select.select([read_fd], [], [], min(2, remaining))[0] or os.read(read_fd, 1) != b'1':
                raise ProviderLifecycleError('Owned Linux process did not confirm cgroup admission before native exec')
        except BaseException as admission_error:
            if hasattr(self, 'process'):
                cleanup_deadline = time.monotonic() + 2
                try:
                    self.stop()
                finally:
                    self.process.kill()
                while time.monotonic() < cleanup_deadline:
                    if self.active() == 0 and self.process.poll() is not None:
                        break
                    time.sleep(.01)
                else:
                    raise ProviderLifecycleError('Failed Linux admission subtree release is unconfirmed: '
                        + str(admission_error)) from admission_error
            self.group.rmdir(); raise
        finally:
            os.close(read_fd)
            if write_fd is not None: os.close(write_fd)

    def active(self):
        values = dict(row.split() for row in (self.group / 'cgroup.events').read_text().splitlines())
        return int(values['populated'])

    def stop(self):
        (self.group / 'cgroup.kill').write_text('1')

    def close(self):
        # Cleanup's separately reported deadline already covered tree exit.
        # Never add another blocking wait or delete a populated cgroup.
        if self.process.poll() is None:
            self.process.kill()
            raise ProviderLifecycleError('Native Linux process exit remains unconfirmed')
        self.process.wait(timeout=0)
        self.group.rmdir()


def _wire_ready(provider, timeout):
    url = urlsplit(provider['baseUrl'])
    connection = (http.client.HTTPSConnection if url.scheme == 'https' else http.client.HTTPConnection)(url.hostname, url.port, timeout=timeout)
    try:
        connection.request('GET', provider['ownedBoot']['readyPath'])
        response = connection.getresponse()
        return 200 <= response.status < 300
    finally:
        connection.close()


@contextmanager
def owned_native_provider(provider, remaining_seconds, receipt_dir, *, cancelled=None):
    """Yield mutable lifecycle metadata. Total provider budget + at most 2s cleanup.

    No ownedBoot means an explicitly existing/remote provider; no launch or stop
    occurs and single-GPU handoff stays unverified. On owned exit, failure to read
    the complete tree empty raises and prevents the caller entering a next stage.
    """
    budget = _finite(remaining_seconds, 'remaining_seconds')
    deadline = time.monotonic() + budget
    if cancelled is not None and cancelled():
        raise ProviderLifecycleError('Caller cancelled before owned provider admission')
    validate_provider(provider)
    if provider.get('ownedJob') is not None:
        from native_job_provider import owned_gpu_provider
        with owned_gpu_provider(provider, deadline-time.monotonic(), receipt_dir, cancelled=cancelled) as lifecycle:
            yield lifecycle
        return
    directory = Path(receipt_dir).resolve()
    _require(not directory.exists(), 'fresh lifecycle receipt directory required')
    directory.mkdir(parents=True)
    lifecycle = {'schema': 'consense.native-provider-lifecycle.v1', 'startedAtNs': time.time_ns(),
        'providerDeclarationSha256': hashlib.sha256(_bytes(provider)).hexdigest(),
        'budgetSeconds': budget, 'modelReady': False, 'targetAccepted': False}
    boot = provider.get('ownedBoot')
    if boot is None:
        lifecycle['mode'] = 'remote_handoff_unverified'
        _save(directory / 'start.json', lifecycle)
        try:
            yield lifecycle
        finally:
            lifecycle['exitReceipt'] = {'status': 'remote_handoff_unverified', 'modelReady': False, 'targetAccepted': False}
            lifecycle['exitReceiptRef'] = _save(directory / 'exit.json', lifecycle['exitReceipt'])
        return
    url = urlsplit(provider['baseUrl'])
    with socket.socket(socket.AF_INET6 if ':' in url.hostname else socket.AF_INET) as probe:
        probe.settimeout(min(.25, max(.001, deadline-time.monotonic())))
        if probe.connect_ex((url.hostname, url.port or (443 if url.scheme == 'https' else 80))) == 0:
            raise ProviderLifecycleError('Native provider endpoint is occupied; existing services are never adopted or killed')
    tree = None; timer = None; expired = threading.Event(); primary_error = None
    stop_watching = lambda: None
    with (directory / 'stdout.log').open('xb') as stdout, (directory / 'stderr.log').open('xb') as stderr:
        try:
            if time.monotonic() >= deadline:
                raise TimeoutError('Native provider total deadline elapsed before launch')
            tree = _WindowsTree(boot, stdout, stderr) if os.name == 'nt' else _LinuxTree(boot, stdout, stderr, deadline-time.monotonic())
            lifecycle.update(mode='owned_native', pid=tree.pid,
                ownership='windows_job_kill_on_close' if os.name == 'nt' else 'delegated_cgroup_v2')
            _save(directory / 'start.json', lifecycle)
            def expire():
                expired.set()
                try: tree.stop()
                except OSError: pass  # Main-thread exit readback remains authoritative.
            timer = threading.Timer(max(.001, deadline-time.monotonic()), expire); timer.daemon = True; timer.start()
            stop_watching = _watch_cancellation(cancelled, tree.stop)
            ready_deadline = min(deadline, time.monotonic()+boot['readyTimeoutSeconds'])
            while time.monotonic() < ready_deadline:
                if tree.active() == 0:
                    raise ProviderLifecycleError('Owned native provider exited before wire readiness')
                try:
                    if _wire_ready(provider, min(.25, max(.001, ready_deadline-time.monotonic()))): break
                except (OSError, http.client.HTTPException): pass
                time.sleep(min(.05, max(0, ready_deadline-time.monotonic())))
            else:
                raise TimeoutError('Owned native provider wire readiness deadline elapsed')
            lifecycle['wireReady'] = {'status': 'wire_ready_model_readiness_unverified', 'observedAtNs': time.time_ns(),
                                      'meaning': 'Only configured loopback API availability; no runtime/model identity or GPU readiness proof'}
            _save(directory / 'wire-ready.json', lifecycle['wireReady'])
            yield lifecycle
            if cancelled is not None and cancelled():
                raise ProviderLifecycleError('Caller cancelled the owned provider')
            if expired.is_set() or time.monotonic() >= deadline:
                raise TimeoutError('Native provider total deadline elapsed')
        except BaseException as error:
            primary_error = {'type': type(error).__name__, 'message': str(error)}
            raise
        finally:
            stop_watching()
            if timer is not None: timer.cancel(); timer.join()
            if tree is not None:
                status, active, failure = 'release_unknown', None, None
                try:
                    tree.stop()
                    cleanup_deadline = time.monotonic()+boot['cleanupTimeoutSeconds']
                    while time.monotonic() < cleanup_deadline:
                        active = tree.active()
                        if active == 0 and (os.name == 'nt' or tree.process.poll() is not None):
                            status = 'released'; break
                        time.sleep(min(.02, max(0, cleanup_deadline-time.monotonic())))
                except Exception as error: failure = type(error).__name__ + ': ' + str(error)
                try:
                    tree.close()
                except Exception as error:
                    status, failure = 'release_unknown', type(error).__name__ + ': ' + str(error)
                stdout.flush(); stderr.flush(); os.fsync(stdout.fileno()); os.fsync(stderr.fileno())
                lifecycle['exitReceipt'] = {'status': status, 'activeProcesses': active if os.name == 'nt' or active == 0 else None,
                        'cgroupPopulated': active if os.name != 'nt' else None, 'finishedAtNs': time.time_ns(),
                        'pid': tree.pid, 'ownership': lifecycle['ownership'], 'deadlineExpired': expired.is_set(),
                        'cleanupBudgetSeconds': boot['cleanupTimeoutSeconds'], 'error': failure, 'primaryError': primary_error,
                        'stdoutSha256': _sha(directory / 'stdout.log'), 'stderrSha256': _sha(directory / 'stderr.log'),
                        'modelReady': False, 'targetAccepted': False}
                lifecycle['exitReceiptRef'] = _save(directory / 'exit.json', lifecycle['exitReceipt'])
                if status != 'released':
                    raise ProviderLifecycleError('Owned native tree release not proven; next provider must not execute'
                        + ('; primary error: ' + primary_error['type'] + ': ' + primary_error['message'] if primary_error else '')
                        + ('; cleanup error: ' + failure if failure else ''))
