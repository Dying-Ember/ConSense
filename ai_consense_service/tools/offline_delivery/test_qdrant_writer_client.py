import unittest

from qdrant_writer_client import target_factory


class Session:
    def __init__(self):
        self.calls = []
        self.fail_close = False

    def read(self, *args, **kwargs):
        self.calls.append(('read', args, kwargs))
        return 'read-result'

    def write(self, *args, **kwargs):
        self.calls.append(('write', args, kwargs))
        return 'confirmed-result'

    def close(self):
        self.calls.append(('close',))
        if self.fail_close:
            raise RuntimeError('unknown release')


class Authority:
    def __init__(self):
        self.bindings = []
        self.session = Session()

    def open_writer(self, binding):
        self.bindings.append(binding)
        return self.session


class WriterTargetTest(unittest.TestCase):
    def setUp(self):
        self.binding = {'collection': 'synthetic', 'operationId': 'op-1', 'serverIdentity': 'server-1'}
        self.authority = Authority()

    def test_migration_calls_use_authority_and_frozen_binding(self):
        factory = target_factory(self.authority, self.binding)
        self.binding['collection'] = 'changed'
        client = factory()
        self.assertEqual(self.authority.bindings[0]['collection'], 'synthetic')
        self.assertEqual(client.get_collections(), 'read-result')
        self.assertEqual(client.get_aliases(), 'read-result')
        self.assertEqual(client.collection_exists('synthetic'), 'read-result')
        client.get_collection('synthetic')
        client.count('synthetic', exact=True)
        client.scroll('synthetic', limit=1, offset=None, with_payload=True, with_vectors=True)
        client.create_collection('synthetic', vectors_config='fake-config')
        client.upsert('synthetic', points=['fake-point'], wait=True)
        client.close()
        self.assertEqual([call[1][0] for call in self.authority.session.calls[:-1]],
                         ['get_collections', 'get_aliases', 'collection_exists', 'get_collection', 'count',
                          'scroll', 'create_collection', 'upsert'])
        self.assertEqual(self.authority.session.calls[-1], ('close',))
        with self.assertRaises(RuntimeError):
            factory()

    def test_wrong_scope_unknown_method_and_non_waiting_write_never_dispatch(self):
        client = target_factory(self.authority, self.binding)()
        with self.assertRaises(ValueError):
            client.create_collection('other', vectors_config='fake')
        with self.assertRaises(ValueError):
            client.upsert('synthetic', points=[], wait=1)
        with self.assertRaises(ValueError):
            client._call('write', 'delete_collection', 'synthetic')
        with self.assertRaises(AttributeError):
            client.delete_collection('synthetic')
        self.assertEqual(self.authority.session.calls, [])

    def test_uncertain_open_and_close_are_not_retried(self):
        class BadAuthority:
            def open_writer(self, binding):
                raise RuntimeError('unknown admission')
        factory = target_factory(BadAuthority(), self.binding)
        with self.assertRaisesRegex(RuntimeError, 'unknown admission'):
            factory()
        with self.assertRaisesRegex(RuntimeError, 'already attempted'):
            factory()
        client = target_factory(self.authority, self.binding)()
        self.authority.session.fail_close = True
        with self.assertRaisesRegex(RuntimeError, 'unknown release'):
            client.close()
        with self.assertRaisesRegex(RuntimeError, 'release remains unknown'):
            client.close()
        with self.assertRaises(ValueError):
            client.collection_exists('synthetic')
        self.assertEqual(self.authority.session.calls, [('close',)])

    def test_invalid_binding_fails_before_authority(self):
        for binding in (None, {}, {**self.binding, 'extra': 'x'}, {**self.binding, 'operationId': ''}):
            with self.assertRaises(ValueError):
                target_factory(self.authority, binding)
        self.assertEqual(self.authority.bindings, [])


if __name__ == '__main__':
    unittest.main()
