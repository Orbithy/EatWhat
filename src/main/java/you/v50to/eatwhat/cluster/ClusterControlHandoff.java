package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.control.DeploymentExecutor;
import you.v50to.eatwhat.sync.SyncTransactions;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Durable, admin-requested handoff between processes of one authority. Never replays a deployment. */
public final class ClusterControlHandoff {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.control.independent.IndependentControlHandoffQueue independentQueue;
    private final JdbcTemplate jdbc;
    private final SyncTransactions tx;
    private final ClusterControlLease lease;
    private final DeploymentExecutor deployment;
    private final UUID stateId;
    private final String name;
    private volatile BooleanSupplier runtimeIdle=()->true;
    private final java.util.concurrent.atomic.AtomicInteger operations=new java.util.concurrent.atomic.AtomicInteger();

    public ClusterControlHandoff(JdbcTemplate jdbc,SyncTransactions tx,ClusterControlLease lease,DeploymentExecutor deployment,String name){
        this.jdbc=jdbc;this.tx=tx;this.lease=lease;this.deployment=deployment;
        this.name=name==null||name.isBlank()?"controller-"+lease.holderId().toString().substring(0,8):name;
        if(!this.name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))throw new ClusterFailure(503,"CONTROL_CONTROLLER_NAME_INVALID");
        stateId=deployment.stateIdentity();lease.handoff(this);deployment.admissionGuard(lease::requireAccepting);
    }
    public void runtimeIdle(BooleanSupplier idle){runtimeIdle=Objects.requireNonNull(idle);}
    UUID stateIdentity(){return stateId;}
    boolean sharesStateWith(UUID holder){var rows=jdbc.queryForList("SELECT state_id FROM eatwhat_cluster.control_process WHERE holder_id=? AND cluster_id=?",holder,lease.clusterId());return rows.size()==1&&stateId.equals(rows.getFirst().get("state_id"));}
    String deploymentFenceReason(){return deployment.idleFenceReason();}
    public <T> T operation(Supplier<T> action){operations.incrementAndGet();try{lease.requireAccepting();return action.get();}finally{operations.decrementAndGet();}}
    void heartbeat(){
        jdbc.update("""
            INSERT INTO eatwhat_cluster.control_process(holder_id,cluster_id,name,state_id,lease_seconds,expires_at,deployment_protocol)
            VALUES(?,?,?,?,?,clock_timestamp()+interval '5 seconds',1)
            ON CONFLICT(holder_id) DO UPDATE SET expires_at=excluded.expires_at,deployment_protocol=excluded.deployment_protocol
            """,lease.holderId(),lease.clusterId(),name,stateId,lease.durationSeconds());
    }
    public boolean pending(){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.control_handoff WHERE cluster_id=? AND state IN ('WAITING','TRANSFERRING'))",Boolean.class,lease.clusterId());}
    public Map<String,Object> view(){
        var controllers=jdbc.queryForList("SELECT holder_id,name,state_id,expires_at FROM eatwhat_cluster.control_process WHERE cluster_id=? AND expires_at>clock_timestamp() ORDER BY name,holder_id",lease.clusterId()).stream().map(row->Map.<String,Object>of("id",row.get("holder_id").toString(),"name",row.get("name"),"sharedState",stateId.equals(row.get("state_id")))).toList();
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.control_handoff WHERE cluster_id=? ORDER BY requested_at DESC,request_id DESC LIMIT 1",lease.clusterId());
        Object current=rows.isEmpty()?Map.of("state","NONE"):json(rows.getFirst());
        if(independentQueue!=null){var queued=independentQueue.pending();if(queued.isPresent())current=queued.get();}
        return Map.of("handoffEnabled",true,"controllers",controllers,"handoff",current);
    }
    public Map<String,Object> get(UUID id){
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.control_handoff WHERE request_id=? AND cluster_id=?",id,lease.clusterId());
        if(rows.isEmpty()){if(independentQueue!=null){var queued=independentQueue.get(id);if(queued.isPresent())return queued.get();}throw new ClusterFailure(404,"CONTROL_HANDOFF_NOT_FOUND");}return json(rows.getFirst());
    }
    public Map<String,Object> request(String actor,UUID id,long generation,UUID target){
        if(independentQueue!=null&&independentQueue.standby())return independentQueue.request(actor,id,generation,target);
        return transaction(()->{
        var current=lockLease();
        var prior=jdbc.queryForList("SELECT * FROM eatwhat_cluster.control_handoff WHERE request_id=?",id);
        if(!prior.isEmpty()){
            var saved=prior.getFirst();
            if(!lease.clusterId().equals(saved.get("cluster_id"))||!target.equals(saved.get("target_holder"))||generation!=number(saved,"expected_generation"))throw new ClusterFailure(409,"IDEMPOTENCY_CONFLICT");
            return json(saved);
        }
        if(!Boolean.TRUE.equals(current.get("live"))||generation!=number(current,"generation"))throw new ClusterFailure(409,"CONTROL_GENERATION_CHANGED");
        if(target.equals(current.get("holder_id")))throw new ClusterFailure(409,"CONTROL_TARGET_ALREADY_LEADER");
        if(pending())throw new ClusterFailure(409,"CONTROL_HANDOFF_PENDING");
        var source=process((UUID)current.get("holder_id"));var destination=process(target);
        if(source==null||destination==null)throw new ClusterFailure(409,"CONTROL_TARGET_OFFLINE");
        if(!stateId.equals(source.get("state_id"))||!stateId.equals(destination.get("state_id")))throw new ClusterFailure(409,"CONTROL_SHARED_STATE_REQUIRED");
        jdbc.update("INSERT INTO eatwhat_cluster.control_handoff(request_id,cluster_id,source_holder,target_holder,expected_generation,state,actor) VALUES(?,?,?,?,?,'WAITING',?)",id,lease.clusterId(),current.get("holder_id"),target,generation,actor);
        audit(id,actor,"CONTROL_HANDOFF_REQUEST");return get(id);
    });}
    public Map<String,Object> cancel(String actor,UUID id){
        if(independentQueue!=null&&jdbc.queryForObject("SELECT count(*)FROM eatwhat_cluster.control_handoff WHERE request_id=?",Long.class,id)==0){var queued=independentQueue.cancel(actor,id);if(queued.isPresent())return queued.get();}
        return transaction(()->{
        lockLease();var row=lockRequest(id);
        if(row.get("state").equals("CANCELLED"))return json(row);
        if(!row.get("state").equals("WAITING"))throw new ClusterFailure(409,"CONTROL_HANDOFF_NOT_CANCELLABLE");
        jdbc.update("UPDATE eatwhat_cluster.control_handoff SET state='CANCELLED',reason='ADMIN_CANCELLED',finished_at=clock_timestamp() WHERE request_id=?",id);
        audit(id,actor,"CONTROL_HANDOFF_CANCEL");return get(id);
    });}
    void progress(){
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.control_handoff WHERE cluster_id=? AND state IN ('WAITING','TRANSFERRING')",lease.clusterId());
        if(rows.isEmpty())return;var row=rows.getFirst();UUID id=(UUID)row.get("request_id");
        if(!lease.holderId().equals(row.get("source_holder"))||!row.get("state").equals("WAITING")){
            transaction(()->{var current=lockLease();var request=lockRequest(id);settle(current,request);return null;});return;
        }
        if(!runtimeIdle.getAsBoolean()||operations.get()>0){reason(id,"CONTROL_CYCLE_RUNNING");return;}
        String blocked=deployment.whenIdle(()->transaction(()->{
            var current=lockLease();var request=lockRequest(id);
            if(settle(current,request)||!request.get("state").equals("WAITING"))return null;
            var target=process((UUID)request.get("target_holder"));
            if(target==null){reason(id,"CONTROL_TARGET_OFFLINE");return null;}
            if(!stateId.equals(target.get("state_id"))){reason(id,"CONTROL_SHARED_STATE_REQUIRED");return null;}
            jdbc.update("UPDATE eatwhat_cluster.control_lease SET holder_id=?,generation=generation+1,expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp() WHERE singleton AND cluster_id=?",target.get("holder_id"),target.get("lease_seconds"),lease.clusterId());
            jdbc.update("UPDATE eatwhat_cluster.control_handoff SET state='TRANSFERRING',reason='WAITING_FOR_TARGET_ACK' WHERE request_id=?",id);
            audit(id,(String)request.get("actor"),"CONTROL_HANDOFF_RELEASE");return null;
        }));
        if(blocked!=null)reason(id,blocked);
    }
    /** Must hold the lease row before the request row, including cancellation and replay. */
    private boolean settle(Map<String,Object> current,Map<String,Object> request){
        String state=(String)request.get("state");if(!Set.of("WAITING","TRANSFERRING").contains(state))return true;
        UUID id=(UUID)request.get("request_id");long expected=number(request,"expected_generation");
        if(state.equals("WAITING")){
            if(!request.get("source_holder").equals(current.get("holder_id"))||expected!=number(current,"generation")||!Boolean.TRUE.equals(current.get("live"))){finish(id,request,"FAILED","SOURCE_LEASE_CHANGED");return true;}
            return false;
        }
        if(!request.get("target_holder").equals(current.get("holder_id"))||number(current,"generation")!=expected+1){finish(id,request,"FAILED","TARGET_TAKEOVER_NOT_CONFIRMED");return true;}
        if(lease.holderId().equals(current.get("holder_id"))&&Boolean.TRUE.equals(current.get("live"))){lease.requireHeld();finish(id,request,"COMPLETED","TARGET_ACKNOWLEDGED");}
        return true;
    }
    private void finish(UUID id,Map<String,Object> request,String state,String reason){
        jdbc.update("UPDATE eatwhat_cluster.control_handoff SET state=?,reason=?,finished_at=clock_timestamp() WHERE request_id=?",state,reason,id);
        audit(id,(String)request.get("actor"),state.equals("COMPLETED")?"CONTROL_HANDOFF_COMPLETE":"CONTROL_HANDOFF_FAILED");
    }
    private void reason(UUID id,String reason){jdbc.update("UPDATE eatwhat_cluster.control_handoff SET reason=? WHERE request_id=? AND state='WAITING' AND reason IS DISTINCT FROM ?",reason,id,reason);}
    private Map<String,Object> process(UUID holder){var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.control_process WHERE holder_id=? AND cluster_id=? AND expires_at>clock_timestamp()",holder,lease.clusterId());return rows.isEmpty()?null:rows.getFirst();}
    private Map<String,Object> lockLease(){var rows=jdbc.queryForList("SELECT holder_id,generation,expires_at>clock_timestamp() AS live FROM eatwhat_cluster.control_lease WHERE singleton AND cluster_id=? FOR UPDATE",lease.clusterId());if(rows.isEmpty())throw new ClusterFailure(409,"CONTROL_LEASE_UNAVAILABLE");return rows.getFirst();}
    private Map<String,Object> lockRequest(UUID id){var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.control_handoff WHERE request_id=? AND cluster_id=? FOR UPDATE",id,lease.clusterId());if(rows.isEmpty())throw new ClusterFailure(404,"CONTROL_HANDOFF_NOT_FOUND");return rows.getFirst();}
    private <T> T transaction(Supplier<T> action){return ClusterBarrier.internal(()->tx.required(action));}
    private void audit(UUID id,String actor,String action){jdbc.update("INSERT INTO eatwhat_cluster.audit(request_id,actor,action,epoch) SELECT ?,?,?,epoch FROM eatwhat_cluster.state WHERE singleton",id,actor,action);}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private Map<String,Object> json(Map<String,Object> row){return Map.of("id",row.get("request_id").toString(),"sourceControllerId",row.get("source_holder").toString(),"targetControllerId",row.get("target_holder").toString(),"expectedGeneration",row.get("expected_generation"),"state",row.get("state"),"reason",row.get("reason"),"requestedAt",row.get("requested_at").toString(),"finishedAt",Objects.toString(row.get("finished_at"),""));}
}
