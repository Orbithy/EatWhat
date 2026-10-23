package you.v50to.eatwhat.sync;

/** Explicit domain adapter. Both callbacks run inside the Inbox database transaction. */
public interface EventHandler {
    String aggregateType();

    String eventType();

    int schemaVersion();

    /** Check actual domain prerequisites, not merely retained global identity reservations. */
    boolean dependenciesSatisfied(SyncEvent event);

    /** Same-database effects only. Throw on failure; no network, Redis, files or Outbox recapture. */
    void apply(SyncEvent event);
}
