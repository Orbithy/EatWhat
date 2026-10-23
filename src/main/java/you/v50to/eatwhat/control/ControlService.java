package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Hub access is limited to operator-configured nodes/releases. Requests never supply URLs, paths or commands. */
public final class ControlService {
    private final ControlProperties config;
    private final ControlAudit audit;
    private final DeploymentExecutor deployment;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient client;
    public ControlService(ControlProperties config,ControlAudit audit) {
        this(config,audit,new DeploymentExecutor(config,audit));
    }
    public ControlService(ControlProperties config,ControlAudit audit,DeploymentExecutor deployment) {
        this.client=you.v50to.eatwhat.config.NodeHttpClients.create(config.getNodeCaFile());this.config=config;this.audit=audit;this.deployment=deployment;
        if(!config.getNodes().isEmpty() && !config.getNodes().keySet().equals(Set.of("campus","external"))) throw new ControlFailure(503,"EXACT_NODE_PAIR_REQUIRED");
        config.getNodes().forEach((key,n)->{
            identifier(n.getSiteId());
            if(n.getInstanceId()==null || n.getInstanceId().equals(new UUID(0,0))) throw new ControlFailure(503,"NODE_IDENTITY_REQUIRED");
            nodeBase(n.getOrigin(),n.getApiPrefix());ControlAudit.token(n.getTokenFile());
            n.getChannels().forEach((channel,maximum)->{identifier(channel);if(maximum==null || maximum<1 || maximum>300)throw new ControlFailure(503,"CHANNEL_CATALOG_INVALID");});
        });
        if(config.getNodes().size()==2) {
            var a=config.getNodes().get("campus");var b=config.getNodes().get("external");
            if(a.getSiteId().equals(b.getSiteId()) || a.getInstanceId().equals(b.getInstanceId()) || a.getOrigin().equals(b.getOrigin())) throw new ControlFailure(503,"DISTINCT_NODES_REQUIRED");
        }
        config.getReleases().forEach((id,r)->{identifier(id);if(r.getArtifact()==null || !r.getArtifact().isAbsolute() || r.getSha256()==null || !r.getSha256().matches("[0-9a-f]{64}")) throw new ControlFailure(503,"RELEASE_MANIFEST_INVALID");});
    }
    public static void identifier(String value){if(value==null || !value.matches("[a-zA-Z0-9_-]{1,64}")) throw new ControlFailure(400,"INVALID_IDENTIFIER");}
    public static URI origin(String value) {
        try {
            URI u=URI.create(value);
            boolean loopback="http".equals(u.getScheme()) && "127.0.0.1".equals(u.getHost());
            if(!(loopback || "https".equals(u.getScheme())) || u.getHost()==null || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null || !(u.getPath().isEmpty() || u.getPath().equals("/"))) throw new Exception();
            return URI.create(u.toString().replaceAll("/$",""));
        } catch(Exception e){throw new ControlFailure(503,"NODE_ORIGIN_INVALID");}
    }
    /** Operator-owned reverse-proxy prefix, separate from the credential-free HTTPS origin. */
    public static String apiPrefix(String value) {
        if(value==null||value.isEmpty()||value.equals("/"))return "";
        if(value.length()>128||!value.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?"))throw new ControlFailure(503,"NODE_API_PREFIX_INVALID");
        return value.endsWith("/")?value.substring(0,value.length()-1):value;
    }
    public static URI nodeBase(String value,String prefix){return URI.create(origin(value)+apiPrefix(prefix));}
    Map<String,Object> remote(String key,String path,Map<String,Object> body) {
        var node=config.getNodes().get(key);if(node==null) throw new ControlFailure(404,"NODE_NOT_CONFIGURED");
        try {
            var request=HttpRequest.newBuilder(URI.create(nodeBase(node.getOrigin(),node.getApiPrefix())+"/internal/control/v1"+path))
                    .timeout(Duration.ofSeconds(5)).header("X-Control-Node-Token",ControlAudit.token(node.getTokenFile()));
            if(body==null) request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body()) {
                byte[] bytes=input.readNBytes(65537);
                if(response.statusCode()!=200 || bytes.length>65536) throw new Exception();
                return json.readValue(bytes,Map.class);
            }
        } catch(Exception e){throw new ControlFailure(503,"NODE_REQUEST_FAILED");}
    }
    public Map<String,Object> sites() {
        var nodes=new LinkedHashMap<String,Object>();
        for(String key:List.of("campus","external")) {
            try {
                var snapshot=remote(key,"/snapshot",null);verify(key,snapshot);nodes.put(key,snapshot);
            } catch(ControlFailure e) {nodes.put(key,Map.of("observationStatus","UNKNOWN","reason",e.code,"observedAt",Instant.now().toString()));}
        }
        String pair="UNVERIFIED";
        var a=(Map<?,?>)nodes.get("campus");var b=(Map<?,?>)nodes.get("external");
        if("OBSERVED".equals(a.get("observationStatus")) && "OBSERVED".equals(b.get("observationStatus"))) {
            var da=(Map<?,?>)a.get("database");var db=(Map<?,?>)b.get("database");
            pair=Objects.equals(da.get("databaseFingerprint"),db.get("databaseFingerprint"))?"SAME_DATABASE_REJECTED":"VERIFIED_DISTINCT";
        }
        return Map.of("observedAt",Instant.now().toString(),"pairStatus",pair,"sites",nodes);
    }
    void verify(String key,Map<String,Object> snapshot) {
        var expected=config.getNodes().get(key);
        if(!Objects.equals(expected.getSiteId(),snapshot.get("siteId")) || !Objects.equals(String.valueOf(expected.getInstanceId()),String.valueOf(snapshot.get("instanceId")))
                || !Objects.equals(key.equals("campus")?"CAMPUS":"EXTERNAL",snapshot.get("mode"))) throw new ControlFailure(409,"NODE_IDENTITY_MISMATCH");
        try {if(Duration.between(Instant.parse((String)snapshot.get("observedAt")),Instant.now()).abs().compareTo(Duration.ofSeconds(30))>0) throw new Exception();}
        catch(Exception e){throw new ControlFailure(409,"STALE_NODE_OBSERVATION");}
        if(!Set.of("OBSERVED","UNKNOWN").contains(Objects.toString(snapshot.get("observationStatus"),""))) throw new ControlFailure(409,"INVALID_NODE_OBSERVATION");
        if("OBSERVED".equals(snapshot.get("observationStatus"))) {
            if(!(snapshot.get("database") instanceof Map<?,?> db) || !(db.get("databaseFingerprint") instanceof String fp) || !fp.matches("[0-9a-f]{32}")) throw new ControlFailure(409,"INVALID_DATABASE_OBSERVATION");
        }
    }
    public Map<String,Object> fault(String actor,String site,String channel,String action,int seconds) {
        if(!"isolated-acceptance".equals(config.getEnvironmentKind()))throw new ControlFailure(403,"FAULTS_REQUIRE_ISOLATED_ENVIRONMENT");
        identifier(channel);if(!Set.of("BLOCK","RESTORE").contains(action) || seconds<1 || seconds>300) throw new ControlFailure(400,"INVALID_FAULT_REQUEST");
        if(action.equals("BLOCK")) {
            var pair=sites();if(!pair.get("pairStatus").equals("VERIFIED_DISTINCT")) throw new ControlFailure(409,"PAIR_NOT_VERIFIED");
            var observed=(Map<?,?>)((Map<?,?>)pair.get("sites")).get(site);
            if(observed==null || !(observed.get("channels") instanceof Map<?,?> channels) || !(channels.get(channel) instanceof Map<?,?> state))throw new ControlFailure(404,"CHANNEL_NOT_CONFIGURED");
            if(!(state.get("maximumBlockSeconds") instanceof Number maximum) || seconds>maximum.intValue())throw new ControlFailure(400,"INVALID_FAULT_DURATION");
        }
        UUID request=UUID.randomUUID();audit.append(request,actor,action,site+":"+channel,"REQUESTED",Map.of("seconds",seconds));
        try {
            var result=remote(site,"/faults",Map.of("requestId",request.toString(),"channel",channel,"action",action,"seconds",seconds));
            audit.append(request,actor,action,site+":"+channel,"APPLIED");return Map.of("requestId",request,"channel",result);
        } catch(ControlFailure e){audit.append(request,actor,action,site+":"+channel,"OUTCOME_UNKNOWN");throw e;}
    }
    public List<Map<String,Object>> releases() {
        return config.getReleases().entrySet().stream().map(e->{var r=e.getValue();return Map.<String,Object>of("id",e.getKey(),"sha256",r.getSha256(),"acceptanceEvidenceId",Objects.toString(r.getAcceptanceEvidenceId(),""));}).toList();
    }
    public Map<String,Object> channelCatalog() {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("campus","external")) {
            var node=config.getNodes().get(key);
            List<Map<String,Object>> channels=node==null?List.of():node.getChannels().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e->Map.<String,Object>of("name",e.getKey(),"maximumBlockSeconds",e.getValue(),"source","HUB_CONFIG","state","NOT_OBSERVED","routingVerified",false)).toList();
            result.put(key,channels);
        }
        return result;
    }
    public Map<String,Object> plan(String actor,String releaseId,List<String> nodes) {
        if(nodes==null || nodes.size()!=2 || !new HashSet<>(nodes).equals(Set.of("campus","external"))) throw new ControlFailure(400,"BOTH_SITES_REQUIRED");
        var release=config.getReleases().get(releaseId);if(release==null) throw new ControlFailure(404,"RELEASE_NOT_CONFIGURED");
        String actual;
        try(var stream=Files.newInputStream(release.getArtifact())) {
            var digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];int n;
            while((n=stream.read(bytes))!=-1) digest.update(bytes,0,n);
            actual=HexFormat.of().formatHex(digest.digest());
        } catch(Exception e){throw new ControlFailure(409,"ARTIFACT_UNAVAILABLE");}
        if(!actual.equals(release.getSha256())) throw new ControlFailure(409,"ARTIFACT_SHA256_MISMATCH");
        UUID id=UUID.randomUUID();
        var result=new LinkedHashMap<>(Map.<String,Object>of("id",id.toString(),"createdAt",Instant.now().toString(),"releaseId",releaseId,"artifactSha256",actual,
                "sites",nodes,"state","PREVIEW_ONLY","executionEnabled",deployment.enabled() && deployment.blockers(releaseId,targets(nodes)).isEmpty(),"observations",sites(),
                "blockers",deployment.blockers(releaseId,targets(nodes)),
                "steps",List.of("VERIFY_IDENTITIES_AND_SAME_ARTIFACT","VERIFY_BACKUP_RESTORE_AND_MIGRATION_COMPATIBILITY","RENDER_COMPOSE_PRIVATELY","DEPLOY_ONE_SITE_AND_CHECK_READINESS","VERIFY_RUNTIME_ARTIFACT_AND_READINESS","RECORD_RELEASE_OR_APPLICATION_ROLLBACK")));
        result.put("targets",targets(nodes));result.put("configurationHash",deployment.configurationHash(releaseId,targets(nodes)));
        audit.append(id,actor,"DEPLOYMENT_PLAN",releaseId,"PREVIEW_ONLY");save("plan-"+id,result);return result;
    }
    void save(String name,Object value) {
        try {Path tmp=Files.createTempFile(audit.directory(),"control-",".tmp",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            try {Files.writeString(tmp,json.writeValueAsString(value));Files.move(tmp,audit.directory().resolve(name+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            finally{Files.deleteIfExists(tmp);}
        } catch(Exception e){throw new ControlFailure(503,"CONTROL_STATE_UNAVAILABLE");}
    }
    Map<String,Object> loadPlan(UUID id) {
        try{return json.readValue(Files.readString(audit.directory().resolve("plan-"+id+".json")),Map.class);}
        catch(Exception e){throw new ControlFailure(404,"PLAN_NOT_FOUND");}
    }
    public boolean deploymentEnabled(){return deployment.enabled();}
    public boolean deploymentRecoveryEnabled(){return deployment.recoverySupported();}
    public String environmentKind(){return config.getEnvironmentKind();}
    private List<DeploymentExecutor.Target> targets(List<String> keys){return keys.stream().map(key->{var node=config.getNodes().get(key);return new DeploymentExecutor.Target(key,node==null?key:node.getSiteId(),node==null?new UUID(0,0):node.getInstanceId());}).toList();}
    @SuppressWarnings("unchecked") public Map<String,Object> execute(String actor,UUID id) {
        var plan=loadPlan(id);var nodes=(List<String>)plan.get("sites");
        if(deployment.enabled() && (!Boolean.TRUE.equals(plan.get("executionEnabled")) || !Objects.equals(plan.get("configurationHash"),deployment.configurationHash((String)plan.get("releaseId"),targets(nodes)))))throw new ControlFailure(409,"DEPLOYMENT_PLAN_RECREATE_REQUIRED");
        return deployment.execute(actor,id,(String)plan.get("releaseId"),(String)plan.get("artifactSha256"),targets(nodes),(String)plan.get("configurationHash"),()->{
            if(!"VERIFIED_DISTINCT".equals(sites().get("pairStatus")))throw new ControlFailure(409,"PAIR_NOT_VERIFIED");
        });
    }
    public Object deployment(UUID id){return deployment.get(id);}
    public Object reconcileDeployment(String actor,UUID id){return deployment.reconcile(actor,id);}
    public List<Object> audit(int limit) {
        if(limit<1 || limit>200) throw new ControlFailure(400,"INVALID_LIMIT");
        try {var p=audit.directory().resolve("audit.jsonl");if(!Files.exists(p)) return List.of();
            var tail=new ArrayDeque<Object>();try(var lines=Files.lines(p)){lines.forEach(line->{if(tail.size()==limit)tail.removeFirst();tail.addLast(json.readValue(line,Object.class));});}return new ArrayList<>(tail);
        } catch(Exception e){throw new ControlFailure(503,"AUDIT_UNAVAILABLE");}
    }
}
