"""Offline auditor integrity tests. No API, model or application database."""
import importlib.util
import json
from copy import deepcopy
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("recovery_auditor", ROOT / "scripts/audit-drafting-harness-recovery.py")
AUDITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDITOR)


def trace_fixture():
    fixture = json.loads((ROOT / "ai_consense_service/src/test/resources/drafting/harness/captured-bill-waterproofing/fixture.json").read_text(encoding="utf-8"))
    source = fixture["sources"]["water"]["originalSource"]
    label = "Other waterproofing areas specified in Specification: "
    start = source.index(label) + len(label)
    value = source[start:source.index("\n\nFull 18 area list copied", start)]
    raw = value.replace("7.Internal tankings;", "7Internal tankings;")
    item = {"key": "otherWaterproofingSpecificationAreas", "value": raw, "sourceQuote": label + raw, "confidence": 0.95}
    context = {"sourceStart": 0, "sourceEnd": len(source.encode("utf-16-le")) // 2, "sourceText": source}
    part = {"partId": "1:0", "partIndex": 0, "sourceDocumentId": 1, "sourceHash": "fixture-only", "fileName": "SIM11.docx", "sourceText": source,
            "attempts": [{"attemptIndex": 1, "rawResponse": json.dumps([item]), "context": context}]}
    decision = {"partId": "1:0", "attemptIndex": 1, "itemIndex": 0, "key": item["key"], "status": "accepted", "rawValue": raw,
                "normalizedValue": value, "sourceQuote": label + value, "codes": sorted(AUDITOR.RECOVERY)}
    return {"status": "completed", "runId": "offline-auditor-test", "parts": [part], "decisions": [decision]}


def bill_trace_fixture(rows=None):
    """Synthetic accepted trace to test the offline auditor, not model accuracy."""
    rows = deepcopy(rows) if rows is not None else [
        {"number": "1", "description": "Preliminaries", "type": "BQ"},
        {"number": "1", "description": "Preambles", "type": "SOR"},
    ]
    source = "The complete Bill identity list follows.\nBill No | Formal description | Pricing type\n"
    source += "\n".join(f"{row['number']} | {row['description']} | {row.get('type', '')}" for row in rows) + "\n"
    item = {"key": "billNos", "value": rows, "sourceQuote": "Bill No | Formal description | Pricing type", "confidence": 0.95}
    context = {"sourceStart": 0, "sourceEnd": len(source.encode("utf-16-le")) // 2, "sourceText": source}
    part = {"partId": "bill:0", "partIndex": 0, "sourceDocumentId": "bill", "sourceHash": "fixture-only", "fileName": "synthetic-bills.docx", "sourceText": source,
            "attempts": [{"attemptIndex": 1, "rawResponse": json.dumps([item]), "context": context}]}
    decision = {"partId": "bill:0", "attemptIndex": 1, "itemIndex": 0, "key": "billNos", "status": "accepted", "rawValue": rows,
                "normalizedValue": json.dumps(rows), "sourceQuote": source, "codes": ["evidence_quote_reanchored"]}
    return {"status": "completed", "runId": "offline-bill-auditor-test", "parts": [part], "decisions": [decision]}


class RecoveryAuditIntegrityTest(unittest.TestCase):
    def test_literal_source_marker_restoration_is_auditable_without_reference_answers(self):
        result = AUDITOR.audit(trace_fixture())
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(1, result["recoveredAcceptedDecisions"])
        self.assertFalse(result["referenceAnswersRead"])
        self.assertEqual("NOT_PROVEN", result["boundaries"]["semanticEntailment"])

    def test_rewriting_the_retained_raw_value_is_detected(self):
        trace = trace_fixture()
        trace["decisions"][0]["rawValue"] = trace["decisions"][0]["normalizedValue"]
        result = AUDITOR.audit(trace)
        self.assertEqual("FAIL", result["verdict"])
        self.assertFalse(result["ledger"][0]["checks"]["raw_value_unchanged"])

    def test_contiguous_quote_outside_the_actual_request_context_is_detected(self):
        trace = trace_fixture()
        context = trace["parts"][0]["attempts"][0]["context"]
        source = context["sourceText"]
        context.update(sourceEnd=source.index("Other waterproofing areas specified in Specification:"), sourceText=source[:source.index("Other waterproofing areas specified in Specification:")])
        result = AUDITOR.audit(trace)
        self.assertEqual("FAIL", result["verdict"])
        self.assertTrue(result["ledger"][0]["checks"]["context_exact_original_offset_slice"])
        self.assertFalse(result["ledger"][0]["checks"]["effective_quote_in_actual_attempt_context"])

    def test_source_offset_tampering_is_detected_even_when_quote_remains_visible(self):
        trace = trace_fixture()
        trace["parts"][0]["attempts"][0]["context"]["sourceStart"] = 1
        result = AUDITOR.audit(trace)
        self.assertEqual("FAIL", result["verdict"])
        self.assertFalse(result["ledger"][0]["checks"]["context_exact_original_offset_slice"])

    def test_a_changed_or_missing_body_cannot_pass_normalized_support(self):
        for operation in (lambda value: value.replace("7.Internal tankings;", "7.External tankings;"),
                          lambda value: value.replace("13.G/F level slabs;\n\n", "")):
            trace = trace_fixture()
            trace["decisions"][0]["normalizedValue"] = operation(trace["decisions"][0]["normalizedValue"])
            result = AUDITOR.audit(trace)
            self.assertEqual("FAIL", result["verdict"])

    def test_no_recovery_is_reported_explicitly_instead_of_vacuous_pass(self):
        trace = trace_fixture()
        trace["decisions"][0]["codes"] = []
        result = AUDITOR.audit(trace)
        self.assertEqual("NO_RECOVERY_OBSERVED", result["verdict"])
        self.assertEqual(0, result["recoveredAcceptedDecisions"])

    def test_protocol_wrappers_and_utf16_offsets_are_supported_without_evaluation(self):
        self.assertEqual([{"key": "test", "value": True}], AUDITOR.decode_items('<think>["ignore"]</think>\n```json\n{"data":[{"variables":[{"key":"test","value":true}]}]}\n```'))
        self.assertEqual("😀", AUDITOR.java_slice("A😀B", 1, 3))
        self.assertFalse(AUDITOR.typed_json_equal(True, 1))
        with self.assertRaises(ValueError):
            AUDITOR.decode_items("<think>unfinished")

    def test_bq1_and_sor1_use_only_literal_own_row_type_namespaces(self):
        result = AUDITOR.audit(bill_trace_fixture())
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual([("BQ", "1"), ("SOR", "1")], result["ledger"][0]["billLiteralIdentityKeys"])
        self.assertTrue(result["ledger"][0]["checks"]["bill_raw_types_unchanged"])

    def test_same_namespace_number_cannot_be_deduplicated_by_formal_description(self):
        result = AUDITOR.audit(bill_trace_fixture([
            {"number": "1", "description": "Preliminaries", "type": "BQ"},
            {"number": "1", "description": "Different formal description", "type": "BQ"},
        ]))
        self.assertEqual("FAIL", result["verdict"])
        self.assertFalse(result["ledger"][0]["checks"]["bill_identity_is_unique_in_literal_source_type_namespace"])

    def test_a_missing_type_for_an_overlapping_number_is_ambiguous(self):
        trace = bill_trace_fixture()
        raw = json.loads(trace["parts"][0]["attempts"][0]["rawResponse"])
        del raw[0]["value"][1]["type"]
        trace["parts"][0]["attempts"][0]["rawResponse"] = json.dumps(raw)
        trace["decisions"][0]["rawValue"] = raw[0]["value"]
        trace["decisions"][0]["normalizedValue"] = json.dumps(raw[0]["value"])
        result = AUDITOR.audit(trace)
        self.assertEqual("FAIL", result["verdict"])
        self.assertTrue(result["ledger"][0]["checks"]["bill_raw_types_unchanged"])
        self.assertFalse(result["ledger"][0]["checks"]["bill_identity_is_unique_in_literal_source_type_namespace"])

    def test_a_literal_normalized_type_cannot_be_invented_from_missing_raw_type(self):
        trace = bill_trace_fixture()
        raw = json.loads(trace["parts"][0]["attempts"][0]["rawResponse"])
        del raw[0]["value"][1]["type"]
        trace["parts"][0]["attempts"][0]["rawResponse"] = json.dumps(raw)
        trace["decisions"][0]["rawValue"] = raw[0]["value"]
        result = AUDITOR.audit(trace)
        self.assertEqual("FAIL", result["verdict"])
        self.assertFalse(result["ledger"][0]["checks"]["bill_raw_types_unchanged"])

    def test_a_type_in_another_row_cannot_support_the_overlapping_identity(self):
        trace = bill_trace_fixture()
        original = trace["parts"][0]["sourceText"]
        changed = original.replace("1 | Preambles | SOR", "1 | Preambles | BQ")
        trace["parts"][0]["sourceText"] = changed
        trace["parts"][0]["attempts"][0]["context"]["sourceText"] = changed
        trace["parts"][0]["attempts"][0]["context"]["sourceEnd"] = len(changed.encode("utf-16-le")) // 2
        trace["decisions"][0]["sourceQuote"] = changed
        result = AUDITOR.audit(trace)
        self.assertEqual("FAIL", result["verdict"])
        self.assertFalse(result["ledger"][0]["checks"]["retained_bill_types_literal_in_own_table_row"])
        self.assertFalse(result["ledger"][0]["checks"]["bill_identity_is_unique_in_literal_source_type_namespace"])

    def test_an_untyped_unique_number_retains_original_strict_identity(self):
        result = AUDITOR.audit(bill_trace_fixture([
            {"number": "1", "description": "Preliminaries"},
            {"number": "2", "description": "Concrete works"},
        ]))
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual([("untyped", "1"), ("untyped", "2")], result["ledger"][0]["billLiteralIdentityKeys"])

    def test_boolean_or_numeric_type_is_not_a_missing_type(self):
        for invalid_type in (False, 0):
            with self.subTest(invalid_type=invalid_type):
                result = AUDITOR.audit(bill_trace_fixture([{"number": "1", "description": "Preliminaries", "type": invalid_type}]))
                self.assertEqual("FAIL", result["verdict"])
                self.assertFalse(result["ledger"][0]["checks"]["bill_identity_is_unique_in_literal_source_type_namespace"])

    def test_bill_type_namespace_does_not_relax_raw_immutability_or_context(self):
        trace = bill_trace_fixture()
        trace["decisions"][0]["rawValue"] = []
        trace["parts"][0]["attempts"][0]["context"]["sourceStart"] = 1
        result = AUDITOR.audit(trace)
        self.assertEqual("FAIL", result["verdict"])
        self.assertFalse(result["ledger"][0]["checks"]["raw_value_unchanged"])
        self.assertFalse(result["ledger"][0]["checks"]["context_exact_original_offset_slice"])


if __name__ == "__main__":
    unittest.main()
