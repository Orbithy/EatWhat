package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.JacksonConfig;
import you.v50to.eatwhat.config.SiteProperties;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Browser HTTP -> real application JVMs -> pinned mutual TLS -> two independently committed databases. */
class AccountFlowIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("account-flow");
    private static final GenericContainer<?> CAMPUS_REDIS = IsolatedContainers.redis("account-campus");
    private static final GenericContainer<?> EXTERNAL_REDIS = IsolatedContainers.redis("account-external");
    private static final String PASSWORD = "Synthetic-Password-Canary-7x";
    private static final String POLICY = "cross-border-v1";
    @TempDir static Path certificates;
    @TempDir Path workingRoot;
    private static TransportTlsFixture tls;
    private final ObjectMapper json = new JacksonConfig().objectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll static void startDependencies() throws Exception {
        tls = TransportTlsFixture.create(certificates);
        POSTGRES.start(); CAMPUS_REDIS.start(); EXTERNAL_REDIS.start();
    }
    @AfterAll static void stopDependencies() {
        EXTERNAL_REDIS.stop(); CAMPUS_REDIS.stop(); POSTGRES.stop();
    }

    @Test void nonconsentingAccountUsesVerifyOnlyRelayGrantBackfillsAndWithdrawalPurgesWithoutChangingIdentity() throws Exception {
        try (var sites = new Sites("60s")) {
            String campusToken = token(register(sites.campus, "relayuser", false));
            long id = id(sites.campusDb, "relayuser");
            String gid = gid(sites.campusDb, id);
            // Historical account details exist before grant and must appear only after grant.
            sites.campusDb.admin().update("INSERT INTO user_info(id,signature) VALUES (?,?)", id, "synthetic historical profile");
            JsonNode denied = request(sites.external, "POST", "/auth/register", registration("deniedusr", false), null);
            assertEquals(20005, denied.path("code").asInt());
            assertEquals(0, countUsers(sites.externalDb));
            assertEquals(30004, login(sites.external, "relayuser", "wrong-password").path("code").asInt());
            assertEquals(30004, login(sites.external, "absentusr", "wrong-password").path("code").asInt());
            JsonNode consentRequired = login(sites.external, "relayuser", PASSWORD);
            assertEquals(20005, consentRequired.path("code").asInt(), consentRequired.toString());
            String proof = consentRequired.path("data").path("consentProof").asText();
            assertFalse(proof.isBlank());
            assertEquals(0, countUsers(sites.externalDb));
            assertEquals(20001, request(sites.external, "GET", "/user/info", null, proof).path("code").asInt());
            JsonNode grant = request(sites.external, "POST", "/auth/consent/grant",
                    Map.of("proof", proof, "policyVersion", POLICY), null);
            assertTrue(grant.path("code").asInt() == 0 || grant.path("code").asInt() == 20006, grant.toString());
            assertEquals(20008, request(sites.external, "POST", "/auth/consent/grant",
                    Map.of("proof", proof, "policyVersion", POLICY), null).path("code").asInt());
            eventually(() -> countUsers(sites.externalDb) == 1);
            String externalToken = awaitLogin(sites.external, "relayuser");
            assertEquals(id, id(sites.externalDb, "relayuser"));
            assertEquals(gid, gid(sites.externalDb, id));
            assertEquals("synthetic historical profile", sites.externalDb.runtime().queryForObject(
                    "SELECT signature FROM user_info WHERE id=?", String.class, id));
            assertEquals(0, request(sites.external, "GET", "/user/info", null, externalToken).path("code").asInt());
            // An account channel is never mounted on either ordinary business HTTP port.
            // The existing global handler wraps unmapped-resource errors in the legacy Result envelope.
            JsonNode missing = request(sites.external, "POST", "/internal/account/v1/rpc", Map.of(), null);
            assertEquals(you.v50to.eatwhat.data.enums.BizCode.UNKNOWN_ERROR.getCode(), missing.path("code").asInt());
            assertFalse(missing.has("header"), "No node protocol handler exists on the business port");
            JsonNode withdrawal = request(sites.external, "POST", "/auth/consent/withdraw", Map.of(), externalToken);
            assertEquals(0, withdrawal.path("code").asInt(), withdrawal.toString());
            assertNotEquals(0, request(sites.external, "GET", "/user/info", null, externalToken).path("code").asInt());
            eventually(() -> countUsers(sites.externalDb) == 0);
            assertEquals(1, countUsers(sites.campusDb));
            assertEquals(id, id(sites.campusDb, "relayuser"));
            assertEquals(gid, gid(sites.externalDb, id), "Tombstone identity survives credential cleanup");
            assertNoCredentialQueue(sites);
            eventually(() -> "WITHDRAWN".equals(sites.campusDb.runtime().queryForObject(
                    "SELECT status FROM eatwhat_consent.subject WHERE user_id=?", String.class, id)));
            assertEquals(20005, login(sites.external, "relayuser", PASSWORD).path("code").asInt());
            JsonNode regrant = request(sites.campus, "POST", "/auth/consent/grant", Map.of("policyVersion", POLICY), campusToken);
            assertEquals(0, regrant.path("code").asInt(), regrant.toString());
            awaitLogin(sites.external, "relayuser");
            assertEquals(id, id(sites.externalDb, "relayuser"));
            assertEquals(gid, gid(sites.externalDb, id));
            assertLogsContainNoSecrets();
        }
    }

    @Test void bothSitesRegisterOfflineAndConflictingProvisionalAccountsRemainDistinctAfterRestart() throws Exception {
        try (var sites = new Sites("60s")) {
            token(register(sites.campus, "collision", false));
            long originalCampus = id(sites.campusDb, "collision");
            sites.stopCampus();
            JsonNode first = register(sites.external, "offlineok", true);
            assertEquals(20006, first.path("code").asInt(), first.toString());
            assertTrue(first.path("data").path("registrationAccepted").asBoolean());
            assertFalse(first.path("data").has("tokenValue"));
            JsonNode conflict = register(sites.external, "collision", true);
            assertEquals(20006, conflict.path("code").asInt(), conflict.toString());
            assertTrue(conflict.path("data").path("registrationAccepted").asBoolean());
            assertFalse(conflict.path("data").has("tokenValue"));
            long externalId = id(sites.externalDb, "offlineok");
            long conflictingId = id(sites.externalDb, "collision");
            String externalGid = gid(sites.externalDb, externalId);
            assertEquals(0, externalId % 2);
            assertNotEquals(originalCampus, conflictingId);
            assertEquals(20006, login(sites.external, "offlineok", PASSWORD).path("code").asInt());
            assertEquals(20006, register(sites.external, "offlinebye", true).path("code").asInt());
            long withdrawnId = id(sites.externalDb, "offlinebye");
            assertEquals(30004, request(sites.external, "POST", "/auth/consent/withdraw-with-password",
                    Map.of("username", "offlinebye", "password", "wrong-password"), null).path("code").asInt());
            JsonNode withdrawn = request(sites.external, "POST", "/auth/consent/withdraw-with-password",
                    Map.of("username", "offlinebye", "password", PASSWORD), null);
            assertEquals(0, withdrawn.path("code").asInt(), withdrawn.toString());
            assertEquals("AWAITING_CAMPUS", withdrawn.path("data").path("cleanupStatus").asText());
            assertEquals(withdrawnId, id(sites.externalDb, "offlinebye"), "Do not delete the only account copy while offline");
            // Actual process termination leaves only durable account/job state to recover.
            sites.killExternal();
            sites.restartExternal();
            assertEquals(externalId, id(sites.externalDb, "offlineok"));
            sites.restartCampus();
            awaitLogin(sites.external, "offlineok");
            assertEquals(externalId, id(sites.campusDb, "offlineok"));
            assertEquals(externalGid, gid(sites.campusDb, externalId));
            eventually(() -> sites.campusDb.runtime().queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, withdrawnId) == 1);
            eventually(() -> sites.externalDb.runtime().queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, withdrawnId) == 0);
            assertEquals("WITHDRAWN", sites.campusDb.runtime().queryForObject(
                    "SELECT status FROM eatwhat_consent.subject WHERE user_id=?", String.class, withdrawnId));
            eventually(() -> "CONFLICT".equals(sites.externalDb.runtime().queryForObject(
                    "SELECT backfill_status FROM eatwhat_consent.subject WHERE user_id=?", String.class, conflictingId)));
            assertEquals(originalCampus, id(sites.campusDb, "collision"));
            assertEquals(conflictingId, id(sites.externalDb, "collision"));
            assertEquals(20006, login(sites.external, "collision", PASSWORD).path("code").asInt());
            assertNoCredentialQueue(sites);
            assertLogsContainNoSecrets();
        }
    }

    @Test void expiredAuthorizationBlocksBothOldSessionAndLoginAndReconnectRefreshesWithoutUserIdChanges() throws Exception {
        try (var sites = new Sites("60s")) {
            token(register(sites.campus, "freshuser", true));
            String token = awaitLogin(sites.external, "freshuser");
            long id = id(sites.externalDb, "freshuser");
            sites.stopCampus();
            String offlineToken = token(login(sites.external, "freshuser", PASSWORD));
            assertEquals(0, request(sites.external, "GET", "/user/info", null, offlineToken).path("code").asInt());
            // Bound the durable authorization clock explicitly; no wall-clock sleeps make this test pass.
            sites.externalDb.admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?", id);
            assertEquals(20010, request(sites.external, "GET", "/user/info", null, offlineToken).path("code").asInt());
            assertEquals(20007, login(sites.external, "freshuser", PASSWORD).path("code").asInt());
            assertEquals(20007, login(sites.external, "unknownusr", PASSWORD).path("code").asInt());
            sites.restartCampus();
            String renewed = awaitLogin(sites.external, "freshuser");
            assertEquals(id, id(sites.externalDb, "freshuser"));
            assertEquals(0, request(sites.external, "GET", "/user/info", null, renewed).path("code").asInt());
            // A source security update must never leave an old token authorized through cached roles.
            sites.campusDb.admin().update("UPDATE users SET banned=true WHERE id=?", id);
            eventually(() -> Boolean.TRUE.equals(sites.externalDb.runtime().queryForObject("SELECT banned FROM users WHERE id=?", Boolean.class, id)));
            int blocked = request(sites.external, "GET", "/user/info", null, renewed).path("code").asInt();
            assertTrue(blocked == 30002 || blocked == 20001, "Banned or revoked session must fail specifically");
            assertEquals(30002, login(sites.external, "freshuser", PASSWORD).path("code").asInt());
            assertNoCredentialQueue(sites);
        }
    }

    private Map<String,Object> registration(String name, boolean grant) {
        return Map.of("username",name,"password",PASSWORD,"device","WEB","crossBorderConsent",grant,"consentPolicyVersion",POLICY);
    }
    private JsonNode register(DualModeApplicationProcess process, String name, boolean grant) throws Exception {
        return request(process,"POST","/auth/register",registration(name,grant),null);
    }
    private JsonNode login(DualModeApplicationProcess process,String name,String password) throws Exception {
        return request(process,"POST","/auth/login",Map.of("username",name,"password",password,"device","WEB"),null);
    }
    private String awaitLogin(DualModeApplicationProcess process,String name) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos(); JsonNode response=null;
        do { response=login(process,name,PASSWORD);if(response.path("code").asInt()==0)return token(response);Thread.sleep(100); }
        while(System.nanoTime()<deadline);
        throw new AssertionError("Account did not become ready: "+response);
    }
    private String token(JsonNode response) {
        assertEquals(0,response.path("code").asInt(),response.toString());
        String token=response.path("data").path("tokenValue").asText();assertFalse(token.isEmpty());return token;
    }
    private JsonNode request(DualModeApplicationProcess process,String method,String path,Object body,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+process.port()+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)builder.header("eatwhat-test-token","Bearer "+token);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if(body!=null)builder.header("Content-Type","application/json");
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body());
    }
    private long id(EventFoundationTestSupport.Database db,String name) {return db.runtime().queryForObject("SELECT id FROM users WHERE nick_name=?",Long.class,name);}
    private String gid(EventFoundationTestSupport.Database db,long id) {return db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",String.class,id);}
    private int countUsers(EventFoundationTestSupport.Database db) {return db.runtime().queryForObject("SELECT count(*) FROM users",Integer.class);}
    private void eventually(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(45).toNanos();while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(100);}assertTrue(condition.getAsBoolean());
    }
    private void assertNoCredentialQueue(Sites sites) {
        for(var db:java.util.List.of(sites.campusDb,sites.externalDb)) {
            for(String table:java.util.List.of("outbox","inbox","command_dedup","conflict"))
                assertEquals(0,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync."+table,Integer.class));
            for(String table:java.util.List.of("job","proof","withdrawal_receipt")) {
                String dump=db.admin().queryForObject("SELECT coalesce(json_agg(row_to_json(t))::text,'[]') FROM eatwhat_consent."+table+" t",String.class);
                assertFalse(dump.contains(PASSWORD));assertFalse(dump.contains("passwordHash"));assertFalse(dump.contains("$2a$"));
                assertFalse(dump.contains("relayuser"));
            }
        }
    }
    private void assertLogsContainNoSecrets() throws Exception {
        try(var paths=Files.walk(workingRoot)){for(Path p:paths.filter(v->v.getFileName().toString().equals("application.log")).toList())assertFalse(Files.readString(p).contains(PASSWORD));}
    }

    private class Sites implements AutoCloseable {
        final EventFoundationTestSupport.Database campusDb,externalDb;
        final Map<String,String> campusConfig,externalConfig;
        DualModeApplicationProcess campus,external;
        Sites(String ttl) throws Exception {
            SiteProperties a=site(SiteProperties.Mode.CAMPUS,"account-campus");
            SiteProperties b=site(SiteProperties.Mode.EXTERNAL,"account-external");
            campusDb=EventFoundationTestSupport.createDatabase(POSTGRES,a);externalDb=EventFoundationTestSupport.createDatabase(POSTGRES,b);
            int aPort=unusedPort(), bPort=unusedPort();
            campusConfig=properties(campusDb,b,CAMPUS_REDIS,aPort,bPort,ttl);
            externalConfig=properties(externalDb,a,EXTERNAL_REDIS,bPort,aPort,ttl);
            try {restartCampus();restartExternal();}catch(Exception|Error failure){close();throw failure;}
        }
        void stopCampus() throws Exception {if(campus!=null){campus.close();campus=null;}}
        void restartCampus() throws Exception {campus=new DualModeApplicationProcess(workingRoot,"campus-"+UUID.randomUUID(),campusConfig);campus.awaitReady(json);}
        void restartExternal() throws Exception {external=new DualModeApplicationProcess(workingRoot,"external-"+UUID.randomUUID(),externalConfig);external.awaitReady(json);}
        void killExternal() throws Exception {var child=ProcessHandle.of(external.pid()).orElseThrow();assertTrue(child.destroyForcibly());child.onExit().get(10,TimeUnit.SECONDS);external.close();external=null;}
        public void close() throws Exception {if(external!=null)external.close();if(campus!=null)campus.close();}
    }
    private SiteProperties site(SiteProperties.Mode mode,String id) {var p=new SiteProperties();p.setMode(mode);p.setId(id);p.setInstanceId(UUID.randomUUID());return p;}
    private int unusedPort() throws Exception {try(var socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private Map<String,String> properties(EventFoundationTestSupport.Database db,SiteProperties peer,GenericContainer<?> redis,int localPort,int peerPort,String ttl) {
        boolean campus=db.site().getMode()==SiteProperties.Mode.CAMPUS;var own=campus?tls.campus():tls.external();var remote=campus?tls.external():tls.campus();
        Map<String,String> p=new LinkedHashMap<>();
        // Test fixtures create accounts through the closed-by-default username registration.
        p.put("app.auth.username-registration-enabled","true");
        p.put("app.site.mode",db.site().getMode().name());p.put("app.site.id",db.site().getId());p.put("app.site.instance-id",db.site().getInstanceId().toString());
        p.put("spring.datasource.url",db.url());p.put("spring.datasource.username",db.runtimeUsername());p.put("spring.datasource.password",IsolatedContainers.PASSWORD);
        p.put("spring.flyway.url",db.url());p.put("spring.flyway.user",IsolatedContainers.USER);p.put("spring.flyway.password",IsolatedContainers.PASSWORD);
        p.put("spring.data.redis.host",redis.getHost());p.put("spring.data.redis.port",redis.getMappedPort(6379).toString());p.put("spring.data.redis.url","redis://"+redis.getHost()+":"+redis.getMappedPort(6379)+"/0");p.put("spring.data.redis.username","");p.put("spring.data.redis.password","");
        p.put("app.storage.s3.provider",campus?"RUSTFS":"R2");p.put("app.storage.s3.endpoint",campus?"http://127.0.0.1:1":"https://00000000000000000000000000000000.r2.cloudflarestorage.com");p.put("app.storage.s3.region",campus?"us-east-1":"auto");p.put("app.storage.s3.bucket","synthetic-account-bucket");p.put("app.storage.s3.access-key","synthetic-access");p.put("app.storage.s3.secret-key","synthetic-secret");p.put("app.storage.s3.path-style","true");p.put("app.storage.s3.public-base-url","https://public.example.invalid/");p.put("app.storage.s3.public-read-prefixes","avatar/");p.put("app.storage.s3.allowed-read-prefixes","avatar/,foods/");p.put("app.storage.s3.cdn-endpoint","");
        p.put("app.account.enabled","true");p.put("app.account.synthetic-only","true");p.put("app.account.policy-version",POLICY);p.put("app.account.authorization-ttl",ttl);p.put("app.account.poll-interval","250ms");p.put("app.account.request-timeout","2s");p.put("app.account.lease-duration","10s");
        p.put("app.account.peer-site-id",peer.getId());p.put("app.account.peer-instance-id",peer.getInstanceId().toString());p.put("app.account.peer-base-url","https://127.0.0.1:"+peerPort);p.put("app.account.listen-port",Integer.toString(localPort));p.put("app.account.key-store",own.keyStore().toString());p.put("app.account.key-store-password",TransportTlsFixture.PASSWORD);p.put("app.account.trust-store",tls.trustStore().toString());p.put("app.account.trust-store-password",TransportTlsFixture.PASSWORD);p.put("app.account.peer-certificate-sha256",remote.pin());
        return p;
    }
}
