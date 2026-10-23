package you.v50to.eatwhat.sync.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.EventHandler;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.Map;
import java.util.List;
import java.util.UUID;

/** Replays privacy facts with false-wins concurrency and causal true restoration. */
public final class PrivacyEventHandler implements EventHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final PrivacyStore store;
    private final EntityIdentityService identities;
    private final ConsentStore consent;
    private final PrivacyEvents policy;

    public PrivacyEventHandler(JdbcTemplate jdbc, PrivacyStore store, EntityIdentityService identities,
                               ConsentStore consent) {
        this.jdbc = jdbc;
        this.store = store;
        this.identities = identities;
        this.consent = consent;
        this.policy = new PrivacyEvents(consent);
    }

    @Override public String aggregateType() { return PrivacyEvents.AGGREGATE; }
    @Override public String eventType() { return PrivacyEvents.EVENT; }
    @Override public int schemaVersion() { return 1; }

    private PrivacyEvents.Operation operation(SyncEvent event) {
        policy.validate(event, new NodeIdentity(event.originSite(), event.originInstanceId()));
        return PrivacyEvents.parse(event.payloadJson());
    }

    @Override public boolean dependenciesSatisfied(SyncEvent event) {
        PrivacyEvents.Operation op = operation(event);
        var subjects = jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",
                (row, index) -> row.getLong(1), op.subject());
        if (subjects.isEmpty()) return false;
        var state = consent.getBySubject(op.subject());
        if (state == null) return false;
        if (state.epoch() > op.epoch() || (state.epoch() == op.epoch() && state.status().equals("WITHDRAWN"))) return true;
        Long userId = identities.findNumericId(EntityType.USERS, op.subject()).orElse(null);
        return userId != null && state.epoch() == op.epoch() && consent.replicationAllowed(userId)
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.users WHERE id=?)", Boolean.class, userId));
    }

    @Override public void apply(SyncEvent event) {
        PrivacyEvents.Operation op = operation(event);
        var consentState = consent.getBySubject(op.subject());
        if (consentState == null || consentState.epoch() > op.epoch()
                || (consentState.epoch() == op.epoch() && consentState.status().equals("WITHDRAWN"))) return;
        Long uid = identities.findNumericId(EntityType.USERS, op.subject()).orElseThrow();
        if (consentState.epoch() != op.epoch() || !consent.replicationAllowed(uid)) return;

        Map<String, Boolean> currentPublic = store.readPublic(uid);
        store.ensure(op.subject(), currentPublic);
        PrivacyStore.Snapshot current = store.beginEpoch(op.subject(), op.epoch());
        for (String field : List.of("following", "follower")) {
            PrivacyEvents.BridgeState bridge = op.stateBridge().get(field);
            boolean hasChange = op.changes().containsKey(field);
            if (bridge == null && !hasChange) continue;
            boolean currentValue = field.equals("following") ? current.following() : current.follower();
            VersionVector currentVector = field.equals("following") ? current.followingVector() : current.followerVector();
            VersionVector falseFrontier = field.equals("following") ? current.followingFalseContext() : current.followerFalseContext();
            VersionVector mergedVector = currentVector;
            if (bridge != null) {
                mergedVector = PrivacyStore.merge(mergedVector, new VersionVector(bridge.vector()));
                falseFrontier = PrivacyStore.merge(falseFrontier, new VersionVector(bridge.falseContext()));
            }

            var witnesses = new java.util.LinkedHashMap<UUID, PrivacyEvents.Witness>();
            for (PrivacyEvents.Witness witness : store.trueWitnesses(op.subject(), field)) witnesses.put(witness.id(), witness);
            if (bridge != null) for (PrivacyEvents.Witness witness : bridge.trueWitnesses()) {
                PrivacyEvents.Witness previous = witnesses.putIfAbsent(witness.id(), witness);
                if (previous != null && !previous.vector().equals(witness.vector())) {
                    throw new IllegalArgumentException("PRIVACY_WITNESS_IDENTITY_CONFLICT");
                }
            }
            VersionVector incomingVector = null;
            if (hasChange) {
                VersionVector context = new VersionVector(op.contexts().get(field));
                incomingVector = withDot(context, op.dotSite(), op.dotCounter());
                mergedVector = PrivacyStore.merge(mergedVector, incomingVector);
                if (Boolean.TRUE.equals(op.changes().get(field))) {
                    PrivacyEvents.Witness incomingWitness = new PrivacyEvents.Witness(event.eventId(), incomingVector.counters());
                    PrivacyEvents.Witness priorWitness = witnesses.putIfAbsent(event.eventId(), incomingWitness);
                    if (priorWitness != null && !priorWitness.vector().equals(incomingWitness.vector())) {
                        throw new IllegalArgumentException("PRIVACY_WITNESS_IDENTITY_CONFLICT");
                    }
                } else {
                    falseFrontier = PrivacyStore.merge(falseFrontier, incomingVector);
                }
            }

            var maximalWitnesses = new java.util.ArrayList<>(witnesses.values());
            maximalWitnesses.removeIf(candidate -> witnesses.values().stream().anyMatch(other ->
                    !other.id().equals(candidate.id())
                            && new VersionVector(other.vector()).compare(new VersionVector(candidate.vector()))
                            == VersionVector.Relation.AFTER));
            VersionVector restrictiveFrontier = falseFrontier;
            boolean witnessedTrue = maximalWitnesses.stream().anyMatch(witness -> observes(
                    new VersionVector(witness.vector()), restrictiveFrontier));
            // A legacy/default true with no restrictive history remains true; once any false
            // frontier exists, only a real true operation that observed it can reopen access.
            boolean mergedValue = falseFrontier.counters().isEmpty() && (currentValue || (bridge != null && bridge.value()))
                    || witnessedTrue;
            store.apply(op.subject(), field, mergedValue, mergedVector, falseFrontier);
            if (field.equals("following")) current = new PrivacyStore.Snapshot(mergedValue, current.follower(),
                    current.vector(), mergedVector, current.followerVector(), falseFrontier, current.followerFalseContext(),
                    current.managed(), current.localDirty(), current.activeEpoch(), current.publishedEpoch());
            else current = new PrivacyStore.Snapshot(current.following(), mergedValue, current.vector(),
                    current.followingVector(), mergedVector, current.followingFalseContext(), falseFrontier,
                    current.managed(), current.localDirty(), current.activeEpoch(), current.publishedEpoch());
        }
        VersionVector incomingDomain = contextUnion(op);
        if (!op.changes().isEmpty()) incomingDomain = withDot(incomingDomain, op.dotSite(), op.dotCounter());
        for (PrivacyEvents.BridgeState bridge : op.stateBridge().values()) {
            incomingDomain = PrivacyStore.merge(incomingDomain, new VersionVector(bridge.vector()));
        }
        VersionVector mergedDomain = PrivacyStore.merge(current.vector(), incomingDomain);
        store.mergeVector(op.subject(), mergedDomain, current.localDirty());
        store.upsertPublic(uid, current.following(), current.follower());
        jdbc.update("""
                INSERT INTO eatwhat_sync.privacy_operation(event_id,subject_gid,epoch,origin_site,dot_counter,context,
                    following_value,following_context,follower_value,follower_context,state_bridge)
                VALUES(?,?,?,?,?,?::jsonb,? ,?::jsonb,? ,?::jsonb,?::jsonb) ON CONFLICT(event_id) DO NOTHING
                """, event.eventId(), op.subject(), op.epoch(), op.dotSite(), op.dotCounter(),
                you.v50to.eatwhat.sync.EventCodec.canonicalJson(
                        JSON.writeValueAsString(op.contexts())),
                op.changes().get("following"), you.v50to.eatwhat.sync.EventCodec.vectorJson(
                        new VersionVector(op.contexts().getOrDefault("following", Map.of()))),
                op.changes().get("follower"), you.v50to.eatwhat.sync.EventCodec.vectorJson(
                        new VersionVector(op.contexts().getOrDefault("follower", Map.of()))),
                you.v50to.eatwhat.sync.EventCodec.canonicalJson(JSON.writeValueAsString(op.stateBridge())));
    }

    private static boolean observes(VersionVector context, VersionVector observed) {
        VersionVector.Relation relation = context.compare(observed);
        return relation == VersionVector.Relation.AFTER || relation == VersionVector.Relation.EQUAL;
    }

    private static VersionVector withDot(VersionVector context, String site, long counter) {
        Map<String, Long> merged = new java.util.LinkedHashMap<>(context.counters());
        if (counter <= merged.getOrDefault(site, 0L)) throw new IllegalArgumentException("INVALID_PRIVACY_EVENT");
        merged.put(site, counter);
        return new VersionVector(merged);
    }

    private static VersionVector contextUnion(PrivacyEvents.Operation op) {
        VersionVector result = VersionVector.empty();
        for (Map<String, Long> context : op.contexts().values()) result = PrivacyStore.merge(result, new VersionVector(context));
        return result;
    }
}
