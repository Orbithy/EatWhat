package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.FileGcProperties;
import you.v50to.eatwhat.sync.SyncTransactions;
import java.time.Duration;
import java.util.*;
import static you.v50to.eatwhat.cluster.ClusterStore.*;

/** Votes only after local database facts are checked under the same revision lock as every reference acquisition. */
public final class ClusterGcParticipant {
    private final JdbcTemplate jdbc;private final ClusterTransactions tx;private final ClusterMember local;private final FileGcProperties settings;
    public ClusterGcParticipant(JdbcTemplate jdbc,SyncTransactions tx,ClusterMember local,FileGcProperties settings){this.jdbc=jdbc;this.tx=new ClusterTransactions(tx);this.local=local;this.settings=settings;tx.requireSameDataSource(jdbc);}
    public String prepare(ClusterGcStore.Decision d){return tx.required(()->{
        checkMembership(d);lock(d);
        var old=jdbc.queryForList("SELECT operation_id FROM eatwhat_cluster.gc_fence WHERE file_id=? AND revision=?",d.fileId(),d.revision());
        if(!old.isEmpty()){checkFence(d);return "PREPARED";}
        if(!settings.isEnabled()||!settings.isExecuteEnabled()||settings.isDryRun()||!settings.isCoordinatedEnabled()
                ||!FileGcProperties.isAckUsable(settings.getExecuteAck())||!settings.getExecuteClasses().contains("UNREFERENCED_REVISION"))throw failure("GC_EXECUTION_NOT_AUTHORIZED");
        Duration retention=settings.getRetention();if(retention==null||retention.compareTo(Duration.ofDays(7))<0)throw failure("INVALID_GC_RETENTION");
        if(count("SELECT count(*) FROM eatwhat_file.file_revision WHERE file_id=? AND revision=?",d)==0){
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_sync.inbox WHERE status<>'APPLIED'",Long.class)>0)throw failure("GC_INBOX_PENDING");
            jdbc.update("INSERT INTO eatwhat_cluster.gc_fence(file_id,revision,operation_id,epoch,sha256,size_bytes,participants,state,absent) VALUES(?,?,?,?,?,?,?::jsonb,'PREPARED',true)",d.fileId(),d.revision(),d.operationId(),d.epoch(),d.sha256(),d.sizeBytes(),json(d.participants()));return "PREPARED";
        }
        var rows=jdbc.queryForList("""
            SELECT r.sha256,r.size_bytes,t.unreferenced_since,t.had_public_ref,
              x.state AS replica_state,x.verified_sha256,x.verified_size
            FROM eatwhat_file.file_revision r JOIN eatwhat_file.file_tombstone t USING(file_id,revision)
            JOIN eatwhat_file.file_replica x USING(file_id,revision)
            WHERE r.file_id=? AND r.revision=? AND r.state='VERIFIED' AND x.site='LOCAL'
              AND t.unreferenced_since<=clock_timestamp()-(? * interval '1 millisecond')
            """,d.fileId(),d.revision(),retention.toMillis());
        if(rows.size()!=1)throw failure("GC_LOCAL_RETENTION_OR_REPLICA_PENDING");var r=rows.getFirst();
        if(!d.sha256().equals(((String)r.get("sha256")).trim())||d.sizeBytes()!=((Number)r.get("size_bytes")).longValue()
            ||!"AVAILABLE_LOCAL".equals(r.get("replica_state"))||!d.sha256().equals(r.get("verified_sha256"))
            ||!(r.get("verified_size") instanceof Number size)||size.longValue()!=d.sizeBytes())throw failure("GC_LOCAL_REVISION_UNPROVEN");
        if(count("SELECT count(*) FROM eatwhat_file.file_reference_live f JOIN eatwhat_file.file_key_alias a USING(logical_key) WHERE a.file_id=? AND a.revision=?",d)>0)throw failure("GC_LIVE_REFERENCE");
        var aliases=jdbc.queryForList("SELECT logical_key FROM eatwhat_file.file_key_alias WHERE file_id=? AND revision=?",String.class,d.fileId(),d.revision());
        if(aliases.isEmpty())throw failure("GC_ALIAS_PROOF_MISSING");
        for(String key:aliases){
            if(jdbc.queryForObject("SELECT count(*) FROM users WHERE avatar=?",Long.class,key)>0)throw failure("GC_BUSINESS_REFERENCE");
            for(String table:List.of("restaurant","foods","activity_foods","activity_dinners","activity_posts"))if(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE ?=ANY(picture_url)"+(table.startsWith("activity_")?" AND deleted_at IS NULL":""),Long.class,key)>0)throw failure("GC_BUSINESS_REFERENCE");
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_profile_avatar_keys WHERE logical_key=?",Long.class,key)>0)throw failure("GC_PROFILE_REFERENCE");
        }
        if(count("SELECT count(*) FROM eatwhat_file.file_transfer_job WHERE file_id=? AND revision=? AND status<>'DONE'",d)>0)throw failure("GC_TRANSFERS_PENDING");
        if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_sync.inbox WHERE status<>'APPLIED'",Long.class)>0)throw failure("GC_INBOX_PENDING");
        if(count("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND revision=? AND status<>'DONE'",d)>0)throw failure("GC_CDN_PENDING");
        if(count("SELECT count(*) FROM eatwhat_file.file_gc_coordination WHERE file_id=? AND revision=?",d)>0)throw failure("LEGACY_GC_DECISION_RETAINED");
        jdbc.update("INSERT INTO eatwhat_cluster.gc_fence(file_id,revision,operation_id,epoch,sha256,size_bytes,participants,state) VALUES(?,?,?,?,?,?,?::jsonb,'PREPARED')",d.fileId(),d.revision(),d.operationId(),d.epoch(),d.sha256(),d.sizeBytes(),json(d.participants()));return "PREPARED";
    });}
    public String commit(ClusterGcStore.Decision d){return tx.required(()->{checkMembership(d);lock(d);var fence=checkFence(d);if(fence.get("state").equals("PREPARED"))jdbc.update("UPDATE eatwhat_cluster.gc_fence SET state='COMMITTED' WHERE file_id=? AND revision=?",d.fileId(),d.revision());return "COMMITTED";});}
    public String deleted(ClusterGcStore.Decision d){return tx.required(()->{checkMembership(d);lock(d);var fence=checkFence(d);if(Boolean.TRUE.equals(fence.get("absent"))&&fence.get("state").equals("COMMITTED")){if(count("SELECT count(*) FROM eatwhat_file.file_revision WHERE file_id=? AND revision=?",d)>0)throw failure("GC_ABSENCE_CHANGED");jdbc.update("UPDATE eatwhat_cluster.gc_fence SET state='DELETED' WHERE file_id=? AND revision=?",d.fileId(),d.revision());}return jdbc.queryForObject("SELECT state FROM eatwhat_cluster.gc_fence WHERE file_id=? AND revision=?",String.class,d.fileId(),d.revision());});}
    private void checkMembership(ClusterGcStore.Decision d){
        if(!d.participants().contains(local))throw failure("UNEXPECTED_GC_PARTICIPANT");
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.local_state WHERE singleton FOR SHARE");
        if(rows.size()!=1||!local.siteId().equals(rows.getFirst().get("site_id"))||!local.instanceId().equals(rows.getFirst().get("instance_id"))
                ||((Number)rows.getFirst().get("epoch")).longValue()!=d.epoch()||rows.getFirst().get("pending_change")!=null||!rows.getFirst().get("state").equals("ACTIVE"))throw failure("GC_MEMBERSHIP_NOT_CURRENT");
        var members=parseMembers(rows.getFirst().get("members").toString());
        if(!new HashSet<>(members).containsAll(d.participants()))throw failure("GC_PARTICIPANT_SET_MISMATCH");
    }
    private void lock(ClusterGcStore.Decision d){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended('file-io:'||?::text||':'||?::text,0))",Boolean.class,d.fileId().toString(),Integer.toString(d.revision()))))throw failure("GC_FILE_IO_PENDING");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text||':'||?::text,0))",rs->{},d.fileId().toString(),Integer.toString(d.revision()));
    }
    private Map<String,Object> checkFence(ClusterGcStore.Decision d){var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.gc_fence WHERE file_id=? AND revision=?",d.fileId(),d.revision());if(rows.size()!=1)throw failure("GC_PREPARE_REQUIRED");var r=rows.getFirst();
        if(!d.operationId().equals(r.get("operation_id"))||d.epoch()!=((Number)r.get("epoch")).longValue()||!d.sha256().equals(((String)r.get("sha256")).trim())||d.sizeBytes()!=((Number)r.get("size_bytes")).longValue()||!new HashSet<>(d.participants()).equals(new HashSet<>(parseMembers(r.get("participants").toString()))))throw failure("GC_DECISION_MISMATCH");return r;}
    private long count(String sql,ClusterGcStore.Decision d){return jdbc.queryForObject(sql,Long.class,d.fileId(),d.revision());}
}
