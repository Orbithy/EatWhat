package you.v50to.eatwhat.sync.preferences;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;

/** Complete explicit settings snapshots; inferred recommendation features never enter this family. */
public final class PreferenceEvents implements EventPolicy {
    public static final String AGGREGATE = "preference.operation", EVENT = "preference.replaced";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConsentStore consent;
    public PreferenceEvents(ConsentStore consent) { this.consent = consent; }
    public record Snapshot(UUID subject, long epoch, long clock, List<String> likedTags,
                           List<String> dislikedTags, long updatedAtMicros) {
        public List<SyncEvent.Dependency> dependencies() { return List.of(new SyncEvent.Dependency("users", subject)); }
        @Override public String toString() { return "PreferenceSnapshot[redacted]"; }
    }
    public static String payload(Snapshot value) { return EventCodec.canonicalJson(JSON.writeValueAsString(value)); }
    public static String valueHash(List<String> liked, List<String> disliked) {
        return EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(Map.of("liked", liked, "disliked", disliked))));
    }
    public static void validateTags(List<String> tags) {
        if (tags == null || tags.size() > 20) invalid();
        if (tags.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 32
                || !t.equals(t.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)))
                || !tags.equals(tags.stream().distinct().sorted().toList())) invalid();
    }
    public static Snapshot parse(String payload) {
        try {
            var node = JSON.readTree(EventCodec.canonicalJson(payload));
            if (!node.isObject() || !Set.copyOf(node.propertyNames()).equals(Set.of("subject", "epoch", "clock",
                    "likedTags", "dislikedTags", "updatedAtMicros"))) invalid();
            if (!node.path("subject").isString()) invalid();
            UUID subject = UUID.fromString(node.path("subject").stringValue()); SyncEvent.requireUuid(subject, "preference subject");
            if (!subject.toString().equals(node.path("subject").stringValue())) invalid();
            for (String f : List.of("epoch", "clock", "updatedAtMicros")) {
                var n = node.path(f);
                if (!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 1 || n.longValue() > VersionVector.MAX_COUNTER) invalid();
            }
            for (String f : List.of("likedTags", "dislikedTags")) {
                if (!node.path(f).isArray()) invalid();
                for (var item : node.path(f)) if (!item.isString()) invalid();
            }
            var op = JSON.readValue(node.toString(), Snapshot.class);
            validateTags(op.likedTags()); validateTags(op.dislikedTags());
            if (op.likedTags().stream().anyMatch(op.dislikedTags()::contains)) invalid();
            return op;
        } catch (RuntimeException failure) { throw new IllegalArgumentException("INVALID_PREFERENCE_EVENT"); }
    }
    private static void invalid() { throw new IllegalArgumentException("INVALID_PREFERENCE_EVENT"); }
    @Override public Set<String> aggregateTypes() { return Set.of(AGGREGATE); }
    @Override public void validate(SyncEvent event, NodeIdentity origin) {
        if (!event.originSite().equals(origin.siteId()) || !event.originInstanceId().equals(origin.instanceId())
                || !AGGREGATE.equals(event.aggregateType()) || !EVENT.equals(event.eventType()) || event.schemaVersion() != 1
                || !event.eventId().equals(event.aggregateId()) || !event.baseVersion().equals(VersionVector.empty())) invalid();
        var op = parse(event.payloadJson());
        if (!op.subject().equals(event.actorId()) || !op.dependencies().equals(event.dependencies())) invalid();
    }
    @Override public OutboundDecision outbound(SyncEvent event) {
        var op = parse(event.payloadJson());
        if (consent == null) return OutboundDecision.WAIT;
        var gate = consent.outboundAuthorization(op.subject(), op.epoch(), op.subject(), op.epoch());
        return gate.blocked() ? OutboundDecision.POLICY_BLOCKED : gate.allowed() ? OutboundDecision.ALLOW : OutboundDecision.WAIT;
    }
}
