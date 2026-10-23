package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.SyncTransactions;
import java.util.*;

/** Retained release admission, serialized with membership writes on the authority state row. */
public final class ClusterDeploymentFence {
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    public record Binding(String siteId,UUID instanceId,String databaseFingerprint){}
    private final JdbcTemplate jdbc;private final SyncTransactions tx;private final ClusterControlLease lease;private final UUID stateId;
    public ClusterDeploymentFence(JdbcTemplate jdbc,SyncTransactions tx,ClusterControlLease lease,UUID stateId){this.jdbc=jdbc;this.tx=tx;this.lease=lease;this.stateId=Objects.requireNonNull(stateId);tx.requireSameDataSource(jdbc);}
    public boolean reserve(UUID id,String manifest,long epoch,List<Binding> bindings){
        return reserve(id,manifest,epoch,bindings,null);
    }
    public boolean reserve(UUID id,String manifest,long epoch,List<Binding> bindings,String callbackTokenHash){
        Objects.requireNonNull(id);hash(manifest);if(epoch<1||bindings==null||bindings.isEmpty()||bindings.size()>16||bindings.stream().map(Binding::siteId).distinct().count()!=bindings.size())throw failure("INVALID_DEPLOYMENT_TARGETS");
        if(callbackTokenHash!=null)hash(callbackTokenHash);
        for(var binding:bindings){ClusterStore.name(binding.siteId());Objects.requireNonNull(binding.instanceId());if(binding.databaseFingerprint()==null||!binding.databaseFingerprint().matches("[0-9a-f]{32}"))throw failure("DEPLOYMENT_DATABASE_PROOF_REQUIRED");}
        return lease.write(tx,()->{
            var state=lockState();requireStateOwner();
            if(number(state,"epoch")!=epoch)throw failure("STALE_MEMBERSHIP_EPOCH");
            var prior=rows(id);if(!prior.isEmpty()){bound(prior.getFirst(),manifest);if(!"ACTIVE".equals(prior.getFirst().get("state"))||epoch!=number(prior.getFirst(),"epoch")||!Objects.equals(callbackTokenHash,prior.getFirst().get("callback_token_hash"))||!bindings.equals(List.of(JSON.readValue(prior.getFirst().get("targets_json").toString(),Binding[].class))))throw failure("DEPLOYMENT_FENCE_IDEMPOTENCY_CONFLICT");return false;}
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE cluster_id=? AND state='ACTIVE')",Boolean.class,lease.clusterId()))throw failure("DEPLOYMENT_RECONCILIATION_REQUIRED");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.change WHERE state<>'APPLIED')",Boolean.class))throw failure("MEMBERSHIP_CHANGE_PENDING");
            for(var binding:bindings){var members=jdbc.queryForList("SELECT instance_id,state,database_fingerprint FROM eatwhat_cluster.member WHERE site_id=?",binding.siteId());if(members.size()!=1||!binding.instanceId().equals(members.getFirst().get("instance_id"))||!"ACTIVE".equals(members.getFirst().get("state"))||!binding.databaseFingerprint().equals(members.getFirst().get("database_fingerprint")))throw failure("DEPLOYMENT_TARGET_IDENTITY_CHANGED");}
            long generation=jdbc.queryForObject("SELECT generation FROM eatwhat_cluster.control_lease WHERE singleton",Long.class);
            jdbc.update("INSERT INTO eatwhat_cluster.deployment_fence(job_id,cluster_id,state_id,holder_id,generation,epoch,manifest_hash,callback_token_hash,targets,state) VALUES(?,?,?,?,?,?,?,?,?::jsonb,'ACTIVE')",id,lease.clusterId(),stateId,lease.holderId(),generation,epoch,manifest,callbackTokenHash,ClusterStore.json(bindings));return true;
        });
    }
    /** Read-only verification. Terminal journals may be inspected after the fence was finalized. */
    public Map<String,Object> verify(UUID id,String manifest,boolean applying){
        hash(manifest);var rows=rows(id);if(rows.isEmpty())throw failure("DEPLOYMENT_FENCE_NOT_FOUND");var row=rows.getFirst();bound(row,manifest);if(applying&&!"ACTIVE".equals(row.get("state")))throw failure("DEPLOYMENT_FENCE_NOT_ACTIVE");if(applying&&!Boolean.TRUE.equals(row.get("dispatch_authorized")))throw failure("DEPLOYMENT_DISPATCH_NOT_AUTHORIZED");return view(row);
    }
    public void authorizeDispatch(UUID id,String manifest){hash(manifest);lease.write(tx,()->{var state=lockState();requireStateOwner();var rows=rows(id);if(rows.isEmpty())throw failure("DEPLOYMENT_FENCE_NOT_FOUND");var row=rows.getFirst();bound(row,manifest);if(!"ACTIVE".equals(row.get("state"))||number(state,"epoch")!=number(row,"epoch"))throw failure("DEPLOYMENT_FENCE_NOT_ACTIVE");if(Boolean.TRUE.equals(row.get("dispatch_authorized")))throw failure("DEPLOYMENT_RECONCILIATION_REQUIRED");jdbc.update("UPDATE eatwhat_cluster.deployment_fence SET dispatch_authorized=true WHERE job_id=? AND NOT dispatch_authorized",id);return null;});}
    public boolean noDispatchProven(UUID id,String manifest){hash(manifest);var rows=rows(id);if(rows.isEmpty())return true;bound(rows.getFirst(),manifest);return !Boolean.TRUE.equals(rows.getFirst().get("dispatch_authorized"));}
    /** Recovery continues an admitted job; draining does not admit a new release. */
    public void requireRecovery(UUID id,String manifest,boolean applying){lease.requireHeld();var proof=verify(id,manifest,applying);if(!Boolean.TRUE.equals(proof.get("dispatch_authorized")))throw failure("DEPLOYMENT_DISPATCH_NOT_AUTHORIZED");}
    /** Only a held authority lease and the original private state can finalize a known result. */
    public void finish(UUID id,String manifest,String outcome,boolean noRemoteDispatchProven){
        hash(manifest);if(!Set.of("SUCCEEDED","FAILED","PARTIAL_FAILURE").contains(outcome))throw failure("DEPLOYMENT_FENCE_OUTCOME_REQUIRED");
        lease.write(tx,()->{var state=lockState();requireStateOwner();var rows=rows(id);
            if(rows.isEmpty()){if(noRemoteDispatchProven&&"FAILED".equals(outcome))return null;throw failure("DEPLOYMENT_FENCE_NOT_FOUND");}
            var row=rows.getFirst();bound(row,manifest);
            if(!"ACTIVE".equals(row.get("state"))){if(!outcome.equals(row.get("state")))throw failure("DEPLOYMENT_FENCE_OUTCOME_CONFLICT");return null;}
            if(!"FAILED".equals(outcome)&&!Boolean.TRUE.equals(row.get("dispatch_authorized")))throw failure("DEPLOYMENT_DISPATCH_NOT_AUTHORIZED");
            if(number(state,"epoch")!=number(row,"epoch"))throw failure("STALE_MEMBERSHIP_EPOCH");
            jdbc.update("UPDATE eatwhat_cluster.deployment_fence SET state=?,finished_at=clock_timestamp() WHERE job_id=? AND state='ACTIVE'",outcome,id);return null;
        });
    }
    public boolean finalized(UUID id,String manifest,String outcome){try{return outcome.equals(verify(id,manifest,false).get("state"));}catch(ClusterFailure unavailable){return false;}}
    public boolean exists(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE job_id=? AND cluster_id=?)",Boolean.class,id,lease.clusterId()));}
    /** A per-release read capability; it grants no node, member, SQL or finalization operation. */
    public Map<String,Object> callback(UUID id,String manifest,String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new you.v50to.eatwhat.control.ControlFailure(401,"DEPLOYMENT_CALLBACK_AUTHENTICATION_REQUIRED");
        var rows=rows(id);String supplied=tokenHash(token);Object expected=rows.isEmpty()?null:rows.getFirst().get("callback_token_hash");
        if(!(expected instanceof String value)||!java.security.MessageDigest.isEqual(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII),supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new you.v50to.eatwhat.control.ControlFailure(401,"DEPLOYMENT_CALLBACK_AUTHENTICATION_REQUIRED");
        hash(manifest);var row=rows.getFirst();bound(row,manifest);long epoch=jdbc.queryForObject("SELECT epoch FROM eatwhat_cluster.state WHERE singleton AND cluster_id=?",Long.class,lease.clusterId());
        var result=new LinkedHashMap<String,Object>();result.put("protocol","eatwhat-deployment-fence-v1");result.put("id",id.toString());result.put("clusterId",lease.clusterId().toString());result.put("epoch",row.get("epoch"));result.put("manifestHash",manifest);result.put("state",row.get("state"));result.put("dispatchAuthorized",row.get("dispatch_authorized"));result.put("authorized","ACTIVE".equals(row.get("state"))&&Boolean.TRUE.equals(row.get("dispatch_authorized"))&&epoch==number(row,"epoch"));result.put("targets",List.of(JSON.readValue(row.get("targets_json").toString(),Binding[].class)));return result;
    }
    public static String tokenHash(String token){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public List<Map<String,Object>> active(){return jdbc.queryForList("SELECT job_id,cluster_id,state_id,holder_id,generation,epoch,manifest_hash,state,dispatch_authorized,created_at,finished_at,targets::text AS targets_json FROM eatwhat_cluster.deployment_fence WHERE cluster_id=? AND state='ACTIVE'",lease.clusterId()).stream().map(this::view).toList();}
    private List<Map<String,Object>> rows(UUID id){return jdbc.queryForList("SELECT *,targets::text AS targets_json FROM eatwhat_cluster.deployment_fence WHERE job_id=? AND cluster_id=?",id,lease.clusterId());}
    private Map<String,Object> lockState(){var rows=jdbc.queryForList("SELECT cluster_id,epoch FROM eatwhat_cluster.state WHERE singleton FOR UPDATE");if(rows.size()!=1||!lease.clusterId().equals(rows.getFirst().get("cluster_id")))throw failure("CLUSTER_BINDING_MISMATCH");return rows.getFirst();}
    private void requireStateOwner(){if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.control_process WHERE holder_id=? AND cluster_id=? AND state_id=? AND deployment_protocol=1)",Boolean.class,lease.holderId(),lease.clusterId(),stateId)))throw failure("CONTROL_SHARED_STATE_REQUIRED");}
    private void bound(Map<String,Object> row,String manifest){if(!manifest.equals(row.get("manifest_hash")))throw failure("DEPLOYMENT_FENCE_MANIFEST_CHANGED");if(!stateId.equals(row.get("state_id")))throw failure("CONTROL_SHARED_STATE_REQUIRED");}
    private Map<String,Object> view(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(String key:List.of("job_id","cluster_id","state_id","holder_id","generation","epoch","manifest_hash","state","dispatch_authorized","created_at","finished_at")){Object value=row.get(key);if(value!=null)result.put(key,value instanceof UUID?value.toString():value instanceof java.sql.Timestamp t?t.toInstant().toString():value);}return result;}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw failure("DEPLOYMENT_FENCE_MANIFEST_REQUIRED");}
    private static ClusterFailure failure(String code){return new ClusterFailure(409,code);}
}
