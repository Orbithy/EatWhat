package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring admin sessions/controllers/executor and real child process; SSH/nodes are isolated fixtures. */
class DeploymentPlaneIT extends LocalBusinessFileFixture {
    static final Path PRIVATE,ARTIFACT,SSH,KEY,KNOWN,TOKEN;
    static final String SHA,OLD="b".repeat(64),NODE_TOKEN="deployment-fixture-"+"x".repeat(43);
    static final Map<String,UUID> IDS=Map.of("campus",UUID.randomUUID(),"external",UUID.randomUUID());
    static final Map<String,HttpServer> SERVERS=new LinkedHashMap<>();
    static {
        try{
            PRIVATE=Files.createTempDirectory("eatwhat-deployment-it-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            ARTIFACT=PRIVATE.resolve("candidate.jar");Files.writeString(ARTIFACT,"isolated deployment candidate");SHA=you.v50to.eatwhat.control.DeploymentExecutor.artifactSha(ARTIFACT);
            KEY=PRIVATE.resolve("key");TOKEN=PRIVATE.resolve("token");KNOWN=PRIVATE.resolve("known_hosts");SSH=PRIVATE.resolve("ssh-fixture");
            Files.writeString(KEY,"synthetic deployment key");Files.writeString(TOKEN,NODE_TOKEN);Files.writeString(KNOWN,"synthetic known host");
            Files.setPosixFilePermissions(KEY,PosixFilePermissions.fromString("rw-------"));Files.setPosixFilePermissions(TOKEN,PosixFilePermissions.fromString("rw-------"));
            Files.writeString(SSH,"""
                #!/usr/bin/python3
                import sys,json
                _,agent,config,action,j,sha,site,instance,old=sys.argv[-1].split()
                data=sys.stdin.buffer.read()
                assert action!='apply' or data==b'isolated deployment candidate'
                print(json.dumps(dict(id=j,siteId=site,instanceId=instance,artifactSha256=sha,state='PREPARED' if action=='prepare' else 'SUCCEEDED')))
                """);Files.setPosixFilePermissions(SSH,PosixFilePermissions.fromString("rwx------"));
            for(String key:List.of("campus","external")){
                var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);SERVERS.put(key,server);
                server.createContext("/internal/control/v1/snapshot",e->{
                    if(!NODE_TOKEN.equals(e.getRequestHeaders().getFirst("X-Control-Node-Token"))){e.sendResponseHeaders(401,-1);e.close();return;}
                    byte[] body=JSON.writeValueAsBytes(Map.of("siteId",key,"instanceId",IDS.get(key).toString(),"mode",key.equals("campus")?"CAMPUS":"EXTERNAL","observedAt",Instant.now().toString(),"observationStatus","OBSERVED","database",Map.of("databaseFingerprint",(key.equals("campus")?"a":"b").repeat(32))));
                    e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
                });server.start();
            }
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void deploymentProperties(DynamicPropertyRegistry r){
        r.add("app.control.enabled",()->true);r.add("app.control.environment-kind",()->"managed-deployment");r.add("app.control.state-directory",()->PRIVATE.resolve("state").toString());
        r.add("app.control.deployment.enabled",()->true);r.add("app.control.deployment.ssh-executable",()->SSH.toString());r.add("app.control.deployment.known-hosts-file",()->KNOWN.toString());r.add("app.control.deployment.identity-file",()->KEY.toString());
        r.add("app.control.releases.candidate.artifact",()->ARTIFACT.toString());r.add("app.control.releases.candidate.sha256",()->SHA);r.add("app.control.releases.candidate.acceptance-evidence-id",()->"isolated-reviewed");r.add("app.control.releases.candidate.rollback-compatible",()->true);r.add("app.control.releases.candidate.compatible-from-sha256[0]",()->OLD);
        for(String key:List.of("campus","external")){
            String n="app.control.nodes."+key+".",d="app.control.deployment.targets."+key+".";
            r.add(n+"origin",()->"http://127.0.0.1:"+SERVERS.get(key).getAddress().getPort());r.add(n+"site-id",()->key);r.add(n+"instance-id",()->IDS.get(key).toString());r.add(n+"token-file",()->TOKEN.toString());
            r.add(d+"host",()->key+".invalid");r.add(d+"user",()->"deploy");r.add(d+"site-id",()->key);r.add(d+"instance-id",()->IDS.get(key).toString());r.add(d+"current-sha256",()->OLD);r.add(d+"restore-evidence-id",()->"isolated-restore-reviewed");
        }
    }
    HttpResponse<String> get(String path,String token)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base()+path));if(token!=null)request.header("eatwhat-test-token","Bearer "+token);
        return http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void actualAdminHttpCreatesRunsPollsAndRepeatsOneDurableDeployment()throws Exception{
        var admin=account(true);makeAdmin(admin);var body=Map.of("releaseId","candidate","sites",List.of("external","campus"));
        var created=response("POST","/admin/control/v1/deployment-plans",body,admin.token(),null);assertEquals(201,created.statusCode(),created.body());
        var plan=JSON.readTree(created.body());assertTrue(plan.path("executionEnabled").asBoolean());String id=plan.path("id").asText();
        var accepted=response("POST","/admin/control/v1/deployment-plans/"+id+"/execute",null,admin.token(),null);assertEquals(202,accepted.statusCode(),accepted.body());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);String state="";
        do{var read=get("/admin/control/v1/deployments/"+id,admin.token());assertEquals(200,read.statusCode());state=JSON.readTree(read.body()).path("state").asText();if(state.equals("SUCCEEDED"))break;Thread.sleep(20);}while(System.nanoTime()<deadline);
        assertEquals("SUCCEEDED",state);var repeat=response("POST","/admin/control/v1/deployment-plans/"+id+"/execute",null,admin.token(),null);assertEquals(202,repeat.statusCode());assertEquals("SUCCEEDED",JSON.readTree(repeat.body()).path("state").asText());
        var reconcile=response("POST","/admin/control/v1/deployments/"+id+"/reconcile",null,admin.token(),null);assertEquals(200,reconcile.statusCode());
        assertFalse(accepted.body().contains(KEY.toString()));assertFalse(accepted.body().contains(NODE_TOKEN));
        assertEquals("no-store",accepted.headers().firstValue("Cache-Control").orElse(""));
    }
    @Test void managedEnvironmentRetainsRbacAndRejectsFaultInjection()throws Exception{
        String id=UUID.randomUUID().toString();assertEquals(401,get("/admin/control/v1/deployments/"+id,null).statusCode());
        var user=account(true);assertEquals(403,response("POST","/admin/control/v1/deployment-plans/"+id+"/execute",null,user.token(),null).statusCode());
        assertEquals(403,response("POST","/admin/control/v1/deployments/"+id+"/reconcile",null,user.token(),null).statusCode());
        makeAdmin(user);assertEquals(403,response("POST","/admin/control/v1/sites/campus/faults",Map.of("channel","events","action","BLOCK","seconds",1),user.token(),null).statusCode());
        var suites=JSON.readTree(get("/admin/control/v1/test-suites",user.token()).body());for(var suite:suites)assertFalse(suite.path("enabled").asBoolean());
    }
    @AfterAll static void cleanupDeploymentFixture(){SERVERS.values().forEach(s->s.stop(0));}
}
