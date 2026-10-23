package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.sync.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual subprocess termination at pre/post-commit checkpoints; no real node or network transport. */
class EventProcessRecoveryIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("event-process-recovery");
    @TempDir Path directory;
    private final EventCodec codec = new EventCodec();

    @BeforeAll static void start() { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void killingBeforeCommitRollsBackBothBusinessAndEventThenTheSameCommandCanRetry() throws Exception {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var event = event(db);
        killAtCheckpoint(db, event, "outbox-uncommitted");
        assertEquals(0, count(db, "outbox"));
        assertEquals(0, count(db, "delivery"));
        assertEquals(0, count(db, "entity_state"));
        assertEquals(0, db.runtime().queryForObject("SELECT count(*) FROM business_counter", Integer.class));
        killAtCheckpoint(db, event, "outbox-committed");
        assertEquals(1, count(db, "outbox"));
        assertEquals(1, count(db, "delivery"));
        assertEquals(1, db.runtime().queryForObject("SELECT value FROM business_counter", Integer.class));
    }

    @Test
    void committedOutboxAndAbandonedLeaseSurviveKilledWorkers() throws Exception {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var event = event(db);
        killAtCheckpoint(db, event, "outbox-committed");
        assertEquals(1, count(db, "outbox"));
        killAtCheckpoint(db, event, "delivery-claimed");
        String oldToken = db.runtime().queryForObject("SELECT lease_token::text FROM eatwhat_sync.delivery", String.class);
        var delivery = new DeliveryService(db.runtime(), db.transactions(), codec);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<DeliveryService.Lease> claims = List.of();
        while (claims.isEmpty() && System.nanoTime() < deadline) {
            claims = delivery.claim("external", 1, Duration.ofSeconds(5));
            if (claims.isEmpty()) Thread.sleep(25);
        }
        assertEquals(1, claims.size());
        assertEquals(event.eventId(), claims.getFirst().eventId());
        assertNotEquals(oldToken, claims.getFirst().leaseToken().toString());
        assertEquals(2, claims.getFirst().attempts());
        assertTrue(delivery.acknowledge(claims.getFirst(), "DURABLY_QUEUED"));
        assertTrue(delivery.claim("external", 1, Duration.ofSeconds(5)).isEmpty());
        assertEquals(1, count(db, "outbox"), "Receipt ACK never deletes the durable event");
    }

    @Test
    void applicationCommitThenLostAckNeverRepeatsBusinessEffects() throws Exception {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var event = event(db);
        killAtCheckpoint(db, event, "inbox-applied");
        var inbox = new InboxService(db.runtime(), db.transactions(), codec,
                new EventHandlerRegistry(List.of(EventCrashWorker.counterHandler(db.runtime()))));
        for (int i = 0; i < 10; i++) {
            assertEquals(InboxService.ReceiptStatus.ALREADY_APPLIED, inbox.receive(event, event.originSite()).receiptStatus());
        }
        assertTrue(inbox.claim(10, Duration.ofSeconds(5)).isEmpty());
        assertEquals(1, db.runtime().queryForObject("SELECT value FROM business_counter", Integer.class));
        assertEquals(1, count(db, "inbox"));
        assertEquals(0, count(db, "outbox"));
    }

    private void killAtCheckpoint(EventFoundationTestSupport.Database db, SyncEvent event, String action) throws Exception {
        String suffix = UUID.randomUUID().toString();
        Path input = directory.resolve(suffix + ".json");
        Path signal = directory.resolve(suffix + ".ready");
        Path log = directory.resolve(suffix + ".log");
        Files.writeString(input, codec.encode(event).json());
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dlogging.config=classpath:logback-test.xml", "-cp", classpath, EventCrashWorker.class.getName(),
                action, db.url(), db.runtimeUsername(), db.site().getId(), db.site().getInstanceId().toString(),
                input.toString(), signal.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (!Files.exists(signal) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(Files.exists(signal), () -> "Child did not reach " + action + ": " + read(log));
            assertTrue(child.isAlive(), "Checkpoint must block before parent forcefully terminates the worker");
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertNotEquals(0, child.exitValue());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    private String read(Path file) {
        try { return Files.readString(file); }
        catch (Exception ignored) { return "No child output"; }
    }

    private int count(EventFoundationTestSupport.Database db, String table) {
        return db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync." + table, Integer.class);
    }

    private SyncEvent event(EventFoundationTestSupport.Database db) {
        return new SyncEvent(UUID.randomUUID(), db.site().getId(), db.site().getInstanceId(), "test_counter", UUID.randomUUID(),
                1_000_000_001L, "counter.created", 1, VersionVector.empty(), VersionVector.empty().increment(db.site().getId()),
                List.of(), null, "crash-command", UUID.randomUUID(), "{\"value\":1}");
    }
}
