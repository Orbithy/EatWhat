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
class ControlAccountIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("control-account"), REDIS=IsolatedContainers.redis("control-account");
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
        Path token=directory.resolve("node.token");Files.writeString(token,"synthetic-bootstrap-node-"+"x".repeat(48));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
        r.add("spring.datasource.url",db::url);r.add("spring.datasource.username",db::runtimeUsername);r.add("spring.datasource.password",()->IsolatedContainers.PASSWORD);
        r.add("spring.flyway.url",db::url);r.add("spring.flyway.user",()->IsolatedContainers.USER);r.add("spring.flyway.password",()->IsolatedContainers.PASSWORD);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("app.site.mode",()->"CAMPUS");r.add("app.site.id",site::getId);r.add("app.site.instance-id",()->site.getInstanceId().toString());r.add("app.site.cluster-id",()->site.getClusterId().toString());r.add("app.site.allocation-slot",()->0);
        r.add("app.storage.s3.provider",()->"RUSTFS");r.add("app.storage.s3.endpoint",()->"http://127.0.0.1:1");r.add("app.storage.s3.cdn-endpoint",()->"");
        r.add("app.storage.files.replication.enabled",()->false);r.add("app.storage.files.replication.worker-enabled",()->false);
        r.add("sa-token.is-concurrent",()->true);
        r.add("sa-token.is-share",()->true);
        r.add("app.account.enabled",()->false);r.add("app.sync.transport-enabled",()->false);
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
        userId=jdbc.queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'admin') RETURNING id",Long.class,username,passwords.encode(password));
        token=login(username,password);
    }
    private String login(String name,String password)throws Exception {
        var response=request("POST","/auth/login",Map.of("username",name,"password",password,"device","WEB"),null);
        assertEquals(200,response.statusCode(),response.body());var value=JSON.readTree(response.body());assertEquals(0,value.path("code").asInt(-1),response.body());return value.path("data").path("tokenValue").asText();
    }
    private void error(int status,String code,Map<String,Object> body)throws Exception {
        var response=request("POST","/admin/control/v1/account/credentials",body,token);assertEquals(status,response.statusCode(),response.body());assertEquals(code,JSON.readTree(response.body()).path("code").asText());
    }
    @Test void selfProfileIsAdminOnlyAndNeverIncludesSecrets()throws Exception {
        assertEquals(401,request("GET","/admin/control/v1/account",null,null).statusCode());
        jdbc.update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'user')",username+"_u",passwords.encode(password));
        String ordinary=login(username+"_u",password);
        assertEquals(403,request("GET","/admin/control/v1/account",null,ordinary).statusCode());
        assertEquals(403,request("POST","/admin/control/v1/account/credentials",Map.of("currentPassword",password,"newUsername","notallowed"),ordinary).statusCode());
        var response=request("GET","/admin/control/v1/account",null,token);assertEquals(200,response.statusCode(),response.body());
        var body=JSON.readTree(response.body());assertEquals(username,body.path("username").asText());assertTrue(body.path("canEdit").asBoolean());assertFalse(response.body().contains("password"));
    }
    @Test void usernameOnlyChangeRevokesEveryPriorLoginAndKeepsStableAccountId()throws Exception {
        String other=login(username,password);assertNotEquals(token,other,"Admin tokens must not be reused even when global token sharing is enabled");String next=username+"_new";
        var response=request("POST","/admin/control/v1/account/credentials",Map.of("currentPassword",password,"newUsername",next,"userId",userId+100),token);
        assertEquals(200,response.statusCode(),response.body());assertTrue(JSON.readTree(response.body()).path("reauthenticationRequired").asBoolean());
        assertEquals(next,jdbc.queryForObject("SELECT nick_name FROM users WHERE id=?",String.class,userId));
        assertEquals(401,request("GET","/admin/control/v1/cluster/capabilities",null,token).statusCode());
        assertEquals(401,request("GET","/admin/control/v1/account",null,other).statusCode());
        String fresh=login(next,password);assertEquals(200,request("GET","/admin/control/v1/account",null,fresh).statusCode());
        var old=request("POST","/auth/login",Map.of("username",username,"password",password,"device","WEB"),null);assertNotEquals(0,JSON.readTree(old.body()).path("code").asInt());
        assertEquals(1,db.admin().queryForObject("SELECT count(*) FROM eatwhat_control.credential_change WHERE user_id=? AND username_changed AND NOT password_changed",Integer.class,userId));
    }
    @Test void passwordChangeHashesNewPasswordAndSupportsCombinedRename()throws Exception {
        String next=username+"_new",secret="new-synthetic-password";
        var response=request("POST","/admin/control/v1/account/credentials",Map.of("currentPassword",password,"newUsername",next,"newPassword",secret),token);assertEquals(200,response.statusCode(),response.body());
        String hash=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,userId);assertNotEquals(secret,hash);assertTrue(passwords.matches(secret,hash));assertFalse(passwords.matches(password,hash));
        assertFalse(response.body().contains(secret));assertFalse(response.body().contains(password));
        String fresh=login(next,secret);assertEquals(200,request("GET","/admin/control/v1/account",null,fresh).statusCode());
        var old=request("POST","/auth/login",Map.of("username",next,"password",password,"device","WEB"),null);assertNotEquals(0,JSON.readTree(old.body()).path("code").asInt());
    }
    @Test void passwordOnlyChangeKeepsUsernameAndRejectsAnUnchangedPassword()throws Exception {
        error(400,"PASSWORD_UNCHANGED",Map.of("currentPassword",password,"newPassword",password));
        var response=request("POST","/admin/control/v1/account/credentials",Map.of("currentPassword",password,"newPassword","replacement-password"),token);
        assertEquals(200,response.statusCode(),response.body());String fresh=login(username,"replacement-password");
        assertEquals(username,JSON.readTree(request("GET","/admin/control/v1/account",null,fresh).body()).path("username").asText());
    }
    @Test void unicodePasswordLengthUsesCodePointsAndStillEnforcesTheBcryptByteLimit()throws Exception {
        error(400,"INVALID_NEW_PASSWORD",Map.of("currentPassword",password,"newPassword","😀".repeat(6)));
        error(400,"INVALID_NEW_PASSWORD",Map.of("currentPassword",password,"newPassword","\u00a0".repeat(12)));
        String unicode="x".repeat(62)+"😀😀"; // 64 code points, 66 UTF-16 units, 70 UTF-8 bytes.
        var response=request("POST","/admin/control/v1/account/credentials",Map.of("currentPassword",password,"newPassword",unicode),token);
        assertEquals(200,response.statusCode(),response.body());String fresh=login(username,unicode);assertEquals(200,request("GET","/admin/control/v1/account",null,fresh).statusCode());
    }
    @Test void aLoginSnapshotCannotPickUpTheVersionOfNewCredentials()throws Exception {
        var before=context.getBean(you.v50to.eatwhat.mapper.UserMapper.class).selectById(userId);
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?",passwords.encode("replacement-password"),userId);
        assertEquals(401,assertThrows(you.v50to.eatwhat.control.ControlFailure.class,()->security.loginVersion(before)).status);
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
        // Force another live holder; this request must fail before touching the account.
        jdbc.update("UPDATE "+lease+" SET holder_id=?,generation=generation+1,expires_at=clock_timestamp()+interval '30 seconds' WHERE singleton",UUID.randomUUID());
        try{
            var response=request("POST","/admin/control/v1/account/credentials",Map.of("currentPassword",password,"newUsername",username+"_new"),token);
            assertTrue(Set.of(409,503).contains(response.statusCode()),response.body());
        }finally{jdbc.update("UPDATE "+lease+" SET holder_id=?,generation=?,expires_at=clock_timestamp()+interval '30 seconds' WHERE singleton",holder,generation);context.getBean(ClusterControlLease.class).tick();}
    }
    @Test void wrongCurrentPasswordDoesNotMutateOrLogoutAndIsRateLimited()throws Exception {
        for(int i=0;i<5;i++)error(400,"CURRENT_PASSWORD_INVALID",Map.of("currentPassword","incorrect","newUsername",username+"_new"));
        error(429,"CREDENTIAL_RATE_LIMITED",Map.of("currentPassword",password,"newUsername",username+"_new"));
        assertEquals(username,jdbc.queryForObject("SELECT nick_name FROM users WHERE id=?",String.class,userId));assertEquals(200,request("GET","/admin/control/v1/account",null,token).statusCode());
    }
    @Test void duplicateNamesAndInvalidPasswordsLeaveTheOriginalCredentialsIntact()throws Exception {
        String occupied=username+"_used";jdbc.update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'user')",occupied,passwords.encode(password));
        error(409,"USERNAME_UNAVAILABLE",Map.of("currentPassword",password,"newUsername",occupied));
        error(400,"INVALID_NEW_PASSWORD",Map.of("currentPassword",password,"newPassword","short"));
        error(400,"INVALID_NEW_PASSWORD",Map.of("currentPassword",password,"newPassword","密".repeat(30)));
        error(400,"INVALID_USERNAME",Map.of("currentPassword",password,"newUsername","has spaces"));
        error(400,"NO_CREDENTIAL_CHANGES",Map.of("currentPassword",password));
        assertEquals(200,request("GET","/admin/control/v1/account",null,token).statusCode());
        assertEquals(0,db.admin().queryForObject("SELECT count(*) FROM eatwhat_control.credential_change WHERE user_id=?",Integer.class,userId));
    }
    @Test void aDemotedAdministratorCannotReuseAnOldTokenOrCachedAdminRole()throws Exception {
        assertEquals(200,request("GET","/admin/control/v1/account",null,token).statusCode());
        jdbc.update("UPDATE users SET role='user' WHERE id=?",userId);
        assertEquals(401,request("GET","/admin/control/v1/account",null,token).statusCode());
        var response=request("POST","/auth/login",Map.of("username",username,"password",password,"device","WEB"),null);
        var data=JSON.readTree(response.body());assertEquals(0,data.path("code").asInt(-1));assertNotEquals("admin",data.path("data").path("tag").asText());
        String fresh=data.path("data").path("tokenValue").asText();assertEquals(403,request("GET","/admin/control/v1/account",null,fresh).statusCode());
    }
    @Test void databaseFenceRevokesAStaleOrRacingTokenEvenWhenRedisStillKnowsIt()throws Exception {
        // Simulate credential rotation committing while a login with the prior snapshot is in flight.
        jdbc.update("UPDATE users SET nick_name=? WHERE id=?",username+"_new",userId);
        assertEquals(401,request("GET","/admin/control/v1/cluster/capabilities",null,token).statusCode());
        // This management-only fixture has not joined a business site: its earlier filter denies all business routes.
        assertEquals(423,request("GET","/auth/verify",null,token).statusCode());
        String fresh=login(username+"_new",password);assertEquals(200,request("GET","/admin/control/v1/account",null,fresh).statusCode());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE eatwhat_control.credential_version SET version=0 WHERE user_id=?",userId));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM eatwhat_control.credential_version WHERE user_id=?",userId));
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
