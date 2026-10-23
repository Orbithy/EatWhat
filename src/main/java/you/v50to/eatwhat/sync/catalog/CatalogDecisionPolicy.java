package you.v50to.eatwhat.sync.catalog;

import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;

public final class CatalogDecisionPolicy implements EventPolicy {
    private CatalogDecisionBackfill backfill;
    public void setBackfill(CatalogDecisionBackfill value){backfill=value;}
    private final ConsentStore consent;private final Set<String> sites;
    public CatalogDecisionPolicy(ConsentStore consent,Set<String> sites){this.consent=consent;this.sites=sites;}
    @Override public Set<String> aggregateTypes(){return Set.of(CatalogDecisionEvents.AGGREGATE);}
    @Override public Map<String,Set<Integer>> supportedSchemaVersions(){return Map.of(CatalogDecisionEvents.AGGREGATE,Set.of(2));}
    @Override public void validate(SyncEvent event,NodeIdentity origin){
        if(!event.originSite().equals(origin.siteId())||!event.originInstanceId().equals(origin.instanceId()))throw new IllegalArgumentException("CATALOG_DECISION_ORIGIN_MISMATCH");
        if(CatalogDecisionBackfill.TYPE.equals(event.eventType()) && backfill!=null)backfill.validate(event,origin);
        else CatalogDecisionEvents.validate(event,sites);
    }
    @Override public OutboundDecision outbound(SyncEvent event){
        if(CatalogDecisionBackfill.TYPE.equals(event.eventType()) && backfill!=null)return backfill.outbound(event);
        var action=CatalogDecisionEvents.validate(event,sites);boolean waiting=false;
        for(var e:action.consentEpochs()){
            // Actor evidence is still checked by the decision handler; a banned owner cannot block an admin review.
            var authorization=consent.outboundRemovalAuthorization(e.subjectGid(),e.epoch(),e.subjectGid(),e.epoch());
            if(authorization.blocked())return OutboundDecision.POLICY_BLOCKED;
            if(!authorization.allowed())waiting=true;
        }
        return waiting?OutboundDecision.WAIT:OutboundDecision.ALLOW;
    }
}
