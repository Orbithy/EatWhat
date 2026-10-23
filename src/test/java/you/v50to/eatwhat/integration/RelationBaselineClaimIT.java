package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.identity.IdentityManifestService;
import you.v50to.eatwhat.sync.relation.RelationBaselineClaimService;
import you.v50to.eatwhat.sync.relation.RelationKind;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static you.v50to.eatwhat.identity.IdentityManifestService.Baseline;
import static you.v50to.eatwhat.identity.IdentityManifestService.MANIFEST_VERSION;

/** Owner-only NULL-origin claims are tested against a synthetic, V18-frozen historical snapshot. */
class RelationBaselineClaimIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("relation-baseline-claim");

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void aSharedV18IdentityCanBeClaimedOnceOnlyForItsExactNaturalRelation() throws Exception {
        String database = snapshotDatabase();
        UUID baselineId = UUID.randomUUID();
        UUID relationGid;

        try (Connection owner = connect(database)) {
            var claims = new RelationBaselineClaimService(owner);
            SQLException notBound = assertThrows(SQLException.class, () -> claims.claim(
                    RelationKind.food_like, 123, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), null, "relation-campus"));
            assertTrue(notBound.getMessage().contains("LEGACY_BASELINE_NOT_ACTIVATED"));

            var manifest = new IdentityManifestService(owner, SiteProperties.Mode.LEGACY);
            manifest.beginAuthoritative(new Baseline(baselineId, MANIFEST_VERSION));
            for (EntityType type : EntityType.independentTypes()) {
                while (!manifest.progress(type).completed()) {
                    manifest.backfillBatch(type, 100);
                }
            }

            relationGid = UUID.fromString(query(owner, """
                    SELECT global_id::text FROM eatwhat_identity.entity_identity
                    WHERE entity_type='likes' AND numeric_id=123
                    """));
            UUID actorGid = userGid(owner, 1);
            UUID targetGid = foodGid(owner, 10);
            UUID otherTargetGid = foodGid(owner, 11);
            UUID ownerGid = userGid(owner, 5);
            assertNull(queryNullable(owner, """
                    SELECT origin_site FROM eatwhat_identity.entity_identity
                    WHERE entity_type='likes' AND numeric_id=123
                    """));

            var claim = new RelationBaselineClaimService.Claim(RelationKind.food_like, 123, relationGid,
                    actorGid, targetGid, ownerGid, null, "relation-campus");
            claims.claim(claim);
            claims.claim(claim);
            assertEquals(1, scalar(owner, """
                    SELECT count(*) FROM eatwhat_sync.relation_baseline_claim
                    WHERE entity_type='likes' AND numeric_id=123
                    """));
            assertEquals(1, scalar(owner, """
                    SELECT count(*) FROM eatwhat_sync.relation_projection
                    WHERE entity_type='likes' AND numeric_id=123 AND global_id='%s'::uuid
                    """.formatted(relationGid)));
            assertEquals(1, scalar(owner, """
                    SELECT count(*) FROM eatwhat_sync.relation_alias
                    WHERE entity_type='likes' AND numeric_id=123 AND global_id='%s'::uuid
                    """.formatted(relationGid)));
            assertNull(queryNullable(owner, """
                    SELECT origin_site FROM eatwhat_identity.entity_identity
                    WHERE entity_type='likes' AND numeric_id=123
                    """), "The claim records source evidence without rewriting the shared identity manifest");

            SQLException wrongNaturalKey = assertThrows(SQLException.class, () -> claims.claim(
                    RelationKind.food_like, 123, relationGid, actorGid, otherTargetGid,
                    ownerGid, null, "relation-campus"));
            assertEquals("23505", wrongNaturalKey.getSQLState());
            SQLException wrongOrigin = assertThrows(SQLException.class, () -> claims.claim(
                    RelationKind.food_like, 123, relationGid, actorGid, targetGid,
                    ownerGid, null, "relation-other-site"));
            assertEquals("23505", wrongOrigin.getSQLState());
            SQLException wrongRelationGid = assertThrows(SQLException.class, () -> claims.claim(
                    RelationKind.food_like, 123, UUID.randomUUID(), actorGid, targetGid,
                    ownerGid, null, "relation-campus"));
            assertEquals("23505", wrongRelationGid.getSQLState());

            SQLException immutable = assertThrows(SQLException.class, () -> execute(owner,
                    "UPDATE eatwhat_sync.relation_baseline_claim SET declared_origin='forged'"));
            assertEquals("23514", immutable.getSQLState());
            immutable = assertThrows(SQLException.class, () -> execute(owner,
                    "DELETE FROM eatwhat_sync.relation_baseline_claim"));
            assertEquals("23514", immutable.getSQLState());
        }

        assertEquals("<null>", query(database, """
                SELECT coalesce(origin_site,'<null>') FROM eatwhat_identity.entity_identity
                WHERE entity_type='likes' AND numeric_id=123
                """));
        assertEquals(relationGid.toString(), query(database, """
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type='likes' AND numeric_id=123
                """));
    }

    @Test
    void runtimeRoleCannotCreateClaimsOrExecuteTheOperatorFunction() throws Exception {
        String database = snapshotDatabase();
        String runtimeRole = "relation_claim_runtime_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection owner = connect(database)) {
            execute(owner, "CREATE ROLE " + runtimeRole + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
            execute(owner, "GRANT USAGE ON SCHEMA eatwhat_sync TO " + runtimeRole);
            execute(owner, "GRANT SELECT ON eatwhat_sync.relation_baseline_claim TO " + runtimeRole);
            try (var statement = owner.createStatement()) {
                statement.execute("SET ROLE " + runtimeRole);
                SQLException denied = assertThrows(SQLException.class, () -> statement.execute("""
                        SELECT eatwhat_sync.claim_relation_baseline(
                          'food_like',123,gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),
                          NULL::uuid,'relation-campus')
                        """));
                assertEquals("42501", denied.getSQLState());
                denied = assertThrows(SQLException.class,
                        () -> statement.execute("DELETE FROM eatwhat_sync.relation_baseline_claim"));
                assertEquals("42501", denied.getSQLState());
            }
        }
    }

    private String snapshotDatabase() throws Exception {
        String database = "relation_claim_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection bootstrap = connect(IsolatedContainers.DATABASE);
             var statement = bootstrap.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " TEMPLATE template0");
        }
        String url = url(database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").target("15").baselineOnMigrate(false).cleanDisabled(true)
                .load().migrate();
        try (Connection connection = connect(database)) {
            execute(connection, """
                    INSERT INTO users(id,nick_name,password_hash) VALUES
                      (1,'claim-actor','synthetic'), (5,'claim-owner','synthetic')
                    """);
            execute(connection, """
                    INSERT INTO restaurant(id,name,location,gcj_lng,gcj_lat,account_id)
                    VALUES (1,'claim restaurant',ST_SetSRID(ST_Point(117,36),4326)::geography,117,36,5)
                    """);
            execute(connection, """
                    INSERT INTO foods(id,account_id,restaurant_id,name) VALUES
                      (10,5,1,'claim target'), (11,5,1,'other target')
                    """);
            execute(connection, """
                    INSERT INTO likes(id,account_id,target_type,target_id) VALUES(123,1,'food',10)
                    """);
        }
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true)
                .load().migrate();
        return database;
    }

    private static UUID userGid(Connection connection, long id) throws SQLException {
        return UUID.fromString(query(connection, """
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type='users' AND numeric_id=%d
                """.formatted(id)));
    }

    private static UUID foodGid(Connection connection, long id) throws SQLException {
        return UUID.fromString(query(connection, """
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type='foods' AND numeric_id=%d
                """.formatted(id)));
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), IsolatedContainers.USER, IsolatedContainers.PASSWORD);
    }

    private static String url(String database) {
        return IsolatedContainers.jdbcUrl(POSTGRES, database);
    }

    private static String query(String database, String sql) throws SQLException {
        try (Connection connection = connect(database)) {
            return query(connection, sql);
        }
    }

    private static String query(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static String queryNullable(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
