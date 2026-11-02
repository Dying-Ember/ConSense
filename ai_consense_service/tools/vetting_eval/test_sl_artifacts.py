import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from sl_artifacts import write_subset
from sl_vector_copy import copy_and_verify
from test_sl_vector_copy import FakeQdrant
from test_sl_only_subset import fixture
from sl_only_subset import prepare
from token_windows import WindowError


class SlArtifactsTest(unittest.TestCase):
    def test_only_sl_data_written_with_distinct_descriptors(self):
        frozen,source,points=fixture();client=FakeQdrant({'mixed_collection':{p.id:p for p in points}})
        output,manifest=prepare(frozen,source,frozen_sha256='f'*64,source_collection='mixed_collection',
            editions=['v1'],project_id='sl-only',collection='sl_subset')
        metadata,derived,receipt=copy_and_verify(client,FakeQdrant(),source,output,manifest)
        with TemporaryDirectory() as root:
            directory=Path(root)/'new';result=write_subset(directory,metadata,derived,receipt)
            saved=json.loads((directory/'frozen-manifest.json').read_bytes())
            self.assertEqual(['all-applicable-SL-technical'],saved['scope'])
            self.assertEqual(['v1'],[row['editionId'] for row in saved['originalSources']['slSources']])
            self.assertEqual(output['signature']['corpus'],json.loads((directory/'runtime-plan.json').read_bytes())['signature']['corpus'])
            self.assertEqual(5,len(result))
            for path in directory.iterdir():self.assertNotIn(b'GCC-PRIVATE',path.read_bytes())
            with self.assertRaises(WindowError):write_subset(directory,metadata,derived,receipt)
