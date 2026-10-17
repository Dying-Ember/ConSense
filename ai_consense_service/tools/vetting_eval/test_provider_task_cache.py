"""Authored transport/lifecycle contracts only; no native process or model call."""
import base64
import copy
from contextlib import contextmanager
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image
from artifact_store import ArtifactStore
import complete_ocr
from complete_ocr import CompleteOcrError, recognize_complete_page
from provider_task_cache import commit_success, load_success
import test_complete_ocr as fixtures
from test_paddle_native_codec import native_result, wire


class ProviderTaskCacheTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.store = ArtifactStore(self.root/'cas')
        self.fixture = fixtures.CompleteOcrTests()
        self.raw = self.fixture.image()
        self.config = self.fixture.config()
        self.config['timeoutSeconds'] = 30
        model = self.root/'authored-no-model.bin'; model.write_bytes(b'authored model identity only')
        for provider in ('paddleocr-vl', 'mineru'):
            self.config[provider]['identity']['models'][0]['assetPaths'] = [str(model)]
            self.config[provider]['ownedBoot'] = {'command': [str(Path(sys.executable).resolve())],
                'cwd': str(self.root), 'env': {}, 'runtimeAssets': [
                    {'path': str(path), 'byteCount': path.stat().st_size,
                     'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'kind': kind}
                    for path, kind in ((Path(sys.executable).resolve(), 'executable'), (model, 'model'))],
                'precision': 'bf16', 'readyTimeoutSeconds': 5, 'cleanupTimeoutSeconds': 2, 'readyPath': '/'}
        self.launches = []
        self.mode, self.release_status, self.expired = 'owned_native', 'released', False
        self.owner = patch('complete_ocr.owned_native_provider', self.lifecycle)
        self.owner.start(); self.addCleanup(self.owner.stop)

    @contextmanager
    def lifecycle(self, provider, remaining_seconds, receipt_dir):
        parser = provider['taskBinding']['parser']; self.launches.append(parser)
        lifecycle = {'mode': self.mode, 'pid': 100+len(self.launches), 'ownership': 'delegated_cgroup_v2'}
        try:
            yield lifecycle
        finally:
            lifecycle['exitReceipt'] = {'status': self.release_status, 'pid': lifecycle['pid'],
                'ownership': lifecycle['ownership'], 'cgroupPopulated': 0,
                'deadlineExpired': self.expired, 'primaryError': None}

    def source(self, digest='c'*64):
        context = self.fixture.source_context(self.raw)
        context['source']['sourceSha256'] = digest
        context['source']['sourceFilename'] = 'authored-'+digest[0]+'.pdf'
        context['originalPageTransform']['targetFrame']['sourceSha256'] = digest
        return context

    def transport(self, *, fail_mineru=False, incomplete=False):
        calls, transport = self.fixture.transport(self.raw, fail_mineru=fail_mineru)
        def request(url, body=None, timeout=None, method=None):
            response = transport(url, body, timeout, method)
            if url.endswith('/layout-parsing') and not incomplete:
                value = json.loads(response)
                manifest, arrays = native_result(self.raw, self.store)
                value['result'].update(wire(manifest, arrays))
                image = io.BytesIO(); Image.new('RGB', (2, 2), 'red').save(image, format='PNG')
                value['result']['layoutParsingResults'][0]['outputImages'] = {
                    'paddle-only.png': base64.b64encode(image.getvalue()).decode()}
                return json.dumps(value).encode()
            if url.endswith('/files/file-authored/content'):
                import zipfile
                output = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(response)) as original, zipfile.ZipFile(output, 'w') as target:
                    for name in original.namelist(): target.writestr(name, original.read(name))
                    image = io.BytesIO(); Image.new('RGB', (2, 2), 'blue').save(image, format='PNG')
                    target.writestr('mineru-only.png', image.getvalue())
                return output.getvalue()
            return response
        return calls, request

    def run_page(self, context=None, **transport_options):
        calls, request = self.transport(**transport_options)
        value = recognize_complete_page(self.raw, self.config, self.store, request=request,
            source_context=context or self.source())
        return value, calls

    def records(self):
        return [json.loads(self.store.read_asset(json.loads(path.read_bytes())))
                for path in (self.store.root/'provider-tasks').glob('*.json')]

    def test_two_original_identities_have_separate_success_and_zero_new_calls_on_second_request(self):
        for digest in ('c'*64, 'd'*64):
            first, calls = self.run_page(self.source(digest))
            self.assertEqual(len(calls), 7)
            second, calls = self.run_page(self.source(digest))
            self.assertEqual(calls, [])
            self.assertEqual(second['provenance']['providerLifecycles'], [])
            self.assertEqual(second['provenance']['rawResponses'], [])
            self.assertEqual(second['coverage']['freshProviderTasks'], 0)
            self.assertEqual(second['coverage']['completedProviderTaskHits'], 2)
            self.assertFalse(second['coverage']['dualExecutionComplete'])
            self.assertTrue(second['coverage']['dualResultAssemblyComplete'])
            for key in ('text', 'lines', 'regions', 'wholePageGroups', 'parserRuns', 'dualEvidenceRef'):
                self.assertEqual(second[key], first[key], key)
            self.assertFalse(second['coverage']['sourceQualityAccepted'])
            for task in second['provenance']['providerTasks']:
                self.assertEqual(task['mode'], 'completed_task_reuse')
                self.assertEqual(task['httpDispatches'], 0)
                lifecycle = json.loads(self.store.read_asset(task['historicalLifecycleRef']))
                self.assertEqual(lifecycle['exitReceipt']['status'], 'released')
                for attempt in lifecycle['transaction']['attempts']:
                    self.store.verify(attempt['response'])
        self.assertEqual(self.launches, ['paddleocr-vl', 'mineru']*2)
        self.assertEqual(len(self.records()), 4)

    def test_first_provider_success_survives_second_failure_and_only_missing_provider_runs(self):
        _, request = self.transport(fail_mineru=True)
        with self.assertRaises(CompleteOcrError) as caught:
            recognize_complete_page(self.raw, self.config, self.store, request=request, source_context=self.source())
        failure = json.loads(self.store.read_asset(caught.exception.receipt))
        self.assertEqual(failure['failedProvider'], 'mineru')
        self.assertEqual(len(self.records()), 1)
        self.assertEqual(failure['providerTasks'][0]['cacheDisposition'], 'success_committed')
        self.launches.clear()
        value, calls = self.run_page()
        self.assertEqual(self.launches, ['mineru'])
        self.assertEqual(len(calls), 6)
        self.assertTrue(all(not url.endswith('/layout-parsing') for url, _ in calls))
        self.assertEqual(value['coverage']['completedProviderTaskHits'], 1)
        self.assertEqual(value['coverage']['freshProviderTasks'], 1)
        self.assertEqual([x['mode'] for x in value['provenance']['providerTasks']], ['completed_task_reuse', 'fresh_provider'])

    def test_provider_asset_inventories_are_separate_and_one_bad_task_does_not_damage_sibling_success(self):
        self.run_page()
        records = {record['binding']['parser']: record for record in self.records()}
        self.assertTrue(all(not name.startswith('paddle/') for name in records['mineru']['assets']))
        self.assertTrue(all(not name.startswith('mineru/') for name in records['paddleocr-vl']['assets']))
        asset = records['mineru']['assets']['mineru/mineru-only.png']
        path = self.store.root/asset['relativePath']; original = path.read_bytes()
        for kind in ('missing', 'changed'):
            if kind == 'missing': path.unlink()
            else: path.write_bytes(b'X'*len(original))
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                load_success(self.store, records['mineru']['binding'])
            self.assertIsNotNone(load_success(self.store, records['paddleocr-vl']['binding']))
            path.write_bytes(original)

    def test_full_source_transform_frame_options_and_code_recipe_changes_do_not_hit(self):
        self.run_page()
        records = self.records()
        for mutate in (
            lambda b: b['source'].update(sourceSha256='f'*64),
            lambda b: b['pageMap'][0].update(width=101),
            lambda b: b['pageMap'][0]['originalPageTransform'].update(matrix=[.4, 0, 0, 0, .5, 0]),
            lambda b: b['sourceContext'].update(readingPlan={'version': 'changed'}),
            lambda b: b['provider']['identity']['config'].update(precision='changed'),
            lambda b: b['requestOptions'].update(extra_option=True),
            lambda b: b['implementation'].update(complete_ocr='changed')):
            binding = copy.deepcopy(records[0]['binding']); mutate(binding)
            self.assertIsNone(load_success(self.store, binding))
        with patch.dict(complete_ocr.PADDLE_OPTIONS, useDocUnwarping=True):
            value, calls = self.run_page()
        self.assertEqual(len(calls), 1)
        self.assertEqual(value['coverage']['completedProviderTaskHits'], 1)

    def test_only_mineru_model_identity_change_keeps_paddle_hit_and_runs_only_mineru(self):
        first, _ = self.run_page()
        self.launches.clear()
        self.config['mineru']['identity']['models'][0]['revision'] = 'e'*40
        value, calls = self.run_page()
        self.assertEqual(self.launches, ['mineru'])
        self.assertEqual(len(calls), 6)
        self.assertEqual([task['mode'] for task in value['provenance']['providerTasks']],
                         ['completed_task_reuse', 'fresh_provider'])
        self.assertEqual(value['text'], first['text'])
        self.assertEqual(value['regions'], first['regions'])
        self.assertEqual(len(self.records()), 3)

    def test_remote_unknown_release_expired_and_partial_are_never_committed(self):
        owned = copy.deepcopy(self.config)
        for name in ('paddleocr-vl', 'mineru'): self.config[name].pop('ownedBoot')
        self.mode, self.release_status = 'remote_handoff_unverified', 'remote_handoff_unverified'
        self.run_page(); self.run_page()
        self.assertEqual(self.records(), [])
        self.config = owned
        for mode, release, expired in [('remote_handoff_unverified', 'remote_handoff_unverified', False),
                ('owned_native', 'release_unknown', False), ('owned_native', 'released', True)]:
            self.mode, self.release_status, self.expired = mode, release, expired
            self.run_page(); self.run_page()
            self.assertEqual(self.records(), [])
        self.mode, self.release_status, self.expired = 'owned_native', 'released', False
        value, _ = self.run_page(incomplete=True)
        self.assertEqual([r['binding']['parser'] for r in self.records()], ['mineru'])
        self.assertEqual(value['provenance']['providerTasks'][0]['cacheDisposition'], 'not_eligible_for_success_reuse')

    def test_kubernetes_mode_requires_exact_transaction_and_proven_job_release(self):
        self.run_page()
        record = next(row for row in self.records() if row['binding']['parser'] == 'paddleocr-vl')
        binding = copy.deepcopy(record['binding'])
        binding['provider'].pop('ownedBoot'); binding['provider']['ownedJob'] = {'authored': 'filesystem config mocked only'}
        lifecycle = json.loads(self.store.read_asset(record['lifecycleRef']))
        lifecycle.update(mode='owned_kubernetes_job', taskBinding=lifecycle['transaction']['taskBinding'],
                         operationBindingSha256='b'*64)
        lifecycle['exitReceipt'] = {'status': 'released', 'podsAbsent': True, 'jobUid': 'authored-job-uid'}
        outputs = {k: self.store.read_asset(v) for k, v in record['rawOutputs'].items()}
        assets = {k: self.store.read_asset(v) for k, v in record['assets'].items()}
        import time
        with patch('provider_task_cache.validate_provider'):
            for mutate in (lambda x: x['exitReceipt'].update(podsAbsent=False),
                           lambda x: x.update(operationBindingSha256='unbound'),
                           lambda x: x.update(taskBinding={})):
                invalid = copy.deepcopy(lifecycle); mutate(invalid)
                self.assertIsNone(commit_success(self.store, binding, outputs, assets, invalid,
                    {'status': 'complete'}, record['captureMetadata'], deadline=time.monotonic()+10))
            self.assertIsNotNone(commit_success(self.store, binding, outputs, assets, lifecycle,
                {'status': 'complete'}, record['captureMetadata'], deadline=time.monotonic()+10))
            hit = load_success(self.store, binding)
            self.assertEqual(hit['outputs'], outputs)

    def test_deadline_after_normalization_and_during_commit_does_not_publish_success(self):
        clock = [0.0]
        original = complete_ocr.normalize_parser_run
        def late(*args, **kwargs):
            result = original(*args, **kwargs); clock[0] = 100.0; return result
        with patch('complete_ocr.time.monotonic', side_effect=lambda: clock[0]), \
                patch('complete_ocr.normalize_parser_run', side_effect=late):
            with self.assertRaises(CompleteOcrError): self.run_page()
        self.assertEqual(self.records(), [])
        self.run_page()
        record = self.records()[0]
        lifecycle = json.loads(self.store.read_asset(record['lifecycleRef']))
        outputs = {k: self.store.read_asset(v) for k, v in record['rawOutputs'].items()}
        assets = {k: self.store.read_asset(v) for k, v in record['assets'].items()}
        with patch('provider_task_cache.time.monotonic', side_effect=[0, 2]):
            self.assertIsNone(commit_success(self.store, record['binding'], outputs, assets, lifecycle,
                {'status': 'complete'}, record['captureMetadata'], deadline=1))

    def test_last_provider_late_material_is_retained_but_not_committed_or_used_for_late_auxiliary(self):
        clock = [0.0]
        original = complete_ocr.normalize_parser_run
        def late_last(parser, *args, **kwargs):
            result = original(parser, *args, **kwargs)
            if parser == 'mineru': clock[0] = 100.0
            return result
        calls, request = self.transport()
        with patch('complete_ocr.time.monotonic', side_effect=lambda: clock[0]), \
                patch('complete_ocr.normalize_parser_run', side_effect=late_last):
            value = recognize_complete_page(self.raw, self.config, self.store, request=request,
                source_context=self.source(), line_observer=lambda *_: self.fail('No late auxiliary call'))
        self.assertEqual(len(calls), 7)
        self.assertEqual(value['provenance']['providerTasks'][1]['cacheDisposition'], 'not_stored_total_deadline_elapsed')
        self.assertEqual(value['coverage']['auxiliaryLineCoverage']['status'], 'not_started_budget_exhausted')
        self.assertEqual([row['binding']['parser'] for row in self.records()], ['paddleocr-vl'])
        self.launches.clear()
        resumed, calls = self.run_page()
        self.assertEqual(self.launches, ['mineru'])
        self.assertEqual(len(calls), 6)
        self.assertEqual(resumed['text'], value['text'])
        clock[0] = 0.0
        with patch('complete_ocr.time.monotonic', side_effect=lambda: clock[0]), \
                patch('complete_ocr.normalize_parser_run', side_effect=late_last):
            with self.assertRaisesRegex(CompleteOcrError, 'deadline elapsed during provider capture validation'):
                self.run_page()

    def test_cpu_observer_still_runs_on_both_provider_cache_hits(self):
        self.run_page()
        from test_ocr_layout_coverage import capture_fixture
        calls, request = self.transport()
        seen = []
        def observe(raw, store):
            seen.append(raw); return capture_fixture(store, raw)
        value = recognize_complete_page(self.raw, self.config, self.store, request=request,
            source_context=self.source(), line_observer=observe)
        self.assertEqual(calls, [])
        self.assertEqual(seen, [self.raw])
        self.assertEqual(value['coverage']['auxiliaryLineCoverage']['fullPageOcrCalls'], 1)
        self.assertEqual(value['coverage']['completedProviderTaskHits'], 2)

    def test_bad_cached_cas_has_no_current_submission_but_lost_parse_post_remains_unknown(self):
        self.run_page()
        record = next(row for row in self.records() if row['binding']['parser'] == 'paddleocr-vl')
        asset = record['assets']['paddle/paddle-only.png']
        path = self.store.root/asset['relativePath']; original = path.read_bytes()
        path.write_bytes(b'X'*len(original))
        calls, request = self.transport()
        with self.assertRaises(CompleteOcrError) as caught:
            recognize_complete_page(self.raw, self.config, self.store, request=request, source_context=self.source())
        failure = json.loads(self.store.read_asset(caught.exception.receipt))
        self.assertEqual(calls, [])
        self.assertEqual(failure['attempts'], [])
        self.assertEqual(failure['providerLifecycles'], [])
        self.assertEqual(failure['completedResponses'], [])
        self.assertEqual(failure['remoteCleanup']['status'], 'not_applicable')
        self.assertEqual(len(self.records()), 2)
        path.write_bytes(original)
        calls, transport = self.transport()
        def lost_post(url, **kwargs):
            response = transport(url, **kwargs)
            if url.endswith('/parse/jobs'): raise TimeoutError('authored submission response lost')
            return response
        with patch.dict(complete_ocr.MINERU_OPTIONS, authored_option=True):
            with self.assertRaises(CompleteOcrError) as caught:
                recognize_complete_page(self.raw, self.config, self.store, request=lost_post, source_context=self.source())
        failure = json.loads(self.store.read_asset(caught.exception.receipt))
        self.assertEqual(len(calls), 4)
        self.assertIsNone(failure['ownedJobId'])
        self.assertEqual(failure['remoteCleanup']['status'], 'remote_submission_or_cancellation_unknown')


if __name__ == '__main__':
    unittest.main()
