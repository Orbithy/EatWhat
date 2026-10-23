package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import work.foofish.smsverify.SmsTemplate;
import you.v50to.eatwhat.EatwhatApplication;
import you.v50to.eatwhat.control.*;
import you.v50to.eatwhat.config.SiteProperties;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(classes=EatwhatApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.location=classpath:/application-integration.properties","spring.config.additional-location=","spring.config.import="})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ControlServersIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("servers-web"),REDIS=IsolatedContainers.redis("servers-web");static final JsonMapper JSON=JsonMapper.builder().build();static EventFoundationTestSupport.Database db;static Path directory;
    @Value("${local.server.port}")int port;@MockitoBean JavaMailSender mail;@MockitoBean SmsTemplate sms;@MockitoBean ControlSsh ssh;
    @Autowired ControlBootstrapJournal bootstrapJournal;
    final HttpClient http=HttpClient.newHttpClient();String token;String user;UUID id;String hostKey;
    @DynamicPropertySource static void config(DynamicPropertyRegistry r)throws Exception{
        PG.start();REDIS.start();var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("campus");site.setInstanceId(UUID.randomUUID());db=EventFoundationTestSupport.createDatabase(PG,site);directory=Files.createTempDirectory("eatwhat-servers-it-");
        Path key=directory.resolve("fixture.key");Files.writeString(key,"-----BEGIN OPENSSH PRIVATE KEY-----\nsynthetic-server-reference-only\n-----END OPENSSH PRIVATE KEY-----\n");Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Path inventory=directory.resolve("credentials.json");Files.writeString(inventory,JSON.writeValueAsString(Map.of("version",1,"credentials",Map.of("fixture-key",Map.of("label","Synthetic operator key","authentication","PRIVATE_KEY","file",key.toRealPath().toString())))));Files.setPosixFilePermissions(inventory,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));r.add("app.control.servers.credentials-file",()->{try{return inventory.toRealPath().toString();}catch(Exception e){throw new IllegalStateException(e);}});
        r.add("spring.datasource.url",db::url);r.add("spring.datasource.username",db::runtimeUsername);r.add("spring.datasource.password",()->IsolatedContainers.PASSWORD);r.add("spring.flyway.url",db::url);r.add("spring.flyway.user",()->IsolatedContainers.USER);r.add("spring.flyway.password",()->IsolatedContainers.PASSWORD);r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));r.add("app.site.mode",()->"CAMPUS");r.add("app.site.id",site::getId);r.add("app.site.instance-id",()->site.getInstanceId().toString());r.add("app.storage.s3.provider",()->"RUSTFS");r.add("app.storage.s3.endpoint",()->"http://127.0.0.1:1");r.add("app.storage.s3.cdn-endpoint",()->"");r.add("app.control.enabled",()->true);r.add("app.control.environment-kind",()->"managed-deployment");r.add("app.control.state-directory",()->directory.resolve("state").toString());r.add("app.cluster.enabled",()->false);r.add("app.account.enabled",()->false);
    }
    @BeforeEach void login()throws Exception{
        user="serveradmin_"+UUID.randomUUID().toString().substring(0,8);db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'admin')",user,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("synthetic-password"));token=login(user);id=UUID.randomUUID();byte[] type="ssh-ed25519".getBytes();hostKey="ssh-ed25519 "+Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(4+type.length+4+32).putInt(type.length).put(type).putInt(32).put(new byte[32]).array());
    }
    String login(String name)throws Exception{var r=request("POST","/auth/login",Map.of("username",name,"password","synthetic-password","device","WEB"),null);assertEquals(200,r.statusCode(),r.body());var b=JSON.readTree(r.body());assertEquals(0,b.path("code").asInt(-1),r.body());return b.path("data").path("tokenValue").asText();}
    HttpResponse<String> request(String method,String path,Object input,String auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(input)));if(auth!=null)b.header("eatwhat-test-token",auth.startsWith("Bearer ")?auth:"Bearer "+auth);return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
    Map<String,Object> body(){return new LinkedHashMap<>(Map.of("requestId",id.toString(),"name","fixture-"+id.toString().substring(0,8),"host","fixture-"+id.toString().substring(0,8)+".example.invalid","port",22,"username","root","location","REMOTE","authentication","PASSWORD","credential","synthetic-private-ssh-password","hostKey",hostKey,"confirmedFingerprint",ControlSsh.key(hostKey).get("fingerprint")));}
    @Test void serverInventoryAndEveryWriteRequireAdministrator()throws Exception{
        assertEquals(401,request("GET","/admin/control/v1/servers",null,null).statusCode());assertEquals(401,request("POST","/admin/control/v1/servers",body(),null).statusCode());String ordinary="viewer_"+id.toString().substring(0,8);db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'user')",ordinary,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("synthetic-password"));String t=login(ordinary);assertEquals(403,request("GET","/admin/control/v1/servers",null,t).statusCode());assertEquals(403,request("POST","/admin/control/v1/servers",body(),t).statusCode());assertEquals(401,request("GET","/admin/control/v1/tasks",null,null).statusCode());assertEquals(403,request("GET","/admin/control/v1/tasks",null,t).statusCode());var history=request("GET","/admin/control/v1/tasks",null,token);assertEquals(200,history.statusCode(),history.body());assertTrue(history.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }
    @Test void realHttpCrudUsesCasAndNeverEchoesSshSecrets()throws Exception{
        var added=request("POST","/admin/control/v1/servers",body(),token);assertEquals(201,added.statusCode(),added.body());assertFalse(added.body().contains("synthetic-private-ssh-password"));assertTrue(added.headers().firstValue("Cache-Control").orElse("").contains("no-store"));var list=request("GET","/admin/control/v1/servers",null,token);assertEquals(200,list.statusCode());assertFalse(list.body().contains("synthetic-private-ssh-password"));assertFalse(Files.readString(directory.resolve("state/servers.json")).contains("synthetic-private-ssh-password"));var edit=body();edit.put("requestId",UUID.randomUUID().toString());edit.put("expectedRevision",1);edit.put("credential","");assertEquals(200,request("PUT","/admin/control/v1/servers/"+id,edit,token).statusCode());edit.put("requestId",UUID.randomUUID().toString());assertEquals(409,request("PUT","/admin/control/v1/servers/"+id,edit,token).statusCode());assertEquals(200,request("DELETE","/admin/control/v1/servers/"+id+"?expectedRevision=2",null,token).statusCode());
    }
    @Test void normalAdminHttpCanSelectOperatorKeyWithoutReceivingItsPathOrContents()throws Exception{
        var list=request("GET","/admin/control/v1/servers",null,token);assertEquals(200,list.statusCode());var choices=JSON.readTree(list.body()).path("sshCredentials");assertEquals(1,choices.size());assertEquals("fixture-key",choices.path(0).path("id").asText());assertFalse(list.body().contains("fixture.key"));assertFalse(list.body().contains("synthetic-server-reference-only"));
        var b=body();b.remove("credential");b.put("authentication","PRIVATE_KEY");b.put("credentialId","fixture-key");b.put("privilege","SUDO");
        assertEquals(401,request("POST","/admin/control/v1/servers",b,null).statusCode());var added=request("POST","/admin/control/v1/servers",b,token);assertEquals(201,added.statusCode(),added.body());assertEquals("OPERATOR_INVENTORY",JSON.readTree(added.body()).path("credentialSource").asText());assertFalse(added.body().contains("synthetic-server-reference-only"));assertFalse(Files.readString(directory.resolve("state/servers.json")).contains("synthetic-server-reference-only"));
        var bad=new LinkedHashMap<>(b);bad.put("credential","ambiguous");bad.put("requestId",UUID.randomUUID().toString());assertEquals(400,request("POST","/admin/control/v1/servers",bad,token).statusCode());
        var edit=new LinkedHashMap<>(b);edit.remove("credentialId");edit.put("expectedRevision",1);edit.put("requestId",UUID.randomUUID().toString());var saved=request("PUT","/admin/control/v1/servers/"+id,edit,token);assertEquals(200,saved.statusCode(),saved.body());assertEquals("fixture-key",JSON.readTree(saved.body()).path("credentialId").asText());
        edit.put("expectedRevision",2);edit.put("requestId",UUID.randomUUID().toString());edit.put("credentialId","/etc/passwd");assertEquals(400,request("PUT","/admin/control/v1/servers/"+id,edit,token).statusCode());
        assertEquals(200,request("DELETE","/admin/control/v1/servers/"+id+"?expectedRevision=2",null,token).statusCode());
    }
    @Test void sameOriginAndHostTrustAreRequired()throws Exception{
        var wrong=body();wrong.put("confirmedFingerprint","SHA256:wrong");assertEquals(400,request("POST","/admin/control/v1/servers",wrong,token).statusCode());var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/admin/control/v1/servers")).header("Content-Type","application/json").header("Origin","https://unrelated.example.invalid").header("eatwhat-test-token",token.startsWith("Bearer ")?token:"Bearer "+token).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body())));assertEquals(403,http.send(b.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void failedProbeReportsUnknownEnvironmentWithoutLeakingRemoteOutput()throws Exception{
        assertEquals(201,request("POST","/admin/control/v1/servers",body(),token).statusCode());when(ssh.call(anyMap(),anyString(),anyList(),any(),anyInt())).thenThrow(new java.io.IOException("private remote output synthetic-secret"));var r=request("POST","/admin/control/v1/servers/"+id+"/test",Map.of("expectedRevision",1),token);assertEquals(200,r.statusCode(),r.body());assertEquals("FAILED",JSON.readTree(r.body()).path("connection").path("state").asText());assertFalse(r.body().contains("synthetic-secret"));
    }
    @Test void localModeCannotPointAtSomeOtherHostAndNoCommandFieldIsUsed()throws Exception{
        var b=body();b.put("location","CONTROL_HOST");assertEquals(400,request("POST","/admin/control/v1/servers",b,token).statusCode());b=body();b.put("host","-oProxyCommand=id");assertEquals(400,request("POST","/admin/control/v1/servers",b,token).statusCode());
    }
    @Test void clusterEnrollmentEndpointsRequireAdminAndCannotBootstrapUninstalledRemoteServer()throws Exception {
        String plan=UUID.randomUUID().toString();
        assertEquals(401,request("POST","/admin/control/v1/servers/"+id+"/cluster-plans",Map.of("expectedRevision",1),null).statusCode());
        for(String p:List.of("/server-cluster-plans/"+plan,"/server-cluster-jobs/"+plan))assertEquals(401,request("GET","/admin/control/v1"+p,null,null).statusCode());
        for(String p:List.of("/server-cluster-plans/"+plan+"/execute","/server-cluster-jobs/"+plan+"/reconcile","/server-cluster-jobs/"+plan+"/recovery-plans"))assertEquals(401,request("POST","/admin/control/v1"+p,null,null).statusCode());
        assertEquals(201,request("POST","/admin/control/v1/servers",body(),token).statusCode());
        var recoveryInput=Map.of("expectedRevision",1,"sourceJobId",plan);String recoveryRoute="/admin/control/v1/servers/"+id+"/recovery-plans";
        assertEquals(401,request("POST",recoveryRoute,recoveryInput,null).statusCode());String viewer="recovery_viewer_"+id.toString().substring(0,8);db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'user')",viewer,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("synthetic-password"));assertEquals(403,request("POST",recoveryRoute,recoveryInput,login(viewer)).statusCode());var recovery=request("POST",recoveryRoute,recoveryInput,token);assertEquals(409,recovery.statusCode());assertTrue(recovery.body().contains("RECOVERY_SOURCE_RECONCILIATION_REQUIRED"));
        var r=request("POST","/admin/control/v1/servers/"+id+"/cluster-plans",Map.of("expectedRevision",1),token);assertEquals(201,r.statusCode(),r.body());assertEquals("BLOCKED",JSON.readTree(r.body()).path("state").asText());assertTrue(r.body().contains("SERVER_NOT_INSTALLED"));assertFalse(r.body().contains("synthetic-private-ssh-password"));
    }
    @Test void manualReleaseRecoveryRoutesRequireAdministratorAndValidExplicitConfirmation()throws Exception{
        UUID job=UUID.randomUUID(),plan=UUID.randomUUID();String create="/admin/control/v1/deployments/"+job+"/recovery-plans",base="/admin/control/v1/deployment-recovery-plans/"+plan;var preview=Map.of("requestId",plan.toString(),"siteId","campus");var confirm=Map.of("restoreSha256","a".repeat(64));
        String viewer="restore_viewer_"+id.toString().substring(0,8);db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'user')",viewer,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("synthetic-password"));String ordinary=login(viewer);
        for(String path:List.of(create,base+"/execute",base+"/reconcile")){Object body=path.equals(create)?preview:path.endsWith("/execute")?confirm:Map.of();var anonymous=request("POST",path,body,null);assertEquals(401,anonymous.statusCode(),anonymous.body());assertTrue(anonymous.headers().firstValue("Cache-Control").orElse("").contains("no-store"));assertEquals(403,request("POST",path,body,ordinary).statusCode());}
        assertEquals(401,request("GET",base,null,null).statusCode());assertEquals(403,request("GET",base,null,ordinary).statusCode());var missing=request("POST",create,preview,token);assertEquals(404,missing.statusCode(),missing.body());assertTrue(missing.body().contains("DEPLOYMENT_NOT_FOUND"));assertEquals(404,request("GET",base,null,token).statusCode());assertEquals(400,request("POST",base+"/execute",Map.of("restoreSha256","wrong"),token).statusCode());assertEquals(400,request("POST",create,Map.of("requestId",plan.toString(),"siteId","../other"),token).statusCode());
    }
    @Test void memberConfigurationRecoveryRequiresAdminAndDoesNotAdoptMissingOrUncertainJob()throws Exception{
        String path="/admin/control/v1/server-cluster-jobs/"+UUID.randomUUID()+"/recovery-plans";
        assertEquals(401,request("POST",path,null,null).statusCode());
        String viewer="member_restore_viewer_"+id.toString().substring(0,8);db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'user')",viewer,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("synthetic-password"));
        assertEquals(403,request("POST",path,null,login(viewer)).statusCode());
        var missing=request("POST",path,null,token);assertEquals(404,missing.statusCode(),missing.body());assertTrue(missing.body().contains("AUTHORITY_JOB_NOT_FOUND"));assertTrue(missing.headers().firstValue("Cache-Control").orElse("").contains("no-store"));verifyNoInteractions(ssh);
    }

    @Test void compositeBootstrapHistoryIsReadOnlyAdministratorOnlyAndNeverReturnsPrivateInputs()throws Exception{
        UUID task=UUID.randomUUID(),source=UUID.randomUUID(),target=UUID.randomUUID();String path="/admin/control/v1/controller-jobs/"+task;
        assertEquals(401,request("GET",path,null,null).statusCode());
        String viewer="bootstrap_viewer_"+task.toString().substring(0,8);db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'user')",viewer,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("synthetic-password"));
        assertEquals(403,request("GET",path,null,login(viewer)).statusCode());assertEquals(404,request("GET",path,null,token).statusCode());
        var spec=new ControlBootstrapJournal.Spec(task,source,1,target,1,UUID.randomUUID(),2,"a".repeat(64),"fixture-business","b".repeat(64),2);
        var review=new LinkedHashMap<String,Object>(Map.of("sourceAddress","10.253.74.1","targetAddress","10.253.74.3","targetEndpoint","fixture.example.invalid","wireguardPort",51820,"httpPort",18090,"appBaseUrl","https://fixture.example.invalid/api"));
        try{
            bootstrapJournal.preview("synthetic-original-actor",spec,review,Map.of("password","synthetic-private-bootstrap-value"),()->{});
            bootstrapJournal.accept("synthetic-original-actor",task,ControlBootstrapJournal.PERMISSIONS,()->{});
            var response=request("GET",path,null,token);assertEquals(200,response.statusCode(),response.body());assertEquals("QUEUED",JSON.readTree(response.body()).path("state").asText());assertFalse(response.body().contains("synthetic-private-bootstrap-value"));assertFalse(response.body().contains("_body"));assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            var operations=JSON.readTree(response.body()).path("steps").get(ControlBootstrapJournal.Phase.DEPENDENCY_LINK.ordinal()).path("operations");
            assertEquals(3,operations.size());assertEquals("KEY",operations.get(0).path("operation").asText());assertEquals("SOURCE",operations.get(1).path("operation").asText());assertEquals("CONNECT",operations.get(2).path("operation").asText());
            assertFalse(response.body().contains("_nativeBinding"));assertFalse(response.body().contains("_receipt"));
            var history=request("GET","/admin/control/v1/tasks?type=CONTROLLER_INSTALL&serverId="+target,null,token);assertEquals(200,history.statusCode(),history.body());assertEquals(task.toString(),JSON.readTree(history.body()).path("items").get(0).path("id").asText());assertFalse(history.body().contains("synthetic-private-bootstrap-value"));verifyNoInteractions(ssh);
        }finally{
            Files.deleteIfExists(directory.resolve("state/controller-job-"+task+".json"));Files.deleteIfExists(directory.resolve("state/controller-plan-"+task+".json"));
        }
    }

}
