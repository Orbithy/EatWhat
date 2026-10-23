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
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Browser HTTP -> two real application JVMs -> activity capture -> Outbox -> pinned mTLS push/pull -> Inbox worker
 * -> owner-controlled import functions -> second database. No in-process shortcut sits between the two sites.
 */
class ActivityFlowIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("activity-flow");
    private static final GenericContainer<?> CAMPUS_REDIS = IsolatedContainers.redis("activity-campus");
    private static final GenericContainer<?> EXTERNAL_REDIS = IsolatedContainers.redis("activity-external");
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

    @Test void consentedAuthorsContentFlowsBothWaysAndDeletionIsAnAuthorOnlyTombstone() throws Exception {
        try (var sites = new Sites()) {
            long activity = onlineActivity(sites, "flow");
            String campusToken = token(register(sites.campus, "flowuser", true));
            long author = id(sites.campusDb, "flowuser");
            awaitLogin(sites.external, "flowuser");
            verify(sites, author);
            // The account revision revoked pre-verification external sessions (expected); use a fresh, stable one.
            String externalToken = awaitStableSession(sites.external, "flowuser");
            int province = sites.campusDb.admin().queryForObject("SELECT min(id) FROM provinces", Integer.class);
            int city = sites.campusDb.admin().queryForObject("SELECT min(id) FROM cities WHERE province_id=?", Integer.class, province);

            // Campus publishes through the ordinary HTTP contract.
            JsonNode published = request(sites.campus, "POST", "/activity/flow/posts", Map.of("title", "dumpling",
                    "description", "synthetic", "provinceId", province, "cityId", city,
                    "pictureUrl", List.of("activity/dumpling-1.png", "activity/dumpling-2.png"),
                    "extra", Map.of("dish", "dumpling")), campusToken);
            assertEquals(0, published.path("code").asInt(), published.toString());
            long post = published.path("data").path("id").asLong();
            assertEquals(1, post % 2);
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM activity_posts WHERE id=" + post) == 1);
            JsonNode detail = request(sites.external, "GET", "/activity/posts/" + post, null, externalToken);
            assertEquals(0, detail.path("code").asInt(), detail.toString());
            assertEquals("dumpling", detail.path("data").path("title").asText());
            assertEquals("dumpling", detail.path("data").path("extra").path("dish").asText());
            assertEquals(activity, detail.path("data").path("activityId").asLong());
            assertEquals(author, detail.path("data").path("accountId").asLong());
            assertEquals(0, detail.path("data").path("likesCount").asInt());
            assertEquals(2, detail.path("data").path("pictureUrl").size());
            assertEquals(gid(sites.campusDb, "activity_posts", post), gid(sites.externalDb, "activity_posts", post));

            // The external site publishes as the same (replicated) author.
            JsonNode external = request(sites.external, "POST", "/activity/flow/posts", Map.of("title", "noodle",
                    "pictureUrl", List.of("activity/noodle.png"), "extra", Map.of("dish", "noodle")), externalToken);
            assertEquals(0, external.path("code").asInt(), external.toString());
            long externalPost = external.path("data").path("id").asLong();
            assertEquals(0, externalPost % 2);
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM activity_posts WHERE id=" + externalPost) == 1);
            assertEquals(2, request(sites.campus, "GET", "/activity/flow/posts", null, null).path("data").path("totalItems").asInt());

            // Deleting the replica on the external site reaches the original on campus, and stays a tombstone.
            JsonNode deleted = request(sites.external, "DELETE", "/activity/posts/" + post, null, externalToken);
            assertEquals(0, deleted.path("code").asInt(), deleted.toString());
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM activity_posts WHERE id=" + post + " AND deleted_at IS NOT NULL") == 1);
            assertEquals(1, count(sites.campusDb, "SELECT count(*) FROM activity_posts WHERE id=" + post), "No physical delete");
            assertNotEquals(0, request(sites.campus, "GET", "/activity/posts/" + post, null, campusToken).path("code").asInt());

            for (var db : List.of(sites.campusDb, sites.externalDb)) {
                assertEquals(0, count(db, "SELECT count(*) FROM eatwhat_sync.inbox WHERE status<>'APPLIED'"));
                assertEquals(0, count(db, "SELECT count(*) FROM eatwhat_sync.conflict"));
                assertEquals(0, count(db, "SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'"));
            }
        }
    }

    @Test void contentOfAnAuthorWithoutAGrantNeverLeavesTheSite() throws Exception {
        try (var sites = new Sites()) {
            onlineActivity(sites, "local");
            String campusToken = token(register(sites.campus, "localonly", false));
            long author = id(sites.campusDb, "localonly");
            sites.campusDb.admin().update("INSERT INTO verifications(account_id,method,verified,verified_email) VALUES(?,'school_email',true,'x@school.invalid')", author);
            evictRoleCache(author);
            long outboxBefore = count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type IN ('activity_posts','activity_state')");
            JsonNode published = request(sites.campus, "POST", "/activity/local/posts",
                    Map.of("description", "private post", "pictureUrl", List.of("activity/private.png")), campusToken);
            assertEquals(0, published.path("code").asInt(), published.toString());
            assertEquals(1, count(sites.campusDb, "SELECT count(*) FROM activity_posts"));
            assertEquals(outboxBefore, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type IN ('activity_posts','activity_state')"));
            Thread.sleep(2_000);
            assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM activity_posts"));
        }
    }

    @Test void contentCreatedWhilePeerIsDownIsDeliveredAndAppliedAfterItRestarts() throws Exception {
        try (var sites = new Sites()) {
            onlineActivity(sites, "outage");
            String campusToken = token(register(sites.campus, "outageuser", true));
            awaitLogin(sites.external, "outageuser");
            long author = id(sites.campusDb, "outageuser");
            verify(sites, author);
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'") == 0);
            sites.killExternal();
            JsonNode published = request(sites.campus, "POST", "/activity/outage/posts",
                    Map.of("description", "while the peer is down", "pictureUrl", List.of("activity/outage.png")), campusToken);
            assertEquals(0, published.path("code").asInt(), published.toString());
            long post = published.path("data").path("id").asLong();
            assertEquals(1, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'"));
            sites.restartExternal();
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM activity_posts WHERE id=" + post) == 1);
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'") == 0);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /**
     * A campus administrator creates and opens an activity over HTTP; catalog sync carries the definition to the
     * external site, so posts there can reference it.
     */
    private long onlineActivity(Sites sites, String code) throws Exception {
        String adminName = "adm" + code;
        String adminToken = token(register(sites.campus, adminName, false));
        long admin = id(sites.campusDb, adminName);
        sites.campusDb.admin().update("UPDATE users SET role='admin' WHERE id=?", admin);
        evictRoleCache(admin);
        JsonNode created = request(sites.campus, "POST", "/admin/activities", Map.of("code", code, "name", "Activity " + code,
                "fieldSchema", List.of(Map.of("key", "dish", "label", "Dish", "type", "TEXT", "required", false, "maxLength", 20))),
                adminToken);
        assertEquals(0, created.path("code").asInt(), created.toString());
        long activity = created.path("data").path("id").asLong();
        JsonNode online = request(sites.campus, "POST", "/admin/activities/" + activity + "/status", Map.of("status", "ONLINE"), adminToken);
        assertEquals(0, online.path("code").asInt(), online.toString());
        eventually(() -> count(sites.externalDb, "SELECT count(*) FROM activities WHERE id=" + activity + " AND status='ONLINE'") == 1);
        assertEquals(gid(sites.campusDb, "activities", activity), gid(sites.externalDb, "activities", activity));
        return activity;
    }

    private void verify(Sites sites, long author) throws Exception {
        sites.campusDb.admin().update("INSERT INTO verifications(account_id,method,verified,verified_email) VALUES(?,'school_email',true,'x@school.invalid')", author);
        eventually(() -> count(sites.externalDb, "SELECT count(*) FROM verifications WHERE account_id=" + author) == 1);
        evictRoleCache(author);
    }

    /** Registration already evaluated the role once; a cached "not verified" would otherwise last two minutes. */
    private void evictRoleCache(long author) throws Exception {
        for (var redis : List.of(CAMPUS_REDIS, EXTERNAL_REDIS)) {
            var result = redis.execInContainer("redis-cli", "DEL", "auth:" + author, "role:" + author);
            assertEquals(0, result.getExitCode(), result.getStderr());
        }
    }

    private Map<String, Object> registration(String name, boolean grant) {
        return Map.of("username", name, "password", PASSWORD, "device", "WEB", "crossBorderConsent", grant, "consentPolicyVersion", POLICY);
    }
    private JsonNode register(DualModeApplicationProcess process, String name, boolean grant) throws Exception {
        return request(process, "POST", "/auth/register", registration(name, grant), null);
    }
    private JsonNode login(DualModeApplicationProcess process, String name) throws Exception {
        return request(process, "POST", "/auth/login", Map.of("username", name, "password", PASSWORD, "device", "WEB"), null);
    }
    private String awaitLogin(DualModeApplicationProcess process, String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        JsonNode response;
        do {
            response = login(process, name);
            if (response.path("code").asInt() == 0) return token(response);
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Account did not become ready: " + response);
    }
    /** A session that survives a pause long enough for any pending security-effect revocation to have run. */
    private String awaitStableSession(DualModeApplicationProcess process, String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            String candidate = awaitLogin(process, name);
            Thread.sleep(1_500);
            if (request(process, "GET", "/user/info", null, candidate).path("code").asInt() == 0) return candidate;
        }
        throw new AssertionError("No stable session for " + name);
    }
    private String token(JsonNode response) {
        assertEquals(0, response.path("code").asInt(), response.toString());
        String token = response.path("data").path("tokenValue").asText();
        assertFalse(token.isEmpty());
        return token;
    }
    private JsonNode request(DualModeApplicationProcess process, String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + process.port() + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("eatwhat-test-token", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) builder.header("Content-Type", "application/json");
        return json.readTree(http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
    }
    private long id(EventFoundationTestSupport.Database db, String name) {
        return db.runtime().queryForObject("SELECT id FROM users WHERE nick_name=?", Long.class, name);
    }
    private String gid(EventFoundationTestSupport.Database db, String table, long id) {
        return db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type=? AND numeric_id=?", String.class, table, id);
    }
    private long count(EventFoundationTestSupport.Database db, String sql) {
        return db.admin().queryForObject(sql, Long.class);
    }
    private void eventually(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < end) { if (condition.getAsBoolean()) return; Thread.sleep(150); }
        assertTrue(condition.getAsBoolean());
    }

    private class Sites implements AutoCloseable {
        final EventFoundationTestSupport.Database campusDb, externalDb;
        final Map<String, String> campusConfig, externalConfig;
        DualModeApplicationProcess campus, external;
        Sites() throws Exception {
            SiteProperties a = site(SiteProperties.Mode.CAMPUS, "activity-campus");
            SiteProperties b = site(SiteProperties.Mode.EXTERNAL, "activity-external");
            campusDb = EventFoundationTestSupport.createDatabase(POSTGRES, a);
            externalDb = EventFoundationTestSupport.createDatabase(POSTGRES, b);
            int aAccount = unusedPort(), bAccount = unusedPort(), bSync = unusedPort();
            campusConfig = properties(campusDb, b, CAMPUS_REDIS, aAccount, bAccount, bSync);
            externalConfig = properties(externalDb, a, EXTERNAL_REDIS, bAccount, aAccount, bSync);
            try { restartCampus(); restartExternal(); } catch (Exception | Error failure) { close(); throw failure; }
        }
        void restartCampus() throws Exception {
            campus = new DualModeApplicationProcess(workingRoot, "campus-" + UUID.randomUUID(), campusConfig); campus.awaitReady(json);
        }
        void restartExternal() throws Exception {
            external = new DualModeApplicationProcess(workingRoot, "external-" + UUID.randomUUID(), externalConfig); external.awaitReady(json);
        }
        void killExternal() throws Exception {
            var child = ProcessHandle.of(external.pid()).orElseThrow();
            assertTrue(child.destroyForcibly());
            child.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            external.close(); external = null;
        }
        public void close() throws Exception { if (external != null) external.close(); if (campus != null) campus.close(); }
    }

    private SiteProperties site(SiteProperties.Mode mode, String id) {
        var p = new SiteProperties(); p.setMode(mode); p.setId(id); p.setInstanceId(UUID.randomUUID()); return p;
    }
    private int unusedPort() throws Exception {
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }

    private Map<String, String> properties(EventFoundationTestSupport.Database db, SiteProperties peer, GenericContainer<?> redis,
                                           int localAccountPort, int peerAccountPort, int externalSyncPort) {
        boolean campus = db.site().getMode() == SiteProperties.Mode.CAMPUS;
        var own = campus ? tls.campus() : tls.external();
        var remote = campus ? tls.external() : tls.campus();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("app.site.mode", db.site().getMode().name()); p.put("app.site.id", db.site().getId());
        p.put("app.site.instance-id", db.site().getInstanceId().toString());
        p.put("spring.datasource.url", db.url()); p.put("spring.datasource.username", db.runtimeUsername());
        p.put("spring.datasource.password", IsolatedContainers.PASSWORD);
        p.put("spring.flyway.url", db.url()); p.put("spring.flyway.user", IsolatedContainers.USER);
        p.put("spring.flyway.password", IsolatedContainers.PASSWORD);
        p.put("spring.data.redis.host", redis.getHost()); p.put("spring.data.redis.port", redis.getMappedPort(6379).toString());
        p.put("spring.data.redis.url", "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379) + "/0");
        p.put("spring.data.redis.username", ""); p.put("spring.data.redis.password", "");
        p.put("app.storage.s3.provider", campus ? "RUSTFS" : "R2");
        p.put("app.storage.s3.endpoint", campus ? "http://127.0.0.1:1" : "https://00000000000000000000000000000000.r2.cloudflarestorage.com");
        p.put("app.storage.s3.region", campus ? "us-east-1" : "auto"); p.put("app.storage.s3.bucket", "synthetic-activity-bucket");
        p.put("app.storage.s3.access-key", "synthetic-access"); p.put("app.storage.s3.secret-key", "synthetic-secret");
        p.put("app.storage.s3.path-style", "true"); p.put("app.storage.s3.public-base-url", "https://public.example.invalid/");
        p.put("app.storage.s3.public-read-prefixes", "avatar/"); p.put("app.storage.s3.allowed-read-prefixes", "avatar/,foods/,activity/");
        p.put("app.storage.s3.cdn-endpoint", "");
        // Account module (consent gating) -- the activity domain refuses to start without it.
        p.put("app.account.enabled", "true"); p.put("app.account.synthetic-only", "true"); p.put("app.account.policy-version", POLICY);
        p.put("app.account.authorization-ttl", "60s"); p.put("app.account.poll-interval", "250ms");
        p.put("app.account.request-timeout", "2s"); p.put("app.account.lease-duration", "10s");
        p.put("app.account.peer-site-id", peer.getId()); p.put("app.account.peer-instance-id", peer.getInstanceId().toString());
        p.put("app.account.peer-base-url", "https://127.0.0.1:" + peerAccountPort); p.put("app.account.listen-port", Integer.toString(localAccountPort));
        p.put("app.account.key-store", own.keyStore().toString()); p.put("app.account.key-store-password", TransportTlsFixture.PASSWORD);
        p.put("app.account.trust-store", tls.trustStore().toString()); p.put("app.account.trust-store-password", TransportTlsFixture.PASSWORD);
        p.put("app.account.peer-certificate-sha256", remote.pin());
        // Event transport carrying activity families: campus pushes/pulls, external only listens.
        p.put("app.sync.transport-enabled", "true"); p.put("app.sync.synthetic-only", "false");
        p.put("app.sync.peer-site-id", peer.getId()); p.put("app.sync.peer-instance-id", peer.getInstanceId().toString());
        if (campus) p.put("app.sync.peer-base-url", "https://127.0.0.1:" + externalSyncPort);
        else { p.put("app.sync.listen-host", "127.0.0.1"); p.put("app.sync.listen-port", Integer.toString(externalSyncPort)); }
        p.put("app.sync.key-store", own.keyStore().toString()); p.put("app.sync.key-store-password", TransportTlsFixture.PASSWORD);
        p.put("app.sync.trust-store", tls.trustStore().toString()); p.put("app.sync.trust-store-password", TransportTlsFixture.PASSWORD);
        p.put("app.sync.peer-certificate-sha256", remote.pin());
        p.put("app.sync.request-timeout", "2s"); p.put("app.sync.lease-duration", "10s"); p.put("app.sync.poll-interval", "250ms");
        p.put("app.sync.retry-base", "200ms"); p.put("app.sync.retry-max", "2s");
        p.put("app.sync.activity.enabled", "true"); p.put("app.sync.activity.peer-site-id", peer.getId());
        // Activity definitions travel as catalog objects (kind ACTIVITY).
        p.put("app.sync.catalog.enabled", "true"); p.put("app.sync.catalog.peer-site-id", peer.getId());
        return p;
    }
}
