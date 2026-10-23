package you.v50to.eatwhat.sync.file;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.service.storage.replication.FileReplicaCatalog.PeerRevision;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Database-only publisher. Dirty markers and Outbox append commit together; no external I/O under locks. */
public final class FileSyncWorker {
    private final JdbcTemplate jdbc;
    private final SyncTransactions tx;
    private final OutboxService outbox;
    private final ConsentStore consent;
    private final NodeIdentity local,peer;
    private final int batch;
    private final you.v50to.eatwhat.cluster.ClusterMembershipView membership;
    private you.v50to.eatwhat.service.storage.replication.FileReplicationEnqueuer enqueuer;
    public void setEnqueuer(you.v50to.eatwhat.service.storage.replication.FileReplicationEnqueuer enqueuer) { this.enqueuer=enqueuer; }
    private FileGcProtocol gcProtocol;
    public void setGcProtocol(FileGcProtocol protocol) { this.gcProtocol=protocol; }
    private final AtomicBoolean active=new AtomicBoolean();
    public FileSyncWorker(JdbcTemplate jdbc,SyncTransactions tx,OutboxService outbox,ConsentStore consent,
                          NodeIdentity local,NodeIdentity peer,int batch) {
        this.jdbc=jdbc; this.tx=tx; this.outbox=outbox; this.consent=consent; this.local=local; this.peer=peer; this.batch=batch;
        this.membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
    }
    public void runOnce() {
        if(!active.compareAndSet(false,true)) return;
        try {
            // No locks in this discovery query. Every candidate is rechecked under its own short transaction.
            var candidates=jdbc.queryForList("SELECT file_id,revision FROM eatwhat_file.file_sync_dirty ORDER BY checked_at,file_id,revision LIMIT ?",batch);
            for(var c:candidates) {
                if(Thread.currentThread().isInterrupted()) return;
                try { tx.independent(()->{ publish((UUID)c.get("file_id"),((Number)c.get("revision")).intValue()); return null; }); }
                catch(RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(FileSyncWorker.class).warn("File event candidate failed; retained for retry ({})",failure.getClass().getSimpleName());
                    tx.independent(()->{jdbc.update("UPDATE eatwhat_file.file_sync_dirty SET checked_at=clock_timestamp() WHERE file_id=? AND revision=?",c.get("file_id"),c.get("revision"));return null;});
                }
            }
            if(gcProtocol!=null) tx.independent(()->{gcProtocol.publish(outbox,batch);return null;});
            tx.independent(()->{
                var receipts=jdbc.queryForList("SELECT * FROM eatwhat_file.file_sync_receipt WHERE NOT published ORDER BY created_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED",batch);
                for(var row:receipts) {
                    var a=new FileEvents.Applied((UUID)row.get("event_id"),((String)row.get("envelope_hash")).trim(),
                            (UUID)row.get("file_id"),((Number)row.get("revision")).intValue(),(String)row.get("outcome"));
                    String recipient;
                    if(membership.bound()) {
                        var origins=jdbc.queryForList("SELECT origin_site FROM eatwhat_sync.inbox WHERE event_id=?",String.class,a.eventId());
                        if(origins.isEmpty() && Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('eatwhat_sync.purged_personal_event') IS NOT NULL",Boolean.class))) {
                            var hashes=jdbc.queryForList("SELECT envelope_hash FROM eatwhat_sync.purged_personal_event WHERE event_id=?",String.class,a.eventId());
                            if(hashes.size()==1 && a.envelopeHash().equals(hashes.getFirst())) {
                                // Withdrawal intentionally removed the original payload and routing. No peer confirmation is emitted.
                                jdbc.update("UPDATE eatwhat_file.file_sync_receipt SET published=true WHERE event_id=?",a.eventId());
                                continue;
                            }
                        }
                        if(origins.size()!=1) throw new IllegalStateException("FILE_RECEIPT_ORIGIN_MISSING");
                        recipient=origins.getFirst();
                    } else recipient=peer.siteId();
                    append(FileEvents.event(FileEvents.RECEIPT,local,a.eventId(),1,1,null,a),List.of(recipient));
                    jdbc.update("UPDATE eatwhat_file.file_sync_receipt SET published=true WHERE event_id=?",a.eventId());
                }
                return null;
            });
        } finally { active.set(false); }
    }
    private void publish(UUID file,int revision) {
        var f=jdbc.queryForObject("""
            SELECT o.owner_gid,o.biz,o.logical_key,r.final_key,r.sha256,r.size_bytes,r.content_type
            FROM eatwhat_file.file_object o JOIN eatwhat_file.file_revision r USING(file_id)
            WHERE r.file_id=? AND r.revision=? AND r.state='VERIFIED'
            """,(rs,n)->new PeerRevision(file,revision,rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5).trim(),rs.getLong(6),rs.getString(7)),file,revision);
        if(f==null) return;
        // Consent is locked before per-file state, matching domain writers and account withdrawal.
        jdbc.query("SELECT 1 FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",rs->{},f.ownerGid());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text || ':' || ?::text,0))",rs->{},file.toString(),Integer.toString(revision));
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_file.file_sync_dirty WHERE file_id=? AND revision=? FOR UPDATE SKIP LOCKED",file,revision);
        if(rows.isEmpty()) return;
        var d=rows.getFirst();
        long generation=((Number)d.get("generation")).longValue();
        jdbc.update("UPDATE eatwhat_file.file_sync_dirty SET checked_at=clock_timestamp() WHERE file_id=? AND revision=?",file,revision);
        jdbc.update("INSERT INTO eatwhat_file.file_sync_origin(file_id,origin_site,origin_instance) SELECT ?,?,? WHERE EXISTS(SELECT 1 FROM eatwhat_file.upload_intent WHERE logical_key=? AND status='COMPLETE') ON CONFLICT DO NOTHING",file,local.siteId(),local.instanceId(),f.logicalKey());
        var origins=jdbc.queryForList("SELECT * FROM eatwhat_file.file_sync_origin WHERE file_id=?",file);
        var origin=origins.isEmpty()?Map.<String,Object>of():origins.getFirst();
        var state=consent.getBySubject(f.ownerGid());
        boolean allowed=state!=null&&consent.outboundAuthorization(f.ownerGid(),state.epoch(),f.ownerGid(),state.epoch()).allowed();
        boolean own=local.siteId().equals(origin.get("origin_site"))&&local.instanceId().equals(origin.get("origin_instance"));
        boolean frozen=FileGcProtocol.deletionFrozen(jdbc,file,revision);
        if(allowed&&!frozen) {
            if(own&&enqueuer!=null) enqueuer.onRevisionVerified(file,revision,f.finalKey());
            jdbc.update("UPDATE eatwhat_file.file_transfer_job SET status='READY',consent_epoch=?,next_attempt_at=clock_timestamp(),updated_at=clock_timestamp() WHERE file_id=? AND revision=? AND status IN ('READY','PAUSED_CONSENT') AND (status='PAUSED_CONSENT' OR consent_epoch IS DISTINCT FROM ?)",state.epoch(),file,revision,state.epoch());
        }
        if(allowed&&!frozen&&own&&((Number)d.get("metadata_epoch")).longValue()!=state.epoch()) {
            append(FileEvents.event(FileEvents.METADATA,local,file,revision,state.epoch(),f.ownerGid(),new FileEvents.Metadata(f,state.epoch())));
            jdbc.update("UPDATE eatwhat_file.file_sync_dirty SET metadata_epoch=? WHERE file_id=? AND revision=?",state.epoch(),file,revision);
        }
        if(generation>((Number)d.get("published_generation")).longValue()) {
            var counts=jdbc.queryForMap("SELECT count(*) AS live,count(*) FILTER(WHERE f.visibility='PUBLIC') AS public FROM eatwhat_file.file_reference_live f JOIN eatwhat_file.file_key_alias a USING(logical_key) WHERE a.file_id=? AND a.revision=?",file,revision);
            long live=((Number)counts.get("live")).longValue(),pub=((Number)counts.get("public")).longValue();
            append(FileEvents.event(FileEvents.REFERENCES,local,file,revision,generation,null,new FileEvents.References(file,revision,generation,live,pub,live==0)));
            jdbc.update("UPDATE eatwhat_file.file_sync_dirty SET published_generation=? WHERE file_id=? AND revision=?",generation,file,revision);
        }
        if(allowed&&!frozen&&(generation>((Number)d.get("replica_generation")).longValue()||state.epoch()!=((Number)d.get("replica_epoch")).longValue())) {
            var verified=jdbc.queryForList("SELECT site FROM eatwhat_file.file_replica WHERE file_id=? AND revision=? AND state IN ('AVAILABLE_LOCAL','PEER_VERIFIED') AND verified_sha256=? AND verified_size=?",String.class,file,revision,f.sha256(),f.sizeBytes());
            if(!verified.isEmpty()) {
                append(FileEvents.event(FileEvents.REPLICA,local,file,revision,generation,f.ownerGid(),
                        new FileEvents.Replica(file,revision,f.ownerGid(),state.epoch(),generation,f.sha256(),f.sizeBytes(),verified.contains("LOCAL"),verified.contains("PEER"))));
                jdbc.update("UPDATE eatwhat_file.file_sync_dirty SET replica_generation=?,replica_epoch=? WHERE file_id=? AND revision=?",generation,state.epoch(),file,revision);
            }
        }
    }
    private void append(SyncEvent event) {
        append(event,membership.bound()?List.of():List.of(peer.siteId()));
    }
    private void append(SyncEvent event,Collection<String> recipients) {
        // A worker restart or new grant cannot create two different immutable envelopes for one publication.
        var existing=jdbc.queryForList("SELECT envelope_hash FROM eatwhat_sync.outbox WHERE event_id=?",String.class,event.eventId());
        if(!existing.isEmpty()) {
            if(!existing.getFirst().equals(new EventCodec().encode(event).hash())) throw new IllegalStateException("FILE_PUBLICATION_CONFLICT");
            return;
        }
        outbox.append(event,recipients);
    }
}
