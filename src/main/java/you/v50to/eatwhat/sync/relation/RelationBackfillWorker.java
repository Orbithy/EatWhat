package you.v50to.eatwhat.sync.relation;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small periodic driver; durable leases and cursors live in PostgreSQL. */
public final class RelationBackfillWorker {
    private final RelationBackfillStore store;
    private final Duration lease;
    private final int batchSize;
    private final AtomicBoolean active = new AtomicBoolean();

    public RelationBackfillWorker(RelationBackfillStore store, Duration lease, int batchSize) {
        if (store == null || lease == null || lease.isNegative() || lease.isZero()
                || batchSize < 1 || batchSize > 128) {
            throw new IllegalArgumentException("Invalid relation backfill worker configuration");
        }
        this.store = store;
        this.lease = lease;
        this.batchSize = batchSize;
    }

    /** Returns the number of candidate intents handled by this bounded pass. */
    public int runOnce() {
        if (Thread.currentThread().isInterrupted() || !active.compareAndSet(false, true)) return 0;
        try {
            store.enqueueReadySubjects();
            int handled = 0;
            for (RelationBackfillStore.Job job : store.claim(4, lease)) {
                if (Thread.currentThread().isInterrupted()) break;
                handled += store.process(job, batchSize).handled();
            }
            return handled;
        } finally {
            active.set(false);
        }
    }
}
