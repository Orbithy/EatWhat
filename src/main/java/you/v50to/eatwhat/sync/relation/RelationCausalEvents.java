package you.v50to.eatwhat.sync.relation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Epoch-independent logical relation actions and compact causal-state bridges. */
public final class RelationCausalEvents {
    public static final String ACTION = "relation.action.v2";
    public static final String BRIDGE = "relation.state.v2";
    public static final int SCHEMA_VERSION = 2;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_PAYLOAD_BYTES = 128 * 1024;

    private RelationCausalEvents() { }

    public record Dot(String stream, long counter, UUID eventId, Map<String, Long> context) { }
    public record Action(UUID naturalId, RelationKind kind, UUID actor, UUID target, UUID owner, UUID tag,
                         long actorEpoch, long ownerEpoch, long rowId, UUID rowGid, String rowOrigin,
                         String stream, long counter, Map<String, Long> context, boolean adding,
                         RelationEvents.Operation v1Evidence, Bridge state) { }
    public record Bridge(UUID naturalId, RelationKind kind, UUID actor, UUID target, UUID owner, UUID tag,
                         long actorEpoch, long ownerEpoch, long rowId, UUID rowGid, String rowOrigin,
                         Map<String, Long> seen, List<Dot> addWitnesses, List<Dot> removeFrontier,
                         String blockedReason) { }

    public static String nativeStream(String site) {
        SyncEvent.requireName(site, "relation site", 64);
        return "native|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(site.getBytes(StandardCharsets.UTF_8));
    }

    public static String legacyStream(UUID relationId, String site) {
        SyncEvent.requireUuid(relationId, "legacy relation id");
        SyncEvent.requireName(site, "legacy relation site", 64);
        return "legacy|" + relationId + "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(site.getBytes(StandardCharsets.UTF_8));
    }

    public static String baselineStream(UUID rowGid) {
        SyncEvent.requireUuid(rowGid, "baseline row id");
        return "baseline|" + rowGid;
    }

    public static String payload(Action action) {
        String payload = EventCodec.canonicalJson(JSON.writeValueAsString(action));
        checkPayloadSize(payload);
        return payload;
    }

    public static String payload(Bridge bridge) {
        String payload = EventCodec.canonicalJson(JSON.writeValueAsString(bridge));
        checkPayloadSize(payload);
        return payload;
    }

    public static Action parseAction(String payload) {
        try {
            JsonNode root = JSON.readTree(EventCodec.canonicalJson(payload));
            exact(root, Action.class);
            RelationEvents.parse(root.path("v1Evidence").toString());
            var action = JSON.readValue(root.toString(), Action.class);
            validateIdentity(action.naturalId(), action.kind(), action.actor(), action.target(), action.owner(),
                    action.tag(), action.actorEpoch(), action.ownerEpoch(), action.rowId(), action.rowGid(),
                    action.rowOrigin());
            validateStream(action.stream());
            validateVector(action.context());
            if (action.counter() < 1 || action.counter() > VersionVector.MAX_COUNTER
                    || action.context().getOrDefault(action.stream(), 0L) + 1 != action.counter()
                    || action.v1Evidence() == null || action.v1Evidence().adding() != action.adding()
                    || action.v1Evidence().kind() != action.kind()
                    || action.v1Evidence().actorEpoch() != Math.max(1, action.actorEpoch())
                    || action.v1Evidence().ownerEpoch() != Math.max(1, action.ownerEpoch())
                    || !action.v1Evidence().actor().equals(action.actor())
                    || !action.v1Evidence().target().equals(action.target())
                    || !action.v1Evidence().owner().equals(action.owner())
                    || !Objects.equals(action.v1Evidence().tag(), action.tag())
                    || !action.v1Evidence().rowGid().equals(action.rowGid())
                    || action.v1Evidence().rowId() != action.rowId()
                    || !action.v1Evidence().rowOrigin().equals(action.rowOrigin())
                    || action.state() == null) invalid();
            Bridge state = action.state();
            validateIdentity(state.naturalId(), state.kind(), state.actor(), state.target(), state.owner(),
                    state.tag(), state.actorEpoch(), state.ownerEpoch(), state.rowId(), state.rowGid(), state.rowOrigin());
            if (!sameIdentity(action, state) || state.blockedReason() != null) invalid();
            validateVector(state.seen());
            if (state.addWitnesses() == null || state.removeFrontier() == null
                    || state.addWitnesses().size() > 256 || state.removeFrontier().size() > 256) invalid();
            validateDots(state.addWitnesses(), state.seen());
            validateDots(state.removeFrontier(), state.seen());
            validateProofMaxima(state.naturalId(), state.seen(), state.addWitnesses(), state.removeFrontier());
            if (!covers(state.seen(), action.context())
                    || state.seen().getOrDefault(action.stream(), 0L) < action.counter()) invalid();
            Dot own = (action.adding() ? state.addWitnesses() : state.removeFrontier()).stream()
                    .filter(dot -> dot.stream().equals(action.stream()) && dot.counter() == action.counter())
                    .findFirst().orElse(null);
            if (own == null || !own.context().equals(action.context())) invalid();
            return action;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_RELATION_CAUSAL_EVENT");
        }
    }

    public static Bridge parseBridge(String payload) {
        try {
            JsonNode root = JSON.readTree(EventCodec.canonicalJson(payload));
            exact(root, Bridge.class);
            var bridge = JSON.readValue(root.toString(), Bridge.class);
            validateIdentity(bridge.naturalId(), bridge.kind(), bridge.actor(), bridge.target(), bridge.owner(),
                    bridge.tag(), bridge.actorEpoch(), bridge.ownerEpoch(), bridge.rowId(), bridge.rowGid(),
                    bridge.rowOrigin());
            validateVector(bridge.seen());
            if (bridge.addWitnesses() == null || bridge.removeFrontier() == null
                    || bridge.addWitnesses().size() > 256 || bridge.removeFrontier().size() > 256) invalid();
            validateDots(bridge.addWitnesses(), bridge.seen());
            validateDots(bridge.removeFrontier(), bridge.seen());
            validateProofMaxima(bridge.naturalId(), bridge.seen(), bridge.addWitnesses(), bridge.removeFrontier());
            for (Dot dot : bridge.addWitnesses()) if (!covers(bridge.seen(), dot.context())) invalid();
            for (Dot dot : bridge.removeFrontier()) if (!covers(bridge.seen(), dot.context())) invalid();
            if (bridge.blockedReason() != null) invalid();
            return bridge;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_RELATION_CAUSAL_EVENT");
        }
    }

    public static void validateEvent(SyncEvent event) {
        validateEvent(event, null);
    }

    public static void validateEvent(SyncEvent event, Set<String> allowedNativeSites) {
        if (!RelationEvents.AGGREGATE.equals(event.aggregateType()) || event.schemaVersion() != SCHEMA_VERSION
                || !event.eventId().equals(event.aggregateId()) || !event.baseVersion().equals(VersionVector.empty())
                || !event.nextVersion().equals(VersionVector.empty().increment(event.originSite()))) invalid();
        if (ACTION.equals(event.eventType())) {
            Action action = parseAction(event.payloadJson());
            validateNativeSites(action.state().seen(), event.originSite(), allowedNativeSites, true);
            if (!event.originSite().equals(action.v1Evidence().dotSite())
                    || !nativeStream(event.originSite()).equals(action.stream())
                    || (action.v1Evidence().notification() != null
                        && !event.originSite().equals(action.v1Evidence().notification().originSite()))
                    || (action.adding() ? action.state().addWitnesses() : action.state().removeFrontier()).stream()
                    .noneMatch(dot -> dot.eventId().equals(event.eventId())
                            && dot.stream().equals(action.stream()) && dot.counter() == action.counter())
                    || !event.actorId().equals(action.actor())
                    || !event.dependencies().equals(actionDependencies(action.kind(), action.actor(), action.target(),
                    action.owner(), action.tag()))) invalid();
        } else if (BRIDGE.equals(event.eventType())) {
            Bridge bridge = parseBridge(event.payloadJson());
            validateNativeSites(bridge.seen(), event.originSite(), allowedNativeSites, false);
            if (!event.actorId().equals(bridge.actor())
                    || !event.dependencies().equals(actionDependencies(bridge.kind(), bridge.actor(), bridge.target(),
                    bridge.owner(), bridge.tag()))) invalid();
        } else invalid();
    }

    private static void validateNativeSites(Map<String, Long> seen, String origin, Set<String> allowedSites,
                                            boolean requireOrigin) {
        Set<String> nativeSites = new HashSet<>();
        for (String stream : seen.keySet()) {
            if (!stream.startsWith("native|")) continue;
            String site = new String(Base64.getUrlDecoder().decode(stream.substring(7)), StandardCharsets.UTF_8);
            nativeSites.add(site);
        }
        if (requireOrigin && !nativeSites.contains(origin)) invalid();
        if (allowedSites != null && !allowedSites.containsAll(nativeSites)) invalid();
    }

    public static List<SyncEvent.Dependency> actionDependencies(RelationKind kind, UUID actor, UUID target,
                                                                 UUID owner, UUID tag) {
        var dependencies = new HashSet<SyncEvent.Dependency>();
        dependencies.add(new SyncEvent.Dependency("users", actor));
        dependencies.add(new SyncEvent.Dependency(kind.targetTable, target));
        dependencies.add(new SyncEvent.Dependency("users", owner));
        if (tag != null) dependencies.add(new SyncEvent.Dependency("food_tags", tag));
        return dependencies.stream().sorted(Comparator.comparing(SyncEvent.Dependency::aggregateType)
                .thenComparing(d -> d.aggregateId().toString())).toList();
    }

    private static void validateDots(List<Dot> dots, Map<String, Long> seen) {
        var streams = new HashSet<String>();
        for (Dot dot : dots) {
            validateStream(dot.stream());
            validateVector(dot.context());
            if (dot.counter() < 1 || dot.counter() > VersionVector.MAX_COUNTER || dot.eventId() == null
                    || dot.context().getOrDefault(dot.stream(), 0L) + 1 != dot.counter()
                    || seen.getOrDefault(dot.stream(), 0L) < dot.counter() || !streams.add(dot.stream())) invalid();
        }
    }

    private static void validateProofMaxima(UUID naturalId, Map<String, Long> seen,
                                            List<Dot> adds, List<Dot> removes) {
        var maxima = new TreeMap<String, Long>();
        var dotMaxima = new TreeMap<String, Long>();
        var allDots = new ArrayList<Dot>(adds);
        allDots.addAll(removes);
        for (Dot dot : allDots) {
            collectProof(maxima, dot);
            dotMaxima.merge(dot.stream(), dot.counter(), Math::max);
        }
        if (!maxima.equals(new TreeMap<>(seen))) invalid();
        if (!dotMaxima.equals(new TreeMap<>(seen))) invalid();
        long nativeStreams = seen.keySet().stream().filter(key -> key.startsWith("native|")).distinct().count();
        if (nativeStreams > VersionVector.MAX_SITES) invalid();
        for (String stream : seen.keySet()) {
            if (stream.startsWith("baseline|") && !stream.equals(baselineStream(naturalId))) invalid();
        }
        // Distinct legacy relation IDs remain legal proof namespaces. Receivers preserve them
        // but block projection until an original native proof covers their complete frontier.
        for (Dot dot : adds) {
            if (dot.stream().startsWith("baseline|")) {
                UUID baseline = UUID.fromString(dot.stream().substring(9));
                UUID expected = UUID.nameUUIDFromBytes(("relation-baseline:" + naturalId)
                        .getBytes(StandardCharsets.UTF_8));
                if (!baseline.equals(naturalId) || !dot.eventId().equals(expected)
                        || dot.counter() != 1 || !dot.context().isEmpty()) invalid();
            }
        }
        if (removes.stream().anyMatch(dot -> dot.stream().startsWith("baseline|"))) invalid();
        long baselines = seen.keySet().stream().filter(key -> key.startsWith("baseline|")).count();
        if (baselines > 1) invalid();
    }

    private static void collectProof(Map<String, Long> maxima, Dot dot) {
        dot.context().forEach((stream, counter) -> maxima.merge(stream, counter, Math::max));
        maxima.merge(dot.stream(), dot.counter(), Math::max);
    }

    private static void validateIdentity(UUID naturalId, RelationKind kind, UUID actor, UUID target, UUID owner,
                                         UUID tag, long actorEpoch, long ownerEpoch, long rowId, UUID rowGid,
                                         String rowOrigin) {
        if (naturalId == null || kind == null || actor == null || target == null || owner == null || rowGid == null
                || actorEpoch < 0 || ownerEpoch < 0 || rowId < 1 || rowId > VersionVector.MAX_COUNTER
                || ((kind == RelationKind.tagging) != (tag != null))
                || (kind == RelationKind.follow && (!target.equals(owner) || actor.equals(target)))) invalid();
        SyncEvent.requireUuid(naturalId, "natural relation id");
        SyncEvent.requireUuid(actor, "relation actor"); SyncEvent.requireUuid(target, "relation target");
        SyncEvent.requireUuid(owner, "relation owner"); SyncEvent.requireUuid(rowGid, "relation row id");
        if (tag != null) SyncEvent.requireUuid(tag, "relation tag");
        SyncEvent.requireName(rowOrigin, "relation row origin", 64);
        if (!naturalId.equals(RelationStore.naturalId(kind, actor, target, owner, tag))) invalid();
    }

    private static void validateVector(Map<String, Long> vector) {
        if (vector == null || vector.size() > 512) invalid();
        for (var entry : vector.entrySet()) {
            validateStream(entry.getKey());
            if (entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > VersionVector.MAX_COUNTER) invalid();
        }
    }

    private static boolean covers(Map<String, Long> seen, Map<String, Long> context) {
        return context.entrySet().stream().allMatch(entry -> seen.getOrDefault(entry.getKey(), 0L)
                >= entry.getValue());
    }

    private static boolean sameIdentity(Action action, Bridge bridge) {
        return action.naturalId().equals(bridge.naturalId()) && action.kind() == bridge.kind()
                && action.actor().equals(bridge.actor()) && action.target().equals(bridge.target())
                && action.owner().equals(bridge.owner()) && Objects.equals(action.tag(), bridge.tag())
                && action.actorEpoch() == bridge.actorEpoch() && action.ownerEpoch() == bridge.ownerEpoch()
                && action.rowId() == bridge.rowId() && action.rowGid().equals(bridge.rowGid())
                && Objects.equals(action.rowOrigin(), bridge.rowOrigin());
    }

    private static void validateStream(String stream) {
        if (stream == null || stream.length() > 192) invalid();
        try {
            if (stream.startsWith("native|")) {
                String site = new String(Base64.getUrlDecoder().decode(stream.substring(7)), StandardCharsets.UTF_8);
                SyncEvent.requireName(site, "native relation stream", 64);
                if (!nativeStream(site).equals(stream)) invalid();
            } else if (stream.startsWith("legacy|")) {
                String[] parts = stream.split("\\|", -1);
                if (parts.length != 3) invalid();
                SyncEvent.requireUuid(UUID.fromString(parts[1]), "legacy relation stream");
                String site = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                SyncEvent.requireName(site, "legacy relation stream", 64);
                if (!legacyStream(UUID.fromString(parts[1]), site).equals(stream)) invalid();
            } else if (stream.startsWith("baseline|")) {
                UUID id = UUID.fromString(stream.substring(9));
                SyncEvent.requireUuid(id, "baseline relation stream");
                if (!baselineStream(id).equals(stream)) invalid();
            } else invalid();
        } catch (RuntimeException invalid) {
            invalid();
        }
    }

    private static void exact(JsonNode node, Class<?> type) {
        var fields = new HashSet<String>();
        for (var component : type.getRecordComponents()) fields.add(component.getName());
        if (node == null || !node.isObject() || !fields.equals(Set.copyOf(node.propertyNames()))) invalid();
    }

    private static void checkPayloadSize(String payload) {
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("RELATION_CAUSAL_STATE_TOO_LARGE");
        }
    }

    private static void invalid() { throw new IllegalArgumentException("INVALID_RELATION_CAUSAL_EVENT"); }
}
