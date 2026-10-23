package you.v50to.eatwhat.sync.browse;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;

import java.util.*;

/** Private immutable observations, never derived profiles, URLs or request bodies. */
public final class BrowseEvents implements EventPolicy {
    public static final String AGGREGATE = "browse.observation", EVENT = "browse.observed";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConsentStore consent;

    public BrowseEvents(ConsentStore consent) { this.consent = consent; }

    public record Observation(UUID visitId, UUID actor, UUID owner, String targetType, UUID target,
                              long actorEpoch, long ownerEpoch, long viewedAtMicros) {
        public List<SyncEvent.Dependency> dependencies() {
            var dependencies = new HashSet<SyncEvent.Dependency>();
            dependencies.add(new SyncEvent.Dependency("users", actor));
            dependencies.add(new SyncEvent.Dependency("users", owner));
            dependencies.add(new SyncEvent.Dependency(targetType.equals("food") ? "foods" : "restaurant", target));
            return dependencies.stream().sorted(Comparator.comparing(SyncEvent.Dependency::aggregateType)
                    .thenComparing(d -> d.aggregateId().toString())).toList();
        }
        @Override public String toString() { return "Observation[redacted]"; }
    }

    public static String payload(Observation observation) {
        return EventCodec.canonicalJson(JSON.writeValueAsString(observation));
    }

    public static Observation parse(String payload) {
        try {
            var node = JSON.readTree(EventCodec.canonicalJson(payload));
            if (!node.isObject() || !Set.copyOf(node.propertyNames()).equals(Set.of("visitId", "actor", "owner",
                    "targetType", "target", "actorEpoch", "ownerEpoch", "viewedAtMicros"))) invalid();
            for (String field : List.of("visitId", "actor", "owner", "target")) {
                var value = node.get(field);
                if (!value.isString()) invalid();
                UUID uuid = UUID.fromString(value.stringValue());
                SyncEvent.requireUuid(uuid, "browse identity");
                if (!uuid.toString().equals(value.stringValue())) invalid();
            }
            for (String field : List.of("actorEpoch", "ownerEpoch", "viewedAtMicros")) {
                var value = node.get(field);
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1
                        || value.longValue() > VersionVector.MAX_COUNTER) invalid();
            }
            if (!node.path("targetType").isString()
                    || !Set.of("food", "restaurant").contains(node.path("targetType").stringValue())) invalid();
            return JSON.readValue(node.toString(), Observation.class);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("INVALID_BROWSE_EVENT"); }
    }

    private static void invalid() { throw new IllegalArgumentException("INVALID_BROWSE_EVENT"); }
    @Override public Set<String> aggregateTypes() { return Set.of(AGGREGATE); }
    @Override public void validate(SyncEvent event, NodeIdentity origin) {
        if (!event.originSite().equals(origin.siteId()) || !event.originInstanceId().equals(origin.instanceId())
                || !AGGREGATE.equals(event.aggregateType()) || !EVENT.equals(event.eventType())
                || event.schemaVersion() != 1 || !event.eventId().equals(event.aggregateId())
                || !event.baseVersion().equals(VersionVector.empty())) invalid();
        var observation = parse(event.payloadJson());
        if (!observation.actor().equals(event.actorId()) || !observation.dependencies().equals(event.dependencies())) invalid();
    }
    @Override public OutboundDecision outbound(SyncEvent event) {
        var observation = parse(event.payloadJson());
        if (consent == null) return OutboundDecision.WAIT;
        var authorization = consent.outboundAuthorization(observation.actor(), observation.actorEpoch(),
                observation.owner(), observation.ownerEpoch());
        return authorization.blocked() ? OutboundDecision.POLICY_BLOCKED
                : authorization.allowed() ? OutboundDecision.ALLOW : OutboundDecision.WAIT;
    }
}
