package you.v50to.eatwhat.control;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Accepted remote activation is dispatched once; membership advances only through the genuine coordinator. */
public final class ControlRemoteEnrollment implements SmartLifecycle {
    static final String KIND="REMOTE_MEMBER";
    static final String RECOVERY_KIND="REMOTE_MEMBER_CONFIGURATION_RECOVERY";
    static boolean owns(Object kind){return KIND.equals(kind)||RECOVERY_KIND.equals(kind);}
    private final ControlServerStore store;private final ControlSsh ssh;private final ControlServers servers;private final DeploymentExecutor deployment;private final ControlProperties settings;private final ControlAudit audit;private final SiteProperties site;private final JdbcTemplate jdbc;
    private final ObjectProvider<ClusterStore> membership;private final ObjectProvider<ClusterProfiles> profiles;private final ObjectProvider<ClusterMembershipCoordinator> coordinator;private final ObjectProvider<ClusterProperties> cluster;private final ObjectProvider<ClusterHttpNodes> nodes;
    private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();private ScheduledExecutorService worker;
    public ControlRemoteEnrollment(ControlServerStore store,ControlSsh ssh,ControlServers servers,DeploymentExecutor deployment,ControlProperties settings,ControlAudit audit,SiteProperties site,JdbcTemplate jdbc,ObjectProvider<ClusterStore> membership,ObjectProvider<ClusterProfiles> profiles,ObjectProvider<ClusterMembershipCoordinator> coordinator,ObjectProvider<ClusterProperties> cluster,ObjectProvider<ClusterHttpNodes> nodes){this.store=store;this.ssh=ssh;this.servers=servers;this.deployment=deployment;this.settings=settings;this.audit=audit;this.site=site;this.jdbc=jdbc;this.membership=membership;this.profiles=profiles;this.coordinator=coordinator;this.cluster=cluster;this.nodes=nodes;}
    private Path planPath(UUID id){return store.directory().resolve("server-cluster-plan-"+id+".json");}
    private Path jobPath(UUID id){return store.directory().resolve("server-cluster-job-"+id+".json");}
    private String admin(String actor){var users=jdbc.queryForList("SELECT id,nick_name,password_hash FROM users WHERE id=? AND role='admin' AND NOT banned",Long.parseLong(actor));if(users.size()!=1)throw new ControlFailure(403,"ADMIN_REQUIRED");return store.fingerprint(json.writeValueAsString(users.getFirst()));}
    private String agent(){String recovery=ControlServers.resource("first-install-recovery.py").replace("__EATWHAT_BOOTSTRAP_BASE64__",Base64.getEncoder().encodeToString(ControlServers.resource("first-install.py").getBytes(java.nio.charset.StandardCharsets.UTF_8)));return ControlServers.resource("member-configure.py").replace("__EATWHAT_RECOVERY_BASE64__",Base64.getEncoder().encodeToString(recovery.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private String recoveryAgent(){return ControlServers.resource("member-configuration-recovery.py").replace("__EATWHAT_MEMBER_BASE64__",Base64.getEncoder().encodeToString(agent().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private String agent(Map<String,Object> payload){return payload.containsKey("failedId")?recoveryAgent():agent();}
    private ClusterMember target(Map<String,Object> installation){try{return new ClusterMember(installation.get("siteId").toString(),UUID.fromString(installation.get("instanceId").toString()),installation.get("mode").toString(),((Number)installation.get("allocationSlot")).intValue());}catch(Exception failure){throw new ControlFailure(409,"INSTALLED_MEMBER_IDENTITY_REQUIRED");}}
    @SuppressWarnings("unchecked") private ControlMemberConfiguration.Prepared configuration(UUID serverId,Map<String,Object> installation){
        var m=membership.getObject();var status=m.status();var retained=(List<Map<String,Object>>)status.get("members");
        var authorities=retained.stream().filter(r->"CAMPUS".equals(r.get("mode"))&&((Number)r.get("allocation_slot")).intValue()==0&&"ACTIVE".equals(r.get("state"))).toList();
        if(authorities.size()!=1)throw new ControlFailure(409,"ACTIVE_AUTHORITY_REQUIRED");var a=authorities.getFirst();var authority=new ClusterMember(a.get("site_id").toString(),UUID.fromString(a.get("instance_id").toString()),"CAMPUS",0);
        if(!site.getId().equals(authority.siteId())||!site.getInstanceId().equals(authority.instanceId())||!site.getClusterId().toString().equals(installation.get("clusterId")))throw new ControlFailure(409,"MEMBER_CLUSTER_BINDING_MISMATCH");
        var t=target(installation);var allocation=m.installationAllocation(serverId);
        if(!Objects.equals(site.getClusterId(),allocation.get("cluster_id"))||!Objects.equals(t.siteId(),allocation.get("site_id"))||!Objects.equals(t.instanceId(),allocation.get("instance_id"))||!Objects.equals(t.mode(),allocation.get("mode"))||!(allocation.get("allocation_slot") instanceof Number slot)||t.allocationSlot()!=slot.intValue())throw new ControlFailure(409,"MEMBER_ALLOCATION_RESERVATION_REQUIRED");
        return new ControlMemberConfiguration(settings).prepare(serverId,site.getClusterId(),t,authority,profiles.getObject());
    }
    private void fullAuthority(){
        var c=cluster.getIfAvailable();if(c==null||!c.isEnabled()||!c.isCoordinator()||c.isSingleSiteOnly())throw new ControlFailure(409,"MULTISITE_AUTHORITY_SETUP_REQUIRED");
        try{
            var catalog=profiles.getObject();var authorities=catalog.list().stream().map(p->catalog.get(p.get("id").toString()))
                    .filter(p->p.member().mode().equals("CAMPUS")&&p.member().allocationSlot()==0).toList();
            if(authorities.size()!=1||!authorities.getFirst().member().siteId().equals(site.getId())||!authorities.getFirst().member().instanceId().equals(site.getInstanceId()))throw new ControlFailure(409,"ACTIVE_AUTHORITY_REQUIRED");
            var observed=nodes.getObject().call(authorities.getFirst(),"probe",null);
            if(!"MULTI_SITE".equals(observed.get("dataPlaneMode"))||!Boolean.TRUE.equals(observed.get("dataPlaneReady"))
                    ||!Objects.equals(1,observed.get("protocolVersion")))throw new ControlFailure(409,"AUTHORITY_DATA_PLANE_NOT_READY");
        }catch(ControlFailure failure){throw failure;}catch(Exception unavailable){throw new ControlFailure(503,"AUTHORITY_DATA_PLANE_UNAVAILABLE");}
    }
    @SuppressWarnings("unchecked") public Map<String,Object> create(String actor,UUID serverId,long revision){
        servers.requireWrite();var s=store.server(serverId);ControlServers.expected(s,revision);var installation=(Map<String,Object>)s.get("installation");var blockers=new ArrayList<String>();
        UUID id=UUID.randomUUID();var p=new LinkedHashMap<String,Object>();p.put("id",id.toString());p.put("kind",KIND);p.put("serverId",serverId.toString());p.put("serverRevision",revision);p.put("singleSiteOnly",false);p.put("controllerRestartExpected",false);p.put("originalDatabasePreserved",true);p.put("expiresAt",Instant.now().plusSeconds(900).toString());p.put("_actor",actor);p.put("_adminCheck",admin(actor));
        for(String key:List.of("siteId","instanceId","clusterId","allocationSlot","mode","currentSha256"))if(installation.containsKey(key))p.put(key.equals("currentSha256")?"artifactSha256":key,installation.get(key));
        if(!settings.getServers().isClusterEnrollmentEnabled())blockers.add("CLUSTER_ENROLLMENT_DISABLED");
        if(!ControlSsh.rootExecution(s))blockers.add("MEMBER_CONFIGURATION_ROOT_REQUIRED");
        if(!"SUCCEEDED".equals(installation.get("state"))||!"EXTERNAL".equals(installation.get("mode"))||!"NOT_JOINED".equals(installation.get("clusterMembership")))blockers.add("UNJOINED_EXTERNAL_INSTALLATION_REQUIRED");
        try{fullAuthority();}catch(ControlFailure f){blockers.add(f.code);}
        if(blockers.isEmpty())try{
            var m=membership.getObject();var status=m.status();if(!((List<?>)status.get("pendingChanges")).isEmpty())throw new ControlFailure(409,"MEMBERSHIP_CHANGE_PENDING");
            p.put("baseEpoch",status.get("epoch"));if(((Number)status.get("epoch")).longValue()<1)throw new ControlFailure(409,"ACTIVE_AUTHORITY_REQUIRED");
            var prepared=configuration(serverId,installation);p.put("profileId",prepared.profileId());
            // No previous registered, retired or active identity may be silently adopted.
            if(((List<Map<String,Object>>)status.get("members")).stream().anyMatch(row->prepared.member().siteId().equals(row.get("site_id"))||prepared.member().instanceId().equals(row.get("instance_id"))||prepared.member().allocationSlot()==((Number)row.get("allocation_slot")).intValue()))throw new ControlFailure(409,"MEMBER_ALREADY_RETAINED");
            var payload=new LinkedHashMap<String,Object>();for(String key:List.of("id","serverId","siteId","instanceId","clusterId","allocationSlot","artifactSha256"))payload.put(key,p.get(key));payload.put("runtime",prepared.privatePayload());
            var checked=call(s,payload,"inspect");verify(checked,payload);if(!Boolean.TRUE.equals(checked.get("ready"))||!(checked.get("freezeHash") instanceof String hash)||!hash.matches("[0-9a-f]{64}")||!(checked.get("databaseFingerprint") instanceof String fp)||!fp.matches("[0-9a-f]{32}"))throw new Exception();
            payload.put("freezeHash",checked.get("freezeHash"));p.put("_payload",store.encrypt(json.writeValueAsString(payload)));p.put("_installation",new LinkedHashMap<>(installation));p.put("_configurationHash",store.fingerprint(json.writeValueAsString(prepared.privatePayload())));p.put("_agentHash",store.fingerprint(agent()));p.put("_databaseFingerprint",checked.get("databaseFingerprint"));
        }catch(ControlFailure f){blockers.add(f.code);}catch(Exception failure){blockers.add("MEMBER_CONFIGURATION_PREFLIGHT_FAILED");}
        p.put("blockers",blockers);p.put("state",blockers.isEmpty()?"READY":"BLOCKED");String reason=deployment.whenIdle(()->{servers.requireWrite();ControlServers.expected(store.server(serverId),revision);store.saveJson(planPath(id),p);audit.append(id,actor,"SERVER_CLUSTER_PLAN",serverId.toString(),p.get("state").toString());});if(reason!=null)throw new ControlFailure(409,reason);return ControlInstallExecutor.publicPlan(p);
    }
    /** New approval restores only the original unjoined configuration; it never repeats the old JOIN. */
    @SuppressWarnings("unchecked") public Map<String,Object> createRecovery(String actor,UUID failedId){
        servers.requireWrite();if(!Files.exists(jobPath(failedId)))throw new ControlFailure(404,"AUTHORITY_JOB_NOT_FOUND");var original=store.readJson(jobPath(failedId));
        if(!KIND.equals(original.get("kind"))||!"FAILED".equals(original.get("state"))||!"MEMBER_CONFIGURATION_FAILED_PRESERVED".equals(original.get("code")))throw new ControlFailure(409,"FAILED_MEMBER_CONFIGURATION_REQUIRED");
        var previous=(Map<String,Object>)original.get("_plan");var serverId=UUID.fromString(original.get("serverId").toString());var s=store.server(serverId);var installation=(Map<String,Object>)s.get("installation");
        if(!Objects.equals(previous.get("_installation"),installation))throw new ControlFailure(409,"MEMBER_PLAN_CHANGED");
        UUID id=UUID.randomUUID();var p=new LinkedHashMap<String,Object>();
        for(String key:List.of("serverId","siteId","instanceId","clusterId","allocationSlot","artifactSha256","serverRevision"))p.put(key,previous.get(key));
        p.put("serverRevision",ControlServers.revision(s));p.put("id",id.toString());p.put("failedId",failedId.toString());p.put("kind",RECOVERY_KIND);p.put("singleSiteOnly",false);p.put("controllerRestartExpected",false);p.put("originalDatabasePreserved",true);p.put("newEnrollmentPlanRequired",true);p.put("expiresAt",Instant.now().plusSeconds(900).toString());p.put("_actor",actor);p.put("_adminCheck",admin(actor));p.put("_installation",new LinkedHashMap<>(installation));p.put("_sourceHash",store.fingerprint(json.writeValueAsString(original)));p.put("_agentHash",store.fingerprint(recoveryAgent()));
        var blockers=new ArrayList<String>();
        if(!settings.getServers().isClusterEnrollmentEnabled())blockers.add("CLUSTER_ENROLLMENT_DISABLED");
        if(!ControlSsh.rootExecution(s))blockers.add("MEMBER_CONFIGURATION_ROOT_REQUIRED");
        try{
            fullAuthority();unretained(p);p.put("baseEpoch",membership.getObject().status().get("epoch"));
            if(blockers.isEmpty()){
                var payload=new LinkedHashMap<String,Object>();for(String key:List.of("id","failedId","serverId","siteId","instanceId","clusterId","allocationSlot","artifactSha256"))payload.put(key,p.get(key));
                var checked=call(s,payload,"inspect");verify(checked,payload);
                if(!Boolean.TRUE.equals(checked.get("ready"))||!"NOT_JOINED".equals(checked.get("clusterMembership"))||!(checked.get("freezeHash") instanceof String hash)||!hash.matches("[0-9a-f]{64}")||!Objects.equals(previous.get("_databaseFingerprint"),checked.get("databaseFingerprint")))throw new ControlFailure(409,"MEMBER_RECOVERY_PREFLIGHT_FAILED");
                payload.put("freezeHash",checked.get("freezeHash"));p.put("_payload",store.encrypt(json.writeValueAsString(payload)));p.put("_databaseFingerprint",checked.get("databaseFingerprint"));
            }
        }catch(ControlFailure f){blockers.add(f.code);}catch(Exception unavailable){blockers.add("MEMBER_RECOVERY_PREFLIGHT_FAILED");}
        p.put("blockers",blockers);p.put("state",blockers.isEmpty()?"READY":"BLOCKED");
        String reason=deployment.whenIdle(()->{servers.requireWrite();ControlServers.expected(store.server(serverId),((Number)p.get("serverRevision")).longValue());store.saveJson(planPath(id),p);audit.append(id,actor,"SERVER_CLUSTER_RECOVERY_PLAN",serverId.toString(),p.get("state").toString());});
        if(reason!=null)throw new ControlFailure(409,reason);return ControlInstallExecutor.publicPlan(p);
    }
    @SuppressWarnings("unchecked") private void unretained(Map<String,Object> p){
        if(!Objects.toString(site.getClusterId()).equals(p.get("clusterId")))throw new ControlFailure(409,"MEMBER_CLUSTER_BINDING_MISMATCH");
        var status=membership.getObject().status();if(!((List<?>)status.get("pendingChanges")).isEmpty())throw new ControlFailure(409,"MEMBERSHIP_CHANGE_PENDING");
        if(((List<Map<String,Object>>)status.get("members")).stream().anyMatch(row->Objects.equals(p.get("siteId"),row.get("site_id"))||Objects.equals(p.get("instanceId"),Objects.toString(row.get("instance_id")))||((Number)p.get("allocationSlot")).intValue()==((Number)row.get("allocation_slot")).intValue()))throw new ControlFailure(409,"MEMBER_ALREADY_RETAINED");
    }
    @SuppressWarnings("unchecked") private void validate(Map<String,Object> p,boolean acceptance){
        if(acceptance)servers.requireWrite();else servers.requireExecuting();fullAuthority();
        var serverId=UUID.fromString(p.get("serverId").toString());var s=store.server(serverId);ControlServers.expected(s,((Number)p.get("serverRevision")).longValue());
        boolean sameInstallation=p.get("_installation").equals(s.get("installation"));
        if(RECOVERY_KIND.equals(p.get("kind"))){
            if(!sameInstallation||!p.get("_adminCheck").equals(admin(p.get("_actor").toString()))||!p.get("_agentHash").equals(store.fingerprint(recoveryAgent()))||!p.get("_sourceHash").equals(store.fingerprint(json.writeValueAsString(store.readJson(jobPath(UUID.fromString(p.get("failedId").toString())))))))throw new ControlFailure(409,"MEMBER_PLAN_CHANGED");
            unretained(p);return;
        }
        if(!sameInstallation&&!acceptance){
            var current=new LinkedHashMap<>((Map<String,Object>)s.get("installation"));
            if("ACTIVE".equals(current.get("clusterMembership"))){current.put("clusterMembership","NOT_JOINED");sameInstallation=current.equals(p.get("_installation"))&&"APPLIED".equals(membership.getObject().change(UUID.fromString(p.get("id").toString())).get("state"));}
        }
        if(!p.get("_adminCheck").equals(admin(p.get("_actor").toString()))||!p.get("_agentHash").equals(store.fingerprint(agent()))||!sameInstallation)throw new ControlFailure(409,"MEMBER_PLAN_CHANGED");
        var prepared=configuration(serverId,(Map<String,Object>)s.get("installation"));if(!p.get("_configurationHash").equals(store.fingerprint(json.writeValueAsString(prepared.privatePayload()))))throw new ControlFailure(409,"MEMBER_PLAN_CHANGED");
    }
    public Map<String,Object> execute(String actor,UUID id){
        servers.requireWrite();if(Files.exists(jobPath(id)))return view(id);var p=store.readJson(planPath(id));if(!owns(p.get("kind"))||!"READY".equals(p.get("state")))throw new ControlFailure(409,"MEMBER_PLAN_BLOCKED");
        if(!settings.getServers().isClusterEnrollmentEnabled())throw new ControlFailure(409,"CLUSTER_ENROLLMENT_DISABLED");if(!p.get("_actor").equals(actor)||Instant.now().isAfter(Instant.parse(p.get("expiresAt").toString())))throw new ControlFailure(409,"MEMBER_PLAN_EXPIRED_OR_CHANGED");
        String reason=deployment.whenIdle(()->{
            if(Files.exists(jobPath(id)))return;
            validate(p,true);if(((Number)membership.getObject().status().get("epoch")).longValue()!=((Number)p.get("baseEpoch")).longValue())throw new ControlFailure(409,"STALE_MEMBERSHIP_EPOCH");
            var j=new LinkedHashMap<String,Object>();for(String key:List.of("id","kind","serverId","siteId","instanceId","clusterId","allocationSlot","artifactSha256","singleSiteOnly","controllerRestartExpected"))j.put(key,p.get(key));if(p.containsKey("failedId"))j.put("failedId",p.get("failedId"));j.put("state","QUEUED");j.put("stage",RECOVERY_KIND.equals(p.get("kind"))?"MEMBER_CONFIGURATION_RECOVERY":"MEMBER_CONFIGURATION");j.put("createdAt",Instant.now().toString());j.put("_plan",p);audit.append(id,actor,"SERVER_CLUSTER_EXECUTE",p.get("serverId").toString(),"ACCEPTED");store.saveJson(jobPath(id),j);
            store.mutate(data->{@SuppressWarnings("unchecked") var s=(Map<String,Object>)((Map<?,?>)data.get("servers")).get(p.get("serverId"));s.put("clusterJobId",id.toString());return null;});
        });if(reason!=null&&!Files.exists(jobPath(id)))throw new ControlFailure(409,reason);return view(id);
    }
    private Map<String,Object> view(UUID id){return ControlInstallExecutor.publicPlan(store.readJson(jobPath(id)));}
    private void verify(Map<String,Object> result,Map<String,Object> payload){if(!(payload.containsKey("failedId")?"eatwhat-member-configuration-recovery-v1":"eatwhat-member-configure-v1").equals(result.get("protocol"))||!payload.get("id").equals(result.get("id"))||!payload.get("serverId").equals(result.get("serverId")))throw new ControlFailure(409,"MEMBER_RECEIPT_MISMATCH");}
    private Map<String,Object> call(Map<String,Object> server,Map<String,Object> payload,String action)throws Exception{var h=new LinkedHashMap<>(payload);if(action.equals("dispatch"))h.put("agent",agent(payload));return ssh.call(server,agent(payload),List.of(action),out->{out.write(json.writeValueAsBytes(h));out.write('\n');},60);}
    @SuppressWarnings("unchecked") private void advance(UUID id,boolean continueWork){
        var j=store.readJson(jobPath(id));if(!owns(j.get("kind"))||Set.of("SUCCEEDED","FAILED").contains(j.get("state")))return;var p=(Map<String,Object>)j.get("_plan");
        try{
            if(continueWork)validate(p,false);var s=store.server(UUID.fromString(p.get("serverId").toString()));ControlServers.expected(s,((Number)p.get("serverRevision")).longValue());
            var payload=json.readValue(store.decrypt(p.get("_payload").toString()),Map.class);
            if("QUEUED".equals(j.get("state"))&&continueWork){
                if(((Number)membership.getObject().status().get("epoch")).longValue()!=((Number)p.get("baseEpoch")).longValue())throw new ControlFailure(409,"STALE_MEMBERSHIP_EPOCH");
                j.put("state","RUNNING");store.saveJson(jobPath(id),j);var accepted=call(s,payload,"dispatch");verify(accepted,payload);
                if("REJECTED".equals(accepted.get("state"))&&Boolean.FALSE.equals(accepted.get("effectsStarted"))){j.put("state","FAILED");j.put("code","MEMBER_PLAN_CHANGED");store.saveJson(jobPath(id),j);return;}
                if(!Set.of("ACCEPTED","CONFIGURED","RESTORED").contains(accepted.get("state")))throw new Exception();
            }
            var receipt=call(s,payload,"status");verify(receipt,payload);
            if(Set.of("OUTCOME_UNKNOWN","NOT_STARTED").contains(receipt.get("state"))&&!"QUEUED".equals(j.get("state"))){j.put("state","OUTCOME_UNKNOWN");j.put("code","MEMBER_RECONCILIATION_REQUIRED");store.saveJson(jobPath(id),j);return;}
            if("FAILED".equals(receipt.get("state"))){j.put("state","FAILED");j.put("code",RECOVERY_KIND.equals(j.get("kind"))?"MEMBER_RECOVERY_FAILED_PRESERVED":"MEMBER_CONFIGURATION_FAILED_PRESERVED");store.saveJson(jobPath(id),j);return;}
            if(RECOVERY_KIND.equals(j.get("kind"))){
                if(!"RESTORED".equals(receipt.get("state")))return;
                for(String key:List.of("failedId","siteId","instanceId","clusterId","allocationSlot","artifactSha256"))if(!Objects.equals(payload.get(key),receipt.get(key)))throw new Exception();
                if(!p.get("_databaseFingerprint").equals(receipt.get("databaseFingerprint"))||!"NOT_JOINED".equals(receipt.get("clusterMembership"))||!Boolean.TRUE.equals(receipt.get("originalDatabasePreserved"))||!Boolean.TRUE.equals(receipt.get("newEnrollmentPlanRequired")))throw new Exception();
                j.put("state","SUCCEEDED");j.put("stage","VERIFIED");j.remove("code");j.put("result",Map.of("clusterMembership","NOT_JOINED","originalDatabasePreserved",true,"newEnrollmentPlanRequired",true,"failedId",p.get("failedId")));store.saveJson(jobPath(id),j);audit.append(id,p.get("_actor").toString(),"SERVER_CLUSTER_RECOVERY",p.get("serverId").toString(),"RESTORED_NOT_JOINED");return;
            }
            if(!"CONFIGURED".equals(receipt.get("state")))return;
            for(String key:List.of("siteId","instanceId","clusterId","allocationSlot","artifactSha256"))if(!Objects.equals(payload.get(key),receipt.get(key)))throw new Exception();
            if(!p.get("_databaseFingerprint").equals(receipt.get("databaseFingerprint")))throw new Exception();
            var m=membership.getObject();if(continueWork){
                j.put("state","RUNNING");j.put("stage","MEMBERSHIP_JOIN");store.saveJson(jobPath(id),j);long epoch=((Number)p.get("baseEpoch")).longValue();
                var co=coordinator.getObject();co.register(p.get("_actor").toString(),UUID.nameUUIDFromBytes((id+":register").getBytes(java.nio.charset.StandardCharsets.UTF_8)),epoch,p.get("profileId").toString());co.begin(p.get("_actor").toString(),id,epoch,"JOIN",p.get("siteId").toString());co.reconcile(p.get("_actor").toString(),id);
            }
            var change=m.change(id);var member=m.member(p.get("siteId").toString());if(!"APPLIED".equals(change.get("state"))||!"ACTIVE".equals(member.get("state"))||!p.get("_databaseFingerprint").equals(member.get("database_fingerprint")))return;
            var actual=nodes.getObject().call(profiles.getObject().get(p.get("profileId").toString()),"probe",null);var local=(Map<?,?>)actual.get("membership");if(!"ACTIVE".equals(local.get("state"))||local.get("pending_change")!=null||((Number)local.get("epoch")).longValue()!=((Number)p.get("baseEpoch")).longValue()+1)return;
            store.mutate(data->{var server=(Map<String,Object>)((Map<?,?>)data.get("servers")).get(p.get("serverId"));ControlServers.expected(server,((Number)p.get("serverRevision")).longValue());((Map<String,Object>)server.get("installation")).put("clusterMembership","ACTIVE");return null;});
            j.put("state","SUCCEEDED");j.put("stage","VERIFIED");j.remove("code");j.put("result",Map.of("clusterMembership","ACTIVE","changeState","APPLIED","epoch",local.get("epoch"),"siteId",p.get("siteId"),"instanceId",p.get("instanceId"),"singleSiteOnly",false,"originalDatabasePreserved",true));store.saveJson(jobPath(id),j);audit.append(id,p.get("_actor").toString(),"SERVER_CLUSTER_JOIN",p.get("serverId").toString(),"VERIFIED_APPLIED_ACTIVE");
        }catch(Exception unavailable){
            if("QUEUED".equals(j.get("state"))){
                // Only an owned worker may reject a command which has never
                // crossed the durable RUNNING boundary. Transient dependency
                // failures and standby processes must leave it queued.
                String rejection=queuedRejection(unavailable);
                if(continueWork&&rejection!=null){
                    servers.requireExecuting();
                    audit.append(id,p.get("_actor").toString(),"SERVER_CLUSTER_PREFLIGHT_REJECT",p.get("serverId").toString(),rejection);
                    j.put("state","FAILED");j.put("stage","COMPLETE");j.put("failureStage","MEMBER_CONFIGURATION");j.put("code",rejection);j.put("effectsStarted",false);j.put("updatedAt",Instant.now().toString());store.saveJson(jobPath(id),j);
                }
            }else{j.put("state","OUTCOME_UNKNOWN");j.put("code","MEMBER_RECONCILIATION_REQUIRED");store.saveJson(jobPath(id),j);}
        }
    }
    private static String queuedRejection(Exception failure){
        if(!(failure instanceof ControlFailure rejected))return null;
        return Set.of("MEMBER_PLAN_CHANGED","SERVER_REVISION_CHANGED","MEMBER_ALLOCATION_RESERVATION_REQUIRED","MEMBER_CLUSTER_BINDING_MISMATCH","STALE_MEMBERSHIP_EPOCH","ADMIN_REQUIRED","MEMBER_ALREADY_RETAINED").contains(rejected.code)?rejected.code:null;
    }
    public Map<String,Object> reconcile(UUID id){
        try(var channel=FileChannel.open(store.directory().resolve("deployment.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){return view(id);}if(lock!=null)try(lock){advance(id,false);}
        }catch(java.io.IOException failure){throw new ControlFailure(503,"DEPLOYMENT_STATE_UNAVAILABLE");}return view(id);
    }
    void cycle(){try(var paths=Files.list(store.directory())){for(var path:paths.filter(p->p.getFileName().toString().matches("server-cluster-job-[0-9a-f-]{36}\\.json")).toList()){
        var j=store.readJson(path);if(!owns(j.get("kind"))||!Set.of("QUEUED","RUNNING","OUTCOME_UNKNOWN").contains(j.get("state")))continue;
        try(var channel=FileChannel.open(store.directory().resolve("deployment.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){continue;}if(lock!=null)try(lock){advance(UUID.fromString(j.get("id").toString()),true);}}
    }}catch(Exception unavailable){/* Durable accepted state remains pending. Never retry an uncertain dispatch. */}}
    public synchronized void start(){if(worker==null){worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"control-member-enrollment");t.setDaemon(true);return t;});worker.scheduleWithFixedDelay(this::cycle,2,4,TimeUnit.SECONDS);}}
    public synchronized void stop(){if(worker!=null){worker.shutdownNow();worker=null;}}
    public boolean isRunning(){return worker!=null&&!worker.isShutdown();}
    public int getPhase(){return 1100;}
}
