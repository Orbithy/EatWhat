package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.JacksonConfig;
import you.v50to.eatwhat.utils.ProvincesCitiesInitializer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production migrations against a fresh, test-owned PostgreSQL database. */
class PostgresBaselineIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("schema");
    private static final Set<String> BUSINESS_TABLES = Set.of(
            "users", "contacts", "verifications", "provinces", "cities", "user_info",
            "activity_foods", "activity_dinners", "activity_likes", "notifications", "follow", "privacy",
            "hub", "restaurant", "foods", "browse_history", "food_tags", "food_taggings", "food_favorites", "likes");

    private String jdbcUrl;
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
        String database = "eatwhat_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                IsolatedContainers.jdbcUrl(POSTGRES, IsolatedContainers.DATABASE),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " TEMPLATE template0");
        }
        jdbcUrl = IsolatedContainers.jdbcUrl(POSTGRES, database);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                jdbcUrl, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
        // Each test uses its own database. Container teardown removes every database after the class.
    }

    @Test
    void emptyDatabaseMigratesAllMigrationsIncludingV30AndRerunIsANoOp() {
        Flyway flyway = flyway(null);
        assertEquals(50, flyway.migrate().migrationsExecuted);
        assertEquals("51", flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();

        assertTrue(Set.copyOf(jdbc.queryForList("SELECT extname FROM pg_extension", String.class))
                .containsAll(Set.of("postgis", "citext")));
        Set<String> tables = Set.copyOf(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename NOT IN ('flyway_schema_history', 'spatial_ref_sys', 'reference_dictionary_manifest')
                """, String.class));
        assertEquals(BUSINESS_TABLES, tables);
        assertEquals(14, count("""
                SELECT count(*) FROM pg_trigger t
                JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE NOT t.tgisinternal AND n.nspname='public'
                  AND t.tgrelid <> 'public.reference_dictionary_manifest'::regclass
                  AND p.proname <> 'protect_published_reference_dictionary'
                """), "Existing business triggers remain unchanged");
    }

    @Test
    void v2HistoryCanUpgradeWithoutChangingExistingUserIdentityOrProfile() {
        assertEquals(2, flyway("2").migrate().migrationsExecuted);
        long user = insertUser("upgrade");
        jdbc.update("INSERT INTO user_info(id, gender, birthday, signature) VALUES (?, 'other', DATE '2000-02-29', 'synthetic')", user);

        assertEquals(48, flyway(null).migrate().migrationsExecuted);
        assertEquals(user, jdbc.queryForObject("SELECT id FROM users WHERE nick_name='upgrade'", Long.class));
        assertEquals("user", jdbc.queryForObject("SELECT role FROM users WHERE id=?", String.class, user));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT banned FROM users WHERE id=?", Boolean.class, user));
        assertEquals("2000-02-29", jdbc.queryForObject("SELECT birthday::text FROM user_info WHERE id=?", String.class, user));
        flyway(null).validate();
    }

    @Test
    void citextUniqueChecksAndForeignKeyCascadesAreEnforcedByPostgres() {
        flyway(null).migrate();
        long first = insertUser("first");
        long second = insertUser("second");
        jdbc.update("INSERT INTO contacts(account_id,email) VALUES (?, 'Student@EXAMPLE.INVALID')", first);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO contacts(account_id,email) VALUES (?, 'student@example.invalid')", second));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO verifications(account_id,method,student_id,real_name,verified_email) VALUES (?, 'sso', 'test-id', 'synthetic', 'test@example.invalid')", first));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO food_favorites(account_id,food_id) VALUES (?, 999999)", first));

        long hub = jdbc.queryForObject("""
                INSERT INTO hub(name,center,gcj_lng,gcj_lat)
                VALUES ('synthetic',ST_GeomFromText('POINT(117 36)',4326),117,36) RETURNING id
                """, Long.class);
        long restaurant = insertRestaurant(second);
        jdbc.update("UPDATE restaurant SET hub_id=? WHERE id=?", hub, restaurant);
        long food = insertFood(second, restaurant);
        jdbc.update("INSERT INTO food_favorites(account_id,food_id) VALUES (?,?)", first, food);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO food_favorites(account_id,food_id) VALUES (?,?)", first, food));
        jdbc.update("DELETE FROM hub WHERE id=?", hub);
        assertNull(jdbc.queryForObject("SELECT hub_id FROM restaurant WHERE id=?", Long.class, restaurant));
        jdbc.update("DELETE FROM restaurant WHERE id=?", restaurant);
        assertEquals(0, count("SELECT count(*) FROM foods"));
        assertEquals(0, count("SELECT count(*) FROM food_favorites"));
    }

    @Test
    void bothLikeTriggersTrackStateTransitionsAndRejectDuplicateRelationships() {
        flyway(null).migrate();
        long account = insertUser("likes");
        long food = insertFood(account, insertRestaurant(account));
        assertLikeLifecycle("likes", "foods", "target_id", "food", account, food);

        long activityFood = jdbc.queryForObject("INSERT INTO activity_foods(account_id,food_name) VALUES (?,'synthetic') RETURNING id", Long.class, account);
        assertLikeLifecycle("activity_likes", "activity_foods", "activity_id", "food", account, activityFood);
        long dinner = jdbc.queryForObject("INSERT INTO activity_dinners(account_id,description) VALUES (?,'synthetic') RETURNING id", Long.class, account);
        assertLikeLifecycle("activity_likes", "activity_dinners", "activity_id", "dinner", account, dinner);
    }

    @Test
    void failedTransactionRollsBackBusinessRowsAndTriggerEffectsThenConnectionRecovers() throws SQLException {
        flyway(null).migrate();
        long account = insertUser("rollback");
        long food = insertFood(account, insertRestaurant(account));
        try (Connection connection = DriverManager.getConnection(jdbcUrl, IsolatedContainers.USER, IsolatedContainers.PASSWORD);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO likes(account_id,target_type,target_id) VALUES (" + account + ",'food'," + food + ")");
            SQLException duplicate = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO users(nick_name,password_hash) VALUES ('rollback','synthetic')"));
            assertEquals("23505", duplicate.getSQLState());
            connection.rollback();
            connection.setAutoCommit(true);
            try (var result = statement.executeQuery("SELECT 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
        assertEquals(0, count("SELECT count(*) FROM likes"));
        assertEquals(0, jdbc.queryForObject("SELECT likes_count FROM foods WHERE id=?", Integer.class, food));
        jdbc.update("INSERT INTO likes(account_id,target_type,target_id) VALUES (?,'food',?)", account, food);
        assertEquals(1, jdbc.queryForObject("SELECT likes_count FROM foods WHERE id=?", Integer.class, food));
    }

    @Test
    void postgisArraysJsonAndTimestampsRoundTripThroughActualDatabase() {
        flyway(null).migrate();
        long account = insertUser("types");
        long restaurant = insertRestaurant(account);
        jdbc.update("UPDATE restaurant SET picture_url=ARRAY['restaurants/synthetic-a.png','restaurants/synthetic-b.png'] WHERE id=?", restaurant);
        assertEquals(4326, jdbc.queryForObject("SELECT ST_SRID(location::geometry) FROM restaurant WHERE id=?", Integer.class, restaurant));
        assertEquals(117.0, jdbc.queryForObject("SELECT ST_X(location::geometry) FROM restaurant WHERE id=?", Double.class, restaurant));
        assertEquals(2, jdbc.queryForObject("SELECT cardinality(picture_url) FROM restaurant WHERE id=?", Integer.class, restaurant));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT ST_DWithin(location, ST_GeogFromText('SRID=4326;POINT(117 36)'), 1)
                FROM restaurant WHERE id=?
                """, Boolean.class, restaurant)));
        jdbc.update("""
                INSERT INTO notifications(account_id,actor_id,type,target_type,target_id,data,created_at)
                VALUES (?,?,'system','user',?,'{"synthetic":true}'::jsonb,'2026-10-12T08:00:00+08:00')
                """, account, account, account);
        assertEquals("true", jdbc.queryForObject("SELECT data->>'synthetic' FROM notifications", String.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT created_at = TIMESTAMPTZ '2026-10-12T00:00:00Z' FROM notifications", Boolean.class));
    }

    @Test
    void completeProvinceCityInitializationIsIdempotent() throws Exception {
        flyway(null).migrate();
        try (var context = seedContext()) {
            ProvincesCitiesInitializer initializer = context.getBean(ProvincesCitiesInitializer.class);
            initializer.run();
            assertCompleteDictionary(0, 0);
            var provincesBefore = provinceSnapshot();
            var citiesBefore = citySnapshot();
            var sequencesBefore = seedSequenceSnapshot();
            initializer.run();
            assertEquals(provincesBefore, provinceSnapshot());
            assertEquals(citiesBefore, citySnapshot());
            assertEquals(sequencesBefore, seedSequenceSnapshot(), "A no-op startup must not allocate unused province or city IDs");
            assertTrue(AopUtils.isAopProxy(initializer), "The real startup run method must have Spring transaction advice");
        }
    }

    @Test
    void interruptedProvinceCityInitializationRollsBackAndCompletesOnRetry() throws Exception {
        flyway(null).migrate();
        // Fail on the first city of the second nonempty province batch, after the first batch
        // and two province inserts have already succeeded within the startup transaction.
        int failAt = seedData().values().stream().filter(cities -> !cities.isEmpty()).findFirst().orElseThrow().size() + 1;
        jdbc.execute("CREATE SEQUENCE synthetic_seed_attempts");
        jdbc.execute("""
                CREATE FUNCTION synthetic_seed_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF nextval('synthetic_seed_attempts') = %d THEN
                    RAISE EXCEPTION 'synthetic seed interruption';
                  END IF;
                  RETURN NEW;
                END $$
                """.formatted(failAt));
        jdbc.execute("CREATE TRIGGER synthetic_seed_failure BEFORE INSERT ON cities FOR EACH ROW EXECUTE FUNCTION synthetic_seed_failure()");
        try (var context = seedContext()) {
            ProvincesCitiesInitializer initializer = context.getBean(ProvincesCitiesInitializer.class);
            assertThrows(DataAccessException.class, () -> initializer.run());
            assertTrue(jdbc.queryForObject("SELECT last_value FROM synthetic_seed_attempts", Long.class) >= failAt,
                    "The fault must occur after the first successful city batch");
            assertEquals(0, count("SELECT count(*) FROM provinces"), "A later batch failure must roll back earlier province inserts");
            assertEquals(0, count("SELECT count(*) FROM cities"), "Earlier successful city batches must roll back too");
            jdbc.execute("DROP TRIGGER synthetic_seed_failure ON cities");

            initializer.run();
            assertCompleteDictionary(0, 0);
            var provincesBefore = provinceSnapshot();
            var citiesBefore = citySnapshot();
            initializer.run();
            assertEquals(provincesBefore, provinceSnapshot());
            assertEquals(citiesBefore, citySnapshot());
            assertTrue(AopUtils.isAopProxy(initializer));
        }
    }

    @Test
    void partialProvinceCityDataIsCompletedWithoutChangingExistingIdsOrManualRows() throws Exception {
        flyway(null).migrate();
        var first = seedData().entrySet().stream().filter(entry -> !entry.getValue().isEmpty()).findFirst().orElseThrow();
        int province = jdbc.queryForObject("INSERT INTO provinces(name) VALUES (?) RETURNING id", Integer.class, first.getKey());
        int city = jdbc.queryForObject("INSERT INTO cities(province_id,name) VALUES (?,?) RETURNING id", Integer.class,
                province, first.getValue().getFirst());
        int manualProvince = jdbc.queryForObject("INSERT INTO provinces(name) VALUES ('synthetic-manual-province') RETURNING id", Integer.class);
        jdbc.update("INSERT INTO cities(province_id,name) VALUES (?, 'synthetic-manual-city')", manualProvince);
        jdbc.update("INSERT INTO cities(province_id,name) VALUES (?, 'synthetic-extra-city')", province);
        long user = insertUser("seed-owner");
        jdbc.update("INSERT INTO user_info(id,hometown_province_id,hometown_city_id) VALUES (?,?,?)", user, province, city);
        var originalProvinces = provinceSnapshot();
        var originalCities = citySnapshot();

        try (var context = seedContext()) {
            ProvincesCitiesInitializer initializer = context.getBean(ProvincesCitiesInitializer.class);
            initializer.run();
            assertCompleteDictionary(1, 2);
            assertTrue(provinceSnapshot().containsAll(originalProvinces), "Existing province IDs and rows must be preserved");
            assertTrue(citySnapshot().containsAll(originalCities), "Existing and manually added city IDs and rows must be preserved");
            assertEquals(province, jdbc.queryForObject("SELECT hometown_province_id FROM user_info WHERE id=?", Integer.class, user));
            assertEquals(city, jdbc.queryForObject("SELECT hometown_city_id FROM user_info WHERE id=?", Integer.class, user));
            var completeProvinces = provinceSnapshot();
            var completeCities = citySnapshot();
            initializer.run();
            assertEquals(completeProvinces, provinceSnapshot());
            assertEquals(completeCities, citySnapshot());
            assertTrue(AopUtils.isAopProxy(initializer));
        }
    }

    @Test
    void concurrentInitializersCompleteOnceWithoutDuplicatesOrDeadlock() throws Exception {
        flyway(null).migrate();
        try (var firstContext = seedContext(); var secondContext = seedContext();
             Connection blocker = DriverManager.getConnection(jdbcUrl, IsolatedContainers.USER, IsolatedContainers.PASSWORD);
             Statement lockStatement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            lockStatement.setQueryTimeout(2);
            lockStatement.execute("SELECT pg_advisory_xact_lock(1161909335, 1)");
            ProvincesCitiesInitializer first = firstContext.getBean(ProvincesCitiesInitializer.class);
            ProvincesCitiesInitializer second = secondContext.getBean(ProvincesCitiesInitializer.class);
            var executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> firstResult = null;
            Future<?> secondResult = null;
            long completionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            try {
                firstResult = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    first.run();
                    return null;
                });
                secondResult = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    second.run();
                    return null;
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                // Both independent transaction connections must be blocked on the actual seed lock.
                // A start latch alone can pass even when the scheduler executes the runners serially.
                long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int waitingConnections = 0;
                while (System.nanoTime() < waitDeadline) {
                    try (var waiting = lockStatement.executeQuery("""
                            SELECT count(DISTINCT pid) FROM pg_locks
                            WHERE locktype = 'advisory' AND mode = 'ExclusiveLock' AND NOT granted
                              AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                              AND classid = 1161909335::oid AND objid = 1::oid AND objsubid = 2
                            """)) {
                        assertTrue(waiting.next());
                        waitingConnections = waiting.getInt(1);
                    }
                    if (waitingConnections == 2) {
                        break;
                    }
                    assertFalse(firstResult.isDone() || secondResult.isDone(),
                            "Neither initializer may finish while the seed lock is held by another transaction");
                    Thread.sleep(50);
                }
                assertEquals(2, waitingConnections, "Both initializer transactions must visibly wait for the seed lock");

                blocker.commit();
                firstResult.get(Math.max(1, completionDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                secondResult.get(Math.max(1, completionDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertCompleteDictionary(0, 0);
                assertTrue(AopUtils.isAopProxy(first));
                assertTrue(AopUtils.isAopProxy(second));
            } finally {
                try {
                    // Rollback also releases the blocker if an assertion or timeout occurred before commit.
                    blocker.rollback();
                } finally {
                    start.countDown();
                    if (firstResult != null) {
                        firstResult.cancel(true);
                    }
                    if (secondResult != null) {
                        secondResult.cancel(true);
                    }
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                            "Initializer workers must stop after test cleanup");
                }
            }
        }
    }

    private AnnotationConfigApplicationContext seedContext() {
        var context = new AnnotationConfigApplicationContext();
        context.register(SeedTransactionConfiguration.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(ObjectMapper.class, () -> new JacksonConfig().objectMapper());
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(jdbc.getDataSource()));
        context.registerBean(ProvincesCitiesInitializer.class);
        context.refresh();
        return context;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class SeedTransactionConfiguration {
    }

    private Map<String, List<String>> seedData() throws Exception {
        try (var input = new ClassPathResource("pc.json").getInputStream()) {
            return new JacksonConfig().objectMapper().readValue(input, new TypeReference<>() { });
        }
    }

    private void assertCompleteDictionary(int extraProvinces, int extraCities) throws Exception {
        Map<String, List<String>> expected = seedData();
        assertEquals(expected.size() + extraProvinces, count("SELECT count(*) FROM provinces"));
        long expectedCities = expected.values().stream().mapToLong(names -> names.stream().distinct().count()).sum();
        assertEquals(expectedCities + extraCities, count("SELECT count(*) FROM cities"));
        for (var entry : expected.entrySet()) {
            List<String> actualCities = jdbc.queryForList("""
                    SELECT c.name FROM cities c JOIN provinces p ON p.id=c.province_id WHERE p.name=?
                    """, String.class, entry.getKey());
            assertTrue(actualCities.containsAll(entry.getValue()), "Missing reference cities in " + entry.getKey());
        }
    }

    private List<Map<String, Object>> provinceSnapshot() {
        return jdbc.queryForList("SELECT id,name,created_at FROM provinces ORDER BY id");
    }

    private List<Map<String, Object>> citySnapshot() {
        return jdbc.queryForList("SELECT id,province_id,name,created_at FROM cities ORDER BY id");
    }

    private List<Map<String, Object>> seedSequenceSnapshot() {
        return jdbc.queryForList("""
                SELECT 'provinces' AS name,last_value,is_called FROM provinces_id_seq
                UNION ALL SELECT 'cities' AS name,last_value,is_called FROM cities_id_seq
                ORDER BY name
                """);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private long insertUser(String name) {
        return jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES (?, 'synthetic-not-a-password') RETURNING id", Long.class, name);
    }

    private long insertRestaurant(long account) {
        return jdbc.queryForObject("""
                INSERT INTO restaurant(account_id,name,location,gcj_lng,gcj_lat)
                VALUES (?,'synthetic',ST_GeogFromText('SRID=4326;POINT(117 36)'),117,36) RETURNING id
                """, Long.class, account);
    }

    private long insertFood(long account, long restaurant) {
        return jdbc.queryForObject("INSERT INTO foods(account_id,restaurant_id,name) VALUES (?,?,'synthetic') RETURNING id", Long.class, account, restaurant);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void assertLikeLifecycle(String relationshipTable, String parentTable, String targetColumn,
                                     String targetType, long account, long target) {
        // SQL identifiers here are only the hard-coded schema names supplied by this test.
        String insert = "INSERT INTO " + relationshipTable + "(account_id,target_type," + targetColumn + ") VALUES (?,?,?)";
        jdbc.update(insert, account, targetType, target);
        assertEquals(1, likesCount(parentTable, target));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, account, targetType, target));
        assertEquals(1, likesCount(parentTable, target));
        String where = " WHERE account_id=? AND target_type=? AND " + targetColumn + "=?";
        jdbc.update("UPDATE " + relationshipTable + " SET deleted_at=now()" + where, account, targetType, target);
        assertEquals(0, likesCount(parentTable, target));
        jdbc.update("UPDATE " + relationshipTable + " SET deleted_at=NULL" + where, account, targetType, target);
        jdbc.update("UPDATE " + relationshipTable + " SET deleted_at=NULL" + where, account, targetType, target);
        assertEquals(1, likesCount(parentTable, target));
        jdbc.update("DELETE FROM " + relationshipTable + where, account, targetType, target);
        assertEquals(0, likesCount(parentTable, target));
    }

    private int likesCount(String table, long target) {
        return jdbc.queryForObject("SELECT likes_count FROM " + table + " WHERE id=?", Integer.class, target);
    }
}
