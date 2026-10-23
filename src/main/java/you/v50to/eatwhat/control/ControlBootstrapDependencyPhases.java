package you.v50to.eatwhat.control;

import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.DependencyOperation.*;

/** Three distinct once-only intents. Losing SOURCE's response cannot generate
 * another target key; CONNECT uses the exact original KEY header and agent. */
public final class ControlBootstrapDependencyPhases {
    private final ControlBootstrapNativePhases nativePhases;private final ControlBootstrapNativeInputs inputs;
    private final ControlBootstrapJournal journal;private final ControlBootstrapPhaseClaims claims;
    private final ControlBootstrapTargetNetwork network;
    public ControlBootstrapDependencyPhases(ControlBootstrapNativePhases nativePhases,ControlBootstrapNativeInputs inputs,
                                           ControlBootstrapJournal journal,ControlBootstrapPhaseClaims claims,ControlBootstrapTargetNetwork network){
        this.nativePhases=nativePhases;this.inputs=inputs;this.journal=journal;this.claims=claims;this.network=network;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private static ControlBootstrapJournal.Phase statePhase(boolean source){return source?SOURCE_SHARED_STATE:TARGET_SHARED_STATE;}
    private ControlBootstrapNativeInputs.Sealed saved(ControlBootstrapAdmission.Frozen f,boolean source,String approval){
        return inputs.load(f.spec().id(),DEPENDENCY_LINK,approval,source?f.spec().sourceServerId():f.spec().targetServerId(),
                source?f.spec().sourceRevision():f.spec().targetRevision(),source?f.sourceRoute():f.targetRoute());
    }
    @SuppressWarnings("unchecked") private static boolean pending(Map<String,Object> job,ControlBootstrapJournal.DependencyOperation op){
        var phase=((List<Map<String,Object>>)job.get("steps")).get(DEPENDENCY_LINK.ordinal());
        return "PENDING".equals(((List<Map<String,Object>>)phase.get("operations")).get(op.ordinal()).get("state"));
    }
    public Map<String,Object> advance(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.DependencyOperation op){
        nativePhases.bound(f);boolean source=op==SOURCE;var job=journal.job(f.spec().id());String approval=job.get("requestHash").toString();
        nativePhases.server(f,statePhase(source));
        var input=op==CONNECT?saved(f,false,approval):inputs.prepare(f.spec().id(),DEPENDENCY_LINK,approval,
                source?f.spec().sourceServerId():f.spec().targetServerId(),source?f.spec().sourceRevision():f.spec().targetRevision(),
                source?f.sourceRoute():f.targetRoute(),pending(job,op),()->prepare(f,op,approval));
        var dispatch=claims.dependency(f,op,input.binding(),input.header().get("agentSha256").toString());
        require(approval.equals(dispatch.approvalDigest()),"BOOTSTRAP_PLAN_CHANGED");
        var payload=new LinkedHashMap<>(input.header());if(dispatch.dispatchAllowed())payload.put("agent",input.agent());
        var receipt=nativePhases.call(nativePhases.server(f,statePhase(source)),input.agent(),List.of(dispatch.dispatchAllowed()?"dispatch":"status",op.name()),payload);
        validate(f,op,input,receipt);
        return journal.observeDependencyOperation(f.spec().id(),op,dispatch.claimToken(),receipt);
    }
    private ControlBootstrapNativeInputs.Prepared prepare(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.DependencyOperation op,String approval){
        boolean source=op==SOURCE;var prior=nativePhases.saved(f,statePhase(source),approval);nativePhases.receipt(f,statePhase(source),approval);
        String agent;String sourcePublic;Object targetPublic=null;
        if(source){
            var keyInput=saved(f,false,approval);var key=journal.successfulDependencyReceipt(f.spec().id(),KEY);validate(f,KEY,keyInput,key);
            targetPublic=publicKey(key.get("publicKey"));sourcePublic=publicKey(keyInput.header().get("sourceHostPublicKey"));agent=keyInput.agent();
            require(ControlBootstrapNativeJson.sha(prior.agent()).equals(keyInput.header().get("stateObserverSha256")),"BOOTSTRAP_NATIVE_INPUT_CHANGED");
        }else{
            var key=nativePhases.call(nativePhases.server(f,SOURCE_SHARED_STATE),ControlServers.resource("controller-bootstrap-source-host-key.py"),List.of(),
                    Map.of("id",ControlBootstrapJournal.phaseId(f.spec().id(),DEPENDENCY_LINK).toString()));
            require(DEPENDENCY_LINK.protocol.equals(key.get("protocol"))&&ControlBootstrapJournal.phaseId(f.spec().id(),DEPENDENCY_LINK).toString().equals(key.get("id"))
                    &&Boolean.FALSE.equals(key.get("effectsStarted")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");sourcePublic=publicKey(key.get("publicKey"));
            agent=ControlBootstrapNativePhases.expanded("controller-bootstrap-dependency.py","__EATWHAT_CONTROLLER_DEPENDENCY_POLICY_BASE64__",ControlServers.resource("controller-bootstrap-dependency-policy.py"));
            String marker="__EATWHAT_CONTROLLER_STATE_OBSERVER_BASE64__";
            require(agent.contains(marker)&&agent.indexOf(marker)==agent.lastIndexOf(marker),"BOOTSTRAP_NATIVE_AGENT_INVALID");
            agent=agent.replace(marker,Base64.getEncoder().encodeToString(prior.agent().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        var header=new LinkedHashMap<String,Object>();header.put("id",ControlBootstrapJournal.phaseId(f.spec().id(),DEPENDENCY_LINK).toString());
        header.put("role",source?"SOURCE":"TARGET");header.put("stateId",ControlBootstrapJournal.phaseId(f.spec().id(),statePhase(source)).toString());
        header.put("stateBinding",prior.binding());header.put("stateObserverSha256",ControlBootstrapNativeJson.sha(prior.agent()));header.put("approvalDigest",approval);
        header.put("sourceHostPublicKey",sourcePublic);header.put("dependencySshPort",2223);
        if(source)header.put("forwards",saved(f,false,approval).header().get("forwards"));
        else{
            var forwards=network.forwards(f);
            if(forwards.stream().anyMatch(row->"SOURCE_ADMIN_SSH".equals(row.get("purpose"))))
                require(journal.approvedPermissions(f.spec().id()).contains(ControlBootstrapTargetNetwork.ADMIN_PERMISSION),"BOOTSTRAP_REAPPROVAL_REQUIRED");
            if(forwards.stream().anyMatch(row->row.get("purpose").toString().startsWith("MEMBER_HTTP_")))
                require(journal.approvedPermissions(f.spec().id()).contains(ControlBootstrapTargetNetwork.MEMBER_PERMISSION),"BOOTSTRAP_REAPPROVAL_REQUIRED");
            header.put("forwards",forwards);
        }
        if(source)header.put("targetPublicKey",targetPublic);
        var inspected=nativePhases.call(nativePhases.server(f,statePhase(source)),agent,List.of("inspect",op.name()),header);
        require(DEPENDENCY_LINK.protocol.equals(inspected.get("protocol"))&&header.get("id").equals(inspected.get("id"))&&header.get("role").equals(inspected.get("role"))
                &&Boolean.FALSE.equals(inspected.get("effectsStarted"))&&inspected.get("proof") instanceof String proof&&proof.matches("[a-f0-9]{64}"),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        header.put("expectedProof",inspected.get("proof"));header.put("agentSha256",ControlBootstrapNativeJson.sha(agent));
        return new ControlBootstrapNativeInputs.Prepared(header,agent);
    }
    ControlBootstrapNativeInputs.Sealed completedTarget(ControlBootstrapAdmission.Frozen f,String approval){
        var target=saved(f,false,approval);var source=saved(f,true,approval);
        validate(f,KEY,target,journal.successfulDependencyReceipt(f.spec().id(),KEY));
        validate(f,SOURCE,source,journal.successfulDependencyReceipt(f.spec().id(),SOURCE));
        validate(f,CONNECT,target,journal.successfulDependencyReceipt(f.spec().id(),CONNECT));
        require(ControlBootstrapNativeJson.encode(target.header().get("forwards")).equals(ControlBootstrapNativeJson.encode(source.header().get("forwards"))),"BOOTSTRAP_NATIVE_INPUT_CHANGED");return target;
    }
    private static String publicKey(Object value){
        require(value instanceof String,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");var key=ControlSsh.key(value.toString());
        require("ssh-ed25519".equals(key.get("type")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        byte[] wire=Base64.getDecoder().decode(key.get("publicKey").split(" ")[1]);
        require(wire.length==51&&java.nio.ByteBuffer.wrap(wire,15,4).getInt()==32,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        return key.get("publicKey");
    }
    private static void validate(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.DependencyOperation op,
                                 ControlBootstrapNativeInputs.Sealed input,Map<String,Object> receipt){
        require(DEPENDENCY_LINK.protocol.equals(receipt.get("protocol"))&&ControlBootstrapJournal.phaseId(f.spec().id(),DEPENDENCY_LINK).toString().equals(receipt.get("id"))
                &&op.name().equals(receipt.get("operation"))&&op.role.equals(receipt.get("role"))&&input.binding().equals(receipt.get("binding")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
    }
}
