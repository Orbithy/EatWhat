package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.identity.GlobalIdentityBindingVerifier;
import you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL: IDs, trigger coverage, activation races and safe recovery without any peer network. */
class GlobalIdentityAllocationIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("global-identity");
    private static final long FIRST_CAMPUS = 1_000_000_001L;

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void allBusinessTablesReceiveTransactionalIdentitiesAndConflictLosersDoNotReserveIds() throws Exception {
        Database db = database(SiteProperties.Mode.LEGACY);
        long user = user(db, "alltables1");
        long other = user(db, "alltables2");
        insert(db, "INSERT INTO contacts(account_id,email) VALUES (" + user + ",'identity@example.invalid')");
        insert(db, "INSERT INTO verifications(account_id,method,verified_email) VALUES (" + user + ",'school_email','identity@example.invalid')");
        long activity = insert(db, "INSERT INTO activity_foods(account_id,food_name) VALUES (" + user + ",'synthetic')");
        insert(db, "INSERT INTO activity_dinners(account_id) VALUES (" + user + ")");
        insert(db, "INSERT INTO activity_likes(account_id,target_type,activity_id) VALUES (" + user + ",'food'," + activity + ")");
        insert(db, "INSERT INTO notifications(account_id,actor_id,type,target_type,target_id) VALUES ("
                + user + "," + other + ",'follow','user'," + other + ")");
        insert(db, "INSERT INTO follow(account_id,target_id) VALUES (" + user + "," + other + ")");
        insert(db, "INSERT INTO privacy(account_id) VALUES (" + user + ")");
        long hub = insert(db, """
                INSERT INTO hub(name,center,gcj_lng,gcj_lat)
                VALUES ('synthetic',ST_SetSRID(ST_MakePoint(117,36),4326),117,36)
                """);
        long restaurant = insert(db, """
                INSERT INTO restaurant(name,location,gcj_lng,gcj_lat,hub_id)
                VALUES ('synthetic',ST_SetSRID(ST_MakePoint(117,36),4326)::geography,117,36,%d)
                """.formatted(hub));
        long food = insert(db, "INSERT INTO foods(account_id,restaurant_id,name) VALUES (" + user + "," + restaurant + ",'synthetic')");
        insert(db, "INSERT INTO browse_history(account_id,target_type,target_id) VALUES (" + user + ",'food'," + food + ")");
        long tag = insert(db, "INSERT INTO food_tags(name,tag_type,normalized_name) VALUES ('synthetic','system','synthetic')");
        insert(db, "INSERT INTO food_taggings(food_id,tag_id,account_id) VALUES (" + food + "," + tag + "," + user + ")");
        insert(db, "INSERT INTO food_favorites(account_id,food_id) VALUES (" + user + "," + food + ")");
        insert(db, "INSERT INTO likes(account_id,target_type,target_id) VALUES (" + user + ",'food'," + food + ")");
        long definition = insert(db, "INSERT INTO activities(code,name) VALUES ('synthetic','synthetic')");
        insert(db, "INSERT INTO activity_posts(activity_id,account_id,picture_url) VALUES (" + definition + "," + user + ",ARRAY['activity/synthetic.png'])");
        db.jdbc().update("INSERT INTO user_info(id) VALUES (?)", user);

        assertEquals(Set.copyOf(GlobalIdentityMigrationCallback.ENTITY_TYPES),
                Set.copyOf(db.jdbc().queryForList("SELECT DISTINCT entity_type FROM eatwhat_identity.entity_identity", String.class)));
        assertEquals(20, identityCount(db));
        assertEquals(20, db.jdbc().queryForObject("SELECT count(DISTINCT global_id) FROM eatwhat_identity.entity_identity", Integer.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.entity_identity WHERE origin_site IS NOT NULL", Integer.class));
        UUID original = globalId(db, "users", user);
        assertEquals(0, db.jdbc().update("""
                INSERT INTO users(nick_name,password_hash) VALUES ('alltables1','synthetic')
                ON CONFLICT(nick_name) DO NOTHING
                """));
        assertEquals(20, identityCount(db));
        db.jdbc().update("""
                INSERT INTO users(nick_name,password_hash) VALUES ('alltables1','synthetic')
                ON CONFLICT(nick_name) DO UPDATE SET avatar='avatar/synthetic.png'
                """);
        assertEquals(original, globalId(db, "users", user));
        assertEquals(20, identityCount(db));
        assertSqlState("23514", () -> db.jdbc().update("UPDATE user_info SET id=? WHERE id=?", other, user));
        assertSqlState("23514", () -> db.jdbc().update("UPDATE users SET id=id+100 WHERE id=?", other));
        assertEquals(user, db.jdbc().queryForObject("SELECT id FROM user_info", Long.class));
        assertEquals(1, db.jdbc().queryForObject("SELECT likes_count FROM activity_foods WHERE id=?", Integer.class, activity));
        assertEquals(1, db.jdbc().queryForObject("SELECT likes_count FROM foods WHERE id=?", Integer.class, food));
    }

    @Test
    void businessRollbackRemovesItsMappingButHardDeleteKeepsTheNumericIdentityReserved() throws Exception {
        Database db = database(SiteProperties.Mode.LEGACY);
        try (Connection connection = connection(db.url())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users(nick_name,password_hash) VALUES ('rolledback','synthetic')");
            }
            connection.rollback();
        }
        assertEquals(0, identityCount(db));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM users", Integer.class));
        long id = user(db, "deleted");
        UUID gid = globalId(db, "users", id);
        db.jdbc().update("DELETE FROM users WHERE id=?", id);
        assertEquals(gid, globalId(db, "users", id));
        assertSqlState("23505", () -> db.jdbc().update(
                "INSERT INTO users(id,nick_name,password_hash) VALUES (?,'reused','synthetic')", id));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM users", Integer.class));
        assertTrue(user(db, "fresh") > id);
    }

    @Test
    void twoIndependentDatabasesAllocateDisjointSafeNumericIdsUnderConcurrentWrites() throws Exception {
        Database campus = database(SiteProperties.Mode.CAMPUS);
        Database external = database(SiteProperties.Mode.EXTERNAL);
        Set<Long> numericIds = new HashSet<>();
        Set<UUID> globalIds = new HashSet<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<List<Long>>>();
            for (int worker = 0; worker < 8; worker++) {
                Database db = worker % 2 == 0 ? campus : external;
                int number = worker;
                jobs.add(executor.submit(() -> {
                    List<Long> values = new ArrayList<>();
                    for (int i = 0; i < 20; i++) {
                        long id = user(db, "worker" + number + "_" + i);
                        assertTrue(id > GlobalIdentityMigrationCallback.RESERVED_ID_MAX);
                        assertTrue(id <= GlobalIdentityMigrationCallback.MAX_SAFE_INTEGER);
                        assertEquals(db.site().getMode() == SiteProperties.Mode.CAMPUS ? 1 : 0, id % 2);
                        assertEquals(id, (long) (double) id, "Existing JSON numeric IDs remain exactly representable");
                        values.add(id);
                    }
                    return values;
                }));
            }
            for (var job : jobs) {
                for (long id : job.get(40, TimeUnit.SECONDS)) {
                    assertTrue(numericIds.add(id), "An ID must never collide between sites");
                }
            }
        }
        for (Database db : List.of(campus, external)) {
            assertEquals(80, identityCount(db));
            for (UUID id : db.jdbc().queryForList("SELECT global_id FROM eatwhat_identity.entity_identity", UUID.class)) {
                assertTrue(globalIds.add(id));
            }
        }
        assertEquals(160, numericIds.size());
        assertEquals(160, globalIds.size());
    }

    @Test
    void concurrentFirstActivationIsAtomicAndRestartsNeverRewindSequences() throws Exception {
        Database db = database(SiteProperties.Mode.LEGACY);
        SiteProperties campus = site(SiteProperties.Mode.CAMPUS);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); activate(db, campus); return null; });
            var second = executor.submit(() -> { start.await(); activate(db, campus); return null; });
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.node_state", Integer.class));
        assertEquals(FIRST_CAMPUS, user(db, "before_restart"));
        long last = db.jdbc().queryForObject("SELECT last_value FROM users_id_seq", Long.class);
        activate(db, campus);
        activate(db, campus);
        assertEquals(last, db.jdbc().queryForObject("SELECT last_value FROM users_id_seq", Long.class));
        assertEquals(FIRST_CAMPUS + 2, user(db, "after_restart"));
    }

    @Test
    void failedActivationRollsBackAllSequencesAndBindingBeforeAValidRetry() throws Exception {
        Database db = database(SiteProperties.Mode.LEGACY);
        SiteProperties campus = site(SiteProperties.Mode.CAMPUS);
        String sequenceSnapshot = """
                SELECT sequencename,start_value,min_value,max_value,increment_by,cycle,cache_size,last_value
                FROM pg_sequences
                WHERE schemaname='public' AND sequencename NOT IN ('provinces_id_seq','cities_id_seq')
                ORDER BY sequencename
                """;
        var before = db.jdbc().queryForList(sequenceSnapshot);
        assertEquals(19, before.size());
        db.jdbc().execute("""
                CREATE FUNCTION eatwhat_identity.fail_activation_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF (SELECT count(*) FROM pg_sequences
                      WHERE schemaname='public' AND sequencename NOT IN ('provinces_id_seq','cities_id_seq')
                        AND increment_by=2 AND min_value=1000000001 AND cache_size=1) <> 19 THEN
                    RAISE EXCEPTION 'Failure injection reached before all sequence changes' USING ERRCODE='P0002';
                  END IF;
                  RAISE EXCEPTION 'Injected node binding failure after all sequence changes' USING ERRCODE='P0001';
                END $$
                """);
        db.jdbc().execute("""
                CREATE TRIGGER fail_activation_for_test BEFORE INSERT ON eatwhat_identity.node_state
                FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.fail_activation_for_test()
                """);
        SQLException failure = assertThrows(SQLException.class, () -> activate(db, campus));
        assertEquals("P0001", failure.getSQLState(), "The failure must occur after all 19 sequence ALTER statements");
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.node_state", Integer.class));
        assertEquals(before, db.jdbc().queryForList(sequenceSnapshot), "All sequence settings and values must roll back");
        db.jdbc().execute("DROP TRIGGER fail_activation_for_test ON eatwhat_identity.node_state");
        db.jdbc().execute("DROP FUNCTION eatwhat_identity.fail_activation_for_test()");
        activate(db, campus);
        assertEquals(FIRST_CAMPUS, user(db, "retry"));
    }

    @Test
    void tablesAddedByALaterMigrationJoinAnActivatedSitesAllocationDomain() throws Exception {
        Database empty = emptyDatabase();
        SiteProperties campus = site(SiteProperties.Mode.CAMPUS);
        Database db = new Database(empty.name(), empty.url(), empty.jdbc(), campus, empty.runtimeUsername());
        // Activate the site before V75 created activities/activity_posts, then upgrade it.
        flyway(db, "74", new GlobalIdentityMigrationCallback(campus, db.runtimeUsername())).migrate();
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.node_state", Integer.class));
        flyway(db, null, new GlobalIdentityMigrationCallback(campus, db.runtimeUsername())).migrate();
        long user = user(db, "adopted_owner");
        JdbcTemplate runtime = new JdbcTemplate(new DriverManagerDataSource(db.url(), db.runtimeUsername(), IsolatedContainers.PASSWORD));
        long definition = runtime.queryForObject("INSERT INTO activities(code,name) VALUES ('adopted','adopted') RETURNING id", Long.class);
        long post = runtime.queryForObject("INSERT INTO activity_posts(activity_id,account_id,picture_url) VALUES (?,?,ARRAY['activity/a.png']) RETURNING id",
                Long.class, definition, user);
        assertEquals(FIRST_CAMPUS, definition);
        assertEquals(FIRST_CAMPUS, post);
        assertEquals("campus-test", db.jdbc().queryForObject(
                "SELECT origin_site FROM eatwhat_identity.entity_identity WHERE entity_type='activity_posts' AND numeric_id=?", String.class, post));
        // A restart verifies the adopted sequences like every other table.
        activate(db, campus);
    }

    @Test
    void historicBusinessDataCannotBeSilentlyActivatedOrBackfilled() throws Exception {
        Database db = emptyDatabase();
        flyway(db, "15", null).migrate();
        long id = user(db, "historic");
        var before = db.jdbc().queryForMap("SELECT * FROM users WHERE id=?", id);
        flyway(db, null, new GlobalIdentityMigrationCallback(db.site(), db.runtimeUsername())).migrate();
        assertEquals(before, db.jdbc().queryForMap("SELECT * FROM users WHERE id=?", id));
        assertEquals(0, identityCount(db), "Historical global IDs require an authoritative manifest");
        assertThrows(IllegalStateException.class, () -> activate(db, site(SiteProperties.Mode.CAMPUS)));
        assertEquals(before, db.jdbc().queryForMap("SELECT * FROM users WHERE id=?", id));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.node_state", Integer.class));
        assertEquals(1, db.jdbc().queryForObject("SELECT increment_by FROM pg_sequences WHERE sequencename='users_id_seq'", Integer.class));
    }

    @Test
    void wrongSiteInstanceLegacyDowngradeAndCopiedDatabaseAreRejected() throws Exception {
        Database db = database(SiteProperties.Mode.CAMPUS);
        SiteProperties wrongInstance = site(SiteProperties.Mode.CAMPUS);
        SiteProperties wrongMode = site(SiteProperties.Mode.EXTERNAL);
        SiteProperties legacy = site(SiteProperties.Mode.LEGACY);
        for (SiteProperties wrong : List.of(wrongInstance, wrongMode, legacy)) {
            assertThrows(IllegalStateException.class, () -> activate(db, wrong));
            assertThrows(IllegalStateException.class, () -> new GlobalIdentityBindingVerifier(wrong, db.jdbc()).run(null));
        }
        SiteProperties wrongSite = site(SiteProperties.Mode.CAMPUS);
        wrongSite.setInstanceId(db.site().getInstanceId());
        wrongSite.setId("other-campus");
        assertThrows(IllegalStateException.class, () -> activate(db, wrongSite));

        // Copy into a new database in the same cluster: persisted database fingerprint must fail.
        String cloneName = "identity_clone_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection control = connection(IsolatedContainers.jdbcUrl(POSTGRES, IsolatedContainers.DATABASE));
             var statement = control.createStatement()) {
            statement.execute("CREATE DATABASE " + cloneName + " TEMPLATE " + db.name());
        }
        String cloneUrl = IsolatedContainers.jdbcUrl(POSTGRES, cloneName);
        Database clone = new Database(cloneName, cloneUrl, jdbc(cloneUrl), db.site(), db.runtimeUsername());
        assertThrows(IllegalStateException.class, () -> activate(clone, db.site()));
        assertEquals(FIRST_CAMPUS, user(db, "original_still_works"));
    }

    @Test
    void driftFailsClosedAndAnExplicitForwardRepairPreservesDeletedIdentities() throws Exception {
        Database db = database(SiteProperties.Mode.CAMPUS);
        long deleted = user(db, "before_drift");
        UUID retained = globalId(db, "users", deleted);
        db.jdbc().update("DELETE FROM users WHERE id=?", deleted);
        db.jdbc().execute("ALTER SEQUENCE users_id_seq RESTART WITH " + FIRST_CAMPUS);
        assertThrows(IllegalStateException.class, () -> activate(db, db.site()));
        assertEquals(FIRST_CAMPUS, db.jdbc().queryForObject("SELECT last_value FROM users_id_seq", Long.class));
        db.jdbc().execute("ALTER SEQUENCE users_id_seq RESTART WITH " + (FIRST_CAMPUS + 2));
        activate(db, db.site());
        assertEquals(FIRST_CAMPUS + 2, user(db, "after_repair"));
        assertEquals(retained, globalId(db, "users", deleted));

        db.jdbc().execute("ALTER SEQUENCE users_id_seq INCREMENT BY 1");
        assertThrows(IllegalStateException.class, () -> activate(db, db.site()));
        db.jdbc().execute("ALTER SEQUENCE users_id_seq INCREMENT BY 2");
        activate(db, db.site());
        assertEquals(FIRST_CAMPUS + 4, user(db, "after_config_repair"));
    }

    @Test
    void explicitWrongDomainOrUnallocatedIdsFailAndTheSafeIntegerLimitNeverWraps() throws Exception {
        Database db = database(SiteProperties.Mode.CAMPUS);
        assertSqlState("23514", () -> db.jdbc().update(
                "INSERT INTO users(id,nick_name,password_hash) VALUES (?,'wrongsite','synthetic')", FIRST_CAMPUS + 1));
        assertSqlState("23514", () -> db.jdbc().update(
                "INSERT INTO users(id,nick_name,password_hash) VALUES (?,'bypass','synthetic')", FIRST_CAMPUS + 100));
        assertEquals(0, identityCount(db));
        assertEquals(FIRST_CAMPUS, user(db, "valid"));

        long maximum = GlobalIdentityMigrationCallback.MAX_SAFE_INTEGER;
        db.jdbc().execute("ALTER SEQUENCE users_id_seq RESTART WITH " + (maximum - 2));
        assertEquals(maximum - 2, user(db, "near_limit"));
        assertEquals(maximum, user(db, "at_limit"));
        assertThrows(DataAccessException.class, () -> user(db, "overflow"));
        assertEquals(3, identityCount(db));
        assertThrows(IllegalStateException.class, () -> activate(db, db.site()), "Exhaustion must never restart from the floor");
    }

    @Test
    void multirowDefaultInsertsRespectTheAllocatorAndLegacyWithoutMetadataStillStarts() throws Exception {
        Database db = database(SiteProperties.Mode.EXTERNAL);
        assertEquals(10, db.jdbc().update("""
                INSERT INTO users(nick_name,password_hash)
                SELECT 'multi_' || n, 'synthetic' FROM generate_series(1,10) n
                """));
        assertEquals(10, identityCount(db));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM users WHERE mod(id,2)<>0", Integer.class));
        activate(db, db.site());
        Database old = emptyDatabase();
        flyway(old, "15", null).migrate();
        assertDoesNotThrow(() -> new GlobalIdentityBindingVerifier(old.site(), old.jdbc()).run(null));
    }

    @Test
    void explicitActivationRejectsOwnersSuperusersAndRoleAdministratorsWhileLegacyRemainsCompatible() throws Exception {
        Database db = database(SiteProperties.Mode.LEGACY);
        SiteProperties campus = site(SiteProperties.Mode.CAMPUS);
        String metadataOwner = role(db, "NOLOGIN");
        String roleAdministrator = role(db, "NOLOGIN CREATEROLE");
        db.jdbc().execute("ALTER TABLE eatwhat_identity.entity_identity OWNER TO " + metadataOwner);
        for (String rejected : List.of(metadataOwner, IsolatedContainers.USER, roleAdministrator)) {
            try (Connection migration = connection(db.url())) {
                assertThrows(IllegalStateException.class,
                        () -> new GlobalIdentityMigrationCallback(campus, rejected).activateOrVerify(migration));
            }
            assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.node_state", Integer.class));
            assertEquals(1, db.jdbc().queryForObject("SELECT increment_by FROM pg_sequences WHERE sequencename='users_id_seq'", Integer.class));
        }
        // A legacy installation may share the owner role; its own INSERT privilege must survive.
        try (Connection migration = connection(db.url())) {
            new GlobalIdentityMigrationCallback(db.site(), metadataOwner).activateOrVerify(migration);
        }
        assertTrue(db.jdbc().queryForObject(
                "SELECT has_table_privilege(?, 'eatwhat_identity.entity_identity', 'INSERT')", Boolean.class, metadataOwner));
        db.jdbc().execute("ALTER TABLE eatwhat_identity.entity_identity OWNER TO " + IsolatedContainers.USER);
        activate(db, campus);
        assertEquals(FIRST_CAMPUS, user(db, "restricted_after_rejections"));
    }

    @Test
    void noinheritMembershipCannotSwitchToAProtectedObjectOwner() throws Exception {
        Database db = database(SiteProperties.Mode.LEGACY);
        String owner = role(db, "NOLOGIN");
        db.jdbc().execute("ALTER TABLE eatwhat_identity.entity_identity OWNER TO " + owner);
        db.jdbc().execute("ALTER ROLE " + db.runtimeUsername() + " NOINHERIT");
        db.jdbc().execute("GRANT " + owner + " TO " + db.runtimeUsername());
        assertFalse(db.jdbc().queryForObject("SELECT has_table_privilege(?, 'eatwhat_identity.entity_identity', 'UPDATE')",
                Boolean.class, db.runtimeUsername()));
        assertTrue(db.jdbc().queryForObject("SELECT pg_has_role(?::name,?::name,'MEMBER')",
                Boolean.class, db.runtimeUsername(), owner));
        assertThrows(IllegalStateException.class, () -> activate(db, site(SiteProperties.Mode.CAMPUS)));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.node_state", Integer.class));
    }

    @Test
    void inheritedMetadataWritesAndSwitchableSequenceUpdatesFailClosedOnActivationAndRestart() throws Exception {
        Database db = database(SiteProperties.Mode.LEGACY);
        SiteProperties campus = site(SiteProperties.Mode.CAMPUS);
        String writer = role(db, "NOLOGIN");
        db.jdbc().execute("GRANT UPDATE ON eatwhat_identity.entity_identity TO " + writer);
        db.jdbc().execute("GRANT " + writer + " TO " + db.runtimeUsername());
        assertTrue(db.jdbc().queryForObject("SELECT has_table_privilege(?, 'eatwhat_identity.entity_identity', 'UPDATE')",
                Boolean.class, db.runtimeUsername()));
        assertThrows(IllegalStateException.class, () -> activate(db, campus));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.node_state", Integer.class));
        db.jdbc().execute("REVOKE UPDATE ON eatwhat_identity.entity_identity FROM " + writer);
        activate(db, campus);
        assertEquals(FIRST_CAMPUS, user(db, "before_privilege_drift"));

        // PostgreSQL 16 stores inheritance on each membership; ALTER ROLE only changes future defaults.
        db.jdbc().execute("GRANT " + writer + " TO " + db.runtimeUsername() + " WITH INHERIT FALSE");
        db.jdbc().execute("GRANT " + writer + " TO " + db.runtimeUsername() + " WITH SET TRUE");
        assertTrue(db.jdbc().queryForObject("""
                SELECT NOT inherit_option AND set_option FROM pg_auth_members
                WHERE roleid=(SELECT oid FROM pg_roles WHERE rolname=?)
                  AND member=(SELECT oid FROM pg_roles WHERE rolname=?)
                """, Boolean.class, writer, db.runtimeUsername()));
        db.jdbc().execute("GRANT UPDATE ON SEQUENCE users_id_seq TO " + writer);
        assertFalse(db.jdbc().queryForObject("SELECT has_sequence_privilege(?, 'users_id_seq', 'UPDATE')",
                Boolean.class, db.runtimeUsername()));
        assertThrows(IllegalStateException.class, () -> activate(db, campus), "SET ROLE must not bypass sequence protection");
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.node_state", Integer.class));
        assertEquals(FIRST_CAMPUS, db.jdbc().queryForObject("SELECT last_value FROM users_id_seq", Long.class));
        db.jdbc().execute("REVOKE UPDATE ON SEQUENCE users_id_seq FROM " + writer);
        activate(db, campus);
        assertEquals(FIRST_CAMPUS + 2, user(db, "after_privilege_repair"));
    }

    private Database database(SiteProperties.Mode mode) throws Exception {
        Database empty = emptyDatabase();
        SiteProperties properties = site(mode);
        Database db = new Database(empty.name(), empty.url(), empty.jdbc(), properties, empty.runtimeUsername());
        flyway(db, null, new GlobalIdentityMigrationCallback(properties, db.runtimeUsername())).migrate();
        return db;
    }

    private Database emptyDatabase() throws SQLException {
        String name = "identity_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection(IsolatedContainers.jdbcUrl(POSTGRES, IsolatedContainers.DATABASE));
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " TEMPLATE template0");
        }
        String url = IsolatedContainers.jdbcUrl(POSTGRES, name);
        String runtime = "identity_app_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = jdbc(url);
        admin.execute("CREATE ROLE " + runtime + " LOGIN PASSWORD 'synthetic-test-only'");
        admin.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO " + runtime);
        admin.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO " + runtime);
        return new Database(name, url, admin, site(SiteProperties.Mode.LEGACY), runtime);
    }

    private Flyway flyway(Database db, String target, GlobalIdentityMigrationCallback callback) {
        var config = Flyway.configure().dataSource(db.url(), IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).validateOnMigrate(true).cleanDisabled(true);
        if (target != null) config.target(target);
        if (callback != null) config.callbacks(callback);
        return config.load();
    }

    private SiteProperties site(SiteProperties.Mode mode) {
        SiteProperties properties = new SiteProperties();
        properties.setMode(mode);
        if (mode != SiteProperties.Mode.LEGACY) {
            properties.setId(mode == SiteProperties.Mode.CAMPUS ? "campus-test" : "external-test");
            properties.setInstanceId(UUID.randomUUID());
        }
        return properties;
    }

    private void activate(Database db, SiteProperties properties) throws SQLException {
        try (Connection connection = connection(db.url())) {
            new GlobalIdentityMigrationCallback(properties, db.runtimeUsername()).activateOrVerify(connection);
        }
    }

    private long user(Database db, String name) {
        JdbcTemplate runtime = new JdbcTemplate(new DriverManagerDataSource(db.url(), db.runtimeUsername(), IsolatedContainers.PASSWORD));
        return runtime.queryForObject(
                "INSERT INTO users(nick_name,password_hash) VALUES (?,'synthetic') RETURNING id", Long.class, name);
    }

    private String role(Database db, String options) {
        String name = "identity_role_" + UUID.randomUUID().toString().replace("-", "");
        db.jdbc().execute("CREATE ROLE " + name + " " + options);
        return name;
    }

    private long insert(Database db, String sql) {
        return db.jdbc().queryForObject(sql + " RETURNING id", Long.class);
    }

    private UUID globalId(Database db, String entity, long id) {
        return db.jdbc().queryForObject("""
                SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type=? AND numeric_id=?
                """, UUID.class, entity, id);
    }

    private int identityCount(Database db) {
        return db.jdbc().queryForObject("SELECT count(*) FROM eatwhat_identity.entity_identity", Integer.class);
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }

    private static Connection connection(String url) throws SQLException {
        return DriverManager.getConnection(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD);
    }

    private void assertSqlState(String expected, org.junit.jupiter.api.function.Executable action) {
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class, action);
        assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(expected, ((SQLException) failure.getMostSpecificCause()).getSQLState());
    }

    private record Database(String name, String url, JdbcTemplate jdbc, SiteProperties site, String runtimeUsername) { }
}
