import http.client
import socket
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import uvicorn
import server
from test_server_business_adapter import FakeClient

class KeepAliveTest(unittest.TestCase):
    def test_two_sequential_post_requests(self):
        client = FakeClient()
        config = {'model':'authored-model','token':'outer-authored','profileSha256':'a'*64,
                  'executionSha256':'b'*64,'timeout':3,'verifyRelease':lambda receipt, claims: True}
        listener = socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        listener.bind(('127.0.0.1',0)); listener.listen(64)
        port=listener.getsockname()[1]
        svc=uvicorn.Server(uvicorn.Config(server.app,log_level='critical',lifespan='off',http='h11'))
        with patch.object(server,'_remote_models',SimpleNamespace(client=client)), patch.object(server,'_trusted_business_configuration',return_value=config):
            thread=threading.Thread(target=lambda:svc.run(sockets=[listener]),daemon=True);thread.start()
            try:
                deadline=time.monotonic()+5
                while not svc.started and time.monotonic()<deadline:time.sleep(.01)
                self.assertTrue(svc.started)
                conn=http.client.HTTPConnection('127.0.0.1',port,timeout=3)
                try:
                    for i in range(2):
                        conn.request('POST','/v1/chat/completions',body=b'{"model":"authored-model","stream":false}',headers={'Authorization':'Bearer outer-authored','Content-Type':'application/json'})
                        result=conn.getresponse();body=result.read()
                        self.assertEqual(200,result.status)
                        self.assertEqual(b'{"result":"authored"}',body)
                    self.assertEqual(2,len(client.calls))
                finally:conn.close()
            finally:
                svc.should_exit=True;thread.join(timeout=5);listener.close()
                self.assertFalse(thread.is_alive())
