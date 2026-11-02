"""Check one release tree against an external JSON inventory at this point in time.

The inventory is {"files": [{"path": "relative/path", "sha256": "<lowercase SHA256>"}]}.
Paths use forward slashes. The JSON file must be outside the release tree; its
SHA256 pin must come from an independently trusted source.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import re
import stat
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def no_link(path):
    info = path.lstat()
    require(not stat.S_ISLNK(info.st_mode) and not getattr(info, 'st_file_attributes', 0) & 0x400,
            'symbolic links and reparse points are not permitted')
    return info


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result


def verify(root, manifest, manifest_sha256, *, reject_hardlinks=False):
    require(re.fullmatch('[a-f0-9]{64}', manifest_sha256) is not None, 'invalid manifest SHA256 pin')
    root, manifest = Path(root).absolute(), Path(manifest).absolute()
    for path in (root, *root.parents, manifest, *manifest.parents):
        no_link(path)
    root, manifest = root.resolve(strict=True), manifest.resolve(strict=True)
    require(root.is_dir() and manifest.is_file() and not manifest.is_relative_to(root),
            'release directory and external manifest are required')
    if reject_hardlinks:
        require(manifest.lstat().st_nlink == 1, 'linked manifest is not permitted')
    raw_manifest = manifest.read_bytes()
    require(hashlib.sha256(raw_manifest).hexdigest() == manifest_sha256, 'manifest SHA256 mismatch')
    data = json.loads(raw_manifest.decode('utf-8'), object_pairs_hook=unique_object)
    require(isinstance(data, dict) and set(data) == {'files'} and isinstance(data['files'], list)
            and data['files'], 'nonempty files inventory is required')
    expected = {}
    for row in data['files']:
        require(isinstance(row, dict) and set(row) == {'path', 'sha256'}, 'invalid inventory entry')
        name, digest = row['path'], row['sha256']
        require(isinstance(name, str) and name and '\\' not in name and not PureWindowsPath(name).drive
                and all(part not in ('', '.', '..') for part in name.split('/'))
                and all(ord(char) >= 32 and ord(char) != 127 for char in name), 'invalid relative path')
        require(isinstance(digest, str) and re.fullmatch('[a-f0-9]{64}', digest), 'invalid SHA256')
        require(name not in expected, 'duplicate inventory path')
        expected[name] = digest

    actual = {}
    def visit(directory):
        with os.scandir(directory) as entries:
            for entry in entries:
                path = Path(entry.path)
                info = no_link(path)
                if stat.S_ISDIR(info.st_mode):
                    visit(path)
                else:
                    require(stat.S_ISREG(info.st_mode), 'special files are not permitted')
                    if reject_hardlinks:
                        require(info.st_nlink == 1, 'linked release file is not permitted')
                    actual[path.relative_to(root).as_posix()] = info
    visit(root)
    require(set(actual) == set(expected), 'release files differ from inventory')
    for name, digest in expected.items():
        path = root.joinpath(*name.split('/'))
        flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0)
        with os.fdopen(os.open(path, flags), 'rb') as stream:
            opened = os.fstat(stream.fileno())
            require(stat.S_ISREG(opened.st_mode) and (opened.st_dev, opened.st_ino) ==
                    (actual[name].st_dev, actual[name].st_ino), 'release file changed during verification')
            sha = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                sha.update(block)
            after = os.fstat(stream.fileno())
            if reject_hardlinks:
                require(opened.st_nlink == 1 and after.st_nlink == 1, 'linked release file is not permitted')
            require((opened.st_size, opened.st_mtime_ns) == (after.st_size, after.st_mtime_ns),
                    'release file changed during verification')
        require(sha.hexdigest() == digest, 'release file SHA256 mismatch')
    return ({'status': 'ok', 'files': len(actual), 'root': str(root),
             'manifest': str(manifest), 'manifestSha256': manifest_sha256}, expected)


def check(root, manifest, manifest_sha256):
    return verify(root, manifest, manifest_sha256)[0]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--manifest-sha256', required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(check(args.root, args.manifest, args.manifest_sha256)))
    except (OSError, UnicodeError, ValueError) as error:
        print(json.dumps({'status': 'error', 'error': str(error)}))
        sys.exit(1)
