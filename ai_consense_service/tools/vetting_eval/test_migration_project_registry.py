"""Authored routing bytes only; no receipt, model or server is accessed."""
import hashlib
import json
import unittest

import migration_project_registry as registry


class MigrationProjectRegistryTest(unittest.TestCase):
    def setUp(self):
        self.entries = [
            {'module':'vetting', 'projectId':'A', 'mode':'migrated',
             'receiptPath':'/receipts/a.json', 'receiptSha256':'a'*64},
            {'module':'vetting', 'projectId':'B', 'mode':'migrated',
             'receiptPath':'D:\\receipts\\b.json', 'receiptSha256':'b'*64},
            {'module':'advisory', 'projectId':'A', 'mode':'ordinary'},
        ]

    def config(self, entries=None):
        raw = json.dumps({'schema':registry.SCHEMA, 'entries':self.entries if entries is None else entries}).encode()
        return {registry.REGISTRY:'/routes/registry.json', registry.REGISTRY+'_SHA256':hashlib.sha256(raw).hexdigest()}, raw

    def test_exact_project_and_module_routes(self):
        env, raw = self.config()
        self.assertEqual({'mode':'migrated','receiptPath':'/receipts/a.json','receiptSha256':'a'*64},
                         registry.select_route(env, 'vetting', 'A', raw))
        self.assertEqual('b'*64, registry.select_route(env, 'vetting', 'B', raw)['receiptSha256'])
        self.assertEqual({'mode':'ordinary'}, registry.select_route(env, 'advisory', 'A', raw))
        for module, project in [('advisory','B'), ('vetting','missing'), ('drafting','A')]:
            with self.subTest(module=module, project=project), self.assertRaises(ValueError):
                registry.select_route(env, module, project, raw)

    def test_pin_and_duplicate_json_keys_are_rejected(self):
        env, raw = self.config()
        with self.assertRaises(ValueError): registry.select_route(env, 'vetting', 'A', raw + b' ')
        duplicate = b'{"schema":"x","schema":"y","entries":[]}'
        env[registry.REGISTRY+'_SHA256'] = hashlib.sha256(duplicate).hexdigest()
        with self.assertRaises(ValueError): registry.select_route(env, 'vetting', 'A', duplicate)

    def test_duplicate_routes_and_invalid_rows_fail_even_when_not_selected(self):
        cases = [self.entries + [dict(self.entries[0])],
                 self.entries + [{'module':'vetting','projectId':'C','mode':'ordinary','receiptPath':'/x'}],
                 self.entries + [{'module':'bad','projectId':'C','mode':'ordinary'}],
                 self.entries + [{'module':[],'projectId':'C','mode':'ordinary'}],
                 self.entries + [{'module':'vetting','projectId':3,'mode':'ordinary'}],
                 self.entries + [{'module':'vetting','projectId':'C','mode':'migrated','receiptPath':'relative','receiptSha256':'a'*64}],
                 self.entries + [{'module':'vetting','projectId':'C','mode':'migrated','receiptPath':'/x/../y','receiptSha256':'a'*64}],
                 self.entries + [{'module':'vetting','projectId':'C','mode':'migrated','receiptPath':'/x','receiptSha256':'A'*64}]]
        for entries in cases:
            env, raw = self.config(entries)
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                registry.select_route(env, 'vetting', 'A', raw)

    def test_modes_and_incomplete_or_mixed_configuration(self):
        env, raw = self.config()
        legacy = {registry.LEGACY:'/receipts/legacy.json', registry.LEGACY+'_SHA256':'c'*64}
        self.assertEqual('legacy', registry.select_route(legacy, 'vetting', 'A')['mode'])
        self.assertEqual({'mode':'ordinary'}, registry.select_route({}, 'vetting', 'A'))
        self.assertEqual({'mode':'ordinary'}, registry.select_route({registry.LEGACY:''}, 'vetting', 'A'))
        self.assertEqual({'mode':'legacy', 'receiptPath':'relative', 'receiptSha256':None},
                         registry.select_route({registry.LEGACY:'relative'}, 'vetting', 'A'))
        for bad in ({**env, **legacy}, {registry.REGISTRY:env[registry.REGISTRY]},
                    {registry.REGISTRY:'', registry.LEGACY:''}, {registry.REGISTRY:''}):
            with self.subTest(config=bad), self.assertRaises(ValueError):
                registry.select_route(bad, 'vetting', 'A', raw)


if __name__ == '__main__': unittest.main()
