"""Published parent JSON types stay exact across registry and direct index seams."""
import copy
import unittest

from oer_source_registry import SourceRegistryError
import test_native_source_registry as native_tests
import test_native_pdf_source_registry as pdf_tests
import test_native_collection_registry as collection_tests
import test_native_pdf_source_family as family_tests
import test_unified_source_frame as frame_tests


class RegistryParentJsonIdentityTests(unittest.TestCase):
    def assert_false_is_not_zero(self, registry, chunks, code):
        self.assertEqual(1, registry.validate_native_chunks(chunks))
        self.assertIs(chunks[0]['humanConfirmed'], False)
        changed = copy.deepcopy(chunks)
        changed[0]['humanConfirmed'] = 0
        with self.assertRaises(SourceRegistryError) as rejected:
            registry.validate_native_chunks(changed)
        self.assertEqual(code, rejected.exception.code)
        self.assertEqual(1, registry.validate_native_chunks(chunks))

    def test_native_docx_rejects_numeric_false_and_numeric_native_true(self):
        fixture = native_tests.NativeRegistryTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.registry.publish(fixture.public)
        self.assert_false_is_not_zero(fixture.registry, fixture.public['chunks'], 'NATIVE_INDEX_PARENT_MISMATCH')
        changed = copy.deepcopy(fixture.public['chunks'])
        self.assertIs(changed[0]['parts'][0]['native'], True)
        changed[0]['parts'][0]['native'] = 1
        with self.assertRaises(SourceRegistryError) as rejected:
            fixture.registry.validate_native_chunks(changed)
        self.assertEqual('NATIVE_INDEX_PARENT_MISMATCH', rejected.exception.code)

    def test_native_pdf_rejects_numeric_false(self):
        fixture = pdf_tests.NativePdfRegistryTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.registry.publish(fixture.public)
        self.assert_false_is_not_zero(fixture.registry, fixture.public['chunks'], 'PDF_INDEX_PARENT_MISMATCH')

    def test_complete_docx_collection_rejects_numeric_false(self):
        fixture = collection_tests.NativeCollectionRegistryTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.registry.publish(fixture.root)
        self.assert_false_is_not_zero(fixture.registry, fixture.registry.chunks(fixture.address), 'NATIVE_INDEX_PARENT_MISMATCH')

    def test_complete_pdf_family_rejects_numeric_false(self):
        fixture = family_tests.NativePdfSourceFamilyTest
        fixture.setUpClass()
        self.addCleanup(fixture.tearDownClass)
        self.assert_false_is_not_zero(fixture.registry, fixture.registry.chunks(fixture.family['projectionSha256']), 'SOURCE_FAMILY_INDEX_PARENT_MISMATCH')


class DirectIndexParentJsonIdentityTests(unittest.TestCase):
    setUp = frame_tests.SourceFrameTests.setUp
    tearDown = frame_tests.SourceFrameTests.tearDown

    def test_http_index_preserves_false_zero_distinction_before_embedding(self):
        self.registry.publish(self.single)
        changed = copy.deepcopy(self.single['chunks'])
        self.assertIs(changed[0]['humanConfirmed'], False)
        changed[0]['humanConfirmed'] = 0
        request = self.backend.IndexRequest(projectId='json-parent-type', chunks=changed)
        self.assertIs(type(request.chunks[0].model_dump(exclude_none=True)['humanConfirmed']), int)
        response = self.client.post('/index', json={'projectId': 'json-parent-type', 'chunks': changed})
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('NATIVE_INDEX_PARENT_MISMATCH', response.json()['detail']['kind'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)
        self.assertNotIn(self.backend.project_key('json-parent-type'), self.backend._projects)


if __name__ == '__main__':
    unittest.main()
