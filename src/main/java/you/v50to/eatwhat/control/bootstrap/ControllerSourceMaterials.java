package you.v50to.eatwhat.control.bootstrap;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.ControlSsh;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** Read only, explicit controller file references. Never follows arbitrary
 * request paths or migrator/static-deployer settings, changes permissions,
 * or treats an unreferenced SOURCE file as target installation material. */
public final class ControllerSourceMaterials {
    public static final String INDEX=".bootstrap-source-files-v1.json";
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public static final List<String> PATH_SETTINGS=List.of("app.control.node-token-file","app.cluster.profiles-file",
            "app.control.node-ca-file","app.cluster.node-ca-file","app.control.deployment.authority-ca-file",
            "app.control.servers.credentials-file","app.control.servers.ssh-routes-file","app.control.servers.member-configurations-file");
    private ControllerSourceMaterials(){}
    private static void require(boolean ok){if(!ok)throw new IllegalStateException("BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE");}
    private static Path path(String value){
        require(value!=null&&value.length()<=1024&&!value.chars().anyMatch(Character::isISOControl));Path path=Path.of(value);
        require(path.isAbsolute()&&path.equals(path.normalize()));return path;
    }
    /** Decoded JDBC query values; duplicate/ambiguous options are refused. The
     * original non-file options are not interpreted, resolved or rewritten. */
    public static Map<String,String> jdbcFiles(String text){
        if(text==null)return Map.of();require(text.startsWith("jdbc:postgresql://"));var uri=URI.create(text.substring(5));
        var result=new LinkedHashMap<String,String>();var seen=new HashSet<String>();
        if(uri.getRawQuery()==null)return result;
        for(String part:uri.getRawQuery().split("&",-1)){
            String[] pair=part.split("=",2);require(pair.length==2);String name=URLDecoder.decode(pair[0],StandardCharsets.UTF_8),value=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);
            require(!name.isBlank()&&seen.add(name));
            if(Set.of("sslrootcert","sslcert","sslkey").contains(name)){path(value);result.put(name,value);}
        }return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(byte[] raw,Set<String> keys){
        var value=JSON.readValue(raw,Map.class);require(value.keySet().equals(keys));return value;
    }
    private static Map<?,?> map(Object value){require(value instanceof Map<?,?>);return (Map<?,?>)value;}
    private static String text(Object value){require(value instanceof String);return value.toString();}
    private static void version(Map<String,Object> object){require(Integer.valueOf(1).equals(object.get("version")));}
    private static void uuid(String value){require(UUID.fromString(value).toString().equals(value)&&!value.equals(new UUID(0,0).toString()));}
    static void collect(Path control,long uid,Map<String,String> properties,Map<String,String> files,Map<String,String> hashes)throws Exception{
        var collected=new Collector(control,uid,files,hashes);boolean extra=false;
        for(String setting:List.of("app.cluster.node-ca-file","app.control.node-ca-file","app.control.deployment.authority-ca-file"))
            if(properties.containsKey(setting)){collected.read(properties.get(setting),65536);extra=true;}
        for(var entry:jdbcFiles(properties.get("spring.datasource.url")).entrySet()){collected.tls(entry.getValue(),65536,!entry.getKey().equals("sslkey"));extra=true;}
        String credentialFile=properties.get("app.control.servers.credentials-file");
        if(credentialFile!=null){
            var inventory=object(collected.read(credentialFile,65536),Set.of("version","credentials"));version(inventory);
            var credentials=map(inventory.get("credentials"));require(credentials.size()<=64);
            for(var item:credentials.entrySet()){
                require(text(item.getKey()).matches("[A-Za-z][A-Za-z0-9_-]{0,63}"));var entry=map(item.getValue());
                require(entry.keySet().equals(Set.of("label","authentication","file"))&&text(entry.get("authentication")).equals("PRIVATE_KEY"));
                String label=text(entry.get("label"));require(!label.isBlank()&&label.length()<=64&&!label.chars().anyMatch(Character::isISOControl));
                String key=new String(collected.read(text(entry.get("file")),16384),StandardCharsets.UTF_8);
                require(key.startsWith("-----BEGIN ")&&key.contains("PRIVATE KEY-----")&&key.indexOf(0)<0);
            }extra=true;
        }
        String memberFile=properties.get("app.control.servers.member-configurations-file");
        if(memberFile!=null){
            var inventory=object(collected.read(memberFile,262144),Set.of("version","clusterId","servers"));
            require(Set.of(1,2).contains(inventory.get("version")));boolean v2=Integer.valueOf(2).equals(inventory.get("version"));
            require(text(inventory.get("clusterId")).equals(properties.get("app.site.cluster-id")));var servers=map(inventory.get("servers"));require(servers.size()<=64);
            for(var item:servers.entrySet()){
                uuid(text(item.getKey()));var entry=map(item.getValue());
                var keys=new HashSet<>(Set.of("profileId","authJdbcOrigin","authUsername","authPasswordFile","authCaFile"));if(v2)keys.add("authTransport");
                require(entry.keySet().equals(keys));
                if(v2)require(Set.of("DIRECT","TLS_TERMINATED").contains(text(entry.get("authTransport"))));
                require(text(entry.get("profileId")).matches("[A-Za-z0-9_-]{1,64}")&&text(entry.get("authUsername")).matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
                String origin=text(entry.get("authJdbcOrigin"));require(origin.startsWith("jdbc:postgresql://")&&origin.length()<=512);
                var uri=URI.create(origin.substring(5));require(uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null
                        &&uri.getRawPath().matches("/[A-Za-z_][A-Za-z0-9_]{0,62}")&&uri.getPort()!=0&&uri.getPort()<=65535);
                String password=new String(collected.read(text(entry.get("authPasswordFile")),4096),StandardCharsets.UTF_8).strip();
                require(!password.isEmpty()&&!password.chars().anyMatch(Character::isISOControl));
                String ca=text(entry.get("authCaFile"));if(v2&&"TLS_TERMINATED".equals(entry.get("authTransport")))require(!ca.isEmpty());
                if(!ca.isEmpty())collected.read(ca,65536);
            }extra=true;
        }
        String routeFile=properties.get("app.control.servers.ssh-routes-file");
        if(routeFile!=null){
            var inventory=object(collected.read(routeFile,65536),Set.of("version","routes"));version(inventory);var routes=map(inventory.get("routes"));require(routes.size()<=64);
            for(var item:routes.entrySet()){
                uuid(text(item.getKey()));var entry=map(item.getValue());
                require(entry.keySet().equals(Set.of("host","port","logicalHost","logicalPort","hostKey")));
                ControlSsh.host(text(entry.get("host")));String logical=ControlSsh.host(text(entry.get("logicalHost")));
                require(Set.of("127.0.0.1","localhost","::1").contains(logical));ControlSsh.key(text(entry.get("hostKey")));
                for(String field:List.of("port","logicalPort"))require(entry.get(field) instanceof Integer port&&port>0&&port<=65535);
            }extra=true;
        }
        if(extra){
            require(!files.containsKey(INDEX));byte[] raw=JSON.writeValueAsBytes(new TreeMap<>(Map.of("version",1,"files",collected.names)));
            require(raw.length<=32768&&files.size()<128);files.put(INDEX,Base64.getEncoder().encodeToString(raw));hashes.put(INDEX,ControllerSourceSnapshot.sha(raw));
        }
    }
    private static final class Collector {
        final long uid;final Map<String,String> files,hashes;final Map<String,String> names=new TreeMap<>();
        Collector(Path control,long uid,Map<String,String> files,Map<String,String> hashes){
            this.uid=uid;this.files=files;this.hashes=hashes;
            for(String name:files.keySet())names.put(control.resolve("config").resolve(name).toString(),name);
        }
        byte[] read(String value,int maximum)throws Exception{
            Path source=path(value);return retain(value,source,ControllerSourceSnapshot.privateFile(source,uid,maximum));
        }
        byte[] tls(String value,int maximum,boolean certificate)throws Exception{
            Path source=path(value);for(Path parent=source;parent!=null;parent=parent.getParent())require(!Files.isSymbolicLink(parent));
            require(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)&&Files.size(source)<=maximum);
            long owner=((Number)Files.getAttribute(source,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();require(owner==0||owner==uid);
            var mode=Files.getPosixFilePermissions(source);var allowed=EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
            if(certificate){allowed.add(PosixFilePermission.GROUP_READ);allowed.add(PosixFilePermission.OTHERS_READ);}
            require(mode.contains(PosixFilePermission.OWNER_READ)&&allowed.containsAll(mode));
            byte[] raw;try(var stream=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)){raw=stream.readNBytes(maximum+1);require(raw.length<=maximum);}
            return retain(value,source,raw);
        }
        byte[] retain(String value,Path source,byte[] raw)throws Exception{
            String name=names.get(value);require(raw.length>0);
            if(name!=null){
                if(!Arrays.equals(raw,Base64.getDecoder().decode(files.get(name))))throw new ControllerSourceSnapshot.SourceConfigurationChanged();return raw;
            }
            require(files.size()<127);
            String basename=source.getFileName().toString();int dot=basename.lastIndexOf('.');String suffix=dot<0?".private":basename.substring(dot);
            // PostgreSQL chooses its PKCS12 key manager by the exact .p12/.pfx
            // suffix. Never turn such a key into an undifferentiated .private.
            if(!suffix.matches("\\.[a-z0-9]{1,12}"))suffix=".private";
            name="bootstrap-"+ControllerSourceSnapshot.sha(value.getBytes(StandardCharsets.UTF_8)).substring(0,32)+suffix;
            require(!files.containsKey(name));names.put(value,name);files.put(name,Base64.getEncoder().encodeToString(raw));hashes.put(name,ControllerSourceSnapshot.sha(raw));return raw;
        }
    }
}
