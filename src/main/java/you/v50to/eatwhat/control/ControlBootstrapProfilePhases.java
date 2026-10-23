package you.v50to.eatwhat.control;

import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** One pinned SSH operation per advance. A forced claim is never dispatched
 * again after a timeout, manager change, unknown result or missing remote effect.
 * The receiver uses its own service UID and actual committed authority fence.
 * This child does not complete MEMBER_CONFIGURE or authorize JOIN. */
public final class ControlBootstrapProfilePhases {
    private final ControlBootstrapProfileState state;private final ControlBootstrapNativePhases nativePhases;
    public ControlBootstrapProfilePhases(ControlBootstrapProfileState state,ControlBootstrapNativePhases nativePhases){this.state=state;this.nativePhases=nativePhases;}
    private static ControlBootstrapJournal.Phase phase(ControlBootstrapProfileState.Role role){return role==ControlBootstrapProfileState.Role.SOURCE?SOURCE_RUNTIME:MEMBER_CONFIGURE;}
    public Map<String,Object> advance(ControlBootstrapAdmission.Frozen frozen,ControlBootstrapProfileState.Role role){
        // Check the original inventory revision and this manager's physical
        // route before burning dispatch permission. Recheck before SSH as well.
        nativePhases.bound(frozen);nativePhases.server(frozen,phase(role));
        var permit=state.claim(frozen,role);var accepted=permit.accepted();
        var payload=new LinkedHashMap<String,Object>();payload.put("id",frozen.spec().id().toString());payload.put("role",role.name());payload.put("binding",accepted.binding());
        payload.put("applicationSha256",ControlBootstrapProfileState.applicationHash(accepted,role));payload.put("tokenFile",ControlBootstrapProfileState.tokenFile(accepted,role));
        payload.put("artifactSha256",role==ControlBootstrapProfileState.Role.SOURCE?frozen.sourceRequest().sourceArtifactSha256():frozen.spec().controllerSha256());
        payload.put("sourceProcess",role==ControlBootstrapProfileState.Role.SOURCE?accepted.frozen().sourceEvidence().get("sourceProcess"):null);
        var receipt=nativePhases.call(nativePhases.server(frozen,phase(role)),accepted.bridge().agent(),List.of(permit.dispatchAllowed()?"apply":"status"),payload);
        state.observe(accepted,role,receipt);
        return Map.of("id",frozen.spec().id().toString(),"profileOperations",state.progress(accepted),"memberConfigurationPending",true,"joinAuthorized",false);
    }
}
