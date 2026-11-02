import base64
import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from fixed_gpu_worker import Worker
from request_stage import encoded, envelope


class ResultReleaseTest(unittest.TestCase):
    def test_released_result_requires_complete_release_and_matching_owner(self):
        for final in ('completed', 'failed'):
            with self.subTest(final=final), TemporaryDirectory() as root:
                worker = Worker({'workspace': root, 'stages': {}}, 'example-secret')
                op = envelope('business-llm', b'{}', {'httpPath': '/v1/chat/completions'}, {'model': 'fixture'})
                directory = worker.operations / op['operationId']; directory.mkdir()
                response = {'contract': 'consense.gpu-operation-response.v1', 'bindingSha256': op['bindingSha256'],
                            'status': 200 if final == 'completed' else 503, 'headers': {},
                            'bodyBase64': base64.b64encode(b'result').decode()}
                result = encoded(response)
                (directory / 'request.json').write_bytes(encoded(op))
                (directory / 'response.json').write_bytes(result)
                status = {'operationId': op['operationId'], 'stage': op['stage'], 'status': final,
                          'bindingSha256': op['bindingSha256'], 'responseSha256': hashlib.sha256(result).hexdigest(),
                          'release': {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': [123]}}
                path = directory / 'status.json'; path.write_bytes(encoded(status))
                self.assertEqual(response, worker.result(op['operationId']))
                restarted = Worker({'workspace': root, 'stages': {}}, 'example-secret')
                self.assertEqual(response, restarted.result(op['operationId']))
                for release in ({'status': 'released'},
                                {'status': 'released', 'cgroupPopulated': False, 'observedGpuPids': [123]},
                                {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': []},
                                {'status': 'release_unknown'}):
                    with self.subTest(release=release):
                        status['release'] = release; path.write_bytes(encoded(status))
                        with self.assertRaises(ValueError): worker.result(op['operationId'])
                status['release'] = {'status': 'released', 'cgroupPopulated': 0, 'observedGpuPids': [123]}
                status['operationId'] = 'f' * 32; path.write_bytes(encoded(status))
                with self.assertRaises(ValueError): worker.result(op['operationId'])


if __name__ == '__main__': unittest.main()
