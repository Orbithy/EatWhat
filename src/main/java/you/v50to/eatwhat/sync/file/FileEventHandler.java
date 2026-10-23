package you.v50to.eatwhat.sync.file;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.service.storage.replication.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.util.*;

/** Same-database effects only. Application acknowledgement cannot precede the Inbox APPLIED commit. */
public final class FileEventHandler implements EventHandler {
    private final String type;
    private final JdbcTemplate jdbc;
    private final ConsentStore consent;
    private final FileReplicaCatalog catalog;
    private final FilePublicationService publication;
    private final NodeIdentity peer;
    private final you.v50to.eatwhat.cluster.ClusterMembershipView membership;
    private final long maxFileSize;
    private final SiteProperties.Mode mode;
    public FileEventHandler(String type,JdbcTemplate jdbc,ConsentStore consent,FileReplicaCatalog catalog,
                            FilePublicationService publication,NodeIdentity peer,long maxFileSize,SiteProperties.Mode mode) {
        this.type=type; this.jdbc=jdbc; this.consent=consent; this.catalog=catalog;
        this.publication=publication; this.peer=peer; this.maxFileSize=maxFileSize; this.mode=mode;
        this.membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
    }
    @Override public String aggregateType() { return type; }
    @Override public String eventType() { return type; }
    @Override public int schemaVersion() { return 1; }
    @Override public boolean dependenciesSatisfied(SyncEvent e) {
        FileEvents.validate(e,membership.origin(e,peer));
        if(FileEvents.REPLICA.equals(type)) {
            var r=FileEvents.replica(e.payloadJson());
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_revision WHERE file_id=? AND revision=?)",Boolean.class,r.fileId(),r.revision()));
        }
        if(!FileEvents.METADATA.equals(type)) return true;
        var m=FileEvents.metadata(e.payloadJson());
        var state=consent.getBySubject(m.file().ownerGid());
        return state!=null&&state.epoch()>=m.epoch()
                &&(state.epoch()>m.epoch()||!state.status().equals("GRANTED")||consent.replicationAllowed(state.userId()));
    }
    @Override public void apply(SyncEvent e) {
        FileEvents.validate(e,membership.origin(e,peer));
        if(FileEvents.METADATA.equals(type)) metadata(e);
        else if(FileEvents.REFERENCES.equals(type)) references(e);
        else if(FileEvents.REPLICA.equals(type)) replica(e);
        else receipt(e);
    }
    private boolean frozen(UUID file,int revision) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text||':'||?::text,0))",rs->{},file.toString(),Integer.toString(revision));
        return FileGcProtocol.deletionFrozen(jdbc,file,revision);
    }
    private void metadata(SyncEvent e) {
        var m=FileEvents.metadata(e.payloadJson()); var f=m.file();
        jdbc.query("SELECT 1 FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",rs->{},f.ownerGid());
        var state=consent.getBySubject(f.ownerGid());
        if(state==null||state.epoch()<m.epoch()) throw new IllegalStateException("FILE_OWNER_PENDING");
        if(state.epoch()>m.epoch()||!state.status().equals("GRANTED")||state.withdrawalRequestId()!=null) {
            queueReceipt(e,f.fileId(),f.revision(),"IGNORED"); return;
        }
        if(!consent.replicationAllowed(state.userId())) throw new IllegalStateException("FILE_CONSENT_PENDING");
        if(f.sizeBytes()>maxFileSize) throw new IllegalArgumentException("FILE_SIZE_LIMIT");
        if(!f.logicalKey().startsWith(f.biz()+"/"+state.userId()+"/")) throw new IllegalArgumentException("FILE_LOGICAL_OWNER_MISMATCH");
        // A locally uploaded UUID cannot be reattributed to a remote installation, even before its first publish.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_file.upload_intent WHERE logical_key=? AND status='COMPLETE')",Boolean.class,f.logicalKey())))
            throw new IllegalArgumentException("FILE_ORIGIN_CONFLICT");
        var origins=jdbc.queryForList("SELECT origin_site,origin_instance FROM eatwhat_file.file_sync_origin WHERE file_id=?",f.fileId());
        if(!origins.isEmpty()&&(!e.originSite().equals(origins.getFirst().get("origin_site"))
                ||!e.originInstanceId().equals(origins.getFirst().get("origin_instance")))) throw new IllegalArgumentException("FILE_ORIGIN_CONFLICT");
        if(frozen(f.fileId(),f.revision())) {queueReceipt(e,f.fileId(),f.revision(),"IGNORED");return;}
        if(catalog.recordPeerRevision(f)==FileReplicaCatalog.Result.CONFLICT) throw new IllegalArgumentException("FILE_METADATA_CONFLICT");
        jdbc.update("INSERT INTO eatwhat_file.file_sync_origin VALUES(?,?,?) ON CONFLICT DO NOTHING",f.fileId(),e.originSite(),e.originInstanceId());
        // Metadata may arrive after the business projection: establish references without replaying business writes.
        reconcileReferences(f.logicalKey());
        // The CAMPUS worker is the only node that can reach both object stores. Imported metadata must not queue
        // a pull on EXTERNAL (which cannot read campus RustFS). CAMPUS already has COPY_TO_PEER for its own files.
        jdbc.update("UPDATE eatwhat_file.file_transfer_job SET consent_epoch=? WHERE file_id=? AND revision=? AND status='READY'",m.epoch(),f.fileId(),f.revision());
        queueReceipt(e,f.fileId(),f.revision(),"APPLIED");
    }
    private void references(SyncEvent e) {
        var r=FileEvents.references(e.payloadJson()); String hash=new EventCodec().encode(e).hash();
        jdbc.update("""
            INSERT INTO eatwhat_file.file_sync_peer_state(file_id,revision,origin_site,origin_instance,generation,
                live_count,public_count,tombstoned,event_id,envelope_hash)
            VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(file_id,revision,origin_site,origin_instance) DO UPDATE
            SET generation=excluded.generation,live_count=excluded.live_count,public_count=excluded.public_count,
                tombstoned=excluded.tombstoned,event_id=excluded.event_id,envelope_hash=excluded.envelope_hash,received_at=clock_timestamp()
            WHERE file_sync_peer_state.generation<excluded.generation
            """,r.fileId(),r.revision(),e.originSite(),e.originInstanceId(),r.generation(),r.liveCount(),r.publicCount(),r.tombstoned(),e.eventId(),hash);
        var saved=jdbc.queryForMap("SELECT generation,envelope_hash FROM eatwhat_file.file_sync_peer_state WHERE file_id=? AND revision=? AND origin_site=? AND origin_instance=?",r.fileId(),r.revision(),e.originSite(),e.originInstanceId());
        if(((Number)saved.get("generation")).longValue()==r.generation()&&!hash.equals(saved.get("envelope_hash")))
            throw new IllegalArgumentException("FILE_GENERATION_CONFLICT");
        queueReceipt(e,r.fileId(),r.revision(),"APPLIED");
    }
    private void replica(SyncEvent e) {
        var r=FileEvents.replica(e.payloadJson());
        jdbc.query("SELECT 1 FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",rs->{},r.ownerGid());
        var state=consent.getBySubject(r.ownerGid());
        if(state==null||state.epoch()<r.epoch()) throw new IllegalStateException("FILE_OWNER_PENDING");
        if(state.epoch()>r.epoch()||!state.status().equals("GRANTED")||state.withdrawalRequestId()!=null) {
            queueReceipt(e,r.fileId(),r.revision(),"IGNORED"); return;
        }
        if(!consent.replicationAllowed(state.userId())) throw new IllegalStateException("FILE_CONSENT_PENDING");
        var stored=jdbc.queryForMap("SELECT o.owner_gid,fr.sha256,fr.size_bytes FROM eatwhat_file.file_revision fr JOIN eatwhat_file.file_object o USING(file_id) WHERE fr.file_id=? AND fr.revision=?",r.fileId(),r.revision());
        if(!r.ownerGid().equals(stored.get("owner_gid"))||!r.sha256().equals(((String)stored.get("sha256")).trim())||r.sizeBytes()!=((Number)stored.get("size_bytes")).longValue())
            throw new IllegalArgumentException("FILE_ATTESTATION_MISMATCH");
        if(frozen(r.fileId(),r.revision())) {queueReceipt(e,r.fileId(),r.revision(),"IGNORED");return;}
        if(membership.bound()){
            if(r.localVerified()){
                var origin=membership.origin(e,peer);
                jdbc.update("INSERT INTO eatwhat_cluster.replica_fact(file_id,revision,site_id,instance_id,sha256,size_bytes,state) VALUES(?,?,?,?,?,?,'VERIFIED') ON CONFLICT(file_id,revision,site_id,instance_id) DO NOTHING",r.fileId(),r.revision(),origin.siteId(),origin.instanceId(),r.sha256(),r.sizeBytes());
            }
            publication.recompute(r.fileId(),r.revision());queueReceipt(e,r.fileId(),r.revision(),"APPLIED");return;
        }
        // Only CAMPUS has access to both stores. EXTERNAL's claim about CAMPUS bytes is never trusted.
        if(r.localVerified()) {
            attest(r,"PEER","PEER_VERIFIED");
            jdbc.update("UPDATE eatwhat_file.file_transfer_job SET status='DONE',completed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE file_id=? AND revision=? AND target_site='PEER' AND status='READY'",r.fileId(),r.revision());
        }
        if(r.peerVerified()&&mode==SiteProperties.Mode.EXTERNAL) {
            attest(r,"LOCAL","AVAILABLE_LOCAL");
            jdbc.update("UPDATE eatwhat_file.file_transfer_job SET status='DONE',completed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE file_id=? AND revision=? AND target_site='LOCAL' AND status='READY'",r.fileId(),r.revision());
        }
        publication.recompute(r.fileId(),r.revision());
        queueReceipt(e,r.fileId(),r.revision(),"APPLIED");
    }
    private void attest(FileEvents.Replica r,String site,String status) {
        jdbc.update("UPDATE eatwhat_file.file_replica SET state=?,verified_sha256=?,verified_size=?,verified_at=clock_timestamp(),updated_at=clock_timestamp() WHERE file_id=? AND revision=? AND site=? AND state IN ('PENDING_SYNC',?, 'AVAILABLE_LOCAL','PEER_VERIFIED')",status,r.sha256(),r.sizeBytes(),r.fileId(),r.revision(),site,status);
    }
    private void queueReceipt(SyncEvent e,UUID file,int revision,String outcome) {
        jdbc.update("INSERT INTO eatwhat_file.file_sync_receipt(event_id,envelope_hash,file_id,revision,outcome) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                e.eventId(),new EventCodec().encode(e).hash(),file,revision,outcome);
    }
    private void receipt(SyncEvent e) {
        var a=FileEvents.applied(e.payloadJson());
        var rows=jdbc.queryForList("SELECT o.envelope::text AS envelope,o.envelope_hash FROM eatwhat_sync.outbox o JOIN eatwhat_sync.delivery d USING(event_id) WHERE o.event_id=? AND d.target_site=?",a.eventId(),membership.origin(e,peer).siteId());
        if(rows.isEmpty() && Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('eatwhat_sync.purged_personal_event') IS NOT NULL",Boolean.class))) {
            var hashes=jdbc.queryForList("SELECT envelope_hash FROM eatwhat_sync.purged_personal_event WHERE event_id=?",String.class,a.eventId());
            // A late receipt for deliberately erased content has no publication effect.
            if(hashes.size()==1 && a.envelopeHash().equals(hashes.getFirst())) return;
        }
        if(rows.size()!=1||!a.envelopeHash().equals(rows.getFirst().get("envelope_hash"))) throw new IllegalArgumentException("UNPROVEN_FILE_RECEIPT");
        SyncEvent original=new EventCodec().decode((String)rows.getFirst().get("envelope"));
        if(FileEvents.METADATA.equals(original.aggregateType())) {
            var m=FileEvents.metadata(original.payloadJson());
            if(!m.file().fileId().equals(a.fileId())||m.file().revision()!=a.revision()) throw new IllegalArgumentException("FILE_RECEIPT_MISMATCH");
            if(a.outcome().equals("APPLIED")&&consent.outboundAuthorization(m.file().ownerGid(),m.epoch(),m.file().ownerGid(),m.epoch()).allowed())
                publication.markPeerConfirmed(a.fileId(),a.revision());
        } else if(FileEvents.REFERENCES.equals(original.aggregateType())) {
            var r=FileEvents.references(original.payloadJson());
            if(!r.fileId().equals(a.fileId())||r.revision()!=a.revision()) throw new IllegalArgumentException("FILE_RECEIPT_MISMATCH");
        } else if(FileEvents.REPLICA.equals(original.aggregateType())) {
            var r=FileEvents.replica(original.payloadJson());
            if(!r.fileId().equals(a.fileId())||r.revision()!=a.revision()) throw new IllegalArgumentException("FILE_RECEIPT_MISMATCH");
        } else throw new IllegalArgumentException("INVALID_FILE_RECEIPT_TARGET");
        jdbc.update("INSERT INTO eatwhat_file.file_sync_ack(event_id,receipt_event_id,envelope_hash,outcome) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",a.eventId(),e.eventId(),a.envelopeHash(),a.outcome());
    }
    private void reconcileReferences(String key) {
        String[] tables={"users","restaurant","foods","activity_foods","activity_dinners","activity_posts"};
        String[] kinds={"user_avatar","restaurant","food","activity_food","activity_dinner","activity_post"};
        for(int i=0;i<tables.length;i++) {
            String condition=i==0?"avatar=?":"?=ANY(picture_url)";
            condition+=" AND EXISTS(SELECT 1 FROM eatwhat_file.file_key_alias a JOIN eatwhat_identity.entity_identity owner ON owner.global_id=a.owner_gid AND owner.entity_type='users' WHERE a.logical_key=? AND a.biz='"+(i==0?"avatar":i==1?"restaurants":i==2?"foods":"activity")+"' AND owner.numeric_id="+(i==0?"public.users.id":"public."+tables[i]+".account_id")+")";
            if(i>=3) condition+=" AND deleted_at IS NULL";
            // Lock business rows before reference/revision locks, matching normal UPDATE and GC guards.
            var ids=jdbc.queryForList("SELECT id FROM public."+tables[i]+" WHERE "+condition+" ORDER BY id FOR UPDATE",Long.class,key,key);
            for(Long id:ids) {
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_reference_live WHERE logical_key=? AND ref_kind=? AND (ref_id=? OR ref_id LIKE ?))",Boolean.class,key,kinds[i],id.toString(),id+":%"))) continue;
                String rid=id+":db:"+java.util.HexFormat.of().formatHex(md5(key));
                jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(?,?,?,'PUBLIC') ON CONFLICT DO NOTHING",key,kinds[i],rid);
                jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) SELECT ?,?,?,'RESTORE','RECONCILE' WHERE (SELECT action FROM eatwhat_file.file_reference_event WHERE logical_key=? AND ref_kind=? AND ref_id=? ORDER BY event_id DESC LIMIT 1)='RELEASE'",key,kinds[i],rid,key,kinds[i],rid);
            }
        }
    }
    private static byte[] md5(String key) {
        try { return java.security.MessageDigest.getInstance("MD5").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
