package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.sync.SyncTransportConfiguration;
import you.v50to.eatwhat.config.sync.SyncTransportProperties;
import you.v50to.eatwhat.config.sync.SyncTransportRuntime;
import you.v50to.eatwhat.sync.DeliveryService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.EventHandlerRegistry;
import you.v50to.eatwhat.sync.InboxService;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.SyntheticEventPolicy;
import you.v50to.eatwhat.sync.transport.TransportMetrics;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Spring property binding and SmartLifecycle scheduling, pinned TLS and isolated PostgreSQL. */
class TransportLifecycleIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("transport-lifecycle");
    @TempDir static Path certificates;
    private static TransportTlsFixture tls;

    @BeforeAll static void start() throws Exception {
        tls = TransportTlsFixture.create(certificates);
        POSTGRES.start();
    }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void realSpringLifecycleSchedulesCampusOnlyStopsAndReconstructsFromDurableStateAndReleasesTheListener() throws Exception {
        var campus = database(SiteProperties.Mode.CAMPUS, "lifecycle-campus");
        var external = database(SiteProperties.Mode.EXTERNAL, "lifecycle-external");
        var campusPublicRows = publicBusinessCounts(campus);
        var externalPublicRows = publicBusinessCounts(external);
        var campusSequences = publicSequences(campus);
        var externalSequences = publicSequences(external);
        int externalPort = unusedPort();

        // CAMPUS's configured listener port is already occupied for the entire test. A mistaken
        // listener branch cannot start; its actual outbound scheduler must still work normally.
        try (var campusPortGuard = boundSocket(0)) {
            int campusPort = campusPortGuard.getLocalPort();
            AnnotationConfigApplicationContext externalContext = context(external, campus, externalPort, externalPort);
            AnnotationConfigApplicationContext campusContext = null;
            try {
                var externalRuntime = externalContext.getBean(SyncTransportRuntime.class);
                assertTrue(externalRuntime.isRunning());
                assertThrows(IOException.class, () -> { try (var ignored = boundSocket(externalPort)) { fail("Listener must own its port"); } });

                var fromCampus = seed(campus, external, 1_000_000_001L, SyntheticEventPolicy.AGGREGATE_TYPE, 3);
                var fromExternal = seed(external, campus, 1_000_000_002L, SyntheticEventPolicy.AGGREGATE_TYPE, 5);
                var campusBusiness = seed(campus, external, 1_000_000_003L, "users", 1);
                var externalBusiness = seed(external, campus, 1_000_000_004L, "users", 1);

                // EXTERNAL's lifecycle only opens the listener: no campus means no outbound work.
                consistentlyFor(Duration.ofMillis(800), () -> attempts(external, fromExternal) == 0
                        && inboxCount(campus, fromExternal) == 0);
                assertTrue(externalContext.getBean(TransportMetrics.class).snapshot().values().stream().allMatch(value -> value == 0));

                campusContext = context(campus, external, campusPort, externalPort);
                var campusRuntime = campusContext.getBean(SyncTransportRuntime.class);
                assertTrue(campusRuntime.isRunning());
                var bound = campusContext.getBean(SyncTransportProperties.class);
                assertTrue(bound.isTransportEnabled());
                assertTrue(bound.isSyntheticOnly());
                assertEquals(Duration.ofMillis(250), bound.getPollInterval());
                assertEquals(tls.campus().keyStore(), bound.getKeyStore());

                // There is deliberately no call to CampusSyncWorker.runOnce(): Spring's real
                // scheduler and HTTP client must create these committed cross-database facts.
                eventually(() -> "ACKED".equals(state(campus, fromCampus)) && "ACKED".equals(state(external, fromExternal))
                        && inboxCount(external, fromCampus) == 1 && inboxCount(campus, fromExternal) == 1);
                assertEquals(1, ackedCount(campus));
                assertEquals(1, ackedCount(external));
                assertEquals(new EventCodec().encode(fromCampus).hash(), inboxHash(external, fromCampus));
                assertEquals(new EventCodec().encode(fromExternal).hash(), inboxHash(campus, fromExternal));
                assertTrue(campusContext.getBean(TransportMetrics.class).snapshot().get("push_acked") >= 1);
                assertTrue(campusContext.getBean(TransportMetrics.class).snapshot().get("pull_received") >= 1);
                assertUncaptured(campus, external, campusBusiness, externalBusiness);

                // Test-only handlers are explicitly drained; production has no domain apply worker.
                drain(campusContext.getBean(InboxService.class));
                drain(externalContext.getBean(InboxService.class));
                assertCounter(external, fromCampus, 3);
                assertCounter(campus, fromExternal, 5);

                // Interrupt the real endpoint while keeping the same campus context, scheduler,
                // worker and HTTP client alive. Recovery must not depend on reconstructing them.
                externalContext.close();
                assertFalse(externalRuntime.isRunning());
                var duringOutage = seed(campus, external, 1_000_000_007L, SyntheticEventPolicy.AGGREGATE_TYPE, 13);
                var waitingRemotely = seed(external, campus, 1_000_000_008L, SyntheticEventPolicy.AGGREGATE_TYPE, 17);
                eventually(() -> attempts(campus, duringOutage) >= 1
                        && "TRANSPORT_RETRY".equals(campus.runtime().queryForObject(
                        "SELECT last_error FROM eatwhat_sync.delivery WHERE event_id=?", String.class, duringOutage.eventId())));
                assertEquals(0, inboxCount(external, duringOutage));
                assertEquals(0, attempts(external, waitingRemotely));
                externalContext = context(external, campus, externalPort, externalPort);
                externalRuntime = externalContext.getBean(SyncTransportRuntime.class);
                eventually(() -> "ACKED".equals(state(campus, duringOutage)) && "ACKED".equals(state(external, waitingRemotely))
                        && inboxCount(external, duringOutage) == 1 && inboxCount(campus, waitingRemotely) == 1);
                assertSame(campusRuntime, campusContext.getBean(SyncTransportRuntime.class));
                drain(campusContext.getBean(InboxService.class));
                drain(externalContext.getBean(InboxService.class));
                assertCounter(external, duringOutage, 13);
                assertCounter(campus, waitingRemotely, 17);

                campusContext.close();
                campusContext = null;
                assertFalse(campusRuntime.isRunning());
                var whileStopped = seed(campus, external, 1_000_000_005L, SyntheticEventPolicy.AGGREGATE_TYPE, 7);
                var awaitingPull = seed(external, campus, 1_000_000_006L, SyntheticEventPolicy.AGGREGATE_TYPE, 11);
                consistentlyFor(Duration.ofMillis(1100), () -> "READY".equals(state(campus, whileStopped))
                        && "READY".equals(state(external, awaitingPull)) && attempts(campus, whileStopped) == 0
                        && attempts(external, awaitingPull) == 0 && inboxCount(external, whileStopped) == 0
                        && inboxCount(campus, awaitingPull) == 0);

                campusContext = context(campus, external, campusPort, externalPort);
                assertTrue(campusContext.getBean(SyncTransportRuntime.class).isRunning());
                eventually(() -> "ACKED".equals(state(campus, whileStopped)) && "ACKED".equals(state(external, awaitingPull))
                        && inboxCount(external, whileStopped) == 1 && inboxCount(campus, awaitingPull) == 1);
                drain(campusContext.getBean(InboxService.class));
                drain(externalContext.getBean(InboxService.class));
                assertCounter(external, whileStopped, 7);
                assertCounter(campus, awaitingPull, 11);
                assertEquals(3, ackedCount(campus));
                assertEquals(3, ackedCount(external));
                assertUncaptured(campus, external, campusBusiness, externalBusiness);
                assertEquals(campusPublicRows, publicBusinessCounts(campus));
                assertEquals(externalPublicRows, publicBusinessCounts(external));
                assertEquals(campusSequences, publicSequences(campus));
                assertEquals(externalSequences, publicSequences(external));
                assertEquals(0, campus.runtime().queryForObject("SELECT count(*) FROM eatwhat_identity.entity_identity", Integer.class));
                assertEquals(0, external.runtime().queryForObject("SELECT count(*) FROM eatwhat_identity.entity_identity", Integer.class));

                campusContext.close();
                campusContext = null;
                externalContext.close();
                assertFalse(externalRuntime.isRunning());
                try (var rebound = boundSocket(externalPort)) { assertEquals(externalPort, rebound.getLocalPort()); }

                // Rebuild the external Spring context on the released port too, proving destruction
                // released its real listener rather than only toggling a lifecycle boolean.
                externalContext = context(external, campus, externalPort, externalPort);
                assertTrue(externalContext.getBean(SyncTransportRuntime.class).isRunning());
            } finally {
                if (campusContext != null) campusContext.close();
                externalContext.close();
            }
        }
        try (var rebound = boundSocket(externalPort)) { assertEquals(externalPort, rebound.getLocalPort()); }
    }

    private AnnotationConfigApplicationContext context(EventFoundationTestSupport.Database local,
                                                       EventFoundationTestSupport.Database peer,
                                                       int listenPort, int externalPort) {
        boolean campus = local.site().getMode() == SiteProperties.Mode.CAMPUS;
        var localCertificate = campus ? tls.campus() : tls.external();
        var peerCertificate = campus ? tls.external() : tls.campus();
        Map<String, Object> properties = new HashMap<>();
        properties.put("app.sync.transport-enabled", "true");
        properties.put("app.sync.synthetic-only", "true");
        properties.put("app.sync.capture-enabled", "false");
        properties.put("app.sync.peer-site-id", peer.site().getId());
        properties.put("app.sync.peer-instance-id", peer.site().getInstanceId().toString());
        properties.put("app.sync.key-store", localCertificate.keyStore().toString());
        properties.put("app.sync.key-store-password", TransportTlsFixture.PASSWORD);
        properties.put("app.sync.trust-store", tls.trustStore().toString());
        properties.put("app.sync.trust-store-password", TransportTlsFixture.PASSWORD);
        properties.put("app.sync.peer-certificate-sha256", peerCertificate.pin());
        properties.put("app.sync.listen-host", "127.0.0.1");
        properties.put("app.sync.listen-port", Integer.toString(listenPort));
        properties.put("app.sync.request-timeout", "2s");
        properties.put("app.sync.lease-duration", "10s");
        properties.put("app.sync.poll-interval", "250ms");
        properties.put("app.sync.retry-base", "50ms");
        properties.put("app.sync.retry-max", "250ms");
        if (campus) properties.put("app.sync.peer-base-url", "https://127.0.0.1:" + externalPort);
        var context = new AnnotationConfigApplicationContext();
        // No Boot config-data loader or component scan: host application.yml/.env is never read.
        context.getEnvironment().getPropertySources().remove("systemProperties");
        context.getEnvironment().getPropertySources().remove("systemEnvironment");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-lifecycle", properties));
        context.registerBean(SiteProperties.class, local::site);
        context.registerBean(EventCodec.class, EventCodec::new);
        context.registerBean(DeliveryService.class, () -> new DeliveryService(local.runtime(), local.transactions(), new EventCodec()));
        context.registerBean(InboxService.class, () -> new InboxService(local.runtime(), local.transactions(), new EventCodec(),
                new EventHandlerRegistry(List.of(TransportCrashWorker.counterHandler(local.runtime())))));
        context.register(SyncTransportConfiguration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    private EventFoundationTestSupport.Database database(SiteProperties.Mode mode, String id) {
        var site = new SiteProperties();
        site.setMode(mode);
        site.setId(id);
        site.setInstanceId(UUID.randomUUID());
        return EventFoundationTestSupport.createDatabase(POSTGRES, site);
    }

    private SyncEvent seed(EventFoundationTestSupport.Database local, EventFoundationTestSupport.Database peer,
                           long numericId, String aggregateType, int delta) {
        var event = new SyncEvent(UUID.randomUUID(), local.site().getId(), local.site().getInstanceId(), aggregateType,
                UUID.randomUUID(), numericId, SyntheticEventPolicy.EVENT_TYPE, 1, VersionVector.empty(),
                VersionVector.empty().increment(local.site().getId()), List.of(), null, "lifecycle/" + UUID.randomUUID(),
                UUID.randomUUID(), "{\"delta\":" + delta + "}");
        local.transactions().required(() -> {
            local.runtime().update("INSERT INTO business_counter(gid,numeric_id,value) VALUES (?,?,?)", event.aggregateId(), numericId, delta);
            new OutboxService(local.runtime(), local.transactions(), new EventCodec(), local.site()).append(event, List.of(peer.site().getId()));
            return null;
        });
        return event;
    }

    private void assertUncaptured(EventFoundationTestSupport.Database campus, EventFoundationTestSupport.Database external,
                                  SyncEvent campusBusiness, SyncEvent externalBusiness) {
        assertEquals("READY", state(campus, campusBusiness));
        assertEquals("READY", state(external, externalBusiness));
        assertEquals(0, attempts(campus, campusBusiness));
        assertEquals(0, attempts(external, externalBusiness));
        assertEquals(0, inboxCount(external, campusBusiness));
        assertEquals(0, inboxCount(campus, externalBusiness));
    }

    private void drain(InboxService inbox) {
        for (var lease : inbox.claim(100, Duration.ofSeconds(5))) inbox.process(lease);
    }

    private void assertCounter(EventFoundationTestSupport.Database db, SyncEvent event, int value) {
        var row = db.runtime().queryForMap("SELECT numeric_id,value FROM business_counter WHERE gid=?", event.aggregateId());
        assertEquals(event.numericId(), ((Number) row.get("numeric_id")).longValue());
        assertEquals(value, ((Number) row.get("value")).intValue());
        assertEquals("APPLIED", db.runtime().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, event.eventId()));
    }

    private int attempts(EventFoundationTestSupport.Database db, SyncEvent event) {
        return db.runtime().queryForObject("SELECT attempts FROM eatwhat_sync.delivery WHERE event_id=?", Integer.class, event.eventId());
    }
    private String state(EventFoundationTestSupport.Database db, SyncEvent event) {
        return db.runtime().queryForObject("SELECT status FROM eatwhat_sync.delivery WHERE event_id=?", String.class, event.eventId());
    }
    private int inboxCount(EventFoundationTestSupport.Database db, SyncEvent event) {
        return db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=?", Integer.class, event.eventId());
    }
    private String inboxHash(EventFoundationTestSupport.Database db, SyncEvent event) {
        return db.runtime().queryForObject("SELECT envelope_hash FROM eatwhat_sync.inbox WHERE event_id=?", String.class, event.eventId());
    }
    private int ackedCount(EventFoundationTestSupport.Database db) {
        return db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.delivery WHERE status='ACKED'", Integer.class);
    }
    private List<Map<String, Object>> publicSequences(EventFoundationTestSupport.Database db) {
        return db.admin().queryForList("SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename");
    }
    private Map<String, Integer> publicBusinessCounts(EventFoundationTestSupport.Database db) {
        var tables = db.admin().queryForList("""
                SELECT tablename FROM pg_tables WHERE schemaname='public'
                  AND tablename NOT IN ('business_counter','flyway_schema_history') ORDER BY tablename
                """, String.class);
        Map<String, Integer> counts = new TreeMap<>();
        for (String table : tables) {
            assertTrue(table.matches("[a-z_]+"));
            counts.put(table, db.admin().queryForObject("SELECT count(*) FROM public." + table, Integer.class));
        }
        return counts;
    }
    private int unusedPort() throws IOException {
        try (var socket = boundSocket(0)) { return socket.getLocalPort(); }
    }
    private static ServerSocket boundSocket(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return socket;
        } catch (IOException failure) { socket.close(); throw failure; }
    }
    private void eventually(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Spring scheduler did not create the required durable state within the deadline");
    }
    private void consistentlyFor(Duration duration, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        do {
            assertTrue(condition.getAsBoolean(), "Stopped/passive transport must not claim or receive events");
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
    }
}
