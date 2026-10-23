package you.v50to.eatwhat.control;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;
import static you.v50to.eatwhat.control.ControlBootstrapControllerOperations.Operation.*;

/** One bounded controller step per invocation. The server factory seals the
 * original target bundle before the first claim; no browser property maps.
 * A complete workflow still needs scheduler and business/membership drivers. */
public final class ControlBootstrapControllerPhases {
    private final ControlBootstrapNativePhases nativePhases;private final ControlBootstrapControllerOperations operations;
    private final ControlBootstrapPhaseClaims claims;private final ControlBootstrapJournal journal;private final ControlBootstrapNativeInputs inputs;
    private final ControlBootstrapControllerFactory factory;
    public ControlBootstrapControllerPhases(ControlBootstrapNativePhases nativePhases,ControlBootstrapControllerOperations operations,
            ControlBootstrapPhaseClaims claims,ControlBootstrapJournal journal,ControlBootstrapNativeInputs inputs,ControlBootstrapControllerFactory factory){
        this.nativePhases=nativePhases;this.operations=operations;this.claims=claims;this.journal=journal;this.inputs=inputs;this.factory=factory;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private ControlBootstrapNativeInputs.Sealed input(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);String approval=journal.job(f.spec().id()).get("requestHash").toString();
        return inputs.load(f.spec().id(),CONTROLLER_CONFIG,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute());
    }
    private Map<String,Object> call(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input,List<String> args,Map<String,Object> data){
        return nativePhases.call(nativePhases.server(f,CONTROLLER_CONFIG),input.agent(),args,data);
    }
    private Map<String,Object> result(ControlBootstrapAdmission.Frozen f){
        var result=new LinkedHashMap<>(journal.job(f.spec().id()));result.put("controllerOperations",operations.progress(f));return result;
    }
    public Map<String,Object> configure(ControlBootstrapAdmission.Frozen f){
        nativePhases.server(f,CONTROLLER_CONFIG);
        factory.ensure(f);
        var parent=operations.initialize(f);var progress=operations.progress(f);
        if(!"SUCCEEDED".equals(progress.get("PREPARE"))){
            var permit=operations.claim(f,PREPARE);var input=permit.input();var data=new LinkedHashMap<>(input.header());
            if(permit.dispatchAllowed())data.put("agent",input.agent());
            var receipt=call(f,input,List.of(permit.dispatchAllowed()?"prepare":"prepare-status"),data);
            operations.observe(f,permit,receipt);return result(f);
        }
        if(!"SUCCEEDED".equals(progress.get("TRANSFER"))){
            try{transfer(f);}catch(ControlFailure failure){if("BOOTSTRAP_ARTIFACT_CHANGED".equals(failure.code))claims.controllerArtifactDrift(f);throw failure;}
            return result(f);
        }
        if(!"SUCCEEDED".equals(progress.get("STAGE"))){
            var permit=operations.claim(f,STAGE);var input=permit.input();
            var receipt=call(f,input,List.of(permit.dispatchAllowed()?"dispatch":"status","STAGE"),input.header());
            operations.observe(f,permit,receipt);journal.observe(f.spec().id(),CONTROLLER_CONFIG,parent.claimToken(),receipt);return result(f);
        }
        // Reconcile a crash between saving the native receipt and advancing the
        // parent journal. The original worker is not dispatched again.
        journal.observe(f.spec().id(),CONTROLLER_CONFIG,parent.claimToken(),operations.successReceipt(f,STAGE));return result(f);
    }
    public Map<String,Object> verify(ControlBootstrapAdmission.Frozen f){
        nativePhases.server(f,CONTROLLER_VERIFY);operations.successReceipt(f,STAGE);var input=input(f);
        var permit=claims.claim(f,CONTROLLER_VERIFY);
        var receipt=call(f,input,List.of(permit.dispatchAllowed()?"dispatch":"status","VERIFY"),input.header());
        require(CONTROLLER_VERIFY.protocol.equals(receipt.get("protocol"))&&permit.id().toString().equals(receipt.get("id"))
                &&input.header().get("id").equals(receipt.get("configurationId"))&&"VERIFY".equals(receipt.get("operation"))
                &&input.binding().equals(receipt.get("binding")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        return journal.observe(f.spec().id(),CONTROLLER_VERIFY,permit.claimToken(),receipt);
    }
    private void transfer(ControlBootstrapAdmission.Frozen f){
        var permit=operations.claim(f,TRANSFER);var input=permit.input();var blobs=new LinkedHashMap<String,Object>();String incomplete=null;
        var files=ControlBootstrapControllerCatalog.files(f.artifacts(),input.header());
        for(String kind:files.keySet()){
            var status=call(f,input,List.of("blob-status",kind),input.header());validateBlob(kind,files.get(kind),status);blobs.put(kind,status);
            if(!"READY".equals(status.get("state"))&&incomplete==null)incomplete=kind;
        }
        if(incomplete==null){
            operations.observe(f,permit,Map.of("protocol",CONTROLLER_CONFIG.protocol,"id",input.header().get("id"),"configurationId",input.header().get("id"),
                    "binding",input.binding(),"operation","TRANSFER","state","TRANSFERRED","blobs",blobs));return;
        }
        String kind=incomplete;
        @SuppressWarnings("unchecked") var status=(Map<String,Object>)blobs.get(kind);
        if("COMPLETE_UNSEALED".equals(status.get("state"))){
            operations.finalizeIntent(f,permit,kind);var result=call(f,input,List.of("finalize",kind),input.header());validateBlob(kind,files.get(kind),result);
            require("READY".equals(result.get("state")),"BOOTSTRAP_CONTROLLER_TRANSFER_UNCONFIRMED");return;
        }
        long offset=((Number)status.get("offset")).longValue();String prefix=status.get("prefixSha256").toString();
        int overhead=ControlBootstrapNativeJson.encode(input.header()).length()+4096;
        boolean streaming=ControlBootstrapControllerCatalog.streaming(input.header());
        int capacity=streaming?ControlBootstrapControllerCatalog.maximumChunk(input.header()):Math.min(512*1024,(2*1024*1024-overhead)*3/4);
        require(capacity>0&&overhead<2*1024*1024,"BOOTSTRAP_NATIVE_INPUT_TOO_LARGE");
        var captured=chunk(files.get(kind),offset,prefix,capacity);byte[] chunk=captured.bytes();
        String chunkSha;
        try{chunkSha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chunk));}
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_ARTIFACT_UNAVAILABLE");}
        operations.checkpoint(f,permit,kind,offset,prefix,chunk.length,chunkSha);
        Map<String,Object> next;
        if(streaming){
            byte[] request=ControlBootstrapNativeJson.encode(Map.of("header",input.header(),"offset",offset,"prefixSha256",prefix,"length",chunk.length)).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            next=nativePhases.upload(nativePhases.server(f,CONTROLLER_CONFIG),input.agent(),List.of("put-stream",kind),out->{out.write(request);out.write('\n');out.write(chunk);});
        }else{
            var request=Map.<String,Object>of("header",input.header(),"offset",offset,"prefixSha256",prefix,"bytes",Base64.getEncoder().encodeToString(chunk));next=call(f,input,List.of("put",kind),request);
        }
        validateBlob(kind,files.get(kind),next);
        require(((Number)next.get("offset")).longValue()==offset+chunk.length&&captured.prefixAfter().equals(next.get("prefixSha256")),"BOOTSTRAP_CONTROLLER_TRANSFER_UNCONFIRMED");
    }
    private static void validateBlob(String kind,ControlBootstrapArtifacts.File artifact,Map<String,Object> value){
        Object raw=value.get("offset");
        require(kind.equals(value.get("kind"))&&(raw instanceof Integer||raw instanceof Long)&&value.get("prefixSha256") instanceof String hash&&hash.matches("[a-f0-9]{64}"),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        long offset=((Number)raw).longValue();String state=Objects.toString(value.get("state"),"");
        require(offset>=0&&offset<=artifact.size()&&Set.of("PENDING","PARTIAL","READY","COMPLETE_UNSEALED").contains(state),"BOOTSTRAP_CONTROLLER_BLOB_RECONCILIATION_REQUIRED");
        if(Set.of("READY","COMPLETE_UNSEALED").contains(state))require(offset==artifact.size()&&artifact.sha256().equals(value.get("prefixSha256")),"BOOTSTRAP_CONTROLLER_PREFIX_MISMATCH");
        else require(offset<artifact.size()&&(!"PENDING".equals(state)||offset==0),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
    }
    /** Hash and copy the bounded chunk in the same scan. Never read different
     * bytes after hashing a file path, follow a symlink, truncate a remote part,
     * or assume an acknowledged offset without matching its exact local prefix. */
    private record Chunk(byte[] bytes,String prefixAfter){}
    private static Chunk chunk(ControlBootstrapArtifacts.File artifact,long offset,String expectedPrefix,int maximum){
        try{
            Path path=Path.of(artifact.path());for(Path parent=path;parent!=null;parent=parent.getParent())require(!Files.isSymbolicLink(parent),"BOOTSTRAP_ARTIFACT_CHANGED");
            require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)==artifact.size(),"BOOTSTRAP_ARTIFACT_CHANGED");
            var full=MessageDigest.getInstance("SHA-256");var prefix=MessageDigest.getInstance("SHA-256");var after=MessageDigest.getInstance("SHA-256");var bytes=new java.io.ByteArrayOutputStream();long position=0;
            try(var file=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                var buffer=ByteBuffer.allocate(64*1024);while(file.read(buffer)>=0){
                    if(buffer.position()==0)continue;byte[] data=buffer.array();int n=buffer.position();full.update(data,0,n);
                    int leading=(int)Math.min(n,Math.max(0,offset-position));if(leading>0)prefix.update(data,0,leading);
                    int following=(int)Math.min(n,Math.max(0,offset+maximum-position));if(following>0)after.update(data,0,following);
                    int start=(int)Math.min(n,Math.max(0,offset-position));int end=(int)Math.min(n,Math.max(0,offset+maximum-position));
                    if(end>start)bytes.write(data,start,end-start);position+=n;require(position<=artifact.size(),"BOOTSTRAP_ARTIFACT_CHANGED");buffer.clear();
                }
            }
            require(position==artifact.size()&&HexFormat.of().formatHex(full.digest()).equals(artifact.sha256()),"BOOTSTRAP_ARTIFACT_CHANGED");
            require(HexFormat.of().formatHex(prefix.digest()).equals(expectedPrefix),"BOOTSTRAP_CONTROLLER_PREFIX_MISMATCH");
            require(bytes.size()>0&&bytes.size()<=maximum,"BOOTSTRAP_CONTROLLER_TRANSFER_RANGE_INVALID");return new Chunk(bytes.toByteArray(),HexFormat.of().formatHex(after.digest()));
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_ARTIFACT_UNAVAILABLE");}
    }
}
