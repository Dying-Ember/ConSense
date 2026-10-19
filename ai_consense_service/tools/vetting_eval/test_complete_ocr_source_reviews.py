"""Authored exact-dual review dispatch; no local source files or model calls."""
import copy
import json
import tempfile
import unittest

from artifact_store import ArtifactStore
from complete_ocr import recognize_complete_page, CompleteOcrError
from canonical_projection import _hash
from structured_parser_evidence import build_dual_parser_evidence
from test_canonical_projection import fixture
from test_structured_parser_evidence import accepted_manifest


class CompleteOcrSourceReviewsTests(unittest.TestCase):
    def setUp(self):
        from test_complete_ocr import CompleteOcrTests
        from test_paddle_native_codec import native_result, wire
        self.helper = CompleteOcrTests()
        self.raw = self.helper.image()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.store = ArtifactStore(self.temporary.name)
        self.calls, transport = self.helper.transport(self.raw)
        native_manifest, arrays = native_result(self.raw, self.store)

        def request(url, body=None, timeout=None, method=None):
            response = transport(url, body, timeout, method)
            if url.endswith('/layout-parsing'):
                value = json.loads(response)
                value['result'].update(wire(native_manifest, arrays))
                return json.dumps(value).encode()
            return response

        self.request = request

    def recognize(self, number, config=None):
        context = self.helper.source_context(self.raw)
        context['physicalPageNo'] = number
        context['originalPageTransform']['targetFrame']['physicalPageNo'] = number
        return recognize_complete_page(self.raw, self.helper.config() if config is None else config,
            self.store, request=self.request, source_context=context)

    def review(self, result, parser, *, route=True):
        original = json.loads(self.store.read_asset(result['dualEvidenceRef']))
        primary = next(run for run in original['runs'] if run['parser'] == 'paddleocr-vl')
        selected = next(run for run in original['runs'] if run['parser'] == parser)
        page = selected['pages'][0]['physicalPageNo']
        acceptance = accepted_manifest(selected) if route else None
        bundle = build_dual_parser_evidence(original['runs'], route_acceptance_manifest=acceptance,
            artifact_store=self.store)
        _, manifest = fixture()
        manifest.update(bundleSha256=bundle['bundleSha256'], sourceSha256=bundle['source']['sourceSha256'],
            sourceRevisionSha256=bundle['source']['sourceRevisionSha256'], tableGroups=[], relations=[], assets=[])
        manifest['scope']['requiredPhysicalPages'] = [page]
        manifest['units'] = [{'unitId': 'complete-body', 'scope': 'region', 'runId': selected['runId'],
            'blockId': selected['pages'][0]['blocks'][0]['blockId'], 'reviewedAttributes': ['text', 'geometry']}]
        replacements = [] if parser == 'paddleocr-vl' else [{
            'primaryBlockIds': [primary['pages'][0]['blocks'][0]['blockId']], 'selectedUnitIds': ['complete-body']}]
        manifest['reference'].update(sourceSha256=bundle['source']['sourceSha256'], physicalPages=[page],
            evidence={'purpose': 'Authored review dispatch, not an actual original-source quality claim',
                'completePageCoverage': [{'physicalPageNo': page, 'primaryRunId': primary['runId'],
                    'replacements': replacements}]})
        manifest['reference']['evidenceSha256'] = _hash(manifest['reference']['evidence'])
        entry = {'runIds': [run['runId'] for run in original['runs']], 'canonicalAdoptionManifest': manifest}
        if route:
            entry['parserRouteAcceptance'] = acceptance
        return entry

    def test_one_config_dispatches_two_complete_runs_and_leaves_unmatched_page_unchanged(self):
        original3, original4, original2 = [self.recognize(page) for page in (3, 4, 2)]
        review3, review4 = self.review(original3, 'mineru'), self.review(original4, 'paddleocr-vl', route=False)
        self.assertNotEqual(set(review3['runIds']), set(review4['runIds']))
        config = self.helper.config()
        config['sourceReviews'] = [review3, review4]
        selected3, selected4, unmatched2 = [self.recognize(page, config) for page in (3, 4, 2)]
        self.assertEqual(selected3['text'], 'A different exception.')
        self.assertEqual(selected4['text'], 'A mandatory exception.')
        self.assertEqual(selected3['regions'][0]['channel'], 'mineru')
        self.assertEqual(selected4['regions'][0]['channel'], 'paddleocr-vl')
        for selected, page in ((selected3, 3), (selected4, 4)):
            projection = json.loads(self.store.read_asset(selected['provenance']['canonicalProjectionRef']))
            self.assertEqual(projection['manifest']['scope']['requiredPhysicalPages'], [page])
            self.assertEqual(selected['regions'][0]['canonicalSelection']['projectionSha256'], projection['projectionSha256'])
            self.assertEqual(len(selected['wholePageGroups'][0]['candidates']), 2)
            self.assertFalse(selected['coverage']['sourceQualityAccepted'])
        for key in ('text', 'regions', 'wholePageGroups', 'dualEvidenceRef', 'parserRuns'):
            self.assertEqual(unmatched2[key], original2[key], key)
        self.assertNotIn('canonicalProjectionRef', unmatched2['provenance'])

    def test_run_id_and_rule_list_order_do_not_change_the_matched_review(self):
        first, second = self.recognize(3), self.recognize(4)
        a, b = self.review(first, 'mineru'), self.review(second, 'paddleocr-vl', route=False)
        a['runIds'].reverse()
        config = self.helper.config()
        config['sourceReviews'] = [b, a]
        self.assertEqual(self.recognize(3, config)['text'], 'A different exception.')

    def test_review_with_swapped_source_binding_is_rejected_after_exact_run_match(self):
        a, b = self.recognize(3), self.recognize(4)
        review = self.review(a, 'mineru')
        review['runIds'] = self.review(b, 'paddleocr-vl', route=False)['runIds']
        config = self.helper.config()
        config['sourceReviews'] = [review]
        with self.assertRaisesRegex(CompleteOcrError, 'stale_bundle_adoption'):
            self.recognize(4, config)

    def test_duplicate_run_sets_are_rejected_before_any_provider_call(self):
        review = self.review(self.recognize(3), 'mineru')
        duplicate = copy.deepcopy(review)
        duplicate['runIds'].reverse()
        config = self.helper.config()
        config['sourceReviews'] = [review, duplicate]
        self.calls.clear()
        with self.assertRaisesRegex(ValueError, 'Conflicting source reviews'):
            self.recognize(3, config)
        self.assertEqual(self.calls, [])

    def test_singular_and_list_configuration_cannot_mix_before_any_provider_call(self):
        review = self.review(self.recognize(3), 'mineru')
        for singular in ('parserRouteAcceptance', 'canonicalAdoptionManifest'):
            with self.subTest(singular=singular):
                config = self.helper.config()
                config['sourceReviews'] = [review]
                config[singular] = review[singular]
                self.calls.clear()
                with self.assertRaisesRegex(ValueError, 'one source-review configuration form'):
                    self.recognize(3, config)
                self.assertEqual(self.calls, [])

    def test_malformed_review_shapes_are_rejected_before_any_provider_call(self):
        review = self.review(self.recognize(3), 'mineru')
        malformed = [None, {}, dict(review, extraField=True), dict(review, runIds=[review['runIds'][0]]),
            dict(review, runIds=[review['runIds'][0], review['runIds'][0]]),
            dict(review, canonicalAdoptionManifest=None),
            dict(review, runIds=['not-a-run', 'also-not-a-run']),
            dict(review, runIds=['paddleocr-vl:' + 'a' * 64, 'paddleocr-vl:' + 'b' * 64])]
        for index, entry in enumerate(malformed):
            with self.subTest(case=index):
                config = self.helper.config()
                config['sourceReviews'] = [entry]
                self.calls.clear()
                with self.assertRaises(ValueError):
                    self.recognize(3, config)
                self.assertEqual(self.calls, [])

    def test_non_list_source_reviews_are_rejected_before_any_provider_call(self):
        for value in (None, {}, True, 'invalid'):
            with self.subTest(value=value):
                config = self.helper.config()
                config['sourceReviews'] = value
                with self.assertRaises(ValueError):
                    self.recognize(3, config)
                self.assertEqual(self.calls, [])

    def test_empty_review_list_preserves_the_default_observations(self):
        original = self.recognize(3)
        config = self.helper.config()
        config['sourceReviews'] = []
        unchanged = self.recognize(3, config)
        for key in ('text', 'regions', 'wholePageGroups', 'dualEvidenceRef', 'parserRuns'):
            self.assertEqual(unchanged[key], original[key], key)


if __name__ == '__main__':
    unittest.main(verbosity=2)
