"""Exact canonical bytes with parent-sized encoding, without a full array copy."""
import hashlib
import json
import math
import unittest
from unittest.mock import patch

import normal_observed_projection as normal
import token_windows


class CanonicalDigestWorkloadTests(unittest.TestCase):
    def parents(self):
        return [{'id': str(index), 'content': 'Visible contract text. ' * 100,
                 'parts': [{'page': part, 'text': 'Clause context.', 'bounds': [part, part + 1]}
                           for part in range(15)]} for index in range(20)]

    def test_normal_parent_array_uses_one_encoded_part_per_parent(self):
        parents = self.parents()
        parts = list(normal._canonical_chunks(parents))
        self.assertEqual(2 * len(parents) + 1, len(parts))
        self.assertEqual(json.dumps(parents, ensure_ascii=False, sort_keys=True,
                                   separators=(',', ':'), allow_nan=False).encode(), b''.join(parts))

    def test_window_digest_never_encodes_the_complete_parent_array(self):
        parents = self.parents()
        expected = hashlib.sha256(json.dumps(parents, ensure_ascii=False, sort_keys=True,
                                            separators=(',', ':'), allow_nan=False).encode()).hexdigest()
        encode = json.JSONEncoder.encode
        def guarded(encoder, value):
            self.assertFalse(value is parents, 'The complete parent array must not be encoded into one string.')
            return encode(encoder, value)
        with patch.object(json.JSONEncoder, 'encode', guarded):
            self.assertEqual(expected, token_windows.digest(parents))

    def test_canonical_bytes_keep_nested_unicode_escaping_floats_and_empty_values(self):
        cases = [[], (), {}, [None, False, True, 0, -1, 1.5, -0.0, 1e-7],
                 [{'text': 'Quotes " newline\n tab\t slash / \\ \u00e9 \U0001f600', 'empty': []}],
                 {'nested': [[{'b': 2, 'a': 1}], []], 'nothing': None}]
        for value in cases:
            with self.subTest(value=value):
                expected = json.dumps(value, ensure_ascii=False, sort_keys=True,
                                      separators=(',', ':'), allow_nan=False).encode()
                self.assertEqual(expected, b''.join(normal._canonical_chunks(value)))
                self.assertEqual(hashlib.sha256(expected).hexdigest(), token_windows.digest(value))

    def test_nonfinite_and_circular_values_still_fail(self):
        circular = []; circular.append(circular)
        for value in ([math.nan], [math.inf], circular):
            with self.subTest(value=repr(value)):
                with self.assertRaises(ValueError):
                    token_windows.digest(value)
                with self.assertRaises(ValueError):
                    list(normal._canonical_chunks(value))

    def test_mutating_a_parent_is_seen_by_every_new_digest(self):
        parents = self.parents()
        original = token_windows.digest(parents)
        parents[0]['parts'][0]['text'] += ' Changed obligation.'
        changed = token_windows.digest(parents)
        self.assertNotEqual(original, changed)
        self.assertEqual(changed, hashlib.sha256(b''.join(normal._canonical_chunks(parents))).hexdigest())

    def test_single_window_dict_keeps_the_existing_c_encoder_path(self):
        value = {'id': 'window', 'content': 'Visible clause text.'}
        encode = json.JSONEncoder.encode
        seen = []
        def observe(encoder, item):
            seen.append(item is value)
            return encode(encoder, item)
        with patch.object(json.JSONEncoder, 'encode', observe):
            token_windows.digest(value)
        self.assertEqual([True], seen)


if __name__ == '__main__':
    unittest.main()
