"""Diagnostic receipts must not duplicate complete index inputs or vectors."""
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

from oer_request_evidence import RequestEvidence


class JournalWorkloadTest(unittest.TestCase):
    def test_compact_journal_source_adapter_is_exact(self):
        import oer_request_evidence
        import portable_index_signature as adapter
        from token_windows import WindowError
        current = Path(oer_request_evidence.__file__).read_text(encoding='utf-8')
        compact = """        elif stage == 'parent-bm25-candidates':
            scores = payload.get('allScopeScores', [])
            selected = set(payload.get('orderedCandidateIds', []))
            payload = {key: value for key, value in payload.items() if key != 'allScopeScores'}
            payload.update(scoredScopeCount=len(scores),
                           candidateScores=[row for row in scores if row['id'] in selected],
                           recording='selected_candidates_only')
"""
        self.assertEqual(1, current.count(compact))
        previous = current.replace(compact, '')
        adapter.computation_proof('oer_request_evidence.py', previous, current)
        before = {'signatureVersion': 6, 'algorithms': {'oer_request_evidence.py': hashlib.sha256(previous.encode()).hexdigest()}}
        after = {'signatureVersion': 6, 'algorithms': {'oer_request_evidence.py': hashlib.sha256(current.encode()).hexdigest()}}
        self.assertTrue(adapter.transport_candidate(before, after))
        with self.assertRaises(WindowError):
            adapter.computation_proof('oer_request_evidence.py', previous, current + '\nUNREVIEWED_CHANGE = True\n')

    def test_retrieval_journal_keeps_candidate_scores_without_copying_the_full_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            journal = RequestEvidence(temporary)
            scores = [{"id": str(index), "score": float(index)} for index in range(17509)]
            payload = {"allScopeScores": scores, "orderedCandidateIds": ["5", "9000"]}
            with journal.operation("/retrieve", {"projectId": "whole-scope"}):
                journal.observe("parent-bm25-candidates", payload)
            directory = next(Path(temporary).iterdir())
            raw = (directory / "events.jsonl").read_bytes()
            event = json.loads(raw)["payload"]
            self.assertLess(len(raw), 4000)
            self.assertEqual(17509, event["scoredScopeCount"])
            self.assertEqual([scores[5], scores[9000]], event["candidateScores"])
            self.assertNotIn("allScopeScores", event)
            self.assertIs(scores, payload["allScopeScores"])
            receipt = json.loads((directory / "receipt.json").read_bytes())
            self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt["events"]["sha256"])

    def test_index_wrapper_records_summary_without_dumping_or_changing_actual_request(self):
        class IndexRequest:
            projectId = "complete-authored-scope"
            rebuild = True
            chunks = [SimpleNamespace(id="one", content="Visible source text", sourceHash="a" * 64,
                                      originalSourceSha256="b" * 64, projectionSha256="c" * 64)]
            def model_dump(self, **kwargs):
                raise AssertionError("Complete index request must not be copied for a diagnostic receipt")
        for nested in (False, True):
            with self.subTest(nested=nested), tempfile.TemporaryDirectory() as temporary:
                journal, received, request = RequestEvidence(temporary), [], IndexRequest()
                def actual(value):
                    received.append(value)
                    return {"projectId": value.projectId, "indexed": len(value.chunks)}
                recorded = journal.wrap("/index", actual)
                if nested:
                    with journal.operation("/index-canonical", {"projectId": request.projectId}):
                        self.assertEqual(1, recorded(request)["indexed"])
                else:
                    self.assertEqual(1, recorded(request)["indexed"])
                self.assertEqual([request], received)
                directory = next(Path(temporary).iterdir())
                if nested:
                    event = json.loads((directory / "events.jsonl").read_bytes())
                    saved = event["payload"]["request"]
                else:
                    saved = json.loads((directory / "request.json").read_bytes())
                self.assertEqual(1, saved["chunkCount"])
                self.assertEqual(["a" * 64], saved["sourceHashes"])
                self.assertEqual(["b" * 64], saved["originalSourceSha256"])
                self.assertEqual(["c" * 64], saved["projectionSha256"])
                self.assertNotIn("chunks", saved)
                self.assertNotIn("Visible source text", json.dumps(saved))
                self.assertIsNone(journal.active.get())

    def test_vector_progress_keeps_counts_and_ids_without_copying_vector_values(self):
        with tempfile.TemporaryDirectory() as temporary:
            journal = RequestEvidence(temporary)
            vectors = [[float(index) / 1024 for index in range(1024)] for _ in range(128)]
            payload = {"begin": 256, "vectors": vectors, "dimension": 1024,
                       "windowIds": [str(index) for index in range(128)],
                       "parentIds": ["source-parent"] * 128,
                       "fixedCacheHitsCumulative": 12, "encodedWindowsCumulative": 372}
            with journal.operation("/index-canonical", {"projectId": "whole-scope"}):
                journal.observe("window-index-upsert", payload)
            directory = next(Path(temporary).iterdir())
            raw = (directory / "events.jsonl").read_bytes()
            event = json.loads(raw)
            self.assertLess(len(raw), 8000)
            self.assertNotIn("vectors", event["payload"])
            self.assertEqual(128, event["payload"]["vectorCount"])
            self.assertEqual(payload["windowIds"], event["payload"]["windowIds"])
            self.assertEqual(372, event["payload"]["encodedWindowsCumulative"])
            self.assertIs(vectors, payload["vectors"])
            receipt = json.loads((directory / "receipt.json").read_bytes())
            self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt["events"]["sha256"])
            self.assertEqual("complete", receipt["events"]["integrityStatus"])

    def test_source_plan_records_counts_without_full_source_text_or_point_graph(self):
        with tempfile.TemporaryDirectory() as temporary:
            journal = RequestEvidence(temporary)
            source = "Private complete source body. " * 50000
            payload = {"points": [{"content": source}], "contexts": {"source": source},
                       "bindings": [source, source], "coverage": {"parents": 2, "windows": 1,
                       "contextUnavailable": [{"details": source}]}}
            with journal.operation("/index-canonical", {"projectId": "whole-scope"}):
                journal.observe("dual-granularity-source-plan", payload)
            raw = next(Path(temporary).glob("*/events.jsonl")).read_bytes()
            event = json.loads(raw)
            self.assertLess(len(raw), 4000)
            self.assertEqual(1, event["payload"]["pointCount"])
            self.assertEqual(1, event["payload"]["contextCount"])
            self.assertEqual(2, event["payload"]["bindingCount"])
            self.assertEqual(1, event["payload"]["contextUnavailableCount"])
            self.assertNotIn(b"Private complete source body", raw)
            self.assertEqual(source, payload["points"][0]["content"])


if __name__ == "__main__":
    unittest.main()
