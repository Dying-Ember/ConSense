"""Failure cleanup for a malformed provider response stub; no native service."""
import base64
import hashlib
from pathlib import Path
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from fixed_gpu_worker import Worker
from request_stage import encoded, envelope


class MissingFpTest(unittest.TestCase):
    def test_original_header_error_is_preserved_and_lock_released(self):
        class Reply:
            status = 200
            def close(self): pass
        class Conn:
            sock = SimpleNamespace(shutdown=lambda *_: None, close=lambda: None)
            def __init__(self, *args, **kwargs): pass
            def connect(self): pass
            def request(self, *args, **kwargs): pass
            def getresponse(self): return Reply()
            def close(self): pass
        with tempfile.TemporaryDirectory() as root:
            sample = b'fixture'; digest = hashlib.sha256(sample).hexdigest()
            binding = {'parser':'paddleocr-vl','inputSha256':digest,'inputByteCount':len(sample),
                       'originalSourceSha256':'a'*64,'physicalPageNo':1,'profileSha256':'b'*64}
            worker = Worker({'workspace':root,'stages':{'paddle':{'readyUrl':'http://127.0.0.1:28771/health',
                'timeoutSeconds':30,'providerOptions':{}}}}, secret='fixture')
            op = envelope('paddle', b'{}', binding, {'model':'fixture'})
            directory = Path(root)/'gpu-operations'/op['operationId']; directory.mkdir()
            (directory/'request.json').write_bytes(encoded(op))
            (directory/'status.json').write_bytes(encoded({'operationId':op['operationId'],'stage':'paddle',
                'status':'ready','bindingSha256':op['bindingSha256'],'deadlineUnixMs':int((time.time()+20)*1000)}))
            worker.active[op['operationId']] = (threading.Event(),threading.Event())
            worker.trees[op['operationId']] = object(); lock = threading.Lock()
            worker.sessions[op['operationId']] = {'lock':lock}
            with patch.object(worker,'_listener_owned',return_value=True), \
                 patch.object(worker,'_connection_owned',return_value=True), \
                 patch('fixed_gpu_worker.ProviderConnection',Conn):
                with self.assertRaisesRegex(AttributeError, 'headers'):
                    worker.provider_request(op['operationId'],'POST','/layout-parsing',
                        encoded({'file':base64.b64encode(sample).decode()}),'application/json','a'*32)
            self.assertFalse(lock.locked())
