package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Serial SSH rollouts. Persisted intent and an OS lock precede every remote effect. */
public final class DeploymentExecutor implements AutoCloseable {
    public record Target(String key, String siteId, UUID instanceId) {}
    @FunctionalInterface public interface Driver {
        Map<String,Object> call(String action, UUID id, ControlProperties.DeploymentTarget target,
                               Path artifact, String sha) throws Exception;
    }
    /** Private registry binding. Public progress contains identities, never this connection snapshot. */
    public interface RegisteredTransport extends Driver {
        ControlProperties.DeploymentTarget resolve(Target target);
        boolean available();
        default boolean supportsControlHost(){return false;}
        default void record(UUID id,ControlProperties.DeploymentTarget target,String sha,Map<String,Object> reply){}
        default void reserve(UUID id,String manifest,List<ControlProperties.DeploymentTarget> targets)throws Exception{}
        default void verifyReservation(UUID id,String manifest,boolean applying){}
        default void complete(UUID id,String manifest,Map<String,Object> job)throws Exception{}
        default boolean finalized(UUID id,String manifest,Map<String,Object> job){return true;}
        default boolean noDispatchProven(UUID id,String manifest){return false;}
        default void requireRecovery(UUID id,String manifest,boolean applying){throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_UNAVAILABLE");}
        default Map<String,Object> recovery(String action,UUID id,ControlProperties.DeploymentTarget target,String sha,UUID requestId,String proof)throws Exception{throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_UNAVAILABLE");}
    }
    public static final class AdmissionRejected extends Exception {public final String code;public AdmissionRejected(String code){super("Deployment admission rejected before remote dispatch");if(code==null||!code.matches("[A-Z][A-Z0-9_]{0,127}"))throw new IllegalArgumentException();this.code=code;}}
    private final ControlProperties settings;
    private final ControlAudit audit;
    private final Driver driver;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<UUID> owned = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile Runnable admissionGuard=()->{};
    private volatile RegisteredTransport registered;
    private volatile java.util.function.Supplier<String> externalIdleGuard=()->null;
    private final java.util.List<java.util.function.Supplier<String>> additionalIdleGuards=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Path runtimeArtifact;
    private final String runtimeArtifactSha;
    public DeploymentExecutor(ControlProperties settings, ControlAudit audit) { this(settings,audit,null); }
    public DeploymentExecutor(ControlProperties settings, ControlAudit audit, Driver driver) {
        this.settings=settings; this.audit=audit; this.driver=driver==null?this::ssh:driver;
        Path actual=null;String digest=null;try{var source=new org.springframework.boot.system.ApplicationHome(DeploymentExecutor.class).getSource();if(source!=null&&Files.isRegularFile(source.toPath(),LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(source.toPath())){actual=source.toPath().toRealPath();digest=artifactSha(actual);}}catch(Exception unavailable){}runtimeArtifact=actual;runtimeArtifactSha=digest;
        if(enabled()) validateConfiguration();
    }
    public boolean enabled() { return settings.getDeployment().isEnabled(); }
    public void registeredTransport(RegisteredTransport transport){registered=Objects.requireNonNull(transport);}
    public void externalIdleGuard(java.util.function.Supplier<String> guard){externalIdleGuard=Objects.requireNonNull(guard);}
    public void addIdleGuard(java.util.function.Supplier<String> guard){additionalIdleGuards.add(Objects.requireNonNull(guard));}
    public String idleFenceReason(){String reason=externalIdleGuard.get();if(reason!=null)return reason;for(var guard:additionalIdleGuards){reason=guard.get();if(reason!=null)return reason;}return null;}
    private boolean registrySource(){return settings.getDeployment().getTargetSource()==ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS;}
    public boolean recoverySupported(){return registered!=null&&registered.available();}
    RegisteredTransport recoveryTransport(){if(!recoverySupported())throw new ControlFailure(409,"DEPLOYMENT_RECOVERY_UNAVAILABLE");return registered;}
    private ControlProperties.DeploymentTarget resolve(Target target){
        if(!registrySource())return settings.getDeployment().getTargets().get(target.key());
        if(registered==null)throw new ControlFailure(503,"REGISTERED_DEPLOYMENT_UNAVAILABLE");return registered.resolve(target);
    }
    public String environmentKind() { return settings.getEnvironmentKind(); }
    /** Applies only to new jobs; an already accepted deployment keeps its original execution guard. */
    public void admissionGuard(Runnable guard){admissionGuard=Objects.requireNonNull(guard);}
    public UUID stateIdentity(){
        Path file=audit.directory().resolve("controller-state-id");
        try{
            if(!Files.exists(file)){
                String reason=whenIdle(()->{
                    if(!Files.exists(file)){
                        try{
                            Path tmp=Files.createTempFile(audit.directory(),"controller-state-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                            try{
                                try(var out=FileChannel.open(tmp,StandardOpenOption.WRITE)){out.write(java.nio.ByteBuffer.wrap(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));out.force(true);}
                                Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE);
                                try(var directory=FileChannel.open(audit.directory(),StandardOpenOption.READ)){directory.force(true);}
                            }finally{Files.deleteIfExists(tmp);}
                        }
                        catch(IOException failure){throw new ControlFailure(503,"CONTROL_STATE_ID_UNAVAILABLE");}
                    }
                });
                if(reason!=null)throw new ControlFailure(503,"CONTROL_STATE_ID_UNAVAILABLE");
            }
            if(Files.isSymbolicLink(file)||Files.size(file)>64||!Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")))throw new IOException();
            return UUID.fromString(Files.readString(file).trim());
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"CONTROL_STATE_ID_UNAVAILABLE");}
    }
    /** Same lock as acceptance/apply; unknown outcomes must be reconciled before a graceful handoff. */
    public String whenIdle(Runnable action){
        try(var channel=FileChannel.open(audit.directory().resolve("deployment.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){return "DEPLOYMENT_RUNNING";}
            if(lock==null)return "DEPLOYMENT_RUNNING";
            try(lock){
                try(var files=Files.list(audit.directory())){
                    for(var file:files.filter(p->p.getFileName().toString().matches("(?:deployment|server-job|server-cluster-job|controller-job)-[0-9a-f-]{36}\\.json")).toList())
                        {var job=read(file);if(Set.of("QUEUED","RUNNING","OUTCOME_UNKNOWN").contains(job.get("state"))||unfinalizedFence(job))return "DEPLOYMENT_RECONCILIATION_REQUIRED";}
                }
                String external=idleFenceReason();if(external!=null)return external;action.run();return null;
            }
        }catch(ControlFailure failure){throw failure;}catch(IOException failure){throw new ControlFailure(503,"DEPLOYMENT_STATE_UNAVAILABLE");}
    }
    private void validateConfiguration() {
        var d=settings.getDeployment();
        if(d.getTargetSource()==null||d.getTimeoutSeconds()<30 || d.getTimeoutSeconds()>3600)failConfig();
        if(registrySource()){
            Path ssh=d.getSshExecutable();if(ssh==null||!ssh.isAbsolute()||Files.isSymbolicLink(ssh)||!Files.isRegularFile(ssh)||!Files.isExecutable(ssh))failConfig();return;
        }
        if(d.getTargets().isEmpty())failConfig();
        for(var path:Arrays.asList(d.getSshExecutable(),d.getKnownHostsFile(),d.getIdentityFile()))
            if(path==null || !path.isAbsolute() || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) failConfig();
        if(!Files.isExecutable(d.getSshExecutable())) failConfig();
        try {if(!Files.getPosixFilePermissions(d.getIdentityFile()).equals(PosixFilePermissions.fromString("rw-------")))throw new Exception();}
        catch(Exception e){throw new ControlFailure(503,"PRIVATE_DEPLOYMENT_KEY_REQUIRED");}
        var destinations=new HashSet<String>();
        d.getTargets().forEach((key,t)->{
            ControlService.identifier(key); ControlService.identifier(t.getSiteId());
            if(t.getInstanceId()==null || t.getInstanceId().equals(new UUID(0,0)) || t.getHost()==null || !t.getHost().matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")
                    || t.getUser()==null || !t.getUser().matches("[a-z_][a-z0-9_-]{0,31}") || t.getPort()<1 || t.getPort()>65535
                    || !safePath(t.getAgent()) || !safePath(t.getConfig()) || !sha(t.getCurrentSha256())) failConfig();
            if(!destinations.add(t.getHost()+":"+t.getPort()+":"+t.getConfig()))failConfig();
        });
    }
    private static void failConfig(){throw new ControlFailure(503,"DEPLOYMENT_CONFIG_INVALID");}
    private static boolean safePath(String s){return s!=null && s.matches("/[A-Za-z0-9_./-]+") && !s.contains("..") && !s.contains("/./");}
    private static boolean sha(String s){return s!=null && s.matches("[0-9a-f]{64}");}
    public List<String> blockers(String releaseId, List<Target> targets) {
        var result=new ArrayList<String>();
        if(!enabled())result.add("DEPLOYMENT_EXECUTOR_DISABLED");
        var release=settings.getReleases().get(releaseId);
        if(release==null)result.add("RELEASE_NOT_CONFIGURED");
        else if(!release.isRollbackCompatible() || release.getAcceptanceEvidenceId()==null || release.getAcceptanceEvidenceId().isBlank())result.add("RELEASE_COMPATIBILITY_NOT_REVIEWED");
        if(registrySource()&&(registered==null||!registered.available()))result.add("REGISTERED_DEPLOYMENT_PROTOCOL_NOT_READY");
        if(registrySource()&&"managed-deployment".equals(settings.getEnvironmentKind())){
            if(runtimeArtifactSha==null)result.add("CONTROLLER_RUNTIME_ARTIFACT_UNAVAILABLE");
            else if(release!=null&&!runtimeArtifactSha.equals(release.getSha256())&&!release.getCompatibleFromSha256().contains(runtimeArtifactSha))result.add("CONTROLLER_VERSION_COMPATIBILITY_NOT_REVIEWED");
            var configured=settings.getDeployment().getControllerArtifact();if(configured!=null)try{if(runtimeArtifact==null||!Files.isSameFile(configured,runtimeArtifact))result.add("CONTROLLER_ARTIFACT_RUNTIME_MISMATCH");}catch(Exception mismatch){result.add("CONTROLLER_ARTIFACT_RUNTIME_MISMATCH");}
        }
        for(var target:targets){ControlProperties.DeploymentTarget t;
            try{t=resolve(target);}catch(ControlFailure failure){result.add(failure.code+":"+target.key());continue;}
            if(t==null){result.add("DEPLOYMENT_TARGET_NOT_CONFIGURED:"+target.key());continue;}
            if(!Objects.equals(t.getSiteId(),target.siteId()) || !Objects.equals(t.getInstanceId(),target.instanceId()))result.add("DEPLOYMENT_TARGET_IDENTITY_MISMATCH:"+target.key());
            if(t.isControlHost()&&!(registrySource()&&registered!=null&&registered.supportsControlHost()))result.add("CONTROL_HOST_REQUIRES_SEPARATE_EXECUTOR:"+target.key());
            if(registrySource()&&t.isControlHost()&&!"managed-deployment".equals(settings.getEnvironmentKind())){
                var artifact=settings.getDeployment().getControllerArtifact();
                if(artifact==null||!artifact.isAbsolute())result.add("CONTROLLER_ARTIFACT_REQUIRED:"+target.key());
                else try{if(release!=null&&!release.getCompatibleFromSha256().contains(artifactSha(artifact)))result.add("CONTROLLER_VERSION_COMPATIBILITY_NOT_REVIEWED:"+target.key());}catch(ControlFailure failure){result.add("CONTROLLER_ARTIFACT_UNAVAILABLE:"+target.key());}
            }
            if(t.getRestoreEvidenceId()==null || t.getRestoreEvidenceId().isBlank())result.add("RESTORE_REHEARSAL_NOT_REVIEWED:"+target.key());
            if(release!=null && !release.getCompatibleFromSha256().contains(t.getCurrentSha256()))result.add("UPGRADE_SOURCE_NOT_REVIEWED:"+target.key());
        }
        return result;
    }
    public String configurationHash(String releaseId,List<Target> targets){
        try{var frozen=new LinkedHashMap<String,Object>();
            frozen.put("release",settings.getReleases().get(releaseId));
            frozen.put("targetSource",settings.getDeployment().getTargetSource());
            if(registrySource()){frozen.put("controllerRuntimeArtifact",runtimeArtifactSha);frozen.put("authorityOrigin",settings.getDeployment().getAuthorityOrigin());var path=settings.getDeployment().getControllerArtifact();try{frozen.put("controllerArtifact",path==null?null:artifactSha(path));}catch(ControlFailure unavailable){frozen.put("controllerArtifact",unavailable.code);}}
            frozen.put("ssh",Arrays.asList(settings.getDeployment().getSshExecutable(),settings.getDeployment().getKnownHostsFile(),settings.getDeployment().getIdentityFile()));
            for(var target:targets){try{
                var t=resolve(target);if(t==null){frozen.put(target.key(),null);continue;}
                var projection=json.readValue(json.writeValueAsString(t),LinkedHashMap.class);for(String privateRuntime:List.of("encryptedConnection","deploymentManifestHash","encryptedAuthorityToken","remoteConfigurationProof"))projection.remove(privateRuntime);frozen.put(target.key(),projection);
            }catch(ControlFailure failure){frozen.put(target.key(),Map.of("unavailable",failure.code));}}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(frozen)));
        }catch(Exception e){throw new ControlFailure(503,"DEPLOYMENT_CONFIG_INVALID");}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> existing(UUID id,String releaseId,String sha,List<Target> targets){
        var job=get(id);
        if(!releaseId.equals(job.get("releaseId")) || !sha.equals(job.get("artifactSha256")))throw new ControlFailure(409,"IDEMPOTENCY_CONFLICT");
        var previous=((List<Map<String,Object>>)job.get("targets")).stream().map(row->new Target((String)row.get("site"),(String)row.get("siteId"),UUID.fromString((String)row.get("instanceId")))).toList();
        if(!previous.equals(targets))throw new ControlFailure(409,"IDEMPOTENCY_CONFLICT");
        return job;
    }
    public static String artifactSha(Path path) {
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            var digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];int n;
            while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);
            return HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){throw new ControlFailure(409,"ARTIFACT_UNAVAILABLE");}
    }
    public synchronized Map<String,Object> execute(String actor,UUID id,String releaseId,String expectedSha,List<Target> targets,Runnable guard) {
        return execute(actor,id,releaseId,expectedSha,targets,null,guard);
    }
    public synchronized Map<String,Object> execute(String actor,UUID id,String releaseId,String expectedSha,List<Target> targets,String expectedConfigurationHash,Runnable guard) {
        if(Files.exists(path(id)))return existing(id,releaseId,expectedSha,targets);
        if(closed)throw new ControlFailure(503,"DEPLOYMENT_EXECUTOR_CLOSED");
        if(!enabled()){audit.append(id,actor,"DEPLOYMENT_EXECUTE",releaseId,"REJECTED_LOCKED");throw new ControlFailure(423,"DEPLOYMENT_LOCKED");}
        validateConfiguration();
        if(targets.isEmpty() || targets.size()>16 || new HashSet<>(targets.stream().map(Target::key).toList()).size()!=targets.size())throw new ControlFailure(400,"INVALID_DEPLOYMENT_TARGETS");
        if(!blockers(releaseId,targets).isEmpty())throw new ControlFailure(409,"DEPLOYMENT_PREREQUISITES_REQUIRED");
        String admittedConfiguration=expectedConfigurationHash==null?configurationHash(releaseId,targets):expectedConfigurationHash;
        guard.run();var release=settings.getReleases().get(releaseId);
        if(!Objects.equals(expectedSha,release.getSha256()) || !expectedSha.equals(artifactSha(release.getArtifact())))throw new ControlFailure(409,"ARTIFACT_SHA256_MISMATCH");
        FileChannel channel=null;FileLock lock=null;
        try {
            channel=FileChannel.open(audit.directory().resolve("deployment.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try{lock=channel.tryLock();}catch(OverlappingFileLockException ignored){}
            if(lock==null)throw new ControlFailure(409,"DEPLOYMENT_ALREADY_ACTIVE");
            // Check again under the cross-process lock, including the same plan submitted by another JVM.
            if(Files.exists(path(id)))return existing(id,releaseId,expectedSha,targets);
            admissionGuard.run();guard.run();
            if(!Objects.equals(admittedConfiguration,configurationHash(releaseId,targets)))throw new ControlFailure(409,"DEPLOYMENT_PLAN_RECREATE_REQUIRED");
            try(var files=Files.list(audit.directory())){
                for(Path file:files.filter(p->p.getFileName().toString().matches("(?:deployment|server-job|server-cluster-job|controller-job)-[0-9a-f-]{36}\\.json")).toList())
                    {var previous=read(file);if(Set.of("QUEUED","RUNNING","OUTCOME_UNKNOWN").contains(previous.get("state"))||unfinalizedFence(previous))throw new ControlFailure(409,"DEPLOYMENT_RECONCILIATION_REQUIRED");}
            }
            String external=idleFenceReason();if(external!=null)throw new ControlFailure(409,external);
            Path artifact=audit.directory().resolve("artifact-"+id+".jar");
            Files.createFile(artifact,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try(var in=Files.newInputStream(release.getArtifact(),LinkOption.NOFOLLOW_LINKS);var out=Files.newOutputStream(artifact)){in.transferTo(out);}
            if(!expectedSha.equals(artifactSha(artifact))){Files.deleteIfExists(artifact);throw new ControlFailure(409,"ARTIFACT_SHA256_MISMATCH");}
            var job=new LinkedHashMap<String,Object>();job.put("id",id.toString());job.put("releaseId",releaseId);job.put("artifactSha256",expectedSha);job.put("state","QUEUED");job.put("createdAt",Instant.now().toString());
            String manifestHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(List.of(admittedConfiguration,id.toString(),UUID.randomUUID().toString()))));
            var rows=new ArrayList<Map<String,Object>>();var frozen=new ArrayList<ControlProperties.DeploymentTarget>();
            for(var t:targets){var binding=resolve(t);var row=new LinkedHashMap<String,Object>(Map.of("site",t.key(),"siteId",t.siteId(),"instanceId",t.instanceId().toString(),"state","PENDING","fromSha256",binding.getCurrentSha256()));
                if(binding.getServerId()!=null){row.put("serverId",binding.getServerId().toString());row.put("serverRevision",binding.getServerRevision());row.put("membershipEpoch",binding.getMembershipEpoch());}
                var snapshot=json.readValue(json.writeValueAsString(binding),ControlProperties.DeploymentTarget.class);if(snapshot.getServerId()!=null)snapshot.setDeploymentManifestHash(manifestHash);rows.add(row);frozen.add(snapshot);}
            job.put("targets",rows);job.put("_targets",frozen);job.put("_manifestHash",manifestHash);job.put("_configurationHash",admittedConfiguration);job.put("_registered",frozen.stream().anyMatch(t->t.getServerId()!=null));job.put("_remoteDispatchAllowed",false);audit.append(id,actor,"DEPLOYMENT_EXECUTE",releaseId,"ACCEPTED");save(id,job);
            if(Boolean.TRUE.equals(job.get("_registered"))){
                try{registered.reserve(id,manifestHash,frozen);}
                catch(AdmissionRejected rejected){job.put("state","FAILED");job.put("reason",rejected.code);job.put("_fenceFinalized",true);save(id,job);Files.deleteIfExists(artifact);return publicJob(job);}
                catch(Exception uncertain){job.put("state","OUTCOME_UNKNOWN");job.put("reason","DEPLOYMENT_ADMISSION_OUTCOME_UNKNOWN");save(id,job);Files.deleteIfExists(artifact);return publicJob(job);}
            }
            job.put("_remoteDispatchAllowed",true);save(id,job);
            owned.add(id);
            FileChannel heldChannel=channel;FileLock heldLock=lock;
            try{worker.submit(()->run(actor,id,artifact,expectedSha,job,frozen,guard,heldChannel,heldLock));}catch(RejectedExecutionException stopped){owned.remove(id);job.put("state","OUTCOME_UNKNOWN");job.put("reason","EXECUTOR_DISPATCH_NOT_CONFIRMED");save(id,job);Files.deleteIfExists(artifact);return publicJob(job);}channel=null;lock=null;
            return get(id);
        }catch(ControlFailure e){throw e;}catch(you.v50to.eatwhat.cluster.ClusterFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"DEPLOYMENT_STATE_UNAVAILABLE");}
        finally{try{if(lock!=null)lock.release();if(channel!=null)channel.close();}catch(IOException ignored){}}
    }
    @SuppressWarnings("unchecked") private void run(String actor,UUID id,Path artifact,String sha,Map<String,Object> job,List<ControlProperties.DeploymentTarget> targets,Runnable guard,FileChannel channel,FileLock lock){
        var rows=(List<Map<String,Object>>)job.get("targets");int applied=0;boolean applying=false;
        try {
            job.put("state","RUNNING");job.put("stage","PREFLIGHT");save(id,job);
            for(int i=0;i<targets.size();i++){
                guard.run();var row=rows.get(i);row.put("state","PREFLIGHT");save(id,job);
                verifyReservation(id,job,true);
                verifyReply(call("prepare",id,targets.get(i),artifact,sha),id,targets.get(i),sha,"PREPARED");row.put("state","PREPARED");save(id,job);
            }
            for(int i=0;i<targets.size();i++){
                guard.run();var row=rows.get(i);row.put("state","DEPLOYING");job.put("stage","APPLY");save(id,job);audit.append(id,actor,"DEPLOY_SITE",(String)row.get("site"),"REQUESTED");applying=true;
                verifyReservation(id,job,true);
                var reply=call("apply",id,targets.get(i),artifact,sha);String state=Objects.toString(reply.get("state"),"");
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.getDeployment().getTimeoutSeconds());
                while("RUNNING".equals(state)){
                    verifyReply(reply,id,targets.get(i),sha,state);
                    if(reply.get("stage") instanceof String stage&&stage.matches("[A-Z][A-Z0-9_]{0,63}")){row.put("stage",stage);job.put("stage",stage);}
                    save(id,job);if(System.nanoTime()>=deadline)throw new IOException();Thread.sleep(1000);
                    // Dispatch happened exactly once above. Polling never resends an update.
                    reply=call("status",id,targets.get(i),null,sha);state=Objects.toString(reply.get("state"),"");
                }
                if(!Set.of("SUCCEEDED","ROLLED_BACK","ROLLBACK_FAILED","FAILED").contains(state))throw new IOException();
                verifyReply(reply,id,targets.get(i),sha,state);row.put("state",state);
                if(reply.get("code") instanceof String code&&code.matches("RELEASE_[A-Z0-9_]{1,110}"))row.put("code",code);
                if(reply.get("stage") instanceof String stage&&stage.matches("[A-Z][A-Z0-9_]{0,63}"))row.put("stage",stage);
                if(reply.get("failureStage") instanceof String stage&&stage.matches("[A-Z][A-Z0-9_]{0,63}")){row.put("failureStage",stage);job.put("stage",stage);}
                record(id,targets.get(i),sha,reply);
                if(reply.get("backupId") instanceof String backup && backup.matches("[0-9a-f-]{36}"))row.put("backupId",backup);
                applying=false;save(id,job);audit.append(id,actor,"DEPLOY_SITE",(String)row.get("site"),state);
                if(!state.equals("SUCCEEDED")){job.put("state",state.equals("ROLLBACK_FAILED")?"OUTCOME_UNKNOWN":applied>0?"PARTIAL_FAILURE":"FAILED");job.put("reason",row.getOrDefault("code",state));settle(id,job);return;}applied++;
            }
            job.put("state","SUCCEEDED");job.put("stage","COMPLETE");settle(id,job);audit.append(id,actor,"DEPLOYMENT_EXECUTE",(String)job.get("releaseId"),job.get("state").toString());
        }catch(Exception e){job.put("state",applying?"OUTCOME_UNKNOWN":applied>0?"PARTIAL_FAILURE":"FAILED");job.put("reason",applying?"REMOTE_OUTCOME_REQUIRES_RECONCILIATION":"PREFLIGHT_OR_CONTROL_GUARD_FAILED");
            try{settle(id,job);audit.append(id,actor,"DEPLOYMENT_EXECUTE",(String)job.get("releaseId"),(String)job.get("state"));}catch(Exception ignored){}
        }finally{try{Files.deleteIfExists(artifact);}catch(Exception ignored){}
            try{lock.release();channel.close();}catch(Exception ignored){}finally{owned.remove(id);}}
    }
    /** Read remote journals only. Reconciliation never resends an update or restores a database. */
    @SuppressWarnings("unchecked") public synchronized Map<String,Object> reconcile(String actor,UUID id){
        if(owned.contains(id))throw new ControlFailure(409,"DEPLOYMENT_ALREADY_ACTIVE");
        try(var channel=FileChannel.open(audit.directory().resolve("deployment.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException e){throw new ControlFailure(409,"DEPLOYMENT_ALREADY_ACTIVE");}
            if(lock==null)throw new ControlFailure(409,"DEPLOYMENT_ALREADY_ACTIVE");
            try(lock){
                var job=read(path(id));var state=Objects.toString(job.get("state"),"");
                if(!Set.of("QUEUED","RUNNING","OUTCOME_UNKNOWN").contains(state)&&!unfinalizedFence(job))return publicJob(job);
                audit.append(id,actor,"DEPLOYMENT_RECONCILE",(String)job.get("releaseId"),"REQUESTED");
                var rows=(List<Map<String,Object>>)job.get("targets");boolean unknown=false;int success=0;
                var saved=job.get("_targets") instanceof List<?> list?list:List.of();
                if(saved.size()!=rows.size()){job.put("state","OUTCOME_UNKNOWN");job.put("reason","FROZEN_DEPLOYMENT_BINDING_REQUIRED");save(id,job);return publicJob(job);}
                verifyReservation(id,job,false);
                if(Boolean.TRUE.equals(job.get("_registered"))&&Boolean.FALSE.equals(job.get("_remoteDispatchAllowed"))&&registered.noDispatchProven(id,Objects.toString(job.get("_manifestHash"),""))){for(var row:rows)row.put("state","NOT_STARTED");job.put("state","FAILED");job.put("reason","AUTHORITY_NO_DISPATCH_PROVEN");settle(id,job);return publicJob(job);}
                for(int index=0;index<rows.size();index++){var row=rows.get(index);
                    try{
                        var target=json.readValue(json.writeValueAsString(saved.get(index)),ControlProperties.DeploymentTarget.class);
                        if(target==null || !target.getSiteId().equals(row.get("siteId")) || !target.getInstanceId().toString().equals(row.get("instanceId")) || !target.getCurrentSha256().equals(row.get("fromSha256")))throw new IOException();
                        var r=call("status",id,target,null,(String)job.get("artifactSha256"));String remoteState=Objects.toString(r.get("state"),"");
                        verifyReply(r,id,target,(String)job.get("artifactSha256"),remoteState);
                        if(!Set.of("SUCCEEDED","ROLLED_BACK","FAILED","NOT_STARTED").contains(remoteState))throw new IOException();
                        record(id,target,(String)job.get("artifactSha256"),r);
                        row.put("state",remoteState);if(remoteState.equals("SUCCEEDED"))success++;
                    }catch(Exception e){row.put("state","OUTCOME_UNKNOWN");unknown=true;}
                }
                job.put("state",unknown?"OUTCOME_UNKNOWN":success==rows.size()?"SUCCEEDED":success>0?"PARTIAL_FAILURE":"FAILED");
                job.put("reason",unknown?"REMOTE_OUTCOME_REQUIRES_RECONCILIATION":"REMOTE_JOURNALS_RECONCILED");settle(id,job);
                audit.append(id,actor,"DEPLOYMENT_RECONCILE",(String)job.get("releaseId"),(String)job.get("state"));return publicJob(job);
            }
        }catch(ControlFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"DEPLOYMENT_RECONCILIATION_FAILED");}
    }
    private static void verifyReply(Map<String,Object> r,UUID id,ControlProperties.DeploymentTarget t,String sha,String state)throws IOException{
        if(!id.toString().equals(r.get("id")) || !t.getSiteId().equals(r.get("siteId")) || !t.getInstanceId().toString().equals(r.get("instanceId")) || !sha.equals(r.get("artifactSha256")) || !state.equals(r.get("state")))throw new IOException();
    }
    private Map<String,Object> call(String action,UUID id,ControlProperties.DeploymentTarget target,Path artifact,String sha)throws Exception{
        if(target.getServerId()==null)return driver.call(action,id,target,artifact,sha);
        if(registered==null||!registered.available())throw new ControlFailure(503,"REGISTERED_DEPLOYMENT_PROTOCOL_NOT_READY");return registered.call(action,id,target,artifact,sha);
    }
    public Map<String,Object> runtimeArtifact(){return runtimeArtifactSha==null?Map.of("status","UNAVAILABLE"):Map.of("status","VERIFIED_AT_STARTUP","sha256",runtimeArtifactSha);}
    private void record(UUID id,ControlProperties.DeploymentTarget target,String sha,Map<String,Object> result){if(target.getServerId()!=null){if(registered==null)throw new ControlFailure(503,"REGISTERED_DEPLOYMENT_UNAVAILABLE");registered.record(id,target,sha,result);}}
    private void verifyReservation(UUID id,Map<String,Object> job,boolean applying){if(Boolean.TRUE.equals(job.get("_registered"))){if(registered==null)throw new ControlFailure(503,"REGISTERED_DEPLOYMENT_UNAVAILABLE");registered.verifyReservation(id,Objects.toString(job.get("_manifestHash"),""),applying);}}
    private boolean unfinalizedFence(Map<String,Object> job){
        if(!Boolean.TRUE.equals(job.get("_registered"))||Boolean.TRUE.equals(job.get("_fenceFinalized"))||!Set.of("SUCCEEDED","FAILED","PARTIAL_FAILURE").contains(job.get("state")))return false;
        try{return registered==null||!registered.finalized(UUID.fromString(job.get("id").toString()),Objects.toString(job.get("_manifestHash"),""),job);}catch(Exception unavailable){return true;}
    }
    private void settle(UUID id,Map<String,Object> job){
        save(id,job);
        if(Boolean.TRUE.equals(job.get("_registered"))&&Set.of("SUCCEEDED","FAILED","PARTIAL_FAILURE").contains(job.get("state"))){
            try{if(registered==null)throw new ControlFailure(503,"REGISTERED_DEPLOYMENT_UNAVAILABLE");registered.complete(id,Objects.toString(job.get("_manifestHash"),""),job);job.put("_fenceFinalized",true);}
            catch(Exception unavailable){job.put("state","OUTCOME_UNKNOWN");job.put("reason","DEPLOYMENT_FENCE_FINALIZATION_REQUIRED");}save(id,job);
        }
    }
    private Map<String,Object> ssh(String action,UUID id,ControlProperties.DeploymentTarget target,Path artifact,String sha)throws Exception{
        var d=settings.getDeployment();String remote="python3 "+target.getAgent()+" "+target.getConfig()+" "+action+" "+id+" "+sha+" "+target.getSiteId()+" "+target.getInstanceId()+" "+target.getCurrentSha256();
        var command=List.of(d.getSshExecutable().toString(),"-F","/dev/null","-o","BatchMode=yes","-o","StrictHostKeyChecking=yes","-o","IdentitiesOnly=yes","-o","PasswordAuthentication=no","-o","ConnectTimeout=10","-o","ServerAliveInterval=15","-o","ServerAliveCountMax=3","-o","UserKnownHostsFile="+d.getKnownHostsFile(),"-i",d.getIdentityFile().toString(),"-p",String.valueOf(target.getPort()),"--",target.getUser()+"@"+target.getHost(),remote);
        Process p=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();var io=Executors.newVirtualThreadPerTaskExecutor();
        try{
            var output=io.submit(()->p.getInputStream().readNBytes(65537));var upload=io.submit(()->{try(var out=p.getOutputStream()){if(action.equals("apply"))Files.copy(artifact,out);}return true;});
            if(!p.waitFor(d.getTimeoutSeconds(),TimeUnit.SECONDS))throw new IOException();upload.get(2,TimeUnit.SECONDS);byte[] bytes=output.get(2,TimeUnit.SECONDS);
            if(p.exitValue()!=0 || bytes.length>65536)throw new IOException();return json.readValue(bytes,Map.class);
        }finally{p.destroyForcibly();io.shutdownNow();}
    }
    private Path path(UUID id){return audit.directory().resolve("deployment-"+id+".json");}
    private Map<String,Object> read(Path p){try{if(Files.isSymbolicLink(p)||Files.size(p)>1024*1024)throw new IOException();return json.readValue(Files.readString(p),Map.class);}catch(Exception e){throw new ControlFailure(503,"DEPLOYMENT_STATE_UNAVAILABLE");}}
    public Map<String,Object> get(UUID id){if(!Files.exists(path(id)))throw new ControlFailure(404,"DEPLOYMENT_NOT_FOUND");boolean active=owned.contains(id);var job=read(path(id));
        if(active && !Set.of("QUEUED","RUNNING").contains(job.get("state")))job.put("state","RUNNING");
        if(!active&&Set.of("QUEUED","RUNNING").contains(job.get("state"))){job.put("state","OUTCOME_UNKNOWN");job.put("reason","EXECUTOR_PROCESS_NOT_OWNED_CHECK_REMOTE_JOURNALS");}
        if(!active&&unfinalizedFence(job)){job.put("state","OUTCOME_UNKNOWN");job.put("reason","DEPLOYMENT_FENCE_FINALIZATION_REQUIRED");}return publicJob(job);}
    private static Map<String,Object> publicJob(Map<String,Object> job){var result=new LinkedHashMap<>(job);result.keySet().removeIf(key->key.startsWith("_"));return result;}
    private void save(UUID id,Map<String,Object> value){try{
        Path tmp=Files.createTempFile(audit.directory(),"deployment-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{byte[] bytes=json.writeValueAsBytes(value);try(var ch=FileChannel.open(tmp,StandardOpenOption.WRITE)){var b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())ch.write(b);ch.force(true);}Files.move(tmp,path(id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);try(var directory=FileChannel.open(audit.directory(),StandardOpenOption.READ)){directory.force(true);}}finally{Files.deleteIfExists(tmp);}
    }catch(Exception e){throw new ControlFailure(503,"DEPLOYMENT_STATE_UNAVAILABLE");}}
    @Override public void close(){closed=true;worker.shutdown();try{if(!worker.awaitTermination(2,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){worker.shutdownNow();Thread.currentThread().interrupt();}}
}
