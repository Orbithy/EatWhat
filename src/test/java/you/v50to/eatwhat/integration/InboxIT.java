package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.sync.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static you.v50to.eatwhat.sync.InboxService.InboxStatus.*;
import static you.v50to.eatwhat.sync.InboxService.ReceiptStatus.*;

/** Real PostgreSQL Inbox receipts, fenced application and synthetic same-database domain effects. */
class InboxIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("inbox");
    private static final String SOURCE = "external-test";
    private static final UUID SOURCE_INSTANCE = UUID.fromString("e3adea6e-1a20-4f61-8dfd-8952c81d91a2");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final long NUMERIC_ID = 1_000_000_002L;
    private final EventCodec codec = new EventCodec();

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void tenDuplicateReceiptsBeforeAndAfterApplicationProduceExactlyOneBusinessEffect() {
        var db = database();
        AtomicInteger attempts = new AtomicInteger();
        var service = service(db, handler(db, 1, attempts, ignored -> {}));
        SyncEvent event = event(UUID.randomUUID(), NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        for (int retry = 0; retry < 10; retry++) {
            var receipt = service.receive(event, SOURCE);
            assertEquals(RECEIVED, receipt.status());
            assertEquals(DURABLY_QUEUED, receipt.receiptStatus());
        }
        assertEquals(1, count(db, "inbox"));
        var applied = service.process(onlyLease(service));
        assertEquals(InboxService.InboxStatus.APPLIED, applied.status());
        assertEquals(InboxService.ReceiptStatus.APPLIED, applied.receiptStatus());
        for (int retry = 0; retry < 10; retry++) {
            assertEquals(ALREADY_APPLIED, service.receive(event, SOURCE).receiptStatus());
        }
        assertEquals(1, attempts.get());
        assertEquals(1, value(db, event.aggregateId()));
        assertEquals(event.nextVersion(), vector(db, event.aggregateId()));
        assertEquals(0, count(db, "outbox"));
        assertTrue(service.claim(10, LEASE).isEmpty());
        assertFalse(service.retryWaiting(event.eventId()));
        assertFalse(InboundApplicationScope.isActive());
    }

    @Test
    void wholeEnvelopeChangesAreRejectedEvenWhenPayloadAndEventIdAreUnchanged() {
        var db = database();
        var service = service(db, handler(db, 1, new AtomicInteger(), ignored -> {}));
        SyncEvent original = event(UUID.randomUUID(), NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        service.receive(original, SOURCE);
        SyncEvent otherAggregate = new SyncEvent(original.eventId(), original.originSite(), original.originInstanceId(),
                original.aggregateType(), UUID.randomUUID(), original.numericId(), original.eventType(), original.schemaVersion(),
                original.baseVersion(), original.nextVersion(), original.dependencies(), original.actorId(),
                original.commandKey(), original.correlationId(), original.payloadJson());
        assertThrows(IllegalArgumentException.class, () -> service.receive(otherAggregate, SOURCE));
        SyncEvent wrongPeer = event(UUID.randomUUID(), NUMERIC_ID + 2, SOURCE, VersionVector.empty(), 1, List.of());
        assertThrows(IllegalArgumentException.class, () -> service.receive(wrongPeer, "campus-test"));
        assertEquals(1, count(db, "inbox"));
        assertEquals(codec.encode(original).hash(), db.runtime().queryForObject(
                "SELECT envelope_hash FROM eatwhat_sync.inbox WHERE event_id=?", String.class, original.eventId()));
        assertEquals(original, codec.decode(db.runtime().queryForObject(
                "SELECT envelope::text FROM eatwhat_sync.inbox WHERE event_id=?", String.class, original.eventId())));
    }

    @Test
    void concurrentReceiptAndApplicationDeduplicateUsingDatabaseLocks() throws Exception {
        var db = database();
        AtomicInteger attempts = new AtomicInteger();
        var first = service(db, handler(db, 1, attempts, ignored -> {}));
        var second = service(db, handler(db, 1, attempts, ignored -> {}));
        SyncEvent event = event(UUID.randomUUID(), NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        var receipts = concurrently(() -> first.receive(event, SOURCE), () -> second.receive(event, SOURCE));
        assertTrue(receipts.stream().allMatch(receipt -> receipt.status() == RECEIVED));
        assertEquals(1, count(db, "inbox"));
        var lease = onlyLease(first);
        var results = concurrently(() -> processOutcome(first, lease), () -> processOutcome(second, lease));
        assertEquals(Set.of("applied", "stale"), new HashSet<>(results));
        assertEquals(1, value(db, event.aggregateId()));
        assertEquals(1, attempts.get());
        assertEquals(1, count(db, "entity_state"));
    }

    @Test
    void simultaneousClaimersReceiveDisjointCommittedBatches() throws Exception {
        var db = database();
        var service = service(db, handler(db, 1, new AtomicInteger(), ignored -> {}));
        Set<UUID> events = new HashSet<>();
        for (int index = 0; index < 8; index++) {
            var event = event(UUID.randomUUID(), NUMERIC_ID + index * 2L, SOURCE, VersionVector.empty(), 1, List.of());
            service.receive(event, SOURCE);
            events.add(event.eventId());
        }
        var batches = concurrently(() -> service.claim(4, LEASE), () -> service.claim(4, LEASE));
        Set<UUID> leased = new HashSet<>();
        Set<UUID> tokens = new HashSet<>();
        for (var batch : batches) {
            assertEquals(4, batch.size());
            for (var lease : batch) {
                assertTrue(leased.add(lease.eventId()));
                assertTrue(tokens.add(lease.token()));
            }
        }
        assertEquals(events, leased);
        assertTrue(service.claim(10, LEASE).isEmpty());
        assertEquals(8, db.runtime().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.inbox WHERE status='PROCESSING' AND attempts=1", Integer.class));
    }

    @Test
    void competingFirstEventsSerializeInitialStateAndKeepTheLosingBranchAsAConflict() throws Exception {
        var db = database();
        var service = service(db, handler(db, 1, new AtomicInteger(), ignored -> {}));
        UUID aggregate = UUID.randomUUID();
        var external = event(aggregate, NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        var campus = event(aggregate, NUMERIC_ID, "campus-test", VersionVector.empty(), 1, List.of());
        service.receive(external, SOURCE);
        service.receive(campus, "campus-test");
        var leases = service.claim(2, LEASE);
        assertEquals(2, leases.size());
        var results = concurrently(() -> service.process(leases.get(0)), () -> service.process(leases.get(1)));
        assertEquals(Set.of(InboxService.InboxStatus.APPLIED, InboxService.InboxStatus.CONFLICT),
                results.stream().map(InboxService.Receipt::status).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, value(db, aggregate));
        assertEquals(1, count(db, "entity_state"));
        assertEquals(1, count(db, "conflict"));
        UUID applied = db.runtime().queryForObject("SELECT event_id FROM eatwhat_sync.inbox WHERE status='APPLIED'", UUID.class);
        assertEquals(applied, db.runtime().queryForObject("SELECT last_local_event_id FROM eatwhat_sync.conflict", UUID.class));
        assertEquals(applied.equals(external.eventId()) ? external.nextVersion() : campus.nextVersion(), vector(db, aggregate));
    }

    @Test
    void handlerFailureRollsBackEffectsAndStateButSavedInboxCanBeRecoveredWithANewService() {
        var db = database();
        AtomicInteger attempts = new AtomicInteger();
        var broken = service(db, handler(db, 1, attempts, ignored -> {
            throw new IllegalStateException("synthetic failure after business SQL");
        }));
        var event = event(UUID.randomUUID(), NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        broken.receive(event, SOURCE);
        var abandoned = onlyLease(broken);
        assertThrows(IllegalStateException.class, () -> broken.process(abandoned));
        assertEquals(1, attempts.get());
        assertEquals(0, businessCount(db));
        assertEquals(0, count(db, "entity_state"));
        assertEquals("PROCESSING", status(db, event.eventId()));
        assertFalse(InboundApplicationScope.isActive());

        expire(db, event.eventId());
        var recovered = service(db, handler(db, 1, attempts, ignored -> {}));
        var newLease = onlyLease(recovered);
        assertNotEquals(abandoned.token(), newLease.token());
        assertThrows(InboxService.StaleLeaseException.class, () -> broken.process(abandoned));
        assertEquals(InboxService.InboxStatus.APPLIED, recovered.process(newLease).status());
        assertEquals(1, value(db, event.aggregateId()));
        assertEquals(2, attempts.get());
        assertEquals(ALREADY_APPLIED, recovered.receive(event, SOURCE).receiptStatus());
    }

    @Test
    void leaseExpiringAfterBusinessSqlRollsBackEffectsAndVersionAtTheFinalFence() {
        var db = database();
        AtomicInteger attempts = new AtomicInteger();
        var slow = service(db, handler(db, 1, attempts, event -> {
            // Wait precisely past the persisted DB-clock lease, rather than guessing a thread sleep.
            db.runtime().queryForObject("""
                    SELECT pg_sleep(GREATEST(EXTRACT(EPOCH FROM (lease_until-clock_timestamp())),0)::double precision+0.05)
                    FROM eatwhat_sync.inbox WHERE event_id=?
                    """, Object.class, event.eventId());
        }));
        var event = event(UUID.randomUUID(), NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        slow.receive(event, SOURCE);
        var lease = slow.claim(1, Duration.ofSeconds(2)).getFirst();
        assertThrows(InboxService.StaleLeaseException.class, () -> slow.process(lease));
        assertEquals(1, attempts.get(), "The lease must expire after the business SQL has actually executed");
        assertEquals(0, businessCount(db));
        assertEquals(0, count(db, "entity_state"));
        assertEquals("PROCESSING", status(db, event.eventId()));
        assertFalse(InboundApplicationScope.isActive());
        var recovered = service(db, handler(db, 1, attempts, ignored -> {}));
        recovered.process(onlyLease(recovered));
        assertEquals(1, value(db, event.aggregateId()));
    }

    @Test
    void missingBusinessDependencyWaitsWithoutAdvancingTheVectorAndExplicitRetryAppliesLater() {
        var db = database();
        var service = service(db, handler(db, 1, new AtomicInteger(), ignored -> {}));
        UUID dependency = UUID.randomUUID();
        var event = event(UUID.randomUUID(), NUMERIC_ID, SOURCE, VersionVector.empty(), 1,
                List.of(new SyncEvent.Dependency("counter", dependency)));
        service.receive(event, SOURCE);
        assertEquals(WAITING_DEPENDENCY, service.process(onlyLease(service)).status());
        assertEquals(VersionVector.empty(), vector(db, event.aggregateId()));
        assertEquals(0, businessCount(db));
        assertEquals(1, count(db, "pending"));
        assertEquals(1, db.runtime().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.pending WHERE event_id=? AND resolved_at IS NULL", Integer.class, event.eventId()));
        var repeated = service.receive(event, SOURCE);
        assertEquals(WAITING_DEPENDENCY, repeated.status());
        assertEquals(DURABLY_QUEUED, repeated.receiptStatus());
        assertTrue(service.claim(1, LEASE).isEmpty());

        db.runtime().update("INSERT INTO public.business_counter(gid,numeric_id,value) VALUES (?,?,0)", dependency, NUMERIC_ID + 2);
        assertTrue(service.retryWaiting(event.eventId()));
        service.process(onlyLease(service));
        assertEquals(1, value(db, event.aggregateId()));
        assertEquals(event.nextVersion(), vector(db, event.aggregateId()));
        assertEquals(1, count(db, "pending"), "Resolved dependency evidence is retained");
        assertEquals(1, db.runtime().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.pending WHERE event_id=? AND resolved_at IS NOT NULL", Integer.class, event.eventId()));
        assertFalse(service.retryWaiting(event.eventId()));
    }

    @Test
    void predecessorArrivingLaterUnblocksTheSameDurableEventWithoutSkippingItsFirstEffect() {
        var db = database();
        var service = service(db, handler(db, 1, new AtomicInteger(), ignored -> {}));
        UUID aggregate = UUID.randomUUID();
        var first = event(aggregate, NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        var second = event(aggregate, NUMERIC_ID, SOURCE, first.nextVersion(), 1, List.of());
        service.receive(second, SOURCE);
        assertEquals(WAITING_DEPENDENCY, service.process(onlyLease(service)).status());
        assertEquals("MISSING_PREDECESSOR", db.runtime().queryForObject(
                "SELECT reason FROM eatwhat_sync.pending WHERE event_id=?", String.class, second.eventId()));
        service.receive(first, SOURCE);
        service.process(onlyLease(service));
        assertEquals(1, value(db, aggregate));
        assertTrue(service.retryWaiting(second.eventId()));
        service.process(onlyLease(service));
        assertEquals(2, value(db, aggregate));
        assertEquals(second.nextVersion(), vector(db, aggregate));
    }

    @Test
    void unsupportedVersionIsDurablyQuarantinedUntilAnExplicitMatchingHandlerIsInstalled() {
        var db = database();
        var unsupported = service(db);
        var event = event(UUID.randomUUID(), NUMERIC_ID, SOURCE, VersionVector.empty(), 2, List.of());
        assertEquals(QUARANTINED, unsupported.receive(event, SOURCE).status());
        assertEquals(QUARANTINED, unsupported.receive(event, SOURCE).status());
        assertTrue(unsupported.claim(10, LEASE).isEmpty());
        assertFalse(unsupported.retryWaiting(event.eventId()));
        assertEquals(0, count(db, "entity_state"));
        assertEquals(event, codec.decode(db.runtime().queryForObject(
                "SELECT envelope::text FROM eatwhat_sync.inbox WHERE event_id=?", String.class, event.eventId())));
        var currentVersionOnly = service(db, handler(db, 1, new AtomicInteger(), ignored -> {}));
        assertFalse(currentVersionOnly.retryWaiting(event.eventId()));
        var upgraded = service(db, handler(db, 2, new AtomicInteger(), ignored -> {}));
        assertTrue(upgraded.retryWaiting(event.eventId()));
        assertEquals(InboxService.InboxStatus.APPLIED, upgraded.process(onlyLease(upgraded)).status());
        assertEquals(1, value(db, event.aggregateId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"campus-test", "external-test"})
    void concurrentAndUnprovenStaleEventsPreserveBothBranchesWithoutAdvancingAppliedState(String secondOrigin) {
        var db = database();
        var service = service(db, handler(db, 1, new AtomicInteger(), ignored -> {}));
        UUID aggregate = UUID.randomUUID();
        var first = event(aggregate, NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        service.receive(first, SOURCE);
        service.process(onlyLease(service));
        var concurrentOrStale = event(aggregate, NUMERIC_ID, secondOrigin, VersionVector.empty(), 1, List.of());
        service.receive(concurrentOrStale, secondOrigin);
        assertEquals(InboxService.InboxStatus.CONFLICT, service.process(onlyLease(service)).status());
        assertEquals(1, value(db, aggregate));
        assertEquals(first.nextVersion(), vector(db, aggregate));
        assertEquals(1, count(db, "conflict"));
        assertEquals(first, codec.decode(db.runtime().queryForObject(
                "SELECT current_envelope::text FROM eatwhat_sync.conflict WHERE event_id=?", String.class, concurrentOrStale.eventId())));
        assertEquals(concurrentOrStale, codec.decode(db.runtime().queryForObject(
                "SELECT incoming_envelope::text FROM eatwhat_sync.conflict WHERE event_id=?", String.class, concurrentOrStale.eventId())));
        assertEquals(first.eventId(), db.runtime().queryForObject(
                "SELECT last_local_event_id FROM eatwhat_sync.conflict WHERE event_id=?", UUID.class, concurrentOrStale.eventId()));
        assertEquals(InboxService.ReceiptStatus.CONFLICT, service.receive(concurrentOrStale, secondOrigin).receiptStatus());
        assertFalse(service.retryWaiting(concurrentOrStale.eventId()));
        var successor = event(aggregate, NUMERIC_ID, SOURCE, first.nextVersion(), 1, List.of());
        service.receive(successor, SOURCE);
        assertEquals(WAITING_DEPENDENCY, service.process(onlyLease(service)).status());
        assertEquals("AGGREGATE_CONFLICTED", db.runtime().queryForObject(
                "SELECT reason FROM eatwhat_sync.pending WHERE event_id=?", String.class, successor.eventId()));
        assertEquals(first.nextVersion(), vector(db, aggregate));
    }

    @Test
    void numericAndGlobalIdentityDisagreementCannotApplyAnEventToAnotherBusinessObject() {
        var db = database();
        var service = service(db, handler(db, 1, new AtomicInteger(), ignored -> {}));
        var first = event(UUID.randomUUID(), NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        service.receive(first, SOURCE);
        service.process(onlyLease(service));
        var changedNumeric = event(first.aggregateId(), NUMERIC_ID + 2, SOURCE, first.nextVersion(), 1, List.of());
        service.receive(changedNumeric, SOURCE);
        assertThrows(IllegalStateException.class, () -> service.process(onlyLease(service)));
        var changedGlobal = event(UUID.randomUUID(), NUMERIC_ID, SOURCE, VersionVector.empty(), 1, List.of());
        service.receive(changedGlobal, SOURCE);
        IllegalStateException collision = assertThrows(IllegalStateException.class,
                () -> service.process(onlyLease(service)));
        assertTrue(collision.getMessage().contains("numeric identity is already assigned"));
        assertEquals("PROCESSING", status(db, changedGlobal.eventId()));
        assertEquals(1, db.runtime().queryForObject("""
                SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? AND applied_at IS NULL
                """, Integer.class, changedGlobal.eventId()));
        assertEquals(1, db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.inbox WHERE status='APPLIED'", Integer.class));
        assertEquals(1, count(db, "entity_state"));
        assertEquals(0, count(db, "conflict"));
        assertEquals(1, businessCount(db));
        assertEquals(1, value(db, first.aggregateId()));
        assertEquals(first.nextVersion(), vector(db, first.aggregateId()));
    }

    @Test
    void anUnprovenCurrentBranchCannotBeRecordedAsACompleteConflict() {
        var db = database();
        var service = service(db, handler(db, 1, new AtomicInteger(), ignored -> {}));
        UUID aggregate = UUID.randomUUID();
        var current = VersionVector.empty().increment(SOURCE);
        // A deliberately corrupt fixture has a vector but no retained local or applied incoming event.
        db.runtime().update("""
                INSERT INTO eatwhat_sync.entity_state(aggregate_type,aggregate_id,numeric_id,applied_vector,last_event_id)
                VALUES ('counter',?,?,?::jsonb,?)
                """, aggregate, NUMERIC_ID, EventCodec.vectorJson(current), UUID.randomUUID());
        var event = event(aggregate, NUMERIC_ID, "campus-test", VersionVector.empty(), 1, List.of());
        service.receive(event, "campus-test");
        assertThrows(IllegalStateException.class, () -> service.process(onlyLease(service)));
        assertEquals("PROCESSING", status(db, event.eventId()));
        assertEquals(0, count(db, "conflict"));
        assertEquals(0, businessCount(db));
        assertEquals(current, vector(db, aggregate));
        assertFalse(db.runtime().queryForObject("SELECT conflicted FROM eatwhat_sync.entity_state", Boolean.class));
    }

    private EventFoundationTestSupport.Database database() {
        return EventFoundationTestSupport.createDatabase(POSTGRES);
    }

    private InboxService service(EventFoundationTestSupport.Database db, EventHandler... handlers) {
        return new InboxService(db.runtime(), new SyncTransactions(db.runtime(), db.transactionManager()), codec,
                new EventHandlerRegistry(List.of(handlers)));
    }

    private EventHandler handler(EventFoundationTestSupport.Database db, int schema, AtomicInteger attempts,
                                 Consumer<SyncEvent> afterBusiness) {
        return new EventHandler() {
            @Override public String aggregateType() { return "counter"; }
            @Override public String eventType() { return "increment"; }
            @Override public int schemaVersion() { return schema; }

            @Override
            public boolean dependenciesSatisfied(SyncEvent event) {
                assertTrue(InboundApplicationScope.isActive());
                return event.dependencies().stream().allMatch(dependency -> dependency.aggregateType().equals("counter")
                        && db.runtime().queryForObject("SELECT count(*) FROM public.business_counter WHERE gid=?",
                        Integer.class, dependency.aggregateId()) == 1);
            }

            @Override
            public void apply(SyncEvent event) {
                assertTrue(InboundApplicationScope.isActive());
                attempts.incrementAndGet();
                int changed = db.runtime().update("""
                        INSERT INTO public.business_counter(gid,numeric_id,value) VALUES (?,?,1)
                        ON CONFLICT(gid) DO UPDATE SET value=public.business_counter.value+1
                        WHERE public.business_counter.numeric_id=EXCLUDED.numeric_id
                        """, event.aggregateId(), event.numericId());
                assertEquals(1, changed);
                afterBusiness.accept(event);
            }
        };
    }

    private SyncEvent event(UUID aggregate, long numeric, String origin, VersionVector base, int schema,
                            List<SyncEvent.Dependency> dependencies) {
        return new SyncEvent(UUID.randomUUID(), origin, SOURCE_INSTANCE, "counter", aggregate, numeric,
                "increment", schema, base, base.increment(origin), dependencies, null,
                "synthetic-command-" + UUID.randomUUID(), UUID.randomUUID(), "{\"delta\":1}");
    }

    private InboxService.Lease onlyLease(InboxService service) {
        List<InboxService.Lease> leases = service.claim(1, LEASE);
        assertEquals(1, leases.size());
        return leases.getFirst();
    }

    private String processOutcome(InboxService service, InboxService.Lease lease) {
        try {
            service.process(lease);
            return "applied";
        } catch (InboxService.StaleLeaseException expected) {
            return "stale";
        }
    }

    private void expire(EventFoundationTestSupport.Database db, UUID eventId) {
        db.admin().update("UPDATE eatwhat_sync.inbox SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=?", eventId);
    }

    private long value(EventFoundationTestSupport.Database db, UUID aggregate) {
        return db.runtime().queryForObject("SELECT value FROM public.business_counter WHERE gid=?", Long.class, aggregate);
    }

    private VersionVector vector(EventFoundationTestSupport.Database db, UUID aggregate) {
        return EventCodec.vectorFromJson(db.runtime().queryForObject(
                "SELECT applied_vector::text FROM eatwhat_sync.entity_state WHERE aggregate_type='counter' AND aggregate_id=?",
                String.class, aggregate));
    }

    private String status(EventFoundationTestSupport.Database db, UUID eventId) {
        return db.runtime().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, eventId);
    }

    private long businessCount(EventFoundationTestSupport.Database db) {
        return db.runtime().queryForObject("SELECT count(*) FROM public.business_counter", Long.class);
    }

    private long count(EventFoundationTestSupport.Database db, String table) {
        if (!Set.of("inbox", "outbox", "entity_state", "pending", "conflict").contains(table)) {
            throw new IllegalArgumentException("Test table is not allowlisted");
        }
        return db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync." + table, Long.class);
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var futures = new ArrayList<java.util.concurrent.Future<T>>();
        try {
            for (Callable<T> work : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out");
                    return work.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
