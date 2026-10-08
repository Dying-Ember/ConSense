"""Offline scorer regression tests. These files never call the application/API."""

from __future__ import annotations

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from xml.sax.saxutils import escape
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "validate-drafting-fixture.py"
SPEC = importlib.util.spec_from_file_location("validate_drafting_fixture", SCRIPT)
SCORER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCORER)


class AlternateFixtureScorerTests(unittest.TestCase):
    def test_section_editor_id_is_metadata_but_business_fields_and_unknown_fields_are_not(self):
        expected = {"designation": "Section R", "workTypes": ["building"], "location": "North hall"}
        actual = dict(expected, id="temporary-editor-row")
        self.assertTrue(SCORER.equivalent(actual, expected, "sections"))
        self.assertFalse(SCORER.equivalent(dict(actual, location="South hall"), expected, "sections"))
        self.assertFalse(SCORER.equivalent(dict(actual, undocumented="extra"), expected, "sections"))
        self.assertFalse(SCORER.equivalent(actual, expected, "unrelatedList"))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixture = self.root / "alternate"
        self.upload = self.fixture / "01_upload_documents"
        self.reference = self.fixture / "02_reference_do_not_upload"
        self.run = self.root / "saved-run"
        for directory in (self.upload, self.reference, self.run):
            directory.mkdir(parents=True)
        self.bills = [
            {"number": str(i), "description": f"BQ formal trade {i}", "type": "BQ"}
            for i in range(1, 6)
        ] + [
            {"number": str(i), "description": f"SOR formal trade {i}", "type": "SOR"}
            for i in range(1, 3)
        ]
        self.bill_quote = "The full formal Bill schedule is " + json.dumps(self.bills) + "."
        self.month_quote = "The parties adopt an exact contract period of 43 months for this drafting exercise."
        self.water_value = "\n".join(f"{i}.Alternative waterproofing area {i}." for i in range(1, 10))
        self.water_quote = "The complete alternative waterproofing list is:\n" + self.water_value
        self.paragraphs = [self.bill_quote, self.month_quote, self.water_quote]
        self.files = []
        for index in range(1, 16):
            path = self.upload / f"{index:02d}_synthetic.docx"
            paragraphs = self.paragraphs if index == 1 else [f"Correspondence record SIM{index:02d}."]
            xml = '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
            xml += "".join(f"<w:p><w:r><w:t>{escape(text)}</w:t></w:r></w:p>" for text in paragraphs)
            xml += "</w:body></w:document>"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("word/document.xml", xml)
            self.files.append(path)
        keys = sorted(SCORER.WITHHELD) + ["billNos", "contractPeriodMonths", "otherWaterproofingSpecificationAreas", "conditionallyInactive"]
        keys += [f"field{index:02d}" for index in range(79 - len(keys))]
        self.expected = {
            key: {
                "value": None, "status": "unknown" if key in SCORER.WITHHELD else "not_applicable",
                "origin": "deliberately_withheld" if key in SCORER.WITHHELD else "conditional_test",
                "applicability": "active" if key in SCORER.WITHHELD else "inactive",
            } for key in keys
        }
        values = {
            "billNos": (self.bills, self.bill_quote, 1),
            "contractPeriodMonths": (43, self.month_quote, 2),
            "otherWaterproofingSpecificationAreas": (self.water_value, self.water_quote, 3),
        }
        for key, (value, quote, ordinal) in values.items():
            self.expected[key] = {
                "value": value, "status": "provided", "origin": "synthetic_correspondence", "applicability": "active",
                "inputEvidence": {"recordId": "SIM01", "file": self.files[0].name, "nativeParagraph": ordinal, "quote": quote, "sha256": SCORER.sha(self.files[0])},
            }
        self.expected_doc = {
            "expectedInputs": self.expected, "catalogueRuleVersion": "test-catalog",
            "gradingMetadata": {
                "examTitle": "另一套变量考题", "candidateExamScope": "Alternate synthetic values; candidate identification only.",
                "additionalBusinessBoundary": "The seven formal Bill identities require independent downstream business acceptance.",
                "additionalBusinessNotes": ["BQ 1 and SOR 1 are different identities; this fixture has nine waterproofing areas."],
            },
            "billDistributionAndUsageOracle": {"fixtureSpecific": True, "billCount": 7},
        }
        self.variables = [{"key": key, "value": None, "candidates": [], "confirmed": False, "manuallyEdited": False, "adoptionState": "missing"} for key in keys]
        by_key = {row["key"]: row for row in self.variables}
        for key, (value, quote, _ordinal) in values.items():
            raw = value if isinstance(value, str) else json.dumps(value)
            by_key[key].update(value=raw, adoptionState="suggested", candidates=[{
                "value": raw, "sourceDocumentId": "1", "sourceHash": "project-hash-1", "fileName": self.files[0].name, "sourceQuote": quote,
            }])
        self.inputs = [{"id": str(index), "fileName": path.name} for index, path in enumerate(self.files, 1)]
        self.plan = {
            "evidenceRevision": "revision-1", "effectiveValues": {},
            "sourceIdentities": [{"id": str(index), "category": "PROJECT_INPUT", "fileName": path.name, "hash": f"project-hash-{index}"} for index, path in enumerate(self.files, 1)],
        }
        self.trace = {"status": "completed", "stale": False, "evidenceRevision": "revision-1", "parts": [{"sourceDocumentId": "1", "sourceText": "\n".join(self.paragraphs)}]}
        self.catalog = {"ruleVersion": "test-catalog", "groups": [{"fields": [{"key": key, "kind": "text" if key == "otherWaterproofingSpecificationAreas" else "json"} for key in keys]}]}
        self.manifest = {"projectId": "offline-test-only", "stage": "SIM15", "inputFiles": [{"name": path.name, "sha256": SCORER.sha(path)} for path in self.files]}

    def write(self, path, value):
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def grade(self):
        self.write(self.reference / "expected_values.json", self.expected_doc)
        cumulative = {key: value["value"] for key, value in self.expected.items()}
        self.write(self.reference / "incremental_expected_states.json", [{"cumulativeValues": cumulative, "newAnswers": {}} for _ in range(15)])
        for name, value in (("variables", self.variables), ("trace", self.trace), ("plan", self.plan), ("inputs", self.inputs), ("catalog", self.catalog), ("manifest", self.manifest)):
            self.write(self.run / f"{name}.json", value)
        return SCORER.grade(SimpleNamespace(fixture=self.fixture, actual=self.run / "variables.json", trace=self.run / "trace.json", plan=self.run / "plan.json", stage="SIM15"))

    def row(self, report, key):
        return next(row for row in report["ledger"] if row["key"] == key)

    def test_seven_bills_nine_water_43_months_use_only_alternate_oracle(self):
        report, code = self.grade()
        self.assertEqual(0, code)
        self.assertEqual([], report["globalFailures"])
        self.assertEqual({"pass": 79, "review": 0, "fail": 0}, {key: report["summary"][key] for key in ("pass", "review", "fail")})
        self.assertTrue(self.row(report, "billNos")["correctFullCandidate"])
        self.assertTrue(self.row(report, "contractPeriodMonths")["semanticallySupportedByLiteralDiagnostic"])
        markdown = SCORER.markdown(report)
        self.assertIn("另一套变量考题", markdown)
        self.assertIn("all 7 number/type/formal-description", markdown)
        self.assertIn("nine waterproofing areas", markdown)
        self.assertNotIn("Bill 9/10/11", markdown)
        self.assertNotIn("Disc A", markdown)
        self.assertNotIn("第十四个父 Bill", markdown)

    def test_bq1_and_sor1_are_separate_required_identities(self):
        changed = deepcopy(self.bills)
        changed[-2]["type"] = "BQ"
        self.assertFalse(SCORER.equivalent(changed, self.bills, "billNos"))
        self.assertFalse(SCORER.equivalent(self.bills[:-2], self.bills, "billNos"))
        self.assertTrue(SCORER.equivalent(list(reversed(self.bills)), self.bills, "billNos"))

    def test_removing_sor1_fails_recognition_and_display(self):
        row = next(row for row in self.variables if row["key"] == "billNos")
        row["value"] = row["candidates"][0]["value"] = json.dumps(self.bills[:5] + self.bills[6:])
        report, code = self.grade()
        self.assertEqual(1, code)
        self.assertIn("correct_grounded_candidate_not_recognized", self.row(report, "billNos")["failures"])
        self.assertIn("displayed_suggestion_value_incorrect", self.row(report, "billNos")["failures"])

    def test_conditional_null_is_not_false_zero_or_empty_list(self):
        for raw in ("false", "0", "[]"):
            with self.subTest(raw=raw):
                row = next(row for row in self.variables if row["key"] == "conditionallyInactive")
                row["value"] = raw
                report, code = self.grade()
                self.assertEqual(1, code)
                self.assertIn("expected_unfilled_input_has_value", self.row(report, "conditionallyInactive")["failures"])

    def test_exact_period_requires_adoption_anchor_regardless_of_month_number(self):
        evidence = {"file": "period.docx", "quote": self.month_quote}
        for value in (31, 43, 48):
            with self.subTest(value=value):
                quote = f"The BASE allowed ceiling is {value} months; an exact adopted period is not given."
                diagnostic = SCORER.semantic_diagnostic("contractPeriodMonths", value, quote, evidence, "period.docx", {"period.docx": [self.month_quote, quote]})
                self.assertEqual("HUMAN_REVIEW_REQUIRED", diagnostic["verdict"])

    def test_malformed_fixture_presentation_fails_closed(self):
        self.expected_doc["gradingMetadata"]["additionalBusinessNotes"] = "legacy prose"
        with self.assertRaisesRegex(ValueError, "list of nonempty strings"):
            self.grade()

    def test_source_hash_and_no_auto_adoption_guards_remain_enforced(self):
        row = next(row for row in self.variables if row["key"] == "contractPeriodMonths")
        row["candidates"][0]["sourceHash"] = "another-project-source"
        row["confirmed"] = True
        self.plan["effectiveValues"]["contractPeriodMonths"] = 43
        report, code = self.grade()
        self.assertEqual(1, code)
        self.assertIn("no_auto_adoption", report["globalFailures"])
        self.assertIn("no_unadopted_effective_values", report["globalFailures"])
        self.assertIn("candidate_source_grounding_failed", self.row(report, "contractPeriodMonths")["failures"])

    def test_native_docx_preserves_explicit_breaks_and_tabs_across_runs(self):
        path = self.root / "native-separators.docx"
        xml = '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
        xml += '<w:p><w:r><w:t>Adopt this list:</w:t><w:br/><w:t>First item</w:t></w:r>'
        xml += '<w:r><w:cr/><w:t>Second item</w:t><w:tab/><w:t>Scope B</w:t></w:r></w:p>'
        xml += '<w:p><w:r><w:t>Unbroken</w:t><w:t>words</w:t></w:r></w:p>'
        xml += '</w:body></w:document>'
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("word/document.xml", xml)
        paragraphs = SCORER.native_paragraphs(path)
        self.assertEqual(["Adopt this list:\nFirst item\nSecond item\tScope B", "Unbrokenwords"], paragraphs)
        self.assertTrue(SCORER.present(paragraphs[0], "First item\nSecond item\tScope B"))
        self.assertFalse(SCORER.present(paragraphs[0], "First itemSecond item"))
        self.assertFalse(SCORER.present(paragraphs[1], "Unbroken words"))

    def test_multiline_native_paragraph_grades_literal_quote_and_reference_anchor(self):
        # Real python-docx line breaks are w:br nodes rather than newlines inside
        # w:t. Exercise the complete byte/hash/anchor/trace grading path.
        path = self.files[0]
        xml = '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
        for text in self.paragraphs:
            parts = text.split("\n")
            xml += '<w:p><w:r>' + '<w:br/>'.join(f'<w:t>{escape(part)}</w:t>' for part in parts) + '</w:r></w:p>'
        xml += '</w:body></w:document>'
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("word/document.xml", xml)
        current_hash = SCORER.sha(path)
        self.manifest["inputFiles"][0]["sha256"] = current_hash
        for truth in self.expected.values():
            if truth.get("inputEvidence"):
                truth["inputEvidence"]["sha256"] = current_hash
        report, code = self.grade()
        self.assertEqual(0, code)
        row = self.row(report, "otherWaterproofingSpecificationAreas")
        self.assertTrue(row["referenceEvidenceVerified"])
        self.assertTrue(row["candidates"][0]["groundingChecks"]["literal_quote_present_in_native_docx_projection"])
        self.assertTrue(row["recognizedCorrectGroundedAnswer"])
        # Adding an absent item cannot become grounded just because the quote
        # contains a real line break. Hash and exact presence remain required.
        variable = next(v for v in self.variables if v["key"] == "otherWaterproofingSpecificationAreas")
        variable["candidates"][0]["sourceQuote"] += "\n10. Absent item."
        report, code = self.grade()
        self.assertEqual(1, code)
        self.assertIn("candidate_source_grounding_failed", self.row(report, "otherWaterproofingSpecificationAreas")["failures"])

    def install_native_bill_table(self):
        path = self.files[0]
        headers = ["Pricing document", "Bill number", "Issued title"]
        rows = [[b["type"], b["number"], b["description"]] for b in self.bills]
        xml = '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
        xml += '<w:tbl>' + ''.join('<w:tr>' + ''.join(
            '<w:tc><w:p><w:r><w:t>' + escape(cell) + '</w:t></w:r></w:p></w:tc>'
            for cell in row) + '</w:tr>' for row in [headers] + rows) + '</w:tbl>'
        xml += ''.join('<w:p><w:r><w:t>' + escape(text) + '</w:t></w:r></w:p>' for text in self.paragraphs)
        xml += '</w:body></w:document>'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('word/document.xml', xml)
        digest = SCORER.sha(path)
        self.manifest['inputFiles'][0]['sha256'] = digest
        for truth in self.expected.values():
            if truth.get('inputEvidence'):
                truth['inputEvidence'].update(sha256=digest, sourceKind='paragraph')
        quote = '\n'.join(' | '.join(row) for row in [headers] + rows)
        self.expected['billNos']['inputEvidence'] = {
            'recordId': 'SIM01', 'file': path.name, 'sha256': digest,
            'sourceKind': 'table', 'nativeTable': 1, 'headers': headers, 'rows': rows, 'quote': quote,
        }
        bill = next(v for v in self.variables if v['key'] == 'billNos')
        bill['candidates'][0]['sourceQuote'] = quote
        self.trace['parts'][0]['sourceText'] = quote + '\n' + '\n'.join(self.paragraphs)

    def test_native_table_and_following_top_level_paragraph_anchor_are_verified(self):
        self.install_native_bill_table()
        report, _code = self.grade()
        for key in ('billNos', 'contractPeriodMonths', 'otherWaterproofingSpecificationAreas'):
            with self.subTest(key=key):
                row = self.row(report, key)
                self.assertTrue(row['referenceEvidenceVerified'])
                self.assertTrue(row['recognizedCorrectGroundedAnswer'])
        self.assertEqual(0, report['summary']['fail'])
        self.assertIn('原生表格', SCORER.markdown(report))

    def test_native_table_reference_rejects_wrong_row_header_ordinal_and_hash(self):
        self.install_native_bill_table()
        reference = deepcopy(self.expected['billNos']['inputEvidence'])
        for mutation in ('row', 'header', 'ordinal', 'hash', 'quote', 'ambiguous'):
            with self.subTest(mutation=mutation):
                changed = deepcopy(reference)
                if mutation == 'row': changed['rows'][0][0] = 'SOR'
                elif mutation == 'header': changed['headers'][0] = 'Unrelated'
                elif mutation == 'ordinal': changed['nativeTable'] = 2
                elif mutation == 'hash': changed['sha256'] = '0' * 64
                elif mutation == 'quote': changed['quote'] += '\nAbsent row'
                elif mutation == 'ambiguous': changed['nativeParagraph'] = 1
                self.expected['billNos']['inputEvidence'] = changed
                report, code = self.grade()
                self.assertEqual(1, code)
                self.assertIn('reference_evidence_anchor_or_file_hash_invalid', self.row(report, 'billNos')['failures'])
        self.expected['billNos']['inputEvidence'] = reference

    def test_legacy_paragraph_anchor_retains_all_native_paragraph_numbering(self):
        self.install_native_bill_table()
        reference = self.expected['contractPeriodMonths']['inputEvidence']
        reference.pop('sourceKind')
        reference['nativeParagraph'] += 3 * (len(self.bills) + 1)
        report, _code = self.grade()
        self.assertTrue(self.row(report, 'contractPeriodMonths')['referenceEvidenceVerified'])


if __name__ == "__main__":
    unittest.main()
