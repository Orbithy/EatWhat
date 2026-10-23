package you.v50to.eatwhat.control;

import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Pinned-SSH drivers for runtime, WireGuard and shared-state phases, plus
 * independent status-only inspection of retained native components.
 * Each call performs one dispatch or one observation, never a polling loop.
 * A durable claim without a receipt authorizes status only, including when the
 * remote says NOT_STARTED. Native agents own their exact host reservations. */
public final class ControlBootstrapNativePhases {
    private final ControlServerStore store;private final ControlBootstrapJournal journal;
    private final ControlBootstrapAdmission admission;private final ControlBootstrapPhaseClaims claims;
    private final ControlBootstrapNativeInputs inputs;private final ControlSsh ssh;
    public ControlBootstrapNativePhases(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,
                                       ControlBootstrapPhaseClaims claims,ControlBootstrapNativeInputs inputs,ControlSsh ssh){
        this.store=store;this.journal=journal;this.admission=admission;this.claims=claims;this.inputs=inputs;this.ssh=ssh;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private static boolean source(ControlBootstrapJournal.Phase phase){return switch(phase){
        case SOURCE_RUNTIME,SOURCE_PRIVATE_PEER,SOURCE_SHARED_STATE -> true;
        case TARGET_RUNTIME,TARGET_PRIVATE_PEER,TARGET_SHARED_STATE,CONTROLLER_CONFIG,CONTROLLER_VERIFY,BUSINESS_INSTALL,MEMBER_CONFIGURE -> false;
        default -> throw new ControlFailure(409,"BOOTSTRAP_NATIVE_DRIVER_REQUIRED");
    };}
    void bound(ControlBootstrapAdmission.Frozen frozen){
        admission.requireBound(frozen);journal.requireAdmission(frozen.spec().id(),frozen.spec(),frozen.actor(),frozen.review(),frozen.binding());
    }
    Map<String,Object> server(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.Phase phase){
        boolean source=source(phase);var row=store.server(source?f.spec().sourceServerId():f.spec().targetServerId());
        ControlServers.expected(row,source?f.spec().sourceRevision():f.spec().targetRevision());
        admission.requireRegisteredServer(f,source,row);
        require(ControlSsh.rootExecution(row),"BOOTSTRAP_LINUX_ROOT_REQUIRED");
        require((source?f.sourceRoute():f.targetRoute()).equals(ssh.routeBinding(row)),"SSH_ROUTE_BINDING_CHANGED");return row;
    }
    ControlBootstrapNativeInputs.Sealed saved(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.Phase phase,String approval){
        boolean source=source(phase);
        return inputs.load(f.spec().id(),phase,approval,source?f.spec().sourceServerId():f.spec().targetServerId(),
                source?f.spec().sourceRevision():f.spec().targetRevision(),source?f.sourceRoute():f.targetRoute());
    }
    @SuppressWarnings("unchecked") private boolean pending(Map<String,Object> job,ControlBootstrapJournal.Phase phase){
        return "PENDING".equals(((List<Map<String,Object>>)job.get("steps")).get(phase.ordinal()).get("state"));
    }
    public Map<String,Object> advance(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.Phase phase){
        switch(phase){
            case SOURCE_RUNTIME,TARGET_RUNTIME,SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER,SOURCE_SHARED_STATE,TARGET_SHARED_STATE -> {}
            default -> throw new ControlFailure(409,"BOOTSTRAP_NATIVE_DRIVER_REQUIRED");
        }
        boolean source=source(phase);bound(f);var job=journal.job(f.spec().id());String approval=job.get("requestHash").toString();
        // Never inspect a changed route, nor recreate missing inputs for a claimed phase.
        server(f,phase);
        var input=inputs.prepare(f.spec().id(),phase,approval,source?f.spec().sourceServerId():f.spec().targetServerId(),
                source?f.spec().sourceRevision():f.spec().targetRevision(),source?f.sourceRoute():f.targetRoute(),pending(job,phase),
                ()->prepare(f,phase,approval));
        var dispatch=claims.claim(f,phase);
        require(approval.equals(dispatch.approvalDigest()),"BOOTSTRAP_PLAN_CHANGED");
        var payload=new LinkedHashMap<>(input.header());if(dispatch.dispatchAllowed())payload.put("agent",input.agent());
        Map<String,Object> receipt=call(server(f,phase),input.agent(),List.of(dispatch.dispatchAllowed()?"dispatch":"status"),payload);
        validate(f,phase,input,receipt);
        return journal.observe(f.spec().id(),phase,dispatch.claimToken(),receipt);
    }
    /** Independent retained inspection: no prepare, claim, dispatch or journal
     * projection. A new administrator session/process can inspect saved bytes
     * without inheriting the original process's permission to execute. */
    Map<String,Object> inspectSaved(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.Phase phase){
        bound(f);String approval=journal.job(f.spec().id()).get("requestHash").toString();
        switch(phase){
            case SOURCE_RUNTIME,TARGET_RUNTIME,SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER,SOURCE_SHARED_STATE,TARGET_SHARED_STATE -> {}
            case DEPENDENCY_LINK -> {return inspectDependency(f,approval);}
            case CONTROLLER_CONFIG,CONTROLLER_VERIFY -> {return inspectController(f,phase,approval);}
            case BUSINESS_INSTALL -> {return inspectBusiness(f,approval);}
            case MEMBER_CONFIGURE -> {return inspectMemberComponent(f,approval);}
            default -> throw new ControlFailure(409,"BOOTSTRAP_INSPECTION_PHASE_NOT_SUPPORTED");
        }
        var endpoint=server(f,phase);
        if(!inputs.exists(f.spec().id(),phase,source(phase)?f.spec().sourceServerId():f.spec().targetServerId()))throw new ControlFailure(503,"BOOTSTRAP_NATIVE_INPUT_MISSING");
        var input=saved(f,phase,approval);
        var seen=call(endpoint,input.agent(),List.of("status"),input.header());
        validate(f,phase,input,seen);return seen;
    }
    private ControlBootstrapNativeInputs.Sealed retained(ControlBootstrapAdmission.Frozen f,UUID id,ControlBootstrapJournal.Phase phase,String approval,boolean source){
        server(f,source?ControlBootstrapJournal.Phase.SOURCE_RUNTIME:ControlBootstrapJournal.Phase.TARGET_RUNTIME);
        UUID endpoint=source?f.spec().sourceServerId():f.spec().targetServerId();
        if(!inputs.exists(id,phase,endpoint))throw new ControlFailure(503,"BOOTSTRAP_NATIVE_INPUT_MISSING");
        return inputs.load(id,phase,approval,endpoint,source?f.spec().sourceRevision():f.spec().targetRevision(),source?f.sourceRoute():f.targetRoute());
    }
    private Map<String,Object> retainedStatus(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input,boolean source,List<String> command){
        return call(server(f,source?ControlBootstrapJournal.Phase.SOURCE_RUNTIME:ControlBootstrapJournal.Phase.TARGET_RUNTIME),input.agent(),command,input.header());
    }
    private Map<String,Object> component(Map<String,Object> seen,String name){var value=new LinkedHashMap<>(seen);value.put("inspectionComponent",name);return value;}
    @SuppressWarnings("unchecked") private Map<String,Object> inspectDependency(ControlBootstrapAdmission.Frozen f,String approval){
        var parent=((List<Map<String,Object>>)journal.job(f.spec().id()).get("steps")).get(ControlBootstrapJournal.Phase.DEPENDENCY_LINK.ordinal());
        var rows=(List<Map<String,Object>>)parent.get("operations");var row=rows.stream().filter(v->!"SUCCEEDED".equals(v.get("state"))).findFirst().orElse(rows.getLast());
        var op=ControlBootstrapJournal.DependencyOperation.valueOf(row.get("operation").toString());boolean source=op==ControlBootstrapJournal.DependencyOperation.SOURCE;
        var input=retained(f,f.spec().id(),ControlBootstrapJournal.Phase.DEPENDENCY_LINK,approval,source);
        var seen=retainedStatus(f,input,source,List.of("status",op.name()));
        require(ControlBootstrapJournal.Phase.DEPENDENCY_LINK.protocol.equals(seen.get("protocol"))
                &&ControlBootstrapJournal.phaseId(f.spec().id(),ControlBootstrapJournal.Phase.DEPENDENCY_LINK).toString().equals(seen.get("id"))
                &&op.name().equals(seen.get("operation"))&&op.role.equals(seen.get("role"))&&input.binding().equals(seen.get("binding")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        return component(seen,op.name());
    }
    private Map<String,Object> inspectController(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.Phase phase,String approval){
        var input=retained(f,f.spec().id(),ControlBootstrapJournal.Phase.CONTROLLER_CONFIG,approval,false);String op=phase==ControlBootstrapJournal.Phase.CONTROLLER_CONFIG?"STAGE":"VERIFY";
        var seen=retainedStatus(f,input,false,List.of("status",op));
        require("eatwhat-controller-bootstrap-controller-v1".equals(seen.get("protocol"))&&op.equals(seen.get("operation"))
                &&input.header().get(op.equals("VERIFY")?"verifyId":"id").equals(seen.get("id"))
                &&input.header().get("id").equals(seen.get("configurationId"))&&input.binding().equals(seen.get("binding")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        return component(seen,op);
    }
    private Map<String,Object> inspectBusiness(ControlBootstrapAdmission.Frozen f,String approval){
        var input=retained(f,f.spec().id(),ControlBootstrapJournal.Phase.BUSINESS_INSTALL,approval,false);var seen=retainedStatus(f,input,false,List.of("status"));
        require("eatwhat-install-v1".equals(seen.get("protocol"))&&"eatwhat-controller-bootstrap-business-v1".equals(seen.get("bootstrapProtocol"))
                &&"INSTALL".equals(seen.get("operation"))&&input.header().get("id").equals(seen.get("jobId"))
                &&f.spec().targetServerId().toString().equals(seen.get("serverId"))&&input.binding().equals(seen.get("binding")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        return component(seen,"INSTALL");
    }
    private Map<String,Object> inspectMemberComponent(ControlBootstrapAdmission.Frozen f,String approval){
        // The latest saved component is observed, not re-created. CONFIGURED
        // alone cannot prove profile publication or completion of this phase.
        String[][] kinds={{"MEMBER_RUNTIME_INPUTS","eatwhat-member-configure-v1","TARGET"},
                {"MEMBER_HTTPS_INGRESS_INPUTS","eatwhat-controller-bootstrap-member-ingress-v1","TARGET"},
                {"MEMBER_AUTH_ROLE_INPUTS","eatwhat-controller-bootstrap-member-auth-v1","SOURCE"},
                {"MEMBER_TLS_RELAY_INPUTS","eatwhat-controller-bootstrap-member-relay-v1","TARGET"}};
        for(var kind:kinds){
            boolean source=kind[2].equals("SOURCE");UUID id=UUID.nameUUIDFromBytes((f.spec().id()+":"+kind[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(!inputs.exists(id,ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,source?f.spec().sourceServerId():f.spec().targetServerId()))continue;
            var input=retained(f,id,ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,approval,source);var seen=retainedStatus(f,input,source,List.of("status"));
            require(kind[1].equals(seen.get("protocol"))&&input.header().get("id").equals(seen.get("id"))
                    &&f.spec().targetServerId().toString().equals(seen.get("serverId")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
            if(!kind[0].equals("MEMBER_RUNTIME_INPUTS"))require(input.binding().equals(seen.get("binding"))
                    &&(source?"CREATE":"START").equals(seen.get("operation")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
            return component(seen,kind[0].replace("_INPUTS",""));
        }throw new ControlFailure(503,"BOOTSTRAP_NATIVE_INPUT_MISSING");
    }
    static String expanded(String resource,String marker,String embedded){
        String source=ControlServers.resource(resource);require(source.indexOf(marker)>=0&&source.indexOf(marker)==source.lastIndexOf(marker),"BOOTSTRAP_NATIVE_AGENT_INVALID");
        return source.replace(marker,Base64.getEncoder().encodeToString(embedded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    Map<String,Object> call(Map<String,Object> server,String agent,List<String> args,Map<String,Object> payload){
        try{
            byte[] raw=ControlBootstrapNativeJson.encode(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // Member configuration shares the installer's newline-delimited
            // JSON reader. EOF-based agents also accept this JSON whitespace.
            // Binary/artifact uploads use upload() directly and stay unchanged.
            return upload(server,agent,args,out->{out.write(raw);out.write('\n');});
        }catch(ControlFailure failure){throw failure;}
        // A refusal may follow partial remote effects. Preserve the dispatch
        // intent and use status on the next call; do not infer a rollback.
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE");}
    }
    Map<String,Object> upload(Map<String,Object> server,String agent,List<String> args,ControlSsh.Upload upload){
        try{return ssh.call(server,agent,args,upload,args.equals(List.of("verify-joined"))?120:45);}
        // Only the SSH transport's reviewed protocol/code allowlist can reach
        // this type. Preserve the diagnostic without reclassifying the claim.
        catch(ControlSsh.RemoteRefusal refusal){throw new ControlFailure(409,refusal.code);}
        catch(ControlFailure failure){throw failure;}
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE");}
    }
    private ControlBootstrapNativeInputs.Prepared prepare(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.Phase phase,String approval){
        String agent;var header=new LinkedHashMap<String,Object>();
        switch(phase){
            case SOURCE_RUNTIME,TARGET_RUNTIME -> {
                agent=phase==SOURCE_RUNTIME?expanded("controller-bootstrap-runtime.py","__EATWHAT_CONTROLLER_PREFLIGHT_BASE64__",ControlServers.resource("controller-bootstrap-preflight.py"))
                        :saved(f,SOURCE_RUNTIME,approval).agent();
                var initial=phase==SOURCE_RUNTIME?f.sourceInitial():f.targetInitial();
                header.put("request",initial.request());header.put("expectedProof",initial.result().get("proof"));
            }
            case SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER -> {
                boolean source=source(phase);var runtimePhase=source?SOURCE_RUNTIME:TARGET_RUNTIME;
                var runtime=saved(f,runtimePhase,approval);
                var peer=receipt(f,source?TARGET_RUNTIME:SOURCE_RUNTIME,approval);
                Object publicKey=peer.get("publicKey");require(validPublicKey(publicKey),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
                agent=source?expanded("controller-bootstrap-link.py","__EATWHAT_CONTROLLER_RUNTIME_BASE64__",runtime.agent()):saved(f,SOURCE_PRIVATE_PEER,approval).agent();
                header.put("id",ControlBootstrapJournal.phaseId(f.spec().id(),phase).toString());
                header.put("runtimeId",ControlBootstrapJournal.phaseId(f.spec().id(),runtimePhase).toString());
                header.put("runtimeBinding",runtime.binding());header.put("peerPublicKey",publicKey);
            }
            case SOURCE_SHARED_STATE,TARGET_SHARED_STATE -> {
                boolean source=source(phase);var linkPhase=source?SOURCE_PRIVATE_PEER:TARGET_PRIVATE_PEER;
                var link=saved(f,linkPhase,approval);receipt(f,linkPhase,approval);
                agent=source?expanded("controller-bootstrap-state.py","__EATWHAT_CONTROLLER_STATE_POLICY_BASE64__",ControlServers.resource("controller-bootstrap-state-policy.py"))
                        :saved(f,SOURCE_SHARED_STATE,approval).agent();
                header.put("id",ControlBootstrapJournal.phaseId(f.spec().id(),phase).toString());
                header.put("linkId",ControlBootstrapJournal.phaseId(f.spec().id(),linkPhase).toString());header.put("linkBinding",link.binding());header.put("approvalDigest",approval);
                if(source){
                    var metadata=call(server(f,phase),agent,List.of("metadata"),header);
                    require(phase.protocol.equals(metadata.get("protocol"))&&header.get("id").equals(metadata.get("id"))
                            &&Boolean.FALSE.equals(metadata.get("effectsStarted"))&&metadata.get("privateMetadata") instanceof Map<?,?>,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
                    var privateMetadata=(Map<?,?>)metadata.get("privateMetadata");
                    require(privateMetadata.keySet().equals(Set.of("ownerUid","ownerGid","idmapDomain","stateKeySha256")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
                    privateMetadata.forEach((key,value)->header.put(key.toString(),value));
                }else{
                    var original=saved(f,SOURCE_SHARED_STATE,approval).header();
                    for(String key:List.of("ownerUid","ownerGid","idmapDomain","stateKeySha256"))header.put(key,original.get(key));
                }
                var inspected=call(server(f,phase),agent,List.of("inspect"),header);
                require(phase.protocol.equals(inspected.get("protocol"))&&header.get("id").equals(inspected.get("id"))
                        &&(source?"SOURCE":"TARGET").equals(inspected.get("role"))&&Boolean.FALSE.equals(inspected.get("effectsStarted"))
                        &&inspected.get("proof") instanceof String proof&&proof.matches("[a-f0-9]{64}"),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
                header.put("expectedProof",inspected.get("proof"));
            }
            default -> throw new ControlFailure(409,"BOOTSTRAP_NATIVE_DRIVER_REQUIRED");
        }
        header.put("approvalDigest",approval);header.put("agentSha256",ControlBootstrapNativeJson.sha(agent));
        return new ControlBootstrapNativeInputs.Prepared(header,agent);
    }
    Map<String,Object> receipt(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.Phase phase,String approval){
        var input=saved(f,phase,approval);var receipt=journal.successfulReceipt(f.spec().id(),phase);validate(f,phase,input,receipt);return receipt;
    }
    private static boolean validPublicKey(Object value){
        if(!(value instanceof String key)||!key.matches("[A-Za-z0-9+/]{43}="))return false;
        try{byte[] bytes=Base64.getDecoder().decode(key);return bytes.length==32&&!Arrays.equals(bytes,new byte[32])&&Base64.getEncoder().encodeToString(bytes).equals(key);}
        catch(IllegalArgumentException failure){return false;}
    }
    private static void validate(ControlBootstrapAdmission.Frozen f,ControlBootstrapJournal.Phase phase,
                                 ControlBootstrapNativeInputs.Sealed input,Map<String,Object> receipt){
        require(phase.protocol.equals(receipt.get("protocol"))&&ControlBootstrapJournal.phaseId(f.spec().id(),phase).toString().equals(receipt.get("id"))
                &&input.binding().equals(receipt.get("binding")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        if(phase==SOURCE_RUNTIME||phase==TARGET_RUNTIME){
            require((source(phase)?"SOURCE":"TARGET").equals(receipt.get("role"))&&input.approvalDigest().equals(receipt.get("approvalDigest"))
                    &&f.spec().sourceServerId().toString().equals(receipt.get("sourceServerId"))
                    &&f.spec().targetServerId().toString().equals(receipt.get("targetServerId")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        }else if(phase==SOURCE_PRIVATE_PEER||phase==TARGET_PRIVATE_PEER){
            require(input.approvalDigest().equals(receipt.get("approvalDigest"))&&input.header().get("runtimeId").equals(receipt.get("runtimeId")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        }
    }
}
