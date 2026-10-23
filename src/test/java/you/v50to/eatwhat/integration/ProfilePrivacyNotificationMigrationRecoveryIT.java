package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilePrivacyNotificationMigrationRecoveryIT {
    private static final List<String> V24_TABLES = List.of(
            "profile_domain", "profile_operation", "profile_field_state", "profile_conflict", "profile_conflict_audit",
            "privacy_domain", "privacy_operation", "notification_read_operation", "notification_read_state",
            "notification_read_batch", "notification_read_member", "notification_read_domain");

    @Test
    void interruptedV24RollsBackAndRetryPreservesExistingProfilePrivacyAndNotificationRows() {
        try (var postgres = IsolatedContainers.postgres("profile-notification-v24-recovery")) {
            postgres.start();
            String database = "profile_v24_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            Flyway v23 = migration(url, "23");
            assertEquals(22, v23.migrate().migrationsExecuted);
            long owner = admin.queryForObject("""
                    INSERT INTO public.users(nick_name,password_hash) VALUES('v24-owner','synthetic') RETURNING id
                    """, Long.class);
            long actor = admin.queryForObject("""
                    INSERT INTO public.users(nick_name,password_hash) VALUES('v24-actor','synthetic') RETURNING id
                    """, Long.class);
            admin.update("INSERT INTO public.user_info(id,gender,signature) VALUES(?, 'other', 'before V24')", owner);
            admin.update("INSERT INTO public.privacy(account_id,following,follower) VALUES(?,false,true)", owner);
            long notification = admin.queryForObject("""
                    INSERT INTO public.notifications(account_id,actor_id,type,target_type,target_id,data,created_at,expires_at)
                    VALUES(?,?,'follow','user',?,?::jsonb,'2026-10-01T00:00:00Z','2026-11-01T00:00:00Z')
                    RETURNING id
                    """, Long.class, owner, actor, owner, "{\"legacy\":true}");

            var profileBefore = admin.queryForList("SELECT * FROM public.user_info WHERE id=?", owner);
            var privacyBefore = admin.queryForList("SELECT * FROM public.privacy WHERE account_id=?", owner);
            var notificationBefore = admin.queryForList("SELECT * FROM public.notifications WHERE id=?", notification);
            String notificationGidBefore = admin.queryForObject("""
                    SELECT global_id::text FROM eatwhat_identity.entity_identity
                    WHERE entity_type='notifications' AND numeric_id=?
                    """, String.class, notification);
            var v23Checksums = checksums(admin);

            admin.execute("""
                    CREATE FUNCTION public.fail_v24_profile_migration_for_test() RETURNS event_trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                      IF EXISTS (SELECT 1 FROM pg_event_trigger_ddl_commands()
                                 WHERE object_identity='eatwhat_sync.profile_operation' AND command_tag='CREATE TABLE') THEN
                        RAISE EXCEPTION 'Synthetic interrupted V24 migration' USING ERRCODE='P0001';
                      END IF;
                    END $$
                    """);
            admin.execute("CREATE EVENT TRIGGER fail_v24_profile_migration_for_test ON ddl_command_end "
                    + "EXECUTE FUNCTION public.fail_v24_profile_migration_for_test()");

            Flyway latest = migration(url, "24");
            assertThrows(FlywayException.class, latest::migrate);
            assertEquals(22, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            assertFalse(admin.queryForObject("SELECT to_regclass('eatwhat_sync.profile_operation') IS NOT NULL", Boolean.class));
            for (String table : V24_TABLES) {
                assertFalse(tableExists(admin, table), "V24 table must roll back as one migration: " + table);
            }
            assertEquals(profileBefore, admin.queryForList("SELECT * FROM public.user_info WHERE id=?", owner));
            assertEquals(privacyBefore, admin.queryForList("SELECT * FROM public.privacy WHERE account_id=?", owner));
            assertEquals(notificationBefore, admin.queryForList("SELECT * FROM public.notifications WHERE id=?", notification));
            assertEquals(v23Checksums, checksums(admin));

            admin.execute("DROP EVENT TRIGGER fail_v24_profile_migration_for_test");
            admin.execute("DROP FUNCTION public.fail_v24_profile_migration_for_test()");
            assertEquals(1, latest.migrate().migrationsExecuted,
                    "Retry applies only V24 after its failed transactional attempt rolled back");
            assertEquals(0, latest.migrate().migrationsExecuted);
            latest.validate();
            assertEquals(23, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            for (String table : V24_TABLES) {
                assertTrue(tableExists(admin, table), "V24 table should exist after retry: " + table);
            }
            assertEquals(v23Checksums, checksumsExcludingV24(admin));
            assertEquals(profileBefore, admin.queryForList("SELECT * FROM public.user_info WHERE id=?", owner));
            assertEquals(privacyBefore, admin.queryForList("SELECT * FROM public.privacy WHERE account_id=?", owner));
            assertEquals(notificationBefore, admin.queryForList("SELECT * FROM public.notifications WHERE id=?", notification));
            assertEquals(notificationGidBefore, notificationGid(admin, notification));
            assertEquals(notification, admin.queryForObject(
                    "SELECT id FROM public.notifications WHERE account_id=? AND actor_id=?", Long.class, owner, actor));
            assertEquals(notificationBefore, admin.queryForList("SELECT * FROM public.notifications WHERE id=?", notification),
                    "The original expiry timestamp and notification payload remain unchanged");
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class),
                    "Schema migration must not emit profile, privacy, or notification sync events");
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

    private static String notificationGid(JdbcTemplate jdbc, long notificationId) {
        return jdbc.queryForObject("""
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type='notifications' AND numeric_id=?
                """, String.class, notificationId);
    }

    private static List<Map<String, Object>> checksums(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank");
    }

    private static List<Map<String, Object>> checksumsExcludingV24(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success AND version<>'24' ORDER BY installed_rank");
    }
}
