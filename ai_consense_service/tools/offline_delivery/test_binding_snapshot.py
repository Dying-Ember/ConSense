"""Synthetic admission race: identity and submitted source must use one snapshot."""
import copy
import hashlib
import unittest
from unittest.mock import Mock

from fixed_gpu_client import Client, OperationError
from request_stage import encoded


class BindingSnapshotTest(unittest.TestCase):
    def test_legacy_compute_posts_exact_source_used_for_identity(self):
        client = Client('http://127.0.0.1:1234', 'fixture', {'document-embedding':'d'*64})
        binding = {'modelIdentity':{'id':'original'}, 'runtime':{'device':'cuda'}, 'inferenceCap':4}
        before = copy.deepcopy(binding)
        original_identity = client._identity
        identities, submitted = [], []
        def identity(stage, actual, raw):
            identities.append(copy.deepcopy(actual))
            expected = original_identity(stage, actual, raw)
            binding['modelIdentity']['id'] = 'changed-after-identity'
            binding['runtime']['device'] = 'cpu'
            return expected
        def call(method, path, timeout, body=None, cancelled=None):
            submitted.append(copy.deepcopy(body))
            raise ValueError('synthetic admission failure')
        client._identity = identity
        client._call = call
        with self.assertRaises(OperationError):
            client.compute('document-embedding', binding, {'kind':'dimension'}, 2)
        self.assertEqual(identities, [before])
        self.assertEqual(len(submitted), 1)
        self.assertEqual(submitted[0]['binding'], before)

    def test_nonidempotent_numeric_object_keys_reject_before_dispatch(self):
        client = Client('http://127.0.0.1:1234', 'fixture', {'document-embedding':'d'*64})
        binding = {'modelIdentity':{2:'two',10:'ten'}, 'runtime':{'device':'cuda'}, 'inferenceCap':4}
        client._call = Mock(side_effect=ValueError('unexpected dispatch'))
        with self.assertRaisesRegex(ValueError, 'GPU source identity changes under JSON encoding'):
            client.compute('document-embedding', binding, {'kind':'dimension'}, 2)
        client._call.assert_not_called()

    def test_byte_stable_numeric_key_preserves_old_source_digest(self):
        client = Client('http://127.0.0.1:1234', 'fixture', {'document-embedding':'d'*64})
        binding = {'modelIdentity':{2:'two'}, 'runtime':{'device':'cuda'}, 'inferenceCap':4}
        old_digest = hashlib.sha256(encoded(binding)).hexdigest()
        expected, submitted = [], []
        original_identity = client._identity
        def identity(stage, actual, raw):
            result = original_identity(stage, actual, raw)
            expected.append(result)
            return result
        def call(method, path, timeout, body=None, cancelled=None):
            submitted.append(body)
            raise ValueError('synthetic admission failure')
        client._identity = identity; client._call = call
        with self.assertRaises(OperationError): client.compute('document-embedding',binding,{'kind':'dimension'},2)
        self.assertEqual(expected[0]['sourceSha256'],old_digest)
        self.assertEqual(hashlib.sha256(encoded(submitted[0]['binding'])).hexdigest(),old_digest)


if __name__ == '__main__': unittest.main()
