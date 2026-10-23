package you.v50to.eatwhat.cluster;

import you.v50to.eatwhat.control.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Dynamic control targets use retained identities and fixed profiles. Deployment targets bind to frozen membership identities. */
public final class ClusterControl {
    private final ClusterStore membership;private final ClusterProfiles profiles;private final ClusterHttpNodes nodes;private final ControlAudit audit;private final ControlProperties settings;
    private final DeploymentExecutor deployment;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public ClusterControl(ClusterStore membership,ClusterProfiles profiles,ClusterHttpNodes nodes,ControlAudit audit,ControlProperties settings){this(membership,profiles,nodes,audit,settings,new DeploymentExecutor(settings,audit));}
    public ClusterControl(ClusterStore membership,ClusterProfiles profiles,ClusterHttpNodes nodes,ControlAudit audit,ControlProperties settings,DeploymentExecutor deployment){this.membership=membership;this.profiles=profiles;this.nodes=nodes;this.audit=audit;this.settings=settings;this.deployment=deployment;}
    public Object fault(String actor,String site,String channel,String action,int seconds){if(!"isolated-acceptance".equals(settings.getEnvironmentKind()))throw new ClusterFailure(403,"FAULTS_REQUIRE_ISOLATED_ENVIRONMENT");membership.requireLeadership();ClusterStore.name(channel);if(!Set.of("BLOCK","RESTORE").contains(action)||seconds<1||seconds>300)throw new ClusterFailure(400,"INVALID_FAULT_REQUEST");var member=membership.member(site);if(action.equals("BLOCK")&&!member.get("state").equals("ACTIVE"))throw new ClusterFailure(409,"FAULT_TARGET_NOT_ACTIVE");var profile=profiles.get(membership.profile(site));if(!profile.member().instanceId().equals(member.get("instance_id")))throw new ClusterFailure(409,"FAULT_TARGET_IDENTITY_CHANGED");
        if(action.equals("BLOCK"))nodes.fingerprint(profile);UUID id=UUID.randomUUID();audit.append(id,actor,action,site+":"+channel,"REQUESTED");
        var result=nodes.call(profile,"control-fault",Map.of("requestId",id,"channel",channel,"action",action,"seconds",seconds));audit.append(id,actor,action,site+":"+channel,"APPLIED");return result;
    }
    public synchronized Map<String,Object> preview(String actor,UUID id,long epoch,String releaseId,List<String> sites){membership.requireLeadership();if(sites==null||sites.isEmpty()||sites.size()>16||new HashSet<>(sites).size()!=sites.size())throw new ClusterFailure(400,"INVALID_DEPLOYMENT_TARGETS");String hash=ClusterStore.hash(List.of(epoch,releaseId,sites));Path path=audit.directory().resolve("cluster-preview-"+id+".json");
        try{if(Files.exists(path)){var old=JSON.readValue(Files.readString(path),Map.class);if(!hash.equals(old.get("requestHash")))throw new ClusterFailure(409,"IDEMPOTENCY_CONFLICT");return old;}}catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new ClusterFailure(503,"PREVIEW_STORAGE_UNAVAILABLE");}
        if(((Number)membership.status().get("epoch")).longValue()!=epoch)throw new ClusterFailure(409,"STALE_MEMBERSHIP_EPOCH");var active=membership.active();var members=sites.stream().map(site->active.stream().filter(m->m.siteId().equals(site)).findFirst().orElseThrow(()->new ClusterFailure(409,"DEPLOYMENT_TARGET_NOT_ACTIVE"))).toList();
        var release=settings.getReleases().get(releaseId);if(release==null)throw new ClusterFailure(404,"RELEASE_NOT_CONFIGURED");String sha;try(var input=Files.newInputStream(release.getArtifact())){var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);sha=HexFormat.of().formatHex(digest.digest());}catch(Exception failure){throw new ClusterFailure(409,"ARTIFACT_UNAVAILABLE");}if(!sha.equals(release.getSha256()))throw new ClusterFailure(409,"ARTIFACT_SHA256_MISMATCH");
        var plan=new LinkedHashMap<String,Object>();plan.put("id",id);plan.put("requestHash",hash);plan.put("createdAt",Instant.now());plan.put("epoch",epoch);plan.put("releaseId",releaseId);plan.put("artifactSha256",sha);plan.put("members",members);plan.put("configurationHash",deployment.configurationHash(releaseId,targets(members)));plan.put("state","PREVIEW_ONLY");plan.put("executionEnabled",deployment.enabled() && deployment.blockers(releaseId,targets(members)).isEmpty());plan.put("blockers",deployment.blockers(releaseId,targets(members)));plan.put("steps",List.of("VERIFY_FROZEN_MEMBERSHIP_AND_ARTIFACT","VERIFY_BACKUP_RESTORE_AND_MIGRATION","DEPLOY_ONE_SITE_AT_A_TIME","VERIFY_RUNTIME_ARTIFACT_AND_READINESS"));audit.append(id,actor,"CLUSTER_DEPLOYMENT_PREVIEW",releaseId,"PREVIEW_ONLY");
        try{Path tmp=Files.createTempFile(audit.directory(),"cluster-preview-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.writeString(tmp,JSON.writeValueAsString(plan));Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(tmp);}}catch(Exception failure){throw new ClusterFailure(503,"PREVIEW_STORAGE_UNAVAILABLE");}return plan;
    }
    public boolean deploymentEnabled(){return deployment.enabled();}
    public boolean deploymentRecoveryEnabled(){return deployment.recoverySupported();}
    public Map<String,Object> controllerRuntimeArtifact(){return deployment.runtimeArtifact();}
    private List<DeploymentExecutor.Target> targets(List<ClusterMember> members){return members.stream().map(m->new DeploymentExecutor.Target(m.siteId(),m.siteId(),m.instanceId())).toList();}
    @SuppressWarnings("unchecked") public Object execute(String actor,UUID id){
        membership.requireLeadership();var plan=preview(id);long epoch=((Number)plan.get("epoch")).longValue();
        var frozen=((List<Map<String,Object>>)plan.get("members")).stream().map(m->new ClusterMember((String)m.get("siteId"),UUID.fromString((String)m.get("instanceId")),(String)m.get("mode"),((Number)m.get("allocationSlot")).intValue())).toList();
        if(deployment.enabled() && (!Boolean.TRUE.equals(plan.get("executionEnabled")) || !Objects.equals(plan.get("configurationHash"),deployment.configurationHash((String)plan.get("releaseId"),targets(frozen)))))throw new ClusterFailure(409,"DEPLOYMENT_PLAN_RECREATE_REQUIRED");
        return deployment.execute(actor,id,(String)plan.get("releaseId"),(String)plan.get("artifactSha256"),targets(frozen),(String)plan.get("configurationHash"),()->{
            membership.requireLeadership();
            if(((Number)membership.status().get("epoch")).longValue()!=epoch || !membership.active().containsAll(frozen))throw new ClusterFailure(409,"STALE_MEMBERSHIP_EPOCH");
            for(var member:frozen){var profile=profiles.get(membership.profile(member.siteId()));if(!profile.member().equals(member))throw new ClusterFailure(409,"DEPLOYMENT_TARGET_IDENTITY_CHANGED");}
        });
    }
    public Object channels(){return profiles.list().stream().map(meta->{var profile=profiles.get((String)meta.get("id"));return Map.of("siteId",profile.member().siteId(),"instanceId",profile.member().instanceId(),"channels",profile.channels(),"routingVerified",false);}).toList();}
    public Map<String,Object> preview(UUID id){Path path=audit.directory().resolve("cluster-preview-"+id+".json");try{if(Files.isSymbolicLink(path)||Files.size(path)>1024*1024)throw new Exception();return JSON.readValue(Files.readString(path),Map.class);}catch(Exception failure){throw new ClusterFailure(404,"PREVIEW_NOT_FOUND");}}
}
