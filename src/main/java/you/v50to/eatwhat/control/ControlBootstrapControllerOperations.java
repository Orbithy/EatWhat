package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.CONTROLLER_CONFIG;

/** Controller preparation and STAGE are once-only, separately from resumable
 * public artifact bytes. Never infer permission to repeat preparation from an
 * absent remote task after a lost acknowledgement. No HTTP input is accepted. */
public final class ControlBootstrapControllerOperations {
    public enum Operation { PREPARE, TRANSFER, STAGE }
    record Permit(Operation operation,boolean dispatchAllowed,String token,ControlBootstrapNativeInputs.Sealed input){
        @Override public String toString(){return "ControllerOperationPermit[redacted]";}
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlServerStore store;private final ControlBootstrapJournal journal;
    private final ControlBootstrapPhaseClaims claims;private final ControlBootstrapNativeInputs inputs;private final ControlBootstrapNativePhases nativePhases;
    public ControlBootstrapControllerOperations(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapPhaseClaims claims,
                                               ControlBootstrapNativeInputs inputs,ControlBootstrapNativePhases nativePhases){
        this.store=store;this.journal=journal;this.claims=claims;this.inputs=inputs;this.nativePhases=nativePhases;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private Path path(UUID id){return store.directory().resolve("controller-operations-"+id+".json");}
    private ControlBootstrapNativeInputs.Sealed input(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);String approval=journal.job(f.spec().id()).get("requestHash").toString();
        var input=inputs.load(f.spec().id(),CONTROLLER_CONFIG,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute());
        require(ControlBootstrapJournal.phaseId(f.spec().id(),CONTROLLER_CONFIG).toString().equals(input.header().get("id"))
                &&ControlBootstrapJournal.phaseId(f.spec().id(),ControlBootstrapJournal.Phase.CONTROLLER_VERIFY).toString().equals(input.header().get("verifyId")),"BOOTSTRAP_NATIVE_INPUT_CHANGED");
        ControlBootstrapControllerCatalog.files(f.artifacts(),input.header());
        return input;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> row(Map<String,Object> body,Operation operation){
        return (Map<String,Object>)((List<?>)body.get("operations")).get(operation.ordinal());
    }
    private void save(ControlBootstrapNativeInputs.Sealed input,Map<String,Object> body){
        String raw=JSON.writeValueAsString(body);require(raw.length()<=65536,"BOOTSTRAP_CONTROLLER_RECORD_TOO_LARGE");
        store.saveJson(path(input.jobId()),Map.of("version",1,"body",store.encrypt(raw),"seal",store.fingerprint(raw)));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> load(ControlBootstrapNativeInputs.Sealed input){
        var file=store.readJson(path(input.jobId()));require(Integer.valueOf(1).equals(file.get("version")),"BOOTSTRAP_CONTROLLER_RECORD_CHANGED");
        String raw=store.decrypt(file.get("body").toString());require(store.fingerprint(raw).equals(file.get("seal")),"BOOTSTRAP_CONTROLLER_RECORD_CHANGED");
        var body=JSON.readValue(raw,Map.class);
        require(input.jobId().toString().equals(body.get("id"))&&input.binding().equals(body.get("inputBinding"))
                &&input.approvalDigest().equals(body.get("approvalDigest"))&&body.get("operations") instanceof List<?> list&&list.size()==3,"BOOTSTRAP_CONTROLLER_RECORD_CHANGED");
        boolean gap=false;
        for(var op:Operation.values()){
            var value=row(body,op);require(op.name().equals(value.get("operation"))&&Set.of("PENDING","CLAIMED","SUCCEEDED","FAILED").contains(value.get("state")),"BOOTSTRAP_CONTROLLER_RECORD_CHANGED");
            if(gap)require("PENDING".equals(value.get("state")),"BOOTSTRAP_CONTROLLER_RECORD_CHANGED");
            if(!"SUCCEEDED".equals(value.get("state")))gap=true;
        }return body;
    }
    @FunctionalInterface private interface Locked<T>{T run();}
    private <T>T locked(Locked<T> action){
        Path path=store.directory().resolve("controller-operations.lock");
        try{
            try{Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            ControlServerStore.privateFile(path,1024);
            try(var channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new ControlFailure(409,"BOOTSTRAP_CONTROLLER_RECORD_BUSY");}
                if(lock==null)throw new ControlFailure(409,"BOOTSTRAP_CONTROLLER_RECORD_BUSY");try(lock){return action.run();}
            }
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_CONTROLLER_RECORD_UNAVAILABLE");}
    }
    /** Requires a previously sealed, server-generated controller header. Missing
     * records for a claimed legacy task cannot be silently created. */
    ControlBootstrapJournal.Dispatch initialize(ControlBootstrapAdmission.Frozen f){
        var input=input(f);
        locked(()->{
            if(Files.exists(path(input.jobId()),LinkOption.NOFOLLOW_LINKS)){load(input);return null;}
            @SuppressWarnings("unchecked") var steps=(List<Map<String,Object>>)journal.job(f.spec().id()).get("steps");
            require("PENDING".equals(steps.get(CONTROLLER_CONFIG.ordinal()).get("state")),"BOOTSTRAP_CONTROLLER_RECORD_MISSING");
            var body=new LinkedHashMap<String,Object>();body.put("id",input.jobId().toString());body.put("inputBinding",input.binding());body.put("approvalDigest",input.approvalDigest());
            var rows=new ArrayList<Map<String,Object>>();for(var op:Operation.values())rows.add(new LinkedHashMap<>(Map.of("operation",op.name(),"state","PENDING")));
            body.put("operations",rows);save(input,body);return null;
        });return claims.claim(f,CONTROLLER_CONFIG);
    }
    private Permit permit(ControlBootstrapNativeInputs.Sealed input,Operation operation,boolean allowed){
        return new Permit(operation,allowed,store.fingerprint(input.jobId()+":"+input.approvalDigest()+":"+input.binding()+":"+operation),input);
    }
    Permit claim(ControlBootstrapAdmission.Frozen f,Operation operation){
        var input=input(f);
        return locked(()->{
            var body=load(input);for(var prior:Operation.values())if(prior.ordinal()<operation.ordinal())require("SUCCEEDED".equals(row(body,prior).get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
            var value=row(body,operation);require(!"FAILED".equals(value.get("state")),"BOOTSTRAP_TASK_TERMINAL");
            if(!"PENDING".equals(value.get("state")))return permit(input,operation,false);
            claims.controllerIntent(f,()->{value.put("state","CLAIMED");save(input,body);});return permit(input,operation,true);
        });
    }
    void observe(ControlBootstrapAdmission.Frozen f,Permit permit,Map<String,Object> receipt){
        var input=input(f);require(permit.token().equals(permit(input,permit.operation(),false).token()),"BOOTSTRAP_PHASE_TOKEN_MISMATCH");
        require(CONTROLLER_CONFIG.protocol.equals(receipt.get("protocol"))&&input.header().get("id").equals(receipt.get("id"))
                &&input.header().get("id").equals(receipt.get("configurationId"))&&permit.operation().name().equals(receipt.get("operation"))
                &&input.binding().equals(receipt.get("binding")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        locked(()->{
            var body=load(input);var value=row(body,permit.operation());require(!"PENDING".equals(value.get("state")),"BOOTSTRAP_PHASE_NOT_DISPATCHED");
            if("SUCCEEDED".equals(value.get("state")))return null;
            String state=Objects.toString(receipt.get("state"),"");String success=switch(permit.operation()){case PREPARE->"PREPARED";case TRANSFER->"TRANSFERRED";case STAGE->"STAGED";};
            value.put("remoteState",state.matches("[A-Z_]{1,64}")?state:"UNRECOGNIZED");value.put("receipt",receipt);
            if(success.equals(state)){
                if(permit.operation()==Operation.TRANSFER)validateTransfer(f,input,receipt);
                value.put("state","SUCCEEDED");
            }else if("FAILED".equals(state))value.put("state","FAILED");save(input,body);return null;
        });
    }
    private static void validateTransfer(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input,Map<String,Object> receipt){
        require(receipt.get("blobs") instanceof Map<?,?>,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");var blobs=(Map<?,?>)receipt.get("blobs");
        var files=ControlBootstrapControllerCatalog.files(f.artifacts(),input.header());require(blobs.keySet().equals(files.keySet()),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        for(String kind:files.keySet()){
            var artifact=files.get(kind);Object raw=blobs.get(kind);
            require(raw instanceof Map<?,?> value&&kind.equals(value.get("kind"))&&"READY".equals(value.get("state"))
                    &&(value.get("offset") instanceof Integer||value.get("offset") instanceof Long)&&((Number)value.get("offset")).longValue()==artifact.size()
                    &&artifact.sha256().equals(value.get("prefixSha256")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        }
    }
    /** A new conditional append, or an exact retry after a read-only prefix
     * check, is forced under the real authority/lease fence before network I/O.
     * Public bytes are resumable; PREPARE and STAGE are never replayed. */
    void checkpoint(ControlBootstrapAdmission.Frozen f,Permit permit,String kind,long offset,String prefix,int length,String chunkSha){
        var input=input(f);require(permit.operation()==Operation.TRANSFER&&permit.token().equals(permit(input,Operation.TRANSFER,false).token()),"BOOTSTRAP_PHASE_TOKEN_MISMATCH");
        var files=ControlBootstrapControllerCatalog.files(f.artifacts(),input.header());
        require(files.containsKey(kind)&&offset>=0&&length>0&&length<=ControlBootstrapControllerCatalog.maximumChunk(input.header())&&prefix.matches("[a-f0-9]{64}")&&chunkSha.matches("[a-f0-9]{64}"),"BOOTSTRAP_NATIVE_INVALID_INPUT");
        locked(()->{
            var body=load(input);var value=row(body,Operation.TRANSFER);require("CLAIMED".equals(value.get("state")),"BOOTSTRAP_CONTROLLER_TRANSFER_NOT_ACTIVE");
            var artifact=files.get(kind);
            require(offset<=artifact.size()-length,"BOOTSTRAP_CONTROLLER_TRANSFER_RANGE_INVALID");
            @SuppressWarnings("unchecked") var checkpoints=(Map<String,Object>)value.computeIfAbsent("checkpoints",ignored->new LinkedHashMap<>());
            var next=Map.of("offset",offset,"prefixSha256",prefix,"length",length,"chunkSha256",chunkSha);
            if(checkpoints.get(kind) instanceof Map<?,?> old){
                long before=((Number)old.get("offset")).longValue();require(offset>=before,"BOOTSTRAP_CONTROLLER_PREFIX_REGRESSED");
                require(offset!=before||ControlBootstrapNativeJson.encode(old).equals(ControlBootstrapNativeJson.encode(next)),"BOOTSTRAP_CONTROLLER_CHUNK_CHANGED");
            }
            claims.controllerIntent(f,()->{checkpoints.put(kind,next);save(input,body);});return null;
        });
    }
    void finalizeIntent(ControlBootstrapAdmission.Frozen f,Permit permit,String kind){
        var input=input(f);require(permit.operation()==Operation.TRANSFER&&permit.token().equals(permit(input,Operation.TRANSFER,false).token()),"BOOTSTRAP_PHASE_TOKEN_MISMATCH");
        require(ControlBootstrapControllerCatalog.files(f.artifacts(),input.header()).containsKey(kind),"BOOTSTRAP_NATIVE_INVALID_INPUT");
        locked(()->{
            var body=load(input);var value=row(body,Operation.TRANSFER);require("CLAIMED".equals(value.get("state")),"BOOTSTRAP_CONTROLLER_TRANSFER_NOT_ACTIVE");
            @SuppressWarnings("unchecked") var values=(Map<String,Object>)value.computeIfAbsent("finalizations",ignored->new LinkedHashMap<>());
            claims.controllerIntent(f,()->{values.put(kind,true);save(input,body);});return null;
        });
    }
    Map<String,Object> progress(ControlBootstrapAdmission.Frozen f){
        var input=input(f);return locked(()->{
            var body=load(input);var result=new LinkedHashMap<String,Object>();
            for(var op:Operation.values()){var value=row(body,op);result.put(op.name(),value.get("state"));}return result;
        });
    }
    @SuppressWarnings("unchecked") Map<String,Object> successReceipt(ControlBootstrapAdmission.Frozen f,Operation op){
        var input=input(f);return locked(()->{var value=row(load(input),op);require("SUCCEEDED".equals(value.get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");return (Map<String,Object>)value.get("receipt");});
    }
}
