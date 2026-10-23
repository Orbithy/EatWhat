package you.v50to.eatwhat.sync.transport;

import you.v50to.eatwhat.sync.SyncEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

/** The complete allowlist of event families the node transport may carry; everything else is quarantined. */
public interface EventPolicy {
    enum OutboundDecision { ALLOW, WAIT, POLICY_BLOCKED }

    /** Aggregate types claimed from the Outbox before any lock; no other event can be leased or disclosed. */
    Set<String> aggregateTypes();

    /** Actual installed readers, independent of whether their writers are enabled. */
    default Map<String, Set<Integer>> supportedSchemaVersions() {
        var versions = new HashMap<String, Set<Integer>>();
        for (String type : aggregateTypes()) versions.put(type, Set.of(1));
        return Map.copyOf(versions);
    }

    /** Throws IllegalArgumentException for an event this policy does not accept from the expected origin. */
    void validate(SyncEvent event, NodeIdentity expectedOrigin);

    /** Final dynamic authorization before an immutable event is placed on the wire. */
    default OutboundDecision outbound(SyncEvent event) { return OutboundDecision.ALLOW; }

    EventPolicy SYNTHETIC_ONLY = new EventPolicy() {
        @Override public Set<String> aggregateTypes() { return Set.of(SyntheticEventPolicy.AGGREGATE_TYPE); }
        @Override public void validate(SyncEvent event, NodeIdentity expectedOrigin) {
            SyntheticEventPolicy.validate(event, expectedOrigin);
        }
    };

    /** A composed policy accepts an event only when exactly the policy owning its aggregate type accepts it. */
    static EventPolicy compose(List<EventPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        var types = new HashSet<String>();
        for (EventPolicy policy : policies) {
            for (String type : policy.aggregateTypes()) {
                if (!types.add(type)) throw new IllegalArgumentException("Aggregate type is owned by two event policies");
            }
        }
        var copy = List.copyOf(policies);
        var all = Set.copyOf(types);
        return new EventPolicy() {
            @Override public Set<String> aggregateTypes() { return all; }
            @Override public Map<String, Set<Integer>> supportedSchemaVersions() {
                var versions = new HashMap<String, Set<Integer>>();
                for (EventPolicy policy : copy) {
                    var declared = policy.supportedSchemaVersions();
                    if (!declared.keySet().equals(policy.aggregateTypes())) {
                        throw new IllegalArgumentException("Reader capabilities must match owned aggregate types");
                    }
                    versions.putAll(EventSchemas.copy(declared));
                }
                return Map.copyOf(versions);
            }
            @Override public void validate(SyncEvent event, NodeIdentity expectedOrigin) {
                owner(event).validate(event, expectedOrigin);
            }
            @Override public OutboundDecision outbound(SyncEvent event) {
                return owner(event).outbound(event);
            }
            private EventPolicy owner(SyncEvent event) {
                for (EventPolicy policy : copy) {
                    if (policy.aggregateTypes().contains(event.aggregateType())) return policy;
                }
                throw new IllegalArgumentException("EVENT_TYPE_NOT_ALLOWED");
            }
        };
    }
}
