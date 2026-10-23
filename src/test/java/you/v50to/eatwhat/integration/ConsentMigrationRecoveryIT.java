package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConsentMigrationRecoveryIT {
    @Test
    void interruptedV20RollsBackTablesTriggersAndReplacedIdentityFunctionsThenRetriesWithoutChangingV19Data() {
        try (var postgres = IsolatedContainers.postgres("consent-migration")) {
            postgres.start();
            String name = "consent_upgrade_" + UUID.randomUUID().toString().replace("-", "");
            jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE)).execute("CREATE DATABASE " + name + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, name);
            JdbcTemplate admin = jdbc(url);
            migration(url, "19").migrate();
            admin.update("INSERT INTO public.users(nick_name,password_hash) VALUES('before-consent','synthetic-before-migration')");
            var users = admin.queryForList("SELECT * FROM public.users ORDER BY id");
            var identities = admin.queryForList("SELECT * FROM eatwhat_identity.entity_identity ORDER BY entity_type,numeric_id");
            String guard = admin.queryForObject("SELECT pg_get_functiondef('eatwhat_identity.guard_business_identity()'::regprocedure)", String.class);
            int history = admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
            admin.execute("""
                    CREATE FUNCTION public.fail_consent_migration_for_test() RETURNS event_trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF EXISTS(SELECT 1 FROM pg_event_trigger_ddl_commands()
                        WHERE command_tag='CREATE FUNCTION' AND object_identity LIKE 'eatwhat_consent.purge_account%') THEN
                        RAISE EXCEPTION 'Synthetic interrupted consent migration' USING ERRCODE='P0001';
                      END IF;
                    END $$
                    """);
            admin.execute("CREATE EVENT TRIGGER fail_consent_migration_for_test ON ddl_command_end EXECUTE FUNCTION public.fail_consent_migration_for_test()");
            // Keep this recovery test scoped to V20 when later additive migrations are introduced.
            Flyway v20 = migration(url, "20");
            assertThrows(FlywayException.class, v20::migrate);
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname='eatwhat_consent'", Integer.class));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='consent_account_revision'", Integer.class));
            assertEquals(history, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            assertEquals(guard, admin.queryForObject("SELECT pg_get_functiondef('eatwhat_identity.guard_business_identity()'::regprocedure)", String.class));
            assertEquals(users, admin.queryForList("SELECT * FROM public.users ORDER BY id"));
            assertEquals(identities, admin.queryForList("SELECT * FROM eatwhat_identity.entity_identity ORDER BY entity_type,numeric_id"));
            admin.execute("DROP EVENT TRIGGER fail_consent_migration_for_test");
            admin.execute("DROP FUNCTION public.fail_consent_migration_for_test()");
            assertEquals(1, v20.migrate().migrationsExecuted);
            assertEquals(0, v20.migrate().migrationsExecuted);
            v20.validate();
            assertEquals(5, admin.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='consent_account_revision'", Integer.class));
            assertEquals(users, admin.queryForList("SELECT * FROM public.users ORDER BY id"));
            assertEquals(identities, admin.queryForList("SELECT * FROM eatwhat_identity.entity_identity ORDER BY entity_type,numeric_id"));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.inbox", Integer.class));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM eatwhat_consent.subject", Integer.class),
                    "Migrating schema must not infer consent or seed external login projections for historical users");
            admin.update("UPDATE public.users SET avatar='synthetic-after-migration' WHERE nick_name='before-consent'");
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM eatwhat_consent.subject", Integer.class),
                    "Legacy writes with no node binding must remain compatible");
        }
    }

    private static Flyway migration(String url, String target) {
        var config = Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true);
        if (target != null) config.target(target);
        return config.load();
    }
    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }
}
