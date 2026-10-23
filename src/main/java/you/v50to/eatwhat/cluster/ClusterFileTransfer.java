package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.file.FileGcProtocol;
import you.v50to.eatwhat.service.storage.replication.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Credentials and physical keys remain at the node; every upload and proof verifies actual bytes. */
public final class ClusterFileTransfer {
    public record Revision(UUID fileId,int revision){public Revision{Objects.requireNonNull(fileId);if(revision<1)throw new ClusterFailure(400,"INVALID_REVISION");}}
    public record Proof(UUID fileId,int revision,String sha256,long sizeBytes,String state){}
    public record Manifest(Revision file,long epoch,long generation,List<ClusterMember> required,List<ClusterMember> verified){public Manifest{required=List.copyOf(required);verified=List.copyOf(verified);if(required.isEmpty()||required.size()>16||!required.containsAll(verified))throw new ClusterFailure(400,"INVALID_REPLICA_MANIFEST");}}
    private record Metadata(String key,String sha,long size,String contentType,UUID owner,String state){}
    private final JdbcTemplate jdbc;private final ClusterTransactions tx;private final ReplicaObjectStore store;private final ClusterMember local;private final FilePublicationService publication;
    public ClusterFileTransfer(JdbcTemplate jdbc,SyncTransactions tx,ReplicaObjectStore store,ClusterMember local,FilePublicationService publication){this.jdbc=jdbc;this.tx=new ClusterTransactions(tx);this.store=store;this.local=local;this.publication=publication;}
    private Metadata metadata(Revision f){var rows=jdbc.queryForList("SELECT x.object_key,x.state,r.sha256,r.size_bytes,r.content_type,o.owner_gid FROM eatwhat_file.file_replica x JOIN eatwhat_file.file_revision r USING(file_id,revision) JOIN eatwhat_file.file_object o USING(file_id) WHERE x.file_id=? AND x.revision=? AND x.site='LOCAL' AND r.state='VERIFIED'",f.fileId(),f.revision());if(rows.size()!=1)throw new ClusterFailure(409,"FILE_METADATA_PENDING");var r=rows.getFirst();return new Metadata((String)r.get("object_key"),((String)r.get("sha256")).trim(),((Number)r.get("size_bytes")).longValue(),(String)r.get("content_type"),(UUID)r.get("owner_gid"),(String)r.get("state"));}
    private void authorized(Revision f,Metadata m){
        if(FileGcProtocol.deletionFrozen(jdbc,f.fileId(),f.revision())||Set.of("DELETED","DELETING","QUARANTINED","PAUSED_CONSENT").contains(m.state()))throw new ClusterFailure(409,"FILE_TRANSFER_FROZEN");
        var states=jdbc.queryForList("SELECT status,withdrawal_request_id FROM eatwhat_consent.subject WHERE subject_id=?",m.owner());if(states.size()!=1||!states.getFirst().get("status").equals("GRANTED")||states.getFirst().get("withdrawal_request_id")!=null)throw new ClusterFailure(409,"FILE_CONSENT_REQUIRED");
        var membership=jdbc.queryForList("SELECT state FROM eatwhat_cluster.local_state WHERE singleton");if(membership.size()!=1||membership.getFirst().get("state").equals("RETIRED"))throw new ClusterFailure(409,"FILE_SITE_NOT_JOINED");
    }
    static Path temporary(){try{return Files.createTempFile("eatwhat-cluster-file-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(IOException e){throw new ClusterFailure(503,"FILE_TEMP_UNAVAILABLE");}}
    static void remove(Path file){try{Files.deleteIfExists(file);}catch(IOException e){throw new ClusterFailure(503,"FILE_TEMP_CLEANUP_FAILED");}}
    private void verify(ReplicaObjectStore.DigestResult result,Metadata m){if(result.size()!=m.size()||!result.sha256Hex().equals(m.sha()))throw new ClusterFailure(409,"FILE_BYTES_MISMATCH");}
    public Proof proof(Revision f){try(var io=ClusterFileIo.acquire(jdbc,f.fileId(),f.revision())){var m=metadata(f);authorized(f,m);if(!m.state().equals("AVAILABLE_LOCAL"))return new Proof(f.fileId(),f.revision(),m.sha(),m.size(),"PENDING");verify(store.readbackDigest(m.key(),m.size()),m);record(f,m);return new Proof(f.fileId(),f.revision(),m.sha(),m.size(),"VERIFIED");}}
    public void download(Revision f,OutputStream output)throws IOException{try(var io=ClusterFileIo.acquire(jdbc,f.fileId(),f.revision())){var m=metadata(f);authorized(f,m);if(!m.state().equals("AVAILABLE_LOCAL"))throw new ClusterFailure(409,"FILE_SOURCE_PENDING");var tmp=temporary();try{verify(store.downloadTo(m.key(),tmp,m.size()),m);authorized(f,m);Files.copy(tmp,output);}finally{remove(tmp);}}}
    public Proof upload(Revision f,InputStream input){try(var io=ClusterFileIo.acquire(jdbc,f.fileId(),f.revision())){var m=metadata(f);authorized(f,m);var tmp=temporary();try{
        var digest=MessageDigest.getInstance("SHA-256");long size=0;try(var output=Files.newOutputStream(tmp)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){size+=count;if(size>m.size())throw new ClusterFailure(413,"FILE_TOO_LARGE");digest.update(buffer,0,count);output.write(buffer,0,count);}}
        verify(new ReplicaObjectStore.DigestResult(size,HexFormat.of().formatHex(digest.digest())),m);authorized(f,m);
        if(store.head(m.key()).isEmpty())store.putFile(m.key(),m.contentType(),tmp,m.sha());verify(store.readbackDigest(m.key(),m.size()),m);record(f,m);return new Proof(f.fileId(),f.revision(),m.sha(),m.size(),"VERIFIED");
    }catch(ClusterFailure e){throw e;}catch(Exception e){throw new ClusterFailure(503,"FILE_TRANSFER_FAILED");}finally{remove(tmp);}}}
    private void record(Revision f,Metadata m){tx.required(()->{jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text||':'||?::text,0))",rs->{},f.fileId().toString(),Integer.toString(f.revision()));authorized(f,metadata(f));
        jdbc.update("UPDATE eatwhat_file.file_replica SET state='AVAILABLE_LOCAL',verified_sha256=?,verified_size=?,verified_at=clock_timestamp(),updated_at=clock_timestamp() WHERE file_id=? AND revision=? AND site='LOCAL'",m.sha(),m.size(),f.fileId(),f.revision());
        jdbc.update("INSERT INTO eatwhat_cluster.replica_fact(file_id,revision,site_id,instance_id,sha256,size_bytes,state) VALUES(?,?,?,?,?,?,'VERIFIED') ON CONFLICT DO NOTHING",f.fileId(),f.revision(),local.siteId(),local.instanceId(),m.sha(),m.size());return null;});}
    public void manifest(Manifest manifest){var f=manifest.file();tx.required(()->{
        var state=jdbc.queryForList("SELECT epoch FROM eatwhat_cluster.local_state WHERE singleton FOR SHARE");if(state.size()!=1||manifest.epoch()!=((Number)state.getFirst().get("epoch")).longValue())throw new ClusterFailure(409,"STALE_REPLICA_MANIFEST");
        var members=new ClusterMembershipView(jdbc).participants();if(!members.containsAll(manifest.required())||!manifest.required().contains(local))throw new ClusterFailure(409,"REPLICA_PARTICIPANT_MISMATCH");
        var m=metadata(f);authorized(f,m);
        jdbc.update("INSERT INTO eatwhat_cluster.replica_manifest(file_id,revision,epoch,generation,required_members) VALUES(?,?,?,?,?::jsonb) ON CONFLICT(file_id,revision) DO UPDATE SET epoch=excluded.epoch,generation=excluded.generation,required_members=excluded.required_members",f.fileId(),f.revision(),manifest.epoch(),manifest.generation(),ClusterStore.json(manifest.required()));
        for(var member:manifest.verified())jdbc.update("INSERT INTO eatwhat_cluster.replica_fact(file_id,revision,site_id,instance_id,sha256,size_bytes,state) VALUES(?,?,?,?,?,?,'VERIFIED') ON CONFLICT DO NOTHING",f.fileId(),f.revision(),member.siteId(),member.instanceId(),m.sha(),m.size());
        if(manifest.verified().stream().anyMatch(member->!member.equals(local)))publication.markPeerConfirmed(f.fileId(),f.revision());else publication.recompute(f.fileId(),f.revision());return null;
    });}
}
