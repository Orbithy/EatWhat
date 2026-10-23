package you.v50to.eatwhat.sync.preferences;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

/** Whole-setting register: logical clock, then origin site, then event UUID. Never wall-clock LWW. */
public final class PreferenceEventHandler implements EventHandler {
    private final JdbcTemplate jdbc;
    private final ConsentStore consent;
    private final PreferenceEvents policy;
    private final PreferenceCommands commands;
    public PreferenceEventHandler(JdbcTemplate jdbc, ConsentStore consent, PreferenceCommands commands) { this.jdbc=jdbc;this.consent=consent;this.commands=commands;policy=new PreferenceEvents(consent); }
    @Override public String aggregateType(){return PreferenceEvents.AGGREGATE;}
    @Override public String eventType(){return PreferenceEvents.EVENT;}
    @Override public int schemaVersion(){return 1;}
    private PreferenceEvents.Snapshot parse(SyncEvent event) {
        policy.validate(event,new NodeIdentity(event.originSite(),event.originInstanceId())); return PreferenceEvents.parse(event.payloadJson());
    }
    private boolean stale(PreferenceEvents.Snapshot op, ConsentModels.State state) {
        return state!=null&&(state.epoch()>op.epoch()||state.epoch()==op.epoch()&&state.status().equals("WITHDRAWN"));
    }
    @Override public boolean dependenciesSatisfied(SyncEvent event) {
        var op=parse(event);
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",(r,n)->r.getLong(1),op.subject());
        var state=consent.getBySubject(op.subject());
        if(stale(op,state)) return true;
        if(state==null||state.epoch()!=op.epoch()||!consent.replicationAllowed(state.userId())) return false;
        PreferenceCommands.lock(jdbc,op.subject());
        return !commands.hasUnpublishedLocalSnapshot(state);
    }
    @Override public void apply(SyncEvent event) {
        var op=parse(event); var grant=consent.getBySubject(op.subject());
        if(stale(op,grant)||grant==null||grant.epoch()!=op.epoch()||!consent.replicationAllowed(grant.userId())) return;
        PreferenceCommands.lock(jdbc,op.subject());
        var previous=PreferenceCommands.state(jdbc,op.subject());
        if(previous!=null) {
            int comparison=Long.compare(op.epoch(),previous.epoch());
            if(comparison==0) comparison=Long.compare(op.clock(),previous.clock());
            if(comparison==0) comparison=event.originSite().compareTo(previous.site());
            if(comparison==0) comparison=event.eventId().toString().compareTo(previous.event().toString());
            if(comparison<=0) return;
        }
        var value=new PreferenceCommands.Value(op.likedTags(),op.dislikedTags(),PreferenceCommands.time(op.updatedAtMicros()));
        PreferenceCommands.project(jdbc,grant.userId(),value);
        PreferenceCommands.remember(jdbc,op.subject(),new PreferenceCommands.State(op.epoch(),op.clock(),event.eventId(),
                event.originSite(),value.hash(),value.at(),value.empty(),false,op.epoch()));
    }
}
