from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import patch
from fastapi.testclient import TestClient
from source_service_entrypoint import create_app_with_fixed_health
from oer_unified_server import create_unified_app
from test_oer_server import OerHttpContractTest

class UnifiedFixedHealthRouteTest(TestCase):
    def setUp(self):
        OerHttpContractTest.setUp(self)
        self.client.close()
        self.calls = []
        self.retrievals = []
        fixed = SimpleNamespace(health_for_manifest=lambda value: self.calls.append(value) or
                                {"status":"ok", "mode":"fixed-reference-query", "fixedReferenceManifestSha256":value},
                                owns_project=lambda value: value in ("sl-six", "sl-competition"),
                                retrieve=lambda request: self.retrievals.append((request.projectId, request.sourceSha256)) or
                                {"projectId":request.projectId, "selectedSourceSha256":request.sourceSha256})
        with patch("fixed_reference_query.from_environment", return_value=fixed):
            self.app = create_app_with_fixed_health(create_unified_app, self.policy, backend=self.backend)
        self.client = TestClient(self.app)
    def tearDown(self):
        OerHttpContractTest.tearDown(self)
    def test_unified_health_preserves_normal_and_dispatches_each_fixed_manifest(self):
        normal = self.client.get("/health")
        self.assertEqual(200, normal.status_code)
        self.assertEqual(6, normal.json()["indexSignatureVersion"])
        self.assertIn("unifiedCanonical", normal.json()["oer"])
        for sha in ("a"*64, "b"*64):
            response = self.client.get("/health", params={"fixedReference":sha})
            self.assertEqual(200, response.status_code)
            self.assertEqual({"status":"ok", "mode":"fixed-reference-query", "fixedReferenceManifestSha256":sha}, response.json())
        self.assertEqual(["a"*64,"b"*64],self.calls)

    def test_unified_retrieve_routes_each_fixed_project_with_its_selected_sha(self):
        for project, sha in (("sl-six", "c"*64), ("sl-competition", "d"*64)):
            response = self.client.post("/retrieve", json={"projectId":project, "query":"authored query", "role":"standard", "sourceSha256":[sha]})
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual({"projectId":project, "selectedSourceSha256":[sha]}, response.json())
        self.assertEqual([("sl-six",["c"*64]),("sl-competition",["d"*64])],self.retrievals)
