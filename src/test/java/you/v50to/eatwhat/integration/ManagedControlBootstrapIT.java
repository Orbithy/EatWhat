package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import work.foofish.smsverify.SmsTemplate;
import you.v50to.eatwhat.EatwhatApplication;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A fresh management process must start without opting real data into synthetic account synchronization. */
@SpringBootTest(classes=EatwhatApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.config.location=classpath:/application-integration.properties",
                "spring.config.additional-location=", "spring.config.import="})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ManagedControlBootstrapIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("managed-bootstrap"), REDIS=IsolatedContainers.redis("managed-bootstrap");
    static final JsonMapper JSON=JsonMapper.builder().build();
    static EventFoundationTestSupport.Database db;
    static Path directory;
    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;
    @MockitoBean JavaMailSender mail;
    @MockitoBean SmsTemplate sms;
    final HttpClient http=HttpClient.newHttpClient();

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r)throws Exception {
        PG.start(); REDIS.start();
        var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("campus");site.setInstanceId(UUID.randomUUID());site.setClusterId(UUID.randomUUID());site.setAllocationSlot(0);
        db=EventFoundationTestSupport.createDatabase(PG,site);
        directory=Files.createTempDirectory("eatwhat-managed-bootstrap-");
        Path ui=directory.resolve("approved-ui");Files.createDirectory(ui);
        Files.writeString(ui.resolve("index.html"),"<!doctype html><title>synthetic approved management UI</title>");
        Files.writeString(ui.resolve("api.js"),"export const syntheticManagedUi = true;\n");
        Path token=directory.resolve("node.token");Files.writeString(token,"synthetic-bootstrap-node-"+"x".repeat(48));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
        r.add("spring.datasource.url",db::url);r.add("spring.datasource.username",db::runtimeUsername);r.add("spring.datasource.password",()->IsolatedContainers.PASSWORD);
        r.add("spring.flyway.url",db::url);r.add("spring.flyway.user",()->IsolatedContainers.USER);r.add("spring.flyway.password",()->IsolatedContainers.PASSWORD);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("app.site.mode",()->"CAMPUS");r.add("app.site.id",site::getId);r.add("app.site.instance-id",()->site.getInstanceId().toString());r.add("app.site.cluster-id",()->site.getClusterId().toString());r.add("app.site.allocation-slot",()->0);
        r.add("app.storage.s3.provider",()->"RUSTFS");r.add("app.storage.s3.endpoint",()->"http://127.0.0.1:1");r.add("app.storage.s3.cdn-endpoint",()->"");
        r.add("app.storage.files.replication.enabled",()->false);r.add("app.storage.files.replication.worker-enabled",()->false);
        r.add("app.account.enabled",()->false);r.add("app.sync.transport-enabled",()->false);
        r.add("app.control.enabled",()->true);r.add("app.control.environment-kind",()->"managed-deployment");r.add("app.control.state-directory",()->directory.resolve("state").toString());r.add("app.control.node-enabled",()->true);r.add("app.control.node-token-file",token::toString);
        r.add("app.control.ui-enabled",()->true);r.add("spring.web.resources.static-locations",()->ui.toUri().toString());r.add("spring.mvc.static-path-pattern",()->"/control-ui/**");
        r.add("app.cluster.enabled",()->true);r.add("app.cluster.coordinator",()->true);r.add("app.cluster.control-lease.enabled",()->true);r.add("app.cluster.automatic-gc-enabled",()->false);
        r.add("management.endpoint.health.probes.enabled",()->true);r.add("management.endpoint.health.group.readiness.include",()->"readinessState,db,redis");
    }

    @Test void loginAndManagementWorkWhileBusinessAndSynchronizationRemainUnavailable()throws Exception {
        assertTrue(context.getBeansOfType(ConsentStore.class).isEmpty());
        assertTrue(context.getBeansOfType(ClusterAccountController.class).isEmpty());
        assertTrue(context.getBeansOfType(ClusterConsentController.class).isEmpty());
        assertTrue(context.getBeansOfType(ClusterMeshSynchronizer.class).isEmpty());
        assertTrue(context.getBeansOfType(ClusterFileController.class).isEmpty());
        assertTrue(context.getBeansOfType(ClusterFileTransfer.class).isEmpty());
        assertEquals(200,request("GET","/actuator/health/readiness",null,null).statusCode());
        assertEquals(200,request("GET","/actuator/health/liveness",null,null).statusCode());
        assertEquals(401,request("GET","/admin/control/v1/cluster/capabilities",null,null).statusCode());
        db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES('bootadmin',?,'admin')",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("synthetic-bootstrap-password"));
        var login=request("POST","/auth/login",Map.of("username","bootadmin","password","synthetic-bootstrap-password","device","WEB"),null);
        assertEquals(200,login.statusCode(),login.body());var body=JSON.readTree(login.body());assertEquals(0,body.path("code").asInt(-1),login.body());String token=body.path("data").path("tokenValue").asText();assertFalse(token.isBlank());
        var capabilities=request("GET","/admin/control/v1/cluster/capabilities",null,token);assertEquals(200,capabilities.statusCode(),capabilities.body());assertFalse(JSON.readTree(capabilities.body()).path("deploymentExecutionEnabled").asBoolean());
        var status=request("GET","/admin/control/v1/cluster",null,token);assertEquals(200,status.statusCode(),status.body());assertEquals(0,JSON.readTree(status.body()).path("members").size());
        String path="/admin/control/v1/cluster/leadership/handoffs";
        var leader=JSON.readTree(request("GET","/admin/control/v1/cluster/leadership",null,token).body());assertTrue(leader.path("handoffEnabled").asBoolean());
        var change=Map.of("requestId",UUID.randomUUID(),"expectedGeneration",leader.path("generation").asLong(),"targetControllerId",UUID.randomUUID());
        assertEquals(401,request("POST",path,change,null).statusCode());
        db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES('bootuser',?,'user')",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("synthetic-bootstrap-password"));
        var ordinary=request("POST","/auth/login",Map.of("username","bootuser","password","synthetic-bootstrap-password","device","WEB"),null);String ordinaryToken=JSON.readTree(ordinary.body()).path("data").path("tokenValue").asText();
        assertEquals(403,request("POST",path,change,ordinaryToken).statusCode());assertEquals(403,request("POST",path+"/"+UUID.randomUUID()+"/cancel",null,ordinaryToken).statusCode());
        var offline=request("POST",path,change,token);assertEquals(409,offline.statusCode(),offline.body());assertEquals("CONTROL_TARGET_OFFLINE",JSON.readTree(offline.body()).path("code").asText());
        var denied=request("POST","/auth/register",Map.of("username","freshuser","password","synthetic-bootstrap-password","device","WEB"),null);
        assertEquals(423,denied.statusCode(),denied.body());assertEquals("SITE_JOIN_PENDING",JSON.readTree(denied.body()).path("code").asText());
        assertEquals(0,db.runtime().queryForObject("SELECT count(*) FROM users WHERE nick_name='freshuser'",Integer.class));
    }

    @Test void optionalUiEntryServesApprovedStaticBytesAndKeepsManagementProtected()throws Exception {
        var redirect=request("GET","/control-ui/",null,null);assertEquals(302,redirect.statusCode());
        assertEquals("/control-ui/index.html",redirect.headers().firstValue("location").orElseThrow());
        for(String name:List.of("index.html","api.js")){
            var response=request("GET","/control-ui/"+name,null,null);assertEquals(200,response.statusCode(),response.body());
            assertEquals(Files.readString(directory.resolve("approved-ui").resolve(name)),response.body());
        }
        assertEquals(401,request("GET","/admin/control/v1/cluster",null,null).statusCode());
        assertEquals(401,request("GET","/admin/control/v1/account",null,null).statusCode());
        assertEquals(200,request("HEAD","/control-ui/api.js",null,null).statusCode());
        assertEquals(423,request("POST","/control-ui/api.js",Map.of("fixture",true),null).statusCode());
        assertEquals(423,request("GET","/control-ui-unsafe/business",null,null).statusCode());
    }

    HttpResponse<String> request(String method,String path,Object body,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(token!=null)b.header("eatwhat-test-token","Bearer "+token);
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll static void cleanup(@Autowired LettuceConnectionFactory redis,@Autowired ClusterRuntime runtime)throws Exception {
        runtime.stop();redis.destroy();REDIS.stop();PG.stop();
        if(directory!=null)try(var paths=Files.walk(directory)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
}
