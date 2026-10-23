package you.v50to.eatwhat.sync.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongPredicate;

/**
 * Outbound capture. Callers invoke it inside the business transaction that wrote the activity, so the row,
 * its identity and the Outbox event commit or roll back together. An author without replication consent
 * produces no event at all; the activity then stays local.
 */
public final class ActivityEventCapture {
    private static final Logger log = LoggerFactory.getLogger(ActivityEventCapture.class);
    private ActivityDurableCommands durable;
    public void setDurableCommands(ActivityDurableCommands commands) { this.durable=commands; }
    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final EntityIdentityService identities;
    private final SiteProperties site;
    private final String peerSite;
    private final LongPredicate authorMayReplicate;

    public ActivityEventCapture(JdbcTemplate jdbc, OutboxService outbox, EntityIdentityService identities,
                                SiteProperties site, String peerSite, LongPredicate authorMayReplicate) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.outbox = Objects.requireNonNull(outbox);
        this.identities = Objects.requireNonNull(identities);
        this.site = Objects.requireNonNull(site);
        this.peerSite = site.getClusterId()==null?Objects.requireNonNull(peerSite):peerSite;
        this.authorMayReplicate = Objects.requireNonNull(authorMayReplicate);
    }

    public void created(String table, long activityId, long authorId, String foodName, String description,
                        Integer provinceId, Integer cityId, List<String> pictures) {
        created(table, activityId, authorId, foodName, description, provinceId, cityId, pictures, null, null, null);
    }

    /** A generic activity post; {@code extraJson} is the stored extra object. */
    public void createdPost(long postId, long authorId, long activityId, String title, String description,
                            Integer provinceId, Integer cityId, List<String> pictures, String extraJson) {
        if (durable != null) { durable.capture(ActivityEvents.POSTS, postId); return; }
        UUID activityGid = identities.findGlobalId(EntityType.ACTIVITIES, activityId)
                .orElseThrow(() -> new IllegalStateException("Activity definition has no global identity"));
        created(ActivityEvents.POSTS, postId, authorId, null, description, provinceId, cityId, pictures,
                activityGid, title, EventCodec.canonicalJson(extraJson));
    }

    private void created(String table, long activityId, long authorId, String foodName, String description,
                         Integer provinceId, Integer cityId, List<String> pictures, UUID activityGid, String title,
                         String extraJson) {
        if (durable != null) { durable.capture(table,activityId); return; }
        if (!authorMayReplicate.test(authorId)) return;
        UUID gid = globalId(table, activityId);
        UUID authorGid = identities.findGlobalId(EntityType.USERS, authorId)
                .orElseThrow(() -> new IllegalStateException("Activity author has no global identity"));
        Long createdAt = jdbc.queryForObject("SELECT (extract(epoch FROM created_at)*1000)::bigint FROM public."
                + table + " WHERE id=?", Long.class, activityId);
        var fields = new ActivityEvents.Created(activityId, gid, site.getId(), authorGid, foodName, description,
                provinceId, cityId, pictures, Objects.requireNonNull(createdAt), activityGid, title, extraJson);
        outbox.append(new SyncEvent(UUID.randomUUID(), site.getId(), site.getInstanceId(), table, gid, activityId,
                ActivityEvents.CREATED, ActivityEvents.SCHEMA_VERSION, VersionVector.empty(),
                VersionVector.empty().increment(site.getId()), List.of(new SyncEvent.Dependency("users", authorGid)),
                authorGid, "activity.created:" + gid, UUID.randomUUID(), ActivityEvents.createdPayload(table, fields)),
                you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,peerSite));
    }

    public void deleted(String table, long activityId, long authorId, long deletedAtMillis) {
        if (durable != null) { durable.capture(table,activityId); return; }
        if (!authorMayReplicate.test(authorId)) return;
        UUID gid = globalId(table, activityId);
        var states = jdbc.queryForList("""
                SELECT applied_vector::text AS vector,conflicted FROM eatwhat_sync.entity_state
                WHERE aggregate_type=? AND aggregate_id=?
                """, table, gid);
        if (states.isEmpty()) return; // Never replicated (created before consent or with sync off): local only.
        if ((Boolean) states.getFirst().get("conflicted")) {
            // The unresolved conflict record is the operator signal; user deletion must not fail because of it.
            log.warn("Activity deletion not captured because its aggregate has an unresolved sync conflict");
            return;
        }
        VersionVector base = EventCodec.vectorFromJson((String) states.getFirst().get("vector"));
        UUID authorGid = identities.findGlobalId(EntityType.USERS, authorId)
                .orElseThrow(() -> new IllegalStateException("Activity author has no global identity"));
        outbox.append(new SyncEvent(UUID.randomUUID(), site.getId(), site.getInstanceId(), table, gid, activityId,
                ActivityEvents.DELETED, ActivityEvents.SCHEMA_VERSION, base, base.increment(site.getId()),
                List.of(new SyncEvent.Dependency("users", authorGid)), authorGid, "activity.deleted:" + gid,
                UUID.randomUUID(), ActivityEvents.deletedPayload(deletedAtMillis)), you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,peerSite));
    }

    private UUID globalId(String table, long activityId) {
        return identities.findGlobalId(EntityType.fromTableName(table), activityId)
                .orElseThrow(() -> new IllegalStateException("Activity has no global identity"));
    }
}
