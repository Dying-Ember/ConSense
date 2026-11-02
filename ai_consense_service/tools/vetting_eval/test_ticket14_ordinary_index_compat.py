"""The reviewed SL routing update must not invalidate ordinary unchanged indexes."""
import copy
import hashlib
from pathlib import Path
import unittest

import ordinary_predecessor_compat as compat


class Ticket14OrdinaryIndexCompatibilityTest(unittest.TestCase):
    def test_only_reviewed_source_changes_preserve_the_index(self):
        root = Path(compat.__file__).resolve().parent
        current = {'signatureVersion': 6, 'corpus': 'same-source', 'runtime': 'same-model',
                   'competitionScope': 'same-policy', 'oerTargetModule': 'advisory',
                   'algorithms': {**compat.TICKET14_CURRENT,
                                  compat.HELPER: hashlib.sha256((root / compat.HELPER).read_bytes()).hexdigest(),
                                  'unchanged.py': 'a' * 64}}
        stored = copy.deepcopy(current)
        stored['algorithms'].update(compat.TICKET14_PREVIOUS)
        self.assertTrue(compat.compatible(stored, current, root))
        for field in ('corpus', 'runtime', 'competitionScope', 'oerTargetModule'):
            changed = copy.deepcopy(stored); changed[field] = 'changed'
            self.assertFalse(compat.compatible(changed, current, root), field)
        for name in (*compat.TICKET14_PREVIOUS, 'unchanged.py'):
            changed = copy.deepcopy(stored); changed['algorithms'][name] = 'f' * 64
            self.assertFalse(compat.compatible(changed, current, root), name)
        changed = copy.deepcopy(current); changed['algorithms']['extra.py'] = 'e' * 64
        self.assertFalse(compat.compatible(stored, changed, root))
        for module in ('vetting', 'drafting'):
            before, after = copy.deepcopy(stored), copy.deepcopy(current)
            before['oerTargetModule'] = after['oerTargetModule'] = module
            self.assertFalse(compat.compatible(before, after, root), module)

    def test_existing_ordinary_index_is_reused_without_reencoding(self):
        from unittest.mock import patch
        from test_source_page_index import SourcePageIndexHttpTests
        fixture = SourcePageIndexHttpTests('test_unpinned_request_preserves_legacy_source_and_echoes_empty_map')
        fixture.setUp(); self.addCleanup(fixture.tearDown)
        signature = fixture.rt.signature
        def advisory_signature(chunks):
            value = signature(chunks)
            value['oerTargetModule'] = 'advisory'
            return value
        def previous(chunks):
            value = copy.deepcopy(advisory_signature(chunks))
            value['algorithms'].update(compat.TICKET14_PREVIOUS)
            return value
        with patch.object(fixture.rt, 'signature', side_effect=previous):
            built = fixture.index()
        self.assertEqual(200, built.status_code, built.text)
        encodes = len(fixture.encodes)
        with patch.object(fixture.rt, 'signature', side_effect=advisory_signature):
            reused = fixture.index()
        self.assertEqual(200, reused.status_code, reused.text)
        self.assertTrue(reused.json()['cached'])
        self.assertEqual(encodes, len(fixture.encodes))


if __name__ == '__main__':
    unittest.main()
