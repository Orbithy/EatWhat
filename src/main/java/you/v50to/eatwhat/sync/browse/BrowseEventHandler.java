package you.v50to.eatwhat.sync.browse;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.identity.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.Objects;

/** Inserts a local history projection without invoking capture, deduplication or changing viewed time. */
public final class BrowseEventHandler implements EventHandler {
    private final JdbcTemplate jdbc;
    private final ConsentStore consent;
    private final EntityIdentityService identities;
    private final BrowseEvents policy;

    public BrowseEventHandler(JdbcTemplate jdbc, ConsentStore consent) {
        this.jdbc = jdbc; this.consent = consent; this.identities = new EntityIdentityService(jdbc);
        this.policy = new BrowseEvents(consent);
    }
    @Override public String aggregateType() { return BrowseEvents.AGGREGATE; }
    @Override public String eventType() { return BrowseEvents.EVENT; }
    @Override public int schemaVersion() { return 1; }

    private BrowseEvents.Observation observation(SyncEvent event) {
        policy.validate(event, new NodeIdentity(event.originSite(), event.originInstanceId()));
        return BrowseEvents.parse(event.payloadJson());
    }

    private boolean stale(BrowseEvents.Observation op) {
        var actor = consent.getBySubject(op.actor()); var owner = consent.getBySubject(op.owner());
        return actor != null && (actor.epoch() > op.actorEpoch() || actor.epoch() == op.actorEpoch() && actor.status().equals("WITHDRAWN"))
                || owner != null && (owner.epoch() > op.ownerEpoch() || owner.epoch() == op.ownerEpoch() && owner.status().equals("WITHDRAWN"));
    }

    @Override public boolean dependenciesSatisfied(SyncEvent event) {
        var op = observation(event);
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id IN (?,?) ORDER BY user_id FOR SHARE",
                (row, index) -> row.getLong(1), op.actor(), op.owner());
        if (stale(op)) return true;
        var actor = consent.getBySubject(op.actor()); var owner = consent.getBySubject(op.owner());
        if (actor == null || owner == null || actor.epoch() != op.actorEpoch() || owner.epoch() != op.ownerEpoch()
                || !consent.replicationAllowed(actor.userId()) || !consent.replicationAllowed(owner.userId())) return false;
        Long target = identities.findNumericId(EntityType.fromTableName(BrowseCommands.table(op.targetType())), op.target()).orElse(null);
        if (target == null) return false;
        var owners = jdbc.query("SELECT account_id FROM public." + BrowseCommands.table(op.targetType()) + " WHERE id=? FOR SHARE",
                (row, index) -> row.getLong(1), target);
        if (owners.isEmpty()) return terminal(op);
        if (!Objects.equals(owners.getFirst(), owner.userId())) throw new IllegalArgumentException("BROWSE_OWNER_MISMATCH");
        return true;
    }

    private boolean terminal(BrowseEvents.Observation op) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object c
                  JOIN eatwhat_identity.entity_identity i ON i.entity_type=? AND i.global_id=c.object_gid
                    AND i.numeric_id=c.numeric_id AND i.origin_site=c.origin_site
                  WHERE c.object_gid=? AND c.kind=? AND c.author_gid=?
                    AND (c.tombstone OR c.rejected OR (?='food' AND EXISTS(
                      SELECT 1 FROM eatwhat_sync.catalog_object p
                      JOIN eatwhat_identity.entity_identity pi ON pi.entity_type='restaurant' AND pi.global_id=p.object_gid
                        AND pi.numeric_id=p.numeric_id AND pi.origin_site=p.origin_site
                      WHERE p.kind='RESTAURANT' AND p.object_gid=c.parent_gid AND (p.tombstone OR p.rejected)))
                    ) AND NOT EXISTS(SELECT 1 FROM public.%s t WHERE t.id=i.numeric_id) FOR KEY SHARE OF c)
                """.formatted(BrowseCommands.table(op.targetType())), Boolean.class, BrowseCommands.table(op.targetType()), op.target(),
                op.targetType().equals("food") ? "FOOD" : "RESTAURANT", op.owner(), op.targetType()));
    }

    @Override public void apply(SyncEvent event) {
        var op = observation(event);
        if (stale(op)) return;
        if (terminal(op)) return;
        var actor = consent.getBySubject(op.actor()); var owner = consent.getBySubject(op.owner());
        if (actor == null || owner == null || actor.epoch() != op.actorEpoch() || owner.epoch() != op.ownerEpoch()
                || !consent.replicationAllowed(actor.userId()) || !consent.replicationAllowed(owner.userId())) return;
        long target = identities.findNumericId(EntityType.fromTableName(BrowseCommands.table(op.targetType())), op.target()).orElseThrow();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", row -> {}, "browse-visit:" + op.visitId());
        var existing = jdbc.queryForList("SELECT history_id FROM eatwhat_sync.browse_visit WHERE visit_id=?", Long.class, op.visitId());
        if (existing.isEmpty()) {
            long historyId = jdbc.queryForObject("""
                INSERT INTO public.browse_history(account_id,target_type,target_id,created_at,sync_visit_id,sync_origin_site,
                  sync_actor_epoch,sync_owner_epoch)
                VALUES(?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, actor.userId(), op.targetType(), target, BrowseCommands.timestamp(op.viewedAtMicros()),
                op.visitId(), event.originSite(), op.actorEpoch(), op.ownerEpoch());
            jdbc.update("INSERT INTO eatwhat_sync.browse_visit(visit_id,history_id,origin_site) VALUES(?,?,?)",
                    op.visitId(), historyId, event.originSite());
        }
        // A reused visit ID with different content is a conflict, never an UPDATE or a second visit.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.browse_history b JOIN eatwhat_sync.browse_visit v ON v.history_id=b.id
                  WHERE v.visit_id=? AND b.sync_visit_id=v.visit_id AND account_id=?
                  AND target_type=? AND target_id=? AND created_at=? AND b.sync_origin_site=? AND v.origin_site=b.sync_origin_site)
                """, Boolean.class, op.visitId(), actor.userId(), op.targetType(), target,
                BrowseCommands.timestamp(op.viewedAtMicros()), event.originSite()))) {
            throw new IllegalArgumentException("BROWSE_VISIT_IDENTITY_CONFLICT");
        }
    }
}
