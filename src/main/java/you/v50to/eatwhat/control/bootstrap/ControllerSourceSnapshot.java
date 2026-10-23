package you.v50to.eatwhat.control.bootstrap;

import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Private read-only source capture. Never a public HTTP response. The manager
 * must encrypt this material and independently guard admin, lease and registry.
 * No Spring application, migration callback, schema change or credential copy
 * to stdout on failure. The physical source need not be the current leader.
 */
public final class ControllerSourceSnapshot {
    public static final String PROTOCOL="eatwhat-controller-source-snapshot-v1";
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Path CONTROL=Path.of("/opt/eatwhat-control");
    /** Supplied by the manager's authority fence, never by a browser. The
     * physical SOURCE discovers its own configuration and process references. */
    public record Authority(UUID clusterId,String authoritySiteId,UUID authorityInstanceId,
                            String authorityDatabaseFingerprint,UUID stateId,long epoch,String sharedKeySha256) {}
    public record Expected(UUID clusterId,String authoritySiteId,UUID authorityInstanceId,
                           String authorityDatabaseFingerprint,UUID stateId,long epoch,String controllerName,
                           String applicationSha256,String profilesSha256,String sharedKeySha256) {}
    public record Captured(Map<String,String> properties,Map<String,String> privateFiles,Map<String,String> hashes) {
        public Captured {properties=Map.copyOf(properties);privateFiles=Map.copyOf(privateFiles);hashes=Map.copyOf(hashes);}
        @Override public String toString(){return "CapturedControllerSource[redacted]";}
    }
    private ControllerSourceSnapshot(){}
    public static final class SourceConfigurationChanged extends IllegalStateException {
        public SourceConfigurationChanged(){super("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED");}
    }
    private static void unchanged(boolean value){if(!value)throw new SourceConfigurationChanged();}
    private static void require(boolean ok){if(!ok)throw new IllegalStateException("BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE");}
    private static boolean id(UUID id){return id!=null&&!id.equals(new UUID(0,0));}
    private static boolean hash(String value,int size){return value!=null&&value.matches("[a-f0-9]{"+size+"}");}
    public static void validate(Authority a){
        require(a!=null&&id(a.clusterId())&&id(a.authorityInstanceId())&&id(a.stateId())&&a.epoch()>0&&a.epoch()<Long.MAX_VALUE
                &&a.authoritySiteId()!=null&&a.authoritySiteId().matches("[A-Za-z0-9_-]{1,64}")
                &&hash(a.authorityDatabaseFingerprint(),32)&&hash(a.sharedKeySha256(),64));
    }
    public static void validate(Expected e){
        require(e!=null&&id(e.clusterId())&&id(e.authorityInstanceId())&&id(e.stateId())&&e.epoch()>0
                &&e.authoritySiteId()!=null&&e.authoritySiteId().matches("[A-Za-z0-9_-]{1,64}")
                &&e.controllerName()!=null&&e.controllerName().matches("[A-Za-z0-9_-]{1,64}")
                &&hash(e.authorityDatabaseFingerprint(),32)&&hash(e.applicationSha256(),64)
                &&hash(e.profilesSha256(),64)&&hash(e.sharedKeySha256(),64));
    }
    public static String sha(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    /** Properties supports continuation, escaped delimiters and Unicode. Reject
     * duplicate decoded keys, Spring relaxed aliases, placeholders and multiple
     * documents rather than guessing which effective configuration is running.
     */
    public static Map<String,String> parseProperties(byte[] raw)throws Exception {
        require(raw.length>0&&raw.length<=256*1024);
        var properties=new Properties(){
            @Override public synchronized Object put(Object k,Object v){require(!containsKey(k));return super.put(k,v);}
        };
        properties.load(new ByteArrayInputStream(raw));
        var result=new TreeMap<String,String>();var aliases=new HashSet<String>();
        for(String key:properties.stringPropertyNames()){
            String value=properties.getProperty(key);
            require(key.length()<=200&&(key.matches("[a-z0-9]+(?:[.\\-][a-z0-9]+|\\[[0-9]+\\])*")
                    ||key.matches("app\\.control\\.releases\\[[A-Za-z0-9_-]{1,64}\\]\\.(artifact|sha256|acceptance-evidence-id|rollback-compatible)"))
                    &&aliases.add(propertyIdentity(key))&&value.length()<=32768
                    &&value.indexOf(0)<0&&!value.contains("${"));
            require(!key.startsWith("spring.config.")&&!key.startsWith("spring.profiles.")
                    &&!key.startsWith("spring.main.")&&!key.startsWith("spring.application.json")
                    &&!key.startsWith("logging.config"));
            result.put(key,value);
        }
        require(!result.isEmpty()&&result.size()<=1024);
        // Verify against the actual bundled Spring loader, not another parser's
        // assumption about encoding, list syntax or document separators.
        var sources=new PropertiesPropertySourceLoader().load("source-capture",new ByteArrayResource(raw));
        require(sources.size()==1&&sources.getFirst() instanceof MapPropertySource);
        var spring=((MapPropertySource)sources.getFirst()).getSource();require(spring.keySet().equals(result.keySet()));
        result.forEach((k,v)->require(Objects.equals(v,sources.getFirst().getProperty(k))));
        return Collections.unmodifiableMap(result);
    }
    private static String propertyIdentity(String key){
        var release=java.util.regex.Pattern.compile("app\\.control\\.releases(?:\\[([A-Za-z0-9_-]{1,64})\\]|\\.([A-Za-z0-9_-]{1,64}))\\.([a-z0-9-]+)").matcher(key);
        if(release.matches())return "appcontrolreleases["+(release.group(1)==null?release.group(2):release.group(1))+"]"+release.group(3).replace("-","");
        return key.replace(".","").replace("-","").replace("[","").replace("]","");
    }
    static byte[] privateFile(Path path,long uid,int maximum)throws Exception {
        for(Path p=path;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p));
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)<=maximum
                &&Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------")));
        long owner=((Number)Files.getAttribute(path,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();require(owner==0||owner==uid);
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] raw=in.readNBytes(maximum+1);require(raw.length<=maximum);return raw;}
    }
    private static Path tokenPath(Path config,String value){
        require(value!=null);Path p=Path.of(value);require(p.isAbsolute()&&p.normalize().equals(p)
                &&Objects.equals(p.getParent(),config)&&p.getFileName().toString().matches("[a-z0-9][a-z0-9_.-]{0,95}")
                &&!p.getFileName().toString().equals("application.properties")&& !p.getFileName().toString().equals("cluster-profiles.json"));return p;
    }
    /** Discover only fixed managed files. capture() checks all property aliases,
     * ownership, material paths and the manager's shared identity before any
     * derived expectation is returned. The normal collector then proves the
     * actual database and process against this exact expectation. */
    public static Expected discover(Path control,Authority a)throws Exception{
        validate(a);Path state=control.resolve("state"),config=control.resolve("config");
        long uid=((Number)Files.getAttribute(state,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();
        byte[] app=privateFile(config.resolve("application.properties"),uid,256*1024);
        byte[] profiles=privateFile(config.resolve("cluster-profiles.json"),uid,256*1024);
        var values=parseProperties(app);
        var expected=new Expected(a.clusterId(),a.authoritySiteId(),a.authorityInstanceId(),a.authorityDatabaseFingerprint(),a.stateId(),a.epoch(),
                values.get("app.cluster.control-lease.controller-name"),sha(app),sha(profiles),a.sharedKeySha256());
        capture(control,expected);return expected;
    }
    public static Captured capture(Path control,Expected e)throws Exception {
        validate(e);Path state=control.resolve("state"),config=control.resolve("config");
        require(Files.isDirectory(state,LinkOption.NOFOLLOW_LINKS)
                &&Files.getPosixFilePermissions(state).equals(PosixFilePermissions.fromString("rwx------")));
        long uid=((Number)Files.getAttribute(state,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();
        for(Path directory:List.of(control,config,state)){
            require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(directory));
            long owner=((Number)Files.getAttribute(directory,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();
            require((owner==0||owner==uid)&&Files.getPosixFilePermissions(directory).stream().noneMatch(
                    p->p==java.nio.file.attribute.PosixFilePermission.GROUP_WRITE||p==java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE));
        }
        byte[] application=privateFile(config.resolve("application.properties"),uid,256*1024);
        byte[] profiles=privateFile(config.resolve("cluster-profiles.json"),uid,256*1024);
        byte[] key=privateFile(state.resolve("servers.key"),uid,32);
        unchanged(key.length==32&&sha(key).equals(e.sharedKeySha256())&&sha(application).equals(e.applicationSha256())
                &&sha(profiles).equals(e.profilesSha256())
                &&new String(privateFile(state.resolve("controller-state-id"),uid,36),java.nio.charset.StandardCharsets.US_ASCII).equals(e.stateId().toString()));
        var values=parseProperties(application);
        var required=Map.ofEntries(Map.entry("app.site.cluster-id",e.clusterId().toString()),Map.entry("app.site.id",e.authoritySiteId()),
                Map.entry("app.site.instance-id",e.authorityInstanceId().toString()),Map.entry("app.site.mode","CAMPUS"),Map.entry("app.site.allocation-slot","0"),
                Map.entry("app.control.enabled","true"),Map.entry("app.control.node-enabled","true"),
                Map.entry("app.control.environment-kind","managed-deployment"),Map.entry("app.control.state-directory",state.toString()),
                Map.entry("app.cluster.profiles-file",config.resolve("cluster-profiles.json").toString()),Map.entry("app.cluster.enabled","true"),
                Map.entry("app.cluster.coordinator","true"),Map.entry("app.cluster.single-site-only","false"),Map.entry("app.cluster.control-lease.enabled","true"),
                Map.entry("app.cluster.control-lease.controller-name",e.controllerName()));
        required.forEach((k,v)->require(v.equals(values.get(k))));
        // The physical authority owns local state. Before the first extra
        // controller it legitimately does not require an NFS mount. TARGET's
        // mandatory shared-state gate is enforced by the separate bundle policy.
        require(Set.of("true","false").contains(values.getOrDefault("app.control.shared-state-required","false")));
        var root=JSON.readTree(profiles);require(root.isObject()&&root.size()>0&&root.size()<=1024);
        var files=new TreeMap<String,String>();var hashes=new TreeMap<String,String>();
        files.put("cluster-profiles.json",Base64.getEncoder().encodeToString(profiles));
        hashes.put("application.properties",sha(application));hashes.put("cluster-profiles.json",sha(profiles));
        boolean authority=false;
        for(String name:root.propertyNames()){
            var profile=root.path(name);require(name.matches("[A-Za-z0-9_-]{1,64}")&&profile.isObject()&&profile.path("tokenFile").isString());
            Path token=tokenPath(config,profile.path("tokenFile").asText());byte[] bytes=privateFile(token,uid,256);
            require(new String(bytes,java.nio.charset.StandardCharsets.US_ASCII).matches("[A-Za-z0-9_-]{43,128}\\s*"));
            String filename=token.getFileName().toString();files.put(filename,Base64.getEncoder().encodeToString(bytes));hashes.put(filename,sha(bytes));
            var ca=profile.get("caFile");if(ca!=null&&!ca.isNull()){
                require(ca.isString()&&you.v50to.eatwhat.control.ControlService.origin(profile.path("origin").asText()).getScheme().equals("https"));Path path=tokenPath(config,ca.asText());byte[] certificate=privateFile(path,uid,65536);
                String pem=new String(certificate,java.nio.charset.StandardCharsets.US_ASCII).strip();
                require(pem.startsWith("-----BEGIN CERTIFICATE-----")&&pem.endsWith("-----END CERTIFICATE-----")&&!pem.contains("PRIVATE KEY"));
                files.put(path.getFileName().toString(),Base64.getEncoder().encodeToString(certificate));hashes.put(path.getFileName().toString(),sha(certificate));
            }
            if(e.authoritySiteId().equals(profile.path("siteId").asText())){
                require(!authority&&e.authorityInstanceId().toString().equals(profile.path("instanceId").asText())
                        &&"CAMPUS".equals(profile.path("mode").asText())&&profile.path("allocationSlot").isIntegralNumber()
                        &&profile.path("allocationSlot").asInt()==0&&token.toString().equals(values.get("app.control.node-token-file")));authority=true;
            }
        }
        require(authority);
        ControllerSourceMaterials.collect(control,uid,values,files,hashes);
        var exported=new TreeMap<>(values);
        // Migrator credentials and settings never leave the source collector.
        exported.keySet().removeIf(k->k.startsWith("spring.flyway.")&&!k.equals("spring.flyway.enabled"));
        require(JSON.writeValueAsBytes(Map.of("properties",exported,"privateFiles",files)).length<=48*1024);
        return new Captured(exported,files,hashes);
    }
    /** One actual database snapshot; no member/lease seed and no source-primary
     * assumption. Checking the database name/OID prevents a copied member row
     * from masquerading as the authority. No schema or role changes are made.
     */
    public static Map<String,Object> databaseSnapshot(Connection c,Expected e)throws Exception {
        validate(e);c.setReadOnly(true);c.setAutoCommit(false);
        try {
            try(var s=c.createStatement()){s.setQueryTimeout(5);s.execute("SET LOCAL statement_timeout='8s'");s.execute("SET LOCAL lock_timeout='3s'");}
            try(var q=c.prepareStatement("""
                SELECT s.epoch,p.holder_id,l.generation,lp.name,l.holder_id=p.holder_id
                FROM eatwhat_identity.node_state n JOIN eatwhat_identity.allocation a ON a.singleton
                JOIN eatwhat_cluster.state s ON s.singleton
                JOIN eatwhat_cluster.member m ON m.site_id=n.site_id AND m.instance_id=n.instance_id
                JOIN eatwhat_cluster.control_process p ON p.cluster_id=s.cluster_id AND p.name=? AND p.state_id=? AND p.expires_at>clock_timestamp()
                JOIN eatwhat_cluster.control_lease l ON l.singleton AND l.cluster_id=s.cluster_id AND l.expires_at>clock_timestamp()
                JOIN eatwhat_cluster.control_process lp ON lp.holder_id=l.holder_id AND lp.cluster_id=s.cluster_id AND lp.state_id=p.state_id AND lp.expires_at>clock_timestamp()
                WHERE n.singleton AND n.site_id=? AND n.instance_id=? AND n.mode='CAMPUS'
                  AND n.database_name=current_database() AND n.database_oid=(SELECT oid::bigint FROM pg_database WHERE datname=current_database())
                  AND md5(n.cluster_identifier||':'||n.database_oid::text)=?
                  AND a.cluster_id=? AND a.site_id=n.site_id AND a.instance_id=n.instance_id AND a.slot=0
                  AND s.cluster_id=a.cluster_id AND s.epoch=? AND s.coordinator_site=n.site_id AND s.coordinator_instance=n.instance_id
                  AND m.mode='CAMPUS' AND m.allocation_slot=0 AND m.state='ACTIVE' AND m.database_fingerprint=md5(n.cluster_identifier||':'||n.database_oid::text)
                """)){
                q.setQueryTimeout(8);q.setString(1,e.controllerName());q.setObject(2,e.stateId());q.setString(3,e.authoritySiteId());
                q.setObject(4,e.authorityInstanceId());q.setString(5,e.authorityDatabaseFingerprint());q.setObject(6,e.clusterId());q.setLong(7,e.epoch());
                try(var r=q.executeQuery()){
                    require(r.next());var result=new LinkedHashMap<String,Object>();result.put("epoch",r.getLong(1));
                    result.put("holderId",r.getObject(2,UUID.class).toString());result.put("generation",r.getLong(3));
                    result.put("leaderName",r.getString(4));result.put("sourceIsLeader",r.getBoolean(5));require(!r.next());
                    result.put("actualDatabaseNameAndOidVerified",true);result.put("readOnlyTransaction",true);return result;
                }
            }
        }finally{c.rollback();}
    }
    public static void main(String[] args){
        try {
            require(args.length==0||Arrays.equals(args,new String[]{"discover"}));byte[] input=System.in.readNBytes(8193);require(input.length<=8192);
            if(args.length==1){System.out.write(JSON.writeValueAsBytes(discover(CONTROL,JSON.readValue(input,Authority.class))));System.out.println();return;}
            Expected expected=JSON.readValue(input,Expected.class);Captured captured=capture(CONTROL,expected);
            var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            env.getPropertySources().addFirst(new MapPropertySource("sealed-source",new HashMap<>(captured.properties())));
            Class.forName("org.postgresql.Driver");DriverManager.setLoginTimeout(8);
            Map<String,Object> proof;
            try(var c=ControllerExistingAuthoritySchema.readonlyDataSource(env).getConnection()){proof=databaseSnapshot(c,expected);}
            // Repeat file capture after the database read. Configuration or key
            // drift during capture invalidates the whole snapshot.
            unchanged(captured.equals(capture(CONTROL,expected)));
            var result=Map.of("protocol",PROTOCOL,"proof",proof,"sourceAuthority",Map.of("clusterId",expected.clusterId().toString(),
                    "siteId",expected.authoritySiteId(),"instanceId",expected.authorityInstanceId().toString(),"databaseFingerprint",expected.authorityDatabaseFingerprint()),
                    "fileHashes",captured.hashes(),"_private",Map.of("properties",captured.properties(),"privateFiles",captured.privateFiles()));
            byte[] raw=JSON.writeValueAsBytes(result);require(raw.length<=60*1024);System.out.write(raw);System.out.println();
        }catch(SourceConfigurationChanged failure){System.out.println("{\"code\":\"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED\"}");System.exit(1);}
        catch(Exception failure){System.out.println("{\"code\":\"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE\"}");System.exit(1);}
    }
}
