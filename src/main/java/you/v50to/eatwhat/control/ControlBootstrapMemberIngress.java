package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Concrete initial MEMBER_CONFIGURE child operations. Their success is only
 * a private HTTPS entry; the parent remains unconfigured and unjoined.
 * Lost preparation/start acknowledgements authorize status of saved bytes only. */
public final class ControlBootstrapMemberIngress {
    static final String PROTOCOL="eatwhat-controller-bootstrap-member-ingress-v1",PERMISSION="CREATE_PRIVATE_MEMBER_HTTPS";
    enum Operation { PREPARE, START }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlServerStore store;private final ControlBootstrapJournal journal;private final ControlBootstrapNativePhases nativePhases;
    private final ControlBootstrapPhaseClaims claims;private final ControlBootstrapNativeInputs inputs;private final ControlBootstrapMemberRelay relay;private final ControlBootstrapMemberAuth auth;
    public ControlBootstrapMemberIngress(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapPhaseClaims claims,ControlBootstrapNativeInputs inputs,ControlBootstrapMemberRelay relay,ControlBootstrapMemberAuth auth){
        this.store=store;this.journal=journal;this.nativePhases=nativePhases;this.claims=claims;this.inputs=inputs;this.relay=relay;this.auth=auth;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private static UUID id(UUID bootstrap,String name){return UUID.nameUUIDFromBytes((bootstrap+":"+name).getBytes(StandardCharsets.UTF_8));}
    private static UUID inputId(ControlBootstrapAdmission.Frozen f){return id(f.spec().id(),"MEMBER_HTTPS_INGRESS_INPUTS");}
    private Path path(ControlBootstrapAdmission.Frozen f){return store.directory().resolve("controller-member-ingress-"+f.spec().id()+".json");}
    private String approval(ControlBootstrapAdmission.Frozen f){return journal.job(f.spec().id()).get("requestHash").toString();}
    private ControlBootstrapNativeInputs.Sealed input(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);return inputs.load(inputId(f),MEMBER_CONFIGURE,approval(f),f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute());
    }
    private void ensure(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);nativePhases.server(f,MEMBER_CONFIGURE);String approval=approval(f);
        inputs.prepare(inputId(f),MEMBER_CONFIGURE,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute(),
                !Files.exists(path(f),LinkOption.NOFOLLOW_LINKS),()->prepare(f,approval),input->initialize(f,input));
    }
    private ControlBootstrapNativeInputs.Prepared prepare(ControlBootstrapAdmission.Frozen f,String approval){
        require(journal.approvedPermissions(f.spec().id()).contains(PERMISSION),"BOOTSTRAP_MEMBER_INGRESS_SCOPE_REQUIRED");relay.ready(f);auth.ready(f);
        var parent=inputs.load(id(f.spec().id(),"MEMBER_TLS_RELAY_INPUTS"),MEMBER_CONFIGURE,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute());
        String agent=ControlServers.resource("controller-bootstrap-member-ingress.py");var h=new LinkedHashMap<String,Object>();
        h.put("id",id(f.spec().id(),"MEMBER_HTTPS_INGRESS").toString());h.put("bootstrapId",f.spec().id().toString());h.put("serverId",f.spec().targetServerId().toString());
        h.put("relayId",parent.header().get("id"));h.put("relayBinding",parent.binding());h.put("relayAgentSha256",parent.header().get("agentSha256"));
        h.put("sourceAddress",f.review().get("sourceAddress"));h.put("targetAddress",f.review().get("targetAddress"));h.put("approvalDigest",approval);h.put("permission",PERMISSION);
        var seen=nativePhases.call(nativePhases.server(f,MEMBER_CONFIGURE),agent,List.of("inspect"),h);validateIdentity(h,"INSPECT",seen);
        require(Boolean.FALSE.equals(seen.get("effectsStarted"))&&seen.get("proof") instanceof String hash&&hash.matches("[a-f0-9]{64}"),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        h.put("expectedProof",seen.get("proof"));h.put("agentSha256",ControlBootstrapNativeJson.sha(agent));return new ControlBootstrapNativeInputs.Prepared(h,agent);
    }
    private static void validateIdentity(Map<String,Object> header,String operation,Map<String,Object> receipt){
        require(PROTOCOL.equals(receipt.get("protocol"))&&header.get("id").equals(receipt.get("id"))&&header.get("serverId").equals(receipt.get("serverId"))
                &&ControlBootstrapNativeJson.digest(header).equals(receipt.get("binding"))&&operation.equals(receipt.get("operation")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        require(JSON.writeValueAsBytes(receipt).length<=8192,"BOOTSTRAP_PHASE_RECEIPT_TOO_LARGE");
    }
    private static Map<String,Object> row(Map<String,Object> body,Operation op){return (Map<String,Object>)((List<?>)body.get("operations")).get(op.ordinal());}
    private void save(ControlBootstrapAdmission.Frozen f,Map<String,Object> body){
        String raw=JSON.writeValueAsString(body);store.saveJson(path(f),Map.of("version",1,"body",store.encrypt(raw),"seal",store.fingerprint(raw)));
    }
    private Map<String,Object> load(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input){
        require(Files.exists(path(f),LinkOption.NOFOLLOW_LINKS),"BOOTSTRAP_MEMBER_INGRESS_RECORD_MISSING");
        var saved=store.readJson(path(f));require(Integer.valueOf(1).equals(saved.get("version")),"BOOTSTRAP_MEMBER_INGRESS_RECORD_CHANGED");
        String raw=store.decrypt(saved.get("body").toString());require(store.fingerprint(raw).equals(saved.get("seal")),"BOOTSTRAP_MEMBER_INGRESS_RECORD_CHANGED");
        var body=JSON.readValue(raw,LinkedHashMap.class);
        require(f.spec().id().toString().equals(body.get("bootstrapId"))&&input.binding().equals(body.get("inputBinding"))&&input.approvalDigest().equals(body.get("approvalDigest"))
                &&body.get("operations") instanceof List<?> ops&&ops.size()==2,"BOOTSTRAP_MEMBER_INGRESS_RECORD_CHANGED");
        boolean gap=false;for(var op:Operation.values()){
            var row=row(body,op);require(op.name().equals(row.get("operation"))&&Set.of("PENDING","CLAIMED","SUCCEEDED","FAILED").contains(row.get("state")),"BOOTSTRAP_MEMBER_INGRESS_RECORD_CHANGED");
            if(gap)require("PENDING".equals(row.get("state")),"BOOTSTRAP_MEMBER_INGRESS_RECORD_CHANGED");if(!"SUCCEEDED".equals(row.get("state")))gap=true;
        }return body;
    }
    private <T>T locked(Supplier<T> action){
        Path path=store.directory().resolve("member-ingress-operations.lock");
        try{
            try{Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            ControlServerStore.privateFile(path,1024);
            try(var channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new ControlFailure(409,"BOOTSTRAP_MEMBER_INGRESS_RECORD_BUSY");}
                if(lock==null)throw new ControlFailure(409,"BOOTSTRAP_MEMBER_INGRESS_RECORD_BUSY");try(lock){return action.get();}
            }
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_MEMBER_INGRESS_RECORD_UNAVAILABLE");}
    }
    private void initialize(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input){
        locked(()->{
            if(Files.exists(path(f),LinkOption.NOFOLLOW_LINKS)){load(f,input);return null;}
            var body=new LinkedHashMap<String,Object>();body.put("bootstrapId",f.spec().id().toString());body.put("inputBinding",input.binding());body.put("approvalDigest",input.approvalDigest());
            var ops=new ArrayList<Map<String,Object>>();for(var op:Operation.values())ops.add(new LinkedHashMap<>(Map.of("operation",op.name(),"state","PENDING")));body.put("operations",ops);save(f,body);return null;
        });claims.memberConfiguration(f);
    }
    private boolean claim(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input,Operation op){
        return locked(()->{
            var body=load(f,input);for(var prior:Operation.values())if(prior.ordinal()<op.ordinal())require("SUCCEEDED".equals(row(body,prior).get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
            var r=row(body,op);require(!"FAILED".equals(r.get("state")),"BOOTSTRAP_TASK_TERMINAL");if(!"PENDING".equals(r.get("state")))return false;
            require(journal.approvedPermissions(f.spec().id()).contains(PERMISSION),"BOOTSTRAP_MEMBER_INGRESS_SCOPE_REQUIRED");
            relay.ready(f);auth.ready(f);claims.memberConfigurationIntent(f,()->{r.put("state","CLAIMED");save(f,body);});return true;
        });
    }
    private void validate(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input,Operation op,Map<String,Object> receipt){
        validateIdentity(input.header(),op.name(),receipt);
        require(Set.of("PREPARED","NOT_PREPARED","OUTCOME_UNKNOWN","REJECTED","FAILED").contains(receipt.get("state"))&&op==Operation.PREPARE
                ||Set.of("NOT_STARTED","ACCEPTED","RUNNING","OUTCOME_UNKNOWN","HTTPS_READY","FAILED").contains(receipt.get("state"))&&op==Operation.START,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        if("HTTPS_READY".equals(receipt.get("state"))){
            require(Objects.equals(journal.successfulReceipt(f.spec().id(),BUSINESS_INSTALL).get("createdNetworkId"),receipt.get("networkId"))
                    &&input.header().get("targetAddress").equals(receipt.get("targetAddress")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
            require(receipt.get("caSha256") instanceof String hash&&hash.matches("[a-f0-9]{64}"),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
            for(String key:List.of("tlsListenerVerified","healthReady","anonymousSnapshotDenied"))require(Boolean.TRUE.equals(receipt.get(key)),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
            for(String key:List.of("memberConfigured","joinAuthorized"))require(Boolean.FALSE.equals(receipt.get(key)),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        }
    }

    private Map<String,Object> progress(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input){
        return locked(()->{var body=load(f,input);var result=new LinkedHashMap<String,Object>();for(var op:Operation.values())result.put(op.name(),row(body,op).get("state"));return result;});
    }
    private Map<String,Object> result(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input){
        var failure=locked(()->{var body=load(f,input);for(var op:Operation.values())if("FAILED".equals(row(body,op).get("state"))){
            @SuppressWarnings("unchecked") var receipt=(Map<String,Object>)row(body,op).get("receipt");validate(f,input,op,receipt);return receipt;}return null;});
        if(failure!=null)return claims.memberConfigurationFailed(f,"INGRESS",failure);
        var result=new LinkedHashMap<>(journal.job(f.spec().id()));result.put("memberIngressOperations",progress(f,input));result.put("memberConfigurationPending",true);return result;
    }
    public Map<String,Object> advance(ControlBootstrapAdmission.Frozen f){
        ensure(f);var input=input(f);var progress=progress(f,input);
        for(var op:Operation.values()){
            if("FAILED".equals(progress.get(op.name())))return result(f,input);if("SUCCEEDED".equals(progress.get(op.name())))continue;
            boolean dispatch=claim(f,input,op);var payload=new LinkedHashMap<>(input.header());if(op==Operation.PREPARE&&dispatch)payload.put("agent",input.agent());
            String command=op==Operation.PREPARE?(dispatch?"prepare":"prepare-status"):(dispatch?"dispatch":"status");
            var receipt=nativePhases.call(nativePhases.server(f,MEMBER_CONFIGURE),input.agent(),List.of(command),payload);validate(f,input,op,receipt);
            locked(()->{var body=load(f,input);var r=row(body,op);require("CLAIMED".equals(r.get("state")),"BOOTSTRAP_MEMBER_INGRESS_RECORD_CHANGED");
                if((op==Operation.PREPARE?"PREPARED":"HTTPS_READY").equals(receipt.get("state")))r.put("state","SUCCEEDED");else if("FAILED".equals(receipt.get("state")))r.put("state","FAILED");
                r.put("receipt",receipt);save(f,body);return null;});return result(f,input);
        }return result(f,input);
    }
    /** Fresh local material proof and actual SOURCE-to-TARGET TLS reachability.
     * Only the public CA travels to SOURCE; private keys stay on TARGET. */
    Map<String,Object> ready(ControlBootstrapAdmission.Frozen f){
        var input=input(f);var material=materials(f);
        var payload=new LinkedHashMap<>(input.header());payload.put("caPem",material.get("caPem"));payload.put("caSha256",material.get("caSha256"));
        var peer=nativePhases.call(nativePhases.server(f,SOURCE_RUNTIME),input.agent(),List.of("verify-source"),payload);validateIdentity(payload,"VERIFY_SOURCE",peer);
        require("PEER_VERIFIED".equals(peer.get("state")),"BOOTSTRAP_MEMBER_INGRESS_PEER_UNCONFIRMED");
        for(String key:List.of("sourceRouteVerified","targetTlsVerified","healthReady","anonymousSnapshotDenied"))require(Boolean.TRUE.equals(peer.get(key)),"BOOTSTRAP_MEMBER_INGRESS_PEER_UNCONFIRMED");
        for(String key:List.of("memberConfigured","joinAuthorized"))require(Boolean.FALSE.equals(peer.get(key)),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        return peer;
    }
    /** Original public CA for the sealed, two-controller profile publication plan. */
    Map<String,Object> materials(ControlBootstrapAdmission.Frozen f){
        var input=input(f);require(progress(f,input).values().stream().allMatch("SUCCEEDED"::equals),"BOOTSTRAP_MEMBER_INGRESS_NOT_READY");
        var material=nativePhases.call(nativePhases.server(f,MEMBER_CONFIGURE),input.agent(),List.of("materials"),input.header());validateIdentity(input.header(),"MATERIALS",material);
        require("HTTPS_READY".equals(material.get("state"))&&input.header().get("targetAddress").equals(material.get("targetAddress"))
                &&material.get("caPem") instanceof String ca&&ca.length()<=65536&&!ca.contains("PRIVATE KEY")
                &&ControlBootstrapNativeJson.sha(ca).equals(material.get("caSha256")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        var expected=locked(()->row(load(f,input),Operation.START).get("receipt"));
        require(expected instanceof Map<?,?> receipt&&Objects.equals(receipt.get("caSha256"),material.get("caSha256")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        return material;
    }
}
