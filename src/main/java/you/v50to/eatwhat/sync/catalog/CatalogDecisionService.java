package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;
import java.util.*;

/** A conflict decision is itself a causally merged object. Local SQL CAS is never treated as a global lock. */
public final class CatalogDecisionService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final SyncTransactions transactions;private final CatalogStore store;
    private final CatalogMultiwriterSupport support;private final OutboxService outbox;private final SiteProperties site;
    private final CatalogCommands commands;private final ConsentStore consent;
    public record Scope(UUID conflictId,CatalogKind kind,UUID objectGid,String keyHash) { }
    public record DecisionView(UUID conflictId,CatalogKind kind,UUID objectGid,String status,List<UUID> decisionHeads,
                               List<UUID> candidateOperations,UUID winnerOperationId) { }
    public CatalogDecisionService(JdbcTemplate jdbc,SyncTransactions transactions,CatalogStore store,CatalogMultiwriterSupport support,
            OutboxService outbox,SiteProperties site,CatalogCommands commands,ConsentStore consent) {
        this.jdbc=jdbc;this.transactions=transactions;this.store=store;this.support=support;this.outbox=outbox;
        this.site=site;this.commands=commands;this.consent=consent;transactions.requireSameDataSource(jdbc);
    }
    public DecisionView view(UUID conflictId) {
        return transactions.required(()->{
            Scope scope=scope(conflictId);if(scope==null)return null;
            if(scope.objectGid()==null?!commands.canDiscloseConflict(conflictId):!commands.canDiscloseObjectHeadConflict(conflictId))return null;
            var states=jdbc.query("SELECT status,winner_operation_id FROM eatwhat_sync.catalog_decision_state WHERE conflict_id=?",(rs,n)->new DecisionView(conflictId,scope.kind(),scope.objectGid(),rs.getString(1),headIds(conflictId),candidates(scope),rs.getObject(2,UUID.class)),conflictId);
            return states.isEmpty()?new DecisionView(conflictId,scope.kind(),scope.objectGid(),"OPEN",List.of(),candidates(scope),null):states.getFirst();
        });
    }
    public List<DecisionView> list() { return transactions.required(this::listInTransaction); }
    private List<DecisionView> listInTransaction() {
        return jdbc.query("SELECT conflict_id,kind,object_gid,status,heads::text,winner_operation_id FROM eatwhat_sync.catalog_decision_state ORDER BY conflict_id",
            (rs,n)-> {var id=rs.getObject(1,UUID.class);var scope=scope(id);return new DecisionView(id,CatalogKind.valueOf(rs.getString(2)),
                    rs.getObject(3,UUID.class),rs.getString(4),uuidList(rs.getString(5)),scope==null?List.of():candidates(scope),rs.getObject(6,UUID.class));}).stream().filter(v -> v.objectGid()==null?commands.canDiscloseConflict(v.conflictId()):commands.canDiscloseObjectHeadConflict(v.conflictId())).toList();
    }
    public CatalogCommands.Status resolve(UUID conflict,List<UUID> expectedCandidates,List<UUID> expectedDecisions,
            CatalogDecision choice,UUID winner,String reason,long actor) {
        if(!commands.multiWriterEnabled())return CatalogCommands.Status.BLOCKED;
        return transactions.required(()->{
            var proof=support.capture(actor);
            if(!"admin".equals(proof.authorization().get("role"))) throw new IllegalArgumentException("CATALOG_ADMIN_REQUIRED");
            store.lockDomain();Scope scope=scope(conflict);if(scope==null)return CatalogCommands.Status.BLOCKED;
            List<UUID> current=candidates(scope);List<UUID> currentDecisions=headIds(conflict);
            if(!same(current,expectedCandidates)||!same(currentDecisions,expectedDecisions))return CatalogCommands.Status.CONFLICT;
            Map<String,Long> context=seen(conflict);
            var decision=new CatalogDecisionCausality.Decision(UUID.randomUUID(),site.getId(),context.getOrDefault(site.getId(),0L)+1,
                    context,current,choice,winner);
            recordCandidateReviews(current);
            var action=new CatalogDecisionEvents.Action(conflict,scope.kind(),scope.objectGid(),decision,proof,reason,epochs(current),reviewedCandidates(current));
            if(!epochsReady(action))return CatalogCommands.Status.BLOCKED;
            SyncEvent event=new SyncEvent(decision.operationId(),site.getId(),site.getInstanceId(),CatalogDecisionEvents.AGGREGATE,
                    decision.operationId(),jdbc.queryForObject("SELECT eatwhat_sync.next_catalog_number()",Long.class),CatalogDecisionEvents.ACTION,2,
                    VersionVector.empty(),VersionVector.empty().increment(site.getId()),List.of(),proof.actorGid(),"catalog-decision:"+decision.operationId(),
                    decision.operationId(),CatalogDecisionEvents.payload(action));
            outbox.append(event,commands.recipients());
            apply(action);
            return CatalogCommands.Status.APPLIED;
        });
    }
    public boolean dependenciesForReplay(CatalogDecisionEvents.Action action,List<CatalogEvents.ConsentEpoch> epochs) {
        var rebound=new CatalogDecisionEvents.Action(action.conflictId(),action.kind(),action.objectGid(),action.decision(),action.actorEvidence(),action.reason(),epochs,action.reviewedOperations());
        if(!dependencies(rebound))return false;
        Set<UUID> supplied=new HashSet<>();epochs.forEach(e->supplied.add(e.subjectGid()));
        return action.decision().candidateOperations().stream().flatMap(id->store.subjectsForOperation(id).stream()).allMatch(supplied::contains);
    }
    public boolean dependencies(CatalogDecisionEvents.Action action) {
        var assessment=support.assess(action.actorEvidence());
        if(assessment==CatalogActorVerifier.Assessment.KNOWN_REVOKED)throw new IllegalArgumentException("CATALOG_ACTOR_REVOKED");
        if(assessment==CatalogActorVerifier.Assessment.DEPENDENCY_PENDING)return false;
        if(!"admin".equals(action.actorEvidence().authorization().get("role")))throw new IllegalArgumentException("CATALOG_ADMIN_REQUIRED");
        if(scope(action.conflictId())==null)return false;
        if(action.decision().candidateOperations().stream().anyMatch(id->store.readRevision(id)==null))return false;
        Map<String,Long> known=seen(action.conflictId());
        if(action.decision().context().entrySet().stream().anyMatch(e->known.getOrDefault(e.getKey(),0L)<e.getValue()))return false;
        return epochsReady(action);
    }
    public void apply(CatalogDecisionEvents.Action action) {
        store.lockDomain();
        Scope scope=scope(action.conflictId());
        if(scope==null||scope.kind()!=action.kind()||!Objects.equals(scope.objectGid(),action.objectGid()))
            throw new IllegalArgumentException("CATALOG_DECISION_SCOPE_MISMATCH");
        verifyCandidates(scope,action.decision().candidateOperations());
        var assessment=support.assess(action.actorEvidence());
        if(assessment==CatalogActorVerifier.Assessment.KNOWN_REVOKED||assessment==CatalogActorVerifier.Assessment.DEPENDENCY_PENDING)
            throw new IllegalStateException("CATALOG_DECISION_AUTHORIZATION_CHANGED");
        if(!"admin".equals(action.actorEvidence().authorization().get("role")))throw new IllegalArgumentException("CATALOG_ADMIN_REQUIRED");
        var d=action.decision();String payload=CatalogDecisionEvents.payload(action),hash=EventCodec.sha256(payload);
        var existing=jdbc.query("SELECT content_hash FROM eatwhat_sync.catalog_decision_operation WHERE operation_id=?",(rs,n)->rs.getString(1).trim(),d.operationId());
        if(!existing.isEmpty()){if(!existing.getFirst().equals(hash))throw new IllegalArgumentException("CATALOG_DECISION_IDENTITY_CONFLICT");return;}
        jdbc.update("INSERT INTO eatwhat_sync.catalog_decision_operation(operation_id,conflict_id,kind,object_gid,source_site,dot_counter,context,candidate_operations,decision,winner_operation_id,actor_gid,actor_evidence,reason,payload,content_hash) VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?::jsonb,?,?::jsonb,?)",
                d.operationId(),action.conflictId(),action.kind().name(),action.objectGid(),d.site(),d.counter(),json(d.context()),json(d.candidateOperations()),
                d.decision().name(),d.winnerOperationId(),action.actorEvidence().actorGid(),json(action.actorEvidence()),action.reason(),payload,hash);
        List<CatalogDecisionCausality.Decision> previous=headIds(action.conflictId()).stream().map(this::read).map(CatalogDecisionEvents.Action::decision).toList();
        var merged=CatalogDecisionCausality.merge(previous,List.of(d));var outcome=CatalogDecisionCausality.evaluate(merged);
        Map<String,Long> seen=new TreeMap<>(seen(action.conflictId()));d.context().forEach((key,value)->seen.merge(key,value,Math::max));seen.merge(d.site(),d.counter(),Math::max);
        List<UUID> currentCandidates=candidates(scope);
        List<UUID> mergedIds=merged.stream().map(CatalogDecisionCausality.Decision::operationId).toList();
        boolean observedByLaterEdit=currentCandidates.size()==1 && support.observesDecisions(currentCandidates.getFirst(),mergedIds);
        boolean staleCandidates=!observedByLaterEdit && !same(currentCandidates,merged.stream().flatMap(h->h.candidateOperations().stream()).distinct().toList());
        boolean authorizationReview=merged.stream().map(h->read(h.operationId())).map(a->requiresReview(a)|requiresCandidateReview(a)).reduce(false,(a,b)->a||b);
        String status=authorizationReview?"AUTH_REVIEW":staleCandidates?"OPEN":outcome.status().name();
        List<UUID> heads=merged.stream().map(CatalogDecisionCausality.Decision::operationId).sorted().toList();
        jdbc.update("INSERT INTO eatwhat_sync.catalog_decision_state(conflict_id,kind,object_gid,heads,seen,status,winner_operation_id) VALUES(?,?,?,?::jsonb,?::jsonb,?,?) ON CONFLICT(conflict_id) DO UPDATE SET heads=EXCLUDED.heads,seen=EXCLUDED.seen,status=EXCLUDED.status,winner_operation_id=EXCLUDED.winner_operation_id,updated_at=clock_timestamp()",
                action.conflictId(),action.kind().name(),action.objectGid(),json(heads),json(seen),status,"RESOLVED".equals(status)?outcome.winnerOperationId():null);
        if(!observedByLaterEdit)materialize(scope,status,heads,outcome.winnerOperationId());
        else if("RESOLVED".equals(status))support.projectionState(scope.kind(),store.readRevision(currentCandidates.getFirst()).action().objectGid(),"VISIBLE",heads);
    }
    private List<UUID> reviewedCandidates(List<UUID> candidates) {
        return candidates.stream().filter(id->Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_authorization_review WHERE event_id=?)",Boolean.class,id))).toList();
    }
    private void recordCandidateReviews(List<UUID> candidates) {
        for(UUID id:candidates) {
            var proof=support.find(id);if(proof==null)continue;
            var verdict=support.assess(proof);
            if(verdict!=CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW && verdict!=CatalogActorVerifier.Assessment.KNOWN_REVOKED)continue;
            var revision=store.readRevision(id);if(revision==null)continue;var a=revision.action();
            jdbc.update("INSERT INTO eatwhat_sync.catalog_authorization_review(event_id,object_gid,kind,envelope,reason) VALUES(?,?,?,?::jsonb,'DECISION_CANDIDATE_AUTHORITY') ON CONFLICT DO NOTHING",id,a.objectGid(),a.kind().name(),CatalogEvents.payload(a));
        }
    }
    private boolean requiresCandidateReview(CatalogDecisionEvents.Action action) {
        recordCandidateReviews(action.decision().candidateOperations());
        return !action.reviewedOperations().containsAll(reviewedCandidates(action.decision().candidateOperations()));
    }
    private boolean requiresReview(CatalogDecisionEvents.Action action) {
        var verdict=support.assess(action.actorEvidence());
        if(verdict==CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW || verdict==CatalogActorVerifier.Assessment.KNOWN_REVOKED)
            jdbc.update("INSERT INTO eatwhat_sync.catalog_decision_authorization_review(operation_id,conflict_id) VALUES(?,?) ON CONFLICT DO NOTHING",action.decision().operationId(),action.conflictId());
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_decision_authorization_review WHERE operation_id=?)",Boolean.class,action.decision().operationId()));
    }
    public void reassessDecision(UUID id) {
        store.lockDomain();var action=read(id);if(!headIds(action.conflictId()).contains(id)||!(requiresReview(action)|requiresCandidateReview(action)))return;
        jdbc.update("UPDATE eatwhat_sync.catalog_decision_state SET status='AUTH_REVIEW',winner_operation_id=NULL,updated_at=clock_timestamp() WHERE conflict_id=?",action.conflictId());
        materialize(scope(action.conflictId()),"AUTH_REVIEW",headIds(action.conflictId()),null);
    }
    private void materialize(Scope scope,String status,List<UUID> decisions,UUID winner) {
        List<UUID> operations=candidates(scope);
        Set<UUID> objects=new TreeSet<>();for(UUID id:operations)objects.add(store.readRevision(id).action().objectGid());
        for(UUID gid:objects)support.projectionState(scope.kind(),gid,"AUTH_REVIEW".equals(status)?"AUTH_REVIEW":
                Set.of("OPEN","CONFLICT").contains(status)?"CONFLICT":"SUPPRESSED",decisions);
        if(!"RESOLVED".equals(status)||winner==null)return;
        var action=store.readRevision(winner).action();
        if(action.operation()==CatalogOperation.DELETE)return; // Approved disputed deletion stays reversibly suppressed while decisions may conflict.
        var identity=store.lockObject(scope.kind(),action.objectGid());
        if(identity==null||identity.rejected())return;
        if(identity.tombstone() && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_authorization_review a JOIN eatwhat_sync.catalog_revision r ON r.operation_id=a.event_id JOIN eatwhat_sync.catalog_object o ON o.kind=r.kind AND o.object_gid=r.object_gid CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE r.kind=? AND r.object_gid=? AND r.tombstone AND h->>'operationId'=r.operation_id::text)",Boolean.class,scope.kind().name(),identity.gid())))return;
        store.updateObject(new CatalogStore.Identity(identity.kind(),identity.gid(),identity.numericId(),identity.originSite(),identity.authoritySite(),
                identity.authorGid(),action.parentGid(),identity.revision(),false,false,identity.causalState()));
        // Only the narrow SQL function may rebuild from a selected decision; ordinary public UPDATE remains denied.
        String result=jdbc.queryForObject("SELECT eatwhat_sync.apply_catalog_decision_projection(?)",String.class,decisions.getFirst());
        if(!"APPLIED".equals(result))throw new IllegalStateException("CATALOG_DECISION_PROJECTION_"+result);
        support.projectionState(scope.kind(),identity.gid(),"VISIBLE",decisions);

    }
    private Scope scope(UUID id) {
        var head=jdbc.query("SELECT kind,object_gid FROM eatwhat_sync.catalog_head_conflict WHERE conflict_id=?",(rs,n)->new Scope(id,CatalogKind.valueOf(rs.getString(1)),rs.getObject(2,UUID.class),null),id);
        if(!head.isEmpty())return head.getFirst();
        return jdbc.query("SELECT kind,key_hash FROM eatwhat_sync.catalog_conflict WHERE conflict_id=?",(rs,n)->new Scope(id,CatalogKind.valueOf(rs.getString(1)),null,rs.getString(2).trim()),id).stream().findFirst().orElse(null);
    }
    private List<UUID> candidates(Scope scope) {
        if(scope.objectGid()!=null) return jdbc.query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE o.kind=? AND o.object_gid=? ORDER BY 1",
                (rs,n)->rs.getObject(1,UUID.class),scope.kind().name(),scope.objectGid());
        return jdbc.query("SELECT DISTINCT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_candidate c JOIN eatwhat_sync.catalog_object o ON o.kind=c.kind AND o.object_gid=c.object_gid CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h JOIN eatwhat_sync.catalog_revision r ON r.operation_id=(h->>'operationId')::uuid WHERE c.kind=? AND c.key_hash=? AND c.status NOT IN ('REJECTED','SUPERSEDED') AND NOT r.tombstone ORDER BY 1",
                (rs,n)->rs.getObject(1,UUID.class),scope.kind().name(),scope.keyHash());
    }
    private void verifyCandidates(Scope scope,List<UUID> operations) {
        for(UUID id:operations) {
            var revision=store.readRevision(id);if(revision==null)throw new IllegalStateException("CATALOG_DECISION_CANDIDATE_MISSING");
            var a=revision.action();if(a.kind()!=scope.kind())throw new IllegalArgumentException("CATALOG_DECISION_CANDIDATE_INVALID");
            if(a.operation()==CatalogOperation.DELETE && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_authorization_review WHERE event_id=? AND kind=? AND object_gid=?)",Boolean.class,a.operationId(),a.kind().name(),a.objectGid())))throw new IllegalArgumentException("CATALOG_DECISION_CANDIDATE_INVALID");
            if(scope.objectGid()!=null){if(!scope.objectGid().equals(a.objectGid()))throw new IllegalArgumentException("CATALOG_DECISION_OBJECT_MISMATCH");}
            else if(scope.kind()==CatalogKind.HUB) {
                Boolean match=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_import_claim c JOIN eatwhat_sync.catalog_natural_key k ON k.kind='HUB' AND k.key_hash=? AND k.canonical_key->>'sourceKey'=c.source_key AND k.canonical_key->>'sourceId'=c.source_id WHERE c.object_gid=?)",Boolean.class,scope.keyHash(),a.objectGid());
                if(!Boolean.TRUE.equals(match))throw new IllegalArgumentException("CATALOG_IMPORT_SOURCE_MISMATCH");
            }
            else {var key=CatalogPayload.naturalKey(a.kind(),a.canonicalPayload(),a.authorGid()==null?null:a.authorGid().toString(),a.parentGid()==null?null:a.parentGid().toString());
                if(key==null||!key.hash().equals(scope.keyHash()))throw new IllegalArgumentException("CATALOG_DECISION_NATURAL_KEY_MISMATCH");}
        }
    }
    private CatalogDecisionEvents.Action read(UUID id){return jdbc.queryForObject("SELECT payload::text FROM eatwhat_sync.catalog_decision_operation WHERE operation_id=?",(rs,n)->CatalogDecisionEvents.parse(rs.getString(1)),id);}
    private List<UUID> headIds(UUID conflict){return jdbc.query("SELECT heads::text FROM eatwhat_sync.catalog_decision_state WHERE conflict_id=?",(rs,n)->uuidList(rs.getString(1)),conflict).stream().findFirst().orElse(List.of());}
    private Map<String,Long> seen(UUID conflict){return jdbc.query("SELECT seen::text FROM eatwhat_sync.catalog_decision_state WHERE conflict_id=?",(rs,n)->JSON.<Map<String,Long>>readValue(rs.getString(1),JSON.getTypeFactory().constructMapType(Map.class,String.class,Long.class)),conflict).stream().findFirst().orElse(Map.of());}
    private List<CatalogEvents.ConsentEpoch> epochs(List<UUID> candidates){Map<UUID,CatalogEvents.ConsentEpoch> values=new TreeMap<>();for(UUID id:candidates)for(UUID subject:store.subjectsForOperation(id)){var state=consent.getBySubject(subject);if(state==null)throw new IllegalStateException("CATALOG_DECISION_CONSENT_MISSING");values.put(subject,new CatalogEvents.ConsentEpoch(subject,state.epoch()));}return List.copyOf(values.values());}
    private boolean epochsReady(CatalogDecisionEvents.Action action){for(var epoch:action.consentEpochs()){var state=consent.getBySubject(epoch.subjectGid());if(state==null||state.epoch()!=epoch.epoch()||!consent.removalReplicationAllowed(state.userId()))return false;}return true;}
    private static boolean same(Collection<UUID> a,Collection<UUID> b){return b!=null&&a.size()==new HashSet<>(b).size()&&new HashSet<>(a).equals(new HashSet<>(b));}
    private static String json(Object o){return JSON.writeValueAsString(o);}
    private static List<UUID> uuidList(String s){return JSON.readValue(s,JSON.getTypeFactory().constructCollectionType(List.class,UUID.class));}
}
