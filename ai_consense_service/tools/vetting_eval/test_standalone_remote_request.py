"""Standalone public routes must not bypass remote request ownership."""
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
import server

sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'offline_delivery'))


class StandaloneRemoteRequestTest(unittest.TestCase):
    def test_local_route_is_unchanged(self):
        app=FastAPI();app.post('/index')(server.index);app.post('/retrieve')(server.retrieve)
        with patch.object(server,'_remote_models',None): server.install_remote_request_routes(app)
        self.assertIs(server.index,next(r.endpoint for r in app.routes if getattr(r,'path',None)=='/index'))

    def test_standalone_remote_routes_refuse_before_model_or_database(self):
        for path,body in (('/index',{'projectId':'fixture','chunks':[]}),
                          ('/retrieve',{'projectId':'fixture','query':'fixture'})):
            with self.subTest(path=path):
                app=FastAPI();app.post('/index')(server.index);app.post('/retrieve')(server.retrieve)
                with tempfile.TemporaryDirectory() as state, \
                        patch.object(server,'STATE',Path(state)), \
                        patch.object(server,'_request_evidence',None), \
                        patch.object(server,'_remote_models',SimpleNamespace(timeout=3)), \
                        patch.object(server,'window_runtime',side_effect=AssertionError('model or database accessed')):
                    server.install_remote_request_routes(app)
                    with TestClient(app) as client:response=client.post(path,json=body)
                self.assertEqual(503,response.status_code,response.text)
                self.assertEqual('SOURCE_FRAME_PROOF_REQUIRED',response.json()['detail']['kind'])


if __name__=='__main__': unittest.main()
