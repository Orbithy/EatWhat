package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.JacksonConfig;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.utils.ProvinceCityManifest;
import you.v50to.eatwhat.utils.ProvincesCitiesInitializer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual migrations and transaction proxies against test-owned PostgreSQL; no configured application endpoint. */
class DictionaryManifestIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("dictionary");
    private final ProvinceCityManifest manifest = ProvinceCityManifest.load(new JacksonConfig().objectMapper());
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createEmptyDatabase() throws SQLException {
        jdbc = newDatabase();
    }

    @Test
    void independentCampusAndExternalDatabasesPublishExactlyTheSameNumericReferences() throws Exception {
        JdbcTemplate external = newDatabase();
        try (var campusContext = seedContext(jdbc, SiteProperties.Mode.CAMPUS);
             var externalContext = seedContext(external, SiteProperties.Mode.EXTERNAL)) {
            campusContext.getBean(ProvincesCitiesInitializer.class).run();
            externalContext.getBean(ProvincesCitiesInitializer.class).run();
            assertPublished(jdbc);
            assertPublished(external);
            assertEquals(referenceRows(jdbc), referenceRows(external));
            assertEquals(15, jdbc.queryForObject("SELECT id FROM provinces WHERE name='山东省'", Integer.class));
            assertEquals(179, jdbc.queryForObject("SELECT id FROM cities WHERE province_id=15 AND name='济南市'", Integer.class));
        }
    }

    @Test
    void canonicalHistoricalSubsetKeepsIdsTimestampsAndUserForeignKeysWhileCompleting() throws Exception {
        jdbc.update("INSERT INTO provinces(id,name,created_at) VALUES (15,'山东省','2020-01-02T00:00:00Z')");
        jdbc.update("INSERT INTO cities(id,province_id,name,created_at) VALUES (179,15,'济南市','2020-01-03T00:00:00Z')");
        long user = jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES ('dict-history','synthetic') RETURNING id", Long.class);
        jdbc.update("""
                INSERT INTO user_info(id,location_province_id,location_city_id,hometown_province_id,hometown_city_id)
                VALUES (?,15,179,15,179)
                """, user);
        var province = jdbc.queryForMap("SELECT * FROM provinces WHERE id=15");
        var city = jdbc.queryForMap("SELECT * FROM cities WHERE id=179");
        var profile = jdbc.queryForMap("SELECT * FROM user_info WHERE id=?", user);

        try (var context = seedContext(jdbc, SiteProperties.Mode.CAMPUS)) {
            context.getBean(ProvincesCitiesInitializer.class).run();
        }
        assertPublished(jdbc);
        assertEquals(province, jdbc.queryForMap("SELECT * FROM provinces WHERE id=15"));
        assertEquals(city, jdbc.queryForMap("SELECT * FROM cities WHERE id=179"));
        assertEquals(profile, jdbc.queryForMap("SELECT * FROM user_info WHERE id=?", user));
    }

    @Test
    void lateCityFailureRollsBackRowsAndReceiptThenRetryUsesTheSamePublishedIds() throws Exception {
        jdbc.execute("CREATE SEQUENCE synthetic_dictionary_attempts");
        jdbc.execute("""
                CREATE FUNCTION synthetic_dictionary_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM nextval('synthetic_dictionary_attempts');
                    IF NEW.id = 17 THEN
                        RAISE EXCEPTION 'synthetic dictionary interruption';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER synthetic_dictionary_failure BEFORE INSERT ON cities FOR EACH ROW EXECUTE FUNCTION synthetic_dictionary_failure()");
        try (var context = seedContext(jdbc, SiteProperties.Mode.CAMPUS)) {
            ProvincesCitiesInitializer initializer = context.getBean(ProvincesCitiesInitializer.class);
            assertTrue(AopUtils.isAopProxy(initializer));
            assertThrows(DataAccessException.class, () -> initializer.run());
            assertTrue(jdbc.queryForObject("SELECT last_value FROM synthetic_dictionary_attempts", Long.class) >= 17);
            assertEquals(0, count(jdbc, "provinces"));
            assertEquals(0, count(jdbc, "cities"));
            assertEquals(0, count(jdbc, "reference_dictionary_manifest"));
            jdbc.execute("DROP TRIGGER synthetic_dictionary_failure ON cities");
            initializer.run();
            assertPublished(jdbc);
            assertEquals(1, jdbc.queryForObject("SELECT id FROM cities WHERE province_id=1 AND name='东城区'", Integer.class));
        }
    }

    @Test
    void repeatedPublishedStartupDoesNotChangeRowsReceiptOrAllocateSequenceValues() throws Exception {
        try (var context = seedContext(jdbc, SiteProperties.Mode.CAMPUS)) {
            ProvincesCitiesInitializer initializer = context.getBean(ProvincesCitiesInitializer.class);
            initializer.run();
            var rows = allRows();
            var sequences = sequenceSnapshot();
            initializer.run();
            assertEquals(rows, allRows());
            assertEquals(sequences, sequenceSnapshot());
            // Rolling back the application to the legacy seed path does not touch complete rows.
            try (var legacy = seedContext(jdbc, SiteProperties.Mode.LEGACY)) {
                legacy.getBean(ProvincesCitiesInitializer.class).run();
            }
            assertEquals(rows, allRows());
            assertEquals(sequences, sequenceSnapshot());
        }
    }

    @Test
    void referenceSequencesAdvancePastExplicitIdsWithoutRewindingHigherHistory() throws Exception {
        jdbc.queryForObject("SELECT setval('provinces_id_seq', 1000, false)", Long.class);
        jdbc.queryForObject("SELECT setval('cities_id_seq', 451, false)", Long.class);
        try (var context = seedContext(jdbc, SiteProperties.Mode.CAMPUS)) {
            context.getBean(ProvincesCitiesInitializer.class).run();
        }
        assertEquals(1000L, jdbc.queryForObject("SELECT nextval('provinces_id_seq')", Long.class));
        assertEquals(452L, jdbc.queryForObject("SELECT nextval('cities_id_seq')", Long.class));
        assertPublished(jdbc);
    }

    @Test
    void restrictedRuntimeRoleInitializesWithoutObtainingArbitrarySequenceUpdatePrivileges() throws Exception {
        String role = "dict_runtime_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD 'synthetic-dictionary-only'");
        jdbc.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + role);
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + role);
        jdbc.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + role);
        String url = ((DriverManagerDataSource) jdbc.getDataSource()).getUrl();
        var runtime = new JdbcTemplate(new DriverManagerDataSource(url, role, "synthetic-dictionary-only"));
        try (var context = seedContext(runtime, SiteProperties.Mode.CAMPUS)) {
            var initializer = context.getBean(ProvincesCitiesInitializer.class);
            initializer.run();
            initializer.run();
        }
        assertPublished(runtime);
        assertEquals(32L, runtime.queryForObject("SELECT nextval('public.provinces_id_seq')", Long.class));
        assertEquals(452L, runtime.queryForObject("SELECT nextval('public.cities_id_seq')", Long.class));
        for (String sequence : List.of("users_id_seq", "provinces_id_seq", "cities_id_seq")) {
            var failure = assertThrows(DataAccessException.class,
                    () -> runtime.queryForObject("SELECT setval('public." + sequence + "', 1, false)", Long.class));
            assertEquals("42501", ((SQLException) failure.getMostSpecificCause()).getSQLState());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"province-name", "province-id", "city-parent", "city-id", "extra-province", "extra-city"})
    void incompatibleHistoryIsRejectedWithoutChangingAnyExistingRowsOrSequences(String mutation) {
        switch (mutation) {
            case "province-name" -> jdbc.update("INSERT INTO provinces(id,name) VALUES (1,'synthetic-wrong-name')");
            case "province-id" -> jdbc.update("INSERT INTO provinces(id,name) VALUES (1000,'北京市')");
            case "city-parent" -> {
                jdbc.update("INSERT INTO provinces(id,name) VALUES (2,'天津市')");
                jdbc.update("INSERT INTO cities(id,province_id,name) VALUES (1,2,'东城区')");
            }
            case "city-id" -> {
                jdbc.update("INSERT INTO provinces(id,name) VALUES (1,'北京市')");
                jdbc.update("INSERT INTO cities(id,province_id,name) VALUES (1000,1,'东城区')");
            }
            case "extra-province" -> jdbc.update("INSERT INTO provinces(id,name) VALUES (1000,'synthetic-manual-province')");
            case "extra-city" -> {
                jdbc.update("INSERT INTO provinces(id,name) VALUES (1,'北京市')");
                jdbc.update("INSERT INTO cities(id,province_id,name) VALUES (1000,1,'synthetic-manual-city')");
            }
            default -> fail("Unknown test mutation");
        }
        var before = allRows();
        var sequences = sequenceSnapshot();
        try (var context = seedContext(jdbc, SiteProperties.Mode.EXTERNAL)) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> context.getBean(ProvincesCitiesInitializer.class).run());
            assertTrue(failure.getMessage().contains("preserve existing IDs"));
        }
        assertEquals(before, allRows());
        assertEquals(sequences, sequenceSnapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "content", "source"})
    void anotherRegisteredReleaseIsRejectedWithoutReplacingItsReceipt(String mutation) {
        insertReceipt(mutation.equals("version") ? "pc-other" : manifest.version(),
                mutation.equals("content") ? "0".repeat(64) : manifest.contentSha256(),
                mutation.equals("source") ? "0".repeat(64) : manifest.sourceSha256());
        var before = allRows();
        var sequences = sequenceSnapshot();
        try (var context = seedContext(jdbc, SiteProperties.Mode.CAMPUS)) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> context.getBean(ProvincesCitiesInitializer.class).run());
            assertTrue(failure.getMessage().contains("registered version or checksum"));
        }
        assertEquals(before, allRows());
        assertEquals(sequences, sequenceSnapshot());
    }

    @Test
    void incompleteRestoreWithACompletedReceiptRequiresConsistentRecovery() {
        jdbc.update("INSERT INTO provinces(id,name) VALUES (1,'北京市')");
        insertReceipt(manifest.version(), manifest.contentSha256(), manifest.sourceSha256());
        var before = allRows();
        try (var context = seedContext(jdbc, SiteProperties.Mode.CAMPUS)) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> context.getBean(ProvincesCitiesInitializer.class).run());
            assertTrue(failure.getMessage().contains("registered dictionary is incomplete"));
        }
        assertEquals(before, allRows());
    }

    @Test
    void registeredDictionaryAndReceiptRejectDirectSqlMutationButAllowEmptyNoOpInserts() throws Exception {
        try (var context = seedContext(jdbc, SiteProperties.Mode.CAMPUS)) {
            context.getBean(ProvincesCitiesInitializer.class).run();
        }
        var before = allRows();
        for (String sql : List.of(
                "UPDATE reference_dictionary_manifest SET version='pc-other'",
                "DELETE FROM reference_dictionary_manifest",
                "TRUNCATE reference_dictionary_manifest",
                "INSERT INTO provinces(id,name) VALUES (999,'synthetic')",
                "UPDATE provinces SET name='synthetic' WHERE id=1",
                "DELETE FROM provinces WHERE id=1",
                "TRUNCATE provinces CASCADE",
                "INSERT INTO cities(id,province_id,name) VALUES (999,1,'synthetic')",
                "UPDATE cities SET name='synthetic' WHERE id=1",
                "DELETE FROM cities WHERE id=1",
                "TRUNCATE cities CASCADE")) {
            DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.execute(sql), sql);
            assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
            assertEquals("55000", ((SQLException) failure.getMostSpecificCause()).getSQLState(), sql);
            assertEquals(before, allRows());
        }
        assertEquals(0, jdbc.update("INSERT INTO provinces(id,name) SELECT 1,'北京市' WHERE false"));
        assertEquals(0, jdbc.update("INSERT INTO cities(id,province_id,name) SELECT 1,1,'东城区' WHERE false"));
        assertEquals(before, allRows());
    }

    @Test
    void concurrentTransactionsVisiblyWaitForTheDictionaryLockAndPublishOnlyOnce() throws Exception {
        try (var firstContext = seedContext(jdbc, SiteProperties.Mode.CAMPUS);
             var secondContext = seedContext(jdbc, SiteProperties.Mode.CAMPUS);
             Connection blocker = jdbc.getDataSource().getConnection();
             Statement lockStatement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            lockStatement.setQueryTimeout(2);
            lockStatement.execute("SELECT pg_advisory_xact_lock(1161909335, 1)");
            var first = firstContext.getBean(ProvincesCitiesInitializer.class);
            var second = secondContext.getBean(ProvincesCitiesInitializer.class);
            var executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> firstResult = null;
            Future<?> secondResult = null;
            long completionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            try {
                firstResult = executor.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); first.run(); return null; });
                secondResult = executor.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); second.run(); return null; });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int waitingConnections = 0;
                while (System.nanoTime() < waitDeadline) {
                    try (var waiting = lockStatement.executeQuery("""
                            SELECT count(DISTINCT pid) FROM pg_locks
                            WHERE locktype='advisory' AND mode='ExclusiveLock' AND NOT granted
                              AND database=(SELECT oid FROM pg_database WHERE datname=current_database())
                              AND classid=1161909335::oid AND objid=1::oid AND objsubid=2
                            """)) {
                        assertTrue(waiting.next());
                        waitingConnections = waiting.getInt(1);
                    }
                    if (waitingConnections == 2) { break; }
                    assertFalse(firstResult.isDone() || secondResult.isDone());
                    Thread.sleep(50);
                }
                assertEquals(2, waitingConnections, "Both real transaction connections must wait on the advisory lock");
                blocker.commit();
                firstResult.get(Math.max(1, completionDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                secondResult.get(Math.max(1, completionDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertPublished(jdbc);
                assertTrue(AopUtils.isAopProxy(first));
                assertTrue(AopUtils.isAopProxy(second));
            } finally {
                try { blocker.rollback(); }
                finally {
                    start.countDown();
                    if (firstResult != null) { firstResult.cancel(true); }
                    if (secondResult != null) { secondResult.cancel(true); }
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private JdbcTemplate newDatabase() throws SQLException {
        String database = "eatwhat_dict_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(IsolatedContainers.jdbcUrl(POSTGRES, IsolatedContainers.DATABASE),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " TEMPLATE template0");
        }
        var dataSource = new DriverManagerDataSource(IsolatedContainers.jdbcUrl(POSTGRES, database),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    private AnnotationConfigApplicationContext seedContext(JdbcTemplate database, SiteProperties.Mode mode) {
        var context = new AnnotationConfigApplicationContext();
        context.register(SeedTransactionConfiguration.class);
        context.registerBean(JdbcTemplate.class, () -> database);
        context.registerBean(ObjectMapper.class, () -> new JacksonConfig().objectMapper());
        context.registerBean(SiteProperties.class, () -> { var site = new SiteProperties(); site.setMode(mode); return site; });
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(database.getDataSource()));
        context.registerBean(ProvincesCitiesInitializer.class);
        context.refresh();
        return context;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class SeedTransactionConfiguration { }

    private void assertPublished(JdbcTemplate database) {
        assertEquals(manifest.provinces(), database.query("SELECT id,name FROM provinces ORDER BY id", (row, index) ->
                new ProvinceCityManifest.ProvinceEntry(row.getInt("id"), row.getString("name"))));
        assertEquals(manifest.cities(), database.query("SELECT id,province_id,name FROM cities ORDER BY id", (row, index) ->
                new ProvinceCityManifest.CityEntry(row.getInt("id"), row.getInt("province_id"), row.getString("name"))));
        var receipt = database.queryForMap("SELECT dictionary_key,version,content_sha256,source_sha256 FROM reference_dictionary_manifest");
        assertEquals(Map.of("dictionary_key", manifest.dictionaryKey(), "version", manifest.version(),
                "content_sha256", manifest.contentSha256(), "source_sha256", manifest.sourceSha256()), receipt);
    }

    private List<List<Map<String, Object>>> referenceRows(JdbcTemplate database) {
        return List.of(database.queryForList("SELECT id,name FROM provinces ORDER BY id"),
                database.queryForList("SELECT id,province_id,name FROM cities ORDER BY id"));
    }

    private List<List<Map<String, Object>>> allRows() {
        return List.of(jdbc.queryForList("SELECT * FROM provinces ORDER BY id"),
                jdbc.queryForList("SELECT * FROM cities ORDER BY id"),
                jdbc.queryForList("SELECT * FROM reference_dictionary_manifest ORDER BY dictionary_key"));
    }

    private List<Map<String, Object>> sequenceSnapshot() {
        return jdbc.queryForList("""
                SELECT 'provinces' AS name,last_value,is_called FROM provinces_id_seq
                UNION ALL SELECT 'cities' AS name,last_value,is_called FROM cities_id_seq ORDER BY name
                """);
    }

    private void insertReceipt(String version, String contentHash, String sourceHash) {
        jdbc.update("""
                INSERT INTO reference_dictionary_manifest(dictionary_key,version,content_sha256,source_sha256)
                VALUES ('province-city',?,?,?)
                """, version, contentHash, sourceHash);
    }

    private int count(JdbcTemplate database, String table) {
        return database.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
