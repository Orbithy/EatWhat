package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import work.foofish.smsverify.SmsTemplate;
import you.v50to.eatwhat.EatwhatApplication;
import you.v50to.eatwhat.cluster.ClusterControlLease;
import you.v50to.eatwhat.config.SiteProperties;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP login/readiness with only this controller's PostgreSQL/Redis.
 * No campus endpoint, shared Redis, NFS mount, or shared-database session exists. */
@SpringBootTest(classes=EatwhatApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"spring.config.location=classpath:/application-integration.properties","spring.config.additional-location=","spring.config.import="})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class IndependentControlLocalIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("independent-local"),REDIS=IsolatedContainers.redis("independent-local");
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final UUID CLUSTER=UUID.randomUUID(),LOCAL=UUID.randomUUID(),CAMPUS=UUID.randomUUID(),INSTALL=UUID.randomUUID(),HOLDER=UUID.randomUUID(),PEER=UUID.randomUUID(),STATE=UUID.randomUUID();
    static EventFoundationTestSupport.Database db;
    static Path directory;
    @Value("${local.server.port}")int port;
    @Autowired ApplicationContext context;
    @MockitoBean JavaMailSender mail;
    @MockitoBean SmsTemplate sms;
    HttpClient http=HttpClient.newHttpClient();
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r)throws Exception{
        PG.start();REDIS.start();var site=new SiteProperties();site.setMode(SiteProperties.Mode.EXTERNAL);site.setId("singapore");site.setInstanceId(LOCAL);site.setClusterId(CLUSTER);site.setAllocationSlot(1);
        db=EventFoundationTestSupport.createDatabase(PG,site);
        Flyway.configure().dataSource(db.admin().getDataSource()).locations("classpath:db/control-independent").schemas("eatwhat_controller").table("independent_schema_history").load().migrate();
        db.admin().update("INSERT INTO eatwhat_cluster.state(singleton,cluster_id,coordinator_site,coordinator_instance,epoch)VALUES(true,?,'campus',?,1)",CLUSTER,CAMPUS);
        db.admin().update("INSERT INTO eatwhat_cluster.control_lease(singleton,cluster_id,holder_id,generation,expires_at)VALUES(true,?,?,7,clock_timestamp()+interval '5 minutes')",CLUSTER,PEER);
        db.admin().update("INSERT INTO eatwhat_cluster.control_process(holder_id,cluster_id,name,state_id,lease_seconds,expires_at,deployment_protocol)VALUES(?,?,'campus-control',?,15,clock_timestamp()+interval '5 minutes',1)",PEER,CLUSTER,STATE);
        db.admin().update("""
            INSERT INTO eatwhat_controller.installation(singleton,installation_id,controller_id,cluster_id,source_site,source_instance,source_database_fingerprint,local_database_fingerprint)
            SELECT true,?,?,?,'singapore',?,md5(cluster_identifier||':'||database_oid::text),eatwhat_controller.physical_fingerprint()FROM eatwhat_identity.node_state WHERE singleton
            """,INSTALL,HOLDER,CLUSTER,LOCAL);
        String role="\""+db.runtimeUsername()+"\"";
        for(String schema:List.of("eatwhat_cluster","eatwhat_controller")){
            db.admin().execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
            db.admin().execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA "+schema+" TO "+role);
            db.admin().execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA "+schema+" TO "+role);
        }
        db.admin().execute("GRANT EXECUTE ON FUNCTION eatwhat_controller.physical_fingerprint() TO "+role);
        db.admin().execute("REVOKE INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public FROM "+role);
        directory=Files.createTempDirectory("eatwhat-independent-local-");
        Path state=directory.resolve("state");Files.createDirectory(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(state.resolve("controller-state-id"),STATE.toString());Files.setPosixFilePermissions(state.resolve("controller-state-id"),PosixFilePermissions.fromString("rw-------"));
        Path token=directory.resolve("node.token");Files.writeString(token,"synthetic-control-"+"x".repeat(48));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
        r.add("spring.datasource.url",db::url);r.add("spring.datasource.username",db::runtimeUsername);r.add("spring.datasource.password",()->IsolatedContainers.PASSWORD);
        r.add("spring.flyway.url",db::url);r.add("spring.flyway.user",()->IsolatedContainers.USER);r.add("spring.flyway.password",()->IsolatedContainers.PASSWORD);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("app.site.mode",()->"EXTERNAL");r.add("app.site.id",()->"singapore");r.add("app.site.instance-id",()->LOCAL);r.add("app.site.cluster-id",()->CLUSTER);r.add("app.site.allocation-slot",()->1);
        r.add("app.storage.s3.provider",()->"RUSTFS");r.add("app.storage.s3.endpoint",()->"http://127.0.0.1:1");r.add("app.storage.s3.cdn-endpoint",()->"");
        r.add("app.storage.files.replication.enabled",()->false);r.add("app.storage.files.replication.worker-enabled",()->false);
        r.add("app.account.enabled",()->true);r.add("app.account.multiwriter.enabled",()->true);r.add("app.sync.transport-enabled",()->false);
        r.add("app.cluster.authority-site-id",()->"campus");r.add("app.cluster.authority-instance-id",()->CAMPUS);
        r.add("app.control.enabled",()->true);r.add("app.control.environment-kind",()->"managed-deployment");r.add("app.control.state-directory",state::toString);r.add("app.control.node-enabled",()->true);r.add("app.control.node-token-file",token::toString);
        r.add("app.control.database-mode",()->"independent-local");r.add("app.control.shared-state-required",()->false);
        r.add("app.control.independent.enabled",()->true);r.add("app.control.independent.controller-id",()->HOLDER);r.add("app.control.independent.installation-id",()->INSTALL);
        r.add("app.control.independent.authority-site-id",()->"campus");r.add("app.control.independent.authority-instance-id",()->CAMPUS);
        r.add("app.cluster.enabled",()->true);r.add("app.cluster.coordinator",()->true);r.add("app.cluster.control-lease.enabled",()->true);r.add("app.cluster.automatic-gc-enabled",()->false);
        r.add("app.cluster.control-lease.automatic-takeover",()->false);r.add("app.cluster.control-lease.controller-id",()->HOLDER);
        r.add("management.endpoint.health.probes.enabled",()->true);r.add("management.endpoint.health.group.readiness.include",()->"readinessState,db,redis");
    }
    @Test void localLoginAndManagementReadsWorkWithoutAnyCampusDependency()throws Exception{
        String name="local_"+UUID.randomUUID().toString().substring(0,8),password="synthetic-independent-secret";
        db.admin().update("INSERT INTO eatwhat_controller.account(id,username,password_hash)VALUES(?,?,?)",17L,name,new BCryptPasswordEncoder(4).encode(password));
        String token=login(name,password);assertNotNull(token);
        assertEquals(200,request("GET","/admin/control/v1/cluster/capabilities",null,token).statusCode());
        assertEquals(200,request("GET","/admin/control/v1/cluster",null,token).statusCode());
        var profile=JSON.readTree(request("GET","/admin/control/v1/account",null,token).body());assertEquals(name,profile.path("username").asText());
        assertFalse(profile.path("canEdit").asBoolean());
        assertEquals(200,request("GET","/actuator/health/readiness",null,null).statusCode());
        for(String path:List.of("/internal/cluster/v1/probe","/user/info","/auth/phone/login"))assertEquals(404,request("GET",path,null,token).statusCode());
        assertEquals(200,request("POST","/auth/logout",Map.of(),token).statusCode());
        assertEquals(401,request("GET","/admin/control/v1/cluster/capabilities",null,token).statusCode());
    }
    @Test void aBusinessAdminNeverImplicitlyBecomesAnIndependentManagementAdmin()throws Exception{
        String name="business_"+UUID.randomUUID().toString().substring(0,8),password="synthetic-business-secret";
        db.admin().update("INSERT INTO users(nick_name,password_hash,role)VALUES(?,?,'admin')",name,new BCryptPasswordEncoder(4).encode(password));
        var response=request("POST","/auth/login",Map.of("username",name,"password",password,"device","WEB"),null);
        assertNotEquals(0,JSON.readTree(response.body()).path("code").asInt(-1));
        assertEquals(401,request("GET","/admin/control/v1/cluster/capabilities",null,null).statusCode());
    }
    @Test void heldLeaderCanInspectUnknownReleaseWithoutAdmittingNewWork()throws Exception{
        String name="reconcile_"+UUID.randomUUID().toString().substring(0,8),password="synthetic-independent-secret";
        db.admin().update("INSERT INTO eatwhat_controller.account(id,username,password_hash)VALUES(?,?,?)",21L,name,new BCryptPasswordEncoder(4).encode(password));
        String token=login(name,password);UUID job=UUID.randomUUID();Path file=directory.resolve("state/deployment-"+job+".json");
        var lease=context.getBean(ClusterControlLease.class);
        try{
            db.admin().update("UPDATE eatwhat_cluster.control_lease SET holder_id=?,generation=8,expires_at=clock_timestamp()+interval '5 minutes' WHERE singleton",HOLDER);
            assertTrue(lease.tick());
            // A missing frozen binding remains unknown. Reconciliation must reach its
            // own verifier, never dispatch, and never fabricate a successful result.
            var fixture=new LinkedHashMap<String,Object>();fixture.put("id",job.toString());fixture.put("releaseId","synthetic-existing-release");
            fixture.put("artifactSha256","a".repeat(64));fixture.put("state","OUTCOME_UNKNOWN");fixture.put("targets",List.of(Map.of("site","synthetic","state","PENDING")));fixture.put("_targets",List.of());
            Files.writeString(file,JSON.writeValueAsString(fixture));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            db.admin().update("INSERT INTO eatwhat_cluster.deployment_fence(job_id,cluster_id,state_id,holder_id,generation,epoch,manifest_hash,targets,state)VALUES(?,?,?,?,8,1,?,'[{}]'::jsonb,'ACTIVE')",job,CLUSTER,STATE,HOLDER,"b".repeat(64));
            var blocked=request("POST","/admin/control/v1/cluster/profiles/reload",Map.of(),token);assertEquals(409,blocked.statusCode(),blocked.body());
            var response=request("POST","/admin/control/v1/deployments/"+job+"/reconcile",Map.of(),token);
            assertEquals(200,response.statusCode(),response.body());var body=JSON.readTree(response.body());
            assertEquals("OUTCOME_UNKNOWN",body.path("state").asText());assertEquals("FROZEN_DEPLOYMENT_BINDING_REQUIRED",body.path("reason").asText());
            assertEquals(401,request("POST","/admin/control/v1/deployments/"+job+"/reconcile",Map.of(),null).statusCode());
        }finally{
            Files.deleteIfExists(file);db.admin().update("DELETE FROM eatwhat_cluster.deployment_fence WHERE job_id=?",job);
            db.admin().update("UPDATE eatwhat_cluster.control_lease SET holder_id=?,generation=7,expires_at=clock_timestamp()+interval '5 minutes' WHERE singleton",PEER);lease.tick();
        }
    }
    @Test void standbyQueuesOnlyAnAuthenticatedExactHandoffAndNeverClaimsItsOwnLease()throws Exception{
        String name="queued_"+UUID.randomUUID().toString().substring(0,8),password="synthetic-independent-secret";
        db.admin().update("INSERT INTO eatwhat_controller.account(id,username,password_hash)VALUES(?,?,?)",18L,name,new BCryptPasswordEncoder(4).encode(password));
        String token=login(name,password);UUID requestId=UUID.randomUUID();
        var request=Map.of("requestId",requestId.toString(),"expectedGeneration",7,"targetControllerId",HOLDER.toString());
        var response=request("POST","/admin/control/v1/cluster/leadership/handoffs",request,token);assertEquals(202,response.statusCode());
        assertEquals("WAITING",JSON.readTree(response.body()).path("state").asText());
        assertEquals("PENDING",db.admin().queryForObject("SELECT state FROM eatwhat_controller.handoff_outbox WHERE request_id=?",String.class,requestId));
        assertEquals(PEER,db.admin().queryForObject("SELECT holder_id FROM eatwhat_cluster.control_lease WHERE singleton",UUID.class));
        assertEquals(503,request("POST","/admin/control/v1/cluster/profiles/reload",Map.of(),token).statusCode());
        response=request("POST","/admin/control/v1/cluster/leadership/handoffs/"+requestId+"/cancel",Map.of(),token);
        assertEquals(200,response.statusCode());assertEquals("CANCELLED",JSON.readTree(response.body()).path("state").asText());
        assertEquals(PEER,db.admin().queryForObject("SELECT holder_id FROM eatwhat_cluster.control_lease WHERE singleton",UUID.class));
    }
    private String login(String name,String password)throws Exception{
        var response=request("POST","/auth/login",Map.of("username",name,"password",password,"device","WEB"),null);
        assertEquals(200,response.statusCode());var body=JSON.readTree(response.body());assertEquals(0,body.path("code").asInt(-1));return body.path("data").path("tokenValue").asText();
    }
    private HttpResponse<String> request(String method,String path,Object body,String token)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(token!=null)builder.header("eatwhat-test-token","Bearer "+token);
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @AfterAll static void stop(){REDIS.stop();PG.stop();}
}
