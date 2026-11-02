import copy
import hashlib
from pathlib import Path
import unittest

import ordinary_predecessor_compat as compat


class PortableReuseCompatibilityTest(unittest.TestCase):
    def test_exact_current_index_remains_readable_and_changed_identity_is_rejected(self):
        root = Path(__file__).resolve().parent
        hashes = {name: hashlib.sha256((root / name).read_bytes()).hexdigest()
                  for name in ('ordinary_predecessor_compat.py', 'same_project_vector_reuse.py', 'window_runtime.py')}
        current = {'signatureVersion': 6, 'oerTargetModule': 'advisory', 'algorithms': hashes}
        stored = copy.deepcopy(current)
        stored['algorithms']['ordinary_predecessor_compat.py'] = '346c0c2c037e4bf096d5e961b15d946ba6e832bbd97710015fd9a8bd4bc7696e'
        stored['algorithms']['same_project_vector_reuse.py'] = '6928823ad33d94c92468c930df0e6b690458aafe58dfcb11ae837a56e22b4026'
        self.assertTrue(compat.compatible(stored, current, root))
        for key in ('ordinary_predecessor_compat.py', 'same_project_vector_reuse.py', 'window_runtime.py'):
            changed = copy.deepcopy(stored)
            changed['algorithms'][key] = '0' * 64
            self.assertFalse(compat.compatible(changed, current, root))
        changed = copy.deepcopy(stored)
        changed['signatureVersion'] = 2
        self.assertFalse(compat.compatible(changed, current, root))
