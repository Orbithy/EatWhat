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

/** V26 must be additive and retryable while preserving the existing directory and numeric identities. */
class CatalogMigrationRecoveryIT {
    private static final List<String> V26_TABLES = List.of(
            "catalog_object", "catalog_revision", "catalog_natural_key", "catalog_candidate",
            "catalog_conflict", "catalog_conflict_audit", "catalog_resolution_receipt",
            "catalog_head_conflict", "catalog_head_conflict_audit", "catalog_head_resolution_receipt",
            "catalog_import_batch",
            "catalog_hub_source", "catalog_assignment", "catalog_backfill_job", "catalog_backfill_publication",
            "catalog_managed_domain", "catalog_resolution_receipt",
            "catalog_business_write_context", "catalog_withdrawal_write_context",
            "catalog_business_write_pending");

    @Test
    void interruptedV26RollsBackAndRetryPreservesV25CatalogSourceRowsAndIdentities() {
        try (var postgres = IsolatedContainers.postgres("catalog-v26-recovery")) {
            postgres.start();
            String database = "catalog_v26_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            Flyway v25 = migration(url, "25");
            assertEquals(24, v25.migrate().migrationsExecuted,
                    "V25 contains 24 migrations because V9 is intentionally absent");
            long user = admin.queryForObject("""
                    INSERT INTO public.users(nick_name,password_hash) VALUES('catalog-v26-user','synthetic') RETURNING id
                    """, Long.class);
            long hub = admin.queryForObject("""
                    INSERT INTO public.hub(name,center,gcj_lng,gcj_lat)
                    VALUES('V25 hub',ST_SetSRID(ST_MakePoint(117.0,36.0),4326),117.1,36.1) RETURNING id
                    """, Long.class);
            long restaurant = admin.queryForObject("""
                    INSERT INTO public.restaurant(name,address,location,gcj_lng,gcj_lat,hub_id,poi)
                    VALUES('V25 restaurant','synthetic address',ST_SetSRID(ST_MakePoint(117.0,36.0),4326)::geography,
                           117.1,36.1,?,'v25-poi-stable') RETURNING id
                    """, Long.class, hub);
            long food = admin.queryForObject("""
                    INSERT INTO public.foods(account_id,restaurant_id,name,description,price,category,picture_url)
                    VALUES(?,?,'V25 dish','synthetic',12.50,'staple',ARRAY['foods/v25/stable-key']) RETURNING id
                    """, Long.class, user, restaurant);
            long tag = admin.queryForObject("""
                    INSERT INTO public.food_tags(name,tag_type,owner_id,normalized_name)
                    VALUES('V25 tag','custom',?,'v25-tag') RETURNING id
                    """, Long.class, user);
            long tagging = admin.queryForObject("""
                    INSERT INTO public.food_taggings(food_id,tag_id,account_id) VALUES(?,?,?) RETURNING id
                    """, Long.class, food, tag, user);
            long notification = admin.queryForObject("""
                    INSERT INTO public.notifications(account_id,actor_id,type,target_type,target_id,data,created_at,expires_at)
                    VALUES(?,?,'follow','user',?,?::jsonb,'2026-10-01T00:00:00Z','2026-10-31T00:00:00Z') RETURNING id
                    """, Long.class, user, user, user, "{\"synthetic\":true}");

            String userBefore = row(admin, "users", user);
            String hubBefore = row(admin, "hub", hub);
            String restaurantBefore = row(admin, "restaurant", restaurant);
            String foodBefore = row(admin, "foods", food);
            String tagBefore = row(admin, "food_tags", tag);
            String taggingBefore = row(admin, "food_taggings", tagging);
            String notificationBefore = row(admin, "notifications", notification);
            List<Map<String, Object>> identitiesBefore = admin.queryForList(
                    "SELECT entity_type,numeric_id,global_id,origin_site FROM eatwhat_identity.entity_identity ORDER BY entity_type,numeric_id");
            List<Map<String, Object>> v25Checksums = checksums(admin);
            List<String> catalogObjectsBefore = catalogObjects(admin);
            int outboxBefore = admin.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class);

            admin.execute("""
                    CREATE FUNCTION public.fail_v26_catalog_for_test() RETURNS event_trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                      IF EXISTS (SELECT 1 FROM pg_event_trigger_ddl_commands()
                                 WHERE object_identity='eatwhat_sync.catalog_revision' AND command_tag='CREATE TABLE') THEN
                        RAISE EXCEPTION 'Synthetic interrupted V26 migration' USING ERRCODE='P0001';
                      END IF;
                    END
                    $$
                    """);
            admin.execute("CREATE EVENT TRIGGER fail_v26_catalog_for_test ON ddl_command_end "
                    + "EXECUTE FUNCTION public.fail_v26_catalog_for_test()");

            // Keep this recovery test scoped to V26 when later additive migrations are introduced.
            Flyway v26 = migration(url, "26");
            assertThrows(FlywayException.class, v26::migrate);
            assertEquals(24, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            assertEquals(catalogObjectsBefore, catalogObjects(admin),
                    "Failed V26 DDL leaves no catalog relations, sequences, triggers, or functions behind");
            for (String table : V26_TABLES) {
                assertFalse(tableExists(admin, table), "failed transactional V26 DDL leaves no table: " + table);
            }
            assertEquals(v25Checksums, checksums(admin), "Failed V26 cannot rewrite any V1-V25 checksum");
            assertEquals(userBefore, row(admin, "users", user));
            assertEquals(hubBefore, row(admin, "hub", hub));
            assertEquals(restaurantBefore, row(admin, "restaurant", restaurant));
            assertEquals(foodBefore, row(admin, "foods", food));
            assertEquals(tagBefore, row(admin, "food_tags", tag));
            assertEquals(taggingBefore, row(admin, "food_taggings", tagging));
            assertEquals(notificationBefore, row(admin, "notifications", notification));
            assertEquals(identitiesBefore, admin.queryForList(
                    "SELECT entity_type,numeric_id,global_id,origin_site FROM eatwhat_identity.entity_identity ORDER BY entity_type,numeric_id"));
            assertEquals(outboxBefore, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class));

            admin.execute("DROP EVENT TRIGGER fail_v26_catalog_for_test");
            admin.execute("DROP FUNCTION public.fail_v26_catalog_for_test()");
            assertEquals(1, v26.migrate().migrationsExecuted, "Retry applies only V26");
            assertEquals(0, v26.migrate().migrationsExecuted, "A second migration is idempotent");
            v26.validate();
            assertEquals(25, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            for (String table : V26_TABLES) assertTrue(tableExists(admin, table), "V26 table exists: " + table);
            assertEquals(v25Checksums, checksumsExcludingV26(admin), "All V1-V25 migration checksums remain byte-for-byte stable");
            assertEquals(userBefore, row(admin, "users", user));
            assertEquals(hubBefore, row(admin, "hub", hub));
            assertEquals(restaurantBefore, row(admin, "restaurant", restaurant));
            assertEquals(foodBefore, row(admin, "foods", food));
            assertEquals(tagBefore, row(admin, "food_tags", tag));
            assertEquals(taggingBefore, row(admin, "food_taggings", tagging));
            assertEquals(notificationBefore, row(admin, "notifications", notification),
                    "Directory migration cannot rewrite an existing notification's numeric identity or TTL");
            assertEquals(identitiesBefore, admin.queryForList(
                    "SELECT entity_type,numeric_id,global_id,origin_site FROM eatwhat_identity.entity_identity ORDER BY entity_type,numeric_id"));
            assertEquals(outboxBefore, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class),
                    "V26 installation cannot synthesize catalog or relation events from existing rows");
        }
    }

    private static Flyway migration(String url, String target) {
        var config = Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).validateOnMigrate(true).cleanDisabled(true);
        if (target != null) config.target(target);
        return config.load();
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }

    private static String row(JdbcTemplate jdbc, String table, long id) {
        return jdbc.queryForObject("SELECT to_jsonb(t)::text FROM public." + table + " t WHERE id=?", String.class, id);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pg_tables WHERE schemaname='eatwhat_sync' AND tablename=?)
                """, Boolean.class, table);
    }

    private static List<String> catalogObjects(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT object_name FROM (
                  SELECT 'relation:'::text||c.relkind::text||':'||c.relname::text AS object_name
                  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                  WHERE n.nspname='eatwhat_sync' AND c.relname LIKE 'catalog\\_%' ESCAPE '\\'
                  UNION ALL
                  SELECT 'function:'::text||p.proname::text
                  FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                  WHERE n.nspname='eatwhat_sync' AND p.proname LIKE 'catalog\\_%' ESCAPE '\\'
                  UNION ALL
                  SELECT 'trigger:'::text||t.tgname::text
                  FROM pg_trigger t
                  WHERE NOT t.tgisinternal AND t.tgname LIKE 'catalog\\_%' ESCAPE '\\'
                ) objects ORDER BY object_name
                """, String.class);
    }

    private static List<Map<String, Object>> checksums(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank");
    }

    private static List<Map<String, Object>> checksumsExcludingV26(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success AND version<>'26' ORDER BY installed_rank");
    }
}
