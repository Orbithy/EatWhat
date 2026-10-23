package you.v50to.eatwhat.sync.activity;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;

/** Durable, terminal snapshots use independent transport aggregates; legacy entity conflicts cannot lose a delete. */
public final class ActivityStateEvents implements EventPolicy {
    public static final String AGGREGATE="activity_state", EVENT="activity.snapshot";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ConsentStore consent;
    public ActivityStateEvents(ConsentStore consent){this.consent=consent;}
    public record Snapshot(String table, ActivityEvents.Created created, long epoch, Long deletedAt) { }
    public static String payload(Snapshot op){
        var node=JSON.createObjectNode(); node.put("table",op.table());node.put("epoch",op.epoch());
        node.set("created",JSON.readTree(ActivityEvents.createdPayload(op.table(),op.created())));
        if(op.deletedAt()==null)node.putNull("deletedAt");else node.put("deletedAt",op.deletedAt());
        return EventCodec.canonicalJson(node.toString());
    }
    public static Snapshot parse(String json){
        var n=JSON.readTree(EventCodec.canonicalJson(json));
        if(!n.isObject()||!Set.copyOf(n.propertyNames()).equals(Set.of("table","epoch","created","deletedAt"))
                ||!n.path("table").isString()||!ActivityEvents.supportsTable(n.path("table").stringValue())
                ||!n.path("epoch").isIntegralNumber()||!n.path("epoch").canConvertToLong()||n.path("epoch").longValue()<1
                ||!(n.path("deletedAt").isNull()||n.path("deletedAt").isIntegralNumber()&&n.path("deletedAt").canConvertToLong()&&n.path("deletedAt").longValue()>0))
            throw new IllegalArgumentException("INVALID_ACTIVITY_STATE");
        return new Snapshot(n.path("table").stringValue(),ActivityEvents.parseCreated(n.path("table").stringValue(),n.path("created").toString(),true),
                n.path("epoch").longValue(),n.path("deletedAt").isNull()?null:n.path("deletedAt").longValue());
    }
    @Override public Set<String> aggregateTypes(){return Set.of(AGGREGATE);}
    @Override public void validate(SyncEvent e,NodeIdentity origin){
        var op=parse(e.payloadJson());
        if(!AGGREGATE.equals(e.aggregateType())||!EVENT.equals(e.eventType())||e.schemaVersion()!=1
                ||!e.eventId().equals(e.aggregateId())||!e.originSite().equals(origin.siteId())||!e.originInstanceId().equals(origin.instanceId())
                ||!op.created().authorGlobalId().equals(e.actorId())||!e.baseVersion().equals(VersionVector.empty())
                ||!e.nextVersion().equals(VersionVector.empty().increment(e.originSite()))
                ||!e.dependencies().equals(List.of(new SyncEvent.Dependency("users",e.actorId())))
)
            throw new IllegalArgumentException("INVALID_ACTIVITY_STATE");
    }
    @Override public OutboundDecision outbound(SyncEvent e){
        var op=parse(e.payloadJson());var auth=op.deletedAt()!=null
                ?consent.outboundRemovalAuthorization(e.actorId(),op.epoch(),e.actorId(),op.epoch())
                :consent.outboundAuthorization(e.actorId(),op.epoch(),e.actorId(),op.epoch());
        return auth.allowed()?OutboundDecision.ALLOW:auth.blocked()?OutboundDecision.POLICY_BLOCKED:OutboundDecision.WAIT;
    }
}
