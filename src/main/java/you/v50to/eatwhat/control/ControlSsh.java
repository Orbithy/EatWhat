package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Fixed SSH operations, pinned host keys, isolated credential files, bounded I/O and deadlines. */
public class ControlSsh {
    public static final class RemoteRefusal extends IOException {public final String code;RemoteRefusal(String code){super("SSH_REMOTE_REFUSAL");this.code=code;}}
    private static final Set<String> RELEASE_CODES=releaseCodes();
    private static final Set<String> BOOTSTRAP_PROTOCOLS=Set.of("eatwhat-controller-bootstrap-preflight-v1","eatwhat-controller-bootstrap-runtime-v1",
            "eatwhat-controller-bootstrap-link-v1","eatwhat-controller-bootstrap-state-v1","eatwhat-controller-bootstrap-dependency-v1",
            "eatwhat-controller-bootstrap-controller-v1","eatwhat-controller-bootstrap-business-v1","eatwhat-controller-source-snapshot-v1",
            "eatwhat-controller-bootstrap-member-relay-v1","eatwhat-controller-bootstrap-member-auth-v1",
            "eatwhat-controller-bootstrap-member-ingress-v1","eatwhat-controller-profile-publication-v1");
    private static final Set<String> BOOTSTRAP_CODES=bootstrapCodes();
    private static Set<String> bootstrapCodes(){
        var result=new HashSet<String>();
        for(String name:List.of("preflight","runtime","link","link-policy","state","state-policy","state-rpc","dependency","dependency-policy","controller","controller-policy","business","business-network","business-services","business-firewall","source","source-host-key","member-relay","member-auth","member-ingress","profile-request")){
            String file=name.equals("source")?"controller-bootstrap-source.py":"controller-bootstrap-"+name+".py";
            try(var in=ControlSsh.class.getResourceAsStream("/control/"+file)){
                if(in==null)continue;String source=new String(in.readNBytes(524289),java.nio.charset.StandardCharsets.UTF_8);
                var matcher=java.util.regex.Pattern.compile("['\"](BOOTSTRAP_[A-Z0-9_]{1,110})['\"]").matcher(source);while(matcher.find())result.add(matcher.group(1));
            }catch(Exception ignored){}
        }return Set.copyOf(result);
    }
    private static Set<String> releaseCodes(){var result=new HashSet<String>();for(String resource:List.of("cluster-release.py","cluster-release-core.py"))try(var in=ControlSsh.class.getResourceAsStream("/control/"+resource)){if(in==null)continue;String source=new String(in.readNBytes(262145),java.nio.charset.StandardCharsets.UTF_8);var matcher=java.util.regex.Pattern.compile("['\"](RELEASE_[A-Z0-9_]{1,110})['\"]").matcher(source);while(matcher.find())result.add(matcher.group(1));}catch(Exception ignored){}return Set.copyOf(result);}
    private final Semaphore slots=new Semaphore(2);
    private final ControlServerStore store;private final ControlProperties settings;private final ControlSshRoutes routes;private final JsonMapper json=JsonMapper.builder().build();
    public ControlSsh(ControlServerStore store,ControlProperties settings){this.store=store;this.settings=settings;this.routes=new ControlSshRoutes(settings);}
    public static String host(String value){
        if(value==null||value.length()>253||value.startsWith("-")||value.contains("..")||!(value.matches("[A-Za-z0-9][A-Za-z0-9.-]*")||value.matches("[0-9A-Fa-f:]+")&&value.contains(":")))throw new ControlFailure(400,"INVALID_SSH_HOST");return value;
    }
    public static void port(int value){if(value<1||value>65535)throw new ControlFailure(400,"INVALID_SSH_PORT");}
    public static String user(String value){if(value==null||!value.matches("[A-Za-z_][A-Za-z0-9_.@-]{0,127}"))throw new ControlFailure(400,"INVALID_SSH_USER");return value;}
    public static Map<String,String> key(String value){try{
        if(value==null||value.length()>8192||value.contains("\n")||value.contains("\r"))throw new Exception();String[] parts=value.strip().split(" +");if(parts.length!=2||!Set.of("ssh-ed25519","ecdsa-sha2-nistp256","ecdsa-sha2-nistp384","ecdsa-sha2-nistp521","ssh-rsa").contains(parts[0]))throw new Exception();
        byte[] wire=Base64.getDecoder().decode(parts[1]);if(wire.length<32||wire.length>4096)throw new Exception();var buffer=java.nio.ByteBuffer.wrap(wire);int length=buffer.getInt();if(length!=parts[0].length()||buffer.remaining()<length)throw new Exception();byte[] type=new byte[length];buffer.get(type);if(!parts[0].equals(new String(type,java.nio.charset.StandardCharsets.US_ASCII)))throw new Exception();
        return Map.of("publicKey",parts[0]+" "+Base64.getEncoder().encodeToString(wire),"type",parts[0],"fingerprint","SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wire)));
    }catch(Exception e){throw new ControlFailure(400,"INVALID_SSH_HOST_KEY");}}
    public List<Map<String,String>> scan(String host,int port){host(host);port(port);if(!slots.tryAcquire())throw new ControlFailure(429,"SSH_OPERATION_BUSY");try{
        byte[] result=process(List.of(settings.getServers().getKeyscanExecutable().toString(),"-T","5","-p",Integer.toString(port),"-t","ed25519,ecdsa,rsa","--",host),Map.of(),out->{},12,32768,false);var keys=new LinkedHashMap<String,Map<String,String>>();
        for(String line:new String(result,java.nio.charset.StandardCharsets.UTF_8).split("\n")){if(line.startsWith("#"))continue;String[] p=line.strip().split(" +");if(p.length==3)try{var k=key(p[1]+" "+p[2]);keys.put(k.get("fingerprint"),k);}catch(ControlFailure ignored){}}
        if(keys.isEmpty())throw new Exception();return List.copyOf(keys.values());
    }catch(Exception e){throw new ControlFailure(502,"SSH_HOST_KEY_UNAVAILABLE");}finally{slots.release();}}
    @FunctionalInterface public interface Upload {void write(OutputStream stream)throws Exception;}
    // OpenSSH looks up the bare host on port 22; brackets are only used for other ports.
    static String knownHost(String host,int port){return port==22?host:"["+host+"]:"+port;}
    public static String privilege(Map<String,Object> server){
        Object value=server.getOrDefault("privilege","NONE");
        if(!(value instanceof String)||!Set.of("NONE","SUDO").contains(value))throw new ControlFailure(400,"INVALID_SSH_PRIVILEGE");
        return value.toString();
    }
    public static boolean rootExecution(Map<String,Object> server){return "root".equals(server.get("username"))||privilege(server).equals("SUDO");}
    /** Opaque binding of the actual pinned route, including private overrides.
     * Never serialize the physical endpoint or credentials into a public plan.
     */
    public String routeBinding(Map<String,Object> server){
        var endpoint=routes.resolve(server);
        try{return store.fingerprint(json.writeValueAsString(List.of(server.get("id"),endpoint.host(),endpoint.port(),
                user(server.get("username").toString()),privilege(server),key(server.get("hostKey").toString()).get("publicKey"))));}
        catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"SSH_ROUTE_BINDING_UNAVAILABLE");}
    }
    public Map<String,Object> call(Map<String,Object> server,String script,List<String> args,Upload upload,int seconds)throws Exception{
        var endpoint=routes.resolve(server);String host=endpoint.host();int port=endpoint.port();String user=user((String)server.get("username"));var hostKey=key((String)server.get("hostKey"));
        String privilege=privilege(server);
        if(!slots.tryAcquire())throw new ControlFailure(429,"SSH_OPERATION_BUSY");
        Path dir;try{dir=Files.createTempDirectory(store.directory(),"ssh-session-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}catch(Exception e){slots.release();throw e;}
        try{
            Path known=dir.resolve("known_hosts");secret(known,knownHost(host,port)+" "+hostKey.get("publicKey")+"\n");String mode=(String)server.get("authentication");Path credential=dir.resolve("credential");secret(credential,store.decrypt((String)server.get("credential")));
            var command=new ArrayList<>(List.of(settings.getDeployment().getSshExecutable().toString(),"-F","/dev/null","-T","-o","StrictHostKeyChecking=yes","-o","UserKnownHostsFile="+known,"-o","GlobalKnownHostsFile=/dev/null","-o","IdentitiesOnly=yes","-o","IdentityAgent=none","-o","ClearAllForwardings=yes","-o","ConnectTimeout=8","-o","ConnectionAttempts=1","-o","ServerAliveInterval=10","-o","ServerAliveCountMax=2","-o","NumberOfPasswordPrompts=1","-o","LogLevel=ERROR"));
            var env=new HashMap<String,String>();
            if(mode.equals("PRIVATE_KEY"))command.addAll(List.of("-o","BatchMode=yes","-o","PreferredAuthentications=publickey","-o","PasswordAuthentication=no","-i",credential.toString()));
            else if(mode.equals("PASSWORD")){
                Path askpass=dir.resolve("askpass");secret(askpass,"#!/bin/sh\nexec /bin/cat '"+credential.toString().replace("'","'\\''")+"'\n");Files.setPosixFilePermissions(askpass,PosixFilePermissions.fromString("rwx------"));env.put("SSH_ASKPASS",askpass.toString());env.put("SSH_ASKPASS_REQUIRE","force");env.put("DISPLAY","eatwhat-control:0");command.addAll(List.of("-o","BatchMode=no","-o","PubkeyAuthentication=no","-o","PreferredAuthentications=password,keyboard-interactive"));
            }else throw new IOException();
            // Only bundled code and UUID/digest/enum arguments are allowed. No user command or path is accepted.
            for(String arg:args)if(!arg.matches("[A-Za-z0-9_-]{1,128}"))throw new IOException();
            // Fixed reviewed sources may grow beyond Linux's single-argument
            // limit. Compress code only; credentials/artifact stdin are separate.
            byte[] source=script.getBytes(java.nio.charset.StandardCharsets.UTF_8);if(source.length>1024*1024)throw new IOException("SSH_SOURCE_SIZE_LIMIT");
            var compressed=new ByteArrayOutputStream();try(var deflater=new java.util.zip.DeflaterOutputStream(compressed)){deflater.write(source);}
            byte[] framedSource=compressed.toByteArray();String code=Base64.getEncoder().encodeToString(framedSource);
            String prefix=(privilege.equals("SUDO")?"sudo -n -- ":"")+"python3 -c ",suffix=args.isEmpty()?"":" "+String.join(" ",args);
            String remote=prefix+"\"exec(__import__('zlib').decompress(__import__('base64').b64decode('"+code+"')))\""+suffix;
            Upload input=upload;
            if(remote.length()>100000){
                // Preserve the exact reviewed source and original upload. A
                // short fixed loader consumes only its counted source prefix;
                // JSON and streamed artifacts remain untouched on stdin.
                remote=prefix+"\"import sys,zlib;exec(zlib.decompress(sys.stdin.buffer.read("+framedSource.length+")))\""+suffix;
                input=out->{out.write(framedSource);upload.write(out);};
            }
            if(remote.length()>100000)throw new IOException("SSH_SOURCE_ARGUMENT_LIMIT");
            command.addAll(List.of("-p",Integer.toString(port),"--",user+"@"+host,remote));byte[] result=process(command,env,input,seconds,65536,true);return json.readValue(result,Map.class);
        }finally{try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}finally{slots.release();}}
    }
    static void secret(Path path,String text)throws IOException{Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(path,text);}
    static byte[] process(List<String> command,Map<String,String> environment,Upload upload,int seconds,int limit,boolean requireSuccess)throws Exception{
        ProcessBuilder builder=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);var env=builder.environment();env.clear();env.put("PATH","/usr/local/bin:/usr/bin:/bin");env.put("LANG","C.UTF-8");env.putAll(environment);Process process=builder.start();var io=Executors.newVirtualThreadPerTaskExecutor();
        try{
            var output=io.submit(()->process.getInputStream().readNBytes(limit+1));var input=io.submit(()->{try(var out=process.getOutputStream()){upload.write(out);}return true;});
            if(!process.waitFor(seconds,TimeUnit.SECONDS))throw new IOException("SSH_TIMEOUT");input.get(2,TimeUnit.SECONDS);byte[] result=output.get(2,TimeUnit.SECONDS);if(result.length>limit)throw new IOException("SSH_FAILED");
            if(requireSuccess&&process.exitValue()!=0){
                try{var refusal=JsonMapper.builder().build().readValue(result,Map.class);Object code=refusal.get("code");
                    if(code instanceof String value&&("eatwhat-cluster-release-v1".equals(refusal.get("protocol"))&&RELEASE_CODES.contains(value)
                            ||BOOTSTRAP_PROTOCOLS.contains(refusal.get("protocol"))&&BOOTSTRAP_CODES.contains(value)
                            ||"eatwhat-member-configure-v1".equals(refusal.get("protocol"))&&"MEMBER_CONFIGURATION_PRECONDITION_FAILED".equals(value)))throw new RemoteRefusal(value);}
                catch(RemoteRefusal safe){throw safe;}catch(Exception ignored){}
                throw new IOException("SSH_FAILED");
            }return result;
        }finally{process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();io.shutdownNow();}
    }
}
