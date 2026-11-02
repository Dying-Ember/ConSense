"""Fixed-reference stage follows the existing synthetic GPU owner lifecycle."""
import base64
import hashlib
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from fixed_gpu_worker import Worker
from request_stage import encoded


class FixedReferenceOwnerTest(unittest.TestCase):
    def test_profile_pin_owner_and_release(self):
        with TemporaryDirectory() as root:
            profile = {'mode': 'model-session', 'profile': {'adapter': 'consense.model-session.v1'},
                       'timeoutSeconds': 10}
            worker = Worker({'workspace': root, 'stages': {'fixed-reference-query': profile}}, 'example-secret')
            binding = {'requestId': '1'*32, 'sourceScopeSha256': '2'*64, 'modelKind': 'embedding',
                       'modelIdentity': {'id': 'fixture'}, 'runtime': {'device': 'cuda'}, 'inferenceCap': 512,
                       'deadlineBudgetMs': 5000, 'maxBatches': 2, 'maxTotalBytes': 4096}
            request = {'stage': 'fixed-reference-query', 'binding': binding,
                       'bodyBase64': base64.b64encode(b'{}').decode(),
                       'expectedProfileSha256': hashlib.sha256(encoded(profile['profile'])).hexdigest(),
                       'expectedMode': 'model-session'}
            with patch.object(worker, '_validate_profile'), patch('fixed_gpu_worker.threading.Thread'):
                with self.assertRaises(ValueError):
                    worker.start({**request, 'expectedProfileSha256': '0'*64})
                self.assertFalse(worker.marker.exists())
                result = worker.start(request)
                operation_id = result['operationId']
                directory = worker.operations / operation_id
                self.assertTrue(worker.marker.exists())
                with self.assertRaises(ValueError):
                    worker.start({**request, 'binding': {**binding, 'modelKind': 'reranker'}})
                worker._publish_terminal(directory, {'status': 'release_unknown',
                                                       'release': {'status': 'release_unknown'}})
                self.assertTrue(worker.marker.exists())
                release = {'status': 'released', 'cgroupPopulated': 0,
                           'ownership': 'delegated_cgroup_v2', 'processWaited': True}
                worker._publish_terminal(directory, {'status': 'completed', 'release': release})
                self.assertFalse(worker.marker.exists())
                self.assertEqual(worker.status(operation_id)['release'], release)
                rerank = worker.start({**request, 'binding': {**binding, 'modelKind': 'reranker'}})
                self.assertNotEqual(operation_id, rerank['operationId'])


if __name__ == '__main__':
    unittest.main()
