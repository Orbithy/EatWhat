package you.v50to.eatwhat.sync.notification;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.EventPolicy;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Each read fact has its own empty-base transport aggregate; causal state is per notice and recipient. */
public final class NotificationReadEvents implements EventPolicy {
    public static final String AGGREGATE = "notification.read.operation";
    public static final String EVENT = "notification.read";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConsentStore consent;

    public record Operation(long operationNumericId, UUID notificationGid, long notificationId, String notificationOrigin,
                            UUID recipientGid, long recipientEpoch, UUID actorGid, long actorEpoch,
                            String dotSite, long dotCounter, java.util.Map<String, Long> context) {
        public VersionVector vector() { return new VersionVector(context); }
    }

    /** A fresh causal stream per consent epoch lets a regrant supersede withheld old-epoch facts. */
    public static String dotSite(String originSite, long actorEpoch, long recipientEpoch) {
        UUID stream = UUID.nameUUIDFromBytes((originSite + ":notification-read:" + actorEpoch + ":" + recipientEpoch)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "read-" + stream.toString().replace("-", "");
    }

    public NotificationReadEvents(ConsentStore consent) { this.consent = consent; }

    public static String payload(Operation operation) {
        return EventCodec.canonicalJson(JSON.writeValueAsString(operation));
    }

    public static Operation parse(String payload) {
        try {
            JsonNode node = JSON.readTree(EventCodec.canonicalJson(payload));
            var fields = Set.of("operationNumericId", "notificationGid", "notificationId", "notificationOrigin", "recipientGid",
                    "recipientEpoch", "actorGid", "actorEpoch", "dotSite", "dotCounter", "context");
            if (!node.isObject() || !fields.equals(Set.copyOf(node.propertyNames()))) invalid();
            Operation operation = JSON.readValue(node.toString(), Operation.class);
            uuid(operation.notificationGid()); uuid(operation.recipientGid()); uuid(operation.actorGid());
            SyncEvent.requireName(operation.notificationOrigin(), "notification origin", 64);
            SyncEvent.requireName(operation.dotSite(), "read dot site", 64);
            if (operation.operationNumericId() <= 0 || operation.notificationId() <= 0
                    || operation.recipientEpoch() < 0 || operation.actorEpoch() < 0
                    || operation.dotCounter() <= 0 || operation.dotCounter() > VersionVector.MAX_COUNTER
                    || operation.vector().counters().getOrDefault(operation.dotSite(), 0L) + 1 != operation.dotCounter()) {
                invalid();
            }
            return operation;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("INVALID_NOTIFICATION_READ_EVENT");
        }
    }

    private static void uuid(UUID value) { SyncEvent.requireUuid(value, "notification read identity"); }
    private static void invalid() { throw new IllegalArgumentException("INVALID_NOTIFICATION_READ_EVENT"); }

    public static List<SyncEvent.Dependency> dependencies(Operation operation) {
        return java.util.stream.Stream.of(
                new SyncEvent.Dependency("notifications", operation.notificationGid()),
                new SyncEvent.Dependency("users", operation.actorGid()),
                new SyncEvent.Dependency("users", operation.recipientGid()))
                .distinct().sorted(Comparator.comparing(SyncEvent.Dependency::aggregateType)
                        .thenComparing(item -> item.aggregateId().toString())).toList();
    }

    @Override public Set<String> aggregateTypes() { return Set.of(AGGREGATE); }

    @Override
    public OutboundDecision outbound(SyncEvent event) {
        if (!AGGREGATE.equals(event.aggregateType())) return OutboundDecision.ALLOW;
        Operation operation = parse(event.payloadJson());
        if (consent == null) return OutboundDecision.WAIT;
        var authorization = consent.outboundAuthorization(operation.actorGid(), operation.actorEpoch(),
                operation.recipientGid(), operation.recipientEpoch());
        if (authorization.blocked()) return OutboundDecision.POLICY_BLOCKED;
        return authorization.allowed() ? OutboundDecision.ALLOW : OutboundDecision.WAIT;
    }

    @Override
    public void validate(SyncEvent event, NodeIdentity origin) {
        if (!AGGREGATE.equals(event.aggregateType()) || !EVENT.equals(event.eventType())
                || event.schemaVersion() != 1 || !event.eventId().equals(event.aggregateId())
                || !event.originSite().equals(origin.siteId()) || !event.originInstanceId().equals(origin.instanceId())) {
            invalid();
        }
        Operation operation = parse(event.payloadJson());
        if (!operation.dotSite().equals(dotSite(event.originSite(), operation.actorEpoch(), operation.recipientEpoch()))
                || !operation.recipientGid().equals(event.actorId())
                || event.numericId() != operation.operationNumericId() || !dependencies(operation).equals(event.dependencies())
                || !event.baseVersion().equals(VersionVector.empty())
                || !event.nextVersion().equals(event.baseVersion().increment(event.originSite()))) invalid();
    }
}
