package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.identity.IdentityManifestService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static you.v50to.eatwhat.identity.IdentityManifestService.*;

/** Real V15 snapshots upgraded in isolated databases; no host data or automatic history migration. */
class IdentityManifestIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("identity-manifest");

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void interruptedAuthoritativeBackfillResumesAndReplicaImportsTheSameManifestWithoutChangingBusinessData() throws Exception {
        String source = snapshotDatabase();
        String replica = snapshotDatabase();
        Baseline baseline = new Baseline(UUID.randomUUID(), MANIFEST_VERSION);
        UUID firstGlobalId;
        String before;
        try (Connection connection = connect(source)) {
            before = businessSnapshot(connection);
            var tool = tool(connection);
            tool.beginAuthoritative(baseline);
            var first = tool.backfillBatch(EntityType.USERS, 1);
            assertEquals(1, first.lastNumericId());
            assertFalse(first.completed());
            firstGlobalId = readService(source).findGlobalId(EntityType.USERS, 1).orElseThrow();
            execute(connection, """
                    CREATE FUNCTION eatwhat_identity.test_fail_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN IF NEW.entity_type='users' AND NEW.last_numeric_id>=5 THEN
                        RAISE EXCEPTION 'synthetic interrupted checkpoint' USING ERRCODE='P0001';
                    END IF; RETURN NEW; END $$
                    """);
            execute(connection, """
                    CREATE TRIGGER test_checkpoint_failure BEFORE UPDATE ON eatwhat_identity.baseline_checkpoint
                    FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.test_fail_checkpoint()
                    """);
            assertEquals("P0001", assertThrows(SQLException.class,
                    () -> tool.backfillBatch(EntityType.USERS, 1)).getSQLState());
            assertEquals(1, tool.progress(EntityType.USERS).lastNumericId());
            assertTrue(readService(source).findGlobalId(EntityType.USERS, 5).isEmpty(),
                    "Sidecar insert and checkpoint must roll back together");
            execute(connection, "DROP TRIGGER test_checkpoint_failure ON eatwhat_identity.baseline_checkpoint");
        }

        // A new connection/tool represents a stopped operator process restarting with its saved baseline.
        try (Connection connection = connect(source); Connection target = connect(replica)) {
            var sourceTool = tool(connection);
            sourceTool.beginAuthoritative(baseline);
            for (EntityType type : EntityType.independentTypes()) {
                while (!sourceTool.backfillBatch(type, 1).completed()) {
                    // Every call commits one bounded batch.
                }
            }
            assertEquals(firstGlobalId, readService(source).findGlobalId(EntityType.USERS, 1).orElseThrow());
            assertEquals(before, businessSnapshot(connection), "Backfill must not touch business timestamps or old FKs");
            var targetTool = tool(target);
            targetTool.beginReplica(baseline);
            for (EntityType type : EntityType.independentTypes()) {
                long cursor = 0;
                while (true) {
                    ManifestPage page = sourceTool.exportPage(type, cursor, 1);
                    var imported = targetTool.importPage(page);
                    assertEquals(imported, targetTool.importPage(page), "Replayed pages must not advance counts twice");
                    cursor = imported.lastNumericId();
                    if (page.complete()) {
                        assertTrue(imported.completed());
                        break;
                    }
                }
            }
            assertEquals(identitySnapshot(connection), identitySnapshot(target));
            assertEquals(before, businessSnapshot(target));
            assertEquals(5, scalar(connection, "SELECT count(*) FROM eatwhat_identity.entity_identity"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM eatwhat_identity.entity_identity WHERE entity_type='user_info'"));
        }
        EntityIdentityService reader = readService(replica);
        assertEquals(firstGlobalId, reader.findGlobalId(EntityType.USER_INFO, 1).orElseThrow());
        assertEquals(1L, reader.findNumericId(EntityType.USER_INFO, firstGlobalId).orElseThrow());
        assertTrue(reader.findNumericId(EntityType.FOODS, firstGlobalId).isEmpty());
        assertTrue(reader.findGlobalId(EntityType.USERS, 99).isEmpty());
        assertEquals(HISTORICAL_MAX_ID,
                reader.findNumericId(EntityType.USERS, reader.findGlobalId(EntityType.USERS, HISTORICAL_MAX_ID).orElseThrow()).orElseThrow());
    }

    @Test
    void conflictingUuidOriginAbsentRowsAndWrongBaselinesRejectTheWholeImportBatch() throws Exception {
        String database = snapshotDatabase();
        Baseline baseline = new Baseline(UUID.randomUUID(), MANIFEST_VERSION);
        UUID alreadyAssigned = UUID.randomUUID();
        try (Connection connection = connect(database)) {
            execute(connection, """
                    INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id)
                    VALUES ('users',5,?)
                    """, alreadyAssigned);
            var tool = tool(connection);
            tool.beginReplica(baseline);
            Entry validFirst = new Entry(1, UUID.randomUUID(), null);
            for (Entry conflict : List.of(new Entry(5, UUID.randomUUID(), null),
                    new Entry(5, alreadyAssigned, "unexpected-origin"), new Entry(7, UUID.randomUUID(), null))) {
                assertThrows(IllegalStateException.class, () -> tool.importPage(new ManifestPage(baseline,
                        EntityType.USERS, 0, HISTORICAL_MAX_ID, List.of(validFirst, conflict), false)));
                assertEquals(1, scalar(connection, "SELECT count(*) FROM eatwhat_identity.entity_identity"));
                assertEquals(0, scalar(connection, "SELECT count(*) FROM eatwhat_identity.baseline_checkpoint"));
            }
            SQLException uuidCollision = assertThrows(SQLException.class, () -> tool.importPage(new ManifestPage(baseline,
                    EntityType.USERS, 0, HISTORICAL_MAX_ID, List.of(new Entry(1, alreadyAssigned, null)), false)));
            assertEquals("23505", uuidCollision.getSQLState());
            assertThrows(IllegalStateException.class, () -> tool.importPage(new ManifestPage(
                    new Baseline(UUID.randomUUID(), MANIFEST_VERSION), EntityType.USERS, 0, HISTORICAL_MAX_ID,
                    List.of(validFirst), false)));
            assertThrows(IllegalStateException.class, () -> tool.beginAuthoritative(baseline));
            assertThrows(IllegalStateException.class, () -> tool.backfillBatch(EntityType.USERS, 1));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM eatwhat_identity.entity_identity"));
        }
    }

    @Test
    void bindingFreezesEveryBusinessWriteAcrossBatchesAndRejectsActivatedSites() throws Exception {
        String database = snapshotDatabase();
        try (Connection connection = connect(database)) {
            tool(connection).beginAuthoritative(new Baseline(UUID.randomUUID(), MANIFEST_VERSION));
        }
        try (Connection connection = connect(database)) {
            for (EntityType type : EntityType.values()) {
                // Statement guards must fire even when no row is touched, and include the shared-ID extension.
                for (String sql : List.of("UPDATE public." + type.tableName() + " SET id=id WHERE false",
                        "DELETE FROM public." + type.tableName() + " WHERE false",
                        "INSERT INTO public." + type.tableName() + " SELECT * FROM public." + type.tableName() + " WHERE false")) {
                    SQLException frozen = assertThrows(SQLException.class, () -> execute(connection, sql), type.tableName());
                    assertEquals("55000", frozen.getSQLState());
                }
            }
            for (String dictionary : List.of("provinces", "cities")) {
                assertEquals("55000", assertThrows(SQLException.class,
                        () -> execute(connection, "UPDATE public." + dictionary + " SET id=id WHERE false")).getSQLState());
            }
            assertEquals("55000", assertThrows(SQLException.class,
                    () -> execute(connection, "TRUNCATE TABLE user_info")).getSQLState());
            assertEquals("55000", assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO users(id,nick_name,password_hash) VALUES (2,'late-low','synthetic')")).getSQLState());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM users WHERE id=2"));
            assertThrows(IllegalStateException.class, () -> tool(connection)
                    .beginAuthoritative(new Baseline(UUID.randomUUID(), MANIFEST_VERSION)));

            // Mimic an already committed explicit node binding; LEGACY caller input must not bypass it.
            execute(connection, """
                    INSERT INTO eatwhat_identity.node_state(singleton,site_id,mode,instance_id,database_name,database_oid,cluster_identifier)
                    VALUES (true,'synthetic-campus','CAMPUS',?,current_database(),1,'synthetic-cluster')
                    """, UUID.randomUUID());
            assertThrows(IllegalStateException.class, () -> tool(connection).backfillBatch(EntityType.USERS, 1));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anOldRepeatableReadSnapshotCannotWriteAfterAuthoritativeOrReplicaBinding(boolean authoritative) throws Exception {
        String database = snapshotDatabase();
        try (Connection oldWriter = connect(database); Connection operator = connect(database)) {
            oldWriter.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            oldWriter.setAutoCommit(false);
            assertEquals(0, scalar(oldWriter, "SELECT count(*) FROM eatwhat_identity.baseline"));
            assertEquals(3, scalar(oldWriter, "SELECT count(*) FROM users"));
            var baseline = new Baseline(UUID.randomUUID(), MANIFEST_VERSION);
            if (authoritative) {
                tool(operator).beginAuthoritative(baseline);
            } else {
                tool(operator).beginReplica(baseline);
            }
            assertEquals(1, scalar(operator, "SELECT count(*) FROM eatwhat_identity.baseline"));
            assertEquals(0, scalar(oldWriter, "SELECT count(*) FROM eatwhat_identity.baseline"),
                    "The writer demonstrably retains the snapshot from before binding");
            for (String sql : List.of(
                    "INSERT INTO users(id,nick_name,password_hash) VALUES (2,'old-rr','synthetic')",
                    "UPDATE users SET avatar='old-rr.png' WHERE id=1",
                    "DELETE FROM contacts WHERE id=1",
                    "TRUNCATE TABLE user_info",
                    "UPDATE provinces SET name=name WHERE false",
                    "UPDATE cities SET name=name WHERE false")) {
                var beforeWrite = oldWriter.setSavepoint();
                SQLException frozen = assertThrows(SQLException.class, () -> execute(oldWriter, sql));
                assertEquals("55000", frozen.getSQLState());
                oldWriter.rollback(beforeWrite);
                oldWriter.releaseSavepoint(beforeWrite);
                assertEquals(0, scalar(oldWriter, "SELECT count(*) FROM eatwhat_identity.baseline"));
            }
            oldWriter.rollback();
            assertEquals(0, scalar(operator, "SELECT count(*) FROM users WHERE id=2 OR avatar='old-rr.png'"));
            assertEquals(1, scalar(operator, "SELECT count(*) FROM contacts"));
            assertEquals(1, scalar(operator, "SELECT count(*) FROM user_info"));
            assertEquals(20, scalar(operator, """
                    SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public' AND t.tgname='identity_baseline_permanent_guard'
                    """));
        }
    }

    @Test
    void failedBaselineBindingRollsBackPermanentGuardDdlAndMetadataTogether() throws Exception {
        String database = snapshotDatabase();
        try (Connection connection = connect(database)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            IllegalStateException unsafeOperator = assertThrows(IllegalStateException.class, () -> tool(connection)
                    .beginAuthoritative(new Baseline(UUID.randomUUID(), MANIFEST_VERSION)));
            assertTrue(unsafeOperator.getMessage().contains("READ_COMMITTED"));
            assertTrue(connection.getAutoCommit());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM eatwhat_identity.baseline"));
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            execute(connection, """
                    INSERT INTO users(id,nick_name,password_hash)
                    VALUES (1000000001,'outside-history','synthetic')
                    """);
            assertThrows(IllegalStateException.class, () -> tool(connection)
                    .beginAuthoritative(new Baseline(UUID.randomUUID(), MANIFEST_VERSION)));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM eatwhat_identity.baseline"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM eatwhat_identity.baseline_checkpoint"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pg_trigger WHERE tgname='identity_baseline_permanent_guard'"));
            execute(connection, "INSERT INTO users(id,nick_name,password_hash) VALUES (2,'after-rollback','synthetic')");
            execute(connection, "UPDATE users SET avatar='after-rollback.png' WHERE id=2");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM users WHERE id=2 AND avatar='after-rollback.png'"));
        }
    }

    @Test
    void concurrentOperatorBatchesSerializeWithoutDuplicatingOrSkippingHistoricalIdentities() throws Exception {
        String database = snapshotDatabase();
        Baseline baseline = new Baseline(UUID.randomUUID(), MANIFEST_VERSION);
        try (Connection connection = connect(database)) {
            tool(connection).beginAuthoritative(baseline);
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                try (Connection connection = connect(database)) {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Operator start barrier timed out");
                    }
                    return tool(connection).backfillBatch(EntityType.USERS, 1);
                }
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(1L, 5L), results.stream().map(Progress::lastNumericId).collect(java.util.stream.Collectors.toSet()));
        } finally {
            start.countDown();
        }
        try (Connection connection = connect(database)) {
            assertTrue(tool(connection).backfillBatch(EntityType.USERS, 1).completed());
            assertEquals(3, scalar(connection, "SELECT count(*) FROM eatwhat_identity.entity_identity WHERE entity_type='users'"));
            assertEquals(3, scalar(connection, "SELECT count(DISTINCT global_id) FROM eatwhat_identity.entity_identity WHERE entity_type='users'"));
        }
    }

    @Test
    void runtimeCanResolveIdentitiesButCannotWriteOrRunHistoryTools() throws Exception {
        String database = snapshotDatabase();
        String runtime = "manifest_runtime";
        String password = "synthetic-manifest-runtime";
        UUID globalId;
        try (Connection owner = connect(database)) {
            var tool = tool(owner);
            tool.beginAuthoritative(new Baseline(UUID.randomUUID(), MANIFEST_VERSION));
            tool.backfillBatch(EntityType.USERS, 1);
            globalId = readService(database).findGlobalId(EntityType.USERS, 1).orElseThrow();
            execute(owner, "CREATE ROLE " + runtime + " LOGIN PASSWORD 'synthetic-manifest-runtime'");
            execute(owner, "GRANT USAGE ON SCHEMA eatwhat_identity TO " + runtime);
            execute(owner, "GRANT SELECT ON eatwhat_identity.entity_identity TO " + runtime);
        }
        var dataSource = new DriverManagerDataSource(url(database), runtime, password);
        var reader = new EntityIdentityService(new JdbcTemplate(dataSource));
        assertEquals(globalId, reader.findGlobalId(EntityType.USERS, 1).orElseThrow());
        assertEquals(1L, reader.findNumericId(EntityType.USER_INFO, globalId).orElseThrow());
        try (Connection restricted = dataSource.getConnection()) {
            for (String sql : List.of("UPDATE eatwhat_identity.entity_identity SET origin_site='changed'",
                    "DELETE FROM eatwhat_identity.entity_identity", "SELECT * FROM eatwhat_identity.baseline",
                    "SELECT * FROM eatwhat_identity.baseline_checkpoint")) {
                assertEquals("42501", assertThrows(SQLException.class, () -> execute(restricted, sql)).getSQLState());
            }
            assertEquals("42501", assertThrows(SQLException.class,
                    () -> tool(restricted).backfillBatch(EntityType.USERS, 1)).getSQLState());
        }
    }

    private String snapshotDatabase() throws Exception {
        String database = "manifest_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection bootstrap = connect(IsolatedContainers.DATABASE)) {
            execute(bootstrap, "CREATE DATABASE " + database + " TEMPLATE template0");
        }
        Flyway.configure().dataSource(url(database), IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").target("15").cleanDisabled(true).load().migrate();
        try (Connection connection = connect(database)) {
            execute(connection, """
                    INSERT INTO users(id,nick_name,password_hash,created_at,updated_at) VALUES
                    (1,'history1','synthetic-hash','2020-01-01Z','2020-02-01Z'),
                    (5,'history5','synthetic-hash','2020-01-01Z','2020-02-01Z'),
                    (1000000000,'historymax','synthetic-hash','2020-01-01Z','2020-02-01Z')
                    """);
            execute(connection, "INSERT INTO user_info(id,signature,created_at,updated_at) VALUES (1,'synthetic','2020-01-01Z','2020-02-01Z')");
            execute(connection, "INSERT INTO privacy(id,account_id,created_at) VALUES (1,1,'2020-01-01Z')");
            execute(connection, "INSERT INTO contacts(id,account_id,email,created_at,updated_at) VALUES (1,1,'synthetic@example.invalid','2020-01-01Z','2020-02-01Z')");
        }
        Flyway.configure().dataSource(url(database), IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        return database;
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), IsolatedContainers.USER, IsolatedContainers.PASSWORD);
    }

    private static String url(String database) {
        return IsolatedContainers.jdbcUrl(POSTGRES, database);
    }

    private IdentityManifestService tool(Connection connection) {
        return new IdentityManifestService(connection, SiteProperties.Mode.LEGACY);
    }

    private EntityIdentityService readService(String database) {
        return new EntityIdentityService(new JdbcTemplate(new DriverManagerDataSource(
                url(database), IsolatedContainers.USER, IsolatedContainers.PASSWORD)));
    }

    private String businessSnapshot(Connection connection) throws SQLException {
        StringBuilder snapshot = new StringBuilder();
        for (String table : List.of("users", "user_info", "privacy", "contacts")) {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT row_to_json(b)::text FROM public." + table + " b ORDER BY id")) {
                while (rows.next()) {
                    snapshot.append(table).append(':').append(rows.getString(1)).append('\n');
                }
            }
        }
        return snapshot.toString();
    }

    private String identitySnapshot(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("""
                SELECT string_agg(entity_type||':'||numeric_id||':'||global_id||':'||COALESCE(origin_site,'<null>'),
                                  ',' ORDER BY entity_type,numeric_id) FROM eatwhat_identity.entity_identity
                """)) {
            row.next();
            return row.getString(1);
        }
    }

    private void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.execute();
        }
    }

    private long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }
}
