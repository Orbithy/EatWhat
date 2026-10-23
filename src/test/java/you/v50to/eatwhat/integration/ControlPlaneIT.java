package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Spring auth/controller + isolated V35 PostGIS runtime role, not a fake status server. */
class ControlPlaneIT extends LocalBusinessFileFixture {
    static final String NODE_TOKEN="synthetic-control-node-"+"x".repeat(44);
    static final Path STATE,TOKEN,ARTIFACT;
    static final String ARTIFACT_SHA;
    static final int PROXY_PORT;
    static {
        try{STATE=Files.createTempDirectory("eatwhat-control-it-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));TOKEN=STATE.resolve("node-token");Files.writeString(TOKEN,NODE_TOKEN);Files.setPosixFilePermissions(TOKEN,PosixFilePermissions.fromString("rw-------"));
            ARTIFACT=STATE.resolve("synthetic-candidate.jar");Files.writeString(ARTIFACT,"synthetic preview-only artifact");ARTIFACT_SHA=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(ARTIFACT)));
            try(var free=new java.net.ServerSocket(0)){PROXY_PORT=free.getLocalPort();}}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void controlProperties(DynamicPropertyRegistry r){r.add("app.control.enabled",()->true);r.add("app.control.environment-kind",()->"isolated-acceptance");r.add("app.control.state-directory",()->STATE.toString());r.add("app.control.node-enabled",()->true);r.add("app.control.node-token-file",()->TOKEN.toString());
        r.add("app.control.releases.synthetic.artifact",()->ARTIFACT.toString());r.add("app.control.releases.synthetic.sha256",()->ARTIFACT_SHA);
        r.add("app.control.proxies.events.listen-port",()->PROXY_PORT);r.add("app.control.proxies.events.target-host",()->"127.0.0.1");r.add("app.control.proxies.events.target-port",()->1);r.add("app.control.proxies.events.maximum-block-seconds",()->2);}
    HttpResponse<String> request(String path,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base()+path)).GET();if(token!=null)b.header("eatwhat-test-token","Bearer "+token);
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void actualHttpAdminAuthorizationAndUnknownPairAreExplicit()throws Exception {
        assertEquals(401,request("/admin/control/v1/sites",null).statusCode());
        var user=account(true);assertEquals(403,request("/admin/control/v1/sites",user.token()).statusCode());makeAdmin(user);
        var response=request("/admin/control/v1/sites",user.token());assertEquals(200,response.statusCode());assertEquals("UNVERIFIED",JSON.readTree(response.body()).path("pairStatus").asText());
        assertFalse(JSON.readTree(request("/admin/control/v1/capabilities",user.token()).body()).path("deploymentExecutionEnabled").asBoolean());
    }
    @Test void actualNodeSqlChecksV35RuntimeRoleAndDoesNotLeakKeysOrTokens()throws Exception {
        assertEquals(401,request("/internal/control/v1/snapshot",null).statusCode());
        var result=http.send(HttpRequest.newBuilder(URI.create(base()+"/internal/control/v1/snapshot")).header("X-Control-Node-Token",NODE_TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,result.statusCode());var s=JSON.readTree(result.body());assertEquals("OBSERVED",s.path("observationStatus").asText(),result.body());
        assertTrue(s.path("database").path("v35").asBoolean());assertTrue(s.path("database").path("restrictedRole").asBoolean());
        assertEquals("NOT_CHECKED",s.path("storageReachability").asText());assertFalse(result.body().contains(NODE_TOKEN));assertFalse(result.body().contains("object_key"));
    }
    @Test void unavailableSuitesAndMalformedInputsCannotStartProcess()throws Exception {
        var admin=account(true);makeAdmin(admin);
        var denied=http.send(HttpRequest.newBuilder(URI.create(base()+"/admin/control/v1/test-runs")).header("eatwhat-test-token","Bearer "+admin.token()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"suite\":\"business\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(409,denied.statusCode());assertEquals("TEST_RUNNER_NOT_CONFIGURED",JSON.readTree(denied.body()).path("code").asText());
        assertEquals(400,request("/admin/control/v1/test-runs/not-a-uuid",admin.token()).statusCode());
    }
    @Test void deploymentPreviewIsAuthenticatedAuditedAndLockedThroughActualHttp()throws Exception {
        var admin=account(true);makeAdmin(admin);String body="{\"releaseId\":\"synthetic\",\"sites\":[\"campus\",\"external\"]}";
        assertEquals(401,response("POST","/admin/control/v1/deployment-plans",JSON.readTree(body),null,null).statusCode());
        var created=response("POST","/admin/control/v1/deployment-plans",JSON.readTree(body),admin.token(),null);assertEquals(201,created.statusCode());
        var plan=JSON.readTree(created.body());assertEquals("PREVIEW_ONLY",plan.path("state").asText());assertFalse(plan.path("executionEnabled").asBoolean());assertEquals(ARTIFACT_SHA,plan.path("artifactSha256").asText());
        assertEquals("no-store",created.headers().firstValue("Cache-Control").orElse(""));
        var blocked=response("POST","/admin/control/v1/deployment-plans/"+plan.path("id").asText()+"/execute",null,admin.token(),null);assertEquals(423,blocked.statusCode());
        var audit=request("/admin/control/v1/audit",admin.token());assertTrue(audit.body().contains("REJECTED_LOCKED"));assertFalse(audit.body().contains(NODE_TOKEN));assertFalse(created.body().contains(ARTIFACT.toString()));
    }
    @Test void nodeFaultRequiresMachineTokenAndUsesRealIdentity()throws Exception {
        var body=Map.of("requestId",UUID.randomUUID().toString(),"channel","events","action","BLOCK","seconds",1);
        String payload=JSON.writeValueAsString(body);var uri=URI.create(base()+"/internal/control/v1/faults");
        assertEquals(401,http.send(HttpRequest.newBuilder(uri).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var result=http.send(HttpRequest.newBuilder(uri).header("X-Control-Node-Token",NODE_TOKEN).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,result.statusCode());assertEquals("BLOCKED",JSON.readTree(result.body()).path("state").asText());
        try(var socket=new java.net.Socket("127.0.0.1",PROXY_PORT)){socket.setSoTimeout(2000);assertEquals(-1,socket.getInputStream().read());}
        payload=JSON.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"channel","events","action","RESTORE","seconds",1));
        var restored=http.send(HttpRequest.newBuilder(uri).header("X-Control-Node-Token",NODE_TOKEN).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,restored.statusCode());assertEquals("FORWARDING",JSON.readTree(restored.body()).path("state").asText());
    }
}
