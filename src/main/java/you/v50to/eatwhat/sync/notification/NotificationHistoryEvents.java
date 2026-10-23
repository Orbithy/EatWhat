package you.v50to.eatwhat.sync.notification;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;

/** A source-proven historical notice is separate from a user's later monotonic read operation. */
public final class NotificationHistoryEvents implements EventPolicy {
    public static final String AGGREGATE="notification.history",EVENT="notification.history.changed";
    private static final JsonMapper JSON=JsonMapper.builder().build();private final ConsentStore consent;
    public NotificationHistoryEvents(ConsentStore consent){this.consent=consent;}
    public record Notice(long id,UUID gid,String origin,UUID actor,UUID recipient,UUID target,String kind,String targetType,long createdAt,long expiresAt) { }
    public record Operation(String action,Notice notice,long actorEpoch,long recipientEpoch,Long readAt) { }
    public static String notice(Notice n){return EventCodec.canonicalJson(JSON.writeValueAsString(n));}
    public static String payload(Operation op){return EventCodec.canonicalJson(JSON.writeValueAsString(op));}
    public static Operation parse(String text){
        var n=JSON.readTree(EventCodec.canonicalJson(text));
        if(!n.isObject()||!Set.copyOf(n.propertyNames()).equals(Set.of("action","notice","actorEpoch","recipientEpoch","readAt"))
            ||!Set.copyOf(n.path("notice").propertyNames()).equals(Set.of("id","gid","origin","actor","recipient","target","kind","targetType","createdAt","expiresAt")))throw new IllegalArgumentException("INVALID_NOTIFICATION_HISTORY");
        var op=JSON.convertValue(n,Operation.class);var v=op.notice();
        SyncEvent.requireUuid(v.gid(),"notice");SyncEvent.requireUuid(v.actor(),"actor");SyncEvent.requireUuid(v.recipient(),"recipient");SyncEvent.requireUuid(v.target(),"target");SyncEvent.requireName(v.origin(),"origin",64);
        if(!Set.of("PUBLISH","READ").contains(op.action())||v.id()<1||v.id()>9_007_199_254_740_991L||v.actor().equals(v.recipient())
                ||op.actorEpoch()<1||op.recipientEpoch()<1||v.createdAt()<1||v.expiresAt()<=v.createdAt()
                ||!Set.of("follow","like").contains(v.kind())||!Set.of("user","food","dinner","post").contains(v.targetType())
                ||v.kind().equals("follow")&&(!v.targetType().equals("user")||!v.target().equals(v.actor()))
                ||v.kind().equals("like")&&v.targetType().equals("user")||op.action().equals("READ")&&(op.readAt()==null||op.readAt()<1)
                ||op.action().equals("PUBLISH")&&op.readAt()!=null)throw new IllegalArgumentException("INVALID_NOTIFICATION_HISTORY");
        return op;
    }
    static String targetTable(Notice v){return switch(v.targetType()){case "user"->"users";case "food"->"activity_foods";case "post"->"activity_posts";default->"activity_dinners";};}
    public static List<SyncEvent.Dependency> dependencies(Operation op){var v=op.notice();return java.util.stream.Stream.of(new SyncEvent.Dependency("users",v.actor()),new SyncEvent.Dependency("users",v.recipient()),new SyncEvent.Dependency(targetTable(v),v.target())).distinct().sorted(Comparator.comparing(SyncEvent.Dependency::aggregateType).thenComparing(d->d.aggregateId().toString())).toList();}
    @Override public Set<String> aggregateTypes(){return Set.of(AGGREGATE);}
    @Override public void validate(SyncEvent e,NodeIdentity origin){var op=parse(e.payloadJson());
        if(!e.aggregateType().equals(AGGREGATE)||!e.eventType().equals(EVENT)||e.schemaVersion()!=1||!e.eventId().equals(e.aggregateId())
            ||!e.originSite().equals(origin.siteId())||!e.originInstanceId().equals(origin.instanceId())
            ||!e.baseVersion().equals(VersionVector.empty())||!e.nextVersion().equals(VersionVector.empty().increment(e.originSite()))
            ||!e.dependencies().equals(dependencies(op))||!e.actorId().equals(op.action().equals("READ")?op.notice().recipient():op.notice().actor()) )throw new IllegalArgumentException("INVALID_NOTIFICATION_HISTORY");
    }
    @Override public OutboundDecision outbound(SyncEvent e){var op=parse(e.payloadJson());var a=consent.outboundAuthorization(op.notice().actor(),op.actorEpoch(),op.notice().recipient(),op.recipientEpoch());return a.allowed()?OutboundDecision.ALLOW:a.blocked()?OutboundDecision.POLICY_BLOCKED:OutboundDecision.WAIT;}
}
