package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.ClusterFailure;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;

/** Durable composite approval/phase journal. No SSH, SQL or package command is run here.
 * The installer must validate real phase receipts before recording them. There is
 * no caller may turn an unknown observation into new dispatch permission.
 */
public final class ControlBootstrapJournal {
    public enum Phase {
        SOURCE_RUNTIME("eatwhat-controller-bootstrap-runtime-v1", "RUNTIME_READY"),
        TARGET_RUNTIME("eatwhat-controller-bootstrap-runtime-v1", "RUNTIME_READY"),
        SOURCE_PRIVATE_PEER("eatwhat-controller-bootstrap-link-v1", "CONFIGURED"),
        TARGET_PRIVATE_PEER("eatwhat-controller-bootstrap-link-v1", "CONFIGURED"),
        SOURCE_SHARED_STATE("eatwhat-controller-bootstrap-state-v1", "EXPORTED"),
        TARGET_SHARED_STATE("eatwhat-controller-bootstrap-state-v1", "MOUNTED"),
        DEPENDENCY_LINK("eatwhat-controller-bootstrap-dependency-v1", "VERIFIED"),
        CONTROLLER_CONFIG("eatwhat-controller-bootstrap-controller-v1", "STAGED"),
        CONTROLLER_VERIFY("eatwhat-controller-bootstrap-controller-v1", "VERIFIED"),
        BUSINESS_INSTALL("eatwhat-install-v1", "SUCCEEDED"),
        MEMBER_CONFIGURE("eatwhat-member-configure-v1", "CONFIGURED"),
        MEMBER_JOIN("eatwhat-controller-bootstrap-membership-v1", "ACTIVE"),
        VERIFY("eatwhat-controller-bootstrap-verification-v1", "VERIFIED");
        final String protocol, success;
        Phase(String protocol,String success){this.protocol=protocol;this.success=success;}
    }
    public record Spec(UUID id,UUID sourceServerId,long sourceRevision,UUID targetServerId,long targetRevision,
                       UUID clusterId,long epoch,String controllerSha256,String releaseId,String businessSha256,int allocationSlot) {}
    public record Dispatch(UUID id,boolean dispatchAllowed,String claimToken,String approvalDigest,Map<String,Object> privateInputs) {
        @Override public String toString(){return "BootstrapDispatch[redacted]";}
    }
    public enum DependencyOperation {
        KEY("TARGET","KEY_READY"),SOURCE("SOURCE","AUTHORIZED"),CONNECT("TARGET","VERIFIED");
        final String role,success;
        DependencyOperation(String role,String success){this.role=role;this.success=success;}
    }
    public record DependencyDispatch(UUID nativeId,DependencyOperation operation,boolean dispatchAllowed,String claimToken,
                                     String nativeBinding,String agentSha256,String approvalDigest,Map<String,Object> privateInputs) {
        @Override public String toString(){return "BootstrapDependencyDispatch[redacted]";}
    }
    public static final Set<String> PERMISSIONS=Set.of("INSTALL_RUNTIME_PACKAGES","ADD_EXACT_PRIVATE_PEER",
            "ALLOW_TARGET_UDP_LISTENER","SHARE_EXISTING_CONTROLLER_STATE","CREATE_FORWARDING_ONLY_IDENTITY","ALLOW_PRIVATE_DEPENDENCY_LISTENER",
            "FORWARD_PINNED_SOURCE_ADMIN_SSH","FORWARD_CAPTURED_MEMBER_ENDPOINTS","INSTALL_BUSINESS_AND_JOIN","ALLOW_BUSINESS_DOCKER_NETWORKING","ALLOW_DOCKER_FORWARD_POLICY_DROP","CREATE_BUSINESS_TLS_RELAY","CREATE_LIMITED_BUSINESS_AUTH_ROLE","CREATE_PRIVATE_MEMBER_HTTPS");
    private static final Set<String> TERMINAL=Set.of("SUCCEEDED","FAILED");
    private static final Set<String> STEP_STATES=Set.of("PENDING","DISPATCHED","RUNNING","OUTCOME_UNKNOWN","SUCCEEDED","FAILED");
    private static final Set<String> PERMANENT=Set.of("BOOTSTRAP_PLAN_CHANGED","SERVER_REVISION_CHANGED","SSH_ROUTE_BINDING_CHANGED","ADMIN_REQUIRED","STALE_MEMBERSHIP_EPOCH",
            "BOOTSTRAP_ARTIFACT_CHANGED","BOOTSTRAP_SOURCE_IDENTITY_CHANGED","BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED","BOOTSTRAP_MANAGED_AUTHORITY_REQUIRED","BOOTSTRAP_TARGET_BUSINESS_RETAINED","INSTALLATION_ALLOCATION_RETAINED");
    private static final Set<String> REVIEW_KEYS=Set.of("sourceAddress","targetAddress","targetEndpoint","wireguardPort","httpPort","appBaseUrl");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlServerStore store;
    private final ControlAudit audit;
    private final DeploymentExecutor deployment;
    private final ControlServers servers;

    public ControlBootstrapJournal(ControlServerStore store,ControlAudit audit,DeploymentExecutor deployment,ControlServers servers){
        this.store=store;this.audit=audit;this.deployment=deployment;this.servers=servers;
    }
    public static UUID phaseId(UUID id,Phase phase){return UUID.nameUUIDFromBytes((id+":"+phase.name()).getBytes(StandardCharsets.UTF_8));}
    private Path planPath(UUID id){return store.directory().resolve("controller-plan-"+id+".json");}
    private Path jobPath(UUID id){return store.directory().resolve("controller-job-"+id+".json");}
    private static void require(boolean value,String code){if(!value)throw new ControlFailure(409,code);}
    private static Object ordered(Object value){
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),ordered(v)));return result;}
        if(value instanceof List<?> list)return list.stream().map(ControlBootstrapJournal::ordered).toList();return value;
    }
    private static String encode(Object value){try{return JSON.writeValueAsString(ordered(JSON.readValue(JSON.writeValueAsString(value),Object.class)));}catch(Exception e){throw new ControlFailure(400,"BOOTSTRAP_INVALID_INPUT");}}
    private String seal(String value){return HexFormat.of().formatHex(Base64.getDecoder().decode(store.fingerprint(value)));}
    private static void privateAddress(String value){
        String[] parts=value.split("\\.",-1);require(parts.length==4,"BOOTSTRAP_PRIVATE_ADDRESS_REQUIRED");int[] octets=new int[4];
        for(int i=0;i<4;i++){try{octets[i]=Integer.parseInt(parts[i]);}catch(Exception e){throw new ControlFailure(400,"BOOTSTRAP_PRIVATE_ADDRESS_REQUIRED");}require(octets[i]>=0&&octets[i]<=255&&Integer.toString(octets[i]).equals(parts[i]),"BOOTSTRAP_PRIVATE_ADDRESS_REQUIRED");}
        require(octets[0]==10||octets[0]==172&&octets[1]>=16&&octets[1]<=31||octets[0]==192&&octets[1]==168,"BOOTSTRAP_PRIVATE_ADDRESS_REQUIRED");
    }
    private static void spec(Spec s,Map<String,Object> review){
        require(s!=null&&s.id()!=null&&s.sourceServerId()!=null&&s.targetServerId()!=null&&s.clusterId()!=null,"BOOTSTRAP_INVALID_IDENTITY");
        validateRequest(s.id(),s.sourceServerId(),s.sourceRevision(),s.targetServerId(),s.targetRevision(),s.releaseId(),s.allocationSlot(),review);
        require(!s.clusterId().equals(new UUID(0,0)),"BOOTSTRAP_INVALID_IDENTITY");require(s.epoch()>0,"BOOTSTRAP_INVALID_BINDING");
        require(s.controllerSha256()!=null&&s.controllerSha256().matches("[a-f0-9]{64}")&&s.businessSha256()!=null&&s.businessSha256().matches("[a-f0-9]{64}"),"BOOTSTRAP_INVALID_ARTIFACT");
    }
    static void validateRequest(UUID id,UUID source,long sourceRevision,UUID target,long targetRevision,String release,int slot,Map<String,Object> review){
        require(id!=null&&source!=null&&target!=null&&!id.equals(new UUID(0,0))&&!source.equals(new UUID(0,0))&&!target.equals(new UUID(0,0))&&!source.equals(target),"BOOTSTRAP_INVALID_IDENTITY");
        require(sourceRevision>0&&targetRevision>0&&slot>0&&slot<1024,"BOOTSTRAP_INVALID_BINDING");
        ControlService.identifier(release);require(review!=null&&review.keySet().equals(REVIEW_KEYS),"BOOTSTRAP_REVIEW_REQUIRED");
        for(String key:List.of("sourceAddress","targetAddress","targetEndpoint","appBaseUrl"))require(review.get(key) instanceof String text&&!text.isBlank()&&text.length()<=512&&!text.chars().anyMatch(Character::isISOControl),"BOOTSTRAP_REVIEW_REQUIRED");
        for(String key:List.of("wireguardPort","httpPort"))require(review.get(key) instanceof Integer port&&port>=1024&&port<=65535,"BOOTSTRAP_INVALID_PORT");
        privateAddress(review.get("sourceAddress").toString());privateAddress(review.get("targetAddress").toString());require(!review.get("sourceAddress").equals(review.get("targetAddress")),"BOOTSTRAP_DISTINCT_ADDRESSES_REQUIRED");
        ControlSsh.host(review.get("targetEndpoint").toString());require(!Set.of(18080,18081,25432,16379,28090).contains(review.get("httpPort")),"BOOTSTRAP_INVALID_PORT");
        try{var uri=java.net.URI.create(review.get("appBaseUrl").toString());require(Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null,"INVALID_APPLICATION_URL");}catch(IllegalArgumentException e){throw new ControlFailure(400,"INVALID_APPLICATION_URL");}
    }
    static void validateSpec(Spec spec,Map<String,Object> review){spec(spec,review);}
    public Map<String,Object> preview(String actor,Spec spec,Map<String,Object> review,Map<String,Object> privateInputs,Runnable guard){
        servers.requireWrite();spec(spec,review);require(actor!=null&&!actor.isBlank(),"ADMIN_REQUIRED");
        var value=new LinkedHashMap<String,Object>();value.put("workflowVersion",2);value.put("spec",spec);value.put("review",review);value.put("privateInputs",privateInputs);value.put("actor",actor);
        value.put("permissionsRequired",PERMISSIONS.stream().sorted().toList());
        String body=encode(value);require(body.getBytes(StandardCharsets.UTF_8).length<=262144,"BOOTSTRAP_PRIVATE_INPUT_TOO_LARGE");
        String seal=seal(body);
        if(Files.exists(planPath(spec.id()))){var prior=store.readJson(planPath(spec.id()));require(seal.equals(prior.get("requestHash")),"IDEMPOTENCY_CONFLICT");return publicView(prior);}
        var plan=new LinkedHashMap<String,Object>();plan.put("workflowVersion",2);plan.put("id",spec.id().toString());plan.put("kind","CONTROLLER_INSTALL");
        plan.put("sourceServerId",spec.sourceServerId().toString());plan.put("serverId",spec.targetServerId().toString());
        plan.put("sourceRevision",spec.sourceRevision());plan.put("serverRevision",spec.targetRevision());plan.put("clusterId",spec.clusterId().toString());plan.put("epoch",spec.epoch());
        plan.put("controllerSha256",spec.controllerSha256());plan.put("releaseId",spec.releaseId());plan.put("artifactSha256",spec.businessSha256());plan.put("allocationSlot",spec.allocationSlot());
        plan.put("review",new LinkedHashMap<>(review));plan.put("permissionsRequired",PERMISSIONS.stream().sorted().toList());
        if(privateInputs.containsKey("targetStorage")){
            var storage=ControlBootstrapStorage.read(privateInputs.get("targetStorage"));new ControlBootstrapStorage(store).open(storage,spec.id(),spec.targetServerId(),spec.targetRevision());
            plan.put("storage",storage.review());
        }
        plan.put("requestHash",seal);plan.put("state","PREVIEW_ONLY");plan.put("createdAt",Instant.now().toString());plan.put("expiresAt",Instant.now().plusSeconds(900).toString());
        plan.put("_actor",actor);plan.put("_body",store.encrypt(body));
        var steps=new ArrayList<Map<String,Object>>();for(var phase:Phase.values()){
            var step=new LinkedHashMap<String,Object>();step.put("phase",phase.name());step.put("id",phaseId(spec.id(),phase).toString());step.put("state","PENDING");
            if(phase==Phase.DEPENDENCY_LINK){
                var operations=new ArrayList<Map<String,Object>>();for(var op:DependencyOperation.values()){
                    var row=new LinkedHashMap<String,Object>();row.put("operation",op.name());row.put("role",op.role);
                    // KEY and CONNECT share the TARGET's native header/id.
                    // SOURCE uses the same id on the separately pinned source.
                    row.put("nativeId",step.get("id"));row.put("state","PENDING");operations.add(row);
                }step.put("operations",operations);
            }steps.add(step);
        }plan.put("steps",steps);
        String reason=deployment.whenIdle(()->{servers.requireWrite();guard.run();if(Files.exists(planPath(spec.id())))throw new ControlFailure(409,"IDEMPOTENCY_CONFLICT");audit.append(spec.id(),actor,"CONTROLLER_BOOTSTRAP_PREVIEW",spec.targetServerId().toString(),"PREVIEW_ONLY");store.saveJson(planPath(spec.id()),plan);});
        if(reason!=null)throw new ControlFailure(409,reason);return publicView(plan);
    }
    public Map<String,Object> accept(String actor,UUID id,Set<String> approvedPermissions,Runnable guard){
        servers.requireWrite();if(Files.exists(jobPath(id)))return job(id);
        var plan=store.readJson(planPath(id));require(actor.equals(plan.get("_actor")),"BOOTSTRAP_ORIGINAL_ADMIN_REQUIRED");
        var approved=sealedBody(plan);require(approved.containsKey("permissionsRequired"),"BOOTSTRAP_REAPPROVAL_REQUIRED");
        require(Integer.valueOf(2).equals(plan.get("workflowVersion")),"BOOTSTRAP_REAPPROVAL_REQUIRED");
        require("PREVIEW_ONLY".equals(plan.get("state"))&&Instant.now().isBefore(Instant.parse(plan.get("expiresAt").toString())),"BOOTSTRAP_PLAN_EXPIRED");
        require(PERMISSIONS.equals(approvedPermissions),"BOOTSTRAP_PERMISSION_APPROVAL_REQUIRED");
        require(encode(approvedPermissions.stream().sorted().toList()).equals(encode(approved.get("permissionsRequired"))),"BOOTSTRAP_REAPPROVAL_REQUIRED");
        String reason=deployment.whenIdle(()->{servers.requireWrite();guard.run();if(Files.exists(jobPath(id)))return;
            // The reviewed file may not change while a slow authority guard
            // runs. Re-read and authenticate it at the actual accept boundary.
            var latest=store.readJson(planPath(id));sealedBody(latest);
            require(Instant.now().isBefore(Instant.parse(latest.get("expiresAt").toString())),"BOOTSTRAP_PLAN_EXPIRED");
            require(encode(latest).equals(encode(plan)),"BOOTSTRAP_PLAN_CHANGED");
            var value=new LinkedHashMap<>(plan);value.put("state","QUEUED");value.put("stage",Phase.values()[0].name());value.put("acceptedAt",Instant.now().toString());
            audit.append(id,actor,"CONTROLLER_BOOTSTRAP_ACCEPT",value.get("serverId").toString(),"QUEUED");store.saveJson(jobPath(id),value);
        });if(reason!=null&&!Files.exists(jobPath(id)))throw new ControlFailure(409,reason);return job(id);
    }
    private record Held(FileChannel channel,FileLock lock) implements AutoCloseable {public void close()throws Exception{try{lock.close();}finally{channel.close();}}}
    private Held lock(){try{
        Path path=store.directory().resolve("deployment.lock");if(!Files.exists(path))try{Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
        var channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);try{var lock=channel.tryLock();if(lock==null)throw new ControlFailure(409,"DEPLOYMENT_RUNNING");return new Held(channel,lock);}catch(Exception e){channel.close();throw e;}
    }catch(ControlFailure e){throw e;}catch(OverlappingFileLockException e){throw new ControlFailure(409,"DEPLOYMENT_RUNNING");}catch(Exception e){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> steps(Map<String,Object> job){return (List<Map<String,Object>>)job.get("steps");}
    private static Map<String,Object> step(Map<String,Object> job,Phase phase){return steps(job).get(phase.ordinal());}
    @SuppressWarnings("unchecked") private Map<String,Object> sealedBody(Map<String,Object> job){
        var body=JSON.readValue(store.decrypt(job.get("_body").toString()),Map.class);
        require(seal(encode(body)).equals(job.get("requestHash")),"BOOTSTRAP_PLAN_CHANGED");
        if(body.containsKey("permissionsRequired")){
            Object permissions=body.get("permissionsRequired");
            require(permissions instanceof List<?> list&&!list.isEmpty()&&list.size()<=64
                    &&list.stream().allMatch(p->p instanceof String text&&text.matches("[A-Z][A-Z0-9_]{1,95}")),"BOOTSTRAP_PLAN_CHANGED");
            var sorted=((List<String>)permissions).stream().distinct().sorted().toList();
            require(encode(sorted).equals(encode(permissions))&&encode(permissions).equals(encode(job.get("permissionsRequired"))),"BOOTSTRAP_PLAN_CHANGED");
        }
        require("CONTROLLER_INSTALL".equals(job.get("kind"))&&Objects.equals(body.get("actor"),job.get("_actor"))
                &&encode(body.get("review")).equals(encode(job.get("review")))
                &&(!body.containsKey("workflowVersion")||Objects.equals(body.get("workflowVersion"),job.get("workflowVersion"))),"BOOTSTRAP_PLAN_CHANGED");
        require(body.get("spec") instanceof Map<?,?>,"BOOTSTRAP_PLAN_CHANGED");var spec=(Map<?,?>)body.get("spec");
        for(String key:List.of("id","sourceServerId","sourceRevision","clusterId","epoch","controllerSha256","releaseId","allocationSlot"))
            require(encode(spec.get(key)).equals(encode(job.get(key))),"BOOTSTRAP_PLAN_CHANGED");
        for(var entry:Map.of("targetServerId","serverId","targetRevision","serverRevision","businessSha256","artifactSha256").entrySet())
            require(encode(spec.get(entry.getKey())).equals(encode(job.get(entry.getValue()))),"BOOTSTRAP_PLAN_CHANGED");
        if(body.get("privateInputs") instanceof Map<?,?> inputs&&inputs.containsKey("targetStorage")){
            var storage=ControlBootstrapStorage.read(inputs.get("targetStorage"));
            new ControlBootstrapStorage(store).open(storage,UUID.fromString(spec.get("id").toString()),UUID.fromString(spec.get("targetServerId").toString()),((Number)spec.get("targetRevision")).longValue());
            require(encode(storage.review()).equals(encode(job.get("storage"))),"BOOTSTRAP_STORAGE_BINDING_CHANGED");
        }else require(!job.containsKey("storage"),"BOOTSTRAP_STORAGE_BINDING_CHANGED");
        return body;
    }
    Map<String,String> targetStorageProperties(ControlBootstrapAdmission.Frozen f){
        requireAdmission(f.spec().id(),f.spec(),f.actor(),f.review(),f.binding());
        var body=sealedBody(load(store.directory(),f.spec().id()));var inputs=(Map<?,?>)body.get("privateInputs");
        if(!inputs.containsKey("targetStorage"))return Map.of(); // Original approvals retain their SOURCE configuration.
        return new ControlBootstrapStorage(store).open(ControlBootstrapStorage.read(inputs.get("targetStorage")),f.spec().id(),f.spec().targetServerId(),f.spec().targetRevision());
    }
    @SuppressWarnings("unchecked") Set<String> approvedPermissions(UUID id){
        var body=sealedBody(load(store.directory(),id));
        require(body.get("permissionsRequired") instanceof List<?>,"BOOTSTRAP_REAPPROVAL_REQUIRED");return Set.copyOf((List<String>)body.get("permissionsRequired"));
    }
    private Dispatch descriptor(UUID id,Phase phase,Map<String,Object> job,boolean allowed){
        String token=store.fingerprint(id+":"+phase+":"+job.get("requestHash"));
        var body=sealedBody(job);
        return new Dispatch(phaseId(id,phase),allowed,token,job.get("requestHash").toString(),body);
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> operations(Map<String,Object> job){
        require(step(job,Phase.DEPENDENCY_LINK).get("operations") instanceof List<?>,"BOOTSTRAP_REAPPROVAL_REQUIRED");
        return (List<Map<String,Object>>)step(job,Phase.DEPENDENCY_LINK).get("operations");
    }
    private static Map<String,Object> operation(Map<String,Object> job,DependencyOperation op){return operations(job).get(op.ordinal());}
    private DependencyDispatch dependencyDescriptor(UUID id,DependencyOperation op,Map<String,Object> job,boolean allowed){
        var row=operation(job,op);var parent=descriptor(id,Phase.DEPENDENCY_LINK,job,false);
        String binding=row.get("_nativeBinding").toString(),agent=row.get("_agentSha256").toString();
        String token=store.fingerprint(id+":DEPENDENCY_LINK:"+op+":"+parent.approvalDigest()+":"+binding+":"+agent);
        return new DependencyDispatch(parent.id(),op,allowed,token,binding,agent,parent.approvalDigest(),parent.privateInputs());
    }
    private void checkGuard(UUID id,Phase phase,Map<String,Object> job,Runnable guard){
        require(!Boolean.TRUE.equals(job.get("_approvalInvalidated")),"BOOTSTRAP_REAPPROVAL_REQUIRED");servers.requireExecuting();
        try{require(sealedBody(job).containsKey("permissionsRequired"),"BOOTSTRAP_REAPPROVAL_REQUIRED");guard.run();}catch(ControlFailure failure){
            if(PERMANENT.contains(failure.code)){
                boolean dispatched=steps(job).stream().anyMatch(s->!"PENDING".equals(s.get("state")));
                job.put("state",dispatched?"OUTCOME_UNKNOWN":"FAILED");job.put("code",failure.code);job.put("effectsStarted",dispatched);
                if(dispatched)job.put("_approvalInvalidated",true);
                audit.append(id,job.get("_actor").toString(),"CONTROLLER_BOOTSTRAP_GUARD",phase.name(),failure.code);store.saveJson(jobPath(id),job);
            }throw failure;
        }
    }
    /** Three durable dispatch intents, never one permission to replay KEY.
     * Already dispatched operations return observation-only descriptors even
     * after approval invalidation; later undispatched operations recheck guards.
     */
    public DependencyDispatch claimDependencyOperation(UUID id,DependencyOperation op,String nativeBinding,String agentSha256,Runnable guard){
        return claimDependencyOperationFenced(id,op,nativeBinding,agentSha256,intent->{guard.run();intent.run();});
    }
    public DependencyDispatch claimDependencyOperationFenced(UUID id,DependencyOperation op,String nativeBinding,String agentSha256,java.util.function.Consumer<Runnable> boundary){
        try(var held=lock()){
            var job=load(store.directory(),id);require(!TERMINAL.contains(job.get("state")),"BOOTSTRAP_TASK_TERMINAL");
            for(int i=0;i<Phase.DEPENDENCY_LINK.ordinal();i++)require("SUCCEEDED".equals(steps(job).get(i).get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
            require(Integer.valueOf(2).equals(job.get("workflowVersion")),"BOOTSTRAP_REAPPROVAL_REQUIRED");
            require(nativeBinding!=null&&nativeBinding.matches("[a-f0-9]{64}")&&agentSha256!=null&&agentSha256.matches("[a-f0-9]{64}"),"BOOTSTRAP_DEPENDENCY_BINDING_REQUIRED");
            for(int i=0;i<op.ordinal();i++)require("SUCCEEDED".equals(operations(job).get(i).get("state")),"BOOTSTRAP_PREVIOUS_DEPENDENCY_OPERATION_REQUIRED");
            var current=operation(job,op);
            if(!"PENDING".equals(current.get("state"))){
                require(nativeBinding.equals(current.get("_nativeBinding"))&&agentSha256.equals(current.get("_agentSha256")),"BOOTSTRAP_DEPENDENCY_BINDING_CHANGED");
                return dependencyDescriptor(id,op,job,false);
            }
            if(op==DependencyOperation.CONNECT){var key=operation(job,DependencyOperation.KEY);
                require(nativeBinding.equals(key.get("_nativeBinding"))&&agentSha256.equals(key.get("_agentSha256")),"BOOTSTRAP_DEPENDENCY_BINDING_CHANGED");}
            checkGuard(id,Phase.DEPENDENCY_LINK,job,()->writeOnce(boundary,()->{
                String now=Instant.now().toString();current.put("_nativeBinding",nativeBinding);current.put("_agentSha256",agentSha256);
                current.put("state","DISPATCHED");current.put("dispatchedAt",now);
                var parent=step(job,Phase.DEPENDENCY_LINK);parent.put("state","DISPATCHED");parent.putIfAbsent("dispatchedAt",now);
                job.put("state","RUNNING");job.put("stage",Phase.DEPENDENCY_LINK.name());store.saveJson(jobPath(id),job);
            }));
            return dependencyDescriptor(id,op,job,true);
        }catch(ControlFailure e){throw e;}catch(ClusterFailure e){throw new ControlFailure(e.status(),e.getMessage());}catch(Exception e){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    public Map<String,Object> observeDependencyOperation(UUID id,DependencyOperation op,String token,Map<String,Object> receipt){
        try(var held=lock()){
            var job=load(store.directory(),id);require(!TERMINAL.contains(job.get("state")),"BOOTSTRAP_TASK_TERMINAL");var current=operation(job,op);
            require(!"PENDING".equals(current.get("state")),"BOOTSTRAP_PHASE_NOT_DISPATCHED");var dispatch=dependencyDescriptor(id,op,job,false);
            require(Objects.equals(token,dispatch.claimToken()),"BOOTSTRAP_PHASE_TOKEN_MISMATCH");
            require(dispatch.nativeId().toString().equals(receipt.get("id"))&&Phase.DEPENDENCY_LINK.protocol.equals(receipt.get("protocol"))
                    &&op.name().equals(receipt.get("operation"))&&op.role.equals(receipt.get("role"))&&dispatch.nativeBinding().equals(receipt.get("binding")),"BOOTSTRAP_PHASE_RECEIPT_MISMATCH");
            if("SUCCEEDED".equals(current.get("state")))return publicView(job);
            String raw=encode(receipt);require(raw.getBytes(StandardCharsets.UTF_8).length<=8192,"BOOTSTRAP_PHASE_RECEIPT_TOO_LARGE");
            String remote=Objects.toString(receipt.get("state"),""),now=Instant.now().toString();var parent=step(job,Phase.DEPENDENCY_LINK);
            current.put("observedAt",now);current.put("remoteState",remote.matches("[A-Z_]{1,64}")?remote:"UNRECOGNIZED");current.put("_receipt",store.encrypt(raw));
            parent.put("observedAt",now);
            if(op.success.equals(remote)){
                current.put("state","SUCCEEDED");current.put("finishedAt",now);parent.put("state","RUNNING");job.put("state","RUNNING");job.remove("code");
                if(op==DependencyOperation.CONNECT){parent.put("state","SUCCEEDED");parent.put("finishedAt",now);parent.put("remoteState","VERIFIED");job.put("stage",Phase.CONTROLLER_CONFIG.name());}
            }else if("FAILED".equals(remote)){
                current.put("state","FAILED");parent.put("state","FAILED");job.put("state","FAILED");job.put("code","BOOTSTRAP_PHASE_FAILED_PRESERVED");job.put("effectsStarted",true);
            }else if(Set.of("ACCEPTED","RUNNING").contains(remote)){
                current.put("state","RUNNING");parent.put("state","RUNNING");job.put("state","RUNNING");job.remove("code");
            }else{
                current.put("state","OUTCOME_UNKNOWN");parent.put("state","OUTCOME_UNKNOWN");job.put("state","OUTCOME_UNKNOWN");job.put("code","BOOTSTRAP_PHASE_RECONCILIATION_REQUIRED");
            }
            store.saveJson(jobPath(id),job);audit.append(id,job.get("_actor").toString(),"CONTROLLER_BOOTSTRAP_DEPENDENCY_OBSERVE",op.name(),job.get("state").toString());return publicView(job);
        }catch(ControlFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    public Dispatch claim(UUID id,Phase phase,Runnable guard){
        return claimFenced(id,phase,intent->{guard.run();intent.run();});
    }
    private static void writeOnce(java.util.function.Consumer<Runnable> boundary,Runnable intent){
        var attempted=new java.util.concurrent.atomic.AtomicBoolean();var written=new java.util.concurrent.atomic.AtomicBoolean();
        boundary.accept(()->{require(attempted.compareAndSet(false,true),"BOOTSTRAP_INTENT_ALREADY_WRITTEN");intent.run();written.set(true);});
        require(written.get(),"BOOTSTRAP_INTENT_NOT_WRITTEN");
    }
    /** The lease/state boundary encloses the actual forced intent write. A guard
     * which only checked SQL and then released its lock is insufficient here.
     */
    public Dispatch claimFenced(UUID id,Phase phase,java.util.function.Consumer<Runnable> boundary){
        try(var held=lock()){
            var job=load(store.directory(),id);require(!TERMINAL.contains(job.get("state")),"BOOTSTRAP_TASK_TERMINAL");
            for(int i=0;i<phase.ordinal();i++)require("SUCCEEDED".equals(steps(job).get(i).get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
            var current=step(job,phase);require(phase!=Phase.DEPENDENCY_LINK||!current.containsKey("operations"),"BOOTSTRAP_DEPENDENCY_OPERATION_REQUIRED");
            if(!"PENDING".equals(current.get("state")))return descriptor(id,phase,job,false);
            require(Integer.valueOf(2).equals(job.get("workflowVersion")),"BOOTSTRAP_REAPPROVAL_REQUIRED");
            checkGuard(id,phase,job,()->writeOnce(boundary,()->{
                // Forced to disk while the real lease/state locks remain held.
                current.put("state","DISPATCHED");current.put("dispatchedAt",Instant.now().toString());
                job.put("state","RUNNING");job.put("stage",phase.name());store.saveJson(jobPath(id),job);
            }));
            return descriptor(id,phase,job,true);
        }catch(ControlFailure e){throw e;}catch(ClusterFailure e){throw new ControlFailure(e.status(),e.getMessage());}catch(Exception e){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    /** A controller configuration claim contains preparation, bounded public
     * artifact transfer and STAGE. Every new child/chunk intent still needs the
     * real admission boundary; this never grants a second native worker start. */
    void controllerIntent(UUID id,java.util.function.Consumer<Runnable> boundary,Runnable intent){
        childIntent(id,Phase.CONTROLLER_CONFIG,"BOOTSTRAP_CONTROLLER_CLAIM_REQUIRED",boundary,intent);
    }
    /** Preparation and installation have separate durable intents. A claimed
     * business phase never authorizes starting its worker a second time. */
    void businessIntent(UUID id,java.util.function.Consumer<Runnable> boundary,Runnable intent){
        childIntent(id,Phase.BUSINESS_INSTALL,"BOOTSTRAP_BUSINESS_CLAIM_REQUIRED",boundary,intent);
    }
    void memberConfigurationIntent(UUID id,java.util.function.Consumer<Runnable> boundary,Runnable intent){
        childIntent(id,Phase.MEMBER_CONFIGURE,"BOOTSTRAP_MEMBER_CONFIGURATION_CLAIM_REQUIRED",boundary,intent);
    }
    void memberJoinIntent(UUID id,java.util.function.Consumer<Runnable> boundary,Runnable intent){
        childIntent(id,Phase.MEMBER_JOIN,"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED",boundary,intent);
    }
    String membershipInputBinding(UUID id){Object value=load(store.directory(),id).get("_membershipInputBinding");
        require(value==null||value instanceof String s&&s.matches("[A-Za-z0-9+/]{43}="),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");return (String)value;}
    void membershipInputIntent(UUID id,String binding,java.util.function.Consumer<Runnable> boundary,Runnable initialize){
        inputIntent(id,binding,"_membershipInputBinding","BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED",boundary,initialize);
    }
    String profileInputBinding(UUID id){Object value=load(store.directory(),id).get("_profileInputBinding");
        require(value==null||value instanceof String s&&s.matches("[A-Za-z0-9+/]{43}="),"BOOTSTRAP_PROFILE_RECORD_CHANGED");return (String)value;}
    /** Parent commitment precedes both private originals. Even deletion of the
     * input and ledger cannot silently recover fresh dispatch permission. */
    void profileInputIntent(UUID id,String binding,java.util.function.Consumer<Runnable> boundary,Runnable initialize){
        inputIntent(id,binding,"_profileInputBinding","BOOTSTRAP_PROFILE_RECORD_CHANGED",boundary,initialize);
    }
    private void inputIntent(UUID id,String binding,String key,String changed,java.util.function.Consumer<Runnable> boundary,Runnable initialize){
        require(binding!=null&&binding.matches("[A-Za-z0-9+/]{43}="),changed);
        try(var held=lock()){
            var job=load(store.directory(),id);require(!TERMINAL.contains(job.get("state"))&&!job.containsKey(key),changed);
            var phase=Phase.MEMBER_CONFIGURE;var row=step(job,phase);require(Set.of("DISPATCHED","RUNNING","OUTCOME_UNKNOWN").contains(row.get("state")),"BOOTSTRAP_MEMBER_CONFIGURATION_CLAIM_REQUIRED");
            for(int i=0;i<phase.ordinal();i++)require("SUCCEEDED".equals(steps(job).get(i).get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");descriptor(id,phase,job,false);
            checkGuard(id,phase,job,()->writeOnce(boundary,()->{job.put(key,binding);store.saveJson(jobPath(id),job);initialize.run();}));
        }catch(ControlFailure failure){throw failure;}catch(ClusterFailure failure){throw new ControlFailure(failure.status(),failure.getMessage());}
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_PROFILE_STATE_UNAVAILABLE");}
    }
    private void childIntent(UUID id,Phase phase,String claimRequired,java.util.function.Consumer<Runnable> boundary,Runnable intent){
        try(var held=lock()){
            var job=load(store.directory(),id);require(!TERMINAL.contains(job.get("state")),"BOOTSTRAP_TASK_TERMINAL");
            var row=step(job,phase);
            require(!Set.of("PENDING","SUCCEEDED","FAILED").contains(row.get("state")),claimRequired);
            for(int i=0;i<phase.ordinal();i++)require("SUCCEEDED".equals(steps(job).get(i).get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
            descriptor(id,phase,job,false);
            checkGuard(id,phase,job,()->writeOnce(boundary,intent));
        }catch(ControlFailure failure){throw failure;}catch(ClusterFailure failure){throw new ControlFailure(failure.status(),failure.getMessage());}
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    /** Confirmed local artifact drift is an observation, not a new dispatch.
     * Record it even when a subsequent authority probe is temporarily offline;
     * restoring the old file must not silently restore an invalidated approval. */
    void controllerArtifactDrift(UUID id){
        try(var held=lock()){
            var job=load(store.directory(),id);var row=step(job,Phase.CONTROLLER_CONFIG);
            require(!TERMINAL.contains(job.get("state"))&&!"PENDING".equals(row.get("state")),"BOOTSTRAP_CONTROLLER_CLAIM_REQUIRED");
            descriptor(id,Phase.CONTROLLER_CONFIG,job,false);
            job.put("state","OUTCOME_UNKNOWN");job.put("code","BOOTSTRAP_ARTIFACT_CHANGED");job.put("effectsStarted",true);job.put("_approvalInvalidated",true);
            audit.append(id,job.get("_actor").toString(),"CONTROLLER_BOOTSTRAP_GUARD",Phase.CONTROLLER_CONFIG.name(),"BOOTSTRAP_ARTIFACT_CHANGED");store.saveJson(jobPath(id),job);
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    public Map<String,Object> observe(UUID id,Phase phase,String token,Map<String,Object> receipt){
        try(var held=lock()){
            var job=load(store.directory(),id);require(!TERMINAL.contains(job.get("state")),"BOOTSTRAP_TASK_TERMINAL");
            var current=step(job,phase);require(phase!=Phase.DEPENDENCY_LINK||!current.containsKey("operations"),"BOOTSTRAP_DEPENDENCY_OPERATION_REQUIRED");
            require(!"PENDING".equals(current.get("state")),"BOOTSTRAP_PHASE_NOT_DISPATCHED");
            require(Objects.equals(token,descriptor(id,phase,job,false).claimToken()),"BOOTSTRAP_PHASE_TOKEN_MISMATCH");
            String remoteId=Objects.toString(receipt.get(phase==Phase.BUSINESS_INSTALL?"jobId":"id"),"");
            require(phaseId(id,phase).toString().equals(remoteId)&&phase.protocol.equals(receipt.get("protocol")),"BOOTSTRAP_PHASE_RECEIPT_MISMATCH");
            // Old successful observations must not replace a later phase's state.
            if("SUCCEEDED".equals(current.get("state")))return publicView(job);
            String remote=Objects.toString(receipt.get("state"),"");String now=Instant.now().toString();
            current.put("observedAt",now);current.put("remoteState",remote.matches("[A-Z_]{1,64}")?remote:"UNRECOGNIZED");
            if(phase.success.equals(remote)){
                current.put("state","SUCCEEDED");current.put("finishedAt",now);job.remove("code");
                job.put("state",phase==Phase.VERIFY?"SUCCEEDED":"RUNNING");job.put("stage",phase==Phase.VERIFY?"VERIFIED":Phase.values()[phase.ordinal()+1].name());
                if(phase==Phase.VERIFY)job.put("finishedAt",now);
            }else if("FAILED".equals(remote)){
                current.put("state","FAILED");job.put("state","FAILED");job.put("code","BOOTSTRAP_PHASE_FAILED_PRESERVED");job.put("effectsStarted",true);
            }else if(Set.of("ACCEPTED","RUNNING").contains(remote)){
                current.put("state","RUNNING");job.put("state","RUNNING");job.remove("code");
            }else{
                current.put("state","OUTCOME_UNKNOWN");job.put("state","OUTCOME_UNKNOWN");job.put("code","BOOTSTRAP_PHASE_RECONCILIATION_REQUIRED");
            }
            // Raw remote bodies may contain future private configuration. Encrypt
            // them; the public view only carries the allowlisted state summary.
            String rawReceipt=encode(receipt);require(rawReceipt.getBytes(StandardCharsets.UTF_8).length<=8192,"BOOTSTRAP_PHASE_RECEIPT_TOO_LARGE");
            current.put("_receipt",store.encrypt(rawReceipt));store.saveJson(jobPath(id),job);
            audit.append(id,job.get("_actor").toString(),"CONTROLLER_BOOTSTRAP_OBSERVE",phase.name(),job.get("state").toString());
            return publicView(job);
        }catch(ControlFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    public Map<String,Object> job(UUID id){return publicView(load(store.directory(),id));}
    public Map<String,Object> plan(UUID id){return publicView(loadFile(planPath(id),id));}
    Map<String,Object> existingPreview(UUID id,String actor,String creationBinding){
        if(!Files.exists(planPath(id),LinkOption.NOFOLLOW_LINKS))return null;
        var plan=loadFile(planPath(id),id);var body=sealedBody(plan);
        require(actor.equals(body.get("actor")),"BOOTSTRAP_ORIGINAL_ADMIN_REQUIRED");
        require(body.get("privateInputs") instanceof Map<?,?> inputs&&creationBinding.equals(inputs.get("creationBinding")),"IDEMPOTENCY_CONFLICT");
        return Files.exists(jobPath(id),LinkOption.NOFOLLOW_LINKS)?job(id):publicView(plan);
    }
    ControlBootstrapAdmission.Frozen originalAdmission(UUID id,boolean preview){
        var value=preview?loadFile(planPath(id),id):load(store.directory(),id);var body=sealedBody(value);
        require(body.get("privateInputs") instanceof Map<?,?>,"BOOTSTRAP_REAPPROVAL_REQUIRED");var inputs=(Map<?,?>)body.get("privateInputs");
        require("eatwhat-controller-bootstrap-managed-v1".equals(inputs.get("executionProtocol"))&&inputs.containsKey("admission"),"BOOTSTRAP_REAPPROVAL_REQUIRED");
        try{
            var f=JSON.readValue(JSON.writeValueAsBytes(inputs.get("admission")),ControlBootstrapAdmission.Frozen.class);
            require(encode(f.spec()).equals(encode(body.get("spec")))&&f.actor().equals(body.get("actor"))&&encode(f.review()).equals(encode(body.get("review"))),"BOOTSTRAP_PLAN_CHANGED");return f;
        }catch(ControlFailure failure){throw failure;}catch(Exception invalid){throw new ControlFailure(409,"BOOTSTRAP_PLAN_CHANGED");}
    }
    boolean accepted(UUID id){return Files.exists(jobPath(id),LinkOption.NOFOLLOW_LINKS);}
    private static boolean unstarted(Map<String,Object> job){
        if(Boolean.TRUE.equals(job.get("effectsStarted")))return false;
        return steps(job).stream().allMatch(row->"PENDING".equals(row.get("state"))&&!row.containsKey("dispatchedAt")&&!row.containsKey("_receipt"));
    }
    private boolean managed(Map<String,Object> job){var body=sealedBody(job);return body.get("privateInputs") instanceof Map<?,?> inputs&&"eatwhat-controller-bootstrap-managed-v1".equals(inputs.get("executionProtocol"));}
    private static boolean cancelled(Map<String,Object> job){
        if(!"BOOTSTRAP_CANCELLED_BEFORE_DISPATCH".equals(job.get("code")))return false;
        require("FAILED".equals(job.get("state"))&&Boolean.FALSE.equals(job.get("effectsStarted"))&&unstarted(job)
                &&job.get("_cancelledBy") instanceof String actor&&!actor.isBlank()&&job.get("finishedAt") instanceof String,"BOOTSTRAP_JOURNAL_INVALID");return true;
    }
    Map<String,Object> cancelUnstarted(UUID id,String actor,String requestHash,java.util.function.Consumer<Runnable> boundary){
        require(actor!=null&&!actor.isBlank(),"ADMIN_REQUIRED");require(requestHash!=null&&requestHash.matches("[a-f0-9]{64}"),"BOOTSTRAP_PLAN_CHANGED");
        try(var held=lock()){
            var job=load(store.directory(),id);require(managed(job),"BOOTSTRAP_REAPPROVAL_REQUIRED");
            require(Objects.equals(requestHash,job.get("requestHash")),"BOOTSTRAP_PLAN_CHANGED");
            boolean prior=cancelled(job);
            require(prior||!TERMINAL.contains(job.get("state")),"BOOTSTRAP_TASK_TERMINAL");
            require(unstarted(job),"BOOTSTRAP_DISPATCH_ALREADY_CLAIMED");
            writeOnce(boundary,()->{
                if(prior)return;
                job.put("state","FAILED");job.put("code","BOOTSTRAP_CANCELLED_BEFORE_DISPATCH");job.put("effectsStarted",false);
                job.put("_cancelledBy",actor);job.put("finishedAt",Instant.now().toString());
                audit.append(id,actor,"CONTROLLER_BOOTSTRAP_CANCEL",job.get("serverId").toString(),"CANCELLED_BEFORE_DISPATCH");store.saveJson(jobPath(id),job);
            });return publicView(job);
        }catch(ControlFailure failure){throw failure;}catch(ClusterFailure failure){throw new ControlFailure(failure.status(),failure.getMessage());}
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    /** Validated durable work, including uncertain outcomes which must continue
     * to block handoff. Historical internal jobs are listed but the worker must
     * additionally require the managed execution capsule. */
    List<UUID> pending(){
        try(var paths=Files.list(store.directory())){
            var result=new ArrayList<UUID>();for(var path:paths.filter(p->p.getFileName().toString().matches("controller-job-[0-9a-f-]{36}\\.json")).sorted().toList()){
                UUID id=UUID.fromString(path.getFileName().toString().substring(15,51));
                var value=load(store.directory(),id);sealedBody(value);if(!TERMINAL.contains(value.get("state")))result.add(id);
            }return List.copyOf(result);
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    String busyReason(){return pending().isEmpty()?null:"CONTROLLER_BOOTSTRAP_RECONCILIATION_REQUIRED";}
    /** Worker diagnostics never infer that a remote effect did not happen.
     * Codes are fixed identifiers; exception messages and private inputs never
     * enter the public task. A new controller cannot inherit the old approval. */
    void executionObservation(UUID id,String code,boolean invalidate){
        require(code==null||code.matches("[A-Z][A-Z0-9_]{0,127}"),"BOOTSTRAP_INVALID_INPUT");
        try(var held=lock()){
            var job=load(store.directory(),id);sealedBody(job);if(TERMINAL.contains(job.get("state")))return;
            if(code==null){if(!job.containsKey("executionCode"))return;job.remove("executionCode");job.remove("executionObservedAt");}
            else {if(code.equals(job.get("executionCode"))&&(!invalidate||Boolean.TRUE.equals(job.get("_approvalInvalidated"))))return;
                job.put("executionCode",code);job.put("executionObservedAt",Instant.now().toString());if(invalidate)job.put("_approvalInvalidated",true);}
            store.saveJson(jobPath(id),job);
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    /** Internal only. Successful native receipts may contain private metadata;
     * callers must bind them to the saved native header before using them. */
    @SuppressWarnings("unchecked") Map<String,Object> successfulReceipt(UUID id,Phase phase){
        var job=load(store.directory(),id);descriptor(id,phase,job,false);
        var row=step(job,phase);require("SUCCEEDED".equals(row.get("state"))&&row.get("_receipt") instanceof String,"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
        try{return JSON.readValue(store.decrypt(row.get("_receipt").toString()),Map.class);}
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    @SuppressWarnings("unchecked") Map<String,Object> successfulDependencyReceipt(UUID id,DependencyOperation op){
        var job=load(store.directory(),id);
        var row=operation(job,op);require("SUCCEEDED".equals(row.get("state"))&&row.get("_receipt") instanceof String,"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
        dependencyDescriptor(id,op,job,false);
        try{return JSON.readValue(store.decrypt(row.get("_receipt").toString()),Map.class);}
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}
    }
    /** Internal capsule check. A valid signed snapshot from another preview
     * must not be used as the guard for this job or for a misleading review.
     */
    public void requireAdmission(UUID id,Spec spec,String actor,Map<String,Object> review,String binding){
        var job=load(store.directory(),id);var body=descriptor(id,Phase.SOURCE_RUNTIME,job,false).privateInputs();
        require(id.equals(spec.id())&&Objects.equals(actor,body.get("actor"))&&encode(spec).equals(encode(body.get("spec")))
                &&encode(review).equals(encode(body.get("review")))&&body.get("privateInputs") instanceof Map<?,?> inputs
                &&inputs.get("admission") instanceof Map<?,?> admission&&Objects.equals(binding,admission.get("binding")),"BOOTSTRAP_PLAN_CHANGED");
    }
    private static Map<String,Object> load(Path directory,UUID id){return loadFile(directory.resolve("controller-job-"+id+".json"),id);}
    @SuppressWarnings("unchecked") private static Map<String,Object> loadFile(Path file,UUID id){try{
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new ControlFailure(404,file.getFileName().toString().startsWith("controller-plan-")?"BOOTSTRAP_PLAN_NOT_FOUND":"BOOTSTRAP_JOB_NOT_FOUND");
        ControlServerStore.privateFile(file,1024*1024);var job=JSON.readValue(Files.readString(file),LinkedHashMap.class);
        require(id.toString().equals(job.get("id"))&&"CONTROLLER_INSTALL".equals(job.get("kind")),"BOOTSTRAP_JOURNAL_INVALID");
        require(job.get("steps") instanceof List<?> list&&list.size()==Phase.values().length,"BOOTSTRAP_JOURNAL_INVALID");
        boolean gap=false;for(var phase:Phase.values()){var row=step(job,phase);require(phase.name().equals(row.get("phase"))&&phaseId(id,phase).toString().equals(row.get("id"))&&STEP_STATES.contains(row.get("state")),"BOOTSTRAP_JOURNAL_INVALID");if(gap)require("PENDING".equals(row.get("state")),"BOOTSTRAP_JOURNAL_INVALID");if(!"SUCCEEDED".equals(row.get("state")))gap=true;}
        var parent=step(job,Phase.DEPENDENCY_LINK);
        if(Integer.valueOf(2).equals(job.get("workflowVersion"))||parent.containsKey("operations")){
            require(Integer.valueOf(2).equals(job.get("workflowVersion"))&&parent.get("operations") instanceof List<?> list&&list.size()==3,"BOOTSTRAP_JOURNAL_INVALID");
            boolean operationGap=false,allPending=true;
            for(var op:DependencyOperation.values()){
                var row=operation(job,op);require(op.name().equals(row.get("operation"))&&op.role.equals(row.get("role"))
                        &&parent.get("id").equals(row.get("nativeId"))&&STEP_STATES.contains(row.get("state")),"BOOTSTRAP_JOURNAL_INVALID");
                if(operationGap)require("PENDING".equals(row.get("state")),"BOOTSTRAP_JOURNAL_INVALID");
                if(!"SUCCEEDED".equals(row.get("state")))operationGap=true;
                if(!"PENDING".equals(row.get("state"))){allPending=false;
                    require(row.get("_nativeBinding") instanceof String binding&&binding.matches("[a-f0-9]{64}")
                            &&row.get("_agentSha256") instanceof String agent&&agent.matches("[a-f0-9]{64}"),"BOOTSTRAP_JOURNAL_INVALID");
                    if(op==DependencyOperation.CONNECT){var key=operation(job,DependencyOperation.KEY);
                        require(row.get("_nativeBinding").equals(key.get("_nativeBinding"))&&row.get("_agentSha256").equals(key.get("_agentSha256")),"BOOTSTRAP_JOURNAL_INVALID");}
                }
            }
            require(!"SUCCEEDED".equals(parent.get("state"))||!operationGap,"BOOTSTRAP_JOURNAL_INVALID");
            require(!"PENDING".equals(parent.get("state"))||allPending,"BOOTSTRAP_JOURNAL_INVALID");
        }
        if("SUCCEEDED".equals(job.get("state")))require(!gap,"BOOTSTRAP_JOURNAL_INVALID");
        return job;
    }catch(ControlFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"BOOTSTRAP_JOURNAL_UNAVAILABLE");}}
    private Map<String,Object> publicView(Map<String,Object> job){
        boolean permissionsBound=sealedBody(job).containsKey("permissionsRequired");
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("workflowVersion","id","kind","sourceServerId","serverId","sourceRevision","serverRevision","clusterId","epoch","controllerSha256","releaseId","artifactSha256","allocationSlot","review","storage","permissionsRequired","requestHash","state","stage","code","executionCode","executionObservedAt","effectsStarted","createdAt","acceptedAt","expiresAt","finishedAt"))if(job.containsKey(key))result.put(key,job.get(key));
        var rows=new ArrayList<Map<String,Object>>();boolean uncertain=false,active=false;
        for(var value:steps(job)){
            var row=new LinkedHashMap<String,Object>();for(String key:List.of("phase","id","state","dispatchedAt","observedAt","remoteState","finishedAt"))if(value.containsKey(key))row.put(key,value.get(key));rows.add(row);
            List<Map<String,Object>> observations=List.of(value);
            if(value.containsKey("operations")){
                var children=new ArrayList<Map<String,Object>>();
                @SuppressWarnings("unchecked") var original=(List<Map<String,Object>>)value.get("operations");observations=original;
                for(var child:original){var safe=new LinkedHashMap<String,Object>();
                    for(String key:List.of("operation","nativeId","role","state","dispatchedAt","observedAt","remoteState","finishedAt"))if(child.containsKey(key))safe.put(key,child.get(key));children.add(safe);
                }row.put("operations",children);
            }
            for(var observation:observations){
                if(Set.of("DISPATCHED","OUTCOME_UNKNOWN").contains(observation.get("state")))uncertain=true;
                if("RUNNING".equals(observation.get("state"))){active=true;try{if(Instant.parse(observation.get("observedAt").toString()).plusSeconds(15).isBefore(Instant.now()))uncertain=true;}catch(Exception e){uncertain=true;}}
            }
        }
        uncertain=uncertain||"OUTCOME_UNKNOWN".equals(job.get("state"))||Boolean.TRUE.equals(job.get("_approvalInvalidated"));
        if(!TERMINAL.contains(job.get("state"))&&uncertain){result.put("state","OUTCOME_UNKNOWN");result.putIfAbsent("code","BOOTSTRAP_REMOTE_OBSERVATION_REQUIRED");}
        else if("RUNNING".equals(job.get("state"))&&!active)result.put("state","QUEUED");
        boolean cancelled=cancelled(job);result.put("cancelledBeforeDispatch",cancelled);
        result.put("canCancelBeforeDispatch",managed(job)&&Set.of("QUEUED","RUNNING","OUTCOME_UNKNOWN").contains(job.get("state"))&&unstarted(job));
        result.put("requiresReapproval",!cancelled&&(!permissionsBound&&!TERMINAL.contains(job.get("state"))||Boolean.TRUE.equals(job.get("_approvalInvalidated"))));result.put("requiresReconciliation",uncertain&&!TERMINAL.contains(job.get("state")));result.put("steps",rows);return result;
    }
}
