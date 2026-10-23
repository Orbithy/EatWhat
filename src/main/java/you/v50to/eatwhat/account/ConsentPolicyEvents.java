package you.v50to.eatwhat.account;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;

/** Public configuration only; delivery never depends on any user's consent version. */
public final class ConsentPolicyEvents implements EventPolicy,EventHandler {
    public static final String AGGREGATE="consent-policy.operation",EVENT="consent-policy.replaced";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ConsentPolicyService service;
    public record Change(long revision,String version,String url) { }
    public ConsentPolicyEvents(ConsentPolicyService service){this.service=service;}
    public static String payload(Change value){return EventCodec.canonicalJson(JSON.writeValueAsString(value));}
    public static Change parse(String payload){
        try {
            var node=JSON.readTree(EventCodec.canonicalJson(payload));
            if(!node.isObject()||!Set.copyOf(node.propertyNames()).equals(Set.of("revision","version","url"))
                    ||!node.path("revision").isIntegralNumber()||!node.path("revision").canConvertToLong()
                    ||!node.path("version").isString()||!node.path("url").isString())throw new IllegalArgumentException();
            var change=JSON.readValue(node.toString(),Change.class);
            if(change.revision()<1||change.revision()>VersionVector.MAX_COUNTER)throw new IllegalArgumentException();
            ConsentPolicyService.validate(change.version(),change.url());
            return change;
        }catch(RuntimeException invalid){throw new IllegalArgumentException("INVALID_CONSENT_POLICY_EVENT");}
    }
    @Override public Set<String> aggregateTypes(){return Set.of(AGGREGATE);}
    @Override public void validate(SyncEvent event,NodeIdentity origin){
        var change=parse(event.payloadJson());
        if(!origin.equals(service.authority())||!event.originSite().equals(origin.siteId())||!event.originInstanceId().equals(origin.instanceId())
                ||!AGGREGATE.equals(event.aggregateType())||!EVENT.equals(event.eventType())||event.schemaVersion()!=1
                ||!event.eventId().equals(event.aggregateId())||event.numericId()!=change.revision()
                ||!event.baseVersion().equals(VersionVector.empty())||!event.dependencies().isEmpty()||event.actorId()!=null)
            throw new IllegalArgumentException("UNAUTHORIZED_CONSENT_POLICY_EVENT");
    }
    @Override public String aggregateType(){return AGGREGATE;}
    @Override public String eventType(){return EVENT;}
    @Override public int schemaVersion(){return 1;}
    @Override public boolean dependenciesSatisfied(SyncEvent event){validate(event,new NodeIdentity(event.originSite(),event.originInstanceId()));return true;}
    @Override public void apply(SyncEvent event){validate(event,new NodeIdentity(event.originSite(),event.originInstanceId()));service.apply(event,null);}
}
