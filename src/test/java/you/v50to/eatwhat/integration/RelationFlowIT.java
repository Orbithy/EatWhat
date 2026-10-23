package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.JacksonConfig;
import you.v50to.eatwhat.config.SiteProperties;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Existing relationship HTTP APIs -> two real application JVMs -> causal commands -> Outbox -> pinned mTLS push/pull -> Inbox worker
 * -> owner-controlled import functions -> second database. No in-process shortcut sits between the two sites.
 */
class RelationFlowIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("relation-flow");
    private static final GenericContainer<?> CAMPUS_REDIS = IsolatedContainers.redis("relation-campus");
    private static final GenericContainer<?> EXTERNAL_REDIS = IsolatedContainers.redis("relation-external");
    private static final String PASSWORD = "Synthetic-Password-Canary-7x";
    private static final String POLICY = "cross-border-v1";
    @TempDir static Path certificates;
    @TempDir Path workingRoot;
    private static TransportTlsFixture tls;
    private final ObjectMapper json = new JacksonConfig().objectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll static void startDependencies() throws Exception {
        tls = TransportTlsFixture.create(certificates);
        POSTGRES.start(); CAMPUS_REDIS.start(); EXTERNAL_REDIS.start();
    }
    @AfterAll static void stopDependencies() {
        EXTERNAL_REDIS.stop(); CAMPUS_REDIS.stop(); POSTGRES.stop();
    }

    @Test void existingHttpApisSynchronizeEveryRelationAndKeepNotificationIdentity() throws Exception {
        try (var sites = new Sites()) {
            var users = users(sites);
            long[] parents = directory(sites, users.owner);
            int province = sites.campusDb.admin().queryForObject("SELECT min(id) FROM provinces", Integer.class);
            int city = sites.campusDb.admin().queryForObject("SELECT min(id) FROM cities WHERE province_id=?", Integer.class, province);
            long definition = sites.campusDb.runtime().queryForObject(
                    "INSERT INTO activities(code,name,status) VALUES('relation','Relation','ONLINE') RETURNING id", Long.class);
            RelationReplicationIT.mirror(sites.campusDb, sites.externalDb, "activities", definition);
            JsonNode posted = ok(request(sites.campus, "POST", "/activity/relation/posts", Map.of("description", "fixture",
                    "provinceId", province, "cityId", city, "pictureUrl", List.of("activity/relation.png")), users.ownerToken));
            long activity = posted.path("data").path("id").asLong();
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM activity_posts") == 1);
            ok(request(sites.campus, "POST", "/user/follow?userId=" + users.owner, null, users.campusToken));
            assertNotEquals(0, request(sites.campus, "POST", "/user/follow?userId=" + users.owner, null, users.campusToken).path("code").asInt());
            // Existing business API contracts: repeated follow fails, deleting an absent tag fails,
            // and unlike on a nonexistent activity remains a successful no-op.
            assertNotEquals(0, request(sites.campus, "DELETE", "/foods/" + parents[0] + "/tags/" + parents[1], null, users.campusToken).path("code").asInt());
            ok(request(sites.campus, "DELETE", "/activity/posts/9000000001/like", null, users.campusToken));
            ok(request(sites.campus, "POST", "/foods/" + parents[0] + "/like", null, users.campusToken));
            ok(request(sites.campus, "POST", "/foods/" + parents[0] + "/favorite", null, users.campusToken));
            ok(request(sites.campus, "POST", "/foods/" + parents[0] + "/tags/system", Map.of("tagIds", List.of(parents[1])), users.campusToken));
            ok(request(sites.campus, "POST", "/activity/posts/" + activity + "/like", null, users.campusToken));
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.relation_operation") == 5);
            for (var db : List.of(sites.campusDb, sites.externalDb)) {
                for (String table : List.of("follow", "food_favorites", "food_taggings", "likes")) assertEquals(1, count(db, "SELECT count(*) FROM " + table));
                assertEquals(1, count(db, "SELECT count(*) FROM activity_likes WHERE deleted_at IS NULL"));
                assertEquals(2, count(db, "SELECT count(*) FROM notifications"));
                assertEquals(1, count(db, "SELECT likes_count FROM foods WHERE id=" + parents[0]));
            }
            assertEquals(sites.campusDb.runtime().queryForList("SELECT id,created_at,expires_at FROM notifications ORDER BY id"),
                    sites.externalDb.runtime().queryForList("SELECT id,created_at,expires_at FROM notifications ORDER BY id"));
            // Reverse direction uses exactly the old request shapes, including explicit NULL restoration.
            ok(request(sites.external, "POST", "/user/unfollow?userId=" + users.owner, null, users.externalToken));
            ok(request(sites.external, "POST", "/foods/" + parents[0] + "/unlike", null, users.externalToken));
            ok(request(sites.external, "POST", "/foods/" + parents[0] + "/unfavorite", null, users.externalToken));
            ok(request(sites.external, "DELETE", "/foods/" + parents[0] + "/tags/" + parents[1], null, users.externalToken));
            ok(request(sites.external, "DELETE", "/activity/posts/" + activity + "/like", null, users.externalToken));
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.relation_operation") == 10);
            for (var db : List.of(sites.campusDb, sites.externalDb)) {
                for (String table : List.of("follow", "food_favorites", "food_taggings")) assertEquals(0, count(db, "SELECT count(*) FROM " + table));
                assertEquals(0, count(db, "SELECT count(*) FROM activity_likes WHERE deleted_at IS NULL"));
                assertEquals(0, count(db, "SELECT likes_count FROM foods WHERE id=" + parents[0]));
            }
            ok(request(sites.external, "POST", "/foods/" + parents[0] + "/like", null, users.externalToken));
            eventually(() -> count(sites.campusDb, "SELECT likes_count FROM foods WHERE id=" + parents[0]) == 1);
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'") == 0
                    && count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'") == 0);
        }
    }

    @Test void disconnectedOppositeCommandsConvergeAfterTransportRestarts() throws Exception {
        try (var sites = new Sites()) {
            var users = users(sites);
            sites.external.close(); sites.external = null;
            sites.externalConfig.put("app.sync.transport-enabled", "false");
            sites.restartExternal();
            String externalToken = awaitStableSession(sites.external, "relactor");
            ok(request(sites.campus, "POST", "/user/follow?userId=" + users.owner, null, users.campusToken));
            ok(request(sites.external, "POST", "/user/unfollow?userId=" + users.owner, null, externalToken));
            assertEquals(1, count(sites.campusDb, "SELECT count(*) FROM follow"));
            assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM follow"));
            sites.external.close(); sites.external = null;
            sites.externalConfig.put("app.sync.transport-enabled", "true");
            sites.restartExternal();
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM follow") == 0
                    && count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.relation_operation") == 2);
            assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM follow"));
            externalToken = awaitStableSession(sites.external, "relactor");
            ok(request(sites.external, "POST", "/user/follow?userId=" + users.owner, null, externalToken));
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM follow") == 1);
        }
    }

    @Test void committedOutboxSurvivesBothApplicationProcessesBeingKilled() throws Exception {
        try (var sites = new Sites()) {
            var users = users(sites);
            sites.killExternal();
            ok(request(sites.campus, "POST", "/user/follow?userId=" + users.owner, null, users.campusToken));
            assertEquals(1, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'"));
            var child = ProcessHandle.of(sites.campus.pid()).orElseThrow();
            assertTrue(child.destroyForcibly()); child.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            sites.campus.close(); sites.campus = null;
            sites.restartExternal(); sites.restartCampus();
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM follow") == 1);
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'") == 0);
            assertEquals(1, count(sites.externalDb, "SELECT count(*) FROM notifications"));
            assertEquals(1, count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.inbox WHERE status='APPLIED'"));
        }
    }

    @Test void pushWithdrawalGateNeverSendsCommittedOldEpochPayload() throws Exception {
        try (var sites = new Sites()) {
            var users = users(sites);
            sites.campus.close();
            sites.campus = null;
            sites.campusConfig.put("app.sync.transport-enabled", "false");
            sites.restartCampus();
            String campusToken = awaitStableSession(sites.campus, "relactor");

            ok(request(sites.campus, "POST", "/user/follow?userId=" + users.owner, null, campusToken));
            assertEquals(1, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.relation_operation"));
            assertEquals(1, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.delivery d JOIN eatwhat_sync.outbox o USING(event_id) WHERE o.aggregate_type='relation.operation' AND d.status='READY'"));
            ok(request(sites.campus, "POST", "/auth/consent/withdraw", Map.of(), campusToken));

            sites.campus.close();
            sites.campus = null;
            sites.campusConfig.put("app.sync.transport-enabled", "true");
            sites.restartCampus();
            eventually(() -> count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.delivery d JOIN eatwhat_sync.outbox o USING(event_id) WHERE o.aggregate_type='relation.operation' AND d.status='QUARANTINED' AND d.last_error='CONSENT_EPOCH_BLOCKED'") == 1);

            assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.inbox WHERE aggregate_type='relation.operation'"),
                    "The campus PUSH must not send the old-epoch event to the peer");
            assertEquals(1, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                    "Quarantine preserves the immutable operation fact");
            assertEquals(1, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
        }
    }

    @Test void pullWaitRetriesAndWithdrawalGateNeverReturnsOldEpochPayload() throws Exception {
        try (var sites = new Sites()) {
            var users = users(sites);
            sites.campus.close();
            sites.campus = null;
            sites.campusConfig.put("app.sync.transport-enabled", "false");
            sites.restartCampus();

            String externalToken = awaitStableSession(sites.external, "relactor");
            ok(request(sites.external, "POST", "/user/follow?userId=" + users.owner, null, externalToken));
            assertEquals(1, count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.relation_operation"));

            sites.campus.close();
            sites.campus = null;
            sites.external.close();
            sites.external = null;
            parkCurrentAccountUpsert(sites.campusDb, users.actor());
            sites.externalDb.admin().update("UPDATE eatwhat_consent.subject SET valid_until=NULL WHERE user_id=?", users.actor);

            sites.campusConfig.put("app.sync.transport-enabled", "true");
            sites.restartCampus();
            sites.restartExternal();
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.delivery d JOIN eatwhat_sync.outbox o USING(event_id) WHERE o.aggregate_type='relation.operation' AND d.status='READY' AND d.attempts>0 AND d.last_error='CONSENT_NOT_READY'") == 1,
                    () -> relationBackfillDiagnostics(sites, users.actor()));
            assertEquals(0, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.inbox WHERE aggregate_type='relation.operation'"),
                    "A temporary authorization dependency must not return event bytes");
            assertEquals(1, count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.relation_operation"));

            sites.campus.close();
            sites.campus = null;
            sites.campusConfig.put("app.sync.transport-enabled", "false");
            unparkCurrentAccountUpsert(sites.campusDb, users.actor());
            sites.restartCampus();
            externalToken = awaitStableSession(sites.external, "relactor");
            ok(request(sites.external, "POST", "/auth/consent/withdraw", Map.of(), externalToken));

            sites.campus.close();
            sites.campus = null;
            sites.campusConfig.put("app.sync.transport-enabled", "true");
            sites.restartCampus();
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.delivery d JOIN eatwhat_sync.outbox o USING(event_id) WHERE o.aggregate_type='relation.operation' AND d.status='QUARANTINED' AND d.last_error='CONSENT_EPOCH_BLOCKED'") == 1);

            assertEquals(0, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.inbox WHERE aggregate_type='relation.operation'"),
                    "The external PULL response must omit the old-epoch event");
            assertEquals(1, count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.relation_operation"));
            assertEquals(1, count(sites.externalDb, "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
        }
    }

    private void parkCurrentAccountUpsert(EventFoundationTestSupport.Database db, long userId) {
        int parked = db.admin().update("""
                UPDATE eatwhat_consent.job AS job
                SET status='READY',available_at='infinity'::timestamptz,lease_token=NULL,lease_until=NULL
                FROM eatwhat_consent.subject AS subject
                WHERE job.subject_id=subject.subject_id AND job.user_id=? AND job.kind='UPSERT'
                  AND job.epoch=subject.epoch AND job.revision=subject.revision
                """, userId);
        assertEquals(1, parked, "Fence the current campus snapshot lease before changing the peer TTL");
    }

    private void unparkCurrentAccountUpsert(EventFoundationTestSupport.Database db, long userId) {
        int unparked = db.admin().update("""
                UPDATE eatwhat_consent.job AS job
                SET available_at=clock_timestamp()
                FROM eatwhat_consent.subject AS subject
                WHERE job.subject_id=subject.subject_id AND job.user_id=? AND job.kind='UPSERT'
                  AND job.epoch=subject.epoch AND job.revision=subject.revision
                  AND job.status='READY' AND job.available_at='infinity'::timestamptz
                """, userId);
        assertEquals(1, unparked, "Release the fenced snapshot so withdrawal can use the refreshed stable session");
    }

    @Test void twoJvmHttpGrantAndRegrantBackfillHistoricalFollowWithoutNotifications() throws Exception {
        try (var sites = new Sites()) {
            String actorToken = token(register(sites.campus, "bfactor", false));
            token(register(sites.campus, "bfowner", true));
            long actor = id(sites.campusDb, "bfactor");
            long owner = id(sites.campusDb, "bfowner");
            String actorGid = gid(sites.campusDb, "users", actor);
            String ownerGid = gid(sites.campusDb, "users", owner);
            verify(sites, owner);
            awaitLogin(sites.external, "bfowner");

            sites.campusDb.admin().update("""
                    INSERT INTO verifications(account_id,method,verified,verified_email)
                    VALUES(?,'school_email',true,?)
                    """, actor, "backfill-" + actor + "@school.invalid");
            evictRoleCache(actor);
            ok(request(sites.campus, "POST", "/user/follow?userId=" + owner, null, actorToken));
            long originalId = sites.campusDb.runtime().queryForObject(
                    "SELECT id FROM follow WHERE account_id=? AND target_id=?", Long.class, actor, owner);
            String originalGid = gid(sites.campusDb, "follow", originalId);
            List<Map<String, Object>> campusV1BeforeGrant = followV1Facts(sites.campusDb, actorGid, ownerGid);
            List<Map<String, Object>> externalV1BeforeGrant = followV1Facts(sites.externalDb, actorGid, ownerGid);
            assertEquals(0, count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"),
                    "Historical follow remains local until the actor grants consent");
            long noticesBeforeBackfill = count(sites.campusDb, "SELECT count(*) FROM notifications");

            ok(request(sites.campus, "POST", "/auth/consent/grant",
                    Map.of("policyVersion", POLICY), actorToken));
            eventually(() -> count(sites.externalDb,
                    "SELECT count(*) FROM users WHERE id=" + actor) == 1
                    && count(sites.externalDb, "SELECT count(*) FROM follow WHERE account_id=" + actor) == 1,
                    () -> relationBackfillDiagnostics(sites, actor));
            eventually(() -> !followBackfillFacts(sites.campusDb, actorGid, ownerGid).isEmpty()
                    && followBackfillFacts(sites.campusDb, actorGid, ownerGid)
                    .equals(followBackfillFacts(sites.externalDb, actorGid, ownerGid)),
                    () -> relationRegrantDiagnostics(sites, actor, owner));
            assertEquals(campusV1BeforeGrant, followV1Facts(sites.campusDb, actorGid, ownerGid),
                    "State backfill does not fabricate a new V1 operation at the source");
            assertEquals(externalV1BeforeGrant, followV1Facts(sites.externalDb, actorGid, ownerGid),
                    "A pure state bridge does not create a V1 operation at the peer");
            assertEquals(originalId, sites.externalDb.runtime().queryForObject(
                    "SELECT id FROM follow WHERE account_id=? AND target_id=?", Long.class, actor, owner));
            assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM notifications"),
                    "Automatic historical relation backfill does not create notices");

            ok(request(sites.campus, "POST", "/auth/consent/withdraw", Map.of(), actorToken));
            eventually(() -> count(sites.externalDb,
                    "SELECT count(*) FROM follow WHERE account_id=" + actor + " AND target_id=" + owner) == 0);
            String freshActorToken = awaitLogin(sites.campus, "bfactor");
            ok(request(sites.campus, "POST", "/auth/consent/grant",
                    Map.of("policyVersion", POLICY), freshActorToken));
            eventually(() -> regrantBackfillConverged(sites, actor, owner, actorGid, ownerGid),
                    () -> relationRegrantDiagnostics(sites, actor, owner));

            assertCurrentFollowBackfill(sites, actor, owner, actorGid, ownerGid, originalId, originalGid);
            List<Map<String, Object>> factsBeforeRerun = List.copyOf(
                    followBackfillFacts(sites.campusDb, actorGid, ownerGid));
            List<Map<String, Object>> campusV1BeforeRerun = List.copyOf(
                    followV1Facts(sites.campusDb, actorGid, ownerGid));
            List<Map<String, Object>> externalV1BeforeRerun = List.copyOf(
                    followV1Facts(sites.externalDb, actorGid, ownerGid));
            int campusAttempts = requeueCurrentRelationBackfill(sites.campusDb, actor);
            int externalAttempts = requeueCurrentRelationBackfill(sites.externalDb, actor);
            eventually(() -> currentRelationBackfillDone(sites.campusDb, actor, campusAttempts)
                    && currentRelationBackfillDone(sites.externalDb, actor, externalAttempts),
                    () -> relationRegrantDiagnostics(sites, actor, owner));
            assertCurrentFollowBackfill(sites, actor, owner, actorGid, ownerGid, originalId, originalGid);
            assertEquals(factsBeforeRerun, followBackfillFacts(sites.campusDb, actorGid, ownerGid),
                    "Rerunning current-epoch scans does not append logical relation facts");
            assertEquals(campusV1BeforeRerun, followV1Facts(sites.campusDb, actorGid, ownerGid));
            assertEquals(externalV1BeforeRerun, followV1Facts(sites.externalDb, actorGid, ownerGid));

            assertEquals(originalId, sites.campusDb.runtime().queryForObject(
                    "SELECT id FROM follow WHERE account_id=? AND target_id=?", Long.class, actor, owner));
            assertEquals(originalId, sites.externalDb.runtime().queryForObject(
                    "SELECT id FROM follow WHERE account_id=? AND target_id=?", Long.class, actor, owner));
            assertEquals(noticesBeforeBackfill, count(sites.campusDb, "SELECT count(*) FROM notifications"),
                    "Grant and regrant backfill do not create historical follow notices");
            assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM notifications"));
        }
    }

    @Test void twoJvmCatalogHttpDirectoryRecoversAfterOfflineTransportAndConsentRegrant() throws Exception {
        try (var sites = new Sites(false, true, false)) {
            var users = users(sites);
            token(register(sites.campus, "catadmin", true));
            long catalogAdmin = id(sites.campusDb, "catadmin");
            sites.campusDb.admin().update("UPDATE users SET role='admin' WHERE id=?", catalogAdmin);
            evictRoleCache(catalogAdmin);
            String adminToken = awaitStableSession(sites.campus, "catadmin");

            int province = sites.campusDb.admin().queryForObject("SELECT min(id) FROM provinces", Integer.class);
            int city = sites.campusDb.admin().queryForObject(
                    "SELECT min(id) FROM cities WHERE province_id=?", Integer.class, province);
            Map<String, Object> hubBody = new LinkedHashMap<>();
            hubBody.put("name", "PR05E synthetic hub");
            hubBody.put("gcjLng", 117.01);
            hubBody.put("gcjLat", 36.01);
            hubBody.put("boundaryGeoJson", null);
            ok(request(sites.campus, "POST", "/hub/add", hubBody, adminToken));
            long hubId = sites.campusDb.runtime().queryForObject(
                    "SELECT id FROM hub WHERE name='PR05E synthetic hub'", Long.class);
            String hubGid = gid(sites.campusDb, "hub", hubId);

            ok(request(sites.campus, "POST", "/restaurant/add", Map.of(
                    "name", "PR05E synthetic restaurant", "address", "Synthetic campus address",
                    "cityId", city, "gcjLng", 117.01, "gcjLat", 36.01, "hubId", hubId,
                    "POI", "PR05E-HTTP-POI", "pictureUrl", List.of("restaurants/pr05e/http-key")), users.campusToken()));
            long restaurantId = sites.campusDb.runtime().queryForObject(
                    "SELECT id FROM restaurant WHERE poi='PR05E-HTTP-POI'", Long.class);
            String restaurantGid = gid(sites.campusDb, "restaurant", restaurantId);

            Map<String, Object> foodBody = new LinkedHashMap<>();
            foodBody.put("restaurantId", restaurantId);
            foodBody.put("name", "PR05E synthetic food");
            foodBody.put("description", "synthetic");
            foodBody.put("price", null);
            foodBody.put("category", null);
            foodBody.put("pictureUrl", List.of("foods/pr05e/http-key"));
            ok(request(sites.campus, "POST", "/foods/upload", foodBody, users.campusToken()));
            long foodId = sites.campusDb.runtime().queryForObject(
                    "SELECT id FROM foods WHERE restaurant_id=? AND name='PR05E synthetic food'", Long.class, restaurantId);
            String foodGid = gid(sites.campusDb, "foods", foodId);
            ok(request(sites.campus, "POST", "/foods/" + foodId + "/tags/custom",
                    Map.of("names", List.of("PR05E HTTP custom")), users.campusToken()));
            long tagId = sites.campusDb.runtime().queryForObject("""
                    SELECT t.id FROM food_tags t JOIN food_taggings ft ON ft.tag_id=t.id
                    WHERE ft.food_id=? AND t.owner_id=? AND t.name='PR05E HTTP custom'
                    """, Long.class, foodId, users.actor());
            String tagGid = gid(sites.campusDb, "food_tags", tagId);

            assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM hub WHERE id=" + hubId));
            assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM restaurant WHERE id=" + restaurantId));
            assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM foods WHERE id=" + foodId));
            assertEquals(0, count(sites.campusDb,
                    "SELECT count(*) FROM eatwhat_sync.delivery d JOIN eatwhat_sync.outbox o USING(event_id) " +
                            "WHERE o.aggregate_type='catalog.operation' AND d.target_site='relation-external' AND d.status='ACKED'"),
                    "The HTTP writes remain durable while event transport is offline");

            sites.campus.close(); sites.campus = null;
            sites.external.close(); sites.external = null;
            sites.campusConfig.put("app.sync.transport-enabled", "true");
            sites.externalConfig.put("app.sync.transport-enabled", "true");
            sites.restartExternal();
            sites.restartCampus();
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM hub WHERE id=" + hubId) == 1
                    && count(sites.externalDb, "SELECT count(*) FROM restaurant WHERE id=" + restaurantId) == 1
                    && count(sites.externalDb, "SELECT count(*) FROM foods WHERE id=" + foodId) == 1
                    && count(sites.externalDb, "SELECT count(*) FROM food_tags WHERE id=" + tagId) == 1
                    && count(sites.externalDb, "SELECT count(*) FROM food_taggings WHERE food_id=" + foodId) == 1,
                    () -> catalogHttpDiagnostics(sites, hubId, restaurantId, foodId, tagId));
            for (var db : List.of(sites.campusDb, sites.externalDb)) {
                assertEquals(hubGid, gid(db, "hub", hubId));
                assertEquals(restaurantGid, gid(db, "restaurant", restaurantId));
                assertEquals(foodGid, gid(db, "foods", foodId));
                assertEquals(tagGid, gid(db, "food_tags", tagId));
            }

            ok(request(sites.campus, "POST", "/auth/consent/withdraw", Map.of(), users.campusToken()));
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM restaurant WHERE id=" + restaurantId) == 0
                    && count(sites.externalDb, "SELECT count(*) FROM foods WHERE id=" + foodId) == 0
                    && count(sites.externalDb, "SELECT count(*) FROM food_tags WHERE id=" + tagId) == 0);
            String refreshedActorToken = awaitLogin(sites.campus, "relactor");
            ok(request(sites.campus, "POST", "/auth/consent/grant",
                    Map.of("policyVersion", POLICY), refreshedActorToken));
            eventually(() -> count(sites.externalDb, "SELECT count(*) FROM restaurant WHERE id=" + restaurantId) == 1
                    && count(sites.externalDb, "SELECT count(*) FROM foods WHERE id=" + foodId) == 1
                    && count(sites.externalDb, "SELECT count(*) FROM food_tags WHERE id=" + tagId) == 1
                    && count(sites.externalDb, "SELECT count(*) FROM food_taggings WHERE food_id=" + foodId) == 1);
            assertEquals(restaurantGid, gid(sites.externalDb, "restaurant", restaurantId));
            assertEquals(foodGid, gid(sites.externalDb, "foods", foodId));
            assertEquals(tagGid, gid(sites.externalDb, "food_tags", tagId));
        }
    }

    @Test void twoJvmProfileAndPrivacyApisSurviveAccountSnapshotAndConsentRegrant() throws Exception {
        try (var sites = new Sites(true)) {
            var users = users(sites);
            eventually(() -> count(sites.campusDb,
                            "SELECT count(*) FROM eatwhat_sync.profile_domain WHERE subject_gid='" + gid(sites.campusDb, "users", users.actor) + "'") == 1
                    && count(sites.externalDb,
                            "SELECT count(*) FROM eatwhat_sync.profile_domain WHERE subject_gid='" + gid(sites.externalDb, "users", users.actor) + "'") == 1
                    && sites.campusDb.admin().queryForObject("SELECT baseline_id FROM eatwhat_sync.profile_domain WHERE subject_gid=?",
                            UUID.class, UUID.fromString(gid(sites.campusDb, "users", users.actor))).equals(
                            sites.externalDb.admin().queryForObject("SELECT baseline_id FROM eatwhat_sync.profile_domain WHERE subject_gid=?",
                                    UUID.class, UUID.fromString(gid(sites.externalDb, "users", users.actor)))));
            UUID profileSubject = UUID.fromString(gid(sites.campusDb, "users", users.actor));
            assertEquals(1, sites.campusDb.runtime().queryForObject(
                    "SELECT count(*) FROM eatwhat_sync.profile_domain WHERE subject_gid=?", Integer.class, profileSubject));
            assertEquals(1, sites.externalDb.runtime().queryForObject(
                    "SELECT count(*) FROM eatwhat_sync.profile_domain WHERE subject_gid=?", Integer.class, profileSubject));
            assertEquals(sites.campusDb.admin().queryForObject(
                            "SELECT baseline_id FROM eatwhat_sync.profile_domain WHERE subject_gid=?", UUID.class, profileSubject),
                    sites.externalDb.admin().queryForObject(
                            "SELECT baseline_id FROM eatwhat_sync.profile_domain WHERE subject_gid=?", UUID.class, profileSubject),
                    "The campus worker must establish and deliver the common empty-profile baseline before an edit");

            long profileOperationsBeforeEmptyUpdate = count(sites.campusDb,
                    "SELECT count(*) FROM eatwhat_sync.profile_operation "
                            + "WHERE subject_gid='" + profileSubject + "' AND changes<>'{}'::jsonb");
            ok(request(sites.campus, "PUT", "/user/updateInfo", Map.of(), users.campusToken));
            assertEquals(profileOperationsBeforeEmptyUpdate,
                    count(sites.campusDb, "SELECT count(*) FROM eatwhat_sync.profile_operation "
                            + "WHERE subject_gid='" + profileSubject + "' AND changes<>'{}'::jsonb"),
                    "An empty legacy profile update is a no-op while profile sync is enabled");

            ok(request(sites.campus, "POST", "/user/follow?userId=" + users.owner, null, users.campusToken));
            long noticeId = sites.campusDb.runtime().queryForObject(
                    "SELECT id FROM notifications WHERE account_id=? ORDER BY id", Long.class, users.owner);
            eventually(() -> count(sites.externalDb,
                    "SELECT count(*) FROM notifications WHERE account_id=" + users.owner + " AND id=" + noticeId) == 1);
            long originalCreatedAt = count(sites.campusDb,
                    "SELECT (extract(epoch FROM created_at)*1000)::bigint FROM notifications WHERE id=" + noticeId);
            long originalExpiresAt = count(sites.campusDb,
                    "SELECT (extract(epoch FROM expires_at)*1000)::bigint FROM notifications WHERE id=" + noticeId);
            String externalOwnerToken = awaitStableSession(sites.external, "relowner");
            ok(request(sites.external, "POST", "/notification/read/" + noticeId, null, externalOwnerToken));
            eventually(() -> count(sites.campusDb,
                    "SELECT count(*) FROM notifications WHERE id=" + noticeId + " AND read_at IS NOT NULL") == 1);
            assertEquals(originalCreatedAt, count(sites.campusDb,
                    "SELECT (extract(epoch FROM created_at)*1000)::bigint FROM notifications WHERE id=" + noticeId));
            assertEquals(originalExpiresAt, count(sites.campusDb,
                    "SELECT (extract(epoch FROM expires_at)*1000)::bigint FROM notifications WHERE id=" + noticeId));

            String campusSignature = "campus baseline";
            ok(request(sites.campus, "PUT", "/user/updateInfo", Map.of("signature", campusSignature), users.campusToken));
            eventually(() -> campusSignature.equals(value(sites.externalDb,
                    "SELECT signature FROM user_info WHERE id=" + users.actor)));

            String multilineSignature = "first line\nsecond\tline";
            ok(request(sites.external, "PUT", "/user/updateInfo",
                    Map.of("signature", multilineSignature, "gender", "other"), users.externalToken));
            ok(request(sites.external, "POST", "/user/changePrivacy", Map.of("following", false), users.externalToken));
            eventually(() -> multilineSignature.equals(value(sites.campusDb,
                            "SELECT signature FROM user_info WHERE id=" + users.actor))
                    && "other".equals(value(sites.campusDb,
                            "SELECT gender FROM user_info WHERE id=" + users.actor))
                    && Boolean.FALSE.equals(sites.campusDb.admin().queryForObject(
                            "SELECT following FROM privacy WHERE account_id=?", Boolean.class, users.actor)));

            ok(request(sites.campus, "POST", "/auth/consent/withdraw", Map.of(), users.campusToken));
            eventually(() -> count(sites.externalDb,
                    "SELECT count(*) FROM eatwhat_consent.subject WHERE user_id=" + users.actor
                            + " AND status='WITHDRAWN'") == 1);
            String freshToken = awaitLogin(sites.campus, "relactor");
            ok(request(sites.campus, "POST", "/auth/consent/grant",
                    Map.of("policyVersion", POLICY), freshToken));
            eventually(() -> count(sites.externalDb,
                            "SELECT count(*) FROM eatwhat_consent.subject WHERE user_id=" + users.actor
                                    + " AND status='GRANTED' AND identity_ready") == 1
                    && multilineSignature.equals(value(sites.externalDb,
                            "SELECT signature FROM user_info WHERE id=" + users.actor))
                    && Boolean.FALSE.equals(sites.externalDb.admin().queryForObject(
                            "SELECT following FROM privacy WHERE account_id=?", Boolean.class, users.actor)));

            // Force the ordinary campus account TTL refresh job due now. Its valid snapshot must preserve
            // D-owned profile/privacy fields while continuing to refresh account-owned identity fields.
            sites.campusDb.admin().update("UPDATE users SET nick_name='campusnew',role='admin' WHERE id=?", users.actor);
            sites.campusDb.admin().update("""
                    UPDATE eatwhat_consent.job SET available_at=clock_timestamp()
                    WHERE job_id=(SELECT job_id FROM eatwhat_consent.job
                      WHERE user_id=? AND kind='UPSERT' AND status='READY'
                      ORDER BY epoch DESC,revision DESC,job_id DESC LIMIT 1)
                    """, users.actor);
            eventually(() -> "campusnew".equals(value(sites.externalDb,
                            "SELECT nick_name FROM users WHERE id=" + users.actor))
                    && "admin".equals(value(sites.externalDb,
                            "SELECT role FROM users WHERE id=" + users.actor))
                    && multilineSignature.equals(value(sites.externalDb,
                            "SELECT signature FROM user_info WHERE id=" + users.actor))
                    && "other".equals(value(sites.externalDb,
                            "SELECT gender FROM user_info WHERE id=" + users.actor))
                    && Boolean.FALSE.equals(sites.externalDb.admin().queryForObject(
                            "SELECT following FROM privacy WHERE account_id=?", Boolean.class, users.actor)));

            // Account security fields remain under the campus snapshot authority even with D enabled.
            sites.campusDb.admin().update("UPDATE users SET banned=true,ban_reason='synthetic PR05D check',banned_at=now() WHERE id=?",
                    users.actor);
            sites.campusDb.admin().update("""
                    UPDATE eatwhat_consent.job SET available_at=clock_timestamp()
                    WHERE job_id=(SELECT job_id FROM eatwhat_consent.job
                      WHERE user_id=? AND kind='UPSERT' AND status='READY'
                      ORDER BY epoch DESC,revision DESC,job_id DESC LIMIT 1)
                    """, users.actor);
            eventually(() -> Boolean.TRUE.equals(sites.externalDb.admin().queryForObject(
                            "SELECT banned FROM users WHERE id=?", Boolean.class, users.actor))
                    && "synthetic PR05D check".equals(value(sites.externalDb,
                            "SELECT ban_reason FROM users WHERE id=" + users.actor)));
        }
    }

    private String value(EventFoundationTestSupport.Database db, String sql) {
        return db.admin().query(sql, (row, index) -> row.getString(1)).stream().findFirst().orElse(null);
    }

    private record Users(long actor, long owner, String campusToken, String externalToken, String ownerToken) { }
    private Users users(Sites sites) throws Exception {
        String actorToken = token(register(sites.campus, "relactor", true));
        String ownerToken = token(register(sites.campus, "relowner", true));
        long actor = id(sites.campusDb, "relactor"), owner = id(sites.campusDb, "relowner");
        awaitLogin(sites.external, "relactor"); awaitLogin(sites.external, "relowner");
        verify(sites, actor); verify(sites, owner);
        return new Users(actor, owner, actorToken, awaitStableSession(sites.external, "relactor"), ownerToken);
    }
    private JsonNode ok(JsonNode response) { assertEquals(0, response.path("code").asInt(), response.toString()); return response; }
    private long[] directory(Sites sites, long owner) throws Exception {
        var jdbc = sites.campusDb.runtime();
        long restaurant = jdbc.queryForObject("INSERT INTO restaurant(name,location,gcj_lng,gcj_lat,account_id) VALUES('fixture',ST_SetSRID(ST_Point(117,36),4326),117,36,?) RETURNING id", Long.class, owner);
        long food = jdbc.queryForObject("INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'fixture food') RETURNING id", Long.class, owner, restaurant);
        long tag = jdbc.queryForObject("INSERT INTO food_tags(name,normalized_name,tag_type) VALUES('fixture tag','fixture tag','system') RETURNING id", Long.class);
        RelationReplicationIT.mirror(sites.campusDb, sites.externalDb, "restaurant", restaurant);
        RelationReplicationIT.mirror(sites.campusDb, sites.externalDb, "foods", food);
        RelationReplicationIT.mirror(sites.campusDb, sites.externalDb, "food_tags", tag);
        return new long[]{food, tag};
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private void verify(Sites sites, long author) throws Exception {
        sites.campusDb.admin().update("INSERT INTO verifications(account_id,method,verified,verified_email) VALUES(?,'school_email',true,?)", author, "x" + author + "@school.invalid");
        eventually(() -> count(sites.externalDb, "SELECT count(*) FROM verifications WHERE account_id=" + author) == 1);
        evictRoleCache(author);
    }

    /** Registration already evaluated the role once; a cached "not verified" would otherwise last two minutes. */
    private void evictRoleCache(long author) throws Exception {
        for (var redis : List.of(CAMPUS_REDIS, EXTERNAL_REDIS)) {
            var result = redis.execInContainer("redis-cli", "DEL", "auth:" + author, "role:" + author);
            assertEquals(0, result.getExitCode(), result.getStderr());
        }
    }

    private Map<String, Object> registration(String name, boolean grant) {
        return Map.of("username", name, "password", PASSWORD, "device", "WEB", "crossBorderConsent", grant, "consentPolicyVersion", POLICY);
    }
    private JsonNode register(DualModeApplicationProcess process, String name, boolean grant) throws Exception {
        return request(process, "POST", "/auth/register", registration(name, grant), null);
    }
    private JsonNode login(DualModeApplicationProcess process, String name) throws Exception {
        return request(process, "POST", "/auth/login", Map.of("username", name, "password", PASSWORD, "device", "WEB"), null);
    }
    private String awaitLogin(DualModeApplicationProcess process, String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        JsonNode response;
        do {
            response = login(process, name);
            if (response.path("code").asInt() == 0) return token(response);
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Account did not become ready: " + response);
    }
    /** A session that survives a pause long enough for any pending security-effect revocation to have run. */
    private String awaitStableSession(DualModeApplicationProcess process, String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            String candidate = awaitLogin(process, name);
            Thread.sleep(1_500);
            if (request(process, "GET", "/user/info", null, candidate).path("code").asInt() == 0) return candidate;
        }
        throw new AssertionError("No stable session for " + name);
    }
    private String token(JsonNode response) {
        assertEquals(0, response.path("code").asInt(), response.toString());
        String token = response.path("data").path("tokenValue").asText();
        assertFalse(token.isEmpty());
        return token;
    }
    private JsonNode request(DualModeApplicationProcess process, String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + process.port() + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("eatwhat-test-token", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) builder.header("Content-Type", "application/json");
        return json.readTree(http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
    }
    private long id(EventFoundationTestSupport.Database db, String name) {
        return db.runtime().queryForObject("SELECT id FROM users WHERE nick_name=?", Long.class, name);
    }
    private String gid(EventFoundationTestSupport.Database db, String table, long id) {
        return db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type=? AND numeric_id=?", String.class, table, id);
    }
    private long count(EventFoundationTestSupport.Database db, String sql) {
        return db.admin().queryForObject(sql, Long.class);
    }
    private void eventually(BooleanSupplier condition) throws Exception {
        eventually(condition, () -> "");
    }
    private void eventually(BooleanSupplier condition, Supplier<String> failureDetails) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < end) { if (condition.getAsBoolean()) return; Thread.sleep(150); }
        assertTrue(condition.getAsBoolean(), failureDetails.get());
    }
    private String catalogHttpDiagnostics(Sites sites, long hubId, long restaurantId, long foodId, long tagId) {
        StringBuilder details = new StringBuilder("catalog HTTP sync diagnostics");
        for (var entry : List.of(Map.entry("campus", sites.campusDb), Map.entry("external", sites.externalDb))) {
            var db = entry.getValue();
            String publicRows = db.admin().queryForObject("""
                    SELECT jsonb_build_object(
                      'hub', (SELECT count(*) FROM public.hub WHERE id=?),
                      'restaurant', (SELECT count(*) FROM public.restaurant WHERE id=?),
                      'food', (SELECT count(*) FROM public.foods WHERE id=?),
                      'tag', (SELECT count(*) FROM public.food_tags WHERE id=?),
                      'tagging', (SELECT count(*) FROM public.food_taggings WHERE food_id=?))::text
                    """, String.class, hubId, restaurantId, foodId, tagId, foodId);
            String objects = db.admin().queryForObject("""
                    SELECT coalesce(jsonb_agg(jsonb_build_object(
                      'kind',kind,'gid',object_gid,'id',numeric_id,'revision',revision,
                      'tombstone',tombstone,'rejected',rejected,'parent',parent_gid)
                      ORDER BY kind,numeric_id)::text,'[]')
                    FROM eatwhat_sync.catalog_object WHERE numeric_id IN (?,?,?,?)
                    """, String.class, hubId, restaurantId, foodId, tagId);
            String deliveries = db.admin().queryForObject("""
                    SELECT coalesce(jsonb_agg(jsonb_build_object(
                      'id',o.event_id,'type',o.envelope->>'eventType','kind',o.envelope->'payloadJson'->>'kind',
                      'op',o.envelope->'payloadJson'->>'operation','object',o.envelope->'payloadJson'->>'objectGid',
                      'revision',o.envelope->'payloadJson'->>'revision','target',d.target_site,
                      'status',d.status,'receipt',d.receipt_status,'error',d.last_error)
                      ORDER BY o.local_order),'[]'::jsonb)::text
                    FROM eatwhat_sync.outbox o LEFT JOIN eatwhat_sync.delivery d USING(event_id)
                    WHERE o.aggregate_type='catalog.operation'
                    """, String.class);
            String inbox = db.admin().queryForObject("""
                    SELECT coalesce(jsonb_agg(jsonb_build_object(
                      'id',event_id,'origin',origin_site,'type',envelope->>'eventType',
                      'status',status,'attempts',attempts,'error',last_error)
                      ORDER BY received_at),'[]'::jsonb)::text
                    FROM eatwhat_sync.inbox WHERE aggregate_type='catalog.operation'
                    """, String.class);
            details.append("\n").append(entry.getKey()).append(" public=").append(publicRows)
                    .append(" objects=").append(objects).append(" outbox=").append(deliveries)
                    .append(" inbox=").append(inbox);
        }
        if (sites.campus != null) {
            details.append("\nCampus child application.log tail:\n").append(sites.campus.recentLog());
        }
        if (sites.external != null) {
            details.append("\nExternal child application.log tail:\n").append(sites.external.recentLog());
        }
        return details.toString();
    }
    private String relationBackfillDiagnostics(Sites sites, long actor) {
        StringBuilder details = new StringBuilder("relation backfill diagnostic actor=").append(actor);
        diagnosticSite(details, "campus", sites.campusDb, actor);
        diagnosticSite(details, "external", sites.externalDb, actor);
        return details.toString();
    }
    private String relationRegrantDiagnostics(Sites sites, long actor, long owner) {
        StringBuilder details = new StringBuilder(relationBackfillDiagnostics(sites, actor));
        diagnosticRegrantSite(details, "campus", sites.campusDb, actor, owner);
        diagnosticRegrantSite(details, "external", sites.externalDb, actor, owner);
        return details.toString();
    }
    private void diagnosticRegrantSite(StringBuilder details, String label,
                                       EventFoundationTestSupport.Database db, long actor, long owner) {
        details.append('\n').append(label).append(" regrantFollow=")
                .append(db.admin().queryForList("SELECT id,account_id,target_id FROM follow WHERE account_id=? AND target_id=?",
                        actor, owner));
        details.append(" followOperations=").append(db.admin().queryForList("""
                SELECT event_id,dot_site,dot_counter,context,adding,
                       payload->>'actorEpoch' AS actor_epoch,payload->>'ownerEpoch' AS owner_epoch,
                       payload->>'kind' AS kind,payload->>'actor' AS actor_gid,
                       payload->>'target' AS target_gid,payload->>'owner' AS owner_gid
                FROM eatwhat_sync.relation_operation
                WHERE payload->>'kind'='follow'
                  AND payload->>'actor'=(SELECT global_id::text FROM eatwhat_identity.entity_identity
                                         WHERE entity_type='users' AND numeric_id=?)
                ORDER BY dot_site,dot_counter,event_id
                """, actor));
        details.append(" causalFacts=").append(db.admin().queryForList("""
                SELECT f.event_id::text,f.stream_key,f.dot_counter,f.context::text,f.adding,f.legacy,f.payload::text
                FROM eatwhat_sync.relation_causal_fact f
                JOIN eatwhat_sync.relation_causal_state s USING(natural_id)
                WHERE s.kind='follow' AND s.actor_gid=(SELECT global_id FROM eatwhat_identity.entity_identity
                  WHERE entity_type='users' AND numeric_id=?)
                  AND s.owner_gid=(SELECT global_id FROM eatwhat_identity.entity_identity
                  WHERE entity_type='users' AND numeric_id=?)
                ORDER BY f.event_id
                """, actor, owner));
    }
    private void assertCurrentFollowBackfill(Sites sites, long actor, long owner,
                                             String actorGid, String ownerGid,
                                             long originalId, String originalGid) {
        String externalActorGid = gid(sites.externalDb, "users", actor);
        String externalOwnerGid = gid(sites.externalDb, "users", owner);
        assertEquals(actorGid, externalActorGid);
        assertEquals(ownerGid, externalOwnerGid);
        String externalFollowGid = gid(sites.externalDb, "follow", originalId);
        assertEquals(originalGid, externalFollowGid);

        List<Map<String, Object>> campusFacts = followBackfillFacts(sites.campusDb, actorGid, ownerGid);
        List<Map<String, Object>> externalFacts = followBackfillFacts(sites.externalDb, actorGid, ownerGid);
        assertFalse(campusFacts.isEmpty(), "The initial local follow action or seeded baseline remains represented");
        assertEquals(campusFacts, externalFacts, "Both sites converge on the same immutable causal fact set");
        List<Map<String, Object>> campusState = followBackfillState(sites.campusDb, actorGid, ownerGid);
        List<Map<String, Object>> externalState = followBackfillState(sites.externalDb, actorGid, ownerGid);
        assertEquals(campusState, externalState, "The pure state bridge converges on the same bounded frontier");
        assertEquals(1, campusState.size());
        assertEquals(Long.toString(originalId), campusState.getFirst().get("row_id"));
        assertEquals(originalGid, campusState.getFirst().get("row_gid"));
        assertNull(campusState.getFirst().get("blocked_reason"));
        assertEquals(1, count(sites.campusDb,
                "SELECT count(*) FROM follow WHERE account_id=" + actor + " AND target_id=" + owner));
        assertEquals(1, count(sites.externalDb,
                "SELECT count(*) FROM follow WHERE account_id=" + actor + " AND target_id=" + owner));
        assertEquals(0, count(sites.externalDb, "SELECT count(*) FROM notifications"));
    }
    private boolean regrantBackfillConverged(Sites sites, long actor, long owner,
                                              String actorGid, String ownerGid) {
        if (count(sites.campusDb, "SELECT count(*) FROM follow WHERE account_id=" + actor + " AND target_id=" + owner) != 1
                || count(sites.externalDb, "SELECT count(*) FROM follow WHERE account_id=" + actor + " AND target_id=" + owner) != 1) {
            return false;
        }
        if (!currentRelationBackfillStatusDone(sites.campusDb, actor)
                || !currentRelationBackfillStatusDone(sites.externalDb, actor)
                || !currentFollowIntentPublished(sites.campusDb, actorGid, ownerGid)
                || !currentFollowIntentPublished(sites.externalDb, actorGid, ownerGid)
                || !relationOperationDeliveriesAcked(sites.campusDb)
                || !relationOperationDeliveriesAcked(sites.externalDb)) {
            return false;
        }
        return followBackfillFacts(sites.campusDb, actorGid, ownerGid)
                .equals(followBackfillFacts(sites.externalDb, actorGid, ownerGid))
                && followBackfillState(sites.campusDb, actorGid, ownerGid)
                .equals(followBackfillState(sites.externalDb, actorGid, ownerGid));
    }
    private boolean currentFollowIntentPublished(EventFoundationTestSupport.Database db,
                                                 String actorGid, String ownerGid) {
        return Boolean.TRUE.equals(db.admin().queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM eatwhat_sync.relation_intent i
                  WHERE i.kind='follow' AND i.desired_present
                    AND i.actor_gid=?::uuid AND i.owner_gid=?::uuid
                    AND i.published_revision=i.intent_revision
                    AND i.published_actor_epoch=(SELECT epoch FROM eatwhat_consent.subject WHERE subject_id=i.actor_gid)
                    AND i.published_owner_epoch=(SELECT epoch FROM eatwhat_consent.subject WHERE subject_id=i.owner_gid)
                )
                """, Boolean.class, actorGid, ownerGid));
    }
    private boolean relationOperationDeliveriesAcked(EventFoundationTestSupport.Database db) {
        return count(db, """
                SELECT count(*) FROM eatwhat_sync.outbox o
                LEFT JOIN eatwhat_sync.delivery d USING(event_id)
                WHERE o.aggregate_type='relation.operation'
                  AND (d.event_id IS NULL OR d.status<>'ACKED')
                """) == 0;
    }
    private List<Map<String, Object>> followBackfillFacts(EventFoundationTestSupport.Database db,
                                                          String actorGid, String ownerGid) {
        return db.admin().queryForList("""
                SELECT f.event_id::text AS event_id,f.natural_id::text AS natural_id,
                       f.stream_key,f.dot_counter::text AS dot_counter,f.context::text AS context,
                       f.adding::text AS adding,f.payload::text AS payload,f.legacy::text AS legacy
                FROM eatwhat_sync.relation_causal_fact f
                JOIN eatwhat_sync.relation_causal_state s USING(natural_id)
                WHERE s.kind='follow' AND s.actor_gid=?::uuid AND s.owner_gid=?::uuid
                ORDER BY f.event_id
                """, actorGid, ownerGid);
    }
    private List<Map<String, Object>> followBackfillState(EventFoundationTestSupport.Database db,
                                                          String actorGid, String ownerGid) {
        return db.admin().queryForList("""
                SELECT natural_id::text AS natural_id,row_id::text AS row_id,row_gid::text AS row_gid,
                       row_origin,actor_epoch::text AS actor_epoch,owner_epoch::text AS owner_epoch,
                       seen::text AS seen,add_witnesses::text AS add_witnesses,
                       remove_frontier::text AS remove_frontier,blocked_reason
                FROM eatwhat_sync.relation_causal_state
                WHERE kind='follow' AND actor_gid=?::uuid AND owner_gid=?::uuid
                """, actorGid, ownerGid);
    }
    private List<Map<String, Object>> followV1Facts(EventFoundationTestSupport.Database db,
                                                    String actorGid, String ownerGid) {
        return db.admin().queryForList("""
                SELECT event_id::text AS event_id,relation_id::text AS relation_id,
                       dot_site,dot_counter::text AS dot_counter,context::text AS context,
                       adding::text AS adding,payload::text AS payload
                FROM eatwhat_sync.relation_operation
                WHERE payload->>'kind'='follow' AND payload->>'actor'=? AND payload->>'owner'=?
                ORDER BY event_id
                """, actorGid, ownerGid);
    }
    private int requeueCurrentRelationBackfill(EventFoundationTestSupport.Database db, long actor) {
        Integer attempts = db.admin().queryForObject("""
                SELECT attempts FROM eatwhat_sync.relation_backfill_job
                WHERE user_id=? AND epoch=(SELECT epoch FROM eatwhat_consent.subject WHERE user_id=?)
                """, Integer.class, actor, actor);
        int updated = db.admin().update("""
                UPDATE eatwhat_sync.relation_backfill_job
                SET status='READY',phase=0,row_cursor=0,history_cursor=NULL,
                    available_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,last_error=NULL,
                    updated_at=clock_timestamp()
                WHERE user_id=? AND epoch=(SELECT epoch FROM eatwhat_consent.subject WHERE user_id=?)
                  AND status='DONE'
                """, actor, actor);
        assertEquals(1, updated, "Requeue the completed current-epoch actor scan on each site");
        return attempts;
    }
    private boolean currentRelationBackfillDone(EventFoundationTestSupport.Database db, long actor, int previousAttempts) {
        return currentRelationBackfillStatusDone(db, actor)
                && Boolean.TRUE.equals(db.admin().queryForObject("""
                        SELECT attempts>? FROM eatwhat_sync.relation_backfill_job
                        WHERE user_id=? AND epoch=(SELECT epoch FROM eatwhat_consent.subject WHERE user_id=?)
                        """, Boolean.class, previousAttempts, actor, actor));
    }
    private boolean currentRelationBackfillStatusDone(EventFoundationTestSupport.Database db, long actor) {
        return Boolean.TRUE.equals(db.admin().queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM eatwhat_sync.relation_backfill_job
                  WHERE user_id=? AND epoch=(SELECT epoch FROM eatwhat_consent.subject WHERE user_id=?)
                    AND status='DONE'
                )
                """, Boolean.class, actor, actor));
    }
    private void diagnosticSite(StringBuilder details, String label,
                                EventFoundationTestSupport.Database db, long actor) {
        details.append('\n').append(label).append(" subject=").append(db.admin().queryForList("""
                SELECT user_id,epoch,status,identity_ready,backfill_status,cleanup_status,valid_until
                FROM eatwhat_consent.subject WHERE user_id=?
                """, actor));
        details.append(" accountJobs=").append(db.admin().queryForList("""
                SELECT epoch,revision,kind,status,attempts FROM eatwhat_consent.job
                WHERE user_id=? ORDER BY epoch,revision,job_id
                """, actor));
        details.append(" relationJobs=").append(db.admin().queryForList("""
                SELECT epoch,status,phase,row_cursor,history_cursor,attempts,last_error
                FROM eatwhat_sync.relation_backfill_job WHERE user_id=? ORDER BY created_at
                """, actor));
        details.append(" intents=").append(db.admin().queryForList("""
                SELECT kind,desired_present,intent_revision,published_revision,published_actor_epoch,
                       published_owner_epoch,blocked_reason
                FROM eatwhat_sync.relation_intent
                WHERE actor_gid=(SELECT global_id FROM eatwhat_identity.entity_identity
                                 WHERE entity_type='users' AND numeric_id=?)
                ORDER BY kind
                """, actor));
        details.append(" outbox=").append(db.admin().queryForList("""
                SELECT aggregate_type,count(*) AS count FROM eatwhat_sync.outbox GROUP BY aggregate_type ORDER BY aggregate_type
                """));
        details.append(" delivery=").append(db.admin().queryForList("""
                SELECT o.aggregate_type,d.target_site,d.status,d.last_error,count(*) AS count
                FROM eatwhat_sync.delivery d JOIN eatwhat_sync.outbox o USING(event_id)
                GROUP BY o.aggregate_type,d.target_site,d.status,d.last_error ORDER BY o.aggregate_type,d.target_site,d.status
                """));
        details.append(" inbox=").append(db.admin().queryForList("""
                SELECT aggregate_type,status,last_error,count(*) AS count FROM eatwhat_sync.inbox
                GROUP BY aggregate_type,status,last_error ORDER BY aggregate_type,status
                """));
    }

    private class Sites implements AutoCloseable {
        final EventFoundationTestSupport.Database campusDb, externalDb;
        final Map<String, String> campusConfig, externalConfig;
        DualModeApplicationProcess campus, external;
        Sites() throws Exception { this(false, false, true); }
        Sites(boolean enablePr05d) throws Exception { this(enablePr05d, false, true); }
        Sites(boolean enablePr05d, boolean enableCatalog, boolean transportEnabled) throws Exception {
            SiteProperties a = site(SiteProperties.Mode.CAMPUS, "relation-campus");
            SiteProperties b = site(SiteProperties.Mode.EXTERNAL, "relation-external");
            campusDb = EventFoundationTestSupport.createDatabase(POSTGRES, a);
            externalDb = EventFoundationTestSupport.createDatabase(POSTGRES, b);
            int aAccount = unusedPort(), bAccount = unusedPort(), bSync = unusedPort();
            campusConfig = properties(campusDb, b, CAMPUS_REDIS, aAccount, bAccount, bSync,
                    enablePr05d, enableCatalog, transportEnabled);
            externalConfig = properties(externalDb, a, EXTERNAL_REDIS, bAccount, aAccount, bSync,
                    enablePr05d, enableCatalog, transportEnabled);
            try { restartCampus(); restartExternal(); } catch (Exception | Error failure) { close(); throw failure; }
        }
        void restartCampus() throws Exception {
            campus = new DualModeApplicationProcess(workingRoot, "campus-" + UUID.randomUUID(), campusConfig); campus.awaitReady(json);
        }
        void restartExternal() throws Exception {
            external = new DualModeApplicationProcess(workingRoot, "external-" + UUID.randomUUID(), externalConfig); external.awaitReady(json);
        }
        void killExternal() throws Exception {
            var child = ProcessHandle.of(external.pid()).orElseThrow();
            assertTrue(child.destroyForcibly());
            child.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            external.close(); external = null;
        }
        public void close() throws Exception { if (external != null) external.close(); if (campus != null) campus.close(); }
    }

    private SiteProperties site(SiteProperties.Mode mode, String id) {
        var p = new SiteProperties(); p.setMode(mode); p.setId(id); p.setInstanceId(UUID.randomUUID()); return p;
    }
    private int unusedPort() throws Exception {
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }

    private Map<String, String> properties(EventFoundationTestSupport.Database db, SiteProperties peer, GenericContainer<?> redis,
                                           int localAccountPort, int peerAccountPort, int externalSyncPort,
                                           boolean enablePr05d, boolean enableCatalog, boolean transportEnabled) {
        boolean campus = db.site().getMode() == SiteProperties.Mode.CAMPUS;
        var own = campus ? tls.campus() : tls.external();
        var remote = campus ? tls.external() : tls.campus();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("app.site.mode", db.site().getMode().name()); p.put("app.site.id", db.site().getId());
        p.put("app.site.instance-id", db.site().getInstanceId().toString());
        p.put("spring.datasource.url", db.url()); p.put("spring.datasource.username", db.runtimeUsername());
        p.put("spring.datasource.password", IsolatedContainers.PASSWORD);
        p.put("spring.flyway.url", db.url()); p.put("spring.flyway.user", IsolatedContainers.USER);
        p.put("spring.flyway.password", IsolatedContainers.PASSWORD);
        p.put("spring.data.redis.host", redis.getHost()); p.put("spring.data.redis.port", redis.getMappedPort(6379).toString());
        p.put("spring.data.redis.url", "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379) + "/0");
        p.put("spring.data.redis.username", ""); p.put("spring.data.redis.password", "");
        p.put("app.storage.s3.provider", campus ? "RUSTFS" : "R2");
        p.put("app.storage.s3.endpoint", campus ? "http://127.0.0.1:1" : "https://00000000000000000000000000000000.r2.cloudflarestorage.com");
        p.put("app.storage.s3.region", campus ? "us-east-1" : "auto"); p.put("app.storage.s3.bucket", "synthetic-activity-bucket");
        p.put("app.storage.s3.access-key", "synthetic-access"); p.put("app.storage.s3.secret-key", "synthetic-secret");
        p.put("app.storage.s3.path-style", "true"); p.put("app.storage.s3.public-base-url", "https://public.example.invalid/");
        p.put("app.storage.s3.public-read-prefixes", "avatar/"); p.put("app.storage.s3.allowed-read-prefixes", "avatar/,foods/,activity/");
        p.put("app.storage.s3.cdn-endpoint", "");
        // Account module (consent gating) -- the activity domain refuses to start without it.
        p.put("app.account.enabled", "true"); p.put("app.account.synthetic-only", "true"); p.put("app.account.policy-version", POLICY);
        p.put("app.account.authorization-ttl", "60s"); p.put("app.account.poll-interval", "250ms");
        p.put("app.account.request-timeout", "2s"); p.put("app.account.lease-duration", "10s");
        p.put("app.account.peer-site-id", peer.getId()); p.put("app.account.peer-instance-id", peer.getInstanceId().toString());
        p.put("app.account.peer-base-url", "https://127.0.0.1:" + peerAccountPort); p.put("app.account.listen-port", Integer.toString(localAccountPort));
        p.put("app.account.key-store", own.keyStore().toString()); p.put("app.account.key-store-password", TransportTlsFixture.PASSWORD);
        p.put("app.account.trust-store", tls.trustStore().toString()); p.put("app.account.trust-store-password", TransportTlsFixture.PASSWORD);
        p.put("app.account.peer-certificate-sha256", remote.pin());
        // Event transport carrying activity families: campus pushes/pulls, external only listens.
        p.put("app.sync.transport-enabled", Boolean.toString(transportEnabled)); p.put("app.sync.synthetic-only", "false");
        p.put("app.sync.peer-site-id", peer.getId()); p.put("app.sync.peer-instance-id", peer.getInstanceId().toString());
        if (campus) p.put("app.sync.peer-base-url", "https://127.0.0.1:" + externalSyncPort);
        else { p.put("app.sync.listen-host", "127.0.0.1"); p.put("app.sync.listen-port", Integer.toString(externalSyncPort)); }
        p.put("app.sync.key-store", own.keyStore().toString()); p.put("app.sync.key-store-password", TransportTlsFixture.PASSWORD);
        p.put("app.sync.trust-store", tls.trustStore().toString()); p.put("app.sync.trust-store-password", TransportTlsFixture.PASSWORD);
        p.put("app.sync.peer-certificate-sha256", remote.pin());
        p.put("app.sync.request-timeout", "2s"); p.put("app.sync.lease-duration", "10s"); p.put("app.sync.poll-interval", "250ms");
        p.put("app.sync.retry-base", "200ms"); p.put("app.sync.retry-max", "2s");
        p.put("app.sync.relation.enabled", "true"); p.put("app.sync.relation.peer-site-id", peer.getId());
        p.put("app.sync.activity.enabled", "true"); p.put("app.sync.activity.peer-site-id", peer.getId());
        p.put("app.sync.profile.enabled", Boolean.toString(enablePr05d));
        p.put("app.sync.profile.peer-site-id", peer.getId());
        p.put("app.sync.privacy.enabled", Boolean.toString(enablePr05d));
        p.put("app.sync.privacy.peer-site-id", peer.getId());
        p.put("app.sync.notification.enabled", Boolean.toString(enablePr05d));
        p.put("app.sync.notification.peer-site-id", peer.getId());
        p.put("app.sync.catalog.enabled", Boolean.toString(enableCatalog));
        p.put("app.sync.catalog.peer-site-id", peer.getId());
        return p;
    }
}
