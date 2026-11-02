"""One H2 synthetic tmpfs probe with fixed source identities.

The wrapper observation does not prove Java identity, actual tmpfs or release.
"""
import copy
from pathlib import Path
import re
import stat
import time
from urllib.parse import quote

import container_admission as admission
import jre_probe_admission as jre


SCOPE = jre.H2_SYNTHETIC_SCOPE
IMAGE = 'sha256:d607e8ae9fbcbba5b5651ca13f722600d956fb420f6d0192f40ba08fd5778ef1'
TMPFS = {'/runtime/probe': 'rw,nosuid,nodev,noexec,size=16m,uid=10001,gid=10001,mode=0700'}
COMMAND = ['-c', 'sleep 2; exec /bin/sh /runtime/probe-input/h2-synthetic-entrypoint.sh']
INPUTS = {
    '/runtime/probe-input/h2.jar': ('/srv/consense-rehearsal-20261029/toolchains/h2-2.1.214-stage-20261029-attempt2.jar',
                                    'd623cdc0f61d218cf549a8d09f1c391ff91096116b22e2475475fce4fbe72bd0', 2543012),
    '/runtime/probe-input/H2SyntheticProbe.java': ('/srv/consense-rehearsal-20261029/toolchains/h2-synthetic-probe-attempt1/H2SyntheticProbe.java',
                                                   'f07b1b4f2562e2feb5f7015618664833617e45f0950c729f2006879940f7d4a8', 5746),
    '/runtime/probe-input/h2-synthetic-entrypoint.sh': ('/srv/consense-rehearsal-20261029/toolchains/h2-synthetic-probe-attempt1/h2-synthetic-entrypoint.sh',
                                                        '2409bd4e37cfdaa060d5921355c7e6d8c89bae534daf49eb6704b33f2ece819a', 537),
}
INPUT_IDS = {
    '/runtime/probe-input/h2.jar': (2112, 56137),
    '/runtime/probe-input/H2SyntheticProbe.java': (2112, 56678),
    '/runtime/probe-input/h2-synthetic-entrypoint.sh': (2112, 56679),
}
STAGE = Path('/srv/consense-rehearsal-20261029/toolchains/h2-synthetic-probe-attempt1')
STAGE_ID = (2112, 56676)


def _verify_inputs(hash_contents=True, prior=None):
    jre._safe_parent(STAGE)
    directory = STAGE.lstat()
    admission.require(stat.S_ISDIR(directory.st_mode) and (directory.st_dev, directory.st_ino) == STAGE_ID
                      and directory.st_uid == directory.st_gid == 0
                      and stat.S_IMODE(directory.st_mode) == 0o700, 'H2 input directory differs')
    started, count, identities = time.monotonic(), 0, {}
    for target, (source, digest, size) in INPUTS.items():
        path = Path(source)
        jre._safe_parent(path)
        before = path.lstat()
        admission.require(stat.S_ISREG(before.st_mode) and (before.st_dev, before.st_ino) == INPUT_IDS[target]
                          and before.st_uid == before.st_gid == 0 and stat.S_IMODE(before.st_mode) == 0o444
                          and before.st_nlink == 1 and before.st_size == size, 'H2 input identity differs')
        identities[target] = list(jre._identity(before))
        count = jre._verify_entry(path, ('file', size, digest), started, count,
                                  hash_contents=hash_contents, scope=SCOPE)
        admission.require(list(jre._identity(path.lstat())) == identities[target], 'H2 input metadata changed')
    admission.require(jre._identity(STAGE.lstat()) == jre._identity(directory), 'H2 input directory changed')
    admission.require(prior is None or prior['identities'] == identities, 'H2 input metadata changed after hashing')
    return {'bytesRead': count, 'hashesRechecked': hash_contents, 'identities': identities}


def _profile(profile, pin):
    admission.require(isinstance(profile, dict) and set(profile) == {'name', 'config'}
                      and isinstance(pin, str) and re.fullmatch('[a-f0-9]{64}', pin)
                      and admission._pin(profile) == pin, 'verified H2 profile required')
    config = profile['config']
    admission.require(profile['name'] == 'consense-h2-synthetic-probe-local01' and isinstance(config, dict)
                      and set(config) == {'Image', 'User', 'Entrypoint', 'Cmd', 'Env', 'Labels', 'HostConfig'}
                      and config['Image'] == IMAGE and config['User'] == 'consense:consense'
                      and config['Entrypoint'] == ['/bin/sh'] and config['Cmd'] == COMMAND
                      and config['Env'] == jre.ENV
                      and config['Labels'] == {'consense.role': 'h2-synthetic-probe',
                                               'consense.deployment': 'r1-20261029'},
                      'invalid H2 command or identity')
    host = config['HostConfig']
    admission.require(isinstance(host, dict) and set(host) == {
        'NetworkMode', 'ReadonlyRootfs', 'Privileged', 'CapDrop', 'SecurityOpt', 'Mounts',
        'Memory', 'NanoCpus', 'PidsLimit', 'Tmpfs'}
        and host['NetworkMode'] == 'none' and host['ReadonlyRootfs'] is True and host['Privileged'] is False
        and host['CapDrop'] == ['ALL'] and host['SecurityOpt'] == ['no-new-privileges:true']
        and host['Tmpfs'] == TMPFS and type(host['Memory']) is int and host['Memory'] == 536870912
        and type(host['NanoCpus']) is int and host['NanoCpus'] == 500000000
        and type(host['PidsLimit']) is int and host['PidsLimit'] == 32, 'unsafe H2 host config')
    expected = dict(jre.MOUNTS)
    expected.update({target: item[0] for target, item in INPUTS.items()})
    mounts = admission._mounts(config)
    admission.require(len(mounts) == len(expected) and {m['Target'] for m in mounts} == set(expected)
                      and all(m['ReadOnly'] and expected.get(m['Target']) == m['Source'] for m in mounts),
                      'H2 mounts differ')


def _output(output):
    lines = output.splitlines()
    header = ['synthetic fixture only; no inference about real database credentials',
              'H2 DDL may commit implicitly; commit does not establish transactional rollback']
    expected = header + [None, 'phase1 preparing password statement', 'phase1 password statement prepared',
                         'phase1 executing password statement',
                         'phase1 password statement executed; DDL may have committed implicitly',
                         'phase1 commit returned', 'phase1 complete'] + header + [
                             'phase2 old password rejected sqlstate=28000 errorcode=28000',
                             'phase2 wrong password rejected sqlstate=28000 errorcode=28000', None,
                             'phase2 new synthetic credential and marker verified', 'phase2 complete']
    version = r'java\.version=\S+ h2\.database=2\.1\.214(?: \([^\r\n]*\))? h2\.driver=2\.1\.214(?: \([^\r\n]*\))?'
    admission.require(len(lines) == len(expected) and all(
        re.fullmatch(version, line) is not None if item is None else line == item
        for line, item in zip(lines, expected)), 'H2 output differs or is incomplete')
    admission.require('synthetic-only-new-password' not in output
                      and 'synthetic-only-wrong-password' not in output, 'H2 output contains a password')
    return output


def run(profile, profile_sha256, known_containers, expected_daemon, timeout=60):
    """Retain the container; tmpfs contents disappear once the container stops."""
    profile, known_containers, expected_daemon = copy.deepcopy((profile, known_containers, expected_daemon))
    admission.require(type(timeout) is int and 1 <= timeout <= 60, 'bounded timeout required')
    admission._daemon_pin(expected_daemon)
    with admission._lock():
        jre._verify_scope(SCOPE)
        _profile(profile, profile_sha256)
        jre._verify_resource(expected_daemon)
        inputs = _verify_inputs()
        jre._verify_resource(expected_daemon)
        info = admission._request('GET', '/info', expected_daemon)
        admission.require(isinstance(info, dict) and info.get('ID') == expected_daemon['id']
                          and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        before = admission._inventory(known_containers, expected_daemon)
        admission.require(len(before) < 5, 'container slot unavailable')
        jre._image(profile, expected_daemon)
        protected = list(jre.MOUNTS.values()) + [item[0] for item in INPUTS.values()]
        admission._verify_mount_isolation(protected, [], expected_daemon)
        checked = jre._verify_runtime(profile, scope=SCOPE)
        jre._verify_scope(SCOPE)
        jre._verify_resource(expected_daemon)
        _verify_inputs(hash_contents=False, prior=inputs)
        admission._verify_mount_isolation(protected, [], expected_daemon)
        after = admission._inventory(known_containers, expected_daemon)
        admission.require(sorted(after) == sorted(before) and len(after) < 5, 'container inventory changed before create')
        jre._verify_scope(SCOPE)
        jre._verify_resource(expected_daemon)
        _verify_inputs(hash_contents=False, prior=inputs)
        try:
            created = admission._request('POST', '/containers/create?name=' + quote(profile['name'], safe=''),
                                         expected_daemon, profile['config'])
        except Exception as error:
            raise admission.AdmissionError('H2 create outcome unknown; inspect exact name before retry') from error
        cid = created.get('Id') if isinstance(created, dict) else None
        admission.require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid) and cid not in before,
                          'invalid created identity; inspect exact name before retry')
        return _finish_created(profile, cid, before, known_containers, expected_daemon, timeout, checked, inputs)


def _finish_created(profile, cid, before, known, daemon, timeout, checked, inputs):
    try:
        path = '/containers/' + cid
        created = jre._inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'created')
        admission.require(created.get('StartedAt') == '0001-01-01T00:00:00Z'
                          and created.get('FinishedAt') == '0001-01-01T00:00:00Z', 'container already started')
        jre._check_inventory(before, cid, known, daemon)
        deadline = time.monotonic() + timeout
        jre._verify_scope(SCOPE)
        jre._verify_resource(daemon)
        _verify_inputs(hash_contents=False, prior=inputs)
        admission._verify_mount_isolation(list(jre.MOUNTS.values()) + [item[0] for item in INPUTS.values()], [], daemon)
        admission._request('POST', path + '/start', daemon)
        observed = None
        while True:
            row = admission._request('GET', path + '/json', daemon)
            admission.require(time.monotonic() < deadline, 'H2 probe timed out')
            status = row.get('State', {}).get('Status') if isinstance(row, dict) else None
            if status == 'running':
                state = jre._inspect(row, profile, cid, 'running')
                jre._timestamp(state.get('StartedAt'))
                pid = state['Pid']
                start = jre._starttime(pid)
                admission._process_identity(pid)
                admission.require(jre._starttime(pid) == start, 'H2 wrapper identity changed')
                current = {'pid': pid, 'starttime': start, 'startedAt': state['StartedAt']}
                admission.require(observed is None or current == observed, 'H2 wrapper changed')
                observed = current
            elif status == 'exited':
                state = jre._inspect(row, profile, cid, 'exited')
                admission.require(observed is not None and state['ExitCode'] == 0
                                  and state.get('StartedAt') == observed['startedAt']
                                  and jre._timestamp(state.get('FinishedAt')) >= jre._timestamp(state['StartedAt']),
                                  'H2 probe did not produce observed successful exit')
                break
            else:
                raise admission.AdmissionError('unexpected H2 probe state')
            time.sleep(.1)
        output = _output(jre._logs(cid, daemon))
        jre._check_inventory(before, cid, known, daemon)
        terminal = jre._inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'exited')
        admission.require(all(terminal.get(k) == state.get(k) for k in
                              ('ExitCode', 'StartedAt', 'FinishedAt', 'OOMKilled', 'Error')),
                          'H2 terminal evidence changed')
        jre._verify_scope(SCOPE)
        return {'id': cid, 'wrapperProcess': observed, 'exitCode': state['ExitCode'],
                'finishedAt': state['FinishedAt'], 'output': output, 'removed': False,
                'runtimeFileCheck': checked, 'inputFileCheck': inputs, 'javaProcessIdentityVerified': False,
                'tmpfsRuntimeVerified': False, 'releaseVerified': False, 'fixtureRetained': False}
    except Exception as error:
        raise admission.AdmissionError('H2 probe ' + cid + ' retained or outcome unknown') from error
