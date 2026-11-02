import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from fixed_gpu_profiles import load_profiles


class ProfileManifestTest(unittest.TestCase):
    def test_pinned_manifest_and_malformed_or_changed_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'profiles.json'
            profiles = {'document-embedding': 'a'*64, 'query-embedding-rerank': 'b'*64,
                        'paddle': 'c'*64}
            raw = json.dumps({'schema':'consense.fixed-gpu-profiles.v1','profiles':profiles}).encode()
            path.write_bytes(raw); pin = hashlib.sha256(raw).hexdigest()
            self.assertEqual({key:profiles[key] for key in ('document-embedding','query-embedding-rerank')},
                             load_profiles(path, pin))
            with self.assertRaisesRegex(ValueError, 'identity differs'): load_profiles(path, '0'*64)
            path.write_bytes(raw + b' ')
            with self.assertRaisesRegex(ValueError, 'identity differs'): load_profiles(path, pin)
            duplicate = b'{"schema":"consense.fixed-gpu-profiles.v1","schema":"other","profiles":{}}'
            path.write_bytes(duplicate)
            with self.assertRaisesRegex(ValueError, 'duplicate'): load_profiles(path, hashlib.sha256(duplicate).hexdigest())

    def test_requires_both_retrieval_stages_and_rejects_unknown_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'profiles.json'
            for profiles in ({'document-embedding':'a'*64},
                             {'document-embedding':'a'*64,'query-embedding-rerank':'X'*64},
                             {'document-embedding':'a'*64,'query-embedding-rerank':'b'*64,'unexpected':'c'*64}):
                raw=json.dumps({'schema':'consense.fixed-gpu-profiles.v1','profiles':profiles}).encode()
                path.write_bytes(raw)
                with self.subTest(profiles=profiles),self.assertRaises(ValueError):
                    load_profiles(path,hashlib.sha256(raw).hexdigest())


if __name__ == '__main__': unittest.main()
