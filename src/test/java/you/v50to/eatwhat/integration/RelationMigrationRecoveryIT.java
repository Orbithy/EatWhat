package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import you.v50to.eatwhat.sync.relation.RelationEvents;
import you.v50to.eatwhat.sync.relation.RelationKind;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RelationMigrationRecoveryIT {
    @Test void v23SeedUsesCurrentProjectionAndBlocksAmbiguousMissingLegacyAddWithoutResurrection() {
        try (var postgres = IsolatedContainers.postgres("relation-backfill-v23-seed")) {
            postgres.start();
            String database = "relation_seed_" + UUID.randomUUID().toString().replace("-", "");
            jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE))
                    .execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            Flyway v22 = migration(url, "22");
            assertEquals(21, v22.migrate().migrationsExecuted);
            JdbcTemplate admin = jdbc(url);

            long actor = admin.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('seed-actor','synthetic') RETURNING id", Long.class);
            long owner = admin.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('seed-owner','synthetic') RETURNING id", Long.class);
            long restaurant = admin.queryForObject("""
                    INSERT INTO restaurant(name,location,gcj_lng,gcj_lat,account_id)
                    VALUES('seed-restaurant',ST_SetSRID(ST_Point(117,36),4326),117,36,?) RETURNING id
                    """, Long.class, owner);
            long currentFood = admin.queryForObject("INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'old remove target') RETURNING id",
                    Long.class, owner, restaurant);
            long missingFood = admin.queryForObject("INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'old add target') RETURNING id",
                    Long.class, owner, restaurant);
            long currentLike = admin.queryForObject("INSERT INTO likes(account_id,target_type,target_id) VALUES(?,'food',?) RETURNING id",
                    Long.class, actor, currentFood);
            long missingLike = admin.queryForObject("INSERT INTO likes(account_id,target_type,target_id) VALUES(?,'food',?) RETURNING id",
                    Long.class, actor, missingFood);

            UUID actorGid = gid(admin, "users", actor);
            UUID ownerGid = gid(admin, "users", owner);
            UUID currentTargetGid = gid(admin, "foods", currentFood);
            UUID missingTargetGid = gid(admin, "foods", missingFood);
            UUID currentLikeGid = gid(admin, "likes", currentLike);
            UUID missingLikeGid = gid(admin, "likes", missingLike);
            UUID removeId = UUID.randomUUID();
            UUID oldAddId = UUID.randomUUID();
            seedOperation(admin, removeId, RelationKind.food_like, actorGid, currentTargetGid, ownerGid,
                    currentLike, currentLikeGid, false);
            seedOperation(admin, oldAddId, RelationKind.food_like, actorGid, missingTargetGid, ownerGid,
                    missingLike, missingLikeGid, true);
            admin.update("DELETE FROM likes WHERE id=?", missingLike);

            Flyway v23 = migration(url, "23");
            assertEquals(1, v23.migrate().migrationsExecuted,
                    "The V22 snapshot applies only the appended V23 history-backfill migration");
            UUID currentNatural = UUID.nameUUIDFromBytes(
                    ("food_like:" + actorGid + ":" + currentTargetGid + ":" + ownerGid + ":null")
                            .getBytes(StandardCharsets.UTF_8));
            UUID missingNatural = UUID.nameUUIDFromBytes(
                    ("food_like:" + actorGid + ":" + missingTargetGid + ":" + ownerGid + ":null")
                            .getBytes(StandardCharsets.UTF_8));

            assertTrue(admin.queryForObject("""
                    SELECT desired_present AND blocked_reason IS NULL
                    FROM eatwhat_sync.relation_intent WHERE natural_id=?
                    """, Boolean.class, currentNatural),
                    "The current live public relation wins over an older REMOVE in the V22 fact history");
            assertTrue(admin.queryForObject("""
                    SELECT desired_present AND blocked_reason='LEGACY_HISTORY_AMBIGUOUS'
                    FROM eatwhat_sync.relation_intent WHERE natural_id=?
                    """, Boolean.class, missingNatural),
                    "A missing row plus an old ADD is ambiguous between purge and user deletion");
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM likes WHERE id=?", Integer.class, missingLike),
                    "Migration must not resurrect a missing legacy row");
            assertEquals(currentLikeGid, gid(admin, "likes", currentLike),
                    "A live legacy row keeps its existing numeric ID and GID");
            assertEquals(missingLikeGid, gid(admin, "likes", missingLike),
                    "Physical deletion leaves the historical identity reservation intact");
            assertEquals(2, admin.queryForObject("SELECT count(*) FROM eatwhat_sync.relation_intent", Integer.class));
        }
    }

    @Test void interruptedV22AndV23RollBackAndRetryFromV21WithoutChangingEarlierChecksumsOrData() {
        try (var postgres = IsolatedContainers.postgres("relation-migration")) {
            postgres.start();
            String database = "relation_upgrade_" + UUID.randomUUID().toString().replace("-", "");
            jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE))
                    .execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);
            assertEquals(20, migration(url, "21").migrate().migrationsExecuted);
            admin.update("INSERT INTO public.users(nick_name,password_hash) VALUES('before-relations','synthetic')");
            var users = admin.queryForList("SELECT * FROM public.users ORDER BY id");
            var identities = admin.queryForList("SELECT * FROM eatwhat_identity.entity_identity ORDER BY entity_type,numeric_id");
            var oldChecksums = admin.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank");
            String guard = admin.queryForObject("SELECT pg_get_functiondef('eatwhat_identity.guard_business_identity()'::regprocedure)", String.class);
            admin.execute("""
                    CREATE FUNCTION public.fail_relation_migration_for_test() RETURNS event_trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF EXISTS (SELECT 1 FROM pg_event_trigger_ddl_commands()
                                 WHERE object_identity='eatwhat_sync.relation_operation' AND command_tag='CREATE TABLE') THEN
                        RAISE EXCEPTION 'Synthetic interrupted relation migration' USING ERRCODE='P0001';
                      END IF;
                    END $$
                    """);
            admin.execute("CREATE EVENT TRIGGER fail_relation_migration_for_test ON ddl_command_end EXECUTE FUNCTION public.fail_relation_migration_for_test()");
            Flyway v23 = migration(url, "23");
            assertThrows(FlywayException.class, v23::migrate);
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='eatwhat_sync' AND tablename LIKE 'relation_%'", Integer.class));
            assertEquals(20, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            assertEquals(users, admin.queryForList("SELECT * FROM public.users ORDER BY id"));
            assertEquals(identities, admin.queryForList("SELECT * FROM eatwhat_identity.entity_identity ORDER BY entity_type,numeric_id"));
            assertEquals(guard, admin.queryForObject("SELECT pg_get_functiondef('eatwhat_identity.guard_business_identity()'::regprocedure)", String.class));
            admin.execute("DROP EVENT TRIGGER fail_relation_migration_for_test");
            admin.execute("DROP FUNCTION public.fail_relation_migration_for_test()");
            assertEquals(2, v23.migrate().migrationsExecuted,
                    "Retry from V21 applies V22 and V23 after the interrupted transaction rolled back");
            assertEquals(0, v23.migrate().migrationsExecuted);
            v23.validate();
            assertEquals(8, admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='eatwhat_sync' AND tablename LIKE 'relation_%'", Integer.class));
            assertEquals(oldChecksums, admin.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success AND version NOT IN ('22','23') ORDER BY installed_rank"));
            assertEquals(users, admin.queryForList("SELECT * FROM public.users ORDER BY id"));
            assertEquals(identities, admin.queryForList("SELECT * FROM eatwhat_identity.entity_identity ORDER BY entity_type,numeric_id"));
            assertTrue(admin.queryForObject("SELECT to_regclass('eatwhat_sync.relation_event_number') IS NOT NULL", Boolean.class));
        }
    }

    @Test void interruptedV23BackfillRollsBackAndRetriesWithoutReapplyingV22() {
        try (var postgres = IsolatedContainers.postgres("relation-v23-recovery")) {
            postgres.start();
            String database = "relation_v23_" + UUID.randomUUID().toString().replace("-", "");
            jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE))
                    .execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            assertEquals(21, migration(url, "22").migrate().migrationsExecuted);
            JdbcTemplate admin = jdbc(url);
            assertEquals(5, admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='eatwhat_sync' AND tablename LIKE 'relation_%'", Integer.class));
            var v22Checksums = admin.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank");

            admin.execute("""
                    CREATE FUNCTION public.fail_v23_relation_migration_for_test() RETURNS event_trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF EXISTS (SELECT 1 FROM pg_event_trigger_ddl_commands()
                                 WHERE object_identity='eatwhat_sync.relation_intent' AND command_tag='CREATE TABLE') THEN
                        RAISE EXCEPTION 'Synthetic interrupted V23 relation migration' USING ERRCODE='P0001';
                      END IF;
                    END $$
                    """);
            admin.execute("CREATE EVENT TRIGGER fail_v23_relation_migration_for_test ON ddl_command_end EXECUTE FUNCTION public.fail_v23_relation_migration_for_test()");
            Flyway v23 = migration(url, "23");
            assertThrows(FlywayException.class, v23::migrate);
            assertEquals(21, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            assertEquals(v22Checksums, admin.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank"));
            assertTrue(admin.queryForObject("SELECT to_regclass('eatwhat_sync.relation_operation') IS NOT NULL", Boolean.class));
            assertNull(admin.queryForObject("SELECT to_regclass('eatwhat_sync.relation_intent')", String.class));

            admin.execute("DROP EVENT TRIGGER fail_v23_relation_migration_for_test");
            admin.execute("DROP FUNCTION public.fail_v23_relation_migration_for_test()");
            assertEquals(1, v23.migrate().migrationsExecuted);
            assertEquals(0, v23.migrate().migrationsExecuted);
            v23.validate();
            assertEquals(8, admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='eatwhat_sync' AND tablename LIKE 'relation_%'", Integer.class));
            assertTrue(admin.queryForObject("SELECT to_regclass('eatwhat_sync.relation_intent') IS NOT NULL", Boolean.class));
            assertEquals(v22Checksums, admin.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success AND version<>'23' ORDER BY installed_rank"));
        }
    }

    private Flyway migration(String url, String target) {
        var config = Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true);
        if (target != null) config.target(target);
        return config.load();
    }
    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }

    private static UUID gid(JdbcTemplate jdbc, String type, long id) {
        return UUID.fromString(jdbc.queryForObject("""
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type=? AND numeric_id=?
                """, String.class, type, id));
    }

    private static void seedOperation(JdbcTemplate jdbc, UUID eventId, RelationKind kind, UUID actor, UUID target,
                                      UUID owner, long rowId, UUID rowGid, boolean adding) {
        String origin = "migration-campus";
        var operation = new RelationEvents.Operation(kind, actor, target, owner, null, 1, 1, rowId, rowGid,
                origin, Map.of(), origin, 1, adding, null);
        UUID relationId = operation.relationId();
        jdbc.update("INSERT INTO eatwhat_sync.relation_state(relation_id,vector) VALUES (?,?::jsonb)",
                relationId, "{\"" + origin + "\":1}");
        jdbc.update("""
                INSERT INTO eatwhat_sync.relation_operation(event_id,relation_id,dot_site,dot_counter,context,adding,payload)
                VALUES(?,?,?,1,'{}'::jsonb,?,?::jsonb)
                """, eventId, relationId, origin, adding, RelationEvents.payload(operation));
    }
}
