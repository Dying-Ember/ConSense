"""Bounded evidence for one H2 diagnostic; this never authorizes a listener."""
import os
from pathlib import Path
import re

import container_admission as admission
import jre_probe_admission as jre

MAX_TABLE = 1024 * 1024
MAX_MATCHES = 32
MAX_FDS = 256
PORT = 28682


def _identity(pid):
    start = jre._starttime(pid)
    net = os.stat('/proc/' + str(pid) + '/ns/net')
    boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    return start, (net.st_dev, net.st_ino), boot


def _parse_table(raw, family, matches):
    if len(raw) > MAX_TABLE or not raw.endswith(b'\n'):
        raise ValueError('tcp table truncated or oversized')
    lines = raw.decode('ascii', errors='strict').splitlines()
    if not lines or not lines[0].strip().startswith('sl'):
        raise ValueError('tcp table header unavailable')
    width = 8 if family == 'tcp' else 32
    for line in lines[1:]:
        fields = line.split()
        if len(fields) < 10 or not re.fullmatch(r'[0-9A-Fa-f]{2}', fields[3]):
            raise ValueError('invalid tcp table row')
        local = fields[1].split(':')
        if len(local) != 2 or not re.fullmatch('[0-9A-Fa-f]{%d}' % width, local[0]) or \
                not re.fullmatch(r'[0-9A-Fa-f]{4}', local[1]) or not fields[9].isdigit():
            raise ValueError('invalid tcp endpoint')
        if fields[3].upper() == '0A' and int(local[1], 16) == PORT:
            if fields[9] == '0' or len(line) > 4096 or len(matches) >= MAX_MATCHES:
                raise ValueError('too many or oversized listener rows')
            matches.append({'family': family, 'raw': line, 'address': local[0], 'inode': fields[9]})


def _tables(pid, entries):
    for family in ('tcp', 'tcp6'):
        with (Path('/proc') / str(pid) / 'net' / family).open('rb') as stream:
            raw = stream.read(MAX_TABLE + 1)
        _parse_table(raw, family, entries)
        if len(entries) > MAX_MATCHES:
            raise ValueError('too many listener rows')


def _fds(pid):
    sockets = set()
    with os.scandir('/proc/' + str(pid) + '/fd') as entries:
        count = 0
        for entry in entries:
            count += 1
            if count > MAX_FDS:
                raise ValueError('too many file descriptors')
            target = os.readlink(entry.path)
            match = re.fullmatch(r'socket:\[(\d+)\]', target)
            if match:
                sockets.add(match.group(1))
    return sockets


def capture(pid, starttime, boot, netns):
    """Capture a first and verification sample; errors return unknown, never an authorization."""
    if type(pid) is not int or pid <= 0 or type(starttime) is not int or starttime <= 0 or \
            not isinstance(boot, str) or not boot or not isinstance(netns, tuple) or len(netns) != 2 or \
            any(type(value) is not int or value < 0 for value in netns):
        raise ValueError('invalid bound identity')
    result = {'status': 'unknown', 'pid': pid, 'starttime': starttime, 'boot': boot,
              'netns': list(netns), 'listeners': [], 'verificationListeners': [], 'reason': None}
    try:
        expected = (starttime, netns, boot)
        if _identity(pid) != expected:
            raise ValueError('identity differs before sample')
        _tables(pid, result['listeners'])
        first_fds = _fds(pid)
        _tables(pid, result['verificationListeners'])
        second_fds = _fds(pid)
        if _identity(pid) != expected:
            raise ValueError('identity differs after sample')
        key = lambda row: (row['family'], row['raw'])
        if sorted(result['listeners'], key=key) != sorted(result['verificationListeners'], key=key) or \
                first_fds != second_fds:
            raise ValueError('listener or descriptor snapshot changed')
        for row in result['listeners'] + result['verificationListeners']:
            row['ownership'] = 'owned' if row['inode'] in first_fds else 'absent'
        result['status'] = 'observed'
    except (OSError, ValueError, UnicodeError, admission.AdmissionError) as error:
        result['reason'] = str(error)
        for row in result['listeners'] + result['verificationListeners']:
            row['ownership'] = 'unknown'
    return result
