#!/usr/bin/env python3
"""Real loopback HTTP boundary; no SSH, Compose, production or external requests."""
import http.server, importlib.util, json, threading, unittest, uuid
from pathlib import Path

spec=importlib.util.spec_from_file_location('cluster_release_http',Path(__file__).resolve().parents[1]/'src/main/resources/control/cluster-release-core.py')
core=importlib.util.module_from_spec(spec);spec.loader.exec_module(core)

class CallbackHttpChecks(unittest.TestCase):
    def setUp(self):
        self.header={'id':str(uuid.uuid4()),'clusterId':str(uuid.uuid4()),'epoch':3,'manifestHash':'a'*64,'siteId':'fixture','instanceId':str(uuid.uuid4()),'databaseFingerprint':'b'*32,'authorityToken':'r'*43};self.requests=[];self.response={k:self.header[k] for k in ['id','clusterId','epoch','manifestHash']}|{'protocol':'eatwhat-deployment-fence-v1','state':'ACTIVE','authorized':True,'dispatchAuthorized':True,'targets':[{k:self.header[k] for k in ['siteId','instanceId','databaseFingerprint']}]};self.mode='normal';test=self
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                test.requests.append((self.path,self.headers.get('X-Deployment-Token')))
                if test.mode=='redirect':self.send_response(302);self.send_header('Location','/must-not-receive-token');self.end_headers();return
                self.send_response(403 if test.mode=='denied' else 200);self.end_headers()
                body=b'x'*65537 if test.mode=='oversize' else b'provider-sensitive-error-text' if test.mode=='denied' else json.dumps(test.response).encode()
                self.wfile.write(body)
        self.server=http.server.HTTPServer(('127.0.0.1',0),Handler);self.worker=threading.Thread(target=self.server.serve_forever,daemon=True);self.worker.start();self.header['authorityOrigin']='http://127.0.0.1:'+str(self.server.server_port)
    def tearDown(self):self.server.shutdown();self.server.server_close();self.worker.join(2)
    def test_real_read_capability_header_and_exact_task_proof(self):
        self.assertTrue(core.verify_authority(self.header)['authorized']);self.assertEqual([('/internal/deployment/v1/fences/'+self.header['id']+'?manifest='+self.header['manifestHash'],self.header['authorityToken'])],self.requests)
    def test_redirect_is_never_followed_with_a_private_capability(self):
        self.mode='redirect'
        with self.assertRaises(core.ReleaseRejected) as error:core.verify_authority(self.header)
        self.assertEqual('RELEASE_AUTHORITY_UNAVAILABLE',error.exception.code);self.assertEqual(1,len(self.requests));self.assertNotIn('must-not-receive-token',self.requests[0][0])
    def test_bounded_response_and_safe_errors(self):
        for mode in ['oversize','denied']:
            self.mode=mode
            with self.subTest(mode=mode),self.assertRaises(core.ReleaseRejected) as error:core.verify_authority(self.header)
            self.assertEqual('RELEASE_AUTHORITY_UNAVAILABLE',error.exception.code);self.assertNotIn('sensitive',str(error.exception))
    def test_terminal_and_another_task_response_cannot_authorize_a_release(self):
        for key,value in [('id',str(uuid.uuid4())),('state','FAILED'),('authorized',False)]:
            original=self.response[key];self.response[key]=value
            with self.subTest(key=key),self.assertRaises(core.ReleaseRejected):core.verify_authority(self.header)
            self.response[key]=original

if __name__=='__main__':unittest.main()
