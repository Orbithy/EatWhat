package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;

/** Immutable source claims preserve both identities when disconnected imports claim the same external ID. */
public final class CatalogImportReplication implements EventHandler, EventPolicy {
    public static final String AGGREGATE="catalog.import", ACTION="catalog.import.batch";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Claim(UUID claimId,String sourceId,UUID objectGid,long numericId,UUID catalogOperationId) { }
    public record Batch(UUID batchId,String sourceKey,UUID requestId,String batchHash,int created,int updated,
                        List<Claim> claims,CatalogActorVerifier.Evidence actorEvidence) { }
    private final JdbcTemplate jdbc;private final CatalogStore store;private final CatalogMultiwriterSupport support;
    private final CatalogCommands commands;private final OutboxService outbox;private final SiteProperties site;private final Set<String> sites;
    public CatalogImportReplication(JdbcTemplate jdbc,CatalogStore store,CatalogMultiwriterSupport support,CatalogCommands commands,
                                    OutboxService outbox,SiteProperties site,Set<String> sites) {
        this.jdbc=jdbc;this.store=store;this.support=support;this.commands=commands;this.outbox=outbox;this.site=site;this.sites=sites;
    }
    @Override public String aggregateType(){return AGGREGATE;}
    @Override public String eventType(){return ACTION;}
    @Override public int schemaVersion(){return 2;}
    @Override public Set<String> aggregateTypes(){return Set.of(AGGREGATE);}
    @Override public Map<String,Set<Integer>> supportedSchemaVersions(){return Map.of(AGGREGATE,Set.of(2));}
    @Override public void validate(SyncEvent e,NodeIdentity origin){
        if(!e.originSite().equals(origin.siteId())||!e.originInstanceId().equals(origin.instanceId()))throw new IllegalArgumentException("IMPORT_ORIGIN_MISMATCH");parse(e);
    }
    @Override public OutboundDecision outbound(SyncEvent e){parse(e);return OutboundDecision.ALLOW;}
    private Batch parse(SyncEvent e){
        Batch b=JSON.readValue(e.payloadJson(),Batch.class);
        if(!AGGREGATE.equals(e.aggregateType())||!ACTION.equals(e.eventType())||e.schemaVersion()!=2||!sites.contains(e.originSite())
                ||!e.eventId().equals(b.batchId())||!e.aggregateId().equals(e.eventId())||!e.actorId().equals(b.actorEvidence().actorGid())
                ||!e.nextVersion().equals(VersionVector.empty().increment(e.originSite()))||!e.baseVersion().equals(VersionVector.empty())||!e.dependencies().isEmpty()
                ||b.sourceKey()==null||!b.sourceKey().matches("[A-Za-z0-9._:-]{1,128}")||b.requestId()==null
                ||b.batchHash()==null||!b.batchHash().matches("[0-9a-f]{64}")||b.claims()==null||b.claims().isEmpty()||b.claims().size()>100
                ||b.created()<0||b.updated()<0||b.created()+b.updated()!=b.claims().size())throw new IllegalArgumentException("INVALID_IMPORT_BATCH");
        Set<UUID> ids=new HashSet<>();Set<String> sources=new HashSet<>();
        for(Claim c:b.claims()) if(c.claimId()==null||c.objectGid()==null||c.catalogOperationId()==null||c.numericId()<=0
                ||c.sourceId()==null||c.sourceId().isBlank()||c.sourceId().length()>256||!ids.add(c.claimId())||!sources.add(c.sourceId()))
            throw new IllegalArgumentException("INVALID_IMPORT_CLAIM");
        return b;
    }
    @Override public boolean dependenciesSatisfied(SyncEvent e){
        var b=parse(e);var assessment=support.assess(b.actorEvidence());
        if(assessment==CatalogActorVerifier.Assessment.KNOWN_REVOKED)throw new IllegalArgumentException("IMPORT_ADMIN_REVOKED");
        if(assessment==CatalogActorVerifier.Assessment.DEPENDENCY_PENDING)return false;
        requireAdmin(b);
        return b.claims().stream().allMatch(c->store.readRevision(c.catalogOperationId())!=null);
    }
    @Override public void apply(SyncEvent e){apply(parse(e),e.originSite());}
    public void publish(UUID batchId,CatalogHubImportRequest request,CatalogCommands.ImportCounts counts,long actor){
        if(!commands.multiWriterEnabled())return;
        List<Claim> claims=new ArrayList<>();
        for(var item:request.hubs()) {
            var row=jdbc.queryForMap("SELECT object_gid,numeric_id FROM eatwhat_sync.catalog_hub_source WHERE source_key=? AND source_id=?",request.sourceKey(),item.sourceId());
            var identity=store.findObject(CatalogKind.HUB,(UUID)row.get("object_gid"));
            List<UUID> heads=jdbc.query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE kind='HUB' AND object_gid=?",(rs,n)->rs.getObject(1,UUID.class),identity.gid());
            if(heads.size()!=1)throw new IllegalStateException("IMPORT_OBJECT_CONFLICT");
            claims.add(new Claim(UUID.randomUUID(),item.sourceId(),identity.gid(),identity.numericId(),heads.getFirst()));
        }
        var batch=new Batch(batchId,request.sourceKey(),request.requestId(),request.contentHash(),counts.created(),counts.updated(),List.copyOf(claims),support.capture(actor));
        var event=new SyncEvent(batchId,site.getId(),site.getInstanceId(),AGGREGATE,batchId,
                jdbc.queryForObject("SELECT eatwhat_sync.next_catalog_number()",Long.class),ACTION,2,VersionVector.empty(),
                VersionVector.empty().increment(site.getId()),List.of(),batch.actorEvidence().actorGid(),"catalog-import:"+batchId,batchId,json(batch));
        outbox.append(event,commands.recipients());apply(batch,site.getId());
    }
    public CatalogCommands.CatalogImportResult replay(CatalogHubImportRequest request){
        var payloads=jdbc.query("SELECT DISTINCT payload::text FROM eatwhat_sync.catalog_import_claim WHERE source_key=? AND request_id=?",(rs,n)->rs.getString(1),request.sourceKey(),request.requestId());
        if(payloads.isEmpty())return null;
        var batches=payloads.stream().map(p->JSON.readValue(p,Batch.class)).toList();
        Batch first=batches.getFirst();
        boolean conflict=batches.stream().anyMatch(b->!b.batchHash().equals(request.contentHash()));
        return new CatalogCommands.CatalogImportResult(first.batchId(),conflict?CatalogCommands.ImportStatus.CONFLICT:CatalogCommands.ImportStatus.REPLAYED,first.created(),first.updated());
    }
    public void requireUnambiguousSources(CatalogHubImportRequest request){
        for(var item:request.hubs()) {
            String hash=sourceHash(request.sourceKey(),item.sourceId());
            Integer count=jdbc.queryForObject("SELECT count(DISTINCT object_gid) FROM eatwhat_sync.catalog_import_claim WHERE source_key=? AND source_id=?",Integer.class,request.sourceKey(),item.sourceId());
            if(count!=null&&count>1){
                var resolved=jdbc.queryForList("SELECT status FROM eatwhat_sync.catalog_decision_state WHERE conflict_id=?",String.class,CatalogStore.stableConflictId(CatalogKind.HUB,hash));
                if(resolved.size()!=1||!resolved.getFirst().equals("RESOLVED"))throw new IllegalArgumentException("IMPORT_SOURCE_CONFLICT");
            }
        }
    }
    private void apply(Batch b,String origin){
        store.lockDomain();requireAdmin(b);var assessment=support.assess(b.actorEvidence());
        if(assessment==CatalogActorVerifier.Assessment.KNOWN_REVOKED||assessment==CatalogActorVerifier.Assessment.DEPENDENCY_PENDING)throw new IllegalStateException("IMPORT_AUTHORIZATION_CHANGED");
        for(Claim c:b.claims()) {
            var revision=store.readRevision(c.catalogOperationId());
            if(revision==null||revision.action().kind()!=CatalogKind.HUB||!revision.action().objectGid().equals(c.objectGid())
                    ||revision.action().numericId()!=c.numericId()||revision.action().operation()==CatalogOperation.DELETE)
                throw new IllegalArgumentException("IMPORT_CATALOG_EVIDENCE_MISMATCH");
            var existing=jdbc.queryForList("SELECT payload::text FROM eatwhat_sync.catalog_import_claim WHERE operation_id=?",String.class,c.claimId());
            if(!existing.isEmpty()){if(!EventCodec.canonicalJson(existing.getFirst()).equals(json(b)))throw new IllegalArgumentException("IMPORT_CLAIM_IDENTITY_CONFLICT");continue;}
            jdbc.update("INSERT INTO eatwhat_sync.catalog_import_claim(operation_id,source_key,source_id,request_id,batch_hash,object_gid,numeric_id,source_site,actor_gid,actor_evidence,payload) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
                    c.claimId(),b.sourceKey(),c.sourceId(),b.requestId(),b.batchHash(),c.objectGid(),c.numericId(),origin,b.actorEvidence().actorGid(),json(b.actorEvidence()),json(b));
            String canonical=json(Map.of("sourceKey",b.sourceKey(),"sourceId",c.sourceId())),hash=EventCodec.sha256(canonical);
            jdbc.update("INSERT INTO eatwhat_sync.catalog_natural_key(kind,key_hash,canonical_key) VALUES('HUB',?,?::jsonb) ON CONFLICT DO NOTHING",hash,canonical);
            var a=revision.action();
            jdbc.update("INSERT INTO eatwhat_sync.catalog_candidate(candidate_id,kind,key_hash,object_gid,numeric_id,source_site,author_gid,revision,content_hash,summary,status) VALUES(?,'HUB',?,?,?,?,NULL,?,?,?::jsonb,'PENDING') ON CONFLICT(kind,key_hash,object_gid) DO UPDATE SET revision=EXCLUDED.revision,content_hash=EXCLUDED.content_hash,candidate_id=EXCLUDED.candidate_id,updated_at=clock_timestamp() WHERE catalog_candidate.revision<=EXCLUDED.revision",
                    a.operationId(),hash,c.objectGid(),c.numericId(),origin,a.revision(),a.contentHash(),json(Map.of("name",a.canonicalPayload().get("name"))));
            var gids=jdbc.queryForList("SELECT DISTINCT object_gid FROM eatwhat_sync.catalog_import_claim WHERE source_key=? AND source_id=? ORDER BY object_gid",UUID.class,b.sourceKey(),c.sourceId());
            var decisionHeads=support.decisionHeads(CatalogKind.HUB,c.objectGid());
            boolean followsResolution=!decisionHeads.isEmpty() && support.observesDecisions(c.catalogOperationId(),decisionHeads) && support.editable(CatalogKind.HUB,c.objectGid());
            if((gids.size()>1&&!followsResolution)||assessment==CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW){
                jdbc.update("INSERT INTO eatwhat_sync.catalog_conflict(conflict_id,kind,key_hash,revision,status,candidates) VALUES(?,'HUB',?,1,'OPEN',?::jsonb) ON CONFLICT(kind,key_hash) DO UPDATE SET revision=catalog_conflict.revision+1,status='OPEN',candidates=EXCLUDED.candidates,updated_at=clock_timestamp()",CatalogStore.stableConflictId(CatalogKind.HUB,hash),hash,json(gids));
                for(UUID gid:gids)support.projectionState(CatalogKind.HUB,gid,assessment==CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW?"AUTH_REVIEW":"CONFLICT",List.of());

            } else {
                jdbc.update("INSERT INTO eatwhat_sync.catalog_hub_source(source_key,source_id,object_gid,numeric_id,content_hash) VALUES(?,?,?,?,?) ON CONFLICT(source_key,source_id) DO NOTHING",b.sourceKey(),c.sourceId(),c.objectGid(),c.numericId(),a.contentHash());
            }
        }
    }
    private static void requireAdmin(Batch b){if(!"admin".equals(b.actorEvidence().authorization().get("role")))throw new IllegalArgumentException("IMPORT_ADMIN_REQUIRED");}
    static String sourceHash(String key,String id){return EventCodec.sha256(json(Map.of("sourceKey",key,"sourceId",id)));}
    private static String json(Object value){return EventCodec.canonicalJson(JSON.writeValueAsString(value));}
}
