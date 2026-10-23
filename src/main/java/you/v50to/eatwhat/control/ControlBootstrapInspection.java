package you.v50to.eatwhat.control;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate observation of an original retained task. This cannot clear an
 * invalid approval, grant a new claim, save a remote receipt or resume work. */
public final class ControlBootstrapInspection {
    private final ControlBootstrapJournal journal;
    private final ControlBootstrapNativePhases nativePhases;
    private final ControlBootstrapMembership membership;
    private final ControlBootstrapMemberRuntime runtime;
    private final ControlBootstrapSourceInspection source;
    private final AtomicBoolean inspecting=new AtomicBoolean();
    public ControlBootstrapInspection(ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,ControlBootstrapMembership membership,ControlBootstrapMemberRuntime runtime,ControlBootstrapSourceInspection source){
        this.journal=journal;this.nativePhases=nativePhases;this.membership=membership;this.runtime=runtime;this.source=source;
    }
    private static final Set<String> STATES=Set.of("NOT_STARTED","ACCEPTED","RUNNING","OUTCOME_UNKNOWN","REJECTED","FAILED","RUNTIME_READY","CONFIGURED","EXPORTED","MOUNTED",
            "KEY_READY","AUTHORIZED","VERIFIED","STAGED","SUCCEEDED","KEYS_READY","RELAY_READY","ROLE_READY","HTTPS_READY","PEER_VERIFIED",
            "PREPARING","COMMITTED","INSTALLED","APPLIED","REGISTERED","JOINED_VERIFIED");
    private static final Set<String> SAFE_FAILURES=Set.of("BOOTSTRAP_NATIVE_INPUT_MISSING","BOOTSTRAP_NATIVE_INPUT_CHANGED","BOOTSTRAP_NATIVE_RECEIPT_MISMATCH",
            "BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE","BOOTSTRAP_INSPECTION_PHASE_NOT_SUPPORTED","SERVER_REVISION_CHANGED","SSH_ROUTE_BINDING_CHANGED","BOOTSTRAP_LINUX_ROOT_REQUIRED",
            "BOOTSTRAP_MEMBERSHIP_RECORD_MISSING","BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED","BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED","BOOTSTRAP_MEMBERSHIP_OBSERVATION_UNAVAILABLE",
            "BOOTSTRAP_JOIN_CONFIRMATIONS_REQUIRED","BOOTSTRAP_MEMBER_RUNTIME_NOT_READY","BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED","BOOTSTRAP_MEMBER_RUNTIME_RECORD_CHANGED",
            "BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED","BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE","BOOTSTRAP_PROFILES_NOT_READY","BOOTSTRAP_PROFILE_RECORD_CHANGED");
    public Map<String,Object> inspect(UUID id){return inspect(id,false);}
    @SuppressWarnings("unchecked") public Map<String,Object> inspect(UUID id,boolean current){
        if(!inspecting.compareAndSet(false,true))throw new ControlFailure(409,"BOOTSTRAP_INSPECTION_BUSY");
        try{
            var before=journal.job(id);var f=journal.originalAdmission(id,false);
            if(current&&!"SUCCEEDED".equals(before.get("state")))throw new ControlFailure(409,"BOOTSTRAP_CURRENT_INSPECTION_REQUIRES_SUCCESS");
            nativePhases.bound(f);
            var report=new LinkedHashMap<String,Object>();report.put("protocol","eatwhat-controller-bootstrap-inspection-v1");report.put("jobId",id.toString());
            report.put("requestHash",before.get("requestHash"));report.put("jobSnapshot",before);report.put("startedAt",Instant.now().toString());
            report.put("inspectionMode",current?"CURRENT_STATE":"RETAINED_TASK");
            report.put("readOnly",true);report.put("canResume",false);report.put("approvalGranted",false);report.put("historicalSuccessIsCurrentHealth",false);
            report.put("parentPhaseCompletionNotInferred",true);
            var steps=(List<Map<String,Object>>)before.get("steps");
            var pending=steps.stream().filter(v->current?"VERIFY".equals(v.get("phase")):!"SUCCEEDED".equals(v.get("state"))).findFirst();
            if((!current&&"SUCCEEDED".equals(before.get("state")))||Boolean.TRUE.equals(before.get("cancelledBeforeDispatch"))){report.put("inspectionState","TERMINAL_HISTORY_ONLY");}
            else if(pending.isEmpty()){report.put("inspectionState","NO_UNFINISHED_PHASE");}
            else {
                var step=pending.get();report.put("phase",step.get("phase"));report.put("phaseId",step.get("id"));
                if("PENDING".equals(step.get("state")))report.put("inspectionState","NOT_DISPATCHED");
                else try{
                    var phase=ControlBootstrapJournal.Phase.valueOf(step.get("phase").toString());
                    var joined=phase==ControlBootstrapJournal.Phase.MEMBER_JOIN||phase==ControlBootstrapJournal.Phase.VERIFY;
                    var seen=joined?inspectMembership(f,phase):nativePhases.inspectSaved(f,phase);
                    if(!(seen.get("state") instanceof String state)||!STATES.contains(state))throw new ControlFailure(409,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
                    var safe=new LinkedHashMap<String,Object>();safe.put("state",state);
                    for(String name:List.of("effectsStarted","derivedFromConfiguration","registrationCommandObserved","joinCommandObserved","allJoinConfirmationsObserved","currentMembershipMatchesOriginalJoin","targetRuntimeVerified","sourceConfigurationRevalidated","sourceProcessRevalidated"))if(seen.get(name) instanceof Boolean) safe.put(name,seen.get(name));
                    if(joined){for(String name:List.of("clusterEpoch","expectedJoinedEpoch"))if(seen.get(name) instanceof Number)safe.put(name,seen.get(name));for(String name:List.of("healthCheckedAt","sourceCheckedAt"))if(seen.get(name) instanceof String stamp)safe.put(name,stamp);}
                    if(seen.get("inspectionComponent") instanceof String name&&Set.of("KEY","SOURCE","CONNECT","STAGE","VERIFY","INSTALL","MEMBER_RUNTIME","MEMBER_HTTPS_INGRESS","MEMBER_AUTH_ROLE","MEMBER_TLS_RELAY","MEMBER_JOIN","JOINED_RUNTIME").contains(name))safe.put("component",name);
                    report.put("observation",safe);report.put("inspectionState",joined?"ORIGINAL_MEMBERSHIP_STATUS_OBSERVED":"ORIGINAL_NATIVE_STATUS_OBSERVED");
                }catch(ControlFailure failure){report.put("inspectionState","OBSERVATION_UNAVAILABLE");report.put("reason",SAFE_FAILURES.contains(failure.code)?failure.code:"BOOTSTRAP_INSPECTION_UNCONFIRMED");}
                catch(RuntimeException failure){report.put("inspectionState","OBSERVATION_UNAVAILABLE");report.put("reason","BOOTSTRAP_INSPECTION_UNCONFIRMED");}
            }
            var after=journal.job(id);report.put("jobChangedDuringInspection",!ControlBootstrapNativeJson.encode(before).equals(ControlBootstrapNativeJson.encode(after)));
            report.put("finishedAt",Instant.now().toString());return report;
        }finally{inspecting.set(false);}
    }
    private Map<String,Object> inspectMembership(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.Phase phase){
        var seen=membership.inspectOriginal(f);var result=new LinkedHashMap<>(seen.observation());
        result.put("sourceConfigurationRevalidated",false);result.put("targetRuntimeVerified",false);
        if(phase==ControlBootstrapJournal.Phase.VERIFY){
            var proof=seen.joinedProof().orElseThrow(()->new ControlFailure(409,"BOOTSTRAP_JOIN_CONFIRMATIONS_REQUIRED"));
            var target=runtime.inspectJoined(f,proof);
            // Do not merge arbitrary native payloads into the public report.
            if(!"JOINED_VERIFIED".equals(target.get("state")))throw new ControlFailure(409,"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");
            result.put("state","JOINED_VERIFIED");result.put("inspectionComponent","JOINED_RUNTIME");result.put("targetRuntimeVerified",true);result.put("healthCheckedAt",target.get("healthCheckedAt"));
            var origin=source.inspectJoined(f);
            if(!Boolean.TRUE.equals(origin.get("sourceConfigurationRevalidated"))||!Boolean.TRUE.equals(origin.get("sourceProcessRevalidated")))throw new ControlFailure(409,"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED");
            result.put("sourceConfigurationRevalidated",true);result.put("sourceProcessRevalidated",true);result.put("sourceCheckedAt",origin.get("sourceCheckedAt"));
            // Slow remote diagnostics must not mask a concurrent SQL successor.
            var after=membership.inspectOriginal(f);
            if(after.joinedProof().isEmpty()||!ControlBootstrapNativeJson.encode(proof).equals(ControlBootstrapNativeJson.encode(after.joinedProof().get())))throw new ControlFailure(409,"BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED");
            fresh(result.get("healthCheckedAt"),"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");fresh(result.get("sourceCheckedAt"),"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE");
        }return result;
    }
    private static void fresh(Object value,String code){try{var stamp=Instant.parse(Objects.toString(value));var now=Instant.now();
        if(stamp.isBefore(now.minusSeconds(60))||stamp.isAfter(now.plusSeconds(2)))throw new IllegalArgumentException();
    }catch(Exception invalid){throw new ControlFailure(409,code);}}
}
