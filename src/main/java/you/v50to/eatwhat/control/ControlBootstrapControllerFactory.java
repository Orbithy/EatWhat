package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Server-owned controller input generation after the original dependency
 * chain succeeds. Never accepts a browser property map or regenerates a claimed
 * phase's missing inputs. Endpoint generation is not a connectivity proof. */
public final class ControlBootstrapControllerFactory {
    static final String ROUTES="bootstrap-target-ssh-routes-v1.json";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlBootstrapTargetNetwork network;private final ControlBootstrapDependencyPhases dependencies;
    private final ControlBootstrapNativePhases nativePhases;private final ControlBootstrapNativeInputs inputs;private final ControlBootstrapJournal journal;
    public ControlBootstrapControllerFactory(ControlBootstrapTargetNetwork network,ControlBootstrapDependencyPhases dependencies,
            ControlBootstrapNativePhases nativePhases,ControlBootstrapNativeInputs inputs,ControlBootstrapJournal journal){
        this.network=network;this.dependencies=dependencies;this.nativePhases=nativePhases;this.inputs=inputs;this.journal=journal;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    public void ensure(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);var job=journal.job(f.spec().id());String approval=job.get("requestHash").toString();
        var step=(Map<?,?>)((List<?>)job.get("steps")).get(CONTROLLER_CONFIG.ordinal());
        inputs.prepare(f.spec().id(),CONTROLLER_CONFIG,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute(),
                "PENDING".equals(step.get("state")),()->prepare(f,approval));
    }
    private ControlBootstrapNativeInputs.Prepared prepare(ControlBootstrapAdmission.Frozen f,String approval){
        var dependency=dependencies.completedTarget(f,approval);var plan=network.plan(f);
        require(ControlBootstrapNativeJson.encode(plan.forwards()).equals(ControlBootstrapNativeJson.encode(dependency.header().get("forwards"))),"BOOTSTRAP_TARGET_DEPENDENCY_PLAN_CHANGED");
        var bundle=bundle(f,plan);var authority=f.sourceRequest().expected();
        var sourceAuthority=Map.of("clusterId",authority.clusterId().toString(),"siteId",authority.authoritySiteId(),
                "instanceId",authority.authorityInstanceId().toString(),"databaseFingerprint",authority.authorityDatabaseFingerprint());
        var header=new LinkedHashMap<String,Object>();header.put("id",ControlBootstrapJournal.phaseId(f.spec().id(),CONTROLLER_CONFIG).toString());
        header.put("verifyId",ControlBootstrapJournal.phaseId(f.spec().id(),CONTROLLER_VERIFY).toString());header.put("approvalDigest",approval);
        header.put("dependencyId",dependency.header().get("id"));header.put("dependencyBinding",dependency.binding());header.put("dependencyAgentSha256",dependency.header().get("agentSha256"));
        header.put("sourceAuthority",sourceAuthority);header.put("bundle",bundle);
        validateLocal(f,bundle,plan.forwards(),sourceAuthority);
        String agent=ControlBootstrapNativePhases.expanded("controller-bootstrap-controller.py","__EATWHAT_CONTROLLER_CONTROLLER_POLICY_BASE64__",ControlServers.resource("controller-bootstrap-controller-policy.py"));
        var inspected=nativePhases.call(nativePhases.server(f,CONTROLLER_CONFIG),agent,List.of("inspect"),header);
        require(CONTROLLER_CONFIG.protocol.equals(inspected.get("protocol"))&&header.get("id").equals(inspected.get("id"))
                &&Boolean.FALSE.equals(inspected.get("effectsStarted"))&&inspected.get("proof") instanceof String proof&&proof.matches("[a-f0-9]{64}"),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        header.put("expectedProof",inspected.get("proof"));header.put("agentSha256",ControlBootstrapNativeJson.sha(agent));return new ControlBootstrapNativeInputs.Prepared(header,agent);
    }
    Map<String,Object> bundle(ControlBootstrapAdmission.Frozen f,ControlBootstrapTargetNetwork.Planned plan){
        var source=network.sourceProperties(f);var values=new TreeMap<>(source);values.putAll(journal.targetStorageProperties(f));
        require(!source.containsKey("spring.data.redis.ssl.bundle")&&!source.keySet().stream().anyMatch(key->key.startsWith("spring.ssl.bundle.")),"BOOTSTRAP_TARGET_TLS_BUNDLE_MATERIAL_REQUIRED");
        require(!Boolean.parseBoolean(source.get("spring.data.redis.ssl.enabled"))||source.getOrDefault("spring.data.redis.host","localhost").equals("127.0.0.1"),"BOOTSTRAP_TARGET_TLS_HOST_ROUTE_REQUIRED");
        values.keySet().removeIf(key->key.startsWith("spring.flyway.")||key.startsWith("spring.sql.init.")||key.startsWith("app.control.proxies.")
                ||key.startsWith("app.cluster.profiles.")||key.startsWith("app.control.controller-bootstrap.")||key.startsWith("app.control.releases.")||key.startsWith("app.control.releases[")
                ||key.startsWith("app.control.deployment.targets.")||Set.of("app.control.deployment.identity-file","app.control.deployment.known-hosts-file").contains(key)
                ||key.startsWith("management.")||key.startsWith("server.ssl.")||key.startsWith("logging.file.")||key.startsWith("logging.logback."));
        values.putAll(plan.materials().pathOverrides());var a=f.sourceRequest().expected();String name="control-"+f.spec().targetServerId().toString().replace("-","").substring(0,24);
        values.putAll(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","18081"),Map.entry("server.servlet.context-path",""),Map.entry("spring.mvc.servlet.path","/"),
                Map.entry("app.control.enabled","true"),Map.entry("app.control.node-enabled","true"),Map.entry("app.control.environment-kind","managed-deployment"),
                Map.entry("app.control.state-directory","/opt/eatwhat-control/state"),Map.entry("app.control.shared-state-required","true"),Map.entry("app.cluster.enabled","true"),Map.entry("app.cluster.coordinator","true"),
                Map.entry("app.cluster.single-site-only","false"),Map.entry("app.cluster.control-lease.enabled","true"),Map.entry("app.cluster.control-lease.candidate","true"),
                Map.entry("app.cluster.control-lease.automatic-takeover","false"),Map.entry("app.cluster.control-lease.controller-name",name),Map.entry("app.site.cluster-id",a.clusterId().toString()),
                Map.entry("app.site.id",a.authoritySiteId()),Map.entry("app.site.instance-id",a.authorityInstanceId().toString()),Map.entry("app.site.mode","CAMPUS"),Map.entry("app.site.allocation-slot","0"),
                Map.entry("app.control.ui-enabled","true"),Map.entry("spring.mvc.static-path-pattern","/control-ui/**"),
                Map.entry("spring.web.resources.static-locations","file:/srv/eatwhat-control-releases/ui-"+f.artifacts().ui().sha256()+"/"),
                Map.entry("spring.flyway.enabled","true"),Map.entry("spring.sql.init.mode","never"),Map.entry("app.control.database-mode","existing-authority"),
                Map.entry("app.control.authority-database-fingerprint",a.authorityDatabaseFingerprint()),Map.entry("app.control.deployment.authority-origin",plan.authorityOrigin()),
                Map.entry("app.control.deployment.ssh-executable","/usr/bin/ssh"),Map.entry("app.control.servers.keyscan-executable","/usr/bin/ssh-keyscan"),
                Map.entry("spring.data.redis.host","127.0.0.1"),Map.entry("spring.data.redis.port","16379"),Map.entry("management.endpoints.web.exposure.include","health"),
                Map.entry("management.endpoint.health.probes.enabled","true"),Map.entry("management.endpoint.health.show-details","never"),Map.entry("management.endpoint.health.group.readiness.include","readinessState,db,redis")));
        values.putAll(ControlBootstrapControllerCatalog.properties(f.artifacts()));
        URI database=ControlBootstrapTargetNetwork.database(values);String query=database.getRawQuery();
        if(query!=null)for(String part:query.split("&")){
            var pair=part.split("=",2);if(URLDecoder.decode(pair[0],StandardCharsets.UTF_8).equals("sslmode")&&pair.length==2&&URLDecoder.decode(pair[1],StandardCharsets.UTF_8).equals("verify-full"))
                require(database.getHost().equals("127.0.0.1"),"BOOTSTRAP_TARGET_TLS_HOST_ROUTE_REQUIRED");
        }
        values.put("spring.datasource.url","jdbc:postgresql://127.0.0.1:25432"+database.getRawPath()+(query==null?"":"?"+query));
        var files=new TreeMap<>(plan.materials().privateFiles());require(!files.containsKey(ROUTES),"BOOTSTRAP_TARGET_PRIVATE_FILENAME_COLLISION");
        files.put("cluster-profiles.json",Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(plan.profiles())));
        files.put(ROUTES,Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(Map.of("version",1,"routes",plan.sshRoutes()))));values.put("app.control.servers.ssh-routes-file","/opt/eatwhat-control/config/"+ROUTES);
        var bundle=new LinkedHashMap<String,Object>();bundle.put("clusterId",a.clusterId().toString());bundle.put("authoritySiteId",a.authoritySiteId());bundle.put("authorityInstanceId",a.authorityInstanceId().toString());
        bundle.put("authorityDatabaseFingerprint",a.authorityDatabaseFingerprint());bundle.put("controllerName",name);bundle.put("controllerSha256",f.artifacts().controller().sha256());bundle.put("controllerSize",f.artifacts().controller().size());
        bundle.put("uiSha256",f.artifacts().ui().sha256());bundle.put("uiSize",f.artifacts().ui().size());bundle.put("uiFiles",f.artifacts().uiFiles());bundle.put("catalog",ControlBootstrapControllerCatalog.metadata(f.artifacts()));
        bundle.put("properties",values);bundle.put("privateFiles",files);return bundle;
    }
    private static void validateLocal(ControlBootstrapAdmission.Frozen f,Map<String,Object> bundle,List<Map<String,Object>> forwards,Map<String,String> authority){
        try{
            String program="import base64,json,pathlib,sys\nn={};h=json.load(sys.stdin);exec(compile(base64.b64decode(h['policy']),'<policy>','exec'),n)\ntry:\n n['validate_bundle'](h['bundle'],h['prior'],h['forwards'],h['authority'])\n n['verify_jar'](pathlib.Path(h['jar']),h['bundle'])\n print(json.dumps({'valid':True}))\nexcept Exception as failure:\n print(json.dumps({'valid':False,'code':getattr(failure,'code','BOOTSTRAP_TARGET_CONFIGURATION_INVALID')}))\n";
            var request=Map.of("policy",Base64.getEncoder().encodeToString(ControlServers.resource("controller-bootstrap-controller-policy.py").getBytes(StandardCharsets.UTF_8)),"bundle",bundle,
                    "prior",f.targetInitial().request(),"forwards",forwards,"authority",authority,"jar",f.artifacts().controller().path());
            byte[] input=JSON.writeValueAsBytes(request);require(input.length<=2*1024*1024,"BOOTSTRAP_TARGET_CONFIGURATION_TOO_LARGE");
            byte[] raw=ControlSsh.process(List.of("/usr/bin/python3","-I","-c",program),Map.of(),out->out.write(input),30,1024,true);var result=JSON.readValue(raw,Map.class);
            if(!Boolean.TRUE.equals(result.get("valid"))){String code=Objects.toString(result.get("code"),"");throw new ControlFailure(409,code.matches("BOOTSTRAP_[A-Z0-9_]{1,110}")?code:"BOOTSTRAP_TARGET_CONFIGURATION_INVALID");}
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_TARGET_CONFIGURATION_UNAVAILABLE");}
    }
}
