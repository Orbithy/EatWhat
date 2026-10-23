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
class ControlMultiwriterAccountIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("control-multiwriter-account"), REDIS=IsolatedContainers.redis("control-multiwriter-account");
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
        db.admin().update("INSERT INTO eatwhat_cluster.local_state(singleton,cluster_id,epoch,site_id,instance_id,state,members) VALUES(true,?,1,?,?,'ACTIVE','[]'::jsonb)",site.getClusterId(),site.getId(),site.getInstanceId());
        directory=Files.createTempDirectory("eatwhat-managed-bootstrap-");
        Path token=directory.resolve("node.token");Files.writeString(token,"synthetic-bootstrap-node-"+"x".repeat(48));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
        r.add("spring.datasource.url",db::url);r.add("spring.datasource.username",db::runtimeUsername);r.add("spring.datasource.password",()->IsolatedContainers.PASSWORD);
        r.add("spring.flyway.url",db::url);r.add("spring.flyway.user",()->IsolatedContainers.USER);r.add("spring.flyway.password",()->IsolatedContainers.PASSWORD);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("app.site.mode",()->"CAMPUS");r.add("app.site.id",site::getId);r.add("app.site.instance-id",()->site.getInstanceId().toString());r.add("app.site.cluster-id",()->site.getClusterId().toString());r.add("app.site.allocation-slot",()->0);
        r.add("app.storage.s3.provider",()->"RUSTFS");r.add("app.storage.s3.endpoint",()->"http://127.0.0.1:1");r.add("app.storage.s3.cdn-endpoint",()->"");
        r.add("app.storage.files.replication.enabled",()->false);r.add("app.storage.files.replication.worker-enabled",()->false);
        r.add("sa-token.is-concurrent",()->true);
        r.add("sa-token.is-share",()->true);
        r.add("app.account.multiwriter.enabled",()->true);r.add("app.account.enabled",()->true);r.add("app.sync.transport-enabled",()->false);
        r.add("app.control.enabled",()->true);r.add("app.control.environment-kind",()->"managed-deployment");r.add("app.control.state-directory",()->directory.resolve("state").toString());r.add("app.control.node-enabled",()->true);r.add("app.control.node-token-file",token::toString);
        r.add("app.cluster.enabled",()->true);r.add("app.cluster.coordinator",()->true);r.add("app.cluster.control-lease.enabled",()->true);r.add("app.cluster.automatic-gc-enabled",()->false);
        r.add("management.endpoint.health.probes.enabled",()->true);r.add("management.endpoint.health.group.readiness.include",()->"readinessState,db,redis");
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired you.v50to.eatwhat.control.ControlAccountSecurity security;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    private String username,password,token;private long userId;
    @BeforeEach void account()throws Exception {
        username="admin_"+UUID.randomUUID().toString().substring(0,8);password="synthetic-admin-password";
        db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=false");
        userId=jdbc.queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'admin') RETURNING id",Long.class,username,passwords.encode(password));
        db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");
        token=login(username,password);
    }
    private String login(String name,String password)throws Exception {
        var response=request("POST","/auth/login",Map.of("username",name,"password",password,"device","WEB"),null);
        assertEquals(200,response.statusCode(),response.body());var value=JSON.readTree(response.body());assertEquals(0,value.path("code").asInt(-1),response.body());return value.path("data").path("tokenValue").asText();
    }
    private void error(int status,String code,Map<String,Object> body)throws Exception {
        var response=request("POST","/admin/control/v1/account/credentials",body,token);assertEquals(status,response.statusCode(),response.body());assertEquals(code,JSON.readTree(response.body()).path("code").asText());
    }
    @Test void fullV2ControlPasswordChangeKeepsCredentialStampAndCausalHistory()throws Exception {
        assertEquals("74",db.admin().queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",String.class));
        assertTrue(db.admin().queryForObject("SELECT enabled FROM eatwhat_account_multiwriter.configuration WHERE singleton",Boolean.class));
        assertTrue(context.containsBean("accountAuthService"));assertEquals(1,context.getBeansOfType(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.class).size());
        String replacement="synthetic-control-replacement";var response=request("POST","/admin/control/v1/account/credentials",Map.of("currentPassword",password,"newPassword",replacement),token);
        assertEquals(200,response.statusCode(),response.body());assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.operation WHERE actor_id=? AND field='password' AND source_site<>'baseline'",Long.class,userId));
        assertEquals(401,request("GET","/admin/control/v1/account",null,token).statusCode());String fresh=login(username,replacement);assertEquals(200,request("GET","/admin/control/v1/account",null,fresh).statusCode());
        assertTrue(security.independentBusinessAuthentication());
        var sessions=context.getBean(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.class);assertThrows(RuntimeException.class,()->sessions.authorizeSession(token));assertEquals(userId,sessions.authorizeSession(fresh).userId());
    }
    @Test void allLegacyTokensRequireFreshLoginAndCannotBeAdoptedFromRedis()throws Exception {
        for(String condition:List.of("normal","revoked","stale","expired","idle-expired")){
            String legacy=login(username,password);String hash=you.v50to.eatwhat.sync.EventCodec.sha256(legacy);
            // Retain the real Redis login and control stamp, but model a token issued before v2 existed.
            db.admin().update("DELETE FROM eatwhat_account_multiwriter.session WHERE token_hash=?",hash);
            String passwordHash=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,userId);
            UUID subject=jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,userId);
            long version=jdbc.queryForObject("SELECT coalesce((SELECT version FROM eatwhat_cluster.session_security_version WHERE user_id=?),0)",Long.class,userId);
            db.admin().update("INSERT INTO eatwhat_cluster.login_session(token_hash,subject_id,user_id,source_site,source_instance,consent_epoch,security_hash,security_version,expires_at,idle_seconds,last_seen,revoked) VALUES(?,?,?,?,?,0,?,?,clock_timestamp()+(?*interval '1 second'),60,clock_timestamp()-(?*interval '1 second'),?)",hash,subject,userId,db.site().getId(),db.site().getInstanceId(),you.v50to.eatwhat.sync.EventCodec.sha256(passwordHash+"\u0000false\u0000admin"),condition.equals("stale")?version+1:version,condition.equals("expired")?-1:3600,condition.equals("idle-expired")?120:0,condition.equals("revoked"));
            long facts=jdbc.queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.session_fact WHERE token_hash=?",Long.class,hash);
            assertEquals(401,request("GET","/admin/control/v1/account",null,legacy).statusCode(),condition);
            assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.session WHERE token_hash=?",Long.class,hash));
            assertEquals(facts,jdbc.queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.session_fact WHERE token_hash=?",Long.class,hash));
        }
        String fresh=login(username,password);assertEquals(200,request("GET","/admin/control/v1/account",null,fresh).statusCode());
        assertEquals(userId,context.getBean(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.class).authorizeSession(fresh).userId());
    }
    @Test void validDurableSessionCannotBypassAdvancedControlCredentialStamp()throws Exception {
        var sessions=context.getBean(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.class);assertEquals(userId,sessions.authorizeSession(token).userId());
        db.admin().update("INSERT INTO eatwhat_control.credential_version(user_id,version) VALUES(?,1) ON CONFLICT(user_id) DO UPDATE SET version=eatwhat_control.credential_version.version+1",userId);
        assertEquals(userId,sessions.authorizeSession(token).userId(),"business causal state alone is intentionally unchanged");
        assertEquals(401,request("GET","/admin/control/v1/account",null,token).statusCode());
    }
    @Test void unprivilegedAndBadPasswordCannotUseControlCredentialCommand()throws Exception {
        error(400,"CURRENT_PASSWORD_INVALID",Map.of("currentPassword","incorrect","newPassword","not-the-real-password"));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.operation WHERE actor_id=? AND source_site<>'baseline'",Long.class,userId));
        jdbc.update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'user')",username+"_normal",passwords.encode(password));String ordinary=login(username+"_normal",password);
        assertEquals(403,request("POST","/admin/control/v1/account/credentials",Map.of("currentPassword",password,"newPassword","ordinary-attempt-password"),ordinary).statusCode());
    }
    @Test void standbyAndDrainingLeaderCannotChangeCredentials()throws Exception {
        String lease="eatwhat_cluster.control_lease";
        UUID holder=jdbc.queryForObject("SELECT holder_id FROM "+lease+" WHERE singleton",UUID.class);
        long generation=jdbc.queryForObject("SELECT generation FROM "+lease+" WHERE singleton",Long.class);
        UUID request=UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_cluster.control_handoff(request_id,cluster_id,source_holder,target_holder,expected_generation,state,actor) SELECT ?,cluster_id,holder_id,?,generation,'WAITING','synthetic-test' FROM "+lease+" WHERE singleton",request,UUID.randomUUID());
        try{
            assertFalse(JSON.readTree(request("GET","/admin/control/v1/account",null,token).body()).path("canEdit").asBoolean());
            error(409,"CONTROL_HANDOFF_PENDING",Map.of("currentPassword",password,"newUsername",username+"_new"));
        }finally{jdbc.update("UPDATE eatwhat_cluster.control_handoff SET state='CANCELLED' WHERE request_id=?",request);}
        // Exercise the existing non-coordinator admission without fabricating an invalid lease holder.
        var cluster=context.getBean(ClusterProperties.class);cluster.setCoordinator(false);
        try{error(409,"ACCOUNT_AUTHORITY_REQUIRED",Map.of("currentPassword",password,"newUsername",username+"_new"));}
        finally{cluster.setCoordinator(true);}

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
