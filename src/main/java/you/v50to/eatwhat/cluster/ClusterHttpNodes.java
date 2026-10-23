package you.v50to.eatwhat.cluster;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.ControlAudit;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** HTTPS or explicit loopback tunnels only. No redirects, caller URLs, response bodies in errors, or credential logs. */
public final class ClusterHttpNodes implements ClusterMembershipCoordinator.Nodes {
    public interface Synchronizer {void synchronize(ClusterMembershipCoordinator.Plan plan,List<ClusterProfiles.Entry> nodes);}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final UUID clusterId;private final Synchronizer synchronizer;private final ClusterStore membership;
    private final HttpClient client;
    public ClusterHttpNodes(UUID clusterId,Synchronizer synchronizer,ClusterStore membership){this(clusterId,synchronizer,membership,null);}
    public ClusterHttpNodes(UUID clusterId,Synchronizer synchronizer,ClusterStore membership,java.nio.file.Path nodeCaFile){this.clusterId=clusterId;this.synchronizer=synchronizer;this.membership=membership;this.client=you.v50to.eatwhat.config.NodeHttpClients.create(nodeCaFile);}
    public Map<String,Object> call(ClusterProfiles.Entry node,String operation,Object input){
        if(!Set.of("health","health-check","storage-check","probe","stage","fence","prepared","apply","release","gc-prepare","gc-commit","gc-state","gc-run","control-fault","session-introspect","replay-status","replay-seed","replay-pull","replay-push","replay-ack","reseed-consent","flush","file-proof","file-manifest").contains(operation))throw new ClusterFailure(400,"INVALID_NODE_OPERATION");
        if(!Set.of("health","health-check","probe","replay-status","file-proof","gc-state","session-introspect").contains(operation))membership.requireLeadership();
        try{
            var request=HttpRequest.newBuilder(URI.create(node.baseUri()+"/internal/cluster/v1/"+operation)).timeout(Duration.ofSeconds(10)).header("X-Control-Node-Token",ControlAudit.token(node.tokenFile()));
            if(input==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(input)));
            var response=node.client(client).send(request.build(),HttpResponse.BodyHandlers.ofInputStream());try(var body=response.body()){
                byte[] bytes=body.readNBytes(4*1024*1024+65537);if(response.statusCode()!=200||bytes.length>4*1024*1024+65536)throw new ClusterFailure(503,"CLUSTER_NODE_REQUEST_FAILED");
                Map<String,Object> result=JSON.readValue(bytes,Map.class);verify(node,result,operation);return result;
            }
        }catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new ClusterFailure(503,"CLUSTER_NODE_REQUEST_FAILED");}
    }
    private void verify(ClusterProfiles.Entry node,Map<String,Object> result,String operation){if(!node.member().siteId().equals(result.get("siteId"))||!node.member().instanceId().toString().equals(Objects.toString(result.get("instanceId")))||!clusterId.toString().equals(Objects.toString(result.get("clusterId"))))throw new ClusterFailure(409,"CLUSTER_NODE_IDENTITY_MISMATCH");
        // A copied configuration/UUID on a different database is not the original GC participant.
        // RESTORE intentionally works without a database proof; it only restores a configured proxy.
        if(!Set.of("control-fault","health","health-check").contains(operation)){Object fingerprint=membership.member(node.member().siteId()).get("database_fingerprint");if(fingerprint!=null&&!fingerprint.equals(result.get("databaseFingerprint")))throw new ClusterFailure(409,"CLUSTER_NODE_DATABASE_MISMATCH");}
    }
    @Override public String fingerprint(ClusterProfiles.Entry node){var probe=call(node,"probe",null);if(!Boolean.TRUE.equals(probe.get("dataPlaneReady"))||!Objects.equals(1,probe.get("protocolVersion"))||!Objects.equals(node.member().allocationSlot(),probe.get("allocationSlot"))||!node.member().mode().equals(probe.get("mode")))throw new ClusterFailure(409,"CLUSTER_DATA_PLANE_NOT_READY");Object fp=probe.get("databaseFingerprint");if(!(fp instanceof String value)||!value.matches("[0-9a-f]{32}"))throw new ClusterFailure(409,"INVALID_DATABASE_PROOF");return value;}
    private String proof(ClusterProfiles.Entry node,String phase,ClusterMembershipCoordinator.Plan plan){var result=call(node,phase,plan);if(!plan.changeId().toString().equals(Objects.toString(result.get("changeId")))||!ClusterStore.hash(plan).equals(result.get("planHash"))||!(result.get("proofHash") instanceof String proof)||!proof.matches("[0-9a-f]{64}"))throw new ClusterFailure(409,"CLUSTER_NODE_PROOF_MISMATCH");return proof;}
    @Override public void fence(ClusterProfiles.Entry node,ClusterMembershipCoordinator.Plan plan){proof(node,"fence",plan);}
    @Override public void stage(ClusterProfiles.Entry node,ClusterMembershipCoordinator.Plan plan){proof(node,"stage",plan);}
    @Override public String prepared(ClusterProfiles.Entry node,ClusterMembershipCoordinator.Plan plan){return proof(node,"prepared",plan);}
    @Override public String apply(ClusterProfiles.Entry node,ClusterMembershipCoordinator.Plan plan){return proof(node,"apply",plan);}
    @Override public String release(ClusterProfiles.Entry node,ClusterMembershipCoordinator.Plan plan){return proof(node,"release",plan);}
    @Override public void synchronize(ClusterMembershipCoordinator.Plan plan,List<ClusterProfiles.Entry> nodes){synchronizer.synchronize(plan,nodes);}
}
