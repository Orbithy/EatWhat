package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.*;
import java.security.cert.CertificateFactory;
import java.util.*;

/** Operator-owned enrollment inputs. HTTP supplies a registered server UUID, never URLs or secret paths. */
public final class ControlMemberConfiguration {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ControlProperties settings;
    public ControlMemberConfiguration(ControlProperties settings){this.settings=settings;}

    /** TLS termination is an explicit operator-owned route, never a JDBC query override. */
    public enum AuthTransport { DIRECT, TLS_TERMINATED }

    public record Prepared(String profileId,ClusterMember member,Map<String,String> properties,
                           String authPassword,String authCaPem,String nodeToken,AuthTransport authTransport) {
        public Prepared {properties=Map.copyOf(properties);}
        public Map<String,Object> privatePayload(){
            var value=new LinkedHashMap<String,Object>(Map.of("profileId",profileId,"properties",properties,
                    "authPassword",authPassword,"authCaPem",authCaPem,"nodeToken",nodeToken));
            // Preserve the exact v1 payload and its frozen hash for existing direct plans.
            if(authTransport!=AuthTransport.DIRECT)value.put("authTransport",authTransport.name());
            return Collections.unmodifiableMap(value);
        }
        @Override public String toString(){return "PreparedMemberConfiguration[redacted]";}
    }

    public Prepared prepare(UUID serverId,UUID clusterId,ClusterMember installed,ClusterMember authority,ClusterProfiles profiles){
        try {
            if(clusterId==null||clusterId.equals(new UUID(0,0))||!installed.mode().equals("EXTERNAL")||installed.allocationSlot()<1
                    ||!authority.mode().equals("CAMPUS")||authority.allocationSlot()!=0)throw new Exception();
            var root=JSON.readTree(privateBytes(settings.getServers().getMemberConfigurationsFile(),262144));
            if(!root.isObject()||!Set.copyOf(root.propertyNames()).equals(Set.of("version","clusterId","servers"))
                    ||!root.path("version").isIntegralNumber()||!Set.of(1,2).contains(root.path("version").asInt())
                    ||!clusterId.toString().equals(root.path("clusterId").asText())||!root.path("servers").isObject()
                    ||root.path("servers").size()>64)throw new Exception();
            for(String key:root.path("servers").propertyNames())if(!UUID.fromString(key).toString().equals(key))throw new Exception();
            var entry=root.path("servers").path(serverId.toString());
            int version=root.path("version").asInt();
            var keys=new HashSet<>(Set.of("profileId","authJdbcOrigin","authUsername","authPasswordFile","authCaFile"));
            if(version==2)keys.add("authTransport");
            if(!entry.isObject()||!Set.copyOf(entry.propertyNames()).equals(keys))throw new Exception();
            for(String key:entry.propertyNames())if(!entry.path(key).isString())throw new Exception();
            var transport=version==1?AuthTransport.DIRECT:AuthTransport.valueOf(entry.path("authTransport").asText());
            String profileId=entry.path("profileId").asText();var target=profiles.get(profileId);
            if(!installed.equals(target.member()))throw new Exception();
            var authorities=profiles.list().stream().map(p->profiles.get(p.get("id").toString())).filter(p->p.member().mode().equals("CAMPUS")&&p.member().allocationSlot()==0).toList();
            if(authorities.size()!=1||!authorities.getFirst().member().equals(authority))throw new Exception();
            var endpoint=authorities.getFirst();String username=entry.path("authUsername").asText();
            if(!username.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))throw new Exception();
            String password=new String(privateBytes(Path.of(entry.path("authPasswordFile").asText()),4096),java.nio.charset.StandardCharsets.UTF_8).strip();
            if(password.isEmpty()||password.chars().anyMatch(Character::isISOControl))throw new Exception();
            String jdbcOrigin=entry.path("authJdbcOrigin").asText();jdbc(jdbcOrigin,transport);
            String caPath=entry.path("authCaFile").asText(),ca="";
            if(!caPath.isEmpty()){
                byte[] bytes=privateBytes(Path.of(caPath),65536);
                if(java.util.stream.IntStream.range(0,bytes.length).anyMatch(i->bytes[i]<0))throw new Exception();
                ca=new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);
                if(!ca.strip().startsWith("-----BEGIN CERTIFICATE-----")||!ca.strip().endsWith("-----END CERTIFICATE-----")||ca.contains("PRIVATE KEY"))throw new Exception();
                var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));if(certificates.isEmpty()||certificates.size()>16)throw new Exception();
                for(var certificate:certificates){var cert=(java.security.cert.X509Certificate)certificate;cert.checkValidity();if(cert.getBasicConstraints()<0)throw new Exception();}
            }
            URI db=URI.create(jdbcOrigin.substring(5));if((transport==AuthTransport.TLS_TERMINATED||!loopback(db.getHost()))&&ca.isEmpty())throw new Exception();
            var p=new LinkedHashMap<>(runtime(clusterId,installed,authority,endpoint.origin().toString(),endpoint.apiPrefix(),jdbcOrigin,username,transport));
            if(!ca.isEmpty()){p.put("app.cluster.node-ca-file","/config/authority-ca.pem");p.put("app.control.node-ca-file","/config/authority-ca.pem");}
            return new Prepared(profileId,installed,p,password,ca,ControlAudit.token(target.tokenFile()),transport);
        }catch(Exception failure){throw new ControlFailure(503,"PRIVATE_MEMBER_CONFIGURATION_REQUIRED");}
    }

    /** Fixed managed-container paths; no arbitrary overrides, peer fields, deployment switch or GC execution. */
    public static Map<String,String> runtime(UUID clusterId,ClusterMember installed,ClusterMember authority,String origin,String apiPrefix,String jdbcOrigin,String username){
        return runtime(clusterId,installed,authority,origin,apiPrefix,jdbcOrigin,username,AuthTransport.DIRECT);
    }
    public static Map<String,String> runtime(UUID clusterId,ClusterMember installed,ClusterMember authority,String origin,String apiPrefix,String jdbcOrigin,String username,AuthTransport transport){
        if(clusterId==null||clusterId.equals(new UUID(0,0))||!installed.mode().equals("EXTERNAL")||installed.allocationSlot()<1
                ||!authority.mode().equals("CAMPUS")||authority.allocationSlot()!=0||!username.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))throw new ControlFailure(503,"PRIVATE_MEMBER_CONFIGURATION_REQUIRED");
        ControlService.nodeBase(origin,apiPrefix);
        String jdbc;try{jdbc=jdbc(jdbcOrigin,transport);}catch(Exception failure){throw new ControlFailure(503,"PRIVATE_MEMBER_CONFIGURATION_REQUIRED");}
        var p=new LinkedHashMap<String,String>();
        p.put("app.site.cluster-id",clusterId.toString());p.put("app.site.id",installed.siteId());p.put("app.site.instance-id",installed.instanceId().toString());p.put("app.site.mode","EXTERNAL");p.put("app.site.allocation-slot",Integer.toString(installed.allocationSlot()));
        p.put("app.control.enrollment-required","true");p.put("app.cluster.enabled","true");p.put("app.cluster.coordinator","false");p.put("app.cluster.single-site-only","false");p.put("app.cluster.control-lease.enabled","false");p.put("app.cluster.automatic-gc-enabled","false");
        p.put("app.cluster.authority-origin",origin);p.put("app.cluster.authority-api-prefix",apiPrefix);p.put("app.cluster.authority-site-id",authority.siteId());p.put("app.cluster.authority-instance-id",authority.instanceId().toString());
        p.put("app.cluster.auth-store.enabled","true");p.put("app.cluster.auth-store.jdbc-url",jdbc);p.put("app.cluster.auth-store.username",username);p.put("app.cluster.auth-store.password-file","/config/authority-auth.password");
        p.put("app.account.enabled","true");p.put("app.account.synthetic-only","false");p.put("app.sync.transport-enabled","true");p.put("app.sync.synthetic-only","false");p.put("app.sync.capture-enabled","false");
        for(String domain:List.of("activity","relation","profile","privacy","notification","catalog","files"))p.put("app.sync."+domain+".enabled","true");
        p.put("app.storage.files.lifecycle-enabled","true");p.put("app.storage.files.replication.enabled","true");p.put("app.storage.files.replication.worker-enabled","false");p.put("app.storage.files.gc.execute-enabled","false");p.put("app.control.deployment.enabled","false");
        return Collections.unmodifiableMap(p);
    }

    private static boolean loopback(String host){return Set.of("127.0.0.1","localhost","[::1]","::1").contains(host);}
    private static String jdbc(String value,AuthTransport transport)throws Exception{
        if(transport==null)throw new Exception();
        if(value==null||!value.startsWith("jdbc:postgresql://")||value.length()>512)throw new Exception();
        var uri=URI.create(value.substring(5));
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getRawPath().matches("/[A-Za-z_][A-Za-z0-9_]{0,62}")||uri.getPort()>65535||uri.getPort()==0)throw new Exception();
        boolean tls=transport==AuthTransport.TLS_TERMINATED||!loopback(uri.getHost());
        return value+"?connectTimeout=3&socketTimeout=5"+(tls?"&sslmode=verify-full&sslrootcert=/config/authority-ca.pem":"")
                +(transport==AuthTransport.TLS_TERMINATED?"&channelBinding=disable":"");
    }
    static byte[] privateBytes(Path path,int max)throws Exception{
        if(path==null||!path.isAbsolute())throw new Exception();
        for(Path item=path;item!=null;item=item.getParent())if(Files.isSymbolicLink(item))throw new Exception();
        ControlServerStore.privateFile(path,max);return Files.readAllBytes(path);
    }
}
