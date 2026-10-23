package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import you.v50to.eatwhat.sync.SyncTransactions;
import java.util.*;
import java.util.function.Supplier;

/** DB-clock lease for replicas of the same logical authority site. Never cache a successful fence. */
public final class ClusterControlLease {
    private final JdbcTemplate jdbc;
    private final UUID clusterId;
    private final UUID holderId;
    private final int durationSeconds;
    private final boolean candidate;
    private final boolean automaticTakeover;
    private volatile long generation;
    private ClusterControlHandoff handoff;
    private record Approved(UUID holder,long generation,UUID state,Object transaction){}
    private final ThreadLocal<Approved> approved=new ThreadLocal<>();

    public ClusterControlLease(JdbcTemplate jdbc,UUID clusterId,int durationSeconds){
        this(jdbc,clusterId,durationSeconds,true);
    }
    public ClusterControlLease(JdbcTemplate jdbc,UUID clusterId,int durationSeconds,boolean candidate){
        this(jdbc,clusterId,durationSeconds,candidate,true);
    }
    public ClusterControlLease(JdbcTemplate jdbc,UUID clusterId,int durationSeconds,boolean candidate,boolean automaticTakeover){
        this(jdbc,clusterId,durationSeconds,candidate,automaticTakeover,null);
    }
    /** Independent replicas retain their process identity across restarts. A manual-only
     * former holder must never turn a restart into a claim on its peer's authority. */
    public ClusterControlLease(JdbcTemplate jdbc,UUID clusterId,int durationSeconds,boolean candidate,boolean automaticTakeover,UUID controllerId){
        if(durationSeconds<5||durationSeconds>120)throw new ClusterFailure(503,"CONTROL_LEASE_DURATION_INVALID");
        if(controllerId!=null&&(controllerId.equals(new UUID(0,0))||automaticTakeover))throw new ClusterFailure(503,"PERSISTENT_CONTROL_ID_REQUIRES_MANUAL_HANDOFF");
        this.holderId=controllerId==null?UUID.randomUUID():controllerId;
        this.jdbc=Objects.requireNonNull(jdbc);this.clusterId=Objects.requireNonNull(clusterId);this.durationSeconds=durationSeconds;
        this.candidate=candidate;
        this.automaticTakeover=automaticTakeover;
    }

    /** A standby never steals a live lease. Renewals and takeovers use the same atomic row update. */
    public boolean tick(){
        if(!candidate)return false;
        if(handoff!=null)handoff.heartbeat();
        var rows=jdbc.query("""
            INSERT INTO eatwhat_cluster.control_lease(singleton,cluster_id,holder_id,generation,expires_at)
            SELECT true,?,?,1,clock_timestamp()+(? * interval '1 second')
            WHERE ? OR EXISTS(SELECT 1 FROM eatwhat_cluster.control_lease existing
                WHERE existing.singleton AND existing.cluster_id=? AND existing.holder_id=?)
            ON CONFLICT(singleton) DO UPDATE SET
                holder_id=excluded.holder_id,
                generation=CASE WHEN eatwhat_cluster.control_lease.holder_id=excluded.holder_id
                    AND eatwhat_cluster.control_lease.expires_at>clock_timestamp()
                    THEN eatwhat_cluster.control_lease.generation ELSE eatwhat_cluster.control_lease.generation+1 END,
                expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp()
            WHERE eatwhat_cluster.control_lease.cluster_id=excluded.cluster_id
              AND (eatwhat_cluster.control_lease.holder_id=excluded.holder_id
                   OR (? AND eatwhat_cluster.control_lease.expires_at<=clock_timestamp()))
              AND (? OR (EXISTS(SELECT 1 FROM eatwhat_cluster.control_process own_process
                    WHERE own_process.holder_id=excluded.holder_id AND own_process.cluster_id=excluded.cluster_id AND own_process.state_id=?)
                  AND (eatwhat_cluster.control_lease.holder_id=excluded.holder_id
                    OR EXISTS(SELECT 1 FROM eatwhat_cluster.control_process previous_process
                      WHERE previous_process.holder_id=eatwhat_cluster.control_lease.holder_id
                        AND previous_process.cluster_id=excluded.cluster_id AND previous_process.state_id=?))))
            RETURNING generation
            """,(rs,n)->rs.getLong(1),clusterId,holderId,durationSeconds,automaticTakeover,clusterId,holderId,durationSeconds,automaticTakeover,handoff==null,handoff==null?new UUID(0,0):handoff.stateIdentity(),handoff==null?new UUID(0,0):handoff.stateIdentity());
        generation=rows.isEmpty()?0:rows.getFirst();
        if(handoff!=null)handoff.progress();
        return generation>0;
    }
    UUID holderId(){return holderId;}
    UUID clusterId(){return clusterId;}
    int durationSeconds(){return durationSeconds;}
    void handoff(ClusterControlHandoff handoff){this.handoff=handoff;}
    public boolean draining(){return handoff!=null&&handoff.pending();}
    public void requireAccepting(){requireHeld();if(draining())throw new ClusterFailure(409,"CONTROL_HANDOFF_PENDING");if(handoff!=null){String reason=handoff.deploymentFenceReason();if(reason!=null)throw new ClusterFailure(409,reason);}}

    /** FOR SHARE is retained until commit, so takeover cannot pass an in-flight control write. */
    public void requireHeldInTransaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw new IllegalStateException("Control fence requires its authority transaction");
        var permit=approved.get();if(permit!=null){
            if(permit.transaction()!=TransactionSynchronizationManager.getResource(jdbc.getDataSource()))throw new ClusterFailure(409,"CONTROL_LEASE_NOT_HELD");
            requireApproved(permit.holder(),permit.generation(),permit.state());return;
        }
        if(!held(" FOR SHARE"))throw new ClusterFailure(503,"CONTROL_LEASE_NOT_HELD");
    }
    public void requireHeld(){if(!held(""))throw new ClusterFailure(503,"CONTROL_LEASE_NOT_HELD");}
    private boolean held(String lock){
        long expected=generation;if(expected<1||handoff!=null&&!handoff.sharesStateWith(holderId))return false;
        var rows=jdbc.query("SELECT cluster_id,holder_id,generation,expires_at>clock_timestamp() AS live FROM eatwhat_cluster.control_lease WHERE singleton"+lock,
            (rs,n)->Map.of("cluster",rs.getObject(1,UUID.class),"holder",rs.getObject(2,UUID.class),"generation",rs.getLong(3),"live",rs.getBoolean(4)));
        if(rows.size()!=1)return false;var row=rows.getFirst();
        return clusterId.equals(row.get("cluster"))&&holderId.equals(row.get("holder"))&&expected==((Number)row.get("generation")).longValue()&&Boolean.TRUE.equals(row.get("live"));
    }
    public <T> T write(SyncTransactions tx,Supplier<T> work){tx.requireSameDataSource(jdbc);return ClusterBarrier.internal(()->tx.required(()->{requireHeldInTransaction();return work.get();}));}
    public UUID controllerId(){return holderId;}
    void requireSameDataSource(JdbcTemplate writer){
        if(writer==null||writer.getDataSource()!=jdbc.getDataSource())throw new IllegalArgumentException("Control authority and writer require the same DataSource");
    }
    /** A standby may apply an already approved holder's narrowly scoped local
     * configuration. The caller still verifies the sealed task and admin; this
     * method never elects a leader or grants a different holder/generation.
     * Commit precedes return, so a remote acknowledgement cannot escape an
     * unrelated outer transaction. Lock order matches ordinary lease writes. */
    public <T>T writeUnderApprovedHolder(SyncTransactions tx,UUID expectedHolder,long expectedGeneration,UUID stateId,Supplier<T> work){
        tx.requireSameDataSource(jdbc);
        if(expectedHolder==null||stateId==null||expectedGeneration<1)throw new ClusterFailure(409,"CONTROL_LEASE_NOT_HELD");
        return ClusterBarrier.internal(()->tx.independent(()->{
            requireApproved(expectedHolder,expectedGeneration,stateId);
            if(approved.get()!=null)throw new ClusterFailure(409,"CONTROL_LEASE_NOT_HELD");
            Object transaction=TransactionSynchronizationManager.getResource(jdbc.getDataSource());if(transaction==null)throw new IllegalStateException("Approved control requires its bound transaction");
            approved.set(new Approved(expectedHolder,expectedGeneration,stateId,transaction));
            try{return work.get();}finally{approved.remove();}
        }));
    }
    private void requireApproved(UUID expectedHolder,long expectedGeneration,UUID stateId){
        var rows=jdbc.queryForList("SELECT cluster_id,holder_id,generation,expires_at>clock_timestamp() AS live FROM eatwhat_cluster.control_lease WHERE singleton FOR SHARE");
        if(rows.size()!=1||!clusterId.equals(rows.getFirst().get("cluster_id"))||!expectedHolder.equals(rows.getFirst().get("holder_id"))
                ||expectedGeneration!=((Number)rows.getFirst().get("generation")).longValue()||!Boolean.TRUE.equals(rows.getFirst().get("live")))throw new ClusterFailure(409,"CONTROL_LEASE_NOT_HELD");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.control_process WHERE holder_id=? AND cluster_id=? AND state_id=? AND deployment_protocol=1 AND expires_at>clock_timestamp())",Boolean.class,
                expectedHolder,clusterId,stateId)))throw new ClusterFailure(409,"CONTROL_SHARED_STATE_REQUIRED");
    }
    public Map<String,Object> status(){
        var rows=jdbc.queryForList("SELECT holder_id,generation,expires_at,expires_at>clock_timestamp() AS live FROM eatwhat_cluster.control_lease WHERE singleton AND cluster_id=?",clusterId);
        if(rows.isEmpty())return Map.of("state","UNCLAIMED","localLeader",false,"takeoverMode",automaticTakeover?"AUTOMATIC":"MANUAL_HANDOFF_ONLY");
        var row=rows.getFirst();boolean leader=holderId.equals(row.get("holder_id"))&&generation==((Number)row.get("generation")).longValue()&&Boolean.TRUE.equals(row.get("live"))&&(handoff==null||handoff.sharesStateWith(holderId));
        var result=new LinkedHashMap<String,Object>(Map.of("state",Boolean.TRUE.equals(row.get("live"))?"HELD":"EXPIRED","localLeader",leader,"generation",row.get("generation"),"expiresAt",((java.util.Date)row.get("expires_at")).toInstant().toString(),"takeoverMode",automaticTakeover?"AUTOMATIC":"MANUAL_HANDOFF_ONLY"));
        if(handoff!=null){result.putAll(handoff.view());result.put("localControllerId",holderId.toString());result.put("leaderControllerId",row.get("holder_id").toString());}
        if(handoff!=null&&!Boolean.TRUE.equals(row.get("live"))&&!handoff.sharesStateWith((UUID)row.get("holder_id")))result.put("reason","CONTROL_SHARED_STATE_REQUIRED");
        return result;
    }
    public void release(){
        long held=generation;generation=0;
        if(held>0)jdbc.update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp(),updated_at=clock_timestamp() WHERE singleton AND cluster_id=? AND holder_id=? AND generation=?",clusterId,holderId,held);
    }
}
