"""Authored ASGI workload and exact frozen-output checks; no live service/models."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
import retrieval_source_units as units
import source_read_server as server
import test_source_read_semantic as fixtures
from test_attachment_context_guards import native_corpus
from test_explicit_margin_context_red import corpus as margin_corpus
import test_retrieval_source_units as structural_fixtures


REFERENCE = Path(__file__).resolve().parents[2] / 'reference'


def frozen_module(filename, name):
    specification = importlib.util.spec_from_file_location(name, REFERENCE / filename)
    result = importlib.util.module_from_spec(specification)
    sys.modules[name] = result
    specification.loader.exec_module(result)
    return result


class DictionaryTemplateWorkloadTests(unittest.TestCase):
    setUp = fixtures.SemanticSourceReadTests.setUp
    tearDown = fixtures.SemanticSourceReadTests.tearDown
    read = fixtures.SemanticSourceReadTests.read
    publish = fixtures.SemanticSourceReadTests.publish
    publish_pdf = fixtures.SemanticSourceReadTests.publish_pdf
    publish_all = fixtures.SemanticSourceReadTests.publish_all
    normal = fixtures.SemanticSourceReadTests.normal

    def mixed_sources(self):
        mixed = self.publish_all()
        normal = self.normal()
        self.sources = mixed + [normal]
        return [parent for source in self.sources
                for parent in self.registry.chunks(source['projectionSha256'])]

    def test_dictionary_prepare_skips_discarded_full_member_projection(self):
        parents = self.mixed_sources()
        with patch.object(server, 'semantic_unit', wraps=server.semantic_unit) as full_projection:
            response = self.read('prepare', view='semantic_units',
                semanticReadVersion=server.DICTIONARY_VERSION)
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(len(parents), response.json()['coverage']['totalOrigins'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)
        print(json.dumps({'check': 'discarded_full_member_projection',
            'originCount': len(parents), 'fullProjectionCalls': full_projection.call_count}))
        self.assertEqual(0, full_projection.call_count,
            'Dictionary templates must not expand member bodies that they immediately discard')

    def test_raw_templates_exactly_match_frozen_composition_and_existing_callers(self):
        frozen = frozen_module('retrieval_source_units.py', 'frozen_template_units')
        corpora = [structural_fixtures.StructuralSelectionTests().corpus(), native_corpus(), margin_corpus()]
        checked = 0
        for parents in corpora:
            catalog = units.SourceUnits(parents)
            for parent in parents:
                raw = catalog.resolve(parent)
                before = copy.deepcopy(raw)
                projected = units.semantic_unit(raw, 'c' * 64)
                expected = frozen.semantic_unit_template(projected)
                self.assertEqual(server._serialize(expected),
                    server._serialize(units.semantic_unit_template(raw)))
                self.assertEqual(expected, units.semantic_unit_template(projected))
                self.assertEqual(before, raw)
                checked += 1
        member = {'id': 'discarded', 'content': 'Discarded member body'}
        raw = {'members': [member], 'originIds': ['discarded'],
            'sourceIdentity': {'proof': True}, 'headingLocation': 'native-location',
            'bbox': [1, 2, 3, 4], 'unknownSemanticDeclaration': {'preserve': True},
            'hierarchyContext': {'members': [member], 'originIds': ['discarded'],
                'status': 'unknown', 'requiredMemberIds': ['retained'],
                'declaration': {'layoutEvidence': {'numbering': {'label': '(iii)'}}}},
            'attachmentContext': {'members': [], 'originIds': [],
                'tableGroup': {'conditions': ['unless approved'], 'units': 'mm',
                    'rows': [{'cells': [{'text': '', 'blankState': 'observed_blank'}]}]}}}
        expected = frozen.semantic_unit_template(units.semantic_unit(raw, 'c' * 64))
        self.assertEqual(server._serialize(expected),
            server._serialize(units.semantic_unit_template(raw)))
        print(json.dumps({'check': 'raw_template_exact_equivalence',
            'resolvedOrigins': checked, 'nestedAdversarialTemplate': True}))

    def test_published_prepare_scan_ids_and_cas_exactly_match_frozen_route(self):
        self.assert_published_output_matches_frozen(self.mixed_sources())

    def test_public_table_reference_keeps_exact_manifest_dependencies_and_cells(self):
        from native_pdf_canonical_projection import produce_native_pdf
        from native_pdf_projection_transport import publish_native_pdf_transport
        import test_native_pdf_table_projection as table_fixtures
        table = table_fixtures.NativePdfTableProjectionTest()
        table.setUp()
        self.addCleanup(table.temporary.cleanup)
        table.manifest['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        for path in table.store.root.glob('sha256/*/*.blob'):
            self.store.put_bytes(path.read_bytes())
        internal = produce_native_pdf(table.native, table.dual, table.manifest, artifact_store=self.store)
        source = publish_native_pdf_transport(internal, artifact_store=self.store)
        self.registry.publish(source)
        self.sources = [source]
        result = self.assert_published_output_matches_frozen(self.registry.chunks(source['projectionSha256']))
        cells = result['origins'][0]['nativeTables'][0]['fragments'][0]['cells']
        self.assertEqual(['Visible fi cafe', ''], [cell['text'] for cell in cells])
        self.assertEqual('source_reviewed_blank', cells[1]['valueState'])

    def assert_published_output_matches_frozen(self, parents):
        frozen = frozen_module('source_read_server.py', 'frozen_source_read_server')
        frozen.semantic_unit_template = frozen_module(
            'retrieval_source_units.py', 'frozen_route_template_units').semantic_unit_template
        apps = [FastAPI(), FastAPI()]
        for module, app in zip((frozen, server), apps):
            app.state.request_evidence = self.app.state.request_evidence
            with patch.object(module.os, 'urandom', return_value=b'authored-test-only-secret-32bytes!'):
                module.install_source_read_route(app, self.registry, self.policy,
                    target_module='vetting', software_guard=self.rt.algorithms)
        clients = [TestClient(app) for app in apps]
        base = {'module': 'vetting', 'view': 'semantic_units',
            'semanticReadVersion': server.DICTIONARY_VERSION,
            'projectionSha256': [source['projectionSha256'] for source in self.sources]}
        compared_responses = 0
        try:
            # Both fresh route closures must do cold preparation; no derived hit
            # may substitute a different source reconstruction in one arm.
            prepared, cas_snapshots = [], []
            with patch.object(self.registry, '_derived_get', return_value=None):
                for client in clients:
                    response = client.post('/sources/read', json={**base, 'operation': 'prepare'})
                    self.assertEqual(200, response.status_code, response.text)
                    prepared.append(response)
                    snapshot = {}
                    for address in response.json()['readManifestSha256']:
                        raw = (self.store.root / 'sha256' / address[:2] / (address + '.blob')).read_bytes()
                        snapshot[address] = raw
                        manifest = json.loads(raw)
                        references = [row['recordRef'] for row in manifest['origins']]
                        references += list(manifest['groups'].values())
                        for descriptor in references:
                            snapshot[descriptor['sha256']] = self.store.read_asset(descriptor)
                    cas_snapshots.append(snapshot)
            for response in prepared:
                self.assertEqual(200, response.status_code, response.text)
                self.assertTrue(response.json()['coldPreparation']['performed'])
            self.assertEqual(prepared[0].content, prepared[1].content)
            self.assertEqual(cas_snapshots[0], cas_snapshots[1])
            compared_responses += 1
            binding = {key: prepared[0].json()[key] for key in
                       ('readManifestSha256', 'readManifestToken')}
            manifests = [json.loads((self.store.root / 'sha256' / address[:2] /
                         (address + '.blob')).read_bytes())
                         for address in binding['readManifestSha256']]
            descriptors = [entry['recordRef'] for manifest in manifests
                           for entry in manifest['origins']]
            descriptors += [descriptor for manifest in manifests
                            for descriptor in manifest['groups'].values()]
            for descriptor in descriptors:
                raw = self.store.read_asset(descriptor)
                self.assertEqual(descriptor['sha256'], hashlib.sha256(raw).hexdigest())
            cursor, known, observed_ids = None, [], []
            for _ in range(len(parents) + 1):
                request = {**base, **binding, 'operation': 'scan', 'maxItems': 2,
                    'cursor': cursor, 'knownSemanticGroups': known}
                responses = [client.post('/sources/read', json=request) for client in clients]
                for response in responses:
                    self.assertEqual(200, response.status_code, response.text)
                self.assertEqual(responses[0].content, responses[1].content)
                compared_responses += 1
                body = responses[0].json()
                observed_ids.extend(row['id'] for row in body['origins'])
                known.extend(row['groupId'] for row in body['units'])
                cursor = body['nextCursor']
                if cursor is None:
                    break
            self.assertIsNone(cursor)
            self.assertEqual([parent['id'] for parent in parents], observed_ids)
            selected = [parent['id'] for parent in reversed(parents)]
            self.assertLessEqual(len(selected), 64)
            responses = [client.post('/sources/read', json={**base, **binding,
                'operation': 'ids', 'originIds': selected}) for client in clients]
            for response in responses:
                self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(responses[0].content, responses[1].content)
            self.assertEqual(selected, [row['id'] for row in responses[0].json()['origins']])
            compared_responses += 1
            self.assertEqual([], self.encodes)
            self.assertEqual([], self.predictions)
            print(json.dumps({'check': 'frozen_route_exact_equivalence',
                'sourceCount': len(self.sources), 'originCount': len(parents),
                'exactResponses': compared_responses,
                'exactCasBlobs': len(cas_snapshots[0]),
                'manifestAddresses': binding['readManifestSha256'],
                'checkedMemberAndGroupDescriptors': len(descriptors)}))
            return responses[0].json()
        finally:
            for client in clients:
                client.close()


if __name__ == '__main__':
    unittest.main()
