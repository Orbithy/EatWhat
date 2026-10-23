package you.v50to.eatwhat.cluster;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.control.ControlController;
import java.util.*;

@RestController
@RequestMapping("/admin/control/v1/cluster")
@ConditionalOnProperty(name={"app.cluster.enabled","app.cluster.coordinator"},havingValue="true")
public class ClusterAdminController {
    @org.springframework.beans.factory.annotation.Autowired private you.v50to.eatwhat.control.ControlAudit healthAudit;
    private final ControlController authorization;private final ClusterStore store;private final ClusterProfiles profiles;private final ClusterMembershipCoordinator coordinator;
    @org.springframework.beans.factory.annotation.Autowired private ClusterGcStore gc;
    @org.springframework.beans.factory.annotation.Autowired private ClusterGcCoordinator gcCoordinator;
    @org.springframework.beans.factory.annotation.Autowired private ClusterHttpNodes nodes;
    @org.springframework.beans.factory.annotation.Autowired private ClusterRuntime runtime;
    @org.springframework.beans.factory.annotation.Autowired private ClusterControl control;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<ClusterControlHandoff> handoff;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<ClusterDeploymentFence> deploymentFence;
    public ClusterAdminController(ControlController authorization,ClusterStore store,ClusterProfiles profiles,ClusterMembershipCoordinator coordinator){this.authorization=authorization;this.store=store;this.profiles=profiles;this.coordinator=coordinator;}
    @GetMapping public Object status(){authorization.admin();var result=new LinkedHashMap<>(store.status());result.put("worker",Map.of("running",runtime.isRunning(),"lastCode",runtime.lastCode()));result.put("leadership",runtime.leadership());return result;}
    @GetMapping("/leadership") public Object leadership(){authorization.admin();return runtime.leadership();}
    @GetMapping("/deployment-fences") public Object deploymentFences(){authorization.admin();var fence=deploymentFence.getIfAvailable();return fence==null?Map.of("available",false,"reason","DEPLOYMENT_FENCE_UNAVAILABLE"):Map.of("available",true,"active",fence.active());}
    @GetMapping("/profiles") public Object profiles(){authorization.admin();return profiles.list();}
    @GetMapping("/channels") public Object channels(){authorization.admin();return control.channels();}
    @GetMapping("/capabilities") public Object capabilities(){authorization.admin();var result=new LinkedHashMap<String,Object>(Map.of("topology","SINGLE_CLUSTER","maximumActiveSites",store.singleSiteOnly()?1:16,"allocationSlots",1024,"coordinatorTransferEnabled",false,"leadershipHandoffEnabled",handoff.getIfAvailable()!=null,"businessSessionShared",true,"deploymentExecutionEnabled",control.deploymentEnabled(),"deploymentRecoveryEnabled",control.deploymentRecoveryEnabled(),"remoteFailureMatrixAccepted",false,"singleSiteOnly",store.singleSiteOnly()));result.put("controllerRuntimeArtifact",control.controllerRuntimeArtifact());return result;}
    private <T> T write(java.util.function.Function<String,T> action){String actor=authorization.admin();var service=handoff.getIfAvailable();if(service!=null)return service.operation(()->action.apply(actor));store.requireLeadership();return action.apply(actor);}
    private ClusterControlHandoff handoff(){var service=handoff.getIfAvailable();if(service==null)throw new ClusterFailure(409,"CONTROL_HANDOFF_DISABLED");return service;}
    public record Handoff(@NotNull UUID requestId,@Min(1) long expectedGeneration,@NotNull UUID targetControllerId){}
    @PostMapping("/leadership/handoffs") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Object requestHandoff(@Valid @RequestBody Handoff request){String actor=authorization.admin();return handoff().request(actor,request.requestId(),request.expectedGeneration(),request.targetControllerId());}
    @GetMapping("/leadership/handoffs/{id}") public Object handoff(@PathVariable UUID id){authorization.admin();return handoff().get(id);}
    @PostMapping("/leadership/handoffs/{id}/cancel") public Object cancelHandoff(@PathVariable UUID id){String actor=authorization.admin();return handoff().cancel(actor,id);}
    @PostMapping("/profiles/reload") public Object reload(){return write(actor->{var retained=((List<Map<String,Object>>)store.status().get("members")).stream().filter(row->!row.get("state").equals("RETIRED")).map(row->new ClusterMember((String)row.get("site_id"),(UUID)row.get("instance_id"),(String)row.get("mode"),((Number)row.get("allocation_slot")).intValue())).toList();return profiles.reload(retained);});}
    public record Register(@NotNull UUID requestId,@Min(0) long expectedEpoch,@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,64}") String profileId){}
    @PostMapping("/sites") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object register(@Valid @RequestBody Register input){return write(actor->coordinator.register(actor,input.requestId(),input.expectedEpoch(),input.profileId()));}
    public record Change(@NotNull UUID requestId,@Min(0) long expectedEpoch){}
    @PostMapping("/sites/{site}/cancel") public Object cancel(@PathVariable String site,@Valid @RequestBody Change input){return write(actor->store.cancelRegistration(actor,input.requestId(),input.expectedEpoch(),site));}
    @PostMapping("/sites/{site}/join") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Object join(@PathVariable String site,@Valid @RequestBody Change input){return write(actor->coordinator.begin(actor,input.requestId(),input.expectedEpoch(),"JOIN",site));}
    @PostMapping("/sites/{site}/retire") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Object retire(@PathVariable String site,@Valid @RequestBody Change input){return write(actor->coordinator.begin(actor,input.requestId(),input.expectedEpoch(),"RETIRE",site));}
    @GetMapping("/changes/{id}") public Object change(@PathVariable UUID id){authorization.admin();return store.change(id);}
    @GetMapping("/changes") public Object changes(@RequestParam(defaultValue="20") int limit){authorization.admin();return store.changes(limit);}
    @GetMapping("/audit") public Object audit(@RequestParam(defaultValue="50") int limit){authorization.admin();return store.audit(limit);}
    @GetMapping("/gc") public Object gcList(@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="true") boolean pendingOnly){authorization.admin();return gc.list(limit,pendingOnly);}
    @GetMapping("/gc/candidates") public Object candidates(@RequestParam(defaultValue="20") int limit){authorization.admin();return gc.candidates(limit);}
    @PostMapping("/changes/{id}/resume") public Object resume(@PathVariable UUID id){return write(actor->coordinator.reconcile(actor,id));}
    @GetMapping("/observations") public Object observations(){authorization.admin();var result=new ArrayList<Map<String,Object>>();for(var member:store.active()){var row=new LinkedHashMap<String,Object>();row.put("member",member);try{var profile=profiles.get(store.profile(member.siteId()));row.put("node",nodes.call(profile,"probe",null));row.put("sync",nodes.call(profile,"replay-status",null).get("progress"));row.put("health","REACHABLE");}catch(ClusterFailure failure){row.put("health","UNKNOWN");row.put("code",failure.getMessage());try{var diagnostics=nodes.call(profiles.get(store.profile(member.siteId())),"health",null);row.put("node",diagnostics);row.put("health","REACHABLE");row.put("identityProof","CONFIGURATION_ONLY");}catch(ClusterFailure unavailable){row.put("diagnosticsCode",unavailable.getMessage());}}result.add(row);}return result;}
    public record HealthCheck(@NotNull UUID requestId){}
    private ClusterProfiles.Entry healthProfile(String site){if(!store.active().stream().anyMatch(m->m.siteId().equals(site)))throw new ClusterFailure(409,"ACTIVE_SITE_REQUIRED");return profiles.get(store.profile(site));}
    @PostMapping("/sites/{site}/health-checks") public Object health(@PathVariable String site,@Valid @RequestBody HealthCheck request){authorization.admin();return nodes.call(healthProfile(site),"health-check",request);}
    @PostMapping("/sites/{site}/storage-checks") public Object storage(@PathVariable String site,@Valid @RequestBody HealthCheck request){return write(actor->{var profile=healthProfile(site);healthAudit.append(request.requestId(),actor,"STORAGE_WRITE_CHECK",site,"REQUESTED");return nodes.call(profile,"storage-check",request);});}
    public record Delete(@NotNull UUID operationId,@NotNull UUID fileId,@Min(1) int revision,@Min(1) long expectedEpoch){}
    @PostMapping("/gc") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) public Object beginGc(@Valid @RequestBody Delete request){return write(actor->gc.begin(actor,request.operationId(),request.fileId(),request.revision(),request.expectedEpoch()));}
    @GetMapping("/gc/{id}") public Object gc(@PathVariable UUID id){authorization.admin();return gc.snapshot(id);}
    @PostMapping("/gc/{id}/resume") public Object resumeGc(@PathVariable UUID id){return write(actor->gcCoordinator.resume(id));}
    @PostMapping("/sites/{site}/faults") public Object fault(@PathVariable String site,@Valid @RequestBody ControlController.Fault request){return write(actor->control.fault(actor,site,request.channel(),request.action(),request.seconds()));}
    public record Preview(@NotNull UUID requestId,@Min(1) long expectedEpoch,@NotBlank String releaseId,@NotNull List<String> sites){}
    @PostMapping("/deployment-plans") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public Object preview(@Valid @RequestBody Preview request){return write(actor->control.preview(actor,request.requestId(),request.expectedEpoch(),request.releaseId(),request.sites()));}
    @PostMapping("/deployment-plans/{id}/execute") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) public Object execute(@PathVariable UUID id){return control.execute(authorization.admin(),id);}
    @GetMapping("/deployment-plans/{id}") public Object preview(@PathVariable UUID id){authorization.admin();return control.preview(id);}
}
