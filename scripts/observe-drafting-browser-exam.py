"""Collect a prepared local exam started through the browser; makes GET requests only."""
import argparse
import importlib.util
import json
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("exam_runner", ROOT / "scripts/run-drafting-exam.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--run-dir", type=Path, required=True)
parser.add_argument("--expected-harness-version", required=True)
parser.add_argument("--resume-observation", action="store_true", help="Resume GET-only collection using the preserved initial engine offset and observation time.")
args = parser.parse_args()
out = args.run_dir.resolve()
manifest = runner.read(out / "manifest.json") if hasattr(runner, "read") else json.loads((out / "manifest.json").read_text(encoding="utf-8-sig"))
if args.resume_observation:
    assert manifest["status"] in {"awaiting_browser_extraction", "observer_failed"}, "Require the original observation manifest."
else:
    assert manifest["status"] == "prepared", "Require a fresh prepared project."
prefix = manifest["baseUrl"] + "/api/drafting/" + manifest["projectId"]
variables = runner.require(runner.request(prefix + "/variables"), "clean project")
assert all(not row.get("confirmed") and not row.get("manuallyEdited") for row in variables), "Never observe an adopted or manually edited project."
if args.resume_observation:
    runtime = json.loads((out / "runtime-before.json").read_text(encoding="utf-8-sig"))
    assert manifest.get("extractionTrigger") == "in-app-browser" and manifest.get("observationStartedAt"), "Require the prior browser-triggered observation."
else:
    assert all(not row.get("value") and not row.get("candidates") for row in variables), "Require a clean initial project."
    runtime = runner.begin_runtime_evidence(out)
    runner.save(out / "gpu-before.json", runner.gpu_snapshot())
    manifest.update(observationStartedAt=runner.utc())
manifest.update(status="awaiting_browser_extraction", extractionTrigger="in-app-browser", expectedHarnessVersion=args.expected_harness_version)
runner.save(out / "manifest.json", manifest)
started = time.monotonic()
print("Observer ready. Awaiting browser extraction; this process does not POST or call a model.", flush=True)
trace = None
try:
    while time.monotonic() - started < 14400:
        response = runner.request(prefix + "/variables/extract-trace")
        current = runner.require(response, "observed trace")
        if current and current.get("runId"):
            trace = current
            if current.get("status") in {"completed", "failed"}:
                assert current["startedAt"] >= manifest["observationStartedAt"], "Reject a prior run."
                assert args.expected_harness_version == current["harnessVersion"], "Unexpected deployed harness version."
                runner.save(out / "trace.json", response["body"])
                break
        time.sleep(30)
        print(f"Observing browser exam ({time.monotonic() - started:.0f}s); awaiting persisted completed trace.", flush=True)
    else:
        raise TimeoutError("Browser exam did not finish within observation timeout.")
    manifest.update(status="completed" if trace["status"] == "completed" else "extraction_failed",
                    runId=trace["runId"], extractionStartedAt=trace["startedAt"], extractionFinishedAt=trace["finishedAt"],
                    elapsedSeconds=(datetime.fromisoformat(trace["finishedAt"].replace("Z", "+00:00")) - datetime.fromisoformat(trace["startedAt"].replace("Z", "+00:00"))).total_seconds())
    for name, route in [("variables", "/variables"), ("trace", "/variables/extract-trace"),
                        ("plan", "/plan"), ("progress", "/progress")]:
        result = runner.request(prefix + route)
        runner.require(result, "readback " + name)
        runner.save(out / (name + ".json"), result["body"])
    runner.save(out / "gpu-after.json", runner.gpu_snapshot())
    runner.finish_runtime_evidence(out, runtime)
    manifest.update(finishedAt=runner.utc(), observationElapsedSeconds=round(time.monotonic() - started, 3))
    runner.save(out / "manifest.json", manifest)
    print(f"Browser exam {manifest['status']}; run {trace['runId']}; extraction {manifest['elapsedSeconds']:.3f}s.", flush=True)
except Exception as exc:
    manifest.update(status="observer_failed", observationError=str(exc), finishedAt=runner.utc())
    runner.save(out / "manifest.json", manifest)
    raise
