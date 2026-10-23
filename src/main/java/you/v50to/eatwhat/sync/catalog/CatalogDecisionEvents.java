package you.v50to.eatwhat.sync.catalog;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.*;
import java.util.*;

public final class CatalogDecisionEvents {
    public static final String AGGREGATE="catalog.decision", ACTION="catalog.decision.action";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private CatalogDecisionEvents() { }
    public record Action(UUID conflictId,CatalogKind kind,UUID objectGid,
                         CatalogDecisionCausality.Decision decision,CatalogActorVerifier.Evidence actorEvidence,
                         String reason,List<CatalogEvents.ConsentEpoch> consentEpochs,List<UUID> reviewedOperations) {
        public Action(UUID conflictId,CatalogKind kind,UUID objectGid,CatalogDecisionCausality.Decision decision,CatalogActorVerifier.Evidence actorEvidence,String reason,List<CatalogEvents.ConsentEpoch> consentEpochs) {
            this(conflictId,kind,objectGid,decision,actorEvidence,reason,consentEpochs,List.of());
        }
        public Action {
            SyncEvent.requireUuid(conflictId,"catalog conflict");Objects.requireNonNull(kind);
            Objects.requireNonNull(decision);Objects.requireNonNull(actorEvidence);
            if(reason==null||reason.isBlank()||reason.length()>1000)throw new IllegalArgumentException("Invalid decision reason");
            consentEpochs=List.copyOf(consentEpochs);
            reviewedOperations=reviewedOperations==null?List.of():List.copyOf(reviewedOperations);
            if(new HashSet<>(reviewedOperations).size()!=reviewedOperations.size()||!decision.candidateOperations().containsAll(reviewedOperations))throw new IllegalArgumentException("Invalid reviewed catalog operations");
            if(consentEpochs.size()>CatalogEvents.MAX_CONSENT_SUBJECTS)throw new IllegalArgumentException("Decision consent set too large");
        }
    }
    public static String payload(Action action){return EventCodec.canonicalJson(JSON.writeValueAsString(action));}
    public static Action parse(String payload){return JSON.readValue(EventCodec.canonicalJson(payload),Action.class);}
    public static Action validate(SyncEvent event,Set<String> sites) {
        Action action=parse(event.payloadJson());var d=action.decision();
        if(!AGGREGATE.equals(event.aggregateType())||!ACTION.equals(event.eventType())||event.schemaVersion()!=2
                ||!d.operationId().equals(event.eventId())||!event.aggregateId().equals(event.eventId())
                ||!d.site().equals(event.originSite())||!action.actorEvidence().actorGid().equals(event.actorId())
                ||!event.nextVersion().equals(VersionVector.empty().increment(event.originSite()))||!event.baseVersion().equals(VersionVector.empty())||!event.dependencies().isEmpty()
                ||!sites.contains(event.originSite())||!sites.containsAll(d.context().keySet()))
            throw new IllegalArgumentException("INVALID_CATALOG_DECISION_EVENT");
        return action;
    }
}
