package you.v50to.eatwhat.sync.file;

import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.transport.*;
import java.util.Set;

public final class FileEventPolicy implements EventPolicy {
    private final ConsentStore consent;
    public FileEventPolicy(ConsentStore consent) { this.consent=consent; }
    @Override public Set<String> aggregateTypes() { return Set.of(FileEvents.METADATA,FileEvents.REFERENCES,FileEvents.RECEIPT,FileEvents.REPLICA,FileGcEvents.TYPE); }
    @Override public void validate(SyncEvent event,NodeIdentity origin) { if(FileGcEvents.TYPE.equals(event.aggregateType())) FileGcEvents.validate(event,origin); else FileEvents.validate(event,origin); }
    @Override public OutboundDecision outbound(SyncEvent e) {
        validate(e,new NodeIdentity(e.originSite(),e.originInstanceId()));
        if(!FileEvents.METADATA.equals(e.aggregateType())&&!FileEvents.REPLICA.equals(e.aggregateType())) return OutboundDecision.ALLOW;
        java.util.UUID owner; long epoch;
        if(FileEvents.METADATA.equals(e.aggregateType())) { var m=FileEvents.metadata(e.payloadJson()); owner=m.file().ownerGid(); epoch=m.epoch(); }
        else { var r=FileEvents.replica(e.payloadJson()); owner=r.ownerGid(); epoch=r.epoch(); }
        var auth=consent.outboundAuthorization(owner,epoch,owner,epoch);
        return auth.blocked()?OutboundDecision.POLICY_BLOCKED:auth.allowed()?OutboundDecision.ALLOW:OutboundDecision.WAIT;
    }
}
