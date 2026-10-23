package you.v50to.eatwhat.control.bootstrap;

import tools.jackson.databind.json.JsonMapper;
import javax.net.SocketFactory;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;

/** Fixed SOURCE-only CLI. Private migrator credentials stay on this host.
 * Preparation snapshots them once; status never discovers replacement
 * credentials or creates/repairs a role. No Spring context or pooled session. */
public final class AuthorityAuthRoleSource {
    public static final String PROTOCOL="eatwhat-authority-auth-source-v1";
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Path CONTROL=Path.of("/opt/eatwhat-control"),SITES=Path.of("/opt/eatwhat-sites"),BASE=Path.of("/var/lib/eatwhat-controller-bootstrap-member-auth");
    public record Input(AuthorityAuthRole.Request role,ControllerSourceSnapshot.Expected source) {
        public Input {
            require(role!=null&&source!=null);ControllerSourceSnapshot.validate(source);
            require(role.clusterId().equals(source.clusterId())&&role.authoritySiteId().equals(source.authoritySiteId())
                    &&role.authorityInstanceId().equals(source.authorityInstanceId())&&role.authorityDatabaseFingerprint().equals(source.authorityDatabaseFingerprint())
                    &&role.stateId().equals(source.stateId())&&role.epoch()==source.epoch());
        }
        @Override public String toString(){return "AuthorityAuthSourceInput[redacted]";}
    }
    public record Material(String jdbcUrl,String username,String password,String kind,Map<String,String> sourceFiles,Map<String,String> tlsFiles,Set<String> privateTlsFiles) {
        public Material {
            jdbc(jdbcUrl);require(username!=null&&username.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")&&password!=null&&!password.isBlank()&&password.length()<=4096
                    &&password.chars().noneMatch(Character::isISOControl)&&Set.of("SOURCE_CONFIG","AUTHORITY_BUSINESS_CONFIG").contains(kind));
            sourceFiles=Map.copyOf(sourceFiles);tlsFiles=Map.copyOf(tlsFiles);privateTlsFiles=Set.copyOf(privateTlsFiles);
            require(!sourceFiles.isEmpty()&&sourceFiles.size()<=2&&tlsFiles.size()<=3&&tlsFiles.keySet().containsAll(privateTlsFiles));
            for(var files:List.of(sourceFiles,tlsFiles))files.forEach((path,hash)->require(Path.of(path).isAbsolute()&&Path.of(path).normalize().toString().equals(path)&&hash.matches("[a-f0-9]{64}")));
        }
        public String binding(){return digest(Map.of("jdbcUrl",jdbcUrl,"username",username,"password",password,"kind",kind,"sourceFiles",sourceFiles,"tlsFiles",tlsFiles,"privateTlsFiles",new TreeSet<>(privateTlsFiles)));}
        public Map<String,Object> review(){return Map.of("kind",kind,"credentialBinding",binding(),"sourceFileCount",sourceFiles.size(),"tlsFileCount",tlsFiles.size());}
        @Override public String toString(){return "AuthorityAuthSourceMaterial[redacted]";}
    }
    private record Vault(String inputBinding,String credentialBinding,Material material){
        @Override public String toString(){return "AuthorityAuthSourceVault[redacted]";}
    }
    private AuthorityAuthRoleSource(){}
    private static void require(boolean value){if(!value)throw new IllegalStateException("AUTHORITY_AUTH_SOURCE_REQUIRED");}
    private static Object ordered(Object value){
        if(value instanceof Map<?,?> map){var out=new TreeMap<String,Object>();map.forEach((k,v)->out.put(k.toString(),ordered(v)));return out;}
        if(value instanceof List<?> list)return list.stream().map(AuthorityAuthRoleSource::ordered).toList();return value;
    }
    private static String digest(Object value){try{return ControllerSourceSnapshot.sha(JSON.writeValueAsBytes(ordered(value)));}catch(Exception failure){throw new IllegalStateException("AUTHORITY_AUTH_SOURCE_REQUIRED");}}
    private static URI jdbc(String text){
        try{
            require(text!=null&&text.startsWith("jdbc:postgresql://")&&text.length()<=2048);var uri=URI.create(text.substring(5));
            require(Set.of("127.0.0.1","localhost","[::1]","::1").contains(uri.getHost())&&uri.getUserInfo()==null&&uri.getFragment()==null
                    &&uri.getRawPath().matches("/[A-Za-z_][A-Za-z0-9_]{0,62}")&&uri.getPort()!=0&&uri.getPort()<=65535);
            var parameters=query(uri);require(parameters.keySet().stream().allMatch(Set.of("sslmode","sslrootcert","sslcert","sslkey","sslpassword","connectTimeout","socketTimeout","ApplicationName")::contains));
            require(Set.of("disable","allow","prefer","require","verify-ca","verify-full").contains(parameters.getOrDefault("sslmode","prefer")));
            for(String key:List.of("connectTimeout","socketTimeout"))if(parameters.containsKey(key))require(parameters.get(key).matches("[1-9]|10"));
            return uri;
        }catch(Exception failure){throw new IllegalStateException("AUTHORITY_AUTH_SOURCE_REQUIRED");}
    }
    private static Map<String,String> query(URI uri){
        var result=new LinkedHashMap<String,String>();if(uri.getRawQuery()==null)return result;
        for(String part:uri.getRawQuery().split("&",-1)){
            var pair=part.split("=",2);require(pair.length==2);String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8),value=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);
            require(!key.isEmpty()&&!value.isEmpty()&&value.chars().noneMatch(Character::isISOControl)&&result.put(key,value)==null);
        }return result;
    }
    private static int port(URI uri){return uri.getPort()<0?5432:uri.getPort();}
    private static boolean sameEndpoint(URI a,URI b){return a.getHost().equalsIgnoreCase(b.getHost())&&port(a)==port(b)&&a.getRawPath().equals(b.getRawPath());}
    private static byte[] read(Path path,int maximum,Set<Long> owners,boolean publicCertificate)throws Exception{
        require(path.isAbsolute()&&path.equals(path.normalize()));for(Path p=path;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p));
        var modes=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)<=maximum&&owners.contains(((Number)Files.getAttribute(path,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue())
                &&(modes.equals(PosixFilePermissions.fromString("rw-------"))||publicCertificate&&modes.equals(PosixFilePermissions.fromString("rw-r--r--"))));
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] raw=in.readNBytes(maximum+1);require(raw.length<=maximum);return raw;}
    }
    private static Map<String,String> properties(Path path,Set<Long> owners)throws Exception{return ControllerSourceSnapshot.parseProperties(read(path,256*1024,owners,false));}
    private static boolean authority(Map<String,String> p,ControllerSourceSnapshot.Expected expected){
        return expected.clusterId().toString().equals(p.get("app.site.cluster-id"))&&expected.authoritySiteId().equals(p.get("app.site.id"))
                &&expected.authorityInstanceId().toString().equals(p.get("app.site.instance-id"))&&"CAMPUS".equals(p.get("app.site.mode"))&&"0".equals(p.get("app.site.allocation-slot"));
    }
    /** Local-only capture. SOURCE's own Flyway identity takes precedence;
     * otherwise match exactly one installed CAMPUS business configuration.
     * No caller-selected URL/secret path and no fallback to runtime credentials. */
    public static Material discover(Path control,Path sites,ControllerSourceSnapshot.Expected expected)throws Exception{
        ControllerSourceSnapshot.capture(control,expected);
        long owner=((Number)Files.getAttribute(control.resolve("state"),"unix:uid")).longValue();var owners=new HashSet<Long>(List.of(0L,owner));
        Path source=control.resolve("config/application.properties");byte[] sourceBytes=read(source,256*1024,owners,false);var runtime=ControllerSourceSnapshot.parseProperties(sourceBytes);String url=runtime.get("spring.datasource.url");URI endpoint=jdbc(url);
        var files=new TreeMap<String,String>();String sourceHash=ControllerSourceSnapshot.sha(sourceBytes);require(sourceHash.equals(expected.applicationSha256()));files.put(source.toString(),sourceHash);
        var selected=runtime;String kind="SOURCE_CONFIG";
        boolean hasUser=runtime.containsKey("spring.flyway.user"),hasPassword=runtime.containsKey("spring.flyway.password");
        if(hasUser||hasPassword)require(hasUser&&hasPassword);
        else{
            require(Files.isDirectory(sites,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(sites));var found=new ArrayList<Path>();int count=0;
            var businessOwners=new HashSet<>(owners);businessOwners.add(10001L);
            try(var entries=Files.newDirectoryStream(sites)){
                for(Path directory:entries){
                    String name=directory.getFileName().toString();if(!name.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))continue;
                    require(++count<=64&&!UUID.fromString(name).equals(new UUID(0,0))&&!Files.isSymbolicLink(directory));
                    Path path=directory.resolve("config/application.properties");if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
                    var p=properties(path,businessOwners);
                    if(authority(p,expected))found.add(path);
                }
            }require(found.size()==1);Path path=found.getFirst();byte[] businessBytes=read(path,256*1024,businessOwners,false);selected=ControllerSourceSnapshot.parseProperties(businessBytes);
            require(authority(selected,expected)&&Objects.equals(selected.get("spring.datasource.username"),runtime.get("spring.datasource.username"))
                    &&Objects.equals(selected.get("spring.datasource.password"),runtime.get("spring.datasource.password")));
            files.put(path.toString(),ControllerSourceSnapshot.sha(businessBytes));kind="AUTHORITY_BUSINESS_CONFIG";owners=businessOwners;
        }
        String username=selected.get("spring.flyway.user"),password=selected.get("spring.flyway.password");require(username!=null&&!username.equals(runtime.get("spring.datasource.username"))&&password!=null);
        String proposed=selected.get("spring.flyway.url");
        if(kind.equals("SOURCE_CONFIG")&&proposed!=null){require(sameEndpoint(endpoint,jdbc(proposed)));url=proposed;}
        else if(proposed!=null&&query(endpoint).keySet().stream().anyMatch(Set.of("sslcert","sslkey","sslpassword")::contains)){
            // A container-only URL cannot identify a host-side migrator TLS
            // client certificate. Require an explicit matching local endpoint.
            require(sameEndpoint(endpoint,jdbc(proposed)));url=proposed;
        }else require(query(endpoint).keySet().stream().noneMatch(Set.of("sslcert","sslkey","sslpassword")::contains));
        var modes=List.of("disable","allow","prefer","require","verify-ca","verify-full");
        require(modes.indexOf(query(jdbc(url)).getOrDefault("sslmode","prefer"))>=modes.indexOf(query(endpoint).getOrDefault("sslmode","prefer")));
        var tls=new TreeMap<String,String>();var privateTls=new TreeSet<String>();
        for(var e:query(jdbc(url)).entrySet())if(Set.of("sslrootcert","sslcert","sslkey").contains(e.getKey())){
            Path path=Path.of(e.getValue());tls.put(path.toString(),ControllerSourceSnapshot.sha(read(path,65536,owners,!e.getKey().equals("sslkey"))));if(e.getKey().equals("sslkey"))privateTls.add(path.toString());
        }
        // Every file used to choose the connection must still have its frozen bytes.
        for(var e:files.entrySet())require(e.getValue().equals(ControllerSourceSnapshot.sha(read(Path.of(e.getKey()),256*1024,owners,false))));
        return new Material(url,username,password,kind,files,tls,privateTls);
    }
    private static void match(Input input){new Input(input.role(),input.source());}
    private static byte[] key(Path control,Input input)throws Exception{
        long uid=((Number)Files.getAttribute(control.resolve("state"),"unix:uid")).longValue();
        byte[] key=ControllerSourceSnapshot.privateFile(control.resolve("state/servers.key"),uid,32);require(key.length==32&&ControllerSourceSnapshot.sha(key).equals(input.source().sharedKeySha256()));return key;
    }
    public static Connection connect(Material material)throws Exception{
        var properties=new Properties();properties.setProperty("user",material.username());properties.setProperty("password",material.password());
        properties.setProperty("connectTimeout","3");properties.setProperty("socketTimeout","10");
        properties.setProperty("socketFactory",LoopbackSocketFactory.class.getName());return DriverManager.getConnection(material.jdbcUrl(),properties);
    }
    /** The actual resolved socket must be loopback, including when an original
     * URL uses localhost. A changed resolver cannot receive migrator secrets. */
    public static final class LoopbackSocketFactory extends SocketFactory {
        public LoopbackSocketFactory(){}
        @Override public Socket createSocket(){return new Socket(){
            @Override public void connect(SocketAddress address,int timeout)throws IOException{
                if(!(address instanceof InetSocketAddress endpoint)||endpoint.isUnresolved()||!endpoint.getAddress().isLoopbackAddress())throw new IOException("AUTHORITY_AUTH_LOOPBACK_REQUIRED");
                super.connect(address,timeout);
            }
            @Override public void connect(SocketAddress address)throws IOException{connect(address,0);}
        };}
        private Socket open(InetAddress address,int port,InetAddress local,int localPort)throws IOException{
            Socket socket=createSocket();try{if(local!=null){if(!local.isLoopbackAddress())throw new IOException("AUTHORITY_AUTH_LOOPBACK_REQUIRED");socket.bind(new InetSocketAddress(local,localPort));}socket.connect(new InetSocketAddress(address,port));return socket;}
            catch(IOException failure){socket.close();throw failure;}
        }
        @Override public Socket createSocket(String host,int port)throws IOException{return open(InetAddress.getByName(host),port,null,0);}
        @Override public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{return open(InetAddress.getByName(host),port,local,localPort);}
        @Override public Socket createSocket(InetAddress host,int port)throws IOException{return open(host,port,null,0);}
        @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{return open(host,port,local,localPort);}
    }
    public static Map<String,Object> inspect(Path control,Path sites,Input input)throws Exception{
        match(input);var material=discover(control,sites,input.source());byte[] key=key(control,input);
        try(var c=connect(material)){AuthorityAuthRole.preflight(c,input.role(),key);}finally{Arrays.fill(key,(byte)0);}
        return Map.of("protocol",PROTOCOL,"id",input.role().id().toString(),"state","INSPECTED","effectsStarted",false,
                "credentialBinding",material.binding(),"proof",digest(Map.of("input",JSON.readValue(JSON.writeValueAsBytes(input),Map.class),"credentialBinding",material.binding())));
    }
    private static void writeNew(Path path,byte[] bytes)throws Exception{
        Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try(var channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){var data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);}
        try(var directory=FileChannel.open(path.getParent(),StandardOpenOption.READ)){directory.force(true);}
    }
    private static Map<String,Object> inputMap(Path task)throws Exception{return JSON.readValue(read(task.resolve("sql-input.json"),65536,Set.of(0L),false),LinkedHashMap.class);}
    private static Input input(Map<String,Object> map){
        require(map.keySet().equals(Set.of("role","source","credentialBinding"))&&map.get("credentialBinding") instanceof String hash&&hash.matches("[a-f0-9]{64}"));
        return new Input(JSON.convertValue(map.get("role"),AuthorityAuthRole.Request.class),JSON.convertValue(map.get("source"),ControllerSourceSnapshot.Expected.class));
    }
    private static Vault prepared(Path task,Map<String,Object> raw)throws Exception{
        var vault=JSON.readValue(read(task.resolve("sql-credentials.json"),65536,Set.of(0L),false),Vault.class);
        require(vault.inputBinding().equals(digest(raw))&&vault.credentialBinding().equals(raw.get("credentialBinding"))&&vault.material().binding().equals(vault.credentialBinding()));return vault;
    }
    private static void tlsFiles(Material material,Path control)throws Exception{
        long uid=((Number)Files.getAttribute(control.resolve("state"),"unix:uid")).longValue();var owners=new HashSet<Long>(List.of(0L,uid,10001L));
        for(var e:material.tlsFiles().entrySet())require(e.getValue().equals(ControllerSourceSnapshot.sha(read(Path.of(e.getKey()),65536,owners,!material.privateTlsFiles().contains(e.getKey())))));
    }
    public static void main(String[] args){
        try{
            require(System.getProperty("os.name").equals("Linux")&&((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).longValue()==0&&args.length>=1);
            String action=args[0];
            if(action.equals("inspect")){
                require(args.length==1);byte[] bytes=System.in.readNBytes(65537);require(bytes.length<=65536);var map=JSON.readValue(bytes,LinkedHashMap.class);
                require(map.keySet().equals(Set.of("role","source")));var in=new Input(JSON.convertValue(map.get("role"),AuthorityAuthRole.Request.class),JSON.convertValue(map.get("source"),ControllerSourceSnapshot.Expected.class));
                System.out.println(JSON.writeValueAsString(inspect(CONTROL,SITES,in)));return;
            }
            require(args.length==2&&Set.of("capture","capture-status","create","status").contains(action));UUID id=UUID.fromString(args[1]);require(id.toString().equals(args[1])&&!id.equals(new UUID(0,0)));
            Path task=BASE.resolve(args[1]);for(Path p=task;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p)&&((Number)Files.getAttribute(p,"unix:uid")).longValue()==0&&(Integer.valueOf(0).equals((int)Files.getAttribute(p,"unix:mode")&0022)));
            var map=inputMap(task);var in=input(map);require(in.role().id().equals(id));Path file=task.resolve("sql-credentials.json");
            if(action.equals("capture")&&!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
                var material=discover(CONTROL,SITES,in.source());require(material.binding().equals(map.get("credentialBinding")));
                writeNew(file,JSON.writeValueAsBytes(new Vault(digest(map),material.binding(),material)));
            }
            var saved=prepared(task,map);var result=new LinkedHashMap<String,Object>();
            if(action.startsWith("capture"))result.putAll(Map.of("state","SOURCE_CREDENTIALS_CAPTURED","credentialBinding",saved.credentialBinding(),"privateCredentialSha256",ControllerSourceSnapshot.sha(read(file,65536,Set.of(0L),false))));
            else{
                if(action.equals("create"))require(discover(CONTROL,SITES,in.source()).binding().equals(saved.credentialBinding()));
                tlsFiles(saved.material(),CONTROL);
                try(var connection=connect(saved.material())){
                    if(action.equals("create")){byte[] key=key(CONTROL,in);try{result.putAll(AuthorityAuthRole.create(connection,in.role(),key));}finally{Arrays.fill(key,(byte)0);}}
                    else result.putAll(AuthorityAuthRole.inspect(connection,in.role()));
                }
            }
            result.put("sourceProtocol",PROTOCOL);result.put("id",id.toString());System.out.println(JSON.writeValueAsString(result));
        }catch(Exception failure){System.out.println("{\"sourceProtocol\":\""+PROTOCOL+"\",\"code\":\"AUTHORITY_AUTH_SOURCE_UNCONFIRMED\"}");System.exit(1);}
    }
}
