"""One synthetic H2 exec; the caller owns the exact scope, server and durable receipts."""
import json
import os
from pathlib import Path
import re
import struct
import time

import container_admission as admission
import h2_tcp_combined_admission as h2
import jre_probe_admission as jre

COMMAND = ['/runtime/jre/bin/java', '-Xmx128m', '-Djava.io.tmpdir=/runtime/probe',
           '--class-path', '/runtime/probe-input/h2.jar', '/runtime/probe-input/H2TcpReachabilityProbe.java']
EXPECTED = b'CLIENT_READY\nCOMMIT_START\nCOMMIT_RETURNED\nCLIENT_COMPLETE\n'
MAX_OUTPUT = 65536


def _receive(sock, size, deadline, *, allow_eof=False):
    result = bytearray()
    while len(result) < size:
        remaining = deadline - time.monotonic()
        admission.require(remaining > 0, 'exec stream deadline expired')
        sock.settimeout(min(5, remaining))
        part = sock.recv(size - len(result))
        if not part and not result and allow_eof:
            return None
        admission.require(bool(part), 'exec stream truncated')
        result.extend(part)
    return bytes(result)


def _frame(sock, deadline, *, allow_eof=False):
    header = _receive(sock, 8, deadline, allow_eof=allow_eof)
    if header is None:
        return None
    admission.require(header[0] in (1, 2) and header[1:4] == b'\0\0\0', 'exec stream frame differs')
    length = struct.unpack('>I', header[4:])[0]
    admission.require(0 < length <= MAX_OUTPUT, 'exec stream frame too large')
    return header[0], _receive(sock, length, deadline)


def _attach(daemon, exec_id, deadline):
    sock = admission._daemon_peer(daemon)
    try:
        body = b'{"Detach":false,"Tty":false}'
        request = (('POST /exec/' + exec_id + '/start HTTP/1.1\r\nHost: localhost\r\n'
                    'Connection: Upgrade\r\nUpgrade: tcp\r\nContent-Type: application/json\r\n'
                    'Content-Length: ' + str(len(body)) + '\r\n\r\n').encode('ascii') + body)
        sock.sendall(request)
        header = bytearray()
        while not header.endswith(b'\r\n\r\n'):
            admission.require(len(header) < 16384, 'exec response headers too large')
            header.extend(_receive(sock, 1, deadline))
        lines = bytes(header).split(b'\r\n')
        admission.require(lines[0] in (b'HTTP/1.1 101 UPGRADED', b'HTTP/1.1 101 Switching Protocols'),
                          'exec attach response unknown')
        return sock
    except BaseException:
        sock.close()
        raise


def _binding(daemon, exec_id, cid, server):
    result = admission._request('GET', '/exec/' + exec_id + '/json', daemon)
    admission.require(isinstance(result, dict) and result.get('ID') == exec_id and
                      result.get('ContainerID') == cid and result.get('Running') is True and
                      type(result.get('Pid')) is int and result['Pid'] > 0, 'exec running identity unknown')
    pid = result['Pid']
    start = jre._starttime(pid)
    h2._process_identity(pid)
    info = os.stat('/proc/' + str(pid) + '/exe')
    admission.require((info.st_dev, info.st_ino) == (2112, 56329), 'exec Java executable differs')
    h2._network(daemon, pid, start)
    with (Path('/proc') / str(pid) / 'cgroup').open('rb') as stream:
        client_cgroup = stream.read(16385)
    with (Path('/proc') / str(server['pid']) / 'cgroup').open('rb') as stream:
        server_cgroup = stream.read(16385)
    admission.require(0 < len(client_cgroup) <= 16384 and client_cgroup == server_cgroup and
                      jre._starttime(pid) == start and jre._starttime(server['pid']) == server['starttime'],
                      'exec cgroup or process identity differs')
    return {'execId': exec_id, 'containerId': cid, 'pid': pid, 'starttime': start}


def run(daemon, cid, server, guard, record, timeout=60):
    """One exec and one GO; unknown outcomes retain the server and never repeat SQL."""
    admission.require(type(timeout) is int and 1 <= timeout <= 60 and re.fullmatch('[a-f0-9]{64}', cid),
                      'exec arguments differ')
    deadline = time.monotonic() + timeout
    guard()
    admission.require(time.monotonic() < deadline, 'exec deadline before create')
    payload = {'AttachStdin': True, 'AttachStdout': True, 'AttachStderr': True, 'Tty': False,
               'Privileged': False, 'User': 'consense:consense', 'Cmd': COMMAND}
    print(json.dumps({'event': 'exec_create_intent', 'containerId': cid}), flush=True)
    created = admission._request('POST', '/containers/' + cid + '/exec', daemon, payload)
    exec_id = created.get('Id') if isinstance(created, dict) else None
    admission.require(isinstance(exec_id, str) and re.fullmatch('[a-f0-9]{64}', exec_id), 'exec create outcome unknown')
    print(json.dumps({'event': 'exec_created', 'containerId': cid, 'execId': exec_id}), flush=True)
    guard()
    admission.require(time.monotonic() < deadline, 'exec deadline before start')
    print(json.dumps({'event': 'exec_start_intent', 'execId': exec_id}), flush=True)
    sock = _attach(daemon, exec_id, deadline)
    try:
        output, stderr = bytearray(), bytearray()
        while len(output) < len(b'CLIENT_READY\n'):
            channel, data = _frame(sock, deadline)
            admission.require(channel == 1, 'exec stderr before handshake')
            output.extend(data)
            admission.require(b'CLIENT_READY\n'.startswith(output), 'exec readiness differs')
        binding = _binding(daemon, exec_id, cid, server)
        guard()
        admission.require(_binding(daemon, exec_id, cid, server) == binding, 'exec identity changed before GO')
        record('client-ready.json', {'binding': binding, 'sqlSent': False})
        guard()
        admission.require(_binding(daemon, exec_id, cid, server) == binding, 'exec identity changed after receipt')
        record('client-go-intent.json', {'binding': binding, 'writeMayBeAmbiguous': True})
        guard()
        admission.require(time.monotonic() < deadline and _binding(daemon, exec_id, cid, server) == binding,
                          'exec identity changed before GO submission')
        print(json.dumps({'event': 'exec_go_intent', 'execId': exec_id}), flush=True)
        sock.sendall(b'GO\n')
        while True:
            frame = _frame(sock, deadline, allow_eof=True)
            if frame is None:
                break
            channel, data = frame
            (output if channel == 1 else stderr).extend(data)
            admission.require(len(output) + len(stderr) <= MAX_OUTPUT, 'exec output exceeds limit')
        terminal = admission._request('GET', '/exec/' + exec_id + '/json', daemon)
        admission.require(time.monotonic() < deadline and isinstance(terminal, dict) and terminal.get('ID') == exec_id and
                          terminal.get('ContainerID') == cid and terminal.get('Running') is False and
                          type(terminal.get('ExitCode')) is int and terminal['ExitCode'] == 0 and
                          bytes(output) == EXPECTED and not stderr, 'exec terminal or output differs')
        guard()
        digest = record('client-complete.json', {'binding': binding, 'exitCode': 0,
                        'stdout': bytes(output).decode('ascii'), 'stderr': '', 'sqlSucceeded': True})
        return {'binding': binding, 'receiptSha256': digest, 'exitCode': 0}
    finally:
        sock.close()
