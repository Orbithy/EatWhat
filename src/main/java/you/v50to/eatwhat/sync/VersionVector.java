package you.v50to.eatwhat.sync;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Causal counters; wall-clock time never decides whether an event wins. */
public record VersionVector(Map<String, Long> counters) {
    public static final long MAX_COUNTER = 9_007_199_254_740_991L;
    /** Retired origins remain part of causal history; active membership has its separate 16-node bound. */
    public static final int MAX_SITES = 1024;
    public enum Relation { EQUAL, BEFORE, AFTER, CONCURRENT }

    public VersionVector {
        Objects.requireNonNull(counters, "counters");
        if (counters.size() > MAX_SITES) throw new IllegalArgumentException("Too many vector sites");
        var normalized = new TreeMap<String, Long>();
        counters.forEach((site, value) -> {
            SyncEvent.requireName(site, "vector site", 64);
            if (value == null || value < 0 || value > MAX_COUNTER) {
                throw new IllegalArgumentException("Invalid vector counter");
            }
            if (value > 0) normalized.put(site, value);
        });
        counters = Collections.unmodifiableMap(normalized);
    }

    public static VersionVector empty() { return new VersionVector(Map.of()); }

    public VersionVector increment(String site) {
        SyncEvent.requireName(site, "site", 64);
        var next = new TreeMap<>(counters);
        long value = next.getOrDefault(site, 0L);
        if (value == MAX_COUNTER) throw new IllegalArgumentException("Vector counter exhausted");
        next.put(site, value + 1);
        return new VersionVector(next);
    }

    public Relation compare(VersionVector other) {
        Objects.requireNonNull(other, "other");
        boolean less = false;
        boolean greater = false;
        var sites = new TreeSet<>(counters.keySet());
        sites.addAll(other.counters.keySet());
        for (String site : sites) {
            int order = Long.compare(counters.getOrDefault(site, 0L), other.counters.getOrDefault(site, 0L));
            less |= order < 0;
            greater |= order > 0;
        }
        return less ? (greater ? Relation.CONCURRENT : Relation.BEFORE)
                : (greater ? Relation.AFTER : Relation.EQUAL);
    }
}
