package you.v50to.eatwhat.sync.relation;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.VersionVector;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Persists epoch-independent relation causality; transport epochs never mint logical actions. */
public final class RelationCausalStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOO_LARGE = "LEGACY_CONTEXT_TOO_LARGE";
    private static final String MULTI_LEGACY = "MULTI_LEGACY";
    private static final String LOCAL_CONTEXT_UNKNOWN = "LOCAL_CONTEXT_UNKNOWN";
    private final JdbcTemplate jdbc;

    public RelationCausalStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Identity(UUID naturalId, RelationKind kind, UUID actor, UUID target, UUID owner, UUID tag,
                           long actorEpoch, long ownerEpoch, long rowId, UUID rowGid, String rowOrigin) { }
    public record State(Identity identity, Map<String, Long> seen,
                        List<RelationCausalEvents.Dot> adds, List<RelationCausalEvents.Dot> removes,
                        boolean legacyLoaded, String blockedReason) {
        public boolean present() {
            return adds.stream().anyMatch(add -> removes.stream().allMatch(remove ->
                    add.context().getOrDefault(remove.stream(), 0L) >= remove.counter()));
        }
        public boolean hasStreamFact(String stream) {
            return adds.stream().anyMatch(dot -> dot.stream().equals(stream))
                    || removes.stream().anyMatch(dot -> dot.stream().equals(stream));
        }
        public boolean needsBridge() { return blockedReason == null; }
    }

    /** Called under the same per-natural-key advisory lock as projection writes. */
    public State ensure(Identity identity, boolean publicPresent, boolean unrepresentedLocalIntent) {
        return ensure(identity, publicPresent, unrepresentedLocalIntent, false);
    }

    public State ensure(Identity identity, boolean publicPresent, boolean unrepresentedLocalIntent,
                        boolean incomingCausalEvidence) {
        insertState(identity);
        State state = load(identity.naturalId());
        jdbc.update("UPDATE eatwhat_sync.relation_causal_state SET actor_epoch=?,owner_epoch=?,updated_at=clock_timestamp() WHERE natural_id=?",
                identity.actorEpoch(), identity.ownerEpoch(), identity.naturalId());
        state = load(identity.naturalId());
        if (!state.legacyLoaded()) {
            state = importLegacy(identity, state);
            if (state.blockedReason() != null) {
                // A size ceiling reached while importing old facts is sticky and fail-closed.
            } else if (unrepresentedLocalIntent) {
                state = setBlocked(state, LOCAL_CONTEXT_UNKNOWN);
            } else if (!state.seen().isEmpty() && state.present() != publicPresent) {
                // A current public/history mismatch is not explained by an event that has not
                // yet been merged. Preserve the local projection and fail closed.
                state = setBlocked(state, LOCAL_CONTEXT_UNKNOWN);
            } else if (legacyRelationCount(identity.naturalId()) > 1
                    || legacyRelationIds(state.seen()).size() > 1) {
                // V1 vectors reset at consent boundaries. Their ordering across relation IDs
                // cannot be reconstructed from timestamps or epoch numbers.
                state = setBlocked(state, MULTI_LEGACY);
            } else if (state.seen().isEmpty() && publicPresent && !incomingCausalEvidence) {
                state = seedBaseline(state);
            } else if (state.seen().isEmpty() && !publicPresent && !incomingCausalEvidence) {
                // A missing projection alone is not evidence that the user removed it.
                state = setBlocked(state, LOCAL_CONTEXT_UNKNOWN);
            }
            jdbc.update("UPDATE eatwhat_sync.relation_causal_state SET legacy_loaded=true,updated_at=clock_timestamp() WHERE natural_id=?",
                    identity.naturalId());
            state = load(identity.naturalId());
        }
        return state;
    }

    public State load(UUID naturalId) {
        return jdbc.query("""
                SELECT natural_id,kind,actor_gid,target_gid,owner_gid,tag_gid,actor_epoch,owner_epoch,
                       row_id,row_gid,row_origin,seen::text,add_witnesses::text,remove_frontier::text,
                       legacy_loaded,blocked_reason
                FROM eatwhat_sync.relation_causal_state WHERE natural_id=? FOR UPDATE
                """, (row, index) -> {
            Identity identity = new Identity(row.getObject(1, UUID.class), RelationKind.valueOf(row.getString(2)),
                    row.getObject(3, UUID.class), row.getObject(4, UUID.class), row.getObject(5, UUID.class),
                    row.getObject(6, UUID.class), row.getLong(7), row.getLong(8), row.getLong(9),
                    row.getObject(10, UUID.class), row.getString(11));
            return new State(identity, JSON.readValue(row.getString(12), JSON.getTypeFactory()
                    .constructMapType(Map.class, String.class, Long.class)),
                    JSON.readValue(row.getString(13), JSON.getTypeFactory().constructCollectionType(List.class,
                            RelationCausalEvents.Dot.class)),
                    JSON.readValue(row.getString(14), JSON.getTypeFactory().constructCollectionType(List.class,
                            RelationCausalEvents.Dot.class)), row.getBoolean(15), row.getString(16));
        }, naturalId).stream().findFirst().orElseThrow();
    }

    public RelationCausalEvents.Action captureLocal(Identity identity, State before, String stream, UUID eventId,
                                                    boolean adding, RelationEvents.Operation evidence) {
        long counter = nextCounter(before.seen(), stream);
        if (!canCaptureLocal(before, stream, adding)) throw new IllegalStateException("Relation causal state is blocked");
        String payload = logicalFactPayload(identity.naturalId(), stream, counter, eventId, before.seen(), adding);
        insertFact(eventId, identity.naturalId(), stream, counter, before.seen(), adding, payload, false);
        State result = mergeDot(before, new RelationCausalEvents.Dot(stream, counter, eventId, before.seen()), adding);
        persist(result, null);
        return previewLocal(identity, before, stream, eventId, adding, evidence);
    }

    public boolean canCaptureLocal(State before, String stream, boolean adding, UUID eventId,
                                   RelationEvents.Operation evidence) {
        return canCaptureLocal(before.identity(), before, stream, adding, eventId, evidence);
    }

    public boolean canCaptureLocal(Identity identity, State before, String stream, boolean adding, UUID eventId,
                                   RelationEvents.Operation evidence) {
        if (!canCaptureLocal(before, stream, adding)) return false;
        try {
            String payload = RelationCausalEvents.payload(previewLocal(identity, before, stream,
                    eventId, adding, evidence));
            RelationCausalEvents.parseAction(payload);
            return true;
        } catch (IllegalArgumentException oversizedOrInvalid) {
            return false;
        }
    }

    private RelationCausalEvents.Action previewLocal(Identity identity, State before, String stream, UUID eventId,
                                                      boolean adding, RelationEvents.Operation evidence) {
        long counter = nextCounter(before.seen(), stream);
        State mergedWithBlocker = mergeDot(before,
                new RelationCausalEvents.Dot(stream, counter, eventId, before.seen()), adding);
        State merged = new State(mergedWithBlocker.identity(), mergedWithBlocker.seen(),
                mergedWithBlocker.adds(), mergedWithBlocker.removes(), mergedWithBlocker.legacyLoaded(), null);
        return new RelationCausalEvents.Action(identity.naturalId(), identity.kind(), identity.actor(),
                identity.target(), identity.owner(), identity.tag(), identity.actorEpoch(), identity.ownerEpoch(),
                identity.rowId(), identity.rowGid(), identity.rowOrigin(), stream, counter, before.seen(), adding,
                evidence, bridge(merged, identity));
    }

    public State mergeAction(RelationCausalEvents.Action action, UUID eventId) {
        State before = load(action.naturalId());
        if (!sameNatural(before.identity(), action)) throw new IllegalArgumentException("Relation natural identity changed");
        State merged = previewBridge(before, action.state());
        if (!fits(merged)) return setBlocked(before, TOO_LARGE);
        boolean multiLegacy = legacyRelationIds(merged.seen()).size() > 1;
        String blocked = canClearMultiLegacy(before, action.state()) ? null : before.blockedReason();
        if (multiLegacy && blocked == null && !canClearMultiLegacy(before, action.state())) blocked = MULTI_LEGACY;
        merged = mergeBridge(action.state());
        String payload = logicalFactPayload(action.naturalId(), action.stream(), action.counter(), eventId,
                action.context(), action.adding());
        insertFact(eventId, action.naturalId(), action.stream(), action.counter(), action.context(),
                action.adding(), payload, false);
        // A remote action cannot prove it observed an unrepresented local V1-era intent.
        // Only a new local user command may clear that local ambiguity.
        persist(merged, blocked);
        return load(action.naturalId());
    }

    public boolean canApply(RelationCausalEvents.Action action) {
        // A blocked local legacy history must not be ACKed with its action/notice discarded.
        // Only a real local command can resolve that ambiguity.
        State before = load(action.naturalId());
        if (before.blockedReason() != null && !MULTI_LEGACY.equals(before.blockedReason())) return false;
        State merged = previewBridge(before, action.state());
        if (!fits(merged)) {
            setBlocked(before, TOO_LARGE);
            return false;
        }
        boolean multiLegacy = legacyRelationIds(merged.seen()).size() > 1;
        if (multiLegacy && !canClearMultiLegacy(before, action.state())) {
            persistBridgeFacts(action.state());
            setBlocked(merged, MULTI_LEGACY);
            return false;
        }
        if (MULTI_LEGACY.equals(before.blockedReason()) && !canClearMultiLegacy(before, action.state())) return false;
        return true;
    }

    public boolean canApplyBridge(RelationCausalEvents.Bridge bridge) {
        State before = load(bridge.naturalId());
        if (before.blockedReason() != null && !MULTI_LEGACY.equals(before.blockedReason())) return false;
        State merged = previewBridge(before, bridge);
        if (!fits(merged)) {
            setBlocked(before, TOO_LARGE);
            return false;
        }
        boolean multiLegacy = legacyRelationIds(merged.seen()).size() > 1;
        if (multiLegacy && !canClearMultiLegacy(before, bridge)) {
            persistBridgeFacts(bridge);
            persist(merged, MULTI_LEGACY);
            return false;
        }
        if (MULTI_LEGACY.equals(before.blockedReason()) && !canClearMultiLegacy(before, bridge)) return false;
        return true;
    }

    public State mergeLegacy(RelationEvents.Operation operation, UUID eventId) {
        UUID naturalId = RelationStore.naturalId(operation.kind(), operation.actor(), operation.target(),
                operation.owner(), operation.tag());
        State before = load(naturalId);
        String stream = RelationCausalEvents.legacyStream(operation.relationId(), operation.dotSite());
        Map<String, Long> context = legacyContext(operation.relationId(), operation.context());
        State merged = mergeLegacy(before, operation, eventId, stream, context);
        return classifyLegacyMerge(before, merged);
    }

    /**
     * Archives a predecessor-satisfied V1 operation before deciding whether it can be projected.
     * This lets a blocked MULTI_LEGACY stream retain the exact event and retry after a native
     * command supplies a proof covering the combined legacy frontier.
     */
    public State prepareLegacy(UUID eventId, RelationEvents.Operation operation) {
        UUID naturalId = RelationStore.naturalId(operation.kind(), operation.actor(), operation.target(),
                operation.owner(), operation.tag());
        State before = load(naturalId);
        if (LOCAL_CONTEXT_UNKNOWN.equals(before.blockedReason())) return before;
        recordV1Evidence(eventId, operation);
        return mergeLegacy(operation, eventId);
    }

    private State classifyLegacyMerge(State before, State merged) {
        if (before.blockedReason() != null && !MULTI_LEGACY.equals(before.blockedReason())) return merged;
        if (merged.blockedReason() != null && !MULTI_LEGACY.equals(merged.blockedReason())) return merged;
        boolean multiLegacy = legacyRelationCount(merged.identity().naturalId()) > 1
                || legacyRelationIds(merged.seen()).size() > 1;
        boolean proven = canClearMultiLegacy(before, bridge(merged));
        String blocked = merged.blockedReason();
        if (multiLegacy && !proven) blocked = MULTI_LEGACY;
        else if (MULTI_LEGACY.equals(blocked) && proven) blocked = null;
        if (!Objects.equals(blocked, merged.blockedReason())) {
            persist(merged, blocked);
            return load(merged.identity().naturalId());
        }
        return merged;
    }

    /** V1 dots reset when its consent-derived relation ID changes; that order is unknowable. */
    public boolean canApplyLegacy(RelationEvents.Operation operation) {
        UUID naturalId = RelationStore.naturalId(operation.kind(), operation.actor(), operation.target(),
                operation.owner(), operation.tag());
        State state = load(naturalId);
        if (LOCAL_CONTEXT_UNKNOWN.equals(state.blockedReason())) return false;
        Integer existing = jdbc.queryForObject("""
                SELECT count(DISTINCT o.relation_id)::integer
                FROM eatwhat_sync.relation_operation o
                JOIN eatwhat_sync.relation_causal_fact f ON f.event_id=o.event_id AND f.legacy
                WHERE f.natural_id=?
                """, Integer.class, naturalId);
        boolean seenRelation = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.relation_causal_fact
                  WHERE natural_id=? AND legacy AND stream_key LIKE ?)
                """, Boolean.class, naturalId, "legacy|" + operation.relationId() + "|%"));
        Set<UUID> knownRelationIds = legacyRelationIds(state.seen());
        UUID incomingRelationId = operation.relationId();
        boolean conflictingStream = knownRelationIds.stream().anyMatch(id -> !id.equals(incomingRelationId));
        boolean multiLegacy = conflictingStream || (existing != null && existing > 0 && !seenRelation
                && !knownRelationIds.contains(incomingRelationId)) || (existing != null && existing > 1);
        if (!multiLegacy) return state.blockedReason() == null;
        State merged = state;
        if (state.blockedReason() == null) merged = setBlocked(state, MULTI_LEGACY);
        return canClearMultiLegacy(state, bridge(merged));
    }

    public State mergeBridge(RelationCausalEvents.Bridge bridge) {
        State before = load(bridge.naturalId());
        if (!sameNatural(before.identity(), bridge)) throw new IllegalArgumentException("Relation natural identity changed");
        State merged = previewBridge(before, bridge);
        if (!fits(merged)) return setBlocked(before, TOO_LARGE);
        persistBridgeFacts(bridge);
        String blocked = canClearMultiLegacy(before, bridge) ? null : before.blockedReason();
        if (legacyRelationIds(merged.seen()).size() > 1 && blocked == null
                && !canClearMultiLegacy(before, bridge)) blocked = MULTI_LEGACY;
        persist(merged, blocked);
        return load(bridge.naturalId());
    }

    private void persistBridgeFacts(RelationCausalEvents.Bridge bridge) {
        for (RelationCausalEvents.Dot add : bridge.addWitnesses()) verifyBridgeFact(bridge.naturalId(), add, true);
        for (RelationCausalEvents.Dot remove : bridge.removeFrontier()) verifyBridgeFact(bridge.naturalId(), remove, false);
    }

    private static boolean canClearMultiLegacy(State before, RelationCausalEvents.Bridge incoming) {
        if (before.blockedReason() != null && !MULTI_LEGACY.equals(before.blockedReason())) return false;
        Map<String, Long> legacyFrontier = new TreeMap<>();
        collectLegacyFrontier(legacyFrontier, before.seen());
        collectLegacyFrontier(legacyFrontier, incoming.seen());
        if (legacyFrontier.isEmpty() || legacyRelationIds(legacyFrontier).size() < 2) return false;
        return java.util.stream.Stream.of(before.adds(), before.removes(), incoming.addWitnesses(),
                        incoming.removeFrontier()).flatMap(Collection::stream)
                .filter(dot -> dot.stream().startsWith("native|"))
                .anyMatch(dot -> covers(dot.context(), legacyFrontier));
    }

    private static void collectLegacyFrontier(Map<String, Long> target, Map<String, Long> source) {
        source.forEach((stream, counter) -> {
            if (stream.startsWith("legacy|")) target.merge(stream, counter, Math::max);
        });
    }

    private State previewBridge(State before, RelationCausalEvents.Bridge bridge) {
        State merged = new State(before.identity(), new TreeMap<>(before.seen()), new ArrayList<>(before.adds()),
                new ArrayList<>(before.removes()), before.legacyLoaded(), before.blockedReason());
        for (var entry : bridge.seen().entrySet()) {
            merged.seen().merge(entry.getKey(), entry.getValue(), Math::max);
        }
        for (RelationCausalEvents.Dot add : bridge.addWitnesses()) {
            merged = mergeDot(merged, add, true);
        }
        for (RelationCausalEvents.Dot remove : bridge.removeFrontier()) {
            merged = mergeDot(merged, remove, false);
        }
        if (!covers(merged.seen(), bridge.seen())) throw new IllegalArgumentException("Invalid causal bridge frontier");
        return merged;
    }

    public RelationCausalEvents.Bridge bridge(State state) {
        return bridge(state, state.identity());
    }

    /** The bridge carries this node's validated projection alias, not a shared natural-key field. */
    public RelationCausalEvents.Bridge bridge(State state, Identity projectionIdentity) {
        if (!sameNatural(state.identity(), projectionIdentity)) {
            throw new IllegalArgumentException("Relation natural identity changed");
        }
        Identity i = projectionIdentity;
        return new RelationCausalEvents.Bridge(i.naturalId(), i.kind(), i.actor(), i.target(), i.owner(), i.tag(),
                i.actorEpoch(), i.ownerEpoch(), i.rowId(), i.rowGid(), i.rowOrigin(), state.seen(), state.adds(),
                state.removes(), state.blockedReason());
    }

    private static boolean sameNatural(Identity left, Identity right) {
        return left.naturalId().equals(right.naturalId()) && left.kind() == right.kind()
                && left.actor().equals(right.actor()) && left.target().equals(right.target())
                && left.owner().equals(right.owner()) && Objects.equals(left.tag(), right.tag());
    }

    public State setBlocked(State state, String reason) {
        persist(state, reason);
        return load(state.identity().naturalId());
    }

    public void recordV1Evidence(UUID eventId, RelationEvents.Operation operation) {
        UUID relationId = operation.relationId();
        jdbc.update("INSERT INTO eatwhat_sync.relation_state(relation_id) VALUES (?) ON CONFLICT DO NOTHING", relationId);
        jdbc.update("""
                INSERT INTO eatwhat_sync.relation_operation(event_id,relation_id,dot_site,dot_counter,context,adding,payload)
                VALUES(?,?,?, ?,?::jsonb,?,?::jsonb) ON CONFLICT DO NOTHING
                """, eventId, relationId, operation.dotSite(), operation.dotCounter(),
                EventCodec.vectorJson(operation.vector()), operation.adding(), RelationEvents.payload(operation));
        boolean matches = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.relation_operation
                  WHERE event_id=? AND relation_id=? AND dot_site=? AND dot_counter=? AND payload=?::jsonb)
                """, Boolean.class, eventId, relationId, operation.dotSite(), operation.dotCounter(),
                RelationEvents.payload(operation)));
        if (!matches) throw new IllegalStateException("Relation V1 evidence identity conflict");
        var current = EventCodec.vectorFromJson(jdbc.queryForObject(
                "SELECT vector::text FROM eatwhat_sync.relation_state WHERE relation_id=? FOR UPDATE",
                String.class, relationId));
        var vector = new TreeMap<>(current.counters());
        operation.context().forEach((site, counter) -> vector.merge(site, counter, Math::max));
        vector.merge(operation.dotSite(), operation.dotCounter(), Math::max);
        jdbc.update("UPDATE eatwhat_sync.relation_state SET vector=?::jsonb WHERE relation_id=?",
                EventCodec.vectorJson(new VersionVector(vector)), relationId);
    }

    private State importLegacy(Identity identity, State state) {
        var operations = jdbc.query("""
                SELECT event_id,payload::text FROM eatwhat_sync.relation_operation
                WHERE payload->>'kind'=? AND (payload->>'actor')::uuid=? AND (payload->>'target')::uuid=?
                  AND (payload->>'owner')::uuid=? AND (payload->>'tag') IS NOT DISTINCT FROM ?
                ORDER BY event_id
                """, (row, index) -> Map.entry(row.getObject(1, UUID.class), row.getString(2)),
                identity.kind().name(), identity.actor(), identity.target(), identity.owner(),
                identity.tag() == null ? null : identity.tag().toString());
        State current = state;
        for (var entry : operations) {
            if (current.blockedReason() != null) break;
            if (factExists(entry.getKey())) continue; // A V2 inner V1 payload is evidence, not another logical action.
            RelationEvents.Operation operation = RelationEvents.parse(entry.getValue());
            if (!RelationStore.naturalId(operation.kind(), operation.actor(), operation.target(),
                    operation.owner(), operation.tag()).equals(identity.naturalId())) {
                throw new IllegalStateException("Relation history natural key mismatch");
            }
            String stream = RelationCausalEvents.legacyStream(operation.relationId(), operation.dotSite());
            current = mergeLegacy(current, operation, entry.getKey(), stream,
                    legacyContext(operation.relationId(), operation.context()));
        }
        return current;
    }

    private State mergeLegacy(State before, RelationEvents.Operation operation, UUID eventId,
                              String stream, Map<String, Long> context) {
        RelationCausalEvents.Dot dot = new RelationCausalEvents.Dot(stream, operation.dotCounter(), eventId, context);
        State result = mergeDot(before, dot, operation.adding());
        if (!fits(result)) return setBlocked(before, TOO_LARGE);
        String payload = logicalFactPayload(before.identity().naturalId(), stream, operation.dotCounter(),
                eventId, context, operation.adding());
        insertFact(eventId, before.identity().naturalId(), stream, operation.dotCounter(), context,
                operation.adding(), payload, true);
        persist(result, before.blockedReason());
        return load(before.identity().naturalId());
    }

    private State seedBaseline(State state) {
        Identity identity = state.identity();
        String stream = RelationCausalEvents.baselineStream(identity.naturalId());
        UUID eventId = UUID.nameUUIDFromBytes(("relation-baseline:" + identity.naturalId())
                .getBytes(StandardCharsets.UTF_8));
        Map<String, Long> context = Map.of();
        String payload = logicalFactPayload(identity.naturalId(), stream, 1, eventId, context, true);
        insertFact(eventId, identity.naturalId(), stream, 1, context, true, payload, true);
        State result = mergeDot(state,
                new RelationCausalEvents.Dot(stream, 1, eventId, context), true);
        persist(result, state.blockedReason());
        return load(identity.naturalId());
    }

    private void insertState(Identity identity) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.relation_causal_state
                  (natural_id,kind,actor_gid,target_gid,owner_gid,tag_gid,actor_epoch,owner_epoch,row_id,row_gid,row_origin)
                VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(natural_id) DO NOTHING
                """, identity.naturalId(), identity.kind().name(), identity.actor(), identity.target(), identity.owner(),
                identity.tag(), identity.actorEpoch(), identity.ownerEpoch(), identity.rowId(), identity.rowGid(),
                identity.rowOrigin());
        boolean matches = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.relation_causal_state
                  WHERE natural_id=? AND kind=? AND actor_gid=? AND target_gid=? AND owner_gid=?
                    AND tag_gid IS NOT DISTINCT FROM ?)
                """, Boolean.class, identity.naturalId(), identity.kind().name(), identity.actor(), identity.target(),
                identity.owner(), identity.tag()));
        if (!matches) throw new IllegalStateException("Relation causal natural key collision");
    }

    private State mergeDot(State state, RelationCausalEvents.Dot dot, boolean adding) {
        var seen = new TreeMap<>(state.seen());
        dot.context().forEach((key, value) -> seen.merge(key, value, Math::max));
        seen.merge(dot.stream(), dot.counter(), Math::max);
        var adds = new ArrayList<>(state.adds());
        var removes = new ArrayList<>(state.removes());
        List<RelationCausalEvents.Dot> selected = adding ? adds : removes;
        RelationCausalEvents.Dot streamPrior = java.util.stream.Stream.concat(adds.stream(), removes.stream())
                .filter(value -> value.stream().equals(dot.stream()))
                .max(Comparator.comparingLong(RelationCausalEvents.Dot::counter)).orElse(null);
        if (streamPrior != null) {
            if (dot.counter() > streamPrior.counter() && !covers(dot.context(), streamPrior.context())) {
                throw new IllegalStateException("Relation causal witness context regressed");
            }
            if (dot.counter() < streamPrior.counter() && !covers(streamPrior.context(), dot.context())) {
                throw new IllegalStateException("Relation causal witness context regressed");
            }
            if (dot.counter() == streamPrior.counter()) {
                RelationCausalEvents.Dot sameType = (adding ? state.adds() : state.removes()).stream()
                        .filter(value -> value.stream().equals(dot.stream()) && value.counter() == dot.counter())
                        .findFirst().orElse(null);
                if (sameType == null || !dot.eventId().equals(sameType.eventId())
                        || !dot.context().equals(sameType.context())) {
                    throw new IllegalStateException("Relation causal dot has conflicting immutable evidence");
                }
            }
        }
        RelationCausalEvents.Dot prior = selected.stream().filter(value -> value.stream().equals(dot.stream()))
                .findFirst().orElse(null);
        if (prior == null || dot.counter() > prior.counter()) {
            selected.removeIf(value -> value.stream().equals(dot.stream()));
            selected.add(dot);
        } else if (dot.counter() == prior.counter() && !prior.equals(dot)) {
            throw new IllegalStateException("Relation causal dot has conflicting immutable evidence");
        }
        return new State(state.identity(), Map.copyOf(seen), List.copyOf(adds), List.copyOf(removes),
                state.legacyLoaded(), state.blockedReason());
    }

    private void persist(State state, String blockedReason) {
        String seen = JSON.writeValueAsString(state.seen());
        String adds = JSON.writeValueAsString(state.adds());
        String removes = JSON.writeValueAsString(state.removes());
        if (!fitsJson(seen, adds, removes)) throw new IllegalStateException("Relation causal state exceeds safe size");
        jdbc.update("""
                UPDATE eatwhat_sync.relation_causal_state
                SET seen=?::jsonb,add_witnesses=?::jsonb,remove_frontier=?::jsonb,
                    blocked_reason=?,updated_at=clock_timestamp() WHERE natural_id=?
                """, seen, adds, removes, blockedReason, state.identity().naturalId());
    }

    public boolean canCaptureLocal(State before, String stream) {
        return canCaptureLocal(before, stream, true);
    }

    public boolean canCaptureLocal(State before, String stream, boolean adding) {
        if (before.blockedReason() != null && !MULTI_LEGACY.equals(before.blockedReason())
                && !LOCAL_CONTEXT_UNKNOWN.equals(before.blockedReason())) return false;
        long counter = nextCounter(before.seen(), stream);
        UUID candidate = UUID.nameUUIDFromBytes(("capture-preflight:" + before.identity().naturalId() + ":"
                + stream + ":" + counter).getBytes(StandardCharsets.UTF_8));
        State after = mergeDot(before, new RelationCausalEvents.Dot(stream, counter, candidate, before.seen()), adding);
        return fits(after);
    }

    private boolean fits(State state) {
        if (state.seen().size() > 512 || state.adds().size() > 256 || state.removes().size() > 256) return false;
        String seen = JSON.writeValueAsString(state.seen());
        String adds = JSON.writeValueAsString(state.adds());
        String removes = JSON.writeValueAsString(state.removes());
        if (!fitsJson(seen, adds, removes)) return false;
        try {
            RelationCausalEvents.payload(bridge(state));
            return true;
        } catch (IllegalArgumentException oversized) {
            return false;
        }
    }

    private static boolean fitsJson(String seen, String adds, String removes) {
        return (seen + adds + removes).getBytes(StandardCharsets.UTF_8).length <= 120 * 1024;
    }

    private void insertFact(UUID eventId, UUID naturalId, String stream, long counter, Map<String, Long> context,
                            boolean adding, String payload, boolean legacy) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.relation_causal_fact
                  (event_id,natural_id,stream_key,dot_counter,context,adding,payload,legacy)
                VALUES(?,?,?, ?,?::jsonb,?,?::jsonb,?) ON CONFLICT DO NOTHING
                """, eventId, naturalId, stream, counter, JSON.writeValueAsString(context), adding, payload, legacy);
        boolean matches = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.relation_causal_fact
                  WHERE event_id=? AND natural_id=? AND stream_key=? AND dot_counter=?
                    AND context=?::jsonb AND adding=? AND payload=?::jsonb)
                """, Boolean.class, eventId, naturalId, stream, counter, JSON.writeValueAsString(context), adding, payload));
        if (!matches) throw new IllegalStateException("Relation causal fact identity conflict");
    }

    private void verifyBridgeFact(UUID naturalId, RelationCausalEvents.Dot dot, boolean adding) {
        String payload = logicalFactPayload(naturalId, dot.stream(), dot.counter(), dot.eventId(), dot.context(), adding);
        insertFact(dot.eventId(), naturalId, dot.stream(), dot.counter(), dot.context(), adding, payload, false);
    }

    private boolean factExists(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.relation_causal_fact WHERE event_id=?)",
                Boolean.class, eventId));
    }

    private int legacyRelationCount(UUID naturalId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(DISTINCT o.relation_id)::integer
                FROM eatwhat_sync.relation_operation o
                JOIN eatwhat_sync.relation_causal_fact f ON f.event_id=o.event_id AND f.legacy
                WHERE f.natural_id=?
                """, Integer.class, naturalId);
        return count == null ? 0 : count;
    }

    private static Set<UUID> legacyRelationIds(Map<String, Long> seen) {
        Set<UUID> relationIds = new HashSet<>();
        for (String stream : seen.keySet()) {
            if (!stream.startsWith("legacy|")) continue;
            String[] parts = stream.split("\\|", -1);
            if (parts.length != 3) throw new IllegalStateException("Malformed legacy causal stream");
            relationIds.add(UUID.fromString(parts[1]));
        }
        return relationIds;
    }

    private static Map<String, Long> legacyContext(UUID relationId, Map<String, Long> context) {
        var mapped = new TreeMap<String, Long>();
        context.forEach((site, counter) -> {
            if (counter > 0) mapped.put(RelationCausalEvents.legacyStream(relationId, site), counter);
        });
        return Map.copyOf(mapped);
    }

    private static boolean covers(Map<String, Long> seen, Map<String, Long> context) {
        return context.entrySet().stream().allMatch(entry -> seen.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }

    private static long nextCounter(Map<String, Long> seen, String stream) {
        long current = seen.getOrDefault(stream, 0L);
        if (current >= VersionVector.MAX_COUNTER) throw new IllegalStateException("Relation causal counter exhausted");
        return current + 1;
    }

    private static String logicalFactPayload(UUID naturalId, String stream, long counter, UUID eventId,
                                             Map<String, Long> context, boolean adding) {
        return EventCodec.canonicalJson(JSON.writeValueAsString(Map.of("naturalId", naturalId, "stream", stream,
                "counter", counter, "eventId", eventId, "context", context, "adding", adding)));
    }

    private static boolean sameNatural(Identity identity, RelationCausalEvents.Action action) {
        return identity.naturalId().equals(action.naturalId()) && identity.kind() == action.kind()
                && identity.actor().equals(action.actor()) && identity.target().equals(action.target())
                && identity.owner().equals(action.owner()) && Objects.equals(identity.tag(), action.tag());
    }

    private static boolean sameNatural(Identity identity, RelationCausalEvents.Bridge bridge) {
        return identity.naturalId().equals(bridge.naturalId()) && identity.kind() == bridge.kind()
                && identity.actor().equals(bridge.actor()) && identity.target().equals(bridge.target())
                && identity.owner().equals(bridge.owner()) && Objects.equals(identity.tag(), bridge.tag());
    }
}
