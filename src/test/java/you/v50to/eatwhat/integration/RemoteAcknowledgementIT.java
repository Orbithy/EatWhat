package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.sync.DeliveryService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.SyntheticEventPolicy;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Genuine restricted-role PostgreSQL transactions; no HTTP or database mock decides settlement. */
class RemoteAcknowledgementIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("remote-acknowledgement");
    private static final String TARGET = "external-test";
    private static final Duration LEASE = Duration.ofMinutes(1);
    private final EventCodec codec = new EventCodec();

    @BeforeAll static void start() { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void hashTargetTokenAndReceiptMustMatchBeforeAnyMutation() {
        var db = database();
        var event = append(db, SyntheticEventPolicy.AGGREGATE_TYPE, 1);
        var delivery = delivery(db);
        var lease = delivery.claim(TARGET, 1, LEASE).getFirst();
        String hash = codec.encode(event).hash();
        assertEquals("REJECTED", delivery.settleRemote(event.eventId(), TARGET, lease.leaseToken(), "0".repeat(64), "APPLIED", Duration.ZERO));
        assertEquals("REJECTED", delivery.settleRemote(event.eventId(), "wrong-target", lease.leaseToken(), hash, "APPLIED", Duration.ZERO));
        assertEquals("REJECTED", delivery.settleRemote(UUID.randomUUID(), TARGET, lease.leaseToken(), hash, "APPLIED", Duration.ZERO));
        assertEquals("STALE_LEASE", delivery.settleRemote(event.eventId(), TARGET, UUID.randomUUID(), hash, "APPLIED", Duration.ZERO));
        for (String status : List.of("OK", "ACKED", "ALREADY_ACKED", "STALE_LEASE", "QUARANTINED")) {
            assertEquals("REJECTED", delivery.settleRemote(event.eventId(), TARGET, lease.leaseToken(), hash, status, Duration.ZERO));
        }
        assertEquals("LEASED", state(db, event));
        assertEquals(lease.leaseToken(), db.runtime().queryForObject("SELECT lease_token FROM eatwhat_sync.delivery", UUID.class));
        assertNull(db.runtime().queryForObject("SELECT acked_at FROM eatwhat_sync.delivery", Timestamp.class));
        assertEquals("ACKED", delivery.settleRemote(event.eventId(), TARGET, lease.leaseToken(), hash, "DURABLY_QUEUED", Duration.ZERO));
    }

    @Test
    void lostAcknowledgementResponseCanReadCommittedTerminalFactWithoutRewritingIt() {
        var db = database();
        var event = append(db, SyntheticEventPolicy.AGGREGATE_TYPE, 1);
        var lease = delivery(db).claim(TARGET, 1, LEASE).getFirst();
        String hash = codec.encode(event).hash();
        assertEquals("ACKED", delivery(db).settleRemote(event.eventId(), TARGET, lease.leaseToken(), hash, "DURABLY_QUEUED", Duration.ZERO));
        Timestamp committedAt = db.runtime().queryForObject("SELECT acked_at FROM eatwhat_sync.delivery", Timestamp.class);
        // Force every attempted write to fail: ALREADY_ACKED must remain a pure observation.
        db.admin().execute("""
                CREATE FUNCTION public.reject_remote_ack_write() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic unexpected terminal mutation'; END $$
                """);
        db.admin().execute("""
                CREATE TRIGGER reject_remote_ack_write BEFORE UPDATE ON eatwhat_sync.delivery
                FOR EACH ROW EXECUTE FUNCTION public.reject_remote_ack_write()
                """);
        for (String receipt : List.of("APPLIED", "ALREADY_APPLIED", "DURABLY_QUEUED", "CONFLICT")) {
            assertEquals("ALREADY_ACKED", delivery(db).settleRemote(event.eventId(), TARGET, UUID.randomUUID(), hash, receipt, Duration.ZERO));
        }
        assertEquals("REJECTED", delivery(db).settleRemote(event.eventId(), TARGET, lease.leaseToken(), "0".repeat(64), "APPLIED", Duration.ZERO));
        assertEquals("STALE_LEASE", delivery(db).settleRemote(event.eventId(), TARGET, lease.leaseToken(), hash, "REJECTED", Duration.ZERO));
        assertEquals("STALE_LEASE", delivery(db).settleRemote(event.eventId(), TARGET, lease.leaseToken(), hash, "RETRYABLE_ERROR", Duration.ZERO));
        assertEquals(committedAt, db.runtime().queryForObject("SELECT acked_at FROM eatwhat_sync.delivery", Timestamp.class));
        assertEquals("DURABLY_QUEUED", db.runtime().queryForObject("SELECT receipt_status FROM eatwhat_sync.delivery", String.class));
        assertNull(db.runtime().queryForObject("SELECT lease_token FROM eatwhat_sync.delivery", UUID.class));
        assertTrue(delivery(db).claim(TARGET, 1, LEASE).isEmpty());
    }

    @Test
    void retryQuarantineAndExpiredOrReplacedTokensHaveDistinctDurableResults() {
        var db = database();
        var event = append(db, SyntheticEventPolicy.AGGREGATE_TYPE, 1);
        var delivery = delivery(db);
        var old = delivery.claim(TARGET, 1, LEASE).getFirst();
        String hash = codec.encode(event).hash();
        db.admin().execute("UPDATE eatwhat_sync.delivery SET lease_until=clock_timestamp()-interval '1 second'");
        for (String receipt : List.of("APPLIED", "REJECTED", "RETRYABLE_ERROR")) {
            assertEquals("STALE_LEASE", delivery.settleRemote(event.eventId(), TARGET, old.leaseToken(), hash, receipt, Duration.ZERO));
        }
        assertEquals("LEASED", state(db, event));
        var renewed = delivery.claim(TARGET, 1, LEASE).getFirst();
        assertNotEquals(old.leaseToken(), renewed.leaseToken());
        assertEquals("STALE_LEASE", delivery.settleRemote(event.eventId(), TARGET, old.leaseToken(), hash, "APPLIED", Duration.ZERO));
        assertEquals("RETRIED", delivery.settleRemote(event.eventId(), TARGET, renewed.leaseToken(), hash, "RETRYABLE_ERROR", Duration.ofHours(1)));
        assertEquals("READY", state(db, event));
        assertTrue(delivery.claim(TARGET, 1, LEASE).isEmpty());
        db.admin().execute("UPDATE eatwhat_sync.delivery SET available_at=clock_timestamp()-interval '1 second'");
        var retried = delivery(db).claim(TARGET, 1, LEASE).getFirst();
        assertEquals(3, retried.attempts());
        assertEquals("QUARANTINED", delivery(db).settleRemote(event.eventId(), TARGET, retried.leaseToken(), hash, "REJECTED", Duration.ZERO));
        assertEquals("QUARANTINED", state(db, event));
        assertEquals("REMOTE_REJECTED", db.runtime().queryForObject("SELECT last_error FROM eatwhat_sync.delivery", String.class));
        assertTrue(delivery(db).claim(TARGET, 1, LEASE).isEmpty());
        assertEquals(1, db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox", Integer.class));
    }

    @Test
    void aggregateFilterIsAppliedBeforeClaimingAndLeavesBusinessEventsUntouched() throws Exception {
        var db = database();
        var business = append(db, "users", 1);
        var synthetic = append(db, SyntheticEventPolicy.AGGREGATE_TYPE, 2);
        try (var blocker = db.dataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT event_id FROM eatwhat_sync.delivery WHERE event_id=? FOR UPDATE")) {
                statement.setObject(1, business.eventId());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            var leases = delivery(db).claimForAggregate(TARGET, 8, LEASE, SyntheticEventPolicy.AGGREGATE_TYPE);
            assertEquals(List.of(synthetic.eventId()), leases.stream().map(DeliveryService.Lease::eventId).toList());
            blocker.rollback();
        }
        assertEquals("READY", state(db, business));
        assertEquals(0, db.runtime().queryForObject("SELECT attempts FROM eatwhat_sync.delivery WHERE event_id=?", Integer.class, business.eventId()));
        assertTrue(delivery(db).claimForAggregate(TARGET, 8, LEASE, SyntheticEventPolicy.AGGREGATE_TYPE).isEmpty());
        assertEquals(business.eventId(), delivery(db).claim(TARGET, 8, LEASE).getFirst().eventId());
    }

    @Test
    void concurrentDuplicateAcknowledgementsMutateOnceAndAllOthersObserveTheSameDurableResult() throws Exception {
        var db = database();
        var event = append(db, SyntheticEventPolicy.AGGREGATE_TYPE, 1);
        var lease = delivery(db).claim(TARGET, 1, LEASE).getFirst();
        String hash = codec.encode(event).hash();
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic barrier timeout");
                    return delivery(db).settleRemote(event.eventId(), TARGET, lease.leaseToken(), hash, "APPLIED", Duration.ZERO);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<String> statuses = new ArrayList<>();
            for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter("ACKED"::equals).count());
            assertEquals(3, statuses.stream().filter("ALREADY_ACKED"::equals).count());
        }
        assertEquals("ACKED", state(db, event));
        assertEquals(1, db.runtime().queryForObject("SELECT attempts FROM eatwhat_sync.delivery", Integer.class));
    }

    @Test
    void databaseFailureRollsBackSettlementAndTheSameLeaseCanSucceedAfterRecovery() {
        var db = database();
        var event = append(db, SyntheticEventPolicy.AGGREGATE_TYPE, 1);
        var lease = delivery(db).claim(TARGET, 1, LEASE).getFirst();
        String hash = codec.encode(event).hash();
        db.admin().execute("""
                CREATE FUNCTION public.fail_remote_settlement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic settlement outage'; END $$
                """);
        db.admin().execute("""
                CREATE TRIGGER fail_remote_settlement AFTER UPDATE ON eatwhat_sync.delivery
                FOR EACH ROW EXECUTE FUNCTION public.fail_remote_settlement()
                """);
        assertThrows(DataAccessException.class, () -> delivery(db).settleRemote(event.eventId(), TARGET, lease.leaseToken(), hash, "APPLIED", Duration.ZERO));
        assertEquals("LEASED", state(db, event));
        assertEquals(lease.leaseToken(), db.runtime().queryForObject("SELECT lease_token FROM eatwhat_sync.delivery", UUID.class));
        assertNull(db.runtime().queryForObject("SELECT acked_at FROM eatwhat_sync.delivery", Timestamp.class));
        db.admin().execute("DROP TRIGGER fail_remote_settlement ON eatwhat_sync.delivery");
        assertEquals("ACKED", delivery(db).settleRemote(event.eventId(), TARGET, lease.leaseToken(), hash, "APPLIED", Duration.ZERO));
    }

    @Test
    void remoteAckThatWaitsForAnUnchangedRowLockCannotPassAnExpiredFence() throws Exception {
        var db = database();
        var event = append(db, SyntheticEventPolicy.AGGREGATE_TYPE, 1);
        var lease = delivery(db).claim(TARGET, 1, Duration.ofSeconds(5)).getFirst();
        String hash = codec.encode(event).hash();
        try (var blocker = db.dataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT event_id FROM eatwhat_sync.delivery WHERE event_id=? FOR UPDATE")) {
                lock.setObject(1, event.eventId());
                try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
            }
            var ack = executor.submit(() -> delivery(db).settleRemote(event.eventId(), TARGET, lease.leaseToken(), hash, "APPLIED", Duration.ZERO));
            try {
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline) {
                    waiting = db.admin().queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                            WHERE a.usename=? AND NOT l.granted AND l.locktype IN ('transactionid','tuple'))
                            """, Boolean.class, db.runtimeUsername());
                    if (waiting) break;
                    Thread.sleep(10);
                }
                assertTrue(waiting, "Must observe the PostgreSQL wait, not guess from a client sleep");
                assertTrue(db.admin().queryForObject("SELECT lease_until>clock_timestamp() FROM eatwhat_sync.delivery", Boolean.class));
                try (var expire = blocker.prepareStatement("SELECT pg_sleep(GREATEST(0,extract(epoch FROM (?::timestamptz-clock_timestamp())))+0.05)")) {
                    expire.setTimestamp(1, Timestamp.from(lease.leaseUntil()));
                    expire.execute();
                }
                blocker.commit(); // No UPDATE: force fresh-clock correctness after a pure lock wait.
                assertEquals("STALE_LEASE", ack.get(10, TimeUnit.SECONDS));
            } finally { blocker.rollback(); }
        }
        assertEquals("LEASED", state(db, event));
        assertNull(db.runtime().queryForObject("SELECT acked_at FROM eatwhat_sync.delivery", Timestamp.class));
        assertEquals(2, delivery(db).claim(TARGET, 1, LEASE).getFirst().attempts());
    }

    private EventFoundationTestSupport.Database database() { return EventFoundationTestSupport.createDatabase(POSTGRES); }
    private DeliveryService delivery(EventFoundationTestSupport.Database db) {
        return new DeliveryService(db.runtime(), db.transactions(), codec);
    }
    private SyncEvent append(EventFoundationTestSupport.Database db, String aggregateType, long numericId) {
        var event = new SyncEvent(UUID.randomUUID(), db.site().getId(), db.site().getInstanceId(), aggregateType,
                UUID.randomUUID(), numericId, SyntheticEventPolicy.EVENT_TYPE, 1, VersionVector.empty(),
                VersionVector.empty().increment(db.site().getId()), List.of(), null, "synthetic-" + UUID.randomUUID(),
                UUID.randomUUID(), "{\"delta\":1}");
        db.transactions().required(() -> {
            db.runtime().update("INSERT INTO business_counter(gid,numeric_id,value) VALUES (?,?,1)", event.aggregateId(), numericId);
            new OutboxService(db.runtime(), db.transactions(), codec, db.site()).append(event, List.of(TARGET));
            return null;
        });
        return event;
    }
    private String state(EventFoundationTestSupport.Database db, SyncEvent event) {
        return db.runtime().queryForObject("SELECT status FROM eatwhat_sync.delivery WHERE event_id=? AND target_site=?",
                String.class, event.eventId(), TARGET);
    }
}
