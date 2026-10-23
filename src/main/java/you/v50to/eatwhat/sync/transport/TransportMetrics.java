package you.v50to.eatwhat.sync.transport;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Process-local counters only. No user, peer URL, event, payload or exception labels. */
public final class TransportMetrics {
    private static final Set<String> NAMES = Set.of("push_acked", "push_retried", "push_quarantined",
            "push_failed", "pull_received", "pull_rejected", "pull_failed", "ack_sent", "ack_failed",
            "stale_lease", "cycle_failed", "inbox_applied", "inbox_waiting_dependency", "inbox_conflict",
            "inbox_quarantined", "inbox_received", "inbox_processing", "inbox_process_failed", "inbox_requeued",
            "inbox_requeue_failed");
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void increment(String metric) {
        if (!NAMES.contains(metric)) throw new IllegalArgumentException("Unknown transport metric");
        counters.computeIfAbsent(metric, ignored -> new LongAdder()).increment();
    }

    public void add(String metric, long amount) {
        if (!NAMES.contains(metric)) throw new IllegalArgumentException("Unknown transport metric");
        if (amount > 0) counters.computeIfAbsent(metric, ignored -> new LongAdder()).add(amount);
    }

    public Map<String, Long> snapshot() {
        var result = new TreeMap<String, Long>();
        NAMES.forEach(name -> result.put(name, counters.containsKey(name) ? counters.get(name).sum() : 0L));
        return Map.copyOf(result);
    }
}
