package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Python HTTP client; the local protocol server simulates only health
 * responses, not Docker, JOIN, authority or full installation. */
class ControlMemberHealthNegotiationTest {
    @Test void explicitlyNegotiatesJsonForSpringReadinessAndStillRejectsHtml()throws Exception {
        String program="""
            import base64,datetime,http.server,json,sys,threading,types,uuid
            h=json.load(sys.stdin);m=types.ModuleType('actual_health_client')
            exec(compile(base64.b64decode(h['source']),'<actual-health-client>','exec'),m.__dict__)
            state={'requests':[],'html':False};identity={'siteId':'server-123456abcdef','instanceId':str(uuid.uuid4()),'clusterId':str(uuid.uuid4())}
            token='x'*43
            class Handler(http.server.BaseHTTPRequestHandler):
                def log_message(self,*args):pass
                def do_POST(self):
                    assert self.path=='/internal/cluster/v1/health-check'
                    body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));assert str(uuid.UUID(body['requestId']))==body['requestId']
                    self.do_GET()
                def do_GET(self):
                    assert self.headers['X-Control-Node-Token']==token
                    accept=self.headers.get('Accept');state['requests'].append((self.path,accept))
                    now=datetime.datetime.now(datetime.timezone.utc);until=now+datetime.timedelta(seconds=60)
                    kinds={'application':'APPLICATION_ACCEPTING_TRAFFIC','database':'POSTGRES_SELECT_1','redis':'REDIS_PING','storage':'S3_HEAD_BUCKET_AND_EMPTY_LIST'}
                    control={**identity,'mode':'EXTERNAL','readiness':'READY','storageReachability':'REACHABLE','healthChecks':{k:{'status':'PASS','code':'OK','running':False,'checkKind':kind,'checkedAt':now.isoformat(),'validUntil':until.isoformat()} for k,kind in kinds.items()}}
                    body={'status':'UP'} if self.path=='/actuator/health/readiness' else {**identity,'control':control}
                    content='application/json'
                    if self.path=='/actuator/health/readiness':content='text/html' if state['html'] else ('application/json' if accept=='application/json' else 'application/vnd.spring-boot.actuator.v3+json')
                    raw=json.dumps(body).encode();self.send_response(200);self.send_header('Content-Type',content);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
            server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            original={**identity,'runtime':{'nodeToken':token}};proof={'bootstrapId':str(uuid.uuid4())}
            try:
                assert m.health_joined(original,server.server_port,proof)
                assert any(path=='/actuator/health/readiness' for path,_ in state['requests'])
                assert all(accept=='application/json' for _,accept in state['requests'])
                state['html']=True
                try:m.health_joined(original,server.server_port,proof)
                except m.Rejected:pass
                else:raise AssertionError('HTML readiness unexpectedly accepted')
                print(json.dumps({'explicitJsonNegotiation':True,'standardSpringJsonSupported':True,'htmlStillRejected':True}))
            finally:server.shutdown();server.server_close()
            """;
        var p=new ProcessBuilder("/usr/bin/python3","-I","-c",program).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var json=JsonMapper.builder().build();try(var out=p.getOutputStream()){
            out.write(json.writeValueAsBytes(Map.of("source",Base64.getEncoder().encodeToString(ControlServers.resource("member-configure.py").getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        }
        assertTrue(p.waitFor(30,TimeUnit.SECONDS));assertEquals(0,p.exitValue());var result=json.readValue(p.getInputStream().readAllBytes(),Map.class);
        assertEquals(3,result.size());assertTrue(result.values().stream().allMatch(Boolean.TRUE::equals));
    }
}
