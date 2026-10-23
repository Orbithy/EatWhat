package you.v50to.eatwhat.sync.file;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.util.*;

/** Durable, non-expiring prepare fences. Only CAMPUS coordinates; each node deletes only its own LOCAL store. */
public final class FileGcProtocol implements EventHandler {
    private final JdbcTemplate jdbc;
    private final NodeIdentity local,peer;
    private final boolean campus;
    public FileGcProtocol(JdbcTemplate jdbc,NodeIdentity local,NodeIdentity peer,SiteProperties.Mode mode) {
        this.jdbc=jdbc; this.local=local; this.peer=peer; this.campus=mode==SiteProperties.Mode.CAMPUS;
        if(mode!=SiteProperties.Mode.CAMPUS&&mode!=SiteProperties.Mode.EXTERNAL) throw new IllegalArgumentException("GC_SITE_MODE");
    }
    /** Local-only GC must also fence first replication, even before a bilateral operation exists. */
    public static boolean deletionFrozen(JdbcTemplate jdbc,UUID file,int revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_gc_coordination WHERE file_id=? AND revision=? AND state<>'REQUESTED')
                OR EXISTS(SELECT 1 FROM eatwhat_file.file_replica WHERE file_id=? AND revision=? AND state IN ('DELETING','DELETED'))
                OR EXISTS(SELECT 1 FROM eatwhat_cluster.gc_fence WHERE file_id=? AND revision=?)
            """,Boolean.class,file,revision,file,revision,file,revision));
    }
    @Override public String aggregateType() { return FileGcEvents.TYPE; }
    @Override public String eventType() { return FileGcEvents.TYPE; }
    @Override public int schemaVersion() { return 1; }
    @Override public boolean dependenciesSatisfied(SyncEvent e) {
        checked(e);
        var d=FileGcEvents.decision(e.payloadJson());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_revision WHERE file_id=? AND revision=?)",Boolean.class,d.fileId(),d.revision()))) return false;
        if(d.phase().equals("PREPARE")) return true;
        var rows=jdbc.queryForList("SELECT state FROM eatwhat_file.file_gc_coordination WHERE file_id=? AND revision=?",d.fileId(),d.revision());
        if(rows.isEmpty()) return false;
        String state=(String)rows.getFirst().get("state");
        return switch(d.phase()) {
            case "COMMIT" -> Set.of("PREPARED","COMMITTED","DELETED").contains(state);
            case "DELETED" -> Set.of("COMMITTED","DELETED").contains(state);
            default -> true;
        };
    }
    private void checked(SyncEvent e) {
        if(you.v50to.eatwhat.cluster.ClusterGcBarrier.managed(jdbc))throw new IllegalArgumentException("CLUSTER_LEGACY_GC_EXCLUDED");
        FileGcEvents.validate(e,peer);var d=FileGcEvents.decision(e.payloadJson());
        if(!d.coordinator().equals(campus?local:peer)||!d.participant().equals(campus?peer:local))
            throw new IllegalArgumentException("GC_INSTALLATION_MISMATCH");
    }
    @Override public void apply(SyncEvent e) {
        checked(e); var d=FileGcEvents.decision(e.payloadJson());
        lock(d.fileId(),d.revision());
        var revision=jdbc.queryForMap("SELECT sha256,size_bytes FROM eatwhat_file.file_revision WHERE file_id=? AND revision=?",d.fileId(),d.revision());
        if(!d.sha256().equals(((String)revision.get("sha256")).trim())||d.sizeBytes()!=((Number)revision.get("size_bytes")).longValue())
            throw new IllegalArgumentException("GC_REVISION_MISMATCH");
        if(d.phase().equals("PREPARE")) {
            jdbc.update("""
                INSERT INTO eatwhat_file.file_gc_coordination(file_id,revision,operation_id,coordinator_site,coordinator_instance,
                  participant_site,participant_instance,sha256,size_bytes,role,state)
                VALUES(?,?,?,?,?,?,?,?,?,'PARTICIPANT','REQUESTED') ON CONFLICT(file_id,revision) DO NOTHING
                """,d.fileId(),d.revision(),d.operationId(),d.coordinator().siteId(),d.coordinator().instanceId(),
                    d.participant().siteId(),d.participant().instanceId(),d.sha256(),d.sizeBytes());
        }
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_file.file_gc_coordination WHERE file_id=? AND revision=? FOR UPDATE",d.fileId(),d.revision());
        if(rows.isEmpty()) throw new IllegalStateException("GC_PREPARE_MISSING");
        var row=rows.getFirst();
        if(!decision(row,d.phase()).equals(d)) throw new IllegalArgumentException("GC_OPERATION_MISMATCH");
        String state=(String)row.get("state");
        switch(d.phase()) {
            case "PREPARE" -> { }
            case "PREPARED" -> jdbc.update("UPDATE eatwhat_file.file_gc_coordination SET peer_prepared=true,updated_at=clock_timestamp() WHERE file_id=? AND revision=?",d.fileId(),d.revision());
            case "COMMIT" -> {
                if(!Set.of("PREPARED","COMMITTED","DELETED").contains(state)) throw new IllegalStateException("GC_NOT_PREPARED");
                jdbc.update("UPDATE eatwhat_file.file_gc_coordination SET state='COMMITTED',updated_at=clock_timestamp() WHERE file_id=? AND revision=? AND state='PREPARED'",d.fileId(),d.revision());
            }
            case "DELETED" -> {
                if(!Set.of("COMMITTED","DELETED").contains(state)) throw new IllegalStateException("GC_NOT_COMMITTED");
                jdbc.update("UPDATE eatwhat_file.file_gc_coordination SET peer_deleted=true,updated_at=clock_timestamp() WHERE file_id=? AND revision=?",d.fileId(),d.revision());
                jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETED',updated_at=clock_timestamp() WHERE file_id=? AND revision=? AND site='PEER'",d.fileId(),d.revision());
            }
            default -> throw new IllegalArgumentException("GC_PHASE");
        }
    }
    public void lock(UUID file,int revision) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text||':'||?::text,0))",rs->{},file.toString(),Integer.toString(revision));
    }
    /** Called only after all local GC gates/facts were checked, under the revision lock. */
    public boolean prepareOrCommit(UUID file,int revision) {
        if(you.v50to.eatwhat.cluster.ClusterGcBarrier.managed(jdbc))return you.v50to.eatwhat.cluster.ClusterGcBarrier.committed(jdbc,file,revision);
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_file.file_gc_coordination WHERE file_id=? AND revision=? FOR UPDATE",file,revision);
        if(rows.isEmpty()) {
            if(!campus) return false;
            jdbc.update("""
                INSERT INTO eatwhat_file.file_gc_coordination(file_id,revision,operation_id,coordinator_site,coordinator_instance,
                  participant_site,participant_instance,sha256,size_bytes,role,state)
                SELECT file_id,revision,?,?,?, ?,?,sha256,size_bytes,'COORDINATOR','PREPARING'
                FROM eatwhat_file.file_revision WHERE file_id=? AND revision=?
                """,UUID.randomUUID(),local.siteId(),local.instanceId(),peer.siteId(),peer.instanceId(),file,revision);
            return false;
        }
        var row=rows.getFirst();
        // A configuration change cannot reuse the old installation's prepare fence.
        var d=decision(row,"PREPARE");
        if(!d.coordinator().equals(campus?local:peer)||!d.participant().equals(campus?peer:local)) return false;
        String state=(String)row.get("state");
        if(campus&&state.equals("PREPARING")&&Boolean.TRUE.equals(row.get("peer_prepared"))) {
            jdbc.update("UPDATE eatwhat_file.file_gc_coordination SET state='COMMITTED',updated_at=clock_timestamp() WHERE file_id=? AND revision=?",file,revision);
            return true;
        }
        if(!campus&&state.equals("REQUESTED")) {
            jdbc.update("UPDATE eatwhat_file.file_gc_coordination SET state='PREPARED',updated_at=clock_timestamp() WHERE file_id=? AND revision=?",file,revision);
            return false;
        }
        return state.equals("COMMITTED");
    }
    /** Publisher is database-only; Outbox append and phase progress are one transaction. */
    public void publish(OutboxService outbox,int batch) {
        if(you.v50to.eatwhat.cluster.ClusterGcBarrier.managed(jdbc))return;
        var rows=jdbc.queryForList("""
            SELECT * FROM eatwhat_file.file_gc_coordination
            WHERE (role='COORDINATOR' AND state='PREPARING' AND published_phase<1)
               OR (role='PARTICIPANT' AND state IN ('PREPARED','COMMITTED') AND published_phase<2)
               OR (role='COORDINATOR' AND state IN ('COMMITTED','DELETED') AND published_phase<3)
               OR (state='DELETED' AND published_phase<4)
            ORDER BY updated_at,file_id,revision LIMIT ? FOR UPDATE SKIP LOCKED
            """,batch);
        for(var row:rows) {
            boolean coordinator=row.get("role").equals("COORDINATOR");String state=(String)row.get("state");
            int published=((Number)row.get("published_phase")).intValue();
            // Never skip COMMIT if deletion finishes before the publisher next wakes.
            int phase=coordinator?(state.equals("PREPARING")?1:published<3?3:4):(state.equals("DELETED")?4:2);
            var d=decision(row,FileGcEvents.PHASES.get(phase-1));
            if(!local.equals(coordinator?d.coordinator():d.participant())||!peer.equals(coordinator?d.participant():d.coordinator())) continue;
            var event=FileGcEvents.envelope(d,local);outbox.append(event,List.of(peer.siteId()));
            jdbc.update("UPDATE eatwhat_file.file_gc_coordination SET published_phase=? WHERE file_id=? AND revision=?",phase,d.fileId(),d.revision());
        }
    }
    private static FileGcEvents.Decision decision(Map<String,Object> r,String phase) {
        return new FileGcEvents.Decision((UUID)r.get("operation_id"),(UUID)r.get("file_id"),((Number)r.get("revision")).intValue(),
                ((String)r.get("sha256")).trim(),((Number)r.get("size_bytes")).longValue(),
                new NodeIdentity((String)r.get("coordinator_site"),(UUID)r.get("coordinator_instance")),
                new NodeIdentity((String)r.get("participant_site"),(UUID)r.get("participant_instance")),phase);
    }
}
