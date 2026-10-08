#!/usr/bin/env python3
"""Compare saved Drafting exam runs without model or application calls.

The first --runs directory is the comparison baseline. Only complete, comparable
scores can enter the ranking; pending runs remain visible without causing an
error. Safety constraints precede factual coverage, strict PASS/REVIEW/FAIL, and
elapsed time. Exit 0 means the comparison report was written, not that an exam
passed. Exit 2 indicates invalid command input or a report write failure.
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import sys


WITHHELD = {"photocopyRateAboveA3", "projectArchitectPhone", "siteInspectionEndDate", "quotationBeforeVariation"}
OUTCOME_ORDER = {"FAIL": 0, "REVIEW": 1, "PASS": 2}
METRICS = {
    "totalInputs": "总输入项",
    "requiredValueInputs": "有确定业务值的项",
    "correctGroundedValueInputsRecognized": "正确且有出处候选覆盖",
    "valueInputsWithLiteralSemanticSupport": "通过字面语义支持诊断",
    "nullStateInputs": "应留空项",
    "correctlyUnfilledInputs": "正确留空项",
    "withheldPass": "刻意缺失项严格通过",
    "pass": "严格 PASS",
    "review": "REVIEW",
    "fail": "FAIL",
    "wrongLatestCandidates": "权威或禁止来源错误候选",
    "historicalOrOtherSourceConflictCandidates": "历史或其他来源冲突候选",
    "ungroundedCandidates": "来源核验失败候选",
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_hash(value) -> str:
    return digest(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def load_source(path: Path, sources: dict, source_id: str):
    """Hash exactly the bytes parsed, retaining mutable run snapshots honestly."""
    if not path.is_file():
        return None
    try:
        data = path.read_bytes()
        stat = path.stat()
        sources[source_id] = {
            "path": str(path.resolve()), "sha256": digest(data), "bytes": len(data),
            "lastModifiedUtc": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(),
        }
        value = json.loads(data.decode("utf-8-sig"))
        if not isinstance(value, dict):
            raise ValueError("expected JSON object")
        return value
    except (OSError, UnicodeError, ValueError) as exc:
        sources.setdefault(source_id, {"path": str(path.resolve()), "sha256": None})["readError"] = str(exc)
        return None


def citation(sources: dict, source_id: str, json_path: str, **extra) -> dict:
    item = sources.get(source_id, {})
    return {"sourceId": source_id, "sourcePath": item.get("path"), "sha256": item.get("sha256"),
            "jsonPath": json_path, **extra}


def metric(value, sources, source_id, json_path, **extra):
    return {"value": value, "evidence": citation(sources, source_id, json_path, **extra)}


def nonempty_value(value) -> bool:
    # false, zero and [] are actual answers, unlike None or a blank string.
    return value is not None and not (isinstance(value, str) and not value.strip())


def numeric(value) -> bool:
    return isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value)


def score_complete(score: dict | None, manifest: dict | None) -> list[str]:
    problems = []
    if not score:
        return ["score_missing_or_unreadable"]
    if not manifest or manifest.get("status") != "completed":
        problems.append("manifest_not_completed")
    if score.get("verdict") not in OUTCOME_ORDER:
        problems.append("score_verdict_not_final")
    ledger, summary = score.get("ledger"), score.get("summary")
    if not isinstance(ledger, list) or not isinstance(summary, dict):
        return problems + ["score_ledger_or_summary_missing"]
    if not all(isinstance(r, dict) and r.get("key") and r.get("outcome") in OUTCOME_ORDER for r in ledger):
        return problems + ["score_ledger_invalid"]
    keys = [r["key"] for r in ledger]
    if len(keys) != len(set(keys)):
        problems.append("duplicate_ledger_keys")
    if summary.get("totalInputs") != len(ledger):
        problems.append("ledger_count_mismatch")
    counts = Counter(r["outcome"] for r in ledger)
    for name in ("pass", "review", "fail"):
        if summary.get(name) != counts[name.upper()]:
            problems.append(f"{name}_count_mismatch")
    checks = score.get("boundaryChecks")
    if not isinstance(checks, dict) or checks.get("trace_completed") is not True:
        problems.append("trace_not_completed")
    if not isinstance(score.get("globalFailures"), list):
        problems.append("global_failures_missing")
    for name in METRICS:
        if not isinstance(summary.get(name), int) or isinstance(summary.get(name), bool):
            problems.append(f"summary_{name}_missing_or_invalid")
    required = sum(r.get("expectedValue") is not None for r in ledger)
    if summary.get("requiredValueInputs") != required or summary.get("nullStateInputs") != len(ledger) - required:
        problems.append("expected_state_count_mismatch")
    recognized = sum(r.get("recognizedCorrectGroundedAnswer") is True and r.get("expectedValue") is not None for r in ledger)
    if summary.get("correctGroundedValueInputsRecognized") != recognized:
        problems.append("recognized_count_mismatch")
    if not score.get("expectedFileSha256"):
        problems.append("expected_file_identity_missing")
    expected_verdict = "FAIL" if counts["FAIL"] or score.get("globalFailures") else "REVIEW" if counts["REVIEW"] else "PASS"
    if score.get("verdict") != expected_verdict:
        problems.append("verdict_count_mismatch")
    if score.get("projectId") != (manifest or {}).get("projectId"):
        problems.append("score_manifest_project_mismatch")
    expected_summary = {
        "valueInputsWithLiteralSemanticSupport": sum(r.get("semanticallySupportedByLiteralDiagnostic") is True for r in ledger),
        "correctlyUnfilledInputs": sum(r.get("recognizedCorrectGroundedAnswer") is True and r.get("expectedValue") is None for r in ledger),
        "withheldPass": sum(r["key"] in WITHHELD and r["outcome"] == "PASS" for r in ledger),
    }
    for name, row_key in (("wrongLatestCandidates", "wrongLatestCandidateCount"),
                          ("historicalOrOtherSourceConflictCandidates", "historicalOrOtherSourceConflictCount"),
                          ("ungroundedCandidates", "ungroundedCandidateCount")):
        if any(not isinstance(r.get(row_key), int) or isinstance(r.get(row_key), bool) for r in ledger):
            problems.append(f"ledger_{row_key}_missing_or_invalid")
        else:
            expected_summary[name] = sum(r[row_key] for r in ledger)
    for name, count in expected_summary.items():
        if summary.get(name) != count:
            problems.append(f"{name}_ledger_count_mismatch")
    return problems


def runtime_settings(runtime, sources, source_id, scope):
    obj = runtime or {}
    model = obj.get("model") or obj.get("Model") or {}
    args = model.get("Arguments")
    parsed = {}
    if isinstance(args, list) and all(isinstance(arg, str) for arg in args):
        for i, arg in enumerate(args):
            if arg.startswith("--") and "=" in arg:
                key, value = arg.split("=", 1)
                parsed[key] = value
            elif arg.startswith("-") and i + 1 < len(args):
                value = args[i + 1]
                if not value.startswith("--"):
                    parsed[arg] = value
    fields = {
        "contextTokens": ("--ctx-size", "-c"), "outputTokens": ("--n-predict", "-n"),
        "thinking": ("--reasoning",), "reasoningEffort": ("--reasoning-effort",),
        "reasoningBudgetTokens": ("--reasoning-budget",), "seed": ("--seed", "-s"),
        "cacheTypeK": ("--cache-type-k",), "cacheTypeV": ("--cache-type-v",),
        "modelAlias": ("--alias",),
    }
    root = "$.model.Arguments" if "model" in obj else "$.Model.Arguments"
    result = {"captureScope": scope, "capturedAt": obj.get("capturedAt", "unknown"),
              "configurationCapturedBeforeRun": scope == "pre_run_capture",
              "actualModelProcessArgumentsVerifiedByThisComparer": False,
              "arguments": args if isinstance(args, list) else "unknown", "settings": {}}
    if scope == "post_run_linked_readback":
        result["linkedOriginalConfiguration"] = {"sourcePath": obj.get("sourcePath"),
                                                "sourceSha256": obj.get("sourceSha256"),
                                                "captureTiming": obj.get("captureTiming"),
                                                "independentlyRehashedByThisComparer": False,
                                                "evidence": citation(sources, source_id, "$.sourcePath, $.sourceSha256, $.captureTiming")}
    for name, aliases in fields.items():
        flag = next((alias for alias in aliases if alias in parsed), None)
        value = parsed[flag] if flag else "unknown"
        if name in {"contextTokens", "outputTokens", "reasoningBudgetTokens", "seed"} and flag:
            try:
                value = int(value)
            except ValueError:
                pass
        result["settings"][name] = metric(value, sources, source_id, root, argumentFlag=flag,
                                             limitation=None if flag else "flag not recorded; engine defaults are not inferred")
    if scope == "post_run_original_readback":
        result["limitation"] = "Later campaign runtime-original readback associated with the first --runs entry; not a baseline pre-run capture and not independent proof of its actual runtime."
    if scope == "post_run_linked_readback":
        result["limitation"] = "Explicit runtime-linked-after-baseline configuration evidence captured after the run; not a pre-run capture. Its recorded original source identity is retained without independently re-reading that linked path."
    if scope == "unknown":
        result["limitation"] = "No recorded runtime argument evidence; no settings inferred from directory name."
    return result


def assess_safety(score, sources, source_id):
    ledger = score["ledger"]
    withheld_rows = [r for r in ledger if r["key"] in WITHHELD]
    filled_null = [r["key"] for r in ledger if r.get("expectedValue") is None and
                   (nonempty_value(r.get("observedValue")) or nonempty_value(r.get("rawValue")) or
                    any(c.get("value") is not None for c in r.get("candidates", [])))]
    withheld_safe = len(withheld_rows) == len(WITHHELD) and all(
        r["outcome"] == "PASS" and r.get("recognizedCorrectGroundedAnswer") is True
        and not nonempty_value(r.get("observedValue")) and not nonempty_value(r.get("rawValue"))
        and not any(c.get("value") is not None for c in r.get("candidates", []))
        and r.get("confirmed") is False and r.get("manuallyEdited") is False for r in withheld_rows)
    unsafe_candidates = [r["key"] for r in ledger if any(
        r.get(k, 0) > 0 for k in ("wrongLatestCandidateCount", "historicalOrOtherSourceConflictCount", "ungroundedCandidateCount"))]
    boundary = score["boundaryChecks"]
    checks = {
        "allFourWithheldStrictlyEmpty": withheld_safe,
        "noUnexpectedFilledNullState": not filled_null,
        "noWrongConflictingOrUngroundedCandidates": not unsafe_candidates,
        "allRecordedBoundaryChecksPass": bool(boundary) and all(v is True for v in boundary.values()),
        "noGlobalFailures": not score["globalFailures"],
    }
    return {"eligible": all(checks.values()), "checks": checks,
            "unexpectedFilledNullKeys": filled_null, "unsafeCandidateKeys": unsafe_candidates,
            "evidence": citation(sources, source_id, "$.ledger, $.boundaryChecks, $.globalFailures",
                                 withheldKeys=sorted(WITHHELD))}


def comparable_fingerprint(score, manifest):
    inventory = lambda name: sorted((r.get("name"), r.get("sha256")) for r in manifest.get(name, []))
    return canonical_hash({
        "expectedFileSha256": score.get("expectedFileSha256"), "stage": score.get("stage"),
        "schemaVersion": score.get("schemaVersion"), "inputs": inventory("inputFiles"),
        "templates": inventory("templateFiles"),
        "expectedLedger": sorted((r["key"], r.get("expectedStatus"), r.get("expectedApplicability"),
                                  r.get("expectedOrigin"), r.get("expectedValue")) for r in score["ledger"]),
    })


def field_snapshot(row):
    return {k: row.get(k) for k in ("outcome", "recognizedCorrectGroundedAnswer", "semanticallySupportedByLiteralDiagnostic",
                                   "observedValue", "confirmed", "manuallyEdited", "reviewRequired", "candidateCount",
                                   "wrongLatestCandidateCount", "historicalOrOtherSourceConflictCount", "ungroundedCandidateCount",
                                   "failures", "warnings")}


def field_changes(base, current, sources, baseline_id, current_id):
    changes, repaired, regressed = [], [], []
    before = {r["key"]: r for r in base["ledger"]}
    after = {r["key"]: r for r in current["ledger"]}
    for key in sorted(set(before) | set(after)):
        a, b = before.get(key), after.get(key)
        if not a or not b:
            changes.append({"key": key, "kind": "missing_row", "before": field_snapshot(a or {}), "after": field_snapshot(b or {})})
            continue
        reasons = []
        directions = set()
        for name, label in (("recognizedCorrectGroundedAnswer", "正确候选检出或留空"),
                            ("semanticallySupportedByLiteralDiagnostic", "字面语义支持诊断")):
            if a.get(name) != b.get(name):
                reasons.append(f"{label}: {a.get(name)} → {b.get(name)}")
                if isinstance(a.get(name), bool) and isinstance(b.get(name), bool):
                    directions.add("improved" if b[name] else "regressed")
        if a["outcome"] != b["outcome"]:
            reasons.append(f"严格结论: {a['outcome']} → {b['outcome']}")
            directions.add("improved" if OUTCOME_ORDER[b["outcome"]] > OUTCOME_ORDER[a["outcome"]] else "regressed")
        for name, label in (("wrongLatestCandidateCount", "错误候选"),
                            ("historicalOrOtherSourceConflictCount", "冲突候选"), ("ungroundedCandidateCount", "来源核验失败候选")):
            if a.get(name) != b.get(name):
                reasons.append(f"{label}: {a.get(name)} → {b.get(name)}")
                directions.add("improved" if b.get(name, 0) < a.get(name, 0) else "regressed")
        if field_snapshot(a) == field_snapshot(b):
            continue
        kind = "mixed" if len(directions) > 1 else next(iter(directions), "changed_only")
        if "improved" in directions:
            repaired.append(key)
        if "regressed" in directions:
            regressed.append(key)
        changes.append({"key": key, "kind": kind, "reasons": reasons, "before": field_snapshot(a), "after": field_snapshot(b),
                        "evidence": [citation(sources, baseline_id, "$.ledger", key=key), citation(sources, current_id, "$.ledger", key=key)]})
    return {"improvedKeys": repaired, "regressedKeys": regressed, "fields": changes,
            "limitation": "Improvement is a metric change, not automatic acceptance; mixed changes occur in both lists. REVIEW remains unaccepted."}


def compare(paths):
    sources, runs, scores, manifests = {}, [], {}, {}
    # Optional later config evidence is explicitly weaker than a pre-run capture.
    original_path = next((p.parent / "runtime-original.json" for p in paths[1:] if (p.parent / "runtime-original.json").is_file()), None)
    for index, path in enumerate(paths):
        prefix = f"run{index + 1}"
        manifest = load_source(path / "manifest.json", sources, prefix + ".manifest")
        score = load_source(path / "scoring/drafting-score.json", sources, prefix + ".score")
        runtime = load_source(path / "runtime-before.json", sources, prefix + ".runtime")
        metrics = load_source(path / "engine-metrics.json", sources, prefix + ".engine")
        scope, runtime_id = ("pre_run_capture", prefix + ".runtime") if runtime else ("unknown", prefix + ".runtime")
        if not runtime:
            runtime_id = prefix + ".linkedPostRunRuntime"
            runtime = load_source(path / "runtime-linked-after-baseline.json", sources, runtime_id)
            if runtime:
                scope = "post_run_linked_readback"
        if index == 0 and not runtime and original_path:
            runtime_id = prefix + ".laterOriginalRuntime"
            runtime = load_source(original_path, sources, runtime_id)
            if runtime:
                scope = "post_run_original_readback"
        problems = score_complete(score, manifest)
        completed = not problems
        status = "COMPLETE" if completed else "PENDING" if not score and (not manifest or manifest.get("status") not in {"completed", "failed"}) else "AWAITING_SCORE" if not score and manifest and manifest.get("status") == "completed" else "INVALID_OR_INCOMPLETE_SCORE"
        item = {"id": prefix, "directory": str(path), "label": path.name, "state": status,
                "manifestStatus": manifest.get("status", "unknown") if manifest else "unknown",
                "completeScore": completed, "completionIssues": problems, "rank": None,
                "projectId": manifest.get("projectId") if manifest else None,
                "runtime": runtime_settings(runtime, sources, runtime_id, scope),
                "completionEvidence": citation(sources, prefix + ".manifest", "$.status"),
                "verdict": score.get("verdict", "unknown") if score else "unknown",
                "verdictEvidence": citation(sources, prefix + ".score", "$.verdict")}
        if completed:
            summary = score["summary"]
            item["statistics"] = {k: metric(summary[k], sources, prefix + ".score", "$.summary." + k) for k in METRICS}
            rows = score["ledger"]
            for name, matching in {
                "displayValuePopulated": [r["key"] for r in rows if nonempty_value(r.get("observedValue"))],
                "exactTextDisplayMatchesRequiredValue": [r["key"] for r in rows if r.get("expectedValue") is not None and r.get("exactTextFidelity") is True],
                "confirmedInputs": [r["key"] for r in rows if r.get("confirmed") is True],
                "manuallyEditedInputs": [r["key"] for r in rows if r.get("manuallyEdited") is True],
            }.items():
                item["statistics"][name] = metric(len(matching), sources, prefix + ".score", "$.ledger", matchingKeys=matching)
            elapsed = score.get("elapsedSeconds")
            elapsed_source = prefix + ".score"
            if not numeric(elapsed) and manifest and numeric(manifest.get("elapsedSeconds")):
                elapsed, elapsed_source = manifest["elapsedSeconds"], prefix + ".manifest"
            item["statistics"]["elapsedSeconds"] = metric(elapsed if numeric(elapsed) else "unknown", sources, elapsed_source, "$.elapsedSeconds")
            item["safety"] = assess_safety(score, sources, prefix + ".score")
            item["comparisonFingerprint"] = comparable_fingerprint(score, manifest)
            scores[prefix], manifests[prefix] = score, manifest
        if metrics:
            item["engineMetrics"] = {"scope": metrics.get("scope", "unknown"), "limitations": [metrics.get(k) for k in ("promptTokenCaveat", "finishReasonCaveat") if metrics.get(k)],
                                     "statistics": {k: metric(metrics[k], sources, prefix + ".engine", "$." + k) for k in ("traceCallCount", "generatedTokens", "maxGeneratedTokens", "outputCapReachedCalls", "finishReason") if k in metrics},
                                     "recordedSourceIdentities": {k: metrics[k] for k in ("logPath", "logSha256", "traceSha256") if k in metrics},
                                     "recordedSourceIdentityEvidence": citation(sources, prefix + ".engine", "$.logPath, $.logSha256, $.traceSha256")}
        else:
            item["engineMetrics"] = {"state": "unknown", "limitation": "No recorded engine-metrics.json; output truncation, reasoning token split and API finish_reason cannot be established."}
        runs.append(item)
    baseline = runs[0]
    for item in runs:
        item["comparableToBaseline"] = bool(item["completeScore"] and baseline["completeScore"] and item.get("comparisonFingerprint") == baseline.get("comparisonFingerprint"))
        item["rankingEligible"] = bool(item["comparableToBaseline"] and item["safety"]["eligible"])
        if item["comparableToBaseline"] and item is not baseline:
            item["changesFromBaseline"] = field_changes(scores[baseline["id"]], scores[item["id"]], sources, baseline["id"] + ".score", item["id"] + ".score")
    eligible = [r for r in runs if r["rankingEligible"]]
    def order(row):
        stat = row["statistics"]
        elapsed = stat["elapsedSeconds"]["value"]
        return (-stat["correctGroundedValueInputsRecognized"]["value"],
                -stat["pass"]["value"], stat["fail"]["value"], stat["review"]["value"],
                elapsed if numeric(elapsed) else math.inf)
    eligible.sort(key=order)
    for rank, row in enumerate(eligible, 1):
        row["rank"] = rank
    return {"schemaVersion": 1, "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
            "scope": "Read-only comparison of saved real Drafting candidate exams; no grading rule changes, API calls, adoption or contract generation.",
            "baselineId": baseline["id"], "bestEligibleRunId": eligible[0]["id"] if eligible else None,
            "rankingEvidence": [citation(sources, r["id"] + ".score", "$.summary, $.ledger, $.boundaryChecks, $.globalFailures") for r in eligible],
            "rankingOrder": ["complete score matching baseline fixture/stage/input/template/expected ledger identities", "four withheld empty; no unexpected null fill; no wrong/conflicting/ungrounded candidates; all recorded boundaries pass", "correct grounded required-value candidate coverage descending", "strict PASS descending, FAIL ascending, REVIEW ascending", "elapsed seconds ascending; unknown last"],
            "interpretation": {
                "candidateCoverage": "A correct grounded candidate exists or correct fragments jointly cover the business answer; this does not imply an automatically selected display value or contract semantic acceptance.",
                "literalSemanticSupport": "Literal/reference support diagnostic is separate from candidate coverage and does not prove full contractual entailment.",
                "prefill": "displayValuePopulated counts displayed non-null suggestions including false, zero and []; it does not establish their correctness. Exact-text matches are reported separately and do not replace business equivalence grading.",
                "adoption": "confirmedInputs and manuallyEditedInputs report application adoption flags; this exam expects no manual adoption and no suggestions entering effectiveValues.",
                "review": "REVIEW requires review and is never counted as PASS or full acceptance.",
                "contractAcceptance": "Even all 79 inputs passing does not validate generated NTT/SCT/SCC contracts: detailed Bill 9/10/11 distribution, trade safety 12.2/12.3/12.4 links, preserved repayment terms and duplicate-free inspection wording remain separate acceptance work.",
                "configuration": "Only recorded model.Arguments or Model.Arguments supply settings. Missing flags are unknown; later original-config readback is not a pre-run capture.",
                "limits": "This compares supplied score snapshots. It does not regrade answers or certify a completed manifest without a complete matching score. Best means best observed eligible run, not a proven model ceiling.",
            }, "runs": runs, "sources": sources}


def md_escape(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def markdown(report):
    rows = ["# Drafting 变量识别运行对照", "", "本报告只读取保存结果。比较脚本退出 0 表示报告已生成；考题是否通过以各行严格结论为准。", "",
            "完整评分先通过安全约束，再按确定业务值候选覆盖、严格 PASS / FAIL / REVIEW、耗时排序。PENDING / AWAITING_SCORE 不参加最佳成绩排序；REVIEW 保留为待复核。", "",
            "| 运行 | 状态 / 严格结论 | 安全约束 | 正确有出处候选 | 字面支持诊断 | 四项缺失通过 | PASS / REVIEW / FAIL | 秒 | 排名 |",
            "|---|---|---|---|---|---|---|---|---|"]
    def val(row, name):
        return row.get("statistics", {}).get(name, {}).get("value", "unknown")
    for run in report["runs"]:
        safety = "通过" if run.get("safety", {}).get("eligible") else "不通过" if "safety" in run else "pending"
        result = f"{val(run, 'pass')} / {val(run, 'review')} / {val(run, 'fail')}" if run["completeScore"] else "pending"
        rows.append("| " + " | ".join(map(md_escape, [run["label"], f"{run['state']} / {run['verdict']}", safety,
                     f"{val(run, 'correctGroundedValueInputsRecognized')} / {val(run, 'requiredValueInputs')}",
                     val(run, "valueInputsWithLiteralSemanticSupport"), f"{val(run, 'withheldPass')} / 4", result,
                     val(run, "elapsedSeconds"), run["rank"] or "—"])) + " |")
    best = next((r for r in report["runs"] if r["id"] == report["bestEligibleRunId"]), None)
    rows += ["", f"当前可比较且通过安全约束的最佳完整运行：`{best['directory']}`。严格结论 `{best['verdict']}`。" if best else "当前没有可排序的完整、安全且与基线相同考题的运行。", "",
             "候选覆盖、引用字面支持、显示预填和人工采用是不同指标。显示非空建议可能是 false、0 或 []；非空数不是正确数。79 项全 PASS 仍须单独验收合同生成、Bill 分发和安全子项关系。", "",
             "## 实际记录的运行参数", "", "| 运行 | ctx | output | thinking | effort | 思考预算 | seed | 参数证据范围 |", "|---|---:|---:|---|---|---:|---|---|"]
    for run in report["runs"]:
        setting = run["runtime"]["settings"]
        values = [setting[k]["value"] for k in ("contextTokens", "outputTokens", "thinking", "reasoningEffort", "reasoningBudgetTokens", "seed")]
        rows.append("| " + " | ".join(map(md_escape, [run["label"], *values, run["runtime"]["captureScope"]])) + " |")
    rows += ["", "`post_run_linked_readback` 来自明确标注后采的 runtime-linked-after-baseline.json，保留其记载的原配置来源与 hash。`post_run_original_readback` 是从后续 campaign 的 runtime-original.json 取得的原配置读回。两者都未预先采集，不能独立证明 baseline 当时实际配置。`unknown` 不从目录名或引擎默认值推断。", ""]
    for run in report["runs"]:
        rows += [f"## {run['label']}", "", f"目录：`{run['directory']}`。状态 `{run['state']}`，与基线可比较 `{run['comparableToBaseline']}`。", ""]
        if not run["completeScore"]:
            rows += ["评分待完成或不可用：" + ", ".join(run["completionIssues"]), ""]
            continue
        rows += ["| 统计 | 值 | 来源 SHA-256 / JSON 路径 |", "|---|---:|---|"]
        for name, stat in run["statistics"].items():
            evidence = stat["evidence"]
            rows.append(f"| {METRICS.get(name, name)} | {md_escape(stat['value'])} | `{evidence['sha256']}` `{evidence['jsonPath']}` |")
        rows += ["", "安全约束：" + ", ".join(f"{k}={v}" for k, v in run["safety"]["checks"].items()), ""]
        if run["safety"]["unexpectedFilledNullKeys"] or run["safety"]["unsafeCandidateKeys"]:
            rows += ["误填留空项：" + ", ".join(run["safety"]["unexpectedFilledNullKeys"]), "错误/冲突/来源核验失败字段：" + ", ".join(run["safety"]["unsafeCandidateKeys"]), ""]
        changes = run.get("changesFromBaseline")
        if changes:
            rows += ["改善字段：" + (", ".join(changes["improvedKeys"]) or "无"), "", "退步字段：" + (", ".join(changes["regressedKeys"]) or "无"), "",
                     "| 字段 | 变化类别 | 原严格结果 → 当前 | 原正确检出/留空 → 当前 | 原因 |", "|---|---|---|---|---|"]
            for field in changes["fields"]:
                a, b = field["before"], field["after"]
                rows.append("| " + " | ".join(map(md_escape, [field["key"], field["kind"], f"{a.get('outcome')} → {b.get('outcome')}",
                            f"{a.get('recognizedCorrectGroundedAnswer')} → {b.get('recognizedCorrectGroundedAnswer')}", "; ".join(field.get("reasons", []))])) + " |")
            rows += ["", "mixed 同时列为改善和退步；字段变化的两个评分文件 SHA-256 与 ledger key 保存在 JSON evidence 中。", ""]
        engine = run["engineMetrics"]
        if "statistics" in engine:
            rows += ["模型日志统计范围：" + str(engine.get("scope")), ""]
            for name, stat in engine["statistics"].items():
                rows.append(f"- {name}: `{stat['value']}`，来源 `{stat['evidence']['sha256']}` / `{stat['evidence']['jsonPath']}`。")
            rows += ["", *engine["limitations"], ""]
        else:
            rows += [engine["limitation"], ""]
    rows += ["## 来源文件快照", "", "每个统计绑定读取时的完整文件 SHA-256。运行中 manifest 可能继续变化，重新比较会生成新快照。", "", "| 来源 ID | 路径 | SHA-256 |", "|---|---|---|"]
    for source_id, source in report["sources"].items():
        rows.append(f"| {source_id} | `{md_escape(source['path'])}` | `{source.get('sha256', 'unknown')}` |")
    return "\n".join(rows) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", nargs="+", type=Path, required=True, help="saved run directories; first is baseline")
    parser.add_argument("--out-dir", type=Path, required=True, help="JSON and Chinese Markdown report destination")
    args = parser.parse_args()
    paths = [p.resolve() for p in args.runs]
    if len(paths) != len(set(paths)):
        parser.error("--runs contains duplicate directories")
    for path in paths:
        if not path.is_dir():
            parser.error(f"run directory does not exist: {path}")
    try:
        report = compare(paths)
        output = args.out_dir.resolve()
        output.mkdir(parents=True, exist_ok=True)
        json_path, md_path = output / "drafting-comparison.json", output / "drafting-comparison.md"
        # Output filenames cannot collide with any of the input evidence files.
        input_paths = {Path(source["path"]).resolve() for source in report["sources"].values()}
        if json_path in input_paths or md_path in input_paths:
            raise ValueError("output would overwrite an input evidence file")
        json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        md_path.write_text(markdown(report), encoding="utf-8")
        print(json.dumps({"bestEligibleRunId": report["bestEligibleRunId"], "runs": [{"id": r["id"], "state": r["state"], "verdict": r["verdict"], "rank": r["rank"]} for r in report["runs"]],
                          "jsonReport": str(json_path), "markdownReport": str(md_path)}, ensure_ascii=False, indent=2))
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print(f"Comparison input/report error: {exc}", file=sys.stderr)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
