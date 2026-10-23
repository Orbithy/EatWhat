package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.*;
import java.util.*;

/** Always-on compatible reader. Writer activation is independent of reader installation. */
public final class CatalogMultiwriterHandler implements EventHandler {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final CatalogEventHandler delegate;
    private final CatalogMultiwriterSupport support;
    private final JdbcTemplate jdbc;
    private final java.util.function.Supplier<String> authority;
    public CatalogMultiwriterHandler(CatalogEventHandler delegate,CatalogMultiwriterSupport support,JdbcTemplate jdbc,
                                     java.util.function.Supplier<String> authority) {
        this.delegate=delegate;this.support=support;this.jdbc=jdbc;this.authority=authority;
        delegate.enableMultiwriterReader(support);
    }
    @Override public String aggregateType(){return CatalogEvents.AGGREGATE;}
    @Override public String eventType(){return delegate.eventType();}
    @Override public int schemaVersion(){return 2;}
    private CatalogActorVerifier.Assessment assessment(SyncEvent event) {
        SyncEvent shape=CatalogMultiwriterEvents.legacyShape(event);
        delegate.validateEnvelope(shape);
        var evidence=CatalogMultiwriterEvents.evidence(event);
        List<CatalogEvents.Action> actions;
        if(CatalogEvents.ACTION.equals(event.eventType())) actions=List.of(CatalogEvents.parseAction(shape.payloadJson()));
        else {
            var bridge=CatalogEvents.parseBridge(shape.payloadJson());
            actions=bridge.heads().stream().map(h->new CatalogEvents.Action(bridge.kind(),bridge.objectGid(),h.revision(),h.expectedRevision(),
                    h.operation(),h.authoritySite(),h.authorGid(),h.parentGid(),h.numericId(),h.identityOrigin(),h.canonicalPayload(),
                    h.contentHash(),h.operationId(),h.dotSite(),h.dotCounter(),h.context(),h.operationConsentEpochs(),null,h.resolutionProof())).toList();
        }
        boolean review=false,pending=false;
        for(var action:actions) {
            var proof=evidence.get(action.operationId());
            if(proof==null) {
                // A bridge may retain a pre-upgrade immutable head, but only under the original authority policy.
                if(CatalogEvents.ACTION.equals(event.eventType()) || !Objects.equals(authority.get(),action.authoritySite())
                        || ((action.kind()==CatalogKind.HUB || action.kind()==CatalogKind.SYSTEM_TAG || action.kind()==CatalogKind.ACTIVITY || action.resolutionProof()!=null
                        || (action.kind()==CatalogKind.FOOD||action.kind()==CatalogKind.RESTAURANT)&&action.operation()!=CatalogOperation.CREATE)
                        &&!Objects.equals(authority.get(),action.dotSite())))
                    throw new IllegalArgumentException("CATALOG_ACTOR_EVIDENCE_REQUIRED");
                continue;
            }
            var verdict=support.assess(proof);
            boolean previouslyReviewed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_authorization_review WHERE event_id=?)",Boolean.class,action.operationId()));
            if(previouslyReviewed && verdict!=CatalogActorVerifier.Assessment.DEPENDENCY_PENDING && verdict!=CatalogActorVerifier.Assessment.KNOWN_REVOKED)verdict=CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW;
            if(verdict==CatalogActorVerifier.Assessment.KNOWN_REVOKED) throw new IllegalArgumentException("CATALOG_ACTOR_REVOKED");
            if(verdict==CatalogActorVerifier.Assessment.DEPENDENCY_PENDING){pending=true;continue;}
            if(!support.decisionDependencies(action,proof)){pending=true;continue;}
            CatalogMultiwriterSupport.requirePermission(action,proof);
            review|=verdict==CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW;
        }
        return pending?CatalogActorVerifier.Assessment.DEPENDENCY_PENDING:review?
                CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW:CatalogActorVerifier.Assessment.AUTHORIZED;
    }
    @Override public boolean dependenciesSatisfied(SyncEvent event) {
        var verdict=assessment(event);
        return verdict!=CatalogActorVerifier.Assessment.DEPENDENCY_PENDING
                && delegate.dependenciesSatisfied(CatalogMultiwriterEvents.legacyShape(event));
    }
    @Override public void apply(SyncEvent event) {
        var verdict=assessment(event);
        if(verdict==CatalogActorVerifier.Assessment.DEPENDENCY_PENDING) throw new IllegalStateException("CATALOG_ACTOR_DEPENDENCY_CHANGED");
        SyncEvent shape=CatalogMultiwriterEvents.legacyShape(event);
        if(verdict==CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW) {
            if(!delegate.currentReviewConsent(shape))return;
            CatalogKind kind;UUID gid;
            if(CatalogEvents.ACTION.equals(event.eventType())){var action=CatalogEvents.parseAction(shape.payloadJson());kind=action.kind();gid=action.objectGid();}
            else {var bridge=CatalogEvents.parseBridge(shape.payloadJson());kind=bridge.kind();gid=bridge.objectGid();}
            var reviewedIds=CatalogEvents.ACTION.equals(event.eventType())?List.of(event.eventId()):CatalogEvents.parseBridge(shape.payloadJson()).heads().stream().map(h->h.operationId()).toList();
            for(UUID id:reviewedIds)jdbc.update("INSERT INTO eatwhat_sync.catalog_authorization_review(event_id,object_gid,kind,envelope,reason) VALUES(?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",
                    id,gid,kind.name(),JSON.writeValueAsString(event),"CONCURRENT_REVOCATION");
            delegate.stageAuthorizationReview(shape);
            CatalogMultiwriterEvents.evidence(event).forEach(support::save);
            return;
        }
        boolean originalAlreadyKnown=CatalogEvents.ACTION.equals(event.eventType()) && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision WHERE operation_id=?)",Boolean.class,event.eventId()));
        delegate.apply(shape);
        // Stale consent events intentionally install no revision and must not leave orphan proofs.
        CatalogMultiwriterEvents.evidence(event).forEach((id,proof)-> {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision WHERE operation_id=?)",Boolean.class,id))) support.save(id,proof);
        });
        if(CatalogEvents.ACTION.equals(event.eventType()) && !originalAlreadyKnown) {
            var action=CatalogEvents.parseAction(shape.payloadJson());
            if(action.operation()!=CatalogOperation.DELETE && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE o.kind=? AND o.object_gid=? AND h->>'operationId'=?)",Boolean.class,action.kind().name(),action.objectGid(),action.operationId().toString())))
                support.reconcileEdit(action,CatalogMultiwriterEvents.evidence(event).get(action.operationId()));
        }
    }
}
