package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import you.v50to.eatwhat.config.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static java.nio.file.StandardOpenOption.*;

/** A durable, idempotent, explicit write/read/delete probe. Restart only reconciles cleanup, never repeats PUT. */
public final class ControlStorageCheck implements AutoCloseable {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> TERMINAL=Set.of("PASSED","FAILED");
    private static final Set<String> PUBLIC=Set.of("requestId","state","phase","code","startedAt","updatedAt","writeVerified","readVerified","cleanupVerified");
    private final Path directory;private final S3Client client;private final String bucket,binding,instance;private final ControlAudit audit;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"control-storage-check");t.setDaemon(true);return t;});
    private Map<String,Object> current=Map.of("state","NOT_CHECKED");private boolean busy,closed;
    public ControlStorageCheck(ControlAudit audit,SiteProperties site,S3StorageProperties props){this(audit,site,props,ControlHealthChecks.client(props));}
    ControlStorageCheck(ControlAudit audit,SiteProperties site,S3StorageProperties props,S3Client client){
        this.audit=audit;this.client=client;this.bucket=props.getBucket();this.instance=String.valueOf(site.getInstanceId());this.binding=sha((props.getEndpoint()+"|"+props.getRegion()+"|"+bucket).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        directory=audit.directory().resolve("storage-checks");
        try{
            if(Files.isSymbolicLink(directory))throw new Exception();Files.createDirectories(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if(!Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------")))throw new Exception();
            var records=records();if(!records.isEmpty())current=read(records.getLast());
            var pending=records.stream().map(this::read).filter(j->!TERMINAL.contains(j.get("state"))).toList();
            if(!pending.isEmpty()){busy=true;executor.execute(()->{try{for(var j:pending)run(j,false);}finally{synchronized(this){busy=false;}}});}
        }catch(Exception e){throw new ControlFailure(503,"STORAGE_CHECK_STATE_UNAVAILABLE");}
    }
    private List<Path> records(){try(var paths=Files.list(directory)){return paths.filter(p->p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).sorted(Comparator.comparing(p->p.toFile().lastModified())).toList();}catch(Exception e){throw new ControlFailure(503,"STORAGE_CHECK_STATE_UNAVAILABLE");}}
    private Path path(UUID id){return directory.resolve(id+".json");}
    private Map<String,Object> read(Path p){try{if(Files.isSymbolicLink(p)||Files.size(p)>8192||!Files.getPosixFilePermissions(p).equals(PosixFilePermissions.fromString("rw-------")))throw new Exception();return new LinkedHashMap<>(JSON.readValue(Files.readAllBytes(p),Map.class));}catch(Exception e){throw new ControlFailure(503,"STORAGE_CHECK_STATE_UNAVAILABLE");}}
    private synchronized void save(Map<String,Object> j){
        j.put("updatedAt",Instant.now().toString());Path p=path(UUID.fromString(j.get("requestId").toString())),temp=directory.resolve("."+UUID.randomUUID()+".tmp");
        try{Files.createFile(temp,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try(var f=FileChannel.open(temp,WRITE,LinkOption.NOFOLLOW_LINKS)){var b=ByteBuffer.wrap(JSON.writeValueAsBytes(j));while(b.hasRemaining())f.write(b);f.force(true);}Files.move(temp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);try(var d=FileChannel.open(directory,READ)){d.force(true);}current=new LinkedHashMap<>(j);}catch(Exception e){throw new ControlFailure(503,"STORAGE_CHECK_STATE_UNAVAILABLE");}finally{try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
    public synchronized Map<String,Object> snapshot(){var result=new LinkedHashMap<String,Object>();current.forEach((k,v)->{if(PUBLIC.contains(k))result.put(k,v);});return result;}
    public synchronized Map<String,Object> start(String actor,UUID id){
        if(id==null)throw new ControlFailure(400,"REQUEST_ID_REQUIRED");if(closed)throw new ControlFailure(503,"STORAGE_CHECK_STOPPING");
        Path p=path(id);Map<String,Object> j;
        if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){j=read(p);if(TERMINAL.contains(j.get("state"))||busy){return publicView(j);} }
        else{
            if(busy||records().stream().map(this::read).anyMatch(r->!TERMINAL.contains(r.get("state"))))throw new ControlFailure(409,"STORAGE_CHECK_CLEANUP_REQUIRED");
            if(client==null||bucket==null||bucket.isBlank())throw new ControlFailure(409,"STORAGE_NOT_CONFIGURED");
            if(records().size()>=200)throw new ControlFailure(409,"STORAGE_CHECK_HISTORY_FULL");
            j=new LinkedHashMap<>();j.put("requestId",id.toString());j.put("state","RUNNING");j.put("phase","PREPARED");j.put("code","CHECK_IN_PROGRESS");j.put("startedAt",Instant.now().toString());j.put("instanceId",instance);j.put("binding",binding);j.put("key",".__eatwhat_control_health/"+instance+"/"+id);j.put("marker",UUID.randomUUID().toString());
            byte[] payload=new byte[256];new SecureRandom().nextBytes(payload);j.put("payload",Base64.getEncoder().encodeToString(payload));j.put("checksum",sha(payload));j.put("writeVerified",false);j.put("readVerified",false);j.put("cleanupVerified",false);
            audit.append(id,actor,"STORAGE_WRITE_CHECK",instance,"REQUESTED");save(j);
        }
        boolean fresh="PREPARED".equals(j.get("phase"));busy=true;var task=j;
        try{executor.execute(()->{try{run(task,fresh);}finally{synchronized(this){busy=false;}}});}catch(RejectedExecutionException e){busy=false;throw new ControlFailure(503,"STORAGE_CHECK_STOPPING");}
        return publicView(j);
    }
    private Map<String,Object> publicView(Map<String,Object> j){var r=new LinkedHashMap<String,Object>();j.forEach((k,v)->{if(PUBLIC.contains(k))r.put(k,v);});return r;}
    private void run(Map<String,Object> j,boolean fresh){
        Path guard=directory.resolve("probe.lock");
        try{
            if(!Files.exists(guard,LinkOption.NOFOLLOW_LINKS))try{Files.createFile(guard,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            try(var f=FileChannel.open(guard,WRITE,LinkOption.NOFOLLOW_LINKS);var lock=f.tryLock()){
                if(lock==null){j.put("state","CLEANUP_PENDING");j.put("code","STORAGE_CHECK_LOCKED");save(j);return;}
                if(!binding.equals(j.get("binding"))||!instance.equals(j.get("instanceId"))||!Objects.equals(j.get("key"),".__eatwhat_control_health/"+instance+"/"+UUID.fromString(j.get("requestId").toString()))){j.put("state","CLEANUP_PENDING");j.put("code","STORAGE_CHECK_CONFIGURATION_CHANGED");save(j);return;}
                var latest=read(path(UUID.fromString(j.get("requestId").toString())));
                if(TERMINAL.contains(latest.get("state"))){synchronized(this){current=latest;}return;}
                // Another process may already have crossed the write boundary while this worker waited.
                fresh=fresh&&"PREPARED".equals(latest.get("phase"));j.clear();j.putAll(latest);
                String failure=null;
                if(fresh){
                    j.put("phase","WRITE");save(j); // durable uncertainty boundary, before any PUT
                    try{
                        var put=PutObjectRequest.builder().bucket(bucket).key(j.get("key").toString()).contentType("application/octet-stream").metadata(Map.of("eatwhat-probe",j.get("marker").toString(),"sha256",j.get("checksum").toString())).overrideConfiguration(c->c.putHeader("If-None-Match","*")).build();
                        client.putObject(put,RequestBody.fromBytes(Base64.getDecoder().decode(j.get("payload").toString())));j.put("writeVerified",true);j.put("phase","READ");save(j);
                        try(var stream=client.getObject(GetObjectRequest.builder().bucket(bucket).key(j.get("key").toString()).build())){byte[] bytes=stream.readNBytes(257);if(bytes.length!=256||!sha(bytes).equals(j.get("checksum")))throw new ReadMismatch();j.put("readVerified",true);}
                    }catch(Exception e){failure=e instanceof ReadMismatch?"STORAGE_READ_MISMATCH":ControlHealthChecks.storageFailure(e).code();}
                }
                j.put("phase","CLEANUP");j.put("state","CLEANUP_PENDING");j.put("code",failure==null?"STORAGE_CLEANUP_IN_PROGRESS":failure);save(j);
                try{cleanup(j);j.put("cleanupVerified",true);j.put("state",Boolean.TRUE.equals(j.get("writeVerified"))&&Boolean.TRUE.equals(j.get("readVerified"))?"PASSED":"FAILED");j.put("phase","COMPLETE");j.put("code","PASSED".equals(j.get("state"))?"OK":failure==null?"STORAGE_CHECK_INTERRUPTED_CLEANED":failure);save(j);audit.append(UUID.fromString(j.get("requestId").toString()),"storage-check","STORAGE_WRITE_CHECK",instance,j.get("state").toString());}
                catch(Exception e){j.put("state","CLEANUP_PENDING");j.put("code",e instanceof OwnershipFailure?"STORAGE_PROBE_OWNERSHIP_MISMATCH":"STORAGE_PROBE_CLEANUP_FAILED");save(j);}
            }
        }catch(Exception e){synchronized(this){current=Map.of("requestId",j.get("requestId"),"state","CLEANUP_PENDING","phase","CLEANUP","code","STORAGE_CHECK_STATE_UNAVAILABLE","updatedAt",Instant.now().toString());}}
    }
    private void cleanup(Map<String,Object> j){
        if(client==null)throw new IllegalStateException();String key=j.get("key").toString();HeadObjectResponse head;
        try{head=client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());}catch(S3Exception e){if(e.statusCode()==404)return;throw e;}
        if(!j.get("marker").equals(head.metadata().get("eatwhat-probe"))||!j.get("checksum").equals(head.metadata().get("sha256")))throw new OwnershipFailure();
        String version=head.versionId();client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).versionId(version).build());
        try{client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).versionId(version).build());throw new IllegalStateException();}catch(S3Exception e){if(e.statusCode()!=404)throw e;}
    }
    private static final class ReadMismatch extends RuntimeException{}
    private static final class OwnershipFailure extends RuntimeException{}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException();}}
    @Override public void close(){
        synchronized(this){closed=true;executor.shutdown();}
        try{if(executor.awaitTermination(15,TimeUnit.SECONDS)){if(client!=null)client.close();}else executor.shutdownNow();}catch(InterruptedException e){executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
