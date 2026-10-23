package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import java.util.*;

/** Source collection for the future composite installer, not a write API.
 * Inputs come from frozen inventory/preflight and reviewed artifacts, not HTTP
 * secret maps. Captured private payload belongs only in the encrypted journal.
 */
public final class ControlBootstrapSources {
    static final String INSPECTION_PROTOCOL="eatwhat-controller-source-inspection-v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlServerStore store;private final ControlServers servers;private final ControlSsh ssh;
    public ControlBootstrapSources(ControlServerStore store,ControlServers servers,ControlSsh ssh){this.store=store;this.servers=servers;this.ssh=ssh;}
    public record Request(String sourceArtifactSha256,String probeArtifactSha256,String unitSha256,ControllerSourceSnapshot.Expected expected) {
        @Override public String toString(){return "BootstrapSourceRequest[redacted]";}
    }
    public record Captured(UUID serverId,long revision,String routeBinding,Map<String,Object> evidence,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<String,Object> privatePayload) {
        public Captured {evidence=Map.copyOf(evidence);privatePayload=Map.copyOf(privatePayload);}
        @Override public String toString(){return "BootstrapSourceCapture[redacted]";}
    }
    public record Discovered(Request request,Captured captured){
        @Override public String toString(){return "BootstrapSourceDiscovery[redacted]";}
    }
    private static void require(boolean value){if(!value)throw new ControlFailure(502,"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE");}
    public Captured capture(UUID serverId,long revision,Request request){
        return collect(serverId,revision,request,true);
    }
    /** Read-only observation for an already approved running job. Draining may
     * stop new plans without preventing this original job from finishing. */
    public Captured observeExecuting(UUID serverId,long revision,Request request){return collect(serverId,revision,request,false);}
    /** Independent read-only collection. Caller authenticates the original
     * capsule and current administrator; this grants no execution authority. */
    Captured inspectOriginal(UUID serverId,long revision,String originalRoute,Request request){
        ControllerSourceSnapshot.validate(request.expected());
        for(String hash:List.of(request.sourceArtifactSha256(),request.probeArtifactSha256(),request.unitSha256()))
            if(!hash.matches("[a-f0-9]{64}"))throw new ControlFailure(400,"BOOTSTRAP_INVALID_ARTIFACT");
        var registered=inspectionServer(serverId,revision,originalRoute);
        try{
            byte[] input=JSON.writeValueAsBytes(request);require(input.length<=8192);
            var response=ssh.call(registered,ControlServers.resource("controller-bootstrap-source.py"),List.of("inspection"),out->out.write(input),50);
            require(response.keySet().equals(Set.of("protocol","sourceProtocol","proof","sourceAuthority","fileHashes","sourceProcess","captureBinding","privateMaterialIncluded"))
                    &&INSPECTION_PROTOCOL.equals(response.get("protocol"))&&ControllerSourceSnapshot.PROTOCOL.equals(response.get("sourceProtocol"))
                    &&Boolean.FALSE.equals(response.get("privateMaterialIncluded"))&&response.get("captureBinding") instanceof String binding&&binding.matches("[a-f0-9]{64}"));
            validateFacts(request,response);inspectionServer(serverId,revision,originalRoute);
            return captured(serverId,revision,originalRoute,response);
        }catch(ControlFailure failure){throw failure;}
        catch(ControlSsh.RemoteRefusal refusal){throw refusal(refusal);}
        catch(Exception unavailable){throw new ControlFailure(502,"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE");}
    }
    private Map<String,Object> inspectionServer(UUID id,long revision,String originalRoute){
        var row=store.server(id);ControlServers.expected(row,revision);
        if(!ControlSsh.rootExecution(row))throw new ControlFailure(409,"BOOTSTRAP_LINUX_ROOT_REQUIRED");
        if(!Objects.equals(originalRoute,ssh.routeBinding(row)))throw new ControlFailure(409,"SSH_ROUTE_BINDING_CHANGED");return row;
    }
    private void authorization(boolean accepting){if(accepting)servers.requireWrite();else servers.requireExecuting();}
    /** Planning obtains its authority anchor from local SQL/state. No browser
     * supplies file hashes, artifact paths, credentials or a controller name. */
    public Discovered discover(UUID serverId,long revision,ControllerSourceSnapshot.Authority authority){
        servers.requireWrite();
        try{ControllerSourceSnapshot.validate(authority);}catch(Exception invalid){throw new ControlFailure(400,"BOOTSTRAP_INVALID_SOURCE_AUTHORITY");}
        var registered=store.server(serverId);ControlServers.expected(registered,revision);
        if(!ControlSsh.rootExecution(registered))throw new ControlFailure(409,"BOOTSTRAP_LINUX_ROOT_REQUIRED");
        String route=ssh.routeBinding(registered);
        try{
            byte[] input=JSON.writeValueAsBytes(Map.of("authority",authority));require(input.length<=8192);
            var response=ssh.call(registered,ControlServers.resource("controller-bootstrap-source.py"),List.of("discover"),out->out.write(input),80);
            require(response.keySet().equals(Set.of("protocol","request","capture"))&&"eatwhat-controller-source-discovery-v1".equals(response.get("protocol"))
                    &&response.get("request") instanceof Map<?,?> request&&request.keySet().equals(Set.of("sourceArtifactSha256","probeArtifactSha256","unitSha256","expected"))
                    &&response.get("capture") instanceof Map<?,?>);
            var request=JSON.readValue(JSON.writeValueAsBytes(response.get("request")),Request.class);ControllerSourceSnapshot.validate(request.expected());
            for(String hash:Arrays.asList(request.sourceArtifactSha256(),request.probeArtifactSha256(),request.unitSha256()))require(hash!=null&&hash.matches("[a-f0-9]{64}"));
            var e=request.expected();
            require(request.sourceArtifactSha256().equals(request.probeArtifactSha256())
                    &&authority.equals(new ControllerSourceSnapshot.Authority(e.clusterId(),e.authoritySiteId(),e.authorityInstanceId(),e.authorityDatabaseFingerprint(),e.stateId(),e.epoch(),e.sharedKeySha256())));
            @SuppressWarnings("unchecked") var capture=(Map<String,Object>)response.get("capture");validateResponse(request,capture);
            servers.requireWrite();var current=store.server(serverId);ControlServers.expected(current,revision);
            if(!route.equals(ssh.routeBinding(current)))throw new ControlFailure(409,"SSH_ROUTE_BINDING_CHANGED");
            return new Discovered(request,captured(serverId,revision,route,capture));
        }catch(ControlFailure failure){throw failure;}
        catch(ControlSsh.RemoteRefusal refusal){throw refusal(refusal);}
        catch(Exception unavailable){throw new ControlFailure(502,"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE");}
    }
    private static ControlFailure refusal(ControlSsh.RemoteRefusal refusal){
        return Set.of("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED","BOOTSTRAP_SOURCE_PROFILE_RECEIVER_REQUIRED","BOOTSTRAP_SOURCE_DISCOVERY_REQUIRED",
                "BOOTSTRAP_SOURCE_PROFILE_DIRECTORY_REQUIRED","BOOTSTRAP_SOURCE_PROFILE_STORAGE_READ_ONLY").contains(refusal.code)
                ?new ControlFailure(409,refusal.code):new ControlFailure(502,"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE");
    }
    private static void validateResponse(Request request,Map<String,Object> response){
        require(response.keySet().equals(Set.of("protocol","proof","sourceAuthority","fileHashes","_private","sourceProcess","captureBinding"))
                &&ControllerSourceSnapshot.PROTOCOL.equals(response.get("protocol"))
                &&response.get("captureBinding") instanceof String binding&&binding.matches("[a-f0-9]{64}")
                &&response.get("_private") instanceof Map<?,?> secrets&&secrets.keySet().equals(Set.of("properties","privateFiles")));
        validateFacts(request,response);
    }
    private static void validateFacts(Request request,Map<String,Object> response){
        var e=request.expected();require(Map.of("clusterId",e.clusterId().toString(),"siteId",e.authoritySiteId(),
                "instanceId",e.authorityInstanceId().toString(),"databaseFingerprint",e.authorityDatabaseFingerprint()).equals(response.get("sourceAuthority")));
        require(response.get("proof") instanceof Map<?,?> proof&&proof.get("epoch") instanceof Number epoch
                &&epoch.longValue()==e.epoch()&&Boolean.TRUE.equals(proof.get("readOnlyTransaction"))&&Boolean.TRUE.equals(proof.get("actualDatabaseNameAndOidVerified")));
    }
    private static Captured captured(UUID serverId,long revision,String route,Map<String,Object> response){
        var evidence=new LinkedHashMap<String,Object>();
        for(String key:List.of("protocol","proof","sourceAuthority","fileHashes","sourceProcess","captureBinding"))evidence.put(key,response.get(key));
        @SuppressWarnings("unchecked") var privatePayload=(Map<String,Object>)response.getOrDefault("_private",Map.of());
        return new Captured(serverId,revision,route,evidence,privatePayload);
    }
    private Captured collect(UUID serverId,long revision,Request request,boolean accepting){
        authorization(accepting);ControllerSourceSnapshot.validate(request.expected());
        for(String hash:List.of(request.sourceArtifactSha256(),request.probeArtifactSha256(),request.unitSha256()))
            if(!hash.matches("[a-f0-9]{64}"))throw new ControlFailure(400,"BOOTSTRAP_INVALID_ARTIFACT");
        var registered=store.server(serverId);ControlServers.expected(registered,revision);
        if(!ControlSsh.rootExecution(registered))throw new ControlFailure(409,"BOOTSTRAP_LINUX_ROOT_REQUIRED");
        String route=ssh.routeBinding(registered);
        try {
            byte[] input=JSON.writeValueAsBytes(request);require(input.length<=8192);
            var response=ssh.call(registered,ControlServers.resource("controller-bootstrap-source.py"),accepting?List.of("profile-receiver"):List.of(),out->out.write(input),50);
            validateResponse(request,response);
            authorization(accepting);var current=store.server(serverId);ControlServers.expected(current,revision);
            if(!route.equals(ssh.routeBinding(current)))throw new ControlFailure(409,"SSH_ROUTE_BINDING_CHANGED");
            return captured(serverId,revision,route,response);
        }catch(ControlFailure failure){throw failure;}
        catch(ControlSsh.RemoteRefusal refusal){throw refusal(refusal);}
        catch(Exception failure){throw new ControlFailure(502,"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE");}
    }
    /** Recheck the logical revision AND this manager's physical route before
     * any future dispatch. Admin/leader/epoch checks remain the manager's job.
     */
    public void requireRegisteredBinding(Captured captured){
        servers.requireWrite();var current=store.server(captured.serverId());ControlServers.expected(current,captured.revision());
        if(!captured.routeBinding().equals(ssh.routeBinding(current)))throw new ControlFailure(409,"SSH_ROUTE_BINDING_CHANGED");
    }
}
