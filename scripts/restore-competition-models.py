#!/usr/bin/env python3
"""Verify and reassemble the competition model files split for Git LFS."""

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import stat

CHUNK = 8 * 1024 * 1024
MAX_PART = 1_800_000_000


def checked_path(value):
    if not isinstance(value, str) or not value or '\\' in value:
        raise ValueError('invalid relative path')
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ('', '.', '..') for part in value.split('/')):
        raise ValueError('invalid relative path: ' + value)
    return path


def validate_file(item):
    if not isinstance(item.get('size'), int) or isinstance(item['size'], bool) or item['size'] < 0:
        raise ValueError('invalid size')
    if not isinstance(item.get('sha256'), str) or not re.fullmatch(r'[0-9a-f]{64}', item['sha256']):
        raise ValueError('invalid SHA256')


def open_parent(root_fd, path):
    fd = os.dup(root_fd)
    try:
        for part in path.parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def hash_fd(fd):
    digest = hashlib.sha256()
    size = 0
    while data := os.read(fd, CHUNK):
        digest.update(data)
        size += len(data)
    return size, digest.hexdigest()


def restore(root, manifest):
    if not hasattr(os, 'O_NOFOLLOW') or not hasattr(os, 'O_DIRECTORY'):
        raise RuntimeError('请在支持 O_NOFOLLOW 的 Linux 系统上运行')
    models = manifest.get('models')
    if not isinstance(models, list) or not models:
        raise ValueError('清单没有模型')
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    seen = set()
    try:
        for model in models:
            validate_file(model)
            target = checked_path(model.get('path'))
            if target in seen:
                raise ValueError('重复模型路径: ' + str(target))
            seen.add(target)
            parts = model.get('parts')
            if not isinstance(parts, list) or not parts:
                raise ValueError('模型没有分片: ' + str(target))
            for index, part in enumerate(parts):
                validate_file(part)
                part_path = checked_path(part.get('path'))
                if part_path != target.with_name(target.name + f'.part-{index:03d}') or not 0 < part['size'] <= MAX_PART:
                    raise ValueError('分片路径、顺序或大小错误: ' + str(part_path))
            if sum(part['size'] for part in parts) != model['size']:
                raise ValueError('分片总大小不符: ' + str(target))

            parent_fd = open_parent(root_fd, target)
            temp_name = '.' + target.name + '.restore-' + secrets.token_hex(8)
            temp_created = False
            try:
                try:
                    existing_fd = os.open(target.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=parent_fd)
                except FileNotFoundError:
                    existing_fd = None
                if existing_fd is not None:
                    try:
                        if hash_fd(existing_fd) != (model['size'], model['sha256']):
                            raise FileExistsError('目标文件已存在且内容不同: ' + str(target))
                    finally:
                        os.close(existing_fd)
                    print('已存在并校验通过:', target)
                    continue

                output_fd = os.open(temp_name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent_fd)
                temp_created = True
                total = 0
                digest = hashlib.sha256()
                try:
                    for part in parts:
                        part_path = checked_path(part['path'])
                        input_fd = os.open(part_path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=parent_fd)
                        try:
                            before = os.fstat(input_fd)
                            if not stat.S_ISREG(before.st_mode) or before.st_size != part['size']:
                                raise ValueError('分片大小或类型不符: ' + str(part_path))
                            part_hash = hashlib.sha256()
                            part_size = 0
                            while data := os.read(input_fd, CHUNK):
                                part_hash.update(data)
                                digest.update(data)
                                part_size += len(data)
                                total += len(data)
                                view = memoryview(data)
                                while view:
                                    view = view[os.write(output_fd, view):]
                            after = os.fstat(input_fd)
                            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or (part_size, part_hash.hexdigest()) != (part['size'], part['sha256']):
                                raise ValueError('分片校验失败: ' + str(part_path))
                        finally:
                            os.close(input_fd)
                    if (total, digest.hexdigest()) != (model['size'], model['sha256']):
                        raise ValueError('模型整体校验失败: ' + str(target))
                    os.fchmod(output_fd, 0o644)
                    os.fsync(output_fd)
                finally:
                    os.close(output_fd)
                os.link(temp_name, target.name, src_dir_fd=parent_fd, dst_dir_fd=parent_fd, follow_symlinks=False)
                print('还原并校验通过:', target)
            finally:
                if temp_created:
                    os.unlink(temp_name, dir_fd=parent_fd)
                os.close(parent_fd)
    finally:
        os.close(root_fd)


def main():
    root = Path(__file__).resolve().parent.parent / 'competition-assets' / 'models'
    with (root / 'split-models.json').open('r', encoding='utf-8') as handle:
        manifest = json.load(handle)
    restore(root, manifest)


if __name__ == '__main__':
    main()
