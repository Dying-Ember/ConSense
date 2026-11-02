"""One history key only; ordinary projects retain their existing index path."""
from contextlib import ExitStack
from contextvars import ContextVar
import os
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import migration_project_registry as routes
from token_windows import WindowError
from test_source_page_index import SourcePageIndexHttpTests


class SingleHistoryRouteTest(unittest.TestCase):
    def setUp(self):
        self.env = {routes.HISTORY: '/runtime/index-evidence/candidate-receipt.json',
                    routes.HISTORY + '_SHA256': 'a' * 64,
                    routes.HISTORY + '_PROJECT_ID': 'the-history-key'}

    def test_only_exact_vetting_key_is_migrated(self):
        self.assertEqual({'mode': 'migrated', 'receiptPath': self.env[routes.HISTORY], 'receiptSha256': 'a' * 64},
                         routes.select_route(self.env, 'vetting', 'the-history-key'))
        for module, key in (('vetting', 'another-project'), ('advisory', 'the-history-key'),
                            ('drafting', 'the-history-key')):
            self.assertEqual({'mode': 'ordinary'}, routes.select_route(self.env, module, key))

    def test_invalid_or_mixed_configuration_fails_for_every_project(self):
        cases = ({routes.HISTORY: '/receipt'}, {**self.env, routes.HISTORY + '_SHA256': 'bad'},
                 {**self.env, routes.HISTORY + '_PROJECT_ID': ''},
                 {**self.env, routes.LEGACY: ''}, {**self.env, routes.REGISTRY: '/registry'})
        for config in cases:
            with self.subTest(config=config), self.assertRaises(ValueError):
                routes.select_route(config, 'vetting', 'another-project')

    def test_only_target_is_readonly_and_configuration_change_is_detected(self):
        variable = ContextVar('source-frame', default=None)
        runtime = SimpleNamespace(target_module='vetting', source_registry=SimpleNamespace(_validation_frame=variable))
        backend = SimpleNamespace(_migrated_index=None, _windows_runtime=runtime)
        with patch.dict(os.environ, self.env, clear=True):
            self.assertEqual('ordinary', routes.require_writable(backend, 'another-project', runtime)['mode'])
            with self.assertRaises(WindowError): routes.require_writable(backend, 'the-history-key', runtime)
            with ExitStack() as leases:
                token = variable.set({'leaseStack': leases})
                try:
                    self.assertEqual('ordinary', routes.require_writable(backend, 'another-project', runtime)['mode'])
                    with self.assertRaises(WindowError): routes.require_writable(backend, 'the-history-key', runtime)
                    os.environ[routes.HISTORY + '_PROJECT_ID'] = 'changed'
                    with self.assertRaises(WindowError): leases.close()
                finally:
                    variable.reset(token)

    def test_other_project_real_index_entry_still_writes(self):
        fixture = SourcePageIndexHttpTests('test_unpinned_request_preserves_legacy_source_and_echoes_empty_map')
        fixture.setUp(); self.addCleanup(fixture.tearDown)
        with patch.dict(os.environ, self.env):
            response = fixture.index()
            self.assertEqual(200, response.status_code, response.text)
            self.assertIn('AUTHORED-pins-index', fixture.backend._projects)
            before = len(fixture.encodes)
            with patch.dict(os.environ, {routes.HISTORY + '_PROJECT_ID': 'AUTHORED-pins-index'}):
                target = fixture.index()
                self.assertNotEqual(200, target.status_code, target.text)
                self.assertEqual(before, len(fixture.encodes))

    def test_single_history_route_rejects_non_history_receipt(self):
        import migrated_qdrant_index as migration
        variable = ContextVar('source-frame', default=None)
        registry = SimpleNamespace(_validation_frame=variable, family_target_module='vetting')
        backend = SimpleNamespace(_migrated_index=None)
        provider = SimpleNamespace(acquire=lambda binding: None)
        route = {'mode':'migrated', 'singleHistory':True, 'receiptPath':'/fake/receipt', 'receiptSha256':'a' * 64}
        with patch.object(migration, '_source_frame_witness_provider', return_value=provider), \
             patch.object(migration, 'load_migration_reader', return_value=object()), ExitStack() as leases:
            token = variable.set({'indexMetadata':{}, 'leaseStack':leases})
            try:
                with self.assertRaisesRegex(WindowError, 'sealed history receipt'):
                    migration.migration_candidate_for_frame(backend, 'the-history-key', registry, route)
                self.assertFalse(hasattr(backend, '_single_history_reader'))
            finally:
                variable.reset(token)


if __name__ == '__main__': unittest.main()
