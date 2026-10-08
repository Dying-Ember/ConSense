#!/usr/bin/env python3
"""Read saved exam evidence only; no network, generation, service or scorer changes."""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import statistics


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def utc(value):
    # Python accepts the saved Java nanosecond dates, reducing precision to us.
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def sha(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    root = args.root.resolve()
    base = root / ".local/state/drafting-exam"
    sources = {}

    def source(identity, path, locator):
        sources[identity] = {"path": str(path), "sha256": sha(path), "locator": locator}
        return identity

    retoken_path = base / "final-content-retokenization.json"
    retoken = load(retoken_path)
    source("retokenization", retoken_path, "$.runs[*].calls; $.caveat")
    configs = [("baseline", base / "baseline-32k-off", root / ".local/logs/bonsai-20261008-093417-514.stderr.log"),
               ("medium4k", base / "campaign-medium4k-20261008/medium4k", root / ".local/logs/bonsai-20261008-101216-453.stderr.log"),
               ("off16k", base / "campaign-off16k-20261008/off16k", root / ".local/logs/bonsai-20261008-112932-704.stderr.log")]
    results = []
    for name, folder, log_path in configs:
        manifest = load(folder / "manifest.json")
        trace = load(folder / "trace.json")["data"]
        metrics = load(folder / "engine-metrics.json")
        source(name + ".manifest", folder / "manifest.json", "$.elapsedSeconds; $.extractionStartedAt; $.finishedAt; $.inputFiles; $.templateFiles")
        source(name + ".trace", folder / "trace.json", "$.data.startedAt; $.data.finishedAt; $.data.parts[*].attempts; $.data.relations; $.data.rawResponses")
        source(name + ".metrics", folder / "engine-metrics.json", "$.scope; $.calls[*]")
        source(name + ".log", log_path, "Per-call prompt/eval/total lines specified in runs[*].calls[*].logLines")
        stdout_path = log_path.with_name(log_path.name.replace(".stderr.log", ".stdout.log"))
        source(name + ".stdout", stdout_path, f"Entire file; saved length {stdout_path.stat().st_size} bytes")
        for suffix in ("gpu-before", "gpu-after"):
            source(name + "." + suffix, folder / (suffix + ".json"), "$.capturedAt; $.csv")
        score_path = folder / "scoring/drafting-score.json"
        source(name + ".score", score_path, "$.summary; $.globalFailures; $.boundaryChecks")
        runtime_path = folder / ("runtime-linked-after-baseline.json" if name == "baseline" else "runtime-before.json")
        source(name + ".runtime", runtime_path, "$.model.Arguments; $.captureTiming or $.capturedAt; $.modelReceipt")
        flat = [(part, attempt) for part in trace["parts"] for attempt in part["attempts"]]
        # Recalls execute after the initial pass; parts group them with their source.
        schedule = [a for a in flat if a[1]["kind"] != "recall"] + [a for a in flat if a[1]["kind"] == "recall"]
        for relation in trace.get("relations", []):
            if relation.get("rawResponse") is not None:
                schedule.append(({"fileName": "[joint relation review]", "partIndex": None},
                                 {"kind": "joint_review", "attemptIndex": 1, "relationKey": relation["key"],
                                  "rawResponse": relation["rawResponse"]}))
        assert [a[1]["rawResponse"] for a in schedule] == trace["rawResponses"], "Cannot align trace execution order."
        assert len(schedule) == len(metrics["calls"])
        records = collections.defaultdict(dict)
        timing_re = re.compile(r"task\s+(\d+)\s*\|\s*(prompt eval time|eval time|total time)\s*=\s*([\d.]+) ms /\s*(\d+) tokens")
        log_lines = log_path.read_text(encoding="utf-8-sig").splitlines()
        for line_no, line in enumerate(log_lines, 1):
            match = timing_re.search(line)
            if match:
                task, kind, ms, tokens = match.groups()
                records[int(task)][kind] = {"milliseconds": float(ms), "tokens": int(tokens), "line": line_no}
        reencoded = next((item for item in retoken["runs"] if item["name"] == name), None)
        if reencoded:
            assert reencoded["traceSha256"] == sha(folder / "trace.json")
        calls = []
        used_ids = set()
        ordered_log_tasks = sorted((task for task, values in records.items() if "eval time" in values),
                                   key=lambda task: records[task]["eval time"]["line"])
        if not all("taskId" in item for item in metrics["calls"]):
            assert len(ordered_log_tasks) == len(metrics["calls"]), "Outside-run generation records require explicit task selection."
        for index, ((part, attempt), recorded) in enumerate(zip(schedule, metrics["calls"])):
            expected_task = recorded.get("taskId", ordered_log_tasks[index])
            candidates = [task for task, values in records.items() if task not in used_ids and "eval time" in values
                          and values["eval time"]["tokens"] == recorded["generatedTokens"]
                          and abs(values["eval time"]["milliseconds"] - recorded["generationMilliseconds"]) < 0.00001
                          and task == expected_task]
            assert len(candidates) == 1, f"Ambiguous/outside-scope engine call {name}/{index}: {candidates}"
            task = candidates[0]
            used_ids.add(task)
            row = records[task]
            assert "prompt eval time" in row and "total time" in row
            if "promptEvaluationMilliseconds" in recorded:
                assert abs(row["prompt eval time"]["milliseconds"] - recorded["promptEvaluationMilliseconds"]) < 0.00001
            final_tokens = reencoded["calls"][index]["reencodedFinalTokens"] if reencoded else None
            calls.append({"callIndex": index, "taskId": task, "kind": attempt["kind"],
                          "fileName": part["fileName"], "partIndex": part["partIndex"], "attemptIndex": attempt["attemptIndex"],
                          "relationKey": attempt.get("relationKey"),
                          "generationMilliseconds": recorded["generationMilliseconds"], "generatedTokens": recorded["generatedTokens"],
                          "promptEvaluationMilliseconds": row["prompt eval time"]["milliseconds"],
                          "evaluatedPromptTokens": row["prompt eval time"]["tokens"],
                          "engineTotalMilliseconds": row["total time"]["milliseconds"],
                          "reencodedFinalTokens": final_tokens,
                          "generatedMinusReencodedFinalTokens": recorded["generatedTokens"] - final_tokens if final_tokens is not None else None,
                          "logLines": {kind: item["line"] for kind, item in row.items()},
                          "evidence": [name + ".metrics", name + ".trace", name + ".log", "retokenization"]})
        generation_seconds = sum(c["generationMilliseconds"] for c in calls) / 1000
        prompt_seconds = sum(c["promptEvaluationMilliseconds"] for c in calls) / 1000
        tokens = sum(c["generatedTokens"] for c in calls)
        trace_wall = (utc(trace["finishedAt"]) - utc(trace["startedAt"])).total_seconds()
        wall = manifest["elapsedSeconds"]
        category = {}
        for kind in sorted({c["kind"] for c in calls}):
            subset = [c for c in calls if c["kind"] == kind]
            gen = sum(c["generationMilliseconds"] for c in subset) / 1000
            category[kind] = {"calls": len(subset), "generatedTokens": sum(c["generatedTokens"] for c in subset),
                              "generationSeconds": gen, "promptSeconds": sum(c["promptEvaluationMilliseconds"] for c in subset) / 1000,
                              "weightedDecodeTokensPerSecond": sum(c["generatedTokens"] for c in subset) / gen,
                              "reencodedFinalTokens": sum(c["reencodedFinalTokens"] for c in subset) if reencoded else None}
        # These builds omit core model/buffer diagnostics at the captured log level.
        startup = log_lines[:next(i for i, line in enumerate(log_lines) if "selected slot" in line)]
        diagnostics = [(i + 1, line) for i, line in enumerate(startup)
                       if re.search(r"offloaded|offloading|model buffer|KV buffer|compute buffer|CPU_Mapped|CUDA\d+", line, re.I)]
        results.append({"id": name, "directory": str(folder), "scope": manifest["scope"],
                        "originalEngineMetricsScope": metrics["scope"],
                        "alignmentScope": "Same-length controlled serial run; trace execution order verified against all rawResponses, each metric record uniquely matched to retired log. No HTTP request IDs or exact API reasoning/finish_reason usage retained.",
                        "elapsedSeconds": wall, "traceWallSeconds": trace_wall,
                        "callsCount": len(calls), "generatedTokens": tokens,
                        "generationSeconds": generation_seconds, "promptEvaluationSeconds": prompt_seconds,
                        "weightedDecodeTokensPerSecond": tokens / generation_seconds,
                        "wallAmortizedTokensPerSecond": tokens / wall,
                        "averageGeneratedTokensPerCall": tokens / len(calls),
                        "wallMinusDecodeAndPromptSeconds": wall - generation_seconds - prompt_seconds,
                        "traceMinusDecodeAndPromptSeconds": trace_wall - generation_seconds - prompt_seconds,
                        "generationWallPercent": generation_seconds / wall * 100,
                        "reencodedFinalTokens": reencoded["reencodedFinalTokens"] if reencoded else None,
                        "generatedMinusReencodedFinalTokens": tokens - reencoded["reencodedFinalTokens"] if reencoded else None,
                        "harnessVersion": trace["harnessVersion"], "category": category, "calls": calls,
                        "accuracySummary": load(score_path)["summary"],
                        "slowestDecodeCalls": [{"callIndex": c["callIndex"], "taskId": c["taskId"], "fileName": c["fileName"],
                                                "kind": c["kind"], "decodeTokensPerSecond": c["generatedTokens"] * 1000 / c["generationMilliseconds"],
                                                "logLines": c["logLines"]}
                                               for c in sorted(calls, key=lambda c: c["generatedTokens"] / c["generationMilliseconds"])[:3]],
                        "startupEvidence": {"logLines": [1, len(startup)], "actualOffloadedLayerCount": None,
                                            "actualCpuModelBufferMiB": None, "actualCudaModelBufferMiB": None,
                                            "actualGpuKvBufferMiB": None, "diagnosticLines": diagnostics,
                                            "limitation": "Captured startup logs omit layer placement and core buffer details. CLI requests cannot verify actual full GPU placement."},
                        "evidence": [name + ".manifest", name + ".trace", name + ".metrics", name + ".log", "retokenization"]})
    old, new, off = results
    for run in results[1:]:
        assert old["harnessVersion"] == run["harnessVersion"]
        for field in ("inputFiles", "templateFiles"):
            assert load(Path(old["directory"]) / "manifest.json")[field] == load(Path(run["directory"]) / "manifest.json")[field]
    token_work = (new["generatedTokens"] - old["generatedTokens"]) / old["weightedDecodeTokensPerSecond"]
    speed_penalty = new["generationSeconds"] - new["generatedTokens"] / old["weightedDecodeTokensPerSecond"]
    decomposition = {"elapsedRatio": new["elapsedSeconds"] / old["elapsedSeconds"],
                     "callCountRatio": new["callsCount"] / old["callsCount"],
                     "averageTokensPerCallRatio": new["averageGeneratedTokensPerCall"] / old["averageGeneratedTokensPerCall"],
                     "totalGeneratedTokensRatio": new["generatedTokens"] / old["generatedTokens"],
                     "decodeThroughputChangePercent": (new["weightedDecodeTokensPerSecond"] / old["weightedDecodeTokensPerSecond"] - 1) * 100,
                     "reencodedFinalTokenRatio": new["reencodedFinalTokens"] / old["reencodedFinalTokens"],
                     "elapsedIncreaseSeconds": new["elapsedSeconds"] - old["elapsedSeconds"],
                     "extraTokenWorkAtBaselineSpeedSeconds": token_work,
                     "lowerDecodeThroughputPenaltyAtNewTokenLoadSeconds": speed_penalty,
                     "promptEvaluationChangeSeconds": new["promptEvaluationSeconds"] - old["promptEvaluationSeconds"],
                     "unattributedWallChangeSeconds": new["wallMinusDecodeAndPromptSeconds"] - old["wallMinusDecodeAndPromptSeconds"],
                     "counterfactualCaveat": "Sequential accounting at observed baseline weighted decode rate; not a causal estimate of context-only or thinking-only hardware effects.",
                     "reasoningCaveat": retoken["caveat"]}
    campaign = base / "campaign-medium4k-20261008"
    samples = []
    start, finish = utc(load(Path(new["directory"]) / "trace.json")["data"]["startedAt"]), utc(load(Path(new["directory"]) / "trace.json")["data"]["finishedAt"])
    for filename in ("medium4k-gpu-samples.jsonl", "medium4k-observer-resumed.jsonl"):
        path = campaign / filename
        source(filename, path, "JSONL row index; active samples filtered to trace startedAt <= observedAtUtc <= finishedAt")
        for row_index, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            row = json.loads(line)
            at = utc(row.get("observedAtUtc", row.get("ObservedAtUtc")))
            if start <= at <= finish:
                samples.append({"at": at, "used": row.get("usedMiB", row.get("UsedMiB")),
                                "free": row.get("freeMiB", row.get("FreeMiB")), "util": row.get("utilizationPercent", row.get("UtilizationPercent")),
                                "source": filename, "row": row_index})
    samples.sort(key=lambda s: s["at"])
    peak = max(samples, key=lambda s: s["used"])
    minimum = min(samples, key=lambda s: s["free"])
    gaps = [(samples[i]["at"] - samples[i - 1]["at"]).total_seconds() for i in range(1, len(samples))]
    gpu = {"scope": "Whole-card intermittent samples, including unrelated GPU clients; no per-model allocation or continuous peak claim",
           "activeSamples": len(samples), "observedMaxUsedMiB": peak["used"], "observedMinFreeMiB": minimum["free"],
           "peakEvidence": {"sourceId": peak["source"], "jsonlRow": peak["row"], "observedAtUtc": peak["at"].isoformat()},
           "minimumFreeEvidence": {"sourceId": minimum["source"], "jsonlRow": minimum["row"], "observedAtUtc": minimum["at"].isoformat()},
           "utilizationMinPercent": min(s["util"] for s in samples), "utilizationMaxPercent": max(s["util"] for s in samples),
           "utilizationMedianPercent": statistics.median(s["util"] for s in samples),
           "largestInteriorSamplingGapSeconds": max(gaps), "initialUnobservedSeconds": (samples[0]["at"] - start).total_seconds(),
           "finalUnobservedSeconds": (finish - samples[-1]["at"]).total_seconds(),
           "limitation": "GPU utilization is not SM occupancy, memory bandwidth saturation, a hardware ceiling, or proof of full-layer CUDA execution. Terminal unload samples are excluded."}
    module_path = base / "current-off16k-gpu-module-readback.json"
    source("current.off16k.moduleReadback", module_path, "$.Scope; $.OwnershipVerified; $.Modules; $.ComputeAppCsv")
    current = load(module_path)
    long_path = root / ".local/state/capacity/run-55k-off8k-20261008"
    source("long55k.assessment", long_path / "assessment.json", "$.scope; $.verdict; $.usage; $.timings; $.finish_reason; $.reasoning_chars")
    source("long55k.requestSummary", long_path / "request-summary.json", "$.scope; $.generation_attempts; $.reported_model_context; $.generation_elapsed_seconds; $.timings; $.usage")
    long_assessment, long_summary = load(long_path / "assessment.json"), load(long_path / "request-summary.json")
    bounds = {"scope": "Conditional planning arithmetic for the same serial workload using measured throughput; not hardware benchmark ceilings",
              "decodeOnlySecondsPer1000Tokens": 1000 / new["weightedDecodeTokensPerSecond"],
              "decodeOnlySeconds4096ThinkingPlus1000Final": 5096 / new["weightedDecodeTokensPerSecond"],
              "decodeOnlyMinutesForMeasuredMediumWorkload": new["generationSeconds"] / 60,
              "requiredDecodeRateFor10MinuteSameWorkload": new["generatedTokens"] / (600 - new["elapsedSeconds"] + new["generationSeconds"]),
              "required10MinuteSpeedupFromMeasuredDecode": new["generatedTokens"] / (600 - new["elapsedSeconds"] + new["generationSeconds"]) / new["weightedDecodeTokensPerSecond"],
              "notEstablished": ["A4000 optimized-kernel ceiling", "thermal/power throttling", "SM or memory-bandwidth bottleneck", "same task on MiniMax wall time"]}
    output = {"schemaVersion": 1, "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
              "scope": "Read-only saved Drafting-exam performance comparison; runner, scorer, harness, model configuration and service processes untouched",
              "sources": sources, "runs": results, "decomposition": decomposition, "gpuObservation": gpu,
              "currentCudaReadback": current, "conditionalHardwarePlanning": bounds}
    output["same64kOutput16kThinkingComparison"] = {
        "off16kElapsedSeconds": off["elapsedSeconds"],
        "mediumToOffElapsedRatio": new["elapsedSeconds"] / off["elapsedSeconds"],
        "mediumToOffGeneratedTokenRatio": new["generatedTokens"] / off["generatedTokens"],
        "mediumVsOffDecodeThroughputChangePercent": (new["weightedDecodeTokensPerSecond"] / off["weightedDecodeTokensPerSecond"] - 1) * 100,
        "scope": "Both same recorded 64K F16/16K output/seed42 and harness; reasoning medium4096 vs off. Separate serial runs, not randomized repetitions; runtime/environment drift and model stochasticity remain possible.",
        "evidence": ["medium4k.runtime", "off16k.runtime", "medium4k.trace", "off16k.trace", "medium4k.metrics", "off16k.metrics"]}
    output["longPromptComparison"] = {
        "scope": long_summary["scope"], "verdict": long_assessment["verdict"],
        "generationAttempts": long_summary["generation_attempts"], "contextTokens": long_summary["reported_model_context"],
        "promptTokens": long_assessment["usage"]["prompt_tokens"], "generatedTokens": long_assessment["usage"]["completion_tokens"],
        "cachedTokens": long_assessment["usage"]["prompt_tokens_details"]["cached_tokens"],
        "promptEvaluationSeconds": long_assessment["timings"]["prompt_ms"] / 1000,
        "promptTokensPerSecond": long_assessment["timings"]["prompt_per_second"],
        "generationSeconds": long_assessment["timings"]["predicted_ms"] / 1000,
        "decodeTokensPerSecond": long_assessment["timings"]["predicted_per_second"],
        "httpElapsedSeconds": long_summary["generation_elapsed_seconds"],
        "reasoningCharacters": long_assessment["reasoning_chars"], "finishReason": long_assessment["finish_reason"],
        "limitation": "A different synthetic workload from short-part Drafting exams; decode rates do not isolate context-size as a causal effect or establish real-contract quality.",
        "evidence": ["long55k.assessment", "long55k.requestSummary"]}
    output_path = base / "performance-comparison.json"
    output_path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report = [
        "# Drafting 本机推理耗时评估",
        "",
        "2026-10-08。针对相同 15 份缺失证据考题、相同 NTT/SCT/SCC 模板和相同 harness 的三轮已完成考试做只读分解。没有改 runner、评分器、harness、模型配置或服务进程。off16k 运行时的 CUDA 模块读回仅用于补充观察，各轮生成统计按对应退休日志隔离。",
        "",
        "**本机的当前速度确实限制绝对等待时间；本次从约 11 分钟变为约 65 分钟，主要原因是生成工作量增加，而不是调用次数增加或每 token 速度突然下降数倍。** medium4k 总生成量增加到基线的约 6.26 倍，调用少了 2 次，加权解码吞吐只降低约 5.34%。最终 JSON 的独立重编码只增加约 31%，额外生成主要属于思考及生成边界差异。重编码差值不是 API 的精确 reasoning usage。",
        "",
        "| 指标 | 32K / off / 2K 基线 | 64K F16 / medium4K / 16K | 64K F16 / off / 16K | 证据与计算 |",
        "| --- | ---: | ---: | ---: | --- |",
    ]
    items = [
        ("runner elapsed（秒）", "elapsedSeconds", ".manifest $.elapsedSeconds"),
        ("harness trace wall（秒）", "traceWallSeconds", ".trace $.data.finishedAt - startedAt"),
        ("实际生成调用次数", "callsCount", ".trace rawResponses / .metrics calls；逐项对齐"),
        ("engine 总生成 tokens", "generatedTokens", ".metrics $.calls[*].generatedTokens 的和"),
        ("纯解码总时间（秒）", "generationSeconds", ".metrics $.calls[*].generationMilliseconds / 1000 的和"),
        ("prompt eval 总时间（秒）", "promptEvaluationSeconds", ".log 按已匹配 task ID 取 prompt eval time；baseline 不以缺字段当 0"),
        ("加权解码 tokens/s", "weightedDecodeTokensPerSecond", "总 generatedTokens / 纯解码秒数"),
        ("wall 摊销 tokens/s", "wallAmortizedTokensPerSecond", "总 generatedTokens / runner elapsed；不是 kernel 解码速度"),
        ("平均每调用 generated tokens", "averageGeneratedTokensPerCall", "总 generatedTokens / callsCount"),
        ("runner wall 减解码与 prefill（秒）", "wallMinusDecodeAndPromptSeconds", "elapsed - generation - prompt"),
        ("最终 content 独立重编码 tokens", "reencodedFinalTokens", "retokenization $.runs[*].calls；add_special=false"),
        ("generated 减最终重编码 tokens", "generatedMinusReencodedFinalTokens", "上两项相减；含思考和 EOS/template/parser 边界误差"),
    ]
    for title, key, evidence in items:
        left, right, third = old[key], new[key], off[key]
        fmt = lambda value: "未重编码" if value is None else str(value) if isinstance(value, int) else f"{value:.3f}"
        report.append(f"| {title} | {fmt(left)} | {fmt(right)} | {fmt(third)} | baseline/medium4k/off16k{evidence} |")
    report += ["", "加权吞吐采用 `sum(tokens) / sum(seconds)`，没有平均各次 tokens/s；基线 prompt eval 已按 engine-metrics 的 41 个 task ID 回查旧日志，前后烟测不计入。medium/off16k 的完整 39/40 个日志生成记录按顺序与 metrics 匹配并逐条验证时间和 token 数，未混入其他引擎前后烟测。trace 的 `rawResponses` 按实际执行顺序保存，而 `parts[*].attempts` 按来源分组；分析已验证“先全部非 recall，再全部 recall，最后关系审核”的序列与 rawResponses 逐项完全一致，再与 engine 记录对齐。每次调用的 task ID、类型、文档或 relation key、tokens、时间和日志行号保存在 JSON。",
               "", "`elapsedSeconds` 是 runner 的 monotonic 总时间：旧基线使用已准备项目，medium 从项目准备开始；其少量准备及读取成本范围不同。共同的 trace startedAt 到 finishedAt 区间也分别列出。wall 剩余项只能称未归因的非 engine 解码/prefill 时间，可能包含 HTTP、Java 处理、校验、数据存取、runner 准备/readback、调度及记录舍入，不能全部归给 CPU 或网络。evaluatedPromptTokens 只含实际重算的 prompt，缓存前缀不在内；本报告没有把它当完整输入长度。",
               "", "| 调用类型 | 基线次数 | medium 次数 | off16k 次数 | 基线生成 tokens / 秒 | medium 生成 tokens / 秒 | off16k 生成 tokens / 秒 |",
               "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for kind in sorted(set().union(*(r["category"] for r in results))):
        empty = {"calls": 0, "generatedTokens": 0, "generationSeconds": 0.0}
        a, b, c = old["category"].get(kind, empty), new["category"].get(kind, empty), off["category"].get(kind, empty)
        report.append(f"| {kind} | {a['calls']} | {b['calls']} | {c['calls']} | {a['generatedTokens']} / {a['generationSeconds']:.3f} | {b['generatedTokens']} / {b['generationSeconds']:.3f} | {c['generatedTokens']} / {c['generationSeconds']:.3f} |")
    report += ["", "23 次 primary 对应 23 个来源 part；repair 为格式/候选修复，coverage 为遗漏覆盖检查，recall 是后续来源回查；joint_review 为后置联合证据关系审核。medium 对原基线新增耗时没有来自更多轮调用：primary 和 recall 数相同，repair 少 1 次，coverage 少 1 次。off16k 的 40 次调用包括 39 个 part attempt 和 1 个 advancePaymentAdopted 联合关系审核；该最后调用也已与 rawResponses 和 engine 记录准确配对。类型统计来源为各轮 trace 的 attempt.kind / relations；相应时间和 tokens 来自逐项配对后的日志/engine metrics。",
               "", "用基线加权速度作可复算的顺序记账，可以把增加的等待分为：",
               "", f"- 更多生成 tokens，保持基线速度：**{token_work:.3f} 秒（{token_work / 60:.2f} 分钟）**；计算 `(111753 - 17862) / baseline weighted tok/s`。",
               f"- 新 token 工作量上的较低加权吞吐：**{speed_penalty:.3f} 秒（{speed_penalty / 60:.2f} 分钟）**；计算 `medium generation - 111753 / baseline weighted tok/s`。",
               f"- prefill 差值：**{decomposition['promptEvaluationChangeSeconds']:.3f} 秒**。",
               f"- 其余 wall 差值：**{decomposition['unattributedWallChangeSeconds']:.3f} 秒**。",
               "", f"合计 **{decomposition['elapsedIncreaseSeconds']:.3f} 秒**，与两轮 elapsed 差值一致。这个记账说明工作量是主要因素；32K→64K、output、thinking 和 seed 同时变化，不能据此把吞吐差归因为上下文单一因素，也不能把记录差值直接当因果效应。",
               "", f"medium 的三次最慢调用为 task **45422 / 49966 / 52926**，位于文档 10、11，分别约 **18.16 / 22.79 / 19.09 tokens/s**。日志时间见 `medium4k.log` 行 687–689、736–738、825–827；大部分其他 medium 调用约 30–32 tokens/s。上述低速段落在人工采样结束到独立采样恢复的空档内，现有记录无法判定是共享 GPU 竞争、温度/功耗、运行器路径、上下文处理或其他暂态原因。没有把单个 GPUutil 百分比当根因。",
               "", "**GPU 运行位置的核对结果有明确证据缺口。** 三轮启动 stderr 仅含 server/common 层的加载与槽位日志，stdout 均为空；没有保存实际 offloaded 层数、CPU model buffer、CUDA model buffer、GPU KV/compute buffer 的诊断。`--gpu-layers 99` 是请求值，不能证明实际所有层和全部算子都在 GPU，也不能据此排除局部 CPU fallback。分析 JSON 的 `startupEvidence` 将这些未知值保留为 null。",
               "", "off16k 运行时（UTC 03:33:27）的 PID 110924 已按 receipt 的 executable、marker、creation UTC 验证归属，加载了 ggml-cuda.dll、cudart64_12.dll、nvcuda.dll，且 nvidia-smi 的 compute-app 列表中存在该 PID；这证明该考试运行进程已使用 CUDA GPU。ggml-cpu-alderlake.dll 同时加载是正常可用后端，单凭加载不能推断 CPU 回退。Windows WDDM 将每进程 used_gpu_memory 返回 [N/A]，没有模型单独显存数字。这一运行时快照仍不能补成各层/缓冲位置验收，也不能回推 baseline/medium 的逐层位置。证据为 `current.off16k.moduleReadback`。后续若要定性硬件瓶颈，需要在受控启动中保存核心层/缓冲日志，再配对 profiler、功耗/温度/时钟、CPU/GPU 时间；本次未干预服务。",
               "", f"medium 有 **{gpu['activeSamples']} 条**位于实际 trace 区间的整卡采样，观测最高 used **{gpu['observedMaxUsedMiB']} MiB**，最小 free **{gpu['observedMinFreeMiB']} MiB**；两项均来源于 resumed JSONL 第 **{peak['row']} 行**，UTC `{peak['at'].isoformat()}`。采样 GPUutil 范围 **{gpu['utilizationMinPercent']}–{gpu['utilizationMaxPercent']}%**，中位数 **{gpu['utilizationMedianPercent']:.0f}%**。初始约 **{gpu['initialUnobservedSeconds']:.1f} 秒**没有观察，最大中间空档 **{gpu['largestInteriorSamplingGapSeconds']:.1f} 秒**。这些数字包含桌面和其他 GPU 客户端，属于间歇观测，不是模型单独显存、连续峰值、SM occupancy、显存带宽饱和率或硬件上限。末尾模型卸载后的样本已排除。",
               "", f"按当前运行器和这类串行负载的实测加权速度，**每额外 1,000 tokens 约需 {bounds['decodeOnlySecondsPer1000Tokens']:.1f} 秒纯生成**；一次 **4,096 思考 + 1,000 最终输出**约 **{bounds['decodeOnlySeconds4096ThinkingPlus1000Final']:.1f} 秒**，还要加 prefill 和应用成本。111,753 tokens 本身已经需要约 **{bounds['decodeOnlyMinutesForMeasuredMediumWorkload']:.2f} 分钟**纯生成。假设仍生成这些 tokens、保留当前其他成本，若要求 10 分钟结束，则解码约需 **{bounds['requiredDecodeRateFor10MinuteSameWorkload']:.1f} tokens/s（当前 {bounds['required10MinuteSpeedupFromMeasuredDecode']:.2f} 倍）**。这是条件规划算术，不是宣称 A4000 已达到不可优化的理论上限。",
               "", f"新增 **64K F16 / 16K output / thinking off** 对照已完成：**{off['elapsedSeconds']:.3f} 秒、{off['callsCount']} 次调用、{off['generatedTokens']} generated tokens、{off['weightedDecodeTokensPerSecond']:.3f} tokens/s 纯解码**。保持相同 64K/16K/seed42，medium 比 off16k 生成量为 **{new['generatedTokens'] / off['generatedTokens']:.3f} 倍**、wall 为 **{new['elapsedSeconds'] / off['elapsedSeconds']:.3f} 倍**；这比原始 32K 基线更直接支持思考工作量为主因。off16k 没有独立重编码，表格保留未知以避免在后续长 prompt 试验时额外调用 API。两轮不是随机化重复实验，环境变化和生成差异仍可能影响局部吞吐。",
               "", f"准确率对应的代价也要分开看：基线正确且有来源的 required-value candidate 为 **{old['accuracySummary']['correctGroundedValueInputsRecognized']}/{old['accuracySummary']['requiredValueInputs']}**，medium 为 **{new['accuracySummary']['correctGroundedValueInputsRecognized']}/{new['accuracySummary']['requiredValueInputs']}**，off16k 为 **{off['accuracySummary']['correctGroundedValueInputsRecognized']}/{off['accuracySummary']['requiredValueInputs']}**；三轮 strict PASS 均为 **{new['accuracySummary']['pass']}**。medium 有 **{new['accuracySummary']['review']} REVIEW / {new['accuracySummary']['fail']} FAIL**；off16k 有 **{off['accuracySummary']['review']} REVIEW / {off['accuracySummary']['fail']} FAIL**，且历史/其他来源冲突候选为 **{off['accuracySummary']['historicalOrOtherSourceConflictCandidates']}**。candidate coverage 不是自动采用或完整合同验收；扩大上下文/输出但关闭 thinking 没有在这一次考试提高正确覆盖，因此不能把“更快”当业务通过。来源为三轮 `.score $.summary`。用户提供的 MiniMax M3 能做对是有用基线信息，本次没有 MiniMax 的同题 wall/token 记录，不能计算它相对本机的速度倍数。",
               "", f"另有单次真实长 prompt 容量对照（`long55k.assessment` / `long55k.requestSummary`）：服务报告 **{long_summary['reported_model_context']} context**，输入 **{long_assessment['usage']['prompt_tokens']} tokens**，缓存 **{long_assessment['usage']['prompt_tokens_details']['cached_tokens']}**，prefill **{long_assessment['timings']['prompt_ms'] / 1000:.3f} 秒 / {long_assessment['timings']['prompt_per_second']:.3f} tokens/s**；生成 **{long_assessment['usage']['completion_tokens']} tokens**，decode **{long_assessment['timings']['predicted_ms'] / 1000:.3f} 秒 / {long_assessment['timings']['predicted_per_second']:.3f} tokens/s**；HTTP 总时间 **{long_summary['generation_elapsed_seconds']:.3f} 秒**。`reasoning_chars={long_assessment['reasoning_chars']}`、`finish_reason={long_assessment['finish_reason']}`，合成事实及证据断言 **{long_assessment['verdict']}**，生成尝试 **{long_summary['generation_attempts']} 次**。短分段 off16k 考试的加权 **{off['weightedDecodeTokensPerSecond']:.3f} tokens/s** 与此 **{long_assessment['timings']['predicted_per_second']:.3f} tokens/s**来自不同负载，不能当作只改变 context 参数的孤立因果。55K 合成检索成功证明该输入可执行及这些断言通过，不构成真实合同准确率或 128K/256K 能力验收。",
               "", "复算命令：`python scripts/analyze-drafting-performance.py`。该脚本只读取已保存文件，无网络或模型生成；`final-content-retokenization.json` 是此前通过同模型 `/tokenize` 独立重编码最终文本的收据，不会在复算时再次调用模型。输出 JSON 包含每次调用、证据定位、来源 SHA256 和上述公式。",
               "", f"性能 JSON：`{output_path}`，SHA256 `{sha(output_path)}`。",
               "", "| Source ID | 文件 | SHA256 | JSON / 日志定位 |", "| --- | --- | --- | --- |"]
    for identity, item in sources.items():
        report.append(f"| {identity} | `{item['path']}` | `{item['sha256']}` | {item['locator']} |")
    (root / "docs/drafting-performance-assessment.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(json.dumps({"runs": [{k: r[k] for k in ["id", "callsCount", "generatedTokens", "generationSeconds", "promptEvaluationSeconds", "weightedDecodeTokensPerSecond", "elapsedSeconds", "wallMinusDecodeAndPromptSeconds", "category"]} for r in results],
                      "decomposition": decomposition, "gpuObservation": gpu, "conditionalHardwarePlanning": bounds}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
