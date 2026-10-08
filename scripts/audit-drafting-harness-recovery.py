#!/usr/bin/env python3
"""Offline audit of accepted evidence-recovery decisions in one saved trace.

Reads no oracle, DOCX, API, model, or database. Writes only a fresh report directory.
Exit: 0 recovery checks PASS; 1 FAIL; 2 invalid inputs; 3 no recovery / REVIEW.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

RECOVERY = {"evidence_quote_reanchored", "source_value_typography_restored"}
WRAPPERS = ("items", "findings", "variables", "results", "data", "list")
STRICT_MARKER = re.compile(r"(?m)(?:^[\t ]*|(?<=[;:])[\t ]*)(\d{1,4})[.)][\t ]*(?=[^\s\d])")
RAW_MARKER = re.compile(r"(?m)(?:^[\t ]*|(?<=[;:])[\t ]*)(\d{1,4})(?:[.)][\t ]*|(?=[^\W\d_]))")


def identity(text):
    return re.sub(r"\s+", " ", text.translate(str.maketrans({"“": '"', "”": '"', "‘": "'", "’": "'", "\u00a0": " "}))).strip()


def present(source, literal):
    return isinstance(literal, str) and bool(literal.strip()) and identity(literal) in identity(source)


def typed_json_equal(left, right):
    # Unlike Python equality, preserve JSON Boolean versus integer and 1 versus 1.0.
    return json.dumps(left, sort_keys=True, ensure_ascii=False, allow_nan=False) == json.dumps(right, sort_keys=True, ensure_ascii=False, allow_nan=False)


def decode_items(raw):
    """Documented JSON/fence/think/wrapper protocol; never evaluates model text."""
    if not isinstance(raw, str):
        raise ValueError("rawResponse is absent")
    text = raw.strip()
    while text.startswith("<think>"):
        end = text.find("</think>")
        if end < 0:
            raise ValueError("unfinished reasoning block")
        text = text[end + 8:].strip()
    if text.startswith("```"):
        end = text.find("\n")
        if end >= 0:
            text = text[end + 1:]
        end = text.rfind("```")
        if end >= 0:
            text = text[:end]
    starts = [at for at in (text.find("{"), text.find("[")) if at >= 0]
    if not starts:
        raise ValueError("rawResponse contains no JSON container")
    node, _ = json.JSONDecoder(parse_constant=lambda text: (_ for _ in ()).throw(ValueError(text))).raw_decode(text[min(starts):])

    def unwrap(value, depth=0):
        if depth > 20:
            raise ValueError("unsupported wrapper depth")
        if isinstance(value, dict):
            for key in WRAPPERS:
                if isinstance(value.get(key), list):
                    return unwrap(value[key], depth + 1)
        if isinstance(value, list):
            result = []
            for item in value:
                wrapped = next((key for key in WRAPPERS if isinstance(item, dict) and isinstance(item.get(key), list)), None)
                result.extend(unwrap(item[wrapped], depth + 1)) if wrapped else result.append(item)
            return result
        return value

    items = unwrap(node)
    if not isinstance(items, list):
        raise ValueError("decoded output is not an item array")
    return items


def java_slice(source, start, end):
    # Trace offsets originate in Java String and therefore count UTF-16 code units.
    data = source.encode("utf-16-le", errors="surrogatepass")
    if type(start) is not int or type(end) is not int or not 0 <= start <= end <= len(data) // 2:
        raise ValueError("invalid original UTF-16 offsets")
    return data[start * 2:end * 2].decode("utf-16-le")


def numbered_items(text, marker=STRICT_MARKER):
    matches = list(marker.finditer(text))
    result = []
    for index, match in enumerate(matches):
        body = identity(text[match.end():matches[index + 1].start() if index + 1 < len(matches) else len(text)])
        # Only one enumeration separator is harmless; additional punctuation is model output.
        result.append((int(match.group(1)), body[:-1].strip() if body.endswith(";") else body))
    return result


def bill_rows(quote):
    rows = []
    for line in quote.splitlines():
        cells = [cell.strip() for cell in line.split("|")]
        if len(cells) >= 2 and re.fullmatch(r"\d+[A-Za-z]?(?:\.\d+)?", cells[0]) and cells[1]:
            rows.append({"number": cells[0], "description": cells[1], "typeCell": cells[2] if len(cells) > 2 else ""})
    return rows


def audit(trace):
    if not isinstance(trace, dict) or trace.get("status") != "completed" or not trace.get("runId"):
        raise ValueError("a completed trace with runId is required")
    parts = trace.get("parts")
    if not isinstance(parts, list) or not isinstance(trace.get("decisions"), list):
        raise ValueError("parts and decisions arrays are required")
    by_part = {part["partId"]: part for part in parts}
    if len(by_part) != len(parts):
        raise ValueError("duplicate partId")
    grouped = defaultdict(list)
    for part in parts:
        grouped[str(part["sourceDocumentId"])].append(part)
    originals = {}
    for doc_id, source_parts in grouped.items():
        source_parts.sort(key=lambda part: part["partIndex"])
        if [part["partIndex"] for part in source_parts] != list(range(len(source_parts))):
            raise ValueError("original core parts are missing or duplicated")
        if len({(part.get("fileName"), part.get("sourceHash")) for part in source_parts}) != 1:
            raise ValueError("source identity changes within a document")
        originals[doc_id] = "".join(part["sourceText"] for part in source_parts)
    ledger = []
    for index, decision in enumerate(trace["decisions"]):
        codes = sorted(RECOVERY.intersection(decision.get("codes") or []))
        if decision.get("status") != "accepted" or not codes:
            continue
        checks, notes = {}, []
        row = {"decisionIndex": index, "key": decision.get("key"), "partId": decision.get("partId"),
               "attemptIndex": decision.get("attemptIndex"), "itemIndex": decision.get("itemIndex"), "recoveryCodes": codes,
               "checks": checks, "notes": notes, "semanticEntailment": "NOT_PROVEN_BY_THIS_AUDIT"}
        ledger.append(row)
        try:
            part = by_part[decision["partId"]]
            attempts = [attempt for attempt in part.get("attempts", []) if attempt.get("attemptIndex") == decision["attemptIndex"]]
            if len(attempts) != 1:
                raise ValueError("missing or duplicate original attempt")
            attempt = attempts[0]
            items = decode_items(attempt.get("rawResponse"))
            item_index = decision["itemIndex"]
            if type(item_index) is not int or not 0 <= item_index < len(items) or not isinstance(items[item_index], dict):
                raise ValueError("original item index is invalid")
            item = items[item_index]
            checks["raw_item_key_matches"] = item.get("key") == decision.get("key")
            checks["raw_value_unchanged"] = typed_json_equal(item.get("value"), decision.get("rawValue"))
            row["originalModelSourceQuote"] = item.get("sourceQuote")
            row["effectiveSourceQuote"] = decision.get("sourceQuote")
            row["originalRawValue"] = item.get("value")
            row["normalizedValue"] = decision.get("normalizedValue")
            context = attempt.get("context")
            if not isinstance(context, dict):
                raise ValueError("recovery has no recorded actual attempt context")
            supplied = context.get("sourceText")
            original = originals[str(part["sourceDocumentId"])]
            checks["context_exact_original_offset_slice"] = isinstance(supplied, str) and supplied == java_slice(original, context.get("sourceStart"), context.get("sourceEnd"))
            checks["effective_quote_in_actual_attempt_context"] = isinstance(supplied, str) and present(supplied, decision.get("sourceQuote"))
            checks["effective_quote_in_original_source"] = present(original, decision.get("sourceQuote"))
            checks["confidence_still_within_acceptance_range"] = type(item.get("confidence")) in (int, float) and 0.70 <= item["confidence"] <= 1
            row["source"] = {"fileName": part.get("fileName"), "sourceDocumentId": part.get("sourceDocumentId"), "parsedSourceHash": part.get("sourceHash"),
                             "contextStartUtf16": context.get("sourceStart"), "contextEndUtf16": context.get("sourceEnd"),
                             "originalParsedUtf8Sha256": hashlib.sha256(original.encode("utf-8")).hexdigest()}
            normalized, raw = decision.get("normalizedValue"), item.get("value")
            if decision.get("key") == "billNos":
                value = json.loads(normalized)
                quoted = bill_rows(decision.get("sourceQuote") or "")
                checks["bill_rows_match_complete_quoted_table"] = isinstance(value, list) and bool(quoted) and Counter((str(row.get("number")), identity(str(row.get("description")))) for row in value) == Counter((row["number"], identity(row["description"])) for row in quoted)
                checks["bill_number_identity_is_unique"] = isinstance(value, list) and len({str(row.get("number")) for row in value}) == len(value)
                checks["bill_raw_identities_unchanged"] = isinstance(raw, list) and isinstance(value, list) and Counter((str(row.get("number")), identity(str(row.get("description")))) for row in raw) == Counter((str(row.get("number")), identity(str(row.get("description")))) for row in value)
                checks["retained_bill_types_literal_in_own_table_row"] = isinstance(value, list) and all(not row.get("type") or any(str(row.get("number")) == source["number"] and identity(str(row.get("description"))) == identity(source["description"]) and str(row["type"]).casefold() == source["typeCell"].casefold() for source in quoted) for row in value)
                notes.append("Bill optional metadata roles, source supersession and prose identity statements are not semantically certified.")
            elif isinstance(normalized, str) and isinstance(raw, str):
                normalized_items, raw_items = numbered_items(normalized), numbered_items(raw, RAW_MARKER)
                numbers = [number for number, _ in normalized_items]
                quoted_numbers = [int(match.group(1)) for match in STRICT_MARKER.finditer(decision.get("sourceQuote") or "")]
                checks["normalized_value_is_exact_original_substring"] = normalized in original
                checks["normalized_value_is_contiguous_in_actual_context"] = present(supplied, normalized)
                checks["normalized_numbered_sequence_is_complete_in_quote"] = bool(numbers) and numbers == quoted_numbers
                checks["normalized_numbered_sequence_has_no_gaps_or_duplicates"] = bool(numbers) and numbers == list(range(numbers[0], numbers[0] + len(numbers)))
                checks["raw_and_normalized_ordered_bodies_and_identifiers_equal"] = bool(normalized_items) and raw_items == normalized_items
                if "source_value_typography_restored" not in codes:
                    checks["no_value_change_for_quote_only_recovery"] = typed_json_equal(raw, normalized)
                row["numberedIdentifiers"] = numbers
                notes.append("Per-item bodies and identifiers are literal checks. Contractual applicability and semantic completeness are not independently proven.")
            else:
                row["unsupportedValueShape"] = True
                notes.append("Unknown recovered value shape; manual audit required.")
        except (KeyError, TypeError, ValueError, AttributeError, UnicodeError) as failure:
            checks["audit_inputs_decodable"] = False
            row["auditError"] = str(failure)
        row["verdict"] = "FAIL" if not checks or not all(checks.values()) else "REVIEW" if row.get("unsupportedValueShape") else "PASS"
    failed = sum(row["verdict"] == "FAIL" for row in ledger)
    review = sum(row["verdict"] == "REVIEW" for row in ledger)
    verdict = "FAIL" if failed else "REVIEW" if review else "PASS" if ledger else "NO_RECOVERY_OBSERVED"
    return {"schemaVersion": 1, "auditedAtUtc": datetime.now(timezone.utc).isoformat(), "runId": trace["runId"], "harnessVersion": trace.get("harnessVersion"),
            "verdict": verdict, "recoveredAcceptedDecisions": len(ledger), "pass": len(ledger) - failed - review, "fail": failed, "review": review,
            "scope": "Accepted recovery decisions only; not whole-exam grading or document/adoption acceptance.", "referenceAnswersRead": False,
            "boundaries": {"sourceDocxByteIdentity": "NOT_EVALUATED_USE_EXAM_SCORER", "semanticEntailment": "NOT_PROVEN", "adoptionAndPersistence": "NOT_EVALUATED",
                           "unrecoveredAcceptedCandidates": "NOT_AUDITED", "originalSource": "Reconstructed from contiguous recorded core parts; no external source independently fetched."},
            "ledger": ledger}


def markdown(report):
    lines = ["# Drafting recovery audit: " + report["verdict"], "", f"Run `{report.get('runId')}`; accepted recovery decisions {report.get('recoveredAcceptedDecisions', 0)}.", "",
             report.get("scope", "Invalid input"), "", "This checks immutable raw output, actual request context and literal ordered source support. It does not independently prove contractual semantics.", ""]
    for row in report.get("ledger", []):
        lines += [f"## {row['key']} — {row['verdict']}", "", f"Decision {row['decisionIndex']}, part `{row['partId']}`, attempt {row['attemptIndex']}, item {row['itemIndex']}.", "",
                  "| Check | Result |", "|---|---|"]
        lines += [f"| {key} | {'PASS' if value else 'FAIL'} |" for key, value in row["checks"].items()]
        if row.get("auditError"):
            lines += ["", "Audit error: " + row["auditError"]]
        for key in ("originalModelSourceQuote", "effectiveSourceQuote", "originalRawValue", "normalizedValue"):
            lines += ["", key + ":", "", "```json", json.dumps(row.get(key), ensure_ascii=False, indent=2), "```"]
        lines += ["", "Boundaries: " + " ".join(row["notes"])]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    if args.out_dir.exists():
        parser.error("Use a fresh report directory; previous audit evidence must not be overwritten.")
    args.out_dir.mkdir(parents=True)
    try:
        payload = json.loads(args.trace.read_text(encoding="utf-8-sig"))
        if isinstance(payload, dict) and "code" in payload:
            if payload["code"] != 0:
                raise ValueError("trace API snapshot is unsuccessful")
            payload = payload.get("data")
        report = audit(payload)
        report["tracePath"] = str(args.trace.resolve())
        report["traceSha256"] = hashlib.sha256(args.trace.read_bytes()).hexdigest()
        report["auditScriptSha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        exit_code = 1 if report["verdict"] == "FAIL" else 3 if report["verdict"] in {"REVIEW", "NO_RECOVERY_OBSERVED"} else 0
    except (OSError, ValueError, TypeError, KeyError) as failure:
        report = {"verdict": "INPUT_ERROR", "error": str(failure), "scope": "No application write, network or model call performed."}
        exit_code = 2
    (args.out_dir / "recovery-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.out_dir / "recovery-audit.md").write_text(markdown(report), encoding="utf-8")
    print(json.dumps({key: report.get(key) for key in ("verdict", "runId", "recoveredAcceptedDecisions", "pass", "fail", "review", "error")}, ensure_ascii=False, indent=2))
    raise SystemExit(exit_code)


if __name__ == "__main__":
    main()
