package you.v50to.eatwhat.cluster;

import tools.jackson.databind.json.JsonMapper;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/** Server-owned, additive publication of one reserved business profile.
 * No HTTP/SSH API or authorization grant: the composite driver must seal this
 * plan and supply its actual authority fence around the durable intent/effect.
 * A retained partial publication is observed, never resumed or overwritten.
 */
public final class ClusterProfilePublication {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private ClusterProfilePublication(){}
    public record Plan(UUID bootstrapId,UUID serverId,ClusterMember member,String profilesFile,String beforeBase64,String afterBase64,
                       Map<String,String> beforeManifest,Map<String,String> afterManifest,Map<String,String> additionsBase64,String binding){
        public Plan{beforeManifest=Map.copyOf(beforeManifest);afterManifest=Map.copyOf(afterManifest);additionsBase64=Map.copyOf(additionsBase64);}
        @Override public String toString(){return "ClusterProfilePublicationPlan[redacted]";}
        public String beforeBinding(){return digest(beforeManifest);}
        public String afterBinding(){return digest(afterManifest);}
    }
    private static void require(boolean ok,String code){if(!ok)throw new ClusterFailure(409,code);}
    static String sha(byte[] raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception failure){throw new IllegalStateException();}}
    private static Object ordered(Object value){if(value instanceof Map<?,?> m){var next=new TreeMap<String,Object>();m.forEach((k,v)->next.put(k.toString(),ordered(v)));return next;}if(value instanceof List<?> a)return a.stream().map(ClusterProfilePublication::ordered).toList();return value;}
    static String digest(Object value){return sha(JSON.writeValueAsBytes(ordered(JSON.readValue(JSON.writeValueAsBytes(value),Object.class))));}
    private static UUID phase(UUID bootstrap,String name){return UUID.nameUUIDFromBytes((bootstrap+":"+name).getBytes(StandardCharsets.UTF_8));}
    private static String tokenName(UUID server){return "bootstrap-member-"+server+".token";}
    private static String caName(UUID server){return "bootstrap-member-"+server+".ca.pem";}
    private static Path profilePath(String value){
        Path p=Path.of(value);require(p.isAbsolute()&&p.normalize().equals(p)&&p.toString().length()<=1024&&p.getFileName().toString().equals("cluster-profiles.json")
                &&p.getParent().getFileName().toString().equals("config"),"PROFILE_PUBLICATION_PATH_REQUIRED");return p;
    }
    static byte[] privateBytes(Path path,int maximum)throws Exception{
        for(Path p=path;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p),"PROFILE_PUBLICATION_UNSAFE_FILE");
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)>0&&Files.size(path)<=maximum
                &&Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------")),"PROFILE_PUBLICATION_UNSAFE_FILE");
        long owner=((Number)Files.getAttribute(path,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue(),parent=((Number)Files.getAttribute(path.getParent(),"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();
        require(owner==0||owner==parent,"PROFILE_PUBLICATION_UNSAFE_FILE");
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes(maximum+1);require(bytes.length<=maximum,"PROFILE_PUBLICATION_UNSAFE_FILE");return bytes;}
    }
    private static String reference(Path profiles,Object value){
        require(value instanceof String,"PROFILE_PUBLICATION_BASELINE_REQUIRED");Path p=Path.of((String)value);
        require(p.isAbsolute()&&p.normalize().equals(p)&&profiles.getParent().equals(p.getParent())&&p.getFileName().toString().matches("[a-z0-9][a-z0-9_.-]{0,95}")
                &&!p.equals(profiles)&&!p.getFileName().toString().equals("application.properties"),"PROFILE_PUBLICATION_BASELINE_REQUIRED");return p.toString();
    }
    @SuppressWarnings("unchecked") static Map<String,Object> profiles(byte[] raw){
        try{require(raw.length>0&&raw.length<=262144,"PROFILE_PUBLICATION_BASELINE_REQUIRED");var tree=JSON.readTree(raw);
            require(tree.isObject()&&!tree.isEmpty()&&tree.size()<=16,"PROFILE_PUBLICATION_BASELINE_REQUIRED");return JSON.readValue(raw,LinkedHashMap.class);
        }catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new ClusterFailure(409,"PROFILE_PUBLICATION_BASELINE_REQUIRED");}
    }
    /** Inputs are the captured private profile bytes/hashes, not live replacement material. */
    public static Plan create(UUID bootstrap,UUID server,ClusterMember member,String targetAddress,Path profiles,byte[] before,
                              Map<String,String> capturedHashes,String token,String ca){
        require(bootstrap!=null&&server!=null&&!bootstrap.equals(new UUID(0,0))&&!server.equals(new UUID(0,0)),"PROFILE_PUBLICATION_IDENTITY_REQUIRED");profilePath(profiles.toString());
        require(member!=null&&member.siteId().equals("server-"+server.toString().replace("-","").substring(0,12))&&member.instanceId().equals(phase(bootstrap,"BUSINESS_INSTANCE"))
                &&member.mode().equals("EXTERNAL")&&member.allocationSlot()>0,"PROFILE_PUBLICATION_IDENTITY_REQUIRED");
        try{String[] octets=targetAddress.split("\\.",-1);require(octets.length==4,"PROFILE_PUBLICATION_PRIVATE_ROUTE_REQUIRED");int[] n=new int[4];
            for(int i=0;i<4;i++){n[i]=Integer.parseInt(octets[i]);require(n[i]>=0&&n[i]<=255&&octets[i].equals(Integer.toString(n[i])),"PROFILE_PUBLICATION_PRIVATE_ROUTE_REQUIRED");}
            require(n[0]==10||n[0]==172&&n[1]>=16&&n[1]<=31||n[0]==192&&n[1]==168,"PROFILE_PUBLICATION_PRIVATE_ROUTE_REQUIRED");
        }catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new ClusterFailure(409,"PROFILE_PUBLICATION_PRIVATE_ROUTE_REQUIRED");}
        require(token!=null&&token.matches("[A-Za-z0-9_-]{43,128}"),"PROFILE_PUBLICATION_CREDENTIAL_REQUIRED");
        try{require(ca!=null&&ca.length()<=65536&&ca.chars().allMatch(c->c<128)&&!ca.contains("PRIVATE KEY")&&ca.strip().startsWith("-----BEGIN CERTIFICATE-----")
                    &&ca.strip().endsWith("-----END CERTIFICATE-----"),"PROFILE_PUBLICATION_CREDENTIAL_REQUIRED");
            var certs=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(ca.getBytes(StandardCharsets.US_ASCII)));
            require(!certs.isEmpty()&&certs.size()<=16,"PROFILE_PUBLICATION_CREDENTIAL_REQUIRED");for(var c:certs){var cert=(java.security.cert.X509Certificate)c;cert.checkValidity();require(cert.getBasicConstraints()>=0,"PROFILE_PUBLICATION_CREDENTIAL_REQUIRED");}
        }catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new ClusterFailure(409,"PROFILE_PUBLICATION_CREDENTIAL_REQUIRED");}
        var old=profiles(before);require(old.size()<16&&!old.containsKey(member.siteId()),"PROFILE_PUBLICATION_MEMBER_RETAINED");
        var pre=new TreeMap<String,String>();pre.put(profiles.toString(),sha(before));
        for(var raw:old.values()){
            require(raw instanceof Map<?,?>,"PROFILE_PUBLICATION_BASELINE_REQUIRED");var p=(Map<?,?>)raw;
            require(!member.siteId().equals(p.get("siteId"))&&!member.instanceId().toString().equals(p.get("instanceId"))
                    &&(!(p.get("allocationSlot") instanceof Number slot)||member.allocationSlot()!=slot.intValue()),"PROFILE_PUBLICATION_MEMBER_RETAINED");
            for(String key:List.of("tokenFile","caFile"))if(key.equals("tokenFile")||p.get(key)!=null){String path=reference(profiles,p.get(key));String hash=capturedHashes.get(Path.of(path).getFileName().toString());
                require(hash!=null&&hash.matches("[a-f0-9]{64}"),"PROFILE_PUBLICATION_BASELINE_REQUIRED");pre.put(path,hash);}
        }
        require(sha(before).equals(capturedHashes.get("cluster-profiles.json")),"PROFILE_PUBLICATION_BASELINE_REQUIRED");
        var additions=new TreeMap<String,String>();additions.put(profiles.getParent().resolve(tokenName(server)).toString(),Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.US_ASCII)));
        additions.put(profiles.getParent().resolve(caName(server)).toString(),Base64.getEncoder().encodeToString(ca.getBytes(StandardCharsets.US_ASCII)));
        require(additions.keySet().stream().noneMatch(pre::containsKey),"PROFILE_PUBLICATION_MEMBER_RETAINED");
        var next=new TreeMap<>(old);next.put(member.siteId(),Map.of("siteId",member.siteId(),"instanceId",member.instanceId().toString(),"mode","EXTERNAL","allocationSlot",member.allocationSlot(),
                "origin","https://"+targetAddress+":28490","apiPrefix","","tokenFile",profiles.getParent().resolve(tokenName(server)).toString(),"caFile",profiles.getParent().resolve(caName(server)).toString()));
        byte[] after=JSON.writeValueAsBytes(ordered(next));var post=new TreeMap<>(pre);post.put(profiles.toString(),sha(after));additions.forEach((p,b)->post.put(p,sha(Base64.getDecoder().decode(b))));
        String a=Base64.getEncoder().encodeToString(before),b=Base64.getEncoder().encodeToString(after);
        String binding=digest(List.of(bootstrap,server,member,profiles.toString(),a,b,pre,post,additions));return new Plan(bootstrap,server,member,profiles.toString(),a,b,pre,post,additions,binding);
    }
    public static void validate(Plan plan){
        try{Path file=profilePath(plan.profilesFile());var after=profiles(Base64.getDecoder().decode(plan.afterBase64()));var row=(Map<?,?>)after.get(plan.member().siteId());
            var origin=java.net.URI.create(row.get("origin").toString());var hashes=new TreeMap<String,String>();plan.beforeManifest().forEach((p,h)->hashes.put(Path.of(p).getFileName().toString(),h));
            String token=new String(Base64.getDecoder().decode(plan.additionsBase64().get(file.getParent().resolve(tokenName(plan.serverId())).toString())),StandardCharsets.US_ASCII);
            String ca=new String(Base64.getDecoder().decode(plan.additionsBase64().get(file.getParent().resolve(caName(plan.serverId())).toString())),StandardCharsets.US_ASCII);
            var expected=create(plan.bootstrapId(),plan.serverId(),plan.member(),origin.getHost(),file,Base64.getDecoder().decode(plan.beforeBase64()),hashes,token,ca);
            require(expected.equals(plan),"PROFILE_PUBLICATION_PLAN_CHANGED");
        }catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new ClusterFailure(409,"PROFILE_PUBLICATION_PLAN_CHANGED");}
    }
    static Map<String,String> manifest(Path file)throws Exception{
        byte[] raw=privateBytes(file,262144);var result=new TreeMap<String,String>();result.put(file.toString(),sha(raw));
        for(var value:profiles(raw).values()){var p=(Map<?,?>)value;for(String key:List.of("tokenFile","caFile"))if(key.equals("tokenFile")||p.get(key)!=null){String path=reference(file,p.get(key));result.put(path,sha(privateBytes(Path.of(path),key.equals("tokenFile")?256:65536)));}}
        require(Arrays.equals(raw,privateBytes(file,262144)),"PROFILE_PUBLICATION_FILES_CHANGED");return Map.copyOf(result);
    }
    private static Path state(Plan p){
        Path config=profilePath(p.profilesFile()).getParent(),legacy=config.getParent().resolve("profile-publications");
        // The service owns config/, while the application home remains root
        // owned and non-writable. Never fork a retained old-layout intent.
        require(!Files.isSymbolicLink(legacy),"PROFILE_PUBLICATION_UNSAFE_FILE");
        require(!Files.exists(legacy.resolve(p.serverId().toString()),LinkOption.NOFOLLOW_LINKS),"PROFILE_PUBLICATION_RETAINED_LAYOUT");
        return config.resolve("profile-publications");
    }
    private static void safeDirectory(Path p)throws Exception{
        for(Path path=p;path!=null;path=path.getParent())require(!Files.isSymbolicLink(path),"PROFILE_PUBLICATION_UNSAFE_FILE");
        require(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&Files.getPosixFilePermissions(p).stream().noneMatch(v->v==PosixFilePermission.GROUP_WRITE||v==PosixFilePermission.OTHERS_WRITE),"PROFILE_PUBLICATION_UNSAFE_FILE");
    }
    private static void force(Path path,byte[] raw,boolean exclusive)throws Exception{
        var options=exclusive?Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW):Set.of(StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);
        try(var ch=FileChannel.open(path,options,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){var buffer=java.nio.ByteBuffer.wrap(raw);while(buffer.hasRemaining())ch.write(buffer);ch.force(true);}
        try(var ch=FileChannel.open(path.getParent(),StandardOpenOption.READ)){ch.force(true);}
    }
    private static Map<String,Object> result(Plan p,String state){return Map.of("protocol","eatwhat-cluster-profile-publication-v1","bootstrapId",p.bootstrapId().toString(),"serverId",p.serverId().toString(),
            "binding",p.binding(),"state",state,"beforeBinding",p.beforeBinding(),"afterBinding",p.afterBinding(),"joinAuthorized",false);}
    /** Service-private persistence primitive, called only inside the composite's
     * actual DB/admin/lease fence. Fencing is deliberately not simulated here. */
    public static Map<String,Object> publish(Plan plan,Consumer<Runnable> fencedEffect){
        validate(plan);if(Files.exists(state(plan).resolve(plan.serverId().toString()),LinkOption.NOFOLLOW_LINKS))return observe(plan);
        return fenced(fencedEffect,()->publishFenced(plan));
    }
    /** Authority/DB locks are acquired before any catalog monitor or file lock.
     * Spring transaction fences must execute synchronously on this thread. */
    static <T>T fenced(Consumer<Runnable> fence,java.util.function.Supplier<T> effect){
        require(fence!=null,"PROFILE_PUBLICATION_FENCE_REQUIRED");Thread owner=Thread.currentThread();boolean[] active={true},ran={false};var result=new java.util.concurrent.atomic.AtomicReference<T>();
        try{fence.accept(()->{require(Thread.currentThread()==owner&&active[0],"PROFILE_PUBLICATION_FENCE_THREAD_REQUIRED");require(!ran[0],"PROFILE_PUBLICATION_FENCE_REPLAYED");ran[0]=true;result.set(effect.get());});}
        finally{active[0]=false;}
        require(ran[0]&&result.get()!=null,"PROFILE_PUBLICATION_FENCE_REQUIRED");return result.get();
    }
    private static Map<String,Object> publishFenced(Plan plan){
        Path file=profilePath(plan.profilesFile()),base=state(plan),task=base.resolve(plan.serverId().toString());
        try{
            safeDirectory(file.getParent());safeDirectory(base.getParent());
            if(!Files.exists(base,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(base,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));safeDirectory(base);
            Path lock=base.resolve("publication.lock");try{force(lock,"lock\n".getBytes(StandardCharsets.US_ASCII),true);}catch(FileAlreadyExistsException ignored){}
            privateBytes(lock,64);try(var ch=FileChannel.open(lock,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock held;try{held=ch.tryLock();}catch(OverlappingFileLockException busy){throw new ClusterFailure(409,"PROFILE_PUBLICATION_BUSY");}require(held!=null,"PROFILE_PUBLICATION_BUSY");
                try(held){
                    if(Files.exists(task,LinkOption.NOFOLLOW_LINKS))return observe(plan);
                    require(manifest(file).equals(plan.beforeManifest()),"PROFILE_PUBLICATION_FILES_CHANGED");
                    for(String name:plan.additionsBase64().keySet())require(!Files.exists(Path.of(name),LinkOption.NOFOLLOW_LINKS),"PROFILE_PUBLICATION_MEMBER_RETAINED");
                    require(manifest(file).equals(plan.beforeManifest()),"PROFILE_PUBLICATION_FILES_CHANGED");
                    Files.createDirectory(task,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    force(task.resolve("plan.json"),JSON.writeValueAsBytes(plan),true);
                    force(task.resolve("intent.json"),JSON.writeValueAsBytes(result(plan,"CLAIMED")),true);
                    for(var entry:plan.additionsBase64().entrySet())force(Path.of(entry.getKey()),Base64.getDecoder().decode(entry.getValue()),true);
                    require(manifest(file).equals(plan.beforeManifest()),"PROFILE_PUBLICATION_FILES_CHANGED");
                    Path staged=task.resolve("profiles.after.json");force(staged,Base64.getDecoder().decode(plan.afterBase64()),true);
                    Files.move(staged,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    try(var directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)){directory.force(true);}
                    require(manifest(file).equals(plan.afterManifest()),"PROFILE_PUBLICATION_FILES_CHANGED");
                    force(task.resolve("published.json"),JSON.writeValueAsBytes(result(plan,"FILES_PUBLISHED")),true);
                    return observe(plan);
                }
            }
        }catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new ClusterFailure(503,"PROFILE_PUBLICATION_OUTCOME_UNKNOWN");}
    }
    public static Map<String,Object> observe(Plan plan){
        validate(plan);try{Path task=state(plan).resolve(plan.serverId().toString());safeDirectory(task);
            var original=JSON.readValue(privateBytes(task.resolve("plan.json"),1048576),Plan.class);require(original.equals(plan),"PROFILE_PUBLICATION_RETAINED");
            require(JSON.readValue(privateBytes(task.resolve("intent.json"),8192),Map.class).equals(result(plan,"CLAIMED")),"PROFILE_PUBLICATION_RETAINED");
            try{if(!manifest(Path.of(plan.profilesFile())).equals(plan.afterManifest()))return result(plan,"OUTCOME_UNKNOWN");}
            catch(Exception changed){return result(plan,"OUTCOME_UNKNOWN");}
            // Lost completion acknowledgement is evidence only, never another write.
            return result(plan,"FILES_PUBLISHED");
        }catch(ClusterFailure failure){throw failure;}catch(Exception failure){return result(plan,"OUTCOME_UNKNOWN");}
    }
}
