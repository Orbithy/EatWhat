package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.JacksonConfig;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Independent JVMs, isolated PostgreSQL databases and separate Redis instances; no real cloud credentials. */
class DualModeConfigurationIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("dual-mode");
    private static final GenericContainer<?> CAMPUS_REDIS = IsolatedContainers.redis("dual-campus");
    private static final GenericContainer<?> EXTERNAL_REDIS = IsolatedContainers.redis("dual-external");
    private static final String PASSWORD = "synthetic-dual-password";
    private static final String BUCKET = "synthetic-dual-bucket";
    private static final String R2_HOST = "00000000000000000000000000000000.r2.cloudflarestorage.com";
    private final ObjectMapper mapper = new JacksonConfig().objectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @TempDir
    Path workingRoot;

    @BeforeAll
    static void startIsolatedDependencies() {
        try {
            POSTGRES.start();
            CAMPUS_REDIS.start();
            EXTERNAL_REDIS.start();
        } catch (RuntimeException failure) {
            stopIsolatedDependencies();
            throw failure;
        }
    }

    @AfterAll
    static void stopIsolatedDependencies() {
        EXTERNAL_REDIS.stop();
        CAMPUS_REDIS.stop();
        POSTGRES.stop();
    }

    @Test
    void independentSitesKeepLegacyHttpContractsAndSurviveTheOtherProcessStopping() throws Exception {
        String campusDatabase = createEmptyDatabase();
        String externalDatabase = createEmptyDatabase();
        // Own a port that never speaks S3. Presigning and startup must never connect to it.
        try (ServerSocket unavailableRustFs = unavailableService()) {
            String rustFsEndpoint = "http://127.0.0.1:" + unavailableRustFs.getLocalPort();
            Map<String, String> campusConfig = properties("CAMPUS", campusDatabase, CAMPUS_REDIS, rustFsEndpoint);
            Map<String, String> externalConfig = properties("EXTERNAL", externalDatabase, EXTERNAL_REDIS,
                    "https://" + R2_HOST);
            try (var campus = new DualModeApplicationProcess(workingRoot, "campus", campusConfig);
                 var external = new DualModeApplicationProcess(workingRoot, "external", externalConfig)) {
                JsonNode campusReady = campus.awaitReady(mapper);
                JsonNode externalReady = external.awaitReady(mapper);
                assertNotEquals(campus.pid(), external.pid());
                assertNotEquals(ProcessHandle.current().pid(), campus.pid());
                assertNotEquals(campus.port(), external.port());
                assertReadyStorage(campusReady, "CAMPUS", "RUSTFS", rustFsEndpoint, "us-east-1");
                assertReadyStorage(externalReady, "EXTERNAL", "R2", "https://" + R2_HOST, "auto");
                assertHealthy(campus);
                assertHealthy(external);

                // The same username may exist independently; numeric IDs now have disjoint site allocation.
                register(campus, "dualsame");
                register(external, "dualsame");
                String campusToken = login(campus, "dualsame");
                String externalToken = login(external, "dualsame");
                assertNotEquals(campusToken, externalToken);
                assertEquals(0, request(campus, "GET", "/auth/verify", null, campusToken).path("code").asInt());
                assertEquals(0, request(external, "GET", "/auth/verify", null, externalToken).path("code").asInt());
                assertEquals(20001, request(external, "GET", "/auth/verify", null, campusToken).path("code").asInt());
                assertEquals(20001, request(campus, "GET", "/auth/verify", null, externalToken).path("code").asInt());

                JdbcTemplate campusDb = jdbc(campusDatabase);
                JdbcTemplate externalDb = jdbc(externalDatabase);
                long campusId = campusDb.queryForObject("SELECT id FROM users WHERE nick_name='dualsame'", Long.class);
                long externalId = externalDb.queryForObject("SELECT id FROM users WHERE nick_name='dualsame'", Long.class);
                assertNotEquals(campusId, externalId, "Site allocation must prevent the old first-ID collision");
                assertTrue(campusId > 1_000_000_000L && externalId > 1_000_000_000L);
                assertEquals(1, campusId % 2);
                assertEquals(0, externalId % 2);
                assertNotEquals(
                        campusDb.queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?", String.class, campusId),
                        externalDb.queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?", String.class, externalId));
                assertEquals(campusDb.queryForList("SELECT id,name FROM provinces ORDER BY id"),
                        externalDb.queryForList("SELECT id,name FROM provinces ORDER BY id"));
                assertEquals(campusDb.queryForList("SELECT id,province_id,name FROM cities ORDER BY id"),
                        externalDb.queryForList("SELECT id,province_id,name FROM cities ORDER BY id"));
                assertPresign(campus, campusToken, campusId, rustFsEndpoint, "us-east-1");
                assertPresign(external, externalToken, externalId, "https://" + R2_HOST, "auto");
                register(campus, "campusonly");
                register(external, "external1");
                assertEquals(0, campusDb.queryForObject("SELECT count(*) FROM users WHERE nick_name='external1'", Integer.class));
                assertEquals(0, externalDb.queryForObject("SELECT count(*) FROM users WHERE nick_name='campusonly'", Integer.class));
                assertEquals(2, campusDb.queryForObject("SELECT count(*) FROM users", Integer.class));
                assertEquals(2, externalDb.queryForObject("SELECT count(*) FROM users", Integer.class));

                external.close();
                assertHealthy(campus);
                login(campus, "dualsame");
                register(campus, "campusnew");
                assertEquals(0, externalDb.queryForObject("SELECT count(*) FROM users WHERE nick_name='campusnew'", Integer.class));

                // Exercise the inverse direction after a bounded restart, reusing only that site's data.
                try (var restartedExternal = new DualModeApplicationProcess(workingRoot, "external-restarted", externalConfig)) {
                    restartedExternal.awaitReady(mapper);
                    campus.close();
                    assertHealthy(restartedExternal);
                    login(restartedExternal, "dualsame");
                    register(restartedExternal, "external2");
                    assertEquals(0, campusDb.queryForObject("SELECT count(*) FROM users WHERE nick_name='external2'", Integer.class));
                }
            }
        }
    }

    @Test
    void missingLocalPostgresFailsBeforeReadinessWithoutFallingBackToHostConfiguration() throws Exception {
        try (ServerSocket unavailableDatabase = unavailableService()) {
            var config = properties("CAMPUS", "unavailable", CAMPUS_REDIS, "http://127.0.0.1:1");
            String url = "jdbc:postgresql://127.0.0.1:" + unavailableDatabase.getLocalPort()
                    + "/unavailable?connectTimeout=2&socketTimeout=2";
            config.put("spring.datasource.url", url);
            config.put("spring.flyway.url", url);
            try (var child = new DualModeApplicationProcess(workingRoot, "missing-postgres", config)) {
                String log = child.awaitStartupFailure();
                assertTrue(log.contains("PSQLException") || log.contains("Unable to obtain connection"),
                        "Failure must identify the unavailable PostgreSQL connection, not another missing bean");
            }
        }
    }

    @Test
    void missingLocalRedisFailsBeforeReadinessWhilePostgresRemainsAvailable() throws Exception {
        String database = createEmptyDatabase();
        try (ServerSocket unavailableRedis = unavailableService()) {
            var config = properties("EXTERNAL", database, EXTERNAL_REDIS, "https://" + R2_HOST);
            config.put("spring.data.redis.host", "127.0.0.1");
            config.put("spring.data.redis.port", Integer.toString(unavailableRedis.getLocalPort()));
            config.put("spring.data.redis.url", "redis://127.0.0.1:" + unavailableRedis.getLocalPort() + "/0");
            try (var child = new DualModeApplicationProcess(workingRoot, "missing-redis", config)) {
                String log = child.awaitStartupFailure();
                assertTrue(log.toLowerCase().contains("redis"), "Failure must identify the local Redis dependency");
                assertEquals(1, jdbc(database).queryForObject("SELECT 1", Integer.class));
            }
        }
    }

    @Test
    void incompatibleSiteAndStorageModesFailBeforeMigrationsOrReadiness() throws Exception {
        String database = createEmptyDatabase();
        var config = properties("CAMPUS", database, CAMPUS_REDIS, "https://" + R2_HOST);
        config.put("app.storage.s3.provider", "R2");
        config.put("app.storage.s3.region", "auto");
        try (var child = new DualModeApplicationProcess(workingRoot, "conflicting-mode", config)) {
            String log = child.awaitStartupFailure();
            assertTrue(log.contains("app.storage.s3.provider") || log.contains("RUSTFS"),
                    "Failure must identify the incompatible storage provider");
            assertNull(jdbc(database).queryForObject("SELECT to_regclass('public.flyway_schema_history')::text", String.class),
                    "Site validation must happen before Flyway writes any schema history");
        }
    }

    private LinkedHashMap<String, String> properties(String mode, String database, GenericContainer<?> redis,
                                                   String storageEndpoint) {
        var values = new LinkedHashMap<String, String>();
        // Test fixtures create accounts through the closed-by-default username registration.
        values.put("app.auth.username-registration-enabled", "true");
        values.put("app.site.mode", mode);
        values.put("app.site.id", mode.equals("CAMPUS") ? "campus-test" : "singapore-test");
        values.put("app.site.instance-id", mode.equals("CAMPUS")
                ? "00000000-0000-4000-8000-000000000001" : "00000000-0000-4000-8000-000000000002");
        values.put("spring.datasource.url", databaseUrl(database));
        values.put("spring.datasource.username", runtimeRole(database));
        values.put("spring.datasource.password", IsolatedContainers.PASSWORD);
        values.put("spring.flyway.url", databaseUrl(database));
        values.put("spring.flyway.user", IsolatedContainers.USER);
        values.put("spring.flyway.password", IsolatedContainers.PASSWORD);
        values.put("spring.data.redis.host", redis.getHost());
        values.put("spring.data.redis.port", redis.getMappedPort(6379).toString());
        values.put("spring.data.redis.url", "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379) + "/0");
        values.put("spring.data.redis.username", "");
        values.put("spring.data.redis.password", "");
        values.put("app.storage.s3.provider", mode.equals("CAMPUS") ? "RUSTFS" : "R2");
        values.put("app.storage.s3.endpoint", storageEndpoint);
        values.put("app.storage.s3.region", mode.equals("CAMPUS") ? "us-east-1" : "auto");
        values.put("app.storage.s3.bucket", BUCKET);
        values.put("app.storage.s3.access-key", "synthetic-dual-access");
        values.put("app.storage.s3.secret-key", "synthetic-dual-secret");
        values.put("app.storage.s3.path-style", "true");
        values.put("app.storage.s3.cdn-endpoint", "");
        values.put("app.storage.s3.public-base-url", "https://public.example.invalid/" + mode.toLowerCase());
        values.put("app.storage.s3.allowed-read-prefixes", "avatar/,activity/,foods/,restaurants/");
        values.put("app.storage.s3.public-read-prefixes", "avatar/");
        values.put("app.storage.s3.allowed-content-types", "image/png,image/jpeg");
        return values;
    }

    private String createEmptyDatabase() throws Exception {
        String database = "eatwhat_dual_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(
                IsolatedContainers.jdbcUrl(POSTGRES, IsolatedContainers.DATABASE),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            statement.execute("CREATE ROLE " + runtimeRole(database) + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '" + IsolatedContainers.PASSWORD + "'");
        }
        try (var connection = DriverManager.getConnection(databaseUrl(database),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + runtimeRole(database));
            statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO " + runtimeRole(database));
            statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO " + runtimeRole(database));
        }
        return database;
    }

    private String runtimeRole(String database) {
        // Database names are generated internally; the role remains below PostgreSQL's 63-byte limit.
        return database + "_app";
    }

    private String databaseUrl(String database) {
        return IsolatedContainers.jdbcUrl(POSTGRES, database).replace("connectTimeout=5", "connectTimeout=2")
                .replace("socketTimeout=10", "socketTimeout=2");
    }

    private JdbcTemplate jdbc(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(databaseUrl(database),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }

    private ServerSocket unavailableService() throws Exception {
        return new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    }

    private void assertReadyStorage(JsonNode ready, String mode, String provider, String endpoint, String region) {
        assertEquals(mode, ready.path("mode").asText());
        assertEquals(provider, ready.path("provider").asText());
        assertEquals(mode.equals("CAMPUS") ? "campus-test" : "singapore-test", ready.path("siteId").asText());
        assertEquals("https://public.example.invalid/" + mode.toLowerCase() + "/avatar/probe.png",
                ready.path("publicUrl").asText());
        assertSignedUrl(ready.path("privateUrl").asText(), endpoint, "foods/probe.png", region);
    }

    private void assertPresign(DualModeApplicationProcess child, String token, long id, String endpoint, String region)
            throws Exception {
        JsonNode response = request(child, "POST", "/upload/presign",
                "{\"biz\":\"avatar\",\"fileName\":\"avatar.png\",\"contentType\":\"image/png\",\"fileSize\":12}", token);
        assertEquals(0, response.path("code").asInt());
        JsonNode data = response.path("data");
        String key = data.path("key").asText();
        assertTrue(key.matches("avatar/" + id + "/[0-9]{6}/[a-f0-9]{32}\\.png"));
        assertTrue(data.path("expireAt").asLong() > System.currentTimeMillis());
        assertSignedUrl(data.path("putUrl").asText(), endpoint, key, region);
        // This checks the existing signature contract only; no bytes were uploaded or replicated.
    }

    private void assertSignedUrl(String value, String endpoint, String key, String region) {
        URI url = URI.create(value);
        assertEquals(URI.create(endpoint).getAuthority(), url.getAuthority());
        assertEquals(URI.create(endpoint).getScheme(), url.getScheme());
        assertEquals("/" + BUCKET + "/" + key, url.getPath());
        String query = URLDecoder.decode(url.getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(query.contains("X-Amz-Signature="));
        assertTrue(query.contains("/" + region + "/s3/aws4_request"), "Credential scope must use the site's region");
    }

    private void register(DualModeApplicationProcess child, String username) throws Exception {
        JsonNode response = credentials(child, "register", username);
        assertEquals(0, response.path("code").asInt());
        assertFalse(response.path("data").path("tokenValue").asText().isBlank());
    }

    private String login(DualModeApplicationProcess child, String username) throws Exception {
        JsonNode response = credentials(child, "login", username);
        assertEquals(0, response.path("code").asInt());
        assertEquals("eatwhat-test-token", response.path("data").path("tokenName").asText());
        assertTrue(response.path("data").path("isLogin").asBoolean());
        String token = response.path("data").path("tokenValue").asText();
        assertFalse(token.isBlank());
        return token;
    }

    private JsonNode credentials(DualModeApplicationProcess child, String action, String username) throws Exception {
        return request(child, "POST", "/auth/" + action,
                mapper.writeValueAsString(Map.of("username", username, "password", PASSWORD, "device", "WEB")), null);
    }

    private JsonNode request(DualModeApplicationProcess child, String method, String path, String body, String token)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + child.port() + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) {
            request.header("eatwhat-test-token", "Bearer " + token);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode result = mapper.readTree(response.body());
        assertTrue(result.path("code").isIntegralNumber());
        assertTrue(result.path("msg").isString());
        assertTrue(result.path("timestamp").isIntegralNumber());
        assertTrue(result.has("data"));
        return result;
    }

    private void assertHealthy(DualModeApplicationProcess child) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + child.port() + "/actuator/health"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("UP", mapper.readTree(response.body()).path("status").asText());
    }
}
