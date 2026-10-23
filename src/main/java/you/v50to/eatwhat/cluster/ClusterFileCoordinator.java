package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.ControlAudit;
import you.v50to.eatwhat.service.storage.replication.ReplicaObjectStore;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.*;

/** Copies immutable bytes through a bounded-memory relay. Neither node's S3 credentials leave its process. */
public final class ClusterFileCoordinator {
    private static final JsonMapper JSON=JsonMapper.builder().build();private final JdbcTemplate jdbc;private final ClusterRecipientConsent consent;private final ClusterStore membership;
    private final HttpClient client;
    public ClusterFileCoordinator(JdbcTemplate jdbc,ClusterStore membership){this(jdbc,membership,null);}
    public ClusterFileCoordinator(JdbcTemplate jdbc,ClusterStore membership,Path nodeCaFile){this.jdbc=jdbc;this.membership=membership;consent=new ClusterRecipientConsent(jdbc);this.client=you.v50to.eatwhat.config.NodeHttpClients.create(nodeCaFile);}
    public void cycle(ClusterHttpNodes http,List<ClusterProfiles.Entry> nodes){
        var rows=jdbc.queryForList("SELECT r.file_id,r.revision,r.sha256,r.size_bytes,o.owner_gid,s.epoch FROM eatwhat_file.file_revision r JOIN eatwhat_file.file_object o USING(file_id) JOIN eatwhat_consent.subject s ON s.subject_id=o.owner_gid WHERE r.state='VERIFIED' AND s.status='GRANTED' AND s.withdrawal_request_id IS NULL AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.gc_fence g WHERE g.file_id=r.file_id AND g.revision=r.revision) AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.replica_manifest m WHERE m.file_id=r.file_id AND m.revision=r.revision AND m.epoch=(SELECT epoch FROM eatwhat_cluster.local_state WHERE singleton) AND m.generation=(SELECT coalesce(max(grant_id),0) FROM eatwhat_cluster.recipient_grant) AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(m.required_members) member WHERE NOT EXISTS(SELECT 1 FROM eatwhat_cluster.replica_fact f WHERE f.file_id=m.file_id AND f.revision=m.revision AND f.site_id=member->>'siteId' AND f.instance_id=(member->>'instanceId')::uuid AND f.state='VERIFIED'))) ORDER BY r.file_id,r.revision LIMIT 16");
        for(var row:rows){var f=new ClusterFileTransfer.Revision((UUID)row.get("file_id"),((Number)row.get("revision")).intValue());String sha=((String)row.get("sha256")).trim();long size=((Number)row.get("size_bytes")).longValue();UUID owner=(UUID)row.get("owner_gid");long consentEpoch=((Number)row.get("epoch")).longValue();
            var required=nodes.stream().filter(n->consent.allowed(owner,consentEpoch,n.member())).toList();if(required.size()<2)continue;
            var verified=new ArrayList<ClusterProfiles.Entry>();var pending=new ArrayList<ClusterProfiles.Entry>();
            for(var node:required){var p=proof(http,node,f,sha,size);if(p.state().equals("VERIFIED"))verified.add(node);else pending.add(node);}
            if(verified.isEmpty())throw new ClusterFailure(409,"FILE_VERIFIED_SOURCE_MISSING");
            if(!pending.isEmpty()){var tmp=ClusterFileTransfer.temporary();try{
                membership.requireLeadership();download(verified.getFirst(),f,tmp,sha,size);
                for(var target:pending){membership.requireLeadership();if(!consent.allowed(owner,consentEpoch,target.member()))throw new ClusterFailure(409,"FILE_RECIPIENT_CHANGED");upload(target,f,tmp);var p=proof(http,target,f,sha,size);if(!p.state().equals("VERIFIED"))throw new ClusterFailure(409,"FILE_COPY_PENDING");verified.add(target);}
            }finally{ClusterFileTransfer.remove(tmp);}}
            for(var target:required){if(!consent.allowed(owner,consentEpoch,target.member()))throw new ClusterFailure(409,"FILE_RECIPIENT_CHANGED");var probe=http.call(target,"probe",null);long epoch=((Number)((Map<?,?>)probe.get("membership")).get("epoch")).longValue();if(epoch<1)continue;
                http.call(target,"file-manifest",new ClusterFileTransfer.Manifest(f,epoch,consent.generation(),required.stream().map(ClusterProfiles.Entry::member).toList(),verified.stream().map(ClusterProfiles.Entry::member).toList()));}
        }
    }
    private ClusterFileTransfer.Proof proof(ClusterHttpNodes http,ClusterProfiles.Entry node,ClusterFileTransfer.Revision f,String sha,long size){var p=JSON.convertValue(http.call(node,"file-proof",f).get("proof"),ClusterFileTransfer.Proof.class);if(!p.fileId().equals(f.fileId())||p.revision()!=f.revision()||!sha.equals(p.sha256())||p.sizeBytes()!=size||!Set.of("VERIFIED","PENDING").contains(p.state()))throw new ClusterFailure(409,"FILE_PROOF_MISMATCH");return p;}
    private HttpRequest.Builder request(ClusterProfiles.Entry node,ClusterFileTransfer.Revision file){return HttpRequest.newBuilder(URI.create(node.baseUri()+"/internal/cluster/v1/files/"+file.fileId()+"/"+file.revision())).timeout(Duration.ofMinutes(5)).header("X-Control-Node-Token",ControlAudit.token(node.tokenFile()));}
    private void download(ClusterProfiles.Entry source,ClusterFileTransfer.Revision file,Path tmp,String sha,long expected){try{
        var response=source.client(client).send(request(source,file).GET().build(),HttpResponse.BodyHandlers.ofInputStream());try(var input=response.body();var output=Files.newOutputStream(tmp)){
            if(response.statusCode()!=200)throw new ClusterFailure(503,"FILE_RELAY_DOWNLOAD_FAILED");var digest=MessageDigest.getInstance("SHA-256");long size=0;byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){size+=count;if(size>expected)throw new ClusterFailure(409,"FILE_RELAY_SIZE_MISMATCH");digest.update(buffer,0,count);output.write(buffer,0,count);}if(size!=expected||!sha.equals(HexFormat.of().formatHex(digest.digest())))throw new ClusterFailure(409,"FILE_RELAY_DIGEST_MISMATCH");
        }
    }catch(ClusterFailure e){throw e;}catch(Exception e){throw new ClusterFailure(503,"FILE_RELAY_DOWNLOAD_FAILED");}}
    private void upload(ClusterProfiles.Entry target,ClusterFileTransfer.Revision file,Path tmp){try{
        var response=target.client(client).send(request(target,file).header("Content-Type","application/octet-stream").PUT(HttpRequest.BodyPublishers.ofFile(tmp)).build(),HttpResponse.BodyHandlers.ofInputStream());try(var body=response.body()){body.readNBytes(65537);if(response.statusCode()!=200)throw new ClusterFailure(503,"FILE_RELAY_UPLOAD_FAILED");}
    }catch(ClusterFailure e){throw e;}catch(Exception e){throw new ClusterFailure(503,"FILE_RELAY_UPLOAD_FAILED");}}
}
