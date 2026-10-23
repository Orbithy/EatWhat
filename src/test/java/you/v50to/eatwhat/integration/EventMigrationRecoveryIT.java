package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventMigrationRecoveryIT {
    @Test
    void v19FailureAfterSeveralTablesRollsBackAndRetryPreservesV18BusinessAndIdentity() {
        try (var postgres = IsolatedContainers.postgres("event-migration")) {
            postgres.start();
            String database = "event_upgrade_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);
            assertEquals(17, migration(url, "18").migrate().migrationsExecuted);
            admin.update("INSERT INTO users(nick_name,password_hash) VALUES ('before-events','synthetic')");
            var business = admin.queryForList("SELECT * FROM users ORDER BY id");
            var identities = admin.queryForList("SELECT * FROM eatwhat_identity.entity_identity ORDER BY numeric_id");
            admin.execute("""
                    CREATE FUNCTION public.reject_event_migration_for_test() RETURNS event_trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF EXISTS (SELECT 1 FROM pg_event_trigger_ddl_commands()
                                 WHERE object_identity='eatwhat_sync.pending' AND command_tag='CREATE TABLE') THEN
                        RAISE EXCEPTION 'Synthetic interrupted event migration' USING ERRCODE='P0001';
                      END IF;
                    END $$
                    """);
            admin.execute("CREATE EVENT TRIGGER reject_event_migration_for_test ON ddl_command_end EXECUTE FUNCTION public.reject_event_migration_for_test()");
            // Keep this recovery test scoped to V19 when later additive migrations are introduced.
            Flyway v19 = migration(url, "19");
            assertThrows(FlywayException.class, v19::migrate);
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname='eatwhat_sync'", Integer.class));
            assertEquals(17, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            assertEquals(business, admin.queryForList("SELECT * FROM users ORDER BY id"));
            assertEquals(identities, admin.queryForList("SELECT * FROM eatwhat_identity.entity_identity ORDER BY numeric_id"));
            admin.execute("DROP EVENT TRIGGER reject_event_migration_for_test");
            admin.execute("DROP FUNCTION public.reject_event_migration_for_test()");
            assertEquals(1, v19.migrate().migrationsExecuted);
            assertEquals(0, v19.migrate().migrationsExecuted);
            v19.validate();
            assertEquals(7, admin.queryForObject("""
                    SELECT count(*) FROM pg_tables WHERE schemaname='eatwhat_sync'
                      AND tablename IN ('outbox','delivery','inbox','entity_state','pending','conflict','command_dedup')
                    """, Integer.class));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.inbox", Integer.class));
            assertEquals(business, admin.queryForList("SELECT * FROM users ORDER BY id"));
            assertEquals(identities, admin.queryForList("SELECT * FROM eatwhat_identity.entity_identity ORDER BY numeric_id"));
        }
    }

    private Flyway migration(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }
}
