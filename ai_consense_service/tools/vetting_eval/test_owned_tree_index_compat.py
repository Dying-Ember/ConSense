import copy
import hashlib
from pathlib import Path
import shutil
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import ordinary_predecessor_compat as compat


class OwnedTreeIndexCompatibilityTest(unittest.TestCase):
    def test_only_exact_reviewed_release_changes_reuse_old_index(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            source = Path(compat.__file__).parent
            for name in (*compat.CURRENT, compat.HELPER):
                shutil.copyfile(source / name, root / name)
            digests = {name:hashlib.sha256((root / name).read_bytes()).hexdigest()
                       for name in (*compat.CURRENT, compat.HELPER)}
            current = {'signatureVersion':5, 'corpus':'same', 'embedding':'same',
                       'runtime':'same', 'tokenizerAndModelFiles':{'sha':'same'},
                       'competitionScope':'same', 'algorithms':{**digests, 'other.py':'a'*64}}
            old = copy.deepcopy(current)
            old['algorithms'][compat.HELPER] = compat.PREVIOUS_HELPER[-1]
            old['algorithms'].update(compat.RELEASE_PREDECESSORS)
            with patch.object(compat, '__file__', str(root / compat.HELPER)):
                self.assertTrue(compat.compatible(old, current, root))
                for key in ('signatureVersion', 'corpus', 'embedding', 'runtime', 'tokenizerAndModelFiles', 'competitionScope'):
                    changed = copy.deepcopy(old); changed[key] = 'different'
                    self.assertFalse(compat.compatible(changed, current, root), key)
                for name in ('model_session_receipt.py', 'model_session_observer.py', 'other.py'):
                    changed = copy.deepcopy(old); changed['algorithms'][name] = 'f'*64
                    self.assertFalse(compat.compatible(changed, current, root), name)
                target = root / 'model_session_receipt.py'
                target.write_bytes(target.read_bytes() + b'\n# altered\n')
                altered = copy.deepcopy(current)
                altered['algorithms'][target.name] = hashlib.sha256(target.read_bytes()).hexdigest()
                self.assertFalse(compat.compatible(old, altered, root))


if __name__ == '__main__':
    unittest.main()
