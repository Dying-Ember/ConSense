"""Transfer isolated, sealed Local index collections to a new Server."""
import json
import os
import stat
import hashlib
import argparse
import math
import time
import re
from pathlib import Path

from qdrant_client import models

from fixed_development_baseline_runtime import desc
from index_integrity import verify_reuse
from migrate_qdrant_server import read_points, require
from sealed_local_stream import ColdLocalReader
from token_windows import digest

_IMPORTED_SOURCE_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def _deadline(max_seconds):
    require(type(max_seconds) in (int, float) and math.isfinite(max_seconds) and max_seconds > 0,
            'Finite deadline required')
    deadline = time.monotonic() + max_seconds
    def checkpoint():
        require(time.monotonic() < deadline, 'Operation deadline expired')
    return checkpoint


def _pinned_json(path, expected, checkpoint=lambda: None):
    path = Path(path)
    checkpoint()
    require(path.is_absolute() and not path.is_symlink() and path.resolve() == path, 'Pinned input path is aliased')
    raw = path.read_bytes()
    checkpoint()
    require(hashlib.sha256(raw).hexdigest() == expected, 'Pinned input SHA differs: ' + path.name)
    checkpoint(); value = json.loads(raw); checkpoint()
    return value


def _unique(rows, message):
    require(len(rows) == 1, message)
    return rows[0]


def derive_single_collection_meta(source_bytes, source_sha256, collection):
    """Derive only Local metadata; no collection is transferred or adopted."""
    require(type(source_bytes) is bytes and 0 < len(source_bytes) <= 16 * 1024 * 1024,
            'Bounded original Local metadata bytes required')
    require(isinstance(source_sha256, str) and len(source_sha256) == 64
            and hashlib.sha256(source_bytes).hexdigest() == source_sha256,
            'Original Local metadata SHA differs')
    require(isinstance(collection, str) and collection not in ('', '.', '..')
            and '/' not in collection and '\\' not in collection, 'Invalid selected collection')

    def unique_object(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, 'Duplicate Local metadata JSON key')
            value[key] = item
        return value

    def invalid_constant(value):
        raise ValueError('Non-finite Local metadata number: ' + value)

    metadata = json.loads(source_bytes, object_pairs_hook=unique_object, parse_constant=invalid_constant)
    require(type(metadata) is dict and set(metadata) == {'collections', 'aliases'}
            and type(metadata['collections']) is dict and bool(metadata['collections'])
            and type(metadata['aliases']) is dict and not metadata['aliases'],
            'Complete Local collections and empty aliases required')
    collections = metadata['collections']
    require(all(isinstance(name, str) and name not in ('', '.', '..')
                and '/' not in name and '\\' not in name and type(config) is dict
                for name, config in collections.items()) and collection in collections,
            'Selected collection missing or Local collection metadata invalid')
    derived = json.dumps({'collections': {collection: collections[collection]}, 'aliases': {}},
                         ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
    evidence = {'schema': 'consense.single-collection-local-metadata-derivation.v1',
                'status': 'derived_metadata_only_not_migrated',
                'originalSha256': source_sha256, 'originalBytes': len(source_bytes),
                'originalCollections': sorted(collections), 'selectedCollection': collection,
                'excludedCollections': sorted(name for name in collections if name != collection),
                'derivedSha256': hashlib.sha256(derived).hexdigest(), 'derivedBytes': len(derived),
                'selectedConfigurationSha256': digest(collections[collection])}
    return derived, evidence


def verify_single_collection_meta(source_bytes, source_sha256, collection, derived_bytes, evidence):
    expected_bytes, expected_evidence = derive_single_collection_meta(source_bytes, source_sha256, collection)
    require(type(derived_bytes) is bytes and derived_bytes == expected_bytes and type(evidence) is dict
            and evidence == expected_evidence and digest(evidence) == digest(expected_evidence),
            'Derived Local metadata or provenance differs')


def _checked_file(path, expected, checkpoint=lambda: None):
    path = Path(path)
    checkpoint()
    require(path.is_absolute() and not path.is_symlink() and path.resolve() == path, 'Registered file path is aliased')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        named = path.stat(follow_symlinks=False)
        def identity(info):
            result = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_nlink)
            return result + ((info.st_ctime_ns,) if os.name != 'nt' else ())
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and identity(before) == identity(named),
                'Registered file identity differs')
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            checkpoint()
            digest.update(block)
        checkpoint()
        after = os.fstat(stream.fileno())
        require(identity(before) == identity(after) == identity(path.stat(follow_symlinks=False))
                and before.st_ctime_ns == after.st_ctime_ns,
                'Registered file changed while reading')
    require(before.st_size == expected['bytes'] and digest.hexdigest() == expected['sha256'],
            'Registered file bytes differ')
    return {'path': str(path), 'bytes': before.st_size, 'sha256': digest.hexdigest(),
            'device': before.st_dev, 'inode': before.st_ino}


def load_single_inputs(register_path, register_sha, amendment_path, amendment_sha,
                       phase_a_path, phase_a_sha, phase_b_path, phase_b_sha, number, checkpoint=lambda: None):
    """Verify one registered import without opening Qdrant or changing any file."""
    require(type(number) is int and 1 <= number <= 5, 'One registered collection number required')
    register = _pinned_json(register_path, register_sha, checkpoint)
    amendment = _pinned_json(amendment_path, amendment_sha, checkpoint)
    phase_a = _pinned_json(phase_a_path, phase_a_sha, checkpoint)
    phase_b = _pinned_json(phase_b_path, phase_b_sha, checkpoint)
    require(amendment['originalRegisterSha256'] == register_sha and phase_a['state'] == 'COPIED_UNVERIFIED',
            'Registration or phase A identity differs')
    row = _unique([r for r in phase_a['files'] if r.get('kind') == 'collection' and r.get('number') == number],
                  'Collection must occur exactly once')
    collection = row['collection']; module = 'vetting' if number == 1 else 'advisory'
    require(phase_b['number'] == number and phase_b['status'] == 'PASS'
            and phase_b['metadataSha256'] == row['metadata']['sha256']
            and phase_b['sqliteSha256'] == row['sqlite']['sha256'], 'Phase B identity differs')
    generation = _unique([r for r in register['mappings'] if r.get('kind') == 'index-generation'
                          and r['identity']['collection'] == collection], 'Registered generation is ambiguous')
    require(generation['identity']['metadata'] == row['metadata']
            and generation['identity']['sqlite']['sha256'] == row['sqlite']['sha256']
            and generation['identity']['sqlite']['bytes'] == row['sqlite']['bytes'], 'Generation identity differs')
    metadata = Path(generation['futureWritableTarget']); root = metadata.parent
    sqlite_target = root / collection / 'storage.sqlite'; meta_target = root / 'qdrant-meta.json'
    sqlite_correction = _unique([r for r in amendment['corrections'] if r.get('kind') == 'index-sqlite'
                                 and r.get('correctedTarget') == str(sqlite_target)], 'SQLite correction is ambiguous')
    require(sqlite_correction.get('matchedMetadataTarget') == str(metadata), 'Corrected SQLite metadata binding differs')
    meta_correction = _unique([r for r in amendment['corrections'] if r.get('kind') == 'qdrant-meta'
                               and r.get('correctedTarget') == str(meta_target)], 'Qdrant metadata correction is ambiguous')
    qmeta = _unique([r for r in phase_a['files'] if r.get('kind') == 'qdrant-meta' and r.get('module') == module],
                    'Qdrant metadata identity is ambiguous')
    for correction, kind, expected in ((sqlite_correction, 'index-sqlite', row['sqlite']),
                                        (meta_correction, 'qdrant-meta', qmeta)):
        index = correction['mappingIndex']
        require(type(index) is int and 0 <= index < len(register['mappings']), 'Correction index invalid')
        original = register['mappings'][index]
        require(original['kind'] == kind and original['targetLinux'] == correction['baselineTarget']
                and original.get('futureWritableTarget') == correction['priorTarget']
                and correction['sha256'] == expected['sha256'] == original['identity']['sha256']
                and original['identity']['bytes'] == expected['bytes'], 'Correction identity differs')
    result = {'number': number, 'module': module, 'collection': collection,
              'metadata': _checked_file(metadata, row['metadata'], checkpoint),
              'sqlite': _checked_file(sqlite_target, row['sqlite'], checkpoint),
              'qdrantMeta': _checked_file(meta_target, qmeta, checkpoint), 'phaseB': phase_b}
    if number != 1:
        result['registration'] = [str(register_path), register_sha, str(amendment_path), amendment_sha,
                                  str(phase_a_path), phase_a_sha, str(phase_b_path), phase_b_sha, number]
    return result


def _recheck_advisory_inputs(inputs, checkpoint):
    registration = inputs.get('registration')
    require(type(registration) is list and len(registration) == 9 and inputs.get('number') in (2, 3, 4, 5),
            'Pinned Advisory registration required')
    require(load_single_inputs(*registration, checkpoint=checkpoint) == inputs,
            'Registered Advisory identity changed')


def verify_runtime_tree(inputs, local_state, checkpoint=lambda: None):
    """Accept only an independently prepared one-collection Local working tree."""
    checkpoint(); root = Path(local_state)
    require(root.is_absolute() and root.is_dir() and not root.is_symlink() and root.resolve() == root,
            'Local working tree is absent or aliased')
    collection = inputs['collection']
    require(collection and collection not in ('.', '..') and '/' not in collection and '\\' not in collection,
            'Collection path is invalid')
    for item in ('sqlite', 'qdrantMeta'):
        registered = Path(inputs[item]['path'])
        require(not registered.is_relative_to(root) and not root.is_relative_to(registered.parent),
                'Local working tree must be separate from registered copies')
    derived = inputs.get('number') in (2, 3, 4, 5)
    if derived:
        _recheck_advisory_inputs(inputs, checkpoint)
    require({p.name for p in root.iterdir()} == ({'meta.json', 'original-meta.json', 'collection'} if derived
                                                else {'meta.json', 'collection'}), 'Local working tree has unexpected state')
    collections = root / 'collection'
    require({p.name for p in collections.iterdir()} == {collection}, 'Local working tree must contain one collection')
    directory = collections / collection
    require({p.name for p in directory.iterdir()} == {'storage.sqlite'}, 'Local collection has unexpected state')
    if derived:
        original_meta = _checked_file(root / 'original-meta.json', inputs['qdrantMeta'], checkpoint)
        require((original_meta['device'], original_meta['inode']) !=
                (inputs['qdrantMeta']['device'], inputs['qdrantMeta']['inode']), 'Local metadata aliases registered file')
        original = (root / 'original-meta.json').read_bytes(); checkpoint()
        raw, evidence = derive_single_collection_meta(original, inputs['qdrantMeta']['sha256'], collection)
        actual_meta = _checked_file(root / 'meta.json', {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}, checkpoint)
        verify_single_collection_meta(original, inputs['qdrantMeta']['sha256'], collection,
                                      (root / 'meta.json').read_bytes(), evidence)
    else:
        actual_meta = _checked_file(root / 'meta.json', inputs['qdrantMeta'], checkpoint)
    actual_sqlite = _checked_file(directory / 'storage.sqlite', inputs['sqlite'], checkpoint)
    for actual, original in ((actual_meta, inputs['qdrantMeta']), (actual_sqlite, inputs['sqlite'])):
        require((actual['device'], actual['inode']) != (original['device'], original['inode']),
                'Local working tree aliases a registered file')
    meta = _pinned_json(root / 'meta.json', actual_meta['sha256'], checkpoint)
    require(set(meta) == {'collections', 'aliases'} and set(meta['collections']) == {collection} and not meta['aliases'],
            'Single-collection Local metadata is required; derived metadata is not supported')
    result = {'path': str(root), 'meta': actual_meta, 'sqlite': actual_sqlite}
    if derived:
        result.update(originalMeta=original_meta, metadataDerivation=evidence)
    return result


def _sync_directory(path):
    if os.name == 'posix':
        fd = os.open(path, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
        try: os.fsync(fd)
        finally: os.close(fd)


def prepare_runtime_tree(inputs, local_state, max_copy_bytes, max_seconds, *, checkpoint=None):
    """Prepare fresh Local files; never open a registered file with write access."""
    require(inputs['number'] in (1, 2, 3, 4, 5), 'One registered collection number required')
    derived = inputs['number'] != 1
    require(type(max_copy_bytes) is int and max_copy_bytes > 0 and type(max_seconds) in (int,float)
            and math.isfinite(max_seconds) and max_seconds > 0
            and inputs['sqlite']['bytes'] + inputs['qdrantMeta']['bytes'] <= max_copy_bytes,
            'Finite copy byte budget exceeded')
    checkpoint = checkpoint or _deadline(max_seconds)
    checkpoint()
    if derived:
        _recheck_advisory_inputs(inputs, checkpoint)
    root = Path(local_state)
    require(root.is_absolute() and root.parent.is_dir() and root.parent.resolve() == root.parent
            and not root.exists() and not root.is_symlink(), 'Fresh Local working tree required')
    collection = inputs['collection']
    require(collection and collection not in ('.', '..') and '/' not in collection and '\\' not in collection,
            'Collection path is invalid')
    for name in ('sqlite', 'qdrantMeta'):
        source = Path(inputs[name]['path'])
        require(not root.is_relative_to(source.parent) and not source.is_relative_to(root),
                'Local working tree must be separate from registered copies')
    if derived:
        require(0 < inputs['qdrantMeta']['bytes'] <= 16 * 1024 * 1024, 'Bounded original Local metadata bytes required')
        original = Path(inputs['qdrantMeta']['path']).read_bytes(); checkpoint()
        derived_meta, _ = derive_single_collection_meta(original, inputs['qdrantMeta']['sha256'], collection)
        require(inputs['sqlite']['bytes'] + inputs['qdrantMeta']['bytes'] + len(derived_meta) <= max_copy_bytes,
                'Finite copy byte budget exceeded')
    else:
        meta = _pinned_json(inputs['qdrantMeta']['path'], inputs['qdrantMeta']['sha256'], checkpoint)
        require(set(meta) == {'collections', 'aliases'} and set(meta['collections']) == {collection} and not meta['aliases'],
                'Single-collection Local metadata is required; derived metadata is not supported')
    checkpoint();root.mkdir(mode=0o700); _sync_directory(root.parent)
    directory = root / 'collection'; directory.mkdir(mode=0o700)
    target_dir = directory / collection; target_dir.mkdir(mode=0o700)
    _sync_directory(root); _sync_directory(directory)
    for item, target in ((inputs['qdrantMeta'], root/('original-meta.json' if derived else 'meta.json')),
                         (inputs['sqlite'], target_dir/'storage.sqlite')):
        checkpoint()
        checked = _checked_file(item['path'], item, checkpoint)
        checkpoint()
        require((checked['device'], checked['inode']) == (item['device'], item['inode']), 'Registered file identity changed')
        source = Path(item['path']); partial = target.with_name(target.name+'.partial')
        fd = os.open(source, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        try: output = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os,'O_NOFOLLOW',0),0o600)
        except BaseException:
            os.close(fd);raise
        with os.fdopen(fd, 'rb') as src, os.fdopen(output, 'wb') as dst:
            before = os.fstat(src.fileno())
            require((before.st_dev, before.st_ino, before.st_size, before.st_nlink)
                    == (item['device'], item['inode'], item['bytes'], 1), 'Registered source identity changed')
            sha = hashlib.sha256(); count = 0
            for block in iter(lambda: src.read(1024 * 1024), b''):
                checkpoint()
                count += len(block); require(count <= item['bytes'], 'Registered source exceeded size')
                sha.update(block); dst.write(block)
            dst.flush(); os.fsync(dst.fileno())
            after = os.fstat(src.fileno()); named = source.stat(follow_symlinks=False)
            require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                    == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns)
                    and (after.st_dev, after.st_ino) == (named.st_dev, named.st_ino), 'Registered source changed')
        require(count == item['bytes'] and sha.hexdigest() == item['sha256'], 'Registered source bytes changed')
        staged = _checked_file(partial, item, checkpoint)
        checkpoint()
        require((staged['device'], staged['inode']) != (item['device'], item['inode']), 'Copied file aliases source')
        os.link(partial, target); os.unlink(partial); _sync_directory(target.parent)
        _checked_file(target, item, checkpoint)
    if derived:
        checkpoint()
        fd = os.open(root/'meta.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        with os.fdopen(fd, 'wb') as stream:
            stream.write(derived_meta); stream.flush(); os.fsync(stream.fileno())
        _sync_directory(root)
    checkpoint();result=verify_runtime_tree(inputs, root, checkpoint);checkpoint()
    return result


def load_inputs(root, phase_a_sha, phase_b_shas):
    root = Path(root).resolve()
    require(len(phase_b_shas) == 5, 'Five externally pinned Phase B receipts required')
    phase_a = root / 'phase-a-receipt.private.json'
    require(desc(phase_a)['sha256'] == phase_a_sha, 'Phase A receipt SHA differs')
    recorded = json.loads(phase_a.read_bytes())
    require(recorded['state'] == 'COPIED_UNVERIFIED', 'Unexpected Phase A state')
    rows = [r for r in recorded['files'] if r['kind'] == 'collection']
    require(len(rows) == 5 and {r['number'] for r in rows} == set(range(1, 6)),
            'Exactly five distinct recorded collections required')
    require(len({r['collection'] for r in rows}) == 5, 'Collection names must be distinct')
    result = []
    for row in sorted(rows, key=lambda r: r['number']):
        number = row['number']
        phase_b = root / 'phase-b' / str(number) / 'result.private.json'
        require(desc(phase_b)['sha256'] == phase_b_shas[number-1], 'Phase B receipt SHA differs')
        check = json.loads(phase_b.read_bytes())
        require(check['number'] == number and check['status'] == 'PASS'
                and check['metadataSha256'] == row['metadata']['sha256']
                and check['sqliteSha256'] == row['sqlite']['sha256'], 'Phase B identity differs')
        for name, expected in (('metadata.json', row['metadata']), ('storage.sqlite', row['sqlite'])):
            path = (root / str(number) / name).resolve()
            require(path.is_relative_to(root), 'Source file escapes sealed copy')
            actual = desc(path)
            require(actual['sha256'] == expected['sha256'] and actual['bytes'] == expected['bytes'],
                    'Sealed input bytes differ')
        module = 'vetting' if number == 1 else 'advisory'
        result.append({'row': row, 'check': check, 'module': module})
    for module in ('vetting', 'advisory'):
        expected = [r for r in recorded['files'] if r['kind'] == 'qdrant-meta' and r['module'] == module]
        require(len(expected) == 1, 'Missing module Qdrant metadata identity')
        path = (root / (module + '-qdrant-meta.json')).resolve()
        require(path.is_relative_to(root), 'Module metadata escapes sealed copy')
        actual = desc(path)
        require(actual['sha256'] == expected[0]['sha256'] and actual['bytes'] == expected[0]['bytes'],
                'Module Qdrant metadata bytes differ')
    return result


def _target_collection(source, target):
    if target is None:
        return source
    require(isinstance(target, str) and re.fullmatch(r'ticket03_stage_[0-9a-f]{32}', target),
            'Unique staging target collection required')
    return target


def migrate_collection(source, target, metadata, batch_size, checkpoint=lambda: None, *, target_collection=None):
    require(1 <= batch_size <= 256, 'Batch size must be 1..256')
    key, collection = metadata['projectId'], metadata['collection']
    destination = _target_collection(collection, target_collection)
    checkpoint()
    require(not target.collection_exists(destination), 'Target collection already exists')
    original = verify_reuse(source, metadata, key=key, collection=collection,
                            chunks=metadata['chunks'], signature=metadata['signature'])
    checkpoint()
    target.create_collection(destination, vectors_config=models.VectorParams(
        size=original['dimension'], distance=models.Distance.COSINE))
    checkpoint()
    for records in read_points(source, collection, original['pointCount'], batch_size):
        checkpoint()
        target.upsert(destination, points=[models.PointStruct(id=p.id, vector=p.vector, payload=p.payload)
                                         for p in records], wait=True)
        checkpoint()
    checkpoint()
    target_metadata = {**metadata, 'collection': destination}
    actual = verify_reuse(target, target_metadata, key=key, collection=destination,
                          chunks=metadata['chunks'], signature=metadata['signature'])
    checkpoint()
    require(actual == original, 'Server vector seal differs from the source cold seal')
    return {'source': original, 'target': actual}


def transfer_prepared(inputs, local_state, metadata, target_factory, record, batch_size, max_seconds, max_points, *, target_collection=None, checkpoint=None):
    """One bounded, sealed candidate; external supervision supplies the hard deadline."""
    require(inputs['number'] == 1, 'Only the first Vetting collection is approved')
    require(type(max_points) is int and max_points > 0 and type(max_seconds) in (int, float)
            and math.isfinite(max_seconds) and max_seconds > 0 and 1 <= batch_size <= 256,
            'Finite transfer budgets are required')
    destination = _target_collection(inputs['collection'], target_collection)
    if target_collection is not None:
        write_record = record
        def record(value):
            write_record({**value, 'targetCollection': destination})
    checkpoint = checkpoint or _deadline(max_seconds)
    source = target = None; failure = None; result = None; target_may_exist = False; close_failed = []
    record({'status':'running','collection':inputs['collection'],'targetMayExist':False})
    try:
        checkpoint();verify_runtime_tree(inputs,local_state,checkpoint)
        cold=inputs['phaseB'];seal=metadata['vectorIntegrity']
        require(metadata['collection'] == inputs['collection'] and seal['sealSha256'] == cold['sealSha256']
                and seal['sealSha256'] == digest({k:v for k,v in seal.items() if k != 'sealSha256'})
                and seal['pointCount'] == cold['pointCount'] and seal['dimension'] == cold['dimension']
                and 'persistedPointsSha256' in cold['matchedRecordedValues']
                and type(seal['pointCount']) is int and 0 < seal['pointCount'] <= max_points,
                'Cold seal identity or point budget differs')
        checkpoint();source=ColdLocalReader(local_state,inputs['collection'],checkpoint)
        original=verify_reuse(source,metadata,key=metadata['projectId'],collection=inputs['collection'],
                              chunks=metadata['chunks'],signature=metadata['signature'])
        require(original['pointsSha256'] == seal['persistedPointsSha256'], 'Source cold vector seal differs')
        checkpoint()
        record({'status':'transfer_intent','collection':inputs['collection'],'targetMayExist':True})
        target_may_exist=True
        checkpoint();target=target_factory();checkpoint()
        result=migrate_collection(source,target,metadata,batch_size,checkpoint,target_collection=target_collection)
        require(result['source'] == original, 'Source seal changed during transfer')
    except BaseException as error:
        failure=error
    finally:
        for name,client in (('source',source),('target',target)):
            if client is not None:
                try:client.close()
                except BaseException as error:
                    close_failed.append(name)
                    if failure is None:failure=error
    if failure is not None:
        receipt={'status':'failed','collection':inputs['collection'],'targetMayExist':target_may_exist,
                 'errorType':type(failure).__name__}
        if close_failed:receipt['closeFailedClients']=close_failed
        record(receipt)
        raise failure
    try:
        checkpoint()
        record({'status':'completed_candidate_not_adopted','collection':inputs['collection'],
                'source':result['source'],'target':result['target'],'businessAccepted':False})
        checkpoint()
    except BaseException as error:
        record({'status':'failed','collection':inputs['collection'],'targetMayExist':target_may_exist,
                'errorType':type(error).__name__})
        raise
    return result


def transfer_with_journal(inputs, local_state, target_factory, out, batch_size, max_seconds, max_points, *, target_collection=None, target_url=None, checkpoint=None):
    """Bind registered metadata and retain an exclusive, synchronized transfer journal."""
    destination = _target_collection(inputs['collection'], target_collection)
    require((target_collection is None and target_url is None)
            or (target_collection is not None and type(target_url) is str
                and target_url == 'http://127.0.0.1:28683'),
            'Registered loopback Server required')
    require(checkpoint is None or callable(checkpoint), 'Checkpoint must be callable')
    deadline = _deadline(max_seconds)
    if checkpoint is None:
        checkpoint = deadline
    else:
        external_checkpoint = checkpoint
        def checkpoint():
            deadline()
            external_checkpoint()
            deadline()
    out=Path(out);local=Path(local_state)
    require(out.is_absolute() and out.parent.is_dir() and out.parent.resolve() == out.parent
            and not out.exists() and not out.is_symlink(), 'A fresh absolute receipt directory is required')
    require(local.is_absolute() and not out.is_relative_to(local) and not local.is_relative_to(out),
            'Receipt and Local working tree must be separate')
    for name in ('metadata','sqlite','qdrantMeta'):
        protected=Path(inputs[name]['path'])
        require(protected.is_absolute() and not out.is_relative_to(protected.parent)
                and not protected.is_relative_to(out), 'Receipt overlaps a registered index directory')
    out.mkdir(mode=0o700);_sync_directory(out.parent)
    fd=os.open(out/'migration-receipt.jsonl',os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
    with os.fdopen(fd,'w',encoding='utf-8') as receipt:
        _sync_directory(out)
        last_status=None; target_may_exist=False
        def record(value):
            nonlocal last_status, target_may_exist
            if target_collection is not None:
                value = {**value, 'targetCollection': destination, 'targetUrl': target_url}
            receipt.write(json.dumps(value,ensure_ascii=False)+'\n');receipt.flush();os.fsync(receipt.fileno())
            last_status=value['status']
            target_may_exist = target_may_exist or value.get('targetMayExist') is True
        record({'status':'initializing','collection':inputs['collection'],'targetMayExist':False})
        try:
            checkpoint();expected=inputs['metadata'];before=_checked_file(expected['path'],expected,checkpoint)
            require((before['device'],before['inode']) == (expected['device'],expected['inode']),
                    'Registered metadata identity changed')
            metadata=_pinned_json(expected['path'],expected['sha256'],checkpoint)
            after=_checked_file(expected['path'],expected,checkpoint)
            require((after['device'],after['inode']) == (before['device'],before['inode']),
                    'Registered metadata identity changed')
            record({'status':'metadata_verified','collection':inputs['collection'],
                    'metadataSha256':expected['sha256'],'targetMayExist':False})
            checkpoint()
            return transfer_prepared(inputs,local,metadata,target_factory,record,batch_size,max_seconds,max_points,
                                     target_collection=target_collection,checkpoint=checkpoint)
        except BaseException as error:
            if last_status != 'failed':
                record({'status':'failed','collection':inputs['collection'],
                        'targetMayExist':target_may_exist,'errorType':type(error).__name__})
            raise


def server_target(url, request_timeout):
    """Prepare a factory for the explicitly registered loopback Server only."""
    require(url == 'http://127.0.0.1:28683', 'Only the registered loopback Server is allowed')
    require(type(request_timeout) in (int,float) and math.isfinite(request_timeout) and request_timeout > 0,
            'Finite Server request timeout required')
    def connect():
        from qdrant_client import QdrantClient
        return QdrantClient(url=url,timeout=request_timeout)
    return connect


def main():
    parser = argparse.ArgumentParser(description='Prepare or validate one registered index; Server transfer remains blocked')
    parser.add_argument('--number', type=int, required=True)
    for name in ('register', 'amendment', 'phase-a', 'phase-b'):
        parser.add_argument('--'+name, required=True)
        parser.add_argument('--'+name+'-sha256', required=True)
    parser.add_argument('--local-state', required=True)
    parser.add_argument('--out', required=True)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--max-copy-bytes',type=int)
    parser.add_argument('--max-seconds',type=float)
    parser.add_argument('--transfer', action='store_true')
    args = parser.parse_args()
    checkpoint = _deadline(args.max_seconds) if args.prepare else lambda: None
    out = Path(args.out)
    require(out.is_absolute() and out.parent.is_dir() and out.parent.resolve() == out.parent
            and not out.exists() and not out.is_symlink(), 'A fresh absolute receipt directory is required')
    local = Path(args.local_state)
    require(local.is_absolute() and not out.is_relative_to(local) and not local.is_relative_to(out),
            'Receipt and Local working tree must be separate')
    try: registered = _pinned_json(args.register,args.register_sha256,checkpoint)
    except (OSError,ValueError,TypeError):
        checkpoint()
        registered = None
    if registered is not None:
        for row in registered.get('mappings',[]):
            if row.get('kind') not in ('index-generation','index-sqlite','qdrant-meta'):continue
            for key in ('targetLinux','futureWritableTarget'):
                value=row.get(key)
                if not value:continue
                protected=Path(value).parent
                require(not out.is_relative_to(protected) and not local.is_relative_to(protected),
                        'Receipt or Local working tree overlaps a registered index directory')
    out.mkdir(mode=0o700);_sync_directory(out.parent)
    with (out/'migration-receipt.jsonl').open('x', encoding='utf-8') as receipt:
        def record(value):
            receipt.write(json.dumps(value, ensure_ascii=False)+'\n');receipt.flush();os.fsync(receipt.fileno())
        _sync_directory(out)
        record({'status':'running','number':args.number,'serverConnected':False})
        try:
            require(not args.transfer, 'Server transfer is blocked until runtime admission is implemented')
            inputs = load_single_inputs(args.register,args.register_sha256,args.amendment,args.amendment_sha256,
                                        args.phase_a,args.phase_a_sha256,args.phase_b,args.phase_b_sha256,args.number,checkpoint)
            require(inputs['number'] == 1, 'Only the first Vetting collection is approved')
            metadata = _pinned_json(inputs['metadata']['path'], inputs['metadata']['sha256'],checkpoint)
            cold = inputs['phaseB'];seal = metadata['vectorIntegrity']
            require(metadata['collection'] == inputs['collection'] and seal['sealSha256'] == cold['sealSha256']
                    and seal['sealSha256'] == digest({k:v for k,v in seal.items() if k != 'sealSha256'})
                    and seal['pointCount'] == cold['pointCount'] and seal['dimension'] == cold['dimension']
                    and 'persistedPointsSha256' in cold['matchedRecordedValues'], 'Cold seal identity differs')
            runtime = (prepare_runtime_tree(inputs,local,args.max_copy_bytes,args.max_seconds,checkpoint=checkpoint) if args.prepare
                       else verify_runtime_tree(inputs,local))
            checkpoint()
            record({'status':'prepared_not_migrated' if args.prepare else 'validated_not_migrated',
                    'number':args.number,'collection':inputs['collection'],
                    'metadata':inputs['metadata'],'sqlite':inputs['sqlite'],'runtime':runtime,
                    'serverConnected':False,'businessAccepted':False})
            checkpoint()
            return 0
        except BaseException as error:
            record({'status':'failed','number':args.number,'serverConnected':False,
                    'error':{'type':type(error).__name__,'message':str(error)}})
            return 1


if __name__ == '__main__':
    raise SystemExit(main())
