"""Native HTTP wire captured through a real tiny child; no LLM or cluster runs."""
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import sys
import unittest
from unittest.mock import patch

from test_delivery import DeliveryFixture
import chat_operation_worker
import request_stage
import stage_worker


class ChatOperationWorkerTest(DeliveryFixture,unittest.TestCase):
    def setUp(self):
        super().setUp(); self.workspace = self.root/'workspace'; self.workspace.mkdir()
        with socket.socket() as sock: sock.bind(('127.0.0.1',0)); port = sock.getsockname()[1]
        script = "import hashlib,json\nfrom http.server import BaseHTTPRequestHandler,HTTPServer\nclass H(BaseHTTPRequestHandler):\n def do_GET(self):\n  self.send_response(200);self.end_headers();self.wfile.write(b'ok')\n def do_POST(self):\n  raw=self.rfile.read(int(self.headers['Content-Length']));self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(json.dumps({'actualRequestSha256':hashlib.sha256(raw).hexdigest(),'fixtureOnly':True}).encode())\nHTTPServer(('127.0.0.1',"+str(port)+"),H).serve_forever()"
        self.profile = {'adapter':'consense.native-chat.v1','model':'authored-only','baseUrl':'http://127.0.0.1:'+str(port),
            'command':[sys.executable,'-c',script],'environment':{'CUDA_VISIBLE_DEVICES':'0','HF_HUB_OFFLINE':'1','TRANSFORMERS_OFFLINE':'1'},
            'readyPath':'/health','timeoutSeconds':5,'apiPaths':{'/v1/chat/completions':'/v1/chat/completions'}}
        self.manifest['requestStages'] = {'business-llm':{'command':[sys.executable,str(Path(chat_operation_worker.__file__).resolve())],
            'runtimeAssets':['worker.py'],'profile':self.profile}}
        self.preparation = self.prepare(); self.deleted = False

    def runner(self,argv,payload=None):
        if 'get' in argv and 'pvc' in argv: return {'metadata':{'uid':'fixture-pvc'}}
        if 'get' in argv and 'pods' in argv:
            return {'items':[] if self.deleted or not any(value.startswith('job-name=') for value in argv) else [
                {'metadata':{'uid':'fixture-pod','ownerReferences':[{'uid':'fixture-job'}]}}]}
        if 'create' in argv:
            job = json.loads(payload); env = {row['name']:row['value'] for row in job['spec']['template']['spec']['containers'][0]['env'] if 'value' in row}
            env.update(CONSENSE_JOB_UID='fixture-job',CONSENSE_POD_UID='fixture-pod')
            with patch.dict(os.environ,env): self.worker_result = stage_worker.execute(env['CONSENSE_OPERATION_MANIFEST'],self.root,self.workspace,'business-llm')
            return {'metadata':{'uid':'fixture-job'}}
        if 'get' in argv and 'job' in argv: return {'metadata':{'uid':'fixture-job'},'status':{'succeeded':1} if self.worker_result['status'] == 'persisted' else {'failed':1}}
        if 'delete' in argv: self.deleted = True
        return {}

    def test_exact_raw_wire_reaches_owned_http_child_then_persisted_job_releases(self):
        body = b'{"model":"authored-only", "messages":[{"role":"user","content":"Actual raw wire"}],"stream":false}'
        operation = request_stage.envelope('business-llm',body,{'httpPath':'/v1/chat/completions'},self.profile)
        result = request_stage.run_request(self.root/'prepared',self.root,self.workspace,operation,self.preparation['manifestSha256'],runner=self.runner)
        self.assertEqual(200,result['status']); self.assertTrue(self.deleted)
        response = json.loads(base64.b64decode(result['bodyBase64']))
        self.assertEqual(hashlib.sha256(body).hexdigest(),response['actualRequestSha256'])
        self.assertEqual('unavailable-finite-job',result['headers']['x-consense-body-stream'])
        self.assertEqual('configured_native_job_profile_not_attested_loaded_model_identity',result['runtimeIdentityScope'])

    def test_another_model_cannot_launch_native_server_or_publish_a_result(self):
        body = b'{"model":"another-model","messages":[],"stream":false}'
        operation = request_stage.envelope('business-llm',body,{'httpPath':'/v1/chat/completions'},self.profile)
        with self.assertRaisesRegex(ValueError,'Job failed'):
            request_stage.run_request(self.root/'prepared',self.root,self.workspace,operation,self.preparation['manifestSha256'],runner=self.runner)
        self.assertTrue(self.deleted)
        self.assertEqual('failed',self.worker_result['status'])
        self.assertFalse((self.workspace/'gpu-operations'/operation['operationId']/'response.json').exists())


if __name__ == '__main__': unittest.main()
