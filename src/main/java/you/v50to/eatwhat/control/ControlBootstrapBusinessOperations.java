package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.BUSINESS_INSTALL;

/** Separate once-only preparation/install intents. Saving a receipt and
 * projecting the installation may be reconciled after a crash; dispatch may
 * never be inferred from a missing remote receipt or lost acknowledgement. */
public final class ControlBootstrapBusinessOperations {
    public enum Operation { PREPARE, INSTALL }
    record Permit(Operation operation,boolean dispatchAllowed,String token,ControlBootstrapNativeInputs.Sealed input){
        @Override public String toString(){return "BusinessOperationPermit[redacted]";}
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlServerStore store;private final ControlBootstrapJournal journal;
    private final ControlBootstrapPhaseClaims claims;private final ControlBootstrapNativeInputs inputs;private final ControlBootstrapNativePhases nativePhases;
    public ControlBootstrapBusinessOperations(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapPhaseClaims claims,
            ControlBootstrapNativeInputs inputs,ControlBootstrapNativePhases nativePhases){
        this.store=store;this.journal=journal;this.claims=claims;this.inputs=inputs;this.nativePhases=nativePhases;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private Path path(UUID id){return store.directory().resolve("business-operations-"+id+".json");}
    private ControlBootstrapNativeInputs.Sealed input(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);String approval=journal.job(f.spec().id()).get("requestHash").toString();
        var input=inputs.load(f.spec().id(),BUSINESS_INSTALL,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute());
        require(ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL).toString().equals(input.header().get("id"))
                &&f.spec().id().toString().equals(input.header().get("bootstrapId"))&&input.header().get("business") instanceof Map<?,?> b
                &&f.spec().targetServerId().toString().equals(b.get("serverId")),"BOOTSTRAP_NATIVE_INPUT_CHANGED");return input;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> row(Map<String,Object> body,Operation operation){return (Map<String,Object>)((List<?>)body.get("operations")).get(operation.ordinal());}
    private void save(ControlBootstrapNativeInputs.Sealed input,Map<String,Object> body){
        String raw=JSON.writeValueAsString(body);require(raw.length()<=65536,"BOOTSTRAP_BUSINESS_RECORD_TOO_LARGE");
        store.saveJson(path(input.jobId()),Map.of("version",1,"body",store.encrypt(raw),"seal",store.fingerprint(raw)));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> load(ControlBootstrapNativeInputs.Sealed input){
        var file=store.readJson(path(input.jobId()));require(Integer.valueOf(1).equals(file.get("version")),"BOOTSTRAP_BUSINESS_RECORD_CHANGED");
        String raw=store.decrypt(file.get("body").toString());require(store.fingerprint(raw).equals(file.get("seal")),"BOOTSTRAP_BUSINESS_RECORD_CHANGED");var body=JSON.readValue(raw,Map.class);
        require(input.jobId().toString().equals(body.get("id"))&&input.binding().equals(body.get("inputBinding"))&&input.approvalDigest().equals(body.get("approvalDigest"))
                &&body.get("operations") instanceof List<?> list&&list.size()==2,"BOOTSTRAP_BUSINESS_RECORD_CHANGED");
        boolean gap=false;for(var op:Operation.values()){
            var value=row(body,op);require(op.name().equals(value.get("operation"))&&Set.of("PENDING","CLAIMED","SUCCEEDED","FAILED").contains(value.get("state")),"BOOTSTRAP_BUSINESS_RECORD_CHANGED");
            if(gap)require("PENDING".equals(value.get("state")),"BOOTSTRAP_BUSINESS_RECORD_CHANGED");if(!"SUCCEEDED".equals(value.get("state")))gap=true;
        }return body;
    }
    @FunctionalInterface private interface Locked<T>{T run();}
    private <T>T locked(Locked<T> action){
        Path path=store.directory().resolve("business-operations.lock");
        try{
            try{Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            ControlServerStore.privateFile(path,1024);
            try(var channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new ControlFailure(409,"BOOTSTRAP_BUSINESS_RECORD_BUSY");}
                if(lock==null)throw new ControlFailure(409,"BOOTSTRAP_BUSINESS_RECORD_BUSY");try(lock){return action.run();}
            }
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_BUSINESS_RECORD_UNAVAILABLE");}
    }
    ControlBootstrapJournal.Dispatch initialize(ControlBootstrapAdmission.Frozen f){
        var input=input(f);locked(()->{
            if(Files.exists(path(input.jobId()),LinkOption.NOFOLLOW_LINKS)){load(input);return null;}
            var steps=(List<?>)journal.job(f.spec().id()).get("steps");
            require("PENDING".equals(((Map<?,?>)steps.get(BUSINESS_INSTALL.ordinal())).get("state")),"BOOTSTRAP_BUSINESS_RECORD_MISSING");
            var body=new LinkedHashMap<String,Object>();body.put("id",input.jobId().toString());body.put("inputBinding",input.binding());body.put("approvalDigest",input.approvalDigest());
            var rows=new ArrayList<Map<String,Object>>();for(var op:Operation.values())rows.add(new LinkedHashMap<>(Map.of("operation",op.name(),"state","PENDING")));
            body.put("operations",rows);save(input,body);return null;
        });return claims.claim(f,BUSINESS_INSTALL);
    }
    private Permit permit(ControlBootstrapNativeInputs.Sealed input,Operation operation,boolean allowed){
        return new Permit(operation,allowed,store.fingerprint(input.jobId()+":"+input.approvalDigest()+":"+input.binding()+":"+operation),input);
    }
    Permit claim(ControlBootstrapAdmission.Frozen f,Operation operation){
        var input=input(f);return locked(()->{
            var body=load(input);for(var prior:Operation.values())if(prior.ordinal()<operation.ordinal())require("SUCCEEDED".equals(row(body,prior).get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
            var value=row(body,operation);require(!"FAILED".equals(value.get("state")),"BOOTSTRAP_TASK_TERMINAL");
            if(!"PENDING".equals(value.get("state")))return permit(input,operation,false);
            claims.businessIntent(f,()->{value.put("state","CLAIMED");save(input,body);});return permit(input,operation,true);
        });
    }
    static void validate(ControlBootstrapNativeInputs.Sealed input,Operation operation,Map<String,Object> receipt){
        var business=(Map<?,?>)input.header().get("business");
        require(BUSINESS_INSTALL.protocol.equals(receipt.get("protocol"))&&ControlBootstrapBusinessFactory.PROTOCOL.equals(receipt.get("bootstrapProtocol"))
                &&input.header().get("id").equals(receipt.get("jobId"))&&business.get("serverId").equals(receipt.get("serverId"))
                &&operation.name().equals(receipt.get("operation"))&&input.binding().equals(receipt.get("binding")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        require(JSON.writeValueAsBytes(receipt).length<=8192,"BOOTSTRAP_PHASE_RECEIPT_TOO_LARGE");
        if(operation==Operation.INSTALL&&"SUCCEEDED".equals(receipt.get("state"))){
            for(String key:List.of("siteId","instanceId","mode","clusterId","allocationSlot","artifactSha256","httpPort"))
                require(Objects.equals(business.get(key),receipt.get(key)),"BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED");
            require(("/opt/eatwhat-sites/"+business.get("serverId")).equals(receipt.get("root"))
                    &&("fresh-install-restore-"+input.header().get("id")).equals(receipt.get("restoreEvidenceId"))
                    &&Boolean.TRUE.equals(receipt.get("restoreCleanupVerified"))&&"NOT_JOINED".equals(receipt.get("clusterMembership"))
                    &&Boolean.TRUE.equals(receipt.get("priorNetworkRevalidationRequired"))&&Boolean.FALSE.equals(receipt.get("businessHealthRevalidated")),"BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED");
            if(input.header().containsKey("networkPolicySha256"))validateNetwork(input,receipt);
            if(input.header().containsKey("servicesPolicySha256"))validateServices(input,receipt);
            if(input.header().containsKey("firewallPolicySha256"))validateFirewall(input,receipt);
        }
    }
    private static void validateFirewall(ControlBootstrapNativeInputs.Sealed input,Map<String,Object> receipt){
        String code="BOOTSTRAP_BUSINESS_FIREWALL_UNCONFIRMED";var business=(Map<?,?>)input.header().get("business");
        for(String prefix:List.of("firewall","ipv6")){
            require(Boolean.TRUE.equals(receipt.get(prefix+"DeltaVerified"))&&receipt.get(prefix+"Proof") instanceof Map<?,?>,code);
            var proof=(Map<?,?>)receipt.get(prefix+"Proof");String expected=prefix.equals("firewall")?"expectedFirewallBaselineSha256":"expectedIpv6BaselineSha256";
            require(("eatwhat-business-"+prefix+"-v1").equals(proof.get("protocol"))&&(prefix.equals("firewall")?"FIREWALL_DELTA_VERIFIED":"IPV6_DELTA_VERIFIED").equals(proof.get("state"))
                    &&input.binding().equals(proof.get("inputBinding"))&&input.approvalDigest().equals(proof.get("approvalDigest"))
                    &&input.header().get("id").equals(proof.get("jobId"))&&business.get("serverId").equals(proof.get("serverId"))
                    &&Objects.equals(receipt.get("createdNetworkId"),proof.get("ownedNetworkId"))&&input.header().get(expected).equals(proof.get("baselineSha256"))
                    &&Boolean.TRUE.equals(proof.get(prefix+"DeltaVerified"))&&Boolean.FALSE.equals(proof.get("joinAuthorized"))
                    &&proof.get("observationSha256") instanceof String hash&&hash.matches("[a-f0-9]{64}"),code);
            for(String key:prefix.equals("firewall")?List.of("originalRulesPreserved"):List.of("originalAddressesAndRoutesPreserved","originalSysctlsPreserved"))require(Boolean.TRUE.equals(proof.get(key)),code);
        }
    }
    private static void validateServices(ControlBootstrapNativeInputs.Sealed input,Map<String,Object> receipt){
        String code="BOOTSTRAP_BUSINESS_SERVICES_UNCONFIRMED";
        require(Boolean.TRUE.equals(receipt.get("controllerServicesRevalidated"))&&receipt.get("controllerServicesProof") instanceof Map<?,?>,code);
        var proof=(Map<?,?>)receipt.get("controllerServicesProof");var business=(Map<?,?>)input.header().get("business");
        require("eatwhat-business-controller-services-v1".equals(proof.get("protocol"))&&"CONTROLLER_SERVICES_REVALIDATED".equals(proof.get("state"))
                &&input.header().get("controllerBinding").equals(proof.get("controllerBinding"))&&input.binding().equals(proof.get("inputBinding"))
                &&business.get("serverId").equals(proof.get("serverId"))&&input.header().get("id").equals(proof.get("jobId"))
                &&input.approvalDigest().equals(proof.get("approvalDigest"))&&input.header().get("expectedServicesBaselineSha256").equals(proof.get("baselineSha256"))
                &&proof.get("observationSha256") instanceof String hash&&hash.matches("[a-f0-9]{64}"),code);
        for(String key:List.of("controllerServicesRevalidated","boundedNfsIdentityVerified","mountGuardVerified","privateSshMasterVerified","anonymousAdminDenied",
                "readOnlyAuthorityDatabaseVerified","currentControllerLeaseLive","firewallRevalidationRequired","ipv6RevalidationRequired"))require(Boolean.TRUE.equals(proof.get(key)),code);
        require("UP".equals(proof.get("controllerReadiness"))&&Boolean.FALSE.equals(proof.get("businessHealthVerified"))&&Boolean.FALSE.equals(proof.get("joinAuthorized")),code);
    }
    private static void validateNetwork(ControlBootstrapNativeInputs.Sealed input,Map<String,Object> receipt){
        String code="BOOTSTRAP_BUSINESS_NETWORK_UNCONFIRMED";
        require(Boolean.TRUE.equals(receipt.get("kernelIpv4DeltaVerified"))&&receipt.get("kernelNetworkProof") instanceof Map<?,?>,code);
        var proof=(Map<?,?>)receipt.get("kernelNetworkProof");var business=(Map<?,?>)input.header().get("business");
        require("eatwhat-business-kernel-network-v1".equals(proof.get("protocol"))&&"KERNEL_IPV4_DELTA_VERIFIED".equals(proof.get("state"))
                &&business.get("serverId").equals(proof.get("serverId"))&&input.header().get("id").equals(proof.get("jobId"))
                &&input.approvalDigest().equals(proof.get("approvalDigest"))&&input.binding().equals(proof.get("inputBinding"))
                &&input.header().get("expectedKernelBaselineSha256").equals(proof.get("baselineSha256"))
                &&Objects.equals(receipt.get("createdNetworkId"),proof.get("ownedNetworkId"))&&"SUCCEEDED".equals(receipt.get("installationState")),code);
        for(Object hash:List.of(Objects.toString(receipt.get("createdNetworkId"),""),Objects.toString(receipt.get("createdNetworkProofSha256"),""),
                Objects.toString(receipt.get("networkBaselineSha256"),""),Objects.toString(proof.get("observationSha256"),"")))
            require(hash instanceof String sha&&sha.matches("[a-f0-9]{64}"),code);
        for(String key:List.of("kernelIpv4DeltaVerified","ipv4DefaultRoutePreserved","dnsPreserved","ipv6RevalidationRequired","privateLinkRevalidationRequired","firewallRevalidationRequired"))
            require(Boolean.TRUE.equals(proof.get(key)),code);
        require(Boolean.FALSE.equals(proof.get("businessHealthVerified"))&&Boolean.FALSE.equals(proof.get("joinAuthorized")),code);
    }
    void observe(ControlBootstrapAdmission.Frozen f,Permit permit,Map<String,Object> receipt){
        var input=input(f);require(permit.token().equals(permit(input,permit.operation(),false).token()),"BOOTSTRAP_PHASE_TOKEN_MISMATCH");validate(input,permit.operation(),receipt);
        locked(()->{
            var body=load(input);var value=row(body,permit.operation());require(!"PENDING".equals(value.get("state")),"BOOTSTRAP_PHASE_NOT_DISPATCHED");
            if(Set.of("SUCCEEDED","FAILED").contains(value.get("state")))return null;
            String state=Objects.toString(receipt.get("state"),"");value.put("remoteState",state.matches("[A-Z_]{1,64}")?state:"UNRECOGNIZED");value.put("receipt",receipt);
            if((permit.operation()==Operation.PREPARE?"PREPARED":"SUCCEEDED").equals(state))value.put("state","SUCCEEDED");else if("FAILED".equals(state))value.put("state","FAILED");
            save(input,body);return null;
        });
    }
    Map<String,Object> progress(ControlBootstrapAdmission.Frozen f){var input=input(f);return locked(()->{
        var body=load(input);var result=new LinkedHashMap<String,Object>();for(var op:Operation.values())result.put(op.name(),row(body,op).get("state"));return result;
    });}
    @SuppressWarnings("unchecked") Map<String,Object> terminalReceipt(ControlBootstrapAdmission.Frozen f,Operation operation){var input=input(f);return locked(()->{
        var value=row(load(input),operation);require(Set.of("SUCCEEDED","FAILED").contains(value.get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
        var receipt=(Map<String,Object>)value.get("receipt");validate(input,operation,receipt);return receipt;
    });}
}
