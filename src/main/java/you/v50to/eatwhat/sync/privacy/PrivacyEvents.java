package you.v50to.eatwhat.sync.privacy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.EventPolicy;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-setting causal privacy changes. Concurrent restrictive values win. */
public final class PrivacyEvents implements EventPolicy {
    public static final String AGGREGATE = "privacy.operation";
    public static final String EVENT = "privacy.changed";
    private static final Set<String> FIELDS = Set.of("following", "follower");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConsentStore consent;

    public PrivacyEvents(ConsentStore consent) { this.consent = consent; }

    public record Witness(UUID id, Map<String, Long> vector) { }
    public record BridgeState(boolean value, Map<String, Long> vector,
                              Map<String, Long> falseContext, List<Witness> trueWitnesses) { }
    public record Operation(UUID subject, long epoch, String dotSite, long dotCounter,
                            Map<String, Boolean> changes, Map<String, Map<String, Long>> contexts,
                            Map<String, BridgeState> stateBridge) {
        public Operation(UUID subject, long epoch, String dotSite, long dotCounter,
                         Map<String, Boolean> changes, Map<String, Map<String, Long>> contexts) {
            this(subject, epoch, dotSite, dotCounter, changes, contexts, Map.of());
        }
    }

    public static String payload(Operation op) { return EventCodec.canonicalJson(JSON.writeValueAsString(op)); }

    public static Operation parse(String payload) {
        try {
            JsonNode root = JSON.readTree(EventCodec.canonicalJson(payload));
            Set<String> expected = Set.of("subject", "epoch", "dotSite", "dotCounter", "changes", "contexts");
            Set<String> actual = Set.copyOf(root.propertyNames());
            if (!actual.equals(expected) && !actual.equals(withBridge(expected)) || !root.path("subject").isString()
                    || !root.path("epoch").isIntegralNumber() || !root.path("dotSite").isString()
                    || !root.path("dotCounter").isIntegralNumber() || !root.path("changes").isObject()
                    || !root.path("contexts").isObject()) invalid();
            UUID subject = UUID.fromString(root.path("subject").stringValue());
            SyncEvent.requireUuid(subject, "privacy subject");
            long epoch = root.path("epoch").longValue(), counter = root.path("dotCounter").longValue();
            String dot = root.path("dotSite").stringValue();
            SyncEvent.requireName(dot, "privacy dot", 64);
            if (epoch < 0 || counter < 1 || counter > VersionVector.MAX_COUNTER
                    || !FIELDS.containsAll(root.path("changes").propertyNames())
                    || !Set.copyOf(root.path("changes").propertyNames()).equals(Set.copyOf(root.path("contexts").propertyNames()))) invalid();
            Map<String, BridgeState> bridge = root.has("stateBridge") ? parseBridge(root.path("stateBridge")) : Map.of();
            if (root.path("changes").isEmpty() && bridge.isEmpty()) invalid();
            for (String field : root.path("changes").propertyNames()) {
                if (!root.path("changes").get(field).isBoolean() || !root.path("contexts").get(field).isObject()) invalid();
                var vector = EventCodec.vectorFromJson(root.path("contexts").get(field).toString());
                if (vector.counters().getOrDefault(dot, 0L) != counter - 1) invalid();
            }
            var types = JSON.getTypeFactory();
            var stringType = types.constructType(String.class);
            Map<String, Boolean> changes = JSON.convertValue(root.path("changes"),
                    types.constructMapType(Map.class, stringType, types.constructType(Boolean.class)));
            var vectorType = types.constructMapType(Map.class, stringType, types.constructType(Long.class));
            Map<String, Map<String, Long>> contexts = JSON.convertValue(root.path("contexts"),
                    types.constructMapType(Map.class, stringType, vectorType));
            return new Operation(subject, epoch, dot, counter, Map.copyOf(changes), Map.copyOf(contexts), bridge);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_PRIVACY_EVENT");
        }
    }

    private static Set<String> withBridge(Set<String> keys) {
        var result = new java.util.HashSet<>(keys);
        result.add("stateBridge");
        return Set.copyOf(result);
    }

    private static Map<String, BridgeState> parseBridge(JsonNode node) {
        if (!node.isObject() || !FIELDS.containsAll(node.propertyNames())) invalid();
        var result = new java.util.LinkedHashMap<String, BridgeState>();
        for (String field : node.propertyNames()) {
            JsonNode state = node.get(field);
            if (!state.isObject() || !Set.of("value", "vector", "falseContext", "trueWitnesses").equals(Set.copyOf(state.propertyNames()))
                    || !state.path("value").isBoolean() || !state.path("vector").isObject()
                    || !state.path("falseContext").isObject() || !state.path("trueWitnesses").isArray()) invalid();
            Map<String, Long> vector = EventCodec.vectorFromJson(state.path("vector").toString()).counters();
            Map<String, Long> falseContext = EventCodec.vectorFromJson(state.path("falseContext").toString()).counters();
            if (!covers(vector, falseContext)) invalid();
            var witnesses = new java.util.ArrayList<Witness>();
            if (state.path("trueWitnesses").size() > VersionVector.MAX_SITES) invalid();
            var ids = new java.util.HashSet<UUID>();
            for (JsonNode witness : state.path("trueWitnesses")) {
                if (!witness.isObject() || !Set.of("id", "vector").equals(Set.copyOf(witness.propertyNames()))
                        || !witness.path("id").isString() || !witness.path("vector").isObject()) invalid();
                UUID id = UUID.fromString(witness.path("id").stringValue());
                if (!ids.add(id)) invalid();
                Map<String, Long> witnessVector = EventCodec.vectorFromJson(witness.path("vector").toString()).counters();
                if (!covers(vector, witnessVector)) invalid();
                witnesses.add(new Witness(id, witnessVector));
            }
            boolean expectedValue = falseContext.isEmpty() || witnesses.stream()
                    .anyMatch(witness -> covers(witness.vector(), falseContext));
            if (state.path("value").booleanValue() != expectedValue) invalid();
            result.put(field, new BridgeState(expectedValue, vector, falseContext, List.copyOf(witnesses)));
        }
        return Map.copyOf(result);
    }

    private static boolean covers(Map<String, Long> vector, Map<String, Long> observed) {
        VersionVector.Relation relation = new VersionVector(vector).compare(new VersionVector(observed));
        return relation == VersionVector.Relation.AFTER || relation == VersionVector.Relation.EQUAL;
    }

    private static void invalid() { throw new IllegalArgumentException("INVALID_PRIVACY_EVENT"); }

    @Override public Set<String> aggregateTypes() { return Set.of(AGGREGATE); }
    @Override public OutboundDecision outbound(SyncEvent event) {
        if (consent == null) return OutboundDecision.WAIT;
        Operation op = parse(event.payloadJson());
        var decision = consent.outboundAuthorization(op.subject(), op.epoch(), op.subject(), op.epoch());
        if (decision.blocked()) return OutboundDecision.POLICY_BLOCKED;
        return decision.allowed() ? OutboundDecision.ALLOW : OutboundDecision.WAIT;
    }
    @Override public void validate(SyncEvent event, NodeIdentity origin) {
        if (event == null || origin == null || !AGGREGATE.equals(event.aggregateType()) || !EVENT.equals(event.eventType())
                || event.schemaVersion() != 1 || !origin.siteId().equals(event.originSite())
                || !origin.instanceId().equals(event.originInstanceId()) || !event.eventId().equals(event.aggregateId())
                || !event.baseVersion().equals(VersionVector.empty())
                || !event.nextVersion().equals(VersionVector.empty().increment(event.originSite()))) invalid();
        Operation op = parse(event.payloadJson());
        if (!op.dotSite().equals(event.originSite())
                || !op.subject().equals(event.actorId())
                || !event.dependencies().equals(List.of(new SyncEvent.Dependency("users", op.subject())))) invalid();
    }
}
