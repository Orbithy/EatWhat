package you.v50to.eatwhat.sync.profile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.EventPolicy;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/** Strict field allowlist and transport gate for independently versioned profile operations. */
public final class ProfileEvents implements EventPolicy {
    public static final String AGGREGATE = "profile.operation";
    public static final String EVENT = "profile.changed";
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> FIELDS = Set.of("avatar", "gender", "birthday", "signature", "hometown");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConsentStore consent;

    public ProfileEvents(ConsentStore consent) { this.consent = consent; }

    public record Operation(UUID subject, long epoch, UUID baselineId, String baselineOrigin, String baselineHash,
                            Map<String, Object> baseline, Map<String, Object> changes,
                            Map<String, Map<String, Long>> contexts, String dotSite, long dotCounter,
                            Resolution resolution, Map<String, List<Head>> headBridge,
                            Map<String, ResolutionProof> resolutionBridge) {
        public Operation(UUID subject, long epoch, UUID baselineId, String baselineOrigin, String baselineHash,
                         Map<String, Object> baseline, Map<String, Object> changes,
                         Map<String, Map<String, Long>> contexts, String dotSite, long dotCounter) {
            this(subject, epoch, baselineId, baselineOrigin, baselineHash, baseline, changes, contexts,
                    dotSite, dotCounter, null, Map.of(), Map.of());
        }
        public Operation(UUID subject, long epoch, UUID baselineId, String baselineOrigin, String baselineHash,
                         Map<String, Object> baseline, Map<String, Object> changes,
                         Map<String, Map<String, Long>> contexts, String dotSite, long dotCounter,
                         Resolution resolution, Map<String, List<Head>> headBridge) {
            this(subject, epoch, baselineId, baselineOrigin, baselineHash, baseline, changes, contexts,
                    dotSite, dotCounter, resolution, headBridge, Map.of());
        }
        public Operation(UUID subject, long epoch, UUID baselineId, String baselineOrigin, String baselineHash,
                         Map<String, Object> baseline, Map<String, Object> changes,
                         Map<String, Map<String, Long>> contexts, String dotSite, long dotCounter,
                         Resolution resolution) {
            this(subject, epoch, baselineId, baselineOrigin, baselineHash, baseline, changes, contexts,
                    dotSite, dotCounter, resolution, Map.of(), Map.of());
        }
    }

    public record Resolution(UUID conflictId, long expectedRevision, UUID selectedHeadId, List<UUID> expectedHeads) { }
    public record ResolutionProof(UUID eventId, UUID selectedHeadId, List<UUID> expectedHeads) { }
    public record Head(UUID id, Object value, Map<String, Long> vector) { }

    public static String payload(Operation operation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subject", operation.subject()); payload.put("epoch", operation.epoch());
        payload.put("baselineId", operation.baselineId()); payload.put("baselineOrigin", operation.baselineOrigin());
        payload.put("baselineHash", operation.baselineHash()); payload.put("baseline", operation.baseline());
        payload.put("changes", operation.changes()); payload.put("contexts", operation.contexts());
        payload.put("dotSite", operation.dotSite()); payload.put("dotCounter", operation.dotCounter());
        if (operation.resolution() != null) payload.put("resolution", operation.resolution());
        if (!operation.headBridge().isEmpty()) payload.put("headBridge", operation.headBridge());
        if (!operation.resolutionBridge().isEmpty()) payload.put("resolutionBridge", operation.resolutionBridge());
        return EventCodec.canonicalJson(JSON.writeValueAsString(payload));
    }

    public static Operation parse(String payload) {
        try {
            JsonNode root = JSON.readTree(EventCodec.canonicalJson(payload));
            Set<String> baseKeys = Set.of("subject", "epoch", "baselineId", "baselineOrigin", "baselineHash", "baseline", "changes", "contexts", "dotSite", "dotCounter");
            Set<String> actualKeys = Set.copyOf(root.propertyNames());
            var expectedKeys = new java.util.HashSet<>(baseKeys);
            if (root.has("resolution")) expectedKeys.add("resolution");
            if (root.has("headBridge")) expectedKeys.add("headBridge");
            if (root.has("resolutionBridge")) expectedKeys.add("resolutionBridge");
            if (!actualKeys.equals(expectedKeys)) invalid();
            if (!root.isObject() || !root.path("subject").isString() || !root.path("baselineId").isString()
                    || !root.path("baselineOrigin").isString() || !root.path("baselineHash").isString() || !root.path("dotSite").isString()
                    || !root.path("epoch").isIntegralNumber() || !root.path("dotCounter").isIntegralNumber()
                    || !root.path("baseline").isObject() || !root.path("changes").isObject()
                    || !root.path("contexts").isObject()) invalid();
            UUID subject = uuid(root.path("subject").stringValue());
            UUID baselineId = uuid(root.path("baselineId").stringValue());
            long epoch = root.path("epoch").longValue();
            long counter = root.path("dotCounter").longValue();
            String origin = root.path("baselineOrigin").stringValue();
            String baselineHash = root.path("baselineHash").stringValue();
            String dot = root.path("dotSite").stringValue();
            SyncEvent.requireName(origin, "baseline origin", 64);
            SyncEvent.requireName(dot, "dot site", 64);
            if (!baselineHash.matches("[0-9a-f]{64}")) invalid();
            if (epoch < 0 || counter < 1 || counter > VersionVector.MAX_COUNTER
                    || !FIELDS.containsAll(root.path("baseline").propertyNames())
                    || root.path("baseline").size() != FIELDS.size()
                    || !FIELDS.containsAll(root.path("changes").propertyNames())) invalid();
            validateValues(root.path("baseline"));
            validateValues(root.path("changes"));
            for (String field : root.path("changes").propertyNames()) {
                if (!root.path("contexts").has(field) || !root.path("contexts").get(field).isObject()) invalid();
                EventCodec.vectorFromJson(root.path("contexts").get(field).toString());
            }
            if (!Set.copyOf(root.path("changes").propertyNames()).equals(Set.copyOf(root.path("contexts").propertyNames()))) invalid();
            Map<String, Object> baseline = JSON.convertValue(root.path("baseline"),
                    JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            Map<String, Object> changes = JSON.convertValue(root.path("changes"),
                    JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            var longType = JSON.getTypeFactory().constructType(Long.class);
            var stringType = JSON.getTypeFactory().constructType(String.class);
            var vectorType = JSON.getTypeFactory().constructMapType(Map.class, stringType, longType);
            var contextsType = JSON.getTypeFactory().constructMapType(Map.class, stringType, vectorType);
            Map<String, Map<String, Long>> contexts = JSON.convertValue(root.path("contexts"), contextsType);
            for (Map<String, Long> context : contexts.values()) {
                if (context.getOrDefault(dot, 0L) != counter - 1) invalid();
            }
            Resolution resolution = root.has("resolution") ? parseResolution(root.path("resolution")) : null;
            Map<String, List<Head>> headBridge = root.has("headBridge") ? parseHeadBridge(root.path("headBridge")) : Map.of();
            Map<String, ResolutionProof> resolutionBridge = root.has("resolutionBridge")
                    ? parseResolutionBridge(root.path("resolutionBridge")) : Map.of();
            if (root.path("changes").isEmpty() && headBridge.isEmpty()) invalid();
            if ((!headBridge.isEmpty() || !resolutionBridge.isEmpty()) && !root.path("changes").isEmpty()
                    || !resolutionBridge.isEmpty() && headBridge.isEmpty()
                    || resolution != null && (!headBridge.isEmpty() || !resolutionBridge.isEmpty())
                    || headBridge.keySet().stream().anyMatch(root.path("changes").propertyNames()::contains)) invalid();
            return new Operation(subject, epoch, baselineId, origin, baselineHash, immutableAllowNull(baseline),
                    immutableAllowNull(changes), Collections.unmodifiableMap(new LinkedHashMap<>(contexts)), dot, counter,
                    resolution, Collections.unmodifiableMap(new LinkedHashMap<>(headBridge)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(resolutionBridge)));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_PROFILE_EVENT");
        }
    }

    private static UUID uuid(String value) {
        UUID uuid = UUID.fromString(value);
        SyncEvent.requireUuid(uuid, "profile identity");
        if (!uuid.toString().equals(value)) invalid();
        return uuid;
    }

    private static Set<String> withResolution(Set<String> keys) {
        var result = new java.util.HashSet<>(keys); result.add("resolution"); return Set.copyOf(result);
    }

    private static Set<String> withHeadBridge(Set<String> keys) {
        var result = new java.util.HashSet<>(keys); result.add("headBridge"); return Set.copyOf(result);
    }

    private static Set<String> withResolutionBridge(Set<String> keys) {
        var result = new java.util.HashSet<>(keys); result.add("resolutionBridge"); return Set.copyOf(result);
    }

    private static Map<String, ResolutionProof> parseResolutionBridge(JsonNode node) {
        if (!node.isObject() || node.isEmpty() || !FIELDS.containsAll(node.propertyNames())) invalid();
        var result = new LinkedHashMap<String, ResolutionProof>();
        for (String group : node.propertyNames()) {
            JsonNode proof = node.get(group);
            if (!proof.isObject() || !Set.of("eventId", "selectedHeadId", "expectedHeads")
                    .equals(Set.copyOf(proof.propertyNames())) || !proof.path("eventId").isString()
                    || !proof.path("selectedHeadId").isString() || !proof.path("expectedHeads").isArray()
                    || proof.path("expectedHeads").isEmpty()) invalid();
            List<UUID> expected = new ArrayList<>();
            for (JsonNode head : proof.path("expectedHeads")) {
                if (!head.isString()) invalid();
                expected.add(uuid(head.stringValue()));
            }
            if (expected.stream().distinct().count() != expected.size()) invalid();
            result.put(group, new ResolutionProof(uuid(proof.path("eventId").stringValue()),
                    uuid(proof.path("selectedHeadId").stringValue()), expected.stream().sorted().toList()));
        }
        return result;
    }

    private static Map<String, List<Head>> parseHeadBridge(JsonNode node) {
        if (!node.isObject() || node.isEmpty() || !FIELDS.containsAll(node.propertyNames())) invalid();
        var result = new LinkedHashMap<String, List<Head>>();
        var longType = JSON.getTypeFactory().constructType(Long.class);
        var vectorType = JSON.getTypeFactory().constructMapType(Map.class,
                JSON.getTypeFactory().constructType(String.class), longType);
        for (String group : node.propertyNames()) {
            JsonNode heads = node.get(group);
            if (!heads.isArray() || heads.isEmpty()) invalid();
            var parsed = new ArrayList<Head>();
            var ids = new java.util.HashSet<UUID>();
            for (JsonNode head : heads) {
                if (!head.isObject() || !Set.of("id", "value", "vector").equals(Set.copyOf(head.propertyNames()))
                        || !head.path("id").isString() || !head.path("vector").isObject()) invalid();
                UUID id = uuid(head.path("id").stringValue());
                if (!ids.add(id)) invalid();
                var fieldValue = JSON.createObjectNode();
                fieldValue.set(group, head.get("value"));
                validateValues(fieldValue);
                Map<String, Long> vector = JSON.convertValue(head.path("vector"), vectorType);
                new VersionVector(vector);
                parsed.add(new Head(id, JSON.convertValue(head.get("value"), Object.class), Map.copyOf(vector)));
            }
            result.put(group, List.copyOf(parsed));
        }
        return result;
    }

    private static Resolution parseResolution(JsonNode node) {
        Set<String> expected = Set.of("conflictId", "expectedRevision", "selectedHeadId", "expectedHeads");
        if (!node.isObject() || !Set.copyOf(node.propertyNames()).equals(expected)
                || !node.path("conflictId").isString() || !node.path("selectedHeadId").isString()
                || !node.path("expectedRevision").isIntegralNumber() || node.path("expectedRevision").longValue() < 1
                || !node.path("expectedHeads").isArray() || node.path("expectedHeads").isEmpty()) invalid();
        UUID conflict = uuid(node.path("conflictId").stringValue());
        UUID selected = uuid(node.path("selectedHeadId").stringValue());
        List<UUID> heads = new ArrayList<>();
        for (JsonNode item : node.path("expectedHeads")) {
            if (!item.isString()) invalid();
            heads.add(uuid(item.stringValue()));
        }
        List<UUID> sorted = heads.stream().distinct().sorted().toList();
        if (sorted.size() != heads.size() || !sorted.equals(heads) || !heads.contains(selected)) invalid();
        return new Resolution(conflict, node.path("expectedRevision").longValue(), selected, List.copyOf(heads));
    }

    private static Map<String, Object> immutableAllowNull(Map<String, Object> input) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static void validateValues(JsonNode values) {
        for (String field : values.propertyNames()) {
            JsonNode value = values.get(field);
            switch (field) {
                case "avatar" -> nullableText(value, 255);
                case "gender" -> {
                    if (!value.isNull() && (!value.isString()
                            || !Set.of("male", "female", "other").contains(value.stringValue()))) invalid();
                }
                case "birthday" -> {
                    if (!value.isNull()) {
                        if (!value.isString()) invalid();
                        try { LocalDate.parse(value.stringValue()); }
                        catch (RuntimeException malformed) { invalid(); }
                    }
                }
                case "signature" -> nullableText(value, 255);
                case "hometown" -> {
                    if (!value.isObject() || !Set.of("provinceId", "cityId").equals(Set.copyOf(value.propertyNames()))) invalid();
                    JsonNode province = value.get("provinceId"), city = value.get("cityId");
                    if ((!province.isNull() && (!province.isIntegralNumber() || province.longValue() < 1 || province.longValue() > Integer.MAX_VALUE))
                            || (!city.isNull() && (!city.isIntegralNumber() || city.longValue() < 1 || city.longValue() > Integer.MAX_VALUE))
                            ) invalid();
                }
                default -> invalid();
            }
        }
    }

    private static void nullableText(JsonNode value, int limit) {
        // Preserve the legacy DTO contract: both avatar keys and signatures only had a
        // length bound; in particular, multiline signatures are valid profile values.
        if (!value.isNull() && (!value.isString() || value.stringValue().length() > limit)) invalid();
    }

    private static void invalid() { throw new IllegalArgumentException("INVALID_PROFILE_EVENT"); }

    public static Operation operation(SyncEvent event) { return event.schemaVersion()==2 ? ProfileV2Envelope.parse(event.payloadJson()).operation() : parse(event.payloadJson()); }
    @Override public Map<String,Set<Integer>> supportedSchemaVersions() { return Map.of(AGGREGATE,Set.of(1,2)); }
    @Override public Set<String> aggregateTypes() { return Set.of(AGGREGATE); }

    @Override public OutboundDecision outbound(SyncEvent event) {
        if (consent == null) return OutboundDecision.WAIT;
        Operation operation = operation(event);
        ConsentStore.OutboundAuthorization authorization = consent.outboundAuthorization(
                operation.subject(), operation.epoch(), operation.subject(), operation.epoch());
        if (authorization.blocked()) return OutboundDecision.POLICY_BLOCKED;
        return authorization.allowed() ? OutboundDecision.ALLOW : OutboundDecision.WAIT;
    }

    public static List<SyncEvent.Dependency> dependencies(Operation operation,UUID actor) {
        var dependencies=new ArrayList<SyncEvent.Dependency>(); dependencies.add(new SyncEvent.Dependency("users",operation.subject()));
        if(!actor.equals(operation.subject())) dependencies.add(new SyncEvent.Dependency("users",actor));
        return List.copyOf(dependencies);
    }
    @Override public void validate(SyncEvent event, NodeIdentity origin) {
        if (event == null || origin == null || !AGGREGATE.equals(event.aggregateType())
                || !EVENT.equals(event.eventType()) || (event.schemaVersion() != SCHEMA_VERSION && event.schemaVersion()!=2)
                || !origin.siteId().equals(event.originSite()) || !origin.instanceId().equals(event.originInstanceId())
                || !event.eventId().equals(event.aggregateId()) || !event.baseVersion().equals(VersionVector.empty())
                || !event.nextVersion().equals(VersionVector.empty().increment(event.originSite()))) invalid();
        Operation operation = operation(event);
        if (!operation.dotSite().equals(event.originSite())
                || !(event.schemaVersion()==2 && operation.resolution()!=null
                    ? ProfileV2Envelope.parse(event.payloadJson()).authorization().subjectId().equals(event.actorId())
                    : operation.subject().equals(event.actorId()))
                || operation.dotCounter() < 1 || !event.dependencies().equals(
                dependencies(operation,event.actorId()))) invalid();
    }
}
