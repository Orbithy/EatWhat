package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.UnexpectedRollbackException;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.sync.DeliveryService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.InboundApplicationScope;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic local business effects only; the application role performs real PostgreSQL operations. */
class OutboxDeliveryIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("outbox-delivery");
    private static final String TARGET = "external-test";
    private static final Duration LEASE = Duration.ofMinutes(1);

    @BeforeAll static void start() { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void businessOutboxDeliveryAndVersionCommitTogetherAndRequireTheBusinessTransaction() {
        var db = database();
        var event = event(db, UUID.randomUUID(), 1, VersionVector.empty());
        var outbox = outbox(db);
        assertThrows(RuntimeException.class, () -> outbox.append(event, List.of(TARGET)));
        assertEmpty(db);
        db.transactions().required(() -> {
            createBusiness(db, event);
            outbox.append(event, List.of(TARGET));
            return null;
        });
        assertEquals(1, count(db, "outbox"));
        assertEquals(1, count(db, "delivery"));
        assertEquals(1, businessValue(db, event.aggregateId()));
        assertEquals(event.nextVersion(), vector(db, event.aggregateId()));
        assertEquals(new EventCodec().encode(event).hash(), db.runtime().queryForObject(
                "SELECT envelope_hash FROM eatwhat_sync.outbox WHERE event_id=?", String.class, event.eventId()));
        assertEquals("READY", db.runtime().queryForObject("SELECT status FROM eatwhat_sync.delivery", String.class));
    }

    @Test
    void failureAfterAppendRollsBackBusinessEventDeliveryAndState() {
        var db = database();
        var event = event(db, UUID.randomUUID(), 1, VersionVector.empty());
        assertThrows(IllegalStateException.class, () -> db.transactions().required(() -> {
            createBusiness(db, event);
            outbox(db).append(event, List.of(TARGET));
            throw new IllegalStateException("synthetic failure after append");
        }));
        assertEmpty(db);
    }

    @Test
    void failureOnSecondDeliveryRollsBackAlreadyWrittenOutboxAndFirstDelivery() {
        var db = database();
        var event = event(db, UUID.randomUUID(), 1, VersionVector.empty());
        db.admin().execute("""
                CREATE FUNCTION public.fail_second_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.target_site='failure-target' THEN
                    IF (SELECT count(*) FROM eatwhat_sync.outbox)<>1
                       OR (SELECT count(*) FROM eatwhat_sync.delivery)<>1 THEN
                      RAISE EXCEPTION 'Injection happened before previous writes' USING ERRCODE='P0002';
                    END IF;
                    RAISE EXCEPTION 'Synthetic delivery failure' USING ERRCODE='P0001';
                  END IF;
                  RETURN NEW;
                END $$
                """);
        db.admin().execute("""
                CREATE TRIGGER fail_second_delivery BEFORE INSERT ON eatwhat_sync.delivery
                FOR EACH ROW EXECUTE FUNCTION public.fail_second_delivery()
                """);
        DataAccessException failure = assertThrows(DataAccessException.class, () -> db.transactions().required(() -> {
            createBusiness(db, event);
            outbox(db).append(event, List.of(TARGET, "failure-target"));
            return null;
        }));
        assertEquals("P0001", ((SQLException) failure.getMostSpecificCause()).getSQLState());
        assertEmpty(db);
        db.admin().execute("DROP TRIGGER fail_second_delivery ON eatwhat_sync.delivery");
        db.transactions().required(() -> { createBusiness(db, event); outbox(db).append(event, List.of(TARGET)); return null; });
        assertEquals(1, count(db, "outbox"));
    }

    @Test
    void caughtDuplicateAppendStillRollsBackTheCallersBusinessMutation() {
        var db = database();
        var event = event(db, UUID.randomUUID(), 1, VersionVector.empty());
        appendBusiness(db, event);
        assertThrows(UnexpectedRollbackException.class, () -> db.transactions().required(() -> {
            db.runtime().update("UPDATE business_counter SET value=value+1 WHERE gid=?", event.aggregateId());
            assertThrows(IllegalStateException.class, () -> outbox(db).append(event, List.of(TARGET)));
            return null;
        }));
        assertEquals(1, businessValue(db, event.aggregateId()));
        assertEquals(1, count(db, "outbox"));
        assertEquals(event.nextVersion(), vector(db, event.aggregateId()));
    }

    @Test
    void inboundRecaptureAndWrongOriginCannotLeavePartialLocalWrites() {
        var db = database();
        var event = event(db, UUID.randomUUID(), 1, VersionVector.empty());
        assertThrows(IllegalStateException.class, () -> db.transactions().required(() -> {
            createBusiness(db, event);
            return InboundApplicationScope.run(() -> { outbox(db).append(event, List.of(TARGET)); return null; });
        }));
        assertFalse(InboundApplicationScope.isActive());
        var forged = new SyncEvent(event.eventId(), event.originSite(), UUID.randomUUID(), event.aggregateType(),
                event.aggregateId(), event.numericId(), event.eventType(), 1, event.baseVersion(), event.nextVersion(),
                event.dependencies(), event.actorId(), event.commandKey(), event.correlationId(), event.payloadJson());
        assertThrows(IllegalStateException.class, () -> db.transactions().required(() -> {
            createBusiness(db, forged); outbox(db).append(forged, List.of(TARGET)); return null;
        }));
        assertEmpty(db);
        appendBusiness(db, event);
        assertEquals(1, count(db, "outbox"));
    }

    @Test
    void concurrentFirstAggregateWritesCommitExactlyOneVersionAndOneBusinessEffect() throws Exception {
        var db = database();
        UUID gid = UUID.randomUUID();
        db.runtime().update("INSERT INTO business_counter(gid,numeric_id,value) VALUES (?,1,0)", gid);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                var event = event(db, gid, 1, VersionVector.empty());
                tasks.add(executor.submit(() -> {
                    await(start);
                    try {
                        db.transactions().required(() -> {
                            // Append competes on the initially absent entity_state before business row locking.
                            outbox(db).append(event, List.of(TARGET));
                            db.runtime().update("UPDATE business_counter SET value=value+1 WHERE gid=?", gid);
                            return null;
                        });
                        return true;
                    } catch (IllegalStateException expected) { return false; }
                }));
            }
            start.countDown();
            int successful = 0;
            for (var task : tasks) if (task.get(20, TimeUnit.SECONDS)) successful++;
            assertEquals(1, successful);
        }
        assertEquals(1, businessValue(db, gid));
        assertEquals(1, count(db, "outbox"));
        assertEquals(VersionVector.empty().increment(db.site().getId()), vector(db, gid));
    }

    @Test
    void numericIdentityCollisionsRemainExplicitAndRollBackTheBusinessMutation() {
        var db = database();
        var original = event(db, UUID.randomUUID(), 1, VersionVector.empty());
        appendBusiness(db, original);
        var differentGlobalId = event(db, UUID.randomUUID(), 1, VersionVector.empty());
        IllegalStateException collision = assertThrows(IllegalStateException.class,
                () -> appendBusiness(db, differentGlobalId));
        assertTrue(collision.getMessage().contains("another global identity"));
        assertEquals(0, db.runtime().queryForObject("SELECT count(*) FROM business_counter WHERE gid=?",
                Integer.class, differentGlobalId.aggregateId()));

        var differentNumericId = event(db, original.aggregateId(), 2, original.nextVersion());
        IllegalStateException mismatch = assertThrows(IllegalStateException.class, () -> db.transactions().required(() -> {
            db.runtime().update("UPDATE business_counter SET value=value+1 WHERE gid=?", original.aggregateId());
            outbox(db).append(differentNumericId, List.of(TARGET));
            return null;
        }));
        assertTrue(mismatch.getMessage().contains("differs from its existing global identity"));
        assertEquals(1, businessValue(db, original.aggregateId()));
        assertEquals(original.nextVersion(), vector(db, original.aggregateId()));
        for (String table : List.of("entity_state", "outbox", "delivery")) assertEquals(1, count(db, table));
    }

    @Test
    void concurrentClaimersReceiveDisjointCommittedLeases() throws Exception {
        var db = database();
        db.transactions().required(() -> {
            for (int i = 1; i <= 40; i++) outbox(db).append(event(db, UUID.randomUUID(), i, VersionVector.empty()), List.of(TARGET));
            return null;
        });
        CountDownLatch start = new CountDownLatch(1);
        var ids = new HashSet<UUID>();
        var tokens = new HashSet<UUID>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<List<DeliveryService.Lease>>>();
            for (int i = 0; i < 4; i++) tasks.add(executor.submit(() -> { await(start); return delivery(db).claim(TARGET, 10, LEASE); }));
            start.countDown();
            for (var task : tasks) {
                var leases = task.get(20, TimeUnit.SECONDS);
                assertEquals(10, leases.size());
                for (var lease : leases) {
                    assertTrue(ids.add(lease.eventId()));
                    assertTrue(tokens.add(lease.leaseToken()));
                    assertEquals(1, lease.attempts());
                    assertEquals(lease.eventId(), lease.event().eventId());
                }
            }
        }
        assertEquals(40, ids.size());
        assertEquals(40, db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.delivery WHERE status='LEASED'", Integer.class));
        assertTrue(delivery(db).claim(TARGET, 1, LEASE).isEmpty());
    }

    @Test
    void lowerSequenceCommittedAfterAHigherSequenceIsStillClaimed() throws Exception {
        var db = database();
        var low = event(db, UUID.randomUUID(), 1, VersionVector.empty());
        var high = event(db, UUID.randomUUID(), 2, VersionVector.empty());
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicLong lowOrder = new AtomicLong();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> db.transactions().required(() -> {
                createBusiness(db, low);
                outbox(db).append(low, List.of(TARGET));
                lowOrder.set(db.runtime().queryForObject("SELECT local_order FROM eatwhat_sync.outbox WHERE event_id=?", Long.class, low.eventId()));
                inserted.countDown();
                await(commit);
                return null;
            }));
            try {
                assertTrue(inserted.await(10, TimeUnit.SECONDS));
                appendBusiness(db, high);
                long highOrder = db.runtime().queryForObject("SELECT local_order FROM eatwhat_sync.outbox WHERE event_id=?", Long.class, high.eventId());
                assertTrue(lowOrder.get() < highOrder);
                var claimed = delivery(db).claim(TARGET, 10, LEASE);
                assertEquals(List.of(high.eventId()), claimed.stream().map(DeliveryService.Lease::eventId).toList());
                assertTrue(delivery(db).acknowledge(claimed.getFirst(), "DURABLY_QUEUED"));
            } finally { commit.countDown(); }
            writer.get(15, TimeUnit.SECONDS);
        }
        var late = delivery(db).claim(TARGET, 10, LEASE);
        assertEquals(List.of(low.eventId()), late.stream().map(DeliveryService.Lease::eventId).toList());
    }

    @Test
    void expiredLeaseCannotAcknowledgeRetryOrQuarantineAndARebuiltServiceRecoversIt() {
        var db = database();
        appendBusiness(db, event(db, UUID.randomUUID(), 1, VersionVector.empty()));
        var old = delivery(db).claim(TARGET, 1, LEASE).getFirst();
        db.admin().update("UPDATE eatwhat_sync.delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=?", old.eventId());
        assertFalse(delivery(db).acknowledge(old, "DURABLY_QUEUED"));
        assertFalse(delivery(db).retry(old, Duration.ZERO, "expired"));
        assertFalse(delivery(db).quarantine(old, "expired"));
        var recoveredService = delivery(db);
        var recovered = recoveredService.claim(TARGET, 1, LEASE).getFirst();
        assertNotEquals(old.leaseToken(), recovered.leaseToken());
        assertEquals(2, recovered.attempts());
        assertFalse(recoveredService.acknowledge(old, "DURABLY_QUEUED"));
        assertFalse(recoveredService.retry(old, Duration.ZERO, "stale"));
        assertFalse(recoveredService.quarantine(old, "stale"));
        assertTrue(recoveredService.retry(recovered, Duration.ZERO, "transient"));
        var next = recoveredService.claim(TARGET, 1, LEASE).getFirst();
        assertEquals(3, next.attempts());
        assertTrue(recoveredService.acknowledge(next, "DURABLY_QUEUED"));
        assertFalse(recoveredService.acknowledge(next, "DURABLY_QUEUED"));
        assertEquals("DURABLY_QUEUED", db.runtime().queryForObject("SELECT receipt_status FROM eatwhat_sync.delivery", String.class));
        assertTrue(recoveredService.claim(TARGET, 1, LEASE).isEmpty());
        assertEquals(1, count(db, "outbox"));
    }

    @Test
    void retryDelayAndQuarantineRetainTheEventWithoutPrematureReclaim() {
        var db = database();
        appendBusiness(db, event(db, UUID.randomUUID(), 1, VersionVector.empty()));
        var lease = delivery(db).claim(TARGET, 1, LEASE).getFirst();
        assertTrue(delivery(db).retry(lease, Duration.ofHours(1), "synthetic-backoff"));
        assertTrue(delivery(db).claim(TARGET, 1, LEASE).isEmpty());
        db.admin().execute("UPDATE eatwhat_sync.delivery SET available_at=clock_timestamp()-interval '1 second'");
        var retried = delivery(db).claim(TARGET, 1, LEASE).getFirst();
        assertTrue(delivery(db).quarantine(retried, "unsupported-receipt"));
        assertTrue(delivery(db).claim(TARGET, 1, LEASE).isEmpty());
        assertEquals("QUARANTINED", db.runtime().queryForObject("SELECT status FROM eatwhat_sync.delivery", String.class));
        assertEquals(1, count(db, "outbox"));
    }

    @Test
    void nonDurableReceiptsNeverAcknowledgeOrReleaseTheLease() {
        var db = database();
        appendBusiness(db, event(db, UUID.randomUUID(), 1, VersionVector.empty()));
        var lease = delivery(db).claim(TARGET, 1, LEASE).getFirst();
        for (String receipt : List.of("REJECTED", "RETRYABLE_ERROR", "UNKNOWN_SCHEMA", "QUARANTINED", "OK")) {
            assertThrows(IllegalArgumentException.class, () -> delivery(db).acknowledge(lease, receipt));
            assertEquals("LEASED", db.runtime().queryForObject("SELECT status FROM eatwhat_sync.delivery", String.class));
            assertEquals(lease.leaseToken(), db.runtime().queryForObject("SELECT lease_token FROM eatwhat_sync.delivery", UUID.class));
        }
        assertTrue(delivery(db).acknowledge(lease, "DURABLY_QUEUED"));
    }

    @Test
    void acknowledgementWaitingForAnUnchangedLockedRowMustRecheckExpiryAfterAcquiringTheLock() throws Exception {
        var db = database();
        appendBusiness(db, event(db, UUID.randomUUID(), 1, VersionVector.empty()));
        var lease = delivery(db).claim(TARGET, 1, Duration.ofSeconds(5)).getFirst();
        try (var blocker = db.dataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT event_id FROM eatwhat_sync.delivery WHERE event_id=? FOR UPDATE")) {
                lock.setObject(1, lease.eventId());
                try (var row = lock.executeQuery()) { assertTrue(row.next()); }
            }
            var acknowledgement = executor.submit(() -> delivery(db).acknowledge(lease, "DURABLY_QUEUED"));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = db.admin().queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                              WHERE a.usename=? AND NOT l.granted AND l.locktype IN ('transactionid','tuple'))
                            """, Boolean.class, db.runtimeUsername());
                    if (waiting) break;
                    Thread.sleep(10);
                }
                assertTrue(waiting, "PostgreSQL must report the actual ungranted lock before advancing to expiry");
                assertTrue(db.admin().queryForObject("SELECT lease_until>clock_timestamp() FROM eatwhat_sync.delivery", Boolean.class),
                        "The acknowledgement must have started waiting before its lease expired");
                try (var expire = blocker.prepareStatement("""
                        SELECT pg_sleep(GREATEST(0,extract(epoch FROM (?::timestamptz-clock_timestamp())))+0.05)
                        """)) {
                    expire.setTimestamp(1, java.sql.Timestamp.from(lease.leaseUntil()));
                    expire.execute();
                }
                blocker.commit(); // Release only the lock: never modify the tuple to trigger EPQ.
                assertFalse(acknowledgement.get(10, TimeUnit.SECONDS));
            } finally { blocker.rollback(); }
        }
        assertEquals("LEASED", db.runtime().queryForObject("SELECT status FROM eatwhat_sync.delivery", String.class));
        assertNull(db.runtime().queryForObject("SELECT acked_at FROM eatwhat_sync.delivery", java.sql.Timestamp.class));
        assertEquals(2, delivery(db).claim(TARGET, 1, LEASE).getFirst().attempts());
    }

    @Test
    void corruptedPersistedHashCannotBeClaimedAndLeaseMutationRollsBack() {
        var db = database();
        var event = event(db, UUID.randomUUID(), 1, VersionVector.empty());
        appendBusiness(db, event);
        // Migration-owner-only damage injection simulates a corrupted restored event record.
        db.admin().execute("ALTER TABLE eatwhat_sync.outbox DISABLE TRIGGER outbox_immutable");
        try { db.admin().update("UPDATE eatwhat_sync.outbox SET envelope_hash=?", "0".repeat(64)); }
        finally { db.admin().execute("ALTER TABLE eatwhat_sync.outbox ENABLE TRIGGER outbox_immutable"); }
        assertThrows(IllegalStateException.class, () -> delivery(db).claim(TARGET, 1, LEASE));
        assertEquals("READY", db.runtime().queryForObject("SELECT status FROM eatwhat_sync.delivery", String.class));
        assertEquals(0, db.runtime().queryForObject("SELECT attempts FROM eatwhat_sync.delivery", Integer.class));
        assertNull(db.runtime().queryForObject("SELECT lease_token FROM eatwhat_sync.delivery", UUID.class));
        db.admin().execute("ALTER TABLE eatwhat_sync.outbox DISABLE TRIGGER outbox_immutable");
        try { db.admin().update("UPDATE eatwhat_sync.outbox SET envelope_hash=?", new EventCodec().encode(event).hash()); }
        finally { db.admin().execute("ALTER TABLE eatwhat_sync.outbox ENABLE TRIGGER outbox_immutable"); }
        assertEquals(event.eventId(), delivery(db).claim(TARGET, 1, LEASE).getFirst().eventId());
    }

    @Test
    void eventIdentityAndHistoryAreProtectedWithTheActualRestrictedRuntimeRole() {
        var db = database();
        appendBusiness(db, event(db, UUID.randomUUID(), 1, VersionVector.empty()));
        for (String sql : List.of(
                "UPDATE eatwhat_sync.outbox SET envelope='{}'::jsonb",
                "UPDATE eatwhat_sync.delivery SET target_site='reassigned'",
                "UPDATE eatwhat_sync.entity_state SET numeric_id=100",
                "DELETE FROM eatwhat_sync.outbox", "TRUNCATE eatwhat_sync.delivery",
                "SELECT setval('eatwhat_sync.outbox_local_order_seq',1,false)")) {
            assertSqlState(db.runtime(), sql, "42501");
        }
        for (String sql : List.of(
                "UPDATE eatwhat_sync.outbox SET envelope='{}'::jsonb",
                "UPDATE eatwhat_sync.delivery SET target_site='reassigned'",
                "DELETE FROM eatwhat_sync.outbox", "TRUNCATE eatwhat_sync.delivery")) {
            assertSqlState(db.admin(), sql, "55000");
        }
        assertEquals(1, count(db, "outbox"));
        assertTrue(delivery(db).acknowledge(delivery(db).claim(TARGET, 1, LEASE).getFirst(), "DURABLY_QUEUED"));
    }

    private EventFoundationTestSupport.Database database() { return EventFoundationTestSupport.createDatabase(POSTGRES); }
    private OutboxService outbox(EventFoundationTestSupport.Database db) {
        return new OutboxService(db.runtime(), db.transactions(), new EventCodec(), db.site());
    }
    private DeliveryService delivery(EventFoundationTestSupport.Database db) {
        return new DeliveryService(db.runtime(), db.transactions(), new EventCodec());
    }
    private SyncEvent event(EventFoundationTestSupport.Database db, UUID gid, long numericId, VersionVector base) {
        return new SyncEvent(UUID.randomUUID(), db.site().getId(), db.site().getInstanceId(), "synthetic.counter", gid,
                numericId, "counter.increment", 1, base, base.increment(db.site().getId()), List.of(), null,
                "command-" + UUID.randomUUID(), UUID.randomUUID(), "{\"increment\":1}");
    }
    private void appendBusiness(EventFoundationTestSupport.Database db, SyncEvent event) {
        db.transactions().required(() -> { createBusiness(db, event); outbox(db).append(event, List.of(TARGET)); return null; });
    }
    private void createBusiness(EventFoundationTestSupport.Database db, SyncEvent event) {
        db.runtime().update("INSERT INTO business_counter(gid,numeric_id,value) VALUES (?,?,1)", event.aggregateId(), event.numericId());
    }
    private int count(EventFoundationTestSupport.Database db, String table) {
        return db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync." + table, Integer.class);
    }
    private long businessValue(EventFoundationTestSupport.Database db, UUID gid) {
        return db.runtime().queryForObject("SELECT value FROM business_counter WHERE gid=?", Long.class, gid);
    }
    private VersionVector vector(EventFoundationTestSupport.Database db, UUID gid) {
        return EventCodec.vectorFromJson(db.runtime().queryForObject(
                "SELECT applied_vector::text FROM eatwhat_sync.entity_state WHERE aggregate_id=?", String.class, gid));
    }
    private void assertEmpty(EventFoundationTestSupport.Database db) {
        for (String table : List.of("outbox", "delivery", "entity_state")) assertEquals(0, count(db, table));
        assertEquals(0, db.runtime().queryForObject("SELECT count(*) FROM business_counter", Integer.class));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic concurrency barrier timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private void assertSqlState(JdbcTemplate jdbc, String sql, String state) {
        DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.execute(sql));
        assertEquals(state, ((SQLException) failure.getMostSpecificCause()).getSQLState());
    }
}
