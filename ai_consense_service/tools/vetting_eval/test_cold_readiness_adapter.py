"""Exact reviewed source adapters over authored signatures; no index or model access."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest

from portable_index_signature import build, computation_proof, matches, sha, ENV_PATH, ENV_SHA
from token_windows import WindowError


@unittest.skipUnless(os.environ.get('CONSENSE_BOOTSTRAP_PREDECESSOR_SOURCE')
    and os.environ.get('CONSENSE_BOOTSTRAP_CURRENT_SOURCE'),
    'requires exact frozen predecessor and candidate software roots; no models or indexes')
class ColdReadinessAdapterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.current_root = Path(os.environ['CONSENSE_BOOTSTRAP_CURRENT_SOURCE']).resolve()
        cls.predecessor_root = Path(os.environ['CONSENSE_BOOTSTRAP_PREDECESSOR_SOURCE'])
        cls.inherited = json.loads((cls.current_root.parent / 'index-signature-adapter.json').read_bytes())
        cls.immediate = build(cls.predecessor_root / 'code', cls.current_root)

    def signature(self, algorithms):
        return {'signatureVersion': 6, 'algorithms': copy.deepcopy(algorithms),
            'tokenizerAndModelFiles': {'files': [{'sha256': 'a' * 64}], 'dtype': 'float32'},
            'corpus': 'b' * 64, 'runtime': {'dtype': 'float32', 'device': 'cuda'},
            'windowRecipe': {'max_tokens': 512, 'structural_tokens': 2048, 'overlap': 64},
            'competitionScope': {'manifestSha256': 'c' * 64}, 'oerTargetModule': 'advisory'}

    def test_inherited_and_immediate_exact_predecessor_domains_keep_model_recipe_and_sources_bound(self):
        for adapter in (self.inherited, self.immediate):
            with self.subTest(inherited=adapter is self.inherited), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'adapter.json'
                raw = json.dumps(adapter, sort_keys=True).encode('utf-8')
                path.write_bytes(raw)
                environment = {ENV_PATH: str(path), ENV_SHA: sha(raw)}
                stored = self.signature(adapter['predecessorHashes'])
                current = self.signature(adapter['currentHashes'])
                self.assertTrue(matches(stored, current, self.current_root, environment))
                for field, changed in (
                        ('corpus', 'd' * 64), ('runtime', {'dtype': 'float16', 'device': 'cuda'}),
                        ('windowRecipe', {'max_tokens': 1024}),
                        ('tokenizerAndModelFiles', {'files': [{'sha256': 'e' * 64}]}),
                        ('competitionScope', {'manifestSha256': 'f' * 64}),
                        ('oerTargetModule', 'vetting')):
                    altered = copy.deepcopy(current)
                    altered[field] = changed
                    self.assertFalse(matches(stored, altered, self.current_root, environment), field)
                changed = copy.deepcopy(stored)
                changed['algorithms']['oer_server.py'] = '0' * 64
                self.assertFalse(matches(changed, current, self.current_root, environment))

    def test_unreviewed_bootstrap_or_model_computation_cannot_receive_a_source_adapter(self):
        before = (self.predecessor_root / 'code/oer_server.py').read_text(encoding='utf-8')
        after = (self.current_root / 'oer_server.py').read_text(encoding='utf-8')
        computation_proof('oer_server.py', before, after)
        with self.assertRaises(WindowError):
            computation_proof('oer_server.py', before, after + '\nUNREVIEWED_BOOTSTRAP_CHANGE = True\n')
        before = 'def encode_embeddings(value): return value\n'
        with self.assertRaises(WindowError):
            computation_proof('server.py', before, 'def encode_embeddings(value): return [0]\n')
