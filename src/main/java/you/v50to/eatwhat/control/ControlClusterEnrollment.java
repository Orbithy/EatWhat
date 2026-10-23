package you.v50to.eatwhat.control;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import tools.jackson.databind.json.JsonMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Durable initial-authority enrollment. An accepted command survives the intentional control restart. */
public final class ControlClusterEnrollment implements SmartLifecycle {
    private final ControlServerStore store;private final ControlSsh ssh;private final ControlServers servers;
    private final DeploymentExecutor deployment;private final ControlProperties settings;private final ControlAudit audit;
    private final SiteProperties site;private final JdbcTemplate jdbc;private final ObjectProvider<ClusterStore> membership;
    private final ObjectProvider<ClusterMembershipCoordinator> coordinator;private final ObjectProvider<ClusterProperties> cluster;
    private final JsonMapper json=JsonMapper.builder().build();private ScheduledExecutorService worker;
    private ControlRemoteEnrollment remote;
    public ControlClusterEnrollment remote(ControlRemoteEnrollment value){this.remote=Objects.requireNonNull(value);return this;}
    public ControlClusterEnrollment(ControlServerStore store,ControlSsh ssh,ControlServers servers,DeploymentExecutor deployment,ControlProperties settings,ControlAudit audit,SiteProperties site,JdbcTemplate jdbc,ObjectProvider<ClusterStore> membership,ObjectProvider<ClusterMembershipCoordinator> coordinator,ObjectProvider<ClusterProperties> cluster){this.store=store;this.ssh=ssh;this.servers=servers;this.deployment=deployment;this.settings=settings;this.audit=audit;this.site=site;this.jdbc=jdbc;this.membership=membership;this.coordinator=coordinator;this.cluster=cluster;}
    public Map<String,Object> createRecovery(String actor,UUID failedId){if(remote==null)throw new ControlFailure(409,"CLUSTER_ENROLLMENT_DISABLED");return remote.createRecovery(actor,failedId);}
    public boolean enabled(){return settings.getServers().isClusterEnrollmentEnabled();}
    private Path planPath(UUID id){return store.directory().resolve("server-cluster-plan-"+id+".json");}
    private Path jobPath(UUID id){return store.directory().resolve("server-cluster-job-"+id+".json");}
    private static Map<String,Object> publicView(Map<String,Object> input){var result=new LinkedHashMap<>(input);result.keySet().removeIf(k->k.startsWith("_"));return result;}
    public Map<String,Object> plan(UUID id){if(!Files.exists(planPath(id)))throw new ControlFailure(404,"SERVER_PLAN_NOT_FOUND");return publicView(store.readJson(planPath(id)));}
    public Map<String,Object> job(UUID id){if(!Files.exists(jobPath(id)))throw new ControlFailure(404,"AUTHORITY_JOB_NOT_FOUND");return publicView(store.readJson(jobPath(id)));}
    private String adminCheck(String actor){var rows=jdbc.queryForList("SELECT id,nick_name,password_hash FROM users WHERE id=? AND role='admin' AND NOT banned",Long.parseLong(actor));if(rows.size()!=1)throw new ControlFailure(403,"ADMIN_REQUIRED");var u=rows.getFirst();return store.fingerprint(u.get("id")+"\0"+u.get("nick_name")+"\0"+u.get("password_hash"));}
    @SuppressWarnings("unchecked") public Map<String,Object> create(String actor,UUID serverId,long revision){
        servers.requireWrite();var server=store.server(serverId);ControlServers.expected(server,revision);var installation=(Map<String,Object>)server.get("installation");var blockers=new ArrayList<String>();
        if("EXTERNAL".equals(installation.get("mode"))&&remote!=null)return remote.create(actor,serverId,revision);
        if(!enabled())blockers.add("CLUSTER_ENROLLMENT_DISABLED");
        if(!"CONTROL_HOST".equals(server.get("location"))||!ControlSsh.rootExecution(server))blockers.add("INITIAL_AUTHORITY_CONTROL_HOST_REQUIRED");
        if(!"SUCCEEDED".equals(installation.get("state")))blockers.add("SERVER_NOT_INSTALLED");
        var m=membership.getIfAvailable();if(m==null||site.getClusterId()==null||((Number)m.status().get("epoch")).longValue()!=0||!((List<?>)m.status().get("members")).isEmpty())blockers.add("CLUSTER_ALREADY_INITIALIZED");
        UUID id=UUID.randomUUID();var p=new LinkedHashMap<String,Object>();p.put("id",id.toString());p.put("kind","INITIAL_AUTHORITY");p.put("serverId",serverId.toString());p.put("serverRevision",revision);p.put("clusterId",Objects.toString(site.getClusterId(),""));p.put("siteId",installation.getOrDefault("siteId",""));p.put("instanceId",installation.getOrDefault("instanceId",""));p.put("httpPort",installation.getOrDefault("httpPort",18090));p.put("databasePort",25432);p.put("allocationSlot",0);p.put("singleSiteOnly",true);p.put("controllerRestartExpected",true);p.put("originalDatabasePreserved",true);p.put("expiresAt",Instant.now().plusSeconds(900).toString());p.put("_actor",actor);p.put("_adminCheck",adminCheck(actor));
        if(blockers.isEmpty())try{var result=call(server,p,"inspect");if(!"eatwhat-authority-v1".equals(result.get("protocol"))||!Boolean.TRUE.equals(result.get("ready"))||!(result.get("preflightHash") instanceof String hash)||!hash.matches("[0-9a-f]{64}"))blockers.add("AUTHORITY_PREFLIGHT_FAILED");else{p.put("_preflightHash",result.get("preflightHash"));p.put("administratorUsername",result.get("administratorUsername"));}}catch(Exception failure){blockers.add("AUTHORITY_PREFLIGHT_FAILED");}
        p.put("state",blockers.isEmpty()?"READY":"BLOCKED");p.put("blockers",blockers);
        var out=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();String reason=deployment.whenIdle(()->{servers.requireWrite();ControlServers.expected(store.server(serverId),revision);store.saveJson(planPath(id),p);audit.append(id,actor,"SERVER_CLUSTER_PLAN",serverId.toString(),p.get("state").toString());out.set(publicView(p));});if(reason!=null)throw new ControlFailure(409,reason);return out.get();
    }
    @SuppressWarnings("unchecked") public synchronized Map<String,Object> execute(String actor,UUID id){
        servers.requireWrite();if(Files.exists(jobPath(id)))return job(id);var p=store.readJson(planPath(id));
        if(ControlRemoteEnrollment.owns(p.get("kind"))&&remote!=null)return remote.execute(actor,id);
        if(!enabled())throw new ControlFailure(409,"CLUSTER_ENROLLMENT_DISABLED");if(!"READY".equals(p.get("state")))throw new ControlFailure(409,"AUTHORITY_PLAN_BLOCKED");
        if(Instant.now().isAfter(Instant.parse((String)p.get("expiresAt"))))throw new ControlFailure(409,"AUTHORITY_PLAN_EXPIRED");
        if(!actor.equals(p.get("_actor"))||!adminCheck(actor).equals(p.get("_adminCheck")))throw new ControlFailure(409,"AUTHORITY_PLAN_CHANGED");
        var server=store.server(UUID.fromString((String)p.get("serverId")));ControlServers.expected(server,((Number)p.get("serverRevision")).longValue());
        String reason=deployment.whenIdle(()->{servers.requireWrite();ControlServers.expected(store.server(UUID.fromString((String)p.get("serverId"))),((Number)p.get("serverRevision")).longValue());var j=new LinkedHashMap<String,Object>();for(String key:List.of("id","kind","serverId","siteId","instanceId","clusterId","serverRevision","singleSiteOnly","controllerRestartExpected"))j.put(key,p.get(key));j.put("state","QUEUED");j.put("stage","AUTHORITY_SETUP");j.put("createdAt",Instant.now().toString());j.put("_plan",p);audit.append(id,actor,"SERVER_CLUSTER_EXECUTE",p.get("serverId").toString(),"ACCEPTED");store.saveJson(jobPath(id),j);markJob(p);});if(reason!=null)throw new ControlFailure(409,reason);
        // Dispatch once. An ambiguous SSH response is reconciled by receipt; never sent again.
        worker.execute(()->dispatch(id));return job(id);
    }
    @SuppressWarnings("unchecked") private void markJob(Map<String,Object> p){store.mutate(data->{var s=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(p.get("serverId"));s.put("clusterJobId",p.get("id"));return null;});}
    private Map<String,Object> call(Map<String,Object> server,Map<String,Object> p,String action)throws Exception{var header=new LinkedHashMap<String,Object>();for(String k:List.of("id","serverId","siteId","instanceId","clusterId","httpPort","databasePort","_actor","_preflightHash"))if(p.containsKey(k))header.put(k,p.get(k));if(action.equals("dispatch"))header.put("agent",ControlServers.resource("cluster-enroll.py"));return ssh.call(server,ControlServers.resource("cluster-enroll.py"),List.of(action),out->{out.write(json.writeValueAsBytes(header));out.write('\n');},60);}
    @SuppressWarnings("unchecked") private void dispatch(UUID id){var j=store.readJson(jobPath(id));var p=(Map<String,Object>)j.get("_plan");try{
        j.put("state","RUNNING");store.saveJson(jobPath(id),j);var s=store.server(UUID.fromString((String)p.get("serverId")));ControlServers.expected(s,((Number)p.get("serverRevision")).longValue());if(!adminCheck(p.get("_actor").toString()).equals(p.get("_adminCheck")))throw new Exception();
        var receipt=call(s,p,"dispatch");if(!id.toString().equals(receipt.get("id"))||!p.get("serverId").equals(receipt.get("serverId")))throw new Exception();
        if("REJECTED".equals(receipt.get("state"))&&Boolean.FALSE.equals(receipt.get("effectsStarted"))){j.put("state","FAILED");j.put("code","AUTHORITY_PLAN_CHANGED");store.saveJson(jobPath(id),j);return;}
        if(!Set.of("ACCEPTED","SETUP_READY").contains(receipt.get("state")))throw new Exception();
    }catch(Exception failure){j.put("state","OUTCOME_UNKNOWN");j.put("code","AUTHORITY_RECONCILIATION_REQUIRED");store.saveJson(jobPath(id),j);}}
    public Map<String,Object> reconcile(String actor,UUID id){servers.requireWrite();if(ControlRemoteEnrollment.owns(store.readJson(jobPath(id)).get("kind"))&&remote!=null)return remote.reconcile(id);refresh(id,false);return job(id);}
    @SuppressWarnings("unchecked") private synchronized void refresh(UUID id,boolean acceptedContinuation){
        var j=store.readJson(jobPath(id));if(Set.of("SUCCEEDED","FAILED").contains(j.get("state")))return;var p=(Map<String,Object>)j.get("_plan");
        try{var s=store.server(UUID.fromString((String)p.get("serverId")));ControlServers.expected(s,((Number)p.get("serverRevision")).longValue());var receipt=call(s,p,"status");
            if(!id.toString().equals(receipt.get("id"))||!p.get("serverId").equals(receipt.get("serverId")))throw new Exception();
            if("FAILED".equals(receipt.get("state"))){j.put("state","FAILED");j.put("code","AUTHORITY_SETUP_FAILED_PRESERVED");store.saveJson(jobPath(id),j);return;}
            if(!"SETUP_READY".equals(receipt.get("state")))return;
            if(!site.getId().equals(p.get("siteId"))||!site.getInstanceId().toString().equals(p.get("instanceId"))||!Objects.toString(site.getClusterId()).equals(p.get("clusterId")))return;
            var c=cluster.getIfAvailable();if(c==null||!c.isSingleSiteOnly()||!c.isCoordinator())throw new Exception();
            if(!adminCheck(p.get("_actor").toString()).equals(p.get("_adminCheck")))throw new Exception();
            var m=membership.getObject();var current=(List<Map<String,Object>>)m.status().get("members");var co=coordinator.getObject();
            if(acceptedContinuation){
                servers.requireExecuting();j.put("state","RUNNING");j.put("stage","MEMBERSHIP_JOIN");store.saveJson(jobPath(id),j);
                if(current.isEmpty())co.register(p.get("_actor").toString(),UUID.nameUUIDFromBytes((id+":register").getBytes(java.nio.charset.StandardCharsets.UTF_8)),0,"authority");
                current=(List<Map<String,Object>>)m.status().get("members");if(current.size()!=1||!p.get("siteId").equals(current.getFirst().get("site_id"))||!p.get("instanceId").equals(current.getFirst().get("instance_id").toString()))throw new Exception();
                try{m.change(id);}catch(ClusterFailure missing){if(missing.status()!=404)throw missing;co.begin(p.get("_actor").toString(),id,0,"JOIN",p.get("siteId").toString());}
                co.reconcile(p.get("_actor").toString(),id);
            }
            var change=m.change(id);var member=m.member(p.get("siteId").toString());var local=jdbc.queryForList("SELECT epoch,state,pending_change FROM eatwhat_cluster.local_state WHERE singleton");
            if(!"APPLIED".equals(change.get("state"))||!"ACTIVE".equals(member.get("state"))||local.size()!=1||!"ACTIVE".equals(local.getFirst().get("state"))||local.getFirst().get("pending_change")!=null||((Number)local.getFirst().get("epoch")).longValue()!=1)throw new Exception();
            var result=Map.<String,Object>of("clusterMembership","ACTIVE","changeState","APPLIED","epoch",1,"siteId",p.get("siteId"),"instanceId",p.get("instanceId"),"singleSiteOnly",true);
            store.mutate(data->{var server=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(p.get("serverId"));ControlServers.expected(server,((Number)p.get("serverRevision")).longValue());((Map<String,Object>)server.get("installation")).put("clusterMembership","ACTIVE");return null;});
            j.put("state","SUCCEEDED");j.put("stage","VERIFIED");j.put("result",result);j.remove("code");store.saveJson(jobPath(id),j);audit.append(id,p.get("_actor").toString(),"SERVER_CLUSTER_JOIN",p.get("serverId").toString(),"VERIFIED_APPLIED_ACTIVE");
        }catch(Exception unavailable){/* Durable accepted command is retried only by this worker; GET and reconcile never initiate membership writes. */}
    }
    private void cycle(){try(var files=Files.list(store.directory())){for(var p:files.filter(f->f.getFileName().toString().matches("server-cluster-job-[0-9a-f-]{36}\\.json")).toList()){
        var j=store.readJson(p);if(!Set.of("QUEUED","RUNNING","OUTCOME_UNKNOWN").contains(j.get("state")))continue;
        if(ControlRemoteEnrollment.owns(j.get("kind")))continue;
        try(var channel=FileChannel.open(store.directory().resolve("deployment.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){continue;}if(lock!=null)try(lock){refresh(UUID.fromString(j.get("id").toString()),true);}}
    }}catch(Exception ignored){/* Remains pending; no re-dispatch or invented completion. */}}
    public synchronized void start(){if(worker!=null)return;worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"control-cluster-enrollment");t.setDaemon(true);return t;});worker.scheduleWithFixedDelay(this::cycle,4,4,TimeUnit.SECONDS);}
    public synchronized void stop(){if(worker!=null){worker.shutdownNow();worker=null;}}
    public boolean isRunning(){return worker!=null&&!worker.isShutdown();}
    public int getPhase(){return 1100;}
}
