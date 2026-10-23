package you.v50to.eatwhat.config.sync;

import org.springframework.context.SmartLifecycle;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.DeliveryService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.InboxService;
import you.v50to.eatwhat.sync.transport.*;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Starts after singleton/database initialization; never adds routes or authentication to business MVC. */
public final class SyncTransportRuntime implements SmartLifecycle {
    private final SiteProperties site;
    private final SyncTransportProperties properties;
    private final DeliveryService delivery;
    private final InboxService inbox;
    private final EventCodec codec;
    private final TransportMetrics metrics;
    private final EventPolicy policy;
    private volatile boolean running;
    private NodeHttpsServer server;
    private SyncHttpClient client;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService inboxScheduler;

    public SyncTransportRuntime(SiteProperties site, SyncTransportProperties properties, DeliveryService delivery,
                                InboxService inbox, EventCodec codec, TransportMetrics metrics) {
        this(site, properties, delivery, inbox, codec, metrics, EventPolicy.SYNTHETIC_ONLY);
    }

    public SyncTransportRuntime(SiteProperties site, SyncTransportProperties properties, DeliveryService delivery,
                                InboxService inbox, EventCodec codec, TransportMetrics metrics, EventPolicy policy) {
        this.policy = policy;
        this.site = site; this.properties = properties; this.delivery = delivery;
        this.inbox = inbox; this.codec = codec; this.metrics = metrics;
    }

    @Override public synchronized void start() {
        if (running) return;
        // Non-synthetic transport is valid only when a registered domain policy actually exists.
        properties.validate(site, !policy.aggregateTypes().isEmpty() && policy != EventPolicy.SYNTHETIC_ONLY);
        char[] keyPassword = properties.getKeyStorePassword().toCharArray();
        char[] trustPassword = properties.getTrustStorePassword().toCharArray();
        try {
            var tls = NodeTls.create(properties.getKeyStore(), keyPassword, properties.getTrustStore(),
                    trustPassword, properties.getPeerCertificateSha256());
            var local = new NodeIdentity(site.getId(), site.getInstanceId());
            var peer = new NodeIdentity(properties.getPeerSiteId(), properties.getPeerInstanceId());
            if (site.getMode() == SiteProperties.Mode.EXTERNAL) {
                var endpoint = new NodeEndpoint(local, peer, inbox, delivery, codec,
                        properties.getLeaseDuration(), properties.getRetryBase(), policy);
                server = new NodeHttpsServer(new InetSocketAddress(properties.getListenHost(), properties.getListenPort()),
                        tls, endpoint, properties.getRequestTimeout());
                server.start();
            } else {
                client = new SyncHttpClient(properties.getPeerBaseUrl(), tls, properties.getRequestTimeout());
                var worker = new CampusSyncWorker(local, peer, delivery, inbox, codec, client,
                        properties.getLeaseDuration(), new RetryPolicy(properties.getRetryBase(), properties.getRetryMax()), metrics, policy);
                scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                    var thread = new Thread(task, "campus-sync-poll"); thread.setDaemon(true); return thread;
                });
                scheduler.scheduleWithFixedDelay(() -> {
                    try { worker.runOnce(); } catch (RuntimeException failure) { metrics.increment("cycle_failed"); }
                }, 0, properties.getPollInterval().toMillis(), TimeUnit.MILLISECONDS);
            }
            if (policy != EventPolicy.SYNTHETIC_ONLY) {
                // Domain events must actually be applied: PR-04 receipt alone only stores them durably.
                var inboxWorker = new InboxWorker(inbox, properties.getLeaseDuration(), properties.getPollInterval(), metrics);
                inboxScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                    var thread = new Thread(task, "sync-inbox-apply"); thread.setDaemon(true); return thread;
                });
                inboxScheduler.scheduleWithFixedDelay(() -> {
                    try { inboxWorker.runOnce(); } catch (RuntimeException failure) { metrics.increment("cycle_failed"); }
                }, 0, properties.getPollInterval().toMillis(), TimeUnit.MILLISECONDS);
            }
            running = true;
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("Cannot start isolated synthetic sync transport");
        } finally {
            Arrays.fill(keyPassword, '\0'); Arrays.fill(trustPassword, '\0');
        }
    }

    @Override public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (inboxScheduler != null) inboxScheduler.shutdownNow();
        if (client != null) { client.close(); client = null; }
        if (server != null) { server.close(); server = null; }
        running = false;
        if (inboxScheduler != null) {
            try {
                if (!inboxScheduler.awaitTermination(35, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Inbox worker did not stop within the bounded timeout");
                }
                inboxScheduler = null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the inbox worker");
            }
        }
        if (scheduler != null) {
            try {
                // The event transaction timeout is 30 seconds; stop must not silently orphan a DB task.
                if (!scheduler.awaitTermination(35, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Sync worker did not stop within the bounded timeout");
                }
                scheduler = null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the sync worker");
            }
        }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
