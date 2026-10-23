package you.v50to.eatwhat.sync.browse;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local history and its immutable Outbox append share a commit. Unpublished rows are the durable backfill queue. */
public final class BrowseCommands {
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final OutboxService outbox;
    private final ConsentStore consent;
    private final SiteProperties site;
    private final String peer;
    private final int batchSize;
    private final EntityIdentityService identities;
    private final BrowseEvents policy;
    private final AtomicBoolean active = new AtomicBoolean();

    public BrowseCommands(JdbcTemplate jdbc, SyncTransactions transactions, OutboxService outbox,
                          ConsentStore consent, SiteProperties site, String peer, int batchSize) {
        this.jdbc = jdbc; this.transactions = transactions; this.outbox = outbox; this.consent = consent;
        this.site = site; this.peer = peer; this.batchSize = batchSize;
        this.identities = new EntityIdentityService(jdbc); this.policy = new BrowseEvents(consent);
    }

    static String table(String type) {
        return switch (type) { case "food" -> "foods"; case "restaurant" -> "restaurant";
            default -> throw new IllegalArgumentException("INVALID_BROWSE_TARGET"); };
    }

    private Long owner(String type, long target, boolean lock) {
        return jdbc.query("SELECT account_id FROM public." + table(type) + " WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), (row, index) -> row.getLong(1), target).stream().findFirst().orElse(null);
    }

    private void lockSubjects(long actor, long owner) {
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE user_id IN (?,?) ORDER BY user_id FOR SHARE",
                (row, index) -> row.getLong(1), actor, owner);
        jdbc.query("SELECT id FROM public.users WHERE id IN (?,?) ORDER BY id FOR KEY SHARE",
                (row, index) -> row.getLong(1), actor, owner);
        // Serialize the five-minute deduplication even when different targets are visited concurrently.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", row -> {}, "browse:" + actor);
    }

    public boolean record(long actor, String type, long target) {
        table(type);
        return transactions.required(() -> {
            Long owner = owner(type, target, false);
            if (owner == null) return false;
            lockSubjects(actor, owner);
            if (!Objects.equals(owner, owner(type, target, true))) return false;
            // Campus local use does not require a grant; withdrawn external sessions may not write.
            if (site.getMode() == SiteProperties.Mode.EXTERNAL && !consent.replicationAllowed(actor)) return false;
            if (jdbc.queryForObject("SELECT count(*) FROM public.users WHERE id=?", Long.class, actor) != 1) return false;
            var ids = jdbc.query("""
                    INSERT INTO public.browse_history(account_id,target_type,target_id,created_at,sync_visit_id,sync_origin_site)
                    SELECT ?,?,?,clock_timestamp(),?,?
                    WHERE NOT EXISTS(SELECT 1 FROM public.browse_history WHERE account_id=? AND target_type=?
                      AND target_id=? AND created_at>=clock_timestamp()-interval '5 minutes') RETURNING id
                    """, (row, index) -> row.getLong(1), actor, type, target, UUID.randomUUID(), site.getId(), actor, type, target);
            if (ids.isEmpty()) return false;
            publishLocked(ids.getFirst());
            return true;
        });
    }

    private record Visit(long id, long actor, String type, long target, Instant at, UUID visit, String origin,
                         long actorEpoch, long ownerEpoch) { }

    private Visit visit(long id, boolean lock) {
        return jdbc.query("""
                SELECT b.*,coalesce(b.sync_visit_id,i.global_id) AS visit,
                       coalesce(b.sync_origin_site,i.origin_site) AS origin
                FROM public.browse_history b JOIN eatwhat_identity.entity_identity i
                  ON i.entity_type='browse_history' AND i.numeric_id=b.id WHERE b.id=?
                """ + (lock ? " FOR UPDATE OF b" : ""), (row, index) -> new Visit(row.getLong("id"),
                row.getLong("account_id"), row.getString("target_type"), row.getLong("target_id"),
                row.getTimestamp("created_at").toInstant(), row.getObject("visit", UUID.class), row.getString("origin"),
                row.getLong("sync_actor_epoch"), row.getLong("sync_owner_epoch")), id).stream().findFirst().orElse(null);
    }

    public boolean publish(long id) {
        return transactions.required(() -> {
            Visit visit = visit(id, false);
            if (visit == null || !site.getId().equals(visit.origin())) return false;
            Long owner = owner(visit.type(), visit.target(), false);
            if (owner == null) return false;
            lockSubjects(visit.actor(), owner);
            if (!Objects.equals(owner, owner(visit.type(), visit.target(), true))) return false;
            return publishLocked(id);
        });
    }

    private boolean publishLocked(long id) {
        Visit visit = visit(id, true);
        if (visit == null || !site.getId().equals(visit.origin())) return false;
        Long owner = owner(visit.type(), visit.target(), false);
        if (owner == null || !consent.replicationAllowed(visit.actor()) || !consent.replicationAllowed(owner)) return false;
        var actorState = consent.get(visit.actor()); var ownerState = consent.get(owner);
        if (visit.actorEpoch() == actorState.epoch() && visit.ownerEpoch() == ownerState.epoch()) return false;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", row -> {}, "browse-visit:" + visit.visit());
        jdbc.update("INSERT INTO eatwhat_sync.browse_visit(visit_id,history_id,origin_site) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                visit.visit(), visit.id(), visit.origin());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.browse_visit WHERE visit_id=? AND history_id=? AND origin_site=?)",
                Boolean.class, visit.visit(), visit.id(), visit.origin()))) {
            throw new IllegalArgumentException("BROWSE_VISIT_IDENTITY_CONFLICT");
        }
        var observation = new BrowseEvents.Observation(visit.visit(), global(EntityType.USERS, visit.actor()),
                global(EntityType.USERS, owner), visit.type(), global(EntityType.fromTableName(table(visit.type())), visit.target()),
                actorState.epoch(), ownerState.epoch(), micros(visit.at()));
        UUID eventId = UUID.randomUUID();
        long number = jdbc.queryForObject("SELECT eatwhat_sync.next_relation_number()", Long.class);
        var event = new SyncEvent(eventId, site.getId(), site.getInstanceId(), BrowseEvents.AGGREGATE, eventId,
                number, BrowseEvents.EVENT, 1, VersionVector.empty(), VersionVector.empty().increment(site.getId()),
                observation.dependencies(), observation.actor(), "browse:" + eventId, eventId, BrowseEvents.payload(observation));
        policy.validate(event, new NodeIdentity(site.getId(), site.getInstanceId()));
        outbox.append(event, you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site, peer));
        jdbc.update("""
                UPDATE public.browse_history SET sync_visit_id=?,sync_origin_site=?,sync_actor_epoch=?,sync_owner_epoch=?,
                  sync_attempted_at=clock_timestamp() WHERE id=?
                """, visit.visit(), visit.origin(), actorState.epoch(), ownerState.epoch(), id);
        return true;
    }

    private UUID global(EntityType type, long id) {
        return identities.findGlobalId(type, id).orElseThrow(() -> new IllegalStateException("BROWSE_IDENTITY_REQUIRED"));
    }

    static long micros(Instant time) { return Math.addExact(Math.multiplyExact(time.getEpochSecond(), 1_000_000), time.getNano() / 1_000); }
    static Timestamp timestamp(long micros) { return Timestamp.from(Instant.ofEpochSecond(micros / 1_000_000, micros % 1_000_000 * 1_000)); }

    /** Bounded, restart-safe scan of source observations, including history from before opt-in or deployment. */
    public int runOnce() {
        if (Thread.currentThread().isInterrupted() || !active.compareAndSet(false, true)) return 0;
        try {
            var candidates = jdbc.query("""
                    SELECT b.id FROM public.browse_history b
                    JOIN eatwhat_identity.entity_identity i ON i.entity_type='browse_history' AND i.numeric_id=b.id
                    JOIN eatwhat_consent.subject actor ON actor.user_id=b.account_id
                    JOIN public.users au ON au.id=actor.user_id
                    JOIN LATERAL (
                      SELECT account_id FROM public.foods WHERE b.target_type='food' AND id=b.target_id
                      UNION ALL SELECT account_id FROM public.restaurant WHERE b.target_type='restaurant' AND id=b.target_id
                    ) target ON true
                    JOIN eatwhat_consent.subject owner ON owner.user_id=target.account_id
                    JOIN public.users ou ON ou.id=owner.user_id
                    WHERE coalesce(b.sync_origin_site,i.origin_site)=?
                      AND actor.status='GRANTED' AND owner.status='GRANTED' AND actor.identity_ready AND owner.identity_ready
                      AND actor.withdrawal_request_id IS NULL AND owner.withdrawal_request_id IS NULL
                      AND actor.policy_version=? AND owner.policy_version=? AND NOT au.banned AND NOT ou.banned
                      AND (NOT ? OR (actor.valid_until>clock_timestamp() AND owner.valid_until>clock_timestamp()))
                      AND (b.sync_actor_epoch<>actor.epoch OR b.sync_owner_epoch<>owner.epoch)
                      AND (b.sync_attempted_at IS NULL OR b.sync_attempted_at<clock_timestamp()-interval '30 seconds')
                    ORDER BY b.sync_attempted_at NULLS FIRST,b.id LIMIT ?
                    """, (row, index) -> row.getLong(1), site.getId(), consent.policyVersion(), consent.policyVersion(),
                    site.getMode() == SiteProperties.Mode.EXTERNAL && !consent.multiwriterEnabled(), batchSize);
            int published = 0;
            for (long id : candidates) {
                if (Thread.currentThread().isInterrupted()) break;
                try { if (publish(id)) published++; }
                catch (RuntimeException retryLater) {
                    // Payload and account identifiers must never appear in diagnostics.
                    transactions.required(() -> { jdbc.update("UPDATE public.browse_history SET sync_attempted_at=clock_timestamp() WHERE id=?", id); return null; });
                }
            }
            return published;
        } finally { active.set(false); }
    }
}
