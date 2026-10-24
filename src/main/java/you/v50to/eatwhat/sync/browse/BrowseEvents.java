package you.v50to.eatwhat.sync.browse;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;

import java.util.*;

/** Private immutable observations, never derived profiles, URLs or request bodies. */
public final class BrowseEvents implements EventPolicy {
    public static final String AGGREGATE = "browse.observation", EVENT = "browse.observed", CLEAR = "browse.cleared";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConsentStore consent;

    public BrowseEvents(ConsentStore consent) { this.consent = consent; }

    public record Observation(UUID visitId, UUID actor, UUID owner, String targetType, UUID target,
                              long actorEpoch, long ownerEpoch, long viewedAtMicros, Map<String, Long> clearContext) {
        public Observation { clearContext = clearContext == null ? Map.of() : new VersionVector(clearContext).counters(); }
        public Observation(UUID visitId, UUID actor, UUID owner, String targetType, UUID target,
                           long actorEpoch, long ownerEpoch, long viewedAtMicros) {
            this(visitId, actor, owner, targetType, target, actorEpoch, ownerEpoch, viewedAtMicros, Map.of());
        }
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
        var value = new LinkedHashMap<String, Object>();
        value.put("visitId", observation.visitId()); value.put("actor", observation.actor());
        value.put("owner", observation.owner()); value.put("targetType", observation.targetType());
        value.put("target", observation.target()); value.put("actorEpoch", observation.actorEpoch());
        value.put("ownerEpoch", observation.ownerEpoch()); value.put("viewedAtMicros", observation.viewedAtMicros());
        if (!observation.clearContext().isEmpty()) value.put("clearContext", observation.clearContext());
        return EventCodec.canonicalJson(JSON.writeValueAsString(value));
    }

    public static Observation parse(String payload) {
        try {
            var node = JSON.readTree(EventCodec.canonicalJson(payload));
            var fields = new HashSet<>(Set.of("visitId", "actor", "owner", "targetType", "target", "actorEpoch", "ownerEpoch", "viewedAtMicros"));
            if (node.has("clearContext")) {
                fields.add("clearContext");
                EventCodec.vectorFromJson(node.get("clearContext").toString());
            }
            if (!node.isObject() || !Set.copyOf(node.propertyNames()).equals(fields)) invalid();
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

    public record Clear(UUID actor, long actorEpoch, String targetType, Map<String, Long> context) {
        @Override public String toString() { return "Clear[redacted]"; }
    }
    public static String payload(Clear clear) { return EventCodec.canonicalJson(JSON.writeValueAsString(clear)); }
    public static Clear parseClear(String payload) {
        try {
            var node = JSON.readTree(EventCodec.canonicalJson(payload));
            if (!node.isObject() || !Set.copyOf(node.propertyNames()).equals(Set.of("actor", "actorEpoch", "targetType", "context"))
                    || !node.path("actor").isString() || !node.path("actorEpoch").isIntegralNumber()
                    || !node.path("actorEpoch").canConvertToLong() || !node.path("targetType").isString()) invalid();
            UUID actor = UUID.fromString(node.path("actor").stringValue()); SyncEvent.requireUuid(actor, "browse actor");
            long epoch = node.path("actorEpoch").longValue();
            if (!actor.toString().equals(node.path("actor").stringValue()) || epoch < 1 || epoch > VersionVector.MAX_COUNTER) invalid();
            BrowseCommands.table(node.path("targetType").stringValue());
            var context = EventCodec.vectorFromJson(node.path("context").toString());
            if (context.counters().isEmpty()) invalid();
            return new Clear(actor, epoch, node.path("targetType").stringValue(), context.counters());
        } catch (RuntimeException failure) { throw new IllegalArgumentException("INVALID_BROWSE_CLEAR_EVENT"); }
    }

    private static void invalid() { throw new IllegalArgumentException("INVALID_BROWSE_EVENT"); }
    @Override public Set<String> aggregateTypes() { return Set.of(AGGREGATE); }
    @Override public Map<String, Set<Integer>> supportedSchemaVersions() { return Map.of(AGGREGATE, Set.of(1, 2)); }
    @Override public void validate(SyncEvent event, NodeIdentity origin) {
        if (!event.originSite().equals(origin.siteId()) || !event.originInstanceId().equals(origin.instanceId())
                || !AGGREGATE.equals(event.aggregateType()) || !event.eventId().equals(event.aggregateId())
                || !event.baseVersion().equals(VersionVector.empty())
                || !event.nextVersion().equals(VersionVector.empty().increment(event.originSite()))) invalid();
        if (CLEAR.equals(event.eventType())) {
            Clear clear = parseClear(event.payloadJson());
            if (event.schemaVersion()!=2 || !clear.actor().equals(event.actorId())
                    || !event.dependencies().equals(List.of(new SyncEvent.Dependency("users",clear.actor())))) invalid();
        } else {
            if (!EVENT.equals(event.eventType())) invalid();
            var observation = parse(event.payloadJson());
            if (event.schemaVersion()!=(observation.clearContext().isEmpty()?1:2)
                    || !observation.actor().equals(event.actorId()) || !observation.dependencies().equals(event.dependencies())) invalid();
        }
    }
    @Override public OutboundDecision outbound(SyncEvent event) {
        if (consent == null) return OutboundDecision.WAIT;
        UUID actor, owner; long actorEpoch, ownerEpoch;
        if (CLEAR.equals(event.eventType())) {
            var clear = parseClear(event.payloadJson()); actor=owner=clear.actor(); actorEpoch=ownerEpoch=clear.actorEpoch();
        } else {
            var observation = parse(event.payloadJson()); actor=observation.actor(); owner=observation.owner();
            actorEpoch=observation.actorEpoch(); ownerEpoch=observation.ownerEpoch();
        }
        var authorization = consent.outboundAuthorization(actor, actorEpoch, owner, ownerEpoch);
        return authorization.blocked() ? OutboundDecision.POLICY_BLOCKED
                : authorization.allowed() ? OutboundDecision.ALLOW : OutboundDecision.WAIT;
    }
}
