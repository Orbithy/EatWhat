package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.util.*;

/** Derives TARGET endpoints from authenticated SOURCE material and the two
 * frozen registry entries. A derived route is a plan, never a connectivity
 * receipt. Unresolved third-host SSH routes are refused, not silently copied. */
public final class ControlBootstrapTargetNetwork {
    static final String ADMIN_PERMISSION="FORWARD_PINNED_SOURCE_ADMIN_SSH";
    static final String MEMBER_PERMISSION="FORWARD_CAPTURED_MEMBER_ENDPOINTS";
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ControlBootstrapAdmission admission;private final ControlBootstrapTargetMaterials materials;
    public ControlBootstrapTargetNetwork(ControlBootstrapAdmission admission,ControlBootstrapTargetMaterials materials){this.admission=admission;this.materials=materials;}
    public record Planned(@com.fasterxml.jackson.annotation.JsonIgnore ControlBootstrapTargetMaterials.Prepared materials,
                          @com.fasterxml.jackson.annotation.JsonIgnore Map<String,Object> profiles,
                          @com.fasterxml.jackson.annotation.JsonIgnore List<Map<String,Object>> forwards,
                          @com.fasterxml.jackson.annotation.JsonIgnore Map<String,Object> sshRoutes,
                          @com.fasterxml.jackson.annotation.JsonIgnore String authorityOrigin) {
        public Planned {profiles=Map.copyOf(profiles);forwards=List.copyOf(forwards);sshRoutes=Map.copyOf(sshRoutes);}
        @Override public String toString(){return "BootstrapTargetNetwork[unverified,redacted]";}
        @com.fasterxml.jackson.annotation.JsonValue public Map<String,Object> review(){return Map.of("status","PLANNED_CONNECTIVITY_UNVERIFIED","profileCount",profiles.size(),
                "forwardPlan",forwards,"sshRouteCount",sshRoutes.size(),"sshServerIds",sshRoutes.keySet().stream().sorted().toList());}
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private static boolean loopback(String value){return Set.of("127.0.0.1","localhost","::1").contains(value);}
    @SuppressWarnings("unchecked") private static Map<String,Object> profile(Object value){require(value instanceof Map<?,?>,"BOOTSTRAP_TARGET_PROFILE_INVALID");return new LinkedHashMap<>((Map<String,Object>)value);}
    private static Map<String,Object> forward(String purpose,int local,int remote){return Map.of("purpose",purpose,"localPort",local,"remotePort",remote);}
    @SuppressWarnings("unchecked") Map<String,String> sourceProperties(ControlBootstrapAdmission.Frozen f){
        admission.requireBound(f);var raw=admission.privateSourcePayload(f).get("properties");require(raw instanceof Map<?,?>,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");return (Map<String,String>)raw;
    }
    static URI database(Map<String,String> values){
        try{String text=values.get("spring.datasource.url");require(text!=null&&text.startsWith("jdbc:postgresql://"),"BOOTSTRAP_TARGET_DATABASE_ROUTE_REQUIRED");
            URI uri=URI.create(text.substring(5));require(uri.getHost()!=null&&loopback(uri.getHost())&&uri.getUserInfo()==null&&uri.getFragment()==null,"BOOTSTRAP_TARGET_DATABASE_ROUTE_REQUIRED");return uri;
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_TARGET_DATABASE_ROUTE_REQUIRED");}
    }
    static int sourcePort(URI uri,int fallback){int port=uri.getPort()<0?fallback:uri.getPort();ControlSsh.port(port);return port;}
    static int cachePort(Map<String,String> values){
        try{
            require(!values.containsKey("spring.data.redis.url"),"BOOTSTRAP_TARGET_CACHE_URL_MATERIAL_REQUIRED");
            require(loopback(values.getOrDefault("spring.data.redis.host","localhost")),"BOOTSTRAP_TARGET_CACHE_ROUTE_REQUIRED");
            int port=Integer.parseInt(values.getOrDefault("spring.data.redis.port","6379"));ControlSsh.port(port);return port;
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_TARGET_CACHE_ROUTE_REQUIRED");}
    }
    public List<Map<String,Object>> forwards(ControlBootstrapAdmission.Frozen f){return plan(f).forwards();}
    public Planned plan(ControlBootstrapAdmission.Frozen f){
        try{return planBound(f);}catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_TARGET_PROFILE_INVALID");}
    }
    @SuppressWarnings("unchecked") private Planned planBound(ControlBootstrapAdmission.Frozen f){
        admission.requireBound(f);var prepared=materials.prepare(f);var registered=admission.registeredServers(f);var authority=f.sourceRequest().expected();
        var profiles=JSON.readValue(Base64.getDecoder().decode(prepared.privateFiles().get("cluster-profiles.json")),Map.class);
        require(!profiles.isEmpty()&&profiles.size()<=16,"BOOTSTRAP_TARGET_PROFILE_INVALID");
        var values=sourceProperties(f);int databasePort=sourcePort(database(values),5432),cachePort=cachePort(values);
        var output=new TreeMap<String,Object>();var forwards=new ArrayList<Map<String,Object>>();
        forwards.add(forward("CONTROL_DATABASE",25432,databasePort));forwards.add(forward("CONTROL_CACHE",16379,cachePort));
        var localPorts=new HashSet<>(Set.of(25432,16379,28090,18080,18081,2223));var remotePorts=new HashSet<Integer>();
        require(remotePorts.add(databasePort)&&remotePorts.add(cachePort),"BOOTSTRAP_TARGET_PROFILE_PORT_INVALID");
        for(Object raw:profiles.values()){
            var p=profile(raw);if(!authority.authoritySiteId().equals(p.get("siteId")))continue;
            URI origin=URI.create(p.get("origin").toString());int port=sourcePort(origin,origin.getScheme().equals("https")?443:80);
            require(remotePorts.add(port),"BOOTSTRAP_TARGET_PROFILE_PORT_INVALID");forwards.add(forward("AUTHORITY_HTTP",28090,port));
        }
        String authorityOrigin=null;int index=0;var sites=new HashSet<String>();var instances=new HashSet<String>();var slots=new HashSet<Integer>();
        for(String id:new TreeSet<String>(profiles.keySet())){
            var p=profile(profiles.get(id));require(p.get("siteId") instanceof String&&p.get("instanceId") instanceof String&&p.get("allocationSlot") instanceof Integer,"BOOTSTRAP_TARGET_PROFILE_INVALID");
            int slot=(Integer)p.get("allocationSlot");require(slot>=0&&slot<1024&&sites.add(p.get("siteId").toString())&&instances.add(p.get("instanceId").toString())&&slots.add(slot),"BOOTSTRAP_TARGET_PROFILE_INVALID");
            URI origin;try{origin=URI.create(p.get("origin").toString());}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_TARGET_PROFILE_INVALID");}
            require(origin.getHost()!=null&&origin.getUserInfo()==null&&origin.getQuery()==null&&origin.getFragment()==null
                    &&Set.of("","/").contains(origin.getRawPath())&&Set.of("http","https").contains(origin.getScheme()),"BOOTSTRAP_TARGET_PROFILE_INVALID");
            require(!origin.getScheme().equals("https")||!loopback(origin.getHost())||origin.getHost().equals("127.0.0.1"),"BOOTSTRAP_TARGET_TLS_HOST_ROUTE_REQUIRED");
            if(authority.authoritySiteId().equals(p.get("siteId"))){
                require(authorityOrigin==null&&authority.authorityInstanceId().toString().equals(p.get("instanceId"))&&slot==0&&"CAMPUS".equals(p.get("mode")),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
                require(loopback(origin.getHost()),"BOOTSTRAP_TARGET_AUTHORITY_ROUTE_REQUIRED");
                authorityOrigin=origin.getScheme()+"://127.0.0.1:28090";p.put("origin",authorityOrigin);
            }else if(loopback(origin.getHost())){
                int remote=origin.getPort()<0?(origin.getScheme().equals("https")?443:80):origin.getPort();
                require(remote>=1&&remote<=65535&&!Set.of(18080,18081,2223).contains(remote)&&remotePorts.add(remote),"BOOTSTRAP_TARGET_PROFILE_PORT_INVALID");
                int local=28100+index++;require(localPorts.add(local),"BOOTSTRAP_TARGET_PROFILE_PORT_INVALID");
                forwards.add(forward("MEMBER_HTTP_"+slot,local,remote));p.put("origin",origin.getScheme()+"://127.0.0.1:"+local);
            }else require(origin.getScheme().equals("https"),"BOOTSTRAP_TARGET_PROFILE_TLS_REQUIRED");
            output.put(id,p);
        }require(authorityOrigin!=null,"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
        var routes=new TreeMap<String,Object>();
        var source=registered.get(f.spec().sourceServerId());var target=registered.get(f.spec().targetServerId());
        for(var entry:prepared.sourceRoutes().entrySet())require(entry.getKey().equals(f.spec().sourceServerId().toString())||entry.getKey().equals(f.spec().targetServerId().toString()),"BOOTSTRAP_TARGET_THIRD_HOST_SSH_ROUTE_REQUIRED");
        for(var entry:Map.of(f.spec().sourceServerId(),source,f.spec().targetServerId(),target).entrySet()){
            var row=entry.getValue();String logical=ControlSsh.host(row.get("host").toString());int port=((Number)row.get("port")).intValue();ControlSsh.port(port);
            if(!loopback(logical))continue;
            boolean owner=entry.getKey().equals(f.spec().sourceServerId());int physical=port;
            if(owner){require(remotePorts.add(port)&&localPorts.add(28300),"BOOTSTRAP_TARGET_ADMIN_PORT_COLLISION");physical=28300;forwards.add(forward("SOURCE_ADMIN_SSH",physical,port));}
            routes.put(entry.getKey().toString(),Map.of("host","127.0.0.1","port",physical,"logicalHost",logical,"logicalPort",port,"hostKey",ControlSsh.key(row.get("hostKey").toString()).get("publicKey")));
        }
        if(f.review()!=null&&f.review().get("httpPort") instanceof Number business)
            require(forwards.stream().noneMatch(row->((Integer)row.get("localPort")).intValue()==business.intValue()),"BOOTSTRAP_TARGET_BUSINESS_PORT_COLLISION");
        return new Planned(prepared,output,forwards,routes,authorityOrigin);
    }
}
