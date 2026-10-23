package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Two isolated databases, actual pinned mTLS, no campus listener and only synthetic handlers. */
class CrossSiteTransportIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("cross-site-transport");
    private static final Duration LEASE = Duration.ofSeconds(2);
    @TempDir static Path certificates;
    @TempDir Path directory;
    private static TransportTlsFixture tls;

    @BeforeAll static void start() throws Exception {
        tls = TransportTlsFixture.create(certificates);
        POSTGRES.start();
    }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void campusInitiatedPushAndPullConvergeWithoutGeneratingNewNumericIdentitiesOrEchoEvents() throws Exception {
        try (Pair pair = new Pair()) {
            var campusSequence = sequenceSnapshot(pair.campusDb);
            var externalSequence = sequenceSnapshot(pair.externalDb);
            SyncEvent fromCampus = pair.seed(true, 3, 1);
            SyncEvent fromExternal = pair.seed(false, 7, 1);
            pair.converge(pair.worker(pair.network()));
            assertCounter(pair.campusDb, fromCampus, 3);
            assertCounter(pair.externalDb, fromCampus, 3);
            assertCounter(pair.campusDb, fromExternal, 7);
            assertCounter(pair.externalDb, fromExternal, 7);
            assertEquals(counterSnapshot(pair.campusDb), counterSnapshot(pair.externalDb));
            for (var db : List.of(pair.campusDb, pair.externalDb)) {
                assertEquals(1, count(db, "eatwhat_sync.outbox"));
                assertEquals(1, count(db, "eatwhat_sync.inbox"));
                assertEquals(0, count(db, "eatwhat_identity.entity_identity"));
                assertEquals(0, count(db, "public.users"));
                assertEquals(2, count(db, "eatwhat_sync.entity_state"));
            }
            assertEquals(campusSequence, sequenceSnapshot(pair.campusDb));
            assertEquals(externalSequence, sequenceSnapshot(pair.externalDb));
            assertEquals(pair.codec.encode(fromCampus).hash(), pair.externalDb.admin().queryForObject(
                    "SELECT envelope_hash FROM eatwhat_sync.inbox WHERE event_id=?", String.class, fromCampus.eventId()));
        }
    }

    @Test
    void actualConnectionOutageLeavesBothSitesWritableAndReconnectReplaysTheExistingEvents() throws Exception {
        try (Pair pair = new Pair()) {
            int port = pair.server.port();
            TransportClient network = pair.network();
            CampusSyncWorker worker = pair.worker(network);
            pair.server.close();
            pair.server = null;
            SyncEvent fromCampus = pair.seed(true, 4, 1);
            SyncEvent fromExternal = pair.seed(false, 5, 1);
            worker.runOnce();
            assertNotEquals("ACKED", status(pair.campusDb, fromCampus));
            assertTrue(attempts(pair.campusDb, fromCampus) >= 1, "The outbound worker must really attempt the offline peer");
            assertEquals("TRANSPORT_RETRY", pair.campusDb.admin().queryForObject(
                    "SELECT last_error FROM eatwhat_sync.delivery WHERE event_id=?", String.class, fromCampus.eventId()));
            assertEquals(0, count(pair.externalDb, "eatwhat_sync.inbox"));
            // Fresh local business commits are independent of remote reachability.
            SyncEvent duringOutage = pair.seed(true, 6, 1);
            assertCounter(pair.campusDb, duringOutage, 6);
            pair.startServer(port);
            // Keep both the HTTP client and worker: their failed-connection/backoff state must recover too.
            pair.converge(worker);
            assertCounter(pair.externalDb, fromCampus, 4);
            assertCounter(pair.externalDb, duringOutage, 6);
            assertCounter(pair.campusDb, fromExternal, 5);
            assertEquals(counterSnapshot(pair.campusDb), counterSnapshot(pair.externalDb));
            assertEquals(2, count(pair.campusDb, "eatwhat_sync.outbox"));
            assertEquals(1, count(pair.externalDb, "eatwhat_sync.outbox"));
        }
    }

    @Test
    void tenLostPushResponsesAfterRealRemoteCommitNeverRepeatBusinessEffects() throws Exception {
        try (Pair pair = new Pair()) {
            SyncEvent event = pair.seed(true, 9, 1);
            TransportClient network = pair.network();
            AtomicInteger lost = new AtomicInteger();
            TransportClient drop = (path, request) -> {
                var response = network.exchange(path, request);
                if (path.endsWith("/push") && !request.events().isEmpty() && lost.get() < 10) {
                    assertEquals(1, count(pair.externalDb, "eatwhat_sync.inbox"));
                    lost.incrementAndGet();
                    throw new IOException("Synthetic committed response loss");
                }
                return response;
            };
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (lost.get() < 10 && System.nanoTime() < deadline) {
                // A fresh worker has no in-memory delivery cursor or idempotency state to rely on.
                pair.worker(drop).runOnce();
                pair.drain();
                assertNotEquals("ACKED", status(pair.campusDb, event));
                Thread.sleep(25);
            }
            assertEquals(10, lost.get());
            pair.converge(pair.worker(pair.network()));
            assertEquals(1, count(pair.externalDb, "eatwhat_sync.inbox"));
            assertCounter(pair.externalDb, event, 9);
            assertTrue(attempts(pair.campusDb, event) >= 11);
        }
    }

    @Test
    void lostPullResponseKeepsTheRemoteLeaseRecoverableWithoutASequenceCursor() throws Exception {
        try (Pair pair = new Pair()) {
            SyncEvent event = pair.seed(false, 11, 1);
            TransportClient network = pair.network();
            AtomicBoolean lost = new AtomicBoolean();
            var worker = pair.worker((path, request) -> {
                var response = network.exchange(path, request);
                if (path.endsWith("/pull") && !response.events().isEmpty() && lost.compareAndSet(false, true)) {
                    assertEquals("LEASED", status(pair.externalDb, event));
                    throw new IOException("Synthetic pull response loss");
                }
                return response;
            });
            worker.runOnce();
            assertTrue(lost.get());
            assertEquals(0, count(pair.campusDb, "eatwhat_sync.inbox"));
            UUID abandonedToken = pair.externalDb.admin().queryForObject(
                    "SELECT lease_token FROM eatwhat_sync.delivery WHERE event_id=?", UUID.class, event.eventId());
            assertNotNull(abandonedToken);
            pair.converge(pair.worker(pair.network()));
            assertTrue(attempts(pair.externalDb, event) >= 2);
            assertCounter(pair.campusDb, event, 11);
            assertEquals(1, count(pair.campusDb, "eatwhat_sync.inbox"));
        }
    }

    @Test
    void lostAcknowledgementBeforeAndAfterRemoteCommitHaveDistinctSafeRecoveryPaths() throws Exception {
        try (Pair pair = new Pair()) {
            SyncEvent before = pair.seed(false, 13, 1);
            TransportClient network = pair.network();
            AtomicBoolean lostBefore = new AtomicBoolean();
            pair.worker((path, request) -> {
                if (path.endsWith("/ack") && !request.acks().isEmpty() && lostBefore.compareAndSet(false, true)) {
                    assertEquals(1, count(pair.campusDb, "eatwhat_sync.inbox"));
                    throw new IOException("Synthetic ACK request loss");
                }
                return network.exchange(path, request);
            }).runOnce();
            assertTrue(lostBefore.get());
            assertEquals("LEASED", status(pair.externalDb, before));
            pair.drain();
            pair.converge(pair.worker(pair.network()));
            assertCounter(pair.campusDb, before, 13);
            assertTrue(attempts(pair.externalDb, before) >= 2);

            SyncEvent after = pair.seed(false, 17, 1);
            AtomicBoolean lostAfter = new AtomicBoolean();
            pair.worker((path, request) -> {
                var response = network.exchange(path, request);
                if (path.endsWith("/ack") && !request.acks().isEmpty() && lostAfter.compareAndSet(false, true)) {
                    assertEquals("ACKED", status(pair.externalDb, after));
                    throw new IOException("Synthetic committed ACK response loss");
                }
                return response;
            }).runOnce();
            assertTrue(lostAfter.get());
            pair.converge(pair.worker(pair.network()));
            assertEquals(1, attempts(pair.externalDb, after));
            assertCounter(pair.campusDb, after, 17);
            assertEquals(2, count(pair.campusDb, "eatwhat_sync.inbox"));
        }
    }

    @Test
    void oneBatchCommitsSuccessesQuarantinesRejectionsAndRetriesOnlyTheTransientFailure() throws Exception {
        try (Pair pair = new Pair()) {
            SyncEvent valid = pair.seed(true, 2, 1);
            SyncEvent rejected = pair.seed(true, 3, 1);
            SyncEvent transientFailure = pair.seed(true, 5, 1);
            SyncEvent unknownSchema = pair.seed(true, 7, 2);
            // A previously persisted immutable event with this ID and different contents must be rejected.
            pair.externalInbox.receive(withPayload(rejected, "{\"delta\":99}"), pair.campus.siteId());
            pair.externalDb.admin().execute("""
                    CREATE FUNCTION public.synthetic_receive_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.event_id='%s'::uuid THEN RAISE EXCEPTION 'synthetic transient failure'; END IF;
                      RETURN NEW;
                    END $$
                    """.formatted(transientFailure.eventId()));
            pair.externalDb.admin().execute("""
                    CREATE TRIGGER synthetic_receive_failure BEFORE INSERT ON eatwhat_sync.inbox
                    FOR EACH ROW EXECUTE FUNCTION public.synthetic_receive_failure()
                    """);
            pair.worker(pair.network()).runOnce();
            assertEquals("ACKED", status(pair.campusDb, valid));
            assertEquals("QUARANTINED", status(pair.campusDb, rejected));
            assertEquals("READY", status(pair.campusDb, transientFailure));
            assertEquals("ACKED", status(pair.campusDb, unknownSchema));
            assertEquals("QUARANTINED", pair.externalDb.admin().queryForObject(
                    "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, unknownSchema.eventId()));
            assertEquals(0, pair.externalDb.admin().queryForObject(
                    "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=?", Integer.class, transientFailure.eventId()));
            assertEquals(3, count(pair.externalDb, "eatwhat_sync.inbox"));
            pair.externalDb.admin().execute("DROP TRIGGER synthetic_receive_failure ON eatwhat_sync.inbox");
            pair.externalDb.admin().execute("DROP FUNCTION public.synthetic_receive_failure()");
            CampusSyncWorker recovered = pair.worker(pair.network());
            eventually(() -> "ACKED".equals(status(pair.campusDb, transientFailure)), () -> {
                recovered.runOnce();
                pair.drain();
            });
            assertCounter(pair.externalDb, valid, 2);
            assertCounter(pair.externalDb, transientFailure, 5);
            assertEquals(0, pair.externalDb.admin().queryForObject(
                    "SELECT count(*) FROM business_counter WHERE gid=?", Integer.class, unknownSchema.aggregateId()));
            assertEquals("QUARANTINED", status(pair.campusDb, rejected));
            assertEquals(4, count(pair.campusDb, "eatwhat_sync.outbox"));
        }
    }

    @Test
    void missingIndividualReceiptRetriesOnlyThatEventAndAnIncorrectHashCannotAcknowledgeAnyOfItsBatch() throws Exception {
        try (Pair pair = new Pair()) {
            SyncEvent first = pair.seed(true, 2, 1);
            SyncEvent second = pair.seed(true, 3, 1);
            TransportClient network = pair.network();
            AtomicBoolean omit = new AtomicBoolean();
            pair.worker((path, request) -> {
                var response = network.exchange(path, request);
                if (path.endsWith("/push") && omit.compareAndSet(false, true)) {
                    return new TransportProtocol.Response(response.header(), response.events(), response.acks().stream()
                            .filter(ack -> !ack.eventId().equals(second.eventId())).toList());
                }
                return response;
            }).runOnce();
            assertEquals("ACKED", status(pair.campusDb, first));
            assertEquals("READY", status(pair.campusDb, second));
            pair.converge(pair.worker(pair.network()));
            assertEquals(1, attempts(pair.campusDb, first));
            assertTrue(attempts(pair.campusDb, second) >= 2);

            SyncEvent third = pair.seed(true, 5, 1);
            SyncEvent fourth = pair.seed(true, 7, 1);
            AtomicBoolean corrupt = new AtomicBoolean();
            pair.worker((path, request) -> {
                var response = network.exchange(path, request);
                if (path.endsWith("/push") && corrupt.compareAndSet(false, true)) {
                    var acks = new ArrayList<>(response.acks());
                    var ack = acks.getFirst();
                    acks.set(0, new TransportProtocol.Ack(ack.eventId(), "a".repeat(64), ack.leaseToken(), ack.status()));
                    return new TransportProtocol.Response(response.header(), response.events(), acks);
                }
                return response;
            }).runOnce();
            assertNotEquals("ACKED", status(pair.campusDb, third));
            assertNotEquals("ACKED", status(pair.campusDb, fourth));
            pair.converge(pair.worker(pair.network()));
            assertEquals(counterSnapshot(pair.campusDb), counterSnapshot(pair.externalDb));
            assertEquals(4, count(pair.externalDb, "eatwhat_sync.inbox"));
        }
    }

    @Test
    void networkCompletionAfterTheLeaseExpiresCannotLetTheOldWorkerAcknowledge() throws Exception {
        try (Pair pair = new Pair()) {
            SyncEvent event = pair.seed(true, 19, 1);
            TransportClient network = pair.network();
            AtomicBoolean held = new AtomicBoolean();
            pair.worker((path, request) -> {
                var response = network.exchange(path, request);
                if (path.endsWith("/push") && held.compareAndSet(false, true)) {
                    assertEquals(1, count(pair.externalDb, "eatwhat_sync.inbox"));
                    eventually(() -> Boolean.TRUE.equals(pair.campusDb.admin().queryForObject(
                            "SELECT lease_until<=clock_timestamp() FROM eatwhat_sync.delivery WHERE event_id=?",
                            Boolean.class, event.eventId())), () -> { });
                }
                return response;
            }).runOnce();
            assertTrue(held.get());
            assertNotEquals("ACKED", status(pair.campusDb, event));
            pair.converge(pair.worker(pair.network()));
            assertCounter(pair.externalDb, event, 19);
            assertEquals(1, count(pair.externalDb, "eatwhat_sync.inbox"));
            assertTrue(attempts(pair.campusDb, event) >= 2);
        }
    }

    @Test
    void independentCampusWorkersUseDisjointLeasesAcrossTheActualHttpsChannel() throws Exception {
        try (Pair pair = new Pair()) {
            for (int i = 0; i < 12; i++) pair.seed(true, i + 1, 1);
            AtomicReference<Set<UUID>> firstBatch = new AtomicReference<>();
            AtomicReference<Set<UUID>> secondBatch = new AtomicReference<>();
            CountDownLatch bothClaimed = new CountDownLatch(2);
            CampusSyncWorker firstWorker = pair.worker(recordFirstPush(pair.network(), firstBatch, bothClaimed));
            CampusSyncWorker secondWorker = pair.worker(recordFirstPush(pair.network(), secondBatch, bothClaimed));
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(firstWorker::runOnce);
                var second = executor.submit(secondWorker::runOnce);
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertNotNull(firstBatch.get(), "First worker must reach the actual HTTPS push boundary");
            assertNotNull(secondBatch.get(), "Second worker must reach the actual HTTPS push boundary");
            assertFalse(firstBatch.get().isEmpty());
            assertFalse(secondBatch.get().isEmpty());
            Set<UUID> overlap = new HashSet<>(firstBatch.get());
            overlap.retainAll(secondBatch.get());
            assertTrue(overlap.isEmpty(), "Inbox deduplication must not conceal duplicate outbound claims");
            Set<UUID> claimed = new HashSet<>(firstBatch.get());
            claimed.addAll(secondBatch.get());
            assertEquals(12, claimed.size(), "Both first claims must cover the twelve ready events");
            pair.converge(pair.worker(pair.network()));
            assertEquals(12, count(pair.externalDb, "eatwhat_sync.inbox"));
            assertEquals(counterSnapshot(pair.campusDb), counterSnapshot(pair.externalDb));
            assertEquals(0, count(pair.externalDb, "eatwhat_sync.outbox"));
        }
    }

    @Test
    void killingRealWorkersAfterRemoteReceiptAndAroundPullAcknowledgementRecoversWithoutNewIds() throws Exception {
        for (String checkpoint : List.of("push-response", "pull-received-before-ack", "ack-response")) {
            try (Pair pair = new Pair()) {
                boolean push = checkpoint.equals("push-response");
                SyncEvent event = pair.seed(push, 23, 1);
                killAtCheckpoint(pair, checkpoint, () -> {
                    assertEquals(1, count(push ? pair.externalDb : pair.campusDb, "eatwhat_sync.inbox"));
                    assertEquals(checkpoint.equals("ack-response") ? "ACKED" : "LEASED",
                            status(push ? pair.campusDb : pair.externalDb, event));
                });
                pair.converge(pair.worker(pair.network()));
                assertCounter(pair.campusDb, event, 23);
                assertCounter(pair.externalDb, event, 23);
                assertEquals(1, count(push ? pair.externalDb : pair.campusDb, "eatwhat_sync.inbox"));
                assertEquals(1, count(push ? pair.campusDb : pair.externalDb, "eatwhat_sync.outbox"));
                assertEquals(0, count(push ? pair.externalDb : pair.campusDb, "eatwhat_sync.outbox"));
            }
        }
    }

    private void killAtCheckpoint(Pair pair, String checkpoint, Runnable committedEvidence) throws Exception {
        Path childDirectory = Files.createDirectory(directory.resolve(checkpoint));
        Path signal = childDirectory.resolve("committed.ready");
        Path log = childDirectory.resolve("worker.log");
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                        .split(File.pathSeparator)).map(value -> Path.of(value).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m", "-Xmx192m", "-Dlogging.config=classpath:logback-test.xml", "-cp", classpath,
                TransportCrashWorker.class.getName(), checkpoint, pair.campusDb.url(), pair.campusDb.runtimeUsername(),
                pair.campus.siteId(), pair.campus.instanceId().toString(), pair.external.siteId(), pair.external.instanceId().toString(),
                pair.uri().toString(), tls.campus().keyStore().toString(), tls.trustStore().toString(), tls.external().pin(), signal.toString())
                .directory(childDirectory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("TZ", "UTC");
        Process child = builder.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (!Files.exists(signal) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(Files.exists(signal), () -> "Transport child missed " + checkpoint + ": " + read(log));
            assertTrue(child.isAlive());
            committedEvidence.run();
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

    private static final class Pair implements AutoCloseable {
        final EventFoundationTestSupport.Database campusDb = database(SiteProperties.Mode.CAMPUS, "campus-test");
        final EventFoundationTestSupport.Database externalDb = database(SiteProperties.Mode.EXTERNAL, "external-test");
        final NodeIdentity campus = identity(campusDb);
        final NodeIdentity external = identity(externalDb);
        final EventCodec codec = new EventCodec();
        final InboxService campusInbox = inbox(campusDb);
        final InboxService externalInbox = inbox(externalDb);
        final ConcurrentLinkedQueue<SyncHttpClient> clients = new ConcurrentLinkedQueue<>();
        NodeHttpsServer server;
        long nextCampus = 1_000_000_001L;
        long nextExternal = 1_000_000_002L;

        Pair() throws Exception { startServer(0); }

        void startServer(int port) throws Exception {
            server = new NodeHttpsServer(new InetSocketAddress("127.0.0.1", port), tls.context(tls.external(), tls.campus()),
                    new NodeEndpoint(external, campus, externalInbox, delivery(externalDb), codec, LEASE, Duration.ofMillis(20)),
                    Duration.ofSeconds(3));
            server.start();
        }

        URI uri() { return URI.create("https://127.0.0.1:" + server.port()); }

        SyncHttpClient network() {
            var client = new SyncHttpClient(uri(), tls.context(tls.campus(), tls.external()), Duration.ofSeconds(2));
            clients.add(client);
            return client;
        }

        CampusSyncWorker worker(TransportClient client) {
            return new CampusSyncWorker(campus, external, delivery(campusDb), campusInbox, codec, client, LEASE,
                    new RetryPolicy(Duration.ofMillis(20), Duration.ofMillis(100), () -> 0.5), new TransportMetrics());
        }

        SyncEvent seed(boolean fromCampus, int delta, int schemaVersion) {
            var db = fromCampus ? campusDb : externalDb;
            long numeric = fromCampus ? nextCampus : nextExternal;
            if (fromCampus) nextCampus += 2;
            else nextExternal += 2;
            SyncEvent event = new SyncEvent(UUID.randomUUID(), db.site().getId(), db.site().getInstanceId(),
                    SyntheticEventPolicy.AGGREGATE_TYPE, UUID.randomUUID(), numeric, SyntheticEventPolicy.EVENT_TYPE,
                    schemaVersion, VersionVector.empty(), VersionVector.empty().increment(db.site().getId()),
                    List.of(), null, "synthetic/" + UUID.randomUUID(), UUID.randomUUID(), "{\"delta\":" + delta + "}");
            db.transactions().required(() -> {
                db.runtime().update("INSERT INTO business_counter(gid,numeric_id,value) VALUES (?,?,?)", event.aggregateId(), numeric, delta);
                new OutboxService(db.runtime(), db.transactions(), codec, db.site()).append(event,
                        List.of(fromCampus ? external.siteId() : campus.siteId()));
                return null;
            });
            return event;
        }

        void drain() {
            for (var inbox : List.of(campusInbox, externalInbox)) {
                for (int iteration = 0; iteration < 10; iteration++) {
                    var claimed = inbox.claim(100, Duration.ofSeconds(5));
                    if (claimed.isEmpty()) break;
                    claimed.forEach(inbox::process);
                }
            }
        }

        void converge(CampusSyncWorker worker) throws Exception {
            eventually(() -> unacknowledged(campusDb) == 0 && unacknowledged(externalDb) == 0, () -> {
                worker.runOnce();
                drain();
            });
            drain();
        }

        @Override public void close() {
            clients.forEach(SyncHttpClient::close);
            if (server != null) server.close();
        }
    }

    private static EventFoundationTestSupport.Database database(SiteProperties.Mode mode, String id) {
        SiteProperties properties = new SiteProperties();
        properties.setMode(mode);
        properties.setId(id);
        properties.setInstanceId(UUID.randomUUID());
        return EventFoundationTestSupport.createDatabase(POSTGRES, properties);
    }

    private static NodeIdentity identity(EventFoundationTestSupport.Database db) {
        return new NodeIdentity(db.site().getId(), db.site().getInstanceId());
    }

    private static InboxService inbox(EventFoundationTestSupport.Database db) {
        return new InboxService(db.runtime(), db.transactions(), new EventCodec(),
                new EventHandlerRegistry(List.of(TransportCrashWorker.counterHandler(db.runtime()))));
    }

    private static DeliveryService delivery(EventFoundationTestSupport.Database db) {
        return new DeliveryService(db.runtime(), db.transactions(), new EventCodec());
    }

    private static TransportClient recordFirstPush(TransportClient network, AtomicReference<Set<UUID>> firstBatch,
                                                   CountDownLatch bothClaimed) {
        return (path, request) -> {
            if (path.endsWith("/push") && firstBatch.compareAndSet(null, request.events().stream()
                    .map(TransportProtocol.EventItem::eventId).collect(Collectors.toUnmodifiableSet()))) {
                // Both committed claims must coexist before either worker can receive an HTTP ACK.
                bothClaimed.countDown();
                assertTrue(bothClaimed.await(5, TimeUnit.SECONDS), "Both workers must claim before sending either batch");
            }
            return network.exchange(path, request);
        };
    }

    private static int count(EventFoundationTestSupport.Database db, String table) {
        return db.admin().queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static long unacknowledged(EventFoundationTestSupport.Database db) {
        return db.admin().queryForObject("SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'", Long.class);
    }

    private static String status(EventFoundationTestSupport.Database db, SyncEvent event) {
        return db.admin().queryForObject("SELECT status FROM eatwhat_sync.delivery WHERE event_id=?", String.class, event.eventId());
    }

    private static int attempts(EventFoundationTestSupport.Database db, SyncEvent event) {
        return db.admin().queryForObject("SELECT attempts FROM eatwhat_sync.delivery WHERE event_id=?", Integer.class, event.eventId());
    }

    private static void assertCounter(EventFoundationTestSupport.Database db, SyncEvent event, int expected) {
        var row = db.admin().queryForMap("SELECT numeric_id,value FROM business_counter WHERE gid=?", event.aggregateId());
        assertEquals(event.numericId(), ((Number) row.get("numeric_id")).longValue());
        assertEquals(expected, ((Number) row.get("value")).intValue());
    }

    private static List<Map<String, Object>> counterSnapshot(EventFoundationTestSupport.Database db) {
        return db.admin().queryForList("SELECT gid,numeric_id,value FROM business_counter ORDER BY gid");
    }

    private static List<Map<String, Object>> sequenceSnapshot(EventFoundationTestSupport.Database db) {
        return db.admin().queryForList("SELECT schemaname,sequencename,last_value FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename");
    }

    private static SyncEvent withPayload(SyncEvent event, String payload) {
        return new SyncEvent(event.eventId(), event.originSite(), event.originInstanceId(), event.aggregateType(), event.aggregateId(),
                event.numericId(), event.eventType(), event.schemaVersion(), event.baseVersion(), event.nextVersion(),
                event.dependencies(), event.actorId(), event.commandKey(), event.correlationId(), payload);
    }

    private static void eventually(BooleanSupplier completed, CheckedAction action) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            action.run();
            if (completed.getAsBoolean()) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Transport did not reach its persisted recovery condition within the bounded deadline");
    }

    private static String read(Path path) {
        try {
            String text = Files.readString(path);
            return text.substring(Math.max(0, text.length() - 8000));
        } catch (IOException ignored) { return "No child output"; }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
