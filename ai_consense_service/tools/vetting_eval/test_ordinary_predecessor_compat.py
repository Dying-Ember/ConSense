"""The Source update retains only the reviewed predecessor's ordinary indexes."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import oer_server
import ordinary_predecessor_compat as compat
from test_source_page_index import SourcePageIndexHttpTests


def predecessor(signature):
    result = copy.deepcopy(signature)
    result['algorithms'].pop(compat.HELPER)
    result['algorithms'].update(compat.PREDECESSOR)
    result['algorithms']['server.py'] = compat.PREVIOUS_SERVER[0]
    return result


class OrdinaryPredecessorCompatibilityTest(unittest.TestCase):
    def test_business_only_server_update_keeps_current_index_and_rejects_drift(self):
        root = oer_server.HERE
        files = (*compat.CURRENT, compat.HELPER, 'portable_index_signature.py')
        current = {'signatureVersion': 5, 'corpus': 'same', 'embedding': 'same-model',
                   'runtime': 'same-runtime', 'tokenizerAndModelFiles': 'same-model-files',
                   'competitionScope': 'same-sources', 'oerTargetModule': 'vetting', 'algorithms': {
            **{name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in files},
            'other.py': 'a' * 64}}
        old = copy.deepcopy(current)
        old['algorithms']['server.py'] = 'b0e4f1ee1852fe103ae57cffc1127d8976c84ade2ae5f4b973f8ef4dd0a4e37b'
        old['algorithms']['portable_index_signature.py'] = '77b621514a727e700cca219a0db9787905fb381d65855c510273e1ee79394807'
        old['algorithms'][compat.HELPER] = 'e642c81d4a3a5d1bb46164421678e84824b3e993767ab3375a4d61cd5d9c29ea'
        self.assertTrue(compat.compatible(old, current, root))
        for key in ('signatureVersion', 'corpus', 'embedding', 'runtime', 'tokenizerAndModelFiles',
                    'competitionScope', 'oerTargetModule', 'other.py', 'server.py'):
            changed = copy.deepcopy(old)
            if key in changed:
                changed[key] = 'different'
            else:
                changed['algorithms'][key] = 'f' * 64
            self.assertFalse(compat.compatible(changed, current, root), key)

    def test_existing_index_is_retrieved_and_reused(self):
        f = SourcePageIndexHttpTests('test_unpinned_request_preserves_legacy_source_and_echoes_empty_map')
        f.setUp(); self.addCleanup(f.tearDown)
        current = f.rt.signature
        with patch.object(f.rt, 'signature', side_effect=lambda chunks: predecessor(current(chunks))):
            built = f.index()
        self.assertEqual(200, built.status_code, built.text)
        encodes_before = len(f.encodes)
        result = f.client.post('/retrieve', json={'projectId': 'AUTHORED-pins-index', 'query': 'Visible', 'candidates': 4, 'limit': 2})
        self.assertEqual(200, result.status_code, result.text)
        reused = f.index()
        self.assertEqual(200, reused.status_code, reused.text)
        self.assertTrue(reused.json()['cached'])
        self.assertLessEqual(len(f.encodes) - encodes_before, 1)

    def test_previous_running_source_index_is_retrieved_and_reused(self):
        f = SourcePageIndexHttpTests('test_unpinned_request_preserves_legacy_source_and_echoes_empty_map')
        f.setUp(); self.addCleanup(f.tearDown)
        current = f.rt.signature
        def old_source(chunks):
            result = copy.deepcopy(current(chunks))
            result['algorithms']['server.py'] = compat.PREVIOUS_SERVER[0]
            result['algorithms'][compat.HELPER] = compat.PREVIOUS_HELPER[0]
            return result
        with patch.object(f.rt, 'signature', side_effect=old_source):
            built = f.index()
        self.assertEqual(200, built.status_code, built.text)
        encodes_before = len(f.encodes)
        result = f.client.post('/retrieve', json={'projectId': 'AUTHORED-pins-index', 'query': 'Visible', 'candidates': 4, 'limit': 2})
        self.assertEqual(200, result.status_code, result.text)
        reused = f.index()
        self.assertEqual(200, reused.status_code, reused.text)
        self.assertTrue(reused.json()['cached'])
        self.assertLessEqual(len(f.encodes) - encodes_before, 1)

    def test_versions_and_signature_fields_are_strict_for_lf_and_crlf(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for line_ending in (b'\n', b'\r\n'):
                for name in (*compat.CURRENT, compat.HELPER):
                    raw = (oer_server.HERE / name).read_bytes().replace(b'\r\n', b'\n')
                    (root / name).write_bytes(raw.replace(b'\n', line_ending))
                def hashes():
                    return {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in (*compat.CURRENT, compat.HELPER)}
                current = {'corpus': 'same', 'algorithms': {**hashes(), 'other.py': 'a' * 64}}
                old = predecessor(current)
                with patch.object(compat, '__file__', str(root / compat.HELPER)):
                    with self.subTest(line_ending=line_ending):
                        self.assertTrue(compat.compatible(old, current, root))
                        historical_page = copy.deepcopy(old)
                        historical_page['algorithms']['source_page_index.py'] = compat.PAGE_PREDECESSOR[1]
                        self.assertTrue(compat.compatible(historical_page, current, root))
                        historical_page['algorithms']['source_page_index.py'] = 'f' * 64
                        self.assertFalse(compat.compatible(historical_page, current, root))
                        previous_helper = copy.deepcopy(current)
                        previous_helper['algorithms'][compat.HELPER] = compat.PREVIOUS_HELPER[0]
                        self.assertTrue(compat.compatible(previous_helper, current, root))
                        previous_helper['algorithms'][compat.HELPER] = compat.PREVIOUS_HELPER[2]
                        previous_helper['algorithms']['server.py'] = compat.PREVIOUS_SERVER[0]
                        self.assertTrue(compat.compatible(previous_helper, current, root))
                        previous_helper['algorithms']['source_page_index.py'] = compat.PAGE_PREDECESSOR[1]
                        self.assertFalse(compat.compatible(previous_helper, current, root))
                        for name in (*compat.CURRENT, compat.HELPER):
                            path = root / name
                            original = path.read_bytes()
                            try:
                                path.write_bytes(original + b'\n# changed\n')
                                altered = copy.deepcopy(current)
                                altered['algorithms'][name] = hashlib.sha256(path.read_bytes()).hexdigest()
                                self.assertFalse(compat.compatible(old, altered, root))
                            finally:
                                path.write_bytes(original)
                        for key in ('corpus', 'other.py', 'extra.py', 'old-reader', 'old-wrapper'):
                            before, after = copy.deepcopy(old), copy.deepcopy(current)
                            if key == 'corpus': after[key] = 'changed'
                            elif key == 'old-reader': before['algorithms']['migrated_qdrant_index.py'] = 'f' * 64
                            elif key == 'old-wrapper': before['algorithms']['oer_server.py'] = 'f' * 64
                            else: after['algorithms'][key] = 'f' * 64
                            self.assertFalse(compat.compatible(before, after, root))
            helper = (root / compat.HELPER).read_bytes().replace(b'\r\n', b'\n')
            self.assertFalse(compat._approved_helper(helper.replace(b'\n', b'\r\n', 1)))
