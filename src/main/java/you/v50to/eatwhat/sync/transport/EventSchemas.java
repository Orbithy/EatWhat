package you.v50to.eatwhat.sync.transport;

import you.v50to.eatwhat.sync.SyncEvent;
import java.util.*;

/** Receiver capabilities only control delivery, never the event's authorization or validation. */
public final class EventSchemas {
    private static final Map<String, Set<Integer>> LEGACY;
    static {
        var versions = new HashMap<String, Set<Integer>>();
        for (String type : Set.of("activity_foods", "activity_dinners", "profile.operation",
                "privacy.operation", "notification.read.operation", "catalog.operation", "browse.observation",
                "preference.operation", "file.metadata", "file.references", "file.receipt", "file.replica")) {
            versions.put(type, Set.of(1));
        }
        // V25 already shipped the causal relation protocol before capability negotiation existed.
        versions.put("relation.operation", Set.of(1, 2));
        LEGACY = Map.copyOf(versions);
    }

    private EventSchemas() { }

    public static Map<String, Set<Integer>> legacy() { return LEGACY; }

    public static Map<String, Set<Integer>> fromProbe(Map<String, ?> probe) {
        if (!probe.containsKey("eventSchemas")) return legacy();
        return copy(probe.get("eventSchemas"));
    }

    public static Map<String, Set<Integer>> copy(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.size() > 64) throw invalid();
        var result = new HashMap<String, Set<Integer>>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String type)) throw invalid();
            SyncEvent.requireName(type, "aggregate capability", 64);
            if (!(entry.getValue() instanceof Collection<?> values) || values.isEmpty() || values.size() > 8) {
                throw invalid();
            }
            var versions = new HashSet<Integer>();
            for (Object version : values) {
                if (!(version instanceof Integer || version instanceof Long)) throw invalid();
                long number = ((Number) version).longValue();
                if (number < 1 || number > 256 || !versions.add((int) number)) throw invalid();
            }
            result.put(type, Set.copyOf(versions));
        }
        return Map.copyOf(result);
    }

    public static boolean supports(Map<String, Set<Integer>> capabilities, String aggregate, int version) {
        return capabilities.getOrDefault(aggregate, Set.of()).contains(version);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_EVENT_READER_CAPABILITIES");
    }
}
