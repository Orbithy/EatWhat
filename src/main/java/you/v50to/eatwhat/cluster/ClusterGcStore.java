package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.SyncTransactions;
import java.util.*;
import static you.v50to.eatwhat.cluster.ClusterStore.*;

/** Every participant is frozen at creation. Missing, replaced or retired participants never become implicit YES. */
public final class ClusterGcStore {
    public record Decision(UUID operationId,UUID fileId,int revision,long epoch,String sha256,long sizeBytes,List<ClusterMember> participants){
        public Decision {
            Objects.requireNonNull(operationId);Objects.requireNonNull(fileId);participants=List.copyOf(participants);
            if(revision<1||epoch<1||sha256==null||!sha256.matches("[0-9a-f]{64}")||sizeBytes<1||participants.isEmpty()||participants.size()>16
                    ||participants.stream().map(ClusterMember::siteId).distinct().count()!=participants.size()
                    ||participants.stream().map(ClusterMember::instanceId).distinct().count()!=participants.size())throw new ClusterFailure(400,"INVALID_GC_DECISION");
        }
    }
    private final JdbcTemplate jdbc;private final ClusterTransactions tx;private final ClusterStore membership;
    public ClusterGcStore(JdbcTemplate jdbc,SyncTransactions tx,ClusterStore membership){this.jdbc=jdbc;this.tx=new ClusterTransactions(tx,membership.lease(),jdbc);this.membership=membership;tx.requireSameDataSource(jdbc);}
    public Decision begin(UUID operation,UUID file,int revision,long expectedEpoch){return begin("cluster-worker",operation,file,revision,expectedEpoch);}
    public Decision begin(String actor,UUID operation,UUID file,int revision,long expectedEpoch){return tx.required(()->{
        var state=membership.lock();long epoch=((Number)state.get("epoch")).longValue();
        var prior=jdbc.queryForList("SELECT operation_id FROM eatwhat_cluster.gc_operation WHERE operation_id=?",operation);
        if(!prior.isEmpty()){var d=get(operation);if(!d.fileId().equals(file)||d.revision()!=revision||d.epoch()!=expectedEpoch)throw failure("IDEMPOTENCY_CONFLICT");return d;}
        if(epoch!=expectedEpoch)throw failure("STALE_MEMBERSHIP_EPOCH");
        if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.member WHERE allocation_slot=1 AND joined_epoch IS NOT NULL",Long.class)!=1)throw failure("BOOTSTRAP_PAIR_REQUIRED");
        if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.change WHERE state<>'APPLIED'",Long.class)>0)throw failure("MEMBERSHIP_CHANGE_PENDING");
        var participants=membership.active();if(participants.isEmpty())throw failure("NO_ACTIVE_MEMBERS");
        var revisionRows=jdbc.queryForList("SELECT sha256,size_bytes,owner_gid FROM eatwhat_file.file_revision JOIN eatwhat_file.file_object USING(file_id) WHERE file_id=? AND revision=? AND state='VERIFIED'",file,revision);
        if(revisionRows.size()!=1)throw failure("VERIFIED_REVISION_REQUIRED");var r=revisionRows.getFirst();
        var consent=new ClusterRecipientConsent(jdbc);UUID owner=(UUID)r.get("owner_gid");
        if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_consent.subject WHERE subject_id=?",Long.class,owner)==1)participants=participants.stream().filter(m->consent.historicallyAllowed(owner,m)).toList();
        if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.gc_operation WHERE file_id=? AND revision=?",Long.class,file,revision)>0)throw failure("GC_REVISION_ALREADY_DECIDED");
        var decision=new Decision(operation,file,revision,epoch,((String)r.get("sha256")).trim(),((Number)r.get("size_bytes")).longValue(),participants);
        jdbc.update("INSERT INTO eatwhat_cluster.gc_operation(operation_id,file_id,revision,epoch,sha256,size_bytes,participants,state) VALUES(?,?,?,?,?,?,?::jsonb,'PREPARING')",operation,file,revision,epoch,decision.sha256(),decision.sizeBytes(),json(participants));
        jdbc.update("INSERT INTO eatwhat_cluster.audit(request_id,actor,action,epoch) VALUES(?,?,'GC_PREPARING',?)",operation,actor,epoch);return decision;
    });}
    public Decision get(UUID operation){var rows=jdbc.queryForList("SELECT *,participants::text AS participant_json FROM eatwhat_cluster.gc_operation WHERE operation_id=?",operation);if(rows.isEmpty())throw new ClusterFailure(404,"GC_OPERATION_NOT_FOUND");var r=rows.getFirst();return new Decision(operation,(UUID)r.get("file_id"),((Number)r.get("revision")).intValue(),((Number)r.get("epoch")).longValue(),((String)r.get("sha256")).trim(),((Number)r.get("size_bytes")).longValue(),parseMembers((String)r.get("participant_json")));}
    public String state(UUID id){return jdbc.queryForObject("SELECT state FROM eatwhat_cluster.gc_operation WHERE operation_id=?",String.class,id);}
    public Map<String,Object> snapshot(UUID id){return Map.of("decision",get(id),"state",state(id),"votes",jdbc.queryForList("SELECT site_id,instance_id,phase,created_at FROM eatwhat_cluster.gc_vote WHERE operation_id=? ORDER BY created_at,site_id,phase",id).stream().map(ClusterStore::external).toList());}
    public List<Map<String,Object>> list(int limit,boolean pendingOnly){bounded(limit);return jdbc.queryForList("SELECT operation_id FROM eatwhat_cluster.gc_operation WHERE NOT ? OR state<>'DELETED' ORDER BY created_at DESC LIMIT ?",UUID.class,pendingOnly,limit).stream().map(this::snapshot).toList();}
    public List<Map<String,Object>> candidates(int limit){bounded(limit);return jdbc.queryForList("SELECT file_id AS \"fileId\",revision,state,block_reason AS \"blockedReason\",updated_at AS \"updatedAt\" FROM eatwhat_file.file_gc_candidate WHERE class='UNREFERENCED_REVISION' AND state IN ('ELIGIBLE','BLOCKED','DETECTED') ORDER BY updated_at DESC LIMIT ?",limit).stream().map(ClusterStore::external).toList();}
    void vote(UUID operation,ClusterMember participant,String phase){tx.required(()->{
        membership.lock();var d=get(operation);if(!d.participants().contains(participant)||!Set.of("PREPARED","DELETED").contains(phase))throw failure("UNEXPECTED_GC_PARTICIPANT");
        if(phase.equals("DELETED")&&state(operation).equals("PREPARING"))throw failure("GC_NOT_COMMITTED");
        jdbc.update("INSERT INTO eatwhat_cluster.gc_vote(operation_id,site_id,instance_id,phase) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",operation,participant.siteId(),participant.instanceId(),phase);return null;
    });}
    public String advance(UUID operation){return tx.required(()->{
        membership.lock();var d=get(operation);String state=state(operation);if(state.equals("DELETED"))return state;
        String phase=state.equals("PREPARING")?"PREPARED":"DELETED";
        for(var p:d.participants())if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.gc_vote WHERE operation_id=? AND site_id=? AND instance_id=? AND phase=?",Long.class,operation,p.siteId(),p.instanceId(),phase)!=1)return state;
        String next=state.equals("PREPARING")?"COMMITTED":"DELETED";jdbc.update("UPDATE eatwhat_cluster.gc_operation SET state=? WHERE operation_id=?",next,operation);
        if(next.equals("DELETED"))for(var participant:d.participants())jdbc.update("UPDATE eatwhat_cluster.replica_fact SET state='DELETED' WHERE file_id=? AND revision=? AND site_id=? AND instance_id=?",d.fileId(),d.revision(),participant.siteId(),participant.instanceId());return next;
    });}
}
