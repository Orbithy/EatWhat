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

/** V25 is additive: an interrupted migration must preserve the V24 relation/public baseline byte-for-byte. */
class RelationCausalMigrationRecoveryIT {
    private static final List<String> V25_TABLES = List.of("relation_causal_state", "relation_causal_fact");

    @Test
    void interruptedV25RollsBackAndRetryPreservesV24RelationsIdentitiesAndNoticeExpiry() {
        try (var postgres = IsolatedContainers.postgres("relation-causal-v25-recovery")) {
            postgres.start();
            String database = "relation_v25_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            Flyway v24 = migration(url, "24");
            assertEquals(23, v24.migrate().migrationsExecuted,
                    "The V24 target includes every existing migration except the intentionally absent V9");
            long actor = admin.queryForObject("""
                    INSERT INTO public.users(nick_name,password_hash) VALUES('v25-actor','synthetic') RETURNING id
                    """, Long.class);
            long owner = admin.queryForObject("""
                    INSERT INTO public.users(nick_name,password_hash) VALUES('v25-owner','synthetic') RETURNING id
                    """, Long.class);
            long follow = admin.queryForObject("""
                    INSERT INTO public.follow(account_id,target_id) VALUES(?,?) RETURNING id
                    """, Long.class, actor, owner);
            long notification = admin.queryForObject("""
                    INSERT INTO public.notifications(account_id,actor_id,type,target_type,target_id,data,created_at,expires_at)
                    VALUES(?,?,'follow','user',?,?::jsonb,'2026-10-01T00:00:00Z','2026-11-01T00:00:00Z')
                    RETURNING id
                    """, Long.class, owner, actor, owner, "{\"legacy\":true}");

            var followBefore = admin.queryForList("SELECT * FROM public.follow WHERE id=?", follow);
            var notificationBefore = admin.queryForList("SELECT * FROM public.notifications WHERE id=?", notification);
            String followGidBefore = identity(admin, "follow", follow);
            String notificationGidBefore = identity(admin, "notifications", notification);
            var v24Checksums = checksums(admin);
            int outboxBefore = admin.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class);

            admin.execute("""
                    CREATE FUNCTION public.fail_v25_relation_causality_for_test() RETURNS event_trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                      IF EXISTS (SELECT 1 FROM pg_event_trigger_ddl_commands()
                                 WHERE object_identity='eatwhat_sync.relation_causal_fact' AND command_tag='CREATE TABLE') THEN
                        RAISE EXCEPTION 'Synthetic interrupted V25 migration' USING ERRCODE='P0001';
                      END IF;
                    END
                    $$
                    """);
            admin.execute("CREATE EVENT TRIGGER fail_v25_relation_causality_for_test ON ddl_command_end "
                    + "EXECUTE FUNCTION public.fail_v25_relation_causality_for_test()");

            // Keep this test scoped to the V24 -> V25 causal migration. V26 has its own recovery test.
            Flyway latest = migration(url, "25");
            assertThrows(FlywayException.class, latest::migrate);
            assertEquals(23, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            for (String table : V25_TABLES) assertFalse(tableExists(admin, table), "V25 DDL rolled back: " + table);
            assertEquals(v24Checksums, checksums(admin), "The failed V25 attempt cannot rewrite any earlier checksum");
            assertEquals(followBefore, admin.queryForList("SELECT * FROM public.follow WHERE id=?", follow));
            assertEquals(notificationBefore, admin.queryForList("SELECT * FROM public.notifications WHERE id=?", notification));
            assertEquals(followGidBefore, identity(admin, "follow", follow));
            assertEquals(notificationGidBefore, identity(admin, "notifications", notification));
            assertEquals(outboxBefore, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class));

            admin.execute("DROP EVENT TRIGGER fail_v25_relation_causality_for_test");
            admin.execute("DROP FUNCTION public.fail_v25_relation_causality_for_test()");
            assertEquals(1, latest.migrate().migrationsExecuted,
                    "Retry applies only V25 after the interrupted transactional attempt");
            assertEquals(0, latest.migrate().migrationsExecuted, "A second migrate is idempotent");
            latest.validate();
            assertEquals(24, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            for (String table : V25_TABLES) assertTrue(tableExists(admin, table), "V25 table exists: " + table);
            assertEquals(v24Checksums, checksumsExcludingV25(admin));
            assertEquals(followBefore, admin.queryForList("SELECT * FROM public.follow WHERE id=?", follow));
            assertEquals(notificationBefore, admin.queryForList("SELECT * FROM public.notifications WHERE id=?", notification));
            assertEquals(followGidBefore, identity(admin, "follow", follow));
            assertEquals(notificationGidBefore, identity(admin, "notifications", notification));
            assertEquals(outboxBefore, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class),
                    "Migration and recovery must not publish historical relation or notice operations");
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

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pg_tables WHERE schemaname='eatwhat_sync' AND tablename=?)
                """, Boolean.class, table);
    }

    private static String identity(JdbcTemplate jdbc, String type, long id) {
        return jdbc.queryForObject("""
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type=? AND numeric_id=?
                """, String.class, type, id);
    }

    private static List<Map<String, Object>> checksums(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank");
    }

    private static List<Map<String, Object>> checksumsExcludingV25(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success AND version<>'25' ORDER BY installed_rank");
    }
}
