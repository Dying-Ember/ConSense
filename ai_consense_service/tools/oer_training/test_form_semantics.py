"""Form task boundaries: mixed writing, source review and truthful unknowns."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image

try:
    from .dataset import SCHEMA as MANIFEST_SCHEMA, validate_manifest
    from .form_semantics import SCHEMA, PROMPT, form_diagnostic, validate_form
except ImportError:
    from dataset import SCHEMA as MANIFEST_SCHEMA, validate_manifest
    from form_semantics import SCHEMA, PROMPT, form_diagnostic, validate_form


class FormSemantics(unittest.TestCase):
    def setUp(self):
        self.page = {"schema": SCHEMA, "pageId": "p1", "imageSize": [120, 80],
                     "groups": [{"id": "g1", "label": "Contractor", "parentId": None}],
                     "fields": [{"id": "f1", "type": "title", "writingBbox": [10, 10, 110, 50],
                                 "state": "mixed", "value": "Actual title", "owner": "g1",
                                 "labels": [{"id": "label1", "text": "TITLE"}],
                                 "writing": [{"id": "w1", "bbox": [10, 10, 110, 25],
                                              "state": "filled", "value": "Actual title"},
                                             {"id": "w2", "bbox": [10, 30, 110, 50],
                                              "state": "unfilled", "value": None}]}],
                     "relations": [{"id": "r1", "kind": "belongs_to", "fromId": "f1", "toId": "g1"}]}

    def test_mixed_title_keeps_both_regions_without_pixel_masks(self):
        self.assertIs(validate_form(self.page, complete=True), self.page)
        self.assertEqual(self.page["fields"][0]["writing"][1]["state"], "unfilled")

    def test_observed_group_without_a_printed_heading_keeps_null_label(self):
        self.page["groups"][0]["label"] = None
        validate_form(self.page, complete=True)

    def test_same_label_text_keeps_separate_occurrences(self):
        second = copy.deepcopy(self.page["fields"][0])
        second.update(id="f2", labels=[{"id": "label2", "text": "TITLE"}])
        for row in second["writing"]:
            row["id"] += "-second"
        self.page["fields"].append(second)
        validate_form(self.page, complete=True)
        self.assertEqual(len(self.page["fields"]), 2)

    def test_unknown_prediction_stays_unknown_and_cannot_be_a_full_target(self):
        field = self.page["fields"][0]
        field.update(state="unknown", value=None)
        field["writing"][1]["state"] = "unknown"
        validate_form(self.page)
        with self.assertRaisesRegex(ValueError, "Unknown form target"):
            validate_form(self.page, complete=True)

    def test_protocol_diagnostic_does_not_award_source_quality_or_json_cer(self):
        result = form_diagnostic(json.dumps(self.page), "stop", page_id="p1", image_size=[120, 80])
        self.assertTrue(result["protocolValid"])
        self.assertFalse(result["sourceSemanticsAccepted"])
        self.assertNotIn("characterErrorRate", result)
        for raw, finish in ((json.dumps(self.page), "length"), ('{"schema":1,"schema":2}', "stop"),
                            ('{"fields":', "stop"), (None, "stop")):
            self.assertFalse(form_diagnostic(raw, finish, page_id="p1", image_size=[120, 80])["protocolValid"])
        self.assertFalse(form_diagnostic(json.dumps(self.page), "stop", page_id="p2",
                                         image_size=[120, 80])["protocolValid"])
        self.assertFalse(form_diagnostic(json.dumps(self.page), "stop", page_id="p1",
                                         image_size=[121, 80])["protocolValid"])

    def test_inconsistent_states_values_and_geometry_are_rejected(self):
        changes = [
            lambda p: p["fields"][0].update(state="filled"),
            lambda p: p["fields"][0].update(value="invented"),
            lambda p: p["fields"][0].update(owner="other-owner"),
            lambda p: p["fields"][0].update(writingBbox=[10, 10, float("nan"), 50]),
            lambda p: p["fields"][0]["writing"][0].update(bbox=[0, 0, 110, 25]),
            lambda p: p["fields"][0]["writing"][1].update(value=""),
            lambda p: p["groups"][0].update(parentId="g1"),
            lambda p: p["relations"][0].update(toId="f1"),
            lambda p: p["fields"][0]["writing"][0].update(id="f1"),
        ]
        for change in changes:
            page = copy.deepcopy(self.page)
            change(page)
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_form(page, complete=True)

    def test_manifest_binds_actual_image_and_independent_review(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def artifact(name, data):
                (root / name).write_bytes(data)
                return {"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}

            Image.new("RGB", (120, 80), (243, 243, 243)).save(root / "page.png")
            target = artifact("target.json", json.dumps(self.page).encode())
            review = {"method": "source_only", "actor": "author", "humanConfirmed": False,
                      "artifact": artifact("review.json", b'{"sourceOnly":true}')}
            sample = {"sampleId": "p1", "sourceId": "source", "task": "form",
                      "originalPhysicalPageNo": 1, "coverage": "complete_task_unit", "unknownRanges": [],
                      "image": artifact("page.png", (root / "page.png").read_bytes()), "target": target,
                      "imageTransform": {"kind": "original_full_page", "originalNormalizedBbox": [0, 0, 1, 1]},
                      "sourceReview": review}
            source = {"sourceId": "source", "partition": "train", "sourceFamily": "source-family",
                      "editionFamily": "source-edition", "nearDuplicateGroup": "source-near",
                      "exposure": "development_historically_exposed", "route": "competition_in_scope",
                      "original": artifact("source.pdf", b"original source fixture")}
            manifest = {"schema": MANIFEST_SCHEMA, "purpose": "compatibility_only",
                        "sources": [source], "samples": [sample]}
            binding = {"pageId": "p1", "imageSha256": sample["image"]["sha256"],
                       "targetSha256": target["sha256"], "sourceSha256": source["original"]["sha256"],
                       "originalPhysicalPageNo": 1}
            review["independentArtifact"] = artifact("independent.json", json.dumps({
                "actor": "other-reviewer", "sourceOnly": True, "acceptedSamples": [binding]}).encode())

            def validate():
                (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
                return validate_manifest(root / "manifest.json")

            _, records, inventory = validate()
            self.assertTrue(records[0]["prompt"].startswith(PROMPT))
            self.assertIn('"pageId": "p1", "imageSize": [120, 80]', records[0]["prompt"])
            self.assertFalse(inventory["qualityImprovementClaim"])
            self.assertEqual(inventory["humanConfirmedSampleCount"], 0)
            for invalid in ({"actor": "author", "sourceOnly": True, "acceptedSamples": [binding]},
                            {"actor": "other", "sourceOnly": True, "acceptedSamples": []}):
                review["independentArtifact"] = artifact("independent.json", json.dumps(invalid).encode())
                with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, "independent source-only"):
                    validate()
            review["independentArtifact"] = artifact("independent.json", json.dumps({"actor": "other",
                "sourceOnly": True, "acceptedSamples": [binding]}).encode())
            sample["imageTransform"]["kind"] = "original_crop"
            with self.assertRaisesRegex(ValueError, "complete original page"):
                validate()
            sample["imageTransform"]["kind"] = "original_full_page"
            Image.new("RGB", (120, 80), (245, 245, 245)).save(root / "other.png")
            original_image = sample["image"]
            sample["image"] = artifact("other.png", (root / "other.png").read_bytes())
            with self.assertRaisesRegex(ValueError, "independent source-only"):
                validate()
            sample["image"] = original_image
            answer_dir = root / "2e_questions and answers"
            answer_dir.mkdir()
            prior_review = review["independentArtifact"]
            review["independentArtifact"] = artifact("2e_questions and answers/review.json",
                                                    (root / prior_review["path"]).read_bytes())
            with self.assertRaisesRegex(ValueError, "Official answers"):
                validate()
            review["independentArtifact"] = prior_review
            manifest["purpose"] = "quality_training"
            with self.assertRaisesRegex(ValueError, "independent-family train and dev"):
                validate()
            manifest["purpose"] = "compatibility_only"
            self.page["imageSize"] = [121, 80]
            sample["target"] = artifact("target.json", json.dumps(self.page).encode())
            with self.assertRaisesRegex(ValueError, "imageSize differs"):
                validate()


if __name__ == "__main__":
    unittest.main()
