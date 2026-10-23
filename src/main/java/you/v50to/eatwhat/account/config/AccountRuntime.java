package you.v50to.eatwhat.account.config;

import org.springframework.context.SmartLifecycle;
import you.v50to.eatwhat.account.AccountRequestHandler;
import you.v50to.eatwhat.account.AccountSyncWorker;
import you.v50to.eatwhat.account.transport.AccountNodeClient;
import you.v50to.eatwhat.account.transport.AccountNodeServer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Private account channel, independent of the PR-04 event listener and its campus-only worker. */
public final class AccountRuntime implements SmartLifecycle {
    private final SiteProperties site;
    private final AccountProperties properties;
    private final SSLContext tls;
    private final AccountNodeClient client;
    private final AccountRequestHandler handler;
    private final AccountSyncWorker worker;
    private volatile boolean running;
    private AccountNodeServer server;
    private ScheduledExecutorService scheduler;

    public AccountRuntime(SiteProperties site, AccountProperties properties, SSLContext tls, AccountNodeClient client,
                           AccountRequestHandler handler, AccountSyncWorker worker) {
        this.site = site; this.properties = properties; this.tls = tls; this.client = client;
        this.handler = handler; this.worker = worker;
    }

    @Override public synchronized void start() {
        if (running) return;
        properties.validate(site);
        try {
            server = new AccountNodeServer(new InetSocketAddress(properties.getListenHost(), properties.getListenPort()),
                    tls, new NodeIdentity(site.getId(), site.getInstanceId()),
                    new NodeIdentity(properties.getPeerSiteId(), properties.getPeerInstanceId()), handler, properties.getRequestTimeout());
            server.start();
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "account-recovery"); thread.setDaemon(true); return thread;
            });
            scheduler.scheduleWithFixedDelay(() -> {
                try { worker.runOnce(); } catch (RuntimeException unavailable) { /* Persistent tasks recover next cycle. */ }
            }, 0, properties.getPollInterval().toMillis(), TimeUnit.MILLISECONDS);
            running = true;
        } catch (RuntimeException failure) {
            stop();
            throw new IllegalStateException("Cannot start isolated account exercise");
        }
    }

    @Override public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        // The shared bounded client is destroyed with its Spring bean, not on a stop/start cycle.
        // Interrupting the worker cancels its outstanding call; request handlers have their own deadline.
        if (server != null) { server.close(); server = null; }
        running = false;
        if (scheduler != null) {
            try {
                if (!scheduler.awaitTermination(35, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Account worker did not stop within its deadline");
                }
                scheduler = null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping account worker");
            }
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 110; }
}
