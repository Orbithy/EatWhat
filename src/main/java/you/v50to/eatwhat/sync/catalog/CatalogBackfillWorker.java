package you.v50to.eatwhat.sync.catalog;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Periodic driver for the durable catalog replay cursor. */
public final class CatalogBackfillWorker {
    private final CatalogBackfillStore store;
    private final Duration lease;
    private final int batchSize;
    private final AtomicBoolean active = new AtomicBoolean();

    public CatalogBackfillWorker(CatalogBackfillStore store, Duration lease, int batchSize) {
        if (store == null || lease == null || lease.isNegative() || lease.isZero()
                || batchSize < 1 || batchSize > 128) {
            throw new IllegalArgumentException("Invalid catalog backfill worker configuration");
        }
        this.store = store;
        this.lease = lease;
        this.batchSize = batchSize;
    }

    public int runOnce() {
        if (Thread.currentThread().isInterrupted() || !active.compareAndSet(false, true)) return 0;
        try {
            store.enqueueReadySubjects();
            int handled = 0;
            for (CatalogBackfillStore.Job job : store.claim(4, lease)) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    handled += store.process(job, batchSize).handled();
                } catch (CatalogBackfillStore.BackfillInterruptedException interrupted) {
                    break; // transaction rolled back; the durable lease expires and is reclaimed.
                } catch (RuntimeException retryThroughLeaseExpiry) {
                    if (Thread.currentThread().isInterrupted()) break;
                    // The transaction rolled back; never turn a partial replay into DONE.
                }
            }
            return handled;
        } finally {
            active.set(false);
        }
    }
}
