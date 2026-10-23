package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Explicit old-JAR restoration of an unresolved registered release. Never resends a recovery UUID. */
public final class ControlDeploymentRecovery implements AutoCloseable {
    private final ControlProperties settings;private final ControlAudit audit;private final ControlServerStore files;private final DeploymentExecutor deployment;
    private final java.util.function.Function<Path,Map<String,Object>> planReader;
    private final JsonMapper json=JsonMapper.builder().build();private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Set<UUID> owned=ConcurrentHashMap.newKeySet();private volatile boolean closed;
    public ControlDeploymentRecovery(ControlProperties settings,ControlAudit audit,ControlServerStore files,DeploymentExecutor deployment){this(settings,audit,files,deployment,files::readJson);}
    // Test-only read scheduling seam; production always uses the private store.
    ControlDeploymentRecovery(ControlProperties settings,ControlAudit audit,ControlServerStore files,DeploymentExecutor deployment,java.util.function.Function<Path,Map<String,Object>> planReader){this.settings=settings;this.audit=audit;this.files=files;this.deployment=deployment;this.planReader=planReader;}
    private Path planPath(UUID id){return audit.directory().resolve("deployment-recovery-plan-"+id+".json");}
    private Path jobPath(UUID id){return audit.directory().resolve("deployment-"+id+".json");}
    private void savePlan(UUID id,Map<String,Object> plan){files.saveJson(planPath(id),plan);}
    private Map<String,Object> readPlan(UUID id){if(!Files.exists(planPath(id)))throw new ControlFailure(404,"DEPLOYMENT_RECOVERY_PLAN_NOT_FOUND");return planReader.apply(planPath(id));}
    private record Held(FileChannel channel,FileLock lock) implements AutoCloseable {public void close(){try{lock.release();channel.close();}catch(Exception ignored){}}}
    private Held lock(){try{var channel=FileChannel.open(audit.directory().resolve("deployment.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);try{var lock=channel.tryLock();if(lock==null)throw new ControlFailure(409,"DEPLOYMENT_ALREADY_ACTIVE");return new Held(channel,lock);}catch(Exception e){channel.close();throw e;}}catch(ControlFailure e){throw e;}catch(OverlappingFileLockException e){throw new ControlFailure(409,"DEPLOYMENT_ALREADY_ACTIVE");}catch(Exception e){throw new ControlFailure(503,"DEPLOYMENT_STATE_UNAVAILABLE");}}
    private String controller(){var proof=deployment.runtimeArtifact();if("VERIFIED_AT_STARTUP".equals(proof.get("status")))return proof.get("sha256").toString();if("isolated-acceptance".equals(settings.getEnvironmentKind()))return "ISOLATED_CLASSES";throw new ControlFailure(409,"CONTROLLER_RUNTIME_ARTIFACT_UNAVAILABLE");}
    private record Binding(Map<String,Object> job,Map<String,Object> row,ControlProperties.DeploymentTarget target,String hash){}
    private String hash(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new ControlFailure(503,"DEPLOYMENT_STATE_UNAVAILABLE");}}
    @SuppressWarnings("unchecked") private Binding binding(UUID jobId,String site,boolean applying){
        if(!Files.exists(jobPath(jobId)))throw new ControlFailure(404,"DEPLOYMENT_NOT_FOUND");
        var job=files.readJson(jobPath(jobId));if(!jobId.toString().equals(job.get("id"))||!Boolean.TRUE.equals(job.get("_registered"))||!Boolean.TRUE.equals(job.get("_remoteDispatchAllowed")))throw new ControlFailure(409,"FROZEN_DEPLOYMENT_BINDING_REQUIRED");
        if(applying&&!Set.of("OUTCOME_UNKNOWN","RUNNING").contains(job.get("state")))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_NOT_ELIGIBLE");
        var rows=job.get("targets") instanceof List<?> values?values:List.of();var targets=job.get("_targets") instanceof List<?> values?values:List.of();if(rows.size()!=targets.size()||rows.isEmpty())throw new ControlFailure(409,"FROZEN_DEPLOYMENT_BINDING_REQUIRED");
        Binding selected=null;
        for(int i=0;i<rows.size();i++){var row=(Map<String,Object>)rows.get(i);if(!site.equals(row.get("siteId")))continue;if(selected!=null)throw new ControlFailure(409,"FROZEN_DEPLOYMENT_BINDING_REQUIRED");var target=json.readValue(json.writeValueAsString(targets.get(i)),ControlProperties.DeploymentTarget.class);
            if(target==null||target.getServerId()==null||target.getInstanceId()==null||target.getCurrentSha256()==null||!site.equals(target.getSiteId())||!target.getInstanceId().toString().equals(row.get("instanceId"))||!target.getCurrentSha256().equals(row.get("fromSha256"))||!Objects.equals(job.get("_manifestHash"),target.getDeploymentManifestHash())||target.getRemoteConfigurationProof()==null||!target.getRemoteConfigurationProof().matches("[0-9a-f]{64}"))throw new ControlFailure(409,"FROZEN_DEPLOYMENT_BINDING_REQUIRED");
            if(applying&&Set.of("SUCCEEDED","ROLLED_BACK","FAILED","NOT_STARTED").contains(row.get("state")))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_NOT_ELIGIBLE");
            selected=new Binding(job,row,target,hash(json.writeValueAsString(List.of(jobId.toString(),job.get("_manifestHash"),job.get("artifactSha256"),targets.get(i)))));
        }
        if(selected==null)throw new ControlFailure(404,"DEPLOYMENT_RECOVERY_TARGET_NOT_FOUND");deployment.recoveryTransport().requireRecovery(jobId,selected.target.getDeploymentManifestHash(),applying);return selected;
    }
    private void verifyPlanBinding(Map<String,Object> plan,Binding binding){if(!Objects.equals(plan.get("_bindingHash"),binding.hash)||!Objects.equals(plan.get("_controllerSha"),controller()))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_PLAN_RECREATE_REQUIRED");}
    public synchronized Map<String,Object> create(String actor,UUID jobId,UUID requestId,String site){
        if(site==null||!site.matches("[A-Za-z0-9_-]{1,64}"))throw new ControlFailure(400,"INVALID_DEPLOYMENT_TARGETS");
        if(Files.exists(planPath(requestId))){var prior=readPlan(requestId);if(!jobId.toString().equals(prior.get("jobId"))||!site.equals(prior.get("siteId")))throw new ControlFailure(409,"IDEMPOTENCY_CONFLICT");return view(prior);}
        if(closed)throw new ControlFailure(503,"DEPLOYMENT_EXECUTOR_CLOSED");
        try(var held=lock()){
            if(Files.exists(planPath(requestId))){var prior=readPlan(requestId);if(!jobId.toString().equals(prior.get("jobId"))||!site.equals(prior.get("siteId")))throw new ControlFailure(409,"IDEMPOTENCY_CONFLICT");return view(prior);}
            var binding=binding(jobId,site,true);String version=controller();
            var reply=deployment.recoveryTransport().recovery("recovery-prepare",jobId,binding.target,binding.job.get("artifactSha256").toString(),requestId,null);
            if(!"RECOVERY_PREPARED".equals(reply.get("state"))||!requestId.toString().equals(reply.get("recoveryRequestId"))||!(reply.get("recoveryProof") instanceof String proof)||!proof.matches("[0-9a-f]{64}")||!(reply.get("currentSha256") instanceof String current)||!current.matches("[0-9a-f]{64}")||!binding.target.getCurrentSha256().equals(reply.get("restoreSha256"))||!Boolean.TRUE.equals(reply.get("databaseIdentityVerified")))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_PREVIEW_NOT_VERIFIED");
            var plan=new LinkedHashMap<String,Object>();plan.put("id",requestId.toString());plan.put("jobId",jobId.toString());plan.put("siteId",site);plan.put("instanceId",binding.target.getInstanceId().toString());plan.put("serverId",binding.target.getServerId().toString());plan.put("epoch",binding.target.getMembershipEpoch());plan.put("restoreSha256",binding.target.getCurrentSha256());plan.put("currentSha256",reply.get("currentSha256"));plan.put("backupId",jobId.toString());plan.put("state","PREVIEW_ONLY");plan.put("createdAt",Instant.now().toString());plan.put("expiresAt",Instant.now().plusSeconds(300).toString());plan.put("_bindingHash",binding.hash);plan.put("_controllerSha",version);plan.put("_remoteProof",reply.get("recoveryProof"));
            audit.append(requestId,actor,"DEPLOYMENT_RECOVERY_PREVIEW",jobId+":"+site,"PREVIEW_ONLY");savePlan(requestId,plan);return view(plan);
        }catch(ControlFailure e){throw e;}catch(ControlSsh.RemoteRefusal e){throw new ControlFailure(409,e.code);}catch(Exception e){throw new ControlFailure(502,"DEPLOYMENT_RECOVERY_PREVIEW_UNAVAILABLE");}
    }
    public Map<String,Object> get(UUID id){return view(readPlan(id));}
    public synchronized Map<String,Object> execute(String actor,UUID id,String confirmedSha){
        var plan=readPlan(id);if(!Objects.equals(plan.get("restoreSha256"),confirmedSha))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_CONFIRMATION_REQUIRED");
        if(!"PREVIEW_ONLY".equals(plan.get("state")))return view(plan);
        if(closed)throw new ControlFailure(503,"DEPLOYMENT_EXECUTOR_CLOSED");if(!Instant.now().isBefore(Instant.parse(plan.get("expiresAt").toString())))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_PLAN_EXPIRED");
        Held held=lock();boolean submitted=false;
        try{
            // Another controller may have accepted this UUID after our first
            // read but before this lock. Never overwrite its retained decision.
            var latest=readPlan(id);if(!"PREVIEW_ONLY".equals(latest.get("state")))return view(latest);plan.clear();plan.putAll(latest);
            if(!Objects.equals(plan.get("restoreSha256"),confirmedSha))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_CONFIRMATION_REQUIRED");if(!Instant.now().isBefore(Instant.parse(plan.get("expiresAt").toString())))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_PLAN_EXPIRED");
            UUID job=UUID.fromString(plan.get("jobId").toString());var binding=binding(job,plan.get("siteId").toString(),true);verifyPlanBinding(plan,binding);
            plan.put("state","QUEUED");plan.put("stage","ACCEPTED");audit.append(id,actor,"DEPLOYMENT_RECOVERY_EXECUTE",job.toString(),"ACCEPTED");savePlan(id,plan);attempt(binding,id,plan);
            owned.add(id);worker.submit(()->run(actor,id,plan,binding,held));submitted=true;return get(id);
        }catch(ControlFailure e){throw e;}catch(Exception e){owned.remove(id);plan.put("state","OUTCOME_UNKNOWN");plan.put("code","DEPLOYMENT_RECOVERY_DISPATCH_NOT_CONFIRMED");savePlan(id,plan);return view(plan);}finally{if(!submitted)held.close();}
    }
    private Map<String,Object> call(String action,UUID id,Map<String,Object> plan,Binding binding)throws Exception{
        var reply=deployment.recoveryTransport().recovery(action,UUID.fromString(plan.get("jobId").toString()),binding.target,binding.job.get("artifactSha256").toString(),id,plan.get("_remoteProof").toString());
        if(!(reply.get("recovery") instanceof Map<?,?> recovery)||!id.toString().equals(recovery.get("id")))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_REPLY_MISMATCH");return reply;
    }
    private void run(String actor,UUID id,Map<String,Object> plan,Binding binding,Held held){
        try{plan.put("state","RUNNING");plan.put("stage","DISPATCH");savePlan(id,plan);var reply=call("recover",id,plan,binding);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.getDeployment().getTimeoutSeconds());
            while(update(plan,reply).equals("RUNNING")){attempt(binding,id,plan);savePlan(id,plan);if(System.nanoTime()>=deadline)throw new java.io.IOException();Thread.sleep(1000);reply=call("recovery-status",id,plan,binding);}
            savePlan(id,plan);attempt(binding,id,plan);audit.append(id,actor,"DEPLOYMENT_RECOVERY_EXECUTE",plan.get("jobId").toString(),plan.get("state").toString());
        }catch(Exception e){plan.put("state","OUTCOME_UNKNOWN");plan.put("code",e instanceof ControlSsh.RemoteRefusal refusal?refusal.code:"DEPLOYMENT_RECOVERY_OUTCOME_REQUIRES_RECONCILIATION");savePlan(id,plan);attempt(binding,id,plan);}finally{held.close();owned.remove(id);}
        if("SUCCEEDED".equals(plan.get("state")))try{deployment.reconcile(actor,UUID.fromString(plan.get("jobId").toString()));}catch(Exception ignored){/* Parent stays blocked until explicit read-only reconciliation. */}
    }
    @SuppressWarnings("unchecked") private String update(Map<String,Object> plan,Map<String,Object> reply){
        var recovery=(Map<String,Object>)reply.get("recovery");String state=Objects.toString(recovery.get("state"),"");if(!Set.of("RUNNING","SUCCEEDED","FAILED","NOT_STARTED","OUTCOME_UNKNOWN").contains(state))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_REPLY_MISMATCH");
        if(state.equals("SUCCEEDED")&&!"ROLLED_BACK".equals(reply.get("state")))throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_REPLY_MISMATCH");
        plan.put("state",state.equals("NOT_STARTED")?"FAILED":state);for(String key:List.of("stage","code","failureStage")){Object value=recovery.get(key);if(value instanceof String code&&code.matches("[A-Z][A-Z0-9_]{0,127}"))plan.put(key,value);}plan.put("updatedAt",Instant.now().toString());return plan.get("state").toString();
    }
    @SuppressWarnings("unchecked") private void attempt(Binding binding,UUID id,Map<String,Object> plan){
        var attempts=(List<Map<String,Object>>)binding.job.computeIfAbsent("recoveryAttempts",ignored->new ArrayList<>());var record=attempts.stream().filter(row->id.toString().equals(row.get("id"))).findFirst().orElseGet(()->{if(attempts.size()>=256)throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_HISTORY_CAPACITY");var row=new LinkedHashMap<String,Object>();row.put("id",id.toString());row.put("siteId",plan.get("siteId"));row.put("restoreSha256",plan.get("restoreSha256"));attempts.add(row);return row;});
        for(String key:List.of("state","stage","code","failureStage","createdAt","updatedAt"))if(plan.containsKey(key))record.put(key,plan.get(key));binding.row.put("recoveryRequestId",id.toString());binding.row.put("recoveryState",plan.get("state"));files.saveJson(jobPath(UUID.fromString(plan.get("jobId").toString())),binding.job);
    }
    public synchronized Map<String,Object> reconcile(String actor,UUID id){
        if(owned.contains(id))throw new ControlFailure(409,"DEPLOYMENT_ALREADY_ACTIVE");var plan=readPlan(id);if("SUCCEEDED".equals(plan.get("state"))){try{deployment.reconcile(actor,UUID.fromString(plan.get("jobId").toString()));}catch(Exception ignored){}return view(plan);}if(Set.of("FAILED","PREVIEW_ONLY").contains(plan.get("state")))return view(plan);
        try(var held=lock()){
            var binding=binding(UUID.fromString(plan.get("jobId").toString()),plan.get("siteId").toString(),false);var reply=call("recovery-status",id,plan,binding);update(plan,reply);savePlan(id,plan);attempt(binding,id,plan);audit.append(id,actor,"DEPLOYMENT_RECOVERY_RECONCILE",plan.get("jobId").toString(),plan.get("state").toString());
        }catch(ControlFailure e){throw e;}catch(ControlSsh.RemoteRefusal e){throw new ControlFailure(409,e.code);}catch(Exception e){throw new ControlFailure(502,"DEPLOYMENT_RECOVERY_RECONCILIATION_UNAVAILABLE");}
        if("SUCCEEDED".equals(plan.get("state")))try{deployment.reconcile(actor,UUID.fromString(plan.get("jobId").toString()));}catch(Exception ignored){}
        return view(plan);
    }
    private Map<String,Object> view(Map<String,Object> plan){
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","jobId","siteId","instanceId","serverId","epoch","restoreSha256","currentSha256","backupId","state","stage","code","failureStage","createdAt","updatedAt","expiresAt"))if(plan.containsKey(key))result.put(key,plan.get(key));
        if(Set.of("QUEUED","RUNNING").contains(result.get("state"))&&!owned.contains(UUID.fromString(plan.get("id").toString()))){result.put("state","OUTCOME_UNKNOWN");result.put("code","DEPLOYMENT_RECOVERY_PROCESS_NOT_OWNED");}
        var parent=deployment.get(UUID.fromString(plan.get("jobId").toString()));result.put("parentState",parent.get("state"));result.put("parentReconciliationRequired",Set.of("QUEUED","RUNNING","OUTCOME_UNKNOWN").contains(parent.get("state")));result.put("executionEnabled","PREVIEW_ONLY".equals(result.get("state"))&&"OUTCOME_UNKNOWN".equals(parent.get("state"))&&Instant.now().isBefore(Instant.parse(plan.get("expiresAt").toString()))&&Objects.equals(plan.get("_controllerSha"),controller()));return result;
    }
    @Override public void close(){closed=true;worker.shutdown();try{if(!worker.awaitTermination(2,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){worker.shutdownNow();Thread.currentThread().interrupt();}}
}
