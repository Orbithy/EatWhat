package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static java.nio.file.StandardOpenOption.*;

/** Acknowledged control requests are written and fsynced before an effect. Never stores credentials or stdout. */
public final class ControlAudit {
    private static final java.util.concurrent.ConcurrentMap<Path,Object> APPEND_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private final Path directory;
    private final JsonMapper json = JsonMapper.builder().build();
    public ControlAudit(Path directory) {this(directory,false);}
    public ControlAudit(Path directory,boolean sharedStateRequired) {
        try {
            if (directory==null || !directory.isAbsolute() || Files.isSymbolicLink(directory)) throw new Exception();
            if(sharedStateRequired) {
                // This check precedes every directory/file creation. A missing mount must
                // never become a fresh local state directory with an independent AES key.
                if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)
                        || !java.util.Set.of("nfs","nfs4").contains(Files.getFileStore(directory).type()))throw new Exception();
                if(!Files.isRegularFile(directory.resolve("controller-state-id"),LinkOption.NOFOLLOW_LINKS)
                        ||!Files.isRegularFile(directory.resolve("servers.key"),LinkOption.NOFOLLOW_LINKS))throw new Exception();
            }
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (!Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"))) throw new Exception();
            this.directory=directory.toRealPath();
        } catch(Exception e) { throw new ControlFailure(503,"PRIVATE_STATE_DIRECTORY_REQUIRED"); }
    }
    public Path directory() { return directory; }
    public synchronized void append(UUID id,String actor,String action,String target,String outcome) {
        append(id,actor,action,target,outcome,Map.of());
    }
    public void append(UUID id,String actor,String action,String target,String outcome,Map<String,Object> details) {
        synchronized(APPEND_LOCKS.computeIfAbsent(directory,ignored->new Object())) {
        try {
            var p=directory.resolve("audit.jsonl");
            if(Files.isSymbolicLink(p)) throw new Exception();
            if(!Files.exists(p))try{Files.createFile(p,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(p).equals(PosixFilePermissions.fromString("rw-------")))throw new Exception();
            byte[] b=(json.writeValueAsString(Map.of("requestId",id.toString(),"actor",actor,"action",action,
                    "target",target,"outcome",outcome,"at",Instant.now().toString(),"details",details))+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // NFS append alone is not an inter-host record boundary. Serialize the complete
            // durable record with a POSIX file lock, in addition to the in-process lock.
            try(var f=FileChannel.open(p,WRITE,APPEND,LinkOption.NOFOLLOW_LINKS);var lock=f.lock()) { var buffer=ByteBuffer.wrap(b); while(buffer.hasRemaining()) f.write(buffer); f.force(true); }
        } catch(Exception e) { throw new ControlFailure(503,"AUDIT_UNAVAILABLE"); }
        }
    }
    public static String token(Path file) {
        try {
            if(file==null || !file.isAbsolute() || Files.isSymbolicLink(file) || Files.size(file)>256
                    || !Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))) throw new Exception();
            String token=Files.readString(file).strip();
            if(!token.matches("[A-Za-z0-9_-]{43,128}")) throw new Exception();
            return token;
        } catch(Exception e) { throw new ControlFailure(503,"PRIVATE_NODE_TOKEN_REQUIRED"); }
    }
}
