"""Explicit source adapter for the audited v3/v4 predecessor and portable v5/v6.

Every changed production module must match the exact reviewed AST pair below.
A new statement, encoder replacement or omitted model guard requires a new
reviewed adapter. External receipt SHA, model bytes and vector seals still bind.
"""
import argparse
import ast
import copy
import hashlib
import json
import os
from pathlib import Path

from token_windows import fail
from embedding_compatibility import unique_object

PROTOCOL = 'portable-index-signature-source-adapter-v1'
ENV_PATH = 'CONSENSE_INDEX_SIGNATURE_ADAPTER'
ENV_SHA = ENV_PATH + '_SHA256'
MAX_BYTES = 4 * 1024 * 1024
# The predecessor is 0b475c3; formatting is immaterial, executable AST is exact.
APPROVED_SOURCE_ADAPTERS = {
    "wire_fixture_server.py": [
        "51d960a1e0d2cb84c20bb3d2001bbced7ecf3932435e95498531dd0a9393c975",
        "25b4fde9ccede490358c9c6b788b9cf8fcff29c1a58f2042e006bd5c7421b226"
    ],
    "build_fixed_development_baseline.py": [
        "831639922ef6403df357c16fe66ba3b163c96da018ab44871111448d5f0f07aa",
        "7e8aa76fa02b59828161adb37074be2cf3d351741ee198c1b988bf50138b69dc"
    ],
    "competition_scope.py": [
        "4a1c24b1385ac96027202071f17b3c9ab75125ba15fe5f83380aefbf3b3c9482",
        "61a0be87995c4a6994519e4b222c7b0efac5d95b08ac7339dca8115fb4585927"
    ],
    "complete_ocr.py": [
        "14b967d63fd1e948816ba1198cfa2abb3e19970b37b9218f1fb9c87716e6ff87",
        "f44553137d7d89df9bb7581c4c3b0b785a63b9c72668951698ffd6a9583b43c2"
    ],
    "migrate_qdrant_server.py": [
        "da54cc0299c746b4167ff0103015b3399afa164e4e7802c64ec9866fe39ec3ea",
        "6d467525d9c7612b0e20bc886c8f801ea0a07ce77f33ed40d7151d200fbc31de"
    ],
    "migrated_qdrant_index.py": [
        "a66b75cd0db90495bf8334cd5b3d5025a140a2a6ab97f5b0288f9abfb77d63ba",
        "d42192b47353a5f23a1c4b942d2a665f12a8885cf1426aad93f4d078fafd9e74"
    ],
    "native_pdf_canonical_projection.py": [
        "2da9654a361de3eb86347596847ac2ee2ad052eca473bc88439ed541bd6d39f6",
        "7d361e72198281f70173191aaa381ecf0214bccf880677e192047e5e5f9a594e"
    ],
    "native_provider_lifecycle.py": [
        "9c4e19dbc27abec0f9e7103915dff611f336929c2235875a2fb5c245130adaae",
        "94b2e916ff0c2916bcb6c17166d18c5b7d5248a4f623a6b76079c9d5a1482dbc"
    ],
    "oer_native_pdf_server.py": [
        "edf89ea9095b8ae0a1cfe7631a34e07d0423dcd4ef74b6bf0748e4105883d09d",
        "42b8f1c8d1886c7fabfccd1401d03b7bb747a341305db0084721a5116b16a677"
    ],
    "oer_server.py": [
        "eab515fe3216ba465d98b539572d6bb1c67633afaa899feba93ee1116d63830a",
        "44f5c1f6b897f87960375c4355fcb0b1b7f7370cbc3f38615fa91a654fc88ca9"
    ],
    "paddle_native_server.py": [
        "4b02e40459f10cc7f6c2d2f615ba7864ddd7fae59a95568b2089b9492fe1081d",
        "a6d7b24fee6557947c48e2bbca33ef7ec4986e466a9cc415aff7f3b19fb19704"
    ],
    "retrieval_source_units.py": [
        "200566fa827b229eeadb1830f9709e81dc47f5bfebfbbd122685cfe9449d69b9",
        "9e3ed0b78889d484036af72ccb31278fdb85ebb01a24c83d9ac851f941729574"
    ],
    "server.py": [
        "4959f58c97c1f0e2a045536b7635a260b850d1c26f631aded8aba467246fefb2",
        "0d08bd9cb1f34a1926aee1021cbcc47c75efec8a923bb6e26ff09e52da948f21"
    ],
    "source_read_server.py": [
        "df18ca19742a3bdd691c86162e96e84316c643ee3d61811c06f6429158c15629",
        "71d426bfa9c14bcdaefcae79c5b469ba8c73da0ba0365dc927a49f7fed7f2539"
    ],
    "window_runtime.py": [
        "73561f32797b95251516e27b930f0082b9ea294517ef60473747126ffbe2c8ac",
        "b5638fd25d20a84afb118b6f465f1f9cc9b0e92d6d52f91fc41af4b4b1f7f5c7"
    ]
}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def portable(identity):
    return {k: v for k, v in identity.items() if k != 'resolvedSnapshot'}


def migration_signature_version(stored, current):
    return stored == current and stored in (3, 4, 5, 6) or (stored, current) in ((3, 5), (4, 6))


def comparable(value):
    result = copy.deepcopy(value)
    result.pop('algorithms', None)
    result['signatureVersion'] = {3: 5, 4: 6}.get(result.get('signatureVersion'), result.get('signatureVersion'))
    result['tokenizerAndModelFiles'] = portable(result.get('tokenizerAndModelFiles', {}))
    return result


def legacy_candidate(stored, current):
    return stored.get('signatureVersion') in (3, 4) and comparable(stored) == comparable(current)


def computation_proof(name, before, after):
    def fingerprint(source):
        return sha(ast.dump(ast.parse(source), include_attributes=False).encode('utf-8'))
    pair = [fingerprint(before), fingerprint(after)]
    fail(pair[0] == pair[1] or APPROVED_SOURCE_ADAPTERS.get(name) == pair,
         'INDEX_ADAPTER_COMPUTATION_CHANGED',
         'Exact reviewed predecessor and source adapter required; unreviewed computation or module changes are rejected',
         source=name)


def build(predecessor, current):
    predecessor, current = Path(predecessor), Path(current)
    old = {p.name: p.read_bytes() for p in predecessor.glob('*.py') if not p.name.startswith('test_')}
    new = {p.name: p.read_bytes() for p in current.glob('*.py') if not p.name.startswith('test_')}
    changed = {name: raw.decode('utf-8') for name, raw in old.items()
               if name in new and sha(raw) != sha(new[name])}
    for name, source in changed.items():
        computation_proof(name, source, new[name].decode('utf-8'))
    return {'protocol': PROTOCOL, 'predecessorHashes': {k: sha(v) for k, v in old.items()},
            'currentHashes': {k: sha(v) for k, v in new.items()}, 'predecessorSources': changed,
            'scope': 'explicit audited source adapter only; model bytes, recipe, corpus and actual vector seals remain mandatory'}


def matches(stored, current, source_root, environ=None):
    if stored == current:
        return True
    if not legacy_candidate(stored, current):
        return False
    env = os.environ if environ is None else environ
    path, expected = env.get(ENV_PATH), env.get(ENV_SHA)
    if not path or not expected:
        return False
    with Path(path).open('rb') as handle:
        raw = handle.read(MAX_BYTES + 1)
    fail(len(raw) <= MAX_BYTES, 'INDEX_ADAPTER_TOO_LARGE', 'Signature adapter exceeds its finite byte bound')
    fail(sha(raw) == expected, 'INDEX_ADAPTER_SHA_CHANGED', 'External signature-adapter SHA differs')
    receipt = json.loads(raw, object_pairs_hook=unique_object)
    fail(receipt.get('protocol') == PROTOCOL, 'INDEX_ADAPTER_PROTOCOL', 'Known signature adapter required')
    before, after = receipt['predecessorHashes'], receipt['currentHashes']
    fail(isinstance(stored.get('algorithms'), dict) and bool(stored['algorithms'])
         and isinstance(current.get('algorithms'), dict) and bool(current['algorithms']),
         'INDEX_ADAPTER_DEPENDENCY_CHANGED', 'Complete predecessor and current algorithm identities required')
    for name, digest in stored['algorithms'].items():
        fail(before.get(name) == digest, 'INDEX_ADAPTER_PREDECESSOR_CHANGED', 'Stored algorithm differs from the frozen predecessor', source=name)
        fail(after.get(name) == digest or name in receipt['predecessorSources'],
             'INDEX_ADAPTER_DEPENDENCY_CHANGED', 'Changed indexed dependency requires its reviewed adapter', source=name)
    for name, digest in current['algorithms'].items():
        fail(name in stored['algorithms'] or name == 'portable_index_signature.py',
             'INDEX_ADAPTER_DEPENDENCY_CHANGED', 'New indexed dependency has no predecessor adapter', source=name)
        fail(after.get(name) == digest and Path(name).name == name
             and sha((Path(source_root) / name).read_bytes()) == digest,
             'INDEX_ADAPTER_CURRENT_CHANGED', 'Current source differs from the pinned adapter', source=name)
    for name, source in receipt['predecessorSources'].items():
        fail(Path(name).name == name and sha(source.encode('utf-8')) == before.get(name),
             'INDEX_ADAPTER_PREDECESSOR_CHANGED', 'Embedded predecessor bytes differ')
        new = (Path(source_root) / name).read_bytes()
        fail(sha(new) == after.get(name), 'INDEX_ADAPTER_CURRENT_CHANGED', 'Adapter source changed', source=name)
        computation_proof(name, source, new.decode('utf-8'))
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--predecessor', required=True)
    parser.add_argument('--current', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args(); output = Path(args.output)
    raw = json.dumps(build(args.predecessor, args.current), ensure_ascii=False, sort_keys=True).encode('utf-8')
    fail(len(raw) <= MAX_BYTES, 'INDEX_ADAPTER_TOO_LARGE', 'Signature adapter exceeds its finite byte bound')
    with output.open('xb') as handle:
        handle.write(raw)
    print(json.dumps({'path': str(output.resolve()), 'sha256': sha(raw), 'bytes': len(raw), 'protocol': PROTOCOL}))


if __name__ == '__main__':
    main()
