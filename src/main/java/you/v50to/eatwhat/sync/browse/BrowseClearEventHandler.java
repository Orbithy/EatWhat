package you.v50to.eatwhat.sync.browse;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

public final class BrowseClearEventHandler implements EventHandler {
    private final JdbcTemplate jdbc;
    private final ConsentStore consent;
    private final BrowseClearStore store;
    private final BrowseEvents policy;
    public BrowseClearEventHandler(JdbcTemplate jdbc, ConsentStore consent) {
        this.jdbc=jdbc; this.consent=consent; this.store=new BrowseClearStore(jdbc); this.policy=new BrowseEvents(consent);
    }
    @Override public String aggregateType() { return BrowseEvents.AGGREGATE; }
    @Override public String eventType() { return BrowseEvents.CLEAR; }
    @Override public int schemaVersion() { return 2; }
    private BrowseEvents.Clear operation(SyncEvent event) {
        policy.validate(event, new NodeIdentity(event.originSite(),event.originInstanceId()));
        return BrowseEvents.parseClear(event.payloadJson());
    }
    @Override public boolean dependenciesSatisfied(SyncEvent event) {
        var op=operation(event);
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=? FOR SHARE",
                (r,n)->r.getLong(1),op.actor());
        var actor=consent.getBySubject(op.actor());
        return actor!=null && (actor.epoch()>op.actorEpoch()
                || actor.epoch()==op.actorEpoch() && (actor.status().equals("WITHDRAWN") || consent.replicationAllowed(actor.userId())));
    }
    @Override public void apply(SyncEvent event) {
        var op=operation(event); var actor=consent.getBySubject(op.actor());
        if(actor==null || actor.epoch()!=op.actorEpoch() || !consent.replicationAllowed(actor.userId()))return;
        store.merge(actor.userId(),op.targetType(),op.context());
    }
}
