package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.relation.RelationBackfillStore;
import you.v50to.eatwhat.sync.relation.RelationBackfillWorker;
import you.v50to.eatwhat.sync.relation.RelationCausalEvents;
import you.v50to.eatwhat.sync.relation.RelationKind;
import you.v50to.eatwhat.sync.relation.RelationStore;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Historical relation reconstruction uses isolated databases and the same runtime role as normal commands. */
class RelationBackfillIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("relation-backfill");
    private static final Duration LEASE = Duration.ofSeconds(20);
    private static final int BATCH_SIZE = 2;

    @BeforeAll
    static void startDependencies() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDependencies() {
        POSTGRES.stop();
    }

    @Test
    void actorGrantBackfillsEveryExistingRelationWithoutCreatingOrExtendingNotifications() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        pair.a().consent().withdraw(pair.actor());

        for (RelationKind kind : RelationKind.values()) {
            assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), kind, true), kind.name());
        }

        var sourceRows = relationIds(pair, pair.a());
        var noticesBefore = notifications(pair.a());
        assertTrue(RelationReplicationIT.events(pair.a()).isEmpty(), "Unconsented history must stay local");
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM eatwhat_sync.relation_operation"));

        grantAndProject(pair.a(), pair.b(), pair.actor());
        drainAcrossWorkerRestarts(pair.a());

        var events = RelationReplicationIT.events(pair.a());
        assertEquals(RelationKind.values().length, events.size());
        assertEquals(1, RelationReplicationIT.count(pair.a(), """
                SELECT count(*) FROM eatwhat_sync.relation_backfill_job WHERE user_id=? AND status='DONE'
                """, pair.actor()), "The actor's current-epoch scan reaches a durable DONE checkpoint");
        for (var event : events) {
            assertEquals(RelationCausalEvents.BRIDGE, event.eventType(),
                    "Historical backfill publishes a state bridge, not a new user action");
            var bridge = RelationCausalEvents.parseBridge(event.payloadJson());
            assertEquals(pair.a().consent().get(pair.actor()).epoch(), bridge.actorEpoch());
            assertEquals(pair.a().consent().get(pair.owner()).epoch(), bridge.ownerEpoch());
        }

        var noticesAfter = notifications(pair.a());
        assertEquals(noticesBefore, noticesAfter, "A historical notice keeps its original ID and TTL");

        RelationReplicationIT.exchange(pair);
        for (RelationKind kind : RelationKind.values()) {
            assertTrue(RelationReplicationIT.present(pair, pair.a(), kind), kind.name());
            assertTrue(RelationReplicationIT.present(pair, pair.b(), kind), kind.name());
            assertEquals(sourceRows.get(kind), relationId(pair, pair.a(), kind));
            assertEquals(sourceRows.get(kind), relationId(pair, pair.b(), kind));
        }
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM notifications"),
                "Backfilled relations do not synthesize old notices at the peer");

        int operationCount = events.size();
        long outboxCount = RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'");
        drain(pair.a());
        assertEquals(operationCount, RelationReplicationIT.events(pair.a()).size());
        assertEquals(outboxCount, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
    }

    @Test
    void ownerGrantQueuesBackfillForAnAlreadyGrantedActorsExistingRelation() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        pair.a().consent().withdraw(pair.owner());
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.favorite, true));
        assertTrue(RelationReplicationIT.events(pair.a()).isEmpty());

        grantAndProject(pair.a(), pair.b(), pair.owner());
        drain(pair.a());
        assertEquals(1, RelationReplicationIT.events(pair.a()).size());

        RelationReplicationIT.exchange(pair);
        assertTrue(RelationReplicationIT.present(pair, pair.b(), RelationKind.favorite));
    }

    @Test
    void withdrawalPurgeThenRegrantRestoresOnlyRelationsTheUserDidNotCancel() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        RelationReplicationIT.change(pair, pair.a(), RelationKind.follow, true);
        RelationReplicationIT.change(pair, pair.a(), RelationKind.favorite, true);
        RelationReplicationIT.exchange(pair);

        long originalFollowId = relationId(pair, pair.a(), RelationKind.follow);
        long originalFavoriteId = relationId(pair, pair.a(), RelationKind.favorite);
        long sourceNoticeCount = RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM notifications");

        pair.a().consent().withdraw(pair.actor());
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, false),
                "A local cancellation during withdrawal updates desired state");
        assertFalse(RelationReplicationIT.present(pair, pair.a(), RelationKind.follow));
        assertTrue(RelationReplicationIT.present(pair, pair.a(), RelationKind.favorite));

        applyPurge(pair.a(), pair.b(), pair.actor());
        assertFalse(RelationReplicationIT.present(pair, pair.b(), RelationKind.follow));
        assertFalse(RelationReplicationIT.present(pair, pair.b(), RelationKind.favorite));
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM notifications"));

        grantAndProject(pair.a(), pair.b(), pair.actor());
        drain(pair.a());
        assertEquals(4, RelationReplicationIT.events(pair.a()).size(),
                "Regrant emits current-state bridges for the local cancellation and retained favorite");
        RelationReplicationIT.exchange(pair);

        assertFalse(RelationReplicationIT.present(pair, pair.a(), RelationKind.follow));
        assertFalse(RelationReplicationIT.present(pair, pair.b(), RelationKind.follow),
                "An old ADD must not override the explicit withdrawal-period unfollow");
        assertTrue(RelationReplicationIT.present(pair, pair.a(), RelationKind.favorite));
        assertTrue(RelationReplicationIT.present(pair, pair.b(), RelationKind.favorite),
                "Purge alone must not be interpreted as a user REMOVE");
        assertEquals(originalFollowId, relationIntentRowId(pair.a(), pair, RelationKind.follow),
                "An absent local follow keeps its numeric identity in the durable intent tombstone");
        assertEquals(originalFavoriteId, relationId(pair, pair.a(), RelationKind.favorite));
        assertEquals(originalFavoriteId, relationId(pair, pair.b(), RelationKind.favorite));
        assertEquals(sourceNoticeCount, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM notifications"));
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM notifications"),
                "History replay does not recreate purged notifications");
    }

    @Test
    void sameEpochRemovalWaitsForExpiredAuthorizationAndWinsOverAnOldIncomingAdd() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        RelationReplicationIT.change(pair, pair.a(), RelationKind.follow, true);
        var originalEvent = RelationReplicationIT.events(pair.a()).getFirst();
        RelationReplicationIT.receive(pair.b(), originalEvent);

        pair.b().db().admin().update("UPDATE eatwhat_consent.subject SET valid_until=NULL WHERE user_id=?", pair.actor());
        assertFalse(RelationReplicationIT.changeResult(pair, pair.b(), RelationKind.follow, false),
                "Legacy unfollow on an absent local projection remains a successful no-op");
        assertFalse(RelationReplicationIT.changeResult(pair, pair.b(), RelationKind.follow, false),
                "Repeating the dirty no-op must not pretend the unpublished REMOVE was sent");
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
        assertEquals(1, dirtyIntentCount(pair.b(), pair, RelationKind.follow));

        pair.b().db().admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()+interval '1 minute' WHERE user_id=?",
                pair.actor());
        RelationReplicationIT.process(pair.b());
        assertFalse(RelationReplicationIT.present(pair, pair.b(), RelationKind.follow),
                "An inbound ADD cannot overwrite an unpublished local cancellation");

        drain(pair.b());
        assertEquals(0, dirtyIntentCount(pair.b(), pair, RelationKind.follow),
                "The current epoch REMOVE acknowledges the exact dirty intent revision");
        RelationReplicationIT.exchange(pair);
        assertFalse(RelationReplicationIT.present(pair, pair.a(), RelationKind.follow));
        assertFalse(RelationReplicationIT.present(pair, pair.b(), RelationKind.follow));
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "Campus retains only its original ADD evidence; the peer REMOVE arrives as a state bridge");
        assertEquals(2, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "The peer keeps the imported ADD and its real local REMOVE evidence");
        assertEquals(2, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_causal_fact"));
        assertEquals(2, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM eatwhat_sync.relation_causal_fact"),
                "The peer imports the same logical ADD and REMOVE facts through the bridge");
    }

    @Test
    void concurrentWorkersAndAnAbandonedLeaseProduceOneReplayableCurrentEpochOperation() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        pair.a().consent().withdraw(pair.actor());
        RelationReplicationIT.change(pair, pair.a(), RelationKind.favorite, true);
        grantAndProject(pair.a(), pair.b(), pair.actor());

        var first = worker(pair.a());
        var second = worker(pair.a());
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var left = executor.submit(() -> { start.await(); return first.runOnce(); });
            var right = executor.submit(() -> { start.await(); return second.runOnce(); });
            start.countDown();
            left.get(20, TimeUnit.SECONDS);
            right.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, RelationReplicationIT.events(pair.a()).size());
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_operation"));
        RelationReplicationIT.exchange(pair);
        assertTrue(RelationReplicationIT.present(pair, pair.b(), RelationKind.favorite));

        int operationCount = RelationReplicationIT.events(pair.a()).size();
        int beforeIdempotentDrain = RelationReplicationIT.events(pair.a()).size();
        drain(pair.a());
        assertEquals(beforeIdempotentDrain, RelationReplicationIT.events(pair.a()).size());
        assertEquals(operationCount, RelationReplicationIT.events(pair.a()).size());
    }

    @Test
    void sameEpochDirtyIntentReopensACompletedJobAfterAuthorizationRecovers() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        pair.a().consent().withdraw(pair.actor());
        RelationReplicationIT.change(pair, pair.a(), RelationKind.follow, true);
        grantAndProject(pair.a(), pair.b(), pair.actor());

        drain(pair.a());
        assertEquals(1, RelationReplicationIT.events(pair.a()).size());
        assertEquals(1, RelationReplicationIT.count(pair.a(), """
                SELECT count(*) FROM eatwhat_sync.relation_backfill_job WHERE user_id=? AND status='DONE'
                """, pair.actor()));
        assertEquals(1, RelationReplicationIT.events(pair.a()).size());

        pair.a().db().admin().update("UPDATE eatwhat_consent.subject SET identity_ready=false WHERE user_id=?", pair.actor());
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, false));
        assertEquals(1, dirtyIntentCount(pair.a(), pair, RelationKind.follow));
        drain(pair.a());
        assertEquals(1, RelationReplicationIT.events(pair.a()).size(),
                "A same-epoch dirty intent waits while authorization is unavailable");

        pair.a().db().admin().update("""
                UPDATE eatwhat_consent.subject SET identity_ready=true WHERE user_id=?
                """, pair.actor());
        drain(pair.a());
        assertEquals(2, RelationReplicationIT.events(pair.a()).size());
        RelationReplicationIT.exchange(pair);
        assertFalse(RelationReplicationIT.present(pair, pair.a(), RelationKind.follow));
        assertFalse(RelationReplicationIT.present(pair, pair.b(), RelationKind.follow));
    }

    @Test
    void expiredLeaseFencesTheOldWorkerAndTheNewLeaseContinuesWithoutDuplicateFacts() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        pair.a().consent().withdraw(pair.actor());
        long restaurant = pair.a().db().runtime().queryForObject("""
                INSERT INTO restaurant(name,location,gcj_lng,gcj_lat,account_id)
                VALUES('lease actor restaurant',ST_SetSRID(ST_Point(117,36),4326),117,36,?) RETURNING id
                """, Long.class, pair.actor());
        long food = pair.a().db().runtime().queryForObject("""
                INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'lease actor food') RETURNING id
                """, Long.class, pair.actor(), restaurant);
        pair.a().commands().change(RelationKind.favorite, pair.actor(), food, null, true);
        grantAndProject(pair.a(), pair.b(), pair.actor());

        var store = store(pair.a());
        store.enqueueReadySubjects();
        long v1EvidenceBeforeLease = RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation");
        var factsBeforeLease = RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact");
        var outboxBeforeLease = RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'");
        var staleLease = claimActorJob(store, pair.actor(), LEASE);
        pair.a().db().admin().update("""
                UPDATE eatwhat_sync.relation_backfill_job
                SET lease_until=clock_timestamp()-interval '1 second'
                WHERE job_id=? AND lease_token=?
                """, staleLease.id(), staleLease.token());
        var currentLease = claimActorJob(store, pair.actor(), LEASE);
        assertNotEquals(staleLease.token(), currentLease.token());

        var causalStateBeforeStale = pair.a().db().runtime().queryForList("""
                SELECT natural_id::text,seen::text,add_witnesses::text,remove_frontier::text,blocked_reason
                FROM eatwhat_sync.relation_causal_state ORDER BY natural_id
                """);
        var causalFactsBeforeStale = pair.a().db().runtime().queryForList("""
                SELECT event_id::text,natural_id::text,stream_key,dot_counter,context::text,adding,payload::text
                FROM eatwhat_sync.relation_causal_fact ORDER BY natural_id,stream_key,dot_counter
                """);
        var intentsBeforeStale = pair.a().db().runtime().queryForList("""
                SELECT natural_id::text,desired_present,intent_revision,published_revision,
                       published_actor_epoch,published_owner_epoch,row_id,row_gid::text,blocked_reason
                FROM eatwhat_sync.relation_intent ORDER BY natural_id
                """);
        var jobBeforeStale = pair.a().db().runtime().queryForList("""
                SELECT status,phase,row_cursor,history_cursor,lease_token::text,lease_until,attempts
                FROM eatwhat_sync.relation_backfill_job WHERE job_id=?
                """, staleLease.id());

        var staleResult = store.process(staleLease, 1);
        assertTrue(staleResult.waiting());
        assertEquals(causalStateBeforeStale, pair.a().db().runtime().queryForList("""
                SELECT natural_id::text,seen::text,add_witnesses::text,remove_frontier::text,blocked_reason
                FROM eatwhat_sync.relation_causal_state ORDER BY natural_id
                """), "The stale worker leaves all causal state unchanged");
        assertEquals(causalFactsBeforeStale, pair.a().db().runtime().queryForList("""
                SELECT event_id::text,natural_id::text,stream_key,dot_counter,context::text,adding,payload::text
                FROM eatwhat_sync.relation_causal_fact ORDER BY natural_id,stream_key,dot_counter
                """), "The stale worker leaves immutable facts unchanged");
        assertEquals(intentsBeforeStale, pair.a().db().runtime().queryForList("""
                SELECT natural_id::text,desired_present,intent_revision,published_revision,
                       published_actor_epoch,published_owner_epoch,row_id,row_gid::text,blocked_reason
                FROM eatwhat_sync.relation_intent ORDER BY natural_id
                """), "The stale worker cannot advance intent publication");
        assertEquals(jobBeforeStale, pair.a().db().runtime().queryForList("""
                SELECT status,phase,row_cursor,history_cursor,lease_token::text,lease_until,attempts
                FROM eatwhat_sync.relation_backfill_job WHERE job_id=?
                """, staleLease.id()), "The active lease token and cursor remain owned by the new worker");
        assertEquals(v1EvidenceBeforeLease, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "An expired worker cannot create V1 action evidence");
        assertEquals(factsBeforeLease, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact"),
                "An expired worker cannot commit a causal fact");
        assertEquals(outboxBeforeLease, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"),
                "An expired worker cannot publish a bridge");

        store.process(currentLease, 1);
        drain(pair.a());
        assertEquals(v1EvidenceBeforeLease, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "A pure state bridge does not fabricate a V1 operation");
        assertEquals(factsBeforeLease, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact"));
        assertEquals(outboxBeforeLease + 1, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
        assertEquals(RelationCausalEvents.BRIDGE, RelationReplicationIT.events(pair.a()).getFirst().eventType());
        assertEquals(1, RelationReplicationIT.count(pair.a(), """
                SELECT count(*) FROM eatwhat_sync.relation_backfill_job WHERE user_id=? AND status='DONE'
                """, pair.actor()));
    }

    @Test
    void committedRemoveWinsOverAStaleBackfillCandidateSnapshot() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        long rowId = pair.a().db().admin().queryForObject("""
                INSERT INTO public.likes(account_id,target_type,target_id) VALUES(?,'food',?) RETURNING id
                """, Long.class, pair.actor(), pair.food());

        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.food_like, false));
        int eventCountAfterRemove = RelationReplicationIT.events(pair.a()).size();
        assertEquals(1, eventCountAfterRemove);
        assertFalse(RelationReplicationIT.present(pair, pair.a(), RelationKind.food_like));

        assertTrue(pair.a().commands().backfillCandidate(RelationKind.food_like, pair.actor(), pair.food(),
                pair.owner(), null, rowId, true));
        assertFalse(RelationReplicationIT.present(pair, pair.a(), RelationKind.food_like),
                "A scan candidate read before REMOVE must re-read the persisted intent under the relation lock");
        assertEquals(eventCountAfterRemove, RelationReplicationIT.events(pair.a()).size());
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_operation"));
    }

    @Test
    void candidateFactAndCursorRollbackTogetherThenTheSamePageCanRecover() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        long restaurant = pair.a().db().runtime().queryForObject("""
                INSERT INTO restaurant(name,location,gcj_lng,gcj_lat,account_id)
                VALUES('rollback actor restaurant',ST_SetSRID(ST_Point(117,36),4326),117,36,?) RETURNING id
                """, Long.class, pair.actor());
        long food = pair.a().db().runtime().queryForObject("""
                INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'rollback actor food') RETURNING id
                """, Long.class, pair.actor(), restaurant);
        long rowId = pair.a().db().admin().queryForObject("""
                INSERT INTO public.likes(account_id,target_type,target_id) VALUES(?,'food',?) RETURNING id
                """, Long.class, pair.actor(), food);

        var store = store(pair.a());
        store.enqueueReadySubjects();
        var job = claimActorJob(store, pair.actor(), LEASE);
        pair.a().db().admin().execute("""
                CREATE FUNCTION public.fail_backfill_cursor_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF OLD.row_cursor < NEW.row_cursor THEN
                    RAISE EXCEPTION 'injected relation cursor checkpoint failure' USING ERRCODE='P0001';
                  END IF;
                  RETURN NEW;
                END $$
                """);
        pair.a().db().admin().execute("""
                CREATE TRIGGER fail_backfill_cursor_for_test BEFORE UPDATE OF row_cursor
                ON eatwhat_sync.relation_backfill_job FOR EACH ROW
                EXECUTE FUNCTION public.fail_backfill_cursor_for_test()
                """);
        try {
            assertThrows(RuntimeException.class, () -> store.process(job, 1));
            assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_operation"));
            assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
            assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_intent"));
            assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_causal_fact"));
            assertEquals(0, RelationReplicationIT.count(pair.a(), """
                    SELECT row_cursor FROM eatwhat_sync.relation_backfill_job WHERE job_id=?
                    """, job.id()));
        } finally {
            pair.a().db().admin().execute("DROP TRIGGER fail_backfill_cursor_for_test ON eatwhat_sync.relation_backfill_job");
            pair.a().db().admin().execute("DROP FUNCTION public.fail_backfill_cursor_for_test()");
        }

        store.process(job, 1);
        assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "Backfill publishes a pure state bridge instead of fabricating a V1 action");
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_causal_fact"),
                "The transaction commits the deterministic baseline fact with its cursor");
        assertEquals(RelationCausalEvents.BRIDGE, RelationReplicationIT.events(pair.a()).getFirst().eventType());
        assertEquals(rowId, RelationReplicationIT.count(pair.a(), """
                SELECT row_cursor FROM eatwhat_sync.relation_backfill_job WHERE job_id=?
                """, job.id()));
        // Clear other subject leases created by the same claim batch so recovery observes durable work,
        // not an unrelated lease waiting for its timeout.
        for (var other : store.claim(16, LEASE)) {
            store.process(other, 32);
        }
        drain(pair.a());
        assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "Restarting after the atomic commit cannot fabricate V1 evidence");
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_causal_fact"));
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"),
                "Restarting after the atomic commit cannot duplicate the bridge");
    }

    @Test
    void blockedAmbiguousLegacyIntentDoesNotStarveLaterValidRelationWork() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        pair.a().consent().withdraw(pair.actor());
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));

        UUID actorGid = globalId(pair.a(), "users", pair.actor());
        UUID ownerGid = globalId(pair.a(), "users", pair.owner());
        UUID targetGid = globalId(pair.a(), "foods", pair.food());
        UUID blockedNaturalId = UUID.randomUUID();
        pair.a().db().admin().update("""
                INSERT INTO eatwhat_sync.relation_intent(
                  natural_id,kind,actor_gid,target_gid,owner_gid,desired_present,intent_revision,published_revision,
                  row_id,row_gid,row_origin,local_intent,blocked_reason)
                VALUES(?,'food_like',?,?,?,true,1,0,1,?, ?,false,'LEGACY_HISTORY_AMBIGUOUS')
                """, blockedNaturalId, actorGid, targetGid, ownerGid, UUID.randomUUID(),
                pair.a().db().site().getId());

        grantAndProject(pair.a(), pair.b(), pair.actor());
        drain(pair.a());
        RelationReplicationIT.exchange(pair);

        assertTrue(RelationReplicationIT.present(pair, pair.a(), RelationKind.follow));
        assertTrue(RelationReplicationIT.present(pair, pair.b(), RelationKind.follow),
                "A blocked ambiguous historical row cannot hold the subject job ahead of valid work");
        assertEquals(1, RelationReplicationIT.count(pair.a(), """
                SELECT count(*) FROM eatwhat_sync.relation_intent
                WHERE natural_id=? AND blocked_reason='LEGACY_HISTORY_AMBIGUOUS'
                """, blockedNaturalId));
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.relation_operation"));
    }

    @Test
    void moreThanSixtyFourUnavailableIntentsDoNotStarveValidIntentOrDeletedActivityPhase() throws Exception {
        var pair = RelationReplicationIT.pair(POSTGRES);
        pair.a().consent().withdraw(pair.actor());

        long ownRestaurant = pair.a().db().runtime().queryForObject("""
                INSERT INTO restaurant(name,location,gcj_lng,gcj_lat,account_id)
                VALUES('actor-owned fixture',ST_SetSRID(ST_Point(117,36),4326),117,36,?) RETURNING id
                """, Long.class, pair.actor());
        long ownFood = pair.a().db().runtime().queryForObject("""
                INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'actor-owned target') RETURNING id
                """, Long.class, pair.actor(), ownRestaurant);
        RelationReplicationIT.mirror(pair.a().db(), pair.b().db(), "restaurant", ownRestaurant);
        RelationReplicationIT.mirror(pair.a().db(), pair.b().db(), "foods", ownFood);
        long activity = pair.a().db().runtime().queryForObject("""
                INSERT INTO activity_foods(account_id,food_name,picture_url)
                VALUES(?,'deleted synthetic activity',ARRAY['activity/deleted.png']) RETURNING id
                """, Long.class, pair.actor());
        RelationReplicationIT.mirror(pair.a().db(), pair.b().db(), "activity_foods", activity);
        long deletedActivityLike = pair.a().db().runtime().queryForObject("""
                INSERT INTO activity_likes(account_id,target_type,activity_id)
                VALUES(?,'food',?) RETURNING id
                """, Long.class, pair.actor(), activity);
        pair.a().db().admin().update("UPDATE activity_likes SET deleted_at=clock_timestamp() WHERE id=?", deletedActivityLike);
        pair.a().db().admin().update("UPDATE activity_foods SET deleted_at=clock_timestamp() WHERE id=?", activity);

        assertTrue(pair.a().commands().change(RelationKind.food_like, pair.actor(), ownFood, null, true));
        long localV1Evidence = RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation");
        assertEquals(0, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"),
                "An explicit unconsented action is retained locally without an Outbox event");
        UUID actorGid = globalId(pair.a(), "users", pair.actor());
        UUID validTargetGid = globalId(pair.a(), "foods", ownFood);
        UUID validNaturalId = RelationStore.naturalId(RelationKind.food_like, actorGid, validTargetGid,
                actorGid, null);
        assertTrue(Boolean.TRUE.equals(pair.a().db().admin().queryForObject(
                "SELECT ?::uuid < ?::uuid", Boolean.class, new UUID(0, 65), validNaturalId)),
                "The synthetic unavailable intents precede the valid intent in database cursor order");

        for (long index = 1; index <= 65; index++) {
            pair.a().db().admin().update("""
                    INSERT INTO eatwhat_sync.relation_intent(
                      natural_id,kind,actor_gid,target_gid,owner_gid,desired_present,intent_revision,published_revision,
                      row_id,row_gid,row_origin,local_intent)
                    VALUES(?,'food_like',?,?,?,true,1,0,1,?,?,true)
                    """, new UUID(0, index), actorGid, UUID.randomUUID(), actorGid, UUID.randomUUID(),
                    pair.a().db().site().getId());
        }

        grantAndProject(pair.a(), pair.b(), pair.actor());
        var worker = new RelationBackfillWorker(store(pair.a()), LEASE, 32);
        worker.runOnce();
        assertEquals(localV1Evidence, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation"));
        assertEquals(0, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
        worker.runOnce();
        assertEquals(localV1Evidence, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation"));
        assertEquals(0, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
        worker.runOnce();
        assertEquals(localV1Evidence, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "Reauthorization does not mint a second V1 user operation");
        assertEquals(1, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"),
                "The valid item after 65 unavailable owner/target dependencies is reached in the next bounded pass");
        assertEquals(RelationCausalEvents.BRIDGE, RelationReplicationIT.events(pair.a()).getFirst().eventType());

        long waitingJobs = 0;
        for (int attempt = 0; attempt < 40 && waitingJobs == 0; attempt++) {
            worker.runOnce();
            waitingJobs = RelationReplicationIT.count(pair.a(), """
                    SELECT count(*) FROM eatwhat_sync.relation_backfill_job
                    WHERE user_id=? AND status='WAIT' AND phase=6 AND last_error='DEPENDENCY_WAIT'
                    """, pair.actor());
        }
        assertEquals(1, waitingJobs,
                "The job finishes all relation phases but remains retryable for unresolved intent dependencies");
        assertEquals(localV1Evidence, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "A soft-deleted activity like does not block the scanner from finishing later relation phases");
    }

    private static RelationBackfillWorker worker(RelationReplicationIT.Site site) {
        return new RelationBackfillWorker(store(site), LEASE, BATCH_SIZE);
    }

    private static RelationBackfillStore.Job claimActorJob(RelationBackfillStore store, long actor,
                                                            Duration lease) {
        var jobs = store.claim(16, lease);
        RelationBackfillStore.Job actorJob = jobs.stream()
                .filter(job -> job.userId() == actor)
                .findFirst().orElseThrow();
        for (var job : jobs) {
            if (!job.id().equals(actorJob.id())) {
                store.process(job, 32);
            }
        }
        return actorJob;
    }

    private static RelationBackfillStore store(RelationReplicationIT.Site site) {
        var outbox = new OutboxService(site.db().runtime(), site.db().transactions(),
                RelationReplicationIT.CODEC, site.db().site());
        var identities = new EntityIdentityService(site.db().runtime());
        return new RelationBackfillStore(site.db().runtime(), site.db().transactions(), site.store(),
                outbox, identities, site.consent(), site.db().site(), site.peerSiteId());
    }

    private static int drain(RelationReplicationIT.Site site) {
        RelationBackfillWorker worker = worker(site);
        int processed = 0;
        for (int attempt = 0; attempt < 100; attempt++) {
            processed += worker.runOnce();
            long runnableJobs = RelationReplicationIT.count(site, """
                    SELECT count(*) FROM eatwhat_sync.relation_backfill_job
                    WHERE status IN ('READY','LEASED')
                       OR (status='WAIT' AND available_at<=clock_timestamp())
                    """);
            if (runnableJobs == 0) return processed;
        }
        fail("Relation backfill did not reach a durable wait or done state within 100 passes");
        return processed;
    }

    private static int drainAcrossWorkerRestarts(RelationReplicationIT.Site site) {
        int processed = 0;
        for (int attempt = 0; attempt < 250; attempt++) {
            processed += new RelationBackfillWorker(store(site), LEASE, 1).runOnce();
            long activeJobs = RelationReplicationIT.count(site, """
                    SELECT count(*) FROM eatwhat_sync.relation_backfill_job
                    WHERE status IN ('READY','LEASED')
                       OR (status='WAIT' AND available_at<=clock_timestamp())
                    """);
            if (activeJobs == 0) return processed;
        }
        fail("Relation backfill did not finish within 250 one-item worker passes");
        return processed;
    }

    private static void grantAndProject(RelationReplicationIT.Site source, RelationReplicationIT.Site peer, long userId) {
        ConsentModels.State grant = source.consent().grant(userId, RelationReplicationIT.POLICY);
        ConsentModels.Job job = source.consent().claim(32, LEASE).stream()
                .filter(candidate -> candidate.userId() == userId && candidate.epoch() == grant.epoch()
                        && candidate.kind().equals("UPSERT"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", peer.consent().apply(source.consent().export(job)));
        source.consent().complete(job);
    }

    private static void applyPurge(RelationReplicationIT.Site source, RelationReplicationIT.Site peer, long userId) {
        source.consent().withdraw(userId);
        ConsentModels.Job job = source.consent().claim(32, LEASE).stream()
                .filter(candidate -> candidate.userId() == userId && candidate.kind().equals("PURGE"))
                .findFirst().orElseThrow();
        assertNull(source.consent().export(job).data());
        assertEquals("APPLIED", peer.consent().apply(source.consent().export(job)));
        source.consent().complete(job);
    }

    private static Map<RelationKind, Long> relationIds(RelationReplicationIT.Pair pair,
                                                        RelationReplicationIT.Site site) {
        var result = new EnumMap<RelationKind, Long>(RelationKind.class);
        for (RelationKind kind : RelationKind.values()) {
            result.put(kind, relationId(pair, site, kind));
        }
        return result;
    }

    private static long relationId(RelationReplicationIT.Pair pair, RelationReplicationIT.Site site,
                                   RelationKind kind) {
        StringBuilder sql = new StringBuilder("SELECT id FROM ").append(kind.table)
                .append(" WHERE account_id=? AND ").append(kind.targetColumn).append("=?");
        var arguments = new java.util.ArrayList<Object>();
        arguments.add(pair.actor());
        arguments.add(pair.target(kind));
        if (kind.targetType != null) {
            sql.append(" AND target_type=?");
            arguments.add(kind.targetType);
        }
        if (kind == RelationKind.tagging) {
            sql.append(" AND tag_id=?");
            arguments.add(pair.tag());
        }
        return site.db().runtime().queryForObject(sql.toString(), Long.class, arguments.toArray());
    }

    private static long relationIntentRowId(RelationReplicationIT.Site site, RelationReplicationIT.Pair pair,
                                            RelationKind kind) {
        UUID actor = globalId(site, "users", pair.actor());
        UUID target = globalId(site, kind.targetTable, pair.target(kind));
        return site.db().runtime().queryForObject("""
                SELECT row_id FROM eatwhat_sync.relation_intent
                WHERE kind=? AND actor_gid=? AND target_gid=?
                """, Long.class, kind.name(), actor, target);
    }

    private static List<Map<String, Object>> notifications(RelationReplicationIT.Site site) {
        return site.db().runtime().queryForList("SELECT id,created_at,expires_at FROM notifications ORDER BY id");
    }

    private static long dirtyIntentCount(RelationReplicationIT.Site site, RelationReplicationIT.Pair pair,
                                        RelationKind kind) {
        UUID actor = globalId(site, "users", pair.actor());
        UUID target = globalId(site, "users", pair.owner());
        UUID owner = globalId(site, "users", pair.owner());
        UUID naturalId = RelationStore.naturalId(kind, actor, target, owner, null);
        return RelationReplicationIT.count(site, """
                SELECT count(*) FROM eatwhat_sync.relation_intent
                WHERE natural_id=? AND published_revision<intent_revision
                """, naturalId);
    }

    private static UUID globalId(RelationReplicationIT.Site site, String type, long rowId) {
        return UUID.fromString(site.db().runtime().queryForObject("""
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type=? AND numeric_id=?
                """, String.class, type, rowId));
    }

}
