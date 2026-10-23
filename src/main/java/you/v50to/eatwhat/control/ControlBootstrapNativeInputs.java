package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.Supplier;

/** Private immutable native inputs, forced before the journal's dispatch intent.
 * A restarted observer executes these exact saved bytes, including embedded
 * policies. It never substitutes the current release's installer code. */
public final class ControlBootstrapNativeInputs {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlServerStore store;
    public ControlBootstrapNativeInputs(ControlServerStore store){this.store=store;}
    public record Prepared(Map<String,Object> header,String agent) {
        @Override public String toString(){return "BootstrapNativePrepared[redacted]";}
    }
    public record Sealed(UUID jobId,ControlBootstrapJournal.Phase phase,String approvalDigest,UUID serverId,long revision,
                         String routeBinding,Map<String,Object> header,String agent,String binding) {
        @Override public String toString(){return "BootstrapNativeInput[redacted]";}
    }
    private Path path(UUID id,ControlBootstrapJournal.Phase phase,UUID server){
        // The dependency task uses the same native UUID on two separately
        // pinned hosts. KEY/CONNECT must share the target's immutable input.
        String suffix=phase==ControlBootstrapJournal.Phase.DEPENDENCY_LINK?"-"+server:"";
        return store.directory().resolve("controller-native-"+id+"-"+phase.name()+suffix+".json");
    }
    private static void require(boolean ok){if(!ok)throw new ControlFailure(409,"BOOTSTRAP_NATIVE_INPUT_CHANGED");}
    boolean exists(UUID id,ControlBootstrapJournal.Phase phase,UUID server){return Files.exists(path(id,phase,server),LinkOption.NOFOLLOW_LINKS);}
    /** The caller must have authenticated the accepted admission capsule. The
     * supplier runs only for an unclaimed phase with no previous input. */
    Sealed prepare(UUID id,ControlBootstrapJournal.Phase phase,String approval,UUID server,long revision,String route,
                   boolean pending,Supplier<Prepared> supplier){
        return prepare(id,phase,approval,server,revision,route,pending,supplier,input->{});
    }
    /** Called only for newly forced inputs. A crash after the input write does
     * not replay initialization or recreate a missing child operation ledger. */
    Sealed prepare(UUID id,ControlBootstrapJournal.Phase phase,String approval,UUID server,long revision,String route,
                   boolean pending,Supplier<Prepared> supplier,java.util.function.Consumer<Sealed> initialize){
        Path lockPath=store.directory().resolve("controller-native-input.lock");
        try{
            try{Files.createFile(lockPath,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            ControlServerStore.privateFile(lockPath,1024);
            try(var channel=FileChannel.open(lockPath,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new ControlFailure(409,"BOOTSTRAP_NATIVE_INPUT_BUSY");}
                if(lock==null)throw new ControlFailure(409,"BOOTSTRAP_NATIVE_INPUT_BUSY");
                try(lock){
                    if(Files.exists(path(id,phase,server),LinkOption.NOFOLLOW_LINKS))return load(id,phase,approval,server,revision,route);
                    if(!pending)throw new ControlFailure(503,"BOOTSTRAP_NATIVE_INPUT_MISSING");
                    Prepared prepared=supplier.get();
                    // Round trip removes mutable aliases held by the preparer.
                    @SuppressWarnings("unchecked") Map<String,Object> header=JSON.readValue(ControlBootstrapNativeJson.encode(prepared.header()),Map.class);
                    require(!header.containsKey("agent")&&approval.equals(header.get("approvalDigest"))
                            &&ControlBootstrapNativeJson.sha(prepared.agent()).equals(header.get("agentSha256")));
                    require(prepared.agent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=524288);
                    var input=new Sealed(id,phase,approval,server,revision,route,header,prepared.agent(),ControlBootstrapNativeJson.digest(header));
                    String body=JSON.writeValueAsString(input);
                    store.saveJson(path(id,phase,server),Map.of("version",1,"body",store.encrypt(body),"seal",store.fingerprint(body)));
                    initialize.accept(input);
                    return load(id,phase,approval,server,revision,route);
                }
            }
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_NATIVE_INPUT_UNAVAILABLE");}
    }
    Sealed load(UUID id,ControlBootstrapJournal.Phase phase,String approval,UUID server,long revision,String route){
        try{
            var file=store.readJson(path(id,phase,server));require(Integer.valueOf(1).equals(file.get("version")));
            String body=store.decrypt(file.get("body").toString());require(store.fingerprint(body).equals(file.get("seal")));
            var input=JSON.readValue(body,Sealed.class);
            require(id.equals(input.jobId())&&phase==input.phase()&&approval.equals(input.approvalDigest())
                    &&server.equals(input.serverId())&&revision==input.revision()&&route.equals(input.routeBinding())
                    &&approval.equals(input.header().get("approvalDigest"))&&!input.header().containsKey("agent")
                    &&ControlBootstrapNativeJson.sha(input.agent()).equals(input.header().get("agentSha256"))
                    &&ControlBootstrapNativeJson.digest(input.header()).equals(input.binding()));
            // Defensive deep copy on each load; never publish through HTTP.
            return input;
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_NATIVE_INPUT_UNAVAILABLE");}
    }
}
