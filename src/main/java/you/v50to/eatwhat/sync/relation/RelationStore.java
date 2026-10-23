package you.v50to.eatwhat.sync.relation;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Serializes a business key and merges immutable operations in the caller's transaction. */
public final class RelationStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public RelationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(RelationKind kind, long actor, long target, Long tag) {
        String key = kind + ":" + actor + ":" + target + ":" + (tag == null ? 0 : tag);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class, key);
    }

    public VersionVector state(UUID relationId) {
        jdbc.update("INSERT INTO eatwhat_sync.relation_state(relation_id) VALUES (?) ON CONFLICT DO NOTHING",
                relationId);
        String vector = jdbc.queryForObject("""
                SELECT vector::text FROM eatwhat_sync.relation_state WHERE relation_id=? FOR UPDATE
                """, String.class, relationId);
        return EventCodec.vectorFromJson(vector);
    }

    public boolean satisfied(RelationEvents.Operation operation) {
        VersionVector.Relation relation = state(operation.relationId()).compare(operation.vector());
        return relation == VersionVector.Relation.EQUAL || relation == VersionVector.Relation.AFTER;
    }

    public boolean merge(UUID eventId, RelationEvents.Operation operation) {
        VersionVector current = state(operation.relationId());
        if (!satisfied(operation)) {
            throw new IllegalStateException("Missing relation causal predecessor");
        }

        String payload = RelationEvents.payload(operation);
        jdbc.update("""
                INSERT INTO eatwhat_sync.relation_operation
                    (event_id, relation_id, dot_site, dot_counter, context, adding, payload)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """, eventId, operation.relationId(), operation.dotSite(), operation.dotCounter(),
                EventCodec.vectorJson(operation.vector()), operation.adding(), payload);

        boolean insertedEvidenceMatches = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM eatwhat_sync.relation_operation
                  WHERE event_id=? AND relation_id=? AND dot_site=? AND dot_counter=? AND payload=?::jsonb
                )
                """, Boolean.class, eventId, operation.relationId(), operation.dotSite(),
                operation.dotCounter(), payload));
        if (!insertedEvidenceMatches) {
            throw new IllegalStateException("Relation dot already has different immutable evidence");
        }

        var merged = new TreeMap<>(current.counters());
        merged.merge(operation.dotSite(), operation.dotCounter(), Math::max);
        jdbc.update("UPDATE eatwhat_sync.relation_state SET vector=?::jsonb WHERE relation_id=?",
                EventCodec.vectorJson(new VersionVector(merged)), operation.relationId());

        // An add survives only if it observed every remove. Older and concurrent adds cannot resurrect it.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1
                  FROM eatwhat_sync.relation_operation AS add_op
                  WHERE add_op.relation_id=? AND add_op.adding
                    AND NOT EXISTS (
                      SELECT 1
                      FROM eatwhat_sync.relation_operation AS remove_op
                      WHERE remove_op.relation_id=add_op.relation_id AND NOT remove_op.adding
                        AND coalesce((add_op.context->>remove_op.dot_site)::bigint, 0) < remove_op.dot_counter
                    )
                )
                """, Boolean.class, operation.relationId()));
    }

    public record Projection(long rowId, UUID rowGid, String rowOrigin) {
    }

    public record Intent(UUID naturalId, RelationKind kind, UUID actor, UUID target, UUID owner, UUID tag,
                         boolean desiredPresent, long revision, long publishedRevision,
                         Long publishedActorEpoch, Long publishedOwnerEpoch,
                         long rowId, UUID rowGid, String rowOrigin, boolean localIntent) {
        public boolean needsPublish(long actorEpoch, long ownerEpoch) {
            return publishedRevision < revision || publishedActorEpoch == null || publishedOwnerEpoch == null
                    || publishedActorEpoch != actorEpoch || publishedOwnerEpoch != ownerEpoch;
        }
    }

    public static UUID naturalId(RelationKind kind, UUID actor, UUID target, UUID owner, UUID tag) {
        return UUID.nameUUIDFromBytes((kind.name() + ":" + actor + ":" + target + ":" + owner + ":" + tag)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Persist the caller's local intent even when consent currently prevents event publication. */
    public Intent recordLocalIntent(RelationKind kind, UUID actor, UUID target, UUID owner, UUID tag,
                                    boolean desired, Projection projection) {
        UUID naturalId = naturalId(kind, actor, target, owner, tag);
        jdbc.update("""
                INSERT INTO eatwhat_sync.relation_intent
                    (natural_id,kind,actor_gid,target_gid,owner_gid,tag_gid,desired_present,
                     intent_revision,published_revision,row_id,row_gid,row_origin,local_intent)
                VALUES(?,?,?,?,?,?,?,1,0,?,?,?,true)
                ON CONFLICT(natural_id) DO UPDATE SET
                  desired_present=EXCLUDED.desired_present,
                  intent_revision=eatwhat_sync.relation_intent.intent_revision + 1,
                  local_intent=true,blocked_reason=NULL,updated_at=clock_timestamp()
                """, naturalId, kind.name(), actor, target, owner, tag, desired,
                projection.rowId(), projection.rowGid(), projection.rowOrigin());
        return readIntent(naturalId).orElseThrow();
    }

    /** Historical/current projection seed is inserted once and never overwrites an explicit local intent. */
    public Intent seedIntent(RelationKind kind, UUID actor, UUID target, UUID owner, UUID tag,
                             boolean desired, Projection projection) {
        UUID naturalId = naturalId(kind, actor, target, owner, tag);
        jdbc.update("""
                INSERT INTO eatwhat_sync.relation_intent
                    (natural_id,kind,actor_gid,target_gid,owner_gid,tag_gid,desired_present,
                     intent_revision,published_revision,row_id,row_gid,row_origin,local_intent)
                VALUES(?,?,?,?,?,?,?,1,0,?,?,?,false)
                ON CONFLICT(natural_id) DO NOTHING
                """, naturalId, kind.name(), actor, target, owner, tag, desired,
                projection.rowId(), projection.rowGid(), projection.rowOrigin());
        return readIntent(naturalId).orElseThrow();
    }

    public Optional<Intent> readIntent(UUID naturalId) {
        return jdbc.query("""
                SELECT natural_id,kind,actor_gid,target_gid,owner_gid,tag_gid,desired_present,
                       intent_revision,published_revision,published_actor_epoch,published_owner_epoch,
                       row_id,row_gid,row_origin,local_intent
                FROM eatwhat_sync.relation_intent WHERE natural_id=?
                """, (row, index) -> new Intent(row.getObject(1, UUID.class),
                RelationKind.valueOf(row.getString(2)), row.getObject(3, UUID.class), row.getObject(4, UUID.class),
                row.getObject(5, UUID.class), row.getObject(6, UUID.class), row.getBoolean(7), row.getLong(8),
                row.getLong(9), (Long) row.getObject(10), (Long) row.getObject(11), row.getLong(12),
                row.getObject(13, UUID.class), row.getString(14), row.getBoolean(15)), naturalId)
                .stream().findFirst();
    }

    public List<Intent> pendingIntents(UUID subject, String afterNaturalId) {
        return jdbc.query("""
                SELECT i.natural_id,i.kind,i.actor_gid,i.target_gid,i.owner_gid,i.tag_gid,i.desired_present,
                       i.intent_revision,i.published_revision,i.published_actor_epoch,i.published_owner_epoch,
                       i.row_id,i.row_gid,i.row_origin,i.local_intent
                FROM eatwhat_sync.relation_intent i
                WHERE (i.actor_gid=? OR i.owner_gid=?)
                  AND i.blocked_reason IS NULL
                  AND (?::uuid IS NULL OR i.natural_id>?::uuid)
                  AND (i.published_revision<i.intent_revision OR i.published_actor_epoch IS NULL
                    OR i.published_owner_epoch IS NULL OR i.published_actor_epoch<>coalesce(
                         (SELECT s.epoch FROM eatwhat_consent.subject s WHERE s.subject_id=i.actor_gid),-1)
                    OR i.published_owner_epoch<>coalesce(
                         (SELECT s.epoch FROM eatwhat_consent.subject s WHERE s.subject_id=i.owner_gid),-1))
                ORDER BY i.natural_id LIMIT 64
                """, (row, index) -> new Intent(row.getObject(1, UUID.class), RelationKind.valueOf(row.getString(2)),
                row.getObject(3, UUID.class), row.getObject(4, UUID.class), row.getObject(5, UUID.class),
                row.getObject(6, UUID.class), row.getBoolean(7), row.getLong(8), row.getLong(9),
                (Long) row.getObject(10), (Long) row.getObject(11), row.getLong(12),
                row.getObject(13, UUID.class), row.getString(14), row.getBoolean(15)),
                subject, subject, afterNaturalId, afterNaturalId);
    }

    public void markPublished(Intent intent, long actorEpoch, long ownerEpoch, boolean effectivePresent) {
        jdbc.update("""
                UPDATE eatwhat_sync.relation_intent
                SET desired_present=?,published_revision=?,published_actor_epoch=?,published_owner_epoch=?,
                    local_intent=false,updated_at=clock_timestamp()
                WHERE natural_id=? AND intent_revision=?
                """, effectivePresent, intent.revision(), actorEpoch, ownerEpoch,
                intent.naturalId(), intent.revision());
    }

    public void markObserved(RelationKind kind, UUID actor, UUID target, UUID owner, UUID tag,
                             Projection projection, boolean effectivePresent,
                             long actorEpoch, long ownerEpoch) {
        UUID naturalId = naturalId(kind, actor, target, owner, tag);
        jdbc.update("""
                INSERT INTO eatwhat_sync.relation_intent
                    (natural_id,kind,actor_gid,target_gid,owner_gid,tag_gid,desired_present,
                     intent_revision,published_revision,published_actor_epoch,published_owner_epoch,
                     row_id,row_gid,row_origin,local_intent)
                VALUES(?,?,?,?,?,?,?,1,1,?,?,?,?,?,false)
                ON CONFLICT(natural_id) DO UPDATE SET
                  desired_present=CASE WHEN eatwhat_sync.relation_intent.local_intent
                    AND eatwhat_sync.relation_intent.published_revision<eatwhat_sync.relation_intent.intent_revision
                    THEN eatwhat_sync.relation_intent.desired_present ELSE EXCLUDED.desired_present END,
                  published_revision=CASE WHEN eatwhat_sync.relation_intent.local_intent
                    AND eatwhat_sync.relation_intent.published_revision<eatwhat_sync.relation_intent.intent_revision
                    THEN eatwhat_sync.relation_intent.published_revision ELSE eatwhat_sync.relation_intent.intent_revision END,
                  published_actor_epoch=CASE WHEN eatwhat_sync.relation_intent.local_intent
                    AND eatwhat_sync.relation_intent.published_revision<eatwhat_sync.relation_intent.intent_revision
                    THEN eatwhat_sync.relation_intent.published_actor_epoch ELSE EXCLUDED.published_actor_epoch END,
                  published_owner_epoch=CASE WHEN eatwhat_sync.relation_intent.local_intent
                    AND eatwhat_sync.relation_intent.published_revision<eatwhat_sync.relation_intent.intent_revision
                    THEN eatwhat_sync.relation_intent.published_owner_epoch ELSE EXCLUDED.published_owner_epoch END,
                  row_id=EXCLUDED.row_id,row_gid=EXCLUDED.row_gid,
                  row_origin=EXCLUDED.row_origin,
                  local_intent=CASE WHEN eatwhat_sync.relation_intent.local_intent
                    AND eatwhat_sync.relation_intent.published_revision<eatwhat_sync.relation_intent.intent_revision
                    THEN true ELSE false END,blocked_reason=NULL,updated_at=clock_timestamp()
                """, naturalId, kind.name(), actor, target, owner, tag, effectivePresent,
                actorEpoch, ownerEpoch, projection.rowId(), projection.rowGid(), projection.rowOrigin());
    }

    public Projection project(RelationKind kind, long actor, long target, Long tag,
                              boolean present, RelationEvents.Operation operation, String eventOrigin) {
        boolean claimedSharedIdentity = operation != null && isClaimedSharedIdentity(
                kind, actor, target, tag, operation);
        String result = jdbc.queryForObject("""
                SELECT eatwhat_sync.project_relation(?, ?, ?, ?, ?, ?, ?, ?, ?)::text
                """, String.class, kind.name(), actor, target, tag, present,
                operation == null || claimedSharedIdentity ? null : operation.rowId(),
                operation == null || claimedSharedIdentity ? null : operation.rowGid(),
                operation == null || claimedSharedIdentity ? null : operation.rowOrigin(), eventOrigin);
        var json = JSON.readTree(result);
        return new Projection(json.path("row_id").longValue(),
                UUID.fromString(json.path("row_gid").stringValue()),
                json.path("row_origin").stringValue());
    }

    private boolean isClaimedSharedIdentity(RelationKind kind, long actor, long target, Long tag,
                                             RelationEvents.Operation operation) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM eatwhat_sync.relation_baseline_claim c
                  WHERE c.entity_type=? AND c.numeric_id=? AND c.global_id=? AND c.declared_origin=?
                    AND c.natural_key=jsonb_build_object('kind',?,'actor',?::uuid,'target',?::uuid,
                                                        'owner',?::uuid,'tag',?::uuid)
                    AND EXISTS(SELECT 1 FROM eatwhat_sync.relation_projection p
                      WHERE p.relation_key=? AND p.entity_type=c.entity_type AND p.numeric_id=c.numeric_id
                        AND p.global_id=c.global_id AND p.origin_site=c.declared_origin)
                )
                """, Boolean.class, kind.table, operation.rowId(), operation.rowGid(), operation.rowOrigin(),
                kind.name(), operation.actor(), operation.target(), operation.owner(), operation.tag(),
                kind.name() + ":" + actor + ":" + target + ":" + (tag == null ? 0 : tag)));
    }

    public boolean present(RelationKind kind, long actor, long target, Long tag) {
        StringBuilder sql = new StringBuilder("SELECT EXISTS (SELECT 1 FROM public.")
                .append(kind.table).append(" WHERE account_id=? AND ").append(kind.targetColumn).append("=?");
        var arguments = new ArrayList<Object>();
        arguments.add(actor);
        arguments.add(target);
        if (kind.targetType != null) {
            sql.append(" AND target_type=? AND deleted_at IS NULL");
            arguments.add(kind.targetType);
        }
        if (tag != null) {
            sql.append(" AND tag_id=?");
            arguments.add(tag);
        }
        sql.append(")");
        return Boolean.TRUE.equals(jdbc.queryForObject(sql.toString(), Boolean.class, arguments.toArray()));
    }

    public void notification(UUID eventId, long actor, long target, RelationEvents.Notice notice,
                             String eventOrigin) {
        jdbc.queryForObject("""
                SELECT eatwhat_sync.import_relation_notification(?, ?, ?, ?, ?::jsonb)::text
                """, String.class, eventId, actor, target, eventOrigin,
                JSON.writeValueAsString(notice));
    }

    public Long resolve(String table, UUID globalId) {
        var rows = jdbc.query("""
                SELECT identity.numeric_id
                FROM eatwhat_identity.entity_identity AS identity
                JOIN public.%s AS business ON business.id=identity.numeric_id
                WHERE identity.entity_type=? AND identity.global_id=?
                """.formatted(table), (row, index) -> row.getLong(1), table, globalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public UUID relationId(Intent intent, long actorEpoch, long ownerEpoch) {
        return UUID.nameUUIDFromBytes((intent.kind().name() + ":" + intent.actor() + ":" + intent.target()
                + ":" + intent.tag() + ":" + actorEpoch + ":" + ownerEpoch).getBytes(StandardCharsets.UTF_8));
    }

    public boolean blocked(UUID naturalId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.relation_intent WHERE natural_id=? AND blocked_reason IS NOT NULL)
                """, Boolean.class, naturalId));
    }

    public void blockIntent(UUID naturalId, String reason) {
        jdbc.update("UPDATE eatwhat_sync.relation_intent SET blocked_reason=?,updated_at=clock_timestamp() WHERE natural_id=?",
                reason, naturalId);
    }
}
