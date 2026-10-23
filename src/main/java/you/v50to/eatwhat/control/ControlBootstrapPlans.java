package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Builds an original review from registered hosts, actual authority/source
 * capture and reviewed local artifacts. No browser paths or property maps. */
public final class ControlBootstrapPlans {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlServers servers;private final ControlServerStore store;private final ControlBootstrapAdmission admission;
    private final ControlBootstrapSources sources;private final ControlBootstrapPreflights preflights;private final ControlBootstrapArtifacts artifacts;
    private final ControlBootstrapTargetNetwork network;private final ControlBootstrapJournal journal;private final ControlBootstrapStorage storage;
    public ControlBootstrapPlans(ControlServers servers,ControlServerStore store,ControlBootstrapAdmission admission,ControlBootstrapSources sources,
            ControlBootstrapPreflights preflights,ControlBootstrapArtifacts artifacts,ControlBootstrapTargetNetwork network,ControlBootstrapJournal journal){
        this.servers=servers;this.store=store;this.admission=admission;this.sources=sources;this.preflights=preflights;this.artifacts=artifacts;this.network=network;this.journal=journal;storage=new ControlBootstrapStorage(store);
    }
    public record Request(UUID id,UUID sourceServerId,long sourceRevision,UUID targetServerId,long targetRevision,String releaseId,int allocationSlot,
                          String sourceAddress,String targetAddress,String targetEndpoint,int wireguardPort,int httpPort,String appBaseUrl,
                          ControlBootstrapStorage.Selection storage){
        @Override public String toString(){return "BootstrapPlanRequest[redacted]";}
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    public Map<String,Object> capabilities(){String reason=admission.planningReason();return Map.of("supported",true,"canPlan",reason.isEmpty(),"reason",reason,
            "permissionsRequired",ControlBootstrapJournal.PERMISSIONS.stream().sorted().toList(),"storageProviders",List.of("GENERIC","RUSTFS","R2"),
            "storageModes",List.of("SOURCE","CUSTOM"),"planTtlSeconds",900,"backgroundExecution",true);}
    private static Map<String,Object> review(Request r){return Map.of("sourceAddress",r.sourceAddress(),"targetAddress",r.targetAddress(),"targetEndpoint",r.targetEndpoint(),"wireguardPort",r.wireguardPort(),"httpPort",r.httpPort(),"appBaseUrl",r.appBaseUrl());}
    private String creationBinding(Request r,Map<String,Object> review){
        try{return store.fingerprint(JSON.writeValueAsString(List.of(r.id(),r.sourceServerId(),r.sourceRevision(),r.targetServerId(),r.targetRevision(),r.releaseId(),r.allocationSlot(),new TreeMap<>(review),storage.requestBinding(r.storage()))));}
        catch(ControlFailure failure){throw failure;}catch(Exception invalid){throw new ControlFailure(400,"BOOTSTRAP_INVALID_INPUT");}
    }
    private static void targetCapability(ControlBootstrapArtifacts.Frozen artifact){
        try(var jar=new ZipFile(Path.of(artifact.controller().path()).toFile())){
            String marker="META-INF/eatwhat-controller-bootstrap-target-storage-v1";
            require(jar.getEntry(marker)!=null,"BOOTSTRAP_TARGET_STORAGE_CAPABILITY_REQUIRED");
            try(var in=jar.getInputStream(jar.getEntry(marker))){require(Arrays.equals(in.readAllBytes(),"eatwhat-controller-bootstrap-target-storage-v1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),"BOOTSTRAP_TARGET_STORAGE_CAPABILITY_REQUIRED");}
            require(jar.getEntry("META-INF/eatwhat-controller-bootstrap-managed-workflow-v1")!=null,"BOOTSTRAP_WORKFLOW_CAPABILITY_REQUIRED");
            for(String name:List.of("ControlBootstrapStorage","ControlBootstrapPlans","ControlBootstrapWorkflow","ControlBootstrapWorker","ControlBootstrapPlanController"))require(jar.getEntry("BOOT-INF/classes/you/v50to/eatwhat/control/"+name+".class")!=null,"BOOTSTRAP_WORKFLOW_CAPABILITY_REQUIRED");
        }catch(ControlFailure failure){throw failure;}catch(Exception invalid){throw new ControlFailure(409,"BOOTSTRAP_TARGET_STORAGE_CAPABILITY_REQUIRED");}
    }
    public Map<String,Object> preview(String actor,Request r){
        servers.requireWrite();require(r!=null&&actor!=null&&!actor.isBlank(),"BOOTSTRAP_INVALID_INPUT");
        Map<String,Object> review;
        try{review=review(r);}catch(Exception invalid){throw new ControlFailure(400,"BOOTSTRAP_INVALID_INPUT");}
        // Validate before SSH and before looking up an idempotency record.
        ControlBootstrapJournal.validateRequest(r.id(),r.sourceServerId(),r.sourceRevision(),r.targetServerId(),r.targetRevision(),r.releaseId(),r.allocationSlot(),review);
        String binding=creationBinding(r,review);var existing=journal.existingPreview(r.id(),actor,binding);if(existing!=null)return existing;
        var anchor=admission.sourceAuthority(actor);var artifact=artifacts.freeze(r.releaseId());targetCapability(artifact);
        var source=sources.discover(r.sourceServerId(),r.sourceRevision(),anchor);
        require(source.request().probeArtifactSha256().equals(artifact.controller().sha256()),"BOOTSTRAP_SOURCE_CONTROLLER_UPGRADE_REQUIRED");
        var spec=new ControlBootstrapJournal.Spec(r.id(),r.sourceServerId(),r.sourceRevision(),r.targetServerId(),r.targetRevision(),anchor.clusterId(),anchor.epoch(),artifact.controller().sha256(),r.releaseId(),artifact.business().sha256(),r.allocationSlot());
        var initialSource=preflights.capture(r.sourceServerId(),r.sourceRevision(),preflight(spec,review,anchor.stateId(),true));
        var initialTarget=preflights.capture(r.targetServerId(),r.targetRevision(),preflight(spec,review,anchor.stateId(),false));
        var frozen=admission.freeze(actor,spec,review,initialSource,initialTarget,source.request(),source.captured(),artifact);
        network.plan(frozen); // All managed endpoint/credential routes must be derivable before approval.
        @SuppressWarnings("unchecked") var sourceProperties=(Map<String,String>)source.captured().privatePayload().get("properties");
        var targetStorage=storage.freeze(spec.id(),spec.targetServerId(),spec.targetRevision(),r.storage(),sourceProperties);
        var inputs=Map.<String,Object>of("admission",frozen,"targetStorage",targetStorage,"creationBinding",binding,"executionProtocol","eatwhat-controller-bootstrap-managed-v1");
        return admission.beforeEffects(frozen,()->journal.preview(actor,spec,review,inputs,()->{}));
    }
    private static Map<String,Object> preflight(ControlBootstrapJournal.Spec spec,Map<String,Object> review,UUID state,boolean source){
        var input=new LinkedHashMap<String,Object>();input.put("id",ControlBootstrapJournal.phaseId(spec.id(),source?ControlBootstrapJournal.Phase.SOURCE_RUNTIME:ControlBootstrapJournal.Phase.TARGET_RUNTIME).toString());
        input.put("role",source?"SOURCE":"TARGET");input.put("sourceServerId",spec.sourceServerId().toString());input.put("targetServerId",spec.targetServerId().toString());
        for(String key:List.of("sourceAddress","targetAddress","targetEndpoint","wireguardPort"))input.put(key,review.get(key));
        input.put("sharedStateId",state.toString());input.put("controllerArtifactSha256",spec.controllerSha256());input.put("businessAllocationSlot",spec.allocationSlot());return Map.copyOf(input);
    }
    public Map<String,Object> accept(String actor,UUID id,Set<String> permissions){
        var frozen=journal.originalAdmission(id,!journal.accepted(id));require(frozen.actor().equals(actor),"BOOTSTRAP_ORIGINAL_ADMIN_REQUIRED");admission.requireBound(frozen);
        if(journal.accepted(id))return journal.job(id);
        require(ControlBootstrapJournal.PERMISSIONS.equals(permissions),"BOOTSTRAP_PERMISSION_APPROVAL_REQUIRED");
        return admission.beforeEffects(frozen,()->journal.accept(actor,id,permissions,()->{}));
    }
    public Map<String,Object> cancelUnstarted(String actor,UUID id,String requestHash){
        require(actor!=null&&!actor.isBlank(),"ADMIN_REQUIRED");var frozen=journal.originalAdmission(id,false);admission.requireBound(frozen);
        return journal.cancelUnstarted(id,actor,requestHash,intent->admission.beforeUnstartedCancellation(frozen,actor,()->{intent.run();return null;}));
    }
}
