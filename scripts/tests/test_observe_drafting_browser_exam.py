"""Offline observer time-boundary tests; all runner/API operations are synthetic."""
import contextlib
import io
import json
from pathlib import Path
import runpy
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "observe-drafting-browser-exam.py"
VERSION = "synthetic-observer-test"


class ObserverTimeBoundaryTests(unittest.TestCase):
    def observe(self, observed, started, finished=None, version=VERSION):
        """Execute the CLI against an isolated manifest and a fake GET-only runner."""
        trace = {
            "runId": "synthetic-run", "status": "completed", "harnessVersion": version,
            "startedAt": started, "finishedAt": finished or started,
        }
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            manifest = {"status": "prepared", "baseUrl": "http://synthetic.invalid", "projectId": "synthetic-project"}
            (out / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            requests = []

            def request(url, *args, **kwargs):
                # The observer must continue to use only the default GET operation.
                self.assertFalse(args)
                self.assertFalse(kwargs)
                requests.append(url)
                data = trace if url.endswith("/extract-trace") else [] if url.endswith("/variables") else {}
                return {"httpStatus": 200, "body": {"code": 0, "data": data}}

            def save(path, value):
                path.write_text(json.dumps(value), encoding="utf-8")

            fake_runner = SimpleNamespace(
                request=request, require=lambda response, _label: response["body"]["data"],
                save=save, utc=lambda: observed, begin_runtime_evidence=lambda _out: {},
                finish_runtime_evidence=lambda _out, _runtime: None, gpu_snapshot=lambda: {},
            )
            fake_spec = SimpleNamespace(loader=SimpleNamespace(exec_module=lambda _module: None))
            argv = [str(SCRIPT), "--run-dir", str(out), "--expected-harness-version", VERSION]
            with patch("sys.argv", argv), patch("importlib.util.spec_from_file_location", return_value=fake_spec), \
                    patch("importlib.util.module_from_spec", return_value=fake_runner), \
                    patch("time.monotonic", return_value=100), patch("time.sleep") as sleep, \
                    contextlib.redirect_stdout(io.StringIO()):
                error = None
                try:
                    runpy.run_path(str(SCRIPT), run_name="__main__")
                except (AssertionError, ValueError, TypeError) as exc:
                    error = exc
            self.assertFalse(sleep.called)
            return json.loads((out / "manifest.json").read_text(encoding="utf-8")), error, requests

    def assert_accepted(self, observed, started, finished=None, elapsed=0):
        manifest, error, requests = self.observe(observed, started, finished)
        self.assertIsNone(error)
        self.assertEqual("completed", manifest["status"])
        self.assertAlmostEqual(elapsed, manifest["elapsedSeconds"], places=9)
        self.assertEqual(6, len(requests))

    def assert_rejected(self, observed, started, finished=None):
        manifest, error, requests = self.observe(observed, started, finished)
        self.assertIsNotNone(error)
        self.assertEqual("observer_failed", manifest["status"])
        self.assertEqual(2, len(requests), "Invalid trace must fail before final artifact readbacks.")

    def test_equivalent_utc_spellings_are_accepted(self):
        for observed, started in [
            ("2026-10-09T00:00:00Z", "2026-10-09T00:00:00+00:00"),
            ("2026-10-09T00:00:00+00:00", "2026-10-09T00:00:00Z"),
            ("2026-10-09T00:00:00.123456+00:00", "2026-10-09T00:00:00.123456000Z"),
        ]:
            with self.subTest(observed=observed, started=started):
                self.assert_accepted(observed, started)

    def test_equivalent_timezone_offsets_are_accepted(self):
        for started in ["2026-10-09T08:00:00+08:00", "2026-10-08T19:00:00-05:00"]:
            with self.subTest(started=started):
                self.assert_accepted("2026-10-09T00:00:00Z", started)

    def test_newer_instants_are_accepted_across_offsets_and_fraction_lengths(self):
        for observed, started in [
            ("2026-10-09T00:00:00Z", "2026-10-08T19:00:01-05:00"),
            ("2026-10-09T00:00:00Z", "2026-10-09T00:00:00.000000001Z"),
        ]:
            with self.subTest(observed=observed, started=started):
                self.assert_accepted(observed, started)

    def test_prior_instants_are_rejected_across_offsets(self):
        for started in ["2026-10-09T07:59:59+08:00", "2026-10-09T00:00:00+01:00"]:
            with self.subTest(started=started):
                self.assert_rejected("2026-10-09T00:00:00Z", started)

    def test_prior_nanosecond_is_rejected_without_microsecond_truncation(self):
        self.assert_rejected("2026-10-09T00:00:00.123456700+00:00", "2026-10-09T00:00:00.123456699Z")

    def test_later_nanosecond_is_accepted(self):
        self.assert_accepted("2026-10-09T00:00:00.123456700+00:00", "2026-10-09T00:00:00.123456701Z")

    def test_elapsed_uses_instants_across_offsets(self):
        self.assert_accepted("2026-10-09T00:00:00Z", "2026-10-09T08:00:00+08:00",
                             "2026-10-08T19:00:02.5-05:00", elapsed=2.5)

    def test_elapsed_preserves_nanosecond_difference(self):
        self.assert_accepted("2026-10-09T00:00:00Z", "2026-10-09T00:00:00.123456701Z",
                             "2026-10-09T00:00:00.123456799Z", elapsed=0.000000098)

    def test_finished_before_started_is_rejected(self):
        self.assert_rejected("2026-10-09T00:00:00Z", "2026-10-09T00:00:02Z", "2026-10-09T00:00:01Z")

    def test_missing_timezone_is_rejected(self):
        for observed, started, finished in [
            ("2026-10-09T00:00:00", "2026-10-09T00:00:01Z", None),
            ("2026-10-09T00:00:00Z", "2026-10-09T00:00:01", None),
            ("2026-10-09T00:00:00Z", "2026-10-09T00:00:01Z", "2026-10-09T00:00:02"),
        ]:
            with self.subTest(observed=observed, started=started, finished=finished):
                self.assert_rejected(observed, started, finished)

    def test_invalid_timestamp_is_rejected(self):
        for started in ["invalidZ", "2026-10-09T00:00:01+00:60", "2026-10-09T00:00:01+24:00",
                        "2026-10-09T00:00:01.1234567890Z", "2026-02-30T00:00:01Z"]:
            with self.subTest(started=started):
                self.assert_rejected("2026-10-09T00:00:00Z", started)

    def test_unexpected_harness_version_still_rejected(self):
        manifest, error, requests = self.observe("2026-10-09T00:00:00Z", "2026-10-09T00:00:01Z", version="wrong")
        self.assertIsNotNone(error)
        self.assertEqual("observer_failed", manifest["status"])
        self.assertEqual(2, len(requests))


if __name__ == "__main__":
    unittest.main()
