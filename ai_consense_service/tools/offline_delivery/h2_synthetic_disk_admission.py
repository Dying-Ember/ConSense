"""One H2 synthetic disk bootstrap with a fixed empty fixture directory.

The wrapper observation does not prove Java identity, fixture persistence or release.
"""
import copy
import os
from pathlib import Path
import re
import stat
import time
from urllib.parse import quote

import container_admission as admission
import jre_probe_admission as jre


SCOPE = jre.H2_DISK_BOOTSTRAP_SCOPE
IMAGE = 'sha256:d607e8ae9fbcbba5b5651ca13f722600d956fb420f6d0192f40ba08fd5778ef1'
FIXTURE = Path('/srv/consense-rehearsal-20261029/instances/local01/h2-synthetic-tcp-attempt1/work')
FIXTURE_ID = (2112, 56687)
FIXTURE_PARENT_ID = (2112, 56680)
PROTECTED_DATA = ['/srv/consense-rehearsal-20261029/baseline',
                  '/srv/consense-rehearsal-20261029/instances/local01/h2-recovery-20261029-2000']

GPU_ID = '5caf726699a497bff26741b11c41c8ae0a614d00da841262b90a6e33acbaa887'
GPU_PROFILE_PIN = '422a6b2872d053d77b66422e64c0823659ffa70fc4f320ae64424458a0851304'
GPU_PROFILE = {'config': {'Cmd': ['/probe/code/ticket04-cuda-container-probe.py'],
            'Entrypoint': ['/usr/bin/python3', '-I', '-S', '-B'],
            'Env': ['PATH=/usr/bin:/bin', 'HOME=/home/consense', 'LANG=C'],
            'HostConfig': {'CapDrop': ['ALL'],
                           'Devices': [{'CgroupPermissions': 'rw',
                                        'PathInContainer': '/dev/dxg',
                                        'PathOnHost': '/dev/dxg'}],
                           'Memory': 268435456,
                           'Mounts': [{'ReadOnly': True,
                                       'Source': '/srv/consense-docker/test-rootfs/usr',
                                       'Target': '/usr',
                                       'Type': 'bind'},
                                      {'ReadOnly': True,
                                       'Source': '/srv/consense-docker/test-rootfs/etc/ld.so.cache',
                                       'Target': '/etc/ld.so.cache',
                                       'Type': 'bind'},
                                      {'ReadOnly': True,
                                       'Source': '/srv/consense-rehearsal-20261029/t04-gpu-positive-control-attempt1',
                                       'Target': '/probe',
                                       'Type': 'bind'},
                                      {'ReadOnly': True,
                                       'Source': '/usr/lib/wsl/lib',
                                       'Target': '/probe/driver/lib',
                                       'Type': 'bind'},
                                      {'ReadOnly': True,
                                       'Source': '/usr/lib/wsl/drivers',
                                       'Target': '/probe/driver/drivers',
                                       'Type': 'bind'}],
                           'NanoCpus': 250000000,
                           'NetworkMode': 'none',
                           'PidsLimit': 20,
                           'Privileged': False,
                           'ReadonlyRootfs': True,
                           'SecurityOpt': ['no-new-privileges:true']},
            'Image': 'sha256:d607e8ae9fbcbba5b5651ca13f722600d956fb420f6d0192f40ba08fd5778ef1',
            'Labels': {'consense.deployment': 'r1-20261029', 'consense.role': 'gpu'},
            'User': 'consense:consense'},
 'name': 'consense-gpu-positive-control-local01',
 'ports': [],
 'role': 'gpu'}
GPU_START = '2026-10-29T19:47:15.079984583Z'
GPU_FINISH = '2026-10-29T19:47:15.23980952Z'


def _verify_fixture(prior=None):
    jre._safe_parent(FIXTURE)
    parent = FIXTURE.parent.lstat()
    admission.require(stat.S_ISDIR(parent.st_mode) and (parent.st_dev, parent.st_ino) == FIXTURE_PARENT_ID
                      and parent.st_uid == parent.st_gid == 0 and stat.S_IMODE(parent.st_mode) == 0o700,
                      'synthetic fixture parent differs')
    before = FIXTURE.lstat()
    admission.require(stat.S_ISDIR(before.st_mode) and (before.st_dev, before.st_ino) == FIXTURE_ID
                      and before.st_uid == before.st_gid == 10001 and stat.S_IMODE(before.st_mode) == 0o700,
                      'synthetic fixture identity differs')
    fd = os.open(FIXTURE, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        admission.require(jre._identity(before) == jre._identity(os.fstat(fd)) and not os.listdir(fd)
                          and jre._identity(before) == jre._identity(os.fstat(fd))
                          == jre._identity(FIXTURE.lstat()), 'synthetic fixture changed or is not empty')
    finally:
        os.close(fd)
    current = list(jre._identity(before))
    admission.require(prior is None or prior == current, 'synthetic fixture changed after preflight')
    return current
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
    admission.require(profile['name'] == 'consense-h2-synthetic-disk-bootstrap-local01' and isinstance(config, dict)
                      and set(config) == {'Image', 'User', 'Entrypoint', 'Cmd', 'Env', 'Labels', 'HostConfig'}
                      and config['Image'] == IMAGE and config['User'] == 'consense:consense'
                      and config['Entrypoint'] == ['/bin/sh'] and config['Cmd'] == COMMAND
                      and config['Env'] == jre.ENV
                      and config['Labels'] == {'consense.role': 'h2-synthetic-disk-bootstrap',
                                               'consense.deployment': 'r1-20261029'},
                      'invalid H2 command or identity')
    host = config['HostConfig']
    admission.require(isinstance(host, dict) and set(host) == {
        'NetworkMode', 'ReadonlyRootfs', 'Privileged', 'CapDrop', 'SecurityOpt', 'Mounts',
        'Memory', 'NanoCpus', 'PidsLimit'}
        and host['NetworkMode'] == 'none' and host['ReadonlyRootfs'] is True and host['Privileged'] is False
        and host['CapDrop'] == ['ALL'] and host['SecurityOpt'] == ['no-new-privileges:true']
        and type(host['Memory']) is int and host['Memory'] == 536870912
        and type(host['NanoCpus']) is int and host['NanoCpus'] == 500000000
        and type(host['PidsLimit']) is int and host['PidsLimit'] == 32, 'unsafe H2 host config')
    expected = dict(jre.MOUNTS)
    expected.update({target: item[0] for target, item in INPUTS.items()})
    expected['/runtime/probe'] = str(FIXTURE)
    mounts = admission._mounts(config)
    admission.require(len(mounts) == len(expected) and {m['Target'] for m in mounts} == set(expected)
                      and all(m['ReadOnly'] is (m['Target'] != '/runtime/probe')
                              and expected.get(m['Target']) == m['Source'] for m in mounts),
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


def _inspect(result, profile, cid, state):
    actual = jre._inspect(result, profile, cid, state)
    admission.require(result['HostConfig'].get('Tmpfs') in (None, {}), 'unexpected tmpfs mount')
    return actual


def _known_inventory(known, daemon, probe_id=None):
    admission.require(known == {} and admission._pin(GPU_PROFILE) == GPU_PROFILE_PIN,
                      'fixed retained GPU profile required')
    rows = admission._request('GET', '/containers/json?all=1', daemon)
    admission.require(isinstance(rows, list) and len(rows) <= 5 and all(isinstance(row, dict) for row in rows),
                      'invalid container inventory')
    ids = [row.get('Id') for row in rows]
    expected = [GPU_ID] + ([probe_id] if probe_id is not None else [])
    admission.require(all(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid) for cid in ids)
                      and len(ids) == len(set(ids)) and sorted(ids) == sorted(expected),
                      'unknown or missing container')
    gpu = admission._request('GET', '/containers/' + GPU_ID + '/json', daemon)
    state = jre._inspect(gpu, GPU_PROFILE, GPU_ID, 'exited')
    admission.require(gpu['HostConfig'].get('Tmpfs') in (None, {})
                      and state['ExitCode'] == 1 and state.get('StartedAt') == GPU_START
                      and state.get('FinishedAt') == GPU_FINISH, 'retained GPU terminal differs')
    return ids


def run(profile, profile_sha256, known_containers, expected_daemon, timeout=60):
    """Retain the container and fixture; never retry an ambiguous bootstrap."""
    profile, known_containers, expected_daemon = copy.deepcopy((profile, known_containers, expected_daemon))
    admission.require(type(timeout) is int and 1 <= timeout <= 60, 'bounded timeout required')
    admission._daemon_pin(expected_daemon)
    with admission._lock():
        jre._verify_scope(SCOPE)
        fixture = _verify_fixture()
        _profile(profile, profile_sha256)
        jre._verify_resource(expected_daemon)
        inputs = _verify_inputs()
        jre._verify_resource(expected_daemon)
        info = admission._request('GET', '/info', expected_daemon)
        admission.require(isinstance(info, dict) and info.get('ID') == expected_daemon['id']
                          and info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        before = _known_inventory(known_containers, expected_daemon)
        admission.require(len(before) < 5, 'container slot unavailable')
        jre._image(profile, expected_daemon)
        protected = list(jre.MOUNTS.values()) + [item[0] for item in INPUTS.values()] + PROTECTED_DATA
        admission._verify_mount_isolation(protected, [str(FIXTURE)], expected_daemon)
        checked = jre._verify_runtime(profile, scope=SCOPE)
        jre._verify_scope(SCOPE)
        jre._verify_resource(expected_daemon)
        _verify_inputs(hash_contents=False, prior=inputs)
        admission._verify_mount_isolation(protected, [str(FIXTURE)], expected_daemon)
        after = _known_inventory(known_containers, expected_daemon)
        admission.require(sorted(after) == sorted(before) and len(after) < 5, 'container inventory changed before create')
        jre._verify_scope(SCOPE)
        jre._verify_resource(expected_daemon)
        _verify_inputs(hash_contents=False, prior=inputs)
        _verify_fixture(prior=fixture)
        try:
            created = admission._request('POST', '/containers/create?name=' + quote(profile['name'], safe=''),
                                         expected_daemon, profile['config'])
        except Exception as error:
            raise admission.AdmissionError('H2 create outcome unknown; inspect exact name before retry') from error
        cid = created.get('Id') if isinstance(created, dict) else None
        admission.require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid) and cid not in before,
                          'invalid created identity; inspect exact name before retry')
        return _finish_created(profile, cid, before, known_containers, expected_daemon, timeout, checked, inputs, fixture)


def _finish_created(profile, cid, before, known, daemon, timeout, checked, inputs, fixture):
    try:
        path = '/containers/' + cid
        created = _inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'created')
        admission.require(created.get('StartedAt') == '0001-01-01T00:00:00Z'
                          and created.get('FinishedAt') == '0001-01-01T00:00:00Z', 'container already started')
        _known_inventory(known, daemon, cid)
        deadline = time.monotonic() + timeout
        jre._verify_scope(SCOPE)
        jre._verify_resource(daemon)
        _verify_inputs(hash_contents=False, prior=inputs)
        admission._verify_mount_isolation(list(jre.MOUNTS.values()) + [item[0] for item in INPUTS.values()]
                                          + PROTECTED_DATA, [str(FIXTURE)], daemon)
        _verify_fixture(prior=fixture)
        _known_inventory(known, daemon, cid)
        admission._request('POST', path + '/start', daemon)
        observed = None
        while True:
            row = admission._request('GET', path + '/json', daemon)
            admission.require(time.monotonic() < deadline, 'H2 probe timed out')
            status = row.get('State', {}).get('Status') if isinstance(row, dict) else None
            if status == 'running':
                state = _inspect(row, profile, cid, 'running')
                jre._timestamp(state.get('StartedAt'))
                pid = state['Pid']
                start = jre._starttime(pid)
                admission._process_identity(pid)
                admission.require(jre._starttime(pid) == start, 'H2 wrapper identity changed')
                current = {'pid': pid, 'starttime': start, 'startedAt': state['StartedAt']}
                admission.require(observed is None or current == observed, 'H2 wrapper changed')
                observed = current
            elif status == 'exited':
                state = _inspect(row, profile, cid, 'exited')
                admission.require(observed is not None and state['ExitCode'] == 0
                                  and state.get('StartedAt') == observed['startedAt']
                                  and jre._timestamp(state.get('FinishedAt')) >= jre._timestamp(state['StartedAt']),
                                  'H2 probe did not produce observed successful exit')
                break
            else:
                raise admission.AdmissionError('unexpected H2 probe state')
            time.sleep(.1)
        output = _output(jre._logs(cid, daemon))
        _known_inventory(known, daemon, cid)
        terminal = _inspect(admission._request('GET', path + '/json', daemon), profile, cid, 'exited')
        admission.require(all(terminal.get(k) == state.get(k) for k in
                              ('ExitCode', 'StartedAt', 'FinishedAt', 'OOMKilled', 'Error')),
                          'H2 terminal evidence changed')
        jre._verify_scope(SCOPE)
        return {'id': cid, 'wrapperProcess': observed, 'exitCode': state['ExitCode'],
                'finishedAt': state['FinishedAt'], 'output': output, 'removed': False,
                'runtimeFileCheck': checked, 'inputFileCheck': inputs, 'javaProcessIdentityVerified': False,
                'releaseVerified': False, 'fixturePersistenceVerified': False, 'retainedGpuRelease': 'unknown'}
    except Exception as error:
        raise admission.AdmissionError('H2 probe ' + cid + ' retained or outcome unknown') from error
