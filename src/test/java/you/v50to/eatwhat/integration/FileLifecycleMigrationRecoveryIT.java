package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V27 must roll back atomically and retry without changing the existing V26 schema or rows. */
class FileLifecycleMigrationRecoveryIT {
    private static final List<String> V27_TABLES = List.of(
            "upload_intent", "file_object", "file_revision", "file_replica", "file_key_alias",
            "file_reference", "file_orphan_candidate", "file_transfer_job");

    @Test
    void interruptedV27RollsBackAndRetryPreservesV26BusinessRowsAndChecksums() {
        try (var postgres = IsolatedContainers.postgres("file-v27-recovery")) {
            postgres.start();
            String database = "file_v27_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            Flyway v26 = migration(url, "26");
            assertEquals(25, v26.migrate().migrationsExecuted,
                    "V26 includes 25 migrations because V9 is intentionally absent");
            long user = admin.queryForObject("""
                    INSERT INTO public.users(nick_name,password_hash) VALUES('file-v26-user','synthetic') RETURNING id
                    """, Long.class);
            var userBefore = admin.queryForList("SELECT * FROM public.users WHERE id=?", user);
            var v26Checksums = checksums(admin);

            admin.execute("""
                    CREATE FUNCTION public.fail_v27_file_migration_for_test() RETURNS event_trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                      IF EXISTS (SELECT 1 FROM pg_event_trigger_ddl_commands()
                                 WHERE object_identity='eatwhat_file.file_revision' AND command_tag='CREATE TABLE') THEN
                        RAISE EXCEPTION 'Synthetic interrupted V27 migration' USING ERRCODE='P0001';
                      END IF;
                    END
                    $$
                    """);
            admin.execute("CREATE EVENT TRIGGER fail_v27_file_migration_for_test ON ddl_command_end "
                    + "EXECUTE FUNCTION public.fail_v27_file_migration_for_test()");

            Flyway v27 = migration(url, "27");
            assertThrows(FlywayException.class, v27::migrate);
            assertFalse(schemaExists(admin, "eatwhat_file"), "Failed V27 leaves no file schema behind");
            for (String table : V27_TABLES) {
                assertFalse(tableExists(admin, table), "Failed V27 leaves no table: " + table);
            }
            assertEquals(25, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            assertEquals(v26Checksums, checksums(admin), "Failed V27 cannot rewrite earlier migration checksums");
            assertEquals(userBefore, admin.queryForList("SELECT * FROM public.users WHERE id=?", user));

            admin.execute("DROP EVENT TRIGGER fail_v27_file_migration_for_test");
            admin.execute("DROP FUNCTION public.fail_v27_file_migration_for_test()");
            assertEquals(1, v27.migrate().migrationsExecuted, "Retry applies only V27");
            assertEquals(0, v27.migrate().migrationsExecuted, "A second migration is idempotent");
            v27.validate();
            assertTrue(schemaExists(admin, "eatwhat_file"));
            assertEquals(26, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            for (String table : V27_TABLES) assertTrue(tableExists(admin, table), "V27 table exists: " + table);
            assertEquals(v26Checksums, checksumsExcludingV27(admin), "All V1-V26 checksums remain unchanged");
            assertEquals(userBefore, admin.queryForList("SELECT * FROM public.users WHERE id=?", user));
        }
    }

    private static Flyway migration(String url, String target) {
        return Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").target(target).baselineOnMigrate(false).cleanDisabled(true).load();
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }

    private static boolean schemaExists(JdbcTemplate jdbc, String schema) {
        return jdbc.queryForObject("SELECT to_regnamespace(?) IS NOT NULL", Boolean.class, schema);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_tables WHERE schemaname='eatwhat_file' AND tablename=?)",
                Boolean.class, table);
    }

    private static List<Map<String, Object>> checksums(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank");
    }

    private static List<Map<String, Object>> checksumsExcludingV27(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success AND version<>'27' ORDER BY installed_rank");
    }
}
