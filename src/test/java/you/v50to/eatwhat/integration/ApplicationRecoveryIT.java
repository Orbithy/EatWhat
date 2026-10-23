package you.v50to.eatwhat.integration;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import work.foofish.smsverify.SmsTemplate;
import you.v50to.eatwhat.EatwhatApplication;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.service.storage.S3CompatibleStorageService;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Real HTTP, Spring, Flyway, PostgreSQL and Redis; mail, SMS and S3 never leave the test. */
@SpringBootTest(classes = EatwhatApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.location=classpath:/application-integration.properties",
                "spring.config.additional-location=",
                "spring.config.import=",
                "spring.autoconfigure.exclude=work.foofish.smsverify.SmsAutoConfiguration",
                "logging.config=classpath:logback-test.xml"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationRecoveryIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("application");
    private static final GenericContainer<?> REDIS = IsolatedContainers.redis("application");
    private static final String APPLICATION_DATABASE = "eatwhat_application_test";
    private static final String PASSWORD = "synthetic-password-123";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private ConfigurableEnvironment environment;
    @Autowired
    private RestaurantMapper restaurants;

    @MockitoBean
    private JavaMailSender mail;
    @MockitoBean
    private SmsTemplate sms;
    @MockitoBean
    private S3CompatibleStorageService storage;

    @DynamicPropertySource
    static void isolatedEndpoints(DynamicPropertyRegistry registry) {
        try {
            POSTGRES.start();
            // The image preinstalls PostGIS in POSTGRES_DB; the application must migrate a
            // genuinely empty database without enabling Flyway's baseline-on-migrate shortcut.
            try (var connection = DriverManager.getConnection(
                    IsolatedContainers.jdbcUrl(POSTGRES, IsolatedContainers.DATABASE),
                    IsolatedContainers.USER, IsolatedContainers.PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + APPLICATION_DATABASE + " TEMPLATE template0");
            }
            REDIS.start();
        } catch (RuntimeException | SQLException failure) {
            REDIS.stop();
            POSTGRES.stop();
            throw new IllegalStateException("Cannot initialize isolated test services", failure);
        }
        // Test fixtures create accounts through the closed-by-default username registration.
        registry.add("app.auth.username-registration-enabled", () -> "true");
        registry.add("spring.datasource.url", ApplicationRecoveryIT::applicationJdbcUrl);
        registry.add("spring.datasource.username", () -> IsolatedContainers.USER);
        registry.add("spring.datasource.password", () -> IsolatedContainers.PASSWORD);
        registry.add("spring.flyway.url", () -> IsolatedContainers.jdbcUrl(POSTGRES, APPLICATION_DATABASE));
        registry.add("spring.flyway.user", () -> IsolatedContainers.USER);
        registry.add("spring.flyway.password", () -> IsolatedContainers.PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
        registry.add("spring.flyway.clean-disabled", () -> "true");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.username", () -> "");
        registry.add("sentry.dsn", () -> "");
        registry.add("sentry.enabled", () -> "false");
    }

    @AfterAll
    static void stopIsolatedServices() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @AfterEach
    void noExternalSideEffects() {
        verifyNoInteractions(mail, sms);
        // Only explicitly verified calls to the storage test double are permitted.
        verifyNoMoreInteractions(storage);
        for (String table : java.util.List.of("outbox", "delivery", "inbox", "entity_state", "command_dedup")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_sync." + table, Integer.class),
                    "Existing HTTP and mapper operations must not activate event capture: " + table);
        }
    }

    @Test
    void realContextMigratesAndSeedsOnlyTheIsolatedDatabase() {
        assertEquals(applicationJdbcUrl(),
                environment.getProperty("spring.datasource.url"));
        assertEquals(REDIS.getMappedPort(6379), environment.getProperty("spring.data.redis.port", Integer.class));
        environment.getPropertySources().forEach(source ->
                assertFalse(source.getName().contains("application.yml"), "Host application.yml must not be loaded"));
        assertEquals(50, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM provinces", Integer.class) > 0);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM cities", Integer.class) > 0);
        redis.opsForValue().set("eatwhat:test:context", "ready", Duration.ofMinutes(1));
        assertEquals("ready", redis.opsForValue().get("eatwhat:test:context"));
    }

    @Test
    void registrationLoginAndLogoutUseRealDatabaseAndRedisSessions() throws Exception {
        JsonNode registered = credentials("register", "httpuser");
        assertEquals(0, registered.path("code").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE nick_name='httpuser'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM privacy p JOIN users u ON u.id=p.account_id WHERE u.nick_name='httpuser'", Integer.class));
        assertNotEquals(PASSWORD, jdbc.queryForObject("SELECT password_hash FROM users WHERE nick_name='httpuser'", String.class));
        JsonNode loggedIn = credentials("login", "httpuser");
        assertEquals(0, loggedIn.path("code").asInt());
        String token = loggedIn.path("data").path("tokenValue").asText();
        assertFalse(token.isBlank());
        assertEquals(0, request("GET", "/auth/verify", null, token).path("code").asInt());
        assertEquals(0, request("POST", "/auth/logout", "", token).path("code").asInt());
        assertEquals(20001, request("GET", "/auth/verify", null, token).path("code").asInt());
    }

    @Test
    void productionAnnotationsRejectAnonymousAndNonAdminHttpRequests() throws Exception {
        // These controller methods do not call StpUtil themselves; rejection must come from
        // the real @SaCheckLogin / @SaCheckRole infrastructure in the application context.
        assertEquals(20001, request("GET", "/api/location/provinces", null, null).path("code").asInt());
        JsonNode registered = credentials("register", "roleuser");
        assertEquals(0, registered.path("code").asInt());
        String token = registered.path("data").path("tokenValue").asText();
        assertFalse(token.isBlank());
        assertEquals("user", jdbc.queryForObject("SELECT role FROM users WHERE nick_name='roleuser'", String.class));
        assertEquals(0, request("GET", "/api/location/provinces", null, token).path("code").asInt());
        assertEquals(20004, request("GET", "/admin/summary", null, token).path("code").asInt());
    }

    @Test
    void administrativeBanBlocksEveryLoginAliasEvenAfterRedisBanMarkerIsLostAndUnbanRestoresAccess() throws Exception {
        JsonNode admin = credentials("register", "fixadmin");
        assertEquals(0, admin.path("code").asInt());
        String adminToken = admin.path("data").path("tokenValue").asText();
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE nick_name='fixadmin'", Long.class);
        jdbc.update("UPDATE users SET role='admin' WHERE id=?", adminId);
        redis.delete("role:" + adminId);

        JsonNode target = credentials("register", "bantarget");
        assertEquals(0, target.path("code").asInt());
        String originalToken = target.path("data").path("tokenValue").asText();
        long targetId = jdbc.queryForObject("SELECT id FROM users WHERE nick_name='bantarget'", Long.class);
        jdbc.update("INSERT INTO contacts(account_id,email,phone) VALUES (?,?,?)",
                targetId, "bantarget@example.invalid", "13900000000");

        long banStartedAt = System.currentTimeMillis();
        assertEquals(0, request("POST", "/user/ban/" + targetId,
                "{\"reason\":\"synthetic regression\"}", adminToken).path("code").asInt());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT banned FROM users WHERE id=?", Boolean.class, targetId));
        assertEquals("synthetic regression", jdbc.queryForObject("SELECT ban_reason FROM users WHERE id=?", String.class, targetId));
        var bannedAt = jdbc.queryForObject("SELECT banned_at FROM users WHERE id=?", java.time.OffsetDateTime.class, targetId);
        assertNotNull(bannedAt);
        assertTrue(bannedAt.toInstant().toEpochMilli() >= banStartedAt);
        assertTrue(bannedAt.toInstant().toEpochMilli() <= System.currentTimeMillis());
        assertEquals(20001, request("GET", "/auth/verify", null, originalToken).path("code").asInt());
        String[] aliases = {"bantarget", "bantarget@example.invalid", "13900000000"};
        for (String alias : aliases) {
            assertDisabledLogin(alias);
        }

        // Simulate loss of only this test account's cache marker, never flush Redis.
        StpUtil.untieDisable(targetId);
        assertFalse(StpUtil.isDisable(targetId));
        for (String alias : aliases) {
            assertDisabledLogin(alias);
        }
        assertNull(StpUtil.getSessionByLoginId(targetId, false));
        assertTrue(StpUtil.getTokenValueListByLoginId(targetId).isEmpty());

        assertEquals(0, request("POST", "/user/unban/" + targetId, "", adminToken).path("code").asInt());
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT banned FROM users WHERE id=?", Boolean.class, targetId));
        assertNull(jdbc.queryForObject("SELECT ban_reason FROM users WHERE id=?", String.class, targetId));
        assertNull(jdbc.queryForObject("SELECT banned_at FROM users WHERE id=?", java.time.OffsetDateTime.class, targetId));
        for (String alias : aliases) {
            JsonNode restored = credentials("login", alias);
            assertEquals(0, restored.path("code").asInt());
            assertFalse(restored.path("data").path("tokenValue").asText().isBlank());
        }
    }

    @Test
    void unverifiedUserCanPresignAvatarsThroughRealAopButCannotPresignOtherBusinessTypes() throws Exception {
        JsonNode registered = credentials("register", "avatarusr");
        assertEquals(0, registered.path("code").asInt());
        String token = registered.path("data").path("tokenValue").asText();
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE nick_name='avatarusr'", Long.class);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verifications WHERE account_id=?", Integer.class, userId));
        when(storage.presignPut(userId, UploadBiz.AVATAR, "single.png", "image/png", 12L))
                .thenReturn(new ObjectStorageService.PresignedUpload(
                        "https://storage.example.invalid/single", "avatar/synthetic-single.png", 123456789L));
        when(storage.presignPut(userId, UploadBiz.AVATAR, "batch.png", "image/png", 12L))
                .thenReturn(new ObjectStorageService.PresignedUpload(
                        "https://storage.example.invalid/batch", "avatar/synthetic-batch.png", 123456789L));

        for (boolean batch : new boolean[]{false, true}) {
            String path = batch ? "/upload/presign/batch" : "/upload/presign";
            String file = batch ? "batch.png" : "single.png";
            String body = uploadBody(batch, "avatar", file);
            assertEquals(20001, request("POST", path, body, null).path("code").asInt());
            JsonNode response = request("POST", path, body, token);
            assertEquals(0, response.path("code").asInt());
            JsonNode signed = batch ? response.path("data").path("files").get(0) : response.path("data");
            assertEquals(batch ? "avatar/synthetic-batch.png" : "avatar/synthetic-single.png", signed.path("key").asText());
            assertTrue(signed.path("putUrl").isString());
            assertTrue(signed.path("expireAt").isIntegralNumber());
            for (String biz : new String[]{"activity", "foods", "restaurants", null}) {
                JsonNode denied = request("POST", path, uploadBody(batch, biz, file), token);
                assertEquals(20004, denied.path("code").asInt());
                assertEquals("权限不足：用户未验证", denied.path("msg").asText());
                assertTrue(denied.path("data").isNull());
            }
        }
        verify(storage).presignPut(userId, UploadBiz.AVATAR, "single.png", "image/png", 12L);
        verify(storage).presignPut(userId, UploadBiz.AVATAR, "batch.png", "image/png", 12L);
    }

    private void assertDisabledLogin(String alias) throws Exception {
        JsonNode denied = credentials("login", alias);
        assertEquals(30002, denied.path("code").asInt());
        assertEquals("用户被禁用", denied.path("msg").asText());
        assertTrue(denied.path("data").isNull());
    }

    private String uploadBody(boolean batch, String biz, String fileName) throws Exception {
        var file = java.util.Map.of("fileName", fileName, "contentType", "image/png", "fileSize", 12);
        var body = new java.util.LinkedHashMap<String, Object>();
        if (biz != null) {
            body.put("biz", biz);
        }
        if (batch) {
            body.put("files", java.util.List.of(file));
        } else {
            body.putAll(file);
        }
        return mapper.writeValueAsString(body);
    }

    @Test
    void restaurantMapperRoundTripsTypeHandlersAndFiltersByHubInItsResultMap() {
        long account = jdbc.queryForObject("""
                INSERT INTO users(nick_name,password_hash)
                VALUES ('mapperuser','synthetic-not-a-password') RETURNING id
                """, Long.class);
        long selectedHub = insertSyntheticHub("mapper-selected");
        long otherHub = insertSyntheticHub("mapper-other");
        long originalTime = Instant.parse("2020-01-02T03:04:05.123Z").toEpochMilli();
        Restaurant selected = syntheticRestaurant(account, selectedHub, "mapper-check-selected", "synthetic-mapper-a", originalTime);
        Restaurant other = syntheticRestaurant(account, otherHub, "mapper-check-other", "synthetic-mapper-b", originalTime);
        assertEquals(1, restaurants.insert(selected));
        assertEquals(1, restaurants.insert(other));
        assertNotNull(selected.getId(), "The existing numeric generated-key contract must remain intact");

        Restaurant loaded = restaurants.selectById(selected.getId());
        assertRestaurantMapping(selected, loaded, originalTime);
        var filtered = restaurants.searchRestaurants("mapper-check", selectedHub, 117.25, 36.65, 1000.0, new Page<>(1, 10));
        assertEquals(1, filtered.getTotal());
        assertEquals(1, filtered.getRecords().size());
        assertRestaurantMapping(selected, filtered.getRecords().getFirst(), originalTime);
        var unfiltered = restaurants.searchRestaurants("mapper-check", null, 117.25, 36.65, 1000.0, new Page<>(1, 10));
        assertEquals(2, unfiltered.getTotal(), "The hub predicate must distinguish colocated matching restaurants");

        selected.setPictureUrl(new String[]{"restaurants/synthetic-replacement.png"});
        assertEquals(1, restaurants.updateById(selected));
        Restaurant updated = restaurants.selectById(selected.getId());
        assertArrayEquals(selected.getPictureUrl(), updated.getPictureUrl());
        assertEquals(originalTime, updated.getCreatedAt());
        assertNotNull(updated.getUpdatedAt());
        assertTrue(updated.getUpdatedAt() > originalTime, "The DB updated_at trigger must survive mapper writes");
    }

    @Test
    void redisOutageRollsBackRegistrationAndExistingSessionWorksAfterRecovery() throws Exception {
        JsonNode registered = credentials("register", "survivor");
        assertEquals(0, registered.path("code").asInt());
        String token = registered.path("data").path("tokenValue").asText();
        assertEquals(0, request("GET", "/auth/verify", null, token).path("code").asInt());
        redis.opsForValue().set("eatwhat:test:recovery", "before-outage", Duration.ofMinutes(2));

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThrows(DataAccessException.class, () -> redis.opsForValue().get("eatwhat:test:recovery"));
            JsonNode failed = credentials("register", "retryuser");
            assertNotEquals(0, failed.path("code").asInt(), "A Redis failure must not report successful registration");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM users WHERE nick_name='retryuser'", Integer.class));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        awaitRedisRecovery();
        assertEquals("before-outage", redis.opsForValue().get("eatwhat:test:recovery"));
        assertEquals(0, request("GET", "/auth/verify", null, token).path("code").asInt());
        assertEquals(0, credentials("register", "retryuser").path("code").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE nick_name='retryuser'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM privacy p LEFT JOIN users u ON u.id=p.account_id WHERE u.id IS NULL", Integer.class));
    }

    @Test
    void postgresOutageReturnsFailureAndSameHttpRequestSucceedsAfterRecovery() throws Exception {
        String path = "/auth/checkUsername?username=dbresume";
        assertEquals(0, request("POST", path, "", null).path("code").asInt());

        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            JsonNode failed = request("POST", path, "", null);
            assertNotEquals(0, failed.path("code").asInt(), "A database outage must not report username availability");
        } finally {
            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }

        awaitPostgresRecovery();
        assertEquals(0, request("POST", path, "", null).path("code").asInt());
    }

    private static String applicationJdbcUrl() {
        // Driver URL options take precedence over Hikari datasource properties. Keep the
        // application outage bounded while the dedicated Flyway connection retains 10 seconds.
        return IsolatedContainers.jdbcUrl(POSTGRES, APPLICATION_DATABASE)
                .replace("socketTimeout=10", "socketTimeout=2");
    }

    private long insertSyntheticHub(String name) {
        return jdbc.queryForObject("""
                INSERT INTO hub(name,center,gcj_lng,gcj_lat)
                VALUES (?,ST_GeomFromText('POINT(117.25 36.65)',4326),117.25,36.65) RETURNING id
                """, Long.class, name);
    }

    private Restaurant syntheticRestaurant(long account, long hub, String name, String poi, long timestamp) {
        Restaurant restaurant = new Restaurant();
        restaurant.setAccountId(account);
        restaurant.setHubId(hub);
        restaurant.setName(name);
        restaurant.setPOI(poi);
        restaurant.setLocation(new GeometryFactory(new PrecisionModel(), 4326)
                .createPoint(new Coordinate(117.25, 36.65)));
        restaurant.setGcjLng(117.25);
        restaurant.setGcjLat(36.65);
        restaurant.setPictureUrl(new String[]{"restaurants/synthetic-a.png", "restaurants/synthetic-b.png"});
        restaurant.setCreatedAt(timestamp);
        restaurant.setUpdatedAt(timestamp);
        return restaurant;
    }

    private void assertRestaurantMapping(Restaurant expected, Restaurant actual, long timestamp) {
        assertNotNull(actual);
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getAccountId(), actual.getAccountId());
        assertEquals(expected.getHubId(), actual.getHubId());
        assertEquals(expected.getPOI(), actual.getPOI());
        assertNotNull(actual.getLocation());
        assertEquals(4326, actual.getLocation().getSRID());
        assertEquals(117.25, actual.getLocation().getX(), 0.000000001);
        assertEquals(36.65, actual.getLocation().getY(), 0.000000001);
        assertArrayEquals(expected.getPictureUrl(), actual.getPictureUrl());
        assertEquals(timestamp, actual.getCreatedAt());
        assertEquals(timestamp, actual.getUpdatedAt());
    }

    private JsonNode credentials(String action, String username) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of(
                "username", username, "password", PASSWORD, "device", "WEB"));
        return request("POST", "/auth/" + action, body, null);
    }

    private JsonNode request(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("eatwhat-test-token", "Bearer " + token);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Existing Result envelope uses HTTP 200 for business errors");
        JsonNode result = mapper.readTree(response.body());
        assertTrue(result.isObject(), "Response must be a Result JSON object");
        assertTrue(result.path("code").isIntegralNumber(), "Missing/non-numeric code must never default to success");
        assertTrue(result.path("msg").isString(), "Result.msg must be a string");
        assertTrue(result.path("timestamp").isIntegralNumber(), "Result.timestamp must remain epoch milliseconds");
        assertTrue(result.has("data"), "Result.data must be present, including null error payloads");
        return result;
    }

    private void awaitRedisRecovery() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        DataAccessException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if ("before-outage".equals(redis.opsForValue().get("eatwhat:test:recovery"))) {
                    return;
                }
            } catch (DataAccessException failure) {
                lastFailure = failure;
            }
            Thread.sleep(100);
        }
        fail("Redis did not recover within 10 seconds", lastFailure);
    }

    private void awaitPostgresRecovery() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        DataAccessException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class))) {
                    return;
                }
            } catch (DataAccessException failure) {
                lastFailure = failure;
            }
            Thread.sleep(100);
        }
        fail("PostgreSQL did not recover within 10 seconds", lastFailure);
    }
}
