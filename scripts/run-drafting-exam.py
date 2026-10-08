#!/usr/bin/env python3
"""Run the real local Drafting workflow without exposing the examiner's answers.

Only the three explicitly selected 2a templates and 01_upload_documents/*.docx
are uploaded. Each run creates a clean, labelled project; it never confirms or
generates a contract. All requests and readbacks are saved for offline grading.
"""
import argparse
import concurrent.futures
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE = ROOT / "Ref/simulated_2b_correspondence/03_missing_evidence_test"
DEFAULT_TEMPLATES = ROOT / "Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2a_Standard documents"
TEMPLATES = {
    "NTT": "01_Notes to Tenderers (NTT).docx",
    "SCT": "02_Special Conditions of Tender (SCT).docx",
    "SCC": "06_Special Conditions of Contract (SCC).docx",
}


def utc():
    return datetime.now(timezone.utc).isoformat()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def identity(path):
    return {"path": str(path.resolve()), "name": path.name,
            "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def multipart(files, field):
    boundary = "ConSenseExam" + uuid.uuid4().hex
    chunks = []
    for path in files:
        chunks.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; "
                       f"filename=\"{path.name}\"\r\nContent-Type: "
                       "application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n").encode("utf-8"))
        chunks.extend([path.read_bytes(), b"\r\n"])
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), "multipart/form-data; boundary=" + boundary


def request(url, method="GET", body=None, content_type="application/json", timeout=120):
    if isinstance(body, dict):
        body = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Content-Type": content_type, "X-ConSense-Llm-Profile": "local"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return {"httpStatus": response.status, "body": json.loads(response.read().decode("utf-8"))}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        return {"httpStatus": exc.code, "body": parsed}


def require(result, label):
    body = result["body"]
    if result["httpStatus"] != 200 or body.get("code") != 0:
        raise RuntimeError(f"{label} failed: HTTP {result['httpStatus']}; {body}")
    return body.get("data")


def gpu_snapshot():
    try:
        result = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total,memory.used,memory.free,utilization.gpu",
                                 "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=10)
        return {"capturedAt": utc(), "exitCode": result.returncode, "csv": result.stdout.strip()}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"capturedAt": utc(), "error": str(exc)}


def begin_runtime_evidence(out):
    """Read only: retain exact local settings and the beginning of the engine log interval."""
    config_path = ROOT / ".local/runtime.json"
    receipt_path = ROOT / ".local/state/bonsai.json"
    if not config_path.is_file() or not receipt_path.is_file():
        return None
    config = json.loads(config_path.read_text(encoding="utf-8-sig"))
    receipt = json.loads(receipt_path.read_text(encoding="utf-8-sig"))
    log = Path(receipt["Stderr"])
    info = {"capturedAt": utc(), "model": config.get("Model"), "modelReceipt": receipt,
            "engineLogOffset": log.stat().st_size if log.is_file() else None,
            "gitHead": subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True,
                                      text=True, timeout=10).stdout.strip()}
    jar = ROOT / "ai_consense_service/target/consense-service-1.0.0.jar"
    if jar.is_file():
        info["backendArtifact"] = identity(jar)
    save(out / "runtime-before.json", info)
    return info


def finish_runtime_evidence(out, info):
    if not info or info.get("engineLogOffset") is None:
        return
    with Path(info["modelReceipt"]["Stderr"]).open("rb") as stream:
        stream.seek(info["engineLogOffset"])
        text = stream.read().decode("utf-8", errors="replace")
    (out / "engine-extraction-interval.log").write_text(text, encoding="utf-8")
    calls = []
    current = {}
    for line in text.splitlines():
        match = re.search(r"prompt eval time =\s*([\d.]+) ms /\s*(\d+) tokens", line)
        if match:
            current = {"promptEvaluationMilliseconds": float(match[1]), "evaluatedPromptTokens": int(match[2])}
        match = re.search(r"(?<!prompt )eval time =\s*([\d.]+) ms /\s*(\d+) tokens", line)
        if match:
            current.update(generationMilliseconds=float(match[1]), generatedTokens=int(match[2]))
        if "slot      release:" in line and current:
            match = re.search(r"truncated = (\d+)", line)
            current["engineTruncatedFlag"] = int(match[1]) if match else None
            current["outputCapReached"] = current.get("generatedTokens", 0) >= info["model"].get("MaxOutputTokens", 0)
            calls.append(current)
            current = {}
    save(out / "engine-metrics.json", {"scope": "all engine calls in this wall-clock interval; may include concurrent clients",
         "promptTokenCaveat": "evaluatedPromptTokens excludes reused cached prefix tokens",
         "finishReason": None,
         "finishReasonCaveat": "Ordinary Drafting chat adapter retains content only; engine logs do not establish API finish_reason.",
         "calls": calls, "generatedTokens": sum(c.get("generatedTokens", 0) for c in calls),
         "outputCapReachedCalls": sum(bool(c.get("outputCapReached")) for c in calls)})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--templates", type=Path, default=DEFAULT_TEMPLATES)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--project-id", help="New project identifier only; an existing identifier is rejected.")
    parser.add_argument("--stage", type=int, choices=range(1, 16), default=15,
                        help="Upload only the first N documents into a fresh project (default all 15).")
    parser.add_argument("--timeout", type=int, default=14400, help="Synchronous extraction timeout in seconds.")
    parser.add_argument("--prepare-only", action="store_true", help="Prepare files and project without calling the model.")
    parser.add_argument("--extract-prepared", type=Path, help="Run extraction on the clean project from a prepare-only run.")
    args = parser.parse_args()
    parsed_url = urllib.parse.urlparse(args.base_url)
    if parsed_url.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed_url.scheme != "http":
        parser.error("This test runner only writes to a local loopback HTTP endpoint.")
    base = args.base_url.rstrip("/")
    manifest = None
    if args.extract_prepared:
        out = args.extract_prepared.resolve()
        manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("status") != "prepared" or manifest.get("baseUrl") != base:
            parser.error("Only an untouched prepare-only run at the same endpoint can be extracted.")
        project_id = manifest["projectId"]
    else:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        project_id = args.project_id or f"drafting-exam-{stamp}-{uuid.uuid4().hex[:8]}"
        out = (args.out_dir or ROOT / ".local/state/drafting-exam" / project_id).resolve()
        if out.exists():
            parser.error("Output directory already exists; use a new directory to preserve prior evidence.")
        files = sorted((args.fixture / "01_upload_documents").glob("*.docx"))
        if len(files) != 15:
            parser.error(f"Expected exactly 15 DOCX documents in 01_upload_documents, found {len(files)}.")
        files = files[:args.stage]
        templates = {key: args.templates / name for key, name in TEMPLATES.items()}
        for path in list(templates.values()) + files:
            if not path.is_file():
                parser.error(f"Missing input file: {path}")
        # Preflight the project list before creating any new records.
        projects = require(request(base + "/api/projects"), "project preflight")
        if any(project.get("id") == project_id for project in projects):
            parser.error("Refusing to reuse an existing project; previous values would contaminate this exam.")
        out.mkdir(parents=True)
        manifest = {"schemaVersion": 1, "projectId": project_id, "baseUrl": base,
                    "fixturePath": str(args.fixture.resolve()), "stage": f"SIM{args.stage:02d}",
                    "startedAt": utc(), "status": "preparing", "inputFiles": [identity(p) for p in files],
                    "templateFiles": [{"key": k, **identity(p)} for k, p in templates.items()],
                    "scope": "real Drafting API; no manual adoption or document generation", "errors": []}
        save(out / "manifest.json", manifest)
        save(out / "projects-before.json", projects)
    prefix = base + "/api/drafting/" + urllib.parse.quote(project_id, safe="")
    started = time.monotonic()
    runtime_info = None
    try:
        if not args.extract_prepared:
            creation = request(base + "/api/projects", "POST", {
                "id": project_id, "nameZhHans": "[自动验收] Drafting 缺失证据考题 " + project_id,
                "nameEn": "[AUTOMATED EXAM] Drafting missing evidence " + project_id})
            save(out / "project-create.json", creation["body"])
            require(creation, "create exam project")
            for key, path in templates.items():
                print(f"Uploading standard template {key}: {path.name}", flush=True)
                data, content_type = multipart([path], "file")
                result = request(prefix + f"/templates/{key}/replace", "POST", data, content_type, timeout=600)
                save(out / f"template-upload-{key}.json", result["body"])
                value = require(result, f"upload template {key}")
                if value.get("failed") or value.get("parsed") != 1:
                    raise RuntimeError(f"Template {key} did not parse: {value}")
            print(f"Uploading {len(files)} correspondence DOCX files from 01_upload_documents", flush=True)
            data, content_type = multipart(files, "files")
            result = request(prefix + "/inputs/upload", "POST", data, content_type, timeout=600)
            save(out / "input-upload.json", result["body"])
            value = require(result, "upload exam correspondence")
            if value.get("failed") or value.get("parsed") != len(files):
                raise RuntimeError(f"Not all correspondence parsed: {value}")
        for name, route in [("inputs", "/inputs"), ("templates", "/templates"),
                            ("catalog", "/catalog"), ("variables-before", "/variables")]:
            result = request(prefix + route)
            save(out / (name + ".json"), result["body"])
            values = require(result, name)
            if name == "inputs":
                wanted = {item["name"] for item in manifest["inputFiles"]}
                if {item.get("fileName") for item in values} != wanted or any(item.get("status") != "PARSED" for item in values):
                    raise RuntimeError("Uploaded input readback differs from the allowed DOCX files.")
            if name == "variables-before" and any(item.get("value") or item.get("confirmed") or
                    item.get("manuallyEdited") or item.get("candidates") for item in values):
                raise RuntimeError("Project has previous values/candidates/adoptions; refusing contaminated test.")
        if args.prepare_only:
            manifest.update(status="prepared", preparedAt=utc(), elapsedSeconds=round(time.monotonic() - started, 3))
            save(out / "manifest.json", manifest)
            print(f"Prepared clean test project. Receipts: {out}", flush=True)
            return 0
        save(out / "gpu-before.json", gpu_snapshot())
        runtime_info = begin_runtime_evidence(out)
        manifest.update(status="extracting", extractionStartedAt=utc())
        save(out / "manifest.json", manifest)
        print(f"Calling real Drafting extraction for {project_id}; awaiting synchronous response.", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(request, prefix + "/variables/extract", "POST", b"", "application/json", args.timeout)
            while True:
                try:
                    result = future.result(timeout=30)
                    break
                except concurrent.futures.TimeoutError:
                    print(f"Extraction in progress ({time.monotonic() - started:.0f}s); model trace will be saved when complete.", flush=True)
        save(out / "extract-response.json", result["body"])
        manifest["extractResponseStatus"] = result["httpStatus"]
        manifest["extractResponseCode"] = result["body"].get("code")
        if result["body"].get("code") != 0 or result["httpStatus"] != 200:
            manifest["errors"].append({"step": "extract", "response": result})
        manifest["status"] = "completed" if not manifest["errors"] else "extraction_failed"
    except Exception as exc:
        manifest["status"] = "runner_failed"
        manifest["errors"].append({"step": "runner", "type": type(exc).__name__, "message": str(exc)})
        print(f"Runner failure: {exc}", file=sys.stderr, flush=True)
    finally:
        if not args.prepare_only:
            for name, route in [("variables", "/variables"), ("trace", "/variables/extract-trace"),
                                ("plan", "/plan"), ("progress", "/progress")]:
                try:
                    result = request(prefix + route)
                    save(out / (name + ".json"), result["body"])
                    require(result, "final " + name)
                except Exception as exc:
                    manifest["errors"].append({"step": "readback " + name, "message": str(exc)})
            save(out / "gpu-after.json", gpu_snapshot())
            try:
                finish_runtime_evidence(out, runtime_info)
            except Exception as exc:
                manifest.setdefault("telemetryErrors", []).append(str(exc))
        manifest.update(finishedAt=utc(), elapsedSeconds=round(time.monotonic() - started, 3))
        save(out / "manifest.json", manifest)
        print(f"Run status: {manifest['status']}; evidence: {out}", flush=True)
    return 0 if manifest["status"] in {"completed", "prepared"} and not manifest["errors"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
