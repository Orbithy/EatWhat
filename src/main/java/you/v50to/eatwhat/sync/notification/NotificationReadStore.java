package you.v50to.eatwhat.sync.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JDBC boundary for notification read facts, membership, and their per-notice causal projection. */
public final class NotificationReadStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public NotificationReadStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Basic(long id, long accountId, long actorId) { }
    public record Member(UUID batchId, UUID notificationGid, long notificationId, UUID recipientGid,
                         long recipientEpoch, UUID actorGid, long actorEpoch, String notificationOrigin,
                         UUID priorEventId, String status) { }
    public record Epoch(long userId, UUID subjectId, long epoch, String status, boolean identityReady) { }
    public record Notice(UUID notificationGid, long notificationId, String notificationOrigin,
                         UUID recipientGid, UUID actorGid, long actorEpoch, long recipientEpoch,
                         long createdAtMillis, long expiresAtMillis, boolean liveRow) { }
    public record Dot(long numericId, long counter, Map<String, Long> context) { }

    public List<Basic> unreadBasics(long accountId) {
        return jdbc.query("""
                SELECT id,account_id,actor_id FROM public.notifications
                WHERE account_id=? AND read_at IS NULL ORDER BY id
                """, (row, index) -> new Basic(row.getLong(1), row.getLong(2), row.getLong(3)), accountId);
    }

    public List<Basic> unreadBasics(long accountId, Collection<Long> ids, boolean lockRows) {
        if (ids.isEmpty()) return List.of();
        List<Basic> result = new ArrayList<>();
        // Keep each statement well below PostgreSQL's bind-parameter limit for large read-all snapshots.
        for (List<Long> chunk : chunks(ids.stream().distinct().toList())) {
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = "SELECT id,account_id,actor_id FROM public.notifications WHERE account_id=? AND read_at IS NULL AND id IN ("
                    + marks + ") ORDER BY id" + (lockRows ? " FOR UPDATE" : "");
            List<Object> args = new ArrayList<>(); args.add(accountId); args.addAll(chunk);
            result.addAll(jdbc.query(sql, (row, index) -> new Basic(row.getLong(1), row.getLong(2), row.getLong(3)), args.toArray()));
        }
        return result;
    }

    public void lockConsentSubjects(Collection<Long> userIds) {
        var ordered = userIds.stream().distinct().sorted().toList();
        for (long userId : ordered) {
            jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE user_id=? FOR UPDATE",
                    (row, index) -> row.getLong(1), userId);
        }
    }

    public List<Long> userIdsForSubjects(Collection<UUID> subjectIds) {
        var ordered = subjectIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ordered.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        return jdbc.query("""
                SELECT numeric_id FROM eatwhat_identity.entity_identity
                WHERE entity_type='users' AND global_id IN (
                """ + marks + ") ORDER BY numeric_id",
                (row, index) -> row.getLong(1), ordered.toArray());
    }

    public List<Epoch> epochsByUserIds(Collection<Long> userIds) {
        var ordered = userIds.stream().distinct().sorted().toList();
        if (ordered.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        return jdbc.query("""
                SELECT user_id,subject_id,epoch,status,identity_ready FROM eatwhat_consent.subject
                WHERE user_id IN (""" + marks + ") ORDER BY user_id",
                (row, index) -> new Epoch(row.getLong(1), row.getObject(2, UUID.class), row.getLong(3),
                        row.getString(4), row.getBoolean(5)), ordered.toArray());
    }

    /** Validates both the V22 source fact and its original relation operation before a local read is propagated. */
    public List<Notice> stableNotices(long accountId, Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        List<Notice> result = new ArrayList<>();
        for (List<Long> chunk : chunks(ids.stream().distinct().toList())) result.addAll(stableNoticesChunk(accountId, chunk));
        return result;
    }

    private List<Notice> stableNoticesChunk(long accountId, List<Long> ids) {
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = """
                SELECT ni.global_id,n.id,ni.origin_site,ri.global_id,ai.global_id,
                       coalesce(asub.epoch,0),coalesce(rsub.epoch,0),
                       (extract(epoch FROM n.created_at)*1000)::bigint,
                       (extract(epoch FROM n.expires_at)*1000)::bigint,
                       n.expires_at>clock_timestamp()
                FROM public.notifications n
                JOIN eatwhat_identity.entity_identity ni ON ni.entity_type='notifications' AND ni.numeric_id=n.id
                JOIN eatwhat_identity.entity_identity ri ON ri.entity_type='users' AND ri.numeric_id=n.account_id
                JOIN eatwhat_identity.entity_identity ai ON ai.entity_type='users' AND ai.numeric_id=n.actor_id
                JOIN eatwhat_sync.relation_notification rn ON rn.numeric_id=n.id AND rn.global_id=ni.global_id
                  AND rn.recipient_gid=ri.global_id AND rn.kind=n.type
                  AND rn.payload->>'numericId'=n.id::text AND rn.payload->>'globalId'=ni.global_id::text
                  AND rn.payload->>'recipientGid'=ri.global_id::text AND rn.payload->>'originSite'=ni.origin_site
                  AND rn.payload->>'kind'=n.type
                  AND rn.payload->>'targetType'=n.target_type
                  AND (rn.payload->>'createdAt')::bigint=(extract(epoch FROM n.created_at)*1000)::bigint
                  AND (rn.payload->>'expiresAt')::bigint=(extract(epoch FROM n.expires_at)*1000)::bigint
                JOIN eatwhat_sync.relation_operation ro ON ro.event_id=rn.source_event_id
                  AND ro.payload->>'actor'=ai.global_id::text AND ro.payload->>'owner'=ri.global_id::text
                LEFT JOIN eatwhat_consent.subject asub ON asub.subject_id=ai.global_id
                LEFT JOIN eatwhat_consent.subject rsub ON rsub.subject_id=ri.global_id
                WHERE n.account_id=? AND n.id IN (
                """ + marks + ") ORDER BY n.id";
        List<Object> args = new ArrayList<>(); args.add(accountId); args.addAll(ids);
        return jdbc.query(sql, (row, index) -> new Notice(row.getObject(1, UUID.class), row.getLong(2), row.getString(3),
                row.getObject(4, UUID.class), row.getObject(5, UUID.class), row.getLong(6), row.getLong(7),
                row.getLong(8), row.getLong(9), row.getBoolean(10)), args.toArray());
    }

    public void createBatch(UUID batchId, UUID recipientGid, long recipientEpoch, List<Notice> notices) {
        if (notices.isEmpty()) return;
        jdbc.update("INSERT INTO eatwhat_sync.notification_read_batch(batch_id,recipient_gid,epoch,status) VALUES(?,?,?,'READY')",
                batchId, recipientGid, recipientEpoch);
        createMembers(batchId, recipientEpoch, notices);
    }

    /** Permanently claims this recipient's read domain before the first enabled read write. */
    public void markRecipientManaged(long accountId) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.notification_read_domain(recipient_gid)
                SELECT global_id FROM eatwhat_identity.entity_identity
                WHERE entity_type='users' AND numeric_id=?
                ON CONFLICT(recipient_gid) DO NOTHING
                """, accountId);
        Boolean managed = jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM eatwhat_identity.entity_identity i
                  JOIN eatwhat_sync.notification_read_domain d ON d.recipient_gid=i.global_id
                  WHERE i.entity_type='users' AND i.numeric_id=?
                )
                """, Boolean.class, accountId);
        if (!Boolean.TRUE.equals(managed)) {
            throw new IllegalStateException("Notification recipient has no stable global identity");
        }
    }

    /** Claims the recipient's managed read domain after an inbound operation is identity-checked. */
    public void markRecipientManaged(UUID recipientGid) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.notification_read_domain(recipient_gid)
                SELECT global_id FROM eatwhat_identity.entity_identity
                WHERE entity_type='users' AND global_id=?
                ON CONFLICT(recipient_gid) DO NOTHING
                """, recipientGid);
        Boolean managed = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.notification_read_domain WHERE recipient_gid=?)
                """, Boolean.class, recipientGid);
        if (!Boolean.TRUE.equals(managed)) {
            throw new IllegalStateException("Notification recipient has no stable global identity");
        }
    }

    public void createMembers(UUID batchId, long recipientEpoch, List<Notice> notices) {
        if (notices.isEmpty()) return;
        List<Object[]> args = notices.stream().map(notice -> new Object[]{batchId, notice.notificationGid(),
                notice.notificationId(), recipientEpoch, notice.actorGid(), notice.actorEpoch()}).toList();
        jdbc.batchUpdate("""
                INSERT INTO eatwhat_sync.notification_read_member
                  (batch_id,notification_gid,notification_id,recipient_epoch,actor_gid,actor_epoch,status)
                VALUES(?,?,?,?,?,?,'READY') ON CONFLICT(batch_id,notification_gid) DO NOTHING
                """, args);
    }

    public void markRead(long accountId, Collection<Long> ids) {
        for (long id : ids) jdbc.update("""
                UPDATE public.notifications SET read_at=coalesce(read_at,clock_timestamp())
                WHERE account_id=? AND id=? AND read_at IS NULL
                """, accountId, id);
    }

    public List<Member> pendingMembers(int limit) {
        return jdbc.query("""
                WITH eligible AS MATERIALIZED (
                  SELECT m.batch_id,m.notification_gid,m.notification_id,b.recipient_gid,m.recipient_epoch,
                         m.actor_gid,m.actor_epoch,ni.origin_site,m.event_id,m.status,b.created_at,m.last_attempt_at
                  FROM eatwhat_sync.notification_read_member m
                  JOIN eatwhat_sync.notification_read_batch b ON b.batch_id=m.batch_id
                  JOIN eatwhat_identity.entity_identity ni ON ni.entity_type='notifications' AND ni.global_id=m.notification_gid
                  LEFT JOIN eatwhat_consent.subject actor_consent ON actor_consent.subject_id=m.actor_gid
                  LEFT JOIN eatwhat_consent.subject recipient_consent ON recipient_consent.subject_id=b.recipient_gid
                  WHERE (m.status IN ('READY','WAIT') AND b.status IN ('READY','WAIT'))
                    OR (m.status='DONE' AND
                        (actor_consent.epoch IS DISTINCT FROM m.actor_epoch
                         OR recipient_consent.epoch IS DISTINCT FROM m.recipient_epoch))
                )
                SELECT e.batch_id,e.notification_gid,e.notification_id,e.recipient_gid,e.recipient_epoch,
                       e.actor_gid,e.actor_epoch,e.origin_site,e.event_id,e.status
                FROM eligible e
                ORDER BY e.last_attempt_at NULLS FIRST,e.created_at,e.batch_id,e.notification_gid
                LIMIT ?
                """, (row, index) -> new Member(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getLong(3), row.getObject(4, UUID.class), row.getLong(5), row.getObject(6, UUID.class),
                row.getObject(7) == null ? 0L : row.getLong(7), row.getString(8),
                row.getObject(9, UUID.class), row.getString(10)), limit);
    }

    /** Persist fair retry rotation, including WAIT outcomes and attempts interrupted after the row lock. */
    public void markAttempted(Member member) {
        jdbc.update("""
                UPDATE eatwhat_sync.notification_read_member SET last_attempt_at=clock_timestamp()
                WHERE batch_id=? AND notification_gid=?
                """, member.batchId(), member.notificationGid());
    }

    public Member lockMember(Member candidate) {
        return jdbc.query("""
                SELECT m.batch_id,m.notification_gid,m.notification_id,b.recipient_gid,m.recipient_epoch,
                       m.actor_gid,m.actor_epoch,ni.origin_site,m.event_id,m.status
                FROM eatwhat_sync.notification_read_member m
                JOIN eatwhat_sync.notification_read_batch b ON b.batch_id=m.batch_id
                JOIN eatwhat_identity.entity_identity ni ON ni.entity_type='notifications' AND ni.global_id=m.notification_gid
                LEFT JOIN eatwhat_consent.subject actor_consent ON actor_consent.subject_id=m.actor_gid
                LEFT JOIN eatwhat_consent.subject recipient_consent ON recipient_consent.subject_id=b.recipient_gid
                WHERE m.batch_id=? AND m.notification_gid=? AND (
                  (m.status IN ('READY','WAIT') AND b.status IN ('READY','WAIT'))
                  OR (m.status='DONE' AND
                    (actor_consent.epoch IS DISTINCT FROM m.actor_epoch
                     OR recipient_consent.epoch IS DISTINCT FROM m.recipient_epoch)))
                FOR UPDATE OF m SKIP LOCKED
                """, (row, index) -> new Member(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getLong(3), row.getObject(4, UUID.class), row.getLong(5), row.getObject(6, UUID.class),
                row.getObject(7) == null ? 0L : row.getLong(7), row.getString(8),
                row.getObject(9, UUID.class), row.getString(10)), candidate.batchId(), candidate.notificationGid())
                .stream().findFirst().orElse(null);
    }

    public void updateMember(Member member, long recipientEpoch, long actorEpoch, UUID eventId, String status) {
        jdbc.update("""
                UPDATE eatwhat_sync.notification_read_member SET recipient_epoch=?,actor_epoch=?,event_id=?,status=?
                WHERE batch_id=? AND notification_gid=?
                """, recipientEpoch, actorEpoch, eventId, status, member.batchId(), member.notificationGid());
        refreshBatchStatus(member.batchId());
    }

    /** Change retry state without rebinding an old event to consent epochs it was never published under. */
    public void setMemberStatus(Member member, String status) {
        jdbc.update("UPDATE eatwhat_sync.notification_read_member SET status=? WHERE batch_id=? AND notification_gid=?",
                status, member.batchId(), member.notificationGid());
        refreshBatchStatus(member.batchId());
    }

    private void refreshBatchStatus(UUID batchId) {
        jdbc.update("""
                UPDATE eatwhat_sync.notification_read_batch b SET status=CASE
                  WHEN EXISTS(SELECT 1 FROM eatwhat_sync.notification_read_member m WHERE m.batch_id=b.batch_id AND m.status='WAIT') THEN 'WAIT'
                  WHEN EXISTS(SELECT 1 FROM eatwhat_sync.notification_read_member m WHERE m.batch_id=b.batch_id AND m.status='READY') THEN 'READY'
                  ELSE 'DONE' END
                WHERE b.batch_id=?
                """, batchId);
    }

    public boolean delivered(UUID eventId) {
        List<Boolean> rows = jdbc.query("""
                SELECT count(*)>0 AND bool_and(status='ACKED') FROM eatwhat_sync.delivery WHERE event_id=?
                """, (row, index) -> row.getBoolean(1), eventId);
        return !rows.isEmpty() && rows.getFirst();
    }

    public Dot nextDot(UUID notificationGid, UUID recipientGid, String dotSite, String peerDotSite) {
        return nextDot(notificationGid,recipientGid,dotSite,peerDotSite==null?java.util.Set.of():java.util.Set.of(peerDotSite));
    }
    public Dot nextDot(UUID notificationGid, UUID recipientGid, String dotSite, java.util.Set<String> remoteDotSites) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.notification_read_state(notification_gid,recipient_gid,vector)
                VALUES(?,?,'{}'::jsonb) ON CONFLICT(notification_gid,recipient_gid) DO NOTHING
                """, notificationGid, recipientGid);
        String vector = jdbc.queryForObject("""
                SELECT vector::text FROM eatwhat_sync.notification_read_state
                WHERE notification_gid=? AND recipient_gid=? FOR UPDATE
                """, String.class, notificationGid, recipientGid);
        Map<String, Long> current = stateCounters(vector);
        long counter = current.getOrDefault(dotSite, 0L) + 1;
        if (counter > VersionVector.MAX_COUNTER) throw new IllegalStateException("Notification read counter exhausted");
        var context = new java.util.TreeMap<String, Long>();
        if (current.containsKey(dotSite)) context.put(dotSite, current.get(dotSite));
        for(String remote:remoteDotSites)if(current.containsKey(remote))context.put(remote,current.get(remote));
        Long numericId = jdbc.queryForObject("SELECT eatwhat_sync.next_notification_read_number()", Long.class);
        return new Dot(numericId, counter, context);
    }

    public void insertOperation(UUID eventId, Dot dot, NotificationReadEvents.Operation operation) {
        String context = EventCodec.canonicalJson(EventCodec.vectorJson(new VersionVector(operation.context())));
        jdbc.update("""
                INSERT INTO eatwhat_sync.notification_read_operation
                  (event_id,numeric_id,notification_gid,recipient_gid,actor_gid,actor_epoch,epoch,origin_site,dot_counter,context)
                VALUES(?,?,?,?,?,?,?,?,?,?::jsonb)
                """, eventId, dot.numericId(), operation.notificationGid(), operation.recipientGid(),
                operation.actorGid(), operation.actorEpoch(), operation.recipientEpoch(), operation.dotSite(),
                operation.dotCounter(), context);
        mergeStateVector(operation);
    }

    public boolean readPredecessorsSatisfied(NotificationReadEvents.Operation operation) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.notification_read_state(notification_gid,recipient_gid,vector)
                VALUES(?,?,'{}'::jsonb) ON CONFLICT(notification_gid,recipient_gid) DO NOTHING
                """, operation.notificationGid(), operation.recipientGid());
        String vector = jdbc.queryForObject("""
                SELECT vector::text FROM eatwhat_sync.notification_read_state
                WHERE notification_gid=? AND recipient_gid=? FOR UPDATE
                """, String.class, operation.notificationGid(), operation.recipientGid());
        var current = stateCounters(vector);
        var relevant = new java.util.TreeMap<String, Long>();
        for (String site : operation.context().keySet()) {
            if (current.containsKey(site)) relevant.put(site, current.get(site));
        }
        if (current.containsKey(operation.dotSite())) relevant.put(operation.dotSite(), current.get(operation.dotSite()));
        VersionVector.Relation relation = new VersionVector(relevant).compare(operation.vector());
        return relation == VersionVector.Relation.EQUAL || relation == VersionVector.Relation.AFTER;
    }

    public void applyOperation(UUID eventId, long numericId, NotificationReadEvents.Operation operation) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.notification_read_operation
                  (event_id,numeric_id,notification_gid,recipient_gid,actor_gid,actor_epoch,epoch,origin_site,dot_counter,context)
                VALUES(?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(event_id) DO NOTHING
                """, eventId, numericId, operation.notificationGid(), operation.recipientGid(), operation.actorGid(),
                operation.actorEpoch(), operation.recipientEpoch(), operation.dotSite(), operation.dotCounter(),
                EventCodec.vectorJson(operation.vector()));
        boolean same = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.notification_read_operation
                  WHERE event_id=? AND numeric_id=? AND notification_gid=? AND recipient_gid=? AND actor_gid=?
                    AND actor_epoch=? AND epoch=? AND origin_site=? AND dot_counter=? AND context=?::jsonb)
                """, Boolean.class, eventId, numericId, operation.notificationGid(), operation.recipientGid(),
                operation.actorGid(), operation.actorEpoch(), operation.recipientEpoch(), operation.dotSite(),
                operation.dotCounter(), EventCodec.vectorJson(operation.vector())));
        if (!same) throw new IllegalStateException("Notification read immutable identity collision");
        markRecipientManaged(operation.recipientGid());
        mergeStateVector(operation);
        jdbc.update("""
                UPDATE public.notifications SET read_at=coalesce(read_at,clock_timestamp())
                WHERE id=? AND account_id=(SELECT numeric_id FROM eatwhat_identity.entity_identity
                  WHERE entity_type='users' AND global_id=?) AND expires_at>clock_timestamp()
                """, operation.notificationId(), operation.recipientGid());
    }

    /** Preserve every known frontier component while the projection row is locked. */
    private void mergeStateVector(NotificationReadEvents.Operation operation) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.notification_read_state(notification_gid,recipient_gid,vector)
                VALUES(?,?,'{}'::jsonb) ON CONFLICT(notification_gid,recipient_gid) DO NOTHING
                """, operation.notificationGid(), operation.recipientGid());
        String json = jdbc.queryForObject("""
                SELECT vector::text FROM eatwhat_sync.notification_read_state
                WHERE notification_gid=? AND recipient_gid=? FOR UPDATE
                """, String.class, operation.notificationGid(), operation.recipientGid());
        var merged = new java.util.TreeMap<>(stateCounters(json));
        operation.context().forEach((site, counter) -> merged.merge(site, counter, Math::max));
        merged.merge(operation.dotSite(), operation.dotCounter(), Math::max);
        jdbc.update("""
                UPDATE eatwhat_sync.notification_read_state SET vector=?::jsonb
                WHERE notification_gid=? AND recipient_gid=?
                """, EventCodec.canonicalJson(JSON.writeValueAsString(merged)), operation.notificationGid(), operation.recipientGid());
    }

    /** Persistent projection may outlive many consent epochs; only event contexts use the 16-site vector limit. */
    private static Map<String, Long> stateCounters(String json) {
        JsonNode node = JSON.readTree(json);
        if (node == null || !node.isObject()) throw new IllegalStateException("Invalid notification read state vector");
        var counters = new java.util.TreeMap<String, Long>();
        node.properties().forEach(entry -> {
            String site = entry.getKey();
            JsonNode value = entry.getValue();
            you.v50to.eatwhat.sync.SyncEvent.requireName(site, "vector site", 64);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0
                    || value.longValue() > VersionVector.MAX_COUNTER) {
                throw new IllegalStateException("Invalid notification read state counter");
            }
            if (value.longValue() > 0) counters.put(site, value.longValue());
        });
        return counters;
    }

    private static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> result = new ArrayList<>();
        for (int start = 0; start < values.size(); start += 500) {
            result.add(values.subList(start, Math.min(values.size(), start + 500)));
        }
        return result;
    }
}
