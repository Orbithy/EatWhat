package you.v50to.eatwhat.control;

import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Routes only the original accepted task. Child drivers retain all authority,
 * once-only dispatch and native reconciliation checks. No in-memory cursor or
 * HTTP request is needed to reconstruct the next phase after interruption. */
public final class ControlBootstrapWorkflow {
    private final ControlBootstrapJournal journal;
    private final ControlBootstrapAdmission admission;
    private final ControlBootstrapNativePhases nativePhases;
    private final ControlBootstrapDependencyPhases dependencies;
    private final ControlBootstrapControllerPhases controllers;
    private final ControlBootstrapBusinessPhases business;
    private final ControlBootstrapMemberRelay relay;
    private final ControlBootstrapMemberAuth auth;
    private final ControlBootstrapMemberIngress ingress;
    private final ControlBootstrapMemberRuntime runtime;
    private final ControlBootstrapProfilePhases profiles;
    private final ControlBootstrapMembership membership;
    private final ControlBootstrapVerify verification;

    public ControlBootstrapWorkflow(ControlBootstrapJournal journal,ControlBootstrapAdmission admission,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapDependencyPhases dependencies,ControlBootstrapControllerPhases controllers,ControlBootstrapBusinessPhases business,
            ControlBootstrapMemberRelay relay,ControlBootstrapMemberAuth auth,ControlBootstrapMemberIngress ingress,ControlBootstrapMemberRuntime runtime,
            ControlBootstrapProfilePhases profiles,ControlBootstrapMembership membership,ControlBootstrapVerify verification){
        this.journal=journal;this.admission=admission;this.nativePhases=nativePhases;this.dependencies=dependencies;this.controllers=controllers;this.business=business;
        this.relay=relay;this.auth=auth;this.ingress=ingress;this.runtime=runtime;this.profiles=profiles;this.membership=membership;this.verification=verification;
    }
    public Map<String,Object> advance(UUID id){
        var job=journal.job(id);if(Set.of("SUCCEEDED","FAILED").contains(job.get("state")))return job;
        var f=journal.originalAdmission(id,false);admission.requireBound(f);
        if(Boolean.TRUE.equals(job.get("requiresReapproval")))throw new ControlFailure(409,"BOOTSTRAP_REAPPROVAL_REQUIRED");
        var steps=(List<?>)job.get("steps");
        for(var phase:ControlBootstrapJournal.Phase.values()){
            var step=(Map<?,?>)steps.get(phase.ordinal());if("SUCCEEDED".equals(step.get("state")))continue;
            return switch(phase){
                case SOURCE_RUNTIME,TARGET_RUNTIME,SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER,SOURCE_SHARED_STATE,TARGET_SHARED_STATE -> nativePhases.advance(f,phase);
                case DEPENDENCY_LINK -> dependency(f,step);
                case CONTROLLER_CONFIG -> controllers.configure(f);
                case CONTROLLER_VERIFY -> controllers.verify(f);
                case BUSINESS_INSTALL -> business.advance(f);
                case MEMBER_CONFIGURE -> configure(f);
                case MEMBER_JOIN -> membership.advance(f);
                case VERIFY -> verification.advance(f);
            };
        }
        throw new ControlFailure(409,"BOOTSTRAP_JOURNAL_STATE_CHANGED");
    }
    private Map<String,Object> dependency(ControlBootstrapAdmission.Frozen f,Map<?,?> step){
        var operations=(List<?>)step.get("operations");
        for(var op:ControlBootstrapJournal.DependencyOperation.values())
            if(!"SUCCEEDED".equals(((Map<?,?>)operations.get(op.ordinal())).get("state")))return dependencies.advance(f,op);
        throw new ControlFailure(409,"BOOTSTRAP_JOURNAL_STATE_CHANGED");
    }
    private static boolean complete(Map<String,Object> result,String key,Set<String> expected){
        if("FAILED".equals(result.get("state"))||"SUCCEEDED".equals(result.get("state")))return false;
        return result.get(key) instanceof Map<?,?> rows&&rows.keySet().equals(expected)&&rows.values().stream().allMatch("SUCCEEDED"::equals);
    }
    private Map<String,Object> configure(ControlBootstrapAdmission.Frozen f){
        var result=relay.advance(f);if(!complete(result,"memberRelayOperations",Set.of("PREPARE","START")))return result;
        result=auth.advance(f);if(!complete(result,"memberAuthOperations",Set.of("PREPARE","CREATE")))return result;
        result=ingress.advance(f);if(!complete(result,"memberIngressOperations",Set.of("PREPARE","START")))return result;
        result=runtime.advance(f);if(!"CONFIGURED".equals(result.get("memberRuntimeState")))return result;
        result=profiles.advance(f,ControlBootstrapProfileState.Role.SOURCE);
        if(!(result.get("profileOperations") instanceof Map<?,?> rows)||!"SUCCEEDED".equals(rows.get("SOURCE")))return result;
        result=profiles.advance(f,ControlBootstrapProfileState.Role.TARGET);
        if(!complete(result,"profileOperations",Set.of("SOURCE","TARGET")))return result;
        return membership.configure(f);
    }
}
