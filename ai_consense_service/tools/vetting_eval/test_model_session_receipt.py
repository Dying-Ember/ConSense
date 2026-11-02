"""Receipt contract tests with authored data only; no GPU or service."""
import copy
import hashlib
import json
import unittest

from model_session_receipt import describe_batch, verify_model_session_receipt
from token_windows import WindowError


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()


def sha(value):
    return hashlib.sha256(encoded(value)).hexdigest()


class ModelSessionReceiptTest(unittest.TestCase):
    def setUp(self):
        self.binding = {'requestId': 'a' * 32, 'sourceScopeSha256': 'b' * 64, 'modelKind': 'embedding',
            'modelIdentity': {'model': 'fixture', 'private': 'do-not-return'}, 'runtime': {'device': 'cuda'},
            'inferenceCap': 512, 'deadlineBudgetMs': 10000, 'maxBatches': 3, 'maxTotalBytes': 4096}
        self.stage = 'document-embedding'
        self.profile = 'c' * 64
        self.operation = 'd' * 32
        self.descriptors = [describe_batch(0, {'kind': 'dimension'}, {'dimension': 2}),
                            describe_batch(1, {'kind': 'embedding', 'texts': ['fixture']}, {'vectors': [[1, 0]]})]
        self.receipt = self.make_receipt()

    def make_receipt(self):
        claims = {k: self.binding[k] for k in ('requestId', 'sourceScopeSha256', 'modelKind', 'inferenceCap',
                                              'deadlineBudgetMs', 'maxBatches', 'maxTotalBytes')}
        claims.update(modelIdentitySha256=sha(self.binding['modelIdentity']), runtimeSha256=sha(self.binding['runtime']))
        batches = [{k: d[k] for k in ('ordinal', 'inputSha256', 'outputSha256')} for d in self.descriptors]
        summary = {'operationId': self.operation, 'bindingSha256': 'e' * 64, 'requestId': self.binding['requestId'],
            'batchCount': len(batches), 'batchesSha256': sha(batches),
            'totalBytes': sum(d['inputBytes'] + d['outputBytes'] for d in self.descriptors),
            'status': 'computation_completed_release_unverified'}
        return {'schema': 'consense.fixed-model-session-receipt.v1', 'operationId': self.operation,
            'stage': self.stage, 'sourceClaims': claims, 'bodySha256': hashlib.sha256(b'{}').hexdigest(),
            'sourceSha256': sha(self.binding), 'profileSha256': self.profile, 'bindingSha256': 'e' * 64,
            'modelSummarySha256': sha(summary), 'summary': summary, 'batches': batches,
            'release': {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': [123]}}

    def verify(self, receipt=None, **changes):
        args = {'expected_stage': self.stage, 'expected_binding': self.binding,
                'expected_profile_sha256': self.profile, 'expected_operation_id': self.operation,
                'batch_descriptors': self.descriptors}
        args.update(changes)
        return verify_model_session_receipt(self.receipt if receipt is None else receipt, **args)

    def test_valid_receipt_digest_keeps_limits_explicit(self):
        result = self.verify()
        self.assertEqual(sha(self.receipt), result['receiptSha256'])
        for name in ('bindingSha256IndependentlyRecomputed', 'batchObservationIndependentlyAttested',
                     'gpuReleaseIndependentlyVerified', 'executionClosureVerified'):
            self.assertIs(result[name], False)
        self.assertNotIn('do-not-return', json.dumps(result))

    def test_independent_stage_scope_model_runtime_profile_and_operation(self):
        variants = [dict(expected_stage='query-embedding-rerank'), dict(expected_profile_sha256='f' * 64),
                    dict(expected_operation_id='f' * 32)]
        for field, value in [('requestId', 'f' * 32), ('sourceScopeSha256', 'f' * 64),
                             ('modelIdentity', {'model': 'other'}), ('runtime', {'device': 'cuda', 'dtype': 'float16'}),
                             ('inferenceCap', 513), ('deadlineBudgetMs', 9999)]:
            variants.append({'expected_binding': {**self.binding, field: value}})
        for change in variants:
            with self.subTest(change=change), self.assertRaises(WindowError):
                self.verify(**change)

    def test_missing_extra_and_tampered_receipt_fields_fail(self):
        for field in self.receipt:
            altered = copy.deepcopy(self.receipt)
            del altered[field]
            with self.subTest(missing=field), self.assertRaises(WindowError): self.verify(altered)
        for field, value in [('bodySha256', '0' * 64), ('sourceSha256', '0' * 64),
                             ('modelSummarySha256', '0' * 64), ('bindingSha256', 'INVALID')]:
            altered = copy.deepcopy(self.receipt); altered[field] = value
            with self.subTest(field=field), self.assertRaises(WindowError): self.verify(altered)
        altered = {**self.receipt, 'unexpected': 1}
        with self.assertRaises(WindowError): self.verify(altered)

    def test_independently_observed_batches_sizes_and_order_are_required(self):
        variants = [[], self.descriptors[:1], list(reversed(self.descriptors)),
                    [{**self.descriptors[0], 'ordinal': True}, self.descriptors[1]],
                    [{**self.descriptors[0], 'inputSha256': '0' * 64}, self.descriptors[1]],
                    [{**self.descriptors[0], 'outputBytes': self.descriptors[0]['outputBytes'] + 1}, self.descriptors[1]],
                    [{**self.descriptors[0], 'inputBytes': 0}, self.descriptors[1]]]
        for observed in variants:
            with self.subTest(observed=observed), self.assertRaises(WindowError): self.verify(batch_descriptors=observed)
        altered = copy.deepcopy(self.receipt); altered['batches'][0]['ordinal'] = 1
        altered['summary']['batchesSha256'] = sha(altered['batches']); altered['modelSummarySha256'] = sha(altered['summary'])
        with self.assertRaises(WindowError): self.verify(altered)

    def test_budgets_and_summary_reject_coherent_forgery(self):
        for field, value in [('maxBatches', 1), ('maxTotalBytes', 1)]:
            self.binding[field] = value
            altered = self.make_receipt()
            with self.subTest(field=field), self.assertRaises(WindowError): self.verify(altered)
            self.binding[field] = 3 if field == 'maxBatches' else 4096
        altered = copy.deepcopy(self.receipt)
        altered['summary']['totalBytes'] += 1; altered['modelSummarySha256'] = sha(altered['summary'])
        with self.assertRaises(WindowError): self.verify(altered)
        altered = copy.deepcopy(self.receipt)
        altered['summary']['batchCount'] = True; altered['modelSummarySha256'] = sha(altered['summary'])
        with self.assertRaises(WindowError): self.verify(altered)

    def test_release_claims_require_exact_shape_and_valid_pids(self):
        variants = [{'status': 'release_unknown', 'cgroupPopulated': 0, 'observedGpuPids': [123]},
                    {'status': 'released', 'cgroupPopulated': False, 'observedGpuPids': [123]},
                    {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': []},
                    {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': [123, 123]},
                    {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': [0]},
                    {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': [123], 'extra': True}]
        for value in variants:
            altered = {**self.receipt, 'release': value}
            with self.subTest(release=value), self.assertRaises(WindowError): self.verify(altered)

    def test_self_consistent_binding_and_observation_claims_are_not_attestation(self):
        altered = copy.deepcopy(self.receipt)
        altered['bindingSha256'] = 'f' * 64
        altered['summary']['bindingSha256'] = altered['bindingSha256']
        altered['modelSummarySha256'] = sha(altered['summary'])
        result = self.verify(altered)
        self.assertIs(result['bindingSha256IndependentlyRecomputed'], False)
        self.assertIs(result['gpuReleaseIndependentlyVerified'], False)
        self.assertIs(result['batchObservationIndependentlyAttested'], False)
        invented = copy.deepcopy(self.descriptors)
        invented[0]['inputBytes'] += 3
        altered['summary']['totalBytes'] += 3
        altered['modelSummarySha256'] = sha(altered['summary'])
        result = self.verify(altered, batch_descriptors=invented)
        self.assertIs(result['batchObservationIndependentlyAttested'], False)


if __name__ == '__main__':
    unittest.main()
