package you.v50to.eatwhat.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;

/** Explicit local event append; callers supply the surrounding business transaction. */
public final class OutboxService {
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final EventCodec codec;
    private final SiteProperties site;
    private final you.v50to.eatwhat.cluster.ClusterMembershipView membership;

    public OutboxService(JdbcTemplate jdbc, SyncTransactions transactions, EventCodec codec, SiteProperties site) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.transactions.requireSameDataSource(jdbc);
        this.codec = Objects.requireNonNull(codec);
        this.site = Objects.requireNonNull(site);
        this.membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
    }

    public void append(SyncEvent event, Collection<String> targetSites) {
        transactions.mandatory(() -> {
            Objects.requireNonNull(event, "event");
            if (InboundApplicationScope.isActive()) {
                throw new IllegalStateException("An inbound application cannot recapture an outbound event");
            }
            if (site.getMode() == SiteProperties.Mode.LEGACY || site.getMode() == null
                    || !Objects.equals(site.getId(), event.originSite())
                    || !Objects.equals(site.getInstanceId(), event.originInstanceId())) {
                throw new IllegalStateException("Local event origin must match the configured explicit site installation");
            }
            Objects.requireNonNull(targetSites, "targetSites");
            Collection<String> routed=targetSites;
            boolean cluster=membership.bound();
            if(cluster&&!event.aggregateType().equals(you.v50to.eatwhat.sync.file.FileEvents.RECEIPT))routed=membership.participants().stream().map(you.v50to.eatwhat.cluster.ClusterMember::siteId).filter(id->!id.equals(site.getId())).toList();
            if ((!cluster&&routed.isEmpty()) || routed.size() > 16) {
                throw new IllegalArgumentException("An event requires between one and sixteen delivery targets");
            }
            var targets = new LinkedHashSet<String>();
            for (String target : routed) {
                SyncEvent.requireName(target, "targetSite", 64);
                if (target.equals(site.getId()) || !targets.add(target)) {
                    throw new IllegalArgumentException("Targets must be distinct remote sites");
                }
                if(cluster&&membership.participants().stream().noneMatch(m->m.siteId().equals(target)))throw new IllegalArgumentException("Target is not a cluster participant");
            }
            EventCodec.Encoded encoded = codec.encode(event);
            jdbc.update("""
                    INSERT INTO eatwhat_sync.entity_state(aggregate_type,aggregate_id,numeric_id)
                    VALUES (?,?,?) ON CONFLICT DO NOTHING
                    """, event.aggregateType(), event.aggregateId(), event.numericId());
            var states = jdbc.queryForList("""
                    SELECT numeric_id,applied_vector::text AS applied_vector,conflicted
                    FROM eatwhat_sync.entity_state WHERE aggregate_type=? AND aggregate_id=? FOR UPDATE
                    """, event.aggregateType(), event.aggregateId());
            if (states.isEmpty()) {
                throw new IllegalStateException("Aggregate numeric identity is already bound to another global identity");
            }
            var state = states.getFirst();
            if (((Number) state.get("numeric_id")).longValue() != event.numericId()) {
                throw new IllegalStateException("Aggregate numeric identity differs from its existing global identity");
            }
            if ((Boolean) state.get("conflicted")) {
                throw new IllegalStateException("Aggregate has an unresolved conflict");
            }
            if (!EventCodec.vectorFromJson((String) state.get("applied_vector")).equals(event.baseVersion())) {
                throw new IllegalStateException("Local event base differs from the applied aggregate version");
            }
            // No conflict suppression: command-level deduplication must run before business writes.
            jdbc.update("""
                    INSERT INTO eatwhat_sync.outbox(event_id,origin_site,origin_instance_id,
                      aggregate_type,aggregate_id,numeric_id,envelope,envelope_hash)
                    VALUES (?,?,?,?,?,?,?::jsonb,?)
                    """, event.eventId(), event.originSite(), event.originInstanceId(), event.aggregateType(),
                    event.aggregateId(), event.numericId(), encoded.json(), encoded.hash());
            for (String target : targets) {
                jdbc.update("INSERT INTO eatwhat_sync.delivery(event_id,target_site) VALUES (?,?)", event.eventId(), target);
            }
            jdbc.update("""
                    UPDATE eatwhat_sync.entity_state SET applied_vector=?::jsonb,last_event_id=?
                    WHERE aggregate_type=? AND aggregate_id=?
                    """, EventCodec.vectorJson(event.nextVersion()), event.eventId(), event.aggregateType(), event.aggregateId());
            return null;
        });
    }
}
