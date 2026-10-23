package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts;
import you.v50to.eatwhat.sync.EventCodec;
import java.util.*;

/** Persistent authorization evidence for independently originated directory operations. */
public final class CatalogMultiwriterSupport {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final MultiwriterAccounts accounts;
    public CatalogMultiwriterSupport(JdbcTemplate jdbc, MultiwriterAccounts accounts) {
        this.jdbc=Objects.requireNonNull(jdbc);this.accounts=Objects.requireNonNull(accounts);
    }
    public boolean ready() { return accounts.enabled() && Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT enabled FROM eatwhat_sync.catalog_multiwriter_config WHERE singleton",Boolean.class)); }
    public CatalogActorVerifier.Evidence capture(long actor) {
        if(!ready()) throw new IllegalStateException("CATALOG_MULTIWRITER_NOT_ACTIVATED");
        var evidence=accounts.capture(actor);
        if(evidence.banned()) throw new IllegalStateException("CATALOG_ACTOR_REVOKED");
        Map<String,Object> fields=JSON.convertValue(evidence,JSON.getTypeFactory().constructMapType(Map.class,String.class,Object.class));
        return new CatalogActorVerifier.Evidence(evidence.subjectId(),fields);
    }
    public CatalogActorVerifier.Assessment assess(CatalogActorVerifier.Evidence proof) {
        var evidence=JSON.convertValue(proof.authorization(),MultiwriterAccounts.AuthorizationEvidence.class);
        if(!proof.actorGid().equals(evidence.subjectId())) return CatalogActorVerifier.Assessment.KNOWN_REVOKED;
        return CatalogActorVerifier.Assessment.valueOf(accounts.assess(evidence).name());
    }
    public void captureCommand(CatalogEvents.Action action) {
        Long actor=jdbc.queryForObject("SELECT eatwhat_sync.catalog_command_actor(?,?)",Long.class,action.kind().name(),action.numericId());
        if(actor==null) throw new IllegalStateException("CATALOG_ACTOR_CONTEXT_REQUIRED");
        CatalogActorVerifier.Evidence evidence=capture(actor);
        requirePermission(action,evidence);
        save(action.operationId(),new CatalogActorVerifier.Evidence(evidence.actorGid(),evidence.authorization(),decisionHeads(action.kind(),action.objectGid())));
    }
    public List<UUID> decisionHeads(CatalogKind kind,UUID object) {
        return jdbc.query("SELECT DISTINCT h.value::uuid FROM eatwhat_sync.catalog_decision_state d CROSS JOIN LATERAL jsonb_array_elements_text(d.heads) h WHERE d.kind=? AND (d.object_gid=? OR d.conflict_id IN (SELECT c.conflict_id FROM eatwhat_sync.catalog_conflict c JOIN eatwhat_sync.catalog_candidate a ON a.kind=c.kind AND a.key_hash=c.key_hash WHERE a.kind=? AND a.object_gid=?)) ORDER BY 1",(rs,n)->rs.getObject(1,UUID.class),kind.name(),object,kind.name(),object);
    }
    public boolean editable(CatalogKind kind,UUID object) {
        var states=jdbc.queryForList("SELECT status FROM eatwhat_sync.catalog_projection_state WHERE kind=? AND object_gid=?",String.class,kind.name(),object);
        if(!states.isEmpty()&&!states.getFirst().equals("VISIBLE"))return false;
        return !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_decision_state d WHERE d.kind=? AND d.status<>'RESOLVED' AND (d.object_gid=? OR d.conflict_id IN (SELECT c.conflict_id FROM eatwhat_sync.catalog_conflict c JOIN eatwhat_sync.catalog_candidate a ON a.kind=c.kind AND a.key_hash=c.key_hash WHERE a.kind=? AND a.object_gid=?)))",Boolean.class,kind.name(),object,kind.name(),object));
    }
    public boolean decisionDependencies(CatalogEvents.Action action,CatalogActorVerifier.Evidence evidence) {
        for(UUID id:evidence.decisionHeads()) {
            var rows=jdbc.queryForList("SELECT kind,object_gid,candidate_operations::text FROM eatwhat_sync.catalog_decision_operation WHERE operation_id=? UNION ALL SELECT payload->>'kind' kind,(payload->>'objectGid')::uuid object_gid,(payload->'decision'->'candidateOperations')::text candidate_operations FROM eatwhat_sync.catalog_decision_archive WHERE operation_id=?",id,id);
            if(rows.isEmpty())return false;
            var row=rows.getFirst();if(!action.kind().name().equals(row.get("kind")))throw new IllegalArgumentException("CATALOG_DECISION_CONTEXT_SCOPE");
            List<UUID> candidates=JSON.readValue((String)row.get("candidate_operations"),JSON.getTypeFactory().constructCollectionType(List.class,UUID.class));
            boolean match=false;for(UUID candidate:candidates)if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision WHERE operation_id=? AND object_gid=?)",Boolean.class,candidate,action.objectGid())))match=true;
            if(!match)throw new IllegalArgumentException("CATALOG_DECISION_CONTEXT_SCOPE");
        }
        return true;
    }
    public boolean observesDecisions(UUID operation,List<UUID> decisions) {
        var evidence=find(operation);return evidence!=null && evidence.decisionHeads().containsAll(decisions);
    }
    public void reconcileEdit(CatalogEvents.Action action,CatalogActorVerifier.Evidence evidence) {
        var current=decisionHeads(action.kind(),action.objectGid());
        if(current.isEmpty())return;
        if(evidence.decisionHeads().containsAll(current) && editable(action.kind(),action.objectGid()))return;
        jdbc.update("UPDATE eatwhat_sync.catalog_decision_state d SET status=CASE WHEN status IN ('AUTH_REVIEW','CONFLICT') THEN status ELSE 'OPEN' END,winner_operation_id=NULL,updated_at=clock_timestamp() WHERE d.kind=? AND (d.object_gid=? OR d.conflict_id IN (SELECT c.conflict_id FROM eatwhat_sync.catalog_conflict c JOIN eatwhat_sync.catalog_candidate a ON a.kind=c.kind AND a.key_hash=c.key_hash WHERE a.kind=? AND a.object_gid=?))",action.kind().name(),action.objectGid(),action.kind().name(),action.objectGid());
        projectionState(action.kind(),action.objectGid(),"CONFLICT",current);
    }
    public void save(UUID operation,CatalogActorVerifier.Evidence proof) {
        String json=EventCodec.canonicalJson(JSON.writeValueAsString(proof));
        String hash=EventCodec.sha256(json);
        var known=jdbc.query("SELECT actor_gid,evidence_hash FROM eatwhat_sync.catalog_actor_evidence WHERE operation_id=?",
                (rs,n)->Map.entry(rs.getObject(1,UUID.class),rs.getString(2).trim()),operation);
        if(!known.isEmpty()) {
            if(!known.getFirst().getKey().equals(proof.actorGid()) || !known.getFirst().getValue().equals(hash))
                throw new IllegalArgumentException("CATALOG_ACTOR_EVIDENCE_CONFLICT");
            return;
        }
        jdbc.update("INSERT INTO eatwhat_sync.catalog_actor_evidence(operation_id,actor_gid,evidence,evidence_hash) VALUES(?,?,?::jsonb,?)",
                operation,proof.actorGid(),json,hash);
    }
    public CatalogActorVerifier.Evidence find(UUID operation) {
        return jdbc.query("SELECT evidence::text FROM eatwhat_sync.catalog_actor_evidence WHERE operation_id=?",
                (rs,n)->JSON.readValue(rs.getString(1),CatalogActorVerifier.Evidence.class),operation).stream().findFirst().orElse(null);
    }
    public Map<UUID,CatalogActorVerifier.Evidence> bridgeEvidence(CatalogEvents.StateBridge bridge) {
        Map<UUID,CatalogActorVerifier.Evidence> result=new HashMap<>();
        for(var head:bridge.heads()) {var proof=find(head.operationId());if(proof!=null)result.put(head.operationId(),proof);}
        return Map.copyOf(result);
    }
    /** Call only after assess has authenticated the role and security heads. */
    public static void requirePermission(CatalogEvents.Action action,CatalogActorVerifier.Evidence evidence) {
        boolean admin="admin".equals(evidence.authorization().get("role"));
        boolean owner=evidence.actorGid().equals(action.authorGid());
        boolean allowed=switch(action.kind()) {
            case HUB,SYSTEM_TAG,ACTIVITY -> admin;
            case CUSTOM_TAG -> owner || admin && action.resolutionProof()!=null;
            case FOOD -> action.operation()==CatalogOperation.CREATE ? owner : owner || admin;
            case RESTAURANT -> action.operation()==CatalogOperation.CREATE ? owner : admin;
        };
        if(!allowed || Boolean.TRUE.equals(evidence.authorization().get("banned")))
            throw new IllegalArgumentException("CATALOG_ACTOR_PERMISSION_DENIED");
    }
    public void projectionState(CatalogKind kind,UUID object,String status,List<UUID> decisions) {
        // Object-head and natural-key decisions are independent scopes. Neither may expose a
        // revision rejected, disputed, or still awaiting a decision in another applicable scope.
        if("VISIBLE".equals(status)) {
            decisions=decisionHeads(kind,object);
            status=visibilityAcrossScopes(kind,object,decisions);
        }
        jdbc.update("INSERT INTO eatwhat_sync.catalog_projection_state(kind,object_gid,status,decision_heads) VALUES(?,?,?,?::jsonb) "
                +"ON CONFLICT(kind,object_gid) DO UPDATE SET status=EXCLUDED.status,decision_heads=EXCLUDED.decision_heads,updated_at=clock_timestamp()",
                kind.name(),object,status,JSON.writeValueAsString(decisions.stream().sorted().toList()));
        jdbc.queryForList("SELECT eatwhat_sync.refresh_catalog_visibility(?,numeric_id) FROM eatwhat_sync.catalog_object WHERE kind=? AND object_gid=?",kind.name(),kind.name(),object);
    }
    private record ScopeVisibility(String status,UUID winner,UUID winnerObject,boolean deleted) { }
    private String visibilityAcrossScopes(CatalogKind kind,UUID object,List<UUID> decisions) {
        var scopes=jdbc.query("""
                WITH scopes AS (
                  SELECT c.conflict_id,c.status FROM eatwhat_sync.catalog_head_conflict c
                    WHERE c.kind=? AND c.object_gid=?
                  UNION
                  SELECT c.conflict_id,c.status FROM eatwhat_sync.catalog_conflict c
                    JOIN eatwhat_sync.catalog_candidate a ON a.kind=c.kind AND a.key_hash=c.key_hash
                    WHERE a.kind=? AND a.object_gid=? AND a.status NOT IN ('REJECTED','SUPERSEDED')
                )
                SELECT COALESCE(d.status,s.status),d.winner_operation_id,r.object_gid,COALESCE(r.tombstone,false)
                  FROM scopes s LEFT JOIN eatwhat_sync.catalog_decision_state d ON d.conflict_id=s.conflict_id
                  LEFT JOIN eatwhat_sync.catalog_revision r ON r.operation_id=d.winner_operation_id
                """,(rs,n)->new ScopeVisibility(rs.getString(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getBoolean(4)),
                kind.name(),object,kind.name(),object);
        if(scopes.stream().anyMatch(s->"AUTH_REVIEW".equals(s.status())))return "AUTH_REVIEW";
        if(scopes.stream().anyMatch(s->Set.of("OPEN","CONFLICT").contains(s.status())))return "CONFLICT";
        if(scopes.stream().anyMatch(s->"REJECTED".equals(s.status()) || s.winner()!=null && (!object.equals(s.winnerObject()) || s.deleted())))return "SUPPRESSED";
        var winners=scopes.stream().map(ScopeVisibility::winner).filter(Objects::nonNull).distinct().toList();
        if(winners.size()>1) {
            // A later ordinary edit may legitimately supersede older decisions, but only when
            // its immutable evidence proves it observed every current decision head.
            var heads=jdbc.query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE o.kind=? AND o.object_gid=?",
                    (rs,n)->rs.getObject(1,UUID.class),kind.name(),object);
            if(heads.size()!=1 || !observesDecisions(heads.getFirst(),decisions))return "CONFLICT";
        }
        return "VISIBLE";
    }

}
