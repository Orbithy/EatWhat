package you.v50to.eatwhat.sync.transport;

import you.v50to.eatwhat.sync.InboxService;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies received events through the registered handlers. One event per short transaction; a failure of one
 * event never blocks the batch, and an expired lease simply makes the event claimable again.
 */
public final class InboxWorker {
    private static final int BATCH = 50;
    private final InboxService inbox;
    private final Duration lease;
    private final Duration waitingRetry;
    private final TransportMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();

    public InboxWorker(InboxService inbox, Duration lease, Duration waitingRetry, TransportMetrics metrics) {
        this.inbox = Objects.requireNonNull(inbox);
        this.lease = Objects.requireNonNull(lease);
        this.waitingRetry = Objects.requireNonNull(waitingRetry);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public void runOnce() {
        if (Thread.currentThread().isInterrupted() || !running.compareAndSet(false, true)) return;
        try {
            try { metrics.add("inbox_requeued", inbox.requeueWaiting(BATCH, waitingRetry)); }
            catch (RuntimeException failure) { metrics.increment("inbox_requeue_failed"); }
            for (var claimed : inbox.claim(BATCH, lease)) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    var receipt = inbox.process(claimed);
                    metrics.increment("inbox_" + receipt.status().name().toLowerCase(java.util.Locale.ROOT));
                } catch (InboxService.StaleLeaseException stale) {
                    metrics.increment("stale_lease");
                } catch (RuntimeException failure) {
                    // The transaction rolled back; the lease expires and the event is retried.
                    metrics.increment("inbox_process_failed");
                }
            }
        } finally { running.set(false); }
    }
}
