"""One counted compiler smoke with explicit evidence limits.

The wrapper observation does not prove Java identity, tmpfs enforcement or release.
"""
import copy
import re
import time
from urllib.parse import quote

import container_admission as admission
import jre_probe_admission as jre


SCOPE = jre.COMPILER_SCOPE
IMAGE = 'sha256:d607e8ae9fbcbba5b5651ca13f722600d956fb420f6d0192f40ba08fd5778ef1'
TMPFS = {'/tmp': 'rw,nosuid,nodev,noexec,size=16m,mode=1777'}
COMMAND = ['-c', "set -eu; umask 077; sleep 2; "
           "printf '%s\\n' 'public class CompilerSmoke { public static void main(String[] a) { System.out.println(\"SOURCE_MODE_OK\"); } }' > /tmp/CompilerSmoke.java; "
           "/runtime/jre/bin/java -Djava.io.tmpdir=/tmp /tmp/CompilerSmoke.java; "
           "/runtime/jre/bin/java -Djava.io.tmpdir=/tmp -m jdk.compiler/com.sun.tools.javac.Main -d /tmp /tmp/CompilerSmoke.java; "
           "test -s /tmp/CompilerSmoke.class; printf '%s\\n' JAVAC_COMPILE_OK"]


def _profile(profile, pin):
    admission.require(isinstance(profile, dict) and set(profile) == {'name', 'config'}
                      and isinstance(pin, str) and re.fullmatch('[a-f0-9]{64}', pin)
                      and admission._pin(profile) == pin, 'verified compiler profile required')
    config = profile['config']
    admission.require(profile['name'] == 'consense-jre-compiler-probe-local01' and isinstance(config, dict)
                      and set(config) == {'Image', 'User', 'Entrypoint', 'Cmd', 'Env', 'Labels', 'HostConfig'}
                      and config['Image'] == IMAGE
                      and config['User'] == 'consense:consense' and config['Entrypoint'] == ['/bin/sh']
                      and config['Cmd'] == COMMAND and config['Env'] == jre.ENV
                      and config['Labels'] == {'consense.role': 'jre-compiler-probe',
                                               'consense.deployment': 'r1-20261029'},
                      'invalid compiler command or identity')
    host = config['HostConfig']
    admission.require(isinstance(host, dict) and set(host) == {
        'NetworkMode', 'ReadonlyRootfs', 'Privileged', 'CapDrop', 'SecurityOpt', 'Mounts',
        'Memory', 'NanoCpus', 'PidsLimit', 'Tmpfs'}
        and host['NetworkMode'] == 'none' and host['ReadonlyRootfs'] is True and host['Privileged'] is False
        and host['CapDrop'] == ['ALL'] and host['SecurityOpt'] == ['no-new-privileges:true']
        and host['Tmpfs'] == TMPFS and type(host['Memory']) is int and host['Memory'] == 536870912
        and type(host['NanoCpus']) is int and host['NanoCpus'] == 500000000
        and type(host['PidsLimit']) is int and host['PidsLimit'] == 32, 'unsafe compiler host config')
    mounts = admission._mounts(config)
    admission.require(len(mounts) == len(jre.MOUNTS) and {m['Target'] for m in mounts} == set(jre.MOUNTS)
                      and all(m['ReadOnly'] and jre.MOUNTS.get(m['Target']) == m['Source'] for m in mounts),
                      'compiler mounts differ')


def run(profile, profile_sha256, known_containers, expected_daemon, timeout=60):
    """Run one scoped probe and retain its exact container for reconciliation."""
    profile, known_containers, expected_daemon = copy.deepcopy((profile, known_containers, expected_daemon))
    admission.require(type(timeout) is int and 1 <= timeout <= 60, 'bounded timeout required')
    _profile(profile, profile_sha256)
    admission._daemon_pin(expected_daemon)
    with admission._lock():
        jre._verify_scope(SCOPE)
        jre._verify_resource(expected_daemon)
        info = admission._request('GET', '/info', expected_daemon)
        admission.require(isinstance(info, dict) and info.get('ID') == expected_daemon['id']
                          and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        before = admission._inventory(known_containers, expected_daemon)
        admission.require(len(before) < 5, 'container slot unavailable')
        _profile(profile, profile_sha256)
        jre._image(profile, expected_daemon)
        admission._verify_mount_isolation(list(jre.MOUNTS.values()), [], expected_daemon)
        checked = jre._verify_runtime(profile, scope=SCOPE)
        jre._verify_scope(SCOPE)
        jre._verify_resource(expected_daemon)
        admission._verify_mount_isolation(list(jre.MOUNTS.values()), [], expected_daemon)
        after = admission._inventory(known_containers, expected_daemon)
        admission.require(sorted(after) == sorted(before) and len(after) < 5, 'container inventory changed before create')
        jre._verify_scope(SCOPE)
        jre._verify_resource(expected_daemon)
        try:
            created = admission._request('POST', '/containers/create?name=' + quote(profile['name'], safe=''),
                                         expected_daemon, profile['config'])
        except Exception as error:
            raise admission.AdmissionError('compiler create outcome unknown; inspect exact name before retry') from error
        cid = created.get('Id') if isinstance(created, dict) else None
        admission.require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid) and cid not in before,
                          'invalid created identity; inspect exact name before retry')
        return _finish_created(profile, cid, before, known_containers, expected_daemon, timeout, checked)


def _finish_created(profile, cid, before, known, daemon, timeout, checked):
    """The terminal container is deliberately retained for independent reconciliation."""
    try:
        path = '/containers/' + cid
        created = jre._inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'created')
        admission.require(created.get('StartedAt') == '0001-01-01T00:00:00Z'
                          and created.get('FinishedAt') == '0001-01-01T00:00:00Z',
                          'compiler container has already started')
        jre._check_inventory(before, cid, known, daemon)
        deadline = time.monotonic() + timeout
        jre._verify_scope(SCOPE)
        jre._verify_resource(daemon)
        admission._verify_mount_isolation(list(jre.MOUNTS.values()), [], daemon)
        admission._request('POST', path + '/start', daemon)
        admission.require(time.monotonic() < deadline, 'compiler timed out during start')
        observed = None
        while True:
            row = admission._request('GET', path + '/json', daemon)
            admission.require(time.monotonic() < deadline, 'compiler timed out')
            status = row.get('State', {}).get('Status') if isinstance(row, dict) else None
            if status == 'running':
                state = jre._inspect(row, profile, cid, 'running')
                jre._timestamp(state.get('StartedAt'))
                pid = state['Pid']
                start = jre._starttime(pid)
                admission._process_identity(pid)
                admission.require(jre._starttime(pid) == start, 'compiler wrapper identity changed')
                current = {'pid': pid, 'starttime': start, 'startedAt': state['StartedAt']}
                admission.require(observed is None or current == observed, 'compiler wrapper changed')
                observed = current
            elif status == 'exited':
                state = jre._inspect(row, profile, cid, 'exited')
                admission.require(observed is not None and state['ExitCode'] == 0
                                  and state.get('StartedAt') == observed['startedAt']
                                  and jre._timestamp(state.get('FinishedAt')) >= jre._timestamp(state['StartedAt']),
                                  'compiler did not produce observed successful exit')
                break
            else:
                raise admission.AdmissionError('unexpected compiler state')
            time.sleep(.1)
        output = jre._logs(cid, daemon)
        lines = output.splitlines()
        admission.require(lines.count('SOURCE_MODE_OK') == 1 and lines.count('JAVAC_COMPILE_OK') == 1
                          and lines.index('SOURCE_MODE_OK') < lines.index('JAVAC_COMPILE_OK'),
                          'compiler output differs')
        jre._check_inventory(before, cid, known, daemon)
        terminal = jre._inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'exited')
        admission.require(all(terminal.get(k) == state.get(k) for k in
                              ('ExitCode', 'StartedAt', 'FinishedAt', 'OOMKilled', 'Error')),
                          'compiler terminal evidence changed')
        jre._verify_scope(SCOPE)
        return {'id': cid, 'wrapperProcess': observed, 'exitCode': state['ExitCode'],
                'finishedAt': state['FinishedAt'], 'output': output, 'removed': False,
                'runtimeFileCheck': checked, 'javaProcessIdentityVerified': False,
                'tmpfsRuntimeVerified': False, 'releaseVerified': False}
    except Exception as error:
        raise admission.AdmissionError('compiler probe ' + cid + ' retained or outcome unknown') from error
