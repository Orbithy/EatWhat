package you.v50to.eatwhat.sync.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.EventHandler;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.relation.RelationEvents;
import you.v50to.eatwhat.sync.relation.RelationKind;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.List;
import java.util.UUID;

import static you.v50to.eatwhat.account.ConsentModels.State;

/** Applies only a monotonic read fact; it never creates or extends a notification. */
public final class NotificationReadEventHandler implements EventHandler {
    private final JdbcTemplate jdbc;
    private final NotificationReadStore store;
    private final ConsentStore consent;
    private final NotificationReadEvents policy = new NotificationReadEvents(null);

    public NotificationReadEventHandler(JdbcTemplate jdbc, NotificationReadStore store, ConsentStore consent) {
        this.jdbc = jdbc; this.store = store; this.consent = consent;
    }

    @Override public String aggregateType() { return NotificationReadEvents.AGGREGATE; }
    @Override public String eventType() { return NotificationReadEvents.EVENT; }
    @Override public int schemaVersion() { return 1; }

    private NotificationReadEvents.Operation operation(SyncEvent event) {
        policy.validate(event, new NodeIdentity(event.originSite(), event.originInstanceId()));
        return NotificationReadEvents.parse(event.payloadJson());
    }

    @Override
    public boolean dependenciesSatisfied(SyncEvent event) {
        NotificationReadEvents.Operation operation = operation(event);
        lockSubjects(operation);
        if (stale(operation)) return true;
        var actor = consent.getBySubject(operation.actorGid());
        var recipient = consent.getBySubject(operation.recipientGid());
        if (actor == null || recipient == null || actor.epoch() != operation.actorEpoch()
                || recipient.epoch() != operation.recipientEpoch()) return false;
        if (!consent.replicationAllowed(actor.userId()) || !consent.replicationAllowed(recipient.userId())) return false;
        if (!sourceReady(operation)) return false;
        return store.readPredecessorsSatisfied(operation);
    }

    @Override
    public void apply(SyncEvent event) {
        NotificationReadEvents.Operation operation = operation(event);
        if (stale(operation)) return;
        store.applyOperation(event.eventId(), event.numericId(), operation);
    }

    private boolean stale(NotificationReadEvents.Operation operation) {
        var actor = consent.getBySubject(operation.actorGid());
        var recipient = consent.getBySubject(operation.recipientGid());
        return stale(actor, operation.actorEpoch()) || stale(recipient, operation.recipientEpoch());
    }

    private static boolean stale(State state, long eventEpoch) {
        return state != null && (state.epoch() > eventEpoch
                || (state.epoch() == eventEpoch && (state.status().equals("WITHDRAWN")
                || state.withdrawalRequestId() != null)));
    }

    private void lockSubjects(NotificationReadEvents.Operation operation) {
        List<UUID> identities = java.util.stream.Stream.of(operation.actorGid(), operation.recipientGid())
                .distinct().toList();
        String marks = String.join(",", java.util.Collections.nCopies(identities.size(), "?"));
        jdbc.query("""
                SELECT s.user_id FROM eatwhat_consent.subject s
                WHERE s.subject_id IN (
                  SELECT global_id FROM eatwhat_identity.entity_identity
                  WHERE entity_type='users' AND global_id IN (
                """ + marks + ")) ORDER BY s.user_id FOR SHARE",
                (row, index) -> row.getLong(1), identities.toArray());
    }

    /** Missing source notifications wait; an expired source identity can satisfy the dependency without a row. */
    private boolean sourceReady(NotificationReadEvents.Operation operation) {
        List<Source> rows = jdbc.query("""
                SELECT rn.payload::text,ni.numeric_id,ni.origin_site,ri.numeric_id,ai.numeric_id,
                       ro.payload->>'actor',ro.payload->>'owner',ro.payload::text,
                       CASE WHEN ro.payload->>'kind'='follow' THEN ai.numeric_id ELSE ti.numeric_id END,
                       n.id,n.account_id,n.actor_id,n.type,
                       n.target_type,n.target_id,
                       (extract(epoch FROM n.created_at)*1000)::bigint,
                       (extract(epoch FROM n.expires_at)*1000)::bigint,
                       (rn.payload->>'createdAt')::bigint,
                       (rn.payload->>'expiresAt')::bigint,
                       (rn.payload->>'expiresAt')::bigint <= (extract(epoch FROM clock_timestamp())*1000)::bigint
                FROM eatwhat_sync.relation_notification rn
                JOIN eatwhat_identity.entity_identity ni ON ni.entity_type='notifications'
                  AND ni.global_id=rn.global_id AND ni.numeric_id=rn.numeric_id
                JOIN eatwhat_identity.entity_identity ri ON ri.entity_type='users' AND ri.global_id=rn.recipient_gid
                JOIN eatwhat_sync.relation_operation ro ON ro.event_id=rn.source_event_id
                JOIN eatwhat_identity.entity_identity ai ON ai.entity_type='users'
                  AND ai.global_id=(ro.payload->>'actor')::uuid
                LEFT JOIN eatwhat_identity.entity_identity ti ON ti.global_id=(ro.payload->>'target')::uuid
                  AND ti.entity_type=CASE ro.payload->>'kind'
                    WHEN 'activity_food_like' THEN 'activity_foods'
                    WHEN 'activity_dinner_like' THEN 'activity_dinners'
                    WHEN 'activity_post_like' THEN 'activity_posts'
                    ELSE 'users' END
                LEFT JOIN public.notifications n ON n.id=ni.numeric_id
                WHERE rn.global_id=? AND rn.numeric_id=? AND rn.recipient_gid=?
                """, (row, index) -> new Source(row.getString(1), row.getLong(2), row.getString(3), row.getLong(4),
                row.getLong(5), row.getString(6), row.getString(7), row.getString(8),
                row.getObject(9) == null ? null : row.getLong(9),
                row.getObject(10) == null ? null : row.getLong(10),
                row.getObject(11) == null ? null : row.getLong(11),
                row.getObject(12) == null ? null : row.getLong(12),
                row.getString(13), row.getString(14),
                row.getObject(15) == null ? null : row.getLong(15),
                row.getObject(16) == null ? null : row.getLong(16),
                row.getObject(17) == null ? null : row.getLong(17),
                row.getLong(18), row.getLong(19), row.getBoolean(20)),
                operation.notificationGid(), operation.notificationId(), operation.recipientGid());
        if (rows.isEmpty()) return false;
        Source source = rows.getFirst();
        if (source.numericId() != operation.notificationId()
                || !source.originSite().equals(operation.notificationOrigin())
                || source.actorNumericId() <= 0 || !operation.actorGid().toString().equals(source.sourceActor())
                || !operation.recipientGid().toString().equals(source.sourceOwner())) return false;
        tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        var payload = json.readTree(source.payload());
        if (!operation.notificationGid().toString().equals(payload.path("globalId").stringValue())
                || !operation.recipientGid().toString().equals(payload.path("recipientGid").stringValue())
                || !operation.notificationOrigin().equals(payload.path("originSite").stringValue())
                || !operation.actorGid().toString().equals(source.sourceActor())) return false;
        RelationEvents.Operation relation;
        try {
            relation = RelationEvents.parse(source.relationPayload());
        } catch (RuntimeException invalidSource) {
            return false;
        }
        RelationEvents.Notice notice = relation.notification();
        if (notice == null || !relation.actor().equals(operation.actorGid())
                || !relation.owner().equals(operation.recipientGid())
                || !notice.globalId().equals(operation.notificationGid())
                || notice.numericId() != operation.notificationId()
                || !notice.recipientGid().equals(operation.recipientGid())
                || !notice.originSite().equals(operation.notificationOrigin())
                || !sourceOwnerMatches(source, operation)
                || !notice.kind().equals(payload.path("kind").stringValue())
                || !notice.targetType().equals(payload.path("targetType").stringValue())
                || notice.numericId() != source.numericId()
                || !integralEquals(payload.path("numericId"), source.numericId())
                || !integralEquals(payload.path("createdAt"), source.createdAtPayload())
                || !integralEquals(payload.path("expiresAt"), source.expiresAtPayload())
                || notice.createdAt() != source.createdAtPayload()
                || notice.expiresAt() != source.expiresAtPayload()
                || source.expectedTargetId() == null
                || !notificationTargetMatches(relation, notice, source.actorNumericId(), source.expectedTargetId())) {
            return false;
        }
        if (source.notificationNumericId() == null) {
            return source.expired();
        }
        return source.accountId() != null && source.actorId() != null
                && source.accountId() == source.recipientNumericId() && source.actorId() == source.actorNumericId()
                && source.kind() != null && payload.path("kind").stringValue().equals(source.kind())
                && source.targetType() != null && payload.path("targetType").stringValue().equals(source.targetType())
                && source.targetId() != null && source.createdAt() != null && source.expiresAt() != null
                && integralEquals(payload.path("createdAt"), source.createdAt())
                && integralEquals(payload.path("expiresAt"), source.expiresAt())
                && source.targetId().longValue() == source.expectedTargetId().longValue()
                && source.expiresAt() == source.expiresAtPayload();
    }

    private static boolean sourceOwnerMatches(Source source, NotificationReadEvents.Operation operation) {
        return operation.recipientGid().toString().equals(source.sourceOwner());
    }

    private static boolean notificationTargetMatches(RelationEvents.Operation relation, RelationEvents.Notice notice,
                                                     long actorNumericId, Long targetNumericId) {
        if (relation.kind() == RelationKind.follow) {
            return "follow".equals(notice.kind()) && "user".equals(notice.targetType())
                    && targetNumericId == actorNumericId;
        }
        if (relation.kind() == RelationKind.activity_food_like) {
            return "like".equals(notice.kind()) && "food".equals(notice.targetType());
        }
        if (relation.kind() == RelationKind.activity_dinner_like) {
            return "like".equals(notice.kind()) && "dinner".equals(notice.targetType());
        }
        if (relation.kind() == RelationKind.activity_post_like) {
            return "like".equals(notice.kind()) && "post".equals(notice.targetType());
        }
        return false;
    }

    private static boolean integralEquals(tools.jackson.databind.JsonNode value, long expected) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() == expected;
    }

    private record Source(String payload, long numericId, String originSite, long recipientNumericId,
                          long actorNumericId, String sourceActor, String sourceOwner, String relationPayload,
                          Long expectedTargetId, Long notificationNumericId,
                          Long accountId, Long actorId, String kind, String targetType, Long targetId,
                          Long createdAt, Long expiresAt, long createdAtPayload, long expiresAtPayload, boolean expired) { }
}
