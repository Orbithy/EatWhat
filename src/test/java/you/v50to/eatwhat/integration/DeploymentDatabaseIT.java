package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;
import you.v50to.eatwhat.config.FileSchemaMigrationCallback;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the deployment init script in a new test-owned PostGIS volume, then uses the real app role. */
class DeploymentDatabaseIT {
    private static final String APPLICATION_DATABASE = "eatwhat_deployment_test";
    private static final String APPLICATION_USER = "eatwhat_application";
    // The literal quote, dollar variable syntax and $$ must survive shell/psql quoting unchanged.
    private static final String APPLICATION_PASSWORD = "synthetic-'quoted-$value-$$-password";
    private static final String INIT_SCRIPT = "/docker-entrypoint-initdb.d/20-create-application-db.sh";

    @Test
    void initScriptCreatesAnEmptyApplicationDatabaseAndRestrictedRuntimeRoleForAdminMigrations() throws Exception {
        Path script = Path.of("deploy", "initdb", "20-create-application-db.sh").toAbsolutePath();
        assertTrue(Files.isRegularFile(script), "The actual deployment init script must be present");
        try (GenericContainer<?> postgres = IsolatedContainers.postgres("deployment-initdb")
                .withLabel("eatwhat.test.scenario", "deployment-initdb")
                .withEnv("POSTGRES_DB", "postgres")
                .withEnv("PG_DB", APPLICATION_DATABASE)
                .withEnv("PG_APP_USER", APPLICATION_USER)
                .withEnv("PG_APP_PASSWORD", APPLICATION_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath(script, 0755), INIT_SCRIPT)) {
            postgres.start();
            assertTrue(postgres.getLogs().contains(INIT_SCRIPT), "The image entrypoint must execute the copied script");
            String applicationUrl = IsolatedContainers.jdbcUrl(postgres, APPLICATION_DATABASE);

            try (Connection bootstrap = adminConnection(postgres, "postgres");
                 Connection administrator = adminConnection(postgres, APPLICATION_DATABASE)) {
                assertTrue(extensions(bootstrap).contains("postgis"),
                        "The image initializes PostGIS in its bootstrap database before the 20 script");
                assertEquals(Set.of("plpgsql"), extensions(administrator),
                        "The application database must come from template0, without inherited PostGIS/citext");
                assertEquals(0, scalarLong(administrator, "SELECT count(*) FROM pg_tables WHERE schemaname='public'"));
                assertEquals(IsolatedContainers.USER, scalarString(administrator,
                        "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()"));
            }

            // TCP authentication proves the unmodified special-character password actually works.
            try (Connection application = DriverManager.getConnection(applicationUrl, APPLICATION_USER, APPLICATION_PASSWORD)) {
                assertEquals(APPLICATION_USER, scalarString(application, "SELECT current_user"));
                assertRestrictedRole(application);
            }
            SQLException wrongPassword = assertThrows(SQLException.class, () -> {
                try (Connection ignored = DriverManager.getConnection(applicationUrl, APPLICATION_USER,
                        "synthetic-wrong-password")) {
                    fail("An incorrect application password must not authenticate over TCP");
                }
            });
            assertEquals("28P01", wrongPassword.getSQLState());

            Flyway flyway = Flyway.configure()
                    .dataSource(applicationUrl, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .validateOnMigrate(true)
                    .cleanDisabled(true)
                    .callbacks(new GlobalIdentityMigrationCallback(new SiteProperties(), APPLICATION_USER),
                            new FileSchemaMigrationCallback(APPLICATION_USER))
                    .load();
            assertEquals(50, flyway.migrate().migrationsExecuted);
            assertEquals("51", flyway.info().current().getVersion().getVersion());
            flyway.validate();

            try (Connection application = DriverManager.getConnection(applicationUrl, APPLICATION_USER, APPLICATION_PASSWORD)) {
                assertTrue(extensions(application).containsAll(Set.of("postgis", "citext")));
                assertRestrictedRole(application);
                assertFileRolePermissions(application);
                long accountId;
                try (var insert = application.prepareStatement(
                        "INSERT INTO users(nick_name,password_hash) VALUES (?,?) RETURNING id")) {
                    insert.setString(1, "deploytest");
                    insert.setString(2, "synthetic-not-a-real-password-hash");
                    try (var result = insert.executeQuery()) {
                        assertTrue(result.next());
                        accountId = result.getLong(1);
                        assertTrue(accountId > 0, "The restricted role must be able to use the generated ID sequence");
                    }
                }
                assertEquals(accountId, scalarLong(application,
                        "SELECT currval(pg_get_serial_sequence('public.users','id'))"));
                assertEquals(accountId + 1, scalarLong(application,
                        "SELECT nextval(pg_get_serial_sequence('public.users','id'))"));
                try (var update = application.prepareStatement("UPDATE users SET avatar=? WHERE id=?")) {
                    update.setString(1, "avatar/deployment-test.png");
                    update.setLong(2, accountId);
                    assertEquals(1, update.executeUpdate());
                }
                assertEquals("avatar/deployment-test.png", scalarString(application,
                        "SELECT avatar FROM users WHERE nick_name='deploytest'"));
                try (var delete = application.prepareStatement("DELETE FROM users WHERE id=?")) {
                    delete.setLong(1, accountId);
                    assertEquals(1, delete.executeUpdate());
                }
                assertEquals(0, scalarLong(application, "SELECT count(*) FROM users WHERE nick_name='deploytest'"));

                for (String ddl : new String[]{
                        "CREATE TABLE public.forbidden_runtime_table (id bigint)",
                        "CREATE SCHEMA forbidden_runtime_schema",
                        "ALTER TABLE public.users ADD COLUMN forbidden_runtime_column text",
                        "ALTER SEQUENCE public.users_id_seq RESTART WITH 1",
                        "DROP TABLE public.users",
                        "TRUNCATE TABLE public.users"
                }) {
                    SQLException denied = assertThrows(SQLException.class, () -> {
                        try (Statement statement = application.createStatement()) {
                            statement.execute(ddl);
                        }
                    }, "The application role must not execute: " + ddl);
                    assertEquals("42501", denied.getSQLState(), "DDL must fail for insufficient privilege");
                }
                assertEquals(0, scalarLong(application, "SELECT count(*) FROM users"),
                        "The role must remain usable after rejected DDL statements");
            }
            assertEquals(0, flyway.migrate().migrationsExecuted, "Runtime CRUD must leave migration history intact");

            var campus = new SiteProperties();
            campus.setMode(SiteProperties.Mode.CAMPUS);
            campus.setId("deployment-campus");
            campus.setInstanceId(UUID.fromString("00000000-0000-4000-8000-000000000001"));
            try (Connection administrator = adminConnection(postgres, APPLICATION_DATABASE)) {
                new GlobalIdentityMigrationCallback(campus, APPLICATION_USER).activateOrVerify(administrator);
            }
            try (Connection application = DriverManager.getConnection(applicationUrl, APPLICATION_USER, APPLICATION_PASSWORD)) {
                long siteUser = scalarLong(application,
                        "INSERT INTO users(nick_name,password_hash) VALUES ('site-user','synthetic') RETURNING id");
                assertEquals(1_000_000_001L, siteUser);
                assertEquals("deployment-campus", scalarString(application,
                        "SELECT origin_site FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=" + siteUser));
                assertNotNull(scalarString(application,
                        "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=" + siteUser));
                for (String forbidden : new String[]{
                        "DELETE FROM eatwhat_identity.entity_identity",
                        "UPDATE eatwhat_identity.node_state SET mode='EXTERNAL'",
                        "INSERT INTO eatwhat_identity.baseline(singleton,baseline_id,manifest_version,baseline_role) VALUES (true,gen_random_uuid(),1,'AUTHORITATIVE')",
                        "SELECT setval('public.users_id_seq',1000000001,false)"
                }) {
                    SQLException denied = assertThrows(SQLException.class, () -> {
                        try (Statement statement = application.createStatement()) {
                            statement.execute(forbidden);
                        }
                    });
                    assertEquals("42501", denied.getSQLState(), "Runtime must not alter allocation or identity metadata");
                }
            }
        }
    }

    private Connection adminConnection(GenericContainer<?> postgres, String database) throws SQLException {
        return DriverManager.getConnection(IsolatedContainers.jdbcUrl(postgres, database),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD);
    }

    private void assertRestrictedRole(Connection application) throws SQLException {
        try (Statement statement = application.createStatement();
             var role = statement.executeQuery("""
                     SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls, rolcanlogin
                     FROM pg_roles WHERE rolname=current_user
                     """)) {
            assertTrue(role.next());
            for (int column = 1; column <= 5; column++) {
                assertFalse(role.getBoolean(column), "Application role must not have elevated role capabilities");
            }
            assertTrue(role.getBoolean(6));
        }
    }

    private void assertFileRolePermissions(Connection application) throws SQLException {
        try (Statement statement = application.createStatement();
             var grants = statement.executeQuery("""
                     SELECT has_schema_privilege(current_user,'eatwhat_file','USAGE'),
                            has_table_privilege(current_user,'eatwhat_file.upload_intent','SELECT'),
                            has_table_privilege(current_user,'eatwhat_file.upload_intent','INSERT'),
                            has_table_privilege(current_user,'eatwhat_file.upload_intent','UPDATE'),
                            has_table_privilege(current_user,'eatwhat_file.upload_intent','DELETE'),
                            has_table_privilege(current_user,'eatwhat_file.file_revision','INSERT'),
                            has_table_privilege(current_user,'eatwhat_file.file_revision','UPDATE')
                     """)) {
            assertTrue(grants.next());
            assertTrue(grants.getBoolean(1), "The runtime role needs file schema access");
            assertTrue(grants.getBoolean(2) && grants.getBoolean(3) && grants.getBoolean(4),
                    "The lifecycle role needs intent read/claim/update operations");
            assertFalse(grants.getBoolean(5), "The runtime role cannot delete upload intents");
            assertTrue(grants.getBoolean(6), "The lifecycle role can add immutable verified revisions");
            assertFalse(grants.getBoolean(7), "The runtime role cannot rewrite immutable revision metadata");
        }
        // V29/V30: release events, tombstones and inventory findings.
        try (Statement statement = application.createStatement();
             var grants = statement.executeQuery("""
                     SELECT has_table_privilege(current_user,'eatwhat_file.file_reference_event','INSERT'),
                            has_sequence_privilege(current_user,'eatwhat_file.file_reference_event_event_id_seq','USAGE'),
                            has_table_privilege(current_user,'eatwhat_file.file_reference_event','UPDATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_reference_event','DELETE'),
                            has_table_privilege(current_user,'eatwhat_file.file_reference_live','SELECT'),
                            has_table_privilege(current_user,'eatwhat_file.file_tombstone','INSERT'),
                            has_table_privilege(current_user,'eatwhat_file.file_tombstone','DELETE'),
                            has_table_privilege(current_user,'eatwhat_file.file_inventory_finding','INSERT'),
                            has_sequence_privilege(current_user,'eatwhat_file.file_inventory_finding_finding_id_seq','USAGE'),
                            has_table_privilege(current_user,'eatwhat_file.file_inventory_finding','UPDATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_gc_run','DELETE')
                     """)) {
            assertTrue(grants.next());
            assertTrue(grants.getBoolean(1) && grants.getBoolean(2),
                    "The runtime role can append release events, including drawing event ids from the sequence");
            assertFalse(grants.getBoolean(3) || grants.getBoolean(4), "Release events are append-only for the runtime role");
            assertTrue(grants.getBoolean(5), "The runtime role can read the live reference view");
            assertTrue(grants.getBoolean(6), "Tombstone maintenance runs as the runtime role");
            assertFalse(grants.getBoolean(7), "The runtime role cannot delete tombstones");
            assertTrue(grants.getBoolean(8) && grants.getBoolean(9), "The runtime role can append inventory findings");
            assertFalse(grants.getBoolean(10), "Inventory findings are append-only");
            assertFalse(grants.getBoolean(11), "The runtime role cannot delete GC runs");
        }
        // V31: GC candidates are a retained state machine (uuid key, so no sequence). Row operations the executor
        // needs: read, create (detect), update (claim/fence/finish). Never delete or truncate.
        try (Statement statement = application.createStatement();
             var grants = statement.executeQuery("""
                     SELECT has_table_privilege(current_user,'eatwhat_file.file_gc_candidate','SELECT'),
                            has_table_privilege(current_user,'eatwhat_file.file_gc_candidate','INSERT'),
                            has_table_privilege(current_user,'eatwhat_file.file_gc_candidate','UPDATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_gc_candidate','DELETE'),
                            has_table_privilege(current_user,'eatwhat_file.file_gc_candidate','TRUNCATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_gc_run','UPDATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_replica','UPDATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_tombstone','UPDATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_orphan_candidate','UPDATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_replica','DELETE')
                     """)) {
            assertTrue(grants.next());
            assertTrue(grants.getBoolean(1) && grants.getBoolean(2) && grants.getBoolean(3),
                    "The GC executor can read, detect and update candidates");
            assertFalse(grants.getBoolean(4) || grants.getBoolean(5), "GC candidates are never deleted or truncated by the runtime role");
            assertTrue(grants.getBoolean(6), "The GC records its run results");
            assertTrue(grants.getBoolean(7), "The GC marks the local replica DELETED");
            assertTrue(grants.getBoolean(8), "The GC row-locks tombstones (FOR UPDATE needs UPDATE)");
            assertTrue(grants.getBoolean(9), "The GC marks orphan candidates CLEANED");
            assertFalse(grants.getBoolean(10), "Replicas are never row-deleted");
        }
        // V32: the CDN invalidation queue (uuid key: no sequence). The tombstone trigger inserts tasks as the runtime
        // role and the worker claims/updates them; nothing deletes them.
        try (Statement statement = application.createStatement();
             var grants = statement.executeQuery("""
                     SELECT has_table_privilege(current_user,'eatwhat_file.file_cdn_invalidation','SELECT'),
                            has_table_privilege(current_user,'eatwhat_file.file_cdn_invalidation','INSERT'),
                            has_table_privilege(current_user,'eatwhat_file.file_cdn_invalidation','UPDATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_cdn_invalidation','DELETE'),
                            has_table_privilege(current_user,'eatwhat_file.file_cdn_invalidation','TRUNCATE'),
                            has_table_privilege(current_user,'eatwhat_file.file_replica_alert','INSERT')
                     """)) {
            assertTrue(grants.next());
            assertTrue(grants.getBoolean(1) && grants.getBoolean(2) && grants.getBoolean(3),
                    "The CDN invalidation worker and the tombstone trigger need read, enqueue and claim/update");
            assertFalse(grants.getBoolean(4) || grants.getBoolean(5), "CDN invalidation tasks are never deleted or truncated by the runtime role");
            assertTrue(grants.getBoolean(6), "The worker appends alerts");
        }
        // V33: the multipart upload id column rides on the existing table-level grants (no new table or sequence).
        try (Statement statement = application.createStatement();
             var grants = statement.executeQuery("""
                     SELECT has_column_privilege(current_user,'eatwhat_file.file_transfer_job','multipart_upload_id','UPDATE'),
                            has_column_privilege(current_user,'eatwhat_file.file_transfer_job','multipart_upload_id','SELECT'),
                            has_table_privilege(current_user,'eatwhat_file.file_transfer_job','DELETE')
                     """)) {
            assertTrue(grants.next());
            assertTrue(grants.getBoolean(1) && grants.getBoolean(2),
                    "The replication worker can record and clear the multipart upload id of its leased job");
            assertFalse(grants.getBoolean(3), "Transfer jobs are never deleted by the runtime role");
        }
    }

    private Set<String> extensions(Connection connection) throws SQLException {
        Set<String> values = new HashSet<>();
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT extname FROM pg_extension")) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
