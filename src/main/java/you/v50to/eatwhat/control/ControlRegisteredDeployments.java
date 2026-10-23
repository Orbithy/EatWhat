package you.v50to.eatwhat.control;

import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.ClusterStore;
import you.v50to.eatwhat.cluster.ClusterDeploymentFence;
import you.v50to.eatwhat.cluster.ClusterFailure;
import you.v50to.eatwhat.config.SiteProperties;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Registered SSH bindings are resolved by immutable member identity, never by a user supplied command/path. */
public final class ControlRegisteredDeployments implements DeploymentExecutor.RegisteredTransport {
    public interface MembershipView {Map<String,Object> status();Map<String,Object> member(String site);}
    private final ControlServerStore store;private final ControlSsh ssh;private final ControlProperties settings;private final SiteProperties local;private final Supplier<MembershipView> membership;
    private final JsonMapper json=JsonMapper.builder().build();
    private Supplier<ClusterDeploymentFence> fence=()->null;
    public ControlRegisteredDeployments(ControlServerStore store,ControlSsh ssh,ControlProperties settings,SiteProperties local,ObjectProvider<ClusterStore> membership,ObjectProvider<ClusterDeploymentFence> fence){this(store,ssh,settings,local,(Supplier<MembershipView>)()->{var m=membership.getIfAvailable();return m==null?null:new MembershipView(){public Map<String,Object> status(){return m.status();}public Map<String,Object> member(String site){return m.member(site);}};});this.fence=fence::getIfAvailable;}
    ControlRegisteredDeployments(ControlServerStore store,ControlSsh ssh,ControlProperties settings,SiteProperties local,Supplier<MembershipView> membership){this.store=store;this.ssh=ssh;this.settings=settings;this.local=local;this.membership=membership;}
    ControlRegisteredDeployments(ControlServerStore store,ControlSsh ssh,ControlProperties settings,SiteProperties local,Supplier<MembershipView> membership,Supplier<ClusterDeploymentFence> fence){this(store,ssh,settings,local,membership);this.fence=fence;}
    /** Missing reviewed protocol is an explicit blocker, never an executable placeholder. */
    @Override public boolean available(){try(var in=getClass().getResourceAsStream("/control/cluster-release.py")){return in!=null&&fence.get()!=null;}catch(Exception e){return false;}}
    @Override public boolean supportsControlHost(){return available();}
    private ClusterDeploymentFence fence(){var value=fence.get();if(value==null)throw new ControlFailure(503,"DEPLOYMENT_FENCE_UNAVAILABLE");return value;}
    @Override public void reserve(UUID id,String manifest,List<ControlProperties.DeploymentTarget> targets)throws Exception{
        var guard=fence();if(targets.isEmpty()||targets.stream().anyMatch(t->t.getMembershipEpoch()!=targets.getFirst().getMembershipEpoch()||!Objects.equals(t.getClusterId(),local.getClusterId())))throw new DeploymentExecutor.AdmissionRejected("REGISTERED_DEPLOYMENT_CLUSTER_MISMATCH");
        try{for(var target:targets)if(!Objects.equals(target.getAuthorityOrigin(),authorityOrigin(target.isControlHost()))||!Objects.equals(target.getAuthorityCaPem(),authorityCaPem()))throw new DeploymentExecutor.AdmissionRejected("DEPLOYMENT_AUTHORITY_ORIGIN_CHANGED");}
        catch(ControlFailure invalid){throw new DeploymentExecutor.AdmissionRejected(invalid.code);}
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // The caller persists these private frozen targets before submitting any worker. Never use a
        // general node/admin token, and never regenerate a capability while reconciling an old job.
        for(var target:targets)target.setEncryptedAuthorityToken(store.encrypt(token));
        try{boolean created=guard.reserve(id,manifest,targets.getFirst().getMembershipEpoch(),targets.stream().map(t->new ClusterDeploymentFence.Binding(t.getSiteId(),t.getInstanceId(),t.getDatabaseFingerprint())).toList(),ClusterDeploymentFence.tokenHash(token));if(!created)throw new ControlFailure(409,"DEPLOYMENT_RECONCILIATION_REQUIRED");guard.authorizeDispatch(id,manifest);}
        catch(ClusterFailure rejected){if(!guard.exists(id))throw new DeploymentExecutor.AdmissionRejected(rejected.getMessage());throw rejected;}
    }
    private String authorityOrigin(boolean controlHost){
        try{var uri=ControlService.origin(settings.getDeployment().getAuthorityOrigin());
            if(uri.getPort()==0||uri.getPort()>65535||"http".equals(uri.getScheme())&&!controlHost)throw new IllegalArgumentException();
            return uri.toString();
        }catch(Exception invalid){throw new ControlFailure(409,"DEPLOYMENT_AUTHORITY_ORIGIN_REQUIRED");}
    }
    private String authorityCaPem(){
        var path=settings.getDeployment().getAuthorityCaFile();if(path==null)return null;
        try{
            if(!"https".equals(ControlService.origin(settings.getDeployment().getAuthorityOrigin()).getScheme()))throw new Exception();
            byte[] bytes=ControlMemberConfiguration.privateBytes(path,16384);
            // Same bounded, CA-only, valid certificate policy as node clients;
            // this does not alter JVM/system trust or disable hostname checks.
            you.v50to.eatwhat.config.NodeHttpClients.create(path);
            return new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);
        }catch(Exception invalid){throw new ControlFailure(503,"DEPLOYMENT_AUTHORITY_CA_INVALID");}
    }
    @Override public void verifyReservation(UUID id,String manifest,boolean applying){var guard=fence();if(!applying&&!guard.exists(id))return;var proof=guard.verify(id,manifest,applying);if(applying&&!Boolean.TRUE.equals(proof.get("dispatch_authorized")))throw new ControlFailure(409,"DEPLOYMENT_DISPATCH_NOT_AUTHORIZED");}
    @Override public boolean noDispatchProven(UUID id,String manifest){return fence().noDispatchProven(id,manifest);}
    @Override public void requireRecovery(UUID id,String manifest,boolean applying){fence().requireRecovery(id,manifest,applying);}
    @Override public Map<String,Object> recovery(String action,UUID id,ControlProperties.DeploymentTarget target,String sha,UUID requestId,String proof)throws Exception{
        if(!Set.of("recovery-prepare","recover","recovery-status").contains(action))throw new ControlFailure(400,"INVALID_DEPLOYMENT_ACTION");
        requireRecovery(id,target.getDeploymentManifestHash(),!action.equals("recovery-status"));
        return call(action,id,target,null,sha,null,requestId,proof);
    }
    @SuppressWarnings("unchecked") @Override public void complete(UUID id,String manifest,Map<String,Object> job)throws Exception{
        var rows=(List<Map<String,Object>>)job.get("targets");boolean unused=!rows.isEmpty()&&rows.stream().allMatch(row->"NOT_STARTED".equals(row.get("state")));fence().finish(id,manifest,job.get("state").toString(),unused);
        if(!(Boolean.FALSE.equals(job.get("_remoteDispatchAllowed"))&&unused&&fence().noDispatchProven(id,manifest))){
            var frozen=(List<?>)job.get("_targets");if(frozen==null||frozen.size()!=rows.size())throw new ControlFailure(409,"FROZEN_DEPLOYMENT_BINDING_REQUIRED");
            for(int i=0;i<frozen.size();i++){
                var target=json.readValue(json.writeValueAsString(frozen.get(i)),ControlProperties.DeploymentTarget.class);String expected=Objects.toString(rows.get(i).get("state"),"");
                var proof=call("seal",id,target,null,job.get("artifactSha256").toString(),expected);
                if(!expected.equals(proof.get("state")))throw new ControlFailure(409,"REMOTE_RECEIPT_SEAL_REQUIRED");
            }
        }
        job.put("_remoteReceiptsSealed",true);
    }
    @Override public boolean finalized(UUID id,String manifest,Map<String,Object> job){return Boolean.TRUE.equals(job.get("_remoteReceiptsSealed"))&&fence().finalized(id,manifest,job.get("state").toString());}
    static String agentSource(){
        String core=ControlServers.resource("cluster-release-core.py").replace("__EATWHAT_RELEASE_AGENT_BASE64__",Base64.getEncoder().encodeToString(ControlServers.resource("remote-release.py").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return ControlServers.resource("cluster-release.py").replace("__EATWHAT_CLUSTER_RELEASE_CORE_BASE64__",Base64.getEncoder().encodeToString(core.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @SuppressWarnings("unchecked") @Override public ControlProperties.DeploymentTarget resolve(DeploymentExecutor.Target expected){
        var cluster=membership.get();if(cluster==null||local.getClusterId()==null)throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_CLUSTER_REQUIRED");
        var state=cluster.status();long epoch=((Number)state.get("epoch")).longValue();if(epoch<1||!local.getClusterId().toString().equals(state.get("clusterId")))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_CLUSTER_MISMATCH");
        if(!(state.get("pendingChanges") instanceof List<?> changes)||!changes.isEmpty())throw new ControlFailure(409,"MEMBERSHIP_CHANGE_PENDING");
        var member=cluster.member(expected.siteId());if(!"ACTIVE".equals(member.get("state"))||!expected.instanceId().toString().equals(Objects.toString(member.get("instance_id"))))throw new ControlFailure(409,"DEPLOYMENT_TARGET_NOT_ACTIVE");
        Object fingerprint=member.get("database_fingerprint");if(!(fingerprint instanceof String fp)||!fp.matches("[0-9a-f]{32}"))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_DATABASE_PROOF_REQUIRED");
        var matches=((Map<String,Object>)store.read().get("servers")).values().stream().filter(value->{if(!(value instanceof Map<?,?> server)||!(server.get("installation") instanceof Map<?,?> installation))return false;return expected.siteId().equals(installation.get("siteId"))&&expected.instanceId().toString().equals(Objects.toString(installation.get("instanceId")));}).toList();
        if(matches.isEmpty())throw new ControlFailure(409,"DEPLOYMENT_SERVER_NOT_REGISTERED");if(matches.size()!=1)throw new ControlFailure(409,"DEPLOYMENT_SERVER_BINDING_AMBIGUOUS");
        var server=(Map<String,Object>)matches.getFirst();var installation=(Map<String,Object>)server.get("installation");
        if(!"SUCCEEDED".equals(installation.get("state"))||!"ACTIVE".equals(installation.get("clusterMembership")))throw new ControlFailure(409,"DEPLOYMENT_SERVER_NOT_ACTIVE");
        UUID serverId=UUID.fromString(server.get("id").toString());String root="/opt/eatwhat-sites/"+serverId;if(!root.equals(installation.get("root")))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_PATH_MISMATCH");
        String current=Objects.toString(installation.get("currentSha256"),"");if(!current.matches("[0-9a-f]{64}"))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_VERSION_UNKNOWN");
        ControlSsh.host(server.get("host").toString());ControlSsh.port(((Number)server.get("port")).intValue());ControlSsh.user(server.get("username").toString());ControlSsh.key(server.get("hostKey").toString());
        var target=new ControlProperties.DeploymentTarget();target.setServerId(serverId);target.setServerRevision(ControlServers.revision(server));target.setSiteId(expected.siteId());target.setInstanceId(expected.instanceId());target.setMembershipEpoch(epoch);target.setClusterId(local.getClusterId());target.setCurrentSha256(current);target.setRestoreEvidenceId(Objects.toString(installation.get("restoreEvidenceId"),""));target.setHost(server.get("host").toString());target.setUser(server.get("username").toString());target.setPort(((Number)server.get("port")).intValue());target.setAgent(root+"/deploy/cluster-release.py");target.setConfig(root+"/deploy/release.json");target.setDatabaseFingerprint(fp);
        target.setControlHost(expected.siteId().equals(local.getId())&&expected.instanceId().equals(local.getInstanceId())||"CONTROL_HOST".equals(server.get("location")));
        target.setAuthorityOrigin(authorityOrigin(target.isControlHost()));
        target.setAuthorityCaPem(authorityCaPem());
        var frozen=new LinkedHashMap<String,Object>();for(String key:List.of("id","revision","host","port","username","location","authentication","hostKey","credential","installation"))frozen.put(key,server.get(key));
        // Retain pre-increment fingerprints for old rows; explicit elevation is part of every new frozen command.
        if(server.containsKey("privilege"))frozen.put("privilege",ControlSsh.privilege(server));
        target.setConnectionFingerprint(store.fingerprint(json.writeValueAsString(frozen)));target.setEncryptedConnection(store.encrypt(json.writeValueAsString(frozen)));return target;
    }
    @SuppressWarnings("unchecked") @Override public Map<String,Object> call(String action,UUID id,ControlProperties.DeploymentTarget target,Path artifact,String sha)throws Exception{
        return call(action,id,target,artifact,sha,null);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> call(String action,UUID id,ControlProperties.DeploymentTarget target,Path artifact,String sha,String expectedRemoteState)throws Exception{
        return call(action,id,target,artifact,sha,expectedRemoteState,null,null);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> call(String action,UUID id,ControlProperties.DeploymentTarget target,Path artifact,String sha,String expectedRemoteState,UUID recoveryId,String recoveryProof)throws Exception{
        if(!available())throw new ControlFailure(503,"REGISTERED_DEPLOYMENT_PROTOCOL_NOT_READY");
        if(!Set.of("prepare","apply","status","seal","recovery-prepare","recover","recovery-status").contains(action))throw new ControlFailure(400,"INVALID_DEPLOYMENT_ACTION");
        boolean dispatch=Set.of("prepare","apply").contains(action);verifyReservation(id,target.getDeploymentManifestHash(),dispatch);
        var server=json.readValue(store.decrypt(target.getEncryptedConnection()),LinkedHashMap.class);if(!target.getServerId().toString().equals(server.get("id"))||target.getServerRevision()!=ControlServers.revision(server))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_BINDING_CHANGED");
        if(dispatch){
            var current=store.server(target.getServerId());ControlServers.expected(current,target.getServerRevision());var expected=resolve(new DeploymentExecutor.Target(target.getSiteId(),target.getSiteId(),target.getInstanceId()));
            if(!Objects.equals(expected.getConnectionFingerprint(),target.getConnectionFingerprint())||expected.getMembershipEpoch()!=target.getMembershipEpoch()||!Objects.equals(expected.getDatabaseFingerprint(),target.getDatabaseFingerprint())||!Objects.equals(expected.getAuthorityOrigin(),target.getAuthorityOrigin())||!Objects.equals(expected.getAuthorityCaPem(),target.getAuthorityCaPem()))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_BINDING_CHANGED");
        }
        var header=new LinkedHashMap<String,Object>();header.put("id",id.toString());header.put("serverId",target.getServerId().toString());header.put("siteId",target.getSiteId());header.put("instanceId",target.getInstanceId().toString());header.put("clusterId",target.getClusterId().toString());header.put("epoch",target.getMembershipEpoch());header.put("databaseFingerprint",target.getDatabaseFingerprint());header.put("artifactSha256",sha);header.put("fromSha256",target.getCurrentSha256());header.put("restoreEvidenceId",target.getRestoreEvidenceId());
        header.put("manifestHash",target.getDeploymentManifestHash());
        header.put("authorityOrigin",target.getAuthorityOrigin());if(target.getEncryptedAuthorityToken()!=null)header.put("authorityToken",store.decrypt(target.getEncryptedAuthorityToken()));
        if(target.getAuthorityCaPem()!=null)header.put("authorityCaPem",target.getAuthorityCaPem());
        if(target.getRemoteConfigurationProof()!=null)header.put("configurationProof",target.getRemoteConfigurationProof());
        if(action.equals("apply")){if(target.getRemoteConfigurationProof()==null||!target.getRemoteConfigurationProof().matches("[0-9a-f]{64}"))throw new ControlFailure(409,"REMOTE_CONFIGURATION_PROOF_REQUIRED");header.put("artifactBytes",Files.size(artifact));header.put("timeoutSeconds",settings.getDeployment().getTimeoutSeconds());}
        if(expectedRemoteState!=null)header.put("expectedRemoteState",expectedRemoteState);
        if(recoveryId!=null){header.put("recoveryRequestId",recoveryId.toString());if(recoveryProof!=null)header.put("recoveryProof",recoveryProof);if(action.equals("recover"))header.put("timeoutSeconds",settings.getDeployment().getTimeoutSeconds());}
        var reply=ssh.call(server,agentSource(),List.of(action.equals("apply")?"dispatch":action),out->{out.write(json.writeValueAsBytes(header));out.write('\n');if(action.equals("apply"))Files.copy(artifact,out);},action.equals("apply")?settings.getDeployment().getTimeoutSeconds():45);
        if(!"eatwhat-cluster-release-v1".equals(reply.get("protocol")))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_PROTOCOL_MISMATCH");
        for(String key:List.of("id","serverId","siteId","instanceId","clusterId","databaseFingerprint","artifactSha256","fromSha256","manifestHash"))if(!Objects.equals(header.get(key),reply.get(key)))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_BINDING_CHANGED");
        if(!(reply.get("epoch") instanceof Number epoch)||epoch.longValue()!=target.getMembershipEpoch())throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_BINDING_CHANGED");
        if(action.equals("prepare")){if(!(reply.get("configurationProof") instanceof String proof)||!proof.matches("[0-9a-f]{64}"))throw new ControlFailure(409,"REMOTE_CONFIGURATION_PROOF_REQUIRED");target.setRemoteConfigurationProof(proof);}
        return reply;
    }
    @SuppressWarnings("unchecked") @Override public void record(UUID id,ControlProperties.DeploymentTarget target,String sha,Map<String,Object> reply){
        if(!"SUCCEEDED".equals(reply.get("state")))return;
        if(!id.toString().equals(reply.get("id"))||!target.getSiteId().equals(reply.get("siteId"))||!target.getInstanceId().toString().equals(reply.get("instanceId"))||!sha.equals(reply.get("artifactSha256")))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_BINDING_CHANGED");
        store.mutate(data->{var server=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(target.getServerId().toString());if(server==null)throw new ControlFailure(409,"DEPLOYMENT_SERVER_NOT_REGISTERED");ControlServers.expected(server,target.getServerRevision());var installation=(Map<String,Object>)server.get("installation");
            if(!target.getSiteId().equals(installation.get("siteId"))||!target.getInstanceId().toString().equals(Objects.toString(installation.get("instanceId")))||!"ACTIVE".equals(installation.get("clusterMembership")))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_BINDING_CHANGED");
            if(sha.equals(installation.get("currentSha256"))&&id.toString().equals(installation.get("lastDeploymentJobId")))return null;
            if(!target.getCurrentSha256().equals(installation.get("currentSha256")))throw new ControlFailure(409,"REGISTERED_DEPLOYMENT_VERSION_CHANGED");
            var next=new LinkedHashMap<>(installation);next.put("currentSha256",sha);next.put("lastDeploymentJobId",id.toString());server.put("installation",next);return null;});
    }
}
