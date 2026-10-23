package you.v50to.eatwhat.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.control.ControlNode;
import java.util.*;

@RestController
@RequestMapping("/internal/cluster/v1")
@ConditionalOnProperty(name={"app.cluster.enabled","app.control.node-enabled"},havingValue="true")
public class ClusterNodeController {
    private final ControlNode authentication;private final ClusterNodeAgent agent;private final ClusterGcParticipant gc;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.service.storage.replication.FileGcService> physicalGc;
    @org.springframework.beans.factory.annotation.Autowired private ClusterSessions sessions;
    @org.springframework.beans.factory.annotation.Autowired private ClusterMember local;
    @org.springframework.beans.factory.annotation.Autowired private you.v50to.eatwhat.config.SiteProperties site;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<ClusterAuthStore> authStore;
    public ClusterNodeController(ControlNode authentication,ClusterNodeAgent agent,ClusterGcParticipant gc){this.authentication=authentication;this.agent=agent;this.gc=gc;}
    @GetMapping("/probe") public Object probe(@RequestHeader(value="X-Control-Node-Token",required=false) String token){authentication.authenticate(token);var result=new LinkedHashMap<>(agent.probe());result.put("control",authentication.snapshot());return result;}
    @GetMapping("/enrollment-proof")
    public Object enrollmentProof(@RequestHeader(value="X-Control-Node-Token",required=false) String token){
        authentication.authenticate(token);
        if(sessions.independentAuthentication()){
            if(!sessions.localAuthenticationHealthy())throw new ClusterFailure(503,"LOCAL_AUTH_SCHEMA_UNAVAILABLE");
            // probe validates the persisted cluster/site/installation/slot binding and returns its DB fingerprint.
            // An UNJOINED node is only an enrollment candidate; normal stage/fence/apply proof is still required.
            var result=new LinkedHashMap<>(agent.probe());
            result.put("authenticationMode","LOCAL_MULTIWRITER");result.put("authStoreBinding","NOT_REQUIRED");return result;
        }
        var store=authStore.getIfAvailable();if(store==null)throw new ClusterFailure(503,"AUTH_STORE_BINDING_REQUIRED");
        store.verify();var result=new LinkedHashMap<>(agent.probe());result.put("authStoreBinding","VERIFIED");return result;
    }
    @GetMapping("/health") public Object health(@RequestHeader(value="X-Control-Node-Token",required=false) String token){authentication.authenticate(token);return Map.of("siteId",local.siteId(),"instanceId",local.instanceId(),"clusterId",site.getClusterId(),"identityProof","CONFIGURATION_ONLY","control",authentication.healthSnapshot());}
    public record HealthCheck(UUID requestId){}
    @PostMapping("/health-check") public Object health(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody HealthCheck input){authentication.authenticate(token);if(input.requestId()==null)throw new ClusterFailure(400,"REQUEST_ID_REQUIRED");authentication.refreshHealth();return health(token);}
    @PostMapping("/storage-check") public Object storage(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody HealthCheck input){authentication.authenticate(token);if(input.requestId()==null)throw new ClusterFailure(400,"REQUEST_ID_REQUIRED");var result=new LinkedHashMap<>(agent.probe());authentication.storageCheck("authenticated-control",input.requestId());result.put("control",authentication.healthSnapshot());return result;}
    public record Fault(UUID requestId,String channel,String action,int seconds){}
    @PostMapping("/control-fault") public Object fault(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody Fault input){authentication.authenticate(token);if(input.requestId()==null||input.seconds()<1||input.seconds()>300)throw new ClusterFailure(400,"INVALID_FAULT_REQUEST");return Map.of("siteId",local.siteId(),"instanceId",local.instanceId(),"clusterId",site.getClusterId(),"fault",authentication.fault(input.channel(),input.action(),input.seconds(),input.requestId()));}
    @PostMapping("/gc-run") public Object runGc(@RequestHeader(value="X-Control-Node-Token",required=false) String token){authentication.authenticate(token);var service=physicalGc.getIfAvailable();if(service==null)throw new ClusterFailure(409,"GC_EXECUTOR_NOT_READY");service.runCycle();return agent.probe();}
    @PostMapping("/session-introspect") public Object introspect(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody ClusterSessions.Token input){authentication.authenticate(token);var result=new LinkedHashMap<>(agent.probe());result.put("session",sessions.localProof(input));return result;}
    @PostMapping("/{phase:stage|fence|prepared|apply|release}")
    public Object membership(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@PathVariable String phase,@RequestBody ClusterMembershipCoordinator.Plan plan){
        authentication.authenticate(token);String proof=switch(phase){case "stage"->agent.stage(plan);case "fence"->agent.fence(plan);case "prepared"->agent.prepared(plan);case "apply"->agent.apply(plan);case "release"->agent.release(plan);default->throw new ClusterFailure(400,"INVALID_PHASE");};
        var response=new LinkedHashMap<>(agent.probe());response.put("changeId",plan.changeId());response.put("planHash",ClusterStore.hash(plan));response.put("proofHash",proof);return response;
    }
    @PostMapping("/{phase:gc-prepare|gc-commit|gc-state}")
    public Object gc(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@PathVariable String phase,@RequestBody ClusterGcStore.Decision decision){
        authentication.authenticate(token);String state=switch(phase){case "gc-prepare"->gc.prepare(decision);case "gc-commit"->gc.commit(decision);case "gc-state"->gc.deleted(decision);default->throw new ClusterFailure(400,"INVALID_PHASE");};
        var response=new LinkedHashMap<>(agent.probe());response.put("operationId",decision.operationId());response.put("decisionHash",ClusterStore.hash(decision));response.put("state",state);return response;
    }
}
