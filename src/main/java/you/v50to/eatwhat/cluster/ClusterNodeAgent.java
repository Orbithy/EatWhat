package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.SyncTransactions;
import java.util.*;
import static you.v50to.eatwhat.cluster.ClusterStore.*;
import you.v50to.eatwhat.cluster.ClusterMembershipCoordinator.Plan;

/** A durable local write fence is released only after all nodes installed the coordinator's committed epoch. */
public final class ClusterNodeAgent {
    public interface DataPlane {
        boolean ready();
        default String mode(){return "MULTI_SITE";}
        default Map<String,Set<Integer>> eventSchemas(){return you.v50to.eatwhat.sync.transport.EventSchemas.legacy();}
        void verifyCatchup(Plan plan,ClusterMember local);
    }
    private final JdbcTemplate jdbc;private final ClusterTransactions tx;private final UUID clusterId;private final ClusterMember local;private final DataPlane data;
    public ClusterNodeAgent(JdbcTemplate jdbc,SyncTransactions tx,UUID clusterId,ClusterMember local,DataPlane data){this.jdbc=jdbc;this.tx=new ClusterTransactions(tx);this.clusterId=clusterId;this.local=local;this.data=data;tx.requireSameDataSource(jdbc);}
    public Map<String,Object> probe(){
        var allocation=jdbc.queryForList("SELECT cluster_id,site_id,instance_id,slot FROM eatwhat_identity.allocation WHERE singleton");
        if(allocation.size()!=1||!clusterId.equals(allocation.getFirst().get("cluster_id"))||!local.siteId().equals(allocation.getFirst().get("site_id"))||!local.instanceId().equals(allocation.getFirst().get("instance_id"))||local.allocationSlot()!=((Number)allocation.getFirst().get("slot")).intValue())throw failure("CLUSTER_ALLOCATION_NOT_BOUND");
        String fp=jdbc.queryForObject("SELECT md5(cluster_identifier||':'||database_oid::text) FROM eatwhat_identity.node_state WHERE singleton",String.class);
        var result=new LinkedHashMap<String,Object>(Map.of("siteId",local.siteId(),"instanceId",local.instanceId(),"clusterId",clusterId,"allocationSlot",local.allocationSlot(),"mode",local.mode(),"databaseFingerprint",fp,"protocolVersion",1,"dataPlaneReady",data.ready()));
        result.put("dataPlaneMode",data.mode());
        result.put("eventSchemas",data.eventSchemas());
        var state=jdbc.queryForList("SELECT epoch,state,pending_change FROM eatwhat_cluster.local_state WHERE singleton");
        result.put("membership",state.isEmpty()?Map.of("epoch",0,"state","UNJOINED"):state.getFirst());
        result.put("drain",jdbc.queryForMap("""
          SELECT (SELECT count(*) FROM eatwhat_file.file_reference_live) AS "liveReferences",
            (SELECT count(*) FROM eatwhat_file.file_replica WHERE site='LOCAL' AND state<>'DELETED') AS "localObjects",
            (SELECT count(*) FROM eatwhat_file.upload_intent WHERE status NOT IN ('COMPLETE','EXPIRED','REJECTED')) AS "pendingUploads",
            (SELECT count(*) FROM eatwhat_consent.subject WHERE origin_site=? AND (NOT source_archived OR withdrawal_request_id IS NOT NULL)) AS "unarchivedAccounts"
          """,local.siteId()));return result;
    }
    private void validate(Plan plan){
        if(!clusterId.equals(plan.clusterId())||!plan.participants().contains(local)||plan.nextEpoch()!=plan.baseEpoch()+1||!Set.of("JOIN","RETIRE").contains(plan.kind())||plan.participants().size()>16||plan.participants().isEmpty())throw failure("MEMBERSHIP_PLAN_MISMATCH");
        var expected=new ArrayList<>(plan.participants());if(plan.kind().equals("RETIRE"))expected.removeIf(m->m.siteId().equals(plan.siteId()));
        if(!expected.equals(plan.nextMembers())||plan.participants().stream().map(ClusterMember::siteId).distinct().count()!=plan.participants().size()||plan.participants().stream().map(ClusterMember::instanceId).distinct().count()!=plan.participants().size())throw failure("MEMBERSHIP_PLAN_MISMATCH");
        probe();if(!data.ready())throw failure("CLUSTER_DATA_PLANE_NOT_READY");
    }
    public String stage(Plan plan){validate(plan);return tx.required(()->{
        jdbc.query("SELECT pg_advisory_xact_lock(1161909335,36)",rs->{});
        var saved=jdbc.queryForList("SELECT plan_hash FROM eatwhat_cluster.local_change WHERE change_id=?",plan.changeId());
        if(!saved.isEmpty()){if(!hash(plan).equals(saved.getFirst().get("plan_hash")))throw failure("MEMBERSHIP_PLAN_CHANGED");return phaseProof(plan,"STAGED");}
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.local_state WHERE singleton FOR UPDATE");
        boolean candidate=plan.kind().equals("JOIN")&&plan.siteId().equals(local.siteId());
        if(rows.isEmpty()){
            if(!candidate)throw failure("LOCAL_CLUSTER_NOT_JOINED");
            var previous=plan.participants().stream().filter(m->!m.equals(local)).toList();
            jdbc.update("INSERT INTO eatwhat_cluster.local_state(singleton,cluster_id,epoch,site_id,instance_id,state,members,pending_change) VALUES(true,?,?,?,?,'JOINING',?::jsonb,?)",clusterId,plan.baseEpoch(),local.siteId(),local.instanceId(),json(previous),plan.changeId());
        }else{
            var row=rows.getFirst();if(!clusterId.equals(row.get("cluster_id"))||!local.siteId().equals(row.get("site_id"))||!local.instanceId().equals(row.get("instance_id"))||((Number)row.get("epoch")).longValue()!=plan.baseEpoch()||row.get("pending_change")!=null||!row.get("state").equals("ACTIVE"))throw failure("LOCAL_MEMBERSHIP_NOT_CURRENT");
            var expected=plan.kind().equals("JOIN")?plan.participants().stream().filter(m->!m.siteId().equals(plan.siteId())).toList():plan.participants();
            if(!new HashSet<>(parseMembers(row.get("members").toString())).equals(new HashSet<>(expected)))throw failure("MEMBERSHIP_PARTICIPANTS_CHANGED");
        }
        jdbc.update("INSERT INTO eatwhat_cluster.local_change(change_id,plan,plan_hash) VALUES(?,?::jsonb,?)",plan.changeId(),json(plan),hash(plan));
        long cutoff=jdbc.queryForObject("SELECT coalesce(max(local_order),0) FROM eatwhat_sync.outbox",Long.class);
        jdbc.update("INSERT INTO eatwhat_cluster.replay_cursor(change_id,source_site,source_instance,cutoff,account_complete) VALUES(?,?,?,?,?)",plan.changeId(),local.siteId(),local.instanceId(),cutoff,local.allocationSlot()!=0);
        if(candidate)recordPhase(plan,"FENCED");return recordPhase(plan,"STAGED");
    });}
    public String fence(Plan plan){validate(plan);return tx.required(()->{
        jdbc.query("SELECT pg_advisory_xact_lock(1161909335,36)",rs->{});
        var old=jdbc.queryForList("SELECT plan_hash FROM eatwhat_cluster.local_change WHERE change_id=?",plan.changeId());
        if(!old.isEmpty()){if(!hash(plan).equals(old.getFirst().get("plan_hash")))throw failure("MEMBERSHIP_PLAN_CHANGED");if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.local_phase WHERE change_id=? AND phase='FENCED'",Long.class,plan.changeId())>0)return phaseProof(plan,"FENCED");}
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.local_state WHERE singleton FOR UPDATE");
        if(rows.isEmpty()){
            if(!plan.kind().equals("JOIN")||!plan.siteId().equals(local.siteId()))throw failure("LOCAL_CLUSTER_NOT_JOINED");
            var previous=plan.participants().stream().filter(m->!m.equals(local)).toList();
            jdbc.update("INSERT INTO eatwhat_cluster.local_state(singleton,cluster_id,epoch,site_id,instance_id,state,members,pending_change) VALUES(true,?,?,?,?,'JOINING',?::jsonb,?)",clusterId,plan.baseEpoch(),local.siteId(),local.instanceId(),json(previous),plan.changeId());
        }else{
            var row=rows.getFirst();if(!clusterId.equals(row.get("cluster_id"))||!local.siteId().equals(row.get("site_id"))||!local.instanceId().equals(row.get("instance_id"))||((Number)row.get("epoch")).longValue()!=plan.baseEpoch()||row.get("pending_change")!=null||!row.get("state").equals("ACTIVE"))throw failure("LOCAL_MEMBERSHIP_NOT_CURRENT");
            var expected=plan.kind().equals("JOIN")?plan.participants().stream().filter(m->!m.siteId().equals(plan.siteId())).toList():plan.participants();
            if(!new HashSet<>(parseMembers(row.get("members").toString())).equals(new HashSet<>(expected)))throw failure("MEMBERSHIP_PARTICIPANTS_CHANGED");
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_consent.subject WHERE origin_site=? AND (NOT source_archived OR withdrawal_request_id IS NOT NULL)",Long.class,local.siteId())>0)throw failure("LOCAL_ACCOUNT_ARCHIVE_PENDING");
            if(plan.kind().equals("RETIRE")&&plan.siteId().equals(local.siteId())){
                if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live",Long.class)>0)throw failure("RETIREMENT_REFERENCES_PENDING");
                if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE site='LOCAL' AND state<>'DELETED'",Long.class)>0)throw failure("RETIREMENT_OBJECTS_PENDING");
                if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.upload_intent WHERE status NOT IN ('COMPLETE','EXPIRED','REJECTED')",Long.class)>0)throw failure("RETIREMENT_UPLOADS_PENDING");
            }
            jdbc.update("UPDATE eatwhat_cluster.local_state SET pending_change=?,state=? WHERE singleton",plan.changeId(),plan.kind().equals("RETIRE")&&plan.siteId().equals(local.siteId())?"DRAINING":"ACTIVE");
        }
        jdbc.update("INSERT INTO eatwhat_cluster.local_change(change_id,plan,plan_hash) VALUES(?,?::jsonb,?) ON CONFLICT DO NOTHING",plan.changeId(),json(plan),hash(plan));return recordPhase(plan,"FENCED");
    });}
    public String prepared(Plan plan){validate(plan);verifySaved(plan);data.verifyCatchup(plan,local);return tx.required(()->{
        var row=locked(plan);if(((Number)row.get("epoch")).longValue()!=plan.baseEpoch())throw failure("LOCAL_MEMBERSHIP_NOT_CURRENT");
        if(plan.kind().equals("RETIRE")&&plan.siteId().equals(local.siteId())){
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live",Long.class)>0)throw failure("RETIREMENT_REFERENCES_PENDING");
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE site='LOCAL' AND state<>'DELETED'",Long.class)>0)throw failure("RETIREMENT_OBJECTS_PENDING");
        }
        if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_transfer_job WHERE status<>'DONE'",Long.class)>0||jdbc.queryForObject("SELECT count(*) FROM eatwhat_sync.inbox WHERE status<>'APPLIED'",Long.class)>0)throw failure("MEMBERSHIP_DRAIN_PENDING");
        return recordPhase(plan,"PREPARED");
    });}
    public String apply(Plan plan){validate(plan);verifySaved(plan);return tx.required(()->{
        var row=locked(plan);if(((Number)row.get("epoch")).longValue()==plan.nextEpoch())return phaseProof(plan,"APPLIED");
        phaseProof(plan,"PREPARED");if(((Number)row.get("epoch")).longValue()!=plan.baseEpoch())throw failure("LOCAL_MEMBERSHIP_NOT_CURRENT");
        jdbc.update("UPDATE eatwhat_cluster.local_state SET epoch=?,state=?,members=?::jsonb WHERE singleton",plan.nextEpoch(),plan.nextMembers().contains(local)?"ACTIVE":"RETIRED",json(plan.nextMembers()));return recordPhase(plan,"APPLIED");
    });}
    public String release(Plan plan){validate(plan);verifySaved(plan);return tx.required(()->{
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.local_state WHERE singleton FOR UPDATE");var row=rows.getFirst();
        if(((Number)row.get("epoch")).longValue()!=plan.nextEpoch())throw failure("LOCAL_MEMBERSHIP_NOT_CURRENT");
        if(row.get("pending_change")==null)return phaseProof(plan,"RELEASED");
        if(!plan.changeId().equals(row.get("pending_change")))throw failure("MEMBERSHIP_CHANGE_PENDING");phaseProof(plan,"APPLIED");
        jdbc.update("UPDATE eatwhat_cluster.local_state SET pending_change=NULL WHERE singleton");return recordPhase(plan,"RELEASED");
    });}
    private Map<String,Object> locked(Plan plan){var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.local_state WHERE singleton FOR UPDATE");if(rows.size()!=1||!plan.changeId().equals(rows.getFirst().get("pending_change")))throw failure("LOCAL_MEMBERSHIP_NOT_PREPARED");return rows.getFirst();}
    private void verifySaved(Plan plan){var rows=jdbc.queryForList("SELECT plan_hash FROM eatwhat_cluster.local_change WHERE change_id=?",plan.changeId());if(rows.size()!=1||!hash(plan).equals(rows.getFirst().get("plan_hash")))throw failure("MEMBERSHIP_PLAN_CHANGED");}
    private String recordPhase(Plan plan,String phase){String proof=hash(List.of(plan,local,phase));jdbc.update("INSERT INTO eatwhat_cluster.local_phase(change_id,phase,proof_hash) VALUES(?,?,?) ON CONFLICT DO NOTHING",plan.changeId(),phase,proof);return phaseProof(plan,phase);}
    private String phaseProof(Plan plan,String phase){var rows=jdbc.queryForList("SELECT proof_hash FROM eatwhat_cluster.local_phase WHERE change_id=? AND phase=?",String.class,plan.changeId(),phase);if(rows.size()!=1)throw failure("MEMBERSHIP_PHASE_NOT_READY");return rows.getFirst();}
}
