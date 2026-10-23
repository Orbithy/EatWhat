package you.v50to.eatwhat.control;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;

/** Administrator-only server inventory; never returns credentials or remote stdout. */
public final class ControlServers {
    private final ControlCredentials credentials;private final ControlServerStore store;private final ControlSsh ssh;private final DeploymentExecutor deployment;private final ControlAudit audit;private final SiteProperties site;private final ObjectProvider<ClusterProperties> cluster;private final ObjectProvider<ClusterControlLease> lease;
    public ControlServers(ControlServerStore store,ControlSsh ssh,DeploymentExecutor deployment,ControlAudit audit,SiteProperties site,ObjectProvider<ClusterProperties> cluster,ObjectProvider<ClusterControlLease> lease){this(store,ssh,deployment,audit,site,cluster,lease,new ControlCredentials(new ControlProperties()));}
    ControlServers(ControlServerStore store,ControlSsh ssh,DeploymentExecutor deployment,ControlAudit audit,SiteProperties site,ObjectProvider<ClusterProperties> cluster,ObjectProvider<ClusterControlLease> lease,ControlCredentials credentials){this.credentials=credentials;this.store=store;this.ssh=ssh;this.deployment=deployment;this.audit=audit;this.site=site;this.cluster=cluster;this.lease=lease;}
    public List<Map<String,String>> credentials(){return credentials.metadata();}
    public void requireWrite(){if(site.getMode()==SiteProperties.Mode.EXTERNAL)throw new ControlFailure(409,"SERVER_AUTHORITY_REQUIRED");var c=cluster.getIfAvailable();if(c!=null&&c.isEnabled()&&!c.isCoordinator())throw new ControlFailure(409,"SERVER_AUTHORITY_REQUIRED");var l=lease.getIfAvailable();if(l!=null)l.requireAccepting();}
    public void requireExecuting(){if(site.getMode()==SiteProperties.Mode.EXTERNAL)throw new ControlFailure(409,"SERVER_AUTHORITY_REQUIRED");var l=lease.getIfAvailable();if(l!=null)l.requireHeld();}
    public String reason(){try{requireWrite();return "";}catch(ControlFailure f){return f.code;}catch(ClusterFailure f){return f.getMessage();}}
    @SuppressWarnings("unchecked") public List<Map<String,Object>> list(){return ((Map<String,Object>)store.read().get("servers")).values().stream().map(value->view((Map<String,Object>)value)).sorted(Comparator.comparing(s->s.get("name").toString())).toList();}
    public static long revision(Map<String,Object> s){return ((Number)s.get("revision")).longValue();}
    public static void expected(Map<String,Object> s,long revision){if(revision(s)!=revision)throw new ControlFailure(409,"SERVER_REVISION_CHANGED");}
    public static Map<String,Object> view(Map<String,Object> server){var v=new LinkedHashMap<String,Object>();for(String key:List.of("id","name","host","port","username","location","authentication","revision","connection","installation","clusterJobId","createdAt","updatedAt"))if(server.containsKey(key))v.put(key,server.get(key));v.put("privilege",ControlSsh.privilege(server));v.put("hasCredential",server.containsKey("credential"));v.put("credentialSource",server.getOrDefault("credentialSource","MANUAL"));if(server.containsKey("credentialId"))v.put("credentialId",server.get("credentialId"));v.put("hostKey",ControlSsh.key((String)server.get("hostKey")));return v;}
    @SuppressWarnings("unchecked") public Map<String,Object> save(String actor,UUID id,ControlServerController.ServerInput input,boolean create){
        requireWrite();validate(input);var result=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
        String blocker=deployment.whenIdle(()->result.set(store.mutate(data->{
            requireWrite();var servers=(Map<String,Object>)data.get("servers");var requests=(Map<String,Object>)data.get("requests");String request=input.requestId().toString();Object remembered=requests.get(request);String digest=store.fingerprint(toJson(input));
            String fingerprint=ControlSsh.key(input.hostKey()).get("fingerprint");if(!fingerprint.equals(input.confirmedFingerprint()))throw new ControlFailure(400,"SSH_HOST_KEY_CONFIRMATION_REQUIRED");
            var previous=(Map<String,Object>)servers.get(id.toString());
            if(remembered!=null){if(!(remembered instanceof Map<?,?> prior)||!id.toString().equals(prior.get("id"))||!digest.equals(prior.get("hash"))||previous==null||revision(previous)!=((Number)prior.get("revision")).longValue())throw new ControlFailure(409,"IDEMPOTENCY_CONFLICT");return view(previous);}
            if(create&&previous!=null)throw new ControlFailure(409,"SERVER_ALREADY_EXISTS");if(!create&&previous==null)throw new ControlFailure(404,"SERVER_NOT_FOUND");if(previous!=null)expected(previous,input.expectedRevision()==null?-1:input.expectedRevision());
            if(previous==null&&servers.size()>=64)throw new ControlFailure(409,"SERVER_LIMIT_REACHED");
            String credential=input.credential();boolean reference=input.credentialId()!=null;if(reference)credential=credentials.resolve(input.credentialId(),input.authentication());boolean unchanged=credential==null||credential.isEmpty();
            if(unchanged&&(previous==null||!previous.get("authentication").equals(input.authentication())))throw new ControlFailure(400,"SSH_CREDENTIAL_REQUIRED");
            for(var value:servers.values()){var other=(Map<String,Object>)value;if(!other.get("id").equals(id.toString())&&other.get("host").equals(input.host())&&((Number)other.get("port")).intValue()==input.port()&&other.get("username").equals(input.username()))throw new ControlFailure(409,"SSH_SERVER_ALREADY_REGISTERED");}
            var server=new LinkedHashMap<String,Object>();server.put("id",id.toString());server.put("name",input.name().strip());server.put("host",input.host());server.put("port",input.port());server.put("username",input.username());server.put("privilege",input.privilege()==null?(previous==null?"NONE":ControlSsh.privilege(previous)):input.privilege());server.put("location",input.location());server.put("authentication",input.authentication());server.put("hostKey",ControlSsh.key(input.hostKey()).get("publicKey"));server.put("credential",unchanged?previous.get("credential"):store.encrypt(credential));server.put("revision",previous==null?1:revision(previous)+1);server.put("connection",Map.of("state","NOT_TESTED"));server.put("installation",previous==null?Map.of("state","NOT_INSTALLED"):previous.get("installation"));server.put("createdAt",previous==null?Instant.now().toString():previous.get("createdAt"));server.put("updatedAt",Instant.now().toString());
            if(reference){server.put("credentialSource","OPERATOR_INVENTORY");server.put("credentialId",input.credentialId());}else if(unchanged){server.put("credentialSource",previous.getOrDefault("credentialSource","MANUAL"));if(previous.containsKey("credentialId"))server.put("credentialId",previous.get("credentialId"));}else server.put("credentialSource","MANUAL");
            if(previous!=null&&previous.containsKey("clusterJobId"))server.put("clusterJobId",previous.get("clusterJobId"));
            if(previous!=null&&!Objects.equals(((Map<?,?>)previous.get("installation")).get("state"),"NOT_INSTALLED")&&(!previous.get("host").equals(input.host())||!previous.get("port").equals(input.port())||!previous.get("location").equals(input.location())))throw new ControlFailure(409,"INSTALLED_SERVER_ADDRESS_IMMUTABLE");
            audit.append(input.requestId(),actor,create?"SERVER_ADD":"SERVER_EDIT",id.toString(),"ACCEPTED");servers.put(id.toString(),server);requests.put(request,Map.of("id",id.toString(),"hash",digest,"revision",server.get("revision")));while(requests.size()>1024)requests.remove(requests.keySet().iterator().next());return view(server);
        })));
        if(blocker!=null)throw new ControlFailure(409,blocker);return result.get();
    }
    private static String toJson(Object value){try{
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();String encoded=mapper.writeValueAsString(value);
        // Preserve the exact request proof used by older clients before this optional field existed.
        if(value instanceof ControlServerController.ServerInput input){var prior=mapper.readValue(encoded,LinkedHashMap.class);if(input.privilege()==null)prior.remove("privilege");if(input.credentialId()==null)prior.remove("credentialId");return mapper.writeValueAsString(prior);}
        return encoded;
    }catch(Exception e){throw new ControlFailure(400,"INVALID_SERVER_INPUT");}}
    static void validate(ControlServerController.ServerInput s){
        if(s.credentialId()!=null&&(!ControlCredentials.validId(s.credentialId())||!"PRIVATE_KEY".equals(s.authentication())||(s.credential()!=null&&!s.credential().isEmpty())))throw new ControlFailure(400,"INVALID_SSH_CREDENTIAL_REFERENCE");
        ControlSsh.host(s.host());ControlSsh.port(s.port());ControlSsh.user(s.username());if(s.privilege()!=null&&!Set.of("NONE","SUDO").contains(s.privilege()))throw new ControlFailure(400,"INVALID_SSH_PRIVILEGE");if(s.name()==null||s.name().isBlank()||s.name().length()>64||s.name().chars().anyMatch(Character::isISOControl))throw new ControlFailure(400,"INVALID_SERVER_NAME");
        if(!Set.of("REMOTE","CONTROL_HOST").contains(s.location())||!Set.of("PASSWORD","PRIVATE_KEY").contains(s.authentication()))throw new ControlFailure(400,"INVALID_SERVER_INPUT");
        if(s.location().equals("CONTROL_HOST")&&!Set.of("127.0.0.1","localhost","::1").contains(s.host()))throw new ControlFailure(400,"CONTROL_HOST_LOOPBACK_REQUIRED");
        if(s.credential()!=null&&!s.credential().isEmpty()){
            if(s.authentication().equals("PASSWORD")&&(s.credential().length()>256||s.credential().contains("\n")||s.credential().contains("\r")||s.credential().indexOf(0)>=0))throw new ControlFailure(400,"INVALID_SSH_CREDENTIAL");
            if(s.authentication().equals("PRIVATE_KEY")&&(s.credential().length()>16384||!s.credential().startsWith("-----BEGIN ")||!s.credential().contains("PRIVATE KEY-----")||s.credential().indexOf(0)>=0))throw new ControlFailure(400,"INVALID_SSH_CREDENTIAL");
        }
    }
    public List<Map<String,String>> hostKeys(String host,int port){requireWrite();return ssh.scan(host,port);}
    @SuppressWarnings("unchecked") public Map<String,Object> test(String actor,UUID id,long revision){
        requireWrite();var server=store.server(id);expected(server,revision);Map<String,Object> connection;
        try{var result=ssh.call(server,resource("server-probe.py"),List.of(),out->{},45);if(!"eatwhat-server-probe-v1".equals(result.get("protocol")))throw new Exception();var c=new LinkedHashMap<String,Object>();c.put("state","CONNECTED");c.put("checkedAt",Instant.now().toString());for(String k:List.of("linux","python","docker","compose","dockerAccess","root","memoryMiB","diskFreeMiB"))if(result.containsKey(k))c.put(k,result.get(k));connection=c;}
        catch(Exception e){connection=Map.of("state","FAILED","code","SSH_CONNECTION_FAILED","checkedAt",Instant.now().toString());}
        var finalConnection=connection;return store.mutate(data->{requireWrite();var current=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(id.toString());if(current==null)throw new ControlFailure(404,"SERVER_NOT_FOUND");expected(current,revision);current.put("connection",finalConnection);audit.append(UUID.randomUUID(),actor,"SERVER_TEST",id.toString(),finalConnection.get("state").toString());return view(current);});
    }
    @SuppressWarnings("unchecked") public void delete(String actor,UUID id,long revision){requireWrite();String blocker=deployment.whenIdle(()->store.mutate(data->{var servers=(Map<String,Object>)data.get("servers");var current=(Map<String,Object>)servers.get(id.toString());if(current==null)throw new ControlFailure(404,"SERVER_NOT_FOUND");expected(current,revision);audit.append(UUID.randomUUID(),actor,"SERVER_REMOVE",id.toString(),"ACCEPTED_INVENTORY_ONLY");servers.remove(id.toString());scrubServerPlans(id);return null;}));if(blocker!=null)throw new ControlFailure(409,blocker);}
    @SuppressWarnings("unchecked") private void scrubServerPlans(UUID id){try(var files=Files.list(store.directory())){
        for(Path file:files.filter(p->p.getFileName().toString().matches("server-(?:cluster-)?(?:plan|job)-[0-9a-f-]{36}\\.json")).toList()){
            var data=store.readJson(file);if(!id.toString().equals(data.get("serverId")))continue;
            data.remove("_server");data.remove("_payload");if(data.get("_plan") instanceof Map<?,?> plan){((Map<String,Object>)plan).remove("_server");((Map<String,Object>)plan).remove("_payload");}store.saveJson(file,data);
        }
    }catch(ControlFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}}
    public static String resource(String name){try(var in=ControlServers.class.getResourceAsStream("/control/"+name)){if(in==null)throw new Exception();return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new ControlFailure(503,"SERVER_AGENT_UNAVAILABLE");}}
}
