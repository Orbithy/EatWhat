package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;

/** Stages immutable decision DAGs before catalog bridges; active consent epochs remain outside historical operations. */
public final class CatalogDecisionBackfill implements EventHandler {
    public static final String TYPE="catalog.decision.bridge";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Bridge(CatalogKind kind,UUID objectGid,List<CatalogEvents.ConsentEpoch> consentEpochs,List<CatalogDecisionEvents.Action> decisions,List<CatalogEvents.Action> candidates,Map<UUID,CatalogActorVerifier.Evidence> candidateEvidence,List<UUID> reviewedOperations,List<UUID> reviewedDecisions) { }
    private final JdbcTemplate jdbc;private final SyncTransactions transactions;private final CatalogMultiwriterSupport support;
    private final CatalogDecisionService service;private final CatalogCommands commands;private final ConsentStore consent;
    private final CatalogEventHandler staging;private final CatalogStore store;private final OutboxService outbox;private final SiteProperties site;private final Set<String> sites;
    public CatalogDecisionBackfill(JdbcTemplate jdbc,SyncTransactions transactions,CatalogMultiwriterSupport support,CatalogDecisionService service,
            CatalogCommands commands,ConsentStore consent,OutboxService outbox,SiteProperties site,Set<String> sites,CatalogEventHandler staging,CatalogStore store){
        this.staging=staging;this.store=store;staging.enableMultiwriterReader(support);this.jdbc=jdbc;this.transactions=transactions;this.support=support;this.service=service;this.commands=commands;this.consent=consent;this.outbox=outbox;this.site=site;this.sites=sites;
        commands.setDecisionBackfill(this);
    }
    @Override public String aggregateType(){return CatalogDecisionEvents.AGGREGATE;}
    @Override public String eventType(){return TYPE;}
    @Override public int schemaVersion(){return 2;}
    public Set<String> aggregateTypes(){return Set.of(aggregateType());}
    public Map<String,Set<Integer>> supportedSchemaVersions(){return Map.of(aggregateType(),Set.of(2));}
    private Bridge parse(SyncEvent event){
        if(!aggregateType().equals(event.aggregateType())||!TYPE.equals(event.eventType())||event.schemaVersion()!=2||!sites.contains(event.originSite())||!event.eventId().equals(event.aggregateId())||event.actorId()!=null||!event.nextVersion().equals(VersionVector.empty().increment(event.originSite()))||!event.baseVersion().equals(VersionVector.empty())||!event.dependencies().isEmpty())throw new IllegalArgumentException("INVALID_DECISION_BRIDGE");
        Bridge bridge=JSON.readValue(event.payloadJson(),Bridge.class);
        if(bridge.kind()==null||bridge.objectGid()==null||bridge.decisions()==null||bridge.decisions().isEmpty()||bridge.decisions().size()>1024||bridge.consentEpochs()==null||bridge.candidates()==null||bridge.candidates().size()>2048||bridge.candidateEvidence()==null||bridge.reviewedOperations()==null||bridge.reviewedDecisions()==null)throw new IllegalArgumentException("INVALID_DECISION_BRIDGE");
        Set<UUID> ids=new HashSet<>();for(var a:bridge.decisions())if(!ids.add(a.decision().operationId())||a.kind()!=bridge.kind()||!sites.contains(a.decision().site())||!sites.containsAll(a.decision().context().keySet()))throw new IllegalArgumentException("INVALID_DECISION_BRIDGE");
        Set<UUID> required=new HashSet<>();bridge.decisions().forEach(a->required.addAll(a.decision().candidateOperations()));
        Set<UUID> supplied=new HashSet<>();for(var a:bridge.candidates()){
            CatalogEvents.parseAction(CatalogEvents.payload(a));
            if(a.kind()!=bridge.kind()||!supplied.add(a.operationId())||!sites.contains(a.dotSite())||!sites.contains(a.identityOrigin())||!sites.containsAll(a.context().keySet()))throw new IllegalArgumentException("INVALID_DECISION_CANDIDATE");
        }
        if(!required.equals(supplied)||!supplied.containsAll(bridge.candidateEvidence().keySet())||!supplied.containsAll(bridge.reviewedOperations())||!ids.containsAll(bridge.reviewedDecisions()))throw new IllegalArgumentException("DECISION_CANDIDATE_SET_MISMATCH");
        return bridge;
    }
    public void validate(SyncEvent event,NodeIdentity origin){if(!event.originSite().equals(origin.siteId())||!event.originInstanceId().equals(origin.instanceId()))throw new IllegalArgumentException("DECISION_BRIDGE_ORIGIN");parse(event);}
    public EventPolicy.OutboundDecision outbound(SyncEvent event){var bridge=parse(event);for(var e:bridge.consentEpochs()){var a=consent.outboundRemovalAuthorization(e.subjectGid(),e.epoch(),e.subjectGid(),e.epoch());if(a.blocked())return EventPolicy.OutboundDecision.POLICY_BLOCKED;if(!a.allowed())return EventPolicy.OutboundDecision.WAIT;}return EventPolicy.OutboundDecision.ALLOW;}
    private boolean ready(Bridge bridge){for(var e:bridge.consentEpochs()){var s=consent.getBySubject(e.subjectGid());if(s==null||s.epoch()!=e.epoch()||!consent.removalReplicationAllowed(s.userId()))return false;}return true;}
    @Override public boolean dependenciesSatisfied(SyncEvent event){var b=parse(event);if(!ready(b))return false;for(var a:b.decisions()){
        var verdict=support.assess(a.actorEvidence());if(verdict==CatalogActorVerifier.Assessment.DEPENDENCY_PENDING)return false;
        if(verdict==CatalogActorVerifier.Assessment.KNOWN_REVOKED)throw new IllegalArgumentException("DECISION_BRIDGE_ACTOR_REVOKED");
        if(!"admin".equals(a.actorEvidence().authorization().get("role")))throw new IllegalArgumentException("DECISION_BRIDGE_ADMIN_REQUIRED");
    }
        Set<UUID> subjects=new HashSet<>();b.consentEpochs().forEach(e->subjects.add(e.subjectGid()));
        for(var candidate:b.candidates()) {
            if(candidate.authorGid()!=null&&!subjects.contains(candidate.authorGid()))throw new IllegalArgumentException("DECISION_CANDIDATE_CONSENT_MISSING");
            if(candidate.kind()==CatalogKind.FOOD && candidate.parentGid()!=null){var parent=store.findObject(CatalogKind.RESTAURANT,candidate.parentGid());if(parent==null)return false;if(parent.authorGid()!=null&&!subjects.contains(parent.authorGid()))throw new IllegalArgumentException("DECISION_PARENT_CONSENT_MISSING");}
            var proof=b.candidateEvidence().get(candidate.operationId());
            if(proof!=null){var assessment=support.assess(proof);if(assessment==CatalogActorVerifier.Assessment.DEPENDENCY_PENDING)return false;if(assessment==CatalogActorVerifier.Assessment.KNOWN_REVOKED)throw new IllegalArgumentException("DECISION_CANDIDATE_ACTOR_REVOKED");CatalogMultiwriterSupport.requirePermission(candidate,proof);}
            else if(!candidate.dotSite().equals(candidate.authoritySite()) && candidate.operation()!=CatalogOperation.CREATE)throw new IllegalArgumentException("DECISION_CANDIDATE_PROOF_MISSING");
        }
        return true;}
    @Override public void apply(SyncEvent event){
        var b=parse(event);if(!dependenciesSatisfied(event))throw new IllegalStateException("DECISION_BRIDGE_DEPENDENCY_CHANGED");
        Set<UUID> reviews=new HashSet<>(b.reviewedOperations());
        b.candidateEvidence().forEach((id,proof)->{var verdict=support.assess(proof);if(verdict==CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW||verdict==CatalogActorVerifier.Assessment.KNOWN_REVOKED)reviews.add(id);});
        for(UUID id:reviews) {
            var candidate=b.candidates().stream().filter(a->a.operationId().equals(id)).findFirst().orElseThrow();
            jdbc.update("INSERT INTO eatwhat_sync.catalog_authorization_review(event_id,object_gid,kind,envelope,reason) VALUES(?,?,?,?::jsonb,'REPLICATED_REVIEW') ON CONFLICT DO NOTHING",id,candidate.objectGid(),candidate.kind().name(),JSON.writeValueAsString(event));
        }
        for(UUID id:b.reviewedDecisions()) {
            var action=b.decisions().stream().filter(a->a.decision().operationId().equals(id)).findFirst().orElseThrow();
            jdbc.update("INSERT INTO eatwhat_sync.catalog_decision_authorization_review(operation_id,conflict_id) VALUES(?,?) ON CONFLICT DO NOTHING",id,action.conflictId());
        }
        staging.stageDecisionCandidates(b.candidates());
        b.candidateEvidence().forEach(support::save);
        for(var a:b.decisions()){
            String payload=CatalogDecisionEvents.payload(a);
            var known=jdbc.queryForList("SELECT payload::text FROM eatwhat_sync.catalog_decision_archive WHERE operation_id=?",String.class,a.decision().operationId());
            if(!known.isEmpty()&&!EventCodec.canonicalJson(known.getFirst()).equals(payload))throw new IllegalArgumentException("DECISION_BRIDGE_IDENTITY_CONFLICT");
            jdbc.update("INSERT INTO eatwhat_sync.catalog_decision_archive(operation_id,bridge_event_id,payload) VALUES(?,?,?::jsonb) ON CONFLICT DO NOTHING",a.decision().operationId(),event.eventId(),payload);
        }
        applyReady(event,b);
    }
    private void applyReady(SyncEvent event,Bridge bridge){
        if(!ready(bridge))return;
        for(var action:bridge.decisions())if(service.dependenciesForReplay(action,bridge.consentEpochs()))service.apply(action);
    }
    @Scheduled(fixedDelayString="${app.sync.catalog.decision-backfill-delay-ms:5000}")
    public void runOnce(){
        transactions.required(()->{
            var events=jdbc.queryForList("SELECT DISTINCT i.envelope::text FROM eatwhat_sync.catalog_decision_archive a JOIN eatwhat_sync.inbox i ON i.event_id=a.bridge_event_id WHERE i.status='APPLIED' AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_decision_operation d WHERE d.operation_id=a.operation_id) LIMIT 32",String.class);
            for(String raw:events){var event=new EventCodec().decode(raw);applyReady(event,parse(event));}return null;
        });
    }
    public void publish(CatalogKind kind,UUID gid,List<CatalogEvents.ConsentEpoch> epochs){
        if(!commands.multiWriterEnabled())return;
        var actions=jdbc.queryForList("SELECT payload::text FROM eatwhat_sync.catalog_decision_operation WHERE kind=? AND (object_gid=? OR conflict_id IN (SELECT c.conflict_id FROM eatwhat_sync.catalog_conflict c JOIN eatwhat_sync.catalog_candidate a ON a.kind=c.kind AND a.key_hash=c.key_hash WHERE a.object_gid=?)) ORDER BY dot_counter,source_site",String.class,kind.name(),gid,gid).stream().map(CatalogDecisionEvents::parse).toList();
        if(actions.isEmpty())return;
        Map<UUID,CatalogEvents.ConsentEpoch> epochMap=new TreeMap<>();epochs.forEach(e->epochMap.put(e.subjectGid(),e));
        var candidates=actions.stream().flatMap(a->a.decision().candidateOperations().stream()).distinct().map(store::readRevision).map(CatalogStore.ImmutableRevision::action).toList();
        Map<UUID,CatalogActorVerifier.Evidence> proofs=new HashMap<>();
        for(var candidate:candidates){var proof=support.find(candidate.operationId());if(proof!=null)proofs.put(candidate.operationId(),proof);for(UUID subject:store.subjectsForOperation(candidate.operationId())){var state=consent.getBySubject(subject);if(state==null||!consent.removalReplicationAllowed(state.userId()))return;epochMap.put(subject,new CatalogEvents.ConsentEpoch(subject,state.epoch()));}}
        List<UUID> reviewed=candidates.stream().map(CatalogEvents.Action::operationId).filter(id->Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_authorization_review WHERE event_id=?)",Boolean.class,id))).toList();
        List<UUID> reviewedDecisions=actions.stream().map(a->a.decision().operationId()).filter(id->Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_decision_authorization_review WHERE operation_id=?)",Boolean.class,id))).toList();
        Bridge bridge=new Bridge(kind,gid,List.copyOf(epochMap.values()),actions,candidates,Map.copyOf(proofs),reviewed,reviewedDecisions);String payload=EventCodec.canonicalJson(JSON.writeValueAsString(bridge));UUID id=UUID.nameUUIDFromBytes((TYPE+":"+site.getId()+":"+site.getInstanceId()+":"+payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.outbox WHERE event_id=?)",Boolean.class,id)))return;
        var event=new SyncEvent(id,site.getId(),site.getInstanceId(),aggregateType(),id,jdbc.queryForObject("SELECT eatwhat_sync.next_catalog_number()",Long.class),TYPE,2,VersionVector.empty(),VersionVector.empty().increment(site.getId()),List.of(),null,TYPE+":"+id,id,payload);
        outbox.append(event,commands.recipients());
    }
}
