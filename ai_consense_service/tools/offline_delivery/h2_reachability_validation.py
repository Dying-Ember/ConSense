"""One five-slot synthetic reachability candidate; stage identity must be independently bound."""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import time
from urllib.parse import quote

import container_admission as admission
import h2_listener_diagnostic as diag
import h2_listener_observation as observation
import h2_reachability_exec as client
import h2_tcp_combined_admission as h2
import jre_probe_admission as jre

SCOPE = 'ticket-02 H2 synthetic reachability exec attempt1 only'
RETAINED = '3d4f965ccf2ca64d9467bd7658b1da412a6585d7fd9f99b3281d83932541a218'
RETAINED_START = '2026-10-29T21:42:24.784199756Z'
RETAINED_END = '2026-10-29T21:42:25.935243279Z'
SOURCE = Path('/srv/consense-rehearsal-20261029/toolchains/h2-tcp-reachability-client-attempt1/H2TcpReachabilityProbe.java')
SOURCE_SHA = '193c3e2ad41178c4d6d5f15f259beeb103e04fabda23f37f4d331021ad3b5680'
SOURCE_DIR_ID = (2112, 62225)
SOURCE_ID = (2112, 62236)
EVIDENCE_NAME = 'h2-synthetic-reachability-exec-attempt1'
PROFILE = copy.deepcopy(diag.PROFILE)
PROFILE['name'] = 'consense-h2-reachability-local01'
PROFILE['config']['Labels']['consense.role'] = 'h2-reachability'
PROFILE['config']['Cmd'][PROFILE['config']['Cmd'].index('-ifExists')] = '-ifNotExists'
PROFILE['config']['HostConfig']['Mounts'].append({'Type': 'bind', 'Source': str(SOURCE),
                                                   'Target': '/runtime/probe-input/H2TcpReachabilityProbe.java',
                                                   'ReadOnly': True})


def _source():
    admission.require(SOURCE_DIR_ID is not None and SOURCE_ID is not None, 'staged source identity not bound')
    jre._safe_parent(SOURCE)
    parent, before = SOURCE.parent.lstat(), SOURCE.lstat()
    admission.require(stat.S_ISDIR(parent.st_mode) and (parent.st_dev, parent.st_ino, parent.st_uid,
                      parent.st_gid, stat.S_IMODE(parent.st_mode)) == (*SOURCE_DIR_ID, 0, 0, 0o700) and
                      stat.S_ISREG(before.st_mode) and (before.st_dev, before.st_ino, before.st_uid, before.st_gid,
                      before.st_size, before.st_nlink, stat.S_IMODE(before.st_mode)) == (*SOURCE_ID, 0, 0, 2493, 1, 0o444),
                      'staged source metadata differs')
    fd = os.open(SOURCE, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        data = os.read(fd, 2494)
        admission.require(len(data) == 2493 and hashlib.sha256(data).hexdigest() == SOURCE_SHA and
                          jre._identity(before) == jre._identity(os.fstat(fd)) == jre._identity(SOURCE.lstat()) and
                          jre._identity(parent) == jre._identity(SOURCE.parent.lstat()), 'staged source bytes differ')
        return list(jre._identity(before))
    finally:
        os.close(fd)


def _inventory(owned=()):
    h2._inventory(diag.DAEMON, [diag.FAILED, RETAINED, *owned])
    failed = admission._request('GET', '/containers/' + diag.FAILED + '/json', diag.DAEMON)
    state = jre._inspect(failed, h2.PROFILE, diag.FAILED, 'exited')
    admission.require(state['ExitCode'] == 143 and state['StartedAt'] == diag.FAILED_STARTED and
                      state['FinishedAt'] == diag.FAILED_FINISHED and failed['HostConfig'].get('Tmpfs') in (None, {})
                      and failed['HostConfig'].get('AutoRemove') is False and
                      failed['HostConfig'].get('RestartPolicy') == {'Name': 'no', 'MaximumRetryCount': 0},
                      'retained failed H2 differs')
    retained = admission._request('GET', '/containers/' + RETAINED + '/json', diag.DAEMON)
    state = diag.inspect(retained, RETAINED, 'exited')
    admission.require(state['ExitCode'] == 143 and state['StartedAt'] == RETAINED_START and
                      state['FinishedAt'] == RETAINED_END, 'retained diagnostic differs')


def _scope():
    jre._verify_scope(SCOPE)


def run(profile_pin):
    """Never retries, removes or signals on unknown outcomes."""
    admission.require(SOURCE_DIR_ID is not None and SOURCE_ID is not None, 'staged source identity not bound')
    admission.require(admission._pin(PROFILE) == profile_pin, 'reachability profile differs')
    admission._daemon_pin(diag.DAEMON)
    admission.require(hasattr(os, 'pidfd_open') and hasattr(signal, 'pidfd_send_signal'), 'pidfd unavailable')
    with admission._lock():
        _scope(); jre._verify_resource(diag.DAEMON); h2._network(diag.DAEMON); _inventory()
        info = admission._request('GET', '/info', diag.DAEMON)
        admission.require(isinstance(info, dict) and info.get('ID') == diag.DAEMON['id'] and
                          info.get('DockerRootDir') == admission.DATA_ROOT, 'daemon identity differs')
        admission._mounts(PROFILE['config']); jre._image(PROFILE, diag.DAEMON)
        protected = list(jre.MOUNTS.values()) + [h2.JAR, str(SOURCE)] + h2.disk.PROTECTED_DATA
        h2._qdrant_check(diag.DAEMON, protected); fixture = h2._closed_fixture(); h2._ports_free()
        source = _source(); inputs = diag.runtime(SCOPE)
        _scope(); jre._verify_resource(diag.DAEMON); diag.recheck_inputs(inputs)
        admission.require(_source() == source, 'source changed'); h2._closed_fixture(fixture)
        h2._qdrant_check(diag.DAEMON, protected); h2._network(diag.DAEMON); h2._ports_free(); _inventory()
        folder = diag.make_evidence(EVIDENCE_NAME)
        try:
            _scope(); jre._verify_resource(diag.DAEMON)
            print(json.dumps({'event': 'reachability_create_intent', 'name': PROFILE['name']}), flush=True)
            created = admission._request('POST', '/containers/create?name=' + quote(PROFILE['name'], safe=''),
                                         diag.DAEMON, PROFILE['config'])
            cid = created.get('Id') if isinstance(created, dict) else None
            admission.require(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid) and
                              cid not in (diag.FAILED, RETAINED, h2.disk.GPU_ID, h2.QDRANT_ID), 'server create unknown')
            print(json.dumps({'event': 'reachability_created', 'id': cid}), flush=True)
            state = diag.inspect(admission._request('GET', '/containers/' + cid + '/json', diag.DAEMON),
                                 cid, 'created', PROFILE)
            admission.require(state['StartedAt'] == state['FinishedAt'] == '0001-01-01T00:00:00Z', 'server already started')
            _scope(); _inventory([cid]); diag.recheck_inputs(inputs)
            admission.require(_source() == source, 'source changed'); h2._closed_fixture(fixture)
            h2._qdrant_check(diag.DAEMON, protected); h2._network(diag.DAEMON); h2._ports_free()
            print(json.dumps({'event': 'reachability_start_intent', 'id': cid}), flush=True)
            admission._request('POST', '/containers/' + cid + '/start', diag.DAEMON)
            deadline = time.monotonic() + 60
            sample = None
            while time.monotonic() < deadline:
                _scope(); jre._verify_resource(diag.DAEMON); _inventory([cid])
                binding = diag.bound(cid, PROFILE)
                tmpfs = diag.verify_tmpfs(binding)
                sample = observation.capture(binding['pid'], binding['starttime'], h2.BOOT, binding['networkNamespace'])
                if sample['status'] != 'observed' or sample['listeners']:
                    break
                time.sleep(.5)
            admission.require(sample is not None and sample.get('status') == 'observed', 'listener observation unknown')
            rows = sample.get('listeners')
            admission.require(isinstance(rows, list) and len(rows) == 1 and
                              (rows[0].get('family'), rows[0].get('address')) in
                              (('tcp', '0100007F'), ('tcp6', '0000000000000000FFFF00000100007F')) and
                              rows[0].get('ownership') == 'owned', 'listener differs')
            diag.record(folder, 'listener.json', {'binding': binding, 'tmpfs': tmpfs, 'sample': sample,
                                                  'continuousIsolationVerified': False})

            def checkpoint():
                _scope(); jre._verify_resource(diag.DAEMON); _inventory([cid]); h2._qdrant_check(diag.DAEMON)

            def running_guard():
                checkpoint()
                admission.require(diag.bound(cid, PROFILE) == binding and _source() == source, 'server or source changed')
                admission.require(h2._listener(binding['pid']), 'listener unavailable')

            result = client.run(diag.DAEMON, cid, binding, running_guard,
                                lambda name, value: diag.record(folder, name, value))
            checkpoint()
            stopped = diag.stop(binding, set(), guard=checkpoint, profile=PROFILE)
            digest = diag.record(folder, 'end.json', {'containerId': cid, 'client': result, 'stop': stopped,
                                'cgroupReleaseVerified': False, 'persistenceVerified': False})
            return {'containerId': cid, 'client': result, 'stop': stopped, 'endReceiptSha256': digest,
                    'continuousIsolationVerified': False, 'cgroupReleaseVerified': False}
        finally:
            os.close(folder)
