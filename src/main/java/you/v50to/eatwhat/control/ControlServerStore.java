package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;

/** Shared, revisioned server registry. Credentials and installation payloads are encrypted at rest. */
public final class ControlServerStore {
    private final Path root; private final byte[] key; private final JsonMapper json=JsonMapper.builder().build();
    public ControlServerStore(ControlAudit audit){
        root=audit.directory();Path path=root.resolve("servers.key");
        synchronized(ControlServerStore.class){try{
            if(!Files.exists(path)){
                if(Files.exists(root.resolve("servers.json")))throw new Exception();
                byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
                Path tmp=Files.createTempFile(root,"servers-key-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try{writeForced(tmp,bytes);try{Files.createLink(path,tmp);}catch(FileAlreadyExistsException ignored){}sync();}finally{Files.deleteIfExists(tmp);}
            }
            privateFile(path,32);key=Files.readAllBytes(path);if(key.length!=32)throw new Exception();
        }catch(Exception e){throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}}
    }
    public Path directory(){return root;}
    public String fingerprint(String value){try{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}}
    public String encrypt(String value){try{
        byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD("eatwhat-control-servers-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));byte[] bytes=cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12+bytes.length).put(nonce).put(bytes).array());
    }catch(Exception e){throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}}
    public String decrypt(String value){try{
        byte[] bytes=Base64.getDecoder().decode(value);if(bytes.length<28)throw new Exception();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));cipher.updateAAD("eatwhat-control-servers-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));return new String(cipher.doFinal(bytes,12,bytes.length-12),java.nio.charset.StandardCharsets.UTF_8);
    }catch(Exception e){throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}}
    public synchronized <T>T mutate(Function<Map<String,Object>,T> operation){
        Path lockPath=root.resolve("servers.lock");
        try{
            if(!Files.exists(lockPath))try{Files.createFile(lockPath,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            privateFile(lockPath,1024);
            try(var channel=FileChannel.open(lockPath,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new ControlFailure(409,"SERVER_REGISTRY_BUSY");}
                if(lock==null)throw new ControlFailure(409,"SERVER_REGISTRY_BUSY");
                try(lock){var data=read();T result=operation.apply(data);saveJson(root.resolve("servers.json"),data);return result;}
            }
        }catch(ControlFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}
    }
    @SuppressWarnings("unchecked") public Map<String,Object> read(){Path p=root.resolve("servers.json");if(!Files.exists(p))return new LinkedHashMap<>(Map.of("servers",new LinkedHashMap<String,Object>(),"requests",new LinkedHashMap<String,Object>()));return readJson(p);}
    @SuppressWarnings("unchecked") public Map<String,Object> server(UUID id){var servers=(Map<String,Object>)read().get("servers");Object s=servers.get(id.toString());if(!(s instanceof Map))throw new ControlFailure(404,"SERVER_NOT_FOUND");return (Map<String,Object>)s;}
    @SuppressWarnings("unchecked") public Map<String,Object> readJson(Path p){try{privateFile(p,4*1024*1024);return json.readValue(Files.readString(p),LinkedHashMap.class);}catch(Exception e){throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}}
    public void saveJson(Path p,Object value){try{atomic(p,json.writeValueAsBytes(value));}catch(ControlFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}}
    public void atomic(Path p,byte[] bytes){try{
        if(!p.getParent().equals(root)||Files.isSymbolicLink(p))throw new Exception();Path tmp=Files.createTempFile(root,"server-state-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{writeForced(tmp,bytes);Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync();}finally{Files.deleteIfExists(tmp);}
    }catch(Exception e){throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}}
    private static void writeForced(Path p,byte[] bytes)throws Exception{try(var f=FileChannel.open(p,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())f.write(b);f.force(true);}}
    private void sync()throws Exception{try(var c=FileChannel.open(root,StandardOpenOption.READ)){c.force(true);}}
    static void privateFile(Path p,long max)throws Exception{if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>max||!Files.getPosixFilePermissions(p).equals(PosixFilePermissions.fromString("rw-------")))throw new Exception();}
}
